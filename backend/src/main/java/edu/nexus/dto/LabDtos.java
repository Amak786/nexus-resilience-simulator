package edu.nexus.dto;

import edu.nexus.algorithms.rbtree.RedBlackTree.NodeView;
import edu.nexus.dto.FlowDtos.RunStats;
import edu.nexus.dto.FlowDtos.TraceStep;

import java.util.List;

/** Algorithm Lab: run each Java implementation on small, user-defined inputs. */
public final class LabDtos {

    private LabDtos() {}

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
