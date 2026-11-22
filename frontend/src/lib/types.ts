// Mirrors of the Java DTOs (edu.trace.*). The front end displays results; it computes nothing itself.

export interface ValidationIssue { field: string; message: string }
export interface ApiErrorBody { error: string; message: string; issues: ValidationIssue[] }

export type DataClass = 'REAL_MAPPED' | 'VERIFIED_OBSERVATION' | 'DERIVED_RESULT' | 'ESTIMATE' | 'SAMPLE' | 'NOT_AVAILABLE';
export interface DataItem { item: string; dataClass: DataClass }
export interface Explanation { dataUsed: DataItem[]; calculated: string; canConclude: string[]; cannotConclude: string[] }

// ---------------------------------------------------------------- sources
export interface FeedStatus {
  id: string; name: string; owner: string; endpoint: string; licence: string; updateFrequency: string | null; kind: string;
  status: string; lastAttemptAt: string | null; lastSuccessAt: string | null; feedTimestamp: string | null; ageMinutes: number | null;
  error: string | null; records: number; notes: string;
}
export interface SensorStatus {
  sensorId: string; source: string | null; agentVersion: string | null; status: 'LIVE' | 'DELAYED' | 'DISCONNECTED';
  secondsSinceLastBatch: number; lastBatchAt: string; lastObservedAt: string | null; batches: number; rows: number;
  rejectedRows: number; duplicateBatches: number;
}
export interface SourcesResponse {
  serverTime: string; intel: FeedStatus[]; viewerConfigured: boolean; ingestConfigured: boolean; viewer: boolean;
  sensors: SensorStatus[]; mode: 'LIVE' | 'HISTORICAL' | 'NO_SENSOR'; retentionHours: number; storage: string;
  bloom: { bits: number; hashes: number; inserted: number; theoreticalFpr: number; screened: number; possiblyPresent: number; confirmed: number; falsePositives: number };
}

// ---------------------------------------------------------------- findings and investigations
export interface Evidence { type: 'OBSERVATION' | 'INTEL_MATCH' | 'HEURISTIC'; text: string; source: string; at: string | null }
export type Status = 'OPEN' | 'ACKNOWLEDGED' | 'DISMISSED' | 'CONFIRMED_INCIDENT';
export interface Finding {
  id: string; rule: string; category: 'INTEL_MATCH' | 'HEURISTIC'; severity: 'HIGH' | 'MEDIUM' | 'LOW'; confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  score: number; scoreBreakdown: string[]; subject: string; title: string; firstSeen: string | null; lastSeen: string | null;
  observations: number; sensors: string[]; processes: string[]; ports: number[]; evidence: Evidence[]; why: string; known: string[];
  uncertain: string[]; nextSteps: string[]; status: Status; analystNote: string | null;
}
export interface Ranking { k: number; candidates: number; kthScore: number; topIds: string[]; quickselectComparisons: number; matchesSort: boolean; indexSize: number; indexHeight: number; indexValid: boolean }
export interface FindingsResponse { findings: Finding[]; ranking: Ranking; scoringRule: string[]; commonPorts: number[] }
export interface Observation {
  sensorId: string; protocol: string; localAddress: string; localPort: number; remoteAddress: string; remotePort: number; state: string | null;
  process: string | null; firstSeen: string; lastSeen: string; lastIngested: string | null; observations: number;
}
export interface IntelMatch { list: string; detail: string; listUpdatedAt: string | null; sourceUrl: string }
export interface Investigation {
  query: string; ip: string; scope: string; localObservationsIncluded: boolean; observations: number; firstSeen: string | null; lastSeen: string | null;
  ports: number[]; processes: string[]; sensors: string[]; intel: IntelMatch[];
  asn: { ip: string; prefix: string | null; asns: string[]; lookedUpAt: string; source: string } | null;
  abuse: { abuseConfidenceScore: number; totalReports: number; distinctUsers: number; lastReportedAt: string | null; usageType: string | null; isp: string | null; lookedUpAt: string } | null;
  abuseStatus: string; findings: Finding[]; assessment: string; known: string[]; uncertain: string[]; nextSteps: string[]; lookedUpAt: string;
}
export interface ChangeSummary { windowMinutes: number; remotesNow: number; remotesBefore: number; newRemotes: number; goneRemotes: number; componentsNow: number; componentsBefore: number; newRemoteSample: string[]; note: string }
export interface TimeSeries { minutes: number; perMinute: number[]; queryFrom: number; queryTo: number; querySum: number; queryMax: number; queryMaxMinute: number; matchesScan: boolean; note: string }
export interface OverviewResponse {
  computedAt: string; flows: number; remoteAddresses: number; findingsOpen: number; findingsHigh: number; ranking: Ranking; topFindings: Finding[];
  changes: ChangeSummary; recent: Observation[]; timeSeries: TimeSeries;
}

// ---------------------------------------------------------------- graph
export interface GNode {
  id: string; kind: 'SENSOR' | 'PROCESS' | 'REMOTE_IP' | 'ASN'; label: string; observations: number; degree: number; betweenness: number;
  articulation: boolean; component: number; firstSeen: string | null; lastSeen: string | null; scope: string | null; intelMatch: boolean; maxSeverity: string | null;
}
export interface GEdge { a: string; b: string; kind: string; observations: number; ports: number[] }
export interface GraphView {
  computedAt: string; nodes: number; edges: number; components: number; largestComponent: number; articulationPoints: number; bridges: number;
  nodeList: GNode[]; edgeList: GEdge[]; mostCentral: string[]; runs: { algorithm: string; input: string; output: string; ms: number }[]; truncated: boolean; note: string;
}
export interface PathResult {
  from: string; to: string; connected: boolean; bfsPath: string[]; bfsHops: number; reachableFromSource: number; dfsVisited: number;
  dijkstraPath: string[]; dijkstraCost: number; fibonacciMs: number; priorityQueueMs: number; queuesAgree: boolean;
  disjointPathsEdmondsKarp: number; disjointPathsFordFulkerson: number; maxFlowEqualsMinCut: boolean; minCutEdges: string[]; notes: string[];
}

// ---------------------------------------------------------------- AI analyst
export interface EvidenceItem { tool: string; input: unknown; result: { result?: string; source?: string; computedAt?: string; error?: string; data?: unknown } }
export interface AnalystResponse {
  aiAvailable: boolean; model: string; answer: string; answerKind: 'AI_INTERPRETATION' | 'EVIDENCE_ONLY' | 'REFUSAL' | 'INCOMPLETE';
  evidence: EvidenceItem[]; stopReason: string | null; generatedAt: string; notice: string | null;
}

// ---------------------------------------------------------------- Algorithm Lab
export interface AStarComparison {
  from: { lat: number; lon: number; snapDistanceM: number }; to: { lat: number; lon: number; snapDistanceM: number }; reachable: boolean;
  distanceM: number; aStarSettled: number; dijkstraSettled: number; aStarMs: number; dijkstraMs: number; sameDistance: boolean; note: string;
}
export interface NodeView { id: number; key: string; color: 'RED' | 'BLACK'; left: number; right: number; subtreeSize: number }
export interface RunStats { algorithm: string; maxFlow: number; augmentations: number; arcScans: number; elapsedMs: number }
export interface TraceStep { step: number; nodes: string[]; arcs: { edge: number; reverse: boolean }[]; bottleneck: number; flowAfter: number; edgeFlows: number[] | null }
export interface MatrixRouteResponse {
  tour: string[]; legs: { from: string; to: string; km: number }[]; routeKm: number; nearestNeighbourTour: string[];
  nearestNeighbourKm: number; mstLowerBoundKm: number; certifiedRatioBound: number; exactKm: number | null; actualRatio: number | null;
  mstEdges: string[][]; unreachableStops: string[]; matrixLabels: string[]; matrix: number[][]; guarantee: string; assumptions: string[];
}
export interface BloomLabResponse {
  bits: number; hashes: number; inserted: number; theoreticalFpr: number; probes: number; probeFalsePositives: number; measuredFpr: number;
  results: { id: string; bloom: string; exactPresent: boolean; verdict: string }[]; note: string;
}
export interface AlgorithmInfo { id: string; name: string; syllabusUnit: string; javaClass: string; time: string; space: string; usedIn: string; limitations: string; whyChosen: string; vivaDemo: string }
export interface BenchmarkRow { size: string; primary: string; primaryMedianMs: number; primaryMinMs: number; primaryOps: number; baseline: string; baselineMedianMs: number | null; baselineOps: number | null; quality: string }
export interface BenchmarkReport { algorithm: string; title: string; opsMeaning: string; warmups: number; repetitions: number; rows: BenchmarkRow[]; environment: string; methodology: string[] }
export interface SelfCheckReport { allPassed: boolean; seed: number; checks: { algorithm: string; property: string; cases: number; passed: boolean; detail: string; ms: number }[] }
export interface Lookup { query: string; kind: 'IP' | 'DOMAIN'; domain: string | null; resolvedAt: string | null; dnsError: string | null; results: Investigation[]; note: string }
