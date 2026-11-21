package edu.trace.algorithms;

import edu.trace.algorithms.tsp.DistanceMatrix;
import edu.trace.algorithms.tsp.HeldKarpTsp;
import edu.trace.algorithms.tsp.MstPreorderTsp;
import edu.trace.algorithms.tsp.NearestNeighbourTsp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricTspTest {

    private static void assertPermutation(int[] order, int n, int start) {
        assertEquals(n, order.length);
        boolean[] seen = new boolean[n];
        for (int v : order) {
            assertTrue(!seen[v], "vertex " + v + " repeated");
            seen[v] = true;
        }
        assertEquals(start, order[0]);
    }

    @Test
    @DisplayName("TSP-1: unit square - MST weight 3, approximate and exact tours 4")
    void square() {
        DistanceMatrix d = DistanceMatrix.euclidean(new double[] {0, 1, 1, 0}, new double[] {0, 0, 1, 1});
        MstPreorderTsp.Result r = MstPreorderTsp.solve(d, 0);
        assertEquals(3.0, r.mstWeight(), 1e-9);
        assertEquals(4.0, r.length(), 1e-9);
        assertEquals(4.0, HeldKarpTsp.optimalLength(d), 1e-9);
        assertEquals(3, r.mstEdges().size());
    }

    @Test
    @DisplayName("TSP-2 (property, seed 3): on 200 random instances MST <= OPT <= tour <= 2 OPT")
    void approximationGuarantee() {
        SplittableRandom rnd = new SplittableRandom(3);
        for (int trial = 0; trial < 200; trial++) {
            int n = 2 + rnd.nextInt(9);
            double[] xs = new double[n];
            double[] ys = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = rnd.nextDouble() * 100;
                ys[i] = rnd.nextDouble() * 100;
            }
            DistanceMatrix d = DistanceMatrix.euclidean(xs, ys);
            MstPreorderTsp.Result r = MstPreorderTsp.solve(d, 0);
            double opt = HeldKarpTsp.optimalLength(d);
            assertPermutation(r.order(), n, 0);
            assertTrue(r.mstWeight() <= opt + 1e-9);
            assertTrue(r.length() >= opt - 1e-9);
            assertTrue(r.length() <= 2 * opt + 1e-9);
        }
    }

    @Test
    @DisplayName("TSP-3 (metric validation): asymmetric, negative, non-zero diagonal, triangle violation, infinite all rejected")
    void rejectsNonMetric() {
        assertThrows(DistanceMatrix.NonMetricException.class, () -> DistanceMatrix.of(new double[][] {{0, 1}, {2, 0}}));
        assertThrows(DistanceMatrix.NonMetricException.class, () -> DistanceMatrix.of(new double[][] {{0, -1}, {-1, 0}}));
        assertThrows(DistanceMatrix.NonMetricException.class, () -> DistanceMatrix.of(new double[][] {{1, 1}, {1, 0}}));
        assertThrows(DistanceMatrix.NonMetricException.class,
                () -> DistanceMatrix.of(new double[][] {{0, 1, 10}, {1, 0, 1}, {10, 1, 0}}));
        assertThrows(DistanceMatrix.NonMetricException.class,
                () -> DistanceMatrix.of(new double[][] {{0, Double.POSITIVE_INFINITY}, {Double.POSITIVE_INFINITY, 0}}));
        assertThrows(DistanceMatrix.NonMetricException.class, () -> DistanceMatrix.of(new double[][] {{0, 1}, {1}}));
        assertThrows(DistanceMatrix.NonMetricException.class, () -> DistanceMatrix.of(new double[0][0]));
    }

    @Test
    @DisplayName("TSP-4 (boundary): one stop gives length 0; two stops go there and back")
    void tinyInstances() {
        DistanceMatrix one = DistanceMatrix.of(new double[][] {{0}});
        assertEquals(0.0, MstPreorderTsp.solve(one, 0).length());
        DistanceMatrix two = DistanceMatrix.of(new double[][] {{0, 5}, {5, 0}});
        assertEquals(10.0, MstPreorderTsp.solve(two, 0).length());
        assertEquals(10.0, two.tourLength(NearestNeighbourTsp.solve(two, 0)));
    }

    @Test
    @DisplayName("TSP-5: start vertex is respected and duplicate locations (distance 0) are allowed")
    void startAndDuplicates() {
        DistanceMatrix d = DistanceMatrix.of(new double[][] {{0, 3, 3, 4}, {3, 0, 0, 5}, {3, 0, 0, 5}, {4, 5, 5, 0}});
        MstPreorderTsp.Result r = MstPreorderTsp.solve(d, 2);
        assertPermutation(r.order(), 4, 2);
        assertPermutation(NearestNeighbourTsp.solve(d, 3), 4, 3);
        assertTrue(r.length() <= 2 * HeldKarpTsp.optimalLength(d) + 1e-9);
    }

    @Test
    @DisplayName("TSP-6 (invalid input): bad start index and oversized exact solve are rejected")
    void invalidInput() {
        DistanceMatrix d = DistanceMatrix.of(new double[][] {{0, 1}, {1, 0}});
        assertThrows(IllegalArgumentException.class, () -> MstPreorderTsp.solve(d, 2));
        assertThrows(IllegalArgumentException.class, () -> NearestNeighbourTsp.solve(d, -1));
        double[] xs = new double[HeldKarpTsp.LIMIT + 1];
        assertThrows(IllegalArgumentException.class, () -> HeldKarpTsp.optimalLength(DistanceMatrix.euclidean(xs, xs.clone())));
    }
}
