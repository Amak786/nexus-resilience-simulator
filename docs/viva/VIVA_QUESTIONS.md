# Viva questions (43)

**1. What problem does TRACE solve?** When a machine makes an unfamiliar connection, TRACE shows what is known about the remote address,
which public evidence exists, how it relates to other observed activity, and what to check next — with sources, times and uncertainty.

**2. How is it different from a conventional network-monitoring tool?** Monitoring tools count traffic and raise alerts. TRACE is an
investigation workspace: each finding carries its evidence, the rule that produced it, what is known and unknown, and graph context.

**3. How does the application obtain real data?** Sensors on authorised machines upload connection metadata with a token; threat lists are
downloaded hourly from abuse.ch and Spamhaus; lookups query RIPEstat (and AbuseIPDB if configured).

**4. What does real-time mean here?** Sensors push every 15 seconds and the server pushes an SSE event after each batch, so observations
appear within seconds. Threat lists are at most about an hour old. Both freshness values are shown.

**5. Why not capture packets?** Payloads are unnecessary for this question, raise privacy risk and need privileged capture setup.
Metadata is enough to relate endpoints and check reputation.

**6. Why use graphs?** Security questions are relational: which processes reach the same address, which entity connects groups, how many
independent routes link two entities. Graph algorithms answer these directly.

**7. Why DSU instead of BFS or DFS for components?** DSU builds components from an edge list in near-linear time without adjacency lists,
and is ideal when edges are added. BFS gives the same answer and is used as a test oracle.

**8. When can't you use DSU?** It cannot delete edges and ignores direction. For time windows TRACE rebuilds a DSU per window; for paths it
uses BFS/Dijkstra.

**9. Why Dijkstra rather than BFS?** BFS minimises hops. Dijkstra minimises a weighted cost; TRACE uses weight 1/observations so the
path follows the most frequently observed relationships. Both are shown.

**10. Why a Fibonacci heap?** It gives O(1) amortized decrease-key and O(E + V log V) Dijkstra. In practice it measured about 2x slower than
Java's PriorityQueue on our graphs (constant factors, memory locality); both run and must agree.

**11. Where is A* used and why not on the network graph?** A* needs an admissible heuristic. Network entities have no coordinates, so there
is none. On the OpenStreetMap road graph, straight-line distance never overestimates, so A* returns the Dijkstra distance while settling
fewer nodes (tested).

**12. What is the difference between Ford-Fulkerson and Edmonds-Karp?** Both augment along residual paths. Ford-Fulkerson uses any path
(DFS here) and runs in O(E·f); Edmonds-Karp uses the shortest path (BFS), giving O(VE²) regardless of capacities.

**13. What does the min cut tell you?** The smallest set of observed relationships whose removal separates two entities; with unit
capacities its size equals the number of relationship-disjoint paths (max-flow min-cut theorem). It is a structural fact, not a vulnerability.

**14. What is a Bloom filter false positive?** A never-inserted address whose k bit positions were all set by other addresses. The filter
never gives false negatives, so every "maybe" is confirmed against the exact map; false positives are counted and shown.

**15. Why use a Bloom filter at all for a list of a few entries?** It is the screening structure that scales to large blocklists in little
memory; TRACE shows its theoretical and measured behaviour honestly even when the current list is small.

**16. How does the red-black tree answer "is this IP in a DROP range"?** Ranges are keyed by start address; a floor query finds the largest
start ≤ the IP in O(log n); the IP is inside if it is ≤ that range's end. Tested at both boundaries of each range.

**17. How does centrality help?** Betweenness shows entities that sit on many shortest paths between others, e.g. a process that links
otherwise separate groups of destinations. It points to where to look, not to wrongdoing.

**18. What are articulation points and bridges?** Nodes or edges whose removal disconnects the observed graph, found by one DFS with
low-link values. In a star around one sensor many hubs are articulation points; TRACE says so rather than alarming.

**19. Why randomized Quickselect?** Top-k needs only the k-th largest score: expected O(n) instead of O(n log n) for sorting. Randomised
pivots avoid adversarial worst cases; results are checked against a sort.

**20. What does the segment tree do?** Stores observations per minute and answers range sums and peak minutes in O(log n), checked
against a linear scan.

**21. How is a finding scored?** Severity base (70/40/15) + confidence (+20/+10/0) + volume (≤ 10) + multiple sensors (+5). Each finding
lists its breakdown and a test checks it.

**22. What is the difference between severity and confidence?** Severity is how bad it would be if true; confidence is how strong the
evidence is. A list match is high severity but medium confidence.

**23. How do you distinguish suspicion from proof?** Categories: observation, heuristic, threat-intel match. "Confirmed incident" can only be
set by an analyst after checking the host. TRACE never labels a host compromised.

**24. What does the AI contribute?** It turns several tool results into a short explanation and suggests read-only next steps, separating
observed data, algorithm results and interpretation.

**25. Can the AI make a false claim?** It can misphrase, so it can only use six tools that return computed data, is instructed not to
claim compromise, and every tool result is shown next to its answer for checking. Without a key the app returns the evidence alone.

**26. Why not let the AI read all the data?** Cost, privacy and accuracy: it gets only the small, relevant results the tools return.

**27. What happens if the sensor disconnects?** Its status changes LIVE → DELAYED (90 s) → DISCONNECTED (15 min), the mode becomes
historical, and no new data is invented.

**28. What happens if a threat feed fails?** Two retries with backoff, then status ERROR with the error text; the previous list stays in
use and its age is shown as STALE after three missed refreshes.

**29. How are duplicates handled?** Every batch has an id; a re-sent batch is ignored. Identical rows in one batch are dropped. Repeated
polls of the same connection become one flow with an observation count.

**30. Why store observation time and ingestion time separately?** Sensors can be delayed or their clocks wrong; analysis uses when it was
observed, freshness uses when it arrived. Future timestamps beyond 5 minutes are rejected.

**31. How do you prevent SSRF?** Outbound calls go to fixed hosts; the only variable is a validated IP literal; host names are not
resolved; private and metadata addresses (169.254.169.254) are never sent out.

**32. How is the ingestion endpoint protected?** Bearer token compared in constant time; if not configured it is disabled, not open; body
size and row limits apply.

**33. Does an IP address identify a person?** No. Addresses are shared by NAT, cloud and CDNs. TRACE never uses country as a suspicion signal.

**34. Why in-memory storage?** One lab's 24 hours of flows fit in memory and keep hosting free; a database is the documented next step.

**35. How do you prove the program works?** 100 automated tests: algorithms vs independent oracles (BFS, brute force, closed forms,
TreeMap, sorting, Held-Karp), ingestion, feeds with simulated failures, auth over HTTP, AI fallback; plus a live self-check endpoint.

**36. What did you measure?** Benchmarks for Dijkstra (Fibonacci vs PriorityQueue), flows, DSU, Quickselect, segment tree, Bloom filter,
red-black tree and TSP, with warm-up and repetitions; the Fibonacci heap result is reported honestly as slower.

**37. Why Java and Spring Boot?** Course requirement for the algorithms; Spring provides REST, scheduling and SSE so the effort goes into
the algorithms and the investigation logic.

**38. What are the limitations?** TCP metadata only, polling may miss short connections, lists are incomplete, heuristics are simple,
memory storage, single tenant. See LIMITATIONS_AND_FUTURE_WORK.md.

**39. Why is this useful in India?** It runs free on a laptop or a free host, needs no paid feeds, works on Windows and Linux lab machines,
and explains findings in plain language for small IT teams and colleges.

**40. What is your own contribution?** The ingestion model, rule engine and scoring, graph model, all assessed algorithm implementations,
sensors, the evidence-first UI and the grounding design of the AI analyst — with the tests that check them.

**41. How does a domain lookup work without risking SSRF?** The server only asks DNS for the name (with a 4-second timeout) and checks each
returned address like an IP lookup. It never opens a connection to the domain. URLs, ports and paths are rejected by validation.

**42. How does TRACE use Zeek?** `sensor/trace-zeek.py` follows Zeek's `conn.log` (TSV or JSON) on a monitoring host and forwards only
time, protocol, addresses, ports and connection state to the same authenticated ingestion API used by the other sensors.

**43. What happens to data when the server restarts?** By default observations are in memory and are lost. With `TRACE_DATA_DIR` the store is
written to disk every minute (temporary file + atomic move) and reloaded at start; on Render's free plan the disk itself is ephemeral.
