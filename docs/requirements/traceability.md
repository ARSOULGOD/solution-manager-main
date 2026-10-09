# Traceability — requirement → decision → design

One row per requirement. **Review this whenever a decision changes status**: a requirement whose satisfying decision
is `PROPOSED` is not yet covered, however complete the design looks.

Backfilled 2026-08-10 from the parent objectives and the architect group's stated constraints. **Refreshed 2026-08-18**
for the `UI-D*` / `DM-D*` / `FE-D*` families and the now-executable [`../../contracts/`](../../contracts/).

Bare `§` references are sections of [`../design/architecture.md`](../design/architecture.md); anything else is pathed.

## Coverage

| REQ | Requirement | Status | Satisfied by | Design | Covered? |
|---|---|---|---|---|---|
| [001](REQ-001-unified-config-layer.md) | Onboarding a solution needs no new console code | DESIGNED | `D1` 🔒 · `SM-D19` · `SM-D20` · **`UI-D*`** (esp. `UI-D11` packages-as-data, `UI-D14` closed vocabularies, `UI-D15` pure view model) | [`../design/dynamic-ui/design.md`](../design/dynamic-ui/design.md) + [`../../contracts/`](../../contracts/) (schema · gate validator · Cache and File Mesh fixtures · `negative/gate08-solution-specific-kind.json` = this REQ in executable form) · `architecture.md` §5.1 | ⚠️ mechanism changed — `D3` premise false. ⚠️ **`architecture.md` §14 is superseded** by `dynamic-ui/design.md` and must not be read alongside it |
| [002](REQ-002-support-three-solutions.md) | Support File Mesh, Cache, DB MI | ACCEPTED | `B/D5` 🔒 · `SM-D20` · `UI-D11` · `UI-D19` | §15 phasing · [`../../contracts/validation/fixtures/`](../../contracts/validation/fixtures/) — Cache curated, File Mesh **generated** from 1,989 real template rows | ⚠️ v1 = Cache only. Two of three now have a real package fixture; DB MI has none |
| [003](REQ-003-onprem-and-saas.md) | On-prem **and** multi-tenant SaaS | ACCEPTED | `D9` 🔒 · `B/D17` 🔒 · `SM-D1` · `FE-D7` · `FE-D19` | §3.1, §3.2 · [`../design/frontend/design.md`](../design/frontend/design.md) | ⚠️ CP/DP identity protocol unspecified. ✅ **Open item 21 CLOSED 2026-09-16 (ruling OR-10)** — the composition module the plane split needs is declared: `console-api`, a thin module with no database and no cross-schema SQL, which is why it does not conflict with `I7`. ⚠️ **Not built** — it is [plan 009](../plans/backend/009-console-api-and-monitoring-replacement.md)'s |
| [004](REQ-004-ui-drives-artifact-deploy.md) | UI drives artifact deployment | **LOCKED — proposed retirement by `SM-D2`** 🔒 | nothing yet; `SM-D2` is unratified | — | ⚠️ still binding until ratified |
| [005](REQ-005-three-runtimes.md) | Docker + Kubernetes + bare Linux | ACCEPTED | `B/D5` 🔒 · `SM-D25` · **`AGW-D*`** (the plane side of the `host-local` path the segmented-runtime columns require) | §12, §15 · [`../design/agent-gateway/design.md`](../design/agent-gateway/design.md) | ⚠️ K8s writer model unresolved. ⚠️ `AGW-D*` PROPOSED 2026-09-08; the agent↔plane wire (open item 6) still does not exist |
| [006](REQ-006-rbac.md) | Scoped RBAC | ACCEPTED | `D10` 🔒 · `D13` 🔒 · `SM-D23` · `SM-D31` · `DM-D18` · `FE-D22` · **[the ratified `D10` amendment](../design/D10-G2-APPROVAL-2026-09-15.md)** | §5, §16 · [`../design/dynamic-metrics/design.md`](../design/dynamic-metrics/design.md) §8 | ✅ **G2 CLOSED and the `D10` amendment RATIFIED/`LOCKED` 2026-09-16** (project owner = final architecture authority; open item 40 **closed**) — the **38**-verb catalog, three built-in roles, scoped bindings with downward inheritance, bootstrap admin with forced rotation, minimal local identity, durable/revocable sessions with CSRF, append-only CP audit and one enforcement point are approved; open item 24 closed. ⚠️ **Unavailable by design — open item 39's six remaining letters, not two:** no strategy-assertion verbs (b), no `group.*` verbs (c), no `instance.undeclare` (d), no package actions (e), no agent verbs (h), no `instance.version.redeclare` or archive/restore (i). ✅ `instance.stop`, `audit.view` and `user.edit` **are** now catalogued. ⚠️ **A different 35-member catalog still exists in the tree** alongside the ratified 38 — see that record §6. ⚠️ Nothing is implemented yet; scope of plan 006. ⚠️ Target owner only added by `SM-D23`. ✅ **`DM-D18`'s six verbs are catalogued** (§1.1 rows 16–18, 20–22), so open item 23 is closed with 24. |
| [007](REQ-007-six-month-metrics.md) | Six months of metrics, raw queryable | ACCEPTED | `SM-D8` · `SM-D26` · `SM-D29` · `SM-D30` · `DM-D10` · `DM-D11` · `DM-D14` · `AGW-D9` (the agent telemetry ingest boundary) | §8 · [`../design/dynamic-metrics/design.md`](../design/dynamic-metrics/design.md) §5.4 (advertised tiers), §5.6 · [`../design/agent-gateway/design.md`](../design/agent-gateway/design.md) §6 | ❌ **unproven — not benchmarked.** The read path (automatic tier routing, forced-Raw, streaming CSV export) is designed; the capacity to hold six months is not demonstrated |
| [008](REQ-008-no-footprint-customers.md) | Serve footprint-refusing customers | ACCEPTED | `SM-D12`/`SM-D23` · `SM-D11` | §4.1 | ⚠️ costs the outbound-only posture. ⚠️ no per-pod metrics on K8s for these customers (§13) — the UI shows fewer series with a stated reason |
| [009](REQ-009-no-mi-development.md) | No MI/MDK development required | ACCEPTED | **`HC1`** · `SM-D16` · `I1` · `UI-D12` · `DM-D3` | throughout · [`../design/dynamic-metrics/design.md`](../design/dynamic-metrics/design.md) §3.1 (the contract binds the **data plane**, not the process) | ✅ hard constraint, enforced by audit. `UI-D12` is `HC1` applied: MI version is **declared** because `build-info` is not fair game |
| [012](REQ-012-event-driven-live-console.md) | Event-driven backbone; live console state | ACCEPTED | `BE-D1`…`BE-D9` | [`../design/backend/design.md`](../design/backend/design.md) | ⚠️ all satisfying decisions PROPOSED, drafted 2026-08-27. ⚠️ `BE-D6` supersedes `DM-D17` (proposal-against-proposal, ratifies after); `BE-D7` amends `FE-D19` — flagged, not resolved |
| [013](REQ-013-dp-set-telemetry-cadence.md) | The DP sets an agent's telemetry pull and push intervals | PROPOSED | `AG-D26` (proposed by the design; not yet in `decisions.md`) | [`../superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md`](../superpowers/specs/2026-10-09-go-agent-telemetry-cadence-design.md) | ⚠️ needs two `SessionAccept.limits` fields the gateway design does not define (open item `AG-23`); per agent only, per binding stays `AG-14` |

🔒 = satisfied by a **LOCKED** decision. Everything else rests on a **PROPOSED** decision — `SM-D*`, `UI-D*`, `DM-D*`,
`FE-D*`, `AG-D*`, `BE-D*` or `AGW-D*` — and is therefore **provisionally** covered. If ratification changes the decision, the requirement reverts
to `ACCEPTED`. Status is decided only by the ratification matrix in [`../design/DECISIONS.md`](../design/DECISIONS.md).

## Reverse check — decisions with no requirement behind them

Not automatically wrong, but worth a second look: a decision nobody asked for is either implicit knowledge worth
capturing as a requirement, or scope creep.

| Decision | Why it exists with no REQ | Action |
|---|---|---|
| `SM-D2` (customer CI/CD deploys) | Inverts REQ-004 rather than satisfying a requirement | Needs a **positive** requirement: "SM must not require customers to surrender their deployment pipeline" |
| `SM-D13` (declared-only authority) | Follows from `SM-D2`; no requirement asks for it | Capture the consequence as a requirement or accept it as derived |
| `SM-D18` (Java backend) | Engineering choice, not a stated need | No requirement needed; it is a means |
| `SM-D24`/`SM-D25` | Corrections to defects, not new scope | None |
| **the whole `DM-D*` family** | **No metrics-UI requirement exists.** `REQ-001` and `REQ-007` are the closest and neither asks for dashboards, widgets or exploration. [`../design/dynamic-metrics/design.md`](../design/dynamic-metrics/design.md) §21 raises this as its own open item 2 and asks for **`REQ-010`** | **Raise `REQ-010`** with a named source, then add a row above. `design/README.md` step 1 requires the requirement exist *before* the design — that order was inverted here |
| `DM-D5` (`entity`/`tenant` scopes, `personal` layer) | The EC2-style cross-instance overlay it serves has no `REQ`, and it reverses `SM-D31`'s explicit rejection of per-user dashboards | Covered by `REQ-010` when raised; ratifies only **after** `SM-D31` |
| "reusable across multiple products" | Shapes [`../design/dynamic-metrics/design.md`](../design/dynamic-metrics/design.md) §9.3 materially but has **no source**. Its open item 3 asks for **`REQ-011`** | **Raise `REQ-011`** with a named source, or stop treating it as binding |
| `UI-D19` (Solace authors, customer loads) | Chosen to satisfy an operational need *without* touching locked `B/D4` | None — it is a means, and the constraint it respects is already recorded |
| **`AGW-D3`, `AGW-D8`, `AGW-D10`, `AGW-D12`** (session fencing, backpressure, HA routing, capacity model) | **No requirement states an agent-fleet scale or availability target.** `REQ-005` needs `host-local` to exist; nothing says how many agents, at what sample rate, with what failover expectation. [`../design/agent-gateway/design.md`](../design/agent-gateway/design.md) §13 AGW-4 raises this | **Raise a fleet-scale/availability requirement** with a named source before any capacity number from `AGW-D12`'s model is written into a runbook. `design/README.md` step 1 was, again, inverted here — recorded rather than hidden |

## Known trace hazards

- **REQ-004 is retired, not unsatisfied.** Anyone reading the parent objectives without this table will read it as an
  outstanding gap. And because `SM-D2` is **PROPOSED**, objective #4 is still formally **LOCKED**.
- **REQ-007 is the weakest link.** `SM-D26` makes six months a conformance prerequisite, but the sizing model has not
  been benchmarked (open item 3). Do not tell a customer six months is assured.
- **REQ-008 has a cost that no requirement records.** Serving footprint-refusing customers reverses locked `D2`/`§17`.
  That trade should arguably be its own requirement so it is visible to whoever ratifies.
- **`REQ-001`'s design pointer moved.** It pointed at `architecture.md` §5.1 **and §14**; §14 is **superseded** by
  [`../design/dynamic-ui/design.md`](../design/dynamic-ui/design.md), which says so in its own header, and
  [`../design/frontend/design.md`](../design/frontend/design.md) §5 repeats *"do not implement §14's observed-version
  renderer or YAML-leaf parser."* Anyone still reading §14 as the config-driven-UI design is reading a retracted
  document. §14 has not been rewritten or marked in place — that reconciliation is `dynamic-ui/design.md` open item 8.
- **`REQ-001` is now partly executable, and that is the strongest evidence in this table.**
  [`../../contracts/`](../../contracts/) turns "no new console code" into a CI assertion: gate 8 plus
  `negative/gate08-solution-specific-kind.json` fail the build the day a solution-specific `source.kind` appears. No
  other row has an equivalent.
- **`REQ-006` was UNBUILDABLE as recorded — ✅ closed 2026-09-16, and the history is kept because the gap was
  understated roughly fourfold** (corrected 2026-08-19 — `DECISIONS.md` **open item 24**). What was recorded: the
  approved mockup's own catalog enumerates **30 permissions of which only 2 are grantable**
  (`instance.bindTarget`, `target.use`), so under `D10` 🔒's deny-by-default and this requirement's own acceptance
  criterion — *"no endpoint is reachable without it"* — **every core v1 screen was unauthorizable**, not merely
  dashboards and export; first-admin bootstrap, the local identity store and the audit log were also undesigned.
  The **metrics slice** was the narrower half of the same gap: `DM-D18` needed six verbs `D10` did not define
  (open item 23).
  ⛔ **Both claims are DISCHARGED.** The [ratified `D10` amendment](../design/D10-G2-APPROVAL-2026-09-15.md)
  (`LOCKED` 2026-09-16) closes item 24 with a **38**-verb catalog, three built-in roles, bootstrap admin,
  the local identity store and the append-only CP audit, and closes item 23 by cataloguing `DM-D18`'s six verbs
  (§1.1 rows 16–18, 20–22). ⚠️ **"Buildable" is not "built"** — nothing is implemented; see
  [`REQ-006-rbac.md`](REQ-006-rbac.md) and the row above for what remains unavailable **by design** (open item 39's
  six remaining letters, including strategy-assertion and `group.*`).
- **Two design bodies have no requirement behind them.** Dynamic Metrics asks for `REQ-010` and `REQ-011` and neither
  exists. Until they do, this table cannot show what those ~1,400 lines of design serve.
- **Every ⚠️ and ❌ above traces to an open item** in [`../design/DECISIONS.md`](../design/DECISIONS.md). If that list is
  clean and this table still has gaps, one of them is wrong.
