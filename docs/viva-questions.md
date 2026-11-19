# Viva question bank (with simple, truthful answers)

## Network flow

**Q. Why is max flow the right model for relief delivery?**
Depots have limited stock and roads have limited throughput; we want the largest total that can reach demand zones. Add a
super source feeding depots (capacity = stock) and a super sink fed by zones (capacity = demand), and the maximum s-t flow is
exactly the most kits we can deliver in one window.

**Q. Ford-Fulkerson vs Edmonds-Karp?**
Same method: augment along paths in the residual graph. Ford-Fulkerson (ours uses DFS) picks any path, so its number of
augmentations can depend on capacity values: O(E·|f*|). Edmonds-Karp always picks a shortest path (BFS), which bounds the
augmentations by O(VE), giving O(VE²) regardless of capacities. They are one technique family, not two algorithms.

**Q. In your benchmark FF was faster. Isn't EK supposed to be better?**
EK has a better *worst-case* guarantee. On random graphs DFS often finds long paths that push a lot of flow, so it can be
faster in practice. Our test with a capacity-1 middle edge shows the worst case: BFS needs 2 augmentations while an unlucky
DFS order could need 2,000,000.

**Q. What are reverse (residual) edges for?**
They let a later path undo flow sent earlier. Our test `reverseArcCancelsFlow` builds a graph where DFS first sends flow
through a→b and the optimum must cancel it; the trace marks the path that used the reverse arc.

**Q. How do you know the flow is maximum?**
After the algorithm stops, nodes reachable from s in the residual graph form a cut whose capacity equals the flow (max-flow
min-cut theorem). The code checks this equality and conservation on every request, and the tests assert it.

**Q. What does a min-cut edge mean in the city?**
It is a saturated link whose capacity limits total delivery: a road (raise its capacity), a depot's stock (more stock), or a
zone's demand (that zone is fully served, so it is not a bottleneck).

**Q. Does max flow solve multi-depot inventory allocation?**
No. It is single-commodity and single-period, with no travel times, schedules or fairness. It answers "how much can move
right now and where is the bottleneck", which supports planning but is not the whole problem.

## DSU

**Q. Why DSU for connectivity, and why a separate graph?**
Connectivity ("is there any physical link?") ignores direction and capacity, so we use an undirected graph. DSU answers
"same component?" in near-constant amortized time.

**Q. Union by rank and path compression?**
Rank keeps trees shallow by attaching the shorter under the taller; path compression points visited nodes straight at the
root. Together: O(α(n)) amortized, effectively constant.

**Q. How do you handle a road failure if DSU can't delete edges?**
We rebuild the DSU from the remaining roads. It is O((V+E)α(V)) per rebuild, cheap at city scale.

## Red-black tree

**Q. Why a red-black tree for incidents?**
Priorities change all the time; we need insert, delete and ordered traversal in O(log n) worst case. A heap gives fast max
but not ordered listing or rank; a sorted array has O(n) inserts.

**Q. What are the invariants?** Root black; no red node with a red child; every root-to-leaf path has the same number of
black nodes; BST order. They imply height ≤ 2 log₂(n+1).

**Q. How is an update done?** The key (score, id) changes, so delete the old key and insert the new one: two O(log n) operations.

**Q. Why the id tie-breaker?** Keys must be unique in the tree; ids are unique, so (score, id) never ties.

**Q. Is the score a real risk model?** No. It is a transparent rule (200·severity + min(300, people/50) + zone
vulnerability %) for the demo, not a validated prediction.

## Bloom filter (CO5)

**Q. Can it have false negatives?** Not in our insertion-only setting: added bits are never cleared and the seed is fixed.
Deletion, a seed change or corruption could cause them, and we support none of those.

**Q. False positives?** Yes: all k bits may have been set by other ids. Probability ≈ (1−e^(−kn/m))^k. Every possible hit
is checked against the exact registry, so final verdicts are exact.

**Q. How are m and k chosen?** m = −n ln p / (ln 2)², k = (m/n) ln 2: about 9.6 bits and 7 hashes per id at p = 1%.

**Q. How do you measure the real false-positive rate?** Generate ids we have verified are absent, query them, and count
positives. All of those are false positives.

## Segment tree

**Q. Why not just loop over the array?** A loop is O(range length) per query. The segment tree is O(log n) for range sum,
range max and range add (lazy propagation), which matters when queries and updates are interleaved.

**Q. What is lazy propagation?** A range update tags whole covered nodes instead of touching every element; tags are pushed
to children only when a later query needs to look inside.

**Q. How is the worst window found?** A range sum for every start hour: O(T log T). A static sliding window would be O(T),
but the tree also supports updates such as demand surges.

## Randomized Quickselect (CO5)

**Q. Why randomized?** A random pivot makes the expected time O(n) for every input order; no adversarial ordering forces the
O(n²) worst case.

**Q. Why three-way partitioning?** Impact data has many repeated values; grouping "= pivot" elements keeps that linear.

**Q. Is it reproducible?** Yes: the pivot generator is seeded. The answer never depends on the seed, only the work does.

**Q. Percentile definition?** Nearest rank: the value at position ceil(p/100 · n) in sorted order. Each result is checked
against a full sort.

## Metric TSP

**Q. Why is the tour at most 2·OPT?** The MST weighs at most OPT (an optimal tour minus one edge is a spanning tree).
Walking the tree costs 2·MST; skipping repeated vertices never adds length because of the triangle inequality.

**Q. Why validate the matrix?** Without symmetry and the triangle inequality the guarantee is false, so non-metric input is
rejected.

**Q. Where do distances come from?** Shortest paths over road length in km, never road capacity.

**Q. Is the tour optimal?** No, and we never say it is. We show the MST lower bound and, for ≤ 10 stops, the exact optimum.

## Engineering

**Q. Why is the API stateless?** Every request carries the dataset, so many users can share one deployment without
interfering, and there is no database to lose on free hosting.

**Q. How is bad input handled?** Strict validation with field-level messages; unknown JSON fields and wrong types are
rejected; bodies over 2 MiB get HTTP 413; internal errors never reveal stack traces.

**Q. What did *you* implement?** _Each student answers from their own contribution (see the report, section 10)._
