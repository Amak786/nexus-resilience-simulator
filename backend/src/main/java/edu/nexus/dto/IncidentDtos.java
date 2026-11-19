package edu.nexus.dto;

import edu.nexus.algorithms.rbtree.RedBlackTree.NodeView;
import edu.nexus.model.CityDataset;

import java.util.List;

/** Types for the incident priority index (red-black tree) and duplicate screening (Bloom filter). */
public final class IncidentDtos {

    private IncidentDtos() {}

    /**
     * One operation applied after the dataset's incidents are loaded.
     * type: INSERT (needs id, title, severity, affectedPeople; zoneId optional), UPDATE (id plus any of
     * severity/affectedPeople), RESOLVE (id; deletes it), LOOKUP (id).
     */
    public record PriorityOperation(String type, String id, String title, String zoneId, Integer severity,
                                    Long affectedPeople) {}

    public record PriorityRequest(CityDataset dataset, List<PriorityOperation> operations) {}

    public record RankedIncident(int rank, String id, String title, String zoneId, int severity, long affectedPeople,
                                 int vulnerabilityBonus, int score, String key) {}

    public record TreeMetrics(int size, int height, int blackHeight, double heightUpperBound, long rotations,
                              boolean invariantsHold, String invariantMessage) {}

    public record OperationResult(int index, String type, String id, String outcome, long rotations,
                                  List<String> events) {}

    public record PriorityResponse(List<RankedIncident> queue, List<NodeView> tree, TreeMetrics metrics,
                                   List<OperationResult> operations, String scoreFormula, String disclaimer) {}

    /**
     * Duplicate screening. Registry = dataset incident ids + {@code registeredIds} + {@code generateHistorical}
     * synthetic historical ids. Every Bloom "possibly present" answer is verified against the exact registry.
     */
    public record ScreenRequest(CityDataset dataset, Long expectedInsertions, Double falsePositiveRate, Long seed,
                                List<String> registeredIds, Integer generateHistorical, List<String> incomingIds,
                                Integer absentProbes) {}

    /** bloom: POSSIBLY_PRESENT or DEFINITELY_ABSENT; verdict: CONFIRMED_DUPLICATE, FALSE_POSITIVE or NEW. */
    public record ScreenedId(String id, String bloom, String verdict, boolean exactLookupPerformed, List<Long> bitPositions) {}

    public record ScreenResponse(long bitCount, int hashCount, long memoryBytes, long expectedInsertions,
                                 double targetFalsePositiveRate, long insertedCount, long setBits, double fillRatio,
                                 double theoreticalFprAtExpected, double theoreticalFprAtActual,
                                 int absentProbes, int probeFalsePositives, double measuredFpr,
                                 int registrySize, int duplicateRegistryEntries, List<String> registrySample,
                                 List<ScreenedId> results, int exactLookupsAvoided, List<String> formulas,
                                 List<String> warnings) {}

    /** Segment tree on an hourly series. Operation type: SUM, MAX, ADD (value added to [from,to]) or SET (to = from). */
    public record TimelineOperation(String type, Integer from, Integer to, Long value) {}

    public record TimelineRequest(CityDataset dataset, List<Long> series, List<TimelineOperation> operations) {}

    public record TimelineOpResult(int index, String type, int from, int to, Long value, Long result, Integer argmaxHour,
                                   long nodesVisited, boolean matchesNaiveBaseline) {}

    public record TimelineResponse(List<Long> initialSeries, List<Long> finalSeries, List<TimelineOpResult> results,
                                   String complexity) {}
}
