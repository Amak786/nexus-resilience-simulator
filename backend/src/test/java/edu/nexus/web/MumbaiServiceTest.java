package edu.nexus.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.nexus.model.ValidationException;
import edu.nexus.mumbai.EventDtos;
import edu.nexus.mumbai.EventService;
import edu.nexus.mumbai.MumbaiDtos.Area;
import edu.nexus.mumbai.MumbaiDtos.ComponentsRequest;
import edu.nexus.mumbai.MumbaiDtos.ComponentsResponse;
import edu.nexus.mumbai.MumbaiDtos.CriticalLinksRequest;
import edu.nexus.mumbai.MumbaiDtos.CriticalLinksResponse;
import edu.nexus.mumbai.MumbaiDtos.ProvenanceResponse;
import edu.nexus.mumbai.MumbaiDtos.RouteRequest;
import edu.nexus.mumbai.MumbaiDtos.RouteResponse;
import edu.nexus.mumbai.MumbaiService;
import edu.nexus.mumbai.TourService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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
    @DisplayName("MB-1 (provenance): every source has a category; live traffic is explicitly NOT_AVAILABLE")
    void provenance() {
        ProvenanceResponse p = mumbai.provenance();
        assertTrue(p.graph().nodes() > 10_000 && p.graph().segments() > 15_000, "real Greater Mumbai graph");
        assertTrue(p.sources().stream().anyMatch(s -> s.id().equals("roads") && s.category().equals("STATIC_GEOGRAPHIC")));
        assertTrue(p.sources().stream().anyMatch(s -> s.id().equals("live-traffic") && s.category().equals("NOT_AVAILABLE")));
        assertTrue(p.sources().stream().anyMatch(s -> s.id().equals("capacity") && s.category().equals("MODELLED_PROXY")));
        p.sources().forEach(s -> assertTrue(s.category() != null && s.name() != null));
    }

    @Test
    @DisplayName("MB-2 (route): Dadar to Bandra - both heaps agree; road length >= straight-line distance")
    void route() {
        RouteResponse r = mumbai.route(new RouteRequest(19.0180, 72.8470, 19.0560, 72.8450, "DISTANCE"));
        assertTrue(r.reachable());
        assertTrue(r.queuesAgree());
        double straight = haversine(r.from().lat(), r.from().lon(), r.to().lat(), r.to().lon());
        assertTrue(r.distanceM() >= straight - 1, "road distance " + r.distanceM() + " < straight line " + straight);
        assertEquals(r.segmentIds().size(), r.geometry().size());
        assertTrue(r.fibonacci().settled() > 0 && r.binary().settled() > 0);
    }

    @Test
    @DisplayName("MB-3 (DSU): removing segments never decreases the component count")
    void components() {
        ComponentsResponse base = mumbai.components(new ComponentsRequest(DADAR, List.of()));
        assertEquals(base.before().componentCount(), base.after().componentCount());
        List<Integer> remove = base.segmentComponent().stream().limit(25).map(sc -> sc[0]).toList();
        ComponentsResponse cut = mumbai.components(new ComponentsRequest(DADAR, remove));
        assertTrue(cut.after().componentCount() >= cut.before().componentCount());
        assertEquals(remove.size(), cut.removed());
        assertTrue(cut.segmentComponent().stream().filter(sc -> sc[1] == -1).count() == remove.size());
    }

    @Test
    @DisplayName("MB-4 (critical links): EK and FF agree, flow is verified and equals the min-cut capacity")
    void criticalLinks() {
        CriticalLinksResponse r = mumbai.criticalLinks(new CriticalLinksRequest(DADAR, 19.0180, 72.8470, 19.0480, 72.8400, null));
        assertTrue(r.maxFlowVph() > 0);
        assertTrue(r.algorithmsAgree());
        assertTrue(r.flowVerified());
        assertTrue(r.maxFlowEqualsMinCut());
        assertEquals(r.maxFlowVph(), r.cut().stream().mapToLong(c -> Math.round(c.capacityVph())).sum());
    }

    @Test
    @DisplayName("MB-5 (TSP planner): starts at the depot, executable <= planned <= 2 x MST bound")
    void tour() {
        TourService tours = new TourService(mumbai);
        TourService.TourResponse t = tours.plan(new TourService.TourRequest(List.of(
                new TourService.Stop("Dadar TT", 19.0180, 72.8470), new TourService.Stop("Sion", 19.0430, 72.8640),
                new TourService.Stop("Mahim", 19.0410, 72.8410), new TourService.Stop("Worli Naka", 19.0080, 72.8170)), 0));
        assertEquals("Dadar TT", t.tour().get(0));
        assertTrue(t.executableKm() <= t.plannedKm() + 1e-6);
        assertTrue(t.plannedKm() <= 2 * t.mstLowerBoundKm() + 1e-6);
        assertTrue(t.exactKm() <= t.plannedKm() + 1e-6);
    }

    @Test
    @DisplayName("MB-6 (invalid input): inverted area, area outside Mumbai, too-large area, far-away point")
    void invalidInput() {
        assertThrows(ValidationException.class, () -> mumbai.network(new Area(19.1, 72.8, 19.0, 72.9)));
        assertThrows(ValidationException.class, () -> mumbai.network(new Area(28.5, 77.0, 28.7, 77.3)));
        assertThrows(ValidationException.class, () -> mumbai.criticalLinks(
                new CriticalLinksRequest(MumbaiService.STUDY_AREA, 19.0, 72.84, 19.2, 72.85, null)));
        assertThrows(ValidationException.class, () -> mumbai.route(new RouteRequest(18.5, 72.5, 19.05, 72.84, "TIME")));
    }

    @Test
    @DisplayName("EV-1 (red-black index): events come back in creation order and the range count is O(log n)-consistent")
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
        assertTrue(s.totalNotes() > 0);
        assertEquals(s.totalNotes(), s.monthly().stream().mapToLong(EventDtos.MonthCount::count).sum());
        s.resolutionPercentiles().forEach(p -> assertTrue(p.matchesSortOracle()));
    }

    @Test
    @DisplayName("EV-3 (Bloom + exact store): a known id is a confirmed duplicate; a new test id is imported once")
    void bloomImport() throws Exception {
        long known = events.list(null, null, "all", 1).items().get(0).id();
        String body = "{\"features\":[" + feature(known) + "," + feature(999_000_000_001L) + "]}";
        Object first = invoke(body);
        EventDtos.RefreshResponse r1 = (EventDtos.RefreshResponse) first;
        assertEquals(1, r1.confirmedDuplicates());
        assertEquals(1, r1.newRecords());
        EventDtos.RefreshResponse r2 = (EventDtos.RefreshResponse) invoke(body);
        assertEquals(2, r2.confirmedDuplicates(), "re-importing the same records adds nothing");
        assertEquals(0, r2.newRecords());
        assertFalse(r2.possiblyPresent() < r2.confirmedDuplicates(), "every confirmed duplicate passed the Bloom pre-check");
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
