import { useMemo, useState } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { RouteResponse } from '../lib/types';
import { MumbaiMap, pairs, type Overlay } from '../components/MumbaiMap';
import { PlaceSearch } from './FindRoute';
import { Button, Card, DataBadge, ErrorBox, Metric, Notice, PageHeader, fmt } from '../components/ui';

export function Overview() {
  const { provenance: p, network } = useAppState();
  const [from, setFrom] = useState<{ lat: number; lon: number; label: string } | null>(null);
  const [to, setTo] = useState<{ lat: number; lon: number; label: string } | null>(null);
  const route = useRequest<RouteResponse>();
  const overlays: Overlay[] = useMemo(() => (route.data?.geometry ?? []).map((g) => ({ coords: pairs(g), color: '#7c3aed', weight: 6 })), [route.data]);
  if (!p || !network) return null;
  const baseline = p.sources.find((s) => s.id === 'baseline');
  return (
    <>
      <PageHeader kicker="Overview" title="Nexus Resilience: Silent Shield">
        Routing and emergency-planning tools trust their road dataset. If that dataset is silently changed - by a normal map edit, a
        mistake or deliberate tampering - routes and connectivity change too. Silent Shield compares a trusted snapshot of Mumbai's real
        OpenStreetMap road network with an updated one, shows what changed and what it affects, and ranks changes for human review.
      </PageHeader>
      <Notice tone="warn"><b>Live traffic unavailable.</b> {p.liveTrafficStatus}</Notice>
      <div className="metrics">
        <Metric label="Junctions (updated snapshot)" value={fmt(p.graph.nodes)} hint="snapshot count, not live" />
        <Metric label="Road segments" value={fmt(p.graph.segments)} hint={`${fmt(p.graph.oneWaySegments)} one-way`} />
        <Metric label="Mapped length" value={fmt(p.graph.totalKm)} unit="km" hint="motorway to tertiary" />
        <Metric label="Largest connected part" value={`${fmt(100 * p.graph.connectivity.largestComponentShare, 1)}%`} hint={`${p.graph.connectivity.weakComponents} components (DSU)`} />
      </div>
      <div className="grid-main">
        <Card title="Mumbai arterial road network (OpenStreetMap)">
          <MumbaiMap segments={network.items} area={p.studyArea} overlays={overlays}
            markers={[...(from ? [{ ...from, label: 'A', color: '#16a34a' }] : []), ...(to ? [{ ...to, label: 'B', color: '#dc2626' }] : [])]} height={540} />
          <p className="small"><b>Connectivity insight:</b> {p.graph.connectivity.summary}</p>
        </Card>
        <div className="stack">
          <Card title="Start here">
            <ol className="small">
              <li><a href="#/integrity">Integrity Check</a> - compare the trusted baseline with the updated snapshot (main workflow).</li>
              <li><a href="#/route">Find Route</a> and <a href="#/connectivity">Network Connectivity</a> - what a change does.</li>
              <li><a href="#/lab">Algorithm Lab</a> - each algorithm on small inputs, tests and benchmarks.</li>
            </ol>
          </Card>
          <Card title="Quick route">
            <PlaceSearch label="From" value={from} onPick={setFrom} />
            <PlaceSearch label="To" value={to} onPick={setTo} />
            <Button busy={route.busy} disabled={!from || !to} onClick={() => void route.run(() => api.route(from!, to!, 'DISTANCE', []))}>Find Route</Button>
            <ErrorBox error={route.error} />
            {route.data && (route.data.reachable
              ? <p className="small">Shortest by road length: <b>{fmt(route.data.distanceM / 1000, 2)} km</b> (Dijkstra, Fibonacci heap {fmt(route.data.fibonacci.elapsedMs, 1)} ms vs PriorityQueue {fmt(route.data.binary.elapsedMs, 1)} ms).</p>
              : <p className="small warn-text">Unreachable in the arterial graph.</p>)}
          </Card>
          <Card title="Data sources and freshness">
            <ul className="sources">
              {p.sources.map((s) => (
                <li key={s.id}>
                  <div><DataBadge c={s.dataClass} /> <b>{s.name}</b> <span className="muted small">{s.status}</span></div>
                  <div className="small muted">{s.freshness}{s.dataTimestamp ? ` · data as of ${s.dataTimestamp.slice(0, 10)}` : ''}</div>
                </li>
              ))}
            </ul>
            {!baseline && <p className="small muted">Trusted baseline: OpenStreetMap state on 2025-11-20 (Overpass history query), used by the Integrity Check.</p>}
          </Card>
        </div>
      </div>
      <Card title="Data classes used everywhere in this app">
        <div className="legend-grid">
          <div><DataBadge c="REAL_MAPPED" /> Mapped by OpenStreetMap contributors (roads, junctions, one-way rules).</div>
          <div><DataBadge c="VERIFIED_OBSERVATION" /> From a documented provider with timestamp (OSM map notes - not traffic incidents).</div>
          <div><DataBadge c="DERIVED_RESULT" /> Calculated by an algorithm from real data (routes, components, cuts, change scores).</div>
          <div><DataBadge c="ESTIMATE" /> An assumption because no measurement exists (free-flow speed, lane capacity).</div>
          <div><DataBadge c="SAMPLE" /> Sample or demonstration input (Algorithm Lab, synthetic tampering).</div>
          <div><DataBadge c="NOT_AVAILABLE" /> No verified source (live traffic). Nothing fake is shown instead.</div>
        </div>
      </Card>
    </>
  );
}
