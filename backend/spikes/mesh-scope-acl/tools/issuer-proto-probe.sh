#!/bin/bash
#
# Round 4 MINOR 1 — is a credential that names an Object.prototype key treated as a principal?
#
# Runs the ROUND-3 issuer (read out of git at commit 2b266d1, never modified on disk) and the
# CURRENT issuer, each on a spare port (8095 / 8096; the suite's own issuer stays on 8091), and
# posts /ticket with each prototype-key credential plus one ordinary unknown credential. Prints
# the HTTP status per credential. Expected: round-3 issuer HTTP 200 (ticket signed for a caller who
# is not a principal), current issuer HTTP 401 for every one. Run from anywhere; touches only a
# temp directory; needs node, curl and git (read-only `git show`).
set -uo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'kill $P1 $P2 2>/dev/null; rm -rf "$TMP"' EXIT
[ -d "$HERE/issuer/node_modules/jose" ] || (cd "$HERE/issuer" && npm install >/dev/null) || exit 1
mkdir -p "$TMP/old" "$TMP/new"
git -C "$HERE" show 2b266d1:backend/spikes/mesh-scope-acl/issuer/issuer.mjs | sed 's/8091/8095/g' > "$TMP/old/issuer.mjs" || { echo "cannot read 2b266d1"; exit 1; }
sed 's/8091/8096/g' "$HERE/issuer/issuer.mjs" > "$TMP/new/issuer.mjs"
for d in old new; do cp "$HERE/issuer/package.json" "$TMP/$d/"; ln -s "$HERE/issuer/node_modules" "$TMP/$d/node_modules"; done
(cd "$TMP/old" && exec node issuer.mjs >/dev/null 2>&1) & P1=$!
(cd "$TMP/new" && exec node issuer.mjs >/dev/null 2>&1) & P2=$!
sleep 2
rc=0
for spec in "round-3 issuer (2b266d1):8095:200" "current issuer:8096:401"; do
  label="${spec%%:*}"; rest="${spec#*:}"; port="${rest%%:*}"; want="${rest##*:}"
  echo "== $label on 127.0.0.1:$port — expected HTTP $want for every prototype-key credential"
  for c in __proto__ constructor toString hasOwnProperty valueOf isPrototypeOf; do
    code=$(curl -s -o "$TMP/body" -w '%{http_code}' -X POST -H "Authorization: Bearer $c" "http://127.0.0.1:$port/ticket")
    claims=""
    if [ "$code" = "200" ]; then claims=" claims=$(python3 -c "import json,base64;t=json.load(open('$TMP/body'))['token'].split('.')[1];t+='='*(-len(t)%4);print(json.dumps(json.loads(base64.urlsafe_b64decode(t))))")"; fi
    echo "   Bearer $c -> HTTP $code$claims"
    [ "$code" = "$want" ] || rc=1
  done
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer cred-nobody" "http://127.0.0.1:$port/ticket"); echo "   Bearer cred-nobody (ordinary unknown) -> HTTP $code"
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer cred-p-dev" "http://127.0.0.1:$port/ticket"); echo "   Bearer cred-p-dev (real principal, control) -> HTTP $code"
done
[ $rc -eq 0 ] && echo "RESULT: as expected (round-3 issuer signs for prototype keys; current issuer refuses)" || echo "RESULT: UNEXPECTED"
exit $rc
