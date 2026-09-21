# Errata — where the visual specification of record is wrong

**`FE-D25` says "where the built console and this reference disagree, the reference wins."** That rule is only safe if the places the reference is *wrong* are written down. Otherwise it launders errors into the product: a builder transcribes faithfully, the fidelity test passes, and the console ships a capability Solution Manager cannot have or a state machine a decision forbids.

Every entry below was found by independent review and verified against source on 2026-08-19. **This file overrides `FE-D25` for the specific lines it names.** Nothing else in the reference is affected.

The mockup flags most of its own inventions in-screen — a findings panel, a bordered note, a comment. **The entries here are the ones it does *not* flag**, which is what makes them dangerous: they read as settled design.

---

## E1 — CRITICAL · `designer.jsx:303` makes SM an artifact-deployment plane

**Reference says:** a "Restore the runtime" modal with a danger action `Restore 2.4 & restart`, body copy *"Restoring re-applies the declared version (2.4) to the target and restarts it — an outage. Use this when the deployed 2.3 was the accident."* Reached from `designer_panels.jsx:147`'s `BlockedBanner` on `VERSION_DRIFT`.

**What governs instead:** [`architecture.md`](../../architecture.md) §7.2 — *"restore the runtime — SM **instructs the operator** to deploy the declared version through their pipeline, then re-checks."* SM does not deploy.

**Why this one matters most.** That SM is a **config/governance plane, not a deployment plane** is the *one* thing `CLAUDE.md` records the architect group as having actually confirmed. `SM-D2` rests on it and `REQ-004`'s retirement is proposed because of it. The reference also **contradicts itself**: `instances.jsx:109` says *"An instance is declared here and deployed by your own CI/CD — Solution Manager applies configuration, never artifacts."*

**Correct flow:** offer *accept the runtime* (update the declaration to the observed version, re-render config against **its** schema with a field-level diff) and, for the other direction, **instruct-and-recheck** — tell the operator to deploy the declared version through their own pipeline, then re-observe. No SM-initiated deploy, no SM-owned outage.

## E2 — CRITICAL · `deferred.jsx:50` misstates LOCKED `D10`

**Reference says:** *"Locked verbs (v1): `solution.read`, `solution.configure` … The only two currently grantable."*

**Verified:** neither verb exists anywhere in this repo. `D10`'s two grantable verbs are **`instance.bindTarget`** and **`target.use`** ([`DECISIONS.md`](../../DECISIONS.md) `D10`) — which `admin_data.jsx:10,16` states correctly.

So the artifact contains **two Admin screens making disjoint claims about the same LOCKED decision.** The `deferred.jsx` one is dead code — loaded at `Solution Manager v2.html:33` before `admin.jsx` and never assigned to `window` — but it is source text in a document adopted as specification, and a reader citing it would misstate a locked decision.

**Correct:** treat `admin_data.jsx` as authoritative on the verb catalog. The `deferred.jsx` `AdminScreen` should be deleted from the artifact at its next revision.

## E3 — HIGH · `explore.jsx` aggregation wiring contradicts `DM-D8` / `DM-D22` three ways

| Line | Reference does | Contract says |
|---|---|---|
| `:32` | redefines `AGGREGATION_NOT_APPLICABLE` as *"needs at least two instances to combine"* | it means **incoherent aggregation for the metric type** ([dynamic-metrics/design.md](../../dynamic-metrics/design.md) §, `DM-D8`) — same code, different semantics |
| `:67` | fires `STAT_REQUIRES_AGGREGATION` at **any** instance count | `DM-D22` scopes it to **>1 instance without aggregation** |
| `:136` | **disables** the aggregation select when gauge/stat is chosen (`disabled={forceAgg}`) | `DM-D22`: the control **forces a non-`none` value** when either mark is selected |

**Combined effect:** pick one instance and the gauge mark → a blocking error whose only remedy control is disabled. **A single-instance gauge is uncreatable** — while the shipped default dashboard renders exactly that widget (`dashboards.jsx:18`, `w4`). Unlike the `422`-body invention beside it, none of this is flagged in-screen.

**Correct:** `DM-D22`'s wiring. The reference's error copy in `ERR_TEXT` (`explore.jsx:30-36`) is still the approved operator wording — it is the *triggering conditions* and the disabled state that are wrong.

## E4 — HIGH · `charts.jsx:9` paints every chart in Tableau 10, not Solace

**Reference says:** `PALETTE = ["#4E79A7", "#59A14F", "#B07AA1", "#9C755F", "#EDC948", "#76B7B2", "#FF9DA7", "#8CD17D"]` — the Tableau 10 categorical ramp.

**What governs instead:** `FE-D26`. The verified upstream palette carries a dedicated chart ramp — `#3C69E1`, `#3A4880`, `#009193`, `#7841A8`, `#CB1AA5`, `#F66651`, `#FCA829`, `#7CD3F6`, `#86939E`, `#DA162D` (see [`PALETTE-PROVENANCE.md`](PALETTE-PROVENANCE.md)) — already transcribed as `tokens.accent` in `frontend/src/shared/theme/tokens.ts`. **None of the mockup's eight series colours appears in it.**

Earlier "no invented paint" checks covered `frontend/` literals and the chip tones; nobody checked the series palette *inside the reference*. Added to open item 19b as a designer question.

**Correct:** the verified `accent` ramp. Series colour is also unstable across widgets — see E9.

## E5 — HIGH · `dashboards.jsx` uses a two-layer dashboard model; `DM-D5` defines three

**Reference says:** `package-baseline` and `operator` layers (`:15-21`, filter at `:104`; *"added to the operator dashboard layer"*, `explore.jsx:161`) — reusing the **metric-declaration** layer vocabulary (`PackageMetric.layer`).

**What governs instead:** `DM-D5` — **`product-default` / `shared` / `personal`**. The `personal` layer is the exact clause that supersedes `SM-D31` and must ratify **after** it. The reference neither uses those names nor shows the contested layer, and its findings panel (`:118`) flags only the envelope inventions.

Two closed vocabularies for "layer" now exist with overlapping-but-different members — the drift `UI-D14` exists to prevent.

**Correct:** whichever ratifies. Either way the reference's layer chips and copy need renaming, and the `personal` layer needs a decision before entity/tenant/personal UI is built.

## E6 — MEDIUM · `login.jsx:59` promises on-device draft persistence nothing supports

**Reference says:** *"Your in-progress configuration edits were saved on this device and will reload after you sign in."*

**What governs instead:** `FE-D17` keeps Designer drafts in React Hook Form (memory). `FE-D21` covers only Explore drafts. `FE-D24` drops state and returns to login. Nothing persists a config draft.

The header comment at `login.jsx:5` flags it as an assumption — but under `FE-D25` the *copy* is contractual, and it carries an unexamined security cost: a config draft can hold sensitive non-secret values, or a secret typed but not yet converted to a `secretRef`, persisted unencrypted on a shared operator workstation (`I6` in spirit, if not yet in letter).

**Correct:** either design local draft persistence deliberately — with a threat model — or change the copy. Do not transcribe it as-is.

## E7 — MEDIUM · `charts.jsx:153` re-derives gaps client-side

`SeriesPath` breaks the line when point spacing exceeds `gapThreshold * 1.5` — a second, client-side gap evaluator with an invented ×1.5 multiplier, layered on top of the server's `gaps[]` which the same file also honours at `:151`.

`DM-D16` exists to forbid exactly this: *"Making the server the single evaluator is stronger… they cannot disagree, because only one of them decides."*

**Correct:** render `gaps[]` only.

## E8 — MEDIUM · `dashboards.jsx:79` demonstrates `DM-D17` flooring as a no-op

`const floored = Math.max(refreshS, bucketS >= 3600 ? refreshS : refreshS)` — always `refreshS`, and `floored` is never used. The interval at `:80` polls at the raw 10s/30s option against an hourly bucket. The comment claims flooring; the behaviour contradicts `DM-D17` (*"floored at the served `bucketSeconds`"*).

In a specification of record a fake demonstration reads as approved behaviour.

**Correct:** floor the refresh at the served `bucketSeconds`.

## E9 — MEDIUM · series colour is positional, so an instance changes colour between widgets

`charts.jsx:78` assigns palette entries sequentially per result set. The same instance therefore wears different colours in adjacent widgets on one dashboard. For an operator reading an incident across widgets this is a correctness hazard, not a cosmetic one, and no decision covers colour identity.

**Correct:** needs a decision — stable colour keyed by series identity. Routed as a new open item.

## E10 — MEDIUM · `designer_panels.jsx:202` branches on `model.isFlagship`

The health-gate restart strategy is disabled `if (model.isFlagship)`, with hardcoded Cache prose (*"its standby reports healthy while processing nothing"*).

The **condition is real** (open item 15). The **mechanism is the anti-pattern** `REQ-001` exists to reject: a solution-identity flag plus screen-embedded copy, inside the one component meant to render every solution. No contract field carries it — `contracts/view-models/src/types.ts` has no `isFlagship`, and the restart-strategy vocabulary itself (`designer_data.jsx:193`) has no contract home either.

**Correct shape:** the package declares something like *health-gate-unsuitable + reason*, and the Designer renders whatever it is told. Routed as a new open item.

## E11 — LOW · `explore.jsx:7` hardcodes `SERIES_CAP = 20`

That is the **top** of `DM-D7`'s configurable range, whose default is **10**, and `DM-D10` says the UI hardcodes no cap at all. A transcriber will carry 20 as *the* cap.

**Correct:** read the cap from server capabilities.

## E12 — LOW · `designer_data.jsx`'s "keys repeat" premise is already defined away

The comment and the bare duplicate `ENABLED` keys per component would fail **gate 9** (key uniqueness, `contracts/validation/validate-package.py`). The real File Mesh fixture uses qualified keys (`file.source.ftp_settings.enabled`), and `project()` addresses everything by bare key. The mockup's `compGetter` first-wins disambiguation (`designer.jsx:19`) models a problem the contract has already solved.

`FE-D25` disclaims *data shapes*, but "keys repeat" is presented as a design **premise**, not as data.

**Correct:** qualified keys, as the fixtures use.

## E13 — MEDIUM · `governance.jsx:57` makes one control both a view filter and a production mutation

The log level `<select>` reads as a filter sitting beside the component filter, but choosing `DEBUG` triggers a confirm and then the **audited `loggers` POST** — it changes the log level of a running production process. Two different kinds of action share one idiom, and the destructive one is the less obvious.

Separately, `:53` selects a **component** but not a **replica**, while the instance above it has three. `UI-D10`'s `/actuator/logfile` is **per-process**, so it is undefined whose file streams.

**Correct:** separate the view filter from the level mutation (the mutation is a deliberate, permissioned, audited action — `FE-D9` says high-consequence verbs are not a tease), and add the replica dimension or state explicitly which replica is authoritative. Routed as open item 31.

## E14 — HIGH · `designer.jsx:316` renders a blocked instance as "Version OK"

The `VersionPill` map has arms for `OK`, `VERSION_DRIFT` and `VERSION_UNVERIFIED`, then falls back: `map[v.state] || map.OK`.

**There is no `VERSION_UNKNOWN` arm.** So an instance whose running version no package covers — `VERSION.unknown`, which is `tone: "err"`, `edit: false`, `blocked: true`, and the one state that **blocks config editing entirely** — renders a green *"Version OK"* chip.

`UI-D12` makes `VERSION_UNKNOWN` a blocking state precisely because monitoring continues while editing must not. A pill that says "OK" over a blocked instance inverts the signal at the exact moment the operator needs it.

**Correct:** add the `VERSION_UNKNOWN` arm (`err` / `ban` / "Version unknown") and make the fallback **fail loud** rather than defaulting to OK — an unrecognised state is a bug, not a healthy instance.

---

## What is NOT errata

The mockup flags many of its own gaps in-screen and those flags are **correct and valuable** — the `409` resolution controls, the dashboard document shape, the per-replica row schema, the `422` error body, `partial.unavailable[]`, the metric catalog's missing `firstSeen`, the absent unit vocabulary. Those are honest "invented — needs a product decision" notes, not errors.

⚠️ **But a JSX comment is not a route.** None of them had reached [`DECISIONS.md`](../../DECISIONS.md), so they were invisible to ratification — which is the failure mode this repo's own rules exist to prevent (*"anything undesigned is explicitly routed — never silently absent"*, `spec-review.md`). They are now open items; see the register.
