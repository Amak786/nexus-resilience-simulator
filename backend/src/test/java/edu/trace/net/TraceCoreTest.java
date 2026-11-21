package edu.trace.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.trace.intel.ThreatIntelServiceTestSupport;
import edu.trace.intel.ThreatIntelService;
import edu.trace.model.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ingestion, validation, deduplication, retention, findings and graph analysis (no network). */
class TraceCoreTest {

    static final Instant T0 = Instant.parse("2026-11-21T12:00:00Z");

    static final class MutableClock extends Clock {
        Instant now = T0;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId z) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static EventStore.Row row(String at, String remote, int port, String proc) {
        return new EventStore.Row(at, "TCP", "192.168.1.10", 40000 + Math.floorMod((remote + proc + port).hashCode(), 20000), remote, port, "Established", proc);
    }

    static EventStore.Batch batch(String id, List<EventStore.Row> rows) {
        return new EventStore.Batch("laptop-1", id, "test", "t/1", T0.toString(), rows);
    }

    @Test
    @DisplayName("TR-1 (IP parsing): literals only, canonical form, scopes; host names and junk are rejected")
    void ipParsing() {
        assertEquals("8.8.8.8", IpAddr.canonical(" 8.8.8.8 "));
        assertNull(IpAddr.canonical("example.com"), "no DNS resolution");
        assertNull(IpAddr.canonical("256.1.1.1"));
        assertNull(IpAddr.canonical("010.1.1.1"), "leading zeros are ambiguous");
        assertNull(IpAddr.canonical("http://169.254.169.254/"));
        assertEquals("1.2.3.4", IpAddr.canonical("::ffff:1.2.3.4"), "IPv4-mapped IPv6 is normalised");
        assertEquals(IpAddr.Scope.PRIVATE, IpAddr.scope("10.0.0.1"));
        assertEquals(IpAddr.Scope.LINK_LOCAL, IpAddr.scope("169.254.169.254"));
        assertEquals(IpAddr.Scope.CGNAT, IpAddr.scope("100.64.0.1"));
        assertEquals(IpAddr.Scope.PUBLIC, IpAddr.scope("8.8.8.8"));
        assertEquals(IpAddr.Scope.LOOPBACK, IpAddr.scope(IpAddr.canonical("::1")));
        assertEquals(IpAddr.Scope.PRIVATE, IpAddr.scope(IpAddr.canonical("fd00::1")));
    }

    @Test
    @DisplayName("TR-2 (validation): bad rows are rejected with reasons; bad batches throw 400")
    void validation() {
        MutableClock c = new MutableClock();
        EventStore s = new EventStore(c, Duration.ofHours(24), 1000);
        var r = s.ingest(batch("batch-0001", List.of(
                row(T0.toString(), "8.8.8.8", 443, "chrome"),
                row(T0.toString(), "not-an-ip", 443, null),
                row(T0.plus(Duration.ofHours(1)).toString(), "1.1.1.1", 443, null),
                row(T0.minus(Duration.ofDays(2)).toString(), "1.1.1.1", 443, null),
                row(T0.toString(), "127.0.0.1", 80, null),
                new EventStore.Row(T0.toString(), "ICMP", "192.168.1.10", 1, "1.1.1.1", 1, null, null),
                row(T0.toString(), "1.1.1.1", 70000, null),
                row(T0.toString(), "1.1.1.1", 443, "bad<script>"))));
        assertEquals(1, r.accepted());
        assertEquals(7, r.rejected());
        assertEquals(5, r.rejectReasons().size(), "first five reasons reported");
        assertThrows(ValidationException.class, () -> s.ingest(new EventStore.Batch("bad id!", "batch-0002", null, null, null, List.of())));
        assertThrows(ValidationException.class, () -> s.ingest(new EventStore.Batch("ok", "x", null, null, null, List.of())));
        List<EventStore.Row> many = new ArrayList<>();
        for (int i = 0; i <= EventStore.MAX_ROWS_PER_BATCH; i++) {
            many.add(row(T0.toString(), "8.8.4.4", 443, null));
        }
        assertThrows(ValidationException.class, () -> s.ingest(batch("batch-big1", many)));
    }

    @Test
    @DisplayName("TR-3 (duplicates): a re-sent batch is ignored; repeated rows in a batch are dropped; polls aggregate into one flow")
    void duplicates() {
        MutableClock c = new MutableClock();
        EventStore s = new EventStore(c, Duration.ofHours(24), 1000);
        var a = s.ingest(batch("batch-aaaa", List.of(row(T0.toString(), "8.8.8.8", 443, "chrome"), row(T0.toString(), "8.8.8.8", 443, "chrome"))));
        assertEquals(1, a.accepted());
        assertEquals(1, a.duplicateRows());
        var again = s.ingest(batch("batch-aaaa", List.of(row(T0.toString(), "8.8.8.8", 443, "chrome"))));
        assertTrue(again.duplicateBatch());
        assertEquals(0, again.accepted());
        c.now = T0.plusSeconds(15);
        s.ingest(batch("batch-bbbb", List.of(row(c.now.toString(), "8.8.8.8", 443, "chrome"))));
        assertEquals(1, s.flows().size());
        var f = s.flows().get(0);
        assertEquals(2, f.observations);
        assertEquals(T0, f.firstSeen);
        assertEquals(c.now, f.lastSeen);
        assertEquals(1, s.sensors().iterator().next().duplicateBatches());
    }

    @Test
    @DisplayName("TR-4 (retention): flows not seen within the window are deleted; per-minute counts follow observation time")
    void retention() {
        MutableClock c = new MutableClock();
        EventStore s = new EventStore(c, Duration.ofHours(1), 1000);
        s.ingest(batch("batch-r001", List.of(row(T0.toString(), "8.8.8.8", 443, null))));
        long[] pm = s.perMinute(5);
        assertEquals(1, pm[4]);
        c.now = T0.plus(Duration.ofMinutes(61));
        assertTrue(s.flows().isEmpty());
    }

    @Test
    @DisplayName("TR-5 (findings): Feodo and DROP matches become INTEL_MATCH; uncommon port is a LOW heuristic; score follows the rule")
    void findings() throws Exception {
        MutableClock c = new MutableClock();
        EventStore s = new EventStore(c, Duration.ofHours(24), 1000);
        ThreatIntelService intel = ThreatIntelServiceTestSupport.loaded(new ObjectMapper());
        AnalysisService a = new AnalysisService(s, intel);
        s.ingest(batch("batch-f001", List.of(
                row(T0.toString(), "50.16.16.211", 443, "svchost"),   // on the test Feodo list
                row(T0.toString(), "1.10.16.5", 443, "app"),          // inside DROP 1.10.16.0/20
                row(T0.toString(), "8.8.8.8", 4444, "tool"),          // uncommon port
                row(T0.toString(), "8.8.4.4", 443, "chrome"))));      // ordinary
        var fs = a.findings();
        assertEquals(3, fs.size());
        var feodo = fs.stream().filter(f -> f.rule().equals("INTEL_FEODO")).findFirst().orElseThrow();
        assertEquals("INTEL_MATCH", feodo.category());
        assertEquals("HIGH", feodo.severity());
        assertEquals("MEDIUM", feodo.confidence(), "a list match is evidence, not proof");
        assertEquals(80, feodo.score());
        assertTrue(fs.stream().anyMatch(f -> f.rule().equals("INTEL_DROP") && f.subject().equals("1.10.16.5")));
        var port = fs.stream().filter(f -> f.rule().equals("UNCOMMON_PORT")).findFirst().orElseThrow();
        assertEquals("LOW", port.severity());
        assertEquals(15, port.score());
        for (var f : fs) {
            int sum = f.scoreBreakdown().stream().mapToInt(w -> Integer.parseInt(w.substring(1, w.indexOf(' ')))).sum();
            assertEquals(f.score(), sum, f.id());
            assertFalse(f.category().equals("CONFIRMED_INCIDENT"));
        }
        a.decide(feodo.id(), "CONFIRMED_INCIDENT", "checked on host");
        assertEquals("CONFIRMED_INCIDENT", a.findings().stream().filter(f -> f.id().equals(feodo.id())).findFirst().orElseThrow().status());
        assertThrows(ValidationException.class, () -> a.decide(feodo.id(), "HACKED", null));
        var rank = a.rank(fs, 2);
        assertEquals(2, rank.topIds().size());
        assertTrue(rank.matchesSort());
        assertTrue(rank.indexValid());
    }

    @Test
    @DisplayName("TR-6 (graph): sensor-process-remote graph; DSU, BFS, Dijkstra(Fib)=PQ, EK=FF=min cut on real calculations")
    void graph() throws Exception {
        MutableClock c = new MutableClock();
        EventStore s = new EventStore(c, Duration.ofHours(24), 1000);
        AnalysisService a = new AnalysisService(s, ThreatIntelServiceTestSupport.loaded(new ObjectMapper()));
        s.ingest(batch("batch-g001", List.of(row(T0.toString(), "8.8.8.8", 443, "chrome"), row(T0.toString(), "8.8.4.4", 443, "chrome"),
                row(T0.toString(), "8.8.8.8", 443, "edge"), row(T0.toString(), "1.1.1.1", 443, "edge"))));
        var g = a.graph(100);
        assertEquals(6, g.nodes(), "sensor, 2 processes, 3 remote addresses");
        assertEquals(6, g.edges());
        assertEquals(1, g.components());
        var p = a.path("proc:laptop-1/chrome", "ip:1.1.1.1");
        assertTrue(p.connected());
        assertEquals(3, p.bfsHops(), "chrome - 8.8.8.8 - edge - 1.1.1.1");
        assertTrue(p.queuesAgree());
        assertEquals(p.disjointPathsEdmondsKarp(), p.disjointPathsFordFulkerson());
        assertEquals(1, p.disjointPathsEdmondsKarp(), "1.1.1.1 hangs off edge only");
        assertTrue(p.maxFlowEqualsMinCut());
        var p2 = a.path("proc:laptop-1/chrome", "proc:laptop-1/edge");
        assertEquals(2, p2.disjointPathsEdmondsKarp(), "via the sensor node and via 8.8.8.8");
        assertThrows(ValidationException.class, () -> a.path("ip:9.9.9.9", "ip:1.1.1.1"));
        var ts = a.timeSeries(10, 0, 9);
        assertEquals(4, ts.querySum());
        assertTrue(ts.matchesScan());
    }
}
