#!/usr/bin/env bash
#
# Plan 013 T1.5 live gates L3 and L4 (combined schedule section 4; OR-110, OR-122 (4)): the API half of the checks that need the
# E2E host. OWED: written, NEVER RUN against a host (the night it was written Docker was down and no remote host was to be touched).
#
#   L3 = S2 and S3 of T1.5: edit the live record (the next probe and apply use it) and rotate the key.
#   L4 = MF-R2: the connection test with the Docker daemon stopped, and with an OS account that is not in the `docker` group.
#
# What this script does and does not do. It drives ONLY the Solution Manager API (login, the four T1.5 operations, getHealth,
# observeApply) against a RUNNING local stack (backend/local/sm-local.sh up). It never connects to the E2E host itself and never
# reads a file of coordinates: everything arrives in the environment. The steps that need root or an account on the host
# (stop and start the Docker daemon, create and remove an OS account, add and remove a public key in authorized_keys) are MANUAL: the
# script prints the exact action, waits for you to do it and press Enter, and records nothing about the host. L4 is root-level, so
# it also refuses to start unless SM_LIVE_GATE_L4_PERMITTED=yes says the owner has permitted it (combined schedule section 8 item 5).
#
# Usage:
#   backend/local/live-gate-t15.sh plan                  print the procedure; contact nothing
#   backend/local/live-gate-t15.sh --dry-run STEP        print every request STEP would send; send nothing (no login either)
#   backend/local/live-gate-t15.sh l3-edit               S2: move the record to a second sshd port with the original fenced off; probe and apply use the new record
#   backend/local/live-gate-t15.sh l3-rotate             S3: rotate to a new key, confirm by the public fingerprint, then the old key is refused
#   backend/local/live-gate-t15.sh l4-daemon             MF-R2 (a): daemon stopped -> docker.available=false, authenticated=true, never a 5xx
#   backend/local/live-gate-t15.sh l4-nogroup            MF-R2 (b): an account outside the docker group -> the same result
#   backend/local/live-gate-t15.sh l4-restore-check      after restoring both: the test passes again
#
# Environment (nothing here is read from a file in the repository):
#   SM_BASE (http://127.0.0.1:8080)   SM_ORIGIN (http://localhost:8080; must be a trusted origin of the stack)
#   SM_USER, SM_PASSWORD   an Administrator that has already rotated its initial credential (target.edit and target.use)
#   SM_ORGANIZATION        optional: the organization it signs in to, by id or name (default: the install organization, OR-125 (4))
#   TARGET_ID              the registered E2E target
#   L3_ALT_PORT            a second sshd listener of the SAME machine (same host keys), started by hand for the run, for l3-edit
#   L3_APPLY_BODY_FILE     a JSON submitApply body for the Cache placement (from `sm-local.sh bodies` and the designer), for l3-edit
#   L3_INSTANCE_ID         the instance the apply and the health read name, for l3-edit
#   L3_OLD_KEY_FILE, L3_NEW_KEY_FILE   the current and the replacement private key (OpenSSH, unencrypted), for l3-rotate
#   L4_NOGROUP_USER        the OS account without the docker group (its authorized_keys already holds the stored key's public half)
#   SM_LIVE_GATE_L4_PERMITTED=yes   the owner's permission for the root-level L4 steps
#   SM_LIVE_GATE_L3_FENCE_PERMITTED=yes   the owner's permission for l3-edit's one root-level step: a firewall rule on the E2E host that
#                          refuses the stack's address to the ORIGINAL sshd port (the host's sshd is not stopped: nobody is locked out)
#   SM_LIVE_GATE_MANUAL_HOOK=cmd    run `cmd "<the manual action>"` at each MANUAL step instead of waiting (a wrapper that performs it)
#   SM_LIVE_GATE_NONINTERACTIVE=1   do not wait at a MANUAL step and do nothing there; the default waits for Enter

set -euo pipefail

SM_BASE="${SM_BASE:-http://127.0.0.1:8080}"
SM_ORIGIN="${SM_ORIGIN:-http://localhost:8080}"
dry=false
step=""
for a in "$@"; do
  case "$a" in
    --dry-run) dry=true ;;
    -h|--help) sed -n '2,36p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) step="$a" ;;
  esac
done

say() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }
need() { local v; for v in "$@"; do [[ -n "${!v:-}" ]] || die "$v is required (see the header)"; done; }
manual() {
  printf '\n  MANUAL (on the E2E host, as the owner permits): %s\n' "$*"
  if [[ "$dry" == true ]]; then return; fi
  if [[ -n "${SM_LIVE_GATE_MANUAL_HOOK:-}" ]]; then "$SM_LIVE_GATE_MANUAL_HOOK" "$*"; return; fi   # a wrapper performs it
  if [[ "${SM_LIVE_GATE_NONINTERACTIVE:-}" != 1 ]]; then read -r -p "  press Enter when done " _ || true; fi
}

# ---- the plan ----------------------------------------------------------------------------------------------------------
procedure() {
  cat <<'EOF'
L3 (T1.5 S2, S3), E2E host, OR-110. Each step leaves the host's Cache working.
  l3-edit   (review W4C-6: the old record is made UNUSABLE, so only the new one can pass; the control step proves it is)
    1. MANUAL start a second sshd listener on L3_ALT_PORT with the host's own keys (same pin)
    2. testTargetConnection            expect pinned=true authenticated=true through the ORIGINAL record
    3. MANUAL fence: refuse the stack's address to the ORIGINAL sshd port (firewall rule; the sshd keeps running)
    4. testTargetConnection            CONTROL: expect hostKeyPinned=false (the fence works, and the old record cannot reach the host)
    5. PATCH port -> L3_ALT_PORT       expect 200 (the alternate listener offers the pin); the record now names it
    6. testTargetConnection            expect pinned=true authenticated=true: only the NEW record can pass while the old is fenced
    7. getHealth(instance)             poll: every placement GREEN through the new record, or the gate fails
    8. submitApply(L3_APPLY_BODY_FILE) observe to a terminal state: APPLIED or STAGED, or the gate fails (no terminal state is a failure)
    9. MANUAL remove the fence; PATCH port back (expect 200); testTargetConnection passes; MANUAL stop the alternate listener
  l3-rotate (T1.5 S3, with the fixture image approved this is also covered by TargetConnectionTestIT)
    1. MANUAL add the new public key to authorized_keys on the host        (SM cannot do it: OR-52)
    2. PUT credential (new key)        expect 200 and the NEW public fingerprint; testTargetConnection: authenticated=true
    3. MANUAL remove the old public key from authorized_keys
    4. PUT credential (old key)        200 (SM does not check the host); testTargetConnection: authenticated=false (refused by the host)
    5. PUT credential (new key)        restore; testTargetConnection: authenticated=true
L4 (MF-R2), root-level; needs SM_LIVE_GATE_L4_PERMITTED=yes; the daemon stop is LAST and alone.
  l4-nogroup
    1. MANUAL create the OS account L4_NOGROUP_USER (no docker group) with the stored key's public half in its authorized_keys
    2. PATCH sshUser -> L4_NOGROUP_USER  200 (no scan, not refused for work in flight)
    3. testTargetConnection            expect 200, authenticated=true, docker.available=false (never a 5xx)
    4. PATCH sshUser back; MANUAL remove the account
  l4-daemon
    1. MANUAL stop the Docker daemon on the host
    2. testTargetConnection            expect 200, hostKeyPinned=true authenticated=true docker.available=false (never a 5xx)
    3. MANUAL start the Docker daemon; wait until the Cache container is running again
  l4-restore-check                      testTargetConnection: everything true again; getHealth(instance) healthy
EOF
}

if [[ "$step" == "plan" || -z "$step" ]]; then procedure; exit 0; fi
case "$step" in l3-edit|l3-rotate|l4-daemon|l4-nogroup|l4-restore-check) ;; *) die "unknown step '$step' (try: plan)" ;; esac
if [[ "$step" == l3-edit && "$dry" != true && "${SM_LIVE_GATE_L3_FENCE_PERMITTED:-}" != yes ]]; then
  die "l3-edit fences the original sshd port for the stack's address (a firewall rule on the E2E host); it runs only with the owner's explicit permission (SM_LIVE_GATE_L3_FENCE_PERMITTED=yes). Without the fence the gate cannot tell the new record from the old one."
fi
if [[ "$step" == l4-* && "$step" != l4-restore-check && "${SM_LIVE_GATE_L4_PERMITTED:-}" != yes ]]; then
  die "$step is a root-level change on the E2E host; it runs only with the owner's explicit permission (SM_LIVE_GATE_L4_PERMITTED=yes)"
fi

# ---- the API -----------------------------------------------------------------------------------------------------------
# Review W4C-5 (I6, on the operator's disk): everything this script writes, including the private keys l3-rotate turns into request
# bodies, goes under ONE private directory that an EXIT trap removes on every way out (a failed expect, an unset variable, ^C, a
# kill). INT, TERM and HUP exit, which runs the EXIT trap. A file created outside $WORK would escape it, so nothing is.
umask 077
WORK="$(mktemp -d "${TMPDIR:-/tmp}/live-gate-t15.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
trap 'exit 130' INT TERM HUP
JAR="$WORK/jar"
CSRF=""

# request METHOD PATH [JSON-BODY-FILE]  -> prints the status, leaves the body in ${JAR}.body
request() {
  local method="$1" path="$2" body="${3:-}"
  if [[ "$dry" == true ]]; then
    printf '    [dry-run] %s %s%s%s\n' "$method" "$SM_BASE" "$path" "${body:+  body=$body}" >&2
    echo 200 >"${JAR}.body.status"; echo '{}' >"${JAR}.body"; echo 200; return
  fi
  local args=(-sS -o "${JAR}.body" -w '%{http_code}' -X "$method" -b "$JAR" -c "$JAR" -H "Origin: $SM_ORIGIN" -H "X-SM-CSRF: $CSRF")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data-binary "@$body")
  curl "${args[@]}" "$SM_BASE$path"
}
# json PATH  -> the value at a dotted path of the last response body ("docker.available", "items.-1.state"); empty if absent
json() {
  python3 - "${JAR}.body" "$1" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    d = None
for k in sys.argv[2].split("."):
    if d is None:
        break
    if isinstance(d, list):
        try:
            d = d[int(k)]
        except (ValueError, IndexError):
            d = None
    elif isinstance(d, dict):
        d = d.get(k)
    else:
        d = None
print("" if d is None else d)
PY
}
expect() { # expect STATUS-WANTED STATUS-GOT LABEL
  [[ "$dry" == true ]] && { say "ok   $3 -> (dry-run, nothing sent)"; return 0; }
  [[ "$2" == "$1" ]] && { say "ok   $3 -> $2"; return 0; }
  printf 'FAIL %s -> %s (wanted %s): %s\n' "$3" "$2" "$1" "$(head -c 400 "${JAR}.body" 2>/dev/null)" >&2; exit 1
}
expect_field() { # expect_field PATH WANT LABEL
  local got; got="$(json "$1")"
  [[ "$dry" == true || "$got" == "$2" ]] && { say "ok   $3: $1=$got"; return 0; }
  printf 'FAIL %s: %s is "%s" (wanted "%s")\n' "$3" "$1" "$got" "$2" >&2; exit 1
}
login() {
  need SM_USER SM_PASSWORD
  [[ "$dry" == true ]] && { say "[dry-run] login as \$SM_USER"; return; }
  local body; body="$(mktemp "$WORK/login.XXXXXX")"
  # json.dumps, so a password with a quote or a backslash is still valid JSON; the values come from the environment, never argv
  python3 -c 'import json,os; print(json.dumps({"organization": os.environ.get("SM_ORGANIZATION", "00000000-0000-4000-8000-000000000011"), "username": os.environ["SM_USER"], "credential": os.environ["SM_PASSWORD"]}))' >"$body"
  local code; code="$(curl -sS -o "${JAR}.body" -w '%{http_code}' -c "$JAR" -H "Origin: $SM_ORIGIN" -H 'Content-Type: application/json' \
    --data-binary "@$body" "$SM_BASE/api/cp/auth/login")"; rm -f "$body"
  expect 200 "$code" "login"
  CSRF="$(json csrfToken)"; [[ -n "$CSRF" ]] || die "no csrfToken in the login response"
  [[ "$(json mustRotate)" != "True" ]] || die "$SM_USER must rotate its initial credential first (rotateOwnCredential)"
}
# json_obj KEY VALUE [int]  -> the path of a file holding {"KEY": VALUE} (a string, or a number with `int`); built with json.dumps
json_obj() {
  local f; f="$(mktemp "$WORK/body.XXXXXX")"
  python3 -c 'import json,sys; v=sys.argv[2]; print(json.dumps({sys.argv[1]: int(v) if len(sys.argv) > 3 else v}))' "$@" >"$f"
  echo "$f"
}
# health_states -> the distinct placement states of the last getHealth body, comma-joined and sorted ("GREEN", "GREEN,RED", "")
health_states() {
  python3 - "${JAR}.body" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
    print(",".join(sorted({p.get("state", "") for p in d.get("placements", [])})))
except Exception:
    print("")
PY
}

test_connection() { # test_connection LABEL
  local code; code="$(request POST "/api/dp/targets/$TARGET_ID/connection/test")"
  expect 200 "$code" "testTargetConnection ($1)"
  say "     result: $(cat "${JAR}.body")"
}

need TARGET_ID
login

case "$step" in
  l3-edit)
    need L3_ALT_PORT L3_APPLY_BODY_FILE L3_INSTANCE_ID
    original="$(request GET "/api/dp/targets/$TARGET_ID/connection")"; expect 200 "$original" "getTargetConnection"
    original_port="$(json port)"; [[ "$dry" == true ]] && original_port="<original port>"
    manual "start a SECOND sshd listener on port $L3_ALT_PORT with the host's own keys (e.g. sshd -p $L3_ALT_PORT -o PidFile=/tmp/sm-gate-sshd.pid); it must offer the pinned key"
    test_connection "original record, before"; expect_field hostKeyPinned True "pinned"; expect_field authenticated True "authenticated"
    manual "FENCE: refuse the stack's source address to TCP port $original_port on the host (a firewall rule; do NOT stop the host's sshd). Remove it in the last step"
    test_connection "CONTROL: original record, fenced"
    expect_field hostKeyPinned False "the control: the fence works, so the old record can no longer reach the host"
    say "S2: the record's port $original_port becomes $L3_ALT_PORT (the same machine and keys, so the pin is offered)"
    expect 200 "$(request PATCH "/api/dp/targets/$TARGET_ID/connection" "$(json_obj port "$L3_ALT_PORT" int)")" "editTargetConnection port -> alternate listener"
    expect_field port "$L3_ALT_PORT" "the record now names the alternate port"
    test_connection "new record, original fenced"; expect_field hostKeyPinned True "pinned"; expect_field authenticated True "authenticated"
    say "the NEXT probe: every placement must be GREEN through the new record (the old one is fenced)"
    green=false
    for i in 1 2 3 4 5 6; do
      expect 200 "$(request GET "/api/dp/instances/$L3_INSTANCE_ID/health")" "getHealth (poll $i)"
      [[ "$dry" == true ]] && { green=true; break; }
      states="$(health_states)"; say "     placement states: ${states:-none}"
      [[ "$states" == "GREEN" ]] && { green=true; break; }
      sleep 10
    done
    [[ "$green" == true ]] || die "the instance never became GREEN through the new record: the next probe did not use it"
    say "the NEXT apply: submit and observe it to a terminal state"
    expect 202 "$(request POST "/api/dp/instances/$L3_INSTANCE_ID/apply" "$L3_APPLY_BODY_FILE")" "submitApply"
    apply_id="$(json applyId)"
    terminal=false
    for i in $(seq 1 30); do
      expect 200 "$(request GET "/api/dp/instances/$L3_INSTANCE_ID/apply/observe?applyId=$apply_id")" "observeApply (poll $i)"
      state="$(json items.-1.state)"; say "     state=$state"
      case "$state" in
        APPLIED|STAGED) terminal=true; break ;;
        FAILED_*|CONFIG_DIVERGED|APPLY_INDETERMINATE) die "the apply ended $state through the edited record" ;;
      esac
      [[ "$dry" == true ]] && { terminal=true; break; }
      sleep 5
    done
    [[ "$terminal" == true ]] || die "the apply never reached APPLIED or STAGED in 30 polls; the record is still on the alternate port (restore it by hand: PATCH port $original_port, after removing the fence)"
    manual "REMOVE the fence on TCP port $original_port"
    expect 200 "$(request PATCH "/api/dp/targets/$TARGET_ID/connection" "$(json_obj port "$original_port" int)")" "editTargetConnection port -> original"
    test_connection "restored"; expect_field authenticated True "authenticated"
    manual "stop the alternate sshd listener on port $L3_ALT_PORT"
    ;;
  l3-rotate)
    need L3_OLD_KEY_FILE L3_NEW_KEY_FILE
    key_body() { python3 -c 'import json,sys; print(json.dumps({"privateKey": open(sys.argv[1]).read()}))' "$1" >"$2"; }
    new_body="$(mktemp "$WORK/new-key.XXXXXX")"; old_body="$(mktemp "$WORK/old-key.XXXXXX")"
    if [[ "$dry" == true ]]; then echo '{"privateKey":"<redacted>"}' >"$new_body"; cp "$new_body" "$old_body"
    else key_body "$L3_NEW_KEY_FILE" "$new_body"; key_body "$L3_OLD_KEY_FILE" "$old_body"; fi
    manual "add the NEW public key to the authorized_keys of the target's SSH user (keep the old one for now)"
    expect 200 "$(request PUT "/api/dp/targets/$TARGET_ID/credential" "$new_body")" "rotateTargetCredential (new key)"
    say "the stored key's public fingerprint is now: $(json credential.publicKeyFingerprint)   (compare: ssh-keygen -lf new.pub)"
    test_connection "new key"; expect_field authenticated True "authenticated with the new key"
    manual "REMOVE the OLD public key from authorized_keys"
    expect 200 "$(request PUT "/api/dp/targets/$TARGET_ID/credential" "$old_body")" "rotateTargetCredential (old key again)"
    test_connection "old key, removed from the host"; expect_field authenticated False "the host refuses the old key"
    expect 200 "$(request PUT "/api/dp/targets/$TARGET_ID/credential" "$new_body")" "rotateTargetCredential (new key, restored)"
    test_connection "restored"; expect_field authenticated True "authenticated with the new key"
    rm -f "$new_body" "$old_body"      # early, as soon as they are no longer needed; the EXIT trap removes them on any other exit
    ;;
  l4-nogroup)
    need L4_NOGROUP_USER
    original="$(request GET "/api/dp/targets/$TARGET_ID/connection")"; expect 200 "$original" "getTargetConnection"
    original_user="$(json sshUser)"; [[ "$dry" == true ]] && original_user="<original user>"
    manual "create the OS account $L4_NOGROUP_USER WITHOUT the docker group, with the stored key's public half in its authorized_keys"
    expect 200 "$(request PATCH "/api/dp/targets/$TARGET_ID/connection" "$(json_obj sshUser "$L4_NOGROUP_USER")")" "editTargetConnection sshUser -> account without docker"
    test_connection "account without the docker group"
    expect_field authenticated True "authenticated"; expect_field docker.available False "docker is unavailable to that account"
    expect 200 "$(request PATCH "/api/dp/targets/$TARGET_ID/connection" "$(json_obj sshUser "$original_user")")" "editTargetConnection sshUser -> original"
    manual "remove the OS account $L4_NOGROUP_USER"
    ;;
  l4-daemon)
    manual "stop the Docker daemon on the E2E host (this is the most disruptive step; nothing else runs meanwhile)"
    test_connection "daemon stopped"
    expect_field hostKeyPinned True "pinned"; expect_field authenticated True "authenticated"; expect_field docker.available False "docker is unavailable"
    manual "START the Docker daemon again and wait until the Cache container is running"
    ;;
  l4-restore-check)
    test_connection "after the restore"
    expect_field hostKeyPinned True "pinned"; expect_field authenticated True "authenticated"; expect_field docker.available True "docker answers again"
    ;;
esac
say "$step: done"
