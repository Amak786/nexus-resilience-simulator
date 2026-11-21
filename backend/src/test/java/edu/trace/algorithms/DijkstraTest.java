package edu.trace.algorithms;

import edu.trace.algorithms.graph.Dijkstra;
import edu.trace.algorithms.graph.RoadGraph;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DijkstraTest {

    /** Builds a graph where length and time are the same weight. */
    private static RoadGraph graph(int n, int[][] edges, boolean[] oneWay) {
        int m = edges.length;
        int[] u = new int[m];
        int[] v = new int[m];
        double[] w = new double[m];
        for (int i = 0; i < m; i++) {
            u[i] = edges[i][0];
            v[i] = edges[i][1];
            w[i] = edges[i][2];
        }
        return new RoadGraph(n, u, v, oneWay == null ? new boolean[m] : oneWay, w, w, new double[m]);
    }

    @Test
    @DisplayName("DJ-1: hand-checked distances and path on a small two-way graph, both heaps")
    void handChecked() {
        RoadGraph g = graph(5, new int[][] {{0, 1, 4}, {0, 2, 1}, {2, 1, 2}, {1, 3, 1}, {2, 3, 5}, {3, 4, 3}}, null);
        for (Dijkstra.Queue q : Dijkstra.Queue.values()) {
            Dijkstra.Result r = Dijkstra.run(g, 0, -1, RoadGraph.Metric.DISTANCE, q);
            assertEquals(0, r.dist()[0]);
            assertEquals(3, r.dist()[1], q.name());
            assertEquals(4, r.dist()[3], q.name());
            assertEquals(7, r.dist()[4], q.name());
            assertEquals(List.of(0, 2, 1, 3, 4), r.pathTo(g, 4), q.name());
        }
    }

    @Test
    @DisplayName("DJ-2 (one-way): a one-way segment cannot be used backwards")
    void oneWayRespected() {
        RoadGraph g = graph(3, new int[][] {{0, 1, 1}, {1, 2, 1}, {0, 2, 10}}, new boolean[] {true, true, false});
        assertEquals(2, Dijkstra.run(g, 0, 2, RoadGraph.Metric.DISTANCE, Dijkstra.Queue.FIBONACCI).dist()[2]);
        assertEquals(10, Dijkstra.run(g, 2, 0, RoadGraph.Metric.DISTANCE, Dijkstra.Queue.FIBONACCI).dist()[0],
                "going back must use the two-way segment");
    }

    @Test
    @DisplayName("DJ-3 (boundary): unreachable nodes stay at infinity; zero-weight edges and single-node graphs work")
    void boundaries() {
        RoadGraph g = graph(4, new int[][] {{0, 1, 0}, {2, 3, 5}}, null);
        Dijkstra.Result r = Dijkstra.run(g, 0, -1, RoadGraph.Metric.DISTANCE, Dijkstra.Queue.FIBONACCI);
        assertEquals(0, r.dist()[1]);
        assertTrue(Double.isInfinite(r.dist()[3]));
        assertTrue(r.pathTo(g, 3).isEmpty());
        RoadGraph one = graph(1, new int[0][], null);
        assertEquals(0, Dijkstra.run(one, 0, 0, RoadGraph.Metric.DISTANCE, Dijkstra.Queue.BINARY).dist()[0]);
    }

    @Test
    @DisplayName("DJ-4 (oracle, seed 31): 100 random graphs - both heaps equal Floyd-Warshall distances")
    void matchesFloydWarshall() {
        SplittableRandom rnd = new SplittableRandom(31);
        for (int trial = 0; trial < 100; trial++) {
            int n = 2 + rnd.nextInt(25);
            int m = rnd.nextInt(4 * n);
            int[][] e = new int[m][3];
            boolean[] ow = new boolean[m];
            double[][] fw = new double[n][n];
            for (double[] row : fw) {
                java.util.Arrays.fill(row, Double.POSITIVE_INFINITY);
            }
            for (int i = 0; i < n; i++) {
                fw[i][i] = 0;
            }
            for (int i = 0; i < m; i++) {
                e[i] = new int[] {rnd.nextInt(n), rnd.nextInt(n), rnd.nextInt(20)};
                ow[i] = rnd.nextBoolean();
                fw[e[i][0]][e[i][1]] = Math.min(fw[e[i][0]][e[i][1]], e[i][2]);
                if (!ow[i]) {
                    fw[e[i][1]][e[i][0]] = Math.min(fw[e[i][1]][e[i][0]], e[i][2]);
                }
            }
            for (int k = 0; k < n; k++) {
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        fw[i][j] = Math.min(fw[i][j], fw[i][k] + fw[k][j]);
                    }
                }
            }
            RoadGraph g = graph(n, e, ow);
            for (Dijkstra.Queue q : Dijkstra.Queue.values()) {
                Dijkstra.Result r = Dijkstra.run(g, 0, -1, RoadGraph.Metric.DISTANCE, q);
                for (int v = 0; v < n; v++) {
                    assertEquals(fw[0][v], r.dist()[v], 1e-9, "trial " + trial + " node " + v + " " + q);
                }
            }
        }
    }

    @Test
    @DisplayName("DJ-5: early exit at the target gives the same target distance as a full run")
    void earlyExit() {
        SplittableRandom rnd = new SplittableRandom(5);
        int n = 400;
        int[][] e = new int[1600][];
        for (int i = 0; i < e.length; i++) {
            e[i] = new int[] {rnd.nextInt(n), rnd.nextInt(n), 1 + rnd.nextInt(50)};
        }
        RoadGraph g = graph(n, e, null);
        Dijkstra.Result full = Dijkstra.run(g, 0, -1, RoadGraph.Metric.DISTANCE, Dijkstra.Queue.FIBONACCI);
        Dijkstra.Result early = Dijkstra.run(g, 0, 123, RoadGraph.Metric.DISTANCE, Dijkstra.Queue.FIBONACCI);
        assertEquals(full.dist()[123], early.dist()[123]);
        assertTrue(early.settled() <= full.settled());
    }

    @Test
    @DisplayName("DJ-6 (invalid input): bad nodes and negative weights are rejected")
    void invalidInput() {
        RoadGraph g = graph(2, new int[][] {{0, 1, 1}}, null);
        assertThrows(IllegalArgumentException.class, () -> Dijkstra.run(g, 5, -1, RoadGraph.Metric.TIME, Dijkstra.Queue.BINARY));
        assertThrows(IllegalArgumentException.class, () -> graph(2, new int[][] {{0, 1, -3}}, null));
    }
}
