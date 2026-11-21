import { useMemo } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { SegmentDetail } from '../lib/types';
import { MumbaiMap, defaultStyle, pairs, type Overlay } from '../components/MumbaiMap';
import { Card, DataBadge, ErrorBox, PageHeader, fmt } from '../components/ui';

export function Explorer() {
  const { network, provenance } = useAppState();
  const req = useRequest<SegmentDetail>();
  const d = req.data;
  const overlays: Overlay[] = useMemo(() => (d ? [{ coords: pairs(d.segment.geometry), color: '#f59e0b', weight: 8 }] : []), [d]);
  if (!network || !provenance) return null;
  const a = provenance.graph.attributes;
  return (
    <>
      <PageHeader kicker="Explore Roads" title="Real mapped roads and what is missing">
        Click any road to see its OpenStreetMap attributes, which fields are missing, and how the app fills gaps (always labelled as an estimate).
      </PageHeader>
      <div className="grid-main">
        <Card>
          <MumbaiMap segments={network.items} styleOf={defaultStyle} overlays={overlays} height={620} onSegmentClick={(s) => void req.run(() => api.segment(s.id))} />
          <p className="small muted">Colours: motorway (orange), trunk (amber), primary (blue), secondary (teal), tertiary (grey). © OpenStreetMap contributors, ODbL.</p>
        </Card>
        <div className="stack">
          <Card title="Selected road segment">
            <ErrorBox error={req.error} />
            {!d ? <p className="muted small">Click a road on the map.</p> : (
              <>
                <table className="table small"><tbody>
                  <tr><td>Name</td><td>{d.segment.name || <span className="muted">(no name tag)</span>}</td></tr>
                  <tr><td>Class</td><td>{d.segment.roadClass}</td></tr>
                  <tr><td>Direction</td><td>{d.segment.oneWay ? 'one-way' : 'two-way'}</td></tr>
                  <tr><td>Length</td><td>{fmt(d.segment.lengthM)} m</td></tr>
                  <tr><td>OSM way</td><td><a href={d.osmUrl} target="_blank" rel="noreferrer">{d.segment.wayId}</a></td></tr>
                </tbody></table>
                <b className="small">Attributes and their data class</b>
                <ul className="plain-list small">{d.attributes.map((x, i) => <li key={i}><DataBadge c={x.dataClass} /> {x.item}</li>)}</ul>
                {d.missingAttributes.length > 0 && <p className="small warn-text">Missing in OSM: {d.missingAttributes.join(', ')}</p>}
              </>
            )}
          </Card>
          <Card title="Attribute coverage (whole snapshot)">
            <table className="table small"><tbody>
              <tr><td>Name present</td><td className="num">{fmt(a.namePresentPct, 1)}%</td><td className="num muted">{fmt(a.segmentsMissingName)} missing</td></tr>
              <tr><td>lanes tag present</td><td className="num">{fmt(a.lanesTagPresentPct, 1)}%</td><td className="num muted">{fmt(a.segmentsMissingLanes)} missing</td></tr>
              <tr><td>maxspeed tag present</td><td className="num">{fmt(a.maxspeedTagPresentPct, 1)}%</td><td className="num muted">{fmt(a.segmentsMissingMaxspeed)} missing</td></tr>
            </tbody></table>
            <p className="small muted">Because most lanes and speed tags are missing, capacities and travel times can only be estimates.</p>
          </Card>
        </div>
      </div>
    </>
  );
}
