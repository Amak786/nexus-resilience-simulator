package edu.nexus.algorithms.tsp;

import java.util.ArrayList;
import java.util.List;

/**
 * Metric TSP 2-approximation: minimum spanning tree plus preorder walk ("double tree").
 *
 * <pre>
 * APPROX-TSP(D, start)
 *   T = MST of the complete graph on D (Prim, dense version)
 *   L = preorder walk of T from start
 *   return the cycle L, start
 * </pre>
 *
 * <p><b>Guarantee.</b> Deleting one edge from an optimal tour gives a spanning tree, so
 * w(MST) &le; OPT. A full walk of T traverses every tree edge twice (cost 2 w(MST)); the preorder tour
 * skips repeated vertices, and by the triangle inequality each shortcut never increases length. Hence
 * tour &le; 2 w(MST) &le; 2 OPT. The tour is <i>not</i> claimed to be optimal; {@code tour / w(MST)}
 * is reported as a certified upper bound on the actual ratio.
 *
 * <p><b>Complexity.</b> Dense Prim O(n^2) time, O(n) extra space (plus the O(n^2) matrix).
 */
public final class MstPreorderTsp {

    /**
     * @param order     visiting order starting at the start vertex (return leg implicit)
     * @param length    closed tour length
     * @param mstWeight weight of the MST, a lower bound on the optimal tour
     * @param mstEdges  tree edges as {parent, child} pairs
     */
    public record Result(int[] order, double length, double mstWeight, List<int[]> mstEdges) {}

    private MstPreorderTsp() {}

    public static Result solve(DistanceMatrix d, int start) {
        int n = d.size();
        if (start < 0 || start >= n) {
            throw new IllegalArgumentException("start index " + start + " is outside [0, " + n + ")");
        }
        double[] key = new double[n];
        int[] parent = new int[n];
        boolean[] inTree = new boolean[n];
        java.util.Arrays.fill(key, Double.POSITIVE_INFINITY);
        java.util.Arrays.fill(parent, -1);
        key[start] = 0;
        double weight = 0;
        for (int iter = 0; iter < n; iter++) {
            int u = -1;
            for (int v = 0; v < n; v++) {
                if (!inTree[v] && (u == -1 || key[v] < key[u])) {
                    u = v;
                }
            }
            inTree[u] = true;
            weight += key[u];
            for (int v = 0; v < n; v++) {
                if (!inTree[v] && d.get(u, v) < key[v]) {
                    key[v] = d.get(u, v);
                    parent[v] = u;
                }
            }
        }
        List<List<Integer>> children = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            children.add(new ArrayList<>());
        }
        List<int[]> edges = new ArrayList<>();
        for (int v = 0; v < n; v++) {
            if (parent[v] >= 0) {
                children.get(parent[v]).add(v);
                edges.add(new int[] {parent[v], v});
            }
        }
        int[] order = new int[n];
        int count = 0;
        int[] stack = new int[n];
        int sp = 0;
        stack[sp++] = start;
        while (sp > 0) {
            int u = stack[--sp];
            order[count++] = u;
            List<Integer> kids = children.get(u);
            for (int c = kids.size() - 1; c >= 0; c--) {
                stack[sp++] = kids.get(c);
            }
        }
        return new Result(order, d.tourLength(order), weight, edges);
    }
}
