import type {
  AlgorithmInfo, ApiErrorBody, BenchmarkReport, CityDataset, DatasetSummary, FailureLabResponse, FlowResponse,
  PriorityOperation, PriorityResponse, ResilienceResponse, RouteResponse, ScreenResponse, SelfCheckReport, ValidationIssue,
} from './types';

/** Base URL of the Java API. Empty = same origin (dev proxy). Set VITE_API_BASE_URL in production. */
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
    throw new ApiError(
      'Cannot reach the NEXUS API. If it is hosted on a free tier it may be waking up (about 30-60 s); try again.',
      0,
    );
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
  health: () => request<{ status: string; version: string; java: string }>('GET', '/api/health'),
  algorithms: () => request<AlgorithmInfo[]>('GET', '/api/algorithms'),
  demo: () => request<CityDataset>('GET', '/api/dataset/demo'),
  validate: (dataset: CityDataset) => request<DatasetSummary>('POST', '/api/dataset/validate', dataset),
  importCsv: (nodesCsv: string, roadsCsv: string) =>
    request<{ dataset: CityDataset; summary: DatasetSummary }>('POST', '/api/dataset/import-csv', { nodesCsv, roadsCsv }),
  flow: (body: {
    dataset: CityDataset; source?: string; sink?: string; algorithm?: string; failedRoadIds?: string[];
    failedNodeIds?: string[]; capacityOverrides?: Record<string, number>; includeTrace?: boolean;
  }) => request<FlowResponse>('POST', '/api/flow/optimize', body),
  failureLab: (body: {
    dataset: CityDataset; failedRoadIds: string[]; failedNodeIds: string[]; windowHours?: number;
    worstWindowHours?: number; batchScenarios?: number; roadsPerScenario?: number; seed?: number;
  }) => request<FailureLabResponse>('POST', '/api/failure-lab/simulate', body),
  resilience: (body: { dataset: CityDataset; failedRoadIds: string[]; failedNodeIds: string[]; scanCriticalRoads: boolean }) =>
    request<ResilienceResponse>('POST', '/api/resilience/components', body),
  priority: (dataset: CityDataset, operations: PriorityOperation[]) =>
    request<PriorityResponse>('POST', '/api/incidents/priority', { dataset, operations }),
  screen: (body: {
    dataset: CityDataset; expectedInsertions: number; falsePositiveRate: number; seed: number;
    generateHistorical: number; incomingIds: string[]; absentProbes: number;
  }) => request<ScreenResponse>('POST', '/api/incidents/screen', body),
  route: (body: { dataset: CityDataset; depotId: string; stopIds: string[]; failedRoadIds: string[]; failedNodeIds: string[] }) =>
    request<RouteResponse>('POST', '/api/routes/plan', body),
  lab: <T,>(kind: 'flow' | 'dsu' | 'rbtree' | 'quickselect' | 'tsp', body: unknown) => request<T>('POST', `/api/lab/${kind}`, body),
  timeline: <T,>(body: unknown) => request<T>('POST', '/api/timeline/query', body),
  benchmark: (algorithm: string) => request<BenchmarkReport>('POST', `/api/benchmarks/${algorithm}`),
  selfCheck: () => request<SelfCheckReport>('POST', '/api/selfcheck'),
};
