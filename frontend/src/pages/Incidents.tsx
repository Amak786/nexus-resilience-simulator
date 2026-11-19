import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { PriorityOperation, PriorityResponse } from '../lib/types';
import { TreeView } from '../components/TreeView';
import { Badge, Button, Card, Complexity, ErrorBox, Field, Metric, Notice, PageHeader, fmt } from '../components/ui';

export function Incidents() {
  const { dataset } = useAppState();
  const [ops, setOps] = useState<PriorityOperation[]>([]);
  const [kind, setKind] = useState<PriorityOperation['type']>('INSERT');
  const [id, setId] = useState('INC-2001');
  const [title, setTitle] = useState('Culvert collapse near Old Town');
  const [zone, setZone] = useState('Z2');
  const [severity, setSeverity] = useState(4);
  const [people, setPeople] = useState(2500);
  const req = useRequest<PriorityResponse>();
  const runReq = req.run;

  useEffect(() => {
    if (dataset) void runReq(() => api.priority(dataset, ops));
  }, [dataset, ops, runReq]);

  if (!dataset) return null;
  const r = req.data;
  const zones = dataset.nodes.filter((n) => n.type === 'DEMAND_ZONE');

  const submit = () => {
    const op: PriorityOperation = kind === 'INSERT' ? { type: kind, id, title, zoneId: zone || null, severity, affectedPeople: people }
      : kind === 'UPDATE' ? { type: kind, id, severity, affectedPeople: people } : { type: kind, id };
    setOps([...ops, op]);
  };
  const lastOp = r?.operations[r.operations.length - 1];

  return (
    <>
      <PageHeader kicker="Incident priority index" title="Red-black tree queue of open incidents">
        Incidents are ordered by a transparent triage score (highest first, ties broken by id). Every change is applied to a
        red-black tree on the server, which reports rotations, fix-up cases and invariant checks.
      </PageHeader>
      <div className="grid-2">
        <Card title="Apply an operation">
          <div className="form-grid">
            <Field label="Operation"><select value={kind} onChange={(e) => setKind(e.target.value as PriorityOperation['type'])}>
              <option value="INSERT">Insert new incident</option><option value="UPDATE">Update severity / people</option>
              <option value="RESOLVE">Resolve (delete)</option><option value="LOOKUP">Look up rank</option></select></Field>
            <Field label="Incident id"><input value={id} onChange={(e) => setId(e.target.value)} list="incident-ids" /></Field>
            {kind === 'INSERT' && <Field label="Title"><input value={title} onChange={(e) => setTitle(e.target.value)} /></Field>}
            {kind === 'INSERT' && <Field label="Zone"><select value={zone} onChange={(e) => setZone(e.target.value)}><option value="">none</option>
              {zones.map((z) => <option key={z.id} value={z.id}>{z.id} · {z.name}</option>)}</select></Field>}
            {(kind === 'INSERT' || kind === 'UPDATE') && <Field label="Severity (1-5)"><input type="number" min={1} max={5} value={severity} onChange={(e) => setSeverity(Number(e.target.value))} /></Field>}
            {(kind === 'INSERT' || kind === 'UPDATE') && <Field label="Affected people"><input type="number" min={0} value={people} onChange={(e) => setPeople(Number(e.target.value))} /></Field>}
          </div>
          <datalist id="incident-ids">{r?.queue.map((q) => <option key={q.id} value={q.id} />)}</datalist>
          <div className="row">
            <Button busy={req.busy} onClick={submit}>Apply</Button>
            <Button variant="ghost" onClick={() => setOps([])} disabled={ops.length === 0}>Reset to dataset incidents</Button>
          </div>
          <ErrorBox error={req.error} />
          {req.error && ops.length > 0 && <Button variant="ghost" onClick={() => setOps(ops.slice(0, -1))}>Undo last (rejected) operation</Button>}
          {lastOp && !req.error && (
            <Notice tone="ok"><b>{lastOp.type} {lastOp.id}:</b> {lastOp.outcome}. Rotations: {lastOp.rotations}.
              {lastOp.events.length > 0 && <ul className="plain-list small">{lastOp.events.map((e, i) => <li key={i}><code>{e}</code></li>)}</ul>}</Notice>
          )}
          <p className="muted small">{r?.scoreFormula}</p>
        </Card>
        <Card title="Tree metrics">
          {r && (
            <>
              <div className="metrics compact">
                <Metric label="Incidents" value={r.metrics.size} />
                <Metric label="Height" value={r.metrics.height} hint={`bound 2·log₂(n+1) = ${r.metrics.heightUpperBound}`} />
                <Metric label="Black height" value={r.metrics.blackHeight} hint="same on every path" />
                <Metric label="Rotations so far" value={r.metrics.rotations} />
              </div>
              <p>{r.metrics.invariantsHold ? <Badge tone="good">all invariants hold</Badge> : <Badge tone="bad">{r.metrics.invariantMessage}</Badge>}
                <span className="muted small"> root black · no red-red · equal black height · BST order · sizes</span></p>
            </>
          )}
          <Complexity time="O(log n) insert / delete / lookup / rank; O(n) in-order queue" space="O(n)" />
          <p className="muted small">{r?.disclaimer}</p>
        </Card>
      </div>
      {r && (
        <>
          <Card title="Tree structure (keys are score/id)"><TreeView nodes={r.tree} /></Card>
          <Card title="Priority queue (in-order traversal)">
            <table className="table"><thead><tr><th>Rank</th><th>Incident</th><th>Zone</th><th className="num">Severity</th><th className="num">People</th><th className="num">Zone bonus</th><th className="num">Score</th></tr></thead>
              <tbody>{r.queue.map((q) => <tr key={q.id}><td>{q.rank}</td><td><b>{q.id}</b> {q.title}</td><td>{q.zoneId ?? '-'}</td><td className="num">{q.severity}</td><td className="num">{fmt(q.affectedPeople)}</td><td className="num">{q.vulnerabilityBonus}</td><td className="num"><b>{q.score}</b></td></tr>)}</tbody></table>
          </Card>
        </>
      )}
    </>
  );
}
