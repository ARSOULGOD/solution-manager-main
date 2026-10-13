#!/usr/bin/env bash
#
# OR-118, plan 013 T4.1: a one-command LOCAL stack for end-to-end API testing: PostgreSQL 14 in Docker, the role setup and both
# migration steps, the production launcher on port 8080, and then EVERYTHING ELSE THROUGH THE API as the bootstrap administrator -
# the single-use credential rotated, the organization named, a sub-organization / environment hierarchy, a Cache entitlement, an
# environment-bound Operator and (optionally) the E2E host registered as an SSH-Docker target (scan, register, test). No seed SQL:
# the only database work left is provisioning (Phase 0 and the migrations). The Postman collection's "Setup" folder is the same walk.
# Read backend/local/README.md first. Local development only: it generates throwaway credentials and binds to 127.0.0.1.
#
# Commands:
#   up        build (once), PostgreSQL, migrations, launcher, API seed, target. Idempotent.
#   build     build the launcher jar and unpack its classpath
#   seed      (re)run the API seed steps that have not run (needs the launcher running): rotation, hierarchy, entitlement, Operator, target
#   migrate   apply pending control/data migrations to the existing database (keeps the volume and state)
#   start     start the launcher if it is not running        stop   stop the launcher
#   status    what is running                                down   stop, remove the PostgreSQL container, volume and .state/
#   bodies    print the declareInstance body for the target file (no secret)
#   check     validate prerequisites and the target file; run nothing, contact nothing      (also: any command with --check)
#
# Environment (all optional):
#   SM_LOCAL_PG_PORT (55440)   SM_LOCAL_PG_CONTAINER (sm-local-pg)   SM_LOCAL_PG_VOLUME (sm-local-pgdata)   SM_LOCAL_PORT (8080)   SM_LOCAL_BIND (127.0.0.1)
#   SM_LOCAL_TRUSTED_ORIGIN (http://localhost:$SM_LOCAL_PORT)   the gateway's one trusted Origin; set this when a
#                              separate frontend dev server (not Postman same-origin) is the caller, e.g. https://localhost:8080
#   SM_LOCAL_ADMIN_PASSWORD    the bootstrap admin's single-use initial credential (else generated and printed once)
#   SM_LOCAL_ENTITLED_SOLUTIONS (cache file-mesh)   space-separated solutions to entitle ACME for, 5 instances each
#   SM_LOCAL_OPERATOR_USER (local-operator)   SM_LOCAL_OPERATOR_PASSWORD (else generated and printed once)
#   SM_LOCAL_ADMIN_PASSWORD    the bootstrap admin's single-use INITIAL credential (else generated)
#   SM_LOCAL_ADMIN_NEW_PASSWORD the credential the script rotates it to (12+ characters; else generated, printed once and kept in .state/secrets.env)
#   SM_LOCAL_OPERATOR_USER (local-operator)   SM_LOCAL_OPERATOR_PASSWORD (12+ characters; else generated and printed once)
#   SM_LOCAL_TARGET_ENV        the target file, OUTSIDE the repo (default $HOME/.sm-local/target.env); see the README
#   SM_LOCAL_MESH_HOST SM_LOCAL_MESH_VPN SM_LOCAL_MESH_USERNAME SM_LOCAL_MESH_PASSWORD   enable the mesh (all four); else it is off
#   SM_LOCAL_ENV_FILE          a file to source first (DOCKER_HOST, JAVA_HOME ...); it may NOT change the PostgreSQL names/port below
#   SM_LOCAL_STATE_DIR         the state directory (default backend/local/.state); a second stack uses its own so it never shares a launcher PID or secrets
#   SM_LOCAL_PG_LABEL          key=value: label the PostgreSQL container and volume with it, and remove only a container/volume that carries it
#   SM_LOCAL_MVN (mvn)  SM_LOCAL_MVN_OPTS (-o)  SM_LOCAL_REBUILD=1 to rebuild   SM_LOCAL_HEAP (-Xmx2g)

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
# Overridable so a second stack (the G-A1 gate, ga1-agent.sh) never shares this stack's launcher PID, secrets or markers; the default is unchanged.
STATE="${SM_LOCAL_STATE_DIR:-$here/.state}"
SECRETS="$STATE/secrets.env"

# Overridable so a second stack never touches a stack that already holds these names; the defaults are unchanged. Resolved BEFORE the
# env file is sourced, and an env file that changes them is refused (it could point a second stack's `down` at the default stack).
PG_CONTAINER="${SM_LOCAL_PG_CONTAINER:-sm-local-pg}"
PG_VOLUME="${SM_LOCAL_PG_VOLUME:-sm-local-pgdata}"
PG_PORT="${SM_LOCAL_PG_PORT:-55440}"
PG_LABEL="${SM_LOCAL_PG_LABEL:-}"
if [[ -n "${SM_LOCAL_ENV_FILE:-}" ]]; then
  source "$SM_LOCAL_ENV_FILE"
  if [[ "${SM_LOCAL_PG_CONTAINER:-sm-local-pg}" != "$PG_CONTAINER" || "${SM_LOCAL_PG_VOLUME:-sm-local-pgdata}" != "$PG_VOLUME" \
        || "${SM_LOCAL_PG_PORT:-55440}" != "$PG_PORT" ]]; then
    printf 'error: SM_LOCAL_ENV_FILE sets SM_LOCAL_PG_CONTAINER/VOLUME/PORT to values other than this run resolved; refusing\n' >&2; exit 1
  fi
fi
APP_PORT="${SM_LOCAL_PORT:-8080}"
BIND="${SM_LOCAL_BIND:-127.0.0.1}"
DB=sm
TARGET_ENV="${SM_LOCAL_TARGET_ENV:-$HOME/.sm-local/target.env}"
OPERATOR_USER="${SM_LOCAL_OPERATOR_USER:-local-operator}"
CP="$STATE/app/BOOT-INF/classes:$STATE/app/BOOT-INF/lib/*"

say() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }
rand() { { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${1:-24}"; } || true; }

check_only=false
args=()
for a in "$@"; do
  case "$a" in
    --check) check_only=true ;;
    -h|--help) sed -n '2,29p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) args+=("$a") ;;
  esac
done
cmd="${args[0]:-}"
[[ "$cmd" == "check" ]] && check_only=true && cmd=up

# ---- prerequisites -----------------------------------------------------------------------------------------------------
target_vars=(SM_LOCAL_TARGET_HOST SM_LOCAL_TARGET_PORT SM_LOCAL_TARGET_USER SM_LOCAL_TARGET_FINGERPRINT SM_LOCAL_TARGET_KEY_FILE
  SM_LOCAL_TARGET_CONTAINER_PREFIX SM_LOCAL_TARGET_CONFIG_ROOT SM_LOCAL_CACHE_CONTAINER SM_LOCAL_CACHE_CONTAINER_CONFIG_PATH
  SM_LOCAL_CACHE_FILE_NAME SM_LOCAL_CACHE_HEALTH_PORT SM_LOCAL_CACHE_BROKER_HOST SM_LOCAL_CACHE_BROKER_VPN
  SM_LOCAL_CACHE_BROKER_USERNAME SM_LOCAL_CACHE_BROKER_PASSWORD)
required_for_registration=(SM_LOCAL_TARGET_HOST SM_LOCAL_TARGET_USER SM_LOCAL_TARGET_FINGERPRINT SM_LOCAL_TARGET_KEY_FILE
  SM_LOCAL_TARGET_CONTAINER_PREFIX SM_LOCAL_TARGET_CONFIG_ROOT)

prereqs() {
  local problems=()
  command -v docker >/dev/null || problems+=("docker is not on PATH")
  docker info >/dev/null 2>&1 || problems+=("the Docker daemon does not answer (set DOCKER_HOST or SM_LOCAL_ENV_FILE)")
  command -v java >/dev/null || problems+=("java is not on PATH (JDK 17 or newer)")
  command -v "${SM_LOCAL_MVN:-mvn}" >/dev/null || [[ -d "$STATE/app" ]] || problems+=("maven is not on PATH")
  command -v curl >/dev/null || problems+=("curl is not on PATH")
  command -v unzip >/dev/null || problems+=("unzip is not on PATH")
  command -v openssl >/dev/null || true
  if command -v java >/dev/null; then
    local major
    major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
    [[ "${major:-0}" -ge 17 ]] || problems+=("JDK 17 or newer is required (found ${major:-none})")
  fi
  if ((${#problems[@]})); then printf 'problem: %s\n' "${problems[@]}" >&2; return 1; fi
}

# Loads the target file in a subshell and reports (names only, never values) whether registration is possible.
target_ready() {
  [[ -f "$TARGET_ENV" ]] || { TARGET_MSG="no target file at the configured path (SM_LOCAL_TARGET_ENV); the target is skipped"; return 1; }
  local missing
  missing="$(
    set -a; source "$TARGET_ENV" >/dev/null 2>&1; set +a
    for v in "${required_for_registration[@]}"; do [[ -n "${!v:-}" ]] || printf '%s ' "$v"; done
    if [[ -n "${SM_LOCAL_TARGET_KEY_FILE:-}" && ! -r "${SM_LOCAL_TARGET_KEY_FILE}" ]]; then printf 'SM_LOCAL_TARGET_KEY_FILE(unreadable) '; fi
  )"
  if [[ -n "$missing" ]]; then TARGET_MSG="the target file is incomplete: $missing- the target is skipped"; return 1; fi
  TARGET_MSG="target file found"
}

# ---- state -------------------------------------------------------------------------------------------------------------
load_state() { if [[ -f "$SECRETS" ]]; then set -a; source "$SECRETS"; set +a; fi; }
save_secret() { # name value  (state is mode 600; values are never printed)
  mkdir -p "$STATE"; chmod 700 "$STATE"
  ( umask 077; touch "$SECRETS" ); chmod 600 "$SECRETS"
  grep -v "^$1=" "$SECRETS" > "$SECRETS.tmp" 2>/dev/null || true
  printf '%s=%s\n' "$1" "$2" >> "$SECRETS.tmp"; chmod 600 "$SECRETS.tmp"; mv "$SECRETS.tmp" "$SECRETS"
}
drop_secret() { # name
  [[ -f "$SECRETS" ]] || return 0
  grep -v "^$1=" "$SECRETS" > "$SECRETS.tmp" 2>/dev/null || true
  chmod 600 "$SECRETS.tmp"; mv "$SECRETS.tmp" "$SECRETS"
}
# The PostgreSQL container this state directory belongs to is recorded at the first `up`; up/start/stop/seed/down refuse a different one.
recorded_container() { grep '^SM_LOCAL_STATE_PG_CONTAINER=' "$SECRETS" 2>/dev/null | head -1 | cut -d= -f2- || true; }
guard_container() {
  local rec; rec="$(recorded_container)"
  [[ -z "$rec" || "$rec" == "$PG_CONTAINER" ]] || die "this state directory belongs to the PostgreSQL container '$rec', not '$PG_CONTAINER'; refusing (set SM_LOCAL_PG_CONTAINER / SM_LOCAL_STATE_DIR consistently)"
}
# With SM_LOCAL_PG_LABEL set, a container or volume is ours only if it carries the label.
owned() { # container|volume name
  [[ -n "$PG_LABEL" ]] || return 0
  local got key="${PG_LABEL%%=*}"
  if [[ "$1" == container ]]; then got="$(docker inspect -f "{{index .Config.Labels \"$key\"}}" "$2" 2>/dev/null || true)"
  else got="$(docker volume inspect -f "{{index .Labels \"$key\"}}" "$2" 2>/dev/null || true)"; fi
  [[ "$got" == "${PG_LABEL#*=}" ]]
}
jdbc_url() { printf 'jdbc:postgresql://127.0.0.1:%s/%s' "${SM_LOCAL_STATE_PG_PORT:-$PG_PORT}" "$DB"; }
# PGPASSWORD by name only: the value travels in this process's environment, never on a command line.
psql_super() { PGPASSWORD="$SM_LOCAL_SUPER_PASSWORD" docker exec -i -e PGPASSWORD "$PG_CONTAINER" psql -h 127.0.0.1 -v ON_ERROR_STOP=1 -q -U postgres -d "$DB" "$@"; }
# LocalSeed on the launcher's classpath. `hash` needs nothing else; `seed` and `register-target` are an API client (below).
seed_java() { java -Xmx1g -cp "$CP" "$here/LocalSeed.java" "$@"; }
pid_alive() { [[ -f "$STATE/launcher.pid" ]] && kill -0 "$(cat "$STATE/launcher.pid")" 2>/dev/null; }
http_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:$APP_PORT$1" 2>/dev/null || true; }

# ---- steps -------------------------------------------------------------------------------------------------------------
do_build() {
  local jar
  jar="$(ls "$repo"/backend/launcher/target/launcher-*-exec.jar 2>/dev/null | head -1 || true)"
  if [[ -z "$jar" || "${SM_LOCAL_REBUILD:-}" == "1" ]]; then
    say "building the launcher (mvn ${SM_LOCAL_MVN_OPTS--o} -pl launcher -am package, tests skipped)"
    # shellcheck disable=SC2086
    "${SM_LOCAL_MVN:-mvn}" ${SM_LOCAL_MVN_OPTS--o} -q -f "$repo/backend/pom.xml" -pl launcher -am -DskipTests -DskipITs package
    jar="$(ls "$repo"/backend/launcher/target/launcher-*-exec.jar | head -1)"
  else
    say "launcher jar already built ($(basename "$jar")); SM_LOCAL_REBUILD=1 rebuilds"
  fi
  mkdir -p "$STATE"; chmod 700 "$STATE"
  # Reproducible Maven archives can retain a fixed filesystem timestamp even
  # when their contents changed. An explicit rebuild must therefore force the
  # unpack; relying only on -nt can otherwise leave stale dependency jars in
  # the local launcher classpath.
  if [[ ! -d "$STATE/app" || "$jar" -nt "$STATE/app/.unpacked" || "${SM_LOCAL_REBUILD:-}" == "1" ]]; then
    rm -rf "$STATE/app"; mkdir -p "$STATE/app"
    unzip -q "$jar" 'BOOT-INF/classes/*' 'BOOT-INF/lib/*' -d "$STATE/app"
    touch "$STATE/app/.unpacked"
    say "classpath unpacked"
  fi
}

do_db() {
  load_state
  mkdir -p "$STATE"; chmod 700 "$STATE"
  if [[ -z "${SM_LOCAL_SUPER_PASSWORD:-}" ]]; then
    save_secret SM_LOCAL_SUPER_PASSWORD "$(rand 32)"
    for r in CP_MIGRATOR CP_RUNTIME DP_MIGRATOR DP_RUNTIME; do save_secret "SM_LOCAL_${r}_PASSWORD" "$(rand 32)"; done
    save_secret SM_LOCAL_STATE_PG_PORT "$PG_PORT"
    save_secret SM_LOCAL_STATE_PG_CONTAINER "$PG_CONTAINER"
    load_state
    say "generated database credentials (stored in $(basename "$STATE")/secrets.env, mode 600; never printed)"
  fi
  if docker ps -a --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
    owned container "$PG_CONTAINER" || die "container $PG_CONTAINER exists and does not carry the label $PG_LABEL: it is not ours; refusing to adopt it"
    if [[ "$(docker inspect -f '{{.State.Running}}' "$PG_CONTAINER")" != "true" ]]; then
      say "starting the existing PostgreSQL container $PG_CONTAINER"; docker start "$PG_CONTAINER" >/dev/null
    else
      say "PostgreSQL container $PG_CONTAINER already running (127.0.0.1:${SM_LOCAL_STATE_PG_PORT})"
    fi
  else
    if docker volume inspect "$PG_VOLUME" >/dev/null 2>&1; then
      if [[ -n "$PG_LABEL" ]] && ! owned volume "$PG_VOLUME"; then die "volume $PG_VOLUME exists and does not carry the label $PG_LABEL: it is not ours; refusing"; fi
      die "volume $PG_VOLUME exists without its container or state; run 'sm-local.sh down' first"
    fi
    say "starting PostgreSQL 14 as $PG_CONTAINER on 127.0.0.1:${SM_LOCAL_STATE_PG_PORT}, volume $PG_VOLUME"
    local lab=(); [[ -z "$PG_LABEL" ]] || { lab=(--label "$PG_LABEL"); docker volume create --label "$PG_LABEL" "$PG_VOLUME" >/dev/null; }
    POSTGRES_PASSWORD="$SM_LOCAL_SUPER_PASSWORD" docker run -d --name "$PG_CONTAINER" ${lab[@]+"${lab[@]}"} \
      -p "127.0.0.1:${SM_LOCAL_STATE_PG_PORT}:5432" -v "$PG_VOLUME:/var/lib/postgresql/data" \
      -e POSTGRES_PASSWORD -e POSTGRES_DB="$DB" postgres:14 >/dev/null
  fi
  local i
  for i in $(seq 1 60); do
    psql_super -c 'select 1' >/dev/null 2>&1 && break
    sleep 1
    [[ $i -lt 60 ]] || die "PostgreSQL did not become ready"
  done

  if [[ ! -f "$STATE/phase0.done" ]]; then
    say "Phase 0: roles and schemas (sm_control_phase0.sql, sm_data_phase0.sql)"
    psql_super < "$repo/backend/cp-core/src/main/resources/db/bootstrap/sm_control_phase0.sql"
    psql_super < "$repo/backend/dp-core/src/main/resources/db/bootstrap/sm_data_phase0.sql"
    for r in sm_cp_migrator:CP_MIGRATOR sm_cp_runtime:CP_RUNTIME sm_dp_migrator:DP_MIGRATOR sm_dp_runtime:DP_RUNTIME; do
      local var="SM_LOCAL_${r#*:}_PASSWORD"
      printf "ALTER ROLE %s PASSWORD '%s';\n" "${r%%:*}" "${!var}" | psql_super
    done
    touch "$STATE/phase0.done"
  else
    say "Phase 0 already applied"
  fi

  if [[ ! -f "$STATE/migrated.done" ]]; then
    # The bootstrap administrator's single-use INITIAL credential, and the one the API seed rotates it to. Both are kept in the
    # state file (mode 600) because the seed is replayable and every replay signs in again; neither is printed here.
    # A re-run after a failed migration reuses what the first run saved: the hash in the database must match the saved credential.
    local admin_pw="${SM_LOCAL_ADMIN_INITIAL_PASSWORD:-${SM_LOCAL_ADMIN_PASSWORD:-}}"
    local admin_next="${SM_LOCAL_ADMIN_NEW_PASSWORD:-}"
    if [[ -z "$admin_pw" ]]; then admin_pw="$(rand 20)"; fi
    if [[ -z "$admin_next" ]]; then admin_next="$(rand 24)"; save_secret SM_LOCAL_ADMIN_NEW_GENERATED 1; fi
    [[ ${#admin_next} -ge 12 ]] || die "SM_LOCAL_ADMIN_NEW_PASSWORD must be at least 12 characters"
    save_secret SM_LOCAL_ADMIN_INITIAL_PASSWORD "$admin_pw"
    save_secret SM_LOCAL_ADMIN_NEW_PASSWORD "$admin_next"
    local hash
    hash="$(SM_LOCAL_PW="$admin_pw" seed_java hash 2>/dev/null | grep "^{argon2id}" | tail -1)"
    say "migration step 1: sm_control (SmControlMigrationMain) with the bootstrap admin's credential hash"
    SM_CONTROL_MIGRATOR_URL="$(jdbc_url)" SM_CONTROL_MIGRATOR_USERNAME=sm_cp_migrator \
      SM_CONTROL_MIGRATOR_PASSWORD="$SM_LOCAL_CP_MIGRATOR_PASSWORD" SM_CONTROL_BOOTSTRAP_CREDENTIAL_HASH="$hash" \
      java -cp "$CP" com.solace.sm.cp.persistence.SmControlMigrationMain
    say "migration step 2: sm_data (SmDataMigrationMain)"
    SM_DATA_MIGRATOR_URL="$(jdbc_url)" SM_DATA_MIGRATOR_USERNAME=sm_dp_migrator \
      SM_DATA_MIGRATOR_PASSWORD="$SM_LOCAL_DP_MIGRATOR_PASSWORD" \
      java -cp "$CP" com.solace.sm.dp.apply.persistence.SmDataMigrationMain
    touch "$STATE/migrated.done"
    say "bootstrap administrator created by migration V007 (single-use credential kept in the state file; the API seed rotates it)"
  else
    say "migrations already applied"
    if [[ ! -f "$STATE/data-v041.done" ]]; then
      say "migration step 3: sm_data generic connection-backed instance admission (SmDataMigrationMain)"
      SM_DATA_MIGRATOR_URL="$(jdbc_url)" SM_DATA_MIGRATOR_USERNAME=sm_dp_migrator \
        SM_DATA_MIGRATOR_PASSWORD="$SM_LOCAL_DP_MIGRATOR_PASSWORD" \
        java -cp "$CP" com.solace.sm.dp.apply.persistence.SmDataMigrationMain
      touch "$STATE/data-v041.done"
    fi
  fi
}

# Apply pending migrations without resetting the local database. Flyway's migration history makes both mains
# idempotent: already-applied versions are skipped and only new versions are executed. This deliberately does not
# touch migrated.done, the PostgreSQL volume, or any seed data.
do_migrate() {
  pid_alive && die "stop the launcher first: ./backend/local/sm-local.sh stop"
  [[ -d "$STATE/app" ]] || do_build
  do_db
  load_state
  local admin_pw="${SM_LOCAL_ADMIN_INITIAL_PASSWORD:-${SM_LOCAL_ADMIN_NEW_PASSWORD:-${SM_LOCAL_ADMIN_PASSWORD:-}}}"
  [[ -n "$admin_pw" ]] || die "no saved bootstrap credential; set SM_LOCAL_ADMIN_PASSWORD and retry"
  local hash
  hash="$(SM_LOCAL_PW="$admin_pw" seed_java hash 2>/dev/null | grep "^{argon2id}" | tail -1)"
  [[ -n "$hash" ]] || die "could not derive the bootstrap credential hash"
  say "applying pending sm_control migrations (existing database is preserved)"
  SM_CONTROL_MIGRATOR_URL="$(jdbc_url)" SM_CONTROL_MIGRATOR_USERNAME=sm_cp_migrator \
    SM_CONTROL_MIGRATOR_PASSWORD="$SM_LOCAL_CP_MIGRATOR_PASSWORD" SM_CONTROL_BOOTSTRAP_CREDENTIAL_HASH="$hash" \
    java -cp "$CP" com.solace.sm.cp.persistence.SmControlMigrationMain
  say "applying pending sm_data migrations (existing database is preserved)"
  SM_DATA_MIGRATOR_URL="$(jdbc_url)" SM_DATA_MIGRATOR_USERNAME=sm_dp_migrator \
    SM_DATA_MIGRATOR_PASSWORD="$SM_LOCAL_DP_MIGRATOR_PASSWORD" \
    java -cp "$CP" com.solace.sm.dp.apply.persistence.SmDataMigrationMain
  say "migrations complete; PostgreSQL volume and application data were kept"
}

# The environment an API-client run needs: where the launcher is, a trusted origin, and the administrator's credentials. The
# sign-in is by the credential in effect: the saved INITIAL one until the rotation has been recorded, then the new one.
api_env() {
  load_state
  [[ -n "${SM_LOCAL_SUPER_PASSWORD:-}" ]] || die "no state: run 'sm-local.sh up' first"
  pid_alive || die "the launcher is not running: run 'sm-local.sh start' (or 'up') first - the seed goes through its API"
  export SM_LOCAL_BASE_URL="http://127.0.0.1:$APP_PORT/api" SM_LOCAL_ORIGIN="${SM_LOCAL_TRUSTED_ORIGIN:-http://localhost:$APP_PORT}"
  if [[ -f "$STATE/admin.rotated" ]]; then export SM_LOCAL_ADMIN_ROTATED=1; else unset SM_LOCAL_ADMIN_ROTATED; fi
}

# Runs one LocalSeed API command, then reads back the NAME=value lines it printed EVEN WHEN IT FAILED part-way, so the progress it
# made (the rotation above all) is recorded and the next run resumes from it. Returns the command's exit status.
run_api() { # command [target]   (target: also load the target file into the environment of this one run)
  local cmd="$1" withtarget="${2:-}" rc=0 out="$STATE/$1.out"
  ( umask 077; : > "$out" )
  (
    if [[ "$withtarget" == target ]]; then set -a; source "$TARGET_ENV" >/dev/null 2>&1; set +a; fi
    seed_java "$cmd" > "$out"
  ) || rc=$?
  local line
  while IFS= read -r line; do
    case "$line" in
      SM_LOCAL_ADMIN_ROTATED=1)
        if [[ ! -f "$STATE/admin.rotated" ]]; then
          touch "$STATE/admin.rotated"; drop_secret SM_LOCAL_ADMIN_INITIAL_PASSWORD
          if [[ "${SM_LOCAL_ADMIN_NEW_GENERATED:-}" == 1 ]]; then
            printf '\n  BOOTSTRAP ADMIN (the single-use credential has been rotated)\n    username: bootstrap-admin   (organization: ACME; omit it at login, this install holds one)\n    password: not printed; it is SM_LOCAL_ADMIN_NEW_PASSWORD in %s/secrets.env (mode 600)\n\n' "$(basename "$STATE")"
          else
            say "bootstrap admin rotated to SM_LOCAL_ADMIN_NEW_PASSWORD (kept in backend/local/.state/secrets.env, mode 600)"
          fi
        fi ;;
      SM_LOCAL_ORGANIZATION_ID=*|SM_LOCAL_UNIT_ID=*|SM_LOCAL_ENVIRONMENT_ID=*|SM_LOCAL_TARGET_ID=*)
        save_id "${line%%=*}" "${line#*=}" ;;
      SM_LOCAL_OPERATOR_DONE=1)
        if [[ "${SM_LOCAL_OPERATOR_PASSWORD_GENERATED:-}" == 1 ]]; then
          printf '\n  OPERATOR (environment-bound)\n    organization: ACME   (omit it at login)\n    username: %s\n    password: not printed; in %s/operator.pw (mode 600)\n\n' "$OPERATOR_USER" "$(basename "$STATE")"
        else
          say "operator $OPERATOR_USER: password = SM_LOCAL_OPERATOR_PASSWORD"
        fi ;;
      SM_LOCAL_SEED_DONE=1) touch "$STATE/seeded.done" ;;
    esac
  done < "$out"
  return "$rc"
}
save_id() { # name value -> ids.env (mode 600, replaced, not appended)
  ( umask 077; touch "$STATE/ids.env" ); chmod 600 "$STATE/ids.env"
  grep -v "^$1=" "$STATE/ids.env" > "$STATE/ids.env.tmp" 2>/dev/null || true
  printf '%s=%s\n' "$1" "$2" >> "$STATE/ids.env.tmp"; chmod 600 "$STATE/ids.env.tmp"; mv "$STATE/ids.env.tmp" "$STATE/ids.env"
}

do_seed() {
  api_env
  if [[ -f "$STATE/seeded.done" ]]; then say "API seed already done"; return 0; fi
  say "seeding through the API as bootstrap-admin: rotation, ACME with ACME-Sub-1/2 (Dev, UAT, Prod each), the 'cache' entitlement, the Operator $OPERATOR_USER bound at ACME-Sub-1 / Dev"
  local generated=""
  if [[ -z "${SM_LOCAL_OPERATOR_PASSWORD:-}" ]]; then SM_LOCAL_OPERATOR_PASSWORD="$(rand 20)"; generated=1; fi
  [[ ${#SM_LOCAL_OPERATOR_PASSWORD} -ge 12 ]] || die "SM_LOCAL_OPERATOR_PASSWORD must be at least 12 characters"
  if [[ -n "$generated" ]]; then ( umask 077; printf '%s\n' "$SM_LOCAL_OPERATOR_PASSWORD" > "$STATE/operator.pw" ); fi
  SM_LOCAL_OPERATOR_USER="$OPERATOR_USER" SM_LOCAL_OPERATOR_PASSWORD="$SM_LOCAL_OPERATOR_PASSWORD" \
    SM_LOCAL_OPERATOR_PASSWORD_GENERATED="$generated" \
    run_api seed || die "the API seed failed; fix the cause above and run 'sm-local.sh seed' again (every step is replay-safe)"
}

do_target() {
  load_state
  if [[ -f "$STATE/target.done" ]]; then say "target already registered"; return 0; fi
  local TARGET_MSG=""
  if ! target_ready; then say "TARGET SKIPPED: $TARGET_MSG (apply and health will have nothing to act on)"; return 0; fi
  api_env
  [[ -f "$STATE/seeded.done" ]] || die "the API seed has not completed: run 'sm-local.sh seed' first"
  set -a; source "$STATE/ids.env"; set +a
  say "registering the SSH-Docker target through the API: scanHostKeys, registerTarget, testTargetConnection (SM connects to the host to scan it)"
  SM_LOCAL_TARGET_NAME="${SM_LOCAL_TARGET_NAME:-local-e2e-host}" run_api register-target target \
    || die "target registration failed; fix the cause above and run 'sm-local.sh seed' again"
  touch "$STATE/target.done"
  set -a; source "$STATE/ids.env"; set +a
  say "target registered (${SM_LOCAL_TARGET_ID:-id in backend/local/.state/ids.env})"
}

do_start() {
  load_state
  [[ -d "$STATE/app" ]] || die "not built: run 'sm-local.sh up'"
  if pid_alive; then say "launcher already running (pid $(cat "$STATE/launcher.pid"))"; return 0; fi
  if [[ ! -f "$STATE/master.key" ]]; then
    ( umask 077; head -c 32 /dev/urandom | base64 | tr -d '\n' > "$STATE/master.key" ); chmod 600 "$STATE/master.key"
    say "generated the master key (backend/local/.state/master.key, mode 600)"
  fi
  [[ "$(http_code /api/cp/auth/identity)" == "000" ]] || die "port $APP_PORT already answers: something else is running there (SM_LOCAL_PORT)"
  local mesh=(SM_MESH_ENABLED=false)
  if [[ -n "${SM_LOCAL_MESH_HOST:-}" ]]; then
    mesh=(SM_MESH_ENABLED=true SM_MESH_HOST="$SM_LOCAL_MESH_HOST" SM_MESH_VPN="${SM_LOCAL_MESH_VPN:?}"
          SM_MESH_CLIENT_USERNAME="${SM_LOCAL_MESH_USERNAME:?}" SM_MESH_PASSWORD="${SM_LOCAL_MESH_PASSWORD:?}")
  fi
  say "starting the launcher on $BIND:$APP_PORT (log: backend/local/.state/launcher.log)"
  env "${mesh[@]}" \
    SERVER_PORT="$APP_PORT" SERVER_ADDRESS="$BIND" \
    SM_CONTROL_RUNTIME_URL="$(jdbc_url)" SM_CONTROL_RUNTIME_USERNAME=sm_cp_runtime SM_CONTROL_RUNTIME_PASSWORD="$SM_LOCAL_CP_RUNTIME_PASSWORD" \
    SM_DATA_RUNTIME_URL="$(jdbc_url)" SM_DATA_RUNTIME_USERNAME=sm_dp_runtime SM_DATA_RUNTIME_PASSWORD="$SM_LOCAL_DP_RUNTIME_PASSWORD" \
    SM_GATEWAY_TRUSTED_ORIGINS_0="${SM_LOCAL_TRUSTED_ORIGIN:-http://localhost:$APP_PORT}" SM_GATEWAY_DIRECT_CLIENT_CONNECTIONS=true \
    SM_DP_MASTER_KEY_FILE="$STATE/master.key" SM_DP_MASTER_KEY_ID=local-1 \
    nohup java "${SM_LOCAL_HEAP:--Xmx2g}" -cp "$CP" com.solace.sm.launcher.SolutionManagerApplication \
      > "$STATE/launcher.log" 2>&1 < /dev/null &
  echo $! > "$STATE/launcher.pid"
  local i code
  for i in $(seq 1 120); do
    pid_alive || { tail -20 "$STATE/launcher.log" >&2; die "the launcher exited during startup (see backend/local/.state/launcher.log)"; }
    code="$(http_code /api/cp/auth/identity)"
    [[ "$code" != "000" ]] && { say "launcher answers (HTTP $code on /api/cp/auth/identity) after ${i}s"; return 0; }
    sleep 1
  done
  die "the launcher did not answer within 120 s (see backend/local/.state/launcher.log)"
}

do_stop() {
  if pid_alive; then
    local pid; pid="$(cat "$STATE/launcher.pid")"
    say "stopping the launcher (pid $pid)"
    kill "$pid" 2>/dev/null || true
    local i; for i in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$pid" 2>/dev/null || true
  else
    say "launcher not running"
  fi
  rm -f "$STATE/launcher.pid"
}

do_down() {
  do_stop
  if docker ps -a --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
    if owned container "$PG_CONTAINER"; then say "removing container $PG_CONTAINER"; docker rm -f "$PG_CONTAINER" >/dev/null
    else say "NOT removing container $PG_CONTAINER: it does not carry the label $PG_LABEL"; fi
  fi
  if docker volume inspect "$PG_VOLUME" >/dev/null 2>&1; then
    if owned volume "$PG_VOLUME"; then say "removing volume $PG_VOLUME"; docker volume rm "$PG_VOLUME" >/dev/null
    else say "NOT removing volume $PG_VOLUME: it does not carry the label $PG_LABEL"; fi
  fi
  if [[ -d "$STATE" ]]; then say "removing the state directory $(basename "$STATE")/"; rm -rf "$STATE"; fi
  say "down: nothing left behind (the target file and the repo are untouched)"
}

do_status() {
  load_state
  local db="absent" app="stopped"
  docker ps -a --format '{{.Names}} {{.Status}}' 2>/dev/null | grep "^$PG_CONTAINER " | sed 's/^/PostgreSQL:  /' || echo "PostgreSQL:  absent"
  pid_alive && app="running (pid $(cat "$STATE/launcher.pid")), HTTP $(http_code /api/cp/auth/identity) on :$APP_PORT"
  echo "launcher:    $app"
  local f marker
  for f in phase0 migrated admin.rotated seeded target; do
    marker="$STATE/$f.done"; [[ "$f" == "admin.rotated" ]] && marker="$STATE/admin.rotated"
    [[ -f "$marker" ]] && echo "step $f: done" || echo "step $f: not done"
  done
  local TARGET_MSG=""; target_ready && echo "target file: ready" || echo "target file: $TARGET_MSG"
}

do_bodies() {
  target_ready || { echo "$TARGET_MSG" >&2; return 1; }
  ( set -a; source "$TARGET_ENV" >/dev/null 2>&1; set +a
    cat <<JSON
{
  "solutionId": "{{solutionId}}",
  "name": "local-cache",
  "targetId": "{{targetId}}",
  "declaredPackageVersion": {{packageVersion}},
  "placement": {
    "containerName": "${SM_LOCAL_CACHE_CONTAINER:-<container>}",
    "configDirectory": "${SM_LOCAL_TARGET_CONFIG_ROOT}",
    "containerConfigPath": "${SM_LOCAL_CACHE_CONTAINER_CONFIG_PATH:-/app/external/spring/config}",
    "fileName": "${SM_LOCAL_CACHE_FILE_NAME:-application.yml}",
    "healthPort": ${SM_LOCAL_CACHE_HEALTH_PORT:-8081}
  },
  "acquisition": { "mode": "direct-http" }
}
JSON
  )
}

# ---- dispatch ----------------------------------------------------------------------------------------------------------
if $check_only; then
  prereqs && say "prerequisites ok"
  TARGET_MSG=""
  if target_ready; then say "target file: $TARGET_MSG"; else say "target file: $TARGET_MSG"; fi
  say "would do: build (once) -> PostgreSQL 14 as $PG_CONTAINER on 127.0.0.1:$PG_PORT -> Phase 0 + both migrations -> launcher on $BIND:$APP_PORT -> API seed as bootstrap-admin (rotate, hierarchy, entitlement, Operator) -> target through registerTarget"
  exit 0
fi

case "$cmd" in up|start|stop|seed|down) guard_container ;; esac
case "$cmd" in
  up)     prereqs || exit 1; do_build; do_db; do_start; do_seed; do_target
          say "stack is up. Base URL http://localhost:$APP_PORT/api - walkthrough: backend/local/README.md" ;;
  build)  prereqs || exit 1; do_build ;;
  migrate) prereqs || exit 1; do_migrate ;;
  seed)   do_seed; do_target ;;
  start)  do_start ;;
  stop)   do_stop ;;
  down)   do_down ;;
  status) do_status ;;
  bodies) do_bodies ;;
  *)      sed -n '2,29p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
