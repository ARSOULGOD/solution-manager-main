# Endpoint inventory — for the frontend team

**As of 2026-09-16, this is a contract inventory, not an implementation record.**
It says what is *contracted*, what is *planned*, and what will answer `501`, so the console
can be built against a surface that is agreed rather than guessed.

Read with [`console.yaml`](console.yaml) (the machine-readable contract, gated by
`run-checks.sh` check 11) and
[`../../docs/design/common/contracts/console-integration.md`](../../docs/design/common/contracts/console-integration.md)
§5 (the full 66-operation map with rationale).

---

## The honest status, in one paragraph

The **backend CP half does not exist**. Measured: `cp-api`, `cp-core`, `dp-api` and
`console-gateway` each hold exactly **one marker class**; `sm_control` has **no migrations**;
the only authorization code in the tree is the demo launcher's, which is not a production
authority and whose waiver expired 2026-09-14. What *does* exist is the **apply engine** in
`dp-core` (plans 004–005 merged on this lineage: **8** `RuntimeAdapter` operations, the three-table
journal, admission fences) and the **event contract** in `contracts/events/`. So this
inventory is a contract to build against, not a description of a running server.

⛔ **The frontend cannot integrate against real APIs today.** It can, starting now, generate
types from `console.yaml` and replace hand-written request/response shapes, so that when the
server lands the integration is a base-URL change rather than a rewrite.

---

## 1. Contracted — 25 operations in `console.yaml`

These have an agreed path, verb, scope, request and response shape. Each is owned by plan 006
(authorization substrate) or plan 007 (the HTTP surface).

| Method | Path | Permission | Grant scope |
|---|---|---|---|
| POST | `/cp/auth/login` | *none — establishes the session* | self |
| POST | `/cp/auth/logout` | *authentication only* | self |
| GET | `/cp/auth/identity` | *authentication only* (`OR-15`) | self |
| POST | `/cp/auth/credential` | `local.rotateOwnCredential` ⚠️ not a catalog verb | self |
| GET | `/cp/hierarchy/organizations` | *authentication only* | derived |
| GET | `/cp/hierarchy/organizations/{organizationId}/sub-orgs` | *authentication only* | derived |
| GET | `/cp/hierarchy/.../environments` | *authentication only* | derived |
| GET | `/cp/solutions` | `solution.view` | organization |
| POST | `/cp/solutions/load` | `package.load` | organization (`OR-14`) |
| GET | `/cp/targets` | `target.view` | organization |
| GET | `/dp/solutions/{solutionId}/instances` | `instance.view` | solution |
| POST | `/dp/solutions/{solutionId}/instances` | `instance.create` + `instance.bindTarget` + `target.use` | environment |
| GET | `/dp/instances/{instanceId}` | `instance.view` | instance |
| GET | `/dp/instances/{instanceId}/designer` | `config.view` | instance |
| POST | `/dp/instances/{instanceId}/config` | `config.edit` | instance |
| GET | `/dp/instances/{instanceId}/config/history` | `config.view` | instance |
| POST | `/dp/instances/{instanceId}/apply` | `config.apply` (+ `instance.restart` when the strategy restarts) | instance |
| GET | `/dp/instances/{instanceId}/apply/observe` | `config.view` | instance |
| POST | `/dp/instances/{instanceId}/apply/rollback` | `config.rollback` (+ `instance.restart`) | instance |
| POST | `/dp/instances/{instanceId}/stop` | `instance.stop` ⚠️ **Administrator-only** | instance |
| GET | `/dp/instances/{instanceId}/health` | `health.view` | instance |
| GET | `/dp/instances/{instanceId}/config-ladder` | `config.view` | instance |
| GET | `/dp/instances/{instanceId}/logs/{component}` | `logs.read` | instance |
| GET | `/dp/instances/{instanceId}/log-levels/{component}` | `logs.read` | instance |
| POST | `/dp/instances/{instanceId}/log-levels/{component}` | `logs.setLevel` | instance |

---

## 2. Planned, not yet contracted — 11 admin operations

Verbs exist in the ratified catalog; **no plan builds them yet**, so they are absent from
`console.yaml` rather than stubbed. Building the Admin screens against them today means
building against a shape nobody has agreed.

`GET /cp/admin/permission-catalog` · `GET /cp/admin/roles` · `GET /cp/admin/principals` ·
`GET /cp/admin/scope-tree` · `GET /cp/admin/assignments` · `POST /cp/admin/assignments` ·
`DELETE /cp/admin/assignments/{assignmentId}` · `GET /cp/admin/effective-permissions` ·
`GET /cp/admin/users` · `POST /cp/admin/users` · `PATCH /cp/admin/users/{id}`

⚠️ **`POST /cp/admin/roles` is a different case** — `role.edit` **is** catalog member #26, but
`sm_control.role` is `SELECT`-only to the runtime role by DCL, because v1 ships **built-in
roles only**. It is blocked by the data model, not by vocabulary, and needs a decision before
it can ship at all.

---

## 3. Will answer `501`, not `403` — no approved verb

⛔ **The distinction matters to the console.** `403` means *you may not*; `501` means *nobody
may, because the operation has no verb*. Rendering a `501` as a permission error would tell a
user to go ask an administrator for something no administrator can grant.

| Operation | Why | Open item 39 |
|---|---|---|
| `DELETE /dp/instances/{instanceId}` (undeclare) | no verb exists | letter (d) |
| `POST /dp/instances/{instanceId}/actions/{actionKey}` | package-declared actions have no verb | letter (e) |
| The ten `/cp/admin/groups*` operations | no `group.*` verb was approved | letter (c) |
| Selecting `restart-all` or `rolling-no-validation` | high-consequence strategy assertion has no verb | letter (b) |
| `instance.version.redeclare`, archive/restore | resource not identified | letter (i) |
| Agent Gateway operations | the human actions are not enumerated yet | letter (h) |

⚠️ **`Strategy` in `console.yaml` therefore has exactly two members**, `manual` and
`rolling-health-gated`. Do not offer the other two in a picker.

---

## 4. What the console must handle, and is easy to get wrong

1. **`stateVersion` is the only ordering signal.** Every stateful response carries it. Refetch
   when a mesh notice reports a higher one; ⛔ **never order by timestamp**, and never treat an
   event payload as state — events are refetch notices (`BE-D1`, `OR-3`).
2. **`availability` is a first-class field, per surface.** A degraded surface is *declared*, with
   a reason. ⛔ **Never render a degraded surface as an empty one** — an empty list and a broken
   feed look identical to a user and mean opposite things. Polling is a declared degraded mode,
   not the normal architecture.
3. **`404` and "exists but out of scope" are deliberately indistinguishable.** Discriminating
   them leaks existence across a scope boundary.
4. **Every mutation carries `X-SM-CSRF`.** Its value comes from the login or identity response.
5. **`mustRotate` gates almost everything.** A principal in that state has a session that
   authorizes only identity, logout and credential rotation. The console must route to the
   rotation screen, not to a dead dashboard.
6. **Permissions in the identity response are for rendering, never for deciding.** The server
   decides; the overlay only hides what would be refused anyway.
7. **No resolved secret is ever returned** (`I6`) — not by the designer, not by history, not by
   the ladder. Fields carry a reference and a presence flag.
8. **`202` means durable, not done.** Apply, rollback, stop and log-level changes return
   `202 {applyId, stateVersion}` before any adapter call; the outcome is observed at
   `/apply/observe`. ⚠️ A `202` from `stop` does **not** mean the workload stopped: the two
   post-admission failures `stop.no.known.placement` and `stop.replica.label.collision` surface
   as a terminal `STOP_FAILED` on the request.

---

## 5. Two hierarchies — do not use one where the other belongs

- **API / topic / event vocabulary — six names** (`OR-2`):
  `Organization → Sub-organization → Environment → Solution → Instance → Placement`.
- **RBAC grant hierarchy — five levels** (`OR-16`, which amends `D11`): the same list
  **ending at Instance**.

⛔ **Placement is an operational child identity of Instance and is not a grantable level.** It
appears in URLs, topics and payloads; it never appears in a role binding. A placement-targeted
`stop` is authorized at the owning instance, which is why a binding cannot grant `stop` for one
placement only. `ScopePath` in `console.yaml` has five components and no placement.

---

## 6. What the frontend can do now

1. **Generate types from `console.yaml`** and delete the hand-written equivalents. The document
   is gated by check 11, so a verb or scope that drifts from the ratified catalog fails the
   build rather than reaching a screen.
2. **Model `availability` and `stateVersion` in the client state layer now.** Retrofitting
   degraded-mode rendering after the fact is how a console ends up showing empty lists for
   broken feeds.
3. **Build the `501` path.** It is a different user-facing message from `403`.
4. ⛔ **Do not build the Admin role/user/assignment screens yet** — §2 above. Their shapes are
   not agreed, and the built-in-roles constraint may change what is possible.
