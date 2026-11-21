package edu.trace.algorithms;

import edu.trace.algorithms.rbtree.PriorityKey;
import edu.trace.algorithms.rbtree.RedBlackTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedBlackTreeTest {

    @Test
    @DisplayName("RB-1 (rotation): inserting 1,2,3 triggers one left rotation; 2 becomes the black root")
    void singleRotation() {
        RedBlackTree<Integer, String> t = new RedBlackTree<>();
        t.put(1, "a");
        t.put(2, "b");
        t.put(3, "c");
        t.validate();
        assertEquals(1, t.rotationCount());
        RedBlackTree.NodeView root = t.snapshot().get(0);
        assertEquals("2", root.key());
        assertEquals("BLACK", root.color());
        assertEquals(2, t.height());
    }

    @Test
    @DisplayName("RB-2 (rotation): inserting 3,1,2 triggers the inner-child case (two rotations)")
    void doubleRotation() {
        RedBlackTree<Integer, String> t = new RedBlackTree<>();
        List<String> log = new ArrayList<>();
        t.setEventLog(log);
        t.put(3, "c");
        t.put(1, "a");
        t.put(2, "b");
        t.validate();
        assertEquals(2, t.rotationCount());
        assertTrue(log.stream().anyMatch(s -> s.contains("case 2")));
        assertEquals("2", t.snapshot().get(0).key());
    }

    @Test
    @DisplayName("RB-3: sorted inserts keep height <= 2 log2(n+1) and black height equal on all paths")
    void balancedOnSortedInsert() {
        RedBlackTree<Integer, Integer> t = new RedBlackTree<>();
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            t.put(i, i);
        }
        t.validate();
        assertTrue(t.height() <= 2 * Math.log(n + 1) / Math.log(2));
        assertEquals(n, t.size());
    }

    @Test
    @DisplayName("RB-4 (deletion, seed 99): 20k random puts/removes match TreeMap; invariants hold throughout")
    void randomOperationsMatchTreeMap() {
        SplittableRandom rnd = new SplittableRandom(99);
        RedBlackTree<Integer, Long> t = new RedBlackTree<>();
        TreeMap<Integer, Long> oracle = new TreeMap<>();
        for (int step = 0; step < 20_000; step++) {
            int key = rnd.nextInt(2000);
            if (rnd.nextInt(3) == 0) {
                assertEquals(oracle.remove(key) != null, t.remove(key));
            } else {
                long v = rnd.nextInt(1000);
                t.put(key, v);
                oracle.put(key, v);
            }
            if (step % 500 == 0) {
                t.validate();
            }
        }
        t.validate();
        assertEquals(new ArrayList<>(oracle.keySet()), t.keysInOrder());
        oracle.forEach((k, v) -> assertEquals(v, t.get(k)));
    }

    @Test
    @DisplayName("RB-5 (deletion): removing every key in random order leaves a valid empty tree")
    void deleteAll() {
        RedBlackTree<Integer, Integer> t = new RedBlackTree<>();
        List<Integer> keys = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            keys.add(i);
            t.put(i, i);
        }
        Collections.shuffle(keys, new Random(1));
        for (int k : keys) {
            assertTrue(t.remove(k));
            if (k % 300 == 0) {
                t.validate();
            }
        }
        t.validate();
        assertTrue(t.isEmpty());
        assertEquals(0, t.size());
    }

    @Test
    @DisplayName("RB-6: rank and select are inverse and agree with in-order position")
    void rankAndSelect() {
        RedBlackTree<Integer, Integer> t = new RedBlackTree<>();
        int[] keys = {50, 20, 80, 10, 30, 70, 90, 60};
        for (int k : keys) {
            t.put(k, k);
        }
        List<Integer> inOrder = t.keysInOrder();
        for (int i = 0; i < inOrder.size(); i++) {
            assertEquals(i + 1, t.rank(inOrder.get(i)));
            assertEquals(inOrder.get(i), t.select(i + 1));
        }
        assertEquals(-1, t.rank(55));
    }

    @Test
    @DisplayName("RB-7 (priority key): higher score first, ties broken by id; update = delete + reinsert")
    void priorityOrdering() {
        RedBlackTree<PriorityKey, String> t = new RedBlackTree<>();
        t.put(new PriorityKey(900, "INC-2"), "b");
        t.put(new PriorityKey(900, "INC-1"), "a");
        t.put(new PriorityKey(1200, "INC-9"), "c");
        assertEquals(List.of(new PriorityKey(1200, "INC-9"), new PriorityKey(900, "INC-1"), new PriorityKey(900, "INC-2")),
                t.keysInOrder());
        assertTrue(t.remove(new PriorityKey(900, "INC-2")));
        t.put(new PriorityKey(1500, "INC-2"), "b");
        assertEquals("INC-2", t.select(1).zoneId());
        t.validate();
    }

    @Test
    @DisplayName("RB-8 (boundary): empty tree, missing keys, duplicate insert replaces value only")
    void boundaryCases() {
        RedBlackTree<Integer, String> t = new RedBlackTree<>();
        assertFalse(t.remove(7));
        assertNull(t.get(7));
        assertEquals(0, t.height());
        assertTrue(t.put(5, "x"));
        assertFalse(t.put(5, "y"), "second insert of the same key is an update");
        assertEquals("y", t.get(5));
        assertEquals(1, t.size());
        assertThrows(NullPointerException.class, () -> t.put(null, "z"));
        assertThrows(IllegalArgumentException.class, () -> t.select(2));
    }
}
