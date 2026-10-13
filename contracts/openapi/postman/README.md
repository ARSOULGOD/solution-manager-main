# Postman collection — Solution Manager console API

For frontend engineers who want to call the production backend before the console screens exist.

| File | What it is |
|---|---|
| `solution-manager.postman_collection.json` | Postman Collection v2.1 — every operation of `contracts/openapi/console.yaml`, once |
| `solution-manager.local.postman_environment.json` | Environment for a locally running launcher |
| `generate-postman.py` | The generator. **The two JSON files are generated; never edit them by hand** |
| `check-postman.py` | Drift + coverage check (see [Regenerating](#regenerating)) |

The contract `contracts/openapi/console.yaml` is the single source of truth. This collection is a view of it,
not a second contract: if the two disagree, the contract wins and the collection is regenerated.

## Import

1. Postman, **Import**, select both JSON files in this folder.
2. Select the environment **Solution Manager — local** (top-right).
3. Open the environment and fill in `username` and `password` (the last marked secret). A username is unique per organization (`OR-125` (4)); the `organization` of a login is OPTIONAL (`OR-131` Q3): omitted, it is the install's only organization, so the generated `login` body omits it. On an install holding several organizations an omitted `organization` is `400`: add `"organization": "<id or name>"` (in any case) to the login body. A fresh install's one organization is `00000000-0000-4000-8000-000000000011` until it is renamed. **No real value is committed.**
   A fresh install's first administrator is `bootstrap-admin` (migration `V007__bootstrap_admin.sql`); its password
   is the single-use secret whoever installed the system supplied (only its Argon2id hash is stored).

`baseUrl` defaults to `http://localhost:8080/api`: the production launcher sets no `server.port`
(`backend/launcher/src/main/resources/application.properties`), so Spring Boot's default **8080** applies, and the
contract's one server is the relative `/api`. `origin` (`http://localhost:8080`) is sent as the `Origin` header on
every state-changing request — see [Auth](#auth-how-the-collection-signs-in).

## Running the backend locally

> **One command instead:** `backend/local/sm-local.sh up` does everything below, then sets the install up **through the API** (the same
> walk as this collection's `Setup` folder: rotate, name the organization, hierarchy, entitlement, an Operator, and an optional SSH-Docker
> target) - see [`backend/local/README.md`](../../../backend/local/README.md). The manual recipe stays for reference.

The frontend team normally points at a backend someone else runs. If you need your own, **read this first**:
the launcher is a production composition; it needs PostgreSQL, two migration steps and a master key, and it refuses
to start without them. There is no docker-compose for it in the repo and no install runbook yet
(`docs/runbooks/install-prerequisites.md` covers capacity and the migration step only). The recipe below is
**not an official runbook**: it is what the smoke test below actually did, assembled from the
`TwoSchemaTestDatabase` integration-test harness and the two migration entry points. All paths are in
`backend/`.

Prerequisites: JDK 17+ (the build ran under 21), Maven, Docker, and at least a 2 GiB heap (Argon2id login hashing,
`docs/runbooks/install-prerequisites.md`).

```bash
# 1. build (offline works when ~/.m2 is warm)
mvn -f backend/pom.xml -pl launcher -am -DskipTests -DskipITs package

# 2. a throwaway PostgreSQL 14
docker run -d --name sm-pg -e POSTGRES_PASSWORD=smoke -e POSTGRES_DB=sm -p 127.0.0.1::5432 postgres:14
docker port sm-pg 5432/tcp          # note the host port, e.g. 58831

# 3. Phase 0 (superuser, once), then set the four role passwords
docker exec -i sm-pg psql -v ON_ERROR_STOP=1 -U postgres -d sm < backend/cp-core/src/main/resources/db/bootstrap/sm_control_phase0.sql
docker exec -i sm-pg psql -v ON_ERROR_STOP=1 -U postgres -d sm < backend/dp-core/src/main/resources/db/bootstrap/sm_data_phase0.sql
for r in sm_cp_migrator sm_cp_runtime sm_dp_migrator sm_dp_runtime; do
  docker exec sm-pg psql -U postgres -d sm -c "ALTER ROLE $r PASSWORD '<choose>'"; done

# 4. the two one-shot migration steps (configuration is environment-only).
#    SM_CONTROL_BOOTSTRAP_CREDENTIAL_HASH is the {argon2id} hash of the bootstrap admin's password; make one with
#    com.solace.sm.cp.identity.CredentialHasher#hash (there is no CLI for it). Plaintext never enters the database.
SM_CONTROL_MIGRATOR_URL=jdbc:postgresql://127.0.0.1:<port>/sm SM_CONTROL_MIGRATOR_USERNAME=sm_cp_migrator \
SM_CONTROL_MIGRATOR_PASSWORD=... SM_CONTROL_BOOTSTRAP_CREDENTIAL_HASH='{argon2id}...' \
  java -cp <launcher classpath> com.solace.sm.cp.persistence.SmControlMigrationMain
SM_DATA_MIGRATOR_URL=jdbc:postgresql://127.0.0.1:<port>/sm SM_DATA_MIGRATOR_USERNAME=sm_dp_migrator \
SM_DATA_MIGRATOR_PASSWORD=... \
  java -cp <launcher classpath> com.solace.sm.dp.apply.persistence.SmDataMigrationMain

# 5. the launcher (runtime credentials only; it never receives a migrator credential)
SM_CONTROL_RUNTIME_URL=jdbc:postgresql://127.0.0.1:<port>/sm SM_CONTROL_RUNTIME_USERNAME=sm_cp_runtime SM_CONTROL_RUNTIME_PASSWORD=... \
SM_DATA_RUNTIME_URL=jdbc:postgresql://127.0.0.1:<port>/sm     SM_DATA_RUNTIME_USERNAME=sm_dp_runtime     SM_DATA_RUNTIME_PASSWORD=... \
SM_GATEWAY_TRUSTED_ORIGINS_0=http://localhost:8080 \
SM_GATEWAY_DIRECT_CLIENT_CONNECTIONS=true \
SM_DP_MASTER_KEY="$(head -c 32 /dev/urandom | base64)" SM_DP_MASTER_KEY_ID=local-1 \
  java -Xmx2g -cp <launcher classpath> com.solace.sm.launcher.SolutionManagerApplication
```

`<launcher classpath>` is the unpacked `BOOT-INF/lib/*` and `BOOT-INF/classes` of
`backend/launcher/target/launcher-*-exec.jar`. Notes that matter:

- `sm.gateway.trusted-origins` has **no default** and startup fails without it; it must equal the `origin`
  environment variable exactly (`scheme://host[:port]`, lowercase, no trailing slash).
- Startup also fails unless exactly one of `sm.gateway.trusted-proxies` or `sm.gateway.direct-client-connections=true`
  is set. Direct is right for a local run.
- The mesh is off by default (`sm.mesh.enabled=false`; one WARN at boot) — fine for REST integration.
- `SM_DP_MASTER_KEY` must come from the environment. Never commit one.
- On a fresh database the entitlement tables are empty, so `loadPackage` answers `409 ENTITLEMENT_REFUSED` until an
  operator provisions entitlement ([`EntitlementMain`](#before-loading-a-package)), and there are no targets, so most of the instance surface
  answers `404`/empty until the data exists. See the happy path below.

## Auth: how the collection signs in

This is exactly what `console.yaml`'s `sessionCookie` scheme and the gateway implement
(`backend/console-gateway`, `AuthController`):

1. **`login`** (`POST /cp/auth/login`, folder `0. Start here`) sends `{username, credential}` (and `organization` when the install holds several) with a trusted
   `Origin`; an organization that does not exist is refused exactly as a wrong credential is (`401`). No CSRF token yet (none can exist). The response sets two cookies, both `Path=/`, `SameSite=Strict`,
   `Secure`, no `Domain`: `__Host-SM_SESSION` (`HttpOnly`, the session) and `__Host-SM_CSRF` (script-readable). The
   body also returns `csrfToken` (the same value).
2. Postman's **cookie jar** carries the session cookie on every later call automatically. The login test script
   stores `csrfToken` in the `csrfToken` variable (in the active environment, or the collection variables when no
   environment is selected).
3. Every state-changing request (POST/PUT/PATCH/DELETE) is pre-filled with `X-SM-CSRF: {{csrfToken}}` and
   `Origin: {{origin}}`. Reads need neither. A missing or foreign CSRF token is `403 CSRF_REJECTED`; an untrusted
   origin is `403 ORIGIN_REJECTED`.
4. **`mustRotate`**: the bootstrap administrator's credential is single-use. The first login succeeds with
   `mustRotate: true`, and every route other than `getIdentity`, `rotateOwnCredential` and `logout` answers
   `428 MUST_ROTATE` until you run **`rotateOwnCredential`** (`currentCredential` is `{{password}}`,
   `newCredential` is `{{newPassword}}`, min 12 characters). On `204` the script copies `newPassword` into `password`.
   Other sessions of the principal are revoked; the one you hold survives. Then run `getIdentity` again to see the
   real permission list and `mustRotate: false`.
5. `logout` clears both cookies.

Evaluation order, from the contract: origin, session, must-rotate, CSRF, authorization. So an unauthenticated
mutation is `401` (never a CSRF error), and an untrusted origin is `403 ORIGIN_REJECTED`.

**Cookie jar note.** The cookies are `__Host-` prefixed and `Secure`, but you call `http://localhost`. curl and
browsers treat localhost as a secure context and keep them (curl verified, below). **Postman's behaviour with
`Secure` cookies on plain `http://localhost` is not verified here** (no Postman in this environment). If a call after
login answers `401 UNAUTHENTICATED`, open Postman's *Cookies* manager for `localhost` and check both cookies are
present. Fallback: set the variable `cookieMode=manual`; the login test script then stores the two `Set-Cookie`
values and the collection pre-request script sends them in a `Cookie` header. Use it only if the jar does not send
them — two session cookies in one request are refused `401`.

## SERVED vs "CONTRACTED, NOT YET SERVED"

Every request's description begins with `operationId: ...` and one of three markers; unserved names end in `(501)`, wired ones in
`(wired, not counted served)`.

- **SERVED** — a handler exists in the production launcher. The *Responses* list in the description shows every
  code the contract declares.
- **WIRED — NOT COUNTED SERVED** — a handler exists and is tested, but the measurement that lets it be counted served has not
  been recorded, so **do not build the feature on it**. Today that is `exportRange` alone: plan 011 P11-7.2 wired it, and
  P11-8.1 Step 6b has not yet recorded the 105 M-row stream (M-Q18). The inventory lists it in its own table, "Wired, not counted
  served", never in "Served now".
- **CONTRACTED, NOT YET SERVED (answers 501)** — the path, verb, permission, scope and the request and response
  shapes are agreed in `console.yaml`, but nothing is implemented. Generate types from it and code against it. Per
  the contract and `ENDPOINT-INVENTORY.md` the call answers `501 NOT_IMPLEMENTED`. A route that is not mapped at all
  answers `404 NOT_FOUND`. Either means "not served"; do not build UI behaviour on the failure.

How the marker is decided: an operation is SERVED if `ENDPOINT-INVENTORY.md`'s "Served now" table lists it **or**
`backend/console-gateway` carries `@ConsoleOperation("<operationId>")` for it, **unless** the inventory's "Wired, not counted
served" table lists it, which overrides the code scan and gives the WIRED marker. The generator prints a note when the
inventory and the code disagree. **At the current tree they agree:** the inventory's two tables and the gateway's 73
`@ConsoleOperation` handlers (48 at wave 4; plan 012 T7 added `listApplySchedules` and `cancelApplySchedule` and T5 `undeclareInstance`, plan 013 T1.4 added `scanHostKeys`, `registerTarget` and `getTargetConnection`, T1.5 added `editTargetConnection`, `rotateTargetCredential`, `testTargetConnection` and `retireTarget`, T2.3 added seven hierarchy and entitlement operations and T3.2 eight user and role operations) are the same set, so every contracted operation is marked SERVED or WIRED. One of the served ones,
`declareMetricType`, is a **contracted refusal**: it is routed and always answers `501 NOT_IMPLEMENTED`
(`reason: metric-declare-deferred`) to a caller who holds `metric.declare`, never `403`; the collection's test
script accepts the `501` for it, and only for the operations whose contract entry declares a `501` (a `501` from any other served
route fails the test). (The original generation, on an earlier tree, had 32 served and the 14 monitoring
operations unserved; the monitoring routes and the corrected inventory arrived with P11-7.2 and plan 010's close.)

At generation time: **73 operations — 72 served, 1 wired-not-counted-served, 0 not served.**

## Variables

Ids one call returns and the next consumes are captured by each request's test script and stored as variables:
`csrfToken`, `organizationId`, `unitId` (the `subOrganizationId` path parameter), `environmentId`, `solutionId`,
`packageVersion`, `targetId`, `instanceId`, `placementId`, `stateVersion`, `generation`, `applyId`, `fleetId`,
`dashboardId` (derived as `db_` + `instanceId`), `dashboardRevision`, `widgetId`, plus the ones you set by hand:
`component`, `fieldKey`, `entityType`, `labelKey`, `metricName`. Scripts write to the active environment when one is
selected (and to the collection variables when none is), and read the same way. Captures take the **first** item of
a list — change the variable by hand to follow another one.

## Before loading a package

`loadPackage` is refused with **`409 ENTITLEMENT_REFUSED`** until the caller's organization holds an
**entitlement** for the solution the package declares. Since plan 013 T2.3 (`OR-121`) the console sets it over HTTP:
**`setEntitlement`** (`PUT /cp/entitlements/{solution}`, body `{"maxInstances": n}`, permission `entitlement.set` at **install** scope, so
the bootstrap administrator may and an organization-bound Administrator may not). The `Setup` folder does it as step 7, with the
solution and limit from `setupSolution` and `setupEntitlementMax`. The standalone operator command below remains for the cases the API
cannot reach (an installation with no administrator able to sign in, or an organization other than the bootstrap
administrator's own); it is authorized by the **runtime** credential (`sm_cp_runtime`), the same model as the lockout-recovery
command. Do this once per organization and solution, before the `loadPackage` step:

```bash
# <launcher classpath> is the same unpacked BOOT-INF/lib/* + BOOT-INF/classes used in "Running the backend locally".
# Configuration is environment-only: the command takes no arguments and prints back only the solution and limit.
SM_CONTROL_RUNTIME_URL=jdbc:postgresql://<host>:<port>/<database> \
SM_CONTROL_RUNTIME_USERNAME=sm_cp_runtime \
SM_CONTROL_RUNTIME_PASSWORD=<runtime-password> \
SM_CONTROL_ENTITLEMENT_ORGANIZATION_ID=<organization-uuid> \
SM_CONTROL_ENTITLEMENT_SOLUTION=<solution-name> \
SM_CONTROL_ENTITLEMENT_MAX_INSTANCES=<positive-integer> \
SM_CONTROL_ENTITLEMENT_SET_BY=<username-of-an-active-local-principal-of-that-organization> \
  java -cp <launcher classpath> com.solace.sm.cp.persistence.EntitlementMain
```

| Variable | Value |
|---|---|
| `SM_CONTROL_RUNTIME_URL` / `_USERNAME` / `_PASSWORD` | The runtime credential. The URL is a bare `jdbc:postgresql://host:port/database` location — no credential or parameters in it. |
| `SM_CONTROL_ENTITLEMENT_ORGANIZATION_ID` | The organization's UUID. Read it from `login` / `getIdentity` → `organizations[].id` (or `listOrganizations`) after signing in as a principal of that organization. |
| `SM_CONTROL_ENTITLEMENT_SOLUTION` | The catalog solution name the package declares in its `solution` field, e.g. the committed Cache fixture (`contracts/validation/fixtures/cache-2.4.package.json`) declares `cache`. Lowercase letters, digits and `-`, starting with a letter, at most 64 characters. |
| `SM_CONTROL_ENTITLEMENT_MAX_INSTANCES` | How many instances of the solution the organization may run — a positive integer. |
| `SM_CONTROL_ENTITLEMENT_SET_BY` | The **username** of an active local principal of that same organization (for example `bootstrap-admin`); recorded on the row as who it is attributed to. The audit actor is none: the write is audited as `ENTITLEMENT_SET` from the operator command. |

Exit codes: `0` set (it prints `entitlement set: solution <name>, max instances <n>`); `1` the write failed; `2` a
variable is missing or malformed (nothing was connected to); `3` `SM_CONTROL_ENTITLEMENT_SET_BY` is not an active local
principal of that organization (nothing was written). Running it again for the same organization and solution changes
the limit. No restart of the launcher is needed.

## The `Setup` folder: a fresh install, top to bottom

The second folder, **`Setup`**, is the walk a new install takes from the bootstrap credential to a registered target and a bound
Operator (plan 013 T4.1, `OR-114` item (3)). Its requests are the ordinary requests of the folders below with a body that suits the
walk; each operation still has its one place in its own folder, and `check-postman.py` verifies both (the `Setup` folder holds exactly
the generator's `SETUP_STEPS`, in order, each naming a contracted operation). The order:

| # | Operation | What it does |
|---|---|---|
| 1 | `login` | the bootstrap administrator (`username`, `password`); `organization` is omitted (`OR-131` Q3) |
| 2 | `rotateOwnCredential` | the single-use credential becomes yours. Set `newPassword` first. Skip on an install already rotated |
| 3 | `getIdentity` | `mustRotate` is now false; captures the install's one `organizationId` and its name |
| 4 | `renameOrganization` | names the organization (`setupOrganizationName`); install-bound callers only |
| 5 | `createSubOrganization` | `setupUnitName`; captures `unitId` |
| 6 | `createEnvironment` | `setupEnvironmentName`; captures `environmentId` |
| 7 | `setEntitlement` | `setupSolution` (the package's `solution`, `cache` for the fixture) up to `setupEntitlementMax` instances |
| 8 | `loadPackage` | the committed Cache fixture |
| 9 | `scanHostKeys` | lists the host keys `sshHost` offers (it logs them to the Postman console); **nothing is pinned** |
| 10 | `registerTarget` | the SSH-Docker target with `hostKeyFingerprint` (the one **you** confirmed out of band), the key in `sshPrivateKey` and the envelope; SM connects once to pre-check |
| 11 | `testTargetConnection` | one fixed command over the pinned key and the stored key; a failure is a result, not a 5xx |
| 12 | `createUser` | the Operator, with a provisional credential `newUserCredential` it must rotate at first sign-in |
| 13 | `listRoles` | captures the Operator role id |
| 14 | `grantAssignment` | binds the Operator at the new environment |

Fill the **Setup variables** in the environment first: `setup*`, `targetName`, `sshHost`, `sshPort`, `sshUser`, `hostKeyFingerprint`,
`allowedConfigRoot`, `allowedContainerPrefix`, and the secret `sshPrivateKey` (the unencrypted key file's text, pasted as it is; the
pre-request script escapes its newlines for the JSON body and never stores it elsewhere). `<...>` placeholders are deliberate: the
host key is a decision, not a default, and SM does not copy a scanned key into the pin for you. Plain HTTP works against a loopback
launcher; a deployment that terminates TLS in front needs `sm.gateway.trusted-proxies` (plan 013 `OQ-14` (b)) or the writes answer
`403 INSECURE_TRANSPORT`. Re-running is safe except `rotateOwnCredential` (skip it once rotated) and `createUser` (`409` for the same
username, by design). **The folder has not been run against a live stack in this wave (live gates owed); the local script that does
the same walk through its own client is `backend/local/sm-local.sh`.**

## Typical happy path

Run top to bottom; each step's test script feeds the next. **On a freshly installed system run the `Setup` folder first**
(next section): it makes the entitlement, the target and the Operator that steps 3 onward need.

1. **`0. Start here`**: `login`, `getIdentity`, `rotateOwnCredential` (if `mustRotate`), `getIdentity`. The identity
   carries `organizations` (`OR-116`): the organizations and sub-organizations you can see, with ids and names (a node's display name, `OR-120`), by the
   same rule as the hierarchy reads below. It is `[]` while `mustRotate` is true (the hierarchy reads answer `428`
   until rotation), so call `getIdentity` again after rotating.
2. **`2. hierarchy`**: `listOrganizations` (captures `organizationId`), `listSubOrganizations` (`unitId`),
   `listEnvironments` (`environmentId`; the three hierarchy reads derive from your own bindings).
3. **`5. catalog`**: `loadPackage` — the body is the committed Cache package fixture
   (`contracts/validation/fixtures/cache-2.4.package.json`); needs `package.load` and an entitlement
   (`409 ENTITLEMENT_REFUSED` otherwise; see [Before loading a package](#before-loading-a-package)) — then `listSolutions` (`solutionId`, `packageVersion`).
4. **`6. targets`**: `listTargets` (`targetId`). Targets are registered over the API (`scanHostKeys`, `registerTarget`, `testTargetConnection`: the
   `Setup` folder); the operator command `TargetRegistrationMain` remains for install and emergency use.
5. **`7. instances`**: `declareInstance` (`POST /dp/environments/{environmentId}/instances`; fill in the placeholder
   `<...>` fields — container name, config directory, health port) captures `instanceId` and `placementId`;
   `listInstances`, `getInstance`.
6. **`8. designer`**: `getDesigner` (captures `stateVersion` and `generation`), `stageConfig` (edit the example
   `componentSelection`/`draftValues`/`rendered` from the designer's `viewModel`; the server re-checks them against
   your own projection and answers `422` on mismatch), `setConfigSecret`, `getConfigHistory`.
7. **`9. apply`**: `submitApply` (`generation` from the stage, `placementId` is required, `strategy: manual`) answers
   `202` with `applyId` and a `Location`; **`observeApply`** (enable the `applyId` query parameter, or use
   `sinceStateVersion`) until `terminal: true`. `rollbackApply`, `stopInstance` (Administrator and Operator, `OR-121`).
8. **`11. governance`**: `getHealth`, `getConfigLadder`, `readLogs`, `getLogLevel`/`setLogLevel`.
9. **`12. monitoring`** (served by P11-7.2; `declareMetricType` is the contracted `501`; the export streams CSV and is
   not yet counted proven until the storage gate records its 105 M-row run): `getCapabilities`, `listEntityTypes`, `getMetricsCatalog`,
   `queryRange`/`queryInstant`, dashboards. Dashboard writes need `X-SM-Dashboard-Revision` (captured by
   `getDashboard`); a stale one is `409 CONFLICT`.

`1. home` (`getHomeSummary`, `getHomeAttention`) and `10. fleet` (`submitFleetApply`, `observeFleetApply`) sit outside
the linear path.

## Errors: `application/problem+json`

Every error from the application is one RFC 9457 shape (`Problem`), with a server-generated `correlationId` equal
to the `X-Correlation-Id` response header (quote it in bug reports; it joins to the audit log):

```json
{ "type": "about:blank", "title": "Credential rotation required", "status": 428,
  "code": "MUST_ROTATE", "correlationId": "eb4be305-035a-499b-8cf9-7c9b4aefd0c8", "detail": "(optional)", "instance": "(optional)" }
```

**Switch on `code`, never on `title`, `detail` or the HTTP status alone** — several codes share a status
(`FORBIDDEN`, `CSRF_REJECTED`, `ORIGIN_REJECTED` are all 403). `404` is deliberately identical for "does not exist"
and "exists but outside your scope". `ErrorCode` is a closed enum in `console.yaml`. Problems never carry a
credential, hash, session or CSRF token. Requests the servlet container rejects before the application sees them
(a malformed request line, oversized headers) get the container's own response, without this shape.
Every `/api` response carries `Cache-Control: no-store`.

## stateVersion and collections

- Every response that carries a single entity's state carries **`stateVersion`** (integer, `Versioned`). It is the
  **only** ordering signal: compare nothing else, never timestamps. Mesh notices tell the console a higher version
  exists; REST stays authoritative and is refetched (or backfilled with `observeApply`'s `sinceStateVersion`).
- **Collections carry versions per item, never one for the whole list** (`SolutionList`, `InstanceList`,
  `ApplyObservationList`, `HomeSummary` ...). `ScopeNodeList`, `TargetList`, `LogPage`, `LogLevel`, `Health` (per
  placement) and `Identity` carry none.
- Optimistic concurrency: `stageConfig`, `setConfigSecret` and `acceptRuntimeConfig` take `baseStateVersion`
  (`409 CONFLICT` when stale); dashboard writes take the `X-SM-Dashboard-Revision` header.
- Responses may carry `availability` `{state: live | degraded | unavailable, mode: events | polling, reason}`. A
  degraded surface is declared, never silently empty: render the reason.
- Pagination is per operation, not global: `observeApply` (`sinceStateVersion`, `limit`), `readLogs` (`limit`,
  `before`, `after`, `placementId`), `listMetricInstances` (`search`; returns `{items, total}`). There is no
  cursor convention shared across the API.

## Regenerating

```bash
python3 contracts/openapi/postman/generate-postman.py     # rewrites the two JSON files in place
python3 contracts/openapi/postman/check-postman.py        # exit 0 = no drift, every operationId exactly once
```

Python 3 with PyYAML 6 only. The output is deterministic (no timestamps), so a clean tree after regeneration is the
test. Regenerate and commit whenever `console.yaml`, `ENDPOINT-INVENTORY.md`, the `@ConsoleOperation` handlers in
`backend/console-gateway` or the package fixture change. `check-postman.py` regenerates into a temp directory,
fails on any byte of drift, fails unless every `operationId` in `console.yaml` appears exactly once in the
collection's operation folders (and nothing else does; the `Setup` folder is the one allowed second use, checked against the
generator's `SETUP_STEPS`), checks each served/not-served marker against the inventory and the code, and
checks every `{{variable}}` is declared. The per-operation variable captures and the handful of body overrides
live in two small tables at the top of `generate-postman.py`, keyed by `operationId`; a new contract operation gets a
generic example body without touching them.

**It is wired into `contracts/run-checks.sh`** as step 15 (`python3 contracts/openapi/postman/check-postman.py`),
so a contract, inventory or handler change that leaves the collection stale fails the contract gate.

## Verified (2026-10-06)

Smoke-tested against a **real locally running launcher** (`SolutionManagerApplication` from commit `1e4e337e`'s
tree, PostgreSQL 14 in a local throwaway container, both migration steps run, mesh off), driven with `curl` and
Python `http.client` doing exactly what the collection does (login with `Origin`, cookie carry, `X-SM-CSRF` on
unsafe methods). The launcher listened on **8080** with no port configured. Results:

| Step | Result |
|---|---|
| `login` as `bootstrap-admin` | `200`, both `__Host-` cookies set, `mustRotate: true` |
| cookie jar over `http://localhost` (curl) | both `Secure` cookies stored and re-sent |
| `getIdentity` | `200`, 43 permissions after rotation |
| `listOrganizations` before rotation | `428 MUST_ROTATE` (problem body as documented) |
| `rotateOwnCredential` with `X-SM-CSRF` + `Origin` | `204`; `mustRotate` then `false` |
| `listOrganizations` | `200`, the install's organization |
| `listSubOrganizations` | `200 {"items":[]}` |
| `listSolutions`, `listTargets`, `getHomeSummary`, `getHomeAttention`, `listInstances` | `200` (empty) |
| `logout`, then `getIdentity` | `204`, then `401 UNAUTHENTICATED` |
| all other 32 operations then served, with zero-uuid ids | `404 NOT_FOUND` (resource absent) or `409`/`403` (entitlement / no fleet verb), **never `501`** |
| the 14 `/api/console/v1` operations (measured on `1e4e337e`, before P11-7.2 mapped them) | `404 NOT_FOUND`, not `501` (no route was mapped); **not re-measured on the merged tree** |

**Not verified:** the Postman application itself (Postman's cookie jar, the pre-request/test scripts running inside
Postman; the scripts were syntax-checked and their capture logic exercised under Node with a mock `pm`, not inside
Postman); any state-changing call beyond login, rotate and logout; anything needing data (a loaded package, a
target, an instance), because the throwaway database had none and entitlement is operator-provisioned. Response
bodies of data-bearing operations are therefore checked against the contract only by reading it.
