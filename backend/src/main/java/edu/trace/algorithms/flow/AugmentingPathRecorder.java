package edu.trace.algorithms.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared augmentation step for both solvers: walks the parent-arc chain from sink to source, finds
 * the bottleneck, pushes it, and (optionally) records the path for the execution trace.
 */
final class AugmentingPathRecorder {

    private final FlowNetwork g;
    private final TraceOptions options;
    private final List<MaxFlowResult.AugmentingStep> steps = new ArrayList<>();
    private boolean truncated;

    AugmentingPathRecorder(FlowNetwork g, TraceOptions options) {
        this.g = g;
        this.options = options;
    }

    /** Pushes the bottleneck along the path encoded in {@code parentArc} and returns it. */
    long augment(int[] parentArc, int s, int t, long flowBefore, int stepNumber) {
        long bottleneck = Long.MAX_VALUE;
        for (int v = t; v != s; v = g.arcFrom(parentArc[v])) {
            bottleneck = Math.min(bottleneck, g.residual(parentArc[v]));
        }
        List<Integer> nodes = null;
        List<MaxFlowResult.PathArc> arcs = null;
        boolean record = steps.size() < options.maxSteps();
        if (record) {
            nodes = new ArrayList<>();
            arcs = new ArrayList<>();
            nodes.add(t);
        } else if (options.maxSteps() > 0) {
            truncated = true;
        }
        for (int v = t; v != s; v = g.arcFrom(parentArc[v])) {
            int a = parentArc[v];
            g.push(a, bottleneck);
            if (record) {
                nodes.add(g.arcFrom(a));
                arcs.add(new MaxFlowResult.PathArc(a / 2, (a & 1) == 1));
            }
        }
        if (record) {
            Collections.reverse(nodes);
            Collections.reverse(arcs);
            long[] snapshot = options.edgeSnapshots() ? g.edgeFlows() : null;
            steps.add(new MaxFlowResult.AugmentingStep(stepNumber, List.copyOf(nodes), List.copyOf(arcs),
                    bottleneck, flowBefore + bottleneck, snapshot));
        }
        return bottleneck;
    }

    List<MaxFlowResult.AugmentingStep> steps() {
        return List.copyOf(steps);
    }

    boolean truncated() {
        return truncated;
    }
}
