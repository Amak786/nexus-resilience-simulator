# Nexus Resilience: Silent Shield

Road-network **data-integrity and resilience analysis** on Mumbai's real OpenStreetMap road network.
Advanced Algorithms (CIA 3) project. Java 21 / Spring Boot back end (all assessed algorithms hand-written), React + TypeScript front end.

Live: https://nexus-resilience.onrender.com (free hosting: the first request after idle can take 30-60 s).

## The problem in one paragraph

Navigation, logistics and emergency-planning software trusts its road dataset. If that dataset changes - through a normal map edit,
a mistake, or someone tampering with the data file - routes and connectivity silently change too. Silent Shield compares a
**trusted baseline** snapshot with an **updated** snapshot, lists every changed road, measures what each change does to
connectivity and to a probe route, and ranks the changes for a human reviewer with a documented scoring rule.
It never claims that a change is an attack: an anomaly is only "worth checking".

## What is real and what is not

| Item | Class | Source |
| --- | --- | --- |
| Updated road snapshot: 12,907 junctions, 18,169 segments, 12,397 ways (motorway to tertiary) | A · real mapped | OpenStreetMap via Overpass, data timestamp 2026-11-20 |
| Trusted baseline: 9,837 junctions, 13,842 segments, 10,184 ways | A · real mapped | Same Overpass query evaluated on the OSM history as of 2025-11-20 ("attic" query) |
| Detected changes (e.g. 497 in Dadar-Mahim-Bandra) | C · derived | Way-by-way comparison of the two snapshots |
| OSM map notes (2,567; 958 created since 2023) | B · observation | OSM Notes API. Community map-issue reports, **not** traffic incidents |
| Travel time (free-flow) | D · estimate | maxspeed tag (about 4 % of segments) or a class default speed |
| Lane capacity (veh/h) | D · estimate, opt-in | lanes tag (about 20 % of segments) or a class default. **Not** measured throughput |
| Demonstration tampering (scenario S1, user edits) | E · sample | Typed in by the user; always labelled SIMULATED_TAMPERING |
| Live traffic | not available | No free, terms-verified Mumbai feed. A licensed provider key would be needed |

Removed or relabelled during the audit: the old "3,200 veh/h max flow" (it came from the lane-capacity guess, and about 80 % of segments
have no lanes tag), and "958 incidents" (these are map notes, not incidents). Critical links now default to the structural model
(capacity 1 per segment, so max flow = number of road-disjoint routes).

## Change classification

| Status | Meaning |
| --- | --- |
| ROUTINE_UNREVIEWED | Real OSM edit, score below 5 |
| SUSPICIOUS_NEEDS_REVIEW | Real OSM edit, score 5 or more. An anomaly to check, not proof of an attack |
| LEGITIMATE_REVIEWED | The analyst checked it (for example its public OSM history) and marked it legitimate |
| SIMULATED_TAMPERING | A demonstration modification with no public edit record |
| VERIFIED_MALICIOUS | Never assigned automatically: needs external evidence the tool does not have. Always 0 |

Scoring rule (integer points, shown per change): road class (motorway/trunk +3, primary +2, secondary/links +1),
junctions cut off or joined by the change (DSU: 1-4 → +2, 5-19 → +4, 20+ → +6), touches the probe route (+3),
one-way rule changed (+2 on primary or higher, else +1), no public edit record (+3).

## Algorithms and where they are used

| Algorithm | Role in Silent Shield | Complexity |
| --- | --- | --- |
| Bloom filter | Fingerprints of trusted baseline roads; screens every updated road; "possibly known" answers are confirmed by an exact HashSet | O(k) per operation |
| Disjoint Set Union | Components before and after; junctions cut off by each change (rebuilt without the way, because DSU cannot delete) | O(m α(n)) per rebuild |
| Dijkstra + Fibonacci heap (vs PriorityQueue) | Probe route before and after; route search | O(m + n log n); binary heap O(m log n) |
| Ford-Fulkerson and Edmonds-Karp | Number of road-disjoint routes between the probe points (unit capacities) | FF O(E·f), EK O(V E²) |
| Max flow / min cut | Fewest segments whose loss separates the probe points | O(V+E) after max flow |
| Randomized Quickselect | k-th highest score gives the top-k changes without sorting all of them; checked against a sort | expected O(n) |
| Red-black tree | Alert index keyed by (score desc, change id); reviewed changes are deleted | O(log n) |
| Segment tree | Risk per west-to-east strip: range sum and max, point update when a change is reviewed | O(log n) |
| Metric-TSP (MST preorder) | Inspection order for the top flagged changes over shortest-path road distances | ≤ 2 × optimal |

Measured: the Fibonacci heap gives identical distances but is about 2× slower than `java.util.PriorityQueue`
(for example 12.2 ms vs 5.4 ms on the Mumbai graph). See [docs/benchmark-results.md](docs/benchmark-results.md).

## Screens

Overview · **Integrity Check** (main workflow) · Explore Roads · Find Route · Network Connectivity · Critical Links · Multi-Stop Planner · Algorithm Lab.

## Run locally

Requirements: JDK 21+, Maven 3.9+, Node 20+.

```bash
cd backend && mvn verify          # 94 tests, then builds target/nexus-api.jar
java -jar target/nexus-api.jar    # API on http://localhost:8080
```

```bash
cd frontend && npm ci && npm run dev   # UI on http://localhost:5173 (proxies /api to 8080; set API_PROXY to change)
```

Or build one container: `docker build -t nexus . && docker run -p 8080:8080 nexus`.

Rebuild the data (optional; the snapshots are committed):

```bash
node data-pipeline/build-mumbai-snapshot.mjs            # downloads current roads, the 2025-11-20 baseline and notes
node data-pipeline/build-mumbai-snapshot.mjs --offline  # rebuild from data-pipeline/raw/
```

## Main API

- `POST /api/integrity/analyse`: `{area, fromLat, fromLon, toLat, toLon, demoModifications?, reviewedLegitimate?, topK?, stripFrom?, stripTo?}`
- `GET /api/integrity/scenario?south&west&north&east&fromLat&fromLon&toLat&toLon`: reproducible demo scenario S1
- `POST /api/mumbai/route`, `/components`, `/critical-links`, `/tour`; `GET /api/mumbai/search`, `/segments/{id}`, `/provenance`
- `POST /api/lab/{flow,dsu,rbtree,fibheap,bloom,quickselect,segment-tree,tsp}`, `POST /api/benchmarks/{suite}`, `POST /api/selfcheck`
- OpenAPI: `/swagger-ui.html`. More: [docs/api.md](docs/api.md)

Example:

```bash
curl -X POST https://nexus-resilience.onrender.com/api/integrity/analyse -H "Content-Type: application/json" -d "{\"area\":{\"south\":19.01,\"west\":72.83,\"north\":19.06,\"east\":72.87},\"fromLat\":19.018,\"fromLon\":72.847,\"toLat\":19.056,\"toLon\":72.845}"
```

## Limitations

- Only arterial roads (motorway to tertiary). Residential streets can provide detours that are not modelled.
- OSM records what mappers entered, not what is on the ground today. A real edit can be wrong, and a "suspicious" one can be correct.
- No live traffic and no measured capacities. Times and lane capacities are labelled estimates.
- Integrity analysis runs on bounded areas (at most 6,000 segments per snapshot) to keep the algorithms fast and explainable.
- This is a student prototype, not an official or production security tool.

## Documentation

[Proposal](docs/proposal.md) · [Technical report](docs/technical-report.md) · [Demo script](docs/demo-script.md) ·
[Viva questions](docs/viva-questions.md) · [API](docs/api.md) · [Deployment](docs/deployment.md) · [Benchmarks](docs/benchmark-results.md)

Map data © OpenStreetMap contributors, available under the Open Database Licence (ODbL).
