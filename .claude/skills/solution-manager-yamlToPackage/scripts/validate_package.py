#!/usr/bin/env python3
"""Validate a Solution Package: the JSON Schema plus the structural checks the schema cannot
express. Errors fail the run; warnings are reported and left for a human.

    python3 validate_package.py path/to/package.json [--schema contracts/solution-package.schema.json]

The schema is auto-located by walking up from the package and from the current directory to a
`contracts/solution-package.schema.json`. Without it (or without the `jsonschema` module) only the
structural checks run, and the output says so.
"""
import argparse
import json
import os
import re
import sys

PASSWORD_RX = re.compile(r"(password|passwd|pwd)$", re.IGNORECASE)


def find_schema(*starts):
    for start in starts:
        d = os.path.abspath(start if os.path.isdir(start) else os.path.dirname(start))
        while True:
            cand = os.path.join(d, "contracts", "solution-package.schema.json")
            if os.path.isfile(cand):
                return cand
            parent = os.path.dirname(d)
            if parent == d:
                break
            d = parent
    return None


def schema_errors(pkg, schema_path):
    try:
        import jsonschema
    except ImportError:
        return None, "jsonschema module not installed: schema check skipped"
    with open(schema_path, encoding="utf-8") as fh:
        schema = json.load(fh)
    v = jsonschema.Draft202012Validator(schema)
    return [f"schema: {'/'.join(map(str, e.path)) or '<root>'}: {e.message}"
            for e in sorted(v.iter_errors(pkg), key=lambda e: list(e.path))], None


def structural(pkg):
    errors, warnings = [], []
    pv = pkg.get("packageVersion") or []
    if not pv or not all(isinstance(x, int) for x in pv):
        errors.append("packageVersion must be a non-empty array of integers")
    elif pv != sorted(set(pv)):
        errors.append(f"packageVersion {pv} must be ordered and unique")

    carried = {v: 0 for v in pv}
    total = 0
    for comp in pkg.get("components", []):
        ct, cp = comp.get("type"), comp.get("configPath", "")
        cv = comp.get("version", pv)
        for v in cv:
            if v not in pv:
                errors.append(f"{ct}: component version {v} is not in packageVersion {pv}")
            else:
                carried[v] += 1
        fields = comp.get("fields", [])
        total += len(fields)
        by_key, seen_ci = {f["key"]: f for f in fields}, {}
        for f in fields:
            k = f["key"]
            for v in f.get("version", []):
                if v not in cv:
                    errors.append(f"{ct}/{k}: field version {v} is outside component version {cv}")
            if cp and (k == cp or k.startswith(cp + ".")):
                errors.append(f"{ct}/{k}: key repeats configPath {cp!r}; it would render as {cp}.{k}")
            low = k.lower()
            if low in seen_ci:
                errors.append(f"{ct}/{k}: duplicates {seen_ci[low]!r} case-insensitively")
            seen_ci.setdefault(low, k)
            leaf = re.split(r"[.]", k)[-1].replace("-", "").replace("_", "")
            if PASSWORD_RX.search(leaf) and f.get("type") != "secret":
                errors.append(f"{ct}/{k}: looks like a password but type is {f.get('type')!r}, not secret")
            if f.get("type") == "secret" and f.get("default") not in (None, ""):
                warnings.append(f"{ct}/{k}: secret field carries a default value (I6: secret values never land in config storage)")
            if f.get("type") == "enum" and not (f.get("options") or f.get("optionsSource")):
                errors.append(f"{ct}/{k}: enum without options or optionsSource")
            if f.get("type") == "enum" and f.get("options") and f.get("default") is not None \
                    and f["default"] not in f["options"]:
                errors.append(f"{ct}/{k}: default {f['default']!r} is not in options {f['options']}")
            if f.get("discriminator") and f.get("type") not in ("enum", "bool"):
                errors.append(f"{ct}/{k}: discriminator must be enum or bool")
            for wk, wv in (f.get("when") or {}).items():
                tgt = by_key.get(wk)
                if tgt is None:
                    errors.append(f"{ct}/{k}: when references unknown field {wk!r}")
                elif tgt.get("options") and wv not in tgt["options"]:
                    errors.append(f"{ct}/{k}: when {wk}={wv!r} is not one of {tgt['options']}")
            ref = (f.get("valueFrom") or {}).get("ref")
            if ref is not None and ref not in by_key:
                errors.append(f"{ct}/{k}: valueFrom.ref {ref!r} is not a field of this component")
            ui = f.get("ui") or {}
            for part in ("section", "label"):
                if not ui.get(part):
                    warnings.append(f"{ct}/{k}: ui.{part} is missing")

    # A component may legitimately join at a later version (cache from v2), but the LATEST version
    # carried by only some components means an instance at that version loses the others.
    n_comp = len(pkg.get("components", []))
    unused = [v for v, n in carried.items() if n == 0]
    if pv and 0 < carried.get(pv[-1], 0) < n_comp:
        warnings.append(f"latest package version {pv[-1]} is carried by only {carried[pv[-1]]} of {n_comp} components")
    if unused:
        warnings.append(f"package version(s) {unused} are carried by no component")

    auth = pkg.get("authoring")
    if auth:
        if auth.get("totalRows") != total:
            # The reference packages count extraction rows here (cache: 1494 rows, 28 fields), so a
            # mismatch is reported, not failed. The engine itself always writes the field count.
            warnings.append(f"authoring.totalRows {auth.get('totalRows')} != field count {total}")
        if auth.get("reviewedRows", 0) > auth.get("totalRows", 0):
            errors.append("authoring.reviewedRows exceeds totalRows")
    prov = pkg.get("provenance") or {}
    if prov.get("author") == "solace" and not prov.get("signature"):
        errors.append("provenance: author solace without a signature")
    return errors, warnings


def validate(pkg, schema_path=None):
    errors, warnings = structural(pkg)
    notes = []
    if schema_path:
        se, note = schema_errors(pkg, schema_path)
        if note:
            notes.append(note)
        else:
            errors = se + errors
            notes.append(f"schema: {schema_path}")
    else:
        notes.append("schema not found: schema check skipped (pass --schema)")
    return {"ok": not errors, "errors": errors, "warnings": warnings, "notes": notes}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("packages", nargs="+")
    ap.add_argument("--schema")
    args = ap.parse_args()
    rc = 0
    for p in args.packages:
        with open(p, encoding="utf-8") as fh:
            pkg = json.load(fh)
        res = validate(pkg, args.schema or find_schema(p, os.getcwd()))
        nf = sum(len(c.get("fields", [])) for c in pkg.get("components", []))
        print(f"{'PASS' if res['ok'] else 'FAIL'} {p}  components={len(pkg.get('components', []))} fields={nf}")
        for n in res["notes"]:
            print(f"  note: {n}")
        for e in res["errors"]:
            print(f"  error: {e}")
        for w in res["warnings"]:
            print(f"  warning: {w}")
        rc |= 0 if res["ok"] else 1
    sys.exit(rc)


if __name__ == "__main__":
    main()
