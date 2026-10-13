#!/usr/bin/env bash
# Offline check for live-gate-t15.sh (plan 013 T1.5; the gates themselves are OWED): contacts nothing and starts nothing. It proves the
# script's guard rails, not the gates: the procedure prints, every step has a dry run that sends nothing, an unknown step is refused,
# and the root-level steps (all of L4, and l3-edit's firewall rule) refuse to run without the owner's permission variable (the restore
# check, which changes nothing, does not), and (W4C-5) a failed gate leaves no file behind.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/live-gate-t15.sh"
fail() { echo "FAIL: $*" >&2; exit 1; }

bash -n "$script" || fail "syntax"
"$script" plan | grep -q 'l4-daemon' || fail "the procedure does not list l4-daemon"

export SM_USER=u SM_PASSWORD=p TARGET_ID=00000000-0000-0000-0000-000000000001 L3_ALT_PORT=2222
export L3_APPLY_BODY_FILE=/dev/null L3_INSTANCE_ID=00000000-0000-0000-0000-000000000002 L3_OLD_KEY_FILE=/dev/null
export L3_NEW_KEY_FILE=/dev/null L4_NOGROUP_USER=nogroup SM_BASE=http://127.0.0.1:9   # nothing listens on the discard port
for step in l3-edit l3-rotate l4-nogroup l4-daemon l4-restore-check; do
  out="$(SM_LIVE_GATE_L4_PERMITTED=yes "$script" --dry-run "$step" 2>&1)" || fail "--dry-run $step failed: $out"
  grep -q 'FAIL' <<<"$out" && fail "--dry-run $step reported a failure: $out"
  grep -q "$step: done" <<<"$out" || fail "--dry-run $step did not finish: $out"
done

for step in l4-nogroup l4-daemon; do
  if (unset SM_LIVE_GATE_L4_PERMITTED; "$script" --dry-run "$step") >/dev/null 2>&1; then fail "$step ran without the owner's permission"; fi
done
(unset SM_LIVE_GATE_L4_PERMITTED; "$script" --dry-run l4-restore-check) >/dev/null 2>&1 || fail "the restore check changes nothing and needs no permission"
(unset SM_LIVE_GATE_L3_FENCE_PERMITTED; "$script" l3-edit) >/dev/null 2>&1 && fail "l3-edit ran without the owner's permission for its firewall rule"
if "$script" frobnicate >/dev/null 2>&1; then fail "an unknown step was accepted"; fi
if (unset SM_USER; "$script" l3-rotate) >/dev/null 2>&1; then fail "a missing credential was not refused"; fi
# Review W4C-5: no file the script wrote survives a failed gate. A stand-in API answers login and every other request with a body
# that lacks the fields the gate expects, so l3-rotate fails AFTER it has written the old and the new private key and the login body.
# What is looked for is a unique marker in the keys and the password, anywhere in the temporary directories mktemp may use (macOS's
# bare `mktemp` ignores TMPDIR, so TMPDIR alone would miss a leak).
marker="gate-test-marker-$$-$RANDOM"
fake_dir="$(mktemp -d)"; leftovers="$(mktemp -d)"; default_tmp="$(dirname "$(mktemp -u)")"
cat >"$fake_dir/server.py" <<'PY'
import http.server, json, sys
class H(http.server.BaseHTTPRequestHandler):
    def _answer(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n:
            self.rfile.read(n)
        body = json.dumps({"csrfToken": "x", "mustRotate": False}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    do_GET = do_POST = do_PUT = do_PATCH = _answer
    def log_message(self, *a): pass
s = http.server.HTTPServer(("127.0.0.1", 0), H)
print(s.server_address[1], flush=True)
s.serve_forever()
PY
python3 "$fake_dir/server.py" >"$fake_dir/port" &
server=$!
cleanup() {
  kill "$server" 2>/dev/null || true
  # whatever the script under test leaked, this test does not add to the pile
  grep -rlF "$marker" "$default_tmp" "$leftovers" 2>/dev/null | xargs rm -f 2>/dev/null || true
  rm -rf "$fake_dir" "$leftovers"
}
trap cleanup EXIT
for _ in $(seq 1 50); do [[ -s "$fake_dir/port" ]] && break; sleep 0.1; done
port="$(cat "$fake_dir/port")"
printf -- '-----BEGIN OPENSSH PRIVATE KEY-----\nold-%s\n-----END OPENSSH PRIVATE KEY-----\n' "$marker" >"$fake_dir/old.key"
printf -- '-----BEGIN OPENSSH PRIVATE KEY-----\nnew-%s\n-----END OPENSSH PRIVATE KEY-----\n' "$marker" >"$fake_dir/new.key"
leaked() { grep -rlF "$marker" "$default_tmp" "$leftovers" 2>/dev/null | grep -v "^$fake_dir/" || true; }

if TMPDIR="$leftovers" SM_PASSWORD="pw-$marker" SM_BASE="http://127.0.0.1:$port" SM_LIVE_GATE_NONINTERACTIVE=1 \
    L3_OLD_KEY_FILE="$fake_dir/old.key" L3_NEW_KEY_FILE="$fake_dir/new.key" "$script" l3-rotate >"$fake_dir/out" 2>&1; then
  fail "the stand-in API passed a gate it cannot pass"
fi
grep -q 'rotateTargetCredential (new key)' "$fake_dir/out" || fail "the gate did not get as far as writing the key bodies: $(cat "$fake_dir/out")"
[[ -z "$(leaked)" ]] || fail "a failed gate left secrets on disk: $(leaked)"
# a login that cannot connect (the password body was already written)
if TMPDIR="$leftovers" SM_PASSWORD="pw-$marker" SM_BASE=http://127.0.0.1:9 "$script" l3-rotate >/dev/null 2>&1; then fail "a gate passed with nothing listening"; fi
[[ -z "$(leaked)" ]] || fail "a failed login left the password on disk: $(leaked)"
# a password with a quote and a backslash is still valid JSON (the body is built with json.dumps)
if TMPDIR="$leftovers" SM_PASSWORD='p"w\x' SM_BASE="http://127.0.0.1:$port" SM_LIVE_GATE_NONINTERACTIVE=1 L3_OLD_KEY_FILE="$fake_dir/old.key" \
    L3_NEW_KEY_FILE="$fake_dir/new.key" "$script" l3-rotate >"$fake_dir/out2" 2>&1; then fail "unexpected pass"; fi
grep -q 'ok   login -> 200' "$fake_dir/out2" || fail "a password with a quote did not log in: $(cat "$fake_dir/out2")"
echo "test-live-gate-t15.sh: ok"
