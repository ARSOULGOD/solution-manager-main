#!/usr/bin/env python3
"""Derive the File Mesh renderer vocabulary from file-mesh-ui's schema dump.

Why this exists: the first draft of design.md quoted these figures from a grep whose
regex broke on apostrophes and newlines inside HELPER_TEXT, silently skipping rows.
It reported 1,494 rows and 88 showWhen values; the real figures are materially larger.
A lossy extraction reported as a verified count is exactly the failure mode I2 warns
about, so the derivation is checked in and re-runnable rather than described.

Usage:
    python3 extract-fm-vocabulary.py [path/to/file-mesh-schema.sql] > vocabulary.txt

Source resolution, in order:
    1. the command-line argument, if given
    2. $SM_FILEMESH_SQL, if set
    3. ~/Work/Development/file-mesh-ui/database/file-mesh-schema.sql

file-mesh-ui is a separate repository that is not vendored here, so (3) holds only on a machine
that happens to have checked it out at that path. Set SM_FILEMESH_SQL rather than editing this
file; run-checks.sh steps 4-6 SKIP (they do not fail) when the dump cannot be found.
"""

import collections
import json
import os
import re
import sys

COLUMNS = [
    "ID", "COMPONENT_TYPE", "CONFIGURATION_FIELD", "CONFIGURATION_DESCRIPTION",
    "UI_TYPE", "UI_DATA_SOURCE", "SECTION", "SECTION_ORDER", "CONFIGURATION_PATH",
    "CONNECTOR_TYPE", "SECTION_FIELD_ORDER", "DEFAULT_VALUE", "UI_TYPE_META",
    "SUB_SECTION", "SUB_SECTION_ORDER", "CONFIG_FILE_RELATIVE_PATH",
    "FILE_PROCESS_CONFIGURATION", "MIN_VERSION", "MAX_VERSION",
]

# Overridable because this points outside the repo: file-mesh-ui is a sibling checkout, and the
# path below is only correct on a machine that placed it there. Hardcoding it made contract
# checks 4-6 fail with a bare FileNotFoundError for everyone else.
DEFAULT_SQL = os.environ.get("SM_FILEMESH_SQL") or os.path.expanduser(
    "~/Work/Development/file-mesh-ui/database/file-mesh-schema.sql")


def split_tuple(body):
    """Split one VALUES(...) body on commas, respecting '' -escaped SQL strings.

    A naive regex cannot do this: HELPER_TEXT contains both apostrophes ('' in the
    dump) and literal newlines, so any pattern anchored on quote-or-comma loses rows.
    """
    out, cur, i, in_str = [], [], 0, False
    while i < len(body):
        c = body[i]
        if in_str:
            if c == "'":
                if i + 1 < len(body) and body[i + 1] == "'":   # escaped quote
                    cur.append("'")
                    i += 2
                    continue
                in_str = False
            else:
                cur.append(c)
        elif c == "'":
            in_str = True
        elif c == ",":
            out.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
        i += 1
    out.append("".join(cur).strip())
    return out


def rows_for(txt, table):
    """Yield dicts for every INSERT into `table`, tolerating multi-line values."""
    marker = f'INSERT INTO "CM_SCHEMA"."{table}" VALUES ('
    pos = 0
    while True:
        start = txt.find(marker, pos)
        if start < 0:
            return
        i = start + len(marker)
        depth, in_str = 1, False
        while i < len(txt) and depth:
            c = txt[i]
            if in_str:
                if c == "'":
                    if i + 1 < len(txt) and txt[i + 1] == "'":
                        i += 2
                        continue
                    in_str = False
            elif c == "'":
                in_str = True
            elif c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        vals = split_tuple(txt[start + len(marker):i])
        yield dict(zip(COLUMNS, vals)) if len(vals) == len(COLUMNS) else {"_BAD": vals}
        pos = i + 1


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SQL
    txt = open(path, encoding="utf-8", errors="replace").read()

    real = list(rows_for(txt, "FM_CONFIGURATION_TEMPLATE"))
    copy = list(rows_for(txt, "FM_CONFIGURATION_TEMPLATE_COPY"))
    bad = [r for r in real if "_BAD" in r]
    real = [r for r in real if "_BAD" not in r]

    print(f"source: {path}")
    print(f"FM_CONFIGURATION_TEMPLATE rows      : {len(real)}")
    print(f"FM_CONFIGURATION_TEMPLATE_COPY rows : {len(copy)}")
    print(f"unparsed rows (must be 0)           : {len(bad)}")

    print("\nUI_TYPE distribution:")
    for k, c in collections.Counter(r["UI_TYPE"] for r in real).most_common():
        print(f"  {k or 'NULL':<16} {c}")

    print("\nUI_TYPE_META keys:")
    keys, metatype, bad_json = collections.Counter(), collections.Counter(), 0
    for r in real:
        raw = r["UI_TYPE_META"]
        if not raw or raw == "NULL":
            continue
        try:
            o = json.loads(raw)
        except Exception:
            bad_json += 1
            continue
        if not isinstance(o, dict):
            continue
        for k in o:
            keys[k] += 1
        if "type" in o:
            metatype[str(o["type"])] += 1
    for k, c in keys.most_common():
        print(f"  {k:<18} {c}")
    print(f"  (unparsable UI_TYPE_META: {bad_json})")
    print("\n  meta.type values:", dict(metatype.most_common()))

    print("\nshowWhen / dependency — distinct predicate shapes:")
    shapes = collections.Counter()
    for r in real:
        raw = r["UI_TYPE_META"]
        if not raw or raw == "NULL":
            continue
        try:
            o = json.loads(raw)
        except Exception:
            continue
        for key in ("showWhen", "dependency"):
            v = o.get(key)
            if isinstance(v, dict):
                shapes[(key, json.dumps(v, sort_keys=True))] += 1
    for (key, s), c in shapes.most_common():
        print(f"  {key:<11} x{c:<4} {s[:120]}")

    print("\nGRAMMAR CHECK — any predicate value that is not a scalar literal?")
    viol = []
    for r in real:
        raw = r["UI_TYPE_META"]
        if not raw or raw == "NULL":
            continue
        try:
            o = json.loads(raw)
        except Exception:
            continue
        for key in ("showWhen", "dependency"):
            v = o.get(key)
            if isinstance(v, dict):
                for fk, fv in v.items():
                    if isinstance(fv, (dict, list)):
                        viol.append((r["COMPONENT_TYPE"], r["CONFIGURATION_FIELD"], key, fk, fv))
    print(f"  violations: {len(viol)}"
          + ("  -> AND-of-equalities holds" if not viol else ""))
    for v in viol[:10]:
        print(f"    {v}")

    print("\nUI_DATA_SOURCE — dynamic option sources (the F3 gap):")
    ds = collections.Counter()
    for r in real:
        raw = r["UI_DATA_SOURCE"]
        if not raw or raw == "NULL":
            continue
        try:
            o = json.loads(raw)
            ds[",".join(sorted(o.keys())) if isinstance(o, dict) else "non-object"] += 1
        except Exception:
            ds["non-json"] += 1
    for k, c in ds.most_common():
        print(f"  {k:<24} {c}")

    print("\nComponents by field count (top 12):")
    for k, c in collections.Counter(r["COMPONENT_TYPE"] for r in real).most_common(12):
        print(f"  {k:<26} {c}")

    print("\nButtons and API-sourced fields per component (F3 evidence):")
    per = collections.defaultdict(lambda: [0, 0])
    for r in real:
        if r["UI_TYPE"] == "button":
            per[r["COMPONENT_TYPE"]][0] += 1
        raw = r["UI_DATA_SOURCE"]
        if raw and raw != "NULL" and "api" in raw:
            per[r["COMPONENT_TYPE"]][1] += 1
    for k, (b, a) in sorted(per.items(), key=lambda kv: -sum(kv[1]))[:10]:
        print(f"  {k:<26} buttons={b:<4} api-sourced={a}")

    print("\nMIN_VERSION / MAX_VERSION population:")
    for col in ("MIN_VERSION", "MAX_VERSION"):
        n = sum(1 for r in real if r[col] and r[col] != "NULL")
        print(f"  {col:<12} non-null: {n}")


if __name__ == "__main__":
    main()
