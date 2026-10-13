#!/usr/bin/env bash
#
# Plan 015 T5.3 (G-A2): the host driver. The REAL Go agent on the E2E host, beside the host's existing Cache, enrolled to the LOCAL stack through
# a loopback reverse tunnel; plus (OR-145) dp-remote coexistence: a second, unlabelled Cache on the same host is declared on the host's SSH-Docker
# target and applied while the agent runs and again while it is stopped. It extends the G-A1 harness (ga1-agent.sh, ga1-scenario.py): the local
# stack, the PKI, the scenario and the assertions are those; this script supplies the REMOTE half (agent process, Caches, events, tunnel, clean-up).
#
# THE HOST IS REACHED ONLY THROUGH $GA2_HOST_CMD (default ~/.sm-local/e2e-host.sh): `run '<cmd>'`, `put <local> <remote>`, `tunnel <rport> <lport>`.
# `@ROOT@` (the target's config root) and `@PREFIX@` (its container prefix) are substituted by that command. This script never uses ssh, scp or
# `docker -H`, never reads a target file, and puts NO secret on any command line, local or remote: the broker password, the enrolment token and any
# generated password reach the host on STDIN only (`printf %s "$v" | $GA2_HOST_CMD run 'umask 077; cat > <file>'`).
#
# Commands:
#   all              up, scenario, then (trap) down. The one command for the real run. Exit: 0 all PASS; 3 a (known) FINDING; 1 a FAIL or a changed host.
#   up               preflight, snapshot of the host's existing containers, the local stack, the agent build (linux/arm64), the remote directory, the binary
#                    and CA, the tunnel, both Caches. A failed `up` cleans up after itself.
#   scenario         the G-A2 steps through ga1-scenario.py (GA1_STEPS to select)
#   down             idempotent clean-up: agent, this run's labelled containers, the run directory, the tunnel, the snapshot assertion, the local stack
#   status           what is running (redacted)
#   agent-start | agent-stop | agent-log-size | agent-log-since N     the REMOTE agent (the token, on a first start, from $SM_AGENT_ENROLL_TOKEN, to a 0600 file by stdin)
#   restart-sm       the local launcher again (delegates to ga1-agent.sh, without the broker values)
#   docker <args>    a remote docker CLI call; restart|stop|rm|kill|exec|start (and prune/system) are refused on a container lacking THIS run's label
#   events-start <container> | events-stop [<container>]   a remote `docker events` in the background, proven attached by a `docker top` canary
#   conf-holds <a|r> <value>      prints match / no-match only (does the remote application.yml hold max-size: <value>)
#   snapshot | snapshot-check     the existing containers (name, id[:12], StartedAt, Status, Health) + a health probe of the existing Cache; and the comparison
#   evidence <scenario-log>       a redacted markdown skeleton on stdout (ids cut to 8; no host, address, user, root path or broker value; existing-1..n)
#
# Environment (the integrator exports; the driver never sources a target file):
#   SM_E2E_BROKER_HOST (tcp://h:p)  SM_E2E_BROKER_VPN  SM_E2E_BROKER_USERNAME  SM_E2E_BROKER_PASSWORD   the broker the TEST Caches use; masked in every line
#   GA2_HOST_CMD (~/.sm-local/e2e-host.sh)   GA2_CACHE_IMAGE (solace-cache-engine:demo-20260914)   GA2_CACHE_MEMORY (640m)
#   GA2_HEALTH_A (18281)  GA2_HEALTH_R (18282)  GA2_EXISTING_HEALTH_PORT (8081)   GA2_TUNNEL_PORT (19443)  GA2_AGENT_ADMIN_PORT (19464)
#   GA2_GOOS (linux) GA2_GOARCH (arm64)   the agent build for the host
#   GA2_KNOWN_FINDINGS (e.g. m3)   GA1_STEPS   GA1_TARGET (default on here: the SSH-Docker target must be registered for Step 3)
#   GA2_EXISTING_HEALTH_OPTIONAL=1   the existing Cache's health need not be 200 at the baseline (the local dry run has none)
# Docker is shared: run under the lock with tc-env.sh sourced (recovered-scratchpad/docker-lock.sh <label> ...).
# Existing containers of the host are NEVER restarted, stopped, removed or exec'd; nothing is written outside @ROOT@/ga2-<run>/.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
self="$here/ga2-host.sh"
GA1SH="$here/ga1-agent.sh"
HOST_CMD="${GA2_HOST_CMD:-$HOME/.sm-local/e2e-host.sh}"
STUB=0; [[ "$(basename "$HOST_CMD")" == e2e-host-stub.sh ]] && STUB=1
GA2D="${GA2_STATE_DIR:-$here/.state-ga2-host}"        # this driver's own state (run id, resolved names, tunnel pid, snapshots); gitignored
[[ $STUB -eq 1 && -z "${GA2_STATE_DIR:-}" ]] && GA2D="$here/.state-ga2-stub"   # a dry run never shares the real run's state
LABEL_KEY=com.solace.sm.ga2.run
IMAGE="${GA2_CACHE_IMAGE:-solace-cache-engine:demo-20260914}"
MEMORY="${GA2_CACHE_MEMORY:-640m}"
HA="${GA2_HEALTH_A:-18281}"; HR="${GA2_HEALTH_R:-18282}"
EXISTING_PORT="${GA2_EXISTING_HEALTH_PORT:-8081}"
TUNNEL_PORT="${GA2_TUNNEL_PORT:-19443}"
ADMIN_PORT="${GA2_AGENT_ADMIN_PORT:-19464}"
BROKER_VARS=(SM_E2E_BROKER_HOST SM_E2E_BROKER_VPN SM_E2E_BROKER_USERNAME SM_E2E_BROKER_PASSWORD)

# The local stack: its own state directory, database container, volume and port, never sm-local.sh's defaults (port 55440).
export SM_LOCAL_STATE_DIR="${GA2_LOCAL_STATE_DIR:-$here/.state-ga2}"
export SM_LOCAL_PG_CONTAINER="${SM_LOCAL_PG_CONTAINER:-ga2-pg}" SM_LOCAL_PG_VOLUME="${SM_LOCAL_PG_VOLUME:-ga2-pgdata}" SM_LOCAL_PG_PORT="${SM_LOCAL_PG_PORT:-55462}"
export GA1_TARGET="${GA1_TARGET:-on}"
export GA1_GOOS="${GA2_GOOS:-linux}" GA1_GOARCH="${GA2_GOARCH:-arm64}"
export GA1_GATEWAY_PORT="$TUNNEL_PORT"

say() { printf '==> %s\n' "$(redact_str "$*")"; }
die() { printf 'error: %s\n' "$(redact_str "$*")" >&2; exit 1; }

# ---- redaction: every line this script prints passes through it -------------------------------------------------------------------
REDACT=()
load_redact() {
  REDACT=()
  local v val
  for v in "${BROKER_VARS[@]}"; do val="${!v:-}"; if [[ ${#val} -ge 3 ]]; then REDACT+=("$val"); fi; done
  if [[ ${#ROOT} -ge 3 ]]; then REDACT+=("$ROOT"); fi
  return 0
}
redact_str() {
  local s="$1" v
  for v in ${REDACT[@]+"${REDACT[@]}"}; do if [[ -n "$v" ]]; then s="${s//"$v"/<redacted>}"; fi; done
  printf '%s' "$s"
}
redact() { local l; while IFS= read -r l || [[ -n "$l" ]]; do redact_str "$l"; printf '\n'; done; }

sq() { local s="${1//\'/\'\\\'\'}"; printf "'%s'" "$s"; }   # POSIX single-quote a word for the remote shell

# ---- the run id and the remote paths ------------------------------------------------------------------------------------------------
RUN=""; ROOT=""; PREFIX=""
OWN_RUN=0   # set once THIS invocation has made a run: only then may a failure or an exit clean up (never an earlier run's resources)
valid_run() { [[ "${1:-}" =~ ^[a-z0-9]{8}$ ]]; }
load_state() { # RUN from $GA2_RUN_ID or the state file; ROOT and PREFIX as resolved by `up`
  RUN="${GA2_RUN_ID-}"
  if [[ -z "$RUN" && -f "$GA2D/run.id" ]]; then RUN="$(cat "$GA2D/run.id")"; fi
  if [[ -f "$GA2D/root" ]]; then ROOT="$(cat "$GA2D/root")"; fi
  if [[ -f "$GA2D/prefix" ]]; then PREFIX="$(cat "$GA2D/prefix")"; fi
  load_redact
}
need_run() { valid_run "$RUN" || die "the run id is empty or not ^[a-z0-9]{8}\$ ('$(printf %s "$RUN" | head -c 12)'): refusing to touch the host"; }
RD() { need_run; printf '@ROOT@/ga2-%s' "$RUN"; }   # the ONLY directory this script writes in on the host

# ---- the host command ---------------------------------------------------------------------------------------------------------------
host_ok() { [[ -x "$HOST_CMD" ]] || die "GA2_HOST_CMD is not an executable file"; }
# stderr goes through a temporary file and then `redact` (no process substitution: its pipe descriptor would leak into a process the command starts
# in the background, and hold the caller's stderr open for as long as that process lives).
rrun() { # a remote command; no stdin
  local ef rc=0; ef="$(mktemp)"
  "$HOST_CMD" run "$1" < /dev/null 2> "$ef" || rc=$?
  redact < "$ef" >&2; rm -f "$ef"; return $rc
}
rrun_in() { # a remote command whose stdin is the caller's (secrets go this way, never in $1)
  local ef rc=0; ef="$(mktemp)"
  "$HOST_CMD" run "$1" 2> "$ef" || rc=$?
  redact < "$ef" >&2; rm -f "$ef"; return $rc
}

# Every docker call on the host goes through here. Mutating verbs are refused on a container that does not carry THIS run's label; a created
# container must carry it; prune/system are never allowed.
rdocker() {
  need_run
  local a=("$@") sub="${1:-}" i name got
  [[ "$sub" == container && ${#a[@]} -ge 2 ]] && { a=("${a[@]:1}"); sub="${a[0]}"; }
  case "$sub" in
    system|prune|rmi) die "docker $sub is never run on the host by this driver" ;;
    run|create)
      printf '%s\0' "${a[@]}" | grep -qzxF -- "$LABEL_KEY=$RUN" || die "docker $sub without --label $LABEL_KEY=$RUN: everything created on the host carries this run's label" ;;
    restart|stop|rm|kill|exec|start|pause|unpause|rename|update|cp)
      local seen=0
      for ((i = 1; i < ${#a[@]}; i++)); do
        name="${a[i]}"
        [[ "$name" == -* ]] && continue
        got="$(rrun "docker inspect -f '{{index .Config.Labels \"$LABEL_KEY\"}}' $(sq "$name") 2>/dev/null" || true)"
        [[ "$got" == "$RUN" ]] || die "docker $sub $name refused: that container does not carry $LABEL_KEY=$RUN (an existing container of the host is never touched)"
        seen=$((seen + 1))
        [[ "$sub" == exec ]] && break   # only the first positional of exec is the container
      done
      [[ $seen -ge 1 ]] || die "docker $sub with no container argument that can be label-checked: refused" ;;
  esac
  local cmd="docker" w
  for w in "$@"; do cmd+=" $(sq "$w")"; done
  rrun "$cmd"
}

guard_local_docker() {
  command -v docker >/dev/null || die "docker is not on PATH"
  if [[ "$(docker context show 2>/dev/null)" == colima* || "${DOCKER_HOST:-}" == *colima* ]]; then
    die "the Docker context is Colima: source recovered-scratchpad/tc-env.sh (Docker Desktop) first; this driver never touches Colima"
  fi
  case "$SM_LOCAL_PG_CONTAINER:$SM_LOCAL_PG_PORT" in sm-local-pg:*|*:55440) die "the local database must not be sm-local.sh's default container/port" ;; esac
}

# ---- resolving the host's names ------------------------------------------------------------------------------------------------------
resolve_names() {
  # e2e-host.sh redacts the config root in everything it prints, so it cannot be read back: the caller exports it (GA2_ROOT_PATH, from the
  # target file, never printed) and the host confirms that @ROOT@ is that path. A stub host prints its root unredacted.
  if [[ -n "${GA2_ROOT_PATH:-}" ]]; then ROOT="$GA2_ROOT_PATH"; else ROOT="$(rrun 'printf %s "@ROOT@"')"; fi
  [[ "$ROOT" == /* && ${#ROOT} -ge 2 ]] || die "the config root is not an absolute path (export GA2_ROOT_PATH for a real host: its command redacts @ROOT@)"
  [[ "$(rrun "[ \"@ROOT@\" = $(sq "$ROOT") ] && echo same || echo differs")" == same ]] || die "GA2_ROOT_PATH is not the host's @ROOT@"
  PREFIX="$(rrun 'printf %s "@PREFIX@"')"
  [[ -n "$PREFIX" ]] || die "the host command did not resolve @PREFIX@ (Cache R must carry the SSH target's container prefix)"
  ( umask 077; printf '%s' "$ROOT" > "$GA2D/root"; printf '%s' "$PREFIX" > "$GA2D/prefix" )
  load_redact
}

# ---- snapshots of the host's existing containers ---------------------------------------------------------------------------------------
snap_fmt() { printf '{{.Name}}|{{printf "%%.12s" .Id}}|{{.State.StartedAt}}|{{.State.Status}}|{{if index .State "Health"}}{{.State.Health.Status}}{{else}}none{{end}}|{{index .Config.Labels "%s"}}' "$LABEL_KEY"; }
take_snapshot() { # outfile  (containers that do NOT carry this run's label; plus the existing Cache's health, status code only)
  local out="$1" lines code
  lines="$(rrun "ids=\$(docker ps -aq); [ -z \"\$ids\" ] || docker inspect -f '$(snap_fmt)' \$ids")" || die "the snapshot of the host's containers failed"
  # GA2_SNAPSHOT_IGNORE (an extended regex of names) exists for the LOCAL dry run only, whose "host" is the shared local daemon that also carries the dry
  # run's own broker and database; the real run never sets it.
  printf '%s\n' "$lines" | awk -F'|' -v run="$RUN" -v ign="${GA2_SNAPSHOT_IGNORE:-^$}" 'NF >= 6 && $6 != run { sub(/^\//, "", $1); if ($1 ~ ign && ign != "^$") next; print $1 "|" $2 "|" $3 "|" $4 "|" $5 }' | sort > "$out"
  code="$(rrun "curl -s -o /dev/null -m 5 -w '%{http_code}' http://127.0.0.1:$EXISTING_PORT/actuator/health || true")"
  printf '%s' "${code:-000}" > "$out.health"
}
do_snapshot() { need_run; mkdir -p "$GA2D"; rm -f "$GA2D"/snap.*; take_snapshot "$GA2D/snap.before"; printf '%s' "$RUN" > "$GA2D/snap.before.run"; chmod 600 "$GA2D/snap.before"*; say "snapshot: $(grep -c . "$GA2D/snap.before" || true) existing container(s) recorded; the existing Cache's health probe answered $(cat "$GA2D/snap.before.health")"; }
do_snapshot_check() {
  need_run
  [[ -f "$GA2D/snap.before" ]] || { say "snapshot-check: no baseline snapshot recorded; nothing to compare"; return 0; }
  [[ "$(cat "$GA2D/snap.before.run" 2>/dev/null)" == "$RUN" ]] || { say "snapshot-check: the recorded baseline belongs to another run; refusing to compare (and not failing on it)"; return 0; }
  take_snapshot "$GA2D/snap.after"
  local bad=0 n=0 line name rest after hb ha
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    n=$((n + 1)); name="${line%%|*}"; rest="${line#*|}"
    after="$(awk -F'|' -v n="$name" '$1 == n' "$GA2D/snap.after" | head -1)"
    if [[ -z "$after" ]]; then echo "CHANGED existing-$n: gone"; bad=1; continue; fi
    # id, StartedAt and Status must be byte-equal; Health may move (reported)
    if [[ "$(cut -d'|' -f2-4 <<<"$line")" != "$(cut -d'|' -f2-4 <<<"$after")" ]]; then echo "CHANGED existing-$n: id/StartedAt/Status differ"; bad=1; else echo "same    existing-$n: id, StartedAt, Status byte-equal"; fi
    [[ "$(cut -d'|' -f5 <<<"$line")" == "$(cut -d'|' -f5 <<<"$after")" ]] || echo "note    existing-$n: health went $(cut -d'|' -f5 <<<"$line") -> $(cut -d'|' -f5 <<<"$after")"
  done < "$GA2D/snap.before"
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    [[ -n "$(awk -F'|' -v n="${line%%|*}" '$1 == n' "$GA2D/snap.before")" ]] || echo "note    a container appeared that is not this run's and was not there before (not ours)"
  done < "$GA2D/snap.after"
  hb="$(cat "$GA2D/snap.before.health")"; ha="$(cat "$GA2D/snap.after.health")"
  if [[ "$ha" == 200 ]]; then echo "same    the existing Cache's health probe: 200"
  elif [[ "${GA2_EXISTING_HEALTH_OPTIONAL:-0}" == 1 && "$ha" == "$hb" ]]; then echo "same    the existing Cache's health probe: $ha (as at the baseline; optional)"
  else echo "CHANGED the existing Cache's health probe: $hb -> $ha"; bad=1; fi
  if [[ $bad -ne 0 ]]; then say "snapshot-check: THE HOST'S EXISTING CONTAINERS CHANGED"; return 1; fi
  say "snapshot-check: every existing container is as it was"
}

# ---- preflight (read-only) -----------------------------------------------------------------------------------------------------------
preflight() {
  local rd; rd="$(RD)"; local fails=() kb ports out p
  rrun 'docker info >/dev/null 2>&1' || fails+=("docker info does not work for the host user")
  rrun "docker image inspect $(sq "$IMAGE") >/dev/null 2>&1" || fails+=("the image $IMAGE is not present on the host (this driver never pulls)")
  rrun 'command -v curl >/dev/null 2>&1' || fails+=("curl is not on the host")
  if [[ $STUB -eq 0 ]]; then
    [[ "$(rrun 'uname -m')" == aarch64 ]] || fails+=("the host is not aarch64 (the agent is built for linux/arm64)")
    kb="$(rrun "awk '/MemAvailable/ {print \$2}' /proc/meminfo")"; [[ "${kb:-0}" -ge 1572864 ]] || fails+=("available memory is under 1.5 GB (${kb:-?} kB)")
    out="$(rrun "ss -ltn | awk 'NR>1 {print \$4}'")"
    for p in "$TUNNEL_PORT" "$ADMIN_PORT" "$HA" "$HR"; do if grep -Eq "[:.]$p\$" <<<"$out"; then fails+=("remote port $p is not free"); fi; done
  fi
  kb="$(rrun "df -Pk @ROOT@ | awk 'NR==2 {print \$4}'")"; [[ "${kb:-0}" -ge 2097152 ]] || fails+=("free disk under 2 GB (${kb:-?} kB)")
  rrun "test ! -e $rd" || fails+=("$(redact_str "$rd") already exists")
  say "existing containers' label KEYS (values are never read): $(rrun "ids=\$(docker ps -aq); [ -z \"\$ids\" ] || docker inspect -f '{{range \$k, \$v := .Config.Labels}}{{\$k}}{{\"\\n\"}}{{end}}' \$ids" | sort -u | tr '\n' ' ')"
  # nothing the envelope can reach may be an SM-labelled container's config
  out="$(rrun "ids=\$(docker ps -aq); [ -z \"\$ids\" ] || docker inspect -f '{{if index .Config.Labels \"com.solace.sm.instance\"}}{{range .Mounts}}{{.Source}}{{\"\\n\"}}{{end}}{{end}}' \$ids")"
  if grep -qF -- "$ROOT/ga2-$RUN/cache-a" <<<"$out"; then fails+=("an SM-labelled container's mount falls inside the agent envelope"); fi
  if ((${#fails[@]})); then printf 'preflight FAILED: %s\n' "${fails[@]}" | redact >&2; return 1; fi
  say "preflight ok"
}

# ---- the remote directory, the binary, the CA, the Cache configs -----------------------------------------------------------------------
yq() { local s="${1//\\/\\\\}"; s="${s//\"/\\\"}"; printf '"%s"' "$s"; }
cache_yml() { # a|r
  local ns="ga2-$RUN-$1"
  cat <<YML
cache-engine:
  namespace: $(yq "$ns")
  max-size: 250000
  cache-hit-topic: $(yq "ga2/$ns/hit")
  cache-miss-topic: $(yq "POST/ga2/$ns/miss")
  solace:
    enabled: true
    host: $(yq "$SM_E2E_BROKER_HOST")
    vpn: $(yq "$SM_E2E_BROKER_VPN")
    username: $(yq "$SM_E2E_BROKER_USERNAME")
    password: $(yq "$SM_E2E_BROKER_PASSWORD")
    management:
      enabled: false
YML
}

# The mode of @ROOT@/application.yml, which the container user can read: files take it, directories take it plus x where it has r.
MODE_SNIPPET='fm=$(stat -c %a @ROOT@/application.yml 2>/dev/null || stat -f %Lp @ROOT@/application.yml); dm=$(printf %o $(( 0$fm | ((0$fm & 0444) >> 2) )))'

make_remote_dirs() {
  local rd; rd="$(RD)"
  rrun "set -e; $MODE_SNIPPET; umask 077; mkdir $rd; mkdir $rd/bin $rd/agent-data; mkdir $rd/cache-a $rd/cache-r; chmod 700 $rd $rd/bin $rd/agent-data; chmod \$dm $rd/cache-a $rd/cache-r"
}
put_file() { # local remote mode
  local ef rc=0; ef="$(mktemp)"
  "$HOST_CMD" put "$1" "$2" 2> "$ef" || rc=$?
  redact < "$ef" >&2; rm -f "$ef"
  [[ $rc -eq 0 ]] || die "put to the host failed"
  rrun "chmod $3 $2"
}
write_cache_conf() { # a|r  — the password goes by stdin
  local rd; rd="$(RD)"
  cache_yml "$1" | rrun_in "set -e; $MODE_SNIPPET; umask 077; cat > $rd/cache-$1/application.yml; chmod \$fm $rd/cache-$1/application.yml"
}

CACHE_A() { printf 'ga2a-%s' "$RUN"; }
CACHE_R_PH() { printf '@PREFIX@ga2r-%s' "$RUN"; }      # as the host command takes it
CACHE_R_REAL() { printf '%sga2r-%s' "$PREFIX" "$RUN"; } # as the scenario names it (the prefix resolved)

start_cache() { # a|r
  local rd name health extra=() ; rd="$(RD)"
  if [[ "$1" == a ]]; then
    name="$(CACHE_A)"; health="$HA"
    extra=(--label com.solace.sm.instance=ga2-cache-a --label com.solace.sm.config-file=/app/external/spring/config/application.yml --label "com.solace.sm.health-port=$HA")
  else
    name="$(CACHE_R_PH)"; health="$HR"   # NO com.solace.sm.* labels: the agent's discovery must ignore it; the SSH target's prefix puts it in that envelope
  fi
  rdocker run -d --name "$name" --label "$LABEL_KEY=$RUN" ${extra[@]+"${extra[@]}"} --memory "$MEMORY" \
    -p "127.0.0.1:$health:8081" -v "$rd/cache-$1:/app/external/spring/config" \
    --health-cmd 'bash -c "exec 3<>/dev/tcp/127.0.0.1/8081"' --health-interval 3s --health-timeout 2s --health-retries 20 --health-start-period 20s \
    "$IMAGE" >/dev/null
}
wait_healthy() { # container
  local i st
  for i in $(seq 1 60); do
    st="$(rdocker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null || true)"
    [[ "$st" == healthy ]] && { say "$(printf %s "$1" | sed "s#^$PREFIX#<prefix>#") healthy"; return 0; }
    sleep 2
  done
  rdocker logs --tail 15 "$1" 2>&1 | redact >&2 || true
  die "a Cache container did not become healthy in 120 s"
}

# ---- the tunnel ------------------------------------------------------------------------------------------------------------------------
tunnel_alive() { [[ -f "$GA2D/tunnel.pid" ]] && kill -0 "$(cat "$GA2D/tunnel.pid")" 2>/dev/null; }
remote_port_open() { rrun "ss -ltn | grep -q 127.0.0.1:$TUNNEL_PORT"; }
start_tunnel() {
  tunnel_alive && { say "tunnel already running"; return 0; }
  # A new session, so the whole tree (a wrapper and its ssh) can be signalled by group; nohup + redirects so it outlives this shell.
  nohup python3 -c 'import os, sys; os.setsid(); os.execv(sys.argv[1], sys.argv[1:])' "$HOST_CMD" tunnel "$TUNNEL_PORT" "$TUNNEL_PORT" \
    > "$GA2D/tunnel.log" 2>&1 < /dev/null &
  echo $! > "$GA2D/tunnel.pid"
  disown "$!" 2>/dev/null || true   # no "Terminated" job notice when the clean-up stops it
  local i
  for i in $(seq 1 30); do
    if [[ $STUB -eq 1 ]]; then tunnel_alive && { say "tunnel up (stub: nothing to forward)"; return 0; }
    else remote_port_open && { say "tunnel up: the host listens on 127.0.0.1:$TUNNEL_PORT"; return 0; }; fi
    sleep 1
  done
  die "the tunnel did not come up in 30 s (see $(basename "$GA2D")/tunnel.log, redacted: $(redact < "$GA2D/tunnel.log" | tail -3 | tr '\n' ' '))"
}
stop_tunnel() {
  [[ -f "$GA2D/tunnel.pid" ]] || return 0
  local pid; pid="$(cat "$GA2D/tunnel.pid")"
  [[ "$pid" =~ ^[0-9]+$ ]] || { rm -f "$GA2D/tunnel.pid"; return 0; }
  if kill -0 "$pid" 2>/dev/null; then
    kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
    local i; for i in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    kill -0 "$pid" 2>/dev/null && { kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true; }
  fi
  rm -f "$GA2D/tunnel.pid"
  if [[ $STUB -eq 0 ]] && valid_run "$RUN"; then
    local i; for i in $(seq 1 20); do remote_port_open || { say "tunnel closed: the remote port $TUNNEL_PORT is no longer listening"; return 0; }; sleep 1; done
    echo "error: the remote tunnel port is STILL listening after the tunnel was stopped" >&2; return 1
  fi
  say "tunnel stopped"
}

# ---- the remote agent -----------------------------------------------------------------------------------------------------------------
remote_agent_alive() { # exit 0 when the recorded pid is a live sm-agent
  local rd; rd="$(RD)"
  rrun "p=\$(cat $rd/agent.pid 2>/dev/null) || exit 1; kill -0 \$p 2>/dev/null || exit 1; c=\$(ps -p \$p -o comm= 2>/dev/null); case \"\${c##*/}\" in sm-agent*) exit 0;; esac; exit 1"
}
do_agent_start() {
  need_run; local rd; rd="$(RD)"
  if remote_agent_alive; then say "agent already running"; return 0; fi
  if [[ -n "${SM_AGENT_ENROLL_TOKEN:-}" ]]; then   # the token: stdin to a 0600 file, never argv
    printf 'SM_AGENT_ENROLL_TOKEN=%s\n' "$SM_AGENT_ENROLL_TOKEN" | rrun_in "umask 077; cat > $rd/agent.env"
  fi
  # The agent reads the token from ITS OWN environment (read out of the 0600 file, not sourced, so no byte of it is ever evaluated); the file is
  # removed as soon as the environment holds it, so it is never left behind, whether or not the enrolment then succeeds.
  rrun "cd $rd || exit 1; if [ -f agent.env ]; then IFS= read -r l < agent.env; SM_AGENT_ENROLL_TOKEN=\${l#SM_AGENT_ENROLL_TOKEN=}; export SM_AGENT_ENROLL_TOKEN; rm -f agent.env; fi; \
nohup ./bin/sm-agent --data-dir $rd/agent-data --connect-endpoint 127.0.0.1:$TUNNEL_PORT --trust-bundle $rd/ca.pem --roles control,telemetry --admin-addr 127.0.0.1:$ADMIN_PORT --log-level debug \
>> agent.log 2>&1 < /dev/null & echo \$! > agent.pid"
  remote_agent_alive || sleep 2
  remote_agent_alive || die "the remote agent is not running after its start"
  say "remote agent started"
}
do_agent_stop() {
  need_run; local rd; rd="$(RD)"
  if remote_agent_alive; then
    rrun "p=\$(cat $rd/agent.pid); kill \$p 2>/dev/null; for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do kill -0 \$p 2>/dev/null || break; sleep 0.5; done; kill -0 \$p 2>/dev/null && kill -9 \$p 2>/dev/null; rm -f $rd/agent.pid; exit 0"
    say "remote agent stopped"
  else
    rrun "rm -f $rd/agent.pid; exit 0"; say "remote agent not running"
  fi
}
agent_log_size() { local rd; rd="$(RD)"; rrun "if [ -f $rd/agent.log ]; then wc -c < $rd/agent.log | tr -d ' '; else echo 0; fi"; }
agent_log_since() { local rd; rd="$(RD)"; [[ "${1:-}" =~ ^[0-9]+$ ]] || die "offset must be a number"; rrun "if [ -f $rd/agent.log ]; then tail -c +$(($1 + 1)) $rd/agent.log; fi"; }

# ---- remote docker events, conf check -----------------------------------------------------------------------------------------------------
safe_name() { [[ "${1:-}" =~ ^[A-Za-z0-9_.@-]+$ ]] || die "an odd container name"; printf '%s' "$1" | tr '@' '_'; }
do_events_start() {
  need_run; local name="${1:?container}" rd f p i; rd="$(RD)"; f="$rd/events-$(safe_name "$name").log"; p="$rd/events-$(safe_name "$name").pid"
  rrun "rm -f $f $p; nohup docker events --filter container=$(sq "$name") --format '{{.Action}}' > $f 2>&1 < /dev/null & echo \$! > $p"
  for i in $(seq 1 40); do   # attached = a `docker top` canary of the container shows up in the file (not a sleep)
    rdocker top "$name" >/dev/null 2>&1 || true
    if rrun "grep -qx top $f"; then say "events collector attached (canary seen)"; return 0; fi
    sleep 0.5
  done
  rrun "kill \$(cat $p) 2>/dev/null; exit 0"
  die "the remote docker events collector did not attach (no canary in 20 s)"
}
do_events_stop() {
  need_run; local name="${1:-}" rd f p; rd="$(RD)"
  [[ -n "$name" ]] || die "events-stop needs the container name"
  f="$rd/events-$(safe_name "$name").log"; p="$rd/events-$(safe_name "$name").pid"
  if ! rrun "pid=\$(cat $p 2>/dev/null) && kill -0 \$pid 2>/dev/null"; then echo "docker events died during the window" >&2; return 1; fi
  rrun "kill \$(cat $p) 2>/dev/null; sleep 0.5; cat $f; rm -f $p; exit 0"
}
do_conf_holds() { # a|r value
  need_run; local w="${1:-}" v="${2:-}" rd; rd="$(RD)"
  [[ "$w" == a || "$w" == r ]] || die "conf-holds takes a or r"; [[ "$v" =~ ^[0-9]+$ ]] || die "conf-holds takes a number"
  rrun "if grep -Eq '^[[:space:]]*max-size:[[:space:]]*$v[[:space:]]*\$' $rd/cache-$w/application.yml; then echo match; else echo no-match; fi"
}

# ---- up / scenario / down ----------------------------------------------------------------------------------------------------------------------
need_broker_env() {
  local v miss=()
  for v in "${BROKER_VARS[@]}"; do [[ -n "${!v:-}" ]] || miss+=("$v"); done
  ((${#miss[@]} == 0)) || die "set ${miss[*]} in the environment (the driver never sources a target file)"
  [[ "$SM_E2E_BROKER_HOST" == tcp://* ]] || die "SM_E2E_BROKER_HOST must be tcp://host:port"
}
ga1() { env -u SM_E2E_BROKER_PASSWORD -u SM_E2E_BROKER_HOST -u SM_E2E_BROKER_VPN -u SM_E2E_BROKER_USERNAME "$GA1SH" "$@"; }   # the launcher never sees the broker values

do_up() {
  guard_local_docker; host_ok; need_broker_env
  mkdir -p "$GA2D"; chmod 700 "$GA2D"
  if [[ -f "$GA2D/run.id" ]]; then die "a run is already recorded here (run $(cat "$GA2D/run.id")): run 'down' first"; fi
  local id; id="$({ LC_ALL=C tr -dc 'a-z0-9' </dev/urandom | head -c 8; } || true)"
  valid_run "$id" || die "could not generate a run id"
  ( umask 077; printf '%s' "$id" > "$GA2D/run.id" )
  OWN_RUN=1
  load_state
  load_redact
  say "G-A2 run $RUN$([[ $STUB -eq 1 ]] && echo ' (STUB host: a local dry run)')"
  resolve_names
  preflight
  do_snapshot
  say "the local stack (target: $GA1_TARGET), the PKI and the agent build for $GA1_GOOS/$GA1_GOARCH"
  ga1 up
  local rd bin; rd="$(RD)"
  bin="$SM_LOCAL_STATE_DIR/ga1/bin/$GA1_GOOS-$GA1_GOARCH/sm-agent"
  [[ -x "$bin" ]] || die "the agent binary for $GA1_GOOS/$GA1_GOARCH was not built"
  make_remote_dirs
  put_file "$bin" "$rd/bin/sm-agent" 755
  put_file "$SM_LOCAL_STATE_DIR/ga1/pki/ca.pem" "$rd/ca.pem" 644
  say "remote directory made; the agent binary and the trust bundle are on the host"
  start_tunnel
  write_cache_conf a; write_cache_conf r
  start_cache a; start_cache r
  wait_healthy "$(CACHE_A)"; wait_healthy "$(CACHE_R_PH)"
  say "up: complete"
}

do_scenario() {
  guard_local_docker; need_run; need_broker_env
  [[ -n "$ROOT" && -n "$PREFIX" ]] || die "no resolved host names: run 'up' first"
  local known="${GA2_KNOWN_FINDINGS:-${GA1_KNOWN_FINDINGS:-}}" dpr=""
  [[ "$GA1_TARGET" == on ]] && dpr=on
  (
    export GA1_SCRIPT="$self" GA1_CACHE="$(CACHE_A)" GA1_CACHE_R="$(CACHE_R_REAL)" GA1_R_CONFIG_DIR="$ROOT/ga2-$RUN/cache-r" GA1_R_HEALTH_PORT="$HR"
    export GA1_PROBE_CMD="$(printf '%q' "$self")" GA1_EVENTS_CMD="$(printf '%q' "$self")" GA1_CONF_CHECK_CMD="$(printf '%q conf-holds' "$self")"
    export GA1_BROKER_HOST="$SM_E2E_BROKER_HOST" GA1_BROKER_VPN="$SM_E2E_BROKER_VPN" GA1_BROKER_USERNAME="$SM_E2E_BROKER_USERNAME"
    export GA1_BROKER_PASSWORD_ENV=SM_E2E_BROKER_PASSWORD GA1_NAMESPACE="ga2-$RUN-a" GA1_TOPIC_PREFIX=ga2
    export GA1_INSTANCE_LABEL=ga2-cache-a GA1_CONTAINER_PREFIX=ga2a- GA1_ALLOWED_CONFIG_ROOT="$ROOT/ga2-$RUN/cache-a"
    export GA1_KNOWN_FINDINGS="$known" GA1_DPREMOTE="$dpr" GA1_TABLE_TITLE="G-A2 results" GA1_HEALTH_WAIT=90 GA2_ROOT="$ROOT"
    export GA1_MASK_ENV="SM_E2E_BROKER_HOST SM_E2E_BROKER_VPN SM_E2E_BROKER_USERNAME SM_E2E_BROKER_PASSWORD GA2_ROOT"
    if [[ -z "${GA1_STEPS:-}" ]]; then unset GA1_STEPS; fi
    exec "$GA1SH" scenario
  ) 2>&1 | redact
}

CLEANING=0
do_down() {
  need_run   # refuses an empty or odd run id before anything is touched
  guard_local_docker; host_ok
  local rc=0 ids id rd; rd="$(RD)"
  [[ $CLEANING -eq 0 ]] || return 0; CLEANING=1
  say "down: run $RUN"
  do_agent_stop || true
  rrun "for p in $rd/events-*.pid; do [ -f \"\$p\" ] && kill \$(cat \"\$p\") 2>/dev/null; done; exit 0" || true
  ids="$(rdocker ps -aq --filter "label=$LABEL_KEY=$RUN" || true)"
  for id in $ids; do rdocker rm -f "$id" >/dev/null || rc=1; done
  say "removed the containers labelled for this run: $(grep -c . <<<"$ids" || true)"
  rrun "d=$rd; case \"\$d\" in */ga2-$RUN) rm -rf -- \"\$d\" ;; esac; exit 0" || rc=1
  say "removed the run directory"
  stop_tunnel || rc=1
  do_snapshot_check || rc=1
  ga1 down || rc=1
  # what `evidence` needs to redact the log survives the run (state only, mode 600, gitignored); the run id does not
  { printf 'root|%s\n' "$ROOT"; printf 'prefix|%s\n' "$PREFIX"; local n=0 l; if [[ -f "$GA2D/snap.before" ]]; then while IFS= read -r l; do [[ -n "$l" ]] && { n=$((n + 1)); printf 'existing-%s|%s\n' "$n" "${l%%|*}"; }; done < "$GA2D/snap.before"; fi; } > "$GA2D/redact.map" 2>/dev/null || true
  chmod 600 "$GA2D/redact.map" 2>/dev/null || true
  rm -f "$GA2D/run.id" "$GA2D/root" "$GA2D/prefix" "$GA2D/tunnel.pid"
  CLEANING=0
  return $rc
}

do_status() {
  load_state; load_redact
  if ! valid_run "$RUN"; then say "no run recorded"; return 0; fi
  say "run $RUN; tunnel: $(tunnel_alive && echo up || echo down)"
  host_ok
  rdocker ps -a --filter "label=$LABEL_KEY=$RUN" --format '{{.Names}}  {{.Status}}' | redact || true
  if remote_agent_alive; then echo "agent: running"; else echo "agent: stopped"; fi
  ga1 status || true
}

# ---- evidence ----------------------------------------------------------------------------------------------------------------------------------
do_evidence() {
  local log="${1:?scenario log}"; [[ -f "$log" ]] || die "no such log"
  GA2_EVIDENCE_MAP="$GA2D/redact.map" python3 - "$log" <<'PY'
import os, re, sys
text = open(sys.argv[1], errors="replace").read()
pairs = []
mp = os.environ.get("GA2_EVIDENCE_MAP", "")
if os.path.exists(mp):
    for line in open(mp):
        k, _, v = line.rstrip("\n").partition("|")
        if v:
            pairs.append((k, v))
for name in ("SM_E2E_BROKER_HOST", "SM_E2E_BROKER_VPN", "SM_E2E_BROKER_USERNAME", "SM_E2E_BROKER_PASSWORD"):
    v = os.environ.get(name, "")
    if len(v) >= 3:
        pairs.append(("<broker>", v))
# longest first: a value that contains another is replaced whole
for k, v in sorted(pairs, key=lambda p: -len(p[1])):
    rep = {"root": "<root>", "prefix": "<prefix>"}.get(k, k)
    text = text.replace(v, rep)
text = re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", lambda m: m.group(0)[:8], text)
text = re.sub(r"\b\d{1,3}(?:\.\d{1,3}){3}\b", "<ip>", text)       # an address (loopback too: nothing numeric of the host survives)
text = re.sub(r"[a-z]{3,5}://[^\s\"']+", "<url>", text)
text = re.sub(r"/(?:Users|home|root|var|private)/[^\s\"']+", "<path>", text)
table = []
grab = False
for l in text.splitlines():
    if l.startswith("==== ") and l.endswith(" ===="):
        grab = True
    if grab:
        table.append(l)
print("# G-A2 evidence (redacted skeleton)\n")
print("Generated by `ga2-host.sh evidence` from the scenario log. No host, address, user, root path or broker value; ids are cut to 8 characters; the host's")
print("existing containers are `existing-1..n`. The integrator completes the sections marked TODO after reading the log.\n")
print("## Result table\n\n```")
print("\n".join(table) if table else "(no result table in the log)")
print("```\n")
print("## Host unchanged\n\nTODO: paste the `snapshot-check` lines (existing-N byte-equal; health probe).\n")
print("## Findings\n\nTODO: each FINDING line of the log, with its disposition.\n")
print("## Timings\n\nTODO: the remote-apply elapsed seconds (agent up / agent down) and the M3 lines.\n")
print("## Unedited redacted log\n\n```")
print(text.rstrip())
print("```")
PY
}

# ---- dispatch -------------------------------------------------------------------------------------------------------------------------------------
cmd="${1:-}"; shift || true
case "$cmd" in
  up)
    trap 'rc=$?; if [[ $rc -ne 0 && $OWN_RUN -eq 1 ]]; then trap - EXIT; echo "up failed: cleaning up" >&2; load_state; if valid_run "$RUN"; then do_down || true; fi; fi' EXIT
    do_up ;;
  all)
    on_exit() { local rc=$?; trap - EXIT INT TERM; load_state; local scn=$rc; if [[ $OWN_RUN -eq 1 ]] && valid_run "$RUN"; then do_down || { [[ $scn -eq 0 || $scn -eq 3 ]] && scn=1; }; fi; exit "$scn"; }
    trap on_exit EXIT; trap 'exit 130' INT; trap 'exit 143' TERM
    do_up
    scn=0; do_scenario || scn=$?
    exit "$scn" ;;
  scenario)         load_state; do_scenario ;;
  down)             load_state; do_down ;;
  status)           do_status ;;
  agent-start)      load_state; do_agent_start ;;
  agent-stop)       load_state; do_agent_stop ;;
  agent-log-size)   load_state; agent_log_size ;;
  agent-log-since)  load_state; agent_log_since "${1:-}" ;;
  restart-sm)       ga1 restart-sm ;;
  docker)           load_state; rdocker "$@" ;;
  events-start)     load_state; do_events_start "${1:-}" ;;
  events-stop)      load_state; do_events_stop "${1:-}" ;;
  conf-holds)       load_state; do_conf_holds "${1:-}" "${2:-}" ;;
  snapshot)         load_state; do_snapshot ;;
  snapshot-check)   load_state; do_snapshot_check ;;
  evidence)         do_evidence "${1:-}" ;;
  *) sed -n '2,36p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
