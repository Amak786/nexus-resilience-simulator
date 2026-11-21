import { useMemo, useState } from 'react';
import { api, type LatLon } from '../lib/api';
import { PRESET_AREAS, useAppState, useRequest } from '../lib/state';
import type { Change, DemoModification, IntegrityResponse, Segment, SideSummary } from '../lib/types';
import { MumbaiMap, pairs, type Marker, type Overlay, type SegStyle } from '../components/MumbaiMap';
import { Badge, Button, Card, ErrorBox, Explain, Field, List, Metric, Notice, PageHeader, fmt } from '../components/ui';

/** Default probe points per preset area (two well-known junction areas inside each box). */
const PROBES: Record<string, [LatLon, LatLon]> = {
  dadar: [{ lat: 19.018, lon: 72.847 }, { lat: 19.056, lon: 72.845 }],
  kurla: [{ lat: 19.065, lon: 72.88 }, { lat: 19.09, lon: 72.91 }],
  andheri: [{ lat: 19.107, lon: 72.84 }, { lat: 19.14, lon: 72.87 }],
  fort: [{ lat: 18.915, lon: 72.828 }, { lat: 18.975, lon: 72.835 }],
  borivali: [{ lat: 19.185, lon: 72.845 }, { lat: 19.24, lon: 72.855 }],
};

const STATUS_LABEL: Record<string, string> = {
  SIMULATED_TAMPERING: 'Simulated tampering (demo)', SUSPICIOUS_NEEDS_REVIEW: 'Suspicious - needs review',
  ROUTINE_UNREVIEWED: 'Routine (unreviewed)', LEGITIMATE_REVIEWED: 'Legitimate (reviewed)',
};
const STATUS_COLOR: Record<string, string> = {
  SIMULATED_TAMPERING: '#7c3aed', SUSPICIOUS_NEEDS_REVIEW: '#dc2626', ROUTINE_UNREVIEWED: '#f59e0b', LEGITIMATE_REVIEWED: '#16a34a',
};

const insideArea = (a: { south: number; west: number; north: number; east: number }, s: Segment) => {
  const ok = (i: number) => s.geometry[i] >= a.south && s.geometry[i] <= a.north && s.geometry[i + 1] >= a.west && s.geometry[i + 1] <= a.east;
  return ok(0) && ok(s.geometry.length - 2);
};

function describeMod(m: DemoModification): string {
  if (m.type === 'ADD_CONNECTION') return `Add unrecorded connection (${m.fromLat?.toFixed(4)}, ${m.fromLon?.toFixed(4)}) to (${m.toLat?.toFixed(4)}, ${m.toLon?.toFixed(4)})`;
  return `${m.type === 'REMOVE_WAY' ? 'Remove' : 'Flip one-way rule of'} OSM way ${m.wayId}`;
}

export function Integrity() {
  const { network } = useAppState();
  const [areaId, setAreaId] = useState('dadar');
  const area = PRESET_AREAS.find((p) => p.id === areaId)!.area;
  const [probe, setProbe] = useState<[LatLon, LatLon]>(PROBES.dadar);
  const [mods, setMods] = useState<DemoModification[]>([]);
  const [reviewed, setReviewed] = useState<string[]>([]);
  const [mode, setMode] = useState<'probe' | 'remove' | 'flip' | 'connect'>('probe');
  const [pending, setPending] = useState<LatLon | null>(null);
  const [nextProbe, setNextProbe] = useState(0);
  const [selected, setSelected] = useState<string | null>(null);
  const [topK, setTopK] = useState(10);
  const [strip, setStrip] = useState<[number, number]>([0, 15]);
  const req = useRequest<IntegrityResponse>();
  const scen = useRequest<unknown>();
  const r = req.data;

  const segs = useMemo(() => (network ? network.items.filter((s) => insideArea(area, s)) : []), [network, area]);
  const changeById = useMemo(() => new Map((r?.changes ?? []).map((c) => [c.id, c])), [r]);
  const sel = selected ? changeById.get(selected) ?? null : null;

  const run = (m = mods, rv = reviewed, k = topK, st = strip) =>
    void req.run(() => api.integrity({ area, from: probe[0], to: probe[1], demoModifications: m, reviewedLegitimate: rv, topK: k, stripFrom: st[0], stripTo: st[1] }));

  const reset = () => { setMods([]); setReviewed([]); setSelected(null); setPending(null); setMode('probe'); setStrip([0, 15]); req.setData(null); };

  const loadScenario = () => void scen.run(async () => {
    const s = await api.scenario(area, probe[0], probe[1]);
    setMods(s.modifications);
    run(s.modifications);
    return s;
  });

  const styleOf = useMemo(() => {
    const removedWays = new Set(mods.filter((m) => m.type === 'REMOVE_WAY').map((m) => m.wayId));
    return (s: Segment): SegStyle | null => removedWays.has(s.wayId) ? { color: '#7c3aed', weight: 3, dash: '4 4' } : { color: '#94a3b8', weight: 1.5, opacity: 0.6 };
  }, [mods]);

  const overlays: Overlay[] = useMemo(() => {
    const o: Overlay[] = [];
    if (r) {
      r.baseline.route.geometry.forEach((g) => o.push({ coords: pairs(g), color: '#0f172a', weight: 7, dash: '2 6' }));
      r.updated.route.geometry.forEach((g) => o.push({ coords: pairs(g), color: '#2563eb', weight: 4 }));
      for (const c of r.changes) {
        const w = c.id === selected ? 8 : 4;
        c.geometry.forEach((g) => o.push({ coords: pairs(g), color: STATUS_COLOR[c.status], weight: w, dash: c.kinds.includes('REMOVED') ? '6 4' : undefined }));
      }
    }
    mods.filter((m) => m.type === 'ADD_CONNECTION').forEach((m) => o.push({ coords: [[m.fromLat!, m.fromLon!], [m.toLat!, m.toLon!]], color: '#7c3aed', weight: 4, dash: '8 6' }));
    return o;
  }, [r, mods, selected]);

  const markers: Marker[] = [
    { ...probe[0], label: 'Probe A', color: '#16a34a' }, { ...probe[1], label: 'Probe B', color: '#dc2626' },
    ...(pending ? [{ ...pending, label: 'connection start', color: '#7c3aed' }] : []),
  ];

  const onMapClick = (lat: number, lon: number) => {
    if (mode === 'probe') { setProbe(nextProbe === 0 ? [{ lat, lon }, probe[1]] : [probe[0], { lat, lon }]); setNextProbe(1 - nextProbe); }
    if (mode === 'connect') {
      if (!pending) setPending({ lat, lon });
      else { setMods([...mods, { type: 'ADD_CONNECTION', fromLat: pending.lat, fromLon: pending.lon, toLat: lat, toLon: lon }]); setPending(null); }
    }
  };
  const onSegmentClick = (s: Segment) => {
    if (mode === 'remove' || mode === 'flip') {
      setMods([...mods, { type: mode === 'remove' ? 'REMOVE_WAY' : 'FLIP_ONEWAY', wayId: s.wayId }]);
    }
  };

  if (!network) return null;
  return (
    <>
      <PageHeader kicker="Integrity Check · main workflow" title="Has the road-network dataset changed in a way that matters?">
        A routing service depends on its road dataset. Silent Shield compares a <b>trusted baseline</b> (OpenStreetMap as it was on
        20 Nov 2025) with the <b>updated snapshot</b> (OpenStreetMap now), finds every changed road, measures what each change does to
        connectivity and to a probe route, and ranks changes for human review. A high score means "check this first", <b>never</b> "this was an attack".
      </PageHeader>
      <div className="grid-main">
        <Card>
          <MumbaiMap segments={segs} styleOf={styleOf} markers={markers} overlays={overlays} area={area} fitArea={area} height={620}
            onMapClick={onMapClick} onSegmentClick={mode === 'remove' || mode === 'flip' ? onSegmentClick : undefined} />
          <p className="small muted">
            <span className="lg-line" style={{ borderColor: '#dc2626' }} /> suspicious · <span className="lg-line" style={{ borderColor: '#f59e0b' }} /> routine ·
            <span className="lg-line" style={{ borderColor: '#16a34a' }} /> reviewed legitimate · <span className="lg-line" style={{ borderColor: '#7c3aed' }} /> demonstration ·
            dashed = removed · black dotted = baseline probe route · blue = updated probe route
          </p>
        </Card>
        <div className="stack">
          <Card title="1 · Load snapshots and probe route">
            <Field label="Area"><select value={areaId} onChange={(e) => { setAreaId(e.target.value); setProbe(PROBES[e.target.value]); reset(); }}>
              {PRESET_AREAS.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}</select></Field>
            <Field label="Map click mode" hint="Probe: clicks move A then B. Remove / flip: click a road. Connect: click two points.">
              <select value={mode} onChange={(e) => { setMode(e.target.value as typeof mode); setPending(null); }}>
                <option value="probe">Move probe points (clicks alternate A, B)</option>
                <option value="remove">DEMO: remove a road</option>
                <option value="flip">DEMO: flip a one-way rule</option>
                <option value="connect">DEMO: add an unrecorded connection</option>
              </select></Field>
            <div className="row">
              <Button busy={req.busy} onClick={() => run()}>Compare snapshots</Button>
              <Button variant="ghost" busy={scen.busy} onClick={loadScenario}>Load demo scenario S1</Button>
              <Button variant="ghost" onClick={reset}>Reset</Button>
            </div>
            {mods.length > 0 && (
              <Notice tone="warn"><b>Demonstration modifications ({mods.length})</b> - synthetic, not real edits and not a real attack.
                <ol className="small">{mods.map((m, i) => <li key={i}>{describeMod(m)} <button className="link" onClick={() => setMods(mods.filter((_, j) => j !== i))}>remove</button></li>)}</ol>
                Press "Compare snapshots" to recompute.</Notice>
            )}
            <ErrorBox error={req.error ?? scen.error} />
          </Card>
          {r && <StatusCard r={r} />}
        </div>
      </div>
      {r && (
        <>
          <Card title="2 · Before and after">
            <BeforeAfter b={r.baseline} a={r.updated} />
            {r.warnings.map((w) => <Notice key={w} tone="warn">{w}</Notice>)}
          </Card>
          <div className="grid-2">
            <Card title={`3 · Changes ranked for investigation (${r.totalChanges})`}>
              <div className="table-scroll short">
                <table className="table small"><thead><tr><th>ID</th><th>Change</th><th>Road</th><th className="num">Score</th><th>Status</th></tr></thead>
                  <tbody>{r.changes.map((c) => (
                    <tr key={c.id} onClick={() => setSelected(c.id)} className={c.id === selected ? 'selected' : ''} style={{ cursor: 'pointer' }}>
                      <td>{c.id}</td><td>{c.kinds.join(', ')}</td><td>{c.name || '(unnamed)'} <span className="muted">{c.roadClass}</span></td>
                      <td className="num">{c.score}</td><td className={`status-${c.status}`}>{STATUS_LABEL[c.status]}</td></tr>))}</tbody></table>
              </div>
              <p className="small muted">Click a row to inspect it. Scoring rule (threshold {r.suspiciousThreshold}):</p>
              <List items={r.scoringRule} />
            </Card>
            <Card title={sel ? `Change ${sel.id}` : 'Selected change'}>
              {!sel ? <p className="muted small">Select a change in the table.</p> : <ChangeDetail c={sel} reviewed={reviewed.includes(sel.id)}
                onReview={(on) => { const rv = on ? [...reviewed, sel.id] : reviewed.filter((x) => x !== sel.id); setReviewed(rv); run(mods, rv); }} />}
            </Card>
          </div>
          <div className="grid-3">
            <Card title="Top-k (randomized Quickselect)">
              <Field label="k"><input type="number" min={1} max={100} value={topK} onChange={(e) => setTopK(Number(e.target.value))} /></Field>
              <Button variant="ghost" onClick={() => run(mods, reviewed, topK)}>Recompute top-k</Button>
              <p className="small">k-th highest score: <b>{r.ranking.kthScore}</b> of {r.ranking.candidates} unreviewed changes · {fmt(r.ranking.quickselectComparisons)} comparisons ·
                {r.ranking.matchesSort ? <Badge tone="good">matches full sort</Badge> : <Badge tone="bad">mismatch</Badge>}</p>
              <ol className="small">{r.ranking.topIds.map((id) => <li key={id}><button className="link" onClick={() => setSelected(id)}>{id}</button> score {changeById.get(id)?.score}</li>)}</ol>
              <p className="small muted">{r.ranking.note}</p>
            </Card>
            <Card title="Alert index (red-black tree)">
              <p className="small">{r.alerts.size} alerts · height {r.alerts.height} · black-height {r.alerts.blackHeight} · {r.alerts.rotations} rotations ·
                {r.alerts.removedAfterReview} deleted after review · {r.alerts.invariantsHold ? <Badge tone="good">invariants hold</Badge> : <Badge tone="bad">invariant broken</Badge>}</p>
              <p className="small muted">Key: {r.alerts.key}</p>
              <div className="table-scroll short"><ol className="small">{r.alerts.inOrder.map((s) => <li key={s}>{s}</li>)}</ol></div>
            </Card>
            <Card title="Risk by area strip (segment tree)">
              <div className="strip-bars" aria-label="risk per strip">{r.strips.risk.map((v, i) => (
                <div key={i} className={i >= r.strips.queryFrom && i <= r.strips.queryTo ? 'in' : ''} style={{ height: `${(100 * v) / Math.max(1, ...r.strips.risk)}%` }} title={`strip ${i}: ${v}`} />))}</div>
              <div className="row">
                <Field label="From strip"><input type="number" min={0} max={15} value={strip[0]} onChange={(e) => setStrip([Number(e.target.value), strip[1]])} /></Field>
                <Field label="To strip"><input type="number" min={0} max={15} value={strip[1]} onChange={(e) => setStrip([strip[0], Number(e.target.value)])} /></Field>
              </div>
              <Button variant="ghost" onClick={() => run(mods, reviewed, topK, strip)}>Range query</Button>
              <p className="small">Sum of risk in strips {r.strips.queryFrom}-{r.strips.queryTo}: <b>{r.strips.querySum}</b> {r.strips.matchesBruteForce && <Badge tone="good">matches brute force</Badge>} ·
                hottest strip {r.strips.hottestStrip} (risk {r.strips.hottestRisk}) · {r.strips.updatesApplied} review update(s)</p>
              <p className="small muted">{r.strips.note} Strip 0 is the west edge.</p>
            </Card>
          </div>
          <div className="grid-2">
            <Card title="Bloom-filter screening of updated roads">
              <p className="small">{fmt(r.bloom.trustedFingerprints)} trusted baseline fingerprints in {fmt(r.bloom.bits)} bits, {r.bloom.hashes} hashes (theoretical false-positive rate {fmt(100 * r.bloom.theoreticalFpr, 2)}%).</p>
              <div className="metrics compact">
                <Metric label="Screened" value={fmt(r.bloom.screened)} />
                <Metric label="Definitely new" value={fmt(r.bloom.definitelyNew)} />
                <Metric label="Possibly known" value={fmt(r.bloom.possiblyKnown)} hint={`${fmt(r.bloom.confirmedKnown)} confirmed exactly`} />
                <Metric label="False positives" value={fmt(r.bloom.falsePositives)} tone={r.bloom.falsePositives ? 'warn' : undefined} hint="caught by the exact check" />
              </div>
              <p className="small muted">{r.bloom.note}</p>
            </Card>
            <Card title="Inspection route (metric-TSP approximation)">
              {r.inspection ? (
                <>
                  <p className="small">Order: {r.inspection.tour.join(' → ')}</p>
                  <p className="small">Planned {fmt(r.inspection.plannedKm, 2)} km (MST lower bound {fmt(r.inspection.mstLowerBoundKm, 2)} km, at most {fmt(r.inspection.certifiedRatioBound, 2)}x optimal).</p>
                </>
              ) : null}
              <p className="small muted">{r.inspectionNote}</p>
            </Card>
          </div>
          <Card title="Algorithms used in this analysis">
            <div className="table-scroll"><table className="table small"><thead><tr><th>Algorithm</th><th>Input</th><th>Output</th><th>Complexity</th><th>Limitation</th></tr></thead>
              <tbody>{r.algorithms.map((a) => <tr key={a.algorithm}><td><b>{a.algorithm}</b></td><td>{a.input}</td><td>{a.output}</td><td>{a.complexity}</td><td>{a.limitation}</td></tr>)}</tbody></table></div>
            <Explain e={r.explanation} />
          </Card>
        </>
      )}
    </>
  );
}

function StatusCard({ r }: { r: IntegrityResponse }) {
  const s = r.statusCounts;
  return (
    <Card title="Classification">
      <table className="table small"><tbody>
        <tr><td className="status-LEGITIMATE_REVIEWED">Legitimate (reviewed by you)</td><td className="num">{s.legitimateReviewed}</td></tr>
        <tr><td className="status-ROUTINE_UNREVIEWED">Routine, unreviewed (real OSM edits)</td><td className="num">{s.routineUnreviewed}</td></tr>
        <tr><td className="status-SUSPICIOUS_NEEDS_REVIEW">Suspicious - needs review</td><td className="num">{s.suspiciousNeedsReview}</td></tr>
        <tr><td className="status-SIMULATED_TAMPERING">Simulated tampering (demonstration)</td><td className="num">{s.simulatedTampering}</td></tr>
        <tr><td><b>Verified malicious</b></td><td className="num">{s.verifiedMalicious}</td></tr>
      </tbody></table>
      <p className="small muted">{r.verifiedMaliciousNote}</p>
    </Card>
  );
}

function BeforeAfter({ b, a }: { b: SideSummary; a: SideSummary }) {
  const row = (label: string, x: string, y: string) => <tr><td>{label}</td><td>{x}</td><td>{y}</td><td>{x === y ? '' : <Badge tone="warn">changed</Badge>}</td></tr>;
  const km = (s: SideSummary) => (s.route.reachable ? `${fmt(s.route.distanceM / 1000, 2)} km` : 'UNREACHABLE');
  return (
    <div className="table-scroll"><table className="table small ba-table">
      <thead><tr><th /><th>Trusted baseline</th><th>Updated{a.snapshot.role.includes('DEMONSTRATION') ? ' + demonstration' : ''}</th><th /></tr></thead>
      <tbody>
        {row('OSM data date', (b.snapshot.osmDate ?? '-').slice(0, 10), (a.snapshot.osmDate ?? '-').slice(0, 10))}
        {row('Data class', b.snapshot.dataClass, a.snapshot.dataClass)}
        {row('Roads (ways) / segments / junctions in area', `${b.snapshot.ways} / ${b.snapshot.segments} / ${b.snapshot.nodes}`, `${a.snapshot.ways} / ${a.snapshot.segments} / ${a.snapshot.nodes}`)}
        {row('Connected components (DSU)', String(b.connectivity.components), String(a.connectivity.components))}
        {row('Largest component share', `${fmt(100 * b.connectivity.largestShare, 1)}%`, `${fmt(100 * a.connectivity.largestShare, 1)}%`)}
        {row('Probe route A to B (Dijkstra, length)', km(b), km(a))}
        {row('Fibonacci heap / PriorityQueue ms', `${fmt(b.route.fibonacci.elapsedMs, 2)} / ${fmt(b.route.binary.elapsedMs, 2)}`, `${fmt(a.route.fibonacci.elapsedMs, 2)} / ${fmt(a.route.binary.elapsedMs, 2)}`)}
        {row('Heaps agree', b.route.queuesAgree ? 'yes' : 'NO', a.route.queuesAgree ? 'yes' : 'NO')}
        {row('Road-disjoint routes A to B (EK = FF)', `${b.flow.disjointRoutesEdmondsKarp} = ${b.flow.disjointRoutesFordFulkerson}`, `${a.flow.disjointRoutesEdmondsKarp} = ${a.flow.disjointRoutesFordFulkerson}`)}
        {row('Minimum cut (segments)', `${b.flow.minCutSize}${b.flow.maxFlowEqualsMinCut ? ' = max flow' : ''}`, `${a.flow.minCutSize}${a.flow.maxFlowEqualsMinCut ? ' = max flow' : ''}`)}
        <tr><td>SHA-256 of snapshot file</td><td className="hash">{b.snapshot.fileSha256}</td><td className="hash">{a.snapshot.fileSha256}</td><td /></tr>
        <tr><td>SHA-256 of area road list</td><td className="hash">{b.snapshot.areaSha256}</td><td className="hash">{a.snapshot.areaSha256}</td><td /></tr>
      </tbody></table></div>
  );
}

function ChangeDetail({ c, reviewed, onReview }: { c: Change; reviewed: boolean; onReview: (on: boolean) => void }) {
  return (
    <>
      <p className={`status-${c.status}`}>{STATUS_LABEL[c.status]} · score {c.score}</p>
      <table className="table small"><tbody>
        <tr><td>Origin</td><td>{c.origin === 'DEMONSTRATION' ? <Badge tone="warn">DEMONSTRATION (synthetic)</Badge> : <Badge tone="info">Real OSM edit (public history)</Badge>}</td></tr>
        <tr><td>Kind</td><td>{c.kinds.join(', ')}</td></tr>
        <tr><td>Before</td><td>{c.before}</td></tr>
        <tr><td>After</td><td>{c.after}</td></tr>
        <tr><td>Junctions cut off / joined</td><td>{c.disconnectedNodes}</td></tr>
        <tr><td>On probe route</td><td>{c.onProbeRoute ? 'yes' : 'no'}</td></tr>
        {c.historyUrl && <tr><td>Evidence</td><td><a href={c.historyUrl} target="_blank" rel="noreferrer">OSM edit history of way {c.wayId}</a></td></tr>}
      </tbody></table>
      <b className="small">Why this score</b>
      <List items={c.scoreBreakdown.length ? c.scoreBreakdown : ['0 points: no rule applies']} />
      {c.origin === 'REAL_OSM_EDIT' && (
        <Button variant="ghost" onClick={() => onReview(!reviewed)}>{reviewed ? 'Undo "reviewed legitimate"' : 'Mark as reviewed: legitimate'}</Button>
      )}
      <p className="small muted">Legitimate vs suspicious is a human decision. Check the public edit history and, if possible, the place itself; the score only orders the work.</p>
    </>
  );
}
