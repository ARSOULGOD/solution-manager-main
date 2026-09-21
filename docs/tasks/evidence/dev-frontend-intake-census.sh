#!/usr/bin/env bash
# Census for docs/tasks/dev-frontend-intake.md.
#
# L12: every figure quoted in the intake report is derived here and its output
# committed beside it. This script reports its own failures — any line reading
# "CHECK FAILED" means a figure in the report is unsupported and the report is
# wrong until it is re-run.
#
# Usage:  docs/tasks/evidence/dev-frontend-intake-census.sh > docs/tasks/evidence/dev-frontend-intake-census.txt
#
# Pinned inputs. Re-pin deliberately; a moved ref silently changes every figure.
BASE=9c0d06e7c52e0a565a7cd0e3c658361cd925c30d   # merge base
OURS=85170b4                                    # backend baseline (Merge agent gateway design)
THEIRS=origin/dev/frontend                      # frontend source branch

set -u
fail=0
# Strips trailing whitespace: `cut` truncation of a commit subject otherwise leaves it,
# and `git diff --check` rejects the committed output.
note() { printf '%s\n' "$*" | sed 's/[[:space:]]*$//'; }
# check <label> <ok>  — ok=1 passes, ok=0 prints CHECK FAILED and bumps `fail`.
# The label states what is WRONG when it fires, so a failure reads as a finding.
check() {
  if [ "$2" != "1" ]; then note "  CHECK FAILED: $1"; fail=$((fail+1)); fi
}

note "dev/frontend intake census"
note "=========================="
note "generated : $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
note "base      : $BASE  $(git log -1 --format='%s' $BASE 2>/dev/null | cut -c1-60)"
note "ours      : $(git rev-parse --short $OURS)  $(git log -1 --format='%s' $OURS | cut -c1-60)"
note "theirs    : $(git rev-parse --short $THEIRS)  $(git log -1 --format='%s' $THEIRS | cut -c1-60)"
note ""

# Every ref must resolve, or all figures below are meaningless.
for r in "$BASE" "$OURS" "$THEIRS"; do
  if git rev-parse --verify --quiet "$r^{commit}" >/dev/null; then ok=1; else ok=0; fi
  check "ref $r does not resolve" "$ok"
done
# The stated merge base must actually be the merge base.
actual_mb=$(git merge-base "$OURS" "$THEIRS")
if [ "$actual_mb" = "$BASE" ]; then ok=1; else ok=0; fi
check "merge-base($OURS,$THEIRS) is $actual_mb, not the pinned $BASE" "$ok"
note ""

note "1. Branch size"
F_commits=$(git rev-list --count $BASE..$THEIRS)
note "  commits on theirs not in base : $F_commits"
note "  files changed, theirs vs base : $(git diff --name-only $BASE $THEIRS | wc -l | tr -d ' ')"
note ""

note "2. Merge hazard under backend/ (why a direct merge is refused)"
tmp=$(mktemp -d)
git diff --name-status $BASE $THEIRS -- backend | awk '$1=="D"{print $2}' | sort > "$tmp/theirs_deleted"
git diff --name-only  $BASE $OURS   -- backend | sort > "$tmp/ours_changed"
comm -23 "$tmp/theirs_deleted" "$tmp/ours_changed" > "$tmp/silent"
comm -12 "$tmp/theirs_deleted" "$tmp/ours_changed" > "$tmp/conflict"
F_present=$(git ls-tree -r --name-only $OURS -- backend | wc -l | tr -d ' ')
F_deleted=$(wc -l < "$tmp/theirs_deleted" | tr -d ' ')
F_silent=$(wc -l < "$tmp/silent" | tr -d ' ')
F_conflict=$(wc -l < "$tmp/conflict" | tr -d ' ')
note "  backend files present at ours          : $F_present"
note "  backend files deleted by theirs vs base: $F_deleted"
note "  -> SILENTLY deleted by a merge         : $F_silent   (deleted theirs, untouched ours: git takes the delete, no conflict)"
note "  -> raised as modify/delete CONFLICT    : $F_conflict"
note "  the conflicting files:"
sed 's/^/    /' "$tmp/conflict"
note "  silently-deleted files by module:"
sed 's|^backend/||; s|/.*||' "$tmp/silent" | sort | uniq -c | sed 's/^/    /'
# The reactor root POM is the figure that makes the hazard concrete.
if grep -qx 'backend/pom.xml' "$tmp/silent"; then ok=1; else ok=0; fi
check "backend/pom.xml is no longer in the silent-delete set" "$ok"
note ""

note "3. Provenance of the deletion — an evil merge, not a later commit"
note "  commits on theirs touching backend/pom.xml (full history):"
git log --format='    %h %s' --full-history $BASE..$THEIRS -- backend/pom.xml
for p in $(git log -1 --format='%P' 0bea7a2 2>/dev/null); do
  n=$(git ls-tree --name-only "$p" -- backend/pom.xml | grep -c . )
  note "    parent $(git rev-parse --short $p) has backend/pom.xml: $n   ($(git log -1 --format='%s' $p | cut -c1-45))"
done
note ""

note "4. Decision-ID collisions"
note "  max BE-D at the merge base : $(git show $BASE:docs/design/backend/decisions.md | grep -oE '^## .BE-D[0-9]+.' | tail -1)"
note "  ours   BE-D12 : $(grep -m1 '^## .BE-D12.' docs/design/backend/decisions.md | cut -c1-96)"
note "  theirs BE-D12 : $(git show $THEIRS:docs/design/backend/decisions.md | grep -m1 '^## .BE-D12.' | cut -c1-96)"
ours12=$(grep -m1 '^## .BE-D12.' docs/design/backend/decisions.md)
theirs12=$(git show $THEIRS:docs/design/backend/decisions.md | grep -m1 '^## .BE-D12.')
if [ "$ours12" != "$theirs12" ]; then ok=1; else ok=0; fi
check "BE-D12 titles are identical — the collision claim is wrong" "$ok"
# Citation load on the baseline's BE-D12, excluding its own defining file.
# Measured against the COMMIT $OURS, never the worktree. A worktree measure counts the
# report that quotes it, so the figure grows every time the report mentions it — the
# measure would be self-referential and the quoted number stale on the next edit.
# `git grep` also ignores the operator's `grep` alias, which a `grep -r` here did not.
cite_files=$(git grep -l  'BE-D12' $OURS -- '*.md' '*.java' '*.sql' '*.json' \
        | grep -v 'docs/design/backend/decisions.md$' | wc -l | tr -d ' ')
cite_lines=$(git grep -h  'BE-D12' $OURS -- '*.md' '*.java' '*.sql' '*.json' \
        ':(exclude)docs/design/backend/decisions.md' | wc -l | tr -d ' ')
note "  ours' BE-D12 is cited in $cite_files files / $cite_lines lines at $OURS (excl. its own decisions.md)"
note "    (two measures because they differ: one file may cite it many times. Neither is 'the' count.)"
# Self-reference guard: the documents that QUOTE these figures must not be inside the
# measured set, or the number changes every time the report is edited. This is the check
# whose absence let two stale figures ship in the first review round.

# FE-D: the families diverge at an id, not merely at their maxima. Compare titles id-by-id.
note "  FE-D ids whose TITLE differs between ours and theirs (a citation means two things):"
git show $OURS:docs/design/frontend/decisions.md | grep -oE '^## .FE-D[0-9]+. — .*' | sort > "$tmp/fe_ours"
git show $THEIRS:docs/design/frontend/decisions.md | grep -oE '^## .FE-D[0-9]+. — .*' | sort > "$tmp/fe_theirs"
divergent=0
while IFS= read -r line; do
  id=$(printf '%s' "$line" | grep -oE 'FE-D[0-9]+')
  mine=$(grep -m1 "\`$id\` —" "$tmp/fe_ours"   | sed 's/^## //')
  been=$(grep -m1 "\`$id\` —" "$tmp/fe_theirs" | sed 's/^## //')
  if [ -n "$mine" ] && [ -n "$been" ] && [ "$mine" != "$been" ]; then
    note "    $id"; note "      ours  : $mine"; note "      theirs: $been"
    divergent=$((divergent+1))
  fi
done < "$tmp/fe_ours"
note "  divergent FE-D ids: $divergent"

note "  BE-D ids defined on theirs (the collision surface is only as wide as this):"
note "    $(git show $THEIRS:docs/design/backend/decisions.md | grep -oE '^## .BE-D[0-9]+.' | sed 's/^## //' | tr '\n' ' ')"
note ""
note "  new parent-family D* ids on theirs (family is closed at D17 on ours):"
git show $THEIRS:docs/design/DECISIONS.md | grep -oE '^\| \*\*D(1[89]|20)\*\*' | sort -u | sed 's/^/    /'
note "  (maxima only — see the id-by-id compare above for what actually collides)"
note "  ours' highest FE-D  : $(grep -oE '^## .FE-D[0-9]+.' docs/design/frontend/decisions.md | tail -1)"
note "  theirs' highest FE-D: $(git show $THEIRS:docs/design/frontend/decisions.md | grep -oE '^## .FE-D[0-9]+.' | tail -1)"
note ""

note "5. Contract (wire) changes — contracts/ is single-writer"
F_contracts=$(git diff --name-only $BASE $THEIRS -- contracts | wc -l | tr -d ' ')
note "  contracts/ files changed, theirs vs base: $F_contracts"
note "  envelope required-key rename:"
git diff $BASE $THEIRS -- contracts/events/envelope.schema.json | grep -E '^[+-] +"(required|tenant|organization)' | sed 's/^/    /'
note "  asyncapi topic parameter rename (address lines):"
git diff $BASE $THEIRS -- contracts/events/asyncapi.yaml | grep -E '^[+-] +address:' | sed 's/^/    /'
note "  backend files naming the envelope 'tenant' accessor/field:"
grep -rl 'tenant' backend/mesh-events/src/main/java backend/mesh-events/src/test/java 2>/dev/null | sed 's/^/    /'
note ""

note "6. Reference-path rename (breaks existing citations)"
note "  theirs moved docs/design/frontend/reference/sm-v2/* up one level. Citations on ours:"
# Measured at $OURS, not the worktree — see the self-reference note in section 4.
F_smv2_files=$(git grep -l 'sm-v2/' $OURS -- 'docs/**.md' | wc -l | tr -d ' ')
F_smv2_lines=$(git grep -h 'sm-v2/' $OURS -- 'docs/**.md' | wc -l | tr -d ' ')
note "  .md files on ours citing 'sm-v2/' : $F_smv2_files"
note "  total citing lines                : $F_smv2_lines"
git grep -l 'sm-v2/' $OURS -- 'docs/**.md' | sed "s|^$OURS:|    |"
# The INCOMING side matters too: theirs' frontend sources carry flattened reference paths
# that are wrong on this layout. A re-pointing task scoped only to docs/ would miss them.
note "  theirs' frontend source files carrying flattened 'reference/app/' paths:"
note "    files: $(git grep -l 'reference/app/' $THEIRS -- 'frontend/**' | wc -l | tr -d ' ')   lines: $(git grep -h 'reference/app/' $THEIRS -- 'frontend/**' | wc -l | tr -d ' ')"
note ""

note "7. The report must quote these figures, not stale ones"
# This is the check whose absence let two stale numbers ship in review round 1. It reads the
# document that quotes this script and fails if a figure there no longer matches. It is the
# only check here that can catch a divergence between evidence and prose.
REPORT=docs/tasks/dev-frontend-intake.md
if [ ! -f "$REPORT" ]; then
  note "  CHECK FAILED: $REPORT not found — the figures below are quoted nowhere"; fail=$((fail+1))
else
  quoted() { # quoted <label> <string-that-must-appear>
    if grep -qF -- "$2" "$REPORT"; then note "  ok   $1 — \"$2\""
    else note "  CHECK FAILED: $1 — $REPORT does not contain \"$2\""; fail=$((fail+1)); fi
  }
  quoted "commits"            "**$F_commits**"
  quoted "backend present"    "**$F_present**"
  quoted "backend deleted"    "**$F_deleted**"
  quoted "silently deleted"   "**$F_silent**"
  quoted "merge conflicts"    "**$F_conflict**"
  quoted "contracts changed"  "$F_contracts files"
  quoted "BE-D12 citations"   "$cite_files files / $cite_lines lines"
  quoted "sm-v2 citations"    "$F_smv2_lines citing lines across $F_smv2_files"
  quoted "divergent FE-D"     "FE-D25"
fi
note ""

note "=========================="
if [ "$fail" -eq 0 ]; then
  note "CHECKS PASSED (0 failed). Every figure above is supported."
else
  note "CHECKS FAILED: $fail. Figures above are NOT usable until resolved."
fi
rm -rf "$tmp"
exit $fail
