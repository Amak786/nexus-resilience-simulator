import { useCallback, useMemo, useState } from 'react';
import { api } from '../lib/api';
import { PRESET_AREAS, useAppState, useRequest } from '../lib/state';
import type { Area, CriticalLinksResponse, Segment } from '../lib/types';
import { MumbaiMap, type Marker, type SegStyle } from '../components/MumbaiMap';
import { Badge, Button, Card, Complexity, ErrorBox, Explain, Field, Metric, Notice, PageHeader, fmt } from '../components/ui';
import type { CapacityModel } from '../lib/api';

const insideArea = (a: Area, s: Segment) => {
  const ok = (i: number) => s.geometry[i] >= a.south && s.geometry[i] <= a.north && s.geometry[i + 1] >= a.west && s.geometry[i + 1] <= a.east;
  return ok(0) && ok(s.geometry.length - 2);
};

export function CriticalLinks() {
  const { network } = useAppState();
  const [areaId, setAreaId] = useState(PRESET_AREAS[0].id);
  const area = PRESET_AREAS.find((p) => p.id === areaId)!.area;
  const [points, setPoints] = useState<{ lat: number; lon: number }[]>([]);
  const [removed, setRemoved] = useState<Set<number>>(new Set());
  const [model, setModel] = useState<CapacityModel>('UNIT');
  const req = useRequest<CriticalLinksResponse>();
  const r = req.data;

  const segs = useMemo(() => (network ? network.items.filter((s) => insideArea(area, s)) : []), [network, area]);
  const cutIds = useMemo(() => new Set(r?.cut.map((c) => c.segmentId) ?? []), [r]);
  const flowById = useMemo(() => {
    const m = new Map<number, number>();
    r?.segmentFlows.forEach(([id, f]) => m.set(id, (m.get(id) ?? 0) + f));
    return m;
  }, [r]);
  const styleOf = useCallback((s: Segment): SegStyle => {
    if (removed.has(s.id)) return { color: '#94a3b8', weight: 3, dash: '4 4' };
    if (cutIds.has(s.id)) return { color: '#dc2626', weight: 7 };
    const f = flowById.get(s.id);
    if (f) return { color: '#2563eb', weight: 2 + 5 * Math.min(1, f / (r?.capacityModel === 'UNIT' ? 1 : Math.max(1, s.capacityVph))) };
    return { color: '#94a3b8', weight: 1.5, opacity: 0.7 };
  }, [removed, cutIds, flowById, r]);
  const markers: Marker[] = useMemo(() => {
    const m: Marker[] = points.map((p, i) => ({ ...p, label: i === 0 ? 'Source' : 'Sink', color: i === 0 ? '#16a34a' : '#dc2626' }));
    return m;
  }, [points]);

  if (!network) return null;
  const run = () => void req.run(() => api.criticalLinks(area, points[0], points[1], [...removed], model));

  return (
    <>
      <PageHeader kicker="Critical Links" title="Which roads limit movement between two places?">
        No measured road capacities exist for Mumbai. The default <b>structural model</b> gives every directed segment capacity 1, so
        max flow = number of road-disjoint routes and the minimum cut = fewest segments whose closure separates the two points (Menger).
        The lane model is an opt-in <b>assumption</b>, not measured throughput.
      </PageHeader>
      <div className="grid-main">
        <Card>
          <MumbaiMap segments={segs} styleOf={styleOf} markers={markers} area={area} fitArea={area} height={600}
            onMapClick={(lat, lon) => { setPoints(points.length >= 2 ? [{ lat, lon }] : [...points, { lat, lon }]); req.setData(null); }}
            onSegmentClick={(s) => { const n = new Set(removed); if (n.has(s.id)) n.delete(s.id); else n.add(s.id); setRemoved(n); req.setData(null); }} />
          <p className="small muted">Click the map to set source then sink. Click a road to remove it (simulate a closure); click again to restore.
            <span className="lg-line cut" /> min cut <span className="lg-line flow" /> carries flow <span className="lg-line removed" /> removed</p>
        </Card>
        <div className="stack">
          <Card title="Analysis">
            <Field label="Area (bounded subgraph)"><select value={areaId} onChange={(e) => { setAreaId(e.target.value); setPoints([]); setRemoved(new Set()); req.setData(null); }}>
              {PRESET_AREAS.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}</select></Field>
            <Field label="Capacity model"><select value={model} onChange={(e) => { setModel(e.target.value as CapacityModel); req.setData(null); }}>
              <option value="UNIT">Structural: capacity 1 per segment (default)</option><option value="LANE_ASSUMPTION">Assumed lanes x per-lane rule (D · estimate)</option></select></Field>
            <p className="small">{segs.length} segments in area · source {points[0] ? 'set' : 'not set'} · sink {points[1] ? 'set' : 'not set'} · {removed.size} removed</p>
            <div className="row">
              <Button busy={req.busy} disabled={points.length < 2} onClick={run}>Run max flow / min cut</Button>
              <Button variant="ghost" onClick={() => { setPoints([]); setRemoved(new Set()); req.setData(null); }}>Reset</Button>
            </div>
            <ErrorBox error={req.error} />
            {r?.warnings.map((w) => <Notice key={w} tone="warn">{w}</Notice>)}
            {r && (
              <>
                <div className="metrics compact">
                  <Metric label="Max flow" value={fmt(r.maxFlow)} unit={r.unit} />
                  <Metric label="Min-cut capacity" value={fmt(r.minCutCapacity)} unit={r.capacityModel === 'UNIT' ? 'segments' : 'assumed veh/h'} tone={r.maxFlowEqualsMinCut ? 'good' : 'bad'} hint={r.maxFlowEqualsMinCut ? 'equal: certified' : 'mismatch'} />
                </div>
                <table className="table small"><thead><tr><th>Algorithm</th><th className="num">Value</th><th className="num">Paths</th><th className="num">Arc scans</th><th className="num">ms</th></tr></thead>
                  <tbody>{[r.edmondsKarp, r.fordFulkerson].map((s) => <tr key={s.algorithm}><td>{s.algorithm}</td><td className="num">{fmt(s.maxFlow)}</td><td className="num">{s.augmentations}</td><td className="num">{fmt(s.arcScans)}</td><td className="num">{fmt(s.elapsedMs, 2)}</td></tr>)}</tbody></table>
                <p className="small">{r.algorithmsAgree ? <Badge tone="good">same max-flow value</Badge> : <Badge tone="bad">values differ</Badge>} {r.flowVerified && <Badge tone="good">conservation and capacity verified</Badge>}</p>
              </>
            )}
            <Complexity time="Edmonds-Karp O(V E²); Ford-Fulkerson O(E·|f*|)" space="O(V + E)" />
          </Card>
          {r && (
            <Card title={`Minimum cut (${r.cut.length} directed segments)`}>
              <table className="table small"><thead><tr><th>Road</th><th>Class</th><th className="num">Capacity ({r.capacityModel === 'UNIT' ? 'unit' : 'assumed'})</th></tr></thead>
                <tbody>{r.cut.map((c, i) => <tr key={i}><td>{c.name || '(unnamed)'}</td><td>{c.roadClass}</td><td className="num">{fmt(c.capacity)}</td></tr>)}</tbody></table>
              <Explain e={r.explanation} />
            </Card>
          )}
        </div>
      </div>
    </>
  );
}
