#!/usr/bin/env python3
"""Extract the design set's own SQL blocks, by line range, from a COMMITTED tree.

The extracted text is the artifact under test. This script never edits, reformats
or repairs a block: it slices lines out of `git show <commit>:<path>` and records
the range and md5 of every slice into extract.json.

Blocks taken:
  design.md                    §5.2 and §7.4  (every ```sql fence)
  lease-and-registry-sketch.md §2 … §7        (every ```sql fence)
Bootstrap, md5-pinned, in the order it must be applied:
  ../../eda-walking-skeleton.md           216-253   Phase 0 (roles + `CREATE SCHEMA sm_data`)
  backend/.../V001__telemetry_schema.sql  whole     the telemetry schema (placement, series, …)
  ../../eda-walking-skeleton.md           254-533   Phase 1 (the apply-family tables)

Why the telemetry migration is here, and why it sits BETWEEN Phase 0 and Phase 1: it is the
repo's only Flyway V-file and its own lines 3-5 declare it the source of truth for the
telemetry schema, so it is what defines `sm_data.series` — the relation sketch §5.2's
stable-replica DELETE reads. It is taken from the committed file BYTES, never hand-copied,
and pinned by md5 exactly like the other two inputs. Ordering rationale: README.md.
"""
import argparse, hashlib, json, os, re, subprocess, sys

REPO_PREFIX = "docs/design/backend/agent-gateway"
DESIGN = f"{REPO_PREFIX}/design.md"
SKETCH = f"{REPO_PREFIX}/lease-and-registry-sketch.md"
SKELETON = "docs/design/backend/eda-walking-skeleton.md"
TELEMETRY = "backend/src/main/resources/db/migration/V001__telemetry_schema.sql"

BOOTSTRAP = [
    # (name, path, first_line|None, last_line|None, expected md5 of the slice / of the whole file)
    # None/None means the WHOLE file, byte for byte. Applied in THIS order.
    ("phase0",    SKELETON,  216,  253,  "768e195b7c17861d97e402c5318be51b"),
    ("telemetry", TELEMETRY, None, None, "d8e3948552258e17d2c51e440afcbe55"),
    ("phase1",    SKELETON,  254,  533,  "5c28fa14868fbd72cbec158193e6d86f"),
]

WANTED = {
    DESIGN: ("5.2", "7.4"),
    SKETCH: ("2", "3", "4", "5", "6", "7"),
}


def git_show(repo, commit, path):
    """Read a file at a commit — or, with --worktree, from the working tree.

    --worktree exists for DEVELOPMENT ONLY: it lets the harness be debugged against
    uncommitted design edits. A recorded evidence run must name a commit SHA, and
    extract.json records which mode produced it.
    """
    if commit == "WORKTREE":
        with open(os.path.join(repo, path)) as f:
            return f.read()
    out = subprocess.run(["git", "-C", repo, "show", f"{commit}:{path}"],
                         capture_output=True, text=True)
    if out.returncode:
        sys.exit(f"git show {commit}:{path} failed: {out.stderr.strip()}")
    return out.stdout


def md5(text):
    return hashlib.md5(text.encode()).hexdigest()


def section_of(heading):
    """'### 5.2 Ownership …' -> '5.2';  '## 7. DCL …' -> '7'."""
    m = re.match(r"^#+\s+([0-9]+(?:\.[0-9]+)*)\.?\s", heading or "")
    return m.group(1) if m else None


def top(sec):
    return sec.split(".")[0] if sec else None


def fences(text):
    """Yield (lang, first_line, last_line, section) for every fenced block.

    first_line/last_line are 1-based INCLUSIVE line numbers of the block CONTENT
    (the ``` lines themselves are not part of the slice).
    """
    lines = text.split("\n")
    heading, inside, start, lang = None, False, None, None
    for i, l in enumerate(lines, 1):
        if not inside and l.startswith("#"):
            heading = l
        if l.startswith("```"):
            if not inside:
                inside, start, lang = True, i + 1, l[3:].strip()
                sec_at_open = section_of(heading)
            else:
                inside = False
                yield lang, start, i - 1, sec_at_open
    if inside:
        sys.exit("unterminated fence")


def uncommented(sql):
    """Drop whole-line and trailing `--` comments. Never used to feed the engine —
    only to classify a block and to find its real placeholders."""
    out = []
    for l in sql.split("\n"):
        if l.lstrip().startswith("--"):
            continue
        i = l.find("--")
        out.append(l[:i] if i >= 0 else l)
    return "\n".join(out)


def classify(sql):
    body = uncommented(sql)
    has_ph = re.search(r"\$[a-zA-Z_][a-zA-Z0-9_]*", body) is not None
    creates = re.search(r"\b(CREATE|ALTER)\b", body, re.I) is not None
    grants = re.search(r"\bGRANT\b", body, re.I) is not None
    if not has_ph and grants and not creates:
        return "DCL"
    if not has_ph and creates:
        return "DDL"
    if has_ph:
        return "statements"
    return "statements"


def placeholders(sql):
    return sorted(set(re.findall(r"\$([a-zA-Z_][a-zA-Z0-9_]*)", uncommented(sql))))


def main():
    ap = argparse.ArgumentParser()
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--repo", default=os.path.abspath(os.path.join(here, "..", "..", "..", "..", "..", "..")))
    ap.add_argument("--commit", default=None,
                    help="the commit to slice. REQUIRED unless --worktree is given: there is "
                         "deliberately no default, because a default goes stale the next time the "
                         "design is edited and would silently test the wrong line ranges.")
    ap.add_argument("--worktree", action="store_true",
                    help="DEVELOPMENT ONLY: read the design files from the working tree instead of a "
                         "commit. A recorded evidence run must use --commit <sha>.")
    ap.add_argument("--out", default=os.path.join(here, "extracted"))
    a = ap.parse_args()

    if a.worktree:
        a.commit = "WORKTREE"
    elif not a.commit:
        ap.error("--commit <sha> is required (or --worktree for a development run)")
    os.makedirs(a.out, exist_ok=True)
    manifest = {"commit": a.commit, "repo": a.repo, "worktree": bool(a.worktree),
                "blocks": [], "bootstrap": []}

    # --- bootstrap, md5-pinned, IN ORDER --------------------------------------
    for name, path, lo, hi, want in BOOTSTRAP:
        text = git_show(a.repo, a.commit, path)
        if lo is None:                      # whole file, byte for byte
            slice_, lo_r, hi_r = text, 1, text.count("\n")
        else:
            slice_ = "\n".join(text.split("\n")[lo - 1:hi]) + "\n"
            lo_r, hi_r = lo, hi
        got = md5(slice_)
        if got != want:
            sys.exit(f"ABORT: bootstrap {name} ({path} "
                     f"{'whole file' if lo is None else f'{lo}-{hi}'}) md5 {got} != expected {want}")
        fn = os.path.join(a.out, f"bootstrap_{name}.sql")
        open(fn, "w").write(slice_)
        manifest["bootstrap"].append({"name": name, "path": path, "first_line": lo_r,
                                      "last_line": hi_r, "whole_file": lo is None,
                                      "md5": got, "file": os.path.basename(fn)})

    # --- design + sketch sql blocks -------------------------------------------
    for path, want_secs in WANTED.items():
        text = git_show(a.repo, a.commit, path)
        lines = text.split("\n")
        doc = "design" if path.endswith("design.md") else "sketch"
        seen = {}
        for lang, lo, hi, sec in fences(text):
            if lang != "sql" or sec is None:
                continue
            if not (sec in want_secs or top(sec) in want_secs):
                continue
            n = seen.get(sec, 0) + 1
            seen[sec] = n
            body = "\n".join(lines[lo - 1:hi]) + "\n"
            fn = f"{doc}_{sec}_{n}_L{lo}-{hi}.sql"
            open(os.path.join(a.out, fn), "w").write(body)
            manifest["blocks"].append({
                "doc": doc, "path": path, "section": sec, "ordinal": n,
                "first_line": lo, "last_line": hi, "lines": hi - lo + 1,
                "md5": md5(body), "kind": classify(body),
                "placeholders": placeholders(body), "file": fn,
            })

    with open(os.path.join(a.out, "extract.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    if a.worktree:
        print("!! --worktree: extracted from UNCOMMITTED working-tree files. Development only.")
    print(f"extracted {len(manifest['blocks'])} sql blocks + {len(manifest['bootstrap'])} bootstrap files -> {a.out}")
    for b in manifest["blocks"]:
        print(f"  {b['kind']:11} {b['doc']:6} §{b['section']:4} #{b['ordinal']} "
              f"L{b['first_line']}-{b['last_line']:<5} {b['md5'][:8]} {','.join(b['placeholders'])[:70]}")


if __name__ == "__main__":
    main()
