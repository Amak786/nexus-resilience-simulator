# Limitations and future work

## Current limitations (true today)
- **Visibility**: only machines running a sensor; TCP only; the sensor polls every 15 s, so very short connections can be missed.
- **Storage**: in memory, 24 h retention, lost on restart (including Render's free-tier sleep/restart). No long-term history.
- **Single tenant**: one ingest token and one viewer token; no user accounts or roles.
- **Threat intelligence**: Feodo Tracker currently lists only a handful of addresses; Spamhaus DROP covers hijacked netblocks only;
  AbuseIPDB requires a key. Absence from lists is not safety.
- **Heuristics**: three simple, documented rules (list match, uncommon port, high fan-out) with fixed thresholds; no baselining per host.
- **Graph**: built from current observations; ASN nodes appear only after an address was investigated; centrality is skipped above
  3,000 nodes; the force-directed view shows at most 600 nodes.
- **AI analyst**: requires a paid key; it was implemented and tested with tool results and the no-key fallback, but a live model answer
  was not verified in this environment because no key was available.
- **Not implemented**: Zeek / Suricata adapters, CERT-In advisory ingestion, IPv6 DROP list, alert notifications, active response.

## Future work
1. PostgreSQL (or SQLite) behind the `EventStore` seam for 30-day history and per-host baselines.
2. Zeek `conn.log` adapter in the sensor for SPAN-port deployments.
3. Per-user accounts with roles (viewer, analyst, admin) instead of shared tokens.
4. Additional lists (abuse.ch ThreatFox, Spamhaus EDROP / DROPv6) and CERT-In advisories where terms permit.
5. Change-detection rules over time windows (new destination for a long-running process, sudden fan-out increase).
