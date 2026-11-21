# Demo script (about 7 minutes)

Open https://nexus-resilience.onrender.com about a minute before the demo (free hosting wakes slowly).

1. **Overview (45 s).** Problem: software trusts its road data. Point at the data classes and the "Live traffic unavailable" warning.
   Say: "Everything real is OpenStreetMap. Estimates and samples are labelled."
2. **Integrity Check (3 min).** Area Dadar-Mahim-Bandra. Press **Compare snapshots**.
   - Before/after table: baseline 2025-11-20 vs updated 2026-11-20, components, probe route length, disjoint routes, SHA-256 hashes.
   - Classification: 497 real OSM changes, 9 suspicious, **0 verified malicious**. Say why it is always 0.
   - Click the top change and read its score breakdown. Open its OSM history link. Mark one change **reviewed: legitimate** and show it leave the top-k, the red-black index and the strip risk.
   - Press **Load demo scenario S1** (remove a road on the probe route, flip a one-way rule, add an unrecorded connection).
     Show the purple SIMULATED_TAMPERING rows, the longer probe route, the changed updated hash and the unchanged baseline hash.
   - Show the Quickselect top-k ("matches full sort"), Bloom screening (false positives caught exactly) and the algorithm table.
   - Press **Reset**.
3. **Find Route (45 s).** Type "Senapati Bapat Marg" and a second road, press Find Route, then click a road on the route to close it
   and route again (or get "Unreachable"). Show the Fibonacci heap vs PriorityQueue times and say honestly that the PriorityQueue is faster.
4. **Critical Links (45 s).** Default structural model: max flow = number of road-disjoint routes = min-cut size.
5. **Algorithm Lab (1 min).** Bloom tab with an id that was never inserted; Benchmarks; **Self-check** (all checks pass on the live server).
6. **Close (15 s).** "It does not detect attacks. It shows what changed, what that breaks, and what to check first, with every number traceable."
