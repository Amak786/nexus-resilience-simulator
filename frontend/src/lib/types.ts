// Mirrors of the Java DTOs (edu.nexus.dto.*). The front end only displays these results.

export type NodeType = 'DEPOT' | 'JUNCTION' | 'DEMAND_ZONE';

export interface CityNode {
  id: string; name: string; type: NodeType; x: number; y: number;
  supply?: number | null; demand?: number | null; severity?: number | null;
  vulnerablePercent?: number | null; population?: number | null;
}
export interface Road { id: string; from: string; to: string; capacity: number; lengthKm: number; oneWay?: boolean | null }
export interface Incident { id: string; title: string; zoneId: string | null; severity: number; affectedPeople: number; reportedHour: number }
export interface DatasetMeta { name: string; description: string; synthetic: boolean | null; units: string; version: string }
export interface CityDataset {
  meta: DatasetMeta | null; nodes: CityNode[]; roads: Road[];
  incidents: Incident[] | null; demandTimeline: { label: string; hourlyDemand: number[] } | null;
}
export interface DatasetSummary {
  name: string; synthetic: boolean; nodes: number; depots: number; junctions: number; demandZones: number;
  roads: number; oneWayRoads: number; incidents: number; timelineHours: number; totalSupply: number;
  totalDemand: number; totalRoadCapacity: number; totalRoadKm: number;
}

export interface ValidationIssue { field: string; message: string }
export interface ApiErrorBody { error: string; message: string; issues: ValidationIssue[] }

export interface FlowEdge { index: number; kind: 'SUPPLY' | 'ROAD' | 'DEMAND'; roadId: string | null; from: string; to: string; capacity: number; flow: number; saturated: boolean; inMinCut: boolean }
export interface CutEdge { index: number; kind: string; roadId: string | null; from: string; to: string; capacity: number; explanation: string }
export interface ZoneAllocation { id: string; name: string; demand: number; received: number; unmet: number; status: string }
export interface DepotUsage { id: string; name: string; supply: number; dispatched: number; unused: number }
export interface RunStats { algorithm: string; maxFlow: number; augmentations: number; arcScans: number; elapsedMs: number }
export interface TraceArc { edge: number; reverse: boolean }
export interface TraceStep { step: number; nodes: string[]; arcs: TraceArc[]; bottleneck: number; flowAfter: number; edgeFlows: number[] | null }
export interface FlowResponse {
  algorithm: string; source: string; sink: string; maxFlow: number; minCutCapacity: number; maxFlowEqualsMinCut: boolean;
  edges: FlowEdge[]; minCut: CutEdge[]; sourceSideNodes: string[]; zones: ZoneAllocation[] | null; depots: DepotUsage[] | null;
  totalDemand: number | null; unmetDemand: number | null; totalSupply: number | null; primary: RunStats; baseline: RunStats | null;
  baselineAgrees: boolean | null; trace: TraceStep[]; traceTruncated: boolean; assumptions: string[]; warnings: string[];
}

export interface Component { id: number; nodeIds: string[]; depotIds: string[]; zoneIds: string[]; totalSupply: number; totalDemand: number; hasDepot: boolean }
export interface ComponentsSummary { componentCount: number; components: Component[]; isolatedZoneIds: string[]; excludedFailedNodes: string[]; successfulUnions: number; redundantUnions: number }
export interface ResilienceResponse {
  before: ComponentsSummary; after: ComponentsSummary; newlyIsolatedZones: string[];
  unionTrace: { roadId: string; a: string; b: string; merged: boolean }[];
  criticalRoads: { roadId: string; from: string; to: string; componentsIfRemoved: number; zonesCutOffFromDepots: string[] }[];
  notes: string[];
}

export interface Interval { startHour: number; endHour: number; units: number }
export interface FailureLabResponse {
  throughputBefore: number; throughputAfter: number; throughputDrop: number; dropPercent: number;
  minCutBefore: CutEdge[]; minCutAfter: CutEdge[];
  zones: { id: string; name: string; demand: number; receivedBefore: number; receivedAfter: number; lostUnits: number }[];
  connectivityBefore: ComponentsSummary; connectivityAfter: ComponentsSummary; newlyIsolatedZones: string[];
  timeline: {
    hours: number; demand: number[]; capacityPerHourBefore: number; capacityPerHourAfter: number;
    shortfallBefore: number[]; shortfallAfter: number[]; totalShortfallBefore: number; totalShortfallAfter: number;
    worstWindowAfter: Interval; peakShortfallHourAfter: Interval; segmentTreeNodesVisited: number;
  } | null;
  batch: {
    scenarios: number; roadsPerScenario: number; seed: number; impacts: number[];
    percentiles: { percentile: number; value: number; comparisons: number; matchesSortBaseline: boolean }[];
    sortBaselineComparisons: number; selectedFailurePercentileRank: number;
    worstScenarios: { failedRoads: string[]; throughput: number; impact: number }[];
  };
  recoveryActions: { type: string; target: string; description: string; throughputAfterAction: number; gain: number; recoversPercentOfDrop: number }[];
  assumptions: string[]; warnings: string[];
}

export interface NodeView { id: number; key: string; color: 'RED' | 'BLACK'; left: number; right: number; subtreeSize: number }
export interface PriorityOperation { type: 'INSERT' | 'UPDATE' | 'RESOLVE' | 'LOOKUP'; id: string; title?: string; zoneId?: string | null; severity?: number; affectedPeople?: number }
export interface PriorityResponse {
  queue: { rank: number; id: string; title: string; zoneId: string | null; severity: number; affectedPeople: number; vulnerabilityBonus: number; score: number; key: string }[];
  tree: NodeView[];
  metrics: { size: number; height: number; blackHeight: number; heightUpperBound: number; rotations: number; invariantsHold: boolean; invariantMessage: string };
  operations: { index: number; type: string; id: string; outcome: string; rotations: number; events: string[] }[];
  scoreFormula: string; disclaimer: string;
}

export interface ScreenResponse {
  bitCount: number; hashCount: number; memoryBytes: number; expectedInsertions: number; targetFalsePositiveRate: number;
  insertedCount: number; setBits: number; fillRatio: number; theoreticalFprAtExpected: number; theoreticalFprAtActual: number;
  absentProbes: number; probeFalsePositives: number; measuredFpr: number; registrySize: number; duplicateRegistryEntries: number;
  registrySample: string[]; results: { id: string; bloom: string; verdict: string; exactLookupPerformed: boolean; bitPositions: number[] }[];
  exactLookupsAvoided: number; formulas: string[]; warnings: string[];
}

export interface RouteResponse {
  tour: string[]; legs: { from: string; to: string; km: number }[]; routeKm: number; nearestNeighbourTour: string[];
  nearestNeighbourKm: number; mstLowerBoundKm: number; certifiedRatioBound: number; exactKm: number | null;
  actualRatio: number | null; mstEdges: string[][]; unreachableStops: string[]; matrixLabels: string[];
  matrix: number[][]; guarantee: string; assumptions: string[];
}

export interface AlgorithmInfo { id: string; name: string; syllabusUnit: string; javaClass: string; time: string; space: string; usedIn: string; limitations: string }
export interface BenchmarkRow { size: string; primary: string; primaryMedianMs: number; primaryMinMs: number; primaryOps: number; baseline: string; baselineMedianMs: number | null; baselineOps: number | null; quality: string }
export interface BenchmarkReport { algorithm: string; title: string; opsMeaning: string; warmups: number; repetitions: number; rows: BenchmarkRow[]; environment: string; methodology: string[] }
export interface SelfCheckReport { allPassed: boolean; seed: number; checks: { algorithm: string; property: string; cases: number; passed: boolean; detail: string; ms: number }[] }
