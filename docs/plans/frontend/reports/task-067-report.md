# FE-067 · Governance states and accessibility pass — Report

## What I implemented

This task's Refs are Design §16/§18 and UI §A.9 (states); Deps `064`–`066` (Health hero, config ladder, log
viewer). Unlike 064–066, it adds no new component — it is a review-and-close pass over the three already-built
Governance components, checked against `docs/design/frontend/reference/app/governance.jsx` (the demo's `loading`
/ `error` / `notdeclared` scenarios and the log viewer's live-region intent).

Auditing the existing code against the task's three acceptance boxes (`error ≠ down`, `log stream does not spam
live regions`, `axe clean in both themes`) found:

- **`error ≠ down`** — already correct. `GovernancePage`'s health/config-ladder failure renders a distinct
  `ErrorPanel` (`role="alert"`, title *"Couldn't load health for this instance"*) that replaces the whole page,
  never conflated with `HealthHero`'s `RED` rollup (*"The health endpoint is not answering"*, rendered inline
  within a successful response). No code change needed; this pass adds a regression test (below) so it stays
  true.
- **Loading skeletons at content dimensions** and the **not-declared state** — already correct from 064/065 (the
  `NOT_APPLICABLE` rollup's paragraph, and `ReplicaCards` hidden precisely when `availability !== 'declared'`,
  matching `governance.jsx`'s `notDeclared` branch). No code change; this pass adds the missing axe coverage.
- **`log stream does not spam live regions`** — this was the real gap. `LogViewer`'s viewport (`role="region"`)
  had no `aria-live` at all, so screen readers got zero feedback on Stream/Pause — not "compliant by having
  nothing to spam," just unannounced. Fixed below.

| File | Summary |
|---|---|
| `frontend/src/features/governance/LogViewer/LogViewer.tsx` | Added a visually-hidden `aria-live="polite"` region, separate from the log viewport, holding one short sentence — set only on genuine streaming start/stop transitions (`startStreaming()`, a user-initiated `stopStreaming()`, and the `finally` branch when the fixture's finite stream ends on its own). Per-line updates never touch it — the transcript keeps rendering into the plain (non-live) viewport exactly as before. `stopStreaming()` only announces when a stream was actually active (guarded on `abortRef.current !== null`), so switching the component selector while paused stays silent rather than firing a spurious "stopped." |
| `frontend/src/shared/i18n/messages.en.ts` | Two new keys: `logViewerStreamingStartedAnnouncement` ("Streaming logs.") / `logViewerStreamingStoppedAnnouncement` ("Log stream stopped."). |
| `LogViewer.test.tsx` | New case: streams two lines with a gate between them, asserts the live region reads "Streaming logs." after the first line, still reads exactly that (unchanged) after the second line arrives, and only changes to "Log stream stopped." once Pause is clicked — proving the region tracks start/stop, not line count. |
| `LogViewer.a11y.test.tsx` | New case, both themes: the active-streaming state (lines rendered, live region populated) has zero axe violations. |
| `GovernancePage.a11y.test.tsx` | Three new cases × two themes: loading skeleton, health-query error state, and the `NOT_APPLICABLE`/not-declared state — none were axe-tested before this pass (only healthy+standby, `RED`, and the DEBUG dialog were). |
| `GovernancePage.test.tsx` | New case: tabs through the healthy-snapshot page and asserts focus visits Open in Monitoring → Component select → Level select → Stream logs, in that order, with nothing skipped — the "full keyboard traversal" acceptance box, previously asserted nowhere. |

## Design decisions, and why

1. **A separate live region, not `aria-live` on the viewport itself.** Putting `aria-live="polite"` directly on
   the scrolling transcript would announce every appended line — exactly what the task explicitly forbids
   ("not announced on every line... announce start/stop only"). A second, visually-hidden node whose text is
   only ever set by the two transition points is the standard pattern for "announce the state change, not the
   feed."
2. **Natural stream completion announces "stopped," same as a user-clicked Pause.** The real fixture's
   `streamLogs()` (`governanceRepository.ts`) fetches a static line list and yields all of it immediately, then
   returns — i.e. every real stream in this app today ends on its own, not via Pause. Browser verification below
   is what surfaced this: without announcing the `finally`-branch stop too, the live region would silently go
   stale the moment a real stream finished, which is the common case, not the edge case.
3. **`stopStreaming()` only announces if a stream was actually running.** It's called unconditionally from
   `handleComponentChange` (switching components stops whatever was streaming, per the existing `useEffect`
   cleanup comment). Announcing "stopped" when nothing was streaming would be exactly the kind of noise the
   task's "does not spam" box is checking for, just relocated from per-line to per-click.
4. **No copy or layout change to the error/not-declared states.** Both already matched the reference's intent
   structurally (distinct component, distinct trigger, distinct title) before this task started; the acceptance
   boxes for those two are about proving it, not building it, so the delta here is tests, not implementation.

## Testing

Every command run from `frontend/`, output inspected:

```
npx tsc -p tsconfig.json --noEmit                    # clean
npm run lint                                          # clean (project-wide, --max-warnings=0)
npx vitest run src/features/governance                # 9 files, 58/58 (was 51/51 before this task)
npx vitest run                                        # full suite: 237 files, 1370/1370 green
npm run build                                          # production build succeeds
```

Task-specific acceptance items:

- **`error ≠ down`** — `GovernancePage.a11y.test.tsx`'s new error-state case renders the health-query failure
  and asserts `getByRole('alert')` (the `ErrorPanel`), distinct from `HealthHero`'s rollup badge, which never
  appears in that state.
- **`log stream does not spam live regions`** — `LogViewer.test.tsx`'s new case is the direct proof: the live
  region's text is asserted unchanged across a line arriving, and only changes on the Pause click.
- **`axe clean in both themes`** — six new `it.each(['light','dark'])` cases (three governance states + the
  active-streaming LogViewer state) cover states that had no axe coverage before this task; all pass in both
  modes.
- **Full keyboard traversal** — `GovernancePage.test.tsx`'s new case tabs through the page and asserts the
  visited-element sequence equals the four interactive controls in DOM order.

### Browser verification

Dev server (`npm run dev`, port auto-selected to 5174) + a scratch Playwright script (not committed) against
`/solutions/cache/instances/cache-prod-01/governance`, the real MSW-backed `GovernanceRepository`:

- Page renders with no console errors.
- Clicking **Stream logs** populates the transcript from the fixture (`Started successfully.` / `Slow response
  from broker.`) and the live region reads **"Streaming logs."**
- Because the fixture's stream is finite, it completes within ~1s; the live region correctly updates to
  **"Log stream stopped."** and the button reverts to "Stream logs" — confirming design decision 2 above against
  the real repository, not just the test's synthetic never-ending stream.

Screenshot (`gov-1-loaded.png`, initial load, all sections rendering correctly) available on request; the
streaming screenshot showed a `fullPage`-screenshot stitching artifact (the fixed shell captured twice) unrelated
to the app itself — the live page (non-stitched) matched the first screenshot's layout throughout.

## Status

DONE — implemented, tested (58/58 on the touched suite; full suite green, 237/237 files, 1370/1370 tests),
lint-clean, typecheck-clean, production build succeeds, browser-verified against the real fixture repository. Not
yet marked done in `task-list.md`/`execution.md` or committed — pending user confirmation. GitHub issue #83
(`FE-067 · Governance states and accessibility pass`) already exists — to be closed on commit.
