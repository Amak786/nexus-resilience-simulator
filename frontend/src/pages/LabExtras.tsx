import { useState } from 'react';
import { api } from '../lib/api';
import { useRequest } from '../lib/state';
import type { BenchmarkReport, BloomLabResponse, SelfCheckReport } from '../lib/types';
import { CompareBars } from '../components/Charts';
import { Badge, Button, Card, ErrorBox, Field, List, fmt } from '../components/ui';

interface HeapResult { results: { index: number; type: string; outcome: string; size: number; rootDegrees: number[]; links: number; cuts: number; cascadingCuts: number; invariantsHold: boolean }[]; size: number; note: string }

export function FibHeapLab() {
  const [text, setText] = useState('INSERT 50\nINSERT 30\nINSERT 70\nINSERT 10\nINSERT 60\nINSERT 40\nINSERT 20\nEXTRACT_MIN\nDECREASE_KEY 4 5\nDECREASE_KEY 2 1\nEXTRACT_MIN');
  const [err, setErr] = useState<string | null>(null);
  const req = useRequest<HeapResult>();
  const run = () => {
    setErr(null);
    try {
      const operations = text.split('\n').map((l) => l.trim()).filter(Boolean).map((l, i) => {
        const [type, a, b] = l.split(/\s+/);
        const t = type.toUpperCase();
        const num = (x: string | undefined) => { const v = Number(x); if (x === undefined || Number.isNaN(v)) throw new Error(`line ${i + 1}: "${x ?? ''}" is not a number`); return v; };
        if (t === 'INSERT') return { type: t, key: num(a) };
        if (t === 'DECREASE_KEY') return { type: t, handle: num(a), key: num(b) };
        return { type: t };
      });
      void req.run(() => api.lab<HeapResult>('fibheap', { operations }));
    } catch (e) { setErr((e as Error).message); }
  };
  const r = req.data;
  return (
    <div className="grid-2">
      <Card title="Operations">
        <Field label="INSERT key · EXTRACT_MIN · DECREASE_KEY handle newKey" hint="handle = position of the INSERT (0-based)"><textarea rows={11} value={text} onChange={(e) => setText(e.target.value)} /></Field>
        <Button busy={req.busy} onClick={run}>Run Fibonacci heap</Button>
        {err && <div className="alert error">{err}</div>}
        <ErrorBox error={req.error} />
      </Card>
      {r && (
        <Card title={`Final size ${r.size}`}>
          <table className="table small"><thead><tr><th>#</th><th>Operation</th><th>Root degrees</th><th className="num">Links</th><th className="num">Cuts</th><th>Valid</th></tr></thead>
            <tbody>{r.results.map((x) => <tr key={x.index}><td>{x.index + 1}</td><td>{x.outcome}</td><td><code>[{x.rootDegrees.join(', ')}]</code></td><td className="num">{x.links}</td><td className="num">{x.cuts} ({x.cascadingCuts} casc.)</td>
              <td>{x.invariantsHold ? <Badge tone="good">yes</Badge> : <Badge tone="bad">no</Badge>}</td></tr>)}</tbody></table>
          <p className="small muted">{r.note}</p>
        </Card>
      )}
    </div>
  );
}

const SUITES = [
  { id: 'dijkstra', label: 'Fibonacci heap vs PriorityQueue (Dijkstra)' }, { id: 'flow', label: 'Edmonds-Karp vs Ford-Fulkerson' },
  { id: 'quickselect', label: 'Quickselect vs sort' }, { id: 'segtree', label: 'Segment tree vs scan' }, { id: 'dsu', label: 'DSU vs naive' },
  { id: 'rbtree', label: 'Red-black tree vs sorted list' }, { id: 'bloom', label: 'Bloom filter vs HashSet' }, { id: 'tsp', label: 'TSP: MST vs nearest neighbour' },
];

export function BenchmarksPanel() {
  const bench = useRequest<BenchmarkReport>();
  const [suite, setSuite] = useState<string | null>(null);
  const b = bench.data;
  return (
    <Card title="Benchmarks (run live on the server: three input sizes, warm-up, median of repeated runs)">
      <div className="row wrap">{SUITES.map((s) => (
        <Button key={s.id} variant={suite === s.id ? 'primary' : 'ghost'} busy={bench.busy && suite === s.id} disabled={bench.busy}
          onClick={() => { setSuite(s.id); void bench.run(() => api.benchmark(s.id)); }}>{s.label}</Button>))}</div>
      <ErrorBox error={bench.error} />
      {bench.busy && <p className="muted">Running on the server; larger sizes can take several seconds on free hosting…</p>}
      {b && !bench.busy && (
        <>
          <h3>{b.title}</h3>
          <CompareBars rows={b.rows.map((r) => ({ label: r.size, a: r.primaryMedianMs, b: r.baselineMedianMs, aName: r.primary, bName: r.baseline }))} />
          <div className="table-scroll"><table className="table small"><thead><tr><th>Input</th><th>Algorithm</th><th className="num">Median ms</th><th className="num">Ops</th><th>Baseline</th><th className="num">Median ms</th><th className="num">Ops</th><th>Check</th></tr></thead>
            <tbody>{b.rows.map((r) => <tr key={r.size}><td>{r.size}</td><td>{r.primary}</td><td className="num">{fmt(r.primaryMedianMs, 3)}</td><td className="num">{fmt(r.primaryOps)}</td>
              <td>{r.baseline}</td><td className="num">{fmt(r.baselineMedianMs, 3)}</td><td className="num">{fmt(r.baselineOps)}</td><td className="small">{r.quality}</td></tr>)}</tbody></table></div>
          <p className="small"><b>Ops:</b> {b.opsMeaning}</p>
          <p className="small muted">{b.environment}</p>
          <List items={b.methodology} />
        </>
      )}
    </Card>
  );
}

export function SelfCheckPanel() {
  const check = useRequest<SelfCheckReport>();
  return (
    <Card title="Live self-check (seeded property tests on the running server)" actions={<Button busy={check.busy} onClick={() => void check.run(api.selfCheck)}>Run self-check</Button>}>
      <ErrorBox error={check.error} />
      {!check.data ? <p className="muted">About 10,000 seeded cases across the algorithms; the full JUnit suite (76 tests) runs in CI on every push.</p> : (
        <>
          <p>{check.data.allPassed ? <Badge tone="good">all checks passed</Badge> : <Badge tone="bad">a check failed</Badge>} <span className="muted small">seed {check.data.seed}</span></p>
          <table className="table small"><thead><tr><th>Algorithm</th><th>Property</th><th className="num">Cases</th><th>Result</th></tr></thead>
            <tbody>{check.data.checks.map((c) => <tr key={c.algorithm}><td>{c.algorithm}</td><td>{c.property}</td><td className="num">{fmt(c.cases)}</td><td>{c.passed ? <Badge tone="good">pass</Badge> : <Badge tone="bad">{c.detail}</Badge>}</td></tr>)}</tbody></table>
        </>
      )}
    </Card>
  );
}

/** Bloom filter on user-typed SAMPLE identifiers; every "possibly present" answer is checked against a HashSet. */
export function BloomLab() {
  const [ins, setIns] = useState('W101, W202, W303, W404, W505, W606, W707, W808');
  const [qs, setQs] = useState('W101, W202, W999, W123, X1');
  const [fpr, setFpr] = useState(0.05);
  const req = useRequest<BloomLabResponse>();
  const split = (t: string) => t.split(/[\s,]+/).map((x) => x.trim()).filter(Boolean);
  const r = req.data;
  return (
    <Card title="Bloom filter: known-identifier screening (sample data)">
      <Field label="Known identifiers to insert (sample)"><textarea rows={2} value={ins} onChange={(e) => setIns(e.target.value)} /></Field>
      <Field label="Identifiers to check"><textarea rows={2} value={qs} onChange={(e) => setQs(e.target.value)} /></Field>
      <Field label="Target false-positive rate"><input type="number" step={0.01} min={0.001} max={0.5} value={fpr} onChange={(e) => setFpr(Number(e.target.value))} /></Field>
      <Button busy={req.busy} onClick={() => void req.run(() => api.lab<BloomLabResponse>('bloom', { inserted: split(ins), queries: split(qs), falsePositiveRate: fpr, probes: 2000 }))}>Run</Button>
      <ErrorBox error={req.error} />
      {r && (
        <>
          <p className="small">{fmt(r.bits)} bits, {r.hashes} hash functions, {r.inserted} inserted. Theoretical false-positive rate {fmt(100 * r.theoreticalFpr, 2)}%;
            measured on {r.probes} identifiers that were never inserted: {r.probeFalsePositives} false positives ({fmt(100 * r.measuredFpr, 2)}%).</p>
          <table className="table small"><thead><tr><th>Identifier</th><th>Bloom filter</th><th>Exact HashSet</th><th>Verdict</th></tr></thead>
            <tbody>{r.results.map((x) => <tr key={x.id}><td>{x.id}</td><td>{x.bloom}</td><td>{x.exactPresent ? 'present' : 'absent'}</td>
              <td>{x.verdict.includes('FALSE') ? <Badge tone="warn">{x.verdict}</Badge> : x.verdict}</td></tr>)}</tbody></table>
          <p className="small muted">{r.note}</p>
        </>
      )}
    </Card>
  );
}
