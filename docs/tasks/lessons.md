# Lessons — solution-manager (repo-local)

Patterns specific to working **in this repo**: authoring design docs, maintaining the decision register, keeping
requirements traced.

> **Which file?**
> - **Cross-cutting** — affects more than one repo, or is architectural/contract-level →
>   [`../design/common/lessons.md`](../design/common/lessons.md). Every repo reads that one.
> - **Only about working here** → this file.
>
> When in doubt, put it in the cross-cutting file. The cost of a lesson being seen by people who didn't need it is
> far lower than the cost of it being invisible to someone who did.

**Review this at session start.** Each entry exists because something went wrong once.

---

## R1 — Verify a mechanism works before designing on top of it

**What happened:** a design specified `info.sm.instanceId` as its *only* instance-correlation primitive, asserting
that `/actuator/info` was exposed by default and that `info.*` properties surfaced automatically. Both were false in
Spring Boot 3.x, and a third claim ("carries only what SM put there") was a security statement that was simply
untrue. The mechanism was load-bearing for two separate decisions.

**Rule:** when a design depends on a specific platform behaviour, verify that behaviour *before* building the
design around it — not after review. See `L1` in the cross-cutting lessons for the general form.

---

## R2 — Test the verification, not just the thing being verified

**What happened:** `sync-conventions.sh`'s drift check appeared to work — a tamper test reported DRIFT. It only
passed because the test had modified the hash marker *as well as* the block body. The check never compared the
body at all, so the actual failure mode it existed to catch (hand-edit, marker untouched) was undetected.

**Rule:** when testing a guard, make the test simulate the *realistic* failure, not a convenient one. Ask "what
would someone actually do wrong here?" — a person editing a synced block will not helpfully update its checksum.

---

## R3 — Don't leave an empty folder where a document might wrongly land

**What happened:** after moving plans into the component repos, a central `plans/backend/` folder would have
remained. An empty folder in a familiar location is an invitation to file something there, recreating the
two-copies problem in a new spot.

**Rule:** when relocating a class of document, delete the old location outright and put the *convention* (template,
rules) somewhere that is clearly not an artifact folder.

---

## R4 — Check the ID prefix before writing a decision down

**What happened:** `B/D17` (editions) was recorded as `D17` (first vertical slice) while consolidating — with the
no-renumbering rule on screen at the time. Caught only by a scripted completeness check over all 53 IDs.

**Rule:** when touching the decision register, run a coverage check over every ID rather than trusting a read-through.


---

## R5 — Verify against the artefact, not a proxy for it

**What happened:** FE-101's production-build gate started out reading Vite's **in-memory** build
(`build({ write: false })`), the way `verify-bundle.mjs` does. That mode is right for a bundle budget
and wrong for "what does the customer receive". It hid the task's biggest finding — `publicDir` is
not copied in memory, so MSW's shipped service worker was only visible because the script happened to
walk `public/` separately — and it reported `renderedLength: 0` for a module that a **written** build
renders at 99 bytes, which led to a real fix being made, measured as a no-op, and reverted. Building
into a throwaway directory and walking what was written found both.

**Rule:** when a check's subject is "the thing that ships", make the check read the thing that ships.
An in-memory bundle, a source-directory listing and a module-graph entry are each a *proxy*, and
every proxy has a gap the real artefact does not. Ask what the proxy cannot see — for a bundle:
copied static assets, post-build hooks, and anything measured differently because nothing was
written — and if the answer includes what you are trying to prove, change the subject.

---

## R6 — A structural guarantee still has to be run before it is preferred

**What happened:** the same task fixed the shipped service worker by moving it out of `public/` and
serving it from a dev-only middleware — a guarantee by construction, which is normally the better
kind, and which was written up as such before the suite had been run. It broke 11 e2e tests
non-deterministically, and two plausible explanations (a stale dev server; a cold dependency cache)
each looked convincing enough to act on and were both wrong. A three-way bisect against a clean
`main` settled it in one pass: 166 passed on `main`, 11 failed with the middleware, 168 passed with
it reverted.

**Rule:** "structural" is an argument about a design, not evidence about a system. Prefer the
structural version *after* the full suite has run on it, not while writing the commit message. And
when a change produces a failure set that **differs between runs**, stop theorising and bisect
against a known-good tree — a controlled comparison is faster than the second wrong hypothesis, let
alone the third.

---

---

## R7 — In the backend reactor, a test class not named `*Test` is silently skipped

**What happened:** measured during Task 8 of backend plan 001. maven-surefire's default includes are
`Test*` / `*Test` / `*Tests` / `*TestCase`, so a class named `…IT` is invisible to it — no error, no warning, a
green build that ran nothing. Integration tests need **maven-failsafe** bound explicitly, and in this reactor only
`backend/dp-core/pom.xml` configures it (for `JcsmpEventPublisherIT`). Every other module would swallow an `*IT`
class whole.

**Rule:** when adding a test to a backend module, either name it `*Test` or confirm that module's POM binds
failsafe. Prove the test actually ran — read the `Tests run:` line for that class in the build output, and for a
conditionally-skipped IT confirm it reports `Skipped: 1` rather than vanishing. A test that does not appear in the
surefire/failsafe summary did not run, whatever the build says.

---

## R8 — A plan number already cited in the tree by anticipation must be reconciled at execution time

**What happened:** backend plan 002 turned out to be `002-contract-consumption-locks`, but three files had
already written "plan 002" as a forward reference to work that plan does not do — `JcsmpEventPublisher` and
the publisher contract kit expected it to expose the drop counter through a metrics stack, and the G1 spike
expected it to lift the ACL fixtures into CI. Once 002 executed, those sentences read as claims about a plan
that had just landed without doing any of it. The branch's stale-reference scan did not catch a single one:
its patterns were written before the collision existed, and every citation was accurate prose about a
*future* plan at the moment it was written.

**Rule:** before executing a numbered plan, grep the tree for its number and read what the existing citations
expect of it. Each one is then a choice made deliberately — deliver it, or re-point it to a **role-based**
forward reference ("the metrics plan", "the mesh-provisioning plan"). Prefer role-based forward references
when writing them in the first place: a role stays true regardless of which number the work ends up with,
and a number does not.

---

## R9 — A successful bundle does not prove a dependency can execute under CSP

**What happened:** Solution Package validation compiled its JSON Schema with AJV when the browser loaded the
module. AJV uses dynamic function generation for compilation, so the production bundle built successfully but
failed at runtime because the shipped Content Security Policy correctly forbids `unsafe-eval`. The first
standalone replacement used CommonJS and worked in the production bundle, but failed through Vite's development
server; only exercising both modes exposed the format mismatch.

**Rule:** code generators used by browser features must run during development/build tooling, not at browser
module initialization. Commit a deterministic native-ESM artifact, reject stale generated output before dev and
build, and verify both the development server and the production bundle under the shipped CSP.

---

## R10 — Testcontainers on this host needs the socket override when Colima and Docker Desktop coexist

**What happened:** plan 004's first container run failed with `Container startup failed for image
testcontainers/ryuk:0.12.0 … error while creating mount source path '…/.colima/default/docker.sock':
operation not supported`. `~/.testcontainers.properties` pointed `docker.host` at Colima while
`/var/run/docker.sock` was Docker Desktop's. Worse, Testcontainers caches the reaper failure, so only
the FIRST IT class to start a container errored and the rest passed — a partially red run that reads
like a flake.

**Rule:** run container-starting Maven commands with
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` on such hosts; never bake a host path
into a POM; and when exactly one IT class errors at container start, suspect the environment before
the test.

---

## R11 — Spring keys a transaction by the DataSource INSTANCE, so a harness must cache one per role

**What happened:** a test harness handed out a fresh `SimpleDriverDataSource` from every getter. A
`JdbcTemplate` over one instance and a `DataSourceTransactionManager` over another did not share a
transaction: every write took its own auto-commit connection, the rollback IT failed with the row
still present, and the commit IT had been passing for the wrong reason.

**Rule:** one cached `DataSource` instance per role in any test harness; and every after-commit or
rollback test carries an in-transaction "row not yet visible on another connection" precondition so
the trap cannot recur silently.

---

## R12 — Strip SQL comments BEFORE tracking quotes when parsing the design's DDL

**What happened:** §3.1's prose comments contain English apostrophes ("PG 14's default …"). A
quote-aware statement splitter run over the raw text opened a phantom string literal on a comment and
reported nonsense for the rest of the file. Measured as a red test before the pass order was fixed.

**Rule:** any code that parses the design's SQL block strips `--` comments first, tracks quotes
second, and asserts its own preconditions (`SqlStatements.assertNoDialectSurprises`) so a dialect
feature the splitter cannot see through fails loudly instead of silently.

---

## R13 — A conditional-skip annotation on a load-bearing suite turns the completion gate into a no-op, and the failure mode is a green build

**What happened:** plan 004's sixteen `sm_data` IT classes all carried
`@Testcontainers(disabledWithoutDocker = true)`, and the plan named `mvn -f backend/pom.xml clean
verify` as the completion gate. An external review ran that exact command on a Docker-unreachable
host and got `BUILD SUCCESS` with every one of those sixteen classes — every migration, DCL,
tenant-isolation, fence and transaction-boundary proof the plan rested on — reported `Skipped`, not
failed, and nothing else in the build said so. This repo has no CI, so the gate is only ever what a
human happens to run, and a skip that reads as green is the worst version of that: it looks exactly
like proof and is not.

**Rule:** a conditional-skip annotation (`disabledWithoutDocker`, `@EnabledIfSystemProperty`, or
similar) on a suite the plan calls its completion gate needs a *paired* mandatory check — one test
with no such condition that asserts the precondition directly and fails loudly if it does not hold —
so the precondition's absence is a build failure, not a quieter version of the same build. Before
trusting any "it should skip cleanly" claim about such an annotation, simulate the missing
precondition for real (stop the service, break the socket, deny the permission) and read the actual
exit code and the actual `*-summary.xml`; "the mechanism is proven by analogy to a similar-looking
condition elsewhere" is not the same claim and was wrong here specifically because analogy does not
transfer whether skipping still lets the *build* go green.

---

## R14 — A plan's `Produces` signature must be able to compute its own decision table

**What happened:** plan 005's Task 10 specified `ResumePlanner.decide(StepIntentRow intent,
Optional<AttemptRow> open, RuntimeStatus observed)` and, immediately below it, a fourteen-row
decision table whose `ROLLBACK` rows read *"every replica reads the intent's `config_hash`"* and
*"reads the target **or** its own `MATERIALIZE` hash"*. Neither row is computable from that
signature: `ROLLBACK` is placement-scoped, so "every replica" needs a list rather than one status,
and "its own `MATERIALIZE` hash" is a different row of the journal that the `ROLLBACK` intent does
not carry. The plan was internally inconsistent and read as complete, because the signature and the
table were written in different passes and each was right about its own half.

**Rule:** when a plan pairs an interface with a table of cases, walk the table against the
signature, cell by cell, before the plan is finished — for each row, name the parameter every value
in it comes from. A row that needs a value no parameter supplies is a plan defect, not an
implementation detail, and it is cheapest to find while the signature is still a paragraph. The
self-review's existing "type consistency" pass checks that names match between tasks; this is the
same pass pointed inward at one task.

---

## R15 — A predicted mutation count that comes out short has located an unguarded guard

**What happened:** plan 005's Task 9 predicted that making the health gate treat `NOT_DECLARED` as
any other signal would turn **2** tests red. It turned exactly **1** red. The second test the plan
named is a *pre-flight* refusal, and `HealthGate.await` is not on its path — so the pre-flight check
had no mutation of its own and nothing had shown it could fail. Adding one showed it fires, and
fires as the deliberately loud `IllegalStateException` the executor keeps for the case the gate
should be unreachable in.

The converse is worth the same suspicion. A mutation predicted to turn **1** red turned **4**;
the three extra (`aRollingNoValidationApplyRestartsWithoutGating`,
`restartAllRequiresAnOutageConfirmation`, `rollingNoValidationIsNotRefusedByAnAbsentHealthSignal`)
were collateral — replica-count assertions tripped by a mutation that restarted every replica
twice — and naming them is what lets a reader tell collateral from evidence.

**Rule:** treat a shortfall between a predicted and a measured red count as a finding, not as a
prediction that was merely imprecise, and name every red you attribute to a mutation. Ask which code path the missing red was supposed to come
from; nine times in ten it is a second guard that shares the rule but not the method, and it needs
its own mutation. Record both numbers — predicted and measured — so the discrepancy stays visible
in the plan rather than being quietly normalised to whichever one is larger.

## R16 — A wire-level smoke test cannot prove client-side wiring (2026-10-01, integration plan 001)

Plan 132's acceptance gate was run with `curl` and a cookie jar because no browser tool was available;
every check passed. The first real-browser run then found that credential rotation returned
**403 `CSRF_REJECTED`**: `app/main.tsx` wraps the app in `StrictMode`, which double-invokes the `useMemo`
that builds the auth adapter, and the discarded twin's constructor-time `configureCsrf(...)` (a
process-global side effect) overwrote the live adapter's hook. `curl` supplied the header by hand, so
the defect was invisible to it. A user could never have left the forced-rotation dialog.

**Rule:** when a plan's acceptance gate says "the browser works", run a browser (Playwright is already in
`frontend/node_modules`), not an HTTP replay, and do not write "verified" for anything only its server half
was observed. **Corollary:** a process-global side effect inside a `useMemo`/`useState` initializer is a bug
under `StrictMode` — register it on use (as `localSessionAdapter` now does on each token) or from an effect
with cleanup, never only at construction.
## A scratch Maven resolution against the shared `~/.m2` can turn the offline licence gate red (plan 015 T0.2, 2026-10-08)

`backend/scripts/check-licences.py` resolves the build-plugin realm with `mvn -o`. Its `OUT_OF_SCOPE` ruling for the
never-run site and deploy plugins holds only because those plugins' dependencies are **absent** from `~/.m2`. A scratch
`mvn dependency:resolve-plugins` run online, to measure two new plugins, downloaded them (jetty 9.4.46, sisu 0.3.5,
aether, ...): 25 unapproved coordinates appeared and step 13 of `contracts/run-checks.sh` went red in every worktree,
which share one cache. It was caught because the full gate was run before committing, and repaired by deleting the 263
files whose fresh `.sha1` sibling dated from that run.

**Rule:** any scratch Maven run that may download (a coverage diff, a version probe) passes a private
`-Dmaven.repo.local=<scratchpad>/m2`; the shared `~/.m2` is primed only by the task that owns priming (plan 015 T0.4
Step 2), and `check-licences.py` is run afterwards to confirm it stays green.
