# 8-minute demo script

Before the demo: open the deployed URL a minute early (free hosting sleeps), and on **Network & Data** press
**Reset to synthetic demo** so everyone sees the same deterministic data.

| Time | Page | What to do | What to say |
|---|---|---|---|
| 0:00-0:45 | Overview | Point at the hero and the six metrics. | "NEXUS answers: when infrastructure fails, how much aid still moves, who is cut off, and what to fix first. Varunapuri is synthetic. The deliverable throughput, 2,200 kits per 12 hours, is computed by Edmonds-Karp and certified by an equal minimum cut." |
| 0:45-3:15 | Failure Lab | Click **Island causeway R22 washed out**, then **Run failure simulation**. | Walk the results top to bottom: throughput is unchanged (Ward 33 was already unserved because the roads into Z3 are saturated: a non-obvious insight); **DSU** shows Ward 33 is now physically isolated; **min cut** before vs after; **segment tree** timeline with the worst 6-hour window and peak hour; **Quickselect** P50/P90/P95 over 100 seeded random failures, each matching a full sort; **recovery actions** ranked by recovered throughput. Then try **Central depot D1 offline** to show a much bigger impact. |
| 3:15-4:30 | Flow Optimizer | Algorithm "compare", **Run max flow**, then press **Next ▶** a few times. | "This replays the actual backend trace: each augmenting path, its bottleneck and running total. Ford-Fulkerson reaches the same value with different paths. Max flow equals min cut: that's the certificate." |
| 4:30-5:30 | Incident Priority | Insert `INC-2001` severity 5, then update an existing incident. | "A red-black tree keyed by (score desc, id). Watch the fix-up log: recolouring and rotations. Height stays within 2 log₂(n+1), and the invariants are checked after every operation." |
| 5:30-6:15 | Duplicate Screening | Set the target false-positive rate to `0.3`, then **Screen reports**. | "The Bloom filter says *definitely new* or *possibly seen*. Every possible hit is verified exactly: here's a confirmed duplicate, and here's a false positive resolved as new. The measured false-positive rate matches theory." |
| 6:15-6:45 | Delivery Routes | **Plan tour**. | "MST plus preorder: at most 2× optimal on a metric. We show the MST lower bound and, for small cases, the exact optimum. Never claimed optimal." |
| 6:45-7:30 | Benchmarks & Tests | **Run self-check**, then one benchmark (e.g. DSU). | "About 10,000 seeded property checks on the live server, plus 62 JUnit tests in CI. Benchmarks use three sizes, warm-up, median of repeated runs, and a baseline." |
| 7:30-8:00 | Network & Data | Set a road capacity to `-5` and press **Validate & apply**. | "Invalid input is rejected with the exact field, never silently fixed. Thank you." |

**If the examiner changes the input:** every page recomputes from the current dataset; the Algorithm Lab accepts custom
graphs, operation lists and matrices. **Fallback if the hosted API is asleep or down:** run locally
(`mvn spring-boot:run` + `npm run dev`, see README).
