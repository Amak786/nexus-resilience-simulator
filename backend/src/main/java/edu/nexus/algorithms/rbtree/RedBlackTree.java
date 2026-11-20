package edu.nexus.algorithms.rbtree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Red-black tree map (CLRS chapter 13) with subtree-size augmentation for O(log n) rank queries.
 *
 * <p><b>Invariants</b> (checked by {@link #validate()}):
 * <ol>
 *   <li>every node is red or black and the root is black;</li>
 *   <li>a red node has no red child;</li>
 *   <li>every root-to-leaf path has the same number of black nodes (black height);</li>
 *   <li>keys are in strict BST order;</li>
 *   <li>each node's cached subtree size is correct.</li>
 * </ol>
 * Rules 2 and 3 force height &le; 2 log2(n + 1), so search, insert, delete and rank are O(log n)
 * worst case. Insert does at most 2 rotations, delete at most 3. Space O(n).
 *
 * <p>A single black sentinel {@code nil} stands for every leaf. An optional event log records which
 * fix-up case ran, so the Algorithm Lab can show why each rotation happened.
 */
public final class RedBlackTree<K extends Comparable<K>, V> {

    private static final boolean RED = true;
    private static final boolean BLACK = false;

    private final class Node {
        K key;
        V value;
        boolean color;
        int size;
        Node left;
        Node right;
        Node parent;
    }

    /** Read-only view of a node for visualisation. Child ids are -1 for leaves. */
    public record NodeView(int id, String key, String color, int left, int right, int subtreeSize) {}

    private final Node nil;
    private Node root;
    private long rotations;
    private List<String> eventLog;

    public RedBlackTree() {
        nil = new Node();
        nil.color = BLACK;
        nil.size = 0;
        nil.left = nil;
        nil.right = nil;
        nil.parent = nil;
        root = nil;
    }

    /** Start recording fix-up cases into {@code log} (pass {@code null} to stop). */
    public void setEventLog(List<String> log) {
        this.eventLog = log;
    }

    public int size() {
        return root.size;
    }

    public boolean isEmpty() {
        return root == nil;
    }

    public long rotationCount() {
        return rotations;
    }

    public V get(K key) {
        Node x = find(Objects.requireNonNull(key, "key"));
        return x == nil ? null : x.value;
    }

    public boolean containsKey(K key) {
        return find(Objects.requireNonNull(key, "key")) != nil;
    }

    /**
     * Inserts the key, or replaces the value when the key is already present.
     *
     * @return {@code true} if a new node was created
     */
    public boolean put(K key, V value) {
        Objects.requireNonNull(key, "key");
        Node y = nil;
        Node x = root;
        int cmp = 0;
        while (x != nil) {
            y = x;
            cmp = key.compareTo(x.key);
            if (cmp == 0) {
                x.value = value;
                log("update " + key + ": value replaced, no structural change");
                return false;
            }
            x = cmp < 0 ? x.left : x.right;
        }
        Node z = new Node();
        z.key = key;
        z.value = value;
        z.color = RED;
        z.size = 1;
        z.left = nil;
        z.right = nil;
        z.parent = y;
        if (y == nil) {
            root = z;
        } else if (cmp < 0) {
            y.left = z;
        } else {
            y.right = z;
        }
        for (Node p = y; p != nil; p = p.parent) {
            p.size++;
        }
        log("insert " + key + " as a red leaf");
        insertFixup(z);
        return true;
    }

    /** Removes the key. Returns {@code false} when it is absent. */
    public boolean remove(K key) {
        Node z = find(Objects.requireNonNull(key, "key"));
        if (z == nil) {
            return false;
        }
        log("delete " + key);
        if (z.left != nil && z.right != nil) {
            Node s = minimum(z.right);
            log("  two children: copy successor " + s.key + " into the node, then delete the successor");
            z.key = s.key;
            z.value = s.value;
            z = s;
        }
        for (Node p = z.parent; p != nil; p = p.parent) {
            p.size--;
        }
        Node child = z.left != nil ? z.left : z.right;
        boolean removedBlack = z.color == BLACK;
        transplant(z, child);
        if (removedBlack) {
            log("  removed a black node: restore black height");
            deleteFixup(child);
        }
        return true;
    }

    /** 1-based position of {@code key} in ascending order, or -1 when absent. O(log n). */
    public int rank(K key) {
        Objects.requireNonNull(key, "key");
        int r = 0;
        Node x = root;
        while (x != nil) {
            int cmp = key.compareTo(x.key);
            if (cmp < 0) {
                x = x.left;
            } else {
                r += x.left.size + (cmp == 0 ? 1 : 1);
                if (cmp == 0) {
                    return r;
                }
                x = x.right;
            }
        }
        return -1;
    }

    /** Key at 1-based position {@code i} in ascending order. O(log n). */
    public K select(int i) {
        if (i < 1 || i > size()) {
            throw new IllegalArgumentException("rank " + i + " is outside [1, " + size() + "]");
        }
        Node x = root;
        while (true) {
            int leftSize = x.left.size;
            if (i <= leftSize) {
                x = x.left;
            } else if (i == leftSize + 1) {
                return x.key;
            } else {
                i -= leftSize + 1;
                x = x.right;
            }
        }
    }

    /** Visits entries in ascending key order (iterative in-order traversal). */
    public void forEachInOrder(BiConsumer<K, V> visitor) {
        Deque<Node> stack = new ArrayDeque<>();
        Node x = root;
        while (x != nil || !stack.isEmpty()) {
            while (x != nil) {
                stack.push(x);
                x = x.left;
            }
            x = stack.pop();
            visitor.accept(x.key, x.value);
            x = x.right;
        }
    }

    /**
     * Visits entries with {@code lo <= key <= hi} in ascending order, stopping after {@code limit}
     * entries. O(log n + k) for k visited entries: subtrees entirely outside the range are skipped.
     *
     * @return number of entries visited
     */
    public int forEachInRange(K lo, K hi, int limit, BiConsumer<K, V> visitor) {
        Objects.requireNonNull(lo, "lo");
        Objects.requireNonNull(hi, "hi");
        if (lo.compareTo(hi) > 0 || limit <= 0) {
            return 0;
        }
        int[] count = {0};
        rangeVisit(root, lo, hi, limit, visitor, count);
        return count[0];
    }

    /** Number of keys in [lo, hi], using subtree sizes: O(log n). */
    public int countInRange(K lo, K hi) {
        if (lo.compareTo(hi) > 0) {
            return 0;
        }
        return countLess(hi, true) - countLess(lo, false);
    }

    private int countLess(K bound, boolean inclusive) {
        int c = 0;
        Node x = root;
        while (x != nil) {
            int cmp = x.key.compareTo(bound);
            if (cmp < 0 || (cmp == 0 && inclusive)) {
                c += x.left.size + 1;
                x = x.right;
            } else {
                x = x.left;
            }
        }
        return c;
    }

    private void rangeVisit(Node x, K lo, K hi, int limit, BiConsumer<K, V> visitor, int[] count) {
        if (x == nil || count[0] >= limit) {
            return;
        }
        boolean goLeft = x.key.compareTo(lo) > 0;
        boolean goRight = x.key.compareTo(hi) < 0;
        if (goLeft) {
            rangeVisit(x.left, lo, hi, limit, visitor, count);
        }
        if (count[0] < limit && x.key.compareTo(lo) >= 0 && x.key.compareTo(hi) <= 0) {
            visitor.accept(x.key, x.value);
            count[0]++;
        }
        if (goRight) {
            rangeVisit(x.right, lo, hi, limit, visitor, count);
        }
    }

    public List<K> keysInOrder() {
        List<K> keys = new ArrayList<>(size());
        forEachInOrder((k, v) -> keys.add(k));
        return keys;
    }

    /** Number of nodes on the longest root-to-leaf path. */
    public int height() {
        return heightOf(root);
    }

    /** Black nodes on any root-to-leaf path, not counting the root's own colour twice (nil counted). */
    public int blackHeight() {
        int h = 0;
        for (Node x = root; x != nil; x = x.left) {
            if (x.color == BLACK) {
                h++;
            }
        }
        return h + 1;
    }

    /** Level-order node list for drawing the tree. */
    public List<NodeView> snapshot() {
        List<NodeView> out = new ArrayList<>();
        if (root == nil) {
            return out;
        }
        Map<Node, Integer> ids = new IdentityHashMap<>();
        List<Node> order = new ArrayList<>();
        Deque<Node> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            Node x = q.poll();
            ids.put(x, order.size());
            order.add(x);
            if (x.left != nil) {
                q.add(x.left);
            }
            if (x.right != nil) {
                q.add(x.right);
            }
        }
        for (Node x : order) {
            out.add(new NodeView(ids.get(x), String.valueOf(x.key), x.color == RED ? "RED" : "BLACK",
                    x.left == nil ? -1 : ids.get(x.left), x.right == nil ? -1 : ids.get(x.right), x.size));
        }
        return out;
    }

    /** Checks every invariant; throws {@link IllegalStateException} naming the first violation. */
    public void validate() {
        if (root.color != BLACK) {
            throw new IllegalStateException("root must be black");
        }
        if (root != nil && root.parent != nil) {
            throw new IllegalStateException("root's parent must be nil");
        }
        check(root, null, null);
    }

    // ---------------------------------------------------------------- internals

    private void log(String event) {
        if (eventLog != null) {
            eventLog.add(event);
        }
    }

    private Node find(K key) {
        Node x = root;
        while (x != nil) {
            int cmp = key.compareTo(x.key);
            if (cmp == 0) {
                return x;
            }
            x = cmp < 0 ? x.left : x.right;
        }
        return nil;
    }

    private Node minimum(Node x) {
        while (x.left != nil) {
            x = x.left;
        }
        return x;
    }

    private void updateSize(Node n) {
        n.size = 1 + n.left.size + n.right.size;
    }

    private void leftRotate(Node x) {
        rotations++;
        log("  rotate left at " + x.key);
        Node y = x.right;
        x.right = y.left;
        if (y.left != nil) {
            y.left.parent = x;
        }
        y.parent = x.parent;
        if (x.parent == nil) {
            root = y;
        } else if (x == x.parent.left) {
            x.parent.left = y;
        } else {
            x.parent.right = y;
        }
        y.left = x;
        x.parent = y;
        updateSize(x);
        updateSize(y);
    }

    private void rightRotate(Node y) {
        rotations++;
        log("  rotate right at " + y.key);
        Node x = y.left;
        y.left = x.right;
        if (x.right != nil) {
            x.right.parent = y;
        }
        x.parent = y.parent;
        if (y.parent == nil) {
            root = x;
        } else if (y == y.parent.right) {
            y.parent.right = x;
        } else {
            y.parent.left = x;
        }
        x.right = y;
        y.parent = x;
        updateSize(y);
        updateSize(x);
    }

    private void insertFixup(Node z) {
        while (z.parent.color == RED) {
            boolean parentIsLeft = z.parent == z.parent.parent.left;
            Node uncle = parentIsLeft ? z.parent.parent.right : z.parent.parent.left;
            if (uncle.color == RED) {
                log("  case 1 (red uncle): recolour parent, uncle and grandparent " + z.parent.parent.key);
                z.parent.color = BLACK;
                uncle.color = BLACK;
                z.parent.parent.color = RED;
                z = z.parent.parent;
                continue;
            }
            if (parentIsLeft) {
                if (z == z.parent.right) {
                    log("  case 2 (black uncle, inner child): rotate parent to make it an outer child");
                    z = z.parent;
                    leftRotate(z);
                }
                log("  case 3 (black uncle, outer child): recolour and rotate grandparent");
                z.parent.color = BLACK;
                z.parent.parent.color = RED;
                rightRotate(z.parent.parent);
            } else {
                if (z == z.parent.left) {
                    log("  case 2 (black uncle, inner child): rotate parent to make it an outer child");
                    z = z.parent;
                    rightRotate(z);
                }
                log("  case 3 (black uncle, outer child): recolour and rotate grandparent");
                z.parent.color = BLACK;
                z.parent.parent.color = RED;
                leftRotate(z.parent.parent);
            }
        }
        root.color = BLACK;
    }

    private void transplant(Node u, Node v) {
        if (u.parent == nil) {
            root = v;
        } else if (u == u.parent.left) {
            u.parent.left = v;
        } else {
            u.parent.right = v;
        }
        v.parent = u.parent;
    }

    private void deleteFixup(Node x) {
        while (x != root && x.color == BLACK) {
            if (x == x.parent.left) {
                Node w = x.parent.right;
                if (w.color == RED) {
                    log("  delete case 1 (red sibling): rotate so the sibling becomes black");
                    w.color = BLACK;
                    x.parent.color = RED;
                    leftRotate(x.parent);
                    w = x.parent.right;
                }
                if (w.left.color == BLACK && w.right.color == BLACK) {
                    log("  delete case 2 (black sibling, black nephews): recolour sibling, move up");
                    w.color = RED;
                    x = x.parent;
                } else {
                    if (w.right.color == BLACK) {
                        log("  delete case 3 (far nephew black): rotate sibling");
                        w.left.color = BLACK;
                        w.color = RED;
                        rightRotate(w);
                        w = x.parent.right;
                    }
                    log("  delete case 4 (far nephew red): rotate parent, done");
                    w.color = x.parent.color;
                    x.parent.color = BLACK;
                    w.right.color = BLACK;
                    leftRotate(x.parent);
                    x = root;
                }
            } else {
                Node w = x.parent.left;
                if (w.color == RED) {
                    log("  delete case 1 (red sibling): rotate so the sibling becomes black");
                    w.color = BLACK;
                    x.parent.color = RED;
                    rightRotate(x.parent);
                    w = x.parent.left;
                }
                if (w.right.color == BLACK && w.left.color == BLACK) {
                    log("  delete case 2 (black sibling, black nephews): recolour sibling, move up");
                    w.color = RED;
                    x = x.parent;
                } else {
                    if (w.left.color == BLACK) {
                        log("  delete case 3 (far nephew black): rotate sibling");
                        w.right.color = BLACK;
                        w.color = RED;
                        leftRotate(w);
                        w = x.parent.left;
                    }
                    log("  delete case 4 (far nephew red): rotate parent, done");
                    w.color = x.parent.color;
                    x.parent.color = BLACK;
                    w.left.color = BLACK;
                    rightRotate(x.parent);
                    x = root;
                }
            }
        }
        x.color = BLACK;
    }

    private int heightOf(Node x) {
        if (x == nil) {
            return 0;
        }
        return 1 + Math.max(heightOf(x.left), heightOf(x.right));
    }

    private int check(Node x, K lo, K hi) {
        if (x == nil) {
            return 1;
        }
        if ((lo != null && x.key.compareTo(lo) <= 0) || (hi != null && x.key.compareTo(hi) >= 0)) {
            throw new IllegalStateException("BST order violated at key " + x.key);
        }
        if (x.color == RED && (x.left.color == RED || x.right.color == RED)) {
            throw new IllegalStateException("red node " + x.key + " has a red child");
        }
        if ((x.left != nil && x.left.parent != x) || (x.right != nil && x.right.parent != x)) {
            throw new IllegalStateException("broken parent pointer at key " + x.key);
        }
        int lh = check(x.left, lo, x.key);
        int rh = check(x.right, x.key, hi);
        if (lh != rh) {
            throw new IllegalStateException("black heights differ below key " + x.key);
        }
        if (x.size != 1 + x.left.size + x.right.size) {
            throw new IllegalStateException("stale subtree size at key " + x.key);
        }
        return lh + (x.color == BLACK ? 1 : 0);
    }
}
