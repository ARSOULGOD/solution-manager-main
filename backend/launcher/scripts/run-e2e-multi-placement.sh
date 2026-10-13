#!/usr/bin/env bash
#
# Plan 008 S5 Step 2 (live gate L2): run ONLY the live multi-placement gate (MultiPlacementLiveIT) against an OPERATOR-PROVIDED
# SSH-Docker host that runs TWO Cache containers: the first, and a second one the customer's CI/CD has already deployed (SM
# deploys nothing, OR-29; OR-110 allows a second Cache on the E2E host "if needed"). The same target as run-e2e-walkthrough.sh,
# the same SM_E2E_* variables, and the same sm.walkthrough.ssh.* properties underneath.
#
# *** THIS WRITES TO THE HOST. ***  Both Caches' configuration files are overwritten by a `manual` fleet (their directories
# also receive SM's hidden retained pre-image files). The ORIGINAL keyed hash of each file is read first and held in memory and
# in the evidence; the run restores both files through the engine's own restore and FAILS LOUDLY if it could not. SM's database,
# local mesh broker and registered rows are this run's own throwaway Testcontainers. Like run-e2e-walkthrough.sh, it refuses to
# run unless SM_E2E_CONFIRM_WRITE=yes (exactly that word): you know this run overwrites both Caches' config files.
#
# *** WITH SM_E2E_CONFIRM_DISRUPT=yes IT ALSO STOPS THE SECOND CACHE, STOPS BOTH, AND RESTARTS BOTH. ***  The plan's L2 asks for
# all three (stop P2; instance-wide stop; restart both). A container the run stopped is started again at the end (and the run
# FAILS LOUDLY if it could not), and after the restore both containers are restarted so they RUN the original configuration.
# OR-110 already authorises stopping and restarting the Cache and adding a second one on the E2E host, so no further owner
# permission is needed for it; the switch stays an explicit opt-in. Do not set it on any other host.
#
# *** ONLY A DISRUPTIVE PASS SATISFIES L2. *** L2 as planned requires stopping P2, the instance-wide stop and restarting both.
# A run without SM_E2E_CONFIRM_DISRUPT=yes skips those three steps (recording each skip) and DOES NOT CLOSE L2: it is a
# partial rehearsal on the real host.
#
# Nothing here looks for a host: the target is whatever you put in the environment. Without it the IT is SKIPPED with its
# reason (run the Maven command from --check without the -D properties to see the skip). THE L2 CLAIM IS NOT MADE until a
# DISRUPTIVE run of this script has passed against the host and its evidence has been recorded.
#
# The SM mesh events of the run go to a LOCAL Solace broker started from the local image solace-pubsub-enterprise:10.25.0.177
# (Testcontainers; the image is loaded from the support-portal tarball, never pulled). It also needs Docker for a throwaway
# PostgreSQL. It never talks to any other broker.
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
#   SM_E2E_SECOND_HOST_PORT  the HOST port its container port 8081 is published on (health and metrics both); must differ from
#                            the first Cache's (SM_E2E_HEALTH_PORT, default 8081)
#   The second container must already be RUNNING on the host, with its own config file mounted from that directory.
# ---- target (optional, the walkthrough's own) -----------------------------------------------------------------------------
#   SM_E2E_SSH_PORT (22)  SM_E2E_HEALTH_PORT (8081)  SM_E2E_ACQUISITION (via-ssh | direct-http; default via-ssh)
#   SM_E2E_CONTAINER_CONFIG_PATH  SM_E2E_FILE_NAME  SM_E2E_NAMESPACE
# ---- this gate ----------------------------------------------------------------------------------------------------------
#   SM_E2E_CONFIRM_WRITE     exactly "yes", REQUIRED: you know this run overwrites both Caches' config files (and restores them)
#   SM_E2E_CONFIRM_DISRUPT   exactly "yes": stop P2, stop both, restart both (see above). Never the default. Needed for L2.
# ---- the run ------------------------------------------------------------------------------------------------------------
#   SM_E2E_EVIDENCE_DIR      where the evidence file goes (default backend/launcher/target/walkthrough-evidence)
#   SM_E2E_MVN               the Maven command (default: mvn, else backend/mvnw)
#   SM_E2E_MVN_OPTS          extra Maven options (default: -o, offline)
#   SM_E2E_TC_ENV            a file to source first (Testcontainers/Docker/JAVA_HOME settings for your machine)
#
# Usage:   SM_E2E_...=... backend/launcher/scripts/run-e2e-multi-placement.sh [--check]
#   --check   validate the environment and print the command; run nothing, contact nothing.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"      # backend/launcher/scripts -> repo root

check_only=false
for arg in "$@"; do
  case "$arg" in
    --check) check_only=true ;;
    -h|--help) sed -n '2,57p' "${BASH_SOURCE[0]}"; exit 0 ;;
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
if [[ "${SM_E2E_CONFIRM_WRITE:-}" != "yes" ]]; then
  problems+=("SM_E2E_CONFIRM_WRITE is not 'yes': this run overwrites the configuration files of BOTH Caches (${SM_E2E_CONFIG_DIR:-<SM_E2E_CONFIG_DIR>} and ${SM_E2E_SECOND_CONFIG_DIR:-<SM_E2E_SECOND_CONFIG_DIR>}) on the host, then restores them")
fi
if [[ -n "${SM_E2E_CONFIRM_DISRUPT:-}" && "${SM_E2E_CONFIRM_DISRUPT}" != "yes" ]]; then
  problems+=("SM_E2E_CONFIRM_DISRUPT must be exactly 'yes' or unset (it stops and restarts containers; 'true' or '1' is refused)")
fi
if (( ${#problems[@]} > 0 )); then
  echo "run-e2e-multi-placement.sh: not running; fix:" >&2
  for p in "${problems[@]}"; do echo "  - $p" >&2; done
  echo "(see the header of this script; nothing was started and nothing was contacted)" >&2
  exit 2
fi

# ---- assemble the Maven command: the target as -D properties --------------------------------------------------------------
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
  "-Dsm.walkthrough.multi.second.container=${SM_E2E_SECOND_CONTAINER}"
  "-Dsm.walkthrough.multi.second.config-dir=${SM_E2E_SECOND_CONFIG_DIR}"
  "-Dsm.walkthrough.multi.second.host-port=${SM_E2E_SECOND_HOST_PORT}"
)
[[ -n "${SM_E2E_CONTAINER_CONFIG_PATH:-}" ]] && props+=("-Dsm.walkthrough.container-config-path=${SM_E2E_CONTAINER_CONFIG_PATH}")
[[ -n "${SM_E2E_FILE_NAME:-}" ]] && props+=("-Dsm.walkthrough.file-name=${SM_E2E_FILE_NAME}")
[[ -n "${SM_E2E_HEALTH_PORT:-}" ]] && props+=("-Dsm.walkthrough.health-port=${SM_E2E_HEALTH_PORT}")
[[ -n "${SM_E2E_ACQUISITION:-}" ]] && props+=("-Dsm.walkthrough.acquisition=${SM_E2E_ACQUISITION}")
[[ -n "${SM_E2E_NAMESPACE:-}" ]] && props+=("-Dsm.walkthrough.cache.namespace=${SM_E2E_NAMESPACE}")
[[ "${SM_E2E_CONFIRM_DISRUPT:-}" == "yes" ]] && props+=("-Dsm.walkthrough.multi.disruptive=yes")

if [[ -n "${SM_E2E_MVN:-}" ]]; then mvn_cmd="${SM_E2E_MVN}"
elif command -v mvn >/dev/null 2>&1; then mvn_cmd="mvn"
else mvn_cmd="$repo/backend/mvnw"; fi
# shellcheck disable=SC2206   # SM_E2E_MVN_OPTS is a space-separated list of options by contract
mvn_opts=(${SM_E2E_MVN_OPTS--o})

# Only this IT. -Dtest=NoSuchUnit keeps surefire from running every unit test of the modules built with -am.
cmd=("$mvn_cmd" "${mvn_opts[@]}" -f "$repo/backend/pom.xml" -pl launcher -am verify
     -Dtest=NoSuchUnit -Dit.test=MultiPlacementLiveIT
     -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false "${props[@]}")

echo "== plan 008 S5 live gate L2: one instance, two placements on one target =="
echo "target     : ssh ${SM_E2E_SSH_USER}@${SM_E2E_SSH_HOST}:${SM_E2E_SSH_PORT:-22}  first container ${SM_E2E_CONTAINER}  mode ${SM_E2E_ACQUISITION:-via-ssh}"
echo "second     : container ${SM_E2E_SECOND_CONTAINER}, directory ${SM_E2E_SECOND_CONFIG_DIR}, host port ${SM_E2E_SECOND_HOST_PORT} (must already be running)"
echo "WRITES     : both Caches' configuration files (a manual fleet), each restored to its ORIGINAL keyed hash by the run's last steps"
if [[ "${SM_E2E_CONFIRM_DISRUPT:-}" == "yes" ]]; then
  echo "DISRUPTIVE : *** stops the second Cache, then stops BOTH, then restarts BOTH, and restarts both again after the restore (SM_E2E_CONFIRM_DISRUPT=yes) ***"
else
  echo "disruptive : no. No container is stopped or restarted; the stop and restart steps are skipped and recorded as skipped."
  echo "L2         : a NON-disruptive pass does NOT close L2 (L2 needs: stop P2, stop the instance, restart both). Set SM_E2E_CONFIRM_DISRUPT=yes for the run that can."
fi
echo "SM events  : a LOCAL broker started by the run from solace-pubsub-enterprise:10.25.0.177 (never a remote broker)"
echo "evidence   : ${evidence_dir}/MultiPlacementLiveIT-<UTC>.md"
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
echo "evidence written by this run:"
ls -1t "${evidence_dir}"/MultiPlacementLiveIT-*.md 2>/dev/null | head -2 || true
echo "next: record the evidence in the plan 008 Results (S5, live gate L2)."
echo "      If the evidence says 'MANUAL:' a file or a container this run changed was NOT put back: do it now."
exit "$status"
