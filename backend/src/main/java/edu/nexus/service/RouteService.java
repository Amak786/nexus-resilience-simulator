package edu.nexus.service;

import edu.nexus.algorithms.graph.ShortestPaths;
import edu.nexus.algorithms.tsp.DistanceMatrix;
import edu.nexus.algorithms.tsp.HeldKarpTsp;
import edu.nexus.algorithms.tsp.MstPreorderTsp;
import edu.nexus.algorithms.tsp.NearestNeighbourTsp;
import edu.nexus.dto.RouteDtos.Leg;
import edu.nexus.dto.RouteDtos.MatrixTspRequest;
import edu.nexus.dto.RouteDtos.RouteRequest;
import edu.nexus.dto.RouteDtos.RouteResponse;
import edu.nexus.model.CityNode;
import edu.nexus.model.NodeType;
import edu.nexus.model.Road;
import edu.nexus.model.ValidationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Delivery tour for one vehicle: depot plus a set of stops.
 *
 * <p>The <b>distance matrix</b> is built from road {@code lengthKm} (never from capacity) by running
 * Dijkstra from every stop over the undirected operating road graph. Assumption: under emergency traffic
 * control a relief convoy may use a one-way road in either direction, so travel distances are symmetric.
 * Shortest-path distances satisfy the triangle inequality, and the matrix is still validated before use.
 */
@Service
public class RouteService {

    public static final int MAX_STOPS = 60;
    public static final int EXACT_LIMIT = 10;
    public static final String GUARANTEE =
            "MST + preorder tour length <= 2 x optimal for metric distances (symmetric, non-negative, triangle inequality). The tour is approximate, not claimed optimal.";

    public RouteResponse plan(RouteRequest req) {
        if (req == null) {
            throw new ValidationException("request", "is required");
        }
        Map<String, CityNode> byId = DatasetValidator.validate(req.dataset());
        Set<String> failedRoads = Inputs.knownIds(req.failedRoadIds(), FlowService.roadIds(req.dataset().roads()), "failedRoadIds", 3000);
        Set<String> failedNodes = Inputs.knownIds(req.failedNodeIds(), byId.keySet(), "failedNodeIds", 500);
        CityNode depot = byId.get(req.depotId());
        if (depot == null || depot.type() != NodeType.DEPOT) {
            throw new ValidationException("depotId", "must name a DEPOT node");
        }
        if (failedNodes.contains(depot.id())) {
            throw new ValidationException("depotId", "the depot is marked as failed");
        }
        if (req.stopIds() == null || req.stopIds().isEmpty()) {
            throw new ValidationException("stopIds", "choose at least one stop");
        }
        if (req.stopIds().size() > MAX_STOPS) {
            throw new ValidationException("stopIds", "at most " + MAX_STOPS + " stops");
        }
        Set<String> stops = new LinkedHashSet<>(Inputs.knownIds(req.stopIds(), byId.keySet(), "stopIds", MAX_STOPS));
        stops.remove(depot.id());
        for (String s : stops) {
            if (failedNodes.contains(s)) {
                throw new ValidationException("stopIds", "stop '" + s + "' is marked as failed");
            }
        }
        List<CityNode> nodes = req.dataset().nodes();
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            idx.put(nodes.get(i).id(), i);
        }
        ShortestPaths sp = new ShortestPaths(nodes.size());
        for (Road r : req.dataset().roads() == null ? List.<Road>of() : req.dataset().roads()) {
            if (!failedRoads.contains(r.id()) && !failedNodes.contains(r.from()) && !failedNodes.contains(r.to())) {
                sp.addUndirectedEdge(idx.get(r.from()), idx.get(r.to()), r.lengthKm());
            }
        }
        double[] fromDepot = sp.from(idx.get(depot.id()));
        List<String> labels = new ArrayList<>();
        labels.add(depot.id());
        List<String> unreachable = new ArrayList<>();
        for (String s : stops) {
            if (Double.isInfinite(fromDepot[idx.get(s)])) {
                unreachable.add(s);
            } else {
                labels.add(s);
            }
        }
        int k = labels.size();
        double[][] m = new double[k][k];
        for (int i = 0; i < k; i++) {
            double[] dist = sp.from(idx.get(labels.get(i)));
            for (int j = 0; j < k; j++) {
                m[i][j] = dist[idx.get(labels.get(j))]; // unrounded: rounding could break the triangle inequality
            }
        }
        for (int i = 0; i < k; i++) {
            for (int j = i + 1; j < k; j++) { // remove rounding asymmetry
                double v = Math.min(m[i][j], m[j][i]);
                m[i][j] = v;
                m[j][i] = v;
            }
        }
        List<String> assumptions = List.of(
                "Distances are shortest road distances in km (Dijkstra on road lengthKm), not road capacities.",
                "Under emergency traffic control a convoy may use any operating road in either direction.",
                "Failed roads and facilities are removed before computing distances.",
                "One vehicle, unlimited load, no time windows: a simplified routing model.");
        return solve(labels, m, 0, unreachable, assumptions);
    }

    /** Algorithm Lab entry point: arbitrary labelled matrix, validated as a metric. */
    public RouteResponse planMatrix(MatrixTspRequest req) {
        if (req == null || req.matrix() == null || req.matrix().isEmpty()) {
            throw new ValidationException("matrix", "is required");
        }
        int n = req.matrix().size();
        if (n > MAX_STOPS) {
            throw new ValidationException("matrix", "at most " + MAX_STOPS + " rows");
        }
        List<String> labels = req.labels() == null ? new ArrayList<>() : new ArrayList<>(req.labels());
        if (labels.isEmpty()) {
            for (int i = 0; i < n; i++) {
                labels.add("P" + i);
            }
        }
        if (labels.size() != n || new LinkedHashSet<>(labels).size() != n) {
            throw new ValidationException("labels", "need " + n + " distinct labels");
        }
        double[][] m = new double[n][];
        for (int i = 0; i < n; i++) {
            List<Double> row = req.matrix().get(i);
            if (row == null || row.size() != n) {
                throw new ValidationException("matrix[" + i + "]", "must have exactly " + n + " entries");
            }
            m[i] = new double[n];
            for (int j = 0; j < n; j++) {
                if (row.get(j) == null) {
                    throw new ValidationException("matrix[" + i + "][" + j + "]", "is missing (unreachable stops must be removed explicitly)");
                }
                m[i][j] = row.get(j);
            }
        }
        int start = Inputs.intParam(req.start(), 0, 0, n - 1, "start");
        return solve(labels, m, start, List.of(), List.of("Matrix supplied by the user and validated as a metric before solving."));
    }

    private static RouteResponse solve(List<String> labels, double[][] raw, int start, List<String> unreachable,
                                       List<String> assumptions) {
        DistanceMatrix d;
        try {
            d = DistanceMatrix.of(raw);
        } catch (DistanceMatrix.NonMetricException ex) {
            throw new ValidationException("matrix", "not a metric: " + ex.getMessage() + ". The 2-approximation guarantee would not hold, so the input is rejected.");
        }
        int n = d.size();
        MstPreorderTsp.Result approx = MstPreorderTsp.solve(d, start);
        int[] nn = NearestNeighbourTsp.solve(d, start);
        Double exact = n <= EXACT_LIMIT ? round3(HeldKarpTsp.optimalLength(d)) : null;
        List<String> tour = names(labels, approx.order());
        List<Leg> legs = new ArrayList<>();
        int[] o = approx.order();
        for (int i = 0; i < o.length && n > 1; i++) {
            int a = o[i];
            int b = o[(i + 1) % o.length];
            legs.add(new Leg(labels.get(a), labels.get(b), round3(d.get(a, b))));
        }
        List<List<String>> mst = new ArrayList<>();
        for (int[] e : approx.mstEdges()) {
            mst.add(List.of(labels.get(e[0]), labels.get(e[1])));
        }
        List<List<Double>> matrix = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Double> row = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                row.add(round3(d.get(i, j)));
            }
            matrix.add(row);
        }
        double len = round3(approx.length());
        double mstW = round3(approx.mstWeight());
        double bound = mstW == 0 ? 1.0 : round3(len / mstW);
        Double actual = exact == null ? null : (exact == 0 ? 1.0 : round3(len / exact));
        return new RouteResponse(tour, legs, len, names(labels, nn), round3(d.tourLength(nn)), mstW, bound, exact, actual,
                mst, unreachable, labels, matrix, GUARANTEE, assumptions);
    }

    private static List<String> names(List<String> labels, int[] order) {
        List<String> out = new ArrayList<>();
        for (int i : order) {
            out.add(labels.get(i));
        }
        return out;
    }

    private static double round3(double v) {
        return Double.isFinite(v) ? Math.round(v * 1000) / 1000.0 : v;
    }
}
