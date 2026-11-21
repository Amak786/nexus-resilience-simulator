# Technical report: Nexus Resilience - Silent Shield

## 1. Overview

Silent Shield checks the integrity of a road-network dataset. It compares a trusted baseline snapshot of Mumbai's arterial roads
with an updated snapshot, finds structural differences, measures their effect on connectivity and routes, and ranks them for review.
The rest of the application (route finding, connectivity, critical links, multi-stop planning, Algorithm Lab) uses the same real graph.

## 2. Architecture

```
data-pipeline/build-mumbai-snapshot.mjs   Overpass (current + 2025-11-20 attic query), OSM Notes API
        │  roads.json, roads-baseline.json, notes.json   (committed, ODbL)
        ▼
backend (Spring Boot, Java 21)
  algorithms/   bloom, dsu, flow (FordFulkerson, EdmondsKarp, MinCut), graph (Dijkstra, RoadGraph CSR),
                heap (FibonacciHeap), rbtree, segtree, select (RandomizedQuickselect), tsp
  mumbai/       MumbaiNetwork (snapshot loader), MumbaiService (route, components, critical links, search),
                EventService (OSM notes), TourService (metric TSP over road distances)
  integrity/    IntegrityService + IntegrityController   (/api/integrity/*)
  web/, service/  Algorithm Lab, benchmarks, self-check, error handling
        ▼  JSON over HTTP
frontend (React + TypeScript + Leaflet, OSM tiles)  - displays results only; no algorithm runs in the browser
```

One Docker image serves the API and the built front end on Render.

## 3. Data sources

| Dataset | Query | Date | Size |
| --- | --- | --- | --- |
| Updated snapshot | `way["highway"~"^(motorway|trunk|primary|secondary|tertiary)(_link)?$"]` in bbox 18.89,72.77,19.29,72.99 | OSM timestamp 2026-11-20T16:43:50Z | 12,397 ways, 12,907 junctions, 18,169 segments |
| Trusted baseline | Same query with `[date:"2025-11-20T00:00:00Z"]` (Overpass attic data) | 2025-11-20 | 10,184 ways, 9,837 junctions, 13,842 segments |
| OSM map notes | Notes API, same bbox | retrieved 2026-11-20 | 2,567 notes (958 created since 2023-01-01) |

Graph construction (the same for both snapshots): a node is a way endpoint or a node shared by two ways; a segment is the piece of a way
between two such nodes; length is the haversine length of the OSM geometry; `oneway=yes/-1`, roundabouts and motorways are directed.
Licence: ODbL, © OpenStreetMap contributors. Usage policies respected: one query each, identifying User-Agent, cached raw files.

**Why the baseline is "trusted".** It is the OSM state a year earlier, i.e. the dataset version the application previously used. Trusted
means "the version we accept as reference", not "error-free".

## 4. The integrity workflow

1. **Extract** the induced subgraph of the selected area from both snapshots (segments with both ends inside the area).
2. **Apply demonstrations** (optional) to the updated subgraph: REMOVE_WAY, FLIP_ONEWAY, ADD_CONNECTION. They are marked DEMONSTRATION.
3. **Compare** way by way (OSM way id). A way is changed if it is absent on one side, its class or one-way rule differs, a one-way segment
   reverses, its length in the area differs by more than 10 m, or its name differs.
4. **Screen** with a Bloom filter of baseline fingerprints `(way id | class | one-way | length/10 m)`; possible hits are confirmed exactly.
5. **Measure impact** of each change with DSU: rebuild without the way and count junctions that leave the main component
   (for an added road, the junctions it joins). Junctions touched only by that road are ignored, because they disappear with it.
6. **Probe route**: Dijkstra (Fibonacci heap and PriorityQueue) between two probe points snapped to a junction that exists in both snapshots.
7. **Resilience**: Edmonds-Karp and Ford-Fulkerson with capacity 1 per directed segment give the number of road-disjoint routes; the minimum
   cut lists the fewest segments whose loss separates the probe points.
8. **Score and classify** with the published rule (README). Threshold 5 gives SUSPICIOUS_NEEDS_REVIEW.
9. **Rank and index**: Quickselect top-k, red-black alert index, segment tree over 16 west-to-east strips.
10. **Plan an inspection**: metric-TSP over the top flagged changes using shortest-path road distances.
11. **Hashes**: SHA-256 of each snapshot file and of the canonical area road list. A demonstration changes the updated hash and leaves the baseline hash alone.

Example (Dadar-Mahim-Bandra, probe 19.018,72.847 to 19.056,72.845, no demonstration): 497 real changes
(223 added, 33 removed, 89 geometry, 21 one-way, 18 class, 28 name), 9 suspicious, 0 malicious; Bloom screening
476 definitely new, 653 possibly known, 3 false positives (all caught by the exact check).

## 5. Distinguishing legitimate, suspicious, simulated and malicious

- **Legitimate** is a human decision (button "Mark as reviewed: legitimate"), normally after checking the public OSM edit history linked from each change.
- **Suspicious** means the change is important under the rule (major road, cuts off junctions, on the probe route, direction flipped, no public record).
- **Simulated tampering** is anything the user typed in as a demonstration.
- **Verified malicious** is never set by the program, because proving intent needs evidence outside the data (an incident report, account
  forensics, a confirmed revert by the OSM Data Working Group).

## 6. Algorithm justifications

| Algorithm | Why it fits | Why not the alternative |
| --- | --- | --- |
| DSU | Many "are these connected / how many components" questions over the same edge list; near-linear | BFS/DFS also works but needs adjacency lists per run; DSU is simpler for counts. DSU cannot delete, so for each change it is rebuilt, and it ignores direction (weak connectivity), so directed questions use Dijkstra/BFS |
| Dijkstra + Fibonacci heap | Non-negative lengths; the syllabus structure with O(1) amortized decrease-key | Measured slower than PriorityQueue on these sparse graphs; both are run and must agree |
| Ford-Fulkerson / Edmonds-Karp | Max flow with unit capacities = number of edge-disjoint routes (Menger) | No measured capacities exist, so vehicle flow would be invented |
| Min cut | Names the bottleneck segments | Only valid inside the bounded area |
| Bloom filter | Compact "seen before?" screen of trusted fingerprints with no false negatives | Can give false positives, hence the exact HashSet check |
| Quickselect | Top-k needs only the k-th score: expected O(n) | Sorting all changes costs O(n log n) |
| Red-black tree | Ordered alert list with O(log n) insert and delete after review | A sorted array costs O(n) per delete |
| Segment tree | Range sum/max of risk over strips with point updates | Prefix sums cannot be updated cheaply |
| Metric TSP | Visiting order for an inspector; MST preorder ≤ 2 × optimal on a metric | Exact TSP is exponential; used only to check small cases |

## 7. Complexity (n junctions, m segments, c changes)

Extraction O(m); diff O(m); Bloom O(m·k); impact O(c·m·α(n)); Dijkstra O(m + n log n); Edmonds-Karp O(n·m²) worst case
(small here because max flow ≤ out-degree of the source); Quickselect expected O(c); red-black tree O(c log c); segment tree O(16 + q log 16);
TSP O(s·(m + n log n) + s²).

## 8. Testing (94 automated tests, all passing)

- Algorithm unit tests (BloomFilter, DSU, Dijkstra, Fibonacci heap, max flow, metric TSP, Quickselect, red-black tree, segment tree, range queries).
- `IntegrityServiceTest` (12): real diff has no simulated or malicious items; Bloom counts add up and every hit is verified;
  scenario S1 is labelled and changes only the updated hash; same input gives same output and reset restores the real result;
  review removes a change from ranking, alert index and strip risk; EK = FF = min cut and both heaps agree on both snapshots;
  every score equals its breakdown; Quickselect top-k equals a sort oracle on random data; red-black invariants and order after deletions;
  segment-tree ranges equal brute force for all 136 ranges; DSU cut-off equals an independent BFS; invalid input gives clear errors.
- `MumbaiServiceTest` (15) and `ApiIntegrationTest` (5): route validity on the directed graph, closures, DSU vs BFS, critical links, search, HTTP errors, CORS, size limits.
- `POST /api/selfcheck` reruns seeded property checks on the deployed server.

## 9. Benchmarks (summary; full tables in benchmark-results.md)

The Fibonacci heap returns identical distances but is about 2× slower than PriorityQueue (Mumbai graph: 12.2 ms vs 5.4 ms; grids up to 600×600).
Ford-Fulkerson (DFS) is often faster than Edmonds-Karp on these graphs; Edmonds-Karp has the better worst-case bound.
Timings in the UI are single runs and include JIT warm-up on the first request.

## 10. Limitations

Arterial roads only; OSM is a community map, not ground truth; no live traffic or measured capacity; strips are a simple bucketing;
the scoring weights are a transparent judgement, not a trained model; bounded areas only (≤ 6,000 segments per snapshot).
