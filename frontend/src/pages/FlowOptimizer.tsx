import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { FlowResponse } from '../lib/types';
import { NetworkMap, roadStylesFromFlow } from '../components/NetworkMap';
import { FailurePicker, noFailures, toggle, type Failures } from '../components/FailurePicker';
import { Badge, Button, Card, Complexity, ErrorBox, Field, List, Metric, Notice, PageHeader, fmt } from '../components/ui';

export function FlowOptimizer() {
  const { dataset, setLastFlow } = useAppState();
  const [source, setSource] = useState('SUPER_SOURCE');
  const [sink, setSink] = useState('SUPER_SINK');
  const [algorithm, setAlgorithm] = useState('COMPARE');
  const [fail, setFail] = useState<Failures>(noFailures());
  const [step, setStep] = useState(0);
  const [playing, setPlaying] = useState(false);
  const req = useRequest<FlowResponse>();
  const r = req.data;

  useEffect(() => {
    if (!playing || !r) return;
    if (step >= r.trace.length) { setPlaying(false); return; }
    const t = setTimeout(() => setStep((s) => s + 1), 700);
    return () => clearTimeout(t);
  }, [playing, step, r]);

  if (!dataset) return null;
  const run = async () => {
    setStep(0);
    setPlaying(false);
    const out = await req.run(() => api.flow({ dataset, source, sink, algorithm, failedRoadIds: [...fail.roads], failedNodeIds: [...fail.nodes], includeTrace: true }));
    if (out && out.source === 'SUPER_SOURCE' && out.sink === 'SUPER_SINK') setLastFlow(out);
  };

  // Replay: step 0 = final flow; step k = state after augmenting path k (edge flows recorded by the Java solver).
  const current = r && step > 0 ? r.trace[step - 1] : null;
  const edgesForMap = r ? r.edges.map((e) => ({ ...e, flow: current?.edgeFlows ? current.edgeFlows[e.index] : e.flow, inMinCut: current ? false : e.inMinCut })) : [];
  const pathRoads = new Set<string>();
  current?.arcs.forEach((a) => { const e = r?.edges[a.edge]; if (e?.roadId) pathRoads.add(e.roadId); });

  return (
    <>
      <PageHeader kicker="Flow Optimizer" title="Max flow, min cut and a replay of every augmenting path">
        Directed capacity network: each two-way road is two arcs with the road's capacity; a super source feeds depots (capacity = stock) and
        demand zones drain to a super sink (capacity = demand).
      </PageHeader>
      <div className="grid-2">
        <Card title="Inputs">
          <div className="form-grid">
            <Field label="Source"><select value={source} onChange={(e) => setSource(e.target.value)}>
              <option value="SUPER_SOURCE">SUPER_SOURCE (all depots)</option>
              {dataset.nodes.map((n) => <option key={n.id} value={n.id}>{n.id} · {n.name}</option>)}</select></Field>
            <Field label="Sink"><select value={sink} onChange={(e) => setSink(e.target.value)}>
              <option value="SUPER_SINK">SUPER_SINK (all demand zones)</option>
              {dataset.nodes.map((n) => <option key={n.id} value={n.id}>{n.id} · {n.name}</option>)}</select></Field>
            <Field label="Algorithm"><select value={algorithm} onChange={(e) => setAlgorithm(e.target.value)}>
              <option value="COMPARE">Edmonds-Karp, compared with Ford-Fulkerson</option>
              <option value="EDMONDS_KARP">Edmonds-Karp (BFS) only</option>
              <option value="FORD_FULKERSON">Ford-Fulkerson (DFS) only</option></select></Field>
          </div>
          <FailurePicker dataset={dataset} value={fail} onChange={setFail} />
          <div className="row"><Button busy={req.busy} onClick={() => void run()}>Run max flow</Button></div>
          <ErrorBox error={req.error} />
          {r?.warnings.map((w) => <Notice key={w} tone="warn">{w}</Notice>)}
        </Card>
        <Card title="Result">
          {!r ? <p className="muted">Run the solver to see the maximum flow, its certificate and the trace.</p> : (
            <>
              <div className="metrics compact">
                <Metric label="Maximum flow" value={fmt(r.maxFlow)} unit="kits" tone="good" />
                <Metric label="Min-cut capacity" value={fmt(r.minCutCapacity)} unit="kits" hint={r.maxFlowEqualsMinCut ? 'equal: flow is certified optimal' : 'MISMATCH'} />
                {r.unmetDemand !== null && <Metric label="Unmet demand" value={fmt(r.unmetDemand)} unit="kits" tone={r.unmetDemand ? 'warn' : 'good'} />}
              </div>
              <table className="table"><thead><tr><th>Run</th><th className="num">Value</th><th className="num">Augmenting paths</th><th className="num">Arc scans</th><th className="num">Time (ms)</th></tr></thead>
                <tbody>
                  <tr><td>{r.primary.algorithm}</td><td className="num">{fmt(r.primary.maxFlow)}</td><td className="num">{r.primary.augmentations}</td><td className="num">{fmt(r.primary.arcScans)}</td><td className="num">{fmt(r.primary.elapsedMs, 3)}</td></tr>
                  {r.baseline && <tr><td>{r.baseline.algorithm}</td><td className="num">{fmt(r.baseline.maxFlow)}</td><td className="num">{r.baseline.augmentations}</td><td className="num">{fmt(r.baseline.arcScans)}</td><td className="num">{fmt(r.baseline.elapsedMs, 3)}</td></tr>}
                </tbody></table>
              {r.baseline && <p className="small">Same maximum value: {r.baselineAgrees ? <Badge tone="good">yes</Badge> : <Badge tone="bad">no</Badge>}. Paths and per-edge flows may differ; that is expected.</p>}
              <Complexity time="Edmonds-Karp O(V E²); Ford-Fulkerson O(E·|f*|)" space="O(V + E)" />
            </>
          )}
        </Card>
      </div>
      {r && (
        <>
          <Card title="Step-by-step replay of the backend trace" actions={
            <div className="row tight">
              <Button variant="ghost" onClick={() => { setPlaying(false); setStep(Math.max(0, step - 1)); }} disabled={step === 0}>◀ Prev</Button>
              <Button variant="ghost" onClick={() => { if (step >= r.trace.length) setStep(0); setPlaying(!playing); }}>{playing ? 'Pause' : 'Play'}</Button>
              <Button variant="ghost" onClick={() => { setPlaying(false); setStep(Math.min(r.trace.length, step + 1)); }} disabled={step >= r.trace.length}>Next ▶</Button>
            </div>}>
            <input type="range" min={0} max={r.trace.length} value={step} onChange={(e) => { setPlaying(false); setStep(Number(e.target.value)); }} aria-label="Trace step" className="slider" />
            <p className="small">
              {current ? <>Step <b>{current.step}</b> of {r.trace.length}: path <code>{current.nodes.join(' → ')}</code>, bottleneck <b>{fmt(current.bottleneck)}</b>, total flow <b>{fmt(current.flowAfter)}</b>
                {current.arcs.some((a) => a.reverse) && <> · <Badge tone="info">uses a residual reverse arc (cancels earlier flow)</Badge></>}</>
                : <>Showing the final flow and min cut. Move the slider to replay {r.trace.length} augmenting paths{r.traceTruncated ? ' (trace truncated at 200)' : ''}.</>}
            </p>
            <NetworkMap dataset={dataset} failedRoads={fail.roads} failedNodes={fail.nodes} roadStyles={roadStylesFromFlow(edgesForMap, pathRoads)}
              highlightNodes={current ? new Set(current.nodes) : undefined} height={460}
              onRoadClick={(id) => setFail({ ...fail, roads: toggle(fail.roads, id) })} />
          </Card>
          <div className="grid-2">
            <Card title="Bottleneck explanations (min cut)">
              <ul className="plain-list">{r.minCut.map((c) => <li key={c.index}><Badge tone={c.kind === 'ROAD' ? 'bad' : 'neutral'}>{c.kind}</Badge> {c.explanation}</li>)}</ul>
              <p className="muted small">Residual-reachable from the source: {r.sourceSideNodes.join(', ')}</p>
            </Card>
            {r.zones && (
              <Card title="Allocation by demand zone">
                <table className="table"><thead><tr><th>Zone</th><th className="num">Demand</th><th className="num">Received</th><th className="num">Unmet</th><th>Status</th></tr></thead>
                  <tbody>{r.zones.map((z) => <tr key={z.id}><td>{z.id} · {z.name}</td><td className="num">{fmt(z.demand)}</td><td className="num">{fmt(z.received)}</td><td className="num">{fmt(z.unmet)}</td>
                    <td><Badge tone={z.status === 'MET' ? 'good' : z.status === 'PARTIAL' ? 'warn' : 'bad'}>{z.status}</Badge></td></tr>)}</tbody></table>
              </Card>
            )}
          </div>
          <Card title="Per-edge flow and capacity">
            <div className="table-scroll"><table className="table small"><thead><tr><th>#</th><th>Kind</th><th>Road</th><th>From → To</th><th className="num">Flow</th><th className="num">Capacity</th><th>State</th></tr></thead>
              <tbody>{r.edges.map((e) => <tr key={e.index}><td>{e.index}</td><td>{e.kind}</td><td>{e.roadId ?? '-'}</td><td>{e.from} → {e.to}</td><td className="num">{fmt(e.flow)}</td><td className="num">{fmt(e.capacity)}</td>
                <td>{e.inMinCut ? <Badge tone="bad">min cut</Badge> : e.saturated ? <Badge tone="warn">saturated</Badge> : e.flow > 0 ? <Badge tone="good">used</Badge> : <Badge>idle</Badge>}</td></tr>)}</tbody></table></div>
          </Card>
          <Card title="Modelling assumptions"><List items={r.assumptions} /></Card>
        </>
      )}
    </>
  );
}
