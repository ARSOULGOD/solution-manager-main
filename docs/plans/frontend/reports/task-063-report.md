# FE-063 · Composer canvas (multi-component solutions) — Report

## What I implemented

Reference: `designer.jsx`'s own `Composer`/`InspectorForm` (`docs/design/frontend/reference/app/designer.jsx:349-417`),
`designer_data.jsx`'s `fileMeshModel()` (palette/nodes/role shape, `:113-151`), and `theme.css`'s
`.comp-body`/`.palette`/`.node`/`.inspector` rules (`:426-445`).

| File | Summary |
|---|---|
| `frontend/src/features/designer/Composer/nodeCanvas.ts` (+ `.test.ts`) | New. Pure logic: default node layout (columned by role — source left, sink right, anything else in the middle — stacked top-to-bottom within a column), the derived source×sink link set, the keyboard-nudge step function, and `isComposerEligible` (the package-capability gate: `componentSelection.length > 1`). |
| `frontend/src/features/designer/Composer/composerSectionExpansion.ts` | New. `defaultExpandedSections` — sections start expanded unless all-advanced, the same default the reference's `InspectorForm` uses. Shared by the inspector and the list view so both start usable without an extra click per section. |
| `frontend/src/features/designer/Composer/composerIcons.tsx`, `composerRoleLabel.ts` | New. Role-keyed (never type-keyed) decorative icon and label, shared by the palette, canvas nodes, inspector header and list-view group headers. |
| `frontend/src/features/designer/Composer/ComposerPalette.tsx` | New. The left pane: `pkg.components` rendered read-only (no wire endpoint exists to add an instance — `I1`), each with the "generated from the package's declared component types" caption. |
| `frontend/src/features/designer/Composer/ComposerNode.tsx` | New. One canvas node — a native `<button>` for click/keyboard selection, a nested pointer-drag handle, and an `onKeyDown` that intercepts only the four arrow keys for a keyboard-equivalent move. Shows field count and either an `ok` chip or the required-and-empty count. |
| `frontend/src/features/designer/Composer/ComposerInspector.tsx` | New. The right pane — the selected node's own `project()`-scoped `ViewModel`, rendered through the unmodified `FieldSearch`/`SectionCard`/`FieldRow` (tasks 048–051). |
| `frontend/src/features/designer/Composer/ComposerFieldList.tsx` | New. The required non-pointer path ("Composer / All fields" toggle) — one group per composed instance, each with its own component header and `SectionCard` list. |
| `frontend/src/features/designer/Composer/Composer.tsx` (+ `.types.ts`, `index.ts`) | New. Wires the three panes plus the view toggle; owns node-position and selection state; bails to `null` when `!isComposerEligible`. |
| `frontend/src/features/designer/screenRegistry.ts` (+ `.test.ts`) | New. The `D1`/`UI-D21` named-screen registry — empty by construction, `resolveNamedScreen` always `undefined`. |
| `frontend/src/shared/i18n/messages.en.ts` | 17 new keys for the palette, canvas hint, node labels/counts, and view toggle. |
| `frontend/src/features/designer/Composer/{Composer,nodeCanvas}.test.ts(x)`, `Composer.a11y.test.tsx` | New. 34 tests total (component + pure-logic + a11y). |

No page exists yet to mount `Composer` into — confirmed by search before starting (no `DesignerPage.tsx` anywhere;
`InstanceShell.tsx`'s `designer` category still renders a placeholder). Every prior Phase 4 task since 046 shipped
the same way: a standalone, fixture-tested component under `src/features/designer/`, not a page. `Composer` follows
that pattern exactly, ready for whichever task first assembles the real Designer page.

## Design decisions, and why

1. **No `ViewSection`/`ViewModel` contract change.** `SectionNav.tsx`'s own module doc (task 050) and
   `FieldSearch.tsx`'s (task 051) both flag that `ViewSection` carries no component-instance identity and name
   FE-063 as the task that would need to fix it. I didn't: the inspector and the list view both call `project()`
   scoped to a **single-entry** `componentSelection` — exactly the pattern `useDesignerDraft.ts`'s own
   `visibleInstanceValues()` (task 052) already established. From `FieldSearch`/`SectionCard`'s point of view, a
   single selected node is indistinguishable from a single-component solution, which is the exact case those
   components already handle correctly. This avoids touching `contracts/view-model/types.ts` and `project.ts` —
   shared with a future Java port — for a problem that scoping already solves.

2. **`useSectionJump` (task 050) is not reused inside the composer.** It bundles accordion `expanded` state with
   scroll-into-view/focus for a nav rail neither pane has, and wiring its `containerRef` into a real DOM node
   triggered the `react-hooks/refs` ESLint rule ("passing a ref to a function may read its value during render") —
   this is the **first** place in the codebase that actually attaches that ref to JSX (no page existed to do it
   before). Rather than fight the rule for behaviour I don't need, both `ComposerInspector` and
   `ComposerFieldList` own a plain local `Set<number>` for `expanded`, matching `SectionCard`'s own stated
   contract ("accordion state is owned by the caller").

3. **Two `useEffect`s I originally wrote (resetting node positions / selection when `componentSelection` changes)
   were rewritten as "adjust state during render"**, not effects — `react-hooks/set-state-in-effect` flagged
   calling `setState` synchronously inside an effect body. Both are now plain computations during render (compute
   `missingPositions`/`stillSelected`, call the setter only when something actually changed, and use the
   **effective** value for this render's own output), the pattern React's own docs prescribe for "adjusting state
   when a prop changes" without an extra effect-then-render round trip. A dragged node's position is never reset
   by this — only a *missing* instance id gets a default position merged in.

4. **The derived link is every declared `source` paired with every declared `sink`** (`nodeCanvas.ts`'s
   `derivedLinks`), not hard-coded to "exactly one of each". The contract carries no edge/link data at all
   (`ComponentSelectionEntry` has no such field) — `UI-D20`'s own rationale is that File Mesh's real
   `CONNECTOR_DESIGN` links are themselves derived deterministically from the component set, so a cross-product
   is the only link this component could possibly persist, and it never does (positions and links are both
   presentation-only).

5. **The palette has no add-to-canvas affordance.** The reference's own palette items are captioned "prototype:
   static" — there's no wire endpoint to add a component instance to a running solution, and inventing one would
   be new MI-instance authoring the design doesn't specify. Each item states `composerPaletteItemUnavailable` via
   its `title` rather than looking interactive and doing nothing.

6. **Node field-count/required-count badges come from `groupSectionFields`** (`SectionCard/sectionFieldGroups.ts`,
   task 049/050's own shared arithmetic), summed across a per-node `project()` call — not a new counting rule.
   `requiredEmptyCount` is `blockCount`: a field carrying a `bind`-tier validation, which — per `project.ts`'s own
   `evaluateValidations` — only fires when the field's **current** value is empty, not merely because it's
   declared `required`. `Composer.test.tsx`'s node-label assertions and the "selects a node" test both exercise
   this: `REMOTE_PATH` (required, but filled by the draft) never carries the asterisk/required styling that
   `TOPIC` (required, empty) does.

7. **`Composer` itself also gates on `isComposerEligible`, not just the future caller.** The task's own line reads
   "build behind a package-capability check"; a caller-side gate is the primary mechanism, but a defensive
   `return null` inside the component costs one line and prevents a one-node canvas (no derived link, nothing the
   flat form doesn't already show) from ever rendering by mistake.

8. **Icons are role-keyed, never type-keyed** (`composerIcons.tsx`): `source`/`sink`/anything-else, the same rule
   the palette's own caption states in prose ("never a hard-coded FTP/SFTP/S3 list") — a per-`type` icon lookup
   table would have been exactly that list under a different name.

## Testing

Every command run from `frontend/`, output inspected:

```
npx vitest run src/features/designer/Composer src/features/designer/screenRegistry.test.ts --environment jsdom
  # 4 files, 34/34 passed

npm run lint          # clean, --max-warnings=0
npm run typecheck     # clean
npx prettier --check <every file this task touched or added>   # clean (after one --write pass)
npm run build         # production build succeeds
npm test              # full suite: 214 files, 1030 tests — 1029 passed, 1 failed
```

**The 1 full-suite failure is not from this task.** It's `src/features/designer/ReplicaTable/ReplicaTable.a11y.test.tsx`
(untouched by this task) — an axe-core timeout under full-suite parallel load, the same pre-existing flake
FE-061's and FE-062's own reports already documented. Re-ran it in isolation: `1 file, 2/2 passed`, confirming it's
unrelated to this change.

Coverage highlights (`Composer.test.tsx`, `nodeCanvas.test.ts`, `Composer.a11y.test.tsx`):
- Renders nothing for a single-component selection (the capability gate).
- Palette is derived from `pkg.components`, never a hard-coded list.
- One canvas node per composed instance, labelled with its own field/required-empty counts.
- Selecting a node scopes the inspector to that component only (no cross-contamination between source/sink fields).
- A pointer drag and a keyboard arrow-key nudge **both** leave `onFieldChange` uncalled — moving a node never
  dirties the form.
- An inspector edit routes to the correct `instanceId`.
- The "All fields" toggle renders every composed component's fields with no node selection required (the
  non-pointer path).
- `nodeCanvas.ts`: layout columns, derived-link cross product (including the zero-link single-component and
  fan-out multi-sink cases), arrow-key-to-direction mapping, nudge clamping at zero, and the eligibility gate.
- axe: canvas view and list view, light and dark.

## Status

Complete. `Ready: FIXTURE` per the plan — no wire endpoint exists for adding a component instance (palette is
read-only by design), and there is still no page to mount `Composer` into (a pre-existing gap this task doesn't
close; every Phase 4 task since 046 has shipped the same way).

Commit range: `f476980`
