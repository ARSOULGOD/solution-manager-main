# REQ-015 — The Data Plane sets how often an agent pushes its stored telemetry

| | |
|---|---|
| **Status** | PROPOSED |
| **Source** | go-agent developer (rinawarnav@gmail.com), brainstorming sessions for the telemetry cadence change |
| **Raised** | 2026-10-08, as `REQ-013` in solution-manager-main; renumbered here and narrowed to push, 2026-10-13 |
| **Priority** | should (provisional: the source did not state one) |
| **Phase** | v1 (`host-local` telemetry role) |

## Requirement

A user at the Data Plane chooses how often each `host-local` agent pushes the telemetry it has stored to the Data Plane.
Between pull and push the data is stored on the agent's host, and it is deleted once the Data Plane acknowledges it. When the
Data Plane chooses nothing, the agent pushes every minute (`OR-73`, `AGW-D27` item 4).

## Why

A predictable send schedule, set at the Data Plane rather than on the host. The project owner's cadence for the agent path is
5 s collection and a 1 min push (`OR-73`); the 5 s collection was built (`AG-D28`), the push was not.

## Acceptance

With pull 5 s (the declarations) and no push value from the Data Plane, an agent writes a batch every 5 s, sends the stored
batches together about once a minute, and deletes each one when it is acknowledged. With a push value set, it sends at that
interval from its next session. With push `0`, it sends each batch as soon as it is written.

## Satisfied by

`AG-D30` (PROPOSED) in [`../design/go-agent/decisions.md`](../design/go-agent/decisions.md) · design
[`../design/go-agent/specs/2026-10-13-go-agent-telemetry-push-interval-design.md`](../design/go-agent/specs/2026-10-13-go-agent-telemetry-push-interval-design.md)
· built by [`../plans/go-agent/008-telemetry-push-interval.md`](../plans/go-agent/008-telemetry-push-interval.md). The status
stays `PROPOSED` until the project owner accepts this requirement into scope.

## Conflicts

⚠️ **A Data-Plane-chosen value departs from `OR-128` (8)'s fixed values**; the default keeps them (central open item 48 (d)).
⚠️ **Until the Java gateway sends the field** (item 48 (a)), only the 1 min default and `fake-gateway` exercise it.
**Renumbered:** raised as `REQ-013` in solution-manager-main, where it also asked for a Data-Plane-set pull interval; this tree's
`REQ-013` is fleet apply (`OR-47`), and pull is already set per binding by the declarations (`AG-D28`).
