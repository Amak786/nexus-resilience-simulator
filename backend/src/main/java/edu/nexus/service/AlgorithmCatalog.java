package edu.nexus.service;

import edu.nexus.dto.LabDtos.AlgorithmInfo;

import java.util.List;

/** Complexity, workflow and limitations of every implemented algorithm (served to the UI and the docs). */
public final class AlgorithmCatalog {

    private AlgorithmCatalog() {}

    public static final List<AlgorithmInfo> ALL = List.of(
            new AlgorithmInfo("dijkstra-fibheap", "Dijkstra with a Fibonacci heap (vs java.util.PriorityQueue)", "Unit 2",
                    "algorithms.graph.Dijkstra, algorithms.heap.FibonacciHeap",
                    "O(E + V log V) with the Fibonacci heap; O((V + E) log V) with a binary heap", "O(V + E)",
                    "Network Explorer routes; Multi-stop Planner distance matrix",
                    "Non-negative weights only. Travel times are derived free-flow estimates, not live traffic. The Fibonacci heap has better amortized bounds but larger constants, so it is not always faster in practice (see benchmarks)."),
            new AlgorithmInfo("edmonds-karp", "Edmonds-Karp max flow (BFS augmenting paths) + min cut", "Unit 5",
                    "algorithms.flow.EdmondsKarp, algorithms.flow.MinCut",
                    "O(V E^2)", "O(V + E)",
                    "Critical Links: bottleneck between two points in a selected area",
                    "Runs on MODELLED capacity proxies (lanes x per-lane capacity), not measured throughput. The min cut is a property of the model, not a prediction of real disruption impact."),
            new AlgorithmInfo("ford-fulkerson", "Ford-Fulkerson method (DFS augmenting paths)", "Unit 5",
                    "algorithms.flow.FordFulkerson",
                    "O(E |f*|) for integer capacities (pseudo-polynomial)", "O(V + E)",
                    "Critical Links comparison on the same graph and capacities",
                    "Edmonds-Karp is the BFS variant of the same method. With integer capacities each augmentation adds >= 1, so it terminates; running time depends on the flow value."),
            new AlgorithmInfo("dsu", "Disjoint-set union (union by rank + path compression)", "Unit 2",
                    "algorithms.dsu.DisjointSetUnion",
                    "O(alpha(n)) amortized per operation", "O(n)",
                    "Network Health / Explorer: connected components before and after removing segments",
                    "No efficient deletion: removals are handled by rebuilding the DSU from the remaining segments."),
            new AlgorithmInfo("red-black-tree", "Red-black tree (CLRS) with subtree sizes", "Unit 3",
                    "algorithms.rbtree.RedBlackTree",
                    "O(log n) insert, delete, search, rank, count-in-range; O(log n + k) range scan", "O(n)",
                    "Network Health: OSM map notes ordered by (creation time, note id)",
                    "Indexes community map-issue reports, which are not traffic incidents."),
            new AlgorithmInfo("bloom-filter", "Bloom filter (bit array, k double-hashed positions)", "Unit 6 (CO5)",
                    "algorithms.bloom.BloomFilter",
                    "O(k) per add or query", "m bits (about 9.6 bits per item at 1% FPR)",
                    "Network Health: duplicate pre-check when importing the latest OSM notes",
                    "False positives are possible, so every 'possibly present' id is confirmed against the exact id set; it is never the source of truth."),
            new AlgorithmInfo("segment-tree", "Segment tree with lazy range add (sum, min, max)", "Unit 3",
                    "algorithms.segtree.SegmentTree",
                    "Build O(n); query and update O(log n)", "O(n)",
                    "Network Health: notes created per day (real time-indexed observations)",
                    "No historical travel-time observations are available, so it is applied to note counts; days after the snapshot are unknown until a refresh."),
            new AlgorithmInfo("quickselect", "Randomized Quickselect (3-way partition, seeded pivots)", "Unit 1 / Unit 6 (CO5)",
                    "algorithms.select.RandomizedQuickselect",
                    "Expected O(n); worst case O(n^2) with vanishing probability", "O(n) copy, O(1) extra",
                    "Network Health: P50/P90 resolution time of real closed map notes",
                    "Percentiles are flagged as not meaningful when fewer than 30 observations are available."),
            new AlgorithmInfo("metric-tsp", "Metric TSP 2-approximation (MST + preorder walk)", "Unit 5",
                    "algorithms.tsp.MstPreorderTsp, DistanceMatrix, NearestNeighbourTsp, HeldKarpTsp",
                    "O(n^2) (dense Prim) + O(n^3) metric validation", "O(n^2) matrix",
                    "Multi-stop Planner on real road distances",
                    "Approximate (<= 2 x OPT) for the symmetric planning distances; the executable road route is expanded leg by leg and is not claimed optimal."));
}
