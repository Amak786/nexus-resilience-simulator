import { useEffect } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { DatasetSummary, FlowResponse } from '../lib/types';
import { NetworkMap, roadStylesFromFlow } from '../components/NetworkMap';
import { Badge, Button, Card, ErrorBox, Metric, PageHeader, SyntheticBanner, fmt } from '../components/ui';

export function Overview() {
  const { dataset, setLastFlow } = useAppState();
  const summary = useRequest<DatasetSummary>();
  const flow = useRequest<FlowResponse>();
  const runSummary = summary.run;
  const runFlow = flow.run;

  useEffect(() => {
    if (!dataset) return;
    void runSummary(() => api.validate(dataset));
    void runFlow(async () => {
      const r = await api.flow({ dataset, algorithm: 'COMPARE', includeTrace: false });
      setLastFlow(r);
      return r;
    });
  }, [dataset, runSummary, runFlow, setLastFlow]);

  if (!dataset) return null;
  const s = summary.data;
  const f = flow.data;
  const cutRoads = f?.minCut.filter((c) => c.kind === 'ROAD') ?? [];
  const worstZones = f?.zones ? [...f.zones].sort((a, b) => b.unmet - a.unmet).filter((z) => z.unmet > 0).slice(0, 5) : [];

  return (
    <>
      <section className="hero">
        <div className="hero-text">
          <div className="hero-tag">NEXUS · infrastructure resilience engine · prototype</div>
          <h1>When a bridge fails, who loses relief and what do we fix first?</h1>
          <p>
            NEXUS turns a city's roads, depots and shelters into a network it can reason about. It proves how much aid the network can move,
            stress-tests it against failures, and ranks repairs by how much delivery capacity they win back, in seconds and with a mathematical certificate.
          </p>
          <div className="hero-cta">
            <a className="btn primary" href="#/failure-lab">Run the bridge-collapse scenario →</a>
            <a className="btn ghost" href="#/methodology">How it works</a>
          </div>
        </div>
        <div className="hero-steps">
          <div className="hero-step"><span>01</span><b>Measure</b><p>Exact maximum deliverable throughput, certified by an equal minimum cut (Edmonds-Karp).</p></div>
          <div className="hero-step"><span>02</span><b>Stress-test</b><p>Failures re-solved instantly; impact ranked against hundreds of seeded random failures (Quickselect).</p></div>
          <div className="hero-step"><span>03</span><b>Act</b><p>Recovery options ranked by recovered throughput; worst hours located with a segment tree.</p></div>
        </div>
      </section>
      <PageHeader kicker="Overview" title="City resilience at a glance">
        Every number on this page is computed by the Java API from the current dataset ({dataset.meta?.name ?? 'custom data'}).
      </PageHeader>
      <SyntheticBanner />
      <ErrorBox error={summary.error ?? flow.error} />
      <div className="metrics">
        <Metric label="Depot supply" value={fmt(s?.totalSupply)} unit="kits / 12 h" hint={`${s?.depots ?? '-'} depots`} />
        <Metric label="Zone demand" value={fmt(s?.totalDemand)} unit="kits / 12 h" hint={`${s?.demandZones ?? '-'} demand zones`} />
        <Metric label="Deliverable (max flow)" value={fmt(f?.maxFlow)} unit="kits / 12 h" tone="good"
          hint={f ? `certified by an equal min cut (${fmt(f.minCutCapacity)})` : 'computing…'} />
        <Metric label="Unmet demand" value={fmt(f?.unmetDemand)} unit="kits" tone={f && f.unmetDemand ? 'warn' : 'good'}
          hint={f && f.totalDemand ? `${fmt((100 * (f.unmetDemand ?? 0)) / f.totalDemand, 1)}% of demand` : ''} />
        <Metric label="Critical cut roads" value={fmt(cutRoads.length)} tone={cutRoads.length ? 'bad' : undefined} hint="saturated roads on the min cut" />
        <Metric label="Last run" value={f ? (f.maxFlowEqualsMinCut ? 'Certified' : 'Check') : flow.busy ? 'Running' : '-'}
          tone={f?.maxFlowEqualsMinCut ? 'good' : undefined}
          hint={f ? `EK ${f.primary.augmentations} paths, ${fmt(f.primary.elapsedMs, 2)} ms · FF agrees: ${f.baselineAgrees ? 'yes' : 'no'}` : ''} />
      </div>
      <div className="grid-2">
        <Card title="Network flow (Edmonds-Karp)" actions={<Button variant="ghost" busy={flow.busy} onClick={() => dataset && void runFlow(() => api.flow({ dataset, algorithm: 'COMPARE', includeTrace: false }))}>Re-run</Button>}>
          {f ? <NetworkMap dataset={dataset} roadStyles={roadStylesFromFlow(f.edges)} caption="Road labels show flow/capacity in kits per 12-hour window (both directions summed)." />
            : <div className="skeleton" style={{ height: 360 }} />}
        </Card>
        <div className="stack">
          <Card title="Bottlenecks on the minimum cut">
            {cutRoads.length === 0 ? <p className="muted">No road is on the minimum cut: the binding limits are depot stock or zone demand.</p> : (
              <ul className="plain-list">{cutRoads.map((c) => <li key={c.index}><Badge tone="bad">{c.roadId}</Badge> {c.explanation}</li>)}</ul>
            )}
            {f && f.minCut.filter((c) => c.kind === 'SUPPLY').length > 0 && (
              <p className="muted small">Also on the cut: {f.minCut.filter((c) => c.kind === 'SUPPLY').map((c) => c.to).join(', ')} (depot stock fully used).</p>
            )}
          </Card>
          <Card title="Most under-served zones">
            {worstZones.length === 0 ? <p className="muted">All demand can be met.</p> : (
              <table className="table"><thead><tr><th>Zone</th><th className="num">Demand</th><th className="num">Unmet</th></tr></thead>
                <tbody>{worstZones.map((z) => <tr key={z.id}><td>{z.id} · {z.name}</td><td className="num">{fmt(z.demand)}</td><td className="num warn-text">{fmt(z.unmet)}</td></tr>)}</tbody></table>
            )}
          </Card>
          <Card title="Workflows">
            <div className="links">
              <a href="#/failure-lab">Failure Lab → simulate a bridge collapse</a>
              <a href="#/flow">Flow Optimizer → replay augmenting paths</a>
              <a href="#/incidents">Incident queue → red-black tree</a>
              <a href="#/screening">Duplicate screening → Bloom filter</a>
              <a href="#/evidence">Benchmarks &amp; live self-check</a>
            </div>
          </Card>
        </div>
      </div>
    </>
  );
}
