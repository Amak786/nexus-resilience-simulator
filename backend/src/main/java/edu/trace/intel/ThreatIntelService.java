package edu.trace.intel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.trace.algorithms.bloom.BloomFilter;
import edu.trace.algorithms.rbtree.RedBlackTree;
import edu.trace.net.IpAddr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * External threat intelligence, refreshed on a schedule and served from memory.
 *
 * <ul>
 *   <li>abuse.ch Feodo Tracker IP blocklist (botnet command-and-control servers; JSON; CC0). Screened with a Bloom filter,
 *   every "possibly present" answer is confirmed against the exact map.</li>
 *   <li>Spamhaus DROP (hijacked / criminal netblocks, IPv4 CIDR, NDJSON). Ranges are kept in a red-black tree keyed by the
 *   range start; a lookup is one floor query, O(log n).</li>
 *   <li>RIPEstat prefix-overview (which network announces an address: ASN and holder) - on demand, cached, public addresses only.</li>
 *   <li>AbuseIPDB check API - only when ABUSEIPDB_API_KEY is set on the server; on demand, cached, daily cap.</li>
 * </ul>
 * External calls only go to these fixed hosts, with a validated IP literal as the only variable part (no SSRF).
 */
@Service
public class ThreatIntelService {

    private static final Logger log = LoggerFactory.getLogger(ThreatIntelService.class);
    static final String UA = "trace-network-explorer/1.0 (academic project)";
    static final String FEODO_URL = "https://feodotracker.abuse.ch/downloads/ipblocklist.json";
    static final String DROP_URL = "https://www.spamhaus.org/drop/drop_v4.json";
    static final String RIPE_URL = "https://stat.ripe.net/data/prefix-overview/data.json?sourceapp=trace-academic&resource=";
    static final String ABUSEIPDB_URL = "https://api.abuseipdb.com/api/v2/check?maxAgeInDays=90&ipAddress=";

    @FunctionalInterface
    public interface Http {
        Response send(HttpRequest request) throws IOException, InterruptedException;
    }

    public record Response(int status, String body) {}

    public record FeodoEntry(String ip, int port, String status, String malware, String firstSeen, String lastOnline, Long asNumber,
                             String asName, String country) {}

    public record DropRange(String cidr, String sblId, String rir, long start, long end) {}

    public record AsnInfo(String ip, String prefix, List<String> asns, String lookedUpAt, String source) {}

    public record AbuseInfo(String ip, int abuseConfidenceScore, int totalReports, int distinctUsers, String lastReportedAt,
                            String usageType, String isp, String lookedUpAt) {}

    public record IntelMatch(String list, String detail, String listUpdatedAt, String sourceUrl) {}

    public record FeedStatus(String id, String name, String owner, String endpoint, String licence, String updateFrequency, String kind,
                             String status, String lastAttemptAt, String lastSuccessAt, String feedTimestamp, Long ageMinutes,
                             String error, int records, String notes) {}

    public record BloomStats(long bits, int hashes, long inserted, double theoreticalFpr, long screened, long possiblyPresent,
                             long confirmed, long falsePositives) {}

    static final class State {
        volatile Instant lastAttempt;
        volatile Instant lastSuccess;
        volatile String feedTimestamp;
        volatile String error;
        volatile int records;
    }

    private final ObjectMapper mapper;
    private final Http http;
    private final Clock clock;
    private final boolean enabled;
    private final String abuseKey;
    private final int abuseDailyCap;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private volatile Map<String, FeodoEntry> feodo = Map.of();
    private volatile BloomFilter feodoBloom = BloomFilter.optimal(1, 0.01, BloomFilter.DEFAULT_SEED);
    private volatile RedBlackTree<Long, DropRange> drop = new RedBlackTree<>();
    private final Map<String, AsnInfo> asnCache = new ConcurrentHashMap<>();
    private final Map<String, AbuseInfo> abuseCache = new ConcurrentHashMap<>();
    private volatile long lastRipeCall;
    private volatile long abuseDay;
    private volatile int abuseToday;
    private long screened;
    private long possiblyPresent;
    private long confirmed;
    private long falsePositives;

    @Autowired
    public ThreatIntelService(ObjectMapper mapper, @Value("${trace.intel.enabled:true}") boolean enabled,
                              @Value("${ABUSEIPDB_API_KEY:}") String abuseKey, @Value("${trace.intel.abuseipdb-daily-cap:500}") int abuseDailyCap) {
        this(mapper, defaultHttp(), Clock.systemUTC(), enabled, abuseKey, abuseDailyCap);
    }

    ThreatIntelService(ObjectMapper mapper, Http http, Clock clock, boolean enabled, String abuseKey, int abuseDailyCap) {
        this.mapper = mapper;
        this.http = http;
        this.clock = clock;
        this.enabled = enabled;
        this.abuseKey = abuseKey == null ? "" : abuseKey.strip();
        this.abuseDailyCap = abuseDailyCap;
        for (String id : List.of("feodo", "spamhaus-drop", "ripestat", "abuseipdb")) {
            states.put(id, new State());
        }
    }

    private static Http defaultHttp() {
        HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
        return req -> {
            HttpResponse<String> r = c.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(r.statusCode(), r.body());
        };
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (enabled) {
            CompletableFuture.runAsync(this::refreshFeeds);
        }
    }

    /** Hourly: abuse.ch and Spamhaus both ask clients not to download their lists more often than needed. */
    @Scheduled(initialDelayString = "PT60M", fixedDelayString = "PT60M")
    public void scheduled() {
        if (enabled) {
            refreshFeeds();
        }
    }

    public void refreshFeeds() {
        refreshFeodo();
        refreshDrop();
    }

    // ---------------------------------------------------------------- feeds

    public void refreshFeodo() {
        State s = states.get("feodo");
        s.lastAttempt = clock.instant();
        try {
            Response r = fetch(get(FEODO_URL, Duration.ofSeconds(30)));
            Map<String, FeodoEntry> parsed = parseFeodo(mapper.readTree(r.body()));
            BloomFilter bf = BloomFilter.optimal(Math.max(1, parsed.size()), 0.01, BloomFilter.DEFAULT_SEED);
            parsed.keySet().forEach(bf::add);
            synchronized (this) {
                feodo = parsed;
                feodoBloom = bf;
            }
            s.records = parsed.size();
            s.feedTimestamp = clock.instant().toString();
            s.lastSuccess = clock.instant();
            s.error = null;
        } catch (Exception e) {
            s.error = message(e);
            log.warn("Feodo refresh failed: {}", s.error);
        }
    }

    public void refreshDrop() {
        State s = states.get("spamhaus-drop");
        s.lastAttempt = clock.instant();
        try {
            Response r = fetch(get(DROP_URL, Duration.ofSeconds(30)));
            List<DropRange> ranges = new ArrayList<>();
            String ts = parseDrop(r.body(), ranges);
            RedBlackTree<Long, DropRange> t = new RedBlackTree<>();
            for (DropRange d : ranges) {
                t.put(d.start(), d);
            }
            t.validate();
            drop = t;
            s.records = ranges.size();
            s.feedTimestamp = ts;
            s.lastSuccess = clock.instant();
            s.error = null;
        } catch (Exception e) {
            s.error = message(e);
            log.warn("Spamhaus DROP refresh failed: {}", s.error);
        }
    }

    Map<String, FeodoEntry> parseFeodo(JsonNode root) {
        if (!root.isArray()) {
            throw new IllegalArgumentException("Feodo list is not a JSON array");
        }
        Map<String, FeodoEntry> out = new LinkedHashMap<>();
        for (JsonNode e : root) {
            String ip = IpAddr.canonical(e.path("ip_address").asText(null));
            if (ip == null) {
                continue; // skip malformed rows instead of trusting them
            }
            out.put(ip, new FeodoEntry(ip, e.path("port").asInt(0), e.path("status").asText("unknown"), e.path("malware").asText(null),
                    e.path("first_seen").asText(null), e.path("last_online").asText(null),
                    e.hasNonNull("as_number") ? e.get("as_number").asLong() : null, e.path("as_name").asText(null),
                    e.path("country").asText(null)));
        }
        return out;
    }

    /** NDJSON: one {"cidr":..} object per line; the final line carries metadata (timestamp, size). Returns the timestamp. */
    String parseDrop(String body, List<DropRange> out) throws IOException {
        String ts = null;
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n = mapper.readTree(line);
            if (n.has("cidr")) {
                String[] p = n.get("cidr").asText().split("/");
                long base = p.length == 2 ? IpAddr.ipv4(p[0]) : -1;
                int bits = p.length == 2 && p[1].matches("\\d{1,2}") ? Integer.parseInt(p[1]) : -1;
                if (base < 0 || bits < 8 || bits > 32) {
                    continue;
                }
                long size = 1L << (32 - bits);
                long start = base & ~(size - 1) & 0xffffffffL;
                out.add(new DropRange(n.get("cidr").asText(), n.path("sblid").asText(null), n.path("rir").asText(null), start, start + size - 1));
            } else if (n.has("timestamp")) {
                ts = Instant.ofEpochSecond(n.get("timestamp").asLong()).toString();
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("Spamhaus DROP response contained no ranges");
        }
        return ts;
    }

    // ---------------------------------------------------------------- lookups (no network for list checks)

    /** List matches for an IP. Feodo: Bloom screen then exact confirmation. DROP: red-black floor query. */
    public synchronized List<IntelMatch> matches(String ip) {
        String c = IpAddr.canonical(ip);
        List<IntelMatch> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        screened++;
        if (feodoBloom.mightContain(c)) {
            possiblyPresent++;
            FeodoEntry e = feodo.get(c);
            if (e != null) {
                confirmed++;
                out.add(new IntelMatch("abuse.ch Feodo Tracker", "listed as " + (e.malware() == null ? "botnet" : e.malware())
                        + " command-and-control, port " + e.port() + ", status " + e.status() + ", first seen " + e.firstSeen()
                        + (e.lastOnline() == null ? "" : ", last online " + e.lastOnline()), states.get("feodo").feedTimestamp,
                        "https://feodotracker.abuse.ch/browse/host/" + c + "/"));
            } else {
                falsePositives++;
            }
        }
        DropRange d = dropRange(c);
        if (d != null) {
            out.add(new IntelMatch("Spamhaus DROP", "inside " + d.cidr() + " (" + d.sblId() + ", " + d.rir() + ")",
                    states.get("spamhaus-drop").feedTimestamp, "https://www.spamhaus.org/drop/"));
        }
        return out;
    }

    DropRange dropRange(String canonical) {
        long v = IpAddr.ipv4(canonical);
        if (v < 0) {
            return null;
        }
        RedBlackTree<Long, DropRange> t = drop;
        Long start = t.isEmpty() ? null : t.floorKey(v);
        if (start == null) {
            return null;
        }
        DropRange r = t.get(start);
        return v <= r.end() ? r : null;
    }

    public synchronized BloomStats bloomStats() {
        BloomFilter b = feodoBloom;
        return new BloomStats(b.bitCount(), b.hashCount(), b.insertedCount(), b.theoreticalFalsePositiveProbability(), screened,
                possiblyPresent, confirmed, falsePositives);
    }

    /** ASN / prefix from RIPEstat for a public address; cached 6 h; at most one call per second. */
    public AsnInfo asn(String ip) {
        String c = IpAddr.canonical(ip);
        if (c == null || IpAddr.scope(c) != IpAddr.Scope.PUBLIC) {
            return null;
        }
        AsnInfo cached = asnCache.get(c);
        if (cached != null && Instant.parse(cached.lookedUpAt()).isAfter(clock.instant().minus(Duration.ofHours(6)))) {
            return cached;
        }
        State s = states.get("ripestat");
        synchronized (this) {
            long wait = 1000 - (System.currentTimeMillis() - lastRipeCall);
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return cached;
                }
            }
            lastRipeCall = System.currentTimeMillis();
        }
        s.lastAttempt = clock.instant();
        try {
            JsonNode root = mapper.readTree(fetch(get(RIPE_URL + URLEncoder.encode(c, StandardCharsets.UTF_8), Duration.ofSeconds(15))).body());
            JsonNode d = root.path("data");
            List<String> asns = new ArrayList<>();
            d.path("asns").forEach(a -> asns.add("AS" + a.path("asn").asLong() + " " + a.path("holder").asText("")));
            AsnInfo info = new AsnInfo(c, d.path("resource").asText(null), asns, clock.instant().toString(), "RIPEstat prefix-overview");
            asnCache.put(c, info);
            if (asnCache.size() > 5000) {
                asnCache.clear();
            }
            s.lastSuccess = clock.instant();
            s.records = asnCache.size();
            s.error = null;
            return info;
        } catch (Exception e) {
            s.error = message(e);
            return cached;
        }
    }

    public AsnInfo cachedAsn(String ip) {
        return asnCache.get(IpAddr.canonical(ip));
    }

    public boolean abuseConfigured() {
        return !abuseKey.isEmpty();
    }

    /** AbuseIPDB check (only if a key is configured); cached 6 h; daily cap protects the free quota. */
    public AbuseInfo abuse(String ip) {
        String c = IpAddr.canonical(ip);
        if (!abuseConfigured() || c == null || IpAddr.scope(c) != IpAddr.Scope.PUBLIC) {
            return null;
        }
        AbuseInfo cached = abuseCache.get(c);
        if (cached != null && Instant.parse(cached.lookedUpAt()).isAfter(clock.instant().minus(Duration.ofHours(6)))) {
            return cached;
        }
        long day = clock.instant().getEpochSecond() / 86400;
        synchronized (this) {
            if (day != abuseDay) {
                abuseDay = day;
                abuseToday = 0;
            }
            if (abuseToday >= abuseDailyCap) {
                states.get("abuseipdb").error = "daily lookup cap reached";
                return cached;
            }
            abuseToday++;
        }
        State s = states.get("abuseipdb");
        s.lastAttempt = clock.instant();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ABUSEIPDB_URL + URLEncoder.encode(c, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(15)).header("User-Agent", UA).header("Accept", "application/json").header("Key", abuseKey)
                    .GET().build();
            JsonNode d = mapper.readTree(fetch(req).body()).path("data");
            AbuseInfo info = new AbuseInfo(c, d.path("abuseConfidenceScore").asInt(0), d.path("totalReports").asInt(0),
                    d.path("numDistinctUsers").asInt(0), d.path("lastReportedAt").asText(null), d.path("usageType").asText(null),
                    d.path("isp").asText(null), clock.instant().toString());
            abuseCache.put(c, info);
            s.lastSuccess = clock.instant();
            s.records = abuseCache.size();
            s.error = null;
            return info;
        } catch (Exception e) {
            s.error = message(e);
            return cached;
        }
    }

    // ---------------------------------------------------------------- status

    public List<FeedStatus> status() {
        Instant now = clock.instant();
        return List.of(
                feed("feodo", "Feodo Tracker IP blocklist", "abuse.ch", FEODO_URL, "CC0 1.0", "every 60 min", "PERIODIC", Duration.ofMinutes(60), now,
                        "Botnet C2 servers (Emotet, QakBot, ...). A match means the address was listed, not that this host is infected."),
                feed("spamhaus-drop", "Spamhaus DROP", "The Spamhaus Project", DROP_URL, "free to use; see spamhaus.org/drop terms",
                        "every 60 min", "PERIODIC", Duration.ofMinutes(60), now, "Netblocks hijacked or leased by criminal operations (IPv4)."),
                feed("ripestat", "RIPEstat prefix overview", "RIPE NCC", "stat.ripe.net/data/prefix-overview", "free, fair use",
                        "on demand, cached 6 h", "ON_DEMAND", Duration.ofHours(6), now, "Which network (ASN) announces an address. Context, not reputation."),
                abuseConfigured()
                        ? feed("abuseipdb", "AbuseIPDB check", "AbuseIPDB", "api.abuseipdb.com/api/v2/check", "AbuseIPDB terms; key required",
                        "on demand, cached 6 h, cap " + abuseDailyCap + "/day", "ON_DEMAND", Duration.ofHours(6), now,
                        "Community abuse reports. A score is evidence, not proof.")
                        : new FeedStatus("abuseipdb", "AbuseIPDB check", "AbuseIPDB", "api.abuseipdb.com/api/v2/check", "AbuseIPDB terms; key required",
                        null, "NOT_CONFIGURED", "NOT_CONFIGURED", null, null, null, null, null, 0,
                        "Set ABUSEIPDB_API_KEY on the server to enable community abuse reports."));
    }

    private FeedStatus feed(String id, String name, String owner, String endpoint, String licence, String freq, String kind, Duration period,
                            Instant now, String notes) {
        State s = states.get(id);
        String status;
        if (!enabled && !kind.equals("ON_DEMAND") && s.lastSuccess == null) {
            status = "DISABLED";
        } else if (s.lastSuccess == null) {
            status = s.lastAttempt == null ? (kind.equals("ON_DEMAND") ? "IDLE" : "NOT_YET_FETCHED") : "ERROR";
        } else if (Duration.between(s.lastSuccess, now).compareTo(period.multipliedBy(3)) > 0 && !kind.equals("ON_DEMAND")) {
            status = "STALE";
        } else {
            status = s.error == null ? "OK" : "DEGRADED";
        }
        Long age = s.lastSuccess == null ? null : Duration.between(s.lastSuccess, now).toMinutes();
        return new FeedStatus(id, name, owner, endpoint, licence, freq, kind, status, str(s.lastAttempt), str(s.lastSuccess), s.feedTimestamp,
                age, s.error, s.records, notes);
    }

    // ---------------------------------------------------------------- http

    private static HttpRequest get(String url, Duration timeout) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("User-Agent", UA).GET().build();
    }

    Response fetch(HttpRequest req) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                Thread.sleep(sleepMillis(attempt));
            }
            try {
                Response r = http.send(req);
                if (r.status() == 200) {
                    return r;
                }
                last = new IOException("HTTP " + r.status() + " from " + req.uri().getHost());
                if (r.status() != 429 && r.status() < 500) {
                    throw last;
                }
            } catch (HttpTimeoutException e) {
                last = new IOException("timeout contacting " + req.uri().getHost(), e);
            }
        }
        throw last;
    }

    long sleepMillis(int attempt) {
        return 2000L << (attempt - 1);
    }

    private static String str(Instant i) {
        return i == null ? null : i.toString();
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return (m == null ? e.getClass().getSimpleName() : m).replaceAll("\\s+", " ");
    }

    Map<String, FeodoEntry> feodoEntries() {
        return new HashMap<>(feodo);
    }
}
