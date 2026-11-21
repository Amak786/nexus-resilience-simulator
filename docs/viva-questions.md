# Viva questions and answers

## Project

**1. What is the project in one sentence?**
It compares a trusted snapshot of Mumbai's real road network with an updated one, shows what changed, measures what each change does to
connectivity and routes, and ranks changes for a human to check.

**2. What real-world problem does it solve?**
Navigation and emergency software trusts its road data. A wrong or tampered dataset silently breaks routes. The tool makes such changes visible.

**3. Where does the data come from?**
OpenStreetMap via the Overpass API: the current state (2026-11-20) and the same query on OSM history for 2025-11-20. Map notes come from the OSM Notes API. Licence ODbL.

**4. Is anything in the app fake?**
No real-looking number is invented. Estimates (free-flow time, lane capacity) are labelled D, demonstrations are labelled E and SIMULATED_TAMPERING,
and live traffic is shown as unavailable.

**5. What does the project contribute beyond a normal map application?**
A map app answers "what is my route?". This answers "did the data behind the route change, what did that break, and what should be checked first?",
with a reproducible diff, impact measurement, a documented ranking rule and evidence links.

## Integrity and security

**6. How do you distinguish a legitimate update from suspicious tampering?**
The program does not decide intent. It scores impact (road class, junctions cut off, probe route, one-way flips, missing public record).
Real OSM edits have a public history the reviewer can check; a person marks a change legitimate. Demonstrations are always simulated tampering.

**7. Why is "verified malicious" always zero?**
Proving malice needs evidence outside the data, such as an incident report or account forensics. Claiming it from an anomaly would be dishonest.

**8. Why use SHA-256 hashes?**
A hash proves whether two datasets are byte-identical. Any demonstration modification changes the updated hash and leaves the baseline hash unchanged.
A hash says that something changed, not what; the diff says what.

**9. Why can a high-scoring change still be legitimate?**
A new flyover legitimately adds a major road that joins many junctions. The score only orders the review.

## Algorithms

**10. Why DSU rather than BFS or DFS?**
Counting components over an edge list is near-linear with DSU (O(m α(n))) and needs no adjacency list or visited array per run. BFS gives the same
components and is used in the tests as the independent reference.

**11. Why not use DSU for every connectivity task?**
DSU ignores direction, so it measures weak connectivity only, and it cannot delete an edge. Directed reachability and routes need BFS or Dijkstra,
and after a removal DSU must be rebuilt.

**12. How does a removed edge affect connectivity?**
If the edge was a bridge, its component splits and some junctions lose the main network; otherwise a detour exists and the count is unchanged.
The app rebuilds DSU without that road and counts junctions that left the main component.

**13. Why use a Fibonacci heap?**
It gives Dijkstra O(m + n log n) through O(1) amortized decrease-key. On our sparse road graph it is about 2x slower than Java's PriorityQueue
in practice because of constant factors and memory locality. Both run, and the tests check that they give identical distances.

**14. How do Ford-Fulkerson and Edmonds-Karp differ?**
Both push flow along augmenting paths in the residual graph. Ford-Fulkerson finds any path (DFS here); Edmonds-Karp always takes the shortest path by BFS,
which bounds it at O(V E²) regardless of capacities.

**15. What does a minimum cut tell us?**
The smallest total capacity of edges whose removal separates source from sink. With unit capacities it is the fewest road segments whose closure
disconnects the probe points, and it equals the number of road-disjoint routes (max-flow min-cut theorem, Menger).

**16. Why unit capacities instead of vehicles per hour?**
No measured capacities exist for Mumbai, and about 80 % of segments have no lanes tag. Unit capacity answers a structural question without inventing data.

**17. Why can a Bloom filter return a false positive?**
Each item sets k bits. A never-inserted item may find all its k bits already set by other items. It can never give a false negative,
so a "possibly present" answer is confirmed with an exact HashSet.

**18. How is the false-positive rate chosen?**
For n items and target p, m = -n ln p / (ln 2)² bits and k = (m/n) ln 2 hashes. We use p = 1 %; the measured false positives are shown.

**19. Why randomized Quickselect instead of sorting every alert?**
Top-k only needs the k-th largest score. Quickselect finds it in expected O(n); sorting costs O(n log n). The random pivot avoids worst-case
inputs, and the result is checked against a full sort.

**20. Why a red-black tree for alerts?**
Alerts must stay ordered by (score, id) while reviewed alerts are deleted. A red-black tree keeps insert, delete and search at O(log n)
with a height of at most 2 log₂(n+1).

**21. What does the segment tree do?**
It stores risk per west-to-east strip and answers range sum or max in O(log n). Marking a change legitimate is a point update.

**22. When is the metric-TSP approximation valid?**
When distances satisfy the triangle inequality. Shortest-path road distances do; the matrix is symmetrised by max(d(i,j), d(j,i)).
MST preorder is then at most twice the optimal tour, and the MST weight is a lower bound shown beside it.

**23. Why use OSM way ids as the comparison key?**
Way ids are stable across OSM versions, so an edit to a road keeps its id. Junctions can split segments differently between snapshots,
so comparing segments would report false changes.

## Correctness and evidence

**24. How do you prove that the program works correctly?**
- 94 automated tests: unit tests for each algorithm, property tests against oracles (BFS for DSU, sorting for Quickselect, brute force for the segment tree, exact TSP for small cases), invariant checks for the red-black tree and Fibonacci heap, max flow = min cut with conservation checked, and integration tests on the real snapshots.
- A self-check endpoint reruns seeded checks on the deployed server.

**25. Is the result reproducible?**
Yes. The snapshots are committed, Quickselect uses a fixed seed, scenario S1 is deterministic, and a test checks that the same request gives the same ranking.

**26. What are the complexities of the full analysis?**
Diff O(m); per-change DSU impact O(c·m·α(n)); Dijkstra O(m + n log n); Edmonds-Karp small in practice because max flow ≤ degree of the source; ranking expected O(c).

**27. What are the main limitations?**
Arterial roads only, OSM is not ground truth, no live traffic or measured capacity, the scoring weights are a transparent judgement, and areas are bounded.

**28. How would you extend it?**
Scheduled snapshot imports with automatic diffing, signed datasets (digital signatures, not just hashes), directed reachability impact per change, and a licensed traffic feed.
