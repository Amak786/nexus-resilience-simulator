package edu.nexus.service;

import edu.nexus.algorithms.rbtree.PriorityKey;
import edu.nexus.algorithms.rbtree.RedBlackTree;
import edu.nexus.dto.IncidentDtos.OperationResult;
import edu.nexus.dto.IncidentDtos.PriorityOperation;
import edu.nexus.dto.IncidentDtos.PriorityRequest;
import edu.nexus.dto.IncidentDtos.PriorityResponse;
import edu.nexus.dto.IncidentDtos.RankedIncident;
import edu.nexus.dto.IncidentDtos.TreeMetrics;
import edu.nexus.model.CityNode;
import edu.nexus.model.Incident;
import edu.nexus.model.NodeType;
import edu.nexus.model.ValidationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Incident priority index backed by a red-black tree keyed by {@link PriorityKey} (score descending,
 * incident id ascending). In-order traversal is the response queue; updates are delete-then-reinsert
 * because the key changes.
 *
 * <p>Score = 200 x severity + min(300, affectedPeople / 50) + linked zone's vulnerablePercent.
 * This is a transparent triage rule for the demo, not a validated risk-prediction model.
 */
@Service
public class IncidentPriorityService {

    public static final int MAX_OPERATIONS = 200;
    public static final String FORMULA =
            "score = 200 x severity + min(300, floor(affectedPeople / 50)) + vulnerablePercent of the linked zone (0 if none); ties broken by incident id (A-Z)";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private record Entry(String id, String title, String zoneId, int severity, long affectedPeople, int bonus) {
        int score() {
            return 200 * severity + (int) Math.min(300, affectedPeople / 50) + bonus;
        }

        PriorityKey key() {
            return new PriorityKey(score(), id);
        }
    }

    public PriorityResponse build(PriorityRequest req) {
        if (req == null) {
            throw new ValidationException("request", "is required");
        }
        Map<String, CityNode> byId = DatasetValidator.validate(req.dataset());
        RedBlackTree<PriorityKey, Entry> tree = new RedBlackTree<>();
        Map<String, Entry> current = new HashMap<>();
        for (Incident inc : req.dataset().incidents() == null ? List.<Incident>of() : req.dataset().incidents()) {
            Entry e = new Entry(inc.id(), inc.title(), inc.zoneId(), inc.severity(), inc.affectedPeople(), bonus(byId, inc.zoneId()));
            tree.put(e.key(), e);
            current.put(e.id(), e);
        }
        List<PriorityOperation> ops = req.operations() == null ? List.of() : req.operations();
        if (ops.size() > MAX_OPERATIONS) {
            throw new ValidationException("operations", "at most " + MAX_OPERATIONS + " operations are allowed");
        }
        List<OperationResult> results = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            PriorityOperation op = ops.get(i);
            String f = "operations[" + i + "]";
            if (op == null || op.type() == null) {
                throw new ValidationException(f + ".type", "must be INSERT, UPDATE, RESOLVE or LOOKUP");
            }
            if (op.id() == null || !ID.matcher(op.id()).matches()) {
                throw new ValidationException(f + ".id", "must be 1-32 letters, digits, '-' or '_'");
            }
            List<String> events = new ArrayList<>();
            tree.setEventLog(events);
            long rotBefore = tree.rotationCount();
            String outcome;
            switch (op.type()) {
                case "INSERT" -> {
                    if (current.containsKey(op.id())) {
                        throw new ValidationException(f + ".id", "incident '" + op.id() + "' already exists");
                    }
                    if (op.title() == null || op.title().isBlank() || op.title().length() > 120) {
                        throw new ValidationException(f + ".title", "must be 1-120 characters");
                    }
                    checkSeverity(op.severity(), f);
                    checkPeople(op.affectedPeople(), f);
                    if (op.zoneId() != null && (byId.get(op.zoneId()) == null || byId.get(op.zoneId()).type() != NodeType.DEMAND_ZONE)) {
                        throw new ValidationException(f + ".zoneId", "must name a DEMAND_ZONE node or be null");
                    }
                    Entry e = new Entry(op.id(), op.title().trim(), op.zoneId(), op.severity(), op.affectedPeople(), bonus(byId, op.zoneId()));
                    tree.put(e.key(), e);
                    current.put(e.id(), e);
                    outcome = "inserted with score " + e.score() + ", rank " + tree.rank(e.key());
                }
                case "UPDATE" -> {
                    Entry old = requireExisting(current, op.id(), f);
                    if (op.severity() == null && op.affectedPeople() == null) {
                        throw new ValidationException(f, "UPDATE needs severity and/or affectedPeople");
                    }
                    if (op.severity() != null) {
                        checkSeverity(op.severity(), f);
                    }
                    if (op.affectedPeople() != null) {
                        checkPeople(op.affectedPeople(), f);
                    }
                    Entry upd = new Entry(old.id(), old.title(), old.zoneId(),
                            op.severity() != null ? op.severity() : old.severity(),
                            op.affectedPeople() != null ? op.affectedPeople() : old.affectedPeople(), old.bonus());
                    int oldRank = tree.rank(old.key());
                    tree.remove(old.key());
                    tree.put(upd.key(), upd);
                    current.put(upd.id(), upd);
                    outcome = "score " + old.score() + " -> " + upd.score() + ", rank " + oldRank + " -> " + tree.rank(upd.key());
                }
                case "RESOLVE" -> {
                    Entry old = requireExisting(current, op.id(), f);
                    tree.remove(old.key());
                    current.remove(old.id());
                    outcome = "resolved and removed from the queue";
                }
                case "LOOKUP" -> {
                    Entry e = current.get(op.id());
                    outcome = e == null ? "not found" : "found: score " + e.score() + ", rank " + tree.rank(e.key()) + " of " + tree.size();
                }
                default -> throw new ValidationException(f + ".type", "must be INSERT, UPDATE, RESOLVE or LOOKUP");
            }
            tree.setEventLog(null);
            results.add(new OperationResult(i, op.type(), op.id(), outcome, tree.rotationCount() - rotBefore, events));
        }

        List<RankedIncident> queue = new ArrayList<>();
        tree.forEachInOrder((k, e) -> queue.add(new RankedIncident(queue.size() + 1, e.id(), e.title(), e.zoneId(),
                e.severity(), e.affectedPeople(), e.bonus(), e.score(), k.toString())));
        boolean ok = true;
        String msg = "all red-black invariants hold";
        try {
            tree.validate();
        } catch (IllegalStateException ex) {
            ok = false;
            msg = ex.getMessage();
        }
        int n = tree.size();
        TreeMetrics metrics = new TreeMetrics(n, tree.height(), tree.blackHeight(),
                Math.round(2 * Math.log(n + 1) / Math.log(2) * 100) / 100.0, tree.rotationCount(), ok, msg);
        return new PriorityResponse(queue, tree.snapshot(), metrics, results, FORMULA,
                "Priority index for decision support on synthetic data. It is a transparent ordering rule, not an independently validated real-world risk model.");
    }

    private static int bonus(Map<String, CityNode> byId, String zoneId) {
        if (zoneId == null) {
            return 0;
        }
        CityNode z = byId.get(zoneId);
        return z == null || z.vulnerablePercent() == null ? 0 : z.vulnerablePercent();
    }

    private static Entry requireExisting(Map<String, Entry> current, String id, String f) {
        Entry e = current.get(id);
        if (e == null) {
            throw new ValidationException(f + ".id", "incident '" + id + "' is not in the queue");
        }
        return e;
    }

    private static void checkSeverity(Integer s, String f) {
        if (s == null || s < 1 || s > 5) {
            throw new ValidationException(f + ".severity", "must be an integer in [1, 5]");
        }
    }

    private static void checkPeople(Long p, String f) {
        if (p == null || p < 0 || p > DatasetValidator.MAX_QUANTITY) {
            throw new ValidationException(f + ".affectedPeople", "must be between 0 and " + DatasetValidator.MAX_QUANTITY);
        }
    }
}
