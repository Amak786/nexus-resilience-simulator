package edu.trace.algorithms.tsp;

import java.util.Arrays;

/**
 * Exact TSP by Held-Karp dynamic programming, O(2^n n^2) time and O(2^n n) space. Used only as a
 * test oracle and to report the true approximation ratio on very small instances.
 */
public final class HeldKarpTsp {

    public static final int LIMIT = 12;

    private HeldKarpTsp() {}

    /** Optimal closed-tour length starting and ending at vertex 0. */
    public static double optimalLength(DistanceMatrix d) {
        int n = d.size();
        if (n > LIMIT) {
            throw new IllegalArgumentException("exact TSP limited to " + LIMIT + " stops");
        }
        if (n <= 1) {
            return 0;
        }
        int m = n - 1;
        int full = 1 << m;
        double[][] dp = new double[full][m];
        for (double[] row : dp) {
            Arrays.fill(row, Double.POSITIVE_INFINITY);
        }
        for (int j = 0; j < m; j++) {
            dp[1 << j][j] = d.get(0, j + 1);
        }
        for (int mask = 1; mask < full; mask++) {
            for (int j = 0; j < m; j++) {
                if ((mask & (1 << j)) == 0 || dp[mask][j] == Double.POSITIVE_INFINITY) {
                    continue;
                }
                for (int k = 0; k < m; k++) {
                    if ((mask & (1 << k)) == 0) {
                        int nm = mask | (1 << k);
                        double cand = dp[mask][j] + d.get(j + 1, k + 1);
                        if (cand < dp[nm][k]) {
                            dp[nm][k] = cand;
                        }
                    }
                }
            }
        }
        double best = Double.POSITIVE_INFINITY;
        for (int j = 0; j < m; j++) {
            best = Math.min(best, dp[full - 1][j] + d.get(j + 1, 0));
        }
        return best;
    }
}
