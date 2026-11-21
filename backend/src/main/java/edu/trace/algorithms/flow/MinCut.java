package edu.trace.algorithms.flow;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimum s-t cut extracted from the residual graph of a maximum flow, plus an independent
 * flow-validity check.
 *
 * <p><b>Max-flow min-cut theorem.</b> When no augmenting path remains, let S be the nodes reachable
 * from s through arcs with positive residual capacity. Every edge from S to V\S is saturated and every
 * edge from V\S to S carries zero flow, so the cut (S, V\S) has capacity exactly equal to the flow.
 * Since every cut's capacity is an upper bound on every flow, this cut is minimum and the flow is
 * maximum.
 *
 * <p>Complexity: one BFS, O(V + E).
 */
public final class MinCut {

    /**
     * @param sourceSide     {@code sourceSide[v]} is true when v is reachable from s in the residual graph
     * @param cutEdges       edges from the source side to the sink side (all saturated)
     * @param cutCapacity    sum of their capacities; equals the max-flow value
     */
    public record Result(boolean[] sourceSide, List<Integer> cutEdges, long cutCapacity) {}

    private MinCut() {}

    /** Call immediately after a solver has run on {@code g}. */
    public static Result fromResidual(FlowNetwork g, int s) {
        g.checkNode(s);
        int n = g.nodeCount();
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        int qh = 0;
        int qt = 0;
        seen[s] = true;
        queue[qt++] = s;
        while (qh < qt) {
            int u = queue[qh++];
            for (int a = g.firstArc(u); a != -1; a = g.nextArc(a)) {
                int v = g.arcTo(a);
                if (g.residual(a) > 0 && !seen[v]) {
                    seen[v] = true;
                    queue[qt++] = v;
                }
            }
        }
        List<Integer> cut = new ArrayList<>();
        long capacity = 0;
        for (int e = 0; e < g.edgeCount(); e++) {
            if (seen[g.edgeFrom(e)] && !seen[g.edgeTo(e)] && g.edgeCapacity(e) > 0) {
                cut.add(e);
                capacity += g.edgeCapacity(e);
            }
        }
        return new Result(seen, List.copyOf(cut), capacity);
    }

    /**
     * Checks capacity bounds and flow conservation at every node except s and t, independently of
     * the solver. Returns the flow value or throws {@link IllegalStateException}.
     */
    public static long verifyFlow(FlowNetwork g, int s, int t) {
        g.checkSourceSink(s, t);
        long[] excess = new long[g.nodeCount()];
        for (int e = 0; e < g.edgeCount(); e++) {
            long f = g.edgeFlow(e);
            if (f < 0 || f > g.edgeCapacity(e)) {
                throw new IllegalStateException("capacity constraint violated on edge " + e);
            }
            excess[g.edgeTo(e)] += f;
            excess[g.edgeFrom(e)] -= f;
        }
        for (int v = 0; v < excess.length; v++) {
            if (v != s && v != t && excess[v] != 0) {
                throw new IllegalStateException("flow conservation violated at node " + v);
            }
        }
        if (excess[t] != -excess[s]) {
            throw new IllegalStateException("source outflow and sink inflow differ");
        }
        return excess[t];
    }
}
