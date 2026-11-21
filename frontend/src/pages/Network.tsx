import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { GEdge, GNode, GraphView, PathResult } from '../lib/types';
import { Badge, Button, Card, ErrorBox, List, PageHeader, fmt } from '../components/ui';
import { NeedsViewer, ago, sevTone } from '../components/common';

const KIND_COLOR: Record<GNode['kind'], string> = { SENSOR: '#8fb3c9', PROCESS: '#5fb3a3', REMOTE_IP: '#7c8a99', ASN: '#a58fc9' };

interface P { x: number; y: number; vx: number; vy: number }

/** Small force-directed layout (repulsion + springs), computed once per graph, drawn on a canvas. */
function useLayout(nodes: GNode[], edges: GEdge[], w: number, h: number) {
  return useMemo(() => {
    const pos = new Map<string, P>();
    const n = nodes.length;
    nodes.forEach((nd, i) => {
      const a = (2 * Math.PI * i) / Math.max(1, n);
      const r = nd.kind === 'SENSOR' ? 0 : nd.kind === 'PROCESS' ? 0.25 : nd.kind === 'ASN' ? 0.45 : 0.38;
      pos.set(nd.id, { x: w / 2 + Math.cos(a) * r * w, y: h / 2 + Math.sin(a) * r * h, vx: 0, vy: 0 });
    });
    const arr = nodes.map((x) => pos.get(x.id)!);
    for (let it = 0; it < 220; it++) {
      for (let i = 0; i < n; i++) {
        for (let j = i + 1; j < n; j++) {
          const p = arr[i];
          const q = arr[j];
          const dx = p.x - q.x;
          const dy = p.y - q.y;
          const d2 = Math.max(25, dx * dx + dy * dy);
          const f = 260 / d2;
          p.vx += dx * f; p.vy += dy * f; q.vx -= dx * f; q.vy -= dy * f;
        }
      }
      for (const e of edges) {
        const p = pos.get(e.a);
        const q = pos.get(e.b);
        if (!p || !q) continue;
        const dx = q.x - p.x;
        const dy = q.y - p.y;
        const d = Math.sqrt(dx * dx + dy * dy) || 1;
        const f = (d - 45) * 0.01;
        p.vx += (dx / d) * f * d * 0.1; p.vy += (dy / d) * f * d * 0.1; q.vx -= (dx / d) * f * d * 0.1; q.vy -= (dy / d) * f * d * 0.1;
      }
      for (const p of arr) {
        p.vx += (w / 2 - p.x) * 0.006; p.vy += (h / 2 - p.y) * 0.006;
        p.x = Math.min(w - 12, Math.max(12, p.x + Math.max(-8, Math.min(8, p.vx * 0.5))));
        p.y = Math.min(h - 12, Math.max(12, p.y + Math.max(-8, Math.min(8, p.vy * 0.5))));
        p.vx *= 0.6; p.vy *= 0.6;
      }
    }
    return pos;
  }, [nodes, edges, w, h]);
}

function GraphCanvas({ g, selected, onSelect, highlight }: { g: GraphView; selected: string[]; onSelect: (id: string) => void; highlight: Set<string> }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const W = 900;
  const H = 560;
  const pos = useLayout(g.nodeList, g.edgeList, W, H);
  useEffect(() => {
    const c = ref.current;
    const ctx = c?.getContext('2d');
    if (!c || !ctx) return;
    const dpr = window.devicePixelRatio || 1;
    c.width = W * dpr; c.height = H * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, W, H);
    for (const e of g.edgeList) {
      const p = pos.get(e.a);
      const q = pos.get(e.b);
      if (!p || !q) continue;
      const on = highlight.has(e.a) && highlight.has(e.b);
      ctx.strokeStyle = on ? 'rgba(95,179,163,0.95)' : 'rgba(150,165,180,0.28)';
      ctx.lineWidth = on ? 2 : 0.8;
      ctx.beginPath(); ctx.moveTo(p.x, p.y); ctx.lineTo(q.x, q.y); ctx.stroke();
    }
    for (const n of g.nodeList) {
      const p = pos.get(n.id)!;
      const r = n.kind === 'SENSOR' ? 9 : n.kind === 'PROCESS' ? 6 : 4 + Math.min(4, Math.log2(1 + n.betweenness) / 2);
      ctx.fillStyle = n.intelMatch || n.maxSeverity === 'HIGH' ? '#d0605e' : n.maxSeverity ? '#d9a441' : KIND_COLOR[n.kind];
      ctx.beginPath(); ctx.arc(p.x, p.y, r, 0, 2 * Math.PI); ctx.fill();
      if (n.articulation) { ctx.strokeStyle = 'rgba(230,236,242,0.6)'; ctx.lineWidth = 1; ctx.stroke(); }
      if (selected.includes(n.id)) { ctx.strokeStyle = '#e6ecf2'; ctx.lineWidth = 2.5; ctx.beginPath(); ctx.arc(p.x, p.y, r + 4, 0, 2 * Math.PI); ctx.stroke(); }
      if (n.kind !== 'REMOTE_IP' || selected.includes(n.id) || n.maxSeverity) {
        ctx.fillStyle = 'rgba(214,222,230,0.85)'; ctx.font = '11px system-ui, sans-serif';
        ctx.fillText(n.label.length > 26 ? n.label.slice(0, 25) + '…' : n.label, p.x + r + 3, p.y + 3);
      }
    }
  }, [g, pos, selected, highlight]);
  const click = (ev: React.MouseEvent<HTMLCanvasElement>) => {
    const rect = ev.currentTarget.getBoundingClientRect();
    const x = ((ev.clientX - rect.left) / rect.width) * W;
    const y = ((ev.clientY - rect.top) / rect.height) * H;
    let best: string | null = null;
    let bd = 18 * 18;
    for (const n of g.nodeList) {
      const p = pos.get(n.id)!;
      const d = (p.x - x) ** 2 + (p.y - y) ** 2;
      if (d < bd) { bd = d; best = n.id; }
    }
    if (best) onSelect(best);
  };
  return <canvas ref={ref} className="graph-canvas" style={{ aspectRatio: `${W} / ${H}` }} onClick={click} role="img" aria-label="Graph of observed network relationships" />;
}

export function Network() {
  const { viewer, tick } = useAppState();
  const graph = useRequest<GraphView>();
  const path = useRequest<PathResult>();
  const [selected, setSelected] = useState<string[]>([]);
  const [filter, setFilter] = useState<'ALL' | 'FLAGGED'>('ALL');
  const runGraph = graph.run;
  useEffect(() => { if (viewer) void runGraph(() => api.graph(250)); }, [viewer, tick, runGraph]);
  const g = graph.data;
  const shown = useMemo<GraphView | null>(() => {
    if (!g || filter === 'ALL') return g;
    const keep = new Set(g.nodeList.filter((n) => n.maxSeverity || n.intelMatch || n.kind !== 'REMOTE_IP').map((n) => n.id));
    return { ...g, nodeList: g.nodeList.filter((n) => keep.has(n.id)), edgeList: g.edgeList.filter((e) => keep.has(e.a) && keep.has(e.b)) };
  }, [g, filter]);
  const highlight = useMemo(() => new Set(path.data?.dijkstraPath ?? []), [path.data]);
  const pick = (id: string) => { setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id].slice(-2))); path.setData(null); };
  const nodeById = useMemo(() => new Map((g?.nodeList ?? []).map((n) => [n.id, n])), [g]);
  const label = (id: string) => nodeById.get(id)?.label ?? id;

  return (
    <>
      <PageHeader kicker="Network" title="How observed entities are connected">
        Nodes are sensors, processes, remote addresses and (after a lookup) announcing networks. Every edge is an observed relationship.
        Click one node for details, a second to analyse the path between them.
      </PageHeader>
      <NeedsViewer>
        <ErrorBox error={graph.error} />
        {g && g.nodes === 0 && <div className="alert info">No observations yet. Connect a sensor on Data Sources.</div>}
        {shown && g && g.nodes > 0 && (
          <div className="grid-main">
            <Card actions={<div className="row">
              <Button variant={filter === 'ALL' ? 'primary' : 'ghost'} onClick={() => setFilter('ALL')}>All</Button>
              <Button variant={filter === 'FLAGGED' ? 'primary' : 'ghost'} onClick={() => setFilter('FLAGGED')}>Flagged + hubs</Button></div>}>
              <GraphCanvas g={shown} selected={selected} onSelect={pick} highlight={highlight} />
              <p className="small muted"><span className="dot" style={{ background: KIND_COLOR.SENSOR }} />sensor <span className="dot" style={{ background: KIND_COLOR.PROCESS }} />process
                <span className="dot" style={{ background: KIND_COLOR.REMOTE_IP }} />remote address <span className="dot" style={{ background: KIND_COLOR.ASN }} />network (ASN)
                <span className="dot bad" />threat-list / high finding <span className="dot warn" />other finding · ringed = articulation point{g.truncated ? ` · showing the ${g.nodeList.length} most central of ${g.nodes} nodes` : ''}</p>
            </Card>
            <div className="stack">
              <Card title="Graph metrics (computed now)">
                <table className="table small"><tbody>
                  <tr><td>Nodes / edges</td><td className="num">{fmt(g.nodes)} / {fmt(g.edges)}</td></tr>
                  <tr><td>Connected components (DSU)</td><td className="num">{g.components} (largest {g.largestComponent})</td></tr>
                  <tr><td>Articulation points / bridges (DFS)</td><td className="num">{g.articulationPoints} / {g.bridges}</td></tr>
                </tbody></table>
                <b className="small">Most central (Brandes betweenness)</b>
                <List items={g.mostCentral.length ? g.mostCentral : ['no node lies between others yet']} />
                <details className="small"><summary>Algorithm runs</summary>
                  <ul className="plain-list">{g.runs.map((r) => <li key={r.algorithm}><b>{r.algorithm}</b>: {r.output} <span className="muted">({r.input}, {r.ms} ms)</span></li>)}</ul></details>
                <p className="small muted">{g.note}</p>
              </Card>
              <Card title={selected.length === 2 ? 'Path between selected nodes' : selected.length === 1 ? 'Selected node' : 'Select nodes'}>
                {selected.length === 0 && <p className="small muted">Click a node on the graph.</p>}
                {selected.map((id) => { const n = nodeById.get(id); return n ? (
                  <div key={id} className="small node-detail">
                    <b>{n.label}</b> <Badge tone="neutral">{n.kind.toLowerCase().replace('_', ' ')}</Badge> {n.maxSeverity && <Badge tone={sevTone(n.maxSeverity)}>{n.maxSeverity} finding</Badge>}
                    <div className="muted">{fmt(n.observations)} observations · degree {n.degree} · betweenness {n.betweenness}{n.articulation ? ' · articulation point' : ''}{n.scope ? ` · ${n.scope.toLowerCase()} address` : ''}</div>
                    <div className="muted">first {ago(n.firstSeen)} · last {ago(n.lastSeen)}</div>
                    {n.kind === 'REMOTE_IP' && <a href={`#/investigations?ip=${n.label}`}>Investigate {n.label} →</a>}
                  </div>) : null; })}
                {selected.length === 2 && <Button busy={path.busy} onClick={() => void path.run(() => api.path(selected[0], selected[1]))}>Analyse path</Button>}
                <ErrorBox error={path.error} />
                {path.data && (
                  <div className="small">
                    {!path.data.connected ? <p className="warn-text">Not connected in the observed graph (different components).</p> : (
                      <>
                        <p><b>BFS</b> fewest hops: {path.data.bfsHops} · {path.data.bfsPath.map(label).join(' → ')}</p>
                        <p><b>Dijkstra</b> (Fibonacci heap {path.data.fibonacciMs} ms vs PriorityQueue {path.data.priorityQueueMs} ms, {path.data.queuesAgree ? 'same result' : 'MISMATCH'}): {path.data.dijkstraPath.map(label).join(' → ')} · evidence distance {path.data.dijkstraCost}</p>
                        <p><b>Max flow</b> relationship-disjoint paths: Edmonds-Karp {path.data.disjointPathsEdmondsKarp}, Ford-Fulkerson {path.data.disjointPathsFordFulkerson}
                          {path.data.maxFlowEqualsMinCut ? ' = min cut' : ''}. Min cut: {path.data.minCutEdges.join('; ') || '-'}</p>
                        <p className="muted">Reachable from the first node: {path.data.reachableFromSource} (BFS), {path.data.dfsVisited} (DFS).</p>
                      </>
                    )}
                    <List items={path.data.notes} />
                  </div>
                )}
              </Card>
            </div>
          </div>
        )}
      </NeedsViewer>
    </>
  );
}
