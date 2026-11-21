import { useCallback, useMemo, useState } from 'react';
import { api } from '../lib/api';
import { PRESET_AREAS, useAppState, useRequest } from '../lib/state';
import type { ComponentsResponse, EventListResponse, EventStatsResponse, RefreshResponse, Segment } from '../lib/types';
import { MumbaiMap, type Marker, type SegStyle } from '../components/MumbaiMap';
import { Badge, Button, Card, Complexity, DataBadge, ErrorBox, Explain, Field, List, Metric, Notice, PageHeader, fmt } from '../components/ui';

const PALETTE = ['#2563eb', '#ea580c', '#16a34a', '#9333ea', '#db2777', '#0891b2', '#ca8a04', '#4f46e5'];

export function Connectivity() {
  const { network, provenance, reload } = useAppState();
  const [areaId, setAreaId] = useState(PRESET_AREAS[0].id);
  const area = PRESET_AREAS.find((p) => p.id === areaId)!.area;
  const [removed, setRemoved] = useState<Set<number>>(new Set());
  const comps = useRequest<ComponentsResponse>();
  const [from, setFrom] = useState('2023-01-01');
  const [to, setTo] = useState(new Date().toISOString().slice(0, 10));
  const [status, setStatus] = useState('all');
  const list = useRequest<EventListResponse>();
  const stats = useRequest<EventStatsResponse>();
  const refresh = useRequest<RefreshResponse>();

  const compOf = useMemo(() => new Map(comps.data?.segmentComponent ?? []), [comps.data]);
  const segs = useMemo(() => (network ? network.items.filter((s) => s.geometry[0] >= area.south && s.geometry[0] <= area.north && s.geometry[1] >= area.west && s.geometry[1] <= area.east) : []), [network, area]);
  const styleOf = useCallback((s: Segment): SegStyle => {
    if (removed.has(s.id)) return { color: '#94a3b8', weight: 3, dash: '4 4' };
    const c = compOf.get(s.id);
    if (c === undefined) return { color: '#94a3b8', weight: 1.5 };
    return { color: c === 0 ? '#94a3b8' : PALETTE[c % PALETTE.length], weight: c === 0 ? 1.5 : 4 };
  }, [compOf, removed]);
  const noteMarkers: Marker[] = useMemo(() => (list.data?.items ?? []).filter((n) => n.lat >= area.south && n.lat <= area.north && n.lon >= area.west && n.lon <= area.east)
    .slice(0, 60).map((n) => ({ lat: n.lat, lon: n.lon, label: `#${n.id}`, color: n.status === 'open' ? '#dc2626' : '#16a34a' })), [list.data, area]);

  if (!network || !provenance) return null;
  const events = provenance.sources.find((s) => s.id === 'events');
  const runEvents = () => { void list.run(() => api.events(from, to, status)); void stats.run(() => api.eventStats(from, to)); };
  const maxMonth = Math.max(1, ...(stats.data?.monthly.map((m) => m.count) ?? [1]));

  return (
    <>
      <PageHeader kicker="Network Connectivity" title="Which parts of the road network are connected?">
        Connected components come from a disjoint-set union (DSU) over the selected area. DSU measures <b>weak</b> connectivity (ignoring one-way rules); directed reachability and practical vehicle access are different questions. Click roads to remove them: DSU cannot delete, so it is rebuilt. Event records are real OpenStreetMap map notes
        (community-reported map issues). They are <b>not</b> traffic incidents.
      </PageHeader>
      <div className="grid-main">
        <Card>
          <MumbaiMap segments={segs} styleOf={styleOf} markers={noteMarkers} area={area} fitArea={area} height={560}
            onSegmentClick={(s) => { const n = new Set(removed); if (n.has(s.id)) n.delete(s.id); else n.add(s.id); setRemoved(n); }} />
          <p className="small muted">Grey = largest connected component; coloured = smaller components; dashed = removed by you. Dots: map notes in the area (red open, green closed).</p>
        </Card>
        <div className="stack">
          <Card title="Connected components (DSU)">
            <Field label="Area"><select value={areaId} onChange={(e) => { setAreaId(e.target.value); setRemoved(new Set()); comps.setData(null); }}>
              {PRESET_AREAS.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}</select></Field>
            <div className="row"><Button busy={comps.busy} onClick={() => void comps.run(() => api.components(area, [...removed]))}>Analyse connectivity</Button>
              <Button variant="ghost" onClick={() => { setRemoved(new Set()); comps.setData(null); }}>Clear removals</Button></div>
            <p className="small muted">Click roads on the map to remove them, then re-run.</p>
            <ErrorBox error={comps.error} />
            {comps.data && (
              <div className="metrics compact">
                <Metric label="Components before" value={comps.data.before.componentCount} />
                <Metric label="Components after" value={comps.data.after.componentCount} tone={comps.data.after.componentCount > comps.data.before.componentCount ? 'warn' : undefined} />
                <Metric label="Largest component" value={`${fmt(100 * comps.data.after.largestShare, 1)}%`} hint={`${fmt(comps.data.after.largestNodes)} of ${fmt(comps.data.nodes)} nodes`} />
                <Metric label="Unions (merged / redundant)" value={`${comps.data.after.successfulUnions} / ${comps.data.after.redundantUnions}`} />
              </div>
            )}
            {comps.data && <Explain e={comps.data.explanation} />}
            <Complexity time="O((V + E) α(V)) per rebuild" space="O(V)" />
          </Card>
          <Card title="Data gaps">
            <Notice tone="warn"><b>Travel-time trends: not available.</b> No verified historical or live travel-time feed for Mumbai is connected, so no trend is shown. Route times are free-flow estimates only.</Notice>
            <p className="small">{events && <DataBadge c={events.dataClass} />} {events?.name}: {events?.freshness}</p>
            <div className="row"><Button variant="ghost" busy={refresh.busy} onClick={() => void refresh.run(async () => { const r = await api.refreshEvents(); await reload(); return r; })}>Import latest notes from OSM (live)</Button></div>
            <ErrorBox error={refresh.error} />
            {refresh.data && <Notice tone={refresh.data.status === 'OK' ? 'ok' : 'warn'}><b>{refresh.data.status}</b>: {refresh.data.message}
              {refresh.data.status === 'OK' && <> Bloom filter: {fmt(refresh.data.bloomBits)} bits, {refresh.data.bloomHashes} hashes; {refresh.data.possiblyPresent} possible hits, all checked against the exact id set.</>}</Notice>}
          </Card>
        </div>
      </div>
      <Card title="Map-note records (red-black tree index by creation time)" actions={<Button busy={list.busy || stats.busy} onClick={runEvents}>Query</Button>}>
        <div className="form-grid">
          <Field label="From"><input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label="To"><input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
          <Field label="Status"><select value={status} onChange={(e) => setStatus(e.target.value)}><option value="all">all</option><option value="open">open</option><option value="closed">closed</option></select></Field>
        </div>
        <ErrorBox error={list.error ?? stats.error} />
        {stats.data && (
          <>
            <div className="metrics compact">
              <Metric label="Notes in range (segment tree)" value={fmt(stats.data.totalNotes)} hint={stats.data.matchesScan ? 'matches a plain scan' : 'MISMATCH'} />
              <Metric label="Busiest day (range max)" value={stats.data.busiestDay.date} hint={`${stats.data.busiestDay.count} notes`} />
              <Metric label="Median time to close (P50)" value={stats.data.resolutionPercentiles[0] ? `${fmt(stats.data.resolutionPercentiles[0].valueDays, 1)} d` : '-'} hint={`n = ${stats.data.resolvedSample} closed notes`} tone={stats.data.sampleAdequate ? undefined : 'warn'} />
              <Metric label="P90 time to close" value={stats.data.resolutionPercentiles[1] ? `${fmt(stats.data.resolutionPercentiles[1].valueDays, 1)} d` : '-'} hint="randomized Quickselect, checked vs sort" />
            </div>
            <div className="bars" aria-label="Notes per month">
              {stats.data.monthly.map((m) => <div key={m.month} className="bar" title={`${m.month}: ${m.count}`} style={{ height: `${(100 * m.count) / maxMonth}%` }} />)}
            </div>
            <p className="small muted">Notes per month, {stats.data.from} to {stats.data.to}. Segment-tree nodes visited: {fmt(stats.data.segmentTreeNodesVisited)} vs {fmt(stats.data.scanCellsTouched)} cells for a scan of each day once.</p>
            <List items={stats.data.notes} />
          </>
        )}
        {list.data && (
          <>
            <p className="small">{fmt(list.data.countInRange)} notes in range (O(log n) count via subtree sizes); showing {list.data.returned}. Index: {list.data.index.size} records, height {list.data.index.height}, {list.data.index.invariantsHold ? <Badge tone="good">red-black invariants hold</Badge> : <Badge tone="bad">invalid</Badge>}</p>
            <div className="table-scroll short"><table className="table small"><thead><tr><th>Created (UTC)</th><th>Status</th><th>Note</th><th>Link</th></tr></thead>
              <tbody>{list.data.items.map((n) => <tr key={n.id}><td>{n.created.slice(0, 10)}</td><td><Badge tone={n.status === 'open' ? 'warn' : 'good'}>{n.status}</Badge></td><td>{n.text || <span className="muted">(no text)</span>}</td><td><a href={n.url} target="_blank" rel="noreferrer">#{n.id}</a></td></tr>)}</tbody></table></div>
          </>
        )}
      </Card>
    </>
  );
}
