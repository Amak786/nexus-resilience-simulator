# Data flow: one real connection, end to end

1. **Observation (sensor).** At 21:24:10 the PowerShell sensor reads `Get-NetTCPConnection`, keeps Established/SynSent/CloseWait rows
   with a non-loopback remote address, adds the process name (opt-in) and stamps `observedAt` in UTC.
2. **Upload.** It POSTs `{sensorId, batchId (UUID), source, agentVersion, connections[]}` with `Authorization: Bearer <ingest token>`.
   On a timeout it retries with the same batchId.
3. **Validation (EventStore).** Token checked in constant time. Each row: protocol TCP/UDP, IP literals only, ports 0-65535, safe
   process name, `observedAt` not in the future (> 5 min) and not older than retention, no loopback. Rejected rows are counted with reasons.
4. **Deduplication and aggregation.** A repeated batchId returns `duplicateBatch: true` and changes nothing. Rows with the same
   (sensor, protocol, local, local port, remote, remote port) become one **flow** with firstSeen, lastSeen, observations, lastIngested.
   Per-minute counts are keyed by observation time; ingestion time is stored separately.
5. **Push.** The controller broadcasts an SSE `ingest` event; open browsers refetch the overview, graph and findings.
6. **Threat screening.** For each public remote address: Bloom filter over the Feodo list (maybe → exact map check), then a red-black
   floor query over Spamhaus DROP ranges.
7. **Rules and scoring.** Matches and heuristics become findings with evidence, known, uncertain and next steps; Quickselect selects the
   top-k scores, a red-black tree orders them.
8. **Graph.** Sensor → process → remote address (→ ASN after a lookup). DSU components, Tarjan articulation points and bridges,
   Brandes centrality; on request BFS, Dijkstra (Fibonacci heap vs PriorityQueue) and Edmonds-Karp / Ford-Fulkerson with the min cut.
9. **UI.** The analyst sees the finding, the evidence and its sources and times, and records a decision (acknowledge, dismiss, or confirm
   an incident — a human decision).
10. **AI (optional).** The analyst asks a question; Claude calls tools such as `list_findings` and `investigate_ip`, receives the computed
    JSON and writes an explanation separated into observed data, algorithm results and interpretation. Every tool result is shown as
    evidence. Without a key, the same tools run and their output is shown without an AI text.
11. **Retention.** Flows not seen for 24 h are deleted on the next access.
