# 001 — Solution Manager console: frontend implementation plan

| | |
|---|---|
| **Component** | `frontend/` (the Solution Manager console SPA) |
| **Design** | [`../../design/frontend/design.md`](../../design/frontend/design.md) — the primary baseline; §4 architecture, §7 folders, §8 theme, §9 components, §10 Designer, §11–12 Monitoring, §13 API, §14 routing, §15 authz, §16 errors, §17 perf, §18 a11y, §19 testing, §24 phasing |
| **UI reference** | [`../../design/frontend/reference/`](../../design/frontend/reference/) is the **source of truth for screens and visual styling** (`FE-D30`) — layout, spacing, component structure, navigation, tables, forms, dialogs, hierarchy and the full palette, light and dark. **[Appendix A](#appendix-a--ui-specification)** and **[Appendix B](#appendix-b--visual-token-record)** remain the *durable behavioural and token record* extracted from it. Its authority stops at the desktop shell: it does **not** override `G16` / `FE-D29` and is **not** a layout spec below `lg`. |
| **Requirements** | `REQ-001` `REQ-002` `REQ-003` `REQ-005` `REQ-006` `REQ-007` `REQ-008` `REQ-009` (`REQ-004` explicitly **not** implemented — see §13) |
| **Decisions** | `D7` 🔒 `D1` 🔒 `D9` 🔒 `D10` 🔒 `D13` 🔒 `D14` 🔒 `B/D15` 🔒 · `I1`–`I7` · `FE-D1`–`FE-D29` (`FE-D26` superseded by `FE-D29`) · `UI-D1`–`UI-D23` · `DM-D1`–`DM-D23` · `SM-D14` `SM-D15` `SM-D22` `SM-D26` `SM-D27` `SM-D30` `SM-D31` |
| **Status** | **DRAFT — NOT APPROVED.** See "Gate" below. |
| **Author** | generated 2026-08-19 from the repository, the design baseline and the supplied UI reference bundle |

> **For agentic workers:** REQUIRED SUB-SKILL — use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task, **on a lower-cost model** (`plan-template.md`: writing the plan needs the strongest model; executing an already-written one does not). **Ask the developer which session mode to use before starting.**

**Goal:** Build the Solution Manager console — a schema-driven operations shell that renders Designer, Governance and Monitoring for any solution from a Solution Package, with zero per-solution React code.

**Architecture:** One React SPA on a shared kernel plus feature slices. Server state in TanStack Query, session UI in Zustand, workspace state in the URL, drafts in React Hook Form. Two pure engines — `project()` and `projectSeries()` — hold every view-model semantic and are fixture-tested against their Java counterparts. Three same-origin plane clients sit behind one gateway, and every backend call goes through a typed repository interface so a fixture and a real API are interchangeable.

**Tech Stack:** React · TypeScript `strict` · Vite · MUI · MUI X Data Grid **Community** · Apache ECharts (via `ChartRenderer` only) · TanStack Query · Zustand · React Router · React Hook Form · Vitest · Testing Library · jest-axe · MSW · Playwright.

**Spec:** [`../../design/frontend/design.md`](../../design/frontend/design.md) (the argument), plus [Appendix A](#appendix-a--ui-specification) and [Appendix B](#appendix-b--visual-token-record) of this document (the screens and the tokens). **Executors read all three.**

## Global constraints

Every task's requirements implicitly include these. Values are copied verbatim from the sources named.

| # | Constraint | Source |
|---|---|---|
| G1 | **React + Vite + Material-UI.** No second component library, no second styling runtime, no second charting stack. | `D7` 🔒 |
| G2 | **TypeScript `strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`. No `any` in app code — including tests.** | `FE-D15` |
| G3 | **MUI X Data Grid Community only.** `@mui/x-data-grid-pro`, `@mui/x-data-grid-premium` and `@mui/x-charts-pro` may not enter the lockfile. | `FE-D11` |
| G4 | **`echarts` may be imported by exactly one module:** `features/monitoring/chart/ChartRenderer.tsx`. | `FE-D16` |
| G5 | **The browser talks to one same-origin gateway.** No `VITE_*` variable may name a plane host; the SPA never stores a private DP URL. | `FE-D19` |
| G6 | **Tenant is display-only.** Never a path segment, never a request parameter, never client-authoritative. | `I5`, `FE-D8` |
| G7 | **The browser never receives a resolved secret** — not in a form, a diff, an export, history, a log, or inside a `yaml` blob. `type: yaml` ships no editor in v1. | `I6`, `FE-D23` |
| G8 | **No raw colour outside `src/shared/theme`.** Every hex, `rgb()` and `hsl()` lives in the token layer. | `FE-D5` |
| G9 | **Timestamps are UTC on the wire.** Display via `Intl`, and **every time axis labels its zone**. | `B/D15` 🔒 |
| G10 | **Absence is never zero.** A missing bucket produces no point; an unknown count renders `—`. A gap is a break, never a zero line, never a connect-across. | `UI-D8`, `DM-D16` |
| G11 | **`restart-observed` is never presented as convergence**, and an apply outcome is never derived from an HTTP response. | `SM-D22`, `FE-D18` |
| G12 | **Status is text + icon + colour.** Colour is never the only channel, in any state, in either theme. | `UI-D9`, design §18 |
| G13 | **Unauthorized ⇒ omit. Capability-limited ⇒ disable with a stated reason.** Hiding a control is **not** a security boundary. | `FE-D9`, `D10` 🔒 |
| G14 | **Never retry a `422`; never retry a mutation; never apply an optimistic update to apply or restart.** | `FE-D20` |
| G15 | **No alerting affordance.** Nothing may imply that anyone is notified or paged. | design §20, `DM-D15` |
| G16 | **Responsive: desktop, tablet and mobile.** Content reflows to **320 CSS px** with no two-dimensional scrolling, per WCAG 2.2 **AA** SC 1.4.10. Breakpoints `xs 320 · sm 600 · md 905 · lg 1240 · xl 1560`. Excepted from reflow, per the criterion itself: **data tables** (individual cells still comply) and **interfaces requiring a persistent visible toolbar** — these scroll on one axis inside their own container, never the page body. | `FE-D29`, WCAG 2.2 SC 1.4.10, §16 Q7 |
| G17 | **WCAG 2.2 Level AA** for chrome, forms and navigation; charts additionally require a non-colour channel and a tabular alternative. | design §18 |
| G18 | **No MI development may be required.** Every degradation states the package's own reason; no empty state asks for a change to a managed integration. | `I1` / `HC1` |
| G19 | **Air-gap clean.** No analytics, no beacons, no CDN, no third-party fonts. Self-host everything. | `REQ-003` |
| G20 | **No production build may contain** the auth mock, the dev gallery, MSW, a fixture dataset, a secret, or a plane hostname. | `FE-D14`, `FE-D24` |

---

> ## ⚠️ Gate — read before scheduling any task
>
> [`../README.md`](../README.md) says: *"Do not start a plan when it depends on a `PROPOSED` decision that ratification could still change."* **Every `FE-D*`, `UI-D*`, `DM-D*` and `SM-D*` decision this plan implements is `PROPOSED`**, and [`../../design/DECISIONS.md`](../../design/DECISIONS.md) **open item 19** is recorded as *"blocks the frontend sub-spec."*
>
> This plan is therefore written **ahead of its gate, deliberately**, so that the work is decomposed, traced and costed while ratification proceeds. It is **not** an authorisation to build. Each task carries a **Definition of Ready** class (§17) — `READY`, `FIXTURE`, `BLOCKED`, `DECISION`. **Only `READY` and `FIXTURE` tasks may be scheduled**, and `FIXTURE` tasks must ship behind the seam in §9 so a real contract replaces a fixture without touching a component.
>
> **This plan invents no backend endpoint, no database schema and no business rule.** Every place the design is silent is written as `API CONTRACT — TBD` or `TBD` and listed in §16.

---

## 1. Executive summary

### What this builds

One React SPA — the console — that renders **Designer**, **Governance** and **Monitoring** for any solution from a Solution Package, with **zero per-solution React code** (`REQ-001`, `UI-D14`). The supplied reference was a complete, high-fidelity, state-complete prototype of that console: 12 screens, 5 demo states each, 6 Designer scenarios, 5 Governance scenarios, 8 chart data-states. **Appendices A and B are the behavioural and token specification.** The same prototype is checked in at [`../../design/frontend/reference/`](../../design/frontend/reference/) as a 1280 px visual check; layouts below `lg` are specified by Phase R (`105`–`110`) and `FE-D29`, not by that shell's `min-width: 1280px`. This plan converts that specification into a production codebase on the stack `D7` locks.

### The approach in five sentences

1. **Build the seam before the screens.** Two things must exist before feature work forks: the **shared UI kernel** (Phase 1 — the primitives in §A.1, re-expressed as MUI wrappers) and the **API seam** (Phase 2 — typed clients, error codec, fixture layer). Everything after that parallelises cleanly.
2. **Fixtures are first-class, and are never mistaken for contracts.** The console REST surface is unwritten except the Dynamic Metrics query API (§9). Every other call goes through a named repository interface with an MSW-backed fixture implementation derived from the datasets in §A.13. A fixture module carries a header naming what is invented and what is specified.
3. **Purity is the divergence guard.** `project()` (`UI-D15`) and `projectSeries()` (`DM-D20`) are pure TypeScript functions living in `contracts/`, run against the **same JSON fixtures the Java CI runs**. They are the only place view-model semantics live; components render their output and nothing more.
4. **States are the deliverable, not a polish pass.** The prototype ships loading / empty / partial / error / validation / blocked / diverged / restart / permission states for every screen and states them as rules ("a gap is never a zero line", "restart-observed is never convergence", "hidden is not deleted"). §10's state matrix makes each one a testable acceptance criterion.
5. **Honesty is a functional requirement.** Multiple `must not` rules in the design are behaviours a test can assert: never draw a zero line for a gap, never present `restart-observed` as convergence, never present an apply outcome as success when it is `APPLY_INDETERMINATE`, never colour-only a status, never show an alerting affordance that would imply someone is paged.

### What comes out at each stage

| Stage | Runnable outcome |
|---|---|
| End of Phase 0–2 | An empty, themed, routed, authenticated shell with a fixture backend and a green CI: lint, types, unit, component, a11y, e2e smoke |
| End of Phase 3 | Login → Home → Solutions → Instances → Instance, all five demo states, entirely on fixtures |
| End of Phase 4 | The **walking skeleton** ([`architecture.md`](../../design/architecture.md) §15): one generated Designer screen, save with generation, `409` diff, manual apply rendering `APPLY_INDETERMINATE` |
| End of Phase 6 | Governance + Monitoring: Explore, one dashboard, real ECharts, gaps as gaps |
| End of Phase 8 | Production-hardened: bundle budget, CSP, contrast validated in both themes, e2e journeys, no mock in the production build |

### The single most important finding

**The reference bundle materially changes open item 19.** It shipped `solace-agent-mesh-design-system-…/colors_and_type.css` — the Solace Agent Mesh Design System token file whose *absence* is what open item 19 records — and the console stylesheet built on it opened with:

> *"SOLUTION MANAGER — MUI-structured component layer, painted with Solace tokens. Palette/type come from `_ds` Agent Mesh `colors_and_type.css` (Rubik + Solace scale). Structure & interaction follow Material-UI conventions."*

That is precisely the reconciliation [`design.md`](../../design/frontend/design.md) §2.3 asked for: **`D7`'s MUI structure, painted with the Solace DS tokens** — not a stack reopen. It does **not** close open item 19 by itself: the architect group still has to accept that token set as the authoritative visual reference. Token values are recorded in [Appendix B](#appendix-b--visual-token-record); the prototype is checked in at `docs/design/frontend/reference/` and named the source of truth for screens and visual styling by **`FE-D30`** — which settles *what development works from*, not *whether it is brand-final*. This converts item 19 from *"the artefact does not exist"* to *"accept this artefact"* — a far cheaper decision. See §16 Q1.

---

## 2. Current architecture assessment

### 2.1 What exists today

| Area | State |
|---|---|
| **Application code** | **None.** `frontend/` contains only `README.md`. Greenfield. |
| **Component design** | Complete and reviewed — [`design.md`](../../design/frontend/design.md), 866 lines, independent adversarial review R1 + internal R2 applied (§27) |
| **Component decisions** | `FE-D1`–`FE-D29` in [`../../design/frontend/decisions.md`](../../design/frontend/decisions.md), all `PROPOSED`. `FE-D26` is recorded and **superseded** by `FE-D29` (`I3`) |
| **UI reference** | Desktop prototype checked in at [`../../design/frontend/reference/`](../../design/frontend/reference/) — **source of truth for screens and visual styling** at 1280 px (`FE-D30`), now rejoined with its `_ds/` token bundle so it renders. Behaviour and tokens extracted into Appendices A and B. **No visual reference below `lg`** (R18) |
| **Contracts** | Only the Dynamic Metrics query API is specified to endpoint level (§9). No OpenAPI file exists anywhere. `contracts/` directory **not created**. |
| **Pure engines** | `project()` and `projectSeries()` are **specified but not written** in either language |
| **Shared fixtures** | `UI-D15`/`DM-D20` require a shared JSON fixture suite. **Not written.** |
| **CI** | None. `.github/` holds issue templates only. |
| **Sibling reference repos** | `solace-cache-engine`, `file-mesh-ui`, `pubsubplus-connector-file-common` are **not present on this machine** — the `D7` "harvest File Mesh wrappers" instruction cannot be executed here and is therefore treated as a *pattern* checklist, not a code source (`FE-D4`, design §9) |

### 2.2 What this plan adds

A new `frontend/` Vite application on the `FE-D3` layout, plus a `contracts/` tree for the two pure engines and their shared fixtures. Nothing existing is modified beyond `frontend/README.md` (task 104) — there is no working code to refactor, so §17 of the brief (backward compatibility) is largely **not applicable**; see §12.4.

### 2.3 Conflict register — identified, impact stated, resolution recommended

Per the source-of-truth order. **A lower-priority source never silently overrides a higher one.**

| # | Conflict | Higher source | Lower source | Impact | Recommended resolution | Status |
|---|---|---|---|---|---|---|
| C1 | **MUI vs Tailwind + Radix** | `D7` 🔒 (React+Vite+MUI) | Deleted design handoff (Tailwind v4 + Radix), still present in the delivered ZIP as `design_handoff_solution_manager/` | Whole component layer | **Build MUI.** The reference bundle already resolves this: MUI structure, Solace DS paint (§B.2). Do **not** revive the Tailwind/Radix handoff — it is the superseded, pre-revision prototype. | Resolvable by ratifying item 19 |
| C2 | **Brand tokens blocked** | `FE-D5` (paint blocked on item 19) | the supplied Solace Agent Mesh token file, recorded in §B.1 | Theme task 002/003 | Adopt the DS tokens **labelled as pending architect acceptance**, and gate UX-accept on contrast validation (task 097). Do not hard-code any hex outside the token layer. | **DECISION** — §16 Q1 |
| C3 | **Viewport policy** | `design.md` §18 + WCAG 2.2 **AA** SC 1.4.10 Reflow (320 px, verified at w3.org) | `dynamic-metrics` §18: *desktop only, ≥1280px, an explicit non-goal* | Responsive scope of every screen | **Build responsive to 320 px.** ⚠️ **This conflict was previously resolved the other way, and that resolution was defective:** the plan commits to WCAG 2.2 **AA** (C4), SC 1.4.10 Reflow **is** a AA criterion, and a ≥1280px floor cannot satisfy it. `G16` and `G17` were in direct contradiction. `dynamic-metrics` §18's non-goal is therefore **superseded on the a11y ground it never considered**, not merely outvoted — and `design.md` §8.1's *"filters collapse, grid stacks"* stands. The criterion's own exceptions (data tables, persistent toolbars) carry the hard cases. | **Resolved — `FE-D29`.** Cross-component: needs architect-group sign-off because it supersedes a `dynamic-metrics` non-goal |
| C4 | **WCAG target** | `design.md` §18: **WCAG 2.2 AA** | `dynamic-metrics` §18: WCAG 2.1 AA | a11y acceptance bar | **WCAG 2.2 AA** — the frontend spec owns chrome/forms/navigation a11y and sets the higher bar; 2.2 is a superset. Metrics-specific rules (chart keyboard model, tabular alternative) still bind. | Resolved in-plan |
| C5 | **API path prefixes** | `FE-D19`/`design.md` §13: browser → one gateway origin, `/api/cp` + `/api/dp` | `dynamic-metrics` §5.2: `/api/console/v1/*` for composed reads, `/api/dp/v1/*` for pure DP reads — and flags the third `console` module as needing a decision | Client construction (task 027) | Build **three named clients** (`cpClient`, `dpClient`, `consoleClient`) over **one base-path constant** from `config.json`. All three are same-origin; `FE-D19` is preserved because no plane hostname reaches the browser. The prefix is data, not architecture. | Resolved in-plan; the *module* question stays upstream |
| C6 | **Series cap source** | `DM-D10`: cap advertised via `capabilities.maxSeriesPerWidget`, default 10, range 10–20 | the reference screens hardcode a cap of 20 (§A.10.3) | Explore selector | **Capabilities-driven, fail closed to 10.** The prototype constant is a fixture value, not a spec. | Resolved in-plan |
| C7 | **Dashboard scopes** | `SM-D31`: per-instance + shared only | `DM-D5`: adds `entity`/`tenant` scopes and a `personal` layer — a proposal against a proposal | Dashboard model, RBAC surface | **v1 = `instance` scope, layers `product-default` ∪ `shared`** (design §12). `entity`/`tenant`/`personal` are **not built**. The reference screens agree — exactly two layers (`package-baseline`, `operator`), §A.11.4. | Resolved in-plan; ratification pending |
| C8 | **Escape hatch** | `D1` 🔒: config-driven UI **plus** a custom-UI module | `UI-D21`: narrow to SM-built named screen types, no package-supplied code | Whether a module loader ships | **Ship the generic renderer + composer; create the named-screen registry and leave it empty.** No package-supplied code path in v1 (design §10). | Flagged, not resolved |
| C9 | **`REQ-004` artifact deployment** | `REQ-004` 🔒 LOCKED: *UI drives artifact deployment* | `SM-D2` (proposed retirement); design §2.3 | Whether Designer deploys JARs/images | **Build no artifact-deploy UI.** The reference screens state it on-screen (§A.6): *"An instance is declared here; the artifact is deployed by your own CI/CD."* A locked requirement is not satisfied by inventing a flow the architecture forbids — record it as an open trace gap (§13). | Flagged, not resolved |
| C10 | **`type: yaml` fields** | `I6`: no secret in diffs/exports/history | `UI-D23`: yaml is warn-only, cannot mask inner keys | Whether a YAML editor ships | **Gate `type: yaml` to Phase 2** (`FE-D23`). The reference screens already render it as an explicitly gated control with a stated reason (§A.8.3). Cache declares no yaml field, so v1 loses nothing. | Resolved in-plan |
| C11 | **`dp-remote` apply ordering** | `architecture.md` §13: no generation ordering, idempotency, lease or crash-safe resume for `dp-remote` | `FE-D18` clause B | The apply CTA on the walking skeleton's own locus | **Save-side generation only (clause A). Apply renders `APPLY_INDETERMINATE` by default**, resolved from observed state, operator-initiated retry only. | **BLOCKED** on open item 1 |
| C12 | **`DM-D18` RBAC verbs** | `D10` 🔒 catalog | `DM-D18` proposes 6 verbs; the reference screens mark **2 grantable, 6 proposed, 22 candidate-only** (§A.12.1) | Every metrics/dashboard permission gate | Build the permission model as **data**; ship the two grantable verbs live and render the rest through the same `status` vocabulary the prototype already defines. **Do not gate a v1 feature on an ungrantable verb** — an ungrantable verb would deny everyone. | **DECISION** — §16 Q4 |
| C13 | **Where the visual reference lives** | repo conventions (design is versioned and reviewable) | prototype originally supplied outside the tree | Pixel drift vs this plan's prose | **Appendices A and B are the behavioural/token record.** The prototype is checked in at `docs/design/frontend/reference/` as a **1280 px check**, not as a substitute for `FE-D29`. Below `lg`, Phase R + tests are the spec (R18, Q2) | Resolved in-plan |

### 2.4 Requirement quality findings (brief §2)

| Finding | Detail |
|---|---|
| **Missing** | No `REQ` covers **alerting** (design forbids implying it exists), **log streaming** (`UI-D10` behaviour has no requirement), **the first-admin bootstrap** (the reference screens call it *"the single largest hole"*), or **package upload/signature verification** (`UI-D19` behaviour, no `REQ`). |
| **Missing** | `dynamic-metrics` §9.3 flags it itself: *"reusable across multiple products has no requirement in `docs/requirements/`."* This plan therefore does **not** structure `features/monitoring` as a publishable package — only as a clean barrel export. |
| **Ambiguous** | `REQ-007` (six months of metrics) is `ACCEPTED` but ❌ **unproven** — the UI must render advertised retention as advertised and never as assured. Implemented as a copy rule in task 081. |
| **Conflicting** | `REQ-004` — see C9. |
| **Duplicate** | None found. |

---

## 3. UI screen inventory

Twelve screens plus a state harness. `P*` codes are the reference's own, retained so a task, a screen and an appendix section stay mappable. Each screen's full specification is in **[Appendix A](#appendix-a--ui-specification)**; the `Spec` column links to it. Every screen carried a **Demo state** control cycling `Populated · Loading · Empty/first-run · Partial data · Error` — that control is a **reference affordance and must not ship** (§A.14, task 036).

| # | Screen | Spec | Route | Purpose | Ships in |
|---|---|---|---|---|---|
| P1 | **Login** | [§A.3](#a3--login) | `/login` | Local session. Distinguishes *session-expired* from *idle-timeout* arrival. Leaves room for one SSO button without redesign. No signup, no password reset. | Phase 3 |
| P2 | **Home** | [§A.4](#a4--home) | `/` | *"What needs your attention right now"* — attention feed + 4 stat tiles. First-run = "load a Solution Package". | Phase 3 |
| P3 | **Solutions catalog** | [§A.5](#a5--solutions-catalog) | `/solutions` | Package cards: version, `appliesTo` range, provenance/signature, authoring review meter, components, entitlement, dev-mode (unsigned) treatment. Super-admin **Load a package** dialog. | Phase 3 |
| P4 | **Instance inventory** | [§A.6](#a6--instance-inventory) | `/solutions/:solutionId/instances` | Data Grid. `driver` and `control_locus` are **row data, never a UI branch** (`REQ-005`, `REQ-008`). Health(5) · not-found(5) · version(4) · config(6) vocabularies. Expandable replica detail. Selection in the URL. Bulk restart. | Phase 3 |
| P5 | **Designer** | [§A.8](#a8--designer) | `/solutions/:s/instances/:i/designer` | The renderer. 6 scenarios: *Editable · Validation · Blocked · Restart · Diverged · Composer*. Section nav, field search, advanced disclosure, validation summary, save bar, apply/restart, rollout card, diff, conflict, override-reason. | Phase 4 |
| P5e | **Composer canvas** | [§A.8.18](#a8--designer) | same route, view toggle | Multi-component solutions (File Mesh): palette · draggable nodes · derived link · inspector form. Layout is presentation, excluded from the config hash (`UI-D20`). | Phase 4 (gated) |
| P6 | **Governance** | [§A.9](#a9--governance) | `/solutions/:s/instances/:i/governance` | Health hero (5 rollups) · replica cards · 5-rung config-state ladder ending at an honestly-unreached `effective-verified` · log viewer with DEBUG confirmation and no-endpoint degradation. | Phase 5 |
| P7 | **Monitoring landing** | [§A.11.1](#a11--dashboard) | `/solutions/:s/instances/:i/monitoring` | Renders the package's default dashboard directly. Three readings are distinguished on-screen; a dashboards **index** is not assumed (no list route in the contract). | Phase 6 |
| P8 | **Explore** | [§A.10](#a10--explore-and-charts) | `…/monitoring/explore` | Five gated steps: metric (single-select) → instances (to a projected-series cap) → seriesBy → aggregation → groupBy. Marks line/gauge/stat. 8 data-states. Typed `422`s, never retried. Save as widget. | Phase 6 |
| P9 | **Dashboard** | [§A.11](#a11--dashboard) | `…/monitoring/dashboards/:dashboardId` | 12-column grid, dashboard-authoritative range/refresh/resolution, layer filter and badges, hide-not-delete, edit-layout reorder, newer-schema read-only, off-screen widgets don't load. | Phase 6 |
| P10 | **Targets** | [§A.12.6](#a12--admin-and-targets) | `/targets` | Later increment. Read-only model preview; credential entry deliberately absent. **Not in v1 nav.** | Phase 7 |
| P11/P13/P14 | **Admin** | [§A.12](#a12--admin-and-targets) | `/admin/:tab` | Roles · Assignments · Access check · Users. Permission catalog rendered from `status` data. First-admin bootstrap flagged as invention. **Not in v1 nav.** | Phase 7 |
| P12 | **Instance context** | [§A.7](#a7--instance-context) | wraps P5/P6/P7–P9 | Shared instance header: name, env, health rollup, drift badge, persistent mutation outcome with correlation id; Designer/Governance/Monitoring tabs. | Phase 3 |

### 3.1 Behaviour the screens imply that the requirements do **not** cover

Flagged rather than invented. Each is listed in §16.

| Screen | Behaviour | Nothing backs it |
|---|---|---|
| P2 Home | The **attention feed** (`ATTENTION[]`: severity, icon, title, detail, deep link) | No API, no severity vocabulary, no ordering rule, no dedupe rule |
| P2 Home | Stat tiles "Solutions managed / Instances declared / Need attention now / Config staged" | No aggregate endpoint |
| P3 Solutions | Package **upload**, **signature verification**, **dev-mode (unsigned) admission** | `UI-D19` says Solace authors and the customer loads; no upload API, no signature-result shape |
| P3 Solutions | **Authoring review meter** (`reviewedRows / totalRows`) | `UI-D17` makes provenance a column property; no package field carries these counts |
| P4 Inventory | **Declare instance** | No API; entitlement check at declaration is `SM-D13` but has no wire shape |
| P4 Inventory | **Bulk restart across instances** | Multi-instance apply has no protocol; single-instance apply is itself `BLOCKED` (C11) |
| P5 Designer | `409` **resolution controls** — "keep mine", "take theirs", "per-field merge"; no actor or timestamp on the conflict | The prototype itself renders these **disabled** with *"not specified"* — copy that treatment exactly |
| P5 Designer | Rollout table columns `renderedConfigVersion`, `restartObserved` | The prototype labels both **invented column names**; the contract names them in prose with no row schema |
| P6 Governance | Log **streaming**, component selector, level change, audit record | `UI-D10` describes `/actuator/loggers` + `/actuator/logfile` at the *MI*; the **console↔backend** log route does not exist |
| P8 Explore | `firstSeen` and a **suggested type** on a catalog entry | The prototype flags this as a required contract addition; `SM-D30` forbids inferring a type |
| P8 Explore | `partial.unavailable[]` item shape and its **reason vocabulary** | Flagged in-prototype as invented |
| P9 Dashboard | The **entire dashboard document**: envelope, hidden-default representation, dashboard `range`/`refresh`, whether `{x,y,w,h,order}` live on the widget or a placement entry, a list route, a reset payload | Flagged in-prototype: *"The dashboard document has no shape in the repo"* |
| P13 Admin | Local-user CRUD, role builder, assignment scope picker, audit log, 403 page content | The reference screens name all five as unspecified; the **first-admin bootstrap** is called the load-bearing hole (§A.12) |

---

## 4. Implementation architecture

Follows [`design.md`](../../design/frontend/design.md) §4 and §7 exactly. Nothing here is new architecture.

### 4.1 Tree

```
contracts/                            # shared with backend CI — NOT a frontend folder
  view-model/
    project.ts                        # UI-D15 pure engine (TypeScript half)
    types.ts
  metrics/
    projectSeries.ts                  # DM-D20 pure engine (TypeScript half)
    buildQuery.ts
    types.ts
  fixtures/
    view-model/*.json                 # (package, selection, draft, observed) -> expected viewModel
    metrics/*.json                    # (response, widgetConfig) -> expected ChartModel
    README.md                         # pins JSON number formatting + key ordering

frontend/
  index.html
  vite.config.ts  tsconfig.json  eslint.config.js  vitest.config.ts  playwright.config.ts
  public/
    config.json                       # runtime config, replaced at deploy (FE-D14)
  src/
    app/
      main.tsx  App.tsx
      providers.tsx                   # Theme > Query > Auth > Router, in that order
      router.tsx  routes.ts
      boot/                           # config.json loader, build info
      errors/                         # RouteErrorBoundary, WidgetErrorBoundary
    shared/
      api/                            # http core, cpClient/dpClient/consoleClient, error codec, download
      auth/                           # adapter iface, local session, dev mock, idle lock, guards
      authz/                          # permission set, usePermissions, Can, omit-vs-disable
      theme/                          # tokens.css, createSmTheme, colorMode store, echartsTheme
      ui/                             # THE design system — see Phase 1
      state/                          # zustand stores (ui, capabilities fallback)
      format/                         # UTC/Intl time, units, numbers
      i18n/                           # messages.en.ts, ICU helpers
      testdoubles/                    # MSW handlers + fixture datasets
      vocab/                          # HEALTH / NOTFOUND / VERSION / CONFIG / DRIVER / LOCUS
    features/
      inventory/                      # solutions catalog, instances grid, instance context
      designer/                       # control registry, form protocol, apply/restart, composer
      governance/                     # health, config ladder, logs
      monitoring/                     # MetricsProvider, Explore, Dashboard, ChartRenderer, marks
      admin/                          # roles, assignments, access check, users  (Phase 7)
      targets/                        # read-only preview                        (Phase 7)
      home/                           # attention feed, first run
    generated/                        # OpenAPI output — CI-regenerated, never hand-edited
```

**Import rule (CI-enforced, task 009):** `features/x` may import `shared/*`, `contracts/*` and another feature's **public barrel only**. `app/*` may import features. `shared/*` may import nothing from `features/*`. `contracts/*` imports neither React nor anything else in the tree.

### 4.2 The four state lifetimes (`FE-D1`, `FE-D2`)

| Lifetime | Owner | Holds |
|---|---|---|
| Server | **TanStack Query** | packages, instances, view model, health, catalogs, capabilities, series, dashboards |
| Session/global UI | **Zustand** | colour mode, sidebar collapsed, density, capabilities fail-closed fallback, dismissed banners |
| URL | **React Router** | solution, instance, category, dashboard id, range preset, instance selection, explore `?draft=` **id** |
| Ephemeral | **React / RHF** | Designer draft values, dialog open, chart zoom, expanded sections, advanced toggle |

**Not negotiable:** selected instance ids live in the **URL**, never only in Zustand (`FE-D6`) — the reference screens already do this (`?sel=a,b`, §A.6.4). Explore **documents** never live in the URL (`FE-D21`); only the id does.

### 4.3 The three seams that make the SPA replaceable-under

1. **Plane clients** — `cpClient` / `dpClient` / `consoleClient` over one same-origin base path (`FE-D19`, C5). Composition happens in hooks, never in leaves.
2. **Repository interfaces** — every feature talks to a typed repository (`InstanceRepository`, `ConfigRepository`, `MetricsRepository`, …). A fixture implementation and a real implementation satisfy the same interface; swapping is a provider change, not a component change (§9).
3. **Pure engines** — `project()` / `projectSeries()`. Components are dumb consumers; every semantic rule the design states lives in the engine and is fixture-tested against Java.

### 4.4 Rules that are enforced by tooling, not by review

| Rule | Enforcement | Task |
|---|---|---|
| No `any` in app code | `tsc --strict` + `@typescript-eslint/no-explicit-any` (error) | 001 |
| No raw hex outside `shared/theme` | custom ESLint rule / `stylelint` | 009 |
| No `@mui/x-*-pro` / `-premium` import | CI grep + `no-restricted-imports` | 009 |
| Leaves do not `fetch` | `no-restricted-imports` on `shared/api` from `**/ui/**` | 009 |
| `echarts` imported only by `ChartRenderer` | `no-restricted-imports` allowlist (`FE-D16`) | 009 |
| Cross-feature deep imports | `eslint-plugin-boundaries` | 009 |
| No auth mock in a production build | build-time assertion + CI grep (`FE-D24`) | 101 |
| No secret value in a log call | ESLint rule on the logger's argument shape (`I6`, `FE-D13`) | 102 |

---

## 5. Implementation phases

Nine phases plus a **cross-cutting Phase R** (`105`–`110`). Each row's **Gate** must pass before the next phase's *dependent* work starts; independent workstreams (§8) overlap. Phase R tasks execute *inside* the phase named on the task (`105` in Phase 0, `106`/`107` in Phase 3, `108` in Phase 4, `109` in Phase 6, `110` in Phase 8) — they are not a tenth sequential phase.

### Phase 0 — Foundation · tasks 001–010 · **plus `105` (Phase R)**

- **Objective** A themed, routed, typed, tested empty shell with CI. No feature code.
- **Requirements** none directly; enables all.
- **Design** §5 stack, §7 folders, §8 theme, §21 deployment.
- **Depends on** C2 (token acceptance) for *paint*, not for *structure* — build with tokens labelled provisional.
- **Deliverables** Vite app; strict TS; MUI theme (light+dark) from Solace DS tokens; router with lazy routes; `config.json` boot; error boundaries; i18n catalog; Vitest/RTL/axe/MSW/Playwright wired; guardrail lint rules; dev-only component gallery.
- **Testing** CI runs lint + typecheck + unit + one e2e smoke and fails on any.
- **DoD** `npm run build` produces a static bundle; `npm test`, `npm run lint`, `npm run typecheck`, `npm run e2e` all green in CI; both themes render the gallery; zero hard-coded hex outside `shared/theme`.

### Phase 1 — Shared UI kernel · tasks 011–025

- **Objective** The design system (`FE-D4`): every primitive in §A.1, as an MUI wrapper. This layer **is** the common-components spec (design §9).
- **Requirements** `REQ-001` (one component set serves every solution), `REQ-006` (states carry text+icon, not colour alone).
- **Design** §8.2, §9. **UI** §A.1 (component kit) · §B (visual tokens).
- **Depends on** Phase 0.
- **Deliverables** 15 components + the state-vocabulary module, each in the gallery, each with component tests and an axe test.
- **Testing** Component tests for keyboard, focus trap, aria wiring, disabled-with-reason, secret masking. Axe clean in both themes.
- **DoD** Every gallery entry passes axe in light and dark; no feature code exists yet that duplicates a primitive.

### Phase 2 — API seam, auth, permissions · tasks 026–035

- **Objective** Everything a feature needs to fetch, fail, authenticate and authorise — with a fixture backend that is honest about being one.
- **Requirements** `REQ-003` (tenant never client-authoritative; no phone-home), `REQ-006` (deny by default).
- **Design** §13, §15, §16. **Decisions** `FE-D7` `FE-D8` `FE-D9` `FE-D10` `FE-D13` `FE-D19` `FE-D20` `FE-D22` `FE-D24`, `I5` `I6`.
- **Depends on** Phase 0. Overlaps Phase 1.
- **Deliverables** HTTP core; three clients; typed error codec; QueryClient defaults; auth adapter + dev mock; idle lock; permission model; MSW fixture layer; contract-type seam; streaming download.
- **Testing** Unit tests for the retry predicate (never `422`), abort-on-supersede, unknown-field tolerance, correlation-id precedence, idle timer, omit-vs-disable.
- **DoD** A component can be written against a repository interface with no knowledge of whether a fixture or a server answers. No `VITE_*` variable names a plane host.

### Phase 3 — Shell, identity, inventory · tasks 036–045 · **plus `106`, `107` (Phase R)**

- **Objective** Everything up to and including "I have found the instance I care about."
- **Requirements** `REQ-001` `REQ-002` `REQ-005` `REQ-008`. **Design** §9, §14. **UI** P1 P2 P3 P4 P12.
- **Depends on** Phases 1 + 2.
- **Deliverables** AppShell; Login; Home; Solutions; package load dialog; instances grid; URL-encoded selection; bulk-restart confirm; InstanceHeader + tabs; 403.
- **Testing** Route-guard tests, URL round-trip tests, all five demo states per screen as component tests, axe per screen.
- **DoD** Every screen renders loading / empty / populated / partial / error from fixtures; `driver` and `locus` never branch a component; the demo-state control does **not** exist in the build.

### Phase 4 — Designer · tasks 046–063 · **plus `108` (Phase R)**

- **Objective** The **walking skeleton**: one generated Designer screen for Cache, save with generation, `409` → diff, manual apply rendering `APPLY_INDETERMINATE`.
- **Requirements** `REQ-001` `REQ-009`. **Design** §10, §10.1. **Decisions** `UI-D2`–`UI-D4` `UI-D12` `UI-D15` `UI-D16` `UI-D20` `UI-D22` `UI-D23`, `FE-D17` `FE-D18` `FE-D22` `FE-D23`, `SM-D15` `SM-D22`.
- **Depends on** Phase 3 + `project()` (task 046).
- **Deliverables** view-model consumption; control registry; field/section rendering; advanced disclosure; search; validation tiers; save/apply/restart; version states; rollout; divergence; composer.
- **Walking skeleton (Gate D)** is an **e2e of the UX contract against fixtures**: save carries a generation; apply renders `APPLY_INDETERMINATE` and must not claim success from HTTP. **Production `dp-remote` apply correctness remains blocked on open item 1** (R1). Fixture-green Gate D does not close R1.
- **Testing** The shared fixture suite is the primary gate. Component tests assert: an inactive branch never enters the save payload; a secret is never in a diff cell; `restart-observed` is never labelled convergence; apply renders indeterminate.
- **DoD** e2e: login → open instance → edit → save (generation on the request) → apply → `APPLY_INDETERMINATE` rendered, **not** success and **not** failure.

### Phase 5 — Governance · tasks 064–067

- **Objective** *"Is the endpoint up?"* — and nothing more. No synthetic score.
- **Requirements** `REQ-009`. **Design** design §9. **Decisions** `UI-D9` `UI-D10`, `SM-D22`. **UI** P6.
- **Depends on** Phase 3.
- **Deliverables** health hero + replica cards; 5-rung config ladder; log viewer with privileged DEBUG confirm and no-endpoint degradation.
- **Testing** `NOT_APPLICABLE` never renders red; a standby is visually distinct from healthy-and-working; the ladder never marks `effective-verified` reached.
- **DoD** All five governance scenarios render; log content is never written to any store, cache or log.

### Phase 6 — Monitoring · tasks 068–089 · **plus `109` (Phase R)**

- **Objective** Explore and one instance-scoped dashboard, on real ECharts, with every state from `dynamic-metrics` §11.2.
- **Requirements** `REQ-007` (advertised, never assured). **Design** §11, §12, §17. **Decisions** `DM-D1`–`DM-D23`, `FE-D16` `FE-D20` `FE-D21`. **UI** P7 P8 P9.
- **Depends on** Phases 1–3 + `projectSeries()` (task 071).
- **Deliverables** capabilities (fail-closed); catalogs; buildQuery + canonical hash; projectSeries; ChartRenderer; gaps/coverage/thresholds; tooltip + legend; chart a11y; stat/gauge marks; Explore; save widget; dashboard + toolbar + grid + layers; CSV export; first run.
- **Testing** Fixture suite (gaps, counter-rate, replica aggregation, migration). Assert **no zero-fill, no connect-across**, `422` never retried, refresh dims never blanks, off-screen widgets do not query.
- **DoD** A gap is a break in every rendering path; a preset is never labelled "6 months" as guaranteed; a widget error does not take down the dashboard.

### Phase 7 — Admin & Targets · tasks 090–095 · **gated**

- **Objective** Ship the RBAC *model* screens without inventing an unratified verb catalog or an unspecified identity flow.
- **Requirements** `REQ-006`. **Decisions** `D10` 🔒, `DM-D18` (proposed), `D13` 🔒 (deferred). **UI** P10 P11 P13 P14.
- **Depends on** Phase 3 + §16 Q4 (catalog) and Q5 (bootstrap).
- **Deliverables** admin shell; roles + editor; assignments + scope tree; access check; users; targets preview. Nav stays hidden (`design.md` §14) until the routes are real.
- **DoD** Every unratified verb renders through the `status` vocabulary, never as if grantable. The first-admin bootstrap ships **only** after Q5.

### Phase 8 — Production hardening · tasks 096–104 · **plus `110` (Phase R)**

- **Objective** Make the production concerns testable rather than aspirational.
- **Deliverables** code-splitting + bundle budget; palette/contrast CI in both themes; automated a11y sweep; e2e journeys; CSP + air-gap verification; production build assertions; observability + secret-leak lint; performance verification; docs + plan review.
- **DoD** §18's production-readiness gate is answered with evidence, not assertion.

---

## 6. Detailed task breakdown

**Reading a task.** `Refs` cites requirements · decisions · design section · UI appendix section. `Deps` lists prerequisite task IDs. `Ready` is the Definition-of-Ready class from §17. **The global Definition of Done in §17.2 applies to every task**, and **§6.0.8 gives the exact verification command for every task class**; a task's `Acceptance` lists what is additional and specific.

### 6.0 · Interface contracts — write these first

> **Why this subsection exists.** §8 rule 1 says *"publish the interface before the implementation"* — this is that publication. Streams B, C and D each land a **types-only PR** carrying their block below **before** any implementation, so stream E can code against real signatures rather than prose. Every name here is binding: a task that says "the payload is the rendered set from `project()`" means `renderedFields()` as declared in §6.0.2.
>
> **Nothing here is a backend contract.** These are the *frontend's* types. Where the wire shape is unknown, the type carries a `// CONTRACT: TBD` marker and the unknown is confined to the adapter that produces it.

#### 6.0.1 Error, result and outcome types — `src/shared/api/types.ts`

```ts
/** Codes SPECIFIED by dynamic-metrics §5.8. */
export type SpecifiedErrorCode =
  | 'SERIES_CAP_EXCEEDED' | 'AGGREGATION_NOT_APPLICABLE'
  | 'GROUP_BY_REQUIRES_AGGREGATION' | 'STAT_REQUIRES_AGGREGATION' | 'METRIC_UNTYPED';

/** Codes NAMED in design §13/§16 but with no body shape anywhere. CONTRACT: TBD (Q9). */
export type TbdErrorCode =
  | 'CONFIG_DIVERGED' | 'APPLY_INDETERMINATE' | 'VERSION_DRIFT' | 'VERSION_UNKNOWN' | 'CONFIG_CONFLICT';

export type ErrorCode = SpecifiedErrorCode | TbdErrorCode;

export class ApiError extends Error {
  readonly status: number;
  readonly code?: ErrorCode | string;   // an unknown code is surfaced, never swallowed
  readonly correlationId?: string;      // FE-D13: the SERVER's id, always authoritative
  readonly details?: unknown;           // CONTRACT: TBD — the 422/409 body shape (Q9)
}

/** G14. The ONLY retry predicate in the app. */
export const shouldRetry = (failureCount: number, error: unknown): boolean =>
  error instanceof ApiError && error.status >= 500 && error.status !== 501 && failureCount < 3;
```

#### 6.0.2 View-model engine — `contracts/view-model/types.ts`

```ts
export type ControlKind =
  | 'text' | 'number' | 'select' | 'switch' | 'duration'
  | 'secret' | 'string-list' | 'key-value' | 'table' | 'yaml';   // CLOSED (UI-D14)

export type ActionKind = 'test-connection' | 'browse-remote-path' | 'refresh-catalog';  // CLOSED
export type OptionsSourceEntity = 'broker' | 'environment' | 'connection' | 'instance' | 'metric';  // CLOSED

export type FieldValue =
  | { kind: 'value'; value: unknown; source: 'default' | 'draft' | 'observed' }
  | { kind: 'unset' }
  | { kind: 'secretRef'; ref: string }      // a REFERENCE. never a value (G7)
  | { kind: 'secretUnset' };

export interface Validation { severity: 'block' | 'warn'; code: string; message: string }

export interface VmField {
  uid: string;                 // stable identity: `${instanceKey}::${section}::${key}`
  instanceKey: string;
  key: string;
  renderedPath: string | null; // null => a `virtual` field. RENDERS IDENTICALLY (§A.8.4)
  controlKind: ControlKind;
  value: FieldValue;
  options?: readonly string[];
  optionsSource?: { kind: 'sm-catalog'; entity: OptionsSourceEntity };
  section: string;
  visible: boolean;            // computed by project() from `when` over defaults-applied values
  enabled: boolean;
  advanced: boolean;
  masked: boolean;
  discriminator: boolean;
  label: string;
  helperText?: string;
  validations: readonly Validation[];
}

export interface VmSection { title: string; order: number; allAdvanced: boolean }
export interface VmAction {
  key: string; kind: ActionKind; label: string;
  inputs: readonly string[];   // field KEYS. the browser sends references, never values (G7)
  visible: boolean; enabled: boolean;
}
export interface VmComponent {
  instanceKey: string; type: string; role: 'source' | 'sink' | 'none';
  sections: readonly VmSection[]; fields: readonly VmField[]; actions: readonly VmAction[];
}

export type VersionState = 'OK' | 'VERSION_DRIFT' | 'VERSION_UNKNOWN' | 'VERSION_UNVERIFIED';
export type HealthRollup = 'GREEN' | 'RED' | 'RESTART_EXPECTED' | 'INACTIVE_REPLICA' | 'NOT_APPLICABLE';
export type ConfigState = 'in_sync' | 'staged' | 'rendered' | 'diverged' | 'drift' | 'na';

export interface VmStatus {
  version: {
    state: VersionState; declared: string; observed?: string;
    editingBlocked: boolean;   // TRUE for DRIFT/UNKNOWN, FALSE for UNVERIFIED (UI-D12)
    reason?: string;           // REQUIRED whenever editingBlocked is true
  };
  healthRollup: HealthRollup;
  healthAvailability: 'declared' | 'not-declared';
  configState: ConfigState;
}

export interface ViewModel {
  solution: string; packageVersion: string | number; single: boolean;
  components: readonly VmComponent[];
  status: VmStatus;
  requiresUniformConfig: boolean;   // true => rolling-* strategies SUPPRESSED (§A.8.14)
  healthMappingDeclared: boolean;   // false => rolling-health-gated has nothing to read
}

export type DraftValues = Readonly<Record<string, unknown>>;   // keyed by VmField.uid

/** UI-D15. Pure: no React, no DOM, no HTTP, no clock, no auth. */
export function project(
  pkg: SolutionPackage, selection: ComponentSelection,
  draft: DraftValues, observed: ObservedState,
): ViewModel;

/**
 * FE-D17. THE SAVE PAYLOAD. Derived from the view model's VISIBLE fields only —
 * never from RHF's getValues(), which still holds inactive branches.
 */
export function renderedFields(vm: ViewModel, draft: DraftValues): Readonly<Record<string, unknown>>;
```

#### 6.0.3 Metrics engine — `contracts/metrics/types.ts`

```ts
export type Mark = 'line' | 'gauge' | 'single-stat';                 // CLOSED (DM-D12)
export type MetricType = 'counter' | 'gauge' | 'histogram';          // histogram unsupported in v1
export type SeriesBy = 'instance' | 'replica';
export type AcrossInstances = 'none' | 'sum' | 'avg' | 'min' | 'max';

export interface QueryRequest {
  queryVersion: 1;
  metric: string;                                   // EXACTLY ONE (DM-D23)
  selector: { entityType: string; instanceIds: readonly string[]; labels: Readonly<Record<string,string>> };
  range: { from: string; to: string };              // absolute ISO-8601 UTC, resolved at issue time
  resolution: 'auto' | 'raw';                       // no free bucket size (SM-D31)
  seriesBy: SeriesBy;
  aggregation: { acrossReplicas: 'auto' | AcrossInstances; acrossInstances: AcrossInstances };
  groupBy?: readonly string[];                      // composes AFTER instance aggregation only
}

/** [timestamp, value, sampleCount, expectedCount]. A bucket with no samples emits NO tuple (G10). */
export type Point = readonly [ts: string, value: number, sampleCount: number, expectedCount: number];

export type SeriesState =
  | 'ok' | 'inactive-replica' | 'pending-config-change' | 'type-suspect' | 'unverified-durability';

export interface QueryResponse {
  queryVersion: number;
  metric: { name: string; metricType: MetricType | null; unit: string | null };
  render: { transform: 'none' | 'rate' | 'delta'; rateSeconds?: number };   // SERVER-decided
  resolution: { requested: 'auto' | 'raw'; served: string; bucketSeconds: number };
  coverage: { requestedFrom: string; servedFrom: string; truncatedByRetention: boolean };
  gapThresholdSeconds: number;                       // SERVER-declared. the client never re-derives it
  series: readonly {
    seriesRef: { instanceId: string; placementId: string | null; sourceKey: string | null };
    label: string;
    replicaComposition: { total: number; inactive: number };
    state: SeriesState;
    points: readonly Point[];
    gaps: readonly { from: string; to: string; reason: string }[];
  }[];
  partial: { degraded: boolean; unavailable: readonly { instanceId: string; label: string; reason: string }[] };
  // ^ CONTRACT: TBD — item shape and reason vocabulary are INVENTED (Q9)
}

export interface ChartSeriesModel {
  key: string; label: string; color: string;
  dash: 'solid' | 'dashed';                 // the SECOND channel past palette size (G12)
  state: SeriesState;
  segments: readonly (readonly Point[])[];  // one per unbroken run. gaps are segment boundaries
  lowCoverage: readonly string[];           // timestamps rendered as hollow markers
}
export interface ChartModel {
  series: readonly ChartSeriesModel[];
  yAxis: { label: string; unit: string | null; logAllowed: boolean; logDisabledReason?: string };
  thresholds: readonly { value: number; label: string; severity: string; dash: 'solid'|'dashed' }[];
  annotations: readonly { ts: string; label: string }[];
  coverageBand?: { from: string; to: string };
  servedTier: string;
}

export function buildQuery(w: WidgetConfig, r: TimeRange, c: Capabilities): QueryRequest;
export function canonicalQueryHash(q: QueryRequest): string;   // key order normalised, ids sorted
export function projectSeries(res: QueryResponse, w: WidgetConfig, theme: ChartTheme): ChartModel;
```

#### 6.0.4 Apply outcome — the state machine that makes success unrepresentable

```ts
/**
 * FE-D18 clause B is BLOCKED (open item 1). This type encodes that:
 * there is NO variant meaning "succeeded". A dp-remote apply terminates in
 * INDETERMINATE and is resolved from OBSERVED STATE by an operator (G11).
 */
export type ApplyOutcome =
  | { kind: 'NOT_STARTED' }
  | { kind: 'IN_FLIGHT'; startedAt: string }
  | { kind: 'INDETERMINATE'; reason: string; observedAt: string; replicas: readonly ReplicaObservation[] }
  | { kind: 'CONFIRMED_BY_OPERATOR'; confirmedAt: string; replicas: readonly ReplicaObservation[] }
  | { kind: 'DIVERGED'; replicas: readonly ReplicaObservation[] };

export interface ReplicaObservation {
  replicaId: string;
  renderedConfigVersion: string;   // CONTRACT: TBD — INVENTED column name (Q12)
  restartObserved: boolean;        // CONTRACT: TBD — INVENTED column name (Q12)
  health: HealthRollup;
}

/** The ONLY transition into a terminal non-diverged state. Requires an operator act. */
export function confirmActual(o: ApplyOutcome, at: string): Extract<ApplyOutcome, { kind: 'CONFIRMED_BY_OPERATOR' }>;
```

**Why this earns its place:** a reviewer cannot merge a component that claims an apply succeeded, because no such value exists to render. The design's hardest rule becomes a type error rather than a code-review catch.

#### 6.0.5 Repository interfaces — `src/features/*/…Repository.ts`

Twelve seams. **Each has exactly one fixture implementation and one HTTP implementation; swapping is a provider change.** A `CONTRACT: TBD` marker names what is unknown, so the rework surface is greppable and provably one file per seam.

```ts
// ---- SPECIFIED (dynamic-metrics §5.4–5.8). Implement against the real shape today.
export interface MetricsRepository {
  capabilities(s?: AbortSignal): Promise<Capabilities>;
  entityTypes(s?: AbortSignal): Promise<EntityType[]>;
  instances(entityType: string, q: InstanceQuery, s?: AbortSignal): Promise<Paged<MetricInstance>>;
  metricCatalog(q: CatalogQuery, s?: AbortSignal): Promise<CatalogEntry[]>;
  labelValues(labelKey: string, q: LabelQuery, s?: AbortSignal): Promise<string[]>;
  queryRange(req: QueryRequest, s?: AbortSignal): Promise<QueryResponse>;
  queryInstant(req: QueryRequest, s?: AbortSignal): Promise<QueryResponse>;
  exportRangeUrl(req: QueryRequest): string;              // streamed via navigation, never buffered
  declareMetricType(name: string, t: MetricType, unit: string): Promise<CatalogEntry>;  // CONTRACT: TBD (Q14)
}

// ---- CONTRACT: TBD. Shapes below are the FRONTEND's requirement, not a backend promise.
export interface AuthRepository {                          // CONTRACT: TBD (Q3 — CSRF)
  login(c: Credentials): Promise<Identity>;
  identity(): Promise<Identity>;                           // returns the tenant DISPLAY string (G6)
  logout(): Promise<void>;
}
export interface HomeRepository {                          // CONTRACT: TBD (Q10) — whole endpoint
  summary(): Promise<HomeSummary>;                         // counts must be able to express UNKNOWN (G10)
  attention(): Promise<AttentionItem[]>;                   // severity vocabulary TBD
}
export interface SolutionsRepository {                     // CONTRACT: TBD (Q10)
  list(): Promise<SolutionPackageSummary[]>;               // reviewedRows/totalRows have NO schema field
  loadPackage(f: File): Promise<PackageLoadResult>;        // signature-result shape TBD
}
export interface InstancesRepository {                     // CONTRACT: TBD
  list(solutionId: string, q: InventoryQuery): Promise<Paged<InstanceRow>>;
  get(instanceId: string): Promise<InstanceRow>;
  // declare(): intentionally ABSENT — no API, no field set. Do not invent one (Q10)
}
export interface ConfigRepository {                        // CONTRACT: TBD (Q8, Q9)
  /** Q19 RESOLVED (FE-122, frontend side): the server returns the PACKAGE +
   *  values and the browser projects. The wire ENCODING is still the backend
   *  sub-spec's; the shape below is what the client needs either way. */
  loadDesigner(instanceId: string): Promise<DesignerLoad>;
  save(instanceId: string, body: SaveConfigBody): Promise<SaveConfigResult>;  // 409 => ApiError
  history(instanceId: string): Promise<ConfigHistoryEntry[]>;
  setSecret(instanceId: string, fieldKey: string, intent: SecretIntent): Promise<{ ref: string }>;
  acceptRuntime(instanceId: string, toVersion: string, renames: RenameMap): Promise<VersionDiff>;
  restoreRuntime(instanceId: string): Promise<void>;
}
export interface SaveConfigBody {
  fields: Readonly<Record<string, unknown>>;   // renderedFields(). NEVER getValues()
  generation: string;                          // CONTRACT: TBD — the FIELD NAME only (Q8, cheap)
  overrideReason?: string;                     // required when semantic warnings were overridden
}
export interface ApplyRepository {                         // BLOCKED — open item 1
  stage(instanceId: string, strategy: RestartStrategy): Promise<ApplyOutcome>;  // never returns success
  observe(instanceId: string): Promise<ReplicaObservation[]>;                   // the ONLY truth source
  rollback(instanceId: string, toGeneration: string): Promise<ApplyOutcome>;
}
export interface ActionsRepository {                       // CONTRACT: TBD
  run(instanceId: string, actionKey: string): Promise<{ ok: boolean; message: string }>;  // verdict only (G7)
}
export interface GovernanceRepository {                    // CONTRACT: TBD (Q13)
  health(instanceId: string): Promise<HealthSnapshot>;
  configLadder(instanceId: string): Promise<ConfigLadder>;
  logLevels(instanceId: string, component: string): Promise<LogLevelState>;
  setLogLevel(instanceId: string, component: string, level: LogLevel): Promise<void>;  // privileged, audited
  streamLogs(instanceId: string, component: string, s: AbortSignal): AsyncIterable<LogLine>;
  // ^ transport unknown (SSE? chunked? WebSocket?). The AsyncIterable hides it from every consumer.
}
export interface DashboardRepository {                     // CONTRACT: TBD (Q15) — ENTIRE document
  get(dashboardId: string): Promise<DashboardDoc>;
  saveWidget(dashboardId: string, w: WidgetConfig): Promise<WidgetConfig>;
  setLayout(dashboardId: string, l: WidgetPlacement[]): Promise<void>;
  hideDefault(dashboardId: string, widgetId: string, hidden: boolean): Promise<void>;
  resetDefaults(dashboardId: string): Promise<DashboardDoc>;
  // list(): intentionally ABSENT — the contract has no list route. Do not invent one
}
export interface AdminRepository {                         // CONTRACT: TBD (Q4, Q5)
  permissionCatalog(): Promise<PermissionDef[]>;
  roles(): Promise<Role[]>; saveRole(r: RoleDraft): Promise<Role>;
  principals(): Promise<Principal[]>; scopeTree(): Promise<ScopeNode>;
  assignments(): Promise<Assignment[]>; createAssignment(a: AssignmentDraft): Promise<Assignment>;
  effectivePermissions(principalId: string, scope: string[]): Promise<EffectiveGrant[]>;
  users(): Promise<LocalUser[]>; setUserActive(id: string, active: boolean): Promise<void>;
  // bootstrapFirstAdmin(): intentionally ABSENT — unspecified (Q5). Do not invent one
}
export interface TargetsRepository { list(): Promise<TargetRow[]> }   // READ-ONLY. no write method exists
```

#### 6.0.6 Permission model — `src/shared/authz/types.ts`

```ts
export type CatalogStatus = 'grantable' | 'proposed' | 'absent';
export interface PermissionDef {
  id: `${string}.${string}`;      // resource.action (D10)
  status: CatalogStatus;
  highConsequence?: boolean;      // confirm EVEN WHEN AUTHORIZED
  twoSided?: boolean;             // needs a grant on BOTH scopes
}

export interface PermissionApi {
  has(id: string): boolean;
  status(id: string): CatalogStatus;
  /** G13. Two modes, never confused. `omit` removes the control entirely. */
  gate(id: string): 'omit' | 'render';
}

/**
 * C12 RULE, enforced by this signature: a v1 feature may only gate on a
 * `grantable` verb. Gating on `proposed`/`absent` would deny EVERYONE.
 */
export type GrantableVerb = 'target.use' | 'instance.bindTarget';
export function requireGrantable(id: GrantableVerb): void;
```

#### 6.0.7 Shared-kernel component props — the cross-stream contract

Only the props stream E cannot write a screen without. Full APIs live with the components.

```ts
export interface ButtonProps { variant?: 'contained'|'outlined'|'text'|'danger'|'dangerContained';
  size?: 'sm'|'md'|'lg'; icon?: IconName; iconRight?: IconName; block?: boolean; }
export interface IconButtonProps { icon: IconName; 'aria-label': string; small?: boolean }  // label REQUIRED

export interface StateChipProps { entry: VocabEntry; size?: 'sm'|'md'; showTip?: boolean }
export interface VocabEntry { key: string; label: string; tone: Tone; icon: IconName; tip?: string; scope?: string }
//                                                        ^ icon and label are NON-OPTIONAL (G12)

export interface DataTableProps<R> {
  rows: readonly R[]; columns: readonly ColumnDef<R>[]; getRowId: (r: R) => string;
  selection?: readonly string[]; onSelectionChange?: (ids: string[]) => void;  // CONTROLLED; owner puts it in the URL
  detailPanel?: (r: R) => ReactNode; focusRowId?: string;
  quickFilter?: (r: R) => string; csvFileName?: string; empty?: ReactNode;
}

export interface DiffRow {
  path: string; kind: 'add'|'remove'|'change'|'rename';
  old: string | typeof SECRET_MASK | null;   // a secret row can ONLY carry the mask token — G7 at type level
  new: string | typeof SECRET_MASK;
  secret?: boolean;
}
export const SECRET_MASK: unique symbol;
export interface DiffViewProps { rows: readonly DiffRow[]; labels?: readonly [string, string] }

export interface SecretFieldProps {
  state: { kind: 'unset' } | { kind: 'set'; ref: string };   // NO value variant exists
  onSet: (v: string) => void; onClear: () => void; disabled?: boolean;
}   // there is deliberately no `reveal` prop (G7, FE-D23)

export interface ConfirmDialogProps {
  title: string; consequence?: 'high' | 'outage';   // drives the banner AND the danger button
  onConfirm: () => void; onCancel: () => void; confirmLabel: string;
}
```

#### 6.0.8 Verification protocol — the exact commands

Every task's verification is one of these. `<path>` is the task's own test file.

| Task class | Tasks | Command |
|---|---|---|
| **Standard component/unit** | most of 011–095, 106–109 | `npm run lint && npm run typecheck && npm test -- <path> && npm run test:a11y -- <path>` |
| **Pure engine + shared fixtures** | 046, 070, 071 | `npm run test:contracts` — runs `contracts/fixtures/**` in both directions; **must be byte-identical to the Java runner's input** |
| **Route / integration** | 004, 031, 042, 045 | `npm test -- <path> --environment jsdom` with a memory router |
| **End-to-end** | 099 and each phase gate | `npm run e2e -- --grep "<journey name>"` |
| **Guardrail lint rules** | 009 | `npm run lint -- src/test/lint-fixtures` — **must exit non-zero**; a passing run is the failure |
| **Bundle / build assertions** | 096, 100, 101 | `npm run build && npm run verify:bundle` |
| **Palette / contrast** | 097 | `npm run verify:palette` — fails the build below 3:1 (chart) or 4.5:1 (text) in **either** theme |
| **Full a11y sweep** | 098 | `npm run test:a11y -- --all` plus the manual keyboard script in `docs/checklists/` |
| **Performance** | 103 | `npm run test:perf` — asserts request dedup, visibility gating and render budgets |
| **Reflow / responsive** | 105, 110 | `npm test -- src/test/reflow.ts` (105); `npm run verify:reflow` (110) — six widths; undeclared horizontal scroll fails |

**Every task also runs the phase gate before it can be marked done:** `npm run verify` = `lint && typecheck && test && test:contracts && build`.

---

### Phase 0 — Foundation

#### FE-001 · Application scaffold, strict TypeScript, lint, CI
**Objective** A building, linting, type-checked Vite app with a CI pipeline that fails on any of the four gates.
**Refs** Dec `D7` 🔒 `FE-D3` `FE-D15` · Design §5, §7 · UI n/a
**Deps** none
**Files** *Create:* `frontend/package.json`, `vite.config.ts`, `tsconfig.json`, `tsconfig.node.json`, `eslint.config.js`, `.prettierrc`, `index.html`, `src/app/main.tsx`, `src/app/App.tsx`, `.github/workflows/frontend.yml`
**Implementation**
- React + Vite + TypeScript. **Pin the React minor and the React Router major at scaffold time** (design §5) and record the pins in the PR description; do not track `latest`.
- `tsconfig`: `strict: true`, `noUncheckedIndexedAccess: true`, `exactOptionalPropertyTypes: true`, `noImplicitOverride`, `verbatimModuleSyntax`. Path aliases `@app/* @shared/* @features/* @contracts/*`.
- ESLint: `@typescript-eslint` recommended-type-checked; `no-explicit-any` **error**; `react-hooks` rules; `import/order`.
- CI job matrix on Node LTS: `lint`, `typecheck`, `test`, `build`. **No `continue-on-error` anywhere.**
- `index.html` carries `<meta name="viewport" content="width=device-width, initial-scale=1">` (design §8.1) and the skip link target. **No `maximum-scale`, no `user-scalable=no`** — suppressing zoom fails WCAG 2.2 AA SC 1.4.4, and the meta tag is where that failure is usually introduced (`G16`, C3).
**Testing** CI green on an empty app; a deliberate `any` fails the lint job; a deliberate type error fails typecheck.
**Acceptance** ☐ four CI gates exist and fail correctly when broken ☐ aliases resolve in Vite, tsc and Vitest ☐ no `any` anywhere
**Ready** `READY`

#### FE-002 · Design-token layer with stated provenance
**Objective** One token source; every colour, type step, radius, shadow and spacing value in the app traces to it.
**Refs** Dec `FE-D5` (paint blocked) · `FE-D30` (`reference/` is the visual source of truth) · Design §8.1 · UI §B.1 (Solace Agent Mesh tokens) · §B.2 (console layer)
**Source** Copy from the checked-in files, not from Appendix B prose: `docs/design/frontend/reference/_ds/solace-agent-mesh-design-system-659eb3ca…/colors_and_type.css` for the palette and type scale, and `docs/design/frontend/reference/theme.css` for the console layer and dark-mode surfaces. Appendix B is the human-readable transcription; the CSS is authoritative where they differ.
**Deps** 001
**Files** *Create:* `src/shared/theme/tokens.css`, `src/shared/theme/tokens.ts`, `src/shared/theme/PROVENANCE.md`
**Implementation**
- Copy the Solace Agent Mesh DS custom properties verbatim into `tokens.css`: brand `--brand-wMain #00C895`; primary `--primary-wMain #015B82` (+ `w100 #01374E`, `w90 #014968`, `w60`, `w40`, `w20`, `w10`); neutrals `--secondary-*`; backgrounds `#021B2F / #03223B / #F7F8F9 / #FFFFFF`; semantics info `#0591D3` · error `#E94C4E` · warning `#FF8E2B` · success `#009A80` (each with `w100/w30/w10`); the 10 chart accents; dark-surface set; type `Rubik / Inter / JetBrains Mono` with the `--fs-*`/`--lh-*` scale; 4px spacing scale; radii; five shadows; motion durations and easings.
- `tokens.ts` re-exports them as a typed object for the MUI theme factory. **No component may read `tokens.css` directly.**
- `PROVENANCE.md` states: source bundle and hash, that these are **pending architect acceptance of open item 19 (§16 Q1)**, and that a PR treating them as brand-final before that acceptance is a defect (design §8.1).
- **Fonts:** Rubik/Inter/JetBrains Mono. Because `REQ-003` includes air-gapped deployments, **self-host the font files in `public/fonts` and `@font-face` them** — do not `@import` Google Fonts as the DS file does. Record the substitution.
**Testing** Snapshot test asserting the token set is complete and that no token resolves to `undefined`.
**Acceptance** ☐ every value traces to §B.1 or is marked as a documented substitution ☐ zero network font requests at runtime ☐ a token change is a one-file change (proven: grep finds no hex outside `shared/theme`)
**Verify** `npm run lint && npm run typecheck && npm test -- src/shared/theme && npm run verify:palette`
**Ready** `READY` — the values are recorded in §B.1 and a swap is one file, so Q1's answer does **not** materially change this task. ⚠️ **UX-accept remains blocked on Q1.**

#### FE-003 · MUI theme factory, colour mode, ECharts theme bridge
**Objective** One `createTheme` object, light and dark from the same tokens, with the chart theme a pure derivation of it.
**Refs** Dec `D7` 🔒 `FE-D5` `DM-D13` · Design §8.1 · UI §B.2 (dark surface set)
**Deps** 002
**Files** *Create:* `src/shared/theme/createSmTheme.ts`, `src/shared/theme/palette.ts`, `src/shared/theme/echartsTheme.ts`, `src/shared/state/uiStore.ts`
**Implementation**
- `palette.mode` **only** — one palette function parameterised by mode. No second dark file (design §8.1).
- Semantic palette plus **status extensions**: `health.ok`, `health.down`, `health.restartExpected`, `health.inactiveReplica`, `pending`, `unverified` — module-augment MUI's `Palette` so they are typed.
- Typography from the DS scale; `spacing: 4` with an 8px rhythm at component level; `shape.borderRadius` from `--radius-lg`; shadows mapped from the five DS shadows; **breakpoints come from the token layer, not MUI's defaults** — `xs 0 · sm 600 · md 905 · lg 1240 · xl 1560`, so the theme and the CSS token file cannot disagree about where a layout changes (`G16`, task 105).
- `CssBaseline` on. Focus-visible ring: 2px `--brand-wMain`, offset 2px (§B.2).
- `echartsTheme(theme)` is a **pure map** from the MUI theme to an ECharts theme object — axis, split line, tooltip, text, and the categorical series palette. Exported from `shared/theme`, consumed only by `ChartRenderer`.
- Colour mode in Zustand, persisted, defaulting to `light`; toggle lives in the app bar (task 036).
**Testing** Unit: `echartsTheme` output changes with mode and contains no literal hex. Render the gallery in both modes.
**Acceptance** ☐ mode switch repaints without remount ☐ no component defines a colour ☐ `echartsTheme` is pure (same input → deep-equal output)
**Ready** `READY`

#### FE-004 · Providers, router, route table, code splitting
**Objective** The route surface from design §14, lazily loaded, with the composition order fixed.
**Refs** Dec `FE-D6` · Design §14 · UI §A.2 (routing model)
**Deps** 003
**Files** *Create:* `src/app/providers.tsx`, `src/app/router.tsx`, `src/app/routes.ts`, `src/app/NotFound.tsx`
**Implementation**
- Provider order: `ThemeProvider` → `CssBaseline` → `QueryClientProvider` → `AuthProvider` → `RouterProvider`.
- Route table exactly as design §14: `/login`, `/`, `/solutions`, `/solutions/:solutionId/instances`, `/solutions/:solutionId/instances/:instanceId/:category`, `…/monitoring/explore`, `…/monitoring/dashboards/:dashboardId`, `/targets`, `/admin`. `:category` ∈ `designer | governance | monitoring` — an unknown value 404s rather than falling through.
- **`tenantId` is never a path segment** (`I5`, `FE-D8`).
- `React.lazy` per feature; a `Suspense` fallback that reserves layout (no shift).
- 404 page; unknown route does not redirect silently.
**Testing** Memory-router tests: every route resolves; `:category` rejects garbage; lazy chunks are separate in the build manifest.
**Acceptance** ☐ each feature is its own chunk ☐ no route carries a tenant ☐ deep link to any route restores that view
**Ready** `READY`

#### FE-005 · Runtime configuration boot
**Objective** Deploy-time configuration without a rebuild, and no plane topology in the browser.
**Refs** Dec `FE-D14` `FE-D19` `FE-D24` · Design §13, §21
**Deps** 001
**Files** *Create:* `public/config.json`, `src/app/boot/loadConfig.ts`, `src/app/boot/RuntimeConfig.tsx`, `src/app/boot/buildInfo.ts`
**Implementation**
- `config.json` shape: `{ apiBasePath, idleTimeoutMinutes, channel }` — **public prefix, idle minutes and channel only** (design §13). It carries **no plane hostname**; two public plane URLs are forbidden (`FE-D19`).
- Fetched before first render with `cache: "no-store"`; parse failure renders a boot error page, never a silent default.
- `idleTimeoutMinutes` defaults to 30, confirmable with operations (§16 Q6).
- `buildInfo` from `import.meta.env` — version and commit, shown in the support menu (task 049).
**Testing** Unit: a missing file, a malformed file and an unknown extra field each behave as specified (the last is ignored).
**Acceptance** ☐ no secret and no plane host in the bundle or in `config.json` ☐ boot failure is visible, not silent
**Ready** `READY`

#### FE-006 · Error boundaries and the error taxonomy
**Objective** A widget error never takes down a dashboard; a route error never takes down the shell.
**Refs** Dec `FE-D10` · Design §16 · UI §A.11 (widget error boundary)
**Deps** 004
**Files** *Create:* `src/app/errors/RouteErrorBoundary.tsx`, `src/app/errors/WidgetErrorBoundary.tsx`, `src/shared/ui/ErrorPanel.tsx`
**Implementation**
- `ErrorPanel` takes `{ title, detail, code?, correlationId?, onRetry? }` and always distinguishes **error** from **empty** — *"empty is never a silent success"* (`FE-D10`).
- `WidgetErrorBoundary` renders inside the widget frame and keeps siblings refreshing (`dynamic-metrics` §17).
- `RouteErrorBoundary` keeps the shell (nav, breadcrumbs, header) mounted.
- Both surface the **server-returned** correlation id when present (`FE-D13`).
**Testing** Component: a throwing child inside a widget leaves siblings mounted; a throwing route leaves the shell mounted.
**Acceptance** ☐ no error path renders an empty state ☐ correlation id shown when the server supplied one
**Ready** `READY`

#### FE-007 · Message catalogue and formatting
**Objective** No concatenated sentences, UTC canonical, units formatted from the declared unit.
**Refs** Dec `B/D15` 🔒 · Design §18 (i18n) · `dynamic-metrics` §18
**Deps** 001
**Files** *Create:* `src/shared/i18n/messages.en.ts`, `src/shared/i18n/t.ts`, `src/shared/format/time.ts`, `src/shared/format/units.ts`
**Implementation**
- English only in v1; every user-visible string is a catalogue key. **No string built by concatenating fragments**, because a view-model value must never be glued into a sentence (design §18).
- ICU plural forms for the many `N replica(s)` / `N field(s)` strings the prototype uses.
- `time.ts`: payloads are UTC; display via `Intl.DateTimeFormat`; **the active zone is always labelled on a time axis** (`dynamic-metrics` §18). A display-timezone toggle is out of v1 scope — record it.
- `units.ts`: format from the canonical `unit` string, **never parsed from a metric name** (`DM-D13`).
**Testing** Unit tests for plural forms, UTC→local rendering, unit formatting including `ratio`, `ms`, `count`, `entries`.
**Acceptance** ☐ zero literal user-facing strings in components ☐ every rendered time is labelled with its zone
**Ready** `READY`

#### FE-008 · Test harness
**Objective** Five test kinds runnable locally and in CI from day one, so no task can defer its tests.
**Refs** Dec `FE-D12` · Design §19
**Deps** 001
**Files** *Create:* `vitest.config.ts`, `src/test/setup.ts`, `src/test/renderWithProviders.tsx`, `playwright.config.ts`, `e2e/smoke.spec.ts`, `src/shared/testdoubles/server.ts`
**Implementation**
- Vitest + jsdom; React Testing Library; `@axe-core/react`/`jest-axe` matcher; MSW node server started in `setup.ts` with `onUnhandledRequest: "error"` — **an unmocked request fails the test**, which is what stops fixtures drifting invisibly.
- `renderWithProviders` mounts theme + query + router + auth with per-test overrides for permissions and colour mode.
- Playwright against the Vite preview server with MSW's browser worker.
- A `contracts` test project runs the shared JSON fixture suite (tasks 046, 071).
**Testing** The harness tests itself: a trivial component test, a trivial axe test, a trivial e2e.
**Acceptance** ☐ `npm test` covers unit + component + a11y ☐ `npm run e2e` green ☐ an unmocked fetch fails a test
**Ready** `READY`

#### FE-009 · Architecture guardrails in lint
**Objective** Turn design rules into build failures.
**Refs** Dec `FE-D3` `FE-D11` `FE-D15` `FE-D16` `I7` · Design §4, §7, §25
**Deps** 001
**Files** *Modify:* `eslint.config.js` · *Create:* `scripts/check-forbidden-deps.mjs`
**Implementation** — implement each row of §4.4:
- `eslint-plugin-boundaries` zones `app | features | shared | contracts` with the §4.1 rule.
- `no-restricted-imports`: `echarts*` allowed only in `features/monitoring/chart/ChartRenderer.tsx`; `@shared/api/*` forbidden inside `**/shared/ui/**`; `@mui/x-data-grid-pro`, `@mui/x-data-grid-premium`, `@mui/x-charts-pro` forbidden everywhere (design §25 names this exact risk).
- A no-raw-colour rule for `#rrggbb` / `rgb(` / `hsl(` outside `shared/theme`.
- `contracts/**` may not import `react`, `@mui/*`, or anything under `src/`.
- `scripts/check-forbidden-deps.mjs` greps the lockfile for Pro/Premium packages and fails CI.
**Testing** A fixture file per rule that must fail lint (kept under `src/test/lint-fixtures`, excluded from the build).
**Acceptance** ☐ each rule demonstrably fails on its fixture ☐ CI runs the dependency check
**Ready** `READY`

#### FE-010 · Dev-only component gallery
**Objective** One place to review every primitive in both themes, and the surface the a11y sweep runs against.
**Refs** Design §9 · UI §A.1
**Deps** 003, 004
**Files** *Create:* `src/app/dev/Gallery.tsx`, `src/app/dev/gallery.routes.ts`
**Implementation** Route `/dev/gallery`, **registered only when `import.meta.env.DEV`** so it cannot reach a production bundle (same rule as the auth mock, `FE-D24`). One section per Phase-1 component showing default / hover / focus / disabled / error / loading, in both themes.
**Testing** e2e: the route 404s in a production build. Axe sweep (task 098) walks it.
**Acceptance** ☐ absent from the production bundle ☐ every Phase-1 component appears
**Ready** `READY`

### Phase 1 — Shared UI kernel

> Every task in this phase: *Deps* include 003 + 010; *Files* create `src/shared/ui/<Name>/` (component, types, test, a11y test) and register the component in the gallery and in `src/shared/ui/index.ts`; *Testing* always includes an axe pass in **both** themes. Only what is specific is written below.

#### FE-011 · Buttons
**Objective** One button API covering the prototype's five weights, including the two danger weights that high-consequence confirmations need.
**Refs** Dec `FE-D9` · Design §8.2, §15 · UI §A.1 (buttons) · §B.4 (button specimen)
**Deps** 003, 010
**Implementation** Variants `contained | outlined | text | danger | dangerContained`; sizes `sm | md | lg`; `icon` and `iconRight` slots; `block`. Uppercase label with the DS letter-spacing. Minimum height 40px on primary actions (design §18 hit target). `IconButton` 40/32px with a **required** `aria-label`. A disabled button that is disabled *for a reason* must be wrapped by the caller in a `Tooltip` carrying that reason — never a bare disabled control (`FE-D9`).
**Acceptance** ☐ danger variants distinguishable without colour (icon + label) ☐ `IconButton` without `aria-label` fails typecheck
**Ready** `READY`

#### FE-012 · Form control wrappers
**Objective** Label / helper / error wiring done once, correctly, so no field author can get `aria-describedby` wrong.
**Refs** Dec `FE-D15` · Design §18 (forms) · UI §A.1 (form controls) · §A.8 (control kinds)
**Deps** 003, 010
**Implementation** `TextField` (with `reveal` for password), `NumberField` (`inputMode="numeric"`), `Select`, `Switch`, `Checkbox` (supporting `mixed`), `Autocomplete`, `TextArea`. Each: `label` + optional `required` marker + `helperText` + `error`, with `aria-invalid` and `aria-describedby` wired automatically and a stable generated id. **Never a placeholder as the only label.**
**Acceptance** ☐ every control announces label, requiredness, help and error to a screen reader ☐ `Checkbox` supports `aria-checked="mixed"`
**Ready** `READY`

#### FE-013 · Chip, StateChip and the state-vocabulary module
**Objective** The single place every status string, tone, icon and explanatory tooltip lives — so a state is never rendered as colour alone and never rendered two different ways on two screens.
**Refs** Req `REQ-006` · Dec `UI-D8` `UI-D9` `SM-D22` `SM-D27` · Design §9 (`StatusBadge`), §18 · UI §A.13 (state vocabularies) · §A.1 (chip · state chip)
**Deps** 003, 010
**Files** *Create:* `src/shared/vocab/health.ts`, `notFound.ts`, `version.ts`, `configState.ts`, `driver.ts`, `locus.ts`, `index.ts`; `src/shared/ui/Chip`, `src/shared/ui/StateChip`
**Implementation**
- Port the vocabularies verbatim from §A.13.1. Each entry is `{ key, label, tone, icon, tip?, scope? }`.
  - **Health (5):** `healthy · not_responding · restart_expected · inactive_replica · not_applicable`.
  - **Not-found (5):** `restart_expected · unreachable (host alert) · not_exposed (configuration finding) · missing (instance alert) · bootstrap (awaiting first restart)`. A single `MISSING` badge is a defect (`architecture.md` §7.2).
  - **Version (4):** `ok · drift (read-only) · unknown (blocked) · unverified (editable — the permanent-normal VM state)`. `UNVERIFIED` **does not** block editing (`UI-D12` over `SM-D14`).
  - **Config (6):** `in_sync · staged · rendered · diverged · drift · na`. `in_sync` renders **with** an `effective-unverified` qualifier chip, because restart-observed is not convergence (`SM-D22`).
  - **Driver (3)** `docker · k8s · systemd`, **Locus (3)** `dp-remote · host-local · observe-only` — both are **row data with a tooltip**, never a UI branch (`REQ-005`, `REQ-008`).
- `StateChip` renders **icon + text + tone**, and an `InfoTip` when the entry has a `tip`. **Colour is never the only channel.**
**Testing** A table-driven test asserting every vocabulary entry renders a non-empty label and a non-null icon; a test asserting `not_applicable` never resolves to the error tone.
**Acceptance** ☐ no screen defines its own status string ☐ every state carries text and icon ☐ `unverified` is not styled as an error
**Ready** `READY`

#### FE-014 · Alert / Banner and Tooltip / InfoTip
**Refs** Dec `FE-D10` `DM-D10` · Design §9, §16 · UI §A.1 (alert · tooltip · info tip)
**Deps** 003, 010
**Implementation** `Alert` severities `info | warn | err | ok | neutral`, with `title`, `icon` override, an `action` slot (the retry button), and `role="alert"` **only** for `err`. `Tooltip` is focusable (`tabIndex=0`) so it is keyboard-reachable; `InfoTip` is the small `i` affordance the prototype uses ~40 times. A tooltip is never the only carrier of a blocking reason — the reason is also in text.
**Acceptance** ☐ `err` alerts are announced ☐ every tooltip reachable by keyboard ☐ no tooltip-only error message
**Ready** `READY`

#### FE-015 · Surfaces and page furniture
**Refs** Design §9 · UI §A.1 (surfaces · page header) · §B.5 (page widths)
**Deps** 003, 010
**Implementation** `Paper` (flat / padded / hoverable), `PageHeader` (title, sub, right-actions slot — actions come from the RBAC omit list, task 032), `SectionHead`, and the page widths as **layout components rather than CSS classes** — capped at `1240px` default / `1560px` wide, and fluid below `lg` with responsive gutters (`40px → 24px → 16px`). `PageHeader`'s right-hand action slot **wraps below the title under `md`** rather than truncating; actions are never dropped to save space, because an omitted action is `FE-D9`'s signal for *unauthorised* (`G13`).
**Acceptance** ☐ page width is a component prop, not a copied class ☐ `PageHeader` right slot omits rather than disables unauthorised actions ☐ no horizontal page-body scroll at 320 px ☐ actions wrap, never truncate
**Ready** `READY`

#### FE-016 · Dialog, ConfirmDialog, high-consequence confirm
**Objective** Every destructive path uses the same trapped, labelled, escapable dialog — and high-consequence actions confirm **even when authorised**.
**Refs** Dec `FE-D9` · Design §15 · UI §A.1 (dialog) · §A.8 (restart confirm) · §A.6 (bulk restart dialog)
**Deps** 003, 010
**Implementation** MUI `Dialog` wrapper: focus trapped, focus restored to the trigger on close, `Escape` closes, `aria-modal`, `aria-labelledby` from the title, scrim click closes only when there is no dirty input. `ConfirmDialog` adds a body slot and a typed confirm button. `HighConsequenceDialog` requires an explicit `consequence` prop (`outage | high`) that drives the banner and the danger button, for: restart-all, rolling-no-validation, force-raw, streaming export, version re-declare, archive/restore (design §15).
**Acceptance** ☐ focus trapped and restored ☐ a high-consequence action cannot be confirmed without the consequence banner rendering
**Ready** `READY`

#### FE-017 · Menu / Popover
**Refs** UI §A.1 (menu) · Design §18 (keyboard)
**Deps** 003, 010
**Implementation** MUI `Menu` wrapper with roving focus, `Escape` to close, outside-click to close, and a `caption` item type for the prototype's `mi-cap` group labels. Trigger receives `aria-haspopup` and `aria-expanded`.
**Acceptance** ☐ full keyboard operation ☐ closing returns focus to the trigger
**Ready** `READY`

#### FE-018 · ToggleButtonGroup and route-driven Tabs
**Refs** UI §A.1 (toggle group · tabs) · §A.7 (instance tabs) · Design §9 (`CategoryTabs`), §18 (focus after tab change)
**Deps** 003, 010
**Implementation** `ToggleGroup` (`role="group"`, `aria-pressed`) for env filters, seriesBy, mark, resolution, layer. `RouteTabs` drives `role="tablist"`/`tab` from the router — a tab is a link, so it is deep-linkable and middle-clickable. **On tab change, focus moves into the panel** (design §18).
**Acceptance** ☐ tabs are links ☐ focus lands in the panel after a change
**Ready** `READY`

#### FE-019 · EmptyState and Skeleton
**Refs** Dec `FE-D10` · Design §9, §16 · UI §A.1 (empty state · skeleton) · §A.14 (state harness)
**Deps** 003, 010
**Implementation** `EmptyState` (icon, title, body, action slot) — its copy must name **why** it is empty and what to do, never just "no data". `Skeleton` at **the dimensions of the content it replaces** so nothing shifts when data arrives (`dynamic-metrics` §11.2).
**Acceptance** ☐ zero layout shift measured between skeleton and loaded state ☐ no empty state is reachable from an error path
**Ready** `READY`

#### FE-020 · DataTable on MUI X Data Grid **Community**
**Objective** One table for inventory, replicas, assignments, users and permissions — with no Pro feature anywhere.
**Refs** Dec `FE-D11` · Design §9, §17 · UI §A.1 (data table)
**Deps** 003, 010
**Implementation**
- Community only: sorting, quick filter, column visibility, density (`standard | compact`), checkbox selection, CSV export, virtualization. **No pinning, no row grouping, no Excel export** — `FE-D11` makes Pro a procurement decision, not an assumption.
- **Expandable detail row** (the prototype's replica panel) via the Community detail-panel pattern; the expander is a real button with `aria-expanded`.
- Selection is **controlled** and the owner puts it in the URL (task 042).
- Column definitions carry an optional `info` tooltip (the prototype uses this to explain that `driver`/`locus` are data).
- CSV export runs client-side over the **filtered** rows for small tables; the large streaming export is a different mechanism (task 035/088).
**Testing** Keyboard traversal of header sort, selection and expansion; `aria-sort` on the sorted header; a test asserting no Pro import.
**Acceptance** ☐ zero Pro/Premium API usage ☐ selection controlled ☐ expander announced
**Ready** `READY`

#### FE-021 · Toast and the persistent outcome badge
**Objective** A mutation result is *acknowledged* by a toast and *recorded* on the resource — the prototype's rule that a toast is not a record.
**Refs** Dec `FE-D13` · Design §9, §16 · UI §A.7 (toasts · outcome badge)
**Deps** 003, 010
**Implementation** `Toaster` in an `aria-live="polite"` region, auto-dismiss ~6s, manual dismiss, and **the server correlation id in every toast** (`FE-D13`). `OutcomeBadge` persists the last mutation outcome on the instance header with its correlation id. **Toasts are for acknowledgement, not for errors that must remain on the resource** (design §16) — an error that needs action renders as an `Alert` in place.
**Acceptance** ☐ every toast carries a correlation id ☐ no error-that-requires-action is delivered only as a toast
**Ready** `READY`

#### FE-022 · DiffView
**Objective** The field-level diff that `architecture.md` §7.2 calls *"the load-bearing UX"* — used by four different flows.
**Refs** Dec `I6` `FE-D23` `UI-D15` · Design §9, §10.1 · UI §A.8 (diff view · diff datasets)
**Deps** 003, 010
**Implementation**
- Row shape `{ path, kind: add | remove | change | rename, old, new, secret? }` with configurable column labels, because the same component serves: draft-vs-deployed, **409 mine-vs-theirs**, rollback current-vs-target, and accept-runtime re-render.
- **A `secret: true` row renders masked on both sides and carries a `secret · masked` tag.** The component must be incapable of displaying a secret: its props type makes `old`/`new` for a secret row a literal mask token, not a string (`I6`).
- Rename rows state *"needs mapping"* — an unmapped rename silently drops a value (`architecture.md` §7.2).
**Testing** A test that passing a real value on a `secret` row is a **type error**, plus a runtime test that it renders masked regardless.
**Acceptance** ☐ a secret value cannot reach the DOM ☐ all four callers use this one component
**Ready** `READY`

#### FE-023 · SecretField
**Objective** Write-only credential entry. The browser never receives a resolved secret (`FE-D23`).
**Refs** Dec `I6` `FE-D23` `UI-D22` · Design §9, §10 · UI §A.8 (secret control)
**Deps** 003, 010
**Implementation** Two states: *no credential set* and *credential set* (showing the **reference**, e.g. `secretRef://kv/acme/…`, never a value). Actions **Set / Replace / Clear**. Entry is a `type="password"` input in an inline sub-form; on save the component emits a **set-secret intent**, not a value bound into the draft. Copy states plainly: *"stored as a reference and never displayed again, here or in diffs, exports and history."* **There is no reveal affordance** — reveal is not a hide/show of the value (`FE-D23`).
**Testing** The component never renders a value; clearing emits an explicit clear intent; the field's contribution to a diff is a mask token.
**Acceptance** ☐ no code path returns a resolved secret to the browser ☐ no reveal control exists
**Ready** `FIXTURE` — set/clear wire shape is **API CONTRACT — TBD**

#### FE-024 · Breadcrumbs and SidebarNav
**Refs** Design §9, §14 · UI §A.2 (breadcrumbs · sidebar nav)
**Deps** 003, 010
**Implementation** `Breadcrumbs` derived from the route (Home / Solutions / *Solution* / Instances / *Instance* / *Category*) inside `<nav aria-label="Breadcrumb">`, with the current page not a link and long names truncated with a title. `SidebarNav` on the navy dark surface: `Home`, `Solutions` (with a count) and a sub-list of loaded solutions with instance counts; active item marked with `aria-current="page"` **and** a non-colour marker (the accent bar). **`Targets` and `Admin` are omitted from the nav** until their routes ship (design §14).
**Acceptance** ☐ nav contains only Home and Solutions in v1 ☐ active state is not colour-only
**Ready** `READY`

#### FE-025 · FilterBar and SearchField
**Refs** Design §9 · UI §A.6 (toolbar) · §A.8 (field search) · §A.10
**Deps** 003, 010
**Implementation** A labelled search input with a debounce **only for free text** — enum/toggle filters apply immediately (design §17). Filters expose their value to the owner for URL serialisation. Includes a result-count live region so a screen-reader user hears the filter took effect.
**Acceptance** ☐ enum filters are not debounced ☐ result count announced politely
**Ready** `READY`

### Phase 2 — API seam, auth, permissions

#### FE-026 · HTTP core
**Objective** One request path with correlation, abort, tolerant parsing and no leakage.
**Refs** Dec `FE-D7` `FE-D13` `FE-D19` `FE-D20` · Design §13
**Deps** 001
**Files** *Create:* `src/shared/api/http.ts`, `src/shared/api/correlation.ts`, `src/shared/api/types.ts`
**Implementation**
- `request<T>(path, { method, body, signal, headers })` → `Promise<T>`; JSON in/out; **unknown response fields are ignored, never stripped and never validated away** (design §13, `DM-D2`).
- Client **may** send `X-Correlation-Id`; the **response id is authoritative** and is what surfaces in UI and logs (`FE-D13`).
- `credentials: "same-origin"` so the httpOnly session cookie travels; a CSRF token header hook is present and configurable (§16 Q3).
- Every request takes an `AbortSignal`; a superseded request is aborted **before** the next is issued (`dynamic-metrics` §10).
- **Never logs a request body.** Never logs a response body for config or metrics payloads (`I6`, `FE-D13`).
**Testing** Unit: abort ordering; unknown-field tolerance; correlation precedence; that a 204 does not throw.
**Acceptance** ☐ superseded requests abort first ☐ no body reaches any log
**Ready** `READY`

#### FE-027 · Plane clients behind one gateway origin
**Objective** Three named clients, one origin, no plane topology in the browser.
**Refs** Dec `FE-D7` `FE-D19` `SM-D1` `I7` · Design §13 · Conflict C5
**Deps** 026, 005
**Files** *Create:* `src/shared/api/cpClient.ts`, `dpClient.ts`, `consoleClient.ts`, `index.ts`
**Implementation**
- All three compose `http` with a prefix derived from `config.apiBasePath`: `cp` → `${base}/cp`, `dp` → `${base}/dp`, `console` → `${base}/console/v1` (C5 — `dynamic-metrics` §5.2's composed-read routes).
- **No `VITE_*` variable may name a plane host.** A lint rule forbids `VITE_.*_URL` (task 009 extension).
- Composition of a CP read and a DP read happens **in a hook**, never in a leaf (design §4).
- A comment block records that the third `console` module is an **unresolved architectural question upstream** (`dynamic-metrics` §5.2) and that the client split here does not decide it.
**Testing** Unit: each client prefixes correctly; a test asserting no client accepts an absolute URL.
**Acceptance** ☐ one origin ☐ absolute URLs rejected ☐ composition only in hooks
**Ready** `READY`

#### FE-028 · Typed error codec and retry policy
**Objective** The UI switches on a machine `code`, never on a message string.
**Refs** Dec `FE-D10` `FE-D20` · Design §13, §16 · `dynamic-metrics` §5.8 · UI §A.10 (typed error copy)
**Deps** 026
**Files** *Create:* `src/shared/api/ApiError.ts`, `src/shared/api/errorCodes.ts`, `src/shared/api/retry.ts`
**Implementation**
- `ApiError { status, code?, message?, correlationId?, details?: unknown }`.
- `errorCodes.ts` registry, **each entry marked `SPECIFIED` or `TBD`**:
  - `SPECIFIED` (from `dynamic-metrics` §5.8): `SERIES_CAP_EXCEEDED` `AGGREGATION_NOT_APPLICABLE` `GROUP_BY_REQUIRES_AGGREGATION` `STAT_REQUIRES_AGGREGATION` `METRIC_UNTYPED`.
  - `TBD` (named in design §13/§16 but with **no body shape anywhere**): `CONFIG_DIVERGED` `APPLY_INDETERMINATE`, version-state codes, and the `409` conflict payload. The prototype flags the `422` body shape as unspecified too — carry that flag into the code comments (§16 Q9).
- Retry: **5xx only**, bounded exponential backoff. **Never retry `422`** — an incoherent aggregation is not transient. Never retry a mutation.
- An unknown `code` renders the server's message plus the correlation id, and is **never** silently swallowed.
**Testing** Unit per code; a test asserting a `422` is not retried; a test asserting an unknown code still surfaces.
**Acceptance** ☐ no component matches on an error string ☐ `422` never retried ☐ every `TBD` code is marked as such in source
**Ready** `FIXTURE`

#### FE-029 · QueryClient defaults, fail-closed
**Refs** Dec `FE-D1` `FE-D20` · Design §6.2, §11 · `dynamic-metrics` §10
**Deps** 028
**Files** *Create:* `src/shared/api/queryClient.ts`, `src/shared/api/queryKeys.ts`
**Implementation** `refetchOnWindowFocus: false` for metrics; retry predicate from task 028; **no optimistic updates on apply or restart** (`FE-D20`); `placeholderData: keepPreviousData` so a refresh **dims rather than blanks** (`dynamic-metrics` §10); structural sharing on. `queryKeys.ts` is the single key factory — metrics keys are built from the **canonical query hash** (task 070) so two widgets asking the same question issue one request.
**Acceptance** ☐ no mutation is optimistic ☐ refetch keeps previous data ☐ all keys come from the factory
**Ready** `READY`

#### FE-030 · Auth adapter, local session, DEV-only mock
**Refs** Dec `D13` 🔒 (deferred) `FE-D8` `FE-D14` `FE-D24` · Design §15 · UI §A.3
**Deps** 001
**Files** *Create:* `src/shared/auth/AuthAdapter.ts`, `localSessionAdapter.ts`, `devMockAdapter.ts`, `AuthProvider.tsx`
**Implementation**
- Interface: `getIdentity()`, `login(credentials)`, `logout()`, `onSessionExpired(cb)`, `getAccessToken?()`. OIDC/Keycloak replaces the implementation without touching a feature (`FE-D8`).
- v1 = local session on an **httpOnly, SameSite=strict cookie set by the gateway**. **No refresh token in `localStorage`** (`FE-D14`). If the API cannot set cookies, an in-memory access token is the only fallback.
- `devMockAdapter` is registered **only under `import.meta.env.DEV`**, and task 101 asserts its absence from a production bundle.
- **Tenant is display-only**, from the server's identity response; the client never sends it as authority (`I5`, `FE-D8`).
**Testing** Unit: expiry callback fires on `401`; the mock is tree-shaken from a production build (asserted in 101).
**Acceptance** ☐ no token in `localStorage` ☐ tenant never sent as a parameter ☐ mock absent from prod
**Ready** `FIXTURE` — login endpoint is **API CONTRACT — TBD**

#### FE-031 · Route guards, 401 handling, idle lock
**Refs** Dec `FE-D24` · Design §14, §15, §16 · UI §A.3 (arrival states)
**Deps** 030, 004, 005
**Files** *Create:* `src/shared/auth/RequireAuth.tsx`, `src/shared/auth/useIdleTimer.ts`
**Implementation**
- Unauthenticated → `/login`, preserving the attempted URL for post-login return.
- A `401` from any request routes to `/login` with `arrival=session` — *"the server no longer recognised your session"*.
- Idle timer from `config.idleTimeoutMinutes` (default 30) → `/login` with `arrival=idle`.
- **Local draft preservation — DECIDED in this plan (proposed `FE-D25`, §16.1).** The reference copy promises that in-progress edits survive an idle lock. That is a behaviour, not a message, so this task ships the mechanism:
  - Designer drafts **only** — never metrics, never Explore, never anything else.
  - Stored in **`sessionStorage`** (per-tab, cleared when the browser tab closes), keyed `smc.draft.<instanceId>`, holding the RHF draft **minus any secret intent** (G7 — a pending secret value never touches storage).
  - Written on a debounced change, **cleared on successful save, on apply, and on explicit discard**.
  - Restored after re-authentication **with an explicit banner** naming what was restored and offering `Discard`. A silent restore is worse than none: the operator must know the form is not the server's state.
  - The login copy says exactly what the mechanism does, and no more.
**Testing** Guard tests; idle lock round-trip (draft written → lock → sign in → banner + restored values); a test asserting **no secret intent is ever written to storage**; a test asserting the copy and the mechanism ship together.
**Verify** `npm run lint && npm run typecheck && npm test -- src/shared/auth --environment jsdom`
**Acceptance** ☐ both arrival states distinguishable ☐ draft restored with an explicit banner and a discard action ☐ no secret in storage ☐ no unimplemented promise in the copy
**Ready** `READY`

#### FE-032 · Permission model, omit-vs-disable
**Objective** Frontend RBAC as a **cosmetic overlay** that is honest about being cosmetic.
**Refs** Req `REQ-006` · Dec `D10` 🔒 `FE-D9` `FE-D22` `DM-D18` (proposed) · Design §15 · UI §A.12 (permission catalog)
**Deps** 001
**Files** *Create:* `src/shared/authz/permissions.ts`, `usePermissions.ts`, `Can.tsx`, `catalog.ts`
**Implementation**
- `catalog.ts` carries the permission ids **with their catalog status** — `grantable | proposed | absent` — exactly as §A.12.1 models it. Today: **`target.use` and `instance.bindTarget` are grantable**; the six `DM-D18` metrics/dashboard verbs are `proposed`; the rest are `absent` candidates.
- **Rule (C12): a v1 feature is never gated on a non-`grantable` verb**, because an ungrantable verb denies everyone. Features whose intended verb is `proposed`/`absent` render **ungated in v1** and carry a `TODO(verb)` marker resolved when the catalog is amended (§16 Q4).
- `Can` supports two modes: **omit** (unauthorised — the control does not exist, `FE-D9`) and **disable-with-reason** (authorised but a capability or cap blocks it). These must never be confused: *"omit, not puzzle-disable."*
- Every module carries the banner comment: **hiding a control is not a security boundary; the server denies by default** (design §15).
**Testing** Component tests for both modes; a test asserting no v1 feature gates on a non-grantable verb; a type test asserting `requireGrantable` (§6.0.6) rejects a `proposed`/`absent` id at compile time.
**Verify** `npm run lint && npm run typecheck && npm test -- src/shared/authz`
**Acceptance** ☐ omit and disable are distinct and tested ☐ catalog status is data ☐ no ungrantable gate in v1 ☐ gating on a non-grantable verb is a **type error**
**Ready** `READY` — the catalog is **data** (§6.0.6), so Q4's answer changes a fixture, not this implementation. Phase 7's *shipping* remains gated on Q4.

#### FE-033 · Fixture layer (MSW) and repository interfaces
**Objective** Build every screen before any endpoint exists, without inventing a contract.
**Refs** Dec `FE-D7` · Design §2.2, §25 · UI §A.13 (fixture datasets · series generator)
**Deps** 008
**Files** *Create:* `src/shared/testdoubles/handlers/*.ts`, `src/shared/testdoubles/data/*.ts`, `src/shared/testdoubles/README.md`, and one `…Repository.ts` interface per feature
**Implementation**
- **Every fixture module begins with a header** naming: which fields are **specified** (with the citation) and which are **invented for the fixture**. §A.13 records which is which — carry that discipline into every module.
- Port the datasets: 3 solution packages (incl. the unsigned dev-mode one), 12 Cache instances spanning **every** health/not-found/version/config state, the attention feed, the Cache 26-field/8-section model with 4 discriminators, the File Mesh 2-component/~100-field model with virtual fields, rollout and diverged replica sets, diff and conflict sets, restart strategies, the RBAC catalog/roles/principals/scope tree/assignments, the metric catalog with `plottable`/`needs-typing`/`type-suspect`, and the deterministic series generator honouring gaps · low coverage · retention truncation · no-data · partial · inactive · pending.
- Repository interfaces are the seam (§9). A feature imports the interface, never `msw`.
**Testing** A test asserting `onUnhandledRequest: "error"` catches an un-fixtured call.
**Acceptance** ☐ every fixture header states specified-vs-invented ☐ no feature imports `msw` ☐ all 12 instance states representable
**Ready** `READY`

#### FE-034 · Contract types seam and codegen script
**Refs** Dec `FE-D7` `FE-D15` · Design §7 (`generated/`), §13
**Deps** 001
**Files** *Create:* `src/generated/README.md`, `scripts/generate-types.mjs`, `src/shared/api/contracts/*.ts`
**Implementation** `generated/` holds OpenAPI output, is CI-regenerated and **never hand-edited**. Until an OpenAPI document exists, hand types live in `shared/api/contracts/`, colocated, each carrying a `// CONTRACT: TBD — <what is needed>` header, and are **replaced wholesale** by generated types rather than extended into a shadow contract (`FE-D7`). The codegen script exists and fails loudly with "no OpenAPI document" so the wiring is proven before the contract lands.
**Acceptance** ☐ every hand type marked TBD with what is needed ☐ generation wired and failing informatively
**Ready** `FIXTURE`

#### FE-035 · Streaming download helper
**Refs** Dec `DM-D14` `FE-D19` · Design §13, §15 · `dynamic-metrics` §5.8
**Deps** 026, 016
**Files** *Create:* `src/shared/api/download.ts`
**Implementation** A **same-origin navigation-style download** so the session cookie travels and the browser streams to disk; the response is `text/csv` with `Transfer-Encoding: chunked` and **must not be buffered into memory** (`DM-D14`). Gated by a high-consequence confirm (task 016) because a streaming export can strain the deployment (design §15). Distinguish from the Data Grid's small client-side CSV (task 020) — different mechanism, different name, different confirmation.
**Acceptance** ☐ never buffers the body ☐ high-consequence confirm before start ☐ not confused with grid CSV
**Ready** `FIXTURE` — export endpoint specified in `dynamic-metrics` §5.8; RBAC verb is `proposed`

### Phase 3 — Shell, identity, inventory

#### FE-036 · AppShell
**Objective** The persistent chrome: navy drawer, white app bar, breadcrumbs, tenant display, theme toggle, support menu, account menu, skip link, focus management.
**Refs** Req `REQ-003` (tenant display-only) · Dec `D9` 🔒 `FE-D8` `FE-D13` · Design §9, §14, §18 · UI §A.2
**Deps** 024, 017, 021, 030
**Files** *Create:* `src/app/shell/AppShell.tsx`, `AppBar.tsx`, `TenantDisplay.tsx`, `SupportMenu.tsx`, `AccountMenu.tsx`, `ThemeToggle.tsx`
**Implementation**
- Grid: `256px` drawer + main; app bar `64px`. Skip link → `#main`; `<main id="main" tabIndex={-1}>` is **focused and scrolled to top on every route change** (§A.2; design §18 requires it).
- **Tenant is a display string the server returns — not a switcher, not in the route** (`I5`, `FE-D8`). Render it as text with a title explaining that.
- Support menu: UI version from `buildInfo`, the session correlation id, and a copy button — *"quote the correlation id on a support ticket"* (`FE-D13`).
- Account menu: identity, role chip, sign out. **Targets and Admin appear here only behind a `DEV`/preview flag** until Phase 7 ships them (design §14).
- **Two prototype affordances must not ship:** the *Demo state* selector and the per-screen *Scenario* selectors. They exist to make states reviewable; in production the state comes from the server. Add a lint rule forbidding the identifier `DEMO_STATES` outside `testdoubles`.
- **No bell, no alerting affordance** — nothing may imply someone is paged (design §20, `dynamic-metrics` §16).
**Testing** Route-change focus test; axe on the shell; a test asserting no demo/scenario control renders; a test asserting no notification affordance exists.
**Acceptance** ☐ focus moves to `main` on navigation ☐ tenant is not interactive ☐ no demo control ☐ no bell
**Ready** `READY`

#### FE-037 · Login screen
**Objective** Local sign-in with four failure/arrival states distinguished, and room for one SSO button later.
**Refs** Dec `D13` 🔒 `FE-D24` · Design §14, §15 · UI §A.3
**Deps** 030, 031, 012, 014
**Files** *Create:* `src/features/auth/LoginPage.tsx`, `LoginForm.tsx`, `LoginArrivalAlert.tsx`
**Implementation**
- Centred card on the brand mesh background. Fields: username, password (with reveal). Submit disabled while submitting; **duplicate submission prevented** by disabling and by an in-flight guard.
- States: `idle · submitting · rejected · unreachable`, and arrivals `session-expired` (warn) and `idle-timeout` (info) from the query string (task 031).
- `rejected` renders one error under the password field — *"Username or password is incorrect."* — and **never says which field was wrong** (account enumeration).
- Footer copy states plainly: accounts are created by an administrator; **no self-service sign-up, no password reset**; sessions end after N minutes idle (N from config).
- Layout leaves a slot below the divider for a single *"Sign in with your organisation"* button so SSO drops in without redesign — **the slot is empty in v1** (`D13` deferred).
**Testing** All six states as component tests; axe; e2e login journey; a test asserting the error message does not distinguish user-not-found from bad-password.
**Acceptance** ☐ six states render ☐ no enumeration ☐ no unimplemented SSO control ☐ idle copy matches the mechanism that actually ships (task 031)
**Ready** `FIXTURE`

#### FE-038 · Home
**Objective** *"What needs my attention"* — not an aggregate dashboard; plus the first-run package prerequisite.
**Refs** Req `REQ-001` · Dec `UI-D8` (absence is never zero) · Design §9 · UI §A.4 (home · attention feed)
**Deps** 036, 019, 013, 033
**Files** *Create:* `src/features/home/HomePage.tsx`, `AttentionFeed.tsx`, `StatStrip.tsx`, `FirstRun.tsx`, `homeRepository.ts`
**Implementation**
- Four stat tiles, each a link into a filtered inventory view. **A value that is unknown renders `—`, never `0`** (`UI-D8`). In the partial state a count renders as `11 +1?` with a banner naming the unreporting host.
- Attention feed: severity icon, title, detail, chevron, keyboard-activatable row, deep link carrying `?focus=<instanceId>`.
- Empty feed renders an explicit *"Nothing needs attention"* with the reason — **not a blank panel**.
- First run: the Solution Package prerequisite, counts as `—`, and **one** primary action. Non-super-admins get a stated *"ask your administrator"* alert instead of the action (`UI-D19`).
- Footer line, verbatim in intent: *"Solution Manager surfaces these here. It does not notify or page anyone — no alerting is configured in this release."* (design §20).
**Testing** Five demo states; a test asserting an unknown count never renders `0`; a test asserting the no-alerting line is present.
**Acceptance** ☐ `—` not `0` ☐ empty ≠ error ☐ no-alerting statement present ☐ every feed row deep-links
**Ready** `FIXTURE` — attention feed and stats are **API CONTRACT — TBD** (§16 Q10)

#### FE-039 · Solutions catalog
**Objective** The package cards, including the two things that carry trust weight: provenance and authoring review.
**Refs** Req `REQ-001` `REQ-002` · Dec `UI-D11` `UI-D17` `UI-D19` · Design §9 · UI §A.5
**Deps** 036, 013, 015, 019
**Files** *Create:* `src/features/inventory/solutions/SolutionsPage.tsx`, `SolutionCard.tsx`, `ReviewMeter.tsx`, `solutionsRepository.ts`
**Implementation**
- Card: name, kind, entitlement chip, **provenance** (`Signed · Solace` with the signature fingerprint in a tooltip, vs `Unsigned · dev-mode` with a conspicuous top bar and a disabled Open action), package version, `appliesTo` semver range (`UI-D11`), source, component pills, instance count, telemetry mode.
- **ReviewMeter**: `reviewedRows / totalRows` with three renderings — fully reviewed (ok), partial (warn, and copy saying partial is **normal**), zero (err, *"assembled by automated extraction, unreviewed"*). Fully-unreviewed must not look identical to curated.
- ⚠️ `reviewedRows`/`totalRows` are **not in any package schema** — fixture-only, flagged in the repository interface (§16 Q10).
**Testing** Three review states; the unsigned card cannot navigate; axe.
**Acceptance** ☐ unsigned is conspicuous and non-navigable ☐ 0% ≠ 100% visually ☐ review fields marked TBD in source
**Ready** `FIXTURE`

#### FE-040 · Load-a-package dialog
**Refs** Dec `UI-D19` `UI-D11` · Design §9 · UI §A.5 (load package dialog)
**Deps** 039, 016, 032
**Implementation** Super-admin-only action (**omitted**, not disabled, for everyone else — `FE-D9`). Drop zone + file picker for a signed package; on selection, show the signature-verification result; then Load. An **unsigned package is admitted only in an explicit development mode** and is marked conspicuously wherever it appears afterwards.
**Testing** Non-super-admin sees no control; unsigned path renders the dev-mode treatment; keyboard-operable drop zone.
**Acceptance** ☐ control omitted without the verb ☐ signature result shown before load ☐ dev-mode admission is explicit
**Ready** `FIXTURE` — upload, signature-result and dev-mode admission are **API CONTRACT — TBD** (§16 Q10)

#### FE-041 · Instance inventory grid
**Objective** The table that proves `REQ-005` and `REQ-008`: runtime and locus are **row data**, never a UI branch.
**Refs** Req `REQ-005` `REQ-008` `REQ-002` · Dec `UI-D9` `UI-D12` `SM-D22` `SM-D14` · Design §9, §14 · UI §A.6 · §A.13 (state vocabularies)
**Deps** 020, 013, 033
**Files** *Create:* `src/features/inventory/instances/InstancesPage.tsx`, `columns.tsx`, `ReplicaDetailPanel.tsx`, `instancesRepository.ts`
**Implementation**
- Columns exactly as the prototype: **Instance** (link + org/sub-org), **Env**, **Where it runs** (`driver` + a tooltip stating it is data, not a view), **How it's reached** (`locus` + tooltip), **Health**, **Version**, **Config**, **Replicas**, **Last seen**.
- **Health cell** renders the rollup **plus a per-replica summary line** (`2 healthy · 1 standby`). A `notfound` rollup renders the specific not-found state **and its alert scope** (host / configuration / instance / bootstrap).
- **Version cell** renders state + an editability sentence + `obs … · decl …` in mono. `unverified` says editing is **allowed**.
- **Config cell** renders the state, and for `in_sync` **also** the `effective-unverified` qualifier with the reason (`SM-D22`).
- **Last seen** `never` renders `—` with *"never observed — awaiting its first restart"* (the `architecture.md` §7.1 bootstrap case).
- Detail panel: replica cards with per-replica health and note, plus version and configuration explanations.
- `Declare instance` action present but see task 045.
**Testing** A table-driven test rendering **all 12 fixture instances** and asserting each vocabulary state renders its own label; a test asserting no component branches on `driver` or `locus`.
**Acceptance** ☐ all 5 health, 5 not-found, 4 version, 6 config states render distinctly ☐ zero `if (driver === …)` in the tree ☐ `in_sync` always carries the honesty qualifier
**Ready** `FIXTURE`

#### FE-042 · Inventory URL state, filters, deep-link focus
**Refs** Dec `FE-D6` `FE-D21` · Design §6.2, §14 · UI §A.6 (URL selection · focus)
**Deps** 041, 025
**Implementation** Env filter (`All/DEV/TEST/PROD`), quick search, column visibility, density. **Selection is serialised to `?sel=a,b,c` and focus to `?focus=<id>`** so a link reproduces exactly what the sender saw (`FE-D6`). A `?focus=` row is expanded and highlighted on arrival and receives focus. Filter and sort state are component state; **selection and focus are URL state** — the distinction is the point.
**Testing** URL round-trip: select → reload → same selection; `?focus=` scrolls, expands and focuses.
**Acceptance** ☐ selection survives reload ☐ deep link from the attention feed lands on the right row
**Ready** `READY`

#### FE-043 · Bulk restart confirmation
**Refs** Dec `SM-D15` `SM-D22` `FE-D18` clause B (**blocked**) · Design §10.1 · UI §A.6 (bulk restart dialog)
**Deps** 041, 016
**Implementation** Enabled only with a selection. The dialog states, per instance: name, replica count, expected unavailability, and the rule *"writing configuration is not the same as applying it."* Closing copy: *"Restart is operator-initiated. The console confirms only that it wrote config and observed a restart — never that the process is using it."*
⚠️ **The action itself does not execute in v1.** Multi-instance apply has no protocol and single-instance apply is `APPLY_INDETERMINATE` by default (C11). Ship the **dialog and the copy**; wire the call only when open item 1 closes.
**Testing** The dialog renders per-instance detail; a test asserting no success message is emitted.
**Acceptance** ☐ never reports success ☐ per-instance consequence stated
**Ready** `BLOCKED` (open item 1) for execution; `FIXTURE` for the dialog

#### FE-044 · Instance context header and category tabs
**Refs** Design §9, §14 · UI §A.7
**Deps** 018, 021, 013
**Implementation** Header: instance name, env chip, health rollup chip (with an info tip when health is `not-declared`), a **version-drift badge saying "Designer read-only"** when drift blocks editing, and the **persistent outcome badge** with its correlation id. When drift blocks Designer, a line states that **Governance and Monitoring still work** — two of three categories remain available. Tabs `Designer | Governance | Monitoring` as route links.
**Testing** Drift state renders the badge and the two-of-three sentence; the outcome badge survives a route change within the instance.
**Acceptance** ☐ blocked Designer does not read as a dead instance ☐ outcome persists on the resource
**Ready** `FIXTURE`

#### FE-045 · Declare-instance stub and the 403 page
**Refs** Dec `SM-D13` `D10` 🔒 · Design §14, §15 · UI §A.6 · §A.12 (targets preview)
**Deps** 041, 016, 032
**Implementation**
- **Declare instance** has no API, no field set and no entitlement-check shape. Ship the **action** with a dialog that states what is not yet specified, rather than an invented form (the prototype's own posture on unspecified surfaces). Do not invent fields.
- **403 page — DECIDED in this plan (proposed `FE-D27`, §16.1).** It states that the action is not permitted, shows the **server** correlation id, and offers a route back. It **does not name the missing permission**: naming it tells an unauthorised caller which verb to acquire, and the recovery path (ask an administrator, quote the correlation id) is identical either way. If a security review later permits naming it, that is a copy change in one component.
**Testing** Component: the rendered DOM contains no permission id from any fixture; the correlation id is present; the declare dialog renders the unspecified-surface notice and no form fields.
**Verify** `npm run lint && npm run typecheck && npm test -- src/features/inventory --environment jsdom`
**Acceptance** ☐ no invented declare form ☐ 403 leaks no permission name ☐ correlation id shown
**Ready** `FIXTURE` — Q11 decided here; the declare-instance **endpoint** stays TBD (Q10) and the task ships the honest stub, not a form.
⚠️ **The declare half of this task is SUPERSEDED by `FE-115`.** Q10 is still open — what changed is the decision, not the spec: the console now renders a form for the instance's **identity** fields and states on its face that no API backs it. The 403 half of this entry stands unchanged. Read `FE-115` before treating "no invented declare form" as current.

### Phase 4 — Designer

#### FE-046 · View-model types, `project()`, shared fixture runner
**Objective** The `UI-D15` engine in TypeScript, proven against the same JSON fixtures the Java implementation runs.
**Refs** Req `REQ-001` · Dec `UI-D2` `UI-D3` `UI-D4` `UI-D15` `UI-D16` · Design §10 · UI §A.8 (view-model shapes · applicability)
**Deps** 008
**Files** *Create:* `contracts/view-model/types.ts`, `contracts/view-model/project.ts`, `contracts/fixtures/view-model/*.json`, `contracts/fixtures/README.md`, `src/test/contracts.test.ts`
**Implementation**
- Signature: `project(pkg, componentSelection, draftValues, observedState) → ViewModel` where `ViewModel = { sections[], graphs[], status }` and a field is `{ key, controlKind, value, options, enabled, visible, masked, advanced, helperText, validations[] }` (design §10 / `dynamic-ui` §6.5).
- The obligations, each a fixture case:
  1. `when:` evaluates over **defaults-applied** values — `matchIfMissing` is resolved by the field's `default` before the predicate runs (`UI-D2`).
  2. Predicates are **equality, implicitly AND-ed**. No OR, no negation, no comparison.
  3. Fields are a **flat list carrying the whole ancestor chain** (`UI-D3`) — one evaluation against the value map, no tree walk.
  4. Changing a discriminator **re-evaluates all descendants**, collapsing deeper branches.
  5. **Inactive branches keep their draft values** but **only visible fields render into config** (`UI-D4`).
  6. `advanced: true` is a disclosure flag, not a visibility rule.
  7. `secret: true` is masked in form, diff, export and history (`I6`).
  8. `VERSION_DRIFT` / `VERSION_UNKNOWN` **block editing with a stated reason**; `VERSION_UNVERIFIED` **does not** (`UI-D12`).
  9. `project()` is **auth-free** — RBAC is a second overlay (`FE-D22`, task 059/032).
- Pure: no React, no DOM, no HTTP, no clock. `contracts/fixtures/README.md` **pins JSON number formatting and key ordering**, or the comparison itself becomes the divergence (`UI-D15`).
**Testing** The fixture suite is the test. Minimum cases: defaults-applied predicate, three-deep discriminator chain, branch switch and switch-back, hidden-key retention, advanced flag, secret masking, each version state.
**Acceptance** ☐ `contracts/` imports nothing from `src/` ☐ every obligation has a named fixture ☐ the suite is runnable by the Java CI (same files)
**Ready** `FIXTURE` — the package schema is `⚠️ partially specified` (`contracts/README.md`); fixture-drive it and mark the gaps

#### FE-047 · Control registry
**Objective** `controlKind → component`, so the same code renders every solution. Adding a **control** is an SM release; adding a **field** is data (`UI-D14`).
**Refs** Dec `UI-D14` `UI-D22` `UI-D23` `FE-D23` · Design §10 · UI §A.8 (control kinds)
**Deps** 012, 023, 046
**Files** *Create:* `src/features/designer/controls/registry.ts` + one component per kind
**Implementation**
- Registry entries: `text` `number` `select` `switch` `duration` `secret` `string-list` `key-value` `table` `yaml`.
  - `number` → numeric input mode + bind validation.
  - `duration` → text with a canonical-unit helper (`Spring Duration · 120m · PT2H`).
  - `secret` → `SecretField` (task 023).
  - `string-list` / `key-value` → repeating rows with add/remove, each row's controls labelled.
  - `table` → columns declared by the package (each column a scalar field declaration, `UI-D22`); render the "declared by the package" placeholder where no table field exists in the fixture.
  - **`yaml` → a gated, non-editing placeholder** stating the raw editor is disabled in this release (`FE-D23`, C10). **It must not render an editor.**
- Options come from static `options` **or** an `optionsSource: { kind: sm-catalog, entity }` — a package can never supply a URL (`UI-D22`).
- The registry is exhaustive over the `controlKind` union at the **type** level, so a new kind is a compile error rather than a blank field.
**Testing** One test per kind; a test asserting `yaml` renders no editable surface; a type test asserting exhaustiveness.
**Acceptance** ☐ no per-solution control ☐ `yaml` cannot be edited ☐ missing kind fails typecheck
**Ready** `READY`

#### FE-048 · FieldRow
**Refs** Dec `UI-D4` `UI-D16` `UI-D17` `I6` · Design §10, §18 · UI §A.8 (field row)
**Deps** 047
**Implementation** Label · required marker · **discriminator hint** (*"controls fields below"*, with a tip explaining hidden-branch retention) · rendered path (mono, when the solution is single-component) · `Default` hint · `Modified` chip · the control · helper text · validation messages. A **`virtual` field (no rendered path) renders identically to a real one — no badge, no visual tell** (the prototype states this as deliberate). A warn message appends *"a Solace recommendation, not a hard limit — you can proceed"* so the two tiers are unmistakable (`UI-D16`).
**Testing** Modified/Default states; a virtual field is visually indistinguishable; a blocking validation sets `aria-invalid` and is referenced by `aria-describedby`.
**Acceptance** ☐ virtual fields have no tell ☐ block vs warn distinguishable by text, not colour ☐ discriminator hint present
**Ready** `READY`

#### FE-049 · SectionCard and advanced disclosure
**Refs** Dec `UI-D15` (advanced obligation) · Design §10 · UI §A.8 (section card)
**Deps** 048
**Implementation** Accordion header with title, applicable-field count, an `Advanced` chip for all-advanced sections, and **block/warn count chips**. Body renders normal fields, then a `Show N advanced fields` toggle. When a discriminator hides everything: *"No fields apply here with the current selections"* — never an empty card. **The advanced toggle is required, not polish**: all 1,989 File Mesh rows carry the flag and a 132-field FTP component is unusable without it (design §10).
**Acceptance** ☐ counts reflect only **applicable** fields ☐ empty section states why ☐ advanced collapsed by default
**Ready** `READY`

#### FE-050 · Section navigation and jump
**Refs** Design §10 · UI §A.8 (section nav · jump)
**Deps** 049
**Implementation** Left rail listing components (multi-component only) and their sections, each with a field count or a block/warn dot. Clicking expands the section and scrolls the first field into view. Keyboard-operable; the target field receives focus, not just scroll (design §18).
**Acceptance** ☐ jump moves focus, not only scroll ☐ dots reflect live validation
**Ready** `READY`

#### FE-051 · Field search
**Refs** Design §10, §17 · UI §A.8 (field search)
**Deps** 049
**Implementation** Free-text over label, key, rendered path and section, **across all components**, debounced (free text only). Results render as flat `FieldRow`s with a component/section crumb. Only **applicable** fields are searchable — a hidden branch is not a search hit. Result count announced politely.
**Acceptance** ☐ hidden-branch fields are not returned ☐ count announced ☐ debounced only here
**Ready** `READY`

#### FE-052 · Draft form protocol (React Hook Form)
**Objective** The `FE-D17` rule, implemented once: **RHF holds the whole draft; the save payload is the rendered set from `project()`.**
**Refs** Dec `FE-D17` `UI-D4` · Design §10 · UI §A.8 (draft state)
**Deps** 046, 049
**Files** *Create:* `src/features/designer/form/useDesignerDraft.ts`, `mergeReset.ts`
**Implementation**
- RHF with `shouldUnregister: false` so **inactive-branch values are retained**.
- On a discriminator change: re-run `project()`, then **`reset` by merge** — new/derived values applied, **hidden keys preserved**. A plain `reset` would drop them and is a defect.
- Dirty tracking is a **deep comparison against the loaded baseline**, so the save bar's *"N unsaved changes"* is accurate.
- **`getValues()` is never the payload.** The payload is derived from the `project()` view model's rendered set (task 055). Enforce with a lint rule forbidding `getValues` outside this module.
**Testing** Switch a discriminator away and back — the original values return. Assert the save payload for a config with a hidden branch **does not contain** the hidden keys.
**Acceptance** ☐ hidden keys retained in draft ☐ hidden keys absent from payload ☐ `getValues` used in exactly one module
**Ready** `READY`

#### FE-053 · Validation tiers
**Refs** Dec `UI-D16` · Design §10, §16 · UI §A.8 (live validation)
**Deps** 052
**Implementation** Two tiers, never merged: **bind blocks save**, **semantic warns and can be overridden with a recorded reason**. Validations are evaluated **live** so `REQUIRED` clears as the operator types and a threshold warning tracks the value. Validation rules come from the package via `project()`; **no business rule is hard-coded in the console** — this is the `REQ-001` line.
**Testing** A required field clears on input; a warn does not block; a block does; no rule literal exists outside a fixture.
**Acceptance** ☐ zero hard-coded business rules ☐ live re-evaluation ☐ warn never blocks
**Ready** `READY`

#### FE-054 · ValidationSummary
**Refs** Design §10, §18 · UI §A.8 (validation summary)
**Deps** 053, 050
**Implementation** A form-level alert with a chip per offending field; clicking a chip jumps to it and focuses it. Header states the count and distinguishes *"must be fixed before saving"* from *"recommendations to review"*. Renders as `role="alert"` only when blocking.
**Acceptance** ☐ every chip navigates and focuses ☐ blocking vs advisory unambiguous in text
**Ready** `READY`

#### FE-055 · Save flow, save bar, config generation
**Objective** `FE-D18` **clause A** — the unblocked half.
**Refs** Dec `FE-D17` `FE-D18` (A) `UI-D4` · Design §10.1, §13 · UI §A.8 (save bar)
**Deps** 052, 053, 026
**Implementation**
- Sticky save bar: dirty count, blocking count, recommendation count, **Save draft** and **Apply…**. Save is disabled when blocking > 0 or dirty == 0; **Apply is disabled until saved** — save and apply are two different things and never collapse.
- Payload = the **rendered field set from `project()`** plus the **last-seen configuration-of-record generation** (`FE-D18` clause A). The generation field name is **API CONTRACT — TBD** (§16 Q8) — it is a cheap, unblocked naming question, not the blocked apply protocol.
- Success copy states exactly what happened: *"Draft saved to the platform record. This wrote nothing to the MI — use Apply for that."*
- **No optimistic cache update** (`FE-D20`).
**Testing** Payload excludes inactive branches; payload includes the generation; save with blocking errors is impossible; the success message does not imply an MI write.
**Acceptance** ☐ generation on every save ☐ save ≠ apply in copy and control ☐ no optimism
**Ready** `FIXTURE` — generation **field name** is TBD (Q8); the mechanism is unblocked

#### FE-056 · `409` conflict → refetch and diff
**Refs** Dec `FE-D18` (A) `I6` · Design §10.1, §16 · UI §A.8 (conflict dialog)
**Deps** 055, 022
**Implementation** On `409`: **no silent overwrite**. Show a dialog explaining that another edit landed, render a `DiffView` of *your draft → current record* with secrets masked, and offer **Refetch & review** as the only enabled path.
⚠️ **Copy the prototype's honesty exactly:** the contract specifies **no** conflict resolution — no "overwrite anyway", no "take theirs", no per-field merge, and **no actor or timestamp** to say who conflicted or when. Render those three buttons **disabled with a stated "not specified" note**, or omit them; do not implement an invented resolution.
**Testing** A `409` never mutates local state silently; the three resolution controls are non-functional and labelled; secrets masked in the conflict diff.
**Acceptance** ☐ no invented resolution ☐ refetch-and-reapply is the only working path ☐ secrets masked
**Ready** `FIXTURE` — `409` body shape is **API CONTRACT — TBD** (§16 Q9)

#### FE-057 · Warning override with recorded reason
**Refs** Dec `UI-D16` · Design §10, §16 · UI §A.8 (override reason dialog)
**Deps** 053, 055, 016
**Implementation** Saving with semantic warnings opens a dialog listing each warning and requiring a free-text reason (minimum a few words) that is **recorded in configuration history alongside the change** (`UI-D16`). Copy makes clear these are recommendations the MI will accept, not hard limits.
**Testing** Save is blocked until a reason is entered; the reason accompanies the save payload.
**Acceptance** ☐ override is impossible without a reason ☐ reason travels with the change
**Ready** `FIXTURE` — history/override wire shape **TBD**

#### FE-058 · Package-declared actions
**Refs** Dec `UI-D22` `I6` `FE-D23` · Design §10 · UI §A.8 (package actions)
**Deps** 047, 026
**Implementation** Render the package's `actions[]` — closed vocabulary `test-connection · browse-remote-path · refresh-catalog`. **Each executes server-side only and returns a verdict, never a credential** (`UI-D22`, `I6`); the copy says so on screen. The action names its inputs; the browser sends field **references**, never a resolved secret. Disabled while editing is blocked.
**Testing** A test asserting no action request body can contain a resolved secret (type-level), plus a component test of the verdict rendering.
**Acceptance** ☐ server-side only ☐ verdict only ☐ inputs named on screen
**Ready** `FIXTURE` — action invocation route **TBD**

#### FE-059 · Version states, blocked banner, accept/restore
**Objective** The `architecture.md` §7.2 correction paths, with the field-level diff doing the load-bearing work.
**Refs** Dec `UI-D12` `SM-D14` (superseded on this point) `FE-D22` · Design §10, §15 · UI §A.8 (version pill · blocked banner · accept runtime)
**Deps** 046, 022, 016
**Implementation**
- `VersionPill`: `OK` · `VERSION_DRIFT` · `VERSION_UNKNOWN` · `VERSION_UNVERIFIED`, each with `decl`/`obs`.
- **`UNVERIFIED` does not block editing** and must not be styled as a fault — it is the permanent-normal state for an MI on a plain VM (`UI-D12`).
- `DRIFT`/`UNKNOWN` render the blocked banner: *"read-only, not unavailable"* — **the configuration stays fully legible** — with two routes out.
- **Accept the runtime**: re-render the declaration against the observed version's schema, with the diff classified **four ways** — added/defaulted · removed · type-or-constraint changed · **renamed → needs mapping**. An unmapped rename must block completion, because it silently drops a value.
- **Restore the runtime**: a high-consequence confirm stating it redeploys the declared version and causes an outage.
- **RBAC is applied after `project()`**, never inside it (`FE-D22`): `viewModel = project(...)`; `chrome = overlayRbac(viewModel, permissions)`.
**Testing** Each version state; `UNVERIFIED` is editable; an unmapped rename blocks; the blocked banner does not hide the config.
**Acceptance** ☐ four states ☐ blocked ≠ hidden ☐ unmapped rename cannot complete ☐ RBAC overlays, never mutates, the view model
**Ready** `FIXTURE`

#### FE-060 · Rollout card: config stepper and replica table
**Objective** Make `restart-observed ≠ convergence` structurally visible.
**Refs** Dec `SM-D22` `UI-D9` · Design §10.1 · UI §A.8 (config stepper · replica table)
**Deps** 013, 020
**Implementation**
- **Five-state stepper**: `desired → rendered → persisted → restart-observed → effective-verified`. For a solution with no protected config endpoint, **the fifth stays permanently unreached**, with the reason stated and the outcome named `APPLY_INDETERMINATE`. The stepper must be **incapable** of marking `effective-verified` reached without an explicit verified signal.
- **Per-replica table**, never a single spinner (design §10.1): replica · rendered config version · restart observed · health. Divergent generations are visually marked. Both column names carry an info tip saying they are **invented** — the contract names them in prose with no row schema (§16 Q12).
**Testing** A test asserting the fifth rung cannot render as done from any fixture in the suite; a divergent-generation fixture highlights the odd replica.
**Acceptance** ☐ convergence never claimed ☐ per-replica detail, not a spinner ☐ invented column names flagged in the UI
**Ready** `FIXTURE`

#### FE-061 · Apply: strategy picker, restart confirm, `APPLY_INDETERMINATE`
**Objective** The design's hardest constraint, implemented honestly: **the walking skeleton runs on `dp-remote`, which has no apply-ordering guarantee.**
**Refs** Dec `SM-D15` `SM-D22` `FE-D18` (B **blocked**) `FE-D20` · Design §10.1, §16, §25 · `architecture.md` §9, §13 · UI §A.8 (strategy picker · restart confirm)
**Deps** 060, 016, 032
**Implementation**
- **StrategyPicker** with the suppression rules as *rules*, not copy:
  - `manual` — **default** (`SM-D15`). Stage config everywhere, then wait.
  - `rolling-health-gated` — offered **only** if the package declares a health mapping **and** `mustBeUniformAcrossReplicas` is not true. For Cache it is **unavailable** and the reason is stated: a standby reports healthy while processing nothing, so a health gate would advance past a replica that took no traffic (open item 15).
  - `rolling-no-validation` — high-consequence; **confirm even when authorised**.
  - `restart-all` — full-outage confirm.
  - `scheduled` — **out of this release**, shown disabled with the reason.
  - When `mustBeUniformAcrossReplicas` is true, **both rolling strategies are suppressed** and the picker says why.
  - Footer states the default multi-replica ordering: **stage everywhere, then restart** — a halt leaves config uniform-but-unapplied.
- **RestartConfirm** copy is prescribed by design §10.1: *"requires restart · N replicas · expect unavailability · continue?"* plus the restart-observed caveat.
- **Outcome handling — the load-bearing part.** A `dp-remote` apply resolves to **`APPLY_INDETERMINATE` by default**. The UI **must not** derive success or failure from the HTTP response. It resolves the outcome from **observed state** (staged generation + per-replica `restart-observed`), blocks further applies until the operator confirms actual, and offers **operator-initiated retry only** — an automatic retry without an idempotency key is a second write.
**Testing** e2e: an apply that times out renders `APPLY_INDETERMINATE`, **not** success and **not** failure (design §19 names this test explicitly). Unit: each suppression rule; `rolling-health-gated` unavailable for the Cache fixture.
**Acceptance** ☐ no success claim from a response ☐ retry is operator-initiated ☐ every suppression states its reason ☐ default is `manual`
**Ready** `BLOCKED` (open item 1) for the wire call; `FIXTURE` for the full UX incl. the indeterminate outcome

#### FE-062 · `CONFIG_DIVERGED` and rollback with a diff preview
**Refs** Dec `B/D12` (config-only rollback) `SM-D22` · Design §10.1 (R2 finding 5) · `architecture.md` §9 · UI §A.8 (diverged banner · rollback)
**Deps** 060, 022, 016
**Implementation** Banner: *"Replicas are holding different config generations after a halted rollout. Pick one remediation — there is no 'force healthy'."* Three remediations: **re-stage everywhere**, **restart the remaining**, **roll back to the last uniform generation**. The rollback **must render a `DiffView` against the currently-staged generation before the operator confirms** — R1 finding 5 corrected exactly this: a rollback is a config change like any other and gets no lighter confirmation for being a revert.
**Testing** No "force healthy" affordance exists; rollback cannot be confirmed without the diff rendering.
**Acceptance** ☐ three remediations, no fourth ☐ rollback shows the diff first ☐ replica table visible alongside
**Ready** `BLOCKED` (open item 1) for execution; `FIXTURE` for the UX

#### FE-063 · Composer canvas (multi-component solutions)
**Refs** Dec `UI-D20` `UI-D21` (flagged) `D1` 🔒 · Design §10 · UI §A.8 (composer · inspector)
**Deps** 049, 052
**Implementation** Three panes: **palette** generated from the package's declared component types (*never* a hard-coded FTP/SFTP/S3 list), **canvas** with draggable nodes and a derived source→sink link, and an **inspector** rendering the selected component's sections through the same `SectionCard`/`FieldRow` components. A node shows its field count and its required-but-empty count. **Node positions are presentation only** — moving a node is not a config change, does not prompt a restart, and is **excluded from the config hash** (`UI-D20`); the canvas says so.
⚠️ **Keyboard is not optional.** A pointer-only canvas fails AA. Provide node selection and reordering from the keyboard, and a **list view of the same components** as the non-pointer path (the prototype's *All fields* toggle is that view — keep it).
⚠️ The **named-screen registry exists and stays empty** in v1 (`D1` vs `UI-D21`, C8). No package-supplied code path.
**Testing** Palette is derived from fixture data; moving a node does not mark the form dirty; full keyboard path exists; the registry is empty and no loader exists.
**Acceptance** ☐ layout excluded from dirty state and hash ☐ keyboard-complete ☐ no package-supplied module loader
**Ready** `FIXTURE` — Phase 2 feature (File Mesh); build behind a package-capability check

### Phase 5 — Governance

#### FE-064 · Health hero and replica cards
**Objective** Answer one question — *is the endpoint up?* — and refuse to answer any other.
**Refs** Req `REQ-009` · Dec `UI-D9` `I1` · Design §9 · `dynamic-ui` §5.1 · UI §A.9
**Deps** 044, 013, 015
**Files** *Create:* `src/features/governance/GovernancePage.tsx`, `HealthHero.tsx`, `ReplicaCards.tsx`, `governanceRepository.ts`
**Implementation**
- Hero: large state badge, rollup label, and an explanatory paragraph **per rollup**:
  - `GREEN` — the endpoint reports up; *the platform does not judge whether the MI is actually working; if it reports healthy while malfunctioning that is the MI's defect*.
  - `RED` — *the health endpoint is not answering* → **primary CTA "Open in Monitoring"**, because the workflow is **red badge → open the graphs → no data confirms it** (`UI-D9`).
  - `RESTART_EXPECTED` — a platform-initiated restart; **expected activity, not an outage**.
  - `INACTIVE_REPLICA` — every replica is a standby: up and deliberately processing nothing; **zero KPIs are expected**, not a fault.
  - `NOT_APPLICABLE` — the solution declares no health block; **never red, never an outage**.
- **No synthetic health score, no composite gauge** — an explicit non-goal (`dynamic-ui` §5.1). Add a comment saying so, so a future PR does not "improve" it.
- Replica cards: per-replica state and note; a standby card is visually distinct and its note says it is **excluded from availability math and is not a healthy zero**.
**Testing** All five rollups; `NOT_APPLICABLE` never uses the error tone; the `RED` CTA is primary; no aggregate score is computed anywhere.
**Acceptance** ☐ five rollups ☐ standby ≠ healthy ☐ no synthetic score ☐ red routes to the graphs
**Ready** `FIXTURE` — health read route **TBD**

#### FE-065 · Configuration-state ladder
**Refs** Dec `SM-D22` `UI-D15` · Design §10.1 · UI §A.9 (config-state ladder)
**Deps** 064
**Implementation** Five rungs — `Desired · Rendered · Persisted · Restart-observed · Effective-verified` — each with a plain-language note. The last rung renders as **honestly unknown**, with an `effective-unverified` chip and the reason: *the MI exposes no protected config endpoint, so the running process is permanently effective-unverified — an honest gap, not a pass.* Closing alert: *a completed restart reaches restart-observed, never convergence.* Same rule as task 060: the component cannot mark the fifth rung reached without an explicit verified signal.
**Acceptance** ☐ five rungs, never collapsed ☐ fifth rung honest ☐ shares the state source with task 060
**Ready** `FIXTURE`

#### FE-066 · Log viewer
**Refs** Dec `UI-D10` `I6` `I1` · Design §9 · `dynamic-ui` §5.2 · UI §A.9 (log viewer)
**Deps** 015, 016, 021
**Implementation**
- Component selector (from the package's declared components), level selector `ERROR/WARN/INFO/DEBUG`, Stream/Pause, a monospace viewport that auto-scrolls and holds a bounded buffer.
- **Raising to DEBUG is privileged and audited** (`UI-D10`): a confirm dialog stating it is verbose, can affect performance on a live process, and that **the change and the operator's identity are written to the audit log**; a live `DEBUG · audited` chip while active.
- **Degradation, not an error:** where a component exposes no log endpoint, render a stated *"this component exposes no log endpoint"* alert — *"a stated degraded condition — not an error, and not an empty stream implying silence"* (`I1`'s "optional with a defined degradation").
- **Log content is streamed on request and never persisted** — not to a store, not to a cache, not to `localStorage`, not to any log (`UI-D10`, `I6`).
**Testing** A test asserting no log line reaches any persistence API; the no-endpoint state is not an error state; DEBUG requires confirmation.
**Acceptance** ☐ nothing persisted ☐ degradation stated ☐ DEBUG confirmed and marked
**Ready** `FIXTURE` — console↔backend log route is **API CONTRACT — TBD** (§16 Q13)

#### FE-067 · Governance states and accessibility pass
**Refs** Design §16, §18 · UI §A.9 (states)
**Deps** 064–066
**Implementation** Loading skeletons at content dimensions; an **error state distinct from "not responding"** — *"the console reached the server but the status query failed"*; the not-declared state. Full keyboard traversal; the log viewport is a labelled region and its updates are **not** announced on every line (that would flood a screen reader) — announce start/stop only.
**Acceptance** ☐ error ≠ down ☐ log stream does not spam live regions ☐ axe clean in both themes
**Ready** `FIXTURE`

### Phase 6 — Monitoring

#### FE-068 · MetricsProvider and capabilities (fail closed)
**Refs** Req `REQ-007` · Dec `DM-D10` `FE-D20` · Design §11 · `dynamic-metrics` §5.4, §17
**Deps** 027, 029
**Files** *Create:* `src/features/monitoring/MetricsProvider.tsx`, `useCapabilities.ts`, `capabilityDefaults.ts`
**Implementation**
- `GET {console}/capabilities` — **a specified route** (`dynamic-metrics` §5.4). Response carries `tiers[]` (name, retention, minBucketSeconds), `maxRange`, `maxSeriesPerWidget`, `maxPointsPerSeries`, `marks[]`, `aggregations[]`, `features{forceRaw, streamingExport, pngExport}`.
- **The UI hardcodes no preset, no cap and no tier.** Presets are **derived from `tiers`** and filtered to what is advertised.
- **Fail closed** on fetch failure: series cap 10, presets to 24h, **no forced-Raw**, and a banner stating the UI is running on conservative defaults (`dynamic-metrics` §17).
- **`REQ-007` copy rule:** a retention window is rendered as **advertised**, never as assured. A "6 months" preset carries the advertised-retention caveat.
**Testing** Capabilities failure produces exactly the fail-closed set; no preset literal exists outside `capabilityDefaults`; a test asserting no string promises guaranteed retention.
**Acceptance** ☐ zero hardcoded presets/caps ☐ fail-closed proven ☐ retention never presented as assured
**Ready** `READY` — route specified

#### FE-069 · Catalog queries
**Refs** Dec `SM-D30` `DM-D10` `UI-D5` · `dynamic-metrics` §5.5 · UI §A.10 (metric catalog)
**Deps** 068
**Implementation** `entity-types`, `entity-types/{t}/instances`, `metrics/catalog`, `labels/{k}/values` — all **specified routes**. A catalog entry carries `name, metricType, unit, layer (package-baseline | user-extension), state (plottable | needs-typing | type-suspect), lastSeen, labelKeys, durability, suspectReason?`. Three states, three renderings, **none of them silence**.
⚠️ The prototype flags that the entry has **no `firstSeen` and no suggested-type field**, so no type suggestion can be offered — record as §16 Q14, do not invent one (`SM-D30` forbids inferring a type).
**Acceptance** ☐ all three states render distinctly ☐ no inferred type ☐ missing fields flagged in source
**Ready** `READY` — routes specified; two response fields flagged as missing

#### FE-070 · `buildQuery` and the canonical query hash
**Refs** Dec `DM-D1` `DM-D2` `DM-D23` · `dynamic-metrics` §5.6, §10
**Deps** 068
**Files** *Create:* `contracts/metrics/buildQuery.ts`, `contracts/metrics/canonicalHash.ts`
**Implementation** `buildQuery(widgetConfig, timeRange, capabilities) → QueryRequest`. Canonicalisation: **key order normalised, instance ids sorted, relative ranges resolved to absolute at issue time**. The hash is the TanStack `queryKey`, so **two widgets asking the same question issue one request** and identical in-flight keys share one promise (`dynamic-metrics` §10). `queryVersion` is always present in the body (`DM-D2`). **Exactly one metric per request** — a list of metric names is a malformed request (`400`), not a business refusal (`DM-D23`).
**Testing** Two differently-ordered but semantically identical configs hash equal; a dashboard of 8 widgets over 4 metrics issues 4 requests.
**Acceptance** ☐ hash is order-insensitive ☐ dedup demonstrated ☐ one metric per request enforced by type
**Ready** `READY`

#### FE-071 · `projectSeries` and its shared fixture suite
**Refs** Dec `DM-D20` `DM-D16` `UI-D8` `SM-D26` `SM-D27` · `dynamic-metrics` §9.4, §5.6
**Deps** 008
**Files** *Create:* `contracts/metrics/projectSeries.ts`, `contracts/metrics/types.ts`, `contracts/fixtures/metrics/*.json`
**Implementation**
- `projectSeries(queryResponse, widgetConfig, theme) → ChartModel`. Pure: data in, data out.
- Rules it must implement, each a fixture case:
  1. **A bucket with no samples produces no point** — never `null`, never `0` (`UI-D8`, `DM-D16`).
  2. **Gaps are server-declared** via `gaps[]`; the client does not re-derive the threshold. The line **breaks**; it is never connected across.
  3. **No delta is computed across a gap** — a restart both gaps and resets a counter, so a delta there is a false spike.
  4. **Counters render as rate or delta**, from the server's `render.transform`, **never as a raw monotonic climb**.
  5. `sampleCount < expectedCount` marks the point **low-coverage** (a distinct marker), required at the raw tier too.
  6. `coverage.truncatedByRetention` yields a boundary and a band, and the model carries the served-from timestamp.
  7. Per-series `state` — `ok · inactive-replica · pending-config-change · type-suspect · unverified-durability` — is carried through to tooltip and legend.
  8. **Colours are sequential within a chart, never hashed from an instance id**; past palette size, the second channel is **dash pattern**, not a reused colour.
- `contracts/fixtures/README.md` pins number formatting and key ordering (shared with task 046).
**Testing** The fixture suite: gap cases, counter-rate cases, replica-aggregation cases, schema-migration cases, low-coverage, retention truncation, inactive replica.
**Acceptance** ☐ no fixture produces a zero-filled point ☐ colours never collide within a chart ☐ suite runnable by the Java CI
**Ready** `READY`

#### FE-072 · ChartRenderer core
**Refs** Dec `DM-D13` `FE-D16` `D7` 🔒 · Design §11, §17 · `dynamic-metrics` §12
**Deps** 003, 071
**Files** *Create:* `src/features/monitoring/chart/ChartRenderer.tsx`, `useEcharts.ts`
**Implementation**
- **The only module that may import `echarts`** (`FE-D16`, enforced in task 009). A thin in-tree adapter — **do not adopt an unmaintained wrapper package** (design §5).
- **Dynamic `import()`** so ECharts is not in the initial bundle (design §17).
- `canvas` renderer with `large: true` and `progressive` above a point threshold — 10 series × ~1000 points is where SVG DOM cost shows.
- Theme from `echartsTheme(muiTheme)` (task 003); re-themed on mode change without remounting the instance.
- Resize via `ResizeObserver`; dispose on unmount; **`prefers-reduced-motion` disables progressive animation** (design §18).
**Testing** A test asserting `echarts` appears in no other module's import graph; a build test asserting it is a separate chunk; a reduced-motion test.
**Acceptance** ☐ single import site ☐ async chunk ☐ theme is derived, not duplicated ☐ motion respected
**Ready** `READY`

#### FE-073 · Gaps, coverage, thresholds, annotations
**Refs** Dec `UI-D8` `DM-D16` `SM-D31` · `dynamic-metrics` §11.2, §12 · UI §A.10 (series path · gaps)
**Deps** 072
**Implementation** Absent points fed as absent with `connectNulls: false`; the server's `gaps[]` drives shaded coverage bands; low-coverage points render as **hollow markers**; retention truncation renders a band plus a dashed boundary and a notice; thresholds are `markLine` carrying **both** colour **and** dash pattern (never colour alone); annotations (e.g. *"replica added (+1) — summed series steps; not a workload change"*) render as vertical markers with an accessible label.
⚠️ **Log scale is disabled for counters under `transform: rate`** — a counter rate is legitimately zero when idle, and a log axis silently drops every non-positive point. The control is **disabled with the reason stated**. For gauges it remains available and non-positive values are excluded **with a visible notice**, never silently.
**Testing** A gap fixture renders a break, asserted on the ECharts option object, not a screenshot; log scale is unavailable for a counter-rate fixture.
**Acceptance** ☐ no zero-fill, no connect-across, in any path ☐ thresholds carry two channels ☐ log scale restricted with a stated reason
**Ready** `READY`

#### FE-074 · Tooltip and legend
**Refs** Dec `DM-D13` `DM-D7` `SM-D27` · `dynamic-metrics` §12 · UI §A.10 (tooltip · legend)
**Deps** 073
**Implementation**
- **Shared-axis tooltip** listing every series at the cursor, **sorted by value**, each row carrying a colour swatch, the formatted value, and the **per-series state** (`gap · inactive replica · pending · type-suspect · best-effort`). Without the state in the tooltip, careful gap rendering stops at the line and never reaches the number the operator reads.
- A series with no point at the cursor renders **`gap`**, not a blank and not a zero.
- **The legend scrolls; it never truncates** — `maxHeightRows` bounds height, not membership. Plotting a series with no legend entry leaves a line unidentifiable, which defeats the overlay.
**Acceptance** ☐ tooltip carries state ☐ missing point reads `gap` ☐ legend membership never reduced
**Ready** `READY`

#### FE-075 · Chart accessibility
**Objective** Close the design's stated residual: a canvas chart is otherwise unreachable by keyboard.
**Refs** Design §18 · `dynamic-metrics` §18
**Deps** 072–074
**Files** *Create:* `src/features/monitoring/chart/ChartDataTable.tsx`, `chartSummary.ts`
**Implementation**
- The chart is **one focus stop**. `←`/`→` move a cursor along the time axis, `↑`/`↓` change the focused series, `Enter` opens the **tabular alternative**.
- `ChartDataTable` is a real, keyboard-reachable table of the same `ChartModel`, plus a **text summary**: metric, range, series count, min/max/last per series.
- `aria-label` from the widget title; ECharts `aria` enabled with series names taken from the same labels the legend uses.
- Record the residual honestly: canvas charts will still fail some screen-reader chart-exploration heuristics; the mitigation is the table, not a claim that ECharts is accessible.
**Testing** Keyboard test for all four key behaviours; the data table contains every series and every rendered point; axe on both.
**Acceptance** ☐ chart reachable and explorable by keyboard ☐ tabular alternative for every chart ☐ no false accessibility claim in docs
**Ready** `READY`

#### FE-076 · Single-stat and gauge marks
**Refs** Dec `DM-D12` `DM-D22` `SM-D31` · `dynamic-metrics` §7 · UI §A.10 (gauge · single-stat)
**Deps** 071, 015
**Implementation** `mark` is the **sole visualisation axis**, closed at `line | gauge | single-stat`; `fill` ("area") is an **option on `line`, not a vocabulary member** (`DM-D12`). Both single-value marks **require aggregation to exactly one value** (`DM-D22`) — with more than one instance and `acrossInstances: "none"` the query is refused `422` and the UI says so rather than silently showing the first instance's number under a title naming ten.
**Acceptance** ☐ three marks only ☐ `area` is an option, not a mark ☐ single-value marks force aggregation
**Ready** `READY`

#### FE-077 · Explore shell and gated control steps
**Refs** Dec `DM-D9` `DM-D23` · Design §11 · `dynamic-metrics` §9.1, §11.1 · UI §A.10
**Deps** 068–076
**Files** *Create:* `src/features/monitoring/explore/ExplorePage.tsx`, `ControlStep.tsx`
**Implementation** A left control column of five **numbered, gated** steps — `1 Metric · 2 Instances · 3 Series by · 4 Aggregation · 5 Group by` — and a right result column. A step is **locked with a visible reason** until its prerequisite is satisfied (metric untyped locks 2–4; no instance aggregation locks 5). Explore-first, save-second (`DM-D9`).
**Acceptance** ☐ every locked step states why ☐ ordering matches the contract's evaluation order
**Ready** `READY`

#### FE-078 · InstanceSelector with projected series count
**Objective** The cap is enforced **before** the query, not after it fails.
**Refs** Dec `DM-D7` `DM-D23` · `dynamic-metrics` §11.4 · Conflict C6 · UI §A.10
**Deps** 077, 068
**Implementation**
- Multi-select list with a live **`projected / cap` meter**. **The cap counts rendered series, not instances**: projection is `Σ replicas` under `seriesBy: replica` and `Σ 1` under `seriesBy: instance`. Each row shows what it would add (`3 sr`).
- Switching to replica mode **visibly reduces** how many instances remain selectable.
- **At the cap, unselected instances are disabled** with an inline explanation and **two routes forward**: *narrow by label* and *aggregate into one line*. It must **not** truncate silently and must **not** let a selection succeed and then fail at query time.
- The cap comes from `capabilities.maxSeriesPerWidget`, **fail-closed to 10** — not the prototype's literal 20 (C6).
**Testing** Projection arithmetic in both modes; at-cap disabling; both escape routes work; the cap is read from capabilities.
**Acceptance** ☐ projection counts series ☐ no silent truncation ☐ cap is capability-driven
**Ready** `READY`

#### FE-079 · MetricSelector, `needs-typing`, type-metric dialog
**Refs** Dec `DM-D23` `SM-D30` `UI-D5` · `dynamic-metrics` §5.5, §11.2 · UI §A.10 (type-metric dialog)
**Deps** 069, 077
**Implementation**
- **Single-select** (`DM-D23`): choosing a different metric **replaces** the current one and re-queries; it never adds a second series group. Copy states that comparing two metrics means two widgets.
- Each entry shows its **layer** (`pkg` / `ext`) and **state** chip. `needs-typing` is **disabled in the picker** with a `Type…` affordance. `type-suspect` is selectable and carries a **persistent warning** that is never auto-corrected.
- The typing dialog collects `metricType ∈ counter|gauge|histogram` and a free-text `unit`, and states plainly that **inferring a type is forbidden**.
- ⚠️ **No suggested type is offered** — the catalog entry carries neither `firstSeen` nor a suggestion field (§16 Q14). Render that as a stated limitation in the dialog rather than guessing.
**Testing** Selecting a second metric replaces rather than adds; `needs-typing` cannot be plotted; no suggestion is rendered.
**Acceptance** ☐ single-select enforced ☐ untyped never plotted ☐ no inferred or suggested type
**Ready** `FIXTURE` — typing write route **TBD**

#### FE-080 · Aggregation and group-by controls
**Refs** Dec `DM-D8` `DM-D22` · `dynamic-metrics` §5.7 · UI §A.10
**Deps** 077, 079
**Implementation** Two axes in a fixed order: **across replicas** → instance series, then **across instances** → one line, then **`groupBy`**. Offer only functions the metric's type permits (`histogram` is unsupported in v1). `groupBy` is **disabled unless `acrossInstances != none`**, because ten instances + `groupBy: [region]` + no aggregation is genuinely ambiguous and the API refuses it rather than picking a reading. Aggregation is **forced non-`none`** for `gauge`/`single-stat`.
**Acceptance** ☐ order fixed ☐ `groupBy` gated on aggregation ☐ type-incompatible functions not offered
**Ready** `READY`

#### FE-081 · TimeRangePicker and ResolutionControl
**Refs** Req `REQ-007` · Dec `DM-D10` `DM-D11` `SM-D31` `B/D15` 🔒 · `dynamic-metrics` §5.4
**Deps** 068, 032
**Implementation** Presets **derived from `capabilities.tiers`**, filtered to what is advertised, from the set `15m · 1h · 6h · 24h · 7d · 30d · 90d · 6mo · custom`. Every rendered window is UTC-canonical with the display zone labelled. `ResolutionControl` is **`Auto | Raw` only — no free bucket size** (`SM-D31`); `Raw` is present only when `features.forceRaw` is advertised **and** the `metrics.forceRaw` verb permits it (currently `proposed` — see C12/task 032), and it is a **high-consequence** action (a forced-Raw query over six months can strain the deployment).
**Acceptance** ☐ no hardcoded preset ☐ zone labelled ☐ no free bucket size ☐ Raw is gated and confirmed
**Ready** `READY` (with C12's rule applied to the verb)

#### FE-082 · Explore result states and typed `422` rendering
**Refs** Dec `DM-D16` `FE-D10` `FE-D20` `SM-D27` · `dynamic-metrics` §5.8, §11.2 · UI §A.10 (typed error copy)
**Deps** 077–081, 028
**Implementation** Render each of `dynamic-metrics` §11.2 exactly, and **never the happy path alone**:

| State | Renders | Must not |
|---|---|---|
| Empty selection | a prompt naming the missing choice | show an empty axis |
| First loading | skeleton at chart dimensions | shift layout on arrival |
| Refreshing | previous series dimmed + indicator | blank the chart |
| No data for range | *"No samples in this range"* + the **served tier** + a nearest-data hint | draw a zero line |
| Partial | resolved series + a banner naming each unavailable instance **and why** | present a partial fleet as complete |
| Gaps | the line breaks; low-coverage buckets distinct | zero-fill or connect across |
| Retention truncation | chart from the earliest retained point + *"data before X is outside the retention window"* | pretend the range was served |
| `needs-typing` | disabled picker entry + typing affordance | infer a type |
| `type-suspect` | chart + persistent warning | auto-correct |
| `pending-config-change` | series marked pending | render as retirement |
| `inactive-replica` | annotated, excluded from `avg` | count as a healthy zero |
| Unverified durability | caveat badge | imply gaps are impossible |
| `422` | the typed code, its stated reason, **not retried** | retry, or render as a generic failure |

⚠️ The **`422` body shape is unspecified** and `partial.unavailable[]`'s item shape and reason vocabulary are invented — flag both in source (§16 Q9).
**Testing** One test per row, driven by the fixture generator's eight data-states.
**Acceptance** ☐ all 13 states covered ☐ `422` never retried ☐ no zero line in any state
**Ready** `FIXTURE`

#### FE-083 · Explore draft persistence
**Refs** Dec `FE-D21` `DM-D9` `DM-D19` · Design §6.2, §26 (self-pass row "Incident URL")
**Deps** 077
**Implementation** The Explore **document** lives in per-tab `sessionStorage`, **not the URL**; the URL carries only `?draft=<id>` plus range and instance selection (`FE-D21`). The draft is discarded on navigate-away unless saved (`DM-D9`).
⚠️ **State the consequence in the UI**, because R1 finding 3 corrected exactly this over-claim: a shared Explore link **before the widget is saved** lands the recipient on an empty Explore, not the sender's view. Offer *"Save as widget to share this"* rather than a share affordance that silently does not work.
**Acceptance** ☐ no widget JSON in the URL ☐ the sharing limitation is stated, not implied away
**Ready** `READY`

#### FE-084 · SaveWidgetDialog, widget config schema and migrations
**Refs** Dec `DM-D6` `DM-D12` `DM-D21` `SM-D31` · `dynamic-metrics` §7 · Conflict C7
**Deps** 082, 016
**Files** *Create:* `src/features/monitoring/widget/widgetConfig.ts`, `migrations.ts`, `SaveWidgetDialog.tsx`
**Implementation**
- Widget config exactly as `dynamic-metrics` §7: `schemaVersion`, `id`, `title`, `mark`, `options{fill,stack,scale}`, `query{metric,selector,seriesBy,aggregation,groupBy}`, `range`, `resolution`, `axes`, `legend`, `series`, `thresholds`, `refresh`.
- **`range` and `refresh` are defaults, not bindings** — a dashboard overrides them always (`DM-D21`). They still travel with the config because a widget must be self-contained for URL serialisation and the alerting seed.
- Migrations: `schemaVersion` is a **monotonic integer**, forward-only, registered in an **ordered chain** so `v1 → v4` runs three functions. **Unknown fields are preserved on round-trip** — an older console must not silently destroy a newer console's widget.
- Save scope in v1: **`instance` scope, layer `shared`** (C7). No `entity`/`tenant`/`personal` control ships.
- ⚠️ **Do not build an alerting seed affordance.** If "create alert" appears anywhere it must state that it is **seed-only and nobody is notified** (`DM-D15`, design §2.2) — the simpler and safer choice is to omit it in v1.
**Testing** Round-trip preserves an unknown field; the migration chain runs in order; no scope control beyond `instance`+`shared` renders.
**Acceptance** ☐ unknown fields survive ☐ ordered chain ☐ no unratified scope surface ☐ no alerting affordance
**Ready** `FIXTURE` — widget persistence route **TBD** (§16 Q15)

#### FE-085 · DashboardView and toolbar
**Refs** Dec `DM-D21` `DM-D11` `DM-D17` `DM-D18` · Design §12 · UI §A.11
**Deps** 084, 081
**Implementation**
- Toolbar: **time range · refresh · resolution (Auto/Raw) · layer filter**, all **dashboard-wide**. *"The dashboard's range and refresh are authoritative for every widget — a widget's own settings are defaults it overrides. There is deliberately no per-widget time pinning."* Two charts on one dashboard showing different windows is a documented way to misread an incident.
- **`ResolutionControl` belongs here too**, not only in Explore — forcing Raw during an incident is exactly the case the override exists for, and incidents are read on dashboards. Gated as in task 081.
- Refresh is **polling, floored at the served bucket size, and paused when the document is hidden** (`DM-D17`).
- The monitoring landing renders the package's **default dashboard directly**. A dashboards **index is not assumed** — the contract has no list route; say so on screen rather than inventing one (§16 Q15).
**Acceptance** ☐ no per-widget pinning ☐ refresh floored and paused ☐ no invented list route
**Ready** `FIXTURE`

#### FE-086 · DashboardGrid and WidgetFrame
**Refs** Dec `DM-D6` `FE-D20` · Design §12, §17 · `dynamic-metrics` §8, §17 · UI §A.11 (widget cell)
**Deps** 085, 006, 072
**Implementation**
- **12-column grid**; each widget carries `{x,y,w,h}` **plus an explicit `order`** that drives keyboard and screen-reader traversal and **must not depend on pixel position**. Edit mode exposes move-earlier / move-later and shows the order number.
- `WidgetFrame`: title, **LayerBadge**, menu, state chrome, **its own error boundary** — one failing widget never takes down the dashboard.
- **Off-screen widgets do not query**: `IntersectionObserver` drives TanStack Query's `enabled`.
- **Refresh dims, never blanks** (`placeholderData`, task 029).
- A widget whose `schemaVersion` is **newer than the console renders read-only and whole — never partially** — with a stated reason (`DM-D6`).
**Testing** A widget that throws leaves siblings refreshing; an off-screen widget issues no request; a `schemaVersion+1` fixture renders read-only.
**Acceptance** ☐ boundary per widget ☐ visibility-gated queries ☐ dim-not-blank ☐ newer schema read-only and whole
**Ready** `FIXTURE`

#### FE-087 · Layers: badge, filter, hide-not-delete, reset
**Refs** Dec `SM-D31` `DM-D5` (scope deferred) `DM-D18` · Design §12 · Conflict C7
**Deps** 086
**Implementation** Two layers in v1 — **`product-default` (package) and `shared` (operator)** — each with a badge, because they have **different upgrade behaviour**: a package upgrade **replaces `product-default` wholesale** and never touches the other. Without the badge an operator cannot tell which widgets an upgrade will replace, which makes hide-not-delete unintelligible.
**A package default may be hidden but never deleted**; hiding is an override stored in the layer above, so a later package can still improve that default and the operator's choice survives. A hidden default renders as a placeholder with a **Restore** action. `Reset defaults` restores the package layer.
⚠️ The **hidden-default representation and the reset payload have no shape in the contract** — flagged (§16 Q15).
**Acceptance** ☐ package defaults cannot be deleted ☐ badges present ☐ hidden defaults restorable
**Ready** `FIXTURE`

#### FE-088 · CSV export
**Refs** Dec `DM-D14` `DM-D18` · Design §13, §15 · `dynamic-metrics` §5.8
**Deps** 035, 085
**Implementation** `POST {console}/export/range` — a **specified route**. Streaming `text/csv`, same-origin so the cookie travels, **never buffered**. High-consequence confirm before start (design §15). Gated on `metrics.export`, applying C12's rule while that verb is `proposed`. **PNG export is Phase 2** and does not ship (`DM-D14`).
**Acceptance** ☐ streams ☐ confirmed ☐ no PNG affordance
**Ready** `READY` — route specified; verb `proposed`

#### FE-089 · Monitoring first-run
**Refs** Dec `DM-D9` · `dynamic-metrics` §11.1 · UI §A.11 (monitoring first run)
**Deps** 085, 019
**Implementation** **The empty console never shows an empty grid.** It shows the entity types that exist with their **instance counts** and **one** primary action into Explore. If the package shipped `product-default` dashboards, **those render instead** and this state never appears — which is why it is a first-install concern, not a routine one.
**Acceptance** ☐ no empty grid ☐ counts shown ☐ exactly one primary action
**Ready** `FIXTURE`

### Phase 7 — Admin and Targets · **gated, not in v1 navigation**

> Design §14: *"Do not show Targets/Admin in the shell until those routes exist."* Everything here ships behind that rule, and the prototype's own posture is the model: **surface the model and the open blockers rather than inventing UI on top of unresolved specs.**

#### FE-090 · Admin shell, tabs, and the honesty banner
**Refs** Req `REQ-006` · Dec `D10` 🔒 `DM-D18` · Design §14, §15 · UI §A.12 (admin shell)
**Deps** 018, 032
**Implementation** Route `/admin/:tab` with `roles | assignments | access | users`. A header banner states what is settled and what is not: **the model is locked, the verb catalog and identity are not** — 2 grantable, 6 proposed, the rest candidate placeholders; and that **the permission catalog is policy-as-code, so there is deliberately no catalog editor**. Nav entry stays hidden.
**Verify** `npm run lint && npm run typecheck && npm test -- src/features/admin --environment jsdom`
**Acceptance** ☐ not in nav ☐ banner states catalog status ☐ no catalog editor
**Ready** `FIXTURE` — the catalog is data (§6.0.6), so Q4 does not change this implementation. **Scheduling** stays behind the Phase 7 gate.

#### FE-091 · Roles and RoleEditor
**Refs** Dec `D10` 🔒 · UI §A.12 (roles · role editor · permission catalog)
**Deps** 090, 020
**Implementation** Built-in roles are **read-only but fully inspectable and cloneable** — *"the built-in one but without restart"* is the common real request, and the fixture models it. Custom roles are editable. The editor groups permissions by resource, marks **high-consequence** verbs, marks **two-sided** verbs (`target.use` / `instance.bindTarget` need a permission on both the instance's scope and the target's `ownership_scope`), and renders each verb's catalog status through the shared `status` vocabulary. **Adding a verb is a data change; this layout does not move.**
**Verify** `npm run lint && npm run typecheck && npm test -- src/features/admin/roles && npm run test:a11y -- src/features/admin/roles`
**Acceptance** ☐ built-ins not editable ☐ clone works ☐ non-grantable verbs visibly non-grantable
**Ready** `FIXTURE` — Q4 changes catalog **data**, not this layout. Scheduling stays behind the Phase 7 gate.

#### FE-092 · Assignments and ScopeTree
**Refs** Dec `D10` 🔒 `SM-D23` · UI §A.12 (assignments)
**Deps** 090
**Implementation** An assignment is the triple **(principal, role, scope)**. **`principal` is a discriminated `user | group` reference, never a user id** — so an IdP migration is a data change and roles never reference principals. Two views: **by principal** (a table) and **by scope** (pick a node, see who reaches it). **Inheritance is shown as inherited, never as if granted directly** — showing it as direct is how someone deletes an assignment and is surprised nothing changed.
**Verify** `npm run lint && npm run typecheck && npm test -- src/features/admin/assignments && npm run test:a11y -- src/features/admin/assignments`
**Acceptance** ☐ principal is discriminated ☐ inherited ≠ direct ☐ scope grants downward and says so
**Ready** `FIXTURE` — scheduling stays behind the Phase 7 gate.

#### FE-093 · Access check (effective permissions)
**Refs** Dec `D10` 🔒 · UI §A.12 (access check)
**Deps** 092
**Implementation** Given a principal and a scope: exactly what they have and **which assignment conferred it**. This is the *"why was I denied?"* screen — deny-by-default is only operable if it is debuggable. An explicit denial example states that a permission not granted is denied, and one that does not exist cannot be granted at all.
**Verify** `npm run lint && npm run typecheck && npm test -- src/features/admin/access`
**Acceptance** ☐ every granted permission names its conferring assignment ☐ denial is explained, not just absent
**Ready** `FIXTURE` — scheduling stays behind the Phase 7 gate.

#### FE-094 · Users (local)
**Refs** Dec `D13` 🔒 (deferred) · UI §A.12 (users · add user)
**Deps** 090, 020
**Implementation** Local accounts, explicitly *"a stopgap that an identity provider will replace"*. **Deactivate, never delete** — an account that authored configuration history stays attributable. Creating a user **does not** assign a role; identity and authorization are separate screens **on purpose**, so identity can become IdP-backed later without touching assignments. A banner states what disappears at IdP migration (**Add user**, **Deactivate**) and what survives (**the list**).
⚠️ Credential handling for a new user is **three options with different dependencies** — admin-set temporary credential (no external dependency), emailed invitation (**introduces an email capability this console does not have**), out-of-band handoff. **Pick one by decision, not by default** (§16 Q5).
**Acceptance** ☐ no delete ☐ role not implied by creation ☐ the chosen credential path is a recorded decision
**Ready** `DECISION` (§16 Q5)

#### FE-095 · Targets preview
**Refs** Req `REQ-005` `REQ-008` · Dec `SM-D23` `SM-D11a` · Design §14 · UI §A.12 (targets preview)
**Deps** 090
**Implementation** A **read-only** model preview: target id, location, driver, `control_locus`, ownership scope — plus the Target/Binding field lists. Copy makes the security meaning explicit: `dp-remote` = the platform **stores host credentials centrally**; `host-local` = **stores none**, an outbound agent reports in; `observe-only` = SM **never writes** here.
⚠️ **Credential entry is deliberately absent** — the apply protocol it depends on is an open blocker (open items 1 and 2). **Nothing on this screen writes to a target.**
**Acceptance** ☐ read-only ☐ no credential entry ☐ locus security meaning stated
**Ready** `BLOCKED` (open items 1, 2) for anything beyond read-only

### Phase 8 — Production hardening

#### FE-096 · Code splitting and a bundle budget
**Refs** Design §17, §21
**Deps** 072, all features
**Implementation** Route-level splitting per feature; **ECharts in its own async chunk**, entered only by `ChartRenderer`; Data Grid split with the inventory route. A CI budget fails the build when the initial chunk exceeds an agreed ceiling; the ceiling is recorded in the workflow with its rationale.
**Acceptance** ☐ ECharts absent from the initial chunk ☐ budget enforced in CI
**Ready** `READY`

#### FE-097 · Palette and contrast validation in CI
**Objective** Turn the design's *"contrast cannot be signed off until tokens exist"* into a passing or failing check.
**Refs** Design §8.1, §18 · `dynamic-metrics` §18 · Conflict C2
**Deps** 002, 003
**Files** *Create:* `scripts/validate-palette.mjs`, `src/shared/theme/palette.contrast.test.ts`
**Implementation**
- Assert **every categorical chart colour ≥ 3:1** against **both** the light and the dark surface tokens, and **all text/labels ≥ 4.5:1** — *"the palette is validated against both themes in CI, not chosen by eye."*
- Assert the categorical palette is **colour-blind-safe** and that **no state is signalled by colour alone** (cross-check against the vocabulary module: every entry has an icon and a label).
- Assert focus-ring contrast in both themes.
- A failure is a **build failure**, not a warning.
**Acceptance** ☐ both themes validated ☐ failure blocks CI ☐ every status carries a non-colour channel
**Ready** `READY` (result feeds §16 Q1's UX-accept)

#### FE-098 · Automated accessibility sweep
**Refs** Design §18 (WCAG 2.2 AA) · Conflict C4
**Deps** all screens
**Implementation** Axe runs over the component gallery and over every screen in every major state, in **both themes**, in CI. Plus a **written manual keyboard script** (checked into `docs/`) covering: skip link, drawer, breadcrumbs, tabs, data grid, Designer field traversal and jump-to-field, dialogs (trap, Esc, focus restore), composer nodes, dashboard `order` traversal, and the chart keyboard model. Manual results are recorded per release.
⚠️ `dynamic-metrics` §18 notes there is **no accessibility checklist in `docs/checklists/`** — this task creates one.
**Acceptance** ☐ axe clean in both themes ☐ manual script exists and is run ☐ a11y checklist added to `docs/checklists/`
**Ready** `READY`

#### FE-099 · End-to-end journeys
**Refs** Dec `FE-D12` · Design §19
**Deps** all features
**Implementation** Playwright over the fixture backend:
1. **Walking skeleton** — login → solution → instance → Designer → edit → save (**assert the generation is on the request**; assert an inactive branch is **not** in the payload) → apply → **assert `APPLY_INDETERMINATE` renders, not success and not failure**.
2. **Concurrent save** — a `409` renders a diff and never overwrites.
3. **Incident path** — red health badge → Governance → *Open in Monitoring* → **assert the gap renders as a break, not a zero line**.
4. **Explore → save widget → dashboard** — the widget appears in the operator layer with its layer badge.
5. **Deep link** — a URL with `?sel=` and `?focus=` reproduces the sender's view.
6. **Permission** — with the verb absent, the control is **omitted**; with a capability limit, it is **disabled with a reason**.
7. **Idle lock** — the session drops to `/login` with the idle arrival state.
**Acceptance** ☐ all seven green in CI ☐ each asserts a *must-not*, not only a happy path
**Ready** `FIXTURE`

#### FE-100 · CSP and air-gap verification
**Refs** Req `REQ-003` · Design §21
**Deps** 096
**Implementation** `default-src 'self'`. **If ECharts requires `unsafe-eval`, that is a recorded exception with a written reason — not a silent relaxation** (design §26 item 10). A CI check asserts **zero outbound requests to any third-party origin** at runtime — no analytics, no beacons, no CDN, and **no Google Fonts** (task 002 self-hosts them). An air-gapped deployment must work with no egress.
**Acceptance** ☐ CSP present and tested ☐ zero third-party requests ☐ any `unsafe-eval` is a recorded exception
**Ready** `READY`

#### FE-101 · Production build assertions
**Refs** Dec `FE-D14` `FE-D24` · Design §21
**Deps** 005, 010, 030
**Implementation** CI asserts of a production bundle: **no auth mock**, **no dev gallery route**, **no MSW**, **no fixture dataset**, **no secret**, **no plane hostname**, and that `config.json` is externally replaceable without a rebuild. Each assertion is a grep or a bundle-analysis test that fails the build.
**Acceptance** ☐ six assertions in CI ☐ each demonstrably fails when violated
**Ready** `READY`

#### FE-102 · Observability and secret-leak prevention
**Refs** Dec `FE-D13` `I6` · Design §20
**Deps** 026, 006
**Implementation** A structured `logger` that is **development-only for verbose levels**; the **server correlation id** is the id shown in support UI. An ESLint rule forbids passing a config value object, a metric payload, or anything named `secret|password|token|credential` to the logger. Production error shipping **waits on a backend log endpoint** — do not invent a SaaS RUM product for an air-gapped on-prem deployment (design §20).
**Acceptance** ☐ no secret can reach a log ☐ no third-party telemetry ☐ correlation id surfaced for support
**Ready** `READY`

#### FE-103 · Performance verification
**Refs** Design §17, §22 · `dynamic-metrics` §13
**Deps** 086, 041, 049
**Implementation** Tests, not assertions: off-screen widgets issue no request; a dashboard of 8 widgets over 4 metrics issues **4** requests (dedup); refresh is floored at the served bucket and paused when hidden; a 12-row grid and a ~100-field Designer form render within an agreed budget; the legend **virtualises** rather than truncating; free-text inputs are debounced and enum inputs are not.
**Acceptance** ☐ dedup proven ☐ visibility gating proven ☐ no truncated legend ☐ budgets recorded
**Ready** `READY`

#### FE-104 · Documentation and plan closure
**Refs** `docs/checklists/definition-of-done.md`, `plan-review.md`
**Deps** all
**Implementation** Update `frontend/README.md` (status, how to run, where the seam is, how to replace a fixture with a real client). Add the a11y checklist from task 098. Fill this plan's **Review** section: what changed versus the plan and why. **Anything decided during execution lands in `docs/design/DECISIONS.md` — as a new `FE-D*` that supersedes, never an in-place edit (`I3`)** — before the issue closes.
**Acceptance** ☐ README current ☐ review section filled ☐ every execution-time decision has an ID
**Ready** `READY`

### Phase R — Responsive layout · tasks 105–110 · **cross-cutting**

> Added by `FE-D29` (§16.1). These six tasks are **not a phase in sequence** — each executes inside the phase named in its heading, alongside the tasks it extends. Responsive behaviour that is bolted on at the end is responsive behaviour that is wrong, because the information architecture decisions live in the components themselves.
>
> ⚠️ **No visual reference exists below 1280 px.** The reference bundle was built at a fixed 1280 px viewport (§B.5), so every layout below `lg` is specified here in prose and validated by test, not checked against a design. This is risk **R18** and it is the single largest uncertainty these six tasks carry.

#### FE-105 · Responsive foundation: breakpoints, layout primitives, reflow harness
**Objective** One breakpoint system, one set of layout primitives, and a test harness that can assert reflow — so no component invents its own media query.
**Refs** Dec `FE-D29` `FE-D5` · WCAG 2.2 AA SC 1.4.10, SC 1.3.4, SC 1.4.4 · Design §8.1 · UI §B.5
**Deps** 002, 008 · **Executes in** Phase 0
**Files** *Create:* `src/shared/theme/breakpoints.ts`, `src/shared/ui/layout/{Stack,Cluster,Grid,Sidebar}.tsx`, `src/test/reflow.ts` · *Modify:* `src/shared/theme/tokens.css`
**Implementation**
- Breakpoints in the **token layer**, consumed by both the MUI theme and CSS: `sm 600 · md 905 · lg 1240 · xl 1560`. MUI's `xs` key is its 0-floor by convention; the **supported floor is 320 px**, and that is what the reflow harness asserts.
- Four layout primitives so features never write a raw media query: `Stack` (vertical rhythm), `Cluster` (wrapping horizontal group — the pattern behind every toolbar and action row), `Grid` (column count per breakpoint), `Sidebar` (the rail-plus-content pattern that collapses).
- **Prefer container queries over viewport queries** where a component's layout depends on its own width rather than the page's — a widget in a 3-column dashboard and the same widget in a 1-column stack are the same component at different sizes. Viewport queries make that a lie.
- `reflow.ts` exports `assertReflow(el, width)`: renders at a given CSS width and fails if `scrollWidth > clientWidth` on the page body, or if any focusable element is clipped out of the viewport.
- **A raw `@media` outside `shared/theme` and `shared/ui/layout` is a lint error** — extend task 009's rule set.
**Testing** Unit: each primitive at 320/600/905/1240/1560. Harness self-test: a deliberately overflowing fixture must fail `assertReflow`.
**Acceptance** ☐ one breakpoint source, consumed by theme and CSS ☐ no raw media query outside the layout layer (lint-enforced) ☐ harness fails on its own overflow fixture ☐ no `user-scalable=no`, no `maximum-scale`
**Verify** `npm run lint && npm run typecheck && npm test -- src/shared/ui/layout src/test/reflow.ts`
**Ready** `READY`

#### FE-106 · Responsive app shell and navigation
**Objective** The 256px drawer and 64px app bar work at 320 px without hiding navigation behind a control the operator cannot find.
**Refs** Dec `FE-D29` `D9` 🔒 · Design §9, §14, §18 · UI §A.2
**Deps** 036, 105 · **Executes in** Phase 3
**Files** *Modify:* `src/app/shell/AppShell.tsx`, `AppBar.tsx`, `src/shared/ui/Breadcrumbs`, `SidebarNav`
**Implementation**
- Drawer: **permanent** at `lg` and up; **temporary overlay** below `lg`, opened by a labelled menu button in the app bar, closed on `Escape`, on navigation, and on scrim click. Focus is trapped while open and **restored to the trigger** on close — same rule as task 016's dialogs.
- App bar below `md`: brand + menu button + account menu stay; the tenant display, theme toggle and support menu move into the account menu. **The tenant display is never dropped** — it is `I5`'s only on-screen expression, so it moves rather than disappears.
- Breadcrumbs collapse to *"… / current"* below `md`, with the elided ancestors reachable from a menu. The current page stays visible, because it is the one crumb that is not a link and not recoverable from elsewhere.
- The skip link and `main` focus-on-route-change behaviour are unchanged at every width.
**Testing** Drawer open/close/focus-restore at 320 px; tenant display reachable at every width; breadcrumb collapse preserves the current page; axe at 320 · 600 · 905 · 1240 in both themes.
**Acceptance** ☐ every nav destination reachable at 320 px ☐ tenant never dropped ☐ focus trapped and restored ☐ `assertReflow` passes on the shell
**Verify** `npm run lint && npm run typecheck && npm test -- src/app/shell --environment jsdom && npm run test:a11y -- src/app/shell`
**Ready** `READY`

#### FE-107 · Responsive data presentation — the table-to-card transform
**Objective** A 9-column inventory grid at 320 px, without losing a single state the operator needs to see.
**Refs** Req `REQ-005` `REQ-008` · Dec `FE-D29` `FE-D11` `UI-D9` · WCAG 2.2 SC 1.4.10 (data-table exception) · UI §A.6
**Deps** 020, 041, 105 · **Executes in** Phase 3
**Files** *Modify:* `src/shared/ui/DataTable`, `src/features/inventory/instances/InstancesPage.tsx` · *Create:* `src/shared/ui/DataTable/CardView.tsx`
**Implementation**
- **Two conformance routes, and both are used.** SC 1.4.10 exempts data tables, so at `md` and up the grid keeps its columns and scrolls **horizontally inside its own container** — never the page body. Below `md` that exception stops being useful to a human, so the same row model renders as a **card list**: primary identifier, then the state chips, then the remaining fields as label/value pairs.
- **The card view is a presentation of the same row model** — it re-renders `columns[].render` output, so a state cannot be styled one way in the table and another in a card. ⚠️ It introduces **no branch on `driver` or `locus`**; `REQ-005` and `REQ-008` hold unchanged, and task 041's zero-branch test covers both views.
- Selection, expansion, sort and quick-filter all survive the transform; the detail panel becomes an inline expansion of the card.
- Bulk actions move to a sticky bottom bar below `md` so a selection is never made invisible by scroll position.
**Testing** All 12 fixture instances render every health/not-found/version/config state in **both** views; a table-driven test asserts the two views emit the same state labels; selection round-trips through `?sel=` in both; no page-body horizontal scroll at 320 px.
**Acceptance** ☐ both views render all 26 vocabulary states ☐ zero `driver`/`locus` branches in either ☐ table scrolls in its container, never the body ☐ selection and focus deep links work in both
**Verify** `npm run lint && npm run typecheck && npm test -- src/shared/ui/DataTable src/features/inventory --environment jsdom && npm run test:a11y -- src/features/inventory`
**Ready** `FIXTURE`

#### FE-108 · Responsive Designer
**Objective** A 132-field component, its section rail, its save bar and its composer, usable at 320 px.
**Refs** Dec `FE-D29` `UI-D20` `UI-D21` `FE-D17` · Design §10 · UI §A.8
**Deps** 049, 050, 052, 055, 063, 105 · **Executes in** Phase 4
**Files** *Modify:* `src/features/designer/**` (SectionNav, SectionCard, FieldRow, SaveBar, Composer)
**Implementation**
- **Section nav** is a persistent rail at `lg` and up; below that it becomes a drawer opened from a *"Jump to section"* control that keeps its block/warn dots — the dots are how an operator finds the failing field, so they may not be dropped with the rail.
- **FieldRow** stacks label-above-control below `sm`. The rendered path, `Default` hint and `Modified` chip move under the control rather than being hidden — every one of them is a correctness signal.
- **Save bar** is already sticky; below `md` it collapses to counts plus the two actions, with the counts remaining the primary content. **`Apply` stays disabled until saved at every width** — the save/apply distinction is a rule, not a layout.
- **Composer canvas** below `md` **switches to the flat "All fields" list view and says why.** The plan already ships that list as the non-pointer path (task 063), so this reuses a required affordance rather than inventing a mobile canvas. Node positions remain presentation-only and excluded from the config hash (`UI-D20`).
- **Diff, conflict and rollback dialogs** become full-screen sheets below `sm`; the diff's two columns stack, with each row labelled by side. ⚠️ A stacked secret row still renders masked on **both** sides — `G7` does not relax with the viewport.
**Testing** 100-field fixture at 320 px: every field reachable and editable; section jump moves focus; a stacked diff masks secrets on both sides; the composer's list fallback states its reason; `assertReflow` on each Designer scenario.
**Acceptance** ☐ every field reachable at 320 px ☐ block/warn dots survive the rail collapse ☐ save ≠ apply at every width ☐ secrets masked in the stacked diff ☐ no mobile canvas invented
**Verify** `npm run lint && npm run typecheck && npm test -- src/features/designer --environment jsdom && npm run test:a11y -- src/features/designer`
**Ready** `FIXTURE`

#### FE-109 · Responsive Monitoring — Explore controls and the dashboard grid
**Objective** Five gated control steps and a 12-column grid at 320 px, with charts that stay honest when they get small.
**Refs** Dec `FE-D29` `DM-D21` `DM-D17` `UI-D8` · Design §11, §12 · UI §A.10, §A.11
**Deps** 077, 081, 085, 086, 105 · **Executes in** Phase 6
**Files** *Modify:* `src/features/monitoring/explore/**`, `src/features/monitoring/dashboard/**`, `chart/ChartRenderer.tsx`
**Implementation**
- **Explore** is two columns at `lg`; below that the five steps become a vertical accordion in the same order, **each keeping its locked state and its stated reason**. A locked step that loses its reason is worse than a locked step.
- **Dashboard grid**: 12 columns at `lg`, **6 at `md`, 1 at `sm` and below**. Stacking order comes from the widget's existing explicit `order` field — the plan already requires `order` to drive traversal and to **not depend on pixel position** (task 086), so the responsive stack is that same contract, not a new one.
- **The dashboard toolbar uses SC 1.4.10's persistent-toolbar exception**: it stays visible and scrolls on one axis inside its own container. Range, refresh and resolution remain dashboard-authoritative at every width — **no per-widget time pinning appears on small screens** (`DM-D21`).
- **Charts**: legend moves below the plot under `md` and keeps scrolling rather than truncating (`G12`). Below `sm` the chart's **tabular alternative (task 075) becomes the primary affordance**, offered directly rather than behind `Enter` — a 320 px canvas is not readable, and the table already exists and is already the accessible path.
- Off-screen widgets still do not query; visibility gating is unchanged and matters more when stacking makes most widgets off-screen.
**Testing** Explore at 320 px: all five steps reachable, locked reasons intact. Dashboard stacks by `order`, not by position. A gap still renders as a break at every width. Off-screen widgets issue no request when stacked.
**Acceptance** ☐ locked steps keep their reasons ☐ stacking follows `order` ☐ no per-widget time pinning ☐ legend never truncates ☐ no zero-fill at any width ☐ tabular alternative primary below `sm`
**Verify** `npm run lint && npm run typecheck && npm test -- src/features/monitoring --environment jsdom && npm run test:a11y -- src/features/monitoring`
**Ready** `FIXTURE`

#### FE-110 · Reflow conformance in CI
**Objective** Turn `FE-D29` from a claim into a build failure.
**Refs** Dec `FE-D29` · WCAG 2.2 AA SC 1.4.10, SC 1.3.4 · Design §18
**Deps** 105–109, 098 · **Executes in** Phase 8
**Files** *Create:* `scripts/verify-reflow.mjs`, `e2e/reflow.spec.ts` · *Modify:* `.github/workflows/frontend.yml`, `docs/checklists/` a11y checklist
**Implementation**
- Playwright walks **every screen in every major state** at **320 · 375 · 600 · 905 · 1240 · 1560** and asserts: no horizontal scroll on the page body; no focusable element clipped out of the viewport; no text requiring two-dimensional scrolling to read a line.
- Containers claiming an exception must **declare it**: an allowlist naming each element and which exception it uses (data table, persistent toolbar). **An undeclared horizontal scroll fails the build** — otherwise the exception becomes a blanket excuse.
- Axe runs at 320 px as well as desktop, in both themes — a11y defects are not width-invariant.
- Orientation: assert no screen locks to portrait or landscape (SC 1.3.4).
- The manual keyboard script from task 098 gains a narrow-viewport pass.
**Testing** The job fails on a deliberately overflowing fixture; the allowlist rejects an undeclared scroller.
**Acceptance** ☐ six widths × every screen state in CI ☐ exceptions declared, not assumed ☐ axe clean at 320 px in both themes ☐ failure blocks the build
**Verify** `npm run build && npm run verify:reflow && npm run test:a11y -- --all`
**Ready** `READY`

#### FE-111 · Collapsible nav drawer at desktop widths; dark-mode login contrast fix

**Objective** Two operator-reported defects, both confirmed by reading the shipped code, not assumed: the drawer permanently claims desktop screen space with no user control, and the login card ignores dark mode entirely.
**Refs** Dec `FE-D29` (responsive shell) `FE-D5` (theme tokens only) · UI §A.2, §A.3
**Deps** 036, 037, 106 · **Executes in** Phase 3
**Files** *Modify:* `src/app/shell/AppShell.tsx`, `AppBar.tsx`, `src/shared/state/uiStore` (persisted collapse preference) · `src/features/auth/LoginPage.tsx`
**Implementation**
- **Bug 1 — no desktop collapse control.** Task 106 made the drawer `variant="permanent"` at `lg` and up with no toggle: `AppBar.tsx`'s menu button only renders when `showNavToggle` (`= !permanentDrawer`) is true, so at `lg`+ there is no affordance at all — this is the reported "fixed, cannot be collapsed" behaviour, verified in `AppShell.tsx`. Add an operator-controlled collapsed state at every width ≥ `lg` (icon-rail or full close, consistent with the existing pattern), toggled from the app bar and persisted in `useUiStore` — the same store `ThemeToggle` already uses — so the choice survives navigation and reload. Below `lg`, task 106's temporary-overlay/backdrop/focus-trap/close-on-navigate behaviour is unchanged; that is the modal pattern working as designed, not part of this defect.
- **Bug 2 — login card is not dark-mode aware.** `LoginPage.tsx` paints its full-bleed background and card from `tokens.console.navy` / `tokens.semantic.bg1` / `tokens.console.e8` — the flat `tokens` object, which `tokens.ts` resolves **once from `:root` at module load** and never re-reads the `[data-theme="dark"]` overrides (see the note at `tokens.ts:518-519`). The card therefore stays white in dark mode while the `Typography` inside it draws from `theme.palette.text.*`, which **is** mode-reactive — light-on-white text is the exact low-contrast defect reported. Replace the frozen-token backgrounds on `LoginPage.tsx`'s outer `Box` and card `Paper` with mode-reactive values from `theme.palette` (`useTheme()`), the same source every other themed surface in the app already uses, so the card, its border and its shadow flip with `useUiStore`'s mode.
**Testing** Toggle dark mode on `/login`: card background, border and text all pass the app's existing contrast bar in both themes. At `lg` and up: collapse the drawer, reload, confirm the collapsed state persisted; the collapsed state never hides its own toggle and never traps focus. Below `lg`, task 106's existing overlay tests still pass unmodified.
**Acceptance** ☐ drawer collapsible/expandable at every width ≥ `lg`, state persisted across reload ☐ login card, border and text meet contrast in both themes ☐ no regression to task 106's below-`lg` overlay behaviour
**Verify** `npm run lint && npm run typecheck && npm test -- src/app/shell src/features/auth --environment jsdom && npm run test:a11y -- src/app/shell src/features/auth`
**Ready** `READY`

#### FE-112 · Sidebar solution list wired to the catalog

**Objective** One operator-reported defect, confirmed by reading the shipped code: the drawer reads "Solutions (0)" on every screen, including `/solutions` itself while the catalog renders three package cards behind it.
**Refs** Dec `UI-D8` (never a fabricated empty) `UI-D11` · UI §A.2 · reference `docs/design/frontend/reference/app/shell.jsx`
**Deps** 036, 039, 033 · **Executes in** Phase 3
**Files** *Add:* `src/features/inventory/solutions/sidebarSolutions.ts` · *Modify:* `src/app/shell/AppShell.tsx`, `src/app/routes/AuthenticatedLayout.tsx`, `src/shared/ui/SidebarNav/SidebarNav.tsx`, `src/shared/ui/Breadcrumbs/Breadcrumbs.types.ts`
**Implementation**
- **Root cause.** `AppShell.tsx` passed a module-level `const NO_SOLUTIONS = []` to `SidebarNav`, and `SidebarNav` defaulted its count to `solutions.length`. Task 036's own file header records why — at that point no file owned a plane-client fetch and the client-distribution mechanism did not exist. Task 039 established it; the hardcoded empty was never removed. `0` was therefore not a stale count but a literal.
- **The fetch.** `AppShell` reads `queryKeys.solutions.list()` — the same key `SolutionsPage` uses — so the drawer and the catalog share one cache entry and one in-flight request, and `SolutionsPage`'s post-load `invalidateQueries` refreshes the drawer with no extra wiring. The repository is injected from `AuthenticatedLayout` (which already reads `RuntimeConfig`) rather than built in `app/shell`, matching how `SolutionsRoute` builds its own.
- **Count solutions, not packages.** Two packages of the same solution — a signed release and a dev-mode nightly — are one solution. Reference `shell.jsx` computes `new Set(SOLUTIONS.map(s => s.solution)).size`; `countDistinctSolutions()` does the same. The sub-list omits `devMode` packages, also per the reference.
- **`UI-D8`.** `solutionCount` becomes optional-unknown: while the catalog is loading or errored, no count renders at all rather than `(0)`. The catalog screen owns the error surface; the drawer must not become one.
**Testing** With fixtures loaded the drawer reads `Solutions (2)` with `Cache Engine (12 instances)` and `File Mesh (0 instances)` beneath it and no nightly entry; mounted with no repository it reads a bare `Solutions` with no parenthesised count. Both assertions verified to fail against the pre-fix component.
**Acceptance** ☐ drawer count matches the catalog ☐ dev-mode packages omitted from the sub-list ☐ no fabricated `(0)` in any non-loaded state ☐ one request shared with `/solutions`
**Verify** `npm run lint && npm run typecheck && npm test -- src/app/shell src/features/inventory src/shared/ui/SidebarNav`
**Ready** `READY`

#### FE-113 · Solution Packages as the source of the Designer view model

**Objective** Discharge the deletion `shared/testdoubles/data/designer.ts` has promised since Phase 2 — "this is a local copy … deleted in favour of importing from [`contracts/view-model/types.ts`] once it exists". The engine has existed since task 046; the hand-written `CACHE_VIEW_MODEL` (26 `VmField` literals) never moved.
**Refs** `REQ-001` · `UI-D14` `UI-D15` `UI-D2` `FE-D7` · `dynamic-ui/design.md` §3.4/§3.5 · `architecture.md` §5.1
**Deps** 033, 046 · **Executes in** Phase 4
**Files** *Add:* `contracts/packages/cache/{package.json,draftValues.json}`, `contracts/packages/db-designer/package.json`, `contracts/packages/README.md`, `src/app/dev/{toDesignerViewModel,examplePackage,cachePackageEquivalence}.ts` · *Modify:* `src/app/main.tsx`, `src/shared/testdoubles/data/designer.ts` (header only)
**Implementation**
- **Author a real package per solution.** `cache/package.json` is derived from the baked fixture, not hand-transcribed. It reproduces all 26 fields and all 8 sections through the real `project()`; the equivalence test asserts the whole object, so a spot check cannot mask a dropped field.
- **Adapter, not a rewrite.** `toDesignerViewModel()` maps the contract `ViewModel` to the shape the Designer consumes — three control-kind spellings, validation tier→severity, nested-sections→flat-fields. It is deleted along with the fixture layer.
- **Dev server renders `db-designer` from its package file**, proving `REQ-001`'s gate end to end: a solution the console has never heard of, onboarded from data. DEV-only, dead-code-eliminated in production (`FE-D24`).
- ⚠️ **Three gaps found, none resolved here** (`I3`/`I4`) — recorded in `contracts/packages/README.md`. (a) `ActionDeclaration` §3.5 carries neither `label` nor `ui.section`, so an action's placement and copy cannot survive the round trip — the only residual diff, asserted explicitly. (b) `UI-D2`'s equality-only `when:` cannot express the Cache fixture's own documented "TOPIC **or** COMPOSITE" condition; the package declares the narrower single-value form. (c) A section's order is the first field's `ui.order` — `project()`'s own undocumented-by-design choice.
- **Not done: deleting `CACHE_VIEW_MODEL`.** The blocker is architectural, not data: `shared/testdoubles` cannot call `project()` because `eslint.config.js`'s boundaries policy has no `shared -> contracts` rule. Needs a decision — widen the policy for `shared/testdoubles/**`, or move the designer handler to a layer that may import contracts.
**Testing** Whole-object equivalence between the projected Cache package and `CACHE_VIEW_MODEL`; the two action gaps asserted rather than normalised away, so closing §3.5 turns the test red. `db-designer` projection asserted against what the package declares — discriminator branching, secret masking, defaults, required validations, advanced disclosure, action inputs.
**Acceptance** ☐ every Cache field and section derivable from a package file ☐ residual differences asserted, not hidden ☐ a package-only solution renders in dev ☐ no dev-only code in the production bundle
**Verify** `npm run lint && npm run typecheck && npm test && npm run build`
**Ready** `READY`

#### FE-114 · Load a Solution Package from a file at runtime (dev)

**Objective** `FE-113` proved `REQ-001`'s gate — onboard a solution from a package file with zero commits to SM — for one package that still arrived *by being committed*. Remove that last dependency: a package file dropped on the Solutions catalog at runtime produces a catalog card, an instance and a Designer, through the same `project()` engine, with no code naming it.
**Refs** `REQ-001` · `UI-D11` `UI-D14` `UI-D19` `FE-D24` · `dynamic-ui/design.md` §3, §6.2 · §16 Q10 (still open)
**Deps** 040, 113 · **Executes in** Phase 3
**Files** *Add:* `src/app/dev/{parsePackage,derivePackageCatalog,devPackageStore,devSolutionsRepository,testPackage}.ts` (+5 test files) · *Modify:* `src/app/main.tsx`, `src/app/routes/SolutionsRoute.tsx`, `src/features/inventory/solutions/{solutionsRepository,LoadPackageDialog}.tsx`
**Implementation**
- **The bytes are read in the browser and nowhere else.** `solutionsRepository.load()`'s wire contract is untouched — it still posts `{ fileName, fileSize }` — because the real upload format is `§16 Q10` TBD and inventing one would assert a contract nobody has ratified.
- **Validation is scoped to what the flow dereferences, and no schema library is added.** `parsePackage.ts` checks `solution`, `packageVersion`, `appliesTo`, `provenance.author`, the `authoring` counts, and per component `type` / `configPath` / `fields[].key` / `fields[].type`, plus `actions[].kind` and `metrics[].source.kind` — those last three *because* they index a closed-union `Record` in `project()`, where an unlisted member renders a control that does not exist. `cardinality`, `role` and `loggerPrefix` are declared by the contract and read by nothing here, so a package omitting them is admitted. This is a dev-mode loader, not a conformance gate: the authoritative schema is `dynamic-ui/design.md` §3 and is not published as JSON Schema, so a hand-written one here would be a second, drifting statement of it. Four rejection codes, each naming the offending path: `zip-unsupported`, `sompkg-unsupported`, `malformed-json`, `invalid-package`.
- **Nothing here may know about a specific solution.** `derivePackageCatalog.ts` supplies the console-side half a package deliberately does not carry — display name, card colour (a stable FNV-1a hash of the id, fixed saturation and lightness so contrast cannot degrade), glyph, one-line descriptor, telemetry line, and the single synthesized instance. Every derivation is shape-only and stated; a table keyed on "cache" would be exactly the per-solution code `REQ-001` forbids, dressed as presentation.
- **Nothing unknowable is fabricated:** `published` stays empty rather than becoming today's date, and the synthesized instance reports `lastSeen: 'never'` with unobserved health.
- **Pending, then committed.** Parsing happens when the operator *picks* the file (the only moment its bytes exist), but a picked file is not a loaded package — `holdPending()` parks it and only the dialog's confirm registers it. `commitPending?()`/`discardPending?()` are **optional** on the repository interface and **absent** from the real one.
- **`sessionStorage`, revalidated on rehydration.** A dropped package must survive the reloads a dev server forces and must **not** survive closing the tab. Every stored entry is re-run through `validatePackage()` on read; a storage read that throws is treated as "no packages", because this module loads during boot and may not break startup.
- **DEV-only, provably.** `main.tsx` installs the decorator inside a literal `if (import.meta.env.DEV)` branch (`FE-D24`), so Vite drops the module graph — `msw` included — from a production build.
**Testing** 79 cases across 5 files: parser (each rejection code, the archive extensions, the `Blob.text`/`FileReader` split), every catalog derivation, store lifecycle incl. tampered and truncated storage, the repository decorator, and the screens' click-through (card → Open → the package's own instance list).
**Acceptance** ☑ a package the console has never seen produces a card, an instance and a Designer ☑ no module names a solution ☑ nothing unknowable is fabricated ☑ no dev-only code in `dist/`
**Verify** `npm run lint && npm run typecheck && npm test && npm run build`
**Ready** `READY`

> **Recorded 2026-08-28.** This section was written after the fact: `FE-114` was executed and reported ([`reports/task-114-report.md`](reports/task-114-report.md)) but never had a task section here, which is why [`task-list.md`](task-list.md) listed it and [`execution.md`](execution.md) §7 did not. Two of its modules have since been **superseded**: `viewModelFor.ts` and `examplePackage.ts` were deleted by `FE-122`, which made the Designer read carry the package instead of a projection (`FE-D33`). Its follow-up "decide whether an unsigned dev-loaded package should be openable" was closed on 2026-08-28 as an implementation note under [`UI-D19`](../../design/dynamic-ui/decisions.md) — Open is blocked only when the console is not itself a dev build (commit `af85903`).

#### FE-115 · Declare an instance from a form

**Objective** Replace `FE-045`'s honest refusal with a working declare form, without inventing anything worse than the field set it is already known to be inventing.
**Refs** `REQ-005` `REQ-008` · Dec `SM-D13` `D10` 🔒 `UI-D12` `FE-D13` `FE-D6` · Design §14, §15, §16 · UI §A.6 · §16 Q10 (still open)
**Deps** 041, 045, 016, 114 · **Executes in** Phase 3
**Files** *Add:* `src/shared/api/contracts/instances.ts`, `src/shared/testdoubles/data/declaredInstance.ts`, `src/features/inventory/instances/declareForm.ts`, `src/app/dev/devDeclaredInstances.ts` · *Modify:* `DeclareInstanceDialog.tsx`, `InstancesPage.tsx`, `instancesRepository.ts`, `derivePackageCatalog.ts`, `devPackageHandlers.ts`, `handlers/dp.ts`, `main.tsx`, `messages.en.ts`
**Implementation**
- **Identity only.** Name, env, org, sub-org, driver, locus, replica names, optional declared version — the fields the grid already shows and `derivePackageCatalog.ts` otherwise guesses. Every **observed** fact (health, config state, `version.observed`, `lastSeen`) is absent from the form and derived as *not yet observed*. Inventing a field set is a debt with a named owner (Q10); letting an operator type a health status would be inventing **observations**.
- **Not configuration.** The package's `components[].fields[]` stay in the Designer, which renders them through `project()`. Two editable copies of one field with no rule for which wins is the failure mode avoided here.
- **One definition of a just-declared row** (`shared/testdoubles/data/declaredInstance.ts`), reused by `derivePackageInstance()`, the default handlers and the dev store — so the shape that decides whether the console claims an unmade observation cannot drift.
- **The dev store persists the DECLARATION, not the row**, and rebuilds the row on read: the observable facts are re-derived every time, so a hand-edited `"healthy"` in `sessionStorage` cannot survive a reload. A second store, not an extension of `devPackageStore` — authored vs derived provenance, and a package re-drop must not discard declarations made against it.
- **Duplicate names refused against the merged list**, fixtures included, in the dialog *and* in the handler — a client-side check is a courtesy, never the guarantee.
- ⚠️ **No entitlement check.** The action is enabled for anyone who can reach the page; the permission model (task 032) has no shape for it. Recorded, not worked around.
**Testing** Pure form rules without a dialog (defaults, name format, uniqueness, replica duplication, payload trimming, omission of a blank version); dialog behaviour incl. the invalid state's a11y; page wiring (post, invalidate, `?focus=`, failure in place, draft discarded on close); end-to-end through the real repository and real handlers — drop a package, declare against it, both rows in the grid, Designer resolves through the solution.
**Acceptance** ☐ no observed fact is askable ☐ package config fields absent from the dialog ☐ the dialog states that no API backs it ☐ a declaration cannot collide with a fixture row ☐ tampered storage cannot assert health
**Verify** `npm run lint && npm run typecheck && npm test -- src/features/inventory src/app/dev src/shared/testdoubles`
**Ready** `FIXTURE` — Q10 unchanged; `DeclareInstanceInput` is the shape to reconcile when it closes, not a position to defend.

#### FE-116 · Select-all clears the selection instead of filling it

**Objective** Fix the shared `DataTable`'s header select-all, which selected nothing on every selectable table.
**Refs** `FE-D6` · UI §A.6 · `@mui/x-data-grid` 9.11.0 `GridRowSelectionModel`
**Deps** 020, 041 · **Executes in** Phase 3
**Files** *Modify:* `src/shared/ui/DataTable/DataTable.tsx`, `DataTable.test.tsx`
**Root cause** MUI emits a **different selection model** for select-all than for a row click:
`{ type: 'exclude', ids }` — "every row EXCEPT these" — versus `{ type: 'include', ids }`. Select-all is therefore
expressed as "all rows, minus nothing", carrying an **empty** `ids` set. The handler read `ids` alone, so select-all
arrived as "select nothing" and cleared the selection; because `rowSelectionModel` is controlled, the grid immediately
re-rendered every checkbox unchecked, which is why the click looked inert rather than destructive.
**Implementation**
- Branch on `model.type`; expand an `exclude` model over the **visible** rows minus its `ids`.
- `exclude` expands over visible rows ONLY — select-all means "all of what I can see", never "all of what the caller
  filtered away too". Composes with the existing rule that a caller-filtered selection is preserved, not pruned.
- `hasId()` compares stringified ids: `GridRowId` is `string | number` and this component works in strings throughout.
**Testing** Three cases, each confirmed failing against the unfixed component: selects every visible row; deselects when
all are already selected; selects only visible rows while preserving a filtered-away selection.
**Acceptance** ☐ select-all fills the selection ☐ a filtered-away selection survives it ☐ deselect-all still clears
**Verify** `npm run lint && npm run typecheck && npm test -- src/shared/ui/DataTable src/features/inventory`
**Ready** `READY`

#### FE-117 · Undeclare an instance

**Objective** Give the inventory a removal action — and establish, in code and copy, which lifecycle actions SM may and may not offer.
**Refs** `REQ-005` `REQ-008` · Dec `SM-D13` `SM-D16` `SM-D15` `FE-D13` `I1` · Design §14 · UI §A.6 · §16 Q10
**Deps** 041, 115, 116 · **Executes in** Phase 3
**Files** *Add:* `UndeclareInstancesDialog.tsx` (+ a11y test) · *Modify:* `instancesRepository.ts`, `InstancesPage.tsx`,
`planeClientFactory.ts`, `devDeclaredInstances.ts`, `devPackageHandlers.ts`, `handlers/dp.ts`, `messages.en.ts`
**Implementation**
- **Undeclare removes SM's RECORD and nothing else.** Reported as "delete/pause missing"; neither is buildable.
  *Delete* (the workload) is a deployment-plane action `SM-D16`/`I1` forbid. *Pause* approximates `stop`, one of four
  **package-declared** lifecycle verbs (`architecture.md` §5) that `ActionKind` cannot yet express. Undeclare is the
  only one SM may perform. ⚠️ Decisive: *restart* — fully specified (`SM-D15`), shipped in 043 — **still does not
  execute** (open item 1); two less-specified verbs must not be stacked above it.
- **Copy is the feature.** "Remove" reads as "stop the workload" in every other console. The dialog states both halves:
  what IS removed (record, config of record, staged changes, history) and what is NOT (the process, which keeps running
  unmanaged). `consequence: high`, not `outage` — nothing stops, so an outage claim would be the opposite lie.
- **Toolbar action, not a control column** — matches `Restart N…` and the reference, and avoids adding CSV-exclusion
  and card-placement rules to the shared `DataTable` contract.
- **Dev removals are TOMBSTONES** in their own storage key: a fixture array cannot be mutated, and a fixture row must be
  removable on the same terms as a declaration. Re-declaring a removed name lifts its tombstone.
- **`allSettled`, not `all`** — one failure must not hide the successes; the dialog stays open naming exactly which
  instances are still declared.
- ⚠️ **Bug found in the browser, missed by unit tests:** `DataTable` restores a selected id whose row has left `rows`
  (right for the env filter, wrong for a removal — it cannot tell them apart), leaving "2 selected" over a grid the rows
  had already left. Fixed by `liveSelection = selection ∩ instances`: a selection may name a filtered-away row, never a
  nonexistent one.
**Testing** Repository (204 + 404); dev store (tombstones, persistence, tombstone-lifting, `clear()`); end-to-end through
real repositories and handlers (declare→undeclare, fixture undeclare, double-undeclare 404, re-declare restores); page
(action offered, copy states both halves, removal clears `?sel=`, partial failure names survivors, stale-selection
regression); dialog a11y in both modes incl. the failure state.
**Acceptance** ☐ no lifecycle verb offered ☐ dialog states what is NOT touched ☐ a fixture row is removable ☐ a removed
row is never counted as selected ☐ partial failure names the survivors
**Verify** `npm run lint && npm run typecheck && npm test -- src/features/inventory src/app/dev src/shared/ui/DataTable`
**Ready** `FIXTURE` — Q10 unchanged; the DELETE route is invented alongside `DeclareInstanceInput`.

#### FE-118 · A package declares several versions; an instance is declared against one

**Objective** Give the package contract a version dimension, so one package file can serve several schema versions and an instance can say which one it is configured against — without which the Designer renders fields that do not exist for that instance.
**Refs** `REQ-001` · `UI-D15` `UI-D14` `UI-D2` `UI-D19` `I2` `I3` · **produced `FE-D31` `FE-D32`** · `dynamic-ui/design.md` §3.1–§3.5, §6.5 · §16 Q10 · report [`task-118`](reports/task-118-report.md)
**Deps** 046, 113, 114, 115 · **Executes in** Phase 3
**Files** *Modify:* `contracts/view-model/{types,project}.ts`, `contracts/packages/{cache,db-designer}/package.json`, `contracts/packages/README.md`, all 19 `contracts/fixtures/view-model/*.json` + `run.test.ts` + `fixtures/README.md`, `src/app/dev/{parsePackage,derivePackageCatalog,viewModelFor}.ts`, `src/shared/testdoubles/data/{instances,solutions,declaredInstance}.ts`, `src/shared/api/contracts/instances.ts`, `src/features/inventory/instances/{declareForm.ts,DeclareInstanceDialog.tsx,InstancesPage.tsx}`, `SolutionCard.tsx`, `messages.en.ts` · *Add:* three `version-scoped-*` fixtures
**Implementation**
- **`PackageVersionId = number | string`; `SolutionPackage.packageVersion` becomes an ascending, non-empty ARRAY.** Components and fields gain an optional `version`.
- **Absent ≠ empty, and the distinction is load-bearing.** Absent means "every version the package supports" — the rule that keeps a third-party package and all 19 existing fixtures valid. An empty array belongs to NO version and never renders; the loader rejects it rather than let a field vanish quietly, and also rejects a `version` naming a version the package itself does not declare.
- **`project()` takes an optional FIFTH argument**, plus an exported `inVersion(declared, selected)` that is true when either side is `undefined`. Omitted means no filtering — which is exactly what every pre-existing caller and fixture gets.
- ⚠️ **Ordering is the one detail that matters.** Fields are filtered **before `applyDefaults` runs**: a field excluded by version must not contribute a `default` to the values map `when:` evaluates against, or a v7-only field silently steers the visibility of a v6 field. A dedicated fixture proves it — the fixture suite is `project()`'s only test.
- A version-excluded component is skipped **whole**, `graphs[]` included: it must not chart metrics on Monitoring when the Designer refuses to render it.
- ⚠️ **Cache gets an invented v6** — `[6, 7]`, v7 minus three Expiry/eviction fields. **Authored demo history, not derived from the Cache MI**; stated under its own heading in `contracts/packages/README.md`. It exists so the selector, the column and the filter are exercised by something an operator can see.
- **`version` is REQUIRED on a declaration and lives in the validated input.** Unlike health or `version.observed` it is IDENTITY: nothing observes it, the operator chooses it. **Preselected at one version, empty at several — never a silent default to "latest"**, which is the class of bug this task removes. Two distinct errors: unchosen vs not-served.
- **Two fields called "version" on one form**, so the copy says which is which: *Package version* = the config schema; *Declared version* = the MI build.
- Declare is **gated while the catalog is pending**, with a `Tooltip` stating why — you cannot pick from a list you cannot enumerate. The catalog query stays non-blocking for the table.
**Testing** Three new contract fixtures (field excluded, component excluded, excluded field contributes no default) + all 19 existing on the array form; loader rules incl. an unsupported version and an empty array; form preselect/required/membership/number round-trip; dialog options-from-prop and error-clears; end-to-end declare → storage → grid → Designer at v6 vs v7 through the real repository and handlers; equivalence re-asserted **projected at v7**, with v6 proven a strict subset.
**Acceptance** ☐ absent `version` still renders everywhere ☐ an excluded field contributes no default ☐ no silent version default ☐ the wire carries the declared id's own type ☐ the Designer projects at the INSTANCE's version
**Verify** `npm run lint && npm run typecheck && npm test && npm run build`
**Ready** `FIXTURE` — Q10 unchanged; `packageVersion` on `DeclareInstanceInput` is the same placeholder shape FE-115 opened.

#### FE-119 · Package Version column, and a grid whose column widths actually apply

**Objective** Fix the congested instance grid at its root — in the shared `DataTable`, not the column list — and add the Package Version column.
**Refs** `FE-D29` `FE-D11` `I2` · UI §A.6 · `@mui/x-data-grid` column-dimensions + row-height docs · reference `app/instances.jsx:93`
**Deps** 020, 041, 107, 118 · **Executes in** Phase 3
**Files** *Modify:* `src/shared/ui/DataTable/DataTable.tsx`, `src/features/inventory/instances/columns.tsx` (+ test), `src/shared/ui/Chip/{Chip.types.ts,Chip.tsx,Chip.gallery.tsx,Chip.test.tsx}`, `messages.en.ts` · *Add:* `DataTable.columnDimensions.test.tsx`
**Root cause** `DataTable` set **both** `width` and `flex: 1` on every column. MUI documents the interaction outright — "flex doesn't work together with width. If you set both flex and width in GridColDef, flex will override width" (fetched per `I2`, 2026-08-26) — so every declared width was discarded and nine columns rendered identically wide.
**Implementation**
- A declared width becomes `minWidth` (the floor) **plus** a proportional `flex` share (`base / 100`) — the two properties that actually compose.
- **Horizontal scrolling is accepted, not a regression**: ten floors exceed the viewport, and the alternative is ten identical columns with truncated contents.
- ⚠️ **Two further causes found in a BROWSER, both of which the planned row-height fix would not have addressed.** (a) `density` SCALES `rowHeight` rather than replacing it — a flat `44` rendered **30px** compact rows, worse than the default; the target is now divided by MUI's own exported `COMPACT_DENSITY_FACTOR` rather than a reverse-engineered constant. (b) The real clipping cause was **`line-height`**: MUI centres cell text by setting line-height to the row height, so an inline first child eats the whole row and any second line falls outside `overflow: hidden`. Measured at 79px of content in a 64px cell — the org/sub-org and replica-summary sub-lines were missing at **both** densities, not just compact. Fixed with `display: flex; align-items: center; line-height: normal` on the cell, in `DataTable` rather than per-cell, since `configCell` clipped too.
- Row heights then sized to the tallest content the grid actually draws: **64 / 52**, compact 52 not 44 because the Config cell wraps two chips and measured 50px.
- New column after `env`: `neutral` tone + new `Chip mono`, per the reference. ⚠️ **Never a semantic tone** — `ok`/`warn`/`err` mean health, and a version is not a health state.
- Card view below `md` untouched (`FE-D29`).
**Testing** Column dimensions asserted against the DEFINITIONS, not rendered headers — jsdom gives the grid no measurable container, so MUI resolves every header to `0px` and a DOM assertion would be vacuous; the test mocks only `DataGrid`, captures its `columns`, and delegates to the real component. Plus the new cell, its `valueGetter`, column order, and the `mono` chip.
**Acceptance** ☐ a declared width survives as `minWidth` ☐ no two-line cell clips at either density ☐ horizontal scroll rather than truncation ☐ the version chip carries no health tone ☐ card view unchanged
**Verify** `npm run lint && npm run typecheck && npm test && npm run build`, then in a browser at both densities and below `md`
**Ready** `READY`

#### FE-120 · Instance count derived, never a hardcoded fixture number

**Objective** Stop the Solutions card and the sidebar reporting a stale instance count that the grid and Home already disagreed with.
**Refs** UI §A.5, §A.13 · `REQ-005`
**Deps** 033, 039, 112, 115, 117 · **Executes in** Phase 3
**Files** *Modify:* `src/shared/testdoubles/data/solutions.ts`, `src/shared/testdoubles/handlers/cp.ts`, `src/app/dev/devPackageHandlers.ts`, `derivePackageCatalog.ts`, `devPackageFlow.test.ts`
**Implementation**
- `withInstanceCounts(cards, instances)` recomputes each card's `instanceCount` from the live list, applied in **both** handlers serving `/cp/solutions` — the default fixture one over `CACHE_INSTANCES`, the dev one over the merged `allInstances()`, so declare **and** undeclare both move the number.
- ⚠️ **Keyed on the CARD ID, never `card.solution`.** `/dp/solutions/:solutionId/instances` filters on the card id, so any other key would give a number disagreeing with the grid the card links to — `cache` and `cache-nightly` share one `solution` and are exactly where the two readings diverge. Asserted by walking every card against its own grid.
- `derivePackageCatalog` keeps `instanceCount: 1` as its derivation-time value, with a comment that the handler is authoritative.
- **One honest fixture change: `cache-nightly` goes 1 → 0.** No instance carries that `solutionId`; the card was already lying, and deriving the number is what surfaced it.
**Testing** Count moves on declare and on undeclare; every card agrees with its own grid; the nightly card reports 0.
**Acceptance** ☐ declaring moves the card and the sidebar ☐ undeclaring moves them back ☐ no card disagrees with its grid ☐ Home, Solutions and sidebar agree
**Verify** `npm run lint && npm run typecheck && npm test && npm run build`
**Ready** `READY`

#### FE-121 · One version rule: `packageVersion` reaches every `project()` call

**Objective** The instance's package version must be the only thing deciding which components and fields exist, and it must decide that identically on screen, in the defaults seeded into the draft, and in the save payload.
**Refs** `REQ-001` · `UI-D2` `UI-D4` `UI-D15` `FE-D17` · **produced `FE-D37`** (and amends `FE-D32`) · `dynamic-ui/design.md` §3.4
**Deps** 046, 052, 063, 118 · **Executes in** Phase 4
**Files** *Modify:* `contracts/packages/cache/package.json`, `contracts/packages/README.md`, `src/features/designer/form/{mergeReset,useDesignerDraft}.ts`, `src/features/designer/Composer/{Composer.tsx,Composer.types.ts,ComposerFieldList.tsx,ComposerInspector.tsx}`
**Root cause** `project()`'s fifth parameter is OPTIONAL and *omitted means no version filtering at all* — the behaviour every caller had before `FE-118` existed, preserved deliberately. Six callers written before `FE-118` still omit it: `useDesignerDraft.ts:113` (the save payload) and `:148` (the render), `Composer.tsx:66`, `ComposerFieldList.tsx:89`, `ComposerInspector.tsx:58`, plus `applyPackageDefaults` in `mergeReset.ts`, which reads `component.fields` with no version filter at all. The dev/MSW path (`viewModelFor.ts`) *does* pass it — so the codebase holds two version rules and only one is right.
**Implementation**
- `applyPackageDefaults(pkg, componentSelection, draft, packageVersion)` filters components **and** fields through the contract's own exported `inVersion()` — never a locally restated rule.
- `UseDesignerDraftArgs` and `ComposerProps`/`ComposerFieldListProps`/`ComposerInspectorProps` each gain `packageVersion: PackageVersionId`, threaded to all six call sites and added to every dependency array that already lists `pkg`.
- **The payload call site matters as much as the render one.** `FE-D17` makes the save payload `renderedFields()`; a version-filtered render with an unfiltered payload writes a field the instance's schema does not have.
- ⚠️ **The Cache package cannot currently express half the version story, and is amended first.** Verified over `contracts/packages/cache/package.json`: no field is v6-only (so *"removed in v7"* has no fixture), and none of the three v7-only fields declares a `default` (so *"v6 seeds no v7 default"* would pass for the wrong reason — there is nothing to seed at either version). `max-weight` gains a `default`, and a v6-only `max-size-bytes` it reads as having replaced is added. **Authored demo history, not derived from the Cache MI** — the same posture `FE-D32` takes for the invented v6; recorded in `contracts/packages/README.md`.
**Testing** A v6 Cache instance seeds no v7-only default, renders no v7-only field and pays out none; the same package at v7 does all three; a v6-only field behaves in the opposite direction; version-free fields are unaffected at both. Asserted at all three levels on purpose — this was three separate omissions of one argument, and **both directions** are asserted because a suite checking only *added-in-v7* would pass against an implementation that filtered nothing on the v6 side.
**Acceptance** ☐ the package expresses a field ADDED and a field REMOVED between versions ☐ no non-test `project()` call omits the version ☐ v6 draft, render and payload all exclude v7-only fields, and include the v6-only one ☐ existing `mergeReset`/`useDesignerDraft` cases keep their meaning
**Verify** `npm run lint && npm run typecheck && npm test -- src/features/designer`
**Ready** `READY`

#### FE-122 · The Designer read carries the package, not a projection (resolves §16 Q19)

**Objective** One projection path in the codebase, sourced from package JSON — and the deletion `FE-113` left open, discharged.
**Refs** `REQ-001` · `UI-D14` `UI-D15` `FE-D7` `FE-D17` · §16 **Q19** · **produced `FE-D33` `FE-D34`** · `dynamic-ui/design.md` §3.4/§3.5 · `architecture.md` §5.1
**Deps** 033, 046, 113, 121 · **Executes in** Phase 4
**Files** *Add:* `contracts/packages/file-mesh/package.json`, `src/shared/testdoubles/data/packages.ts` · *Modify:* `eslint.config.js`, `src/features/designer/configRepository.ts`, `src/shared/testdoubles/data/{designer,instances}.ts`, `src/shared/testdoubles/handlers/dp.ts`, `src/app/dev/devPackageHandlers.ts`, `contracts/packages/README.md` · *Delete:* `src/app/dev/{toDesignerViewModel,viewModelFor,viewModelFor.test,cachePackageEquivalence}.ts`
**Implementation**
- **§16 Q19 resolved: the browser projects.** `DesignerLoad` becomes `{ package, packageVersion, values, observed, configGeneration }`. This is Q19's own recommendation (*"Build for the first — design §10's sequence diagram shows the browser projecting"*) and what every Phase 4 component already assumes: they all take contract types, never `DesignerViewModel`. A pre-projected snapshot also cannot re-evaluate a `when:` predicate as the operator edits, which is the screen's whole point. **The frontend side only** — the wire encoding stays the backend sub-spec's.
- **Boundaries policy widened: `shared → contracts`.** `contracts/` is a leaf — it imports nothing, not React, not the DOM, not `@shared/*` — so this creates no cycle and mirrors the `app → contracts` and `feature → contracts` policies that already exist. First of the two options [`reports/task-113-report.md`](reports/task-113-report.md) named. `shared → feature` stays disallowed, and gets a lint fixture proving it.
- **`file-mesh/package.json` derived, not transcribed**, from `FILE_MESH_SOURCE`/`FILE_MESH_SINK` with a whole-object equivalence test that is deleted along with its subjects — the same method `cache/package.json` was authored by.
- **`CACHE_VIEW_MODEL`, `FILE_MESH_VIEW_MODEL` and the `Vm*` types deleted.** The **observation** fixtures in the same file (`ROLLOUT_REPLICAS`, `DIVERGED_REPLICAS`, `DIFF_DRAFT`, `CONFLICT_THEIRS`, `RESTART_STRATEGIES`, `ReplicaObservationFixture`, `DiffRowFixture`, `SECRET_MASK_TOKEN`) **stay** — `RolloutCard`, `ReplicaTable`, `ConflictDialog`, `StrategyPicker` and `applyRepository` depend on them and they duplicate no view model.
- `InstanceRow.packageVersion` can now import `PackageVersionId`; its "not importable from `shared`" comment is deleted, not left to mislead.
**Testing** File Mesh package-vs-fixture equivalence before the fixture is removed; the widened policy still errors on `shared → feature`; every Designer route serves a package; a dropped package still reaches the Designer through the same one path.
**Acceptance** ☐ `DesignerViewModel` and both baked view models gone ☐ observation fixtures retained ☐ `shared → feature` still refused ☐ dev-loaded and fixture packages take the same code path
**Verify** `npm run lint && npm run typecheck && npm test && npm run build`
**Ready** `FIXTURE` — Q19 is answered for the frontend; Q8 (generation field name) and Q9 (409 body) are unchanged.

#### FE-123 · `DesignerPage` — the screen, and the route that renders it

**Objective** Compose the ~25 Designer components — every one built, tested, and until now without a caller — into a screen, and replace the *"coming in a later phase"* placeholder.
**Refs** `REQ-001` `REQ-006` · `UI-D4` `UI-D12` `UI-D16` `FE-D17` `FE-D18` `FE-D20` `FE-D30` · **produced `FE-D35` `FE-D36`** · design §10, §14 · UI §A.8 · reference `app/designer.jsx`
**Deps** 044, 047–059, 121, 122 · **Executes in** Phase 4
**Files** *Add:* `src/features/designer/index.ts`, `src/features/designer/DesignerPage/{DesignerPage.tsx,DesignerPage.types.ts,DesignerForm.tsx,componentSelection.ts,observedState.ts,index.ts}` · *Modify:* `src/features/instance/InstanceShell.tsx`, `src/app/routes/InstanceCategoryRoute.tsx`, `src/shared/api/queryKeys.ts`, `src/shared/i18n/messages.en.ts`
**Implementation**
- **`DesignerPage` routes between five states and does nothing else**: skeleton · error-with-retry · *this package declares nothing for this version* · form · (`FE-124`) composer. Projection, validation and save logic all already live elsewhere and are not restated.
- **`deriveComponentSelection(pkg, packageVersion)` is the single source of truth** for which components exist. ⚠️ **`one-of-many` composition is not invented** — nothing in the console produces per-instance composition data (`FE-115` asks identity only), so this returns the degenerate `exactly-one` case, `instanceId = type`, and says so.
- `DesignerForm` is split out so the page file stays a state router rather than a 400-line component; layout follows `reference/app/designer.jsx` — sticky section nav, scrolling form pane, pinned save bar.
- **The generation keys the draft.** `useDesignerDraft`'s `initialDraft` is stable by contract, so a save produces a new baseline by remount, never an optimistic edit (`FE-D20`).
- A `409` from `SaveBar`'s `onSaveError` opens `ConflictDialog`; `SaveBar` already suppresses its own inline alert for that status, so the two never both show.
- `instanceDesignerPlaceholder` is **deleted** from the catalogue; governance and monitoring keep theirs.
**Testing** All five states from fixtures; a v6 instance renders no v7 field and a v7 one does; a discriminator change reveals a dependent field **with no network request**; a saved value beats a package default; drift disables everything and hides the save bar; axe over the editable and blocked states.
**Acceptance** ☐ the Designer renders from a package alone, zero per-solution React ☐ a package declaring nothing for this version says so rather than rendering blank ☐ no refetch on a discriminator change ☐ `InstanceShell`'s placeholder test rewritten, not deleted
**Verify** `npm run lint && npm run typecheck && npm test && npm run build`
**Ready** `FIXTURE`

#### FE-124 · The composer branch and the rollout/apply half

**Objective** Multi-component solutions get the canvas; the rollout and apply controls appear on the page, wired to an endpoint that only ever answers `INDETERMINATE`.
**Refs** `REQ-006` · `UI-D1` `UI-D21` `SM-D22` `FE-D18` clause B `FE-D20` `G11` · design §10.1, §13 · UI §A.8 · `DECISIONS.md` **open item 1**
**Deps** 060–063, 122, 123 · **Executes in** Phase 4
**Files** *Modify:* `src/features/designer/DesignerPage/{DesignerPage.tsx,DesignerForm.tsx}`, `src/shared/i18n/messages.en.ts`
**Implementation**
- `componentSelection.length > 1` renders a `Composer`/`All fields` toggle; one component renders the form with no toggle. **One draft, two views** — never a second `useDesignerDraft`.
- `RolloutCard`, `StrategyPicker`, `RestartConfirm`, `ApplyOutcomePanel`, `DivergedBanner` and `RollbackDialog` wired to `applyRepository`, which answers `INDETERMINATE` and nothing else.
- ⚠️ **`G11` is the acceptance criterion.** The outcome panel reports what was observed — config written, a restart seen — never that the process is using it. No stronger verdict is *available*: `DECISIONS.md` open item 1 means there is no `dp-remote` apply protocol with generation ordering or idempotency. Stated in the module header, not just in copy.
**Testing** Toggle preserves the draft; Apply offered only when nothing is unsaved; restart confirmed with a replica count; `APPLY_INDETERMINATE` rendered and no success verdict anywhere; diverged banner offers three remediations and no "force healthy".
**Acceptance** ☐ single-component solutions show no toggle ☐ switching views loses no edit ☐ `grep -riE 'applied successfully|apply succeeded'` finds nothing ☐ open item 1 not claimed as closed
**Verify** `npm run lint && npm run typecheck && npm test -- src/features/designer`
**Ready** `BLOCKED` — ships **UX only**, wire call blocked on open item 1, exactly as `FE-061`/`FE-062` do.

#### FE-125 · Designer reflow, accessibility sweep, and the Gate D journey

**Objective** The Designer reflows to 320px, passes axe, and Phase 4's exit gate is demonstrated rather than asserted.
**Refs** `G16` `G17` `FE-D29` `FE-D18` · WCAG 2.2 AA SC 1.4.10 · [`execution.md`](execution.md) Gate D
**Deps** 105, 108, 123, 124 · **Executes in** Phase 4
**Files** *Modify:* `src/features/designer/DesignerReflow.test.tsx`, `e2e/smoke.spec.ts`
**Implementation**
- The reflow harness keeps its 100-field / 10-section scale and its "one section at a time" constraint (mounting 100 MUI `FieldRow`s together starves the vitest pool) but drives the **real** `DesignerPage` instead of hand-built `ViewSection` literals and a stub repository.
- Gate D journey 1 in Playwright: a **v6** instance shows no v7 field, an edit saves and moves the generation, and apply renders `APPLY_INDETERMINATE`. Uses a real instance id from `data/instances.ts` — never an invented one.
**Testing** Reflow at `reflowFloorPx` (320): no two-dimensional scroll, every field reachable by jump, save bar operable. Production bundle greps clean of MSW, fixtures and dev stores (`G20`, `FE-D24`).
**Acceptance** ☐ 320px with no horizontal page scroll ☐ axe clean ☐ Gate D journey green ☐ no dev-only code in `dist/`
**Verify** `npm run lint && npm run typecheck && npm test && npm run build && npm run test:e2e`
**Ready** `FIXTURE`

---

## 7. Dependency graph

```
                    ┌──────────────── 001 scaffold ────────────────┐
                    ▼                                              ▼
              002 tokens ──► 003 theme ──► 004 router          008 test harness
                    │            │              │                   │
                    │            ▼              ▼                   │
                    │        010 gallery    005 config          009 guardrails
                    │            │              │
                    └────────────┴──────┬───────┘
                                        ▼
                                   105 breakpoints + reflow harness
                                        │
                     ╔══════════════ GATE A: Phase 0 green ══════════════╗
                     ║  build · lint · typecheck · unit · a11y · e2e     ║
                     ╚═══════════════════════════════════════════════════╝
                          │                                    │
        ┌─────────────────┴─────────────────┐   ┌──────────────┴───────────────┐
        ▼  Phase 1 (UI kernel)              ▼   ▼  Phase 2 (seams)             ▼
   011 012 013 014 015 016 017 018      026 ─► 027 ─► 028 ─► 029
   019 020 021 022 023 024 025          030 ─► 031        032        033 034 035
        └─────────────────┬─────────────────┘   └──────────────┬───────────────┘
                          ▼                                    ▼
                     ╔══════════════ GATE B: kernel + seam ══════════════╗
                     ║  a component can be built against a repository    ║
                     ║  interface with no knowledge of its backing       ║
                     ╚═══════════════════════════════════════════════════╝
                                        │
                                        ▼
                              036 AppShell ──► 044 InstanceHeader
                                  │  │  │            │
                    ┌─────────────┘  │  └──────┐     │
                    ▼                ▼         ▼     │
                  037 login       038 home   039 solutions ─► 040 load pkg
                                              041 grid ─► 042 URL ─► 043 bulk ─► 045 declare/403
                                              106 responsive shell ──► 107 table/card (deps 020, 041, 105)
                                        │
                     ╔══════════════ GATE C: Phase 3 ════════════════════╗
                     ║  every screen renders 5 states from fixtures      ║
                     ╚═══════════════════════════════════════════════════╝
                                        │
        ┌───────────────────────────────┼───────────────────────────────┐
        ▼ Phase 4 Designer              ▼ Phase 5 Governance            ▼ Phase 6 Monitoring
   046 project() ─► 047 registry   064 health ─► 065 ladder       068 caps ─► 069 catalogs
        │              ▼                    ▼                          │
        │           048 FieldRow        066 logs ─► 067 states         ├─► 070 buildQuery
        │              ▼                                               │
        │           049 SectionCard ─► 050 nav ─► 051 search      071 projectSeries
        │              ▼                                               ▼
        └──────────► 052 RHF draft ─► 053 validation ─► 054 summary   072 ChartRenderer
                        ▼                                              ▼
                     055 save ─► 056 conflict                    073 gaps ─► 074 tooltip ─► 075 a11y
                        ▼        057 override                          ▼
                     058 actions  059 version states             076 marks
                        ▼                                              ▼
                     060 rollout ─► 061 apply ⚠ ─► 062 diverged ⚠  077 explore shell
                        ▼                                              ├─ 078 instances
                     063 composer (gated) ─► 108 resp. Designer     ├─ 079 metric
                                                                       ├─ 080 aggregation
                                                                       └─ 081 range/resolution
                                                                            ▼
                                                                       082 states ─► 083 draft
                                                                            ▼
                                                                       084 save widget
                                                                            ▼
                                                                       085 dashboard ─► 086 grid ─► 087 layers
                                                                            ▼         088 export   089 first run
                                                                       109 responsive Monitoring
        ┌───────────────────────────────────────────────────────────────────┘
        ▼
   ╔══════════════ GATE D: walking skeleton accepted ══════════════╗
   ║  fixture e2e: save w/ generation, APPLY_INDETERMINATE (not prod apply) ║
   ╚════════════════════════════════════════════════════════════════╝
        │
        ├──► Phase 7 (gated on §16 Q4/Q5): 090 ─► 091 · 092 ─► 093 · 094 · 095 ⚠
        │
        └──► Phase 8: 096 097 098 099 100 101 102 103 ─► 104
                       110 reflow CI (deps 105–109, 098)

  ⚠ = BLOCKED on DECISIONS.md open item 1 (dp-remote apply protocol) for its wire call
```

### Critical path

`001 → 003 → 004 → (011–025 ∥ 026–035) → 036 → 044 → 046 → 052 → 055 → 061 → e2e journey 1`

Everything else hangs off that spine. **Task 046 (`project()`) is the longest single-owner item on the path** — start it in parallel with Phase 1, since it depends only on the test harness (008) and imports nothing from `src/`.

---

## 8. Parallel workstreams

Five streams. Each names its shared resources and its merge-conflict hot spots.

| Stream | Owns | Tasks | Starts after | Shared files (conflict risk) | Interface it must publish first |
|---|---|---|---|---|---|
| **A · Platform** | scaffold, theme, router, config, guardrails, CI, hardening, reflow harness | 001–010, 105, 096–104, 110 | — | `eslint.config.js`, `vite.config.ts`, CI workflow, `providers.tsx` | Theme token names + the route table (001–004); breakpoints (105) |
| **B · Design system** | every `shared/ui` primitive + vocabularies | 011–025 | Gate A | `src/shared/ui/index.ts` (**barrel — highest conflict risk**), gallery | Component props for `Button`, `TextField`, `StateChip`, `DataTable`, `Dialog` |
| **C · Seams** | http, clients, errors, auth, authz, fixtures, contracts | 026–035 | Gate A | `queryKeys.ts`, `errorCodes.ts`, MSW handler index | Repository interfaces + `ApiError` shape |
| **D · Engines** | `project()`, `projectSeries()`, `buildQuery`, fixture suites | 046, 070, 071 | 008 only | `contracts/fixtures/README.md` | `ViewModel` and `ChartModel` types |
| **E · Features** | every screen + responsive adaptations of those screens | 036–045, 047–069, 072–095, 106–109 | Gate B (+ D for 4/6; 105 for 106–109) | per-feature — **low conflict by construction** | — |

### Rules that keep the streams from colliding

1. **Publish the interface before the implementation.** Streams B, C and D each land a **types-only PR first** (component props, repository interfaces, engine types). Stream E codes against those types while the implementations are still in flight.
2. **The barrel is append-only.** `src/shared/ui/index.ts` is alphabetically sorted and only ever gains lines — mechanical conflicts resolve trivially.
3. **One feature, one owner, one folder.** Within Phase 3–7, `features/<name>/` is a single owner's tree. Cross-feature reuse goes through `shared/`, never a deep import (enforced, task 009).
4. **Fixtures are owned by C, consumed by E.** A feature that needs a new fixture state raises it to C rather than editing the dataset — otherwise two features silently disagree about what "partial" means.
5. **The engines are owned by D and by the backend jointly.** A change to a fixture in `contracts/fixtures/` is a **contract change** and follows the single-writer rule (`contracts/README.md`) — reviewed, never a convenience edit.

### Safe simultaneous starts

- Day 1: A (001) alone.
- After Gate A: **B, C and D run fully in parallel** — three independent trees, no shared feature code.
- After Gate B: **E forks into four**: inventory (036–045), designer (047–063), governance (064–067), monitoring (072–089). Designer needs D's `project()`; monitoring needs D's `projectSeries()`; the other two need neither.
- Phase 7 and Phase 8 overlap E's tail: 096–103 can begin as soon as any feature is merged, since each is a check rather than a feature.

---

## 9. API / fixture seam

### 9.1 The rule

**A component never knows whether a fixture or a server answered.** It calls a hook; the hook calls a repository; a provider decides which repository implementation is bound. Swapping is a one-line provider change, and no component, no query key and no type changes.

```
Component ──► useXxx() ──► XxxRepository (interface, hand-typed today)
                                 ├── fixtureXxxRepository   (MSW, Phase 2)
                                 └── httpXxxRepository       (cp/dp/consoleClient)
```

### 9.2 What is actually specified versus what is TBD

**Specified to endpoint level** — `dynamic-metrics` §5.4–5.8. These may be implemented against the real shape today:

| Route | Method | Used by |
|---|---|---|
| `{console}/capabilities` | GET | 068 |
| `{console}/entity-types` · `/entity-types/{t}/instances` | GET | 069 |
| `{console}/metrics/catalog` | GET | 069, 079 |
| `{console}/labels/{k}/values` | GET | 080 |
| `{console}/query/range` | POST | 070, 082, 086 |
| `{console}/query/instant` | POST | 076 |
| `{console}/export/range` | POST (streaming CSV) | 088 |

**Everything else is `API CONTRACT — TBD`.** For each: what the UI needs, what it must send, what it must receive back, and what is unknown.

| Seam | UI requirement | Frontend interface | Sends | Needs back | Unknown — what is required |
|---|---|---|---|---|---|
| **Auth** | login, identity, logout, expiry | `AuthAdapter` (030) | credentials | identity, roles/permissions, **tenant display string** | Login route; session cookie attributes; **CSRF mechanism** (Q3); the permission-set response shape |
| **Home aggregate** | 4 stat tiles | `homeRepository` (038) | — | counts, and an explicit "unknown" for each | Whole endpoint. Whether an unknown count is `null` vs absent — the UI must render `—`, so absence must be expressible |
| **Attention feed** | ranked feed with deep links | `homeRepository` (038) | — | items: severity, title, detail, target instance, kind | Whole endpoint; **the severity vocabulary**; ordering and dedupe rules; whether items are derived or stored |
| **Solution catalog** | package cards | `solutionsRepository` (039) | — | package metadata, provenance/signature, `appliesTo`, components, entitlement, instance count | Route; **signature-verification result shape**; **authoring review counts have no schema field at all** |
| **Package load** | upload + verify + admit | `solutionsRepository` (040) | package file | verification verdict, dev-mode admission state | Whole endpoint; multipart vs pre-signed; **signature failure taxonomy** |
| **Instance inventory** | the grid | `instancesRepository` (041) | filters, paging | per-instance: env, org/sub-org, driver, locus, health rollup + per-replica, **the specific not-found state**, version state + observed/declared, config state, replica list, last-seen | Route; paging model; **the not-found discriminator's wire name**; whether `lastSeen: never` is `null` |
| **Declare instance** | create | `instancesRepository` (045) | — | — | Everything. **Do not invent a form** |
| **Designer view model** | render the form | `configRepository` (046, 055) | instance id, declared version | the `project()` inputs: package, component selection, draft values, observed state | Route(s); **whether the server returns the package + values or a pre-projected view model** — a materially different client |
| **Save draft** | optimistic-lock save | `configRepository` (055) | rendered field set + **last-seen generation** + override reason | new generation | **The generation field's name** (Q8 — cheap, unblocked); the override-reason field; the history record shape |
| **409 conflict** | diff and re-apply | `configRepository` (056) | — | the current record, enough to diff | **The `409` body shape**; and there is **no actor and no timestamp**, so the UI cannot say who conflicted or when (Q9) |
| **Apply / restart** | stage + restart | `applyRepository` (061) | strategy, instance/replica scope | **observed state**, never a success verdict | ⛔ **BLOCKED** — open item 1. No generation ordering, no idempotency key, no lease, no crash-safe resume for `dp-remote` |
| **Rollout / replicas** | per-replica table | `applyRepository` (060) | — | per replica: rendered config version, restart-observed, health | **No row schema exists** — both column names are invented (Q12) |
| **Version reconciliation** | accept / restore | `configRepository` (059) | target version | the 4-way classified diff incl. **renames needing mapping** | Routes; the rename-mapping submission shape |
| **Package actions** | test-connection etc. | `actionsRepository` (058) | action key + input field **references** | a verdict, **never a credential** | Route; verdict shape; timeout semantics |
| **Secrets** | set / replace / clear | `configRepository` (023) | a set-secret intent | a `secretRef` | Route; whether the browser posts the value to the gateway or to a secret backend; rotation flow |
| **Health** | Governance hero | `governanceRepository` (064) | — | rollup + per-replica states, and `not-declared` distinguishable from `not-responding` | Route; the state discriminator's wire name |
| **Logs** | stream, level, audit | `governanceRepository` (066) | component, level | a stream, and a stated no-endpoint condition | Whole console↔backend seam. `UI-D10` describes the **MI's** actuator endpoints, not the console's (Q13). Transport (SSE? chunked? WebSocket?) unknown |
| **Dashboards** | list, read, save, hide, reset | `dashboardRepository` (084–087) | widget config, layout, hide override | dashboard document | ⛔ **The entire document has no shape**: envelope, hidden-default representation, dashboard-level `range`/`refresh`, whether `{x,y,w,h,order}` live on the widget or a placement entry, a **list route**, the **reset payload** (Q15) |
| **Metric typing** | declare a type | `metricsRepository` (079) | metric name, type, unit | updated catalog entry | Write route. Also: the catalog entry has **no `firstSeen` and no suggested-type field** (Q14) |
| **RBAC admin** | roles, assignments, users | `adminRepository` (090–094) | — | catalog, roles, principals, scope tree, assignments, users | Everything. Plus the **verb catalog is unratified** (Q4) and the **first-admin bootstrap is unspecified** (Q5) |

### 9.3 Fixture discipline

1. Every fixture module opens with a header naming **what is specified (with a citation)** and **what is invented**.
2. Fixtures live in `src/shared/testdoubles/`; **no feature imports `msw`**.
3. `onUnhandledRequest: "error"` — an un-fixtured call fails the test rather than passing silently.
4. **A fixture is never promoted to a contract.** When a real contract lands, the hand type in `shared/api/contracts/` is **replaced wholesale** by the generated type (`FE-D7`) — never extended.
5. A fixture that models an invented shape carries `// INVENTED —` on the field, so a grep enumerates the plan's own debt.

---

## 10. UI state matrix

Every cell is an implementation obligation with a test. `—` = not applicable to that screen. **The rules in bold are `must not`s from the design; a test asserts each one.**

| Screen | Initial | Loading | Success | Empty / first-run | Validation error | API error | Permission denied | Disabled | Confirmation | Restart / processing |
|---|---|---|---|---|---|---|---|---|---|---|
| **Login** | idle form | submitting, submit disabled | redirect to attempted URL | — | inline, **never naming which field** | *"can't reach Solution Manager"* | — | submit while in flight | — | arrivals: session-expired · idle-timeout |
| **Home** | — | skeleton strip + feed | tiles + attention feed | *"load a Solution Package"*, counts `—` | — | *"couldn't load your home view"* — **error ≠ all-clear** | non-super-admin: *ask your administrator* | — | — | partial: banner + `11 +1?`, **unknown is `—` never `0`** |
| **Solutions** | — | card skeletons | package cards | *"no packages loaded"* + load action | — | *"couldn't load the catalog"* + retry | Load action **omitted** | unsigned card's Open disabled with reason | load dialog | — |
| **Instances** | — | grid skeleton rows | grid + expandable replicas | *"no instances declared"* + declare→apply→restart→observable explainer | — | *"couldn't load instances"* — **distinct from "no instances" and from "all healthy"** | Declare/Restart **omitted** | at-selection-zero the bulk action is absent | bulk restart dialog | rows carry `restart_expected` / `staged` states |
| **Designer** | view model loaded, form pristine | form skeleton | fields render, save bar shows *"no unsaved changes"* | *"no fields apply here with the current selections"* per section | field-level + summary + section dots + **save disabled** | *"couldn't load the configuration model"* | Save/Apply **omitted**; blocked edits **disabled with reason** | Apply disabled until saved; everything disabled when version-blocked | save-with-warnings reason · apply strategy · restart · rollback · accept/restore | rollout card, per-replica table, **`APPLY_INDETERMINATE`** |
| **Designer · blocked** | — | — | — | — | — | — | — | **read-only, not unavailable — config stays fully legible** | accept-runtime · restore-runtime | — |
| **Designer · diverged** | — | — | — | — | — | — | — | — | 3 remediations; **rollback shows a diff first**; **no "force healthy"** | replica generations marked |
| **Composer** | node selected | — | canvas + inspector | palette from package data | required-empty count per node | — | — | — | — | **node moves are not config changes** |
| **Governance** | — | hero + card skeletons | hero + replicas + ladder + logs | `not-declared` — **never red, never an outage** | — | *"couldn't load health"* — **distinct from "not responding"** | — | log level control disabled where unsupported | DEBUG raise (privileged, audited) | `RESTART_EXPECTED` is **expected activity, not an outage** |
| **Logs** | not streaming | *"waiting for output…"* | tailing lines | — | — | — | — | — | DEBUG confirm | **no-endpoint = stated degradation, not an error, not silence** |
| **Explore** | step 1 active, 2–5 locked with reasons | chart skeleton at final dimensions | chart + legend + tooltip | *"select at least one instance"* — **never an empty axis** | typed `422` inline, **never retried** | *"explore failed to load"* + retry | Raw **omitted** without the verb | at-cap instances disabled + **two routes forward**; groupBy locked without aggregation | save-as-widget | refreshing **dims, never blanks** |
| **Explore · data** | — | — | — | *"no samples in this range"* + served tier + nearest-data hint — **never a zero line** | — | partial: banner naming **each** unavailable instance and why | — | log scale disabled for counter-rate **with the reason** | — | gaps **break** the line; low coverage hollow; retention band + boundary |
| **Dashboard** | default dashboard renders | per-widget skeletons | grid of widgets | **entity types + counts + one action — never an empty grid** | — | per-widget boundary; **one failure never takes the dashboard down** | Raw/export **omitted** without the verb | hidden defaults show Restore, **never Delete** | reset defaults · export | refresh dims; off-screen widgets **do not query**; newer schema **read-only and whole** |
| **Admin** | roles tab | — | roles/assignments/users | *"no assignments reach this scope"* | — | — | non-grantable verbs shown as **not grantable**, never as grantable | built-in roles read-only but **inspectable and cloneable** | create/edit role · new assignment · deactivate user | — |
| **Targets** | read-only list | — | list + model | — | — | — | — | **credential entry absent by design** | — | — |
| **403** | — | — | — | — | — | *"not permitted"* + correlation id, **without naming the permission** | — | — | — | — |

---

## 11. Testing strategy

**One rule governs the whole pyramid: a test asserts a `must not`, not only a happy path.** The design is full of them, and each is where the product's honesty actually lives.

### 11.1 Unit — pure logic, no DOM

| Target | Cases |
|---|---|
| `project()` (046) | defaults-applied predicates · three-deep discriminator chain · switch-away-and-back retention · **rendered set excludes hidden branches** · advanced flag · secret masking · four version states |
| `projectSeries()` (071) | **absent produces no point** · server-declared gaps · **no delta across a gap** · counter rate/delta · low coverage · retention truncation · per-series state · **sequential colours, no collision** |
| `buildQuery` + hash (070) | order-insensitivity · sorted instance ids · relative→absolute at issue · one metric per request |
| Error codec (028) | per-code mapping · **`422` never retried** · unknown code still surfaces |
| Retry / abort (026) | 5xx backoff · abort-before-issue ordering · unknown-field tolerance |
| Idle timer (031) | fires at N · resets on activity · routes with the right arrival |
| Permissions (032) | omit vs disable · no v1 gate on a non-grantable verb |
| Formatting (007) | UTC→local with the zone label · plurals · units per `unit` string |
| Widget migrations (084) | ordered chain · **unknown fields preserved** |

### 11.2 Component — Testing Library

Every Phase 1 primitive, plus for each feature: **each state in §10's row**. Specific must-haves:

- `StateChip` renders **text + icon**, never colour alone, for every vocabulary entry (5 health · 5 not-found · 4 version · 6 config · 3 driver · 3 locus).
- `DiffView` **cannot render a secret** (a type test plus a runtime test).
- `SecretField` never renders a value and has **no reveal control**.
- Designer: an inactive branch's keys are **absent from the save payload**.
- Designer: the config stepper **cannot mark `effective-verified` reached** from any fixture.
- Designer: `VERSION_UNVERIFIED` is **editable**; `VERSION_DRIFT` is **read-only but legible**.
- Governance: `NOT_APPLICABLE` never uses the error tone; a standby is distinguishable from healthy-and-working.
- Explore: the projected-series count is `Σ replicas` in replica mode; at-cap disables with two routes forward.
- Dashboard: an off-screen widget issues **no** request; a throwing widget leaves siblings refreshing.
- Every screen: the **error state is distinguishable from the empty state** (`FE-D10`).

### 11.3 Integration — feature flows against the fixture seam

Route guards and redirects · URL round-trips for selection, focus, range and dashboard id · the CP+DP composition hook returning one coherent view · query dedup across a dashboard · `409` → refetch → diff → re-apply · capabilities failure → fail-closed banner and conservative controls.

### 11.4 End-to-end — Playwright

The seven journeys in task 099. **Journey 1 is the walking-skeleton acceptance gate** and is named in design §19; journey 3 (gap ≠ zero) is the one that proves the product's core honesty claim end to end.

### 11.5 Accessibility

Axe over the gallery and every screen state in **both themes**, in CI (task 098). A written manual keyboard script, run per release, covering the paths axe cannot reach: focus trap and restore, jump-to-field focus, canvas node keyboard operation, the chart keyboard model, and dashboard `order` traversal. **The chart tabular alternative is tested as a feature, not as a fallback.**

### 11.6 Regression

- **The shared fixture suite is the primary regression net.** CI fails if `contracts/fixtures/` drifts from the copy the backend runs — *one suite, two runners*.
- Guardrail lint fixtures (task 009): each rule has a file that must fail.
- Production-bundle assertions (task 101): six greps that must fail when violated.
- Palette/contrast validation (task 097) runs on every theme change.
- Bundle budget (task 096) runs on every build.

### 11.7 What we deliberately do not test

- **Visual regression is Phase 2**, after the tokens are accepted (design §19). Snapshotting an unverified palette bakes in a colour we may have to change.
- **No coverage-number target.** Tests exist for behaviour; a coverage threshold would reward testing the registry's barrel file.

---

## 12. Security, accessibility, performance — and the other non-functionals

### 12.1 Security

> **Stated once, prominently, and repeated in `shared/authz/`: hiding a UI control is not a security boundary.** Frontend RBAC is cosmetic. Every request still hits deny-by-default on the server (design §15). A reviewer who accepts "the button is hidden" as the control has accepted nothing.

| Concern | Position | Task |
|---|---|---|
| **Authentication boundary** | Local session v1; the adapter makes OIDC an implementation swap. `D13` (Keycloak) is deferred. | 030 |
| **Token handling** | **httpOnly, `SameSite=strict` cookie set by the gateway.** **No refresh token in `localStorage`** (`FE-D14`). In-memory access token only if the API cannot set cookies. | 030 |
| **CSRF** | A token header hook exists on every mutation. **The mechanism itself is unresolved** (§16 Q3) — design §26 item 9. | 026, 030 |
| **Browser storage** | Colour mode, sidebar state, dismissed banners (Zustand-persisted). Explore drafts in **per-tab `sessionStorage`**. **Never a credential, never config values, never metric payloads.** | 029, 083 |
| **Secrets** | The browser **never receives a resolved secret** (`FE-D23`) — not in a form, a diff, an export, history, or a `yaml` blob. `type: yaml` is **gated to Phase 2** until server-side redaction exists. `test-connection` runs **server-side only**. | 023, 047, 058 |
| **Tenant isolation** | Tenant is **display-only**, from the server (`I5`, `FE-D8`). No client wire mechanism carries tenant as authority — there is nothing here for the server to trust. | 030, 036 |
| **Authorization presentation** | **Omit** when the verb is absent; **disable with a reason** when a capability or cap blocks. High-consequence actions confirm **even when authorised**. | 032, 016 |
| **Error exposure** | Typed `code` + the server's message + the correlation id. **The 403 page does not name the missing permission** until the information-leak question is decided (§16 Q11). | 028, 045 |
| **Input validation** | Client validation is UX. **The server validates.** Bind-tier blocks, semantic-tier warns; no business rule is hard-coded in the console. | 053 |
| **XSS** | React escapes by default; **`dangerouslySetInnerHTML` is banned by lint**, with no exception. Log content renders as text, never as markup. | 009, 066 |
| **Unsafe navigation** | External links are not a feature of this console. Any URL from server data is rendered as text, not as an `href`, unless a decision says otherwise. | 009 |
| **Air-gap** | No analytics, no beacons, no CDN, no third-party fonts (`REQ-003`). CI asserts zero third-party requests. | 100, 102 |
| **CSP** | `default-src 'self'`; any `unsafe-eval` for ECharts is a **recorded exception**, never silent. | 100 |
| **Supply chain** | Licences verified: React/Vite/MUI/Data Grid Community/TanStack Query/Zustand/React Router/RHF/Vitest/RTL **MIT**; ECharts and Playwright **Apache-2.0**. **No Pro/Premium package may enter the lockfile** (CI check). | 009, 096 |

### 12.2 Accessibility — WCAG 2.2 AA (C4)

Built in per task, not appended. Task 098 is the **sweep**, not the implementation.

| Topic | Rule | Where |
|---|---|---|
| Landmarks | skip link → `main`; nav labelled; `main` focused on route change | 036 |
| Keyboard | every Designer field, every composer node, dashboard `order` traversal, dialogs trapped, `Esc` closes, focus restored | 016, 050, 063, 086 |
| Focus | visible ring meeting contrast in **both** themes; focus moves into the panel after a tab change | 003, 018, 097 |
| Status | **text + icon + colour** — never colour alone, for every vocabulary entry (5 health · 5 not-found · 4 version · 6 config · 3 driver · 3 locus) | 013 |
| Contrast | chart colours ≥ 3:1 against **both** surfaces; text ≥ 4.5:1; **validated in CI, not by eye** | 097 |
| Charts | one focus stop; arrow-key cursor; **tabular alternative + text summary**; `aria` on, series names from the legend labels | 075 |
| Live regions | polite for toasts and refresh; **the log stream does not announce every line** | 021, 066 |
| Forms | `label` + `helperText` + `aria-describedby` + `aria-invalid`, wired by the wrapper so a field author cannot get it wrong | 012 |
| Motion | `prefers-reduced-motion` disables progressive chart animation and transitions | 003, 072 |
| **Reflow** | **SC 1.4.10 (AA): content reflows to 320 CSS px with no two-dimensional scrolling.** Data tables and persistent toolbars use the criterion's stated exceptions and scroll on one axis **inside their own container** — the page body never scrolls horizontally | **105, 106, 107, 108, 109, 110** |
| Orientation | SC 1.3.4 (AA): no screen locks to portrait or landscape | 105 |
| Hit target | 40px on primary actions (AA 2.5.8 requires 24px — this is a margin, **not** an AAA claim) | 011 |
| Colour-blind | categorical palette CB-safe; past palette size the second channel is **dash pattern**, not a reused colour | 071, 073 |
| i18n | English v1; **no concatenated sentences**; every time axis labels its zone | 007 |

**Residual, stated honestly:** canvas charts will still fail some screen-reader chart-exploration heuristics. The mitigation is the data table and the text summary — **not** a claim that ECharts is accessible (design §18).

### 12.3 Performance — justified, not speculative

| Pressure | Mitigation | Justification | Task |
|---|---|---|---|
| 10 series × ~1000 points | ECharts `canvas` + `large` + `progressive` | SVG DOM cost is visible at this size (`dynamic-metrics` §12) | 072 |
| Dashboard of N widgets | `IntersectionObserver` → Query `enabled` | Off-screen widgets querying is pure waste | 086 |
| Duplicate queries | canonical hash → shared `queryKey` → dedup | 8 widgets over 4 metrics = **4** requests | 070, 103 |
| Refresh storms | polling floored at the served bucket; **paused when hidden**; no window-focus refetch | `DM-D17`, `FE-D20` | 029, 085 |
| Blank-on-refresh | `placeholderData` keeps the previous series, dimmed | *"blanking a chart on refresh is how an operator misreads a refresh as an outage"* | 029 |
| 12+ instance rows, growing | Data Grid virtualization | Community feature, already in the stack | 020 |
| ~100–1,989-field forms | sections + **advanced disclosure**; re-project only on discriminator change; debounce **free text only** | A 132-field FTP component is unusable without disclosure | 049, 052 |
| Legend with many series | **virtualize, never truncate** | An unlabelled line is unidentifiable | 074 |
| Initial bundle | route-level splitting; **ECharts async**; budget in CI | ECharts is the single largest dependency | 096 |

**Deliberately not optimised:** no browser-side rollup (the server downsamples, `maxPointsPerSeries` ~1000); no six-month raw pagination in the browser — **export is the escape hatch** (design §22); no memoization pass without a measurement.

### 12.4 The remaining non-functionals

| NFR | Position |
|---|---|
| **Reliability** | §12.5's degradation table is the specification. The console degrades per failure class and **never blanks**. |
| **Maintainability** | One design system; one state model per lifetime; feature folders with barrel-only cross-imports, CI-enforced. Adding a **solution** is data; adding a **control kind** or a **mark** is an SM release (`UI-D14`). |
| **Scalability** | New solution → package data. New metric → catalog. New tenant → server isolation, no SPA change. New mark/control → a registry entry. SaaS split → coarse hooks already in place. |
| **Observability** | Correlation id (server's is authoritative) surfaced in toasts, error panels and the support menu. **No production error shipping until a backend log endpoint exists** — do not invent RUM for an air-gapped deployment. |
| **Compatibility** | Evergreen Chrome, Edge, Firefox, Safari (latest two) — `FE-D28` (proposed). **Responsive 320 px and up** — mobile, tablet and desktop (C3, `FE-D29`); reflow conformance is verified in CI (task 110). |
| **Testability** | The seam (§9) makes every screen testable without a backend; the engines are pure; MSW fails on any un-fixtured call. |
| **Backward compatibility (brief §17)** | **Largely not applicable — there is no frontend code.** No existing functionality to preserve, no component to refactor, no migration. The one real compatibility surface is **forward**: widget `schemaVersion` migrations must preserve unknown fields so an older console cannot destroy a newer one's widget (task 084). The File Mesh UI is a **pattern harvest checklist, not a code source** — and the repo is not on this machine (§2.1). |

---

## 13. Requirements traceability

| REQ | Status | Decision | Design § | UI screen | Tasks | Test coverage | Verdict |
|---|---|---|---|---|---|---|---|
| **REQ-001** — onboarding a solution needs no console code | DESIGNED | `D1` 🔒 `SM-D19` `SM-D20` `UI-D14` | §4, §10 | P3 P5 P5e P8 | 033, 039, **046, 047, 048, 049, 053**, 063, 069, 079 | Fixture suite; a test asserting **zero per-solution branches** and zero hard-coded business rules | ✅ **Covered** — the control registry + `project()` are the mechanism |
| **REQ-002** — File Mesh, Cache, DB MI | ACCEPTED | `B/D5` 🔒 | §15 phasing | P3 P5e | 039, 047, 063 | The File Mesh fixture (2 components, ~100 fields, virtual fields) renders through the same components as Cache | ⚠️ **Partial by design** — v1 is Cache; File Mesh proves no second architecture is needed |
| **REQ-003** — on-prem **and** SaaS | ACCEPTED | `D9` 🔒 `SM-D1` `FE-D19` | §3.1, §13, §21 | all | 005, 026, **027**, 100, 102 | Tests: one origin; no plane host in the bundle; **zero third-party requests** | ✅ **Covered** — gateway-only + air-gap verified |
| **REQ-004** — UI drives artifact deployment | **LOCKED, retirement proposed** | `SM-D2` (unratified) | — | — | **none** | — | ❌ **Deliberately not implemented.** Conflict C9. A locked requirement with no implementation is a **trace gap that must be recorded**, not silently closed |
| **REQ-005** — Docker + Kubernetes + bare Linux | ACCEPTED | `B/D5` 🔒 `SM-D25` | §12 | P4 P10 | **041**, 042, 095 | A test asserting **no component branches on `driver`** | ✅ **Covered** — runtime is row data |
| **REQ-006** — scoped RBAC | ACCEPTED | `D10` 🔒 `D13` 🔒 `SM-D23` `FE-D9` `FE-D22` | §15 | P13 P14, every action | **032**, 011, 015, 059, 090–094 | Omit-vs-disable component tests; e2e journey 6 | ⚠️ **Partial — blocked upstream.** The model ships; **the verb catalog is unratified** (C12, Q4). Two verbs are grantable |
| **REQ-007** — six months of metrics, raw queryable | ACCEPTED, ❌ **unproven** | `SM-D8` `SM-D26` `DM-D10` | §11 | P8 P9 | **068**, 081, 088 | A test asserting **no string promises guaranteed retention**; presets derived from `tiers` | ⚠️ **Frontend obligation met; the requirement itself is unproven.** The UI renders advertised retention as advertised |
| **REQ-008** — footprint-refusing customers | ACCEPTED | `SM-D12` `SM-D23` | §4.1 | P4 P10 | **041**, 095 | A test asserting **no component branches on `locus`**; the same console for all three loci | ✅ **Covered** — locus is row data, not a skin |
| **REQ-009** — no MI/MDK development | ACCEPTED | `HC1` `SM-D16` `I1` | throughout | P3 P6 P8 | **066**, 069, 064, 079 | Every degraded condition renders **the package's stated reason**; no empty state asks for an MI change | ✅ **Covered** — degradation is stated, never requested |

### 13.1 Requirements gaps this plan cannot close

| Gap | Why it matters |
|---|---|
| **`REQ-004` has no implementation and no ratified retirement.** | A locked requirement is formally unsatisfied. Implementing it would contradict `SM-D2` and `architecture.md`. **Ratify the retirement or re-scope the requirement** — do not let the console quietly not-do a locked requirement. |
| **No `REQ` for alerting, logs, package upload, or first-admin bootstrap.** | Four surfaces the prototype draws have no requirement behind them. Each is either scope creep or an unwritten requirement; §16 routes them. |
| **`REQ-007` is unbenchmarked** (open item 5). | The UI's copy discipline is the only mitigation available to the frontend. |

---

## 14. Decision traceability

Verifying that no task violates a decision. **Every `FE-D*` is `PROPOSED`** — the plan implements them as written and flags where they are gated.

| Decision | Design impact | Implementation area | Tasks | How the plan validates it |
|---|---|---|---|---|
| `D7` 🔒 React + Vite + MUI | The whole component layer | scaffold, theme, every primitive | 001, 003, 011–025 | CI bans a second UI library; the reference stylesheet's own header confirms the screens are MUI-structured (§B.2) |
| `D1` 🔒 config-driven + escape hatch | Renderer + named-screen registry | designer | 046, 063 | Registry exists and is **empty**; no module loader; test asserts both (C8) |
| `D9` 🔒 one SPA, collapsible to single-tenant | No tenant branch anywhere | shell, auth | 030, 036 | Tenant is display-only; no tenant in the route |
| `D10` 🔒 `resource.action`, deny by default | Every action's presentation | authz | 032 | Catalog is data with a status; **no v1 gate on a non-grantable verb** (C12) |
| `D13` 🔒 Keycloak, deferred | Auth adapter shape | auth | 030, 037 | Adapter interface; **empty SSO slot**, no unimplemented control |
| `D14` 🔒 PostgreSQL | The UI never speaks SQL | all | — | `DM-D1`: the query API is the sole boundary |
| `B/D15` 🔒 UTC canonical | Every timestamp | format, charts | 007, 081 | Payloads UTC; display via `Intl`; **the zone is always labelled** |
| `I1` no MI development | Degradation copy | governance, monitoring | 064, 066, 069 | No empty state asks for an MI change; every degradation states the package's reason |
| `I5` tenant never from the client | Auth, routing, clients | auth, router | 004, 030 | Tenant not a path segment, not a parameter; display only |
| `I6` secrets never in diffs/exports/history/logs | Secret field, diff, logger | 022, 023, 058, 102 | `DiffView` is **type-incapable** of rendering a secret; the logger rejects secret-shaped arguments |
| `I7` no CP↔DP crossing | Client split, composition | 027 | Composition in hooks; three clients; the third-module question flagged upstream (C5) |
| `FE-D1` TanStack Query owns server state | State model | 029 | No server data in Zustand — a lint rule on the store's type |
| `FE-D2` Zustand session UI; React ephemeral | State model | 003, 029 | Four lifetimes, §4.2, one owner each |
| `FE-D3` feature folders + shared kernel | Tree | 001, 009 | `eslint-plugin-boundaries` |
| `FE-D4` one design system, harvest wrappers | Phase 1 | 011–025 | No second library; harvest is a **pattern checklist** (the FM repo is absent) |
| `FE-D5` tokens only; paint blocked | Theme | 002, 003, 097 | No raw hex outside `shared/theme` (lint); provenance file; contrast validated in CI |
| `FE-D6` Router + URL workspace | Routing, selection | 004, 042, 083 | URL round-trip tests; **documents never in the URL** |
| `FE-D7` plane clients, OpenAPI types | API layer | 027, 034 | Hand types marked TBD and **replaced**, never extended |
| `FE-D8` auth adapter; tenant not from client | Auth | 030 | Adapter swap test; tenant display-only |
| `FE-D9` omit unauthorized; disable for limits | Every action | 032, 011 | Both modes exist, are distinct, and are tested |
| `FE-D10` typed errors; empty ≠ success | Errors, states | 028, 019, 006 | Every screen's error state is a distinct test from its empty state |
| `FE-D11` Data Grid **Community** | Tables | 020, 009 | CI greps the lockfile for Pro/Premium |
| `FE-D12` Vitest/RTL/Playwright + shared fixtures | Testing | 008, 046, 071, 099 | One suite, two runners; CI fails on fixture drift |
| `FE-D13` correlation: server id authoritative | HTTP, toasts, support | 026, 021, 049 | Response id wins; shown in three places |
| `FE-D14` static SPA, injected env, no secrets | Config, build | 005, 101 | Six production-bundle assertions |
| `FE-D15` strict TypeScript | Everything | 001, 009 | `no-explicit-any` error; strict flags |
| `FE-D16` ECharts only via `ChartRenderer` | Charts | 072, 009 | Import-graph test; single import site |
| `FE-D17` RHF drafts; save = `project()` rendered | Designer | 052, 055 | Payload test: hidden keys absent; `getValues` confined to one module |
| `FE-D18` A save generation / B apply **blocked** | Save + apply | 055 (A), 061 062 043 (B) | A: generation on every save. B: **`APPLY_INDETERMINATE` by default**, no success from a response |
| `FE-D19` browser → gateway only | Clients, config | 005, 027 | No `VITE_*_URL`; one origin; absolute URLs rejected |
| `FE-D20` fail-closed Query defaults | Query, apply | 029 | No focus refetch; no optimistic mutation; `422` never retried |
| `FE-D21` widget documents off the URL | Explore | 083 | `sessionStorage` + `?draft=` id; the sharing limitation is **stated** |
| `FE-D22` RBAC overlay after `project()` | Designer, authz | 046, 059, 032 | `project()` is auth-free — a fixture-level assertion |
| `FE-D23` no resolved secret in the browser | Secrets, yaml | 023, 047, 022 | No reveal control; `yaml` renders **no editor** |
| `FE-D24` idle lock; no production auth mock | Auth, build | 031, 101 | Idle test; mock absent from the production bundle |
| `UI-D2`–`UI-D4` predicate grammar, discriminators | `project()` | 046 | Fixture cases per obligation |
| `UI-D8` absence is never zero | Charts, home, inventory | 038, 071, 073, 082 | **No fixture produces a zero-filled point**; unknown counts render `—` |
| `UI-D9` health + two non-red qualifiers | Governance, inventory | 013, 041, 064 | Five rollups; standby ≠ healthy; `NOT_APPLICABLE` never red |
| `UI-D10` logs streamed, never persisted | Governance | 066 | A test asserting no log line reaches persistence |
| `UI-D12` version declared, `UNVERIFIED` editable | Designer, inventory | 041, 059 | `UNVERIFIED` is editable and not styled as a fault |
| `UI-D14` closed vocabularies, open data | Registry, marks, vocab | 013, 047, 076 | Registries are exhaustive at the type level |
| `UI-D15` view model is a pure function | `project()` | 046 | `contracts/` imports nothing from `src/` |
| `UI-D16` bind blocks, semantic warns + reason | Validation | 053, 057 | Override impossible without a recorded reason |
| `UI-D20`/`UI-D21` composer; layout is presentation | Composer | 063 | Node moves do not dirty the form; registry empty |
| `UI-D22` dynamic options + actions | Registry, actions | 047, 058 | `optionsSource.kind` closed at `sm-catalog`; actions server-side only |
| `UI-D23` yaml is a bounded tunnel | Registry | 047 | `yaml` renders **no editable surface** in v1 |
| `DM-D1`/`DM-D2` query API is the boundary; versioning | Metrics client | 070 | `queryVersion` always present; unknown fields ignored |
| `DM-D5` extra scopes | Dashboards | 084, 087 | **Not built** — v1 is `instance` + two layers (C7) |
| `DM-D6` versioned widget config | Widgets | 084 | Ordered migration chain; unknown fields preserved |
| `DM-D7` cap counts rendered series | Explore | 078 | `Σ replicas` projection; at-cap disabling |
| `DM-D8`/`DM-D22` type-aware aggregation | Explore | 080, 076 | Incoherent combinations are refused, not computed |
| `DM-D10` capabilities advertised | Monitoring | 068, 081 | Zero hardcoded presets/caps; fail-closed proven |
| `DM-D11` auto resolution + forced Raw | Toolbars | 081, 085 | No free bucket size; Raw is gated and confirmed |
| `DM-D12` `mark` is the sole axis | Marks | 076 | Three marks; `area` is an option |
| `DM-D13` ECharts | Charts | 072–074 | Single import site; theme derived from MUI |
| `DM-D14` streaming CSV v1, PNG Phase 2 | Export | 035, 088 | Streams, never buffers; no PNG affordance |
| `DM-D15` alerting seed only | Widgets | 084 | **No alerting affordance ships**; if ever shown, it states nobody is notified |
| `DM-D16` gaps server-declared | Charts | 071, 073 | Client never re-derives the threshold |
| `DM-D17` polling, floored, paused when hidden | Dashboard | 085, 103 | Verified in task 103 |
| `DM-D18` six new verbs | Authz | 032 | Rendered by catalog status; **no v1 gate on them** (C12) |
| `DM-D20` `projectSeries` pure + fixtures | Engine | 071 | Shared suite, two runners |
| `DM-D21` dashboard range/refresh authoritative | Dashboard | 085 | **No per-widget pinning exists** |
| `DM-D23` one metric per widget | Explore | 070, 079 | Single-select; type-level enforcement |
| `SM-D15` manual restart default | Apply | 061 | `manual` is the default and is labelled so |
| `SM-D22` restart-observed ≠ convergence | Rollout, ladder | 060, 065 | The fifth rung **cannot** render as reached |
| `SM-D27` durability declared per signal | Charts | 071, 074, 082 | Unverified durability carries a caveat badge |
| `SM-D30` no inferred metric type | Explore | 079 | No suggestion offered; the missing fields are flagged |
| `SM-D31` marks, resolution, dashboards | Metrics | 076, 081, 087 | Closed mark set; Auto/Raw only; two layers |

**No task in this plan violates a decision.** Three tasks (043, 061, 062) are **blocked by** one (`FE-D18` clause B) and ship their UX without their wire call; one (095) is blocked at the same place. **One** task (094) is decision-required, on Q5. 

**This plan additionally proposed five new decisions** — `FE-D25` (idle-lock draft preservation) · `FE-D26` (desktop-only ≥1280px, **superseded**) · `FE-D27` (a 403 never names its permission) · `FE-D28` (browser matrix) · `FE-D29` (responsive to 320 px, superseding `FE-D26`). They are **recorded as `PROPOSED` in [`../../design/frontend/decisions.md`](../../design/frontend/decisions.md)** (`I3`). They are not ratified (`I4`).

### 12.5 Resilience and error handling — behaviour per failure

**The console never blanks.** Chrome always renders; failure is scoped as tightly as the design allows. Where backend behaviour is required to make a frontend behaviour correct, that is named rather than assumed.

| Failure | Console behaviour | Backend behaviour required |
|---|---|---|
| **API unavailable (5xx / network)** | `ErrorPanel` in place with retry; Query backs off on 5xx only; chrome, nav and breadcrumbs stay mounted | — |
| **Query API unavailable specifically** | Dashboard chrome renders; **every widget** shows a scoped error with retry. **Never a blank page** | `503` (`dynamic-metrics` §5.8) |
| **Partial success** | Resolved series render; a banner names **each** unavailable instance **and why**. A partial fleet is never presented as complete | `200` + `partial.degraded` + `partial.unavailable[]`. ⚠️ **The item shape and reason vocabulary are invented** (Q9) |
| **Invalid / partial data** | Unknown response fields are **ignored, not rejected** (`DM-D2`); a missing required field surfaces a typed error, never a silent default | Additive changes must not bump the version |
| **Request timeout on a read** | Abort, show the error, offer retry | — |
| **Request timeout on an apply** | ⚠️ **`APPLY_INDETERMINATE`** — the request may still have landed on the host. **Resolve from observed state, never from the response.** Block further applies until the operator confirms actual; **operator-initiated retry only** | ⛔ **open item 1** — an apply id, DP-persisted per-host in-flight state, bounded retry and an explicit `APPLY_INDETERMINATE` state |
| **Connectivity lost** | Reads fail into `ErrorPanel`; **in-flight mutations are never assumed to have failed**; the Designer draft survives in the form | — |
| **Session expires** | Any `401` routes to `/login` with `arrival=session`; the attempted URL is preserved | Consistent `401` on session loss |
| **Idle timeout** | Local timer → `/login` with `arrival=idle`. ⚠️ **The "your edits were saved on this device" claim requires a durable local draft** — ship the mechanism or change the copy (Q6) | — |
| **User lacks permission** | Verb absent → control **omitted**; server refuses anyway → 403 page with a correlation id and **no permission name** (Q11) | Deny by default, server-side. **Hiding a control is not the boundary** |
| **Operation partially succeeds (rollout halts)** | **`CONFIG_DIVERGED`**: banner + per-replica generation table + **three remediations**; rollback previews a diff first. **No "force healthy."** Default multi-replica ordering is stage-everywhere-then-restart, so a halt leaves config **uniform-but-unapplied** | `CONFIG_DIVERGED` state + per-replica rendered-config version and restart-observed. ⚠️ **No row schema exists** (Q12) |
| **Restart in progress** | `RESTART_EXPECTED` is **expected activity, not an outage**; graphs render the window as a **gap, not a zero** | The suppression window opened by `PENDING_RESTART`, closed by `restart-observed` |
| **Refresh during an operation** | Route state is in the URL; the outcome badge persists on the instance; **the console re-reads observed state** rather than replaying a mutation | Observed state must be readable independently of the mutation's response |
| **Navigate away mid-operation** | The mutation is not cancelled client-side; the outcome lands on the instance header and in a toast when the user returns | — |
| **Duplicate requests** | Reads: identical in-flight keys **share one promise**. Mutations: the control disables in flight; **no automatic retry** — without an idempotency key a retry is a second write | ⛔ **open item 1** — an idempotency key would make a safe retry possible |
| **Concurrent save** | `409` → **no silent overwrite** → refetch + field-level diff. ⚠️ **No actor, no timestamp**, so the UI cannot say who conflicted or when | The `409` body (Q9); ideally actor + timestamp |
| **Concurrent apply** | ⚠️ **Unmitigated and stated as such.** Two operators each holding the current generation can both apply; last remote write wins, silently. **The UI must not imply it can detect this** | ⛔ **open item 1** — rollout generation ordering |
| **Capabilities fetch fails** | **Fail closed**: cap 10, presets to 24h, no forced-Raw, and a banner saying the UI is on conservative defaults | — |
| **One widget throws** | Its error boundary contains it; **siblings keep refreshing** | — |
| **Newer widget schema** | Renders **read-only and whole**, never partially; unknown fields preserved on save | `schemaVersion` on the document |
| **MI exposes no log endpoint** | A **stated degraded condition** — not an error, and not an empty stream implying silence | The package's `logs` declaration |
| **Metric arrives untyped** | Disabled in the picker with a typing affordance. **Never infer a type** | Quarantine, not rejection (`UI-D5`) |

---

## 15. Risks

| # | Risk | Impact | Probability | Mitigation | Blocking? |
|---|---|---|---|---|---|
| R1 | **`dp-remote` apply protocol does not exist** (open item 1) — no generation ordering, idempotency key, lease or crash-safe resume, **and the walking skeleton runs on that locus** | The apply CTA — the whole point of Designer — cannot be correct. Two operators can race and last write wins silently | **Certain today** | Ship the full UX with `APPLY_INDETERMINATE` by default, resolved from observed state; operator-initiated retry only; **state the unmitigated race rather than implying detection**. Tasks 043, 061, 062, 095 ship UX without the wire call | **BLOCKING** for those four |
| R2 | **Console REST is unwritten** for 7 of 9 seams | Fixture-built screens may need hook (not component) rework when contracts land | High | The repository seam (§9) confines rework to hooks; hand types are **replaced, never extended**; every fixture flags what is invented | Non-blocking — but **R2 is the largest source of rework** |
| R3 | **Everything rests on `PROPOSED` decisions** — `FE-D*`, `UI-D*`, `DM-D*`, `SM-D*` | Ratification could invalidate built work; the repo's own rule forbids starting | High | Per-task Readiness class; the Gate at the top; `instance`-scope-only dashboards and an empty named-screen registry avoid building the contested surfaces | **BLOCKING** at the plan level (§17 gate) |
| R4 | **Open item 19 not formally closed** despite the DS bundle existing | Contrast and brand sign-off cannot complete; a theme PR could bake in a wrong palette | Medium | Tokens are labelled provisional with a provenance file; contrast validated in CI (097); **no raw hex anywhere else**, so a token swap is one file | Non-blocking for build; **blocking for UX-accept** |
| R5 | **The dashboard document has no shape at all** | Task 084–087 model an entirely invented envelope | High | Confine the invention to `dashboardRepository` + `widgetConfig.ts`; every invented field carries `// INVENTED —`; raise as Q15 | Non-blocking with the seam; **high rework risk** |
| R6 | **RBAC verb catalog unratified** — 2 grantable, 6 proposed, 22 candidate | Gating a feature on an ungrantable verb denies everyone | Certain today | C12's rule: **no v1 feature gates on a non-grantable verb**; verbs render by catalog status; `TODO(verb)` markers enumerate the debt | Non-blocking for v1 features; **blocking for Phase 7** |
| R7 | **Prototype-only affordances leaking into the build** — the demo-state selector, per-screen scenario switches, `SERIES_CAP = 20` | A demo control in production; a hardcoded cap that contradicts `DM-D10` | Medium — the prototype is the reference developers will copy from | Lint rule on `DEMO_STATES`; task 036's explicit test; task 078 reads the cap from capabilities; **this row is called out in the developer README** | Non-blocking, easy to catch |
| R8 | **Shared fixture suite diverges between the TS and Java engines** | The two implementations disagree about a gap or a defaults-applied predicate — the exact failure `UI-D15` exists to prevent | Medium | One suite, two runners; JSON number formatting and key ordering **pinned**; CI fails on drift; `contracts/` is single-writer | Non-blocking; **high consequence if it happens** |
| R9 | **Canvas charts fail screen-reader chart heuristics** | An a11y gap that no amount of ECharts configuration closes | Certain | Tabular alternative + text summary + full keyboard model (075). **Documented as a residual, never claimed closed** | Non-blocking; documented |
| R10 | **`type: yaml` secrets reaching a diff or export** | `I6` violation — a credential in clear | Low in v1 (Cache declares no yaml field) | **A gate, not an accepted risk**: `yaml` renders no editor in v1 and does not ship until server-side redaction exists (Q17) | Blocking for Phase 2 only |
| R11 | **MUI X Pro accidentally imported** | Licence exposure and a procurement event | Low | Lint rule + lockfile grep in CI (009, 096) | Non-blocking |
| R12 | **Parallel development collides on the `shared/ui` barrel** | Merge friction across the whole team | Medium | Append-only, alphabetically sorted barrel; types-only PRs first (§8) | Non-blocking |
| R13 | **`REQ-004` remains formally unsatisfied** | A locked requirement with no implementation and no ratified retirement | Certain today | Recorded as a trace gap (§13.1); **not silently closed** | **BLOCKING for a clean requirements trace**, not for code |
| R14 | **The idle-timeout copy promises a draft mechanism that may not ship** | The console tells the operator their work was saved when it was not — a trust failure worse than losing the work silently | Medium | Task 031 ships the copy and the mechanism together **or neither** | Non-blocking; easy to get wrong |
| R15 | **First-admin bootstrap unspecified** — *"the single largest hole"* | A new deployment has no user and nobody can sign in | Certain | Phase 7 gated on Q5; **do not ship an invented installer-secret flow** | **BLOCKING** for a real deployment |
| R16 | **Fonts, air-gap and the DS token file** — the DS stylesheet `@import`s Google Fonts | An air-gapped deployment silently loses its typeface, or worse, hangs on a font request | Medium | Task 002 self-hosts the fonts; task 100 asserts **zero third-party requests** | Non-blocking, but easy to miss |
| R17 | **No visual reference below `lg`** | Pixel fidelity for mobile/tablet layouts cannot be checked against a design | Certain today | Appendices A and B + `reference/` cover **1280 px**. Below `lg`, Phase R specifies layout in prose and CI (`105`–`110`). Q2 remains for whether Figma should extend below 1280 | Non-blocking for build; **blocking for UX-accept below `lg`** |
| R18 | **Phase R layouts are un-designed** | Operators at 320 px get a layout no designer drew; information architecture may be wrong even if reflow tests pass | High | Honest residual: tests prove SC 1.4.10, not that the IA is good. Architect-group sign-off on `FE-D29` (Q7) before treating mobile as product-complete | Non-blocking for build; **blocking for calling the console "mobile-ready"** |

---

## 16. Open questions and TBDs

**Nothing here is hidden as an assumption.** Each row names who decides.

| # | Question | Why it matters | Affected tasks | Proceed without an answer? | Required decision |
|---|---|---|---|---|---|
| **Q1** | **Are the Solace Agent Mesh tokens recorded in §B.1 the authoritative visual reference, closing open item 19?** | They are the artefact whose absence blocks the frontend sub-spec. They also settle C1 in `D7`'s favour (MUI structure, Solace paint) | 002, 003, 097, and UX-accept for **every** screen | ✅ **Yes, for building** — tokens are labelled provisional and confined to one file. ❌ **No, for UX-accept** | **Architect group** — accept §B.1 as the reference, or supply Figma |
| **Q2** | **Should Figma (or equivalent) cover widths below 1280 px?** | `reference/` is a 1280 px check. Appendices A and B carry behaviour and tokens. **They cannot carry pixel fidelity below `lg`** (R18) | UX-accept below `lg`; 097, 105–110 | ✅ Yes for building; ❌ for UX-accept at 320–905 px | **Design + repo owner** |
| **Q3** | **What is the CSRF mechanism for cookie-authenticated mutations?** | Every mutation needs it; design §26 item 9 | 026, 030, and every mutation | ✅ Yes — the header hook exists and is configurable | **Backend sub-spec** |
| **Q4** | **Ratify the RBAC verb catalog** — `DM-D18`'s six verbs against `D10`, plus the ~22 candidate verbs the prototype names | Today only `target.use` and `instance.bindTarget` are grantable. Gating a v1 feature on an ungrantable verb denies everyone | 032, 081, 088, **090–094** | ✅ For v1 features (C12's rule). ❌ For **Phase 7** | **Architect group** — amend `D10`'s catalog |
| **Q5** | **How does the first administrator come to exist?** | A new deployment has no user and nobody can sign in. The reference screens: *"specified nowhere… the load-bearing hole."* Three credential options carry different dependencies — an emailed invitation needs an **email capability this console does not have** | 094, and any real deployment | ❌ **No** | **Product + backend** — pick the mechanism and the credential path |
| **Q6** | **Idle timeout: confirm the 30-minute duration.** *(The draft-preservation half is **resolved** — see §16.1 `FE-D25`.)* | The duration is an operational preference; the mechanism was the part that blocked implementation, and it is now specified | 005, 031, 037 | ✅ Yes — 30 minutes is the default and comes from `config.json`, so a change is a deploy, not a rebuild | **Operations** — duration only |
| **Q7** | **Ratify the responsive commitment** *(**resolved in-plan** — §16.1 `FE-D29`, which supersedes `FE-D26`)* | Two design documents disagreed, and the first resolution was wrong: a ≥1280px floor cannot satisfy the WCAG 2.2 AA reflow criterion the plan already commits to | Every screen's layout | ✅ Yes — `FE-D29` decides responsive to 320 px | **Architect group** — ratify, and confirm the supersession of `dynamic-metrics` §18's desktop-only non-goal |
| **Q8** | **Name the save-side configuration-of-record generation field** | `FE-D18` **clause A is unblocked** — this is a cheap naming question, deliberately separate from the blocked apply protocol | 055, 056 | ✅ Yes — one field name, trivially renamed | **Backend sub-spec** — cheapest open item on this list |
| **Q9** | **What is the error body shape?** — the `422` body, the `409` conflict body (with no actor and no timestamp), and `partial.unavailable[]`'s item shape and reason vocabulary | The UI switches on `code`; without a body shape it cannot render a stated reason or say who conflicted | 028, 056, 082 | ✅ Yes, behind fixtures | **Backend sub-spec** |
| **Q10** | **Do the Home aggregate, the attention feed, package upload/signature, the authoring review counts, and declare-instance exist as endpoints?** | Five surfaces the prototype draws with nothing behind them. The review counts have **no schema field at all** | 038, 039, 040, 045 | ⚠️ Fixtures only — **and do not invent a declare-instance form** | **Product + backend** — or drop the surfaces from v1 |
| **Q11** | **May a 403 name the missing permission?** *(**resolved conservatively in-plan** — §16.1 `FE-D27`)* | An information-leak question the reference screens raise explicitly (§A.12.7) | 045 | ✅ Yes — `FE-D27` ships the non-naming version; permitting the name later is a copy change in one component | **Security review** — may relax `FE-D27`, cannot be blocked by it |
| **Q12** | **Define the per-replica rollout row schema** | `renderedConfigVersion` and `restartObserved` are **invented column names**; the contract names them in prose only | 060, 062 | ✅ Yes, behind fixtures, with the invention flagged in the UI | **Backend sub-spec** (with open item 1) |
| **Q13** | **Define the console↔backend log seam** | `UI-D10` describes the **MI's** actuator endpoints; the console's route, transport (SSE? chunked? WebSocket?) and audit record are unspecified | 066 | ✅ Yes, behind fixtures | **Backend sub-spec** |
| **Q14** | **Add `firstSeen` and a suggested-type field to the metric catalog entry?** | `SM-D30` forbids inferring a type, and a *"suggest counter from observed monotonicity"* capability is described in prose with nothing to carry it — so the typing dialog can offer no suggestion | 069, 079 | ✅ Yes — offer no suggestion and state the limitation | **Dynamic Metrics owner** |
| **Q15** | **Define the dashboard document** — envelope, hidden-default representation, dashboard-level `range`/`refresh`, whether `{x,y,w,h,order}` live on the widget or a placement entry, a **list route**, and the **reset payload** | The largest single invention in this plan; six structural unknowns | 084–087 | ⚠️ Fixtures only — **high rework risk** | **Dynamic Metrics owner + backend** |
| **Q16** | **Ratify the browser-support matrix** *(**recorded in-plan** — §16.1 `FE-D28`)* | `dynamic-metrics` §18 flags it as an assumption; none exists in the repo, and CI needs a target list to be meaningful | 001, 099, 100 | ✅ Yes — `FE-D28` records evergreen Chrome · Edge · Firefox · Safari, latest two | **Product** — confirm or narrow |
| **Q17** | **Server-side redaction of `yaml`-embedded secrets in diff / export / history payloads** | Gates `type: yaml` entirely (`FE-D23`, `I6` vs `UI-D23`). Cache declares no yaml field, so v1 loses nothing | 047 (Phase 2) | ✅ Yes for v1 — `yaml` ships **no editor** | **Architect group** (with the `UI-D*` set) |
| **Q18** | **Ratify `SM-D1`…`SM-D31` as a set**, with `architecture.md` §13's capability limits explicitly accepted | The whole plan implements `PROPOSED` decisions (R3) | All | ⚠️ The Gate at the top of this document governs | **Architect group** — open item 8, after items 1–4 |
| **Q19** ✅ | **Does the Designer read return the package + values, or a pre-projected view model?** | A **materially different client**: the first needs `project()` in the browser, the second does not. `UI-D15` requires the engine to exist in both languages either way, but the data flow differs | 046, 055 | **RESOLVED (frontend) by `FE-122` / [`FE-D33`](../../design/frontend/decisions.md): the package + values, and the browser projects.** A pre-projected snapshot cannot re-evaluate a `when:` predicate as the operator edits, and every Phase 4 component already takes contract types. The wire **encoding** is still open | **Backend sub-spec** (encoding only) |
| **Q20** | **Who owns the frontend module?** | `new-module.md` requires an owner; design §26 item 12 | All | ✅ Yes | **Engineering management** |


### 16.1 Resolved in this revision — five frontend-owned decisions (`FE-D25`–`FE-D29`)

These questions were **owned by the frontend**. They are **recorded as `PROPOSED` in [`../../design/frontend/decisions.md`](../../design/frontend/decisions.md)** (`I3`). They are **not ratified** (`I4`). `FE-D26` is kept and marked superseded by `FE-D29` — not deleted.

| ID | Decision | Rationale | Supersedes |
|---|---|---|---|
| **`FE-D25`** | **Designer drafts survive an idle lock in per-tab `sessionStorage`, keyed by instance, excluding any secret intent, restored behind an explicit banner with a discard action.** | The reference login copy promises the operator's edits were preserved. A promise the console cannot keep is worse than no promise, and the alternative — deleting the copy — throws away a real protection for a long form. `sessionStorage` (not `localStorage`) because the draft should not outlive the tab; secrets excluded because `I6`/`G7` forbid a credential in browser storage; a banner rather than a silent restore because the operator must know the form is not the server's state. | nothing — new |
| **`FE-D26`** ⚠️ **SUPERSEDED by `FE-D29`** | **The console is desktop-only at ≥ 1280 px.** No phone or tablet layout, an explicit non-goal rather than a deferral. | `dynamic-metrics` §18 states it as a non-goal with a reason (*"an operations console read at 375 px would need a different information architecture, not a responsive stylesheet"*); `design.md` §8.1's *"not desktop-only"* is a passing clause with no supporting requirement. The reference screens assume 1280 px throughout. This is a **narrowing**, recorded as such, not a silent resolution of the conflict (C3). | narrows `design.md` §8.1's responsive clause. **Superseded by `FE-D29`** — the rationale above overlooked that WCAG 2.2 AA SC 1.4.10 Reflow makes 320 px a *requirement*, not a preference. Recorded, not deleted (`I3`). |
| **`FE-D27`** | **A 403 never names the missing permission.** It states the action is not permitted, shows the server correlation id, and offers a route back. | Naming the verb tells an unauthorised caller exactly which permission to acquire, and the operator's recovery path is identical either way: ask an administrator, quote the correlation id. Ship the conservative version; if a security review later permits naming it, that is a copy change in one component — the reverse direction would be a disclosure already made. | nothing — new |
| **`FE-D28`** | **Supported browsers are evergreen Chrome, Edge, Firefox and Safari, latest two versions**, verified in CI. | `dynamic-metrics` §18 flags the absence of a matrix as an assumption. A CI target list is needed for the e2e and CSP jobs to mean anything, and this is the standard set for an internal operations console. Recorded so it is falsifiable rather than assumed. | nothing — new |
| **`FE-D29`** | **The console is responsive across mobile, tablet and desktop, reflowing to 320 CSS px.** Breakpoints `xs 320 · sm 600 · md 905 · lg 1240 · xl 1560`. Data tables and persistent toolbars use the criterion's own exceptions and scroll on one axis **inside their own container**, never the page body. | **This corrects a defect, not a preference.** The plan commits to WCAG 2.2 **AA** (`G17`, conflict C4). SC 1.4.10 Reflow is a **Level AA** criterion requiring 320 px reflow without two-dimensional scrolling (verified at `w3.org/WAI/WCAG22/Understanding/reflow.html`, per `I2`). `FE-D26`'s ≥1280px floor made the stated a11y target unreachable — `G16` and `G17` could not both hold. `dynamic-metrics` §18's non-goal is superseded on a ground it never addressed. | **supersedes `FE-D26`**; supersedes `dynamic-metrics` §18's desktop-only non-goal (cross-component — needs architect-group sign-off) |

**What this does not do.** It does not touch a question owned by the architect group (Q1, Q4, Q17, Q18), the backend sub-spec (Q3, Q8, Q9, Q12, Q13; **Q19's data flow is now decided — its encoding is not**), product (Q5, Q10), or Dynamic Metrics (Q14, Q15). Those remain exactly as they were — **the frontend cannot decide them, and this plan does not pretend to.**


---

## 17. Definition of Ready / Definition of Done

### 17.1 Definition of Ready — the four classes

A task may not be scheduled unless it is `READY` or `FIXTURE`.

| Class | Meaning | Count | May be scheduled? |
|---|---|---|---|
| **`READY`** | Everything needed is in the repo or in this document. No unresolved decision changes the implementation | **74** | ✅ Yes |
| **`FIXTURE`** | The implementation is knowable; only the wire shape is unknown. Ships behind a §6.0.5 repository interface with a flagged fixture | **45** | ✅ Yes — **and the seam is mandatory, not optional** |
| **`DECISION`** | An unresolved decision **materially changes** what gets built | **1** | ❌ Not until the question in §16 is answered |
| **`BLOCKED`** | Depends on a `DECISIONS.md` open item that is a ratification blocker | **5** | ❌ Not for the wire call. The UX half may ship as `FIXTURE` |

**Census (re-run 2026-08-28):** 125 tasks (`FE-001`…`FE-125`). Schedulable = `READY` + `FIXTURE` = **119 (95%)**. **85 are done** — phases 0–5; see [`execution.md` §7](execution.md#7-task-register--all-125) for per-task status and [`../../design/frontend/design.md` §28](../../design/frontend/design.md#28-as-built-map-2026-09-03) for what they produced.

⚠️ **"Materially changes" is the test, and it is applied strictly.** A question whose answer swaps values in one file (Q1's tokens) or changes a fixture's contents (Q4's catalog) does **not** make a task un-ready — that is exactly what the token layer and the data-driven catalog were built for. A question that changes the *shape* of what is built (Q5's bootstrap flow) does. The one remaining `DECISION` is task 094.

**Per-task readiness — a task is Ready when all of these hold:**
- [ ] Its requirement and decision references resolve to documents that exist
- [ ] Every input it consumes is either specified (with a citation) or has a **named fixture with a flagged invention**
- [ ] Its dependencies are merged, or their **types-only PR** is merged (§8 rule 1)
- [ ] Its UI reference in Appendix A has been read — not skimmed
- [ ] Every interface it consumes exists in §6.0, or its own types-only PR is merged first
- [ ] Its verification command from §6.0.8 is known and runnable
- [ ] No unresolved §16 question changes its implementation
- [ ] Its acceptance criteria are checkable by a test, not by inspection

### 17.2 Global Definition of Done — applies to every task

From [`../../checklists/definition-of-done.md`](../../checklists/definition-of-done.md), specialised for the frontend. **Evidence before assertions.**

- [ ] **Implementation complete** and matching Appendix A in structure and copy intent — deviations recorded in the PR with a reason
- [ ] **Existing architecture respected** — feature/shared boundary, state lifetime, seam, engine purity. If a task needed to cross a line, the design is wrong and gets raised, not worked around
- [ ] **Tests added and passing — output seen, not inferred.** Unit + component + a11y at minimum; integration or e2e where the task's acceptance names one
- [ ] **Error states handled** — a distinct, tested error path; **the error state is not the empty state**
- [ ] **Loading and empty states handled** — skeleton at content dimensions; empty states say **why** and what to do
- [ ] **Accessibility addressed** — keyboard path, focus, labels, `aria`, axe clean **in both themes**
- [ ] **Theme addressed** — light and dark verified; **zero raw hex outside `shared/theme`**
- [ ] **Security addressed** — no secret in state, storage, log, diff or export; no client-side authorization assumed to be a boundary; no `dangerouslySetInnerHTML`
- [ ] **No regression** — the shared fixture suite passes; guardrail lint fixtures still fail as designed; the bundle budget holds
- [ ] **Lint, typecheck and build clean** — no `any`, no suppressions without a written reason
- [ ] **Documentation updated where necessary** — the seam's README when a repository changes; `PROVENANCE.md` when a token changes
- [ ] **Every invented shape is flagged in source** with `// INVENTED —` or `// CONTRACT: TBD —`, so a grep enumerates the debt
- [ ] **Acceptance criteria satisfied** — each checkbox in the task, demonstrated
- [ ] **Would a staff engineer approve this?** — and if a fix felt hacky, the elegant version was at least considered
- [ ] **Anything decided while executing landed in `docs/design/DECISIONS.md`** as a **new** `FE-D*` that supersedes — never an in-place edit (`I3`) — before the issue closes

### 17.3 Phase-gate Definition of Done

A phase is done when every task in it is done **and** its gate in §7 passes: Gate A (Phase 0 CI green), Gate B (a component is buildable against a repository interface with no knowledge of its backing), Gate C (every Phase 3 screen renders five states), Gate D (**e2e journey 1 — the walking skeleton — green**).

---

## 18. Production readiness assessment

Scored against the brief's gate. **A ✅ means the plan makes it testable; it does not mean the code exists.**

| Area | Check | Verdict | Evidence / gap |
|---|---|---|---|
| **Architecture** | Consistent with existing architecture | ✅ | §4 restates `design.md` §4/§7; no new architecture is introduced |
| | No unnecessary abstractions | ✅ | Three seams only — clients, repositories, pure engines. Each earns its place: SaaS split, absent contracts, cross-language divergence |
| | Clear module boundaries | ✅ | CI-enforced (task 009), not review-enforced |
| | Clear API/data seams | ⚠️ | **12 repository interfaces are now declared in §6.0.5** with per-seam `CONTRACT: TBD` markers, so the frontend side is pinned and rework is provably one adapter per seam. **7 of 9 wire contracts behind them are still unwritten** (Q9, Q10, Q12, Q13, Q15, Q19) |
| **Requirements** | All requirements mapped | ⚠️ | 8 of 9 mapped with tasks and tests. **`REQ-004` is deliberately unimplemented** and is an open trace gap (§13.1, R13) |
| | No unexplained gaps | ✅ | Every gap is named in §13.1 or §16 |
| | No conflicting assumptions | ✅ | 13 conflicts registered in §2.3 with an impact, a recommendation and a status; none silently resolved |
| **UI** | All important screens covered | ✅ | 12 screens + the instance context, each mapped to tasks (§3) and fully specified in Appendix A |
| | All important states covered | ✅ | §10's matrix: 16 screen-rows × 10 state-columns, each an acceptance criterion, each drawn in the reference (§A.14) rather than inferred |
| | Forms and validation covered | ✅ | §6 tasks 046–057; two tiers; **no business rule hard-coded** |
| | Navigation covered | ✅ | Route table, guards, URL state, deep links, 403/404 |
| **Security** | Auth/authz boundaries understood | ✅ | §12.1; **"hiding a control is not a security boundary" is stated three times and lives in the source** |
| | Sensitive data handled safely | ✅ | `FE-D23` implemented structurally — `DiffView` is **type-incapable** of rendering a secret |
| | No secrets exposed | ✅ | Six production-bundle assertions (task 101) + a logger lint rule (102) |
| | CSRF | ❌ | **Mechanism unresolved** (Q3). The hook exists; the mechanism does not |
| **Accessibility** | Keyboard · screen readers · focus · contrast · forms · tables/charts · themes | ⚠️ | All specified per task, with WCAG 2.2 AA (C4) and CI contrast validation in **both** themes against the tokens in §B.1. **Residual, honestly stated:** canvas charts still fail some SR chart heuristics (R9) |
| **Performance** | Large data · rendering · requests · bundle | ✅ | §12.3 — every optimisation is justified against a stated pressure; none speculative |
| **Reliability** | Errors · timeouts · session · partial failure · long-running | ⚠️ | §12.5 covers 20 failure classes. **Two are unmitigable today**: the concurrent-apply race and apply-timeout resolution, both open item 1 (R1) |
| **Testing** | Unit · component · integration · e2e · a11y · regression | ✅ | §11 — and each e2e journey asserts a `must not`, not only a happy path |
| **Maintainability** | Ownership · reuse · duplication · interfaces | ⚠️ | Interfaces are declared, not described (§6.0); reuse is enforced by lint. **Module owner unassigned** (Q20) |
| **Release** | Build · configuration · environment · deployment · rollback | ⚠️ | Static SPA, `config.json` injection, CSP, air-gap and bundle assertions are all planned (096–101). **SM packaging Dockerfiles do not exist**, and SM self-upgrade is open item 20 — both outside this component |

### 18.1 Blocking gaps — what stops this being production-ready

1. **`dp-remote` apply protocol** (open item 1). The console's central action cannot be made correct. Tasks 043, 061, 062, 095.
2. **First-admin bootstrap** (Q5). A real deployment cannot be signed into.
3. **The dashboard document has no shape** (Q15). Six structural unknowns; the largest invention in the plan.
4. **CSRF mechanism** (Q3). Every mutation depends on it.
5. **RBAC verb catalog unratified** (Q4). Phase 7 cannot **ship** — though its implementation is now unblocked, since the catalog is data (§6.0.6). Six v1 features run ungated in the meantime.
6. **Ratification of the `SM-D*` set** (Q18 / open item 8). The plan's own gate.
7. **`REQ-004` formally unsatisfied** (R13). The requirements trace does not close.
8. **No visual reference below `lg`** (Q2, R17, R18). `reference/` checks 1280 px. Phase R specifies narrower layouts in prose and CI, not against a design.

### 18.2 What is genuinely ready

**119 of 125 tasks (95%) are schedulable** — 74 `READY` and 45 `FIXTURE`. That is a readiness count, not an authorisation: the Gate at the top still forbids starting while `FE-D*` / `UI-D*` / `DM-D*` / `SM-D*` are `PROPOSED`, and **85 tasks were nevertheless executed** under the amendment route `execution.md` §0 records. The six that are not schedulable: **094** (Q5) and the **wire calls** of **043 · 061 · 062 · 095 · 124** (open item 1). Phase R (`105`–`110`) is included in the 119.

---

## 19. Final quality score

# **90 / 100**

**Band: 90–94 — very strong; remaining deductions are upstream or cross-component, not missing frontend craft.**

The score measures **implementation readiness of this plan plus its execution companion**, not document length. It is not a 95: open item 1, unpublished Console REST, and unratified `FE-D*` still bind.

### 19.1 What changed since the first revision, and why the number moved

The first revision scored **87**. A later revision reached a claimed **91** while the document still said "104 tasks" after Phase R added `105`–`110`, and while [`execution.md`](execution.md) still handed reviewers the superseded `FE-D26` viewport rule. Those were **plan defects**, not upstream gaps. This revision closes them:

| Deficiency found | Fix | Effect |
|---|---|---|
| **No interface contracts.** §8 told parallel workers to "publish the interface before the implementation" and then never published one | **§6.0** — engine types, `ApplyOutcome`, 12 repository interfaces, permission model, kernel props | Parallel work is safe on signatures |
| **No verification commands** | **§6.0.8** including a reflow class for `105`/`110` | A task can be proven done |
| **G1–G20 missing from page one** | Global constraints at the top | Reviewers inherit the same `must not`s |
| **Frontend-owned questions left open** | **§16.1** `FE-D25`–`FE-D29`, recorded in `decisions.md` | Idle-draft, 403 copy, browser matrix, viewport decided in-family |
| **Census and G16 drifted** (104 vs 110; execution still desktop-only) | Every tally, graph, register and reviewer lens now uses **110 tasks** and **`FE-D29` / 320 px** | Execution cannot review against a superseded rule |
| **`Deps` missing from `011`–`035` briefs** | A `Deps` line on every task | Extracted briefs carry prerequisites |
| **`FE-D25`–`FE-D29` lived only in this plan** | Recorded as `PROPOSED` in [`../../design/frontend/decisions.md`](../../design/frontend/decisions.md) | Architecture is not invented solely in a plan file |

**Readiness:** **105 of 110 tasks schedulable (95%)** once the Gate lifts; `DECISION` = 1 (`094`).

### 19.2 The arithmetic

| Deduction | Now | Why |
|---|---|---|
| **`dp-remote` apply protocol does not exist** (open item 1) | **−4** | Frontend half is typed (`ApplyOutcome` has no success variant). Gate D is **fixture e2e**, not production apply. Concurrent-apply race still unmitigable |
| **Console REST unwritten for 7 of 9 seams** | **−2** | Seam confines rework to adapters; the OpenAPI still does not exist |
| **Dashboard document has no shape** | **−1** | Frontend types pin blast radius; six structural unknowns remain (Q15) |
| **Every decision implemented is `PROPOSED`**, including `FE-D29` vs `dynamic-metrics` §18 (Q7) | **−2** | Ratification is open item 8; viewport supersession still needs the architect group |
| **First-admin bootstrap and CSRF unspecified** | **−1** | Q5 / Q3 — not frontend-owned |
| | **−10 → 90** | |

A previous self-score of 91 omitted the −1 for `FE-D29` still needing cross-component sign-off and treated a drifted 104-task census as closed. This 90 is the consistent number.

### 19.3 What still earns the score

125 tasks, each with refs, deps, implementation, tests, a verification command and checkable acceptance · 13 conflicts registered · a 16 × 10 state matrix · `must not` rules as assertions and type errors · Appendices A and B plus `reference/` as a 1280 px check · 20 open questions with owners · invented shapes greppable · execution plan aligned on G16, Phase R, and the 110-task register.

### 19.4 Why it is not higher, and exactly what each remaining point costs

**Nothing in this document can reach 95.** Remaining points are held outside this component:

| Close this | Owner | New score |
|---|---|---|
| **Q7** — architect group ratifies `FE-D29` (and the supersession of `dynamic-metrics` §18) | Architect group | **92** |
| **Q8** + **Q3** — save-generation field name; CSRF | Backend sub-spec | **94** |
| **Q1** — accept §B.1 tokens, closing open item 19 | Architect group | **95** |
| **Console OpenAPI** for §9.2 | Backend sub-spec | **97** |
| **Q15** + **Q5** | Dynamic Metrics · Product | **98** |
| **Open item 1** | Architect group | **99** |

The last point is reserved: `REQ-004` remains a **LOCKED** requirement with no implementation and no ratified retirement (R13).

**Honest summary:** the frontend plan and execution companion are internally consistent and executable the moment the Gate lifts. What is left is ratification, contracts, and the apply protocol — named, owned, not hidden.

---

## Review

*What changed versus the plan, and why. Per [`../../checklists/definition-of-done.md`](../../checklists/definition-of-done.md), anything decided during execution lands in [`../../design/frontend/decisions.md`](../../design/frontend/decisions.md) as a new `FE-D*` before the issue closes — per-task detail stays in [`reports/`](reports/).*

### Phases 0–3 · `FE-001`…`FE-120`

Per-task reports: [`reports/`](reports/) (`task-001` … `task-120`). Decisions taken during execution and
promoted to the register: **`FE-D31`**, **`FE-D32`** (package versioning, and the authored version history in
the shipped examples).

**Verification at the close of `FE-118`–`FE-120`.** `lint` · `typecheck` · `test` · `build` all green.
**228 files / 1295 tests**, against a clean-tree baseline of **227 / 1257** measured before the change. No axe
timeout on either run — the pre-existing flake did not appear.

**Where the plan was right, and it mattered.**

- The instances grid's congestion was a `flex`/`width` conflict in `DataTable`, not a column-list problem,
  exactly as diagnosed. `I2`'s instruction to fetch the MUI documentation *before* writing the fix paid off
  twice: the page states the override outright, and it supplied the `minWidth` semantics the fix depends on.
- **"Drive it in a browser" caught what the tests could not** — twice, both below.

**Where the plan was wrong or incomplete.**

1. **The two-line cell clipping was never a row-height problem** (`FE-119`). The prescribed `rowHeight` per
   density did not fix it and at `compact: 44` made it worse — MUI scales `rowHeight` by an undocumented
   density factor and rendered 30px rows. The cause is `line-height`: MUI centres cell text by setting it to
   the row height, so an inline first child consumes the whole row. Fixed at the cause; heights then sized to
   measured content (64 / **52**, not 44).
2. **The Designer browser check could not be performed** (`FE-118`). The Designer screen was still a
   placeholder, so no instance's field set rendered at any version. The behaviour was proven one layer down,
   through the real repository and real dev handlers, in `devPackageFlow.test.ts`. **`FE-123` closes this**,
   and its acceptance carries the browser check the plan wanted here.
3. **19 contract fixtures, not 20.**
4. **A third version fixture was needed.** `project()` has no unit-test file — the fixture suite is its only
   test — so the before-`applyDefaults` ordering would have been asserted in a comment and nowhere else. Added
   `version-scoped-field-contributes-no-default.json`.
5. **`graphs[]` also needs the component filter**, which the plan did not say. A component the Designer
   refuses to render must not chart metrics on Monitoring.

**Found, not fixed.**

- Loading a package whose `solution` id matches a baked catalog card yields **two cards with the same id** —
  `allSolutions()` concatenates without de-duplicating — and React logs a duplicate-key warning. Pre-existing
  in the `FE-114` dev loader; `FE-120`'s derived counts make it more visible (two cards, same number) but did
  not cause it.
- **§7's register has no rows for `FE-113`–`FE-120`.** They are present here and in
  [`task-list.md`](task-list.md) but were never added to [`execution.md`](execution.md) §7. Noted while adding
  `FE-121`–`FE-125`; not backfilled.

### Phase 4 · `FE-121`…`FE-125`

*(To be filled in by the executing session. Decisions already registered ahead of execution: **`FE-D33`**
Q19 resolved · **`FE-D34`** `shared → contracts` · **`FE-D35`** no invented `one-of-many` · **`FE-D36`** no
Cancel · **`FE-D37`** version threading.)*

### Phase 8 · `FE-096`…`FE-104`, `FE-110` — hardening

Per-task reports: [`reports/`](reports/) (`task-096` … `task-104`, `task-110`). Decisions taken during
execution and promoted to the register: **`FE-D39`** (palette/contrast gate) · **`FE-D40`** (DEV-only e2e
session overrides) · **`FE-D41`** (the CSP) · **`FE-D42`** (the logger's primitives-only field type) ·
**`FE-D43`** (MSW stripped from the build; the gate reads the artefact) · **`FE-D44`** (render budgets are
ratios).

**Verification at the close of `FE-104`.** `lint` · `typecheck` · `build` · `verify:bundle` ·
`verify:palette` · `verify:csp` · `verify:perf` · `verify:production` · `verify:reflow` ·
`check:forbidden-deps` · `lint:fixtures:assert` all green. **324 files / 2172 tests**, and
`npm run e2e` **168 passed**. The five unit failures are the pre-existing a11y flake recorded below.

**What the hardening phase found that the feature phases had not.**

The three verification tasks each found a real defect, and in every case the defect had been shipping
green for weeks because nothing measured the right subject:

1. **The production build shipped Mock Service Worker** (`FE-101`), since `FE-039`. `publicDir` is copied
   verbatim into `dist/` and MSW's worker never enters the module graph, so no bundle analysis could see it.
   Recorded as `FE-D43`.
2. **The production bundle carried a fixture dataset** (`FE-101`). One **value** import in `DesignerForm.tsx`
   pulled `shared/testdoubles/data/designer.ts` — invented rows, secret-mask diffs, replica observations —
   into a route chunk. Ten sibling files import the same module safely with `import type`, which TypeScript
   erases. The restart-strategy catalog moved to `features/designer/restartStrategies.ts`.
3. **Six measured AA contrast failures in the shipped palette** (`FE-097`) — a 1.58:1 chart series colour, a
   2.17:1 focus ring. Recorded as `FE-D39`.
4. **A broken skip link on every authenticated screen** and three keyboard-inaccessible scroll regions
   (`FE-110`), invisible to `jest-axe` because RTL never renders `index.html`.

**Where the plan was right, and it mattered.**

- **`FE-101`'s "each demonstrably fails when violated"** is the acceptance line that produced the most value.
  Building the self-test first — six described violating bundles plus a real production build of a fixture
  entry that imports the mock, the gallery, MSW and a fixture dataset on purpose — is what made the two
  defects above findable rather than theoretical.
- **`FE-103`'s "tests, not assertions"** correctly implied that most of the performance contract was already
  covered. Four of its six guarantees were, so the task added the two that were missing and *named* the
  other four rather than duplicating them.

**Where the plan was wrong or incomplete.**

1. **`FE-101` says "six assertions"; there are seven.** The six *must-nots* plus `config-replaceable`, which
   the Implementation line asks for but the Acceptance line does not count.
2. **`FE-102`'s ESLint rule was specified as one rule and needed three.** The task names a rule forbidding a
   secret-shaped value reaching the logger. That leaves `console.*` reachable everywhere else, and nothing
   preventing a transport being added to the logger — both of which defeat the first rule at a different
   layer. Delivered as `NO_SECRET_LOG_FIELD` + `NO_RAW_CONSOLE` + `NO_LOG_TRANSPORT`.
3. **Three `no-restricted-syntax` override blocks in `eslint.config.js` were silent holes** (`FE-102`). The
   rule is replaced wholesale by a later block of the same name, so `shared/ui/layout`,
   `features/designer/form` and `test/matchMedia` could each have logged a password. Not a plan error — a
   pre-existing property of the config that adding a rule exposed.
4. **`FE-103`'s "render within an agreed budget" cannot be met with an absolute jsdom number.** Recorded as
   `FE-D44`; the first attempt passed in isolation and failed in the full suite at 5× the unloaded time.
5. **`FE-104` cannot fill the Phase 4 Review section above.** `FE-121`–`FE-125` were executed in a different
   session; their per-task reports exist in [`reports/`](reports/) but the summary is that session's to
   write, and inventing it here would be worse than leaving the stub visible.

**Found, not fixed.**

- **`InstancesPage.a11y.test.tsx` (and sometimes `Gallery.a11y.test.tsx`) time out under full-suite
  parallelism.** Both files already document being near their budget. **Reproduced on `main` with all of
  Phase 8's work stashed** — 3 failed / 348 passed — so it is pre-existing, not introduced here. CI runs the
  full suite, which makes `test:a11y` a latent red build on a slow runner. It needs a deliberate
  `testTimeout` on those two files, or the DataGrid axe scan split; both belong to `FE-098`'s test, not to a
  hardening task, so it is recorded rather than silently patched.
- **No browser performance measurement exists** (`FE-103`). Frame timing, layout thrash and interaction
  latency need Playwright against the `fixture` project. No task in this plan commissions them.
- **`FE-096` (#163) is marked built but its issue is still open.** Noted while closing `FE-101`–`FE-104`.

---

# Appendix A — UI specification

> **Why this appendix exists.** This appendix is the behavioural extraction of the reference screens: what each screen is, what it contains, how it behaves, and which behaviours are **specified** versus **invented for the reference**. A task that cites `§A.n` needs nothing else for *behaviour*. Pixel check at 1280 px: [`../../design/frontend/reference/`](../../design/frontend/reference/). Layouts below `lg` are Phase R (`FE-D29`), not this appendix.
>
> **What it deliberately does not carry:** pixel fidelity. See §16 Q2 and risk R17.
>
> **Reading convention.** ⚙️ = a behaviour traceable to a decision or the design. 🅸 = **invented for the reference**, with nothing in the repository behind it; every 🅸 also appears in §3.1 or §16.

## A.1 · Shared component kit

The reference kit is **MUI-structured** — outlined fields with floating labels, tabs with an indicator, contained/outlined/text buttons, paper elevation, a data grid. It is the concrete form of design §9's common-components layer.

| Primitive | Contract |
|---|---|
| **Buttons** | Variants `contained · outlined · text · danger (outlined) · danger (contained)`; sizes `sm (32px) · md (40px) · lg (46px)`; leading and trailing icon slots; full-width modifier. Uppercase label, semibold, slight letter-spacing. Disabled renders at 50% with no shadow. **Icon-only buttons are 40px / 32px and always carry an accessible label.** |
| **Form controls** | Outlined `TextField` with a floating label driven by an empty placeholder; a reveal toggle for passwords; `NumberField`; `Select`; `Switch`; `Checkbox` supporting a **mixed** state; textarea. Each renders label + optional required marker + helper text, and swaps helper text for the error message on error. |
| **Chip / StateChip** | Tones `ok · warn · err · info · idle · neutral · purple`; optional leading dot or icon; `sm` size. **`StateChip` renders a vocabulary entry as icon + text + tone, plus an info tip when the entry carries one** — see §A.13.1. ⚙️ Colour is never the only channel. |
| **Alert** | Severities `info · warn · err · ok · neutral`; title, body, icon override, and a trailing action slot (the retry button). |
| **Tooltip / InfoTip** | Focusable (keyboard-reachable). `InfoTip` is the small circled **i** affordance used ~40 times across the screens to explain a state or flag an invention. |
| **Surfaces** | `Paper` (flat · padded · hoverable); `PageHeader` with title, sub-line and a right-hand action slot; `SectionHead` with a title and a small right-hand caption. |
| **Dialog** | Scrim, centred panel with a max width per use (`560` default, `620`–`720` for pickers and editors), header with a close button, scrollable body, footer with cancel + confirm. **`Escape` closes.** |
| **Menu** | Trigger + popover, right- or left-aligned, fixed width, with caption rows for group labels and a `danger` row style. Closes on outside click and `Escape`. |
| **ToggleGroup / Tabs** | Segmented control for env filters, series-by, mark, resolution, layer and density. Tabs carry an icon, a label and an optional count chip, with an active indicator. |
| **EmptyState** | Circled icon, heading, explanatory body, action slot. **The body always names why it is empty and what to do.** |
| **Skeleton** | Rectangles at the dimensions of the content they replace. |
| **Toast** | Bottom-corner stack, severity icon, title, detail, **the correlation id**, dismiss button, ~6s auto-dismiss. |
| **DataTable** | Toolbar (search · filters · column visibility menu · density toggle · CSV) · sortable headers with a sort indicator · optional checkbox column · optional expander column with a detail row · footer showing `N selected` and `N of M rows`. **Community-tier features only** — no pinning, no row grouping. |

## A.2 · App shell

**Layout.** A two-column grid: a `256px` dark navy drawer and the main column; the main column is `64px` app bar over a scrolling content region. Content is centred at `1240px`, or `1560px` on wide pages.

**Drawer.** Brand block (logo mark, "Solution Manager", "Solace Console"); a `Platform` caption; **`Home`** and **`Solutions`** (with a solution count); beneath `Solutions`, a sub-list of loaded solutions each with an instance count. The active item carries an accent bar and `aria-current="page"`. Footer: avatar, user name, role.
⚙️ **`Targets` and `Admin` are absent from the drawer** (design §14 — do not show them until the routes exist).

**App bar.** Breadcrumbs · spacer · **tenant display block** (an upper-case `TENANT` caption over the value, bordered on both sides, with a title explaining it is a server-returned string and not a switcher) · theme toggle (sun/moon segmented) · support menu · account menu.

**Support menu.** UI version · session correlation id · a copy button · the line *"Quote the correlation id on a support ticket."*

**Account menu.** Name, email, role chip · `Profile` · a caption *"Later increments · not in v1 nav"* · `Targets` and `Admin` marked `preview` · `Sign out` (danger).

⚙️ **No bell and no notification affordance anywhere** — nothing may imply someone is paged.

**Breadcrumb model.** `Home` → `Solutions` → *solution* → `Instances` → *instance* → *category*. The current crumb is not a link; ancestor crumbs are.

**Routing model.** `#/home` · `#/solutions` · `#/solutions/:solutionId/instances` · `#/solutions/:s/instances/:i/{designer|governance|monitoring}` · `…/monitoring/explore` · `#/targets` · `#/admin/:tab`. Selection and focus travel as query parameters. ⚙️ **No tenant segment.**

**Focus.** On every route change the main region is focused and scrolled to the top.

## A.3 · Login

Centred card over a decorative node-mesh background. Logo · "Sign in" · "Solace Solution Manager" · username · password (with reveal) · full-width submit.

**States.** `idle` · `submitting` (label becomes *"Signing in…"*, control disabled) · `rejected` (one error under the password field: *"Username or password is incorrect."*) · `unreachable` (an error alert: *"Can't reach Solution Manager"*).

**Arrival states**, rendered as an alert above the fields:
- **Session expired** (warn, lock icon) — *"Your session ended. The server no longer recognised your session, so you were signed out."*
- **Idle timeout** (info, clock icon) — *"Signed out after 30 minutes idle."* 🅸 The reference copy adds *"Your in-progress configuration edits were saved on this device and will reload after you sign in"* — **a claim that requires a mechanism that does not exist yet** (§16 Q6).

**Footer copy.** *"Accounts are created by your administrator. There's no self-service sign-up or password reset in this release. Sessions end after 30 minutes of inactivity."*

**Layout affordance.** A gap below the divider is reserved for a single *"Sign in with your organisation"* button so SSO drops in without redesign. ⚙️ It is **empty in v1** (`D13` deferred).

## A.4 · Home

**Purpose.** *"What needs your attention right now — not an aggregate dashboard. You're here to find the one broken thing."*

**Stat strip.** Four clickable tiles: `Solutions managed` · `Instances declared` · `Need attention now` (error tone) · `Config staged · not applied` (warn tone). Each links into a filtered view. 🅸 No aggregate endpoint exists.

**Attention feed.** A panel headed `Needs attention` with a `N items · newest first` caption. Each row: a tinted severity icon, a bold title, a detail line, a chevron; the whole row is clickable and keyboard-activatable and deep-links with `?focus=<instanceId>`. 🅸 The feed, its severity vocabulary, its ordering and its dedupe rules have nothing behind them.

Representative items (these are the fixture set, §A.13): *replica not responding* · *host unreachable* · *configuration diverged* · *config staged, not applied* · *version drift* · *version unknown* · *N metric series arriving unclassified*.

**Empty feed.** A centred success line *"Nothing needs attention."* with *"All declared instances are healthy, applied and on a known version."* — ⚙️ never a blank panel.

**Partial state.** A warn banner naming the host that has not reported, and counts rendered as e.g. `11 +1?`. ⚙️ **An unknown value renders `—`, never `0`.**

**First run.** A centred card: package icon · *"Load a Solution Package to begin"* · *"Every screen here renders from a Solution Package — published by Solace, loaded by you. Until one is loaded, there's nothing to configure, govern or monitor."* · three tiles (`Solutions`, `Instances`, `Targets`) each showing `—` · a `Load a package` action **for super-admins only**; everyone else sees *"Loading a package is a Solace super-admin action. Ask your administrator to publish and load one."*

**Footer line.** ⚙️ *"Solution Manager surfaces these here. It does not notify or page anyone — no alerting is configured in this release."*

## A.5 · Solutions catalog

**Purpose.** *"The solution types this deployment can manage. Each renders entirely from its Solution Package — the console holds no per-solution code."*

**Card anatomy.** Coloured solution icon · name · entitlement chip (`Entitled` / `Not entitled`) · kind sub-line · **provenance chip**: `Signed · Solace` with the signature fingerprint in an info tip, **or** `Unsigned · dev-mode` in the error tone. An unsigned card additionally carries a full-width top bar: *"Unsigned · development mode — not trusted like a signed package"*, and its `Open` action is **disabled** with the reason.

**Body.** A definition list — `Package version` · `Applies to` (a semver range, with an info tip: *"Semver range of MI versions this package serves — the package's own revision, not the MI's"*) · `Source`. Then the **review meter**, then component pills.

**Review meter.** 🅸 `reviewedRows / totalRows` with three renderings: `100%` → `Fully reviewed` (ok); `0%` → `Not reviewed` (err) plus *"assembled by automated extraction, unreviewed"*; between → `N% reviewed` (warn) plus *"the remainder auto-extracted — a normal state"*. A two-segment bar. **No package schema field carries these counts.**

**Footer.** Instance count · telemetry mode · `Open`.

**Load package dialog.** 🅸 Super-admin only. A drop zone (click or drag, accepting a signed package file) → on selection, a verified state showing the filename and *"Signature verified · Solace-signed"* with a *"Choose another"* link → `Load package`. An info alert: *"Solace-signed packages load normally. An unsigned (dev-mode) package is admitted only when the console runs in development mode, and is marked conspicuously wherever it appears."* Success emits a toast: *"Package loaded — signature verified. The catalog now renders from it."*

**States.** loading (card skeletons) · error (*"Couldn't load the catalog"* + retry) · empty (*"No solution packages loaded"* + the load action, or the ask-your-administrator alert).

## A.6 · Instance inventory

**Purpose.** *"Instances of this solution. An instance is declared here; the artifact is deployed by your own CI/CD."* ⚙️ This sentence is the on-screen expression of `SM-D2` / conflict C9.

### A.6.1 Columns

| Column | Content |
|---|---|
| **Instance** | Name as a link to Designer, over an `org / sub-org` sub-line |
| **Env** | `DEV` (neutral) · `TEST` (info) · `PROD` (purple) chip |
| **Where it runs** | Driver icon + label (`Docker` · `Kubernetes` · `systemd · VM`), with a header info tip: ⚙️ *"driver is data on the row — not a separate view. Same table for Docker, Kubernetes and systemd."* |
| **How it's reached** | `control_locus` chip + info tip, with a header tip: ⚙️ *"control_locus is data on the row — same table, different value. There is no agent view or Kubernetes view."* |
| **Health** | The rollup `StateChip` **over a per-replica summary line** (`2 healthy · 1 standby`). A not-found rollup renders the **specific** not-found state over its **alert scope** (`Host / target alert` · `Configuration finding` · `Instance alert` · `Bootstrap`) |
| **Version** | State chip + an info tip stating the editability consequence, over `obs <x> · decl <y>` in mono |
| **Config** | State chip; for `Applied` **also** an `effective-unverified` chip with the reason; otherwise an info tip |
| **Replicas** | Count, mono, centred |
| **Last seen** | Relative time, or `—` with *"Never observed — awaiting its first restart"* |

### A.6.2 Expanded row
A grid of replica cards (replica name + health chip + the state's explanatory note), then two blocks: `VERSION` with the version state's note and `CONFIGURATION` with the config state's note.

### A.6.3 Toolbar
Search · env segmented filter (`All · DEV · TEST · PROD`) · column-visibility menu · density toggle · CSV · and, when a selection exists, a `Restart N…` action.

### A.6.4 URL state
⚙️ Selection is serialised as `?sel=id,id` and the deep-link target as `?focus=id`. A focused row arrives expanded and highlighted. Filters and sort are component state; selection and focus are URL state.

### A.6.5 Bulk restart dialog
🅸 Title *"Restart selected instances"*. A warn alert: *"Applying config requires a restart — writing configuration is not the same as applying it. These instances will restart to pick up staged config."* Then one line per instance: name · replica count · *"expect ~10–20s unavailability per replica"*. Closing caption: ⚙️ *"Restart is operator-initiated. The console confirms only that it wrote config and observed a restart — never that the process is using it."*

### A.6.6 States
loading (skeleton rows) · error (*"Couldn't load instances… This is an error — distinct from 'no instances' and from 'all healthy'."*) · empty (*"No instances declared yet"* + the declare→apply→restart→observable explainer: *"it stays unobservable until after that first restart, so an early 'missing' is expected, not a fault"*) · partial (a warn banner; *"Unknown values render as '—' or a named not-found state — never a healthy default"*).

## A.7 · Instance context

A header shared by Designer, Governance and Monitoring, plus the category tabs.

**Header row.** Instance name (large) · env chip · health rollup chip (with an info tip when health is not declared) · **a `Version drift · Designer read-only` chip when drift blocks editing** · spacer · **the persistent outcome badge**.

**When drift blocks Designer**, a caption appears: ⚙️ *"Two of the three categories still work — Governance and Monitoring are available while the version state blocks configuration edits."*

**Outcome badge.** The last mutation outcome for this instance — severity icon, title, and the correlation id — pinned to the header and surviving navigation within the instance. ⚙️ *A toast is acknowledgement; the badge is the record.*

**Tabs.** `Designer` · `Governance` · `Monitoring`, each with an icon, as route links.

**Toasts.** Fired alongside every outcome, in a polite live region, each carrying its correlation id.

## A.8 · Designer

⚙️ **One component renders every solution's config editor from the view model.** Single-component solutions skip the canvas; multi-component ones get the composer.

**Layout.** A top bar (solution · Designer, instance id, package version chip, version pill) over a three-part body: a left section-nav rail, a scrolling form column, and a sticky action bar at the bottom of the form column.

### A.8.1 View-model shapes
A component carries `instanceKey · type · role · sections[] · fields[] · actions[]`. Sections arrive **pre-ordered** and may be flagged `allAdvanced`. A field carries `uid · key · renderedPath · controlKind · value{kind} · options · section · visible · enabled · virtual · advanced · masked · discriminator · label · helperText · validations[] · hiddenBy[] · when`. A value is one of `value(v, source) · unset · secretRef(ref) · secretUnset`.

### A.8.2 Applicability
A field is visible when `visible !== false` and its `when` predicate holds against the component's **defaults-applied** value map. ⚙️ Predicates carry the **full ancestor chain**, not just the parent. Changing a discriminator re-evaluates all descendants; values in hidden branches are retained but never rendered into config.

### A.8.3 Control kinds
`text · number · select · switch · duration · secret · string-list · key-value · table · yaml`.
- `duration` shows the hint *"Spring Duration · 120m · PT2H"*.
- `string-list` and `key-value` are repeating rows with per-row remove and an `Add item` / `Add mapping` action.
- `table` renders *"Tabular editor — columns declared by the package."*
- ⚙️ **`yaml` is a gated placeholder, not an editor**: a lock icon with *"Raw YAML editing is gated — this field is edited through the structured form. The raw editor is disabled in this release."*

### A.8.4 Field row
Label · required asterisk · **discriminator hint** (*"controls fields below"*, with the tip *"Choosing this changes which fields below apply. Values in a hidden branch are kept — switch back and they return."*) · spacer · rendered path in mono (single-component solutions only) · `Default` hint · `Modified` chip · the control · helper text · validation messages.
⚙️ **A `virtual` field (no rendered path) renders identically to a real one — no badge, no visual tell.**
⚙️ A warning message appends *"a Solace recommendation, not a hard limit — you can proceed."*

### A.8.5 Secret control
Two states. **Not set:** key icon + *"No credential set"* + a `Set` action. **Set:** a green dot + *"Credential set"* over the reference string (e.g. `secretRef://kv/acme/…`), with `Replace` and `Clear`. Entry is an inline password field with `Save`/`Cancel` and the note: ⚙️ *"The value is write-only — it is stored as a reference and never displayed again, here or in diffs, exports and history."* **There is no reveal affordance.**

### A.8.6 Section card
Header: expander · title · applicable-field count · an `Advanced` chip for all-advanced sections · spacer · a blocking count chip, or a warning count chip when there are no blocks. Body: normal fields, then a `Show N advanced fields` toggle. When nothing applies: *"No fields apply here with the current selections."*

### A.8.7 Section nav and jump
A left rail listing each component (multi-component only) and its sections, each with either a field count or a red/amber dot. Selecting one expands the section and scrolls its first field into view.

### A.8.8 Field search
A search box labelled *"Search N fields…"*. Matches label, key, rendered path and section **across all components**, and renders flat field rows with a `component · section` crumb. Only applicable fields match. Empty: *"No fields match "X"."*

### A.8.9 Validation
Two tiers. **Block** (e.g. `REQUIRED`) prevents save; **warn** (e.g. `ABOVE_RECOMMENDED`) does not. ⚙️ Validations re-evaluate live, so a required field clears as the operator types.
**Validation summary**: an alert headed *"N fields must be fixed before saving"* or *"N recommendations to review"*, with a clickable chip per field that jumps to it.

### A.8.10 Save bar and save flow
Sticky bar: *"No unsaved changes"* or *"N unsaved changes"* · a blocking chip · a recommendations chip · spacer · **`Save draft`** (tip: ⚙️ *"Writes the draft to the platform's own record and sends the last-seen generation. Writes nothing to the MI."*) · **`Apply…`** (tip: *"Writes the saved config to the MI. Usually needs a restart to take effect."*, disabled until saved).
Save success: ⚙️ *"Draft saved to the platform record. This wrote nothing to the MI — use Apply for that."*
Saving with warnings first opens the **override-reason dialog**: the list of warnings, a required free-text reason (a few words minimum), and *"Your reason is recorded in configuration history alongside the change."*

### A.8.11 Conflict dialog (`409`)
Title *"Someone saved first"*. Warn alert: *"Your draft is based on an older generation (HTTP 409). The platform rejected the save because another edit landed after you loaded this form."* Then a diff *"Your draft → current record"*. Then 🅸 an explicit invention notice: *"The contract does not specify conflict resolution. There is no 'overwrite anyway', 'take theirs', or per-field merge defined, and no actor or timestamp field to say who conflicted or when."* Three resolution buttons are rendered **disabled** with the caption *"disabled — not specified"*. The only enabled path is `Refetch & review`.

### A.8.12 Version states and reconciliation
**Version pill:** `Version OK` · `Version drift` · `Version unverified`, with `decl` / `obs` in mono. ⚙️ Unverified carries the tip *"Nothing corroborates the declared version — normal for an MI on a plain VM. This does NOT block editing."*
**Blocked banner** (drift/unknown): lock icon, *"Editing blocked · version drift"*, the reason, and ⚙️ *"The configuration below stays fully legible — this is read-only, not unavailable — but it can't be edited until the declared and deployed versions agree."* Two actions: `Accept the runtime…` and `Restore the runtime…`.
**Accept-runtime dialog:** *"The declaration will be re-rendered against the deployed version's schema."* Four classified rows — `Added / defaulted` · `Removed` · `Type or constraint changed` · `Renamed · needs mapping` — each with a count. Closing alert: ⚙️ *"renamed · needs mapping requires you to point the old field at its new name before this completes — an unmapped rename would silently drop the value."*
**Restore-runtime dialog:** *"Restoring re-applies the declared version to the target and restarts it — an outage. Use this when the deployed version was the accident."*

### A.8.13 Rollout card
A collapsible card headed `Rollout & replicas` with either a `Diverged` chip or an `N replicas` chip.
**Config stepper** — five nodes: `Desired · Rendered · Persisted · Restart-observed · Effective-verified`, each with a tip. ⚙️ Closing note: *"A completed restart reaches **restart-observed**, not effective-verified: the platform knows the process came back with the file, not that it is using it. Outcome is `APPLY_INDETERMINATE`."*
**Replica table** — columns `Replica · Rendered config version · Restart observed · Health`. 🅸 **Both middle column names carry an info tip saying they are invented — the contract names them in prose but defines no row schema.** Divergent generations are highlighted. `Restart observed` renders `observed` or `pending`.

### A.8.14 Strategy picker and restart confirm
Radio cards, each with a label, a description and consequence chips:
- `Manual` — **Default** chip. *"Stage config to every replica, then wait. Nothing restarts until you say so; the instance keeps serving on the current config."*
- `Rolling · health-gated` — *"Restart one replica at a time, advancing only when the last reports healthy."* ⚙️ Disabled when the package declares no health mapping (*"a health gate has nothing to read"*), and disabled for the flagship MI with the reason *"its standby reports healthy while processing nothing, so a health gate would advance past a replica that took no traffic."*
- `Rolling · no validation` — `High-consequence · confirm even when authorized`.
- `Restart all` — `Full outage · confirm even when authorized`.
- `Scheduled` — disabled, *"Out of this release."*
⚙️ When the package requires uniform config, **both rolling strategies are suppressed** with the reason stated. Footer: *"Default multi-replica ordering is **stage everywhere, then restart** — a halt leaves config uniform but unapplied, never half-applied."*
**Restart confirm.** Title *"Apply requires a restart"*, warn alert *"Requires restart · N replicas · expect unavailability · continue?"*, and the closing caption *"restart-observed ≠ effective-verified"*.

### A.8.15 Diverged banner and rollback
Error banner: *"Configuration diverged — replicas are holding different config generations after a halted rollout. Pick one remediation — there is no 'force healthy'."* Three actions: `Re-stage everywhere` · `Restart the remaining` · `Roll back to last uniform generation…`.
**Rollback dialog:** ⚙️ *"A rollback is a full change — reviewed like any other. This reverts every replica to gen-N. It gets no lighter confirmation for being a revert; review the diff first."* — **followed by the diff itself.**

### A.8.16 Diff view
Per row: the config path, a `secret · masked` tag when applicable, and two cells with configurable labels (`Deployed`/`Draft`, `Your draft`/`Current record`, `Current · gen-41`/`Target · gen-40`). ⚙️ A secret row shows a mask token on **both** sides.

### A.8.17 Package actions
Rendered as a card headed `Actions`: label, the caption ⚙️ *"Runs server-side against <named inputs> and returns a verdict — never a credential"*, and the action button. Success: *"Connection test passed (server-side). No credential was returned to the browser."*

### A.8.18 Composer canvas
Three panes.
**Palette** — component types from the package, each with an icon and colour, with the caption ⚙️ *"Generated from the package's declared component types — never a hard-coded FTP/SFTP/S3 list."*
**Canvas** — draggable nodes; a node shows a role label, a name, its field count and either a required-empty count (error) or an `ok` chip; a curved link joins source to sink; connection ports on each side. Banner: ⚙️ *"Node positions are presentation only — moving a node isn't a config change and won't prompt a restart."*
**Inspector** — the selected component's icon, label and type, then its own search box and the same section cards as the form view.
A `Composer / All fields` toggle switches between the canvas and the flat form — **the flat form is the non-pointer path**.

## A.9 · Governance

**Purpose.** ⚙️ *"The platform reports whether the MI's health endpoint says it is up; it does NOT judge whether it is working."* No synthetic score, no composite gauge.

### A.9.1 Health hero
A large tinted state badge, the rollup label, a `rollup` chip, an explanatory paragraph, and an `Open in Monitoring` action (**contained when red**, outlined otherwise).

Paragraphs by rollup:
- **Healthy** — *"The health endpoint reports up. The platform does not judge whether the MI is actually working — if it reports healthy while malfunctioning, that is the MI's defect."*
- **Not responding** — *"The health endpoint is not answering. This is the platform reporting the endpoint is down — open Monitoring to confirm with the graphs."*
- **Restart expected** — *"A platform-initiated restart is in progress. This is expected activity, not an outage."*
- **Standby · idle** — *"Every replica is a standby: up, and deliberately processing nothing. The instance is fine — a standby is not a failure and its zero KPIs are expected."*
- **No health declared** — *"All components of this solution declare no health block, so health is not applicable. This is normal — it is never an outage and never red."*

Beneath the hero, a standing note: ⚙️ *"**Why this screen is simple:** it answers one question — is the endpoint up? The workflow is red badge → open the graphs → no data confirms it is genuinely down. The cross-check only works because a gap renders as a gap, so there is deliberately no synthetic health score here."*

### A.9.2 Replica cards
A card per replica: name in mono, health chip, and a note. A standby card is visually distinct and reads ⚙️ *"Up · processing nothing. Excluded from averages; not a healthy zero."* The section caption reads *"N · standby excluded from availability math."*

### A.9.3 Config-state ladder
Five rungs with a connector, captioned *"five states, never collapsed"*:
1. **Desired** — *"Your intended configuration (the saved draft)."*
2. **Rendered** — *"SM produced the config file."*
3. **Persisted** — *"The file landed on the target."*
4. **Restart-observed** — `current` chip — *"The process restarted and came back with the file. This is as far as the platform can see for this MI."*
5. **Effective-verified** — an `effective-unverified` chip — ⚙️ *"We cannot see this. The MI exposes no protected config endpoint, so the running process is permanently effective-unverified — an honest gap, not a pass."*
Closing alert: *"A completed restart reaches restart-observed — never presented as convergence. The final stage stays honestly unknown."*

### A.9.4 Log viewer
🅸 Header *"Logs"* with the caption ⚙️ *"Streamed on request — never stored by the platform."*
Toolbar: component select · level select (`ERROR · WARN · INFO · DEBUG`) · a `DEBUG live · audited` chip when active · `Stream logs` / `Pause`.
Viewport: dark, monospace, auto-scrolling, bounded buffer; each line is `timestamp · level · message`. Idle text: *"Press Stream logs to tail this component."*
**Raising to DEBUG** opens a confirm: ⚙️ *"Privileged, audited action on a production process — DEBUG logging on a live process is verbose and can affect performance. The change and your identity are written to the audit log. Lower it again when you're done."*
**No log endpoint:** a neutral alert, ⚙️ *"<component> does not expose a streamable log endpoint, so logs can't be tailed here. This is a stated degraded condition — not an error, and not an empty stream implying silence."*

### A.9.5 States
loading (hero + card skeletons) · error (*"Couldn't load health for this instance… distinct from 'not responding'"*).

## A.10 · Explore and charts

**Layout.** A left control column of five numbered, gated steps and a right result column.

### A.10.1 Metric catalog and selector
⚙️ **Single-select.** Each entry: metric name (mono) · a layer tag (`pkg` for package-baseline, `ext` for user-extension) · a state chip (`plottable` · `needs typing` · `type-suspect`). A `needs-typing` entry is **disabled** and carries a `Type…` action. Below the list: `type <metricType> · unit <unit>`, or `untyped`.
A `type-suspect` selection adds a persistent warn alert: ⚙️ *"Type-suspect — plotted, never auto-corrected"*, with the declared reason.

**Type-metric dialog.** 🅸 *"This metric arrived unclassified. Inferring a type is forbidden — you must declare it. Until then it can't be plotted."* A three-way type choice (`counter · gauge · histogram`) and a free-text unit with the note *"there is no unit vocabulary anywhere in the contract, so this control has no list to offer."* A standing finding: *"The catalog entry has no `firstSeen` and no suggested-type field… so no suggestion can be offered here."*

### A.10.2 Step gating
`1 Metric` → `2 Instances` → `3 Series by` → `4 Aggregation` → `5 Group by`. Steps 2–4 are locked while the metric is untyped; step 5 is locked until aggregation across instances is chosen, and is labelled *"· needs aggregation"*.

### A.10.3 Instance selector and the series cap
A projection line `**N** / CAP series projected` with a right-hand `per instance` / `per replica` note, a meter bar that turns red past the cap, and a checkbox list where each row shows what it would add (`3 sr`). At the cap, unselected rows are **disabled** and an info panel appears: ⚙️ *"Series cap reached — two ways forward"* with `Narrow by label` and `Aggregate into one line`.
🅸 The reference hardcodes the cap at 20; ⚙️ the contract makes it `capabilities.maxSeriesPerWidget`, default 10 (conflict C6).

### A.10.4 Result column
Toolbar: mark toggle (`Line · Gauge · Stat`) · range select · the note `absolute · resolution auto` · spacer · `Save as widget`.
Errors render as one alert per code, titled with the **code** and `· 422 · not retried`, carrying the stated reason and 🅸 the caption *"The 422 body shape is unspecified — the field carrying this stated reason is invented."*

Typed error copy:
| Code | Message |
|---|---|
| `SERIES_CAP_EXCEEDED` | *"Projected N series exceeds the cap of M (seriesBy: X). Narrow the selection, or aggregate into fewer lines."* |
| `AGGREGATION_NOT_APPLICABLE` | *"Aggregation across instances needs at least two instances to combine."* |
| `GROUP_BY_REQUIRES_AGGREGATION` | *"Group-by only applies once you aggregate across instances."* |
| `STAT_REQUIRES_AGGREGATION` | *"A single-stat or gauge shows one number, so it requires aggregation to a single value."* |
| `METRIC_UNTYPED` | *"This metric has no declared type and cannot be queried until it is typed."* |

**Banners.** Partial: *"Partial data — some instances did not resolve"*, one line per unavailable instance with its reason, plus 🅸 *"Invented: `partial.unavailable[]` item shape and its reason vocabulary aren't in the contract."* Retention: *"Data before X is outside the retention window; the chart starts at the earliest retained point."*

**Chart-area empty states.** No instances: *"Select at least one instance — nothing is plotted until you choose what to compare."* Untyped: *"This metric needs a type"* + the typing action. Blocked: *"Resolve the request above to plot."*

**Standing legend note.** ⚙️ *"Hollow points = low coverage (fewer samples than expected). Line breaks are gaps — never zero-filled. Dashed = inactive/pending. Counter rates can't use a log axis (zero is legitimate when idle)."*

### A.10.5 Series rendering
- A bucket with no samples produces **no point**; the line **breaks**. Gaps also break at more than 1.5× the gap threshold.
- Low-coverage points (`sampleCount < expectedCount`) render as **hollow markers**.
- Retention truncation renders a shaded band and a dashed boundary.
- Inactive-replica and pending-config series render **dashed** and at reduced opacity.
- Thresholds render as dashed horizontal lines with a label.
- Annotations render as dashed vertical markers with a caret — e.g. *"replica added (+1) — summed series steps; not a workload change"*.

### A.10.6 Tooltip and legend
**Tooltip** — a shared-axis panel headed `<time> · served <tier>`, listing every series **sorted by value**, each row: colour swatch · label · a **state tag** (`gap · inactive replica · pending · type-suspect · best-effort`) · the formatted value. ⚙️ A series with no point at the cursor reads **`gap`**.
**Legend** — a bounded, **scrolling** list; each row is a swatch (dashed for inactive/pending), the label, and a state chip. ⚙️ It never truncates its membership.

### A.10.7 Gauge and single-stat
**Gauge** — a semicircular arc, filled proportionally, turning warn-coloured past a threshold, with the value and unit beneath.
**Single-stat** — a large value with the unit beneath and an optional delta.

## A.11 · Dashboard

### A.11.1 Landing
Monitoring opens on the package's default dashboard. A sub-tab row offers `Dashboards` · *the current dashboard* · `Explore`. 🅸 A standing note distinguishes three readings and states that **a dashboards index is not assumed — the contract has no list route.**

### A.11.2 Toolbar
Range select · refresh select (`Off · 10s · 30s · 1m · 5m`) · an `updating…` indicator while refreshing · resolution toggle (`Auto · Raw`, Raw present only when permitted) · spacer · layer filter (`All layers · Package defaults · Operator layer`) · `Edit layout` · `Reset defaults`.
Standing note: ⚙️ *"The dashboard's range and refresh are **authoritative for every widget** — a widget's own settings are defaults it overrides. There is deliberately no per-widget time pinning."*

### A.11.3 Widget cell
A 12-column grid; each widget spans `w` columns and is `h × 150px` tall. Header: title · **layer tag** (`package` / `operator`) · in edit mode an `#order` pill and move-earlier/move-later buttons · otherwise a menu (`Edit widget`; then `Hide (keep default)` for package widgets or `Delete` for operator widgets).
Body: a skeleton until the widget scrolls into view; then the chart, gauge or stat, dimmed while refreshing.
⚙️ A widget whose `schemaVersion` exceeds the console's renders **read-only and whole**: a lock icon and *"This widget's schema (vN) is newer than this console (vM). It renders read-only and whole — never partially — and unknown fields are preserved on save."*
⚙️ Each widget is inside its own error boundary — *"one failing widget never takes down the dashboard."*

### A.11.4 Layers
Two layers: **`package-baseline`** and **`operator`**, each badged. ⚙️ A hidden package default renders as a placeholder card: *"Hidden default · <title> — hidden, not deleted, so a later package can still improve this default and your choice survives"*, with a `Restore` action. **Package defaults are never deletable.**

### A.11.5 Contract findings panel
🅸 A collapsible `Contract findings` control opens: *"The dashboard document has no shape in the repo — invented here: envelope & field names · the hidden-default representation (modelled as an operator-layer override) · dashboard-level `range`/`refresh` · whether `{x,y,w,h,order}` live on the widget or a placement entry · a dashboards `list` route · the `reset` payload. Raised against the contract, not silently added."*

### A.11.6 Monitoring first run
⚙️ *"No dashboards saved yet — the empty console does not show an empty grid. Start from the entity types below and open Explore to build your first view."* Three tiles (entity type, replicas, metrics) with counts, then a single `Open Explore` action.

## A.12 · Admin and Targets

Both are **later increments and absent from the v1 navigation**, reachable by URL or the account menu's preview entries. Their reference posture is: ⚙️ *surface the model and the open blockers rather than inventing UI on top of unresolved specs.*

### A.12.1 Permission catalog
Grouped by resource — `solution · instance · config · lifecycle · observability · metrics · dashboard · target · role · user`. Each permission carries an id (`resource.action`), an optional **high-consequence** marker, an optional **two-sided** marker, and a **catalog status**:
| Status | Meaning |
|---|---|
| `grantable` | *"Exists in the ratified catalog and can be granted today."* — **only `target.use` and `instance.bindTarget`** |
| `proposed · not grantable` | *"Proposed (◇) but the catalog hasn't been amended, so it can't be granted yet."* — the six `DM-D18` metrics/dashboard verbs |
| `candidate · absent` | *"Not in the catalog at all — a placeholder name so the screen has something to lay out."* — the remaining ~22 |
🅸 Standing finding: *"Catalog not ratified… Adding a verb is a data change — this layout doesn't move."*

### A.12.2 Roles
Card grids for **built-in** (read-only, cloneable) and **custom** roles; each card shows name, kind chip, description, a permission count and an `incl. high-consequence` marker. Opening a built-in role shows: ⚙️ *"Built-in roles can't be edited — but they're fully inspectable, and cloneable. 'The built-in one but without restart' is the common real request."*
**Role editor**: a name field, then the permission catalog grouped by resource with per-group `All`/`Clear`, a selected count, and each row's markers and status chip.

### A.12.3 Assignments
An assignment is the triple **(principal, role, scope)**. Two views:
- **By principal** — a table of principal · role · scope · a row menu (`Effective perms`, `Remove`).
- **By scope** — a scope tree on the left; on the right, who reaches the selected node, each entry marked ⚙️ **`direct`** or **`inherited`** with the granting scope named. Standing note: *"Inherited rows come from an assignment higher in the tree. Removing one changes access here — showing it as 'direct' is how someone deletes an assignment and is surprised nothing changed."*
- **New assignment dialog** — a principal select with two option groups (`Users (local)` and `Groups (IdP)`) ⚙️ *"user or group — same picker"*; a role select; a scope tree with *"grants here and everything below"*. When the role carries a two-sided verb: *"Placing an instance on a target needs a permission on **both** the instance's scope and the target's `ownership_scope` — the one two-sided rule."*
**Principal tag** — an icon tinted by kind (user vs group), name, an `IdP group` chip where applicable, and a sub-line.
**Scope tree (`D20` 🔒)** — synthetic `All organizations` (`[]`) → `Organization → Sub-organization → Environment → Instance`, expandable, each node showing its level. There is no Tenant node.

### A.12.4 Access check
*"Given a principal and a scope: exactly what they have — and which assignment conferred it. The screen for 'why was I denied?'"* Left: principal select + scope tree. Right: a table of permission → conferring role, with a `direct`/`inherited` chip and the granting scope. With nothing: ⚙️ *"Deny-by-default: with no assignment reaching this scope, every action is denied."* Plus a worked denial: *"A permission that isn't granted is denied; one that doesn't exist can't be granted at all."*

### A.12.5 Users
🅸 A table of user · status · last sign-in · created-by · a `Deactivate`/`Reactivate` action. Standing warn: *"Everything on this screen is invention — it will be replaced. When identities come from each customer's IdP, this becomes a read-only list the provider supplies: **Add user** and **Deactivate** disappear; the **list itself survives**. Assignments are untouched."*
⚙️ Footer: *"Deactivate, never delete — an account that authored configuration history stays attributable."*
**Add-user dialog** — name, email, and the note ⚙️ *"Creating a user does not assign a role — identity and authorization are separate screens, on purpose, so identity can become IdP-backed later without touching assignments."* Then three credential options: *admin-set temporary credential* (chosen; forced change on first sign-in; **no external dependency**), *emailed invitation* (marked `needs email` — *"introduces an email capability this console does not have"*), *out-of-band handoff*.
**First-admin bootstrap** — 🅸 *"This deployment has no users yet, so no one can sign in. This screen is reachable only while the user table is empty."* Admin email · installer-provided initial secret · new password. Standing finding: ⚙️ *"**Load-bearing invention.** The first-admin bootstrap is the single largest hole — it is the first thing that happens to a new deployment and is specified nowhere."*

### A.12.6 Targets preview
A read-only table: target (id over location) · driver · **control locus** chip with a tip · ownership scope. Standing alert: ⚙️ *"`control_locus` is a security boundary worth surfacing. `dp-remote` means the platform stores host credentials centrally; `host-local` means it stores none and an outbound agent reports in; `observe-only` means SM never writes here."*
Two model cards — **Target** (`location · driver · tenant/trust domain · control_locus · ownership_scope`) and **Binding** (`infra_telemetry_source · config_mechanism · render_outputs[] · runtime_attachments[] · selectors · lifecycle_owner · restart_strategy`).
Closing finding: ⚙️ *"Not designed here — blocked. Credential entry is intentionally absent: the apply protocol it depends on is an open blocker. Nothing on this screen writes to a target."*

### A.12.7 Named-but-unspecified surfaces
The reference lists these explicitly rather than inventing them: **local-user CRUD** · **role builder** · **assignment scope picker** · **audit log** · **403 page** — the last with the note that ⚙️ *"whether it may name the missing permission"* is an unresolved information-leak question.

## A.13 · Fixture datasets

These are the datasets the reference used and the basis for §9's fixture layer. They are **fixtures, not contracts**.

### A.13.1 State vocabularies
**Health (5)** — `healthy` · `not_responding` (*"stopped answering health checks"*) · `restart_expected` (*"the platform restarted this instance on purpose — this is not an outage"*) · `inactive_replica` (*"a standby: up and deliberately processing nothing… its KPIs read zero because it takes no traffic"*) · `not_applicable` (*"exposes no health endpoint — a normal condition. It is never treated as an outage"*).
**Declared-but-not-found (5)** — `restart_expected` · `unreachable` *(Host / target alert)* · `not_exposed` *(Configuration finding)* · `missing` *(Instance alert)* · `bootstrap` *(Bootstrap — "declare → apply → restart → observable")*. ⚙️ Never one `missing` badge.
**Version (4)** — `ok` · `drift` (read-only until reconciled) · `unknown` (blocked; monitoring continues) · `unverified` (*"normal for an MI on a plain VM. Editing is allowed."*).
**Config (6)** — `in_sync` → *"Applied"* (with the effective-unverified caveat) · `staged` → *"Staged · restart req'd"* (*"written is not working"*) · `rendered` → *"Rendered · not persisted"* · `diverged` · `drift` (*"a hand-edit. Alert-only; reconcile manually (never auto-reverted)"*) · `na` (*"observe-only target — Solution Manager writes no configuration here"*).
**Driver (3)** — `docker · k8s · systemd`. **Locus (3)** — `dp-remote · host-local · observe-only`, each with the security tip in §A.12.6.
**Env tones** — `DEV` neutral · `TEST` info · `PROD` purple.

### A.13.2 Instance inventory fixture
Twelve instances spanning **every** state above: healthy multi-replica with a standby; version drift; version unverified on a VM; a four-replica healthy instance; a staged config with one replica down; a restart-expected replica; a diverged config; an unreachable host with an unknown version; an observe-only target with `na` config; a never-observed bootstrap instance; a config-drift instance that is `missing`; and a `not_exposed` instance. Each carries `env · org · sub-org · driver · locus · replicas[] · rollup · version{state,observed,declared} · config · lastSeen`.

### A.13.3 Designer model fixtures
**Single-component** — 26 fields across 8 pre-ordered sections (`General · Keying · Expiry* · Sizing · Topics · Hit response* · Broker · Management*`, where `*` are all-advanced) with **four discriminators** and a two-level nested predicate chain, plus one `test-connection` action over four inputs including a secret.
**Multi-component** — two composed components (a source and a sink) with ~100 generated fields across 11 sections, **repeated keys across components**, and ~27 `virtual` fields (no rendered path).
**Rollout fixtures** — a four-replica set mid-rollout (two observed, one pending, one standby on the prior generation) and a diverged set (two on each of two generations).
**Diff fixtures** — a draft-vs-deployed set including a secret row, and a conflict set.

### A.13.4 Metric catalog and series generator
Eight metrics spanning `plottable` (both layers), one `needs-typing` (null type and unit) and one `type-suspect` with a stated reason; each with `metricType · unit · layer · state · lastSeen · labelKeys · durability`.
The generator produces a response of the contract's shape and honours eight data states: `normal · gaps · low coverage · retention truncation · no data · partial · inactive replica · pending config`.
Range presets: `15m · 1h · 6h · 24h · 7d · 30d · 90d · 6mo`.

### A.13.5 RBAC fixtures
Five built-in and custom roles (including *"the built-in Org Operator, restart removed — the common real request"*); five principals (three local users, two IdP groups); a synthetic `All` root at `[]` above four grantable levels (`Organization → Sub-organization → Environment → Instance`); five assignments demonstrating direct and inherited grants; four local users including one deactivated. **`D20` 🔒 fixes this shape:** former Tenant → Organization, former Organization → Sub-organization, former Sub-org removed.

## A.14 · The state harness — and why it must not ship

The reference carried two review affordances that are **not product features**:
- A **Demo state** selector in the app bar cycling `Populated · Loading · Empty/first-run · Partial data · Error`, described in-screen as *"Prototype control. Switches the current screen between its loading, empty/first-run, populated, partial-data and error states so every state is reviewable."*
- Per-screen **Scenario** selectors — Designer (`Editable · Validation · Blocked · Restart · Diverged · Composer`), Governance (`Healthy · Two standbys · Restarting · Not responding · No health declared`), Explore (a data-state select), plus simulate-toggles for a `409` and for a missing log endpoint.

⚙️ **In production the state comes from the server.** Task 036 removes both and adds a lint rule; task 101 asserts their absence from the production bundle. They are recorded here because they are the reason the state matrix (§10) is exhaustive — every state was drawn, not inferred.

---

# Appendix B — visual token record

> ⚠️ **Provenance and status.** Values were read from the Solace Agent Mesh Design System token file and the console stylesheet. Both are now checked in: [`../../design/frontend/reference/theme.css`](../../design/frontend/reference/theme.css) and `reference/_ds/solace-agent-mesh-design-system-659eb3ca…/colors_and_type.css` (sha256 `eef234c1…`), which `FE-D30` names the source of truth for visual styling. **That is not acceptance.** They remain **pending architect acceptance** (§16 Q1) and `FE-D5` keeps brand paint blocked until then. A PR that treats them as brand-final before that acceptance is a defect.
>
> ⚠️ The reference bundle also carries the **Solace Brand Book v2.9** (`reference/_ds/solace-brand-book-design-system-2b582b3d…`). It agrees on brand green `#00C895` but differs on the dark blue — `--blue-deep #093B5F` / `--blue-dark #03213B` against Agent Mesh's `#015B82` / `#03223B`. The prototype links **only** the Agent Mesh file, so that chain wins under `FE-D30`; the Brand Book is checked in as context and is not wired up. Folds into Q1.

## B.1 Solace Agent Mesh tokens

Naming convention: `{role}-{w100 | wMain | w90 | w60 | w40 | w30 | w20 | w10}`, where `wMain` is canonical and `w100` is darkest.

| Group | Values |
|---|---|
| **Brand** | `wMain #00C895` (signature Solace green) · `w100 #00AD93` · `w60 #66DEBF` · `w30 #B3EFDF` · `w10 #E6FAF4` |
| **Primary (deep navy blue)** | `w100 #01374E` · `w90 #014968` · `wMain #015B82` · `w60 #679DB4` · `w40 #99BDCD` · `w20 #CCDEE6` · `w10 #E6EFF2` |
| **Primary text** | `wMain #273749` · `w100 #000000` · `w10 #FFFFFF` |
| **Secondary (neutrals)** | `w100 #000000` · `w80 #354E62` · `w70 #536574` · `wMain #8790A0` · `w40 #CFD3D9` · `w20 #E7E9EC` · `w10 #F3F4F6`; secondary text `wMain #647481` · `w50 #B1B9C0` |
| **Background** | `w100 #021B2F` (darkest navy) · `wMain #03223B` (dark surface) · `w20 #F7F8F9` (light panel) · `w10 #FFFFFF` (light default) |
| **Info** | `wMain #0591D3` · `w100 #2B71B1` · `w30 #B4DEF2` · `w10 #E6F4FB` |
| **Error** | `wMain #E94C4E` · `w100 #C33135` · `w30 #F8C9CA` · `w10 #FDEDED` |
| **Warning** | `wMain #FF8E2B` · `w100 #E1681F` · `w30 #FFDDBF` · `w10 #FFF4EA` |
| **Success** | `wMain #009A80` · `w100 #006B53` · `w30 #B3E1D9` · `w10 #E6F5F2` |
| **Accents** (charts, tags, chips) | blue `#3C69E1` · indigo `#3A4880` · teal `#009193` · purple `#7841A8` · magenta `#CB1AA5` · coral `#F66651` · orange `#FCA829` · sky `#7CD3F6` · slate `#86939E` · red `#DA162D`, each with a 10-tint where used |
| **Dark surface** (nav rail on a light theme) | bg `#03223B` · bg-hover `#021B2F` · text `#F5F5F5` · text-muted `#ACBAC2` · brand `#00C895` · border `#536574` |
| **State layer** | `w10 #03223B1a` · `w20 #03223B33` |

⚠️ **Charting note.** The categorical series palette used by the reference charts is a separate, colour-blind-oriented set (`#4E79A7 #59A14F #B07AA1 #9C755F #EDC948 #76B7B2 #FF9DA7 #8CD17D`) rather than the accent list above. ⚙️ Task 097 must validate **whichever set is adopted** at ≥ 3:1 against **both** surfaces; neither set is validated today.

## B.2 Console layer and dark mode

The console stylesheet's own header states the reconciliation:

> *"SOLUTION MANAGER — MUI-structured component layer, painted with Solace tokens. Palette/type come from Agent Mesh `colors_and_type.css` (Rubik + Solace scale). Structure & interaction follow Material-UI conventions (outlined fields, tabs with indicator, contained/outlined/text buttons, data grid, paper elevation)."*

**Derived console values.** primary `#015B82` with hover `#014968` and active `#01374E`; primary tints at 5.5% / 12% / 16% alpha; accent `#00C895`; navy `#03223B`; borders from `secondary-w40` (default) and `secondary-w20` (subtle).
**Elevation** — four steps: `0 1px 3px / 0 1px 2px`, `0 2px 6px / 0 1px 3px`, `0 6px 16px`, `0 14px 34px`, all in `rgba(3,34,59,·)`.
**Motion** — `180ms` standard, `120ms` short, `cubic-bezier(.4,0,.2,1)`; ⚙️ `prefers-reduced-motion` collapses all durations to ~0.
**Focus ring** — ⚙️ `2px solid` brand green at `2px` offset, on `:focus-visible`, with a `2px` radius.
**Selection** — brand green at 24% alpha.

**Dark mode** is applied by a root `data-theme="dark"` attribute (the MUI equivalent is `palette.mode`, `FE-D5`): page and canvas `#03223B`; raised surfaces (app bar, papers, cards, grids, sections, widgets, modals, popovers, toasts) `#0A2C47`; recessed surfaces (table headers, inactive cards, hidden-default cards, secret blocks) `#0E344F`; headings `#EAF1F6`; body `#C9D4DD`; muted `#8EA9BD`; borders `#2B4358` / `#213546`; chart axis text `#8EA9BD`; grid lines `#1C344A`.

## B.3 Typography

**Families** — sans `Rubik`, falling back to `Inter`, `system-ui`, `-apple-system`, `Segoe UI`, sans-serif; mono `JetBrains Mono`, falling back to `ui-monospace`, `SFMono-Regular`, `Menlo`.
⚠️ The DS file loads these from Google Fonts. ⚙️ `REQ-003` includes air-gapped deployments, so task 002 **self-hosts them** and task 100 asserts zero third-party requests.

**Scale** — `xs 12/16 · sm 13/18 · base 14/20 · md 16/24 · lg 18/26 · xl 20/28 · 2xl 24/32 · 3xl 30/38 · 4xl 36/44 · 5xl 48/56`. **Baseline is 14px.**
**Weights** — regular 400 · medium 500 · semibold 600 (buttons) · bold 700.
**Console heading roles** — `h4 26/1.2 semibold, -0.01em` · `h5 20/1.25 semibold` · `h6 16/1.4 semibold` · subtitle `14 semibold` · body `14/1.55` · small `13/1.5` · caption `12/1.45` · overline `11 semibold, uppercase, 0.08em`.
**Spacing** — a 4px base scale (`4 8 12 16 20 24 32 40 48 64 80`).
**Radii** — `xs 2px` (inputs) · `sm 4px` (small buttons, chips) · `md 6px` · `lg 8px` (cards, base) · `xl 12px` · `full 9999px`.

## B.4 Button specimen

Height `40px` (`sm 32` · `lg 46`), padding `0 18px`, radius `6px`, font `13.5px` semibold, `0.02em` tracking, **uppercase**, minimum width `64px`, `8px` gap to an icon.
- **Contained** — primary fill, white text, elevation 1; hover darkens to `primary-w90` at elevation 2; active `primary-w100`.
- **Outlined** — white fill, primary text, a 50%-alpha primary border; hover tints the fill and solidifies the border.
- **Text** — transparent, primary text, tighter padding.
- **Danger (outlined)** — white fill, `error-w100` text, `error-w30` border; hover fills `error-w10`.
- **Danger (contained)** — `error-wMain` fill, white text.
- **Disabled** — 50% opacity, no elevation, `not-allowed`.
- **Icon button** — `40px` (or `32px` small), radius `8px`, transparent, hover fills `secondary-w10`.

⚠️ **One open brand question, recorded by the reference itself:** the DS *guide* says brand green is not a primary-button colour, while the DS *button specimen* renders the primary button in green. The console screens follow **marine `#015B82`** for primary actions and reserve green for accents, focus and the active-nav marker. ⚙️ Confirm with design which is canonical (folds into §16 Q1).

## B.5 Layout

**Viewport** — the reference shell is fixed at `1280px` (`min-width: 1280px` in `reference/`). That is the **drawn** desktop. **Ship `FE-D29`:** reflow to 320 CSS px (C3, Q7, tasks `105`–`110`). Do not copy `min-width: 1280px` into production.
**Shell** — a `256px` drawer plus the main column; app bar `64px`.
**Page widths** — content centred at `1240px`, or `1560px` on wide pages (inventory, solutions, Designer, dashboards), with `32px 40px 64px` padding.
**Dashboard grid** — 12 columns; a widget spans `w` columns and is `h × 150px` tall.
**Scrollbars** — `11px`, thumb `secondary-w40` with a 3px transparent inset and a fully-round radius.
