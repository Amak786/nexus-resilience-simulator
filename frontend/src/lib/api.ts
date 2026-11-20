import type {
  AlgorithmInfo, ApiErrorBody, Area, BenchmarkReport, ComponentsResponse, CriticalLinksResponse, EventListResponse,
  EventStatsResponse, NetworkResponse, ProvenanceResponse, RefreshResponse, RouteResponse, SelfCheckReport, TourResponse,
  ValidationIssue,
} from './types';

/** Base URL of the Java API. Empty = same origin (bundled deployment or dev proxy). */
export const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '');

export class ApiError extends Error {
  constructor(message: string, readonly status: number, readonly issues: ValidationIssue[] = []) {
    super(message);
  }
}

async function request<T>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(API_BASE + path, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError('Cannot reach the NEXUS API. On free hosting it may be waking up (30-60 s); please try again.', 0);
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

const q = (a: Area) => `south=${a.south}&west=${a.west}&north=${a.north}&east=${a.east}`;

export const api = {
  health: () => request<{ status: string; version: string }>('GET', '/api/health'),
  algorithms: () => request<AlgorithmInfo[]>('GET', '/api/algorithms'),
  provenance: () => request<ProvenanceResponse>('GET', '/api/mumbai/provenance'),
  network: (area?: Area) => request<NetworkResponse>('GET', '/api/mumbai/network' + (area ? '?' + q(area) : '')),
  route: (b: { fromLat: number; fromLon: number; toLat: number; toLon: number; metric: 'TIME' | 'DISTANCE' }) =>
    request<RouteResponse>('POST', '/api/mumbai/route', b),
  components: (area: Area, removedSegmentIds: number[]) =>
    request<ComponentsResponse>('POST', '/api/mumbai/components', { area, removedSegmentIds }),
  criticalLinks: (b: { area: Area; sourceLat: number; sourceLon: number; sinkLat: number; sinkLon: number; removedSegmentIds: number[] }) =>
    request<CriticalLinksResponse>('POST', '/api/mumbai/critical-links', b),
  events: (from: string, to: string, status: string) =>
    request<EventListResponse>('GET', `/api/mumbai/events?from=${from}&to=${to}&status=${status}&limit=300`),
  eventStats: (from: string, to: string) => request<EventStatsResponse>('GET', `/api/mumbai/events/stats?from=${from}&to=${to}`),
  refreshEvents: () => request<RefreshResponse>('POST', '/api/mumbai/events/refresh'),
  tour: (stops: { label: string; lat: number; lon: number }[]) =>
    request<TourResponse>('POST', '/api/mumbai/tour', { stops, startIndex: 0 }),
  lab: <T,>(kind: 'flow' | 'dsu' | 'rbtree' | 'fibheap' | 'quickselect' | 'segment-tree' | 'tsp', body: unknown) =>
    request<T>('POST', `/api/lab/${kind}`, body),
  benchmark: (algorithm: string) => request<BenchmarkReport>('POST', `/api/benchmarks/${algorithm}`),
  selfCheck: () => request<SelfCheckReport>('POST', '/api/selfcheck'),
};
