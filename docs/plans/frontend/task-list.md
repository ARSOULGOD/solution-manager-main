# Frontend task list — `FE-001`…`FE-125`, by phase

Source of truth: [`implementation.md`](implementation.md) (task content, authoritative) and
[`execution.md` §5](execution.md#5-the-nine-phases) + [`execution.md` §7](execution.md#7-task-register--all-125)
(phase/session/gate, deps/model/ready/status — authoritative). Task titles are the plan's own headings
(`^#### FE-NNN`); phase, deps, model tier, ready-class and status are the §7 register columns verbatim. Nothing
below is inferred beyond grouping those two tables by the register's `Phase` column.

Regenerate the flat title list with:
```bash
grep -n '^#### FE-' docs/plans/frontend/implementation.md | sed -E 's/^[0-9]+:#### //'
```

**Model key:** **O** = most capable · **S** = standard · **H** = cheapest.
**Status key:** `☐` schedulable · `◐` UX only, wire call blocked · `⛔` blocked · `⚠️` decision required.

---

## Phase 0 · Foundation — `001`–`010`, `105` (11 tasks)

| Session | Entry | Exit gate |
|---|---|---|
| **S1** | Branch + worktree | **Gate A** — build · lint · typecheck · unit · a11y · e2e all green in CI; reflow harness exists |

| ID | Task | Deps | Model | Ready | Status | Issue |
|---|---|---|---|---|---|---|
| 001 | Application scaffold, strict TypeScript, lint, CI | none | S | READY | ✅ | [#21](https://github.com/parikshit-coe/solution-manager/issues/21) (open — CI-bootstrap merge decision pending, see §16.1) |
| 002 | Design-token layer with stated provenance | 001 | H | READY | ✅ | [#22](https://github.com/parikshit-coe/solution-manager/issues/22) (closed) |
| 003 | MUI theme factory, colour mode, ECharts theme bridge | 002 | S | READY | ✅ | [#23](https://github.com/parikshit-coe/solution-manager/issues/23) (closed) |
| 004 | Providers, router, route table, code splitting | 003 | S | READY | ✅ | [#24](https://github.com/parikshit-coe/solution-manager/issues/24) (closed) |
| 005 | Runtime configuration boot | 001 | H | READY | ✅ | [#25](https://github.com/parikshit-coe/solution-manager/issues/25) (closed) |
| 006 | Error boundaries and the error taxonomy | 004 | S | READY | ✅ | [#26](https://github.com/parikshit-coe/solution-manager/issues/26) (closed) |
| 007 | Message catalogue and formatting | 001 | S | READY | ✅ | [#27](https://github.com/parikshit-coe/solution-manager/issues/27) |
| 008 | Test harness | 001 | S | READY | ✅ | [#28](https://github.com/parikshit-coe/solution-manager/issues/28) |
| 009 | Architecture guardrails in lint | 001 | **O** | READY | ✅ | [#29](https://github.com/parikshit-coe/solution-manager/issues/29) |
| 010 | Dev-only component gallery | 003, 004 | H | READY | ✅ | [#30](https://github.com/parikshit-coe/solution-manager/issues/30) (closed) |
| 105 | Responsive foundation: breakpoints, layout primitives, reflow harness | 002, 008 | **O** | READY | ✅ | [#31](https://github.com/parikshit-coe/solution-manager/issues/31) |

**Phase 0 issues live:** [#21–#31](https://github.com/parikshit-coe/solution-manager/issues?q=is%3Aissue+label%3Acomponent%3Afrontend+milestone%3A%22%22) on `parikshit-coe/solution-manager`, labelled `component:frontend` + `kind:implementation`. **All 125 tasks now have issues** (created 2026-09-02); per-phase ranges below.

---

## Phase 1 · Shared UI kernel — `011`–`025` (15 tasks)

| Session | Entry | Exit gate |
|---|---|---|
| **S2** | Gate A | — (rolls into Gate B) |

| ID | Task | Deps | Model | Ready | Status | Issue |
|---|---|---|---|---|---|---|
| 011 | Buttons | 003, 010 | S | READY | ✅ | [#33](https://github.com/parikshit-coe/solution-manager/issues/33) (closed) |
| 012 | Form control wrappers | 003, 010 | S | READY | ✅ | [#34](https://github.com/parikshit-coe/solution-manager/issues/34) (closed) |
| 013 | Chip, StateChip and the state-vocabulary module | 003, 010 | S | READY | ✅ | [#35](https://github.com/parikshit-coe/solution-manager/issues/35) (closed) |
| 014 | Alert / Banner and Tooltip / InfoTip | 003, 010 | S | READY | ✅ | [#36](https://github.com/parikshit-coe/solution-manager/issues/36) (closed) |
| 015 | Surfaces and page furniture | 003, 010 | H | READY | ✅ | [#37](https://github.com/parikshit-coe/solution-manager/issues/37) |
| 016 | Dialog, ConfirmDialog, high-consequence confirm | 003, 010 | S | READY | ✅ | [#38](https://github.com/parikshit-coe/solution-manager/issues/38) |
| 017 | Menu / Popover | 003, 010 | H | READY | ✅ | [#39](https://github.com/parikshit-coe/solution-manager/issues/39) |
| 018 | ToggleButtonGroup and route-driven Tabs | 003, 010 | S | READY | ✅ | [#40](https://github.com/parikshit-coe/solution-manager/issues/40) |
| 019 | EmptyState and Skeleton | 003, 010 | H | READY | ✅ | [#41](https://github.com/parikshit-coe/solution-manager/issues/41) |
| 020 | DataTable on MUI X Data Grid Community | 003, 010 | **O** | READY | ✅ | [#42](https://github.com/parikshit-coe/solution-manager/issues/42) |
| 021 | Toast and the persistent outcome badge | 003, 010 | S | READY | ✅ | [#43](https://github.com/parikshit-coe/solution-manager/issues/43) |
| 022 | DiffView | 003, 010 | S | READY | ✅ | [#44](https://github.com/parikshit-coe/solution-manager/issues/44) |
| 023 | SecretField | 003, 010 | S | FIXTURE | ✅ | [#45](https://github.com/parikshit-coe/solution-manager/issues/45) |
| 024 | Breadcrumbs and SidebarNav | 003, 010 | H | READY | ✅ | [#46](https://github.com/parikshit-coe/solution-manager/issues/46) |
| 025 | FilterBar and SearchField | 003, 010 | S | READY | ✅ | [#47](https://github.com/parikshit-coe/solution-manager/issues/47) |

**Phase 1 issues live:** [#33–#47](https://github.com/parikshit-coe/solution-manager/issues?q=is%3Aissue+label%3Acomponent%3Afrontend+milestone%3A%22%22) on `parikshit-coe/solution-manager`, labelled `component:frontend` + `kind:implementation`.

---

## Phase 2 · API seam, auth, permissions — `026`–`035` (10 tasks)

| Session | Entry | Exit gate |
|---|---|---|
| **S2** | Gate A | **Gate B** — a component is buildable against a repository interface with no knowledge of its backing |

| ID | Task | Deps | Model | Ready | Status | Issue |
|---|---|---|---|---|---|---|
| 026 | HTTP core | 001 | S | READY | ✅ | [#132](https://github.com/parikshit-coe/solution-manager/issues/132) (closed) |
| 027 | Plane clients behind one gateway origin | 026, 005 | H | READY | ✅ | [#133](https://github.com/parikshit-coe/solution-manager/issues/133) (closed) |
| 028 | Typed error codec and retry policy | 026 | S | FIXTURE | ✅ | [#134](https://github.com/parikshit-coe/solution-manager/issues/134) (closed) |
| 029 | QueryClient defaults, fail-closed | 028 | H | READY | ✅ | [#135](https://github.com/parikshit-coe/solution-manager/issues/135) (closed) |
| 030 | Auth adapter, local session, DEV-only mock | 001 | S | FIXTURE | ✅ | [#136](https://github.com/parikshit-coe/solution-manager/issues/136) (closed) |
| 031 | Route guards, 401 handling, idle lock | 030, 004, 005 | S | READY | ✅ | [#137](https://github.com/parikshit-coe/solution-manager/issues/137) (closed) |
| 032 | Permission model, omit-vs-disable | 001 | S | READY | ✅ | [#138](https://github.com/parikshit-coe/solution-manager/issues/138) (closed) |
| 033 | Fixture layer (MSW) and repository interfaces | 008 | **O** | READY | ✅ | [#139](https://github.com/parikshit-coe/solution-manager/issues/139) (closed) |
| 034 | Contract types seam and codegen script | 001 | H | FIXTURE | ✅ | [#140](https://github.com/parikshit-coe/solution-manager/issues/140) (closed) |
| 035 | Streaming download helper | 026, 016 | S | FIXTURE | ✅ | [#141](https://github.com/parikshit-coe/solution-manager/issues/141) (closed) |

**Phase 2 issues live:** [#132–#141](https://github.com/parikshit-coe/solution-manager/issues) on `parikshit-coe/solution-manager`, labelled `component:frontend` + `kind:implementation`. All closed — created retroactively for tracker parity.

---

## Phase 3 · Shell, identity, inventory — `036`–`045`, `106`, `107`, `111`, `112`, `114`–`120` (21 tasks)

| Session | Entry | Exit gate |
|---|---|---|
| **S3** | Gate B | **Gate C** — every Phase 3 screen renders 5 states from fixtures; shell and inventory reflow |

| ID | Task | Deps | Model | Ready | Status | Issue |
|---|---|---|---|---|---|---|
| 036 | AppShell | 024, 017, 021, 030 | S | READY | ✅ | [#48](https://github.com/parikshit-coe/solution-manager/issues/48) (closed) |
| 037 | Login screen | 030, 031, 012, 014 | S | FIXTURE | ✅ | [#49](https://github.com/parikshit-coe/solution-manager/issues/49) (closed) |
| 038 | Home | 036, 019, 013, 033 | S | FIXTURE | ✅ | [#50](https://github.com/parikshit-coe/solution-manager/issues/50) (closed) |
| 039 | Solutions catalog | 036, 013, 015, 019 | S | FIXTURE | ✅ | [#51](https://github.com/parikshit-coe/solution-manager/issues/51) (closed) |
| 040 | Load-a-package dialog | 039, 016, 032 | H | FIXTURE | ✅ | [#52](https://github.com/parikshit-coe/solution-manager/issues/52) (closed) |
| 041 | Instance inventory grid | 020, 013, 033 | **O** | FIXTURE | ✅ | [#53](https://github.com/parikshit-coe/solution-manager/issues/53) (closed) |
| 042 | Inventory URL state, filters, deep-link focus | 041, 025 | S | READY | ✅ | [#54](https://github.com/parikshit-coe/solution-manager/issues/54) (closed) |
| 043 | Bulk restart confirmation | 041, 016 | S | BLOCKED | ◐ UX only | [#55](https://github.com/parikshit-coe/solution-manager/issues/55) (closed) |
| 044 | Instance context header and category tabs | 018, 021, 013 | H | FIXTURE | ✅ | [#56](https://github.com/parikshit-coe/solution-manager/issues/56) (closed) |
| 045 | Declare-instance stub and the 403 page | 041, 016, 032 | H | FIXTURE | ✅ | [#57](https://github.com/parikshit-coe/solution-manager/issues/57) |
| 106 | Responsive app shell and navigation | 036, 105 | S | READY | ✅ | [#58](https://github.com/parikshit-coe/solution-manager/issues/58) |
| 107 | Responsive data presentation — table-to-card | 020, 041, 105 | **O** | FIXTURE | ✅ | [#59](https://github.com/parikshit-coe/solution-manager/issues/59) |
| 111 | Collapsible nav drawer at desktop widths; dark-mode login contrast fix | 036, 037, 106 | S | READY | ✅ | [#142](https://github.com/parikshit-coe/solution-manager/issues/142) (closed) |
| 112 | Sidebar solution list wired to the catalog | 036, 039, 033 | S | READY | ✅ | [#143](https://github.com/parikshit-coe/solution-manager/issues/143) (closed) |
| 114 | Load a Solution Package from a file at runtime (dev) | 040, 113 | **O** | READY | ✅ | [#144](https://github.com/parikshit-coe/solution-manager/issues/144) (closed) |
| 115 | Declare an instance from a form (supersedes 045's stub) | 041, 045, 016, 114 | **O** | FIXTURE | ✅ | [#145](https://github.com/parikshit-coe/solution-manager/issues/145) (closed) |
| 116 | Fix select-all clearing the selection instead of filling it | 020, 041 | S | READY | ✅ | [#146](https://github.com/parikshit-coe/solution-manager/issues/146) (closed) |
| 117 | Undeclare an instance | 041, 115, 116 | **O** | FIXTURE | ✅ | [#147](https://github.com/parikshit-coe/solution-manager/issues/147) (closed) |
| 118 | A package declares several versions; an instance is declared against one | 046, 113, 114, 115 | **O** | FIXTURE | ✅ | [#148](https://github.com/parikshit-coe/solution-manager/issues/148) (closed) |
| 119 | Package Version column, and a grid whose column widths actually apply | 020, 041, 107, 118 | **O** | READY | ✅ | [#149](https://github.com/parikshit-coe/solution-manager/issues/149) (closed) |
| 120 | Instance count derived, never a hardcoded fixture number | 033, 039, 112, 115, 117 | S | READY | ✅ | [#150](https://github.com/parikshit-coe/solution-manager/issues/150) (closed) |

`118`–`120` produced [`FE-D31`](../../design/frontend/decisions.md) and `FE-D32`; their post-execution review is
[`implementation.md`](implementation.md) §Review, and the per-task detail is in [`reports/`](reports/).

`121`–`125` produced `FE-D33`…`FE-D37` — §16 **Q19** resolved on the frontend side, the `shared → contracts`
boundary widened, `one-of-many` composition explicitly not invented, no Cancel control, and the
version-threading discipline. They also discharge the deletion `113` left open; `124` ships **UX only** on
open item 1, like `061`/`062`.

**Phase 3 issues live:** [#48–#59](https://github.com/parikshit-coe/solution-manager/issues?q=is%3Aissue+label%3Acomponent%3Afrontend+milestone%3A%22%22) (`036`–`045`, `106`, `107`) plus [#142–#150](https://github.com/parikshit-coe/solution-manager/issues) (`111`, `112`, `114`–`120`) on `parikshit-coe/solution-manager`, labelled `component:frontend` + `kind:implementation`.

---

## Phase 4 · Designer — `046`–`063`, `108`, `113`, `121`–`125` (25 tasks)

| Session | Entry | Exit gate |
|---|---|---|
| **S4** | Gate C | **Gate D** — fixture e2e journey 1: save carries a generation, apply renders `APPLY_INDETERMINATE`. Does **not** close open item 1 |

| ID | Task | Deps | Model | Ready | Status | Issue |
|---|---|---|---|---|---|---|
| 046 | View-model types, `project()`, shared fixture runner | 008 | **O** | FIXTURE | ✅ | [#61](https://github.com/parikshit-coe/solution-manager/issues/61) (closed) |
| 047 | Control registry | 012, 023, 046 | S | READY | ✅ | [#62](https://github.com/parikshit-coe/solution-manager/issues/62) (closed) |
| 048 | FieldRow | 047 | S | READY | ✅ | [#63](https://github.com/parikshit-coe/solution-manager/issues/63) (closed) |
| 049 | SectionCard and advanced disclosure | 048 | S | READY | ✅ | [#64](https://github.com/parikshit-coe/solution-manager/issues/64) (closed) |
| 050 | Section navigation and jump | 049 | H | READY | ✅ | [#65](https://github.com/parikshit-coe/solution-manager/issues/65) (closed) |
| 051 | Field search | 049 | H | READY | ✅ | [#66](https://github.com/parikshit-coe/solution-manager/issues/66) (closed) |
| 052 | Draft form protocol (React Hook Form) | 046, 049 | **O** | READY | ✅ | [#67](https://github.com/parikshit-coe/solution-manager/issues/67) (closed) |
| 053 | Validation tiers | 052 | S | READY | ✅ | [#68](https://github.com/parikshit-coe/solution-manager/issues/68) (closed) |
| 054 | ValidationSummary | 053, 050 | H | READY | ✅ | [#69](https://github.com/parikshit-coe/solution-manager/issues/69) (closed) |
| 055 | Save flow, save bar, config generation | 052, 053, 026 | S | FIXTURE | ✅ | [#70](https://github.com/parikshit-coe/solution-manager/issues/70) (closed) |
| 056 | `409` conflict → refetch and diff | 055, 022 | S | FIXTURE | ✅ | [#71](https://github.com/parikshit-coe/solution-manager/issues/71) (closed) |
| 057 | Warning override with recorded reason | 053, 055, 016 | H | FIXTURE | ✅ | [#72](https://github.com/parikshit-coe/solution-manager/issues/72) (closed) |
| 058 | Package-declared actions | 047, 026 | H | FIXTURE | ✅ | [#73](https://github.com/parikshit-coe/solution-manager/issues/73) (closed) |
| 059 | Version states, blocked banner, accept/restore | 046, 022, 016 | **O** | FIXTURE | ✅ | [#74](https://github.com/parikshit-coe/solution-manager/issues/74) (closed) |
| 060 | Rollout card: config stepper and replica table | 013, 020 | S | FIXTURE | ✅ | [#75](https://github.com/parikshit-coe/solution-manager/issues/75) (closed) |
| 061 | Apply: strategy picker, restart confirm, `APPLY_INDETERMINATE` | 060, 016, 032 | **O** | BLOCKED | ✅ | [#76](https://github.com/parikshit-coe/solution-manager/issues/76) (closed) |
| 062 | `CONFIG_DIVERGED` and rollback with a diff preview | 060, 022, 016 | S | BLOCKED | ✅ | [#77](https://github.com/parikshit-coe/solution-manager/issues/77) (closed) |
| 063 | Composer canvas (multi-component solutions) | 049, 052 | **O** | FIXTURE | ✅ | [#78](https://github.com/parikshit-coe/solution-manager/issues/78) (closed) |
| 108 | Responsive Designer | 049, 050, 052, 055, 063, 105 | **O** | FIXTURE | ✅ | [#79](https://github.com/parikshit-coe/solution-manager/issues/79) |
| 113 | Solution Packages as the source of the Designer view model | 033, 046 | **O** | READY | ✅ | [#151](https://github.com/parikshit-coe/solution-manager/issues/151) (closed) |
| 121 | One version rule: `packageVersion` reaches every `project()` call | 046, 052, 063, 118 | S | READY | ✅ | [#152](https://github.com/parikshit-coe/solution-manager/issues/152) (closed) |
| 122 | The Designer read carries the package, not a projection (resolves Q19) | 033, 046, 113, 121 | **O** | FIXTURE | ✅ | [#153](https://github.com/parikshit-coe/solution-manager/issues/153) (closed) |
| 123 | `DesignerPage` — the screen, and the route that renders it | 044, 047–059, 121, 122 | **O** | FIXTURE | ✅ | [#154](https://github.com/parikshit-coe/solution-manager/issues/154) (closed) |
| 124 | The composer branch and the rollout/apply half | 060–063, 122, 123 | S | BLOCKED | ✅ | [#155](https://github.com/parikshit-coe/solution-manager/issues/155) (closed) |
| 125 | Designer reflow, accessibility sweep, and the Gate D journey | 105, 108, 123, 124 | S | FIXTURE | ✅ | [#156](https://github.com/parikshit-coe/solution-manager/issues/156) (closed) |

**Phase 4 issues live:** [#61–#79](https://github.com/parikshit-coe/solution-manager/issues?q=is%3Aissue+label%3Acomponent%3Afrontend+milestone%3A%22%22) (`046`–`063`, `108`) plus [#151–#156](https://github.com/parikshit-coe/solution-manager/issues) (`113`, `121`–`125`) on `parikshit-coe/solution-manager`, labelled `component:frontend` + `kind:implementation`.

---

## Phase 5 · Governance — `064`–`067` (4 tasks)

| Session | Entry | Exit gate |
|---|---|---|
| **S5** | Gate C (not D) | `NOT_APPLICABLE` never red; standby ≠ healthy |

| ID | Task | Deps | Model | Ready | Status | Issue |
|---|---|---|---|---|---|---|
| 064 | Health hero and replica cards | 044, 013, 015 | S | FIXTURE | ✅ | [#80](https://github.com/parikshit-coe/solution-manager/issues/80) (closed) |
| 065 | Configuration-state ladder | 064 | H | FIXTURE | ✅ | [#81](https://github.com/parikshit-coe/solution-manager/issues/81) (closed) |
| 066 | Log viewer | 015, 016, 021 | S | FIXTURE | ✅ | [#82](https://github.com/parikshit-coe/solution-manager/issues/82) (closed) |
| 067 | Governance states and accessibility pass | 064–066 | H | FIXTURE | ✅ | [#83](https://github.com/parikshit-coe/solution-manager/issues/83) (closed) |

**Phase 5 issues live:** [#80–#83](https://github.com/parikshit-coe/solution-manager/issues) on `parikshit-coe/solution-manager`, labelled `component:frontend` + `kind:implementation`.

---

## Phase 6 · Monitoring — `068`–`089`, `109`, `126`–`132` (30 tasks)

| Session | Entry | Exit gate |
|---|---|---|
| **S5** | Gate C (not D) | Gaps render as breaks in every path; Explore/dashboard reflow |

| ID | Task | Deps | Model | Ready | Status | Issue |
|---|---|---|---|---|---|---|
| 068 | MetricsProvider and capabilities (fail closed) | 027, 029 | S | READY | ✅ | [#107](https://github.com/parikshit-coe/solution-manager/issues/107) (closed) |
| 069 | Catalog queries | 068 | H | READY | ✅ | [#108](https://github.com/parikshit-coe/solution-manager/issues/108) (closed) |
| 070 | `buildQuery` and the canonical query hash | 068 | **O** | READY | ✅ | [#109](https://github.com/parikshit-coe/solution-manager/issues/109) (closed) |
| 071 | `projectSeries` and its shared fixture suite | 008 | **O** | READY | ✅ | [#110](https://github.com/parikshit-coe/solution-manager/issues/110) (closed) |
| 072 | ChartRenderer core | 003, 071 | **O** | READY | ✅ | [#111](https://github.com/parikshit-coe/solution-manager/issues/111) (closed) |
| 073 | Gaps, coverage, thresholds, annotations | 072 | S | READY | ✅ | [#112](https://github.com/parikshit-coe/solution-manager/issues/112) (closed) |
| 074 | Tooltip and legend | 073 | S | READY | ✅ | [#113](https://github.com/parikshit-coe/solution-manager/issues/113) (closed) |
| 075 | Chart accessibility | 072–074 | **O** | READY | ✅ | [#114](https://github.com/parikshit-coe/solution-manager/issues/114) (closed) |
| 076 | Single-stat and gauge marks | 071, 015 | H | READY | ✅ | [#115](https://github.com/parikshit-coe/solution-manager/issues/115) (closed) |
| 077 | Explore shell and gated control steps | 068–076 | H | READY | ✅ | [#116](https://github.com/parikshit-coe/solution-manager/issues/116) (closed) |
| 078 | InstanceSelector with projected series count | 077, 068 | S | READY | ✅ | [#117](https://github.com/parikshit-coe/solution-manager/issues/117) (closed) |
| 079 | MetricSelector, `needs-typing`, type-metric dialog | 069, 077 | S | FIXTURE | ✅ | [#118](https://github.com/parikshit-coe/solution-manager/issues/118) (closed) |
| 080 | Aggregation and group-by controls | 077, 079 | S | READY | ✅ | [#119](https://github.com/parikshit-coe/solution-manager/issues/119) |
| 081 | TimeRangePicker and ResolutionControl | 068, 032 | S | READY | ✅ | [#120](https://github.com/parikshit-coe/solution-manager/issues/120) |
| 082 | Explore result states and typed `422` rendering | 077–081, 028 | **O** | FIXTURE | ✅ | [#121](https://github.com/parikshit-coe/solution-manager/issues/121) |
| 083 | Explore draft persistence | 077 | H | READY | ✅ | [#122](https://github.com/parikshit-coe/solution-manager/issues/122) (closed) |
| 084 | SaveWidgetDialog, widget config schema and migrations | 082, 016 | S | FIXTURE | ✅ | [#123](https://github.com/parikshit-coe/solution-manager/issues/123) (closed) |
| 085 | DashboardView and toolbar | 084, 081 | S | FIXTURE | ✅ | [#124](https://github.com/parikshit-coe/solution-manager/issues/124) |
| 086 | DashboardGrid and WidgetFrame | 085, 006, 072 | **O** | FIXTURE | ✅ | [#125](https://github.com/parikshit-coe/solution-manager/issues/125) |
| 087 | Layers: badge, filter, hide-not-delete, reset | 086 | S | FIXTURE | ✅ | [#126](https://github.com/parikshit-coe/solution-manager/issues/126) (closed) |
| 088 | CSV export | 035, 085 | H | READY | ✅ | [#127](https://github.com/parikshit-coe/solution-manager/issues/127) |
| 089 | Monitoring first-run | 085, 019 | H | FIXTURE | ✅ | [#128](https://github.com/parikshit-coe/solution-manager/issues/128) |
| 109 | Responsive Monitoring — Explore and dashboard grid | 077, 081, 085, 086, 105 | S | FIXTURE | ✅ | [#129](https://github.com/parikshit-coe/solution-manager/issues/129) |
| 126 | Fix the `useEcharts` StrictMode disposal race | 072 | S | READY | ☐ | — |
| 127 | Correct the ECharts theme shape (axis families) | 003, 072 | S | READY | ☐ | — |
| 128 | Chart legibility — grid, axes, bottom legend, data-table affordance | 126, 127, 074 | S | READY | ☐ | — |
| 129 | Widget card sizing and chrome to the reference | 086, 128 | S | READY | ☐ | — |
| 130 | Monitoring sub-tabs (Dashboards · Explore) | 077, 085, 044 | S | READY | ☐ | — |
| 131 | Dashboard toolbar, banners, and an Add-widget action | 085, 087, 130 | S | READY | ☐ | — |
| 132 | Persist the fixture dashboard; enrich the default widget set | 084, 087, 129 | S | FIXTURE | ☐ | — |

**`126`–`132` (added 2026-09-03)** close the gap between the shipped Monitoring screens and
[`docs/design/frontend/reference/`](../../design/frontend/reference/README.md) (`FE-D30`). Plan and root-cause
analysis: [`126-monitoring-ui-reference-alignment.md`](126-monitoring-ui-reference-alignment.md). `126` and
`127` are **defect fixes, not polish** — the ECharts canvas is disposed by a StrictMode race and never
draws, and the theme's axis keys are at a level ECharts does not read. Land those two first.

**Phase 6 issues live:** [#107–#129](https://github.com/parikshit-coe/solution-manager/issues?q=is%3Aissue+label%3Acomponent%3Afrontend+milestone%3A%22%22) on `parikshit-coe/solution-manager`, labelled `component:frontend` + `kind:implementation`.

---

## Phase 7 · Admin & Targets — `090`–`095` (6 tasks) · ⛔ NOT SCHEDULED

| Session | Entry | Exit gate |
|---|---|---|
| **S7** | **Q4 + Q5 answered** | ⛔ not scheduled |

| ID | Task | Deps | Model | Ready | Status | Issue |
|---|---|---|---|---|---|---|
| 090 | Admin shell, tabs, and the honesty banner | 018, 032 | H | FIXTURE | ✅ | [#157](https://github.com/parikshit-coe/solution-manager/issues/157) (closed) |
| 091 | Roles and RoleEditor | 090, 020 | S | FIXTURE | ✅ | [#158](https://github.com/parikshit-coe/solution-manager/issues/158) (closed) |
| 092 | Assignments and ScopeTree | 090 | S | FIXTURE | ✅ | [#159](https://github.com/parikshit-coe/solution-manager/issues/159) (closed) |
| 093 | Access check (effective permissions) | 092 | H | FIXTURE | ✅ | [#160](https://github.com/parikshit-coe/solution-manager/issues/160) (closed) |
| 094 | Users (local) | 090, 020 | S | DECISION | ✅ | [#161](https://github.com/parikshit-coe/solution-manager/issues/161) (closed) |
| 095 | Targets preview | 090 | S | BLOCKED | ✅ read-only | [#162](https://github.com/parikshit-coe/solution-manager/issues/162) (closed) |

**Phase 7 issues live:** [#157–#162](https://github.com/parikshit-coe/solution-manager/issues) on `parikshit-coe/solution-manager`, labelled `component:frontend` + `kind:implementation`. `090`–`095` closed (built). The phase stays **not scheduled** — issues exist for tracking, not to signal readiness.

**`095` scope:** the entire deliverable is the **read-only** model preview; there is no wire half for a screen that never writes. Credential entry stays deliberately absent — `DECISIONS.md` open items 1, 2 (the `dp-remote` apply protocol). The blocked-work note says so on the screen.

**`094` decision (§16 Q5, frontend surface only):** the credential path is now recorded — `FE-D38` picks the admin-set temporary credential (forced change on first sign-in). The first-admin bootstrap UI is still not built; Q5's mechanism and the `POST /admin/users` contract remain product/backend-owned and open.

---

## Phase 8 · Hardening — `096`–`104`, `110` (10 tasks)

| Session | Entry | Exit gate |
|---|---|---|
| **S6** | Phases 3–6 merged | §18's readiness gate answered with evidence; `verify:reflow` green |

| ID | Task | Deps | Model | Ready | Status | Issue |
|---|---|---|---|---|---|---|
| 096 | Code splitting and a bundle budget | 072, all features | S | READY | ✅ | [#163](https://github.com/parikshit-coe/solution-manager/issues/163) |
| 097 | Palette and contrast validation in CI | 002, 003 | S | READY | ✅ | [#164](https://github.com/parikshit-coe/solution-manager/issues/164) (closed) |
| 098 | Automated accessibility sweep | all screens | S | READY | ✅ | [#165](https://github.com/parikshit-coe/solution-manager/issues/165) (closed) |
| 099 | End-to-end journeys | all features | **O** | FIXTURE | ✅ | [#166](https://github.com/parikshit-coe/solution-manager/issues/166) (closed) |
| 100 | CSP and air-gap verification | 096 | S | READY | ✅ | [#167](https://github.com/parikshit-coe/solution-manager/issues/167) (closed) |
| 101 | Production build assertions | 005, 010, 030 | S | READY | ✅ | [#168](https://github.com/parikshit-coe/solution-manager/issues/168) |
| 102 | Observability and secret-leak prevention | 026, 006 | S | READY | ✅ | [#169](https://github.com/parikshit-coe/solution-manager/issues/169) |
| 103 | Performance verification | 086, 041, 049 | S | READY | ✅ | [#170](https://github.com/parikshit-coe/solution-manager/issues/170) |
| 110 | Reflow conformance in CI | 105–109, 098 | S | READY | ✅ | [#172](https://github.com/parikshit-coe/solution-manager/issues/172) (closed) |
| 104 | Documentation and plan closure | all | H | READY | ✅ | [#171](https://github.com/parikshit-coe/solution-manager/issues/171) |

**Phase 8 issues live:** [#163–#172](https://github.com/parikshit-coe/solution-manager/issues) on `parikshit-coe/solution-manager`, labelled `component:frontend` + `kind:implementation`. `096`–`104` and `110` built. `#163` (`096`) is still open on GitHub despite being built — noted at plan closure, see the plan's Review section.

---

**Tally** (execution.md §7, re-extracted 2026-08-28; `126`–`132` added 2026-09-03): 132 tasks · 80 `READY` · 46 `FIXTURE` · 1 `DECISION` · 5 `BLOCKED` ·
119 schedulable (95%) once the implementation-plan Gate lifts (`docs/plans/README.md`; every `FE-D*`/`UI-D*`/`DM-D*`/`SM-D*`
decision behind this plan is `PROPOSED`, per `decisions.md` and `design.md` §2.3/§27).

Model split: 27 **O** · 76 **S** · 29 **H**.

**Done: all 125** — phases 0–8. Phase 7 (`090`–`095`) was executed even though the phase itself stayed *not
scheduled* in `execution.md` §6.7; its issues exist for tracking, not as a readiness signal. What those tasks
actually produced, and where the code and the design now disagree, is
[`design.md` §28](../../design/frontend/design.md#28-as-built-map-2026-09-03).

⚠️ **This list stops at `FE-125`. Tasks `FE-126`–`FE-148` are built and are not registered here** — their plans
are [`126`](126-monitoring-ui-reference-alignment.md) (`126`–`132`),
[`127`](127-hierarchy-drop-tenant-level.md) (`133`–`139`) and
[`128`](128-groups-rbac-ratification.md) (`140`–`147`) and — planned, landing as executed — [`129`](129-filemesh-source-sink-connectors.md) (`149`–`155`); `FE-148` and issue #131 had no plan. (`FE-149` is a *task* number; the `#149` on the `FE-119` row is a *GitHub issue* — they are unrelated.) Per-task detail for
all of them is in [`reports/`](reports/). Treat `reports/` as the register for anything above `125`.

**Ships UX only, wire call blocked:** `043`, `061`, `062`, `095`, `124` (open item 1 — no `dp-remote` apply-ordering
equivalent, `design.md` `FE-D18` clause B).
