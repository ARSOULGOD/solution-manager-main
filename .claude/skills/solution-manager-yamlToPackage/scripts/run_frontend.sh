#!/usr/bin/env bash
# Run the Solution Manager frontend's tooling inside the node:22 image (the repo needs Node >= 22.12; a host may
# have less). The repo is bind-mounted; node_modules and the Playwright browsers live in named Docker volumes, so
# the host's own frontend/node_modules is never touched.
#
#   run_frontend.sh setup                 # one-time: npm ci + Playwright Chromium into the volumes
#   run_frontend.sh test <vitest args>    # e.g. test contracts/packages/file-mesh/package.test.ts
#   run_frontend.sh e2e  <playwright args>
#   run_frontend.sh dev  [port]           # vite dev server on 127.0.0.1:<port> (default 5173), foreground
#   run_frontend.sh ui-check <ui_check.mjs args>   # Stage 2.3 browser check; SM_WORK is mounted at /work
#   run_frontend.sh exec <cmd...>         # any command in the same container setup
#
# Env: SM_REPO (default: git toplevel of the current dir), SM_NODE_IMAGE (default: the image `setup` builds,
# node:22 plus Chromium's system libraries, tagged sm-skill-node22-playwright:latest).
set -euo pipefail

REPO="${SM_REPO:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
BUILT="sm-skill-node22-playwright:latest"
IMAGE="${SM_NODE_IMAGE:-$BUILT}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${SM_WORK:-$PWD}"
NM_VOL="sm-skill-frontend-node-modules"
PW_VOL="sm-skill-playwright-browsers"
[[ -f "$REPO/frontend/package.json" ]] || { echo "error: $REPO/frontend/package.json not found (set SM_REPO)" >&2; exit 2; }

USER_ARGS=(-u "$(id -u):$(id -g)")   # files written to the repo or /work stay owned by the caller
run() {
  local extra=("$@")
  local cmd=(docker run --rm -i --init --network host "${USER_ARGS[@]}"
    -e HOME=/tmp -e CI=1 -e PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
    -v "$REPO":/repo -v "$NM_VOL":/repo/frontend/node_modules -v "$PW_VOL":/ms-playwright
    -v "$HERE":/skill:ro -v "$WORK":/work
    -w /repo/frontend "$IMAGE")
  echo "+ ${cmd[*]} ${extra[*]}" >&2
  "${cmd[@]}" "${extra[@]}"
}

sub="${1:-}"; shift || true
case "$sub" in
  setup)
    USER_ARGS=()   # apt and npm ci need root; the volumes are chowned back to the caller below
    pw=$(python3 -c "import json;print(json.load(open('$REPO/frontend/package-lock.json'))['packages']['node_modules/@playwright/test']['version'])")
    printf 'FROM node:22\nRUN npx -y playwright@%s install-deps chromium && rm -rf /var/lib/apt/lists/*\n' "$pw" \
      | docker build -q -t "$BUILT" - >/dev/null
    echo "built $BUILT (playwright $pw system deps)" >&2
    run bash -c 'npm ci --no-audit --no-fund && npx playwright install chromium && chown -R '"$(id -u):$(id -g)"' /repo/frontend/node_modules /ms-playwright'
    ;;
  test) run npx vitest run "$@" ;;
  e2e)  run npx playwright test "$@" ;;
  dev)  run npx vite --host 127.0.0.1 --port "${1:-5173}" --strictPort ;;
  # copied into the node_modules VOLUME so @playwright/test resolves; nothing is written to the host's frontend/
  ui-check) run bash -c 'mkdir -p node_modules/.sm-skill && cp /skill/ui_check.mjs node_modules/.sm-skill/ && exec node node_modules/.sm-skill/ui_check.mjs "$@"' _ "$@" ;;
  exec) run "$@" ;;
  *) sed -n '2,13p' "$0"; exit 2 ;;
esac
