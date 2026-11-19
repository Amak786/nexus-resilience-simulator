package edu.nexus.service;

import edu.nexus.algorithms.bloom.BloomFilter;
import edu.nexus.algorithms.dsu.DisjointSetUnion;
import edu.nexus.algorithms.flow.EdmondsKarp;
import edu.nexus.algorithms.flow.FlowNetwork;
import edu.nexus.algorithms.flow.FordFulkerson;
import edu.nexus.algorithms.flow.MinCut;
import edu.nexus.algorithms.flow.TraceOptions;
import edu.nexus.algorithms.rbtree.RedBlackTree;
import edu.nexus.algorithms.segtree.SegmentTree;
import edu.nexus.algorithms.select.RandomizedQuickselect;
import edu.nexus.algorithms.tsp.DistanceMatrix;
import edu.nexus.algorithms.tsp.HeldKarpTsp;
import edu.nexus.algorithms.tsp.MstPreorderTsp;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Live, seeded property checks that run on the deployed server in about a second. They complement the
 * JUnit suite (run at build time) by showing examiners that the running build is correct.
 */
@Service
public class SelfCheckService {

    private static final long SEED = 20261119L;

    public record Check(String algorithm, String property, int cases, boolean passed, String detail, double ms) {}

    public record SelfCheckReport(boolean allPassed, long seed, List<Check> checks) {}

    public SelfCheckReport run() {
        List<Check> checks = new ArrayList<>();
        checks.add(check("Edmonds-Karp / Ford-Fulkerson / min cut",
                "both reach the same value, the flow is valid, and the residual cut capacity equals it", 150, this::flow));
        checks.add(check("Red-black tree", "matches java.util.TreeMap after random inserts/deletes; all invariants hold", 3000, this::rbtree));
        checks.add(check("Disjoint-set union", "connectivity matches a naive relabelling oracle", 500, this::dsu));
        checks.add(check("Bloom filter", "no false negatives; measured FPR within 0.5x-2x of theory", 5000, this::bloom));
        checks.add(check("Segment tree", "range sum/max/add match a plain array", 2000, this::segtree));
        checks.add(check("Randomized Quickselect", "every percentile equals the value from a full sort", 200, this::quickselect));
        checks.add(check("Metric TSP", "MST <= OPT <= approx tour <= 2 x OPT (Held-Karp oracle)", 60, this::tsp));
        return new SelfCheckReport(checks.stream().allMatch(Check::passed), SEED, checks);
    }

    private Check check(String algorithm, String property, int cases, Supplier<String> body) {
        long start = System.nanoTime();
        String failure;
        try {
            failure = body.get();
        } catch (RuntimeException ex) {
            failure = "exception: " + ex.getMessage();
        }
        double ms = Math.round((System.nanoTime() - start) / 1e4) / 100.0;
        return new Check(algorithm, property, cases, failure == null, failure == null ? "passed" : failure, ms);
    }

    private String flow() {
        SplittableRandom rnd = new SplittableRandom(SEED);
        for (int trial = 0; trial < 150; trial++) {
            int n = 2 + rnd.nextInt(25);
            FlowNetwork g = new FlowNetwork(n);
            int m = rnd.nextInt(4 * n + 1);
            for (int i = 0; i < m; i++) {
                g.addEdge(rnd.nextInt(n), rnd.nextInt(n), rnd.nextInt(60));
            }
            long ff = FordFulkerson.run(g, 0, n - 1, TraceOptions.NONE).maxFlow();
            MinCut.verifyFlow(g, 0, n - 1);
            long ek = EdmondsKarp.run(g, 0, n - 1, TraceOptions.NONE).maxFlow();
            MinCut.verifyFlow(g, 0, n - 1);
            long cut = MinCut.fromResidual(g, 0).cutCapacity();
            if (ff != ek || ek != cut) {
                return "trial " + trial + ": FF=" + ff + " EK=" + ek + " cut=" + cut;
            }
        }
        return null;
    }

    private String rbtree() {
        SplittableRandom rnd = new SplittableRandom(SEED);
        RedBlackTree<Integer, Integer> t = new RedBlackTree<>();
        TreeMap<Integer, Integer> o = new TreeMap<>();
        for (int i = 0; i < 3000; i++) {
            int k = rnd.nextInt(800);
            if (rnd.nextInt(3) == 0) {
                if (t.remove(k) != (o.remove(k) != null)) {
                    return "remove disagreement at step " + i;
                }
            } else {
                t.put(k, i);
                o.put(k, i);
            }
            if (i % 100 == 0) {
                t.validate();
            }
        }
        t.validate();
        return t.keysInOrder().equals(new ArrayList<>(o.keySet())) ? null : "in-order keys differ";
    }

    private String dsu() {
        SplittableRandom rnd = new SplittableRandom(SEED);
        int n = 200;
        DisjointSetUnion d = new DisjointSetUnion(n);
        int[] label = new int[n];
        for (int i = 0; i < n; i++) {
            label[i] = i;
        }
        for (int step = 0; step < 500; step++) {
            int a = rnd.nextInt(n);
            int b = rnd.nextInt(n);
            d.union(a, b);
            int from = label[b];
            int to = label[a];
            for (int i = 0; i < n; i++) {
                if (label[i] == from) {
                    label[i] = to;
                }
            }
            int x = rnd.nextInt(n);
            int y = rnd.nextInt(n);
            if ((label[x] == label[y]) != d.connected(x, y)) {
                return "mismatch at step " + step;
            }
        }
        return null;
    }

    private String bloom() {
        BloomFilter f = BloomFilter.optimal(5000, 0.02, SEED);
        for (int i = 0; i < 5000; i++) {
            f.add("IN-" + i);
        }
        for (int i = 0; i < 5000; i++) {
            if (!f.mightContain("IN-" + i)) {
                return "false negative for IN-" + i;
            }
        }
        int fp = 0;
        int probes = 40_000;
        for (int i = 0; i < probes; i++) {
            if (f.mightContain("OUT-" + i)) {
                fp++;
            }
        }
        double measured = fp / (double) probes;
        double theory = f.theoreticalFalsePositiveProbability();
        return measured <= 2 * theory && measured >= theory / 2 ? null
                : "measured " + measured + " vs theory " + theory;
    }

    private String segtree() {
        SplittableRandom rnd = new SplittableRandom(SEED);
        int n = 300;
        long[] a = new long[n];
        for (int i = 0; i < n; i++) {
            a[i] = rnd.nextInt(1000);
        }
        SegmentTree t = new SegmentTree(a);
        for (int step = 0; step < 2000; step++) {
            int l = rnd.nextInt(n);
            int r = l + rnd.nextInt(n - l);
            switch (rnd.nextInt(3)) {
                case 0 -> {
                    long d = rnd.nextInt(201) - 100;
                    t.rangeAdd(l, r, d);
                    for (int i = l; i <= r; i++) {
                        a[i] += d;
                    }
                }
                case 1 -> {
                    long s = 0;
                    for (int i = l; i <= r; i++) {
                        s += a[i];
                    }
                    if (s != t.rangeSum(l, r)) {
                        return "sum mismatch at step " + step;
                    }
                }
                default -> {
                    int arg = l;
                    for (int i = l; i <= r; i++) {
                        if (a[i] > a[arg]) {
                            arg = i;
                        }
                    }
                    SegmentTree.MaxResult m = t.rangeMax(l, r);
                    if (m.value() != a[arg] || m.index() != arg) {
                        return "max mismatch at step " + step;
                    }
                }
            }
        }
        return null;
    }

    private String quickselect() {
        SplittableRandom rnd = new SplittableRandom(SEED);
        for (int trial = 0; trial < 200; trial++) {
            int n = 1 + rnd.nextInt(300);
            double[] v = new double[n];
            for (int i = 0; i < n; i++) {
                v[i] = rnd.nextInt(50); // many duplicates on purpose
            }
            double[] s = v.clone();
            Arrays.sort(s);
            for (double p : new double[] {0, 10, 50, 90, 95, 100}) {
                int rank = Math.max(1, (int) Math.ceil(p / 100 * n));
                if (RandomizedQuickselect.percentile(v, p, trial).value() != s[rank - 1]) {
                    return "trial " + trial + ", p=" + p;
                }
            }
        }
        return null;
    }

    private String tsp() {
        SplittableRandom rnd = new SplittableRandom(SEED);
        for (int trial = 0; trial < 60; trial++) {
            int n = 2 + rnd.nextInt(8);
            double[] xs = new double[n];
            double[] ys = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = rnd.nextDouble() * 50;
                ys[i] = rnd.nextDouble() * 50;
            }
            DistanceMatrix d = DistanceMatrix.euclidean(xs, ys);
            MstPreorderTsp.Result r = MstPreorderTsp.solve(d, 0);
            double opt = HeldKarpTsp.optimalLength(d);
            if (r.mstWeight() > opt + 1e-9 || r.length() < opt - 1e-9 || r.length() > 2 * opt + 1e-9) {
                return "trial " + trial + ": mst=" + r.mstWeight() + " opt=" + opt + " tour=" + r.length();
            }
        }
        return null;
    }
}
