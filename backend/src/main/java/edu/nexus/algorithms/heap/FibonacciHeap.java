package edu.nexus.algorithms.heap;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Fibonacci heap (Fredman and Tarjan, 1987) keyed by {@code double}, as in CLRS chapter 19.
 *
 * <p>The heap is a circular, doubly linked list of heap-ordered trees (the root list) plus a pointer to
 * the minimum root. Work is deferred: insert just adds a one-node tree; extract-min removes the minimum,
 * promotes its children to roots and then <b>consolidates</b>, linking roots of equal degree until all
 * root degrees differ. Decrease-key cuts a node that violates heap order and moves it to the root
 * list; a parent that loses a second child is cut too (<b>cascading cut</b>, tracked with {@code mark}).
 *
 * <pre>
 * Amortized costs (potential = roots + 2 * marked):
 *   insert, min, decreaseKey          O(1)
 *   extractMin                         O(log n)   (max degree D(n) &lt;= log_phi n, phi = golden ratio)
 * </pre>
 *
 * <p>With these costs Dijkstra runs in O(E + V log V) instead of O((V + E) log V) with a binary heap.
 * Whether that is faster in practice depends on the graph; {@code DijkstraBenchmark} measures it.
 */
public final class FibonacciHeap<T> {

    /** A heap entry. Keep the handle to call {@link #decreaseKey}. */
    public static final class Node<T> {
        private double key;
        private final T value;
        private int degree;
        private boolean mark;
        private Node<T> parent;
        private Node<T> child;
        private Node<T> left = this;
        private Node<T> right = this;
        private boolean inHeap = true;

        private Node(double key, T value) {
            this.key = key;
            this.value = value;
        }

        public double key() {
            return key;
        }

        public T value() {
            return value;
        }
    }

    private Node<T> min;
    private int size;
    private long links;
    private long cuts;
    private long cascadingCuts;

    public boolean isEmpty() {
        return min == null;
    }

    public int size() {
        return size;
    }

    /** Tree links performed during consolidation (work counter). */
    public long linkCount() {
        return links;
    }

    public long cutCount() {
        return cuts;
    }

    public long cascadingCutCount() {
        return cascadingCuts;
    }

    /** O(1): adds a single-node tree to the root list. */
    public Node<T> insert(double key, T value) {
        checkKey(key);
        Node<T> x = new Node<>(key, value);
        min = mergeLists(min, x);
        if (x.key < min.key) {
            min = x;
        }
        size++;
        return x;
    }

    /** O(1): the entry with the smallest key. */
    public Node<T> peekMin() {
        if (min == null) {
            throw new NoSuchElementException("heap is empty");
        }
        return min;
    }

    /** O(log n) amortized: removes and returns the minimum entry. */
    public Node<T> extractMin() {
        Node<T> z = min;
        if (z == null) {
            throw new NoSuchElementException("heap is empty");
        }
        // Promote every child of z to the root list.
        if (z.child != null) {
            Node<T> c = z.child;
            do {
                c.parent = null;
                c.mark = false;
                c = c.right;
            } while (c != z.child);
            mergeLists(z, z.child);
            z.child = null;
        }
        // Remove z from the root list.
        if (z.right == z) {
            min = null;
        } else {
            z.left.right = z.right;
            z.right.left = z.left;
            min = z.right;
            consolidate();
        }
        z.left = z;
        z.right = z;
        z.degree = 0;
        z.inHeap = false;
        size--;
        return z;
    }

    /** O(1) amortized: lowers the key of {@code x}; it must not exceed the current key. */
    public void decreaseKey(Node<T> x, double newKey) {
        checkKey(newKey);
        if (!x.inHeap) {
            throw new IllegalArgumentException("node is no longer in the heap");
        }
        if (newKey > x.key) {
            throw new IllegalArgumentException("new key " + newKey + " is greater than current key " + x.key);
        }
        x.key = newKey;
        Node<T> y = x.parent;
        if (y != null && x.key < y.key) {
            cut(x, y);
            cascadingCut(y);
        }
        if (x.key < min.key) {
            min = x;
        }
    }

    /**
     * Checks every structural invariant: circular sibling lists, parent pointers, heap order, degree
     * equals number of children, roots unmarked-or-not irrelevant, min is the smallest root, node count
     * equals size. Throws {@link IllegalStateException} on the first violation.
     */
    public void validate() {
        if (min == null) {
            if (size != 0) {
                throw new IllegalStateException("empty root list but size " + size);
            }
            return;
        }
        Map<Node<T>, Boolean> seen = new IdentityHashMap<>();
        int count = 0;
        Node<T> r = min;
        do {
            if (r.parent != null) {
                throw new IllegalStateException("root has a parent");
            }
            if (r.key < min.key) {
                throw new IllegalStateException("min pointer is not the smallest root");
            }
            count += checkTree(r, seen);
            r = r.right;
        } while (r != min);
        if (count != size) {
            throw new IllegalStateException("node count " + count + " differs from size " + size);
        }
    }

    /** Root-list degrees (for visualisation and tests). */
    public List<Integer> rootDegrees() {
        List<Integer> out = new ArrayList<>();
        if (min == null) {
            return out;
        }
        Node<T> r = min;
        do {
            out.add(r.degree);
            r = r.right;
        } while (r != min);
        return out;
    }

    // ---------------------------------------------------------------- internals

    private void consolidate() {
        int maxDegree = (int) Math.floor(Math.log(Math.max(size, 1)) / Math.log((1 + Math.sqrt(5)) / 2)) + 2;
        @SuppressWarnings("unchecked")
        Node<T>[] byDegree = (Node<T>[]) new Node[maxDegree + 1];
        List<Node<T>> roots = new ArrayList<>();
        Node<T> w = min;
        do {
            roots.add(w);
            w = w.right;
        } while (w != min);
        for (Node<T> x : roots) {
            int d = x.degree;
            while (byDegree[d] != null) {
                Node<T> y = byDegree[d];
                if (y.key < x.key) {
                    Node<T> t = x;
                    x = y;
                    y = t;
                }
                link(y, x);
                byDegree[d] = null;
                d++;
            }
            byDegree[d] = x;
        }
        min = null;
        for (Node<T> x : byDegree) {
            if (x != null) {
                x.left = x;
                x.right = x;
                min = mergeLists(min, x);
                if (x.key < min.key) {
                    min = x;
                }
            }
        }
    }

    /** Makes root y a child of root x (requires y.key >= x.key). */
    private void link(Node<T> y, Node<T> x) {
        links++;
        y.left.right = y.right;
        y.right.left = y.left;
        y.left = y;
        y.right = y;
        y.parent = x;
        x.child = mergeLists(x.child, y);
        x.degree++;
        y.mark = false;
    }

    private void cut(Node<T> x, Node<T> y) {
        cuts++;
        if (x.right == x) {
            y.child = null;
        } else {
            x.left.right = x.right;
            x.right.left = x.left;
            if (y.child == x) {
                y.child = x.right;
            }
        }
        y.degree--;
        x.left = x;
        x.right = x;
        x.parent = null;
        x.mark = false;
        min = mergeLists(min, x);
    }

    private void cascadingCut(Node<T> y) {
        Node<T> z = y.parent;
        while (z != null) {
            if (!y.mark) {
                y.mark = true;
                return;
            }
            cascadingCuts++;
            cut(y, z);
            y = z;
            z = y.parent;
        }
    }

    /** Splices two circular lists; returns a node of the merged list (prefers a). */
    private static <T> Node<T> mergeLists(Node<T> a, Node<T> b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        Node<T> aRight = a.right;
        Node<T> bLeft = b.left;
        a.right = b;
        b.left = a;
        aRight.left = bLeft;
        bLeft.right = aRight;
        return a;
    }

    private int checkTree(Node<T> x, Map<Node<T>, Boolean> seen) {
        if (seen.put(x, Boolean.TRUE) != null) {
            throw new IllegalStateException("node reachable twice (cycle in structure)");
        }
        if (x.left.right != x || x.right.left != x) {
            throw new IllegalStateException("broken sibling links");
        }
        int count = 1;
        int children = 0;
        if (x.child != null) {
            Node<T> c = x.child;
            do {
                children++;
                if (c.parent != x) {
                    throw new IllegalStateException("child has wrong parent");
                }
                if (c.key < x.key) {
                    throw new IllegalStateException("heap order violated: child " + c.key + " < parent " + x.key);
                }
                count += checkTree(c, seen);
                c = c.right;
            } while (c != x.child);
        }
        if (children != x.degree) {
            throw new IllegalStateException("degree " + x.degree + " but " + children + " children");
        }
        return count;
    }

    private static void checkKey(double k) {
        if (Double.isNaN(k)) {
            throw new IllegalArgumentException("key must not be NaN");
        }
    }
}
