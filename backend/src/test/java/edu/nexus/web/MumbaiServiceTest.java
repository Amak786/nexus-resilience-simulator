package edu.nexus.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.nexus.algorithms.graph.RoadGraph;
import edu.nexus.dto.LabDtos;
import edu.nexus.model.ValidationException;
import edu.nexus.mumbai.EventDtos;
import edu.nexus.mumbai.EventService;
import edu.nexus.mumbai.MumbaiDtos.Area;
import edu.nexus.mumbai.MumbaiDtos.ComponentsRequest;
import edu.nexus.mumbai.MumbaiDtos.ComponentsResponse;
import edu.nexus.mumbai.MumbaiDtos.CriticalLinksRequest;
import edu.nexus.mumbai.MumbaiDtos.CriticalLinksResponse;
import edu.nexus.mumbai.MumbaiDtos.DataClass;
import edu.nexus.mumbai.MumbaiDtos.ProvenanceResponse;
import edu.nexus.mumbai.MumbaiDtos.RouteRequest;
import edu.nexus.mumbai.MumbaiDtos.RouteResponse;
import edu.nexus.mumbai.MumbaiService;
import edu.nexus.mumbai.SearchService;
import edu.nexus.mumbai.TourService;
import edu.nexus.service.LabService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Services on the committed OpenStreetMap snapshot (deterministic: no network access). */
class MumbaiServiceTest {

    // Dadar - Mahim - Bandra area (a bounded, explainable subgraph)
    private static final Area DADAR = new Area(19.010, 72.830, 19.060, 72.870);
    private static MumbaiService mumbai;
    private static EventService events;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void load() throws Exception {
        events = new EventService(MAPPER);
        mumbai = new MumbaiService(MAPPER, events, 20000, 6000);
    }

    @Test
    @DisplayName("MB-1 (provenance): every source has a data class; live traffic NOT_AVAILABLE; coverage and insight are consistent")
    void provenance() {
        ProvenanceResponse p = mumbai.provenance();
        assertEquals(MumbaiService.PRODUCT, p.product());
        assertTrue(p.graph().nodes() > 10_000 && p.graph().segments() > 15_000, "real Greater Mumbai graph");
        assertTrue(p.sources().stream().anyMatch(s -> s.id().equals("roads") && s.dataClass() == DataClass.REAL_MAPPED));
        assertTrue(p.sources().stream().anyMatch(s -> s.id().equals("live-traffic") && s.dataClass() == DataClass.NOT_AVAILABLE));
        assertTrue(p.sources().stream().anyMatch(s -> s.id().equals("capacity") && s.dataClass() == DataClass.ESTIMATE));
        var a = p.graph().attributes();
        assertEquals(p.graph().segments(), Math.round(a.segmentsMissingLanes() / (1 - a.lanesTagPresentPct() / 100)), 50);
        var c = p.graph().connectivity();
        assertEquals(p.graph().nodes(), c.largestComponentNodes() + c.nodesOutsideLargest());
        assertTrue(p.liveTrafficStatus().startsWith("Live traffic unavailable"));
    }

    @Test
    @DisplayName("MB-2 (route validity): consecutive segments connect, respect one-way direction, and sum to the reported length")
    void routeIsValidDirectedPath() {
        RouteResponse r = mumbai.route(new RouteRequest(19.0180, 72.8470, 19.0560, 72.8450, "DISTANCE", null));
        assertTrue(r.reachable());
        assertTrue(r.queuesAgree());
        RoadGraph g = mumbai.network().graph();
        int cur = r.from().node();
        double sum = 0;
        for (int s : r.segmentIds()) {
            boolean forward = g.segU(s) == cur;
            assertTrue(forward || g.segV(s) == cur, "segment " + s + " does not start at the current node");
            assertTrue(forward || !g.oneWay(s), "one-way segment " + s + " used against its direction");
            cur = forward ? g.segV(s) : g.segU(s);
            sum += g.lengthM(s);
        }
        assertEquals(r.to().node(), cur);
        assertEquals(r.distanceM(), sum, 0.2);
        double straight = haversine(r.from().lat(), r.from().lon(), r.to().lat(), r.to().lon());
        assertTrue(r.distanceM() >= straight - 1);
        assertFalse(r.explanation().cannotConclude().isEmpty());
    }

    @Test
    @DisplayName("MB-3 (closures): closing the route's segments forces a route that avoids them, at least as long, or unreachable")
    void routeWithClosures() {
        RouteResponse base = mumbai.route(new RouteRequest(19.0180, 72.8470, 19.0560, 72.8450, "DISTANCE", null));
        List<Integer> closed = base.segmentIds().subList(0, Math.min(3, base.segmentIds().size()));
        RouteResponse alt = mumbai.route(new RouteRequest(19.0180, 72.8470, 19.0560, 72.8450, "DISTANCE", closed));
        assertEquals(closed.size(), alt.avoided());
        if (alt.reachable()) {
            assertTrue(alt.distanceM() >= base.distanceM() - 0.1);
            alt.segmentIds().forEach(s -> assertFalse(closed.contains(s), "closed segment used"));
        }
        assertThrows(ValidationException.class, () -> mumbai.route(new RouteRequest(19.018, 72.847, 19.056, 72.845, "DISTANCE", List.of(-1))));
    }

    @Test
    @DisplayName("MB-4 (DSU vs reference): component count matches an independent BFS; removals never reduce it")
    void componentsMatchBfsReference() {
        ComponentsResponse base = mumbai.components(new ComponentsRequest(DADAR, List.of(), null, null, null, null));
        assertEquals(bfsComponents(base.segmentComponent(), Set.of()), base.after().componentCount());
        List<Integer> remove = base.segmentComponent().stream().limit(25).map(sc -> sc[0]).toList();
        ComponentsResponse cut = mumbai.components(new ComponentsRequest(DADAR, remove, null, null, null, null));
        assertTrue(cut.after().componentCount() >= cut.before().componentCount());
        assertEquals(bfsComponents(base.segmentComponent(), new HashSet<>(remove)), cut.after().componentCount());
    }

    @Test
    @DisplayName("MB-5 (weak vs directed): a directed path implies weak connectivity; reachable set fits in the weak component")
    void weakVersusDirected() {
        ComponentsResponse r = mumbai.components(new ComponentsRequest(DADAR, List.of(), 19.0180, 72.8470, 19.0480, 72.8400));
        var p = r.pair();
        assertTrue(!p.directedPathAfter() || p.weaklyConnectedAfter());
        assertTrue(p.reachableFromOriginAfter() <= p.originWeakComponentSizeAfter());
        assertTrue(r.explanation().calculated().contains("Disjoint-set union"));
    }

    @Test
    @DisplayName("MB-6 (critical links, UNIT): EK = FF = min cut = number of cut segments (Menger)")
    void criticalLinksStructural() {
        CriticalLinksResponse r = mumbai.criticalLinks(new CriticalLinksRequest(DADAR, 19.0180, 72.8470, 19.0480, 72.8400, null, null));
        assertEquals("UNIT", r.capacityModel());
        assertTrue(r.algorithmsAgree() && r.flowVerified() && r.maxFlowEqualsMinCut());
        assertEquals(r.maxFlow(), r.cut().size(), "with unit capacities the cut size equals the number of disjoint routes");
    }

    @Test
    @DisplayName("MB-7 (critical links, LANE_ASSUMPTION): certified flow, explanation states it is an estimate")
    void criticalLinksLaneAssumption() {
        CriticalLinksResponse r = mumbai.criticalLinks(new CriticalLinksRequest(DADAR, 19.0180, 72.8470, 19.0480, 72.8400, null, "LANE_ASSUMPTION"));
        assertTrue(r.algorithmsAgree() && r.maxFlowEqualsMinCut());
        assertEquals(r.maxFlow(), r.cut().stream().mapToLong(c -> Math.round(c.capacity())).sum());
        assertTrue(r.explanation().dataUsed().stream().anyMatch(d -> d.dataClass() == DataClass.ESTIMATE));
        assertThrows(ValidationException.class, () -> mumbai.criticalLinks(
                new CriticalLinksRequest(DADAR, 19.018, 72.847, 19.048, 72.84, null, "MEASURED")));
    }

    @Test
    @DisplayName("MB-8 (TSP planner): starts at the depot, executable <= planned <= 2 x MST bound, explanation present")
    void tour() {
        TourService tours = new TourService(mumbai);
        TourService.TourResponse t = tours.plan(new TourService.TourRequest(List.of(
                new TourService.Stop("Dadar TT", 19.0180, 72.8470), new TourService.Stop("Sion", 19.0430, 72.8640),
                new TourService.Stop("Mahim", 19.0410, 72.8410), new TourService.Stop("Worli Naka", 19.0080, 72.8170)), 0));
        assertEquals("Dadar TT", t.tour().get(0));
        assertTrue(t.executableKm() <= t.plannedKm() + 1e-6);
        assertTrue(t.plannedKm() <= 2 * t.mstLowerBoundKm() + 1e-6);
        assertTrue(t.exactKm() <= t.plannedKm() + 1e-6);
        assertFalse(t.explanation().canConclude().isEmpty());
    }

    @Test
    @DisplayName("MB-9 (search + detail): road-name search matches the snapshot; segment detail lists missing tags")
    void searchAndDetail() {
        SearchService search = new SearchService(mumbai, MAPPER);
        var res = search.search("road", false);
        assertFalse(res.roads().isEmpty());
        res.roads().forEach(r -> assertTrue(r.name().toLowerCase().contains("road")));
        assertTrue(res.places().isEmpty(), "no external call unless requested");
        assertThrows(ValidationException.class, () -> search.search("a", false));
        var d = mumbai.segmentDetail(0);
        assertTrue(d.missingAttributes().contains("live traffic"));
        assertThrows(ValidationException.class, () -> mumbai.segmentDetail(-5));
    }

    @Test
    @DisplayName("MB-10 (Quickselect on real lengths): percentiles of segment lengths match sorting")
    void lengthPercentiles() {
        var r = mumbai.lengthStats(DADAR, List.of(10.0, 50.0, 90.0, 100.0));
        assertTrue(r.observations() > 100);
        r.percentiles().forEach(p -> assertTrue(p.matchesSort(), "P" + p.percentile()));
        assertTrue(r.percentiles().get(0).value() <= r.percentiles().get(2).value());
    }

    @Test
    @DisplayName("MB-11 (invalid input): inverted area, outside Mumbai, too-large area, far point, bad metric")
    void invalidInput() {
        assertThrows(ValidationException.class, () -> mumbai.network(new Area(19.1, 72.8, 19.0, 72.9)));
        assertThrows(ValidationException.class, () -> mumbai.network(new Area(28.5, 77.0, 28.7, 77.3)));
        assertThrows(ValidationException.class, () -> mumbai.criticalLinks(
                new CriticalLinksRequest(MumbaiService.STUDY_AREA, 19.0, 72.84, 19.2, 72.85, null, null)));
        assertThrows(ValidationException.class, () -> mumbai.route(new RouteRequest(18.5, 72.5, 19.05, 72.84, "DISTANCE", null)));
        assertThrows(ValidationException.class, () -> mumbai.route(new RouteRequest(19.02, 72.84, 19.05, 72.84, "LIVE", null)));
    }

    @Test
    @DisplayName("LAB-BF: Bloom lab - inserted ids are never missed; possible hits are always checked against the exact set")
    void bloomLab() {
        LabService lab = new LabService();
        List<String> ins = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            ins.add("ID-" + i);
        }
        var r = lab.bloom(new LabDtos.LabBloomRequest(ins, List.of("ID-5", "ID-77", "NEW-1", "NEW-2"), 0.3, 5000));
        assertEquals("POSSIBLY_PRESENT", r.results().get(0).bloom());
        assertTrue(r.results().get(0).exactPresent());
        r.results().forEach(q -> {
            if (q.bloom().equals("POSSIBLY_PRESENT") && !q.exactPresent()) {
                assertTrue(q.verdict().contains("FALSE POSITIVE"));
            }
            if (q.bloom().equals("DEFINITELY_ABSENT")) {
                assertFalse(q.exactPresent(), "no false negatives");
            }
        });
        assertTrue(r.measuredFpr() > 0 && r.measuredFpr() < 0.6, "a 30% filter produces measurable false positives");
    }

    @Test
    @DisplayName("EV-1 (red-black index): events come back in creation order; range count consistent")
    void eventsOrdered() {
        EventDtos.EventListResponse r = events.list("2020-01-01", "2024-12-31", "all", 1000);
        assertTrue(r.returned() > 0);
        for (int i = 1; i < r.items().size(); i++) {
            assertTrue(r.items().get(i - 1).created().compareTo(r.items().get(i).created()) <= 0);
        }
        assertTrue(r.countInRange() >= r.returned());
        assertTrue(r.index().invariantsHold());
    }

    @Test
    @DisplayName("EV-2 (segment tree + Quickselect): range totals match a scan; percentiles match a sort")
    void eventStats() {
        EventDtos.EventStatsResponse s = events.stats("2015-01-01", "2025-12-31");
        assertTrue(s.matchesScan());
        assertEquals(s.totalNotes(), s.monthly().stream().mapToLong(EventDtos.MonthCount::count).sum());
        s.resolutionPercentiles().forEach(p -> assertTrue(p.matchesSortOracle()));
    }

    @Test
    @DisplayName("EV-3 (Bloom + exact store): a known id is a confirmed duplicate; a new test id is imported once")
    void bloomImport() throws Exception {
        long known = events.list(null, null, "all", 1).items().get(0).id();
        String body = "{\"features\":[" + feature(known) + "," + feature(999_000_000_001L) + "]}";
        EventDtos.RefreshResponse r1 = (EventDtos.RefreshResponse) invoke(body);
        assertEquals(1, r1.confirmedDuplicates());
        assertEquals(1, r1.newRecords());
        EventDtos.RefreshResponse r2 = (EventDtos.RefreshResponse) invoke(body);
        assertEquals(2, r2.confirmedDuplicates());
        assertEquals(0, r2.newRecords());
    }

    /** Independent reference: BFS over the undirected view of the listed segments. */
    private static int bfsComponents(List<int[]> segComp, Set<Integer> removed) {
        RoadGraph g = mumbai.network().graph();
        Map<Integer, List<Integer>> adj = new HashMap<>();
        for (int[] sc : segComp) {
            int e = sc[0];
            adj.computeIfAbsent(g.segU(e), k -> new ArrayList<>());
            adj.computeIfAbsent(g.segV(e), k -> new ArrayList<>());
            if (!removed.contains(e)) {
                adj.get(g.segU(e)).add(g.segV(e));
                adj.get(g.segV(e)).add(g.segU(e));
            }
        }
        Set<Integer> seen = new HashSet<>();
        int comps = 0;
        for (int start : adj.keySet()) {
            if (!seen.add(start)) {
                continue;
            }
            comps++;
            ArrayDeque<Integer> q = new ArrayDeque<>(List.of(start));
            while (!q.isEmpty()) {
                for (int v : adj.get(q.poll())) {
                    if (seen.add(v)) {
                        q.add(v);
                    }
                }
            }
        }
        return comps;
    }

    private static Object invoke(String body) throws Exception {
        var m = EventService.class.getDeclaredMethod("importFeatures", com.fasterxml.jackson.databind.JsonNode.class, Instant.class);
        m.setAccessible(true);
        return m.invoke(events, MAPPER.readTree(body), Instant.now());
    }

    /** Documented TEST record (id 999000000001 is not a real OSM note). */
    private static String feature(long id) {
        return "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[72.84,19.02]},\"properties\":{\"id\":" + id
                + ",\"status\":\"open\",\"date_created\":\"2026-01-02 03:04:05 UTC\",\"comments\":[{\"date\":\"2026-01-02 03:04:05 UTC\",\"action\":\"opened\",\"text\":\"test record\"}]}}";
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371008.8;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }
}
