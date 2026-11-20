package edu.nexus.web;

import edu.nexus.dto.LabDtos.AlgorithmInfo;
import edu.nexus.dto.LabDtos.LabDsuRequest;
import edu.nexus.dto.LabDtos.LabDsuResponse;
import edu.nexus.dto.LabDtos.LabFlowRequest;
import edu.nexus.dto.LabDtos.LabFlowResponse;
import edu.nexus.dto.LabDtos.LabHeapRequest;
import edu.nexus.dto.LabDtos.LabHeapResponse;
import edu.nexus.dto.LabDtos.LabRbRequest;
import edu.nexus.dto.LabDtos.LabRbResponse;
import edu.nexus.dto.LabDtos.LabSelectRequest;
import edu.nexus.dto.LabDtos.LabSelectResponse;
import edu.nexus.dto.LabDtos.TimelineRequest;
import edu.nexus.dto.LabDtos.TimelineResponse;
import edu.nexus.dto.RouteDtos.MatrixTspRequest;
import edu.nexus.dto.RouteDtos.RouteResponse;
import edu.nexus.service.AlgorithmCatalog;
import edu.nexus.service.BenchmarkService;
import edu.nexus.service.LabService;
import edu.nexus.service.RouteService;
import edu.nexus.service.SelfCheckService;
import edu.nexus.service.TimelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** System, Algorithm Lab and evidence endpoints. The Mumbai workflow lives in {@code MumbaiController}. */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final LabService lab;
    private final TimelineService timeline;
    private final RouteService routes;
    private final BenchmarkService benchmarks;
    private final SelfCheckService selfCheck;

    public ApiController(LabService lab, TimelineService timeline, RouteService routes, BenchmarkService benchmarks,
                         SelfCheckService selfCheck) {
        this.lab = lab;
        this.timeline = timeline;
        this.routes = routes;
        this.benchmarks = benchmarks;
        this.selfCheck = selfCheck;
    }

    @Tag(name = "System")
    @Operation(summary = "Liveness check used by the hosting platform")
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("service", "nexus-api");
        m.put("version", "2.0.0");
        m.put("java", System.getProperty("java.version"));
        m.put("time", Instant.now().toString());
        return m;
    }

    @Tag(name = "System")
    @Operation(summary = "Implemented algorithms with complexity, where they are used and their limitations")
    @GetMapping("/algorithms")
    public List<AlgorithmInfo> algorithms() {
        return AlgorithmCatalog.ALL;
    }

    @Tag(name = "Algorithm Lab")
    @PostMapping("/lab/flow")
    public LabFlowResponse labFlow(@RequestBody LabFlowRequest request) {
        return lab.flow(request);
    }

    @Tag(name = "Algorithm Lab")
    @PostMapping("/lab/dsu")
    public LabDsuResponse labDsu(@RequestBody LabDsuRequest request) {
        return lab.dsu(request);
    }

    @Tag(name = "Algorithm Lab")
    @PostMapping("/lab/rbtree")
    public LabRbResponse labRb(@RequestBody LabRbRequest request) {
        return lab.rbtree(request);
    }

    @Tag(name = "Algorithm Lab")
    @PostMapping("/lab/fibheap")
    public LabHeapResponse labHeap(@RequestBody LabHeapRequest request) {
        return lab.fibonacciHeap(request);
    }

    @Tag(name = "Algorithm Lab")
    @PostMapping("/lab/bloom")
    public edu.nexus.dto.LabDtos.LabBloomResponse labBloom(@RequestBody edu.nexus.dto.LabDtos.LabBloomRequest request) {
        return lab.bloom(request);
    }

    @Tag(name = "Algorithm Lab")
    @PostMapping("/lab/quickselect")
    public LabSelectResponse labSelect(@RequestBody LabSelectRequest request) {
        return lab.quickselect(request);
    }

    @Tag(name = "Algorithm Lab")
    @PostMapping("/lab/segment-tree")
    public TimelineResponse labSegmentTree(@RequestBody TimelineRequest request) {
        return timeline.run(request);
    }

    @Tag(name = "Algorithm Lab")
    @Operation(summary = "Metric TSP on a user-supplied distance matrix (non-metric input is rejected)")
    @PostMapping("/lab/tsp")
    public RouteResponse labTsp(@RequestBody MatrixTspRequest request) {
        return routes.planMatrix(request);
    }

    @Tag(name = "Evidence")
    @Operation(summary = "List of benchmark suites")
    @GetMapping("/benchmarks")
    public List<String> benchmarkList() {
        return BenchmarkService.ALGORITHMS;
    }

    @Tag(name = "Evidence")
    @Operation(summary = "Run one benchmark suite (three input sizes, warm-up, repeated timing, baseline)")
    @PostMapping("/benchmarks/{algorithm}")
    public BenchmarkService.Report benchmark(@PathVariable String algorithm) {
        return benchmarks.run(algorithm);
    }

    @Tag(name = "Evidence")
    @Operation(summary = "Run seeded property checks of every algorithm on the live server")
    @PostMapping("/selfcheck")
    public SelfCheckService.SelfCheckReport selfCheck() {
        return selfCheck.run();
    }
}
