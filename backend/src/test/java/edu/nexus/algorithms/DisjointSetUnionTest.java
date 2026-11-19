package edu.nexus.algorithms;

import edu.nexus.algorithms.dsu.DisjointSetUnion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisjointSetUnionTest {

    @Test
    @DisplayName("DSU-1: unions merge sets transitively; counts and sizes are right")
    void unionAndFind() {
        DisjointSetUnion d = new DisjointSetUnion(6);
        d.union(0, 1);
        d.union(1, 2);
        d.union(3, 4);
        assertTrue(d.connected(0, 2));
        assertFalse(d.connected(0, 3));
        assertEquals(3, d.componentCount());
        assertEquals(3, d.componentSize(2));
    }

    @Test
    @DisplayName("DSU-2: repeated union returns false and changes nothing")
    void repeatedUnion() {
        DisjointSetUnion d = new DisjointSetUnion(3);
        assertTrue(d.union(0, 1));
        assertFalse(d.union(1, 0));
        assertFalse(d.union(0, 1));
        assertEquals(2, d.componentCount());
    }

    @Test
    @DisplayName("DSU-3 (boundary): empty and single-element structures")
    void tinySets() {
        assertEquals(0, new DisjointSetUnion(0).componentCount());
        DisjointSetUnion one = new DisjointSetUnion(1);
        assertEquals(0, one.find(0));
        assertFalse(one.union(0, 0), "self-union is a no-op");
        assertEquals(1, one.componentCount());
    }

    @Test
    @DisplayName("DSU-4: path compression points every visited node at the root")
    void pathCompression() {
        DisjointSetUnion d = new DisjointSetUnion(8);
        for (int i = 1; i < 8; i++) {
            d.union(0, i);
        }
        int root = d.find(7);
        for (int i = 0; i < 8; i++) {
            d.find(i);
            assertEquals(root, d.parentSnapshot()[i]);
        }
        assertTrue(d.rankOf(root) <= 3, "union by rank keeps rank <= log2(8)");
    }

    @Test
    @DisplayName("DSU-5 (property, seed 11): matches a naive relabelling oracle over 400 random unions")
    void matchesOracle() {
        SplittableRandom rnd = new SplittableRandom(11);
        int n = 300;
        DisjointSetUnion d = new DisjointSetUnion(n);
        int[] label = new int[n];
        for (int i = 0; i < n; i++) {
            label[i] = i;
        }
        for (int step = 0; step < 400; step++) {
            int a = rnd.nextInt(n);
            int b = rnd.nextInt(n);
            d.union(a, b);
            int from = label[b];
            int to = label[a];
            for (int i = 0; i < n; i++) {
                if (label[i] == from) {
                    label[i] = to;
                }
            }
            int x = rnd.nextInt(n);
            int y = rnd.nextInt(n);
            assertEquals(label[x] == label[y], d.connected(x, y), "step " + step);
        }
    }

    @Test
    @DisplayName("DSU-6 (invalid input): out-of-range elements and negative size are rejected")
    void invalidInput() {
        DisjointSetUnion d = new DisjointSetUnion(3);
        assertThrows(IllegalArgumentException.class, () -> d.find(3));
        assertThrows(IllegalArgumentException.class, () -> d.union(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new DisjointSetUnion(-1));
    }
}
