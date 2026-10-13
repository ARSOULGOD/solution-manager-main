# Frontend visual reference — Solution Manager v2

**This folder is the source of truth for UI screens and visual styling** (`FE-D30`).

**Source:** Claude Design project `Solution Manager`, exported and imported 2026-08-19 ·
**Reviewed and approved by:** Parikshit Tyagi

Open [`Solution Manager v2.html`](./Solution%20Manager%20v2.html) in a browser. It is a
self-contained React prototype — no build step, no network — covering login, home, solutions,
instances, designer, governance, monitoring dashboards, explore and admin.

`FE-D30` proposes that this artifact **stands in for the design mockup that `FE-D5` records as
deleted**, and it is `FE-D30` — not `D7` — that this folder is cited through. That distinction is
deliberate. `D7` is `LOCKED` and predates this artifact; describing it as the artifact `D7` refers
to would shift a locked decision's meaning in place, which `I3` forbids. `D7`'s instruction —
*"express the mockup's visual design as an MUI theme"* — is honoured by treating this as a mockup
and rebuilding it in MUI; which artifact fills the "mockup" slot is a separate, new, **proposed**
decision.

## What it is authoritative for

Layout · spacing · component structure · navigation · tables · forms · dialogs · visual
hierarchy · the complete colour palette (light **and** dark) · typography · elevation · motion.
Also **copy, state vocabulary, and empty/error states**.

Implementation follows this design language. **Where the built console and this reference disagree
on layout, copy, state wording or nav structure, this reference wins** — unless a `FE-D*`, `UI-D*`
or `DM-D*` decision says otherwise, in which case flag the conflict rather than resolving it
silently (`I4`). Do not introduce a different visual style unless a new requirement forces it —
and record that as a decision when it happens.

## 🔴 Read [`ERRATA.md`](ERRATA.md) first

**Twelve places in this artifact are wrong**, and "the reference wins" would otherwise launder them
into the product. Two are critical:

- **E1** — `app/designer.jsx:303`'s *"Restore the runtime"* has SM **deploy an artifact and restart
  the target**. SM is a config plane, not a deployment plane — the one thing the architect group has
  confirmed. The artifact contradicts **itself** here (`app/instances.jsx:109`).
- **E2** — `app/deferred.jsx:50` states LOCKED `D10`'s grantable verbs as `solution.read` /
  `solution.configure`. Neither exists; they are `instance.bindTarget` and `target.use`.

The rest cover Explore's aggregation wiring, the chart palette, the dashboard layer model, a login
promise no decision supports, client-side gap re-derivation, a fake refresh-flooring demo,
positional series colour, a per-solution `isFlagship` branch, a hardcoded series cap and the "keys
repeat" premise. **`ERRATA.md` overrides `FE-D30` for the lines it names.**

## ⚠️ Read [`STATE-VOCABULARY.md`](STATE-VOCABULARY.md) before building anything stateful

The state vocabulary lives in **eight tone/label tables plus one ordered ladder**, spread across
five files. It has been transcribed verbatim into [`STATE-VOCABULARY.md`](STATE-VOCABULARY.md),
which exists because it was once reconstructed from a *derived document* rather than from source and
was wrong in membership, tones *and* labels. An adversarial pass then found that the first version
of the transcription had itself missed two tables — `STATUS_META` (Admin RBAC) and `CAT_STATE`
(Metrics Explore) — so the count is stated to stay checkable. `census-vocabularies.py` re-runs the
census; its wider sweep counts nine structures and 37 entries across six files, because it also
picks up the inline map at `app/designer.jsx:316`. **Reconcile the two before relying on either
number.**

Three traps it documents:

- **Collapsing tables loses deliberate distinctions.** `app/data.jsx:21`: *"four conditions, plus
  the bootstrap case. Never one 'missing' badge."* `unreachable` / `not_exposed` / `missing` /
  `bootstrap` are four operator situations with four remedies.
- **Tone is not severity.** `VERSION.drift` is `tone: "warn"` *and* `edit: false`. Blocking and red
  are separate axes; never infer one from the other.
- **The same concept is labelled differently per context.** `inactive_replica` is *"Inactive
  replica"* in `HEALTH` but *"Standby · idle"* in `GOV_HEALTH`. One shared label per concept would
  be a regression.

## What it is *not* authoritative for

| Not covered | Governed instead by |
|---|---|
| **Responsive behaviour** | `FE-D29` · `G16` · Phase R (plan tasks `105`–`110`). The prototype is fixed at 1280 px: `.shell` is `min-width: 1280px`, the data grid `min-width: 1180px`, and the page declares `<meta name="viewport" content="width=1280">`. The only media query in 605 lines is `prefers-reduced-motion`. **Narrower layouts are derived from these components and tokens, not from this folder.** Do not copy `min-width: 1280px` into production. |
| **Stack and architecture** | `D7` (MUI) · `FE-D*` · [`../../../plans/frontend/implementation.md`](../../../plans/frontend/implementation.md). The prototype is hand-rolled CSS + Babel-in-browser because it is a *drawing*, not a starting codebase. |
| **Behaviour and state semantics** | [`../design.md`](../design.md) and implementation plan Appendix A. Where a screen here contradicts a stated behaviour rule (gaps, `APPLY_INDETERMINATE`, restart expectations), the rule wins. |
| **Data** | Everything rendered is fixture data from `app/data.jsx`, `app/designer_data.jsx`, `app/admin_data.jsx`. **Shapes are illustrative, not a contract.** |

## What this is NOT

Not shippable code. Nothing here is built, linted, type-checked or imported by any application. It is:

- React 18.3.1 UMD from `unpkg`, transpiled **in the browser** by `@babel/standalone`
- 22 `app/*.jsx` files, 3,378 lines, sharing globals via `window.*` — no modules, no types
- a hand-rolled `useHashRoute`, and `localStorage` for the authenticated flag
- static mock data
- painted with plain CSS (`theme.css`, 51 KB), not MUI

Each of those is replaced per the `FE-D*` set: strict TypeScript (`FE-D15`), MUI (`FE-D4`), React Router (`FE-D6`), TanStack Query (`FE-D1`), Zustand (`FE-D2`), Vite (`FE-D14`).

## Layout

| Path | Contents |
|---|---|
| `Solution Manager v2.html` | Entry point — hash router, demo-state toggle, screen switch. The `<script>` order documents module dependencies |
| `theme.css` | The console layer: tokens, components, dark mode (605 lines), including tones (`idle`, `purple`) beyond the semantic four |
| `app/*.jsx` | 22 modules — `kit.jsx` is the component library; the rest are screens and fixtures |
| `app/kit.jsx` | **19 primitives**, exported at line 151 — the mapping target for `shared/ui`. Its own header reads *"MUI-structured primitives painted with Solace tokens"* |
| `app/data.jsx` | the state vocabulary — **four tables**: `HEALTH`, `NOTFOUND`, `VERSION`, `CONFIG`; plus `ENV_TONE` and the 7-row `ATTENTION` fixture |
| `app/instance.jsx` | `GOV_HEALTH` table, `InstanceHeader`, `Toaster` |
| `app/designer_data.jsx` | `REPLICA_HEALTH`, the `CONFIG_STATES` apply ladder, `SECRET_MASK` |
| `app/designer_fields.jsx` | `SecretControl` — the **write-only** stored-secret pattern (`FE-D23`) |
| `app/admin_data.jsx` | `STATUS_META` table (permission-catalog status) + the RBAC fixtures: `PERM_GROUPS`, `RBAC_ROLES`, `SCOPE_TREE`, `LOCAL_USERS` |
| `app/explore.jsx` | `CAT_STATE` table (metric-catalog status) + the Explore screen |
| `app/grid.jsx` | `DataGrid`, `toCSV`, `downloadCSV` |
| `app/charts.jsx` | `TimeChart`, `GaugeMark`, `SingleStatMark`, `Legend` |
| `_ds/solace-agent-mesh-design-system-659eb3ca…/` | **Agent Mesh DS** — `colors_and_type.css` is the **verified** palette/type source `theme.css` builds on; see [`PALETTE-PROVENANCE.md`](PALETTE-PROVENANCE.md) |
| `_ds/solace-brand-book-design-system-2b582b3d…/` | **Solace Brand Book v2.9** tokens — identity palette, provenance **not** established; context only, see below |
| `screenshots/` | 18 stills — see the curation rule below |
| `assets/` | `solace-logo-full.svg`, `solace-logo-s.svg` |
| `census-vocabularies.py` | Re-runs the state-vocabulary census over `app/` |

## Token authority chain

```
_ds/…agent-mesh…/colors_and_type.css   →   theme.css   →   src/shared/theme/tokens.css
        (brand palette + type)          (console layer)        (production, plan task FE-002)
```

Values are transcribed in implementation plan **Appendix B**; that transcription was verified
accurate against `theme.css`. `FE-D5` still marks brand paint **BLOCKED** pending architect
acceptance of open item 19 — `FE-D30` names this the working reference, and `FE-D52` records that
the palette itself is **verified against Solace product source**, which are three different claims.
**A PR that treats these values as brand-final before that acceptance is a defect.** Theme work runs
under the [`../GATE-2-WAIVER.md`](../GATE-2-WAIVER.md) waiver, which is **unsigned**.

## Known divergences and open flags

- ⚠️ **Two design systems disagree.** The Brand Book and the Agent Mesh DS agree on brand green
  `#00C895` but differ on the dark blue — Brand Book `--blue-deep #093B5F` / `--blue-dark #03213B`
  versus Agent Mesh primary `#015B82` / background `#03223B`. The prototype links **only** the
  Agent Mesh file, so under `FE-D30` the Agent Mesh chain wins. The Brand Book is checked in for
  context and is **not** wired up. Not resolved here — flagged for design.
- ⚠️ **Primary button colour.** The Agent Mesh guide says brand green is not a primary-button
  colour; its own button specimen renders one in green. `theme.css` uses marine `#015B82` for
  primary actions and reserves green for accents, focus and the active-nav marker. Under `FE-D30`
  the reference wins: **marine**. Confirmation still owed by design (plan §16 Q1).
- ⚠️ **Chart palette.** The charts here use the **Tableau 10** colour-blind-oriented set
  (`#4E79A7 #59A14F #B07AA1 #9C755F #EDC948 #76B7B2 #FF9DA7 #8CD17D`), none of which appears in the
  verified Solace palette, which carries its own chart ramp. **`FE-D52` wins on paint; `FE-D30`
  wins on layout and copy** — errata **E4**. Plan task `097` must validate whichever set ships at
  ≥ 3:1 against **both** surfaces. **Neither set is validated today.**
- Fonts are `@import`ed from Google Fonts by the DS file. `REQ-003` includes air-gapped
  deployments, so plan task `FE-002` **self-hosts** Rubik / Inter / JetBrains Mono instead.

## Screenshot curation

The export carried 67 stills: 16 distinct screens across up to 7 numbered iterations each, plus
three unnumbered. Imported here: **the highest-numbered iteration of each of the 16 screens**, plus
`rbac-check.png` and `rbac-created.png` — 18 files. Superseded iterations and `fix.png` were dropped.

## Deliberately not imported

| Not imported | Why |
|---|---|
| `app/`, `design_handoff_solution_manager/` (+ its `console.css`, `Solution Manager.html`) as delivered | Superseded prototypes predating this artifact |
| `uploads/` (36 files) | Design *inputs* — screenshots, `architecture.md`, sample config files. Already in this repo or not design output |
| 48 superseded screenshot iterations, plus `fix.png` | See curation rule above |

## Provenance

Delivered as a design bundle; `_ds/` and the prototype were separated on arrival and are rejoined
here. Two edits were made to what was delivered, both mechanical:

1. The `_ds/` folder was moved inside this directory and the stylesheet `href` changed from
   `../_ds/…` to `_ds/…`, so the folder is self-contained. Without it, five tokens
   (`--font-sans`, `--font-mono`, `--background-w20`, `--secondary-text-wMain`,
   `--secondary-wMain`) resolved to nothing and the prototype rendered with no Rubik and no page
   background.
2. This README was added.

`colors_and_type.css` sha256 `eef234c1a96bf183d51d722190c63de5d7e83b0093a44d966cce53f2cbba2793`.

Previously named `sm-v2/` and excluded by `.gitignore`; renamed to this folder's root and committed
2026-08-19 under `FE-D30`, since an untracked folder cannot be anyone's source of truth. A second,
un-renamed `sm-v2/` copy arrived on the `contracts-backend-consumption` branch and was removed when
the branches merged — it was byte-identical apart from the `../_ds/…` href this layout fixes.
