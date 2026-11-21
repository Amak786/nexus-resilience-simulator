# Algorithms

All are implemented by hand in `backend/src/main/java/edu/trace/algorithms` and tested in `backend/src/test/java/edu/trace`.
"Observed graph" = nodes sensor, process, remote IP, ASN; edges = observed relationships (n ≤ a few thousand).

| Algorithm | Purpose in TRACE | Input → output | Time | Space | Assumptions / limitations | Tests |
| --- | --- | --- | --- | --- | --- | --- |
| BFS (`GraphAlgorithms.bfs`) | Fewest-hop path; entities reachable from a node | graph, source → hops, parent arcs | O(V+E) | O(V) | Unweighted; observed reachability only | GA-1 vs Dijkstra with unit weights; TR-6 |
| DFS + Tarjan low-links | Articulation points and bridges (single points of connection) | graph → flags, bridge list | O(V+E) | O(V+E) | Iterative (no stack overflow); structural, not risk | GA-2 vs brute force (remove each node/edge) |
| Disjoint Set Union | Components now and per time window | edges → components | O(α(n)) per op | O(n) | No deletion → rebuilt per window | DisjointSetUnionTest; TR-6 |
| Dijkstra + Fibonacci heap | Lowest "evidence distance" path (weight = 1/observations) | weighted graph → distances, path | O(E + V log V) | O(V+E) | Non-negative weights; weight is a modelling choice; measured slower than PriorityQueue | DijkstraTest; FibonacciHeapTest; TR-6 (both heaps agree) |
| A* | Shortest road route with straight-line heuristic (Lab, OSM road graph) | road graph, s, t → distance, settled count | O(E + V log V) worst | O(V) | Needs admissible heuristic → not used on the observed graph (no coordinates) | GA-4: equals Dijkstra; settles fewer nodes on real Mumbai graph |
| Brandes betweenness | Structurally important entities | unweighted graph → score per node | O(VE) | O(V+E) | Skipped above 3,000 nodes; describes structure, not malice | GA-3 closed forms (path, star, cycle) |
| Ford-Fulkerson (DFS) | Relationship-disjoint paths between two entities | unit-capacity network → max flow | O(E·f) | O(V+E) | Integer capacities | MaxFlowTest; TR-6 |
| Edmonds-Karp (BFS) | Same value, polynomial bound; augmenting paths for the min cut | network → max flow, residual | O(VE²) | O(V+E) | - | MaxFlowTest (EK = FF, conservation, capacity) |
| Min cut | Smallest set of relationships separating two entities | residual graph → cut edges | O(V+E) after flow | O(V) | A bottleneck is not a vulnerability | max flow = min cut asserted everywhere |
| Segment tree | Observations per minute: range sum and peak minute | per-minute counts → sums, max | O(log n) per query | O(n) | Counts what sensors reported | SegmentTreeTest; TR-6 (= scan) |
| Randomized Quickselect | k-th highest finding score → top-k | scores, k → k-th value | expected O(n) | O(n) | Ranking only as good as the documented score | QuickselectTest; TR-5 (= sort) |
| Bloom filter | Screen every observed address against the Feodo list | list → bit array; address → maybe / no | O(k) | m bits | False positives (always confirmed exactly); no deletion | BloomFilterTest; TI-1 (every maybe confirmed) |
| Red-black tree | (1) Spamhaus DROP ranges keyed by start, floor lookup; (2) findings ordered by (score desc, id) | keys → ordered index | O(log n) | O(n) | Ranges must not overlap (true for DROP) | RedBlackTreeTest; GA-5 floor vs TreeMap; TI-2 boundaries |
| Metric TSP (MST preorder) | Visiting order for locations (Lab, road graph / matrices) | metric matrix → tour ≤ 2·OPT | O(n²) | O(n²) | Needs triangle inequality; not applied to network entities (no locations) | MetricTspTest (vs Held-Karp) |

## Scoring rule for findings
Severity base HIGH 70 / MEDIUM 40 / LOW 15; confidence HIGH +20, MEDIUM +10; volume + min(10, ⌊log₂ observations⌋); multiple sensors +5.
Test TR-5 checks that every score equals its written breakdown.

## Rules
- INTEL_FEODO / INTEL_DROP: remote public address on the list (HIGH severity, MEDIUM confidence).
- UNCOMMON_PORT: TCP to a public address on a port outside the documented common list and below 49152 (LOW, LOW).
- HIGH_FANOUT: one process reaches ≥ 40 distinct public addresses within 10 minutes (MEDIUM, LOW).
