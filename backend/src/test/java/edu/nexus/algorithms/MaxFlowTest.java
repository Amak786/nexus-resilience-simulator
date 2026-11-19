package edu.nexus.algorithms;

import edu.nexus.algorithms.flow.EdmondsKarp;
import edu.nexus.algorithms.flow.FlowNetwork;
import edu.nexus.algorithms.flow.FordFulkerson;
import edu.nexus.algorithms.flow.MaxFlowResult;
import edu.nexus.algorithms.flow.MinCut;
import edu.nexus.algorithms.flow.TraceOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ford-Fulkerson, Edmonds-Karp and the min-cut certificate (one family, three components). */
class MaxFlowTest {

    private static final TraceOptions TRACE = new TraceOptions(100, true);

    /** CLRS Figure 26.1: maximum flow 23. */
    private static FlowNetwork clrs() {
        FlowNetwork g = new FlowNetwork(6);
        g.addEdge(0, 1, 16);
        g.addEdge(0, 2, 13);
        g.addEdge(2, 1, 4);
        g.addEdge(1, 3, 12);
        g.addEdge(3, 2, 9);
        g.addEdge(2, 4, 14);
        g.addEdge(4, 3, 7);
        g.addEdge(3, 5, 20);
        g.addEdge(4, 5, 4);
        return g;
    }

    @Test
    @DisplayName("FF-1 / EK-1: textbook network gives 23 with both algorithms; flows are valid")
    void textbookNetwork() {
        FlowNetwork g = clrs();
        assertEquals(23, FordFulkerson.run(g, 0, 5, TraceOptions.NONE).maxFlow());
        assertEquals(23, MinCut.verifyFlow(g, 0, 5));
        assertEquals(23, EdmondsKarp.run(g, 0, 5, TraceOptions.NONE).maxFlow());
        assertEquals(23, MinCut.verifyFlow(g, 0, 5));
    }

    @Test
    @DisplayName("CUT-1: residual-reachability cut is saturated and its capacity equals the max flow")
    void minCutEqualsMaxFlow() {
        FlowNetwork g = clrs();
        long flow = EdmondsKarp.run(g, 0, 5, TraceOptions.NONE).maxFlow();
        MinCut.Result cut = MinCut.fromResidual(g, 0);
        assertEquals(flow, cut.cutCapacity());
        for (int e : cut.cutEdges()) {
            assertEquals(g.edgeCapacity(e), g.edgeFlow(e), "cut edge " + e + " must be saturated");
        }
        assertTrue(cut.sourceSide()[0]);
        assertTrue(!cut.sourceSide()[5]);
    }

    @Test
    @DisplayName("FF-2 (residual reverse arc): DFS's first path is undone through a reverse arc")
    void reverseArcCancelsFlow() {
        // Edge insertion order makes the DFS try s->a->b->t first; the optimum needs a->b cancelled.
        FlowNetwork g = new FlowNetwork(4); // s=0, a=1, b=2, t=3
        g.addEdge(0, 2, 1); // s->b
        g.addEdge(0, 1, 1); // s->a (explored first)
        g.addEdge(1, 3, 1); // a->t
        g.addEdge(1, 2, 1); // a->b (explored first from a)
        g.addEdge(2, 3, 1); // b->t
        MaxFlowResult r = FordFulkerson.run(g, 0, 3, TRACE);
        assertEquals(2, r.maxFlow());
        assertTrue(r.steps().stream().anyMatch(s -> s.arcs().stream().anyMatch(MaxFlowResult.PathArc::reverse)),
                "one augmenting path must travel a residual reverse arc");
        assertEquals(0, g.edgeFlow(3), "the a->b edge ends with zero flow after cancellation");
    }

    @Test
    @DisplayName("EK-2 (boundary): disconnected sink and zero-capacity edges give flow 0, not an error")
    void disconnectedAndZeroCapacity() {
        FlowNetwork g = new FlowNetwork(4);
        g.addEdge(0, 1, 10);
        g.addEdge(2, 3, 10);
        MaxFlowResult r = EdmondsKarp.run(g, 0, 3, TRACE);
        assertEquals(0, r.maxFlow());
        assertEquals(0, r.augmentations());
        FlowNetwork z = new FlowNetwork(2);
        z.addEdge(0, 1, 0);
        assertEquals(0, FordFulkerson.run(z, 0, 1, TraceOptions.NONE).maxFlow());
        assertEquals(0, MinCut.fromResidual(z, 0).cutCapacity(), "zero-capacity edges are not reported in the cut");
    }

    @Test
    @DisplayName("EK-3: parallel and antiparallel edges are handled by separate arc pairs")
    void parallelAndAntiparallel() {
        FlowNetwork g = new FlowNetwork(3);
        g.addEdge(0, 1, 4);
        g.addEdge(0, 1, 6);
        g.addEdge(1, 2, 7);
        g.addEdge(2, 1, 3);
        assertEquals(7, EdmondsKarp.run(g, 0, 2, TraceOptions.NONE).maxFlow());
        assertEquals(7, FordFulkerson.run(g, 0, 2, TraceOptions.NONE).maxFlow());
    }

    @Test
    @DisplayName("EK-4: BFS needs 2 augmentations where a bad DFS order could need 2,000,000")
    void edmondsKarpAvoidsPathologicalCase() {
        FlowNetwork g = new FlowNetwork(4);
        g.addEdge(0, 1, 1_000_000);
        g.addEdge(0, 2, 1_000_000);
        g.addEdge(1, 2, 1);
        g.addEdge(1, 3, 1_000_000);
        g.addEdge(2, 3, 1_000_000);
        MaxFlowResult r = EdmondsKarp.run(g, 0, 3, TraceOptions.NONE);
        assertEquals(2_000_000, r.maxFlow());
        assertEquals(2, r.augmentations());
    }

    @Test
    @DisplayName("FF-3 / EK-5 / CUT-2 (property, seed 2026): 300 random graphs - same value, valid flows, cut = flow")
    void randomGraphsAgree() {
        SplittableRandom rnd = new SplittableRandom(2026);
        for (int trial = 0; trial < 300; trial++) {
            int n = 2 + rnd.nextInt(30);
            FlowNetwork g = new FlowNetwork(n);
            int m = rnd.nextInt(4 * n + 1);
            for (int i = 0; i < m; i++) {
                g.addEdge(rnd.nextInt(n), rnd.nextInt(n), rnd.nextInt(50));
            }
            long ff = FordFulkerson.run(g, 0, n - 1, TraceOptions.NONE).maxFlow();
            MinCut.verifyFlow(g, 0, n - 1);
            long ek = EdmondsKarp.run(g, 0, n - 1, TraceOptions.NONE).maxFlow();
            MinCut.verifyFlow(g, 0, n - 1);
            assertEquals(ff, ek, "trial " + trial);
            assertEquals(ek, MinCut.fromResidual(g, 0).cutCapacity(), "trial " + trial);
        }
    }

    @Test
    @DisplayName("FF-4: trace records each path with bottleneck and running total")
    void traceIsConsistent() {
        FlowNetwork g = clrs();
        MaxFlowResult r = EdmondsKarp.run(g, 0, 5, TRACE);
        long total = 0;
        for (MaxFlowResult.AugmentingStep s : r.steps()) {
            total += s.bottleneck();
            assertEquals(total, s.flowAfter());
            assertEquals(0, s.nodes().get(0));
            assertEquals(5, s.nodes().get(s.nodes().size() - 1));
            assertEquals(s.nodes().size() - 1, s.arcs().size());
        }
        assertEquals(r.maxFlow(), total);
        assertEquals(r.augmentations(), r.steps().size());
    }

    @Test
    @DisplayName("FF-5 (invalid input): one-node graph, s == t, unknown node and negative capacity are rejected")
    void invalidInput() {
        FlowNetwork one = new FlowNetwork(1);
        assertThrows(IllegalArgumentException.class, () -> EdmondsKarp.run(one, 0, 0, TraceOptions.NONE));
        assertThrows(IllegalArgumentException.class, () -> new FlowNetwork(0));
        FlowNetwork g = new FlowNetwork(3);
        assertThrows(IllegalArgumentException.class, () -> g.addEdge(0, 7, 1));
        assertThrows(IllegalArgumentException.class, () -> g.addEdge(0, 1, -5));
        assertThrows(IllegalArgumentException.class, () -> FordFulkerson.run(g, 0, 9, TraceOptions.NONE));
    }
}
