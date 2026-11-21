import type {
  AlgorithmInfo, ApiErrorBody, Area, BenchmarkReport, ComponentsResponse, CriticalLinksResponse, DemoModification,
  EventListResponse, EventStatsResponse, IntegrityResponse, LengthStatsResponse, NetworkResponse, ProvenanceResponse,
  RefreshResponse, RouteResponse, ScenarioResponse, SearchResponse, SegmentDetail, SelfCheckReport, TourResponse, ValidationIssue,
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
    throw new ApiError('Cannot reach the API. Free hosting may be waking up (30-60 s); please try again.', 0);
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

export type Metric = 'DISTANCE' | 'ESTIMATED_TIME';
export type CapacityModel = 'UNIT' | 'LANE_ASSUMPTION';
export interface LatLon { lat: number; lon: number }

export interface IntegrityOptions {
  area: Area; from: LatLon; to: LatLon; demoModifications: DemoModification[]; reviewedLegitimate: string[];
  topK: number; stripFrom?: number; stripTo?: number;
}

export const api = {
  health: () => request<{ status: string; version: string }>('GET', '/api/health'),
  algorithms: () => request<AlgorithmInfo[]>('GET', '/api/algorithms'),
  provenance: () => request<ProvenanceResponse>('GET', '/api/mumbai/provenance'),
  network: (area?: Area) => request<NetworkResponse>('GET', '/api/mumbai/network' + (area ? '?' + q(area) : '')),
  segment: (id: number) => request<SegmentDetail>('GET', `/api/mumbai/segments/${id}`),
  search: (text: string, places: boolean) =>
    request<SearchResponse>('GET', `/api/mumbai/search?q=${encodeURIComponent(text)}&places=${places}`),
  route: (from: LatLon, to: LatLon, metric: Metric, avoidSegmentIds: number[]) =>
    request<RouteResponse>('POST', '/api/mumbai/route', { fromLat: from.lat, fromLon: from.lon, toLat: to.lat, toLon: to.lon, metric, avoidSegmentIds }),
  components: (area: Area, removedSegmentIds: number[], from?: LatLon, to?: LatLon) =>
    request<ComponentsResponse>('POST', '/api/mumbai/components', {
      area, removedSegmentIds, fromLat: from?.lat, fromLon: from?.lon, toLat: to?.lat, toLon: to?.lon,
    }),
  criticalLinks: (area: Area, source: LatLon, sink: LatLon, removedSegmentIds: number[], capacityModel: CapacityModel) =>
    request<CriticalLinksResponse>('POST', '/api/mumbai/critical-links', {
      area, sourceLat: source.lat, sourceLon: source.lon, sinkLat: sink.lat, sinkLon: sink.lon, removedSegmentIds, capacityModel,
    }),
  lengthStats: (area: Area, percentiles: number[]) =>
    request<LengthStatsResponse>('GET', `/api/mumbai/stats/lengths?${q(area)}&${percentiles.map((p) => 'percentiles=' + p).join('&')}`),
  events: (from: string, to: string, status: string) =>
    request<EventListResponse>('GET', `/api/mumbai/events?from=${from}&to=${to}&status=${status}&limit=300`),
  eventStats: (from: string, to: string) => request<EventStatsResponse>('GET', `/api/mumbai/events/stats?from=${from}&to=${to}`),
  refreshEvents: () => request<RefreshResponse>('POST', '/api/mumbai/events/refresh'),
  tour: (stops: { label: string; lat: number; lon: number }[]) =>
    request<TourResponse>('POST', '/api/mumbai/tour', { stops, startIndex: 0 }),
  integrity: (o: IntegrityOptions) =>
    request<IntegrityResponse>('POST', '/api/integrity/analyse', {
      area: o.area, fromLat: o.from.lat, fromLon: o.from.lon, toLat: o.to.lat, toLon: o.to.lon,
      demoModifications: o.demoModifications, reviewedLegitimate: o.reviewedLegitimate, topK: o.topK,
      stripFrom: o.stripFrom, stripTo: o.stripTo, planInspection: true,
    }),
  scenario: (area: Area, from: LatLon, to: LatLon) =>
    request<ScenarioResponse>('GET', `/api/integrity/scenario?${q(area)}&fromLat=${from.lat}&fromLon=${from.lon}&toLat=${to.lat}&toLon=${to.lon}`),
  lab: <T,>(kind: 'flow' | 'dsu' | 'rbtree' | 'fibheap' | 'bloom' | 'quickselect' | 'segment-tree' | 'tsp', body: unknown) =>
    request<T>('POST', `/api/lab/${kind}`, body),
  benchmark: (algorithm: string) => request<BenchmarkReport>('POST', `/api/benchmarks/${algorithm}`),
  selfCheck: () => request<SelfCheckReport>('POST', '/api/selfcheck'),
};
