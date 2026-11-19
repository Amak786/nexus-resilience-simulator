# API reference

Base URL: the deployed service (e.g. `https://<service>.onrender.com`) or `http://localhost:8080`.
Interactive documentation: `/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`).

All requests and responses are JSON. The API is **stateless**: endpoints that analyse the city take the full `dataset`
(the object returned by `GET /api/dataset/demo`) in the request body.

## Errors

Every error has the same shape and never contains a stack trace:

```json
{ "error": "VALIDATION_FAILED", "message": "edges[0].capacity: must be an integer between 0 and 1000000000",
  "issues": [ { "field": "edges[0].capacity", "message": "must be an integer between 0 and 1000000000" } ] }
```

| HTTP | `error` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | input violates a rule (field path in `issues`) |
| 400 | `MALFORMED_REQUEST` | invalid JSON, unknown field, wrong type (e.g. `"5"` for a number) |
| 400 | `INVALID_INPUT` | other invalid arguments |
| 404 | `NOT_FOUND` | unknown endpoint |
| 405 | `METHOD_NOT_ALLOWED` | e.g. GET on a POST endpoint |
| 413 | `PAYLOAD_TOO_LARGE` | body over `MAX_REQUEST_BYTES` (2 MiB) |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | missing `Content-Type: application/json` |
| 500 | `INTERNAL_ERROR` | unexpected server error (logged server-side) |

## Dataset schema

```json
{
  "meta": { "name": "...", "description": "...", "synthetic": true, "units": "...", "version": "..." },
  "nodes": [
    { "id": "D1", "name": "Central Relief Depot", "type": "DEPOT", "x": 15, "y": 15, "supply": 1200 },
    { "id": "J1", "name": "Clock Tower Circle", "type": "JUNCTION", "x": 14, "y": 20 },
    { "id": "Z1", "name": "Ward 3", "type": "DEMAND_ZONE", "x": 21, "y": 10, "demand": 420,
      "severity": 5, "vulnerablePercent": 38, "population": 18400 }
  ],
  "roads": [ { "id": "R1", "from": "D1", "to": "J1", "capacity": 500, "lengthKm": 5.2, "oneWay": false } ],
  "incidents": [ { "id": "INC-1001", "title": "...", "zoneId": "Z1", "severity": 5, "affectedPeople": 6200, "reportedHour": 4 } ],
  "demandTimeline": { "label": "...", "hourlyDemand": [111, 114, 109] }
}
```

Rules: ids are 1-32 characters from `A-Z a-z 0-9 - _` and unique; road endpoints must exist and differ; `capacity`, `supply`,
`demand` are whole numbers in [0, 10⁹]; `lengthKm` in (0, 1000]; `severity` 1-5; `vulnerablePercent` 0-100; only depots have
supply and only demand zones have demand; at least one depot; at most 500 nodes, 3,000 roads, 500 incidents; timeline
24-168 values.

## Endpoints

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/api/health` | - | `{status, service, version, java, time}` |
| GET | `/api/algorithms` | - | algorithm catalog with time/space complexity and limitations |
| GET | `/api/dataset/demo` | - | the synthetic Varunapuri dataset |
| POST | `/api/dataset/validate` | dataset | summary (counts, totals) or 400 |
| POST | `/api/dataset/import-csv` | `{nodesCsv, roadsCsv}` | `{dataset, summary}` |
| POST | `/api/flow/optimize` | `{dataset, source?, sink?, algorithm?, failedRoadIds?, failedNodeIds?, capacityOverrides?, includeTrace?}` | max flow, per-edge flow, min cut with explanations, zone allocation, trace |
| POST | `/api/failure-lab/simulate` | `{dataset, failedRoadIds, failedNodeIds, windowHours?, worstWindowHours?, batchScenarios?, roadsPerScenario?, seed?}` | throughput before/after, min cuts, zone impact, DSU components, segment-tree timeline, Quickselect percentiles, ranked recovery actions |
| POST | `/api/resilience/components` | `{dataset, failedRoadIds, failedNodeIds, scanCriticalRoads}` | components before/after, newly isolated zones, union trace, critical roads |
| POST | `/api/incidents/priority` | `{dataset, operations: [{type: INSERT\|UPDATE\|RESOLVE\|LOOKUP, id, ...}]}` | ranked queue, tree snapshot, metrics, fix-up events |
| POST | `/api/incidents/screen` | `{dataset?, expectedInsertions?, falsePositiveRate?, seed?, registeredIds?, generateHistorical?, incomingIds, absentProbes?}` | filter parameters, theoretical and measured FPR, per-id verdicts |
| POST | `/api/timeline/query` | `{series? or dataset, operations: [{type: SUM\|MAX\|ADD\|SET, from, to?, value?}]}` | per-operation answers checked against a plain array |
| POST | `/api/routes/plan` | `{dataset, depotId, stopIds, failedRoadIds?, failedNodeIds?}` | approximate tour, legs, NN baseline, MST bound, exact optimum (≤10 stops), matrix |
| POST | `/api/lab/flow` | `{nodes, edges: [{from, to, capacity}], source, sink}` | both solvers' traces, per-edge flows, min cut |
| POST | `/api/lab/dsu` | `{elementCount, operations: [{type: UNION\|FIND\|CONNECTED, a, b?}]}` | per-op results and parent arrays |
| POST | `/api/lab/rbtree` | `{operations: [{type: INSERT\|DELETE\|LOOKUP, key}]}` | per-op fix-up events, tree snapshot |
| POST | `/api/lab/quickselect` | `{values, percentiles?, seed?}` | selected values, comparisons, sort cross-check |
| POST | `/api/lab/tsp` | `{labels?, matrix, start?}` | tour or 400 if the matrix is not a metric |
| GET | `/api/benchmarks` | - | suite names |
| POST | `/api/benchmarks/{suite}` | - | `flow`, `dsu`, `rbtree`, `bloom`, `segtree`, `quickselect`, `tsp`: three sizes with baseline |
| POST | `/api/selfcheck` | - | seeded property checks of every algorithm |

Optional parameters use documented defaults only when **absent** (`source` = `SUPER_SOURCE`, `sink` = `SUPER_SINK`,
`algorithm` = `EDMONDS_KARP`, `windowHours` = 12, `batchScenarios` = 100, `seed` = 2026, ...). A present but invalid value is
always rejected.

## Example: Failure Lab response (abridged, real output for the demo city with R22 failed, seed 2026)

```json
{
  "throughputBefore": 2200,
  "throughputAfter": 2200,
  "throughputDrop": 0,
  "newlyIsolatedZones": [
    "Z10"
  ],
  "timeline": {
    "totalShortfallAfter": 1444,
    "worstWindowAfter": {
      "startHour": 31,
      "endHour": 36,
      "units": 551
    },
    "peakShortfallHourAfter": {
      "startHour": 33,
      "endHour": 33,
      "units": 104
    }
  },
  "batch": {
    "scenarios": 100,
    "percentiles": [
      {
        "percentile": 50,
        "value": 180,
        "comparisons": 168,
        "matchesSortBaseline": true
      },
      {
        "percentile": 90,
        "value": 400,
        "comparisons": 205,
        "matchesSortBaseline": true
      },
      {
        "percentile": 95,
        "value": 410,
        "comparisons": 193,
        "matchesSortBaseline": true
      }
    ],
    "selectedFailurePercentileRank": 24
  },
  "recoveryActions": [
    {
      "type": "REINFORCE_ROAD",
      "target": "R11",
      "throughputAfterAction": 2325,
      "gain": 125
    },
    {
      "type": "REINFORCE_ROAD",
      "target": "R10",
      "throughputAfterAction": 2320,
      "gain": 120
    }
  ]
}
```

Losing causeway R22 isolates Z10 (DSU), yet total throughput does not change. Z10 was already receiving nothing: the roads that would feed it through Z3 are saturated and lie on the min cut. NEXUS is built to expose non-obvious results like this.
