import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { CityDataset, DatasetSummary } from '../lib/types';
import { NetworkMap } from '../components/NetworkMap';
import { Badge, Button, Card, ErrorBox, Field, Metric, Notice, PageHeader, SyntheticBanner, fmt } from '../components/ui';

const SAMPLE_NODES = `id,name,type,x,y,supply,demand,severity,vulnerablePercent,population
D1,Test Depot,DEPOT,0,0,500,,,,
J1,Test Junction,JUNCTION,5,0,,,,,
Z1,"Ward 1, Riverside",DEMAND_ZONE,10,0,,300,4,35,5000`;
const SAMPLE_ROADS = `id,from,to,capacity,lengthKm,oneWay
R1,D1,J1,400,5.5,false
R2,J1,Z1,250,5.2,`;

export function DataPage() {
  const { dataset, setDataset, resetDemo } = useAppState();
  const check = useRequest<DatasetSummary>();
  const csv = useRequest<{ dataset: CityDataset; summary: DatasetSummary }>();
  const [draft, setDraft] = useState<CityDataset | null>(dataset);
  const [nodesCsv, setNodesCsv] = useState(SAMPLE_NODES);
  const [roadsCsv, setRoadsCsv] = useState(SAMPLE_ROADS);
  const [fileError, setFileError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const runCheck = check.run;

  useEffect(() => { setDraft(dataset); }, [dataset]);
  useEffect(() => { if (dataset) void runCheck(() => api.validate(dataset)); }, [dataset, runCheck]);

  if (!dataset || !draft) return null;
  const s = check.data;

  const setRoad = (i: number, field: 'capacity' | 'lengthKm', value: string) => {
    const roads = draft.roads.map((r, k) => (k === i ? { ...r, [field]: value === '' ? (null as unknown as number) : Number(value) } : r));
    setDraft({ ...draft, roads });
    setSaved(false);
  };
  const setNode = (i: number, field: 'supply' | 'demand' | 'severity' | 'vulnerablePercent', value: string) => {
    const nodes = draft.nodes.map((n, k) => (k === i ? { ...n, [field]: value === '' ? null : Number(value) } : n));
    setDraft({ ...draft, nodes });
    setSaved(false);
  };
  const apply = async () => {
    const ok = await check.run(() => api.validate(draft));
    if (ok) { setDataset(draft); setSaved(true); }
  };
  const upload = async (file: File) => {
    setFileError(null);
    if (file.size > 1_500_000) { setFileError('File is larger than 1.5 MB.'); return; }
    let parsed: CityDataset;
    try {
      parsed = JSON.parse(await file.text()) as CityDataset;
    } catch {
      setFileError('The file is not valid JSON.');
      return;
    }
    const ok = await check.run(() => api.validate(parsed)); // the server decides what is valid
    if (ok) setDataset(parsed);
  };
  const download = () => {
    const blob = new Blob([JSON.stringify(dataset, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'nexus-dataset.json';
    a.click();
    URL.revokeObjectURL(a.href);
  };

  return (
    <>
      <PageHeader kicker="Network & data" title="City model, validation and import">
        The dataset lives in your browser and is sent to the Java API with every analysis. The server validates it strictly and
        rejects malformed records, negative capacities, duplicate ids and unknown road endpoints.
      </PageHeader>
      {dataset.meta?.synthetic ? <SyntheticBanner /> : <Notice tone="warn">Custom dataset loaded: it is not marked as synthetic demo data.</Notice>}
      <div className="metrics">
        <Metric label="Nodes" value={fmt(s?.nodes)} hint={s ? `${s.depots} depots · ${s.junctions} junctions · ${s.demandZones} zones` : ''} />
        <Metric label="Roads" value={fmt(s?.roads)} hint={s ? `${s.oneWayRoads} one-way · ${fmt(s.totalRoadKm, 1)} km` : ''} />
        <Metric label="Supply / demand" value={s ? `${fmt(s.totalSupply)} / ${fmt(s.totalDemand)}` : '-'} unit="kits" />
        <Metric label="Incidents" value={fmt(s?.incidents)} hint={s ? `${s.timelineHours}-hour demand timeline` : ''} />
      </div>
      <div className="grid-2">
        <Card title="Map"><NetworkMap dataset={dataset} height={400} /></Card>
        <Card title="Load, save and reset">
          <div className="row wrap">
            <Button variant="ghost" onClick={() => void resetDemo()}>Reset to synthetic demo</Button>
            <Button variant="ghost" onClick={download}>Download JSON</Button>
            <label className="btn ghost file-btn">Upload JSON…<input type="file" accept="application/json,.json" onChange={(e) => { const f = e.target.files?.[0]; if (f) void upload(f); e.target.value = ''; }} /></label>
          </div>
          {fileError && <div className="alert error">{fileError}</div>}
          <ErrorBox error={check.error} />
          {saved && !check.error && <Notice tone="ok">Changes validated by the server and applied.</Notice>}
          <h3>Import from CSV</h3>
          <Field label="nodes.csv" hint="id,name,type,x,y,supply,demand,severity,vulnerablePercent,population"><textarea rows={4} value={nodesCsv} onChange={(e) => setNodesCsv(e.target.value)} /></Field>
          <Field label="roads.csv" hint="id,from,to,capacity,lengthKm,oneWay"><textarea rows={3} value={roadsCsv} onChange={(e) => setRoadsCsv(e.target.value)} /></Field>
          <div className="row"><Button variant="ghost" busy={csv.busy} onClick={() => void csv.run(async () => { const r = await api.importCsv(nodesCsv, roadsCsv); setDataset(r.dataset); return r; })}>Import CSV (replaces dataset)</Button></div>
          <ErrorBox error={csv.error} />
        </Card>
      </div>
      <Card title="Edit roads (capacity is throughput, length is distance - never mixed)" actions={<Button busy={check.busy} onClick={() => void apply()}>Validate &amp; apply edits</Button>}>
        <div className="table-scroll"><table className="table small"><thead><tr><th>Road</th><th>From → To</th><th>Direction</th><th>Capacity (kits/12 h)</th><th>Length (km)</th></tr></thead>
          <tbody>{draft.roads.map((r, i) => (
            <tr key={r.id}><td>{r.id}</td><td>{r.from} → {r.to}</td><td>{r.oneWay ? <Badge tone="info">one-way</Badge> : 'two-way'}</td>
              <td><input aria-label={`Capacity of ${r.id}`} className="cell-input" type="number" value={r.capacity ?? ''} onChange={(e) => setRoad(i, 'capacity', e.target.value)} /></td>
              <td><input aria-label={`Length of ${r.id}`} className="cell-input" type="number" step="0.1" value={r.lengthKm ?? ''} onChange={(e) => setRoad(i, 'lengthKm', e.target.value)} /></td></tr>))}</tbody></table></div>
      </Card>
      <Card title="Edit facilities">
        <div className="table-scroll"><table className="table small"><thead><tr><th>Id</th><th>Name</th><th>Type</th><th>Supply</th><th>Demand</th><th>Severity</th><th>Vulnerable %</th></tr></thead>
          <tbody>{draft.nodes.map((n, i) => (
            <tr key={n.id}><td>{n.id}</td><td>{n.name}</td><td>{n.type}</td>
              <td>{n.type === 'DEPOT' ? <input aria-label={`Supply of ${n.id}`} className="cell-input" type="number" value={n.supply ?? ''} onChange={(e) => setNode(i, 'supply', e.target.value)} /> : '-'}</td>
              <td>{n.type === 'DEMAND_ZONE' ? <input aria-label={`Demand of ${n.id}`} className="cell-input" type="number" value={n.demand ?? ''} onChange={(e) => setNode(i, 'demand', e.target.value)} /> : '-'}</td>
              <td>{n.type === 'DEMAND_ZONE' ? <input aria-label={`Severity of ${n.id}`} className="cell-input" type="number" value={n.severity ?? ''} onChange={(e) => setNode(i, 'severity', e.target.value)} /> : '-'}</td>
              <td>{n.type === 'DEMAND_ZONE' ? <input aria-label={`Vulnerable percent of ${n.id}`} className="cell-input" type="number" value={n.vulnerablePercent ?? ''} onChange={(e) => setNode(i, 'vulnerablePercent', e.target.value)} /> : '-'}</td></tr>))}</tbody></table></div>
        <p className="muted small">Try entering a negative capacity or severity 9 and press "Validate &amp; apply": the server rejects it and names the field.</p>
      </Card>
    </>
  );
}
