package edu.nexus.service;

import edu.nexus.algorithms.segtree.SegmentTree;
import edu.nexus.algorithms.select.RandomizedQuickselect;
import edu.nexus.dto.FailureLabDtos.BatchImpact;
import edu.nexus.dto.FailureLabDtos.FailureLabRequest;
import edu.nexus.dto.FailureLabDtos.FailureLabResponse;
import edu.nexus.dto.FailureLabDtos.Interval;
import edu.nexus.dto.FailureLabDtos.PercentileValue;
import edu.nexus.dto.FailureLabDtos.RecoveryAction;
import edu.nexus.dto.FailureLabDtos.ScenarioImpact;
import edu.nexus.dto.FailureLabDtos.TimelineImpact;
import edu.nexus.dto.FailureLabDtos.ZoneImpact;
import edu.nexus.dto.FlowDtos.ZoneAllocation;
import edu.nexus.dto.ResilienceDtos.ComponentsSummary;
import edu.nexus.model.CityDataset;
import edu.nexus.model.CityNode;
import edu.nexus.model.Road;
import edu.nexus.model.ValidationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Failure Lab: simulate a failure and measure its impact with every NEXUS algorithm.
 *
 * <ol>
 *   <li>Edmonds-Karp before and after the failure: throughput drop and how the min cut moves.</li>
 *   <li>DSU on the undirected graph: which zones lose every physical link to a depot.</li>
 *   <li>Segment tree over the hourly shortfall series: total, worst interval and peak hour.</li>
 *   <li>Randomized Quickselect over a seeded batch of random failures: P50/P90/P95 impact, and where
 *       the selected failure ranks.</li>
 *   <li>Recovery candidates (restore a failed element, reinforce a min-cut road by 50%), each
 *       re-solved with Edmonds-Karp and ranked by recovered throughput.</li>
 * </ol>
 */
@Service
public class FailureLabService {

    private static final double[] PERCENTILES = {50, 90, 95};
    private static final int MAX_RECOVERY_CANDIDATES = 20;

    public FailureLabResponse simulate(FailureLabRequest req) {
        if (req == null) {
            throw new ValidationException("request", "is required");
        }
        CityDataset ds = req.dataset();
        Map<String, CityNode> byId = DatasetValidator.validate(ds);
        List<Road> roads = ds.roads() == null ? List.of() : ds.roads();
        Set<String> roadIds = FlowService.roadIds(roads);
        Set<String> failedRoads = Inputs.knownIds(req.failedRoadIds(), roadIds, "failedRoadIds", 3000);
        Set<String> failedNodes = Inputs.knownIds(req.failedNodeIds(), byId.keySet(), "failedNodeIds", 500);
        int window = Inputs.intParam(req.windowHours(), 12, 1, 72, "windowHours");
        int scenarios = Inputs.intParam(req.batchScenarios(), 100, 10, 500, "batchScenarios");
        int perScenario = Inputs.intParam(req.roadsPerScenario(), 2, 1, 5, "roadsPerScenario");
        long seed = Inputs.longParam(req.seed(), 2026L, 0, Long.MAX_VALUE, "seed");
        if (perScenario > roads.size()) {
            throw new ValidationException("roadsPerScenario", "cannot exceed the number of roads (" + roads.size() + ")");
        }
        List<String> warnings = new ArrayList<>();

        // 1) Throughput before and after (Edmonds-Karp, super source -> super sink).
        FlowService.Solved before = solve(ds, byId, Set.of(), Set.of(), Map.of());
        FlowService.Solved after = solve(ds, byId, failedRoads, failedNodes, Map.of());
        long tBefore = before.result().maxFlow();
        long tAfter = after.result().maxFlow();
        long drop = tBefore - tAfter;
        if (failedRoads.isEmpty() && failedNodes.isEmpty()) {
            warnings.add("No failure selected: 'after' equals the baseline.");
        }

        List<ZoneAllocation> zb = FlowService.zoneAllocations(before.network(), byId, Set.of(), ds.nodes());
        List<ZoneAllocation> za = FlowService.zoneAllocations(after.network(), byId, failedNodes, ds.nodes());
        List<ZoneImpact> zones = new ArrayList<>();
        for (int i = 0; i < zb.size(); i++) {
            ZoneAllocation b = zb.get(i);
            ZoneAllocation a = za.get(i);
            zones.add(new ZoneImpact(b.id(), b.name(), b.demand(), b.received(), a.received(), b.received() - a.received()));
        }

        // 2) Connectivity (DSU, undirected).
        ComponentsSummary cBefore = ResilienceService.components(ds, Set.of(), Set.of(), null);
        ComponentsSummary cAfter = ResilienceService.components(ds, failedRoads, failedNodes, null);
        List<String> newlyIsolated = new ArrayList<>(cAfter.isolatedZoneIds());
        newlyIsolated.removeAll(cBefore.isolatedZoneIds());

        // 3) Timeline (segment tree).
        TimelineImpact timeline = null;
        if (ds.demandTimeline() == null) {
            warnings.add("Dataset has no demandTimeline, so the hourly segment-tree analysis is skipped.");
        } else {
            int hours = ds.demandTimeline().hourlyDemand().size();
            int worstLen = Inputs.intParam(req.worstWindowHours(), 6, 1, hours, "worstWindowHours");
            timeline = timeline(ds.demandTimeline().hourlyDemand(), tBefore / (double) window,
                    tAfter / (double) window, worstLen);
        }

        // 4) Batch of random failures (seeded) and Quickselect percentiles.
        BatchImpact batch = batch(ds, byId, roads, tBefore, drop, scenarios, perScenario, seed);

        // 5) Recovery candidates.
        List<RecoveryAction> actions = recovery(ds, byId, failedRoads, failedNodes, after, tAfter, drop);

        double dropPct = tBefore == 0 ? 0 : Math.round(10000.0 * drop / tBefore) / 100.0;
        return new FailureLabResponse(tBefore, tAfter, drop, dropPct,
                FlowService.explainCut(before.network(), before.cut(), byId),
                FlowService.explainCut(after.network(), after.cut(), byId),
                zones, cBefore, cAfter, newlyIsolated, timeline, batch, actions, assumptions(window), warnings);
    }

    private static FlowService.Solved solve(CityDataset ds, Map<String, CityNode> byId, Set<String> failedRoads,
                                            Set<String> failedNodes, Map<String, Long> overrides) {
        return FlowService.solve(CityNetwork.build(ds, byId, CityNetwork.SUPER_SOURCE, CityNetwork.SUPER_SINK,
                failedRoads, failedNodes, overrides));
    }

    static TimelineImpact timeline(List<Long> demand, double capBefore, double capAfter, int worstLen) {
        int hours = demand.size();
        long[] sb = new long[hours];
        long[] sa = new long[hours];
        for (int h = 0; h < hours; h++) {
            sb[h] = Math.max(0, Math.round(demand.get(h) - capBefore));
            sa[h] = Math.max(0, Math.round(demand.get(h) - capAfter));
        }
        SegmentTree before = new SegmentTree(sb);
        SegmentTree after = new SegmentTree(sa);
        long totalBefore = before.rangeSum(0, hours - 1);
        long totalAfter = after.rangeSum(0, hours - 1);
        int bestStart = 0;
        long bestSum = Long.MIN_VALUE;
        for (int s = 0; s + worstLen - 1 < hours; s++) {
            long v = after.rangeSum(s, s + worstLen - 1);
            if (v > bestSum) {
                bestSum = v;
                bestStart = s;
            }
        }
        SegmentTree.MaxResult peak = after.rangeMax(0, hours - 1);
        return new TimelineImpact(hours, List.copyOf(demand), round2(capBefore), round2(capAfter), boxed(sb), boxed(sa),
                totalBefore, totalAfter, new Interval(bestStart, bestStart + worstLen - 1, bestSum),
                new Interval(peak.index(), peak.index(), peak.value()), after.nodesVisited() + before.nodesVisited());
    }

    private static BatchImpact batch(CityDataset ds, Map<String, CityNode> byId, List<Road> roads, long tBefore,
                                     long selectedDrop, int scenarios, int perScenario, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        long[] impacts = new long[scenarios];
        List<ScenarioImpact> all = new ArrayList<>();
        for (int s = 0; s < scenarios; s++) {
            Set<String> fail = new HashSet<>();
            List<String> order = new ArrayList<>();
            while (fail.size() < perScenario) {
                String id = roads.get(rnd.nextInt(roads.size())).id();
                if (fail.add(id)) {
                    order.add(id);
                }
            }
            long t = solve(ds, byId, fail, Set.of(), Map.of()).result().maxFlow();
            impacts[s] = tBefore - t;
            all.add(new ScenarioImpact(order, t, impacts[s]));
        }
        double[] values = new double[scenarios];
        for (int i = 0; i < scenarios; i++) {
            values[i] = impacts[i];
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        List<PercentileValue> pct = new ArrayList<>();
        for (double p : PERCENTILES) {
            RandomizedQuickselect.Selection sel = RandomizedQuickselect.percentile(values, p, seed + (long) p);
            int rank = Math.max(1, (int) Math.ceil(p / 100.0 * scenarios));
            pct.add(new PercentileValue(p, sel.value(), sel.comparisons(), sel.value() == sorted[rank - 1]));
        }
        long sortComparisons = Math.round(scenarios * (Math.log(scenarios) / Math.log(2)));
        int atMost = 0;
        for (long v : impacts) {
            if (v <= selectedDrop) {
                atMost++;
            }
        }
        all.sort(Comparator.comparingLong(ScenarioImpact::impact).reversed());
        List<Long> impactList = new ArrayList<>();
        for (long v : impacts) {
            impactList.add(v);
        }
        return new BatchImpact(scenarios, perScenario, seed, impactList, pct, sortComparisons,
                round2(100.0 * atMost / scenarios), all.subList(0, Math.min(5, all.size())));
    }

    private static List<RecoveryAction> recovery(CityDataset ds, Map<String, CityNode> byId, Set<String> failedRoads,
                                                 Set<String> failedNodes, FlowService.Solved after, long tAfter,
                                                 long drop) {
        List<RecoveryAction> actions = new ArrayList<>();
        for (String r : failedRoads) {
            if (actions.size() >= MAX_RECOVERY_CANDIDATES) {
                break;
            }
            Set<String> rest = new HashSet<>(failedRoads);
            rest.remove(r);
            long t = solve(ds, byId, rest, failedNodes, Map.of()).result().maxFlow();
            actions.add(action("RESTORE_ROAD", r, "Repair road " + r + " and reopen it", t, tAfter, drop));
        }
        for (String n : failedNodes) {
            if (actions.size() >= MAX_RECOVERY_CANDIDATES) {
                break;
            }
            Set<String> rest = new HashSet<>(failedNodes);
            rest.remove(n);
            long t = solve(ds, byId, failedRoads, rest, Map.of()).result().maxFlow();
            actions.add(action("RESTORE_FACILITY", n, "Bring facility " + n + " back into operation", t, tAfter, drop));
        }
        Map<String, Long> capacity = new HashMap<>();
        for (Road r : ds.roads()) {
            capacity.put(r.id(), r.capacity());
        }
        Set<String> reinforced = new HashSet<>();
        for (int e : after.cut().cutEdges()) {
            CityNetwork.EdgeInfo info = after.network().edges().get(e);
            if (info.kind() != CityNetwork.EdgeKind.ROAD || !reinforced.add(info.roadId())
                    || actions.size() >= MAX_RECOVERY_CANDIDATES) {
                continue;
            }
            long boosted = capacity.get(info.roadId()) + Math.max(1, capacity.get(info.roadId()) / 2);
            long t = solve(ds, byId, failedRoads, failedNodes, Map.of(info.roadId(), boosted)).result().maxFlow();
            actions.add(action("REINFORCE_ROAD", info.roadId(), "Raise capacity of min-cut road " + info.roadId()
                    + " by 50% (to " + boosted + "), e.g. traffic control or a temporary lane", t, tAfter, drop));
        }
        actions.sort(Comparator.comparingLong(RecoveryAction::gain).reversed().thenComparing(RecoveryAction::target));
        return actions;
    }

    private static RecoveryAction action(String type, String target, String text, long t, long tAfter, long drop) {
        long gain = t - tAfter;
        double pct = drop == 0 ? 0 : round2(100.0 * Math.min(gain, drop) / drop);
        return new RecoveryAction(type, target, text, t, gain, pct);
    }

    private static List<Long> boxed(long[] a) {
        List<Long> out = new ArrayList<>(a.length);
        for (long v : a) {
            out.add(v);
        }
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static List<String> assumptions(int window) {
        return List.of(
                "Throughput is the Edmonds-Karp max flow from all depots to all demand zones for one " + window + "-hour window.",
                "Hourly capacity = throughput / " + window + " (deliveries spread evenly across the window).",
                "Random scenarios fail roads uniformly at random (seeded), independent of the selected failure.",
                "Percentiles use the nearest-rank definition; Quickselect results are cross-checked against a full sort.",
                "Recovery gains are computed by re-solving max flow; they are decision support, not official instructions.");
    }
}
