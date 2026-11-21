import { useMemo, useState } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { TourResponse } from '../lib/types';
import { MumbaiMap, defaultStyle, type Marker, type Overlay } from '../components/MumbaiMap';
import { Badge, Button, Card, Complexity, ErrorBox, Explain, List, Metric, Notice, PageHeader, fmt } from '../components/ui';

export function Planner() {
  const { network } = useAppState();
  const [stops, setStops] = useState<{ label: string; lat: number; lon: number }[]>([]);
  const req = useRequest<TourResponse>();
  const r = req.data;

  const markers: Marker[] = useMemo(() => stops.map((s, i) => ({ lat: s.lat, lon: s.lon, label: s.label, color: i === 0 ? '#16a34a' : '#2563eb' })), [stops]);
  const overlays: Overlay[] = useMemo(() => (r?.geometry ?? []).map((g) => {
    const c: number[][] = [];
    for (let i = 0; i + 1 < g.length; i += 2) c.push([g[i], g[i + 1]]);
    return { coords: c, color: '#7c3aed', weight: 5 };
  }), [r]);

  if (!network) return null;
  return (
    <>
      <PageHeader kicker="Multi-stop Planner" title="Visit several real locations in a good order">
        Click the map to add up to 15 stops (the first is the start). NEXUS computes shortest road distances with Dijkstra, builds a metric
        planning matrix and returns an MST-preorder tour that is at most twice the optimal planning length. It is a planning order, not live traffic-optimised navigation.
      </PageHeader>
      <div className="grid-main">
        <Card>
          <MumbaiMap segments={network.items} styleOf={defaultStyle} markers={markers} overlays={overlays} height={600}
            onMapClick={(lat, lon) => { if (stops.length < 15) { setStops([...stops, { label: stops.length === 0 ? 'Start' : `Stop ${stops.length}`, lat, lon }]); req.setData(null); } }} />
        </Card>
        <div className="stack">
          <Card title={`Stops (${stops.length}/15)`}>
            {stops.length === 0 ? <p className="muted small">Click the map to add the start point.</p> : (
              <ol className="small">{stops.map((s, i) => <li key={i}>{s.label} <span className="muted">{s.lat.toFixed(4)}, {s.lon.toFixed(4)}</span></li>)}</ol>
            )}
            <div className="row">
              <Button busy={req.busy} disabled={stops.length < 2} onClick={() => void req.run(() => api.tour(stops))}>Plan tour</Button>
              <Button variant="ghost" onClick={() => { setStops([]); req.setData(null); }}>Clear</Button>
            </div>
            <ErrorBox error={req.error} />
            {r?.excludedStops.length ? <Notice tone="warn">Excluded (not reachable both ways): {r.excludedStops.join('; ')}</Notice> : null}
            <Complexity time="k Dijkstra runs + O(k²) Prim + O(k³) metric check" space="O(k² + V)" />
          </Card>
          {r && (
            <Card title="Tour">
              <div className="metrics compact">
                <Metric label="Planned tour" value={fmt(r.plannedKm, 2)} unit="km" />
                <Metric label="Executable road route" value={fmt(r.executableKm, 2)} unit="km" hint="directed shortest paths, ≤ planned" />
                <Metric label="Nearest-neighbour baseline" value={fmt(r.nearestNeighbourKm, 2)} unit="km" />
                <Metric label="MST lower bound" value={fmt(r.mstLowerBoundKm, 2)} unit="km" hint={`ratio bound ≤ ${fmt(r.certifiedRatioBound, 2)}`} />
                {r.exactKm !== null && <Metric label="Exact optimum (≤10 stops)" value={fmt(r.exactKm, 2)} unit="km" />}
              </div>
              <ol className="small">{r.legs.map((l, i) => <li key={i}><b>{l.from}</b> → <b>{l.to}</b>: planned {fmt(l.plannedKm, 2)} km, drive {fmt(l.executableKm, 2)} km</li>)}</ol>
              <p className="small"><Badge tone="info">guarantee</Badge> {r.guarantee}</p>
              <List items={r.assumptions} />
              <Explain e={r.explanation} />
            </Card>
          )}
        </div>
      </div>
    </>
  );
}
