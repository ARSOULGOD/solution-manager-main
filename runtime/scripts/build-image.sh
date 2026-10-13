#!/usr/bin/env bash
# Builds the launcher image reproducibly from the working tree.
#
#   runtime/scripts/build-image.sh [tag]            # default tag: sm-launcher:dev
#   SM_SKIP_MVN=1 runtime/scripts/build-image.sh    # reuse backend/launcher/target/*-exec.jar (SKIPS the licence gate: never for a release)
#
# Reproducibility, stated precisely: the same source tree, the same pinned base digest and the same SOURCE_DATE_EPOCH
# produce the same repackaged jar (maven's project.build.outputTimestamp) and the same OCI image manifest digest
# (buildkit's rewrite-timestamp clamps every layer file's mtime to SOURCE_DATE_EPOCH and sets the config's `created`).
# `--check` builds twice and fails if the manifest digests differ. It does not claim bit-identical builds across different
# JDK vendors/versions, different Maven repositories, or different build platforms (the image is built for the builder's
# platform only), or different build-host time zones when `touch -d @epoch` is unavailable (the fallback is pinned to UTC).
# The build day does not matter: the Dockerfile blanks the one day-stamped field a RUN step writes (/etc/shadow).
#
# Why an OCI archive and not `type=docker`: on Docker Desktop with the containerd image store, `type=docker,rewrite-timestamp=true`
# is refused ("exporter option rewrite-timestamp conflicts with unpack"), which is why 87e338e1 dropped the option. Dropping it
# is not harmless: without it the RUN layer keeps its build-time mtimes and two builds give DIFFERENT image ids (measured
# 2026-10-10: sha256:d9a754... vs sha256:cf8df1...). So the image is exported as an OCI archive with rewrite-timestamp=true
# and then `docker load`ed under the tag.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
check=0
tag="sm-launcher:dev"
for arg in "$@"; do
  case "$arg" in --check) check=1 ;; *) tag="$arg" ;; esac
done

# Commit time of HEAD when there is a git history; a fixed epoch otherwise, never "now".
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git -C "$root" log -1 --format=%ct 2>/dev/null || echo 1767225600)}"

if [ -z "${SM_SKIP_MVN:-}" ]; then
  (cd "$root/backend" && mvn -o -q -DskipTests -DskipITs verify -pl launcher -am)   # verify, not package: the I8 licence gate is bound to verify
fi
jar="$(ls "$root"/backend/launcher/target/launcher-*-exec.jar | head -1)"

# Prints the manifest digest of the image it built and loaded under $1. A failed build is a failed script: command
# substitution does not inherit `set -e`, so every step is checked explicitly.
build_once() {
  local ctx tar; ctx="$(mktemp -d)"; tar="$ctx.oci.tar"
  trap 'rm -rf "$ctx" "$tar"' RETURN
  cp "$root/runtime/launcher/Dockerfile" "$ctx/Dockerfile" || return 1
  cp -R "$root/runtime/launcher/bin" "$ctx/bin" || return 1
  cp "$jar" "$ctx/launcher.jar" || return 1
  cp "$root/runtime/launcher/NOTICE" "$ctx/NOTICE" || return 1
  cp "$root/backend/THIRD-PARTY-NOTICES.md" "$ctx/THIRD-PARTY-NOTICES.md" || return 1
  # Normalise mtimes in the context so nothing depends on when the files were checked out.
  find "$ctx" -exec touch -d "@${SOURCE_DATE_EPOCH}" {} + 2>/dev/null || TZ=UTC0 find "$ctx" -exec touch -t "$(TZ=UTC0 date -u -r "${SOURCE_DATE_EPOCH}" +%Y%m%d%H%M.%S)" {} + || return 1
  docker buildx build --no-cache --provenance=false --sbom=false \
    --build-arg SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH}" \
    --output "type=oci,dest=${tar},name=$1,rewrite-timestamp=true" "$ctx" >&2 || return 1
  local digest
  digest="$(tar -xOf "$tar" index.json | sed -n 's/.*"digest":"\(sha256:[0-9a-f]*\)".*/\1/p' | head -1)" || return 1
  [ -n "$digest" ] || { echo "no manifest digest in the OCI archive" >&2; return 1; }
  docker load -i "$tar" >&2 || return 1
  # The loaded image must be the one built: its id (containerd store: the manifest/index digest) is the digest we report.
  local loaded; loaded="$(docker image inspect "$1" --format '{{.Id}}')" || { echo "docker load did not tag $1" >&2; return 1; }
  [ "$loaded" = "$digest" ] || { echo "loaded image $loaded is not the built manifest $digest" >&2; return 1; }
  echo "$digest"
}

echo "jar:   $(shasum -a 256 "$jar" | cut -d' ' -f1)  $(basename "$jar")"
id1="$(build_once "$tag")" || { echo "build failed" >&2; exit 1; }
echo "image manifest: $id1  ($tag)"
if [ "$check" = 1 ]; then
  id2="$(build_once "$tag-recheck")" || { echo "rebuild failed" >&2; exit 1; }
  echo "image manifest: $id2  ($tag-recheck)"
  docker rmi "$tag-recheck" >&2 || true
  [ "$id1" = "$id2" ] && echo "REPRODUCIBLE: identical manifest digests" || { echo "NOT REPRODUCIBLE: $id1 != $id2" >&2; exit 1; }
fi
