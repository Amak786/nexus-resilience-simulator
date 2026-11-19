package edu.nexus.service;

import edu.nexus.algorithms.flow.FlowNetwork;
import edu.nexus.model.CityDataset;
import edu.nexus.model.CityNode;
import edu.nexus.model.NodeType;
import edu.nexus.model.Road;
import edu.nexus.model.ValidationException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the <b>directed capacity network</b> from a city dataset. This is the documented modelling rule:
 *
 * <ol>
 *   <li>Every non-failed road with both endpoints operating becomes an arc {@code from -> to} with its
 *       capacity (or the override). A two-way road also becomes an arc {@code to -> from} with the same
 *       capacity: capacity is per direction.</li>
 *   <li>If the source is {@code SUPER_SOURCE}, an arc {@code SUPER_SOURCE -> depot} with capacity equal to
 *       the depot's supply is added for every operating depot.</li>
 *   <li>If the sink is {@code SUPER_SINK}, an arc {@code zone -> SUPER_SINK} with capacity equal to the
 *       zone's demand is added for every operating demand zone.</li>
 *   <li>A failed node loses every incident arc. A failed road is removed.</li>
 * </ol>
 *
 * <p>With SUPER_SOURCE and SUPER_SINK, the max flow is the largest number of relief units that can reach
 * demand zones in one planning window, given stock and road throughput. It is a single-commodity,
 * single-period model: it ignores travel time, vehicle routing, perishability and fairness between
 * zones, so it does not solve the full multi-depot inventory problem.
 */
public final class CityNetwork {

    public static final String SUPER_SOURCE = "SUPER_SOURCE";
    public static final String SUPER_SINK = "SUPER_SINK";

    public enum EdgeKind { SUPPLY, ROAD, DEMAND }

    /** Domain meaning of one flow-network edge. */
    public record EdgeInfo(int edge, EdgeKind kind, String roadId, String from, String to, long capacity) {}

    private final FlowNetwork net;
    private final List<EdgeInfo> edges;
    private final List<String> labels;
    private final Map<String, Integer> index;
    private final int source;
    private final int sink;

    private CityNetwork(FlowNetwork net, List<EdgeInfo> edges, List<String> labels, Map<String, Integer> index,
                        int source, int sink) {
        this.net = net;
        this.edges = edges;
        this.labels = labels;
        this.index = index;
        this.source = source;
        this.sink = sink;
    }

    public static CityNetwork build(CityDataset ds, Map<String, CityNode> byId, String sourceId, String sinkId,
                                    Set<String> failedRoads, Set<String> failedNodes, Map<String, Long> overrides) {
        String src = sourceId == null ? SUPER_SOURCE : sourceId;
        String snk = sinkId == null ? SUPER_SINK : sinkId;
        if (!src.equals(SUPER_SOURCE) && !byId.containsKey(src)) {
            throw new ValidationException("source", "must be SUPER_SOURCE or a node id; got '" + src + "'");
        }
        if (!snk.equals(SUPER_SINK) && !byId.containsKey(snk)) {
            throw new ValidationException("sink", "must be SUPER_SINK or a node id; got '" + snk + "'");
        }
        if (src.equals(snk)) {
            throw new ValidationException("sink", "source and sink must be different");
        }
        if (failedNodes.contains(src)) {
            throw new ValidationException("source", "the source node is marked as failed");
        }
        if (failedNodes.contains(snk)) {
            throw new ValidationException("sink", "the sink node is marked as failed");
        }
        List<CityNode> nodes = ds.nodes();
        int n = nodes.size();
        List<String> labels = new ArrayList<>(n + 2);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < n; i++) {
            labels.add(nodes.get(i).id());
            index.put(nodes.get(i).id(), i);
        }
        labels.add(SUPER_SOURCE);
        labels.add(SUPER_SINK);
        index.put(SUPER_SOURCE, n);
        index.put(SUPER_SINK, n + 1);
        FlowNetwork net = new FlowNetwork(n + 2);
        List<EdgeInfo> edges = new ArrayList<>();
        if (src.equals(SUPER_SOURCE)) {
            for (CityNode node : nodes) {
                if (node.type() == NodeType.DEPOT && !failedNodes.contains(node.id())) {
                    int e = net.addEdge(n, index.get(node.id()), node.supply());
                    edges.add(new EdgeInfo(e, EdgeKind.SUPPLY, null, SUPER_SOURCE, node.id(), node.supply()));
                }
            }
        }
        if (snk.equals(SUPER_SINK)) {
            for (CityNode node : nodes) {
                if (node.type() == NodeType.DEMAND_ZONE && !failedNodes.contains(node.id())) {
                    int e = net.addEdge(index.get(node.id()), n + 1, node.demand());
                    edges.add(new EdgeInfo(e, EdgeKind.DEMAND, null, node.id(), SUPER_SINK, node.demand()));
                }
            }
        }
        List<Road> roads = ds.roads() == null ? List.of() : ds.roads();
        for (Road r : roads) {
            if (failedRoads.contains(r.id()) || failedNodes.contains(r.from()) || failedNodes.contains(r.to())) {
                continue;
            }
            long cap = overrides.getOrDefault(r.id(), r.capacity());
            int u = index.get(r.from());
            int v = index.get(r.to());
            int e = net.addEdge(u, v, cap);
            edges.add(new EdgeInfo(e, EdgeKind.ROAD, r.id(), r.from(), r.to(), cap));
            if (!r.oneWayOnly()) {
                int e2 = net.addEdge(v, u, cap);
                edges.add(new EdgeInfo(e2, EdgeKind.ROAD, r.id(), r.to(), r.from(), cap));
            }
        }
        edges.sort((a, b) -> Integer.compare(a.edge(), b.edge()));
        return new CityNetwork(net, List.copyOf(edges), List.copyOf(labels), index, index.get(src), index.get(snk));
    }

    public FlowNetwork net() {
        return net;
    }

    public List<EdgeInfo> edges() {
        return edges;
    }

    public List<String> labels() {
        return labels;
    }

    public int source() {
        return source;
    }

    public int sink() {
        return sink;
    }

    public int indexOf(String id) {
        return index.get(id);
    }

    public boolean superSource() {
        return labels.get(source).equals(SUPER_SOURCE);
    }

    public boolean superSink() {
        return labels.get(sink).equals(SUPER_SINK);
    }
}
