# Cross-cutting lessons

Patterns that affect more than one repo, or that are architectural/contract-level. **Every repo reads this file.**

Repo-specific lessons stay in that repo's own `docs/tasks/lessons.md` — including this repo's, at
[`../../tasks/lessons.md`](../../tasks/lessons.md). **When in doubt, put it here**: a lesson seen by
people who did not need it costs far less than one that stays invisible to someone who did.

**Review this at session start.** Each entry exists because something went wrong once.

---

## L1 — Verify framework defaults and licence terms; never recall them

**What happened:** three factual errors reached a design document that had already passed self-review — Spring Boot
actuator defaults, TimescaleDB's Apache-2/TSL split, and PostgreSQL `DETACH PARTITION` locking. Two of them broke
mechanisms the design depended on.

**Rule:** before writing any framework default, licence term or database semantic into a document, verify it with
**WebFetch** against vendor documentation. Web search is disabled here; WebFetch of a specific URL works.

**Why it recurs:** the failure mode is confident recall, not ignorance. It feels like knowing.

---

## L2 — Self-review does not catch wrong facts

**What happened:** a self-review pass found three genuine internal inconsistencies and zero factual errors. An
independent adversarial review then found six critical issues, three of them factual.

**Rule:** for anything that will be built against, run **both** — self-review for internal consistency, then an
independent reviewer instructed to *refute*, with a "looks good" conclusion treated as a failed review.

---

## L3 — Owning a config file is not owning the configuration

**What happened:** a design assumed writing an external Spring config file made SM the authority on config. Spring
resolves properties across an 11-source stack where config files rank 3rd; env vars, system properties and
command-line args all win.

**Rule:** when taking over any layered configuration system, establish **where your write lands in the precedence
order**, and verify the **effective** value rather than the value you wrote. Applies to backend (writes the file)
and go-agent (writes it locally) alike.

---

## L4 — Check the ID prefix before citing a decision

**What happened:** `B/D17` (editions) was recorded as `D17` (first vertical slice) during consolidation — with the
no-renumbering rule visible on the same screen.

**Rule:** decision families collide by number. Always cite with the prefix, and grep the register before asserting
what an ID means.

---

## L5 — Don't offer labelled options without showing the delta

**What happened:** options presented as three named "approaches" were rejected twice as unexplained, because they
were really three ways of organising the same decisions and the reader could not see what differed.

**Rule:** state what does **not** change across the options first, show the concrete per-option delta, anchor
against the status quo, and run the same real scenarios through each. Then ask.

---

## L6 — Read the actual artifact before designing a mechanism against it

**What happened:** three separate design mechanisms were built on assumptions about Cache and File Mesh that were
false, and none of it required special access to discover — just opening the files:

| Assumption | Reality | Cost |
|---|---|---|
| SM can set the config location via an env var | Cache's `Dockerfile:12` passes it on the **command line**, which outranks env vars | The config mechanism did not work on the v1 target solution |
| The config schema is generated from the solution's build | Cache has **no annotation processor and no generated metadata**; adding one violates `HC1` | A locked decision (`D3`) was unimplementable |
| File Mesh config metadata does not exist in a usable form | `FM_CONFIGURATION_TEMPLATE` is a **versioned, production config-driven-UI registry** | The platform's #1 objective was designed from scratch alongside a working implementation |

**Rule:** before specifying how the platform will interact with an existing artifact, **read that artifact** — its
Dockerfile/launch spec, its build file, and its actual schema. Two greps would have prevented all three. This is
`L1` (verify facts) applied to first-party code, where the temptation to assume is strongest because it feels
familiar.

---

## L7 — A guard that cannot fail is not a guard

**What happened:** `sync-conventions.sh --check` exited **0** when every target repo was missing, so CI would pass
against paths that did not exist. Separately, a mistyped mode fell through to the **write** path, and the drift check
compared the stamped hash rather than the block body — so a hand-edit was invisible.

**Rule:** for anything that gates a merge, enumerate the ways it can produce a **false green** and test each one
deliberately: absent target · unknown argument · duplicate content · content edited without touching metadata. A
guard tested only on the happy path is documentation, not enforcement.

---

## L8 — A proposal must never silently change a locked decision's status

**What happened:** the decision register let **PROPOSED** `SM-D2` mark **LOCKED** `B/D6` and `D16` as `RETIRED`,
while its own status legend said proposals are non-binding. Two developers could have built different systems and
both claimed compliance.

**Rule:** status transitions are caused by **ratification**, never by the existence of a proposal. A proposal records
*intent to supersede*; the label stays `LOCKED — proposed supersession by X` until a decision is actually taken.

---

## L9 — A fix that widens cardinality must be re-checked against every key it touches

**What happened:** allowing an Instance to bind to several Targets (multi-site) was the right abstraction, but series
identity was not updated to match. Since the suggested `source_key` values — pod name, host — are **not globally
unique**, two sites would have silently merged into one series and overwritten each other at identical scrape
timestamps. The fix for one defect created a worse one, in a different document.

**Rule:** when a change makes something *more* plural (one→many targets, one→many attachments, one→many writers), list
every uniqueness constraint, primary key and identity claim that assumed singularity, and check each. Widening
cardinality is exactly the change that invalidates keys silently — nothing fails at write time, the data just merges.

---

## L10 — Do not treat a build-time path as a runtime fact

**What happened:** `/projConfig/application.yml` was read as evidence that a deployed File Mesh MI mounts config at
that path. It is actually the **manager's own** generation path — it reads versioned template YAMLs from
`artifacts/connectorType/<type>/<version>/Config/` to seed its UI. Nothing about the running artifact was established.
Separately, `MIN_VERSION`/`MAX_VERSION` columns were cited as solving version-aware rendering, when the production
query has **no version predicate** and the backend interface **omits both fields**.

**Rule:** a column existing is not a feature working, and a path in a repo is not a mount in a container. Before citing
existing code as prior art, **find the read path** — the query, the interface, the consumer. Schema archaeology without
the call site produces confident, wrong conclusions.

---

## L11 — Search the repo before designing; the answer is often already here, verified

**What happened:** an independent review of the Subsystem A spec returned 5 CRITICAL findings. **Four** were cases where
the correct answer was already recorded in this repo, already verified, and simply not propagated:

| Designed as new | Already in the repo |
|---|---|
| Green/red health | `architecture.md:797` — "Restart windows render as *unready/gap*, **not 'down'**"; `DECISIONS.md:811` open item 15 + `architecture.md:835` — a Cache `active_standby` standby "reports `health: UP` while processing nothing" |
| "Never render a flat zero" as a new rule | `architecture.md:273` — `onUnavailable: degrade` already required **per mapping** |
| The Redis-provider hazard, rediscovered by reading source | `architecture.md:290-293` — "switching Cache to Redis … turns its KPI graphs into 500s and a flat zero, and **a flat zero is worse than an error**" |
| Dropping the custom-UI escape hatch as "dissolved" | `D1` is **`LOCKED`** and explicitly names it (`DECISIONS.md:59`) |

Reading the *reference codebases* was done well. Reading *this repo* was not — so the design re-derived what was settled
and contradicted what was locked.

**Rule:** before writing a design section, **grep this repo for its topic first** — `DECISIONS.md` (including the open
items, not just the ratified rows), `architecture.md`, and the invariants. Cite what you find, or state why you are
superseding it. A design that rediscovers a repo fact by reading source has skipped a step, and a design that
contradicts a `LOCKED` decision without flagging it has broken `I4`.

**Why it recurs:** the repo is large enough that recall substitutes for search, and reading source *feels* like the
rigorous option. It is — but it is the second step, not the first.

---

## L12 — Never report a grep count as a verified figure

**What happened:** a design doc's evidence table stated "1,494 template rows · 88 `showWhen` values · 9 `UI_TYPE_META`
keys" as **verified**. A quote-aware parser found **1,989 rows, 230 `showWhen`, and 22 keys** — plus a second
conditional mechanism (`dependency`, 41 rows) that the extraction had hidden entirely. The regex broke on apostrophes
and literal newlines inside `helperText` and silently skipped every row containing them. Nothing errored; the numbers
looked plausible; they were quoted into three documents and a commit message.

**Rule:** a count that will be written into a design is **derived by a checked-in script whose output is committed
alongside it**, and the script must **report its own parse failures** (`unparsed rows: 0`). Never quote a figure
produced by an ad-hoc `grep | sort | uniq -c` over structured data — SQL dumps, JSON, YAML. A lossy extraction is
indistinguishable from an accurate one at the point of use.

**Corollary:** structural conclusions can survive wrong counts — "all predicates are AND-of-equalities" held at 271
predicates as it did at the 88 originally sampled. Report the *check* ("0 grammar violations across all 271"), not the
sample, so the conclusion carries its own scope.

**See:** `docs/design/dynamic-ui/evidence/extract-fm-vocabulary.py` for the shape this takes.
