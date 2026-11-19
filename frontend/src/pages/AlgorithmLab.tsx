import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import { useRequest } from '../lib/state';
import type { AlgorithmInfo, NodeView, RouteResponse, RunStats, TraceStep } from '../lib/types';
import { TreeView } from '../components/TreeView';
import { Badge, Button, Card, Complexity, ErrorBox, Field, List, PageHeader, fmt } from '../components/ui';

type Tab = 'flow' | 'dsu' | 'rbtree' | 'segtree' | 'quickselect' | 'tsp';
const TABS: { id: Tab; label: string; algo: string }[] = [
  { id: 'flow', label: 'Max flow', algo: 'edmonds-karp' },
  { id: 'dsu', label: 'DSU', algo: 'dsu' },
  { id: 'rbtree', label: 'Red-black tree', algo: 'red-black-tree' },
  { id: 'segtree', label: 'Segment tree', algo: 'segment-tree' },
  { id: 'quickselect', label: 'Quickselect', algo: 'quickselect' },
  { id: 'tsp', label: 'Metric TSP', algo: 'metric-tsp' },
];

const lines = (s: string) => s.split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));

class InputError extends Error {}
function num(token: string | undefined, what: string): number {
  const v = Number(token);
  if (token === undefined || token === '' || Number.isNaN(v)) throw new InputError(`"${token ?? ''}" is not a number (${what})`);
  return v;
}

export function AlgorithmLab() {
  const [tab, setTab] = useState<Tab>('flow');
  const [catalog, setCatalog] = useState<AlgorithmInfo[]>([]);
  useEffect(() => { api.algorithms().then(setCatalog).catch(() => setCatalog([])); }, []);
  const info = catalog.find((c) => c.id === TABS.find((t) => t.id === tab)?.algo);
  return (
    <>
      <PageHeader kicker="Algorithm Lab" title="Run each Java implementation on your own small input">
        Inputs are parsed into JSON here, but every result, trace and check below comes from the Java backend.
      </PageHeader>
      <div className="tabs" role="tablist">
        {TABS.map((t) => <button key={t.id} role="tab" aria-selected={tab === t.id} className={`tab ${tab === t.id ? 'on' : ''}`} onClick={() => setTab(t.id)}>{t.label}</button>)}
      </div>
      {info && (
        <Card className="info-card">
          <b>{info.name}</b> <Badge>{info.syllabusUnit}</Badge> <span className="muted small">{info.javaClass}</span>
          <Complexity time={info.time} space={info.space} />
          <p className="small"><b>Limitations:</b> {info.limitations}</p>
        </Card>
      )}
      {tab === 'flow' && <FlowLab />}
      {tab === 'dsu' && <DsuLab />}
      {tab === 'rbtree' && <RbLab />}
      {tab === 'segtree' && <SegLab />}
      {tab === 'quickselect' && <SelectLab />}
      {tab === 'tsp' && <TspLab />}
    </>
  );
}

interface RunTrace { stats: RunStats; steps: TraceStep[]; truncated: boolean }
interface FlowLabResult {
  nodes: string[]; edges: { index: number; from: string; to: string; capacity: number; flowEdmondsKarp: number; flowFordFulkerson: number; inMinCut: boolean }[];
  edmondsKarp: RunTrace; fordFulkerson: RunTrace; minCutCapacity: number; sourceSide: string[]; maxFlowEqualsMinCut: boolean; algorithmsAgreeOnValue: boolean; notes: string[];
}

function FlowLab() {
  const [text, setText] = useState('# from to capacity\ns a 1\ns b 1\na t 1\na b 1\nb t 1');
  const [source, setSource] = useState('s');
  const [sink, setSink] = useState('t');
  const [localErr, setLocalErr] = useState<string | null>(null);
  const req = useRequest<FlowLabResult>();
  const run = () => {
    setLocalErr(null);
    try {
      const edges = lines(text).map((l, i) => { const [from, to, c] = l.split(/\s+/); return { from, to, capacity: num(c, `line ${i + 1} capacity`) }; });
      const nodes = [...new Set([source, sink, ...edges.flatMap((e) => [e.from, e.to])])];
      void req.run(() => api.lab<FlowLabResult>('flow', { nodes, edges, source, sink }));
    } catch (e) { setLocalErr((e as Error).message); }
  };
  const r = req.data;
  return (
    <div className="grid-2">
      <Card title="Directed edges">
        <Field label="One edge per line: from to capacity" hint="Try the default: Ford-Fulkerson must cancel flow through a reverse arc."><textarea rows={8} value={text} onChange={(e) => setText(e.target.value)} /></Field>
        <div className="form-grid"><Field label="Source"><input value={source} onChange={(e) => setSource(e.target.value)} /></Field><Field label="Sink"><input value={sink} onChange={(e) => setSink(e.target.value)} /></Field></div>
        <Button busy={req.busy} onClick={run}>Run Ford-Fulkerson and Edmonds-Karp</Button>
        {localErr && <div className="alert error">{localErr}</div>}
        <ErrorBox error={req.error} />
      </Card>
      {r && (
        <Card title={`Max flow ${fmt(r.edmondsKarp.stats.maxFlow)} · min cut ${fmt(r.minCutCapacity)}`}>
          <p>{r.maxFlowEqualsMinCut ? <Badge tone="good">max flow = min cut</Badge> : <Badge tone="bad">mismatch</Badge>} {r.algorithmsAgreeOnValue ? <Badge tone="good">FF and EK agree on the value</Badge> : <Badge tone="bad">values differ</Badge>}</p>
          {[r.edmondsKarp, r.fordFulkerson].map((t) => (
            <div key={t.stats.algorithm}>
              <h3>{t.stats.algorithm}: {t.stats.augmentations} paths, {fmt(t.stats.arcScans)} arc scans</h3>
              <ol className="small">{t.steps.map((s) => <li key={s.step}><code>{s.nodes.join(' → ')}</code> push {s.bottleneck} (total {s.flowAfter}){s.arcs.some((a) => a.reverse) && <> <Badge tone="info">reverse arc</Badge></>}</li>)}</ol>
            </div>
          ))}
          <table className="table small"><thead><tr><th>Edge</th><th className="num">Cap</th><th className="num">EK flow</th><th className="num">FF flow</th><th>Cut</th></tr></thead>
            <tbody>{r.edges.map((e) => <tr key={e.index}><td>{e.from} → {e.to}</td><td className="num">{e.capacity}</td><td className="num">{e.flowEdmondsKarp}</td><td className="num">{e.flowFordFulkerson}</td><td>{e.inMinCut ? <Badge tone="bad">cut</Badge> : ''}</td></tr>)}</tbody></table>
          <p className="small muted">Source side S = {'{'}{r.sourceSide.join(', ')}{'}'}</p>
          <List items={r.notes} />
        </Card>
      )}
    </div>
  );
}

interface DsuResult { elementCount: number; results: { index: number; type: string; a: number; b: number | null; result: string; componentsAfter: number; parentAfter: number[] | null }[]; finalParent: number[] | null; finalRank: number[] | null; componentCount: number; groups: number[][]; pointerHops: number }

function DsuLab() {
  const [n, setN] = useState(8);
  const [text, setText] = useState('UNION 0 1\nUNION 2 3\nUNION 1 3\nUNION 0 2\nCONNECTED 0 3\nFIND 3\nUNION 5 6\nCONNECTED 4 6');
  const [localErr, setLocalErr] = useState<string | null>(null);
  const req = useRequest<DsuResult>();
  const run = () => {
    setLocalErr(null);
    try {
      const operations = lines(text).map((l, i) => { const [type, a, b] = l.split(/\s+/); return { type: type.toUpperCase(), a: num(a, `line ${i + 1}`), b: b === undefined ? null : num(b, `line ${i + 1}`) }; });
      void req.run(() => api.lab<DsuResult>('dsu', { elementCount: n, operations }));
    } catch (e) { setLocalErr((e as Error).message); }
  };
  const r = req.data;
  return (
    <div className="grid-2">
      <Card title="Operations">
        <Field label="Elements (0 … n-1)"><input type="number" min={1} value={n} onChange={(e) => setN(Number(e.target.value))} /></Field>
        <Field label="UNION a b · FIND a · CONNECTED a b" hint="The fourth line is a repeated union."><textarea rows={8} value={text} onChange={(e) => setText(e.target.value)} /></Field>
        <Button busy={req.busy} onClick={run}>Run DSU</Button>
        {localErr && <div className="alert error">{localErr}</div>}
        <ErrorBox error={req.error} />
      </Card>
      {r && (
        <Card title={`${r.componentCount} components`}>
          <table className="table small"><thead><tr><th>#</th><th>Op</th><th>Result</th><th className="num">Sets</th><th>parent[] after</th></tr></thead>
            <tbody>{r.results.map((x) => <tr key={x.index}><td>{x.index + 1}</td><td>{x.type} {x.a}{x.b !== null ? ` ${x.b}` : ''}</td><td>{x.result}</td><td className="num">{x.componentsAfter}</td><td><code>{x.parentAfter?.join(' ') ?? '-'}</code></td></tr>)}</tbody></table>
          <p className="small">Groups: {r.groups.map((g) => `{${g.join(',')}}`).join(' ')} · rank[]: <code>{r.finalRank?.join(' ')}</code> · pointer hops: {r.pointerHops}</p>
        </Card>
      )}
    </div>
  );
}

interface RbResult { results: { index: number; type: string; key: number; outcome: string; rotations: number; events: string[]; invariantsHold: boolean }[]; tree: NodeView[]; inOrder: number[]; size: number; height: number; blackHeight: number; totalRotations: number }

function RbLab() {
  const [text, setText] = useState('INSERT 10\nINSERT 20\nINSERT 30\nINSERT 15\nINSERT 25\nINSERT 5\nINSERT 1\nDELETE 20\nDELETE 10\nLOOKUP 25');
  const [localErr, setLocalErr] = useState<string | null>(null);
  const req = useRequest<RbResult>();
  const run = () => {
    setLocalErr(null);
    try {
      const operations = lines(text).map((l, i) => { const [type, key] = l.split(/\s+/); return { type: type.toUpperCase(), key: num(key, `line ${i + 1}`) }; });
      void req.run(() => api.lab<RbResult>('rbtree', { operations }));
    } catch (e) { setLocalErr((e as Error).message); }
  };
  const r = req.data;
  return (
    <>
      <div className="grid-2">
        <Card title="Operations">
          <Field label="INSERT k · DELETE k · LOOKUP k" hint="Inserting 10, 20, 30 forces a rotation."><textarea rows={10} value={text} onChange={(e) => setText(e.target.value)} /></Field>
          <Button busy={req.busy} onClick={run}>Run red-black tree</Button>
          {localErr && <div className="alert error">{localErr}</div>}
          <ErrorBox error={req.error} />
        </Card>
        {r && (
          <Card title={`n = ${r.size}, height ${r.height}, black height ${r.blackHeight}, ${r.totalRotations} rotations`}>
            <ol className="small">{r.results.map((x) => <li key={x.index}><b>{x.type} {x.key}</b>: {x.outcome} {x.invariantsHold ? <Badge tone="good">valid</Badge> : <Badge tone="bad">invalid</Badge>}
              {x.events.length > 0 && <ul className="plain-list">{x.events.map((e, i) => <li key={i}><code>{e}</code></li>)}</ul>}</li>)}</ol>
            <p className="small">In-order: {r.inOrder.join(', ')}</p>
          </Card>
        )}
      </div>
      {r && <Card title="Final tree"><TreeView nodes={r.tree} /></Card>}
    </>
  );
}

interface SegResult { initialSeries: number[]; finalSeries: number[]; results: { index: number; type: string; from: number; to: number; value: number | null; result: number | null; argmaxHour: number | null; nodesVisited: number; matchesNaiveBaseline: boolean }[]; complexity: string }

function SegLab() {
  const [series, setSeries] = useState('120, 135, 150, 210, 260, 240, 190, 170, 160, 155, 140, 130');
  const [text, setText] = useState('SUM 0 11\nMAX 0 11\nADD 3 6 40\nMAX 0 11\nSET 4 0\nSUM 2 7');
  const [localErr, setLocalErr] = useState<string | null>(null);
  const req = useRequest<SegResult>();
  const run = () => {
    setLocalErr(null);
    try {
      const values = series.split(/[\s,]+/).filter(Boolean).map((v, i) => num(v, `value ${i + 1}`));
      const operations = lines(text).map((l, i) => {
        const [type, a, b, c] = l.split(/\s+/);
        const t = type.toUpperCase();
        if (t === 'SET') return { type: t, from: num(a, `line ${i + 1}`), value: num(b, `line ${i + 1}`) };
        return { type: t, from: num(a, `line ${i + 1}`), to: num(b, `line ${i + 1}`), value: c === undefined ? null : num(c, `line ${i + 1}`) };
      });
      void req.run(() => api.timeline<SegResult>({ series: values, operations }));
    } catch (e) { setLocalErr((e as Error).message); }
  };
  const r = req.data;
  return (
    <div className="grid-2">
      <Card title="Series and operations">
        <Field label="Series (e.g. hourly demand)"><textarea rows={2} value={series} onChange={(e) => setSeries(e.target.value)} /></Field>
        <Field label="SUM l r · MAX l r · ADD l r v · SET i v" hint="0-based, inclusive ranges"><textarea rows={7} value={text} onChange={(e) => setText(e.target.value)} /></Field>
        <Button busy={req.busy} onClick={run}>Run segment tree</Button>
        {localErr && <div className="alert error">{localErr}</div>}
        <ErrorBox error={req.error} />
      </Card>
      {r && (
        <Card title="Results (each checked against a plain array)">
          <table className="table small"><thead><tr><th>Op</th><th>Answer</th><th className="num">Nodes visited</th><th>Naive check</th></tr></thead>
            <tbody>{r.results.map((x) => <tr key={x.index}><td>{x.type} [{x.from}, {x.to}]{x.value !== null ? ` ${x.type === 'ADD' ? '+' : '='}${x.value}` : ''}</td>
              <td>{x.result !== null ? fmt(x.result) : 'updated'}{x.argmaxHour !== null ? ` at index ${x.argmaxHour}` : ''}</td><td className="num">{x.nodesVisited}</td>
              <td>{x.matchesNaiveBaseline ? <Badge tone="good">match</Badge> : <Badge tone="bad">differs</Badge>}</td></tr>)}</tbody></table>
          <p className="small">Final series: {r.finalSeries.join(', ')}</p>
          <p className="muted small">{r.complexity}</p>
        </Card>
      )}
    </div>
  );
}

interface SelectResult { n: number; results: { percentile: number; rank: number; value: number; comparisons: number; sortBaselineValue: number; matches: boolean }[]; sortComparisonsEstimate: number; notes: string[] }

function SelectLab() {
  const [values, setValues] = useState('42, 7, 19, 88, 3, 56, 71, 19, 64, 25, 90, 12, 37, 50, 8');
  const [pcts, setPcts] = useState('50, 90, 95');
  const [seed, setSeed] = useState(2026);
  const [localErr, setLocalErr] = useState<string | null>(null);
  const req = useRequest<SelectResult>();
  const run = () => {
    setLocalErr(null);
    try {
      const v = values.split(/[\s,]+/).filter(Boolean).map((x, i) => num(x, `value ${i + 1}`));
      const p = pcts.split(/[\s,]+/).filter(Boolean).map((x, i) => num(x, `percentile ${i + 1}`));
      void req.run(() => api.lab<SelectResult>('quickselect', { values: v, percentiles: p, seed }));
    } catch (e) { setLocalErr((e as Error).message); }
  };
  const r = req.data;
  return (
    <div className="grid-2">
      <Card title="Values">
        <Field label="Numbers"><textarea rows={3} value={values} onChange={(e) => setValues(e.target.value)} /></Field>
        <div className="form-grid"><Field label="Percentiles"><input value={pcts} onChange={(e) => setPcts(e.target.value)} /></Field><Field label="Pivot seed"><input type="number" value={seed} onChange={(e) => setSeed(Number(e.target.value))} /></Field></div>
        <Button busy={req.busy} onClick={run}>Run Quickselect</Button>
        {localErr && <div className="alert error">{localErr}</div>}
        <ErrorBox error={req.error} />
      </Card>
      {r && (
        <Card title={`n = ${r.n}`}>
          <table className="table small"><thead><tr><th>Percentile</th><th className="num">Rank</th><th className="num">Quickselect</th><th className="num">Comparisons</th><th className="num">Full sort</th><th>Match</th></tr></thead>
            <tbody>{r.results.map((x) => <tr key={x.percentile}><td>P{x.percentile}</td><td className="num">{x.rank}</td><td className="num">{fmt(x.value, 3)}</td><td className="num">{x.comparisons}</td><td className="num">{fmt(x.sortBaselineValue, 3)}</td><td>{x.matches ? <Badge tone="good">yes</Badge> : <Badge tone="bad">no</Badge>}</td></tr>)}</tbody></table>
          <p className="small">Sort baseline ≈ {r.sortComparisonsEstimate} comparisons.</p>
          <List items={r.notes} />
        </Card>
      )}
    </div>
  );
}

function TspLab() {
  const [labels, setLabels] = useState('Depot, A, B, C, D');
  const [matrix, setMatrix] = useState('0 4 6 5 7\n4 0 3 6 8\n6 3 0 4 5\n5 6 4 0 3\n7 8 5 3 0');
  const [localErr, setLocalErr] = useState<string | null>(null);
  const req = useRequest<RouteResponse>();
  const run = () => {
    setLocalErr(null);
    try {
      const m = lines(matrix).map((l, i) => l.split(/[\s,]+/).map((x, j) => num(x, `row ${i + 1} col ${j + 1}`)));
      const lb = labels.split(',').map((s) => s.trim()).filter(Boolean);
      void req.run(() => api.lab<RouteResponse>('tsp', { labels: lb, matrix: m, start: 0 }));
    } catch (e) { setLocalErr((e as Error).message); }
  };
  const r = req.data;
  return (
    <div className="grid-2">
      <Card title="Distance matrix">
        <Field label="Labels (comma-separated)"><input value={labels} onChange={(e) => setLabels(e.target.value)} /></Field>
        <Field label="Symmetric matrix, one row per line" hint="Change a value to break the triangle inequality: the server rejects it."><textarea rows={6} value={matrix} onChange={(e) => setMatrix(e.target.value)} /></Field>
        <Button busy={req.busy} onClick={run}>Validate and solve</Button>
        {localErr && <div className="alert error">{localErr}</div>}
        <ErrorBox error={req.error} />
      </Card>
      {r && (
        <Card title={`Tour ${fmt(r.routeKm, 3)} (approximate)`}>
          <p><code>{[...r.tour, r.tour[0]].join(' → ')}</code></p>
          <table className="table small"><tbody>
            <tr><td>MST weight (lower bound on OPT)</td><td className="num">{fmt(r.mstLowerBoundKm, 3)}</td></tr>
            <tr><td>Nearest-neighbour baseline</td><td className="num">{fmt(r.nearestNeighbourKm, 3)} <code>{r.nearestNeighbourTour.join('→')}</code></td></tr>
            <tr><td>Exact optimum (Held-Karp, small n)</td><td className="num">{r.exactKm === null ? 'not computed' : fmt(r.exactKm, 3)}</td></tr>
            <tr><td>Certified ratio bound (tour / MST)</td><td className="num">{fmt(r.certifiedRatioBound, 3)}</td></tr>
          </tbody></table>
          <p className="small">MST edges: {r.mstEdges.map((e) => e.join('-')).join(', ')}</p>
          <p className="small muted">{r.guarantee}</p>
        </Card>
      )}
    </div>
  );
}
