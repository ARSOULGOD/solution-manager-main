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

step "2. Negative fixtures must ALL be rejected"
python3 validation/validate-package.py 'validation/negative/*.json' --expect-fail || rc=1

step "3. Overlapping appliesTo must be rejected (gate 3)"
if python3 validation/validate-package.py 'validation/fixtures/overlap/*.json' >/dev/null 2>&1; then
  echo "  FAIL overlap was accepted"; rc=1
else
  echo "  PASS (rejected as intended) overlapping appliesTo"
fi

step "4. File Mesh adapter must reproduce the fixture byte-for-byte"
python3 validation/adapters/filemesh-adapter.py > /tmp/fm-regen.$$ 2>/dev/null
if diff -q /tmp/fm-regen.$$ validation/fixtures/filemesh-ftp-solace.package.json >/dev/null; then
  echo "  PASS adapter output is reproducible"
else
  echo "  FAIL adapter output drifted from the committed fixture"; rc=1
fi
rm -f /tmp/fm-regen.$$

step "5. File Mesh coverage report"
python3 validation/adapters/filemesh-adapter.py --report | head -9

step "6. Evidence extraction must report zero parse failures"
python3 ../docs/design/dynamic-ui/evidence/extract-fm-vocabulary.py 2>/dev/null \
  | sed -n '1,4p;/GRAMMAR/,+1p' | sed 's/^/  /'
python3 ../docs/design/dynamic-ui/evidence/extract-fm-vocabulary.py 2>/dev/null \
  | grep -q "unparsed rows (must be 0)           : 0" || { echo "  FAIL unparsed rows"; rc=1; }

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

step "11. Console OpenAPI: every operation maps to a ratified catalog verb and a grant scope"
python3 openapi/validate-openapi.py || rc=1

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
