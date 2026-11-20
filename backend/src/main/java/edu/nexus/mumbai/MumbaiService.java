package edu.nexus.mumbai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.nexus.algorithms.dsu.DisjointSetUnion;
import edu.nexus.algorithms.flow.EdmondsKarp;
import edu.nexus.algorithms.flow.FlowNetwork;
import edu.nexus.algorithms.flow.FordFulkerson;
import edu.nexus.algorithms.flow.MaxFlowResult;
import edu.nexus.algorithms.flow.MinCut;
import edu.nexus.algorithms.flow.TraceOptions;
import edu.nexus.algorithms.graph.Dijkstra;
import edu.nexus.algorithms.graph.RoadGraph;
import edu.nexus.model.ValidationException;
import edu.nexus.mumbai.MumbaiDtos.Area;
import edu.nexus.mumbai.MumbaiDtos.ClassCount;
import edu.nexus.mumbai.MumbaiDtos.Component;
import edu.nexus.mumbai.MumbaiDtos.ComponentSummary;
import edu.nexus.mumbai.MumbaiDtos.ComponentsRequest;
import edu.nexus.mumbai.MumbaiDtos.ComponentsResponse;
import edu.nexus.mumbai.MumbaiDtos.CriticalLinksRequest;
import edu.nexus.mumbai.MumbaiDtos.CriticalLinksResponse;
import edu.nexus.mumbai.MumbaiDtos.CutSegment;
import edu.nexus.mumbai.MumbaiDtos.DataSource;
import edu.nexus.mumbai.MumbaiDtos.FlowStats;
import edu.nexus.mumbai.MumbaiDtos.GraphStats;
import edu.nexus.mumbai.MumbaiDtos.NetworkResponse;
import edu.nexus.mumbai.MumbaiDtos.PathPreview;
import edu.nexus.mumbai.MumbaiDtos.ProvenanceResponse;
import edu.nexus.mumbai.MumbaiDtos.QueueStats;
import edu.nexus.mumbai.MumbaiDtos.RoadStep;
import edu.nexus.mumbai.MumbaiDtos.RouteRequest;
import edu.nexus.mumbai.MumbaiDtos.RouteResponse;
import edu.nexus.mumbai.MumbaiDtos.Segment;
import edu.nexus.mumbai.MumbaiDtos.Snap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Analyses on the real Mumbai road graph: provenance, map data, routes, components, critical links. */
@Service
public class MumbaiService {

    /** Study area of the snapshot (Greater Mumbai). */
    public static final Area STUDY_AREA = new Area(18.89, 72.77, 19.29, 72.99);

    private final MumbaiNetwork net;
    private final int maxAreaSegments;
    private final int maxFlowSegments;
    private final EventService events;

    public MumbaiService(ObjectMapper mapper, EventService events,
                         @Value("${nexus.max-area-segments:20000}") int maxAreaSegments,
                         @Value("${nexus.max-flow-segments:6000}") int maxFlowSegments) throws IOException {
        try (InputStream in = new ClassPathResource("data/mumbai/roads.json").getInputStream()) {
            this.net = MumbaiNetwork.load(in, mapper);
        }
        this.events = events;
        this.maxAreaSegments = maxAreaSegments;
        this.maxFlowSegments = maxFlowSegments;
    }

    public MumbaiNetwork network() {
        return net;
    }

    // ---------------------------------------------------------------- provenance

    public ProvenanceResponse provenance() {
        JsonNode p = net.provenance();
        RoadGraph g = net.graph();
        List<DataSource> sources = new ArrayList<>();
        String retrieved = text(p, "retrievedAt");
        sources.add(new DataSource("roads", text(p, "dataset"), "STATIC_GEOGRAPHIC", text(p, "source"), text(p, "sourceUrl"),
                text(p, "licence"), retrieved, text(p, "osmDataTimestamp"), ageDays(retrieved), freshness(retrieved),
                p.get("coverage").get("description").asText(), text(p, "notes")));
        sources.add(events.dataSource());
        sources.add(new DataSource("travel-time", "Segment travel-time estimates", "DERIVED_ESTIMATE",
                "Computed in NEXUS from OSM length and maxspeed tags (or class defaults)", null, null, retrieved, null, null,
                "derived from the road snapshot", "every road segment",
                net.assumptions().get("travelTime").asText()));
        sources.add(new DataSource("capacity", "Segment capacity proxy", "MODELLED_PROXY",
                "Computed in NEXUS from OSM lanes tags (or class defaults) x per-lane capacity", null, null, retrieved, null,
                null, "derived from the road snapshot", "every road segment", net.assumptions().get("capacity").asText()));
        sources.add(new DataSource("live-traffic", "Live traffic speeds and incidents", "NOT_AVAILABLE", null, null, null,
                null, null, null, "not connected",
                "Mumbai", "No free, documented, terms-verified live traffic or incident feed for Mumbai was found. Commercial APIs "
                + "(Google, TomTom, HERE) need paid keys; no official public API exists. NEXUS does not show live conditions."));
        sources.add(new DataSource("sample", "Varunapuri sample graph", "DEMONSTRATION", "Hand-made test data in this repository",
                null, "MIT", null, null, null, "static", "fictional", "Used only by offline tests and the Algorithm Lab examples."));
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
        return new ProvenanceResponse(STUDY_AREA, sources,
                new GraphStats(g.nodeCount(), g.segmentCount(), g.arcCount(), oneWay, round1(km), classes), net.assumptions());
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

    // ---------------------------------------------------------------- routes

    public RouteResponse route(RouteRequest req) {
        if (req == null || req.fromLat() == null || req.fromLon() == null || req.toLat() == null || req.toLon() == null) {
            throw new ValidationException("route", "fromLat, fromLon, toLat and toLon are required");
        }
        RoadGraph.Metric metric = parseMetric(req.metric());
        Snap from = snap(req.fromLat(), req.fromLon(), null, "from");
        Snap to = snap(req.toLat(), req.toLon(), null, "to");
        RoadGraph g = net.graph();
        Dijkstra.Result fib = Dijkstra.run(g, from.node(), to.node(), metric, Dijkstra.Queue.FIBONACCI);
        Dijkstra.Result bin = Dijkstra.run(g, from.node(), to.node(), metric, Dijkstra.Queue.BINARY);
        double dF = fib.dist()[to.node()];
        double dB = bin.dist()[to.node()];
        boolean agree = (Double.isInfinite(dF) && Double.isInfinite(dB)) || Math.abs(dF - dB) <= 1e-6 * Math.max(1, dF);
        if (!agree) {
            throw new IllegalStateException("Fibonacci-heap and binary-heap Dijkstra disagree");
        }
        List<String> notes = new ArrayList<>();
        notes.add(metric == RoadGraph.Metric.TIME
                ? "Fastest route by DERIVED travel-time estimates (free-flow speeds), not live traffic."
                : "Shortest route by real OSM road length.");
        notes.add("Only motorway-to-tertiary roads are in the graph; residential streets are excluded.");
        if (Double.isInfinite(dF)) {
            notes.add("No directed path exists between the snapped nodes (one-way restrictions or disconnected parts).");
            return new RouteResponse(from, to, metric.name(), false, 0, 0, List.of(), List.of(), List.of(),
                    stats(fib), stats(bin), true, notes);
        }
        List<Integer> segs = fib.segmentsTo(g, to.node());
        List<double[]> geom = new ArrayList<>();
        List<RoadStep> steps = new ArrayList<>();
        double len = 0;
        double time = 0;
        int cur = from.node();
        for (int s : segs) {
            double[] gm = net.geometry(s);
            geom.add(g.segU(s) == cur ? gm : reversed(gm));
            cur = g.segU(s) == cur ? g.segV(s) : g.segU(s);
            len += g.lengthM(s);
            time += g.travelTimeS(s);
            String nm = net.name(s).isBlank() ? "(unnamed " + net.roadClass(s) + ")" : net.name(s);
            if (!steps.isEmpty() && steps.get(steps.size() - 1).name().equals(nm)) {
                RoadStep last = steps.remove(steps.size() - 1);
                steps.add(new RoadStep(nm, last.roadClass(), last.lengthM() + g.lengthM(s)));
            } else {
                steps.add(new RoadStep(nm, net.roadClass(s), g.lengthM(s)));
            }
        }
        steps.replaceAll(st -> new RoadStep(st.name(), st.roadClass(), round1(st.lengthM())));
        return new RouteResponse(from, to, metric.name(), true, round1(len), round1(time), segs, geom, steps,
                stats(fib), stats(bin), true, notes);
    }

    // ---------------------------------------------------------------- components (DSU)

    public ComponentsResponse components(ComponentsRequest req) {
        if (req == null) {
            throw new ValidationException("request", "is required");
        }
        Area area = checkArea(req.area());
        int[] segs = areaSegments(area, maxAreaSegments);
        Set<Integer> removed = removedSet(req.removedSegmentIds(), segs);
        int[] nodeIds = areaNodes(segs);
        Map<Integer, Integer> local = localIndex(nodeIds);
        ComponentSummary before = summarize(segs, Set.of(), nodeIds, local, null);
        int[] compOfNode = new int[nodeIds.length];
        ComponentSummary after = summarize(segs, removed, nodeIds, local, compOfNode);
        RoadGraph g = net.graph();
        List<int[]> segComp = new ArrayList<>(segs.length);
        for (int e : segs) {
            segComp.add(new int[] {e, removed.contains(e) ? -1 : compOfNode[local.get(g.segU(e))]});
        }
        return new ComponentsResponse(area, nodeIds.length, segs.length, removed.size(), before, after, segComp, List.of(
                "Undirected view: a segment connects its two intersections regardless of one-way rules.",
                "Removed segments are excluded and the DSU is rebuilt; DSU cannot delete edges incrementally.",
                "Small components at the edge of the selected area are often roads that continue outside it."));
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
        Map<Integer, int[]> size = new LinkedHashMap<>(); // root -> [nodes, segments]
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
        List<int[]> arcSeg = new ArrayList<>(); // flow-edge index -> [segment, fromNode, toNode]
        for (int e : segs) {
            if (removed.contains(e)) {
                continue;
            }
            long cap = Math.round(g.capacityVph(e));
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
        MaxFlowResult ek = EdmondsKarp.run(fn, s, t, new TraceOptions(20, false)); // final residual state = EK
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
            warnings.add("No directed path from source to sink inside the selected area (check one-way roads or enlarge the area).");
        }
        if (!removed.isEmpty()) {
            warnings.add(removed.size() + " segment(s) removed by the user before the analysis.");
        }
        return new CriticalLinksResponse(area, source, sink, nodeIds.length, fn.edgeCount(), ek.maxFlow(), cut.cutCapacity(),
                cut.cutCapacity() == ek.maxFlow(), verified == ek.maxFlow(), cutSegs, flows, flowStats(ek), flowStats(ff),
                ff.maxFlow() == ek.maxFlow(), paths, List.of(
                        "Capacities are a MODELLED PROXY (vehicles/hour from lanes x per-lane capacity), not measured throughput.",
                        "Max flow = the most vehicles/hour that could move from source to sink inside the selected area if every road ran at its proxy capacity.",
                        "The minimum cut is a set of directed road segments whose removal disconnects sink from source in this capacity model; their proxy capacities sum to the max flow.",
                        "It does not predict real disaster impact, and a road outside the cut can still matter in practice."),
                warnings);
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
        Set<Integer> s = new java.util.TreeSet<>();
        for (int e : segs) {
            s.add(g.segU(e));
            s.add(g.segV(e));
        }
        return s.stream().mapToInt(Integer::intValue).toArray();
    }

    private static Map<Integer, Integer> localIndex(int[] nodeIds) {
        Map<Integer, Integer> m = new java.util.HashMap<>();
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
        if (m == null || m.equals("TIME")) {
            return RoadGraph.Metric.TIME;
        }
        if (m.equals("DISTANCE")) {
            return RoadGraph.Metric.DISTANCE;
        }
        throw new ValidationException("metric", "must be TIME or DISTANCE");
    }

    private static QueueStats stats(Dijkstra.Result r) {
        return new QueueStats(r.queue() == Dijkstra.Queue.FIBONACCI ? "Fibonacci heap" : "java.util.PriorityQueue (binary heap, lazy deletion)",
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
        return "snapshot, " + (d == 0 ? "retrieved today" : d + " day(s) old") + "; re-run the data pipeline to refresh";
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
