#!/usr/bin/env python3
"""Frozen-migration check (ruling OR-22).

Two complementary checks over the Flyway migrations of all three schemas (`sm_control`, `sm_data`,
`sm_metrics`; the last two since OR-105 (e)):

ANCHOR MODE (always runs — this is what closes the finding below). `frozen-migration-anchors.txt`
lists full 40-hex commit SHAs, one per line ("#" comments allowed, append-only). A commit SHA is
content-addressed, so every V*.sql and every CHECKSUMS.sha256 line present AT an anchor, in any of the
three migration directories, is frozen forever, regardless of what any branch later points to — this is what survives a rewritten branch
tip, or a moving `origin/main` / `origin/<branch>` ref. An anchor commit that is not present
locally is a FAIL, not a skip: fetch it and re-run.

BASE MODE (`--base <commit>`, optional). Compares the commit being checked against a single prior
commit — normally the merge-base with what was last published — so a proposed change is checked
against its OWN pre-change state, even for migrations too new to have an anchor yet. It also
enforces that the anchors file itself is append-only: every anchor SHA present at <base> must
still be present in HEAD's anchors file.

THE FINDING this rewrite closes: the previous version of this check compared against a single
baseline that was a moving ref (origin/<branch>, falling back to origin/main). The moment a
rewritten migration and its rewritten CHECKSUMS.sha256 line are pushed together, that baseline ref
itself moves to point at the rewriting commit — so the check ends up comparing that commit with
itself, and passes. A literal commit SHA does not move once chosen; pinning anchors as SHAs (not
floating refs) is what makes the guarantee survive being pushed. Base mode alone has exactly the
same "compare with itself" blind spot as the old check — anchor mode is what actually closes it.

`--head <commit>` selects the commit to check (read via `git show` / `git ls-tree`); omit it to
check the working tree. `--base <commit>` is independent of `--head` and only widens what gets
checked, on top of the anchors — never in place of them.

Enforcement points: contracts/run-checks.sh step 12 (always), the opt-in .githooks/pre-push hook
(local, bypassable with --no-verify), and .github/workflows/migration-freeze.yml (report-only —
this repository is private on a free plan, so branch protection / rulesets 403 and nothing
server-side can BLOCK a push or a merge).

Exit codes: 0 pass · 1 FAIL (a frozen migration, manifest line, or anchor line changed) ·
2 SKIP (a requested --base commit does not resolve to a commit present locally — nothing to
compare; anchor mode itself never skips inside a git checkout).
"""
import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

# The migration directories, one per schema. The anchors file and the checks below are common to all of them: an
# anchor commit freezes whatever each directory held at that commit.
SCHEMAS = {
    "sm_control": "backend/cp-core/src/main/resources/db/migration/sm_control",
    "sm_data": "backend/dp-core/src/main/resources/db/migration/sm_data",
    "sm_metrics": "backend/dp-core/src/main/resources/db/migration/sm_metrics",
}
MANIFEST = "CHECKSUMS.sha256"
ANCHORS_REL = "backend/scripts/frozen-migration-anchors.txt"
SHA_RE = re.compile(r"^[0-9a-fA-F]{40}$")

# The genesis anchor lives in the checker as well as in the anchors file, so a change that deletes
# or empties the anchors file cannot unfreeze V001-V012 on a path with no base to compare against
# (a new-branch push, or run-checks with no upstream). CI runs the BASE commit's copy of this script,
# so a change under review cannot remove this line either. The override exists only for the
# hermetic self-test, whose throwaway repository does not contain this commit; nothing else sets it.
GENESIS_ANCHORS = ["245d763c975a82f13b09f38386167f85d00a2ff0"]
if "SM_FROZEN_GENESIS_ANCHORS" in os.environ:
    GENESIS_ANCHORS = os.environ["SM_FROZEN_GENESIS_ANCHORS"].split()

ROOT = None


def git(*args, binary=False):
    cmd = ["git", "-C", str(ROOT), *args] if ROOT else ["git", *args]
    out = subprocess.run(cmd, capture_output=True, check=False)
    if out.returncode != 0:
        return None
    return out.stdout if binary else out.stdout.decode()


def label(commit):
    return "working tree" if commit is None else commit[:12]


def read_blob(commit, relpath):
    """Bytes at `relpath`, or None if missing. commit=None reads the working tree file."""
    if commit is None:
        p = ROOT / relpath
        return p.read_bytes() if p.is_file() else None
    return git("show", f"{commit}:{relpath}", binary=True)


def list_migrations(commit):
    """Every migration of every schema at `commit`, as (schema, file name), sorted."""
    found = []
    for schema, rel in SCHEMAS.items():
        if commit is None:
            d = ROOT / rel
            if d.is_dir():
                found.extend((schema, p.name) for p in d.iterdir()
                             if p.name.startswith("V") and p.name.endswith(".sql"))
            continue
        # `<ref>:<dir>` was measured to return nothing here; `ls-tree <ref> -- <dir>/` works.
        listing = git("ls-tree", "--name-only", commit, "--", rel + "/")
        if listing is None:
            continue
        found.extend((schema, Path(p).name) for p in listing.split()
                     if Path(p).name.startswith("V") and p.endswith(".sql"))
    return sorted(found)


def migration_path(schema, name):
    return f"{SCHEMAS[schema]}/{name}"


def shown(schema, name):
    return f"{schema}/{name}"


def read_manifest_lines(commit):
    """Every manifest line of every schema at `commit`, as (schema, line)."""
    lines = []
    for schema, rel in SCHEMAS.items():
        blob = read_blob(commit, f"{rel}/{MANIFEST}")
        if blob is not None:
            lines.extend((schema, line) for line in blob.decode().splitlines() if line.strip())
    return lines


def parse_anchors(blob):
    """(shas, malformed) — ordered lowercase 40-hex SHAs, and any non-blank/non-comment line that
    is not a well-formed 'SHA [# comment]' line."""
    shas, malformed = [], []
    if blob is None:
        return shas, malformed
    for raw in blob.decode("utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        token = line.split()[0]
        if SHA_RE.match(token):
            shas.append(token.lower())
        else:
            malformed.append(raw)
    return shas, malformed


def anchors_at(commit):
    return parse_anchors(read_blob(commit, ANCHORS_REL))


def resolve_commit(rev):
    """Full sha for `rev`, or None if it does not resolve to a commit object here."""
    out = git("rev-parse", "--verify", "--quiet", f"{rev}^{{commit}}")
    return out.strip() if out else None


def check_anchors(head):
    """Anchor mode. Returns (errors, frozen_names, declared_count, checked_shas)."""
    errors = []
    shas, malformed = anchors_at(head)
    shas = GENESIS_ANCHORS + [sha for sha in shas if sha not in GENESIS_ANCHORS]
    for m in malformed:
        errors.append(f"FAIL malformed line in {ANCHORS_REL} "
                       f"(expected a 40-hex commit SHA, optionally followed by a '#' comment): {m!r}")

    frozen_names = set()
    checked_shas = []
    for sha in shas:
        if resolve_commit(sha) is None:
            errors.append(
                f"FAIL anchor {sha} is not present in this local repository — anchor mode cannot "
                f"skip it (OR-22). Fetch it first: git fetch origin {sha}   "
                f"(or 'git fetch --unshallow' if this is a shallow clone), then re-run."
            )
            continue
        checked_shas.append(sha)
        for schema, name in list_migrations(sha):
            frozen_names.add((schema, name))
            before = read_blob(sha, migration_path(schema, name))
            now = read_blob(head, migration_path(schema, name))
            if now is None:
                errors.append(f"FAIL published migration {shown(schema, name)} (frozen at anchor {sha[:12]}) is "
                               f"DELETED in {label(head)} (OR-22: published migrations are immutable)")
            elif now != before:
                errors.append(f"FAIL published migration {shown(schema, name)} (frozen at anchor {sha[:12]}) was "
                               f"CHANGED relative to that anchor (OR-22: put the change in a new migration)")
        manifest_now = set(read_manifest_lines(head))
        for schema, line in read_manifest_lines(sha):
            if (schema, line) not in manifest_now:
                errors.append(f"FAIL {schema} manifest line frozen at anchor {sha[:12]} changed or removed: "
                               f"{line[:80]}")

    return errors, frozen_names, len(shas), checked_shas


def check_base(head, base):
    """Base mode. Returns a list of FAIL messages, each prefixed '(base mode)'."""
    errors = []
    for schema, name in list_migrations(base):
        before = read_blob(base, migration_path(schema, name))
        now = read_blob(head, migration_path(schema, name))
        if now is None:
            errors.append(f"FAIL (base mode) migration {shown(schema, name)}, present at base {base[:12]}, "
                           f"is DELETED in {label(head)}")
        elif now != before:
            errors.append(f"FAIL (base mode) migration {shown(schema, name)}, present at base {base[:12]}, "
                           f"was CHANGED relative to base")
    manifest_now = set(read_manifest_lines(head))
    for schema, line in read_manifest_lines(base):
        if (schema, line) not in manifest_now:
            errors.append(f"FAIL (base mode) {schema} manifest line present at base {base[:12]} "
                           f"changed or removed: {line[:80]}")

    base_shas, _ = anchors_at(base)
    head_shas, _ = anchors_at(head)
    head_set = set(head_shas)
    for sha in base_shas:
        if sha not in head_set:
            errors.append(f"FAIL (base mode) {ANCHORS_REL}: anchor {sha} present at base "
                           f"{base[:12]} is missing (or was edited) in {label(head)} — "
                           f"the anchors file is append-only")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                      formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--head", default=None,
                         help="commit to check; default is the working tree")
    parser.add_argument("--base", default=None,
                         help="prior commit to additionally compare against (proposed-change mode)")
    args = parser.parse_args()

    root_s = git("rev-parse", "--show-toplevel")
    if root_s is None:
        print("  SKIP not a git checkout")
        return 2
    global ROOT
    ROOT = Path(root_s.strip())

    head = args.head
    if head is not None:
        resolved = resolve_commit(head)
        if resolved is None:
            print(f"  FAIL --head {head!r} does not resolve to a commit in this repository")
            return 1
        head = resolved

    anchor_errors, frozen_names, declared, checked_shas = check_anchors(head)
    lbl = label(head)

    base_errors = []
    base_skip = False
    base = None
    if args.base is not None:
        base = resolve_commit(args.base)
        if base is None:
            base_skip = True
        else:
            base_errors = check_base(head, base)

    if declared == 0:
        print(f"  PASS (anchor mode) no anchors declared in {ANCHORS_REL} yet — nothing frozen")
    elif not anchor_errors:
        names = ", ".join(shown(sc, n) for sc, n in sorted(frozen_names))
        anchor_list = ", ".join(s[:12] for s in checked_shas)
        print(f"  PASS (anchor mode) {lbl}: {len(frozen_names)} migration(s) byte-identical at "
              f"{len(checked_shas)} anchor(s) [{anchor_list}] — {names}")
    for e in anchor_errors:
        print(f"  {e}")

    if args.base is not None:
        if base_skip:
            print(f"  SKIP (base mode) --base {args.base!r} does not resolve to a commit present "
                  f"locally — nothing to compare")
        elif not base_errors:
            print(f"  PASS (base mode) {lbl}: every migration/manifest line at base {base[:12]} "
                  f"survives, and {ANCHORS_REL} is append-only since then")
        for e in base_errors:
            print(f"  {e}")

    unanchored = sorted(set(list_migrations(head)) - frozen_names)
    if unanchored:
        print(f"  NOTE {len(unanchored)} migration(s) in {lbl} are not covered by any anchor yet: "
              f"{', '.join(shown(sc, n) for sc, n in unanchored)}")
        print(f"       once pushed, freeze them: append the publishing commit's SHA to "
              f"{ANCHORS_REL} in a follow-up commit (see the migration README)")

    if anchor_errors or base_errors:
        return 1
    if base_skip:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
