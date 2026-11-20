# API reference

Interactive docs: `/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`). All bodies are JSON. Errors always look like
`{"error": "VALIDATION_FAILED", "message": "...", "issues": [{"field": "...", "message": "..."}]}`:
400 validation/malformed JSON/unknown field/wrong type, 404 unknown endpoint, 405 wrong method, 413 body too large,
500 unexpected (logged; no stack trace returned).

## Mumbai network (`/api/mumbai`)

| Method | Path | Input | Output |
|---|---|---|---|
| GET | `/provenance` | - | data sources with category and freshness, graph statistics, modelling assumptions |
| GET | `/network?south&west&north&east` | optional bbox (default: study area) | segments with geometry, length, travel-time estimate, capacity proxy, OSM tags |
| POST | `/route` | `{fromLat, fromLon, toLat, toLon, metric: TIME\|DISTANCE}` | snapped nodes, route geometry, steps, Fibonacci-heap and PriorityQueue statistics |
| POST | `/components` | `{area, removedSegmentIds}` | DSU components before/after, segment → component |
| POST | `/critical-links` | `{area, sourceLat, sourceLon, sinkLat, sinkLon, removedSegmentIds}` | max flow, min-cut segments, per-segment flow, EK vs FF statistics, checks |
| GET | `/events?from&to&status&limit` | ISO dates, all/open/closed | notes in creation order (red-black tree), count in range, index metrics |
| GET | `/events/stats?from&to` | ISO dates | segment-tree totals, busiest/quietest day, monthly counts, Quickselect percentiles |
| POST | `/events/refresh` | - | live import from the OSM Notes API with Bloom pre-check (at most every 10 minutes) |
| POST | `/tour` | `{stops: [{label, lat, lon}], startIndex}` | planning tour, executable legs, NN baseline, MST bound, exact optimum (≤ 10 stops) |

## Algorithm Lab and evidence (`/api`)

`GET /health`, `GET /algorithms`, `POST /lab/{flow|dsu|rbtree|fibheap|quickselect|segment-tree|tsp}`,
`GET /benchmarks`, `POST /benchmarks/{dijkstra|flow|dsu|rbtree|bloom|segtree|quickselect|tsp}`, `POST /selfcheck`.

## Example

```bash
curl -X POST https://nexus-resilience.onrender.com/api/mumbai/route -H 'Content-Type: application/json' \
  -d '{"fromLat":19.018,"fromLon":72.847,"toLat":19.056,"toLon":72.845,"metric":"DISTANCE"}'
```
