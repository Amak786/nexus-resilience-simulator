package edu.nexus.dto;

import edu.nexus.dto.FlowDtos.CutEdge;
import edu.nexus.dto.ResilienceDtos.ComponentsSummary;
import edu.nexus.model.CityDataset;

import java.util.List;

/** Types for the Failure Lab: one connected failure-impact story across all algorithms. */
public final class FailureLabDtos {

    private FailureLabDtos() {}

    /**
     * @param windowHours       planning window the flow capacity refers to (default 12)
     * @param worstWindowHours  length of the worst-shortfall interval to search for (default 6)
     * @param batchScenarios    random failure scenarios for the impact distribution (default 100)
     * @param roadsPerScenario  roads failed in each random scenario (default 2)
     * @param seed              seed for the random scenarios and Quickselect pivots (default 2026)
     */
    public record FailureLabRequest(CityDataset dataset, List<String> failedRoadIds, List<String> failedNodeIds,
                                    Integer windowHours, Integer worstWindowHours, Integer batchScenarios,
                                    Integer roadsPerScenario, Long seed) {}

    public record ZoneImpact(String id, String name, long demand, long receivedBefore, long receivedAfter,
                             long lostUnits) {}

    public record Interval(int startHour, int endHour, long units) {}

    /**
     * Hourly view. {@code capacityPerHour*} = max flow / window hours (assumes deliveries are spread
     * evenly over the window). Shortfall = max(0, demand - capacity), rounded to whole units.
     */
    public record TimelineImpact(int hours, List<Long> demand, double capacityPerHourBefore,
                                 double capacityPerHourAfter, List<Long> shortfallBefore, List<Long> shortfallAfter,
                                 long totalShortfallBefore, long totalShortfallAfter, Interval worstWindowAfter,
                                 Interval peakShortfallHourAfter, long segmentTreeNodesVisited) {}

    public record PercentileValue(double percentile, double value, long comparisons, boolean matchesSortBaseline) {}

    public record ScenarioImpact(List<String> failedRoads, long throughput, long impact) {}

    public record BatchImpact(int scenarios, int roadsPerScenario, long seed, List<Long> impacts,
                              List<PercentileValue> percentiles, long sortBaselineComparisons,
                              double selectedFailurePercentileRank, List<ScenarioImpact> worstScenarios) {}

    public record RecoveryAction(String type, String target, String description, long throughputAfterAction,
                                 long gain, double recoversPercentOfDrop) {}

    public record FailureLabResponse(long throughputBefore, long throughputAfter, long throughputDrop,
                                     double dropPercent, List<CutEdge> minCutBefore, List<CutEdge> minCutAfter,
                                     List<ZoneImpact> zones, ComponentsSummary connectivityBefore,
                                     ComponentsSummary connectivityAfter, List<String> newlyIsolatedZones,
                                     TimelineImpact timeline, BatchImpact batch, List<RecoveryAction> recoveryActions,
                                     List<String> assumptions, List<String> warnings) {}
}
