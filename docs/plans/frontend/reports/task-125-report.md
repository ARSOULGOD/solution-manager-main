# FE-125 · Designer reflow, accessibility sweep, and the Gate D journey — Report

## What this closes

The last task of Phase 4. Three deliverables: the reflow harness drives the real `DesignerPage` instead of
hand-built `ViewSection` literals; a genuine Gate D e2e journey (login → instance → edit → save → apply)
against real fixture data, not merely asserted; and whatever the first two turn up gets fixed, not
worked around. They turned up two real, previously-invisible bugs here — one of them significant — on top
of the one `FE-124`'s own verification already found and fixed.

## What was built

**`DesignerReflow.test.tsx` rewritten** to render the real `DesignerPage` (a synthetic 100-field/10-section
single-component package, `configRepository.loadDesigner()` stubbed to resolve it) rather than assembling
`SectionNav`/`SectionCard`/`SaveBar` by hand. Keeps task 108's own constraint — one section's ~10 `FieldRow`s
mounted at a time — but now achieved by explicitly collapsing each `SectionCard` before opening the next,
since the real accordion (unlike the old single-select stub harness) can hold several sections open at once.
Both tests pass at `reflowFloorPx` (320px): every field reachable and editable, `assertReflow` clean (no
two-dimensional scroll), and the drawer-based section jump (`SectionNav`'s own task-108 behaviour) still
expands, scrolls and focuses correctly at the floor width.

**Gate D e2e journey** (`e2e/designer.spec.ts`, new) — login → a real v6 instance (`cache-prod-03` from
`data/instances.ts`, never an invented id) → confirms `Max weight` (v7-only) absent and `Max entries`
present → fills every field the fixture draft leaves required-and-empty → saves (asserted via the real
network response: the generation moves off the documented `'gen-41'` baseline) → applies → asserts
`APPLY_INDETERMINATE`'s own copy ("Outcome not confirmed") renders, with a body-text check that no
success-verdict language appears anywhere on the page. Runs against `playwright.config.ts`'s new `fixture`
project — see below.

**`playwright.config.ts`** gains a second project. Gate D is named a "**fixture** e2e journey" in the plan
itself, but MSW and the dev-mock auth adapter are both gated on `import.meta.env.DEV` (`FE-D24`) and
dead-code-eliminated from the `preview` build the existing `smoke.spec.ts`/`login.spec.ts` run against —
there is no way to reach a fixture-backed, authenticated flow there. The new `fixture` project runs the
same suite against the **dev** server instead (`webServer` is now an array: `preview` unchanged,
`fixture` adds `npm run dev`), routed by `testMatch` so `smoke`/`login` keep running against `preview` and
`designer.spec.ts` against `fixture`. This is outside FE-125's own literal file list — flagged to the user
before building it, per the explicit trade-off (fabricating a false "backend exists" success vs. genuinely
demonstrating Gate D); the user chose the second project. `preview`'s own "no fabricated success" posture
(`login.spec.ts`'s header) is unchanged — this is a different, additional, and equally honest thing: driving
the same fixture layer the unit suite already exercises under `msw/node`, now in a real browser.

## Two real bugs found here — fixed at the root, not worked around

(A third — `RolloutCard`'s own heading-order bug, the same `SectionCard` mistake repeated — was found and
fixed during `FE-124`'s own verification, before this task started; recorded in that task's report, not
re-claimed here.)

**1. `DesignerForm` was hiding `SectionNav` outright below `md`.** `SectionNav` already reflows itself
(task 108) — a persistent rail at `lg` and up, a "Jump to section" button opening a `Drawer` below it, all
the way to 320px. `DesignerForm`'s own `navUnavailable ? null : <SectionNav/>` (added in `FE-123`)
duplicated that decision and, at the 320px floor specifically, broke it outright — no rail, no drawer
button, no jump mechanism at all. Fixed by always rendering `SectionNav` and letting the wrapping `Box`'s
own `sx` adapt to the same `lg` breakpoint `SectionNav` already uses, rather than a second, wrong,
JS-computed `useMediaQuery` check.

**2. A field whose `key` contains a dot silently never saved — found by the Gate D journey itself.**
`useDesignerDraft.ts`'s `onFieldChange` called `form.setValue(\`${instanceId}.${fieldKey}\`, value)`.
`react-hook-form` parses a dot in a `setValue` NAME string as a nested-path separator; `ComponentValues` is
a flat map where a field's own `key` may legitimately contain one — Cache's real, shipped
`contracts/packages/cache/package.json` has eleven of them (`solace.host`, `solace.vpn`, `solace.username`,
`solace.port`, `solace.tls`, `solace.messaging-mode`, `solace.enabled`, `solace.management.*`,
`cache-hit-response.*`). Typing into any of them focused the field, accepted keystrokes visibly, and then
silently discarded them on the next render — `getValues()`/`useWatch()` were reading a THREE-level nested
object (`values.cache-0.solace.host`) that `project()`/`payload` never look at, since they index
`ComponentValues` by the flat literal key. **Roughly half of Cache's 26 fields — every Broker and Management
field — were unsaveable in the running application before this fix.** No earlier unit test caught it because
every prior `useDesignerDraft` fixture used only dot-free keys (`ACTION`, `MODE`, `field_N`); this is the
first task to drive a real, dotted-key-bearing package through a real browser interaction.

Fixed by changing `onFieldChange` to `form.setValue(instanceId, { ...currentInstance, [fieldKey]: value })`
— the whole instance's `ComponentValues` object, keyed only by the dot-free `instanceId`. `react-hook-form`'s
path parser then never sees the field key at all; it only ever receives the object as an opaque value.
Pinned with a new unit test (`useDesignerDraft.test.ts`) using the real Cache package and a real dotted key,
verified to fail against the pre-fix code (temporarily reverted, confirmed red, restored) before being kept.

## Testing

- `DesignerReflow.test.tsx` — both tests pass at 320px, driving the real `DesignerPage`.
- `e2e/designer.spec.ts` — the full Gate D journey, green, against the `fixture` project.
- `useDesignerDraft.test.ts` — new dotted-key regression test, verified to catch the bug when the fix is
  reverted.
- Full suite: `npm run lint && npm run typecheck && npm test && npm run build && npm run test:e2e` — all
  green (1312 unit/integration tests, 6/6 e2e across both projects).
- Production bundle grep (`G20`, `FE-D24`): `grep -rl "msw/browser\|devPackageHandlers\|devPackageStore\|devDeclaredInstances\|CACHE_VIEW_MODEL" dist/assets/*.js` — no matches.

## Files

| File | Change |
|---|---|
| `src/features/designer/DesignerReflow.test.tsx` | Rewritten to drive the real `DesignerPage`. |
| `src/features/designer/DesignerPage/DesignerForm.tsx` | `SectionNav` no longer hidden below `md`; layout adapts via `sx` instead. |
| `src/features/designer/form/useDesignerDraft.ts` | `onFieldChange` no longer builds a dotted `setValue` path. |
| `src/features/designer/form/useDesignerDraft.test.ts` | New dotted-key regression test. |
| `playwright.config.ts` | Second (`fixture`) project + array `webServer`, targeting the dev server. |
| `e2e/designer.spec.ts` | New — the Gate D journey. |

## Acceptance

- ☑ 320px with no horizontal page scroll (`assertReflow`)
- ☑ axe clean (existing suite, plus two real violations fixed along the way)
- ☑ Gate D journey green — genuinely demonstrated, not asserted
- ☑ no dev-only code in `dist/`

## Verification

`npm run lint` · `npm run typecheck` · `npm test` (1312 tests) · `npm run build` · `npm run test:e2e`
(6/6, both Playwright projects) — all green.

## Phase 4 complete

This closes Phase 4 (`046`–`063`, `108`, `113`, `121`–`125` — 25 tasks). Gate D's own scope stands: it proves
the UX contract (save carries a generation, apply renders `APPLY_INDETERMINATE`), not `dp-remote` apply
correctness — `DECISIONS.md` open item 1 remains open, unaffected by this task.
