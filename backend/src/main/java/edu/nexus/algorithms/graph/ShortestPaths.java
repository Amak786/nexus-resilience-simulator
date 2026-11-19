package edu.nexus.algorithms.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Supporting algorithm (not one of the assessed techniques): Dijkstra's single-source shortest paths
 * on an undirected graph with non-negative edge lengths. Used to turn road lengths (km) into a
 * distance matrix for route planning. Shortest-path distances on an undirected graph are symmetric
 * and satisfy the triangle inequality, so the resulting matrix is metric on the reachable stops.
 *
 * <p>O((V + E) log V) per source with a binary heap ({@link PriorityQueue}).
 */
public final class ShortestPaths {

    private final int n;
    private final List<List<double[]>> adj = new ArrayList<>();

    public ShortestPaths(int n) {
        this.n = n;
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
    }

    public void addUndirectedEdge(int u, int v, double length) {
        if (!(length >= 0) || !Double.isFinite(length)) {
            throw new IllegalArgumentException("edge length must be finite and non-negative");
        }
        adj.get(u).add(new double[] {v, length});
        adj.get(v).add(new double[] {u, length});
    }

    /** Distances from {@code source}; unreachable nodes get {@link Double#POSITIVE_INFINITY}. */
    public double[] from(int source) {
        double[] dist = new double[n];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        dist[source] = 0;
        PriorityQueue<double[]> pq = new PriorityQueue<>((a, b) -> Double.compare(a[1], b[1]));
        pq.add(new double[] {source, 0});
        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            int u = (int) top[0];
            if (top[1] > dist[u]) {
                continue;
            }
            for (double[] e : adj.get(u)) {
                int v = (int) e[0];
                double nd = dist[u] + e[1];
                if (nd < dist[v]) {
                    dist[v] = nd;
                    pq.add(new double[] {v, nd});
                }
            }
        }
        return dist;
    }
}
