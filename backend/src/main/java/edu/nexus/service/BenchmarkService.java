package edu.nexus.service;

import edu.nexus.algorithms.bloom.BloomFilter;
import edu.nexus.algorithms.dsu.DisjointSetUnion;
import edu.nexus.algorithms.flow.EdmondsKarp;
import edu.nexus.algorithms.flow.FlowNetwork;
import edu.nexus.algorithms.flow.FordFulkerson;
import edu.nexus.algorithms.flow.MaxFlowResult;
import edu.nexus.algorithms.flow.TraceOptions;
import edu.nexus.algorithms.rbtree.RedBlackTree;
import edu.nexus.algorithms.segtree.SegmentTree;
import edu.nexus.algorithms.select.RandomizedQuickselect;
import edu.nexus.algorithms.tsp.DistanceMatrix;
import edu.nexus.algorithms.tsp.MstPreorderTsp;
import edu.nexus.algorithms.tsp.NearestNeighbourTsp;
import edu.nexus.model.ValidationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.LongSupplier;

/**
 * Reproducible micro-benchmarks. Inputs are generated from a fixed seed <i>before</i> timing; only the
 * algorithm call is inside the timed region. Each measurement is warmed up, repeated, and summarised by
 * the median and the minimum. Results depend on the machine and JVM; compare growth rates and the
 * baseline ratio, not absolute milliseconds.
 */
@Service
public class BenchmarkService {

    public static final List<String> ALGORITHMS =
            List.of("dijkstra", "flow", "dsu", "rbtree", "bloom", "segtree", "quickselect", "tsp");
    private static final long SEED = 2026L;
    private static final long NAIVE_HOP_BUDGET = 200_000_000L;

    public record Row(String size, String primary, double primaryMedianMs, double primaryMinMs, long primaryOps,
                      String baseline, Double baselineMedianMs, Long baselineOps, String quality) {}

    public record Report(String algorithm, String title, String opsMeaning, int warmups, int repetitions,
                         List<Row> rows, String environment, List<String> methodology) {}

    private record Measure(double medianMs, double minMs, long ops) {}

    private final edu.nexus.mumbai.MumbaiService mumbai;

    public BenchmarkService(org.springframework.beans.factory.ObjectProvider<edu.nexus.mumbai.MumbaiService> mumbai) {
        this.mumbai = mumbai.getIfAvailable();
    }

    public Report run(String algorithm) {
        return switch (algorithm == null ? "" : algorithm) {
            case "flow" -> flow();
            case "dsu" -> dsu();
            case "rbtree" -> rbtree();
            case "bloom" -> bloom();
            case "segtree" -> segtree();
            case "quickselect" -> quickselect();
            case "tsp" -> tsp();
            case "dijkstra" -> dijkstra();
            default -> throw new ValidationException("algorithm", "must be one of " + ALGORITHMS);
        };
    }

    // ---------------------------------------------------------------- suites

    private Report flow() {
        List<Row> rows = new ArrayList<>();
        for (int n : new int[] {100, 400, 1600}) {
            FlowNetwork g = layered(n, 6, new SplittableRandom(SEED + n));
            int s = 0;
            int t = n - 1;
            long[] value = new long[2];
            Measure ek = measure(1, 3, () -> {
                MaxFlowResult r = EdmondsKarp.run(g, s, t, TraceOptions.NONE);
                value[0] = r.maxFlow();
                return r.arcScans();
            });
            Measure ff = measure(1, 3, () -> {
                MaxFlowResult r = FordFulkerson.run(g, s, t, TraceOptions.NONE);
                value[1] = r.maxFlow();
                return r.arcScans();
            });
            rows.add(new Row("V=" + n + ", E=" + g.edgeCount(), "Edmonds-Karp", ek.medianMs(), ek.minMs(), ek.ops(),
                    "Ford-Fulkerson (DFS)", ff.medianMs(), ff.ops(),
                    "max flow " + value[0] + (value[0] == value[1] ? " (both agree)" : " (DISAGREE)")));
        }
        return report("flow", "Max flow: Edmonds-Karp vs Ford-Fulkerson (DFS)", "residual arcs scanned", 1, 3, rows,
                "Random layered networks (5 layers, ~6 arcs per node, capacities 1..100).");
    }

    private Report dsu() {
        List<Row> rows = new ArrayList<>();
        for (int n : new int[] {10_000, 100_000, 500_000}) {
            SplittableRandom rnd = new SplittableRandom(SEED + n);
            int[] a = new int[2 * n];
            int[] b = new int[2 * n];
            for (int i = 0; i < 2 * n; i++) {
                a[i] = rnd.nextInt(n);
                b[i] = rnd.nextInt(n);
            }
            Measure dsu = measure(2, 5, () -> {
                DisjointSetUnion d = new DisjointSetUnion(n);
                for (int i = 0; i < n; i++) {
                    d.union(a[i], b[i]);
                }
                for (int i = n; i < 2 * n; i++) {
                    d.connected(a[i], b[i]);
                }
                return d.pointerHops();
            });
            // The naive baseline can build very long chains, so it runs once per size with a hard work budget.
            Measure naive = measure(0, 1, () -> naiveUnionFind(n, a, b, NAIVE_HOP_BUDGET));
            boolean stopped = naive.ops() < 0;
            rows.add(new Row("n=" + n + ", " + (2 * n) + " ops", "DSU (rank + compression)", dsu.medianMs(), dsu.minMs(),
                    dsu.ops(), "Naive linking (no rank, no compression), 1 run", stopped ? null : naive.medianMs(),
                    stopped ? null : naive.ops(),
                    stopped ? "naive baseline stopped after " + NAIVE_HOP_BUDGET + " pointer hops (" + naive.medianMs() + " ms): far slower"
                            : String.format(Locale.ROOT, "naive does %.1fx more pointer hops", naive.ops() / (double) Math.max(1, dsu.ops()))));
        }
        return report("dsu", "Disjoint-set union vs naive linking", "parent-pointer hops", 2, 5, rows,
                "n random unions followed by n random connectivity queries. The naive baseline runs once with a budget of 2e8 pointer hops.");
    }

    private Report rbtree() {
        List<Row> rows = new ArrayList<>();
        for (int n : new int[] {1_000, 10_000, 50_000}) {
            int[] keys = distinctKeys(n, new SplittableRandom(SEED + n));
            int[] h = new int[1];
            Measure rb = measure(2, 5, () -> {
                RedBlackTree<Integer, Integer> t = new RedBlackTree<>();
                for (int k : keys) {
                    t.put(k, k);
                }
                long found = 0;
                for (int k : keys) {
                    if (t.containsKey(k)) {
                        found++;
                    }
                }
                h[0] = t.height();
                return t.rotationCount() + found * 0;
            });
            Measure list = measure(2, 5, () -> {
                ArrayList<Integer> sorted = new ArrayList<>();
                long moves = 0;
                for (int k : keys) {
                    int pos = Collections.binarySearch(sorted, k);
                    int ins = -pos - 1;
                    moves += sorted.size() - ins;
                    sorted.add(ins, k);
                }
                for (int k : keys) {
                    Collections.binarySearch(sorted, k);
                }
                return moves;
            });
            rows.add(new Row("n=" + n, "Red-black tree", rb.medianMs(), rb.minMs(), rb.ops(),
                    "Sorted ArrayList (binary search + shift)", list.medianMs(), list.ops(),
                    "height " + h[0] + " (bound " + String.format(Locale.ROOT, "%.1f", 2 * Math.log(n + 1) / Math.log(2)) + ")"));
        }
        return report("rbtree", "Red-black tree vs sorted array list (n inserts + n lookups)",
                "primary: rotations; baseline: elements shifted", 2, 5, rows, "Distinct random integer keys.");
    }

    private Report bloom() {
        List<Row> rows = new ArrayList<>();
        for (int n : new int[] {10_000, 50_000, 200_000}) {
            SplittableRandom rnd = new SplittableRandom(SEED + n);
            String[] in = new String[n];
            String[] out = new String[n];
            for (int i = 0; i < n; i++) {
                in[i] = "INC-" + Long.toHexString(rnd.nextLong());
                out[i] = "ABS-" + Long.toHexString(rnd.nextLong());
            }
            double[] fpr = new double[2];
            Measure bf = measure(2, 5, () -> {
                BloomFilter f = BloomFilter.optimal(n, 0.01, SEED);
                for (String s : in) {
                    f.add(s);
                }
                long fp = 0;
                for (String s : out) {
                    if (f.mightContain(s)) {
                        fp++;
                    }
                }
                fpr[0] = fp / (double) n;
                fpr[1] = f.theoreticalFalsePositiveProbability();
                return f.bitCount();
            });
            Measure hs = measure(2, 5, () -> {
                Set<String> set = new HashSet<>();
                for (String s : in) {
                    set.add(s);
                }
                long hits = 0;
                for (String s : out) {
                    if (set.contains(s)) {
                        hits++;
                    }
                }
                return hits;
            });
            rows.add(new Row("n=" + n, "Bloom filter (p=1%)", bf.medianMs(), bf.minMs(), bf.ops(),
                    "HashSet<String> (exact)", hs.medianMs(), null,
                    String.format(Locale.ROOT, "measured FPR %.4f vs theory %.4f; %.1f bits/item", fpr[0], fpr[1], bf.ops() / (double) n)));
        }
        return report("bloom", "Bloom filter vs exact HashSet (n inserts + n absent lookups)", "bits in the filter",
                2, 5, rows, "Probe ids use a different prefix, so every positive is a false positive.");
    }

    private Report segtree() {
        List<Row> rows = new ArrayList<>();
        int q = 2_000;
        for (int n : new int[] {1_000, 10_000, 100_000}) {
            SplittableRandom rnd = new SplittableRandom(SEED + n);
            long[] series = new long[n];
            for (int i = 0; i < n; i++) {
                series[i] = rnd.nextInt(500);
            }
            int[][] ops = new int[q][3];
            for (int i = 0; i < q; i++) {
                int l = rnd.nextInt(n);
                int r = l + rnd.nextInt(n - l);
                ops[i] = new int[] {i % 2, l, r};
            }
            long[] check = new long[2];
            Measure st = measure(2, 5, () -> {
                SegmentTree t = new SegmentTree(series);
                long acc = 0;
                for (int[] o : ops) {
                    if (o[0] == 0) {
                        t.rangeAdd(o[1], o[2], 3);
                    } else {
                        acc += t.rangeSum(o[1], o[2]);
                    }
                }
                check[0] = acc;
                return t.nodesVisited();
            });
            Measure naive = measure(2, 5, () -> {
                long[] a = series.clone();
                long acc = 0;
                long touched = 0;
                for (int[] o : ops) {
                    for (int i = o[1]; i <= o[2]; i++) {
                        if (o[0] == 0) {
                            a[i] += 3;
                        } else {
                            acc += a[i];
                        }
                        touched++;
                    }
                }
                check[1] = acc;
                return touched;
            });
            rows.add(new Row("n=" + n + ", " + q + " ops", "Segment tree (lazy)", st.medianMs(), st.minMs(), st.ops(),
                    "Plain array", naive.medianMs(), naive.ops(), check[0] == check[1] ? "answers agree" : "ANSWERS DIFFER"));
        }
        return report("segtree", "Segment tree vs plain array (alternating range add / range sum)",
                "primary: tree nodes visited; baseline: array cells touched", 2, 5, rows, "Random ranges.");
    }

    private Report quickselect() {
        List<Row> rows = new ArrayList<>();
        for (int n : new int[] {10_000, 100_000, 1_000_000}) {
            SplittableRandom rnd = new SplittableRandom(SEED + n);
            double[] v = new double[n];
            for (int i = 0; i < n; i++) {
                v[i] = rnd.nextDouble() * 1000;
            }
            double[] got = new double[2];
            Measure qs = measure(2, 5, () -> {
                RandomizedQuickselect.Selection s = RandomizedQuickselect.percentile(v, 90, SEED);
                got[0] = s.value();
                return s.comparisons();
            });
            Measure sort = measure(2, 5, () -> {
                double[] c = v.clone();
                Arrays.sort(c);
                got[1] = c[(int) Math.ceil(0.9 * n) - 1];
                return Math.round(n * Math.log(n) / Math.log(2));
            });
            rows.add(new Row("n=" + n, "Randomized Quickselect (P90)", qs.medianMs(), qs.minMs(), qs.ops(),
                    "Full sort (Arrays.sort) then index", sort.medianMs(), sort.ops(),
                    got[0] == got[1] ? "same P90 value" : "VALUES DIFFER"));
        }
        return report("quickselect", "Randomized Quickselect vs full sort (90th percentile)",
                "primary: comparisons counted; baseline: n log2 n estimate (not counted)", 2, 5, rows,
                "Uniform random doubles.");
    }

    private Report tsp() {
        List<Row> rows = new ArrayList<>();
        for (int n : new int[] {50, 200, 800}) {
            SplittableRandom rnd = new SplittableRandom(SEED + n);
            double[] xs = new double[n];
            double[] ys = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = rnd.nextDouble() * 100;
                ys[i] = rnd.nextDouble() * 100;
            }
            DistanceMatrix d = DistanceMatrix.euclidean(xs, ys);
            double[] len = new double[3];
            Measure mst = measure(2, 5, () -> {
                MstPreorderTsp.Result r = MstPreorderTsp.solve(d, 0);
                len[0] = r.length();
                len[2] = r.mstWeight();
                return (long) n * n;
            });
            Measure nn = measure(2, 5, () -> {
                int[] o = NearestNeighbourTsp.solve(d, 0);
                len[1] = d.tourLength(o);
                return (long) n * n;
            });
            rows.add(new Row("n=" + n, "MST + preorder (2-approx)", mst.medianMs(), mst.minMs(), mst.ops(),
                    "Nearest neighbour", nn.medianMs(), nn.ops(),
                    String.format(Locale.ROOT, "tour/MST: approx %.3f, NN %.3f (MST <= OPT, so these bound the ratio to OPT)",
                            len[0] / len[2], len[1] / len[2])));
        }
        return report("tsp", "Metric TSP: MST double-tree vs nearest neighbour", "distance lookups (n^2)", 2, 5, rows,
                "Uniform random points in a 100 x 100 km square (Euclidean, so metric by construction).");
    }

    private Report dijkstra() {
        List<Row> rows = new ArrayList<>();
        for (int side : new int[] {100, 300, 600}) {
            edu.nexus.algorithms.graph.RoadGraph g = grid(side, new SplittableRandom(SEED + side));
            rows.add(dijkstraRow("grid " + side + "x" + side + " (V=" + g.nodeCount() + ", arcs=" + g.arcCount() + ")", g, 0));
        }
        if (mumbai != null) {
            edu.nexus.algorithms.graph.RoadGraph g = mumbai.network().graph();
            rows.add(dijkstraRow("Mumbai OSM graph (V=" + g.nodeCount() + ", arcs=" + g.arcCount() + ")", g, 0));
        }
        return report("dijkstra", "Dijkstra: Fibonacci heap vs java.util.PriorityQueue (single source, all nodes)",
                "heap operations (inserts/decrease-keys + extracts)", 2, 5, rows,
                "Seeded grid graphs with random edge lengths 1-100 m, plus the real Mumbai road graph (travel-time weights).");
    }

    private Row dijkstraRow(String label, edu.nexus.algorithms.graph.RoadGraph g, int source) {
        double[] check = new double[2];
        Measure fib = measure(2, 5, () -> {
            edu.nexus.algorithms.graph.Dijkstra.Result r = edu.nexus.algorithms.graph.Dijkstra.run(g, source, -1,
                    edu.nexus.algorithms.graph.RoadGraph.Metric.TIME, edu.nexus.algorithms.graph.Dijkstra.Queue.FIBONACCI);
            check[0] = sumFinite(r.dist());
            return r.heapOperations();
        });
        Measure bin = measure(2, 5, () -> {
            edu.nexus.algorithms.graph.Dijkstra.Result r = edu.nexus.algorithms.graph.Dijkstra.run(g, source, -1,
                    edu.nexus.algorithms.graph.RoadGraph.Metric.TIME, edu.nexus.algorithms.graph.Dijkstra.Queue.BINARY);
            check[1] = sumFinite(r.dist());
            return r.heapOperations();
        });
        boolean same = Math.abs(check[0] - check[1]) <= 1e-6 * Math.max(1, check[0]);
        return new Row(label, "Dijkstra + Fibonacci heap", fib.medianMs(), fib.minMs(), fib.ops(),
                "Dijkstra + PriorityQueue (lazy deletion)", bin.medianMs(), bin.ops(),
                same ? "identical distances to every node" : "DISTANCES DIFFER");
    }

    private static double sumFinite(double[] d) {
        double s = 0;
        for (double v : d) {
            if (Double.isFinite(v)) {
                s += v;
            }
        }
        return s;
    }

    /** side x side 4-neighbour grid, two-way segments, length = time = random 1..100. */
    private static edu.nexus.algorithms.graph.RoadGraph grid(int side, SplittableRandom rnd) {
        int n = side * side;
        int m = 2 * side * (side - 1);
        int[] u = new int[m];
        int[] v = new int[m];
        boolean[] ow = new boolean[m];
        double[] w = new double[m];
        double[] cap = new double[m];
        int e = 0;
        for (int r = 0; r < side; r++) {
            for (int c = 0; c < side; c++) {
                int id = r * side + c;
                if (c + 1 < side) {
                    u[e] = id;
                    v[e] = id + 1;
                    w[e++] = 1 + rnd.nextInt(100);
                }
                if (r + 1 < side) {
                    u[e] = id;
                    v[e] = id + side;
                    w[e++] = 1 + rnd.nextInt(100);
                }
            }
        }
        return new edu.nexus.algorithms.graph.RoadGraph(n, u, v, ow, w, w, cap);
    }

    // ---------------------------------------------------------------- helpers

    private static Measure measure(int warmups, int reps, LongSupplier task) {
        for (int i = 0; i < warmups; i++) {
            task.getAsLong();
        }
        double[] ms = new double[reps];
        long ops = 0;
        for (int i = 0; i < reps; i++) {
            long start = System.nanoTime();
            ops = task.getAsLong();
            ms[i] = (System.nanoTime() - start) / 1e6;
        }
        Arrays.sort(ms);
        return new Measure(round3(ms[reps / 2]), round3(ms[0]), ops);
    }

    private static Report report(String id, String title, String ops, int warmups, int reps, List<Row> rows, String input) {
        Runtime rt = Runtime.getRuntime();
        String env = "Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + "), "
                + rt.availableProcessors() + " logical CPUs, max heap " + (rt.maxMemory() >> 20) + " MiB, "
                + System.getProperty("os.name");
        return new Report(id, title, ops, warmups, reps, rows, env, List.of(
                "Input: " + input + " Generated from fixed seed " + SEED + " before timing.",
                "Timing: System.nanoTime() around the algorithm call only; " + warmups + " warm-up run(s), then the median and minimum of " + reps + " runs.",
                "Operation counts are machine-independent; milliseconds depend on hardware, JIT and load (a shared free-tier server can be noisy).",
                "Network latency, JSON serialisation and input generation are excluded."));
    }

    private static FlowNetwork layered(int n, int degree, SplittableRandom rnd) {
        FlowNetwork g = new FlowNetwork(n);
        int layers = 5;
        int per = Math.max(1, (n - 2) / layers);
        for (int v = 1; v < n - 1; v++) {
            int layer = Math.min(layers - 1, (v - 1) / per);
            if (layer == 0) {
                g.addEdge(0, v, 1 + rnd.nextInt(100));
            }
            if (layer == layers - 1) {
                g.addEdge(v, n - 1, 1 + rnd.nextInt(100));
            } else {
                int lo = 1 + (layer + 1) * per;
                int hi = Math.min(n - 2, lo + per - 1);
                for (int k = 0; k < degree && lo <= hi; k++) {
                    g.addEdge(v, lo + rnd.nextInt(hi - lo + 1), 1 + rnd.nextInt(100));
                }
            }
        }
        return g;
    }

    /** Returns the pointer hops used, or -1 if the budget was exhausted. */
    private static long naiveUnionFind(int n, int[] a, int[] b, long budget) {
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        long hops = 0;
        for (int i = 0; i < 2 * n; i++) {
            int x = a[i];
            while (parent[x] != x) {
                x = parent[x];
                hops++;
            }
            int y = b[i];
            while (parent[y] != y) {
                y = parent[y];
                hops++;
            }
            if (hops > budget) {
                return -1;
            }
            if (i < n && x != y) {
                parent[x] = y;
            }
        }
        return hops;
    }

    private static int[] distinctKeys(int n, SplittableRandom rnd) {
        Set<Integer> seen = new HashSet<>();
        int[] keys = new int[n];
        int c = 0;
        while (c < n) {
            int k = rnd.nextInt(Integer.MAX_VALUE);
            if (seen.add(k)) {
                keys[c++] = k;
            }
        }
        return keys;
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
