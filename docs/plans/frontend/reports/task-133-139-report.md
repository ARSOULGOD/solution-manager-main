# Tasks 133–139 — Drop the Tenant level; Organization → Sub-organization → Environment

> **Historical report:** `D20` (2026-09-07) retained this shape but corrected its data mapping: former Tenant →
> Organization, former Organization → Sub-organization, former Sub-org removed. Current behavior is recorded by
> `FE-D53`; the details below describe the original execution.

**Plan:** [`127-hierarchy-drop-tenant-level.md`](../127-hierarchy-drop-tenant-level.md) · **Decision:** `FE-D46`
**Executed:** 2026-09-04 · **Branch:** `dev/frontend`

---

## What changed

The console's resource hierarchy is now three levels. Selection is a real dependent cascade: one query per
level, each gated on the level above it.

**Tenant as the isolation boundary was deliberately left intact.** `Identity.tenant`, `TenantDisplay`, the
app-bar chip, the `I5`/`FE-D8` comments and the two "no route carries a tenant segment" tests are untouched and
still meaningful. Only the *hierarchy level* was removed. The two senses were tangled in the original request;
`FE-D46` records why only one of them goes.

### New

| File | Purpose |
|---|---|
| `shared/testdoubles/data/hierarchy.ts` | `ORGANIZATIONS` — the single source of truth, plus three lookup helpers |
| `shared/testdoubles/data/hierarchy.test.ts` | The three-level invariant and the **anti-drift** assertion against `CACHE_INSTANCES` |
| `shared/hierarchy/hierarchyRepository.ts` + `index.ts` | `organizations()` / `subOrgs(org)` / `environments(org, subOrg)` |
| `shared/hierarchy/hierarchyRepository.test.ts` | Each seam, unknown inputs, segment escaping, abort |

### Changed (highlights)

- **`rbac.ts`** — `SCOPE_TREE` is now **derived** from `ORGANIZATIONS` + `CACHE_INSTANCES` instead of
  hand-written. `ScopeNode.level` drops `'Tenant'`, gains `'All'`; `'Sub-org'` → `'Sub-organization'`.
  Assignments rebased off the tenant root; super-admin moves to `scope: []`.
- **`ScopeTree.tsx`** — a branch is now *given* its own path rather than appending its name to its parent's.
  The root is the one node whose name is not a path segment, so selecting it yields `[]`.
- **`scopePath.ts`** — `scopePathString([])` returns the localized "All organizations" rather than `''`.
- **`AccessCheckView.tsx`** — default scope `[]`, and the `enabled` guard drops `scope.length > 0`.
- **`declareForm.ts`** — `env` becomes `Env | ''` with no default; `setOrg`/`setSubOrg` carry the reset rules;
  validation flags only the highest unchosen level.
- **`DeclareInstanceDialog.tsx`** — two free-text fields become dependent `Select`s; Environment moved *below*
  them and renders only loaded values.
- **`InstancesRoute` → `InstancesPage` → dialog** — `hierarchyRepository` threaded through, matching the
  existing route-constructs-repositories pattern.

---

## Three things worth knowing

### 1. The anti-drift test earned its place immediately

`hierarchy.test.ts`'s "resolves every triple the instance rows use" **failed on first run** and caught
`Sandbox`'s `subOrg: '—'` placeholder — the exact hole a mandatory cascade cannot hold. It would also have
caught the pre-existing disagreement where `SCOPE_TREE` knew two organizations and the instance rows carried
six. That drift is now structurally impossible: one is derived from the other.

### 2. `env` losing its default is a strengthening, not a regression

`initialDeclareForm` defaulted to `DEV` on the stated reasoning that a one-click default must never be `PROD`.
Under the cascade no environment can be defaulted at all, because the valid set is unknown until two levels
above it are answered. The guarantee got stronger: a one-click default cannot declare into **any** environment.

### 3. One e2e test was genuinely broken, and it was not mine

`journey 4` failed intermittently. Rather than assume, this was measured: **the untouched baseline failed it 2
of 3 runs** with an identical strict-mode violation. `page.getByText(title)` matched both the toast and the
saved widget, so it passed only when the widget had not yet rendered. Fixed by scoping the assertion to the
`role="region"` named *Notifications* — the toast is what that line was ever asserting about.

A separate, rarer flake remains (a random reflow or journey-3 failure roughly one full run in three, on
baseline as well as here). **It is not fixed and not caused by this work** — it is load-dependent and hits a
different test each time. Worth its own investigation.

---

## Verification

| Gate | Result |
|---|---|
| `npm run typecheck` | **clean** |
| `npx vitest run` | **328 files / 2247 tests passed** (baseline 326 / 2222 → **+2 files, +25 tests**) |
| `npm run lint` | **clean** |
| `npm run lint:fixtures:assert` | **clean** — 13 fixtures still fail on their intended rule, so `shared/hierarchy/` respects the boundary policy |
| `npm run test:a11y` | **102 files / 355 tests passed** |
| `npm run build` | **succeeds**; ECharts still isolated to its own chunk |
| `npx playwright test` | **172/172 passed** on a clean run; see the flake note above |

**`npm run format` is a pre-existing failing gate** — `prettier --check` reports **164** files on the untouched
baseline and **153** here. Not introduced by this work, and not fixed by it: nine files that an exploratory
`prettier --write` had reformatted but that this change does not otherwise touch were reverted, to keep the
diff reviewable.

---

## Deliberately not done

- **Tenant isolation** — `I5`, `REQ-003`, `FE-D8`, `Identity.tenant`, the app-bar chip.
- **`DM-D5`'s dashboard layers** (`entity`/`tenant`/`personal` in `DashboardToolbar`, `LayerBadge`,
  `SaveWidgetDialog`) — a different axis, unratified, unbuilt.
- **Free-form environment names** — `Env` stays the union behind the new interface, per `FE-D46`.
- **`docs/design/frontend/reference/`** — still shows the `Tenant` root at `admin_data.jsx:46`. The divergence
  is recorded in `FE-D46` rather than resolved by editing the reference.
- **`derivePackageCatalog.ts`'s synthesized row** still carries `org: 'Development'` / `subOrg: 'Loaded package'`,
  which are not in `ORGANIZATIONS`. It is a DEV-only package-loading path that deliberately states what it is
  rather than borrowing a real organization; the cascade does not have to offer it. Flagged, not changed.
