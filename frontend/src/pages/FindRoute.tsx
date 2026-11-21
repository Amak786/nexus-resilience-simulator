import { useEffect, useMemo, useState } from 'react';
import { api, type LatLon, type Metric } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { RouteResponse, SearchResponse, Segment } from '../lib/types';
import { MumbaiMap, defaultStyle, pairs, type Marker, type Overlay } from '../components/MumbaiMap';
import { Badge, Button, Card, Complexity, ErrorBox, Explain, Field, Metric as MetricBox, Notice, PageHeader, fmt } from '../components/ui';

interface Place extends LatLon { label: string }

/** Search box: road names from the local OSM index as you type; places (Nominatim) only on Enter / button. */
export function PlaceSearch({ label, value, onPick }: { label: string; value: Place | null; onPick: (p: Place) => void }) {
  const [text, setText] = useState('');
  const [res, setRes] = useState<SearchResponse | null>(null);
  const [open, setOpen] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    if (text.trim().length < 2) { setRes(null); return; }
    const t = setTimeout(() => { api.search(text, false).then((r) => { setRes(r); setOpen(true); }).catch(() => setRes(null)); }, 200);
    return () => clearTimeout(t);
  }, [text]);
  const searchPlaces = () => {
    setErr(null);
    api.search(text, true).then((r) => { setRes(r); setOpen(true); if (!r.places.length && !r.roads.length) setErr(r.placesStatus); }).catch((e) => setErr(String(e.message ?? e)));
  };
  const pick = (p: Place) => { onPick(p); setText(p.label); setOpen(false); };
  return (
    <Field label={label} hint={value ? `${value.lat.toFixed(5)}, ${value.lon.toFixed(5)}` : 'Type a road name, press Enter to search places, or click the map'}>
      <div className="search-box">
        <input value={text} placeholder="e.g. Senapati Bapat Marg, Dadar station" onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); searchPlaces(); } }} onFocus={() => res && setOpen(true)} />
        {open && res && (res.roads.length > 0 || res.places.length > 0) && (
          <ul role="listbox">
            {res.roads.map((r) => <li key={'r' + r.name}><button onClick={() => pick({ label: r.name, lat: r.lat, lon: r.lon })}>{r.name} <span className="muted">road · {r.roadClass} · {fmt(r.totalKm, 1)} km</span></button></li>)}
            {res.places.map((p, i) => <li key={'p' + i}><button onClick={() => pick({ label: p.displayName.split(',')[0], lat: p.lat, lon: p.lon })}>{p.displayName} <span className="muted">place (Nominatim)</span></button></li>)}
          </ul>
        )}
        {err && <span className="field-hint warn-text">{err}</span>}
      </div>
    </Field>
  );
}

export function FindRoute() {
  const { network } = useAppState();
  const [from, setFrom] = useState<Place | null>(null);
  const [to, setTo] = useState<Place | null>(null);
  const [metric, setMetric] = useState<Metric>('DISTANCE');
  const [closed, setClosed] = useState<Set<number>>(new Set());
  const route = useRequest<RouteResponse>();
  const r = route.data;

  const overlays: Overlay[] = useMemo(() => (r?.geometry ?? []).map((g) => ({ coords: pairs(g), color: '#7c3aed', weight: 6 })), [r]);
  const markers: Marker[] = [...(from ? [{ ...from, label: 'A', color: '#16a34a' }] : []), ...(to ? [{ ...to, label: 'B', color: '#dc2626' }] : [])];
  const styleOf = useMemo(() => (s: Segment) => (closed.has(s.id) ? { color: '#dc2626', weight: 4, dash: '3 5' } : defaultStyle(s)), [closed]);

  if (!network) return null;
  const find = () => { if (from && to) void route.run(() => api.route(from, to, metric, [...closed])); };
  return (
    <>
      <PageHeader kicker="Find Route" title="Route between two places on the mapped road network">
        Dijkstra on the real OSM arterial graph, respecting one-way roads. <b>Shortest</b> uses real road length. <b>Estimated time</b> uses
        posted or default free-flow speeds; it is not a live-traffic "fastest" route. Click a road to mark it closed and see if the route survives.
      </PageHeader>
      <Notice tone="warn"><b>Live traffic unavailable.</b> No verified Mumbai traffic feed is connected; nothing here reflects current congestion.</Notice>
      <div className="grid-main">
        <Card>
          <MumbaiMap segments={network.items} styleOf={styleOf} markers={markers} overlays={overlays} height={600}
            onMapClick={(lat, lon) => { const p = { lat, lon, label: `${lat.toFixed(4)}, ${lon.toFixed(4)}` }; if (!from || (from && to)) { setFrom(p); setTo(null); } else setTo(p); route.setData(null); }}
            onSegmentClick={(s) => { const n = new Set(closed); if (n.has(s.id)) n.delete(s.id); else n.add(s.id); setClosed(n); route.setData(null); }} />
        </Card>
        <div className="stack">
          <Card title="Route">
            <PlaceSearch label="From" value={from} onPick={(p) => { setFrom(p); route.setData(null); }} />
            <PlaceSearch label="To" value={to} onPick={(p) => { setTo(p); route.setData(null); }} />
            <Field label="Weight"><select value={metric} onChange={(e) => setMetric(e.target.value as Metric)}>
              <option value="DISTANCE">Shortest - real road length</option><option value="ESTIMATED_TIME">Estimated free-flow time (assumption)</option></select></Field>
            <p className="small">{closed.size} road segment(s) marked closed (hypothetical).</p>
            <div className="row">
              <Button busy={route.busy} disabled={!from || !to} onClick={find}>Find Route</Button>
              <Button variant="ghost" onClick={() => { setFrom(null); setTo(null); setClosed(new Set()); route.setData(null); }}>Reset</Button>
            </div>
            <ErrorBox error={route.error} />
            {r && !r.reachable && <Notice tone="warn"><b>Unreachable.</b> No directed route exists between these points in the arterial graph{closed.size ? ' with the selected closures' : ''}.</Notice>}
            {r && r.reachable && (
              <>
                <div className="metrics compact">
                  <MetricBox label="Length" value={fmt(r.distanceM / 1000, 2)} unit="km" />
                  <MetricBox label="Est. free-flow time" value={fmt(r.estimatedTimeS / 60, 1)} unit="min" hint={`${fmt(r.maxspeedTaggedSharePct, 0)}% of length has a speed tag`} />
                </div>
                <table className="table small"><thead><tr><th>Dijkstra queue</th><th className="num">Settled</th><th className="num">Heap ops</th><th className="num">ms</th></tr></thead>
                  <tbody>{[r.fibonacci, r.binary].map((q) => <tr key={q.queue}><td>{q.queue === 'FIBONACCI' ? 'Fibonacci heap (own)' : 'java.util.PriorityQueue'}</td><td className="num">{fmt(q.settled)}</td><td className="num">{fmt(q.heapOperations)}</td><td className="num">{fmt(q.elapsedMs, 2)}</td></tr>)}</tbody></table>
                <p className="small">{r.queuesAgree ? <Badge tone="good">both queues give the same distance</Badge> : <Badge tone="bad">mismatch</Badge>}</p>
                <details><summary className="small">Roads used ({r.steps.length})</summary>
                  <ol className="small">{r.steps.map((s, i) => <li key={i}>{s.name || '(unnamed)'} <span className="muted">{fmt(s.lengthM)} m</span></li>)}</ol></details>
              </>
            )}
            {r && <Explain e={r.explanation} />}
            <Complexity time="O(E + V log V) Fibonacci heap; O(E log V) binary heap" space="O(V + E)" />
          </Card>
        </div>
      </div>
    </>
  );
}
