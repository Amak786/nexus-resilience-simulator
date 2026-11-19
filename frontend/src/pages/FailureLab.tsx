import { useState } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { FailureLabResponse } from '../lib/types';
import { NetworkMap, type RoadStyle } from '../components/NetworkMap';
import { FailurePicker, noFailures, toggle, type Failures } from '../components/FailurePicker';
import { ImpactHistogram, TimelineChart } from '../components/Charts';
import { Badge, Button, Card, Complexity, ErrorBox, Field, List, Metric, Notice, PageHeader, SyntheticBanner, fmt } from '../components/ui';

const PRESETS: { label: string; roads: string[]; nodes: string[]; note: string }[] = [
  { label: 'Island causeway R22 washed out', roads: ['R22'], nodes: [], note: 'single bridge to Ward 33' },
  { label: 'Riverside Bridge approaches flooded', roads: ['R2', 'R10'], nodes: [], note: 'two roads near J2' },
  { label: 'Central depot D1 offline', roads: [], nodes: ['D1'], note: 'largest depot fails' },
  { label: 'Ring Road East closure', roads: [], nodes: ['J4'], note: 'junction failure' },
];

export function FailureLab() {
  const { dataset } = useAppState();
  const [fail, setFail] = useState<Failures>(() => ({ roads: new Set(['R22']), nodes: new Set() }));
  const [batch, setBatch] = useState(100);
  const [perScenario, setPerScenario] = useState(2);
  const [seed, setSeed] = useState(2026);
  const [worstLen, setWorstLen] = useState(6);
  const req = useRequest<FailureLabResponse>();
  if (!dataset) return null;
  const r = req.data;

  const run = () => req.run(() => api.failureLab({
    dataset, failedRoadIds: [...fail.roads], failedNodeIds: [...fail.nodes], batchScenarios: batch,
    roadsPerScenario: perScenario, seed, worstWindowHours: worstLen, windowHours: 12,
  }));

  const styles: Record<string, RoadStyle> = {};
  r?.minCutAfter.forEach((c) => { if (c.roadId) styles[c.roadId] = { tone: 'cut' }; });
  const pctMarkers = r?.batch.percentiles.map((p) => ({ label: `P${p.percentile}`, value: p.value })) ?? [];

  return (
    <>
      <PageHeader kicker="Failure Lab" title="What happens when infrastructure fails?">
        Pick a failure, then NEXUS re-solves max flow, recomputes connectivity, scans the hourly shortfall with a segment tree,
        ranks the impact against a seeded batch of random failures with Quickselect, and scores recovery actions.
      </PageHeader>
      <SyntheticBanner />
      <div className="grid-2">
        <Card title="1 · Choose a failure">
          <div className="presets">
            {PRESETS.map((p) => (
              <button key={p.label} type="button" className="preset" onClick={() => setFail({ roads: new Set(p.roads), nodes: new Set(p.nodes) })}>
                <b>{p.label}</b><span>{p.note}</span>
              </button>
            ))}
          </div>
          <NetworkMap dataset={dataset} failedRoads={fail.roads} failedNodes={fail.nodes} roadStyles={styles} height={360}
            onRoadClick={(id) => setFail({ ...fail, roads: toggle(fail.roads, id) })}
            onNodeClick={(id) => setFail({ ...fail, nodes: toggle(fail.nodes, id) })} />
          <FailurePicker dataset={dataset} value={fail} onChange={setFail} />
        </Card>
        <Card title="2 · Simulation settings">
          <div className="form-grid">
            <Field label="Random scenarios" hint="10-500, for the impact distribution"><input type="number" min={10} max={500} value={batch} onChange={(e) => setBatch(Number(e.target.value))} /></Field>
            <Field label="Roads failed per scenario" hint="1-5"><input type="number" min={1} max={5} value={perScenario} onChange={(e) => setPerScenario(Number(e.target.value))} /></Field>
            <Field label="Seed" hint="same seed = same scenarios"><input type="number" min={0} value={seed} onChange={(e) => setSeed(Number(e.target.value))} /></Field>
            <Field label="Worst window (hours)" hint="segment-tree interval search"><input type="number" min={1} max={72} value={worstLen} onChange={(e) => setWorstLen(Number(e.target.value))} /></Field>
          </div>
          <div className="row">
            <Button busy={req.busy} onClick={() => void run()}>Run failure simulation</Button>
            <Button variant="ghost" onClick={() => setFail(noFailures())}>Clear failures</Button>
          </div>
          <p className="muted small">Selected: {[...fail.roads, ...fail.nodes].join(', ') || 'nothing (baseline only)'}</p>
          <ErrorBox error={req.error} />
          {r?.warnings.map((w) => <Notice key={w} tone="warn">{w}</Notice>)}
        </Card>
      </div>

      {r && (
        <>
          <div className="metrics">
            <Metric label="Throughput before" value={fmt(r.throughputBefore)} unit="kits / 12 h" />
            <Metric label="Throughput after" value={fmt(r.throughputAfter)} unit="kits / 12 h" tone={r.throughputDrop ? 'warn' : 'good'} />
            <Metric label="Throughput lost" value={fmt(r.throughputDrop)} unit="kits" tone={r.throughputDrop ? 'bad' : 'good'} hint={`${fmt(r.dropPercent, 2)}% of capacity`} />
            <Metric label="Impact percentile" value={`${fmt(r.batch.selectedFailurePercentileRank, 1)}%`}
              hint={`of ${r.batch.scenarios} random ${r.batch.roadsPerScenario}-road failures are no worse`} />
            <Metric label="Newly isolated zones" value={r.newlyIsolatedZones.length ? r.newlyIsolatedZones.join(', ') : 'none'} tone={r.newlyIsolatedZones.length ? 'bad' : 'good'} hint="DSU on the undirected road graph" />
          </div>

          <div className="grid-2">
            <Card title="Recovery actions (ranked by recovered throughput)">
              {r.recoveryActions.length === 0 ? <p className="muted">No candidate actions: nothing failed and no road is on the min cut.</p> : (
                <table className="table"><thead><tr><th>#</th><th>Action</th><th className="num">Throughput after</th><th className="num">Gain</th><th className="num">% of loss</th></tr></thead>
                  <tbody>{r.recoveryActions.map((a, i) => (
                    <tr key={a.type + a.target}><td>{i + 1}</td><td><Badge tone={a.type === 'REINFORCE_ROAD' ? 'info' : 'good'}>{a.type.replace('_', ' ').toLowerCase()}</Badge> {a.description}</td>
                      <td className="num">{fmt(a.throughputAfterAction)}</td><td className="num">+{fmt(a.gain)}</td><td className="num">{r.throughputDrop ? `${fmt(a.recoversPercentOfDrop, 1)}%` : '-'}</td></tr>
                  ))}</tbody></table>
              )}
              <p className="muted small">Each row is a fresh Edmonds-Karp run with that action applied. Decision support only.</p>
            </Card>
            <Card title="Minimum cut: before vs after">
              <div className="two-col">
                <div><h3>Before</h3><ul className="plain-list small">{r.minCutBefore.map((c) => <li key={c.index}>{c.kind === 'ROAD' ? <Badge tone="bad">{c.roadId}</Badge> : <Badge>{c.kind}</Badge>} {c.from} → {c.to} ({fmt(c.capacity)})</li>)}</ul></div>
                <div><h3>After</h3><ul className="plain-list small">{r.minCutAfter.map((c) => <li key={c.index}>{c.kind === 'ROAD' ? <Badge tone="bad">{c.roadId}</Badge> : <Badge>{c.kind}</Badge>} {c.from} → {c.to} ({fmt(c.capacity)})</li>)}</ul></div>
              </div>
              <Complexity time="O(V E²) per Edmonds-Karp solve" space="O(V + E)" note="cut = edges leaving the residual-reachable set" />
            </Card>
          </div>

          {r.timeline && (
            <Card title="Hourly shortfall timeline (segment tree)">
              <TimelineChart demand={r.timeline.demand} capBefore={r.timeline.capacityPerHourBefore} capAfter={r.timeline.capacityPerHourAfter}
                shortfall={r.timeline.shortfallAfter} worst={r.timeline.worstWindowAfter} peakHour={r.timeline.peakShortfallHourAfter.startHour} />
              <div className="metrics compact">
                <Metric label="Total shortfall before" value={fmt(r.timeline.totalShortfallBefore)} unit="kits / 72 h" />
                <Metric label="Total shortfall after" value={fmt(r.timeline.totalShortfallAfter)} unit="kits / 72 h" tone="warn" />
                <Metric label={`Worst ${r.timeline.worstWindowAfter.endHour - r.timeline.worstWindowAfter.startHour + 1}-hour window`}
                  value={`h${r.timeline.worstWindowAfter.startHour}-h${r.timeline.worstWindowAfter.endHour}`} hint={`${fmt(r.timeline.worstWindowAfter.units)} kits short`} />
                <Metric label="Peak shortfall hour" value={`h${r.timeline.peakShortfallHourAfter.startHour}`} hint={`${fmt(r.timeline.peakShortfallHourAfter.units)} kits/h (range max)`} />
                <Metric label="Segment-tree nodes visited" value={fmt(r.timeline.segmentTreeNodesVisited)} hint="all range queries for this page" />
              </div>
              <Complexity time="O(log n) per range sum / max / add" space="O(n)" note="worst window = range sums over every start hour" />
            </Card>
          )}

          <div className="grid-2">
            <Card title="Impact distribution (randomized Quickselect)">
              <ImpactHistogram impacts={r.batch.impacts} markers={pctMarkers} selected={r.throughputDrop} />
              <table className="table"><thead><tr><th>Percentile</th><th className="num">Throughput lost</th><th className="num">Comparisons</th><th>Matches full sort</th></tr></thead>
                <tbody>{r.batch.percentiles.map((p) => <tr key={p.percentile}><td>P{p.percentile}</td><td className="num">{fmt(p.value)}</td><td className="num">{fmt(p.comparisons)}</td><td>{p.matchesSortBaseline ? <Badge tone="good">yes</Badge> : <Badge tone="bad">no</Badge>}</td></tr>)}</tbody></table>
              <p className="muted small">Sort baseline ≈ {fmt(r.batch.sortBaselineComparisons)} comparisons (n log₂ n). Seed {r.batch.seed}.</p>
            </Card>
            <Card title="Zones and connectivity">
              <table className="table"><thead><tr><th>Zone</th><th className="num">Before</th><th className="num">After</th><th className="num">Lost</th></tr></thead>
                <tbody>{r.zones.filter((z) => z.lostUnits !== 0 || z.receivedAfter < z.demand).map((z) => (
                  <tr key={z.id}><td>{z.id} · {z.name}</td><td className="num">{fmt(z.receivedBefore)}</td><td className="num">{fmt(z.receivedAfter)}</td><td className={`num ${z.lostUnits > 0 ? 'warn-text' : ''}`}>{fmt(z.lostUnits)}</td></tr>
                ))}</tbody></table>
              <p className="small">Components: {r.connectivityBefore.componentCount} before → {r.connectivityAfter.componentCount} after
                {r.connectivityAfter.isolatedZoneIds.length > 0 && <> · cut off from every depot: <b>{r.connectivityAfter.isolatedZoneIds.join(', ')}</b></>}</p>
            </Card>
          </div>
          <Card title="Assumptions"><List items={r.assumptions} /></Card>
        </>
      )}
    </>
  );
}
