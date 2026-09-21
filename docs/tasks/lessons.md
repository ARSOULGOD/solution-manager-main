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

## R5 — In the backend reactor, a test class not named `*Test` is silently skipped

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

## R6 — A plan number already cited in the tree by anticipation must be reconciled at execution time

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

## R7 — Testcontainers on this host needs the socket override when Colima and Docker Desktop coexist

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

## R8 — Spring keys a transaction by the DataSource INSTANCE, so a harness must cache one per role

**What happened:** a test harness handed out a fresh `SimpleDriverDataSource` from every getter. A
`JdbcTemplate` over one instance and a `DataSourceTransactionManager` over another did not share a
transaction: every write took its own auto-commit connection, the rollback IT failed with the row
still present, and the commit IT had been passing for the wrong reason.

**Rule:** one cached `DataSource` instance per role in any test harness; and every after-commit or
rollback test carries an in-transaction "row not yet visible on another connection" precondition so
the trap cannot recur silently.

---

## R9 — Strip SQL comments BEFORE tracking quotes when parsing the design's DDL

**What happened:** §3.1's prose comments contain English apostrophes ("PG 14's default …"). A
quote-aware statement splitter run over the raw text opened a phantom string literal on a comment and
reported nonsense for the rest of the file. Measured as a red test before the pass order was fixed.

**Rule:** any code that parses the design's SQL block strips `--` comments first, tracks quotes
second, and asserts its own preconditions (`SqlStatements.assertNoDialectSurprises`) so a dialect
feature the splitter cannot see through fails loudly instead of silently.

---

## R10 — A conditional-skip annotation on a load-bearing suite turns the completion gate into a no-op, and the failure mode is a green build

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

## R11 — A plan's `Produces` signature must be able to compute its own decision table

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

## R12 — A predicted mutation count that comes out short has located an unguarded guard

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
