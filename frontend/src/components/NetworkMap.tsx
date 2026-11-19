import type { CityDataset, CityNode } from '../lib/types';

export interface RoadStyle { tone?: 'flow' | 'cut' | 'path' | 'muted' | 'route'; label?: string; width?: number }

interface Props {
  dataset: CityDataset;
  failedRoads?: Set<string>;
  failedNodes?: Set<string>;
  roadStyles?: Record<string, RoadStyle>;
  nodeTone?: Record<string, string>; // node id -> CSS colour (e.g. component colour)
  highlightNodes?: Set<string>;
  onRoadClick?: (roadId: string) => void;
  onNodeClick?: (nodeId: string) => void;
  overlayPath?: string[]; // node ids drawn as a schematic route
  height?: number;
  caption?: string;
}

const PAD = 2.2;

/** City map drawn from node coordinates (km). All numbers shown come from API responses. */
export function NetworkMap({
  dataset, failedRoads, failedNodes, roadStyles = {}, nodeTone, highlightNodes, onRoadClick, onNodeClick,
  overlayPath, height = 420, caption,
}: Props) {
  const xs = dataset.nodes.map((n) => n.x);
  const ys = dataset.nodes.map((n) => n.y);
  const minX = Math.min(...xs) - PAD;
  const maxX = Math.max(...xs) + PAD;
  const minY = Math.min(...ys) - PAD;
  const maxY = Math.max(...ys) + PAD;
  const w = maxX - minX;
  const h = maxY - minY;
  const byId = new Map(dataset.nodes.map((n) => [n.id, n]));
  const sy = (y: number) => maxY - y + minY; // north up
  const interactive = Boolean(onRoadClick || onNodeClick);

  return (
    <figure className="network">
      <svg viewBox={`${minX} ${minY} ${w} ${h}`} style={{ maxHeight: height }} role="img"
        aria-label={caption ?? 'City network map'}>
        <defs>
          <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="4" markerHeight="4" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" fill="var(--edge)" />
          </marker>
        </defs>
        {dataset.roads.map((r) => {
          const a = byId.get(r.from);
          const b = byId.get(r.to);
          if (!a || !b) return null;
          const failed = failedRoads?.has(r.id) || failedNodes?.has(r.from) || failedNodes?.has(r.to);
          const st = roadStyles[r.id];
          const cls = `road ${failed ? 'failed' : st?.tone ?? ''} ${onRoadClick ? 'clickable' : ''}`;
          const mx = (a.x + b.x) / 2;
          const my = (sy(a.y) + sy(b.y)) / 2;
          return (
            <g key={r.id} className={cls} onClick={onRoadClick ? () => onRoadClick(r.id) : undefined}
              tabIndex={onRoadClick ? 0 : undefined} role={onRoadClick ? 'button' : undefined}
              aria-label={onRoadClick ? `Road ${r.id} ${r.from} to ${r.to}, toggle failure` : undefined}
              onKeyDown={onRoadClick ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onRoadClick(r.id); } } : undefined}>
              <line x1={a.x} y1={sy(a.y)} x2={b.x} y2={sy(b.y)} className="hit" />
              <line x1={a.x} y1={sy(a.y)} x2={b.x} y2={sy(b.y)} className="stroke"
                style={st?.width ? { strokeWidth: st.width } : undefined}
                markerEnd={r.oneWay ? 'url(#arrow)' : undefined} />
              <text x={mx} y={my - 0.35} className="road-label">{st?.label ?? r.id}</text>
              {failed && <text x={mx} y={my + 0.9} className="fail-x">✕</text>}
            </g>
          );
        })}
        {overlayPath && overlayPath.length > 1 && (
          <polyline className="overlay-path"
            points={overlayPath.map((id) => byId.get(id)).filter((n): n is CityNode => !!n).map((n) => `${n.x},${sy(n.y)}`).join(' ')} />
        )}
        {dataset.nodes.map((n) => {
          const failed = failedNodes?.has(n.id);
          const fill = nodeTone?.[n.id];
          return (
            <g key={n.id} className={`node ${n.type.toLowerCase()} ${failed ? 'failed' : ''} ${highlightNodes?.has(n.id) ? 'hl' : ''} ${onNodeClick ? 'clickable' : ''}`}
              transform={`translate(${n.x} ${sy(n.y)})`} onClick={onNodeClick ? () => onNodeClick(n.id) : undefined}
              tabIndex={onNodeClick ? 0 : undefined} role={onNodeClick ? 'button' : undefined}
              aria-label={onNodeClick ? `${n.name}, toggle facility failure` : undefined}
              onKeyDown={onNodeClick ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onNodeClick(n.id); } } : undefined}>
              <title>{`${n.id} - ${n.name}`}</title>
              {n.type === 'DEPOT' ? <rect x={-0.75} y={-0.75} width={1.5} height={1.5} rx={0.25} style={fill ? { fill } : undefined} />
                : n.type === 'JUNCTION' ? <circle r={0.45} style={fill ? { fill } : undefined} />
                  : <circle r={0.75} style={fill ? { fill } : undefined} />}
              <text y={-1.05} className="node-label">{n.id}</text>
            </g>
          );
        })}
      </svg>
      <figcaption>
        <span className="lg depot" /> Depot <span className="lg zone" /> Demand zone <span className="lg junction" /> Junction
        <span className="lg-line cut" /> Min-cut road <span className="lg-line failedl" /> Failed {interactive && <em>· click roads or facilities to toggle failures</em>}
        {caption && <div className="muted">{caption}</div>}
      </figcaption>
    </figure>
  );
}

/** Aggregates directed flow-edge results into per-road labels for the map. */
export function roadStylesFromFlow(
  edges: { kind: string; roadId: string | null; flow: number; capacity: number; inMinCut: boolean }[],
  pathRoads?: Set<string>,
): Record<string, RoadStyle> {
  const agg: Record<string, { flow: number; cap: number; cut: boolean }> = {};
  for (const e of edges) {
    if (e.kind !== 'ROAD' || !e.roadId) continue;
    const a = (agg[e.roadId] ??= { flow: 0, cap: e.capacity, cut: false });
    a.flow += e.flow;
    a.cut ||= e.inMinCut;
  }
  const out: Record<string, RoadStyle> = {};
  for (const [id, a] of Object.entries(agg)) {
    out[id] = {
      tone: pathRoads?.has(id) ? 'path' : a.cut ? 'cut' : a.flow > 0 ? 'flow' : 'muted',
      label: `${id} ${a.flow}/${a.cap}`,
      width: 0.18 + 0.5 * Math.min(1, a.flow / Math.max(1, a.cap)),
    };
  }
  return out;
}
