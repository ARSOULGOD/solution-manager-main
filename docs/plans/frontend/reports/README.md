# Frontend task reports

One `task-NNN-report.md` per `FE-NNN` task in [`../implementation.md`](../implementation.md),
written when the task is verified done — durable, git-tracked evidence of what shipped, not the
`.superpowers/sdd/` scratch workspace (git-ignored, deleted after the final whole-branch review
per [`../execution.md`](../execution.md) §13–14).

**Shape:** what was implemented (with file-by-file rationale for anything beyond the task
brief's literal Files list), design decisions worth recording, the testing done (real command
output, not an assertion), Status, and the commit range. `task-001`/`task-002` additionally carry
their original fix-round history (code review findings and how they were resolved) since those
two tasks were executed through the `subagent-driven-development` dispatch loop; later tasks
were executed directly in-session, so their reports are correspondingly shorter.

`task-003`/`task-004` are marked as retroactively reconstructed from the commit record — they
predate this convention and were not transcribed live. Every report from `task-005` onward is
written at the time the task is verified.

**Not a replacement for:** [`../execution.md`](../execution.md) §7 / [`../task-list.md`](../task-list.md)
(the authoritative register — Status column, commit range, GitHub issue link) or the GitHub issue
itself (status/assignment, per `CLAUDE.md`). This folder is *why and how*; those are *what and
when*.
