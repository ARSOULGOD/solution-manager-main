# Proposal — the v1 scope model: `Organization → Organization Unit → Environment → Solution Instance`

| | |
|---|---|
| **Status** | **PROPOSAL — ⚠️ PARTLY SUPERSEDED by owner rulings, 2026-09-16.** The body is retained unedited (`I3`), and two sections now carry supersession notes: **§3.2** (`scopePathOf` — rulings [OR-14](DECISIONS.md#owner-architecture-rulings--2026-09-16) and [OR-16](DECISIONS.md#owner-architecture-rulings--2026-09-16)) and **§6.5** (the scope schema gains `solution_id` and a `solution` hierarchy table — OR-16). ⛔ **Where this paper and a ruling disagree, the ruling controls.** ⚠️ **"Routed to the architect group" is also stale** — ruling **OR-1** makes the **project owner** the final architecture authority. Original status: *PROPOSAL. Nothing here is decided. Routed to the architect group.* |
| **Amends** | `D11` 🔒 (ownership tree) — its **hierarchy clause only**. `D12` 🔒 is confirmed, not amended. |
| **Introduces** | `SM-D32` (the model) · `SM-D33` (scoped authorization) · `SM-D34` (vocabulary and storage posture) — all **PROPOSED**, bodies in [`DECISIONS.md`](DECISIONS.md) §6 |
| **Companion** | [`D10-AMENDMENT-PROPOSAL.md`](D10-AMENDMENT-PROPOSAL.md) — the verb catalog. This paper supplies the **scope** those verbs are granted *at*; that paper's §5 explicitly declines to choose a scope model. The two ratify together or this one ratifies first. |
| **Prepared** | 2026-09-11 |

⚠️ **This document decides nothing.** `D11` is `LOCKED`; per `I4` a proposal may not resolve a locked decision, and
per `L8` a status changes by ratification, never by a proposal existing. What follows is the model, the algorithm, the
wire shape, the mapping onto what plan 004 already built, and the gates — so the group has something to approve or
reject.

---

## 1. What is being changed, and what is not

`D11` 🔒 states the ownership tree as:

> `Tenant → Organization → (Sub-org) → Environment → Solution Instance`

This proposes, for v1:

```
Organization                     -- the isolation root. RBAC root. One per install in v1.
  └── Organization Unit          -- department / business unit / team
        └── Environment          -- dev / stage / prod
              └── Solution Instance
                    └── Placement        -- the instance↔Target binding (SM-D23); replicas observed beneath it
```

**The level mapping, stated explicitly.** This is a collapse and a rename, not a deletion — and saying which level
becomes which is what makes the change checkable against `D11` and against the other branch's `D20`:

| `D11` 🔒 level | Becomes | Note |
|---|---|---|
| `Tenant` | **`Organization`** | The isolation root keeps its semantics and takes the name. It was always exactly one node per install and could never be selected or sent (`I5`, `HC2`), so as a *navigational* level it discriminated nothing while reading as though it did. |
| `Organization` | **`Organization Unit`** | The level operators actually model — a department, business unit or team. |
| `(Sub-org)` — optional | **removed** | Its job is done by `Organization Unit`; the optional fourth level bought nesting nobody asked for. |
| `Environment` | `Environment` | unchanged |
| `Solution Instance` | `Solution Instance` | unchanged |

So `D11`'s five levels become four ⚠️ **(superseded by OR-16: the grant tree is FIVE levels — `Organization → Sub-organization → Environment → Solution → Instance` — and this sentence's arithmetic predates it)**, and every one of them is accounted for.

**Where this leaves `D12` 🔒** — *"two org levels for v1 (parent pointer retained for later nesting)"*:

| Clause | Status |
|---|---|
| *"two org levels for v1"* | **Held.** Organization + Organization Unit is two. |
| *"parent pointer retained for later nesting"* | **Held, by `§6.4` adding a nullable `parent_unit_id` to `organization_unit` that v1 never populates.** ⚠️ An earlier draft asserted this clause was satisfied while proposing no column for it — a guarantee with no enforcing artifact (`L19`). The column is now in the schema delta, and the fixed-depth scope grammar in §2 is what keeps v1 from using it. |

**What is explicitly NOT changed.** `Tenant` as the **isolation boundary** is untouched. `HC2` (server-side tenant
isolation) stands word for word and is read as *organization* isolation. `I5` stands. `SM-D23`'s decoupling of
**ownership** from **placement** stands — an instance is *owned by* a tree node and *placed on* a Target, and
re-targeting is still a rebind that leaves ownership, RBAC and config history intact.

⚠️ **This is a proposal against a LOCKED decision, flagged and not resolved.** Until the group rules, `D11`'s tree is
what binds. Nothing in plan 004 depends on the difference (§6), so the flag costs no work.

### 1.1 Why this is not a renumbering of an existing decision

`origin/dev/frontend` records the same substantive model as **`D20`, marked `LOCKED`** (2026-09-07), alongside `D18`
and `D19`. The `D*` family is declared **closed at `D1`–`D17`** in both `CLAUDE.md` and `DECISIONS.md`, and nothing in
this baseline corroborates an architect-group ruling on any of the three. **This paper does not adopt, ratify or
contradict that record** — it proposes the model through the open `SM-D*` family, which is where a platform-level
proposal belongs and which ratifies at the *head* of the `SM-D*` → `UI-D*` → `DM-D*` → `FE-D*` → `BE-D*` chain. If the
group confirms it did ratify `D18`/`D19`/`D20`, this paper becomes a conforming refinement and `SM-D32`–`SM-D34` are
withdrawn or re-cast; that confirmation is **open item 34**, not an assumption made here.

The substance agrees, including the mapping: `D20` states *"existing data maps former Tenant → Organization and
former Organization → Sub-organization; the former Sub-org level is removed"* — which is row-for-row the table above.
The divergence is the **middle level's name**: `dev/frontend` calls it **Sub-organization**, this paper calls it
**Organization Unit**. That is a vocabulary reconciliation, recorded in
[`../tasks/dev-frontend-intake.md`](../tasks/dev-frontend-intake.md) §4.3.

---

## 2. Canonical identifiers and relationships

Each level is a row with an immutable surrogate `uuid` id and a mutable display `name`. **Authorization and isolation
use ids; names are display only** — a renamed unit must never silently move a grant.

| Level | Canonical id | Parent | Cardinality (v1) |
|---|---|---|---|
| Organization | `organizationId` | — | exactly **one per install** |
| Organization Unit | `organizationUnitId` | `organizationId` | many per organization |
| Environment | `environmentId` | `organizationUnitId` | many per unit |
| Solution Instance | `instanceId` | `environmentId` | many per environment |
| Placement | `placementId` | `instanceId` (+ Target) | `SM-D23`: one active binding per Target; several Targets allowed. **Not a grantable scope** — see §3.2. |

**Fixed depth. Not a recursive tree.** There is no generic node table, no hierarchy editor, and no arbitrary nesting.
A scope is a **typed 0-to-4-element path**, and its length *is* its level. The notation below is how a scope
*reads*; §6.5 is how it is **stored and validated** — as a canonical `sm_control.scope` row whose depth, typed
position, parentage and organization-consistency are database constraints, never an array a caller hands in:

```
[]                                           -- whole install. Rendered; installer/migration-seeded (§6.5); never created by the console.
[orgId]                                      -- Organization
[orgId, unitId]                              -- Organization Unit
[orgId, unitId, envId]                       -- Environment
[orgId, unitId, envId, instanceId]           -- Solution Instance
```

**Notation, fixed here and used throughout.** Every element of a path is an **id**. Where later sections write a
path with readable words — `[org, unit, dev]` — that is shorthand for the ids at those positions, never for the
display names. Names are labels on the hierarchy rows and are mutable; **renaming a unit must move no grant**, which
is the whole reason the path is ids (`SM-D33`).

**Parents are immutable in v1.** An instance's Environment is fixed at declaration; re-parenting an instance, a unit
or an environment is **out of v1 scope**. Re-parenting changes which grants reach a resource and which history is
attributable to whom, so it is a governance operation with its own audit and authorization story, not a field edit.
Re-*targeting* (changing placement) is unaffected and remains available per `SM-D23`.

**Environment is not a tenant.** Environments are authorization and organizational scopes inside one isolation
boundary. They share the organization's database rows, its broker topic space and its `tenant_id` key. Treating each
environment as a separate tenant is explicitly rejected: it would multiply the isolation root, break `apply_scope`'s
per-organization lock semantics, and fragment the series identity `SM-D26` depends on.

---

## 3. Server-side authorization

### 3.1 The algorithm

Deny-by-default, allow-only, additive. There are **no deny rules** — a grant can only widen.

```
authorize(principal, verb, resource) -> allow | deny:

  1. org  := session.organizationId          -- SERVER-DERIVED. Never read from the request. (I5, HC2)
  2. path := scopePathOf(resource)           -- [] or [orgId, unitId?, envId?, instanceId?]   (§3.2)

  3. if path != [] and path[0] != org:       -- cross-organization, refused before any binding is read
        return deny

  4. grants := { b in roleBindings(principal)
                 : (b.scopePath == [] or b.scopePath[0] == org)   -- whole-install, or THIS organization
                 and isPrefix(b.scopePath, path) }                -- DOWNWARD INHERITANCE
                 -- the binding operand is a canonical scope row and the resource operand is a
                 -- derived ancestor tuple, both FK-proven coherent (§6.5). isPrefix compares typed
                 -- columns level by level; it never parses a client-supplied array. Per level the
                 -- comparison is (b.col IS NULL OR b.col = r.col) -- a bare `=` would make the
                 -- install-scoped binding (all columns NULL) match nothing under three-valued
                 -- logic, and IS NOT DISTINCT FROM would make it match only install resources.

  5. verbs  := union { role(b).permissions : b in grants }
  6. return verb in verbs ? allow : deny     -- deny by default
```

⚠️ **A binding's organization is read from its own `scopePath`, never from a separate column.** An earlier draft
carried `b.organizationId` beside `b.scopePath`, which made the whole-install binding unrepresentable: `[]` has no
organization, so the seeded super-admin of §8 matched nothing and the algorithm denied the first admin every verb.
Deriving it from the path removes the contradiction and the column.

**Inheritance is the prefix rule, and nothing else.** `isPrefix(b, p)` is element-wise id equality over `b`'s length;
`isPrefix([], p)` is true for every `p`. A grant at Organization Unit reaches every Environment and Instance beneath
it; a grant at Environment reaches that environment's instances; a grant at Instance reaches one instance. Nothing
propagates upward or sideways.

**One enforcement point.** This is evaluated inside `BE-D15`'s existing
`authorizationService.require(principal, verb, resource)` and nowhere else — no controller-local checks, no
annotations encoding roles, enforced by the ArchUnit rule `BE-D15` §5.2 already specifies. This proposal changes
**what `require` computes**, not where it is called, so no endpoint signature moves.

**`D10`'s two-sided binding rule is preserved intact.** Binding an instance to a Target still requires
`instance.bindTarget` on the **instance's** scope path **and** `target.use` on the **Target's** `ownership_scope`
(`SM-D23`). Both are now paths in this tree, so the rule finally has the anchor `SM-D23` said it lacked.

### 3.2 `scopePathOf` — every resource kind, so none is undefined

⛔ **TWO ROWS OF THIS TABLE ARE SUPERSEDED BY OWNER RULINGS, 2026-09-16. The table text below is retained unedited
(`I3`); implement the rulings.**

| Row | Retained text | Superseded by | Implement |
|---|---|---|---|
| **Solution Package / catalog entry** | `[]` — *"only a `[]` binding reaches `package.load`"* | ⛔ **[OR-14](DECISIONS.md#owner-architecture-rulings--2026-09-16)** — `package.load` is **organization-scoped**, not install-scoped. The ruling names the verb and its scope, so under `I4` it controls and this row is **stale** | `scopePathOf(SOLUTION_PACKAGE) = [orgId]` |
| **Placement** | *"the path of its Instance … a placement is not a grantable level"* | ✅ **[OR-16](DECISIONS.md#owner-architecture-rulings--2026-09-16)** — which **confirms** this row's substance and raises it from proposal to ruling: Placement is an **operational** identity, authorized through the owning Instance scope plus any required Target permission | unchanged — and ⛔ **no `placement_id` in role bindings** |

⛔ **OR-16 ALSO CHANGES THE PATHS IN THIS TABLE, NOT ONLY §6.5'S COLUMNS — and the paths are what `scopePathOf`
returns, so missing them would make a solution-scoped binding unmatchable.** A binding at
`[org, unit, env, solution]` can only ever authorize if the resources it must reach return a path that has
`solutionId` at position 4. Therefore, under OR-16:

| Resource | Retained path | ⛔ **Path to implement** |
|---|---|---|
| **Solution** — ⚠️ **this table has NO row for it; OR-16 requires one** | *(absent)* | **`[orgId, unitId, envId, solutionId]`** |
| **Solution Instance** | `[orgId, unitId, envId, instanceId]` — **four** elements | **`[orgId, unitId, envId, solutionId, instanceId]`** — **five** |
| **Placement** | *"the path of its Instance"* | **unchanged in words, five elements in effect** — it follows its Instance |
| **Audit record** | *"the path of the resource it concerns"* | **unchanged in words**, and five elements wherever the concerned resource is an instance or placement |

⚠️ **`ResourceKind` therefore gains a `SOLUTION` constant** — the constant *set* changes, not just one value.
`Role definition` stays `[]`; `Principal` stays `[orgId]`; `Deployment Target` keeps its own `ownership_scope`
path; neither ruling touches those three.

⛔ **§6.5's schema needs the matching hierarchy table, which it does not define.** Its `scope` FKs reference
`organization`, `organization_unit`, `environment` and `instance` only, so `solution_id` has nothing to point at.
OR-16 requires a **`sm_control.solution`** table keyed
`(organization_id, organization_unit_id, environment_id, solution_id)`, the `scope` FK onto it, and `instance`
re-keyed to carry `solution_id` so the five-level chain is composite all the way down. ⚠️ **Implementing
`solution_id` as a bare column with no table and no FK would satisfy the letter of the ruling and break its
purpose.**

An algorithm is only as total as this table. Anything absent here is unauthorizable, which is how a resource ships
outside the check.

| Resource | Scope path | Why |
|---|---|---|
| Organization | `[orgId]` | |
| Organization Unit | `[orgId, unitId]` | |
| Environment | `[orgId, unitId, envId]` | |
| Solution Instance | `[orgId, unitId, envId, instanceId]` | |
| **Placement** | **the path of its Instance** | A placement is not a grantable level — you authorize against the instance and, per `D10`'s two-sided rule, additionally against the Target. Granting *at* a placement would let a binding survive a rebind, which `SM-D23` makes a new `placement_id`. |
| **Deployment Target** | **its `ownership_scope` path** (`SM-D23`) | Targets are deliberately **not** in the ownership tree — `SM-D23` decouples placement from ownership — so a Target carries its own path into this tree. §6.4 adds the column. |
| **Solution Package / catalog entry** | **`[]`** | The catalog is install-wide, not organization-owned; `B/D4` 🔒 makes publishing Solace-reserved and entitlement per-tenant, neither of which is a tree position. Only a `[]` binding reaches `package.load`. |
| **Role definition** | **`[]`** | Built-in and install-wide in v1 (`BE-D15` §5.1). |
| **Principal (user or group)** | **`[orgId]`** | Principals belong to an organization; administering them is an organization-level act. |
| **Audit record** | **the path of the resource it concerns** | so audit visibility inherits exactly like the thing audited. |

Two consequences worth stating, because both are easy to get backwards:

- **A `[]` binding reaches everything** — `isPrefix([], p)` holds for every `p`, including `[]`. That is what makes
  §8's seeded super-admin work.
- **An organization-scoped binding does *not* reach a `[]` resource** — `isPrefix([orgId], [])` is false. An
  organization administrator cannot administer the install. This is deliberate, and it is why `package.load` and
  `role.edit` sit at `[]`.

### 3.3 What this changes about `BE-D15`

`BE-D15` §5.1 evaluates bindings **whole-install in v1** and flags that as *"a real v1 narrowing of a LOCKED model"* —
it satisfies `D10`'s centralized-deny-by-default clauses and **falls short of its scoped-assignment clause**, which
`REQ-006`'s acceptance sentence states in terms. `SM-D33` **closes that deviation**: scope is evaluated, per the
algorithm above, in v1.

`BE-D15` said the schema was shaped so either ruling is *"a policy change, not a migration"*. This proposal takes it at
its word and asks the group to rule the scoped way. It is a **proposal against a `PROPOSED` decision**, so it carries
the register's standard ordering constraint: `SM-D33` may not be ratified in the same round as `BE-D15`. The ordering
already required by the chain (`SM-D*` before `BE-D*`) satisfies this without extra sequencing.

### 3.4 Existence versus permission — the response codes

Two failures must not be confused, because one of them leaks the shape of another team's estate:

**One rule, covering a direct lookup and an explicitly-named scope filter alike.** These were previously two rules
that disagreed: §3.4 sent an unauthorized *lookup* to 404 while §4.2 sent an unauthorized *filter* to an empty list,
and nothing said which governed `GET …/instances?unitId=<not mine>`. They are now the same rule.

**Step 0 — an explicit filter resolves to exactly one canonical scope, or it is 404.** `unitId` and
`environmentId` are resolved together against the hierarchy (§6.5) into a single `sm_control.scope` row. If they do
not resolve to exactly one — the id does not exist, it belongs to another organization, it is **an id of the wrong
level** (`unitId` given an environment's id), or the pair is **incoherent** (`environmentId=E` is not under
`unitId=U`) — the answer is **404**, the same 404 as "no such scope". One resolution step, one failure mode, no
separate validation path to keep in step.

**Step 1 — visibility.** A scope is **visible** to a principal when it is *prefix-comparable in either direction*
with some scope they hold a verb at:

```
visible(s) := ∃ b in grants : isPrefix(b.scopePath, s)      -- they hold at or above s
                           or isPrefix(s, b.scopePath)      -- s is an ANCESTOR of something they hold
```

| Request | Condition | Response |
|---|---|---|
| Collection, **no** explicit scope | — | **200** with the authorized subset, which may be `[]`. Omitting a filter asserts nothing about any scope. |
| Collection, **explicit** scope | the named scope resolves **and** is `visible` | **200** with the authorized subset within it, which may be `[]` |
| Collection, **explicit** scope | it does not resolve (step 0), **or** resolves but is **not** `visible` | **404** — every case identical |
| Direct lookup (`GET …/{id}`) | the resource does not exist, is in another organization, or its scope is **not** `visible` | **404** — every case identical |
| Direct lookup | the resource is visible but the principal lacks **this** verb | **403**, **without naming the verb** |

⚠️ **The second disjunct of `visible` is not a softening — it is required for correctness, and an earlier draft's
omission of it broke this paper's own demo.** §8 seeds `demo-operator` at `[org, unit, dev]`. Under an
at-or-above-only test that principal holds nothing at `[org, unit]`, so `GET /cp/hierarchy/units/{unitId}/environments`
would 404 **on their own parent unit**, and §5.1's canonical filtered endpoint —
`GET /dp/solutions/{id}/instances?unitId=&environmentId=` — would 404 whenever `unitId` was populated, even with a
fully-held `environmentId` beside it. The console could not render the hierarchy for the one principal
[`../tasks/demo-delivery-sequence.md`](../tasks/demo-delivery-sequence.md) insists the demo is driven as.

**And it discloses nothing.** A principal granted beneath a scope **already knows that scope exists** — it is on the
path of their own grant. Revealing an ancestor they can already infer is not a disclosure; refusing it is merely a
broken breadcrumb trail. Non-disclosure is preserved exactly where it matters: a scope with no prefix relationship
to any grant they hold is indistinguishable from one that does not exist.

**Why naming a scope still differs from omitting one.** Supplying `unitId=X` asserts that `X` is addressable;
answering `200 []` would distinguish "exists, not yours" from "no such id" the moment those diverge. Omitting the
filter asserts nothing, so the authorized subset — empty or not — is the honest answer. **Existence is privileged;
membership of a result set is not.**

**Testable, and that is the point.** §3.4 now specifies **two** sets of 404 conditions — the explicit-filter set and
the direct-lookup set. Within each route the responses must be **byte-identical**: same status, same body, same
headers. Cross-route equality is neither required nor meaningful. Obligations §6.5 **(i₁)** and **(i₂)** cover one
route each, and each asserts *equality across its own set* rather than asserting each response is a 404 — because
separately-correct 404s with different bodies still leak.

⚠️ **The 403 does not name the missing permission.** An earlier draft of this paper said *"403, naming the verb"*,
which is both a security-posture decision this paper has no standing to make and the *opposite* of what
`origin/dev/frontend` already decided: its `FE-D27` is *"A 403 never names the missing permission"*, reasoning that
*"naming the verb tells an unauthorised caller which permission to acquire"*. The conservative behaviour is adopted
here so the two branches do not have to be reconciled later, but the underlying question — the permission-enumeration
surface — is [`D10-AMENDMENT-PROPOSAL.md`](D10-AMENDMENT-PROPOSAL.md) §4's, still open, and the group may overrule
this either way.

`FE-D9` already omits unauthorized actions in the UI rather than disabling them, so a 403 is a defence-in-depth
backstop rather than the primary UX — which is what makes an unhelpful 403 affordable.

---

## 4. Identity, session and active context

### 4.1 Claims

The session is an **httpOnly, `SameSite=strict` cookie the server sets**; the SPA never reads, writes or stores it
(`FE-D8`, `FE-D14`, and the as-built surface). The cookie is an opaque handle; the claims live in the server-side
session record:

| Claim | Source | Notes |
|---|---|---|
| `principalId` | authenticated subject | |
| `organizationId` | **resolved from the principal at login** | Never supplied, never selectable. One organization per principal in v1. |
| `issuedAt`, `lastSeenAt` | server | `FE-D24` idle lock ends the session |
| `mustRotate` | principal row | `BE-D15` §5.3 — blocks every other endpoint until the first-admin credential is changed |

**CSRF is required, and is not optional under a cookie session.** `FE-D14` and `FE-D24` both require a
server-issued token or an equivalent double-submit on cookie-authenticated mutations, and every mutation in §5.1 is
one. The as-built client already exposes the seam — `configureCsrf({ headerName, getToken })` — **wired to nothing**,
so the backend must supply the mechanism and the frontend must wire that hook in the same release. Omitting it does
not fail loudly; it just leaves the console cross-site forgeable.

There is **no `organizationId` in any request body, query string or path as an authority claim.** Where a create
request names a desired owning unit or environment (declaring an instance, creating a user), those are **resource
data the server authorizes and validates against the session** — never proof that the caller may act there. This is
the File Mesh `orgName` audit finding (`HC2`) restated as a rule the wire shape cannot violate.

### 4.2 Active context is navigation, not authority

The operator's currently-selected unit and environment are a **UI filter**. They may live in the URL (`FE-D6`'s
serializable workspace) precisely because they grant nothing: every request is authorized against the session
organization and the resource's own scope path, whatever the URL says. Naming a unit the principal cannot read
returns **404** — never somebody else's rows, and never an empty `200` that would distinguish "exists, not yours"
from "no such id". §3.4 specifies that case once, for both explicit filters and direct lookups.

### 4.3 The mesh ticket

`BE-D14`'s ticket carries a server-derived `tenant` claim. Under this model its **value** is the organization id; its
**name** is a wire-contract question that must not be answered piecemeal — see §7.

---

## 5. REST resource shape and routing

Three same-origin prefixes, as built: `${apiBasePath}/cp`, `/dp`, `/console/v1`.

### 5.1 The organization is not in the path

```
GET  /cp/hierarchy/organization                       -- the caller's organization. No id: it is derived.
GET  /cp/hierarchy/units
GET  /cp/hierarchy/units/{unitId}/environments
GET  /cp/admin/scope-tree                             -- the grantable tree, as built
GET  /dp/solutions/{solutionId}/instances?unitId=&environmentId=   -- filters, each authorized (§6.2)
POST /dp/solutions/{solutionId}/instances             -- body names the owning unit + environment as resource data
```

**No `/organizations/{organizationId}/…` prefix in v1.** There is exactly one organization reachable per session, so
an id in the path would be either redundant or an invitation for a client to supply one — the precise failure `I5`
exists to prevent. When multi-organization administration arrives, the prefix is added *behind an explicit
`organization.administer` verb* and the server still validates the path against the session; that is an additive
change, not a redesign.

### 5.2 Canonical wire vocabulary

| Concept | Canonical key | Display key |
|---|---|---|
| Organization | `organizationId` | `organization` |
| Organization Unit | `organizationUnitId` | `organizationUnit` |
| Environment | `environmentId` | `environment` |
| Instance | `instanceId` | — |
| RBAC scope | `scope: string[]` of **ids** | resolved to names for display |

**`tenant`, `tenantId` and `tenant_id` never appear on this surface.**

⚠️ The as-built frontend surface is **not yet consistent with this**, and its own §3.8 warns that *"a backend must not
normalize them unless the frontend changes in the same release"*. As built it uses `organization` for auth/user/group
payloads, `{organization}`/`{subOrganization}` in cascade paths, and `org`/`subOrg`/`env` on instance rows. Three
spellings for two concepts. **This proposal adopts the table above as canonical and requires the rename to land as one
coordinated frontend+backend release**, not as a server-side normalization. Recorded as open item 36.

⚠️ **The RBAC scope array changes meaning: names → ids.** As built, `scope: string[]` carries *names*
(`[Organization, Sub-organization?, Environment?, Instance?]`). §2 requires ids, so a rename of a unit cannot silently
move a grant. This is a behavioural change to an existing payload, not a spelling change, and it must land in the same
coordinated release.

---

## 6. Mapping onto what plan 004 already built

This is the section that decides whether the model costs anything. **It does not.**

### 6.1 `tenant_id` is the organization's storage identity, unrenamed

`tenant_id uuid NOT NULL` appears in all five `sm_data` apply tables, the `TenantId` record wraps it, and the DDL-replay
baseline, the DCL role topology and the arch tests are all pinned to it. **The value of `tenant_id` is the
`organizationId`.** Nothing is renamed for the demo.

**The posture, stated as a rule rather than a preference:** a physical rename is justified only by a **concrete safety
reason** — specifically, only if two *different* isolation keys would otherwise coexist in one schema. Vocabulary
alone is not such a reason, because the external surface is renamed at the API layer (§5.2) where the requirement
actually lives. Absent that trigger, renaming buys a changed word and spends a migration across five tables, their
DCL, the replay baseline and every golden test.

### 6.2 `I5` is preserved by keeping two layers separate — and is not weakened

This is the load-bearing claim of the whole proposal, so it is stated precisely:

| Layer | Who enforces | Predicate |
|---|---|---|
| **Authorization** — may this principal do this verb here? | CP / gateway, in `authorizationService.require` | the §3.1 prefix algorithm over unit and environment |
| **Isolation** — whose rows may this query ever touch? | DP central data access | `tenant_id = :t`, bound from the **session-derived** organization |

**Unit and environment never enter the DP isolation predicate.** They narrow *within* an organization, above the data
layer. The reason is a failure mode, not tidiness: if unit/environment filtering were pushed down to become part of
the isolation predicate, then a bug in scope resolution would become a **cross-organization** data leak instead of an
over-broad in-organization read. Keeping isolation coarse, unconditional and organization-keyed means the worst
scope-resolution bug shows a user too much of *their own* organization.

Every existing `WHERE tenant_id = :t AND …` predicate in plan 004 therefore stays exactly as written, with exactly the
bind-parameter discipline it already has.

⚠️ **This is a re-reading of `I5`, not plain conformance to it, and is flagged as such.** `I5`'s own text is *"every
query goes through the central data-access module **that applies tenant/authz scope**"* — it binds *authz* scope to
the data-access module, and this split deliberately moves the authz half above it. The claim being made is that the
split is **stronger** than the literal reading, because it makes the isolation predicate unconditional rather than
computed. That is a judgement the architect group should accept or reject explicitly rather than have assumed.

**Where scope *does* reach a DP query, and how.** §5.1's `GET /dp/solutions/{id}/instances?unitId=&environmentId=` is
a list filtered by authorized scope, so scope must reach the data layer somehow. It reaches it as **a set of instance
ids, never as a scope predicate**:

1. The enforcement point resolves the principal's authorized scope against the hierarchy in `sm_control` and produces
   the set of `instance_id`s the caller may see.
2. The DP query is `WHERE tenant_id = :t AND instance_id IN (:authorized)` — bound parameters, organization still
   unconditional, and **no environment column is consulted in `sm_data`**.

This keeps `I7` intact: there is no cross-schema join, only an id set passed across the two planes' service
interfaces, which is the `BE-D3` composition pattern already used for the audit union.

⚠️ **It also depends on an open question this paper does not close.** `architecture.md` §16.1's **CP/DP instance
identity** gap asks precisely *"how a `sm_data` row's `tenant_id` is validated without a join"*, and recommends *"CP
owns identity, DP references an opaque id"*. This proposal **assumes that recommendation**: the hierarchy and instance
*identity* live in `sm_control`; `sm_data` holds `tenant_id` plus an opaque `instance_id`. If the group resolves that
gap the other way, step 1 above changes and §6.4's placement of the new tables changes with it. Stated as a
dependency, not as settled.

### 6.3 Locks, history and events

| Artifact | Under this model |
|---|---|
| `apply_scope` PK `(tenant_id, instance_id)`; the tenant-keyed scope-lock row | **Unchanged.** The lock is per (organization, instance) — organization is the isolation root, so this is already the correct grain. Environment-scoped locking would be *narrower* and is not wanted: two environments never share an instance. |
| The operation-split partial unique fences on `(tenant_id, instance_id, placement_id)` | **Unchanged.** |
| `apply_attempt`'s triple FK `(tenant_id, apply_id, intent_id)` | **Unchanged.** Its "never depend on a join for tenant safety" property is exactly §6.2's argument, already built. |
| `apply_request` as the DP audit record | **Unchanged**, and **no environment column is added in v1.** Environment is reachable through the instance, and `BE-D15` already composes CP and DP audit at the gateway; adding a denormalized column to an audit-grade table to save one join is a migration with a correctness obligation and no caller. |
| Event envelope `tenant` field; topics `sm/v1/<tenant>/…` | **Value** is the organization. **Name** is gated — §7. |
| Open items 32 / 33 (`BE-D5` ACL shape) | Unchanged in substance; "multi-tenant composition" reads as **multi-organization** composition. The technical gap is identical. |

### 6.4 What must be added

All of it in `sm_control`, per §6.2's dependency on `architecture.md` §16.1's *"CP owns identity"* recommendation:

| Addition | Note |
|---|---|
| `organization`, `organization_unit`, `environment` | the tree |
| `organization_unit.parent_unit_id` — **nullable, never populated in v1** | what makes `D12` 🔒's *"parent pointer retained for later nesting"* an artifact rather than an assertion (§1). The fixed-depth scope grammar in §2 is what keeps v1 from using it. |
| `scope` — the canonical scope record | §6.5. Every **grant's** scope is a row here; nothing stores a scope as an array |
| `role_binding.scope_id` → `scope` | `BE-D15` anticipated a typed scope; this is the type |
| `target.ownership_scope_id` → `scope` | `SM-D23` names this field as *"new and was missing"* but gives it no shape; §3.2 needs one, because `D10`'s two-sided rule authorizes `target.use` against it |
| ancestor columns on `organization_unit`, `environment`, `instance`, each with a matching `UNIQUE` **and a composite FK to its own parent** | the `UNIQUE` makes each table an FK *target* for §6.5's scope rows; the **FK on the table itself** is what makes its ancestor columns truthful. §6.5's "the resource side" explains why `UNIQUE` alone leaves a hole. |
| instance: `environment_id`, plus a **denormalized `organization_id`** | so §6.2's isolation predicate never needs a join — the rule `apply_attempt`'s triple FK already follows |

**No change to any `sm_data` table.**

---

### 6.5 Canonical scope paths — how a malformed or cross-organization path is made unstorable

⛔ **SUPERSEDED IN PART by ruling [OR-16](DECISIONS.md#owner-architecture-rulings--2026-09-16), 2026-09-16. The DDL
below is retained unedited (`I3`); implement the ruling.** The `scope` table gains a **`solution_id`** column and a
fifth composite FK; a new **`sm_control.solution`** table keyed
`(organization_id, organization_unit_id, environment_id, solution_id)` is created for it to reference; and
`sm_control.instance` is re-keyed to carry `solution_id` so the chain stays composite to the leaf. ⛔ **`scope_kind`
gains a `solution` value**, and `canonicalize`'s prefix evaluation runs over five components, not four.
⚠️ **`placement_id` is NOT added** — Placement is an operational identity, authorized through its owning Instance.
See §3.2's supersession table for the matching `scopePathOf` paths.

§3.1 treats a scope path as trustworthy input. Nothing so far said how it becomes trustworthy. A four-element id
array is the easiest thing in this design to get wrong — wrong depth, an environment id sitting in the unit
position, a unit from a different organization, a reference to something since deleted — and each produces a
binding that silently grants the wrong set. **Validation in application code is not sufficient**: a scope is
written from at least three places (role bindings, Target ownership, resource ownership), and a direct-SQL writer
is one the application cannot intercept. This section makes the bad paths **unstorable**.

#### The shape: a canonical scope record, not an array column

`role_binding.scope_path` and `target.ownership_scope` become **foreign keys into a single `sm_control.scope`
table**. No table stores a scope as an array.

```sql
CREATE TABLE sm_control.scope (
    scope_id             uuid PRIMARY KEY,
    kind                 text NOT NULL
        CHECK (kind IN ('install','organization','organization_unit','environment','instance')),
    organization_id      uuid NULL,
    organization_unit_id uuid NULL,
    environment_id       uuid NULL,
    instance_id          uuid NULL,

    -- (1) DEPTH AND TYPED POSITION: exactly the columns `kind` requires, and no others.
    CONSTRAINT scope_shape CHECK (
         (kind = 'install'           AND organization_id IS NULL     AND organization_unit_id IS NULL
                                     AND environment_id  IS NULL     AND instance_id          IS NULL)
      OR (kind = 'organization'      AND organization_id IS NOT NULL AND organization_unit_id IS NULL
                                     AND environment_id  IS NULL     AND instance_id          IS NULL)
      OR (kind = 'organization_unit' AND organization_id IS NOT NULL AND organization_unit_id IS NOT NULL
                                     AND environment_id  IS NULL     AND instance_id          IS NULL)
      OR (kind = 'environment'       AND organization_id IS NOT NULL AND organization_unit_id IS NOT NULL
                                     AND environment_id  IS NOT NULL AND instance_id          IS NULL)
      OR (kind = 'instance'          AND organization_id IS NOT NULL AND organization_unit_id IS NOT NULL
                                     AND environment_id  IS NOT NULL AND instance_id          IS NOT NULL)),

    -- (2) PARENTAGE: each level proven to belong to the one above, by composite FK.
    FOREIGN KEY (organization_id)
        REFERENCES sm_control.organization (organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, organization_unit_id)
        REFERENCES sm_control.organization_unit (organization_id, organization_unit_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, organization_unit_id, environment_id)
        REFERENCES sm_control.environment (organization_id, organization_unit_id, environment_id) ON DELETE RESTRICT,
    FOREIGN KEY (organization_id, organization_unit_id, environment_id, instance_id)
        REFERENCES sm_control.instance (organization_id, organization_unit_id, environment_id, instance_id) ON DELETE RESTRICT
);
```

Three properties fall out of that, none of them application code:

- **Typed position and depth** — `scope_shape` refuses `kind='environment'` with a null `environment_id`, and
  refuses any row carrying a column its kind does not permit.
- **Parentage** — the composite FKs are why an environment's id cannot sit in the unit position, and why a unit of
  organization `O2` cannot appear under organization `O1`: the ancestor columns are *part of the referenced key*.
  This is the device `BE-D13` already uses for `apply_attempt`'s triple FK `(tenant_id, apply_id, intent_id)`, for
  the same stated reason — *"an attempt cannot reference another request's intent even within a tenant"*. The
  parent tables therefore carry their ancestor columns with matching `UNIQUE` constraints; that is the cost, and it
  is one plan 004 already pays.
- **Organization-consistency is not a separate check.** A single `organization_id` column participates in every FK,
  so a path mixing two organizations cannot be expressed, let alone stored.

⚠️ **Why the nulls do not defeat the composite FKs.** PostgreSQL's default `MATCH SIMPLE` skips a composite FK when
*any* of its columns is null — exactly the behaviour wanted: for `kind='organization'` the unit, environment and
instance FKs stand down, while for `kind='instance'` all four are enforced. `scope_shape` is what guarantees the
nulls are the *intended* ones, so the CHECK and the FKs are load-bearing together and neither is redundant.

⚠️ **Canonicality needs five partial unique indexes, and each must list only the columns its kind populates.**
PostgreSQL 14 treats nulls as distinct, so a plain `UNIQUE (kind, organization_id, …)` would store the same
organization scope twice. `NULLS NOT DISTINCT` is PostgreSQL 15 and the platform floor is 14
(`architecture.md` §8.0), so the mechanism is one partial unique index per kind — the device `BE-D13` reached for
on exactly this PG-14 null semantics in the apply fences. **Spelled out, because the naive form silently dedupes
nothing for the `install` kind** — all four of its columns are null, which is precisely the case nulls-are-distinct
defeats:

```sql
CREATE UNIQUE INDEX scope_one_install  ON sm_control.scope (kind)            WHERE kind = 'install';
CREATE UNIQUE INDEX scope_one_org      ON sm_control.scope (organization_id) WHERE kind = 'organization';
CREATE UNIQUE INDEX scope_one_unit     ON sm_control.scope (organization_id, organization_unit_id)
                                                                             WHERE kind = 'organization_unit';
CREATE UNIQUE INDEX scope_one_env      ON sm_control.scope (organization_id, organization_unit_id, environment_id)
                                                                             WHERE kind = 'environment';
CREATE UNIQUE INDEX scope_one_instance ON sm_control.scope (organization_id, organization_unit_id,
                                                            environment_id, instance_id)
                                                                             WHERE kind = 'instance';
```

`scope_one_install` indexing `(kind)` alone is what limits the whole-install scope — §8's `bootstrap-admin` grant —
to exactly one row.

**Verified against the primary source, per `I2`** (PostgreSQL 14 `CREATE TABLE`, fetched 2026-09-11): *"There are
three match types: `MATCH FULL`, `MATCH PARTIAL`, and `MATCH SIMPLE` (which is the default) … `MATCH SIMPLE` allows
any of the foreign key columns to be null; if any of them are null, the row is not required to have a match in the
referenced table."* and *"For the purpose of a unique constraint, null values are not considered equal."*
`NULLS NOT DISTINCT` does not appear in the PostgreSQL 14 documentation.

#### The resource side — the same FKs, on the hierarchy tables themselves

⚠️ **Canonicalizing only the grant side leaves the hole open, and an earlier draft of this section did exactly
that.** §3.1 compares `b.scopePath` against `scopePathOf(resource)`, and §3.2 derives a Solution Instance's path
from **the instance row's own ancestor columns** — not from a `sm_control.scope` row. Requiring only a `UNIQUE` on
those columns makes the table an FK *target*; it constrains nothing about the table itself. So
`instance (organization_id=O1, organization_unit_id=U1, environment_id=E1, …)` where `E1` in fact belongs to `U2`
would be **storable**, and a perfectly canonical binding at `(O1, U1)` would then be a prefix of that instance's
derived path — granting access to an instance that is really somewhere else. The scope table would have refused the
bad *binding* and never seen the bad *resource*.

The fix is the same device, applied one level out. Each hierarchy table carries its ancestor columns **and a
composite FK to its parent's `UNIQUE`**:

```sql
-- organization_unit
    UNIQUE (organization_id, organization_unit_id),
    FOREIGN KEY (organization_id) REFERENCES sm_control.organization (organization_id),

-- environment
    UNIQUE (organization_id, organization_unit_id, environment_id),
    FOREIGN KEY (organization_id, organization_unit_id)
        REFERENCES sm_control.organization_unit (organization_id, organization_unit_id),

-- instance
    UNIQUE (organization_id, organization_unit_id, environment_id, instance_id),
    FOREIGN KEY (organization_id, organization_unit_id, environment_id)
        REFERENCES sm_control.environment (organization_id, organization_unit_id, environment_id),
```

Now a derived resource path is as trustworthy as a canonical scope row, because the same composite-FK argument
proves it — and both sides of `isPrefix` rest on the same guarantee. All ancestor columns here are `NOT NULL`, so
`MATCH SIMPLE`'s null-skipping is not in play on this side at all.

#### The single write point

```java
/** The only way a scope_id comes into existence AT RUNTIME. Resolves within the session's
 *  organization and returns the canonical row, creating it if not referenced before.
 *  Refuses kind='install' — see "The one exception" below. */
ScopeId scopes.canonicalize(SessionContext session, ScopeRequest requested);
```

- **A client-supplied array is input to resolution, never persisted and never trusted.** The API accepts a scope as
  a typed request naming ids level by level; the server resolves each level against the hierarchy **within the
  session's organization** (§4.1) and stores the resulting `scope_id`. There is no code path from a request body to
  a scope column.
- **Every runtime writer of a scope row goes through it** — role bindings, Target `ownership_scope`, resource
  ownership relations. An ArchUnit rule in the `BE-D15` §5.2 idiom enforces that no class outside the scope
  package writes `sm_control.scope` or any `scope_id` column, with a planted violation. The **sole** non-runtime
  writer is the migration, and it writes **exactly one** row — the install scope below, proven by obligation (q).
  ⚠️ **Hierarchy rows are not scope rows.** `organization`, `organization_unit`, `environment` and `instance` are
  installer-seeded on a fresh install and are not governed by this rule; their parentage is guaranteed for *any*
  writer by the resource-side composite FKs above, which is why they need no single-writer discipline.
- **DCL, in the §3.1 pattern this repo already uses:** the CP runtime role holds `SELECT, INSERT` on
  `sm_control.scope` and **no `UPDATE`, no `DELETE`** — a canonical scope row is immutable once created, so a
  binding's meaning cannot be changed by rewriting the scope it points at. Re-scoping a binding means pointing it
  at a different `scope_id`: a visible, auditable change to the binding.
- **Transaction:** `canonicalize` and the binding insert commit **in one transaction**, so a resolved-but-unused
  scope row cannot outlive a failed grant, and a binding can never reference a scope not proven in the same commit.
- **Concurrency:** two grants at the same *new* scope race on the per-kind unique index, and a raw insert would
  abort one entire binding transaction. `canonicalize` therefore inserts `ON CONFLICT DO NOTHING` and re-selects
  the canonical row, so the loser of the race reuses the winner's `scope_id` instead of failing.
- **Deletion is refused, not cascaded** — `ON DELETE RESTRICT` throughout. Removing an environment a live binding
  depends on fails loudly rather than silently widening or orphaning a grant. With §2's immutable-parents rule, a
  stored scope cannot drift under a binding's feet.

#### The one exception: the install scope is seeded, never canonicalized

⚠️ **`canonicalize` cannot create the `install` scope, and an earlier draft of this section implied it could.**
That was not a gap but a **bootstrap deadlock**: the whole-install scope has no organization to resolve within, and
the principal that needs it — §8's `bootstrap-admin` — is the *first* principal on the install, so **no session
exists** at the moment its binding must be written. A signature taking a `SessionContext` cannot produce it, and
had that gone unnoticed the install would have had no reachable administrator at all.

The exception is therefore explicit, and narrow:

| | |
|---|---|
| **Who creates it** | The **migration**, not runtime code. One `kind='install'` row with a fixed, migration-recorded `scope_id`, inserted beside the built-in roles that are already migration-seeded (`BE-D15` §5.1). |
| **Runtime refuses it** | `canonicalize` **rejects** a request for `kind='install'` outright. It is not resolvable from a session by construction, so refusing it is what keeps the runtime writer's contract honest rather than quietly special-cased. |
| **Who references it** | The installer-seeded bootstrap `role_binding` points at that `scope_id`. No runtime route creates, mutates or deletes an install-scoped binding. |
| **Uniqueness** | `scope_one_install ON (kind) WHERE kind = 'install'` already limits it to exactly one row **for every writer**, direct SQL included — not merely for `canonicalize`. |
| **Immutability** | The CP runtime role's `SELECT, INSERT`-only grant means it can be neither altered nor removed at runtime; a second `INSERT` is refused by the index above. |

This is the only scope in the system with a non-runtime origin, and it is the one the rest of the model cannot
bootstrap without. **Every other scope — including every scope the demo uses — is created by `canonicalize` at
runtime**, because every other scope has an organization to resolve within and a session to resolve it from.
Obligations **(n)**–**(q)** below prove each half, with **(q)** stating the invariant in the only form that can
actually be checked: after migration, `sm_control.scope` holds exactly one row and its `kind` is `install`.

#### Prove-it-fires obligations

Named, in the executable-DDL idiom `BE-D13` uses for its own obligations (a)–(u), because a constraint nobody has
watched refuse anything is not known to work (`L7`, `L19`):

| | The write that must be refused | Refused by |
|---|---|---|
| **(a)** | wrong depth — `kind='environment'` with `environment_id` null | `scope_shape` CHECK |
| **(b)** | wrong type at a position — an environment's id supplied as `organization_unit_id` | composite FK on `(organization_id, organization_unit_id)` |
| **(c)** | child under the wrong parent — an environment recorded under a unit that is not its own | composite FK on `(organization_id, organization_unit_id, environment_id)` |
| **(d)** | cross-organization component — an environment of `O2` beneath a unit of `O1` | the shared `organization_id` column in both FKs |
| **(e)** | stale reference — `DELETE` of an environment with a live binding | `ON DELETE RESTRICT` |
| **(f)** | non-canonical duplicate — a second row for the same environment scope | the per-kind partial unique index |
| **(g)** | mutation — `UPDATE` of a `sm_control.scope` row by the CP runtime role | DCL: no `UPDATE` grant |
| **(h)** | a scope written from **inside this codebase** by anything but `scopes.canonicalize` | the ArchUnit rule, with a planted violation |
| **(l)** | an `environment` row whose `(organization_id, organization_unit_id)` is not a real unit | the composite FK on `environment` (the resource side) |
| **(m)** | an `instance` row whose `(organization_id, organization_unit_id, environment_id)` is not a real environment | the composite FK on `instance` (the resource side) |
| **(i₁)** | the explicit-filter 404 conditions (§3.4) returning distinguishable responses | one test asserting **byte-equality across that set** |
| **(i₂)** | the direct-lookup 404 conditions (§3.4) returning distinguishable responses | one test asserting **byte-equality across that set** |
| **(n)** | `scopes.canonicalize` called with `kind='install'` | the runtime refusal — the install scope has no session-resolvable organization |
| **(o)** | a **second** `kind='install'` row, inserted by *any* writer including direct SQL | `scope_one_install` |
| **(p)** | `UPDATE` or `DELETE` of the seeded install row by the CP runtime role | DCL: neither grant exists |
| **(q)** | the migration creating **any** scope row besides the install one | a post-migration assertion that `sm_control.scope` holds exactly one row, of `kind='install'` — this is "the only non-runtime origin" made checkable rather than trusted |

And two that must **succeed**, because a fence that refuses everything is equally wrong:

| | Must be allowed |
|---|---|
| **(j)** | an Environment-scoped grant reaches an Instance beneath it — inheritance still works |
| **(k)** | that same grant does **not** reach a sibling Environment's instance |

⚠️ **What DCL and the constraints do *not* close, said plainly.** §6.5 opens by observing that *"a direct-SQL
writer is one the application cannot intercept"*, and obligation (h) is an ArchUnit rule — a compile-time guard
over this codebase's bytecode, by construction blind to such a writer. The runtime role holds `SELECT, INSERT` on
`sm_control.scope`, which is exactly the privilege a direct-SQL writer would use. So:

| Property | Closed against a direct-SQL writer? |
|---|---|
| depth, typed position, parentage, organization-consistency, canonicality, immutability | **yes** — CHECK, FKs, partial unique indexes and DCL apply to every writer |
| *"resolves within the session's organization"* (the `canonicalize` contract) | **no** — no database constraint can see a session |

The residual is bounded rather than eliminated, and the bound is worth stating: a scope row inserted directly for
another organization is still **filtered at evaluation** by §3.1 step 4's `b.scopePath[0] == org`, and a *binding*
pointing at it would have to be inserted directly too. This is the honest shape — one property enforced by code
discipline, everything else by the database — not a claim that (h) closes what it cannot.

⚠️ **(k) carries a mutation obligation, not merely a test.** The plan-004 repair wave found four guards that read
correctly in source and whose removal no test detected. The prefix check is the same species: remove it so every
grant reaches everything, and **(k) must turn red**. A scope check whose deletion the suite cannot notice is not a
scope check.

## 7. The gated question: environment-level isolation on the mesh

**Do not let this pass as an implementation detail.** It is a new authorization surface created by this model.

Topics are `sm/v1/<organization>/…`, and open item 33's proposed v1 posture is **organization-wide notify
visibility**. This model introduces principals who are legitimately scoped **below** the organization — a Developer
granted at `[ORG, UNIT, ENV]` (ids, per §2's notation). Under an organization-scoped subscription ACL, that
principal's browser can
subscribe to notify events for **PROD**, which their REST scope forbids them to read.

So: **REST would enforce environment scope and the broker would not.** The design's own `BE-D5` mechanism — candidate
(a), the substitution-variable ACL — substitutes only at the *client-username* level, and the topics carry no
environment level to scope at. This is structurally the same shape as open item 33, one level down.

**What v1 may honestly claim.** The demo install (§8) has **one** environment, so no principal is scoped below an
environment that holds data they may not see, and the exposure is not reachable. That is a property of the seed, not
of the design, and it evaporates the moment a second environment is created.

**The gate (open item 35).** Before *either* of these, the architect group must decide environment-level mesh
isolation:

1. a second Environment exists holding data any principal is scoped away from, **or**
2. direct-browser-mesh subscription ships beyond the single-organization, single-environment demo.

Three shapes are available and none is chosen here: an environment level in the topic taxonomy (a `BE-D4` change,
riding G3); an environment-bearing client-username grammar (composes with open item 32's routes and cannot be picked
independently of it); or notify payloads carrying no environment-distinguishing information at all, with the console
filtering — which is a *confidentiality* claim about payload contents, and `I6` currently guarantees only that
*values* are absent.

---

## 8. The demo seed — one organization, one unit, one environment

**The base is installer-seeded; the demo operator is post-login provisioning.** The hierarchy, the install scope
and the bootstrap admin are written by the migration and are **never created by the console** — following
`BE-D15` §5.3's mechanics for the amendment's **option A**. `demo-operator` and its Environment-scope binding are
not: they are provisioned after first sign-in, through the authenticated path described below. ⚠️ An earlier draft
opened this section by claiming the whole seed was migration-created and never console-created, which contradicted
its own corrected runtime path further down. ⚠️ Cited with the qualifier `BE-D15` keeps and an earlier draft of this paper dropped:
[`D10-AMENDMENT-PROPOSAL.md`](D10-AMENDMENT-PROPOSAL.md) §3a recommends A *because it alone works on all three loci
without new tooling*, and notes **B is materially safer** where the console is not reachable before setup — *"a
security posture call, not an engineering one"*. The posture call stays the group's. (`dev/frontend`'s `D19` records
the same shape as settled; that record is open item 34, and §9 below neither adopts nor rejects it.)

**Hierarchy** — ids are the identity, names are labels:

```
organization        ORG  = 0a1d…  name "Solace Demo"
└── organization_unit  UNIT = 7b3c…  name "Platform Engineering"
      └── environment     ENV  = c41e…  name "dev"
            └── instance      INST = 9f20…  name "cache-primary"   (Cache, one declared package version)
                  └── placement → Target "local-docker"            (control_locus: dp-remote, driver: docker)
```

**How each part comes into being** — this is the half an earlier draft got wrong, by calling both scope rows
migration-seeded and so contradicting §6.5's "only scope with a non-runtime origin" two sections earlier:

| Seeded by the **installer/migration** | Created at **runtime**, through the product's own path |
|---|---|
| the hierarchy rows — `organization`, `organization_unit`, `environment`, `instance`, `placement` (not scope rows; §6.5) | — |
| `scope_install` — the one scope that cannot be canonicalized (§6.5) | — |
| `bootstrap-admin` + its binding to `scope_install` | — |
| — | `demo-operator`, and its binding at Environment scope, created **by the bootstrap admin through the authenticated API** after first sign-in and credential rotation |
| — | `scope_demo_env` — produced by `scopes.canonicalize` while creating that binding, resolved from the admin's **real session** |

```
scope_install    kind='install'      (all four ancestor columns NULL)   -- migration
scope_demo_env   kind='environment'  (organization_id=ORG, organization_unit_id=UNIT, environment_id=ENV)
                                                                        -- canonicalize, at binding-creation time

bootstrap-admin  Super Administrator  role_binding.scope_id = scope_install   -- migration; single-use, must_rotate
demo-operator    Operator             role_binding.scope_id = scope_demo_env  -- created through the API
```

⚠️ **`scope_demo_env` is deliberately *not* migration-seeded.** Unlike the install scope it has an organization to
resolve within, and by the time it is needed there is a real session — the bootstrap admin's — to resolve it from.
So no exception is required, and inventing one would be worse than redundant: **a demo seed that bypassed
`canonicalize` would prove nothing about `canonicalize`.**

#### The provisioning path, named — `sm-admin provision-demo-operator`

⚠️ **"Through the authenticated API" was not an executable instruction, because the demo's control surface has no
principal or role-binding endpoint.** [`../tasks/demo-delivery-sequence.md`](../tasks/demo-delivery-sequence.md)
W3 deliberately ships a thin surface — auth, hierarchy reads, designer, config, apply, health — and adding
`POST /cp/admin/users` and `POST /cp/admin/assignments` to it would widen the demo by the whole Admin surface to
create one account. So the path is a **one-shot authenticated setup command**, shipped with the demo compose and
part of **W5**, not W3:

| | |
|---|---|
| **Authenticates** | as `bootstrap-admin`, with the rotated credential. It **refuses to run while `must_rotate` is set**, so the single-use credential must have been changed first — the command cannot be used to skip that step. |
| **Calls** | the same services any caller would: the principal service to create `demo-operator`, `scopes.canonicalize` to resolve the Environment scope from its **real session**, and the binding service to grant `Operator` there. **No new code path, and no new HTTP surface.** |
| **Authorized** | through `authorizationService.require` like every other caller, holding **`user.create`** and **`role.assign`** from the `D10` amendment's Administration group. It has no privileged shortcut: run as a principal lacking either verb, it is refused. |
| **Audited** | both are privileged CP actions, so both write `sm_control.audit_log` per `BE-D15` §5.3 — attributable to `bootstrap-admin`, not to "the installer". |
| **Gated** | **post-G2** with the rest of W2/W5's auth half. It is auth-bearing code. |

**This is not a bypass and must not become one.** It is a client of the same authenticated, authorized, audited
services the console would use; the only thing it avoids is a browser screen the demo does not need. The as-built
console *does* declare `POST /cp/admin/users` and `POST /cp/admin/assignments`
(`console-rest-surface.md` §13.10, §13.7), and those are the natural home for this once the Admin screens are in
scope — at which point the command becomes redundant rather than load-bearing.

A binding references a `scope_id`; it never carries a path, and never a name.

⚠️ **The seed is written in ids because the model is.** An earlier draft wrote `demo-operator`'s grant as a path
of **human-readable slugs** rather than ids — precisely the names-versus-ids defect §3 and `SM-D33` exist to
remove, reintroduced in the paper's own worked example. The slug form is not reproduced here: the consistency scan
greps for any name-bearing scope literal in these documents, and a quotation would have to be exempted from the
check it exists to enforce. Renaming *"Platform Engineering"* must move no grant, and
it does not: `scope_demo_env` holds `UNIT`, and the name is a label on the `organization_unit` row. Where this
document writes a path like `[org, unit, dev]` in prose it is shorthand for the **ids** at those positions, per the
notation fixed in §2 — never for the labels.

`demo-operator` is deliberately scoped at **Environment**, not at Organization: it is the only seed element that
exercises the §3.1 prefix rule at all, and a demo that grants everything at the root proves nothing about the model
it is demonstrating. It should be the account the demo is driven from.

---

## 9. What this proposal explicitly does NOT do

- It does not amend `D11`. Only the architect group can.
- It does not adopt, ratify or reject `dev/frontend`'s `D18`/`D19`/`D20` (open item 34).
- It does not choose the verb catalog — that is [`D10-AMENDMENT-PROPOSAL.md`](D10-AMENDMENT-PROPOSAL.md), unchanged.
- It does not rename anything in `contracts/`, which is single-writer and gated (§7, open item 36).
- It does not rename `tenant_id` (§6.1).
- It does not decide environment-level mesh isolation (§7, open item 35).
- It does not add an arbitrary-depth hierarchy, a hierarchy editor, or browser mesh subscription work to v1.

## 10. What is needed from the group

1. **Approve, amend or reject the model** (§1–§2) as an amendment to `D11`'s hierarchy clause, confirming `D12`.
2. **Rule on scoped evaluation** (§3.2) — this closes `BE-D15`'s flagged v1 deviation, and it is the same ruling the
   `D10` amendment already asks for under its own §Addendum 2.
3. **Confirm or deny the `D18`/`D19`/`D20` record** on `dev/frontend` (open item 34). Until this is answered, two
   registers disagree about what is locked.
4. **Accept the gate on environment-level mesh isolation** (§7, open item 35) rather than letting organization-scoped
   subscriptions stand in for it by default.
5. **Accept the vocabulary posture** (§5.2, §6.1) — renamed at the API, not in storage, in one coordinated release.
