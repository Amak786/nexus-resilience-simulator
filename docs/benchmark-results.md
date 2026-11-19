# Benchmark results

Generated from actual runs of `POST /api/benchmarks/{suite}` on the development laptop on 2026-11-20 (raw JSON in `docs/evidence/`). Re-run them live on the Benchmarks & Tests page; numbers on the free hosting tier will be slower.

**Environment:** Java 25.0.2 (Oracle Corporation), 14 logical CPUs, max heap 3952 MiB, Windows 11

## Max flow: Edmonds-Karp vs Ford-Fulkerson (DFS)

Ops column: residual arcs scanned. 1 warm-up run(s), median and minimum of 3 timed runs.

| Input | Algorithm | Median ms | Min ms | Ops | Baseline | Baseline median ms | Baseline ops | Quality / check |
|---|---|---:|---:|---:|---|---:|---:|---|
| V=100, E=497 | Edmonds-Karp | 1.492 | 1.481 | 70,044 | Ford-Fulkerson (DFS) | 4.203 | 26,459 | max flow 815 (both agree) |
| V=400, E=2057 | Edmonds-Karp | 15.817 | 8.484 | 1,258,678 | Ford-Fulkerson (DFS) | 11.322 | 421,608 | max flow 3802 (both agree) |
| V=1600, E=8297 | Edmonds-Karp | 178.361 | 175.872 | 20,152,517 | Ford-Fulkerson (DFS) | 110.964 | 5,577,696 | max flow 15611 (both agree) |

- Input: Random layered networks (5 layers, ~6 arcs per node, capacities 1..100). Generated from fixed seed 2026 before timing.
- Timing: System.nanoTime() around the algorithm call only; 1 warm-up run(s), then the median and minimum of 3 runs.
- Operation counts are machine-independent; milliseconds depend on hardware, JIT and load (a shared free-tier server can be noisy).
- Network latency, JSON serialisation and input generation are excluded.

## Disjoint-set union vs naive linking

Ops column: parent-pointer hops. 2 warm-up run(s), median and minimum of 5 timed runs.

| Input | Algorithm | Median ms | Min ms | Ops | Baseline | Baseline median ms | Baseline ops | Quality / check |
|---|---|---:|---:|---:|---|---:|---:|---|
| n=10000, 20000 ops | DSU (rank + compression) | 1.443 | 1.232 | 32,742 | Naive linking (no rank, no compression), 1 run | 53.346 | 12,761,123 | naive does 389.7x more pointer hops |
| n=100000, 200000 ops | DSU (rank + compression) | 8.523 | 7.303 | 327,447 | Naive linking (no rank, no compression), 1 run | - | - | naive baseline stopped after 200000000 pointer hops (1991.976 ms): far slower |
| n=500000, 1000000 ops | DSU (rank + compression) | 52.91 | 52.159 | 1,636,360 | Naive linking (no rank, no compression), 1 run | - | - | naive baseline stopped after 200000000 pointer hops (2627.68 ms): far slower |

- Input: n random unions followed by n random connectivity queries. The naive baseline runs once with a budget of 2e8 pointer hops. Generated from fixed seed 2026 before timing.
- Timing: System.nanoTime() around the algorithm call only; 2 warm-up run(s), then the median and minimum of 5 runs.
- Operation counts are machine-independent; milliseconds depend on hardware, JIT and load (a shared free-tier server can be noisy).
- Network latency, JSON serialisation and input generation are excluded.

## Red-black tree vs sorted array list (n inserts + n lookups)

Ops column: primary: rotations; baseline: elements shifted. 2 warm-up run(s), median and minimum of 5 timed runs.

| Input | Algorithm | Median ms | Min ms | Ops | Baseline | Baseline median ms | Baseline ops | Quality / check |
|---|---|---:|---:|---:|---|---:|---:|---|
| n=1000 | Red-black tree | 1.802 | 1.157 | 559 | Sorted ArrayList (binary search + shift) | 1.292 | 254,609 | height 12 (bound 19.9) |
| n=10000 | Red-black tree | 9.672 | 8.767 | 5,844 | Sorted ArrayList (binary search + shift) | 10.985 | 25,261,195 | height 16 (bound 26.6) |
| n=50000 | Red-black tree | 94.368 | 69.157 | 29,006 | Sorted ArrayList (binary search + shift) | 131.687 | 621,273,908 | height 19 (bound 31.2) |

- Input: Distinct random integer keys. Generated from fixed seed 2026 before timing.
- Timing: System.nanoTime() around the algorithm call only; 2 warm-up run(s), then the median and minimum of 5 runs.
- Operation counts are machine-independent; milliseconds depend on hardware, JIT and load (a shared free-tier server can be noisy).
- Network latency, JSON serialisation and input generation are excluded.

## Bloom filter vs exact HashSet (n inserts + n absent lookups)

Ops column: bits in the filter. 2 warm-up run(s), median and minimum of 5 timed runs.

| Input | Algorithm | Median ms | Min ms | Ops | Baseline | Baseline median ms | Baseline ops | Quality / check |
|---|---|---:|---:|---:|---|---:|---:|---|
| n=10000 | Bloom filter (p=1%) | 3.062 | 2.585 | 95,851 | HashSet<String> (exact) | 1.609 | - | measured FPR 0.0107 vs theory 0.0100; 9.6 bits/item |
| n=50000 | Bloom filter (p=1%) | 15.226 | 14.873 | 479,253 | HashSet<String> (exact) | 7.742 | - | measured FPR 0.0103 vs theory 0.0100; 9.6 bits/item |
| n=200000 | Bloom filter (p=1%) | 62.35 | 62.175 | 1,917,012 | HashSet<String> (exact) | 52.641 | - | measured FPR 0.0100 vs theory 0.0100; 9.6 bits/item |

- Input: Probe ids use a different prefix, so every positive is a false positive. Generated from fixed seed 2026 before timing.
- Timing: System.nanoTime() around the algorithm call only; 2 warm-up run(s), then the median and minimum of 5 runs.
- Operation counts are machine-independent; milliseconds depend on hardware, JIT and load (a shared free-tier server can be noisy).
- Network latency, JSON serialisation and input generation are excluded.

## Segment tree vs plain array (alternating range add / range sum)

Ops column: primary: tree nodes visited; baseline: array cells touched. 2 warm-up run(s), median and minimum of 5 timed runs.

| Input | Algorithm | Median ms | Min ms | Ops | Baseline | Baseline median ms | Baseline ops | Quality / check |
|---|---|---:|---:|---:|---|---:|---:|---|
| n=1000, 2000 ops | Segment tree (lazy) | 1.965 | 1.642 | 63,256 | Plain array | 3.042 | 516,811 | answers agree |
| n=10000, 2000 ops | Segment tree (lazy) | 2.772 | 2.462 | 90,770 | Plain array | 7.113 | 5,053,326 | answers agree |
| n=100000, 2000 ops | Segment tree (lazy) | 10.832 | 6.254 | 117,034 | Plain array | 69.097 | 50,865,716 | answers agree |

- Input: Random ranges. Generated from fixed seed 2026 before timing.
- Timing: System.nanoTime() around the algorithm call only; 2 warm-up run(s), then the median and minimum of 5 runs.
- Operation counts are machine-independent; milliseconds depend on hardware, JIT and load (a shared free-tier server can be noisy).
- Network latency, JSON serialisation and input generation are excluded.

## Randomized Quickselect vs full sort (90th percentile)

Ops column: primary: comparisons counted; baseline: n log2 n estimate (not counted). 2 warm-up run(s), median and minimum of 5 timed runs.

| Input | Algorithm | Median ms | Min ms | Ops | Baseline | Baseline median ms | Baseline ops | Quality / check |
|---|---|---:|---:|---:|---|---:|---:|---|
| n=10000 | Randomized Quickselect (P90) | 0.421 | 0.298 | 20,284 | Full sort (Arrays.sort) then index | 2.092 | 132,877 | same P90 value |
| n=100000 | Randomized Quickselect (P90) | 2.785 | 1.729 | 229,719 | Full sort (Arrays.sort) then index | 12.65 | 1,660,964 | same P90 value |
| n=1000000 | Randomized Quickselect (P90) | 31.513 | 30.229 | 3,760,857 | Full sort (Arrays.sort) then index | 145.131 | 19,931,569 | same P90 value |

- Input: Uniform random doubles. Generated from fixed seed 2026 before timing.
- Timing: System.nanoTime() around the algorithm call only; 2 warm-up run(s), then the median and minimum of 5 runs.
- Operation counts are machine-independent; milliseconds depend on hardware, JIT and load (a shared free-tier server can be noisy).
- Network latency, JSON serialisation and input generation are excluded.

## Metric TSP: MST double-tree vs nearest neighbour

Ops column: distance lookups (n^2). 2 warm-up run(s), median and minimum of 5 timed runs.

| Input | Algorithm | Median ms | Min ms | Ops | Baseline | Baseline median ms | Baseline ops | Quality / check |
|---|---|---:|---:|---:|---|---:|---:|---|
| n=50 | MST + preorder (2-approx) | 0.232 | 0.23 | 2,500 | Nearest neighbour | 0.123 | 2,500 | tour/MST: approx 1.623, NN 1.578 (MST <= OPT, so these bound the ratio to OPT) |
| n=200 | MST + preorder (2-approx) | 0.632 | 0.487 | 40,000 | Nearest neighbour | 0.363 | 40,000 | tour/MST: approx 1.604, NN 1.448 (MST <= OPT, so these bound the ratio to OPT) |
| n=800 | MST + preorder (2-approx) | 11.528 | 11.057 | 640,000 | Nearest neighbour | 2.851 | 640,000 | tour/MST: approx 1.559, NN 1.385 (MST <= OPT, so these bound the ratio to OPT) |

- Input: Uniform random points in a 100 x 100 km square (Euclidean, so metric by construction). Generated from fixed seed 2026 before timing.
- Timing: System.nanoTime() around the algorithm call only; 2 warm-up run(s), then the median and minimum of 5 runs.
- Operation counts are machine-independent; milliseconds depend on hardware, JIT and load (a shared free-tier server can be noisy).
- Network latency, JSON serialisation and input generation are excluded.

## Live self-check (seed 20261119)

| Algorithm | Property | Cases | Result | ms |
|---|---|---:|---|---:|
| Edmonds-Karp / Ford-Fulkerson / min cut | both reach the same value, the flow is valid, and the residual cut capacity equals it | 150 | pass | 6.42 |
| Red-black tree | matches java.util.TreeMap after random inserts/deletes; all invariants hold | 3000 | pass | 14.33 |
| Disjoint-set union | connectivity matches a naive relabelling oracle | 500 | pass | 2.3 |
| Bloom filter | no false negatives; measured FPR within 0.5x-2x of theory | 5000 | pass | 17.28 |
| Segment tree | range sum/max/add match a plain array | 2000 | pass | 5.19 |
| Randomized Quickselect | every percentile equals the value from a full sort | 200 | pass | 16.27 |
| Metric TSP | MST <= OPT <= approx tour <= 2 x OPT (Held-Karp oracle) | 60 | pass | 6.76 |
