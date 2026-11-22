package edu.trace.net;

import edu.trace.intel.ThreatIntelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TRACE API. Three access levels:
 * <ul>
 *   <li>sensor: {@code POST /api/ingest/connections} with {@code Authorization: Bearer $TRACE_INGEST_TOKEN};</li>
 *   <li>viewer: observed data (overview, network, findings, observations, stream) with {@code Bearer $TRACE_VIEW_TOKEN};</li>
 *   <li>public: source status, threat-list reputation of public addresses, Algorithm Lab.</li>
 * </ul>
 * If a token is not configured on the server, that level is disabled (HTTP 503) instead of being left open.
 */
@RestController
@RequestMapping("/api")
public class TraceController {

    private static final Logger log = LoggerFactory.getLogger(TraceController.class);

    private final EventStore store;
    private final AnalysisService analysis;
    private final ThreatIntelService intel;
    private final byte[] ingestToken;
    private final byte[] viewToken;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Map<String, Instant> tickets = new ConcurrentHashMap<>();
    private final Map<String, long[]> lookupWindows = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public TraceController(EventStore store, AnalysisService analysis, ThreatIntelService intel,
                           @Value("${TRACE_INGEST_TOKEN:}") String ingestToken, @Value("${TRACE_VIEW_TOKEN:}") String viewToken) {
        this.store = store;
        this.analysis = analysis;
        this.intel = intel;
        this.ingestToken = token(ingestToken, "TRACE_INGEST_TOKEN");
        this.viewToken = token(viewToken, "TRACE_VIEW_TOKEN");
        log.info("token state: ingest={}, viewer={}", state(this.ingestToken), state(this.viewToken));
    }

    // ---------------------------------------------------------------- auth

    /** Spring property first, then the raw environment variable; surrounding whitespace and quotes are removed. */
    static byte[] token(String fromSpring, String envName) {
        String v = fromSpring == null || fromSpring.isBlank() ? System.getenv(envName) : fromSpring;
        if (v == null) {
            return new byte[0];
        }
        v = v.strip();
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1).strip();
        }
        return v.getBytes(StandardCharsets.UTF_8);
    }

    /** Non-secret configuration state: UNSET, TOO_SHORT (under 16 characters) or OK. Never the value or its length. */
    static String state(byte[] t) {
        return t.length == 0 ? "UNSET" : t.length < 16 ? "TOO_SHORT" : "OK";
    }

    /** Thrown for 401 / 503 auth outcomes. */
    public static final class AuthException extends RuntimeException {
        final HttpStatus status;

        AuthException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
    }

    static void require(byte[] expected, HttpServletRequest req, String what) {
        if (expected.length < 16) {
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, what + " access is not configured on this server (token unset or shorter than 16 characters).");
        }
        String h = req.getHeader("Authorization");
        byte[] got = h != null && h.startsWith("Bearer ") ? h.substring(7).strip().getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!MessageDigest.isEqual(expected, got)) { // constant-time comparison
            throw new AuthException(HttpStatus.UNAUTHORIZED, "missing or invalid " + what + " token");
        }
    }

    public void requireViewer(HttpServletRequest req) {
        require(viewToken, req, "viewer");
    }

    public boolean isViewer(HttpServletRequest req) {
        try {
            requireViewer(req);
            return true;
        } catch (AuthException e) {
            return false;
        }
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> auth(AuthException e) {
        return ResponseEntity.status(e.status).body(Map.of("error", e.status == HttpStatus.UNAUTHORIZED ? "UNAUTHORIZED" : e.status == HttpStatus.TOO_MANY_REQUESTS ? "RATE_LIMITED" : "NOT_CONFIGURED",
                "message", e.getMessage(), "issues", List.of()));
    }

    // ---------------------------------------------------------------- sensor ingestion

    @Tag(name = "Ingestion")
    @Operation(summary = "Sensor upload of connection metadata (bearer TRACE_INGEST_TOKEN; idempotent per batchId)")
    @PostMapping("/ingest/connections")
    public EventStore.IngestResult ingest(@RequestBody EventStore.Batch batch, HttpServletRequest req) {
        require(ingestToken, req, "sensor");
        EventStore.IngestResult r = store.ingest(batch);
        if (!r.duplicateBatch() && r.accepted() > 0) {
            broadcast(Map.of("type", "batch", "sensorId", batch.sensorId(), "accepted", r.accepted(), "newFlows", r.newFlows(),
                    "newRemotes", r.newRemotes(), "ingestedAt", r.ingestedAt()));
        }
        return r;
    }

    // ---------------------------------------------------------------- public

    @Tag(name = "Sources")
    @Operation(summary = "Status, freshness and failures of every data source")
    @GetMapping("/sources")
    public Map<String, Object> sources(HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serverTime", store.now().toString());
        m.put("intel", intel.status());
        m.put("bloom", intel.bloomStats());
        m.put("viewerConfigured", viewToken.length >= 16);
        m.put("ingestConfigured", ingestToken.length >= 16);
        m.put("viewerTokenState", state(viewToken));
        m.put("ingestTokenState", state(ingestToken));
        m.put("viewer", isViewer(req));
        List<Map<String, Object>> sensors = new java.util.ArrayList<>();
        Instant now = store.now();
        if (isViewer(req)) {
            for (EventStore.SensorInfo s : store.sensors()) {
                Map<String, Object> x = new LinkedHashMap<>();
                long idle = Duration.between(s.lastBatchAt(), now).toSeconds();
                x.put("sensorId", s.sensorId());
                x.put("source", s.source());
                x.put("agentVersion", s.agentVersion());
                x.put("status", idle <= 90 ? "LIVE" : idle <= 900 ? "DELAYED" : "DISCONNECTED");
                x.put("secondsSinceLastBatch", idle);
                x.put("lastBatchAt", s.lastBatchAt().toString());
                x.put("lastObservedAt", s.lastObservedAt() == null ? null : s.lastObservedAt().toString());
                x.put("batches", s.batches());
                x.put("rows", s.rows());
                x.put("rejectedRows", s.rejectedRows());
                x.put("duplicateBatches", s.duplicateBatches());
                sensors.add(x);
            }
        }
        m.put("sensors", sensors);
        m.put("mode", sensors.isEmpty() ? "NO_SENSOR" : sensors.stream().anyMatch(s -> "LIVE".equals(s.get("status"))) ? "LIVE" : "HISTORICAL");
        m.put("retentionHours", store.retention().toHours());
        return m;
    }

    @Tag(name = "Investigations")
    @Operation(summary = "Investigate an IP address: threat lists, ASN, optional AbuseIPDB, and (viewer only) local observations")
    @GetMapping("/investigate")
    public AnalysisService.Investigation investigate(@RequestParam String ip, HttpServletRequest req) {
        limitLookups(clientId(req));
        return analysis.investigate(ip, isViewer(req), true);
    }

    // ---------------------------------------------------------------- viewer

    @Tag(name = "Overview")
    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest req) {
        requireViewer(req);
        List<AnalysisService.Finding> fs = analysis.findings();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("computedAt", store.now().toString());
        m.put("flows", store.flows().size());
        m.put("remoteAddresses", store.remoteAddresses().size());
        m.put("findingsOpen", fs.stream().filter(f -> f.status().equals("OPEN")).count());
        m.put("findingsHigh", fs.stream().filter(f -> f.severity().equals("HIGH") && !f.status().equals("DISMISSED")).count());
        m.put("ranking", analysis.rank(fs, 5));
        m.put("topFindings", fs.stream().limit(5).toList());
        m.put("changes", analysis.changes(15));
        m.put("recent", analysis.observations(null, 15));
        m.put("timeSeries", analysis.timeSeries(60, null, null));
        return m;
    }

    @Tag(name = "Network")
    @GetMapping("/network/graph")
    public AnalysisService.GraphView graph(@RequestParam(defaultValue = "250") int maxNodes, HttpServletRequest req) {
        requireViewer(req);
        return analysis.graph(Math.max(10, Math.min(600, maxNodes)));
    }

    @Tag(name = "Network")
    @GetMapping("/network/path")
    public AnalysisService.PathResult path(@RequestParam String from, @RequestParam String to, HttpServletRequest req) {
        requireViewer(req);
        return analysis.path(from, to);
    }

    @Tag(name = "Network")
    @GetMapping("/network/timeseries")
    public AnalysisService.TimeSeries series(@RequestParam(defaultValue = "60") int minutes, @RequestParam(required = false) Integer from,
                                             @RequestParam(required = false) Integer to, HttpServletRequest req) {
        requireViewer(req);
        return analysis.timeSeries(minutes, from, to);
    }

    @Tag(name = "Investigations")
    @GetMapping("/findings")
    public Map<String, Object> findings(@RequestParam(defaultValue = "10") int k, HttpServletRequest req) {
        requireViewer(req);
        List<AnalysisService.Finding> fs = analysis.findings();
        return Map.of("findings", fs, "ranking", analysis.rank(fs, k), "scoringRule", AnalysisService.SCORING_RULE,
                "commonPorts", new java.util.TreeSet<>(AnalysisService.COMMON_PORTS));
    }

    public record Decision(String status, String note) {}

    @Tag(name = "Investigations")
    @PostMapping("/findings/{id}/decision")
    public Map<String, Object> decide(@PathVariable String id, @RequestBody Decision d, HttpServletRequest req) {
        requireViewer(req);
        analysis.decide(id, d == null ? null : d.status(), d == null ? null : d.note());
        return Map.of("id", id, "status", d.status());
    }

    @Tag(name = "Investigations")
    @GetMapping("/observations")
    public List<AnalysisService.Observation> observations(@RequestParam(required = false) String ip,
                                                          @RequestParam(defaultValue = "200") int limit, HttpServletRequest req) {
        requireViewer(req);
        return analysis.observations(ip, Math.max(1, Math.min(1000, limit)));
    }

    // ---------------------------------------------------------------- live stream (server-sent events)

    @Tag(name = "Live")
    @Operation(summary = "One-time ticket (60 s) for the event stream, so the token never appears in a URL")
    @PostMapping("/stream/ticket")
    public Map<String, String> ticket(HttpServletRequest req) {
        requireViewer(req);
        tickets.entrySet().removeIf(e -> e.getValue().isBefore(Instant.now()));
        byte[] b = new byte[24];
        random.nextBytes(b);
        String t = HexFormat.of().formatHex(b);
        tickets.put(t, Instant.now().plusSeconds(60));
        return Map.of("ticket", t);
    }

    @Tag(name = "Live")
    @GetMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream(@RequestParam String ticket) {
        Instant exp = tickets.remove(ticket);
        if (exp == null || exp.isBefore(Instant.now())) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "invalid or expired stream ticket");
        }
        if (emitters.size() >= 50) {
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "too many open streams");
        }
        SseEmitter e = new SseEmitter(Duration.ofMinutes(30).toMillis());
        emitters.add(e);
        e.onCompletion(() -> emitters.remove(e));
        e.onTimeout(() -> emitters.remove(e));
        e.onError(x -> emitters.remove(e));
        try {
            e.send(SseEmitter.event().name("hello").data(Map.of("serverTime", Instant.now().toString())));
        } catch (IOException ex) {
            emitters.remove(e);
        }
        return e;
    }

    void broadcast(Object data) {
        for (SseEmitter e : emitters) {
            try {
                e.send(SseEmitter.event().name("ingest").data(data));
            } catch (IOException | IllegalStateException ex) {
                emitters.remove(e);
            }
        }
    }

    // ---------------------------------------------------------------- limits

    private static String clientId(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].strip() : req.getRemoteAddr();
    }

    /** At most 20 investigations per client per 10 minutes (external lookups are rate-limited by their providers). */
    private void limitLookups(String client) {
        long now = System.currentTimeMillis();
        long[] w = lookupWindows.compute(client, (k, old) -> old == null || now - old[0] > 600_000 ? new long[] {now, 0} : old);
        synchronized (w) {
            if (++w[1] > 20) {
                throw new AuthException(HttpStatus.TOO_MANY_REQUESTS, "too many lookups; wait a few minutes");
            }
        }
        if (lookupWindows.size() > 10_000) {
            lookupWindows.clear();
            log.info("lookup rate-limit table reset");
        }
    }
}
