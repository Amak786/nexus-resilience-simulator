# Viva questions and simple, truthful answers

**Is this real data?** The road geometry, one-way rules and map notes are real OpenStreetMap data (snapshot date shown in the
app). Travel times are derived from speed tags or defaults, capacities are a modelled proxy, and there is no live traffic.

**How do roads become a graph?** Intersections (nodes shared by two or more roads) and road ends are nodes; the piece of
road between two consecutive nodes is a segment. Two-way segments give two directed arcs, one-way segments one.

**Why a Fibonacci heap?** Dijkstra does many decrease-key operations. A Fibonacci heap does decrease-key in O(1) amortized
and extract-min in O(log n) amortized, so Dijkstra is O(E + V log V).

**Is it faster?** Not in our measurements: about 2× slower than `PriorityQueue`. It has many pointers and poor cache
locality, and road graphs are sparse, so the asymptotic gain doesn't show. We report this rather than hide it.

**What are consolidation and cascading cuts?** Extract-min links roots of equal degree until all degrees differ (this keeps
trees "Fibonacci-sized"). When a node loses a second child it is cut too, which keeps degrees at most log_φ n.

**Ford-Fulkerson vs Edmonds-Karp?** Same method; EK picks shortest (BFS) augmenting paths, which bounds the number of
augmentations by O(VE) whatever the capacities are. FF with DFS depends on the flow value: O(E·|f*|) for integer capacities.

**What does the min cut mean here?** In the capacity model, it is the cheapest set of directed segments that separates the
sink from the source; its capacity equals the max flow. It is not a prediction of real-world impact.

**Why can't DSU handle road closures directly?** DSU only merges sets. To remove roads we rebuild it from the remaining roads.

**Why a red-black tree for notes?** We need records ordered by time, range queries and inserts when new notes arrive, all
in O(log n). The key (time, id) is unique.

**Can the Bloom filter lose a note?** No false negatives here (insertion-only, fixed seed). It can give false positives, so
every "possibly present" id is checked against the exact id set before being treated as a duplicate.

**What is the segment tree used for?** Notes per day: total in a date range, busiest day (max) and quietest (min) in
O(log n). Every answer is cross-checked with a scan.

**Why Quickselect, and when is a percentile meaningless?** P50/P90 in expected O(n) without sorting. With fewer than 30
observations we flag the result as not meaningful.

**Why is the TSP tour at most twice optimal?** The MST costs at most the optimal tour; walking the tree costs twice the MST;
shortcuts never add length because the planning distances satisfy the triangle inequality. With one-way roads we use
max(d(i,j), d(j,i)) to keep the matrix symmetric and metric; the drivable route is never longer than the plan.

**What would make this production-grade?** Licensed live traffic data, measured capacities, residential roads, validation
with real observations, monitoring, and authentication. None of these are claimed today.

**What did you implement?** _Each student answers from their own contribution (report section 10)._
