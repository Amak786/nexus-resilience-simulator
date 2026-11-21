import type { ReactNode } from 'react';
import { useAppState } from '../lib/state';
import type { Finding } from '../lib/types';
import { Badge, List } from './ui';

export const sevTone = (s: string | null | undefined) => (s === 'HIGH' ? 'bad' : s === 'MEDIUM' ? 'warn' : s === 'LOW' ? 'info' : 'neutral');

export const ago = (iso: string | null | undefined) => {
  if (!iso) return '-';
  const s = Math.round((Date.now() - Date.parse(iso)) / 1000);
  if (s < 60) return `${Math.max(0, s)} s ago`;
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} d ago`;
};

export const time = (iso: string | null | undefined) => (iso ? iso.slice(0, 19).replace('T', ' ') + ' UTC' : '-');

/** Shown instead of a page that needs observed data when the viewer is not signed in. */
export function NeedsViewer({ children }: { children: ReactNode }) {
  const { viewer, sources } = useAppState();
  if (viewer) return <>{children}</>;
  return (
    <div className="alert info">
      <b>Observed network data is private.</b> {sources && !sources.viewerConfigured
        ? 'This server has no viewer token configured (TRACE_VIEW_TOKEN), so no observed data can be shown here. Reputation lookups and the Algorithm Lab still work.'
        : <>Sign in with the viewer token on <a href="#/sources">Data Sources</a>. Reputation lookups and the Algorithm Lab work without it.</>}
    </div>
  );
}

export function FindingCard({ f, actions }: { f: Finding; actions?: ReactNode }) {
  return (
    <div className={`finding sev-${f.severity}`}>
      <div className="finding-head">
        <div>
          <Badge tone={sevTone(f.severity)}>{f.severity} severity</Badge> <Badge tone="neutral">{f.confidence} confidence</Badge>{' '}
          <Badge tone={f.category === 'INTEL_MATCH' ? 'warn' : 'neutral'}>{f.category === 'INTEL_MATCH' ? 'threat-intel match' : 'heuristic'}</Badge>{' '}
          {f.status !== 'OPEN' && <Badge tone={f.status === 'CONFIRMED_INCIDENT' ? 'bad' : 'good'}>{f.status.replace('_', ' ').toLowerCase()} (analyst)</Badge>}
        </div>
        <span className="score" title={f.scoreBreakdown.join('\n')}>score {f.score}</span>
      </div>
      <b>{f.title}</b>
      <div className="small muted">rule {f.rule} · first {time(f.firstSeen)} · last {ago(f.lastSeen)} · {f.observations} observations · sensors {f.sensors.join(', ')}
        {f.processes.length ? ` · processes ${f.processes.join(', ')}` : ''}</div>
      <details className="small">
        <summary>Evidence, reasoning and next steps</summary>
        <p><b>Why generated:</b> {f.why}</p>
        <b>Evidence</b>
        <ul className="plain-list">{f.evidence.map((e, i) => <li key={i}><Badge tone={e.type === 'INTEL_MATCH' ? 'warn' : 'neutral'}>{e.type.toLowerCase().replace('_', ' ')}</Badge> {e.text} <span className="muted">· {e.source}{e.at ? ` · ${time(e.at)}` : ''}</span></li>)}</ul>
        <div className="two-col"><div><b>Known</b><List items={f.known} /></div><div><b>Uncertain</b><List items={f.uncertain} /></div></div>
        <b>Next steps</b><List items={f.nextSteps} />
        <p className="muted">Score: {f.scoreBreakdown.join(', ')}</p>
        {f.analystNote && <p>Analyst note: {f.analystNote}</p>}
      </details>
      {actions}
    </div>
  );
}
