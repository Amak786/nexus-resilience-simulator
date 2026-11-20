package edu.nexus.mumbai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Request/response types for the Mumbai road-network endpoints.
 *
 * <p><b>Data classes</b> used in every response (see {@link DataClass}):
 * A REAL_MAPPED, B VERIFIED_OBSERVATION, C DERIVED_RESULT, D ESTIMATE, E SAMPLE; plus NOT_AVAILABLE for
 * data that would be needed but has no verified source.
 */
public final class MumbaiDtos {

    private MumbaiDtos() {}

    /** Classification of every displayed value. */
    public enum DataClass {
        /** A: sourced from OpenStreetMap or another documented map source. */
        REAL_MAPPED,
        /** B: sourced from an accessible documented provider, with a source timestamp and coverage. */
        VERIFIED_OBSERVATION,
        /** C: calculated from real input data by an algorithm. */
        DERIVED_RESULT,
        /** D: inferred because a verified measurement is unavailable. */
        ESTIMATE,
        /** E: development, offline-demonstration or test data. */
        SAMPLE,
        /** Needed for a feature but no verified source is connected. */
        NOT_AVAILABLE
    }

    /** One input or output and its data class. */
    public record DataItem(String item, DataClass dataClass) {}

    /** Answers: what data was used, what was calculated, what can and cannot be concluded. */
    public record Explanation(List<DataItem> dataUsed, String calculated, List<String> canConclude,
                              List<String> cannotConclude) {}

    /** Geographic rectangle in degrees (WGS-84). */
    public record Area(Double south, Double west, Double north, Double east) {}

    public record DataSource(String id, String name, DataClass dataClass, String status, String source, String sourceUrl,
                             String licence, String retrievedAt, String dataTimestamp, Long ageDays, String freshness,
                             String coverage, String notes) {}

    public record ClassCount(String roadClass, int segments, double km) {}

    /** Share of segments (by count) where an OSM attribute is present. */
    public record AttributeCoverage(double namePresentPct, double lanesTagPresentPct, double maxspeedTagPresentPct,
                                    int segmentsMissingName, int segmentsMissingLanes, int segmentsMissingMaxspeed) {}

    /** DSU over the whole snapshot (weak connectivity, undirected view). */
    public record NetworkInsight(int weakComponents, int largestComponentNodes, double largestComponentShare,
                                 int nodesOutsideLargest, String summary) {}

    public record GraphStats(int nodes, int segments, int arcs, int oneWaySegments, double totalKm,
                             List<ClassCount> byClass, AttributeCoverage attributes, NetworkInsight connectivity) {}

    public record ProvenanceResponse(String product, Area studyArea, List<DataSource> sources, GraphStats graph,
                                     JsonNode assumptions, String liveTrafficStatus) {}

    public record Segment(int id, long wayId, String name, String roadClass, int from, int to, boolean oneWay,
                          double lengthM, double travelTimeS, double capacityVph, Integer lanesTag, Integer maxspeedTag,
                          double[] geometry) {}

    public record NetworkResponse(Area area, int nodes, int segments, boolean truncated, List<Segment> items) {}

    /** Full attribute view of one segment, including which OSM tags are missing. */
    public record SegmentDetail(Segment segment, List<DataItem> attributes, List<String> missingAttributes,
                                String osmUrl) {}

    public record Snap(int node, long osmNodeId, double lat, double lon, double snapDistanceM) {}

    public record QueueStats(String queue, int settled, long relaxations, long heapOperations, double elapsedMs) {}

    public record RoadStep(String name, String roadClass, double lengthM) {}

    /**
     * @param metric          DISTANCE (default; real OSM length) or ESTIMATED_TIME (free-flow speed estimate)
     * @param avoidSegmentIds closed segments that the route must not use
     */
    public record RouteRequest(Double fromLat, Double fromLon, Double toLat, Double toLon, String metric,
                               List<Integer> avoidSegmentIds) {}

    public record RouteResponse(Snap from, Snap to, String metric, boolean reachable, double distanceM,
                                double estimatedTimeS, double maxspeedTaggedSharePct, List<Integer> segmentIds,
                                List<double[]> geometry, List<RoadStep> steps, QueueStats fibonacci, QueueStats binary,
                                boolean queuesAgree, int avoided, Explanation explanation) {}

    public record ComponentsRequest(Area area, List<Integer> removedSegmentIds, Double fromLat, Double fromLon,
                                    Double toLat, Double toLon) {}

    public record Component(int id, int nodes, int segments, double centerLat, double centerLon) {}

    public record ComponentSummary(int componentCount, int largestNodes, double largestShare, int isolatedNodes,
                                   int successfulUnions, int redundantUnions, List<Component> components) {}

    /**
     * Connectivity of a chosen origin/destination pair after removals: weak connectivity (DSU, undirected)
     * versus a directed path that respects one-way rules (BFS).
     */
    public record PairConnectivity(Snap from, Snap to, boolean weaklyConnectedBefore, boolean weaklyConnectedAfter,
                                   boolean directedPathBefore, boolean directedPathAfter, int reachableFromOriginAfter,
                                   int originWeakComponentSizeAfter) {}

    public record ComponentsResponse(Area area, int nodes, int segments, int removed, ComponentSummary before,
                                     ComponentSummary after, List<int[]> segmentComponent, PairConnectivity pair,
                                     Explanation explanation) {}

    /**
     * @param capacityModel UNIT (default: every directed road segment counts 1, so max flow = number of
     *                      edge-disjoint routes) or LANE_ASSUMPTION (assumed vehicles/hour from lanes x per-lane rule)
     */
    public record CriticalLinksRequest(Area area, Double sourceLat, Double sourceLon, Double sinkLat, Double sinkLon,
                                       List<Integer> removedSegmentIds, String capacityModel) {}

    public record CutSegment(int segmentId, String name, String roadClass, int fromNode, int toNode,
                             double capacity, double[] geometry) {}

    public record FlowStats(String algorithm, long maxFlow, int augmentations, long arcScans, double elapsedMs) {}

    public record PathPreview(int step, long bottleneck, long flowAfter, List<Integer> segmentIds) {}

    public record CriticalLinksResponse(Area area, Snap source, Snap sink, String capacityModel, String unit, int nodes,
                                        int arcs, long maxFlow, long minCutCapacity, boolean maxFlowEqualsMinCut,
                                        boolean flowVerified, List<CutSegment> cut, List<long[]> segmentFlows,
                                        FlowStats edmondsKarp, FlowStats fordFulkerson, boolean algorithmsAgree,
                                        List<PathPreview> paths, List<String> warnings, Explanation explanation) {}

    /** A road-name match from the snapshot (for search-as-you-type without an external geocoder). */
    public record RoadMatch(String name, String roadClass, double lat, double lon, double totalKm, int segments) {}

    /** A geocoder result (OpenStreetMap Nominatim, called only on explicit search, max 1 request/second). */
    public record PlaceMatch(String displayName, double lat, double lon, String type) {}

    public record SearchResponse(String query, List<RoadMatch> roads, List<PlaceMatch> places, String placesStatus) {}

    public record PercentileValue(double percentile, double value, long quickselectComparisons, double quickselectMs,
                                  double sortMs, boolean matchesSort) {}

    public record LengthStatsResponse(Area area, int observations, List<PercentileValue> percentiles, Explanation explanation) {}
}
