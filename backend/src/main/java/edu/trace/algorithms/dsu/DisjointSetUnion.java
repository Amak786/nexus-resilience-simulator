package edu.trace.algorithms.dsu;

/**
 * Disjoint-set union (union-find) with union by rank and full path compression.
 *
 * <pre>
 * FIND(x):  r = x; while parent[r] != r: r = parent[r]          // locate root
 *           while parent[x] != r: nxt = parent[x]; parent[x] = r; x = nxt   // compress
 *           return r
 * UNION(a, b): ra = FIND(a); rb = FIND(b); if ra == rb return false
 *              attach the root of smaller rank under the other (ranks equal: either, rank++)
 * </pre>
 *
 * <p><b>Complexity.</b> m operations on n elements take O(m * alpha(n)) amortized time, where alpha is
 * the inverse Ackermann function (at most 4 for any realistic n). Space O(n).
 *
 * <p><b>Limitation.</b> DSU only supports merging. It cannot split a set when an edge is deleted, so a
 * road failure is handled by rebuilding the structure from the modified graph (O((V + E) alpha(V))).
 */
public final class DisjointSetUnion {

    private final int[] parent;
    private final int[] rank;
    private final int[] size;
    private int components;
    private long pointerHops;

    public DisjointSetUnion(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        parent = new int[n];
        rank = new int[n];
        size = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
            size[i] = 1;
        }
        components = n;
    }

    public int elementCount() {
        return parent.length;
    }

    public int find(int x) {
        check(x);
        int r = x;
        while (parent[r] != r) {
            r = parent[r];
            pointerHops++;
        }
        while (parent[x] != r) {
            int nxt = parent[x];
            parent[x] = r;
            x = nxt;
        }
        return r;
    }

    /** Merges the sets of a and b; returns {@code false} if they were already in the same set. */
    public boolean union(int a, int b) {
        int ra = find(a);
        int rb = find(b);
        if (ra == rb) {
            return false;
        }
        if (rank[ra] < rank[rb]) {
            int tmp = ra;
            ra = rb;
            rb = tmp;
        }
        parent[rb] = ra;
        size[ra] += size[rb];
        if (rank[ra] == rank[rb]) {
            rank[ra]++;
        }
        components--;
        return true;
    }

    public boolean connected(int a, int b) {
        return find(a) == find(b);
    }

    public int componentCount() {
        return components;
    }

    public int componentSize(int x) {
        return size[find(x)];
    }

    /** Copy of the parent array (for visualising the forest). */
    public int[] parentSnapshot() {
        return parent.clone();
    }

    public int rankOf(int x) {
        check(x);
        return rank[x];
    }

    /** Parent-pointer steps taken by all finds so far (work counter for benchmarks). */
    public long pointerHops() {
        return pointerHops;
    }

    private void check(int x) {
        if (x < 0 || x >= parent.length) {
            throw new IllegalArgumentException("element " + x + " is outside [0, " + parent.length + ")");
        }
    }
}
