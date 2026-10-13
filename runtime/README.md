# runtime

**SM's own container packaging** — Dockerfiles, image layering, compose for local development. NOT the MI runtime (see the terminology note in the root README).

**Stack:** Docker · **Status:** the **launcher image** and its operations now exist (2026-10-07, `docs/plans/backend/015-history-implementation.md` P7.3) — recorded as `AGW-D22`, `PROPOSED`, because
the `RT-D*` family and `docs/design/runtime/` do not exist yet; re-home the decision when they do. Nothing else (the frontend image, a full-product compose) is here.

## Before writing code here

This component does not own its own architecture. Read, in order:

| What | Where |
|---|---|
| Cross-cutting decisions | [`../docs/design/DECISIONS.md`](../docs/design/DECISIONS.md) |
| Overall architecture | [`../docs/design/architecture.md`](../docs/design/architecture.md) |
| **This component's design** | `../docs/design/runtime/design.md` — **not yet written** |
| This component's decisions | `../docs/design/runtime/decisions.md`, ID family `RT-D*` |
| Approved plan | `../docs/plans/runtime/NNN-<slug>.md` — **none yet** |
| Setup checklist | [`../docs/checklists/new-module.md`](../docs/checklists/new-module.md) |

⚠️ **The baseline is UNBASELINED** — `SM-D*` decisions are PROPOSED and the design has open blocking items. Do not
start implementing against them. See [`../docs/tasks/codex-review-triage.md`](../docs/tasks/codex-review-triage.md).

**If the design is wrong or missing, raise it — do not invent architecture here.** Component-level decisions get an
ID in the `RT-D*` family and are recorded in the design folder, not in code comments.

## What is here

| Path | Contains |
|---|---|
| `launcher/Dockerfile`, `launcher/bin/` | The control-plane + data-plane + agent-gateway image. Base pinned **by digest**; non-root uid 10001; configuration by environment, with the CA keystore password read from a secret file (`SM_AGENT_CA_KEYSTORE_PASSWORD_FILE`); entrypoints `sm-launcher` (the application), `sm-migrate` (Phase 1, both schemas; `SmControlMigrationMain` then `SmDataMigrationMain`, which also runs `sm_metrics`), `sm-healthcheck` (TCP readiness), `sm-tool` (operator tools with the runtime passwords), `sm-secrets.sh` (the shared secret-file reader they source). |
| `scripts/build-image.sh` | The only supported way to build it. `--check` builds twice with `--no-cache` and fails unless the OCI manifest digests match (the same tree, base digest and `SOURCE_DATE_EPOCH` give the same image). |
| `launcher/NOTICE` | Copied with `backend/THIRD-PARTY-NOTICES.md` (BoringSSL, OpenSSL clause 3) to `/usr/share/doc/sm/` in the launcher image (`OR-127` L3). |
| *(agent image)* | The agent image is built from `go-agent/Dockerfile` and its NOTICE lives **in the agent PR** under `go-agent/`, not here. |
| `compose.yml`, `.env.example`, `bootstrap/phase0.sh` | A single-host deployment: `db`, one-shot `bootstrap` (Phase 0, superuser), one-shot `migrate` (Phase 1, migrator roles), `launcher` (runtime roles only). The gateway private key, the operator CA keystore and its password are compose `secrets` (read-only files from the gitignored `./certs`), the five database passwords and the data-plane master key (`dp_master_key`, read through `SM_DP_MASTER_KEY_FILE`; its label is `SM_DP_MASTER_KEY_ID` in `.env`) are compose `secrets` generated into the gitignored `./secrets` (`OR-138`); none is an environment value. The Phase 0 SQL is mounted from where it lives, never copied. |
| `scripts/init-secrets.sh` | Generates the five database password files and the data-plane master key (32 random bytes, base64, 44 characters, no newline; back it up, losing it loses every sealed secret) and rotates the four role passwords (below). |

## Install sequence and database passwords (`OR-138`)

The superuser password and the four role passwords (control and data, migrator and runtime) are **generated, never typed and never in `.env`**:

```
cp .env.example .env                  # fill the remaining values (no password is among them)
scripts/init-secrets.sh               # once: ./secrets (0700) with five 0600 files of 48 random alphanumeric characters
# Linux: a bind-mounted file keeps its host owner, group and mode. To let the image's user (uid 10001) read the four ROLE
# files, give them that GROUP (owner stays you, mode becomes 0640; the directory stays 0700 and db_superuser_password stays 0600):
#   sudo scripts/init-secrets.sh --group 10001 --dir ./secrets      (sudo drops SM_SECRETS_DIR: use --dir or set it in .env)
docker compose --profile bootstrap run --rm bootstrap    # Phase 0
docker compose --profile migrate  run --rm migrate       # Phase 1
docker compose up -d launcher
```

* `init-secrets.sh` runs on the **host**, not as a compose job: compose creates its secret mounts from files that must already exist, and `db` needs `db_superuser_password` before any job could run. It never replaces an existing file, so re-running it changes nothing (it warns about an existing file that is not a plain 48-character alphanumeric file, and errors on a dangling symlink); each file is written to a temp file and linked into place. The directory is `--dir`, else `SM_SECRETS_DIR` from the environment, else `SM_SECRETS_DIR` from `.env` (read, not sourced), else `./secrets`; a relative value is relative to this directory, as compose reads it.
* **Back up `./secrets` like a key.** It is not configuration; losing it means resetting the roles' passwords by hand with the superuser, and a copy of it is a database credential.
* `bootstrap` never sends a plaintext password to the server: it computes the SCRAM-SHA-256 verifier itself and sends `ALTER ROLE ... PASSWORD 'SCRAM-SHA-256$...'` on stdin, in one transaction, under session settings that keep the statement out of the log. If Phase 0 fails **after** its SQL ran, the roles exist without passwords: run `scripts/init-secrets.sh rotate all`.
* `migrate` and `launcher` receive their files as compose secrets (`/run/secrets/...`) through `SM_*_PASSWORD_FILE`, read by `bin/sm-secrets.sh` (a regular, readable file whose content is non-empty after reading; exit 64 otherwise) into the JVM's own environment. `docker inspect` therefore shows no password; the value is visible in that process's environment (`/proc/<pid>/environ`) to uid 10001 or host root, no wider than the secret file itself. Spring `configtree:` would remove even that, later.
* **Operator tools** (`SmControlLockoutRecoveryMain`, `BrokerConnectionMain`, `KeyRotationMain`, `TargetRegistrationMain`, `TargetConnectionMain`) read the runtime passwords from the environment, which `docker compose exec launcher java ...` no longer carries. Run them through the wrapper: `docker compose exec launcher /opt/sm/bin/sm-tool <main-class> [args...]`.
* **Rotation:** `scripts/init-secrets.sh rotate all` (or one or more of `cp_migrator cp_runtime dp_migrator dp_runtime`; duplicates are collapsed, `all` takes no further arguments, a lock in the secrets directory refuses a concurrent rotation). It writes `<role>_password.next` (copying the live file's owner, group and mode), applies all named roles in **one transaction**, and only after the database accepted it replaces the live files. If it fails, the live files are untouched; **re-running `rotate` is the recovery in every case**. Rotating a **runtime** role leaves the running launcher holding the old password, so its new connections fail until it is recreated: `docker compose up -d --force-recreate launcher`. That is a maintenance action (the console and gateway restart, with a drain); the script prints the command with a prominent warning and does **not** do it. `migrate` reads its files on each run. The superuser password is the database container's own init secret and is not rotated by this script.
* Future option, not built: make the two migrator roles `NOLOGIN` between upgrades (enable at `migrate` time), so a leaked migrator password is useless outside an upgrade window.
* Limit: generated passwords are ASCII; do not replace a file with a non-ASCII password (the verifier is computed without SASLprep).

## How it is verified

`ContainerPackagingIT` (`backend/launcher/src/test/java/com/solace/sm/launcher/`; it runs only under `-Pagent-live`) builds the image and exercises it against a real PostgreSQL 14 and the real Go agent: reproducible
build, Phase 0/1 as one-shot containers, startup as non-root with runtime credentials only, enrolment, the authenticated management endpoint (and the absence of any actuator
route), `docker stop` (drain), a drain with an agent that never answers, `docker kill -9`, container recreation, missing TLS material, and `docker compose config`. Operator runbooks are in [`../docs/runbooks/backend/`](../docs/runbooks/backend/) (plan 015 T5.2; gate G-A1's evidence is [`../docs/plans/backend/evidence/015-g-a1.md`](../docs/plans/backend/evidence/015-g-a1.md)).

## Operating limits to read before relying on this

- One gateway replica. No multi-replica deployment is supported or tested.
- `docker stop` exits with **143** (SIGTERM) after a clean drain; that is the JVM's convention for a signal-initiated shutdown, not a failure. A non-zero exit *with* `agent-gateway drained:` in the log is normal.
- The shutdown timeout contract: container stop grace period > `SM_SHUTDOWN_TIMEOUT` (60 s) > `SM_AGENT_GATEWAY_DRAIN_WINDOW` (30 s) + `shutdown-grace` (5 s).
- Agent client certificates live 30 days (`SM_AGENT_CA_LEAF_TTL`, passed through by `compose.yml`); rotation is manual in v1 and nothing alerts on expiry yet — put it in your own monitoring.
- Enrolment tokens live 24 hours unless the issuer asks for longer, up to 30 days (`SM_AGENT_ENROLMENT_TOKEN_DEFAULT_VALIDITY`, `SM_AGENT_ENROLMENT_TOKEN_MAX_VALIDITY`; OR-136 (1)). The launcher refuses to start when the default exceeds the maximum.
- Two scheduled jobs keep an agent installation tidy without anybody reading a page (OR-140): the **enrolment sweep** retires an enrolment the agent never completed (revokes its registration, then retires its target) once the token expired and `sm.dp.declare.abandon-after` (ten minutes) has passed, and the **staleness job** marks a silent agent's placements `unreachable` (and fires `instance.health`) once no report arrived within `sm.dp.agent.report-staleness` (five minutes). Both run under a system actor, so the audit trail says "agent-jobs", never the person who issued the token. Controls: `SM_AGENT_SWEEP_ENABLED` / `SM_AGENT_SWEEP_INTERVAL` (default `true` / `PT1M`; properties `sm.gateway.agent-sweep.*`) and `SM_AGENT_STALENESS_JOB_ENABLED` / `SM_AGENT_STALENESS_JOB_INTERVAL` (default `true` / `PT30S`; properties `sm.dp.agent.staleness-job.*`). With the sweep disabled an interrupted enrolment stays listed as degraded until an operator revokes the agent and retires the target; with the staleness job disabled a silent agent's last verdict stands. A read (`listTargets`, `getHealth`) no longer does either. Both jobs run whether or not the agent gateway is enabled: with the gateway switched off, silent agents correctly go `unreachable`, and the cost is one indexed query per interval.

## Owed (plan 015 T2.4)

- **The agent image is `go-agent/Dockerfile`, in the agent PR.** It must pin its base by digest (measured 2026-10-10:
  `gcr.io/distroless/static-debian13:nonroot@sha256:e2e927ec666bae08560abb3c55d0659eceabb657f56b6782ab500a9fc7f555e3`, the multi-platform index; the tag moves)
  and carry its own NOTICE (the Apache-2.0 NOTICE files of the compiled-in modules) and `THIRD-PARTY-LICENCES.md`.
- The corresponding-source offer for both images is in the release runbook, [`../docs/runbooks/backend/release-the-images.md`](../docs/runbooks/backend/release-the-images.md); its contact and wording are the owner's to confirm.
- Compose sets environment names (`SM_AGENT_GATEWAY_*`, `SM_AGENT_CA_*`, `SM_SHUTDOWN_TIMEOUT`) that `backend/launcher/src/main/resources/application.properties` maps (plan 015 T3.2). Mapped but not passed by compose (set it through a compose override `environment:` entry): `SM_DP_AGENT_MAX_PLACEMENTS_PER_TARGET`. Not mapped at all (an overlay property only): `sm.agent.gateway.drain-reconnect-min` and `-spread`.
