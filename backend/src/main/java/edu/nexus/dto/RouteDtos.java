package edu.nexus.dto;

import edu.nexus.model.CityDataset;

import java.util.List;

/** Types for delivery-route planning (metric TSP 2-approximation). */
public final class RouteDtos {

    private RouteDtos() {}

    public record RouteRequest(CityDataset dataset, String depotId, List<String> stopIds, List<String> failedRoadIds,
                               List<String> failedNodeIds) {}

    public record Leg(String from, String to, double km) {}

    /**
     * {@code certifiedRatioBound} = routeKm / mstLowerBoundKm, an upper bound on routeKm / OPT that is
     * known without solving the problem exactly. {@code exactKm} is only computed for at most 10 stops.
     */
    public record RouteResponse(List<String> tour, List<Leg> legs, double routeKm, List<String> nearestNeighbourTour,
                                double nearestNeighbourKm, double mstLowerBoundKm, double certifiedRatioBound,
                                Double exactKm, Double actualRatio, List<List<String>> mstEdges,
                                List<String> unreachableStops, List<String> matrixLabels, List<List<Double>> matrix,
                                String guarantee, List<String> assumptions) {}

    /** Algorithm Lab: TSP on a user-supplied matrix. */
    public record MatrixTspRequest(List<String> labels, List<List<Double>> matrix, Integer start) {}
}
