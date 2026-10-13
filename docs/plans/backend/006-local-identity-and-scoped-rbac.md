# Backend Plan 006 — local identity and scoped RBAC

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the authorization machinery the ratified `D10` amendment approved — the **38**-verb code-owned
catalog, built-in roles as data, a canonical scope tree, scope-evaluated bindings with downward inheritance, the
local identity substrate with an install-seeded bootstrap administrator under forced rotation, durable revocable
sessions with CSRF, and an append-only control-plane audit — behind **one** `authorizationService.require` with no
second path and no production bypass.

**Architecture:** Everything durable lands in **`sm_control`**, reached only through `cp-core` (`I7`). The
enforcement point is a `cp-api` interface implemented in `cp-core`; no endpoint is added by this plan — HTTP is
plan 007. The catalog is a Java enum mirrored against the approval document by a test, so a verb cannot be added
by editing code alone. Scope evaluation is the `SM-D33` prefix algorithm executed over typed columns in SQL, never
over a client-supplied array.

**Requirements:** [`REQ-006`](../../requirements/REQ-006-rbac.md) — ACCEPTED, **must**, **v1**. This plan is its
implementation, and its three acceptance clauses are this plan's acceptance criteria.

**Decisions:** the [`D10` amendment](../../design/D10-G2-APPROVAL-2026-09-15.md) — ✅ **RATIFIED and `LOCKED` 2026-09-16** · `D10` 🔒 ·
`BE-D15` · `SM-D32`/`SM-D33`/`SM-D34` · `BE-D8` (its identity clauses now owned by `BE-D15` §5.3) · `FE-D22` ·
`FE-D24` · `I3`/`I5`/`I6`/`I7`

**Tech Stack:** Java 17 · **Spring Boot 3.5.16** (BOM only; no web — the version plan 005 pins) ·
**`org.springframework.security:spring-security-crypto` 6.5.11** (BOM-managed) · ⚠️ **`org.bouncycastle:bcprov-jdk18on` 1.86, pinned explicitly — measured: the BOM does not manage it** · Flyway at the
version the reactor BOM manages · **PostgreSQL 14** (the pinned floor, `SM-D26`) · **Testcontainers 1.21.4** ·
**ArchUnit 1.5.0** · JUnit 5

✅ **Every dependency is pinned and every fact measured** as of 2026-09-16 — including the password-hash provider
(`bcprov-jdk18on` **1.86**, pinned explicitly, because the BOM does not manage it). See
[Measured facts](#measured-facts) and Task 3's four measured findings.

**Spec:** [`docs/design/D10-G2-APPROVAL-2026-09-15.md`](../../design/D10-G2-APPROVAL-2026-09-15.md) (what was
approved, and what was not) · [`docs/design/backend/eda-walking-skeleton.md`](../../design/backend/eda-walking-skeleton.md)
§5 (`BE-D15` — the machinery) · [`docs/design/D11-AMENDMENT-PROPOSAL.md`](../../design/D11-AMENDMENT-PROPOSAL.md)
§3.1 (the algorithm), §3.2 (`scopePathOf`, total), §3.4 (404 vs 403), §6.4/§6.5 (what to add; the eighteen
prove-it-fires obligations), §8 (the seed) ·
[`docs/design/common/contracts/console-integration.md`](../../design/common/contracts/console-integration.md) §4, §5

---

## Global Constraints

Every task's requirements implicitly include this section.

- **The ratified catalog is exactly 38 members** — the amendment's §1.1 (rows 1–35) **and §1.7** (rows 36–38:
  `instance.stop`, `audit.view`, `user.edit`). **Do not add a verb.** ⛔ Not for a **restart strategy**, not for
  **`group.*`**, not for **undeclare**, not for **package actions** — each is a *proposal* in open item 39, and an
  operation needing one **must not be written**. ✅ `instance.stop`, `audit.view` and `user.edit` were withheld on
  2026-09-15 and **approved on 2026-09-16**; an earlier revision of this line still barred them.
- **Scope evaluation, not whole-install.** The approval **rejects** `BE-D15` §5.1's whole-install v1 deviation.
  Implement `D11-AMENDMENT-PROPOSAL.md` §3.1 literally, including the per-level
  `(b.col IS NULL OR b.col = r.col)` comparison — a bare `=` makes the install-scoped binding match nothing, and
  `IS NOT DISTINCT FROM` makes it match only install resources. Both wrong answers are cheap to write.
- **No HTTP.** No controller, no `@RestController`, no filter, no `spring-boot-starter-web` dependency. This plan
  ships a tested library plus migrations. Plan 007 exposes it.
- **`I5`** — the organization is **server-derived from the session**, never read from a request. `scopePathOf`
  derives a resource's ancestors from stored rows.
- **`I6`** — no credential, no salt parameter set, no session token and no raw credential ever appears in a log,
  an exception message, an audit `detail` payload or a test fixture committed to the repo. `credential_hash` is a
  salted adaptive hash with its parameters recorded **with** the hash.
- **`I7`** — `sm_control` is `cp-core`'s alone. Nothing in this plan imports `com.solace.sm.dp..`, and no
  cross-schema join or FK to `sm_data` is created. The DP half of the audit is `apply_request`, untouched here.
- **`I3`** — ⛔ **No document reserves a decision id.** New decisions **allocate the next free id from the merged `DECISIONS.md`** — the one central allocator (ruling **OR-11**) — and only **after plan 005 merges and the register is re-read**, because plan 005 carries `BE-D25`/`BE-D26`.
- **Deactivate-never-delete.** No `DELETE` path for a principal. `status = deactivated` ends login and voids the
  effect of bindings; the row, its binding history and its audit lines survive.
- **Every guard carries a planted-violation proof** (`L7`). Every ArchUnit rule, every DCL refusal and every
  algorithm branch lands with a mutation that turns a named test red. **A mutation that does not fire is a finding
  about the tests, not a pass** (`L22`).
- **Never report a grep count as verified** (`L12`); **a guarantee is settled only when its enforcing artifact is
  in the document** (`L19`).
- **Contracts are single-writer.** No file under `contracts/` changes in this plan.
  `./contracts/run-checks.sh --strict` is part of every task's gate.
- **Docker is shared state.** Before any Testcontainers work: `colima status`, `docker context show`, `docker ps`.
  The context must remain `colima`. Never prune, switch context, stop or inspect foreign containers. Serialize
  container work.
- **Commit attribution.** Every commit ends with the session's attribution trailer. Use the configured Git
  identity; do not override `user.name` or `user.email`.

---

## Prerequisites

1. **G2 is closed.** [`D10-G2-APPROVAL-2026-09-15.md`](../../design/D10-G2-APPROVAL-2026-09-15.md) is the
   authorizing record. Read §3 before writing any verb.
2. **Read the approval's §6 first.** Alongside the ratified **38**, the tree still contains a **different 35-member** catalog. The approved one is
   the amendment's; `frontend/src/shared/authz/catalog.ts` ships a different one. Implementing the wrong 35 is the
   single most likely way to fail this plan.
3. **`sm_control` has no migrations yet.** `backend/cp-core` contains one marker class and no
   `db/migration/sm_control/` directory. This plan creates the series at `V001`, so it cannot collide with
   `sm_data`'s `V001`–`V006`.
4. **The install-time database arrangement already exists in design** and must be followed, not reinvented:
   Phase 0 by the bootstrap superuser, Phase 1 as a one-shot migration process holding `sm_cp_migrator` with
   `SET ROLE sm_cp_owner`, and the application receiving **only** `sm_cp_runtime`
   (`eda-walking-skeleton.md` §9 notice 3, mirrored from `sm_data`'s §3.1 pattern).
5. ✅ **Five owner rulings bear directly on this plan.** ⚠️ **Which unblock a task *here*, precisely:** **OR-15** → Task 5, **OR-13** → Task 7, **OR-8** → Task 4 (⛔ **and OR-12 constrains that same task rather than unblocking one**), while **OR-14** unblocks a row of *plan 007's* OpenAPI, not a task here. ⚠️ **An earlier revision said "four owner rulings … three unblock tasks here" over five bullets.** Each ruling's consequence is in
   [Decisions this plan routes](#decisions-this-plan-routed--all-four-now-ruled-and-registered-centrally):
   - **OR-15** (R4) — `GET /cp/auth/identity` needs **authentication only**. Task 5's check and plan 007's endpoint
     are unblocked.
   - **OR-13** (R2) — `user.deactivate` is **`HIGH`**. Task 7's audit-kind assertion is unblocked.
   - **OR-14** (R3) — `package.load` is **organization-scoped**. Plan 007's OpenAPI is unblocked for that row.
   - **OR-12** (R1) — `Administrator` is **bootstrap-only**, and ⛔ **`Administrator` at a narrow scope is NOT a
     production operator role.**
   - **OR-8** — the **production role set is decided**: **`Viewer`** (8 read verbs) and **`Operator`** (19)
     beside `Administrator`. Task 4 is unblocked and seeds **three** roles, not one; least privilege for real
     operators is available in v1. ⚠️ An earlier draft of this block said the production role set "stays
     undecided" — that was written before OR-8 and is corrected here; [Task 4](#task-4--roles-as-data-immutable-through-the-api-by-dcl) and the
     [R1 row](#decisions-this-plan-routed--all-four-now-ruled-and-registered-centrally) already carry OR-8.
6. ✅ **The last blocker is closed, 2026-09-16.** The **password-hash algorithm, artifact and cost parameters** are
   decided (ruling **OR-7**) **and measured on this host** —
   [`evidence/006-argon2id-benchmark.md`](evidence/006-argon2id-benchmark.md). **Task 3 is unblocked**, and with it
   Tasks 6, 8 and 10. ⚠️ Three measured facts change what Task 3 writes; read them there before coding.

## Measured facts

✅ **All filled as of 2026-09-16.** `I2` forbids stating an external fact from memory, and none of these is
recalled: each carries the command or URL that produced it.

| Fact | Status | Needed by |
|---|---|---|
| **The password-hash algorithm, artifact and cost parameters** | ✅ **DECIDED and MEASURED, 2026-09-16.** Ruling **OR-7**: **Argon2id through `DelegatingPasswordEncoder`**, Bouncy Castle **`bcprov-jdk18on` 1.86** pinned explicitly — ⚠️ **measured: the Spring Boot 3.5.16 BOM does not manage it** — with `spring-security-crypto` **6.5.11** (BOM-managed). Floor **19 MiB / t=2 / p=1**; recommended **384 MiB / t=4 / p=1** ≈ 1.02–1.08 s on the benchmark host. Commands, JDK, versions and the whole sweep: [`evidence/006-argon2id-benchmark.md`](evidence/006-argon2id-benchmark.md) | Task 3 — ✅ **unblocked** |
| **Flyway's BOM-managed version** | read from `mvn -f backend/pom.xml dependency:tree` | Tasks 2–8 |
| **The SQLSTATE strings asserted in tests** (`42501`, `23514`, `23503`) | assert by exception class where the driver allows; where a string is unavoidable, record where it was read | Tasks 2–8 |


### Ratification posture — stated, because `docs/plans/README.md` says not to start on a PROPOSED decision

[`docs/plans/README.md`](../README.md) says: *do not start a plan when it depends on a `PROPOSED` decision that
ratification could still change.* This plan does depend on PROPOSED decisions, and the posture is the same one
`BE-D16`–`BE-D18` and plans 004–005 already carry, stated rather than assumed:

- **What is settled** is the part this plan is built on: the `D10` amendment is ✅ **RATIFIED and `LOCKED` 2026-09-16** — ⚠️ **an earlier revision of this line called it "an approval, not a proposal", which understates it** — and `SM-D33`'s algorithm is confirmed in substance by it (approval §1.2). `BE-D15`'s machinery is PROPOSED; its *shape* is what this plan builds, and the approval settled the one clause of it that was in dispute.
- **What ratification would decide** is whether those decisions *stay*, not whether they may be built.
- **What is genuinely blocked** is listed in the non-goals table, and nothing in the task list depends on it.
- **A contradiction found during execution is design-first**: record it and stop the decision-dependent task.
  Do not invent semantics, relax a constraint, or write a migration around it.

---

## File Structure

| Path | Responsibility |
|---|---|
| `backend/cp-api/src/main/java/com/solace/sm/cp/api/authz/Permission.java` | The **38**-member catalog enum. Nothing else. |
| `…/cp/api/authz/ConsequenceClass.java` | `STANDARD` · `HIGH` · `UNDECIDED`. Three values so the **next** unruled verb cannot arrive as `STANDARD` — no member is `UNDECIDED` today. |
| `…/cp/api/authz/OperationRegistry.java` | The API-operation → verb registry. **Refuses to register an operation against an `UNDECIDED` verb**, which is what makes such an operation unshippable by construction rather than by review. Plan 007's check 11 reads it. |
| `…/cp/api/authz/UnshippableOperationException.java`, `ConsequenceClassUndecidedException.java` | The two refusals the `UNDECIDED` class needs. |
| `…/cp/api/authz/AuthorizationService.java` | `require(Principal, Permission, ResourceRef)`. The only enforcement point. |
| `…/cp/api/authz/ResourceRef.java`, `ResourceKind.java`, `ScopePath.java`, `PrincipalRef.java` | Typed operands. No strings with parsing rules. |
| `…/cp/api/authz/AccessDeniedException.java`, `ResourceNotFoundException.java` | The two refusals §3.4 discriminates. |
| `backend/cp-core/src/main/resources/db/migration/sm_control/V001__scope.sql` | Canonical scope table, the hierarchy tables' composite FKs, `scopes.canonicalize`. |
| `…/sm_control/V002__identity.sql` | `principal`, credential columns, `must_rotate`, status. |
| `…/sm_control/V003__role_and_binding.sql` | `permission_catalog` with a FK from `role_permission`, `role`, `role_binding`, and the built-in role seed. The migration is the role-definition authority; do not duplicate the grants as runtime Java constants. |
| `…/sm_control/V004__session.sql` | Durable session rows, expiry, revocation. |
| `…/sm_control/V005__audit_log.sql` | Append-only audit table. |
| `…/sm_control/V006__dcl.sql` | Initial runtime grants for the built-in roles, sessions and append-only audit. Freeze this version when published; later grants use an additive migration. |
| `…/sm_control/V007__bootstrap_admin.sql` | Exactly one scope row (`kind='install'`) and the bootstrap principal bound to it with `must_rotate`. |
| `…/sm_control/V008__runtime_grants.sql` | Additive runtime grants needed by the completed authorization and bootstrap paths, without changing V006's Flyway checksum. |
| `backend/cp-core/src/main/java/com/solace/sm/cp/authz/ScopeEvaluatingAuthorizationService.java` | The §3.1 algorithm. |
| `…/cp/authz/ScopeRepository.java`, `RoleBindingRepository.java`, `ScopePathResolver.java` | Persistence; `scopePathOf` per §3.2. |
| `…/cp/identity/PrincipalRepository.java`, `CredentialHasher.java`, `SessionRepository.java`, `SessionService.java` | The substrate. |
| `…/cp/persistence/JdbcSessionRepository.java`, `JdbcAuditLog.java` | JDBC adapters for the session and audit ports; the domain packages do not import JDBC. |
| `…/cp/identity/MustRotateGate.java`, `LocalOperation.java`, `CredentialRotationRequiredException.java` | The rotation gate, and the **three non-catalog operations** a `must_rotate` principal may still perform (rotate, logout, read own identity). |
| `…/cp/audit/AuditLog.java` | One append method. No update, no delete. |
| `backend/arch-tests/src/test/java/…/AuthorizationBoundaryTest.java` | The `BE-D15` §5.2 rules + planted violations. |
| `docs/runbooks/install-prerequisites.md` | ⚠️ **The one non-code deliverable.** The operator-facing statement the benchmark's condition 2 requires: chosen hash parameters, the absolute floor, the declared heap floor, the concurrency cap's value, and the **install-time re-measurement step**. Written by Task 3 Step 3c. |

**Why `Permission` lives in `cp-api`:** every caller — `cp-core`, the future gateway, plan 007's controllers —
names verbs, and none of them may import `cp-core` internals. `AuthorizationService` sits beside it for the same
reason.

---

## Task 1 — The catalog and the typed operands, mirrored against the approval document

**Files:**
- Create: `backend/cp-api/src/main/java/com/solace/sm/cp/api/authz/Permission.java`
- Create: `…/authz/ConsequenceClass.java`, `…/authz/OperationRegistry.java`, `…/authz/UnshippableOperationException.java`, `…/authz/ConsequenceClassUndecidedException.java`
- Create (the typed operands every later task consumes — they belong here because `Permission` is useless without them): `…/authz/PrincipalRef.java`, `…/authz/ResourceKind.java`, `…/authz/ResourceRef.java`, `…/authz/ScopePath.java`, `…/authz/AccessDeniedException.java`, `…/authz/ResourceNotFoundException.java`
- Create: `backend/cp-api/src/test/java/com/solace/sm/cp/api/authz/PermissionCatalogTest.java`

**Interfaces:**
- Produces: `enum Permission { … }` — **38 members** — with `String id()`, **`ConsequenceClass consequence()`**, `static Permission ofId(String)` (throws on an unknown id), `static Set<Permission> all()`.
- Produces:

```java
/**
 * A permission's consequence class. THREE values, not two, because the authorization deliberately
 * leaves one verb's class undecided and a boolean cannot represent "undecided" without lying.
 */
public enum ConsequenceClass {
    /** Ordinary. Authorized by its verb alone. */
    STANDARD,
    /** High-consequence per architecture.md §16 / the authorization's §1.1 column. Confirmed, audited. */
    HIGH,
    /**
     * Neither source marks this verb's class (the authorization's §1.1 provenance note). The verb IS
     * a catalog member and IS grantable; what is undecided is its consequence class. Any operation
     * whose authorization depends on the class therefore CANNOT SHIP -- it answers 501, exactly like
     * a verb the authorization withheld. Never widen this to STANDARD "for now": that is the silent
     * decision this value exists to prevent.
     */
    UNDECIDED
}
```
- Produces: `enum ConsequenceClass { STANDARD, HIGH, UNDECIDED }` and
  `OperationRegistry.register(String operationId, Permission verb)` — which **throws
  `UnshippableOperationException` when the verb's class is `UNDECIDED`**.

⚠️ **The Java registry is the enforcement; plan 007's check 11 needs its own, separate leg.** Check 11 is a Python
script over the OpenAPI document — it cannot read a Java class. An earlier draft of this bullet claimed "a build
failure in two places", which was one place that works and one that does not exist. Plan 007 Task 1 now parses the
authorization's §1.1 **consequence column** and rejects an operation naming an unmarked verb; that is the second
place, and the two are independent by design.
- Produces: **`ScopeVisibility.visibleTo(ScopePath granted) → Set<ScopePath>`** — the **bidirectional** predicate
  `SM-D33` requires (at or below a grant, **or an ancestor of something held**), distinct from `isPrefixOf`'s
  one-directional *reaching*. ⚠️ `SM-D33` records that two earlier drafts got this wrong.
- Produces: `record PrincipalRef(UUID id)` · `enum ResourceKind` (one constant per row of `D11-AMENDMENT-PROPOSAL.md` §3.2's table **as superseded** — see that section's supersession table, and [Task 2](#task-2--the-canonical-scope-tree-and-why-a-malformed-path-is-unstorable) here. ⛔ **The set of constants CHANGES: add `SOLUTION`**, which §3.2 has no row for and OR-16 requires. ⚠️ **And two path values change:** `SOLUTION_PACKAGE` → `[orgId]` not `[]` (**OR-14**), and `INSTANCE` → **five** elements `[org, unit, env, solution, instance]` not four (**OR-16**), with `PLACEMENT` following its instance) · `record ResourceRef(ResourceKind kind, UUID id, TenantId tenant)` · `record ScopePath(UUID org, UUID unit, UUID env, UUID solution, UUID instance)` — ⚠️ **five levels per OR-16; `solution` is the added one, and there is NO placement component** with nullable levels and `boolean isPrefixOf(ScopePath)` · `AccessDeniedException` · `ResourceNotFoundException`.

✅ **`user.deactivate` is `ConsequenceClass.HIGH`** — owner ruling, 2026-09-16, recorded in the authorization's
§1.1. So **no catalog member is `UNDECIDED` today**, and that is the point of keeping the third value rather than
collapsing back to a boolean: the class exists so that the *next* verb whose consequence nobody has ruled on cannot
silently arrive as `STANDARD`. The enum ships with an empty `UNDECIDED` set and a test that says so.

What the plan guarantees:

| Guarantee | Enforced by |
|---|---|
| **No member is `UNDECIDED` today**, and a new unmarked one would be caught | Task 1 Step 3b's first test asserts the set is **empty** |
| An `UNDECIDED` verb would stay a catalog member and stay grantable | Task 1's mirror test counts **38** regardless of class |
| An operation whose authorization depends on an `UNDECIDED` class **cannot ship**; it answers `501` | Task 1 Step 3b's registry refusal + plan 007's check 11 |
| Audit logic **never silently treats `UNDECIDED` as `STANDARD`** | Task 7's `kindFor` refuses it explicitly |
| A boolean accessor cannot reintroduce the collapse | Task 1 Step 3b's reflection test over the whole `authz` package |

- [ ] **Step 1: Write the failing mirror test.** It must read the approval document, not a copy of the list.

```java
@Test
void theCatalogIsExactlyTheThirtyEightTheAmendmentEnumerates() throws Exception {
    Path doc = Path.of("..", "..", "docs", "design", "D10-G2-APPROVAL-2026-09-15.md");
    // §1.1's table rows look like: | 7 | `config.view` | amendment §2 — Configuration | no |
    Pattern row = Pattern.compile("^\\|\\s*\\d+\\s*\\|\\s*`([a-z][a-zA-Z.]*)`\\s*\\|");
    Set<String> approved = Files.readAllLines(doc).stream()
            .map(row::matcher).filter(Matcher::find).map(m -> m.group(1))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    assertThat(approved).hasSize(38);   // 35 ratified + 3 from the amendment's §1.7
    assertThat(Permission.all()).extracting(Permission::id)
            .containsExactlyInAnyOrderElementsOf(approved);
}

@Test
void theCatalogContainsNoVerbTheAmendmentWithheld() {
    // instance.stop, audit.view and user.edit were REMOVED from this list on 2026-09-16 --
    // they are catalog rows 36-38. What is still withheld are open item 39's proposals.
    assertThat(Permission.all()).extracting(Permission::id).doesNotContain(
            "instance.delete", "instance.undeclare", "instance.version.redeclare",
            "instance.restart.overrideSafety",
            "strategy.assertRestartAll", "strategy.assertRollingNoValidation",
            "group.view", "group.create", "group.edit", "group.status.edit",
            "group.member.manage");
}
```

- [ ] **Step 2: Run it to watch it fail.** `mvn -f backend/pom.xml -pl cp-api test -Dtest=PermissionCatalogTest` — expected: does not compile, `Permission` not found.
- [ ] **Step 3: Write the enum** — **all 38 ids: §1.1's 35 in its order, then §1.7's three**, each with its **`ConsequenceClass`** read from each table's **consequence column**. ⚠️ **The two tables spell the values differently, and one mapping would mis-parse §1.7:** §1.1's column is *High-consequence* with `yes`/`no`; §1.7's is *Consequence* with `**HIGH**`/`**STANDARD**`. So: `yes` **or** `HIGH` → `HIGH`; `no` **or** `STANDARD` → `STANDARD`; **anything unmarked → `UNDECIDED`**. (Nothing is unmarked as of 2026-09-16 — `user.deactivate` is `HIGH` by ruling **OR-13**.) ⛔ **No boolean.** An earlier draft of this step said *"each with `highConsequence`"*, which is the exact thing Step 5's fourth mutation proves must not exist.
- [ ] **Step 3a: Write `ConsequenceClass`, `OperationRegistry` and the two exceptions.** The registry is a checked-in map from API `operationId` to `Permission`; `register` throws `UnshippableOperationException` when the verb's class is `UNDECIDED`. It is small and it is the artifact that makes "cannot ship" mechanical.
- [ ] **Step 3b: Add the `UNDECIDED` containment tests — and watch them fail first.**

```java
@Test void noCatalogMemberIsUndecidedToday() {
    // Every one of the 38 has a ruled class as of 2026-09-16 (user.deactivate = HIGH was the
    // last). The value stays in the enum so the NEXT unmarked verb cannot arrive as STANDARD.
    assertThat(Permission.all().stream().filter(p -> p.consequence() == UNDECIDED)).isEmpty();
}
@Test void userDeactivateIsHighConsequencePerTheOwnerRuling() {
    assertThat(Permission.USER_DEACTIVATE.consequence()).isEqualTo(HIGH);
}
@Test void anUndecidedVerbWouldStillBeACatalogMemberAndStillGrantable() {
    // Asserted against a fixture verb, since no real member is UNDECIDED. The property must
    // hold for whatever arrives next.
    assertThat(catalogOf(fixtureUndecidedVerb())).contains(fixtureUndecidedVerb());
}
@Test void noApiOperationMayDependOnAnUndecidedConsequenceClass() {
    // Unshippable BY CONSTRUCTION, not by a reviewer noticing. Exercised with a fixture verb
    // because -- per the ruling -- no real member is UNDECIDED any more.
    assertThatThrownBy(() -> OperationRegistry.register("someOp", fixtureUndecidedVerb()))
            .isInstanceOf(UnshippableOperationException.class)
            .hasMessageContaining("consequence class is UNDECIDED");
}
@Test void anOperationNamingUserDeactivateIsShippableBecauseItsClassIsRuled() {
    assertThatNoException().isThrownBy(
            () -> OperationRegistry.register("patchUser", Permission.USER_DEACTIVATE));
}
@Test void noBooleanApiAnywhereInAuthzCollapsesTheConsequenceClass() {
    // A boolean accessor is exactly how UNDECIDED silently becomes STANDARD. Scoped to the
    // whole authz package, all declared methods (not just public ones), and matching the
    // synonyms too -- an earlier draft reflected over Permission's public methods only and
    // would have missed isHigh(), highRisk(), a boolean on ConsequenceClass itself, and any
    // helper in OperationRegistry or the audit package.
    for (Class<?> c : classesIn("com.solace.sm.cp.api.authz", "com.solace.sm.cp.audit"))
        assertThat(c.getDeclaredMethods())
                .noneMatch(m -> m.getReturnType() == boolean.class
                             && m.getName().toLowerCase().matches(".*(consequence|highconseq|highrisk|ishigh).*"));
}
```

- [ ] **Step 4: Run it green.** All six tests pass.
- [ ] **Step 5: Prove the mirror *and* the containment fire — four mutations.** (1) Delete one enum constant → `theCatalogIsExactlyTheThirtyEightTheAmendmentEnumerates` red. (2) Add `group.view` → the withheld-verbs test red. (3) **Change `user.deactivate` to `STANDARD` → `userDeactivateIsHighConsequencePerTheOwnerRuling` red**, and mark any member `UNDECIDED` → `noCatalogMemberIsUndecidedToday` red. (4) **Add a `boolean isHighConsequence()` accessor → `noBooleanApiAnywhereInAuthzCollapsesTheConsequenceClass` red.** Also register an operation against the fixture `UNDECIDED` verb and confirm the registry refuses. Restore; `touch` each file (`mv` preserves mtime).
- [ ] **Step 6: Commit.** `feat(cp-api): the 38-verb catalog with a three-valued consequence class, mirrored against its amendment`

⚠️ **The mirror test is the point of this task.** A catalog that can be widened by editing one Java file is not
code-owned — it is code-editable. The test makes adding a verb require editing the decision record, which is the
review gate the approval asked for.

---

## Task 2 — The canonical scope tree, and why a malformed path is unstorable

**Files:**
- Create: `backend/cp-core/src/main/resources/db/migration/sm_control/V001__scope.sql`
- Create: `backend/cp-core/src/main/java/com/solace/sm/cp/persistence/JdbcScopeStore.java`
- Create: `backend/cp-core/src/test/java/com/solace/sm/cp/authz/ScopeCanonicalizationIT.java`
- Create: `backend/cp-core/src/test/java/com/solace/sm/cp/SmControlPostgres.java` (Testcontainers fixture, `sm_data`'s equivalent as the model)

**Interfaces:**
- Produces: table `sm_control.scope(scope_id, kind, organization_id, organization_unit_id, environment_id, solution_id, instance_id)` — ⚠️ **`solution_id` is added by ruling OR-16; §6.5's own column list omits it** — **column names verbatim from `D11-AMENDMENT-PROPOSAL.md` §6.5, including `organization_unit_id`** (an earlier draft of this plan silently shortened it to `unit_id`, against a Global Constraint that says implement §3.1 literally); §6.5's composite FKs on the hierarchy tables; and `canonicalize`.

✅ **RULED 2026-09-16 — this plan ships the FIVE-level grant tree ruling [OR-16](../../design/DECISIONS.md#owner-architecture-rulings--2026-09-16) requires.** ⛔ **`solution_id` is added now; `placement_id` is NOT added to role bindings.** An earlier revision of this section carried the depth as an open owner question; **it is closed.**

`D11-AMENDMENT-PROPOSAL.md` §6.5's schema is `(organization_id, organization_unit_id, environment_id,
instance_id)`. Ruling **OR-2** makes the canonical hierarchy
`Organization → Sub-organization → Environment → **Solution** → **Instance** → **Placement**`, and says external
APIs, **RBAC scopes** and topics use that vocabulary.

⚠️ **Read §3.2 before concluding what is missing — an earlier revision of this section got it wrong.** That
revision said the absent `placement_id` made a **placement-scoped binding unstorable** and left `instance.stop`
*"half authorizable"*. ⛔ **Both statements are incorrect.** [`D11-AMENDMENT-PROPOSAL.md`](../../design/D11-AMENDMENT-PROPOSAL.md)
§3.2's `scopePathOf` table rules, deliberately:

> **Placement** | **the path of its Instance** | A placement is not a grantable level — you authorize against the
> instance […] Granting *at* a placement would let a binding survive a rebind, which `SM-D23` makes a new
> `placement_id`.

So a `stop` aimed at one placement resolves to its instance's path and **is fully authorizable at both scopes
§1.7 approved** (*"instance or placement"* is the **operation's** target granularity, not a binding level), and
the absence of `placement_id` from the `scope` table is **the design, not a gap**. Task 1's
`ScopePath(org, unit, env, solution, instance)` and `ResourceKind` — *"one constant per row of §3.2's table"* — already
encode exactly this.

**What is genuinely missing is one level, and one conflict:**

| Item | Statement |
|---|---|
| ✅ **ADD `solution_id` — and everything that makes it resolvable** | ⛔ **Required by OR-16, and this task ships all four parts.** A bare column would satisfy the letter of the ruling and break its purpose, because a `[org,unit,env,solution]` binding can only authorize if resources return a path with `solutionId` at position 4. **(1)** `sm_control.solution`, keyed `(organization_id, organization_unit_id, environment_id, solution_id)` — §6.5 defines no such table, so there is nothing for the FK to reference until this task creates it. **(2)** `sm_control.instance` **re-keyed** to carry `solution_id`, so the composite chain reaches the leaf. **(3)** `scope.solution_id` with its composite FK, and `solution` added to the `scope_kind` CHECK. **(4)** `canonicalize` and the prefix rule evaluating **five** components. ⚠️ **§3.2's `scopePathOf` paths change with it** — see that section's supersession table |
| ✅ **Placement stays OUT of role bindings** | ⛔ **Ruled by OR-16**, which raises `D11-AMENDMENT-PROPOSAL.md` §3.2's Placement row from proposal to ruling: Placement is an **operational child/resource identity of Instance** — it appears in APIs, topics, events and runtime operations — and a placement-targeted operation authorizes through **the owning Instance scope** plus any separately required **Target** permission (`D10`'s two-sided rule). ⛔ **Do not add a `placement_id` column to `scope` or to `role_binding`.** ⚠️ **An earlier revision of this table called this an open `OR-2`-vs-§3.2 conflict; OR-16 resolves it and supersedes OR-2's grant half** |
| **Vocabulary** | The column is `organization_unit_id`; OR-2's word is **Sub-organization**. Renaming it is a migration, so this plan keeps §6.5's name and records the divergence rather than half-applying it |
| ⛔ **`scopePathOf(Solution Package)`: OR-14 overrides §3.2, and §3.2 has not been updated** | **A second ruling-versus-proposal conflict, and this one IS resolved — in the ruling's favour.** §3.2 puts Solution Package at **`[]`** and says in terms *"only a `[]` binding reaches `package.load`"* and *"an organization-scoped binding does **not** reach a `[]` resource"*. ⛔ **Ruling [OR-14](../../design/DECISIONS.md) says `package.load` is organization-scoped**, naming the verb and its scope — so under `I4` **the ruling controls and the proposal's row is stale**. ⚠️ **Implement `scopePathOf(SOLUTION_PACKAGE) = [orgId]`, NOT `[]`**, and record the divergence in the same place as the others. ⛔ **Do not generalise it:** `Role definition` stays **`[]`** (§3.2's own row, and no ruling touches it), and `Principal` stays **`[orgId]`**. ⚠️ Plan 007's OpenAPI row 7 already says organization, so building §3.2 literally here would make the two plans' `require(package.load, …)` resolve differently |

✅ **Both halves of handover question 9 are ANSWERED by OR-16 — the question is closed.** **(i)** `solution_id` is
added now. **(ii)** Placement is **not** grantable; OR-2's grant half is superseded. ⛔ **Task 2 therefore extends
§6.5's schema by exactly one column and no more** — ⚠️ **do not add `placement_id`**, which OR-16 forbids for the
reason §3.2 gave: a binding granted at a placement would survive a rebind, and `SM-D23` makes a rebind a new
`placement_id`.

⚠️ **`canonicalize` is a Java operation in `cp-core`, not a SQL function.** §6.5 prints it as
`ScopeId scopes.canonicalize(SessionContext session, ScopeRequest requested)` — two typed **application** operands,
and `SessionContext` is precisely the operand the session-organization property is about, which a SQL function
cannot take. It lands as `ScopeCanonicalizationService.canonicalize(SessionContext, ScopeRequest) → ScopeId` in
`com.solace.sm.cp.authz`, and **no `scopes` database schema is created** (`I7` names `sm_control`; a second schema
would need its own DCL and its own justification). If an implementer concludes it must move into SQL, that is a
design change: record it as a new decision (next free id from the merged register) and re-home the session-organization property first.

**Persistence boundary:** `ScopeCanonicalizationService.canonicalize` and the `ScopeStore` port remain in
`com.solace.sm.cp.authz`; the JDBC implementation is **`com.solace.sm.cp.persistence.JdbcScopeStore`**. It is a
CP persistence adapter, not an authorization class that happens to use JDBC. This is the **Plan 006 target and a
local uncommitted implementation candidate**, not the committed branch baseline. Task 9 narrows the existing global
JDBC-confinement rule to permit exactly `com.solace.sm.dp.apply.persistence..` and
`com.solace.sm.cp.persistence..`; it must continue to reject JDBC from `com.solace.sm.cp.authz..` and every other
package.

- [ ] **Step 1: Write the failing obligations that can run in this task.** `D11-AMENDMENT-PROPOSAL.md` §6.5 lists eighteen obligations: **thirteen** are the local database/Java canonicalization set and **five** are owned elsewhere. Task 2 may run only the **nine** whose enforcing artifacts exist at this point; it must not claim a green result for later-task dependencies.

  | Obligations | Owner | Why |
  |---|---|---|
  | (a)–(d), (f), (l), (m), (o) — **eight** | **Task 2** | their schema constraints are created by `V001__scope.sql`; these are refusal tests |
  | (n) | **Task 2** | Java `ScopeCanonicalizationService.canonicalize` refusal: it has no SQL constraint, but its inputs and scope tree exist after `V001__scope.sql` |
  | (e), (g) — **two** | **Task 4** | both require `role_binding` and its DCL, which Task 4 creates; they cannot be green in Task 2 |
  | (p), (q) — **two** | **Task 8** | (p) needs the final DCL and (q) asserts the install-scope seed; neither artifact exists in Task 2. (q) is a post-migration one-row assertion, not a refusal |
  | (j), (k) — **two** | **Task 5** | §6.5 files them under *"And two that must **succeed**"*: success paths through `require`, not refusals |
  | (h) | **Task 9** | it **is** an ArchUnit rule with a planted violation — and §6.5's own honesty table makes it the **sole** enforcement of the one property no constraint can express: *a scope written from inside this codebase by anything but `canonicalize`* |
  | (i₁), (i₂) | **plan 007 Task 3** | HTTP-response **byte-equality** over §3.4's 404 sets. This plan builds no HTTP |

  Each of Task 2's nine asserts a refusal, but only eight are SQL constraint refusals; (n) is the Java `canonicalize` refusal, e.g.:

```java
@Test
void aScopeRowWhoseUnitBelongsToAnotherOrganizationCannotBeStored() {
    assertThatThrownBy(() -> insertScope("unit", org("A"), unitOf(org("B"))))
            .hasMessageContaining("23503");   // FK violation — not an application check
}

@Test
void canonicalizeRefusesKindInstallBecauseItHasNoOrganizationToResolveWithin() {
    assertThatThrownBy(() -> scopes.canonicalize(session(org("A")), ScopeRequest.install()))
            .isInstanceOf(ScopeNotCanonicalizableException.class);
}

@Test
void canonicalizeResolvesWithinTheSessionsOrganizationAndRefusesAnyOther() {
    assertThatThrownBy(() -> scopes.canonicalize(session(org("A")), ScopeRequest.environment(org("B"), unit, env)))
            .isInstanceOf(CrossOrganizationScopeException.class);
}
```

- [ ] **Step 2: Run them; every Task 2 test fails** (no schema).
- [ ] **Step 3: Write `V001__scope.sql`, `ScopeCanonicalizationService.canonicalize`, and `cp.persistence.JdbcScopeStore`** per §6.5 — the migration creates one row per canonical scope with typed columns (never a path array) and composite FKs so an incoherent ancestor tuple is **unstorable** rather than merely rejected by code; the Java service resolves a real session's organization through the CP-persistence adapter. ⛔ Do not create a SQL `scopes.canonicalize` function or place JDBC in `cp.authz`.
- [x] **Step 4: Run the Task 2 tests after Task 9's global JDBC rule is adjusted.** All nine passed. **Measured local-candidate status, not branch baseline (2026-09-17):** `DOCKER_HOST=unix:///Users/parikshit/.docker/run/docker.sock TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock mvn -f backend/pom.xml -pl cp-core -am verify -Dit.test=ScopeCanonicalizationIT -Dfailsafe.failIfNoSpecifiedTests=false` exited 0. Testcontainers started disposable `postgres:14`; Flyway applied `V001__scope.sql` to PostgreSQL 14.24; `ScopeCanonicalizationIT` ran 20 tests, 0 failures, 0 errors, 0 skipped; the restored `ScopeCanonicalizationServiceTest` ran 3 tests, 0 failures, 0 errors, 0 skipped. This run does not claim (e), (g), (p), or (q), whose migrations have not landed.
- [x] **Step 5: Prove each fires.** The same focused PG14 run executed rollback-only mutation probes that dropped `scope_shape` (a), `scope_organization_unit_fk` for the distinct wrong-type and cross-organization writes (b, d), `scope_environment_fk` (c), `scope_one_environment` (f), `environment_organization_unit_fk` (l), `instance_solution_fk` (m), and `scope_one_install` (o). Every matching malformed write was accepted while its database fence was absent, and its real `assertRefused(...)` assertion failed with the expected missing-throwable `AssertionError`; transaction rollback restored the dropped object and probe rows after every leg. For (n), a temporary local removal of the `requested.isInstall()` refusal caused `ScopeCanonicalizationServiceTest#installScopeCannotBeCanonicalizedFromAnOrganizationSession` to fail as expected (1 test, 1 failure: expected a throwable); the exact guard was restored before the focused PG14 run above. The later-owned obligations remain unexecuted here: (e), (g) Task 4; (p), (q) Task 8; (j), (k) Task 5; (h) Task 9; (i1), (i2) plan 007 Task 3. ⚠️ **An obligation whose removal turns nothing red is a finding**, not a pass.
- [ ] **Step 6: Commit.** `feat(cp-core): the canonical sm_control.scope tree with nine immediately executable obligations`

---

## Task 3 — The identity substrate

**Files:**
- Create: `…/db/migration/sm_control/V002__identity.sql`
- Create: `…/cp/identity/PrincipalRepository.java`, `…/cp/identity/CredentialHasher.java`
- Create: `…/cp/identity/PrincipalIT.java`, `…/cp/identity/CredentialHasherTest.java`
- Create: `docs/runbooks/install-prerequisites.md` — ⚠️ **a deliverable of this task, not a follow-up** (Step 3c). The hash parameters' **install-time re-measurement** and **declared heap floor** are install prerequisites, and by `L19` an obligation with no artifact is not enforced.

**Interfaces:**
- Consumes: Task 2's `scope`.
- Produces: `principal(id, organization_id, source, username, credential_hash, status, must_rotate, created_at, deactivated_at)` with `source ∈ {local, idp}`; `CredentialHasher.hash(char[]) → String`, `verify(char[], String) → boolean`.

⚠️ **Plan correction — D11 amendment §4.1 controls:** `organization_id` is `NOT NULL`, references
`sm_control.organization`, and is recovered from the principal at login to form the server-derived session
`organizationId`. It is never supplied or selected as an authority claim by a client. A principal belongs to exactly
one organization in v1. `username` remains globally unique: login has no client-provided organization discriminator,
so a per-organization uniqueness rule would make local identity resolution ambiguous.

`PrincipalRepository` is the identity port; its JDBC implementation is `cp.persistence.JdbcPrincipalRepository`.
This follows the already-enforced JDBC confinement rather than granting the identity package a persistence exception.

- [ ] **Step 1: Write failing tests.**

```java
@Test void sourceIsConstrainedToLocalOrIdp() { /* insert source='saml' -> 23514 */ }
@Test void aPrincipalHasOneStoredOrganizationAndCannotReferenceAMissingOne() { /* NOT NULL/FK */ }
@Test void theRepositoryReturnsThePrincipalsOrganizationForSessionDerivation() { }
@Test void anIdpPrincipalMayHaveNoCredentialHash() { /* null allowed when source='idp' */ }
@Test void aLocalPrincipalWithoutACredentialHashIsRefused() { /* 23514 */ }
@Test void thereIsNoDeletePath() { /* the runtime role holds no DELETE on principal — 42501 */ }
@Test void deactivationSetsStatusAndTimestampAndKeepsTheRow() { }
@Test void theEncoderIsArgon2idAndNotBcrypt() {
    // Measured 2026-09-16: PasswordEncoderFactories' default prefix is {bcrypt}. This is the
    // test that stops the convenience factory being used to implement an Argon2id ruling.
    assertThat(hasher.hash("pw".toCharArray())).startsWith("{argon2id}$argon2id$");
}
@Test void theParametersAreTheMeasuredOnesAndNeverBelowTheFloor() {
    var params = CredentialHasher.parametersOf(hasher.hash("pw".toCharArray()));
    assertThat(params.memoryKib()).isGreaterThanOrEqualTo(19 * 1024);
    assertThat(params.iterations()).isGreaterThanOrEqualTo(2);
    assertThat(params.parallelism()).isEqualTo(1);
}
@Test void verificationIsConcurrencyCappedSoItCannotExhaustTheHeap() {
    // 384 MiB per in-flight verification: an uncapped login endpoint is a DoS with no credentials.
    assertThat(hasher.maxConcurrentVerifications()).isPositive();
}
@Test void theHashRecordsItsOwnParametersSoItCanBeMigratedLater() {
    String h = hasher.hash("correct horse".toCharArray());
    assertThat(CredentialHasher.parametersOf(h)).isNotEmpty();      // algorithm + cost params are recoverable
    assertThat(hasher.verify("correct horse".toCharArray(), h)).isTrue();
    assertThat(hasher.hash("correct horse".toCharArray())).isNotEqualTo(h);   // salted: two hashes differ
}
@Test void verifyIsConstantTimeAndNeverLogsTheCandidate() { }
```

⚠️ The `thereIsNoDeletePath` DCL refusal and its `GRANT DELETE` mutation are not executable in Task 3: Task 4
creates `V006__dcl.sql`, the runtime role, and its table grants. Task 3 must not synthesize a test-only role or claim
a production privilege result. Task 4 owns that refusal after `V006` exists; Task 3 proves deactivate-never-delete
only through its repository surface and durable row semantics.

- [ ] **Step 2: Run; all fail.**
- [ ] **Step 3: Write the migration and the two classes** — against the **measured** parameters in
  [`evidence/006-argon2id-benchmark.md`](evidence/006-argon2id-benchmark.md). Four things the measurement settled,
  each of which would otherwise be got wrong:

  1. ⛔ **Do NOT use `PasswordEncoderFactories.createDelegatingPasswordEncoder()`.** Measured: its default prefix
     is **`{bcrypt}`**, and `routes to argon2id = false`. OR-7 says *Argon2id through `DelegatingPasswordEncoder`*,
     so construct the delegating encoder **with `argon2id` as the default id**. The convenience factory is the most
     likely way to implement this ruling and silently ship bcrypt.
  2. **Pin `org.bouncycastle:bcprov-jdk18on:1.86` explicitly** — the Spring Boot 3.5.16 BOM **does not manage it**
     (measured: declaring it without a version fails the build). `spring-security-crypto` needs no version; the
     BOM manages **6.5.11**. ⚠️ A crypto-only module also needs a commons-logging binding (`spring-jcl`) or
     `DelegatingPasswordEncoder` construction throws `NoClassDefFoundError`.
  3. **Parameters: `memory = 384 MiB, iterations = 4, parallelism = 1`, salt 16, hash 32** — ≈1.02 s encode /
     1.08 s match on the benchmark host, far above the 19 MiB / t=2 / p=1 floor. ⛔ **Two conditions ship with
     them:** a **bounded concurrency cap** around hashing and verification (384 MiB per in-flight operation makes an
     uncapped credential endpoint a memory-exhaustion DoS needing no credentials) and a **declared heap floor** in the
     install prerequisites.
  4. `CredentialHasher` has **no logger at all** (`I6`) — a class that cannot log cannot log a credential.

  ⚠️ **Install-time re-measurement is an obligation.** The benchmark host is an Apple Silicon laptop with 14 cores;
  a smaller production host is **slower** at the same parameters, which is the user-visible direction. Re-measure
  on the target and tune — **never below the floor**.

  ⛔ **The two conditions split differently, and conflating them loses one of them.** The **concurrency cap is
  code** and ships in this task — the benchmark calls it *"part of implementing this parameter set, not a later
  hardening item"*. The **heap floor and the re-measurement are operator-facing**, and by `L19` an obligation with
  no artifact is not enforced — so this task **writes the artifact** rather than leaving the obligation floating.
- [ ] **Step 3c: Write `docs/runbooks/install-prerequisites.md` — the operator-facing statement the benchmark's
      condition 2 requires** (*"the install prerequisites must state the floor alongside the cap"*). It is a short
      document and it is a deliverable of this task, not a follow-up:
  - the chosen parameters (`384 MiB / t=4 / p=1`), the **absolute floor** (`19 MiB / t=2 / p=1`) and ⛔ **never below it**;
  - the **declared heap floor** — with the measured reason: the benchmark host's default 12 GiB heap hid the cost, and
    **384 MiB × 4 concurrent verifications measured 1,752 ms wall and ~1.5 GiB transient**, which a 1 GiB heap does not survive;
  - the **concurrency cap's** configured value, and that saturation is a fast refusal rather than a queue;
  - ⛔ **the re-measurement step itself**: re-run [`evidence/006-argon2id-benchmark.md`](evidence/006-argon2id-benchmark.md) §2's
    commands on the target host before go-live, and tune from *those* numbers;
  - the measured alternative posture (`96 MiB / t=12 / p=1`, ≈650 ms) so the choice is the owner's and is informed.
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Prove the guards fire.** Remove the `source` CHECK → its test red. Remove the local-needs-hash CHECK → its test red. Remove the principal-organization FK → its test red. **Task 4** grants `DELETE` in its `V006` mutation and proves `thereIsNoDeletePath` red; Task 3 does not claim that later-owned DCL proof.
- [ ] **Step 6: Commit.** `feat(cp-core): the local identity substrate — source discriminator, adaptive hash, deactivate-never-delete`

---

## Task 4 — Roles as data, immutable through the API by DCL

**Files:**
- Create: `…/db/migration/sm_control/V003__role_and_binding.sql`
- Create: `…/cp/authz/BuiltInRoleSeedIT.java`

**Interfaces:**
- Produces: `permission_catalog(permission_id)`, `role(role_id, name, built_in)`, `role_permission(role_id, permission_id)`, `role_binding(binding_id, principal_id, role_id, scope_id)`. The SQL seed, not a parallel Java list, defines the roles.

- [ ] **Step 1: Write failing tests.**

```java
@Test void everySeededRolePermissionNamesACatalogVerb() {
    for (String id : selectAll("SELECT permission_id FROM sm_control.role_permission"))
        assertThat(Permission.ofId(id)).isNotNull();   // ofId throws on an unknown id
}
@Test void theRuntimeRoleCannotWriteTheRoleTable() {
    assertThatThrownBy(() -> runtime().execute("INSERT INTO sm_control.role …"))
            .hasMessageContaining("42501");    // permission denied — obligation (k)'s mirror
}
@Test void exactlyThreeRolesAreSeeded() {
    assertThat(selectAll("SELECT name FROM sm_control.role"))
            .containsExactlyInAnyOrder("Administrator", "Viewer", "Operator");
}
@Test void administratorHoldsAllThirtyEightVerbsAndNoOthers() {
    assertThat(permissionsOf("Administrator")).containsExactlyInAnyOrderElementsOf(
            Permission.all().stream().map(Permission::id).collect(toSet()));
    assertThat(Permission.all()).hasSize(38);
}
@Test void viewerHoldsExactlyTheEightReadVerbs() {
    assertThat(permissionsOf("Viewer")).containsExactlyInAnyOrder(
            "solution.view", "instance.view", "config.view", "health.view",
            "logs.read", "metrics.query", "dashboard.view", "target.view");
}
@Test void operatorHoldsViewersEightPlusElevenAndNothingElse() {
    assertThat(permissionsOf("Operator")).containsExactlyInAnyOrder(
            "solution.view", "instance.view", "config.view", "health.view",
            "logs.read", "metrics.query", "dashboard.view", "target.view",
            "instance.bindTarget", "target.use", "instance.create", "config.edit",
            "config.apply", "config.rollback", "instance.restart", "metric.declare",
            "dashboard.edit", "dashboard.reset", "mesh.ticket.mint");
}
@Test void theAdministratorOnlyVerbsAreInNeitherViewerNorOperator() {
    // Amendment §1.8 consequences 1-5, asserted: an Operator cannot open a live chart tail
    // (no mesh.stream.grant), cannot stop an instance, cannot read the audit log, cannot export.
    for (String adminOnly : List.of("mesh.stream.grant", "instance.stop", "audit.view",
                                    "metrics.export", "metrics.forceRaw", "user.edit"))
        assertThat(permissionsOf("Operator")).doesNotContain(adminOnly);
}
@Test void noUnapprovedRoleNameAppearsInTheSeedOrTheCode() {
    // The mockup's role set is not decision authority. Named explicitly so that a later
    // well-meaning addition turns this red instead of shipping an unapproved grant bundle.
    // Viewer and Operator were REMOVED from this list on 2026-09-16: OR-8 approves them and
    // Step 3 seeds them. Leaving them here made two tests in this task mutually exclusive.
    for (String unapproved : List.of("Org Operator", "Placement Manager", "Auditor",
                                     "Ledger Operator", "Reader", "Deployer"))
        assertThat(seedAndCodeSources()).doesNotContain(unapproved);
}
@Test void rolesAreEvaluatedAsDataAndNoRoleNameAppearsInAnEvaluatedCodePath() { }
@Test void aBindingMustReferenceACanonicalScopeRow() { /* FK 23503 */ }
```

- [ ] **Step 2: Run; all fail.**
- [ ] **Step 3: Write `V003__role_and_binding.sql` *and* the role/binding grants in `V006__dcl.sql`, in this task — seeding exactly THREE roles** (ruling **OR-8**): **`Administrator`** (all **38**, ⛔ **bootstrap/emergency use only**), **`Viewer`** (8), **`Operator`** (19). `built_in = true`; `role_permission` rows reference catalog ids; the runtime role receives `SELECT` on `role`/`role_permission` and **no** `INSERT/UPDATE/DELETE`. The session and audit grants are completed before V006 is published, after V004 and V005 exist. Task 8 uses additive V008 for later grants; editing an already-applied V006 would create a Flyway checksum mismatch.
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Prove it fires.** Grant `INSERT ON sm_control.role` to the runtime role → `theRuntimeRoleCannotWriteTheRoleTable` red. Seed a role with a verb not in the catalog → the first test red.
- [ ] **Step 6: Commit.** `feat(cp-core): built-in roles seeded as data, immutable through the API by DCL`

⛔ **Do not seed the mockup's role set. Seed exactly the three roles OR-8 approved.**

The mockup's Admin catalog (`frontend/reference/sm-v2/app/admin_data.jsx`) proposes several roles — Org Operator,
Placement Manager, Auditor and others. **A decision made inside a mockup is not a decision**, and the 2026-09-15
authorization approved a *permission catalog*, not a *role set*. Seeding those roles would create production
authorization grants nobody approved — a worse failure than having too few roles, because a role is a **bundle of
power** and an unapproved bundle is an unapproved grant.

So v1 seeds **three** roles — `Administrator` (all **38**), `Viewer` (8) and `Operator` (19) — and nothing else.
`Administrator` is the one the amendment describes directly — §1.5: *"The administrator is a built-in role with every catalog verb *bound* — special by
grant, never by code path"* — and ✅ **per the owner ruling of 2026-09-16 it is BOOTSTRAP-ONLY: it exists to be
bound to the install's first principal and to nothing else.** ⚠️ An earlier draft of this task said the mockup set
could be seeded now and ratified later. **That is removed.**

⛔ **And `Administrator` at a narrower scope is NOT a production operator role.** An earlier draft of this note
recommended exactly that as the interim answer; the ruling rejects it, and the reason is worth stating because the
shape is superficially attractive: narrowing the *scope* of a role that holds **all 38 verbs** still grants all 38
verbs everywhere it reaches. That is least *reach*, not least *privilege* — a principal so bound could **override
a config warning, set log levels, export metrics, force the raw tier, stop an instance and read the audit log**
inside its environment. ⚠️ **An earlier revision of this sentence said it could "load packages, edit roles and
deactivate users" there, and that is wrong** — `package.load` is **organization**-scoped (OR-14), role definitions
and principals sit at **`[]`** and **`[orgId]`** respectively (§3.2), so an environment-scoped binding reaches none
of the three. The point stands on the verbs it *does* reach; it does not need the wrong examples.

✅ **What an operator gets instead is `Operator`** (OR-8): Viewer's eight read verbs plus eleven — bind/use a
target, create an instance, edit/apply/roll back configuration, restart, declare a metric, edit/reset a dashboard,
and mint a mesh ticket. **Nineteen verbs, not thirty-eight**, and that is what least privilege means here.

---

## Task 5 — `authorizationService.require` — the algorithm, and the mutation that earns its keep

**Files:**
- Create: `backend/cp-api/src/main/java/com/solace/sm/cp/api/authz/AuthorizationService.java`
- Create: `backend/cp-core/src/main/java/com/solace/sm/cp/authz/ScopeEvaluatingAuthorizationService.java`
- Create: `…/cp/authz/ScopePathResolver.java`
- Create: `…/cp/authz/AuthorizationIT.java`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces:

```java
public interface AuthorizationService {
    /** Throws AccessDeniedException unless `principal` holds `verb` for `resource`. Deny by default. */
    void require(PrincipalRef principal, Permission verb, ResourceRef resource);
}
```

`ScopePathResolver.scopePathOf(ResourceRef) → ScopePath` implements `D11-AMENDMENT-PROPOSAL.md` §3.2 **totally**:
every `ResourceKind` has a row. A kind with no row is a resource outside the check.

- [ ] **Step 1: Write the failing tests — these five are mandatory and named by the spec.**

```java
@Test void aUnitScopedGrantReachesAChildEnvironment() { }
@Test void anEnvironmentScopedGrantDoesNotReachASiblingEnvironment() { }   // ← the mutation target
@Test void aCrossOrganizationResourceIsRefusedBeforeBindingsAreConsulted() { }
@Test void anInstallScopedBindingReachesEveryResource() { }                // the NULL-column trap
@Test void aScopeIsVisibleInEitherDirectionNotOnlyDownward() {
    // SM-D33: visible when "prefix-comparable with a held grant IN EITHER DIRECTION -- at or
    // above it, or an ancestor of something held" -- and it records that TWO earlier drafts
    // got this wrong. Without it a principal granted at [org, unit, dev] 404s on its own
    // parent unit and cannot use the hierarchy cascade that rows 8-10 depend on.
    var granted = scope(org("A"), unit("u1"), env("dev"));
    assertThat(visibleTo(granted)).contains(org("A"), unit("u1"), env("dev"));   // ancestors too
    assertThat(visibleTo(granted)).doesNotContain(env("prod"));                  // never siblings
}
@Test void visibilityIsNotReachability() {
    // Seeing an ancestor grants no verb ON it. Two predicates, deliberately separate.
    assertThatThrownBy(() -> authz.require(p, Permission.CONFIG_APPLY, resourceAt(unit("u1"))))
            .isInstanceOf(AccessDeniedException.class);
}
@Test void existenceAndPermissionAreDiscriminated() { /* 404 vs 403 per §3.4 */ }
@Test void everyResourceKindHasAScopePath() {
    for (ResourceKind k : ResourceKind.values())
        assertThatNoException().isThrownBy(() -> resolver.scopePathOf(sampleOf(k)));
}
@Test void aVerbNotGrantedIsDeniedEvenForTheAdministratorRoleMinusThatVerb() { }
```

- [ ] **Step 2: Run; all fail.**
- [ ] **Step 3: Implement §3.1 literally** — server-derived organization, cross-organization refusal at step 3, prefix match with the per-level `(b.col IS NULL OR b.col = r.col)` comparison, union of role verbs, deny by default. No deny rules. No `isAdmin`.
- [ ] **Step 4: Run green.**
- [ ] **Step 5: The mutations — all four, each recorded.**
  1. Delete the prefix check so every grant reaches everything → `anEnvironmentScopedGrantDoesNotReachASibling…` must go red.
  2. Replace the per-level comparison with a bare `=` → `anInstallScopedBindingReachesEveryResource` must go red.
  3. Replace it with `IS NOT DISTINCT FROM` → the unit-reaches-child test must go red.
  4. Move the cross-organization check after the binding read → its test must go red.
  5. **Make visibility one-directional (drop the ancestor branch)** →
     `aScopeIsVisibleInEitherDirectionNotOnlyDownward` must go red. This is the mutation for the half `SM-D33`
     records two drafts already getting wrong.

  ⚠️ **A scope check whose removal no test detects is not a scope check.** This is the fourth instance of the
  pattern the plan 004 repair wave hit; treat a non-firing mutation as a test defect and fix the test.
- [ ] **Step 6: Commit.** `feat(cp-core): scope-evaluated authorization with downward inheritance, four firing mutations`

---

## Task 6 — Durable sessions: expiry, revocation, CSRF token issuance

**Files:**
- Create: `…/db/migration/sm_control/V004__session.sql`
- Create: `…/cp/identity/SessionRepository.java`, `…/cp/identity/SessionService.java`
- Create: `…/cp/persistence/JdbcSessionRepository.java`; keep JDBC outside `cp.identity`.
- Create: `…/cp/identity/SessionIT.java`

**Interfaces:**
- Produces: `SessionService.open(PrincipalRef) → SessionToken`, `resolve(SessionToken) → Optional<Session>`, `revoke(SessionId)`, `revokeAllFor(PrincipalRef)`, `csrfTokenFor(SessionId)`.

- [ ] **Step 1: Write failing tests.** ⚠️ Three values these tests assert are **decisions, not defaults** — fix them in Step 3 and record them: the session-token length and source of randomness, the hash the table stores it under, and the idle/absolute expiry pair (`FE-D24` owns the console-side idle value; the server's own absolute ceiling is this plan's).

```java
@Test void aSessionIsIssuedOnlyToAnActivePrincipal() { }
@Test void deactivatingAPrincipalEndsEverySessionItHolds() { }
@Test void anExpiredSessionResolvesEmptyWithoutBeingDeleted() { }   // auditable, not erased
@Test void bothIdleAndAbsoluteExpiryEndASession() { }
@Test void aRevokedSessionResolvesEmptyImmediately() { }
@Test void theStoredSessionValueIsNotTheCookieValue() {
    // the cookie carries an opaque token; the table stores a hash of it
}
@Test void aCsrfTokenIsBoundToOneSessionAndVerifiesOnlyForIt() { }
@Test void mustRotateOnThePrincipalIsVisibleOnTheResolvedSession() { }
```

- [ ] **Step 2: Run; all fail.**
- [ ] **Step 3: Implement, with these four choices made explicitly rather than by default.** (1) The **cookie value** is ≥ 256 bits from `SecureRandom`, base64url, opaque — never a principal id, never a JWT. (2) The **table stores a hash of it** (a plain digest is sufficient and correct here: the token is already high-entropy, so no adaptive KDF is needed — unlike a password), so a database read cannot impersonate a live session. (3) **Two expiries:** an idle deadline refreshed on use, and an absolute ceiling that is never refreshed; resolution fails when either has passed. (4) **The CSRF token is derived per session** and verified against the resolved session, so a token from another session fails.
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Prove the guards fire.** Store the raw token → `theStoredSessionValueIsNotTheCookieValue` red. Skip the status check on issue → its test red. Refresh the absolute deadline on use → `bothIdleAndAbsoluteExpiryEndASession` red. Make CSRF verification session-independent → its test red.
- [ ] **Step 6: Commit.** `feat(cp-core): durable sessions with real expiry, revocation and per-session CSRF`

⚠️ **This task issues no cookie and sets no header.** It provides the values plan 007's HTTP layer sets. Keeping
the boundary here is what lets §4's contract be tested without a servlet container.

---

## Task 7 — The append-only audit log

**Files:**
- Create: `…/db/migration/sm_control/V005__audit_log.sql`
- Create: `…/cp/audit/AuditLog.java`
- Create: `…/cp/persistence/JdbcAuditLog.java`; keep JDBC and JSON serialization outside `cp.audit`.
- Create: `…/cp/audit/AuditLogIT.java`

**Interfaces:**
- Produces: `AuditLog.record(AuditEntry)` — one method — and the record it takes:

```java
public record AuditEntry(Instant at, PrincipalRef actor, Permission verb, ResourceKind resourceKind,
                         UUID resourceId, TenantId tenant, Outcome outcome, Map<String,Object> detail) {
    public enum Outcome { ALLOW, DENY }
    /**
     * The audit kind a HIGH-consequence verb is recorded under; null for a STANDARD verb that needs
     * no audit line. Throws ConsequenceClassUndecidedException for an UNDECIDED verb -- returning
     * null ("treat it as standard") would silently decide what the authorization left open.
     */
    public static AuditKind kindFor(Permission verb) { … }
}
```

No update, no delete, no query. ✅ **The read's verb now exists — `audit.view`** (STANDARD, scope-filtered,
server-redacted; amendment §1.7) — but the **unioned read endpoint** belongs to plan 007/009, so **no read method
ships here**. ⚠️ `audit.view` is **Administrator-only** under OR-8, so audit review is an administrative act in v1.

- [ ] **Step 1: Write failing tests.**

```java
@Test void theRuntimeRoleCanInsertAndSelectButNeverUpdateOrDelete() {
    assertThatThrownBy(() -> runtime().execute("UPDATE sm_control.audit_log SET outcome='allow'"))
            .hasMessageContaining("42501");
    assertThatThrownBy(() -> runtime().execute("DELETE FROM sm_control.audit_log"))
            .hasMessageContaining("42501");
}
@Test void anEntryIsWrittenInsideTheCallersOwnTransactionAndRolledBackWithIt() { }
@Test void noAuditDetailPayloadMayCarryACredentialOrSessionToken() { /* I6 shape guard */ }
@Test void everyHighConsequenceVerbHasAnAuditEntryKind() {
    for (Permission p : Permission.all())
        if (p.consequence() == HIGH) assertThat(AuditEntry.kindFor(p)).isNotNull();
}
@Test void kindForRefusesAnUndecidedVerbRatherThanTreatingItAsStandard() {
    // A default-to-STANDARD branch here would silently decide what nobody has ruled on, and no
    // other test would notice. Exercised with a fixture verb: user.deactivate is HIGH now.
    assertThatThrownBy(() -> AuditEntry.kindFor(fixtureUndecidedVerb()))
            .isInstanceOf(ConsequenceClassUndecidedException.class);
}
@Test void userDeactivateHasAnAuditKindBecauseItIsHighConsequence() {
    assertThat(AuditEntry.kindFor(Permission.USER_DEACTIVATE)).isNotNull();
}
@Test void everyVerbsClassIsOneOfTheThreeWithNoNullAndNoDefault() {
    for (Permission p : Permission.all())
        assertThat(p.consequence()).isIn(STANDARD, HIGH, UNDECIDED);
}
```

- [ ] **Step 2: Run; all fail.**
- [ ] **Step 3: Implement.** `V005` creates the table with no `UPDATE`/`DELETE` path; `V006` grants the runtime role `SELECT, INSERT` and nothing else. `AuditLog.record` participates in the **caller's** transaction (no new transaction, no `REQUIRES_NEW`) — that is what makes a rolled-back mutation leave no audit line, and what makes an audited mutation impossible to commit without its line.
- [ ] **Step 4: Run green.**
- [ ] **Step 5: Prove it fires.** Grant `UPDATE` → the first test red. Move the write outside the caller's transaction → the second red.
- [ ] **Step 6: Commit.** `feat(cp-core): append-only sm_control.audit_log, append-only by DCL rather than by convention`

---

## Task 8 — DCL, and the bootstrap administrator under forced rotation

**Files:**
- Create: `…/db/migration/sm_control/V007__bootstrap_admin.sql`, `…/sm_control/V008__runtime_grants.sql`; V006 is already applied and must not be edited.
- Create: `…/cp/identity/BootstrapAdminIT.java`
- Create: `…/cp/identity/MustRotateGate.java`, `CredentialRotationRequiredException.java`, `LocalOperation.java`

**Interfaces:**
- Produces:

```java
public final class MustRotateGate {
    /** Throws CredentialRotationRequiredException when the session's principal carries must_rotate. */
    public void check(Session session, Permission attempted) { … }

    /** The escape hatch: the three operations a must_rotate principal may still perform. */
    public void check(Session session, LocalOperation op) { … }
}

/** cp-core-local, NOT part of the permission catalog -- the catalog has 38 members and none of these. */
enum LocalOperation { ROTATE_OWN_CREDENTIAL, LOGOUT, READ_OWN_IDENTITY }
```

⚠️ **The rotation exemption is an operation allow-list, never a permission.** An earlier draft of this task wrote
`ROTATE_OWN_CREDENTIAL` as a `Permission` constant — which the approved catalog does not contain, so Task 1's
mirror test would have failed and **this task would not have compiled**. Rotating one's own credential is not a
grantable verb: every principal may always do it. The gate's job is to make it the *only* thing a `must_rotate`
principal may do.

⛔ **Three operations, not two — and the gate must be fail-closed for verbless endpoints.** An earlier draft listed
two and could not express the third: `GET /cp/auth/identity` is reachable under `must_rotate`
([`console-integration.md`](../../design/common/contracts/console-integration.md) §4.2) and carries **no
`Permission`** (it is *authentication only*), so neither a `Permission` overload nor a two-member `LocalOperation`
could name it. Worse, the shape was **fail-open**: `check(Session, Permission)` is only reachable on catalog-verb
endpoints, so every `none:authenticated` operation would have bypassed `must_rotate` in silence. Hence
`READ_OWN_IDENTITY`, and hence the rule: **every endpoint calls the gate — with a `Permission` if it has one, with
a `LocalOperation` if it does not — and an endpoint that names neither is a defect** that Task 9's boundary rule
catches.

- [ ] **Step 1: Write failing tests.**

```java
@Test void theMigrationCreatesExactlyOneScopeRowAndItIsTheInstallScope() {   // obligation (q)
    assertThat(count("SELECT count(*) FROM sm_control.scope")).isEqualTo(1);
    assertThat(one("SELECT kind FROM sm_control.scope")).isEqualTo("install");
}
@Test void theBootstrapPrincipalIsSeededWithMustRotate() { }
@Test void mustRotateBlocksEveryCatalogVerbWithoutException() {
    for (Permission p : Permission.all())
        assertThatThrownBy(() -> gate.check(bootstrapSession(), p))
                .isInstanceOf(CredentialRotationRequiredException.class);
}
@Test void mustRotateAllowsExactlyRotationLogoutAndOwnIdentity() {
    for (LocalOperation op : LocalOperation.values())
        assertThatNoException().isThrownBy(() -> gate.check(bootstrapSession(), op));
    assertThat(LocalOperation.values()).hasSize(3);   // widening it is a decision, not a convenience
}
@Test void aVerblessAuthenticatedEndpointIsRefusedUnlessItNamesALocalOperation() {
    // The fail-open hole: an endpoint that calls NEITHER overload would bypass must_rotate.
    assertThatThrownBy(() -> gate.checkUnnamed(bootstrapSession()))
            .isInstanceOf(CredentialRotationRequiredException.class);
}
@Test void aPrincipalWithoutMustRotateIsNotGatedAtAll() { }
@Test void rotationClearsMustRotateAndTheOldCredentialStopsVerifying() { }
@Test void theSeededCredentialIsSingleUse() { }
@Test void noSeededCredentialValueAppearsInTheMigrationFile() {
    assertThat(Files.readString(migration("V007__bootstrap_admin.sql")))
            .doesNotContainPattern("(?i)password\\s*=\\s*'");   // the value arrives from the installer, not the SQL
}
```

- [ ] **Step 2: Run; all fail.** **Step 3: Implement.** The credential arrives as an installer-supplied parameter; the migration seeds the principal and the binding, never a literal secret.
- [ ] **Step 4: Run green.**
- [ ] **Step 4a: Add the rotation *substrate* tests — the HTTP half is plan 007 Task 3's, these are the ones that need no endpoint.** The contract's worst named outcome is *"a partial rotation that clears the flag without replacing the hash"*, and nothing asserted it:

```java
@Test void rotationVerifiesTheCurrentCredentialBeforeChangingAnything() { }
@Test void rotationToTheSameCredentialIsRefused() { }
@Test void aWrongCurrentCredentialIsRefusedAndMovesTheLockoutCounter() { }
@Test void rotationIsAtomic() {
    // Force a failure between the hash write and the flag clear; assert NEITHER happened.
    assertThatThrownBy(() -> rotateWithFailureAfterHashWrite(...)).isInstanceOf(...);
    assertThat(mustRotate(principal)).isTrue();
    assertThat(hasher.verify(oldCredential, storedHash())).isTrue();
}
@Test void rotationRevokesTheOtherSessionsOfThatPrincipalAndKeepsTheCurrentOne() { }
@Test void neitherCredentialAppearsInAnyLogLineAuditRowOrExceptionMessage() { }
```

- [ ] **Step 5: Prove they fire.** Add a second scope row to `V007` → obligation (q)'s test red. Remove the `must_rotate` seed → its test red. Let the gate pass a catalog verb → its test red. **Split the rotation into two transactions → `rotationIsAtomic` red.** **Skip the other-session revocation → its test red.**
- [ ] **Step 6: Commit.** `feat(cp-core): install-seeded bootstrap administrator with server-enforced credential rotation`

---

## Task 9 — The boundary rules, with planted violations

**Files:**
- Modify: `backend/arch-tests/src/test/java/com/solace/sm/arch/PlaneBoundaryTest.java`
- Create: `backend/arch-tests/src/test/java/com/solace/sm/arch/AuthorizationBoundaryTest.java`
- Create: five authorization fixture classes under `arch-tests`' violation-fixture package, plus the JDBC-confinement fixtures needed to prove the newly legal CP persistence package and forbidden CP authz use.

**Interfaces:**
- Produces: `@ArchRule`s `endpointsDependOnAuthorizationServiceOnly`, `noIsAdminAnywhere`, and
  `cpCoreNeverImportsDp`, plus a reviewed role-name-branching guard. The former
  `onlyAuthzPackageNamesRoles` rule targeted a
  `BuiltInRoles` class that is not built; Task 9 must replace it with a non-vacuous guard for role-name branching
  and prove the guard with a planted violation. Obligation (h)'s exact guard remains an unresolved Task 9
  proof-design item; it is not claimed implemented by an untested ArchUnit rule.
- Modifies the existing global JDBC-confinement rule (currently `jdbcIsConfinedToApplyPersistence`) so **only**
  `com.solace.sm.dp.apply.persistence..` and `com.solace.sm.cp.persistence..` may depend on JDBC, datasource,
  driver, pool or Flyway types. It does not grant an exception to `com.solace.sm.cp.authz..`.

⛔ **Obligation (h) is unresolved, not waived.** The implementation roles are known:
`ScopeCanonicalizationService` is the service caller of `ScopeStore.canonicalize`, and
`cp.persistence.JdbcScopeStore` is the runtime JDBC writer of `sm_control.scope`. The completed proof must show
both that no other service calls the port and that no other runtime source writes that table, with planted violations.
The exact guard design is deliberately left for Task 9 implementation/review: ArchUnit does not inspect SQL literals,
and no source-guard parser is specified or claimed green here. PG14 integration remains the behavior-level proof.

- [ ] **Step 1: Write the five authorization rules and their five planted-violation fixtures, and adjust the global JDBC-confinement rule with planted proofs.** For (h), first design and review an executable two-boundary proof for the sole `ScopeStore.canonicalize` service caller and the sole runtime `sm_control.scope` writer. Do not substitute an untested ArchUnit DSL snippet or claim SQL-literal inspection.


```java
@ArchTest static final ArchRule noIsAdminAnywhere = noClasses()
        .should().callMethodWhere(nameMatching("(?i).*isAdmin.*|.*isSuperAdmin.*"))
        .because("the administrator is special by grant, never by code path (approval §1.5)");

// Do not retain the earlier BuiltInRoles dependency rule: with no BuiltInRoles class,
// it passes vacuously. Design a guard over actual evaluated role-name branching,
// then plant a violation and prove it turns red.
```

- [ ] **Step 2: Run against the fixtures.** Each authorization rule reports exactly its own violation; the adjusted JDBC rule reports an out-of-bound JDBC dependency, accepts a fixture in `dp.apply.persistence`, accepts a fixture in `cp.persistence`, and rejects a JDBC fixture in `cp.authz`. Do not report (h) complete until its reviewed guard and both planted violations exist.
- [ ] **Step 3: Exclude the fixtures from the production scan** the way the existing `PlaneBoundaryTest` does, so they prove the rule without breaking the build.
- [ ] **Step 4: Run green with fixtures excluded and red with them included.** Record both. **Measured local-candidate status, not branch baseline:** `mvn -f backend/pom.xml -o -q test` passes against the uncommitted `JdbcScopeStore`/global-rule implementation. This does **not** close (h), whose exact guard is unresolved, and PostgreSQL-14/Testcontainers integration tests remain unrun and are not claimed green.
- [ ] **Step 5: Commit.** `test(arch-tests): five authorization boundary rules, each with a planted-violation proof`

---

## Task 10 — Close the plan

- [ ] **Step 1: Full gate.** `mvn -f backend/pom.xml clean verify` · `./contracts/run-checks.sh --strict` · the DDL replay harness extended to `sm_control` (or, if that harness is `sm_data`-specific, state so and record what was run instead) · `git diff --check`.
- [ ] **Step 2: All-16 Plan 006 scope-obligation completion gate.** After Tasks 2, 4, 5, 8 and 9 have landed their dependencies, run the sixteen §6.5 obligations this plan owns: the thirteen local database/Java canonicalization obligations (a)–(g), (l), (m), (n)–(q), plus (h), (j), (k). Prove the refusal and success semantics named by each; specifically, assert (q)'s one install-scope row after `V007`, never as a refusal. `(i1)` and `(i2)` remain the HTTP-response byte-equality checks owned by **plan 007 Task 3**. Plan 006 cannot claim all eighteen green; that claim is reserved until plan 007 verifies those two checks.
- [ ] **Step 3: Write the Results section** — the sixteen Plan 006 scope obligations with their prove-it-fires outcomes, an explicit handoff of (i1)/(i2) to plan 007 Task 3, the four `require` mutations, every DCL refusal, exact test counts, and every skip with its reason.
- [ ] **Step 4: Record what this plan learned as decisions** — ⛔ **allocating the next free id from the merged `DECISIONS.md`** (ruling **OR-11**), not from a number reserved here. Anything that contradicts the design is **design-first: record it and stop**, never invent semantics.
- [ ] **Step 5: Independent adversarial review** before claiming completion. The review must specifically test: a verb outside the ratified 38; whole-install evaluation smuggled back in; a controller-local check; a bypass flag; an audit path that can be updated; a session that survives revocation; and a mutation that did not fire.
- [ ] **Step 6: Commit.**

---

## Executable subset — ✅ every task executable, and ✅ the scope depth is now RULED

**No decision and no measurement blocks a task in this plan.** The password-hash question is **decided (OR-7)**
and the **development-host measurement is complete**
([`evidence/006-argon2id-benchmark.md`](evidence/006-argon2id-benchmark.md)), and OR-1…OR-15 settle the catalog, the
roles and the **four** questions this plan used to route (R1–R4).

⚠️ **That is not the same as "nothing is outstanding", and an earlier revision of this line said so wrongly.**
✅ **The scope-depth question that used to sit here is CLOSED (OR-16).** **One** thing remains outstanding and it
blocks no task here: the **install-time re-measurement** of the hash parameters, which is an **install
prerequisite** (see the note under the table).

⚠️ **This is deliberately not phrased "fully executable".** Every task below can be started, but **Task 2 delivers a
scope tree with no `solution_id`**, so the plan cannot claim complete coverage of OR-2's grantable hierarchy. An
earlier revision of this heading said "fully executable", which read past that.

✅ **The depth question is CLOSED by ruling [OR-16](../../design/DECISIONS.md#owner-architecture-rulings--2026-09-16),
which amends `D11` and names it.** The grant tree is **five** levels —
`Organization → Sub-organization → Environment → Solution → Instance` — so Task 2 **adds `solution_id`** and a
solution-scoped binding **is** expressible. ⛔ **Placement is not a grantable level** and gets no column: a
placement-targeted operation authorizes through **the owning Instance scope** plus any required **Target**
permission, so `instance.stop` is fully authorizable at both the targets §1.7 approved.
See [Task 2](#task-2--the-canonical-scope-tree-and-why-a-malformed-path-is-unstorable).

⚠️ **Two earlier revisions of this section were wrong in opposite directions, and both are corrected.** One said a
**placement-scoped binding is unstorable** and `instance.stop` was *"half authorizable"* — it is not. The next
called the depth an **open owner question** — OR-16 closes it.

✅ **Nothing about the scope tree remains open.** Every task below is executable, and Task 2's deliverable is now
**wider** than it was — five columns, not four.

⚠️ **One further question is routed here rather than left in the approval record** — the amendment's §1.8
consequence 2 says an Operator *"may cause an outage by restarting"* but not by stopping, and that *"it is worth
the owner confirming"*: **is `instance.stop` deliberately Administrator-only while `instance.restart` is an
Operator verb?** ⛔ **Until the owner answers, Task 4 seeds the lists exactly as ratified** — do not move
`instance.stop` into `Operator`. It blocks no task here. ⛔ **It is independent of the broker gates**, which are a separate
matter entirely (they gate plan 007's mesh tasks, not anything here).

| Task | Status | What unblocked it |
|---|---|---|
| 1 — catalog, typed operands, `ConsequenceClass`, `OperationRegistry` | ✅ | OR-9 settles the catalog at **38**; **OR-13** rules `user.deactivate` = `HIGH`, so **no member is `UNDECIDED`** |
| 2 — the canonical scope tree, nine immediately executable obligations | ✅ **executable, and now FIVE levels** | Ruling **OR-16** adds `solution_id`, so a solution-scoped binding **is** expressible. The §6.5 total remains 18: Task 2 owns nine now; Tasks 4, 5, 8, 9 and plan 007 own the other nine. Task 10 closes this plan's 16 owned obligations; plan 007 Task 3 must verify (i1)/(i2) before anyone claims all 18. ⛔ **No `placement_id`** — Placement authorizes through its Instance, so `instance.stop` is fully authorizable at both approved targets |
| 3 — the identity substrate | ✅ **newly unblocked** | OR-7 + the measurement. ⚠️ Read Task 3's four measured findings before coding — the convenience factory ships bcrypt |
| 4 — three roles seeded as data, immutable by DCL | ✅ | OR-8 names them: Administrator (38, bootstrap-only), Viewer (8), Operator (19) |
| 5 — `require` and the four mutations | ✅ | **OR-15** settles the identity endpoint's check |
| 6 — durable sessions | ✅ **newly unblocked** | follows Task 3 |
| 7 — append-only audit log | ✅ | **OR-13** gives `kindFor` its answer; ✅ `audit.view` now exists as the read verb, though this plan ships no read method |
| 8 — bootstrap admin under forced rotation | ✅ **newly unblocked** | follows Task 3 |
| 9 — the five boundary rules | ✅ | — |
| 10 — close the plan | ✅ | nothing outstanding |

⚠️ **Three things, and they are NOT the same kind of thing** — an earlier revision of this note said *"both are
install prerequisites rather than code"*, which mislabelled one of them:

1. ✅ **The concurrency cap is CODE**, and Task 3 Step 3 ships it. The benchmark's condition 1 calls it *"part of
   implementing this parameter set, not a later hardening item"*.
2. ⛔ **The install-time re-measurement is an INSTALL PREREQUISITE** — no task in this plan or plan 007 performs
   it, because it can only run on the target host. Task 3 Step 3c **writes it down** so an operator meets it.
3. ⛔ **The declared heap floor is an INSTALL PREREQUISITE** for the same reason, and is written in the same place.

⛔ **Neither prerequisite blocks any task, and neither may be skipped at install.** Both directions of that
sentence matter: calling them blockers would stall executable work, and dropping them would ship a login endpoint
whose cost was measured on the wrong machine.

---

## Decisions this plan routed — all four now RULED and registered centrally

⚠️ **These are no longer this plan's to assert.** All four were ruled on 2026-09-16 and are recorded in
[`DECISIONS.md`](../../design/DECISIONS.md) as **OR-12…OR-15** — because §0 of the `D10` amendment forbids a
decision living only inside a plan (*"ordinary implementation approval is not an amendment"*). The rows below cite
those register entries; the register is the authority.

Four questions this plan **must not answer by writing code**. Each carries a recommendation and the concrete
consequence of leaving it open, so the owner can rule on a page rather than on a problem statement. **Where a task
depends on one, the task stops rather than guessing** — that is the standing rule, and these are the instances.

| # | Decision | Recommendation | Consequence while open |
|---|---|---|---|
| R1 → ✅ **registered centrally as [OR-12](../../design/DECISIONS.md)** | ✅ **RULED and CLOSED 2026-09-16.** `Administrator` is **bootstrap/emergency only** — and the production role set is now decided too (**OR-8**): **Viewer** (8 read verbs) and **Operator** (19). | **Seed three roles as data.** ⛔ **Do not present `Administrator` at a narrow scope as a production operator role** — an earlier draft of this row recommended exactly that, and it is rejected: narrowing the *scope* of a role holding all 38 verbs still grants all 38 wherever it reaches, which is least *reach*, not least *privilege*. **`Operator` is what an operator gets.** | ✅ Closed. Least privilege for operators is **available** in v1. ⚠️ **Five** consequences of the role lists are recorded in the amendment's §1.8 and asserted by Task 4 — most notably that an **Operator cannot open a live chart tail** (no `mesh.stream.grant`) and **cannot stop an instance** (`instance.stop` is Administrator-only) |
| R2 → ✅ **registered centrally as [OR-13](../../design/DECISIONS.md)** | ✅ **RULED 2026-09-16: `HIGH`.** Neither `catalog.ts` nor `architecture.md` §16 marked it, so this is a decision of record rather than a copied marking | **`ConsequenceClass.HIGH`** — deactivation ends a person's access and voids the effect of their grants, so it is audited and confirmable like every other high-consequence verb | ✅ Closed. `PATCH /cp/admin/users/{id}` is no longer blocked by an undecided class — ✅ and `user.edit` now covers the non-status fields, so the operation is fully authorizable. `catalog.ts` carries no flag for it; that divergence stays in the authorization's §6 item 5 |
| R3 → ✅ **registered centrally as [OR-14](../../design/DECISIONS.md)** | ✅ **RULED 2026-09-16: organization-scoped.** (`console-integration.md` §5.2 row 7) | **Organization.** A multi-organization install must not need an install-wide grant to load a package, and the narrower default is the one that can be widened later without re-granting | ✅ Closed. Row 7's Basis moves `P` → `A` and the OpenAPI can fix the scope |
| R4 → ✅ **registered centrally as [OR-15](../../design/DECISIONS.md)** | ✅ **RULED 2026-09-16: no verb required to read the current principal.** (`console-integration.md` §4.3) | **Candidate (a):** `GET /cp/auth/identity` requires **authentication only** — every session may read its *own* identity and effective permissions. `authz.introspect.self` governs the Admin **access-review** screen instead. Rationale: a principal who can act but renders an empty overlay is a worse failure than a slightly coarse verb | ✅ Closed. Row 1's verb is *authentication only*, the endpoint is buildable, and `MustRotateGate`'s `READ_OWN_IDENTITY` is what keeps it reachable under `must_rotate` |

⚠️ **Three further decisions belong to plan 007, not here**, and are named so nobody looks for them in this plan:
the `BE-D5` broker ACL shape (open items 32/33), environment-level notify isolation (open item 35), and the
`BE-D4` terminal-apply-outcome event's name and schema.

---

## Scope and non-goals — stated exactly

**Not in this plan, and why:**

| Not here | Why |
|---|---|
| Any HTTP endpoint | Plan 007. This plan ships a tested library and migrations |
| strategy-assertion, `group.*`, undeclare, package-action, `instance.version.redeclare`, archive/restore and agent verbs | **The approval withheld them** — open item 39's six remaining letters (b, c, d, e, h, i). Adding one would widen a bounded approval (approval §3, §5). ⛔ **`instance.stop`, `audit.view` and `user.edit` are NOT in this row** — ruling **OR-9** approved them as catalog rows 36–38, and an earlier revision of this row wrongly listed `stop` and the audit read as withheld, which would have produced a 36-member enum that fails Task 1's own mirror test |
| Custom roles, role editing, user CRUD endpoints, group CRUD | v1 is built-in roles only; the write path is a later migration |
| SSO / SCIM / `D13` | Deferred. `source ∈ {local, idp}` is the seam it will use |
| The mesh ticket endpoints | Plan 007, and they need `BE-D14` §4.1's shapes |
| `demo-launcher`'s removal | Plan 007 — removing it without a replacement API leaves no working demo |
| The frontend's catalog reconciliation | Needs the `group.*` decision first (approval §6) |
