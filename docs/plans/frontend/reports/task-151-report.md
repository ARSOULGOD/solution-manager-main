# FE-151 · An instance binds to its component types (`FE-D55`) — Report

Third task of [plan 129](../129-filemesh-source-sink-connectors.md) (design:
[`filemesh-source-sink-connectors.md`](../../../design/dynamic-ui/filemesh-source-sink-connectors.md) §5).

---

## 1 · What was needed

`deriveComponentSelection` returned one entry per declared component — a degenerate case its own header
documents (`one-of-many` composition "is not invented"; §16 Q10, the declare contract, is open). That was
harmless while every solution's components were all composed by every instance. File Mesh breaks it: the package
declares one component per **role**, but an instance is a source **or** a sink — one Spring Boot process on one
port, never both. Without a way to say which, a source instance would render its sink's fields too.

## 2 · What changed

| Change | File |
|---|---|
| `deriveComponentSelection(pkg, version, componentTypes?)` — optional third argument | `features/designer/DesignerPage/componentSelection.ts` |
| `DesignerLoad.componentTypes?` | `features/designer/configRepository.ts` |
| The Designer passes it through | `features/designer/DesignerPage/DesignerPage.tsx` |
| **The Governance log viewer passes it through** — *not in the plan*, see §5 | `features/governance/GovernancePage/GovernancePage.tsx` |
| 7 tests for the binding; 1 for Governance | `componentSelection.test.ts`, `GovernancePage.test.tsx` |

The contract is deliberately the smallest possible:

- `undefined` — no binding declared; every version-eligible component renders. **Byte-identical to the
  two-argument call**, pinned by its own test. This is the whole backward-compatibility guarantee.
- `[…]` — only those components, **in package declaration order**, so section order cannot be altered by how a
  binding happens to be written.
- `[]` — a binding that selects nothing. Kept distinct from `undefined` on purpose: collapsing them would make
  "bound to no components" silently mean "bound to all of them".
- A bound type the package does not declare, or declares only for another version, contributes nothing. The
  function never invents an entry.

## 3 · What deliberately did not change

- **How a binding is authored.** §16 Q10 (the declare-instance wire contract) is still open. `FE-D55` defines only
  how the Designer *consumes* a binding. Nothing yet supplies one over the wire; `FE-154` supplies it from the
  fixture layer.
- **`DesignerForm`'s save path.** It spreads `...load` (`DesignerForm.tsx:187`), so `componentTypes` survives a
  save without any change there.
- **Every existing package and instance.** None declares a binding, so all take the fallback.

## 4 · Evidence

| Check | Result |
|---|---|
| `componentSelection.test.ts` before the implementation | 4 failed / 8 passed (the 3 that passed did so vacuously — Vitest does not type-check, so an ignored third argument still "works" for the fallback cases) |
| after | 12 / 12 |
| `GovernancePage.test.tsx` new test before the Governance fix | **failed on the right assertion**: `cache-engine` was offered although the instance was bound only to `management` |
| after | 11 / 11 |
| `typecheck` / `lint` | clean / clean at `--max-warnings=0` |
| Full suite | 340 / 340 files, 2424 / 2424 tests |

## 5 · Deviations from the plan

**One, and it is the finding of this task.** The plan listed the Designer as the only consumer of
`deriveComponentSelection`. Running `grep` on the callers found a second: the Governance log viewer builds its
component list from the same function. Had it been left, a bound File Mesh **source** would have offered log
levels for the **sink's** components in Governance while the Designer correctly showed only the source — two
screens disagreeing about what the instance is. It is a one-argument fix, and it is pinned by a test that was
written first and seen to fail.

The general point, for whoever adds the next consumer: **a binding is only as good as its least-updated
consumer.** Any code deriving "which components does this instance have" must go through
`deriveComponentSelection` with `load.componentTypes`, not through `pkg.components` directly. `FE-154`'s manual
check should include the Governance page for a bound instance.

**Prettier is not a CI gate here** (`.github/workflows/frontend.yml` does not run it), and
`componentSelection.ts` was already prettier-dirty at `HEAD` (a long import line). Only the lines this task added
were formatted; the pre-existing one was left alone rather than folding an unrelated reformat into this diff.

## 6 · Full-suite result

**340 / 340** files, **2424 / 2424** tests — the 2416 baseline from `FE-150` plus the 8 new (7 binding, 1 Governance), exit 0.
