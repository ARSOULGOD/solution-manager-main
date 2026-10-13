# FE-104 · Documentation and plan closure — Report

## What I implemented

The closure task. Three obligations — README current, the plan's Review section filled, every
execution-time decision carrying an ID — plus the one thing a closure task must not do, which is
paper over what is still open.

Task spec: `docs/plans/frontend/implementation.md` §FE-104. Refs
`docs/checklists/definition-of-done.md`, `plan-review.md`.

| File | Summary |
|---|---|
| `docs/design/frontend/decisions.md` | **`FE-D42`**, **`FE-D43`**, **`FE-D44`** recorded as new IDs with index rows and changelog entries. Nothing edited in place (`I3`). |
| `docs/plans/frontend/implementation.md` | The **Review** section gains its Phase 8 entry: what the phase found, where the plan was right, where it was wrong, and what was found-but-not-fixed. |
| `frontend/README.md` | Status corrected; a **verification gates** table (ten commands, what each proves, which task owns it); a new **"Where the seam is"** section; an **Accessibility** section linking both checklists; the `test:a11y` flake documented where someone hitting it will look. |
| `docs/plans/frontend/task-list.md` | `101`–`104` marked built. |

## The three decisions promoted to the register

Each was taken during execution, and each constrains work that has not been written yet — which is the
test for whether something is a decision or an implementation detail.

**`FE-D42` — the logger's field type carries only primitives.** `LogValue` is
`string | number | boolean | null | undefined`, so a config value set, an `Error` (and its `cause` chain)
or a metric series cannot be logged and still compile. This is `I6` implemented as a type rather than a
redactor, with lint covering the one gap a type cannot see (a primitive under a secret-shaped *name*) and
runtime redaction the one gap lint cannot see (a key assembled at runtime). It binds every future call
site, so it belongs in the register rather than a report.

**`FE-D43` — MSW's worker ships in `public/` and the build strips it; the gate reads the artefact.**
Two decisions that only make sense together. The structural fix was built first and reverted on measured
evidence (11 e2e failures, bisected against a clean `main`), leaving a *weaker* guarantee — "the build
removes it" rather than "the build cannot reach it". What makes that acceptable is the second half:
`verify:production` walks the **written artefact**, so if the cleanup plugin stops running the build goes
red. The general rule is not about MSW: when a check's subject is "what the customer receives", the check
must read what the customer receives.

**`FE-D44` — a jsdom render budget is a ratio, not a millisecond count.** An absolute ceiling measures how
busy the machine is; the first version passed in isolation and failed in the full suite at 5× the unloaded
time. Recorded together with what these budgets are *not* — jsdom tripwires, and no browser performance
measurement exists.

## Rulings

**1 · I did not fill the Phase 4 Review stub, and left it visible.** The Review section carries
*"### Phase 4 · `FE-121`…`FE-125` — (To be filled in by the executing session)"*. Those tasks ran in a
different session; their per-task reports exist in `reports/`, but the summary of *what changed versus the
plan and why* is that session's to write. Reconstructing it from the reports would produce a plausible
paragraph nobody verified, in the one section whose entire purpose is to be trustworthy about divergence.
A visible stub is more honest than an invented summary, and it is named in the Phase 8 entry's "where the
plan was wrong or incomplete" so it is not mistaken for an oversight.

**2 · The Review section records four defects the feature phases shipped green.** MSW in the build, a
fixture dataset in the bundle, six AA contrast failures, a broken skip link on every authenticated screen.
The pattern is worth stating once in the plan rather than four times in four reports: **each had been
shipping for weeks because nothing measured the right subject.** Bundle analysis cannot see `publicDir`;
`jest-axe` never renders `index.html`; a contrast sign-off that is a checklist item is not a measurement.
That is the argument for the hardening phase existing at all, and it is now in the document a future
reader consults rather than only in this one.

**3 · The README gained a "where the seam is" section, because the task asked for the thing and not the
prose.** FE-104's Implementation line asks for *"how to replace a fixture with a real client"*. The honest
answer is that it is **subtractive** — there is one repository implementation, not two, and "fixture vs
real" is only a question of whether MSW is registered — so the section is four numbered steps ending in
`grep CONTRACT: TBD`, not an architecture essay. It also carries the warning `FE-101` paid for: nothing
under `shared/testdoubles/` may be imported as a **value** by shipped code.

**4 · The gates table exists because ten commands is too many to rediscover.** Ten required CI jobs now
guard this component and several were added in this phase. A contributor who runs `npm test` and stops has
missed eight of them. The table names each, what it proves, and the task that owns it — and flags
`test:a11y` as a known flake in the same place, so the person who hits it does not spend an hour on it as
I did.

**5 · Every link added was checked, not assumed.** Repo convention (`CLAUDE.md`) makes "all markdown links
resolve" part of verification here, so the new links in `README.md`, `implementation.md` and
`decisions.md` were resolved against the filesystem — all pass.

## Acceptance

- ☑ **README current** — status line corrected, gates table, seam section, accessibility section, CSP and
  artefact sections already present from `FE-100`/`FE-101`.
- ☑ **review section filled** — Phase 8 entry written; Phase 4's stub deliberately left to its own session
  (Ruling 1) and flagged rather than hidden.
- ☑ **every execution-time decision has an ID** — `FE-D42`, `FE-D43`, `FE-D44`, each a new ID with an index
  row and a changelog entry, nothing edited in place (`I3`). Phase 8's earlier decisions (`FE-D39`,
  `FE-D40`, `FE-D41`) were already registered by their own tasks.

## Verification

Phase 8's own closing run — all nine non-browser gates in one chain, all green:

```
npm run lint                 ✓ 0 problems
npm run typecheck            ✓ clean
npm run verify:perf          ✓ 6 files, 75 tests
npm run verify:palette       ✓ 169 tests (contrast assertions + the contrast maths' own tests)
npm run check:forbidden-deps ✓ no Pro/Premium packages
npm run lint:fixtures:assert ✓ 13 fixtures failed on the intended rule
npm run build                ✓
npm run verify:bundle        ✓ initial JS 134.7 KiB gzip, ceiling 160.0
npm run verify:production    ✓ 7/7 assertions
```

Earlier in this session, on the same tree: `npm run e2e` **168 passed / 0 failed**, `npm test`
**2167 / 2172** (the five failures are the `test:a11y` flake reproduced on `main`), `npm run verify:csp`
**17 passed**.

**Documentation verified, not assumed.** Every markdown link added to `README.md`,
`implementation.md` and `decisions.md` was resolved against the filesystem — all pass. That is repo
convention (`CLAUDE.md`: verification here means "all markdown links resolve, decision IDs are complete
and correctly prefixed"), and the decision IDs were checked for correct prefixing too: `FE-D42`–`FE-D44`
are new, indexed, changelogged, and nothing existing was edited in place.

## Still open at plan closure — stated, not resolved

Closing the plan does not close these, and none of them is a documentation problem:

1. **`test:a11y` is a latent red build.** `InstancesPage.a11y.test.tsx` times out under full-suite
   parallelism; reproduced on `main` with all of Phase 8 stashed. CI runs the full suite. Needs a
   deliberate `testTimeout` or the DataGrid axe scan split — `FE-098`'s test, not a hardening task's.
2. **No browser performance measurement exists** (`FE-103`). Frame timing and interaction latency need
   Playwright; no task commissions them.
3. **`FE-096` (#163) is marked built but its issue is still open.**
4. **Phase 7 (`090`–`095`) remains "not scheduled"** and Phase 4's Review stub is unfilled.
5. **The baseline is still UNBASELINED.** `SM-D*` decisions remain `PROPOSED` (`I4`), and every `FE-D*`
   added here — 42, 43, 44 — is `PROPOSED` too. Code written against a proposed decision does not ratify
   it, and this plan closing does not either.
