# Architecture

```
 Authorised machine                                   TRACE server (Spring Boot, one process)
 ┌───────────────────────┐   HTTPS POST + Bearer      ┌──────────────────────────────────────────────────────────────┐
 │ trace-sensor.ps1 / .sh │ ─────────────────────────▶ │ TraceController /api/ingest/connections (sensor token)        │
 │ every 15 s: TCP table  │  batch{sensorId,batchId,   │   → EventStore: validate, dedup batch id, aggregate flows,     │
 │ metadata only          │        rows[]}             │     per-minute counts, 24 h retention (in memory)              │
 └───────────────────────┘                            │   → SSE broadcast "ingest" to connected viewers                │
                                                      │                                                                │
 abuse.ch Feodo  ──hourly──▶ ThreatIntelService ──────│ Bloom filter + exact map (Feodo) · red-black ranges (DROP)     │
 Spamhaus DROP   ──hourly──▶ (timeouts, 2 retries,    │                                                                │
 RIPEstat, AbuseIPDB ─on demand, cached──▶ status)    │ AnalysisService: findings (rules + score), graph build,        │
                                                      │   DSU, DFS/Tarjan, Brandes, BFS, Dijkstra(Fib), EK/FF/min cut, │
                                                      │   Quickselect top-k, red-black index, segment tree time series │
                                                      │ AnalystService: Claude tool loop over AnalystTools (optional)  │
                                                      │ Lab: algorithm endpoints, benchmarks, self-check, A* on roads  │
                                                      └───────────────┬──────────────────────────────────────────────┘
                                                                      │ JSON (viewer token) + SSE (one-time ticket)
                                                                      ▼
                                                      React UI: Overview · Network · Investigations · Lab · AI · Sources
```

| Component | Responsibility | Why this way |
| --- | --- | --- |
| Sensor scripts | Read the OS connection table, send metadata batches | A hosted website cannot see a local network; an agent on the machine can, with the owner's permission |
| `EventStore` | Validation, deduplication, aggregation into flows, retention | Polling sees the same connection repeatedly; one flow with first/last seen and a count is the truthful model |
| `ThreatIntelService` | Scheduled list downloads, status, on-demand enrichment | Lists change slowly (hourly is enough and respects providers); lookups never block on downloads |
| `AnalysisService` | Rules, scoring, graph and algorithms | Pure Java, unit-testable without HTTP |
| `TraceController` | REST API, auth levels, SSE stream | One place to enforce tokens and limits |
| `AnalystService` / `AnalystTools` | Grounded AI explanations | Tools return computed results; the model cannot read raw data |
| React frontend | Display only | Logic stays testable in one place |

## Why one process and in-memory storage
The target is a single lab or small office: thousands of flows per day fit easily in memory, and a single free container keeps the cost at
zero. The `EventStore` interface is the seam where a database (for example PostgreSQL) would be added for longer history.

## Real-time
Sensors push every 15 s; after each accepted batch the server pushes an SSE event; pages refetch their (cheap) aggregates. Threat lists are
pulled hourly. "Real-time" therefore means seconds for observed connections and up to an hour for list updates — shown on Data Sources.
