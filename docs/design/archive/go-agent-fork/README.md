# go-agent fork docs — ARCHIVED 2026-09-22

These files were `go-agent/docs/` — a component design (`design.md`), a decision
family (`decisions.md`, `AG-D1`–`AG-D6`), a contract-spine workplan and the
ASSUMED surrogate wire (`p2-wire-assumptions.md`, `p2-baseline.md`,
`p2-wire-assumptions-audit.md`, `p3-mechanism-gaps.md`).

**They are superseded and must not be cited.** Authority is:

| Topic | Now lives in |
|---|---|
| The agent↔DP wire, session, lease, commands, reports, telemetry ingest | [`../../backend/agent-gateway/design.md`](../../backend/agent-gateway/design.md) §4, [`decisions.md`](../../backend/agent-gateway/decisions.md) (`AGW-D1`–`AGW-D16`) |
| The agent's own decisions | [`../../go-agent/decisions.md`](../../go-agent/decisions.md) (`AG-D1`–`AG-D20`) |
| The implementation | [`../../../plans/go-agent/005-control-path.md`](../../../plans/go-agent/005-control-path.md) and [`../../go-agent/specs/2026-09-21-go-agent-control-path-design.md`](../../go-agent/specs/2026-09-21-go-agent-control-path-design.md) |

⚠️ **ID collision.** This family's `AG-D2`–`AG-D6` mean different things from the
baseline family's. The baseline is what the agent-gateway design was written
against and what the code implements. Cite `AGW-D*` or the baseline `AG-D*`;
never the IDs in this folder.

Kept because the gateway design's "Fork inventory" cites these paths, and
because `p2-wire-assumptions-audit.md` enumerates questions §4.3 answers —
reading one beside the other shows what was settled.
