#!/bin/bash
#
# Gate-G1 spike test runner — what `npm test` invokes. Tasks 2-5 inherit this sequence.
#
# Deliberately sequential: `a && b & c` would background the whole `a && b` chain and race
# the tests against provisioning. `docker compose up -d` is NOT here — the stack is brought
# up once (see README) and left running across tasks.

set -uo pipefail

cd "$(dirname "$0")"

# Certs must exist before nginx starts; regenerating them under a running nginx would leave it
# serving the old pair, so only generate when missing.
if [ ! -f certs/server.crt ]; then
  ./setup-certs.sh || exit 1
fi

# Broker readiness + provisioning. provision.sh waits for SEMP itself and is idempotent.
./provision.sh || exit 1

# The issuer is a separate npm package: `jose` lives in issuer/package.json, and the root
# package.json declares no workspaces, so a root `npm install` never installs it. On a fresh
# clone the issuer would die with ERR_MODULE_NOT_FOUND and this script would blame the port.
[ -d issuer/node_modules ] || (cd issuer && npm install) || exit 1

# The issuer is a long-lived background process. Start it only if :8090 is not already serving.
ISSUER_PID=""
if ! curl -sf http://localhost:8090/jwks.json >/dev/null 2>&1; then
  node issuer/issuer.mjs &
  ISSUER_PID=$!
  sleep 1
  if ! curl -sf http://localhost:8090/jwks.json >/dev/null 2>&1; then
    echo "issuer failed to come up on :8090"
    [ -n "$ISSUER_PID" ] && kill "$ISSUER_PID" 2>/dev/null
    exit 1
  fi
fi
# Only stop the issuer if THIS script started it.
cleanup() { [ -n "$ISSUER_PID" ] && kill "$ISSUER_PID" 2>/dev/null; }
trap cleanup EXIT

# Self-signed spike certs: the test files' comments assume npm test sets this.
# --no-file-parallelism keeps the spike's numbered test files running in order; they share one
# broker and would otherwise trip over each other's ACL state.
export NODE_TLS_REJECT_UNAUTHORIZED=0
npx vitest run --no-file-parallelism "$@"
