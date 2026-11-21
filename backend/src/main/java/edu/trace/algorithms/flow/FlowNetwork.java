package edu.trace.algorithms.flow;

import java.util.Arrays;

/**
 * Directed capacity network with an explicit residual graph.
 *
 * <p><b>Representation.</b> Arc arrays (struct-of-arrays). {@link #addEdge} stores the forward arc
 * of edge {@code e} at index {@code 2e} and its residual (reverse) twin at {@code 2e + 1}, so the twin
 * of any arc {@code a} is {@code a ^ 1}. The reverse arc starts with residual capacity 0; pushing
 * {@code f} units along an arc lowers its residual by {@code f} and raises its twin's by {@code f},
 * which is what lets a later augmenting path "cancel" earlier flow.
 *
 * <p><b>Space.</b> O(V + E). Parallel edges and antiparallel edges are both allowed because every
 * edge owns its own pair of arcs.
 *
 * <p>The solvers ({@link FordFulkerson}, {@link EdmondsKarp}) each reset the residual capacities
 * before running, so the same network can be solved twice and compared.
 */
public final class FlowNetwork {

    private final int nodeCount;
    private final int[] head;
    private int[] next = new int[16];
    private int[] from = new int[16];
    private int[] to = new int[16];
    private long[] capacity = new long[16];
    private long[] residual = new long[16];
    private int arcCount;

    public FlowNetwork(int nodeCount) {
        if (nodeCount < 1) {
            throw new IllegalArgumentException("a flow network needs at least one node");
        }
        this.nodeCount = nodeCount;
        this.head = new int[nodeCount];
        Arrays.fill(head, -1);
    }

    /** Adds the directed edge u -> v and returns its edge index (0, 1, 2, ...). */
    public int addEdge(int u, int v, long cap) {
        checkNode(u);
        checkNode(v);
        if (cap < 0) {
            throw new IllegalArgumentException("capacity must be non-negative, got " + cap);
        }
        if (arcCount + 2 > to.length) {
            int len = Math.max(arcCount + 2, to.length * 2);
            next = Arrays.copyOf(next, len);
            from = Arrays.copyOf(from, len);
            to = Arrays.copyOf(to, len);
            capacity = Arrays.copyOf(capacity, len);
            residual = Arrays.copyOf(residual, len);
        }
        int f = arcCount;
        int r = f + 1;
        from[f] = u; to[f] = v; capacity[f] = cap; residual[f] = cap; next[f] = head[u]; head[u] = f;
        from[r] = v; to[r] = u; capacity[r] = 0; residual[r] = 0; next[r] = head[v]; head[v] = r;
        arcCount += 2;
        return f / 2;
    }

    public int nodeCount() {
        return nodeCount;
    }

    public int edgeCount() {
        return arcCount / 2;
    }

    public int edgeFrom(int edge) {
        return from[checkEdge(edge) * 2];
    }

    public int edgeTo(int edge) {
        return to[checkEdge(edge) * 2];
    }

    public long edgeCapacity(int edge) {
        return capacity[checkEdge(edge) * 2];
    }

    /** Flow on an edge after a solver ran: capacity minus remaining residual capacity. */
    public long edgeFlow(int edge) {
        int a = checkEdge(edge) * 2;
        return capacity[a] - residual[a];
    }

    /** Snapshot of the flow on every edge, indexed by edge. */
    public long[] edgeFlows() {
        long[] f = new long[edgeCount()];
        for (int e = 0; e < f.length; e++) {
            f[e] = capacity[2 * e] - residual[2 * e];
        }
        return f;
    }

    // ---- package-private residual-graph access used by the solvers -------------------------

    int firstArc(int u) {
        return head[u];
    }

    int nextArc(int a) {
        return next[a];
    }

    int arcTo(int a) {
        return to[a];
    }

    int arcFrom(int a) {
        return from[a];
    }

    long residual(int a) {
        return residual[a];
    }

    void push(int a, long amount) {
        residual[a] -= amount;
        residual[a ^ 1] += amount;
    }

    void resetResidual() {
        System.arraycopy(capacity, 0, residual, 0, arcCount);
    }

    void checkNode(int v) {
        if (v < 0 || v >= nodeCount) {
            throw new IllegalArgumentException("node " + v + " is outside [0, " + nodeCount + ")");
        }
    }

    void checkSourceSink(int s, int t) {
        checkNode(s);
        checkNode(t);
        if (s == t) {
            throw new IllegalArgumentException("source and sink must be different nodes");
        }
    }

    private int checkEdge(int e) {
        if (e < 0 || e >= edgeCount()) {
            throw new IllegalArgumentException("edge " + e + " does not exist");
        }
        return e;
    }
}
