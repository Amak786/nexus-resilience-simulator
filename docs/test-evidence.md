# Test evidence

Output of `mvn verify` in `backend/` on 2026-11-20 (JUnit 5, Surefire). **62 tests, 0 failures.** CI re-runs this on every push.

Test names include the IDs used in the report (e.g. FF-2, RB-4, BF-5). Property tests use fixed seeds so results are reproducible.

| Class | Test | Result | Seconds |
|---|---|---|---:|
| algorithms.BloomFilterTest | deterministicHashing | pass | 0.055 |
| algorithms.BloomFilterTest | noFalseNegatives | pass | 0.024 |
| algorithms.BloomFilterTest | sizingFormulas | pass | 0.003 |
| algorithms.BloomFilterTest | invalidInput | pass | 0.007 |
| algorithms.BloomFilterTest | falsePositiveRate | pass | 0.092 |
| algorithms.BloomFilterTest | emptyFilter | pass | 0.003 |
| algorithms.BloomFilterTest | falsePositiveNeedsVerification | pass | 0.002 |
| algorithms.DisjointSetUnionTest | tinySets | pass | 0.001 |
| algorithms.DisjointSetUnionTest | repeatedUnion | pass | 0.001 |
| algorithms.DisjointSetUnionTest | unionAndFind | pass | 0.001 |
| algorithms.DisjointSetUnionTest | pathCompression | pass | 0.002 |
| algorithms.DisjointSetUnionTest | matchesOracle | pass | 0.006 |
| algorithms.DisjointSetUnionTest | invalidInput | pass | 0.004 |
| algorithms.MaxFlowTest | textbookNetwork | pass | 0.008 |
| algorithms.MaxFlowTest | reverseArcCancelsFlow | pass | 0.004 |
| algorithms.MaxFlowTest | disconnectedAndZeroCapacity | pass | 0.002 |
| algorithms.MaxFlowTest | randomGraphsAgree | pass | 0.021 |
| algorithms.MaxFlowTest | traceIsConsistent | pass | 0.002 |
| algorithms.MaxFlowTest | edmondsKarpAvoidsPathologicalCase | pass | 0.001 |
| algorithms.MaxFlowTest | invalidInput | pass | 0.003 |
| algorithms.MaxFlowTest | parallelAndAntiparallel | pass | 0.002 |
| algorithms.MaxFlowTest | minCutEqualsMaxFlow | pass | 0.002 |
| algorithms.MetricTspTest | square | pass | 0.006 |
| algorithms.MetricTspTest | startAndDuplicates | pass | 0.002 |
| algorithms.MetricTspTest | approximationGuarantee | pass | 0.025 |
| algorithms.MetricTspTest | invalidInput | pass | 0.003 |
| algorithms.MetricTspTest | tinyInstances | pass | 0.002 |
| algorithms.MetricTspTest | rejectsNonMetric | pass | 0.008 |
| algorithms.QuickselectTest | boundaries | pass | 0.005 |
| algorithms.QuickselectTest | inputUntouched | pass | 0.002 |
| algorithms.QuickselectTest | matchesSortWithDuplicates | pass | 0.053 |
| algorithms.QuickselectTest | everyRank | pass | 0.003 |
| algorithms.QuickselectTest | invalidInput | pass | 0.004 |
| algorithms.QuickselectTest | seededDeterminism | pass | 0.022 |
| algorithms.RedBlackTreeTest | doubleRotation | pass | 0.007 |
| algorithms.RedBlackTreeTest | rankAndSelect | pass | 0.001 |
| algorithms.RedBlackTreeTest | deleteAll | pass | 0.024 |
| algorithms.RedBlackTreeTest | balancedOnSortedInsert | pass | 0.113 |
| algorithms.RedBlackTreeTest | randomOperationsMatchTreeMap | pass | 0.047 |
| algorithms.RedBlackTreeTest | singleRotation | pass | 0.001 |
| algorithms.RedBlackTreeTest | boundaryCases | pass | 0.002 |
| algorithms.RedBlackTreeTest | priorityOrdering | pass | 0.005 |
| algorithms.SegmentTreeTest | boundaries | pass | 0.003 |
| algorithms.SegmentTreeTest | logarithmicWork | pass | 0.009 |
| algorithms.SegmentTreeTest | handChecked | pass | 0.002 |
| algorithms.SegmentTreeTest | matchesNaiveArray | pass | 0.013 |
| algorithms.SegmentTreeTest | invalidInput | pass | 0.006 |
| algorithms.SegmentTreeTest | lazyRangeAdd | pass | 0.001 |
| web.ApiIntegrationTest | systemEndpoints | pass | 7.666 |
| web.ApiIntegrationTest | payloadTooLarge | pass | 0.013 |
| web.ApiIntegrationTest | errors | pass | 0.142 |
| web.ApiIntegrationTest | cors | pass | 0.025 |
| web.ApiIntegrationTest | computeEndpoints | pass | 0.24 |
| web.ServiceScenarioTest | timeline | pass | 0.002 |
| web.ServiceScenarioTest | selfCheck | pass | 0.041 |
| web.ServiceScenarioTest | routes | pass | 0.009 |
| web.ServiceScenarioTest | screening | pass | 0.035 |
| web.ServiceScenarioTest | invalidDatasets | pass | 0.005 |
| web.ServiceScenarioTest | failureLab | pass | 0.021 |
| web.ServiceScenarioTest | bridgeFailureIsolatesHamlet | pass | 0.008 |
| web.ServiceScenarioTest | incidentQueue | pass | 0.005 |
| web.ServiceScenarioTest | demoFlowCertified | pass | 0.002 |
