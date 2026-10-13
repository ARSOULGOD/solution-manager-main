# FE-115 · Declare an instance from a form — Report

## What this closes

Task 045 shipped a `DeclareInstanceDialog` that **refused to render a form**. Its header, the
page's header and `instancesRepository.ts`'s header all carried the same reasoning: `§16 Q10`
defines no declare API, no field set and no entitlement-check shape, so a form would be inventing
a contract. `declare()` was marked "intentionally ABSENT — do not invent one".

**Q10 is still open. What changed is the decision, not the spec.** The console now asks for the
identity fields an operator can know before anything has been observed, and says plainly, in the
dialog itself, that nothing yet backs them. All three comment headers were rewritten to state
that — a comment forbidding what the code beside it does is worse than either.

## The line the form does not cross

It asks for **identity only**. Every observed fact — health, config state, `version.observed`,
`lastSeen` — is absent from the form by construction and derived server-side as *not yet
observed*. Inventing a field set is a small debt with a named owner (`Q10`); letting an operator
type a health status would be inventing **observations**, which is a different and much worse
thing.

Configuration is not collected here either. The package's own `components[].fields[]` belong to
the Designer, which renders them from the package through `project()`; duplicating them in this
dialog would make one field editable in two places with no rule for which wins.

## Files

| File | Summary |
|---|---|
| `src/shared/api/contracts/instances.ts` | **New.** `DeclareInstanceInput` — the hand-typed wire shape, same seam and same "replaced wholesale" rule as `common.ts`. Carries `CONTRACT: TBD`. |
| `src/shared/testdoubles/data/declaredInstance.ts` | **New.** `readDeclareInput()` + `buildDeclaredInstance()` + `unobservedRuntimeFacts()`. The single definition of what a just-declared instance looks like, shared by the default handlers and the dev store. |
| `src/features/inventory/instances/declareForm.ts` | **New.** Form state, defaults and validation — pure, so every rule is asserted without rendering a dialog. |
| `src/features/inventory/instances/DeclareInstanceDialog.tsx` | Rewritten from the stub into an eight-control form. |
| `src/features/inventory/instances/instancesRepository.ts` | `declare()` added — `postWithCorrelation`, since the dialog reports its own outcome as a toast and `ToastInput.correlationId` is required. |
| `src/features/inventory/instances/InstancesPage.tsx` | `useMutation`, invalidate, toast, and `?focus=` on the new row. |
| `src/app/dev/devDeclaredInstances.ts` | **New.** The session store — `sessionStorage`, revalidated on rehydration. |
| `src/app/dev/devPackageHandlers.ts` | `POST` route; `allInstances()` merges declarations; the instance and Designer routes resolve them. |
| `src/app/dev/derivePackageCatalog.ts` | `derivePackageInstance()` now reuses `unobservedRuntimeFacts()` instead of restating it. |
| `src/shared/testdoubles/handlers/dp.ts` | A **stateless** `POST` for the default fixture set. |
| `src/app/main.tsx` | Revalidates the declared store at boot, alongside the package store. |
| `src/shared/i18n/messages.en.ts` | Two refusal strings out, 25 form strings in. |
| 3 new test files, 4 updated | 60 new cases — see Verification. |

## Rulings

1. **A second store, not an extension of `devPackageStore`.** That store holds packages and
   derives exactly one instance per package. A declared instance has the opposite provenance —
   authored, not derived — and a different lifecycle: several per solution, declarable against a
   baked-in fixture solution with no package at all, and a re-drop of the package must not discard
   them. Folding them in would mean either losing declarations on a re-drop or making a
   package-keyed map no longer package-keyed.

2. **The store persists the DECLARATION, not the row.** Found by a failing test, not by
   inspection: the first implementation stored `InstanceRow`, whose `replicas` are objects, so
   revalidation rejected every entry and nothing survived a reload. Storing the declaration and
   rebuilding the row also means the derived observable facts are re-derived on every read — a
   hand-edited `"healthy"` in `sessionStorage` cannot survive a reload. `devDeclaredInstances.test.ts`
   asserts exactly that.

3. **The default `dpHandlers` POST is stateless.** It echoes the row it would have created and
   registers nothing. A handler that accumulated rows would make every suite's `CACHE_INSTANCES`
   count depend on test order. Declarations persist only in the DEV handlers, for a browser
   session.

4. **Duplicate names are refused against the MERGED list**, fixtures included — the routes key on
   the id, and two rows sharing one is ambiguous. Checked in the dialog (a courtesy) *and* in the
   handler (the guarantee): a client-side check is never the guarantee.

5. **Submit is never disabled by validation.** Errors appear on the first Declare press, then
   track live. A disabled control with no stated reason is the failure mode this codebase's own
   empty-state copy calls out elsewhere.

6. **The dialog is `maxWidth="md"`, not the `sm` default.** `Grid`'s column count follows the
   *container's* width (task 105), and a 600px dialog never reaches the `sm` breakpoint — the
   paired fields would stack regardless of what they declare. Caught in the browser, not in tests.

7. **A blank declared version is OMITTED from the payload**, never sent as `''`, and reads `—`.
   The one fact on this form an operator may legitimately know before anything runs; absent, it is
   stated as absent.

## What is still open

- **`§16 Q10` is unchanged.** The route, the field set and the entitlement check remain
  unspecified. When Q10 closes, `DeclareInstanceInput` is the thing to reconcile against it — not
  a starting position to defend.
- **No entitlement check.** `Declare instance` is enabled for anyone who can see the page. The
  permission model (task 032) has no shape for this action yet.
- **`instancesEmptyBody` was left alone.** It still says an instance is declared by your own
  CI/CD, which stays true of production; the dialog's own alert carries the distinction.

## Verification

| Gate | Result |
|---|---|
| `npm run typecheck` | passes |
| `npm run lint` | passes, `--max-warnings=0` |
| `npx vitest run` (touched suites: instances, `app/dev`, testdoubles) | **257 passed**, 21 files |
| `npm test` (full) | 1225 passed, 1 axe timeout under pool load |
| Browser, `npm run dev` | full flow driven end to end, **no console errors** |

**The one full-suite failure is pre-existing, and was measured rather than assumed.** Stashing this
branch and running `npm test` on the clean tree produced **11 failures across 7 files**, all axe
timeouts under vitest pool load (the condition commit `20acd0d` addressed, and which
`InstancesPage.a11y.test.tsx` documents in its own header). This branch's run had **one**. The
file passes in isolation.

Browser verification covered: the dialog opening with `cache-01` defaulted off the solution; two
required errors on an empty submit; the name-format error in dark mode; a successful declare
landing a 13th row with `?focus=` on it and a correlation-id toast; the row surviving a reload;
and a duplicate name refused.
