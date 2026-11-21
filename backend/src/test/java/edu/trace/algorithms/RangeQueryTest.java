package edu.trace.algorithms;

import edu.trace.algorithms.rbtree.RedBlackTree;
import edu.trace.algorithms.segtree.SegmentTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Range operations added for the Mumbai workflows: red-black range scan/count and segment-tree range min. */
class RangeQueryTest {

    @Test
    @DisplayName("RB-9 (range scan, seed 12): forEachInRange and countInRange match TreeMap.subMap")
    void redBlackRange() {
        SplittableRandom rnd = new SplittableRandom(12);
        RedBlackTree<Integer, Integer> t = new RedBlackTree<>();
        TreeMap<Integer, Integer> o = new TreeMap<>();
        for (int i = 0; i < 3000; i++) {
            int k = rnd.nextInt(10_000);
            t.put(k, i);
            o.put(k, i);
        }
        for (int q = 0; q < 300; q++) {
            int a = rnd.nextInt(10_000);
            int b = rnd.nextInt(10_000);
            int lo = Math.min(a, b);
            int hi = Math.max(a, b);
            List<Integer> got = new ArrayList<>();
            t.forEachInRange(lo, hi, Integer.MAX_VALUE, (k, v) -> got.add(k));
            assertEquals(new ArrayList<>(o.subMap(lo, true, hi, true).keySet()), got);
            assertEquals(got.size(), t.countInRange(lo, hi));
        }
        List<Integer> limited = new ArrayList<>();
        assertEquals(5, t.forEachInRange(0, 10_000, 5, (k, v) -> limited.add(k)));
        assertEquals(new ArrayList<>(o.keySet()).subList(0, 5), limited);
        assertEquals(0, t.countInRange(50, 10));
    }

    @Test
    @DisplayName("ST-7 (range min, seed 9): rangeMin with lazy adds matches a plain array, leftmost on ties")
    void segmentTreeMin() {
        SplittableRandom rnd = new SplittableRandom(9);
        int n = 300;
        long[] a = new long[n];
        for (int i = 0; i < n; i++) {
            a[i] = rnd.nextInt(20);
        }
        SegmentTree t = new SegmentTree(a);
        for (int step = 0; step < 3000; step++) {
            int l = rnd.nextInt(n);
            int r = l + rnd.nextInt(n - l);
            if (rnd.nextBoolean()) {
                long d = rnd.nextInt(11) - 5;
                t.rangeAdd(l, r, d);
                for (int i = l; i <= r; i++) {
                    a[i] += d;
                }
            } else {
                int arg = l;
                for (int i = l; i <= r; i++) {
                    if (a[i] < a[arg]) {
                        arg = i;
                    }
                }
                assertEquals(new SegmentTree.MaxResult(a[arg], arg), t.rangeMin(l, r), "step " + step);
            }
        }
    }
}
