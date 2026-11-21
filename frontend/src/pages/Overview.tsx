import { useEffect, useMemo, useState } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { AnalystResponse, LiveSource, LiveStatus } from '../lib/types';
import { MumbaiMap, pairs, type Overlay } from '../components/MumbaiMap';
import { Badge, Button, Card, ErrorBox, PageHeader, fmt } from '../components/ui';

const SUGGESTED = [
  'What changed in the road network since the last successful update?',
  'How fresh is the underlying data, and what is missing?',
  'Which roads in Dadar should be reviewed first, and why?',
  'Explain why the route from Dadar station to Bandra is selected.',
];

const tone = (s: LiveSource['status']) => (s === 'OK' ? 'good' : s === 'STALE' ? 'warn' : s === 'NOT_AVAILABLE' ? 'neutral' : 'bad');

function age(min: number | null): string {
  if (min === null) return '';
  if (min < 60) return `${min} min old`;
  if (min < 48 * 60) return `${Math.round(min / 60)} h old`;
  return `${Math.round(min / 1440)} d old`;
}

/** Live workspace: map, freshness, a few real metrics, observed changes, the grounded AI analyst and sources. */
export function Overview() {
  const { provenance: p, network } = useAppState();
  const [live, setLive] = useState<LiveStatus | null>(null);
  const [liveErr, setLiveErr] = useState<string | null>(null);
  const [aiConfigured, setAiConfigured] = useState<boolean | null>(null);
  const [question, setQuestion] = useState('');
  const ask = useRequest<AnalystResponse>();

  useEffect(() => {
    const load = () => api.live().then((l) => { setLive(l); setLiveErr(null); }).catch((e: Error) => setLiveErr(e.message));
    load();
    api.analystInfo().then((i) => setAiConfigured(i.aiConfigured)).catch(() => setAiConfigured(null));
    const t = setInterval(load, 60_000); // the server refreshes on its own schedule; this only re-reads the cache
    return () => clearInterval(t);
  }, []);

  const overlays: Overlay[] = useMemo(() => (live?.roadEdits ?? []).filter((e) => e.geometry && e.geometry.length >= 4)
    .map((e) => ({ coords: pairs(e.geometry!), color: '#c2410c', weight: 6 })), [live]);

  if (!p || !network) return null;
  const edits = live?.roadEdits ?? [];
  const src = (id: string) => live?.sources.find((s) => s.id === id);
  const submit = (q: string) => { const t = q.trim(); if (t) { setQuestion(t); void ask.run(() => api.ask(t)); } };
  const r = ask.data;

  return (
    <>
      <PageHeader kicker="Nexus Resilience · Mumbai" title="Is the road network I rely on changing?">
        Real OpenStreetMap roads, checked against live sources on a schedule, analysed with graph algorithms, and explained by an
        AI analyst that can only use the computed evidence. No live traffic is available, and nothing here pretends otherwise.
      </PageHeader>

      <div className="status-row" aria-label="Data freshness">
        {liveErr && <Badge tone="bad">live status unavailable: {liveErr}</Badge>}
        {live?.sources.map((s) => (
          <span key={s.id} className="status-pill" title={`${s.notes}${s.error ? ' · last error: ' + s.error : ''}`}>
            <span className={`dot ${tone(s.status)}`} aria-hidden /> {s.name}: <b>{s.status.replace(/_/g, ' ').toLowerCase()}</b>
            {s.ageMinutes !== null && <span className="muted"> · {age(s.ageMinutes)}</span>}
          </span>
        ))}
      </div>

      <div className="metrics compact">
        <div className="metric"><div className="metric-label">Mapped arterial roads</div><div className="metric-value">{fmt(p.graph.totalKm)} <span className="metric-unit">km</span></div>
          <div className="metric-hint">{fmt(p.graph.segments)} segments · snapshot {(src('osm-edits') ? live?.roadEditsSince : '')?.slice(0, 10)}</div></div>
        <div className="metric"><div className="metric-label">Largest connected part (DSU)</div><div className="metric-value">{fmt(p.graph.connectivity.largestComponentShare, 1)}%</div>
          <div className="metric-hint">{p.graph.connectivity.weakComponents} components</div></div>
        <div className="metric"><div className="metric-label">Road edits since snapshot</div><div className="metric-value">{src('osm-edits')?.lastSuccessAt ? fmt(edits.length) : '-'}</div>
          <div className="metric-hint">{src('osm-edits')?.lastSuccessAt ? `checked ${src('osm-edits')!.lastSuccessAt!.slice(11, 16)} UTC` : 'not checked yet'}</div></div>
        <div className="metric"><div className="metric-label">Weather now (context)</div><div className="metric-value">{live?.weather ? `${fmt(live.weather.temperatureC, 1)} °C` : '-'}</div>
          <div className="metric-hint">{live?.weather ? `${live.weather.description} · rain next 6 h ${fmt(live.weather.next6hPrecipitationMm, 1)} mm` : 'no observation yet'}</div></div>
      </div>

      <div className="grid-main">
        <Card>
          <MumbaiMap segments={network.items} area={p.studyArea} overlays={overlays} height={560} />
          <p className="small muted">Grey-blue: arterial roads in the committed OSM snapshot. <span style={{ color: '#c2410c' }}>Orange</span>: roads edited in OSM since the snapshot (live check).</p>
        </Card>
        <div className="stack">
          <Card title="AI analyst" actions={aiConfigured === false ? <Badge tone="warn">evidence-only mode</Badge> : aiConfigured ? <Badge tone="good">grounded AI</Badge> : null}>
            <form onSubmit={(e) => { e.preventDefault(); submit(question); }}>
              <textarea rows={2} maxLength={600} value={question} placeholder="Ask about changes, freshness, routes or connectivity" onChange={(e) => setQuestion(e.target.value)} />
              <div className="row"><Button type="submit" busy={ask.busy} disabled={!question.trim()}>Ask</Button></div>
            </form>
            <div className="chips">{SUGGESTED.map((s) => <button key={s} className="chip" onClick={() => submit(s)}>{s}</button>)}</div>
            <ErrorBox error={ask.error} />
            {r && (
              <div className="answer">
                <div className="small">{r.answerKind === 'AI_INTERPRETATION' ? <Badge tone="info">AI interpretation · {r.model}</Badge> : <Badge tone="neutral">computed evidence only</Badge>}
                  <span className="muted"> {r.generatedAt.slice(0, 19).replace('T', ' ')} UTC</span></div>
                {r.notice && <p className="small warn-text">{r.notice}</p>}
                <p className="answer-text">{r.answer}</p>
                <b className="small">Evidence ({r.evidence.length} tool result{r.evidence.length === 1 ? '' : 's'})</b>
                {r.evidence.map((e, i) => (
                  <details key={i} className="small">
                    <summary><code>{e.tool}</code> {e.result.error ? <Badge tone="bad">error</Badge> : <span className="muted">{e.result.result} · {e.result.source}</span>}</summary>
                    <pre className="evidence">{JSON.stringify(e.result.error ?? e.result.data, null, 1).slice(0, 4000)}</pre>
                  </details>
                ))}
              </div>
            )}
            <p className="small muted">The analyst can only call six application tools (data status, network summary, road edits, weather,
              integrity check, route). It never sees the whole dataset, and it is told never to claim a road is blocked.</p>
          </Card>
          <Card title={`Observed changes (${edits.length})`}>
            {edits.length === 0
              ? <p className="small">{src('osm-edits')?.lastSuccessAt ? 'No arterial road was edited in OpenStreetMap since the snapshot at the last check.' : 'The live OSM edit check has not completed yet.'}</p>
              : <ul className="plain-list small">{edits.slice(0, 8).map((e) => <li key={e.wayId}><a href={e.osmUrl} target="_blank" rel="noreferrer">{e.name || `way ${e.wayId}`}</a> <span className="muted">{e.highway} · v{e.version} · {e.editedAt.slice(0, 16).replace('T', ' ')} UTC</span></li>)}</ul>}
            <p className="small">Year-on-year snapshot comparison with scored changes: <a href="#/integrity">Integrity Check</a>.</p>
          </Card>
        </div>
      </div>

      <Card title="Sources and method">
        <table className="table small"><thead><tr><th>Source</th><th>Kind</th><th>Owner · licence</th><th>Update</th><th>Last success</th><th>Observed</th></tr></thead>
          <tbody>{live?.sources.map((s) => (
            <tr key={s.id}><td>{s.name}</td><td>{s.kind.replace('_', ' ').toLowerCase()}</td><td>{s.owner ?? '-'}{s.licence ? ` · ${s.licence}` : ''}</td>
              <td>{s.updateFrequency ?? '-'}</td><td>{s.lastSuccessAt?.slice(0, 16).replace('T', ' ') ?? '-'}</td><td>{s.observedAt?.slice(0, 16).replace('T', ' ') ?? '-'}</td></tr>))}
            <tr><td>Road graph</td><td>static snapshot</td><td>OpenStreetMap · ODbL</td><td>on rebuild</td><td colSpan={2}>{p.sources.find((s) => s.id === 'roads')?.dataTimestamp?.slice(0, 16).replace('T', ' ')}</td></tr>
          </tbody></table>
        <p className="small muted">Pipeline: providers → scheduled ingestion with timeouts, retries and validation → in-memory cache with observation and ingestion
          times → graph algorithms (DSU, Dijkstra, max flow, change detection) → REST API → this map → AI analyst grounded on tool results.</p>
      </Card>
    </>
  );
}
