package edu.nexus.dto;

import edu.nexus.model.CityDataset;

import java.util.List;
import java.util.Map;

/** Request and response types for the flow optimizer. */
public final class FlowDtos {

    private FlowDtos() {}

    /**
     * @param source            SUPER_SOURCE (all depots) or a node id; default SUPER_SOURCE
     * @param sink              SUPER_SINK (all demand zones) or a node id; default SUPER_SINK
     * @param algorithm         EDMONDS_KARP (default), FORD_FULKERSON or COMPARE (runs both)
     * @param capacityOverrides road id to replacement capacity (applied in both directions)
     * @param includeTrace      record augmenting paths for replay (default true)
     */
    public record FlowRequest(CityDataset dataset, String source, String sink, String algorithm,
                              List<String> failedRoadIds, List<String> failedNodeIds,
                              Map<String, Long> capacityOverrides, Boolean includeTrace) {}

    public record FlowEdge(int index, String kind, String roadId, String from, String to, long capacity,
                           long flow, boolean saturated, boolean inMinCut) {}

    public record CutEdge(int index, String kind, String roadId, String from, String to, long capacity,
                          String explanation) {}

    public record ZoneAllocation(String id, String name, long demand, long received, long unmet, String status) {}

    public record DepotUsage(String id, String name, long supply, long dispatched, long unused) {}

    public record RunStats(String algorithm, long maxFlow, int augmentations, long arcScans, double elapsedMs) {}

    public record TraceArc(int edge, boolean reverse) {}

    public record TraceStep(int step, List<String> nodes, List<TraceArc> arcs, long bottleneck, long flowAfter,
                            long[] edgeFlows) {}

    /**
     * {@code zones}, {@code totalDemand} and {@code unmetDemand} are only present when the sink is
     * SUPER_SINK, because unmet demand is only defined against the full demand set.
     */
    public record FlowResponse(String algorithm, String source, String sink, long maxFlow, long minCutCapacity,
                               boolean maxFlowEqualsMinCut, List<FlowEdge> edges, List<CutEdge> minCut,
                               List<String> sourceSideNodes, List<ZoneAllocation> zones, List<DepotUsage> depots,
                               Long totalDemand, Long unmetDemand, Long totalSupply, RunStats primary,
                               RunStats baseline, Boolean baselineAgrees, List<TraceStep> trace,
                               boolean traceTruncated, List<String> assumptions, List<String> warnings) {}
}
