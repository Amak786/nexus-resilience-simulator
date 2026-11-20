package edu.nexus.mumbai;

import edu.nexus.mumbai.EventDtos.EventListResponse;
import edu.nexus.mumbai.EventDtos.EventStatsResponse;
import edu.nexus.mumbai.EventDtos.RefreshResponse;
import edu.nexus.mumbai.MumbaiDtos.Area;
import edu.nexus.mumbai.MumbaiDtos.ComponentsRequest;
import edu.nexus.mumbai.MumbaiDtos.ComponentsResponse;
import edu.nexus.mumbai.MumbaiDtos.CriticalLinksRequest;
import edu.nexus.mumbai.MumbaiDtos.CriticalLinksResponse;
import edu.nexus.mumbai.MumbaiDtos.NetworkResponse;
import edu.nexus.mumbai.MumbaiDtos.ProvenanceResponse;
import edu.nexus.mumbai.MumbaiDtos.RouteRequest;
import edu.nexus.mumbai.MumbaiDtos.RouteResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for the real Mumbai network. All analysis runs in Java on the server. */
@RestController
@RequestMapping("/api/mumbai")
@Tag(name = "Mumbai network")
public class MumbaiController {

    private final MumbaiService mumbai;
    private final EventService events;
    private final TourService tours;

    private final SearchService search;

    public MumbaiController(MumbaiService mumbai, EventService events, TourService tours, SearchService search) {
        this.mumbai = mumbai;
        this.events = events;
        this.tours = tours;
        this.search = search;
    }

    @Operation(summary = "One road segment with every attribute, its data class, and which OSM tags are missing")
    @GetMapping("/segments/{id}")
    public MumbaiDtos.SegmentDetail segment(@org.springframework.web.bind.annotation.PathVariable int id) {
        return mumbai.segmentDetail(id);
    }

    @Operation(summary = "Search road names in the snapshot (instant); with places=true also OSM Nominatim (explicit search only, rate-limited)")
    @GetMapping("/search")
    public MumbaiDtos.SearchResponse search(@RequestParam String q, @RequestParam(defaultValue = "false") boolean places) {
        return search.search(q, places);
    }

    @Operation(summary = "Percentiles of real segment lengths in an area with randomized Quickselect, checked against sorting")
    @GetMapping("/stats/lengths")
    public MumbaiDtos.LengthStatsResponse lengthStats(@RequestParam(required = false) Double south, @RequestParam(required = false) Double west,
                                                      @RequestParam(required = false) Double north, @RequestParam(required = false) Double east,
                                                      @RequestParam(required = false) java.util.List<Double> percentiles) {
        Area a = south == null && west == null && north == null && east == null ? null : new Area(south, west, north, east);
        return mumbai.lengthStats(a, percentiles);
    }

    @Operation(summary = "Data sources with data class (A real mapped, B verified observation, C derived, D estimate, E sample, not available), freshness, graph size and coverage")
    @GetMapping("/provenance")
    public ProvenanceResponse provenance() {
        return mumbai.provenance();
    }

    @Operation(summary = "Road segments touching an area (default: whole study area), with geometry and attributes")
    @GetMapping("/network")
    public NetworkResponse network(@RequestParam(required = false) Double south, @RequestParam(required = false) Double west,
                                   @RequestParam(required = false) Double north, @RequestParam(required = false) Double east) {
        Area a = south == null && west == null && north == null && east == null ? null : new Area(south, west, north, east);
        return mumbai.network(a);
    }

    @Operation(summary = "Route by real length (DISTANCE, default) or estimated free-flow time; optional closed segments; Dijkstra with a Fibonacci heap vs PriorityQueue")
    @PostMapping("/route")
    public RouteResponse route(@RequestBody RouteRequest request) {
        return mumbai.route(request);
    }

    @Operation(summary = "Weak components (DSU) before/after removing segments, plus optional origin/destination directed reachability (BFS)")
    @PostMapping("/components")
    public ComponentsResponse components(@RequestBody ComponentsRequest request) {
        return mumbai.components(request);
    }

    @Operation(summary = "Max flow / min cut between two points in an area: UNIT (structural, default) or LANE_ASSUMPTION capacities; Edmonds-Karp vs Ford-Fulkerson")
    @PostMapping("/critical-links")
    public CriticalLinksResponse criticalLinks(@RequestBody CriticalLinksRequest request) {
        return mumbai.criticalLinks(request);
    }

    @Operation(summary = "OSM map notes in a date range, from the red-black tree index (ordered by creation time)")
    @GetMapping("/events")
    public EventListResponse events(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                    @RequestParam(required = false) String status, @RequestParam(required = false) Integer limit) {
        return events.list(from, to, status, limit);
    }

    @Operation(summary = "Notes-per-day range statistics (segment tree) and resolution-time percentiles (randomized Quickselect)")
    @GetMapping("/events/stats")
    public EventStatsResponse eventStats(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return events.stats(from, to);
    }

    @Operation(summary = "Import the latest notes live from the OSM Notes API (Bloom-filter pre-check + exact verification); max once per 10 min")
    @PostMapping("/events/refresh")
    public RefreshResponse refresh() {
        return events.refresh();
    }

    @Operation(summary = "Multi-stop inspection tour: metric-TSP 2-approximation on real road distances")
    @PostMapping("/tour")
    public TourService.TourResponse tour(@RequestBody TourService.TourRequest request) {
        return tours.plan(request);
    }
}
