#!/usr/bin/env bash
#
# Plan 015 T5.3: the LOCAL DRY RUN of the G-A2 driver (ga2-host.sh) against e2e-host-stub.sh, so nothing of the E2E host is contacted. It starts a
# throwaway broker (labelled), points the driver at it with a SYNTHETIC broker password (used only to prove the secret path), runs `ga2-host.sh all`
# on the host's architecture with the target OFF (no SSH target locally), so every step except the four remote_* ones, then removes the broker.
#
#   source recovered-scratchpad/tc-env.sh
#   recovered-scratchpad/docker-lock.sh ga2dry backend/local/ga2-dry-run.sh [log-file]
#
# Never run this against the real host: it sets GA2_HOST_CMD to the stub unconditionally.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOGF="${1:-$here/.state-ga2-dry/scenario.log}"
DRY="$here/.state-ga2-dry"; mkdir -p "$DRY"; chmod 700 "$DRY"
DRY_ID="$({ LC_ALL=C tr -dc 'a-z0-9' </dev/urandom | head -c 6; } || true)"
BROKER=ga2dry-broker; BPORT=55599
BROKER_IMAGE="${GA1_BROKER_IMAGE:-solace-pubsub-enterprise:10.25.0.177}"
if [[ "$(docker context show 2>/dev/null)" == colima* || "${DOCKER_HOST:-}" == *colima* ]]; then echo "error: Colima context; source tc-env.sh" >&2; exit 1; fi

cleanup() { docker rm -f "$BROKER" >/dev/null 2>&1 || true; rm -f "$DRY/broker.env"; }
trap cleanup EXIT
docker ps -a --format '{{.Names}}' | grep -qx "$BROKER" && { echo "error: $BROKER exists; not adopting it" >&2; exit 1; }
( umask 077; printf 'username_admin_globalaccesslevel=admin\nusername_admin_password=%s\nsystem_scaling_maxconnectioncount=100\n' "$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 20 || true)" > "$DRY/broker.env" )
docker run -d --name "$BROKER" --label "com.solace.sm.ga2dry=$DRY_ID" -p "127.0.0.1:$BPORT:55555" --shm-size 1g --ulimit core=-1 --ulimit nofile=2448:1048576 \
  --env-file "$DRY/broker.env" "$BROKER_IMAGE" >/dev/null
for i in $(seq 1 90); do
  docker exec "$BROKER" curl -s -f http://localhost:5550/health-check/guaranteed-active >/dev/null 2>&1 && break
  sleep 2; [[ $i -lt 90 ]] || { echo "error: the broker did not become active" >&2; exit 1; }
done
echo "==> dry-run broker active"

export GA2_HOST_CMD="$here/e2e-host-stub.sh"
export E2E_STUB_ROOT="$(cd "$DRY" && mkdir -p root && cd root && pwd -P)"
export E2E_STUB_LOG="$DRY/stub-commands.log"; : > "$E2E_STUB_LOG"
export SM_E2E_BROKER_HOST="tcp://host.docker.internal:$BPORT" SM_E2E_BROKER_VPN=default SM_E2E_BROKER_USERNAME=default
export SM_E2E_BROKER_PASSWORD="${GA2_DRY_PASSWORD:-synthetic-ga2-dry-secret-1}"
export SM_LOCAL_PORT="${SM_LOCAL_PORT:-8190}"
export GA1_TARGET=off GA2_GOOS="$(go env GOHOSTOS)" GA2_GOARCH="$(go env GOHOSTARCH)"
export GA2_SNAPSHOT_IGNORE='^(ga2dry-broker|ga2-pg|ga1-.*|testcontainers-.*|.*ryuk.*)$'   # the shared local daemon also carries this run's own broker and database
export GA2_CACHE_IMAGE="${GA2_CACHE_IMAGE:-solace-cache-engine:local}" GA2_EXISTING_HEALTH_OPTIONAL=1
export GA2_KNOWN_FINDINGS="${GA2_KNOWN_FINDINGS:-m3}"
export GA1_STEPS="${GA1_STEPS:-setup enrol apply1 metrics killsm undeclare drain revoke retire}"
rc=0
"$here/ga2-host.sh" all 2>&1 | tee "$LOGF" || rc=${PIPESTATUS[0]}
echo "==> ga2-host.sh all exited $rc; log $LOGF; stub command log $E2E_STUB_LOG"
# No secret may appear in any command string the host was given, nor in the log
for v in SM_E2E_BROKER_PASSWORD; do
  if grep -qF -- "${!v}" "$E2E_STUB_LOG" "$LOGF"; then echo "LEAK: the synthetic password appears in the stub command log or the run log" >&2; rc=9; fi
done
exit "$rc"
