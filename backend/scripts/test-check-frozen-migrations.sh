#!/usr/bin/env bash
# Self-test for check-frozen-migrations.py and .githooks/pre-push (OR-22).
#
# Builds a HERMETIC, throwaway git repo under mktemp — never touches this repository — replays the
# scenarios the frozen-migration check exists to catch, and asserts on exit codes and key message
# text. Wired into contracts/run-checks.sh as step 12b: a check that can silently regress is not a
# check (L7).
set -uo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SELF_DIR/../.." && pwd)"
SCRIPT="$SELF_DIR/check-frozen-migrations.py"
HOOK="$REPO_ROOT/.githooks/pre-push"
REL="backend/cp-core/src/main/resources/db/migration/sm_control"
RELD="backend/dp-core/src/main/resources/db/migration/sm_data"
RELM="backend/dp-core/src/main/resources/db/migration/sm_metrics"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/frozen-migration-selftest.XXXXXX")"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

fails=0
ok()  { printf '  PASS %s\n' "$1"; }
bad() { printf '  FAIL %s\n' "$1"; fails=$((fails + 1)); }

git_tmp() { git -C "$TMP" "$@"; }

run() {  # run <head> [--base <base>] -> sets OUT, RC
  local head="$1"; shift
  OUT="$(cd "$TMP" && python3 backend/scripts/check-frozen-migrations.py --head "$head" "$@" 2>&1)"
  RC=$?
}

hook_run() {  # hook_run "<local ref> <local sha> <remote ref> <remote sha>" -> sets RC
  ( cd "$TMP" && ./.githooks/pre-push origin https://example.invalid/repo.git ) <<<"$1"
  RC=$?
}

# --- build the hermetic repo -------------------------------------------------------------------
git_tmp init -q
git_tmp config user.email "selftest@example.invalid"
git_tmp config user.name "selftest"
git_tmp config commit.gpgsign false

mkdir -p "$TMP/backend/scripts" "$TMP/$REL" "$TMP/$RELD" "$TMP/$RELM"
cp "$SCRIPT" "$TMP/backend/scripts/check-frozen-migrations.py"

printf -- '-- V001 initial\nCREATE TABLE x (id int);\n' > "$TMP/$REL/V001__scope.sql"
printf -- '-- V002 initial\nCREATE TABLE y (id int);\n' > "$TMP/$REL/V002__identity.sql"
( cd "$TMP/$REL" && shasum -a 256 V001__scope.sql V002__identity.sql ) \
  | awk '{print $1"  "$2}' > "$TMP/$REL/CHECKSUMS.sha256"

# OR-105 (e): the same freeze covers sm_data and sm_metrics.
printf -- '-- data V001 initial\nCREATE TABLE d1 (id int);\n' > "$TMP/$RELD/V001__data_one.sql"
printf -- '-- data V002 initial\nCREATE TABLE d2 (id int);\n' > "$TMP/$RELD/V002__data_two.sql"
( cd "$TMP/$RELD" && shasum -a 256 V001__data_one.sql V002__data_two.sql ) \
  | awk '{print $1"  "$2}' > "$TMP/$RELD/CHECKSUMS.sha256"
printf -- '-- metrics V001 initial\nCREATE TABLE m1 (id int);\n' > "$TMP/$RELM/V001__metrics_one.sql"
( cd "$TMP/$RELM" && shasum -a 256 V001__metrics_one.sql ) \
  | awk '{print $1"  "$2}' > "$TMP/$RELM/CHECKSUMS.sha256"

git_tmp add -A
git_tmp commit -q -m "A: add V001, V002"
A="$(git_tmp rev-parse HEAD)"
# The real checker carries 245d763 as its built-in genesis anchor, which this throwaway repo does not
# contain; A plays that role here, exactly as 245d763 does in the real repository.
export SM_FROZEN_GENESIS_ANCHORS="$A"

printf '# frozen-migration-anchors.txt (selftest)\n%s  # initial anchor\n' "$A" \
  > "$TMP/backend/scripts/frozen-migration-anchors.txt"
git_tmp add -A
git_tmp commit -q -m "B: add anchors file naming A"
B="$(git_tmp rev-parse HEAD)"

# --- (a) rewrite V001 AND its manifest line together --------------------------------------------
git_tmp checkout -q -b case-a "$B"
printf -- '-- V001 initial (REWRITTEN)\nCREATE TABLE x (id int);\n' > "$TMP/$REL/V001__scope.sql"
( cd "$TMP/$REL" && shasum -a 256 V001__scope.sql V002__identity.sql ) \
  | awk '{print $1"  "$2}' > "$TMP/$REL/CHECKSUMS.sha256"
git_tmp add -A
git_tmp commit -q -m "C1: rewrite V001 + its manifest line"
C1="$(git_tmp rev-parse HEAD)"

run "$C1"
if [ "$RC" -ne 0 ] && printf '%s' "$OUT" | grep -qi "V001__scope.sql"; then
  ok "(a) anchor mode FAILS on a rewritten V001 (rc=$RC)"
else
  bad "(a) anchor mode should FAIL on a rewritten V001 (rc=$RC): $OUT"
fi

run "$C1" --base "$A"
if [ "$RC" -ne 0 ] && printf '%s' "$OUT" | grep -qi "base mode"; then
  ok "(a) base mode vs A FAILS on a rewritten V001 (rc=$RC)"
else
  bad "(a) base mode vs A should FAIL on a rewritten V001 (rc=$RC): $OUT"
fi

# --- (b) delete V002 (+ its manifest line) -------------------------------------------------------
git_tmp checkout -q -b case-b "$B"
rm "$TMP/$REL/V002__identity.sql"
grep -v "V002__identity.sql" "$TMP/$REL/CHECKSUMS.sha256" > "$TMP/$REL/CHECKSUMS.sha256.new"
mv "$TMP/$REL/CHECKSUMS.sha256.new" "$TMP/$REL/CHECKSUMS.sha256"
git_tmp add -A
git_tmp commit -q -m "C2: delete V002 + its manifest line"
C2="$(git_tmp rev-parse HEAD)"

run "$C2"
if [ "$RC" -ne 0 ] && printf '%s' "$OUT" | grep -qi "DELETED"; then
  ok "(b) anchor mode FAILS on a deleted V002 (rc=$RC)"
else
  bad "(b) anchor mode should FAIL on a deleted V002 (rc=$RC): $OUT"
fi

# --- (c) add V003 (+ its manifest line) — allowed, and NOTEd as unanchored -----------------------
git_tmp checkout -q -b case-c "$B"
printf -- '-- V003 new\nCREATE TABLE z (id int);\n' > "$TMP/$REL/V003__widget.sql"
( cd "$TMP/$REL" && shasum -a 256 V003__widget.sql ) | awk '{print $1"  "$2}' >> "$TMP/$REL/CHECKSUMS.sha256"
git_tmp add -A
git_tmp commit -q -m "C3: add V003 + its manifest line"
C3="$(git_tmp rev-parse HEAD)"

run "$C3"
if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q "NOTE" && printf '%s' "$OUT" | grep -q "V003__widget.sql"; then
  ok "(c) anchor mode PASSES with a new V003, and NOTEs it as unanchored (rc=$RC)"
else
  bad "(c) anchor mode should PASS and NOTE V003 as unanchored (rc=$RC): $OUT"
fi

# --- (d) remove the anchor line — base mode must catch the "unfreeze by editing the file" attack -
git_tmp checkout -q -b case-d "$B"
printf '# the anchor line naming A has been removed\n' > "$TMP/backend/scripts/frozen-migration-anchors.txt"
git_tmp add -A
git_tmp commit -q -m "C4: remove the anchor line"
C4="$(git_tmp rev-parse HEAD)"

run "$C4" --base "$B"
if [ "$RC" -ne 0 ] && printf '%s' "$OUT" | grep -qi "append-only"; then
  ok "(d) base mode vs B FAILS when the anchor line is removed (rc=$RC)"
else
  bad "(d) base mode vs B should FAIL when the anchor line is removed (rc=$RC): $OUT"
fi

# --- (e) THE FINDING: base = the rewritten commit itself, simulating "after the push" ------------
# Base mode alone (comparing C1 with itself) must report no base-mode failure — reproducing the old
# bug exactly — while anchor mode, run in the same invocation, still catches the rewrite. This is
# why anchor mode exists: a moving-baseline check alone would have gone green here.
run "$C1" --base "$C1"
if [ "$RC" -ne 0 ] \
   && printf '%s' "$OUT" | grep -qi "V001__scope.sql" \
   && ! printf '%s' "$OUT" | grep -qi "(base mode) FAIL"; then
  ok "(e) base(C1,C1) reports zero base-mode failures, but anchor mode alone still catches it (rc=$RC)"
else
  bad "(e) expected zero '(base mode) FAIL' lines but a nonzero overall rc from anchor mode (rc=$RC): $OUT"
fi

# --- (f) an anchor SHA that does not exist locally must FAIL, not skip, and name the fetch command
git_tmp checkout -q -b case-f "$B"
FAKE="abcdef0123456789abcdef0123456789abcdef01"
printf '%s  # this commit does not exist locally\n' "$FAKE" >> "$TMP/backend/scripts/frozen-migration-anchors.txt"
git_tmp add -A
git_tmp commit -q -m "C5: reference a missing anchor commit"
C5="$(git_tmp rev-parse HEAD)"

run "$C5"
if [ "$RC" -ne 0 ] && printf '%s' "$OUT" | grep -qi "fetch"; then
  ok "(f) anchor mode FAILS (not skips) when an anchor commit is missing locally, and names the fetch command (rc=$RC)"
else
  bad "(f) anchor mode should FAIL with a fetch hint when an anchor commit is missing (rc=$RC): $OUT"
fi

# --- (h) deleting the anchors file does not unfreeze the genesis anchor ---------------------------
git_tmp checkout -q -b case-h "$B"
printf -- '-- V001 REWRITTEN (h)\nCREATE TABLE x (id bigint);\n' > "$TMP/$REL/V001__scope.sql"
( cd "$TMP/$REL" && shasum -a 256 V001__scope.sql V002__identity.sql ) \
  | awk '{print $1"  "$2}' > "$TMP/$REL/CHECKSUMS.sha256"
git_tmp rm -q backend/scripts/frozen-migration-anchors.txt
git_tmp add -A
git_tmp commit -q -m "H: rewrite V001 and delete the anchors file"
H="$(git_tmp rev-parse HEAD)"
run "$H"
if [ "$RC" -eq 1 ] && grep -q "V001__scope.sql" <<<"$OUT"; then
  ok "(h) anchor mode with the anchors file deleted still fails the V001 rewrite via the genesis anchor"
else
  bad "(h) deleting the anchors file must not unfreeze V001 (rc=$RC): $OUT"
fi

# --- (i) OR-105 (e): rewrite a published sm_data migration AND its manifest line ------------------
git_tmp checkout -q -b case-i "$B"
printf -- '-- data V001 REWRITTEN\nCREATE TABLE d1 (id bigint);\n' > "$TMP/$RELD/V001__data_one.sql"
( cd "$TMP/$RELD" && shasum -a 256 V001__data_one.sql V002__data_two.sql ) \
  | awk '{print $1"  "$2}' > "$TMP/$RELD/CHECKSUMS.sha256"
git_tmp add -A
git_tmp commit -q -m "I: rewrite sm_data V001 + its manifest line"
I="$(git_tmp rev-parse HEAD)"

run "$I"
if [ "$RC" -eq 1 ] && printf '%s' "$OUT" | grep -q "sm_data/V001__data_one.sql" \
   && ! printf '%s' "$OUT" | grep -q "sm_control/V00"; then
  ok "(i) anchor mode FAILS on a rewritten sm_data V001, naming sm_data (rc=$RC)"
else
  bad "(i) anchor mode should FAIL on a rewritten sm_data V001 (rc=$RC): $OUT"
fi

run "$I" --base "$B"
if [ "$RC" -eq 1 ] && printf '%s' "$OUT" | grep -qi "(base mode) migration sm_data/V001__data_one.sql"; then
  ok "(i) base mode FAILS on a rewritten sm_data V001 (rc=$RC)"
else
  bad "(i) base mode should FAIL on a rewritten sm_data V001 (rc=$RC): $OUT"
fi

# --- (j) a published sm_metrics migration -------------------------------------------------------------
git_tmp checkout -q -b case-j "$B"
printf -- '-- metrics V001 REWRITTEN\nCREATE TABLE m1 (id bigint);\n' > "$TMP/$RELM/V001__metrics_one.sql"
( cd "$TMP/$RELM" && shasum -a 256 V001__metrics_one.sql ) \
  | awk '{print $1"  "$2}' > "$TMP/$RELM/CHECKSUMS.sha256"
git_tmp add -A
git_tmp commit -q -m "J: rewrite sm_metrics V001 + its manifest line"
J="$(git_tmp rev-parse HEAD)"
run "$J"
if [ "$RC" -eq 1 ] && printf '%s' "$OUT" | grep -q "sm_metrics/V001__metrics_one.sql"; then
  ok "(j) anchor mode FAILS on a rewritten sm_metrics V001 (rc=$RC)"
else
  bad "(j) anchor mode should FAIL on a rewritten sm_metrics V001 (rc=$RC): $OUT"
fi

# --- (k) deleting a published sm_data migration -------------------------------------------------------
git_tmp checkout -q -b case-k "$B"
git_tmp rm -q "$RELD/V002__data_two.sql"
git_tmp commit -q -m "K: delete sm_data V002"
K="$(git_tmp rev-parse HEAD)"
run "$K"
if [ "$RC" -eq 1 ] && printf '%s' "$OUT" | grep -q "sm_data/V002__data_two.sql" && printf '%s' "$OUT" | grep -qi "DELETED"; then
  ok "(k) anchor mode FAILS on a deleted sm_data V002 (rc=$RC)"
else
  bad "(k) anchor mode should FAIL on a deleted sm_data V002 (rc=$RC): $OUT"
fi

# --- (l) a new sm_data migration is allowed and NOTEd as unanchored -------------------------------------
git_tmp checkout -q -b case-l "$B"
printf -- '-- data V003 new\nCREATE TABLE d3 (id int);\n' > "$TMP/$RELD/V003__data_three.sql"
( cd "$TMP/$RELD" && shasum -a 256 V003__data_three.sql ) | awk '{print $1"  "$2}' >> "$TMP/$RELD/CHECKSUMS.sha256"
git_tmp add -A
git_tmp commit -q -m "L: add sm_data V003 + its manifest line"
L="$(git_tmp rev-parse HEAD)"
run "$L" --base "$B"
if [ "$RC" -eq 0 ] && printf '%s' "$OUT" | grep -q "NOTE" && printf '%s' "$OUT" | grep -q "sm_data/V003__data_three.sql"; then
  ok "(l) a new sm_data V003 PASSES and is NOTEd as unanchored (rc=$RC)"
else
  bad "(l) a new sm_data V003 should PASS and be NOTEd (rc=$RC): $OUT"
fi

# --- (m) a rewritten sm_data migration whose commit is itself later anchored stays frozen --------------
# (the anchor is a commit, so the schema directory travels with it): anchor C-prime = the commit that added V003,
# then a rewrite of V003 must fail against it even with no base.
printf '%s  # sm_data V003 published here\n' "$L" >> "$TMP/backend/scripts/frozen-migration-anchors.txt"
git_tmp add -A
git_tmp commit -q -m "M1: anchor L"
printf -- '-- data V003 REWRITTEN\nCREATE TABLE d3 (id bigint);\n' > "$TMP/$RELD/V003__data_three.sql"
git_tmp add -A
git_tmp commit -q -m "M2: rewrite sm_data V003 after it was anchored"
M2="$(git_tmp rev-parse HEAD)"
run "$M2"
if [ "$RC" -eq 1 ] && printf '%s' "$OUT" | grep -q "sm_data/V003__data_three.sql"; then
  ok "(m) an sm_data migration anchored by a later commit is frozen too (rc=$RC)"
else
  bad "(m) a rewrite of an anchored sm_data V003 should FAIL (rc=$RC): $OUT"
fi

# --- (g) pre-push hook: a rewrite is blocked, an add-only push is allowed ------------------------
mkdir -p "$TMP/.githooks"
cp "$HOOK" "$TMP/.githooks/pre-push"
chmod +x "$TMP/.githooks/pre-push"

hook_run "refs/heads/case-a $C1 refs/heads/case-a $A"
if [ "$RC" -ne 0 ]; then
  ok "(g) pre-push hook blocks a push that rewrites V001 (rc=$RC)"
else
  bad "(g) pre-push hook should block a push that rewrites V001 (rc=$RC)"
fi

hook_run "refs/heads/case-i $I refs/heads/case-i $B"
if [ "$RC" -ne 0 ]; then
  ok "(g) pre-push hook blocks a push that rewrites an sm_data migration (rc=$RC)"
else
  bad "(g) pre-push hook should block a push that rewrites an sm_data migration (rc=$RC)"
fi

hook_run "refs/heads/case-c $C3 refs/heads/case-c $B"
if [ "$RC" -eq 0 ]; then
  ok "(g) pre-push hook allows an add-only push (rc=$RC)"
else
  bad "(g) pre-push hook should allow an add-only push (rc=$RC)"
fi

echo
if [ "$fails" -eq 0 ]; then
  echo "  ALL SELF-TEST CASES PASSED (a-m)"
  exit 0
else
  echo "  $fails SELF-TEST CASE(S) FAILED"
  exit 1
fi
