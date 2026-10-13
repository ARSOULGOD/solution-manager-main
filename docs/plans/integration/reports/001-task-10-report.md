> **Moved 2026-10-01** from `docs/plans/frontend/reports/task-132-10-report.md` to the integration folder (own numbering). The filename-collision note below is the reason for that move and is now resolved.

# Integration plan 001 · Task 10 — End-to-end backend smoke test (acceptance gate) — Report

⚠️ **Filename note**: the task brief asked for this report at
`docs/plans/frontend/reports/task-132-report.md`. That exact path already holds an unrelated,
completed report — `FE-132` ("make the save flow land, enrich the fixture, and prove it on
screen"), from the *frontend plan's own* task numbering (`FE-126`-`FE-148`), which coincidentally
collides with this plan's directory number (`integration/001-auth-hierarchy`). I nearly
overwrote it, caught it via `git status` showing a `modified` (not new) file, restored the
original with `git checkout --`, and confirmed it byte-for-byte intact before writing this report
under this repo's own established disambiguation convention instead — `task-131-01-report.md`
through `task-131-12-report.md` already exist for that plan's per-task reports, so `task-132-10-
report.md` follows the same pattern. Flagging this collision explicitly rather than silently
renaming, since a future plan-132 task (or the plan's own task list) may still expect the bare
`task-132-report.md` name and hit the same collision.

## What this task was

Plan 132 wired the frontend's Auth/Hierarchy screens to the real backend (Tasks 1-8) and set up
one-origin dev serving via a Vite proxy + HTTPS (Task 9). Task 10 is the plan's acceptance gate: a
walkthrough of the golden path (login → forced rotation → hierarchy cascade → logout → session
expiry) proving the whole slice works together end to end, not just that each task's own tests
pass in isolation.

**This environment has no interactive browser.** The brief's Step 3 literally asks for a browser
walkthrough. Rather than fabricate that, I drove the identical golden path at the HTTP wire level
— real Postgres, real compiled `backend/launcher` (`SolutionManagerApplication`, `dev` profile),
real Vite dev server (`VITE_USE_REAL_AUTH=true`), real HTTPS, a curl cookie jar simulating exactly
what a browser's network layer sends — through the actual Vite proxy. Full detail, request/response
evidence, and an honest per-check breakdown of "verified at the wire" vs "not verified — no
browser" is in `.superpowers/sdd/132-auth-hierarchy-backend-integration/task-10-report.md`.

## Result: every wire-level check passed; UI-rendering could not be observed

| Brief's Step-3 check | Wire-level result |
|---|---|
| 1. Login form renders (not the dev mock) | Not directly observable; confirmed via code read (`AuthProvider.tsx`'s `VITE_USE_REAL_AUTH` gate) + passing `LoginPage` tests |
| 2. Login → redirect, `AccountMenu` shows role | `POST /api/cp/auth/login` → 200, `mustRotate:true`, cookies set correctly (`__Host-` prefix, `HttpOnly`/`Secure`/`SameSite=Strict`) |
| 3. Forced-rotation dialog appears, blocks UI | `GET /api/cp/hierarchy/organizations` pre-rotation → **428 `MUST_ROTATE`** |
| 4. Rotate → dialog closes, shell renders | `POST /api/cp/auth/credential` (with `X-SM-CSRF`) → 204; identity flips to `mustRotate:false` |
| 5. Hierarchy cascade, real network calls | Full 3-level cascade (organizations → sub-orgs → environments) resolved by id through the `ScopeNodeList` envelope |
| 6. Logout → redirect, cookie cleared | `POST /api/cp/auth/logout` → 204, cookies expired; subsequent calls → 401 |
| 7. Session expiry → `/login?arrival=session` | Server side proven (401 on invalid session); client redirect logic read + its 3 test suites (16 tests) re-run and passing |

No check failed. The one honest gap, repeated across every check: **actual browser rendering was
never observed** — `AccountMenu`, `RotateCredentialDialog`, and the SPA's client-side redirects are
proven only by (a) the server producing exactly the signal each of those components is coded to
react to, and (b) that reacting code's own passing unit tests, not by watching a screen.

## Deviations from the brief, found while actually running it

1. **`mvn -pl console-gateway -am spring-boot:run` does not work** — `console-gateway` is a library
   module, not runnable. Confirmed again this session (Task 9 found the same thing). The real
   composition root is `backend/launcher`'s `SolutionManagerApplication`, run here via `java -cp`
   against the built classpath rather than `spring-boot:run`.
2. **No plaintext bootstrap credential is pre-recorded anywhere in this tree.** The brief assumes
   one exists in `backend/README.md` or a startup log; it does not. `V007__bootstrap_admin.sql`
   takes an Argon2id **hash** as an installer-supplied Flyway placeholder — generating that hash
   *is* the install step (see `SmControlMigrationMain`'s javadoc). I acted as the installer,
   following the exact precedent already established in the backend's own
   `ProductionAuthenticationIT`/`HierarchyEndpointsIT`: picked a plaintext credential, hashed it
   via `cp-core`'s `CredentialHasher` through `jshell`, and passed the hash as
   `SM_CONTROL_BOOTSTRAP_CREDENTIAL_HASH`. **This is a real gap worth a follow-up**: nothing in
   `backend/README.md` documents this step for an operator.
3. **Port 8080 is occupied on this host** (pre-existing Docker container, unrelated to this repo —
   same collision Task 9 documented). Ran the backend on 8081 for this session only; the committed
   `frontend/vite.config.ts` (target `8080`) was restored before finishing — confirmed via a clean
   `git status`.
4. **A genuinely fresh install has zero sub-organizations and zero environments** — `V007` seeds
   only the organization, the bootstrap principal, and its role binding. To demonstrate the full
   3-level hierarchy cascade (not just an empty second/third level), I seeded one sub-organization
   and one environment directly via SQL, the same technique `HierarchyEndpointsIT` itself uses
   ("there is no create-principal/create-hierarchy endpoint in v1 — installer-seeded only"). Not a
   bug, but worth knowing: any future demo/smoke-test walkthrough needs seed data prepared in
   advance.
5. **Report filename collision** — see the note at the top of this file.

## Follow-up items surfaced, out of this plan's scope

- **No runbook step for generating/recording the bootstrap credential hash.** (New finding, see
  deviation 2 above.)
- **Hierarchy node `name` is literally the UUID string on the wire** (`{"id":"...0011","name":
  "...0011"}`), confirmed directly this session. This is a known, already-flagged gap —
  `HierarchyController`'s own javadoc on `STATIC_STATE_VERSION` says as much ("this constant stops
  being honest the moment `ScopeNode`'s name column lands") — not something this task introduced,
  but re-confirmed live rather than assumed.
- **The deferred `name`/`email` schema gap from spec §5** (identity carries `username`/`role`, no
  display name or email) — re-confirmed on the wire this session (login/identity responses), same
  gap Task 4/5's reports already recorded.
- **This plan's own Task 10 cannot verify client-side rendering** in this environment. If browser-
  level verification is later required as a hard gate (rather than the honest wire-level +
  component-test substitute used here), it needs to run in an environment with an actual browser
  tool available.
- The stale MSW hierarchy fixtures flagged in Task 7's report remain stale/unaddressed by this
  task — out of scope here, carried forward as a pre-existing note.
- **Report-directory naming convention**: `docs/plans/frontend/reports/` mixes two independent
  numbering schemes (the frontend plan's own `FE-NNN` task numbers, and other plans' own directory
  numbers like `131`/`132`) in the same flat directory with the same `task-NNN-report.md` pattern.
  This collision (this task) is the second time that pattern has needed disambiguation — `131`
  already uses `task-131-NN-report.md` for its own per-task reports. Worth a deliberate decision
  (a per-plan subdirectory, or a prefix distinguishing frontend-plan task numbers from other plans'
  directory numbers) rather than each future collision being caught ad hoc by whoever hits it.

## Environment cleanup

Vite dev server and the `launcher` process were both killed, the throwaway Postgres 14 Docker
container (`sm-t10-pg`) was removed, and `frontend/vite.config.ts` was restored to its committed
value — `git status` was clean before this report was written and committed.
