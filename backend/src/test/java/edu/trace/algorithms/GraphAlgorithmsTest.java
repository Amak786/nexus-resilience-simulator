package edu.trace.algorithms;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.trace.algorithms.graph.Dijkstra;
import edu.trace.algorithms.graph.GraphAlgorithms;
import edu.trace.algorithms.graph.RoadGraph;
import edu.trace.algorithms.rbtree.RedBlackTree;
import edu.trace.mumbai.MumbaiDtos.RouteRequest;
import edu.trace.mumbai.MumbaiService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BFS, DFS, articulation points / bridges, Brandes betweenness, A* and red-black floor against independent references. */
class GraphAlgorithmsTest {

    static RoadGraph undirected(int n, int[][] edges) {
        int m = edges.length;
        int[] u = new int[m];
        int[] v = new int[m];
        double[] w = new double[m];
        for (int i = 0; i < m; i++) {
            u[i] = edges[i][0];
            v[i] = edges[i][1];
            w[i] = edges[i].length > 2 ? edges[i][2] : 1;
        }
        return new RoadGraph(n, u, v, new boolean[m], w, w, w);
    }

    static RoadGraph random(Random r, int n, int m) {
        int[][] e = new int[m][];
        for (int i = 0; i < m; i++) {
            int a = r.nextInt(n);
            int b = r.nextInt(n);
            e[i] = new int[] {a, b == a ? (a + 1) % n : b, 1 + r.nextInt(9)};
        }
        return undirected(n, e);
    }

    static int components(RoadGraph g, int skipNode, int skipSeg) {
        boolean[] seen = new boolean[g.nodeCount()];
        int c = 0;
        for (int s = 0; s < g.nodeCount(); s++) {
            if (s == skipNode || seen[s]) {
                continue;
            }
            c++;
            ArrayDeque<Integer> q = new ArrayDeque<>(List.of(s));
            seen[s] = true;
            while (!q.isEmpty()) {
                int x = q.poll();
                for (int a = g.firstArc(x); a < g.endArc(x); a++) {
                    int y = g.arcTo(a);
                    if (y != skipNode && g.arcSegment(a) != skipSeg && !seen[y]) {
                        seen[y] = true;
                        q.add(y);
                    }
                }
            }
        }
        return c;
    }

    @Test
    @DisplayName("GA-1 (BFS/DFS): hop counts match Dijkstra with unit weights; DFS and BFS visit the same set")
    void bfsDfs() {
        Random r = new Random(5);
        for (int t = 0; t < 30; t++) {
            int n = 5 + r.nextInt(40);
            int[][] e = new int[n + r.nextInt(2 * n)][];
            for (int i = 0; i < e.length; i++) {
                int a = r.nextInt(n);
                e[i] = new int[] {a, (a + 1 + r.nextInt(n - 1)) % n, 1};
            }
            RoadGraph g = undirected(n, e);
            var bfs = GraphAlgorithms.bfs(g, 0);
            var d = Dijkstra.run(g, 0, -1, RoadGraph.Metric.DISTANCE, Dijkstra.Queue.FIBONACCI);
            for (int v = 0; v < n; v++) {
                assertEquals(Double.isInfinite(d.dist()[v]) ? -1 : (int) d.dist()[v], bfs.hops()[v]);
            }
            int[] dfs = GraphAlgorithms.dfsOrder(g, 0);
            assertEquals(bfs.reached(), dfs.length);
            assertEquals(0, dfs[0]);
            int target = bfs.order()[bfs.reached() - 1];
            assertEquals(bfs.hops()[target] + 1, GraphAlgorithms.path(g, bfs.parentArc(), 0, target).size());
        }
    }

    @Test
    @DisplayName("GA-2 (Tarjan): articulation points and bridges equal brute force (remove each node / edge, count components)")
    void articulationBruteForce() {
        Random r = new Random(9);
        for (int t = 0; t < 40; t++) {
            int n = 4 + r.nextInt(14);
            RoadGraph g = random(r, n, n + r.nextInt(n));
            var cs = GraphAlgorithms.articulationAndBridges(g);
            int base = components(g, -1, -1);
            for (int v = 0; v < n; v++) {
                boolean expected = components(g, v, -1) > base; // removing v leaves more components than before
                assertEquals(expected, cs.articulation()[v], "node " + v + " in trial " + t);
            }
            for (int e = 0; e < g.segmentCount(); e++) {
                boolean expected = components(g, -1, e) > base;
                assertEquals(expected, cs.bridgeSegments().contains(e), "edge " + e + " in trial " + t);
            }
        }
    }

    @Test
    @DisplayName("GA-3 (Brandes): betweenness on a path and a star equals the closed-form values")
    void betweenness() {
        double[] path = GraphAlgorithms.betweenness(undirected(5, new int[][] {{0, 1}, {1, 2}, {2, 3}, {3, 4}}), true);
        assertArrayEquals(new double[] {0, 3, 4, 3, 0}, path, 1e-9);
        double[] star = GraphAlgorithms.betweenness(undirected(5, new int[][] {{0, 1}, {0, 2}, {0, 3}, {0, 4}}), true);
        assertEquals(6, star[0], 1e-9, "C(4,2) pairs all pass through the centre");
        double[] cycle = GraphAlgorithms.betweenness(undirected(4, new int[][] {{0, 1}, {1, 2}, {2, 3}, {3, 0}}), true);
        assertArrayEquals(new double[] {0.5, 0.5, 0.5, 0.5}, cycle, 1e-9, "two equal shortest paths share the credit");
    }

    @Test
    @DisplayName("GA-4 (A*): zero heuristic = Dijkstra; on the real Mumbai road graph A* gives the same length with fewer settled nodes")
    void aStar() throws Exception {
        Random r = new Random(2);
        RoadGraph g = random(r, 60, 150);
        for (int t = 1; t < 60; t += 7) {
            var a = GraphAlgorithms.aStar(g, 0, t, RoadGraph.Metric.DISTANCE, n -> 0);
            var d = Dijkstra.run(g, 0, t, RoadGraph.Metric.DISTANCE, Dijkstra.Queue.BINARY);
            assertEquals(d.dist()[t], a.distance(), 1e-9);
        }
        MumbaiService mumbai = new MumbaiService(new ObjectMapper(), 20000, 6000);
        var c = mumbai.astar(new RouteRequest(19.018, 72.847, 19.056, 72.845, "DISTANCE", null));
        assertTrue(c.reachable());
        assertTrue(c.sameDistance(), "admissible heuristic keeps optimality");
        assertTrue(c.aStarSettled() < c.dijkstraSettled(), c.aStarSettled() + " vs " + c.dijkstraSettled());
    }

    @Test
    @DisplayName("GA-5 (red-black floor): floorKey matches TreeMap.floorKey on random keys and queries")
    void rbFloor() {
        Random r = new Random(4);
        RedBlackTree<Long, Long> t = new RedBlackTree<>();
        TreeMap<Long, Long> ref = new TreeMap<>();
        assertNull(t.floorKey(5L));
        for (int i = 0; i < 500; i++) {
            long k = r.nextInt(100_000);
            t.put(k, k);
            ref.put(k, k);
        }
        t.validate();
        List<Long> qs = new ArrayList<>(ref.keySet());
        for (int i = 0; i < 2000; i++) {
            long q = i < qs.size() ? qs.get(i) : r.nextInt(110_000) - 5000;
            assertEquals(ref.floorKey(q), t.floorKey(q));
        }
    }
}
