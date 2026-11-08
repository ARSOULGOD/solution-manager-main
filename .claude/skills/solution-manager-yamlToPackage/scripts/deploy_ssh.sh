#!/usr/bin/env bash
# Stage 3: deploy and run a managed integration (MI) on a host over SSH, FOR TESTING. This is a dev/test harness,
# not a Solution Manager feature: SM is a configuration plane and does not deploy artifacts.
#
#   deploy_ssh.sh plan     [options]                  # DEFAULT: print every command; no network action at all
#   deploy_ssh.sh apply    [options] --yes-i-approved # create dir, back up, copy, start, record state
#   deploy_ssh.sh verify   [options]                  # health until UP, log scan, Solace connection
#   deploy_ssh.sh teardown [options] --yes-i-approved # stop, restore the backup, remove only what apply created
#
# Connection (key or agent auth only; never a password; strict host key checking against YOUR known_hosts):
#   --host H --user U [--port 22] (--identity KEYFILE | --agent) --known-hosts FILE
# What to run:
#   --name NAME              instance name (container sm-skill-NAME, pid file, state)
#   --install-dir /abs/dir   the ONE remote directory this script writes to
#   --config FILE            rendered application.yml (render_config.py render); secrets are ${SM_SECRET_*}
#   --delivery docker|jar|tarball   --start docker|nohup|systemd
#   docker:  --image REF [--image-tar FILE] [--config-mount /app/config] [--docker-args "..."]
#   jar:     --artifact app.jar          tarball: --artifact app.tgz --start-cmd "cmd run from <dir>/app"
#   --secret-env NAME        repeatable; the value is read from YOUR shell at apply time and sent on stdin,
#                            never written to a file, a command line or this script's output
#   --timeout SECONDS        verify: how long to wait for UP (default 120)
# Exit: 0 ok, 1 failed, 2 bad usage, 3 refused (needs approval or unsafe), 4 SSH unreachable (stay in plan mode).
set -euo pipefail

die() { echo "error: $1" >&2; exit "${2:-2}"; }
sub="${1:-plan}"; [[ $# -gt 0 ]] && shift
HOST= USER_= PORT=22 IDENTITY= AGENT= KNOWN= NAME= DIR= CONFIG= DELIVERY=docker START=docker IMAGE= IMAGE_TAR=
ARTIFACT= START_CMD= CONFIG_MOUNT=/app/config DOCKER_ARGS= TIMEOUT=120 APPROVED= SECRETS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) HOST=$2; shift ;; --user) USER_=$2; shift ;; --port) PORT=$2; shift ;;
    --identity) IDENTITY=$2; shift ;; --agent) AGENT=1 ;; --known-hosts) KNOWN=$2; shift ;;
    --name) NAME=$2; shift ;; --install-dir) DIR=$2; shift ;; --config) CONFIG=$2; shift ;;
    --delivery) DELIVERY=$2; shift ;; --start) START=$2; shift ;; --image) IMAGE=$2; shift ;;
    --image-tar) IMAGE_TAR=$2; shift ;; --artifact) ARTIFACT=$2; shift ;; --start-cmd) START_CMD=$2; shift ;;
    --config-mount) CONFIG_MOUNT=$2; shift ;; --docker-args) DOCKER_ARGS=$2; shift ;;
    --secret-env) SECRETS+=("$2"); shift ;; --timeout) TIMEOUT=$2; shift ;; --yes-i-approved) APPROVED=1 ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) die "unknown option $1" ;;
  esac
  shift
done

# ---------------------------------------------------------------- validation (no network)
[[ $sub =~ ^(plan|apply|verify|teardown)$ ]] || die "unknown subcommand $sub"
[[ -n $HOST && -n $USER_ && -n $NAME && -n $DIR ]] || die "--host, --user, --name and --install-dir are required"
[[ $NAME =~ ^[a-z0-9][a-z0-9-]{0,40}$ ]] || die "--name must be lowercase letters, digits and dashes"
[[ $DIR == /* && $DIR != *..* && $DIR =~ ^/[A-Za-z0-9._/-]+$ ]] || die "--install-dir must be an absolute, plain path"
d=${DIR%/}
case "$d" in /|/bin*|/boot*|/dev*|/etc*|/lib*|/proc*|/root|/sbin*|/sys*|/usr|/usr/bin*|/usr/lib*|/var|/home|/tmp|/opt|/srv)
  die "refusing install dir $DIR: too broad or a system path" 3 ;; esac
[[ $(tr -cd / <<<"$d" | wc -c) -ge 2 ]] || die "refusing install dir $DIR: use a dedicated directory at least two levels deep" 3
DIR=$d
[[ -z $AGENT && -z $IDENTITY ]] && die "choose --identity KEYFILE or --agent (password auth is not supported)"
[[ $sub != plan && -z $KNOWN ]] && die "--known-hosts FILE is required: strict host key checking against a file you trust" 3
[[ $DELIVERY =~ ^(docker|jar|tarball)$ && $START =~ ^(docker|nohup|systemd)$ ]] || die "bad --delivery/--start"
[[ $DELIVERY == docker && $START != docker ]] && die "--delivery docker needs --start docker"
[[ $sub =~ ^(plan|apply)$ ]] && { [[ -f $CONFIG ]] || die "--config FILE is required"; }
[[ $sub =~ ^(plan|apply)$ && $DELIVERY == docker && -z $IMAGE ]] && die "--delivery docker needs --image"
[[ $sub =~ ^(plan|apply)$ && $DELIVERY != docker && ! -f $ARTIFACT ]] && die "--artifact FILE is required for $DELIVERY"
[[ $DELIVERY == tarball && -z $START_CMD ]] && die "--delivery tarball needs --start-cmd"
for s in "${SECRETS[@]}"; do [[ $s =~ ^SM_SECRET_[A-Z0-9_]+$ ]] || die "--secret-env $s: expected SM_SECRET_*"; done
if [[ -n $CONFIG && -f $CONFIG ]]; then  # every secret-named key must be a placeholder, empty or null (I6)
  leaked=$(python3 - "$CONFIG" <<'EOF'
import re, sys, yaml
rx = re.compile(r"(password|passwd|pwd|secret|token|apikey|privatekey|passphrase)$", re.I)
def walk(d, p=""):
    for k, v in (d or {}).items():
        key = f"{p}.{k}" if p else str(k)
        if isinstance(v, dict):
            yield from walk(v, key)
        elif rx.search(str(k).replace("-", "").replace("_", "")) and v not in (None, "") \
                and not (isinstance(v, str) and re.fullmatch(r"\$\{SM_SECRET_[A-Z0-9_]+\}", v)):
            yield key
print(" ".join(walk(yaml.safe_load(open(sys.argv[1])))))
EOF
)
  [[ -z $leaked ]] || die "$CONFIG holds literal values for secret key(s): $leaked. Render it with render_config.py (placeholders only)" 3
fi

SSH_OPTS=(-p "$PORT" -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10)
[[ -n $KNOWN ]] && SSH_OPTS+=(-o UserKnownHostsFile="$KNOWN" -o GlobalKnownHostsFile=/dev/null)
[[ -n $IDENTITY ]] && SSH_OPTS+=(-i "$IDENTITY" -o IdentitiesOnly=yes)
SCP_OPTS=("${SSH_OPTS[@]/#-p/-P}")
TARGET="$USER_@$HOST"
CNAME="sm-skill-$NAME"
STATE="$DIR/.sm-skill-state"
DRY=; [[ $sub == plan ]] && DRY=1
q() { printf '%q' "$1"; }

# Every remote action goes through these two, so `plan` prints exactly what `apply` runs.
R() {  # R "<remote command>" [stdin-description]
  echo "+ ssh ${SSH_OPTS[*]} $TARGET -- $1${2:+   # stdin: $2}"
  [[ -n $DRY ]] && return 0
  ssh "${SSH_OPTS[@]}" "$TARGET" -- "$1"
}
C() {  # C <local file> <remote path>
  echo "+ scp ${SCP_OPTS[*]} $1 $TARGET:$2"
  [[ -n $DRY ]] && return 0
  scp -q "${SCP_OPTS[@]}" "$1" "$TARGET:$2"
}
secrets_stdin() { local s; for s in "${SECRETS[@]}"; do printf '%s\n' "${!s-}"; done; }
read_secrets_remote() {  # remote shell prologue: read one line per secret from stdin into its variable
  local s out=; for s in "${SECRETS[@]}"; do out+="IFS= read -r $s; export $s; "; done; printf '%s' "$out"
}
cfg() {  # read port/ssl/exposure/log file from the rendered config (local; no network)
  python3 - "$CONFIG" <<'EOF'
import sys, yaml
def flat(d, p=""):
    o = {}
    for k, v in (d or {}).items():
        key = f"{p}.{k}" if p else str(k)
        o.update(flat(v, key) if isinstance(v, dict) else {key: v})
    return o
c = flat(yaml.safe_load(open(sys.argv[1])))
inc = str(c.get("management.endpoints.web.exposure.include", ""))
print(f"PORT={int(c.get('server.port') or 8080)}")
print(f"SSL={'1' if c.get('server.ssl.enabled') is True else '0'}")
print(f"HEALTH_EXPOSED={'1' if ('health' in inc.split(',') or inc.strip() == '*') else '0'}")
print(f"LOGFILE={c.get('logging.file.name') or ''}")
EOF
}

reach() {
  [[ -n $DRY ]] && return 0
  if ! ssh "${SSH_OPTS[@]}" "$TARGET" -- true 2>/tmp/sm-skill-ssh-err.$$; then
    echo "SSH to $TARGET:$PORT failed: $(tail -1 /tmp/sm-skill-ssh-err.$$)" >&2; rm -f /tmp/sm-skill-ssh-err.$$
    echo "No workaround is attempted. Re-run with 'plan' and run the printed commands from a machine that can reach the host." >&2
    exit 4
  fi
  rm -f /tmp/sm-skill-ssh-err.$$
}

case "$sub" in
plan|apply)
  [[ $sub == apply && -z $APPROVED ]] && die "apply needs --yes-i-approved (run plan first and show it to the user)" 3
  for s in "${SECRETS[@]}"; do [[ $sub == apply && -z ${!s-} ]] && die "secret $s is not set in your shell"; done
  [[ $sub == plan ]] && echo "# DRY RUN: nothing below is executed. Secrets appear only as \${SM_SECRET_*} names."
  [[ $START == systemd && $sub == apply ]] && die "systemd start is not automated: the unit would need its secrets in an EnvironmentFile on disk (I6). Use the plan output and your own secret mechanism (e.g. systemd-creds)." 3
  reach
  ts=$(date -u +%Y%m%dT%H%M%SZ)
  R "set -e; created=0; [ -d $(q "$DIR") ] || { mkdir -p $(q "$DIR"); created=1; }; for sub in config work app; do [ -d $(q "$DIR")/\$sub ] || { mkdir -p $(q "$DIR")/\$sub; echo created_sub=\$sub >> $(q "$STATE"); }; done; \
if [ -f $(q "$DIR/config/application.yml") ]; then cp -p $(q "$DIR/config/application.yml") $(q "$DIR/config/application.yml.bak.$ts"); echo backup=$(q "$DIR/config/application.yml.bak.$ts") >> $(q "$STATE"); fi; \
echo created_dir=\$created >> $(q "$STATE"); echo name=$(q "$NAME") >> $(q "$STATE")"
  C "$CONFIG" "$DIR/config/application.yml"
  case "$DELIVERY" in
    docker)
      if [[ -n $IMAGE_TAR ]]; then C "$IMAGE_TAR" "$DIR/app/image.tar"; R "docker image inspect $(q "$IMAGE") >/dev/null 2>&1 || docker load -i $(q "$DIR/app/image.tar")"
      else R "docker image inspect $(q "$IMAGE") >/dev/null"; fi ;;
    jar) C "$ARTIFACT" "$DIR/app/app.jar" ;;
    tarball) C "$ARTIFACT" "$DIR/app/artifact.tgz"; R "tar -xzf $(q "$DIR/app/artifact.tgz") -C $(q "$DIR/app")" ;;
  esac
  envflags=; for s in "${SECRETS[@]}"; do envflags+=" -e $s"; done
  case "$START" in
    docker)
      run="$(read_secrets_remote)docker run -d --name $(q "$CNAME") --label sm-skill=1$envflags \
-v $(q "$DIR/config"):$(q "$CONFIG_MOUNT"):ro -v $(q "$DIR/work"):/app/work $DOCKER_ARGS $(q "$IMAGE") > $(q "$DIR/.cid") && echo container=\$(cat $(q "$DIR/.cid")) >> $(q "$STATE") && cat $(q "$DIR/.cid")"
      if [[ -n $DRY ]]; then R "$run" "${#SECRETS[@]} secret value(s) from your shell"
      else echo "+ ssh ... -- <docker run as printed by plan>   # stdin: ${#SECRETS[@]} secret value(s)"; secrets_stdin | ssh "${SSH_OPTS[@]}" "$TARGET" -- "$run"; fi ;;
    nohup)
      cmd=${START_CMD:-"java -jar $(q "$DIR/app/app.jar") --spring.config.additional-location=file:$(q "$DIR/config")/"}
      run="$(read_secrets_remote)cd $(q "$DIR/work") && nohup $cmd > $(q "$DIR/work/stdout.log") 2>&1 & echo \$! > $(q "$DIR/.pid"); echo pid=\$(cat $(q "$DIR/.pid")) >> $(q "$STATE"); cat $(q "$DIR/.pid")"
      if [[ -n $DRY ]]; then R "$run" "${#SECRETS[@]} secret value(s) from your shell"
      else secrets_stdin | ssh "${SSH_OPTS[@]}" "$TARGET" -- "$run"; fi ;;
    systemd)
      echo "# systemd (printed only): install a unit running from $DIR/work with ExecStart for the artifact,"
      echo "# load secrets with systemd-creds or your vault, then: sudo systemctl daemon-reload && sudo systemctl start $CNAME" ;;
  esac
  if [[ $sub == plan ]]; then echo "# end of plan. Show it to the user; run 'apply --yes-i-approved' only after they approve."
  else echo "applied: state in $STATE (verify next; teardown to undo)"; fi
  ;;
verify)
  reach
  eval "$(cfg)"
  [[ $HEALTH_EXPOSED == 1 ]] || echo "WARN: management.endpoints.web.exposure.include does not expose health; health over HTTP is not available"
  scheme=http; k=; [[ $SSL == 1 ]] && { scheme=https; k=-k; }
  url="$scheme://127.0.0.1:$PORT/actuator/health"
  echo "health: polling $url on the target for up to ${TIMEOUT}s"
  end=$((SECONDS + TIMEOUT)) body= ok=
  while (( SECONDS < end )); do
    body=$(ssh "${SSH_OPTS[@]}" "$TARGET" -- "curl -fsS $k --max-time 5 $(q "$url")" 2>/dev/null || true)
    if python3 -c 'import sys,json; sys.exit(0 if json.loads(sys.argv[1]).get("status")=="UP" else 1)' "$body" 2>/dev/null; then ok=1; break; fi
    sleep 3
  done
  if [[ -n $ok ]]; then echo "HEALTH UP"; else echo "HEALTH NOT UP within ${TIMEOUT}s: ${body:-no response}"; fi
  python3 - "$body" <<'EOF'
import json, sys
try:
    comps = json.loads(sys.argv[1]).get("components", {})
except Exception:
    comps = {}
hits = {k: v for k, v in comps.items() if "solace" in k.lower() or "binder" in k.lower() or "jms" in k.lower()}
if not hits:
    print("SOLACE UNKNOWN: health exposes no solace/binder component (needs management.endpoint.health.show-details)")
for k, v in hits.items():
    mocked = (v.get("details") or {}).get("mocked") is True
    print(f"SOLACE {v.get('status')}{' (MOCKED broker)' if mocked else ''}: component {k}")
EOF
  if [[ -n $LOGFILE ]]; then
    lines=$(ssh "${SSH_OPTS[@]}" "$TARGET" -- "tail -n 400 $(q "$DIR/work/$LOGFILE") 2>/dev/null" || true)
    for s in "${SECRETS[@]}"; do [[ -n ${!s-} ]] && lines=${lines//"${!s}"/****}; done
    errs=$(grep -E 'ERROR|Exception' <<<"$lines" || true)
    echo "LOG $DIR/work/$LOGFILE: $(wc -l <<<"$lines") lines read, $(grep -c . <<<"$errs" || true) ERROR/Exception"
    [[ -n $errs ]] && head -20 <<<"$errs"
  else
    echo "LOG: logging.file.name not set; no log file to scan"
  fi
  [[ -n $ok && -z ${errs:-} ]] || exit 1
  ;;
teardown)
  [[ -z $APPROVED ]] && die "teardown needs --yes-i-approved (offer it; never run it silently)" 3
  reach
  R "set -e; [ -f $(q "$STATE") ] || { echo 'no state file: nothing this script created'; exit 0; }; \
c=\$(sed -n 's/^container=//p' $(q "$STATE") | tail -1); [ -n \"\$c\" ] && docker rm -f \"\$c\" >/dev/null && echo stopped container \$c; \
p=\$(sed -n 's/^pid=//p' $(q "$STATE") | tail -1); [ -n \"\$p\" ] && kill \"\$p\" 2>/dev/null && echo stopped pid \$p; \
b=\$(sed -n 's/^backup=//p' $(q "$STATE") | head -1); if [ -n \"\$b\" ]; then mv -f \"\$b\" $(q "$DIR/config/application.yml"); echo restored \$b; fi; \
if grep -q '^created_dir=1' $(q "$STATE"); then rm -rf -- $(q "$DIR"); echo removed $(q "$DIR"); \
else rm -f -- $(q "$DIR/.cid") $(q "$DIR/.pid") $(q "$DIR/app/image.tar") $(q "$DIR/app/app.jar") $(q "$DIR/app/artifact.tgz"); [ -n \"\$b\" ] || rm -f -- $(q "$DIR/config/application.yml"); \
for sub in \$(sed -n 's/^created_sub=//p' $(q "$STATE")); do case \$sub in config|work|app) rm -rf -- $(q "$DIR")/\$sub;; esac; done; \
rm -f -- $(q "$STATE"); echo removed only the files and subdirectories apply created; fi"
  ;;
esac
exit 0
