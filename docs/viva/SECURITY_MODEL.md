# Security model

## Monitoring boundary
TRACE sees only what its sensors report. A sensor must be installed by someone authorised to monitor that machine. It reads the OS
connection table (metadata), never packet payloads, URLs or DNS names. Process names are opt-in. A hosted website cannot inspect other
devices' traffic, and TRACE does not claim to.

## Access levels
| Level | How | Can do |
| --- | --- | --- |
| Sensor | `Authorization: Bearer $TRACE_INGEST_TOKEN` | Upload batches only |
| Viewer | `Authorization: Bearer $TRACE_VIEW_TOKEN` | Observed data, findings, decisions, graph, SSE stream, AI analyst |
| Public | none | Source status, reputation lookup of an IP (without local data), Algorithm Lab |

Tokens are compared in constant time (`MessageDigest.isEqual`). If a token is unset or shorter than 16 characters, that level is
**disabled** (HTTP 503), never open. The viewer token is held in the browser's sessionStorage only. The SSE stream uses a one-time
60-second ticket so the token never appears in a URL or server log.

## Secrets
All keys come from environment variables on the server (`TRACE_*_TOKEN`, `ANTHROPIC_API_KEY`, `ABUSEIPDB_API_KEY`). None are in the
repository or the frontend bundle; test SEC-1 scans the source for key patterns and token defaults.

## Outbound requests (SSRF)
The server only calls fixed hosts (abuse.ch, spamhaus.org, stat.ripe.net, api.abuseipdb.com, api.anthropic.com). The only variable part
is a validated IP literal; host names are rejected (no DNS resolution). Private, loopback, link-local (e.g. 169.254.169.254), CGNAT,
multicast and reserved addresses are never sent externally (test TI-4).

## Input and abuse limits
Validated DTOs with JSON 400 errors; request body size limit; ≤ 2,000 rows per batch; flow cap with eviction; 20 lookups per client per
10 minutes; AI analyst 8 questions per client per 10 minutes and a daily cap; AbuseIPDB daily cap; CORS allow-list.

## Data handling
Observations live in memory for 24 h (configurable) and are deleted afterwards; nothing is written to disk. Sensor usernames are not
collected. An IP address is not treated as a person, and country is never used as a reason for suspicion.

## Read-only by design
TRACE never blocks, scans or contacts the observed hosts. "Confirmed incident" can only be set by a human.
