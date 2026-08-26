---
name: agent-md-context
description: >-
  Creates and updates go-agent/agent.md as durable project context for the
  Solution Manager monorepo, and writes end-of-session changes into it. Use only
  when explicitly asked — e.g. "update agent.md", "session wrap-up", "refresh
  agent context", or "create agent.md".
disable-model-invocation: true
---

# agent.md project context

## Purpose

Maintain **`go-agent/agent.md`** as a compact, durable context window for the **entire Solution Manager** monorepo (not go-agent-only content). Read it when the user asks for project context; **update it before the chat session ends** when the user asks for wrap-up / an agent.md update.

Do **not** auto-run this skill. Explicit invoke only.

## File location

| Artifact | Path |
|---|---|
| Context file | `go-agent/agent.md` |
| This skill | `.cursor/skills/agent-md-context/SKILL.md` |

If `go-agent/agent.md` is missing, create it from the template below, then fill from repo docs + the current session.

## When invoked

Determine mode from the user request:

| Mode | Triggers (examples) | Action |
|---|---|---|
| **Bootstrap** | "create agent.md", first-time setup | Create file from template; seed from `CLAUDE.md`, `docs/design/`, `go-agent/docs/` |
| **Session wrap-up** | "update agent.md", "session wrap-up", "end of session" | Read existing file; merge this session’s deltas; keep it short |
| **Refresh / load** | "read agent.md", "load project context" | Read file; summarize current state for the user; do not rewrite unless asked |

## Hard rules

1. **Whole-project scope** — cover SM planes, blocking open items, and active workstreams; do not shrink to go-agent-only prose even though the file lives under `go-agent/`.
2. **Pointers over paste** — link to `docs/design/DECISIONS.md`, `architecture.md`, component docs; do not duplicate long decision text.
3. **Keep it short** — prefer under ~150 lines. Cap session log at the **last 10** entries; older entries drop or fold into “Current state”.
4. **No secrets** — never write credentials, tokens, or secret values.
5. **Honest status** — mark PROPOSED / UNBASELINED / LOCKED correctly; do not invent ratification.
6. **Do not commit** unless the user explicitly asks to commit.

## Template (`go-agent/agent.md`)

```markdown
# Solution Manager — agent context

> Durable context for agents. Path: `go-agent/agent.md` (whole monorepo).
> Update on explicit session wrap-up only.

## Snapshot
- **Date:** YYYY-MM-DD
- **Baseline:** UNBASELINED — SM-D* PROPOSED; do not implement as ratified
- **Focus:** <active workstream>

## Project in one paragraph
<2–4 sentences: CP/DP, host-local vs dp-remote, SM owns config not artifacts>

## Canonical docs
| What | Where |
|---|---|
| Index | `CLAUDE.md` |
| Decisions | `docs/design/DECISIONS.md` |
| Architecture | `docs/design/architecture.md` |
| Review triage | `docs/tasks/codex-review-triage.md` |
| go-agent design | `go-agent/docs/design.md` |
| go-agent decisions | `go-agent/docs/decisions.md` (`AG-D*`) |
| go-agent workplan | `go-agent/docs/workplan.md` |

## Active workstreams
| Stream | Status | Next |
|---|---|---|
| Walking skeleton (dp-remote) | … | … |
| go-agent (host-local) | … | … |
| … | … | … |

## Blocking open items (agent-relevant)
- … (cite DECISIONS.md open item numbers / decision IDs)

## Hard constraints (do not violate)
- I1 no MI development · I2 verify external facts · I3 decision IDs · I4 SM-D* PROPOSED · I6 secrets · I7 plane boundary

## Session log (newest first, max 10)
### YYYY-MM-DD — <short title>
- **Done:** …
- **Decided:** …
- **Files touched:** …
- **Next:** …
```

## Session wrap-up procedure

1. **Read** `go-agent/agent.md` (create from template if missing).
2. **Extract this session** — what changed, what was decided, files touched, open questions, explicit next steps. Prefer facts over narrative.
3. **Update Snapshot** — date, focus, one-line status.
4. **Update Active workstreams** — status/next columns only; remove stale rows.
5. **Update Blocking open items** — only if this session changed blockers.
6. **Prepend** a Session log entry (newest first); trim to 10 entries.
7. **Fold** anything durable from old log lines into Snapshot / Active workstreams before deleting them.
8. Show the user a **short diff summary** (bullet list of what you wrote). Do not dump the whole file unless asked.

## Bootstrap procedure

1. Skim `CLAUDE.md`, `docs/design/README.md`, go-agent docs if present.
2. Write `go-agent/agent.md` from the template.
3. Fill Snapshot + Active workstreams from what is actually in the tree.
4. Add one Session log entry for the bootstrap session.
5. Tell the user the file path and that wrap-ups are explicit-only.

## Anti-patterns

- Rewriting the whole file every session (patch sections; prepend log).
- Copying full SM-D28 / design chapters into agent.md.
- Recording unverified “done” for work that was only discussed.
- Auto-updating at random mid-session without the user asking.
