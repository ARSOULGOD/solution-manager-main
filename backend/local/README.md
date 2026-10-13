# Local stack: the production backend on PostgreSQL, for end-to-end API testing (OR-118)

One script brings up the **real** production launcher (`SolutionManagerApplication`) against a throwaway PostgreSQL 14, then
**sets the install up through the API**, as the bootstrap administrator: the organization named, a sub-organization and environment
hierarchy (ACME), a Cache entitlement, an environment-bound Operator and (optionally) your E2E host registered as an SSH-Docker
target, so you can drive every served API from Postman. One command tears it all down. Since plan 013 T4.1 **there is no seed SQL**:
the only database work is provisioning (Phase 0 and the two migration steps).

> Local development only. It generates throwaway credentials, binds the launcher and PostgreSQL to `127.0.0.1`, and runs with
> the mesh off unless you configure a broker. A local Cache is **not** a target (there is no local SSH harness): apply and
> health need a real SSH-Docker host.

## Prerequisites

- Docker (the daemon must answer `docker info`; set `DOCKER_HOST` if it is not the default socket)
- JDK 17 or newer, Maven (offline works when `~/.m2` is warm; `SM_LOCAL_MVN_OPTS=` with no value builds online)
- `curl`, `unzip`, a 2 GiB free heap (Argon2id login hashing)
- Optional: the target file (below). Without it everything works except the target, so there is nothing to apply to.

## One command

```bash
backend/local/sm-local.sh up        # build (first time), PostgreSQL, migrations, launcher, API seed, target
backend/local/sm-local.sh migrate   # apply newly pulled migrations without removing the PostgreSQL volume
```

`up` is idempotent: run again on a running stack and it reports each step as already done. It prints what it did.
First run builds the launcher jar (a few minutes), later runs skip the build (`SM_LOCAL_REBUILD=1` forces it).

> **After pulling a new migration**, run `backend/local/sm-local.sh migrate`. It applies pending `sm_control` and `sm_data`
> migrations to the existing PostgreSQL database and keeps the container, volume, credentials, and seeded data. `up` records
> the initial migration step in `.state/migrated.done` and does not rerun it automatically; `migrate` is the explicit upgrade
> path for that case. Use `down && up` only when you intentionally want to destroy and recreate the local database.
> The same applies to the agent-gateway migrations introduced by `origin/UAT`: run `migrate` against an existing volume before
> restarting the launcher. Use `down && up` only when you intentionally want to destroy and recreate the local database.
> The same applies when a migration that is still unpublished changes after you have already run it: a persistent volume built
> from the earlier `V015` fails Flyway's checksum validation, so run `down` before `up`.

| Command | Does |
|---|---|
| `up` | everything above, in order: provisioning, launcher, then the API seed and the target |
| `seed` | run the API seed steps that have not run (the launcher must be running); each step is replay-safe, so a failed seed is simply run again |
| `status` | container, launcher and which steps are done |
| `stop` / `start` | stop or start only the launcher (the database and its seed stay) |
| `bodies` | print the `declareInstance` body filled in from your target file (no secret) |
| `down` | stop the launcher, remove the PostgreSQL container **and its volume**, delete `backend/local/.state/` |
| `check` (or `--check`) | validate prerequisites and the target file; start nothing, contact nothing |

`backend/local/test-sm-local.sh` is the automated check of that `check` mode.

Defaults, all overridable by environment: `SM_LOCAL_PG_PORT=55440`, `SM_LOCAL_PORT=8080`, `SM_LOCAL_BIND=127.0.0.1`,
`SM_LOCAL_TRUSTED_ORIGIN=http://localhost:$SM_LOCAL_PORT` (set this to the browser frontend origin, such as `http://localhost:5173`),
`SM_LOCAL_ADMIN_PASSWORD` (the bootstrap credential), `SM_LOCAL_ADMIN_NEW_PASSWORD` (what it is rotated to), `SM_LOCAL_OPERATOR_USER=local-operator`, `SM_LOCAL_OPERATOR_PASSWORD`, `SM_LOCAL_ENV_FILE` (a file
sourced first, e.g. `DOCKER_HOST`/`JAVA_HOME`), `SM_LOCAL_TARGET_ENV`, `SM_LOCAL_HEAP=-Xmx2g`. The mesh is off; set
`SM_LOCAL_MESH_HOST`, `SM_LOCAL_MESH_VPN`, `SM_LOCAL_MESH_USERNAME`, `SM_LOCAL_MESH_PASSWORD` together to turn it on.

## What `up` does

1. **PostgreSQL 14** as container `sm-local-pg`, named volume `sm-local-pgdata`, published on `127.0.0.1:55440` only. The
   superuser and four role passwords are **generated on first run** into `backend/local/.state/secrets.env` (mode 600,
   gitignored) and never printed.
2. **Phase 0** (`sm_control_phase0.sql`, `sm_data_phase0.sql`, run as the superuser) and the **two migration steps**
   (`SmControlMigrationMain`, `SmDataMigrationMain`): the same entry points the launcher integration tests and the storage
   gate use. The bootstrap admin comes from migration `V007`.
3. **The launcher** on `http://localhost:8080` with `/api` as the context, mesh off, `sm.gateway.trusted-origins[0]` =
   `http://localhost:8080` (exactly the Postman environment's `origin`), the master key generated once into
   `.state/master.key`. It runs in the background; the log is `backend/local/.state/launcher.log`. It starts **before** the seed,
   because the seed is an API client.
4. **The API seed** (`LocalSeed.java`, an HTTP client of the launcher; no SQL), signed in as `bootstrap-admin`, in this order:
   1. `login`, then `rotateOwnCredential` (the migration's credential is single-use; every other route answers `428` until it is rotated);
   2. `renameOrganization` (the install's one organization becomes **ACME**: an install-bound call, which the bootstrap administrator
      is; its id, `00000000-0000-4000-8000-000000000011`, never changes);
   3. `createSubOrganization` **ACME-Sub-1** and **ACME-Sub-2**, each with `createEnvironment` **Dev**, **UAT**, **Prod**
      (the lists are ordered by name, so **ACME-Sub-1** and **Dev** come first);
   4. `setEntitlement` for the `cache` solution (up to 5 instances). Since `OR-148` a first package load would entitle its organization on its own (default limit 10, `sm.cp.entitlement.on-first-load.*`); the seed still sets this one so the limit is 5;
   5. `createUser` for the Operator (a provisional credential only the seed knows), `listRoles`, `grantAssignment` of **Operator** at
      ACME-Sub-1 / Dev, then the Operator signs in and rotates the provisional credential to the one it keeps;
   6. when the target file is complete: `scanHostKeys` (the seed refuses to go on unless one offered key has the fingerprint **you**
      put in the target file), `registerTarget` (owned at ACME-Sub-1 / Dev) and `testTargetConnection`. **Unlike the old row-only
      seed, this connects to the host**: the scan and the test open SSH connections, and registration pre-checks the address. The host
      must be reachable from where the launcher runs.

   Every step is replay-safe, so `sm-local.sh seed` after a failure resumes. The same walk is the Postman collection's **Setup**
   folder, which you can run by hand against any install.

## Credentials

A username is unique per organization (`OR-125` (4)), so a login can name the **organization** as well as the username, but it is
optional (`OR-131` Q3): this stack holds exactly one organization, **ACME** once the seed has named it (its id,
`00000000-0000-4000-8000-000000000011`, always works too), so a login that omits it signs in to ACME. Both the administrator and
the operator below sign in to it. If you add a second organization, an omitted `organization` becomes a `400`.

| Who | Username | Password | Notes |
|---|---|---|---|
| Bootstrap administrator | `bootstrap-admin` | **before the seed:** `SM_LOCAL_ADMIN_PASSWORD`, else generated (single-use, never shown). **After the seed:** `SM_LOCAL_ADMIN_NEW_PASSWORD`, else generated and kept in `.state/secrets.env` (0600; the path is printed, the value is not) | the seed rotates the single-use credential (the first login answers `mustRotate: true`, and every route but `getIdentity`, `rotateOwnCredential`, `logout` answers `428` until rotation) |
| Operator, bound at the seeded environment | `local-operator` | `SM_LOCAL_OPERATOR_PASSWORD`, else generated and kept in `.state/operator.pw` (0600; the path is printed) | no forced rotation; for checking what a scoped user sees |

Passwords must be 12 or more characters. **The administrator's current password is kept in `backend/local/.state/secrets.env`**
(mode 600, gitignored, removed by `down`), because the seed is replayable and signs in again each time; the single-use initial
credential is dropped from it once the rotation is recorded. The Operator's password is only ever printed. If you miss a printed
value, read the administrator's from that file, or `down` and `up` again.

## The target file (outside the repository)

The target's coordinates and key path come **only** from a file you keep outside the repo,
`${SM_LOCAL_TARGET_ENV:-$HOME/.sm-local/target.env}` (create it with `chmod 600`). It is `source`d by the script at run time:

```bash
SM_LOCAL_TARGET_HOST=...            SM_LOCAL_TARGET_PORT=22
SM_LOCAL_TARGET_USER=...            SM_LOCAL_TARGET_FINGERPRINT=SHA256:...   # the host key to pin (any ONE of its keys: ed25519, ecdsa or rsa)
SM_LOCAL_TARGET_KEY_FILE=...        # path of the SSH private key file (read once, sent to registerTarget, sealed in the secret store)
SM_LOCAL_TARGET_CONTAINER_PREFIX=...  SM_LOCAL_TARGET_CONFIG_ROOT=...        # the envelope every declare is confined to
SM_LOCAL_CACHE_CONTAINER=...        SM_LOCAL_CACHE_CONTAINER_CONFIG_PATH=...  SM_LOCAL_CACHE_FILE_NAME=...
SM_LOCAL_CACHE_HEALTH_PORT=...
SM_LOCAL_CACHE_BROKER_HOST=...      SM_LOCAL_CACHE_BROKER_VPN=...
SM_LOCAL_CACHE_BROKER_USERNAME=...  SM_LOCAL_CACHE_BROKER_PASSWORD=...
```

Which key to pin. Any key the host offers works (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`, or the `_ecdsa_` or `_rsa_`
file, run on the host): the connectors work out the key's type themselves (review W4C-1; before that fix only the Ed25519 key,
or whichever type sshj prefers among the host's keys, could be pinned, and a pin to another failed every apply, health probe and
log read as `ssh.connection.failed`). If you are running an older build, pin the Ed25519 key.

The first seven (the port is optional) drive registration; the `SM_LOCAL_CACHE_*` values are only for you to fill the request bodies (`sm-local.sh bodies`
prints the `declareInstance` body from them; the broker password is never printed, you paste it into `setConfigSecret` yourself).
If the file is missing or incomplete, `up` still works and says `TARGET SKIPPED`. Add the file later and run `sm-local.sh seed`.
Never copy a real host, user, key, broker address or password into the repository.

## Postman walkthrough

Import `contracts/openapi/postman/solution-manager.postman_collection.json` and `solution-manager.local.postman_environment.json`
(see `contracts/openapi/postman/README.md`), select the environment **Solution Manager - local** (`baseUrl`
`http://localhost:8080/api`, `origin` `http://localhost:8080`) and set `username` and `password` to the admin's (the `login` request omits `organization`: this stack has one). Use the
administrator for the walkthrough: it holds every permission. Each step's test script captures the ids the next one needs.

`up` has already walked the collection's **Setup** folder for you except `loadPackage` (rotation, ACME and its hierarchy, the
entitlement, the Operator, the target; the package is row 4 below), so here `login` answers `mustRotate: false` with the password the seed rotated to. To see the walk itself, run the
**Setup** folder against a second install (or `down` and read the folder before `up`): it is the same sequence by hand, with the host
key a decision you make rather than a default.

| # | Request (folder) | Expect |
|---|---|---|
| 1 | `login` (0. Start here) | `200`, `mustRotate: false` (the seed rotated it), two `__Host-` cookies, `csrfToken` captured |
| 2 | `getIdentity` | `200`, the 43 permissions (each at install scope), and `organizations` (`OR-116`): **ACME** with **ACME-Sub-1** and **ACME-Sub-2** beneath it, as the administrator sees them |
| 3 | `listOrganizations`, `listSubOrganizations`, `listEnvironments` (2. hierarchy) | `200` each: **ACME**, then its two sub-organizations, then the three environments of each (**Dev**, **Prod**, **UAT**: the lists are ordered by name); the ids of ACME-Sub-1 and its Dev are captured, being first |
| 4 | `loadPackage` (5. catalog) | `201` with `solutionId`. The body is `{"package": <fixture>}`, where the fixture is `contracts/validation/fixtures/cache-2.4.package.json`; the collection pre-fills it. With curl: wrap it (`python3 -c 'import json;print(json.dumps({"package":json.load(open("cache-2.4.package.json"))}))'`) and send with `X-SM-CSRF` and `Origin` |
| 5 | `listSolutions` | `200`, the `cache` solution, `packageVersions: [1]` (`solutionId` and `packageVersion` captured) |
| 6 | `listTargets` (6. targets) | `200`, your target (`kind docker`, `transport ssh-docker`, owned at the seeded environment, with the envelope); `targetId` captured |
| 7 | `declareInstance` (7. instances) | `201`. Replace the `<...>` placeholders with the output of `sm-local.sh bodies`. `containerName` must start with the container prefix and `configDirectory` must be inside the config root, else `4xx` |
| 8 | `getDesigner` (8. designer) | `200`, the Cache designer view model; captures `stateVersion` and `generation` |
| 9 | `stageConfig` | `200`. Edit the example `componentSelection`, `draftValues` and `rendered` from the designer's `viewModel` (the server re-checks them against its own projection: `422` on mismatch). Put the Cache's broker connection values here; `setConfigSecret` takes the broker password |
| 10 | `submitApply` (9. apply) with `strategy: manual` | `202`, `applyId` and a `Location`. **This changes the target host** (see warnings) |
| 11 | `observeApply` | enable the `applyId` query parameter and repeat until `terminal: true` |
| 12 | `getHealth` (11. governance) | `200`, per-placement health once the Cache is reachable |
| 13 | `readLogs`, `getLogLevel` | `200` (set `component` from the designer) |
| 14 | `getMetricsCatalog`, `queryRange` (12. monitoring) | the catalog answers straight away; `queryRange` returns points only after the collector has run for a few minutes (`getCapabilities` shows the collector's health) |
| 15 | `logout` | `204` |

### Warnings

- **Applying CHANGES the target host.** `submitApply` overwrites the Cache's configuration file in the config root and may
  restart the Cache container; SM also leaves hidden retained pre-image files (`.<file>.sm-preimage-<generation>`) in that
  directory. Point it at a host you are willing to change, with a Cache you can restart.
- **Undo paths.** `rollbackApply` (9. apply) rolls back to a prior generation; `restoreRuntimeConfig` (8. designer) re-asserts the desired
  configuration onto the host; `acceptRuntimeConfig` takes observed drift as the new intended state. If SM cannot undo it,
  restore the file from the pre-image on the host and restart the container yourself.
- The mesh is off, so no mesh notices are published; REST is authoritative (`observeApply` with `sinceStateVersion`).
- A `404` for a route that exists usually means the id is outside your scope (the operator sees less than the admin), by design.
- Postman and the `__Host-` `Secure` cookies over plain `http://localhost`: see `contracts/openapi/postman/README.md` (`cookieMode=manual` is the fallback).

## G-A2: the E2E-host driver (plan 015 T5.3, `OR-134` A9, `OR-145`)

`ga2-host.sh` runs the real Go agent **on the E2E host**, beside the host's existing Cache, enrolled to the local stack through a loopback reverse tunnel, and
(`OR-145`) measures dp-remote coexistence: a second, unlabelled Cache on the same host is declared on the host's SSH-Docker target and applied while the agent
runs and again while it is stopped. It extends the G-A1 harness (`ga1-agent.sh`, `ga1-scenario.py`); the local stack, PKI, scenario and assertions are those.

- **The host is reached only through `$GA2_HOST_CMD`** (default `~/.sm-local/e2e-host.sh`): `run '<cmd>'`, `put <local> <remote>`, `tunnel <rport> <lport>`, with
  `@ROOT@` and `@PREFIX@` substituted by it. The driver never uses ssh, scp or `docker -H`, never reads a target file, and writes only under `@ROOT@/ga2-<run>/`.
- **No secret on any command line.** The broker password, the enrolment token and the Cache configs (which hold the password, as the existing Cache's does) reach
  the host on stdin only; the agent reads the token out of a 0600 file into its own environment and the file is removed at once. The broker values come from the
  integrator's environment: `SM_E2E_BROKER_HOST` (`tcp://h:p`), `SM_E2E_BROKER_VPN`, `SM_E2E_BROKER_USERNAME`, `SM_E2E_BROKER_PASSWORD`; all four are masked in every line.
- **Existing containers stay untouched.** Before anything the driver snapshots every host container that lacks this run's label (name, id, `StartedAt`, status,
  health) and the existing Cache's `/actuator/health` code; the same is asserted before the clean-up ends. `docker restart|stop|rm|kill|exec|start` is refused on a
  container without the run label `com.solace.sm.ga2.run=<run>` (one helper), and a created container must carry it.
- **Clean-up always runs** (trap on EXIT/INT/TERM in `all`; also `ga2-host.sh down`, idempotent): the agent, the labelled containers, the run directory, the tunnel
  (its port is asserted closed), the snapshot assertion, then the local stack.

Run it (the integrator; the real host):

```bash
source <scratchpad>/tc-env.sh                                    # Docker Desktop, never Colima
export SM_E2E_BROKER_HOST=tcp://<host>:<port> SM_E2E_BROKER_VPN=<vpn> SM_E2E_BROKER_USERNAME=<user>    # and the password, without echoing it:
read -rs SM_E2E_BROKER_PASSWORD; export SM_E2E_BROKER_PASSWORD
export GA2_KNOWN_FINDINGS=m3                                     # the M3 finding (G-A1) is known: record it and continue
<scratchpad>/docker-lock.sh ga2 backend/local/ga2-host.sh all 2>&1 | tee <outside-the-repo>/ga2-run.log
backend/local/ga2-host.sh evidence <outside-the-repo>/ga2-run.log > <redacted-skeleton>.md
```

`all` exits 0 (every assertion PASS), 3 (a known FINDING was recorded; everything else PASS) or 1 (a FAIL, or the host's existing containers changed). Step order:
`setup enrol apply1 metrics remote_declare remote_apply_agent_up remote_apply_agent_down remote_undeclare killsm undeclare drain revoke retire`. The four `remote_*`
steps need the SSH-Docker target (`GA1_TARGET=on`, the default here: `sm-local.sh` registers it from the target file as it does for G-A1's seed).

Local dry run (no host is contacted): `ga2-dry-run.sh` points the driver at `e2e-host-stub.sh` (the same interface; `run` is a local `bash -c`, `@ROOT@` a local
directory) and a throwaway broker, with a synthetic password, the target OFF, and every step except the four `remote_*`. `test-ga2-host.sh` is the static check
(no ssh/scp/`docker -H`, no secret inside a remote command string, a refused `down` on an empty run id, the label guard, G-A1's stage body unchanged).

## When you are done

```bash
backend/local/sm-local.sh down      # launcher, container, volume and .state/: nothing left behind
```

`down` never touches your target file or the target host. The Cache config the apply wrote stays on the host: restore it as above.
