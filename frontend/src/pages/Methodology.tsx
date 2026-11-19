import { useEffect, useState } from 'react';
import { api, API_BASE } from '../lib/api';
import type { AlgorithmInfo } from '../lib/types';
import { Card, List, PageHeader } from '../components/ui';

export function Methodology() {
  const [catalog, setCatalog] = useState<AlgorithmInfo[]>([]);
  useEffect(() => { api.algorithms().then(setCatalog).catch(() => setCatalog([])); }, []);
  return (
    <>
      <PageHeader kicker="Methodology" title="Models, assumptions and limitations">
        NEXUS keeps three different graphs apart, because confusing them is the most common modelling mistake.
      </PageHeader>
      <div className="grid-3">
        <Card title="1 · Directed capacity network">
          <p>Used by Edmonds-Karp / Ford-Fulkerson and the min cut.</p>
          <List items={[
            'Road capacity = relief kits per 12-hour window, per direction.',
            'Two-way road → two arcs; one-way road → one arc.',
            'SUPER_SOURCE → depot (capacity = stock); zone → SUPER_SINK (capacity = demand).',
            'Failed roads are removed; a failed facility loses all its arcs.',
          ]} />
        </Card>
        <Card title="2 · Undirected connectivity graph">
          <p>Used by the disjoint-set union.</p>
          <List items={[
            'One undirected edge per operating road, regardless of direction or capacity.',
            'Answers "is there any physical link?", not "how much can flow?".',
            'DSU cannot delete edges, so failures rebuild it from the modified graph.',
          ]} />
        </Card>
        <Card title="3 · Metric distance matrix">
          <p>Used by the TSP 2-approximation.</p>
          <List items={[
            'Built from road lengthKm via shortest paths: never from capacity.',
            'Validated: symmetric, non-negative, zero diagonal, triangle inequality.',
            'Unreachable stops are listed and excluded explicitly.',
          ]} />
        </Card>
      </div>
      <Card title="What max flow does and does not solve">
        <List items={[
          'It gives the largest total number of kits that can reach demand zones in one window, plus a certificate (equal min cut).',
          'It is single-commodity and single-period: no travel times, vehicle schedules, perishability or multi-day inventory.',
          'It maximises the total, not fairness: two equally good flows can serve zones differently.',
          'So it is decision support for capacity planning, not a complete multi-depot inventory allocation model.',
        ]} />
      </Card>
      <Card title="Other modelling rules">
        <List items={[
          'Incident score = 200 × severity + min(300, people / 50) + vulnerable % of the linked zone; ties broken by id. A transparent triage rule, not a validated risk model.',
          'Hourly capacity in the Failure Lab = max flow / 12 hours (deliveries spread evenly). Shortfall = max(0, demand − capacity).',
          'Random failure scenarios fail roads uniformly at random using a fixed seed, so every demo is reproducible.',
          'Percentiles use the nearest-rank definition; every Quickselect answer is cross-checked against a full sort.',
          'Bloom filter: insertion-only, no deletes, fixed seed, so no false negatives; every possible hit is verified exactly.',
          'All bundled data is synthetic; Varunapuri is fictional. Nothing here is an official instruction.',
        ]} />
      </Card>
      <Card title="Algorithms, complexity and limitations (served by the API)">
        <div className="table-scroll"><table className="table small"><thead><tr><th>Algorithm</th><th>Syllabus</th><th>Time</th><th>Space</th><th>Used in</th><th>Limitations</th></tr></thead>
          <tbody>{catalog.map((a) => <tr key={a.id}><td><b>{a.name}</b><div className="muted">{a.javaClass}</div></td><td>{a.syllabusUnit}</td><td>{a.time}</td><td>{a.space}</td><td>{a.usedIn}</td><td>{a.limitations}</td></tr>)}</tbody></table></div>
        <p className="small">Ford-Fulkerson, Edmonds-Karp and min cut are one technique family (network flow), not three separate algorithms.</p>
        <p className="small">API documentation: <a href={`${API_BASE}/swagger-ui.html`} target="_blank" rel="noreferrer">Swagger UI</a></p>
      </Card>
    </>
  );
}

export function Help() {
  return (
    <>
      <PageHeader kicker="Help" title="How to use NEXUS">A short guide for a demo or viva.</PageHeader>
      <div className="grid-2">
        <Card title="Suggested 8-minute walkthrough">
          <ol>
            <li><b>Overview</b>: supply, demand, certified max flow and the bottleneck roads.</li>
            <li><b>Failure Lab</b>: choose "Island causeway R22 washed out" and run it. Show the throughput drop, the isolated zone (DSU), the worst hours (segment tree), the P90 impact (Quickselect) and the ranked recovery actions.</li>
            <li><b>Flow Optimizer</b>: replay the augmenting paths step by step; compare with Ford-Fulkerson.</li>
            <li><b>Incident queue</b>: insert an incident and watch the red-black tree rotate.</li>
            <li><b>Duplicate screening</b>: a confirmed duplicate versus a resolved false positive.</li>
            <li><b>Route planner</b>: tour length versus the MST lower bound.</li>
            <li><b>Benchmarks &amp; tests</b>: run the live self-check.</li>
          </ol>
        </Card>
        <Card title="Good to know">
          <List items={[
            'Data lives in your browser; "Reset to synthetic demo" on Network & Data restores it.',
            'Every calculation runs in the Java API; the website only draws the results.',
            'On a free hosting tier the API sleeps when idle: the first request can take up to a minute.',
            'Invalid input is never silently fixed: the API names the field and the problem.',
            'Keyboard: every control is reachable with Tab; map roads and facilities toggle with Enter.',
          ]} />
        </Card>
      </div>
    </>
  );
}
