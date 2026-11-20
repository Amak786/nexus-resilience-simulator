# Project proposal: NEXUS, Mumbai Network Intelligence

**Course:** Advanced Algorithms (CIA 3), Semester V, 2026-27 · **Team:** _names and IDs_ · **Status:** for faculty approval

**Problem.** Questions about Mumbai's road network (connectivity, bottlenecks between areas, routes, inspection order) are
usually answered informally. We build an explainable prototype that answers them on real OpenStreetMap data and is honest
about what the data can and cannot say.

**Users.** Students, civic researchers and planners exploring network structure (not live operations).

**Features.** Real Mumbai map with provenance; route finder; critical-link (max-flow/min-cut) analysis in a selected area;
connected components before/after removing roads; ordered, de-duplicated, statistically summarised OSM map-note records;
multi-stop inspection planner; Algorithm Lab with benchmarks.

**Techniques.** Dijkstra with a Fibonacci heap (Unit 2); Edmonds-Karp/Ford-Fulkerson/min cut (Unit 5); DSU (Unit 2);
red-black tree and segment tree (Unit 3); Bloom filter and randomized Quickselect (Unit 6, CO5); metric-TSP
2-approximation (Unit 5). Each serves a workflow above.

**Data.** OpenStreetMap via Overpass (roads) and the Notes API (map issues), ODbL. Derived travel times and modelled
capacities are labelled. No live traffic source is available; this is stated in the app.

**Success criteria.** All flows certified by equal min cuts; both heaps give identical route distances; ≥ 5 tests per
algorithm, all passing; benchmarks at three sizes with baselines; invalid input rejected with field-level errors; a public
deployment showing the real map and provenance.
