package edu.trace.net;

import edu.trace.algorithms.dsu.DisjointSetUnion;
import edu.trace.algorithms.flow.EdmondsKarp;
import edu.trace.algorithms.flow.FlowNetwork;
import edu.trace.algorithms.flow.FordFulkerson;
import edu.trace.algorithms.flow.MaxFlowResult;
import edu.trace.algorithms.flow.MinCut;
import edu.trace.algorithms.flow.TraceOptions;
import edu.trace.algorithms.graph.Dijkstra;
import edu.trace.algorithms.graph.GraphAlgorithms;
import edu.trace.algorithms.graph.RoadGraph;
import edu.trace.algorithms.rbtree.PriorityKey;
import edu.trace.algorithms.rbtree.RedBlackTree;
import edu.trace.algorithms.segtree.SegmentTree;
import edu.trace.algorithms.select.RandomizedQuickselect;
import edu.trace.intel.ThreatIntelService;
import edu.trace.intel.ThreatIntelService.IntelMatch;
import edu.trace.model.ValidationException;
import edu.trace.net.EventStore.Flow;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/** Graph construction, graph algorithms, investigation rules and ranking over the observed connection metadata. */
@Service
public class AnalysisService {

    public static final Set<Integer> COMMON_PORTS = Set.of(20, 21, 22, 25, 53, 80, 110, 123, 143, 443, 465, 587, 853, 993, 995,
            3478, 5228, 5223, 8080, 8443, 3389, 1194, 51820, 5222, 4070, 9243, 7680);
    public static final int FANOUT_THRESHOLD = 40;
    public static final Duration FANOUT_WINDOW = Duration.ofMinutes(10);

    public static final List<String> SCORING_RULE = List.of(
            "Base by severity: HIGH 70, MEDIUM 40, LOW 15.",
            "Confidence: HIGH +20, MEDIUM +10, LOW +0 (severity = how bad it would be if true; confidence = how strong the evidence is).",
            "Volume: + min(10, floor(log2(observations))).",
            "Seen by more than one sensor: +5.",
            "Analyst decisions never change the score; they change the status (OPEN, ACKNOWLEDGED, DISMISSED, CONFIRMED_INCIDENT).");

    // ---------------------------------------------------------------- DTOs
    public record Evidence(String type, String text, String source, String at) {}

    public record Finding(String id, String rule, String category, String severity, String confidence, int score, List<String> scoreBreakdown,
                          String subject, String title, String firstSeen, String lastSeen, long observations, List<String> sensors,
                          List<String> processes, List<Integer> ports, List<Evidence> evidence, String why, List<String> known,
                          List<String> uncertain, List<String> nextSteps, String status, String analystNote) {}

    public record GNode(String id, String kind, String label, long observations, int degree, double betweenness, boolean articulation,
                        int component, String firstSeen, String lastSeen, String scope, boolean intelMatch, String maxSeverity) {}

    public record GEdge(String a, String b, String kind, long observations, List<Integer> ports) {}

    public record AlgoRun(String algorithm, String input, String output, double ms) {}

    public record GraphView(String computedAt, int nodes, int edges, int components, int largestComponent, int articulationPoints,
                            int bridges, List<GNode> nodeList, List<GEdge> edgeList, List<String> mostCentral, List<AlgoRun> runs,
                            boolean truncated, String note) {}

    public record PathResult(String from, String to, boolean connected, List<String> bfsPath, int bfsHops, int reachableFromSource,
                             int dfsVisited, List<String> dijkstraPath, double dijkstraCost, double fibonacciMs, double priorityQueueMs,
                             boolean queuesAgree, long disjointPathsEdmondsKarp, long disjointPathsFordFulkerson, boolean maxFlowEqualsMinCut,
                             List<String> minCutEdges, List<String> notes) {}

    public record ChangeSummary(int windowMinutes, int remotesNow, int remotesBefore, int newRemotes, int goneRemotes, int componentsNow,
                                int componentsBefore, List<String> newRemoteSample, String note) {}

    public record Ranking(int k, int candidates, int kthScore, List<String> topIds, long quickselectComparisons, boolean matchesSort,
                          int indexSize, int indexHeight, boolean indexValid) {}

    public record TimeSeries(int minutes, List<Long> perMinute, int queryFrom, int queryTo, long querySum, long queryMax, int queryMaxMinute,
                             boolean matchesScan, String note) {}

    public record Observation(String sensorId, String protocol, String localAddress, int localPort, String remoteAddress, int remotePort,
                              String state, String process, String firstSeen, String lastSeen, String lastIngested, long observations) {}

    public record Investigation(String query, String ip, String scope, boolean localObservationsIncluded, long observations, String firstSeen,
                                String lastSeen, List<Integer> ports, List<String> processes, List<String> sensors, List<IntelMatch> intel,
                                ThreatIntelService.AsnInfo asn, ThreatIntelService.AbuseInfo abuse, String abuseStatus,
                                List<Finding> findings, String assessment, List<String> known, List<String> uncertain, List<String> nextSteps,
                                String lookedUpAt) {}

    private final EventStore store;
    private final ThreatIntelService intel;
    private final Map<String, String[]> decisions = new ConcurrentHashMap<>();

    public AnalysisService(EventStore store, ThreatIntelService intel) {
        this.store = store;
        this.intel = intel;
    }

    // ---------------------------------------------------------------- findings

    public List<Finding> findings() {
        List<Flow> flows = store.flows();
        Instant now = store.now();
        Map<String, List<Flow>> byRemote = new LinkedHashMap<>();
        for (Flow f : flows) {
            byRemote.computeIfAbsent(f.remoteAddress, k -> new ArrayList<>()).add(f);
        }
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, List<Flow>> e : byRemote.entrySet()) {
            String ip = e.getKey();
            List<Flow> fs = e.getValue();
            if (IpAddr.scope(ip) == IpAddr.Scope.PUBLIC) {
                for (IntelMatch m : intel.matches(ip)) {
                    String rule = m.list().startsWith("abuse.ch") ? "INTEL_FEODO" : "INTEL_DROP";
                    out.add(finding(rule, "INTEL_MATCH", "HIGH", "MEDIUM", ip, "Connection to an address on the " + m.list() + " list", fs,
                            List.of(new Evidence("INTEL_MATCH", m.detail(), m.list() + " (" + m.sourceUrl() + ")", m.listUpdatedAt())),
                            "The remote address is on a public threat list that was downloaded by the server and matched exactly.",
                            List.of("A local sensor observed this connection " + total(fs) + " time(s).", "The address is on " + m.list() + "."),
                            List.of("Lists can be outdated or contain shared hosting / cloud addresses.", "Whether data was exchanged or a host is infected."),
                            List.of("Identify the process and user on the sensor host.", "Check the list entry and its dates.",
                                    "Look for other connections from the same process.", "Decide whether to isolate the host - TRACE never blocks on its own.")));
                }
            }
            Set<Integer> odd = new TreeSet<>();
            for (Flow f : fs) {
                if (f.protocol.equals("TCP") && !COMMON_PORTS.contains(f.remotePort) && f.remotePort < 49152 && f.remotePort > 0) {
                    odd.add(f.remotePort);
                }
            }
            if (!odd.isEmpty() && IpAddr.scope(ip) == IpAddr.Scope.PUBLIC) {
                out.add(finding("UNCOMMON_PORT", "HEURISTIC", "LOW", "LOW", ip, "Outbound connection on an uncommon port " + odd, fs,
                        List.of(new Evidence("OBSERVATION", "remote port(s) " + odd + " not in TRACE's common-service list", "sensor connection table",
                                latest(fs).toString())),
                        "Rule UNCOMMON_PORT: TCP to a public address on a port that is not a common service port and below 49152.",
                        List.of("The connection was observed by a sensor.", "The port is not in the documented common-port list."),
                        List.of("Many legitimate applications (games, VPNs, updaters) use other ports.", "The port alone says nothing about intent."),
                        List.of("Check which process opened it.", "Investigate the address (ASN, lists).", "Dismiss if the application is known.")));
            }
        }
        // fan-out per (sensor, process) in the recent window
        Map<String, Set<String>> fan = new HashMap<>();
        Map<String, List<Flow>> fanFlows = new HashMap<>();
        for (Flow f : flows) {
            if (f.lastSeen.isAfter(now.minus(FANOUT_WINDOW)) && IpAddr.scope(f.remoteAddress) == IpAddr.Scope.PUBLIC) {
                String key = f.sensorId + "/" + (f.process == null ? "(unknown process)" : f.process);
                fan.computeIfAbsent(key, k -> new TreeSet<>()).add(f.remoteAddress);
                fanFlows.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
            }
        }
        fan.forEach((key, remotes) -> {
            if (remotes.size() >= FANOUT_THRESHOLD) {
                List<Flow> fs = fanFlows.get(key);
                out.add(finding("HIGH_FANOUT", "HEURISTIC", "MEDIUM", "LOW", key, key + " contacted " + remotes.size()
                                + " distinct public addresses in " + FANOUT_WINDOW.toMinutes() + " min", fs,
                        List.of(new Evidence("OBSERVATION", remotes.size() + " distinct remote addresses", "sensor connection table", latest(fs).toString())),
                        "Rule HIGH_FANOUT: one process reached at least " + FANOUT_THRESHOLD + " distinct public addresses in the window.",
                        List.of("The count comes directly from observed connections."),
                        List.of("Browsers and cloud sync clients legitimately open many connections.", "Not evidence of scanning by itself."),
                        List.of("Compare with the process's normal behaviour.", "Check whether the destinations share an ASN or appear on lists.")));
            }
        });
        out.sort(Comparator.comparingInt((Finding f) -> f.score()).reversed().thenComparing(Finding::id));
        return out;
    }

    private Finding finding(String rule, String category, String severity, String confidence, String subject, String title, List<Flow> fs,
                            List<Evidence> extra, String why, List<String> known, List<String> uncertain, List<String> next) {
        long obs = total(fs);
        Set<String> sensors = new TreeSet<>();
        Set<String> procs = new TreeSet<>();
        Set<Integer> ports = new TreeSet<>();
        Instant first = null;
        Instant last = null;
        for (Flow f : fs) {
            sensors.add(f.sensorId);
            if (f.process != null) {
                procs.add(f.process);
            }
            ports.add(f.remotePort);
            first = first == null || f.firstSeen.isBefore(first) ? f.firstSeen : first;
            last = last == null || f.lastSeen.isAfter(last) ? f.lastSeen : last;
        }
        List<String> why2 = new ArrayList<>();
        int score = switch (severity) {
            case "HIGH" -> 70;
            case "MEDIUM" -> 40;
            default -> 15;
        };
        why2.add("+" + score + " severity " + severity);
        int c = switch (confidence) {
            case "HIGH" -> 20;
            case "MEDIUM" -> 10;
            default -> 0;
        };
        if (c > 0) {
            score += c;
            why2.add("+" + c + " confidence " + confidence);
        }
        int vol = (int) Math.min(10, Math.floor(Math.log(Math.max(1, obs)) / Math.log(2)));
        if (vol > 0) {
            score += vol;
            why2.add("+" + vol + " volume (" + obs + " observations)");
        }
        if (sensors.size() > 1) {
            score += 5;
            why2.add("+5 seen by " + sensors.size() + " sensors");
        }
        List<Evidence> ev = new ArrayList<>();
        ev.add(new Evidence("OBSERVATION", obs + " observation(s) of " + fs.size() + " connection(s), ports " + ports, "TRACE sensor(s) " + sensors,
                last == null ? null : last.toString()));
        ev.addAll(extra);
        String id = rule + ":" + subject;
        String[] d = decisions.get(id);
        return new Finding(id, rule, category, severity, confidence, score, why2, subject, title, str(first), str(last), obs, List.copyOf(sensors),
                List.copyOf(procs), List.copyOf(ports), ev, why, known, uncertain, next, d == null ? "OPEN" : d[0], d == null ? null : d[1]);
    }

    /** Analyst decision. CONFIRMED_INCIDENT can only come from a person, never from a rule. */
    public void decide(String findingId, String status, String note) {
        if (!Set.of("OPEN", "ACKNOWLEDGED", "DISMISSED", "CONFIRMED_INCIDENT").contains(status)) {
            throw new ValidationException("status", "OPEN, ACKNOWLEDGED, DISMISSED or CONFIRMED_INCIDENT");
        }
        if (findingId == null || findingId.length() > 120) {
            throw new ValidationException("id", "unknown finding id");
        }
        String n = note == null ? null : note.strip().substring(0, Math.min(300, note.strip().length()));
        decisions.put(findingId, new String[] {status, n});
    }

    public Ranking rank(List<Finding> fs, int k) {
        if (k < 1 || k > 100) {
            throw new ValidationException("k", "1-100");
        }
        List<Finding> active = fs.stream().filter(f -> !f.status().equals("DISMISSED")).sorted(Comparator.comparing(Finding::id)).toList();
        RedBlackTree<PriorityKey, Finding> index = new RedBlackTree<>();
        active.forEach(f -> index.put(new PriorityKey(f.score(), f.id()), f));
        boolean valid;
        try {
            index.validate();
            valid = true;
        } catch (IllegalStateException e) {
            valid = false;
        }
        if (active.isEmpty()) {
            return new Ranking(k, 0, 0, List.of(), 0, true, 0, index.height(), valid);
        }
        int kk = Math.min(k, active.size());
        double[] scores = active.stream().mapToDouble(Finding::score).toArray();
        RandomizedQuickselect.Selection sel = RandomizedQuickselect.select(scores, scores.length - kk, 20261121L);
        int kth = (int) sel.value();
        List<String> top = new ArrayList<>();
        index.forEachInOrder((key, f) -> { // red-black in-order = score descending, then id
            if (top.size() < kk && f.score() >= kth) {
                top.add(f.id());
            }
        });
        double[] sorted = scores.clone();
        java.util.Arrays.sort(sorted);
        return new Ranking(kk, active.size(), kth, top, sel.comparisons(), (int) sorted[sorted.length - kk] == kth, index.size(),
                index.height(), valid);
    }

    // ---------------------------------------------------------------- graph

    static final class Built {
        final List<String> ids = new ArrayList<>();
        final Map<String, Integer> index = new HashMap<>();
        final Map<String, String[]> meta = new HashMap<>(); // id -> kind,label,scope
        final Map<String, long[]> obs = new HashMap<>();
        final Map<String, Instant[]> seen = new HashMap<>();
        final List<String[]> edges = new ArrayList<>(); // a,b,kind
        final List<Long> edgeObs = new ArrayList<>();
        final List<Set<Integer>> edgePorts = new ArrayList<>();
        final Map<String, Integer> edgeIndex = new HashMap<>();
        RoadGraph g;

        int node(String id, String kind, String label, String scope) {
            Integer i = index.get(id);
            if (i == null) {
                i = ids.size();
                ids.add(id);
                index.put(id, i);
                meta.put(id, new String[] {kind, label, scope});
                obs.put(id, new long[1]);
            }
            return i;
        }

        void edge(String a, String b, String kind, long o, Integer port) {
            String key = a.compareTo(b) < 0 ? a + "\u0000" + b : b + "\u0000" + a;
            Integer i = edgeIndex.get(key);
            if (i == null) {
                i = edges.size();
                edgeIndex.put(key, i);
                edges.add(new String[] {a, b, kind});
                edgeObs.add(0L);
                edgePorts.add(new TreeSet<>());
            }
            edgeObs.set(i, edgeObs.get(i) + o);
            if (port != null) {
                edgePorts.get(i).add(port);
            }
        }
    }

    Built build() {
        Built b = new Built();
        for (Flow f : store.flows()) {
            String s = "sensor:" + f.sensorId;
            b.node(s, "SENSOR", f.sensorId, null);
            String r = "ip:" + f.remoteAddress;
            b.node(r, "REMOTE_IP", f.remoteAddress, IpAddr.scope(f.remoteAddress).name());
            String hub = s;
            if (f.process != null) {
                hub = "proc:" + f.sensorId + "/" + f.process;
                b.node(hub, "PROCESS", f.process + " @ " + f.sensorId, null);
                b.edge(s, hub, "RUNS", f.observations, null);
            }
            b.edge(hub, r, "CONNECTED_TO", f.observations, f.remotePort);
            for (String id : List.of(s, hub, r)) {
                b.obs.get(id)[0] += f.observations;
                Instant[] t = b.seen.computeIfAbsent(id, k -> new Instant[] {f.firstSeen, f.lastSeen});
                if (f.firstSeen.isBefore(t[0])) {
                    t[0] = f.firstSeen;
                }
                if (f.lastSeen.isAfter(t[1])) {
                    t[1] = f.lastSeen;
                }
            }
            ThreatIntelService.AsnInfo asn = intel.cachedAsn(f.remoteAddress);
            if (asn != null && !asn.asns().isEmpty()) {
                String a = "asn:" + asn.asns().get(0).split(" ")[0];
                b.node(a, "ASN", asn.asns().get(0), null);
                b.edge(r, a, "ANNOUNCED_BY", 0, null);
            }
        }
        int m = b.edges.size();
        int[] u = new int[m];
        int[] v = new int[m];
        boolean[] ow = new boolean[m];
        double[] w = new double[m];
        double[] cap = new double[m];
        for (int i = 0; i < m; i++) {
            u[i] = b.index.get(b.edges.get(i)[0]);
            v[i] = b.index.get(b.edges.get(i)[1]);
            long o = b.edgeObs.get(i);
            w[i] = o > 0 ? 1.0 / o : 1.0; // "evidence distance": frequently observed relationships are closer
            cap[i] = 1;
        }
        b.g = new RoadGraph(b.ids.size(), u, v, ow, w, w, cap);
        return b;
    }

    public GraphView graph(int maxNodes) {
        Built b = build();
        RoadGraph g = b.g;
        int n = g.nodeCount();
        List<AlgoRun> runs = new ArrayList<>();
        long t = System.nanoTime();
        DisjointSetUnion dsu = new DisjointSetUnion(Math.max(1, n));
        for (int e = 0; e < g.segmentCount(); e++) {
            dsu.union(g.segU(e), g.segV(e));
        }
        runs.add(new AlgoRun("Disjoint Set Union", n + " nodes, " + g.segmentCount() + " edges", (n == 0 ? 0 : dsu.componentCount()) + " components",
                ms(t)));
        t = System.nanoTime();
        GraphAlgorithms.CutStructure cut = GraphAlgorithms.articulationAndBridges(g);
        int art = 0;
        for (boolean x : cut.articulation()) {
            art += x ? 1 : 0;
        }
        runs.add(new AlgoRun("DFS low-link (Tarjan)", "undirected graph", art + " articulation points, " + cut.bridgeSegments().size() + " bridges", ms(t)));
        t = System.nanoTime();
        double[] bc = n <= 3000 ? GraphAlgorithms.betweenness(g, true) : new double[n];
        runs.add(new AlgoRun("Brandes betweenness", n <= 3000 ? "all nodes, unweighted" : "skipped (graph over 3000 nodes)",
                "centrality per node", ms(t)));
        Map<String, String> sev = new HashMap<>();
        for (Finding f : findings()) {
            String id = f.subject().contains("/") ? "proc:" + f.subject() : "ip:" + f.subject();
            sev.merge(id, f.severity(), (x, y) -> rankSev(x) >= rankSev(y) ? x : y);
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (x, y) -> Double.compare(bc[y], bc[x]));
        int[] degree = new int[n];
        for (int e = 0; e < g.segmentCount(); e++) {
            degree[g.segU(e)]++;
            degree[g.segV(e)]++;
        }
        Map<Integer, Integer> compIds = new HashMap<>();
        List<GNode> nodes = new ArrayList<>();
        Set<Integer> keep = new TreeSet<>();
        for (int i = 0; i < n && keep.size() < maxNodes; i++) {
            keep.add(order[i]);
        }
        int largest = 0;
        for (int i = 0; i < n; i++) {
            largest = Math.max(largest, dsu.componentSize(i));
        }
        for (int i : keep) {
            String id = b.ids.get(i);
            String[] mt = b.meta.get(id);
            Instant[] s = b.seen.get(id);
            int comp = compIds.computeIfAbsent(dsu.find(i), k -> compIds.size());
            boolean intelHit = mt[0].equals("REMOTE_IP") && !intel.matches(mt[1]).isEmpty();
            nodes.add(new GNode(id, mt[0], mt[1], b.obs.get(id)[0], degree[i], round(bc[i]), cut.articulation()[i], comp,
                    s == null ? null : s[0].toString(), s == null ? null : s[1].toString(), mt[2], intelHit, sev.get(id)));
        }
        Set<String> keptIds = new java.util.HashSet<>();
        nodes.forEach(x -> keptIds.add(x.id()));
        List<GEdge> edges = new ArrayList<>();
        for (int e = 0; e < b.edges.size(); e++) {
            String[] ed = b.edges.get(e);
            if (keptIds.contains(ed[0]) && keptIds.contains(ed[1])) {
                edges.add(new GEdge(ed[0], ed[1], ed[2], b.edgeObs.get(e), List.copyOf(b.edgePorts.get(e))));
            }
        }
        List<String> central = new ArrayList<>();
        for (int i = 0; i < Math.min(5, n); i++) {
            if (bc[order[i]] > 0) {
                central.add(b.meta.get(b.ids.get(order[i]))[1] + " (" + round(bc[order[i]]) + ")");
            }
        }
        return new GraphView(store.now().toString(), n, g.segmentCount(), n == 0 ? 0 : dsu.componentCount(), largest, art,
                cut.bridgeSegments().size(), nodes, edges, central, runs, n > maxNodes,
                "Nodes are sensors, processes, remote addresses and announcing networks (ASN, only after an address was investigated). "
                        + "Edges are observed relationships. Centrality and articulation points describe structure, not risk.");
    }

    public PathResult path(String fromId, String toId) {
        Built b = build();
        Integer s = b.index.get(fromId);
        Integer t = b.index.get(toId);
        if (s == null || t == null) {
            throw new ValidationException(s == null ? "from" : "to", "node not in the current graph");
        }
        if (s.equals(t)) {
            throw new ValidationException("to", "choose two different nodes");
        }
        RoadGraph g = b.g;
        GraphAlgorithms.BfsResult bfs = GraphAlgorithms.bfs(g, s);
        List<String> bfsPath = new ArrayList<>();
        GraphAlgorithms.path(g, bfs.parentArc(), s, t).forEach(i -> bfsPath.add(b.ids.get(i)));
        int dfs = GraphAlgorithms.dfsOrder(g, s).length;
        Dijkstra.Result fib = Dijkstra.run(g, s, t, RoadGraph.Metric.DISTANCE, Dijkstra.Queue.FIBONACCI);
        Dijkstra.Result bin = Dijkstra.run(g, s, t, RoadGraph.Metric.DISTANCE, Dijkstra.Queue.BINARY);
        List<String> dPath = new ArrayList<>();
        fib.pathTo(g, t).forEach(i -> dPath.add(b.ids.get(i)));
        FlowNetwork fn = new FlowNetwork(g.nodeCount());
        List<Integer> arcSeg = new ArrayList<>();
        for (int e = 0; e < g.segmentCount(); e++) {
            fn.addEdge(g.segU(e), g.segV(e), 1);
            arcSeg.add(e);
            fn.addEdge(g.segV(e), g.segU(e), 1);
            arcSeg.add(e);
        }
        MaxFlowResult ff = FordFulkerson.run(fn, s, t, TraceOptions.NONE);
        MaxFlowResult ek = EdmondsKarp.run(fn, s, t, TraceOptions.NONE);
        MinCut.Result mc = MinCut.fromResidual(fn, s);
        List<String> cutEdges = new ArrayList<>();
        for (int fe : mc.cutEdges()) {
            String[] ed = b.edges.get(arcSeg.get(fe));
            cutEdges.add(b.meta.get(ed[0])[1] + " - " + b.meta.get(ed[1])[1]);
        }
        boolean connected = bfs.hops()[t] >= 0;
        return new PathResult(fromId, toId, connected, bfsPath, bfs.hops()[t], bfs.reached(), dfs, dPath,
                Double.isInfinite(fib.dist()[t]) ? -1 : round(fib.dist()[t]), round(fib.elapsedMillis()), round(bin.elapsedMillis()),
                Math.abs(fib.dist()[t] - bin.dist()[t]) < 1e-9 || (Double.isInfinite(fib.dist()[t]) && Double.isInfinite(bin.dist()[t])),
                ek.maxFlow(), ff.maxFlow(), mc.cutCapacity() == ek.maxFlow(), cutEdges,
                List.of("BFS: fewest hops. Dijkstra: lowest 'evidence distance' (edge weight 1 / observations), so it prefers frequently observed links.",
                        "Max flow with capacity 1 per relationship = number of relationship-disjoint paths; the minimum cut is the smallest set of "
                                + "observed relationships that separates the two nodes. A bottleneck is a structural fact, not a vulnerability.",
                        "A* is not used here: these graph nodes have no coordinates, so there is no admissible distance heuristic. "
                                + "A* runs in the Algorithm Lab on the road graph, where straight-line distance is admissible."));
    }

    public ChangeSummary changes(int windowMinutes) {
        Instant now = store.now();
        Instant mid = now.minus(Duration.ofMinutes(windowMinutes));
        Instant start = mid.minus(Duration.ofMinutes(windowMinutes));
        Set<String> nowSet = new TreeSet<>();
        Set<String> before = new TreeSet<>();
        List<Flow> flows = store.flows();
        for (Flow f : flows) {
            if (!f.lastSeen.isBefore(mid)) {
                nowSet.add(f.remoteAddress);
            }
            if (f.firstSeen.isBefore(mid) && !f.lastSeen.isBefore(start)) {
                before.add(f.remoteAddress);
            }
        }
        Set<String> added = new TreeSet<>(nowSet);
        added.removeAll(before);
        Set<String> gone = new TreeSet<>(before);
        gone.removeAll(nowSet);
        return new ChangeSummary(windowMinutes, nowSet.size(), before.size(), added.size(), gone.size(), components(flows, mid, now),
                components(flows, start, mid), added.stream().limit(10).toList(),
                "Remote addresses seen in the last " + windowMinutes + " min compared with the previous " + windowMinutes
                        + " min; components computed with DSU over each window separately (DSU cannot delete edges, so each window is rebuilt).");
    }

    private static int components(List<Flow> flows, Instant from, Instant to) {
        Map<String, Integer> idx = new HashMap<>();
        List<int[]> es = new ArrayList<>();
        for (Flow f : flows) {
            if (f.lastSeen.isBefore(from) || f.firstSeen.isAfter(to)) {
                continue;
            }
            int a = idx.computeIfAbsent("s:" + f.sensorId + "/" + f.process, k -> idx.size());
            int c = idx.computeIfAbsent("r:" + f.remoteAddress, k -> idx.size());
            es.add(new int[] {a, c});
        }
        if (idx.isEmpty()) {
            return 0;
        }
        DisjointSetUnion d = new DisjointSetUnion(idx.size());
        es.forEach(e -> d.union(e[0], e[1]));
        return d.componentCount();
    }

    public TimeSeries timeSeries(int minutes, Integer from, Integer to) {
        if (minutes < 2 || minutes > 1440) {
            throw new ValidationException("minutes", "2-1440");
        }
        long[] v = store.perMinute(minutes);
        int a = from == null ? 0 : from;
        int z = to == null ? minutes - 1 : to;
        if (a < 0 || z >= minutes || a > z) {
            throw new ValidationException("from", "0 <= from <= to < minutes");
        }
        SegmentTree st = new SegmentTree(v);
        long sum = st.rangeSum(a, z);
        SegmentTree.MaxResult mx = st.rangeMax(a, z);
        long scan = 0;
        for (int i = a; i <= z; i++) {
            scan += v[i];
        }
        List<Long> list = new ArrayList<>();
        for (long x : v) {
            list.add(x);
        }
        return new TimeSeries(minutes, list, a, z, sum, mx.value(), mx.index(), sum == scan,
                "Observations per minute (by sensor observation time), index 0 = oldest minute. Range sum and max use the segment tree, "
                        + "checked against a linear scan.");
    }

    public List<Observation> observations(String ip, int limit) {
        String c = ip == null ? null : IpAddr.canonical(ip);
        List<Observation> out = new ArrayList<>();
        store.flows().stream().filter(f -> c == null || f.remoteAddress.equals(c))
                .sorted(Comparator.comparing((Flow f) -> f.lastSeen).reversed()).limit(limit)
                .forEach(f -> out.add(new Observation(f.sensorId, f.protocol, f.localAddress, f.localPort, f.remoteAddress, f.remotePort,
                        f.lastState, f.process, f.firstSeen.toString(), f.lastSeen.toString(), str(f.lastIngested), f.observations)));
        return out;
    }

    public Investigation investigate(String query, boolean includeLocal, boolean allowExternal) {
        String ip = IpAddr.canonical(query);
        if (ip == null) {
            throw new ValidationException("ip", "enter an IPv4 or IPv6 address (host names are not resolved)");
        }
        IpAddr.Scope scope = IpAddr.scope(ip);
        List<Flow> fs = includeLocal ? store.flows().stream().filter(f -> f.remoteAddress.equals(ip)).toList() : List.of();
        Set<Integer> ports = new TreeSet<>();
        Set<String> procs = new TreeSet<>();
        Set<String> sensors = new TreeSet<>();
        Instant first = null;
        Instant last = null;
        for (Flow f : fs) {
            ports.add(f.remotePort);
            if (f.process != null) {
                procs.add(f.process);
            }
            sensors.add(f.sensorId);
            first = first == null || f.firstSeen.isBefore(first) ? f.firstSeen : first;
            last = last == null || f.lastSeen.isAfter(last) ? f.lastSeen : last;
        }
        boolean pub = scope == IpAddr.Scope.PUBLIC;
        List<IntelMatch> matches = pub ? intel.matches(ip) : List.of();
        ThreatIntelService.AsnInfo asn = pub && allowExternal ? intel.asn(ip) : null;
        ThreatIntelService.AbuseInfo abuse = pub && allowExternal ? intel.abuse(ip) : null;
        String abuseStatus = !intel.abuseConfigured() ? "not configured (ABUSEIPDB_API_KEY)" : !pub ? "not queried (non-public address)"
                : abuse == null ? "lookup failed or cap reached" : "ok";
        List<Finding> related = includeLocal ? findings().stream().filter(f -> f.subject().equals(ip)).toList() : List.of();
        List<String> known = new ArrayList<>();
        List<String> uncertain = new ArrayList<>();
        List<String> next = new ArrayList<>();
        known.add("Address scope: " + scope + (pub ? "" : " (never sent to external services)"));
        if (includeLocal) {
            known.add(fs.isEmpty() ? "No sensor observed this address in the retention window." : "Observed " + total(fs) + " time(s) by " + sensors);
        } else {
            uncertain.add("Local observations are hidden: sign in with the viewer token to include them.");
        }
        matches.forEach(m -> known.add("Listed on " + m.list() + ": " + m.detail()));
        if (pub && matches.isEmpty()) {
            known.add("Not on the Feodo Tracker or Spamhaus DROP lists as downloaded at " + firstNonNull(intel.status().get(0).lastSuccessAt(), "(not downloaded yet)") + ".");
        }
        if (asn != null && !asn.asns().isEmpty()) {
            known.add("Announced by " + String.join(", ", asn.asns()) + " (prefix " + asn.prefix() + ", RIPEstat).");
        }
        if (abuse != null) {
            known.add("AbuseIPDB confidence score " + abuse.abuseConfidenceScore() + "/100 from " + abuse.totalReports() + " report(s) in 90 days.");
        }
        uncertain.add("An IP address can be shared (cloud, CDN, NAT) and does not identify a person or organisation by itself.");
        uncertain.add("Absence from lists does not mean the address is safe; list presence does not prove compromise.");
        String assessment;
        if (!matches.isEmpty()) {
            assessment = "Threat-list match (evidence of concern, not proof). Severity would be high if the listing is current; confidence is medium.";
            next.add("Find the process that made the connection and check it on the host.");
        } else if (abuse != null && abuse.abuseConfidenceScore() >= 50) {
            assessment = "Community abuse reports exist (score " + abuse.abuseConfidenceScore() + "). Evidence of concern, not proof.";
            next.add("Read the abuse categories on AbuseIPDB and compare with what the host was doing.");
        } else if (!pub) {
            assessment = "Non-public address (" + scope + "): it is not on the internet, so it was not checked against external lists. "
                    + "Judge it from local context (which device uses it, and why).";
        } else {
            assessment = "No reputation evidence of concern was found in the configured sources.";
        }
        next.add("Review the connection history and related nodes in the Network view.");
        return new Investigation(query, ip, scope.name(), includeLocal, total(fs), str(first), str(last), List.copyOf(ports), List.copyOf(procs),
                List.copyOf(sensors), matches, asn, abuse, abuseStatus, related, assessment, known, uncertain, next, store.now().toString());
    }

    /** Result of looking up an IP literal or a domain name. For a domain, every resolved address is investigated. */
    public record Lookup(String query, String kind, String domain, String resolvedAt, String dnsError, List<Investigation> results,
                         String note) {}

    private static final java.util.regex.Pattern HOST = java.util.regex.Pattern.compile(
            "(?i)^(?=.{4,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]$");

    /** Resolver hook (DNS only; TRACE never connects to the host). Replaceable in tests. */
    @FunctionalInterface
    public interface Resolver {
        List<String> resolve(String host) throws Exception;
    }

    private Resolver resolver = host -> {
        java.util.concurrent.CompletableFuture<java.net.InetAddress[]> f =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        return java.net.InetAddress.getAllByName(host);
                    } catch (java.net.UnknownHostException e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                });
        java.net.InetAddress[] all = f.get(4, java.util.concurrent.TimeUnit.SECONDS);
        List<String> out = new ArrayList<>();
        for (java.net.InetAddress a : all) {
            String c = IpAddr.canonical(a.getHostAddress());
            if (c != null && !out.contains(c)) {
                out.add(c);
            }
        }
        return out;
    };

    void setResolver(Resolver r) {
        this.resolver = r;
    }

    public Lookup lookup(String query, boolean includeLocal) {
        String q = query == null ? "" : query.strip().toLowerCase();
        if (q.endsWith(".")) {
            q = q.substring(0, q.length() - 1);
        }
        if (IpAddr.canonical(q) != null) {
            return new Lookup(query, "IP", null, null, null, List.of(investigate(q, includeLocal, true)),
                    "IP address checked directly.");
        }
        if (!HOST.matcher(q).matches()) {
            throw new ValidationException("q", "enter an IPv4/IPv6 address or a domain name such as example.com (no URLs, ports or paths)");
        }
        List<String> addrs;
        String err = null;
        try {
            addrs = resolver.resolve(q);
        } catch (Exception e) {
            addrs = List.of();
            Throwable c = e.getCause() != null ? e.getCause() : e;
            err = c instanceof java.net.UnknownHostException ? "name does not resolve (NXDOMAIN or no address records)"
                    : c instanceof java.util.concurrent.TimeoutException || e instanceof java.util.concurrent.TimeoutException
                    ? "DNS lookup timed out after 4 s" : "DNS lookup failed";
        }
        List<Investigation> res = new ArrayList<>();
        for (String a : addrs.stream().limit(8).toList()) {
            res.add(investigate(a, includeLocal, true));
        }
        return new Lookup(query, "DOMAIN", q, store.now().toString(), err, res,
                "Resolved with the server's DNS resolver at lookup time; results can differ by location and time (CDNs, load balancing). "
                        + "TRACE only queries DNS and the reputation sources; it never connects to the host. A domain can share addresses with "
                        + "unrelated sites, so an address-level match does not by itself condemn the domain.");
    }

    // ---------------------------------------------------------------- helpers
    private static long total(List<Flow> fs) {
        long t = 0;
        for (Flow f : fs) {
            t += f.observations;
        }
        return t;
    }

    private static Instant latest(List<Flow> fs) {
        Instant l = null;
        for (Flow f : fs) {
            l = l == null || f.lastSeen.isAfter(l) ? f.lastSeen : l;
        }
        return l;
    }

    private static int rankSev(String s) {
        return switch (s) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String str(Instant i) {
        return i == null ? null : i.toString();
    }

    private static double ms(long t0) {
        return round((System.nanoTime() - t0) / 1e6);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
