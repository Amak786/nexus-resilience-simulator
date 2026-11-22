#!/usr/bin/env python3
"""TRACE Zeek forwarder: sends connection METADATA from a Zeek conn.log to a TRACE server.

Zeek (https://zeek.org) is an open-source network monitor. Run it on a machine that sees the traffic you are authorised to
monitor (a SPAN/mirror port, or the host itself). It writes one line per connection to conn.log. This script follows that
file and forwards, in batches, only: timestamp, protocol, originator/responder address and port, and connection state.
No payloads, URLs, user names or DNS names are sent.

Works with Zeek's default TSV logs and with JSON logs (LogAscii::use_json=T). Standard library only (Python 3.8+).

  export TRACE_INGEST_TOKEN=...      # the server's TRACE_INGEST_TOKEN
  python3 trace-zeek.py --server https://trace-qol8.onrender.com --log /opt/zeek/logs/current/conn.log --sensor-id lab-gateway
  python3 trace-zeek.py --log conn.log --once    # read the file once (rows older than the server's retention are rejected)
"""
import argparse
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import uuid

VERSION = "trace-zeek/1.0"
PROTO = {"tcp": "TCP", "udp": "UDP"}
SAFE_ID = re.compile(r"^[A-Za-z0-9._-]{1,40}$")


def parse_line(line, fields):
    """One conn.log line -> TRACE row dict, or None. `fields` is the TSV header (None for JSON logs)."""
    line = line.rstrip("\n")
    if not line or line.startswith("#"):
        return None
    if line.lstrip().startswith("{"):
        try:
            rec = json.loads(line)
        except ValueError:
            return None
    elif fields:
        parts = line.split("\t")
        if len(parts) != len(fields):
            return None
        rec = dict(zip(fields, parts))
    else:
        return None
    proto = PROTO.get(str(rec.get("proto", "")).lower())
    try:
        ts = float(rec["ts"])
        lp, rp = int(rec["id.orig_p"]), int(rec["id.resp_p"])
    except (KeyError, ValueError, TypeError):
        return None
    la, ra = rec.get("id.orig_h"), rec.get("id.resp_h")
    if not proto or not la or not ra or la == "-" or ra == "-" or ra.startswith("127.") or ra == "::1":
        return None
    state = rec.get("conn_state")
    return {
        "observedAt": dt.datetime.fromtimestamp(ts, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "protocol": proto,
        "localAddress": la,
        "localPort": lp,
        "remoteAddress": ra,
        "remotePort": rp,
        "state": None if state in (None, "-") else str(state)[:20],
    }


def header_fields(line):
    if line.startswith("#fields"):
        return line.rstrip("\n").split("\t")[1:]
    return None


def send(server, token, sensor_id, rows):
    body = json.dumps({"sensorId": sensor_id, "batchId": str(uuid.uuid4()), "source": "zeek-conn-log", "agentVersion": VERSION,
                       "sentAt": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "connections": rows}).encode()
    req = urllib.request.Request(server.rstrip("/") + "/api/ingest/connections", data=body, method="POST",
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
    for attempt in range(3):  # same batchId on retry: the server ignores duplicates
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                res = json.loads(r.read())
                print(f"{time.strftime('%H:%M:%S')} sent {res['received']} rows: accepted {res['accepted']}, rejected {res['rejected']}"
                      + (f" ({'; '.join(res['rejectReasons'])})" if res.get("rejectReasons") else ""))
                return True
        except urllib.error.HTTPError as e:
            if e.code in (400, 401, 403, 413, 503):
                print(f"server refused the batch (HTTP {e.code}): {e.read()[:200].decode(errors='replace')}", file=sys.stderr)
                return False
        except (urllib.error.URLError, TimeoutError) as e:
            print(f"attempt {attempt + 1} failed: {e}", file=sys.stderr)
        time.sleep(2 ** (attempt + 1))
    return False


def main():
    ap = argparse.ArgumentParser(description="Forward Zeek conn.log metadata to TRACE")
    ap.add_argument("--server", default=os.environ.get("TRACE_SERVER", "http://localhost:8080"))
    ap.add_argument("--log", default="/opt/zeek/logs/current/conn.log")
    ap.add_argument("--sensor-id", default=os.environ.get("SENSOR_ID", "zeek-1"))
    ap.add_argument("--interval", type=float, default=10.0, help="seconds between batches")
    ap.add_argument("--once", action="store_true", help="read the whole file once and exit")
    a = ap.parse_args()
    token = os.environ.get("TRACE_INGEST_TOKEN", "")
    if not token:
        sys.exit("set TRACE_INGEST_TOKEN first")
    if not SAFE_ID.match(a.sensor_id):
        sys.exit("--sensor-id: 1-40 letters, digits, '.', '_' or '-'")
    fields, rows = None, []
    with open(a.log, encoding="utf-8", errors="replace") as f:
        if not a.once:
            f.seek(0, os.SEEK_END)  # live mode: only new connections from now on
        last = time.time()
        while True:
            line = f.readline()
            if line:
                fields = header_fields(line) or fields
                row = parse_line(line, fields)
                if row:
                    rows.append(row)
                if len(rows) < 2000:
                    continue
            elif a.once:
                break
            else:
                time.sleep(0.5)
            if rows and (len(rows) >= 2000 or time.time() - last >= a.interval):
                send(a.server, token, a.sensor_id, rows)
                rows, last = [], time.time()
    if rows:
        send(a.server, token, a.sensor_id, rows)


if __name__ == "__main__":
    main()
