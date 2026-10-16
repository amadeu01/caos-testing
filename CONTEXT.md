# Chaos Engineering Lecture Demo

A simulated streaming platform (modelled on Netflix) used in a lecture to show students how chaos experiments are written, how failure spreads through dependent services, and how tested resilience contains it.

## System under test

**Service**:
One independently running part of the streaming platform with a single responsibility (for example recommendations or playback).
_Avoid_: Microservice, node, component, container

**Dependency**:
A directed call from one Service to another. The unit on which resilience is enabled or disabled.
_Avoid_: Edge, connection, link

**Critical Dependency**:
A Dependency without which a playback start cannot succeed; no fallback can hide its loss.
_Avoid_: Hard dependency

**Non-critical Dependency**:
A Dependency whose loss can be hidden from the viewer by a Fallback.
_Avoid_: Soft dependency, optional dependency

**Fallback**:
The degraded but acceptable answer a Service returns when a Non-critical Dependency fails (for example popular titles instead of personalised recommendations).
_Avoid_: Default, backup

**Resilience Policy**:
The set of protections applied to one Dependency: timeout, retry, circuit breaker, bulkhead and Fallback.
_Avoid_: Hardening, protection settings

**Cascading Failure**:
Failure of one Service spreading to Services that depend on it, ending in loss of Steady State.
_Avoid_: Domino effect, outage

## Experimentation

**Steady State**:
The normal, measurable behaviour of the whole platform as seen by viewers, expressed through SPS.
_Avoid_: Baseline, healthy state

**SPS**:
Starts Per Second: the number of successful playback starts per second. The single business metric that defines Steady State.
_Avoid_: Streams per second, throughput, RPS

**Fault**:
A deliberately introduced real-world failure: a Service being stopped, a Dependency becoming slow, or a Dependency returning errors.
_Avoid_: Chaos, attack, injection

**Experiment**:
A written, repeatable test that states a Hypothesis, introduces a Fault within a Blast Radius, observes SPS and ends with a Verdict.
_Avoid_: Chaos test, scenario, game day

**Hypothesis**:
The claim an Experiment tries to disprove: that Steady State holds while the Fault is present.
_Avoid_: Expectation, assertion

**Blast Radius**:
The portion of viewer traffic and the set of Services an Experiment is allowed to affect.
_Avoid_: Scope, impact area

**Control Group**:
Viewer traffic inside the Blast Radius that receives no Fault, used as the comparison for the Experiment Group.
_Avoid_: Baseline group

**Experiment Group**:
Viewer traffic inside the Blast Radius that receives the Fault.
_Avoid_: Canary, test group

**Abort Condition**:
A limit on SPS loss that, when crossed, stops an Experiment early and removes the Fault.
_Avoid_: Kill switch, stop condition

**Verdict**:
The outcome of an Experiment: the Hypothesis held, the Hypothesis was disproved, or the Experiment was aborted.
_Avoid_: Result, status
