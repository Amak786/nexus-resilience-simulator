# TRACE audit (2026-11-23)

Scope: repository `Amak786/nexus-resilience-simulator` at commit `683c4b3` and the live service https://trace-qol8.onrender.com.

## 1. Inventory

| Layer | What exists |
| --- | --- |
| Frontend | React 18 + TypeScript + Vite, six screens (Overview, Network, Investigations, Algorithm Lab, AI Analyst, Data Sources), canvas graph, SSE client |
| Backend | Java 21, Spring Boot 3.5: `net/` (EventStore, AnalysisService, TraceController, IpAddr), `intel/` (ThreatIntelService), `analyst/` (Claude tool loop), `algorithms/` (all coursework algorithms), `mumbai/` (public road graph for A*/TSP lab), `service/` (lab, benchmarks, self-check) |
| Data store | In-memory event store, 24 h retention (no database) |
| External sources | abuse.ch Feodo Tracker, Spamhaus DROP (hourly), RIPEstat (on demand), AbuseIPDB (optional key), Claude API (optional key) |
| Collectors | `sensor/trace-sensor.ps1` (Windows), `sensor/trace-sensor.sh` (Linux) |
| Deployment | Dockerfile, `render.yaml`; Render services `trace` (trace-qol8) and the older `nexus-resilience` |

## 2. Checks run

| Check | Result |
| --- | --- |
| Backend tests (`mvn test`) | 101 run, 0 failures (last run on `683c4b3`) |
| Frontend lint / typecheck / build | clean / clean / OK (208 kB JS) |
| Live = repository | yes: live `startedAt` 2026-11-22 23:03 UTC, built from `683c4b3` = HEAD |
| Live threat feeds | Feodo OK (5), Spamhaus DROP OK (1,752), RIPEstat OK |
| Live tokens | viewer OK, ingest OK; wrong/missing token → 401 |
| Algorithm Lab (live, every tab clicked and run) | all 12 tabs render and run without errors |
| Investigate (live) | IPv4/IPv6 public, private, DROP match, invalid input all handled |

## 3. Confirmed findings

| # | Severity | Finding |
| --- | --- | --- |
| F1 | High | Investigations accepts only IP literals; domain lookup (required) is missing |
| F2 | High | No integration for a real network sensor such as Zeek/Suricata; only OS connection-table sensors |
| F3 | Medium | For private / reserved addresses the assessment says "No reputation evidence ... was found", which implies a check that was never made |
| F4 | Medium | Algorithm Lab catalogue lacks "why chosen" and "how to demonstrate in a viva" per algorithm |
| F5 | Low | Dead code from the previous project: `TimelineChart`, `ImpactHistogram` in `Charts.tsx` (simulator "kits/h" charts) and their CSS |
| F6 | Low | Segment-tree lab default input labelled "hourly demand" (old project wording) |
| F7 | Low | Docs and sensor examples still point at the old URL `nexus-resilience.onrender.com` |
| F8 | Medium | No database: observations live in memory and are lost on restart (Render free instances restart on sleep and deploy) |
| F9 | Info | Real-time viewer flow (sensor → SSE → UI) was verified locally on 2026-11-21; on the live service it needs the owner's tokens, so it was not exercised by the audit |
| F10 | Info | No dead buttons or broken routes found; every button maps to a backend call |

## 4. Repair plan (in order)

1. F1 — domain investigation: resolve the name server-side (DNS only, with timeout), check every resolved address, never contact the host itself.
2. F2 — Zeek collector: `sensor/trace-zeek.py` forwards Zeek `conn.log` (JSON or TSV) metadata to the existing ingestion API.
3. F3 — truthful assessment text for non-public addresses.
4. F4 — extend the algorithm catalogue (why chosen, viva demo) and show it in the Lab.
5. F5, F6, F7 — remove dead code, fix wording and URLs.
6. F8 — optional file snapshot of the event store (`TRACE_DATA_DIR`) so a local or VPS deployment survives restarts; on Render free the disk is ephemeral, which stays documented.
7. Re-run all checks, deploy, verify live.

## 5. Repair results

| # | Status | Change |
| --- | --- | --- |
| F1 | Fixed | `GET /api/lookup?q=` accepts an IP or a domain; domains are resolved with DNS (4 s timeout, max 8 addresses) and every address is investigated; URLs, ports and invalid names are rejected. Investigations UI and the AI tool use it. Tests TR-8. |
| F2 | Fixed (not live-tested) | `sensor/trace-zeek.py` forwards Zeek `conn.log` (TSV or JSON) metadata; 4 parser tests in `sensor/test_trace_zeek.py`. Not run against a live Zeek sensor here. |
| F3 | Fixed | Non-public addresses now say they were not checked externally. Test TR-8. |
| F4 | Fixed | Catalogue has "why chosen" and "viva demonstration" for all 13 algorithms; the Lab shows purpose, complexity, why, limits and demo per algorithm. |
| F5 | Fixed | Removed `TimelineChart`, `ImpactHistogram` and their CSS. |
| F6 | Fixed | Segment-tree lab now uses "observations per minute" wording and data shape. |
| F7 | Fixed | README, deployment guide, sensor help and demo script point at https://trace-qol8.onrender.com. |
| F8 | Partly fixed | Optional disk snapshot (`TRACE_DATA_DIR`, atomic write, restore with retention); Data Sources shows the storage mode. Still no database; Render free disk is ephemeral. Test TR-9. |
| F9 | Open | Live viewer flow needs the owner's tokens and a running sensor; verified locally only. |

Checks after repair: backend 103 tests pass; Zeek forwarder 4 tests pass; frontend lint, typecheck and build clean.
