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

    public MumbaiController(MumbaiService mumbai, EventService events, TourService tours) {
        this.mumbai = mumbai;
        this.events = events;
        this.tours = tours;
    }

    @Operation(summary = "Data sources with category (live / latest / static / derived / proxy / demo / not available), freshness and graph size")
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

    @Operation(summary = "Shortest or fastest route; Dijkstra with a Fibonacci heap, compared with java.util.PriorityQueue")
    @PostMapping("/route")
    public RouteResponse route(@RequestBody RouteRequest request) {
        return mumbai.route(request);
    }

    @Operation(summary = "Connected components in an area before/after removing segments (DSU, rebuilt after removals)")
    @PostMapping("/components")
    public ComponentsResponse components(@RequestBody ComponentsRequest request) {
        return mumbai.components(request);
    }

    @Operation(summary = "Max flow / min cut between two points in an area on the capacity proxy (Edmonds-Karp vs Ford-Fulkerson)")
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
