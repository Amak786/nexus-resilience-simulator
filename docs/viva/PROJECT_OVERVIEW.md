# Project overview

## Problem
Small IT teams, college labs and start-ups often see an unfamiliar connection on a machine and have no quick, explainable way to answer:
what is this address, is there any public evidence against it, how does it relate to everything else we observed, and what should we check next?
Commercial tools answer with opaque scores; open-source sensors produce raw logs.

## Solution
TRACE turns connection metadata from authorised sensors into an investigation workspace:
1. **Observe** — sensors upload connection metadata (no payloads) with a token.
2. **Check** — remote addresses are screened against downloaded threat lists (abuse.ch Feodo Tracker, Spamhaus DROP) and, on demand,
   enriched with the announcing network (RIPEstat) and optional AbuseIPDB reports.
3. **Relate** — a graph of sensors, processes, remote addresses and networks is analysed with BFS, DFS (articulation points, bridges),
   DSU, Dijkstra (Fibonacci heap), Brandes centrality and max flow / min cut.
4. **Explain** — documented rules create findings that separate severity from confidence and list evidence, what is known, what is
   uncertain and next steps. Only an analyst can mark a confirmed incident.
5. **Ask** — an optional AI analyst explains these results using six read-only tools; without a key it returns the deterministic evidence.

## Users
A lab administrator, a student security club, or a small company's IT person who wants to understand connections on machines they manage.

## Scope
In: metadata of TCP connections from authorised Windows/Linux machines; public threat lists; graph analysis; explanations.
Out: packet capture, blocking, scanning, malware detection, monitoring of networks you do not own, attribution of people.

## What is my own contribution
The ingestion model (flows, dedup by batch id, retention), the rule engine and scoring, the graph model and all assessed algorithms
(implemented by hand in Java), the sensor scripts, the evidence-first UI and the grounding design of the AI analyst.

## Limitations (summary)
In-memory storage (24 h), TCP only, polling sensors can miss very short connections, lists are incomplete and can be outdated, heuristics
are simple. Full list: LIMITATIONS_AND_FUTURE_WORK.md.
