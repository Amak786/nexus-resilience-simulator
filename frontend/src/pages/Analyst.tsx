import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import { useRequest } from '../lib/state';
import type { AnalystResponse } from '../lib/types';
import { Badge, Button, Card, ErrorBox, PageHeader } from '../components/ui';
import { NeedsViewer, time } from '../components/common';

const SUGGESTED = [
  'Which findings need attention first, and why?',
  'How fresh is the data and is the sensor live?',
  'What changed in the network in the last 15 minutes?',
  'Explain how the Bloom filter is used to check addresses.',
];

export function Analyst() {
  const [q, setQ] = useState('');
  const [info, setInfo] = useState<{ aiConfigured: boolean; model: string; tools: string[] } | null>(null);
  const ask = useRequest<AnalystResponse>();
  useEffect(() => { api.analystInfo().then(setInfo).catch(() => setInfo(null)); }, []);
  const submit = (text: string) => { const t = text.trim(); if (t) { setQ(t); void ask.run(() => api.ask(t)); } };
  const r = ask.data;
  return (
    <>
      <PageHeader kicker="AI Analyst" title="Explanations grounded in TRACE's own results">
        The assistant can only call six read-only tools that run TRACE's rules and algorithms. It explains; it does not decide whether something is
        malicious, and it is instructed never to claim a compromise. {info && !info.aiConfigured && 'No AI key is configured on this server, so answers are the deterministic evidence only.'}
      </PageHeader>
      <NeedsViewer>
        <div className="grid-2">
          <Card title="Ask" actions={info ? (info.aiConfigured ? <Badge tone="good">AI: {info.model}</Badge> : <Badge tone="warn">evidence-only mode</Badge>) : null}>
            <form onSubmit={(e) => { e.preventDefault(); submit(q); }}>
              <textarea rows={3} maxLength={600} value={q} onChange={(e) => setQ(e.target.value)} placeholder="Ask about findings, an IP address, freshness or an algorithm" />
              <div className="row"><Button type="submit" busy={ask.busy} disabled={!q.trim()}>Ask</Button></div>
            </form>
            <div className="chips">{SUGGESTED.map((s) => <button key={s} className="chip" onClick={() => submit(s)}>{s}</button>)}</div>
            <ErrorBox error={ask.error} />
            {r && (
              <div className="answer">
                <div className="small">{r.answerKind === 'AI_INTERPRETATION' ? <Badge tone="info">AI interpretation · {r.model}</Badge> : <Badge tone="neutral">deterministic evidence (no AI)</Badge>}
                  <span className="muted"> {time(r.generatedAt)}</span></div>
                {r.notice && <p className="small warn-text">{r.notice}</p>}
                <p className="answer-text">{r.answer}</p>
              </div>
            )}
          </Card>
          <Card title={`Evidence${r ? ` (${r.evidence.length} tool results)` : ''}`}>
            {!r && <p className="small muted">Tool calls and their raw results appear here, so every statement can be checked.</p>}
            {r?.evidence.map((e, i) => (
              <details key={i} className="small" open={i === 0}>
                <summary><code>{e.tool}</code> {e.result.error ? <Badge tone="bad">error</Badge> : <span className="muted">{e.result.result} · {e.result.source}</span>}</summary>
                <pre className="evidence">{JSON.stringify(e.result.error ?? e.result.data, null, 1).slice(0, 6000)}</pre>
              </details>
            ))}
            {info && <p className="small muted">Tools: {info.tools.join(', ')}.</p>}
          </Card>
        </div>
      </NeedsViewer>
    </>
  );
}
