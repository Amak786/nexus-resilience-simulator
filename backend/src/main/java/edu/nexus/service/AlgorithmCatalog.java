package edu.nexus.service;

import edu.nexus.dto.LabDtos.AlgorithmInfo;

import java.util.List;

/** Complexity and limitations of every implemented algorithm (served to the UI and the docs). */
public final class AlgorithmCatalog {

    private AlgorithmCatalog() {}

    public static final List<AlgorithmInfo> ALL = List.of(
            new AlgorithmInfo("edmonds-karp", "Edmonds-Karp max flow (BFS augmenting paths) + min cut", "Unit 5",
                    "algorithms.flow.EdmondsKarp, algorithms.flow.MinCut",
                    "O(V E^2)", "O(V + E)",
                    "Flow Optimizer, Failure Lab throughput, recovery ranking, Overview bottlenecks",
                    "Single commodity, one period; integer capacities; maximises total flow, not fairness."),
            new AlgorithmInfo("ford-fulkerson", "Ford-Fulkerson method (DFS augmenting paths)", "Unit 5",
                    "algorithms.flow.FordFulkerson",
                    "O(E |f*|) for integer capacities (pseudo-polynomial)", "O(V + E)",
                    "Compared with Edmonds-Karp in Flow Optimizer, Algorithm Lab and Benchmarks",
                    "Same family as Edmonds-Karp, not a separate technique; slow when capacities are large; needs integer capacities to guarantee termination."),
            new AlgorithmInfo("dsu", "Disjoint-set union (union by rank + path compression)", "Unit 2",
                    "algorithms.dsu.DisjointSetUnion",
                    "O(alpha(n)) amortized per operation", "O(n)",
                    "Connected infrastructure groups before/after failures; critical-road scan",
                    "No efficient deletion: failures are handled by rebuilding from the modified graph."),
            new AlgorithmInfo("red-black-tree", "Red-black tree (CLRS) with subtree sizes", "Unit 3",
                    "algorithms.rbtree.RedBlackTree, algorithms.rbtree.PriorityKey",
                    "O(log n) insert, delete, search, rank; in-order O(n)", "O(n)",
                    "Incident priority queue (score descending, id tie-break)",
                    "Orders by a transparent triage score; the score is not a validated risk model."),
            new AlgorithmInfo("bloom-filter", "Bloom filter (bit array, k double-hashed positions)", "Unit 6 (CO5)",
                    "algorithms.bloom.BloomFilter",
                    "O(k) per add or query", "m bits (about 9.6 bits per item at 1% FPR)",
                    "Duplicate-incident pre-check before exact verification",
                    "False positives possible (always verified exactly); no deletion; no false negatives under insertion-only use."),
            new AlgorithmInfo("segment-tree", "Segment tree with lazy range add (sum, max, argmax)", "Unit 3",
                    "algorithms.segtree.SegmentTree",
                    "Build O(n); query and range update O(log n)", "O(n)",
                    "Hourly shortfall timeline: totals, worst interval, peak hour, demand surges",
                    "Fixed length; for one query on static data a prefix sum is simpler."),
            new AlgorithmInfo("quickselect", "Randomized Quickselect (3-way partition, seeded pivots)", "Unit 1 / Unit 6 (CO5)",
                    "algorithms.select.RandomizedQuickselect",
                    "Expected O(n); worst case O(n^2) with vanishing probability", "O(n) copy, O(1) extra",
                    "P50/P90/P95 failure-impact percentiles in the Failure Lab",
                    "Each percentile is a separate selection; for many percentiles of one array, sorting once can be cheaper."),
            new AlgorithmInfo("metric-tsp", "Metric TSP 2-approximation (MST + preorder walk)", "Unit 5",
                    "algorithms.tsp.MstPreorderTsp, DistanceMatrix, NearestNeighbourTsp, HeldKarpTsp",
                    "O(n^2) (dense Prim) + O(n^3) metric validation", "O(n^2) matrix",
                    "Delivery Route Planner",
                    "Approximate (<= 2 x OPT) only for metric inputs; non-metric matrices are rejected; one vehicle, no time windows."));
}
