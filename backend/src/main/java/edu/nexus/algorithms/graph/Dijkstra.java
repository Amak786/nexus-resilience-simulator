package edu.nexus.algorithms.graph;

import edu.nexus.algorithms.heap.FibonacciHeap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Dijkstra's single-source shortest paths on a {@link RoadGraph} with non-negative weights, with two
 * interchangeable priority queues so they can be compared on the same input:
 *
 * <ul>
 *   <li>{@link Queue#FIBONACCI}: the custom {@link FibonacciHeap} with true decrease-key.
 *       O(E + V log V) amortized.</li>
 *   <li>{@link Queue#BINARY}: {@link java.util.PriorityQueue} (a binary heap) with lazy deletion,
 *       because it has no decrease-key: a shorter distance inserts a new entry and stale entries are
 *       skipped when popped. O((V + E) log V).</li>
 * </ul>
 *
 * <pre>
 * DIJKSTRA(G, s): dist[*] = INF; dist[s] = 0; Q = {s}
 *   while Q not empty:
 *     u = EXTRACT-MIN(Q); if u == target: stop
 *     for each arc (u, v, w): if dist[u] + w &lt; dist[v]: dist[v] = dist[u] + w; prev[v] = arc; DECREASE-KEY/INSERT(v)
 * </pre>
 *
 * Correctness: with non-negative weights, the extracted node always has its final distance
 * (greedy-choice argument), so the algorithm can stop when the target is extracted.
 */
public final class Dijkstra {

    public enum Queue { FIBONACCI, BINARY }

    /**
     * @param dist       distance from the source (Infinity when unreachable)
     * @param prevArc    arc used to reach each node (-1 for the source and unreachable nodes)
     * @param settled    nodes extracted with their final distance
     * @param relaxations successful distance improvements (= decrease-key or insert calls)
     */
    public record Result(Queue queue, int source, double[] dist, int[] prevArc, int settled, long relaxations,
                         long heapOperations, double elapsedMillis) {

        /** Node sequence from the source to {@code target}, or an empty list if unreachable. */
        public List<Integer> pathTo(RoadGraph g, int target) {
            if (Double.isInfinite(dist[target])) {
                return List.of();
            }
            List<Integer> nodes = new ArrayList<>();
            int v = target;
            nodes.add(v);
            while (v != source) {
                int a = prevArc[v];
                int seg = g.arcSegment(a);
                v = g.segU(seg) == v ? g.segV(seg) : g.segU(seg);
                nodes.add(v);
            }
            Collections.reverse(nodes);
            return nodes;
        }

        /** Segment ids along the path to {@code target}, in order. */
        public List<Integer> segmentsTo(RoadGraph g, int target) {
            if (Double.isInfinite(dist[target]) || target == source) {
                return List.of();
            }
            List<Integer> segs = new ArrayList<>();
            int v = target;
            while (v != source) {
                int a = prevArc[v];
                int seg = g.arcSegment(a);
                segs.add(seg);
                v = g.segU(seg) == v ? g.segV(seg) : g.segU(seg);
            }
            Collections.reverse(segs);
            return segs;
        }
    }

    private Dijkstra() {}

    /** Shortest paths from {@code source}; stops early when {@code target} is settled ({@code -1} = all). */
    public static Result run(RoadGraph g, int source, int target, RoadGraph.Metric metric, Queue queue) {
        return run(g, source, target, metric, queue, null);
    }

    /**
     * As {@link #run(RoadGraph, int, int, RoadGraph.Metric, Queue)}, but arcs of segments with
     * {@code blocked[segment] == true} are skipped (closed roads). {@code blocked} may be null.
     */
    public static Result run(RoadGraph g, int source, int target, RoadGraph.Metric metric, Queue queue, boolean[] blocked) {
        if (blocked != null && blocked.length != g.segmentCount()) {
            throw new IllegalArgumentException("blocked array must have one entry per segment");
        }
        if (source < 0 || source >= g.nodeCount()) {
            throw new IllegalArgumentException("source node " + source + " does not exist");
        }
        if (target < -1 || target >= g.nodeCount()) {
            throw new IllegalArgumentException("target node " + target + " does not exist");
        }
        return queue == Queue.FIBONACCI ? fibonacci(g, source, target, metric, blocked) : binary(g, source, target, metric, blocked);
    }

    private static Result fibonacci(RoadGraph g, int s, int t, RoadGraph.Metric metric, boolean[] blocked) {
        long start = System.nanoTime();
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
        handle[s] = heap.insert(0, s);
        long ops = 1;
        long relax = 0;
        int settled = 0;
        while (!heap.isEmpty()) {
            int u = heap.extractMin().value();
            ops++;
            done[u] = true;
            settled++;
            if (u == t) {
                break;
            }
            for (int a = g.firstArc(u); a < g.endArc(u); a++) {
                int v = g.arcTo(a);
                if (done[v] || (blocked != null && blocked[g.arcSegment(a)])) {
                    continue;
                }
                double nd = dist[u] + g.weight(g.arcSegment(a), metric);
                if (nd < dist[v]) {
                    dist[v] = nd;
                    prev[v] = a;
                    relax++;
                    ops++;
                    if (handle[v] == null) {
                        handle[v] = heap.insert(nd, v);
                    } else {
                        heap.decreaseKey(handle[v], nd);
                    }
                }
            }
        }
        return new Result(Queue.FIBONACCI, s, dist, prev, settled, relax, ops, (System.nanoTime() - start) / 1e6);
    }

    private static Result binary(RoadGraph g, int s, int t, RoadGraph.Metric metric, boolean[] blocked) {
        long start = System.nanoTime();
        int n = g.nodeCount();
        double[] dist = new double[n];
        int[] prev = new int[n];
        boolean[] done = new boolean[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prev, -1);
        PriorityQueue<double[]> pq = new PriorityQueue<>((x, y) -> Double.compare(x[0], y[0]));
        dist[s] = 0;
        pq.add(new double[] {0, s});
        long ops = 1;
        long relax = 0;
        int settled = 0;
        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            ops++;
            int u = (int) top[1];
            if (done[u]) {
                continue; // stale entry (lazy deletion)
            }
            done[u] = true;
            settled++;
            if (u == t) {
                break;
            }
            for (int a = g.firstArc(u); a < g.endArc(u); a++) {
                int v = g.arcTo(a);
                if (done[v] || (blocked != null && blocked[g.arcSegment(a)])) {
                    continue;
                }
                double nd = dist[u] + g.weight(g.arcSegment(a), metric);
                if (nd < dist[v]) {
                    dist[v] = nd;
                    prev[v] = a;
                    relax++;
                    ops++;
                    pq.add(new double[] {nd, v});
                }
            }
        }
        return new Result(Queue.BINARY, s, dist, prev, settled, relax, ops, (System.nanoTime() - start) / 1e6);
    }
}
