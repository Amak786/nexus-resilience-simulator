package edu.nexus.service;

import edu.nexus.model.ValidationException;
import edu.nexus.model.ValidationIssue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small helpers for optional request parameters. A missing optional value uses its documented default; a present but invalid value is always rejected. */
public final class Inputs {

    private Inputs() {}

    public static int intParam(Integer v, int def, int lo, int hi, String field) {
        if (v == null) {
            return def;
        }
        if (v < lo || v > hi) {
            throw new ValidationException(field, "must be between " + lo + " and " + hi);
        }
        return v;
    }

    public static long longParam(Long v, long def, long lo, long hi, String field) {
        if (v == null) {
            return def;
        }
        if (v < lo || v > hi) {
            throw new ValidationException(field, "must be between " + lo + " and " + hi);
        }
        return v;
    }

    public static double doubleParam(Double v, double def, double lo, double hi, String field) {
        if (v == null) {
            return def;
        }
        if (!Double.isFinite(v) || v < lo || v > hi) {
            throw new ValidationException(field, "must be between " + lo + " and " + hi);
        }
        return v;
    }

    /** Distinct ids, each of which must be in {@code known}. Null list = empty. */
    public static Set<String> knownIds(List<String> ids, Set<String> known, String field, int max) {
        Set<String> out = new LinkedHashSet<>();
        if (ids == null) {
            return out;
        }
        if (ids.size() > max) {
            throw new ValidationException(field, "at most " + max + " entries are allowed");
        }
        List<ValidationIssue> issues = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (id == null || !known.contains(id)) {
                issues.add(new ValidationIssue(field + "[" + i + "]", "unknown id '" + id + "'"));
            } else {
                out.add(id);
            }
        }
        if (!issues.isEmpty()) {
            throw new ValidationException(issues);
        }
        return out;
    }

    /** Capacity overrides: keys must be known road ids, values in [0, 1e9]. */
    public static Map<String, Long> overrides(Map<String, Long> m, Set<String> roadIds, String field) {
        if (m == null) {
            return Map.of();
        }
        List<ValidationIssue> issues = new ArrayList<>();
        for (Map.Entry<String, Long> e : m.entrySet()) {
            if (!roadIds.contains(e.getKey())) {
                issues.add(new ValidationIssue(field + "." + e.getKey(), "unknown road id"));
            } else if (e.getValue() == null || e.getValue() < 0 || e.getValue() > DatasetValidator.MAX_QUANTITY) {
                issues.add(new ValidationIssue(field + "." + e.getKey(), "capacity must be between 0 and " + DatasetValidator.MAX_QUANTITY));
            }
        }
        if (!issues.isEmpty()) {
            throw new ValidationException(issues);
        }
        return m;
    }
}
