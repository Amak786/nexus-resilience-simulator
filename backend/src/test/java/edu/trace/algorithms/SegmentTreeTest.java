package edu.trace.algorithms;

import edu.trace.algorithms.segtree.SegmentTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentTreeTest {

    @Test
    @DisplayName("ST-1: range sum and range max on a small hand-checked series")
    void handChecked() {
        SegmentTree t = new SegmentTree(new long[] {5, 1, 9, 3, 7});
        assertEquals(25, t.rangeSum(0, 4));
        assertEquals(13, t.rangeSum(1, 3));
        assertEquals(new SegmentTree.MaxResult(9, 2), t.rangeMax(0, 4));
        assertEquals(new SegmentTree.MaxResult(7, 4), t.rangeMax(3, 4));
    }

    @Test
    @DisplayName("ST-2 (lazy propagation): range add then overlapping queries")
    void lazyRangeAdd() {
        SegmentTree t = new SegmentTree(new long[] {1, 2, 3, 4, 5, 6, 7, 8});
        t.rangeAdd(2, 5, 10);
        assertEquals(1 + 2 + 13 + 14 + 15 + 16 + 7 + 8, t.rangeSum(0, 7));
        assertEquals(29, t.rangeSum(3, 4));
        assertEquals(new SegmentTree.MaxResult(16, 5), t.rangeMax(0, 7));
        t.rangeAdd(0, 7, -20);
        assertEquals(new SegmentTree.MaxResult(-4, 5), t.rangeMax(0, 7));
        assertArrayEquals(new long[] {-19, -18, -7, -6, -5, -4, -13, -12}, t.toArray());
    }

    @Test
    @DisplayName("ST-3 (boundary): single element, full range, point set, ties give the leftmost max")
    void boundaries() {
        SegmentTree one = new SegmentTree(new long[] {42});
        assertEquals(42, one.rangeSum(0, 0));
        one.pointSet(0, -3);
        assertEquals(new SegmentTree.MaxResult(-3, 0), one.rangeMax(0, 0));
        SegmentTree ties = new SegmentTree(new long[] {4, 9, 2, 9, 9});
        assertEquals(1, ties.rangeMax(0, 4).index());
        assertEquals(3, ties.rangeMax(2, 4).index());
    }

    @Test
    @DisplayName("ST-4 (property, seed 5): 5,000 random adds/sums/maxes match a plain array")
    void matchesNaiveArray() {
        SplittableRandom rnd = new SplittableRandom(5);
        int n = 257;
        long[] a = new long[n];
        for (int i = 0; i < n; i++) {
            a[i] = rnd.nextInt(1000) - 200;
        }
        SegmentTree t = new SegmentTree(a);
        for (int step = 0; step < 5000; step++) {
            int l = rnd.nextInt(n);
            int r = l + rnd.nextInt(n - l);
            int op = rnd.nextInt(3);
            if (op == 0) {
                long d = rnd.nextInt(101) - 50;
                t.rangeAdd(l, r, d);
                for (int i = l; i <= r; i++) {
                    a[i] += d;
                }
            } else if (op == 1) {
                long s = 0;
                for (int i = l; i <= r; i++) {
                    s += a[i];
                }
                assertEquals(s, t.rangeSum(l, r));
            } else {
                int arg = l;
                for (int i = l; i <= r; i++) {
                    if (a[i] > a[arg]) {
                        arg = i;
                    }
                }
                assertEquals(new SegmentTree.MaxResult(a[arg], arg), t.rangeMax(l, r));
            }
        }
    }

    @Test
    @DisplayName("ST-5: a query touches O(log n) nodes, far fewer than the range length")
    void logarithmicWork() {
        SegmentTree t = new SegmentTree(new long[1 << 16]);
        long before = t.nodesVisited();
        t.rangeSum(1, (1 << 16) - 2);
        long visited = t.nodesVisited() - before;
        assertTrue(visited <= 4 * 17, "visited " + visited + " nodes");
    }

    @Test
    @DisplayName("ST-6 (invalid input): empty series and bad ranges are rejected")
    void invalidInput() {
        assertThrows(IllegalArgumentException.class, () -> new SegmentTree(new long[0]));
        SegmentTree t = new SegmentTree(new long[] {1, 2, 3});
        assertThrows(IllegalArgumentException.class, () -> t.rangeSum(2, 1));
        assertThrows(IllegalArgumentException.class, () -> t.rangeSum(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> t.rangeAdd(0, 3, 1));
    }
}
