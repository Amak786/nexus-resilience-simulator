import { useAppState } from '../lib/state';
import type { Category } from '../lib/types';
import { MumbaiMap } from '../components/MumbaiMap';
import { Badge, Card, Metric, PageHeader, fmt } from '../components/ui';

export const CATEGORY_TONE: Record<Category, 'good' | 'info' | 'neutral' | 'warn' | 'bad'> = {
  VERIFIED_LIVE: 'good', LATEST_AVAILABLE: 'info', STATIC_GEOGRAPHIC: 'info', DERIVED_ESTIMATE: 'warn',
  MODELLED_PROXY: 'warn', DEMONSTRATION: 'neutral', NOT_AVAILABLE: 'bad',
};

export function Overview() {
  const { provenance: p, network } = useAppState();
  if (!p || !network) return null;
  return (
    <>
      <PageHeader kicker="Overview" title="Mumbai road-network intelligence">
        NEXUS analyses the real mapped arterial road network of Greater Mumbai (OpenStreetMap): connectivity, critical links,
        routes and map-issue records. It is a student prototype. It does not show live traffic.
      </PageHeader>
      <div className="metrics">
        <Metric label="Intersections (nodes)" value={fmt(p.graph.nodes)} />
        <Metric label="Road segments" value={fmt(p.graph.segments)} hint={`${fmt(p.graph.oneWaySegments)} one-way`} />
        <Metric label="Mapped length" value={fmt(p.graph.totalKm)} unit="km" hint="motorway to tertiary roads" />
        <Metric label="Study area" value="Greater Mumbai" hint={`${p.studyArea.south}-${p.studyArea.north}°N, ${p.studyArea.west}-${p.studyArea.east}°E`} />
      </div>
      <div className="grid-main">
        <Card title="Study area and mapped network">
          <MumbaiMap segments={network.items} area={p.studyArea} height={560} />
          <p className="muted small">Colours show road class (motorway, trunk, primary, secondary, tertiary). Hover a road for its name and length.</p>
        </Card>
        <div className="stack">
          <Card title="Data sources and freshness">
            <ul className="sources">
              {p.sources.map((s) => (
                <li key={s.id}>
                  <div><Badge tone={CATEGORY_TONE[s.category]}>{s.category.replace('_', ' ')}</Badge> <b>{s.name}</b></div>
                  <div className="small muted">{s.freshness}{s.retrievedAt ? ` · retrieved ${s.retrievedAt.slice(0, 16).replace('T', ' ')} UTC` : ''}</div>
                  {s.source && <div className="small">Source: {s.sourceUrl ? <a href={s.sourceUrl} target="_blank" rel="noreferrer">{s.source}</a> : s.source}</div>}
                  <div className="small muted">{s.notes}</div>
                </li>
              ))}
            </ul>
          </Card>
          <Card title="Road classes in the graph">
            <table className="table small"><thead><tr><th>Class</th><th className="num">Segments</th><th className="num">km</th></tr></thead>
              <tbody>{p.graph.byClass.map((c) => <tr key={c.roadClass}><td>{c.roadClass}</td><td className="num">{fmt(c.segments)}</td><td className="num">{fmt(c.km, 1)}</td></tr>)}</tbody></table>
          </Card>
        </div>
      </div>
      <Card title="How to read NEXUS">
        <div className="legend-grid">
          <div><Badge tone="info">STATIC GEOGRAPHIC</Badge> Real map geometry from OpenStreetMap (roads, intersections, one-way tags).</div>
          <div><Badge tone="info">LATEST AVAILABLE</Badge> A dated snapshot of a real source, refreshable from its public API.</div>
          <div><Badge tone="good">VERIFIED LIVE</Badge> Fetched from the public API during this session.</div>
          <div><Badge tone="warn">DERIVED ESTIMATE</Badge> Calculated from real data with stated rules (e.g. free-flow travel time).</div>
          <div><Badge tone="warn">MODELLED PROXY</Badge> An assumption-based stand-in (e.g. road capacity from lane counts).</div>
          <div><Badge tone="bad">NOT AVAILABLE</Badge> No verified source found; nothing is shown instead of fake data.</div>
        </div>
      </Card>
    </>
  );
}
