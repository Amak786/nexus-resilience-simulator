package edu.nexus.service;

import edu.nexus.algorithms.segtree.SegmentTree;
import edu.nexus.dto.IncidentDtos.TimelineOpResult;
import edu.nexus.dto.IncidentDtos.TimelineOperation;
import edu.nexus.dto.IncidentDtos.TimelineRequest;
import edu.nexus.dto.IncidentDtos.TimelineResponse;
import edu.nexus.model.ValidationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies a sequence of range queries and updates to an hourly series with the segment tree, and
 * re-computes every answer on a plain array (the naive O(n) baseline) to prove they agree.
 */
@Service
public class TimelineService {

    public static final int MAX_SERIES = 100_000;
    public static final int MAX_OPERATIONS = 500;

    public TimelineResponse run(TimelineRequest req) {
        if (req == null) {
            throw new ValidationException("request", "is required");
        }
        List<Long> series = req.series();
        if (series == null) {
            if (req.dataset() == null) {
                throw new ValidationException("series", "provide a series or a dataset with a demandTimeline");
            }
            DatasetValidator.validate(req.dataset());
            if (req.dataset().demandTimeline() == null) {
                throw new ValidationException("dataset.demandTimeline", "is missing");
            }
            series = req.dataset().demandTimeline().hourlyDemand();
        }
        if (series.isEmpty() || series.size() > MAX_SERIES) {
            throw new ValidationException("series", "must have between 1 and " + MAX_SERIES + " values");
        }
        long[] naive = new long[series.size()];
        for (int i = 0; i < naive.length; i++) {
            Long v = series.get(i);
            if (v == null || Math.abs(v) > 1_000_000_000_000L) {
                throw new ValidationException("series[" + i + "]", "must be a whole number with |value| <= 1e12");
            }
            naive[i] = v;
        }
        List<TimelineOperation> ops = req.operations() == null ? List.of() : req.operations();
        if (ops.size() > MAX_OPERATIONS) {
            throw new ValidationException("operations", "at most " + MAX_OPERATIONS + " operations");
        }
        SegmentTree tree = new SegmentTree(naive);
        List<TimelineOpResult> results = new ArrayList<>();
        int n = naive.length;
        for (int i = 0; i < ops.size(); i++) {
            TimelineOperation op = ops.get(i);
            String f = "operations[" + i + "]";
            if (op == null || op.type() == null) {
                throw new ValidationException(f + ".type", "must be SUM, MAX, ADD or SET");
            }
            int from = op.from() == null ? -1 : op.from();
            int to = op.type().equals("SET") ? from : (op.to() == null ? -1 : op.to());
            if (from < 0 || to >= n || from > to) {
                throw new ValidationException(f, "range [" + from + ", " + to + "] must lie within [0, " + (n - 1) + "] with from <= to");
            }
            long visitedBefore = tree.nodesVisited();
            switch (op.type()) {
                case "SUM" -> {
                    long got = tree.rangeSum(from, to);
                    long expect = 0;
                    for (int h = from; h <= to; h++) {
                        expect += naive[h];
                    }
                    results.add(new TimelineOpResult(i, "SUM", from, to, null, got, null,
                            tree.nodesVisited() - visitedBefore, got == expect));
                }
                case "MAX" -> {
                    SegmentTree.MaxResult got = tree.rangeMax(from, to);
                    int arg = from;
                    for (int h = from; h <= to; h++) {
                        if (naive[h] > naive[arg]) {
                            arg = h;
                        }
                    }
                    results.add(new TimelineOpResult(i, "MAX", from, to, null, got.value(), got.index(),
                            tree.nodesVisited() - visitedBefore, got.value() == naive[arg] && got.index() == arg));
                }
                case "ADD", "SET" -> {
                    if (op.value() == null || Math.abs(op.value()) > 1_000_000_000L) {
                        throw new ValidationException(f + ".value", "must be a whole number with |value| <= 1e9");
                    }
                    if (op.type().equals("ADD")) {
                        tree.rangeAdd(from, to, op.value());
                        for (int h = from; h <= to; h++) {
                            naive[h] += op.value();
                        }
                    } else {
                        tree.pointSet(from, op.value());
                        naive[from] = op.value();
                    }
                    boolean same = true;
                    for (int h = from; h <= to && same; h++) {
                        same = tree.rangeSum(h, h) == naive[h];
                    }
                    results.add(new TimelineOpResult(i, op.type(), from, to, op.value(), null, null,
                            tree.nodesVisited() - visitedBefore, same));
                }
                default -> throw new ValidationException(f + ".type", "must be SUM, MAX, ADD or SET");
            }
        }
        List<Long> finalSeries = new ArrayList<>();
        for (long v : tree.toArray()) {
            finalSeries.add(v);
        }
        return new TimelineResponse(List.copyOf(series), finalSeries, results,
                "Build O(n); SUM, MAX, ADD (lazy range update) and SET are O(log n) each. Naive array baseline: O(r - l + 1) per operation.");
    }
}
