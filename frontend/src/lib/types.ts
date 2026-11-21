// Mirrors of the Java DTOs (edu.nexus.mumbai.*, edu.nexus.integrity.*, edu.nexus.dto.*). The front end only displays results.

export interface ValidationIssue { field: string; message: string }
export interface ApiErrorBody { error: string; message: string; issues: ValidationIssue[] }

/** A real mapped, B verified observation, C derived result, D estimate/assumption, E sample/test. */
export type DataClass = 'REAL_MAPPED' | 'VERIFIED_OBSERVATION' | 'DERIVED_RESULT' | 'ESTIMATE' | 'SAMPLE' | 'NOT_AVAILABLE';
export interface DataItem { item: string; dataClass: DataClass }
export interface Explanation { dataUsed: DataItem[]; calculated: string; canConclude: string[]; cannotConclude: string[] }

export interface Area { south: number; west: number; north: number; east: number }

export interface DataSource {
  id: string; name: string; dataClass: DataClass; status: string; source: string | null; sourceUrl: string | null; licence: string | null;
  retrievedAt: string | null; dataTimestamp: string | null; ageDays: number | null; freshness: string; coverage: string; notes: string;
}
export interface ProvenanceResponse {
  product: string; studyArea: Area; sources: DataSource[]; liveTrafficStatus: string;
  graph: {
    nodes: number; segments: number; arcs: number; oneWaySegments: number; totalKm: number;
    byClass: { roadClass: string; segments: number; km: number }[];
    attributes: {
      namePresentPct: number; lanesTagPresentPct: number; maxspeedTagPresentPct: number;
      segmentsMissingName: number; segmentsMissingLanes: number; segmentsMissingMaxspeed: number;
    };
    connectivity: { weakComponents: number; largestComponentNodes: number; largestComponentShare: number; nodesOutsideLargest: number; summary: string };
  };
  assumptions: { travelTime: string; capacity: string; classDefaults: Record<string, { speed: number; lanes: number; perLane: number }> };
}

export interface Segment {
  id: number; wayId: number; name: string; roadClass: string; from: number; to: number; oneWay: boolean;
  lengthM: number; travelTimeS: number; capacityVph: number; lanesTag: number | null; maxspeedTag: number | null; geometry: number[];
}
export interface NetworkResponse { area: Area; nodes: number; segments: number; truncated: boolean; items: Segment[] }
export interface SegmentDetail { segment: Segment; attributes: DataItem[]; missingAttributes: string[]; osmUrl: string }

export interface Snap { node: number; osmNodeId: number; lat: number; lon: number; snapDistanceM: number }
export interface QueueStats { queue: string; settled: number; relaxations: number; heapOperations: number; elapsedMs: number }
export interface RouteResponse {
  from: Snap; to: Snap; metric: string; reachable: boolean; distanceM: number; estimatedTimeS: number; maxspeedTaggedSharePct: number;
  segmentIds: number[]; geometry: number[][]; steps: { name: string; roadClass: string; lengthM: number }[];
  fibonacci: QueueStats; binary: QueueStats; queuesAgree: boolean; avoided: number; explanation: Explanation;
}

export interface RoadMatch { name: string; roadClass: string; lat: number; lon: number; totalKm: number; segments: number }
export interface PlaceMatch { displayName: string; lat: number; lon: number; type: string }
export interface SearchResponse { query: string; roads: RoadMatch[]; places: PlaceMatch[]; placesStatus: string }

export interface ComponentInfo { id: number; nodes: number; segments: number; centerLat: number; centerLon: number }
export interface ComponentSummary {
  componentCount: number; largestNodes: number; largestShare: number; isolatedNodes: number; successfulUnions: number;
  redundantUnions: number; components: ComponentInfo[];
}
export interface PairConnectivity {
  from: Snap; to: Snap; weaklyConnectedBefore: boolean; weaklyConnectedAfter: boolean; directedPathBefore: boolean;
  directedPathAfter: boolean; reachableFromOriginAfter: number; originWeakComponentSizeAfter: number;
}
export interface ComponentsResponse {
  area: Area; nodes: number; segments: number; removed: number; before: ComponentSummary; after: ComponentSummary;
  segmentComponent: [number, number][]; pair: PairConnectivity | null; explanation: Explanation;
}

export interface CutSegment { segmentId: number; name: string; roadClass: string; fromNode: number; toNode: number; capacity: number; geometry: number[] }
export interface FlowStats { algorithm: string; maxFlow: number; augmentations: number; arcScans: number; elapsedMs: number }
export interface CriticalLinksResponse {
  area: Area; source: Snap; sink: Snap; capacityModel: string; unit: string; nodes: number; arcs: number; maxFlow: number;
  minCutCapacity: number; maxFlowEqualsMinCut: boolean; flowVerified: boolean; cut: CutSegment[]; segmentFlows: [number, number][];
  edmondsKarp: FlowStats; fordFulkerson: FlowStats; algorithmsAgree: boolean;
  paths: { step: number; bottleneck: number; flowAfter: number; segmentIds: number[] }[]; warnings: string[]; explanation: Explanation;
}

export interface LengthStatsResponse {
  area: Area; observations: number; explanation: Explanation;
  percentiles: { percentile: number; value: number; quickselectComparisons: number; quickselectMs: number; sortMs: number; matchesSort: boolean }[];
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
  exactKm: number | null; certifiedRatioBound: number; plannedMatrixKm: number[][]; geometry: number[][]; guarantee: string;
  assumptions: string[]; explanation: Explanation;
}

// ---------------------------------------------------------------- Silent Shield integrity check
export type ModType = 'REMOVE_WAY' | 'FLIP_ONEWAY' | 'ADD_CONNECTION';
export interface DemoModification { type: ModType; wayId?: number | null; fromLat?: number | null; fromLon?: number | null; toLat?: number | null; toLon?: number | null }
export type ChangeStatus = 'LEGITIMATE_REVIEWED' | 'ROUTINE_UNREVIEWED' | 'SUSPICIOUS_NEEDS_REVIEW' | 'SIMULATED_TAMPERING';
export interface SnapshotInfo {
  role: string; osmDate: string | null; retrievedAt: string | null; fileSha256: string; areaSha256: string;
  ways: number; segments: number; nodes: number; dataClass: string;
}
export interface ProbeRoute {
  reachable: boolean; distanceM: number; segments: number; wayIds: number[]; geometry: number[][];
  fibonacci: QueueStats; binary: QueueStats; queuesAgree: boolean;
}
export interface SideSummary {
  snapshot: SnapshotInfo; connectivity: { components: number; largestNodes: number; largestShare: number }; route: ProbeRoute;
  flow: {
    disjointRoutesEdmondsKarp: number; disjointRoutesFordFulkerson: number; minCutSize: number; maxFlowEqualsMinCut: boolean;
    edmondsKarpMs: number; fordFulkersonMs: number; cut: { wayId: number; name: string; roadClass: string; geometry: number[] }[];
  };
}
export interface Change {
  id: string; wayId: number; kinds: string[]; origin: 'REAL_OSM_EDIT' | 'DEMONSTRATION'; status: ChangeStatus; roadClass: string;
  name: string; before: string; after: string; lat: number; lon: number; geometry: number[][]; disconnectedNodes: number;
  onProbeRoute: boolean; score: number; scoreBreakdown: string[]; historyUrl: string | null;
}
export interface IntegrityResponse {
  area: Area; probeFrom: Snap; probeTo: Snap; baseline: SideSummary; updated: SideSummary; totalChanges: number;
  changesTruncated: boolean; changes: Change[];
  statusCounts: { legitimateReviewed: number; routineUnreviewed: number; suspiciousNeedsReview: number; simulatedTampering: number; verifiedMalicious: number };
  bloom: { trustedFingerprints: number; screened: number; definitelyNew: number; possiblyKnown: number; confirmedKnown: number; falsePositives: number; bits: number; hashes: number; theoreticalFpr: number; note: string };
  ranking: { k: number; candidates: number; topIds: string[]; kthScore: number; quickselectComparisons: number; matchesSort: boolean; note: string };
  alerts: { size: number; height: number; blackHeight: number; rotations: number; invariantsHold: boolean; removedAfterReview: number; inOrder: string[]; key: string };
  strips: { strips: number; stripWestLon: number[]; risk: number[]; queryFrom: number; queryTo: number; querySum: number; matchesBruteForce: boolean; hottestStrip: number; hottestRisk: number; updatesApplied: number; nodesVisited: number; note: string };
  inspection: TourResponse | null; inspectionNote: string;
  algorithms: { algorithm: string; input: string; output: string; complexity: string; limitation: string }[];
  scoringRule: string[]; suspiciousThreshold: number; warnings: string[]; explanation: Explanation; verifiedMaliciousNote: string;
}
export interface ScenarioResponse { name: string; label: string; modifications: DemoModification[]; description: string[] }

// ---------------------------------------------------------------- Algorithm Lab
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
export interface AlgorithmInfo { id: string; name: string; syllabusUnit: string; javaClass: string; time: string; space: string; usedIn: string; limitations: string }
export interface BenchmarkRow { size: string; primary: string; primaryMedianMs: number; primaryMinMs: number; primaryOps: number; baseline: string; baselineMedianMs: number | null; baselineOps: number | null; quality: string }
export interface BenchmarkReport { algorithm: string; title: string; opsMeaning: string; warmups: number; repetitions: number; rows: BenchmarkRow[]; environment: string; methodology: string[] }
export interface SelfCheckReport { allPassed: boolean; seed: number; checks: { algorithm: string; property: string; cases: number; passed: boolean; detail: string; ms: number }[] }
