package edu.trace.algorithms.tsp;

/**
 * Nearest-neighbour heuristic, used only as a baseline. Always move to the closest unvisited stop.
 * O(n^2) time. It has no constant-factor guarantee (worst case Theta(log n) times optimal on metric
 * inputs), which is why it is not the primary planner.
 */
public final class NearestNeighbourTsp {

    private NearestNeighbourTsp() {}

    public static int[] solve(DistanceMatrix d, int start) {
        int n = d.size();
        if (start < 0 || start >= n) {
            throw new IllegalArgumentException("start index " + start + " is outside [0, " + n + ")");
        }
        boolean[] visited = new boolean[n];
        int[] order = new int[n];
        int cur = start;
        visited[cur] = true;
        order[0] = cur;
        for (int step = 1; step < n; step++) {
            int best = -1;
            for (int v = 0; v < n; v++) {
                if (!visited[v] && (best == -1 || d.get(cur, v) < d.get(cur, best))) {
                    best = v;
                }
            }
            visited[best] = true;
            order[step] = best;
            cur = best;
        }
        return order;
    }
}
