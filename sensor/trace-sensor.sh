#!/usr/bin/env bash
# TRACE sensor for Linux - uploads connection METADATA of this machine to a TRACE server.
# Reads: `ss -tn` (established TCP: local/remote address and port). With INCLUDE_PROCESS=1, `ss -tnp` adds process names
# (needs root to see other users' processes). No packet contents, no DNS names. Needs: bash, ss (iproute2), curl, python3.
# Run only on machines you own or are authorised to monitor.
#
#   export TRACE_INGEST_TOKEN=...            # the server's TRACE_INGEST_TOKEN
#   TRACE_SERVER=http://localhost:8080 SENSOR_ID=lab-server-1 INTERVAL=15 ./trace-sensor.sh
#   ONCE=1 ./trace-sensor.sh                  # one batch, then exit
set -euo pipefail
: "${TRACE_INGEST_TOKEN:?set TRACE_INGEST_TOKEN}"
SERVER="${TRACE_SERVER:-http://localhost:8080}"
SENSOR_ID="${SENSOR_ID:-sensor-1}"
INTERVAL="${INTERVAL:-15}"
INCLUDE_PROCESS="${INCLUDE_PROCESS:-0}"
URI="${SERVER%/}/api/ingest/connections"

batch() {
  local flags="-tnH"
  [ "$INCLUDE_PROCESS" = "1" ] && flags="-tnpH"
  ss $flags state established 2>/dev/null | SENSOR_ID="$SENSOR_ID" python3 -c '
import sys, json, uuid, re, os, datetime
now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
rows = []
def split(ep):
    host, _, port = ep.rpartition(":")
    return host.strip("[]").split("%")[0], int(port)
for line in sys.stdin:
    p = line.split()
    if len(p) < 4: continue
    try:
        la, lp = split(p[2]); ra, rp = split(p[3])
    except ValueError:
        continue
    if ra in ("127.0.0.1", "::1") or ra.startswith("127."): continue
    row = {"observedAt": now, "protocol": "TCP", "localAddress": la, "localPort": lp, "remoteAddress": ra, "remotePort": rp, "state": "ESTAB"}
    m = re.search(r"users:\(\(\"([^\"]+)\"", line)
    if m: row["process"] = re.sub(r"[^A-Za-z0-9 ._()+-]", "", m.group(1))[:60]
    rows.append(row)
print(json.dumps({"sensorId": os.environ["SENSOR_ID"], "batchId": str(uuid.uuid4()), "source": "linux-ss",
                  "agentVersion": "trace-sensor-sh/1.0", "sentAt": now, "connections": rows[:2000]}))
'
}

echo "TRACE sensor '$SENSOR_ID' -> $URI every ${INTERVAL}s (Ctrl+C to stop)"
while true; do
  body="$(batch)"
  for attempt in 1 2 3; do   # same batchId on retry; the server ignores duplicates
    if out=$(curl -sS -m 30 -X POST "$URI" -H "Authorization: Bearer $TRACE_INGEST_TOKEN" -H "Content-Type: application/json" \
             --data-binary "$body" -w " HTTP %{http_code}"); then
      echo "$(date +%T) $out"; break
    fi
    sleep $((2 ** attempt))
  done
  [ "${ONCE:-0}" = "1" ] && break
  sleep "$INTERVAL"
done
