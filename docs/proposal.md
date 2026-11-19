# Project proposal: NEXUS, Infrastructure Failure & Resilience Simulator

**Course:** Advanced Algorithms (CIA 3), Semester V, B.Tech CS AI & ML, 2026-27
**Team:** _fill in names and IDs (2-3 students)_
**Status:** proposal for faculty approval (not yet approved)

## Real-world problem

During floods and other disasters, city relief operations depend on a fragile network of roads, depots and shelters. When a
bridge or depot fails, planners must quickly know how much relief can still be delivered, which districts are cut off,
when shortages will peak and which repair restores the most capacity. These decisions are currently made by hand and are
hard to verify.

## Target users

District disaster-management control rooms and municipal logistics planners (simulated; all data is synthetic).

## Proposed features

1. **Failure Lab**: select road/facility failures and see throughput loss, isolated zones, worst hours, impact percentile
   against random failures, and ranked recovery actions.
2. Flow optimizer with min-cut bottlenecks and a step-by-step replay of augmenting paths.
3. Connectivity analysis before and after failures, including critical single roads.
4. Incident priority queue that updates as incidents are added, updated and resolved.
5. Duplicate incident screening with a probabilistic pre-check and exact verification.
6. Delivery tour planning with a proven approximation bound.
7. Algorithm Lab, live self-check and benchmarks.

## Core techniques and why they fit

| Technique | Syllabus | Why it is appropriate |
|---|---|---|
| Edmonds-Karp max flow + min cut (Ford-Fulkerson as comparison) | Unit 5 | Relief delivery limited by road and depot capacities is exactly a max-flow problem; the min cut names the bottleneck. |
| Disjoint-set union | Unit 2 | Near-constant-time connectivity for "which districts are still linked to a depot". |
| Red-black tree | Unit 3 | Ordered incident queue with guaranteed O(log n) updates as priorities change. |
| Segment tree | Unit 3 | Range sums and maxima over the 72-hour demand/shortfall series, with range updates for demand surges. |
| Bloom filter (CO5) | Unit 6 | Memory-efficient duplicate pre-check; possible hits verified exactly. |
| Randomized Quickselect (CO5) | Units 1/6 | Percentiles of failure impact in expected linear time. |
| Metric TSP 2-approximation | Unit 5 | Vehicle tour with a provable bound on a metric distance matrix. |

At least two techniques come from Units 4-6 (network flow, TSP approximation, Bloom filter). CO5 is covered by the Bloom
filter and randomized Quickselect.

## Data source

A synthetic, clearly labelled dataset of a fictional city (Varunapuri): 4 depots, 8 junctions, 10 demand zones, 29 roads,
12 incidents and a 72-hour demand forecast. Users can also upload JSON/CSV datasets, which are strictly validated.

## Technology

Java 21 + Spring Boot REST API (all algorithms hand-written), React + TypeScript + Vite front end, JUnit 5 tests, Docker,
public deployment on Render.

## Measurable success criteria

* Every max-flow result is certified by an equal min-cut capacity (checked at runtime and in tests).
* At least five documented correctness tests per core algorithm, including boundary and invalid-input cases; all pass.
* Benchmarks at three increasing input sizes with a named baseline for every algorithm.
* A full Failure Lab analysis of the demo city returns in under 2 seconds on the deployed service.
* Invalid input (empty data, negative capacities, duplicate ids, unknown endpoints, non-metric matrices) is rejected with a
  field-level error, never silently corrected.
