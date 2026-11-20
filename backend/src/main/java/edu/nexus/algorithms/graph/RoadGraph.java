package edu.nexus.algorithms.graph;

import java.util.Arrays;

/**
 * Directed road graph in compressed sparse row (CSR) form.
 *
 * <p><b>Model.</b> Each undirected road <i>segment</i> {@code e} (between two intersections) has static
 * attributes indexed by {@code e}: length in metres, a derived travel-time estimate in seconds and a
 * modelled capacity proxy in vehicles/hour. A two-way segment produces two <i>arcs</i> (u→v and v→u);
 * a one-way segment produces one. Arcs reference their segment, so attributes are stored once.
 *
 * <p>Space O(V + E). Arcs leaving node {@code u} are {@code arcTo[offset[u] .. offset[u+1])}.
 */
public final class RoadGraph {

    private final int nodeCount;
    private final int[] segU;
    private final int[] segV;
    private final boolean[] segOneWay;
    private final double[] lengthM;
    private final double[] travelTimeS;
    private final double[] capacityVph;
    private final int[] offset;
    private final int[] arcTo;
    private final int[] arcSeg;

    /**
     * @param u, v       endpoints of each segment (direction u→v if one-way)
     * @param oneWay     true if only u→v is allowed
     */
    public RoadGraph(int nodeCount, int[] u, int[] v, boolean[] oneWay, double[] lengthM, double[] travelTimeS,
                     double[] capacityVph) {
        int m = u.length;
        if (v.length != m || oneWay.length != m || lengthM.length != m || travelTimeS.length != m || capacityVph.length != m) {
            throw new IllegalArgumentException("segment arrays must have equal length");
        }
        this.nodeCount = nodeCount;
        this.segU = u.clone();
        this.segV = v.clone();
        this.segOneWay = oneWay.clone();
        this.lengthM = lengthM.clone();
        this.travelTimeS = travelTimeS.clone();
        this.capacityVph = capacityVph.clone();
        int[] outDeg = new int[nodeCount + 1];
        for (int e = 0; e < m; e++) {
            check(u[e]);
            check(v[e]);
            if (!(lengthM[e] >= 0) || !(travelTimeS[e] >= 0) || !(capacityVph[e] >= 0)) {
                throw new IllegalArgumentException("segment " + e + " has a negative or NaN attribute");
            }
            outDeg[u[e]]++;
            if (!oneWay[e]) {
                outDeg[v[e]]++;
            }
        }
        offset = new int[nodeCount + 1];
        for (int i = 0; i < nodeCount; i++) {
            offset[i + 1] = offset[i] + outDeg[i];
        }
        int arcs = offset[nodeCount];
        arcTo = new int[arcs];
        arcSeg = new int[arcs];
        int[] fill = Arrays.copyOf(offset, nodeCount);
        for (int e = 0; e < m; e++) {
            arcTo[fill[u[e]]] = v[e];
            arcSeg[fill[u[e]]++] = e;
            if (!oneWay[e]) {
                arcTo[fill[v[e]]] = u[e];
                arcSeg[fill[v[e]]++] = e;
            }
        }
    }

    public int nodeCount() {
        return nodeCount;
    }

    public int segmentCount() {
        return segU.length;
    }

    public int arcCount() {
        return arcTo.length;
    }

    public int firstArc(int u) {
        return offset[u];
    }

    public int endArc(int u) {
        return offset[u + 1];
    }

    public int arcTo(int a) {
        return arcTo[a];
    }

    public int arcSegment(int a) {
        return arcSeg[a];
    }

    public int segU(int e) {
        return segU[e];
    }

    public int segV(int e) {
        return segV[e];
    }

    public boolean oneWay(int e) {
        return segOneWay[e];
    }

    public double lengthM(int e) {
        return lengthM[e];
    }

    public double travelTimeS(int e) {
        return travelTimeS[e];
    }

    public double capacityVph(int e) {
        return capacityVph[e];
    }

    /** Weight of a segment for the chosen metric. */
    public double weight(int e, Metric metric) {
        return metric == Metric.TIME ? travelTimeS[e] : lengthM[e];
    }

    /** Shortest-path metric. */
    public enum Metric { DISTANCE, TIME }

    private void check(int x) {
        if (x < 0 || x >= nodeCount) {
            throw new IllegalArgumentException("node " + x + " is outside [0, " + nodeCount + ")");
        }
    }
}
