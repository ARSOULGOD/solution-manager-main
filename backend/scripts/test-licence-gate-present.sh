#!/usr/bin/env bash
# The I8 licence gate must still be PRESENT (REQ-014 / I8, OR-127, plan 015 T0.2 Step 1).
#
# Why: origin/dp-agent-integration was cut before the licence gate landed, so its tree has no licence gate at all.
# A merge or a hand-port of that branch removes enforcement of I8 without a conflict and without a red build
# (measured in AGW-LICENCE-AUDIT-2026-10-08, headline 1). OR-127 says our gate stays and is never ported over.
# This check makes "the gate is still there" an executable fact instead of a review habit. It does NOT re-prove
# that the gate is correct: scripts/check-licences.py and scripts/test-check-licences.sh do that, and this script
# runs the latter.
#
# It fails unless, in backend/:
#   - pom.xml declares org.codehaus.mojo:license-maven-plugin in <build><plugins> with BOTH executions
#     (licence-gate-shipped, licence-gate-test), each bound to phase `verify` with goal `add-third-party`;
#   - pom.xml carries the explicit `Proprietary - Solace` <license> block;
#   - licence-allowlist.txt, licence-exceptions.txt, licence-artifact-filters.txt, licence-third-party.ftl,
#     scripts/check-licences.py and scripts/test-check-licences.sh exist and are non-empty;
#   - scripts/test-check-licences.sh passes.
# Then it plants each deletion on a scratch copy and proves THIS check reports it (L7: a check that cannot fire is
# decoration).
#
# Usage: test-licence-gate-present.sh              check backend/, then run the planted proofs
#        test-licence-gate-present.sh --backend DIR [--no-selftest]   check DIR only (used by the planted proofs)
set -uo pipefail
SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
REAL_BACKEND="$(cd "$SELF_DIR/.." && pwd)"

backend=""; selftest=1; proofs=1
while [ $# -gt 0 ]; do
  case "$1" in
    --backend) backend="$2"; proofs=0; shift 2 ;;
    --no-selftest) selftest=0; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$backend" ] || backend="$REAL_BACKEND"

# check_gate <backend dir> <run the licence self-test: 1|0>  -> prints problems, returns 0 when none
check_gate() {
  local b="$1" run_selftest="$2" rc=0
  for f in licence-allowlist.txt licence-exceptions.txt licence-artifact-filters.txt licence-third-party.ftl \
           scripts/check-licences.py scripts/test-check-licences.sh; do
    if [ ! -s "$b/$f" ]; then echo "  - missing or empty: backend/$f"; rc=1; fi
  done
  if [ ! -f "$b/pom.xml" ]; then echo "  - missing: backend/pom.xml"; return 1; fi
  python3 - "$b/pom.xml" <<'PY' || rc=1
import sys
import xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
try:
    root = ET.parse(sys.argv[1]).getroot()
except ET.ParseError as e:
    print(f"  - backend/pom.xml is not parseable XML: {e}"); sys.exit(1)
bad = []
plugs = [p for p in root.findall("m:build/m:plugins/m:plugin", ns)
         if p.findtext("m:groupId", "", ns) == "org.codehaus.mojo"
         and p.findtext("m:artifactId", "", ns) == "license-maven-plugin"]
if not plugs:
    bad.append("pom.xml does not declare org.codehaus.mojo:license-maven-plugin in <build><plugins>")
else:
    seen = {}
    for ex in plugs[0].findall("m:executions/m:execution", ns):
        goals = [g.text for g in ex.findall("m:goals/m:goal", ns)]
        seen[ex.findtext("m:id", "", ns)] = (ex.findtext("m:phase", "", ns), goals)
    for eid in ("licence-gate-shipped", "licence-gate-test"):
        ph, goals = seen.get(eid, (None, []))
        if ph is None:
            bad.append(f"license-maven-plugin has no execution {eid!r}")
        elif ph != "verify" or "add-third-party" not in goals:
            bad.append(f"execution {eid!r} is bound to phase {ph!r} goals {goals}, not verify/add-third-party")
names = [l.findtext("m:name", "", ns) for l in root.findall("m:licenses/m:license", ns)]
if "Proprietary - Solace" not in names:
    bad.append("pom.xml lost the explicit 'Proprietary - Solace' <license> block")
for b in bad:
    print("  - " + b)
sys.exit(1 if bad else 0)
PY
  if [ "$run_selftest" = "1" ] && [ -s "$b/scripts/test-check-licences.sh" ]; then
    if ! out="$(bash "$b/scripts/test-check-licences.sh" 2>&1)"; then
      echo "  - scripts/test-check-licences.sh failed:"; printf '%s\n' "$out" | tail -8 | sed 's/^/      /'; rc=1
    fi
  fi
  return $rc
}

if [ "$proofs" = "0" ]; then
  check_gate "$backend" "$selftest"; exit $?
fi

fails=0
ok()  { printf '  PASS %s\n' "$1"; }
bad() { printf '  FAIL %s\n' "$1"; fails=$((fails + 1)); }

echo "licence gate present, untouched tree:"
if out="$(check_gate "$REAL_BACKEND" 1)"; then ok "the I8 gate is present and its self-test passes"
else bad "the untouched tree failed"; printf '%s\n' "$out"; fi

TMP="$(mktemp -d "${TMPDIR:-/tmp}/licence-gate-present.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
fresh() {  # a scratch copy of the files the check reads
  rm -rf "$TMP/b"; mkdir -p "$TMP/b/scripts"
  cp "$REAL_BACKEND"/pom.xml "$REAL_BACKEND"/licence-allowlist.txt "$REAL_BACKEND"/licence-exceptions.txt \
     "$REAL_BACKEND"/licence-artifact-filters.txt "$REAL_BACKEND"/licence-third-party.ftl "$TMP/b/"
  cp "$REAL_BACKEND"/scripts/check-licences.py "$REAL_BACKEND"/scripts/test-check-licences.sh "$TMP/b/scripts/"
}
mutate_pom() {  # mutate_pom <python statements acting on `root`, `ET`, `ns`>
  python3 - "$TMP/b/pom.xml" "$1" <<'PY'
import sys
import xml.etree.ElementTree as ET
ET.register_namespace("", "http://maven.apache.org/POM/4.0.0")
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
tree = ET.parse(sys.argv[1]); root = tree.getroot()
exec(sys.argv[2])
tree.write(sys.argv[1])
PY
}
expect_reported() {  # expect_reported <label> <substring>
  if out="$(check_gate "$TMP/b" 0)"; then bad "planted: $1 -- NOT reported"
  elif printf '%s' "$out" | grep -qF -- "$2"; then ok "planted: $1 -- reported"
  else bad "planted: $1 -- failed, but not with the expected message ($2)"; printf '%s\n' "$out"; fi
}

echo "planted deletions (each on a scratch copy; the untouched copy must pass first):"
fresh
if out="$(check_gate "$TMP/b" 0)"; then ok "the scratch copy passes before any mutation"
else bad "the scratch copy fails before any mutation"; printf '%s\n' "$out"; fi

fresh; mutate_pom '
b = root.find("m:build/m:plugins", ns)
for p in list(b):
    if p.findtext("m:artifactId", "", ns) == "license-maven-plugin": b.remove(p)'
expect_reported "the license-maven-plugin block deleted" "does not declare org.codehaus.mojo:license-maven-plugin"

fresh; mutate_pom '
for e in root.iter("{http://maven.apache.org/POM/4.0.0}phase"):
    if e.text == "verify": e.text = "none"'
expect_reported "both executions unbound from verify" "not verify/add-third-party"

fresh; mutate_pom '
for ex in root.iter("{http://maven.apache.org/POM/4.0.0}execution"):
    if ex.findtext("m:id", "", ns) == "licence-gate-test":
        ex.find("m:id", ns).text = "renamed"'
expect_reported "the test-scope execution renamed away" "no execution 'licence-gate-test'"

fresh; mutate_pom '
l = root.find("m:licenses", ns); root.remove(l)'
expect_reported "the Proprietary - Solace licence block deleted" "Proprietary - Solace"

for f in licence-allowlist.txt licence-exceptions.txt licence-artifact-filters.txt licence-third-party.ftl \
         scripts/check-licences.py scripts/test-check-licences.sh; do
  fresh; rm "$TMP/b/$f"
  expect_reported "backend/$f deleted" "missing or empty: backend/$f"
done

fresh; : > "$TMP/b/licence-allowlist.txt"
expect_reported "licence-allowlist.txt emptied" "missing or empty: backend/licence-allowlist.txt"

# the self-test is part of the gate: a scratch copy whose test script fails must fail the check
fresh; printf '#!/usr/bin/env bash\nexit 1\n' > "$TMP/b/scripts/test-check-licences.sh"
if out="$(check_gate "$TMP/b" 1)"; then bad "planted: a failing scripts/test-check-licences.sh -- NOT reported"
elif printf '%s' "$out" | grep -qF "test-check-licences.sh failed"; then ok "planted: a failing scripts/test-check-licences.sh -- reported"
else bad "planted: a failing self-test -- failed with the wrong message"; printf '%s\n' "$out"; fi

echo
if [ "$fails" -eq 0 ]; then echo "test-licence-gate-present: all scenarios passed"; exit 0; fi
echo "test-licence-gate-present: $fails scenario(s) FAILED"; exit 1
