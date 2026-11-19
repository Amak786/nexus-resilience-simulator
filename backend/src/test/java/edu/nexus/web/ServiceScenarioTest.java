package edu.nexus.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.nexus.dto.FailureLabDtos.FailureLabRequest;
import edu.nexus.dto.FailureLabDtos.FailureLabResponse;
import edu.nexus.dto.FlowDtos.FlowRequest;
import edu.nexus.dto.FlowDtos.FlowResponse;
import edu.nexus.dto.IncidentDtos.PriorityOperation;
import edu.nexus.dto.IncidentDtos.PriorityRequest;
import edu.nexus.dto.IncidentDtos.PriorityResponse;
import edu.nexus.dto.IncidentDtos.ScreenRequest;
import edu.nexus.dto.IncidentDtos.ScreenResponse;
import edu.nexus.dto.IncidentDtos.TimelineOperation;
import edu.nexus.dto.IncidentDtos.TimelineRequest;
import edu.nexus.dto.ResilienceDtos.ResilienceRequest;
import edu.nexus.dto.ResilienceDtos.ResilienceResponse;
import edu.nexus.dto.RouteDtos.MatrixTspRequest;
import edu.nexus.dto.RouteDtos.RouteRequest;
import edu.nexus.dto.RouteDtos.RouteResponse;
import edu.nexus.model.CityDataset;
import edu.nexus.model.CityNode;
import edu.nexus.model.ValidationException;
import edu.nexus.service.DatasetService;
import edu.nexus.service.FailureLabService;
import edu.nexus.service.FlowService;
import edu.nexus.service.IncidentPriorityService;
import edu.nexus.service.ResilienceService;
import edu.nexus.service.RouteService;
import edu.nexus.service.ScreeningService;
import edu.nexus.service.SelfCheckService;
import edu.nexus.service.TimelineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Services on the bundled synthetic city, including failure and invalid-input paths. */
class ServiceScenarioTest {

    private static final CityDataset DEMO = new DatasetService(new ObjectMapper()).demo();

    @Test
    @DisplayName("SVC-1: demo city - EK max flow is certified by an equal min cut and never exceeds demand")
    void demoFlowCertified() {
        FlowResponse r = new FlowService().optimize(new FlowRequest(DEMO, null, null, "COMPARE", null, null, null, true));
        assertTrue(r.maxFlowEqualsMinCut());
        assertEquals(r.maxFlow(), r.minCutCapacity());
        assertEquals(Boolean.TRUE, r.baselineAgrees());
        assertEquals(r.totalDemand() - r.unmetDemand(), r.maxFlow());
        assertTrue(r.maxFlow() <= r.totalSupply());
        assertFalse(r.trace().isEmpty());
    }

    @Test
    @DisplayName("SVC-2: failing bridge road R22 isolates Island Hamlet (Z10) in both DSU and flow")
    void bridgeFailureIsolatesHamlet() {
        ResilienceResponse c = new ResilienceService().analyze(new ResilienceRequest(DEMO, List.of("R22"), null, true));
        assertTrue(c.newlyIsolatedZones().contains("Z10"));
        // The critical-road scan runs on the post-failure graph, so scan the intact city to find R22.
        ResilienceResponse intact = new ResilienceService().analyze(new ResilienceRequest(DEMO, null, null, true));
        assertTrue(intact.criticalRoads().stream().anyMatch(cr -> cr.roadId().equals("R22")
                && cr.zonesCutOffFromDepots().contains("Z10")));
        FlowResponse f = new FlowService().optimize(new FlowRequest(DEMO, null, null, null, List.of("R22"), null, null, false));
        assertEquals(0, f.zones().stream().filter(z -> z.id().equals("Z10")).findFirst().orElseThrow().received());
    }

    @Test
    @DisplayName("SVC-3: Failure Lab - drop is non-negative, percentiles match sort, restoring the failed road recovers it all")
    void failureLab() {
        FailureLabResponse r = new FailureLabService().simulate(new FailureLabRequest(DEMO, List.of("R2"), null, 12, 6, 50, 2, 2026L));
        assertTrue(r.throughputDrop() >= 0);
        assertEquals(r.throughputBefore() - r.throughputAfter(), r.throughputDrop());
        r.batch().percentiles().forEach(p -> assertTrue(p.matchesSortBaseline()));
        assertNotNull(r.timeline());
        assertTrue(r.timeline().totalShortfallAfter() >= r.timeline().totalShortfallBefore());
        r.recoveryActions().stream().filter(a -> a.type().equals("RESTORE_ROAD"))
                .forEach(a -> assertEquals(r.throughputDrop(), a.gain()));
        FailureLabResponse again = new FailureLabService().simulate(new FailureLabRequest(DEMO, List.of("R2"), null, 12, 6, 50, 2, 2026L));
        assertEquals(r.batch().impacts(), again.batch().impacts(), "seeded batch must be reproducible");
    }

    @Test
    @DisplayName("SVC-4: incident queue - update moves an incident, resolve removes it, invariants hold")
    void incidentQueue() {
        IncidentPriorityService s = new IncidentPriorityService();
        PriorityResponse r = s.build(new PriorityRequest(DEMO, List.of(
                new PriorityOperation("UPDATE", "INC-1010", null, null, 5, 9000L),
                new PriorityOperation("RESOLVE", "INC-1001", null, null, null, null),
                new PriorityOperation("INSERT", "INC-2000", "New culvert collapse", "Z2", 4, 1500L))));
        assertTrue(r.metrics().invariantsHold());
        assertEquals(DEMO.incidents().size(), r.queue().size());
        for (int i = 1; i < r.queue().size(); i++) {
            assertTrue(r.queue().get(i - 1).score() >= r.queue().get(i).score());
        }
        assertFalse(r.queue().stream().anyMatch(q -> q.id().equals("INC-1001")));
        assertThrows(ValidationException.class, () -> s.build(new PriorityRequest(DEMO,
                List.of(new PriorityOperation("INSERT", "INC-1002", "dup", null, 2, 10L)))));
    }

    @Test
    @DisplayName("SVC-5: Bloom screening - duplicates confirmed exactly, false positives resolved, FPR measured")
    void screening() {
        ScreenResponse r = new ScreeningService().screen(new ScreenRequest(DEMO, 2000L, 0.2, 1L, null, 1500,
                List.of("INC-1003", "INC-9999", "INC-1003", "brand-new"), 20_000));
        assertEquals("CONFIRMED_DUPLICATE", r.results().get(0).verdict());
        assertEquals(3, r.results().size(), "repeated incoming ids are screened once");
        assertTrue(r.measuredFpr() > 0 && r.measuredFpr() < 0.4);
        r.results().forEach(x -> {
            if (x.bloom().equals("DEFINITELY_ABSENT")) {
                assertEquals("NEW", x.verdict());
            }
        });
    }

    @Test
    @DisplayName("SVC-6: routes - depot first, unreachable stop listed, non-metric lab matrix rejected")
    void routes() {
        RouteService s = new RouteService();
        RouteResponse r = s.plan(new RouteRequest(DEMO, "D1", List.of("Z1", "Z3", "Z10", "Z7"), List.of("R22"), null));
        assertEquals("D1", r.tour().get(0));
        assertEquals(List.of("Z10"), r.unreachableStops());
        assertTrue(r.routeKm() <= 2 * r.mstLowerBoundKm() + 1e-6);
        assertNotNull(r.exactKm());
        assertTrue(r.routeKm() >= r.exactKm() - 1e-6);
        assertThrows(ValidationException.class, () -> s.planMatrix(new MatrixTspRequest(null,
                List.of(List.of(0.0, 1.0, 10.0), List.of(1.0, 0.0, 1.0), List.of(10.0, 1.0, 0.0)), 0)));
    }

    @Test
    @DisplayName("SVC-7: segment-tree timeline operations agree with the naive array")
    void timeline() {
        var r = new TimelineService().run(new TimelineRequest(DEMO, null, List.of(
                new TimelineOperation("SUM", 0, 23, null), new TimelineOperation("ADD", 10, 30, 25L),
                new TimelineOperation("MAX", 0, 71, null), new TimelineOperation("SET", 5, null, 0L),
                new TimelineOperation("SUM", 0, 71, null))));
        r.results().forEach(x -> assertTrue(x.matchesNaiveBaseline(), "op " + x.index()));
    }

    @Test
    @DisplayName("SVC-8 (invalid input): duplicate ids, bad endpoints, negative capacity, unknown failures are rejected")
    void invalidDatasets() {
        List<CityNode> nodes = new ArrayList<>(DEMO.nodes());
        nodes.add(DEMO.nodes().get(0));
        CityDataset dup = new CityDataset(DEMO.meta(), nodes, DEMO.roads(), null, null);
        FlowService f = new FlowService();
        assertThrows(ValidationException.class, () -> f.optimize(new FlowRequest(dup, null, null, null, null, null, null, false)));
        assertThrows(ValidationException.class, () -> f.optimize(new FlowRequest(DEMO, "Z1", "Z1", null, null, null, null, false)));
        assertThrows(ValidationException.class, () -> f.optimize(new FlowRequest(DEMO, null, null, null, List.of("R999"), null, null, false)));
        assertThrows(ValidationException.class, () -> f.optimize(new FlowRequest(DEMO, null, null, null, null, null, Map.of("R1", -5L), false)));
        CityDataset empty = new CityDataset(DEMO.meta(), List.of(), List.of(), null, null);
        assertThrows(ValidationException.class, () -> f.optimize(new FlowRequest(empty, null, null, null, null, null, null, false)));
    }

    @Test
    @DisplayName("SVC-9: live self-check passes every property")
    void selfCheck() {
        SelfCheckService.SelfCheckReport r = new SelfCheckService().run();
        r.checks().forEach(c -> assertTrue(c.passed(), c.algorithm() + ": " + c.detail()));
    }
}
