import { useState } from 'react';
import { auth } from '../lib/api';
import { useAppState } from '../lib/state';
import { Badge, Button, Card, PageHeader, fmt } from '../components/ui';
import { ago, time } from '../components/common';

export function DataSources() {
  const { sources, sourcesError, viewer, stream, refreshSources } = useAppState();
  const [tok, setTok] = useState('');
  const origin = window.location.origin;
  return (
    <>
      <PageHeader kicker="Data Sources" title="Where every number comes from">
        TRACE shows live data only when an authorised sensor is uploading. Threat lists are refreshed hourly. Nothing is simulated: when a
        source is down, its status says so.
      </PageHeader>
      {sourcesError && <div className="alert error">{sourcesError}</div>}
      <div className="grid-2">
        <Card title="Viewer access">
          {viewer ? (
            <><p className="small"><Badge tone="good">signed in</Badge> Event stream: {stream}. The token is kept in this tab's session storage only.</p>
              <Button variant="ghost" onClick={() => { auth.set(''); void refreshSources(); }}>Sign out</Button></>
          ) : (
            <form onSubmit={(e) => { e.preventDefault(); auth.set(tok.trim()); setTok(''); void refreshSources(); }}>
              <p className="small">{sources?.viewerConfigured === false ? 'This server has no TRACE_VIEW_TOKEN set, so observed data is disabled.' : 'Enter the viewer token (TRACE_VIEW_TOKEN) to see observed data.'}</p>
              <div className="row"><input type="password" value={tok} onChange={(e) => setTok(e.target.value)} placeholder="viewer token" aria-label="Viewer token" autoComplete="off" />
                <Button type="submit" disabled={!tok.trim()}>Sign in</Button></div>
            </form>
          )}
          <p className="small muted">Mode: <b>{sources?.mode.replace('_', ' ').toLowerCase()}</b> · retention {sources?.retentionHours} h · storage {sources?.storage === 'MEMORY_WITH_DISK_SNAPSHOT' ? 'memory + disk snapshot' : 'memory only (lost on restart)'} · sensor upload {sources?.ingestConfigured ? 'enabled' : 'disabled (TRACE_INGEST_TOKEN unset)'}</p>
        </Card>
        <Card title="Sensors">
          {!viewer ? <p className="small muted">Sensor details are visible to signed-in viewers.</p> : sources?.sensors.length === 0 ? <p className="small">No sensor has connected yet.</p> : (
            <table className="table small"><thead><tr><th>Sensor</th><th>Status</th><th>Last batch</th><th>Last observation</th><th className="num">Rows</th><th className="num">Rejected</th><th className="num">Dup. batches</th></tr></thead>
              <tbody>{sources?.sensors.map((s) => <tr key={s.sensorId}><td>{s.sensorId}<div className="muted">{s.source} {s.agentVersion}</div></td>
                <td><Badge tone={s.status === 'LIVE' ? 'good' : s.status === 'DELAYED' ? 'warn' : 'bad'}>{s.status.toLowerCase()}</Badge></td>
                <td title={time(s.lastBatchAt)}>{ago(s.lastBatchAt)}</td><td title={time(s.lastObservedAt)}>{ago(s.lastObservedAt)}</td>
                <td className="num">{fmt(s.rows)}</td><td className="num">{fmt(s.rejectedRows)}</td><td className="num">{s.duplicateBatches}</td></tr>)}</tbody></table>
          )}
          <p className="small muted">LIVE: a batch within 90 s · DELAYED: within 15 min · DISCONNECTED: older.</p>
        </Card>
      </div>
      <Card title="Threat intelligence and enrichment">
        <div className="table-scroll"><table className="table small">
          <thead><tr><th>Source</th><th>Status</th><th>Kind</th><th>Last success</th><th>List timestamp</th><th className="num">Records</th><th>Licence</th><th>Error</th></tr></thead>
          <tbody>{sources?.intel.map((f) => <tr key={f.id}><td><b>{f.name}</b><div className="muted">{f.owner} · {f.endpoint}</div><div className="muted">{f.notes}</div></td>
            <td><Badge tone={f.status === 'OK' ? 'good' : f.status === 'ERROR' || f.status === 'STALE' ? 'bad' : 'neutral'}>{f.status.toLowerCase().replace('_', ' ')}</Badge></td>
            <td>{f.kind.toLowerCase().replace('_', ' ')}{f.updateFrequency ? <div className="muted">{f.updateFrequency}</div> : null}</td>
            <td title={time(f.lastSuccessAt)}>{ago(f.lastSuccessAt)}</td><td>{time(f.feedTimestamp)}</td><td className="num">{fmt(f.records)}</td><td>{f.licence}</td><td className="warn-text">{f.error ?? ''}</td></tr>)}</tbody>
        </table></div>
        {sources && <p className="small muted">Bloom filter over the Feodo list: {fmt(sources.bloom.bits)} bits, {sources.bloom.hashes} hashes, theoretical false-positive rate {fmt(100 * sources.bloom.theoreticalFpr, 2)}%;
          screened {fmt(sources.bloom.screened)} addresses, {sources.bloom.possiblyPresent} possible hits, {sources.bloom.confirmed} confirmed, {sources.bloom.falsePositives} false positives (all caught by the exact check).</p>}
        <p className="small muted">Live traffic of the whole internet is not visible to TRACE: it only sees what its sensors on authorised machines report.</p>
      </Card>
      <Card title="Connect a sensor (metadata only, authorised machines only)">
        <p className="small">Windows (PowerShell), from the repository's <code>sensor</code> folder:</p>
        <pre className="evidence">{`$env:TRACE_INGEST_TOKEN = "<server TRACE_INGEST_TOKEN>"
.\\trace-sensor.ps1 -Server ${origin} -SensorId my-laptop -IncludeProcess`}</pre>
        <p className="small">Linux (bash, needs ss, curl and python3):</p>
        <pre className="evidence">{`export TRACE_INGEST_TOKEN="<server TRACE_INGEST_TOKEN>"
TRACE_SERVER=${origin} SENSOR_ID=lab-server ./trace-sensor.sh`}</pre>
        <p className="small">Zeek (network monitor on a SPAN/mirror port or gateway), needs Python 3:</p>
        <pre className="evidence">{`export TRACE_INGEST_TOKEN="<server TRACE_INGEST_TOKEN>"
python3 trace-zeek.py --server ${origin} --log /opt/zeek/logs/current/conn.log --sensor-id lab-gateway`}</pre>
        <p className="small muted">The sensor reads the local TCP connection table every 15 s (addresses, ports, state and, optionally, process names) and uploads it with
          a bearer token. No packet contents, URLs or DNS names are collected. Each batch has an id, so retries are never counted twice.</p>
      </Card>
    </>
  );
}
