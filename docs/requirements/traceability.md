# Traceability — requirement → decision → design

One row per requirement. **Review this whenever a decision changes status**: a requirement whose satisfying decision
is `PROPOSED` is not yet covered, however complete the design looks.

Backfilled 2026-08-10 from the parent objectives and the architect group's stated constraints.

## Coverage

| REQ | Requirement | Status | Satisfied by | Design | Covered? |
|---|---|---|---|---|---|
| [001](REQ-001-unified-config-layer.md) | Onboarding a solution needs no new console code | DESIGNED | `D1` 🔒 · `SM-D19` · `SM-D20` | `architecture.md` §5.1, §14 | ⚠️ mechanism changed — `D3` premise false |
| [002](REQ-002-support-three-solutions.md) | Support File Mesh, Cache, DB MI | ACCEPTED | `B/D5` 🔒 | §15 phasing | ⚠️ v1 = Cache only |
| [003](REQ-003-onprem-and-saas.md) | On-prem **and** multi-tenant SaaS | ACCEPTED | `D9` 🔒 · `B/D17` 🔒 · `SM-D1` | §3.1, §3.2 | ⚠️ CP/DP identity protocol unspecified |
| [004](REQ-004-ui-drives-artifact-deploy.md) | UI drives artifact deployment | **LOCKED — proposed retirement by `SM-D2`** 🔒 | nothing yet; `SM-D2` is unratified | — | ⚠️ still binding until ratified |
| [005](REQ-005-three-runtimes.md) | Docker + Kubernetes + bare Linux | ACCEPTED | `B/D5` 🔒 · `SM-D25` | §12, §15 | ⚠️ K8s writer model unresolved |
| [006](REQ-006-rbac.md) | Scoped RBAC | ACCEPTED | `D10` 🔒 · `D13` 🔒 · `SM-D23` | §5 | ⚠️ Target owner only added by `SM-D23` |
| [007](REQ-007-six-month-metrics.md) | Six months of metrics, raw queryable | ACCEPTED | `SM-D8` · `SM-D26` | §8 | ❌ **unproven — not benchmarked** |
| [008](REQ-008-no-footprint-customers.md) | Serve footprint-refusing customers | ACCEPTED | `SM-D12`/`SM-D23` · `SM-D11` | §4.1 | ⚠️ costs the outbound-only posture |
| [009](REQ-009-no-mi-development.md) | No MI/MDK development required | ACCEPTED | **`HC1`** · `SM-D16` · `I1` | throughout | ✅ hard constraint, enforced by audit |

🔒 = satisfied by a **LOCKED** decision. Everything else rests on a **PROPOSED** `SM-D*` decision and is therefore
**provisionally** covered — if ratification changes the decision, the requirement reverts to `ACCEPTED`.

## Reverse check — decisions with no requirement behind them

Not automatically wrong, but worth a second look: a decision nobody asked for is either implicit knowledge worth
capturing as a requirement, or scope creep.

| Decision | Why it exists with no REQ | Action |
|---|---|---|
| `SM-D2` (customer CI/CD deploys) | Inverts REQ-004 rather than satisfying a requirement | Needs a **positive** requirement: "SM must not require customers to surrender their deployment pipeline" |
| `SM-D13` (declared-only authority) | Follows from `SM-D2`; no requirement asks for it | Capture the consequence as a requirement or accept it as derived |
| `SM-D18` (Java backend) | Engineering choice, not a stated need | No requirement needed; it is a means |
| `SM-D24`/`SM-D25` | Corrections to defects, not new scope | None |

## Known trace hazards

- **REQ-004 is retired, not unsatisfied.** Anyone reading the parent objectives without this table will read it as an
  outstanding gap. And because `SM-D2` is **PROPOSED**, objective #4 is still formally **LOCKED**.
- **REQ-007 is the weakest link.** `SM-D26` makes six months a conformance prerequisite, but the sizing model has not
  been benchmarked (open item 3). Do not tell a customer six months is assured.
- **REQ-008 has a cost that no requirement records.** Serving footprint-refusing customers reverses locked `D2`/`§17`.
  That trade should arguably be its own requirement so it is visible to whoever ratifies.
- **Every ⚠️ and ❌ above traces to an open item** in `../design/DECISIONS.md`. If that list is clean and this table
  still has gaps, one of them is wrong.
