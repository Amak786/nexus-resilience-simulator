package edu.nexus.algorithms.flow;

import java.util.Arrays;

/**
 * Ford-Fulkerson method with depth-first search for augmenting paths.
 *
 * <pre>
 * FORD-FULKERSON(G, s, t)
 *   reset residual capacities
 *   while a DFS from s in the residual graph reaches t:
 *       b = min residual capacity on the path
 *       for each arc a on the path: residual[a] -= b; residual[twin(a)] += b
 *       flow += b
 *   return flow
 * </pre>
 *
 * <p><b>Termination.</b> Capacities are integers, so every augmentation raises the flow by at least 1.
 * The flow is bounded by the total capacity leaving s, so the loop stops after at most |f*|
 * augmentations. (With irrational capacities the method can fail to terminate; we only accept
 * integers.)
 *
 * <p><b>Complexity.</b> Each DFS is O(V + E), so time is O(E * |f*|): pseudo-polynomial, because it
 * depends on the numeric flow value. Space O(V + E).
 *
 * <p>The DFS here is a true depth-first search (explicit stack plus a per-node arc iterator), so the
 * path found is whatever DFS order discovers first, not necessarily the shortest.
 */
public final class FordFulkerson {

    private FordFulkerson() {}

    public static MaxFlowResult run(FlowNetwork g, int s, int t, TraceOptions options) {
        g.checkSourceSink(s, t);
        g.resetResidual();
        long start = System.nanoTime();
        int n = g.nodeCount();
        int[] parentArc = new int[n];
        int[] iterator = new int[n];
        int[] stack = new int[n];
        boolean[] visited = new boolean[n];
        AugmentingPathRecorder recorder = new AugmentingPathRecorder(g, options);
        long flow = 0;
        long scans = 0;
        int augmentations = 0;
        while (true) {
            Arrays.fill(visited, false);
            int sp = 0;
            stack[sp++] = s;
            visited[s] = true;
            iterator[s] = g.firstArc(s);
            boolean found = false;
            while (sp > 0) {
                int u = stack[sp - 1];
                if (u == t) {
                    found = true;
                    break;
                }
                int a = iterator[u];
                if (a == -1) {
                    sp--; // dead end: backtrack
                    continue;
                }
                iterator[u] = g.nextArc(a);
                scans++;
                int v = g.arcTo(a);
                if (g.residual(a) > 0 && !visited[v]) {
                    visited[v] = true;
                    parentArc[v] = a;
                    iterator[v] = g.firstArc(v);
                    stack[sp++] = v;
                }
            }
            if (!found) {
                break;
            }
            augmentations++;
            flow += recorder.augment(parentArc, s, t, flow, augmentations);
        }
        double ms = (System.nanoTime() - start) / 1e6;
        return new MaxFlowResult("Ford-Fulkerson (DFS)", flow, augmentations, scans, recorder.steps(),
                recorder.truncated(), ms);
    }
}
