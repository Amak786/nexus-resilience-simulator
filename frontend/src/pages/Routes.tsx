import { useState } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { RouteResponse } from '../lib/types';
import { NetworkMap } from '../components/NetworkMap';
import { FailurePicker, noFailures, type Failures } from '../components/FailurePicker';
import { Badge, Button, Card, Complexity, ErrorBox, Field, List, Metric, Notice, PageHeader, fmt } from '../components/ui';

export function Routes() {
  const { dataset } = useAppState();
  const [depot, setDepot] = useState('D1');
  const [stops, setStops] = useState<Set<string>>(new Set(['Z1', 'Z2', 'Z3', 'Z5', 'Z7', 'Z10']));
  const [fail, setFail] = useState<Failures>(noFailures());
  const req = useRequest<RouteResponse>();
  if (!dataset) return null;
  const r = req.data;
  const depots = dataset.nodes.filter((n) => n.type === 'DEPOT');
  const toggleStop = (id: string) => { const s = new Set(stops); if (s.has(id)) s.delete(id); else s.add(id); setStops(s); };

  return (
    <>
      <PageHeader kicker="Delivery route planner" title="One-vehicle relief tour (metric TSP 2-approximation)">
        Distances come from road <b>lengths in km</b> (shortest paths), never from capacities. The matrix is validated as a metric, then the tour is
        built from a minimum spanning tree walked in preorder. It is approximate: at most twice the optimum.
      </PageHeader>
      <div className="grid-2">
        <Card title="Stops">
          <Field label="Depot (start and end)"><select value={depot} onChange={(e) => setDepot(e.target.value)}>
            {depots.map((d) => <option key={d.id} value={d.id}>{d.id} · {d.name}</option>)}</select></Field>
          <div className="chips">{dataset.nodes.filter((n) => n.type !== 'DEPOT').map((n) => (
            <button key={n.id} type="button" className={`chip ${stops.has(n.id) ? 'on' : ''}`} aria-pressed={stops.has(n.id)} onClick={() => toggleStop(n.id)} title={n.name}>{n.id}</button>))}</div>
          <details><summary>Simulate failures before routing</summary><FailurePicker dataset={dataset} value={fail} onChange={setFail} /></details>
          <div className="row"><Button busy={req.busy} disabled={stops.size === 0} onClick={() => void req.run(() => api.route({ dataset, depotId: depot, stopIds: [...stops], failedRoadIds: [...fail.roads], failedNodeIds: [...fail.nodes] }))}>Plan tour</Button></div>
          <ErrorBox error={req.error} />
          {r && r.unreachableStops.length > 0 && <Notice tone="warn">Unreachable from {depot} with the current failures, excluded explicitly: <b>{r.unreachableStops.join(', ')}</b></Notice>}
          <Complexity time="O(n²) Prim + O(n³) metric check + Dijkstra per stop" space="O(n²)" />
        </Card>
        <Card title="Tour">
          {!r ? <p className="muted">Choose stops and plan a tour.</p> : (
            <>
              <div className="metrics compact">
                <Metric label="Approximate tour" value={fmt(r.routeKm, 2)} unit="km" tone="good" />
                <Metric label="Nearest-neighbour baseline" value={fmt(r.nearestNeighbourKm, 2)} unit="km" />
                <Metric label="MST lower bound" value={fmt(r.mstLowerBoundKm, 2)} unit="km" hint="OPT ≥ MST" />
                <Metric label="Certified ratio bound" value={`≤ ${fmt(r.certifiedRatioBound, 3)}`} hint="tour / MST ≥ tour / OPT" />
                {r.exactKm !== null && <Metric label="Exact optimum (≤10 stops)" value={fmt(r.exactKm, 2)} unit="km" hint={`actual ratio ${fmt(r.actualRatio, 3)}`} />}
              </div>
              <ol className="tour">{r.legs.map((l, i) => <li key={i}><b>{l.from}</b> → <b>{l.to}</b> <span className="muted">{fmt(l.km, 2)} km</span></li>)}</ol>
              <p className="small"><Badge tone="info">guarantee</Badge> {r.guarantee}</p>
            </>
          )}
        </Card>
      </div>
      {r && (
        <div className="grid-2">
          <Card title="Tour sketch (straight lines between stops; vehicles follow the roads)">
            <NetworkMap dataset={dataset} failedRoads={fail.roads} failedNodes={fail.nodes} overlayPath={[...r.tour, r.tour[0]]} highlightNodes={new Set(r.tour)} height={380} />
          </Card>
          <Card title="Validated distance matrix (km)">
            <div className="table-scroll"><table className="table small matrix"><thead><tr><th />{r.matrixLabels.map((l) => <th key={l}>{l}</th>)}</tr></thead>
              <tbody>{r.matrix.map((row, i) => <tr key={i}><th>{r.matrixLabels[i]}</th>{row.map((v, j) => <td key={j} className="num">{fmt(v, 1)}</td>)}</tr>)}</tbody></table></div>
            <List items={r.assumptions} />
          </Card>
        </div>
      )}
    </>
  );
}
