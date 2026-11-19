# NEXUS: Technical Report

**Infrastructure Failure & Resilience Simulator** · Advanced Algorithms CIA 3 · Semester V · 2026-27
**Team:** _names, IDs and individual contributions to be filled in by the team (section 10)_

## 1. Problem statement and constraints

A city's relief logistics runs on a network: depots hold relief kits, roads carry them (with a throughput limit per 12-hour
window), and demand zones (ward-level shelter clusters) need them. When infrastructure fails, planners need to know:

1. the maximum number of kits that can still reach demand zones (and which roads limit it);
2. which zones have lost every physical link to a depot;
3. when, over the next 72 hours, the shortfall is worst;
4. how severe this failure is compared with other plausible failures;
5. which recovery action restores the most capacity;

plus three supporting tasks: keep incidents prioritised, screen duplicate incident reports, and plan a vehicle tour.

**Inputs.** A dataset of nodes (`DEPOT` with `supply`, `JUNCTION`, `DEMAND_ZONE` with `demand`, `severity` 1-5,
`vulnerablePercent` 0-100), roads (`capacity` in kits per window per direction, `lengthKm`, optional `oneWay`), incidents
and a 24-168-hour demand timeline. **Limits:** 500 nodes, 3,000 roads, quantities in [0, 10⁹], 2 MiB request body.
All bundled data is synthetic (fictional city "Varunapuri").

**Validation and failure cases.** Empty datasets, duplicate ids, unknown road endpoints, self-loops, negative or fractional
capacities, out-of-range severities, malformed JSON, unknown fields, wrong types, oversized bodies, a source equal to the sink,
failed source/sink, non-metric distance matrices and unreachable route stops are all rejected or reported explicitly with the
offending field. Nothing is silently replaced by a default (the only documented default is `oneWay = false` when absent).

## 2. System design

```
React + TypeScript SPA  --JSON (dataset + parameters)-->  Spring Boot REST API (stateless)
  pages, SVG map/charts                                   ApiController -> DatasetValidator -> Services -> algorithms
  dataset held in browser  <--results, traces, proofs---  ApiExceptionHandler: {error, message, issues[]}
```

* **Layers:** `web` (controller, error handler), `dto` (request/response records), `service` (validation, modelling,
  orchestration), `algorithms` (pure Java, no Spring), `model` (dataset records), `config` (CORS, size limit, OpenAPI).
* **Stateless API:** the client sends the dataset with each call, so concurrent users never interfere and the server needs
  no database (free hosting disks are ephemeral).
* **Deployment:** one Docker image (website built into Spring's static resources), Render free web service, `/api/health`
  health check, CI on GitHub Actions.

### Endpoints (full list in `docs/api.md`)

`GET /api/health`, `GET /api/algorithms`, `GET /api/dataset/demo`, `POST /api/dataset/validate`, `POST /api/dataset/import-csv`,
`POST /api/flow/optimize`, `POST /api/failure-lab/simulate`, `POST /api/resilience/components`, `POST /api/incidents/priority`,
`POST /api/incidents/screen`, `POST /api/timeline/query`, `POST /api/routes/plan`, `POST /api/lab/{flow|dsu|rbtree|quickselect|tsp}`,
`GET /api/benchmarks`, `POST /api/benchmarks/{suite}`, `POST /api/selfcheck`.

### Three graphs, never mixed

| Graph | Built from | Used by |
|---|---|---|
| Directed capacity network | road `capacity` (two arcs for two-way roads), super source → depots (supply), zones → super sink (demand) | Edmonds-Karp, Ford-Fulkerson, min cut |
| Undirected connectivity graph | one edge per operating road, ignoring capacity and direction | DSU |
| Metric distance matrix | shortest paths over road `lengthKm` | TSP 2-approximation |

## 3. Algorithms: pseudocode, correctness and complexity

### 3.1 Network flow (Edmonds-Karp, Ford-Fulkerson, min cut) — `algorithms/flow`

Residual representation: edge *e* is stored as forward arc `2e` and reverse arc `2e+1`; pushing *f* along arc *a* does
`res[a] -= f; res[a^1] += f`.

```
EDMONDS-KARP(G, s, t):                      FORD-FULKERSON(G, s, t):
  reset residuals                             same loop, but the path is found by
  while BFS from s reaches t (res > 0):       depth-first search (explicit stack +
     b = min res on the BFS path              per-node arc iterator)
     push b along the path; flow += b
MIN-CUT: S = nodes reachable from s with res > 0; cut = edges S -> V\S
```

*Correctness.* Each augmentation keeps capacity and conservation constraints. When no augmenting path exists, the set *S*
of residual-reachable nodes defines a cut whose forward edges are saturated and whose backward edges carry zero flow, so
flow = capacity(S, V\S). Since any flow is at most any cut's capacity, the flow is maximum and the cut minimum
(max-flow min-cut theorem). NEXUS **checks this at runtime**: `MinCut.verifyFlow` independently re-checks conservation and
capacity bounds, and the service throws if cut capacity ≠ flow.

*Termination.* With integer capacities every augmentation adds at least 1, so Ford-Fulkerson stops after at most |f*|
augmentations. Edmonds-Karp's shortest-path rule makes BFS distances non-decreasing; each arc is critical O(V) times, so
there are O(VE) augmentations.

*Complexity.* EK O(VE²), FF O(E·|f*|), min cut O(V+E), space O(V+E).

### 3.2 Disjoint-set union — `algorithms/dsu`

```
FIND(x): r = root of x; then point every node on the path directly at r
UNION(a,b): ra, rb = FIND(a), FIND(b); if equal return false
            attach lower-rank root under higher; equal ranks -> increment rank
```

*Correctness.* Each set is a rooted tree; FIND returns the unique root, and UNION links two roots, so "same root" ⇔
"same component". *Complexity:* O(α(n)) amortized per operation, O(n) space. *Limitation:* no deletion, so failures rebuild
the DSU from the modified graph; the critical-road scan rebuilds once per road (O(E(V+E)α(V))).

### 3.3 Red-black tree — `algorithms/rbtree`

CLRS insert (cases 1-3: recolour, rotate inner child, rotate grandparent) and delete (cases 1-4 on the doubly-black node),
a sentinel `nil`, and subtree sizes for O(log n) `rank`/`select`. Keys are `PriorityKey(score, id)`: score descending,
id ascending, so keys are unique. An update is delete + reinsert because the key changes.

*Correctness.* The invariants (black root, no red-red, equal black height, BST order, correct sizes) are checked by
`validate()` after operations in the tests and the UI. Black-height equality and no red-red imply height ≤ 2 log₂(n+1),
giving O(log n) worst case for every operation. Space O(n).

### 3.4 Bloom filter — `algorithms/bloom`

```
m = ceil(-n ln p / (ln 2)^2), k = round((m/n) ln 2)
positions g_i(x) = (h1(x) + i·h2(x)) mod m, i = 0..k-1   (seeded FNV-1a + SplitMix64, h2 odd)
ADD sets k bits; QUERY returns true iff all k bits are set
```

*Correctness.* An inserted item's bits are set and never cleared (insertion-only, fixed seed), so there are no false
negatives. A false positive happens when all k bits were set by other items, with probability ≈ (1 − e^(−kn/m))^k.
NEXUS treats every positive as "possibly present" and confirms it against the exact registry; the measured false-positive
rate uses separately generated ids that are verified absent. *Complexity:* O(k) per operation, m bits of space.

### 3.5 Segment tree with lazy propagation — `algorithms/segtree`

Each node stores sum, max and leftmost argmax of its range, plus a pending add tag. Queries combine O(log n) canonical
nodes; a range add tags fully covered nodes and pushes tags down only when needed. *Correctness:* each node's aggregate
equals its range's true aggregate plus pending tags above it (an invariant preserved by `push`/`pull`); tests compare every
answer with a plain array. *Complexity:* build O(n), each query/update O(log n), space O(n).

### 3.6 Randomized Quickselect — `algorithms/select`

```
SELECT(A, k): repeat: pick a uniformly random pivot p in A[lo..hi];
  3-way partition into < p | = p | > p; recurse (iteratively) into the part containing k
```

*Correctness:* after partitioning, every element left of the "= p" block is smaller and every element right is larger, so
k lies in exactly one part, and the "= p" block holds the answer if k falls inside it. *Complexity:* expected O(n)
comparisons, worst case O(n²) with negligible probability; 3-way partitioning keeps many equal values linear.
Seeded pivots make runs reproducible. Percentiles use the nearest-rank definition, cross-checked against a full sort.

### 3.7 Metric TSP 2-approximation — `algorithms/tsp`

```
validate matrix: square, finite, non-negative, zero diagonal, symmetric, d(i,k) ≤ d(i,j) + d(j,k)
T = Prim MST (dense, O(n^2)); tour = preorder walk of T from the depot
```

*Guarantee:* removing one edge of an optimal tour leaves a spanning tree, so w(T) ≤ OPT; the preorder tour shortcuts a
walk of cost 2w(T), and the triangle inequality makes every shortcut no longer, so tour ≤ 2w(T) ≤ 2·OPT. The tour is
reported as approximate; `tour / w(T)` is a certified upper bound on the actual ratio, and Held-Karp gives the exact
optimum for ≤ 10 stops. Non-metric matrices are rejected because the guarantee would not hold.

### Complexity summary

| Algorithm | Time | Space |
|---|---|---|
| Edmonds-Karp | O(V E²) | O(V+E) |
| Ford-Fulkerson (DFS) | O(E·\|f*\|) | O(V+E) |
| Min cut from residual graph | O(V+E) | O(V) |
| DSU | O(α(n)) amortized / op | O(n) |
| Red-black tree | O(log n) insert, delete, search, rank | O(n) |
| Bloom filter | O(k) add/query | m bits |
| Segment tree | O(n) build, O(log n) query/update | O(n) |
| Randomized Quickselect | expected O(n) | O(1) extra |
| MST + preorder TSP | O(n²) (+ O(n³) metric check) | O(n²) |

## 4. The Failure Lab: how the algorithms combine

For a chosen failure: (1) Edmonds-Karp before/after gives the throughput drop and how the min cut moves; (2) a rebuilt DSU
finds zones with no physical link to any depot; (3) the hourly shortfall series `max(0, demand(h) − throughput/12)` goes into
a segment tree, which answers the total, the worst *L*-hour window (range sums at every start) and the peak hour (range max);
(4) a seeded batch of random *r*-road failures is solved and Quickselect gives P50/P90/P95 impact, each checked against a full
sort; (5) recovery candidates (restore each failed element; +50% capacity on each post-failure min-cut road) are re-solved and
ranked by recovered throughput.

## 5. Benchmark methodology and results

Inputs are generated from a fixed seed **before** timing; only the algorithm call is timed with `System.nanoTime()`; each
measurement has warm-up runs and reports the median and minimum of repeated runs; machine-independent operation counts are
reported alongside. Full tables: `docs/benchmark-results.md` (raw JSON in `docs/evidence/`).

Key observations from the recorded run:

* **Max flow.** Both algorithms always agree on the value. On these random layered graphs, DFS Ford-Fulkerson used fewer arc
  scans and less time than Edmonds-Karp. The benefit of Edmonds-Karp is its capacity-independent worst-case bound, shown by
  the test where BFS needs 2 augmentations while an unlucky DFS order can need 2,000,000.
* **DSU vs naive linking.** At n = 10⁴ the naive version needed about 390× more pointer hops; at 10⁵ and 5·10⁵ it exceeded a
  budget of 2·10⁸ hops, while DSU stayed near-linear.
* **Red-black tree vs sorted array.** Rotations grow as O(n) overall while the array baseline shifts O(n²) elements in total
  (6.2·10⁸ shifts at n = 50,000); tree height stayed well under the 2 log₂(n+1) bound.
* **Bloom filter.** Measured false-positive rates (1.00-1.07%) match the 1.00% theory at 9.6 bits (1.2 bytes) per id. An
  exact `HashSet` was faster in wall-clock time at these sizes; the filter's advantage is memory (it never stores the ids),
  which is why it is used only as a pre-check in front of the exact store.
* **Segment tree.** Nodes visited stay logarithmic per operation while the array baseline touches whole ranges.
* **Quickselect vs sort.** Same P90 every time, with about 2-4n comparisons against n log₂ n for sorting.
* **TSP.** The MST tour stayed at 1.56-1.62× the MST lower bound (so at most that far from optimal). Nearest neighbour was
  shorter on all three random instances (1.39-1.58×) but carries no constant-factor guarantee; the 2-approximation is used
  because its bound is provable.

## 6. Testing

62 JUnit 5 tests (`mvn verify`), at least five per algorithm, including boundary and invalid-input cases and seeded
property tests against oracles. Summary and evidence: `docs/test-evidence.md`. A live self-check endpoint re-runs
≈10,000 seeded cases on the deployed server.

## 7. Limitations

Single-commodity, single-period flow (no travel time, schedules, perishability or fairness); a transparent but unvalidated
incident score; evenly spread hourly capacity; one-vehicle routing without time windows; insertion-only Bloom filter;
synthetic data; free-tier hosting is slow and sleeps when idle.

## 8. Future work

Real road networks (OpenStreetMap), multi-commodity and time-expanded flows, min-cost flow for fairness/cost trade-offs,
correlated failure scenarios (flood maps), and a dynamic-connectivity structure in place of DSU rebuilds.

## 9. References

Cormen, Leiserson, Rivest, Stein, *Introduction to Algorithms*, 3rd/4th ed. (chapters on red-black trees, maximum flow,
approximation algorithms); Edmonds & Karp (1972); Kirsch & Mitzenmacher (2006), "Less hashing, same performance";
Rosenkrantz, Stearns & Lewis (1977) on TSP heuristics; Bentley, *Programming Pearls* (segment-tree style range queries);
Hoare (1961), FIND.

## 10. Individual contributions

_To be completed truthfully by each team member (who designed, implemented, tested and documented which parts)._

| Member | ID | Contribution |
|---|---|---|
| | | |
| | | |
