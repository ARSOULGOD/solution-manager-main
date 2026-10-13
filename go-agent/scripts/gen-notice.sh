#!/usr/bin/env bash
# Generates go-agent/NOTICE: the verbatim NOTICE files of every module compiled into sm-agent (Apache-2.0 s4(d)).
# The module set is the one the binary compiles in (go list -deps ./cmd/sm-agent, linux/amd64 + linux/arm64), not a
# hand-kept list, so a new dependency with a NOTICE cannot be missed (plan 015 W2, T2.4 review 3/9).
#
#   scripts/gen-notice.sh           # rewrite go-agent/NOTICE
#   scripts/gen-notice.sh --check   # fail if NOTICE is stale, or lacks a NOTICE of any compiled-in module
set -euo pipefail
cd "$(dirname "$0")/.."
out=NOTICE
tmp="$(mktemp)"; mods="$(mktemp)"
trap 'rm -f "$tmp" "$mods"' EXIT

# "path version dir" per non-main module, for both image platforms, de-duplicated.
tmpl='{{with .Module}}{{if not .Main}}{{.Path}} {{.Version}} {{.Dir}}{{end}}{{end}}'
for arch in amd64 arm64; do
  CGO_ENABLED=0 GOOS=linux GOARCH=$arch GOWORK=off GOFLAGS=-mod=readonly go list -deps -f "$tmpl" ./cmd/sm-agent
done | sed '/^$/d' | sort -u > "$mods"
[ -s "$mods" ] || { echo "gen-notice: no modules found" >&2; exit 2; }

{
  echo "Solution Manager agent (sm-agent) -- third-party notices"
  echo
  echo "This product includes software developed by third parties. The attribution notices that the Apache License 2.0, section 4(d),"
  echo "requires to travel with the work are reproduced below, verbatim from each compiled-in module's NOTICE file at the version"
  echo "compiled in. The licence of every compiled-in module is in THIRD-PARTY-LICENCES.md."
  echo
  echo "Corresponding source: the licences of some included components require the corresponding source to be offered to whoever"
  echo "receives the image. The text of that offer, and the contact it names, are owed pending the project owner's approval; no image is"
  echo "to be distributed before the offer is added to this file. This file does not itself make the offer."
  while read -r m v dir; do
    [ -d "$dir" ] || { echo "gen-notice: $m $v is not in the module cache ($dir)" >&2; exit 2; }
    for f in "$dir"/NOTICE*; do
      [ -f "$f" ] || continue
      echo; echo "================================================================================"
      echo "$m $v ($(basename "$f"))"
      echo "================================================================================"
      cat "$f"
    done
  done < "$mods"
} > "$tmp"

if [ "${1:-}" = "--check" ]; then
  rc=0
  [ -f "$out" ] && diff -q "$tmp" "$out" >/dev/null || { echo "gen-notice: $out is stale (run scripts/gen-notice.sh)" >&2; rc=1; }
  # Independent of the diff: every compiled-in module that has a NOTICE must have its header in the file.
  while read -r m v dir; do
    for f in "$dir"/NOTICE*; do
      [ -f "$f" ] || continue
      grep -qxF "$m $v ($(basename "$f"))" "$out" 2>/dev/null || { echo "gen-notice: $m $v has $(basename "$f") but $out lacks it" >&2; rc=1; }
    done
  done < "$mods"
  [ $rc -eq 0 ] && echo "gen-notice: ok ($(wc -l < "$mods" | tr -d ' ') modules scanned)"
  exit $rc
else
  cp "$tmp" "$out"; chmod 0644 "$out"   # mktemp files are 0600; the image copies this file and the nonroot user must read it
fi
