# Frontend decisions — `FE-D*`

Decisions arising from [`design.md`](design.md). **All are `PROPOSED`.** None is ratified. **Phase 0 frontend code now exists** (`frontend/` — theme, state vocabulary, guards; no screens), built deliberately to touch nothing plane-specific, API-specific or RBAC-related so ratification can still change those freely.

**ID discipline (`I3`):** IDs are immutable and never renumbered. `FE-D*` is the per-component family named in [`../README.md`](../README.md). It does **not** re-decide `D*`, `B/D*`, `SM-D*`, `UI-D*` or `DM-D*`. Cross-cutting choices are cited, not restated.

> **Amendment policy for this draft.** `I3`'s supersession rule protects decisions something has been built or ratified against. `FE-D1`…`FE-D24` are a first draft with no code and no ratification, so review corrections in the same cycle may be applied in place and itemized in the changelog. **Post-ratification the supersession rule applies normally.**

⚠️ **This family rests on an unratified and partially contested base.** `D7` is `LOCKED` but **open item 19** contests the visual system. `SM-D*` and all of `UI-D*` / `DM-D*` are `PROPOSED`. Per `I4`, nothing here may be implemented as though settled.

⚠️ **Two decisions are gated on work outside this document.** `FE-D18`'s **apply/restart** half depends on [`../DECISIONS.md`](../DECISIONS.md) **open item 1** (the `dp-remote` apply protocol — a ratification blocker); its **save** half is unblocked. `FE-D23`'s **`yaml`-embedded-secret** clause gates `type: yaml` on a server-side redaction obligation that does not exist yet. Neither gate was visible in the first draft of this family; both were raised by independent review (design §27).

⚠️ **Conflicts flagged, not resolved (`I4`):**

| This family | Against | Disposition |
|---|---|---|
| `FE-D4` harvest of File Mesh wrappers | `D15` 🔒 generalize-and-harden vs `SM-D18` greenfield | Frontend is a **Vite greenfield** (`D7` already says migrate CRA→Vite). Harvest **wrappers and UX patterns**, do not fork the CRA/Redux-Saga app. Backend Java/Node is out of this document. |
| `FE-D5` / `FE-D26` theme tokens | Open item 19b — the visual reference is the **designer's** to supply | Stack follows `D7`. **Partly resolved 2026-08-19 by `FE-D26`:** an in-repo reference now exists ([`reference/`](reference/)) and the palette is verified against Solace product source, so "no in-repo reference" no longer holds and the interim pair is retired. **Still open:** 19b names the designer as the authority, so the palette is well-provenanced but **not designer-confirmed**; building on it needs the `design.md` §24 gate-2 waiver, and visual-regression baselines stay blocked. |
| `FE-D25` sm-v2 as visual spec of record | `D7` 🔒 refers to a mockup that no longer exists | `D7`'s *instruction* — express the mockup as an MUI theme — is honoured. **Which artifact fills the "mockup" slot is a new proposed decision, not a re-reading of `D7`** — writing sm-v2 into `D7`'s meaning would shift a locked decision in place (`I3`). Flagged for the architect group with the `FE-D*` set. |
| Icon set: `FE-D25` reference vs [`design.md`](design.md) §8.1 | §8.1's Icons row says **"MUI icons; status never icon-only"**; the approved mockup uses a **Lucide-style stroke set** (80 icons, `reference/sm-v2/app/icons.jsx`) that is visually distinct from Material's | **Flagged, not resolved (`I4`).** The accessibility half of §8.1 — *status never icon-only* — is load-bearing and stands unconditionally. The *"MUI icons"* half was written when no mockup existed, which is the same position the palette was in before `FE-D26`. Implementation proceeds on the mockup's set, **generated** from `icons.jsx` rather than transcribed, because `FE-D25` gives the reference precedence on visuals; `@mui/icons-material` stays available for anything the mockup lacks. **Whether §8.1 is amended is the architect group's call** — route it with the `FE-D*` set. |
| ⚠️ `FE-D25` reference copy | **`architecture.md` §7.2 and `SM-D2`** — the mockup's *"Restore the runtime"* modal (`designer.jsx:303`) has SM **re-apply a version to a target and restart it**. Architecture says SM *instructs the operator* to deploy through their own pipeline, then re-checks | 🔴 **The architecture wins.** SM being a config/governance plane and **not** a deployment plane is the one thing the architect group has confirmed. The reference contradicts **itself** here (`instances.jsx:109`: *"applies configuration, never artifacts"*), so this is a defect in the artifact, not a design choice — recorded in [`reference/ERRATA.md`](reference/ERRATA.md) **E1**. `FE-D25` is overridden for those lines. |
| ⚠️ `FE-D25` chart palette | **`FE-D26`** — `charts.jsx:9` paints every series in the **Tableau 10** ramp; none of its eight colours appears in the verified Solace palette, which carries a dedicated chart ramp | **`FE-D26` wins on paint**; `FE-D25` wins on layout and copy. Errata **E4**; added to open item 19b as a designer question. |
| ⚠️ `FE-D25` dashboard layers | **`DM-D5`** — the mockup uses two layers (`package-baseline` / `operator`, reusing the *metric-declaration* layer vocabulary); `DM-D5` defines three (`product-default` / `shared` / `personal`) and its `personal` clause supersedes `SM-D31` | **Flagged, not resolved.** Two closed vocabularies for "layer" now exist with overlapping-but-different members — the drift `UI-D14` exists to prevent. Whichever ratifies, the reference's chips and copy need renaming. Errata **E5**. |
| Renderer last-mile | `D1` 🔒 custom-UI escape hatch vs `UI-D21` named screen types | This document implements the **generic renderer + composer**. The escape hatch stays until the architect group decides. |
| `FE-D11` Data Grid Community | `D7` harvest of File Mesh tables, some of which may need Pro features | v1 uses MIT Community. Pro/Premium is a **procurement** decision, not assumed. |
| Chart adapter | `DM-D13` vs `D7` harvest-charts | **Uphold `DM-D13`.** MUI remains the chrome. See design §11. |
| `FE-D23` yaml-embedded secrets | `UI-D23` §3.7 item 3 accepts **warn-only, stored in clear** vs `I6`'s "never in **field-level diffs**, **exports**, archives or logs" | This document binds only what it owns: the **payload** the console receives must be redacted server-side, and **no `type: yaml` field ships until it is**. Whether the *stored* value is redacted is Subsystem A's decision — **flagged, not resolved.** |
| `FE-D7` base-URL scope | `SM-D1`'s UI seam names "independently configurable base URLs" for `cpClient`/`dpClient` | Narrowed to **internal/packaging-only** configurability behind one public gateway origin (`FE-D19`). Two public `VITE_*` URLs would leak plane topology to the browser and fail `REQ-003`. A defensible reading of `SM-D1`, not a restatement of it — flagged per this document's own discipline. |

---

| ID | Decision | Status |
|---|---|---|
| `FE-D1` | Server state is TanStack Query; API data is never a global Redux/Zustand cache | PROPOSED |
| `FE-D2` | Client/global UI state is Zustand; ephemeral UI is React local state | PROPOSED |
| `FE-D3` | Feature-oriented folders with a shared kernel | PROPOSED |
| `FE-D4` | One design system: MUI + harvest wrappers; no second library | PROPOSED |
| `FE-D5` | Styling is theme tokens only; brand paint blocked — **paint clause superseded by `FE-D26`** | PROPOSED |
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
| `FE-D25` | **`sm-v2` is the visual specification of record** — fills the mockup slot `FE-D5` recorded as empty, without re-reading `D7` | PROPOSED |
| `FE-D26` | Verified product palette governs UI semantics; brand book governs identity. Supersedes `FE-D5`'s paint clause; designer confirmation still pending | PROPOSED |

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

> ⚠️ **The paint clause of this decision is SUPERSEDED by [`FE-D26`](#fe-d26--the-verified-product-palette-governs-ui-semantics-identity-stays-with-the-brand-book) (2026-08-19).** Everything else stands: tokens only, no hex/px/font-size in feature code, one theme object, light and dark through `palette.mode`. The text below is preserved unchanged — it was accurate when written, and `I3` forbids editing a decision's meaning in place.

No hex, px, or font size in feature code. Tokens live in the MUI theme. **Solace brand values are not invented here.** Until open item 19 is closed, development may use MUI defaults plus **interim** colours taken from the checked-in Dynamic Metrics screens (`#003057`, `#00a89e`) labelled **unverified against the deleted DS**. Light/dark both go through the same theme object.

**Rationale.** `D7` says express the mockup as an MUI theme. The mockup is gone. Inventing a Solace palette from memory violates `I2`.

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

## `FE-D25` — sm-v2 is the visual specification of record

The Claude Design artifact **`sm-v2`**, exported and carried at [`reference/`](reference/), is the console's visual and interaction specification: navigation, layout, state vocabulary, copy, and empty/error states. Where the built console and the reference disagree on any of those, **the reference wins** unless a `FE-D*` / `UI-D*` / `DM-D*` decision says otherwise — in which case flag the conflict, do not resolve it silently (`I4`).

It fills the "mockup" slot that `FE-D5` previously recorded as empty (*"The mockup is gone"*). Two things it is **not**:

1. **Not a re-reading of `D7`.** `D7` is `LOCKED`, predates sm-v2, and was locked when its mockup was the deleted Tailwind/Radix handoff. `D7`'s *instruction* — *"express the mockup's visual design as an MUI theme"* — is honoured by rebuilding sm-v2 in MUI. But **which artifact fills that slot is a new decision**, because writing sm-v2 into `D7`'s referent would shift a locked decision's meaning in place, and `I3` says *"A citation like 'per D8' must never shift meaning."* Cite `FE-D25` for the artifact; cite `D7` for the instruction.
2. **Not a stack decision, and not a reopening of one.** sm-v2 is plain CSS on the Solace Agent Mesh design system, which is the system the deleted handoff paired with Tailwind v4 + Radix — the unresolved conflict `DECISIONS.md` records against `D7`. Adopting sm-v2's **paint** through an MUI theme touches neither `D7`'s stack clause nor `FE-D4`'s *"No Tailwind, no Radix, no shadcn, no parallel token set."* The prototype's own CSS ships nowhere. `FE-D4` stands unchanged, and its reading of the handoff as a **visual-reference gap rather than a licence to run two stacks** is what this decision implements.

**Consumed as a specification, never as code.** The artifact is React 18 UMD with in-browser Babel, `window.*` globals, a hand-rolled hash router and `localStorage` auth. Nothing under `reference/` is built, linted, type-checked or imported. Its mock data shapes are **illustrative, not a contract** — the Console OpenAPI, when it exists, is the contract, and payloads are expected to change without invalidating the visual spec.

⚠️ **This decision is overridden, line by line, by [`reference/ERRATA.md`](reference/ERRATA.md).** "The reference wins" is only safe if the places the reference is *wrong* are written down — otherwise it launders errors into the product. Twelve entries are recorded there, two of them critical: the mockup makes SM **deploy artifacts** (**E1**), and it **misstates LOCKED `D10`**'s verbs (**E2**). Read the errata before transcribing anything.

**Transcribe, do not paraphrase.** State labels, tips and screen copy were reviewed and approved as written; they are part of the contract with the operator. The vocabulary is transcribed verbatim at [`reference/STATE-VOCABULARY.md`](reference/STATE-VOCABULARY.md) — **eight** tone/label tables plus one ordered ladder, **35 entries across five files**. Reconstructing it from a derived document instead of from source has already been attempted once and was wrong in membership, tones and labels (`L16`); the first transcription then missed two tables itself, which is why the counts are recorded and checkable.

**Rationale.** `REQ-001`'s console renders every solution from a package, so its screens are the product's one irreducibly hand-designed surface; leaving them specified only by prose is how the state vocabulary got collapsed from 35 entries to 11. A named, in-repo, verbatim artifact makes fidelity checkable instead of remembered. Recording it as a decision rather than a README note is what keeps `D7` citations stable.

**Rejected:** treating sm-v2 as the artifact `D7` already referred to — convenient, but an in-place meaning change to a locked decision. Keeping it out of the repo and linking the design project — an external URL is not a reviewable baseline, and open item 19b exists precisely because the previous reference vanished. Importing the two superseded prototypes as well — they predate sm-v2 and would create ambiguity about which is authoritative.

## `FE-D26` — The verified product palette governs UI semantics; identity stays with the brand book

Supersedes the **paint clause of `FE-D5`** — the interim-colour permission — and nothing else. `FE-D5`'s tokens-only rule, its prohibition on hex/px/font-size in feature code, its one-theme-object rule and its light/dark mechanism all stand as written.

**Palette.** The MUI theme's palette is transcribed from the Solace Agent Mesh design tokens carried at [`reference/_ds/solace-agent-mesh-design-system-*/`](reference/) and **verified 2026-08-19 against `SolaceLabs/solace-agent-mesh` product source**: all 58 unique hex values in the token file appear upstream when compared **case-insensitively** (upstream spells five values lowercase and has one internal case collision, so a case-sensitive diff produces false positives). The `accent` chart ramp and the `darkSurface` navigation slice are upstream too. Method, measured counts and reproduction commands: [`reference/PALETTE-PROVENANCE.md`](reference/PALETTE-PROVENANCE.md).

**Type family** is `Rubik`, verified at the same upstream's `lib/index.css:123` (`font-family: "Rubik", sans-serif;`), **self-hosted** — an on-prem console makes no CDN request at load (`FE-D14`). The wider fallback chain in the token file (`Inter`, `system-ui`, `-apple-system`, `Segoe UI`) and the mono stack are design-authored, not upstream.

**Explicitly NOT verified**, and never to be described as such: the numeric size / line-height / weight scales, spacing, radii, shadows, motion, and the semantic alias layer. Upstream `solace.ts` is palette-only — 183 lines with no typography, spacing, radius, shadow or transition token. These are design-authored structure: coherent, safe to build on, and changeable by a reviewer without contradicting any Solace source.

**Precedence.** The verified product palette governs **UI semantics**. The Solace Brand Book governs **identity** — logo and marketing gradients. The two agree on the signature green `#00C895` and disagree on the **seven** other roles the provenance record's table compares.

**The interim pair `#003057` / `#00a89e` is retired.** Both were wrong: the verified navy is `#015B82`, the verified green `#00C895`.

⚠️ **This does not close open item 19b.** That item assigns the visual reference to **the designer or Figma**; a product repository is neither. The palette is **well-provenanced but not designer-confirmed**. Building on it requires the [`design.md`](design.md) §24 gate-2 **waiver** — a developer decision, not a consequence of this decision existing (`L8`: status transitions come from ratification, never from a proposal). **Three** questions are open for the designer, listed against open item 19b: whether the product palette governs console UI; whether the brand book file's own "v2.9" provenance holds; and how to resolve the untokenised purple used by the PROD chip, IdP-group chip, Designer layer badge and RBAC group icon.

**Ratification.** `FE-D26` supersedes a clause of `FE-D5`, which ratifies in the same family. The register's rule — that ratifying a superseding decision alongside the decision it supersedes leaves two live readings — is addressed by the explicit annotation on `FE-D5`'s body and index row rather than by deferring to a later round: `FE-D5`'s paint clause is marked superseded at the point of reading. If the architect group prefers separate rounds, ratify `FE-D5` first and `FE-D26` second.

**Rationale.** `FE-D5` blocked paint because *"`D7` says express the mockup as an MUI theme. The mockup is gone. Inventing a Solace palette from memory violates `I2`."* Two premises changed: an approved mockup exists again (`FE-D25`), and the palette is no longer recalled — it was fetched, diffed and counted. `I2` demanded verification and verification is what unblocks this. What remains missing is *authority*, not provenance, which is why the instrument is a waiver plus an annotated open item rather than a claim that the gate is met.

**A separate decision from `FE-D5` rather than an in-place amendment**, despite this family's draft amendment policy: that policy licenses *review corrections*, and `FE-D5` contained no error to correct. Changing a decision because the world changed is a new decision (`I3`).

**Rejected:** the Solace Brand Book as the UI palette — unverified provenance, an identity palette lacking the state layers, dark-surface slice and semantic ramps a console needs, and the approved mockup links only the product tokens, so adopting it would make the build diverge from what was reviewed. Continuing on the interim pair — now known wrong. Waiting for Figma before any theme work — the blocking reason was provenance, which now exists.

---

## Changelog

| Date | Change |
|---|---|
| 2026-08-16 | Initial `FE-D1`…`FE-D16`. Licences verified by WebFetch. Playwright recorded as Apache-2.0, not MIT. |
| 2026-08-16 | Senior review: `FE-D17`…`FE-D24`. Amended `FE-D6`, `FE-D7`, `FE-D13`, `FE-D14` in the same unratified cycle. |
| 2026-08-17 | **Independent adversarial review (R1), CRITICAL findings applied** in the same unratified cycle. `FE-D18` split into clause A (config-of-record concurrency, unblocked) and clause B (apply/rollout ordering, **blocked** on `DECISIONS.md` open item 1); title changed to match the body; interim `APPLY_INDETERMINATE`-by-default obligation and the concurrent-apply limit added. `FE-D23` extended to `yaml`-embedded secrets as a **server-side payload** obligation, client-side masking explicitly rejected, `type: yaml` gated to Phase 2. New flagged-conflict row for `UI-D23` vs `I6`. Remaining R1 findings triaged in design §27. |
| 2026-08-18 | **`UI-D24` reconciliation (R3) — one CRITICAL.** This family was drafted before `UI-D24` (virtual fields) existed. **`FE-D17` amended in place** (same unratified review cycle, per the amendment policy above — not superseded, `I3`): the save payload is `project()`'s explicit **`rendered`** set — which already excludes hidden, `virtual` and action entries — consumed rather than re-derived from the visible set (`renderedPath: null` on a virtual field). Under the old wording every File Mesh discriminator would have been written to the MI's config file — **54 phantom keys of the fixture's 206 fields**, a larger instance of the defect `FE-D17` exists to prevent. Title and index row changed to match the body. **`FE-D23` strengthened**: a secret's `value` is a discriminated `secretRef` / `secretUnset` union with no resolved-value arm, so the guarantee is type-enforced and `masked` is a rendering hint only. **Five stale `UI-D23` §3.6 citations retargeted to §3.7** (conflicts table, `FE-D23` body ×2, Phase-2 gate; plus `design.md` §26 item 14) — `UI-D24` took §3.6 and pushed yaml down. Design-side fixes (binding sets restated as **families** not ranges, `project()` output shape, gate-6 `enum \| bool` re-projection, `not-declared` → `not-applicable` health, new §25 risk row) recorded in `design.md` §27 R3. |
| 2026-08-17 | **Second pass (R2), all remaining R1 findings closed + two new ones.** New flagged-conflict row: `FE-D7` base-URL scope vs `SM-D1`'s "independently configurable base URLs" (finding 9, design §27). Every `FE-D` decision now carries a self-contained **Rationale** paragraph — 13 entries (`FE-D10`–`13`, `15`, `17`–`24`) previously relied on rationale scattered elsewhere in the document, against the `spec-review.md` Pass 1 requirement that every decision state one (finding 10). Content for each is drawn from existing document text; no new facts introduced. Design-side fixes (self-pass table, §12, §10.1, §13, §3, §21/§22) recorded in `design.md` §27. |
| 2026-08-19 | **Design import, palette verification, and an adversarial round on both.** New **`FE-D25`** (the approved `sm-v2` artifact is the visual specification of record) and new **`FE-D26`** (verified product palette governs UI semantics; brand book governs identity). `FE-D25` deliberately does **not** frame sm-v2 as the mockup `D7` already referred to — `D7` is `LOCKED` and predates it, so that framing would shift a locked decision's meaning in place. **`FE-D5` is NOT amended**: its paint clause is marked superseded by `FE-D26` and its body is preserved verbatim. An earlier draft of this change amended `FE-D5` in place under this document's draft amendment policy; independent review found that the policy licenses *review corrections* and `FE-D5` contained no error — changing a decision because the world changed is a new decision (`I3`). Artifact imported to [`reference/`](reference/): 22 JSX files, `theme.css`, both `_ds` token sets, 18 curated stills. Palette verified against `SolaceLabs/solace-agent-mesh`: all 58 unique hex values upstream **case-insensitively** (upstream has one internal case collision and five lowercase-spelled values, so a case-sensitive diff false-flags two faithful entries); `Rubik` verified at `lib/index.css:123`; typography/spacing/radii/shadows/motion explicitly **not** verified — upstream `solace.ts` is palette-only. Interim pair `#003057`/`#00a89e` **retired as wrong**. ⚠️ **Open item 19b is NOT closed** — it assigns the reference to the designer, so theme work runs under the `design.md` §24 gate-2 waiver (`L8`). **Adversarial review of this change set corrected three of its own claims:** the state vocabulary is **eight tables / 35 entries / five files**, not six/29/three (it had missed `STATUS_META` and `CAT_STATE`, both rendered in approved screens — the very membership collapse the transcription exists to prevent); the untokenised purple appears at **three** `theme.css` sites with four consumers, not one, so the proposed `accent-purple` swap is wider in scope than first stated **and lowers contrast** 6.39:1 → 5.80:1 rather than raising it; and the provenance record's case-collision evidence was a recalled count ("eight other pairs") where the measured answer is one. Lessons `L15`, `L16`, `L17` added. |
