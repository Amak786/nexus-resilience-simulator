import { useEffect } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { OverviewResponse } from '../lib/types';
import { ErrorBox, Card, PageHeader, Badge, fmt } from '../components/ui';
import { FindingCard, NeedsViewer, ago, time } from '../components/common';

function Sparkline({ values }: { values: number[] }) {
  const max = Math.max(1, ...values);
  const w = 600;
  const h = 60;
  const pts = values.map((v, i) => `${(i / Math.max(1, values.length - 1)) * w},${h - (v / max) * (h - 4) - 2}`).join(' ');
  return (
    <svg viewBox={`0 0 ${w} ${h}`} className="spark" role="img" aria-label="observations per minute, last 60 minutes" preserveAspectRatio="none">
      <polyline points={pts} fill="none" strokeWidth="1.5" />
    </svg>
  );
}

export function Overview() {
  const { sources, viewer, tick } = useAppState();
  const ov = useRequest<OverviewResponse>();
  const runOv = ov.run;
  useEffect(() => { if (viewer) void runOv(api.overview); }, [viewer, tick, runOv]); // tick = new batch pushed over SSE
  const d = ov.data;
  const sensors = sources?.sensors ?? [];
  return (
    <>
      <PageHeader kicker="Overview" title="When a connection looks unusual, what do we actually know?">
        TRACE collects connection metadata from authorised sensors, checks remote addresses against public threat lists, builds a graph of
        observed relationships and explains every finding with its evidence and its uncertainty. Read-only: it never blocks anything.
      </PageHeader>
      <div className="status-row">
        {sources?.intel.map((f) => (
          <span key={f.id} className="status-pill" title={f.error ?? f.notes}>
            <span className={`dot ${f.status === 'OK' ? 'good' : f.status === 'ERROR' || f.status === 'STALE' ? 'bad' : 'neutral'}`} aria-hidden />
            {f.name}: <b>{f.status.toLowerCase().replace('_', ' ')}</b>{f.lastSuccessAt && <span className="muted"> · {ago(f.lastSuccessAt)}</span>}
          </span>
        ))}
        {viewer && sensors.map((s) => (
          <span key={s.sensorId} className="status-pill">
            <span className={`dot ${s.status === 'LIVE' ? 'good' : s.status === 'DELAYED' ? 'warn' : 'bad'}`} aria-hidden />
            sensor {s.sensorId}: <b>{s.status.toLowerCase()}</b><span className="muted"> · last batch {ago(s.lastBatchAt)}</span>
          </span>
        ))}
      </div>
      <NeedsViewer>
        <ErrorBox error={ov.error} />
        {sensors.length === 0 && <div className="alert info">No sensor has sent data yet. Start one from <a href="#/sources">Data Sources</a> (Windows or Linux); results appear here within seconds.</div>}
        {d && (
          <>
            <div className="metrics compact">
              <div className="metric"><div className="metric-label">Remote addresses observed</div><div className="metric-value">{fmt(d.remoteAddresses)}</div><div className="metric-hint">{fmt(d.flows)} connections, last {sources?.retentionHours} h</div></div>
              <div className="metric"><div className="metric-label">Findings open</div><div className="metric-value">{fmt(d.findingsOpen)}</div><div className="metric-hint">{d.findingsHigh} high severity (not dismissed)</div></div>
              <div className="metric"><div className="metric-label">New remotes, last 15 min</div><div className="metric-value">{fmt(d.changes.newRemotes)}</div><div className="metric-hint">{d.changes.goneRemotes} no longer seen · components {d.changes.componentsBefore} → {d.changes.componentsNow}</div></div>
            </div>
            <div className="grid-2">
              <Card title="Findings requiring attention">
                {d.topFindings.length === 0 ? <p className="small">No rule fired on the current observations. That is not proof that nothing is wrong; see the limits on each source.</p>
                  : d.topFindings.map((f) => <FindingCard key={f.id} f={f} />)}
                <p className="small"><a href="#/investigations">All findings and lookups →</a></p>
              </Card>
              <div className="stack">
                <Card title="Observations per minute (last 60 min)">
                  <Sparkline values={d.timeSeries.perMinute} />
                  <p className="small muted">Peak {d.timeSeries.queryMax} at minute {d.timeSeries.queryMaxMinute} of 60; total {d.timeSeries.querySum}
                    {d.timeSeries.matchesScan ? ' (segment tree = linear scan)' : ''}. Counts what sensors reported, not all traffic.</p>
                </Card>
                <Card title="Recent connections">
                  <div className="table-scroll short"><table className="table small">
                    <thead><tr><th>Remote</th><th>Port</th><th>Process</th><th>Last seen</th></tr></thead>
                    <tbody>{d.recent.map((o, i) => <tr key={i}><td><a href={`#/investigations?ip=${o.remoteAddress}`}>{o.remoteAddress}</a></td><td>{o.remotePort}</td><td>{o.process ?? <span className="muted">-</span>}</td><td title={time(o.lastSeen)}>{ago(o.lastSeen)}</td></tr>)}</tbody>
                  </table></div>
                </Card>
              </div>
            </div>
            <p className="small muted">Computed {time(d.computedAt)}. Live updates arrive over a server-sent event stream when a sensor uploads. <Badge tone="neutral">observation</Badge> = reported by a sensor; <Badge tone="warn">threat-intel match</Badge> = on a downloaded list; <Badge tone="neutral">heuristic</Badge> = a documented rule; confirmed incidents exist only when an analyst marks one.</p>
          </>
        )}
      </NeedsViewer>
    </>
  );
}
