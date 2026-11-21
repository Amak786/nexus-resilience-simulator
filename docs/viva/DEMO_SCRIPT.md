# Five-minute demo (real data only)

Before: start the server with both tokens (README), open the site, sign in on **Data Sources**, and start the Windows sensor on your own
laptop: `.\sensor\trace-sensor.ps1 -Server <url> -SensorId demo-laptop -IncludeProcess`.

1. **Data Sources (45 s).** Feodo Tracker and Spamhaus DROP: status OK, last download time, record counts. The sensor shows LIVE with its
   last batch time. Point out: "If the sensor stops, this becomes DELAYED, then DISCONNECTED — nothing is faked."
2. **Overview (60 s).** Remote addresses observed, open findings, new remotes in the last 15 minutes, the observations-per-minute line
   (segment tree), recent connections. Open a browser tab on the laptop; within ~15 s the numbers update through the event stream.
3. **Investigations (75 s).** Expand a finding: rule, severity vs confidence, evidence with sources and times, known / uncertain, next steps,
   score breakdown. Mark one "acknowledged". Then investigate a currently listed Feodo address (copy one from
   https://feodotracker.abuse.ch/browse/): list match + ASN from RIPEstat, labelled "evidence, not proof". Try `example.com` to show
   that host names are refused.
4. **Network (60 s).** The graph of sensor → processes → addresses. Click a process and an address: BFS hops, Dijkstra (Fibonacci heap vs
   PriorityQueue times, same result), relationship-disjoint paths (Edmonds-Karp = Ford-Fulkerson = min cut). Mention articulation points
   and centrality as structure, not risk.
5. **AI Analyst (30 s).** Ask "Which findings need attention first, and why?". Show the evidence panel with each tool result. Without a key
   it shows "deterministic evidence (no AI)" — say that the app is fully usable without AI.
6. **Algorithm Lab (30 s).** A* vs Dijkstra on the OpenStreetMap road graph (same distance, fewer settled nodes), Bloom filter tab, and
   **Self-check** running seeded property tests on the live server.
