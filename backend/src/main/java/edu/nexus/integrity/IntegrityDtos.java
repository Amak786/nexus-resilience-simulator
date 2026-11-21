package edu.nexus.integrity;

import edu.nexus.mumbai.MumbaiDtos.Area;
import edu.nexus.mumbai.MumbaiDtos.Explanation;
import edu.nexus.mumbai.MumbaiDtos.QueueStats;
import edu.nexus.mumbai.MumbaiDtos.Snap;
import edu.nexus.mumbai.TourService.TourResponse;

import java.util.List;

/**
 * Silent Shield: road-network snapshot integrity check.
 *
 * <p>Change origin: {@code REAL_OSM_EDIT} (difference between two real OpenStreetMap states, publicly recorded on
 * openstreetmap.org) or {@code DEMONSTRATION} (a synthetic modification typed in by the user to show what tampering
 * with an offline dataset file would look like; never a real attack).
 *
 * <p>Status: {@code LEGITIMATE_REVIEWED} (an analyst marked it legitimate), {@code ROUTINE_UNREVIEWED} (score below the
 * threshold), {@code SUSPICIOUS_NEEDS_REVIEW} (score at or above the threshold; an anomaly, NOT proof of an attack),
 * {@code SIMULATED_TAMPERING} (a demonstration modification). {@code VERIFIED_MALICIOUS} is never assigned automatically:
 * it requires external evidence that this tool does not have.
 */
public final class IntegrityDtos {

    private IntegrityDtos() {}

    /**
     * REMOVE_WAY (wayId), FLIP_ONEWAY (wayId) or ADD_CONNECTION (fromLat/fromLon to toLat/toLon), applied to the
     * updated snapshot. Demonstration only.
     */
    public record DemoModification(String type, Long wayId, Double fromLat, Double fromLon, Double toLat, Double toLon) {}

    public record IntegrityRequest(Area area, Double fromLat, Double fromLon, Double toLat, Double toLon,
                                   List<DemoModification> demoModifications, List<String> reviewedLegitimate, Integer topK,
                                   Integer stripFrom, Integer stripTo, Boolean planInspection) {}

    public record SnapshotInfo(String role, String osmDate, String retrievedAt, String fileSha256, String areaSha256,
                               int ways, int segments, int nodes, String dataClass) {}

    public record ConnectivityResult(int components, int largestNodes, double largestShare) {}

    public record ProbeRoute(boolean reachable, double distanceM, int segments, List<Long> wayIds, List<double[]> geometry,
                             QueueStats fibonacci, QueueStats binary, boolean queuesAgree) {}

    public record CutLink(long wayId, String name, String roadClass, double[] geometry) {}

    public record FlowResult(long disjointRoutesEdmondsKarp, long disjointRoutesFordFulkerson, long minCutSize,
                             boolean maxFlowEqualsMinCut, double edmondsKarpMs, double fordFulkersonMs, List<CutLink> cut) {}

    public record SideSummary(SnapshotInfo snapshot, ConnectivityResult connectivity, ProbeRoute route, FlowResult flow) {}

    public record Change(String id, long wayId, List<String> kinds, String origin, String status, String roadClass,
                         String name, String before, String after, double lat, double lon, List<double[]> geometry,
                         int disconnectedNodes, boolean onProbeRoute, int score, List<String> scoreBreakdown,
                         String historyUrl) {}

    public record BloomScreen(int trustedFingerprints, int screened, int definitelyNew, int possiblyKnown,
                              int confirmedKnown, int falsePositives, long bits, int hashes, double theoreticalFpr,
                              String note) {}

    public record Ranking(int k, int candidates, List<String> topIds, int kthScore, long quickselectComparisons,
                          boolean matchesSort, String note) {}

    public record AlertIndex(int size, int height, int blackHeight, long rotations, boolean invariantsHold,
                             int removedAfterReview, List<String> inOrder, String key) {}

    public record RiskStrips(int strips, List<Double> stripWestLon, List<Long> risk, int queryFrom, int queryTo,
                             long querySum, boolean matchesBruteForce, int hottestStrip, long hottestRisk,
                             int updatesApplied, long nodesVisited, String note) {}

    public record StatusCounts(int legitimateReviewed, int routineUnreviewed, int suspiciousNeedsReview,
                               int simulatedTampering, int verifiedMalicious) {}

    public record AlgorithmStep(String algorithm, String input, String output, String complexity, String limitation) {}

    public record IntegrityResponse(Area area, Snap probeFrom, Snap probeTo, SideSummary baseline, SideSummary updated,
                                    int totalChanges, boolean changesTruncated, List<Change> changes,
                                    StatusCounts statusCounts, BloomScreen bloom, Ranking ranking, AlertIndex alerts,
                                    RiskStrips strips, TourResponse inspection, String inspectionNote,
                                    List<AlgorithmStep> algorithms, List<String> scoringRule, int suspiciousThreshold,
                                    List<String> warnings, Explanation explanation, String verifiedMaliciousNote) {}

    public record ScenarioResponse(String name, String label, List<DemoModification> modifications, List<String> description) {}
}
