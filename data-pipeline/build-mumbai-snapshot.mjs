#!/usr/bin/env node
// NEXUS data pipeline: builds the Mumbai road-graph and map-notes snapshots from OpenStreetMap.
//
//   node data-pipeline/build-mumbai-snapshot.mjs            # fetch from the network and build
//   node data-pipeline/build-mumbai-snapshot.mjs --offline  # rebuild from data-pipeline/raw/*.json
//
// Sources (both ODbL, (c) OpenStreetMap contributors):
//   * Overpass API  https://overpass-api.de/api/interpreter  (road ways with geometry)
//   * OSM Notes API https://api.openstreetmap.org/api/0.6/notes.json (community map-issue reports)
// Usage policies: one query each, identifying User-Agent, results cached in data-pipeline/raw/.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const RAW = path.join(here, 'raw');
const OUT = path.join(here, '..', 'backend', 'src', 'main', 'resources', 'data', 'mumbai');
const UA = 'nexus-academic-project/1.0 (student algorithms coursework)';

// Study area: Greater Mumbai (island city + western and eastern suburbs). south, west, north, east.
export const BBOX = { south: 18.89, west: 72.77, north: 19.29, east: 72.99 };
const CLASSES = ['motorway', 'motorway_link', 'trunk', 'trunk_link', 'primary', 'primary_link',
  'secondary', 'secondary_link', 'tertiary', 'tertiary_link'];
export const OVERPASS_QUERY = `[out:json][timeout:180];
way["highway"~"^(${CLASSES.join('|')})$"](${BBOX.south},${BBOX.west},${BBOX.north},${BBOX.east});
out body geom;`;

// Modelled proxies (documented assumptions, NOT measurements). Free-flow speed in km/h used when the
// way has no maxspeed tag; lanes per direction used when it has no lanes tag; per-lane capacity in
// vehicles/hour is a simplified highway-capacity rule of thumb for urban roads.
export const CLASS_DEFAULTS = {
  motorway: { speed: 80, lanes: 3, perLane: 2000 }, motorway_link: { speed: 40, lanes: 1, perLane: 1500 },
  trunk: { speed: 60, lanes: 3, perLane: 1800 }, trunk_link: { speed: 40, lanes: 1, perLane: 1400 },
  primary: { speed: 50, lanes: 2, perLane: 1600 }, primary_link: { speed: 35, lanes: 1, perLane: 1200 },
  secondary: { speed: 40, lanes: 2, perLane: 1200 }, secondary_link: { speed: 30, lanes: 1, perLane: 1000 },
  tertiary: { speed: 30, lanes: 1, perLane: 900 }, tertiary_link: { speed: 25, lanes: 1, perLane: 800 },
};

const offline = process.argv.includes('--offline');
fs.mkdirSync(RAW, { recursive: true });
fs.mkdirSync(OUT, { recursive: true });

async function cached(file, fetcher) {
  const p = path.join(RAW, file);
  if (offline || fs.existsSync(p)) {
    if (!fs.existsSync(p)) throw new Error(`--offline but ${p} is missing`);
    return JSON.parse(fs.readFileSync(p, 'utf8'));
  }
  const data = await fetcher();
  fs.writeFileSync(p, JSON.stringify(data));
  return data;
}

const retrievedAt = new Date().toISOString();
const overpass = await cached('overpass-roads.json', async () => {
  const r = await fetch('https://overpass-api.de/api/interpreter', {
    method: 'POST', headers: { 'User-Agent': UA, 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'data=' + encodeURIComponent(OVERPASS_QUERY),
  });
  if (!r.ok) throw new Error('Overpass HTTP ' + r.status);
  const j = await r.json();
  j.__retrievedAt = retrievedAt;
  return j;
});
const notesRaw = await cached('osm-notes.json', async () => {
  const url = `https://api.openstreetmap.org/api/0.6/notes.json?bbox=${BBOX.west},${BBOX.south},${BBOX.east},${BBOX.north}&limit=10000&closed=-1`;
  const r = await fetch(url, { headers: { 'User-Agent': UA } });
  if (!r.ok) throw new Error('Notes HTTP ' + r.status);
  const j = await r.json();
  j.__retrievedAt = retrievedAt;
  return j;
});

// ------------------------------------------------------------------ road graph
const R = 6371008.8;
const rad = (d) => (d * Math.PI) / 180;
function haversine(a, b) {
  const dLat = rad(b[0] - a[0]);
  const dLon = rad(b[1] - a[1]);
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a[0])) * Math.cos(rad(b[0])) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
function simplify(points, eps) { // Douglas-Peucker in degrees (eps ~ 0.00004 deg ~ 4-5 m)
  if (points.length <= 2) return points;
  let maxD = 0;
  let idx = 0;
  const [a, b] = [points[0], points[points.length - 1]];
  for (let i = 1; i < points.length - 1; i++) {
    const p = points[i];
    const dx = b[1] - a[1];
    const dy = b[0] - a[0];
    const len = Math.hypot(dx, dy) || 1e-12;
    const d = Math.abs(dy * p[1] - dx * p[0] + b[1] * a[0] - b[0] * a[1]) / len;
    if (d > maxD) { maxD = d; idx = i; }
  }
  if (maxD <= eps) return [a, b];
  return [...simplify(points.slice(0, idx + 1), eps).slice(0, -1), ...simplify(points.slice(idx), eps)];
}
const parseSpeed = (v) => {
  if (!v) return -1;
  const m = String(v).match(/^(\d+(?:\.\d+)?)\s*(mph)?/);
  if (!m) return -1;
  return Math.round(Number(m[1]) * (m[2] ? 1.609 : 1));
};
const parseInt0 = (v) => (v && /^\d+$/.test(String(v).trim()) ? Number(v) : -1);

const ways = overpass.elements.filter((e) => e.type === 'way' && e.geometry && e.nodes && e.nodes.length >= 2);
const use = new Map();
for (const w of ways) {
  w.nodes.forEach((n, i) => {
    const endpoint = i === 0 || i === w.nodes.length - 1;
    use.set(n, (use.get(n) ?? 0) + (endpoint ? 2 : 1));
  });
}
const isGraphNode = (n) => (use.get(n) ?? 0) >= 2; // shared by two ways, or a way endpoint

const nodeIndex = new Map();
const nodes = { osmId: [], lat: [], lon: [] };
const addNode = (osmId, ll) => {
  if (!nodeIndex.has(osmId)) {
    nodeIndex.set(osmId, nodes.osmId.length);
    nodes.osmId.push(osmId);
    nodes.lat.push(Number(ll[0].toFixed(6)));
    nodes.lon.push(Number(ll[1].toFixed(6)));
  }
  return nodeIndex.get(osmId);
};
const names = [''];
const nameIdx = new Map([['', 0]]);
const edges = { u: [], v: [], way: [], lengthM: [], oneway: [], cls: [], lanes: [], maxspeed: [], name: [], geom: [] };

for (const w of ways) {
  const t = w.tags ?? {};
  const cls = CLASSES.indexOf(t.highway);
  let pts = w.geometry.map((g) => [g.lat, g.lon]);
  let ids = [...w.nodes];
  let oneway = 0;
  const ow = (t.oneway ?? '').toLowerCase();
  if (ow === 'yes' || ow === 'true' || ow === '1' || t.junction === 'roundabout' || (t.highway === 'motorway' && ow !== 'no')) oneway = 1;
  if (ow === '-1' || ow === 'reverse') { oneway = 1; pts = pts.reverse(); ids = ids.reverse(); }
  const name = t.name ?? t.ref ?? '';
  if (!nameIdx.has(name)) { nameIdx.set(name, names.length); names.push(name); }
  let start = 0;
  for (let i = 1; i < ids.length; i++) {
    if (!isGraphNode(ids[i]) && i !== ids.length - 1) continue;
    const seg = pts.slice(start, i + 1);
    let len = 0;
    for (let k = 1; k < seg.length; k++) len += haversine(seg[k - 1], seg[k]);
    if (len >= 0.5) { // skip zero-length duplicates
      edges.u.push(addNode(ids[start], seg[0]));
      edges.v.push(addNode(ids[i], seg[seg.length - 1]));
      edges.way.push(w.id);
      edges.lengthM.push(Math.round(len * 10) / 10);
      edges.oneway.push(oneway);
      edges.cls.push(cls);
      edges.lanes.push(parseInt0(t.lanes));
      edges.maxspeed.push(parseSpeed(t.maxspeed));
      edges.name.push(nameIdx.get(name));
      edges.geom.push(simplify(seg, 0.00004).flatMap((p) => [Number(p[0].toFixed(5)), Number(p[1].toFixed(5))]));
    }
    start = i;
  }
}

const roads = {
  provenance: {
    dataset: 'Mumbai arterial road network',
    category: 'STATIC_GEOGRAPHIC',
    source: 'OpenStreetMap via Overpass API',
    sourceUrl: 'https://overpass-api.de/api/interpreter',
    licence: 'ODbL 1.0 - (c) OpenStreetMap contributors - https://www.openstreetmap.org/copyright',
    query: OVERPASS_QUERY,
    osmDataTimestamp: overpass.osm3s?.timestamp_osm_base ?? null,
    retrievedAt: overpass.__retrievedAt ?? null,
    coverage: { ...BBOX, description: 'Greater Mumbai bounding box; road classes motorway to tertiary (with links)' },
    notes: 'Nodes are intersections and way endpoints; edges are road segments between them. Residential and service roads are excluded to keep the graph explainable.',
  },
  assumptions: {
    travelTime: 'DERIVED_ESTIMATE: length / speed, where speed is the OSM maxspeed tag when present, otherwise the class default free-flow speed. Not live traffic.',
    capacity: 'MODELLED_PROXY: lanes per direction (OSM lanes tag, halved for two-way roads, else class default) x per-lane capacity by class (vehicles/hour). Not measured throughput.',
    classDefaults: CLASS_DEFAULTS,
  },
  classes: CLASSES,
  names,
  nodes,
  edges,
};
fs.writeFileSync(path.join(OUT, 'roads.json'), JSON.stringify(roads));

// ------------------------------------------------------------------ OSM notes (map-issue reports)
const clean = (s) => (s ?? '').replace(/\s+/g, ' ').trim().slice(0, 240);
const notes = notesRaw.features.map((f) => {
  const p = f.properties;
  const closed = [...(p.comments ?? [])].reverse().find((c) => c.action === 'closed');
  return {
    id: p.id,
    lat: Number(f.geometry.coordinates[1].toFixed(6)),
    lon: Number(f.geometry.coordinates[0].toFixed(6)),
    status: p.status,
    created: p.date_created.replace(' UTC', 'Z').replace(' ', 'T'),
    closed: p.status === 'closed' && closed ? closed.date.replace(' UTC', 'Z').replace(' ', 'T') : null,
    comments: (p.comments ?? []).length,
    text: clean(p.comments?.[0]?.text),
  };
});
fs.writeFileSync(path.join(OUT, 'notes.json'), JSON.stringify({
  provenance: {
    dataset: 'OpenStreetMap map notes in the study area',
    category: 'LATEST_AVAILABLE',
    source: 'OpenStreetMap Notes API',
    sourceUrl: 'https://api.openstreetmap.org/api/0.6/notes.json',
    licence: 'ODbL 1.0 - (c) OpenStreetMap contributors',
    retrievedAt: notesRaw.__retrievedAt ?? null,
    coverage: { ...BBOX },
    notes: 'Community-reported map issues (missing roads, wrong names, closures noticed by mappers). They are NOT traffic incidents. Usernames and user ids are removed; only the first comment text is kept (trimmed to 240 characters).',
  },
  records: notes,
}));

console.log(JSON.stringify({ ways: ways.length, nodes: nodes.osmId.length, edges: edges.u.length, names: names.length, notes: notes.length,
  roadsBytes: fs.statSync(path.join(OUT, 'roads.json')).size, notesBytes: fs.statSync(path.join(OUT, 'notes.json')).size }));
