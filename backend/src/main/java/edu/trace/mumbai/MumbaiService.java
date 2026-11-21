package edu.trace.mumbai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.trace.algorithms.dsu.DisjointSetUnion;
import edu.trace.algorithms.flow.EdmondsKarp;
import edu.trace.algorithms.flow.FlowNetwork;
import edu.trace.algorithms.flow.FordFulkerson;
import edu.trace.algorithms.flow.MaxFlowResult;
import edu.trace.algorithms.flow.MinCut;
import edu.trace.algorithms.flow.TraceOptions;
import edu.trace.algorithms.graph.Dijkstra;
import edu.trace.algorithms.graph.RoadGraph;
import edu.trace.algorithms.select.RandomizedQuickselect;
import edu.trace.model.ValidationException;
import edu.trace.mumbai.MumbaiDtos.Area;
import edu.trace.mumbai.MumbaiDtos.AttributeCoverage;
import edu.trace.mumbai.MumbaiDtos.ClassCount;
import edu.trace.mumbai.MumbaiDtos.Component;
import edu.trace.mumbai.MumbaiDtos.ComponentSummary;
import edu.trace.mumbai.MumbaiDtos.ComponentsRequest;
import edu.trace.mumbai.MumbaiDtos.ComponentsResponse;
import edu.trace.mumbai.MumbaiDtos.CriticalLinksRequest;
import edu.trace.mumbai.MumbaiDtos.CriticalLinksResponse;
import edu.trace.mumbai.MumbaiDtos.CutSegment;
import edu.trace.mumbai.MumbaiDtos.DataClass;
import edu.trace.mumbai.MumbaiDtos.DataItem;
import edu.trace.mumbai.MumbaiDtos.DataSource;
import edu.trace.mumbai.MumbaiDtos.Explanation;
import edu.trace.mumbai.MumbaiDtos.FlowStats;
import edu.trace.mumbai.MumbaiDtos.GraphStats;
import edu.trace.mumbai.MumbaiDtos.LengthStatsResponse;
import edu.trace.mumbai.MumbaiDtos.NetworkInsight;
import edu.trace.mumbai.MumbaiDtos.NetworkResponse;
import edu.trace.mumbai.MumbaiDtos.PairConnectivity;
import edu.trace.mumbai.MumbaiDtos.PathPreview;
import edu.trace.mumbai.MumbaiDtos.PercentileValue;
import edu.trace.mumbai.MumbaiDtos.ProvenanceResponse;
import edu.trace.mumbai.MumbaiDtos.QueueStats;
import edu.trace.mumbai.MumbaiDtos.RoadStep;
import edu.trace.mumbai.MumbaiDtos.RouteRequest;
import edu.trace.mumbai.MumbaiDtos.RouteResponse;
import edu.trace.mumbai.MumbaiDtos.Segment;
import edu.trace.mumbai.MumbaiDtos.SegmentDetail;
import edu.trace.mumbai.MumbaiDtos.Snap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Analyses on the real Mumbai road graph: provenance, map data, routes, connectivity, critical links. */
@Service
public class MumbaiService {

    public static final String PRODUCT = "TRACE Algorithm Lab - Mumbai road graph (historical public OpenStreetMap dataset)";
    /** Study area of the snapshot (Greater Mumbai). */
    public static final Area STUDY_AREA = new Area(18.89, 72.77, 19.29, 72.99);
    public static final String LIVE_TRAFFIC_STATUS = "Live traffic unavailable. No free, documented, terms-verified traffic or "
            + "incident feed for Mumbai is connected. Enabling it would need a licensed provider (for example a TomTom, HERE or "
            + "Google traffic API key under their terms) or an official open feed; until then routes use real road length or a "
            + "free-flow speed estimate.";

    private final MumbaiNetwork net;
    private final int maxAreaSegments;
    private final int maxFlowSegments;
    private final AttributeCoverage coverage;
    private final NetworkInsight insight;

    public MumbaiService(ObjectMapper mapper,
                         @Value("${trace.max-area-segments:20000}") int maxAreaSegments,
                         @Value("${trace.max-flow-segments:6000}") int maxFlowSegments) throws IOException {
        try (InputStream in = new ClassPathResource("data/mumbai/roads.json").getInputStream()) {
            this.net = MumbaiNetwork.load(in, mapper);
        }
        this.maxAreaSegments = maxAreaSegments;
        this.maxFlowSegments = maxFlowSegments;
        this.coverage = computeCoverage();
        this.insight = computeInsight();
    }

    public MumbaiNetwork network() {
        return net;
    }

    // ---------------------------------------------------------------- provenance and whole-network facts

    public ProvenanceResponse provenance() {
        JsonNode p = net.provenance();
        RoadGraph g = net.graph();
        List<DataSource> sources = new ArrayList<>();
        String retrieved = text(p, "retrievedAt");
        sources.add(new DataSource("roads", text(p, "dataset"), DataClass.REAL_MAPPED, "snapshot", text(p, "source"),
                text(p, "sourceUrl"), text(p, "licence"), retrieved, text(p, "osmDataTimestamp"), ageDays(retrieved),
                freshness(retrieved), p.get("coverage").get("description").asText(), text(p, "notes")));
        sources.add(new DataSource("travel-time", "Segment travel-time estimate", DataClass.ESTIMATE, "derived on load",
                "TRACE lab: length / speed (OSM maxspeed tag, else road-class default)", null, null, retrieved, null, null,
                "recomputed from the road snapshot", "every road segment",
                "Only " + coverage.maxspeedTagPresentPct() + "% of segments carry a maxspeed tag; the rest use class defaults. "
                        + net.assumptions().get("travelTime").asText()));
        sources.add(new DataSource("capacity", "Lane-based capacity assumption", DataClass.ESTIMATE, "opt-in only",
                "TRACE lab: lanes per direction x per-lane rule of thumb", null, null, retrieved, null, null,
                "recomputed from the road snapshot", "every road segment",
                "Only " + coverage.lanesTagPresentPct() + "% of segments carry a lanes tag. Used only when the user selects the "
                        + "LANE_ASSUMPTION capacity model; never presented as measured throughput."));
        sources.add(new DataSource("live-traffic", "Live traffic speeds, incidents and closures", DataClass.NOT_AVAILABLE,
                "unavailable", null, null, null, null, null, null, "not connected", "Mumbai", LIVE_TRAFFIC_STATUS));
        sources.add(new DataSource("sample", "Algorithm Lab sample inputs", DataClass.SAMPLE, "static",
                "Hand-made examples in this repository", null, "MIT", null, null, null, "static", "not geographic",
                "Used only by the Algorithm Lab and unit tests."));
        Map<String, double[]> byClass = new LinkedHashMap<>();
        int oneWay = 0;
        double km = 0;
        for (int e = 0; e < g.segmentCount(); e++) {
            double[] c = byClass.computeIfAbsent(net.roadClass(e), k -> new double[2]);
            c[0]++;
            c[1] += g.lengthM(e) / 1000;
            km += g.lengthM(e) / 1000;
            if (g.oneWay(e)) {
                oneWay++;
            }
        }
        List<ClassCount> classes = new ArrayList<>();
        byClass.forEach((k, v) -> classes.add(new ClassCount(k, (int) v[0], round1(v[1]))));
        return new ProvenanceResponse(PRODUCT, STUDY_AREA, sources,
                new GraphStats(g.nodeCount(), g.segmentCount(), g.arcCount(), oneWay, round1(km), classes, coverage, insight),
                net.assumptions(), LIVE_TRAFFIC_STATUS);
    }

    private AttributeCoverage computeCoverage() {
        int m = net.graph().segmentCount();
        int noName = 0;
        int noLanes = 0;
        int noSpeed = 0;
        for (int e = 0; e < m; e++) {
            if (net.name(e).isBlank()) {
                noName++;
            }
            if (net.lanesTag(e) <= 0) {
                noLanes++;
            }
            if (net.maxspeedTag(e) <= 0) {
                noSpeed++;
            }
        }
        return new AttributeCoverage(pct(m - noName, m), pct(m - noLanes, m), pct(m - noSpeed, m), noName, noLanes, noSpeed);
    }

    private NetworkInsight computeInsight() {
        RoadGraph g = net.graph();
        DisjointSetUnion dsu = new DisjointSetUnion(g.nodeCount());
        for (int e = 0; e < g.segmentCount(); e++) {
            dsu.union(g.segU(e), g.segV(e));
        }
        Map<Integer, Integer> size = new HashMap<>();
        for (int v = 0; v < g.nodeCount(); v++) {
            size.merge(dsu.find(v), 1, Integer::sum);
        }
        int largest = size.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int outside = g.nodeCount() - largest;
        double share = pct(largest, g.nodeCount());
        return new NetworkInsight(dsu.componentCount(), largest, share, outside,
                share + "% of mapped intersections form one weakly connected network; " + (dsu.componentCount() - 1)
                        + " small pieces (" + outside + " intersections) connect to it only through roads outside the arterial "
                        + "snapshot (e.g. residential streets) or the study boundary.");
    }

    // ---------------------------------------------------------------- map data

    public NetworkResponse network(Area requested) {
        Area area = checkArea(requested);
        List<Segment> items = new ArrayList<>();
        Set<Integer> nodes = new HashSet<>();
        boolean truncated = false;
        RoadGraph g = net.graph();
        for (int e = 0; e < g.segmentCount(); e++) {
            if (inside(area, g.segU(e)) || inside(area, g.segV(e))) {
                if (items.size() >= maxAreaSegments) {
                    truncated = true;
                    break;
                }
                items.add(segment(e));
                nodes.add(g.segU(e));
                nodes.add(g.segV(e));
            }
        }
        return new NetworkResponse(area, nodes.size(), items.size(), truncated, items);
    }

    public SegmentDetail segmentDetail(int id) {
        RoadGraph g = net.graph();
        if (id < 0 || id >= g.segmentCount()) {
            throw new ValidationException("id", "segment " + id + " does not exist");
        }
        Segment s = segment(id);
        MumbaiNetwork.ClassDefault d = net.classDefault(s.roadClass());
        List<DataItem> attrs = new ArrayList<>();
        attrs.add(new DataItem("name: " + (s.name().isBlank() ? "(missing)" : s.name()), s.name().isBlank() ? DataClass.NOT_AVAILABLE : DataClass.REAL_MAPPED));
        attrs.add(new DataItem("road class: " + s.roadClass(), DataClass.REAL_MAPPED));
        attrs.add(new DataItem("direction: " + (s.oneWay() ? "one-way" : "two-way"), DataClass.REAL_MAPPED));
        attrs.add(new DataItem("length: " + s.lengthM() + " m (from OSM geometry)", DataClass.DERIVED_RESULT));
        attrs.add(new DataItem("lanes tag: " + (s.lanesTag() == null ? "missing" : s.lanesTag()), s.lanesTag() == null ? DataClass.NOT_AVAILABLE : DataClass.REAL_MAPPED));
        attrs.add(new DataItem("maxspeed tag: " + (s.maxspeedTag() == null ? "missing" : s.maxspeedTag() + " km/h"), s.maxspeedTag() == null ? DataClass.NOT_AVAILABLE : DataClass.REAL_MAPPED));
        attrs.add(new DataItem("free-flow time: " + s.travelTimeS() + " s at " + (s.maxspeedTag() != null ? s.maxspeedTag() : (int) d.speedKph())
                + " km/h" + (s.maxspeedTag() == null ? " (class default)" : ""), DataClass.ESTIMATE));
        attrs.add(new DataItem("lane-based capacity assumption: " + Math.round(s.capacityVph()) + " veh/h per direction", DataClass.ESTIMATE));
        attrs.add(new DataItem("live speed / congestion", DataClass.NOT_AVAILABLE));
        List<String> missing = new ArrayList<>();
        if (s.name().isBlank()) {
            missing.add("name");
        }
        if (s.lanesTag() == null) {
            missing.add("lanes");
        }
        if (s.maxspeedTag() == null) {
            missing.add("maxspeed");
        }
        missing.add("live traffic");
        return new SegmentDetail(s, attrs, missing, "https://www.openstreetmap.org/way/" + s.wayId());
    }

    // ---------------------------------------------------------------- routes (Dijkstra)

    public RouteResponse route(RouteRequest req) {
        if (req == null || req.fromLat() == null || req.fromLon() == null || req.toLat() == null || req.toLon() == null) {
            throw new ValidationException("route", "fromLat, fromLon, toLat and toLon are required");
        }
        RoadGraph.Metric metric = parseMetric(req.metric());
        Snap from = snap(req.fromLat(), req.fromLon(), null, "from");
        Snap to = snap(req.toLat(), req.toLon(), null, "to");
        RoadGraph g = net.graph();
        boolean[] blocked = blockedSegments(req.avoidSegmentIds());
        int avoided = req.avoidSegmentIds() == null ? 0 : (int) req.avoidSegmentIds().stream().distinct().count();
        Dijkstra.Result fib = Dijkstra.run(g, from.node(), to.node(), metric, Dijkstra.Queue.FIBONACCI, blocked);
        Dijkstra.Result bin = Dijkstra.run(g, from.node(), to.node(), metric, Dijkstra.Queue.BINARY, blocked);
        double dF = fib.dist()[to.node()];
        double dB = bin.dist()[to.node()];
        boolean agree = (Double.isInfinite(dF) && Double.isInfinite(dB)) || Math.abs(dF - dB) <= 1e-6 * Math.max(1, dF);
        if (!agree) {
            throw new IllegalStateException("Fibonacci-heap and binary-heap Dijkstra disagree");
        }
        String weight = metric == RoadGraph.Metric.DISTANCE ? "real road length (m)" : "estimated free-flow time (s)";
        if (Double.isInfinite(dF)) {
            Explanation ex = new Explanation(routeData(metric, avoided),
                    "Dijkstra searched every intersection reachable from A along permitted directions and found no path to B.",
                    List.of("With this arterial snapshot, its one-way rules" + (avoided > 0 ? " and your " + avoided + " closed segment(s)" : "")
                            + ", B cannot be reached from A."),
                    List.of("That the places are unreachable in reality: residential streets and roads outside the study area are not in the graph."));
            return new RouteResponse(from, to, metric.name(), false, 0, 0, 0, List.of(), List.of(), List.of(),
                    stats(fib), stats(bin), true, avoided, ex);
        }
        List<Integer> segs = fib.segmentsTo(g, to.node());
        List<double[]> geom = new ArrayList<>();
        List<RoadStep> steps = new ArrayList<>();
        double len = 0;
        double time = 0;
        double taggedLen = 0;
        int cur = from.node();
        for (int s : segs) {
            double[] gm = net.geometry(s);
            geom.add(g.segU(s) == cur ? gm : reversed(gm));
            cur = g.segU(s) == cur ? g.segV(s) : g.segU(s);
            len += g.lengthM(s);
            time += g.travelTimeS(s);
            if (net.maxspeedTag(s) > 0) {
                taggedLen += g.lengthM(s);
            }
            String nm = net.name(s).isBlank() ? "(unnamed " + net.roadClass(s) + ")" : net.name(s);
            if (!steps.isEmpty() && steps.get(steps.size() - 1).name().equals(nm)) {
                RoadStep last = steps.remove(steps.size() - 1);
                steps.add(new RoadStep(nm, last.roadClass(), last.lengthM() + g.lengthM(s)));
            } else {
                steps.add(new RoadStep(nm, net.roadClass(s), g.lengthM(s)));
            }
        }
        steps.replaceAll(st -> new RoadStep(st.name(), st.roadClass(), round1(st.lengthM())));
        double taggedPct = len == 0 ? 0 : round1(100 * taggedLen / len);
        Explanation ex = new Explanation(routeData(metric, avoided),
                "Dijkstra's algorithm found the path minimising " + weight + " over directed arcs (one-way rules respected). "
                        + "It ran with a Fibonacci heap and with java.util.PriorityQueue; both returned the same total.",
                List.of("This is the minimum-" + (metric == RoadGraph.Metric.DISTANCE ? "length" : "estimated-time") + " path in the mapped arterial network: "
                        + round1(len / 1000) + " km.",
                        "The estimated free-flow time (" + round1(time / 60) + " min) assumes posted or default speeds; only "
                                + taggedPct + "% of this route's length has a mapped maxspeed."),
                List.of("That this is the fastest route now: there is no live traffic data.",
                        "That residential shortcuts do not exist: they are not in the graph.",
                        "Turn restrictions are not modelled."));
        return new RouteResponse(from, to, metric.name(), true, round1(len), round1(time), taggedPct, segs, geom, steps,
                stats(fib), stats(bin), true, avoided, ex);
    }

    private static List<DataItem> routeData(RoadGraph.Metric metric, int avoided) {
        List<DataItem> d = new ArrayList<>();
        d.add(new DataItem("OSM arterial road geometry, one-way rules and road classes", DataClass.REAL_MAPPED));
        d.add(new DataItem(metric == RoadGraph.Metric.DISTANCE ? "Segment lengths from OSM geometry" : "Free-flow travel-time estimates (maxspeed tag or class default)",
                metric == RoadGraph.Metric.DISTANCE ? DataClass.DERIVED_RESULT : DataClass.ESTIMATE));
        if (avoided > 0) {
            d.add(new DataItem(avoided + " segment(s) closed by the user (hypothetical closure)", DataClass.SAMPLE));
        }
        d.add(new DataItem("Live traffic", DataClass.NOT_AVAILABLE));
        return d;
    }

    private boolean[] blockedSegments(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        if (ids.size() > 500) {
            throw new ValidationException("avoidSegmentIds", "at most 500 segments can be closed");
        }
        boolean[] b = new boolean[net.graph().segmentCount()];
        for (Integer id : ids) {
            if (id == null || id < 0 || id >= b.length) {
                throw new ValidationException("avoidSegmentIds", "segment " + id + " does not exist");
            }
            b[id] = true;
        }
        return b;
    }

    // ---------------------------------------------------------------- connectivity (DSU + directed BFS)

    public ComponentsResponse components(ComponentsRequest req) {
        if (req == null) {
            throw new ValidationException("request", "is required");
        }
        Area area = checkArea(req.area());
        int[] segs = areaSegments(area, maxAreaSegments);
        Set<Integer> removed = removedSet(req.removedSegmentIds(), segs);
        int[] nodeIds = areaNodes(segs);
        Map<Integer, Integer> local = localIndex(nodeIds);
        int[] compBefore = new int[nodeIds.length];
        ComponentSummary before = summarize(segs, Set.of(), nodeIds, local, compBefore);
        int[] compOfNode = new int[nodeIds.length];
        ComponentSummary after = summarize(segs, removed, nodeIds, local, compOfNode);
        RoadGraph g = net.graph();
        List<int[]> segComp = new ArrayList<>(segs.length);
        for (int e : segs) {
            segComp.add(new int[] {e, removed.contains(e) ? -1 : compOfNode[local.get(g.segU(e))]});
        }
        PairConnectivity pair = null;
        if (req.fromLat() != null || req.toLat() != null) {
            if (req.fromLat() == null || req.fromLon() == null || req.toLat() == null || req.toLon() == null) {
                throw new ValidationException("from", "give both origin and destination coordinates, or neither");
            }
            boolean[] allowed = new boolean[net.nodeCount()];
            for (int id : nodeIds) {
                allowed[id] = true;
            }
            Snap a = snap(req.fromLat(), req.fromLon(), allowed, "from");
            Snap b = snap(req.toLat(), req.toLon(), allowed, "to");
            int la = local.get(a.node());
            int lb = local.get(b.node());
            boolean[] reachBefore = directedReach(segs, Set.of(), nodeIds, local, la);
            boolean[] reachAfter = directedReach(segs, removed, nodeIds, local, la);
            int reachable = 0;
            int weakSize = 0;
            for (int i = 0; i < nodeIds.length; i++) {
                if (reachAfter[i]) {
                    reachable++;
                }
                if (compOfNode[i] == compOfNode[la]) {
                    weakSize++;
                }
            }
            pair = new PairConnectivity(a, b, compBefore[la] == compBefore[lb], compOfNode[la] == compOfNode[lb],
                    reachBefore[lb], reachAfter[lb], reachable, weakSize);
        }
        List<String> can = new ArrayList<>(List.of(
                "After removing " + removed.size() + " segment(s), the selected area's arterial graph has " + after.componentCount()
                        + " weakly connected component(s) (largest: " + Math.round(after.largestShare() * 100) + "% of its intersections)."));
        if (pair != null) {
            can.add("Weak connectivity (any road link ignoring direction): " + (pair.weaklyConnectedAfter() ? "yes" : "no")
                    + "; a drivable directed path respecting one-way rules: " + (pair.directedPathAfter() ? "yes" : "no") + ".");
        }
        Explanation ex = new Explanation(List.of(
                new DataItem("OSM arterial segments inside the selected area", DataClass.REAL_MAPPED),
                new DataItem("Removed segments chosen by the user (hypothetical closures)", removed.isEmpty() ? DataClass.REAL_MAPPED : DataClass.SAMPLE),
                new DataItem("Live closures", DataClass.NOT_AVAILABLE)),
                "Disjoint-set union over the undirected view (weak connectivity), rebuilt after removals because DSU cannot delete edges; "
                        + "breadth-first search over directed arcs for one-way-aware reachability.",
                can,
                List.of("Practical vehicle accessibility: residential streets, turn bans and real closures are not in the data.",
                        "Small components at the area boundary usually continue outside the selected box.",
                        "Weakly connected does not mean drivable: one-way rules can still block a trip."));
        return new ComponentsResponse(area, nodeIds.length, segs.length, removed.size(), before, after, segComp, pair, ex);
    }

    private boolean[] directedReach(int[] segs, Set<Integer> removed, int[] nodeIds, Map<Integer, Integer> local, int start) {
        RoadGraph g = net.graph();
        List<List<Integer>> adj = new ArrayList<>(nodeIds.length);
        for (int i = 0; i < nodeIds.length; i++) {
            adj.add(new ArrayList<>());
        }
        for (int e : segs) {
            if (removed.contains(e)) {
                continue;
            }
            int u = local.get(g.segU(e));
            int v = local.get(g.segV(e));
            adj.get(u).add(v);
            if (!g.oneWay(e)) {
                adj.get(v).add(u);
            }
        }
        boolean[] seen = new boolean[nodeIds.length];
        int[] q = new int[nodeIds.length];
        int h = 0;
        int t = 0;
        seen[start] = true;
        q[t++] = start;
        while (h < t) {
            int u = q[h++];
            for (int v : adj.get(u)) {
                if (!seen[v]) {
                    seen[v] = true;
                    q[t++] = v;
                }
            }
        }
        return seen;
    }

    private ComponentSummary summarize(int[] segs, Set<Integer> removed, int[] nodeIds, Map<Integer, Integer> local,
                                       int[] compOut) {
        RoadGraph g = net.graph();
        DisjointSetUnion dsu = new DisjointSetUnion(nodeIds.length);
        int merged = 0;
        int redundant = 0;
        for (int e : segs) {
            if (removed.contains(e)) {
                continue;
            }
            if (dsu.union(local.get(g.segU(e)), local.get(g.segV(e)))) {
                merged++;
            } else {
                redundant++;
            }
        }
        Map<Integer, int[]> size = new LinkedHashMap<>();
        Map<Integer, double[]> center = new LinkedHashMap<>();
        for (int i = 0; i < nodeIds.length; i++) {
            int r = dsu.find(i);
            size.computeIfAbsent(r, k -> new int[2])[0]++;
            double[] c = center.computeIfAbsent(r, k -> new double[2]);
            c[0] += net.lat(nodeIds[i]);
            c[1] += net.lon(nodeIds[i]);
        }
        for (int e : segs) {
            if (!removed.contains(e)) {
                size.get(dsu.find(local.get(g.segU(e))))[1]++;
            }
        }
        List<Integer> roots = new ArrayList<>(size.keySet());
        roots.sort(Comparator.comparingInt((Integer r) -> -size.get(r)[0]).thenComparing(r -> r));
        Map<Integer, Integer> compId = new LinkedHashMap<>();
        List<Component> comps = new ArrayList<>();
        int isolated = 0;
        for (int i = 0; i < roots.size(); i++) {
            int r = roots.get(i);
            compId.put(r, i);
            int[] s = size.get(r);
            if (s[0] == 1) {
                isolated++;
            }
            if (comps.size() < 50) {
                double[] c = center.get(r);
                comps.add(new Component(i, s[0], s[1], round6(c[0] / s[0]), round6(c[1] / s[0])));
            }
        }
        if (compOut != null) {
            for (int i = 0; i < nodeIds.length; i++) {
                compOut[i] = compId.get(dsu.find(i));
            }
        }
        int largest = roots.isEmpty() ? 0 : size.get(roots.get(0))[0];
        return new ComponentSummary(roots.size(), largest, nodeIds.length == 0 ? 0 : round4((double) largest / nodeIds.length),
                isolated, merged, redundant, comps);
    }

    // ---------------------------------------------------------------- critical links (max flow / min cut)

    public CriticalLinksResponse criticalLinks(CriticalLinksRequest req) {
        if (req == null || req.sourceLat() == null || req.sourceLon() == null || req.sinkLat() == null || req.sinkLon() == null) {
            throw new ValidationException("criticalLinks", "area, source and sink coordinates are required");
        }
        String model = req.capacityModel() == null ? "UNIT" : req.capacityModel();
        if (!model.equals("UNIT") && !model.equals("LANE_ASSUMPTION")) {
            throw new ValidationException("capacityModel", "must be UNIT or LANE_ASSUMPTION");
        }
        boolean unit = model.equals("UNIT");
        Area area = checkArea(req.area());
        int[] segs = areaSegments(area, maxFlowSegments);
        if (segs.length == 0) {
            throw new ValidationException("area", "the selected area contains no road segments");
        }
        Set<Integer> removed = removedSet(req.removedSegmentIds(), segs);
        int[] nodeIds = areaNodes(segs);
        Map<Integer, Integer> local = localIndex(nodeIds);
        boolean[] allowed = new boolean[net.nodeCount()];
        for (int id : nodeIds) {
            allowed[id] = true;
        }
        Snap source = snap(req.sourceLat(), req.sourceLon(), allowed, "source");
        Snap sink = snap(req.sinkLat(), req.sinkLon(), allowed, "sink");
        if (source.node() == sink.node()) {
            throw new ValidationException("sink", "source and sink snap to the same intersection; pick points further apart");
        }
        RoadGraph g = net.graph();
        FlowNetwork fn = new FlowNetwork(nodeIds.length);
        List<int[]> arcSeg = new ArrayList<>();
        for (int e : segs) {
            if (removed.contains(e)) {
                continue;
            }
            long cap = unit ? 1 : Math.round(g.capacityVph(e));
            int u = local.get(g.segU(e));
            int v = local.get(g.segV(e));
            fn.addEdge(u, v, cap);
            arcSeg.add(new int[] {e, g.segU(e), g.segV(e)});
            if (!g.oneWay(e)) {
                fn.addEdge(v, u, cap);
                arcSeg.add(new int[] {e, g.segV(e), g.segU(e)});
            }
        }
        int s = local.get(source.node());
        int t = local.get(sink.node());
        MaxFlowResult ff = FordFulkerson.run(fn, s, t, TraceOptions.NONE);
        MinCut.verifyFlow(fn, s, t);
        MaxFlowResult ek = EdmondsKarp.run(fn, s, t, new TraceOptions(20, false));
        long verified = MinCut.verifyFlow(fn, s, t);
        MinCut.Result cut = MinCut.fromResidual(fn, s);
        List<CutSegment> cutSegs = new ArrayList<>();
        for (int fe : cut.cutEdges()) {
            int[] m = arcSeg.get(fe);
            cutSegs.add(new CutSegment(m[0], net.name(m[0]), net.roadClass(m[0]), m[1], m[2], fn.edgeCapacity(fe),
                    m[1] == g.segU(m[0]) ? net.geometry(m[0]) : reversed(net.geometry(m[0]))));
        }
        List<long[]> flows = new ArrayList<>();
        for (int fe = 0; fe < fn.edgeCount(); fe++) {
            long f = fn.edgeFlow(fe);
            if (f > 0) {
                flows.add(new long[] {arcSeg.get(fe)[0], f});
            }
        }
        List<PathPreview> paths = new ArrayList<>();
        for (MaxFlowResult.AugmentingStep st : ek.steps()) {
            List<Integer> ps = new ArrayList<>();
            st.arcs().forEach(a -> ps.add(arcSeg.get(a.edge())[0]));
            paths.add(new PathPreview(st.step(), st.bottleneck(), st.flowAfter(), ps));
        }
        List<String> warnings = new ArrayList<>();
        if (ek.maxFlow() == 0) {
            warnings.add("No directed path from source to sink inside the selected area (one-way rules, removals, or enlarge the area).");
        }
        if (!removed.isEmpty()) {
            warnings.add(removed.size() + " segment(s) removed by the user before the analysis (hypothetical).");
        }
        if (source.snapDistanceM() > 300 || sink.snapDistanceM() > 300) {
            warnings.add("A clicked point is more than 300 m from the nearest arterial intersection; the analysis uses the snapped intersection.");
        }
        String unitLabel = unit ? "independent routes (edge-disjoint directed paths)" : "assumed vehicles/hour (not measured)";
        Explanation ex = unit
                ? new Explanation(List.of(new DataItem("OSM arterial segments and one-way rules in the area", DataClass.REAL_MAPPED),
                        new DataItem("Capacity 1 per directed segment (structural model, no traffic assumption)", DataClass.DERIVED_RESULT)),
                "Edmonds-Karp and Ford-Fulkerson computed the maximum number of directed source-to-sink routes that share no road segment; "
                        + "the minimum cut is the smallest set of directed segments whose closure disconnects sink from source (Menger's theorem).",
                List.of("There are " + ek.maxFlow() + " road-disjoint directed routes from source to sink inside the area.",
                        "Closing the " + cutSegs.size() + " highlighted segment(s) would disconnect them within this graph; no smaller set does."),
                List.of("How many vehicles can travel: capacities are not measured.",
                        "Real disruption impact: residential streets outside the snapshot can provide detours."))
                : new Explanation(List.of(new DataItem("OSM arterial segments and one-way rules in the area", DataClass.REAL_MAPPED),
                        new DataItem("Lane-based capacity assumption (lanes tag or class default x per-lane rule)", DataClass.ESTIMATE)),
                "Edmonds-Karp and Ford-Fulkerson computed the maximum flow under the assumed per-segment capacities; the minimum cut's assumed "
                        + "capacities sum to the same value.",
                List.of("Under this assumption, the highlighted segments are the binding bottleneck between source and sink."),
                List.of("Actual traffic throughput: " + coverage.segmentsMissingLanes() + " of " + net.graph().segmentCount()
                        + " segments have no lanes tag and the per-lane values are rules of thumb.",
                        "Current congestion or real disruption impact."));
        return new CriticalLinksResponse(area, source, sink, model, unitLabel, nodeIds.length, fn.edgeCount(), ek.maxFlow(),
                cut.cutCapacity(), cut.cutCapacity() == ek.maxFlow(), verified == ek.maxFlow(), cutSegs, flows,
                flowStats(ek), flowStats(ff), ff.maxFlow() == ek.maxFlow(), paths, warnings, ex);
    }

    // ---------------------------------------------------------------- statistics on real derived values (Quickselect)

    public LengthStatsResponse lengthStats(Area requested, List<Double> percentiles) {
        Area area = checkArea(requested);
        int[] segs = areaSegments(area, Integer.MAX_VALUE);
        if (segs.length == 0) {
            throw new ValidationException("area", "no road segments in the area");
        }
        List<Double> ps = percentiles == null || percentiles.isEmpty() ? List.of(50.0, 90.0) : percentiles;
        if (ps.size() > 10) {
            throw new ValidationException("percentiles", "at most 10 percentiles");
        }
        double[] v = new double[segs.length];
        for (int i = 0; i < segs.length; i++) {
            v[i] = net.graph().lengthM(segs[i]);
        }
        List<PercentileValue> out = new ArrayList<>();
        for (Double p : ps) {
            if (p == null || !(p >= 0 && p <= 100)) {
                throw new ValidationException("percentiles", "each percentile must be in [0, 100]");
            }
            long t0 = System.nanoTime();
            RandomizedQuickselect.Selection sel = RandomizedQuickselect.percentile(v, p, 2026L);
            double qsMs = (System.nanoTime() - t0) / 1e6;
            long t1 = System.nanoTime();
            double[] sorted = v.clone();
            Arrays.sort(sorted);
            int rank = Math.max(1, (int) Math.ceil(p / 100 * v.length));
            double sortValue = sorted[rank - 1];
            double sortMs = (System.nanoTime() - t1) / 1e6;
            out.add(new PercentileValue(p, round1(sel.value()), sel.comparisons(), round3(qsMs), round3(sortMs), sel.value() == sortValue));
        }
        return new LengthStatsResponse(area, v.length, out, new Explanation(
                List.of(new DataItem("Segment lengths from OSM geometry in the area", DataClass.DERIVED_RESULT)),
                "Randomized Quickselect (seed 2026) found nearest-rank percentiles of segment length; each was checked against a full sort.",
                List.of("How long arterial road pieces between intersections typically are in this area."),
                List.of("Anything about traffic: these are geometric lengths, not travel observations.")));
    }

    /** A* (straight-line heuristic) vs Dijkstra (Fibonacci heap) on the road graph, by real road length. */
    public record AStarComparison(Snap from, Snap to, boolean reachable, double distanceM, int aStarSettled, int dijkstraSettled,
                                  double aStarMs, double dijkstraMs, boolean sameDistance, List<double[]> geometry, String note) {}

    public AStarComparison astar(RouteRequest req) {
        if (req == null || req.fromLat() == null || req.fromLon() == null || req.toLat() == null || req.toLon() == null) {
            throw new ValidationException("route", "fromLat, fromLon, toLat and toLon are required");
        }
        Snap from = snap(req.fromLat(), req.fromLon(), null, "from");
        Snap to = snap(req.toLat(), req.toLon(), null, "to");
        RoadGraph g = net.graph();
        double tLat = net.lat(to.node());
        double tLon = net.lon(to.node());
        // admissible: no road between two points is shorter than the great-circle distance (x 0.999 for rounding)
        edu.trace.algorithms.graph.GraphAlgorithms.AStarResult a = edu.trace.algorithms.graph.GraphAlgorithms.aStar(g, from.node(), to.node(),
                RoadGraph.Metric.DISTANCE, n -> 0.999 * net.metresTo(n, tLat, tLon));
        Dijkstra.Result d = Dijkstra.run(g, from.node(), to.node(), RoadGraph.Metric.DISTANCE, Dijkstra.Queue.FIBONACCI);
        double dd = d.dist()[to.node()];
        boolean reachable = !Double.isInfinite(dd);
        boolean same = reachable ? Math.abs(a.distance() - dd) <= 1e-6 * Math.max(1, dd) : Double.isInfinite(a.distance());
        List<double[]> geom = new ArrayList<>();
        a.segments().forEach(e -> geom.add(net.geometry(e)));
        return new AStarComparison(from, to, reachable, reachable ? Math.round(dd * 10) / 10.0 : -1, a.settled(), d.settled(),
                Math.round(a.elapsedMs() * 1000) / 1000.0, Math.round(d.elapsedMillis() * 1000) / 1000.0, same, geom,
                "Both find the shortest route by real road length on the public OpenStreetMap snapshot (historical dataset). A* settles fewer "
                        + "junctions because the straight-line distance to the target guides the search; the heuristic never overestimates.");
    }

    // ---------------------------------------------------------------- helpers

    public Area checkArea(Area a) {
        if (a == null) {
            return STUDY_AREA;
        }
        if (a.south() == null || a.west() == null || a.north() == null || a.east() == null) {
            throw new ValidationException("area", "south, west, north and east are all required");
        }
        if (!(a.south() < a.north()) || !(a.west() < a.east())) {
            throw new ValidationException("area", "south must be less than north and west less than east");
        }
        Area clipped = new Area(Math.max(a.south(), STUDY_AREA.south()), Math.max(a.west(), STUDY_AREA.west()),
                Math.min(a.north(), STUDY_AREA.north()), Math.min(a.east(), STUDY_AREA.east()));
        if (!(clipped.south() < clipped.north()) || !(clipped.west() < clipped.east())) {
            throw new ValidationException("area", "the area does not overlap the Mumbai study area " + STUDY_AREA);
        }
        return clipped;
    }

    boolean inside(Area a, int node) {
        return net.lat(node) >= a.south() && net.lat(node) <= a.north() && net.lon(node) >= a.west() && net.lon(node) <= a.east();
    }

    /** Segments with both endpoints inside the area (the induced subgraph). */
    int[] areaSegments(Area a, int limit) {
        RoadGraph g = net.graph();
        List<Integer> out = new ArrayList<>();
        for (int e = 0; e < g.segmentCount(); e++) {
            if (inside(a, g.segU(e)) && inside(a, g.segV(e))) {
                out.add(e);
            }
        }
        if (out.size() > limit) {
            throw new ValidationException("area", "the selected area has " + out.size() + " road segments; the limit for this analysis is "
                    + limit + ". Select a smaller area.");
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private int[] areaNodes(int[] segs) {
        RoadGraph g = net.graph();
        Set<Integer> s = new TreeSet<>();
        for (int e : segs) {
            s.add(g.segU(e));
            s.add(g.segV(e));
        }
        return s.stream().mapToInt(Integer::intValue).toArray();
    }

    private static Map<Integer, Integer> localIndex(int[] nodeIds) {
        Map<Integer, Integer> m = new HashMap<>();
        for (int i = 0; i < nodeIds.length; i++) {
            m.put(nodeIds[i], i);
        }
        return m;
    }

    private Set<Integer> removedSet(List<Integer> ids, int[] segs) {
        Set<Integer> out = new HashSet<>();
        if (ids == null) {
            return out;
        }
        if (ids.size() > 500) {
            throw new ValidationException("removedSegmentIds", "at most 500 segments can be removed");
        }
        Set<Integer> inArea = new HashSet<>();
        for (int e : segs) {
            inArea.add(e);
        }
        for (Integer id : ids) {
            if (id == null || !inArea.contains(id)) {
                throw new ValidationException("removedSegmentIds", "segment " + id + " is not inside the selected area");
            }
            out.add(id);
        }
        return out;
    }

    Snap snap(double lat, double lon, boolean[] allowed, String field) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            throw new ValidationException(field, "invalid GPS coordinate");
        }
        int n = net.nearestNode(lat, lon, allowed);
        if (n < 0) {
            throw new ValidationException(field, "no road intersection available");
        }
        double d = net.metresTo(n, lat, lon);
        if (d > 3000) {
            throw new ValidationException(field, "the point is " + Math.round(d) + " m from the nearest mapped arterial road; pick a point inside the network");
        }
        return new Snap(n, net.osmNodeId(n), net.lat(n), net.lon(n), round1(d));
    }

    Segment segment(int e) {
        RoadGraph g = net.graph();
        return new Segment(e, net.wayId(e), net.name(e), net.roadClass(e), g.segU(e), g.segV(e), g.oneWay(e),
                round1(g.lengthM(e)), round1(g.travelTimeS(e)), g.capacityVph(e),
                net.lanesTag(e) > 0 ? net.lanesTag(e) : null, net.maxspeedTag(e) > 0 ? net.maxspeedTag(e) : null, net.geometry(e));
    }

    static double[] reversed(double[] flat) {
        double[] r = new double[flat.length];
        for (int i = 0; i < flat.length; i += 2) {
            r[flat.length - 2 - i] = flat[i];
            r[flat.length - 1 - i] = flat[i + 1];
        }
        return r;
    }

    private static RoadGraph.Metric parseMetric(String m) {
        if (m == null || m.equals("DISTANCE")) {
            return RoadGraph.Metric.DISTANCE;
        }
        if (m.equals("ESTIMATED_TIME") || m.equals("TIME")) {
            return RoadGraph.Metric.TIME;
        }
        throw new ValidationException("metric", "must be DISTANCE or ESTIMATED_TIME");
    }

    private static QueueStats stats(Dijkstra.Result r) {
        return new QueueStats(r.queue() == Dijkstra.Queue.FIBONACCI ? "Fibonacci heap (custom)" : "java.util.PriorityQueue (binary heap, lazy deletion)",
                r.settled(), r.relaxations(), r.heapOperations(), Math.round(r.elapsedMillis() * 1000) / 1000.0);
    }

    private static FlowStats flowStats(MaxFlowResult r) {
        return new FlowStats(r.algorithm(), r.maxFlow(), r.augmentations(), r.arcScans(), Math.round(r.elapsedMillis() * 1000) / 1000.0);
    }

    static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    static Long ageDays(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Duration.between(Instant.parse(iso), Instant.now()).toDays();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String freshness(String iso) {
        Long d = ageDays(iso);
        if (d == null) {
            return "unknown";
        }
        return "static snapshot, " + (d == 0 ? "retrieved today" : d + " day(s) old") + "; re-run the data pipeline to refresh";
    }

    static double pct(int part, int whole) {
        return whole == 0 ? 0 : Math.round(1000.0 * part / whole) / 10.0;
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
