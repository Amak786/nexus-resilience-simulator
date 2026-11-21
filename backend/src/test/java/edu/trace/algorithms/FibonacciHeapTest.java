package edu.trace.algorithms;

import edu.trace.algorithms.heap.FibonacciHeap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FibonacciHeapTest {

    @Test
    @DisplayName("FH-1 (empty heap): extract and peek throw; validate passes on empty")
    void emptyHeap() {
        FibonacciHeap<String> h = new FibonacciHeap<>();
        h.validate();
        assertTrue(h.isEmpty());
        assertThrows(NoSuchElementException.class, h::extractMin);
        assertThrows(NoSuchElementException.class, h::peekMin);
    }

    @Test
    @DisplayName("FH-2 (consolidation): after extract-min the root degrees are all distinct")
    void consolidationGivesDistinctDegrees() {
        FibonacciHeap<Integer> h = new FibonacciHeap<>();
        for (int i = 0; i < 64; i++) {
            h.insert(i, i);
        }
        assertEquals(64, h.rootDegrees().size(), "inserts are lazy: 64 one-node trees");
        assertEquals(0.0, h.extractMin().key());
        h.validate();
        List<Integer> deg = h.rootDegrees();
        assertEquals(deg.size(), deg.stream().distinct().count(), "consolidation leaves at most one root per degree");
        assertTrue(h.linkCount() > 0);
    }

    @Test
    @DisplayName("FH-3 (decrease-key): moving a deep node to the top makes it the new minimum and cuts it")
    void decreaseKeyCut() {
        FibonacciHeap<Integer> h = new FibonacciHeap<>();
        List<FibonacciHeap.Node<Integer>> nodes = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            nodes.add(h.insert(100 + i, i));
        }
        h.extractMin(); // builds trees
        h.validate();
        FibonacciHeap.Node<Integer> deep = nodes.get(31);
        h.decreaseKey(deep, 1);
        h.validate();
        assertEquals(1.0, h.peekMin().key());
        assertEquals(31, h.extractMin().value());
        assertTrue(h.cutCount() >= 1, "a non-root node must be cut when it becomes smaller than its parent");
    }

    @Test
    @DisplayName("FH-4 (cascading cut): repeated decrease-keys under one parent trigger a cascading cut")
    void cascadingCut() {
        FibonacciHeap<Integer> h = new FibonacciHeap<>();
        List<FibonacciHeap.Node<Integer>> nodes = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            nodes.add(h.insert(1000 + i, i));
        }
        h.extractMin();
        SplittableRandom rnd = new SplittableRandom(4);
        double next = 999;
        for (int k = 0; k < 120 && h.cascadingCutCount() == 0; k++) {
            FibonacciHeap.Node<Integer> x = nodes.get(1 + rnd.nextInt(127));
            if (x.key() > next) {
                h.decreaseKey(x, next);
                next -= 1;
            }
            h.validate();
        }
        assertTrue(h.cascadingCutCount() > 0, "a parent losing its second child must be cut as well");
    }

    @Test
    @DisplayName("FH-5 (duplicates + oracle, seed 7): 20k random insert/decrease/extract ops match java.util.PriorityQueue")
    void matchesPriorityQueue() {
        SplittableRandom rnd = new SplittableRandom(7);
        FibonacciHeap<Integer> h = new FibonacciHeap<>();
        PriorityQueue<Double> oracle = new PriorityQueue<>();
        List<FibonacciHeap.Node<Integer>> live = new ArrayList<>();
        for (int step = 0; step < 20_000; step++) {
            int op = rnd.nextInt(10);
            if (op < 5 || h.isEmpty()) {
                double key = rnd.nextInt(50); // many duplicate priorities
                live.add(h.insert(key, step));
                oracle.add(key);
            } else if (op < 8) {
                FibonacciHeap.Node<Integer> m = h.extractMin();
                assertEquals(oracle.poll(), m.key(), "step " + step);
                live.remove(m);
            } else {
                FibonacciHeap.Node<Integer> x = live.get(rnd.nextInt(live.size()));
                double nk = x.key() - rnd.nextInt(5);
                oracle.remove(x.key());
                oracle.add(nk);
                h.decreaseKey(x, nk);
            }
            if (step % 1000 == 0) {
                h.validate();
            }
            assertEquals(oracle.size(), h.size());
        }
        h.validate();
    }

    @Test
    @DisplayName("FH-6 (invalid input): increasing a key, NaN keys and stale handles are rejected")
    void invalidInput() {
        FibonacciHeap<Integer> h = new FibonacciHeap<>();
        FibonacciHeap.Node<Integer> a = h.insert(5, 1);
        assertThrows(IllegalArgumentException.class, () -> h.decreaseKey(a, 9));
        assertThrows(IllegalArgumentException.class, () -> h.insert(Double.NaN, 2));
        h.extractMin();
        assertThrows(IllegalArgumentException.class, () -> h.decreaseKey(a, 1));
    }
}
