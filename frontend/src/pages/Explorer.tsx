import { useMemo, useState } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { RouteResponse, Segment } from '../lib/types';
import { MumbaiMap, defaultStyle, type Marker, type Overlay } from '../components/MumbaiMap';
import { Badge, Button, Card, Complexity, ErrorBox, Field, List, PageHeader, fmt } from '../components/ui';

export function Explorer() {
  const { network, provenance } = useAppState();
  const [points, setPoints] = useState<{ lat: number; lon: number }[]>([]);
  const [metric, setMetric] = useState<'TIME' | 'DISTANCE'>('TIME');
  const [selected, setSelected] = useState<Segment | null>(null);
  const route = useRequest<RouteResponse>();
  const r = route.data;

  const markers: Marker[] = useMemo(() => points.map((p, i) => ({ ...p, label: i === 0 ? 'A' : 'B', color: i === 0 ? '#16a34a' : '#dc2626' })), [points]);
  const overlays: Overlay[] = useMemo(() => {
    const o: Overlay[] = [];
    r?.geometry.forEach((g) => { const c: number[][] = []; for (let i = 0; i + 1 < g.length; i += 2) c.push([g[i], g[i + 1]]); o.push({ coords: c, color: '#7c3aed', weight: 6 }); });
    if (selected) { const c: number[][] = []; for (let i = 0; i + 1 < selected.geometry.length; i += 2) c.push([selected.geometry[i], selected.geometry[i + 1]]); o.push({ coords: c, color: '#f59e0b', weight: 8 }); }
    return o;
  }, [r, selected]);

  if (!network || !provenance) return null;
  const defaults = provenance.assumptions.classDefaults[selected?.roadClass ?? ''];

  const click = (lat: number, lon: number) => {
    const next = points.length >= 2 ? [{ lat, lon }] : [...points, { lat, lon }];
    setPoints(next);
    route.setData(null);
  };

  return (
    <>
      <PageHeader kicker="Network Explorer" title="Inspect roads and find routes">
        Click the map twice to set A and B, then compute the shortest (real length) or fastest (estimated free-flow time) route.
        Click a road to inspect its OpenStreetMap attributes.
      </PageHeader>
      <div className="grid-main">
        <Card>
          <MumbaiMap segments={network.items} styleOf={defaultStyle} markers={markers} overlays={overlays} onMapClick={click}
            onSegmentClick={setSelected} height={600} />
        </Card>
        <div className="stack">
          <Card title="Route">
            <p className="small">{points.length === 0 ? 'Click the map to set point A.' : points.length === 1 ? 'Now click to set point B.' : 'Both points set. Click again to start over.'}</p>
            <Field label="Optimise for"><select value={metric} onChange={(e) => setMetric(e.target.value as 'TIME' | 'DISTANCE')}>
              <option value="TIME">Fastest (derived travel-time estimate)</option><option value="DISTANCE">Shortest (real road length)</option></select></Field>
            <div className="row">
              <Button busy={route.busy} disabled={points.length < 2}
                onClick={() => void route.run(() => api.route({ fromLat: points[0].lat, fromLon: points[0].lon, toLat: points[1].lat, toLon: points[1].lon, metric }))}>Find route</Button>
              <Button variant="ghost" onClick={() => { setPoints([]); route.setData(null); }}>Clear</Button>
            </div>
            <ErrorBox error={route.error} />
            {r && (r.reachable ? (
              <>
                <div className="metrics compact">
                  <div className="metric"><div className="metric-label">Length</div><div className="metric-value">{fmt(r.distanceM / 1000, 2)} <span className="metric-unit">km</span></div></div>
                  <div className="metric"><div className="metric-label">Est. free-flow time</div><div className="metric-value">{fmt(r.travelTimeS / 60, 1)} <span className="metric-unit">min</span></div></div>
                </div>
                <table className="table small"><thead><tr><th>Priority queue</th><th className="num">Settled</th><th className="num">Heap ops</th><th className="num">ms</th></tr></thead>
                  <tbody>{[r.fibonacci, r.binary].map((q) => <tr key={q.queue}><td>{q.queue}</td><td className="num">{fmt(q.settled)}</td><td className="num">{fmt(q.heapOperations)}</td><td className="num">{fmt(q.elapsedMs, 2)}</td></tr>)}</tbody></table>
                <p className="small">{r.queuesAgree ? <Badge tone="good">both heaps give the same distance</Badge> : <Badge tone="bad">mismatch</Badge>}</p>
                <details><summary>Turn-by-turn roads ({r.steps.length})</summary>
                  <ol className="small">{r.steps.map((s, i) => <li key={i}>{s.name} <span className="muted">{fmt(s.lengthM)} m</span></li>)}</ol></details>
                <p className="small muted">Snapped A to OSM node {r.from.osmNodeId} ({fmt(r.from.snapDistanceM)} m away), B to {r.to.osmNodeId} ({fmt(r.to.snapDistanceM)} m).</p>
              </>
            ) : <p className="warn-text">No directed route exists between these points in the arterial graph.</p>)}
            {r && <List items={r.notes} />}
            <Complexity time="Dijkstra: O(E + V log V) with a Fibonacci heap" space="O(V + E)" />
          </Card>
          <Card title="Selected road">
            {!selected ? <p className="muted small">Click a road on the map.</p> : (
              <table className="table small"><tbody>
                <tr><td>Name</td><td>{selected.name || '(unnamed)'}</td></tr>
                <tr><td>Class</td><td>{selected.roadClass}</td></tr>
                <tr><td>Direction</td><td>{selected.oneWay ? 'one-way' : 'two-way'}</td></tr>
                <tr><td>Length <Badge tone="info">static</Badge></td><td>{fmt(selected.lengthM)} m</td></tr>
                <tr><td>lanes tag</td><td>{selected.lanesTag ?? <span className="muted">absent → default {defaults?.lanes}/direction</span>}</td></tr>
                <tr><td>maxspeed tag</td><td>{selected.maxspeedTag ? `${selected.maxspeedTag} km/h` : <span className="muted">absent → default {defaults?.speed} km/h</span>}</td></tr>
                <tr><td>Travel time <Badge tone="warn">derived</Badge></td><td>{fmt(selected.travelTimeS, 1)} s</td></tr>
                <tr><td>Capacity <Badge tone="warn">proxy</Badge></td><td>{fmt(selected.capacityVph)} veh/h per direction</td></tr>
                <tr><td>OSM way</td><td><a href={`https://www.openstreetmap.org/way/${selected.wayId}`} target="_blank" rel="noreferrer">{selected.wayId}</a></td></tr>
              </tbody></table>
            )}
          </Card>
        </div>
      </div>
    </>
  );
}
