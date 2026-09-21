# Minimum delivery sequence — one Cache solution, controlled from the console, through a real auth/RBAC path

> ⚠️ **PARTIALLY SUPERSEDED, last updated 2026-09-16. Read this banner before acting on anything below.**
>
> 1. ✅ **G2 is closed and the `D10` amendment is RATIFIED and `LOCKED`** —
>    [`../design/D10-G2-APPROVAL-2026-09-15.md`](../design/D10-G2-APPROVAL-2026-09-15.md). §2.1's *"this holds until
>    the architect group ratifies G2 (open item 24), or it grants a signed, narrow, written waiver"* is **fully
>    discharged**: items 24, 38 **and 40** are closed, and **the project owner is the final architecture
>    authority** — no countersign is outstanding. ⛔ Where old `D10` conflicts with the amendment, the amendment
>    controls.
> 2. ✅ **W5's `Operator` role IS approved** — ruling **OR-8**, 2026-09-16: three built-in roles, **Administrator**
>    (all 38 verbs, **bootstrap/emergency only**), **Viewer** (8) and **Operator** (19). §W5 step 3's
>    `demo-operator` bound to **`Operator` at Environment scope** is therefore the **correct** shape. ⚠️ **Two
>    different plans, not one:** [plan 006](../plans/backend/006-local-identity-and-scoped-rbac.md) Task 4 seeds
>    the **three built-in roles** as data, while the `demo-operator` **principal and its binding** come from
>    **W5's authenticated setup command** `sm-admin provision-demo-operator` (§3 W5 step 3), not from plan 006
>    Task 4. An earlier revision of this line said "plan 006 Task 4 seeds it", which is only half true. ⚠️ An interim banner here
>    previously **forbade** the `Operator` name, because only `Administrator` was approved at the time; **that
>    prohibition is lifted.** What remains true: ⛔ `Administrator` is bootstrap/emergency only and must never be
>    presented as an operator account, at any scope.
> 3. ⛔ **The live-notify half of the demonstration is still hard-gated** by open items 32/33/35, and a
>    tenant-wide notify subscription must not be used to demonstrate scoped RBAC — it would show the opposite of
>    the model. The *architecture* is now decided (**one Message VPN per Organization**, ruling **OR-5**); its ACL
>    **enforcement is unmeasured**, and the spike matrix in
>    [`../design/common/contracts/console-integration.md`](../design/common/contracts/console-integration.md) §11.3
>    ⛔ **must not run while this demo is active**.
> 4. ✅ **The canonical hierarchy is six levels** (ruling **OR-2**): `Organization → Sub-organization → Environment
>    → Solution → Instance → Placement`, on immutable ids. Text below that says *Organization Unit*, or that stops
>    at *Solution Instance*, is stale vocabulary.
> 5. ✅ **The demo's monitoring has a replacement plan** —
>    [plan 009](../plans/backend/009-console-api-and-monitoring-replacement.md), enabled by ruling **OR-10**'s
>    `console-api` module (open item 21 closed). ⛔ **`demo_control` cannot be dropped until plan 009 lands.**
>
> ⚠️ **Everything else below stands only as corrected in place.** The workstream split, W1/W3's shapes and W5's compose/seed structure are unchanged; the G2 gate rows, the scope-depth notes and the role/verb counts are **annotated inline** where they went stale.

| | |
|---|---|
| **Target** | A Cache instance configured and restarted from the frontend, by a logged-in operator whose permissions are actually evaluated. **No unauthenticated control API, not even temporarily.** |
| **Baseline** | `contracts-backend-consumption` @ `85170b4` — plan 004 (apply persistence and admission) implemented and verified. |
| **Gates preserved** | ~~**G2**~~ ✅ **closed 2026-09-16, amendment ratified** — see the banner · **G3** (`BE-D4` amendment) · `BE-D5` ratification (open items 32/33 **and 35**) · the new open item 35. |
| **Prepared** | 2026-09-11 |

⚠️ **This is a sequence, not an approved plan.** `superpowers:writing-plans` produces the plan; this decides the order
and names what blocks what.

---

## 1. The scoping decision that takes three gates off the critical path

**Scope the demo to the REST path only. Do not put the live mesh console in it.**

`BE-D1` and `BE-D14` already make REST the authoritative snapshot and backfill; mesh notify events only *invalidate
queries*. A demo that configures, applies and observes a Cache instance therefore needs no broker subscription at all.
Dropping it removes three gates from the critical path in one decision:

| Gate | With live console | REST-only demo |
|---|---|---|
| **G3** — `BE-D4` amendment; generated `NotifyEvent`/`LiveStateClient` types unsettled | on the path | **off** |
| **`BE-D5`** ratification — blocked on open items 32, 33 **and 35** ⚠️ **(35 too — an earlier revision of this row omitted it, as [`../design/backend/eda-walking-skeleton.md`](../design/backend/eda-walking-skeleton.md) §6 records)** | on the path | **off** |
| **Open item 35** — environment-level mesh isolation | on the path | **off** (and the one-environment seed makes it unreachable anyway) |

What the demo loses is live-updating charts. What it keeps is every claim that matters: authenticated, authorized,
real configuration written to a real container, a real restart, a real observation.

⛔ **DISCHARGED 2026-09-16.** This section concluded *"that leaves exactly one gate on the critical path: G2."*
G2 is closed and the amendment is RATIFIED and `LOCKED`, so **the REST-only demo path now carries no ratification
gate at all**. The three gates the table takes off the path are still genuinely open (open items 32, 33, 35 and the
`BE-D4` amendment) and still gate the **live-notify half** — see the banner's point 3.

## 2. G2 *was* the critical path — ⛔ closed 2026-09-16; kept as the record of what it gated

⛔ **This whole section is historical.** Its heading read *"G2 is the critical path, and it is not an engineering
item."* G2 is closed and the `D10` amendment is RATIFIED and `LOCKED`
([`../design/D10-G2-APPROVAL-2026-09-15.md`](../design/D10-G2-APPROVAL-2026-09-15.md)). ⛔ **Nothing in §2.1 or
§2.2 is a live prohibition.** Read it to understand what was withheld and why; do not read it as a bar on writing
code today.

### 2.1 Two documents disagree about what G2 gates, and the stricter one controls

⚠️ **This section previously followed the looser of two rules. It was wrong to.**

| Source | What it gates |
|---|---|
| **`CLAUDE.md`** (project instructions, §Backend status) | *"**No auth-bearing backend code exists or may be written before G2.**"* — and, in **`CLAUDE.md`'s own** numbered open-items list (not `DECISIONS.md`'s, where "open item N" is a term of art for a different table): *"every auth-bearing backend **task** waits on it"*. This bars the **code**. |
| **`BE-D15`** §5.4 / [`backend/decisions.md`](../design/backend/decisions.md) | *"no auth-bearing **endpoint** ships … the machinery may exist as a **tested library** only"*; *"building a library is not shipping an endpoint"*. This bars only the **exposure**. |

**The stricter rule controls: `CLAUDE.md`.** Two reasons, and neither is a preference:

1. `BE-D15` is **PROPOSED**. Per `I4`, an unratified decision does not narrow a standing project rule — *"do not
   implement against an unratified decision as if settled"*. `BE-D15` reading G2 as endpoints-only is a proposal
   about the gate's scope, not a ruling on it.
2. `CLAUDE.md` is the project's own instruction file. Where it and a proposal disagree about what may be built, the
   instruction is what governs until governance changes it.

⛔ **DISCHARGED 2026-09-16 — the banner at the top of this file is the current statement.** G2 is closed and the
`D10` amendment is ratified by the **project owner**, who is the final architecture authority; items 24, 38 and 40
are all closed, and no countersign was owed. The historical condition follows.

**This held until one of two things happened:** the architect group ratifies G2 (open item 24), or it grants a
**signed, narrow, written waiver** naming exactly which components are exempt and for how long. A waiver is a
document, not a verbal go-ahead and not an inference from a deadline. ⚠️ Recorded as
[`DECISIONS.md`](../design/DECISIONS.md) **open item 38** — this sequence states the interim ruling; it does not
settle the governance question, and `BE-D15`'s text is not edited (`I3`).

**Active exception - 2026-09-14 only.** The project owner authorized the narrow
[`D10-G2-DEMO-WAIVER-2026-09-14.md`](../design/archive/demo-waivers/D10-G2-DEMO-WAIVER-2026-09-14.md): one local Cache demo, one
Bootstrap Super Admin, the named minimum permissions and endpoint surface, expiring at 23:59 Asia/Kolkata. It
does not ratify G2 or widen the permanent implementation scope.

The same time-boxed demo authorization separately permits the one declared EC2 Cache host to use the
[`SM-D11A-DEMO-WAIVER-2026-09-14.md`](../design/archive/demo-waivers/SM-D11A-DEMO-WAIVER-2026-09-14.md) controls. It does not
ratify `SM-D11a` or authorize another remote target.

### 2.2 What that means for the split

**Pre-G2 — buildable now.** Nothing here creates auth-bearing **backend** code — the qualifier matters, because
`CLAUDE.md`'s bar is on *backend* code, while the frontend intake below unavoidably brings RBAC screens and a
`configureCsrf` hook with it. Importing an existing console is permitted; writing backend authorization is not:

- `RuntimeAdapter`, the Docker Cache adapter, the apply engine (W1).
- **Non-auth persistence only** — plan 004's apply tables and the hierarchy tables. `apply_request.requested_by`
  stores an actor id; recording *who asked* is not an authorization decision and does not make the table
  auth-bearing. No `principal`, no `credential_hash`, no `role`, no `role_binding`, no `scope`.
- Frontend **structural** intake — the import itself, fixtures and mocks (W4, up to but excluding the point where
  it is pointed at a real API).
- Demo `docker compose` and the **non-auth** half of the seed: organization, unit, environment, instance,
  placement, and the Cache container (W5).

⛔ **DISCHARGED 2026-09-16 — every row of the table below is now buildable.** The sentence that introduced it read:
*"**Post-G2, or under a signed waiver — nothing before.** Every item below is auth-bearing code by `CLAUDE.md`'s
reading, and none of it may be written first, *including as a library behind `mvn verify`*."* That bar is gone with
G2. ⛔ **Do not read this table as a prohibition** — it is the inventory of what plan 006 and plan 007 now build:
[`../plans/backend/006-local-identity-and-scoped-rbac.md`](../plans/backend/006-local-identity-and-scoped-rbac.md)
owns rows 1–8, [`007-…`](../plans/backend/007-production-api-and-event-driven-walking-skeleton.md) owns the
endpoint row. **Formerly post-G2:**

| | |
|---|---|
| principal / credential storage | `sm_control.principal`, `credential_hash`, `must_rotate` |
| local login and sessions | the session record, idle lock, rotation enforcement |
| the permission registry | the code-owned catalog of the **38** verbs ⚠️ **(38, not 35 — ruling OR-9 added rows 36–38; the ratified amendment §1.1 says any document still saying 35 is stale)** |
| roles and role bindings | `role`, `role_binding`, the seeded built-ins |
| **the canonical `scope` table** | `SM-D33`/§6.5 — it exists only to validate authorization scope |
| the authorization service | `authorizationService.require` and `SM-D33`'s prefix evaluation |
| CSRF | the token or double-submit on cookie-authenticated mutations |
| the auth audit log | `sm_control.audit_log` and its append-only DCL. ⚠️ Only the **CP half** — `BE-D15` §5.3 splits audit along the plane boundary and the DP half is `apply_request` itself, already built in plan 004 and pre-G2 for the same reason as `requested_by`: it records outcomes and makes no authorization decision. |
| **every HTTP endpoint** | authenticated or not — an unauthenticated one is the bypass §6 forbids |

⛔ **DISCHARGED 2026-09-16 — the whole paragraph, not half of it.** It read: *"**The honest conclusion is
unchanged and is not softened by the sequencing.** A live, authenticated, browser-driven control demo **cannot
ship without G2 ratification or the documented waiver** … Under the stricter rule the pre-G2 buildable set is
*smaller* than this document previously claimed, so the demo is further from ready, not nearer."* **G2 is
ratified**, there is no longer a stricter rule, and there is no pre-G2 set — so neither sentence stands. ⚠️ **What
does still stand is the schedule argument in §4**, which never depended on the gate: W2 and W3 are unwritten code,
and closing a ratification gate does not write them.

✅ **Superseded 2026-09-16 — G2 did not need open item 34.** This paragraph previously said that asking the
architect group to confirm `dev/frontend`'s `D18`/`D19` records was "the single highest-value action available"
because it decided whether the demo was blocked. It no longer decides that: G2 is closed and the `D10` amendment
is RATIFIED and `LOCKED` by the project owner ([`D10-G2-APPROVAL-2026-09-15.md`](../design/D10-G2-APPROVAL-2026-09-15.md)),
independently of `dev/frontend`. ⚠️ **Item 34 is narrowed, not closed** — what survives is the **id-allocation**
half (a second branch minting `D18`–`D20` in a family this repo declares closed at `D17`) and the **competing
`D18` 🔒 catalog**, whose relationship to the ratified 38-verb catalog is still unrecorded. Neither gates the demo;
both gate register intake from that branch. See open items 34 and 37 in
[`DECISIONS.md`](../design/DECISIONS.md).

## 3. The five workstreams

### W1 — Apply engine and the Docker adapter (`BE-D12`) — **no gate**

The largest piece, and entirely unblocked.

1. `RuntimeAdapter` — the six operations, exactly as `BE-D12` §2.1 specifies them.
2. `DockerAdapter` — per §2.3's Docker column: inspect/enumerate for `prepare`, temp→fsync→rename for
   `materializeConfig`, declared-verb container restart (**never an image pull** — `SM-D2`, `I1`) for `startOrUpdate`,
   container state + start epoch + config hash read-back for `status`.
3. The apply step loop over plan 004's existing persistence: admission, the intent/attempt journal, health gating by
   polling `status` under a bounded deadline, `stage-everywhere-then-restart` ordering (`SM-D15`).
4. `manual` as the default strategy — under it the core **never** calls `startOrUpdate`; it observes `restart-observed`.
   This is the demo's happy path and the one `BE-D16` makes terminal in `STAGED`.
5. The ArchUnit rule `BE-D12` §2.3 requires: core apply packages must not depend on Docker client types, with a
   planted-violation proof, in the `onlyMeshbridgeTouchesJcsmp` idiom.

### W2 — Local auth, identity and scoped RBAC (`BE-D15` + `SM-D33`) — ✅ **ungated 2026-09-16, and the scope depth is RULED**

⛔ **DISCHARGED 2026-09-16.** This section previously read *"⛔ G2 or signed waiver. Nothing before"* and *"all of
W2 is gated, including the library"* — correct while G2 was open under §2.1's stricter rule, and **wrong now**: G2
is closed, so neither `CLAUDE.md`'s auth-bearing-*code* bar nor `BE-D15`'s endpoints-only reading gates anything
here. W2's successor plan is [`../plans/backend/006-local-identity-and-scoped-rbac.md`](../plans/backend/006-local-identity-and-scoped-rbac.md),
and every task in it is executable.

✅ **The narrowing that used to sit here is CLOSED by ruling [OR-16](../design/DECISIONS.md).** The scope table is
`D11-AMENDMENT-PROPOSAL.md` §6.5's, **plus `solution_id`** — five grantable levels,
`(organization_id, organization_unit_id, environment_id, solution_id, instance_id)` — so a solution-scoped binding
**is** expressible. ⚠️ **Earlier revisions of this paragraph called it an open gap, then an open OR-2-vs-§3.2
conflict; both are superseded.**

⛔ **Placement gets no column, and that is the ruling rather than an omission.** A placement is an operational
child identity of its Instance; a placement-targeted operation authorizes through **the owning Instance scope**
plus any separately required **Target** permission. So `instance.stop`'s approved *"instance or placement"*
targeting **is fully authorizable**, and ⛔ **do not add `placement_id` to `scope` or `role_binding`.**

1. Identity substrate (`BE-D15` §5.3): `principal` with `source ∈ {local, idp}`, salted adaptive `credential_hash`,
   deactivate-never-delete, `must_rotate`.
2. The permission catalog as a code-owned registry; built-in roles seeded as data with `built_in = true` and the
   `sm_control` DCL that makes "immutable through the API" an artifact rather than a convention.
3. `role_binding(principal_id, role_id, scope_id)` referencing the canonical scope record — **not** an id array
   (§6.5 of the amendment; item 7 below).
4. `authorizationService.require(...)` implementing **`SM-D33`'s prefix algorithm** — not whole-install evaluation.
   Tests must include: a unit-scoped grant reaching a child environment; an environment-scoped grant **not** reaching
   a sibling; a cross-organization resource refused before bindings are consulted; and 404-vs-403 discrimination.
5. The ArchUnit rule `BE-D15` §5.2 requires: nothing outside the authorization package references role names or
   binding tables.
6. Audit split along the plane boundary — `sm_control.audit_log` append-only by DCL; DP apply-family verbs audited by
   `apply_request` itself.
7. The canonical `sm_control.scope` table, the matching composite FKs on the hierarchy tables, the
   migration-seeded install scope, and `scopes.canonicalize`, with the eighteen prove-it-fires obligations in
   [`D11-AMENDMENT-PROPOSAL.md`](../design/D11-AMENDMENT-PROPOSAL.md) §6.5. ⛔ **"Gated with the rest" is
   DISCHARGED 2026-09-16** — the table is auth-bearing by purpose, and auth-bearing work is no longer gated.
   ✅ **It ships WITH `solution_id`** (ruling **OR-16**), plus the `sm_control.solution` table and re-keyed
   `instance` that make a solution-scoped binding actually resolve — ⚠️ **an earlier revision of this line said
   solution scopes were inexpressible in v1; OR-16 reversed that.**

⚠️ Item 4 is where a **mutation test earns its keep**: delete the prefix check so every grant reaches everything, and
confirm the sibling-environment test turns red. A scope check that no test can detect the removal of is not a scope
check — and this is a fresh instance of exactly the failure pattern the plan 004 repair wave hit four times.

### W3 — The read/control API — ✅ **ungated 2026-09-16**

⛔ **DISCHARGED 2026-09-16** — this heading read **G2**. Its successor plan is
[`../plans/backend/007-production-api-and-event-driven-walking-skeleton.md`](../plans/backend/007-production-api-and-event-driven-walking-skeleton.md),
whose own Acceptance section states which of its tasks are independently executable and which only need plan 006
to land first (**sequencing, not a gate**).

The thin authenticated surface, and only what the demo drives:

`POST /cp/auth/login`, `GET /cp/auth/identity`, `POST /cp/auth/logout` · `GET /cp/hierarchy/*` ·
`GET /dp/solutions/{id}/instances` · `GET /dp/instances/{id}/designer` · `POST /dp/instances/{id}/config` ·
`POST /dp/instances/{id}/apply` · `GET /dp/instances/{id}/health` · `GET /dp/instances/{id}/config-ladder`.

⚠️ **There is deliberately no principal, role-binding or provisioning endpoint here.** Adding
`POST /cp/admin/users` and `POST /cp/admin/assignments` would widen the demo by the whole Admin surface to create
one account. Provisioning `demo-operator` is **W5's authenticated setup command**, which calls the same services
under the same `authorizationService.require` checks — see
[`D11-AMENDMENT-PROPOSAL.md`](../design/D11-AMENDMENT-PROPOSAL.md) §8. The as-built console declares both
endpoints, and they are the natural home for this once the Admin screens are in scope.

Shapes follow the as-built `console-rest-surface.md` **with the `SM-D34` vocabulary applied** (§5.2 of the amendment),
so the frontend changes once rather than twice. Every route calls `authorizationService.require`; there is no second
path and no bypass flag. **CSRF is not optional** — these are cookie-authenticated mutations, `FE-D14`/`FE-D24`
require a token or double-submit, and the as-built client's `configureCsrf` hook is currently wired to nothing.

### W4 — Frontend integration — **one reviewed contract change, then import; then wiring** (⛔ the *"wiring at G2"* qualifier is DISCHARGED 2026-09-16)

Per [`dev-frontend-intake.md`](dev-frontend-intake.md) §6. ⚠️ The import is **not** dependency-free: the frontend
compiles `contracts/solution-package.schema.json` in and its own tests assert the `packageVersion` **array** form the
baseline does not yet have, so the schema change lands first, through the single-writer contract process. Then import
`frontend/**`, then point it at the real API instead of MSW, then apply the vocabulary rename as one coordinated
change.

### W5 — Local demo environment and evidence — ⛔ **the §2.1 split is DISCHARGED 2026-09-16**

The **Pre-G2 / Post-G2** split below is kept only as a record of what §2.1 required while G2 was open. With G2
closed, **both halves are buildable**; the ordering between them is now ordinary sequencing (the auth half needs
the identity substrate to exist), not a gate.

**Formerly pre-G2:**

1. `docker compose`: PostgreSQL + the SM backend + a real **Cache** container with a bind-mounted external config
   directory SM owns (`SM-D3` `additional-location`).
2. The **non-auth** half of the `SM-D32` §8 seed: one Organization, one Organization Unit, one Environment, one
   Cache instance, one placement. These are hierarchy and inventory rows; none is auth-bearing.

**Formerly post-G2 or signed waiver — now unblocked, and merely later in the sequence:**

3. The **auth** half of the seed, in two parts with different authorities
   ([`D11-AMENDMENT-PROPOSAL.md`](../design/D11-AMENDMENT-PROPOSAL.md) §6.5, §8):
   - **Migration:** exactly one scope row — `scope_install` (`kind='install'`) — plus `bootstrap-admin` bound to
     it with `must_rotate`. ⚠️ The migration creates **no other scope row**; that is obligation (q), asserted after
     migration rather than trusted. `scopes.canonicalize` refuses `kind='install'` because it has no organization
     to resolve within, and the bootstrap admin is the install's first principal, so no session exists when its
     binding is written.
   - **Runtime, via the authenticated setup command `sm-admin provision-demo-operator`** (shipped with the demo
     compose; part of this workstream, **not** W3): the bootstrap admin signs in and rotates its credential — the
     command **refuses to run while `must_rotate` is set** — then the command creates `demo-operator` and grants
     `Operator` at Environment scope. `scope_demo_env` is produced by `scopes.canonicalize` from that real
     session, so it is **not** migration-seeded: unlike the install scope it has an organization and a session
     available, and a seed that bypassed `canonicalize` would prove nothing about `canonicalize`.

     It is **not a bypass**: it authenticates, it is authorized through `authorizationService.require` holding
     `user.create` and `role.assign`, it is refused without them, and both actions write `sm_control.audit_log`
     attributed to `bootstrap-admin` (`BE-D15` §5.3). It adds no HTTP surface and no second code path.

   Bindings reference a `scope_id`; none carries a path or a name. ⛔ **"All of this is gated by §2.2" is DISCHARGED 2026-09-16** — §2.2 is historical, and none of it is gated.
4. **Drive the demo as `demo-operator`, not as the admin.** The environment-scoped grant is the only seed element
   that exercises `SM-D33`'s prefix rule; demonstrating from the root account proves nothing about the model being
   shown.

## 4. Sequence and the critical path

⛔ **The 2026-09-11 diagram below is superseded 2026-09-16.** It placed a `G2 (item 24)` gate between W1/W4/W5 and
W2/W3, with item 34's `D18`/`D19` confirmation as the alternate key. **Both are gone:** item 24 is CLOSED and item
34 no longer decides G2. The REST-only path is now one ordinary dependency chain with **no ratification gate**:

```
now ──► W1  apply engine + Docker adapter               (no gate)  ─┐
    ──► W4  schema change, then import frontend/        (no gate)  ─┤
    ──► W5  compose + Cache + NON-AUTH seed rows        (no gate)  ─┘
                                                                    │
        W2 authz + identity + scope table ──► W3 endpoints ──► W4 wiring ──► W5 auth seed ──► demo
              (no gate; plan 006)                  (no gate; plan 007)
```

⚠️ **What is left is sequencing and one narrowing, not a gate.** W3's session-backed endpoints need W2's identity
substrate *to exist* — a dependency, not a block, because W2 is itself unblocked. ✅ **W2's scope table now carries
`solution_id`** (ruling **OR-16**), so solution-scoped bindings **are** expressible — ⚠️ **an earlier revision said
they were not.** ⛔ **Placement still gets no binding level**, and `instance.stop` authorizes through the owning
Instance at both its approved targets. ⛔ **The live-notify half stays hard-gated** by open items 32/33/35 and the `BE-D4`
amendment, which is exactly why §1 scopes the demo to REST.

⛔ **DISCHARGED 2026-09-16** — this paragraph read *"W2 is now behind the gate, not beside it,"* and concluded the
tail behind G2 was longer than the document previously showed. With G2 closed there is no gate for W2 to be behind.

⚠️ **An honest word about Monday.** Today is Friday 2026-09-11; the target is Monday 2026-09-14. None of W1, W2, W3
or W5 exists yet — a Docker runtime adapter, an apply step loop, an authorization implementation, an HTTP surface and
a demo environment. That is several plans' work, and no amount of sequencing compresses it into one weekend. The
schedule is the thing to renegotiate, not the gate and not the authentication requirement.

If a Monday date is fixed, the deliverable that fits is a **narrated walkthrough of W1 under `mvn verify`** — a real
apply against a real Cache container, shown as passing tests rather than as a UI — plus the imported console running
on its existing fixtures. ⛔ **The paragraph's last two sentences are DISCHARGED 2026-09-16.** They read: *"**Not**
scope evaluation: an earlier version of this paragraph offered 'W1 + W2', and §2.1 places every part of W2 behind
G2. Under the stricter rule there is *less* that can be honestly shown on Monday, not more."* G2 no longer excludes
scope evaluation from a demo, and there is no longer a stricter rule to invoke. ⚠️ **What still excludes it from a *Monday* demo is the
estimate above, not a gate** — W2 is unwritten code, and closing a ratification gate does not shorten it.

## 5. Definition of done, and the evidence for each claim

| Claim | Evidence that settles it |
|---|---|
| The apply path is real | A Cache container's config file changed on disk and the container restarted, shown by `status`'s hash read-back and start-epoch change — not by a log line |
| Authorization is real | `demo-operator` succeeds in `dev` and is refused on a sibling environment, **and** the mutation in W2 item 4 turns that second test red |
| Isolation is intact | Every DP query still `tenant_id = :t`, bound from the session (`SM-D34`); the plan 004 tenant-isolation suite still green, unmodified |
| No bypass exists | The ArchUnit rules from W1 and W2, each with a planted violation; grep alone does not settle it |
| Nothing regressed | `mvn -f backend/pom.xml clean verify` green; `./contracts/run-checks.sh --strict` green; DDL-replay NO DRIFT |

## 6. Explicitly out of scope

Plan 005 implementation (this sequences it, does not start it) · systemd and Kubernetes adapters · the live mesh
console · multi-organization, multi-unit or multi-environment installs · a hierarchy editor · re-parenting · SSO/SCIM
· any unauthenticated control surface, temporary or otherwise.
