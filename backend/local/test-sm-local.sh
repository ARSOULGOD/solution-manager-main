#!/usr/bin/env bash
# Cheap automated check for sm-local.sh (needs the prerequisites installed but starts nothing and contacts nothing): the --check mode must validate the target
# file by NAME only, never print a value from it, skip a missing or incomplete target, and refuse an unknown command.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/sm-local.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

# 1. a complete fake target file: reported ready, and none of its values is echoed
cat > "$tmp/target.env" <<ENV
SM_LOCAL_TARGET_HOST=host-canary.invalid
SM_LOCAL_TARGET_PORT=22
SM_LOCAL_TARGET_USER=user-canary
SM_LOCAL_TARGET_FINGERPRINT=SHA256:fingerprintcanaryfingerprintcanary00
SM_LOCAL_TARGET_KEY_FILE=$tmp/key
SM_LOCAL_TARGET_CONTAINER_PREFIX=prefix-canary-
SM_LOCAL_TARGET_CONFIG_ROOT=/srv/root-canary
SM_LOCAL_CACHE_BROKER_PASSWORD=password-canary
ENV
: > "$tmp/key"
out="$(SM_LOCAL_TARGET_ENV="$tmp/target.env" "$script" --check up 2>&1)" || fail "--check up failed: $out"
grep -q 'target file: target file found' <<<"$out" || fail "a complete target file was not reported ready: $out"
grep -qE 'canary' <<<"$out" && fail "a value from the target file was printed"

# 2. a missing file is skipped with a clear message
out="$(SM_LOCAL_TARGET_ENV="$tmp/absent.env" "$script" check 2>&1)" || fail "check failed"
grep -q 'the target is skipped' <<<"$out" || fail "a missing target file was not reported as skipped: $out"

# 3. an incomplete file names the missing VARIABLES, not values
printf 'SM_LOCAL_TARGET_HOST=host-canary.invalid\n' > "$tmp/partial.env"
out="$(SM_LOCAL_TARGET_ENV="$tmp/partial.env" "$script" check 2>&1)" || fail "check failed"
grep -q 'SM_LOCAL_TARGET_USER' <<<"$out" || fail "the missing variable was not named: $out"
grep -q 'canary' <<<"$out" && fail "a value was printed"

# 4. an unknown command is refused
if "$script" frobnicate >/dev/null 2>&1; then fail "an unknown command was accepted"; fi
# 5. the database names and the state directory are overridable, and the "would do" line names the override
out="$(SM_LOCAL_PG_CONTAINER=name-canary-pg SM_LOCAL_PG_PORT=55999 SM_LOCAL_STATE_DIR="$tmp/st" "$script" --check up 2>&1)" || fail "--check with overrides failed: $out"
grep -q 'as name-canary-pg on 127.0.0.1:55999' <<<"$out" || fail "the PostgreSQL override is not named in the plan: $out"
[[ ! -e "$tmp/st" ]] || fail "--check created the state directory"
out="$("$script" --check up 2>&1)" || fail "--check (defaults) failed"
grep -q 'as sm-local-pg on 127.0.0.1:55440' <<<"$out" || fail "the defaults changed: $out"

# 6. a state directory recorded for one PostgreSQL container refuses another (up/start/stop/seed/down), and removes nothing
mkdir -p "$tmp/st2"; printf 'SM_LOCAL_STATE_PG_CONTAINER=recorded-pg\n' > "$tmp/st2/secrets.env"
for c in up start stop seed down; do
  if out="$(SM_LOCAL_PG_CONTAINER=other-pg SM_LOCAL_STATE_DIR="$tmp/st2" "$script" "$c" 2>&1)"; then fail "'$c' acted on a state directory recorded for another container"; fi
  grep -q "belongs to the PostgreSQL container 'recorded-pg', not 'other-pg'" <<<"$out" || fail "'$c' did not name the mismatch: $out"
done
[[ -f "$tmp/st2/secrets.env" ]] || fail "a refused down removed the state"

# 7. an env file that changes the database names is refused
printf 'SM_LOCAL_PG_CONTAINER=sm-local-pg\n' > "$tmp/env.sh"
if out="$(SM_LOCAL_PG_CONTAINER=mine-pg SM_LOCAL_ENV_FILE="$tmp/env.sh" "$script" --check up 2>&1)"; then fail "an env file that renamed the container was accepted"; fi
grep -q 'refusing' <<<"$out" || fail "the env-file refusal was not explained: $out"
echo "test-sm-local.sh: ok"
