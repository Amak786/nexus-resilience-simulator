# NEXUS: Mumbai Network Intelligence. Technical Report

Advanced Algorithms · CIA 3 · Semester V · 2026-27
**Team:** _names, IDs and individual contributions to be filled in truthfully (section 10)._

## 1. Problem definition

Planners, students and city-watchers in Mumbai often ask graph questions about the road network: which areas are connected
only through a few links, which roads limit movement between two places, what is the shortest or fastest arterial route,
and how should a multi-stop inspection be ordered. NEXUS answers these on the **real mapped network** (OpenStreetMap), with
transparent data provenance, and also indexes and analyses real community-reported map issues (OSM notes).

**Non-goals.** No live traffic (no verified free feed exists), no measured capacities, no disaster-impact prediction, no
official use.

**Inputs.** A Greater Mumbai snapshot (bbox 18.89-19.29°N, 72.77-72.99°E): 12,397 OSM ways (motorway to tertiary) →
12,907 nodes, 18,169 segments; 2,567 OSM notes. User inputs: map clicks (snapped to the nearest intersection within 3 km),
preset areas, removed segments, date ranges, algorithm-lab inputs.
**Limits.** ≤ 20,000 segments per map request, ≤ 6,000 per flow analysis, ≤ 15 tour stops, ≤ 500 removed segments,
2 MiB request bodies. Invalid inputs return HTTP 400 with the field name.

## 2. Data and graph model

* **Pipeline** (`data-pipeline/build-mumbai-snapshot.mjs`): one documented Overpass query, one Notes API call, cached raw
  responses, reproducible offline rebuild. Provenance (source URL, licence, query, OSM data timestamp, retrieval time,
  coverage) is stored in each snapshot and served by `GET /api/mumbai/provenance`.
* **Nodes** = OSM nodes used by ≥ 2 selected ways, or way endpoints. **Segments** = way pieces between consecutive nodes
  (geometry simplified with Douglas-Peucker, ε ≈ 4 m; length from the full geometry by haversine).
* **Direction:** `oneway=yes/true/1`, roundabouts and motorways give one arc; `oneway=-1` reverses; otherwise two arcs.
* **Three separate per-segment fields:** `lengthM` (static), `travelTimeS` = length / speed with speed from `maxspeed` or
  class default (derived estimate), `capacityVph` = lanes per direction × per-lane capacity (modelled proxy; documented
  defaults, e.g. primary: 50 km/h, 2 lanes, 1,600 veh/h/lane).
* **Representation:** CSR adjacency (`RoadGraph`), O(V + E) space.

## 3. Algorithms (pseudocode, correctness, complexity)

### 3.1 Dijkstra with a Fibonacci heap (`algorithms/graph/Dijkstra`, `algorithms/heap/FibonacciHeap`)
```
dist[s]=0; Q.insert(s,0)
while Q not empty: u = Q.extractMin(); if u == target: stop
  for arc (u,v,w): if dist[u]+w < dist[v]: dist[v]=dist[u]+w; prev[v]=arc; Q.decreaseKey(v) or Q.insert(v)
```
Correctness: with non-negative weights the extracted node's distance is final (greedy argument), so early exit is safe.
Fibonacci heap: lazy insert O(1); extract-min removes the minimum, promotes its children and **consolidates** roots of equal
degree (O(log n) amortized, degree ≤ log_φ n); decrease-key cuts a node violating heap order and **cascading-cuts** marked
parents (O(1) amortized; potential = roots + 2 × marked). Dijkstra: O(E + V log V). Baseline: `PriorityQueue` with lazy
deletion, O((V + E) log V). Each route request runs both and checks the distances agree.

### 3.2 Edmonds-Karp, Ford-Fulkerson, min cut (`algorithms/flow`)
Residual arcs are stored in pairs (`a ^ 1` is the twin); pushing f lowers `res[a]` and raises `res[a^1]`. Ford-Fulkerson
finds any augmenting path by DFS (terminates for integer capacities: each augmentation adds ≥ 1; O(E·|f*|)). Edmonds-Karp
uses BFS shortest paths: O(VE) augmentations, O(VE²) time. When no path remains, S = residual-reachable nodes; edges S→T
are saturated and form a minimum cut whose capacity equals the flow (max-flow min-cut theorem). NEXUS verifies conservation
and capacity bounds independently and asserts cut = flow on every request.

### 3.3 DSU (`algorithms/dsu`)
Union by rank + full path compression: O(α(n)) amortized. Components of an area's undirected view; removals rebuild the DSU
(no deletions in ordinary DSU).

### 3.4 Red-black tree (`algorithms/rbtree`)
CLRS insert (cases 1-3) and delete (cases 1-4), sentinel nil, subtree sizes. Key = (creation epoch second, note id), which
is unique. Range scan O(log n + k), count-in-range O(log n). `validate()` checks a black root, no red-red pair, equal black
heights, BST order and sizes.

### 3.5 Bloom filter (`algorithms/bloom`)
m = ⌈−n ln p / ln²2⌉, k = round((m/n) ln 2), positions (h1 + i·h2) mod m. Used to pre-check note ids when importing the
latest notes: "definitely absent" skips the exact lookup; every "possibly present" id is confirmed against the exact id set.
No false negatives in this insertion-only use; false positives are counted and resolved.

### 3.6 Segment tree (`algorithms/segtree`)
Sum, min and max with leftmost argmax/argmin and lazy range add; O(log n) per query/update. Applied to real notes-per-day
counts; live imports do point updates. Every answer is cross-checked with a plain scan.

### 3.7 Randomized Quickselect (`algorithms/select`)
Random pivot, 3-way partition; expected O(n), worst case O(n²). Nearest-rank P50/P90 of real resolution times; compared
with a full sort; flagged as not meaningful below 30 observations; seeded for reproducibility.

### 3.8 Metric TSP 2-approximation (`algorithms/tsp`, `mumbai/TourService`)
Directed road distances d by Dijkstra from each stop; stops not reachable both ways from the start are excluded and reported.
D(i,j) = max(d(i,j), d(j,i)) is symmetric and satisfies the triangle inequality, and the matrix is validated anyway.
Prim's MST + preorder walk gives tour ≤ 2·MST ≤ 2·OPT(D). The executable route expands each leg into its directed shortest
path, so its length ≤ the planned length. Nearest neighbour is the baseline; Held-Karp gives the exact optimum for ≤ 10 stops.

| Algorithm | Time | Space |
|---|---|---|
| Dijkstra + Fibonacci heap | O(E + V log V) amortized | O(V + E) |
| Dijkstra + binary heap (baseline) | O((V + E) log V) | O(V + E) |
| Edmonds-Karp | O(V E²) | O(V + E) |
| Ford-Fulkerson (integer capacities) | O(E·\|f*\|) | O(V + E) |
| DSU | O(α(n)) amortized | O(n) |
| Red-black tree | O(log n); range scan O(log n + k) | O(n) |
| Bloom filter | O(k) | m bits |
| Segment tree | O(log n) per op | O(n) |
| Randomized Quickselect | expected O(n) | O(1) extra |
| MST-preorder TSP | O(n²) + O(n³) validation | O(n²) |

## 4. System design

Spring Boot 3.5 (Java 21): `MumbaiController` and `ApiController` → services (`MumbaiService`, `EventService`,
`TourService`, `LabService`, `BenchmarkService`, `SelfCheckService`) → `edu.nexus.algorithms.*` (no Spring). Central JSON
error handling, request-size filter, CORS from environment variables, OpenAPI. React + TypeScript + Vite + Leaflet front
end with 6 screens; one Docker image serves both on Render.

## 5. Testing

76 JUnit 5 tests (`mvn verify`): Fibonacci heap (consolidation, cuts, cascading cuts, duplicates, empty heap, 20k random ops
vs `PriorityQueue`), Dijkstra (hand-checked, one-way, unreachable, 100 random graphs vs Floyd-Warshall, early exit),
max-flow (CLRS example, reverse-arc cancellation, disconnected, zero capacity, parallel edges, 300 random graphs with
cut = flow), DSU, red-black tree (rotations, deletion, range scans vs `TreeMap`), Bloom filter (statistical FPR, exact-store
handling), segment tree (vs plain array incl. min), Quickselect (vs sort, duplicates, seeds), TSP (metric rejection,
2-approximation vs Held-Karp), Mumbai services on the real snapshot, offline Bloom import, HTTP integration (errors, 413, CORS).

## 6. Benchmarks

Method: seeded inputs generated before timing; only the algorithm call is timed; warm-up runs; median and minimum of
repeated runs; machine-independent operation counts. Results: `docs/benchmark-results.md`. Key observations:
* **Fibonacci heap vs PriorityQueue:** identical distances, slightly fewer heap operations, but about **2× slower** on grids
  of 10⁴-3.6·10⁵ nodes (pointer-heavy nodes, poor cache locality). Better amortized bounds do not guarantee faster code.
* **Edmonds-Karp vs Ford-Fulkerson:** always the same value. DFS is often faster on random graphs; EK's benefit is its
  capacity-independent worst case.
* **Quickselect vs sort, segment tree vs scan, DSU vs naive, red-black tree vs sorted list:** see the tables.

## 7. Limitations

No live traffic or measured capacities; arterial roads only; boundary effects in bounded areas; notes are uneven community
reports; free-tier hosting is slow and sleeps; the min cut is a model property, not an impact prediction.

## 8. Future work

Verified traffic or transit feeds where licensed (e.g. official GTFS if published), residential-road layer with contraction
for speed, time-dependent travel times, k-shortest paths, Gomory-Hu trees for all-pairs min cuts.

## 9. References

CLRS, *Introduction to Algorithms*; Fredman & Tarjan (1987); Edmonds & Karp (1972); Kirsch & Mitzenmacher (2006);
Rosenkrantz, Stearns & Lewis (1977); OpenStreetMap wiki: Key:oneway, Key:lanes, Key:maxspeed, Overpass API, Notes API.

## 10. Individual contributions

| Member | ID | Contribution |
|---|---|---|
| | | |
