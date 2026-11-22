package edu.trace.net;

import edu.trace.model.ValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * In-memory store of observed connection metadata sent by authorised sensors. Only metadata is accepted (addresses,
 * ports, protocol, state, optional process name); no payloads. Records older than the retention window are deleted.
 *
 * <p>Each connection seen in several sensor polls is one {@link Flow} with first/last observation and a count. Batches
 * carry a batch id so that a sensor retry after a timeout is recognised and not counted twice.
 */
@Component
public class EventStore {

    public static final int MAX_ROWS_PER_BATCH = 2000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,40}");
    private static final Pattern BATCH = Pattern.compile("[A-Za-z0-9-]{8,64}");
    private static final Pattern PROCESS = Pattern.compile("[A-Za-z0-9 ._()+-]{1,60}");

    // ---------------------------------------------------------------- wire format (sensor -> server)
    public record Row(String observedAt, String protocol, String localAddress, Integer localPort, String remoteAddress,
                      Integer remotePort, String state, String process) {}

    public record Batch(String sensorId, String batchId, String source, String agentVersion, String sentAt, List<Row> connections) {}

    public record IngestResult(String batchId, boolean duplicateBatch, int received, int accepted, int duplicateRows, int rejected,
                               List<String> rejectReasons, int newFlows, int newRemotes, String ingestedAt) {}

    // ---------------------------------------------------------------- stored state
    public static final class Flow {
        public final String key;
        public final String sensorId;
        public final String protocol;
        public final String localAddress;
        public final int localPort;
        public final String remoteAddress;
        public final int remotePort;
        public volatile String process;
        public volatile String lastState;
        public volatile Instant firstSeen;
        public volatile Instant lastSeen;
        public volatile Instant lastIngested;
        public volatile long observations;

        Flow(String key, String sensorId, String protocol, String la, int lp, String ra, int rp) {
            this.key = key;
            this.sensorId = sensorId;
            this.protocol = protocol;
            this.localAddress = la;
            this.localPort = lp;
            this.remoteAddress = ra;
            this.remotePort = rp;
        }
    }

    public record SensorInfo(String sensorId, String source, String agentVersion, Instant firstBatchAt, Instant lastBatchAt,
                             Instant lastObservedAt, long batches, long rows, long rejectedRows, long duplicateBatches) {}

    private final Clock clock;
    private final Duration retention;
    private final int maxFlows;
    private final Map<String, Flow> flows = new LinkedHashMap<>();
    private final Map<String, SensorInfo> sensors = new LinkedHashMap<>();
    private final LinkedHashSet<String> recentBatches = new LinkedHashSet<>();
    private final Map<Long, Long> perMinute = new HashMap<>(); // epoch minute -> observations (by observedAt)
    private long version;

    @org.springframework.beans.factory.annotation.Autowired
    public EventStore(@Value("${trace.retention-hours:24}") int retentionHours, @Value("${trace.max-flows:20000}") int maxFlows) {
        this(Clock.systemUTC(), Duration.ofHours(retentionHours), maxFlows);
    }

    EventStore(Clock clock, Duration retention, int maxFlows) {
        this.clock = clock;
        this.retention = retention;
        this.maxFlows = maxFlows;
    }

    public synchronized IngestResult ingest(Batch b) {
        if (b == null || b.sensorId() == null || !ID.matcher(b.sensorId()).matches()) {
            throw new ValidationException("sensorId", "1-40 characters: letters, digits, '.', '_' or '-'");
        }
        if (b.batchId() == null || !BATCH.matcher(b.batchId()).matches()) {
            throw new ValidationException("batchId", "8-64 characters: letters, digits or '-' (a UUID is ideal)");
        }
        List<Row> rows = b.connections() == null ? List.of() : b.connections();
        if (rows.size() > MAX_ROWS_PER_BATCH) {
            throw new ValidationException("connections", "at most " + MAX_ROWS_PER_BATCH + " rows per batch");
        }
        Instant now = clock.instant();
        String batchKey = b.sensorId() + "/" + b.batchId();
        SensorInfo s0 = sensors.get(b.sensorId());
        if (recentBatches.contains(batchKey)) {
            sensors.put(b.sensorId(), new SensorInfo(b.sensorId(), s0.source(), s0.agentVersion(), s0.firstBatchAt(), s0.lastBatchAt(),
                    s0.lastObservedAt(), s0.batches(), s0.rows(), s0.rejectedRows(), s0.duplicateBatches() + 1));
            return new IngestResult(b.batchId(), true, rows.size(), 0, 0, 0, List.of(), 0, 0, now.toString());
        }
        recentBatches.add(batchKey);
        if (recentBatches.size() > 5000) {
            recentBatches.remove(recentBatches.iterator().next());
        }
        purge(now);
        Set<String> seenInBatch = new java.util.HashSet<>();
        Set<String> knownRemotes = remoteAddresses();
        int accepted = 0;
        int dup = 0;
        int newFlows = 0;
        List<String> reasons = new ArrayList<>();
        Set<String> newRemotes = new TreeSet<>();
        Instant latest = s0 == null ? null : s0.lastObservedAt();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            String why = validate(r, now);
            if (why != null) {
                if (reasons.size() < 5) {
                    reasons.add("row " + i + ": " + why);
                }
                continue;
            }
            Instant at = Instant.parse(r.observedAt());
            String proto = r.protocol().toUpperCase();
            String la = IpAddr.canonical(r.localAddress());
            String ra = IpAddr.canonical(r.remoteAddress());
            String key = b.sensorId() + "|" + proto + "|" + la + "|" + r.localPort() + "|" + ra + "|" + r.remotePort();
            if (!seenInBatch.add(key + "|" + at)) {
                dup++;
                continue;
            }
            Flow f = flows.get(key);
            if (f == null) {
                if (flows.size() >= maxFlows) {
                    evictOldest();
                }
                f = new Flow(key, b.sensorId(), proto, la, r.localPort(), ra, r.remotePort());
                f.firstSeen = at;
                flows.put(key, f);
                newFlows++;
                if (!knownRemotes.contains(ra)) {
                    newRemotes.add(ra);
                }
            }
            if (at.isBefore(f.firstSeen)) {
                f.firstSeen = at;
            }
            if (f.lastSeen == null || at.isAfter(f.lastSeen)) {
                f.lastSeen = at;
                f.lastState = r.state() == null ? null : r.state().strip();
            }
            if (r.process() != null && !r.process().isBlank()) {
                f.process = r.process().strip();
            }
            f.lastIngested = now;
            f.observations++;
            perMinute.merge(at.getEpochSecond() / 60, 1L, Long::sum);
            accepted++;
            if (latest == null || at.isAfter(latest)) {
                latest = at;
            }
        }
        int rejected = rows.size() - accepted - dup;
        sensors.put(b.sensorId(), new SensorInfo(b.sensorId(), clean(b.source()), clean(b.agentVersion()),
                s0 == null ? now : s0.firstBatchAt(), now, latest, (s0 == null ? 0 : s0.batches()) + 1,
                (s0 == null ? 0 : s0.rows()) + accepted, (s0 == null ? 0 : s0.rejectedRows()) + rejected,
                s0 == null ? 0 : s0.duplicateBatches()));
        version++;
        return new IngestResult(b.batchId(), false, rows.size(), accepted, dup, rejected, reasons, newFlows, newRemotes.size(), now.toString());
    }

    private String validate(Row r, Instant now) {
        if (r == null) {
            return "empty row";
        }
        if (r.protocol() == null || !(r.protocol().equalsIgnoreCase("TCP") || r.protocol().equalsIgnoreCase("UDP"))) {
            return "protocol must be TCP or UDP";
        }
        if (IpAddr.canonical(r.localAddress()) == null || IpAddr.canonical(r.remoteAddress()) == null) {
            return "addresses must be IP literals";
        }
        if (r.localPort() == null || r.remotePort() == null || r.localPort() < 0 || r.localPort() > 65535 || r.remotePort() < 0
                || r.remotePort() > 65535) {
            return "ports must be 0-65535";
        }
        if (r.process() != null && !r.process().isBlank() && !PROCESS.matcher(r.process().strip()).matches()) {
            return "process name has unsupported characters";
        }
        if (r.state() != null && r.state().length() > 20) {
            return "state too long";
        }
        Instant at;
        try {
            at = Instant.parse(r.observedAt());
        } catch (DateTimeParseException | NullPointerException e) {
            return "observedAt must be an ISO-8601 UTC timestamp";
        }
        if (at.isAfter(now.plus(Duration.ofMinutes(5)))) {
            return "observedAt is in the future (check the sensor clock)";
        }
        if (at.isBefore(now.minus(retention))) {
            return "observedAt is older than the retention window";
        }
        IpAddr.Scope sc = IpAddr.scope(IpAddr.canonical(r.remoteAddress()));
        if (sc == IpAddr.Scope.LOOPBACK) {
            return "loopback connections are not stored";
        }
        return null;
    }

    private void purge(Instant now) {
        Instant cut = now.minus(retention);
        flows.values().removeIf(f -> f.lastSeen.isBefore(cut));
        long minuteCut = cut.getEpochSecond() / 60;
        perMinute.keySet().removeIf(m -> m < minuteCut);
    }

    private void evictOldest() {
        flows.values().stream().min(Comparator.comparing((Flow f) -> f.lastSeen)).ifPresent(f -> flows.remove(f.key));
    }

    private static String clean(String s) {
        return s == null ? null : s.replaceAll("[^A-Za-z0-9 ._/-]", "").substring(0, Math.min(40, s.replaceAll("[^A-Za-z0-9 ._/-]", "").length()));
    }

    // ---------------------------------------------------------------- snapshot (optional persistence)

    /** Serializable copy of the store. Flows use plain fields so Jackson can write and read them. */
    public record FlowState(String sensorId, String protocol, String localAddress, int localPort, String remoteAddress, int remotePort,
                            String process, String lastState, String firstSeen, String lastSeen, String lastIngested, long observations) {}

    public record Snapshot(String savedAt, List<FlowState> flows, List<SensorInfo> sensors, Map<Long, Long> perMinute) {}

    public synchronized Snapshot snapshot() {
        List<FlowState> fs = new ArrayList<>();
        for (Flow f : flows.values()) {
            fs.add(new FlowState(f.sensorId, f.protocol, f.localAddress, f.localPort, f.remoteAddress, f.remotePort, f.process, f.lastState,
                    f.firstSeen.toString(), f.lastSeen.toString(), f.lastIngested == null ? null : f.lastIngested.toString(), f.observations));
        }
        return new Snapshot(clock.instant().toString(), fs, List.copyOf(sensors.values()), new HashMap<>(perMinute));
    }

    /** Restores a snapshot; records outside the retention window are dropped immediately. */
    public synchronized int restore(Snapshot s) {
        if (s == null || s.flows() == null) {
            return 0;
        }
        for (FlowState x : s.flows()) {
            String key = x.sensorId() + "|" + x.protocol() + "|" + x.localAddress() + "|" + x.localPort() + "|" + x.remoteAddress() + "|" + x.remotePort();
            Flow f = new Flow(key, x.sensorId(), x.protocol(), x.localAddress(), x.localPort(), x.remoteAddress(), x.remotePort());
            f.process = x.process();
            f.lastState = x.lastState();
            f.firstSeen = Instant.parse(x.firstSeen());
            f.lastSeen = Instant.parse(x.lastSeen());
            f.lastIngested = x.lastIngested() == null ? null : Instant.parse(x.lastIngested());
            f.observations = x.observations();
            flows.put(key, f);
        }
        if (s.sensors() != null) {
            s.sensors().forEach(si -> sensors.put(si.sensorId(), si));
        }
        if (s.perMinute() != null) {
            perMinute.putAll(s.perMinute());
        }
        purge(clock.instant());
        version++;
        return flows.size();
    }

    // ---------------------------------------------------------------- read side
    public synchronized List<Flow> flows() {
        purge(clock.instant());
        return List.copyOf(flows.values());
    }

    public synchronized Collection<SensorInfo> sensors() {
        return List.copyOf(sensors.values());
    }

    public synchronized long version() {
        return version;
    }

    public synchronized Set<String> remoteAddresses() {
        Set<String> s = new java.util.HashSet<>();
        flows.values().forEach(f -> s.add(f.remoteAddress));
        return s;
    }

    /** Observations per minute for the last {@code minutes} minutes, oldest first, ending at the current minute. */
    public synchronized long[] perMinute(int minutes) {
        long end = clock.instant().getEpochSecond() / 60;
        long[] out = new long[minutes];
        for (int i = 0; i < minutes; i++) {
            out[i] = perMinute.getOrDefault(end - minutes + 1 + i, 0L);
        }
        return out;
    }

    public Instant now() {
        return clock.instant();
    }

    public Duration retention() {
        return retention;
    }
}
