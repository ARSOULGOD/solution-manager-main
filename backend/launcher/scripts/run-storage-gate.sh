#!/usr/bin/env bash
#
# Plan 011 P11-8.1: run the monitoring STORAGE GATE, one step at a time, against a PostgreSQL 14 on the dedicated SM test host
# (OR-100, OR-110) that the integrator reached through an SSH port-forward. It runs two integration tests, each skipped unless
# its property is set:
#   MetricsStorageBenchmarkIT  (dp-core)   Steps 0b, 1, 2, 2b, 5, 6a: load, bytes/row, WAL, vacuum, insert rate, rollup cost
#   MetricsGateBenchmarkIT     (launcher)  Step 3: query timings AS AN OPERATOR over HTTP
# (Steps 6b and 6c, the 105 M-row export stream and the default re-check, are not here: they need the export route of P11-7.2.)
#
# *** WHAT THIS WRITES. ***  Everything it writes goes into the benchmark database it is pointed at (tenants of its own, in
# sm_data, sm_metrics and sm_control) and into ./backend/target/storage-gate (evidence log, bench-results.properties). It
# never touches the host's other containers, the Cache or any file outside that database. It does NOT delete anything:
# tearing the benchmark down (docker rm -v of the postgres container, then the volume) is the integrator's, Step 8.
#
# *** THE DATABASE. ***  PostgreSQL 14 runs in a container on the host, bound to the host's loopback only (OR-100). Start it ON THE HOST
# (a named volume so the data is one thing to remove; a memory limit so a t4g.medium with 3.8 GB and a Cache already running is not
# starved; the figures are the integrator's to adjust, and the run record in the evidence file says what was used):
#     docker run -d --name sm-bench-pg --restart no --memory 1536m --memory-swap 1536m --shm-size 256m \
#       -p 127.0.0.1:55432:5432 -v sm_bench_pgdata:/var/lib/postgresql/data \
#       -e POSTGRES_PASSWORD="$SM_BENCH_ADMIN_PASSWORD" -e POSTGRES_DB=sm postgres:14 \
#       -c shared_buffers=256MB -c work_mem=8MB -c maintenance_work_mem=96MB -c max_connections=30 -c listen_addresses='*'
# The URL must name a LOOPBACK address on THIS machine: the host's PostgreSQL is reached through an SSH local port-forward that the
# integrator sets up, for example
#     ssh -N -L 127.0.0.1:55432:127.0.0.1:55432 <user>@<host>          # in a terminal of its own; leave it running
#     export SM_BENCH_URL=jdbc:postgresql://127.0.0.1:55432/sm
# (the host and user come from the integrator's own SSH configuration; this script never reads or opens one, and with the forward
# down every step fails at connect). A non-loopback URL is refused unless SM_BENCH_ALLOW_NON_LOOPBACK=yes.
#
# *** PASSWORDS ARE READ FROM THE ENVIRONMENT ONLY. ***  They are never arguments, never -D properties and never printed;
# the evidence masks them. Set them in your shell without echoing them (read -rs SM_BENCH_ADMIN_PASSWORD; export ...).
# The application's master key for `gate` is derived from SM_BENCH_ADMIN_PASSWORD and the database's OWN identity (the cluster's
# system_identifier and its name, read over the admin connection: GateMasterKey), NOT from SM_BENCH_URL, so a re-run of `gate` against
# the same database presents the key its first boot sealed the canary with even if the SSH port-forward was re-made on another local
# port or the host is spelled differently. Keep the admin password for the database's life: a changed password is another key.
#
# ---- commands -------------------------------------------------------------------------------------------------------------
#   prepare                 Phase 0 (roles, schemas), then the sm_data / sm_secret / sm_metrics migrations. Once per database.
#                             needs SM_BENCH_ADMIN_PASSWORD  SM_BENCH_RUNTIME_PASSWORD  SM_BENCH_MIGRATOR_PASSWORD
#   step <0b|1|2|2b|5|6a|all>   one data-plane step (or all of them in order). Step 0b alone, then STOP and read its VERDICT.
#                             needs SM_BENCH_ADMIN_PASSWORD  SM_BENCH_RUNTIME_PASSWORD
#   gate-prepare            the control-plane schema (sm_control) and the seeded Operator. Once, after Steps 0b and 1.
#                             needs the four above plus SM_BENCH_CP_RUNTIME_PASSWORD  SM_BENCH_CP_MIGRATOR_PASSWORD
#   gate                    Step 3: boot the application, log in as the Operator, time the queries.
#                             needs SM_BENCH_ADMIN_PASSWORD  SM_BENCH_RUNTIME_PASSWORD  SM_BENCH_CP_RUNTIME_PASSWORD
#                             SM_BENCH_CP_MIGRATOR_PASSWORD (the build reads it; the step does not use it)
#   free-disk               print the host's free bytes under SM_BENCH_DATA_PATH over ssh (read-only `df`); needs SM_BENCH_SSH_TARGET
#
# ---- environment ----------------------------------------------------------------------------------------------------------
#   SM_BENCH_URL             jdbc:postgresql://127.0.0.1:<forwarded port>/<database>   (required, no credentials in it)
#   SM_BENCH_ADMIN_USER      the cluster's superuser (default postgres): creates a past month's partition, VACUUM, reads the WAL
#   SM_BENCH_FREE_DISK_BYTES the host's free disk in bytes, from `df` (the `free-disk` command prints it). Step 0b needs it to say
#                            whether the month fits; without it the projection is printed and YOU compare it
#   SM_BENCH_FLOOR_BYTES     the floor never to cross (default 3221225472 = 3 GiB)
#   SM_BENCH_MAX_DB_BYTES    the load stops when the database reaches this size (default: SM_BENCH_FREE_DISK_BYTES - the floor,
#                            when the free disk is given; pg_wal is NOT in a database's size, so leave WAL headroom)
#   SM_BENCH_FREE_DISK_AFTER_DROP_BYTES   Step 6a: the free disk once the benchmark month is dropped, if you measured it
#   SM_BENCH_CPU_COMMAND     a command that prints the host's vmstat lines (Step 2b); or SM_BENCH_SSH_TARGET=<user@host>, which
#                            makes it `ssh -o BatchMode=yes <target> vmstat 5` (read-only)
#   SM_BENCH_SSH_TARGET      user@host for the read-only df and vmstat commands above (your own ssh configuration)
#   SM_BENCH_DATA_PATH       the path `df` is asked about (default /var/lib/docker)
#   scale (default is the plan's real S-A month: 200 instances x 5 metrics x 30 s x 30 days = 86.4 M rows):
#   SM_BENCH_INSTANCES  SM_BENCH_METRICS  SM_BENCH_INTERVAL_SECONDS  SM_BENCH_DAYS   (a scaled run says SCALED-DOWN in every line)
#   SM_BENCH_TENANT  SM_BENCH_ENVIRONMENT   fixed ids for the loaded organization and environment (default: generated, then
#                                           stored in the results directory so the gate finds them)
#   SM_BENCH_INGEST_TICKS (20)  SM_BENCH_INGEST_SECONDS (120)  SM_BENCH_ROLLUP_RUNS (3)  SM_BENCH_ROLLUP_CHUNK_HOURS (24)
#   SM_BENCH_GATE_RUNS (20)  SM_BENCH_GATE_SPACING_MS (150)  SM_BENCH_GATE_INSTANCE_COUNTS (1,10,200)  SM_BENCH_GATE_ENTITY_TYPE
#   SM_BENCH_OUT             results directory (default <repo>/backend/target/storage-gate); the data-plane and gate steps must share it
#   SM_BENCH_MVN  SM_BENCH_MVN_OPTS (default -o)  SM_BENCH_TC_ENV (a file to source first: JAVA_HOME and so on)
#
# Usage:   backend/launcher/scripts/run-storage-gate.sh [--check] <command> [args]
#   --check   validate the environment and print the command; run nothing and contact nothing.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"      # backend/launcher/scripts -> repo root

check_only=false
args=()
for arg in "$@"; do
  case "$arg" in
    --check) check_only=true ;;
    -h|--help) sed -n '2,67p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) args+=("$arg") ;;
  esac
done
command_name="${args[0]:-}"
step_arg="${args[1]:-}"

problems=()
need() { local name; for name in "$@"; do if [[ -z "${!name:-}" ]]; then problems+=("$name is not set"); fi; done; }

case "$command_name" in
  prepare)       need SM_BENCH_URL SM_BENCH_ADMIN_PASSWORD SM_BENCH_RUNTIME_PASSWORD SM_BENCH_MIGRATOR_PASSWORD ;;
  step)
    need SM_BENCH_URL SM_BENCH_ADMIN_PASSWORD SM_BENCH_RUNTIME_PASSWORD
    case "$step_arg" in
      0b|1|2|2b|5|6a|all) ;;
      *) problems+=("step needs one of 0b 1 2 2b 5 6a all, got '${step_arg}'") ;;
    esac ;;
  gate-prepare)  need SM_BENCH_URL SM_BENCH_ADMIN_PASSWORD SM_BENCH_RUNTIME_PASSWORD SM_BENCH_CP_RUNTIME_PASSWORD SM_BENCH_CP_MIGRATOR_PASSWORD ;;
  gate)          need SM_BENCH_URL SM_BENCH_ADMIN_PASSWORD SM_BENCH_RUNTIME_PASSWORD SM_BENCH_CP_RUNTIME_PASSWORD SM_BENCH_CP_MIGRATOR_PASSWORD ;;
  free-disk)     need SM_BENCH_SSH_TARGET ;;
  *)             problems+=("the command must be one of: prepare, step <0b|1|2|2b|5|6a|all>, gate-prepare, gate, free-disk (got '${command_name}')") ;;
esac

if [[ -n "${SM_BENCH_URL:-}" ]]; then
  if [[ ! "${SM_BENCH_URL}" =~ ^jdbc:postgresql://[^/?@]+/[A-Za-z0-9_.-]+$ ]]; then
    problems+=("SM_BENCH_URL must be jdbc:postgresql://host:port/database with no credentials or parameters in it (the value is not echoed)")
  elif [[ ! "${SM_BENCH_URL}" =~ ^jdbc:postgresql://(127\.0\.0\.1|localhost|\[::1\])(:[0-9]+)?/ && "${SM_BENCH_ALLOW_NON_LOOPBACK:-}" != "yes" ]]; then
    problems+=("SM_BENCH_URL is not a loopback address: reach the host's PostgreSQL through an SSH local port-forward (see the header); SM_BENCH_ALLOW_NON_LOOPBACK=yes overrides")
  fi
fi
for name in SM_BENCH_FREE_DISK_BYTES SM_BENCH_FLOOR_BYTES SM_BENCH_MAX_DB_BYTES SM_BENCH_FREE_DISK_AFTER_DROP_BYTES SM_BENCH_INSTANCES \
            SM_BENCH_METRICS SM_BENCH_INTERVAL_SECONDS SM_BENCH_DAYS SM_BENCH_INGEST_TICKS SM_BENCH_INGEST_SECONDS SM_BENCH_ROLLUP_RUNS \
            SM_BENCH_ROLLUP_CHUNK_HOURS SM_BENCH_GATE_RUNS SM_BENCH_GATE_SPACING_MS; do
  if [[ -n "${!name:-}" && ! "${!name}" =~ ^[0-9]+$ ]]; then problems+=("$name must be a whole number"); fi
done
if [[ -n "${SM_BENCH_SSH_TARGET:-}" && ! "${SM_BENCH_SSH_TARGET}" =~ ^[A-Za-z0-9_.@-]+$ ]]; then
  problems+=("SM_BENCH_SSH_TARGET must be user@host (letters, digits, . _ - @ only)")
fi
if [[ -n "${SM_BENCH_CPU_COMMAND:-}" && -n "${SM_BENCH_SSH_TARGET:-}" ]]; then
  problems+=("give SM_BENCH_CPU_COMMAND or SM_BENCH_SSH_TARGET for the vmstat command, not both")
fi
if (( ${#problems[@]} > 0 )); then
  echo "run-storage-gate.sh: not running; fix:" >&2
  for p in "${problems[@]}"; do echo "  - $p" >&2; done
  echo "(see the header of this script; nothing was started and nothing was contacted)" >&2
  exit 2
fi

data_path="${SM_BENCH_DATA_PATH:-/var/lib/docker}"
if [[ "$command_name" == "free-disk" ]]; then
  ssh_cmd=(ssh -o BatchMode=yes "${SM_BENCH_SSH_TARGET}" df -B1 --output=avail "$data_path")
  echo "== free disk (read-only) =="
  echo "command    : ${ssh_cmd[*]}"
  if $check_only; then echo "(--check: validated, nothing was run and nothing was contacted)"; exit 0; fi
  "${ssh_cmd[@]}"
  echo "next: export SM_BENCH_FREE_DISK_BYTES=<the number above>"
  exit 0
fi

# ---- assemble the Maven command: the target as -D properties (never a password) -------------------------------------------
out_dir="${SM_BENCH_OUT:-$repo/backend/target/storage-gate}"
floor="${SM_BENCH_FLOOR_BYTES:-3221225472}"
props=("-Dsm.bench.out=${out_dir}" "-Dsm.bench.admin.user=${SM_BENCH_ADMIN_USER:-postgres}")
add() { [[ -n "${!2:-}" ]] && props+=("-D$1=${!2}") || true; }
add sm.bench.free-disk-bytes SM_BENCH_FREE_DISK_BYTES
add sm.bench.floor-bytes SM_BENCH_FLOOR_BYTES
add sm.bench.free-disk-after-drop-bytes SM_BENCH_FREE_DISK_AFTER_DROP_BYTES
add sm.bench.instances SM_BENCH_INSTANCES
add sm.bench.metrics SM_BENCH_METRICS
add sm.bench.interval-seconds SM_BENCH_INTERVAL_SECONDS
add sm.bench.days SM_BENCH_DAYS
add sm.bench.tenant SM_BENCH_TENANT
add sm.bench.environment SM_BENCH_ENVIRONMENT
add sm.bench.ingest.ticks SM_BENCH_INGEST_TICKS
add sm.bench.ingest.seconds SM_BENCH_INGEST_SECONDS
add sm.bench.rollup.runs SM_BENCH_ROLLUP_RUNS
add sm.bench.rollup.chunk-hours SM_BENCH_ROLLUP_CHUNK_HOURS
add sm.bench.gate.runs SM_BENCH_GATE_RUNS
add sm.bench.gate.spacing-ms SM_BENCH_GATE_SPACING_MS
add sm.bench.gate.instance-counts SM_BENCH_GATE_INSTANCE_COUNTS
add sm.bench.gate.entity-type SM_BENCH_GATE_ENTITY_TYPE
if [[ -n "${SM_BENCH_MAX_DB_BYTES:-}" ]]; then
  props+=("-Dsm.bench.max-db-bytes=${SM_BENCH_MAX_DB_BYTES}")
elif [[ -n "${SM_BENCH_FREE_DISK_BYTES:-}" ]] && (( SM_BENCH_FREE_DISK_BYTES > floor )); then
  props+=("-Dsm.bench.max-db-bytes=$(( SM_BENCH_FREE_DISK_BYTES - floor ))")
fi
cpu_command="${SM_BENCH_CPU_COMMAND:-}"
if [[ -n "${SM_BENCH_SSH_TARGET:-}" ]]; then cpu_command="ssh -o BatchMode=yes ${SM_BENCH_SSH_TARGET} vmstat 5"; fi
[[ -n "$cpu_command" ]] && props+=("-Dsm.bench.cpu-command=${cpu_command}")

case "$command_name" in
  prepare)       module=dp-core;   test_class=MetricsStorageBenchmarkIT; steps="prepare"; url_prop=sm.bench.url ;;
  step)          module=dp-core;   test_class=MetricsStorageBenchmarkIT; url_prop=sm.bench.url
                 if [[ "$step_arg" == "all" ]]; then steps="0b,1,2,2b,5,6a"; else steps="$step_arg"; fi ;;
  gate-prepare)  module=launcher;  test_class=MetricsGateBenchmarkIT;    steps="prepare"; url_prop=sm.bench.gate.url ;;
  gate)          module=launcher;  test_class=MetricsGateBenchmarkIT;    steps="3"; url_prop=sm.bench.gate.url ;;
esac
props+=("-D${url_prop}=${SM_BENCH_URL}")
if [[ "$module" == "dp-core" ]]; then props+=("-Dsm.bench.steps=${steps}"); else props+=("-Dsm.bench.gate.steps=${steps}"); fi

if [[ -n "${SM_BENCH_MVN:-}" ]]; then mvn_cmd="${SM_BENCH_MVN}"
elif command -v mvn >/dev/null 2>&1; then mvn_cmd="mvn"
else mvn_cmd="$repo/backend/mvnw"; fi
# shellcheck disable=SC2206   # SM_BENCH_MVN_OPTS is a space-separated list of options by contract
mvn_opts=(${SM_BENCH_MVN_OPTS--o})

# Only this IT. -Dtest=NoSuchUnit keeps surefire from running every unit test of the modules built with -am.
cmd=("$mvn_cmd" "${mvn_opts[@]}" -f "$repo/backend/pom.xml" -pl "$module" -am verify
     -Dtest=NoSuchUnit "-Dit.test=${test_class}"
     -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false "${props[@]}")

echo "== plan 011 P11-8.1 storage gate: ${command_name} ${step_arg} =="
echo "database   : ${SM_BENCH_URL%/*}/<database> (loopback, through your SSH port-forward); admin ${SM_BENCH_ADMIN_USER:-postgres}; passwords from the environment, not shown"
echo "steps      : ${steps}"
echo "scale      : instances ${SM_BENCH_INSTANCES:-200} x metrics ${SM_BENCH_METRICS:-5} at ${SM_BENCH_INTERVAL_SECONDS:-30} s for ${SM_BENCH_DAYS:-30} days$( [[ -n "${SM_BENCH_INSTANCES:-}${SM_BENCH_METRICS:-}${SM_BENCH_INTERVAL_SECONDS:-}${SM_BENCH_DAYS:-}" ]] && echo '  *** SCALED-DOWN, NOT S-A ***' || true )"
if [[ -n "${SM_BENCH_FREE_DISK_BYTES:-}" ]]; then
  echo "disk       : free ${SM_BENCH_FREE_DISK_BYTES} B, floor ${floor} B$( [[ " ${props[*]} " == *"max-db-bytes"* ]] && echo ', the load stops at the ceiling in -Dsm.bench.max-db-bytes' || true )"
else
  echo "disk       : free disk NOT given (SM_BENCH_FREE_DISK_BYTES): Step 0b prints its projection and YOU compare it with df before Step 1"
fi
echo "writes     : the benchmark database only, and ${out_dir}. Nothing is deleted."
echo "evidence   : ${out_dir}/storage-gate-evidence.log and bench-results.properties"
echo "failsafe   : $repo/backend/${module}/target/failsafe-reports/"
echo "command    : ${cmd[*]}"

if $check_only; then
  echo "(--check: validated, nothing was run and nothing was contacted)"
  exit 0
fi

if [[ -n "${SM_BENCH_TC_ENV:-}" ]]; then
  # shellcheck disable=SC1090
  source "${SM_BENCH_TC_ENV}"
fi

mkdir -p "$out_dir"
find "$repo/backend/${module}/target/failsafe-reports" -name failsafe-summary.xml -delete 2>/dev/null || true
set +e
"${cmd[@]}"
status=$?
set -e

echo
echo "== finished with exit status $status =="
echo "the MEASURED / EXTRAPOLATED / FINDING / NOTE lines are in ${out_dir}/storage-gate-evidence.log; copy them into"
echo "docs/design/backend/evidence/011-storage-gate.md (its sections), labelling every extrapolation as one."
case "$command_name:$step_arg" in
  step:0b) echo "NEXT: read 'VERDICT' and monthProjectionBytes above. If the month does not fit, STOP and take the scaled option to the owner." ;;
  step:6a) echo "NEXT: the 6a VERDICT decides where Step 6b runs." ;;
esac
exit "$status"
