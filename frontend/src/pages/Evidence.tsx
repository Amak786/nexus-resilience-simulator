import { useState } from 'react';
import { api } from '../lib/api';
import { useRequest } from '../lib/state';
import type { BenchmarkReport, SelfCheckReport } from '../lib/types';
import { CompareBars } from '../components/Charts';
import { Badge, Button, Card, ErrorBox, List, PageHeader, fmt } from '../components/ui';

const SUITES = [
  { id: 'flow', label: 'Max flow (EK vs FF)' },
  { id: 'dsu', label: 'DSU' },
  { id: 'rbtree', label: 'Red-black tree' },
  { id: 'bloom', label: 'Bloom filter' },
  { id: 'segtree', label: 'Segment tree' },
  { id: 'quickselect', label: 'Quickselect' },
  { id: 'tsp', label: 'Metric TSP' },
];

export function Evidence() {
  const check = useRequest<SelfCheckReport>();
  const bench = useRequest<BenchmarkReport>();
  const [suite, setSuite] = useState<string | null>(null);
  const b = bench.data;
  return (
    <>
      <PageHeader kicker="Benchmarks & tests" title="Evidence of correctness and performance">
        The JUnit suite (62 tests) runs at build time and in CI. On this page you can re-run seeded property checks and
        benchmarks on the live server: nothing here is precomputed.
      </PageHeader>
      <Card title="Live self-check (seeded property tests)" actions={<Button busy={check.busy} onClick={() => void check.run(api.selfCheck)}>Run self-check</Button>}>
        <ErrorBox error={check.error} />
        {!check.data ? <p className="muted">Runs about 10,000 randomized cases across all algorithms in a second or two.</p> : (
          <>
            <p>{check.data.allPassed ? <Badge tone="good">all checks passed</Badge> : <Badge tone="bad">a check failed</Badge>} <span className="muted small">seed {check.data.seed}</span></p>
            <table className="table"><thead><tr><th>Algorithm</th><th>Property checked</th><th className="num">Cases</th><th>Result</th><th className="num">ms</th></tr></thead>
              <tbody>{check.data.checks.map((c) => <tr key={c.algorithm}><td>{c.algorithm}</td><td className="small">{c.property}</td><td className="num">{fmt(c.cases)}</td>
                <td>{c.passed ? <Badge tone="good">pass</Badge> : <Badge tone="bad">{c.detail}</Badge>}</td><td className="num">{fmt(c.ms, 1)}</td></tr>)}</tbody></table>
          </>
        )}
      </Card>
      <Card title="Benchmarks (three input sizes, warm-up, median of repeated runs, baseline)">
        <div className="row wrap">{SUITES.map((s) => (
          <Button key={s.id} variant={suite === s.id ? 'primary' : 'ghost'} busy={bench.busy && suite === s.id} disabled={bench.busy}
            onClick={() => { setSuite(s.id); void bench.run(() => api.benchmark(s.id)); }}>{s.label}</Button>))}</div>
        <ErrorBox error={bench.error} />
        {bench.busy && <p className="muted">Running on the server; the largest sizes can take several seconds…</p>}
        {b && !bench.busy && (
          <>
            <h3>{b.title}</h3>
            <CompareBars rows={b.rows.map((r) => ({ label: r.size, a: r.primaryMedianMs, b: r.baselineMedianMs, aName: r.primary, bName: r.baseline }))} />
            <div className="table-scroll"><table className="table small"><thead><tr><th>Input</th><th>Algorithm</th><th className="num">Median ms</th><th className="num">Min ms</th><th className="num">Ops</th><th>Baseline</th><th className="num">Median ms</th><th className="num">Ops</th><th>Quality / check</th></tr></thead>
              <tbody>{b.rows.map((r) => <tr key={r.size}><td>{r.size}</td><td>{r.primary}</td><td className="num">{fmt(r.primaryMedianMs, 3)}</td><td className="num">{fmt(r.primaryMinMs, 3)}</td><td className="num">{fmt(r.primaryOps)}</td>
                <td>{r.baseline}</td><td className="num">{fmt(r.baselineMedianMs, 3)}</td><td className="num">{fmt(r.baselineOps)}</td><td className="small">{r.quality}</td></tr>)}</tbody></table></div>
            <p className="small"><b>Ops:</b> {b.opsMeaning} · {b.warmups} warm-up, {b.repetitions} timed runs.</p>
            <p className="small muted">{b.environment}</p>
            <List items={b.methodology} />
          </>
        )}
      </Card>
      <Card title="JUnit test suite (run with mvn test)">
        <table className="table small"><thead><tr><th>Class</th><th className="num">Tests</th><th>Covers</th></tr></thead><tbody>
          <tr><td>MaxFlowTest</td><td className="num">9</td><td>textbook network, reverse-arc cancellation, disconnected sink, zero capacity, parallel edges, pathological DFS case, 300 random graphs, trace, invalid input</td></tr>
          <tr><td>DisjointSetUnionTest</td><td className="num">6</td><td>union/find, repeated union, empty and single element, path compression, oracle comparison, invalid input</td></tr>
          <tr><td>RedBlackTreeTest</td><td className="num">8</td><td>single and double rotation, 100k sorted inserts, deletion vs TreeMap, delete-all, rank/select, priority key, boundaries</td></tr>
          <tr><td>BloomFilterTest</td><td className="num">7</td><td>no false negatives, empty filter, sizing formulas, measured FPR, false positive caught by exact check, deterministic hashing, invalid input</td></tr>
          <tr><td>SegmentTreeTest</td><td className="num">6</td><td>hand-checked queries, lazy range add, boundaries and ties, 5,000 random ops vs array, O(log n) work, invalid ranges</td></tr>
          <tr><td>QuickselectTest</td><td className="num">6</td><td>every rank, input untouched, 500 random arrays with duplicates, single element, seeded reproducibility, invalid input</td></tr>
          <tr><td>MetricTspTest</td><td className="num">6</td><td>unit square, 2-approximation vs Held-Karp, non-metric rejection, tiny instances, duplicates, invalid start</td></tr>
          <tr><td>ServiceScenarioTest</td><td className="num">9</td><td>certified demo flow, bridge isolation, Failure Lab, incident queue, screening, routes, timeline, invalid datasets, self-check</td></tr>
          <tr><td>ApiIntegrationTest</td><td className="num">5</td><td>HTTP endpoints, JSON errors, 413 size limit, CORS allow/deny</td></tr>
        </tbody></table>
      </Card>
    </>
  );
}
