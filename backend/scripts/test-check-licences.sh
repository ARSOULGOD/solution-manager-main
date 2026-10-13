#!/usr/bin/env bash
# Self-test for check-licences.py (REQ-014 / I8, plan 010 T0.4b).
#
# Hermetic: builds a throwaway backend directory and a fake local Maven repository under mktemp, replays each
# scenario the check exists to catch (planted violation -> exit 1 and the right message; then the unplanted
# fixture -> exit 0), and never touches the real tree, ~/.m2 or the network. A check that cannot fire is
# decoration (L7).
set -uo pipefail
SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND="$(cd "$SELF_DIR/.." && pwd)"
SCRIPT="$SELF_DIR/check-licences.py"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/check-licences-selftest.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
fails=0
ok()  { printf '  PASS %s\n' "$1"; }
bad() { printf '  FAIL %s\n' "$1"; fails=$((fails + 1)); }

B="$TMP/backend"; R="$TMP/repo"; REALM="$TMP/realm.txt"
fresh() {
  rm -rf "$B" "$R"; mkdir -p "$B" "$R"
  cp "$BACKEND/pom.xml" "$BACKEND/licence-allowlist.txt" "$BACKEND/licence-third-party.ftl" "$BACKEND/licence-artifact-filters.txt" "$B/"
  cat > "$B/licence-exceptions.txt" <<'X'
# fixture
org.junit.platform--junit-platform-engine--1.12.2=Owner-approved (test-only)
org.junit.platform--junit-platform-launcher--1.12.2=Owner-approved (test-only)
tool.x--approved--1.0=Owner-approved (build-tool-only)
tool.x--waived--1.0=Owner waiver (build-tool-only, no licence stated)
X
  pom tool.x approved 1.0 "No Licence Stated Anywhere"        # approved by exact line
  pom tool.x apache 1.0 "The Apache Software License, Version 2.0"
  pom tool.x mit 1.0 "MIT License"
  pom tool.x waived 1.0                                         # states no licence: the waiver case
  cat > "$REALM" <<'X'
[INFO]    tool.x:apache:maven-plugin:1.0
[INFO]       tool.x:approved:jar:1.0
[INFO]       tool.x:mit:jar:1.0
[INFO]       tool.x:waived:jar:1.0
X
}
pom() {  # pom <g> <a> <v> [licence name ...]   (no names -> no <licenses> element)
  local g="$1" a="$2" v="$3"; shift 3
  local d="$R/$(tr . / <<<"$g")/$a/$v"; mkdir -p "$d"
  { echo "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version>"
    if [ $# -gt 0 ]; then echo "<licenses>"; for n in "$@"; do echo "<license><name>$n</name></license>"; done; echo "</licenses>"; fi
    echo "</project>"; } > "$d/$a-$v.pom"
}
run() { OUT="$(python3 "$SCRIPT" --backend-dir "$B" --realm-file "$REALM" --repo "$R" 2>&1)"; RC=$?; }
expect_fail() {  # expect_fail <label> <message fragment>
  run; if [ $RC -eq 1 ] && grep -qF -- "$2" <<<"$OUT"; then ok "$1"; else bad "$1 (rc=$RC) :: $OUT"; fi
}
expect_ok() { run; if [ $RC -eq 0 ]; then ok "$1"; else bad "$1 (rc=$RC) :: $OUT"; fi; }

fresh;                                                                   expect_ok   "baseline fixture is green"
run; if grep -qF "tool.x:waived:1.0" <<<"$OUT"; then ok "a passing run names each owner waiver"; else bad "the OK line does not name the waiver :: $OUT"; fi

fresh; pom tool.x gpl 1.0 "GNU General Public License, Version 3"; echo "[INFO]       tool.x:gpl:jar:1.0" >> "$REALM"
expect_fail "off-list licence in the plugin realm fails" "tool.x:gpl:1.0"
fresh; pom tool.x none 1.0; echo "[INFO]       tool.x:none:jar:1.0" >> "$REALM"
expect_fail "a POM that declares no licence fails (unknown fails)" "declares NO licence"
fresh; sed -i.bak '/--waived--/d' "$B/licence-exceptions.txt"
expect_fail "removing a waiver names the missing waiver, not a licence" "Owner waiver missing"
fresh; pom tool.x waived 1.0 "MIT License"
expect_fail "a waiver on an artifact that now declares a licence fails" "holds an owner WAIVER"
fresh; sed -i.bak '/:waived:jar/d' "$REALM"
expect_fail "a waiver for an artifact that left the realm is stale" "an owner waiver that no longer resolves"
fresh; sed -i.bak 's/--approved--1.0=Owner-approved (build-tool-only)/--approved--1.0=Owner waiver (build-tool-only, no licence stated)/' "$B/licence-exceptions.txt"
expect_fail "a waiver cannot stand in for a licence approval on a licensed artifact" "holds an owner WAIVER"
fresh; pom tool.x odd 1.0 "Apache 3"; echo "[INFO]       tool.x:odd:jar:1.0" >> "$REALM"
expect_fail "an unmapped licence name fails" "not an exact mapped name"
fresh; pom tool.x near 1.0 "The Apache Software License, Version 2.0 "; echo "[INFO]       tool.x:near:jar:1.0" >> "$REALM"
expect_ok   "a name differing only by surrounding whitespace is the same name (the plugin trims too)"
fresh; pom tool.x near 1.0 "Apache License, Version 2.1"; echo "[INFO]       tool.x:near:jar:1.0" >> "$REALM"
expect_fail "a near-miss name (2.1 for 2.0) does not map" "tool.x:near:1.0"
fresh; pom tool.x dual 1.0 "MIT License" "GNU General Public License, Version 3"; echo "[INFO]       tool.x:dual:jar:1.0" >> "$REALM"
expect_fail "several licences, one off the list, no election: fails closed (Q-L2 a-prime)" "tool.x:dual:1.0"
fresh; pom tool.x dual 1.0 "MIT License" "Apache License 2.0"; echo "[INFO]       tool.x:dual:jar:1.0" >> "$REALM"
expect_ok   "several licences, all on the list: passes"
fresh; sed -i.bak 's/:approved:jar:1.0/:approved:jar:1.1/' "$REALM"; pom tool.x approved 1.1 "MIT License"
expect_fail "a version the exception line does not approve fails (even with an allow-listed licence)" "does not approve"
fresh; sed -i.bak '/approved/d' "$REALM"
expect_fail "a build-tool-only approval that left the realm is stale" "stale"
fresh; sed -i.bak 's/engine--1.12.2/engine--1.12.3/' "$B/licence-exceptions.txt"
expect_fail "junit-platform-launcher version must equal the engine's" "junit-platform-launcher approved versions"
fresh; echo 'tool.x--*--1.0=Owner-approved (build-tool-only)' >> "$B/licence-exceptions.txt"
expect_fail "a wildcard exception key is refused" "must be exact"
fresh; echo 'tool.x--approved--1.0=Owner-approved (build-tool-only)' >> "$B/licence-exceptions.txt"
expect_fail "a duplicate exception key is refused" "duplicate key"
fresh; echo 'tool.x--other--1.0=Something else' >> "$B/licence-exceptions.txt"
expect_fail "an unknown approval name is refused" "unknown name"
fresh; sed -i.bak 's/^Apache-2.0|/Apache-2.1|/' "$B/licence-allowlist.txt"
expect_fail "allow-list canonical names differ from REQ-014" "differ from REQ-014"
fresh; perl -pi -e 's/<includedLicense>ISC</<includedLicense>ISX</' "$B/pom.xml"
expect_fail "pom includedLicenses differ" "includedLicenses"
fresh; sed -i.bak 's/"ISC", //' "$B/licence-third-party.ftl"
expect_fail "template allowList differs" "allowList"
fresh; perl -0pi -e 's/<licenses>.*?<\/licenses>//s' "$B/pom.xml"
expect_fail "the root pom's proprietary licence block removed fails (OR-68)" "exactly one <license>"
fresh; perl -pi -e 's/Proprietary - Solace/Apache-2.0/' "$B/pom.xml"
expect_fail "the proprietary licence block with another name fails" "must be exactly"
fresh; perl -pi -e 's#(<name>Proprietary - Solace</name>)#$1<url>https://example.invalid/licence</url>#' "$B/pom.xml"
expect_fail "the proprietary licence block with an invented url fails" "no <url>"
fresh; perl -0pi -e 's/\n\s*<excludedGroups>[^<]*<\/excludedGroups>//' "$B/pom.xml"
expect_fail "an unpinned plugin parameter (excludedGroups) fails: a -Dlicense flag would beat it" "<excludedGroups> must be pinned"
fresh; perl -0pi -e 's/<includedTypes>[^<]*<\/includedTypes>/<includedTypes>jar<\/includedTypes>/' "$B/pom.xml"
expect_fail "a pinned parameter with another value fails" "<includedTypes> must be pinned"
fresh; perl -0pi -e 's/(<id>licence-gate-test<\/id>.*?<configuration>)/$1<excludedGroups>net.java<\/excludedGroups>/s' "$B/pom.xml"
expect_fail "an execution that sets a filter parameter fails" "could weaken a pinned"
fresh; echo 'exclude gaPattern net.java.dev.jna:jna' >> "$B/licence-artifact-filters.txt"
expect_fail "a filter line in licence-artifact-filters.txt fails" "must hold no filter line"
fresh; echo '# a comment | with a pipe' >> "$B/licence-allowlist.txt"
expect_fail "a # comment line with a pipe in the allow-list file fails (the plugin merges it)" "contains a pipe"
mkdir -p "$B/m"; printf '<project><dependencies><dependency><scope>system</scope></dependency></dependencies></project>' > "$B/m/pom.xml"
expect_fail "a system-scope dependency in a module pom is refused" "<scope>system</scope>"
fresh; mkdir -p "$B/m"; printf '<project><!-- <scope>system</scope> in a comment is fine --></project>' > "$B/m/pom.xml"
expect_ok   "a system-scope mention inside an XML comment is not a dependency"
fresh; : > "$REALM"
expect_fail "an empty plugin realm is an error, not a pass" "empty"
# namespace-less POM with a parent that carries the licence
fresh; mkdir -p "$R/p/q/par/1" "$R/p/q/kid/1"
printf '<project><modelVersion>4.0.0</modelVersion><groupId>p.q</groupId><artifactId>par</artifactId><version>1</version><licenses><license><name>MIT</name></license></licenses></project>' > "$R/p/q/par/1/par-1.pom"
printf '<project><modelVersion>4.0.0</modelVersion><parent><groupId>p.q</groupId><artifactId>par</artifactId><version>1</version></parent><artifactId>kid</artifactId></project>' > "$R/p/q/kid/1/kid-1.pom"
echo "[INFO]       p.q:kid:jar:1" >> "$REALM"
expect_ok   "a licence inherited from a namespace-less parent POM is read"

echo
if [ "$fails" -eq 0 ]; then echo "test-check-licences: all scenarios passed"; else echo "test-check-licences: $fails FAILED"; exit 1; fi
