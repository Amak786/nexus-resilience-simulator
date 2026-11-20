# 8-minute demo script

Open the deployed URL a minute early (free hosting sleeps).

| Time | Screen | Do | Say |
|---|---|---|---|
| 0:00-1:00 | Overview | Point at the map, graph size and the data-source list. | "This is Mumbai's real arterial road network from OpenStreetMap: 12,907 intersections and 18,169 segments. Each source is labelled: static map data, the latest available notes, derived travel times, a modelled capacity proxy, and live traffic marked NOT AVAILABLE, because we found no verified free feed." |
| 1:00-2:30 | Network Explorer | Click Dadar, then Bandra; Find route. Click a road. | "Dijkstra runs twice, with my Fibonacci heap and with Java's PriorityQueue, and they agree. Times are free-flow estimates. This road has no maxspeed tag, so the class default is used, and the UI says so." |
| 2:30-4:30 | Critical Links | Area Dadar-Mahim-Bandra; click source and sink; Run. Remove a cut road; run again. | "Max flow on the capacity proxy. Edmonds-Karp and Ford-Fulkerson reach the same value. The red roads are the minimum cut, and their capacities sum exactly to the max flow: that's the certificate. Removing one shows the next bottleneck. This is a model property, not a disaster prediction." |
| 4:30-6:00 | Network Health | Analyse connectivity; remove a road. Query notes for 2023-today. | "DSU components are rebuilt after a removal, because DSU can't delete. Notes are real map-issue reports, not incidents: the red-black tree orders them by time, the segment tree gives range totals and the busiest day, and Quickselect gives the P50/P90 time to close, checked against sorting." Optionally press **Import latest notes** to show the Bloom-filter de-duplication. |
| 6:00-7:00 | Multi-stop Planner | Click 5 stops; Plan tour. | "Road distances come from Dijkstra. The planning matrix uses the max of both directions, so it's a metric. MST + preorder is ≤ 2 × optimal; we show the MST lower bound, and the exact optimum for small cases." |
| 7:00-8:00 | Algorithm Lab | Fibonacci heap tab (run the default); Benchmarks → Fibonacci heap vs PriorityQueue; Self-check. | "Honest result: the Fibonacci heap has better amortized bounds but is about 2× slower here because of constant factors. 76 unit tests run in CI; the self-check re-runs seeded properties live." |

If the examiner changes inputs: every screen recomputes on the server. The Lab accepts custom graphs, operation lists and matrices.
