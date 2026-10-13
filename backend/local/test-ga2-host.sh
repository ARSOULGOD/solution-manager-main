#!/usr/bin/env bash
# Static and hermetic checks for the G-A2 driver (ga2-host.sh) and the scenario hooks. Starts nothing, contacts nothing, never touches the real host
# command: the host command is the stub and docker is a fake on PATH. Plants violations in COPIES of the driver to prove each guard fires.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
drv="$here/ga2-host.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
code() { grep -v '^[[:space:]]*#' "$1"; }   # the driver without its comment lines

bash -n "$drv" "$here/e2e-host-stub.sh" "$here/ga2-dry-run.sh" || fail "syntax"
python3 -m py_compile "$here/ga1-scenario.py" || fail "scenario syntax"

# 1. no ssh, scp or `docker -H` anywhere in the driver's code
if code "$drv" | grep -nE '(^|[^A-Za-z_.-])(ssh|scp)([^A-Za-z_-]|$)|docker[[:space:]]+(-H|--host)' ; then fail "the driver uses ssh, scp or docker -H"; fi
code "$drv" | grep -qE 'target\.env|e2e-target' && fail "the driver names a target file"
# 2. no secret variable inside a command string handed to the host (the argv the remote shell sees)
leak_scan() { python3 - "$1" <<'PY'
import re, sys
bad = []
for n, line in enumerate(open(sys.argv[1]), 1):
    if line.lstrip().startswith("#"):
        continue
    # the quoted command string after rrun / rrun_in / "$HOST_CMD" run
    for m in re.finditer(r'(?:rrun|rrun_in|"\$HOST_CMD" run)\s+"((?:[^"\\]|\\.)*)"', line):
        if re.search(r'\$\{?(SM_E2E_BROKER_[A-Z]+|SM_AGENT_ENROLL_TOKEN|ENROLL|TOKEN|BROKER_PASS)', m.group(1)):
            bad.append("%d: %s" % (n, m.group(0)[:80]))
print("\n".join(bad))
sys.exit(1 if bad else 0)
PY
}
leak_scan "$drv" || fail "a secret is expanded inside a remote command string"
cp "$drv" "$tmp/planted.sh"; printf '%s\n' 'rrun "echo $SM_E2E_BROKER_PASSWORD"' >> "$tmp/planted.sh"
if leak_scan "$tmp/planted.sh" >/dev/null; then fail "the leak scan did not catch a planted password in a run string"; fi
cp "$drv" "$tmp/planted.sh"; printf '%s\n' 'ssh host true' >> "$tmp/planted.sh"
if ! code "$tmp/planted.sh" | grep -qE '(^|[^A-Za-z_.-])(ssh|scp)([^A-Za-z_-]|$)'; then fail "the ssh scan would not catch a planted ssh"; fi

# 3. the down path refuses an empty or odd run id, and issues no host command
export GA2_HOST_CMD="$here/e2e-host-stub.sh" E2E_STUB_ROOT="$tmp/root" E2E_STUB_LOG="$tmp/stub.log" GA2_STATE_DIR="$tmp/state"
mkdir -p "$tmp/state"; : > "$tmp/stub.log"
for bad in "" "../../etc" "ABCDEFGH" "abc" "abcdefgh1"; do
  if out="$(GA2_RUN_ID="$bad" "$drv" down 2>&1)"; then fail "down accepted the run id '$bad': $out"; fi
  grep -q 'run id is empty or not' <<<"$out" || fail "down did not explain the refusal for '$bad': $out"
done
if "$drv" down >/dev/null 2>&1; then fail "down with no recorded run id was accepted"; fi
[[ ! -s "$tmp/stub.log" ]] || fail "a refused down still ran a host command: $(cat "$tmp/stub.log")"

# 4. the docker helper: a mutating verb on a container that lacks THIS run's label is refused; created containers must carry the label
mkdir -p "$tmp/bin"
cat > "$tmp/bin/docker" <<'FAKE'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_DOCKER_LOG"
if [[ "$1" == inspect ]]; then
  case "${@: -1}" in mine) echo -n "abcd1234" ;; *) echo -n "" ;; esac
fi
FAKE
chmod +x "$tmp/bin/docker"
export FAKE_DOCKER_LOG="$tmp/docker.log" GA2_RUN_ID=abcd1234
: > "$FAKE_DOCKER_LOG"
for verb in restart stop rm kill exec start; do
  if PATH="$tmp/bin:$PATH" "$drv" docker "$verb" existing >/dev/null 2>&1; then fail "docker $verb on an unlabelled container was allowed"; fi
done
if grep -qE '^(restart|stop|rm|kill|exec|start) ' "$FAKE_DOCKER_LOG"; then fail "a mutating docker verb reached the daemon for an unlabelled container: $(cat "$FAKE_DOCKER_LOG")"; fi
for verb in restart stop rm kill start; do PATH="$tmp/bin:$PATH" "$drv" docker "$verb" mine >/dev/null 2>&1 || fail "docker $verb on this run's container was refused"; done
grep -q '^rm ' "$FAKE_DOCKER_LOG" || fail "the allowed rm did not reach the daemon"
if PATH="$tmp/bin:$PATH" "$drv" docker run -d --name x busybox >/dev/null 2>&1; then fail "docker run without the run label was allowed"; fi
PATH="$tmp/bin:$PATH" "$drv" docker run -d --name x --label "com.solace.sm.ga2.run=abcd1234" busybox >/dev/null 2>&1 || fail "docker run with the run label was refused"
if PATH="$tmp/bin:$PATH" "$drv" docker system prune -f >/dev/null 2>&1; then fail "docker system prune was allowed"; fi
PATH="$tmp/bin:$PATH" "$drv" docker inspect -f '{{.Id}}' existing >/dev/null 2>&1 || fail "a read-only inspect of an existing container was refused"
unset GA2_RUN_ID

# 5. conf-holds validates its arguments (no injection through the value)
if GA2_RUN_ID=abcd1234 "$drv" conf-holds a '1; id' >/dev/null 2>&1; then fail "conf-holds accepted a non-numeric value"; fi

# 6. G-A1 behaviour is unchanged: with none of the new variables the stage body is byte-identical to the one G-A1 produced; with them it is the G-A2 one
mkdir -p "$tmp/sc"; : > "$tmp/sc/secrets.env"; : > "$tmp/sc/ids.env"
body() { env -i PATH="$PATH" GA1_DIR="$tmp/sc" GA1_STATE="$tmp/sc" GA1_SCRIPT=x GA1_CACHE=c GA1_CONF=/tmp "$@" python3 - "$here/ga1-scenario.py" <<'PY'
import importlib.util, json, sys
spec = importlib.util.spec_from_file_location("s", sys.argv[1]); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
print(json.dumps(m.stage_body(5, 260000), sort_keys=True)); print(" ".join(m.STEPS))
PY
}
EXPECTED='{"baseStateVersion": 5, "componentSelection": {"instances": [{"instanceKey": "i1", "layout": {"x": 10, "y": 20}, "type": "cache-engine"}]}, "draftValues": {"i1": {"key-source": "TOPIC", "max-size": 260000, "namespace": "ga1", "solace.enabled": true, "solace.host": "tcp://ga1-broker:55555", "solace.username": "default", "solace.vpn": "default"}}, "rendered": [{"instanceKey": "i1", "renderedPath": "cache-engine.mode", "value": "READ"}, {"instanceKey": "i1", "renderedPath": "cache-engine.provider", "value": "caffeine"}, {"instanceKey": "i1", "renderedPath": "cache-engine.namespace", "value": "ga1"}, {"instanceKey": "i1", "renderedPath": "cache-engine.key-source", "value": "TOPIC"}, {"instanceKey": "i1", "renderedPath": "cache-engine.max-size", "value": 260000}, {"instanceKey": "i1", "renderedPath": "cache-engine.solace.enabled", "value": true}, {"instanceKey": "i1", "renderedPath": "cache-engine.solace.host", "value": "tcp://ga1-broker:55555"}, {"instanceKey": "i1", "renderedPath": "cache-engine.solace.vpn", "value": "default"}, {"instanceKey": "i1", "renderedPath": "cache-engine.solace.username", "value": "default"}, {"instanceKey": "i1", "renderedPath": "cache-engine.solace.messaging-mode", "value": "MESSAGING"}, {"instanceKey": "i1", "renderedPath": "cache-engine.solace.management.enabled", "value": false}]}'
out="$(body)"
[[ "$(sed -n 1p <<<"$out")" == "$EXPECTED" ]] || fail "the G-A1 stage body changed: $(sed -n 1p <<<"$out")"
[[ "$(sed -n 2p <<<"$out")" == "setup enrol apply1 metrics killsm undeclare drain revoke retire" ]] || fail "the G-A1 step order changed: $(sed -n 2p <<<"$out")"
out="$(body GA1_DPREMOTE=on GA1_NAMESPACE=ga2-abcd1234-a GA1_BROKER_HOST=tcp://h:1 GA1_BROKER_VPN=v GA1_BROKER_USERNAME=u GA1_TOPIC_PREFIX=ga2 GA1_BROKER_PASSWORD_ENV=P P=zzzsecretzzz)"
[[ "$(sed -n 2p <<<"$out")" == "setup enrol apply1 metrics remote_declare remote_apply_agent_up remote_apply_agent_down remote_undeclare killsm undeclare drain revoke retire" ]] || fail "the G-A2 step order is wrong: $(sed -n 2p <<<"$out")"
b="$(sed -n 1p <<<"$out")"
grep -q '"namespace": "ga2-abcd1234-a"' <<<"$b" || fail "the G-A2 namespace is not staged"
grep -q 'cache-engine.cache-hit-topic' <<<"$b" && grep -q 'tcp://h:1' <<<"$b" || fail "the G-A2 topics or broker host are not staged"
grep -q 'zzzsecretzzz\|solace.password' <<<"$b" && fail "the password (or its field) is in the staged body: it must go through setConfigSecret only"

# 7. the evidence redactor: no host, address, user, root path or broker value; existing-N
mkdir -p "$tmp/state"; printf 'root|/srv/rootcanary\nprefix|pfxcanary-\nexisting-1|oldcache-canary\n' > "$tmp/state/redact.map"
printf '%s\n' '[10:00:00] CHECK PASS: x /srv/rootcanary/ga2-abcd1234/cache-a oldcache-canary pfxcanary-ga2r-abcd1234 tcp://hostcanary.example:55555 10.1.2.3 1234abcd-1234-1234-1234-1234567890ab' '==== G-A2 results ====' 'enrol  PASS  a' > "$tmp/run.log"
ev="$(SM_E2E_BROKER_HOST=tcp://hostcanary.example:55555 SM_E2E_BROKER_VPN=vpncanary SM_E2E_BROKER_USERNAME=usercanary SM_E2E_BROKER_PASSWORD=passcanary "$drv" evidence "$tmp/run.log")"
grep -qE 'rootcanary|oldcache-canary|pfxcanary|hostcanary|10\.1\.2\.3|1234abcd-1234' <<<"$ev" && fail "the evidence kept a value it must redact: $ev"
grep -q 'existing-1' <<<"$ev" && grep -q '1234abcd' <<<"$ev" || fail "the evidence lost the existing-N mapping or the 8-character id"
grep -q 'enrol  PASS  a' <<<"$ev" || fail "the evidence has no result table"
echo "test-ga2-host.sh: ok"
