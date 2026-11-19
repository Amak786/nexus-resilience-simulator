import { useState } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { ComponentsSummary, ResilienceResponse } from '../lib/types';
import { NetworkMap } from '../components/NetworkMap';
import { FailurePicker, toggle, type Failures } from '../components/FailurePicker';
import { Badge, Button, Card, Complexity, ErrorBox, List, Metric, PageHeader, fmt } from '../components/ui';

const PALETTE = ['#2f6fd6', '#d9822b', '#2e9e6b', '#b54ac9', '#c7413a', '#3aa5b8', '#8a6d1f', '#5b5fc7'];

function tones(c: ComponentsSummary): Record<string, string> {
  const out: Record<string, string> = {};
  c.components.forEach((comp, i) => comp.nodeIds.forEach((id) => { out[id] = comp.hasDepot ? PALETTE[i % PALETTE.length] : '#9aa4b2'; }));
  return out;
}

function ComponentTable({ c }: { c: ComponentsSummary }) {
  return (
    <table className="table small"><thead><tr><th>Group</th><th>Nodes</th><th className="num">Supply</th><th className="num">Demand</th><th>Depot?</th></tr></thead>
      <tbody>{c.components.map((comp, i) => (
        <tr key={comp.id}><td><span className="swatch" style={{ background: comp.hasDepot ? PALETTE[i % PALETTE.length] : '#9aa4b2' }} /> {comp.id + 1}</td>
          <td>{comp.nodeIds.join(', ')}</td><td className="num">{fmt(comp.totalSupply)}</td><td className="num">{fmt(comp.totalDemand)}</td>
          <td>{comp.hasDepot ? <Badge tone="good">yes</Badge> : <Badge tone="bad">isolated</Badge>}</td></tr>))}</tbody></table>
  );
}

export function Resilience() {
  const { dataset } = useAppState();
  const [fail, setFail] = useState<Failures>({ roads: new Set(['R22', 'R25']), nodes: new Set() });
  const req = useRequest<ResilienceResponse>();
  if (!dataset) return null;
  const r = req.data;
  return (
    <>
      <PageHeader kicker="Infrastructure resilience" title="Which parts of the city stay physically connected?">
        A separate <b>undirected</b> graph: one edge per operating road, ignoring direction and capacity. Disjoint-set union groups the nodes into
        connected components. DSU cannot delete edges, so the "after" state is rebuilt from the modified graph.
      </PageHeader>
      <div className="grid-2">
        <Card title="Failures">
          <FailurePicker dataset={dataset} value={fail} onChange={setFail} />
          <div className="row"><Button busy={req.busy} onClick={() => void req.run(() => api.resilience({ dataset, failedRoadIds: [...fail.roads], failedNodeIds: [...fail.nodes], scanCriticalRoads: true }))}>Analyse connectivity</Button></div>
          <ErrorBox error={req.error} />
          {r && (
            <div className="metrics compact">
              <Metric label="Components before" value={r.before.componentCount} />
              <Metric label="Components after" value={r.after.componentCount} tone={r.after.componentCount > r.before.componentCount ? 'warn' : 'good'} />
              <Metric label="Newly isolated zones" value={r.newlyIsolatedZones.join(', ') || 'none'} tone={r.newlyIsolatedZones.length ? 'bad' : 'good'} />
              <Metric label="Unions (merged / redundant)" value={`${r.after.successfulUnions} / ${r.after.redundantUnions}`} />
            </div>
          )}
          <Complexity time="O((V + E) α(V)) per rebuild; critical scan O(E (V + E) α(V))" space="O(V)" />
        </Card>
        <Card title={r ? 'Components after failure (colour = group, grey = no depot)' : 'Map'}>
          <NetworkMap dataset={dataset} failedRoads={fail.roads} failedNodes={fail.nodes} nodeTone={r ? tones(r.after) : undefined} height={380}
            onRoadClick={(id) => setFail({ ...fail, roads: toggle(fail.roads, id) })} onNodeClick={(id) => setFail({ ...fail, nodes: toggle(fail.nodes, id) })} />
        </Card>
      </div>
      {r && (
        <>
          <div className="grid-2">
            <Card title="Before failure"><ComponentTable c={r.before} /></Card>
            <Card title="After failure"><ComponentTable c={r.after} /></Card>
          </div>
          <div className="grid-2">
            <Card title="Critical roads (single failure splits the network)">
              {r.criticalRoads.length === 0 ? <p className="muted">No single remaining road disconnects the network.</p> : (
                <table className="table small"><thead><tr><th>Road</th><th>Ends</th><th className="num">Components if removed</th><th>Zones cut off from depots</th></tr></thead>
                  <tbody>{r.criticalRoads.map((c) => <tr key={c.roadId}><td><Badge tone="bad">{c.roadId}</Badge></td><td>{c.from} - {c.to}</td><td className="num">{c.componentsIfRemoved}</td><td>{c.zonesCutOffFromDepots.join(', ') || '-'}</td></tr>)}</tbody></table>
              )}
              <p className="muted small">Found by rebuilding the DSU once per road on the post-failure graph.</p>
            </Card>
            <Card title="Union trace (post-failure build)">
              <div className="table-scroll short"><table className="table small"><thead><tr><th>#</th><th>Road</th><th>Union</th><th>Result</th></tr></thead>
                <tbody>{r.unionTrace.map((u, i) => <tr key={i}><td>{i + 1}</td><td>{u.roadId}</td><td>{u.a} ∪ {u.b}</td><td>{u.merged ? <Badge tone="good">merged</Badge> : <Badge>already connected</Badge>}</td></tr>)}</tbody></table></div>
            </Card>
          </div>
          <Card title="Notes"><List items={r.notes} /></Card>
        </>
      )}
    </>
  );
}
