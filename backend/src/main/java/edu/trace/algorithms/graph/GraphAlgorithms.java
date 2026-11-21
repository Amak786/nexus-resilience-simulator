package edu.trace.algorithms.graph;

import edu.trace.algorithms.heap.FibonacciHeap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Traversal and structure algorithms on a {@link RoadGraph} (a CSR graph: arcs follow the direction rules of each segment;
 * a two-way segment gives two arcs). All are iterative, so deep graphs cannot overflow the call stack.
 */
public final class GraphAlgorithms {

    private GraphAlgorithms() {}

    /** BFS: hop distance from {@code source} (-1 = unreachable) and the parent arc of each reached node. O(V + E). */
    public record BfsResult(int[] hops, int[] parentArc, int reached, int[] order) {}

    public static BfsResult bfs(RoadGraph g, int source) {
        check(g, source);
        int n = g.nodeCount();
        int[] hops = new int[n];
        int[] parent = new int[n];
        Arrays.fill(hops, -1);
        Arrays.fill(parent, -1);
        int[] order = new int[n];
        int head = 0;
        int tail = 0;
        hops[source] = 0;
        order[tail++] = source;
        while (head < tail) {
            int u = order[head++];
            for (int a = g.firstArc(u); a < g.endArc(u); a++) {
                int v = g.arcTo(a);
                if (hops[v] < 0) {
                    hops[v] = hops[u] + 1;
                    parent[v] = a;
                    order[tail++] = v;
                }
            }
        }
        return new BfsResult(hops, parent, tail, Arrays.copyOf(order, tail));
    }

    /** Node path source..target from a parent-arc array (empty if unreachable). */
    public static List<Integer> path(RoadGraph g, int[] parentArc, int source, int target) {
        List<Integer> out = new ArrayList<>();
        if (target != source && parentArc[target] < 0) {
            return out;
        }
        int v = target;
        out.add(v);
        while (v != source) {
            int a = parentArc[v];
            v = arcFrom(g, a);
            out.add(v);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /** Tail node of arc {@code a} (CSR stores arcs grouped by tail). */
    public static int arcFrom(RoadGraph g, int a) {
        int lo = 0;
        int hi = g.nodeCount() - 1;
        while (lo < hi) { // largest u with firstArc(u) <= a
            int mid = (lo + hi + 1) >>> 1;
            if (g.firstArc(mid) <= a) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        while (lo > 0 && g.firstArc(lo) == g.endArc(lo) && g.firstArc(lo) == a) {
            lo--;
        }
        return lo;
    }

    /** Iterative DFS pre-order from {@code source}. O(V + E). */
    public static int[] dfsOrder(RoadGraph g, int source) {
        check(g, source);
        int n = g.nodeCount();
        boolean[] seen = new boolean[n];
        int[] next = new int[n];
        int[] stack = new int[n];
        int[] order = new int[n];
        int sp = 0;
        int k = 0;
        stack[sp++] = source;
        seen[source] = true;
        order[k++] = source;
        next[source] = g.firstArc(source);
        while (sp > 0) {
            int u = stack[sp - 1];
            if (next[u] < g.endArc(u)) {
                int v = g.arcTo(next[u]++);
                if (!seen[v]) {
                    seen[v] = true;
                    order[k++] = v;
                    next[v] = g.firstArc(v);
                    stack[sp++] = v;
                }
            } else {
                sp--;
            }
        }
        return Arrays.copyOf(order, k);
    }

    /**
     * Articulation points and bridges of the undirected view (every segment treated as two-way), by iterative DFS with
     * discovery times and low-links (Tarjan / Hopcroft). An articulation point is a node whose removal disconnects its
     * component; a bridge is a segment whose removal does. O(V + E).
     */
    public record CutStructure(boolean[] articulation, List<Integer> bridgeSegments) {}

    public static CutStructure articulationAndBridges(RoadGraph g) {
        int n = g.nodeCount();
        int m = g.segmentCount();
        // undirected adjacency: for each node, list of (neighbour, segment)
        int[] deg = new int[n];
        for (int e = 0; e < m; e++) {
            if (g.segU(e) != g.segV(e)) {
                deg[g.segU(e)]++;
                deg[g.segV(e)]++;
            }
        }
        int[] start = new int[n + 1];
        for (int i = 0; i < n; i++) {
            start[i + 1] = start[i] + deg[i];
        }
        int[] nb = new int[start[n]];
        int[] seg = new int[start[n]];
        int[] fill = Arrays.copyOf(start, n);
        for (int e = 0; e < m; e++) {
            int u = g.segU(e);
            int v = g.segV(e);
            if (u == v) {
                continue;
            }
            nb[fill[u]] = v;
            seg[fill[u]++] = e;
            nb[fill[v]] = u;
            seg[fill[v]++] = e;
        }
        int[] disc = new int[n];
        int[] low = new int[n];
        int[] parentSeg = new int[n];
        int[] it = new int[n];
        int[] stack = new int[n];
        Arrays.fill(disc, -1);
        boolean[] art = new boolean[n];
        List<Integer> bridges = new ArrayList<>();
        int time = 0;
        for (int root = 0; root < n; root++) {
            if (disc[root] >= 0) {
                continue;
            }
            int sp = 0;
            int rootChildren = 0;
            stack[sp++] = root;
            disc[root] = low[root] = time++;
            parentSeg[root] = -1;
            it[root] = start[root];
            while (sp > 0) {
                int u = stack[sp - 1];
                if (it[u] < start[u + 1]) {
                    int idx = it[u]++;
                    int v = nb[idx];
                    int e = seg[idx];
                    if (e == parentSeg[u]) {
                        continue; // do not go back along the same segment (parallel segments still count)
                    }
                    if (disc[v] < 0) {
                        disc[v] = low[v] = time++;
                        parentSeg[v] = e;
                        it[v] = start[v];
                        stack[sp++] = v;
                        if (u == root) {
                            rootChildren++;
                        }
                    } else {
                        low[u] = Math.min(low[u], disc[v]);
                    }
                } else {
                    sp--;
                    if (sp > 0) {
                        int p = stack[sp - 1];
                        low[p] = Math.min(low[p], low[u]);
                        if (low[u] > disc[p]) {
                            bridges.add(parentSeg[u]);
                        }
                        if (p != root && low[u] >= disc[p]) {
                            art[p] = true;
                        }
                    }
                }
            }
            art[root] = rootChildren > 1;
        }
        return new CutStructure(art, bridges);
    }

    /**
     * Brandes betweenness centrality on the unweighted arcs: for every node, the number of shortest paths between other
     * pairs that pass through it (each shortest path weighted by 1 / number of shortest paths). For an undirected graph
     * (all segments two-way) the result is divided by 2 because each pair is counted from both ends. O(V E) time, O(V + E) space.
     */
    public static double[] betweenness(RoadGraph g, boolean undirected) {
        int n = g.nodeCount();
        double[] cb = new double[n];
        int[] stack = new int[n];
        int[] queue = new int[n];
        int[] dist = new int[n];
        double[] sigma = new double[n];
        double[] delta = new double[n];
        List<List<Integer>> pred = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            pred.add(new ArrayList<>());
        }
        for (int s = 0; s < n; s++) {
            int sp = 0;
            for (int i = 0; i < n; i++) {
                pred.get(i).clear();
            }
            Arrays.fill(dist, -1);
            Arrays.fill(sigma, 0);
            Arrays.fill(delta, 0);
            sigma[s] = 1;
            dist[s] = 0;
            int head = 0;
            int tail = 0;
            queue[tail++] = s;
            while (head < tail) {
                int v = queue[head++];
                stack[sp++] = v;
                for (int a = g.firstArc(v); a < g.endArc(v); a++) {
                    int w = g.arcTo(a);
                    if (dist[w] < 0) {
                        dist[w] = dist[v] + 1;
                        queue[tail++] = w;
                    }
                    if (dist[w] == dist[v] + 1) {
                        sigma[w] += sigma[v];
                        pred.get(w).add(v);
                    }
                }
            }
            while (sp > 0) {
                int w = stack[--sp];
                for (int v : pred.get(w)) {
                    delta[v] += sigma[v] / sigma[w] * (1 + delta[w]);
                }
                if (w != s) {
                    cb[w] += delta[w];
                }
            }
        }
        if (undirected) {
            for (int i = 0; i < n; i++) {
                cb[i] /= 2;
            }
        }
        return cb;
    }

    /** Admissible heuristic: a lower bound on the remaining cost from a node to the target. */
    @FunctionalInterface
    public interface Heuristic {
        double estimate(int node);
    }

    public record AStarResult(double distance, List<Integer> segments, int settled, long heapOperations, double elapsedMs) {}

    /**
     * A* with the same Fibonacci heap as Dijkstra, keyed by g(n) + h(n). With an admissible and consistent heuristic
     * (here: straight-line distance, never longer than any road path) it returns the same distance as Dijkstra while
     * settling fewer nodes. O(E + V log V) in the worst case; h = 0 makes it exactly Dijkstra.
     */
    public static AStarResult aStar(RoadGraph g, int s, int t, RoadGraph.Metric metric, Heuristic h) {
        check(g, s);
        check(g, t);
        long t0 = System.nanoTime();
        int n = g.nodeCount();
        double[] dist = new double[n];
        int[] prev = new int[n];
        boolean[] done = new boolean[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        @SuppressWarnings("unchecked")
        FibonacciHeap.Node<Integer>[] handle = (FibonacciHeap.Node<Integer>[]) new FibonacciHeap.Node[n];
        FibonacciHeap<Integer> heap = new FibonacciHeap<>();
        dist[s] = 0;
        handle[s] = heap.insert(h.estimate(s), s);
        long ops = 1;
        int settled = 0;
        while (!heap.isEmpty()) {
            int u = heap.extractMin().value();
            ops++;
            if (done[u]) {
                continue;
            }
            done[u] = true;
            settled++;
            if (u == t) {
                break;
            }
            for (int a = g.firstArc(u); a < g.endArc(u); a++) {
                int v = g.arcTo(a);
                if (done[v]) {
                    continue;
                }
                double nd = dist[u] + g.weight(g.arcSegment(a), metric);
                if (nd < dist[v]) {
                    dist[v] = nd;
                    prev[v] = a;
                    ops++;
                    double key = nd + h.estimate(v);
                    if (handle[v] == null) {
                        handle[v] = heap.insert(key, v);
                    } else {
                        heap.decreaseKey(handle[v], key);
                    }
                }
            }
        }
        List<Integer> segs = new ArrayList<>();
        if (!Double.isInfinite(dist[t])) {
            int v = t;
            while (v != s) {
                int a = prev[v];
                segs.add(g.arcSegment(a));
                v = arcFrom(g, a);
            }
            java.util.Collections.reverse(segs);
        }
        return new AStarResult(dist[t], segs, settled, ops, (System.nanoTime() - t0) / 1e6);
    }

    private static void check(RoadGraph g, int v) {
        if (v < 0 || v >= g.nodeCount()) {
            throw new IllegalArgumentException("node " + v + " does not exist");
        }
    }

    /** Unused-queue helper kept for clarity in tests. */
    static ArrayDeque<Integer> deque() {
        return new ArrayDeque<>();
    }
}
