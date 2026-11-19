package edu.nexus.algorithms.segtree;

/**
 * Segment tree over a fixed-length series of long values (e.g. hourly demand or shortfall), with
 * lazy propagation.
 *
 * <p>Each internal node stores, for its index range: the sum, the maximum, and the leftmost index of
 * that maximum. A pending "add delta to the whole range" tag is kept in {@code lazy} and pushed down
 * only when a query or update needs to look inside the range.
 *
 * <pre>
 * QUERY(node, [lo, hi], [l, r]):
 *   if [lo,hi] inside [l,r]: return node aggregate
 *   push lazy tag to children; recurse into the overlapping children; combine
 * RANGE-ADD(node, [lo, hi], [l, r], d):
 *   if [lo,hi] inside [l,r]: sum += d * len; max += d; lazy += d; return
 *   push; recurse; pull (recompute from children)
 * </pre>
 *
 * <p><b>Complexity.</b> Build O(n). Range sum, range max, range add and point set each touch
 * O(log n) nodes (at most ~4 per level). Space O(n) (arrays of size 4n).
 *
 * <p><b>Baseline.</b> A plain array answers a range query or a range add in O(r - l + 1). The tree
 * pays off when many updates and queries are interleaved; for a static series with one query, a
 * prefix-sum array is simpler and faster.
 */
public final class SegmentTree {

    /** Maximum value in a range and its leftmost position. */
    public record MaxResult(long value, int index) {}

    private final int n;
    private final long[] sum;
    private final long[] max;
    private final int[] maxIdx;
    private final long[] lazy;
    private long nodesVisited;

    public SegmentTree(long[] values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("a segment tree needs at least one value");
        }
        n = values.length;
        sum = new long[4 * n];
        max = new long[4 * n];
        maxIdx = new int[4 * n];
        lazy = new long[4 * n];
        build(1, 0, n - 1, values);
    }

    public int size() {
        return n;
    }

    /** Nodes touched by all operations so far (work counter for benchmarks). */
    public long nodesVisited() {
        return nodesVisited;
    }

    public long rangeSum(int l, int r) {
        checkRange(l, r);
        return sum(1, 0, n - 1, l, r);
    }

    public MaxResult rangeMax(int l, int r) {
        checkRange(l, r);
        long[] out = {Long.MIN_VALUE, -1};
        maxQuery(1, 0, n - 1, l, r, out);
        return new MaxResult(out[0], (int) out[1]);
    }

    /** Adds {@code delta} to every value in [l, r]. */
    public void rangeAdd(int l, int r, long delta) {
        checkRange(l, r);
        add(1, 0, n - 1, l, r, delta);
    }

    /** Sets position i to {@code value} (implemented as a one-element range add). */
    public void pointSet(int i, long value) {
        checkRange(i, i);
        rangeAdd(i, i, value - rangeSum(i, i));
    }

    /** Current values (O(n log n); for display only). */
    public long[] toArray() {
        long[] a = new long[n];
        for (int i = 0; i < n; i++) {
            a[i] = rangeSum(i, i);
        }
        return a;
    }

    // ---------------------------------------------------------------- internals

    private void build(int node, int lo, int hi, long[] v) {
        if (lo == hi) {
            sum[node] = v[lo];
            max[node] = v[lo];
            maxIdx[node] = lo;
            return;
        }
        int mid = (lo + hi) >>> 1;
        build(2 * node, lo, mid, v);
        build(2 * node + 1, mid + 1, hi, v);
        pull(node);
    }

    private void pull(int node) {
        int a = 2 * node;
        int b = a + 1;
        sum[node] = sum[a] + sum[b];
        if (max[a] >= max[b]) { // ties prefer the left child, giving the leftmost maximum
            max[node] = max[a];
            maxIdx[node] = maxIdx[a];
        } else {
            max[node] = max[b];
            maxIdx[node] = maxIdx[b];
        }
    }

    private void apply(int node, int lo, int hi, long delta) {
        sum[node] += delta * (hi - lo + 1);
        max[node] += delta;
        lazy[node] += delta;
    }

    private void push(int node, int lo, int hi) {
        if (lazy[node] != 0 && lo < hi) {
            int mid = (lo + hi) >>> 1;
            apply(2 * node, lo, mid, lazy[node]);
            apply(2 * node + 1, mid + 1, hi, lazy[node]);
            lazy[node] = 0;
        }
    }

    private long sum(int node, int lo, int hi, int l, int r) {
        nodesVisited++;
        if (r < lo || hi < l) {
            return 0;
        }
        if (l <= lo && hi <= r) {
            return sum[node];
        }
        push(node, lo, hi);
        int mid = (lo + hi) >>> 1;
        return sum(2 * node, lo, mid, l, r) + sum(2 * node + 1, mid + 1, hi, l, r);
    }

    private void maxQuery(int node, int lo, int hi, int l, int r, long[] out) {
        nodesVisited++;
        if (r < lo || hi < l) {
            return;
        }
        if (l <= lo && hi <= r) {
            if (max[node] > out[0]) { // strict: left parts are visited first, keeping the leftmost
                out[0] = max[node];
                out[1] = maxIdx[node];
            }
            return;
        }
        push(node, lo, hi);
        int mid = (lo + hi) >>> 1;
        maxQuery(2 * node, lo, mid, l, r, out);
        maxQuery(2 * node + 1, mid + 1, hi, l, r, out);
    }

    private void add(int node, int lo, int hi, int l, int r, long delta) {
        nodesVisited++;
        if (r < lo || hi < l) {
            return;
        }
        if (l <= lo && hi <= r) {
            apply(node, lo, hi, delta);
            return;
        }
        push(node, lo, hi);
        int mid = (lo + hi) >>> 1;
        add(2 * node, lo, mid, l, r, delta);
        add(2 * node + 1, mid + 1, hi, l, r, delta);
        pull(node);
    }

    private void checkRange(int l, int r) {
        if (l < 0 || r >= n || l > r) {
            throw new IllegalArgumentException("range [" + l + ", " + r + "] is invalid for length " + n);
        }
    }
}
