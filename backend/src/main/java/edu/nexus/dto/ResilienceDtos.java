package edu.nexus.dto;

import edu.nexus.model.CityDataset;

import java.util.List;

/** Types for connectivity analysis on the undirected infrastructure graph (DSU). */
public final class ResilienceDtos {

    private ResilienceDtos() {}

    public record ResilienceRequest(CityDataset dataset, List<String> failedRoadIds, List<String> failedNodeIds,
                                    Boolean scanCriticalRoads) {}

    public record Component(int id, List<String> nodeIds, List<String> depotIds, List<String> zoneIds,
                            long totalSupply, long totalDemand, boolean hasDepot) {}

    /** {@code isolatedZoneIds}: demand zones in a component with no operating depot. */
    public record ComponentsSummary(int componentCount, List<Component> components, List<String> isolatedZoneIds,
                                    List<String> excludedFailedNodes, int successfulUnions, int redundantUnions) {}

    public record UnionStep(String roadId, String a, String b, boolean merged) {}

    public record CriticalRoad(String roadId, String from, String to, int componentsIfRemoved,
                               List<String> zonesCutOffFromDepots) {}

    public record ResilienceResponse(ComponentsSummary before, ComponentsSummary after, List<String> newlyIsolatedZones,
                                     List<UnionStep> unionTrace, List<CriticalRoad> criticalRoads, List<String> notes) {}
}
