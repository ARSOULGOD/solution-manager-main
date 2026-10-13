> # ⚠️ RATIFIED / SUPERSEDED — 2026-09-04
>
> **This paper is historical material. It is preserved, not maintained, and must not be deleted.**
>
> The architect group answered it on 2026-09-04. The answers live in the decision register, not here:
>
> | This paper asked | Answered by |
> |---|---|
> | §2 — approve the 28-verb catalog | **`D18`** 🔒 — approved, and **expanded by five Groups verbs** (`group.view`, `group.create`, `group.edit`, `group.deactivate`, `group.member.manage`). `group.member.view` and `group.role.assign` were considered and declined. |
> | §3a — choose a bootstrap option, and rule on the choice embedded in the mockup | **`D19`** 🔒 — **option A** (install-time seed) chosen, and the mockup's embedded choice is now ratified in the register rather than in a reference file. |
> | §3b — accept a minimal local identity substrate | **Partly.** `D19` settles the v1 model (one organization per user, roles-not-user-types, deactivate-never-delete, a `source: 'local' \| 'idp'` discriminator from day one). Credential policy — length, rotation, lockout — remains **open**. |
> | §3c — decide the audit log | **Still open.** Recorded as a backend dependency: append-only server-side audit carrying actor, action, target, tenant, organization/scope, timestamp, and before/after values with secrets redacted, with the correlation ID as a **join key, not the event id**. No client-side audit is written. |
> | §4 — rule on introspection scope | **Still open.** `D18` explicitly does not decide it. |
> | Addendum (backend) — the three `mesh.*` verbs | **Not answered.** `D18`'s 35 operations contain no `mesh.*` verb; the addendum below arrived from the backend sub-spec after this paper was tabled and was **not** part of what the group ratified. It remains open, and `BE-D5` ratification still waits on it. |
>
> **Read the register, not this file, for what is binding:** [`DECISIONS.md`](DECISIONS.md) — `D10` 🔒
> (the model, unchanged), `D18` 🔒 (the catalog), `D19` 🔒 (v1 local auth and the Bootstrap Super Admin).
>
> **Two notes for a reader arriving from a search.** This paper was authored on a branch whose open items
> were numbered differently — its references to "open item 23 / 24" are that branch's numbering and do not
> resolve against the current list. And its §1 census of the catalog ("2 grantable, 6 proposed, 22 absent")
> describes the state **before** `FE-D45` widened the shipped catalog and before `D18` ratified it. Both are
> left exactly as written, because editing an approved paper to match a later world destroys the record of
> what the group was actually asked.
>
> And its file paths point at `frontend/reference/sm-v2/…`, the layout the reference folder had on the
> branch this paper was written on. That copy was removed when the branches merged (it duplicated
> `frontend/reference/` byte-for-byte); **drop the `sm-v2/` segment** to resolve any path below.
>
> *Nothing below this line has been edited.*

---
# Proposal — one amendment to LOCKED `D10`, to make `REQ-006` buildable

| | |
|---|---|
| **Status** | ✅ **RATIFIED 2026-09-16.** This paper's catalog and evaluation semantics are **`LOCKED`** through [`D10-G2-APPROVAL-2026-09-15.md`](D10-G2-APPROVAL-2026-09-15.md), which **is** the amendment — and which extends the catalog to **38** verbs and adds three built-in roles. ⛔ **§5's *"only the architect group can"* is superseded: the project owner is the final architecture authority**, and open item **40** is closed. **This paper's body is preserved unedited** (`I3`): read it for the evidence and the options, and read the authorization for what may be built, what was explicitly **withheld** (strategy-assertion verbs, `group.*` verbs, a `D*` id) — ⚠️ **`stop` is NOT withheld: ruling OR-9 approved `instance.stop` as catalog row 36 on 2026-09-16, and an earlier revision of this row still listed it here**; and read it for what stays open. Was: *PROPOSAL, nothing here is decided, routed to the architect group.* |
| **Amends** | `D10` 🔒 (RBAC model — deny-by-default, policy-as-code catalog). ✅ **Amended and ratified 2026-09-16**; ⛔ where old `D10` conflicts with the amendment, **the amendment controls**. §5 below is preserved unedited (`I3`) and is **superseded** on the authority question |
| **Resolves** | [`DECISIONS.md`](DECISIONS.md) open item **24**; absorbs the scope of item **23** |
| **Requirement** | [`REQ-006`](../requirements/REQ-006-rbac.md) — `ACCEPTED`, **must**, **v1** |
| **Prepared** | 2026-08-19 |

> ✅ **RATIFIED 2026-09-16 — no longer a live question.** Everything below was written while it was. See [`D10-G2-APPROVAL-2026-09-15.md`](D10-G2-APPROVAL-2026-09-15.md) for the ratified **38-member** catalog, the three built-in roles, and what it still withholds (strategy-assertion and `group.*` verbs — ⛔ **not** `stop`, which was approved 2026-09-16). ⛔ **§5's statement that only the architect group may amend `D10` is superseded** — the project owner is the final architecture authority, and open item 40 is closed. The paragraph that follows described this paper's status *before* ratification and is kept as written.

⚠️ **This document deliberately does not decide anything.** `D10` is `LOCKED`; per `I4` a proposal may not resolve a locked decision, and per `L8` status changes come from ratification, not from a proposal existing. What follows is the evidence, one costed option set per question, and a recommendation — so the group has something to approve or reject rather than a problem statement.

---

## 1. Why this is urgent rather than merely open

`REQ-006`'s acceptance criterion is **"no endpoint is reachable without it."** `D10` is deny-by-default with a policy-as-code catalog. Both are settled.

The catalog contains **two grantable verbs**.

Measured from the approved mockup's own permission catalog (`frontend/reference/sm-v2/app/admin_data.jsx:8-19`, which the mockup renders on its Admin screen): **30 permissions — 2 grantable, 6 proposed, 22 absent.**

The consequence is not "some features are ungated". It is that **every core v1 screen is unauthorizable**:

| Screen / action | Verb it needs | Status |
|---|---|---|
| See a solution at all | `solution.view` | ✗ absent |
| Load a Solution Package | `package.load` | ✗ absent |
| See / create an instance | `instance.view`, `instance.create` | ✗ absent |
| Designer — view config | `config.view` | ✗ absent |
| Designer — edit, apply | `config.edit`, `config.apply` | ✗ absent |
| Restart an instance | `instance.restart` | ✗ absent |
| Roll back, override a warning | `config.rollback`, `config.overrideWarning` | ✗ absent |
| Governance — health | `health.view` | ✗ absent |
| Governance — read logs | `logs.read` | ✗ absent |
| Governance — set log level | `logs.setLevel` | ✗ absent |
| Type a quarantined metric | `metric.declare` | ✗ absent |
| Targets — view, create | `target.view`, `target.create` | ✗ absent |
| Roles and users (the Admin screens) | `role.*`, `user.*` (6 verbs) | ✗ absent |
| Metrics + dashboards | 6 verbs | ◇ proposed (`DM-D18`) |
| **Bind an instance to a target** | `instance.bindTarget` | ✅ grantable |
| **Use a target** | `target.use` | ✅ grantable |

So v1 either ships a console whose every screen is deny-by-default unreachable, or it ships endpoints outside the check — which fails `REQ-006`'s acceptance criterion directly.

⚠️ One consequence is sharper than the rest. `UI-D10` describes the `loggers` POST as **"privileged and audited"**. It is privileged by `logs.setLevel`, which does not exist. Today that endpoint is *either* unreachable *or* unguarded, and no third option exists.

**Previous recording understated this.** Open item 23 and `traceability.md` recorded only the six `DM-D18` verbs and framed the damage as *"dashboards and export are unbuildable."* Both are corrected as of 2026-08-19.

## 2. Proposed: add 28 verbs, in the groups the mockup already uses

Not a new taxonomy — the mockup's own grouping, which operators have reviewed. `instance.bindTarget` and `target.use` are unchanged.

**Platform** — `solution.view` · `package.load`
**Inventory** — `instance.view` · `instance.create` · *(`instance.bindTarget` ✅ exists)*
**Configuration** — `config.view` · `config.edit` · `config.apply` · `config.rollback` · `config.overrideWarning`
**Lifecycle** — `instance.restart`
**Observability** — `health.view` · `logs.read` · `logs.setLevel`
**Metrics** — `metrics.query` · `metrics.export` · `metrics.forceRaw` · `metric.declare`
**Dashboards** — `dashboard.view` · `dashboard.edit` · `dashboard.reset`
**Targets** — `target.view` · `target.create` · *(`target.use` ✅ exists)*
**Administration** — `role.view` · `role.edit` · `role.assign` · `user.view` · `user.create` · `user.deactivate`

Three properties worth the group's explicit attention:

1. **`config.apply` and `instance.restart` are separable, and the mockup separates them.** One of its built-in roles is *"Ledger Operator (no restart)"* — an operator who may stage configuration but not cause an outage. If the group prefers a single `config.apply` that implies restart, that role disappears.
2. **`config.overrideWarning` gates `UI-D16`'s tier-2 semantic override.** Without it, either anyone who can edit may override a semantic warning, or nobody can — and `UI-D16` requires the override be *recorded*, which implies an identifiable actor.
3. **`metric.declare` is what makes `UI-D5`'s quarantine escapable.** Undeclared metrics arrive, are quarantined, and stay quarantined unless someone may type them.

## Addendum 1 (backend, 2026-08-27): mesh access verbs — BE-D5/BE-D6

Three verbs the event-mesh console path requires; deny-by-default applies as everywhere. They sit
after §2's catalog rather than inside it because they arrive from a later sub-spec
([`BE-D5`/`BE-D6`](backend/decisions.md), drafted 2026-08-27) — the ask is that the group rule on
them **together with** the verb catalog above (28 verbs; with this addendum and Addendum 2 below, the
full proposed catalog is 33 grantable verbs / 35 members incl. `D10`'s two existing ones), as one
amendment.
Gate G1's broker-authorization spike is complete — [`spike-authz-result.md`](backend/spike-authz-result.md)
— so these verbs name a mechanism whose core is measured rather than merely proposed; **two ACL-shape
gaps remain open with this same group** (multi-tenant composition of the substitution profile, and the
notify-tier exception shape — see the result doc), and both block `BE-D5` ratification. Those are
ACL-shape questions, not verb-catalog ones: they change where the broker draws the line, not which
verbs the console needs.

| Verb | Grants | High-consequence? |
|---|---|---|
| `mesh.ticket.mint` | obtain a mesh ticket (notify-scope) for one's own session | no |
| `mesh.stream.grant` | receive a chart-tail stream grant with a passing DM-D1 query | no |
| `mesh.stream.revoke` | revoke another session's active stream grant | yes — operator-on-operator action |

§3's bootstrap, identity and audit questions apply unchanged; ticket minting is audited like any
privileged action once the audit log exists.

**Scope note on `mesh.stream.revoke` (added 2026-09-04; restated 2026-09-07 for the session-scoped
branch):** revoking a **stream grant** is DP-effective — the tail publisher drops that series from
the session's branch at its next publish decision, effectively immediately. What is TTL-bounded
(≤ ~15 min) is the **session's mesh access as a whole**: revoking the session stops renewal and its
current ticket survives at most to `exp`. The verb row should ratify with both bounds stated, so the
granted power and the delivered effect read the same
([`backend/eda-walking-skeleton.md`](backend/eda-walking-skeleton.md) §4.1/§4.5).

## Addendum 2 (backend, 2026-09-04): access-check introspection verbs — the §4 question, made concrete

§4 below asks who may call the effective-permissions introspection and notes `role.view` is probably
too coarse. The backend walking-skeleton design ([`backend/eda-walking-skeleton.md`](backend/eda-walking-skeleton.md)
§5.2) proposes the concrete pair, following this paper's own mesh-addendum precedent so the group
rules on a paper that contains what the code would enforce:

| Verb | Grants | High-consequence? |
|---|---|---|
| `authz.introspect.self` | see one's **own** effective permissions | no |
| `authz.introspect.any` | see **any** principal's effective permissions | yes — a permission-enumeration surface (§4) |

With this addendum the full proposed catalog is **35 members — 33 grantable verbs plus this pair**:
the two `D10` already grants (`instance.bindTarget`, `target.use` — §2 leaves them unchanged), the
28 of §2, Addendum 1's three mesh verbs, and `authz.introspect.self`/`.any`; the pair rides the same
single amendment decision. The backend
design also asks the group to rule, with this same amendment, on its flagged **v1 whole-install
scope evaluation** deviation ([`backend/eda-walking-skeleton.md`](backend/eda-walking-skeleton.md)
§5.1) — `D10`'s model is scoped assignments and `REQ-006`'s acceptance says so in terms — and on a
question an independent review raised (2026-09-07): `architecture.md` §16 names
**high-consequence strategy-assertion verbs** (*"assert `restart-all`, `rolling-no-validation`"*)
and this catalog has none, so the restart-strategy picker is not distinguishably authorizable; the
backend design routes it here rather than adding verbs unilaterally
([`backend/eda-walking-skeleton.md`](backend/eda-walking-skeleton.md) §8 row 12).

## 3. Three questions the verb list does not answer

### 3a. First-admin bootstrap

**The mockup calls this "the single largest hole"** (`admin.jsx:105`). Deny-by-default plus an empty assignment table means nobody can grant anything, including the first grant. `D13` 🔒 (BYO IdP) is deferred with *"v1 uses local auth"* as its entire v1 specification.

| Option | Shape | Cost |
|---|---|---|
| **A — install-time seed** | Packaging writes one super-admin with a credential the operator must change on first sign-in | Simple; the credential exists before anyone authenticates, so it must be single-use and forced-rotate |
| **B — first-run claim** | The first authenticated session on an empty install becomes super-admin | No stored credential; a race if the console is reachable before the operator arrives |
| **C — out-of-band CLI** | An operator with filesystem access on the host mints the first admin | Strongest; needs a CLI that does not exist and couples to `runtime/` packaging |

**Recommendation: A**, because it is the only one that works on all three loci (`dp-remote`, `host-local`, `observe-only`) without new tooling — but **B is materially safer** in an environment where the console is not internet-reachable before setup. This is a security posture call, not an engineering one.

⚠️ **`admin.jsx:87` already records a choice** — *"admin-set temporary credential — the chosen option"*. **A decision made inside a mockup is not a decision.** It appears in no register. Either ratify it or overrule it.

### 3b. The local identity substrate

`D13` defers Keycloak. Nothing specifies what v1 has instead. Undesigned: user CRUD, credential policy (length, rotation, lockout), session issuance, and **deactivate-never-delete retention** (`admin.jsx:137` — an assignment history must survive the user, or the audit log develops holes).

**Recommendation:** specify the smallest local store that `D13`'s eventual group→role mapping can replace without a data migration — i.e. principals carry a `source` discriminator (`local` | `idp`) from day one. The mockup already renders that distinction (`admin_iam.jsx:11`'s IdP-group chip).

### 3c. The audit log

`deferred.jsx:53` calls its shape **"unspecified"**, while Governance copy already promises *"recorded in the audit log"* (`governance.jsx:74`) and `UI-D16` requires a recorded override. **The console is currently promising an artifact that has no design.** Either design it or change the copy — `FE-D25` makes that copy contractual.

## 4. One question this proposal raises and does not answer

**Who may call the access-check / effective-permissions introspection?** The Admin screens show an operator's effective permissions, which is a legitimate and useful feature — and also a **permission-enumeration surface**. The repo's own "403 information-leak" question applies. `role.view` is probably too coarse: seeing *your own* effective permissions and seeing *anyone's* are different privileges.

## 5. What this proposal explicitly does NOT do

- It does not amend `D10`. Only the architect group can.
- It does not choose the scope model (`SM-D23`'s scope tree is untouched).
- It does not decide whether the six `DM-D18` verbs ratify with this set or separately.
- It does not resolve `D13`'s deferral — only asks what stands in until then.

## 6. What is needed

1. **Approve, amend or reject the 28-verb catalog** as one `D10` amendment.
2. **Choose a bootstrap option** (3a) — and rule on the choice already embedded in the mockup.
3. **Accept a minimal local identity substrate** (3b), or defer `REQ-006` out of v1 explicitly.
4. **Decide the audit log**: design it, or strike the copy that promises it.
5. **Rule on introspection scope** (§4).

Until 1 and 2 are answered, any screen built is a screen whose actions cannot be authorized. `FE-D22` makes RBAC a *second overlay* after `project()`, so component work is not wasted by waiting — but shipping is.
