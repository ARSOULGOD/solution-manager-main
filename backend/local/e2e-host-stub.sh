#!/usr/bin/env bash
#
# STUB of ~/.sm-local/e2e-host.sh for the G-A2 driver's local dry run (plan 015 T5.3). SAME interface, no host:
#   run '<cmd>'                  the command runs LOCALLY under bash -c, stdin inherited
#   put <local> <remote>         cp
#   tunnel <remote-port> <local-port>   sleeps (the ports coincide locally, so there is nothing to forward)
# `@ROOT@` is replaced by $E2E_STUB_ROOT (a directory the dry run owns; created with a stand-in application.yml) and `@PREFIX@` by ga2stub-.
# $E2E_STUB_LOG, when set, receives every command string it was given (the argv a real host would see: the dry run greps it for secrets).
# It prints STUB on stderr every time, so a log can never be mistaken for a real-host log. It contacts nothing.
set -euo pipefail
echo "STUB (local dry-run host: nothing is contacted)" >&2
: "${E2E_STUB_ROOT:?E2E_STUB_ROOT must name the stub root directory}"
case "$E2E_STUB_ROOT" in /*) ;; *) echo "E2E_STUB_ROOT must be absolute" >&2; exit 2 ;; esac
mkdir -p "$E2E_STUB_ROOT"
if [[ ! -f "$E2E_STUB_ROOT/application.yml" ]]; then ( umask 022; printf '# stub stand-in for the existing Cache config\n' > "$E2E_STUB_ROOT/application.yml" ); fi
sub() { local s="$1"; s="${s//@ROOT@/$E2E_STUB_ROOT}"; s="${s//@PREFIX@/ga2stub-}"; printf '%s' "$s"; }
log() { if [[ -n "${E2E_STUB_LOG:-}" ]]; then printf '%s %s\n' "$1" "$2" >> "$E2E_STUB_LOG"; fi; }
case "${1:-}" in
  run)    shift; [[ $# -eq 1 ]] || { echo "usage: run '<cmd>'" >&2; exit 2; }
          cmd="$(sub "$1")"; log RUN "$cmd"; exec bash -c "$cmd" ;;
  put)    [[ $# -eq 3 ]] || { echo "usage: put <local> <remote>" >&2; exit 2; }
          dest="$(sub "$3")"; log PUT "$2 -> $dest"; cp "$2" "$dest" ;;
  tunnel) [[ $# -eq 3 ]] || { echo "usage: tunnel <remote-port> <local-port>" >&2; exit 2; }
          log TUNNEL "$2 $3"; exec sleep 86400 ;;
  *) echo "usage: run | put | tunnel" >&2; exit 2 ;;
esac
