package edu.nexus.mumbai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Request/response types for the Mumbai network endpoints. */
public final class MumbaiDtos {

    private MumbaiDtos() {}

    /** Geographic rectangle in degrees (WGS-84). */
    public record Area(Double south, Double west, Double north, Double east) {}

    /**
     * Data-source description. {@code category} is one of VERIFIED_LIVE, LATEST_AVAILABLE,
     * STATIC_GEOGRAPHIC, DERIVED_ESTIMATE, MODELLED_PROXY, DEMONSTRATION, NOT_AVAILABLE.
     */
    public record DataSource(String id, String name, String category, String source, String sourceUrl, String licence,
                             String retrievedAt, String dataTimestamp, Long ageDays, String freshness, String coverage,
                             String notes) {}

    public record GraphStats(int nodes, int segments, int arcs, int oneWaySegments, double totalKm,
                             List<ClassCount> byClass) {}

    public record ClassCount(String roadClass, int segments, double km) {}

    public record ProvenanceResponse(Area studyArea, List<DataSource> sources, GraphStats graph, JsonNode assumptions) {}

    public record Segment(int id, long wayId, String name, String roadClass, int from, int to, boolean oneWay,
                          double lengthM, double travelTimeS, double capacityVph, Integer lanesTag, Integer maxspeedTag,
                          double[] geometry) {}

    public record NetworkResponse(Area area, int nodes, int segments, boolean truncated, List<Segment> items) {}

    public record Snap(int node, long osmNodeId, double lat, double lon, double snapDistanceM) {}

    public record QueueStats(String queue, int settled, long relaxations, long heapOperations, double elapsedMs) {}

    public record RoadStep(String name, String roadClass, double lengthM) {}

    public record RouteRequest(Double fromLat, Double fromLon, Double toLat, Double toLon, String metric) {}

    public record RouteResponse(Snap from, Snap to, String metric, boolean reachable, double distanceM,
                                double travelTimeS, List<Integer> segmentIds, List<double[]> geometry,
                                List<RoadStep> steps, QueueStats fibonacci, QueueStats binary, boolean queuesAgree,
                                List<String> notes) {}

    public record ComponentsRequest(Area area, List<Integer> removedSegmentIds) {}

    public record Component(int id, int nodes, int segments, double centerLat, double centerLon) {}

    public record ComponentSummary(int componentCount, int largestNodes, double largestShare, int isolatedNodes,
                                   int successfulUnions, int redundantUnions, List<Component> components) {}

    /** {@code segmentComponent}: pairs [segmentId, componentId] after removals (component 0 = largest). */
    public record ComponentsResponse(Area area, int nodes, int segments, int removed, ComponentSummary before,
                                     ComponentSummary after, List<int[]> segmentComponent, List<String> notes) {}

    public record CriticalLinksRequest(Area area, Double sourceLat, Double sourceLon, Double sinkLat, Double sinkLon,
                                       List<Integer> removedSegmentIds) {}

    public record CutSegment(int segmentId, String name, String roadClass, int fromNode, int toNode,
                             double capacityVph, double[] geometry) {}

    public record FlowStats(String algorithm, long maxFlow, int augmentations, long arcScans, double elapsedMs) {}

    public record PathPreview(int step, long bottleneck, long flowAfter, List<Integer> segmentIds) {}

    public record CriticalLinksResponse(Area area, Snap source, Snap sink, int nodes, int arcs, long maxFlowVph,
                                        long minCutCapacityVph, boolean maxFlowEqualsMinCut, boolean flowVerified,
                                        List<CutSegment> cut, List<long[]> segmentFlows, FlowStats edmondsKarp,
                                        FlowStats fordFulkerson, boolean algorithmsAgree, List<PathPreview> paths,
                                        List<String> assumptions, List<String> warnings) {}
}
