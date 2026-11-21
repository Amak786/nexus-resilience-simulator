# Architecture

```
 Data providers                       Ingestion (scheduled, separate from user requests)
 ───────────────                      ─────────────────────────────────────────────────
 Open-Meteo (weather, live)  ──15 min──►  LiveDataService.refreshWeather()  ─┐ timeouts (10 s connect, 20-90 s request)
 Overpass (OSM road edits)   ──60 min──►  LiveDataService.refreshRoadEdits() ├ 2 retries on 429/5xx/timeout (2 s, 4 s)
 OSM Notes API               ──60 min──►  EventService.refresh()             ┘ validation → reject, never store bad data
 Overpass (snapshots, offline) ────────►  data-pipeline/*.mjs → roads.json, roads-baseline.json (committed)
                                                     │
                                                     ▼
                               In-memory cache: last validated value + observedAt + lastSuccessAt + error
                                                     │
                     ┌───────────────────────────────┼────────────────────────────────┐
                     ▼                               ▼                                ▼
          Graph algorithms (Java)          Integrity / change detection        Live status (/api/live/status)
          CSR RoadGraph, DSU, Dijkstra     baseline vs current, scoring,        freshness: OK / STALE / ERROR /
          (Fibonacci heap + PQ), max flow  Bloom, Quickselect, RB tree,         NOT_YET_FETCHED / NOT_AVAILABLE
          / min cut, metric TSP            segment tree
                     └───────────────────────────────┬────────────────────────────────┘
                                                     ▼
                                   REST API (Spring Boot, validated DTOs, JSON errors,
                                   CORS allow-list, request-size limit, /api/health)
                                     │                                      │
                                     ▼                                      ▼
                        React + Leaflet UI (OSM tiles)          AI analyst (/api/analyst/ask)
                        map, freshness pills, metrics,          Claude via the Anthropic Java SDK, key only in
                        changes, analyst, sources               ANTHROPIC_API_KEY on the server; 6 tools that call
                                                                the layers above; rate limits; evidence-only fallback
```

## Components (for the viva)

| Component | Purpose | Why this design |
| --- | --- | --- |
| `data-pipeline/build-mumbai-snapshot.mjs` | Builds the road graph from Overpass, current and 2025-11-20 (attic) | Heavy downloads happen offline once; the server starts fast and is reproducible |
| `LiveDataService` | Polls sources on a schedule, validates, caches | Page loads never wait for a slow provider; failures only change a status |
| `MumbaiNetwork` / `RoadGraph` | Compressed-sparse-row graph of 12,907 junctions and 18,169 segments | O(1) neighbour scans, little memory on a free 512 MB instance |
| `MumbaiService`, `IntegrityService`, `TourService` | Routes, components, critical links, snapshot diff, inspection tour | All business logic in Java, testable without HTTP |
| `AnalystTools` | Six functions the AI may call; each returns small JSON with a source label | The model never sees the dataset and cannot get a number from anywhere else |
| `AnalystService` | Manual Claude tool-use loop (at most 6 rounds), rate limits, error handling | Simple to explain; degrades to "evidence only" with no key or on provider errors |
| Front end | Displays results, never computes them | One source of truth; easy to test the back end |

## Data timestamps

Each live source records `observedAt` (time of the observation according to the provider: Open-Meteo `current.time`, Overpass
`timestamp_osm_base`) separately from `lastSuccessAt` (when this server ingested it). A source becomes STALE if the last success is older
than three update periods, or if the latest attempt failed (the last good value is kept and still shown with its age).

## Failure behaviour

| Failure | What the user sees |
| --- | --- |
| Overpass busy (429/504) | Retried twice; then status ERROR or STALE with the error text; map and analyses keep working on the snapshot |
| Weather response invalid or not for Mumbai | Rejected by validation; no weather is shown rather than a wrong one |
| No `ANTHROPIC_API_KEY` | Analyst shows "evidence-only mode" and returns the tool results without an AI answer |
| Claude API error or rate limit | Same evidence-only answer with the reason |
| Too many questions | HTTP 429 (8 per client per 10 minutes, 300 per day by default) |

## Persistence

None beyond the committed snapshots. Live values are kept in memory because they are refreshed every 15-60 minutes and would be stale
after a restart anyway. A database would be justified only for history (e.g. trends of edits), which is a documented extension.
