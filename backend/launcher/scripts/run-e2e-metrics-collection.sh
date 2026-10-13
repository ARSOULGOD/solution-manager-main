#!/usr/bin/env bash
#
# Plan 011 P11-4.4: run ONLY the live background-collection gate (MetricsCollectionLiveIT) against an OPERATOR-PROVIDED
# SSH-Docker host that runs the Cache. The same target as run-e2e-walkthrough.sh, the same SM_E2E_* variables, and the same
# sm.walkthrough.ssh.* properties underneath.
#
# *** BY DEFAULT THIS ONLY READS. ***  The collector issues GETs (as a `curl` to the host's own loopback over SSH, or directly)
# and `docker inspect`; nothing is written to the host and no container is touched. SM's database, local mesh broker and
# registered rows are this run's own throwaway Testcontainers.
#
# *** WITH SM_E2E_CONFIRM_DISRUPT=yes IT ALSO RESTARTS ONE CACHE CONTAINER AND STOPS ONE. ***  The plan's Step 2 asks for
# both (exactly one series_reset after a restart; scrape_failed and a gap, never zeros, after a stop). A container the run
# stopped is started again at the end (and the run FAILS LOUDLY if it could not). Do not set it on a host whose Cache matters.
#
# Nothing here looks for a host: the target is whatever you put in the environment. Without it the IT is SKIPPED with its
# reason (run the Maven command from --check without the -D properties to see the skip).
#
# The SM mesh events of the run go to a LOCAL Solace broker started from the local image solace-pubsub-enterprise:10.25.0.177
# (Testcontainers; the image is loaded from the support-portal tarball, never pulled). It also needs Docker for a throwaway
# PostgreSQL. It never talks to any other broker.
#
# ---- target (required; the walkthrough's own) -----------------------------------------------------------------------------
#   SM_E2E_SSH_HOST          the SSH host                      SM_E2E_SSH_USER         the login user
#   SM_E2E_SSH_FINGERPRINT   the host key to pin, SHA256:<base64>
#   SM_E2E_SSH_KEY_FILE      the path of the SSH private key FILE (read once, sealed in the throwaway database, zeroised)
#   SM_E2E_CONTAINER         the Cache container's name (also the registered envelope's container prefix)
#   SM_E2E_CONFIG_DIR        the host directory the Cache's config mount reads (the registered envelope's config root;
#                            NOTHING is written to it by this run)
# ---- target (optional, the walkthrough's own) -----------------------------------------------------------------------------
#   SM_E2E_SSH_PORT (22)  SM_E2E_HEALTH_PORT (8081)  SM_E2E_ACQUISITION (via-ssh | direct-http; default via-ssh)
#   SM_E2E_CONTAINER_CONFIG_PATH  SM_E2E_FILE_NAME  SM_E2E_NAMESPACE
# ---- this gate ----------------------------------------------------------------------------------------------------------
#   SM_E2E_METRICS_MINUTES        how long the collector runs before the assertions (default 10)
#   SM_E2E_METRICS_HOST_PORT      the HOST port the first Cache's container port 8081 is published on (default SM_E2E_HEALTH_PORT)
#   SM_E2E_SECOND_CONTAINER, SM_E2E_SECOND_CONFIG_DIR, SM_E2E_SECOND_HOST_PORT
#                                 ALL THREE or none: a SECOND Cache on the same host (S-G, e.g. 18081 and 28081). Its container
#                                 name must start with SM_E2E_CONTAINER; its directory must lie under SM_E2E_CONFIG_DIR.
#   SM_E2E_SECOND_MODE            via-ssh | direct-http: how the second Cache is read (default: the first's mode). With
#                                 direct-http the data plane must reach the target's address at that port from THIS machine,
#                                 or that instance is skipped and the skip recorded; the target is never edited.
#   SM_E2E_ATTRIBUTION_TRAFFIC    exactly "yes": before the attribution step send 30 `GET /api/cache?key=sm-attribution-<n>`
#                                 requests to the SECOND Cache's loopback port only (absent keys: a 404 and a miss, nothing
#                                 is written) so the two Caches' cache.miss_count differ and attribution is provable by value.
#                                 Needs the second Cache. Never the default.
#   SM_E2E_CONFIRM_DISRUPT        exactly "yes": restart one Cache and stop one (see above). Never the default.
# ---- the run ------------------------------------------------------------------------------------------------------------
#   SM_E2E_EVIDENCE_DIR      where the evidence file goes (default backend/launcher/target/walkthrough-evidence)
#   SM_E2E_MVN               the Maven command (default: mvn, else backend/mvnw)
#   SM_E2E_MVN_OPTS          extra Maven options (default: -o, offline)
#   SM_E2E_TC_ENV            a file to source first (Testcontainers/Docker/JAVA_HOME settings for your machine)
#
# Usage:   SM_E2E_...=... backend/launcher/scripts/run-e2e-metrics-collection.sh [--check]
#   --check   validate the environment and print the command; run nothing, contact nothing.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"      # backend/launcher/scripts -> repo root

check_only=false
for arg in "$@"; do
  case "$arg" in
    --check) check_only=true ;;
    -h|--help) sed -n '2,56p' "${BASH_SOURCE[0]}"; exit 0 ;;
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
second_given=0
for name in SM_E2E_SECOND_CONTAINER SM_E2E_SECOND_CONFIG_DIR SM_E2E_SECOND_HOST_PORT; do
  if [[ -n "${!name:-}" ]]; then second_given=$((second_given + 1)); fi
done
if (( second_given != 0 && second_given != 3 )); then
  problems+=("a second Cache needs SM_E2E_SECOND_CONTAINER, SM_E2E_SECOND_CONFIG_DIR and SM_E2E_SECOND_HOST_PORT together")
fi
if (( second_given == 0 )) && [[ -n "${SM_E2E_SECOND_MODE:-}" ]]; then
  problems+=("SM_E2E_SECOND_MODE is set but no second Cache is")
fi
if [[ -n "${SM_E2E_CONFIRM_DISRUPT:-}" && "${SM_E2E_CONFIRM_DISRUPT}" != "yes" ]]; then
  problems+=("SM_E2E_CONFIRM_DISRUPT must be exactly 'yes' or unset (it restarts and stops a container; 'true' or '1' is refused)")
fi
if [[ -n "${SM_E2E_ATTRIBUTION_TRAFFIC:-}" && "${SM_E2E_ATTRIBUTION_TRAFFIC}" != "yes" ]]; then
  problems+=("SM_E2E_ATTRIBUTION_TRAFFIC must be exactly 'yes' or unset (it sends requests to the second Cache; 'true' or '1' is refused)")
fi
if [[ "${SM_E2E_ATTRIBUTION_TRAFFIC:-}" == "yes" ]] && (( second_given == 0 )); then
  problems+=("SM_E2E_ATTRIBUTION_TRAFFIC=yes needs a second Cache (the SM_E2E_SECOND_* variables): the traffic goes to it only")
fi
if (( ${#problems[@]} > 0 )); then
  echo "run-e2e-metrics-collection.sh: not running; fix:" >&2
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
)
[[ -n "${SM_E2E_CONTAINER_CONFIG_PATH:-}" ]] && props+=("-Dsm.walkthrough.container-config-path=${SM_E2E_CONTAINER_CONFIG_PATH}")
[[ -n "${SM_E2E_FILE_NAME:-}" ]] && props+=("-Dsm.walkthrough.file-name=${SM_E2E_FILE_NAME}")
[[ -n "${SM_E2E_HEALTH_PORT:-}" ]] && props+=("-Dsm.walkthrough.health-port=${SM_E2E_HEALTH_PORT}")
[[ -n "${SM_E2E_ACQUISITION:-}" ]] && props+=("-Dsm.walkthrough.acquisition=${SM_E2E_ACQUISITION}")
[[ -n "${SM_E2E_NAMESPACE:-}" ]] && props+=("-Dsm.walkthrough.cache.namespace=${SM_E2E_NAMESPACE}")
[[ -n "${SM_E2E_METRICS_MINUTES:-}" ]] && props+=("-Dsm.walkthrough.metrics.minutes=${SM_E2E_METRICS_MINUTES}")
[[ -n "${SM_E2E_METRICS_HOST_PORT:-}" ]] && props+=("-Dsm.walkthrough.metrics.host-port=${SM_E2E_METRICS_HOST_PORT}")
if (( second_given == 3 )); then
  props+=("-Dsm.walkthrough.metrics.second.container=${SM_E2E_SECOND_CONTAINER}"
          "-Dsm.walkthrough.metrics.second.config-dir=${SM_E2E_SECOND_CONFIG_DIR}"
          "-Dsm.walkthrough.metrics.second.host-port=${SM_E2E_SECOND_HOST_PORT}")
  [[ -n "${SM_E2E_SECOND_MODE:-}" ]] && props+=("-Dsm.walkthrough.metrics.second.mode=${SM_E2E_SECOND_MODE}")
fi
[[ "${SM_E2E_CONFIRM_DISRUPT:-}" == "yes" ]] && props+=("-Dsm.walkthrough.metrics.disruptive=yes")
[[ "${SM_E2E_ATTRIBUTION_TRAFFIC:-}" == "yes" ]] && props+=("-Dsm.walkthrough.metrics.traffic=yes")

if [[ -n "${SM_E2E_MVN:-}" ]]; then mvn_cmd="${SM_E2E_MVN}"
elif command -v mvn >/dev/null 2>&1; then mvn_cmd="mvn"
else mvn_cmd="$repo/backend/mvnw"; fi
# shellcheck disable=SC2206   # SM_E2E_MVN_OPTS is a space-separated list of options by contract
mvn_opts=(${SM_E2E_MVN_OPTS--o})

# Only this IT. -Dtest=NoSuchUnit keeps surefire from running every unit test of the modules built with -am.
cmd=("$mvn_cmd" "${mvn_opts[@]}" -f "$repo/backend/pom.xml" -pl launcher -am verify
     -Dtest=NoSuchUnit -Dit.test=MetricsCollectionLiveIT
     -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false "${props[@]}")

echo "== plan 011 P11-4.4 live collection gate =="
echo "target     : ssh ${SM_E2E_SSH_USER}@${SM_E2E_SSH_HOST}:${SM_E2E_SSH_PORT:-22}  container ${SM_E2E_CONTAINER}  mode ${SM_E2E_ACQUISITION:-via-ssh}"
echo "metrics    : first Cache host port ${SM_E2E_METRICS_HOST_PORT:-${SM_E2E_HEALTH_PORT:-8081}}; window ${SM_E2E_METRICS_MINUTES:-10} min"
if (( second_given == 3 )); then
  echo "second     : container ${SM_E2E_SECOND_CONTAINER} host port ${SM_E2E_SECOND_HOST_PORT} mode ${SM_E2E_SECOND_MODE:-${SM_E2E_ACQUISITION:-via-ssh}}"
else
  echo "second     : none (one Cache; S-G needs the three SM_E2E_SECOND_* variables)"
fi
if [[ "${SM_E2E_CONFIRM_DISRUPT:-}" == "yes" ]]; then
  echo "WRITES     : *** DISRUPTIVE: restarts one Cache container and STOPS one, then starts it again (SM_E2E_CONFIRM_DISRUPT=yes) ***"
else
  echo "writes     : none. READ-ONLY (GETs and docker inspect). No file is written, no container is touched."
fi
if [[ "${SM_E2E_ATTRIBUTION_TRAFFIC:-}" == "yes" ]]; then
  echo "traffic    : SM_E2E_ATTRIBUTION_TRAFFIC=yes: 30 x GET /api/cache?key=sm-attribution-<n> to ${SM_E2E_SECOND_CONTAINER}'s loopback port ${SM_E2E_SECOND_HOST_PORT} only (absent keys: misses, nothing written)"
fi
echo "SM events  : a LOCAL broker started by the run from solace-pubsub-enterprise:10.25.0.177 (never a remote broker)"
echo "evidence   : ${evidence_dir}/MetricsCollectionLiveIT-<UTC>.md"
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
ls -1t "${evidence_dir}"/MetricsCollectionLiveIT-*.md 2>/dev/null | head -2 || true
echo "next: copy its content into docs/design/backend/evidence/011-collection-live.md (the template's sections)."
echo "      If the evidence says 'MANUAL:' a container this run stopped was NOT started again: do it now."
exit "$status"
