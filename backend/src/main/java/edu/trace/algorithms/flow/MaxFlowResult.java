package edu.trace.algorithms.flow;

import java.util.List;

/**
 * Output of one max-flow run.
 *
 * @param algorithm      solver name
 * @param maxFlow        value of the maximum flow
 * @param augmentations  number of augmenting paths pushed
 * @param arcScans       residual arcs inspected while searching for paths (machine-independent work)
 * @param steps          recorded augmenting paths (may be truncated, see {@code traceTruncated})
 * @param traceTruncated true when more augmentations happened than were recorded
 * @param elapsedMillis  wall-clock time of the solver only (graph construction excluded)
 */
public record MaxFlowResult(String algorithm, long maxFlow, int augmentations, long arcScans,
                            List<AugmentingStep> steps, boolean traceTruncated, double elapsedMillis) {

    /**
     * One augmenting path.
     *
     * @param nodes      node sequence from source to sink
     * @param arcs       the arcs used; {@code reverse == true} means the path travelled a residual
     *                   back-arc, i.e. it cancelled flow previously sent on that edge
     * @param bottleneck units pushed (minimum residual capacity on the path)
     * @param flowAfter  total flow value after this augmentation
     * @param edgeFlows  flow on every edge after this step, or {@code null} when snapshots are off
     */
    public record AugmentingStep(int step, List<Integer> nodes, List<PathArc> arcs, long bottleneck,
                                 long flowAfter, long[] edgeFlows) {}

    /** An arc on an augmenting path: the underlying edge index and the direction it was used in. */
    public record PathArc(int edge, boolean reverse) {}
}
