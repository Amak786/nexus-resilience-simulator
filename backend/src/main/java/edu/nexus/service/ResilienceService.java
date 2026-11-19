package edu.nexus.service;

import edu.nexus.algorithms.dsu.DisjointSetUnion;
import edu.nexus.dto.ResilienceDtos.Component;
import edu.nexus.dto.ResilienceDtos.ComponentsSummary;
import edu.nexus.dto.ResilienceDtos.CriticalRoad;
import edu.nexus.dto.ResilienceDtos.ResilienceRequest;
import edu.nexus.dto.ResilienceDtos.ResilienceResponse;
import edu.nexus.dto.ResilienceDtos.UnionStep;
import edu.nexus.model.CityDataset;
import edu.nexus.model.CityNode;
import edu.nexus.model.NodeType;
import edu.nexus.model.Road;
import edu.nexus.model.ValidationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Connectivity of the <b>undirected infrastructure graph</b>: one vertex per operating node, one
 * undirected edge per operating road. Direction and capacity are deliberately ignored here: the
 * question is "is there any physical link", not "how much can flow". A zero-capacity road still counts
 * as a physical link; a failed road does not.
 *
 * <p>Because DSU cannot delete edges, the "after failure" state is computed by rebuilding a fresh DSU
 * from the modified graph, and the critical-road scan rebuilds once per road (O(E * (V + E) alpha(V))).
 */
@Service
public class ResilienceService {

    public static final int MAX_SCAN_ROADS = 1500;
    public static final int MAX_TRACE = 100;

    public ResilienceResponse analyze(ResilienceRequest req) {
        if (req == null) {
            throw new ValidationException("request", "is required");
        }
        Map<String, CityNode> byId = DatasetValidator.validate(req.dataset());
        Set<String> failedRoads = Inputs.knownIds(req.failedRoadIds(), FlowService.roadIds(req.dataset().roads()), "failedRoadIds", 3000);
        Set<String> failedNodes = Inputs.knownIds(req.failedNodeIds(), byId.keySet(), "failedNodeIds", 500);
        List<UnionStep> trace = new ArrayList<>();
        ComponentsSummary before = components(req.dataset(), Set.of(), Set.of(), null);
        ComponentsSummary after = components(req.dataset(), failedRoads, failedNodes, trace);
        List<String> newlyIsolated = new ArrayList<>(after.isolatedZoneIds());
        newlyIsolated.removeAll(before.isolatedZoneIds());
        newlyIsolated.removeAll(failedNodes);

        List<CriticalRoad> critical = new ArrayList<>();
        List<Road> roads = req.dataset().roads() == null ? List.of() : req.dataset().roads();
        List<String> notes = new ArrayList<>();
        if (Boolean.TRUE.equals(req.scanCriticalRoads())) {
            if (roads.size() > MAX_SCAN_ROADS) {
                notes.add("Critical-road scan skipped: more than " + MAX_SCAN_ROADS + " roads.");
            } else {
                for (Road r : roads) {
                    if (failedRoads.contains(r.id()) || failedNodes.contains(r.from()) || failedNodes.contains(r.to())) {
                        continue;
                    }
                    Set<String> without = new HashSet<>(failedRoads);
                    without.add(r.id());
                    ComponentsSummary s = components(req.dataset(), without, failedNodes, null);
                    if (s.componentCount() > after.componentCount()) {
                        List<String> cutOff = new ArrayList<>(s.isolatedZoneIds());
                        cutOff.removeAll(after.isolatedZoneIds());
                        critical.add(new CriticalRoad(r.id(), r.from(), r.to(), s.componentCount(), cutOff));
                    }
                }
            }
        }
        notes.add("Undirected graph: road direction and capacity are ignored; only physical links matter.");
        notes.add("DSU cannot delete edges, so failures are analysed by rebuilding the DSU from the modified graph.");
        return new ResilienceResponse(before, after, newlyIsolated, trace, critical, notes);
    }

    /** Builds a fresh DSU over operating nodes and roads and groups the result. */
    public static ComponentsSummary components(CityDataset ds, Set<String> failedRoads, Set<String> failedNodes,
                                               List<UnionStep> trace) {
        List<CityNode> nodes = ds.nodes();
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            idx.put(nodes.get(i).id(), i);
        }
        DisjointSetUnion dsu = new DisjointSetUnion(nodes.size());
        int merged = 0;
        int redundant = 0;
        for (Road r : ds.roads() == null ? List.<Road>of() : ds.roads()) {
            if (failedRoads.contains(r.id()) || failedNodes.contains(r.from()) || failedNodes.contains(r.to())) {
                continue;
            }
            boolean m = dsu.union(idx.get(r.from()), idx.get(r.to()));
            if (m) {
                merged++;
            } else {
                redundant++;
            }
            if (trace != null && trace.size() < MAX_TRACE) {
                trace.add(new UnionStep(r.id(), r.from(), r.to(), m));
            }
        }
        Map<Integer, List<CityNode>> groups = new LinkedHashMap<>();
        List<String> excluded = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            CityNode n = nodes.get(i);
            if (failedNodes.contains(n.id())) {
                excluded.add(n.id());
                continue;
            }
            groups.computeIfAbsent(dsu.find(i), k -> new ArrayList<>()).add(n);
        }
        List<List<CityNode>> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparingInt((List<CityNode> g) -> -g.size()).thenComparing(g -> g.get(0).id()));
        List<Component> comps = new ArrayList<>();
        List<String> isolated = new ArrayList<>();
        for (int c = 0; c < ordered.size(); c++) {
            List<CityNode> g = ordered.get(c);
            List<String> ids = new ArrayList<>();
            List<String> depots = new ArrayList<>();
            List<String> zones = new ArrayList<>();
            long supply = 0;
            long demand = 0;
            for (CityNode n : g) {
                ids.add(n.id());
                if (n.type() == NodeType.DEPOT) {
                    depots.add(n.id());
                    supply += n.supply();
                } else if (n.type() == NodeType.DEMAND_ZONE) {
                    zones.add(n.id());
                    demand += n.demand();
                }
            }
            if (depots.isEmpty()) {
                isolated.addAll(zones);
            }
            comps.add(new Component(c, ids, depots, zones, supply, demand, !depots.isEmpty()));
        }
        return new ComponentsSummary(comps.size(), comps, isolated, excluded, merged, redundant);
    }
}
