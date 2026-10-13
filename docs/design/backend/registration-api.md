# D-REG: registering targets, SSH credentials, host keys, entitlements and the scope tree through the API

**Status: PROPOSED, UNBASELINED — RULED by the owner on 2026-10-07 ([`OR-121`](../DECISIONS.md)); its plan 013 is
APPROVED for execution.** Its decisions are `BE-D54`–`BE-D57` (PROPOSED, §12); its three verbs are rows 41–43 of the
combined `D10` amendment, [§1.9](../D10-G2-APPROVAL-2026-09-15.md).

> **Rulings (`OR-121`) — every question takes this design's recommendation:** OQ-1 (a) three verbs; OQ-2 (b) scan then
> confirm; OQ-3 (d) check at registration and at every use; OQ-4 (a) `entitlement.set` at install, refusing below
> `declaredInstances`; OQ-5 (a) no organization creation in v1; OQ-6 (a) W3 in, never an Administrator over the API;
> OQ-7 (a) Administrator only; OQ-8 (a) per-target keys; OQ-9 (a) `docker version` in the test; OQ-11 (b) a retired
> target frees its name; OQ-12 (a) refuse edits while work is in flight; OQ-13 (a) the connection record under
> `target.edit`; OQ-14 (b) credentials only over a secure transport, a trusted proxy or loopback; OQ-15 (a) one SSH
> image by exact tag (its licence row is still the owner's to approve). ⚠️ Routed back for confirmation by name, not
> assumed: the catalog rows (`D10` §0) and OQ-4's change to `OR-48`.
>
> **Two facts changed after this design was written, both applied in plan 013:** (1) `OR-120` (branch `p/names`,
> `sm_control` `V015`) adds the hierarchy `name` columns (1–100 characters, shape CHECK, default-name trigger), so §10.1's
> column work reduces to per-parent uniqueness and grants; (2) the combined amendment is **§1.9** with rows 41–43, not a
> separate §1.10, and its catalog code (all five verbs) lands in plan 012 T1, so the counts are **43 / 24 / 8**
> (Administrator / Operator / Viewer — the Operator's 24 include plan 012's verbs and `instance.stop`).

Written 2026-10-07 against `integration/dp-remote-demo` @
`36af1df6`, on branch `d/regapi`. It designs `OR-114` item (3), widened by `OR-116` (entitlements join item (3)) and
by `OR-119` (item (3) closes before the `OR-115` PR into `dev`). Its plan is
[`013-registration-api.md`](../../plans/backend/013-registration-api.md), also a DRAFT. **Revised once after an
adversarial review (findings R1–R21); every disposition is in §15.**

**Sequenced after the sibling draft plan 012** (`d/ops501`, `docs/design/backend/ops-501.md`; `OR-114` item (2)
precedes item (3)). Plan 012 changes four things this design builds on, so every count and body below is stated **on
plan 012's outcome under its recommended answers**. If the owner rules 012 differently, the integrator recomputes
them from plan 012's Results before W0 here:

| Plan 012 changes | So this design assumes |
|---|---|
| The catalog goes 38 → 40 through a new **§1.9** (rows 39–40) appended to `D10-G2-APPROVAL-2026-09-15.md`. `validate-openapi.py` and `PermissionCatalogMirrorTest` read that section. Operator 21 → 23. | This design appends **§1.10** (rows 41–43) the same way. Catalog 40 → 43, Administrator 43, Operator 23 (25 under OQ-7 (b)), Viewer 8. |
| `record_change_audit()` is replaced to add `INSTANCE_UNDECLARED`. | §10.1's replacement is **012's body plus this design's operation names**. That is a merge of two bodies, not a renumbering. |
| `instance.retired_at` is added, and `countInstances` excludes retired instances (012 T4). | E1/E2 count with 012's `countInstances` (§5.2). |
| Two `sm_control` and up to two `sm_data` migrations. | Migration numbers are allocated at merge (`OR-22`). |

**Decisions drafted here** were written under a `«next free BE-D»` placeholder (§12); the integrator allocated
`BE-D54`–`BE-D57` on 2026-10-07 (`OR-11`, `OR-17`). **Nothing here amends a LOCKED decision by itself:** the
three new verbs of §4 amend the ratified `D10` amendment's catalog and need an explicit owner ruling that names
`D10` and the scope of the change (`OR-1`'s rule, as `OR-80` did). Until that ruling exists, the operations that need
them stay out of `console.yaml` (check 11) and the operator commands stay the only route.

---

## 0. What is being asked, and the honest status quo

The owner's words (`OR-119`, 2026-10-07): *"Registering targets, SSH keys, host keys and entitlements through the
API. Today only operator commands can do it, and the frontend needs it."*

**What exists today** (measured in the tree at `36af1df6`):

| Thing | How it is created today | Through HTTP? |
|---|---|---|
| Target governance row (`sm_control.target`) | `TargetRegistrationMain` — authenticates a principal at the terminal, `require(target.create)` at the ownership scope | ⛔ no |
| Target connection (`sm_data.target_connection`: host, port, user, pinned fingerprint, envelope, `credential_ref`) | `TargetConnectionMain` — DP runtime credential + master key from the environment; key read from a **file path** | ⛔ no |
| SSH private key (`sm_secret.secret`, purpose `SSH_PRIVATE_KEY`) | inside `TargetConnectionMain` (`EnvelopeSecretStore.put`) | ⛔ no |
| Host keys offered by the host (`sm_data.target_host_key`, `OR-89` residual) | `TargetConnectionMain` scans (`SshHostKeyScanner`) or takes `SM_TARGET_HOST_KEY_FINGERPRINTS` | ⛔ no |
| SSH key **rotation** | `InventoryService.rotateCredential` **exists and has no caller** — no command, no endpoint | ⛔ no |
| Target connection **edit** (host, port, user, re-pin) | runtime role holds `UPDATE (host, port, ssh_user, host_key_fingerprint, credential_ref, retired_at)` (`sm_data` V007); **no code path** | ⛔ no |
| Target **retire** | `TARGET_RETIRED` is in the `OR-25` allow-list (V013); **no code path** | ⛔ no |
| Entitlement (`sm_control.entitlement`) | `EntitlementMain` (`OR-48`): runtime credential, audit actor `NULL`, `ENTITLEMENT_SET` | ⛔ no |
| Organization | V007 seeds exactly one (`…0011`) | ⛔ no |
| Sub-organization, environment | **nothing** — `backend/local/LocalSeed.java` (on `p/local`, `OR-118`) inserts them **as the database superuser** | ⛔ no |
| Hierarchy **names** | **no column exists.** `ScopeNode.placeholderNamed` returns the id's own string (`cp-api` `ScopeNode`) | n/a |
| Local principal + role binding (an Operator user) | **nothing** — `LocalSeed` inserts them as the superuser. The 11 `/cp/admin/*` operations are *"planned, not yet contracted"* (`ENDPOINT-INVENTORY.md` §2); **no plan builds them** | ⛔ no |
| Master-key re-wrap (`KeyRotationMain`) | operator command, both master keys from the environment (`OR-85`) | ⛔ no — and §1 keeps it that way |

The deleted demo (`a5167c07^:backend/demo-launcher`) had three screens' worth of endpoints for this: `POST /cp/setup`
(first-run password), `POST /cp/ssh-keys` (a **key library** by reference, AES-encrypted PEM in `demo_control`) and
`POST /cp/ssh-host-key` (one unauthenticated handshake returning one fingerprint). Its first-run screen is already
replaced by `login` + forced `rotateOwnCredential` (`must_rotate`); the other two have no production equivalent.

## 1. Scope

**In scope (this design):**

1. **Targets, end to end:** host-key scan, register (governance + connection + key in one call), read the connection
   record, edit host/port/user and re-pin, rotate the SSH key, test the connection, retire.
2. **Entitlements:** read the organization's entitlements; set one (`OR-116`'s "API registration of entitlements
   joins `OR-114` item (3)").
3. **The scope tree below the organization:** create and rename sub-organizations and environments; rename the
   organization; the `name` columns all of that needs (and which `OR-116`'s identity names have been waiting for).
4. **The operator user**, conditionally (OQ-6): create a local user, list users, deactivate / reset a credential,
   list roles, grant and revoke a role binding — 7 of the 11 *planned* admin operations, whose verbs **already exist**
   in the catalog (#25, #27–#30, #38). They are in scope because no other plan builds them and `LocalSeed` creates the Operator by superuser
   SQL, which the frontend cannot do.

**Out of scope, with the reason:**

| Not here | Why |
|---|---|
| **Creating an organization** | A principal belongs to exactly one organization (`sm_control.principal.organization_id NOT NULL`), and `ScopeEvaluatingAuthorizationService.require` answers `404` for any resource in another organization **before** it reads a grant — even for an install-scope binding. A new organization therefore has no one who can act in it, and `OR-5` gives it its own Message VPN, which is install provisioning. Designing cross-organization administration is a separate decision (OQ-5). v1 keeps one organization per install (V007); this design lets it be **renamed**. |
| **Master-key rotation (`KeyRotationMain`)** | It holds the current **and** previous master key from the environment (`OR-85` OQ-2/OQ-3). Putting it behind HTTP would put both keys in the serving process's reach of a request. It stays an operator command. |
| **Broker connections (`BrokerConnectionMain`)** | Not in the owner's list. Same shape as targets; a follow-up can reuse §6's secret path. |
| **Custom roles, `group.*`, `authz.introspect.*` screens, `GET /admin/effective-permissions`, `GET /admin/scope-tree`, `GET /admin/permission-catalog`, `GET /admin/principals`** | Custom roles are blocked by DCL (`sm_control.role` is `SELECT`-only, approval §3 item 4); `group.*` has no verb (open item 39c). The four reads are not needed to create an operator user. |
| **The VM adapter's targets (`kind: vm`)** | `OR-111`: Docker only now. `transport` stays the closed set `[ssh-docker]`; the VM plan widens it. |
| **Deleting anything** | Deactivate-never-delete everywhere: targets retire, principals deactivate, bindings are revoked (a `DELETE` of the binding row, which the `OR-19` trigger records). Scope nodes are never deleted (`RESTRICT` foreign keys everywhere). |

**What does not change, whatever the owner rules below:** the operator commands stay and keep working (install time,
emergency, scripts and `OR-118`'s local stack); `I5` (the organization is the session's, never a body field); `I6`
(no private key, credential or secret is ever returned, logged or hashed into anything but its envelope); `I7` (the
gateway composes `cp-api` and `dp-api` and owns no database, `OR-10`); deny-by-default (`D10`); no event is added
(the notify catalog is closed; every change here is REST-observed, `OR-3`); no MI is touched (`I1`).

## 2. Binding inputs

| Ruling | What it fixes here |
|---|---|
| `OR-28` | Targets: CP governance row (id + owning scope), DP connection details. The two writes are separate planes. |
| `OR-30` | Host credentials are envelope-encrypted in DP-owned secret tables; **secrets may be entered through the console**. |
| `OR-89` | Host-key uniqueness is per organization; every key type the host offers is recorded. |
| `OR-52` | Each target fences its paths (allowed config root, container prefix) **fixed at registration**, immutable. |
| `OR-56` | Targets are used downward only; `target.use` at the target's owning scope. |
| `OR-82` | `direct-http` polls the target's registered address — so the registered `host` is also a **health and metrics** address, which is why §7's network rule matters. |
| `OR-48` | Entitlements are changed by an operator command, **no verb added or bent**. ⚠️ This design proposes the API route `OR-116` anticipated, which needs a verb — so it needs a ruling that names `OR-48` (OQ-1, OQ-4). |
| `OR-8`, `OR-12` | Three built-in roles; Administrator is bootstrap/emergency only. ⚠️ `target.create`, `user.create` and `role.assign` are **Administrator-only** today, so production registration is an Administrator act unless OQ-7 rules otherwise. |
| `OR-19`, `OR-25` | Every `sm_control` state change is trigger-audited in the same transaction and fails closed without a known operation, correlation id and client address. |
| `OR-22`, `OR-105 (e)` | Published migrations are frozen; every change below is a **new** migration. |
| `OR-38` | `TargetList` and `ScopeNodeList` carry no `stateVersion`; the new target and hierarchy responses follow them. |
| `OR-85` | Superseded secret rows are kept and re-wrapped; the runtime has no `DELETE` on `sm_secret`. Every stored key is forever (until an owner purge), so a replay must not store a second copy (§8). |
| `OR-104` (Q-A2 V-4) | Precedent: a secret may be **compared in memory inside `dp-core`**, reporting only same/differs, through a compare-only port. §8 follows its rule, opening a stored key only inside `dp-core`. It does not reuse the port: `SecretCompare` resolves `CONFIG_FIELD` only, and a text compare would misread a re-exported key (§8). |
| `OR-67` | Test images are approved **by exact tag** (`busybox:1.36`, `postgres:14`, `ryuk`). An SSH-server fixture image is a new approval (OQ-15). |
| `OR-88` | Precedent: an abandoned declare frees its instance name. OQ-11 asks the same question of a retired target's name. |
| `OR-110`, `OR-118` | There is no local SSH harness (`OR-118`: *"no local SSH harness, plan 010 T6.6"*). Scenarios that need a real Docker daemon over SSH run on the E2E host, which the integrator may exercise freely. |
| `OR-21` | `__Host-` cookies are always `Secure`, but *"`http://localhost` still works"*. Non-browser clients are not bound by the cookie rule (§6.1, OQ-14). |

## 3. The running scenarios

Every option in §13 is run through these five.

- **S1 — fresh install, everything through the console.** The owner signs in as `bootstrap-admin`, rotates the
  credential, names the organization, creates sub-organization `Payments` and environment `dev`, entitles `cache` × 5,
  loads the Cache package, scans `10.0.4.17:22`, confirms the ed25519 fingerprint, uploads the key, registers the
  target at `dev`, tests it, and declares an instance. Today steps 3–5 and 8–10 need SQL or a terminal.
- **S2 — the VM's address changes and it is re-imaged.** New IP, new host keys. Instances on it must keep their
  placements (re-targeting mints new placements, `OR-29`). **S2′:** an Operator's apply on that target is still
  running when the administrator submits the edit.
- **S3 — a private key leaks.** The administrator rotates the key on 12 targets now. SM must stop using the old key
  promptly, and the old key must stop being accepted by the hosts.
- **S4 — a stolen Administrator session.** The attacker tries to make the DP open connections to `169.254.169.254`,
  `127.0.0.1:5432` and an internal service, and tries to read a stored key back.
- **S5 — a timeout during registration.** The browser's `POST` times out after the CP row committed and before (or
  after) the DP row committed; the user presses *Register* again, possibly with a different key file.

## 4. Verbs and scopes

| Operation | Verb | Scope evaluated at | Consequence | Status of the verb |
|---|---|---|---|---|
| scan host keys | `target.create` | the requested `ownershipScope` | STANDARD (writes nothing) | ✅ catalog #24 |
| register target | `target.create` | the requested `ownershipScope` | HIGH (`Permission.TARGET_CREATE` is already `HIGH`) | ✅ catalog #24 |
| read connection record | **`target.edit`** (OQ-1 (a)) · `target.create` (OQ-1 (b)/(c)); **not** `target.view` (OQ-13) | the target's `ownershipScope` | — | per OQ-1 |
| edit connection / re-pin / rotate key / retire | **`target.edit`** | the target's `ownershipScope` | HIGH | ⛔ **new — OQ-1** |
| test connection | `target.use` | the target's `ownershipScope` | STANDARD | ✅ catalog #2 |
| list entitlements | `solution.view` | organization | — | ✅ catalog #3 (OQ-4 reading) |
| set entitlement | **`entitlement.set`** | **install** | HIGH | ⛔ **new — OQ-1/OQ-4** |
| create / rename sub-organization, environment; rename organization | **`hierarchy.edit`** | the **parent** scope (create) / the node (rename); **an organization rename is evaluated at install scope only (`OR-125` (3))** | STANDARD | ⛔ **new — OQ-1** |
| list users | `user.view` | organization ↓ ⚠️ | — | ✅ #28 |
| create user | `user.create` | organization ⚠️ | — | ✅ #29 |
| deactivate user | `user.deactivate` | organization ⚠️ | HIGH | ✅ #30 |
| reset another user's credential | `user.edit` | organization ⚠️ | HIGH | ✅ #38 |
| list roles, list assignments | `role.view` | install / organization ↓ | — | ✅ #25 |
| grant / revoke assignment | `role.assign` | **the granted scope** (console-integration §5.2 row 18) | HIGH | ✅ #27 |

**Why `entitlement.set` is evaluated at install scope.** An entitlement is a licensing limit on an organization. If it
were evaluated at the organization, an Administrator bound at that organization could raise its own quota, and the
control would mean nothing. At install scope only an install-bound principal passes. The **organization is still the
session's** (`I5`): the resource path is `install`, whose `organization()` is `null`, so `require` passes its
cross-organization check and evaluates the install-scope grant — no request field names an organization. In a
one-organization install (v1), the install administrator's session organization **is** that organization.

**Why the three new verbs are proposed instead of reusing `target.create`.** Reusing it for edit, rotate and retire
would make `target.create` mean "administer targets" — the "bent verb" `OR-48` refused. Separate verbs cost nothing
in v1 (all Administrator-only, no custom roles) and keep a later custom role able to rotate keys without creating
targets. OQ-1 sets out the cheaper reading.

What each new verb costs, by **plan 012's mechanism** (preamble):

- The catalog grows from plan 012's 40 to 43 under OQ-1 (a).
- The verbs are added in a **§1.10 appended to `D10-G2-APPROVAL-2026-09-15.md`** (rows 41–43, with forward pointers
  only, `I3`). §1.9 is plan 012's.
- `Permission.java` gains them. `PermissionCatalogMirrorTest` (`cp-api`) reads §1.10 as it reads §1.9.
  `validate-openapi.py` changes its count 40 → 43, the same edit plan 012 makes 38 → 40. There is no separate
  "read from the ruling row" mechanism.
- Administrator gains each verb explicitly. V003 seeded Administrator by `SELECT … FROM permission_catalog` once, so a
  new verb needs its own `role_permission` row. `BuiltInRoleSeedIT` (`cp-core`) asserts the new role counts.
- `frontend/src/shared/authz/catalog.ts` drifts until the frontend team reconciles it. This is recorded, not touched
  here.

**⚠️ Divergence flagged, not resolved silently (`I4`).** The `user.*` rows above evaluate at the **organization**.
That follows the code: `ResourceKind.PRINCIPAL` is *"Organization-level: administering a principal is an organization
act"*. `console-integration.md` §5.2 rows 21–23 give `GET/POST/PATCH /admin/users` the scope **`install ↓`**. This
design follows the code, because the code carries the ruled authorization model and a principal belongs to exactly
one organization. T4.2 of plan 013 adds a forward pointer on those rows, leaving their text unedited (`I3`). If the
owner prefers the install reading, U1–U3 move to install scope and only an install-bound principal can administer
users.

## 5. The endpoints

All are under `/api`, CSRF-protected for mutations (`X-SM-CSRF`), session-cookie authenticated, refused under
`must_rotate` (they are not `LocalOperation`s). Problem bodies use the existing `ErrorCode` set. Three new codes
are proposed, all `422`: `CREDENTIAL_INVALID` (§6.3), `HOST_KEY_NOT_OFFERED` (§7.2) and `TARGET_ADDRESS_REFUSED`
(§7.3). Under OQ-14 (b), the credential-bearing routes T2, T5, U2 and U3 can also answer a fourth new code,
`403 INSECURE_TRANSPORT`.

### 5.1 Targets

| # | Method and path | operationId | Body → response |
|---|---|---|---|
| T1 | `POST /dp/host-key-scans` | `scanHostKeys` | `{host, port?, ownershipScope}` → `200 {host, port, offered: [{fingerprint, algorithm}]}`. Writes nothing. |
| T2 | `POST /cp/targets` | `registerTarget` | `{name, kind, transport, ownershipScope, connection: {host, port?, sshUser, hostKeyFingerprint, allowedConfigRoot, allowedContainerPrefix}, privateKey}` → `201 TargetDetail` (new) · `200 TargetDetail` (identical replay, §8) · `409` (name taken differently, a different key, or a host key held by another live target, §8) · `422` (`VALIDATION_FAILED`, `CREDENTIAL_INVALID`, `HOST_KEY_NOT_OFFERED`, `TARGET_ADDRESS_REFUSED`) · `503` (the host could not be scanned). Every `409`/`422`/`503` is decided **before anything is written** (§8). |
| T3 | `GET /dp/targets/{targetId}/connection` | `getTargetConnection` | → `200 TargetConnection` (below). `404` when not visible or not live. Verb per OQ-13: a management read, **not** `target.view`. |
| T4 | `PATCH /dp/targets/{targetId}/connection` | `editTargetConnection` | `{host?, port?, sshUser?, hostKeyFingerprint?}` → `200 TargetConnection`. Any change of `host`, `port` or `hostKeyFingerprint` re-scans, and the (new or kept) pin must be offered. `allowedConfigRoot` / `allowedContainerPrefix` are **not** accepted (`400`, immutable, `OR-52`). **`409 CONFLICT` while any work is in flight on the target** (OQ-12, §7.2). Re-pin also retires the target's `target_host_key` rows that the fresh scan no longer offers (§7.2). |
| T5 | `PUT /dp/targets/{targetId}/credential` | `rotateTargetCredential` | `{privateKey}` → `200 {credential: CredentialSummary}`. Same key as stored (by public-key fingerprint, §8) → `200`, nothing written. **Not** refused while work is in flight (S3 is urgent; §6.4 states the residual). |
| T6 | `POST /dp/targets/{targetId}/connection/test` | `testTargetConnection` | no body → `200 {hostKeyPinned: bool, authenticated: bool, docker: {available: bool, serverVersion?}, checkedAt}`. Never a `5xx` for an unreachable host: an unreachable host is a result, not an error. |
| T7 | `POST /cp/targets/{targetId}/retire` | `retireTarget` | no body → `200 Target` (`retiredAt` set) · `409 CONFLICT` while any **live placement** names it. This is not a choice: V007's `target_connection_retire_guard` trigger (`target_not_retired_under_live_placements`) already refuses it. The DP maps that exception to `409`, and a service-side pre-check exists only to give a precise text. Idempotent: retiring a retired target is `200`. **Order:** DP connection first (a retired connection is unusable at once, and the V012 trigger frees its host keys), then the CP row (`TARGET_RETIRED`). A failure between the two leaves a listed target that cannot be used, and the retry completes it. **A CP row with no DP row** (a registration that never reached the DP, §8) retires **CP-only**. The DP step answers "no connection record" and is treated as done; there is nothing to guard, because a target with no connection record can have no placement. |

`TargetConnection` (new schema; read under the management verb of OQ-13, not `target.view`):

```yaml
TargetConnection:
  required: [targetId, transport, host, port, sshUser, hostKeyFingerprint, offeredHostKeys,
             allowedConfigRoot, allowedContainerPrefix, credential]
  properties:
    targetId:            { type: string, format: uuid }
    transport:           { type: string, enum: [ssh-docker] }
    host:                { type: string }
    port:                { type: integer, minimum: 1, maximum: 65535 }
    sshUser:             { type: string }
    hostKeyFingerprint:  { type: string, pattern: '^SHA256:[A-Za-z0-9+/]{20,86}={0,2}$' }   # the pin
    offeredHostKeys:     { type: array, items: { type: string } }                            # OR-89's set
    allowedConfigRoot:   { type: string, readOnly: true }
    allowedContainerPrefix: { type: string, readOnly: true }
    credential:          { $ref: CredentialSummary }
CredentialSummary:
  required: [present]
  properties:
    present:              { type: boolean }
    publicKeyFingerprint: { type: string }      # SHA256 of the PUBLIC key derived from the stored private key
    setAt:                { type: string, format: date-time }
```

⛔ **There is no field, in any response, that carries private-key material, its `credential_ref`, or a hash of the
private key.** `publicKeyFingerprint` is the fingerprint of the **public** half (what `ssh-keygen -l` prints for the
`.pub`), so the console can show *which* key is stored and the user can compare it with `authorized_keys` — the one
thing a write-only credential otherwise makes impossible (S3: *did the rotation take?*).

`TargetDetail` = `Target` (unchanged) + `connection: TargetConnection`. Only T2 returns it, and T2's caller holds
`target.create` at that scope. `listTargets` keeps returning `Target`, the envelope-only shape that `target.view`
holders (Viewer, Operator) see today. Today's `dp-api` deliberately keeps host, user, fingerprint and credential
reference out of what it exposes (`TargetConnectionService.liveEnvelopes`), and OQ-13 keeps that posture for
`target.view`.

**Why T2 is one call and not "register, then upload the key".** A connection row without a key is not a valid row
(`credential_ref NOT NULL`), and a key stored without a row is an unreferenced secret that `OR-85` keeps forever. One
call is the `setConfigSecret` precedent (`OR-60`, "one call returning `200 StagedConfig`").

**Why T2 lives under `/cp/targets` while T3–T6 live under `/dp/…`.** The path prefix names the owning plane
(console-integration §5.2 vs §5.3). The target's identity and ownership are the CP's (T2 creates them, T7 retires
them, `listTargets` already reads `/cp/targets`); the connection and credential are the DP's.

> **Rider, 2026-10-08 (plan 013 T1.5; adds, changes no decision; items 1 and 4 are flagged for the owner).**
> 1. **The edit's rescan draws on the same budget as a scan, and is audited as the edit it is.** T4 scans the new host exactly as
>    T2's pre-check does (W3 review `W3C-2`), so it takes a token from the same per-session bucket (default 10 a minute, `429
>    RATE_LIMITED`) and leaves one audit row, but of `target.edit` on the target (`TargetProbeAudit.rescanned`), not `target.create`
>    on a scope. A change of `sshUser` alone is neither scanned nor charged. Whichever way the owner rules `W3C-2` (confirm the shared
>    bucket, or give the pre-check its own), the edit follows it: the charge is one method, `chargeRescan`. The connection test
>    (T6) is audited as `target.use` on the target with the furthest step reached (`TargetProbeAudit.tested`) and *(was: not rate-limited; see the second rider, item 2)* is not rate-limited,
>    since it connects to the host the record already names, not one the caller names.
> 2. **The edit is planned, then applied.** `planEdit` (read-only, no I/O) says whether anything changes and whether the host must
>    be scanned, so the gateway can charge a scan it is about to cause; `editConnection` scans **before any lock** (no lock on
>    `apply_scope` is held across a connection to a host the caller named), then takes the `apply_scope` rows of every instance with
>    a live placement on the target in `instance_id` order, then the record (`FOR NO KEY UPDATE`, so a placement insert is not
>    blocked), refuses while work is in flight, writes, re-pins the host keys and appends the event. Under the lock the edit is
>    applied only to the very (host, port, pin) that was scanned; a record that moved since is `409`, retry. Stated residual: the
>    scope locks cover the instances with a live placement **when they are enumerated**; an `addPlacement` of an instance not
>    previously on the target that commits in the same instant is not waited for, and its first apply would meet the new host whole
>    or the old one whole (the apply path reads the record fresh per operation), never a mixture.
> 3. **Every change of a connection record leaves its event**, so the operator command's `rotateCredential` now appends
>    `credential_rotated` too (origin `operator-cli`), as its registration already appends `registered`.
> 4. **Measured, and not fixed here** *(corrected and fixed on 2026-10-08; see the second rider below)*: sshj 0.40.0's default
>    configuration verifies ONLY the host key it negotiates (which type that is, is decided by the CLIENT's order, Ed25519 then
>    ECDSA then RSA, and not by the server: the server only has to offer it), so a target pinned to the host's `ecdsa-sha2-nistp256` or `ssh-rsa` key is `hostKeyPinned=false`
>    in the connection test (measured against a throwaway system `sshd`, plan 013 T1.5 Results). The other sshj connectors (apply,
>    health, logs) are built the same way, a `FingerprintVerifier` on a default `SSHClient`, so they would meet the same refusal;
>    that was not measured. The scan reports
>    all three types (OR-89) and the registration accepts any one as the pin. The honest fix is to restrict the client's host-key
>    algorithms to the pinned key's type in the connectors, or to refuse a pin the transport would not negotiate at registration;
>    both touch files outside T1.5 and are the owner's to rule. T6 reports the truth meanwhile.

> **Second rider, 2026-10-08 (plan 013 T1.5, wave 4 review fix round; adds, changes no decision; items 2 and 3 are flagged for
> the owner).**
> 1. **A pin may be to any host-key type the host offers, and every connector now finds which (`W4C-1`).** The first rider's item 4
>    attributed the choice of key type to the server ("OpenSSH offers `ssh-ed25519` first"); it is the CLIENT's order that decides
>    (sshj's `DefaultConfig` lists Ed25519, ECDSA, RSA; `Proposal.negotiate` takes the first of the client's entries the server also
>    offers; `FingerprintVerifier.findExistingAlgorithms` is empty, so nothing moves the pinned key's type forward). It is also
>    more general than ed25519 against the rest: a host with only ECDSA and RSA keys would accept only the ECDSA pin. Confirmed on
>    every connector, not only the probe. The fix with no migration (none is allowed in wave 4) is one shared helper,
>    `PinnedHostKeyConnect`, used by the per-operation transport, the pooled health connector and the connection test's probe:
>    it connects preferring one key family at a time (Ed25519, ECDSA, RSA) until the pinned fingerprint verifies, and remembers
>    the family per fingerprint in memory (a fingerprint belongs to one key for good). The family LEADS sshj's list instead of
>    restricting it, because a list restricted to ECDSA could not sign with an Ed25519 user key (measured). At most three
>    connections, usually one; an unreachable host stops at once. **Cost to know:** a pin that verifies for no family (a host whose
>    key changed, or a man in the middle) now costs up to three pre-authentication connections per attempt instead of one, and
>    OpenSSH 9.8 and later (`PerSourcePenalties`) penalises a source for each one; one attempt per health poll does not reach the
>    default threshold, but it is the reason this is an interim.
>    **Preferred long-term fix, for the owner: persist the pinned key's algorithm** beside the fingerprint (it is in hand at
>    registration and at a re-pin as `OfferedHostKey.algorithm`), so no discovery is needed and a pin is verified with exactly one
>    connection. That is a new `sm_data` column, so a migration number (OR-22); **the agent gateway was planned to start at
>    `sm_data` V026**, so the number must be taken from the allocation, not assumed.
>    **Operational risk of a registration, inferred from documented defaults and not measured:** one registration can make up to
>    about 16 pre-authentication disconnects from SM's address within seconds (`scanHostKeys` up to 7 attempts, `registerTarget`'s
>    pre-check scan up to 7, cold key-family discovery up to 2). OpenSSH 9.8 and later enables `PerSourcePenalties` by default
>    (`noauth` 1s for a client that disconnects without attempting authentication, `authfail` 5s, `min` 15s before enforcement
>    begins, `max` 10m; `man.openbsd.org/sshd_config`, read 2026-10-08), so 16 such disconnects can reach the 15s threshold and
>    the host may refuse SM's address for up to 10 minutes, which breaks apply, health and logs on that host. Mitigation (host
>    configuration, allowed by `I1`): add SM's address to the host's sshd `PerSourcePenaltyExemptList`. `OR-125` (2), persisting
>    the pinned key's algorithm, removes the discovery connections; reusing the pre-check scan's result would remove one scan.
>    See the runbook's target registration section.
> 2. **The connection test is bounded like the probe it is (`W4C-4`; flagged).** The first rider's item 1 said it was not
>    rate-limited. It opens an SSH connection and runs a command on the request thread for up to 25 s, so a `target.use` holder
>    looping on it could hold gateway threads and burst SSH logins at the host until `MaxStartups` or fail2ban locked `sm` out
>    (which breaks apply and health for every instance there). Conservative interim: it draws on the SAME per-session budget as a
>    scan, and one test of a target runs at a time (a second is `429`, not queued); either refusal is a `target.use` `DENY` row
>    with the reason `rate_limited`. **The owner rules, with `W3C-2`,** whether tests should have a budget of their own and whether
>    the bound should also be per principal or global (the bucket is per session, so several sessions of one principal each have one).
> 3. **The edit's scan is accounted by the data plane's decision (`W4C-3`).** The second item of the first rider ("planned, then
>    applied ... so the gateway can charge a scan it is about to cause") is replaced: the plan is kept for the `404` and the
>    grammar refusal only. The data plane tells the gateway when it is about to scan (`ScanAccounting.beforeScan`, which may refuse:
>    the `429`) and when the host answered, and the gateway charges before and audits once in a `finally`, so a record that moves
>    between the plan and the write cannot leave a scan with no token or no row, or a row for a scan that did not happen.
> 4. **Locks (`W4C-2`).** The first rider's item 2 said the record is locked `FOR NO KEY UPDATE` "so a placement insert is not
>    blocked": V016's `BEFORE INSERT` trigger on `placement` locks the target `FOR UPDATE`, so a placement insert and an edit or a
>    rotation wait for each other (no cycle: the edit holds the `apply_scope` rows first). More importantly, a retirement took no
>    lock the placement writers waited for in the other direction, and V007's guard cannot see an uncommitted placement:
>    reproduced on PostgreSQL 16, a target was retired with a live placement. Now `addPlacement` and `reserve` re-read the
>    record `FOR UPDATE` under their other locks and refuse a retired target (this is what closes it: the placement used to wait
>    for the retirement and then go on without looking again), and `retireConnection` reads it `FOR UPDATE` first (defence in
>    depth: a plain `UPDATE` was measured safe in both directions once the placement side re-reads). `FOR KEY SHARE`
>    (the obvious choice) was measured to deadlock two placements on one target against V016's `FOR UPDATE`, and is not used.

> **Third rider, 2026-10-08 (`OR-125` (2), wave 5; adds, changes no decision).** The "preferred long-term fix" of the second rider's
> item 1 is built. `sm_data` V027 adds `target_connection.host_key_algorithm` (the pinned key's TYPE: `ssh-ed25519`,
> `ecdsa-sha2-nistp256`/`384`/`521`, `ssh-rsa`, held by a CHECK), written **in the same statement as the pin it describes**:
> by `registerTarget` and by an `editTargetConnection` that re-pins, from the data plane's own fresh scan
> (`OfferedHostKey.algorithm` of the key whose fingerprint is the pin; never client-supplied), and cleared to `NULL` when a re-pin
> is to a key of a type the data plane does not pin by, so a stale type never outlives its pin. Every SSH connector
> (`SshjDockerTransport`, `SshjHostConnector`, `SshjTargetSshProbe`, all through `PinnedHostKeyConnect`) then negotiates THAT type
> directly: **one attempt**, the pinned type first, and no fallback, because a fingerprint is a hash of one key and no other
> type can verify it. **Back-fill of existing rows is not derivable without a scan** (a SHA-256 fingerprint cannot be inverted to
> its key type, and no column holds the type), so the column is nullable and `NULL` means "type not known": a connector discovers
> as before ONLY while it is `NULL`, and on its first success records the exact type of the key that matched the pin (a
> conditional `UPDATE ... WHERE host_key_algorithm IS NULL`, scoped to the tenant and the pin, so a recorded type is never
> overwritten by a guess); a replay of the registration back-fills it from its fresh scan. The operator command scans
> fingerprints only, so a target it registers starts `NULL`. The cost the second rider named (up to three pre-authentication
> connections for a pin that verifies for no family, and `PerSourcePenalties`) is now confined to a target whose type is not yet
> recorded, and a pin that fails with a recorded type costs exactly one.

### 5.2 Entitlements

| # | Method and path | operationId | Body → response |
|---|---|---|---|
| E1 | `GET /cp/entitlements` | `listEntitlements` | → `200 {items: [{solution, maxInstances, declaredInstances, setAt}]}` for the session's organization |
| E2 | `PUT /cp/entitlements/{solution}` | `setEntitlement` | `{maxInstances}` → `200 Entitlement`. `409 ENTITLEMENT_REFUSED` when `maxInstances` is below `declaredInstances`. This is the code today's quota refusals already use (`DeclareRegisterTimeQuotaIT`, `CatalogEndpointsIT`), so no new code is needed. Lowering below use is refused, not silently accepted. Idempotent by nature. |

**What `declaredInstances` counts.** It is `JdbcGovernanceRepository.countInstances`. At `36af1df6` that counts
**every** `sm_control.instance` row of the solution: the CP never retires an instance today, so the number means
"ever declared", not "live". Under that count, E2's refusal could block lowering the quota for good once abandoned
declares accumulate. Plan 012 T4 adds `instance.retired_at` and makes `countInstances` exclude retired instances. E1
and E2 are built **on that count** (sequencing, preamble), and the field is named `declaredInstances` rather than
"live" because abandoned declares still count until plan 012's undeclare retires them. The operator command
`EntitlementMain` does **not** refuse lowering below use. That asymmetry is recorded, and OQ-4 asks whether the API
should match the command instead.

No `DELETE`: an entitlement is lowered, never removed (a removed row would make `package.load` refuse a solution
that has running instances).

### 5.3 The scope tree

| # | Method and path | operationId | Body → response |
|---|---|---|---|
| H1 | `POST /cp/hierarchy/organizations/{organizationId}/sub-orgs` | `createSubOrganization` | `{name}` → `201 ScopeNode` · `200` identical replay (same name under the same parent) |
| H2 | `POST /cp/hierarchy/organizations/{organizationId}/sub-orgs/{subOrganizationId}/environments` | `createEnvironment` | `{name}` → `201 ScopeNode` · `200` replay |
| H3 | `PATCH /cp/hierarchy/organizations/{organizationId}` | `renameOrganization` | `{name}` → `200 ScopeNode` |
| H4 | `PATCH …/sub-orgs/{subOrganizationId}` | `renameSubOrganization` | `{name}` → `200 ScopeNode` · `409` name taken under the parent |
| H5 | `PATCH …/environments/{environmentId}` | `renameEnvironment` | as H4 |

The `{organizationId}` path segment must equal the session's organization (the existing hierarchy reads already
enforce this, `ScopeVisibleHierarchyDirectory.requireOwnOrganization`); it is an address, never authority (`I5`).
Sub-organizations are created **top-level only** (`parent_unit_id NULL`): `SM-D32`/`OR-16` fix the depth, and the
nested column stays unused. **No `scope` row is inserted by the create.** Scope rows are minted lazily by
`ScopeStore.canonicalize` (`JdbcScopeStore`: `INSERT … ON CONFLICT DO NOTHING`), which the target and declare paths
already use. V007 seeds no organization scope row, yet targets are bound there today. U6 canonicalizes its granted
scope the same way.

### 5.4 Users and role bindings (conditional on OQ-6)

| # | Method and path | operationId | Body → response |
|---|---|---|---|
| U1 | `GET /cp/admin/users` | `listUsers` | → `200 {items: [{id, username, source, status, mustRotate, createdAt}]}` (session's organization) |
| U2 | `POST /cp/admin/users` | `createUser` | `{username, initialCredential}` → `201 User`. The user is `local`, in the session's organization, `must_rotate = true`. `409` username taken (install-wide unique, `principal_username_unique`). |
| U3 | `PATCH /cp/admin/users/{id}` | `editUser` | `{status: deactivated}` (`user.deactivate`) **or** `{resetCredential}` (`user.edit`, sets `must_rotate`); both in one body is `400`. Reactivation is not offered (no verb says it; console-integration row 23 left it out). **A principal that holds any Administrator binding cannot be deactivated or have its credential reset over the API (`409`)**, and neither can the caller itself. This mirrors U6 and U7: Administrator standing is made and unmade only outside the API (`OR-12`). One administrator therefore cannot lock out another, and the last install-scope administrator cannot be removed. |
| U4 | `GET /cp/admin/roles` | `listRoles` | → the three built-ins with their verbs |
| U5 | `GET /cp/admin/assignments` | `listAssignments` | `?principalId=` → `200 {items: [{assignmentId, principalId, roleId, scope}]}`, filtered by `role.view` reach |
| U6 | `POST /cp/admin/assignments` | `grantAssignment` | `{principalId, roleId, scope}` → `201` · `200` replay (`role_binding_unique`). `role.assign` held **at `scope`**. ⛔ `roleId` = Administrator is refused `422` (`OR-12`: bootstrap/emergency only; OQ-6 asks). |
| U7 | `DELETE /cp/admin/assignments/{assignmentId}` | `revokeAssignment` | → `204`. `role.assign` at the assignment's own scope. **An Administrator binding is never revoked over the API (`409`)**, whoever holds it. This mirrors U6, which never grants one. Under the earlier "not one's own last" rule, a second Administrator could revoke `bootstrap-admin`'s install binding and leave no install-scope principal, after which `entitlement.set` would need SQL. OQ-6 (a″) sets out the narrower guard. |

> **Rider, 2026-10-08 (plan 013 T3.2, `OR-125` (4) and (6); amends U2's username scope, adds nothing else; the U2 text above is
> unedited, `I3`).** A username is unique **per organization**, compared without regard to case: `sm_control` `V019` drops
> `principal_username_unique` and creates `principal_org_username_ci_uq` on `(organization_id, lower(username))`, the case rule
> `V015` already applies to node names. U2's `409` therefore means "a user of THIS organization already has it" and never reveals
> another organization's user (the oracle review `W3D-1` recorded is closed, and the "reserve a name another can never use"
> half with it). **Login carries the organization** (`console.yaml` `login` body: `organization`, an id or a name compared
> without regard to case; an unknown one is refused exactly as a wrong credential is, `401`, the same work and the same
> throttle and audit record). The login throttle's username dimension is keyed by `(resolved organization id, lowercased
> username)`, an organization that names nothing keeps a counter of its own, and the operator commands that name a username
> (`SmControlLockoutRecoveryMain`, `TargetRegistrationMain`) take the organization too. `U3` is two operations
> (`deactivateUser`, `resetUserCredential`), so the users surface is eight operations with the role routes, as the contract draft
> found. `GET /admin/assignments` is evaluated at the organization (`role.view`), stricter than the service's
> "held anywhere in the organization"; the two agree for every principal v1 can create.

`initialCredential` and `resetCredential` travel exactly as `rotateOwnCredential`'s credentials do (redacted
`toString`, Argon2id `OR-7`, the same 12-character floor); the plaintext is never stored or returned. They are
subject to §6.1's transport rule (OQ-14).

## 6. The private-key path (`I6`, `OR-30`)

### 6.1 Wire

`privateKey` is a JSON string holding the key file's text (OpenSSH `-----BEGIN OPENSSH PRIVATE KEY-----` or PEM). The
browser reads the file client-side; there is no multipart upload and no server-side file. In `console.yaml` it is
`writeOnly: true`, `maxLength: 16384` (it mirrors `EnvelopeSecretStore.MAX_VALUE_BYTES` and the
`secret_ciphertext_length` CHECK).

**Transport (corrected; OQ-14).** The earlier draft said plain HTTP was "already impossible". That is true only for
browsers. `OR-21`'s `__Host-` cookies constrain what a browser stores and sends, and `OR-21` itself records that
`http://localhost` still works. A non-browser client sends the cookie over whatever scheme it is given: Postman (the
frontend team's sanctioned client, `OR-112` (4)) and the `OR-118` local stack on `:8080` both do. So does a
misconfigured TLS-terminating proxy that forwards plain HTTP across a network. Any of those can carry a private key
unencrypted. **Proposed (OQ-14 (b)):** T2, T5, U2 and U3 refuse a request (`403 INSECURE_TRANSPORT`, fixed text,
**before the body is read**) unless one of these holds:

- `request.isSecure()`;
- the socket peer is a configured `sm.gateway.trusted-proxies` entry and `X-Forwarded-Proto` is `https` (the same
  trust rule `ClientAddressResolver` applies to `X-Forwarded-For`, `OR-20`);
- the socket peer is loopback, which covers the local stack.

> **Rider, 2026-10-08 (wave W3 review `W3C-1`; adds, changes no decision).** `isSecure()` and the socket peer are only as
> trustworthy as the servlet container's own forwarded-header handling. Measured from the Spring Boot 3.5.16 jars: with
> `server.forward-headers-strategy` unset, Boot adds Tomcat's `RemoteIpValve` whenever it detects a cloud platform
> (Kubernetes, Cloud Foundry, Heroku, Azure App Service, Nomad), and that valve believes `X-Forwarded-Proto` and
> `X-Forwarded-For` from every private-range peer; reproduced (a LAN peer's spoofed headers reached a credential-bearing
> handler). The launcher therefore pins `server.forward-headers-strategy=none` (and refuses to boot on `native`,
> `framework`, an unset value, or either `server.tomcat.remoteip.*` header property), so the gateway's own
> `trusted-proxies` rule is the only forwarded-header logic. A listed proxy must also **overwrite** `X-Forwarded-Proto`
> with its own listener's scheme (`W3C-9`). This is the same exposure `OR-20`'s `ClientAddressResolver` had.

### 6.2 In the gateway

The request records (`RegisterTargetRequest`, `RotateTargetCredentialRequest`) follow `SetConfigSecretRequest`
exactly: a redacting `toString`, `@JsonAnySetter` refusing unknown keys, fixed refusal texts. The controller turns
the string into a `char[]`, passes it in-process to `dp-api` (`OR-10`), and zeroises the array in a `finally`.
⚠️ **Accepted residual, stated rather than discovered:** Jackson materialises the JSON value as a `java.lang.String`,
which the JDK cannot zero — the same residual `setConfigSecret` and `rotateOwnCredential` already carry, and the
same one `SshjDockerTransport.loadKey` documents (M8: sshj needs a `String`). No request-body logging exists in the
gateway; plan task T1.4 adds a log-capture test that registers a key and asserts no byte of it appears in any
appender, and an ArchUnit rule that the request records' `toString` is overridden.

### 6.3 In the data plane

1. **Parse before store.** `dp-core` opens the key with the same loader the transport uses (sshj `loadKeys`) and
   refuses anything it cannot open as an **unencrypted** private key with `422 CREDENTIAL_INVALID` and fixed text.
   The parser's own message is dropped (it can quote key text — `loadKey`'s existing rule). A passphrase-protected key
   is refused: the transport passes no `PasswordFinder` (`loadKeys(…, null, null)`), so such a key could never be
   used. The OpenSSH format marks an unencrypted key with cipher and KDF `"none"` (verified 2026-10-07 against
   `openssh-portable/PROTOCOL.key`); whether sshj 0.40.0 refuses an encrypted one with a null finder, and how, is
   **MF-R1** (measured in plan 013, not assumed).
2. **Derive the public fingerprint** from the parsed key pair (`SshHostKeyScanner.fingerprintOf`, the same
   `SHA256:<base64, no padding>` spelling `ssh-keygen -l` prints with its default `-E sha256`, verified 2026-10-07
   at `man.openbsd.org/ssh-keygen`) and store it beside the reference (§10.2).
3. **Seal** through `EnvelopeSecretStore.put(tenant, SSH_PRIVATE_KEY, char[], actor)` — `BE-D28`'s envelope, AAD
   binding tenant, secret id and purpose. The master key's canary has already verified at boot.
4. **Reference only.** `target_connection.credential_ref` holds the secret id; no other table, response, log, event,
   audit row or hash ever holds the key or anything derived from its private half.

### 6.4 Rotation (T5)

`InventoryService.rotateCredential` (exists) stores a **new** secret row and repoints the record in one transaction;
the old row stays (`OR-85` OQ-4, re-wrapped on every master-key rotation, purged only by the owner). Before it, the
compare of §8 runs: the same key again writes nothing.

**What already makes the rotation take effect in SM.** The earlier draft proposed an eviction method. The tree
already does what that method was for:

- **The apply path never pools.** `SshjDockerTransport` opens a connection per operation and reads the connection
  record fresh each time (`OR-28`). The next operation authenticates with the new key.
- **The pool is identity-bound.** `SshSessionPool` keys a session by `(tenant, host, port, user)` and binds it to
  `Identity(credentialRef, pinnedHostKeyFingerprint)`. On the next lease after a rotation or a re-pin, a session
  opened under the old identity is closed and replaced (`lease()`; the class comment, *"A session opened with a
  different credential or a different pin is closed and replaced"*). The pool has no `targetId` key, so an
  `evict(tenant, targetId)` would have had nothing to evict by. **No pool change is designed.**

**Residuals, stated:**

- An idle pooled session under the old key stays open until its next lease or `evictIdle`. It authenticated before
  the rotation, and closing it would not revoke the key.
- An operation already holding a connection finishes on it. An apply's **later** operations authenticate with the new
  key, so if the host does not yet accept the new key they fail authentication and the engine's existing failure
  path handles them. That is why the administrator installs the new public key on the host **before** calling T5
  (runbook).

**The real S3 mitigation is on the host.** SM stopping use of the old key does not stop anyone else using it. The
leaked key stops working only when its public half is removed from the host's `authorized_keys`, which is the
operator's step on each host. The runbook (plan T4.2) states the order:

1. Add the new public key to `authorized_keys`.
2. Call T5.
3. Confirm with T6 and `CredentialSummary.publicKeyFingerprint`.
4. Remove the old key from `authorized_keys`.

SM cannot do step 4 for the operator without editing a host file outside the placement envelope, which `OR-52`
fences.

## 7. Host keys: scan, pin, re-pin (`OR-89`)

### 7.1 What a scan is

T1 runs the existing `SshHostKeyScanner` in the DP: one connection per key algorithm, each aborted by the verifier
the moment the host presents its key, so **no authentication is attempted and no credential is used**. The result is
the set of offered fingerprints, untrusted. OpenSSH's own manual is explicit about what such a set is worth:
*"ssh-keyscan cannot verify the authenticity of the host keys it obtains, and an attacker on the network with the
ability to intercept traffic could substitute their own host keys"* (fetched 2026-10-07, `man.openbsd.org/ssh-keyscan`).
That is why a scan is never a pin.

### 7.2 Pinning (the proposed rule; OQ-2 sets out the alternatives)

- **The pin is always an explicit field** (`hostKeyFingerprint` in T2/T4). The server never chooses it, never defaults
  it, and never pins "whatever the host presented".
- **At registration and at every re-pin the DP scans again** and refuses a pin the host does not offer
  (`422 HOST_KEY_NOT_OFFERED`). The scan's full set becomes `offeredHostKeys` (`OR-89`); any of them already held by
  another live target of the organization refuses the registration (`target_host_key_once`, unchanged).
- **The console's job** (frontend, recorded for them): show the scanned fingerprints and tell the user to compare one
  with `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` run **on the host**, or with what their infrastructure
  records, before confirming. This is trust-on-first-use **with a human confirmation**; it is only as strong as that
  comparison, which is also true of today's command (`TargetConnectionMain` pins whatever fingerprint the operator
  types, after scanning from the operator's own machine).
- **An unreachable host cannot be registered through the API** (`503 SERVICE_UNAVAILABLE`, "retry"). The command's
  `SM_TARGET_HOST_KEY_FINGERPRINTS` escape hatch exists because the operator's machine may not reach the host; the DP
  must reach it anyway to manage it, so the API has no such hatch.
- **Re-pin (S2).** T4 with a new `hostKeyFingerprint` (and usually a new `host`) does five things in one DP
  transaction:
  1. Re-scans.
  2. Requires the new pin to be offered.
  3. Records the newly offered set.
  4. **Retires the target's `target_host_key` rows that the fresh scan no longer offers.**
  5. Un-retires (`SET retired_at = NULL`) any of the target's own retired rows that the scan offers again.

  Steps 4 and 5 rest on facts in the tree. V012 already grants `UPDATE (retired_at) ON sm_data.target_host_key` to
  `sm_dp_runtime`, so retiring a single key needs no new grant. (The earlier draft said it did; that was wrong.) The
  primary key `(tenant_id, target_id, fingerprint)` makes the V012 pinned-key trigger's `ON CONFLICT … DO NOTHING`
  silently keep a retired row, so a re-offered key must be revived explicitly, or `target_host_key_once` would stop
  covering it. Without step 4, a machine that was **moved** rather than re-imaged would keep its old keys reserved,
  and could not be registered again in the organization. Pooled sessions need no eviction (§6.4: the pool's
  `Identity` includes the pin).
- **No edit while work is in flight (S2′, OQ-12).** Every transport operation re-reads `target_connection` fresh
  (`OR-28`), so a `host`/`port`/pin change landing in the middle of a journaled apply would split it. The apply could
  write the config file on the old host and restart, or read back, on the new one, and a rollback could read the
  wrong host's pre-image. **Proposed:** T4 changes to `host`, `port` or `hostKeyFingerprint` go through three steps:
  1. Take the **admission lock** the engine itself serialises on: `SELECT … FOR UPDATE` of the `sm_data.apply_scope`
     row of every instance with a live placement on the target, in `instance_id` order, so two editors cannot
     deadlock.
  2. Refuse `409 CONFLICT` (*"work is in flight on this target"*) if either of these names one of those placements:
     an `apply_request` with `terminal_at IS NULL` (`REQUESTED`, `RUNNING` or `APPLY_INDETERMINATE`, V002), or a
     `fleet_member` with disposition `pending` in a `RUNNING` `fleet_action` (V014). A fleet's admitted members are
     covered by their apply requests.
  3. Commit the edit under the lock.

  Admission takes the same lock, so nothing can be admitted between the check and the commit. `sshUser`-only edits
  and T5 (rotation) are **not** refused: they do not change which machine an operation reaches. Health and metrics
  polls are not "work in flight". They read fresh each round and simply see the new host on the next one.

### 7.3 The address the DP will connect to (S4)

T1, T2, T4 and T6 make the DP open TCP connections to a caller-named `host:port`. The verb is Administrator-only and
audited, and the connection is an SSH handshake, not an HTTP request — but the same registered `host` is also the
address `direct-http` health and metrics poll (`OR-82`), so an unconstrained `host` reopens the surface `OR-82` closed
(link-local metadata, loopback, the DP's own services).

**Proposed (OQ-3 (d), revised after review):** one `TargetAddressPolicy` in `dp-core`, applied at two points.

- **At registration and edit** (T1, T2, T4, T6): resolve the name, and refuse (`422 TARGET_ADDRESS_REFUSED`) if
  **any** resolved address is loopback, link-local, unspecified or multicast (IPv4 and IPv6), or falls outside the
  optional operator allow-list `sm.dp.targets.allowed-networks` (CIDR list; empty means no extra restriction). Where
  the client supports it, connect to the checked address (MF-R4 for sshj).
- **At every later use**: every DP connector that reaches a target calls the same policy on the stored `host`
  immediately before it connects (`SshjDockerTransport`, `SshjHostConnector` for the pool, `DirectHttpProbe`,
  `DirectHttpFetcher`). A refusal there is a connection failure with a fixed classifier, never a connection.

The second point is the revision. The stored value is a **name**: the `target_connection` host CHECK admits
hostnames, and apply, health and the `direct-http` fetcher (`OR-98`: read fresh each round) re-resolve it on every
use. A check made only at registration is undone by a DNS change made afterwards, and the earlier draft's comparison
overstated what that check closed. Check-at-use connects **by name**, so its residual is the window between the
check and the connect (an attacker who controls the name's DNS and flips it in that window). Pinning the address
narrows the window where the client supports it, and an IP-literal registration closes it entirely.

The four built-in refusals are not an exhaustive list of dangerous addresses: a metadata endpoint hosted on a CGNAT (`100.64.0.0/10`) or unique-local (`fc00::/7`) address is not refused, because customer hosts live in those ranges, and excluding such an endpoint is the operator allow-list's job. No cloud provider's metadata address beyond `169.254.169.254` is listed here: those addresses are UNVERIFIED (`I2`).

**The test seam (R7).** Testcontainers maps fixture ports on loopback or the Docker host gateway, which the policy
refuses. The seam is therefore a **bean, not a property**. `TargetAddressPolicy` is an interface. Production
configuration wires the strict implementation and nothing else. The permissive implementation lives **only in test
sources** and is wired by the ITs' `@TestConfiguration`. An ArchUnit rule asserts that no main-source class
implements the interface other than the strict one. No production setting can relax the rule, so it cannot be
misconfigured in production.

T1 is also rate-limited per session (the existing `MetricsRateGate` pattern: a token bucket, `429 RATE_LIMITED`),
default 10 scans a minute, because each scan opens up to seven connections.

> **Rider, 2026-10-08 (wave W3 review `W3C-2`, `W3C-3`; extends T1's controls to T2's pre-check, flagged for the owner).**
> T2's pre-check opens the same up-to-seven connections to a caller-named host, so leaving it outside T1's controls made
> T2 a way round them. As built, `registerTarget` draws on the **same** per-session budget (`429 RATE_LIMITED`, nothing
> scanned or written) and records the same `TargetProbeAudit` row for the pre-check's scan (`completed`, `unreachable`,
> `address_refused`, or `rate_limited` as a `DENY`; never a host or a key). A refusal before any host is contacted (the key
> does not open, a field fails its grammar) is not a scan and is not recorded. §8's "any refusal here leaves nothing in
> either plane" is unchanged: the audit row is the control plane's record that a probe ran, not registration state. Separately,
> "a scan is bounded to 10 s" was true only between attempts (sshj's timeouts are per read); each attempt is now a hard
> deadline (a watchdog closes its socket).

## 8. Idempotency and the two-plane write (S5)

**Order (revised after review: authorize, then check everything, then write).** The earlier draft wrote the CP
governance row first. Any DP-side refusal after that commit left an orphaned governance row: `CREDENTIAL_INVALID`,
`HOST_KEY_NOT_OFFERED`, an unreachable host, an address refusal, or a host key held by another target. Its name was
then taken for good, because `target_name_once` is `UNIQUE (organization_id, name)` with no `retired_at` predicate
(V013). A retry that corrected `kind` or `ownershipScope` met `409`, and every `target.view` holder saw a degraded
row. T2 now runs five steps:

1. **Authorize, writing nothing.** The gateway's `AuthenticationInterceptor` already calls
   `AuthorizationService.require(principal, catalog permission, resource)` (a `cp-api` port) before every
   controller, and that call writes nothing. T2 needs only a new `ResourceResolver` entry that derives the resource
   from the body's `ownershipScope` (plan T1.4), not a new authorize-only port. A refusal is `403`/`404`, and nothing
   reaches the DP.
2. **DP pre-checks, read-only, no secret stored.** A new `dp-api` call `precheckRegistration(tenant, spec, char[]
   key)` performs, in this order:
   - the field grammars;
   - the key parse (`CREDENTIAL_INVALID`) and its public fingerprint;
   - `TargetAddressPolicy` (`TARGET_ADDRESS_REFUSED`);
   - the scan, with the pin required to be offered (`HOST_KEY_NOT_OFFERED`, or `503` if the host is unreachable);
   - a host-key uniqueness probe that reads `target_host_key` live rows for every offered fingerprint (`409`, *"a
     host key of this machine is registered to another target"*).

   It returns the offered set and the public fingerprint. Any refusal here leaves **nothing** in either plane.
3. **CP insert** (`TargetRegistration.register`, which re-runs `require` and is idempotent on `(organization,
   name)`).
4. **DP insert** of the connection record, the sealed key and the offered set, with the CP-minted `targetId`. The
   unique indexes are the final arbiter.
5. Respond.

No transaction spans both planes (`I7`). The only remaining ways to stop between steps 3 and 4 are a crash, a
timeout, or a **race**, in which another registration takes one of this host's keys between step 2's probe and step
4. The race answers `409`, and the CP row it leaves is degraded and retirable (T7's CP-only path). OQ-11 decides
whether retiring it frees the name.

| Failure point | What the store holds | What the next identical `POST` does |
|---|---|---|
| step 1 or 2 refuses | nothing | the same refusal, or `201` once the cause is fixed |
| after CP, before DP commit (crash or timeout) | CP row only; `listTargets` lists it **degraded** ("no connection record", `TargetController`) | CP finds the same `(organization, name)` with the same kind/transport/scope → returns it; DP has no row → pre-checks and inserts; `201` |
| after DP commit, response lost | both | CP returns existing; DP finds an existing live row for `targetId` with an identical record → compares the key (below) → `200`, nothing written |
| two identical `POST`s at once (double-submit) | — | both pass step 2; one wins each insert and the other hits a unique violation (`target_name_once` in the CP, the primary key in the DP). **The violation is caught and the request re-runs the replay path**, so the loser answers `200`, not `500` or `409`. A DP-side loser opens no second secret row: the insert and the seal are one transaction, which rolls back. |

**Comparing the key on replay, without exposing it (revised).** `TargetConnectionService.register` today treats an
identical record as a replay **without comparing the key**; `TargetConnectionMain` prints *"credential unchanged"*.
Over HTTP that would silently drop the user's new key (S5's "different key file"). The earlier draft compared the
decrypted key's UTF-8 text. That would misread a re-export of the same key (PEM vs OpenSSH, CRLF vs LF, a trailing
newline) as a different key. **Proposed:** compare **public-key fingerprints**. Step 2 has already derived the
candidate's, and `target_connection.credential_public_fingerprint` (§10.2) holds the stored one. Same means `200` and
nothing written. Different means `409 CONFLICT`, *"this target is registered with a different key; rotate it
instead"*. No secret is opened on the normal path.

For a **legacy row** whose fingerprint is `NULL` (registered by the command before §10.2), `dp-core` opens the stored
key in memory, derives its public fingerprint with the same `PrivateKeyInspector`, compares, and back-fills the
column in the same transaction. The plaintext is closed in a `finally`. This follows `OR-104` V-4's rule (the stored
value never leaves `dp-core`; only same or differs is reported) without reusing the `SecretCompare` port, which
resolves `CONFIG_FIELD` only and compares text. The same compare makes T5 idempotent and stops a double-click from
minting a second permanent secret row (`OR-85`).

A differing **record** (other host, user, pin or envelope) under the same name is `409`, as the commands do today.
A request that names a `targetId` is not accepted over HTTP (the command's `SM_TARGET_ID` exists for scripting);
the CP mints it.

**Other operations:** H1/H2 replay on `(parent, name)`; E2 is a `PUT`; U2 is unique on username (`409`); U6 replays on
`role_binding_unique` (`200`); T7 on an already-retired target is `200`; T4 with no effective change writes nothing.
No `Idempotency-Key` header is introduced: every operation here has a natural key.

## 9. Audit

| Where the write lands | How it is audited |
|---|---|
| `sm_control` (target row, retire, entitlement, hierarchy rows and names, scope rows, principal, role binding) | The existing `OR-19` trigger, in the same transaction, failing closed (`OR-25`). New operations join the allow-list in one `CREATE OR REPLACE` of `record_change_audit()`: `HIERARCHY_NODE_CREATED`, `HIERARCHY_NODE_RENAMED`, and only if OQ-6 admits W3: `PRINCIPAL_CREATED`, `PRINCIPAL_DEACTIVATED`, `PRINCIPAL_CREDENTIAL_RESET`, `ROLE_BINDING_GRANTED`, `ROLE_BINDING_REVOKED`. The trigger's row keys for `organization`, `organization_unit`, `environment`, `principal` and `role_binding` **already exist** (V013's `CASE TG_TABLE_NAME`), so only operation names are new. `TARGET_REGISTERED`, `TARGET_RETIRED` and `ENTITLEMENT_SET` already exist. Over HTTP, `ENTITLEMENT_SET` carries an actor. ⚠️ The trigger's `NULL`-actor exemption is keyed on the operation and table only (V013: `ENTITLEMENT_SET` on `entitlement`), so "a `NULL` actor is for the command only" is enforced by the **code**, not the database. §10.1 narrows it to `client_address = 'operator-cli'` (the command's `OPERATOR_CLIENT_ADDRESS`) so the database enforces it too. The readiness probe that checks the function body (`ControlPlanePersistenceConfiguration`, `prosrc LIKE …`) is extended to the new names. `credential_hash` is never copied (`OR-19`'s secret-column rule, unchanged). |
| `sm_data` / `sm_secret` (connection insert, edit, re-pin, key rotation, retire) | ⚠️ **The DP has no change-audit today**; `target_connection` carries only `created_by`. Proposed: an append-only `sm_data.target_connection_event` written **in the same DP transaction** as the change: `(tenant_id, target_id, event_id, kind ∈ {registered, edited, repinned, credential_rotated, retired}, actor, correlation_id, client_address, changed_fields text[], public_key_fingerprint, recorded_at)`. The runtime role gets `SELECT, INSERT` only. ⛔ It never records a key, a `credential_ref`, or old/new host values beyond the field names (the host is not a secret, but this table is not a second copy of the record). |
| Scans (T1) and tests (T6) | They change nothing, so no change-audit row. Each is recorded in `sm_control.audit_log` through a **new `cp-api` port, `TargetProbeAudit`**, implemented in `cp-core` over `AuditLog`. `AuditLog` lives in `cp-core` (`cp/audit/AuditLog.java`) and the gateway depends only on `cp-api` (`OR-10`), so it cannot call `AuditLog` directly. The precedent is `ExportAudit` (`cp-api`) with `ExportAuditConfiguration` (`cp-core`). It records the verb, resource scope and outcome, never the offered keys. |

## 10. Data model changes

### 10.1 `sm_control` (next free version at merge; `V015` at `36af1df6`, after plan 012's two)

- `name text` on `organization`, `organization_unit`, `environment`: added nullable, back-filled with **the id's own
  text** (exactly what `placeholderNamed` shows today, so nothing visibly changes), then `NOT NULL`, `CHECK (length(name)
  BETWEEN 1 AND 128)`; unique per parent (`(organization_id, name)` for units, `(organization_id, organization_unit_id,
  name)` for environments). `ScopeNode.placeholderNamed` and its warning are retired, and the hierarchy reads return
  real names. ⚠️ **`OR-116`'s `Identity.organizations` is not in the tree at `36af1df6`.** The gateway `Identity` has
  no such field. It exists only on the unmerged branch `p/or116` (`aca8d07d`), which also edits `HierarchyDirectory`
  and `ScopeVisibleHierarchyDirectory`, the files W2 changes. **Merge order: `p/or116` before plan 013's W2.** W2 then
  makes `VisibleOrganization` names real in the same change. If `p/or116` has not merged, W2 changes only the
  hierarchy reads, and the conflict in `ScopeVisibleHierarchyDirectory` is resolved by whichever branch merges
  second.
- Grants to `sm_cp_runtime`, unconditional: `INSERT` on `organization_unit` and `environment`; `UPDATE (name)` on
  `organization`, `organization_unit` and `environment`. `UPDATE (retired_at)` on `target` is already granted (V013).
- Grants **only if OQ-6 admits W3** (otherwise the migration omits them, and no `DELETE` is granted with no caller):
  `INSERT` on `principal`; `UPDATE (status, deactivated_at)` on `principal` (`credential_hash, must_rotate` is
  already granted, V008); `INSERT, DELETE` on `role_binding`. With W3, `role_binding` is still the only `DELETE`.
- Catalog: `INSERT INTO permission_catalog` the ruled verbs, with `role_permission` rows for **Administrator** (and
  for Operator only under OQ-7 (b)).
- `record_change_audit()` replaced **once**. Its body is **plan 012's body, which adds `INSTANCE_UNDECLARED`, plus
  this design's operation names**. Only operation names are new (§9: the row keys exist). The `ENTITLEMENT_SET`
  `NULL`-actor exemption is narrowed to `client_address = 'operator-cli'` (R16). This keeps V013's discipline: one
  migration replaces the function, never two in parallel. If plan 012 has not merged first, the integrator rebases
  this body onto whatever body is newest when it merges. The function is a whole-body replacement, so the
  migration's body is a **merge** of the two predecessors, verified by the readiness probe and by the `OR-25`
  refusal tests for every operation in both.
- **Only under OQ-11 (b):** `target_name_once` is replaced by a partial unique index `WHERE retired_at IS NULL`.

### 10.2 `sm_data` (next free version at merge; `V021` at `36af1df6`, after plan 008's and plan 012's)

- `target_connection.credential_public_fingerprint text NULL` (+ grammar CHECK). It is written on insert and
  rotation, and back-filled on a legacy row's first replay or rotation (§8). It is `NULL` for rows registered before
  it ("unknown" in `CredentialSummary`). Added to the runtime's **`UPDATE` column grant only**: V007 already grants
  table-level `SELECT, INSERT` on `target_connection`.
- `target_connection_event` (§9), `SELECT, INSERT` to `sm_dp_runtime`, keyed `tenant_id` first (`I5`), no foreign key
  out of `sm_data` (`I7`).
- No change to `sm_secret` (purpose `SSH_PRIVATE_KEY` exists).

## 11. Invariant audit

| Invariant | Holds because |
|---|---|
| `I1` | Nothing reaches an MI. T6's new remote command (`docker version --format '{{.Server.Version}}'`, a fixed string with no input; the `--format` template is documented at `docs.docker.com/reference/cli/docker/version/`, fetched 2026-10-07) runs the Docker CLI on the host, not the MI. Its exit behaviour when the daemon is unreachable is not documented there and is **MF-R2**. |
| `I5` | Every organization is `Session.organizationId()`. No body carries an organization. Path organization ids are addresses checked against the session. `entitlement.set` evaluates at install scope **on the session's own organization**. |
| `I6` | §6: the key enters as write-only JSON, lives as a `char[]` for one call, is sealed in `sm_secret`, and is referenced by id. No response, log, audit row, event or hash carries it; the only derived value exposed is the **public** fingerprint. Comparison (§8) is in-memory in `dp-core`. |
| `I7` | The gateway composes `cp-api` (`TargetRegistration`, `AuthorizationService`, a new `HierarchyAdministration`, `EntitlementAdministration`, `PrincipalAdministration`, `TargetProbeAudit`) and `dp-api` (`TargetConnectionService` widened with `precheckRegistration`, a new `HostKeyScanService`). No module reaches another plane's tables; no cross-schema foreign key. |
| `OR-22` | One new `sm_control` and one new `sm_data` migration, numbered at merge after plans 008 and 012; nothing published is edited. The V007 retire guard and the V012 host-key triggers are used as they are. |
| `D10` 🔒 | Deny-by-default and one enforcement point unchanged. ⚠️ **Three new verbs amend its catalog** — not built until the owner rules (OQ-1). |
| `OR-48` | ⚠️ The API route for entitlements is a **change** to `OR-48`'s "operator command, no verb". Not built until the owner rules (OQ-4); `EntitlementMain` stays either way. |
| `OR-12` | U6 never grants and U7 never revokes an Administrator binding over the API, and U3 never deactivates or resets an Administrator (OQ-6 (a)). |
| `OR-52` | The envelope stays immutable: T4 refuses `allowedConfigRoot`/`allowedContainerPrefix`, and the runtime still has no `UPDATE` on them (V007). |

## 12. Decisions drafted here (allocated `BE-D54`–`BE-D57`, 2026-10-07)

### `BE-D54` — targets are registered over HTTP in one composed call, authorized and pre-checked before either plane is written, with the key sealed in the DP

`POST /cp/targets` runs in this order:

1. It authorizes `target.create` at the requested ownership scope, writing nothing.
2. It runs every data-plane pre-check read-only: grammar, key parse, address policy, a fresh scan with the pin
   offered, and host-key uniqueness. Any refusal leaves nothing in either plane.
3. It writes the CP governance row.
4. It writes the DP connection record with its `SSH_PRIVATE_KEY` envelope.

Each write is idempotent on its natural key, and a concurrent duplicate re-runs the replay path. A replay is detected
by record **and** by comparing public-key fingerprints. A different key is a `409`, never a silent drop. The private
key is write-only JSON, parsed (unencrypted only) before it is sealed, and represented outside `dp-core` only by its
public-key fingerprint.

### `BE-D55` — a host key is pinned only by an explicit field, and only if the host offers it on a fresh DP scan

Scans are untrusted and pin nothing. Registration and re-pin re-scan from the DP and refuse a pin the host does not
offer; the whole offered set is recorded (`OR-89`), and a re-pin retires the keys no longer offered. The DP refuses
to register, and refuses to connect at every later use (apply, pool, health, metrics), when the target's name
resolves to a loopback, link-local, unspecified or multicast address, or to one outside an optional operator
allow-list. It connects to the checked address where the client supports it. The production policy has no relaxing
setting; tests substitute a test-source bean.

### `BE-D56` — data-plane target changes are audited in the same transaction, in `sm_data`

`target_connection_event` is append-only and written with every insert, edit, re-pin, rotation and retirement of a
connection record; it holds field names and the public-key fingerprint, never key material or a reference.

### `BE-D57` — the scope tree has names, and sub-organizations and environments are created over HTTP

Organization, organization unit and environment carry a `name` (1–128, unique per parent), back-filled with the id
text. `hierarchy.edit` (subject to OQ-1) creates top-level sub-organizations and environments at the parent scope and
renames nodes. Organizations are not created over HTTP in v1.

## 13. Owner questions

✅ **All ruled 2026-10-07 by `OR-121`**, each as recommended (⭐) — see the box at the top. Kept as written.

Every question has the same parts: what does not change, the status quo, the options with their concrete delta, and
the same scenarios (S1–S5, plus S2′ where it bites) run through each option. A scenario an option leaves untouched is
named as untouched rather than left out. Recommendations are marked ⭐, and nothing below is decided until the owner
rules. The counts assume plan 012 merged under its recommended answers (preamble).

### OQ-1 — Which verbs authorize the new writes? (amends the ratified `D10` amendment's catalog)

**Does not change:** deny-by-default, one enforcement point, Administrator holds every verb, no custom roles in v1.
**Status quo:** after plan 012, 40 verbs. Target edit/rotate/retire, hierarchy create/rename and entitlement set have
**no** verb, so they cannot appear in `console.yaml` (check 11) and would answer `501`.

| Option | Delta |
|---|---|
| **(a) Three new verbs**: `target.edit` (HIGH), `hierarchy.edit` (STANDARD), `entitlement.set` (HIGH, install) ⭐ **recommended** | Catalog 40 → 43 (§1.10 rows 41–43). One ruling naming `D10`. `Permission.java`, `PermissionCatalogMirrorTest`, `BuiltInRoleSeedIT` and `validate-openapi.py` extended as plan 012 extends them. The meanings stay narrow. T3 reads under `target.edit` (OQ-13). |
| (b) Two new verbs, `hierarchy.edit` and `entitlement.set`, with **`target.create` read as "register and maintain"** | Catalog 40 → 42. A *reading* of `target.create` (like `OR-94`'s reading of `B/D5`). A later custom role cannot rotate keys without also creating targets. |
| (c) No new verbs: targets only (create via `target.create`); edit/rotate/retire, hierarchy and entitlements stay operator commands | Catalog stays 40. S1 still needs SQL for the sub-organization and environment, and a terminal for the entitlement. S2/S3 have no route at all (no command rotates or edits today). |

- **S1:** (a) and (b) complete in the console; (c) does not.
- **S2/S2′/S3:** (a) and (b) give a route; under (c) **no route exists** (the rotate service has no caller).
- **S4:** identical under all three while the verbs stay Administrator-only (OQ-7).
- **S5:** identical.

### OQ-2 — How is a host key trusted?

**Does not change:** the pin is SHA-256 and unique per organization, and the whole offered set is recorded
(`OR-89`). A pin mismatch at connect refuses the session (`FingerprintVerifier`).
**Status quo:** the operator types a fingerprint into `TargetConnectionMain`, and the command checks it is among the
keys the host offers.

| Option | Delta |
|---|---|
| (a) Explicit only: the console has a text box, and there is no scan endpoint | No T1. The user must obtain the fingerprint out of band. The DP still scans at registration and refuses an unoffered pin. |
| **(b) Scan, then confirm one explicitly** ⭐ **recommended** | T1 exists. The console lists the offered fingerprints and asks the user to compare. The body still carries the pin explicitly, and the DP re-scans at registration. |
| (c) Trust on first use: the DP pins whatever it sees | No pin field; one call. An interceptor present at registration time is pinned silently. |

- **S1:** (b) is the demo's flow and needs no terminal; (a) needs `ssh-keyscan` or host access; (c) is fastest.
- **S2:** (b) shows the new keys; (a) needs them out of band; (c) re-pins blindly.
- **S2′:** unaffected (OQ-12 governs it).
- **S3:** unaffected (rotation does not touch host keys).
- **S4:** (c) lets an on-path attacker become the pinned host with no human step. (a) and (b) both depend on the
  human comparison, and (b)'s risk is a user clicking through.
- **S5:** the same under all three.

### OQ-3 — What may the DP connect to when a caller names a host? (revised: R7)

**Does not change:** Administrator-only verbs (OQ-7), audit of every scan and test, an SSH handshake only for a scan.
**Status quo:** the command connects to whatever the operator types, from the DP host. `OR-82` already decided that
the browser must not steer the DP's HTTP polling to arbitrary addresses. The stored `host` is a **name** (the V007
CHECK admits hostnames and IPv4 literals, not IPv6 literals), which apply, health and the `direct-http` fetcher
re-resolve on every use (`OR-98`).

| Option | Delta |
|---|---|
| (a) No address rule: rate limit and audit only | Nothing to build or configure. `169.254.169.254` is accepted and later polled by `direct-http`. |
| (b) Check at registration and edit only, connecting to the checked address | One policy class and MF-R4. About 0.5 day. A target on the DP's own machine must use its routable address. |
| (c) Allow-list required: empty means nothing may be registered | As (b), plus every install configures CIDRs before S1, including the local stack and the E2E host. |
| **(d) (b) plus a check at every use** in `SshjDockerTransport`, `SshjHostConnector`, `DirectHttpProbe` and `DirectHttpFetcher` ⭐ **recommended** | About +1 day, touching four DP connectors. A refused address at use is a fixed-classifier connection failure. The optional allow-list as in (b). |
| (e) IP literals only, plus (b) | No transport change. The user types an address, not a name, and IPv6 literals need a CHECK-widening migration. A literal cannot re-resolve. |

- **S1:** (a), (b), (d) and (e) need no configuration, but (e) needs the user to know the IP. (c) needs one config
  line first.
- **S2:** under (a)–(d), a DNS-named host whose IP changes needs no edit unless its keys changed. Under (e) every IP
  change is a T4 edit (OQ-12 applies).
- **S2′:** unaffected.
- **S3:** unaffected.
- **S4 (corrected):** (a) leaves metadata and loopback reachable, both for SSH probes and for later `direct-http`.
  (b) refuses a forbidden address **at registration only**. A name that later re-resolves to `169.254.169.254` is
  still polled and connected to, because the check is not repeated (the earlier draft overstated this). (c) is as (b)
  for unlisted ranges, with the same DNS residual. (d) closes the re-resolution path, leaving only the check-to-connect
  window, which needs an attacker who controls the name's DNS and flips it within it. (e) closes it entirely.
- **S5:** unaffected (a refusal writes nothing).

### OQ-4 — Entitlements over HTTP: who sets them, for which organization?

**Does not change:** one entitlement row per (organization, solution), checked at package load and declare
(`OR-40`). `EntitlementMain` stays.
**Status quo (`OR-48`):** only the operator command, with actor `NULL` and no verb. The command does not refuse
lowering below use. `countInstances` counts every instance ever declared until plan 012 T4 (§5.2).

| Option | Delta |
|---|---|
| **(a) `PUT /cp/entitlements/{solution}` on the session's organization, `entitlement.set` at install scope, refusing a value below `declaredInstances` with `409 ENTITLEMENT_REFUSED`; reads under `solution.view`** ⭐ **recommended** | Needs OQ-1's verb and a ruling naming `OR-48`. An org-bound Administrator cannot raise its own quota. Single-organization v1 works for `bootstrap-admin`. Built on plan 012 T4's count. |
| (a′) As (a), but lowering below use is accepted, as the command does | One fewer refusal. Over-quota instances keep running, and new declares are refused (`OR-40`'s check). |
| (b) Keep the command only | No contract change. S1 needs a terminal. |
| (c) Cross-organization install API (`/cp/install/organizations/{id}/entitlements`) | Needs cross-organization authorization, which `require` refuses by design today (§1). Only useful once OQ-5 creates organizations. |

- **S1:** (a) and (a′) complete in the console; (b) needs a terminal; (c) is not reachable in v1.
- **S2/S2′/S3:** untouched.
- **S4:** under (a) and (a′), a stolen **organization**-bound Administrator session cannot change quotas, and a stolen
  install-bound one can (to the organization's own limit only). (a) additionally stops it lowering a quota under live
  use. Under (b) no HTTP session can.
- **S5:** E2 is a `PUT`, so a replay changes nothing under every option.

### OQ-5 — Organizations: create over HTTP?

**Does not change:** one Message VPN per organization (`OR-5`); a principal belongs to one organization.
**Status quo:** V007 creates one organization, and nothing creates a second.

| Option | Delta |
|---|---|
| **(a) Not in v1: rename only; a second organization waits for a multi-organization design** ⭐ **recommended** | No cross-organization authorization designed now. H3 renames. |
| (b) An operator command `OrganizationMain` that creates an organization **and** its first Administrator (single-use credential, `must_rotate`, like V007) | One more command. Multi-organization installs become possible without cross-organization HTTP. VPN provisioning stays manual. |
| (c) An install-scope HTTP route that does (b) | Requires returning or accepting a first credential over HTTP, and a cross-organization rule in `require`: an `I5` exception to be ruled. |

- **S1:** under every option the seeded organization is renamed in the console (H3). Only a **second** organization
  differs: (a) none, (b) terminal, (c) console.
- **S2/S2′/S3:** untouched.
- **S4:** (c) lets a stolen install session mint organizations whose first Administrator credential crosses HTTP.
  (a) and (b) add nothing reachable over HTTP.
- **S5:** (c) would need its own natural key (the organization name); (a) and (b) are not HTTP.

### OQ-6 — The operator user: in this plan? (revised: R18)

**Does not change:** the verbs exist (#25, #27–#30, #38); built-in roles only; deactivate-never-delete.
**Status quo:** no endpoint. `LocalSeed` writes principals and bindings as the database superuser, and
`ENDPOINT-INVENTORY` §6 tells the frontend not to build the Admin screens yet.

| Option | Delta |
|---|---|
| **(a) Include U1–U7 as plan 013's wave W3. The API grants and revokes Viewer and Operator only, never Administrator, and never deactivates or resets a principal holding an Administrator binding** ⭐ **recommended** | No new verb. One migration's grants (§10.1, conditional). `OR-12` enforced structurally and symmetrically. |
| (a′) As (a), but Administrator grantable | A second Administrator can be made over HTTP, which weakens `OR-12`. |
| (a″) As (a), but Administrator bindings are revocable and Administrators deactivatable, **except the last install-scope Administrator** | Symmetric with neither U6 nor `OR-12`'s "bootstrap only". Needs a count-under-lock guard. |
| (b) A separate Admin plan after the VM adapter | Plan 013 is smaller. The owner's `dev` test (`OR-119`) still needs `LocalSeed`'s SQL for users. |
| (c) An operator command `PrincipalMain` | Terminal again; no frontend screen. |

- **S1:** (a), (a′) and (a″) create and bind the Operator in the console; (b) and (c) need SQL or a terminal.
- **S2/S2′/S3:** untouched.
- **S4:** under (a), a stolen Administrator session can create Viewers and Operators and bind them where it holds
  `role.assign`. That is persistence through a new account, but never a new Administrator. Under (a′) it can mint a
  persistent Administrator. Under (a″) it can also lock the legitimate administrators out, down to the last
  install-scope one. Under (b) and (c), nothing is reachable over HTTP.
- **S5:** U2 replays as `409` (username taken). After a timeout the console lists users to see whether the create
  landed. U6 replays as `200`.

### OQ-7 — Who holds the registration verbs?

**Does not change:** three built-in roles; `OR-12` (Administrator is bootstrap/emergency only).
**Status quo:** `target.create`, `user.create` and `role.assign` are Administrator-only (`OR-8`). ⚠️ So in production
the only principal who can register a target is the one `OR-12` says is not a production operator role. This design
surfaces that tension rather than resolving it.

| Option | Delta |
|---|---|
| **(a) Administrator only, recorded as the v1 posture: infrastructure registration is a bootstrap/administrative act** ⭐ **recommended for v1** | No role change. Registration is rare. |
| (b) Operator gains `target.create` and `target.edit` | Operator 23 → 25 (after plan 012). Amends `D10` §1.8 as `OR-80` did. An environment-bound Operator registers its own targets (`OR-56`'s downward rule still holds). |
| (c) A fourth built-in role, "Infrastructure Administrator" (`target.*`, `hierarchy.edit`, `user.*`, `role.assign` without Administrator) | Amends `OR-8`. The cleanest separation, but more surface now. |

- **S1:** under (a), `bootstrap-admin` does every step; under (b) the `dev` Operator registers the `dev` target; under
  (c) a named infrastructure administrator does it.
- **S2/S2′/S3:** under (a) every IP change and every key rotation needs an Administrator session, so the emergency
  role is used routinely. Under (b) and (c) the scoped role does it.
- **S4:** (b) widens the blast radius of a stolen **Operator** session: it can register targets the address policy
  admits, re-point or retire its targets, and rotate their keys. (a) confines all of that to Administrator sessions.
  (c) confines it to one role that is not the emergency one.
- **S5:** untouched.

### OQ-8 — Per-target keys, or a key library?

**Does not change:** keys are sealed in `sm_secret`, referenced by id, and write-only.
**Status quo:** one key per target (`target_connection.credential_ref`). The deleted demo had a library (`POST
/ssh-keys` by reference).

| Option | Delta |
|---|---|
| **(a) Per target** ⭐ **recommended** | No new resource. The console may let the user pick the same file again, client side. |
| (b) A key library: `POST /dp/ssh-credentials` → id, and targets reference it | A new resource with its own lifecycle, verb and reference counting, and its own replay key (the public fingerprint). |

- **S1:** under (a) the key file is chosen per target; under (b) it is uploaded once and referenced.
- **S2/S2′:** untouched.
- **S3:** under (a), 12 calls (the console can loop, and each is idempotent). Under (b), one call, but by design one
  key then spans all 12 targets, which is also the leak's blast radius.
- **S4:** neither returns a key. (b) adds a resource whose removal needs its own verb and guard.
- **S5:** under (a), §8's fingerprint compare covers it; under (b) the upload needs its own replay rule.

### OQ-9 — May T6 add `docker version` to the DP's remote command set?

**Does not change:** every other remote command is placement-scoped and fixed. `I1`: the Docker CLI on the host is
not the MI.
**Status quo:** there is no connection test. The first evidence of an SSH or Docker problem is the first declare's
apply or the first health probe.

| Option | Delta |
|---|---|
| **(a) Yes: one fixed command with no input** ⭐ **recommended** | The console can show "SSH works, Docker answers" before any declare. MF-R2 measures its failure modes on the E2E host. |
| (b) Authenticate only | T6 proves SSH and the key, not Docker. The first declare finds a Docker problem. |

- **S1:** (a) shows the Docker problem at registration; (b) shows it at the first declare.
- **S2:** after a re-pin, (a) also confirms Docker on the new machine.
- **S2′:** untouched.
- **S3:** both confirm the new key authenticates.
- **S4:** (a) adds a fixed, input-free command under the existing `target.use`, so there is no injection surface.
- **S5:** T6 writes nothing, so it is idempotent under both.

### OQ-10 — withdrawn: retiring a target with live placements is already refused (R10)

Not a question. V007's `target_connection_retire_guard` trigger refuses it, and T7 maps that exception to `409`. The
earlier draft's option (b), retiring under live placements, would require dropping a published trigger (`OR-22`).
The id stays reserved so later citations do not shift.

### OQ-11 — Does a retired target free its name? (new: R2)

**Does not change:** a target id is never reused, retired rows are never deleted, and the CP audit is unchanged.
**Status quo:** `target_name_once` is `UNIQUE (organization_id, name)` with no `retired_at` predicate (V013), so a
name is held forever. That includes a degraded row left by §8's race.

| Option | Delta |
|---|---|
| (a) Keep the name reserved | No migration. Re-registering under the same name is impossible after retirement. |
| **(b) Free it: a partial unique index `WHERE retired_at IS NULL`** (the `OR-88` precedent for instance names) ⭐ **recommended** | One new `sm_control` migration. The CP replay on `(organization, name)` considers live rows only. A re-registration gets a **new** id, so no placement follows the name. |

- **S1:** untouched.
- **S2:** if the administrator retires the old VM and registers the new one rather than editing (T4 is the
  recommended path), (a) forces a new name and (b) allows the old one.
- **S2′:** untouched.
- **S3:** untouched.
- **S4:** under (b) an attacker who can retire (no live placements) and register could reuse a name. The new target
  has a new id, so nothing that was bound follows it, and both acts are audited.
- **S5:** the rare race orphan of §8 holds its name forever under (a); under (b), retiring it frees the name.

### OQ-12 — Editing a target's address or pin while work is in flight (new: R4)

**Does not change:** each transport operation re-reads the record (`OR-28`); rotation (T5) is not refused; health and
metrics polls see the change on their next round.
**Status quo:** no edit path exists. The runtime holds the column grant (V007), but no code uses it.

| Option | Delta |
|---|---|
| **(a) Refuse `409` under the admission lock while any non-terminal apply request or fleet member names a live placement on the target (§7.2)** ⭐ **recommended** | Locks the `apply_scope` rows of the target's instances, in id order, for the edit's own short transaction. `APPLY_INDETERMINATE` counts as in flight, because its reconciliation reads the host. |
| (b) Allow, and state the residual | No lock. One apply can write the config on the old host and restart or verify on the new one, and a rollback can read the wrong pre-image. |
| (c) Accept the edit as pending and apply it when the target is idle | A new pending-edit state and a worker. More code than either. |

- **S2 (nothing in flight):** identical under all three.
- **S2′:** (a) answers `409`; the administrator retries after the Operator's apply ends. (b) can split that apply
  across two machines. (c) lands the edit after the apply.
- **S3:** untouched (rotation is not refused).
- **S4:** under (b), an attacker with `target.edit` can deliberately split a running apply; (a) and (c) cannot.
- **S5:** a T4 replay with no effective change writes nothing under all three.

### OQ-13 — Who may read a target's connection record (T3)? (new: R5)

**Does not change:** no key material, `credential_ref` or private-key hash anywhere. `listTargets` keeps the
envelope-only `Target`.
**Status quo:** no HTTP read of host, user or fingerprints. `dp-api`'s `liveEnvelopes` deliberately carries *"no host,
user, fingerprint or credential reference"*. Viewer and Operator both hold `target.view` (V003).

| Option | Delta |
|---|---|
| **(a) T3 requires the management verb: `target.edit` under OQ-1 (a), `target.create` under (b)/(c)** ⭐ **recommended** | Viewers and Operators see what they see today. Only those who can change the record read it. |
| (b) T3 under `target.view` | Every Viewer and Operator in scope sees host, port, SSH user, pinned and offered host keys, the envelope and the public-key fingerprint. |
| (c) Split: `target.view` sees `host` and `port` only; the rest needs the management verb | Two response shapes. Operators can diagnose reachability. |

- **S1:** `bootstrap-admin` holds every verb, so all three are identical.
- **S2:** the editor needs the current record, which it has under all three.
- **S2′:** untouched.
- **S3:** confirming a rotation by `publicKeyFingerprint` is an administrator act under (a) and (c). Under (b) an
  Operator can confirm it too.
- **S4 (a stolen Viewer session):** under (b) it maps every host, SSH user and port in its scope, which is
  reconnaissance for lateral movement. (a) exposes nothing beyond today, and (c) exposes host and port.
- **S5:** untouched.

### OQ-14 — May a private key or credential cross plain HTTP? (new: R15)

**Does not change:** `OR-21`'s cookies; write-only fields; no request-body logging (MF-R3).
**Status quo:** the gateway does not check the scheme. Non-browser clients and a mis-deployed TLS-terminating proxy
can send a body over plain HTTP. The already-shipped `setConfigSecret` and `rotateOwnCredential` carry the same
exposure.

| Option | Delta |
|---|---|
| (a) Accept, and state the residual in the runbook | No code. |
| **(b) Refuse T2, T5, U2 and U3 with `403 INSECURE_TRANSPORT` unless the request is secure, comes from a trusted proxy with `X-Forwarded-Proto: https`, or comes from loopback (§6.1)** ⭐ **recommended** | One gateway filter. It reuses `sm.gateway.trusted-proxies`. Extending it to the two shipped operations is a separate, flagged change, not done here. |
| (c) Refuse unless `request.isSecure()`, with no exceptions | Breaks the `OR-118` local stack and every TLS-terminating proxy deployment. |

- **S1 on the local stack:** (a) and (b) work (loopback); (c) fails.
- **S1 behind a TLS-terminating load balancer:** (a) works; (b) works once the balancer is in `trusted-proxies`, and
  otherwise answers a fixed `403` naming the setting; (c) fails.
- **S2/S2′:** untouched (no secret in T4).
- **S3:** T5 is a credential route and is covered like T2.
- **S4:** under (a), keys and passwords can cross a network in clear through a mis-deployment. (b) and (c) refuse.
- **S5:** the `403` is decided before anything is read or written.

### OQ-15 — How are the SSH scenarios tested? (new: R6, `OR-67`)

**Does not change:** `OR-67`'s rule (test images are approved by exact tag, test-only). The E2E host is available
(`OR-110`).
**Status quo:** the reactor has no SSH fixture. ITs run `busybox:1.36` and `postgres:14` only, `dp-core`'s SSH tests
are unit tests with generated keys, and real SSH runs only on the E2E host (`OR-118`: *"no local SSH harness"*).

| Option | Delta |
|---|---|
| **(a) Approve one SSH-server image, by exact tag and test-only, for scan, auth, key-parse and rotation ITs in the reactor. The Docker-dependent checks (T6's `docker version`, MF-R2, and S2's "the next apply uses the new host") run as live gates on the E2E host** ⭐ **recommended** | Plan task T0.2: pick the candidate and fetch its licence at the pinned tag (`I2`). It is not named here from memory. One owner licence row. About 0.5–1 day. |
| (b) No new image: the reactor ITs use fake `HostKeyScanService`/connector beans, and every real-SSH scenario is a live gate | 0 days. Each build misses the SSH contract, and the live gates carry all of it. |
| (c) An SSH-server image that also runs Docker (Docker-in-Docker) | Full coverage in the reactor. A privileged container and a heavier licence review. About +2 days. |

- **S1:** registration is tested on every build against a real `sshd` under (a) and (c), and only in live gates under
  (b).
- **S2/S2′:** under (a), the re-pin is in the reactor and the "next apply" step is live. Under (c) both are in the
  reactor; under (b) both are live.
- **S3:** under (a) and (c), "the old key is refused" is a reactor IT (the fixture's `authorized_keys`); under (b) it
  is live only.
- **S4:** the address policy is a unit test under all three.
- **S5:** the replay ITs need a scannable host, which is real under (a) and (c) and faked under (b).

## 14. Measured facts owed (plan 013 measures them; nothing above depends on recall)

| Id | Question | Where |
|---|---|---|
| MF-R1 | sshj 0.40.0 `loadKeys(String, null, null)` on a passphrase-protected OpenSSH and PEM key: does it fail at load or at auth, and with what exception? | T1.1 |
| MF-R2 | `docker version --format '{{.Server.Version}}'` exit code and output when the daemon is down, and when the SSH user lacks the `docker` group | T1.5, on the E2E host — ⚠️ stopping the daemon and creating an account are root-level changes that `OR-110` does not name; the owner's explicit permission is asked first (plan 013 T1.5) |
| MF-R3 | No request-body logging anywhere in the launcher at its production log levels (a log-capture test, not a reading of the config) | T1.4 |
| MF-R4 | sshj 0.40.0: connecting to a pre-resolved `InetAddress` while the `HostKeyVerifier` still receives the original name | T1.2 |

## 15. Review dispositions (first adversarial review, 2026-10-07)

All 21 findings were checked against the tree at `36af1df6` and against `d/ops501` (`fc530d5c`) before they were
acted on. Every one held. Two are fixed differently from the reviewer's suggestion, and the reason is given.

| Id | Sev. | Disposition | Where |
|---|---|---|---|
| R1 | MAJOR | **Fixed.** Re-baselined on plan 012: catalog 40 → 43 through §1.10, plan 012's mirror-test and validator mechanism, Operator counts from 012. The `record_change_audit()` body is a merge of 012's body and this design's names. `PermissionCatalogMirrorTest` and `BuiltInRoleSeedIT` are named. E1/E2 use 012 T4's count. | preamble, §4, §5.2, §10.1, OQ-1, OQ-7; plan header, T0.1, T2.1 |
| R2 | MAJOR | **Fixed.** Authorize without writing (the interceptor's `require`), then every DP pre-check read-only, then the CP insert, then the DP insert. T7 defines the CP-only retire. The name-reuse question is OQ-11. | §5.1 T2/T7, §8, §12, OQ-11; plan T1.4, T1.5 |
| R3 | MAJOR | **Fixed.** The eviction method is dropped. The pool's identity binding and the apply path's per-operation connection already make a rotation or re-pin take effect. S3's real mitigation, removing the old key from `authorized_keys`, is stated as the operator's runbook step. | §3 S3, §6.4, §7.2; plan T1.5, T4.2 |
| R4 | MAJOR | **Fixed.** T4 address and pin changes are refused with `409` under the `apply_scope` admission lock while work is in flight. Scenario S2′ and OQ-12 are added, with a test in T1.5. | §3, §5.1 T4, §7.2, OQ-12; plan T1.5 |
| R5 | MAJOR | **Fixed.** T3 moves off `target.view`, and the posture change is OQ-13 with the scenarios run through it. | §4, §5.1, OQ-13 |
| R6 | MAJOR | **Fixed.** A fixture task with its `OR-67` licence row (plan T0.2) is added. Docker-dependent checks move to the E2E host under `OR-110`. The choice is OQ-15. W1 is re-estimated. | §2, OQ-15; plan T0.2, T1.2, T1.4, T1.5, Estimate |
| R7 | MAJOR | **Fixed, differently.** The test seam is a test-sources bean behind an interface, guarded by ArchUnit, not a property refused in a production profile: the tree has no profile mechanism, and a property would be one setting away from production. OQ-3's S4 row is corrected, and options (d) (check at every use, recommended) and (e) (IP literals) are added. | §7.3, §12, OQ-3; plan T1.2 |
| R8 | MAJOR | **Fixed.** Every question now has what does not change, the status quo, the delta, and S1–S5 (S2′ where relevant). OQ-11–OQ-15 cover name reuse, edit during apply, T3 exposure, plain-HTTP transport and the fixture. | §13 |
| R9 | MINOR | **Fixed.** The wrong grant claim is corrected. T4 retires host keys no longer offered and revives re-offered ones (the primary key makes the trigger's `DO NOTHING` keep a retired row). | §7.2 |
| R10 | MINOR | **Fixed.** OQ-10 is withdrawn as a fact. T7 maps the V007 trigger's exception to `409`. | §5.1 T7, OQ-10; plan T1.5 |
| R11 | MINOR | **Fixed, differently.** Replay and rotation compare public-key fingerprints. For legacy `NULL` rows, `dp-core` derives the stored key's fingerprint in memory and back-fills it, rather than calling `SecretCompare`, because that port resolves `CONFIG_FIELD` only and compares text, which has the very format problem the finding names. | §2, §8, §12; plan T1.1 |
| R12 | MINOR | **Fixed.** `UPDATE` grant only. | §10.2; plan T1.3 |
| R13 | MINOR | **Fixed.** A new `TargetAddressProperties` (`sm.dp.targets.*`) and a new `cp-api` port `TargetProbeAudit` (on the `ExportAudit` precedent). | §9; plan T1.2, T1.4 |
| R14 | MINOR | **Fixed.** Scope rows are minted by `ScopeStore.canonicalize`. Only operation names are new in the trigger. | §5.3, §9, §10.1; plan T2.2 |
| R15 | MINOR | **Fixed.** The overclaim is removed, and a transport rule is proposed as OQ-14 (b). | §6.1, OQ-14; plan T1.4, T3.2 |
| R16 | MINOR | **Fixed.** The count is named `declaredInstances` and built on 012 T4. The refusal reuses `409 ENTITLEMENT_REFUSED`. The trigger exemption is narrowed to `client_address = 'operator-cli'`. | §5.2, §9, §10.1, OQ-4; plan T2.1, T2.2 |
| R17 | MINOR | **Fixed.** The divergence from `console-integration.md` §5.2 rows 21–23 is flagged. The code's organization reading governs pending the owner, with a forward pointer in T4.2. | §4; plan T4.2 |
| R18 | MINOR | **Fixed.** The API never revokes an Administrator binding and never deactivates or resets an Administrator, mirroring U6. The narrower "last install-scope Administrator" guard is OQ-6 (a″). | §5.4, §11, OQ-6; plan T3.1 |
| R19 | MINOR | **Fixed.** `p/or116` is a merge prerequisite for W2, with the conflict in `ScopeVisibleHierarchyDirectory` planned. | §10.1; plan header, T2.2 |
| R20 | MINOR | **Fixed.** One gating rule: nothing starts before every question is ruled and the plan approved. W3's grants and operation names are conditional on OQ-6. | §9, §10.1; plan header, T2.1 |
| R21 | MINOR | **Fixed.** Re-estimated after R1 and R6. A concurrent double-submit maps the unique violation to the replay path, with its own IT. | §8; plan T1.4, Estimate |
