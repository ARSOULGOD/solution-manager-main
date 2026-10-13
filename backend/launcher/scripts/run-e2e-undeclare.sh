#!/usr/bin/env bash
#
# Plan 012 T5 (live gate L5, OR-110): undeclare an instance of the E2E host's SECOND Cache and prove, from the HOST's side,
# that SM touched nothing; then clean up the hidden files SM left, by the runbook's commands, with a listing before and after.
# The same target as run-e2e-walkthrough.sh and run-e2e-multi-placement.sh, the same SM_E2E_* variables, and the same
# sm.walkthrough.ssh.* properties underneath. The first Cache is never declared, written, stopped or restarted.
#
# THE GATE, IN FOUR STEPS (this script does all four, in order):
#   1. BEFORE   over its own SSH session (pinned to SM_E2E_SSH_FINGERPRINT): `docker inspect` of the second container (id, start
#               time, running) and a long listing of its config directory. Written to the evidence directory.
#   2. THE RUN  UndeclareLiveIT (Maven): declares an instance on the second Cache, writes and restores its config file once
#               (so SM's hidden .<file>.sm-preimage-* and .<file>.sm-fp-* files exist), then calls
#               DELETE /dp/instances/{id}?workload=leave-running and asserts SM made no host call.
#   3. AFTER    the same `docker inspect` and listing. The container's id and start time MUST be identical to step 1 and it
#               MUST still be running: this is the only observation of the container that does not go through SM, and it is
#               what "undeclare never touches the host" (BE-D49, OPS-Q2 U-C) means. A difference FAILS the gate.
#   4. CLEANUP  with SM_E2E_CONFIRM_CLEANUP=yes: removes the hidden files SM left in that directory (and only regular files
#               whose whole name matches .<file>.sm-preimage-<n>[.absent] or .<file>.sm-fp-<n>, <n> being digits: a near miss such as
#               .<file>.sm-preimage-3.bak is left alone), then lists the directory again. Without it the
#               script prints the commands and exits 3: L5 is NOT closed until the cleanup has been done and listed. This is
#               OR-110's rule that the host is left as it was found, and OR-109's lesson that it is not left to "by hand afterwards".
#               (The live config file itself, and the config's secrets, belong to the running container: they stay.)
#
# *** THIS WRITES TO THE HOST. ***  The second Cache's configuration file is overwritten once (and put back to its ORIGINAL
# keyed hash by the engine's own restore, before the undeclare); the directory receives SM's hidden files. It stops and restarts
# nothing. Like the other live scripts it refuses to run unless SM_E2E_CONFIRM_WRITE=yes (exactly that word). SM's database, local
# mesh broker and registered rows are this run's own throwaway Testcontainers.
#
# Nothing here looks for a host: the target is whatever you put in the environment. THE L5 CLAIM IS NOT MADE until this script has
# passed against the host (exit 0, after the cleanup) and its evidence has been recorded in plan 012 T5's Results.
#
# ---- target (required; the walkthrough's own) -----------------------------------------------------------------------------
#   SM_E2E_SSH_HOST          the SSH host                      SM_E2E_SSH_USER         the login user
#   SM_E2E_SSH_FINGERPRINT   the host key to pin, SHA256:<base64>
#   SM_E2E_SSH_KEY_FILE      the path of the SSH private key FILE (read once, sealed in the throwaway database, zeroised)
#   SM_E2E_CONTAINER         the FIRST Cache container's name (also the registered envelope's container prefix)
#   SM_E2E_CONFIG_DIR        the host directory the first Cache's config mount reads (the registered envelope's config root)
# ---- the second Cache (required; ALL THREE) ---------------------------------------------------------------------------------
#   SM_E2E_SECOND_CONTAINER  its container name: must start with SM_E2E_CONTAINER and differ from it
#   SM_E2E_SECOND_CONFIG_DIR its config directory: must lie strictly UNDER SM_E2E_CONFIG_DIR (e.g. <root>/second)
#   SM_E2E_SECOND_HOST_PORT  the HOST port its container port 8081 is published on; must differ from the first Cache's
#   The second container must already be RUNNING on the host, with its own config file mounted from that directory.
# ---- target (optional, the walkthrough's own) -----------------------------------------------------------------------------
#   SM_E2E_SSH_PORT (22)  SM_E2E_HEALTH_PORT (8081)  SM_E2E_ACQUISITION (via-ssh | direct-http; default via-ssh)
#   SM_E2E_CONTAINER_CONFIG_PATH  SM_E2E_FILE_NAME (default application.yml)  SM_E2E_NAMESPACE
# ---- this gate ----------------------------------------------------------------------------------------------------------
#   SM_E2E_CONFIRM_WRITE     exactly "yes", REQUIRED: you know this run overwrites the second Cache's config file (and restores it)
#   SM_E2E_CONFIRM_CLEANUP   exactly "yes": remove the hidden files SM left (step 4). Without it L5 stays open (exit 3).
# ---- the run ------------------------------------------------------------------------------------------------------------
#   SM_E2E_EVIDENCE_DIR      where the evidence goes (default backend/launcher/target/walkthrough-evidence)
#   SM_E2E_MVN               the Maven command (default: mvn, else backend/mvnw)
#   SM_E2E_MVN_OPTS          extra Maven options (default: -o, offline)
#   SM_E2E_TC_ENV            a file to source first (Testcontainers/Docker/JAVA_HOME settings for your machine)
#
# Exit status: 0 the gate passed and the host was cleaned and listed; 1 the run or the host-side check failed (read the
# evidence; a container that changed is a FAILED gate); 2 not run (bad environment); 3 the run passed but the cleanup was not
# confirmed (L5 open).
#
# Usage:   SM_E2E_...=... backend/launcher/scripts/run-e2e-undeclare.sh [--check]
#   --check   validate the environment and print the command and the host-side commands; run nothing, contact nothing.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"      # backend/launcher/scripts -> repo root

check_only=false
for arg in "$@"; do
  case "$arg" in
    --check) check_only=true ;;
    -h|--help) sed -n '2,60p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $arg (only --check is accepted; the target comes from the environment)" >&2; exit 2 ;;
  esac
done

# ---- validate: name EVERY problem at once, never a value --------------------------------------------------------------
problems=()
for name in SM_E2E_SSH_HOST SM_E2E_SSH_USER SM_E2E_SSH_FINGERPRINT SM_E2E_SSH_KEY_FILE SM_E2E_CONTAINER SM_E2E_CONFIG_DIR \
            SM_E2E_SECOND_CONTAINER SM_E2E_SECOND_CONFIG_DIR SM_E2E_SECOND_HOST_PORT; do
  if [[ -z "${!name:-}" ]]; then problems+=("$name is not set"); fi
done
if [[ -n "${SM_E2E_SSH_KEY_FILE:-}" && ! -r "${SM_E2E_SSH_KEY_FILE}" ]]; then
  problems+=("SM_E2E_SSH_KEY_FILE does not name a readable file")
fi
if [[ -n "${SM_E2E_SSH_FINGERPRINT:-}" && ! "${SM_E2E_SSH_FINGERPRINT}" =~ ^SHA256:[A-Za-z0-9+/]{20,86}=*$ ]]; then
  problems+=("SM_E2E_SSH_FINGERPRINT is not SHA256:<base64>")
fi
# These values are used inside a remote shell command line below: refuse anything outside the grammar SM itself enforces.
if [[ -n "${SM_E2E_SECOND_CONTAINER:-}" && ! "${SM_E2E_SECOND_CONTAINER}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$ ]]; then
  problems+=("SM_E2E_SECOND_CONTAINER is not a container name ([A-Za-z0-9][A-Za-z0-9_.-]*)")
fi
if [[ -n "${SM_E2E_SECOND_CONFIG_DIR:-}" && ! "${SM_E2E_SECOND_CONFIG_DIR}" =~ ^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$ ]]; then
  problems+=("SM_E2E_SECOND_CONFIG_DIR is not an absolute path of [A-Za-z0-9._-] segments")
elif [[ "${SM_E2E_SECOND_CONFIG_DIR:-}" == *..* ]]; then
  problems+=("SM_E2E_SECOND_CONFIG_DIR must not contain '..'")
fi
if [[ -n "${SM_E2E_FILE_NAME:-}" && ! "${SM_E2E_FILE_NAME}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$ ]]; then
  problems+=("SM_E2E_FILE_NAME is not a file name ([A-Za-z0-9][A-Za-z0-9_.-]*)")
fi
if [[ -n "${SM_E2E_SSH_PORT:-}" && ! "${SM_E2E_SSH_PORT}" =~ ^[0-9]{1,5}$ ]]; then
  problems+=("SM_E2E_SSH_PORT is not a port number")
fi
if [[ -n "${SM_E2E_SSH_HOST:-}" && ! "${SM_E2E_SSH_HOST}" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*$ ]]; then
  problems+=("SM_E2E_SSH_HOST is not a host name")
fi
if [[ -n "${SM_E2E_SSH_USER:-}" && ! "${SM_E2E_SSH_USER}" =~ ^[A-Za-z_][A-Za-z0-9_-]{0,31}$ ]]; then
  problems+=("SM_E2E_SSH_USER is not a user name")
fi
if [[ "${SM_E2E_CONFIRM_WRITE:-}" != "yes" ]]; then
  problems+=("SM_E2E_CONFIRM_WRITE is not 'yes': this run overwrites the second Cache's configuration file (${SM_E2E_SECOND_CONFIG_DIR:-<SM_E2E_SECOND_CONFIG_DIR>}) on the host, then restores it")
fi
if [[ -n "${SM_E2E_CONFIRM_CLEANUP:-}" && "${SM_E2E_CONFIRM_CLEANUP}" != "yes" ]]; then
  problems+=("SM_E2E_CONFIRM_CLEANUP must be exactly 'yes' or unset (it deletes files on the host; 'true' or '1' is refused)")
fi
if (( ${#problems[@]} > 0 )); then
  echo "run-e2e-undeclare.sh: not running; fix:" >&2
  for p in "${problems[@]}"; do echo "  - $p" >&2; done
  echo "(see the header of this script; nothing was started and nothing was contacted)" >&2
  exit 2
fi

# ---- the host-side commands (built from validated values only) ----------------------------------------------------------
ssh_port="${SM_E2E_SSH_PORT:-22}"
file_name="${SM_E2E_FILE_NAME:-application.yml}"
second_dir="${SM_E2E_SECOND_CONFIG_DIR}"
second="${SM_E2E_SECOND_CONTAINER}"
inspect_cmd="docker inspect --format '{{.Id}} {{.State.StartedAt}} running={{.State.Running}}' -- ${second}"
list_cmd="ls -la -- ${second_dir}"
# Exactly the names SM writes (SshDockerRuntimeAdapter): .<file>.sm-preimage-<n>[.absent] and .<file>.sm-fp-<n>, <n> digits. Never the
# live file, and never a near miss such as an operator's own .<file>.sm-preimage-3.bak (W5 review W5B-7): the glob only narrows the
# candidates; grep -E (POSIX, so it does not depend on GNU find's -regextype) keeps the exact shapes. SM_E2E_FILE_NAME is validated
# to [A-Za-z0-9_.-], so only '.' needs escaping in the pattern. `|| true` because grep exits 1 when nothing matches.
file_re="${file_name//./\\.}"
hidden_re='^\./\.'"${file_re}"'\.sm-(preimage-[0-9]+(\.absent)?|fp-[0-9]+)$'
find_hidden="cd -- ${second_dir} && find . -maxdepth 1 -type f -name '.${file_name}.sm-*' | { grep -E '${hidden_re}' || true; }"
cleanup_cmd="${find_hidden} | while IFS= read -r f; do rm -f -- \"\$f\" && printf '%s\\n' \"\$f\"; done"

# ---- assemble the Maven command: the target as -D properties --------------------------------------------------------------
evidence_dir="${SM_E2E_EVIDENCE_DIR:-$repo/backend/launcher/target/walkthrough-evidence}"
props=(
  "-Dsm.walkthrough.ssh.host=${SM_E2E_SSH_HOST}"
  "-Dsm.walkthrough.ssh.port=${ssh_port}"
  "-Dsm.walkthrough.ssh.user=${SM_E2E_SSH_USER}"
  "-Dsm.walkthrough.ssh.fingerprint=${SM_E2E_SSH_FINGERPRINT}"
  "-Dsm.walkthrough.ssh.key-file=${SM_E2E_SSH_KEY_FILE}"
  "-Dsm.walkthrough.ssh.container=${SM_E2E_CONTAINER}"
  "-Dsm.walkthrough.config-dir=${SM_E2E_CONFIG_DIR}"
  "-Dsm.walkthrough.evidence.dir=${evidence_dir}"
  "-Dsm.walkthrough.undeclare.second.container=${SM_E2E_SECOND_CONTAINER}"
  "-Dsm.walkthrough.undeclare.second.config-dir=${SM_E2E_SECOND_CONFIG_DIR}"
  "-Dsm.walkthrough.undeclare.second.host-port=${SM_E2E_SECOND_HOST_PORT}"
)
[[ -n "${SM_E2E_CONTAINER_CONFIG_PATH:-}" ]] && props+=("-Dsm.walkthrough.container-config-path=${SM_E2E_CONTAINER_CONFIG_PATH}")
[[ -n "${SM_E2E_FILE_NAME:-}" ]] && props+=("-Dsm.walkthrough.file-name=${SM_E2E_FILE_NAME}")
[[ -n "${SM_E2E_HEALTH_PORT:-}" ]] && props+=("-Dsm.walkthrough.health-port=${SM_E2E_HEALTH_PORT}")
[[ -n "${SM_E2E_ACQUISITION:-}" ]] && props+=("-Dsm.walkthrough.acquisition=${SM_E2E_ACQUISITION}")
[[ -n "${SM_E2E_NAMESPACE:-}" ]] && props+=("-Dsm.walkthrough.cache.namespace=${SM_E2E_NAMESPACE}")

if [[ -n "${SM_E2E_MVN:-}" ]]; then mvn_cmd="${SM_E2E_MVN}"
elif command -v mvn >/dev/null 2>&1; then mvn_cmd="mvn"
else mvn_cmd="$repo/backend/mvnw"; fi
# shellcheck disable=SC2206   # SM_E2E_MVN_OPTS is a space-separated list of options by contract
mvn_opts=(${SM_E2E_MVN_OPTS--o})

# Only this IT. -Dtest=NoSuchUnit keeps surefire from running every unit test of the modules built with -am.
cmd=("$mvn_cmd" "${mvn_opts[@]}" -f "$repo/backend/pom.xml" -pl launcher -am verify
     -Dtest=NoSuchUnit -Dit.test=UndeclareLiveIT
     -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false "${props[@]}")

echo "== plan 012 T5 live gate L5: undeclare an instance of the second Cache; the host is untouched =="
echo "target     : ssh ${SM_E2E_SSH_USER}@${SM_E2E_SSH_HOST}:${ssh_port}  first container ${SM_E2E_CONTAINER} (not touched)  mode ${SM_E2E_ACQUISITION:-via-ssh}"
echo "second     : container ${second}, directory ${second_dir}, host port ${SM_E2E_SECOND_HOST_PORT} (must already be running)"
echo "WRITES     : the second Cache's configuration file, once, restored to its ORIGINAL keyed hash before the undeclare; SM's hidden files appear beside it"
echo "stops      : nothing. No container is stopped, restarted or removed by this gate."
echo "host side  : before and after (over this script's own pinned SSH session): ${inspect_cmd}"
echo "             and: ${list_cmd}"
echo "cleanup    : ${cleanup_cmd}"
if [[ "${SM_E2E_CONFIRM_CLEANUP:-}" == "yes" ]]; then
  echo "             (SM_E2E_CONFIRM_CLEANUP=yes: it WILL be run, after the container check, and listed again)"
else
  echo "             (not confirmed: it will only be printed. L5 is NOT closed until SM_E2E_CONFIRM_CLEANUP=yes has been run)"
fi
echo "SM events  : a LOCAL broker started by the run from solace-pubsub-enterprise:10.25.0.177 (never a remote broker)"
echo "evidence   : ${evidence_dir}/UndeclareLiveIT-<UTC>.md and ${evidence_dir}/L5-host-{before,after,after-cleanup}.txt"
echo "command    : ${cmd[*]}"

if $check_only; then
  echo "(--check: validated, nothing was run and nothing was contacted)"
  exit 0
fi

# ---- the host side ---------------------------------------------------------------------------------------------------------
mkdir -p "${evidence_dir}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
known_hosts="$work/known_hosts"

# Pin the host key to SM_E2E_SSH_FINGERPRINT before trusting it: the scan is compared, never accepted on first use.
pin_host_key() {
  local scanned
  scanned="$(ssh-keyscan -p "${ssh_port}" "${SM_E2E_SSH_HOST}" 2>/dev/null)" || return 1
  local line
  while IFS= read -r line; do
    [[ -z "$line" || "$line" == \#* ]] && continue
    if [[ "$(printf '%s\n' "$line" | ssh-keygen -lf - -E sha256 2>/dev/null | awk '{print $2}')" == "${SM_E2E_SSH_FINGERPRINT}" ]]; then
      printf '%s\n' "$line" > "$known_hosts"
      return 0
    fi
  done <<< "$scanned"
  return 1
}

host() {   # one command on the host, over the pinned key; nothing else is ever sent
  ssh -i "${SM_E2E_SSH_KEY_FILE}" -p "${ssh_port}" -o BatchMode=yes -o IdentitiesOnly=yes \
      -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$known_hosts" \
      "${SM_E2E_SSH_USER}@${SM_E2E_SSH_HOST}" "$1"
}

if ! pin_host_key; then
  echo "run-e2e-undeclare.sh: the host did not offer a key whose fingerprint is SM_E2E_SSH_FINGERPRINT; nothing was run" >&2
  exit 2
fi

before_inspect="$(host "${inspect_cmd}")" || { echo "the second container cannot be inspected on the host; nothing was run" >&2; exit 2; }
case "$before_inspect" in *running=true*) ;; *) echo "the second container is not running (${before_inspect}); nothing was run" >&2; exit 2 ;; esac
{ echo "# L5 host side, BEFORE the run: $(date -u +%Y-%m-%dT%H:%M:%SZ)"; echo "# ${inspect_cmd}"; echo "$before_inspect";
  echo "# ${list_cmd}"; host "${list_cmd}"; } > "${evidence_dir}/L5-host-before.txt"
echo "before     : ${before_inspect}"

if [[ -n "${SM_E2E_TC_ENV:-}" ]]; then
  # shellcheck disable=SC1090
  source "${SM_E2E_TC_ENV}"
fi

rm -f "$repo/backend/launcher/target/failsafe-reports/failsafe-summary.xml"
set +e
"${cmd[@]}"
status=$?
set -e

echo
echo "== the run finished with exit status $status =="
after_inspect="$(host "${inspect_cmd}")" || after_inspect="<could not inspect>"
{ echo "# L5 host side, AFTER the run: $(date -u +%Y-%m-%dT%H:%M:%SZ)"; echo "# ${inspect_cmd}"; echo "$after_inspect";
  echo "# ${list_cmd}"; host "${list_cmd}" || true; } > "${evidence_dir}/L5-host-after.txt"
echo "after      : ${after_inspect}"

gate_failed=false
if [[ "$status" -ne 0 ]]; then
  echo "L5 FAILED: the run itself failed (read the evidence; a MANUAL line means a file was not put back)." >&2
  gate_failed=true
fi
if [[ "$after_inspect" != "$before_inspect" ]]; then
  echo "L5 FAILED: the container CHANGED across the run (before: ${before_inspect}; after: ${after_inspect}). Undeclare must not touch the host." >&2
  gate_failed=true
else
  echo "container  : UNTOUCHED - the same id, the same start time, still running"
fi
if $gate_failed; then
  echo "next: the hidden files are still on the host; clean them by hand only after you have read the evidence." >&2
  exit 1
fi

echo "left on the host by SM (to be cleaned): $(host "${find_hidden}" | tr '\n' ' ')"
if [[ "${SM_E2E_CONFIRM_CLEANUP:-}" != "yes" ]]; then
  echo "L5 is OPEN: the cleanup was not confirmed. To close it, rerun the cleanup with SM_E2E_CONFIRM_CLEANUP=yes, or run on the host:"
  echo "    ${cleanup_cmd}"
  echo "and record the listing before and after in plan 012 T5's Results."
  exit 3
fi

removed="$(host "${cleanup_cmd}")" || { echo "L5 FAILED: the cleanup command failed on the host" >&2; exit 1; }
{ echo "# L5 host side, AFTER the cleanup: $(date -u +%Y-%m-%dT%H:%M:%SZ)"; echo "# ${cleanup_cmd}"; echo "removed:"; echo "$removed";
  echo "# ${list_cmd}"; host "${list_cmd}"; echo "# remaining hidden SM files:"; host "${find_hidden}" || true; } \
  > "${evidence_dir}/L5-host-after-cleanup.txt"
if [[ -n "$(host "${find_hidden}")" ]]; then
  echo "L5 FAILED: hidden SM files remain after the cleanup" >&2
  exit 1
fi
echo "cleanup    : removed: $(echo "$removed" | tr '\n' ' ')"
echo "L5         : PASSED - the container was untouched, the instance was released, and the host directory is clean"
echo "next: record the three L5-host-*.txt files and the UndeclareLiveIT evidence in plan 012 T5's Results."
exit 0
