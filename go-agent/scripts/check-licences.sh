#!/usr/bin/env bash
# Fails if the shipped sm-agent binary compiles in a module (or version) not listed in THIRD-PARTY-LICENCES.md.
# Scope is the binary's set (go list -deps ./cmd/sm-agent), not `go list -m all` (plan 015 P15-12).
# Run from anywhere; checks the Dockerfile's targets (CGO_ENABLED=0 linux/amd64, linux/arm64) and the host.
set -euo pipefail
cd "$(dirname "$0")/.."
listed=$(sed -n 's/^| \([^ |][^ |]*\) | \([^ |][^ |]*\) | .*/\1 \2/p' THIRD-PARTY-LICENCES.md | grep -v '^module version$' | sort -u)
[ -n "$listed" ] || { echo "check-licences: no rows parsed from THIRD-PARTY-LICENCES.md" >&2; exit 2; }
rc=0
# Template: one line per non-main module, "path version", plus " => path version" when the build is redirected by a
# replace directive. A versionless module (a local-path replace, or a workspace/vendored one) prints an empty version.
tmpl='{{with .Module}}{{if not .Main}}{{.Path}} {{.Version}}{{with .Replace}} => {{.Path}} {{.Version}}{{end}}{{end}}{{end}}'
for target in linux/amd64 linux/arm64 host; do
  if [ "$target" = host ]; then env=(CGO_ENABLED=0); else env=(CGO_ENABLED=0 GOOS="${target%/*}" GOARCH="${target#*/}"); fi
  # GOWORK=off and -mod=readonly: an inherited go.work or GOFLAGS must not change the module set that is measured.
  used=$(env "${env[@]}" GOWORK=off GOFLAGS=-mod=readonly go list -deps -f "$tmpl" ./cmd/sm-agent | sed '/^$/d' | sort -u)
  # Fail on any redirected module (=>) or any empty version unless that exact line is a table row.
  bad=$(printf '%s\n' "$used" | grep -e ' => ' -e ' $' || true)
  if [ -n "$bad" ]; then
    echo "check-licences: $target has replaced or versionless modules (their licence cannot be pinned by version):" >&2
    printf '%s\n' "$bad" | sed 's/^/  /' >&2
    rc=1
  fi
  missing=$(comm -13 <(printf '%s\n' "$listed") <(printf '%s\n' "$used"))
  if [ -n "$missing" ]; then
    echo "check-licences: $target compiles modules not in THIRD-PARTY-LICENCES.md:" >&2
    printf '%s\n' "$missing" | sed 's/^/  /' >&2
    rc=1
  fi
done
# Every compiled-in module must have its licence text reproduced (verbatim, current), and its NOTICE (if any) in NOTICE.
scripts/gen-licence-texts.sh --check || rc=1
scripts/gen-notice.sh --check || rc=1
[ $rc -eq 0 ] && echo "check-licences: ok ($(printf '%s\n' "$listed" | wc -l | tr -d ' ') modules listed; linux/amd64, linux/arm64, host covered)"
exit $rc
