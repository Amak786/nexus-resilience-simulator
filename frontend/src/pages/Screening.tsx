import { useState } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { ScreenResponse } from '../lib/types';
import { Badge, Button, Card, Complexity, ErrorBox, Field, List, Metric, Notice, PageHeader, fmt } from '../components/ui';

const DEFAULT_INCOMING = ['INC-1003', 'INC-1007', 'INC-3001', 'INC-3002', 'INC-H000123', 'INC-3003', 'INC-1011', 'INC-4410'].join('\n');

export function Screening() {
  const { dataset } = useAppState();
  const [expected, setExpected] = useState(6000);
  const [p, setP] = useState(0.01);
  const [seed, setSeed] = useState(2026);
  const [historical, setHistorical] = useState(5000);
  const [probes, setProbes] = useState(20000);
  const [incoming, setIncoming] = useState(DEFAULT_INCOMING);
  const req = useRequest<ScreenResponse>();
  if (!dataset) return null;
  const r = req.data;
  const ids = incoming.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean);

  return (
    <>
      <PageHeader kicker="Duplicate screening" title="Bloom-filter pre-check for duplicate incident reports">
        The registry holds the dataset's incidents plus seeded synthetic historical ids. The Bloom filter answers
        <b> definitely new</b> or <b>possibly seen</b>; every possible hit is then verified against the exact registry.
      </PageHeader>
      <div className="grid-2">
        <Card title="Parameters">
          <div className="form-grid">
            <Field label="Expected insertions (n)" hint="1 - 2,000,000"><input type="number" min={1} value={expected} onChange={(e) => setExpected(Number(e.target.value))} /></Field>
            <Field label="Target false-positive rate (p)" hint="0.000001 - 0.5"><input type="number" step="0.001" min={0.000001} max={0.5} value={p} onChange={(e) => setP(Number(e.target.value))} /></Field>
            <Field label="Synthetic historical ids" hint="added to the registry"><input type="number" min={0} value={historical} onChange={(e) => setHistorical(Number(e.target.value))} /></Field>
            <Field label="Known-absent probe ids" hint="for the measured FPR"><input type="number" min={0} value={probes} onChange={(e) => setProbes(Number(e.target.value))} /></Field>
            <Field label="Seed"><input type="number" min={0} value={seed} onChange={(e) => setSeed(Number(e.target.value))} /></Field>
          </div>
          <Field label={`Incoming report ids (${ids.length})`} hint="one per line or comma-separated">
            <textarea rows={6} value={incoming} onChange={(e) => setIncoming(e.target.value)} />
          </Field>
          <div className="row"><Button busy={req.busy} onClick={() => void req.run(() => api.screen({ dataset, expectedInsertions: expected, falsePositiveRate: p, seed, generateHistorical: historical, incomingIds: ids, absentProbes: probes }))}>Screen reports</Button></div>
          <ErrorBox error={req.error} />
          {r?.warnings.map((w) => <Notice key={w} tone="warn">{w}</Notice>)}
        </Card>
        <Card title="Filter">
          {!r ? <p className="muted">Run the screen to size the filter and measure its false-positive rate.</p> : (
            <>
              <div className="metrics compact">
                <Metric label="Bits (m)" value={fmt(r.bitCount)} hint={`${fmt(r.memoryBytes)} bytes · ${fmt(r.bitCount / Math.max(1, r.insertedCount), 1)} bits/id`} />
                <Metric label="Hash positions (k)" value={r.hashCount} />
                <Metric label="Inserted" value={fmt(r.insertedCount)} hint={`${r.duplicateRegistryEntries} duplicate registry rows skipped`} />
                <Metric label="Fill ratio" value={`${fmt(100 * r.fillRatio, 1)}%`} />
                <Metric label="Theoretical FPR" value={fmt(100 * r.theoreticalFprAtActual, 3) + '%'} hint={`at n = ${fmt(r.insertedCount)}`} />
                <Metric label="Measured FPR" value={fmt(100 * r.measuredFpr, 3) + '%'} hint={`${r.probeFalsePositives} of ${fmt(r.absentProbes)} known-absent probes`} tone="good" />
              </div>
              <List items={r.formulas} />
            </>
          )}
          <Complexity time="O(k) per add or query" space="m bits" note="no false negatives under insertion-only use" />
        </Card>
      </div>
      {r && (
        <Card title="Screening results">
          <table className="table"><thead><tr><th>Incoming id</th><th>Bloom filter says</th><th>Exact registry check</th><th>Final verdict</th><th>Bit positions</th></tr></thead>
            <tbody>{r.results.map((x) => (
              <tr key={x.id}><td><code>{x.id}</code></td>
                <td>{x.bloom === 'POSSIBLY_PRESENT' ? <Badge tone="warn">possibly present</Badge> : <Badge tone="good">definitely absent</Badge>}</td>
                <td>{x.exactLookupPerformed ? 'performed' : <span className="muted">skipped (not needed)</span>}</td>
                <td>{x.verdict === 'CONFIRMED_DUPLICATE' ? <Badge tone="bad">confirmed duplicate</Badge> : x.verdict === 'FALSE_POSITIVE' ? <Badge tone="info">false positive → new</Badge> : <Badge tone="good">new</Badge>}</td>
                <td className="small muted">{x.bitPositions.slice(0, 8).join(', ')}{x.bitPositions.length > 8 ? '…' : ''}</td></tr>))}</tbody></table>
          <p className="small">Exact lookups avoided: <b>{r.exactLookupsAvoided}</b> of {r.results.length}. Registry sample: {r.registrySample.join(', ')}</p>
        </Card>
      )}
    </>
  );
}
