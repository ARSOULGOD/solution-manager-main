#!/usr/bin/env bash
# Maintains the generated "Licence texts" section of THIRD-PARTY-LICENCES.md: every distinct LICENSE*/COPYING* text of the
# modules compiled into sm-agent (go list -deps ./cmd/sm-agent, linux/amd64 + linux/arm64), reproduced verbatim from the
# module cache at the pinned version. Modules with an identical text share one copy; copyright lines stay per module.
# A compiled-in module with no licence file in its module cache FAILS (exit 1) -- it cannot be redistributed in the image.
#
#   scripts/gen-licence-texts.sh           # rewrite the section between the BEGIN/END markers
#   scripts/gen-licence-texts.sh --check   # fail if the section is stale or any module lacks a licence text
set -euo pipefail
cd "$(dirname "$0")/.."
doc=THIRD-PARTY-LICENCES.md
begin='<!-- BEGIN GENERATED LICENCE TEXTS (scripts/gen-licence-texts.sh) -->'
end='<!-- END GENERATED LICENCE TEXTS -->'
mods="$(mktemp)"; sect="$(mktemp)"; new="$(mktemp)"; groups="$(mktemp)"
trap 'rm -f "$mods" "$sect" "$new" "$groups"' EXIT
tmpl='{{with .Module}}{{if not .Main}}{{.Path}} {{.Version}} {{.Dir}}{{end}}{{end}}'
for arch in amd64 arm64; do
  CGO_ENABLED=0 GOOS=linux GOARCH=$arch GOWORK=off GOFLAGS=-mod=readonly go list -deps -f "$tmpl" ./cmd/sm-agent
done | sed '/^$/d' | sort -u > "$mods"
[ -s "$mods" ] || { echo "gen-licence-texts: no modules found" >&2; exit 2; }

# The licence file of a module: LICENSE*, else COPYING*, else UNLICENSE* (LICENSE.docs-style secondary files are not
# the code licence; the first match in this order is used and named in the output).
pick() { local d=$1 f; for f in "$d"/LICEN[CS]E "$d"/LICEN[CS]E.txt "$d"/LICEN[CS]E.md "$d"/COPYING "$d"/COPYING.txt "$d"/UNLICENSE; do [ -f "$f" ] && { echo "$f"; return 0; }; done; return 0; }

rc=0
: > "$groups"
while read -r m v dir; do
  f="$(pick "$dir")"
  if [ -z "$f" ] || [ ! -s "$f" ]; then echo "gen-licence-texts: $m $v has no licence text in the module cache ($dir)" >&2; rc=1; continue; fi
  printf '%s\t%s\t%s\t%s\n' "$(shasum -a 256 "$f" | cut -d' ' -f1)" "$m" "$v" "$f" >> "$groups"
done < "$mods"
[ $rc -eq 0 ] || exit 1

{
  echo "$begin"
  echo
  echo "## Licence texts (modules)"
  echo
  echo "Reproduced verbatim from each module's licence file in the Go module cache, at the version in the table above. Modules"
  echo "whose files are byte-identical share one copy; the copyright lines found in each module's own file are listed per module"
  echo "(a line such as \`Copyright [yyyy] [name of copyright owner]\` is Apache-2.0's own appendix boilerplate, not a notice)."
  n=0
  for h in $(cut -f1 "$groups" | sort -u); do
    n=$((n+1))
    first="$(awk -F'\t' -v h="$h" '$1==h{print $4; exit}' "$groups")"
    echo; echo "### Text $n (sha256 ${h:0:12})"; echo
    echo "Modules:"; echo
    awk -F'\t' -v h="$h" '$1==h{print $2" "$3"\t"$4}' "$groups" | sort | while IFS=$'\t' read -r label f; do
      echo "- \`$label\` (\`$(basename "$f")\`)"
      grep -i -E '^[[:space:]]*(copyright|\(c\)|portions copyright)' "$f" | sed 's/[[:space:]]*$//' | sort -u | sed 's/^[[:space:]]*/    - /'
    done
    echo; echo '~~~~text'; cat "$first"; [ -z "$(tail -c1 "$first")" ] || echo; echo '~~~~'
  done
  echo; echo "$end"
} > "$sect"

if [ "${1:-}" = "--check" ]; then
  grep -qxF "$begin" "$doc" && grep -qxF "$end" "$doc" || { echo "gen-licence-texts: markers missing in $doc" >&2; exit 1; }
  diff -q <(sed -n "/^$(printf '%s' "$begin" | sed 's/[][\.*^$/]/\\&/g')\$/,/^$(printf '%s' "$end" | sed 's/[][\.*^$/]/\\&/g')\$/p" "$doc") "$sect" >/dev/null \
    || { echo "gen-licence-texts: the licence texts in $doc are stale (run scripts/gen-licence-texts.sh)" >&2; exit 1; }
  echo "gen-licence-texts: ok ($(wc -l < "$mods" | tr -d ' ') modules, $(cut -f1 "$groups" | sort -u | wc -l | tr -d ' ') distinct texts)"
else
  if grep -qxF "$begin" "$doc"; then
    awk -v b="$begin" -v e="$end" -v s="$sect" '$0==b{while((getline l < s)>0)print l; skip=1; next} $0==e{skip=0; next} !skip' "$doc" > "$new"
  else { cat "$doc"; echo; cat "$sect"; } > "$new"; fi
  cp "$new" "$doc"
fi
