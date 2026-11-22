import type {
  AlgorithmInfo, AnalystResponse, ApiErrorBody, AStarComparison, BenchmarkReport, FindingsResponse, GraphView, Investigation, Lookup,
  Observation, OverviewResponse, PathResult, SelfCheckReport, SourcesResponse, Status, TimeSeries, ValidationIssue,
} from './types';

/** Base URL of the Java API. Empty = same origin (bundled deployment or dev proxy). */
export const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '');

export class ApiError extends Error {
  constructor(message: string, readonly status: number, readonly issues: ValidationIssue[] = []) {
    super(message);
  }
}

const TOKEN_KEY = 'trace.viewerToken';

/** Viewer token kept in sessionStorage only (cleared when the tab closes); never in a URL or localStorage. */
export const auth = {
  get(): string {
    try {
      return sessionStorage.getItem(TOKEN_KEY) ?? '';
    } catch {
      return '';
    }
  },
  set(t: string) {
    try {
      if (t) sessionStorage.setItem(TOKEN_KEY, t);
      else sessionStorage.removeItem(TOKEN_KEY);
    } catch {
      /* storage blocked: token lives for this page only */
    }
    window.dispatchEvent(new Event('trace-auth'));
  },
};

async function request<T>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const tok = auth.get();
  if (tok) headers.Authorization = `Bearer ${tok}`;
  let res: Response;
  try {
    res = await fetch(API_BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError('Cannot reach the TRACE server. Free hosting may be waking up (30-60 s); try again.', 0);
  }
  const text = await res.text();
  if (!res.ok) {
    let parsed: ApiErrorBody | null = null;
    try {
      parsed = JSON.parse(text) as ApiErrorBody;
    } catch {
      /* not JSON */
    }
    throw new ApiError(parsed?.message ?? `Request failed with HTTP ${res.status}`, res.status, parsed?.issues ?? []);
  }
  return JSON.parse(text) as T;
}

export const api = {
  health: () => request<{ status: string; version: string }>('GET', '/api/health'),
  sources: () => request<SourcesResponse>('GET', '/api/sources'),
  overview: () => request<OverviewResponse>('GET', '/api/overview'),
  graph: (maxNodes = 250) => request<GraphView>('GET', `/api/network/graph?maxNodes=${maxNodes}`),
  path: (from: string, to: string) => request<PathResult>('GET', `/api/network/path?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
  series: (minutes: number, from?: number, to?: number) =>
    request<TimeSeries>('GET', `/api/network/timeseries?minutes=${minutes}${from !== undefined ? `&from=${from}&to=${to}` : ''}`),
  findings: (k = 10) => request<FindingsResponse>('GET', `/api/findings?k=${k}`),
  decide: (id: string, status: Status, note?: string) => request<unknown>('POST', `/api/findings/${encodeURIComponent(id)}/decision`, { status, note }),
  observations: (ip?: string) => request<Observation[]>('GET', `/api/observations?limit=300${ip ? `&ip=${encodeURIComponent(ip)}` : ''}`),
  investigate: (ip: string) => request<Investigation>('GET', `/api/investigate?ip=${encodeURIComponent(ip)}`),
  lookup: (q: string) => request<Lookup>('GET', `/api/lookup?q=${encodeURIComponent(q)}`),
  ticket: () => request<{ ticket: string }>('POST', '/api/stream/ticket'),
  analystInfo: () => request<{ aiConfigured: boolean; model: string; tools: string[] }>('GET', '/api/analyst/info'),
  ask: (question: string) => request<AnalystResponse>('POST', '/api/analyst/ask', { question }),
  algorithms: () => request<AlgorithmInfo[]>('GET', '/api/algorithms'),
  astar: (fromLat: number, fromLon: number, toLat: number, toLon: number) =>
    request<AStarComparison>('POST', '/api/mumbai/astar', { fromLat, fromLon, toLat, toLon }),
  lab: <T,>(kind: 'flow' | 'dsu' | 'rbtree' | 'fibheap' | 'bloom' | 'quickselect' | 'segment-tree' | 'tsp', body: unknown) =>
    request<T>('POST', `/api/lab/${kind}`, body),
  benchmark: (algorithm: string) => request<BenchmarkReport>('POST', `/api/benchmarks/${algorithm}`),
  selfCheck: () => request<SelfCheckReport>('POST', '/api/selfcheck'),
};

/** Server-sent events: get a 60-second one-time ticket with the token, then open the stream (token never in the URL). */
export async function openStream(onEvent: (data: unknown) => void, onState: (s: 'connected' | 'disconnected') => void): Promise<() => void> {
  const { ticket } = await api.ticket();
  const es = new EventSource(`${API_BASE}/api/stream?ticket=${ticket}`);
  es.addEventListener('hello', () => onState('connected'));
  es.addEventListener('ingest', (e) => onEvent(JSON.parse((e as MessageEvent).data)));
  es.onerror = () => { onState('disconnected'); es.close(); };
  return () => es.close();
}
