# Glossary

The definitions live in one place, [`CONTEXT.md`](../../CONTEXT.md), shared by humans and coding agents. Code, dashboard labels and docs all use those words, so a term in a class name means exactly what it means in the lecture.

The terms you meet first: **Viewer**, **Playback Start**, **SPS**, **Steady State**, **Service**, **Dependency** (Critical or Non-critical), **Fallback**, **Fault**, **Experiment**, **Blast Radius**.

| Term | Meaning |
|---|---|
| Viewer | a simulated person using the platform |
| Playback Start | a Viewer journey that completes: home page, title, licensed stream |
| SPS | Starts Per Second: Playback Starts per second |
| Steady State | the normal, measurable behaviour of the platform, expressed through SPS |
| Service | one independently running part of the platform with a single responsibility |
| Dependency | a directed call from one Service to another |
| Critical / Non-critical Dependency | one whose loss cannot / can be hidden by a Fallback |
| Fallback | the degraded but acceptable answer returned when a Non-critical Dependency fails |
| Resilience Policy | the protections on one Dependency: timeout, retry, circuit breaker, bulkhead, Fallback |
| Cascading Failure | failure of one Service spreading to those that depend on it |
| Fault | a deliberately introduced real-world failure |
| Ad-hoc Fault | a Fault started by hand, outside any Experiment |
| Experiment | a written, repeatable test: Hypothesis, Fault, Blast Radius, SPS, Verdict |
| Hypothesis | the claim that Steady State holds while the Fault is present |
| Blast Radius | the traffic and Services an Experiment may affect |
| Control Group / Experiment Group | traffic without / with the Fault, inside the Blast Radius |
| Abort Condition | an SPS loss limit that stops an Experiment early |
| Verdict | the outcome: Hypothesis held, disproved, or Experiment aborted |
| Act | one scripted step of the lecture |
