// Mirrors of the Java DTOs (edu.nexus.mumbai.*, edu.nexus.dto.*). The front end only displays these results.

export interface ValidationIssue { field: string; message: string }
export interface ApiErrorBody { error: string; message: string; issues: ValidationIssue[] }

export type Category = 'VERIFIED_LIVE' | 'LATEST_AVAILABLE' | 'STATIC_GEOGRAPHIC' | 'DERIVED_ESTIMATE' | 'MODELLED_PROXY'
  | 'DEMONSTRATION' | 'NOT_AVAILABLE';

export interface Area { south: number; west: number; north: number; east: number }

export interface DataSource {
  id: string; name: string; category: Category; source: string | null; sourceUrl: string | null; licence: string | null;
  retrievedAt: string | null; dataTimestamp: string | null; ageDays: number | null; freshness: string; coverage: string; notes: string;
}
export interface ProvenanceResponse {
  studyArea: Area; sources: DataSource[];
  graph: { nodes: number; segments: number; arcs: number; oneWaySegments: number; totalKm: number; byClass: { roadClass: string; segments: number; km: number }[] };
  assumptions: { travelTime: string; capacity: string; classDefaults: Record<string, { speed: number; lanes: number; perLane: number }> };
}

export interface Segment {
  id: number; wayId: number; name: string; roadClass: string; from: number; to: number; oneWay: boolean;
  lengthM: number; travelTimeS: number; capacityVph: number; lanesTag: number | null; maxspeedTag: number | null; geometry: number[];
}
export interface NetworkResponse { area: Area; nodes: number; segments: number; truncated: boolean; items: Segment[] }

export interface Snap { node: number; osmNodeId: number; lat: number; lon: number; snapDistanceM: number }
export interface QueueStats { queue: string; settled: number; relaxations: number; heapOperations: number; elapsedMs: number }
export interface RouteResponse {
  from: Snap; to: Snap; metric: string; reachable: boolean; distanceM: number; travelTimeS: number; segmentIds: number[];
  geometry: number[][]; steps: { name: string; roadClass: string; lengthM: number }[]; fibonacci: QueueStats; binary: QueueStats;
  queuesAgree: boolean; notes: string[];
}

export interface ComponentInfo { id: number; nodes: number; segments: number; centerLat: number; centerLon: number }
export interface ComponentSummary {
  componentCount: number; largestNodes: number; largestShare: number; isolatedNodes: number; successfulUnions: number;
  redundantUnions: number; components: ComponentInfo[];
}
export interface ComponentsResponse {
  area: Area; nodes: number; segments: number; removed: number; before: ComponentSummary; after: ComponentSummary;
  segmentComponent: [number, number][]; notes: string[];
}

export interface CutSegment { segmentId: number; name: string; roadClass: string; fromNode: number; toNode: number; capacityVph: number; geometry: number[] }
export interface FlowStats { algorithm: string; maxFlow: number; augmentations: number; arcScans: number; elapsedMs: number }
export interface CriticalLinksResponse {
  area: Area; source: Snap; sink: Snap; nodes: number; arcs: number; maxFlowVph: number; minCutCapacityVph: number;
  maxFlowEqualsMinCut: boolean; flowVerified: boolean; cut: CutSegment[]; segmentFlows: [number, number][];
  edmondsKarp: FlowStats; fordFulkerson: FlowStats; algorithmsAgree: boolean;
  paths: { step: number; bottleneck: number; flowAfter: number; segmentIds: number[] }[]; assumptions: string[]; warnings: string[];
}

export interface NoteRecord { id: number; lat: number; lon: number; status: string; created: string; closed: string | null; comments: number; text: string; url: string }
export interface EventListResponse {
  from: string; to: string; status: string; countInRange: number; returned: number; items: NoteRecord[];
  index: { size: number; height: number; blackHeight: number; invariantsHold: boolean }; key: string;
}
export interface EventStatsResponse {
  from: string; to: string; days: number; totalNotes: number; busiestDay: { date: string; count: number };
  quietestDay: { date: string; count: number }; matchesScan: boolean; segmentTreeNodesVisited: number; scanCellsTouched: number;
  monthly: { month: string; count: number }[]; resolvedSample: number; sampleAdequate: boolean;
  resolutionPercentiles: { percentile: number; valueDays: number; comparisons: number; matchesSortOracle: boolean }[]; notes: string[];
}
export interface RefreshResponse {
  status: string; fetchedAt: string; fetched: number; possiblyPresent: number; confirmedDuplicates: number; bloomFalsePositives: number;
  newRecords: number; statusUpdates: number; bloomBits: number; bloomHashes: number; bloomTheoreticalFpr: number; message: string;
}

export interface TourResponse {
  tour: string[]; snapped: Snap[]; excludedStops: string[];
  legs: { from: string; to: string; plannedKm: number; executableKm: number; segmentIds: number[] }[];
  plannedKm: number; executableKm: number; nearestNeighbourKm: number; nearestNeighbourTour: string[]; mstLowerBoundKm: number;
  exactKm: number | null; certifiedRatioBound: number; plannedMatrixKm: number[][]; geometry: number[][]; guarantee: string; assumptions: string[];
}

export interface NodeView { id: number; key: string; color: 'RED' | 'BLACK'; left: number; right: number; subtreeSize: number }
export interface RunStats { algorithm: string; maxFlow: number; augmentations: number; arcScans: number; elapsedMs: number }
export interface TraceStep { step: number; nodes: string[]; arcs: { edge: number; reverse: boolean }[]; bottleneck: number; flowAfter: number; edgeFlows: number[] | null }
export interface MatrixRouteResponse {
  tour: string[]; legs: { from: string; to: string; km: number }[]; routeKm: number; nearestNeighbourTour: string[];
  nearestNeighbourKm: number; mstLowerBoundKm: number; certifiedRatioBound: number; exactKm: number | null; actualRatio: number | null;
  mstEdges: string[][]; unreachableStops: string[]; matrixLabels: string[]; matrix: number[][]; guarantee: string; assumptions: string[];
}
export interface AlgorithmInfo { id: string; name: string; syllabusUnit: string; javaClass: string; time: string; space: string; usedIn: string; limitations: string }
export interface BenchmarkRow { size: string; primary: string; primaryMedianMs: number; primaryMinMs: number; primaryOps: number; baseline: string; baselineMedianMs: number | null; baselineOps: number | null; quality: string }
export interface BenchmarkReport { algorithm: string; title: string; opsMeaning: string; warmups: number; repetitions: number; rows: BenchmarkRow[]; environment: string; methodology: string[] }
export interface SelfCheckReport { allPassed: boolean; seed: number; checks: { algorithm: string; property: string; cases: number; passed: boolean; detail: string; ms: number }[] }
