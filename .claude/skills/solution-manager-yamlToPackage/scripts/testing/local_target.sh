#!/usr/bin/env bash
# Local SSH target for the skill's OWN Stage 3 test (D1): the repo's stand-in sshd image (key auth only, Docker CLI,
# the host's docker.sock) on 127.0.0.1:2223, a fresh ed25519 key, and a known_hosts captured from it. The target root
# is mounted at the SAME path inside the container, so `docker run -v <root>/...` issued over SSH resolves on the host.
# The MI is the stand-in image (scripts/testing/standin-mi), and the Solace broker is MOCKED by a shared directory.
#
#   local_target.sh up   <root>    # prints the connection options to pass to deploy_ssh.sh
#   local_target.sh down <root>    # removes the sshd and every container labelled sm-skill=1; leaves <root>
#
# known_hosts here is captured by ssh-keyscan from a container this script just started on loopback, which is
# acceptable for a local test only. For a real host, the operator supplies a known_hosts they have verified.
set -euo pipefail
cmd=${1:-}; ROOT=${2:-}
[[ -n $ROOT && $ROOT == /* ]] || { echo "usage: $0 up|down /abs/root" >&2; exit 2; }
NAME=sm-skill-sshd PORT=2223 KEYS="$ROOT/.keys"
case "$cmd" in
  up)
    docker image inspect sm-standin-sshd:latest >/dev/null 2>&1 || { echo "sm-standin-sshd:latest not built (docs/runbooks/backend/run-local-logs-and-file-demo.md)" >&2; exit 2; }
    mkdir -p "$KEYS" "$ROOT/target"
    [[ -f $KEYS/id_ed25519 ]] || ssh-keygen -q -t ed25519 -N '' -C sm-skill-test -f "$KEYS/id_ed25519"
    cp "$KEYS/id_ed25519.pub" "$KEYS/authorized_keys" && chmod 644 "$KEYS/authorized_keys"
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker run -d --name "$NAME" --label sm-skill=1 -p 127.0.0.1:$PORT:22 \
      -v /var/run/docker.sock:/var/run/docker.sock -v "$ROOT/target:$ROOT/target" \
      -v "$KEYS/authorized_keys:/home/demo/.ssh/authorized_keys:ro" sm-standin-sshd:latest >/dev/null
    for _ in $(seq 1 20); do ssh-keyscan -p $PORT -t ed25519 127.0.0.1 >"$KEYS/known_hosts" 2>/dev/null && [[ -s $KEYS/known_hosts ]] && break; sleep 1; done
    [[ -s $KEYS/known_hosts ]] || { echo "sshd did not come up" >&2; exit 1; }
    echo "--host 127.0.0.1 --port $PORT --user demo --identity $KEYS/id_ed25519 --known-hosts $KEYS/known_hosts"
    echo "# install dirs must live under $ROOT/target; MI containers should use --network container:$NAME" >&2
    ;;
  down)
    docker ps -aq --filter label=sm-skill=1 | xargs -r docker rm -f >/dev/null
    echo "removed $NAME and sm-skill containers; $ROOT left in place"
    ;;
  *) echo "usage: $0 up|down /abs/root" >&2; exit 2 ;;
esac
