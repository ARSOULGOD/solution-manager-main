# REQ-013 — The Data Plane sets how often an agent pulls telemetry and how often it pushes it

| | |
|---|---|
| **Status** | PROPOSED |
| **Source** | go-agent developer (rinawarnav@gmail.com), brainstorming session for the telemetry cadence change |
| **Raised** | 2026-10-08 |
| **Priority** | should (provisional: the source did not state one) |
| **Phase** | v1 (`host-local` telemetry role) |

## Requirement

A user at the Data Plane chooses two durations for each `host-local` agent: how often it pulls telemetry from its bindings, and how often it pushes the data it has stored to the Data Plane. Between pull and push the data is stored on the agent's host, and it is deleted once the Data Plane acknowledges it.

## Why

A predictable send schedule: the agent sends in bursts at a known interval instead of once per pull, and the person operating the Data Plane, not the host, decides both rates.

## Acceptance

With pull 5 s and push 1 m set at the Data Plane, an agent writes a batch every 5 s, sends the stored batches together about once a minute, and deletes each one when it is acknowledged. When the Data Plane sets neither, the agent behaves as it did before this requirement.

## Satisfied by

`AG-D26` (PROPOSED, once the implementation plan adds it to [`../design/go-agent/decisions.md`](../design/go-agent/decisions.md)) · design [`../superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md`](../superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md)

## Conflicts

⚠️ **Needs a wire change the agent cannot decide.** The two durations travel as new `SessionAccept.limits` fields that the agent-gateway design's §4.3 does not define; they are a DRAFT ask, open item `AG-23`, owned by the `AG-D*` and `AGW` owners. Until a real gateway sends them, only `fake-gateway` exercises this requirement. ⚠️ **Per agent only**: per-binding rates from the Data Plane are part of open item `AG-14`.
