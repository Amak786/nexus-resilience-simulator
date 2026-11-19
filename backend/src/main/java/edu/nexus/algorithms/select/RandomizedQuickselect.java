package edu.nexus.algorithms.select;

import java.util.SplittableRandom;

/**
 * Randomized Quickselect (Hoare's FIND with a uniformly random pivot) and nearest-rank percentiles.
 *
 * <pre>
 * SELECT(A, k):                     // k is 0-based
 *   lo = 0, hi = n - 1
 *   loop:
 *     p = A[random index in [lo, hi]]
 *     three-way partition A[lo..hi] into  &lt; p | = p | &gt; p   (lt, gt are the "= p" bounds)
 *     if k &lt; lt: hi = lt - 1   elif k &gt; gt: lo = gt + 1   else return p
 * </pre>
 *
 * <p><b>Complexity.</b> Expected O(n) comparisons (about 3.4n for the median). The worst case is
 * O(n^2) but needs consistently bad random pivots, which happens with vanishing probability and
 * does not depend on the input order. Three-way partitioning keeps runs of equal values (common in
 * impact data) linear. O(1) extra space: the input copy is permuted in place.
 *
 * <p><b>Reproducibility.</b> The pivot generator is seeded, so the same input and seed always take
 * the same path and produce the same comparison count. The returned value never depends on the seed.
 */
public final class RandomizedQuickselect {

    /** Selected value plus the number of element comparisons used. */
    public record Selection(double value, long comparisons) {}

    private RandomizedQuickselect() {}

    /** k-th smallest value (0-based) of {@code values}; the input array is not modified. */
    public static Selection select(double[] values, int k, long seed) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("cannot select from an empty list");
        }
        if (k < 0 || k >= values.length) {
            throw new IllegalArgumentException("k = " + k + " is outside [0, " + values.length + ")");
        }
        double[] a = values.clone();
        SplittableRandom rnd = new SplittableRandom(seed);
        long comparisons = 0;
        int lo = 0;
        int hi = a.length - 1;
        while (true) {
            if (lo == hi) {
                return new Selection(a[lo], comparisons);
            }
            double pivot = a[lo + rnd.nextInt(hi - lo + 1)];
            int lt = lo;
            int i = lo;
            int gt = hi;
            while (i <= gt) {
                comparisons++;
                if (a[i] < pivot) {
                    swap(a, lt++, i++);
                } else if (a[i] > pivot) {
                    swap(a, i, gt--);
                } else {
                    i++;
                }
            }
            if (k < lt) {
                hi = lt - 1;
            } else if (k > gt) {
                lo = gt + 1;
            } else {
                return new Selection(pivot, comparisons);
            }
        }
    }

    /**
     * Nearest-rank percentile: the value at 1-based rank ceil(p / 100 * n). For p = 0 the minimum is
     * returned. {@code p} must be in [0, 100].
     */
    public static Selection percentile(double[] values, double p, long seed) {
        if (!(p >= 0 && p <= 100)) {
            throw new IllegalArgumentException("percentile must be in [0, 100]");
        }
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("cannot take a percentile of an empty list");
        }
        int rank = (int) Math.ceil(p / 100.0 * values.length);
        return select(values, Math.max(rank, 1) - 1, seed);
    }

    private static void swap(double[] a, int i, int j) {
        double t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
