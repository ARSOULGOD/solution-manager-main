# Frontend visual reference — Solution Manager v2

**Source:** Claude Design project `Solution Manager`, file `sm-v2/Solution Manager v2.html`
**Exported and imported:** 2026-08-19 · **Reviewed and approved by:** Parikshit Tyagi
**Status of this artifact:** proposed as the visual specification of record — see **`FE-D25`** in [`../decisions.md`](../decisions.md)

---

## What this is

The approved visual and interaction specification for the console: navigation, layout, state vocabulary, copy, and empty/error states.

`FE-D25` proposes that this artifact **stands in for the design mockup that `FE-D5` records as deleted**, and it is `FE-D25` — not `D7` — that this folder is cited through. That distinction is deliberate. `D7` is `LOCKED` and predates sm-v2; describing sm-v2 as the artifact `D7` refers to would shift a locked decision's meaning in place, which `I3` forbids (*"A citation like 'per D8' must never shift meaning"*). `D7`'s instruction — *"express the mockup's visual design as an MUI theme"* — is honoured by treating this as a mockup and rebuilding it in MUI; which artifact fills the "mockup" slot is a separate, new, **proposed** decision.

**Where the built console and this reference disagree on layout, copy, state wording or nav structure, this reference wins** — unless a `FE-D*`, `UI-D*` or `DM-D*` decision says otherwise, in which case flag the conflict rather than resolving it silently (`I4`).

## 🔴 Read [`ERRATA.md`](ERRATA.md) first

**Twelve places in this artifact are wrong**, and "the reference wins" would otherwise launder them into the product. Two are critical:

- **E1** — `designer.jsx:303`'s *"Restore the runtime"* has SM **deploy an artifact and restart the target**. SM is a config plane, not a deployment plane — the one thing the architect group has confirmed. The artifact contradicts **itself** here (`instances.jsx:109`).
- **E2** — `deferred.jsx:50` states LOCKED `D10`'s grantable verbs as `solution.read` / `solution.configure`. Neither exists; they are `instance.bindTarget` and `target.use`.

The rest cover Explore's aggregation wiring, the chart palette, the dashboard layer model, a login promise no decision supports, client-side gap re-derivation, a fake refresh-flooring demo, positional series colour, a per-solution `isFlagship` branch, a hardcoded series cap and the "keys repeat" premise. **`ERRATA.md` overrides `FE-D25` for the lines it names.**

## What this is NOT

Not shippable code. Nothing here is built, linted, type-checked or imported by any application. It is:

- React 18.3.1 UMD from `unpkg`, transpiled **in the browser** by `@babel/standalone`
- 22 `app/*.jsx` files, 3,378 lines, sharing globals via `window.*` — no modules, no types
- a hand-rolled `useHashRoute`, and `localStorage` for the authenticated flag
- static mock data — **shapes are illustrative, not a contract**
- painted with plain CSS (`theme.css`, 51 KB), not MUI

Each of those is replaced per the `FE-D*` set: strict TypeScript (`FE-D15`), MUI (`FE-D4`), React Router (`FE-D6`), TanStack Query (`FE-D1`), Zustand (`FE-D2`), Vite (`FE-D14`).

## Layout

| Path | Contents |
|---|---|
| `sm-v2/Solution Manager v2.html` | entry point; the `<script>` order documents module dependencies |
| `sm-v2/app/kit.jsx` | **19 primitives**, exported at line 151 — the mapping target for `shared/ui`. Its own header reads *"MUI-structured primitives painted with Solace tokens"* |
| `sm-v2/app/data.jsx` | the state vocabulary — **four tables**: `HEALTH`, `NOTFOUND`, `VERSION`, `CONFIG`; plus `ENV_TONE` and the 7-row `ATTENTION` fixture |
| `sm-v2/app/instance.jsx` | `GOV_HEALTH` table, `InstanceHeader`, `Toaster` |
| `sm-v2/app/designer_data.jsx` | `REPLICA_HEALTH`, the `CONFIG_STATES` apply ladder, `SECRET_MASK` |
| `sm-v2/app/designer_fields.jsx` | `SecretControl` — the **write-only** stored-secret pattern (`FE-D23`) |
| `sm-v2/app/admin_data.jsx` | `STATUS_META` table (permission-catalog status) + the RBAC fixtures: `PERM_GROUPS`, `RBAC_ROLES`, `SCOPE_TREE`, `LOCAL_USERS` |
| `sm-v2/app/explore.jsx` | `CAT_STATE` table (metric-catalog status) + the Explore screen |
| `sm-v2/app/grid.jsx` | `DataGrid`, `toCSV`, `downloadCSV` |
| `sm-v2/app/charts.jsx` | `TimeChart`, `GaugeMark`, `SingleStatMark`, `Legend` |
| `sm-v2/theme.css` | component paint, including tones (`idle`, `purple`) beyond the semantic four |
| `_ds/solace-agent-mesh-design-system-*/` | **verified** token source — see [`PALETTE-PROVENANCE.md`](PALETTE-PROVENANCE.md) |
| `_ds/solace-brand-book-design-system-*/` | identity palette; provenance **not** established |
| `screenshots/` | 18 stills — see the curation rule below |
| `assets/` | `solace-logo-full.svg`, `solace-logo-s.svg` |

## ⚠️ Read [`STATE-VOCABULARY.md`](STATE-VOCABULARY.md) before building anything stateful

The state vocabulary lives in **eight tone/label tables plus one ordered ladder**, spread across five files — **35 entries** in total. It has been transcribed verbatim into [`STATE-VOCABULARY.md`](STATE-VOCABULARY.md), which exists because it was once reconstructed from a *derived document* rather than from source and was wrong in membership, tones *and* labels. An adversarial pass then found that the first version of the transcription had itself missed two tables — `STATUS_META` (Admin RBAC) and `CAT_STATE` (Metrics Explore) — so the count is stated here to stay checkable.

Three traps it documents:

- **Collapsing tables loses deliberate distinctions.** `data.jsx:21`: *"four conditions, plus the bootstrap case. Never one 'missing' badge."* `unreachable` / `not_exposed` / `missing` / `bootstrap` are four operator situations with four remedies.
- **Tone is not severity.** `VERSION.drift` is `tone: "warn"` *and* `edit: false`. Blocking and red are separate axes; never infer one from the other.
- **The same concept is labelled differently per context.** `inactive_replica` is *"Inactive replica"* in `HEALTH` but *"Standby · idle"* in `GOV_HEALTH`. One shared label per concept would be a regression.

## Screenshot curation

The export carried 67 stills: 16 distinct screens across up to 7 numbered iterations each, plus three unnumbered. Imported here: **the highest-numbered iteration of each of the 16 screens**, plus `rbac-check.png` and `rbac-created.png` — 18 files. Superseded iterations and `fix.png` were dropped.

## Deliberately not imported

| Not imported | Why |
|---|---|
| `app/`, `design_handoff_solution_manager/` (+ its `console.css`, `Solution Manager.html`) | Superseded prototypes predating sm-v2 |
| `uploads/` (36 files) | Design *inputs* — screenshots, `architecture.md`, sample config files. Already in this repo or not design output |
| 48 superseded screenshot iterations, plus `fix.png` | See curation rule above |
