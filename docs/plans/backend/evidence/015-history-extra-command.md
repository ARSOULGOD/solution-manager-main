# The "extra APPLY command" — root cause and fix (verified 2026-10-07)

> **History, taken verbatim from `origin/dp-agent-integration` @ `87e338e1` by plan 015 T0.1 (2026-10-08) and renamed from `011-…`.** It records a run of the *branch's* tree, not ours; it is not authority (plan [`015`](../015-agent-gateway.md) is). Apart from this note, only file names and links between history files were edited.

## The question

Under CPU pressure, a single apply request occasionally produced a second `APPLY` command instead of one. Is it a
valid replay, or a delivery/apply defect?

## Root cause

`AgentRuntimeAdapter` (the apply engine, `dp-core`) issues `PREFLIGHT` then `APPLY`, then **reads the applied
generation back** from `agent_actual_state` to confirm the write landed before declaring the attempt done. Before
the fix, that read-back had **no wait**: under CPU pressure the agent's report (which updates that projection) can
commit *after* the terminal `ACKED:APPLIED` the engine already has in hand, so the read-back sees the pre-apply
generation, calls it `materialize.readback.unavailable`, and retries — issuing a **second** `APPLY` for work that
already succeeded. This is a delivery-layer race in the *engine's own confirmation step*, not a protocol replay and
not duplicate agent-side execution: every extra command traces to exactly one `readback.unavailable` attempt, and
the report that resolves it lands only 13–16 ms after the ack it was racing.

**The fix** (`sm.dp.agent.readback-wait`, default 45 s): `awaitReadBack` now waits (bounded, `System.nanoTime`) for
`persistedGeneration >= completion.generation()` with a matching `effectiveHash` before returning, instead of
checking once.

## Evidence (`ExtraCommandUnderCpuPressureIT`, 20 busy threads pinned for the duration, real DP + real Go agent)

- **Arm A** (`readback-wait=0`, the pre-fix behaviour): 12 applies, **6 produced a second APPLY**. Every one of the 12
  traces that shows a second or third command also shows a `MATERIALIZE:FAILED:materialize.readback.unavailable`
  attempt — the assertion in the test checks this per-trace, not just a count, so a defect that produced an extra
  command for any other reason would fail it.
- **Arm B** (default wait): same pressure, same 12 applies, **0** produced a second APPLY and **0** attempts were
  retried for want of a read-back.
- Raw output: `015-history-raw-extra-command.md` (harness-generated, both arms, all 24 traces).

## Verdict

**Defect, found and fixed**, not valid replay — confirmed by a controlled A/B under the same fault, not by
inspection. Residual risk: the wait is bounded (45 s) tracked against `AgentRuntimeAdapter.READBACK_WAIT`; a report
delayed longer than that still times the attempt out as `materialize.readback.unavailable` and retries, so the race
is narrowed, not eliminated in principle — only reduced from "any CPU hiccup" to "a report delay exceeding 45 s",
which the capacity run (`015-history-capacity.md`) did not observe at 1000 agents.
