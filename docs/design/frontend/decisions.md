# Frontend decisions — `FE-D*`

Decisions arising from [`design.md`](design.md). **All are `PROPOSED`.** None is ratified.

> **As-built, 2026-08-28.** Frontend code now exists on branch `dev/frontend` — plan [`002`](../../plans/frontend/implementation.md)
> phases 0–5 (`FE-001`…`FE-067`, `FE-105`…`FE-125`; 85 of 125 tasks). `FE-D1`…`FE-D37` are implemented against, and
> [`design.md`](design.md) §28 maps each one to the module that carries it. **Implemented is not ratified** (`I4`): the
> code is a fixture-backed console with no server behind it, and every decision below still awaits sign-off.

**ID discipline (`I3`):** IDs are immutable and never renumbered. `FE-D*` is the per-component family named in [`../README.md`](../README.md). It does **not** re-decide `D*`, `B/D*`, `SM-D*`, `UI-D*` or `DM-D*`. Cross-cutting choices are cited, not restated.

> **Amendment policy for this draft.** `I3`'s supersession rule protects decisions something has been built or ratified against. `FE-D1`…`FE-D29` are a first draft with no code and no ratification, so review corrections in the same cycle may be applied in place and itemized in the changelog. **Post-ratification the supersession rule applies normally.** `FE-D26` was superseded by `FE-D29` in this same unratified cycle and is kept on the register.
>
> ⚠️ **That latitude has now largely expired.** `I3`'s protection attaches to *built-against*, not only to *ratified*: every decision a module cites in its header (`design.md` §28) is one an in-place edit would silently invalidate. From 2026-08-28, change those by **superseding**, not by editing — the same rule that has always applied post-ratification.

⚠️ **This family rests on an unratified and partially contested base.** `D7` is `LOCKED` but **open item 19** contests the visual system. `SM-D*` and all of `UI-D*` / `DM-D*` are `PROPOSED`. Per `I4`, nothing here may be implemented as though settled.

⚠️ **Two decisions are gated on work outside this document.** `FE-D18`'s **apply/restart** half depends on [`../DECISIONS.md`](../DECISIONS.md) **open item 1** (the `dp-remote` apply protocol — a ratification blocker); its **save** half is unblocked. `FE-D23`'s **`yaml`-embedded-secret** clause gates `type: yaml` on a server-side redaction obligation that does not exist yet. Neither gate was visible in the first draft of this family; both were raised by independent review (design §27).

⚠️ **Conflicts flagged, not resolved (`I4`):**

| This family | Against | Disposition |
|---|---|---|
| `FE-D4` harvest of File Mesh wrappers | `D15` 🔒 generalize-and-harden vs `SM-D18` greenfield | Frontend is a **Vite greenfield** (`D7` already says migrate CRA→Vite). Harvest **wrappers and UX patterns**, do not fork the CRA/Redux-Saga app. Backend Java/Node is out of this document. |
| `FE-D5` / `FE-D52` theme tokens | Open item 19 / 19b — the architect group has not accepted a visual reference, and 19b names the **designer** as its authority | Stack follows `D7`. **Paint tokens stay blocked** until the architect group accepts one. The reference is no longer absent — `FE-D30` checks it in at [`reference/`](reference/README.md) — and **partly resolved 2026-08-19 by `FE-D52`:** the palette is verified against Solace product source, so "no in-repo reference" no longer holds and the interim pair is retired. But *present* is not *accepted* and *verified* is not *confirmed*: development proceeds against those values, brand-final status does not. Building on them needs the `design.md` §24 gate-2 waiver, and visual-regression baselines stay blocked. |
| `FE-D30` `reference/` as visual spec of record | `D7` 🔒 refers to a mockup that no longer exists | `D7`'s *instruction* — express the mockup as an MUI theme — is honoured. **Which artifact fills the "mockup" slot is a new proposed decision, not a re-reading of `D7`** — writing this reference into `D7`'s meaning would shift a locked decision in place (`I3`). Flagged for the architect group with the `FE-D*` set. |
| Icon set: `FE-D30` reference vs [`design.md`](design.md) §8.1 | §8.1's Icons row says **"MUI icons; status never icon-only"**; the approved mockup uses a **Lucide-style stroke set** (80 icons, `reference/app/icons.jsx`) that is visually distinct from Material's | **Flagged, not resolved (`I4`).** The accessibility half of §8.1 — *status never icon-only* — is load-bearing and stands unconditionally. The *"MUI icons"* half was written when no mockup existed, which is the same position the palette was in before `FE-D52`. Implementation proceeds on the mockup's set, **generated** from `icons.jsx` rather than transcribed, because `FE-D30` gives the reference precedence on visuals; `@mui/icons-material` stays available for anything the mockup lacks. **Whether §8.1 is amended is the architect group's call** — route it with the `FE-D*` set. |
| ⚠️ `FE-D30` reference copy | **`architecture.md` §7.2 and `SM-D2`** — the mockup's *"Restore the runtime"* modal (`app/designer.jsx:303`) has SM **re-apply a version to a target and restart it**. Architecture says SM *instructs the operator* to deploy through their own pipeline, then re-checks | 🔴 **The architecture wins.** SM being a config/governance plane and **not** a deployment plane is the one thing the architect group has confirmed. The reference contradicts **itself** here (`app/instances.jsx:109`: *"applies configuration, never artifacts"*), so this is a defect in the artifact, not a design choice — recorded in [`reference/ERRATA.md`](reference/ERRATA.md) **E1**. `FE-D30` is overridden for those lines. |
| ⚠️ `FE-D30` chart palette | **`FE-D52`** — `app/charts.jsx:9` paints every series in the **Tableau 10** ramp; none of its eight colours appears in the verified Solace palette, which carries a dedicated chart ramp | **`FE-D52` wins on paint**; `FE-D30` wins on layout and copy. Errata **E4**; added to open item 19b as a designer question. Plan task `097` must validate whichever set ships at ≥ 3:1 against **both** surfaces. |
| ⚠️ `FE-D30` dashboard layers | **`DM-D5`** — the mockup uses two layers (`package-baseline` / `operator`, reusing the *metric-declaration* layer vocabulary); `DM-D5` defines three (`product-default` / `shared` / `personal`) and its `personal` clause supersedes `SM-D31` | **Flagged, not resolved.** Two closed vocabularies for "layer" now exist with overlapping-but-different members — the drift `UI-D14` exists to prevent. Whichever ratifies, the reference's chips and copy need renaming. Errata **E5**. |
| Renderer last-mile | `D1` 🔒 custom-UI escape hatch vs `UI-D21` named screen types | This document implements the **generic renderer + composer**. The escape hatch stays until the architect group decides. |
| `FE-D11` Data Grid Community | `D7` harvest of File Mesh tables, some of which may need Pro features | v1 uses MIT Community. Pro/Premium is a **procurement** decision, not assumed. |
| Chart adapter | `DM-D13` vs `D7` harvest-charts | **Uphold `DM-D13`.** MUI remains the chrome. See design §11. |
| `FE-D23` yaml-embedded secrets | `UI-D23` §3.7 item 3 accepts **warn-only, stored in clear** vs `I6`'s "never in **field-level diffs**, **exports**, archives or logs" | This document binds only what it owns: the **payload** the console receives must be redacted server-side, and **no `type: yaml` field ships until it is**. Whether the *stored* value is redacted is Subsystem A's decision — **flagged, not resolved.** |
| `FE-D7` base-URL scope | `SM-D1`'s UI seam names "independently configurable base URLs" for `cpClient`/`dpClient` | Narrowed to **internal/packaging-only** configurability behind one public gateway origin (`FE-D19`). Two public `VITE_*` URLs would leak plane topology to the browser and fail `REQ-003`. A defensible reading of `SM-D1`, not a restatement of it — flagged per this document's own discipline. |
| `FE-D29` viewport (320 px reflow) | `dynamic-metrics` §18 desktop-only ≥1280 px non-goal | **Supersedes that non-goal on SC 1.4.10 grounds.** Cross-component; architect-group sign-off required (implementation plan Q7). Recorded, not silently resolved against a `LOCKED` `D*`. |
| `FE-D30` `reference/` as visual source of truth | `FE-D29` reflow to 320 px — the reference has no responsive layer at all | **`FE-D29` stands.** `FE-D30` is explicitly scoped out of responsiveness; narrower layouts are derived, not drawn. Neither decision moves. |
| `FE-D30` `reference/` as visual source of truth | `FE-D5` paint blocked on open item 19 | **`FE-D5` stands.** `FE-D30` names the working reference; it does not accept it as brand-final. Open item 19 remains open and is the architect group's to close (`I4`). |
| `reference/_ds/` carries two design systems | Solace Brand Book v2.9 `#093B5F`/`#03213B` vs Agent Mesh `#015B82`/`#03223B` | The prototype links **only** Agent Mesh, so that chain wins under `FE-D30`. Brand Book checked in as context, not wired up. **Flagged for design, not resolved here.** |
| `FE-D31` multi-version `packageVersion` array + per-declaration `version` | `UI-D11` originally specified a scalar package version and no component/field `version` | **Resolved by `UI-D25` (PROPOSED).** The owning Dynamic UI family now specifies the array model, the root schema implements it, and the browser validates the same structure. `UI-D11`'s superseded body remains as history. |
| `FE-D45` full 30-operation catalog marked `grantable` | `D10` 🔒 — its ratified catalog grants exactly `instance.bindTarget` and `target.use`; the 28 others are the **unratified** candidate set from the approved mockup | ⚠️ **The console has implemented a `D10` amendment that the architect group has not made.** `D10` is `LOCKED`; per `I4` neither this family nor code may amend it. `DECISIONS.md`'s `D10` row is deliberately **untouched** and the amendment remains open on [`D10-AMENDMENT-PROPOSAL.md`](https://github.com/parikshit-coe/solution-manager/blob/main/docs/design/D10-AMENDMENT-PROPOSAL.md) (issue #20). **Flagged, not resolved (`I4`)** — ratification is the architect group's, and this decision is reverted, not upgraded, if they amend the catalog differently. |
| `FE-D46` removes Tenant from the resource hierarchy and makes `[]` a synthetic global root | `D11` 🔒 defined `Tenant → Organization → (Sub-org) → Environment → Solution Instance` | **Resolved by `D20` 🔒.** The synthetic root and Tenant removal stand. `FE-D53` completes `D20`'s data migration: former Tenant → Organization, former Organization → Sub-organization, former Sub-org removed. |

---

| ID | Decision | Status |
|---|---|---|
| `FE-D1` | Server state is TanStack Query; API data is never a global Redux/Zustand cache | PROPOSED |
| `FE-D2` | Client/global UI state is Zustand; ephemeral UI is React local state | PROPOSED |
| `FE-D3` | Feature-oriented folders with a shared kernel | PROPOSED |
| `FE-D4` | One design system: MUI + harvest wrappers; no second library | PROPOSED |
| `FE-D5` | Styling is theme tokens only; brand paint blocked — **paint clause superseded by `FE-D52`** | PROPOSED |
| `FE-D6` | React Router; serializable workspace state lives in the URL | PROPOSED |
| `FE-D7` | Two clients `cpClient` / `dpClient`; types from OpenAPI; composition in hooks | PROPOSED |
| `FE-D8` | Auth adapter: v1 local session, OIDC-ready; tenant never from the client | PROPOSED |
| `FE-D9` | Unauthorized actions are omitted; disabled-with-reason is for capability limits | PROPOSED |
| `FE-D10` | Typed errors, scoped boundaries; empty is never a silent success | PROPOSED |
| `FE-D11` | Tables use MUI X Data Grid **Community** in v1 | PROPOSED |
| `FE-D12` | Vitest + Testing Library + Playwright; shared `UI-D15`/`DM-D20` fixtures | PROPOSED |
| `FE-D13` | Client logs carry correlation ids; never secrets or telemetry values that `I6` forbids | PROPOSED |
| `FE-D14` | Console is a static SPA; runtime env is injected; no secrets in the bundle | PROPOSED |
| `FE-D15` | TypeScript `strict`; generated contract types; no `any` in app code | PROPOSED |
| `FE-D16` | ECharts is imported only through `ChartRenderer` | PROPOSED |
| `FE-D17` | Designer drafts use React Hook Form; **save payload is `project()`'s `rendered` set — rendered *minus* `virtual`** (`UI-D24`) | PROPOSED |
| `FE-D18` | **Save** sends the config-of-record generation; `409` reloads a diff — no silent overwrite. **Apply/restart** ordering is **blocked** on the `dp-remote` apply protocol | PROPOSED · clause B **BLOCKED** |
| `FE-D19` | The browser talks only to a **same-origin gateway**; it never calls a private DP | PROPOSED |
| `FE-D20` | Query defaults are fail-closed for metrics and mutations: no focus refetch, no optimistic apply | PROPOSED |
| `FE-D21` | Explore/widget **documents** are not stored in the URL; the URL holds ids and range | PROPOSED |
| `FE-D22` | RBAC is a **second overlay** after `project()`; the engine stays auth-free | PROPOSED |
| `FE-D23` | The browser never receives a **resolved** secret — nor a `yaml`-embedded one in a diff/export payload; reveal is not a hide/show of the value | PROPOSED |
| `FE-D24` | Idle session returns to login; production builds contain no auth mock | PROPOSED |
| `FE-D25` | Designer drafts survive idle lock in per-tab `sessionStorage` (no secret intent); restore behind a banner | PROPOSED |
| `FE-D26` | Desktop-only ≥1280 px — **SUPERSEDED by `FE-D29`** | PROPOSED · superseded |
| `FE-D27` | A 403 never names the missing permission | PROPOSED |
| `FE-D28` | Supported browsers: evergreen Chrome, Edge, Firefox, Safari, latest two | PROPOSED |
| `FE-D29` | Console reflows to 320 CSS px (WCAG 2.2 AA SC 1.4.10); supersedes `FE-D26` | PROPOSED |
| `FE-D30` | `reference/` is the source of truth for UI screens and visual styling | PROPOSED |
| `FE-D31` | A package serves several versions; an instance is configured against exactly one | PROPOSED |
| `FE-D32` | Authored version history in the shipped example packages is demo data, not MI history | PROPOSED |
| `FE-D33` | The Designer read carries the package; the browser projects (resolves plan §16 Q19) | PROPOSED |
| `FE-D34` | `shared` may import `contracts` | PROPOSED |
| `FE-D35` | One component instance per declared type; `one-of-many` composition is not invented | PROPOSED |
| `FE-D36` | The Designer has no Cancel; a draft is discarded by leaving | PROPOSED |
| `FE-D37` | The instance's package version is threaded, never re-derived | PROPOSED |
| `FE-D38` | New local users get an admin-set temporary credential (forced change on first sign-in); the first-admin bootstrap stays unbuilt | PROPOSED |
| `FE-D39` | The palette is per-mode and contrast-derived, and CI is the arbiter | PROPOSED |
| `FE-D40` | An e2e journey steers the session through DEV-only URL overrides; the fixture stays coherent | PROPOSED |
| `FE-D41` | One CSP, written in `index.html`, with every relaxation recorded — and ECharts needs no `unsafe-eval` | PROPOSED |
| `FE-D42` | The logger's field type carries only primitives; a payload is a compile error | PROPOSED |
| `FE-D43` | MSW's worker ships in `public/` and the build strips it; the gate reads the artefact | PROPOSED |
| `FE-D44` | A jsdom render budget is a ratio, not a millisecond count | PROPOSED |
| `FE-D45` | The console ships D10's full 30-operation catalog as `grantable`, ahead of ratification | PROPOSED · **superseded by `FE-D47`** (`D18` 🔒 ratified the catalog 2026-09-04) |
| `FE-D46` | The resource hierarchy is Organization → Sub-org → Environment; Tenant is not a level in it | PROPOSED |
| `FE-D47` | Groups gate on the ratified `group.*` verbs; membership is `group.member.manage` | PROPOSED · implements `D18` 🔒 |
| `FE-D48` | The v1 group model: one organization, immutable, flat, additive allow-only | PROPOSED · implements `D18` 🔒 |
| `FE-D49` | Assignments may be composed at Instance scope; root/global renders but is never created | PROPOSED · implements `D19` 🔒 |
| `FE-D50` | The Bootstrap Super Admin is represented by the console, never created by it | PROPOSED · implements `D19` 🔒 |
| `FE-D51` | The app bar labels the identity string **Organization**, not Tenant — superseding `FE-D46`'s app-bar clause | PROPOSED · old `Identity.tenant` seam superseded by `FE-D53`/`D20` |
| `FE-D52` | Verified product palette governs UI semantics; brand book governs identity. Supersedes `FE-D5`'s paint clause; designer confirmation still pending | PROPOSED |
| `FE-D53` | Bind the console to `D20`: Organization is the top isolation/ownership level; remove Tenant fields and the former Sub-org data layer | PROPOSED · implements `D20` 🔒 |
| `FE-D54` | Validate the canonical multi-version Solution Package JSON in the browser before verification or projection | PROPOSED · aligned with `UI-D25` and `FE-D31` |
| `FE-D55` | An instance may bind to the component types it composes; absent means every declared component renders | PROPOSED · consumes `UI-D26`; §16 Q10 still open |
| `FE-D56` | The declare dialog offers a `Role` select when a package declares 2+ non-`none` roles; the choice is resolved to a `componentTypes` list client-side before submit | PROPOSED · authoring half of `FE-D55`'s fixture-layer gap; §16 Q10 itself stays open |

---

## `FE-D1` — Server state is TanStack Query

Catalogues, instance config, health, metric queries, dashboards and mutations live in **TanStack Query**. They are **not** copied into Zustand or Redux.

**Rationale.** Dynamic Metrics §10 already recommended this for keying, in-flight dedup, cancellation, and stale-while-revalidate — the exact failure mode of a dashboard that blanks on refresh. File Mesh's Redux-Saga store treated server data as client state; that is the anti-pattern this decision refuses. Licence: **MIT**, verified 2026-08-16 from [TanStack Query LICENSE](https://raw.githubusercontent.com/TanStack/query/main/LICENSE).

**Rejected:** Redux Toolkit + RTK Query as the server cache — MIT, verified 2026-08-16 from [redux-toolkit LICENSE](https://raw.githubusercontent.com/reduxjs/redux-toolkit/master/LICENSE), and capable, but it duplicates TanStack Query and recreates File Mesh's "all server data in one store". Zustand as the server cache (no query lifecycle). A hand-rolled fetch layer (re-implements cancellation badly).

## `FE-D2` — Zustand for client/global UI; React state for ephemeral UI

Zustand holds: theme mode, sidebar collapsed, capability snapshot fallback, and "unverified theme" banner dismissed. Designer field drafts use **React Hook Form** (`FE-D17`). Dialog open, chart zoom, and wizard step are **React local state**.

**Rationale.** Server state and client state have different lifetimes. One global store for both is how File Mesh grew an untestable saga graph. Licence: **MIT**, verified 2026-08-16 from [zustand LICENSE](https://raw.githubusercontent.com/pmndrs/zustand/main/LICENSE).

**Rejected:** Redux Toolkit for this slice — extra ceremony without a cross-tab undo log or time-travel requirement. Context-only — prop-drilling and extra renders at shell scale.

## `FE-D3` — Feature-oriented folders with a shared kernel

`app/` (shell, routing, providers), `shared/` (design system, clients, auth, errors), `features/<name>/` (designer, governance, monitoring, inventory, admin). A feature may import `shared` and other features **only** through their public `index.ts`. Dynamic UI and Dynamic Metrics are features that **export** renderer/metrics packages; they are not a second app.

**Rationale.** `REQ-001` fails if every solution grows a folder of screens. Features map to operator jobs; the package/view-model engines stay inside designer and monitoring.

## `FE-D4` — One design system: MUI + harvest wrappers

All chrome is MUI (`D7` 🔒). Common components are **thin, typed wrappers** harvested from File Mesh where the wrapper is still valid, redesigned where the audit or a11y fails. **No Tailwind, no Radix, no shadcn, no parallel token set.**

**Rationale.** Open item 19 is a **visual-reference** gap, not a licence to run two stacks. A second design system would fork every Dynamic UI control mapping.

## `FE-D5` — Theme tokens only; brand paint blocked

> ⚠️ **The paint clause of this decision is SUPERSEDED by [`FE-D52`](#fe-d52--the-verified-product-palette-governs-ui-semantics-identity-stays-with-the-brand-book) (2026-08-19), and the artifact it calls missing is supplied by [`FE-D30`](#fe-d30--reference-is-the-source-of-truth-for-ui-screens-and-visual-styling).** Everything else stands: tokens only, no hex/px/font-size in feature code, one theme object, light and dark through `palette.mode`. The text below is preserved unchanged — it was accurate when written, and `I3` forbids editing a decision's meaning in place.

No hex, px, or font size in feature code. Tokens live in the MUI theme. **Solace brand values are not invented here.** Until open item 19 is closed, development may use MUI defaults plus **interim** colours taken from the checked-in Dynamic Metrics screens (`#003057`, `#00a89e`) labelled **unverified against the deleted DS**. Light/dark both go through the same theme object.

⚠️ The former interim pair from the Dynamic Metrics screens (`#003057`, `#00a89e`) is **superseded and must not be used**; it is the wrong navy. Superseded 2026-08-19 by `FE-D30` — see the changelog.

**Rationale.** `D7` says express the mockup as an MUI theme. The mockup was missing when this was written; `FE-D30` supplies one. Inventing a Solace palette from memory violates `I2`, and so does carrying forward a pair known to be the wrong brand values. **Present is not accepted:** this decision stays `BLOCKED` until the architect group closes open item 19.

## `FE-D6` — React Router; serializable workspace in the URL

Routes own: tenant-unaware resource ids (server still scopes), solution, instance, category (Designer / Governance / Monitoring), dashboard id, **range preset**, selected instance ids. `DM-D19` remains: the widget **schema** is serializable so Phase 2 share-by-URL needs no migration. **v1 does not put the widget document in the query string** (`FE-D21`) — proxy and browser length limits make that fail in operations.

**Rationale.** Incident hand-off without a share feature still needs a reload-safe URL for *which resource and window*. A 4 KB widget JSON is not a URL.

## `FE-D7` — Plane-split clients and OpenAPI types

`cpClient` and `dpClient` remain **two modules** with two route prefixes (`/api/cp`, `/api/dp`). In the browser they share **one origin**: the SM gateway ([`architecture.md`](../architecture.md) §3.1 composition, `FE-D19`). Independently configurable *internal* base URLs are a packaging concern behind that gateway, not two public internet URLs in `VITE_*`. Leaf components never call `fetch`. A screen that needs both planes composes in a **container hook**. Types are generated from the Console OpenAPI contract when it exists; until then, hand-written types live next to the clients and are replaced, not grown.

**Rationale.** After the SaaS split, a customer DP is not reachable from a Solace-hosted SPA. Two Vite env URLs would bake in a topology that fails REQ-003. Coarse hooks still matter so the gateway can fan out without 50 chatty calls.

## `FE-D8` — Auth adapter, tenant never from the client

v1: local auth (`D13` Keycloak is **deferred**). The adapter interface is OIDC-ready (access token, refresh, logout). **Tenant id is never taken from a query param, path, or form as authority** (`I5` / `HC2`). The UI may display the tenant the server returned.

**Rationale.** File Mesh trusted client-supplied `orgName`. Repeating that in the SPA would fail the audit the hardening backlog exists for.

## `FE-D9` — Omit unauthorized actions; disable for limits

Missing `dashboard.edit` → Save is **not shown**. Missing `metrics.forceRaw` → Raw control is **not shown**. Series-cap and incoherent-aggregation limits **disable with an inline reason** (`DM-D7`, `DM-D8`) because the operator has the verb but not the capacity.

**Rationale.** Hide-vs-disable mixed at random is how operators hunt for broken buttons. High-consequence verbs that exist but are denied must not appear as a tease.

## `FE-D10` — Typed errors; empty is not success

API errors are a closed set of codes the UI switches on (`422 SERIES_CAP_EXCEEDED`, version states, `APPLY_INDETERMINATE` when the backend exists). Widget and page **error boundaries** isolate blast radius. No-data, gap, and first-run are **named empty states**, never a blank chart (`UI-D8`, Dynamic Metrics §11.2).

**Rationale.** A string-matched error message can't drive UI branching reliably and breaks silently on backend wording changes; a closed code set can. Conflating "no data yet" with "the request failed" is how operators learn to distrust the dashboard — an unlabeled blank chart during a real outage looks identical to a quiet Tuesday.

## `FE-D11` — MUI X Data Grid Community in v1

Instance lists, field tables, and catalog tables use `@mui/x-data-grid` **Community** (MIT, verified via [MUI X licensing](https://mui.com/x/introduction/licensing/) 2026-08-16). Column pinning, excel export, and other Pro/Premium features are **out of v1** unless procurement lands a licence.

**Limit, accepted:** Community has no column pinning / Excel export (those are Pro/Premium). Column resize availability follows the Community package — do not import Pro to get it.

**Rationale.** v1's table needs (instance lists, field tables, catalog tables) are sort/filter/virtualize, all Community. Importing Pro for one screen creates a licence dependency and a bundle-size cost for the whole app; procurement is a business decision this design should not pre-empt by silently assuming a paid tier.

## `FE-D12` — Test pyramid pinned to shared fixtures

Unit: Vitest (MIT). Component: Testing Library (MIT). e2e walking skeleton: Playwright (**Apache-2.0**, not MIT). `project()` and `projectSeries()` must pass the **shared JSON fixture suites** (`UI-D15`, `DM-D20`) in the frontend CI. Visual regression is Phase 2 after open item 19 closes. Licences verified 2026-08-16 by WebFetch.

**Rationale.** `project()` and `projectSeries()` are implemented twice — TypeScript here, Java in the backend — and `UI-D15`/`DM-D20` name that as the exact place the two are most likely to silently diverge. Running one fixture suite in both CIs turns a possible silent divergence into a build failure. Visual regression waits on open item 19 because there is no stable visual baseline to regress against yet.

## `FE-D13` — Observability without `I6` leaks

The client **may** send `X-Correlation-Id`. The **server's** id in the response is authoritative for audit and support. Console logging in production is structured and **redacts** secret fields, config values marked `secret`, and raw metric dumps. Field-level diffs already mask secrets (`I6`); client logs must not undo that.

**Rationale.** A client-generated id is useful for stitching a support conversation to a request, but trusting it for audit would let a client forge or collide ids; the server's id is the one that can't be spoofed. Redaction has to be enforced at every place a secret could leak, not just the ones `I6` names explicitly — client logs are exactly the kind of surface that gets added later and forgotten.

## `FE-D14` — Static SPA, injected env, no secrets in the bundle

The console is built by Vite (MIT, verified 2026-08-16 from [vite LICENSE](https://raw.githubusercontent.com/vitejs/vite/main/LICENSE)) and served as static files by SM packaging. Runtime config is a **same-origin `config.json`** (or equivalent) fetched at boot — not `window.__SM_ENV__` inline script (`FE-D19`). Tokens: prefer httpOnly **SameSite=strict** cookies set by the gateway; CSRF protection on cookie-authenticated mutations. **Never** `localStorage` for refresh tokens.

**Rationale.** On-prem air-gap: no phone-home. Inline `window` env fights CSP. Secrets in `localStorage` were a File Mesh-class footgun.

## `FE-D15` — Strict TypeScript

`strict: true`, `noUncheckedIndexedAccess: true`. Application code does not use `any`. Generated OpenAPI / JSON Schema types are the exception that is regenerated, not edited.

**Rationale.** Generated types are the enforcement mechanism for the contract boundary between this console and the backend; `any` anywhere between the generated types and app code is a hole a payload-shape bug can pass through unnoticed. `noUncheckedIndexedAccess` matters specifically because `project()`'s output is field lookups by key from schema-driven data — an unchecked index is exactly where a missing field would otherwise surface as `undefined` deep in a component instead of at the point of access.

## `FE-D16` — ECharts only through `ChartRenderer`

No feature imports `echarts` except `shared/charts/ChartRenderer`. Theme bridge is the single MUI→ECharts mapper (`DM-D13`).

**Rationale.** Prevents a second chart path (MUI X Charts, File Mesh chart wrappers) from appearing during harvest.

## `FE-D17` — RHF holds drafts; `project()`'s `rendered` set is the save payload

React Hook Form stores Designer **draft** values, including inactive discriminator branches (`UI-D4` retention) **and virtual fields** (`UI-D24`). Licence: MIT, verified 2026-08-16 from [RHF LICENSE](https://raw.githubusercontent.com/react-hook-form/react-hook-form/master/LICENSE).

**Save, apply, diff, and config hash use the `rendered` set `project()` returns — the rendered field set *minus* every field marked `virtual` — never `getValues()`.** RHF's default `shouldUnregister: false` ([useForm](https://react-hook-form.com/docs/useform)) *retains* unmounted fields — a naive submit would send inactive branches into the MI config file. A submit built from the *visible* set instead would send **virtual** fields, which is worse: they are not merely inapplicable, they name **no config property at all**.

**Consume `rendered`; do not re-derive it.** `project()`'s output carries the `rendered` set explicitly, plus a `virtual` flag per field, and a virtual field has **`renderedPath: null`**. The console reads that set. It does **not** compute `visible.filter(f => !f.virtual)` — that is a second implementation of an exclusion rule the engine already owns, and it sits **outside** the `UI-D15` shared fixture suite (`FE-D12`), so a divergence there fails no build in either CI.

On discriminator change: re-run `project()`; `reset` **merges** so inactive-branch and virtual keys are kept. Bind-tier checks may run in RHF; semantic warnings stay `UI-D16`.

**Rationale.** Two distinct exclusions land on the same payload and neither is optional. `UI-D4` requires inactive branches retained in the draft (so switching a discriminator back doesn't lose what the operator typed), which already means the raw form state is not the payload. `UI-D24` then adds fields that are *never* written — they exist because **all 11** fields referenced by a File Mesh predicate have no `CONFIGURATION_PATH`, so without `virtual: true` gate 4 would reject every File Mesh predicate. The checked-in fixture [`../../../contracts/validation/fixtures/filemesh-ftp-solace.package.json`](../../../contracts/validation/fixtures/filemesh-ftp-solace.package.json) carries **206 fields of which 54 are virtual**. `project()` is the one place that already knows both rules; reading the payload from anywhere else recreates — at 54 phantom keys per instance — the bug this decision exists to prevent.

**Amended 2026-08-18.** The original text said *"the rendered field set"* when "rendered" could only mean "not in an inactive branch", because `UI-D24` did not yet exist. Amended in place under this document's amendment policy, not superseded (`I3`).

## `FE-D18` — Two generations: config-of-record concurrency (unblocked) and apply ordering (blocked)

The first draft of this decision treated "generation" as one thing. It is **two**, with different owners and
very different readiness — and conflating them is what made the apply half read as settled when it is not.

**Rationale.** Save-side concurrency (clause A) is optimistic locking on a row the DP already owns outright — it needs nothing that doesn't exist today. Apply-side ordering (clause B) needs guarantees — monotonic generation, idempotency, crash-safe resume — that are `B/D16` properties of an agent↔CP protocol `dp-remote` has no equivalent of. Presenting both under one name let the unblocked half's readiness quietly cover for the blocked half; splitting them is what makes clause B's block visible instead of assumed away.

### Clause A — config-of-record concurrency. **Unblocked; walking-skeleton scope.**

**Save** (writing a draft to the DP's own configuration-of-record) sends the **last-seen config generation**
(or ETag / `If-Match` — the backend names the field). `409 CONFLICT` → discard optimistic UI, refetch, show
field-level diff, operator chooses. **No silent overwrite.**

This is optimistic locking on a row in the DP's own store. It touches no remote host and is buildable as soon
as the Console API exists. The wire name is owned by the unwritten Console API; this clause binds the **client
obligation**.

### Clause B — apply / rollout ordering. **BLOCKED — do not implement.**

**Apply** and **restart** reach a remote host, and their ordering guarantees are a different mechanism
entirely: monotonic rollout generation, idempotency key, DP-persisted per-host in-flight state, bounded retry,
crash-safe resume. Those are `B/D16` properties of the **agent↔CP** protocol.
[`../architecture.md`](../architecture.md) §13 states they **do not exist for `dp-remote`** — *"until a
`dp-remote` equivalent exists that apply path is best-effort and non-idempotent"* — and §4 states directly that
*"safety properties are locus-specific and must not be assumed platform-wide … which is required v1 scope and
currently an open item."* Specifying the equivalent is [`../DECISIONS.md`](../DECISIONS.md)
**open item 1, a ratification blocker**, and **the walking skeleton runs on `dp-remote`**.

So this clause has no backend counterpart for v1's first slice. It is recorded here as the client obligation to
implement **once open item 1 lands**, not as something to wire now.

### Interim obligation while clause B is blocked

The UI must not render a `dp-remote` apply as **succeeded** or **failed** on the strength of the HTTP outcome,
because a timeout there is genuinely indeterminate — the write may have landed on the host. Therefore, on the
`dp-remote` locus:

- Apply outcome is **`APPLY_INDETERMINATE` by default**, resolved by **observed state** (staged generation +
  per-replica `restart-observed`), never by the response code.
- Retry is **operator-initiated after confirming actual**, never automatic and never offered as a plain
  "try again" — without an idempotency key a retry is a **second write**.
- TanStack Query must **not** optimistic-update apply/restart (`FE-D20`).

This is objective #3 (honest telemetry, design §1) applied to apply.

**Limit, accepted:** clause A gives no protection against a *concurrent apply*. Two operators who each hold the
current generation can both apply; the last remote write wins, silently, until open item 1 closes. The UI cannot
detect this and must not imply that it can.

## `FE-D19` — Browser → gateway only

v1 on-prem: the SPA, `/api/cp/*`, and `/api/dp/*` are **one origin** (SM packaging reverse-proxies). After a SaaS split the browser still calls **one console origin**; that origin fans out to CP and customer DP. The SPA does **not** receive a private DP URL.

Boot config is same-origin `config.json` (gateway public prefix, idle timeout, build channel). Not `window.__SM_ENV__`.

**Rationale.** After a SaaS split, a customer's DP is not reachable from a Solace-hosted SPA at all — a design that ever gave the browser a DP URL directly would need a rewrite at that boundary rather than a routing change. One gateway origin keeps the split a backend-only change (`FE-D7`). `config.json` over an inline `window` script avoids fighting CSP and keeps runtime config out of the built bundle, where it would be cached and hard to rotate.

## `FE-D20` — Fail-closed Query defaults

Global QueryClient:

- `retry`: `5xx` and network only; **never** `401` / `403` / `422`
- Metrics and dashboard queries: `refetchOnWindowFocus: false`; `staleTime` ≥ served `bucketSeconds` when known
- `refetchOnReconnect` allowed, bounded
- Apply / restart / export: **no** optimistic cache writes
- Widget queries `enabled` only when visible (`IntersectionObserver`)

**Rationale.** Retrying a `422` just resends a request the server already refused for a reason that won't change. Auto-refetching apply/restart on window focus, or optimistically showing success, risks turning a real ambiguity (`FE-D18` clause B: `dp-remote` has no idempotency key) into an actual second write — the defaults have to fail closed *before* that protocol exists, not after something goes wrong once it doesn't.

## `FE-D21` — Widget documents stay off the URL

Reload-safe explore state: `sessionStorage` keyed by a short id **or** a server-side draft (preferred once the API exists). The URL carries `exploreDraft`, instance ids, dashboard id, range preset. Phase 2 share-by-URL (`DM-D19`) mints a **server id**, not a 4 KB query string.

**Rationale.** An ad-hoc widget document is easily kilobytes of JSON; proxies and browsers both impose URL length limits well below that in practice, so putting the document itself in the query string fails in operations long before it fails in a demo. Keeping only ids and the range preset in the URL is what makes the URL reload-safe without making it fragile. **Limit, accepted:** `sessionStorage` is per-tab, so an unsaved Explore link shared with another operator does not carry the sender's draft — only a saved widget or Phase 2's server-minted share id does.

## `FE-D22` — RBAC overlay after `project()`

```
viewModel = project(package, selection, draft, observed)     // UI-D15 — no auth
chrome    = overlayRbac(viewModel, permissionSet)            // omit / disable
```

Fixtures for `project()` stay user-free. `VERSION_*` blocks belong in `project()` / observed state, not in the permission set.

**Rationale.** `project()`'s fixture suite (`UI-D15`) is shared with the Java backend, which has no browser session to evaluate RBAC against — keeping `project()` auth-free is what keeps that suite portable. Layering RBAC strictly *after* also bounds what it can do to omit/disable only: it can never expand what the engine already computed, which is what keeps frontend RBAC honestly "cosmetic" (§15) rather than a second, divergent source of permission logic.

## `FE-D23` — No resolved secret in the browser, **including inside `yaml`**

The SPA shows `secretRef` or a masked placeholder. Changing a secret is **write-new-value**, never
read-old-value. There is no reveal that fetches the resolved secret. Copy follows package `disableCopy`.

**The view model makes this structural, not a convention.** A secret field's `value` in `project()`'s output is a
**discriminated union of `secretRef` and `secretUnset`** — there is **no arm capable of carrying a resolved
value**, so "the browser must not receive one" is enforced by the type, not by reviewer discipline. `masked` is
therefore a **rendering hint only**; treating it as the control would invert this decision into exactly the
hide-what-we-already-hold pattern the rationale below rejects. A `SecretField` that ever needs a third arm is a
signal that `I6` has been breached upstream, not a UI requirement.

**Rationale.** A reveal-the-value control means the browser holds the credential at least momentarily, which is the exact exposure `I6` exists to close — masking a value the browser already has is not a security boundary, only hiding one that it never receives is. Write-new-value sidesteps the problem entirely: the UI never needs the old value to let the operator replace it.

### The `yaml` extension

`UI-D23` §3.7 item 3 accepts that a password pasted inside a `type: yaml` field **cannot be masked as a field**,
and therefore only **warns** the operator that it will be stored in clear. That is Subsystem A's decision about
**storage**, and this document does not overturn it (`I4`).

It does not, however, license this console to **render** it. `I6` names *"field-level diffs"* and *"exports"*
explicitly, so a `DiffView` or a CSV/JSON export that prints a yaml-embedded credential in clear is a leak on a
**frontend** surface — the one class of surface this document does own.

**The obligation is on the payload, not on the browser.** Diff, export and history responses served to the
console must have yaml-embedded secret-looking content redacted **server-side**, using the same scanner
`UI-D23` §3.7 item 3 already requires for the authoring warning. **A client-side masker is explicitly
rejected**: it would mean the browser holds the credential and merely hides it, which is precisely the pattern
this decision's first paragraph forbids for `secretRef`. Masking is not a security boundary; not sending is.

### Gate

**No `type: yaml` field ships in this console until the redacted-payload obligation is met.**

Cache declares no `yaml` field — `type: yaml` exists because **File Mesh has 12** ([`../dynamic-ui/design.md`](../dynamic-ui/design.md) §3.7).
File Mesh is **Phase 2** (design §24). This therefore gates **Phase 2**, and does **not** block ratifying this
sub-spec or building the v1 Cache slice. Tracked as design §26 item 14.

**Limit, accepted:** the scanner is **heuristic**. A credential that does not look like one is not redacted.
This reduces the leak surface; it does not close it, and must never be documented or demoed as closing it.

## `FE-D24` — Idle lock; no production auth mock

Idle timeout from `config.json` (default 30 minutes of no user input) → drop in-memory tokens, return to `/login`. Production builds **must not** compile the Phase 0 auth mock (`import.meta.env.DEV` or equivalent). CSRF: cookie-authenticated mutations require a server-issued token or equivalent double-submit; `SameSite=strict`.

**Rationale.** An idle authenticated session on a shared operator workstation is a standing credential exposure with no offsetting benefit once the operator has stepped away. Shipping the development auth mock into a production build would silently defeat every other control in this document (`FE-D8`, `FE-D14`) behind a build-config mistake rather than a deliberate choice — `import.meta.env.DEV`-gating makes that mistake a build failure instead of a shipped one.

## `FE-D25` — Idle-lock draft preservation

Designer drafts survive an idle lock in per-tab `sessionStorage`, keyed by instance, excluding any secret intent, restored behind an explicit banner with a discard action.

**Rationale.** Login copy promises in-progress edits survive idle. A promise the console cannot keep is worse than no promise. `sessionStorage` (not `localStorage`) so the draft dies with the tab; secrets excluded (`I6`); a banner rather than a silent restore so the operator knows the form is not the server's state.

**Rejected:** `localStorage` (survives the tab; wrong lifetime). Silent restore (operator cannot tell draft from server state). Dropping the copy (throws away a real protection for a long form).

## `FE-D26` — Desktop-only ≥ 1280 px — SUPERSEDED

The console is desktop-only at ≥ 1280 px. No phone or tablet layout — an explicit non-goal.

**Rationale (historical).** `dynamic-metrics` §18 stated this as a non-goal. The reference screens assume 1280 px. Recorded as a narrowing of `design.md` §8.1.

**Superseded by `FE-D29`.** The rationale overlooked that WCAG 2.2 AA SC 1.4.10 makes 320 px reflow a requirement once the product commits to AA (`FE-D` a11y bar). Kept on the register (`I3`); do not implement.

## `FE-D27` — A 403 never names the missing permission

It states the action is not permitted, shows the server correlation id, and offers a route back.

**Rationale.** Naming the verb tells an unauthorised caller which permission to acquire. Recovery is identical either way: ask an administrator, quote the correlation id. If security review later permits naming it, that is a copy change in one component.

## `FE-D28` — Browser support matrix

Supported browsers are evergreen Chrome, Edge, Firefox and Safari, latest two versions, verified in CI.

**Rationale.** `dynamic-metrics` §18 flagged the absence of a matrix as an assumption. CI needs a target list.

## `FE-D29` — Responsive to 320 CSS px (supersedes `FE-D26`)

The console is responsive across mobile, tablet and desktop, reflowing to 320 CSS px. Breakpoints: `xs` 0-floor in MUI / supported floor 320 · `sm` 600 · `md` 905 · `lg` 1240 · `xl` 1560. Data tables and persistent toolbars use SC 1.4.10's exceptions and scroll on one axis **inside their own container**, never the page body.

**Rationale.** The console commits to WCAG 2.2 **AA**. SC 1.4.10 Reflow is Level AA (Understanding: https://www.w3.org/WAI/WCAG22/Understanding/reflow.html). `FE-D26`'s ≥1280 floor made that target unreachable. `design.md` §8.1 already said desktop-first, not desktop-only. Cross-component: this supersedes `dynamic-metrics` §18's non-goal and needs architect-group sign-off.

**Rejected:** Claiming AA while shipping `min-width: 1280px`. Inventing a second "mobile app" IA. Copying the prototype shell's min-width into production.

---

## `FE-D30` — `reference/` is the source of truth for UI screens and visual styling

The checked-in prototype at [`reference/`](reference/README.md) is the authority for the console's
visual design. Implementation matches its **layout, spacing, component structure, navigation,
tables, forms, dialogs and visual hierarchy**, and reuses its **full colour palette** — primary,
backgrounds, text, status, neutrals, accents, borders and dark mode — together with its
typography, elevation and motion. No alternative visual style is introduced unless a new
requirement forces it, and such a departure is recorded as a decision.

The token authority chain is
`reference/_ds/…agent-mesh…/colors_and_type.css` → `reference/theme.css` → `src/shared/theme/tokens.css`.
Values are transcribed in implementation plan **Appendix B**.

**Two boundaries.**

1. **Responsiveness is out of scope.** The prototype is a fixed 1280 px desktop drawing —
   `.shell` is `min-width: 1280px`, its viewport meta is `width=1280`, and its only media query is
   `prefers-reduced-motion`. `FE-D29`, `G16` and Phase R (plan tasks `105`–`110`) continue to
   govern reflow to 320 CSS px, unchanged. Narrower layouts are **derived** from these components
   and tokens; `reference/` is not consulted for them and does not override `FE-D29`.
2. **This is not acceptance.** `FE-D5` keeps brand paint **BLOCKED** and open item 19 stays open.
   `FE-D30` settles *which artefact development works from*; open item 19 asks *whether the
   architect group accepts it as brand-final*. Those are different questions and only the first is
   answered here (`I4`). `PROVENANCE.md` (plan task `FE-002`) must keep saying so, and UX-accept
   remains blocked.

**Not a stack decision, and not a reopening of one.** The prototype is plain CSS on the Solace Agent Mesh
design system, which is the system the deleted handoff paired with Tailwind v4 + Radix — the unresolved
conflict `DECISIONS.md` records against `D7`. Adopting its **paint** through an MUI theme touches neither
`D7`'s stack clause nor `FE-D4`'s *"No Tailwind, no Radix, no shadcn, no parallel token set."* The
prototype's own CSS ships nowhere. `FE-D4` stands unchanged, and its reading of the handoff as a **visual-
reference gap rather than a licence to run two stacks** is what this decision implements.

**Consumed as a specification, never as code.** The artifact is React 18 UMD with in-browser Babel,
`window.*` globals, a hand-rolled hash router and `localStorage` auth. Nothing under `reference/` is built,
linted, type-checked or imported. Its mock data shapes are **illustrative, not a contract** — the Console
OpenAPI, when it exists, is the contract, and payloads are expected to change without invalidating the
visual spec.

⚠️ **This decision is overridden, line by line, by [`reference/ERRATA.md`](reference/ERRATA.md).** "The
reference wins" is only safe if the places the reference is *wrong* are written down — otherwise it launders
errors into the product. Twelve entries are recorded there, two of them critical: the mockup makes SM
**deploy artifacts** (**E1**), and it **misstates LOCKED `D10`**'s verbs (**E2**). Read the errata before
transcribing anything.

**Transcribe, do not paraphrase.** State labels, tips and screen copy were reviewed and approved as written;
they are part of the contract with the operator. The vocabulary is transcribed verbatim at
[`reference/STATE-VOCABULARY.md`](reference/STATE-VOCABULARY.md) — **eight** tone/label tables plus one
ordered ladder, **35 entries across five files**. Reconstructing it from a derived document instead of from
source has already been attempted once and was wrong in membership, tones and labels (`L16`); the first
transcription then missed two tables itself, which is why the counts are recorded and checkable.

**Rationale.** Before this, the frontend had no in-repo visual reference at all — open item 19,
and the reason `FE-D5` blocked paint. A design bundle supplied one, but it landed under
`.gitignore` (as `sm-v2/`, swept into a rule written for rendered PNGs) with its design-system
dependency stripped out, so five tokens resolved to nothing and the folder could not render. An
untracked, non-rendering folder cannot be anyone's source of truth. Checking it in, rejoining its
`_ds/` bundle and naming its authority converts open item 19 from *"the artefact does not exist"*
into *"accept this artefact"* — a materially cheaper decision — without pre-empting the answer.

**Rejected:** Treating the folder as authoritative while leaving it untracked. Extending its
authority over responsive layout, which would silently reverse `FE-D29` and forfeit WCAG 2.2 AA
SC 1.4.10. Treating it as acceptance of open item 19, which is not this document's to give.
Re-transcribing it into prose instead of committing it, which guarantees drift.

**Flagged, not resolved** (see [`reference/README.md`](reference/README.md)): the bundle carries a
second design system, the Solace Brand Book v2.9, which agrees on brand green `#00C895` but
differs on the dark blue (`#093B5F` / `#03213B` vs Agent Mesh `#015B82` / `#03223B`). The
prototype links only the Agent Mesh file, so under this decision the Agent Mesh chain wins. Also
unresolved: green-vs-marine for primary buttons (`reference/` uses marine, so marine ships), and
which categorical chart palette is adopted — plan task `097` must validate whichever it is at
≥ 3:1 against both surfaces.

---

## `FE-D31` — A package serves several versions; an instance is configured against exactly one

`SolutionPackage.packageVersion` is a **non-empty ascending array**, and a component or field declares which of those versions it belongs to via its own optional `version` array. `InstanceRow.packageVersion` names the single version that instance is configured against, and `project()` filters components and fields against it before any default is applied.

**Rationale.** A package evolves without forking into a file per version. Without a version dimension the Designer shows every declared field to every instance, including fields that do not exist for the schema that instance runs. Recorded from plan `002` (`FE-118`), which built it.

**`version` absent means "every version the package supports."** Not `[]`. That keeps a third-party package written before versioning existed valid, and keeps all existing contract fixtures valid. An **empty** array belongs to no version and would silently never render, so the loader rejects it — an empty array is far more likely to be a lost field than a retired one.

**Rejected:** a scalar `packageVersion` with one file per version (forks the package, and an instance's version then names a *file* rather than a schema). Defaulting silently to "latest" when a package serves several — a wrongly-defaulted package version is invisible and is precisely the class of bug this decision exists to prevent, so selection is **required** whenever more than one version is served, and preselected only when exactly one is.

## `FE-D32` — Authored version history in the shipped example packages is demo data, not MI history

`contracts/packages/cache/package.json` declares versions `[6, 7]` with a documented set of fields scoped to one or the other. **None of this is derived from the Cache MI.** It is authored so the console has a package that exercises both directions of a version change.

**Rationale.** The version machinery (`FE-D31`) is untestable without a package that actually differs between versions, and `I1` forbids requiring anything of the MI to get one. Stating the provenance in `contracts/packages/README.md` stops a reader treating the file as a record of what Cache shipped. Recorded from plan `002`; extended by `FE-121`, which found the package could express *added in v7* but not *removed in v7*, and added a v6-only field so both directions have a fixture.

**Rejected:** deriving version history from the Cache repository (out of scope, and `I1`); leaving the package single-version and asserting the filter only in synthetic fixtures (the screen would then never be exercised against a real package).

## `FE-D33` — The Designer read carries the package; the browser projects (resolves plan §16 Q19)

`ConfigRepository.loadDesigner()` returns the **Solution Package**, the instance's `packageVersion`, the saved values, the observed state and the config generation. The browser runs `project()` over them. It does **not** return a pre-projected view model.

**Rationale.** `UI-D15` requires the projection engine to exist in both languages either way, so this decides the data flow, not whether the engine is shared. Two things force this reading. First, a `when:` predicate must re-evaluate as the operator changes a discriminator (`UI-D2`, `project()`'s obligation 4); a pre-projected snapshot cannot, and round-tripping to the server per keystroke is not a design, it is a workaround. Second, every Designer component built in Phase 4 already takes contract types (`ViewSection`, `ViewStatus`, `SolutionPackage`, `DraftValues`) — the pre-projected shape had exactly one consumer, the fixture handler. The implementation plan's own §16 Q19 recommended this reading; `FE-122` implements it.

**Scope.** This binds the **frontend data flow only.** The wire *encoding* — how a package is serialised on that response — remains the backend sub-spec's to write, and `Q8` (the generation field's name) and `Q9` (the `409` body) are untouched.

**Rejected:** the server returning a projected view model (cannot re-evaluate predicates client-side); returning **both** package and projection (two sources of truth for one screen, which is the condition that let `FE-121`'s version defect exist unnoticed).

## `FE-D34` — `shared` may import `contracts`

`eslint.config.js`'s boundaries policy grants `shared → contracts`, alongside the `app → contracts` and `feature → contracts` policies that already existed. `shared → feature` remains disallowed, with a lint fixture proving it.

**Rationale.** `contracts/` is a leaf: its own lint block forbids it from importing React, the DOM or `@shared/*`, so the edge creates no cycle and no layering inversion. Without it the fixture layer cannot read `contracts/packages/*.json`, which is why a hand-written 1029-line duplicate of a package's projected output survived in `shared/testdoubles/data/designer.ts` for two phases after the engine that makes it derivable existed. `reports/task-113-report.md` named this and the alternative — moving the designer handler out of the fixture layer — and left the choice open; this takes the first.

**Rejected:** moving the Designer's MSW handler to `app/dev` (the default handler set is what every unit suite runs against, so the Designer's handler would live apart from its siblings, and the duplicate literal would survive as a second source of truth).

## `FE-D35` — The Designer renders one component instance per declared type; `one-of-many` composition is not invented

`deriveComponentSelection(pkg, packageVersion)` returns one `ComponentSelectionEntry` per component the selected version declares, with `instanceId = type`. A component declaring `cardinality: 'one-of-many'` therefore renders **once**.

**Rationale.** `UI-D1` says a package declares component *types* and an instance composes named instances of them — but nothing in this console produces that composition data. `FE-115`'s declare form asks for identity only and deliberately refuses to ask for configuration, and §16 Q10 (the declare contract) is still open. Rendering a fabricated composition would invent instance configuration the console has no authority over. The degenerate `exactly-one` case is every package v1 can meaningfully render, including both shipped examples.

**Degradation is stated, not silent:** the module says so in its header, so the limit is discoverable where someone would look for it rather than only in this register.

**Rejected:** synthesising N instances from a heuristic; asking the operator to compose components in the Designer (that is authoring a topology, not configuring an instance, and no contract backs it).

## `FE-D36` — The Designer has no Cancel; a draft is discarded by leaving

The Designer's action bar offers **Save draft** and **Apply**. There is no Cancel, Revert or Discard control.

**Rationale.** A draft lives only in the browser until saved, so "cancel" is a reload. The reference screen (`reference/app/designer.jsx`, the `dsgn-bar` block) offers exactly two actions, and UI §A.8 specifies no third. A discard affordance would need a confirmation of its own — discarding unsaved work is destructive — and inventing both the control and its confirmation is worse than the omission. Guarding *navigation* away from an unsaved draft is a separate, real question and is recorded as a follow-up rather than answered here.

**Rejected:** a Cancel button that reloads (indistinguishable from the browser's own reload, and reads as if it reverts something on the server); an unguarded Discard (destructive with no confirm).

## `FE-D37` — The instance's package version is threaded, never re-derived

Every consumer that needs to know which fields exist takes the version from one place — `project()`'s fifth argument, and `inVersion()` for anything that must ask the same question outside it. No module restates the filtering rule.

**Rationale.** `project()`'s `packageVersion` parameter is **optional, and omitting it means no filtering at all** — the behaviour every caller had before `FE-D31`, preserved so existing fixtures stayed valid. That default is safe for a fixture and wrong for a screen: `FE-121` found six callers written before versioning that still omitted it, including the one that builds the **save payload**. A v6 instance would have rendered, seeded and saved a v7-only field. An optional parameter whose omission is silently permissive needs a single threading discipline, not per-call vigilance.

**Obligation.** The version reaches the render, the defaults seeded into the draft, and the payload — and is asserted at all three levels, in **both** directions (a field added in a later version and a field removed in one). A suite checking only the *added* direction passes against an implementation that filters nothing on the older side.

## `FE-D38` — New local users get an admin-set temporary credential; the first-admin bootstrap stays unbuilt

The Add-user flow (`FE-094`, `AddUserDialog`) sends a `credentialPath` of `temp` — **an admin-set temporary credential the user is forced to change on first sign-in**. The two alternatives §16 Q5 names are shown in the dialog so the choice is visible, but do not ship as working paths: **emailed invitation** is rendered disabled because it "introduces an email capability this console does not have"; **out-of-band handoff** is selectable and passes `credentialPath: 'oob'`, but carries no distinct mechanism beyond the admin communicating the credential themselves. `credentialPath` is a three-value enum on the request so revisiting the decision does not move the request shape; the backend contract for `POST /admin/users` is still `CONTRACT: TBD`.

**The first-administrator bootstrap is deliberately not built.** `UsersView` states, as a standing `neutral` note, that how the first admin comes to exist on a fresh deployment is unspecified and product- and backend-owned. `AdminRepository` keeps `bootstrapFirstAdmin()` absent (its header says so). No installer-secret form is rendered — the reference's `BootstrapView` is explicitly labelled a "load-bearing invention … needs a decision before anyone builds it", so matching the reference's *intent* means leaving it out.

**Rationale.** `FE-094` is the plan's one `Ready: DECISION` task — Q5's credential path had to be picked "by decision, not by default". The temporary-credential path is the only one with **no external dependency**: it works in the air-gapped on-prem deployment `REQ-003` requires, where an email capability does not exist. The checked-in reference (`reference/app/admin.jsx`, `AddUserModal`) already marks it "the chosen option", and `FE-D30` makes the reference the source of truth for screens. Recorded from execution of `FE-094`; the developer confirmed the path before it was built.

**This does not settle Q5.** Q5 is owned by product and backend and remains open (`I4`): this decision binds only the **frontend surface** — which credential control the Add-user dialog offers, and that no bootstrap UI is invented. Ratification of the mechanism, the `POST /admin/users` contract, and the bootstrap flow are still theirs.

**Rejected:** rendering only the temporary-credential option and dropping the other two (diverges from the reference, which shows all three and explains why two are unavailable); building the installer-secret `BootstrapView` (invents a flow Q5 has not decided, against `I4` and the reference's own caveat); an optimistic local toggle on deactivate to make the stateless fixture "stick" (no optimistic mutation anywhere — `FE-D20`, `shared/api/queryClient.ts`).

---

## `FE-D39` — The palette is per-mode and contrast-derived, and CI is the arbiter

`theme.palette.chart.categorical` and `theme.palette.tone` become **functions of `palette.mode`**, their values derived by moving a DS token's lightness — hue and saturation held — until it clears a stated WCAG ratio against the surfaces of that mode. The focus indicator becomes a **two-band ring**. `npm run verify:palette` asserts all of it in both themes and is a required CI job; a failure is a build failure.

**Rationale.** Design §18 recorded contrast as something that "cannot be signed off until open item 19 tokens exist". The tokens exist, so `FE-097` turned the sign-off into a check — and the check found six real defects in the palette that shipped through phases 0–5:

| Defect | Measured | Required |
|---|---|---|
| `accent.sky` as a light-theme series colour | 1.58:1 | 3:1 (SC 1.4.11) |
| `accent.indigo` as a dark-theme series colour | 1.87:1 | 3:1 |
| Focus ring, brand green on white | 2.17:1 | 3:1 |
| `tone.warn` chip label on its own fill | 2.12:1 | 4:1 → 4.5:1 (SC 1.4.3) |
| Six of seven tone chips as label text | 2.12–3.30:1 | 4.5:1 |
| `indigo`/`purple` under deuteranopia | ΔE2000 2.26 | separable |

None of these was a near miss. A single fixed ten-colour list **cannot** serve both a near-white and a near-navy surface — that is arithmetic, not taste — so the list has to be per mode. The tone table had the same problem in a sharper form: `tone.main` is read both as chip-label text on a tinted fill *and* as bare text on the page (`StatStrip`, `LogViewer`, `SectionNav`), so one value cannot satisfy both modes. The old table also shipped the *light* tints into dark mode, where a chip was a near-white pill on a navy page — legible only by accident.

**The values are baked literals, not computed at theme-build time.** Clamping against the live surface inside `createPalette` would be more elegant and would make the gate **vacuous**: the check would assert a property the code had just forced to be true. Baked values can regress; that is what makes the gate worth running. Each literal names its source token and resulting ratio inline in [`palette.ts`](../../../frontend/src/shared/theme/palette.ts).

**The colour-blindness claim is bounded, and says so.** Ten hues drawn from a fourteen-token accent set cannot all be separable under dichromacy — `indigo`/`purple` and `purple`/`magenta` sit at ΔE2000 ≈ 2.3 under deuteranopia in *any* ordering. Rather than assert a guarantee the palette cannot keep, the categorical list is ordered maximin and publishes `chart.cvdSafeCount = 6`: the first six entries hold ΔE2000 ≥ 8.8 across protan, deutan and tritan in both themes, and past six the chart leans on its legend and data table. The gate fails if someone raises that number without finding hues to back it, and equally if someone lowers it to make a bad palette pass.

**Two bands for the focus ring, because one colour cannot do it.** No single value in the token set clears 3:1 on both a white and a navy page. Brand green (§B.2's requirement) is 2.17:1 on white; primary marine is 1.93:1 on dark paper. The ring is therefore green 0–2px out with navy 2–4px out: green stays the band nearest the control so the indicator still reads as brand, navy carries the light theme at 15.24:1, green carries the dark theme at 7.48:1, and the bands separate from each other at 7.48:1 in both. A transparent `outline` sits under them as the forced-colors-mode fallback.

**This does not settle open item 19, and does not touch `FE-D5`'s block on brand paint.** No new hue is introduced: every value traces to a token in `tokens.ts` with only its lightness moved. What changed is legibility, which is not the architect group's question to answer — a console whose focus ring fails SC 1.4.11 is defective under any brand decision they eventually take. If open item 19 replaces the token set, this decision's *method* survives and its literals are re-derived; the gate is what makes that re-derivation safe.

**Obligation.** The contrast maths carries its own unit tests ([`contrast.test.ts`](../../../frontend/src/shared/theme/contrast.test.ts)) against independently known answers — black-on-white at exactly 21:1, `#767676` on white at the canonical 4.5:1 boundary. A gate whose instrument is unverified proves nothing. The CVD threshold is calibrated against the specific simulation pipeline in `contrast.ts`; the two move together or not at all.

**Rejected:** a mode-dependent *single*-colour focus ring (marine on light, green on dark — simpler, but drops brand green from the light theme against reference §B.2); clamping colours at runtime (makes the gate vacuous, above); cutting the categorical palette to the six CVD-safe hues (a seven-series chart would then repeat a colour, which is worse than a documented boundary); shipping the gate with the six failures waived (the waiver list becomes the thing people append to, and the console keeps a focus ring that fails AA); holding `text.disabled` to 4.5:1 (SC 1.4.3 exempts inactive components, and meeting it would make disabled text indistinguishable from enabled).

---

## `FE-D40` — An e2e journey steers the session through DEV-only URL overrides; the fixture stays coherent

`FE-099`'s journeys need session states no fixture *record* can express. Three mechanisms, in order of preference, and the order matters:

1. **Real interleaving.** Journey 2's `409` is produced by a genuine second actor: the save handler now holds a per-instance generation, and a save whose generation is no longer current is rejected. A conflict is not a property of a record, it is what happens when two actors interleave — so it is reproduced, not flagged.
2. **Fixture coherence.** Journey 3's gap comes from the fixture agreeing with itself: an instance whose health rollup says `not_responding` now defaults to the `gaps` series scenario. It previously reported a dead endpoint alongside a perfectly healthy line, which makes §A.13.4's stated workflow — *"red badge → open the graphs → no data confirms it is genuinely down"* — prove the opposite of what it says.
3. **A DEV-only URL override**, last resort, for what neither of the above can reach: `?e2e_grants=` and `?e2e_idle_seconds=` in [`shared/testdoubles/e2eOverrides.ts`](../../../frontend/src/shared/testdoubles/e2eOverrides.ts).

**Rationale.** The alternatives were worse. A test-only global set through `addInitScript` hides the journey's premise inside the test file and makes the scenario unreproducible by hand; a URL a human can paste states it. Driving `config.json` from Playwright does not work at all — MSW's service worker sits between `page.route` and that fetch. And a `superAdmin` flag or a 30-minute idle timeout is not something any fixture *row* can carry.

**The production-safety rule is the caller's, not the callee's.** `readE2EOverrides` guards itself with `import.meta.env.DEV`, and that is **not sufficient**: a build with the guard only in the callee still shipped the reader, reduced by dead-code elimination to a stub returning `undefined` — correct at runtime, present in the bundle. Every call site therefore carries its own literal `if (import.meta.env.DEV)`, which Vite folds to `false` and drops the import with it. Verified against a real `npm run build`: `e2e_grants`, `e2e_idle_seconds`, `e2eOverrides`, `readE2EOverrides`, `idleSeconds` and `devMockAdapter` are all absent from `dist`. `FE-101` will assert this.

**Two journeys are narrower than the plan's line for them, because the console is.** Recorded here so the gap is not mistaken for an oversight:

- **Journey 6's "with the verb absent, the control is omitted" is not testable, by design.** `overlayRbac` is a documented no-op and `permissions.ts`'s **C12** forbids gating a v1 feature on a non-`grantable` verb — only `target.use` and `instance.bindTarget` are grantable, so any such gate would deny every principal. Verified: a session with no permissions and `superAdmin: false` changes nothing anywhere in the console. Rather than invent a gate to have something to assert, the journey **pins C12 itself** — a verbless session must still reach every v1 control — which is the regression this codebase can actually suffer.
- **Journey 4 stops at the save.** Widget persistence does not exist (§16 **Q15**); the save dialog says so itself. Verified during execution: after a successful save the dashboard still shows only its `package`-layer widgets, by reload *and* by in-app navigation. The journey asserts the path that exists and leaves dashboard arrival to whatever task closes Q15.

⚠️ **A UI over-claim was found and is left standing.** The save confirmation reads *"«title» was added to this instance's dashboard"* when nothing was added. Changing that copy is not `FE-099`'s to make — it belongs with Q15 — but it should not be discovered twice.

**`e2eOverrides.ts` lives in `shared/testdoubles/`, not `app/dev/`,** because `shared/auth/devMockAdapter.ts` needs it and `shared` may import only `contracts` (`eslint.config.js`).

**Rejected:** a dedicated "conflicting instance" fixture for journey 2 (a conflict is an interleaving, not a record); `addInitScript` globals (hides the premise); building widget persistence to satisfy journey 4 (that is Q15's contract question, not a test's); inventing a verb gate to satisfy journey 6 (against C12, and would deny every principal in production).

---

## `FE-D41` — One CSP, written in `index.html`, with every relaxation recorded — and ECharts needs no `unsafe-eval`

Design §21 requires `default-src 'self'` and §26 item 10 left open *"CSP vs ECharts — recorded exception if `unsafe-eval` is required"*. `FE-100` closes both.

**The policy is a string in [`frontend/index.html`](../../../frontend/index.html)** — one place, human-readable, shipped verbatim:

```
default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self';
font-src 'self'; connect-src 'self'; base-uri 'self'; form-action 'self';
object-src 'none'; frame-src 'none'; worker-src 'none'
```

**Item 10 is answered: ECharts does not require `unsafe-eval`.** Verified two ways against `echarts@6.1.0`, not assumed: the production bundle contains zero `eval(` and zero `new Function(` (echarts' single `new Function` is a `JSON.parse` fallback that minification drops), and [`e2e/csp.spec.ts`](../../../frontend/e2e/csp.spec.ts) renders a real chart from the real built echarts chunk on a page carrying this exact policy, with zero violations. No exception is taken, and `csp-exceptions.json` records that non-need so the question is not re-opened from memory.

**One relaxation exists, and it is recorded, not silent: `style-src 'unsafe-inline'`.** MUI (`D7` 🔒, `FE-D4`) styles through Emotion, which inserts `<style>` **elements** at runtime; measured, not assumed — without it the production build raises `style-src-elem` violations from the MUI chunk on first paint. The two strict alternatives both need a server that renders the document (a per-response nonce for Emotion's cache; hashes are impossible for runtime-generated rules), and this console ships as a static SPA with SM packaging still unwritten (design §21). `script-src` keeps neither `'unsafe-inline'` nor `'unsafe-eval'`, so injected CSS cannot execute. **Follow-up recorded on the exception itself:** when SM packaging gains a document-serving layer, emit a nonce and delete the entry.

**The general rule is the point, not this one exception.** [`csp-exceptions.json`](../../../frontend/csp-exceptions.json) is a registry, and `npm run verify:csp` fails the build when *any* source that is not `'self'`/`'none'` lacks an entry with a reason, an `FE-D*` id and a follow-up — and equally when an entry outlives the source it justified. The task's *"not a silent relaxation"* therefore holds for every future directive, not only for ECharts.

**Delivery is `<meta>` today, a header tomorrow.** A static SPA has no response-header layer of its own. SM packaging **must** serve the identical policy as a `Content-Security-Policy` header and add `frame-ancestors 'none'`, which a `<meta>` delivery ignores — clickjacking protection is the one directive this seam cannot carry. Recorded in [`frontend/README.md`](../../../frontend/README.md) for whoever builds `runtime/`.

**The dev server is served the policy-free document** (a build-only `<meta>`, stripped by a `vite.config.ts` plugin), because Vite's inline React-Refresh preamble and `ws:` HMR would otherwise force `'unsafe-inline'` and `ws:` into the shipped string. The delta is removed from the build that never ships rather than added to the one that does; [`e2e/csp-screens.spec.ts`](../../../frontend/e2e/csp-screens.spec.ts) re-applies the shipped policy to the dev document — plus exactly three named dev allowances, derived from that same string — so all twelve real screens, charts and grids included, are still walked under it.

**Rationale.** An air-gapped install has no egress, so a third-party request is a broken deployment, not a slow one (`REQ-003`); and a policy nothing tests is a policy that breaks a screen after release. Both halves are therefore gates: a static one over the built bundle, and a runtime one that fails on a single `securitypolicyviolation` or a single request leaving the origin. Both harnesses run a negative control first — a deliberately forbidden image must be blocked and recorded — because a violation listener that never fires makes every other assertion pass by doing nothing.

**Rejected:** a looser policy shared with dev (makes the string untrue of the artefact that ships); generating the policy from a config module (a second place to read, and the shipped HTML would no longer be the source of truth); failing the build on every external origin string in the bundle (framework error messages carry documentation URLs — the runtime assertion is what proves no request is made); `report-only` delivery (a policy that reports is a policy that is not enforced, and nothing in this repo collects the reports).

---

## `FE-D42` — The logger's field type carries only primitives; a payload is a compile error

`I6` says a secret must never reach a log, and design §20 forbids logging a config value, a resolved `secretRef` or a full metric payload. `FE-102` implements that as a **type**, not a redactor:

```ts
export type LogValue = string | number | boolean | null | undefined;
export type LogFields = Readonly<Record<string, LogValue>>;
```

`logger.debug/info/warn/error(event, fields?)` has no overload accepting an object, an `Error` or an array. `logger.error('save failed', { values })`, `{ error }` and `{ series }` therefore do not compile — the largest class of leak is unreachable rather than policed. A caller that needs an error's message narrows it (`error instanceof Error ? error.message : String(error)`) at the call site, which is also where the decision to drop the `cause` chain belongs.

**Three layers, in order of strength, each proven by the only test that can prove it.** The type (`@ts-expect-error` assertions — widen `LogValue` and `npm run typecheck` fails). Then `NO_SECRET_LOG_FIELD`, for the one gap a type cannot see: a primitive under a secret-shaped *name* (`{ password }`), rejected at the call site with a fixture in `lint:fixtures:assert`. Then runtime redaction, for the one gap lint cannot see: a key assembled at runtime (`{ [field]: v }`).

**Redaction over-redacts deliberately.** `secret|password|credential|token|apikey|…` matches as a substring, so a field named `secretRef` is masked even though `I6` permits logging a *reference*. A regex that tried to exempt `…Ref` would be making a judgement about intent, and the failure direction there is a published credential.

**`warn` and `error` are not DEV-gated; `debug` and `info` are.** Design §20's "structured `console` in development" could be read as *nothing* in production. It must not be: `warn`/`error` are what an operator reads out over a support call beside the correlation id, and a console that goes silent in production is a console nobody can support. The verbose half folds out of a production build entirely — neither the call nor the string it would have logged ships.

**No transport, and that is enforced.** `NO_LOG_TRANSPORT` forbids `fetch`, `XMLHttpRequest`, `sendBeacon`, `WebSocket` and `EventSource` inside `shared/observability/**`. Design §20 is explicit that production error shipping waits on a **backend** log endpoint and that no SaaS RUM product may be invented for an air-gapped deployment; `REQ-003` means an install may have no egress at all. When that endpoint exists it is added in this module, behind `config.json`, and nowhere else.

**The correlation id is the server's** (`FE-D13`). `logger` never invents one; call sites pass the id the response carried. `SupportMenu`'s client-generated id stitches a support *conversation* and is never presented as an audit key.

**Rationale.** Every other control in this document assumes secrets are not in the logs. A type that cannot carry one needs no maintenance as new leak shapes appear, whereas a redactor has to recognise each of them — and the redactor is still there, as the net, precisely because the type cannot see a runtime-assembled key.

**Rejected:** an `unknown`/`object` field type with a deep redactor (recognising a secret inside an arbitrary object graph is the problem the type removes); `console.error(msg, obj)` (a browser console renders a logged object as a live expandable reference to the original, so anything it still points at stays reachable from the log — the logger formats to a string instead); DEV-gating `error` (unsupportable in production); adding a RUM SDK behind a flag (`REQ-003`).

---

## `FE-D43` — MSW's service worker ships in `public/` and is stripped from the build; the artefact, not the source tree, is what gets verified

`FE-101` found the production build shipping `dist/mockServiceWorker.js` — 9.6 KB of request-interception machinery, in every build since `FE-039`. `msw init public/` is the documented install, `publicDir` is copied verbatim into the build, and a bundle analysis cannot see it because the file never enters the module graph.

**The structural fix was built first and reverted on evidence.** Moving the worker to a `dev/` directory no build copies, served by an `apply: 'serve'` middleware, is a guarantee by construction and normally the better kind. It broke **11 fixture e2e tests** — Designer, journeys and 320 px reflow, all reporting `Failed to fetch dynamically imported module`, with a *different* set failing each run. Bisected against a clean `main`:

| Tree | `npm run e2e` |
|---|---|
| `main` | 166 passed, 0 failed |
| worker in `dev/` + serve middleware | **11 failed** |
| worker in `public/`, build strips it | 168 passed, 0 failed |

Serving a service worker correctly to five browser contexts registering it concurrently is Vite's static middleware's job. So the mechanism is a `closeBundle` hook (`dropDevOnlyAssetsFromBuild`) that removes the file from the output.

**The guarantee is therefore weaker — "the build removes it" rather than "the build cannot reach it" — and what makes that acceptable is the second half of this decision: the gate reads the ARTEFACT.** `npm run verify:production` runs a real `vite build` into a throwaway directory and walks **what was written**: emitted chunks, everything `publicDir` copied in, and whatever a `closeBundle` hook added or removed. If the plugin stops running — deleted, renamed, or defeated by a config change — `no-msw` goes red. A cleanup step verified by the artefact it cleans is not "one mistake away from shipping"; the mistake fails the build.

**An in-memory build is the wrong subject for an artefact assertion.** `build({ write: false })` (what `verify:bundle` correctly uses for a size budget) never copies `publicDir` — which is exactly where the shipped worker was hiding — and reports different rendered module sizes: `src/app/dev/gallery.routes.ts` measures 0 bytes in memory and **99 bytes** written, which caused a correct fix to be measured as a no-op and reverted before the written build showed otherwise.

**Rationale.** Design §21 and `FE-D24` state what must not ship; until `FE-101` nothing measured the thing that ships, so a file that never entered the module graph shipped for months while every gate stayed green. The general rule this records is not about MSW: **when a check's subject is "what the customer receives", the check must read what the customer receives** — an in-memory bundle, a source-directory listing and a module-graph entry are each a proxy, and every proxy has a gap.

**Rejected:** the `dev/` placement plus serve middleware (measured, breaks concurrent service-worker registration — see the table); deleting the worker entirely (the fixture-backed dev console and the whole `fixture` e2e project depend on it); asserting over `publicDir` instead of the artefact (a proxy, and the one the `dev/` experiment invalidated); failing on module-graph membership rather than rendered bytes (`gallery.routes.ts` participates in the graph while contributing zero bytes — a gate nobody could get green).

---

## `FE-D44` — A jsdom render budget is a ratio, not a millisecond count

`FE-103` had to give design §17's *"a 12-row grid and a ~100-field Designer form render within an agreed budget"* a number. The first version was an absolute ceiling: three unloaded runs at 342 · 348 · 409 ms, ceiling 1500 ms, ~4× headroom. It passed in isolation and **failed in the full suite at 1679 ms** — the identical render, five times slower purely from contention with 300-odd other test files.

Raising the ceiling to 6000 ms would have gone green and been worthless. **An absolute millisecond budget in jsdom measures how busy the machine is, not how expensive the render is.**

So the grid budget measures **scaling**: it renders 1 row and 12 rows in the same process, moments apart, and asserts the 12-row render costs no more than 3× the 1-row render. Machine speed and contention inflate both measurements and cancel out; the regressions the budget exists for do not — an O(n²) pass over rows explodes the ratio, and a per-row subscription, observer or layout read grows it linearly. A healthy Data Grid is dominated by fixed mount cost, so the honest expectation is a ratio near 1.

An absolute ceiling is kept where it is stable: the 100-field `project()` budget is 150 ms against 7–11 ms observed, and it passed in the same full-suite run the grid failed.

**What these budgets are, stated in the file itself.** jsdom has no layout, no paint, no compositor and no real font metrics; these are **regression tripwires**, not performance measurements, and they catch the 10× change rather than the 20% one. **No browser performance measurement exists** — frame timing and interaction latency need Playwright, no task in this plan commissions them, and inventing a browser number from jsdom would be worse than recording none.

**Rationale.** A budget that flakes on a busy CI runner gets deleted rather than investigated, and a budget nothing can breach (the first draft's 6000 ms against an 8 ms subject — a 600× ceiling) is not a budget. Normalising against a calibration measurement taken in the same process is what makes a jsdom timing assertion survive the conditions it actually runs in.

**Rejected:** a bigger absolute ceiling (green and meaningless); running the budgets in an isolated single-worker project (hides the contention the rest of the suite really has, and the number would still not transfer to CI); asserting on rendered DOM node counts instead of time (measures output size, not the per-row cost these regressions change).

---

## `FE-D45` — The console ships `D10`'s full 30-operation catalog as `grantable`, ahead of ratification

> **STATUS — SUPERSEDED 2026-09-04.** The catalog is no longer ahead of ratification: **`D18`** 🔒 ratifies
> it and adds five `group.*` verbs, and **`D19`** 🔒 answers the bootstrap question this entry lists as
> untouched. The console's gates now follow **`FE-D47`**. *Nothing below is rewritten* — this entry is the
> record of what the console did, and why, while the catalog was unratified, including the two
> `@ts-expect-error` assertions it removed (which stay removed; `ALL_GRANTABLE_VERBS` in
> `permissions.typetest.ts` is what replaces the guarantee they used to give). Of the four questions it
> lists as open, **two are now closed** (bootstrap, identity substrate) and **two remain open** (the audit
> log, and introspection scope).

`PERMISSION_CATALOG` (`shared/authz/catalog.ts`) marks **all 30** operations `grantable`, and `GrantableVerb` widens
from the two ratified verbs to the full set. The development identity (`devMockAdapter`, and the `cp` fixture
handlers) holds every id, so every gated control renders.

⚠️ **This is a `PROPOSED` frontend posture, not an amendment to `D10`.** `D10` is `LOCKED` and its ratified catalog
grants exactly `instance.bindTarget` and `target.use`. The remaining 28 are the approved mockup's candidate set,
routed to the architect group as [`D10-AMENDMENT-PROPOSAL.md`](https://github.com/parikshit-coe/solution-manager/blob/main/docs/design/D10-AMENDMENT-PROPOSAL.md)
and still open (issue #20). Recorded here so the divergence is discoverable rather than silent — `DECISIONS.md`'s
`D10` row is untouched (`I3`, `I4`).

**What this does not change.** `createPermissionApi` gates on the **granted set alone** — `has()` and `gate()` never
read `status`. So catalog status drives only what the Admin screens *display*, the width of the compile-time
`GrantableVerb` union, and which ids the dev identity is seeded with. **No runtime authorization behaviour depends on
it**, and `overlayRbac` remains the documented no-op it already was.

**What this costs, stated rather than hidden.**
- **`C12`'s compile-time guard is gone.** `GrantableVerb` was a two-member union, so gating a v1 feature on an
  unratified verb was a *type error*; both `@ts-expect-error` assertions in `permissions.typetest.ts` are removed. The
  surviving property is only that a bare `string` is rejected — typo protection, not ratification protection.
- **Four of the proposal's five questions stay open**, and marking verbs grantable answers none of them: first-admin
  bootstrap (§3a — a grantable verb still needs a principal holding it, and the assignment table is empty on a fresh
  install), the local identity substrate (§3b), the audit log (§3c — `config.overrideWarning` is now gateable while
  `UI-D16` still requires the override be *recorded*, and no audit log exists), and introspection scope (§4).
- **Claimed authority now exceeds enforcement.** The catalog advertises 30 grantable operations; the Designer gates on
  none of them and there is no server. `REQ-006`'s *"no endpoint is reachable without it"* is no closer to met.

**Rationale.** The console needs every screen reachable to be developed and demonstrated while `D10` sits unratified,
and `REQ-006`'s gate is a *server* obligation the frontend cannot discharge either way. Seeding the dev identity is
what actually unlocks the UI; widening the catalog aligns the Admin screens with the set under review, so the mockup's
role editor shows the roles the proposal asks the group to approve.

**Rejected:** editing `D10` in `DECISIONS.md` to say the catalog is full — an in-place meaning change to a `LOCKED`
decision, forbidden by `I3` and `I4`, and it would present the amendment as ratified to a group that has not seen it.
Also rejected: citing `frontend/src/shared/authz/catalog.ts` as normative from the architecture register, which
inverts the ownership model — the register owns policy, code implements it.

---

## `FE-D46` — The console's resource hierarchy is `Organization → Sub-org → Environment`; Tenant is not a level in it

The hierarchy the console presents, filters on and grants against is **three levels**:

```
Organization → Sub-org → Environment   (→ Instance, for RBAC scope only)
```

**Tenant is removed as a hierarchy level.** It was one: `SCOPE_TREE`'s root was a `level: 'Tenant'` node
("Acme Financial"), every RBAC assignment path began with it, and `Solace Super-admin` was granted *at* it. That
root is replaced by a synthetic **All organizations** node whose path is the empty array, with the organizations
as its children.

⚠️ **This does not touch tenant as the isolation boundary.** `I5`, `REQ-003` and `FE-D8` are untouched and
un-superseded: `Identity.tenant` keeps its no-`tenantId`-sibling shape, the app-bar chip keeps displaying the
server-returned string, and the two tests asserting that no route pattern carries a tenant segment
(`routes.test.ts`, `router.test.tsx`) stay green and stay meaningful. **The two senses of "tenant" in this repo are
different things** — the security boundary the server owns, and the hierarchy level the console drew. Only the
second is removed here.

**`[]` is the global scope.** The prefix algebra already supported it and nothing had used it: `isScopePrefix([], x)`
is true for every `x`, and the `effective-permissions` fixture handler already decoded an absent `scope` parameter
to `[]`. So `EffectiveGrant`, the direct-vs-inherited rule, `groupEffectiveGrants` and `AdminRepository`'s shape are
all unchanged. Super-admin's assignment moves from `['Acme Financial']` to `[]`. `scopePathString([])` renders the
localized "All organizations" rather than the empty string, so no caller has to special-case it.

**Selection is a dependent cascade, loaded per step.** `HierarchyRepository` (`shared/hierarchy/`) exposes three
sequential seams — `organizations()`, `subOrgs(org)`, `environments(org, subOrg)` — each a separate query gated on
the previous selection, rather than one tree fetch filtered on the client. Choosing an organization clears the
sub-org and environment; choosing a sub-org clears the environment.

**Environment values stay the `'DEV' | 'TEST' | 'PROD'` union, behind a data-shaped interface.** `environments()`
returns a loaded list, so the UI genuinely performs the third load; the union is only the element type. When a real
API and schema arrive and environments become customer-defined, widening `Env` to `string` is a change to one type
and `EnvChip`'s tone map — not a rewrite of every call site. Building free-form environments now would have meant
touching `EnvChip`, the instances grid, the filters, the Designer and Monitoring for a capability nothing yet
supplies.

**One fixture, two consumers.** The hierarchy had been asserted in two places that already disagreed —
`SCOPE_TREE` knew Payments and Trading; the instance rows carried six organizations. `ORGANIZATIONS`
(`shared/testdoubles/data/hierarchy.ts`) is now the single source, and `SCOPE_TREE` is **derived** from it plus the
instance rows. The scope tree therefore grows from two organizations to six. That is the drift being removed, not a
new fixture surface.

**Rationale.** A Tenant level in the hierarchy earned nothing the console could use. It was a constant — one node,
never more than one, unselectable and unswitchable by `I5`'s own design — so every scope path carried a segment
that discriminated nothing, and every screen spent a level of indentation and a breadcrumb position on it. Worse,
it made the *isolation* boundary look like a *navigational* one, which is exactly the confusion `FE-D8` exists to
prevent: a reader who sees Tenant as the top of the tree reasonably infers there is a tenant switcher somewhere,
and the honest answer is that there must never be one. Removing the level makes the top of the hierarchy the
highest thing an operator can actually choose between, and leaves tenancy where it belongs — in the server's scope,
displayed and never sent.

**What this costs, stated rather than hidden.**
- **It diverges from the `FE-D30` reference**, which still shows `name: "Acme Financial", level: "Tenant"` at the
  root of `reference/app/admin_data.jsx:46`. The reference is the source of truth for *screens and styling*; this is
  a model change, and it is recorded here rather than absorbed silently. The reference is not edited.
- **Two fixture rows had no third level and now must have one.** The `Sandbox` organization carried
  `subOrg: '—'` — an em-dash standing in for "none" — and the reference's `Trading` hung environments directly off
  the organization. A mandatory three-level cascade has room for neither, so `Sandbox` gains the sub-org `Default`
  and `Trading` keeps `Quotes`, which its own instance rows already used. **This is a fixture invention** (§16 Q10's
  field set is already invented) and would be answered properly by the backend contract.
- **`env` loses its default.** `initialDeclareForm` had defaulted to `DEV`; nothing can be chosen before a sub-org
  is, so `env` becomes `Env | ''` and empty is a validation error — the pattern `packageVersion` already used two
  fields above it. This *strengthens* the original "never one click from PROD" intent rather than weakening it.

**What this deliberately leaves alone.** Monitoring's `entity` / `tenant` / `personal` **dashboard layers**
(`DashboardToolbar`, `LayerBadge`, `SaveWidgetDialog`) are the `DM-D5` layer model — a different axis from the
resource hierarchy, still unratified, and still unbuilt. They are not renamed or removed here.

---

## `FE-D47` — The Groups screens gate on the ratified `group.*` verbs; membership is `group.member.manage`

`GROUP_PERMISSIONS` (`features/admin/groups/groupPermissions.ts`) maps each Groups action onto its **own**
ratified verb, and no longer borrows a `user.*` one:

| Action | Verb |
|---|---|
| View groups and members | `group.view` |
| Create group | `group.create` |
| Edit group | `group.edit` |
| Deactivate / reactivate group | `group.deactivate` |
| Add / remove members | `group.member.manage` |
| Assign / revoke a group's role | `role.assign` |

Two verbs are deliberately **not** introduced, per `D18`: `group.member.view` (group and member visibility
are one question — `group.view` answers it) and `group.role.assign` (`role.assign` already means assign or
revoke to or from a principal, and a group is a principal). `GroupPermissionMap` therefore loses its
`memberView` key rather than keeping it as an alias.

**Membership is gated on `group.member.manage`, and must never be weakened to `user.create` or
`user.deactivate`.** Adding a member grants that user every role the group holds, which makes it an
authorization act. The frontend gate is the visible half; the *binding* half is a server obligation `D18`
states and this console cannot discharge — the server must additionally refuse a membership change through
which the actor would confer a role or scope the actor may not delegate.

**What this does not change.** Nothing at any call site. `GroupsView` and `GroupDetails` reference verbs
only as `GROUP_PERMISSIONS.<action>`; the nine `can(...)` sites are untouched by this decision, which is
the property the mapping module was built to have.

**Rationale.** The Groups feature shipped against a catalog with no `group.*` verbs and recorded the
compromise in its own header rather than inventing ids — `GrantableVerb` made a bare `'group.create'` a
compile error precisely so the gap could not be closed quietly by a feature. `D18` closes it properly. The
borrowed mapping was defensible but lossy in one direction that mattered: `group.view` → `user.view` meant
an administrator who could see local *users* could see every *group*, which is a wider read than anyone
chose. Superseding `FE-D45`'s borrowed-verb posture, this decision makes the console's gates and the
register's catalog the same list.

**Supersedes:** the mapping recorded in `FE-D45`'s cost list. `FE-D45` itself stays as the record of what
the console did while the catalog was unratified.

---

## `FE-D48` — The console's v1 group model: one organization, immutable, flat, additive allow-only

The model the screens, the fixtures and the validation all implement, stated once:

- **One Group belongs to exactly one Organization**, held as its **name** (the same identity every other
  hierarchy reference uses — `ORGANIZATIONS[].name`, `RbacAssignment.scope` segments, `CACHE_INSTANCES[].org`).
  No opaque-ID migration is introduced for Groups alone.
- **One local User belongs to exactly one Organization.** No organization-membership join table and no
  primary-organization abstraction.
- **A Group may contain only users of its owning Organization.** Enforced in the form, in the fixture store,
  and — bindingly — on the server.
- **No global or cross-organization groups. No tenant-wide groups. No nested groups.**
- **Organization is immutable after creation.** `GroupEditDraft` has no `organization` field; the type is the
  enforcement.
- **`source: 'local' | 'idp'` is kept.** An IdP group's **identity and membership are read-only** — the
  provider owns them. An authorized administrator may still **assign and revoke RBAC roles** to and from an
  IdP group, because that is a `role.assign` act on a principal, not an edit of the provider's record.
- **Names:** unique per Organization; compared case-insensitively **after trim**; valid only at 1–128
  characters; Unicode letters and numbers, spaces, and `(` `)` `.` `_` `-` `&` `'`; **invalid** when they
  contain control characters, leading or trailing whitespace, `/`, `\`, or `:`.
- **Effective permissions are an additive, allow-only, deduplicated union** of direct assignments, group
  assignments and inherited assignments. **There is no deny rule** — `D10`'s model has none, and inventing a
  group-level one in the frontend would make the fixture disagree with the server it stands in for.
- **A deactivated group confers nothing.** Its assignment rows stay readable for audit.

**On the two name rules that look contradictory.** "Compared case-insensitively after trim" and "invalid
when it contains leading/trailing whitespace" are different rules for different jobs. Trimming is what the
**uniqueness** comparison does, so `"  Ledger "` cannot slip past an existing `"Ledger"`. Rejection is what
**validity** does, so the operator is told the rule instead of silently getting a name in the audit line
that is not the one they typed.

**Rationale.** Every exclusion in this list is a capability someone will eventually ask for, and each one
costs a join table, a resolution order, or a cycle check before it can be built correctly. A flat,
single-organization group is the smallest thing that makes "grant once to a collection" work, and it is the
shape a `D13` IdP migration can absorb without a data migration. `platform-sre` — the one fixture that
reads like a cross-organization group — is already scoped to `Trading` and needed no exception.

**Left open, deliberately.** Whether **deactivating** an IdP group is also provider-owned. Edit and
membership are gated on `source === 'local'`; status is not. The ratified brief says IdP group *identity and
membership* are read-only, which arguably covers status but does not say so. Recorded rather than resolved
silently.

---

## `FE-D49` — Assignments may be composed at Instance scope; root/global renders but is never created

`SelectableScopeType` becomes `'organization' | 'subOrganization' | 'environment' | 'instance'`, and
`ScopeSelector` grows a fourth dependent select whose options are the instances of the chosen environment.
The level already existed everywhere else — `ScopeLevel`, `scopeLevelOf`, `SCOPE_TREE`'s instance leaves —
and only the *composer* omitted it.

**The dialog offers no root/global scope type, and must not.** `D19` makes root grants
installer/database-managed in v1. Existing ones — the `installer bootstrap` super-admin assignment at scope
`[]` — must still **render** in the by-principal table, **confer** effective permissions through the prefix
algebra (`isScopePrefix([], x)` is true for every `x`), and be **inspectable** in the Access check and the
by-scope view. They are labelled **system-managed** rather than given a creation flow.

**One defect this exposed.** The by-scope view defaulted to `selectedScope ?? [scopeTree.name]` — the
synthetic root's *label* used as a path *segment*. `FE-D46` moved the root's path to `[]` and `ScopeTree`
emits `[]` on selection, but this default was never updated, so the view opened on a scope that does not
exist. Corrected to `[]`.

**Rationale.** An instance-scoped grant is the narrowest thing an operator actually asks for — "this person
may restart *that* instance" — and refusing to express it pushes every such request up to the environment,
which grants more than was intended. The scope tree already carried instance leaves for the browse view, so
this adds a select, not a model.

---

## `FE-D50` — The Bootstrap Super Admin is represented by the console, never created by it

`D19` puts exactly one Bootstrap Super Admin in the database at install time. The console's obligations are
narrow and are all display obligations:

- `LocalUser` carries **`isBootstrapAdmin`**, a flag — never a credential, never a hash, never a token.
  The fixture's `u-devon` is it, and already carried `createdBy: 'installer bootstrap'` and the `super` role
  at scope `[]` before this decision existed.
- The Users table marks that row, and **disables** its Deactivate control when it is the **last active**
  bootstrap admin, with the reason on the control.
- `AdminRepository` still has **no `bootstrapFirstAdmin()`** and must not grow one.

**Disabled, not omitted — and that is `FE-D9`, not an exception to it.** `FE-D9` omits controls the operator
is *not authorized* to use. Here the operator **is** authorized; the *system* refuses, because an install
with no active root admin cannot be recovered through the console. That is `FE-D9`'s explicitly carved-out
capability-limit branch — disabled with a stated reason. Omitting the button would have told an
administrator they lack a permission they hold.

**Marked as backend/install-time, in the UI copy itself:** password hashing, forced replacement at first
sign-in, the audit record, and session invalidation. The console shows the state; it does not implement one
of those four and must not appear to.

**Rationale.** The bootstrap admin is the one identity the console can neither create nor safely remove, and
`FE-D38` had left it deliberately unbuilt while the mechanism was unspecified. `D19` specifies it. What
changes here is only that the console stops being *silent* about the row — an administrator looking at the
Users table could previously not tell why one account was different, and could try to deactivate it and get
an unexplained `409`.

---

## `FE-D51` — The app bar labels the identity string `Organization`

The app bar's non-interactive identity chip reads **`Organization <value>`**, with the tooltip "Organization is a
display string the server returns — it is not switchable here." The component is `app/shell/OrganizationDisplay.tsx`.

**This supersedes one clause of `FE-D46`** — the sentence "the app-bar chip keeps displaying the
server-returned string" is still true, but its *label* changes. `FE-D46` is **not edited** (`I3`); it remains the
record of what was decided on 2026-09-04, when the word in the app bar was left alone.

**Nothing about the isolation boundary moves.** `I5`, `REQ-003` and `FE-D8` are untouched and un-superseded:

- `Identity.tenant` keeps its name and its shape — still **no `tenantId` sibling**, so "send it as a parameter"
  stays impossible to express.
- The value is still server-returned, still never sent back, still never a route segment. `routes.test.ts` and
  `router.test.tsx` — the two tests asserting no route pattern carries a tenant segment — are untouched and stay
  green.
- It is still not a switcher: the chip is text with a `title`, inside no `button` and no `a`, which
  `AppShell.test.tsx` asserts directly.

Only the **word an operator reads** changes. The prop is named `organization` and the identity field stays
`tenant`, and `OrganizationDisplay`'s header comment states why the two differ, so the seam is documented at the
one place it exists rather than inferred.

**Rationale.** After `FE-D46` the console draws, filters and grants against `Organization → Sub-org →
Environment`. "Tenant" then appeared in exactly one place in the whole UI — the app bar — naming a concept the
rest of the console had stopped using. An operator has no way to tell that this "Tenant" is the security
boundary rather than a fourth, higher hierarchy level they cannot find anywhere else; the two senses of the word
that `FE-D46` was careful to separate in the *documentation* were still colliding on *screen*. Removing the word
from the UI is what makes `FE-D46`'s separation real for the person using it.

**Divergence from the `FE-D30` reference, recorded not hidden.** `reference/app/shell.jsx` renders
`<span className="tk">Tenant</span>`. The reference predates `FE-D46` — it still draws the Tenant hierarchy level
this console no longer has — so it is stale here, in the same way `FE-D46` already found it stale for
`SCOPE_TREE`. `FE-D30` is not weakened: the reference remains the source of truth for screens and styling, and
this is a wording divergence, not a layout one.

---

## `FE-D52` — The verified product palette governs UI semantics; identity stays with the brand book

Supersedes the **paint clause of `FE-D5`** — the interim-colour permission — and nothing else. `FE-D5`'s tokens-only rule, its prohibition on hex/px/font-size in feature code, its one-theme-object rule and its light/dark mechanism all stand as written.

**Palette.** The MUI theme's palette is transcribed from the Solace Agent Mesh design tokens carried at [`reference/_ds/solace-agent-mesh-design-system-*/`](reference/) and **verified 2026-08-19 against `SolaceLabs/solace-agent-mesh` product source**: all 58 unique hex values in the token file appear upstream when compared **case-insensitively** (upstream spells five values lowercase and has one internal case collision, so a case-sensitive diff produces false positives). The `accent` chart ramp and the `darkSurface` navigation slice are upstream too. Method, measured counts and reproduction commands: [`reference/PALETTE-PROVENANCE.md`](reference/PALETTE-PROVENANCE.md).

**Type family** is `Rubik`, verified at the same upstream's `lib/index.css:123` (`font-family: "Rubik", sans-serif;`), **self-hosted** — an on-prem console makes no CDN request at load (`FE-D14`). The wider fallback chain in the token file (`Inter`, `system-ui`, `-apple-system`, `Segoe UI`) and the mono stack are design-authored, not upstream.

**Explicitly NOT verified**, and never to be described as such: the numeric size / line-height / weight scales, spacing, radii, shadows, motion, and the semantic alias layer. Upstream `solace.ts` is palette-only — 183 lines with no typography, spacing, radius, shadow or transition token. These are design-authored structure: coherent, safe to build on, and changeable by a reviewer without contradicting any Solace source.

**Precedence.** The verified product palette governs **UI semantics**. The Solace Brand Book governs **identity** — logo and marketing gradients. The two agree on the signature green `#00C895` and disagree on the **seven** other roles the provenance record's table compares.

**The interim pair `#003057` / `#00a89e` is retired.** Both were wrong: the verified navy is `#015B82`, the verified green `#00C895`.

⚠️ **This does not close open item 19b.** That item assigns the visual reference to **the designer or Figma**; a product repository is neither. The palette is **well-provenanced but not designer-confirmed**. Building on it requires the [`design.md`](design.md) §24 gate-2 **waiver** — a developer decision, not a consequence of this decision existing (`L8`: status transitions come from ratification, never from a proposal). **Three** questions are open for the designer, listed against open item 19b: whether the product palette governs console UI; whether the brand book file's own "v2.9" provenance holds; and how to resolve the untokenised purple used by the PROD chip, IdP-group chip, Designer layer badge and RBAC group icon.

**Ratification.** `FE-D52` supersedes a clause of `FE-D5`, which ratifies in the same family. The register's rule — that ratifying a superseding decision alongside the decision it supersedes leaves two live readings — is addressed by the explicit annotation on `FE-D5`'s body and index row rather than by deferring to a later round: `FE-D5`'s paint clause is marked superseded at the point of reading. If the architect group prefers separate rounds, ratify `FE-D5` first and `FE-D52` second.

**Rationale.** `FE-D5` blocked paint because *"`D7` says express the mockup as an MUI theme. The mockup is gone. Inventing a Solace palette from memory violates `I2`."* Two premises changed: an approved mockup exists again (`FE-D30`), and the palette is no longer recalled — it was fetched, diffed and counted. `I2` demanded verification and verification is what unblocks this. What remains missing is *authority*, not provenance, which is why the instrument is a waiver plus an annotated open item rather than a claim that the gate is met.

**A separate decision from `FE-D5` rather than an in-place amendment**, despite this family's draft amendment policy: that policy licenses *review corrections*, and `FE-D5` contained no error to correct. Changing a decision because the world changed is a new decision (`I3`).

**Rejected:** the Solace Brand Book as the UI palette — unverified provenance, an identity palette lacking the state layers, dark-surface slice and semantic ramps a console needs, and the approved mockup links only the product tokens, so adopting it would make the build diverge from what was reviewed. Continuing on the interim pair — now known wrong. Waiting for Figma before any theme work — the blocking reason was provenance, which now exists.

> **Merge note (ID re-homing).** This decision was authored as `FE-D26` on branch `contracts-backend-consumption`. Branch `dev/frontend` had concurrently issued `FE-D25` and `FE-D26` to different decisions, so on merge this one was re-homed to `FE-D52` and its companion — *the reference is the visual specification of record* — was found to duplicate `FE-D30` and folded into it. **Neither renumbering changes any decision's meaning**, but a pre-merge citation of `FE-D25`/`FE-D26` from that branch means `FE-D30`/`FE-D52` here. Flagged for the architect group with the `FE-D*` set.

---

## `FE-D53` — The console binds the `D20` Organization-rooted hierarchy end to end

The frontend identity field is `organization`, never `tenant`. The hierarchy fixture maps `Acme Financial` to the
Organization, maps the former Organization values (`Payments`, `Trading`, `Risk`, `Onboarding`, `Logistics`,
`Sandbox`) to Sub-organizations, and removes the former Sub-org values (`Ledger`, `Quotes`, `Batch`, `KYC`, `EU-West`,
`Default`). Environment and Instance remain below them.

The scope tree remains rooted at synthetic `All organizations` with path `[]`. Real paths are therefore
`[organization]`, `[organization, subOrganization]`, `[organization, subOrganization, environment]`, and optionally
an Instance segment. An ordinary identity's Organization is server-derived; only the installer-managed root grant
may span `[]` (`D19`, `D20`, `I5`).

**Rationale.** Merely dropping the Tenant node, as `FE-D46` did, left the old Organization and Sub-org data names in
their old levels. `D20` settles the intended migration rather than only the number of levels. This decision records
the frontend binding without rewriting the historical `FE-D46` body (`I3`).

---

## `FE-D54` — Canonical package validation happens before verification or projection

The `/solutions` load dialog accepts the canonical JSON document defined by
`contracts/solution-package.schema.json`: a non-empty ordered `packageVersion` array, optional component/field
`version` membership, and at least one component. It compiles that schema directly in the browser, runs the same
cross-reference rules as the Python validator—including cross-version reference integrity—reports every schema
problem with a JSON Pointer, and shows a component/field/action/metric summary before confirmation. An invalid file
never reaches `SolutionsRepository.load()`.

A successfully validated document keeps its version arrays when normalized for projection; normalization only maps
canonical nested UI metadata to the renderer's presentation fields. The browser check is usability feedback, not
admission authority: a future backend receiving bytes must repeat the canonical schema, semantic gates and signature
verification.

**Rationale.** Package authors need exact feedback before a backend exists, and version membership affects which
controls, defaults and rendered values exist for an instance. Reading the root schema directly prevents a second
structural contract, while gate 14 prevents a versioned field or action from referring to a field that disappears in
one of its active versions.

**Rejected:** treating TypeScript interfaces as the upload authority; accepting `.zip`/`.sompkg` without a bundle
reader; considering browser validation a trust boundary; flattening a multi-version package into one scalar version.

---

## `FE-D55` — An instance may bind to the component types it composes

`deriveComponentSelection(pkg, packageVersion, componentTypes?)` takes an optional list. **Absent** means the instance
declares no binding and every version-eligible component renders — byte-identical to the behaviour before this
decision, which is the whole backward-compatibility guarantee. **Present** selects only those components, in the
**package's** declaration order, never the binding's. **An empty list selects nothing**, deliberately distinct from
absent. A bound type the package does not declare, or declares only for another version, contributes nothing.

Every consumer that derives "which components does this instance have" must go through this function with the
load's `componentTypes`. The Designer and the Governance log viewer both do.

**Rationale.** `UI-D26` puts role at the component level, so a package can declare a source and a sink that no one
instance composes together. Without a binding a source instance would render its sink's fields, and Governance would
offer log levels for components the instance does not have.

**Scope.** This defines how the Designer **consumes** a binding. How a binding is **authored** is §16 Q10 (the
declare-instance wire contract), still open; until it closes the fixture layer supplies one.

**Rejected:** deriving the binding from `role` (an instance's role is not in the declare contract either);
a per-solution rule in React (`if (solution === …)` — the package exists precisely so the frontend carries no solution knowledge); treating an empty
binding as "everything" (silently widens an instance to components it was told it does not have).

---

## `FE-D56` — Role select at declare time resolves to a `componentTypes` list before it reaches the wire

When a package's version-eligible components carry **two or more distinct non-`none` `role`s**
(`UI-D26`'s closed vocabulary), `DeclareInstanceDialog` shows a required `Role` select with those role strings as
options. The choice never travels as `role` — it is resolved **client-side, in `toDeclareInput`**, to
`componentTypes = [...sharedComponentTypes(pkg, packageVersion), ...chosenGroup.componentTypes]` before the
declare request is built. `DeclareInstanceInput` gains only `componentTypes?: readonly string[]` — the field
`FE-D55` already sanctioned — never a `role` field. Fewer than two roles: no field appears, `componentTypes` stays
`undefined`, identical to today.

**Reconciling with `FE-D55`'s own "Rejected" clause.** That decision rejected "deriving the binding from `role`"
as a mechanism for `deriveComponentSelection` itself — the **consumption** function — reasoning that role was not
even available at consumption time, because nothing upstream of it ever collected one. This decision does not
revisit that: `deriveComponentSelection`'s signature is unchanged, still takes an explicit `componentTypes` list,
and never sees `role`. What changes is upstream of it — the **declare form**, which now offers `role` as a
transient authoring convenience that *produces* a `componentTypes` list, the same shape FE-D55 already sanctioned
as the wire field. `role` itself is never sent, never stored on `InstanceRow`, and never reaches the Designer.
Two different questions — "what does the Designer consume" (`FE-D55`, unchanged) and "how does an operator, today,
produce the list it consumes" (this decision) — reached different, compatible answers.

**Why resolved client-side, not server-side.** `FE-D33` ("the browser projects") already establishes that
derivation from a raw package runs in the browser, not in the dev fixture handlers — `deriveComponentSelection`
itself is the precedent. A server-side resolution would mean inventing a second, DP-owned "resolve role" step with
no more of a real contract behind it than the client-side one has, for no gained honesty: both are equally
fixture-layer, and the dev handler stays a passive `GET` of the raw package plus a passive `POST` of whatever
identity fields the client already computes — its existing role for every other field on this form.

**Rationale.** Reuses `role`, which already exists in the schema for exactly this purpose (`UI-D26`'s own
rationale: "a solution whose instances differ by role declares one component per role"), instead of inventing a
second per-component flag. Generic over any number of distinct roles — nothing names `source`/`sink`/`file-mesh`
(`REQ-001`); a future package under the same closed vocabulary needs no frontend change.

**Also fixes**, as a found-while-building correction rather than a new decision: the `/designer` dev handler's
declared-instance branch never read `InstanceRow.componentTypes` at all, so a session-declared instance always
rendered every component regardless of any binding. `componentTypes` now passes through.

**Rejected:** a two-step popup before the identity dialog opens (the identity form does not branch on role — no
config fields render there either way — so splitting it out is ceremony without a functional reason); folding role
options into `SolutionPackageSummary`/the `SOLUTIONS` sidebar fixture (that fixture is hand-authored and already
disconnected from the real schema package — `file-mesh` is absent from it entirely per plan 129 — piggybacking
role data there would be a second place for it to drift, the exact defect class open items 36/37 already name);
loading the full `DesignerLoad`-style payload just to read `role` (heavier than this dialog has ever needed,
and drags `contracts/view-model` parsing into a form that has deliberately stayed identity-only since task 115).

---

## Changelog

| Date | Change |
|---|---|
| 2026-09-10 | **`FE-D54` recorded and corrected before commit after the intended version model was confirmed.** The load dialog validates the canonical multi-version JSON document directly against the root schema and semantic gates, renders exact paths and a confirmation summary, and preserves component/field version membership into projection. `UI-D25` resolves the former scalar-versus-array conflict in the owning family. No backend upload contract is invented. |
| 2026-09-07 | **`FE-D53` recorded after `D20` ratification.** The console identity and fixture hierarchy use Organization as the top isolation/ownership level; former Organization values become Sub-organizations and the former Sub-org values are removed. The earlier `FE-D46`/`D11` conflict is closed by `D20`, not silently resolved or rewritten (`I3`). |
| 2026-09-07 | **`FE-D46` vs `D11` conflict made explicit.** Added the missing flagged-conflict row. Subsequently resolved by new locked parent decision `D20`; the historical conflict record remains. |
| 2026-09-05 | **`FE-D51` recorded** — the app bar labels its identity string **Organization**, not Tenant, superseding one clause of `FE-D46` (which is left unedited, `I3`). The isolation boundary is untouched: `Identity.tenant` keeps its name and its no-`tenantId`-sibling shape, the value is still server-returned and never sent back, and the two no-tenant-segment route tests stay green. `FE-D46` had removed Tenant from the hierarchy the console draws, leaving the word alive in exactly one place on screen, where an operator could only read it as a fourth hierarchy level. Flagged divergence from the `FE-D30` reference, which predates `FE-D46` and still draws the level. New ID, nothing edited (`I3`). `PROPOSED` (`I4`). |
| 2026-09-04 | **`FE-D47`…`FE-D50` recorded** from plan [`128`](../../plans/frontend/128-groups-rbac-ratification.md), implementing the newly ratified **`D18`** 🔒 (the 35-operation permission catalog) and **`D19`** 🔒 (v1 local auth and the Bootstrap Super Admin). The Groups screens stop borrowing `user.*` verbs; `group.member.view` and `group.role.assign` are declined rather than aliased; the assignment composer gains an **Instance** level while root/global stays render-only and labelled system-managed; the last active Bootstrap Super Admin becomes undeactivatable. **`FE-D45` is marked superseded, not rewritten** — it remains the record of the pre-ratification posture. Two latent defects surfaced and were fixed: the by-scope view defaulted to the root's *label* as a path *segment*, and `ScopeSelector` was the only place the instance level was missing. New IDs, nothing edited (`I3`). All `PROPOSED` (`I4`) — the parent decisions they implement are `LOCKED`. |
| 2026-08-16 | Initial `FE-D1`…`FE-D16`. Licences verified by WebFetch. Playwright recorded as Apache-2.0, not MIT. |
| 2026-08-16 | Senior review: `FE-D17`…`FE-D24`. Amended `FE-D6`, `FE-D7`, `FE-D13`, `FE-D14` in the same unratified cycle. |
| 2026-08-17 | **Independent adversarial review (R1), CRITICAL findings applied** in the same unratified cycle. `FE-D18` split into clause A (config-of-record concurrency, unblocked) and clause B (apply/rollout ordering, **blocked** on `DECISIONS.md` open item 1); title changed to match the body; interim `APPLY_INDETERMINATE`-by-default obligation and the concurrent-apply limit added. `FE-D23` extended to `yaml`-embedded secrets as a **server-side payload** obligation, client-side masking explicitly rejected, `type: yaml` gated to Phase 2. New flagged-conflict row for `UI-D23` vs `I6`. Remaining R1 findings triaged in design §27. |
| 2026-08-18 | **`UI-D24` reconciliation (R3) — one CRITICAL.** This family was drafted before `UI-D24` (virtual fields) existed. **`FE-D17` amended in place** (same unratified review cycle, per the amendment policy above — not superseded, `I3`): the save payload is `project()`'s explicit **`rendered`** set — which already excludes hidden, `virtual` and action entries — consumed rather than re-derived from the visible set (`renderedPath: null` on a virtual field). Under the old wording every File Mesh discriminator would have been written to the MI's config file — **54 phantom keys of the fixture's 206 fields**, a larger instance of the defect `FE-D17` exists to prevent. Title and index row changed to match the body. **`FE-D23` strengthened**: a secret's `value` is a discriminated `secretRef` / `secretUnset` union with no resolved-value arm, so the guarantee is type-enforced and `masked` is a rendering hint only. **Five stale `UI-D23` §3.6 citations retargeted to §3.7** (conflicts table, `FE-D23` body ×2, Phase-2 gate; plus `design.md` §26 item 14) — `UI-D24` took §3.6 and pushed yaml down. Design-side fixes (binding sets restated as **families** not ranges, `project()` output shape, gate-6 `enum \| bool` re-projection, `not-declared` → `not-applicable` health, new §25 risk row) recorded in `design.md` §27 R3. |
| 2026-08-17 | **Second pass (R2), all remaining R1 findings closed + two new ones.** New flagged-conflict row: `FE-D7` base-URL scope vs `SM-D1`'s "independently configurable base URLs" (finding 9, design §27). Every `FE-D` decision now carries a self-contained **Rationale** paragraph — 13 entries (`FE-D10`–`13`, `15`, `17`–`24`) previously relied on rationale scattered elsewhere in the document, against the `spec-review.md` Pass 1 requirement that every decision state one (finding 10). Content for each is drawn from existing document text; no new facts introduced. Design-side fixes (self-pass table, §12, §10.1, §13, §3, §21/§22) recorded in `design.md` §27. |
| 2026-08-19 | **`FE-D25`…`FE-D29` recorded** from the frontend implementation plan §16.1 (`I3`). `FE-D26` desktop-only is superseded by `FE-D29` (320 px reflow / SC 1.4.10). Flagged-conflict row: `FE-D29` vs `dynamic-metrics` §18. All remain `PROPOSED` (`I4`). |
| 2026-08-26 | **`FE-D31`…`FE-D37` recorded** (`I3` — new IDs, nothing edited). `FE-D31`/`FE-D32` promoted from plan [`002`](../../plans/frontend/implementation.md), where they had been taken during execution and recorded only in a batch plan document that never reached this register — the gap this entry closes. `FE-D33`…`FE-D37` from plan `003` (`FE-121`…`FE-125`): §16 **Q19** resolved on the frontend side, the `shared → contracts` boundary widened, `one-of-many` composition explicitly not invented, no Cancel control, and the version-threading discipline. All `PROPOSED` (`I4`). |
| 2026-08-28 | **As-built sync.** `FE-D31`…`FE-D37` added to the index table (they existed as entries but were never indexed). New flagged-conflict row: `FE-D31`'s array `packageVersion` vs [`../dynamic-ui/design.md`](../dynamic-ui/design.md) §3.1's scalar — the contract moved, the document that owns it did not. Header corrected (frontend code exists; 85 of 125 plan tasks done) and the in-place amendment latitude narrowed to decisions nothing is built against. **No new `FE-D` id was taken for the unsigned-package Open rule**: it is a sub-question of `UI-D19` and is recorded in that decision's own family — see the implementation note under [`UI-D19`](../dynamic-ui/decisions.md) (commit `af85903`/`baa2d76`). All remain `PROPOSED` (`I4`). |
| 2026-08-19 | **`FE-D30` recorded** — the visual reference is committed at `docs/design/frontend/reference/` (previously the gitignored `sm-v2/`) and named the source of truth for screens and visual styling, scoped out of responsiveness and explicitly *not* an acceptance of open item 19. Its `_ds/` design-system bundle was rejoined so it renders; five tokens previously resolved to nothing. `FE-D5` was **amended in place** here under this document's pre-ratification amendment policy; that edit was **reverted on merge (2026-09-07)** in favour of the `I3`-correct instrument — `FE-D5`'s body is preserved verbatim and carries a supersession banner instead. Status stays `BLOCKED`; the interim pair `#003057`/`#00a89e` is superseded and wrong, and the `reference/` token set is what development works from. Three new flagged-conflict rows. `PROPOSED` (`I4`). |
| 2026-09-02 | **`FE-D40` recorded** from execution of `FE-099` (the end-to-end journeys). Session states no fixture row can express are reached by real interleaving, fixture coherence, or — last resort — a DEV-only URL override, with the `import.meta.env.DEV` guard at every **call site** (the callee-only guard still shipped a stub, verified against a real build). Two journeys are narrower than the plan's line: journey 6's verb-omission half describes behaviour C12 forbids the console to have, and journey 4's dashboard arrival waits on §16 Q15. New ID, nothing edited (`I3`). `PROPOSED`. |
| 2026-09-02 | **`FE-D39` recorded** from execution of `FE-097` (the palette and contrast gate). Turning design §18's contrast sign-off into a CI check found six measured AA failures in the shipped palette — a 1.58:1 chart series colour, a 2.17:1 focus ring, six of seven tone chips below 4.5:1 as label text. The palette becomes per-mode and contrast-derived, the focus ring two-band, and `npm run verify:palette` a required gate. **No new hue**: every value is an existing token with its lightness moved, so open item 19 and `FE-D5`'s block on brand paint are untouched. New ID, nothing edited (`I3`). `PROPOSED`. |
| 2026-09-02 | **`FE-D38` recorded** from execution of `FE-094` (the plan's one `Ready: DECISION` task). §16 Q5's credential path is picked on the frontend surface: new local users get an admin-set temporary credential (forced change on first sign-in); emailed-invite and out-of-band are shown but not shipped as working paths; the first-admin bootstrap UI is deliberately not built. Q5's mechanism, the `POST /admin/users` contract and the bootstrap flow remain product/backend-owned and open (`I4`). New ID, nothing edited (`I3`). `PROPOSED`. |
| 2026-09-02 | **`FE-D41` recorded** from execution of `FE-100` (CSP and air-gap verification). `default-src 'self'` ships as a `<meta>` in `index.html` — one source of truth, stripped for the dev server rather than loosened for it. Design §26 **item 10 is answered**: ECharts needs no `unsafe-eval` (zero `eval(`/`new Function(` in the bundle; a real chart renders under the exact policy). One recorded relaxation, `style-src 'unsafe-inline'` for Emotion, with a nonce follow-up owned by SM packaging, which must also add the `<meta>`-ignored `frame-ancestors`. New ID, nothing edited (`I3`). `PROPOSED`. |
| 2026-09-03 | **`FE-D42` recorded** from execution of `FE-102` (observability and secret-leak prevention). `I6` is implemented as a **type**: `LogValue` admits only primitives, so a config value set, an `Error` (and its `cause` chain) or a metric series cannot be logged and still compile. Lint covers the one gap a type cannot see — a primitive under a secret-shaped name — and runtime redaction the one gap lint cannot see, a key assembled at runtime. `debug`/`info` fold out of a production build; `warn`/`error` do not, because an operator has to be able to read them out. No transport, lint-enforced (design §20, `REQ-003`). New ID, nothing edited (`I3`). `PROPOSED`. |
| 2026-09-03 | **`FE-D43` recorded** from execution of `FE-101` (production build assertions). The build had shipped MSW's service worker since `FE-039` — `publicDir` is copied verbatim and a bundle analysis cannot see it. The structural fix (worker in `dev/`, served by middleware) was built first and **reverted on evidence**: 11 fixture e2e failures under concurrent service-worker registration, bisected against a clean `main` (166 pass / 11 fail / 168 pass). The cleanup hook that replaced it is held honest by the second half of the decision — `verify:production` walks the **written artefact**, not an in-memory bundle, because `write: false` never copies `publicDir` and reports different rendered module sizes. New ID, nothing edited (`I3`). `PROPOSED`. |
| 2026-09-03 | **`FE-D44` recorded** from execution of `FE-103` (performance verification). An absolute jsdom ms ceiling passed in isolation and failed in the full suite at 5× the unloaded time; it measures machine load, not render cost. Render budgets are now **ratios** against a calibration render taken in the same process, which cancels contention while still catching the per-row and O(n²) regressions they exist for. Recorded with what they are not: jsdom tripwires, and **no browser performance measurement exists**. New ID, nothing edited (`I3`). `PROPOSED`. |
| 2026-09-03 | **`FE-D45` recorded** — the console ships `D10`'s full 30-operation catalog as `grantable` and seeds the dev identity with every id, so every gated control renders while the amendment is unratified. Recorded as a **flagged divergence, not an amendment**: `DECISIONS.md`'s `D10` row is untouched and issue #20 stays open. The entry states what the flip costs — `C12`'s compile-time guard is gone, and four of the proposal's five questions (bootstrap, identity substrate, audit log, introspection scope) are untouched by it. New ID, nothing edited (`I3`). `PROPOSED` (`I4`). |
| 2026-09-04 | **`FE-D46` recorded** — the Tenant level is removed from the console's resource hierarchy, leaving `Organization → Sub-org → Environment`, with `[]` as the global RBAC scope and a synthetic "All organizations" root. Tenant as the **isolation boundary** (`I5`, `REQ-003`, `FE-D8`) is explicitly untouched and un-superseded; only the hierarchy level goes. Selection becomes a per-step dependent cascade behind `HierarchyRepository`; `Env` stays a union behind a data-shaped seam. `SCOPE_TREE` is now derived from a single `ORGANIZATIONS` fixture, removing a drift that had it knowing two organizations while the instance rows carried six. Recorded with its costs: a flagged divergence from the `FE-D30` reference, two invented fixture sub-orgs, and the loss of `env`'s default. New ID, nothing edited (`I3`). `PROPOSED` (`I4`). |
| 2026-08-19 | **Design import, palette verification, and an adversarial round on both.** New **`FE-D30`** (the checked-in `reference/` artifact is the visual specification of record) and new **`FE-D52`** (verified product palette governs UI semantics; brand book governs identity). `FE-D30` deliberately does **not** frame the reference as the mockup `D7` already referred to — `D7` is `LOCKED` and predates it, so that framing would shift a locked decision's meaning in place. **`FE-D5` is NOT amended**: its paint clause is marked superseded by `FE-D52` and its body is preserved verbatim. An earlier draft of this change amended `FE-D5` in place under this document's draft amendment policy; independent review found that the policy licenses *review corrections* and `FE-D5` contained no error — changing a decision because the world changed is a new decision (`I3`). Artifact imported to [`reference/`](reference/): 22 JSX files, `theme.css`, both `_ds` token sets, 18 curated stills. Palette verified against `SolaceLabs/solace-agent-mesh`: all 58 unique hex values upstream **case-insensitively** (upstream has one internal case collision and five lowercase-spelled values, so a case-sensitive diff false-flags two faithful entries); `Rubik` verified at `lib/index.css:123`; typography/spacing/radii/shadows/motion explicitly **not** verified — upstream `solace.ts` is palette-only. Interim pair `#003057`/`#00a89e` **retired as wrong**. ⚠️ **Open item 19b is NOT closed** — it assigns the reference to the designer, so theme work runs under the `design.md` §24 gate-2 waiver (`L8`). **Adversarial review of this change set corrected three of its own claims:** the state vocabulary is **eight tables / 35 entries / five files**, not six/29/three (it had missed `STATUS_META` and `CAT_STATE`, both rendered in approved screens — the very membership collapse the transcription exists to prevent); the untokenised purple appears at **three** `theme.css` sites with four consumers, not one, so the proposed `accent-purple` swap is wider in scope than first stated **and lowers contrast** 6.39:1 → 5.80:1 rather than raising it; and the provenance record's case-collision evidence was a recalled count ("eight other pairs") where the measured answer is one. Lessons `L15`, `L16`, `L17` added. |
| 2026-09-07 | **Merged `contracts-backend-consumption` into `dev/frontend`.** Both branches had concurrently issued `FE-D25` and `FE-D26` to different decisions. Resolved by keeping this branch's numbering — it is cited across the plan, the task reports and 967 changed files — and **re-homing the other branch's two**: its *visual specification of record* duplicated `FE-D30` and was folded into it (its unique paragraphs kept), and its *verified product palette* became **`FE-D52`**, body preserved. **No decision's meaning changed**; a pre-merge citation of `FE-D25`/`FE-D26` from that branch means `FE-D30`/`FE-D52` here. The duplicate `reference/sm-v2/` copy of the prototype was removed — byte-identical to `reference/` apart from the `../_ds/…` href this layout fixes. **The re-homing is flagged for the architect group with the `FE-D*` set** (`I3`/`I4`). |
