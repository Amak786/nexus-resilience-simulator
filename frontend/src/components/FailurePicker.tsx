import type { CityDataset } from '../lib/types';

export interface Failures { roads: Set<string>; nodes: Set<string> }

export const noFailures = (): Failures => ({ roads: new Set(), nodes: new Set() });

export function toggle(set: Set<string>, id: string): Set<string> {
  const next = new Set(set);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  return next;
}

/** Keyboard-accessible failure selection (chips) that mirrors clicks on the map. */
export function FailurePicker({ dataset, value, onChange }: { dataset: CityDataset; value: Failures; onChange: (f: Failures) => void }) {
  return (
    <div className="failure-picker">
      <div className="fp-row">
        <span className="fp-label">Fail roads</span>
        <div className="chips">
          {dataset.roads.map((r) => (
            <button key={r.id} type="button" className={`chip ${value.roads.has(r.id) ? 'on' : ''}`} aria-pressed={value.roads.has(r.id)}
              title={`${r.from} - ${r.to}, ${r.capacity} units/window`} onClick={() => onChange({ ...value, roads: toggle(value.roads, r.id) })}>
              {r.id}
            </button>
          ))}
        </div>
      </div>
      <div className="fp-row">
        <span className="fp-label">Fail facilities</span>
        <div className="chips">
          {dataset.nodes.map((n) => (
            <button key={n.id} type="button" className={`chip ${value.nodes.has(n.id) ? 'on' : ''}`} aria-pressed={value.nodes.has(n.id)}
              title={n.name} onClick={() => onChange({ ...value, nodes: toggle(value.nodes, n.id) })}>
              {n.id}
            </button>
          ))}
        </div>
      </div>
      {(value.roads.size > 0 || value.nodes.size > 0) && (
        <button type="button" className="link" onClick={() => onChange(noFailures())}>Clear all failures</button>
      )}
    </div>
  );
}
