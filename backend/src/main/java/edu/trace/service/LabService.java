package edu.trace.service;

import edu.trace.algorithms.dsu.DisjointSetUnion;
import edu.trace.algorithms.flow.EdmondsKarp;
import edu.trace.algorithms.flow.FlowNetwork;
import edu.trace.algorithms.flow.FordFulkerson;
import edu.trace.algorithms.flow.MaxFlowResult;
import edu.trace.algorithms.flow.MinCut;
import edu.trace.algorithms.flow.TraceOptions;
import edu.trace.algorithms.rbtree.RedBlackTree;
import edu.trace.algorithms.select.RandomizedQuickselect;
import edu.trace.dto.LabDtos.RunStats;
import edu.trace.dto.LabDtos.TraceArc;
import edu.trace.dto.LabDtos.TraceStep;
import edu.trace.dto.LabDtos.DsuOpResult;
import edu.trace.dto.LabDtos.DsuOperation;
import edu.trace.dto.LabDtos.LabDsuRequest;
import edu.trace.dto.LabDtos.LabDsuResponse;
import edu.trace.dto.LabDtos.LabEdge;
import edu.trace.dto.LabDtos.LabEdgeResult;
import edu.trace.dto.LabDtos.LabFlowRequest;
import edu.trace.dto.LabDtos.LabFlowResponse;
import edu.trace.dto.LabDtos.LabRbRequest;
import edu.trace.dto.LabDtos.LabRbResponse;
import edu.trace.dto.LabDtos.LabSelectRequest;
import edu.trace.dto.LabDtos.LabSelectResponse;
import edu.trace.dto.LabDtos.RbOpResult;
import edu.trace.dto.LabDtos.RbOperation;
import edu.trace.dto.LabDtos.RunTrace;
import edu.trace.dto.LabDtos.SelectResult;
import edu.trace.model.ValidationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the Java algorithms on small user-defined inputs and returns their traces. */
@Service
public class LabService {

    public static final long MAX_CAPACITY = 1_000_000_000L;
    public static final int MAX_LAB_NODES = 200;
    public static final int MAX_LAB_EDGES = 2000;
    public static final int MAX_DSU_ELEMENTS = 100_000;
    public static final int MAX_OPS = 300;
    public static final int MAX_SELECT_VALUES = 1_000_000;

    public LabFlowResponse flow(LabFlowRequest req) {
        if (req == null || req.nodes() == null || req.nodes().isEmpty()) {
            throw new ValidationException("nodes", "list at least one node");
        }
        if (req.nodes().size() > MAX_LAB_NODES) {
            throw new ValidationException("nodes", "at most " + MAX_LAB_NODES + " nodes");
        }
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < req.nodes().size(); i++) {
            String id = req.nodes().get(i);
            if (id == null || id.isBlank() || id.length() > 32) {
                throw new ValidationException("nodes[" + i + "]", "must be 1-32 characters");
            }
            if (idx.put(id, i) != null) {
                throw new ValidationException("nodes[" + i + "]", "duplicate node '" + id + "'");
            }
        }
        List<LabEdge> edges = req.edges() == null ? List.of() : req.edges();
        if (edges.size() > MAX_LAB_EDGES) {
            throw new ValidationException("edges", "at most " + MAX_LAB_EDGES + " edges");
        }
        Integer s = idx.get(req.source());
        Integer t = idx.get(req.sink());
        if (s == null) {
            throw new ValidationException("source", "must be one of the listed nodes");
        }
        if (t == null) {
            throw new ValidationException("sink", "must be one of the listed nodes");
        }
        if (s.equals(t)) {
            throw new ValidationException("sink", "source and sink must be different (a one-node graph has no s-t flow)");
        }
        FlowNetwork g = new FlowNetwork(idx.size());
        for (int i = 0; i < edges.size(); i++) {
            LabEdge e = edges.get(i);
            String f = "edges[" + i + "]";
            if (e == null || !idx.containsKey(e.from()) || !idx.containsKey(e.to())) {
                throw new ValidationException(f, "endpoints must be listed nodes");
            }
            if (e.capacity() == null || e.capacity() < 0 || e.capacity() > MAX_CAPACITY) {
                throw new ValidationException(f + ".capacity", "must be an integer between 0 and " + MAX_CAPACITY);
            }
            g.addEdge(idx.get(e.from()), idx.get(e.to()), e.capacity());
        }
        TraceOptions opts = new TraceOptions(200, edges.size() <= 400);
        List<String> labels = new ArrayList<>(idx.keySet());
        MaxFlowResult ff = FordFulkerson.run(g, s, t, opts);
        long[] ffFlows = g.edgeFlows();
        MinCut.verifyFlow(g, s, t);
        MaxFlowResult ek = EdmondsKarp.run(g, s, t, opts);
        MinCut.verifyFlow(g, s, t);
        MinCut.Result cut = MinCut.fromResidual(g, s);
        List<LabEdgeResult> out = new ArrayList<>();
        java.util.Set<Integer> cutSet = new java.util.HashSet<>(cut.cutEdges());
        for (int e = 0; e < g.edgeCount(); e++) {
            out.add(new LabEdgeResult(e, labels.get(g.edgeFrom(e)), labels.get(g.edgeTo(e)), g.edgeCapacity(e),
                    g.edgeFlow(e), ffFlows[e], cutSet.contains(e)));
        }
        List<String> side = new ArrayList<>();
        for (int v = 0; v < labels.size(); v++) {
            if (cut.sourceSide()[v]) {
                side.add(labels.get(v));
            }
        }
        return new LabFlowResponse(labels, out, trace(ek, labels), trace(ff, labels), cut.cutCapacity(), side,
                cut.cutCapacity() == ek.maxFlow(), ff.maxFlow() == ek.maxFlow(),
                List.of("Both algorithms must reach the same max-flow value; their augmenting paths, path count and per-edge flows may differ.",
                        "Ford-Fulkerson here uses DFS, so its augmentation count can depend on capacity values; Edmonds-Karp's is bounded by O(VE).",
                        "Min cut = edges from residual-reachable nodes to the rest, computed after Edmonds-Karp."));
    }

    public LabDsuResponse dsu(LabDsuRequest req) {
        if (req == null || req.elementCount() == null || req.elementCount() < 1 || req.elementCount() > MAX_DSU_ELEMENTS) {
            throw new ValidationException("elementCount", "must be between 1 and " + MAX_DSU_ELEMENTS);
        }
        int n = req.elementCount();
        List<DsuOperation> ops = req.operations() == null ? List.of() : req.operations();
        if (ops.size() > MAX_OPS) {
            throw new ValidationException("operations", "at most " + MAX_OPS + " operations");
        }
        DisjointSetUnion d = new DisjointSetUnion(n);
        boolean snap = n <= 64;
        List<DsuOpResult> results = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            DsuOperation op = ops.get(i);
            String f = "operations[" + i + "]";
            if (op == null || op.type() == null) {
                throw new ValidationException(f + ".type", "must be UNION, FIND or CONNECTED");
            }
            int a = element(op.a(), n, f + ".a");
            String result;
            switch (op.type()) {
                case "UNION" -> {
                    int b = element(op.b(), n, f + ".b");
                    result = d.union(a, b) ? "merged" : "already in the same set (repeated union, no change)";
                }
                case "FIND" -> result = "root " + d.find(a);
                case "CONNECTED" -> {
                    int b = element(op.b(), n, f + ".b");
                    result = d.connected(a, b) ? "connected" : "not connected";
                }
                default -> throw new ValidationException(f + ".type", "must be UNION, FIND or CONNECTED");
            }
            results.add(new DsuOpResult(i, op.type(), a, op.b(), result, d.componentCount(), snap ? boxed(d.parentSnapshot()) : null));
        }
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int v = 0; v < n && n <= 1000; v++) {
            groups.computeIfAbsent(d.find(v), k -> new ArrayList<>()).add(v);
        }
        List<Integer> ranks = new ArrayList<>();
        for (int v = 0; v < n && snap; v++) {
            ranks.add(d.rankOf(v));
        }
        return new LabDsuResponse(n, results, snap ? boxed(d.parentSnapshot()) : null, snap ? ranks : null,
                d.componentCount(), new ArrayList<>(groups.values()), d.pointerHops());
    }

    public LabRbResponse rbtree(LabRbRequest req) {
        List<RbOperation> ops = req == null || req.operations() == null ? List.of() : req.operations();
        if (ops.size() > MAX_OPS) {
            throw new ValidationException("operations", "at most " + MAX_OPS + " operations");
        }
        RedBlackTree<Integer, Integer> tree = new RedBlackTree<>();
        List<RbOpResult> results = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            RbOperation op = ops.get(i);
            String f = "operations[" + i + "]";
            if (op == null || op.type() == null) {
                throw new ValidationException(f + ".type", "must be INSERT, DELETE or LOOKUP");
            }
            if (op.key() == null || Math.abs(op.key()) > 1_000_000) {
                throw new ValidationException(f + ".key", "must be an integer with |key| <= 1000000");
            }
            List<String> events = new ArrayList<>();
            tree.setEventLog(events);
            long before = tree.rotationCount();
            String outcome = switch (op.type()) {
                case "INSERT" -> tree.put(op.key(), op.key()) ? "inserted" : "already present (value replaced)";
                case "DELETE" -> tree.remove(op.key()) ? "deleted" : "not present";
                case "LOOKUP" -> tree.containsKey(op.key()) ? "found at rank " + tree.rank(op.key()) : "not found";
                default -> throw new ValidationException(f + ".type", "must be INSERT, DELETE or LOOKUP");
            };
            tree.setEventLog(null);
            boolean ok = true;
            try {
                tree.validate();
            } catch (IllegalStateException ex) {
                ok = false;
            }
            results.add(new RbOpResult(i, op.type(), op.key(), outcome, tree.rotationCount() - before, events, ok));
        }
        return new LabRbResponse(results, tree.snapshot(), tree.keysInOrder(), tree.size(), tree.height(),
                tree.blackHeight(), tree.rotationCount());
    }

    public LabSelectResponse quickselect(LabSelectRequest req) {
        if (req == null || req.values() == null || req.values().isEmpty()) {
            throw new ValidationException("values", "enter at least one number");
        }
        if (req.values().size() > MAX_SELECT_VALUES) {
            throw new ValidationException("values", "at most " + MAX_SELECT_VALUES + " values");
        }
        double[] v = new double[req.values().size()];
        for (int i = 0; i < v.length; i++) {
            Double x = req.values().get(i);
            if (x == null || !Double.isFinite(x)) {
                throw new ValidationException("values[" + i + "]", "must be a finite number");
            }
            v[i] = x;
        }
        List<Double> ps = req.percentiles() == null || req.percentiles().isEmpty() ? List.of(50.0, 90.0, 95.0) : req.percentiles();
        if (ps.size() > 20) {
            throw new ValidationException("percentiles", "at most 20 percentiles");
        }
        long seed = Inputs.longParam(req.seed(), 2026L, 0, Long.MAX_VALUE, "seed");
        double[] sorted = v.clone();
        Arrays.sort(sorted);
        List<SelectResult> out = new ArrayList<>();
        for (int i = 0; i < ps.size(); i++) {
            Double p = ps.get(i);
            if (p == null || !(p >= 0 && p <= 100)) {
                throw new ValidationException("percentiles[" + i + "]", "must be in [0, 100]");
            }
            RandomizedQuickselect.Selection sel = RandomizedQuickselect.percentile(v, p, seed);
            int rank = Math.max(1, (int) Math.ceil(p / 100.0 * v.length));
            out.add(new SelectResult(p, rank, sel.value(), sel.comparisons(), sorted[rank - 1], sel.value() == sorted[rank - 1]));
        }
        long n = v.length;
        return new LabSelectResponse(v.length, out, Math.round(n * Math.max(1, Math.log(n) / Math.log(2))),
                List.of("Nearest-rank percentile: value at rank ceil(p/100 * n).",
                        "Quickselect expected O(n) comparisons; the sort baseline is O(n log n) (estimate shown as n log2 n).",
                        "Same seed gives the same pivot sequence and comparison count; the selected value never depends on the seed."));
    }

    /** Runs a sequence of Fibonacci-heap operations and reports the root list and invariant check after each. */
    public edu.trace.dto.LabDtos.LabHeapResponse fibonacciHeap(edu.trace.dto.LabDtos.LabHeapRequest req) {
        List<edu.trace.dto.LabDtos.HeapOperation> ops = req == null || req.operations() == null ? List.of() : req.operations();
        if (ops.size() > MAX_OPS) {
            throw new ValidationException("operations", "at most " + MAX_OPS + " operations");
        }
        edu.trace.algorithms.heap.FibonacciHeap<Integer> heap = new edu.trace.algorithms.heap.FibonacciHeap<>();
        List<edu.trace.algorithms.heap.FibonacciHeap.Node<Integer>> handles = new ArrayList<>();
        List<edu.trace.dto.LabDtos.HeapOpResult> out = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            edu.trace.dto.LabDtos.HeapOperation op = ops.get(i);
            String f = "operations[" + i + "]";
            if (op == null || op.type() == null) {
                throw new ValidationException(f + ".type", "must be INSERT, EXTRACT_MIN or DECREASE_KEY");
            }
            String outcome;
            switch (op.type()) {
                case "INSERT" -> {
                    if (op.key() == null || !Double.isFinite(op.key())) {
                        throw new ValidationException(f + ".key", "must be a finite number");
                    }
                    handles.add(heap.insert(op.key(), handles.size()));
                    outcome = "inserted key " + op.key() + " as handle " + (handles.size() - 1);
                }
                case "EXTRACT_MIN" -> {
                    if (heap.isEmpty()) {
                        throw new ValidationException(f, "cannot extract from an empty heap");
                    }
                    edu.trace.algorithms.heap.FibonacciHeap.Node<Integer> m = heap.extractMin();
                    outcome = "extracted key " + m.key() + " (handle " + m.value() + "), then consolidated the root list";
                }
                case "DECREASE_KEY" -> {
                    if (op.handle() == null || op.handle() < 0 || op.handle() >= handles.size()) {
                        throw new ValidationException(f + ".handle", "must be the number of an earlier INSERT (0-based)");
                    }
                    if (op.key() == null || !Double.isFinite(op.key())) {
                        throw new ValidationException(f + ".key", "must be a finite number");
                    }
                    try {
                        long cutsBefore = heap.cutCount();
                        heap.decreaseKey(handles.get(op.handle()), op.key());
                        long cuts = heap.cutCount() - cutsBefore;
                        outcome = "handle " + op.handle() + " now has key " + op.key()
                                + (cuts == 0 ? " (heap order kept, no cut)" : " (" + cuts + " cut(s), incl. cascading)");
                    } catch (IllegalArgumentException ex) {
                        throw new ValidationException(f, ex.getMessage());
                    }
                }
                default -> throw new ValidationException(f + ".type", "must be INSERT, EXTRACT_MIN or DECREASE_KEY");
            }
            boolean ok = true;
            try {
                heap.validate();
            } catch (IllegalStateException ex) {
                ok = false;
            }
            out.add(new edu.trace.dto.LabDtos.HeapOpResult(i, op.type(), outcome, heap.size(), heap.rootDegrees(),
                    heap.linkCount(), heap.cutCount(), heap.cascadingCutCount(), ok));
        }
        return new edu.trace.dto.LabDtos.LabHeapResponse(out, heap.size(),
                "insert and decrease-key are O(1) amortized; extract-min is O(log n) amortized because it consolidates roots of equal degree.");
    }

    /** Bloom filter on user-supplied sample ids, every possible hit confirmed by an exact HashSet. */
    public edu.trace.dto.LabDtos.LabBloomResponse bloom(edu.trace.dto.LabDtos.LabBloomRequest req) {
        if (req == null || req.inserted() == null || req.inserted().isEmpty()) {
            throw new ValidationException("inserted", "enter at least one id to insert");
        }
        if (req.inserted().size() > 10_000 || (req.queries() != null && req.queries().size() > 1_000)) {
            throw new ValidationException("inserted", "at most 10,000 inserted and 1,000 queried ids");
        }
        double p = Inputs.doubleParam(req.falsePositiveRate(), 0.01, 1e-6, 0.5, "falsePositiveRate");
        int probes = Inputs.intParam(req.probes(), 10_000, 0, 100_000, "probes");
        java.util.Set<String> exact = new java.util.HashSet<>();
        for (int i = 0; i < req.inserted().size(); i++) {
            String id = req.inserted().get(i);
            if (id == null || id.isBlank() || id.length() > 64) {
                throw new ValidationException("inserted[" + i + "]", "ids must be 1-64 non-blank characters");
            }
            exact.add(id.trim());
        }
        edu.trace.algorithms.bloom.BloomFilter bf = edu.trace.algorithms.bloom.BloomFilter.optimal(exact.size(), p, 2026L);
        exact.forEach(bf::add);
        List<edu.trace.dto.LabDtos.BloomQueryResult> results = new ArrayList<>();
        for (String raw : req.queries() == null ? List.<String>of() : req.queries()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String id = raw.trim();
            boolean maybe = bf.mightContain(id);
            boolean real = exact.contains(id);
            String verdict = !maybe ? "definitely absent (no exact lookup needed)"
                    : real ? "possibly present -> exact store confirms: duplicate" : "possibly present -> exact store says no: FALSE POSITIVE";
            results.add(new edu.trace.dto.LabDtos.BloomQueryResult(id, maybe ? "POSSIBLY_PRESENT" : "DEFINITELY_ABSENT", real, verdict));
        }
        int fp = 0;
        int tested = 0;
        for (int i = 0; tested < probes; i++) {
            String probe = "__probe-" + i;
            if (exact.contains(probe)) {
                continue;
            }
            tested++;
            if (bf.mightContain(probe)) {
                fp++;
            }
        }
        return new edu.trace.dto.LabDtos.LabBloomResponse(bf.bitCount(), bf.hashCount(), exact.size(),
                Math.round(bf.theoreticalFalsePositiveProbability() * 1e6) / 1e6, tested, fp,
                tested == 0 ? 0 : Math.round(1e6 * fp / tested) / 1e6, results,
                "SAMPLE data. A Bloom filter never misses an inserted id (no false negatives here), but can say 'possibly present' for an id "
                        + "it never saw (false positive), so the exact HashSet is always the final answer.");
    }

    private static RunTrace trace(MaxFlowResult r, List<String> labels) {
        List<TraceStep> steps = new ArrayList<>();
        for (MaxFlowResult.AugmentingStep s : r.steps()) {
            List<String> nodes = s.nodes().stream().map(labels::get).toList();
            List<TraceArc> arcs = s.arcs().stream().map(a -> new TraceArc(a.edge(), a.reverse())).toList();
            steps.add(new TraceStep(s.step(), nodes, arcs, s.bottleneck(), s.flowAfter(), s.edgeFlows()));
        }
        return new RunTrace(new RunStats(r.algorithm(), r.maxFlow(), r.augmentations(), r.arcScans(),
                Math.round(r.elapsedMillis() * 1000) / 1000.0), steps, r.traceTruncated());
    }

    private static int element(Integer x, int n, String field) {
        if (x == null || x < 0 || x >= n) {
            throw new ValidationException(field, "must be an element in [0, " + (n - 1) + "]");
        }
        return x;
    }

    private static List<Integer> boxed(int[] a) {
        List<Integer> out = new ArrayList<>(a.length);
        for (int v : a) {
            out.add(v);
        }
        return out;
    }

}
