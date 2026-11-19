package edu.nexus.service;

import edu.nexus.model.CityDataset;
import edu.nexus.model.CityNode;
import edu.nexus.model.Incident;
import edu.nexus.model.NodeType;
import edu.nexus.model.Road;
import edu.nexus.model.ValidationException;
import edu.nexus.model.ValidationIssue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates a {@link CityDataset}. Every problem is reported with its field path; nothing is silently
 * replaced by a default. The only documented default is {@code oneWay = false} when the field is absent.
 */
public final class DatasetValidator {

    public static final int MAX_NODES = 500;
    public static final int MAX_ROADS = 3000;
    public static final long MAX_QUANTITY = 1_000_000_000L;
    public static final int MAX_INCIDENTS = 500;
    public static final int MIN_HOURS = 24;
    public static final int MAX_HOURS = 168;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final int MAX_ISSUES = 50;

    private DatasetValidator() {}

    /** Throws {@link ValidationException} listing all problems (up to 50). Returns node index by id. */
    public static Map<String, CityNode> validate(CityDataset ds) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (ds == null) {
            throw new ValidationException("dataset", "is required");
        }
        if (ds.nodes() == null || ds.nodes().isEmpty()) {
            throw new ValidationException("dataset.nodes", "dataset is empty: at least one node is required");
        }
        if (ds.nodes().size() > MAX_NODES) {
            throw new ValidationException("dataset.nodes", "at most " + MAX_NODES + " nodes are allowed");
        }
        List<Road> roads = ds.roads() == null ? List.of() : ds.roads();
        if (roads.size() > MAX_ROADS) {
            throw new ValidationException("dataset.roads", "at most " + MAX_ROADS + " roads are allowed");
        }
        Map<String, CityNode> byId = new HashMap<>();
        for (int i = 0; i < ds.nodes().size(); i++) {
            String f = "dataset.nodes[" + i + "]";
            CityNode n = ds.nodes().get(i);
            if (n == null) {
                issues.add(new ValidationIssue(f, "must not be null"));
                continue;
            }
            if (n.id() == null || !ID.matcher(n.id()).matches()) {
                issues.add(new ValidationIssue(f + ".id", "must be 1-32 letters, digits, '-' or '_'"));
            } else if (byId.containsKey(n.id())) {
                issues.add(new ValidationIssue(f + ".id", "duplicate node id '" + n.id() + "'"));
            } else {
                byId.put(n.id(), n);
            }
            if (n.name() == null || n.name().isBlank() || n.name().length() > 100) {
                issues.add(new ValidationIssue(f + ".name", "must be 1-100 characters"));
            }
            if (n.type() == null) {
                issues.add(new ValidationIssue(f + ".type", "must be DEPOT, JUNCTION or DEMAND_ZONE"));
            }
            coordinate(n.x(), f + ".x", issues);
            coordinate(n.y(), f + ".y", issues);
            if (n.type() == NodeType.DEPOT) {
                quantity(n.supply(), f + ".supply", true, issues);
                mustBeAbsent(n.demand(), f + ".demand", "only DEMAND_ZONE nodes have demand", issues);
            } else if (n.type() == NodeType.DEMAND_ZONE) {
                quantity(n.demand(), f + ".demand", true, issues);
                mustBeAbsent(n.supply(), f + ".supply", "only DEPOT nodes have supply", issues);
                range(n.severity(), 1, 5, f + ".severity", issues);
                range(n.vulnerablePercent(), 0, 100, f + ".vulnerablePercent", issues);
                quantity(n.population(), f + ".population", false, issues);
            } else if (n.type() == NodeType.JUNCTION) {
                mustBeAbsent(n.supply(), f + ".supply", "junctions have no supply", issues);
                mustBeAbsent(n.demand(), f + ".demand", "junctions have no demand", issues);
            }
        }
        Set<String> roadIds = new HashSet<>();
        for (int i = 0; i < roads.size(); i++) {
            String f = "dataset.roads[" + i + "]";
            Road r = roads.get(i);
            if (r == null) {
                issues.add(new ValidationIssue(f, "must not be null"));
                continue;
            }
            if (r.id() == null || !ID.matcher(r.id()).matches()) {
                issues.add(new ValidationIssue(f + ".id", "must be 1-32 letters, digits, '-' or '_'"));
            } else if (!roadIds.add(r.id())) {
                issues.add(new ValidationIssue(f + ".id", "duplicate road id '" + r.id() + "'"));
            }
            if (r.from() == null || !byId.containsKey(r.from())) {
                issues.add(new ValidationIssue(f + ".from", "unknown node '" + r.from() + "'"));
            }
            if (r.to() == null || !byId.containsKey(r.to())) {
                issues.add(new ValidationIssue(f + ".to", "unknown node '" + r.to() + "'"));
            }
            if (r.from() != null && r.from().equals(r.to())) {
                issues.add(new ValidationIssue(f, "a road cannot start and end at the same node"));
            }
            quantity(r.capacity(), f + ".capacity", true, issues);
            if (r.lengthKm() == null || !Double.isFinite(r.lengthKm()) || r.lengthKm() <= 0 || r.lengthKm() > 1000) {
                issues.add(new ValidationIssue(f + ".lengthKm", "must be a number in (0, 1000]"));
            }
        }
        if (ds.demandTimeline() != null) {
            List<Long> hd = ds.demandTimeline().hourlyDemand();
            if (hd == null || hd.size() < MIN_HOURS || hd.size() > MAX_HOURS) {
                issues.add(new ValidationIssue("dataset.demandTimeline.hourlyDemand",
                        "must list between " + MIN_HOURS + " and " + MAX_HOURS + " hourly values"));
            } else {
                for (int h = 0; h < hd.size(); h++) {
                    quantity(hd.get(h), "dataset.demandTimeline.hourlyDemand[" + h + "]", true, issues);
                }
            }
        }
        List<Incident> incidents = ds.incidents() == null ? List.of() : ds.incidents();
        if (incidents.size() > MAX_INCIDENTS) {
            issues.add(new ValidationIssue("dataset.incidents", "at most " + MAX_INCIDENTS + " incidents are allowed"));
        } else {
            Set<String> incidentIds = new HashSet<>();
            for (int i = 0; i < incidents.size(); i++) {
                String f = "dataset.incidents[" + i + "]";
                Incident inc = incidents.get(i);
                if (inc == null) {
                    issues.add(new ValidationIssue(f, "must not be null"));
                    continue;
                }
                if (inc.id() == null || !ID.matcher(inc.id()).matches()) {
                    issues.add(new ValidationIssue(f + ".id", "must be 1-32 letters, digits, '-' or '_'"));
                } else if (!incidentIds.add(inc.id())) {
                    issues.add(new ValidationIssue(f + ".id", "duplicate incident id '" + inc.id() + "'"));
                }
                if (inc.title() == null || inc.title().isBlank() || inc.title().length() > 120) {
                    issues.add(new ValidationIssue(f + ".title", "must be 1-120 characters"));
                }
                if (inc.zoneId() != null) {
                    CityNode z = byId.get(inc.zoneId());
                    if (z == null || z.type() != NodeType.DEMAND_ZONE) {
                        issues.add(new ValidationIssue(f + ".zoneId", "must name a DEMAND_ZONE node or be null"));
                    }
                }
                range(inc.severity(), 1, 5, f + ".severity", issues);
                quantity(inc.affectedPeople(), f + ".affectedPeople", true, issues);
                range(inc.reportedHour(), 0, MAX_HOURS - 1, f + ".reportedHour", issues);
            }
        }
        if (issues.isEmpty() && byId.values().stream().noneMatch(n -> n.type() == NodeType.DEPOT)) {
            issues.add(new ValidationIssue("dataset.nodes", "at least one DEPOT is required"));
        }
        if (!issues.isEmpty()) {
            throw new ValidationException(issues.size() > MAX_ISSUES ? issues.subList(0, MAX_ISSUES) : issues);
        }
        return byId;
    }

    /** Checks that every id exists in {@code known}. */
    public static void requireKnown(Collection<String> ids, Set<String> known, String field) {
        if (ids == null) {
            return;
        }
        List<ValidationIssue> issues = new ArrayList<>();
        int i = 0;
        for (String id : ids) {
            if (id == null || !known.contains(id)) {
                issues.add(new ValidationIssue(field + "[" + i + "]", "unknown id '" + id + "'"));
            }
            i++;
        }
        if (!issues.isEmpty()) {
            throw new ValidationException(issues);
        }
    }

    private static void coordinate(Double v, String field, List<ValidationIssue> issues) {
        if (v == null || !Double.isFinite(v) || Math.abs(v) > 10_000) {
            issues.add(new ValidationIssue(field, "must be a number in [-10000, 10000]"));
        }
    }

    private static void quantity(Long v, String field, boolean required, List<ValidationIssue> issues) {
        if (v == null) {
            if (required) {
                issues.add(new ValidationIssue(field, "is required"));
            }
        } else if (v < 0 || v > MAX_QUANTITY) {
            issues.add(new ValidationIssue(field, "must be between 0 and " + MAX_QUANTITY));
        }
    }

    private static void range(Integer v, int lo, int hi, String field, List<ValidationIssue> issues) {
        if (v == null || v < lo || v > hi) {
            issues.add(new ValidationIssue(field, "must be an integer in [" + lo + ", " + hi + "]"));
        }
    }

    private static void mustBeAbsent(Object v, String field, String message, List<ValidationIssue> issues) {
        if (v != null) {
            issues.add(new ValidationIssue(field, message));
        }
    }
}
