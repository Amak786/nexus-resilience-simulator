import type { ReactNode } from 'react';
import type { ApiError } from '../lib/api';
import type { DataClass, Explanation } from '../lib/types';

export const fmt = (n: number | null | undefined, digits = 0) =>
  n === null || n === undefined || Number.isNaN(n)
    ? '-'
    : n.toLocaleString('en-IN', { maximumFractionDigits: digits, minimumFractionDigits: 0 });

export function PageHeader({ title, kicker, children }: { title: string; kicker?: string; children?: ReactNode }) {
  return (
    <header className="page-header">
      {kicker && <div className="kicker">{kicker}</div>}
      <h1>{title}</h1>
      {children && <p className="lede">{children}</p>}
    </header>
  );
}

export function Card({ title, actions, children, className = '' }: { title?: string; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <div className="card-head">
          {title && <h2>{title}</h2>}
          {actions && <div className="card-actions">{actions}</div>}
        </div>
      )}
      {children}
    </section>
  );
}

export function Metric({ label, value, unit, tone, hint }: { label: string; value: ReactNode; unit?: string; tone?: 'good' | 'warn' | 'bad'; hint?: string }) {
  return (
    <div className={`metric ${tone ?? ''}`}>
      <div className="metric-label">{label}</div>
      <div className="metric-value">{value}{unit && <span className="metric-unit"> {unit}</span>}</div>
      {hint && <div className="metric-hint">{hint}</div>}
    </div>
  );
}

export function ErrorBox({ error }: { error: ApiError | null }) {
  if (!error) return null;
  return (
    <div className="alert error" role="alert">
      <strong>{error.message}</strong>
      {error.issues.length > 0 && (
        <ul>
          {error.issues.slice(0, 12).map((i, k) => (
            <li key={k}><code>{i.field}</code>: {i.message}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function Notice({ tone = 'info', children }: { tone?: 'info' | 'warn' | 'ok'; children: ReactNode }) {
  return <div className={`alert ${tone}`}>{children}</div>;
}

export function Badge({ tone = 'neutral', children }: { tone?: 'neutral' | 'good' | 'warn' | 'bad' | 'info'; children: ReactNode }) {
  return <span className={`badge ${tone}`}>{children}</span>;
}

export function Button({ children, onClick, busy, variant = 'primary', disabled, type = 'button' }: {
  children: ReactNode; onClick?: () => void; busy?: boolean; variant?: 'primary' | 'ghost' | 'danger'; disabled?: boolean; type?: 'button' | 'submit';
}) {
  return (
    <button type={type} className={`btn ${variant}`} onClick={onClick} disabled={busy || disabled} aria-busy={busy}>
      {busy ? <span className="spinner" aria-hidden /> : null}
      {children}
    </button>
  );
}

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

export function Complexity({ time, space, note }: { time: string; space: string; note?: string }) {
  return (
    <div className="complexity">
      <span><b>Time</b> {time}</span>
      <span><b>Space</b> {space}</span>
      {note && <span className="muted">{note}</span>}
    </div>
  );
}

export function List({ items }: { items: string[] }) {
  return <ul className="plain-list">{items.map((s, i) => <li key={i}>{s}</li>)}</ul>;
}

const CLASS_LABEL: Record<DataClass, [string, 'good' | 'info' | 'neutral' | 'warn' | 'bad']> = {
  REAL_MAPPED: ['A · Real mapped', 'info'], VERIFIED_OBSERVATION: ['B · Verified observation', 'good'],
  DERIVED_RESULT: ['C · Derived result', 'neutral'], ESTIMATE: ['D · Estimate / assumption', 'warn'],
  SAMPLE: ['E · Sample / demo', 'warn'], NOT_AVAILABLE: ['Not available', 'bad'],
};

export function DataBadge({ c }: { c: DataClass }) {
  const [label, tone] = CLASS_LABEL[c] ?? [c, 'neutral'];
  return <Badge tone={tone}>{label}</Badge>;
}

/** The three questions every result answers. */
export function Explain({ e }: { e: Explanation | null | undefined }) {
  if (!e) return null;
  return (
    <div className="explain">
      <div><b>What data was used?</b><ul className="plain-list">{e.dataUsed.map((d, i) => <li key={i}><DataBadge c={d.dataClass} /> {d.item}</li>)}</ul></div>
      <div><b>What did the algorithm calculate?</b><p className="small">{e.calculated}</p></div>
      <div className="two-col">
        <div><b>Can conclude</b><List items={e.canConclude} /></div>
        <div><b>Cannot conclude</b><List items={e.cannotConclude} /></div>
      </div>
    </div>
  );
}
