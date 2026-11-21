# Proposal: Nexus Resilience - Silent Shield

## Problem statement

Routing, delivery and emergency-response software depends on a road-network dataset that is updated regularly. A change to that
dataset - a legitimate map edit, an honest mistake, or deliberate tampering with the data file (a data-integrity attack) - can
silently remove a road, add a connection that does not exist, or reverse a one-way rule. Users notice only when routes go wrong.

Most map applications answer "what is the route?". Silent Shield answers a different question: **"what changed in the dataset
I depend on, what does each change break or create, and which changes should a person check first?"**

## Objectives

1. Load a trusted baseline and an updated snapshot of Mumbai's real road network (OpenStreetMap, 2025-11-20 and 2026-11-20).
2. Compare them road by road and list removed, added and modified roads.
3. Let the user apply clearly labelled demonstration modifications (remove a road, flip a one-way rule, add an unrecorded connection).
4. Recompute connectivity (DSU), a probe route (Dijkstra with a Fibonacci heap and a PriorityQueue) and road-disjoint routes (max flow / min cut).
5. Show a concise before-and-after table, including SHA-256 hashes of the data.
6. Rank changes with a documented points rule (Quickselect top-k, red-black alert index, segment-tree risk strips, Bloom-filter screening).
7. Show, for every algorithm, its input, output, complexity and limitations.
8. Provide reproducible tests, a fixed demonstration scenario (S1) and a reset button.

## Scope and honesty rules

- Real data only for anything called real. Every displayed item carries a class: A real mapped, B verified observation,
  C derived result, D estimate or assumption, E sample or demonstration.
- Synthetic modifications are always labelled SIMULATED_TAMPERING. No change is ever labelled malicious automatically.
- Not a penetration-testing tool, not a live threat detector, and not a traffic or flood simulator. There is no live traffic.

## Algorithms (all hand-written in Java)

Bloom filter, Disjoint Set Union, Dijkstra with a Fibonacci heap (compared with `java.util.PriorityQueue`), Ford-Fulkerson,
Edmonds-Karp, minimum cut, randomized Quickselect, red-black tree, segment tree and metric-TSP approximation (MST preorder).
The reasons for each choice are in the technical report.

## Deliverables

A deployed web application (Render), source code with 94 automated tests, a benchmark report, a technical report,
a demo script and viva preparation.
