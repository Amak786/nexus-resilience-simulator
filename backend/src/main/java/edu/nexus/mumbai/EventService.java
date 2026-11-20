package edu.nexus.mumbai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.nexus.algorithms.bloom.BloomFilter;
import edu.nexus.algorithms.rbtree.RedBlackTree;
import edu.nexus.algorithms.segtree.SegmentTree;
import edu.nexus.algorithms.select.RandomizedQuickselect;
import edu.nexus.model.ValidationException;
import edu.nexus.mumbai.EventDtos.DayCount;
import edu.nexus.mumbai.EventDtos.EventListResponse;
import edu.nexus.mumbai.EventDtos.EventStatsResponse;
import edu.nexus.mumbai.EventDtos.MonthCount;
import edu.nexus.mumbai.EventDtos.NoteRecord;
import edu.nexus.mumbai.EventDtos.Percentile;
import edu.nexus.mumbai.EventDtos.RefreshResponse;
import edu.nexus.mumbai.EventDtos.TreeMetrics;
import edu.nexus.mumbai.MumbaiDtos.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Event records = OpenStreetMap map notes in the study area (real, community-reported map issues; NOT
 * traffic incidents). Algorithms:
 * <ul>
 *   <li>red-black tree index keyed by (creation time, note id) for ordered range queries;</li>
 *   <li>Bloom filter + exact id set to de-duplicate notes when importing the latest notes live;</li>
 *   <li>segment tree over notes-per-day for range sum/min/max with point updates;</li>
 *   <li>randomized Quickselect for percentiles of real resolution times.</li>
 * </ul>
 */
@Service
public class EventService {

    /** Ordered key: creation time, then note id (unique). */
    public record EventKey(long createdEpochSecond, long noteId) implements Comparable<EventKey> {
        @Override
        public int compareTo(EventKey o) {
            int c = Long.compare(createdEpochSecond, o.createdEpochSecond);
            return c != 0 ? c : Long.compare(noteId, o.noteId);
        }
    }

    private static final String NOTES_API = "https://api.openstreetmap.org/api/0.6/notes.json";
    private static final Duration REFRESH_INTERVAL = Duration.ofMinutes(10);
    private static final int MIN_SAMPLE = 30;
    private static final long SEED = 2026L;

    private final ObjectMapper mapper;
    private final JsonNode provenance;
    private final RedBlackTree<EventKey, NoteRecord> index = new RedBlackTree<>();
    private final Set<Long> exactIds = new HashSet<>();
    private final BloomFilter bloom;
    private final LocalDate day0;
    private final int days;
    private final SegmentTree perDay;
    private final long[] perDayScan; // plain array kept only to cross-check the segment tree
    private Instant lastRefresh;
    private RefreshResponse lastRefreshResult;

    public EventService(ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        JsonNode root;
        try (InputStream in = new ClassPathResource("data/mumbai/notes.json").getInputStream()) {
            root = mapper.readTree(in);
        }
        provenance = root.get("provenance");
        List<NoteRecord> records = new ArrayList<>();
        root.get("records").forEach(r -> records.add(parse(r)));
        LocalDate first = records.stream().map(r -> LocalDate.parse(r.created().substring(0, 10)))
                .min(LocalDate::compareTo).orElse(LocalDate.now(ZoneOffset.UTC));
        day0 = first;
        days = (int) (LocalDate.now(ZoneOffset.UTC).toEpochDay() - first.toEpochDay()) + 400; // room for live refreshes
        perDayScan = new long[days];
        bloom = BloomFilter.optimal(Math.max(10_000, records.size() * 4L), 0.01, SEED);
        for (NoteRecord r : records) {
            add(r, false);
        }
        perDay = new SegmentTree(perDayScan);
    }

    // ---------------------------------------------------------------- provenance

    public synchronized DataSource dataSource() {
        String retrieved = MumbaiService.text(provenance, "retrievedAt");
        String fresh = MumbaiService.freshness(retrieved);
        String status = "snapshot";
        if (lastRefresh != null && lastRefreshResult != null) {
            if ("OK".equals(lastRefreshResult.status())) {
                status = "live import OK";
                fresh = "last successful import from the OSM Notes API at " + lastRefresh + " (" + lastRefreshResult.newRecords() + " new)";
            } else if ("FAILED".equals(lastRefreshResult.status())) {
                status = "last import failed";
                fresh = "snapshot only; last import attempt at " + lastRefresh + " failed: " + lastRefreshResult.message();
            }
        }
        return new DataSource("events", MumbaiService.text(provenance, "dataset") + " (" + index.size() + " records)",
                MumbaiDtos.DataClass.VERIFIED_OBSERVATION, status, MumbaiService.text(provenance, "source"),
                MumbaiService.text(provenance, "sourceUrl"), MumbaiService.text(provenance, "licence"), retrieved, null,
                MumbaiService.ageDays(retrieved), fresh, "Greater Mumbai study area",
                "Community-reported OpenStreetMap map issues (missing roads, wrong names, construction noticed by mappers). "
                        + "They are NOT traffic incidents or official closures. Usernames are removed.");
    }

    // ---------------------------------------------------------------- ordered queries (red-black tree)

    public synchronized EventListResponse list(String from, String to, String status, Integer limit) {
        LocalDate f = date(from, day0, "from");
        LocalDate t = date(to, LocalDate.now(ZoneOffset.UTC), "to");
        if (f.isAfter(t)) {
            throw new ValidationException("from", "must not be after 'to'");
        }
        String st = status == null ? "all" : status;
        if (!Set.of("all", "open", "closed").contains(st)) {
            throw new ValidationException("status", "must be all, open or closed");
        }
        int lim = limit == null ? 200 : limit;
        if (lim < 1 || lim > 1000) {
            throw new ValidationException("limit", "must be between 1 and 1000");
        }
        EventKey lo = new EventKey(f.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), Long.MIN_VALUE);
        EventKey hi = new EventKey(t.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1, Long.MAX_VALUE);
        int inRange = index.countInRange(lo, hi);
        List<NoteRecord> items = new ArrayList<>();
        index.forEachInRange(lo, hi, Integer.MAX_VALUE, (k, r) -> {
            if (items.size() < lim && (st.equals("all") || st.equals(r.status()))) {
                items.add(r);
            }
        });
        return new EventListResponse(f.toString(), t.toString(), st, inRange, items.size(), items, metrics(),
                "(creation time in UTC seconds, note id): unique and time-ordered");
    }

    public synchronized TreeMetrics metrics() {
        boolean ok = true;
        try {
            index.validate();
        } catch (IllegalStateException e) {
            ok = false;
        }
        return new TreeMetrics(index.size(), index.height(), index.blackHeight(), ok);
    }

    // ---------------------------------------------------------------- range statistics (segment tree + Quickselect)

    public synchronized EventStatsResponse stats(String from, String to) {
        LocalDate f = date(from, day0, "from");
        LocalDate t = date(to, LocalDate.now(ZoneOffset.UTC), "to");
        if (f.isBefore(day0)) {
            f = day0;
        }
        if (f.isAfter(t)) {
            throw new ValidationException("from", "must not be after 'to'");
        }
        int l = (int) (f.toEpochDay() - day0.toEpochDay());
        int r = (int) Math.min(days - 1, t.toEpochDay() - day0.toEpochDay());
        long visitedBefore = perDay.nodesVisited();
        long total = perDay.rangeSum(l, r);
        SegmentTree.MaxResult busiest = perDay.rangeMax(l, r);
        SegmentTree.MaxResult quietest = perDay.rangeMin(l, r);
        long scan = 0;
        long scanMax = Long.MIN_VALUE;
        for (int i = l; i <= r; i++) {
            scan += perDayScan[i];
            scanMax = Math.max(scanMax, perDayScan[i]);
        }
        List<MonthCount> monthly = new ArrayList<>();
        LocalDate m = f.withDayOfMonth(1);
        while (!m.isAfter(t)) {
            int ml = (int) Math.max(l, m.toEpochDay() - day0.toEpochDay());
            int mr = (int) Math.min(r, m.plusMonths(1).toEpochDay() - 1 - day0.toEpochDay());
            if (ml <= mr) {
                monthly.add(new MonthCount(m.toString().substring(0, 7), perDay.rangeSum(ml, mr)));
            }
            m = m.plusMonths(1);
        }
        long visited = perDay.nodesVisited() - visitedBefore;

        List<Double> res = new ArrayList<>();
        EventKey lo = new EventKey(f.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), Long.MIN_VALUE);
        EventKey hi = new EventKey(t.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1, Long.MAX_VALUE);
        index.forEachInRange(lo, hi, Integer.MAX_VALUE, (k, rec) -> {
            if (rec.closed() != null) {
                double d = Duration.between(Instant.parse(rec.created()), Instant.parse(rec.closed())).toMinutes() / 1440.0;
                res.add(Math.max(0, d));
            }
        });
        List<Percentile> pct = new ArrayList<>();
        if (!res.isEmpty()) {
            double[] v = res.stream().mapToDouble(Double::doubleValue).toArray();
            double[] sorted = v.clone();
            Arrays.sort(sorted);
            for (double p : new double[] {50, 90}) {
                RandomizedQuickselect.Selection s = RandomizedQuickselect.percentile(v, p, SEED);
                int rank = Math.max(1, (int) Math.ceil(p / 100 * v.length));
                pct.add(new Percentile(p, Math.round(s.value() * 100) / 100.0, s.comparisons(), s.value() == sorted[rank - 1]));
            }
        }
        boolean adequate = res.size() >= MIN_SAMPLE;
        List<String> notes = new ArrayList<>();
        notes.add("Counts are OSM map notes created per day (community map-issue reports), not traffic incidents.");
        notes.add("Days with no notes are real zeros in the snapshot; dates after the snapshot date are unknown until a refresh.");
        if (!adequate) {
            notes.add("Only " + res.size() + " resolved notes in this range: percentiles are not meaningful below " + MIN_SAMPLE + " observations.");
        }
        return new EventStatsResponse(f.toString(), t.toString(), r - l + 1, total,
                new DayCount(day0.plusDays(busiest.index()).toString(), busiest.value()),
                new DayCount(day0.plusDays(quietest.index()).toString(), quietest.value()),
                total == scan && busiest.value() == scanMax, visited, r - l + 1L, monthly, res.size(), adequate, pct, notes);
    }

    // ---------------------------------------------------------------- live import (Bloom filter + exact store)

    /** Imports the latest notes from the OSM Notes API, de-duplicating with the Bloom filter + exact set. */
    public synchronized RefreshResponse refresh() {
        Instant now = Instant.now();
        if (lastRefresh != null && Duration.between(lastRefresh, now).compareTo(REFRESH_INTERVAL) < 0 && lastRefreshResult != null) {
            return new RefreshResponse("RATE_LIMITED", lastRefresh.toString(), 0, 0, 0, 0, 0, 0, bloom.bitCount(),
                    bloom.hashCount(), bloom.theoreticalFalsePositiveProbability(),
                    "To respect the OSM API usage policy, imports run at most every 10 minutes. Last result: "
                            + lastRefreshResult.message());
        }
        MumbaiDtos.Area a = MumbaiService.STUDY_AREA;
        String url = NOTES_API + "?bbox=" + a.west() + "," + a.south() + "," + a.east() + "," + a.north() + "&limit=10000&closed=-1";
        JsonNode body;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> resp = client.send(HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "nexus-academic-project/1.0").timeout(Duration.ofSeconds(40)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return failed(now, "OSM Notes API answered HTTP " + resp.statusCode() + "; the snapshot is unchanged.");
            }
            body = mapper.readTree(resp.body());
        } catch (IOException e) {
            return failed(now, "Could not reach the OSM Notes API (" + e.getClass().getSimpleName() + "); the snapshot is unchanged.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(now, "Import interrupted; the snapshot is unchanged.");
        }
        lastRefresh = now;
        lastRefreshResult = importFeatures(body, now);
        return lastRefreshResult;
    }

    /**
     * De-duplicates and imports note features (OSM Notes API GeoJSON). Every Bloom "possibly present" id is
     * confirmed against the exact id set; definitely-absent ids skip the exact lookup.
     */
    synchronized RefreshResponse importFeatures(JsonNode body, Instant now) {
        int fetched = 0;
        int possible = 0;
        int confirmed = 0;
        int falsePositive = 0;
        int added = 0;
        int updates = 0;
        for (JsonNode f : body.get("features")) {
            NoteRecord r = fromApi(f);
            fetched++;
            if (bloom.mightContain(Long.toString(r.id()))) {
                possible++;
                if (exactIds.contains(r.id())) { // authoritative check
                    confirmed++;
                    if (updateStatus(r)) {
                        updates++;
                    }
                    continue;
                }
                falsePositive++;
            }
            if (add(r, true)) {
                added++;
            }
        }
        return new RefreshResponse("OK", now.toString(), fetched, possible, confirmed, falsePositive, added, updates,
                bloom.bitCount(), bloom.hashCount(), round6(bloom.theoreticalFalsePositiveProbability()),
                "Imported " + added + " new note(s); " + confirmed + " already known (confirmed by the exact id set); "
                        + falsePositive + " Bloom false positive(s) resolved as new.");
    }

    private RefreshResponse failed(Instant now, String message) {
        lastRefresh = now;
        lastRefreshResult = new RefreshResponse("FAILED", now.toString(), 0, 0, 0, 0, 0, 0, bloom.bitCount(), bloom.hashCount(),
                round6(bloom.theoreticalFalsePositiveProbability()), message);
        return lastRefreshResult;
    }

    // ---------------------------------------------------------------- internals

    private boolean add(NoteRecord r, boolean liveUpdate) {
        if (!exactIds.add(r.id())) {
            return false;
        }
        bloom.add(Long.toString(r.id()));
        Instant created = Instant.parse(r.created());
        index.put(new EventKey(created.getEpochSecond(), r.id()), r);
        int d = (int) (LocalDate.ofInstant(created, ZoneOffset.UTC).toEpochDay() - day0.toEpochDay());
        if (d >= 0 && d < days) {
            perDayScan[d]++;
            if (liveUpdate) {
                perDay.pointSet(d, perDayScan[d]);
            }
        }
        return true;
    }

    private boolean updateStatus(NoteRecord fresh) {
        EventKey k = new EventKey(Instant.parse(fresh.created()).getEpochSecond(), fresh.id());
        NoteRecord old = index.get(k);
        if (old != null && !old.status().equals(fresh.status())) {
            index.put(k, fresh);
            return true;
        }
        return false;
    }

    private static NoteRecord parse(JsonNode r) {
        long id = r.get("id").asLong();
        return new NoteRecord(id, r.get("lat").asDouble(), r.get("lon").asDouble(), r.get("status").asText(),
                r.get("created").asText(), r.get("closed").isNull() ? null : r.get("closed").asText(), r.get("comments").asInt(),
                r.get("text").asText(), "https://www.openstreetmap.org/note/" + id);
    }

    private static NoteRecord fromApi(JsonNode f) {
        JsonNode p = f.get("properties");
        long id = p.get("id").asLong();
        String created = iso(p.get("date_created").asText());
        String closed = null;
        JsonNode comments = p.get("comments");
        if ("closed".equals(p.get("status").asText()) && comments != null) {
            for (JsonNode c : comments) {
                if ("closed".equals(c.path("action").asText())) {
                    closed = iso(c.get("date").asText());
                }
            }
        }
        String text = comments != null && comments.size() > 0 ? comments.get(0).path("text").asText("") : "";
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() > 240) {
            text = text.substring(0, 240);
        }
        JsonNode c = f.get("geometry").get("coordinates");
        return new NoteRecord(id, round6(c.get(1).asDouble()), round6(c.get(0).asDouble()), p.get("status").asText(), created,
                closed, comments == null ? 0 : comments.size(), text, "https://www.openstreetmap.org/note/" + id);
    }

    private static String iso(String osmDate) { // "2026-11-20 11:34:36 UTC" -> "2026-11-20T11:34:36Z"
        return osmDate.replace(" UTC", "Z").replace(' ', 'T');
    }

    private static LocalDate date(String s, LocalDate def, String field) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new ValidationException(field, "must be a date like 2024-06-30");
        }
    }

    private static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }

    /** First day covered by the per-day series (for tests). */
    public LocalDate firstDay() {
        return day0;
    }

    public synchronized int size() {
        return index.size();
    }
}
