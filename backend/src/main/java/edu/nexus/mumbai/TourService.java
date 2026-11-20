package edu.nexus.mumbai;

import edu.nexus.algorithms.graph.Dijkstra;
import edu.nexus.algorithms.graph.RoadGraph;
import edu.nexus.algorithms.tsp.DistanceMatrix;
import edu.nexus.algorithms.tsp.HeldKarpTsp;
import edu.nexus.algorithms.tsp.MstPreorderTsp;
import edu.nexus.algorithms.tsp.NearestNeighbourTsp;
import edu.nexus.model.ValidationException;
import edu.nexus.mumbai.MumbaiDtos.Snap;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-stop inspection planner: metric-TSP 2-approximation on real road distances.
 *
 * <ol>
 *   <li>Snap each stop to the nearest intersection; run Dijkstra (Fibonacci heap) from every stop to get
 *       directed shortest road distances d(i, j).</li>
 *   <li>Keep only stops in the same strongly connected part as the start (reachable there <i>and</i> back);
 *       others are reported, never silently dropped.</li>
 *   <li>Planning matrix D(i, j) = max(d(i, j), d(j, i)). It is symmetric, non-negative and satisfies the
 *       triangle inequality (max of two metrics' entries is bounded by sums of maxima), and it upper-bounds
 *       travel in either direction. The matrix is validated before solving.</li>
 *   <li>MST + preorder walk gives the planning tour (&lt;= 2 x optimal for D). The executable route expands
 *       each leg into the directed shortest road path, so its length is &lt;= the planned length.</li>
 * </ol>
 */
@Service
public class TourService {

    public static final int MAX_STOPS = 15;
    public static final int EXACT_LIMIT = 10;

    public record Stop(String label, Double lat, Double lon) {}

    public record TourRequest(List<Stop> stops, Integer startIndex) {}

    public record Leg(String from, String to, double plannedKm, double executableKm, List<Integer> segmentIds) {}

    public record TourResponse(List<String> tour, List<Snap> snapped, List<String> excludedStops, List<Leg> legs,
                               double plannedKm, double executableKm, double nearestNeighbourKm, List<String> nearestNeighbourTour,
                               double mstLowerBoundKm, Double exactKm, double certifiedRatioBound, List<List<Double>> plannedMatrixKm,
                               List<double[]> geometry, String guarantee, List<String> assumptions) {}

    private final MumbaiService mumbai;

    public TourService(MumbaiService mumbai) {
        this.mumbai = mumbai;
    }

    public TourResponse plan(TourRequest req) {
        if (req == null || req.stops() == null || req.stops().size() < 2) {
            throw new ValidationException("stops", "choose at least two stops");
        }
        if (req.stops().size() > MAX_STOPS) {
            throw new ValidationException("stops", "at most " + MAX_STOPS + " stops");
        }
        int start = req.startIndex() == null ? 0 : req.startIndex();
        if (start < 0 || start >= req.stops().size()) {
            throw new ValidationException("startIndex", "must point to one of the stops");
        }
        RoadGraph g = mumbai.network().graph();
        int k = req.stops().size();
        List<Snap> snaps = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            Stop s = req.stops().get(i);
            if (s == null || s.lat() == null || s.lon() == null) {
                throw new ValidationException("stops[" + i + "]", "lat and lon are required");
            }
            snaps.add(mumbai.snap(s.lat(), s.lon(), null, "stops[" + i + "]"));
            labels.add(s.label() == null || s.label().isBlank() ? "Stop " + (i + 1) : s.label().trim());
        }
        for (int i = 0; i < k; i++) {
            for (int j = i + 1; j < k; j++) {
                if (snaps.get(i).node() == snaps.get(j).node()) {
                    throw new ValidationException("stops", labels.get(i) + " and " + labels.get(j) + " snap to the same intersection");
                }
            }
        }
        Dijkstra.Result[] runs = new Dijkstra.Result[k];
        for (int i = 0; i < k; i++) {
            runs[i] = Dijkstra.run(g, snaps.get(i).node(), -1, RoadGraph.Metric.DISTANCE, Dijkstra.Queue.FIBONACCI);
        }
        List<Integer> keep = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            boolean ok = Double.isFinite(runs[start].dist()[snaps.get(i).node()]) && Double.isFinite(runs[i].dist()[snaps.get(start).node()]);
            if (ok) {
                keep.add(i);
            } else {
                excluded.add(labels.get(i) + " (no road path to and from the start in both directions)");
            }
        }
        if (keep.size() < 2) {
            throw new ValidationException("stops", "fewer than two stops are mutually reachable by road from the start");
        }
        int m = keep.size();
        double[][] directed = new double[m][m];
        double[][] planned = new double[m][m];
        for (int a = 0; a < m; a++) {
            for (int b = 0; b < m; b++) {
                directed[a][b] = runs[keep.get(a)].dist()[snaps.get(keep.get(b)).node()] / 1000.0;
            }
        }
        for (int a = 0; a < m; a++) {
            for (int b = 0; b < m; b++) {
                planned[a][b] = Math.max(directed[a][b], directed[b][a]);
            }
        }
        DistanceMatrix d;
        try {
            d = DistanceMatrix.of(planned);
        } catch (DistanceMatrix.NonMetricException ex) {
            throw new IllegalStateException("planning matrix failed the metric check: " + ex.getMessage());
        }
        int s0 = keep.indexOf(start);
        MstPreorderTsp.Result approx = MstPreorderTsp.solve(d, s0);
        int[] nn = NearestNeighbourTsp.solve(d, s0);
        Double exact = null;
        if (m <= EXACT_LIMIT) {
            exact = round3(rotatedExact(d, s0));
        }
        List<Leg> legs = new ArrayList<>();
        List<double[]> geometry = new ArrayList<>();
        double execKm = 0;
        int[] o = approx.order();
        for (int i = 0; i < o.length; i++) {
            int a = o[i];
            int b = o[(i + 1) % o.length];
            int from = keep.get(a);
            int to = keep.get(b);
            List<Integer> segs = runs[from].segmentsTo(g, snaps.get(to).node());
            int cur = snaps.get(from).node();
            for (int seg : segs) {
                double[] gm = mumbai.network().geometry(seg);
                geometry.add(g.segU(seg) == cur ? gm : MumbaiService.reversed(gm));
                cur = g.segU(seg) == cur ? g.segV(seg) : g.segU(seg);
            }
            execKm += directed[a][b];
            legs.add(new Leg(labels.get(from), labels.get(to), round3(planned[a][b]), round3(directed[a][b]), segs));
        }
        List<String> tour = new ArrayList<>();
        for (int idx : o) {
            tour.add(labels.get(keep.get(idx)));
        }
        List<String> nnTour = new ArrayList<>();
        for (int idx : nn) {
            nnTour.add(labels.get(keep.get(idx)));
        }
        List<List<Double>> matrix = new ArrayList<>();
        for (double[] row : planned) {
            List<Double> r = new ArrayList<>();
            for (double v : row) {
                r.add(round3(v));
            }
            matrix.add(r);
        }
        List<Snap> keptSnaps = new ArrayList<>();
        keep.forEach(i -> keptSnaps.add(snaps.get(i)));
        double mst = approx.mstWeight();
        return new TourResponse(tour, keptSnaps, excluded, legs, round3(approx.length()), round3(execKm),
                round3(d.tourLength(nn)), nnTour, round3(mst), exact, mst == 0 ? 1 : round3(approx.length() / mst), matrix,
                geometry, "Planning tour <= 2 x the optimal tour for the symmetric planning distances (MST + preorder walk, triangle inequality verified). It is approximate, not optimal.",
                List.of("Distances are shortest real road lengths over the OSM arterial graph (km), computed with Dijkstra on a Fibonacci heap.",
                        "Planning distance = max of the two directed distances, so one-way streets never make the plan optimistic.",
                        "The executable route follows the directed shortest path for each leg; its length is at most the planned length.",
                        "Travel time, opening hours and vehicle limits are not modelled."));
    }

    /** Held-Karp fixes vertex 0 as start; tour length is rotation-invariant, so solving from index 0 is fine. */
    private static double rotatedExact(DistanceMatrix d, int start) {
        return HeldKarpTsp.optimalLength(d);
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
