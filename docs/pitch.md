# NEXUS: one-page product pitch

*Written as if pitching NEXUS as a product. Everything below about what the software does is true of this prototype;
everything about markets and next steps is a plan, not a claim of traction.*

## The problem

When a flood or earthquake takes out a bridge, emergency teams have to answer three questions within hours:

1. **How much aid can we still move?** Not a guess: the real maximum given which roads and depots are still working.
2. **How bad is this failure?** Is it a nuisance or a catastrophe compared with other things that could break?
3. **What do we fix first?** Which repair or traffic intervention wins back the most delivery capacity?

Today these are usually answered with spreadsheets, maps on a wall and experience. The answers are slow, hard to check,
and impossible to re-run when the situation changes every hour.

## The product

NEXUS is a resilience engine for city infrastructure. Load a network of depots, junctions, demand zones and roads and it:

* **Measures.** Computes the exact maximum deliverable throughput and *proves* it with an equal minimum cut, so the
  bottleneck roads are not an opinion.
* **Stress-tests.** Re-solves the network instantly for any failure, flags districts that lose every physical link to a depot,
  finds the worst hours on a 72-hour demand curve, and ranks the failure against hundreds of reproducible random failures.
* **Acts.** Ranks recovery options (repair this road, reopen that depot, add a temporary lane on a bottleneck) by the
  throughput each one actually recovers.
* **Supports the people doing the work.** A live incident queue that re-prioritises as reports change, duplicate-report
  screening, and a delivery tour with a guaranteed quality bound.

## Why it is credible

* **Certified answers.** Every throughput figure comes with a max-flow/min-cut certificate checked at runtime.
* **Fast at city scale.** The algorithms are polynomial and benchmarked at increasing sizes. A full failure analysis of the
  demo city, including 100 random scenarios, runs in well under a second on a laptop.
* **Explainable.** Every result can be replayed step by step; nothing is a black box.
* **Tested.** 62 automated tests plus a live self-check of about 10,000 seeded cases on the deployed server.

## Who would use it

Disaster-management authorities and municipal control rooms; utilities (power, water, telecom) where the same
"network + failures + recovery" question applies; logistics operators planning resilient distribution; insurers and
infrastructure planners who need defensible stress tests.

## Roadmap from prototype to product

1. **Real data.** Import road networks (e.g. OpenStreetMap) and live closure feeds instead of synthetic data.
2. **Richer models.** Multi-commodity flow (water, food, medicine), travel time and multi-day horizons, fairness constraints.
3. **Scenario planning at scale.** Thousands of correlated failure scenarios (flood maps, seismic zones) on cloud workers.
4. **Operations.** User accounts, audit trails, and integration with incident-management systems.

## The ask (for this academic version)

Evaluate NEXUS as a demonstration that classic algorithms (network flow, union-find, balanced trees, probabilistic
filters, segment trees, randomized selection and approximation) combine into a coherent, verifiable decision-support product.
