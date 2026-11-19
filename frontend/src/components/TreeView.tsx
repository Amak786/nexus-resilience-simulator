import type { NodeView } from '../lib/types';

/** Draws the red-black tree snapshot returned by the API (level-order nodes with child ids). */
export function TreeView({ nodes, maxNodes = 63 }: { nodes: NodeView[]; maxNodes?: number }) {
  if (nodes.length === 0) return <div className="empty">The tree is empty.</div>;
  if (nodes.length > maxNodes) return <div className="empty">Tree has {nodes.length} nodes; drawing is limited to {maxNodes}.</div>;
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const pos = new Map<number, { x: number; depth: number }>();
  let counter = 0;
  let maxDepth = 0;
  const walk = (id: number, depth: number) => { // in-order x positions keep BST order left-to-right
    const n = byId.get(id);
    if (!n) return;
    if (n.left >= 0) walk(n.left, depth + 1);
    pos.set(id, { x: counter++, depth });
    maxDepth = Math.max(maxDepth, depth);
    if (n.right >= 0) walk(n.right, depth + 1);
  };
  walk(0, 0);
  const W = Math.max(320, counter * 64);
  const H = (maxDepth + 1) * 64 + 20;
  const px = (i: number) => 32 + i * ((W - 64) / Math.max(1, counter - 1));
  const py = (d: number) => 30 + d * 64;
  return (
    <div className="tree-scroll">
      <svg viewBox={`0 0 ${W} ${H}`} style={{ minWidth: Math.min(W, 900) }} role="img" aria-label="Red-black tree">
        {nodes.map((n) => [n.left, n.right].filter((c) => c >= 0).map((c) => {
          const a = pos.get(n.id)!;
          const b = pos.get(c)!;
          return <line key={`${n.id}-${c}`} x1={px(a.x)} y1={py(a.depth)} x2={px(b.x)} y2={py(b.depth)} className="tree-edge" />;
        }))}
        {nodes.map((n) => {
          const p = pos.get(n.id)!;
          return (
            <g key={n.id} transform={`translate(${px(p.x)} ${py(p.depth)})`} className={`tree-node ${n.color.toLowerCase()}`}>
              <circle r={24} />
              {n.key.includes('/') ? (
                <text textAnchor="middle">
                  <tspan x={0} y={-2}>{n.key.split('/')[0]}</tspan>
                  <tspan x={0} y={9} className="tree-sub">{n.key.split('/')[1].slice(0, 9)}</tspan>
                </text>
              ) : (
                <text y={4} textAnchor="middle">{n.key.length > 9 ? n.key.slice(0, 9) + '…' : n.key}</text>
              )}
              <title>{`${n.key} (${n.color}, subtree size ${n.subtreeSize})`}</title>
            </g>
          );
        })}
      </svg>
    </div>
  );
}
