import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import { useAppState, useRequest } from '../lib/state';
import type { FindingsResponse, Investigation, Lookup, Observation, Status } from '../lib/types';
import { Badge, Button, Card, ErrorBox, List, PageHeader } from '../components/ui';
import { FindingCard, NeedsViewer, ago, time } from '../components/common';

const hashIp = () => new URLSearchParams(window.location.hash.split('?')[1] ?? '').get('ip') ?? '';

export function Investigations() {
  const { viewer, tick } = useAppState();
  const [ip, setIp] = useState(hashIp());
  const inv = useRequest<Lookup>();
  const fs = useRequest<FindingsResponse>();
  const obs = useRequest<Observation[]>();
  const [filter, setFilter] = useState('');
  const runInv = inv.run;
  const runFs = fs.run;
  const runObs = obs.run;
  useEffect(() => { const q = hashIp(); if (q) { setIp(q); void runInv(() => api.lookup(q)); } }, [runInv]);
  useEffect(() => { if (viewer) { void runFs(() => api.findings(10)); void runObs(() => api.observations()); } }, [viewer, tick, runFs, runObs]);
  const look = () => { if (ip.trim()) void inv.run(() => api.lookup(ip.trim())); };
  const decide = async (id: string, status: Status) => { await api.decide(id, status); void fs.run(() => api.findings(10)); };
  const rows = (obs.data ?? []).filter((o) => !filter || o.remoteAddress.includes(filter) || (o.process ?? '').toLowerCase().includes(filter.toLowerCase()) || String(o.remotePort) === filter);

  return (
    <>
      <PageHeader kicker="Investigations" title="Evidence for each address and finding">
        Look up any IP address or domain against the downloaded threat lists and the announcing network. Signed-in viewers also see local observations and
        the findings produced by TRACE's rules. A reputation match is evidence, not proof.
      </PageHeader>
      <Card title="Investigate an IP address or domain">
        <form className="row" onSubmit={(e) => { e.preventDefault(); look(); }}>
          <input value={ip} onChange={(e) => setIp(e.target.value)} placeholder="e.g. 8.8.8.8, 2001:4860:4860::8888 or example.com" aria-label="IP address or domain" style={{ maxWidth: 360 }} />
          <Button type="submit" busy={inv.busy} disabled={!ip.trim()}>Investigate</Button>
        </form>
        <p className="small muted">A domain is resolved with DNS on the server and every address is checked; TRACE never connects to the host. URLs, ports and paths are
          refused. Private and reserved addresses are never sent to external services.</p>
        <ErrorBox error={inv.error} />
        {inv.data && inv.data.kind === 'DOMAIN' && (
          <p className="small"><b>{inv.data.domain}</b> resolved to {inv.data.results.length} address(es) at {time(inv.data.resolvedAt)}
            {inv.data.dnsError && <span className="warn-text"> · {inv.data.dnsError}</span>}<br /><span className="muted">{inv.data.note}</span></p>
        )}
        {inv.data?.results.map((r) => <InvestigationView key={r.ip} r={r} />)}
      </Card>
      <NeedsViewer>
        <ErrorBox error={fs.error} />
        {fs.data && (
          <div className="grid-2">
            <Card title={`Findings (${fs.data.findings.length})`}>
              <p className="small muted">Top-{fs.data.ranking.k} by randomized Quickselect (k-th score {fs.data.ranking.kthScore}, {fs.data.ranking.quickselectComparisons} comparisons,
                {fs.data.ranking.matchesSort ? ' matches a full sort' : ' MISMATCH'}); ordered by a red-black tree of {fs.data.ranking.indexSize} entries (height {fs.data.ranking.indexHeight}{fs.data.ranking.indexValid ? ', invariants hold' : ''}).</p>
              {fs.data.findings.length === 0 && <p className="small">No rule fired on the current observations.</p>}
              {fs.data.findings.map((f) => (
                <FindingCard key={f.id} f={f} actions={
                  <div className="row small">
                    {(['ACKNOWLEDGED', 'DISMISSED', 'CONFIRMED_INCIDENT', 'OPEN'] as Status[]).filter((s) => s !== f.status).map((s) =>
                      <Button key={s} variant="ghost" onClick={() => void decide(f.id, s)}>{s === 'CONFIRMED_INCIDENT' ? 'Confirm incident (analyst)' : s.toLowerCase()}</Button>)}
                    <a href={`#/investigations?ip=${f.subject}`} onClick={() => { setIp(f.subject); void inv.run(() => api.lookup(f.subject)); }}>investigate</a>
                  </div>} />
              ))}
              <details className="small"><summary>Scoring rule</summary><List items={fs.data.scoringRule} />
                <p>Common ports (not flagged): {fs.data.commonPorts.join(', ')}</p></details>
            </Card>
            <Card title="Observed connections">
              <input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="filter by address, port or process" aria-label="Filter observations" />
              <div className="table-scroll"><table className="table small">
                <thead><tr><th>Remote</th><th>Port</th><th>Process</th><th>Sensor</th><th className="num">Obs.</th><th>First</th><th>Last</th></tr></thead>
                <tbody>{rows.map((o, i) => <tr key={i}><td><a href={`#/investigations?ip=${o.remoteAddress}`} onClick={() => { setIp(o.remoteAddress); void inv.run(() => api.lookup(o.remoteAddress)); }}>{o.remoteAddress}</a></td>
                  <td>{o.protocol} {o.remotePort}</td><td>{o.process ?? '-'}</td><td>{o.sensorId}</td><td className="num">{o.observations}</td><td title={time(o.firstSeen)}>{ago(o.firstSeen)}</td><td title={time(o.lastSeen)}>{ago(o.lastSeen)}</td></tr>)}</tbody>
              </table></div>
              <p className="small muted">Observation time comes from the sensor; ingestion time is recorded separately by the server.</p>
            </Card>
          </div>
        )}
      </NeedsViewer>
    </>
  );
}

function InvestigationView({ r }: { r: Investigation }) {
  return (
    <div className="two-col lookup-result">
      <div>
        <p><b>{r.ip}</b> <Badge tone="neutral">{r.scope.toLowerCase()}</Badge> {r.intel.length > 0 && <Badge tone="warn">on {r.intel.length} threat list(s)</Badge>}</p>
        <p className="small"><b>Assessment:</b> {r.assessment}</p>
        {r.intel.map((m) => <p key={m.list} className="small"><Badge tone="warn">{m.list}</Badge> {m.detail} <a href={m.sourceUrl} target="_blank" rel="noreferrer">source</a> <span className="muted">· list {time(m.listUpdatedAt)}</span></p>)}
        {r.asn && <p className="small">Network: {r.asn.asns.join(', ') || 'not announced'} · prefix {r.asn.prefix ?? '-'} <span className="muted">· {r.asn.source}, {time(r.asn.lookedUpAt)}</span></p>}
        <p className="small">AbuseIPDB: {r.abuse ? `${r.abuse.abuseConfidenceScore}/100 from ${r.abuse.totalReports} reports (${r.abuse.usageType ?? 'usage unknown'})` : r.abuseStatus}</p>
        {r.localObservationsIncluded && <p className="small">Local: {r.observations} observation(s){r.observations ? `, ports ${r.ports.join(', ')}, processes ${r.processes.join(', ') || '-'}, last ${ago(r.lastSeen)}` : ''}</p>}
        <p className="small muted">Looked up {time(r.lookedUpAt)}</p>
      </div>
      <div className="small">
        <b>Known</b><List items={r.known} />
        <b>Uncertain</b><List items={r.uncertain} />
        <b>Next steps</b><List items={r.nextSteps} />
      </div>
    </div>
  );
}
