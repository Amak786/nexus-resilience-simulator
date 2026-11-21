package edu.nexus.integrity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.nexus.algorithms.bloom.BloomFilter;
import edu.nexus.algorithms.dsu.DisjointSetUnion;
import edu.nexus.algorithms.flow.EdmondsKarp;
import edu.nexus.algorithms.flow.FlowNetwork;
import edu.nexus.algorithms.flow.FordFulkerson;
import edu.nexus.algorithms.flow.MaxFlowResult;
import edu.nexus.algorithms.flow.MinCut;
import edu.nexus.algorithms.flow.TraceOptions;
import edu.nexus.algorithms.graph.Dijkstra;
import edu.nexus.algorithms.graph.RoadGraph;
import edu.nexus.algorithms.rbtree.PriorityKey;
import edu.nexus.algorithms.rbtree.RedBlackTree;
import edu.nexus.algorithms.segtree.SegmentTree;
import edu.nexus.algorithms.select.RandomizedQuickselect;
import edu.nexus.integrity.IntegrityDtos.AlertIndex;
import edu.nexus.integrity.IntegrityDtos.AlgorithmStep;
import edu.nexus.integrity.IntegrityDtos.BloomScreen;
import edu.nexus.integrity.IntegrityDtos.Change;
import edu.nexus.integrity.IntegrityDtos.ConnectivityResult;
import edu.nexus.integrity.IntegrityDtos.CutLink;
import edu.nexus.integrity.IntegrityDtos.DemoModification;
import edu.nexus.integrity.IntegrityDtos.FlowResult;
import edu.nexus.integrity.IntegrityDtos.IntegrityRequest;
import edu.nexus.integrity.IntegrityDtos.IntegrityResponse;
import edu.nexus.integrity.IntegrityDtos.ProbeRoute;
import edu.nexus.integrity.IntegrityDtos.Ranking;
import edu.nexus.integrity.IntegrityDtos.RiskStrips;
import edu.nexus.integrity.IntegrityDtos.ScenarioResponse;
import edu.nexus.integrity.IntegrityDtos.SideSummary;
import edu.nexus.integrity.IntegrityDtos.SnapshotInfo;
import edu.nexus.integrity.IntegrityDtos.StatusCounts;
import edu.nexus.model.ValidationException;
import edu.nexus.mumbai.MumbaiDtos.Area;
import edu.nexus.mumbai.MumbaiDtos.DataClass;
import edu.nexus.mumbai.MumbaiDtos.DataItem;
import edu.nexus.mumbai.MumbaiDtos.Explanation;
import edu.nexus.mumbai.MumbaiDtos.QueueStats;
import edu.nexus.mumbai.MumbaiDtos.Snap;
import edu.nexus.mumbai.MumbaiNetwork;
import edu.nexus.mumbai.MumbaiService;
import edu.nexus.mumbai.TourService;
import edu.nexus.mumbai.TourService.TourResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Silent Shield integrity check: compares a TRUSTED BASELINE road-network snapshot (OpenStreetMap as of the baseline
 * date, fetched with an Overpass "attic" query) with the UPDATED snapshot (current OpenStreetMap), optionally with
 * user-entered DEMONSTRATION modifications applied on top, and measures what each difference does to connectivity
 * (DSU), a probe route (Dijkstra with a Fibonacci heap vs PriorityQueue) and road-disjoint routes (max flow / min cut).
 */
@Service
public class IntegrityService {

    public static final int SUSPICIOUS_THRESHOLD = 5;
    public static final int STRIPS = 16;
    public static final int MAX_DEMO_MODIFICATIONS = 10;
    public static final int MAX_CHANGES = 2500;
    public static final int MAX_CHANGES_RETURNED = 400;
    public static final long SEED = 20261121L;

    public static final List<String> SCORING_RULE = List.of(
            "Road class: motorway or trunk +3, primary +2, secondary or motorway/trunk link +1, other 0.",
            "Connectivity (DSU): junctions that lose (or gain) their connection to the main network because of this change: 0 -> +0, 1-4 -> +2, 5-19 -> +4, 20 or more -> +6.",
            "Probe route (Dijkstra): the change touches the probe route in either snapshot -> +3.",
            "Direction: one-way rule changed -> +2 on primary or higher roads, +1 otherwise.",
            "Provenance: no public edit record (a modification made to the dataset file itself, as in the demonstration) -> +3. Real OSM edits have a public, attributable history.",
            "Score >= " + SUSPICIOUS_THRESHOLD + " -> SUSPICIOUS_NEEDS_REVIEW (an anomaly to investigate, not proof of an attack); below -> ROUTINE_UNREVIEWED.",
            "An analyst can mark a real change LEGITIMATE_REVIEWED; demonstration changes are always SIMULATED_TAMPERING.");

    public static final String VERIFIED_MALICIOUS_NOTE = "Verified malicious activity: 0. This tool never labels a change as an attack. "
            + "Confirming malice needs external evidence (for example an incident report, account forensics or a confirmed "
            + "OSM data-working-group revert), which is not available here.";

    private final MumbaiService mumbai;
    private final TourService tours;
    private final MumbaiNetwork baselineNet;
    private final String baselineFileSha;
    private final String updatedFileSha;
    private final int maxSegments;

    public IntegrityService(ObjectMapper mapper, MumbaiService mumbai, TourService tours,
                            @Value("${nexus.max-flow-segments:6000}") int maxSegments) throws IOException {
        this.mumbai = mumbai;
        this.tours = tours;
        this.maxSegments = maxSegments;
        byte[] base = read("data/mumbai/roads-baseline.json");
        this.baselineFileSha = sha256(base);
        this.updatedFileSha = sha256(read("data/mumbai/roads.json"));
        try (InputStream in = new ByteArrayInputStream(base)) {
            this.baselineNet = MumbaiNetwork.load(in, mapper);
        }
    }

    // ------------------------------------------------------------------ snapshot model

    /** One directed-or-two-way road segment between two OSM nodes. wayId < 0 marks a demonstration connection. */
    record Edge(long wayId, long fromOsm, long toOsm, boolean oneWay, double lengthM, String roadClass, String name,
                double[] geom) {}

    /** The induced area subgraph of one snapshot, indexed for the algorithms. */
    static final class AreaGraph {
        final List<Edge> edges;
        final Map<Long, Integer> index = new HashMap<>();
        final List<Long> osm = new ArrayList<>();
        final List<double[]> coord = new ArrayList<>();
        final RoadGraph g;
        final Map<Long, List<Integer>> edgesOfWay = new LinkedHashMap<>();

        AreaGraph(List<Edge> edges, Map<Long, double[]> coords) {
            this.edges = edges;
            int m = edges.size();
            int[] u = new int[m];
            int[] v = new int[m];
            boolean[] ow = new boolean[m];
            double[] len = new double[m];
            double[] one = new double[m];
            for (int i = 0; i < m; i++) {
                Edge e = edges.get(i);
                u[i] = node(e.fromOsm(), coords);
                v[i] = node(e.toOsm(), coords);
                ow[i] = e.oneWay();
                len[i] = e.lengthM();
                one[i] = 1;
                edgesOfWay.computeIfAbsent(e.wayId(), k -> new ArrayList<>()).add(i);
            }
            g = new RoadGraph(osm.size(), u, v, ow, len, len, one);
        }

        private int node(long id, Map<Long, double[]> coords) {
            Integer i = index.get(id);
            if (i != null) {
                return i;
            }
            index.put(id, osm.size());
            osm.add(id);
            coord.add(coords.get(id));
            return osm.size() - 1;
        }

        int nodeCount() {
            return osm.size();
        }
    }

    record WayInfo(long wayId, String roadClass, String name, boolean oneWay, double lengthM, double lat, double lon,
                   List<double[]> geometry, String fingerprint) {}

    // ------------------------------------------------------------------ public API

    public IntegrityResponse analyse(IntegrityRequest req) {
        if (req == null || req.fromLat() == null || req.fromLon() == null || req.toLat() == null || req.toLon() == null) {
            throw new ValidationException("probe", "fromLat, fromLon, toLat and toLon (the probe route) are required");
        }
        Area area = mumbai.checkArea(req.area());
        Map<Long, double[]> coords = new HashMap<>();
        List<Edge> baseEdges = extract(baselineNet, area, coords);
        List<Edge> updEdges = extract(mumbai.network(), area, coords);
        if (baseEdges.isEmpty() || updEdges.isEmpty()) {
            throw new ValidationException("area", "the selected area contains no road segments in one of the snapshots");
        }
        if (baseEdges.size() > maxSegments || updEdges.size() > maxSegments) {
            throw new ValidationException("area", "the selected area has more than " + maxSegments
                    + " segments in a snapshot; select a smaller area");
        }
        List<String> warnings = new ArrayList<>();
        Set<Long> demoTouched = new HashSet<>();
        List<DemoModification> mods = req.demoModifications() == null ? List.of() : req.demoModifications();
        if (mods.size() > MAX_DEMO_MODIFICATIONS) {
            throw new ValidationException("demoModifications", "at most " + MAX_DEMO_MODIFICATIONS + " demonstration modifications");
        }
        AreaGraph base = new AreaGraph(baseEdges, coords);
        updEdges = applyDemo(updEdges, mods, new AreaGraph(updEdges, coords), coords, demoTouched);
        AreaGraph upd = new AreaGraph(updEdges, coords);
        if (!mods.isEmpty()) {
            warnings.add(mods.size() + " DEMONSTRATION modification(s) were applied to the updated snapshot. They simulate "
                    + "tampering with a dataset file; they are not real edits and not a real attack.");
        }

        // probe endpoints: nearest junction present in BOTH snapshots so before/after compare the same places
        Snap from = snapCommon(base, upd, req.fromLat(), req.fromLon(), "from");
        Snap to = snapCommon(base, upd, req.toLat(), req.toLon(), "to");
        if (from.osmNodeId() == to.osmNodeId()) {
            throw new ValidationException("to", "both probe points snap to the same junction; choose points further apart");
        }

        Map<Long, WayInfo> baseWays = ways(base);
        Map<Long, WayInfo> updWays = ways(upd);

        // Bloom filter: trusted fingerprints of every baseline way, used to screen every updated way
        BloomFilter bloom = BloomFilter.optimal(Math.max(1, baseWays.size()), 0.01, BloomFilter.DEFAULT_SEED);
        Set<String> exact = new HashSet<>();
        for (WayInfo w : baseWays.values()) {
            bloom.add(w.fingerprint());
            exact.add(w.fingerprint());
        }
        int definitelyNew = 0;
        int possiblyKnown = 0;
        int confirmed = 0;
        int falsePos = 0;
        for (WayInfo w : updWays.values()) {
            if (!bloom.mightContain(w.fingerprint())) {
                definitelyNew++;
            } else {
                possiblyKnown++;
                if (exact.contains(w.fingerprint())) {
                    confirmed++;
                } else {
                    falsePos++;
                }
            }
        }
        BloomScreen bloomScreen = new BloomScreen(baseWays.size(), updWays.size(), definitelyNew, possiblyKnown, confirmed,
                falsePos, bloom.bitCount(), bloom.hashCount(), bloom.theoreticalFalsePositiveProbability(),
                "Each baseline way is stored as a fingerprint (way id, class, one-way, length to 10 m). 'Definitely new' needs no "
                        + "lookup; every 'possibly known' answer is checked against the exact HashSet, so a false positive can never "
                        + "hide a change. False positives happen because different fingerprints can set the same bits.");

        // side summaries (before / after)
        ProbeRoute baseRoute = probe(base, from, to);
        ProbeRoute updRoute = probe(upd, from, to);
        SideSummary before = new SideSummary(info(baselineNet, "TRUSTED BASELINE", baselineFileSha, base, baseWays.size(),
                "REAL_MAPPED"), connectivity(base, -1), baseRoute, flow(base, from, to));
        SideSummary after = new SideSummary(info(mumbai.network(), mods.isEmpty() ? "UPDATED" : "UPDATED + DEMONSTRATION",
                updatedFileSha, upd, updWays.size(), mods.isEmpty() ? "REAL_MAPPED" : "REAL_MAPPED + SAMPLE"),
                connectivity(upd, -1), updRoute, flow(upd, from, to));

        // diff
        Set<Long> baseRouteWays = new HashSet<>(baseRoute.wayIds());
        Set<Long> updRouteWays = new HashSet<>(updRoute.wayIds());
        Set<String> reviewed = req.reviewedLegitimate() == null ? Set.of() : new HashSet<>(req.reviewedLegitimate());
        TreeSet<Long> all = new TreeSet<>(baseWays.keySet());
        all.addAll(updWays.keySet());
        List<long[]> pending = new ArrayList<>();
        for (long id : all) {
            WayInfo b = baseWays.get(id);
            WayInfo a = updWays.get(id);
            if (b == null || a == null || !b.fingerprint().equals(a.fingerprint()) || !b.name().equals(a.name())) {
                pending.add(new long[] {id});
            }
        }
        if (pending.size() > MAX_CHANGES) {
            throw new ValidationException("area", "the area has " + pending.size() + " changed ways (limit " + MAX_CHANGES
                    + "); select a smaller area");
        }
        int[] baseLargest = largestRoots(base);
        int[] updLargest = largestRoots(upd);
        List<Change> changes = new ArrayList<>();
        for (long[] p : pending) {
            long id = p[0];
            WayInfo b = baseWays.get(id);
            WayInfo a = updWays.get(id);
            List<String> kinds = new ArrayList<>();
            int disconnected;
            if (a == null) {
                kinds.add("REMOVED");
                disconnected = cutOff(base, id, baseLargest);
            } else if (b == null) {
                kinds.add("ADDED");
                disconnected = cutOff(upd, id, updLargest);
            } else {
                if (b.oneWay() != a.oneWay() || !sameDirection(base, upd, id)) {
                    kinds.add("ONE_WAY_CHANGED");
                }
                if (!b.roadClass().equals(a.roadClass())) {
                    kinds.add("CLASS_CHANGED");
                }
                if (Math.abs(b.lengthM() - a.lengthM()) > 10) {
                    kinds.add("GEOMETRY_CHANGED");
                }
                if (!b.name().equals(a.name())) {
                    kinds.add("NAME_CHANGED");
                }
                if (kinds.isEmpty()) {
                    kinds.add("ATTRIBUTE_CHANGED");
                }
                disconnected = 0;
            }
            boolean demo = demoTouched.contains(id);
            boolean onRoute = baseRouteWays.contains(id) || updRouteWays.contains(id);
            WayInfo ref = a != null ? a : b;
            String cls = ref.roadClass();
            List<String> why = new ArrayList<>();
            int score = 0;
            int cp = classPoints(cls);
            if (cp > 0) {
                score += cp;
                why.add("+" + cp + " road class " + cls);
            }
            int np = disconnected == 0 ? 0 : disconnected < 5 ? 2 : disconnected < 20 ? 4 : 6;
            if (np > 0) {
                score += np;
                why.add("+" + np + " " + disconnected + " junction(s) " + (kinds.contains("ADDED") ? "joined to" : "cut off from")
                        + " the main network");
            }
            if (onRoute) {
                score += 3;
                why.add("+3 touches the probe route");
            }
            if (kinds.contains("ONE_WAY_CHANGED")) {
                int op = cp >= 2 ? 2 : 1;
                score += op;
                why.add("+" + op + " one-way rule changed");
            }
            if (demo) {
                score += 3;
                why.add("+3 no public edit record (demonstration file modification)");
            }
            String cid = id < 0 ? "D" + (-id) : "W" + id;
            String status;
            if (demo) {
                status = "SIMULATED_TAMPERING";
            } else if (reviewed.contains(cid)) {
                status = "LEGITIMATE_REVIEWED";
            } else if (score >= SUSPICIOUS_THRESHOLD) {
                status = "SUSPICIOUS_NEEDS_REVIEW";
            } else {
                status = "ROUTINE_UNREVIEWED";
            }
            changes.add(new Change(cid, id, kinds, demo ? "DEMONSTRATION" : "REAL_OSM_EDIT", status, cls, ref.name(),
                    describe(b), describe(a), ref.lat(), ref.lon(), ref.geometry(), disconnected, onRoute, score, why,
                    id > 0 ? "https://www.openstreetmap.org/way/" + id + "/history" : null));
        }
        for (String r : reviewed) {
            if (r.startsWith("D")) {
                warnings.add("Demonstration change " + r + " cannot be marked legitimate; it stays SIMULATED_TAMPERING.");
            }
        }

        StatusCounts counts = counts(changes);
        Ranking ranking = rank(changes, req.topK() == null ? 10 : req.topK());
        AlertIndex alerts = index(changes);
        RiskStrips strips = strips(area, changes, req.stripFrom(), req.stripTo());

        TourResponse inspection = null;
        String inspectionNote;
        boolean plan = req.planInspection() == null || req.planInspection();
        if (plan) {
            Map<String, Change> byId = new HashMap<>();
            changes.forEach(c -> byId.put(c.id(), c));
            List<TourService.Stop> stops = new ArrayList<>();
            stops.add(new TourService.Stop("Start (probe origin)", from.lat(), from.lon()));
            for (String id : ranking.topIds()) {
                Change c = byId.get(id);
                if (stops.size() < 9 && !c.status().equals("ROUTINE_UNREVIEWED")) {
                    stops.add(new TourService.Stop(c.id(), c.lat(), c.lon()));
                }
            }
            if (stops.size() >= 3) {
                try {
                    inspection = tours.plan(new TourService.TourRequest(stops, 0));
                    inspectionNote = "Inspection order for the highest-ranked flagged changes (metric-TSP approximation on "
                            + "shortest-path road distances of the current snapshot). A planning aid, not live navigation.";
                } catch (ValidationException ex) {
                    inspectionNote = "No inspection route: " + ex.getMessage();
                }
            } else {
                inspectionNote = "No inspection route: fewer than two flagged changes in the top-" + ranking.k() + ".";
            }
        } else {
            inspectionNote = "Inspection route not requested.";
        }

        changes.sort(Comparator.comparingInt((Change c) -> c.score()).reversed().thenComparing(Change::id));
        int total = changes.size();
        boolean truncated = total > MAX_CHANGES_RETURNED;
        List<Change> returned = truncated ? new ArrayList<>(changes.subList(0, MAX_CHANGES_RETURNED)) : changes;
        if (truncated) {
            warnings.add("Showing the " + MAX_CHANGES_RETURNED + " highest-scoring of " + total + " changes.");
        }
        if (from.snapDistanceM() > 300 || to.snapDistanceM() > 300) {
            warnings.add("A probe point is more than 300 m from a junction present in both snapshots; the snapped junction is used.");
        }

        Explanation ex = new Explanation(List.of(
                new DataItem("Trusted baseline: OpenStreetMap arterial roads as of " + text(baselineNet.provenance(), "osmDataTimestamp"), DataClass.REAL_MAPPED),
                new DataItem("Updated snapshot: OpenStreetMap arterial roads as of " + text(mumbai.network().provenance(), "osmDataTimestamp"), DataClass.REAL_MAPPED),
                new DataItem("Change records, impact and scores computed from the two snapshots", DataClass.DERIVED_RESULT),
                new DataItem(mods.isEmpty() ? "No demonstration modifications" : mods.size() + " demonstration modification(s) typed in by the user",
                        mods.isEmpty() ? DataClass.NOT_AVAILABLE : DataClass.SAMPLE)),
                "Way-by-way comparison of the two snapshots; DSU connectivity before and after each change; Dijkstra probe route "
                        + "with a Fibonacci heap and a PriorityQueue; road-disjoint routes with Edmonds-Karp and Ford-Fulkerson; "
                        + "ranking with a documented points rule, Quickselect top-k, a red-black alert index and a segment tree of "
                        + "risk by west-to-east strip.",
                List.of("Which mapped connections differ between the two snapshots, and which of them change connectivity or the probe route.",
                        "Which differences a reviewer should look at first under the stated scoring rule."),
                List.of("Whether any change is malicious: real OSM edits are public, and a high score only means 'worth checking'.",
                        "Real-world road conditions: OSM reflects what mappers recorded, not what is on the ground today.",
                        "Effects on residential streets, which are not in either snapshot."));

        return new IntegrityResponse(area, from, to, before, after, total, truncated, returned, counts, bloomScreen, ranking,
                alerts, strips, inspection, inspectionNote, algorithmSteps(base, upd, total), SCORING_RULE,
                SUSPICIOUS_THRESHOLD, warnings, ex, VERIFIED_MALICIOUS_NOTE);
    }

    /**
     * Reproducible demonstration scenario for an area and probe: remove the longest way on the current probe route, flip
     * the direction rule of the longest two-way primary/secondary way off the route, and add an unrecorded connection
     * between the junctions nearest to 25 % and 75 % of the straight line between the probe points.
     */
    public ScenarioResponse scenario(Area requested, double fromLat, double fromLon, double toLat, double toLon) {
        Area area = mumbai.checkArea(requested);
        Map<Long, double[]> coords = new HashMap<>();
        List<Edge> baseEdges = extract(baselineNet, area, coords);
        List<Edge> updEdges = extract(mumbai.network(), area, coords);
        if (baseEdges.isEmpty() || updEdges.isEmpty()) {
            throw new ValidationException("area", "the selected area contains no road segments in one of the snapshots");
        }
        AreaGraph base = new AreaGraph(baseEdges, coords);
        AreaGraph upd = new AreaGraph(updEdges, coords);
        Snap from = snapCommon(base, upd, fromLat, fromLon, "from");
        Snap to = snapCommon(base, upd, toLat, toLon, "to");
        ProbeRoute route = probe(upd, from, to);
        Map<Long, WayInfo> ways = ways(upd);
        List<DemoModification> mods = new ArrayList<>();
        List<String> text = new ArrayList<>();
        Set<Long> onRoute = new HashSet<>(route.wayIds());
        onRoute.stream().map(ways::get).filter(w -> w != null && w.wayId() > 0)
                .max(Comparator.comparingDouble(WayInfo::lengthM).thenComparing(WayInfo::wayId)).ifPresent(w -> {
                    mods.add(new DemoModification("REMOVE_WAY", w.wayId(), null, null, null, null));
                    text.add("Remove way " + w.wayId() + " (" + label(w) + "), the longest road on the probe route.");
                });
        ways.values().stream().filter(w -> !w.oneWay() && !onRoute.contains(w.wayId())
                        && (w.roadClass().equals("primary") || w.roadClass().equals("secondary")))
                .max(Comparator.comparingDouble(WayInfo::lengthM).thenComparing(WayInfo::wayId)).ifPresent(w -> {
                    mods.add(new DemoModification("FLIP_ONEWAY", w.wayId(), null, null, null, null));
                    text.add("Make way " + w.wayId() + " (" + label(w) + ") one-way.");
                });
        double aLat = from.lat() + 0.25 * (to.lat() - from.lat());
        double aLon = from.lon() + 0.25 * (to.lon() - from.lon());
        double bLat = from.lat() + 0.75 * (to.lat() - from.lat());
        double bLon = from.lon() + 0.75 * (to.lon() - from.lon());
        mods.add(new DemoModification("ADD_CONNECTION", null, round5(aLat), round5(aLon), round5(bLat), round5(bLon)));
        text.add("Add an unrecorded straight connection between the junctions nearest (" + round5(aLat) + ", " + round5(aLon)
                + ") and (" + round5(bLat) + ", " + round5(bLon) + ").");
        return new ScenarioResponse("S1", "DEMONSTRATION - synthetic tampering, not a real attack", mods, text);
    }

    // ------------------------------------------------------------------ steps

    private static List<Edge> extract(MumbaiNetwork net, Area a, Map<Long, double[]> coords) {
        RoadGraph g = net.graph();
        List<Edge> out = new ArrayList<>();
        for (int e = 0; e < g.segmentCount(); e++) {
            int u = g.segU(e);
            int v = g.segV(e);
            if (inside(net, a, u) && inside(net, a, v)) {
                coords.putIfAbsent(net.osmNodeId(u), new double[] {net.lat(u), net.lon(u)});
                coords.putIfAbsent(net.osmNodeId(v), new double[] {net.lat(v), net.lon(v)});
                out.add(new Edge(net.wayId(e), net.osmNodeId(u), net.osmNodeId(v), g.oneWay(e), g.lengthM(e), net.roadClass(e),
                        net.name(e), net.geometry(e)));
            }
        }
        return out;
    }

    private static boolean inside(MumbaiNetwork net, Area a, int n) {
        return net.lat(n) >= a.south() && net.lat(n) <= a.north() && net.lon(n) >= a.west() && net.lon(n) <= a.east();
    }

    private List<Edge> applyDemo(List<Edge> edges, List<DemoModification> mods, AreaGraph current, Map<Long, double[]> coords,
                                 Set<Long> touched) {
        List<Edge> out = new ArrayList<>(edges);
        int added = 0;
        for (int i = 0; i < mods.size(); i++) {
            DemoModification m = mods.get(i);
            String field = "demoModifications[" + i + "]";
            String type = m == null || m.type() == null ? "" : m.type();
            switch (type) {
                case "REMOVE_WAY", "FLIP_ONEWAY" -> {
                    if (m.wayId() == null || !current.edgesOfWay.containsKey(m.wayId()) || m.wayId() < 0) {
                        throw new ValidationException(field, "wayId must be a road in the updated snapshot inside the area");
                    }
                    long w = m.wayId();
                    touched.add(w);
                    if (type.equals("REMOVE_WAY")) {
                        out.removeIf(e -> e.wayId() == w);
                    } else {
                        boolean wasOneWay = out.stream().anyMatch(e -> e.wayId() == w && e.oneWay());
                        out.replaceAll(e -> e.wayId() != w ? e : wasOneWay
                                ? new Edge(w, e.toOsm(), e.fromOsm(), true, e.lengthM(), e.roadClass(), e.name(), reversed(e.geom()))
                                : new Edge(w, e.fromOsm(), e.toOsm(), true, e.lengthM(), e.roadClass(), e.name(), e.geom()));
                    }
                }
                case "ADD_CONNECTION" -> {
                    if (m.fromLat() == null || m.fromLon() == null || m.toLat() == null || m.toLon() == null) {
                        throw new ValidationException(field, "ADD_CONNECTION needs fromLat, fromLon, toLat and toLon");
                    }
                    int a = nearest(current, m.fromLat(), m.fromLon());
                    int b = nearest(current, m.toLat(), m.toLon());
                    if (a == b) {
                        throw new ValidationException(field, "both ends snap to the same junction; choose points further apart");
                    }
                    double[] ca = current.coord.get(a);
                    double[] cb = current.coord.get(b);
                    long id = -(++added);
                    touched.add(id);
                    out.add(new Edge(id, current.osm.get(a), current.osm.get(b), false, haversine(ca[0], ca[1], cb[0], cb[1]),
                            "primary", "DEMO: unrecorded connection", new double[] {ca[0], ca[1], cb[0], cb[1]}));
                }
                default -> throw new ValidationException(field, "type must be REMOVE_WAY, FLIP_ONEWAY or ADD_CONNECTION");
            }
        }
        return out;
    }

    private static Map<Long, WayInfo> ways(AreaGraph ag) {
        Map<Long, WayInfo> out = new LinkedHashMap<>();
        for (Map.Entry<Long, List<Integer>> en : ag.edgesOfWay.entrySet()) {
            List<Integer> idx = en.getValue();
            Edge first = ag.edges.get(idx.get(0));
            double len = 0;
            double lat = 0;
            double lon = 0;
            boolean oneWay = false;
            List<double[]> geom = new ArrayList<>();
            for (int i : idx) {
                Edge e = ag.edges.get(i);
                len += e.lengthM();
                oneWay |= e.oneWay();
                double[] g = e.geom();
                lat += (g[0] + g[g.length - 2]) / 2;
                lon += (g[1] + g[g.length - 1]) / 2;
                geom.add(g);
            }
            String fp = en.getKey() + "|" + first.roadClass() + "|" + (oneWay ? 1 : 0) + "|" + Math.round(len / 10);
            out.put(en.getKey(), new WayInfo(en.getKey(), first.roadClass(), first.name(), oneWay, len, lat / idx.size(),
                    lon / idx.size(), geom, fp));
        }
        return out;
    }

    /** True when every one-way segment of the way keeps its direction (only meaningful when both are one-way). */
    private static boolean sameDirection(AreaGraph base, AreaGraph upd, long wayId) {
        Set<String> b = new HashSet<>();
        for (int i : base.edgesOfWay.get(wayId)) {
            Edge e = base.edges.get(i);
            if (e.oneWay()) {
                b.add(e.fromOsm() + ">" + e.toOsm());
            }
        }
        for (int i : upd.edgesOfWay.get(wayId)) {
            Edge e = upd.edges.get(i);
            if (e.oneWay() && b.contains(e.toOsm() + ">" + e.fromOsm())) {
                return false;
            }
        }
        return true;
    }

    static DisjointSetUnion dsu(AreaGraph ag, long skipWay) {
        DisjointSetUnion d = new DisjointSetUnion(ag.nodeCount());
        for (int i = 0; i < ag.edges.size(); i++) {
            if (ag.edges.get(i).wayId() != skipWay) {
                d.union(ag.g.segU(i), ag.g.segV(i));
            }
        }
        return d;
    }

    static ConnectivityResult connectivity(AreaGraph ag, long skipWay) {
        DisjointSetUnion d = dsu(ag, skipWay);
        int largest = 0;
        for (int i = 0; i < ag.nodeCount(); i++) {
            largest = Math.max(largest, d.componentSize(i));
        }
        return new ConnectivityResult(d.componentCount(), largest, round3((double) largest / Math.max(1, ag.nodeCount())));
    }

    /** largest[i] = 1 if node i is in the largest weak component of the full area graph. */
    static int[] largestRoots(AreaGraph ag) {
        DisjointSetUnion d = dsu(ag, Long.MIN_VALUE);
        int best = 0;
        for (int i = 1; i < ag.nodeCount(); i++) {
            if (d.componentSize(i) > d.componentSize(best)) {
                best = i;
            }
        }
        int root = d.find(best);
        int[] out = new int[ag.nodeCount()];
        for (int i = 0; i < ag.nodeCount(); i++) {
            out[i] = d.find(i) == root ? 1 : 0;
        }
        return out;
    }

    /**
     * Junctions that are in the main (largest) component with the way present but not without it, ignoring junctions
     * that only this way touches (they disappear with the road itself). DSU is rebuilt without the way because DSU
     * does not support deleting a union.
     */
    static int cutOff(AreaGraph ag, long wayId, int[] inLargest) {
        DisjointSetUnion d = dsu(ag, wayId);
        boolean[] otherEdge = new boolean[ag.nodeCount()];
        for (int i = 0; i < ag.edges.size(); i++) {
            if (ag.edges.get(i).wayId() != wayId) {
                otherEdge[ag.g.segU(i)] = true;
                otherEdge[ag.g.segV(i)] = true;
            }
        }
        int best = -1;
        for (int i = 0; i < ag.nodeCount(); i++) {
            if (inLargest[i] == 1 && otherEdge[i] && (best < 0 || d.componentSize(i) > d.componentSize(best))) {
                best = i;
            }
        }
        if (best < 0) {
            return 0;
        }
        int root = d.find(best);
        int lost = 0;
        for (int i = 0; i < ag.nodeCount(); i++) {
            if (inLargest[i] == 1 && otherEdge[i] && d.find(i) != root) {
                lost++;
            }
        }
        return lost;
    }

    private Snap snapCommon(AreaGraph base, AreaGraph upd, double lat, double lon, String field) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
            throw new ValidationException(field, "coordinates must be finite numbers");
        }
        long best = -1;
        double bestD = Double.POSITIVE_INFINITY;
        for (int i = 0; i < base.nodeCount(); i++) {
            long id = base.osm.get(i);
            if (!upd.index.containsKey(id)) {
                continue;
            }
            double[] c = base.coord.get(i);
            double d = haversine(lat, lon, c[0], c[1]);
            if (d < bestD) {
                bestD = d;
                best = id;
            }
        }
        if (best < 0 || bestD > 3000) {
            throw new ValidationException(field, "no junction present in both snapshots within 3 km of the point");
        }
        double[] c = base.coord.get(base.index.get(best));
        return new Snap(base.index.get(best), best, c[0], c[1], round1(bestD));
    }

    private static int nearest(AreaGraph ag, double lat, double lon) {
        int best = 0;
        double bestD = Double.POSITIVE_INFINITY;
        for (int i = 0; i < ag.nodeCount(); i++) {
            double[] c = ag.coord.get(i);
            double d = haversine(lat, lon, c[0], c[1]);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static ProbeRoute probe(AreaGraph ag, Snap from, Snap to) {
        int s = ag.index.get(from.osmNodeId());
        int t = ag.index.get(to.osmNodeId());
        Dijkstra.Result fib = Dijkstra.run(ag.g, s, t, RoadGraph.Metric.DISTANCE, Dijkstra.Queue.FIBONACCI);
        Dijkstra.Result bin = Dijkstra.run(ag.g, s, t, RoadGraph.Metric.DISTANCE, Dijkstra.Queue.BINARY);
        boolean reachable = !Double.isInfinite(fib.dist()[t]);
        List<Integer> segs = reachable ? fib.segmentsTo(ag.g, t) : List.of();
        List<Long> ways = new ArrayList<>();
        List<double[]> geometry = new ArrayList<>();
        for (int e : segs) {
            Edge ed = ag.edges.get(e);
            if (ways.isEmpty() || ways.get(ways.size() - 1) != ed.wayId()) {
                ways.add(ed.wayId());
            }
            geometry.add(ed.geom());
        }
        boolean agree = Math.abs(fib.dist()[t] - bin.dist()[t]) < 1e-6 || (Double.isInfinite(fib.dist()[t]) && Double.isInfinite(bin.dist()[t]));
        return new ProbeRoute(reachable, reachable ? round1(fib.dist()[t]) : -1, segs.size(), ways, geometry, qs(fib), qs(bin), agree);
    }

    private static FlowResult flow(AreaGraph ag, Snap from, Snap to) {
        FlowNetwork fn = new FlowNetwork(ag.nodeCount());
        List<Integer> arcEdge = new ArrayList<>();
        for (int i = 0; i < ag.edges.size(); i++) {
            int u = ag.g.segU(i);
            int v = ag.g.segV(i);
            fn.addEdge(u, v, 1);
            arcEdge.add(i);
            if (!ag.g.oneWay(i)) {
                fn.addEdge(v, u, 1);
                arcEdge.add(i);
            }
        }
        int s = ag.index.get(from.osmNodeId());
        int t = ag.index.get(to.osmNodeId());
        MaxFlowResult ff = FordFulkerson.run(fn, s, t, TraceOptions.NONE);
        MaxFlowResult ek = EdmondsKarp.run(fn, s, t, TraceOptions.NONE);
        MinCut.Result cut = MinCut.fromResidual(fn, s);
        List<CutLink> links = new ArrayList<>();
        for (int fe : cut.cutEdges()) {
            Edge e = ag.edges.get(arcEdge.get(fe));
            links.add(new CutLink(e.wayId(), e.name(), e.roadClass(), e.geom()));
        }
        return new FlowResult(ek.maxFlow(), ff.maxFlow(), cut.cutCapacity(), cut.cutCapacity() == ek.maxFlow(),
                round3(ek.elapsedMillis()), round3(ff.elapsedMillis()), links);
    }

    private SnapshotInfo info(MumbaiNetwork net, String role, String fileSha, AreaGraph ag, int ways, String dataClass) {
        List<String> canon = new ArrayList<>();
        for (Edge e : ag.edges) {
            canon.add(e.wayId() + ":" + e.fromOsm() + ":" + e.toOsm() + ":" + (e.oneWay() ? 1 : 0) + ":" + e.roadClass());
        }
        canon.sort(null);
        return new SnapshotInfo(role, text(net.provenance(), "osmDataTimestamp"), text(net.provenance(), "retrievedAt"), fileSha,
                sha256(String.join("\n", canon).getBytes(StandardCharsets.UTF_8)), ways, ag.edges.size(), ag.nodeCount(), dataClass);
    }

    private static StatusCounts counts(List<Change> changes) {
        int leg = 0;
        int rou = 0;
        int sus = 0;
        int sim = 0;
        for (Change c : changes) {
            switch (c.status()) {
                case "LEGITIMATE_REVIEWED" -> leg++;
                case "ROUTINE_UNREVIEWED" -> rou++;
                case "SUSPICIOUS_NEEDS_REVIEW" -> sus++;
                default -> sim++;
            }
        }
        return new StatusCounts(leg, rou, sus, sim, 0);
    }

    /** Top-k by score among changes not reviewed as legitimate, via randomized Quickselect; checked against a full sort. */
    static Ranking rank(List<Change> changes, int k) {
        if (k < 1 || k > 100) {
            throw new ValidationException("topK", "topK must be between 1 and 100");
        }
        List<Change> active = changes.stream().filter(c -> !c.status().equals("LEGITIMATE_REVIEWED"))
                .sorted(Comparator.comparing(Change::id)).toList();
        if (active.isEmpty()) {
            return new Ranking(k, 0, List.of(), 0, 0, true, "No unreviewed changes to rank.");
        }
        int kk = Math.min(k, active.size());
        double[] scores = active.stream().mapToDouble(Change::score).toArray();
        RandomizedQuickselect.Selection sel = RandomizedQuickselect.select(scores, scores.length - kk, SEED);
        int kth = (int) sel.value();
        List<String> top = new ArrayList<>();
        active.stream().filter(c -> c.score() > kth).sorted(Comparator.comparingInt((Change c) -> c.score()).reversed()
                .thenComparing(Change::id)).forEach(c -> top.add(c.id()));
        for (Change c : active) {
            if (top.size() >= kk) {
                break;
            }
            if (c.score() == kth) {
                top.add(c.id());
            }
        }
        double[] sorted = scores.clone();
        Arrays.sort(sorted);
        boolean matches = (int) sorted[sorted.length - kk] == kth;
        return new Ranking(kk, active.size(), top, kth, sel.comparisons(), matches,
                "Quickselect finds the k-th highest score in expected O(n) without sorting all changes; ties at the k-th score are "
                        + "broken by change id. The result is checked against a full sort.");
    }

    /** Red-black tree keyed by (score descending, change id); reviewed-legitimate changes are deleted from the index. */
    static AlertIndex index(List<Change> changes) {
        RedBlackTree<PriorityKey, Change> tree = new RedBlackTree<>();
        for (Change c : changes) {
            tree.put(new PriorityKey(c.score(), c.id()), c);
        }
        int removed = 0;
        for (Change c : changes) {
            if (c.status().equals("LEGITIMATE_REVIEWED") && tree.remove(new PriorityKey(c.score(), c.id()))) {
                removed++;
            }
        }
        boolean ok;
        try {
            tree.validate();
            ok = true;
        } catch (IllegalStateException ex) {
            ok = false;
        }
        List<String> order = new ArrayList<>();
        tree.forEachInOrder((key, c) -> {
            if (order.size() < 50) {
                order.add(c.id() + " (" + c.score() + ")");
            }
        });
        return new AlertIndex(tree.size(), tree.height(), tree.blackHeight(), tree.rotationCount(), ok, removed, order,
                "(score descending, change id) - highest-priority alert first; ties have a deterministic order");
    }

    /** Segment tree over west-to-east strips; reviewed changes are applied as point updates (risk removed). */
    static RiskStrips strips(Area area, List<Change> changes, Integer qFrom, Integer qTo) {
        long[] risk = new long[STRIPS];
        double width = (area.east() - area.west()) / STRIPS;
        for (Change c : changes) {
            risk[strip(area, c, width)] += c.score();
        }
        SegmentTree st = new SegmentTree(risk);
        int updates = 0;
        for (Change c : changes) {
            if (c.status().equals("LEGITIMATE_REVIEWED") && c.score() > 0) {
                int s = strip(area, c, width);
                risk[s] -= c.score();
                st.pointSet(s, risk[s]);
                updates++;
            }
        }
        int from = qFrom == null ? 0 : qFrom;
        int to = qTo == null ? STRIPS - 1 : qTo;
        if (from < 0 || to >= STRIPS || from > to) {
            throw new ValidationException("stripFrom", "strip range must satisfy 0 <= stripFrom <= stripTo < " + STRIPS);
        }
        long sum = st.rangeSum(from, to);
        long brute = 0;
        for (int i = from; i <= to; i++) {
            brute += risk[i];
        }
        SegmentTree.MaxResult hot = st.rangeMax(0, STRIPS - 1);
        List<Double> west = new ArrayList<>();
        List<Long> r = new ArrayList<>();
        for (int i = 0; i < STRIPS; i++) {
            west.add(round5(area.west() + i * width));
            r.add(risk[i]);
        }
        return new RiskStrips(STRIPS, west, r, from, to, sum, sum == brute, hot.index(), hot.value(), updates, st.nodesVisited(),
                "Risk of a strip = sum of scores of changes whose centre lies in it. Range sum and range max are O(log n) per "
                        + "query; marking a change legitimate is an O(log n) point update.");
    }

    private static int strip(Area area, Change c, double width) {
        int s = (int) Math.floor((c.lon() - area.west()) / width);
        return Math.max(0, Math.min(STRIPS - 1, s));
    }

    private static List<AlgorithmStep> algorithmSteps(AreaGraph base, AreaGraph upd, int changes) {
        int n = Math.max(base.nodeCount(), upd.nodeCount());
        int m = Math.max(base.edges.size(), upd.edges.size());
        return List.of(
                new AlgorithmStep("Bloom filter", "fingerprints of baseline ways", "definitely-new vs possibly-known screening",
                        "O(k) per add or query", "False positives possible; always confirmed by an exact HashSet; no deletion."),
                new AlgorithmStep("Disjoint Set Union", n + " junctions, " + m + " segments per snapshot",
                        "weak components; junctions cut off by each change", "O(m alpha(n)) per rebuild; one rebuild per change",
                        "Ignores one-way rules (weak connectivity); cannot delete, so it is rebuilt without the changed way."),
                new AlgorithmStep("Dijkstra + Fibonacci heap (vs PriorityQueue)", "probe origin and destination, length weights",
                        "shortest probe route before and after", "O(m + n log n) with a Fibonacci heap; O(m log n) binary heap",
                        "Distance only; no live traffic. The PriorityQueue is usually faster in practice (measured)."),
                new AlgorithmStep("Edmonds-Karp / Ford-Fulkerson", "unit capacity per directed segment", "number of road-disjoint routes",
                        "EK O(V E^2); FF O(E f) with f = max flow", "Structural count, not vehicles per hour."),
                new AlgorithmStep("Minimum cut", "residual graph after max flow", "smallest set of segments separating the probe points",
                        "O(V + E) after max flow", "Only inside the selected area; detours outside it are ignored."),
                new AlgorithmStep("Randomized Quickselect", changes + " change scores", "k-th highest score -> top-k list",
                        "expected O(n), worst O(n^2)", "Order inside the top-k comes from a small sort of k items."),
                new AlgorithmStep("Red-black tree", "change records keyed by (score desc, id)", "ordered alert index with deletions",
                        "O(log n) insert, delete, search", "Ordering is only as meaningful as the scoring rule."),
                new AlgorithmStep("Segment tree", STRIPS + " west-to-east strips of risk", "range sum / max, point updates on review",
                        "O(log n) query and update", "Strips are a simple spatial bucketing, not administrative wards."),
                new AlgorithmStep("Metric-TSP approximation", "probe origin + top flagged changes", "inspection visiting order",
                        "MST + preorder O(n^2) on the distance matrix", "At most 2x optimal on a metric; not live navigation."));
    }

    // ------------------------------------------------------------------ helpers

    private static int classPoints(String cls) {
        return switch (cls) {
            case "motorway", "trunk" -> 3;
            case "primary" -> 2;
            case "secondary", "motorway_link", "trunk_link" -> 1;
            default -> 0;
        };
    }

    private static String describe(WayInfo w) {
        if (w == null) {
            return "absent";
        }
        return w.roadClass() + ", " + (w.oneWay() ? "one-way" : "two-way") + ", " + Math.round(w.lengthM()) + " m"
                + (w.name().isEmpty() ? "" : ", '" + w.name() + "'");
    }

    private static String label(WayInfo w) {
        return (w.name().isEmpty() ? "unnamed" : w.name()) + ", " + w.roadClass();
    }

    private static QueueStats qs(Dijkstra.Result r) {
        return new QueueStats(r.queue().name(), r.settled(), r.relaxations(), r.heapOperations(), round3(r.elapsedMillis()));
    }

    private static double[] reversed(double[] g) {
        double[] out = new double[g.length];
        for (int i = 0; i < g.length; i += 2) {
            out[g.length - 2 - i] = g[i];
            out[g.length - 1 - i] = g[i + 1];
        }
        return out;
    }

    static double haversine(double aLat, double aLon, double bLat, double bLon) {
        double r = 6371008.8;
        double dLat = Math.toRadians(bLat - aLat);
        double dLon = Math.toRadians(bLon - aLon);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }

    private static String text(JsonNode n, String field) {
        return n == null || n.get(field) == null || n.get(field).isNull() ? null : n.get(field).asText();
    }

    private static byte[] read(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static double round5(double v) {
        return Math.round(v * 100000) / 100000.0;
    }
}
