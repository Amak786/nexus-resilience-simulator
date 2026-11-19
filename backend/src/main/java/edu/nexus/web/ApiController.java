package edu.nexus.web;

import edu.nexus.dto.FailureLabDtos.FailureLabRequest;
import edu.nexus.dto.FailureLabDtos.FailureLabResponse;
import edu.nexus.dto.FlowDtos.FlowRequest;
import edu.nexus.dto.FlowDtos.FlowResponse;
import edu.nexus.dto.IncidentDtos.PriorityRequest;
import edu.nexus.dto.IncidentDtos.PriorityResponse;
import edu.nexus.dto.IncidentDtos.ScreenRequest;
import edu.nexus.dto.IncidentDtos.ScreenResponse;
import edu.nexus.dto.IncidentDtos.TimelineRequest;
import edu.nexus.dto.IncidentDtos.TimelineResponse;
import edu.nexus.dto.LabDtos.AlgorithmInfo;
import edu.nexus.dto.LabDtos.LabDsuRequest;
import edu.nexus.dto.LabDtos.LabDsuResponse;
import edu.nexus.dto.LabDtos.LabFlowRequest;
import edu.nexus.dto.LabDtos.LabFlowResponse;
import edu.nexus.dto.LabDtos.LabRbRequest;
import edu.nexus.dto.LabDtos.LabRbResponse;
import edu.nexus.dto.LabDtos.LabSelectRequest;
import edu.nexus.dto.LabDtos.LabSelectResponse;
import edu.nexus.dto.ResilienceDtos.ResilienceRequest;
import edu.nexus.dto.ResilienceDtos.ResilienceResponse;
import edu.nexus.dto.RouteDtos.MatrixTspRequest;
import edu.nexus.dto.RouteDtos.RouteRequest;
import edu.nexus.dto.RouteDtos.RouteResponse;
import edu.nexus.model.CityDataset;
import edu.nexus.service.AlgorithmCatalog;
import edu.nexus.service.BenchmarkService;
import edu.nexus.service.DatasetService;
import edu.nexus.service.FailureLabService;
import edu.nexus.service.FlowService;
import edu.nexus.service.IncidentPriorityService;
import edu.nexus.service.LabService;
import edu.nexus.service.ResilienceService;
import edu.nexus.service.RouteService;
import edu.nexus.service.ScreeningService;
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

/**
 * REST endpoints. Controllers only route: validation and logic live in the services, and every
 * assessed algorithm runs in Java. The API is stateless: the client sends the dataset with each call.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final DatasetService datasets;
    private final FlowService flow;
    private final FailureLabService failureLab;
    private final ResilienceService resilience;
    private final IncidentPriorityService priority;
    private final ScreeningService screening;
    private final TimelineService timeline;
    private final RouteService routes;
    private final LabService lab;
    private final BenchmarkService benchmarks;
    private final SelfCheckService selfCheck;

    public ApiController(DatasetService datasets, FlowService flow, FailureLabService failureLab,
                         ResilienceService resilience, IncidentPriorityService priority, ScreeningService screening,
                         TimelineService timeline, RouteService routes, LabService lab, BenchmarkService benchmarks,
                         SelfCheckService selfCheck) {
        this.datasets = datasets;
        this.flow = flow;
        this.failureLab = failureLab;
        this.resilience = resilience;
        this.priority = priority;
        this.screening = screening;
        this.timeline = timeline;
        this.routes = routes;
        this.lab = lab;
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
        m.put("version", "1.0.0");
        m.put("java", System.getProperty("java.version"));
        m.put("time", Instant.now().toString());
        return m;
    }

    @Tag(name = "System")
    @Operation(summary = "Implemented algorithms with complexity and limitations")
    @GetMapping("/algorithms")
    public List<AlgorithmInfo> algorithms() {
        return AlgorithmCatalog.ALL;
    }

    @Tag(name = "Dataset")
    @Operation(summary = "The bundled synthetic city (Varunapuri)")
    @GetMapping("/dataset/demo")
    public CityDataset demo() {
        return datasets.demo();
    }

    @Tag(name = "Dataset")
    @Operation(summary = "Validate a dataset and return summary statistics")
    @PostMapping("/dataset/validate")
    public DatasetService.DatasetSummary validate(@RequestBody CityDataset dataset) {
        return datasets.summarize(dataset);
    }

    @Tag(name = "Dataset")
    @Operation(summary = "Build a dataset from nodes and roads CSV text")
    @PostMapping("/dataset/import-csv")
    public DatasetService.ImportResult importCsv(@RequestBody DatasetService.CsvImportRequest request) {
        return datasets.importCsv(request);
    }

    @Tag(name = "Flow")
    @Operation(summary = "Max flow (Edmonds-Karp and/or Ford-Fulkerson), min cut, allocation and trace")
    @PostMapping("/flow/optimize")
    public FlowResponse optimize(@RequestBody FlowRequest request) {
        return flow.optimize(request);
    }

    @Tag(name = "Failure Lab")
    @Operation(summary = "Simulate a failure: throughput, min cut, DSU, segment-tree timeline, Quickselect percentiles, recovery ranking")
    @PostMapping("/failure-lab/simulate")
    public FailureLabResponse simulate(@RequestBody FailureLabRequest request) {
        return failureLab.simulate(request);
    }

    @Tag(name = "Resilience")
    @Operation(summary = "Connected components (DSU) before and after failures, plus critical-road scan")
    @PostMapping("/resilience/components")
    public ResilienceResponse components(@RequestBody ResilienceRequest request) {
        return resilience.analyze(request);
    }

    @Tag(name = "Incidents")
    @Operation(summary = "Incident priority queue in a red-black tree, with optional insert/update/resolve/lookup operations")
    @PostMapping("/incidents/priority")
    public PriorityResponse priority(@RequestBody PriorityRequest request) {
        return priority.build(request);
    }

    @Tag(name = "Incidents")
    @Operation(summary = "Bloom-filter duplicate screening with exact verification and measured false-positive rate")
    @PostMapping("/incidents/screen")
    public ScreenResponse screen(@RequestBody ScreenRequest request) {
        return screening.screen(request);
    }

    @Tag(name = "Timeline")
    @Operation(summary = "Segment-tree range queries and updates on an hourly series (checked against a plain array)")
    @PostMapping("/timeline/query")
    public TimelineResponse timeline(@RequestBody TimelineRequest request) {
        return timeline.run(request);
    }

    @Tag(name = "Routes")
    @Operation(summary = "Delivery tour by MST + preorder 2-approximation over road distances")
    @PostMapping("/routes/plan")
    public RouteResponse plan(@RequestBody RouteRequest request) {
        return routes.plan(request);
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
    @PostMapping("/lab/quickselect")
    public LabSelectResponse labSelect(@RequestBody LabSelectRequest request) {
        return lab.quickselect(request);
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
