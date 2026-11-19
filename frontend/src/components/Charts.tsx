import { fmt } from './ui';

/** Hourly demand (line), capacity before/after (dashed lines) and shortfall after (bars). Values from the API. */
export function TimelineChart({ demand, capBefore, capAfter, shortfall, worst, peakHour }: {
  demand: number[]; capBefore: number; capAfter: number; shortfall: number[];
  worst?: { startHour: number; endHour: number }; peakHour?: number;
}) {
  const W = 720;
  const H = 240;
  const L = 44;
  const B = 28;
  const T = 10;
  const n = demand.length;
  const maxV = Math.max(...demand, capBefore, 1) * 1.1;
  const x = (i: number) => L + (i / Math.max(1, n - 1)) * (W - L - 10);
  const y = (v: number) => T + (1 - v / maxV) * (H - T - B);
  const bw = (W - L - 10) / n;
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((t) => Math.round(maxV * t));
  return (
    <figure className="chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Hourly demand, capacity and shortfall">
        {ticks.map((t) => (
          <g key={t}>
            <line x1={L} x2={W - 10} y1={y(t)} y2={y(t)} className="grid" />
            <text x={L - 6} y={y(t) + 4} className="axis" textAnchor="end">{t}</text>
          </g>
        ))}
        {worst && (
          <rect x={x(worst.startHour) - bw / 2} width={x(worst.endHour) - x(worst.startHour) + bw} y={T} height={H - T - B}
            className="worst-band" />
        )}
        {shortfall.map((s, i) => s > 0 && (
          <rect key={i} x={x(i) - bw * 0.4} width={bw * 0.8} y={y(s)} height={y(0) - y(s)}
            className={`short-bar ${peakHour === i ? 'peak' : ''}`} />
        ))}
        <polyline className="demand-line" points={demand.map((d, i) => `${x(i)},${y(d)}`).join(' ')} />
        <line x1={L} x2={W - 10} y1={y(capBefore)} y2={y(capBefore)} className="cap-before" />
        <line x1={L} x2={W - 10} y1={y(capAfter)} y2={y(capAfter)} className="cap-after" />
        {[0, 12, 24, 36, 48, 60, n - 1].filter((h) => h < n).map((h) => (
          <text key={h} x={x(h)} y={H - 8} className="axis" textAnchor="middle">h{h}</text>
        ))}
      </svg>
      <figcaption>
        <span className="lg-line demand" /> Demand (kits/h) <span className="lg-line capb" /> Capacity before
        ({fmt(capBefore, 1)}/h) <span className="lg-line capa" /> Capacity after ({fmt(capAfter, 1)}/h)
        <span className="lg short" /> Shortfall after {worst && <><span className="lg worst" /> Worst window</>}
      </figcaption>
    </figure>
  );
}

/** Histogram of scenario impacts with percentile and selected-failure markers. */
export function ImpactHistogram({ impacts, markers, selected }: {
  impacts: number[]; markers: { label: string; value: number }[]; selected: number;
}) {
  const W = 720;
  const H = 200;
  const L = 40;
  const B = 30;
  const maxI = Math.max(...impacts, selected, 1);
  const bins = 20;
  const width = maxI / bins;
  const counts = new Array(bins).fill(0) as number[];
  impacts.forEach((v) => { counts[Math.min(bins - 1, Math.floor(v / width))]++; });
  const maxC = Math.max(...counts, 1);
  const x = (v: number) => L + (v / maxI) * (W - L - 12);
  const y = (c: number) => 10 + (1 - c / maxC) * (H - 10 - B);
  return (
    <figure className="chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Distribution of throughput loss across random failure scenarios">
        {counts.map((c, i) => (
          <rect key={i} x={x(i * width) + 1} width={Math.max(1, x(width) - L - 2)} y={y(c)} height={y(0) - y(c)} className="hist-bar">
            <title>{`${fmt(i * width)}-${fmt((i + 1) * width)} units: ${c} scenarios`}</title>
          </rect>
        ))}
        <line x1={L} x2={W - 12} y1={y(0)} y2={y(0)} className="grid" />
        {markers.map((m, i) => (
          <g key={m.label}>
            <line x1={x(m.value)} x2={x(m.value)} y1={10} y2={y(0)} className="pct-line" />
            <text x={x(m.value) + 3} y={20 + i * 13} className="axis">{m.label} = {fmt(m.value)}</text>
          </g>
        ))}
        <line x1={x(selected)} x2={x(selected)} y1={10} y2={y(0)} className="sel-line" />
        <text x={L} y={H - 8} className="axis">0</text>
        <text x={W - 12} y={H - 8} className="axis" textAnchor="end">{fmt(maxI)} units lost</text>
      </svg>
      <figcaption><span className="lg hist" /> Random scenarios <span className="lg-line pct" /> Quickselect percentiles <span className="lg-line sel" /> Selected failure</figcaption>
    </figure>
  );
}

/** Horizontal paired bars: primary vs baseline medians. */
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
