package edu.trace.service;

import edu.trace.model.ValidationException;


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

}
