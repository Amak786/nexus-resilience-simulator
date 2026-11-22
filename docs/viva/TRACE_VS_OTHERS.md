# How TRACE differs from Truecaller and from traditional network tools

## Truecaller (inspiration for the idea only)
Truecaller answers "who is calling?" for a phone number using a large, crowd-sourced, proprietary database of numbers and spam reports.
TRACE borrows only the broad idea — *an unknown identifier arrives; show what is known about it before you trust it* — and applies it to
network endpoints:

| | Truecaller | TRACE |
| --- | --- | --- |
| Identifier | Phone number | IP address or domain observed on your own machines |
| Data | Proprietary crowd-sourced directory | Public, attributable lists (abuse.ch Feodo, Spamhaus DROP), RIPEstat, optional AbuseIPDB, plus your sensors' own observations |
| Output | A name and a spam label | Evidence with sources and times, severity vs confidence, what is known / uncertain, next steps |
| Identity | Identifies a person or business | Never treats an address as a person; no names, no location-based suspicion |
| Relationships | None | Graph of observed relationships with BFS, DSU, Tarjan, Brandes, Dijkstra, max flow |
| Decision | App labels the caller | Human analyst decides; only a person can confirm an incident |

No Truecaller branding, interface, data or phone-number feature is used.

## Traditional network tools

| Tool | What it does well | What TRACE adds |
| --- | --- | --- |
| Wireshark | Packet-level inspection of one capture | TRACE keeps no payloads; it relates many connections over time and explains them |
| Zeek | Rich connection logs from a sensor | TRACE consumes Zeek's conn.log (`sensor/trace-zeek.py`) and adds reputation, graph analysis and explanations |
| Suricata / Snort | Signature-based alerts | TRACE does not detect exploits; it shows evidence and context for connections, and could ingest IDS alerts in future |
| SIEM (Splunk, Wazuh, ELK) | Large-scale log storage and correlation | TRACE is a small, explainable, free tool for one lab or office; every rule and score is documented in plain language |
| `netstat` / Resource Monitor | Current connections on one host | TRACE keeps 24 h history, checks reputation automatically and shows relationships across processes and machines |

## Why this matters for Indian colleges and small businesses
- Runs free (Render free tier or any laptop), uses only free and documented data sources, and works on Windows and Linux lab machines.
- No packet capture or paid feeds, so it is simple to set up legally on machines the institution owns.
- Findings are written for a non-specialist IT person: what was seen, why it matters, what is not known, what to check next.
