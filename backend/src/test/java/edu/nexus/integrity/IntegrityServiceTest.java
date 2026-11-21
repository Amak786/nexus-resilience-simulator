package edu.nexus.integrity;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.nexus.integrity.IntegrityDtos.Change;
import edu.nexus.integrity.IntegrityDtos.DemoModification;
import edu.nexus.integrity.IntegrityDtos.IntegrityRequest;
import edu.nexus.integrity.IntegrityDtos.IntegrityResponse;
import edu.nexus.integrity.IntegrityDtos.ScenarioResponse;
import edu.nexus.model.ValidationException;
import edu.nexus.mumbai.EventService;
import edu.nexus.mumbai.MumbaiDtos.Area;
import edu.nexus.mumbai.MumbaiService;
import edu.nexus.mumbai.TourService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Silent Shield integrity check on the two committed OpenStreetMap snapshots (deterministic, no network). */
class IntegrityServiceTest {

    static final Area DADAR = new Area(19.010, 72.830, 19.060, 72.870);
    static final double[] PROBE = {19.0180, 72.8470, 19.0560, 72.8450};
    private static IntegrityService integrity;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void load() throws Exception {
        MumbaiService mumbai = new MumbaiService(MAPPER, new EventService(MAPPER), 20000, 6000);
        integrity = new IntegrityService(MAPPER, mumbai, new TourService(mumbai), 6000);
    }

    static IntegrityRequest request(List<DemoModification> mods, List<String> reviewed) {
        return new IntegrityRequest(DADAR, PROBE[0], PROBE[1], PROBE[2], PROBE[3], mods, reviewed, 10, null, null, true);
    }

    @Test
    @DisplayName("IN-1 (real diff): without demonstrations every change is a REAL_OSM_EDIT, nothing is SIMULATED or MALICIOUS")
    void realDiffOnly() {
        IntegrityResponse r = integrity.analyse(request(null, null));
        assertTrue(r.totalChanges() > 0, "the two real OSM states differ in Dadar");
        assertTrue(r.changes().stream().allMatch(c -> c.origin().equals("REAL_OSM_EDIT")));
        assertEquals(0, r.statusCounts().simulatedTampering());
        assertEquals(0, r.statusCounts().verifiedMalicious());
        assertEquals(r.totalChanges(), r.statusCounts().routineUnreviewed() + r.statusCounts().suspiciousNeedsReview());
        assertNotEquals(r.baseline().snapshot().fileSha256(), r.updated().snapshot().fileSha256());
        assertTrue(r.changes().stream().allMatch(c -> c.historyUrl().startsWith("https://www.openstreetmap.org/way/")));
    }

    @Test
    @DisplayName("IN-2 (Bloom screening): every 'possibly known' answer is verified exactly; counts add up")
    void bloomScreening() {
        var b = integrity.analyse(request(null, null)).bloom();
        assertEquals(b.screened(), b.definitelyNew() + b.possiblyKnown());
        assertEquals(b.possiblyKnown(), b.confirmedKnown() + b.falsePositives());
        assertTrue(b.theoreticalFpr() <= 0.011);
    }

    @Test
    @DisplayName("IN-3 (demonstration): scenario S1 is labelled SIMULATED_TAMPERING, changes the area hash and the probe route")
    void scenarioIsLabelledDemo() {
        ScenarioResponse s = integrity.scenario(DADAR, PROBE[0], PROBE[1], PROBE[2], PROBE[3]);
        assertEquals(3, s.modifications().size());
        assertTrue(s.label().startsWith("DEMONSTRATION"));
        IntegrityResponse clean = integrity.analyse(request(null, null));
        IntegrityResponse demo = integrity.analyse(request(s.modifications(), null));
        assertEquals(3, demo.statusCounts().simulatedTampering());
        assertTrue(demo.changes().stream().filter(c -> c.origin().equals("DEMONSTRATION"))
                .allMatch(c -> c.status().equals("SIMULATED_TAMPERING") && c.scoreBreakdown().stream().anyMatch(w -> w.contains("no public edit record"))));
        assertNotEquals(clean.updated().snapshot().areaSha256(), demo.updated().snapshot().areaSha256());
        assertEquals(clean.baseline().snapshot().areaSha256(), demo.baseline().snapshot().areaSha256(), "baseline is untouched");
        long removed = s.modifications().get(0).wayId();
        assertFalse(demo.updated().route().wayIds().contains(removed), "the removed road is no longer on the probe route");
        assertTrue(demo.warnings().stream().anyMatch(w -> w.contains("DEMONSTRATION")));
        assertEquals(0, demo.statusCounts().verifiedMalicious());
    }

    @Test
    @DisplayName("IN-4 (reset / determinism): the same request gives the same result; dropping the demo restores the real diff")
    void deterministicAndReset() {
        ScenarioResponse s = integrity.scenario(DADAR, PROBE[0], PROBE[1], PROBE[2], PROBE[3]);
        IntegrityResponse a = integrity.analyse(request(null, null));
        integrity.analyse(request(s.modifications(), null));
        IntegrityResponse b = integrity.analyse(request(null, null));
        assertEquals(a.ranking().topIds(), b.ranking().topIds());
        assertEquals(a.updated().snapshot().areaSha256(), b.updated().snapshot().areaSha256());
        assertEquals(a.totalChanges(), b.totalChanges());
    }

    @Test
    @DisplayName("IN-5 (review): a change marked legitimate leaves the ranking, the alert index and the strip risk")
    void reviewLegitimate() {
        IntegrityResponse a = integrity.analyse(request(null, null));
        String top = a.ranking().topIds().get(0);
        Change c = a.changes().stream().filter(x -> x.id().equals(top)).findFirst().orElseThrow();
        IntegrityResponse b = integrity.analyse(request(null, List.of(top)));
        assertEquals(1, b.statusCounts().legitimateReviewed());
        assertFalse(b.ranking().topIds().contains(top));
        assertEquals(a.alerts().size() - 1, b.alerts().size());
        assertEquals(1, b.alerts().removedAfterReview());
        long riskA = a.strips().risk().stream().mapToLong(Long::longValue).sum();
        long riskB = b.strips().risk().stream().mapToLong(Long::longValue).sum();
        assertEquals(riskA - c.score(), riskB);
    }

    @Test
    @DisplayName("IN-6 (flow): on both snapshots Edmonds-Karp = Ford-Fulkerson = min-cut size; Fibonacci heap = PriorityQueue")
    void flowAndRoute() {
        IntegrityResponse r = integrity.analyse(request(null, null));
        for (var side : List.of(r.baseline(), r.updated())) {
            assertEquals(side.flow().disjointRoutesEdmondsKarp(), side.flow().disjointRoutesFordFulkerson());
            assertTrue(side.flow().maxFlowEqualsMinCut());
            assertEquals(side.flow().minCutSize(), side.flow().cut().size());
            assertTrue(side.route().queuesAgree());
        }
        assertTrue(r.updated().route().reachable());
    }

    @Test
    @DisplayName("IN-7 (scoring rule): every score equals the sum of its documented breakdown; status follows the threshold")
    void scoresFollowRule() {
        IntegrityResponse r = integrity.analyse(request(null, null));
        for (Change c : r.changes()) {
            int sum = c.scoreBreakdown().stream().mapToInt(w -> Integer.parseInt(w.substring(1, w.indexOf(' ')))).sum();
            assertEquals(c.score(), sum, c.id());
            assertEquals(c.score() >= IntegrityService.SUSPICIOUS_THRESHOLD, c.status().equals("SUSPICIOUS_NEEDS_REVIEW"), c.id());
        }
    }

    @Test
    @DisplayName("IN-8 (Quickselect top-k): matches a full-sort oracle on random scores, including ties")
    void quickselectTopK() {
        Random rnd = new Random(7);
        for (int trial = 0; trial < 50; trial++) {
            List<Change> list = new ArrayList<>();
            int n = 1 + rnd.nextInt(60);
            for (int i = 0; i < n; i++) {
                list.add(change("W" + i, rnd.nextInt(12), "ROUTINE_UNREVIEWED", 72.84));
            }
            int k = 1 + rnd.nextInt(15);
            var rk = IntegrityService.rank(list, k);
            int[] sorted = list.stream().mapToInt(Change::score).sorted().toArray();
            int kk = Math.min(k, n);
            assertEquals(sorted[n - kk], rk.kthScore());
            assertTrue(rk.matchesSort());
            assertEquals(kk, rk.topIds().size());
            int[] topScores = rk.topIds().stream().mapToInt(id -> list.get(Integer.parseInt(id.substring(1))).score()).sorted().toArray();
            assertTrue(Arrays.equals(Arrays.copyOfRange(sorted, n - kk, n), topScores), "top-k multiset of scores matches the sort");
        }
    }

    @Test
    @DisplayName("IN-9 (red-black alert index): in-order is score-descending; invariants hold after deletions")
    void alertIndex() {
        Random rnd = new Random(11);
        List<Change> list = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            list.add(change("W" + i, rnd.nextInt(15), i % 3 == 0 ? "LEGITIMATE_REVIEWED" : "ROUTINE_UNREVIEWED", 72.84));
        }
        var idx = IntegrityService.index(list);
        assertTrue(idx.invariantsHold());
        assertEquals(200 - 67, idx.size());
        assertEquals(67, idx.removedAfterReview());
        int prev = Integer.MAX_VALUE;
        for (String s : idx.inOrder()) {
            int score = Integer.parseInt(s.substring(s.indexOf('(') + 1, s.indexOf(')')));
            assertTrue(score <= prev);
            prev = score;
        }
    }

    @Test
    @DisplayName("IN-10 (segment tree): every strip range sum matches brute force; reviews are point updates")
    void segmentTreeStrips() {
        Random rnd = new Random(3);
        List<Change> list = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            list.add(change("W" + i, rnd.nextInt(10), i % 4 == 0 ? "LEGITIMATE_REVIEWED" : "ROUTINE_UNREVIEWED",
                    DADAR.west() + rnd.nextDouble() * (DADAR.east() - DADAR.west())));
        }
        for (int l = 0; l < IntegrityService.STRIPS; l++) {
            for (int r = l; r < IntegrityService.STRIPS; r++) {
                var st = IntegrityService.strips(DADAR, list, l, r);
                assertTrue(st.matchesBruteForce());
                assertEquals(st.risk().subList(l, r + 1).stream().mapToLong(Long::longValue).sum(), st.querySum());
            }
        }
        var st = IntegrityService.strips(DADAR, list, null, null);
        long expected = list.stream().filter(c -> !c.status().equals("LEGITIMATE_REVIEWED")).mapToLong(Change::score).sum();
        assertEquals(expected, st.querySum());
        assertEquals(st.risk().stream().mapToLong(Long::longValue).max().orElseThrow(), st.hottestRisk());
    }

    @Test
    @DisplayName("IN-11 (DSU impact): junctions cut off by removing a way match an independent BFS")
    void dsuCutOffMatchesBfs() {
        // a - b - c - d (way 1: a-b, way 2: b-c, way 3: c-d), plus e - f (way 4) and a loop way 5: a-c
        Map<Long, double[]> coords = new HashMap<>();
        for (long i = 1; i <= 6; i++) {
            coords.put(i, new double[] {19.0 + i * 0.001, 72.84});
        }
        List<IntegrityService.Edge> edges = List.of(edge(1, 1, 2), edge(2, 2, 3), edge(3, 3, 4), edge(4, 5, 6), edge(5, 1, 3));
        var ag = new IntegrityService.AreaGraph(edges, coords);
        int[] largest = IntegrityService.largestRoots(ag);
        assertEquals(2, IntegrityService.connectivity(ag, -1).components());
        assertEquals(0, IntegrityService.cutOff(ag, 2, largest), "way 2 has a detour through way 5");
        assertEquals(0, IntegrityService.cutOff(ag, 3, largest), "d is only on way 3, so it vanishes with the road");
        assertEquals(bfsCutOff(edges, 2), IntegrityService.cutOff(ag, 2, largest));
        var bridge = new IntegrityService.AreaGraph(List.of(edge(1, 1, 2), edge(2, 2, 3), edge(3, 3, 4), edge(6, 4, 1), edge(7, 3, 5),
                edge(8, 5, 6)), coords);
        int[] lg = IntegrityService.largestRoots(bridge);
        assertEquals(2, IntegrityService.cutOff(bridge, 7, lg), "removing the bridge 3-5 cuts off junctions 5 and 6");
        assertEquals(bfsCutOff(bridge.edges, 7), IntegrityService.cutOff(bridge, 7, lg));
    }

    @Test
    @DisplayName("IN-12 (validation): bad modifications, missing probe and out-of-range options give clear 400 errors")
    void validation() {
        assertThrows(ValidationException.class, () -> integrity.analyse(new IntegrityRequest(DADAR, null, null, null, null, null, null, null, null, null, null)));
        assertThrows(ValidationException.class, () -> integrity.analyse(request(List.of(new DemoModification("DELETE_CITY", null, null, null, null, null)), null)));
        assertThrows(ValidationException.class, () -> integrity.analyse(request(List.of(new DemoModification("REMOVE_WAY", 1L, null, null, null, null)), null)));
        List<DemoModification> many = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            many.add(new DemoModification("ADD_CONNECTION", null, 19.02, 72.84, 19.05, 72.85));
        }
        assertThrows(ValidationException.class, () -> integrity.analyse(request(many, null)));
        assertThrows(ValidationException.class, () -> integrity.analyse(new IntegrityRequest(DADAR, PROBE[0], PROBE[1], PROBE[2], PROBE[3], null, null, 0, null, null, false)));
        assertThrows(ValidationException.class, () -> integrity.analyse(new IntegrityRequest(DADAR, PROBE[0], PROBE[1], PROBE[2], PROBE[3], null, null, 5, 9, 3, false)));
        IntegrityResponse r = integrity.analyse(request(List.of(new DemoModification("ADD_CONNECTION", null, 19.02, 72.84, 19.05, 72.85)), List.of("D1")));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("cannot be marked legitimate")));
        assertEquals(1, r.statusCounts().simulatedTampering());
    }

    // ------------------------------------------------------------------ helpers

    private static IntegrityService.Edge edge(long way, long a, long b) {
        return new IntegrityService.Edge(way, a, b, false, 100, "primary", "w" + way,
                new double[] {19.0 + a * 0.001, 72.84, 19.0 + b * 0.001, 72.84});
    }

    private static Change change(String id, int score, String status, double lon) {
        return new Change(id, Long.parseLong(id.substring(1)) + 1, List.of("ADDED"), "REAL_OSM_EDIT", status, "primary", "", "absent",
                "x", 19.03, lon, List.of(), 0, false, score, List.of(), null);
    }

    /** Independent reference: BFS over the graph with and without the way. */
    private static int bfsCutOff(List<IntegrityService.Edge> edges, long way) {
        Map<Long, List<Long>> all = new HashMap<>();
        Map<Long, List<Long>> without = new HashMap<>();
        for (var e : edges) {
            for (var m : List.of(all, without)) {
                if (m == without && e.wayId() == way) {
                    continue;
                }
                m.computeIfAbsent(e.fromOsm(), k -> new ArrayList<>()).add(e.toOsm());
                m.computeIfAbsent(e.toOsm(), k -> new ArrayList<>()).add(e.fromOsm());
            }
        }
        List<List<Long>> compsAll = components(all);
        List<Long> main = compsAll.stream().max((x, y) -> Integer.compare(x.size(), y.size())).orElseThrow();
        List<List<Long>> compsWithout = components(without);
        List<Long> mainAfter = compsWithout.stream().filter(c -> c.stream().anyMatch(main::contains))
                .max((x, y) -> Integer.compare(x.size(), y.size())).orElse(List.of());
        return (int) main.stream().filter(without::containsKey).filter(n -> !mainAfter.contains(n)).count();
    }

    private static List<List<Long>> components(Map<Long, List<Long>> adj) {
        List<List<Long>> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (long s : adj.keySet()) {
            if (!seen.add(s)) {
                continue;
            }
            List<Long> comp = new ArrayList<>();
            ArrayDeque<Long> q = new ArrayDeque<>(List.of(s));
            while (!q.isEmpty()) {
                long x = q.poll();
                comp.add(x);
                for (long y : adj.get(x)) {
                    if (seen.add(y)) {
                        q.add(y);
                    }
                }
            }
            out.add(comp);
        }
        return out;
    }
}
