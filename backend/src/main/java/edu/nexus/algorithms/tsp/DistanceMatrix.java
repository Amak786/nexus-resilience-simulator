package edu.nexus.algorithms.tsp;

/**
 * Validated metric distance matrix.
 *
 * <p>The 2-approximation guarantee of {@link MstPreorderTsp} holds only for a metric, so construction
 * rejects any matrix that is not: square, finite, non-negative, zero on the diagonal, symmetric, and
 * satisfying the triangle inequality d(i,k) &le; d(i,j) + d(j,k) for every triple (checked in O(n^3)
 * with a small relative tolerance for floating-point rounding).
 */
public final class DistanceMatrix {

    public static final double TOLERANCE = 1e-9;

    private final double[][] d;

    private DistanceMatrix(double[][] d) {
        this.d = d;
    }

    /** Validates and copies {@code raw}; throws {@link NonMetricException} describing the first violation. */
    public static DistanceMatrix of(double[][] raw) {
        if (raw == null || raw.length == 0) {
            throw new NonMetricException("the distance matrix is empty");
        }
        int n = raw.length;
        double[][] d = new double[n][];
        for (int i = 0; i < n; i++) {
            if (raw[i] == null || raw[i].length != n) {
                throw new NonMetricException("row " + i + " must have exactly " + n + " entries");
            }
            d[i] = raw[i].clone();
            for (int j = 0; j < n; j++) {
                double v = d[i][j];
                if (!Double.isFinite(v)) {
                    throw new NonMetricException("d(" + i + "," + j + ") is not finite (unreachable stop?)");
                }
                if (v < 0) {
                    throw new NonMetricException("d(" + i + "," + j + ") is negative");
                }
            }
            if (d[i][i] != 0) {
                throw new NonMetricException("d(" + i + "," + i + ") must be 0");
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (Math.abs(d[i][j] - d[j][i]) > TOLERANCE * Math.max(1, Math.abs(d[i][j]))) {
                    throw new NonMetricException("not symmetric: d(" + i + "," + j + ") != d(" + j + "," + i + ")");
                }
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                for (int k = 0; k < n; k++) {
                    double direct = d[i][k];
                    double via = d[i][j] + d[j][k];
                    if (direct > via + TOLERANCE * Math.max(1, via)) {
                        throw new NonMetricException("triangle inequality fails: d(" + i + "," + k + ")="
                                + direct + " > d(" + i + "," + j + ")+d(" + j + "," + k + ")=" + via);
                    }
                }
            }
        }
        return new DistanceMatrix(d);
    }

    /**
     * Euclidean distances between planar points. Euclidean distance is a metric by definition, so the
     * O(n^3) triangle check is skipped (used by benchmarks on large n).
     */
    public static DistanceMatrix euclidean(double[] xs, double[] ys) {
        if (xs.length == 0 || xs.length != ys.length) {
            throw new NonMetricException("need the same positive number of x and y coordinates");
        }
        int n = xs.length;
        double[][] d = new double[n][n];
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(xs[i]) || !Double.isFinite(ys[i])) {
                throw new NonMetricException("point " + i + " has a non-finite coordinate");
            }
            for (int j = 0; j < i; j++) {
                double dx = xs[i] - xs[j];
                double dy = ys[i] - ys[j];
                d[i][j] = Math.sqrt(dx * dx + dy * dy);
                d[j][i] = d[i][j];
            }
        }
        return new DistanceMatrix(d);
    }

    public int size() {
        return d.length;
    }

    public double get(int i, int j) {
        return d[i][j];
    }

    /** Length of the closed tour visiting {@code order} and returning to {@code order[0]}. */
    public double tourLength(int[] order) {
        if (order.length <= 1) {
            return 0;
        }
        double total = 0;
        for (int i = 0; i < order.length; i++) {
            total += d[order[i]][order[(i + 1) % order.length]];
        }
        return total;
    }

    /** Raised when an input matrix violates a metric property. */
    public static final class NonMetricException extends IllegalArgumentException {
        public NonMetricException(String message) {
            super(message);
        }
    }
}
