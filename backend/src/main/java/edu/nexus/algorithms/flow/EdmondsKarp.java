package edu.nexus.algorithms.flow;

import java.util.Arrays;

/**
 * Edmonds-Karp: the Ford-Fulkerson method where every augmenting path is a shortest path (fewest
 * arcs), found by breadth-first search.
 *
 * <pre>
 * EDMONDS-KARP(G, s, t)
 *   reset residual capacities
 *   loop:
 *       BFS from s over arcs with residual > 0, recording parent arcs
 *       if t was not reached: break
 *       b = min residual on the s-t path; push b along it (updating twins)
 *       flow += b
 *   return flow
 * </pre>
 *
 * <p><b>Why BFS matters.</b> With shortest paths, the BFS distance of every node from s never
 * decreases, and each arc can be the bottleneck ("critical") at most O(V) times. That bounds the
 * number of augmentations by O(V * E), independent of the capacity values.
 *
 * <p><b>Complexity.</b> O(V * E^2) time (O(V * E) augmentations times O(E) per BFS), O(V + E) space.
 */
public final class EdmondsKarp {

    private EdmondsKarp() {}

    public static MaxFlowResult run(FlowNetwork g, int s, int t, TraceOptions options) {
        g.checkSourceSink(s, t);
        g.resetResidual();
        long start = System.nanoTime();
        int n = g.nodeCount();
        int[] parentArc = new int[n];
        int[] queue = new int[n];
        boolean[] seen = new boolean[n];
        AugmentingPathRecorder recorder = new AugmentingPathRecorder(g, options);
        long flow = 0;
        long scans = 0;
        int augmentations = 0;
        while (true) {
            Arrays.fill(seen, false);
            int qh = 0;
            int qt = 0;
            queue[qt++] = s;
            seen[s] = true;
            boolean found = false;
            while (qh < qt && !found) {
                int u = queue[qh++];
                for (int a = g.firstArc(u); a != -1; a = g.nextArc(a)) {
                    scans++;
                    int v = g.arcTo(a);
                    if (g.residual(a) > 0 && !seen[v]) {
                        seen[v] = true;
                        parentArc[v] = a;
                        if (v == t) {
                            found = true;
                            break;
                        }
                        queue[qt++] = v;
                    }
                }
            }
            if (!found) {
                break;
            }
            augmentations++;
            flow += recorder.augment(parentArc, s, t, flow, augmentations);
        }
        double ms = (System.nanoTime() - start) / 1e6;
        return new MaxFlowResult("Edmonds-Karp (BFS)", flow, augmentations, scans, recorder.steps(),
                recorder.truncated(), ms);
    }
}
