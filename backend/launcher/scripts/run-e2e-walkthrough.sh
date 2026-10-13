#!/usr/bin/env bash
#
# Plan 010 T6.6 / OR-46: run the first dp-remote end-to-end walkthrough (SkeletonWalkthroughIT) and the backend
# resilience scenarios (MeshResilienceIT) against an OPERATOR-PROVIDED SSH-Docker host that runs the Cache.
#
# *** THIS WRITES TO THE HOST. *** It overwrites the Cache's configuration file (and leaves SM's hidden retained
# pre-image files, .<file>.sm-preimage-<generation>, in that directory); one scenario restarts the Cache container. The
# run reads the file's ORIGINAL keyed hash first and its last step restores the original through the engine's own restore
# (or prints the exact manual restore). Read docs/plans/backend/evidence/010-first-e2e-walkthrough.md before running.
#
# Nothing here looks for a host: the target is whatever you put in the environment. Without it the ITs are SKIPPED
# (never silently green) and the OR-46 claim is not made.
#
# The SM mesh events of the run go to a LOCAL Solace broker the run starts from the local image
# solace-pubsub-enterprise:10.25.0.177 (Testcontainers; the image is loaded from the support-portal tarball, never pulled).
# The run also needs Docker for a throwaway PostgreSQL. It never talks to any other broker.
#
# ---- target (required) ----------------------------------------------------------------------------------------------
#   SM_E2E_SSH_HOST          the SSH host
#   SM_E2E_SSH_USER          the login user (must be able to run `docker` and write the config directory)
#   SM_E2E_SSH_FINGERPRINT   the host key to pin, SHA256:<base64> (ssh-keygen -lf <(ssh-keyscan -t ed25519 HOST) -E sha256)
#   SM_E2E_SSH_KEY_FILE      the path of the SSH private key FILE (read once, sealed in the throwaway database, zeroised)
#   SM_E2E_CONTAINER         the Cache container's name (also the registered envelope's container prefix)
#   SM_E2E_CONFIG_DIR        the host directory the Cache's config mount reads (also the envelope's config root)
#   SM_E2E_CONFIRM_WRITE     must be "yes": you know this run overwrites that directory's config file and restarts the Cache
# ---- target (optional) ----------------------------------------------------------------------------------------------
#   SM_E2E_SSH_PORT (22)  SM_E2E_CONTAINER_CONFIG_PATH (/app/external/spring/config)  SM_E2E_FILE_NAME (application.yml)
#   SM_E2E_HEALTH_PORT (8081)  SM_E2E_ACQUISITION (via-ssh | direct-http; default via-ssh)  SM_E2E_NAMESPACE
#   SM_E2E_RESTORE_RESTART (true | false: restart the container at the end if the run restarted it)
# ---- the Cache's own broker (optional, all three together) --------------------------------------------------------------
#   SM_E2E_BROKER_HOST  SM_E2E_BROKER_VPN  SM_E2E_BROKER_USERNAME
#   SM_E2E_BROKER_PASSWORD   ENVIRONMENT ONLY. Never a flag, never a -D property, never printed: it is staged as a secret
#                            reference and only the reference reaches the config of record (I6).
# ---- the run ------------------------------------------------------------------------------------------------------------
#   SM_E2E_EVIDENCE_DIR      where the evidence files go (default backend/launcher/target/walkthrough-evidence)
#   SM_E2E_MVN               the Maven command (default: mvn, else backend/mvnw)
#   SM_E2E_MVN_OPTS          extra Maven options (default: -o, offline)
#   SM_E2E_TC_ENV            a file to source first (Testcontainers/Docker/JAVA_HOME settings for your machine)
#
# Usage:   SM_E2E_...=... backend/launcher/scripts/run-e2e-walkthrough.sh [--check]
#   --check   validate the environment and print the command (the password is never shown); run nothing.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"      # backend/launcher/scripts -> repo root

check_only=false
for arg in "$@"; do
  case "$arg" in
    --check) check_only=true ;;
    -h|--help) sed -n '2,45p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown argument: $arg (only --check is accepted; the target comes from the environment)" >&2; exit 2 ;;
  esac
done

# ---- validate: name EVERY problem at once, never a value --------------------------------------------------------------
problems=()
for name in SM_E2E_SSH_HOST SM_E2E_SSH_USER SM_E2E_SSH_FINGERPRINT SM_E2E_SSH_KEY_FILE SM_E2E_CONTAINER SM_E2E_CONFIG_DIR; do
  if [[ -z "${!name:-}" ]]; then problems+=("$name is not set"); fi
done
if [[ -n "${SM_E2E_SSH_KEY_FILE:-}" && ! -r "${SM_E2E_SSH_KEY_FILE}" ]]; then
  problems+=("SM_E2E_SSH_KEY_FILE does not name a readable file")
fi
if [[ -n "${SM_E2E_SSH_FINGERPRINT:-}" && ! "${SM_E2E_SSH_FINGERPRINT}" =~ ^SHA256:[A-Za-z0-9+/]{20,86}=*$ ]]; then
  problems+=("SM_E2E_SSH_FINGERPRINT is not SHA256:<base64>")
fi
broker_given=0
for name in SM_E2E_BROKER_HOST SM_E2E_BROKER_VPN SM_E2E_BROKER_USERNAME; do
  if [[ -n "${!name:-}" ]]; then broker_given=$((broker_given + 1)); fi
done
if (( broker_given != 0 && broker_given != 3 )); then
  problems+=("the Cache broker needs SM_E2E_BROKER_HOST, SM_E2E_BROKER_VPN and SM_E2E_BROKER_USERNAME together")
fi
if (( broker_given == 3 )) && [[ -z "${SM_E2E_BROKER_PASSWORD:-}" ]]; then
  problems+=("SM_E2E_BROKER_PASSWORD is not set (environment only; required with the Cache broker)")
fi
if (( broker_given == 0 )) && [[ -n "${SM_E2E_BROKER_PASSWORD:-}" ]]; then
  problems+=("SM_E2E_BROKER_PASSWORD is set but no Cache broker host/vpn/username is: refusing to carry a password nothing uses")
fi
if [[ "${SM_E2E_CONFIRM_WRITE:-}" != "yes" ]]; then
  problems+=("SM_E2E_CONFIRM_WRITE is not 'yes': this run overwrites the Cache's config file in ${SM_E2E_CONFIG_DIR:-<SM_E2E_CONFIG_DIR>} on the host and restarts the container")
fi
if (( ${#problems[@]} > 0 )); then
  echo "run-e2e-walkthrough.sh: not running; fix:" >&2
  for p in "${problems[@]}"; do echo "  - $p" >&2; done
  echo "(see the header of this script; nothing was started and nothing was contacted)" >&2
  exit 2
fi

# ---- assemble the Maven command: the target as -D properties, the password NOT among them ------------------------------
evidence_dir="${SM_E2E_EVIDENCE_DIR:-$repo/backend/launcher/target/walkthrough-evidence}"
props=(
  "-Dsm.walkthrough.ssh.host=${SM_E2E_SSH_HOST}"
  "-Dsm.walkthrough.ssh.port=${SM_E2E_SSH_PORT:-22}"
  "-Dsm.walkthrough.ssh.user=${SM_E2E_SSH_USER}"
  "-Dsm.walkthrough.ssh.fingerprint=${SM_E2E_SSH_FINGERPRINT}"
  "-Dsm.walkthrough.ssh.key-file=${SM_E2E_SSH_KEY_FILE}"
  "-Dsm.walkthrough.ssh.container=${SM_E2E_CONTAINER}"
  "-Dsm.walkthrough.config-dir=${SM_E2E_CONFIG_DIR}"
  "-Dsm.walkthrough.evidence.dir=${evidence_dir}"
)
[[ -n "${SM_E2E_CONTAINER_CONFIG_PATH:-}" ]] && props+=("-Dsm.walkthrough.container-config-path=${SM_E2E_CONTAINER_CONFIG_PATH}")
[[ -n "${SM_E2E_FILE_NAME:-}" ]] && props+=("-Dsm.walkthrough.file-name=${SM_E2E_FILE_NAME}")
[[ -n "${SM_E2E_HEALTH_PORT:-}" ]] && props+=("-Dsm.walkthrough.health-port=${SM_E2E_HEALTH_PORT}")
[[ -n "${SM_E2E_ACQUISITION:-}" ]] && props+=("-Dsm.walkthrough.acquisition=${SM_E2E_ACQUISITION}")
[[ -n "${SM_E2E_NAMESPACE:-}" ]] && props+=("-Dsm.walkthrough.cache.namespace=${SM_E2E_NAMESPACE}")
[[ -n "${SM_E2E_RESTORE_RESTART:-}" ]] && props+=("-Dsm.walkthrough.restore.restart=${SM_E2E_RESTORE_RESTART}")
if (( broker_given == 3 )); then
  props+=("-Dsm.walkthrough.cache.broker.host=${SM_E2E_BROKER_HOST}"
          "-Dsm.walkthrough.cache.broker.vpn=${SM_E2E_BROKER_VPN}"
          "-Dsm.walkthrough.cache.broker.username=${SM_E2E_BROKER_USERNAME}")
fi

if [[ -n "${SM_E2E_MVN:-}" ]]; then mvn_cmd="${SM_E2E_MVN}"
elif command -v mvn >/dev/null 2>&1; then mvn_cmd="mvn"
else mvn_cmd="$repo/backend/mvnw"; fi
# shellcheck disable=SC2206   # SM_E2E_MVN_OPTS is a space-separated list of options by contract
mvn_opts=(${SM_E2E_MVN_OPTS--o})

# Only these two ITs. -Dtest=NoSuchUnit keeps surefire from running every unit test of the modules built with -am.
cmd=("$mvn_cmd" "${mvn_opts[@]}" -f "$repo/backend/pom.xml" -pl launcher -am verify
     -Dtest=NoSuchUnit -Dit.test=SkeletonWalkthroughIT,MeshResilienceIT
     -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false "${props[@]}")

echo "== plan 010 T6.6 live walkthrough =="
echo "target     : ssh ${SM_E2E_SSH_USER}@${SM_E2E_SSH_HOST}:${SM_E2E_SSH_PORT:-22}  container ${SM_E2E_CONTAINER}"
echo "writes to  : ${SM_E2E_CONFIG_DIR}/${SM_E2E_FILE_NAME:-application.yml}  (original keyed hash is read first; restored at the end)"
if (( broker_given == 3 )); then
  echo "Cache broker: ${SM_E2E_BROKER_HOST} vpn ${SM_E2E_BROKER_VPN} as ${SM_E2E_BROKER_USERNAME}  password: from SM_E2E_BROKER_PASSWORD (environment only, not shown)"
else
  echo "Cache broker: none (the staged config keeps solace.enabled=false)"
fi
echo "SM events  : a LOCAL broker started by the run from solace-pubsub-enterprise:10.25.0.177 (never a remote broker)"
echo "evidence   : ${evidence_dir}/SkeletonWalkthroughIT-<UTC>.md and MeshResilienceIT-<UTC>.md"
echo "failsafe   : $repo/backend/launcher/target/failsafe-reports/"
echo "command    : ${cmd[*]}"

if $check_only; then
  echo "(--check: validated, nothing was run and nothing was contacted)"
  exit 0
fi

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
echo "== finished with exit status $status =="
echo "evidence files written by this run:"
ls -1t "${evidence_dir}"/SkeletonWalkthroughIT-*.md "${evidence_dir}"/MeshResilienceIT-*.md 2>/dev/null | head -4 || true
echo "next: copy their content into docs/plans/backend/evidence/010-first-e2e-walkthrough.md (the template's sections),"
echo "      add the Docker state before/after and the host's directory listing, and record any skip with its reason."
echo "      If the evidence says 'MANUAL:' under the restore section, the host's config file was NOT restored: do it now."
exit "$status"
