package edu.trace.dto;

import edu.trace.algorithms.rbtree.RedBlackTree.NodeView;

import java.util.List;

/** Algorithm Lab: run each Java implementation on small, user-defined inputs. */
public final class LabDtos {

    private LabDtos() {}

    public record RunStats(String algorithm, long maxFlow, int augmentations, long arcScans, double elapsedMs) {}

    public record TraceArc(int edge, boolean reverse) {}

    public record TraceStep(int step, List<String> nodes, List<TraceArc> arcs, long bottleneck, long flowAfter,
                            long[] edgeFlows) {}

    /** Segment tree on a series. Operation type: SUM, MIN, MAX, ADD (value added to [from,to]) or SET (to = from). */
    public record TimelineOperation(String type, Integer from, Integer to, Long value) {}

    public record TimelineRequest(List<Long> series, List<TimelineOperation> operations) {}

    public record TimelineOpResult(int index, String type, int from, int to, Long value, Long result, Integer argIndex,
                                   long nodesVisited, boolean matchesNaiveBaseline) {}

    public record TimelineResponse(List<Long> initialSeries, List<Long> finalSeries, List<TimelineOpResult> results,
                                   String complexity) {}

    /** Fibonacci-heap operation: INSERT key, EXTRACT_MIN, or DECREASE_KEY handle newKey (handle = insert number, 0-based). */
    public record HeapOperation(String type, Double key, Integer handle) {}

    public record LabHeapRequest(List<HeapOperation> operations) {}

    public record HeapOpResult(int index, String type, String outcome, int size, List<Integer> rootDegrees,
                               long links, long cuts, long cascadingCuts, boolean invariantsHold) {}

    public record LabHeapResponse(List<HeapOpResult> results, int size, String note) {}

    /** Bloom-filter lab: ids to insert (SAMPLE data), ids to query, target false-positive rate, absent probes. */
    public record LabBloomRequest(List<String> inserted, List<String> queries, Double falsePositiveRate, Integer probes) {}

    /** bloom: POSSIBLY_PRESENT or DEFINITELY_ABSENT; exact: the authoritative HashSet answer. */
    public record BloomQueryResult(String id, String bloom, boolean exactPresent, String verdict) {}

    public record LabBloomResponse(long bits, int hashes, int inserted, double theoreticalFpr, int probes, int probeFalsePositives,
                                   double measuredFpr, List<BloomQueryResult> results, String note) {}

    public record LabEdge(String from, String to, Long capacity) {}

    public record LabFlowRequest(List<String> nodes, List<LabEdge> edges, String source, String sink) {}

    public record LabEdgeResult(int index, String from, String to, long capacity, long flowEdmondsKarp,
                                long flowFordFulkerson, boolean inMinCut) {}

    public record RunTrace(RunStats stats, List<TraceStep> steps, boolean truncated) {}

    public record LabFlowResponse(List<String> nodes, List<LabEdgeResult> edges, RunTrace edmondsKarp,
                                  RunTrace fordFulkerson, long minCutCapacity, List<String> sourceSide,
                                  boolean maxFlowEqualsMinCut, boolean algorithmsAgreeOnValue, List<String> notes) {}

    public record DsuOperation(String type, Integer a, Integer b) {}

    public record LabDsuRequest(Integer elementCount, List<DsuOperation> operations) {}

    public record DsuOpResult(int index, String type, int a, Integer b, String result, int componentsAfter,
                              List<Integer> parentAfter) {}

    public record LabDsuResponse(int elementCount, List<DsuOpResult> results, List<Integer> finalParent,
                                 List<Integer> finalRank, int componentCount, List<List<Integer>> groups,
                                 long pointerHops) {}

    public record RbOperation(String type, Integer key) {}

    public record LabRbRequest(List<RbOperation> operations) {}

    public record RbOpResult(int index, String type, int key, String outcome, long rotations, List<String> events,
                             boolean invariantsHold) {}

    public record LabRbResponse(List<RbOpResult> results, List<NodeView> tree, List<Integer> inOrder, int size,
                                int height, int blackHeight, long totalRotations) {}

    public record LabSelectRequest(List<Double> values, List<Double> percentiles, Long seed) {}

    public record SelectResult(double percentile, int rank, double value, long comparisons, double sortBaselineValue,
                               boolean matches) {}

    public record LabSelectResponse(int n, List<SelectResult> results, long sortComparisonsEstimate, List<String> notes) {}

    public record AlgorithmInfo(String id, String name, String syllabusUnit, String javaClass, String time,
                                String space, String usedIn, String limitations) {}
}
