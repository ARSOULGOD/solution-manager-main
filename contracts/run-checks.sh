#!/usr/bin/env bash
# Every executable check for the Solution Package contract. One command, non-zero on failure.
#
# Usage: ./run-checks.sh [--strict]
#   --strict  a check that could not run is a FAILURE, not a note. Use this in CI.
#
# Why --strict exists: steps 7-9 need `contracts/view-models/node_modules`. Without it they
# print SKIPPED — and the summary line used to say "ALL CHECKS PASSED" anyway, so three of the
# nine checks could stop running and the suite still went green. That is the `L7` failure mode
# (a check that cannot fail is not a check) sitting inside the check runner itself. The summary
# now always states how many checks did not run, and --strict refuses to exit 0 when any did.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"
rc=0
skipped=0
strict=0
for arg in "$@"; do
  case "$arg" in
    --strict) strict=1 ;;
    *) echo "unknown argument: $arg (expected --strict)" >&2; exit 2 ;;
  esac
done
step() { printf '\n\033[1m== %s\033[0m\n' "$1"; }
# skip <reason> — records that a check did not run. Never silently green.
skip() {
  skipped=$((skipped + 1))
  if [ "$strict" -eq 1 ]; then
    echo "  FAIL (--strict): check did not run — $1"; rc=1
  else
    echo "  SKIPPED: $1"
  fi
}

step "0. Preflight: the Python gates' dependencies must be importable AND meet their declared floors"
# NOT a skip. Steps 1-6 and 10 are the Python half of the contract; without these three packages
# they cannot run at all, and a suite that cannot run must be red whether or not --strict was
# passed (`L7`: a check that cannot fail is not a check). The three are declared in
# requirements.txt and were previously undeclared — installed on the author's machine, invisible
# to everyone else, and the failure they produced was a bare ModuleNotFoundError traceback.
#
# It checks the FLOORS too, and that is the R8/F8 fix: requirements.txt declares `>=` versions and
# the preflight only tested importability, so the floors were documentation. An older jsonschema
# that cannot resolve a 2020-12 `$ref` imports perfectly and then fails step 1 with a schema
# traceback that names nothing about versions. `>=` stays (nothing here pins behaviour, and a
# lockfile for three read-only validators is more machinery than the risk warrants) — but a floor
# nothing compares against is not a floor.
#
# Pure stdlib on purpose: importlib.metadata is the installed-distribution truth, and reaching for
# `packaging` to compare three X.Y.Z strings would add a fourth dependency to the check that exists
# to make the first three checkable.
python3 - "$here/requirements.txt" <<'PY'
import re
import sys
from importlib.metadata import PackageNotFoundError, version

# Distribution name (as requirements.txt spells it) -> module name the gates import.
MODULE = {"jsonschema": "jsonschema", "referencing": "referencing", "PyYAML": "yaml"}
REQ = re.compile(r"^\s*([A-Za-z0-9._-]+)\s*>=\s*([0-9][0-9A-Za-z._-]*)\s*$")


def release(v):
    """The PEP 440 release segment as ints. A non-numeric part (rc, dev, a local tag) sorts as -1,
    so a pre-release never counts as satisfying the floor of its own final version."""
    return [int(p) if p.isdigit() else -1 for p in re.split(r"[._-]", v)]


def below(actual, floor):
    a, f = release(actual), release(floor)
    width = max(len(a), len(f))
    a += [0] * (width - len(a))
    f += [0] * (width - len(f))
    return a < f


req_file = sys.argv[1]
problems, checked = [], []
try:
    lines = open(req_file, encoding="utf-8").read().splitlines()
except OSError as exc:
    sys.exit(f"  FAIL cannot read {req_file}: {exc}")

declared = []
for line in lines:
    if not line.strip() or line.lstrip().startswith("#"):
        continue
    m = REQ.match(line)
    if not m:
        problems.append(f"unparsable requirement line (expected 'name>=X.Y.Z'): {line.strip()!r}")
        continue
    declared.append((m.group(1), m.group(2)))

if not declared:
    problems.append(f"no requirements parsed from {req_file} — a preflight with nothing to check "
                    f"must fail, never pass vacuously")

for dist, floor in declared:
    module = MODULE.get(dist, dist)
    try:
        __import__(module)
    except ImportError:
        problems.append(f"{dist}: module '{module}' is not importable (need >= {floor})")
        continue
    try:
        actual = version(dist)
    except PackageNotFoundError:
        problems.append(f"{dist}: imports as '{module}' but no installed distribution metadata "
                        f"— cannot verify the >= {floor} floor")
        continue
    if below(actual, floor):
        problems.append(f"{dist}: installed {actual}, requires >= {floor}")
    else:
        checked.append(f"{dist} {actual} (>= {floor})")

if problems:
    for p in problems:
        print(f"  FAIL {p}")
    print(f"       install/upgrade with:  pip3 install -r {req_file}")
    print("       (the Python gates - steps 1-6 and 10 - cannot run without them)")
    sys.exit(1)
print("  PASS " + " · ".join(checked))
PY
if [ $? -ne 0 ]; then
  # Exit rather than continue: every remaining Python step would fail with the same root cause
  # and bury this message under seven tracebacks.
  printf '\n\033[1m== RESULT: %s\033[0m\n' "FAILURES (preflight)"
  exit 1
fi

step "1. Positive fixtures must pass every gate"
python3 validation/validate-package.py validation/fixtures/cache-2.4.package.json || rc=1
python3 validation/validate-package.py validation/fixtures/filemesh-ftp-solace.package.json --dev-mode || rc=1
# MINOR 4: no Python gate ran over engine-edge before now. It is a dev-mode-signed synthetic
# fixture (provenance.author: dev-mode), so it is refused by gate 2 without --dev-mode.
python3 validation/validate-package.py validation/fixtures/engine-edge.package.json --dev-mode || rc=1

step "2. Negative fixtures must ALL be rejected"
# valueFrom-*.json are excluded here and checked below: their only defect is a
# cross-document gate 15-19 violation (UI-D28), which validate-package.py does not
# know how to see, so as complete packages it would accept them and this step
# would wrongly report them as "should have been rejected".
neg_files=$(ls validation/negative/*.json | grep -v '/valueFrom-')
python3 validation/validate-package.py $neg_files --expect-fail || rc=1
if python3 validation/check_value_from.py 'contracts/validation/negative/valueFrom-*.json'; then
  echo "  FAIL (accepted, should have been rejected) valueFrom-*.json bindings"; rc=1
else
  echo "  PASS (rejected as intended) valueFrom-*.json bindings"
fi

step "3. Overlapping appliesTo must be rejected (gate 3)"
# Requires the rejection to be gate3 SPECIFICALLY, not any failure (B-1): a per-file gate1 (or
# other) violation on either overlap fixture would make the run non-zero for an unrelated
# reason and mask the overlap detector never firing, so a bare "was it rejected" check would
# stay green even if gate3 itself were broken.
overlap_out="$(python3 validation/validate-package.py 'validation/fixtures/overlap/*.json' 2>&1)"
overlap_rc=$?
file_failures="$(echo "$overlap_out" | grep '^  FAIL ' | grep -v '^  FAIL gate3  overlap:')"
gate3_line="$(echo "$overlap_out" | grep '^  FAIL gate3  overlap:')"
if [ $overlap_rc -eq 0 ]; then
  echo "  FAIL overlap was accepted"; rc=1
elif [ -n "$file_failures" ]; then
  echo "  FAIL overlap was rejected, but by a per-file gate violation, not gate3 (the overlap"
  echo "       signal is masked):"
  echo "$overlap_out" | sed 's/^/    /'
  rc=1
elif [ -z "$gate3_line" ]; then
  echo "  FAIL overlap was rejected, but gate3 never fired:"
  echo "$overlap_out" | sed 's/^/    /'
  rc=1
else
  echo "  PASS (rejected as intended, by gate3 only) overlapping appliesTo"
fi

# Steps 4-6 read file-mesh-ui's schema dump, which lives in a SEPARATE repository that is not
# vendored here. Resolve it once: $SM_FILEMESH_SQL wins, else the conventional sibling path.
# Absent, these three SKIP rather than fail — a missing sibling checkout is not a contract defect,
# and it previously surfaced as a bare FileNotFoundError traceback plus a misleading FAIL. Under
# --strict a skip is still a failure, so CI cannot go green without the dump.
fm_sql="${SM_FILEMESH_SQL:-$HOME/Work/Development/file-mesh-ui/database/file-mesh-schema.sql}"
fm_reason="file-mesh-ui schema dump not found at $fm_sql — set SM_FILEMESH_SQL to its location"

step "4. File Mesh adapter must reproduce the fixture byte-for-byte"
if [ ! -f "$fm_sql" ]; then
  skip "$fm_reason"
else
  SM_FILEMESH_SQL="$fm_sql" python3 validation/adapters/filemesh-adapter.py > /tmp/fm-regen.$$ 2>/dev/null
  if diff -q /tmp/fm-regen.$$ validation/fixtures/filemesh-ftp-solace.package.json >/dev/null; then
    echo "  PASS adapter output is reproducible"
  else
    echo "  FAIL adapter output drifted from the committed fixture"; rc=1
  fi
  rm -f /tmp/fm-regen.$$
fi

step "5. File Mesh coverage report"
if [ ! -f "$fm_sql" ]; then
  skip "$fm_reason"
else
  SM_FILEMESH_SQL="$fm_sql" python3 validation/adapters/filemesh-adapter.py --report | head -9
fi

step "6. Evidence extraction must report zero parse failures"
if [ ! -f "$fm_sql" ]; then
  skip "$fm_reason"
else
  SM_FILEMESH_SQL="$fm_sql" python3 ../docs/design/dynamic-ui/evidence/extract-fm-vocabulary.py 2>/dev/null \
    | sed -n '1,4p;/GRAMMAR/,+1p' | sed 's/^/  /'
  SM_FILEMESH_SQL="$fm_sql" python3 ../docs/design/dynamic-ui/evidence/extract-fm-vocabulary.py 2>/dev/null \
    | grep -q "unparsed rows (must be 0)           : 0" || { echo "  FAIL unparsed rows"; rc=1; }
fi

step "7. TypeScript view-model engine: strict typecheck + unit tests"
if [ -d view-models/node_modules ] && command -v npm >/dev/null 2>&1; then
  (cd view-models && npm run --silent typecheck) || { echo "  FAIL tsc reported type errors"; rc=1; }
  (cd view-models && npm run --silent test:run)  || { echo "  FAIL vitest reported failures"; rc=1; }
else
  skip "run 'npm install' in contracts/view-models/ to enable the TypeScript checks"
fi

step "8. View-model fixtures must be reproducible (no silent goalpost moves)"
if [ -d view-models/node_modules ] && command -v npm >/dev/null 2>&1; then
  tmp=$(mktemp -d)
  cp view-models/fixtures/viewmodel/*.json "$tmp"/ 2>/dev/null || true
  (cd view-models && npm run --silent fixtures:gen) >/dev/null 2>&1 || { echo "  FAIL generator errored"; rc=1; }
  # Compare file-by-file, never directory-to-directory: `diff` on two directories reports a
  # subdirectory as a difference, so `excerpts/` and `inline/` each turned this check red on a
  # clean tree. Anything that is not a top-level fixture is now inert here by construction.
  drift=0
  for f in view-models/fixtures/viewmodel/*.json; do
    b=$(basename "$f")
    if [ ! -f "$tmp/$b" ]; then echo "  FAIL new fixture not committed: $b"; drift=1; continue; fi
    if ! diff -q "$tmp/$b" "$f" >/dev/null 2>&1; then
      echo "  FAIL $b changed on regeneration"; diff "$tmp/$b" "$f" | head -8; drift=1
    fi
  done
  for f in "$tmp"/*.json; do
    b=$(basename "$f")
    [ -f "view-models/fixtures/viewmodel/$b" ] || { echo "  FAIL fixture disappeared: $b"; drift=1; }
  done
  if [ $drift -eq 0 ]; then
    echo "  PASS $(ls -1 view-models/fixtures/viewmodel/*.json | wc -l | tr -d ' ') fixtures regenerate identically"
  else
    # Restore, or the check that exists to stop silent goalpost moves moves them itself:
    # the regenerated files would sit on disk and the NEXT run would pass green.
    rm -f view-models/fixtures/viewmodel/*.json
    cp "$tmp"/*.json view-models/fixtures/viewmodel/ 2>/dev/null || true
    echo "  (committed fixtures restored; nothing was overwritten)"
    rc=1
  fi
  rm -rf "$tmp"
else
  skip "needs node_modules in contracts/view-models/"
fi

step "9. Derived samples must regenerate from the fixtures"
if [ -d view-models/node_modules ] && command -v npm >/dev/null 2>&1; then
  (cd view-models && npm run --silent excerpts:gen) >/dev/null 2>&1 \
    && echo "  PASS $(ls -1 view-models/fixtures/viewmodel/excerpts/*.json 2>/dev/null | wc -l | tr -d ' ') excerpts regenerate" \
    || { echo "  FAIL excerpt generator errored"; rc=1; }
  (cd view-models && npm run --silent inline:gen) >/dev/null 2>&1 \
    && echo "  PASS $(ls -1 view-models/fixtures/viewmodel/inline/*.md 2>/dev/null | wc -l | tr -d ' ') inline samples regenerate" \
    || { echo "  FAIL inline-sample generator errored"; rc=1; }
else
  skip "needs node_modules in contracts/view-models/"
fi

step "10. Event contract: fixtures valid, negatives rejected, topics derived"
python3 events/validate-events.py || rc=1

step "11. valueFrom bindings: gates 15-19 (UI-D28)"
python3 validation/check_value_from.py || rc=1

step "12. Connection type descriptors must validate (UI-D27)"
python3 connection-types/validate-descriptors.py || rc=1

# Steps 13-14b are from the integration/dp-remote-demo merge (2026-09-29): that lineage's own
# steps 11/12 collided by number with UI-D27/UI-D28's steps above, so these are renumbered next
# rather than overwriting them — same discipline as decision-ID prefixes (`I3`): a citation must
# never shift meaning.
step "13. Console OpenAPI: every operation maps to a ratified catalog verb and a grant scope"
python3 openapi/validate-openapi.py || rc=1

step "12. Published sm_control, sm_data and sm_metrics migrations are frozen (OR-22, OR-105 e): immutable anchors, plus a proposed-change check against the pre-change baseline"
# Anchor mode (frozen-migration-anchors.txt) is always-on and never skips: it is what survives a
# rewritten commit being pushed to a moving branch ref, which is exactly the gap this check used to
# have. Base mode additionally compares against the merge-base with what was last published, so a
# proposed change is checked against its own pre-change state even before anything reaches an
# anchor.
base_ref=""
if up="$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null)" \
   && [ -n "$up" ] && git rev-parse --verify --quiet "${up}^{commit}" >/dev/null 2>&1; then
  base_ref="$(git merge-base HEAD "$up" 2>/dev/null || true)"
elif git rev-parse --verify --quiet 'origin/main^{commit}' >/dev/null 2>&1; then
  base_ref="$(git merge-base HEAD origin/main 2>/dev/null || true)"
fi
if [ -n "$base_ref" ]; then
  python3 ../backend/scripts/check-frozen-migrations.py --base "$base_ref"
  step12_rc=$?
else
  python3 ../backend/scripts/check-frozen-migrations.py
  step12_rc=$?
  skip "no upstream or origin/main merge-base available — ran the immutable-anchor check only, not the proposed-change check"
fi
case $step12_rc in
  0) ;;
  2) skip "base commit unavailable for the proposed-change check" ;;
  *) rc=1 ;;
esac

step "14b. Self-test: prove the frozen-migration check catches the rewrite-then-push case (hermetic throwaway repo)"
bash ../backend/scripts/test-check-frozen-migrations.sh || { echo "  FAIL frozen-migration self-test failed"; rc=1; }

step "13. Dependency licence gate, build-plugin half (REQ-014 / I8): plugins and their dependencies, the proprietary block, the pinned plugin parameters, the three copies of the allow-list"
# The Maven half (license-maven-plugin, bound to verify) gates every module's own dependency tree. This is the half
# it cannot see, and no exec-capable plugin is available offline to bind it to Maven, so it is wired here, beside the
# sibling frozen-migration check. It needs the offline plugin realm: if `mvn` is absent the check did not run, and
# that is a skip (a failure under --strict), never a silent pass.
if command -v mvn >/dev/null 2>&1; then
  python3 ../backend/scripts/check-licences.py || rc=1
else
  skip "mvn is not on PATH, so backend/scripts/check-licences.py could not resolve the build-plugin realm"
fi

step "13b. Self-test: prove the licence check fires on each planted violation (hermetic: fake repository, throwaway backend directory)"
bash ../backend/scripts/test-check-licences.sh || { echo "  FAIL licence-check self-test failed"; rc=1; }

step "14. The schema must COMPILE under the frontend's own Ajv and options, and agree with the Python gate on every fixture and gate-1 negative (W3-04)"
# frontend/contracts/solution-package/validate.ts runs `ajv.compile(schema)` at module load under strict mode. A schema the
# Python and Java validators accept can still fail to compile there, and then importing the validator throws. This is the
# only check that loads the schema with Ajv. It reads the options FROM validate.ts, so it cannot drift from them. Ajv comes
# from frontend/node_modules, or from $SM_FRONTEND_NODE_MODULES; without it the check did not run (a failure under --strict).
if command -v node >/dev/null 2>&1; then
  node validation/check-frontend-ajv.mjs
  step14_rc=$?
  case $step14_rc in
    0) ;;
    3) skip "the frontend's Ajv is not installed (needs frontend/node_modules, or SM_FRONTEND_NODE_MODULES pointing at one)" ;;
    *) rc=1 ;;
  esac
else
  skip "node is not on PATH, so the frontend-Ajv compile check could not run"
fi

step "15. The generated Postman collection must match console.yaml and ENDPOINT-INVENTORY.md (no drift, each operation once, served markers agree)"
# Pure Python (PyYAML, already a requirement of the gates above). It regenerates into a temp directory and compares bytes,
# so a contract, inventory or @ConsoleOperation change that is not followed by generate-postman.py fails here.
if python3 openapi/postman/check-postman.py; then :; else rc=1; fi

step "16. The I8 licence gate is still present (OR-127): the plugin, its executions, the licence files and the proprietary block, with planted deletions (plan 015 T0.2 Step 1)"
# The agent branch was cut before the gate landed, so a merge or hand-port of it removes I8 enforcement silently.
bash ../backend/scripts/test-licence-gate-present.sh || { echo "  FAIL licence-gate-present check failed"; rc=1; }

step "17. The native protobuf executables are pinned (OR-127, P15-12): only the audited protoc and protoc-gen-grpc-java coordinates, with planted violations (plan 015 T0.2 Step 2)"
# protoc and protoc-gen-grpc-java arrive with an `exe` classifier at execution time: in neither the licence plugin's
# artifact set nor check-licences.py's plugin realm, so this is the only check that sees them.
python3 ../backend/scripts/check-protoc-executables.py || rc=1
python3 ../backend/scripts/check-protoc-executables.py --self-test || rc=1

step "18. The agent wire contract is append-only (AG-D13's exit condition, OR-126 A1): contracts/agent/v1/agent_gateway.proto against baseline.json, with planted violations (plan 015 T1.2)"
# The wire moved from go-agent/proto/ into contracts/ so that both generators (Go and Java) read one file. Pure Python,
# no buf needed: a renumbered, renamed, retyped or removed field, enum value or rpc fails; additions pass.
python3 agent/v1/check_proto_append_only.py || rc=1
python3 agent/v1/check_proto_append_only.py --self-test || rc=1
python3 agent/v1/check_proto_append_only.py --superset || rc=1   # baseline.json keeps every entry of the merge base's, or of every committed version when UAT has none; an unresolvable ref FAILS
# One wire, one file: a second copy of the agent .proto under go-agent/ (outside the retired oldGo-Agent/ tree) would let
# `make proto` or a stray buf run generate a second pb package from a stale draft (plan 015 W1 review, finding 1).
stray=$(find ../go-agent -name '*.proto' -not -path '../go-agent/oldGo-Agent/*' 2>/dev/null)
if [ -n "$stray" ]; then echo "FAIL: stray .proto under go-agent/ (the wire lives only in contracts/agent/v1):" >&2; echo "$stray" >&2; rc=1; else echo "ok: no .proto under go-agent/ outside oldGo-Agent/"; fi

if [ "$rc" -ne 0 ]; then
  verdict="FAILURES"
elif [ "$skipped" -gt 0 ]; then
  # Never claim "ALL CHECKS PASSED" when some did not run.
  verdict="PASSED, but $skipped CHECK(S) DID NOT RUN — re-run with --strict to treat that as failure"
else
  verdict="ALL CHECKS PASSED"
fi
printf '\n\033[1m== RESULT: %s\033[0m\n' "$verdict"
exit $rc

