#!/usr/bin/env bash
#
# Plan 015 T5.1 (G-A1): the LOCAL live gate for the agent gateway. The REAL Go agent (go-agent/, built here) against a local Docker
# Cache, the product driven only through its API, on the production launcher. Companion of sm-local.sh: it adds the agent gateway
# to that stack (sm-local.sh keeps its defaults: gateway off, its own state directory and database names), and runs the scenario
# in ga1-scenario.py.
#
# Commands:
#   up           build the agent, make the PKI, bring the sm-local stack up WITH the gateway listener on
#   cache        start a local broker and the Cache container (labelled, inside the envelope) on a private Docker network
#   scenario     run the G-A1 steps through the API (ga1-scenario.py); prints a redacted log; exits non-zero on any failed assertion
#   all          up, cache, scenario                     (the stack is left running; use `down`)
#   restart-sm   start the launcher again with the gateway environment (used by the scenario after it kills SM)
#   agent-start | agent-stop     the agent process (its token comes from the environment, never from argv)
#   agent-log-size | agent-log-since N     the agent's log (byte count; the bytes after offset N)
#   down         stop the agent, remove ONLY the resources carrying this run's label, bring the sm-local stack down
#   status       what is running
#
# Environment (all optional):
#   GA1_TARGET=on         leave SM_LOCAL_TARGET_ENV to sm-local's default (the E2E target ON when its file exists). Default: OFF -
#                         SM_LOCAL_TARGET_ENV=/nonexistent/..., no target registered, the E2E host is never scanned or contacted.
#   GA1_GATEWAY_PORT (19443)  GA1_READINESS_PORT (19444)  GA1_AGENT_ADMIN (127.0.0.1:19464)  GA1_CACHE_IMAGE (solace-cache-engine:local)
#   GA1_AGENT_DIAL (127.0.0.1:<gateway port>)   the endpoint the agent dials (the host's loopback; the certificate carries IP:127.0.0.1)
#   GA1_GOOS GA1_GOARCH   the agent build target (default: this host's); a non-host binary is built but not started here
#   GA1_PROBE_DOCKER_ARGS   extra docker CLI arguments (e.g. "--context x" or "-H ssh://h") for the scenario's CONTAINER probes only;
#                         database calls always use the local daemon
#   SM_LOCAL_STATE_DIR (backend/local/.state-ga1)   this gate's own state directory, apart from sm-local.sh's default
#   SM_LOCAL_PG_CONTAINER (ga1-pg)  SM_LOCAL_PG_VOLUME (ga1-pgdata)  SM_LOCAL_PG_PORT (55461)   the stack's own database
#   GA1_BROKER_IMAGE (solace-pubsub-enterprise:10.25.0.177)
#
# Every Docker resource this creates carries the label com.solace.sm.ga1.run=<run id> (the id is kept in the state directory), `down`
# removes by that label only, and an existing container, network or volume that lacks it is never adopted.
# Docker is shared: run this under the lock (recovered-scratchpad/docker-lock.sh <label> ...) with tc-env.sh sourced.

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
export SM_LOCAL_STATE_DIR="${SM_LOCAL_STATE_DIR:-$here/.state-ga1}"
STATE="$SM_LOCAL_STATE_DIR"
GA1="$STATE/ga1"
PKI="$GA1/pki"
CONF="$GA1/cache-conf"
AGENT_DATA="$GA1/agent-data"
NET=ga1-net
BROKER=ga1-broker
CACHE=ga1-cache-1
LABEL_KEY=com.solace.sm.ga1.run
GATEWAY_PORT="${GA1_GATEWAY_PORT:-19443}"
READINESS_PORT="${GA1_READINESS_PORT:-19444}"
AGENT_ADMIN="${GA1_AGENT_ADMIN:-127.0.0.1:19464}"
AGENT_DIAL="${GA1_AGENT_DIAL:-127.0.0.1:$GATEWAY_PORT}"
CACHE_IMAGE="${GA1_CACHE_IMAGE:-solace-cache-engine:local}"
BROKER_IMAGE="${GA1_BROKER_IMAGE:-solace-pubsub-enterprise:10.25.0.177}"
SM="$here/sm-local.sh"
# This gate's own PostgreSQL container, volume and port, so it never touches (or removes) a stack another session already runs under
# sm-local.sh's default names.
export SM_LOCAL_PG_CONTAINER="${SM_LOCAL_PG_CONTAINER:-ga1-pg}" SM_LOCAL_PG_VOLUME="${SM_LOCAL_PG_VOLUME:-ga1-pgdata}" SM_LOCAL_PG_PORT="${SM_LOCAL_PG_PORT:-55461}"
# The target is OFF unless explicitly opted in: a local gate must not scan or contact the E2E host by default.
if [[ "${GA1_TARGET:-off}" != on ]]; then export SM_LOCAL_TARGET_ENV=/nonexistent/ga1-target-off.env; fi

say() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }
rand() { { LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "${1:-24}"; } || true; }

# ---- the run id and the labels ------------------------------------------------------------------------------------------------
RUN=""
ensure_run() {
  mkdir -p "$GA1"; chmod 700 "$STATE" "$GA1"
  if [[ -f "$GA1/run.id" ]]; then RUN="$(cat "$GA1/run.id")"; else RUN="$(date +%Y%m%d%H%M%S)-$(rand 6)"; ( umask 077; printf '%s' "$RUN" > "$GA1/run.id" ); fi
  export SM_LOCAL_PG_LABEL="$LABEL_KEY=$RUN"
}
# An existing resource is ours only with this run's label; never adopt an unlabelled one.
owned_or_die() { # kind(container|network) name
  local got
  if [[ "$1" == container ]]; then got="$(docker inspect -f "{{index .Config.Labels \"$LABEL_KEY\"}}" "$2")"; else got="$(docker network inspect -f "{{index .Labels \"$LABEL_KEY\"}}" "$2")"; fi
  [[ "$got" == "$RUN" ]] || die "$1 $2 exists and does not carry $LABEL_KEY=$RUN: it is not this run's; refusing to adopt or remove it"
}

# ---- PKI: operator CA keystore (CA key + the lease-nonce HMAC entry), the gateway's server certificate, the trust bundle -------------
# Measures the keystore the way the launcher reads it (Pkcs12LeaseNonceSigner): both entries listed, the HMAC at least 32 bytes via the JDK API.
pki_check() { # dir
  local d="$1" len tmpj entries
  [[ -f "$d/ca.p12" && -f "$d/ks.pw" && -f "$d/ca.pem" && -f "$d/gateway.crt" && -f "$d/gateway.key" ]] || die "the PKI in $d is incomplete: delete it and run 'pki' again"
  entries="$(keytool -list -storetype PKCS12 -keystore "$d/ca.p12" -storepass:file "$d/ks.pw" 2>/dev/null | { grep -E "^(agent-ca|lease-nonce-hmac)," || true; } | sed 's/,.*,/, /')"
  [[ "$(grep -c . <<<"$entries" || true)" -eq 2 ]] || die "the keystore does not hold both entries (agent-ca, lease-nonce-hmac): delete the PKI and run 'pki' again"
  sed 's/^/    keystore entry: /' <<<"$entries"
  tmpj="$(mktemp -d)"
  cat > "$tmpj/KeyLen.java" <<'JAVA'
import java.io.InputStream; import java.nio.file.*; import java.security.*;
public class KeyLen { public static void main(String[] a) throws Exception {
  KeyStore s = KeyStore.getInstance("PKCS12"); char[] pw = Files.readString(Path.of(a[1])).trim().toCharArray();
  try (InputStream in = Files.newInputStream(Path.of(a[0]))) { s.load(in, pw); }
  Key k = s.getKey("lease-nonce-hmac", pw); System.out.println(k == null || k.getEncoded() == null ? -1 : k.getEncoded().length); } }
JAVA
  len="$(java "$tmpj/KeyLen.java" "$d/ca.p12" "$d/ks.pw" 2>/dev/null || true)"
  rm -rf "$tmpj"
  say "lease-nonce HMAC entry read by the JDK KeyStore API: ${len:-?} bytes (the launcher refuses fewer than 32)"
  [[ "${len:-0}" -ge 32 ]] || die "the lease-nonce key entry is shorter than 32 bytes (${len:-unreadable})"
  openssl x509 -checkend 86400 -noout -in "$d/ca.pem" >/dev/null && openssl x509 -checkend 86400 -noout -in "$d/gateway.crt" >/dev/null \
    || die "a certificate in $d expires within a day: delete the PKI and run 'pki' again"
}

do_pki() {
  ensure_run
  mkdir -p "$PKI"; chmod 700 "$PKI"
  if [[ -f "$PKI/ca.p12" ]]; then say "PKI already made"; pki_check "$PKI"; return 0; fi
  command -v openssl >/dev/null && command -v keytool >/dev/null && command -v java >/dev/null || die "openssl, keytool and java are required"
  local B="$GA1/pki.building"; rm -rf "$B"; mkdir -p "$B"; chmod 700 "$B"
  say "making the operator CA, the gateway certificate and the CA keystore (PKCS12) in the state directory"
  ( umask 077
    rand 32 > "$B/ks.pw"
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$B/ca.key" 2>/dev/null
    openssl req -x509 -new -key "$B/ca.key" -sha256 -days 30 -subj "/CN=GA1 Agent CA" \
      -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" -out "$B/ca.pem"
    openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$B/gateway.key" 2>/dev/null   # PKCS#8, as the listener loads it
    openssl req -new -key "$B/gateway.key" -subj "/CN=localhost" -out "$B/gateway.csr"
    printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature\nextendedKeyUsage=serverAuth\n' > "$B/gateway.ext"
    openssl x509 -req -in "$B/gateway.csr" -CA "$B/ca.pem" -CAkey "$B/ca.key" -CAcreateserial -days 30 -sha256 \
      -extfile "$B/gateway.ext" -out "$B/gateway.crt" 2>/dev/null
    # The CA key under alias agent-ca, opened with the keystore password (the same one opens every entry).
    openssl pkcs12 -export -inkey "$B/ca.key" -in "$B/ca.pem" -name agent-ca -out "$B/ca.p12" -passout "file:$B/ks.pw"
    # The lease-nonce HMAC entry: a PKCS12 secret-key entry of 64 characters (a 32-byte random value as hex), same password.
    # keytool -importpass prompts twice for the value to store, on stdin (printf is a builtin: nothing on argv).
    hmac="$(openssl rand -hex 32)"
    printf '%s\n%s\n' "$hmac" "$hmac" | keytool -importpass -alias lease-nonce-hmac -storetype PKCS12 -keystore "$B/ca.p12" \
      -storepass:file "$B/ks.pw" >/dev/null 2>"$B/keytool.err" || { echo "keytool -importpass failed:" >&2; sed 's/^/    /' "$B/keytool.err" >&2; exit 1; }
    unset hmac
    rm -f "$B/gateway.csr" "$B/gateway.ext" "$B/ca.srl" "$B/keytool.err" "$B/ca.key"   # the plaintext CA key is not kept: the keystore holds it
  ) || { rm -rf "$B"; die "making the PKI failed"; }
  ( pki_check "$B" ) || { rm -rf "$B"; exit 1; }
  # Move the verified files in, the keystore LAST: its presence is the "PKI made" marker.
  for f in ks.pw ca.pem gateway.key gateway.crt ca.p12; do mv "$B/$f" "$PKI/$f"; done
  rmdir "$B"
}

# The environment sm-launcher reads for the gateway (application.properties' SM_AGENT_* names). Call it ONLY inside the subshell that
# starts the launcher: the CA keystore password must reach no other process (not the scenario, not the agent).
gateway_env() {
  export SM_AGENT_GATEWAY_ENABLED=true
  export SM_AGENT_GATEWAY_PORT="$GATEWAY_PORT" SM_AGENT_GATEWAY_READINESS_PORT="$READINESS_PORT"
  export SM_AGENT_GATEWAY_CERT="$PKI/gateway.crt" SM_AGENT_GATEWAY_KEY="$PKI/gateway.key" SM_AGENT_GATEWAY_TRUST="$PKI/ca.pem"
  export SM_AGENT_GATEWAY_ENDPOINT="$AGENT_DIAL"
  export SM_AGENT_CA_KEYSTORE="$PKI/ca.p12" SM_AGENT_CA_KEY_ALIAS=agent-ca
  SM_AGENT_CA_KEYSTORE_PASSWORD="$(cat "$PKI/ks.pw")"; export SM_AGENT_CA_KEYSTORE_PASSWORD
}

# ---- the agent binary ---------------------------------------------------------------------------------------------------------
agent_target() {
  command -v go >/dev/null || die "go is required to build the agent"
  GOOS_T="${GA1_GOOS:-$(go env GOHOSTOS)}"; GOARCH_T="${GA1_GOARCH:-$(go env GOHOSTARCH)}"
  HOST_OS="$(go env GOHOSTOS)"; HOST_ARCH="$(go env GOHOSTARCH)"
  BIN="$GA1/bin/$GOOS_T-$GOARCH_T/sm-agent"
}

do_build_agent() {
  agent_target
  mkdir -p "$(dirname "$BIN")"
  # Rebuild when the binary is missing or older than any source of go-agent/ (the path already carries the target, so a different target is its own binary).
  if [[ -x "$BIN" ]] && [[ -z "$(find "$repo/go-agent" \( -name '*.go' -o -name go.mod -o -name go.sum \) -newer "$BIN" -print -quit)" ]]; then
    say "agent binary for $GOOS_T/$GOARCH_T is up to date"; return 0
  fi
  local ver; ver="$(git -C "$repo" rev-parse --short=12 HEAD)"
  say "building the agent from go-agent/ at $ver for $GOOS_T/$GOARCH_T"
  local cgo=(); [[ "$GOOS_T/$GOARCH_T" == "$HOST_OS/$HOST_ARCH" ]] || cgo=(CGO_ENABLED=0)
  ( cd "$repo/go-agent" && env ${cgo[@]+"${cgo[@]}"} GOOS="$GOOS_T" GOARCH="$GOARCH_T" go build -ldflags "-X main.version=ga1-$ver" -o "$BIN" ./cmd/sm-agent )
  echo "$ver" > "$GA1/agent.commit"
}

do_up() {
  ensure_run
  do_pki
  do_build_agent
  say "bringing the sm-local stack up with the agent gateway ON (listener :$GATEWAY_PORT, readiness :$READINESS_PORT, state $(basename "$STATE")/)"
  ( gateway_env; "$SM" up )
}

# ---- the Cache and its broker -----------------------------------------------------------------------------------------------
do_cache() {
  ensure_run
  local lab=(--label "$LABEL_KEY=$RUN") i
  if docker network inspect "$NET" >/dev/null 2>&1; then owned_or_die network "$NET"; else docker network create "${lab[@]}" "$NET" >/dev/null; fi
  if docker ps -a --format '{{.Names}}' | grep -qx "$BROKER"; then
    owned_or_die container "$BROKER"
  else
    say "starting the broker $BROKER (the Cache cannot start without one; it is the Cache's own messaging, not SM's mesh)"
    ( umask 077; printf 'username_admin_globalaccesslevel=admin\nusername_admin_password=%s\nsystem_scaling_maxconnectioncount=100\n' "$(rand 20)" > "$GA1/broker.env" )
    docker run -d --name "$BROKER" "${lab[@]}" --network "$NET" --shm-size 1g --ulimit core=-1 --ulimit nofile=2448:1048576 \
      --env-file "$GA1/broker.env" "$BROKER_IMAGE" >/dev/null || { rm -f "$GA1/broker.env"; die "the broker did not start"; }
    rm -f "$GA1/broker.env"
  fi
  for i in $(seq 1 90); do
    docker exec "$BROKER" curl -s -f http://localhost:5550/health-check/guaranteed-active >/dev/null 2>&1 && break
    sleep 2; [[ $i -lt 90 ]] || die "the broker did not become active in 180 s"
  done
  say "broker active"
  mkdir -p "$CONF"
  if docker ps -a --format '{{.Names}}' | grep -qx "$CACHE"; then owned_or_die container "$CACHE"; say "Cache container $CACHE already exists (its config file is left as it is)"; return 0; fi
  # The SM-owned file the container starts from (the first apply replaces it). The broker's default VPN and client username.
  cat > "$CONF/application.yml" <<YML
cache-engine:
  max-size: 250000
  solace:
    enabled: true
    host: tcp://$BROKER:55555
    vpn: default
    username: default
    password: default
    management:
      enabled: false
YML
  say "starting $CACHE from $CACHE_IMAGE, labelled com.solace.sm.instance=ga1-cache and com.solace.sm.config-file"
  docker run -d --name "$CACHE" "${lab[@]}" --network "$NET" -p 127.0.0.1::8081 \
    --label com.solace.sm.instance=ga1-cache --label com.solace.sm.config-file=/app/external/spring/config/application.yml \
    -v "$CONF:/app/external/spring/config" \
    --health-cmd 'bash -c "exec 3<>/dev/tcp/127.0.0.1/8081"' --health-interval 3s --health-timeout 2s --health-retries 20 --health-start-period 20s \
    "$CACHE_IMAGE" >/dev/null
  for i in $(seq 1 60); do
    [[ "$(docker inspect -f '{{.State.Health.Status}}' "$CACHE" 2>/dev/null)" == healthy ]] && { say "Cache healthy"; return 0; }
    sleep 2
  done
  docker logs --tail 20 "$CACHE" >&2 || true
  die "the Cache did not become healthy in 120 s"
}

# ---- the agent process: start / stop / alive / log, each a small function a remote variant can replace -------------------------
agent_alive() { # true when the recorded pid is a live sm-agent (never trust a bare pid file)
  [[ -f "$GA1/agent.pid" ]] || return 1
  local pid comm; pid="$(cat "$GA1/agent.pid")"
  kill -0 "$pid" 2>/dev/null || return 1
  comm="$(ps -p "$pid" -o comm= 2>/dev/null || true)"; [[ "$(basename "${comm:-x}")" == sm-agent* ]]
}

agent_launch() { # the enrolment token, on a first start, comes from the caller's environment
  agent_target
  [[ -x "$BIN" ]] || die "no agent binary for $GOOS_T/$GOARCH_T: run 'ga1-agent.sh up' (or build-agent) first"
  [[ "$GOOS_T/$GOARCH_T" == "$HOST_OS/$HOST_ARCH" ]] || die "the agent binary is built for $GOOS_T/$GOARCH_T; this local start runs the host's only (a remote variant replaces agent_launch)"
  mkdir -p "$AGENT_DATA"; chmod 700 "$AGENT_DATA"
  # The agent never sees a gateway or database secret: every such name is unset for it (only SM_AGENT_ENROLL_TOKEN is kept).
  local unset_args=() n
  while IFS= read -r n; do if [[ -n "$n" ]]; then unset_args+=(-u "$n"); fi; done < <(env | cut -d= -f1 | grep -E '^(SM_AGENT_(CA|GATEWAY)_|SM_LOCAL_.*PASSWORD|SM_(CONTROL|DATA)_|SM_DP_MASTER|PGPASSWORD)' || true)
  nohup env ${unset_args[@]+"${unset_args[@]}"} "$BIN" --data-dir "$AGENT_DATA" --connect-endpoint "$AGENT_DIAL" --trust-bundle "$PKI/ca.pem" \
    --roles control,telemetry --admin-addr "$AGENT_ADMIN" --log-level debug >> "$GA1/agent.log" 2>&1 < /dev/null &
  echo $! > "$GA1/agent.pid"
}

agent_kill() {
  if agent_alive; then
    local pid; pid="$(cat "$GA1/agent.pid")"
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    if kill -0 "$pid" 2>/dev/null; then kill -9 "$pid" 2>/dev/null || true; fi
    return 0
  fi
  return 1
}

agent_log_size() { if [[ -f "$GA1/agent.log" ]]; then wc -c < "$GA1/agent.log" | tr -d ' '; else echo 0; fi; }
agent_log_since() { if [[ -f "$GA1/agent.log" ]]; then tail -c "+$(($1 + 1))" "$GA1/agent.log"; fi; }

do_agent_start() {
  ensure_run
  if agent_alive; then say "agent already running"; return 0; fi
  agent_launch
  say "agent started (pid $(cat "$GA1/agent.pid"))"
}

do_agent_stop() {
  if agent_kill; then rm -f "$GA1/agent.pid"; say "agent stopped"; else rm -f "$GA1/agent.pid" 2>/dev/null || true; say "agent not running"; fi
}

do_scenario() {
  ensure_run
  command -v python3 >/dev/null || die "python3 is required"
  # No gateway secret is in this environment: the scenario needs the launcher's restart only through `restart-sm`, which builds its own.
  # GA1_SCRIPT and GA1_CACHE may be set by the caller (the G-A2 driver, ga2-host.sh: the agent and the Cache are on a remote host); unset, the values are G-A1's.
  GA1_DIR="$GA1" GA1_SCRIPT="${GA1_SCRIPT:-$here/ga1-agent.sh}" GA1_CACHE="${GA1_CACHE:-$CACHE}" GA1_PG="$SM_LOCAL_PG_CONTAINER" GA1_CONF="$CONF" GA1_GATEWAY_PORT="$GATEWAY_PORT" GA1_AGENT_ADMIN="$AGENT_ADMIN" \
    GA1_FIXTURE="$repo/contracts/validation/fixtures/cache-2.4.package.json" GA1_STATE="$STATE" GA1_RUN="$RUN" \
    SM_LOCAL_PORT="${SM_LOCAL_PORT:-8080}" python3 "$here/ga1-scenario.py"
}

do_down() {
  [[ -f "$GA1/run.id" ]] || { say "no run id in $(basename "$STATE")/: nothing of this gate's is recorded; removing nothing"; return 0; }
  ensure_run
  local run="$RUN" ids
  do_agent_stop || true
  "$SM" down                      # the launcher, then the PostgreSQL container and volume IF they carry the label, then the state directory
  ids="$(docker ps -aq --filter "label=$LABEL_KEY=$run")"
  if [[ -n "$ids" ]]; then say "removing the containers labelled for this run"; docker rm -f $ids >/dev/null; fi
  ids="$(docker network ls -q --filter "label=$LABEL_KEY=$run")"
  if [[ -n "$ids" ]]; then say "removing the network labelled for this run"; docker network rm $ids >/dev/null; fi
  ids="$(docker volume ls -q --filter "label=$LABEL_KEY=$run")"
  if [[ -n "$ids" ]]; then say "removing the volumes labelled for this run"; docker volume rm $ids >/dev/null; fi
  say "down: only resources labelled $LABEL_KEY=$run were removed"
}

do_status() {
  if [[ -f "$GA1/run.id" ]]; then ensure_run; fi
  docker ps -a --filter "label=$LABEL_KEY" --format '{{.Names}}  {{.Status}}  run={{.Label "'"$LABEL_KEY"'"}}' || true
  if agent_alive; then echo "agent: running (pid $(cat "$GA1/agent.pid"))"; else echo "agent: stopped"; fi
  "$SM" status || true
}

case "${1:-}" in
  up)         do_up ;;
  pki)        do_pki ;;
  build-agent) ensure_run; do_build_agent ;;
  cache)      do_cache ;;
  scenario)   do_scenario ;;
  all)        do_up; do_cache; do_scenario ;;
  restart-sm) do_pki; ( gateway_env; "$SM" start ) ;;
  agent-start) do_agent_start ;;
  agent-stop) do_agent_stop ;;
  agent-log-size) agent_log_size ;;
  agent-log-since) agent_log_since "${2:?offset}" ;;
  down)       do_down ;;
  status)     do_status ;;
  *) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 2 ;;
esac
