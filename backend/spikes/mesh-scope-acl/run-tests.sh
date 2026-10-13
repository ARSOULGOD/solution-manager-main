#!/bin/bash
#
# §11.3 spike test runner — what `npm test` invokes. Ports/paths: this spike's own broker
# (127.0.0.1:8081 SEMP / :8009 SMF / :9444 wss) and issuer (127.0.0.1:8091).
#
# Deliberately sequential — the numbered test files share one broker's ACL/session state.
#
# The Solace Web App SDK is NOT a package.json dependency (round 1, MINOR 9: it was an absolute
# `file:/Users/...` path, so the harness only ran on one machine). Point SOLACE_WEBAPP_SDK_PATH
# at a built checkout (must contain dist/solace-webapp-sdk.es.js); this script links it with
# `npm install --no-save`.

set -uo pipefail

cd "$(dirname "$0")"

if [ ! -f certs/server.crt ]; then
  ./setup-certs.sh || exit 1
fi

[ -d node_modules/vitest ] || npm install || exit 1
if [ ! -f node_modules/solace-webapp-sdk/dist/solace-webapp-sdk.es.js ]; then
  if [ -z "${SOLACE_WEBAPP_SDK_PATH:-}" ] || [ ! -f "$SOLACE_WEBAPP_SDK_PATH/dist/solace-webapp-sdk.es.js" ]; then
    echo "Set SOLACE_WEBAPP_SDK_PATH to a BUILT Solace Web App SDK checkout" >&2
    echo "(a directory containing dist/solace-webapp-sdk.es.js). See README 'SDK'." >&2
    exit 1
  fi
  npm install --no-save "file:$SOLACE_WEBAPP_SDK_PATH" || exit 1
fi

# Broker readiness + provisioning. provision.sh waits for SEMP itself and is idempotent.
./provision.sh || exit 1

[ -d issuer/node_modules ] || (cd issuer && npm install) || exit 1

ISSUER_PID=""
if ! curl -sf http://127.0.0.1:8091/jwks.json >/dev/null 2>&1; then
  node issuer/issuer.mjs &
  ISSUER_PID=$!
  sleep 1
  if ! curl -sf http://127.0.0.1:8091/jwks.json >/dev/null 2>&1; then
    echo "issuer failed to come up on 127.0.0.1:8091"
    [ -n "$ISSUER_PID" ] && kill "$ISSUER_PID" 2>/dev/null
    exit 1
  fi
fi
cleanup() { [ -n "$ISSUER_PID" ] && kill "$ISSUER_PID" 2>/dev/null; }
trap cleanup EXIT

mkdir -p evidence
: > evidence/legs.jsonl          # per-leg records are appended by tests/lib/common.mjs record()
export NODE_TLS_REJECT_UNAUTHORIZED=0
npx vitest run --no-file-parallelism --reporter=verbose "$@"
