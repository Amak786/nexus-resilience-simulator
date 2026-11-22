import { fmt } from './ui';

export function CompareBars({ rows, unit = 'ms' }: { rows: { label: string; a: number; b: number | null; aName: string; bName: string }[]; unit?: string }) {
  const max = Math.max(...rows.flatMap((r) => [r.a, r.b ?? 0]), 1e-9);
  return (
    <div className="compare-bars">
      {rows.map((r) => (
        <div key={r.label} className="cb-row">
          <div className="cb-label">{r.label}</div>
          <div className="cb-bars">
            <div className="cb-bar a" style={{ width: `${(100 * r.a) / max}%` }}><span>{r.aName}: {fmt(r.a, 3)} {unit}</span></div>
            {r.b !== null && <div className="cb-bar b" style={{ width: `${(100 * r.b) / max}%` }}><span>{r.bName}: {fmt(r.b, 3)} {unit}</span></div>}
          </div>
        </div>
      ))}
    </div>
  );
}
