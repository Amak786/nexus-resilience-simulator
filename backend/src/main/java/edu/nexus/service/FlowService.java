package edu.nexus.service;

import edu.nexus.algorithms.flow.EdmondsKarp;
import edu.nexus.algorithms.flow.FlowNetwork;
import edu.nexus.algorithms.flow.FordFulkerson;
import edu.nexus.algorithms.flow.MaxFlowResult;
import edu.nexus.algorithms.flow.MinCut;
import edu.nexus.algorithms.flow.TraceOptions;
import edu.nexus.dto.FlowDtos.CutEdge;
import edu.nexus.dto.FlowDtos.DepotUsage;
import edu.nexus.dto.FlowDtos.FlowEdge;
import edu.nexus.dto.FlowDtos.FlowRequest;
import edu.nexus.dto.FlowDtos.FlowResponse;
import edu.nexus.dto.FlowDtos.RunStats;
import edu.nexus.dto.FlowDtos.TraceArc;
import edu.nexus.dto.FlowDtos.TraceStep;
import edu.nexus.dto.FlowDtos.ZoneAllocation;
import edu.nexus.model.CityNode;
import edu.nexus.model.NodeType;
import edu.nexus.model.Road;
import edu.nexus.model.ValidationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Runs max-flow on the city capacity network and explains the result. */
@Service
public class FlowService {

    public static final int MAX_TRACE_STEPS = 200;
    public static final int MAX_SNAPSHOT_EDGES = 400;

    /** The result of solving one scenario, shared with the Failure Lab. */
    public record Solved(CityNetwork network, MaxFlowResult result, MinCut.Result cut) {}

    public FlowResponse optimize(FlowRequest req) {
        if (req == null) {
            throw new ValidationException("request", "is required");
        }
        Map<String, CityNode> byId = DatasetValidator.validate(req.dataset());
        Set<String> roadIds = roadIds(req.dataset().roads());
        Set<String> failedRoads = Inputs.knownIds(req.failedRoadIds(), roadIds, "failedRoadIds", 3000);
        Set<String> failedNodes = Inputs.knownIds(req.failedNodeIds(), byId.keySet(), "failedNodeIds", 500);
        Map<String, Long> overrides = Inputs.overrides(req.capacityOverrides(), roadIds, "capacityOverrides");
        String algorithm = req.algorithm() == null ? "EDMONDS_KARP" : req.algorithm();
        if (!Set.of("EDMONDS_KARP", "FORD_FULKERSON", "COMPARE").contains(algorithm)) {
            throw new ValidationException("algorithm", "must be EDMONDS_KARP, FORD_FULKERSON or COMPARE");
        }
        CityNetwork cn = CityNetwork.build(req.dataset(), byId, req.source(), req.sink(), failedRoads, failedNodes, overrides);
        FlowNetwork g = cn.net();
        boolean trace = !Boolean.FALSE.equals(req.includeTrace());
        TraceOptions opts = trace ? new TraceOptions(MAX_TRACE_STEPS, g.edgeCount() <= MAX_SNAPSHOT_EDGES) : TraceOptions.NONE;

        MaxFlowResult baseline = null;
        MaxFlowResult primary;
        if (algorithm.equals("FORD_FULKERSON")) {
            primary = FordFulkerson.run(g, cn.source(), cn.sink(), opts);
        } else {
            if (algorithm.equals("COMPARE")) {
                baseline = FordFulkerson.run(g, cn.source(), cn.sink(), TraceOptions.NONE);
            }
            primary = EdmondsKarp.run(g, cn.source(), cn.sink(), opts); // last run: residual state = EK flow
        }
        MinCut.Result cut = certify(cn, primary);

        Set<Integer> cutSet = new HashSet<>(cut.cutEdges());
        List<FlowEdge> edges = new ArrayList<>();
        List<CutEdge> cutEdges = new ArrayList<>();
        for (CityNetwork.EdgeInfo e : cn.edges()) {
            long f = g.edgeFlow(e.edge());
            edges.add(new FlowEdge(e.edge(), e.kind().name(), e.roadId(), e.from(), e.to(), e.capacity(), f,
                    e.capacity() > 0 && f == e.capacity(), cutSet.contains(e.edge())));
        }
        cutEdges.addAll(explainCut(cn, cut, byId));

        List<ZoneAllocation> zones = null;
        Long totalDemand = null;
        Long unmet = null;
        if (cn.superSink()) {
            zones = zoneAllocations(cn, byId, failedNodes, req.dataset().nodes());
            totalDemand = zones.stream().mapToLong(ZoneAllocation::demand).sum();
            unmet = zones.stream().mapToLong(ZoneAllocation::unmet).sum();
        }
        List<DepotUsage> depots = null;
        Long totalSupply = null;
        if (cn.superSource()) {
            depots = new ArrayList<>();
            Map<String, Long> dispatched = new HashMap<>();
            for (CityNetwork.EdgeInfo e : cn.edges()) {
                if (e.kind() == CityNetwork.EdgeKind.SUPPLY) {
                    dispatched.put(e.to(), g.edgeFlow(e.edge()));
                }
            }
            long total = 0;
            for (CityNode n : req.dataset().nodes()) {
                if (n.type() == NodeType.DEPOT) {
                    long d = dispatched.getOrDefault(n.id(), 0L);
                    long supply = failedNodes.contains(n.id()) ? 0 : n.supply();
                    total += supply;
                    depots.add(new DepotUsage(n.id(), n.name(), supply, d, supply - d));
                }
            }
            totalSupply = total;
        }

        List<String> sourceSide = new ArrayList<>();
        for (int v = 0; v < cut.sourceSide().length; v++) {
            if (cut.sourceSide()[v]) {
                sourceSide.add(cn.labels().get(v));
            }
        }
        List<TraceStep> steps = primary.steps().stream().map(s -> new TraceStep(s.step(),
                s.nodes().stream().map(cn.labels()::get).collect(Collectors.toList()),
                s.arcs().stream().map(a -> new TraceArc(a.edge(), a.reverse())).collect(Collectors.toList()),
                s.bottleneck(), s.flowAfter(), s.edgeFlows())).collect(Collectors.toList());

        List<String> warnings = new ArrayList<>();
        if (primary.maxFlow() == 0) {
            warnings.add("No augmenting path exists: the sink is unreachable from the source with positive capacity.");
        }
        if (!failedRoads.isEmpty() || !failedNodes.isEmpty()) {
            warnings.add("Simulated failures: " + failedRoads.size() + " road(s), " + failedNodes.size() + " facility(ies).");
        }
        if (trace && !opts.edgeSnapshots()) {
            warnings.add("Network too large for per-step edge snapshots; paths are still recorded.");
        }
        Boolean agrees = baseline == null ? null : baseline.maxFlow() == primary.maxFlow();
        return new FlowResponse(primary.algorithm(), cn.labels().get(cn.source()), cn.labels().get(cn.sink()),
                primary.maxFlow(), cut.cutCapacity(), cut.cutCapacity() == primary.maxFlow(), edges, cutEdges,
                sourceSide, zones, depots, totalDemand, unmet, totalSupply, stats(primary),
                baseline == null ? null : stats(baseline), agrees, steps, primary.traceTruncated(),
                assumptions(), warnings);
    }

    /** Solves a scenario with Edmonds-Karp and no trace (used by the Failure Lab). */
    public static Solved solve(CityNetwork cn) {
        MaxFlowResult r = EdmondsKarp.run(cn.net(), cn.source(), cn.sink(), TraceOptions.NONE);
        return new Solved(cn, r, certify(cn, r));
    }

    /** Independent checks: flow conservation and max-flow = min-cut. Failure is a server bug (HTTP 500). */
    static MinCut.Result certify(CityNetwork cn, MaxFlowResult r) {
        long verified = MinCut.verifyFlow(cn.net(), cn.source(), cn.sink());
        if (verified != r.maxFlow()) {
            throw new IllegalStateException("flow verification disagrees with solver");
        }
        MinCut.Result cut = MinCut.fromResidual(cn.net(), cn.source());
        if (cut.cutCapacity() != r.maxFlow()) {
            throw new IllegalStateException("max-flow min-cut equality violated");
        }
        return cut;
    }

    static List<CutEdge> explainCut(CityNetwork cn, MinCut.Result cut, Map<String, CityNode> byId) {
        List<CutEdge> out = new ArrayList<>();
        for (int e : cut.cutEdges()) {
            CityNetwork.EdgeInfo info = cn.edges().get(e);
            String why = switch (info.kind()) {
                case SUPPLY -> "Depot " + info.to() + " ships all " + info.capacity()
                        + " units of its stock; extra stock here (if roads allow) could raise delivery.";
                case DEMAND -> "Zone " + info.from() + " receives its full demand of " + info.capacity()
                        + " units; this cut edge is a satisfied demand, not a network bottleneck.";
                case ROAD -> "Road " + info.roadId() + " (" + info.from() + " -> " + info.to() + ") is saturated at "
                        + info.capacity() + " units/window and lies on the minimum cut: raising its capacity is a candidate way to raise throughput.";
            };
            out.add(new CutEdge(e, info.kind().name(), info.roadId(), info.from(), info.to(), info.capacity(), why));
        }
        return out;
    }

    static List<ZoneAllocation> zoneAllocations(CityNetwork cn, Map<String, CityNode> byId, Set<String> failedNodes,
                                                List<CityNode> nodes) {
        Map<String, Long> received = new HashMap<>();
        for (CityNetwork.EdgeInfo e : cn.edges()) {
            if (e.kind() == CityNetwork.EdgeKind.DEMAND) {
                received.put(e.from(), cn.net().edgeFlow(e.edge()));
            }
        }
        List<ZoneAllocation> zones = new ArrayList<>();
        for (CityNode n : nodes) {
            if (n.type() != NodeType.DEMAND_ZONE) {
                continue;
            }
            long r = received.getOrDefault(n.id(), 0L);
            String status;
            if (failedNodes.contains(n.id())) {
                status = "FACILITY_FAILED";
            } else if (n.demand() == 0) {
                status = "NO_DEMAND";
            } else if (r == n.demand()) {
                status = "MET";
            } else if (r == 0) {
                status = "UNSERVED";
            } else {
                status = "PARTIAL";
            }
            zones.add(new ZoneAllocation(n.id(), n.name(), n.demand(), r, n.demand() - r, status));
        }
        return zones;
    }

    static Set<String> roadIds(List<Road> roads) {
        Set<String> ids = new HashSet<>();
        if (roads != null) {
            roads.forEach(r -> ids.add(r.id()));
        }
        return ids;
    }

    private static RunStats stats(MaxFlowResult r) {
        return new RunStats(r.algorithm(), r.maxFlow(), r.augmentations(), r.arcScans(),
                Math.round(r.elapsedMillis() * 1000) / 1000.0);
    }

    static List<String> assumptions() {
        return List.of(
                "Single commodity (generic relief kits), single 12-hour planning window.",
                "Road capacity is per direction; two-way roads become two arcs.",
                "Depot supply and zone demand are fixed for the window; travel time is not modelled.",
                "Max flow maximises total delivered units; it does not enforce fairness between zones.",
                "All data is synthetic decision-support input, not an official instruction.");
    }
}
