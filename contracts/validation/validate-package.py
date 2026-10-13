#!/usr/bin/env python3
"""Load-time gates for a Solution Package (docs/design/dynamic-ui/design.md §6.4).

Gates 1, 6, 7, 8 and 11 are expressed in the JSON Schema itself. Gates 2, 3, 4, 5, 5b,
9, 10, 12, 13, 14, 15 and 16 need cross-referencing the schema cannot do, and live here.

    python3 validate-package.py fixtures/*.package.json
    python3 validate-package.py --expect-fail negative/*.json
"""
import json, sys, glob, os, re

import jsonschema

HERE = os.path.dirname(os.path.abspath(__file__))
SCHEMA = json.load(open(os.path.join(HERE, "..", "solution-package.schema.json")))


def gate1_schema(pkg):
    errs = []
    v = jsonschema.Draft202012Validator(SCHEMA)
    for e in sorted(v.iter_errors(pkg), key=lambda e: list(e.path)):
        errs.append(f"gate1  schema: {'/'.join(str(p) for p in e.path) or '<root>'}: {e.message}")
    return errs


def gate2_signature(pkg, dev_mode=False):
    prov = pkg.get("provenance") or {}
    if prov.get("author") == "solace" and not prov.get("signature"):
        return ["gate2  signature: author=solace but no signature"]
    if prov.get("author") == "dev-mode" and not dev_mode:
        return ["gate2  signature: unsigned dev-mode package but dev mode is off"]
    if not prov:
        return ["gate2  signature: no provenance block"]
    return []


def _rng(spec):
    """Minimal semver-range parse: '>=2.2 <2.5' -> ((2,2), (2,5)). Enough for gate 3."""
    lo = hi = None
    for tok in spec.split():
        num = tuple(int(x) for x in tok.lstrip("><=").split(".") if x.isdigit())
        if tok.startswith(">="):
            lo = num
        elif tok.startswith("<"):
            hi = num
    return lo or (0,), hi or (9999,)


def gate3_overlap(pkgs):
    errs, by_sol = [], {}
    for name, p in pkgs:
        by_sol.setdefault(p.get("solution"), []).append((name, p))
    for sol, items in by_sol.items():
        for i in range(len(items)):
            for j in range(i + 1, len(items)):
                (na, a), (nb, b) = items[i], items[j]
                la, ha = _rng(a.get("appliesTo", ""))
                lb, hb = _rng(b.get("appliesTo", ""))
                if la < hb and lb < ha:
                    errs.append(f"gate3  overlap: {sol}: {na} {a['appliesTo']!r} overlaps {nb} {b['appliesTo']!r}")
    return errs


def semantic_gates(pkg):
    errs = []
    package_versions = pkg.get("packageVersion", [])

    def effective_versions(declared, inherited):
        if declared is None:
            return list(inherited)
        return [version for version in declared if version in inherited]

    for comp in pkg.get("components", []):
        ct = comp["type"]
        fields = comp.get("fields", [])
        by_key = {f["key"]: f for f in fields}
        component_versions = effective_versions(comp.get("version"), package_versions)

        # gate 14 - every declaration version must be selectable. A field also cannot
        # outlive the component that owns it. Cross-reference owners and action inputs
        # must be present in every version in which the owner can run.
        for version in comp.get("version", []):
            if version not in package_versions:
                errs.append(f"gate14 {ct}: component version {version!r} is not declared by packageVersion")
        for field in fields:
            for version in field.get("version", []):
                if version not in package_versions:
                    errs.append(f"gate14 {ct}/{field['key']}: field version {version!r} is not declared by packageVersion")
                elif version not in component_versions:
                    errs.append(f"gate14 {ct}/{field['key']}: field version {version!r} is outside its component versions")

        # gate 9 - uniqueness within the component
        for label, items in (("field", [f["key"] for f in fields]),
                             ("metric", [m["name"] for m in comp.get("metrics", [])]),
                             ("action", [a["key"] for a in comp.get("actions", [])])):
            seen = set()
            for k in items:
                if k in seen:
                    errs.append(f"gate9  {ct}: duplicate {label} key {k!r}")
                seen.add(k)

        def check_pred(owner, when, owner_versions):
            for k, v in (when or {}).items():
                # gate 4 - key names a field in the same component
                tgt = by_key.get(k)
                if tgt is None:
                    errs.append(f"gate4  {ct}/{owner}: when references unknown field {k!r}")
                    continue
                target_versions = effective_versions(tgt.get("version"), component_versions)
                missing_versions = [version for version in owner_versions if version not in target_versions]
                if missing_versions:
                    errs.append(f"gate14 {ct}/{owner}: when field {k!r} is unavailable in versions {missing_versions!r}")
                # gate 5b - value must be a member / type-compatible
                if "options" in tgt:
                    if v not in tgt["options"]:
                        errs.append(f"gate5b {ct}/{owner}: when {k}={v!r} is not in options {tgt['options']}")
                elif "optionsSource" in tgt:
                    pass  # not statically checkable; noted in design §6.4
                elif tgt["type"] == "bool" and not isinstance(v, bool):
                    errs.append(f"gate5b {ct}/{owner}: when {k}={v!r} is not a bool")
                elif tgt["type"] == "int" and not isinstance(v, int):
                    errs.append(f"gate5b {ct}/{owner}: when {k}={v!r} is not an int")
                # gate 5 - reachability: this predicate must ENTAIL the target's own predicate
                for j, jv in (tgt.get("when") or {}).items():
                    if j not in when:
                        errs.append(f"gate5  {ct}/{owner}: when omits ancestor {j!r} required by {k!r}")
                    elif when[j] != jv:
                        errs.append(f"gate5  {ct}/{owner}: unreachable - {j}={when[j]!r} contradicts {k!r}'s {j}={jv!r}")

        for f in fields:
            check_pred(f["key"], f.get("when"), effective_versions(f.get("version"), component_versions))
        for a in comp.get("actions", []):
            check_pred(a["key"], a.get("when"), component_versions)
            for inp in a.get("inputs", []):
                if inp not in by_key:
                    errs.append(f"gate4  {ct}/{a['key']}: action input {inp!r} is not a field")
                    continue
                input_versions = effective_versions(by_key[inp].get("version"), component_versions)
                missing_versions = [version for version in component_versions if version not in input_versions]
                if missing_versions:
                    errs.append(f"gate14 {ct}/{a['key']}: action input {inp!r} is unavailable in versions {missing_versions!r}")
        for m in comp.get("metrics", []):
            check_pred(m["name"], m.get("when"), component_versions)

        # gate 12 - a discriminator that any predicate keys on must be reachable: either it
        # declares a default, or the operator can set it. Without this every dependent field
        # is hidden on a fresh draft, which is the defect the File Mesh fixture exposed in
        # ALLOW_SEMP / ALLOW_SSL (options ["yes"], no default, ui.readOnly true).
        keyed = {k for x in list(fields) + list(comp.get("actions", [])) + list(comp.get("metrics", []))
                 for k in (x.get("when") or {})}
        for f in fields:
            if f["key"] not in keyed:
                continue
            has_default = "default" in f and f["default"] is not None
            settable = not (f.get("ui") or {}).get("readOnly", False)
            if not has_default and not settable:
                errs.append(f"gate12 {ct}: {f['key']!r} is keyed on by a predicate but has no default "
                            f"and is readOnly, so every dependent field is hidden forever")

        # gate 13 - secrecy is declared one way
        for f in fields:
            if f["type"] == "secret" and f.get("secret") is False:
                errs.append(f"gate13 {ct}: {f['key']!r} declares type 'secret' with secret:false — "
                            f"one guarantee, two contradictory spellings")

        # gate 10 - a yaml subtree must not collide with a declared key
        for f in fields:
            if f["type"] == "yaml":
                sub = f.get("yamlSubtree", "")
                for k in by_key:
                    if k != f["key"] and (k == sub or k.startswith(sub + ".")):
                        errs.append(f"gate10 {ct}: yaml field {f['key']!r} subtree {sub!r} collides with field {k!r}")

        # gate 16 - a solace_event metric's instanceKey names a field of THIS component that is not a secret (plan 011
        # P11-5.1; I6). The field's configured value is matched against what a broker event carries, and a secret is
        # never read for that: it would be resolved into a comparison against traffic. A key naming no field could
        # never match anything, so it is refused here rather than discovered as an instance that attributes nothing.
        for m in comp.get("metrics", []):
            src = m.get("source") or {}
            if src.get("kind") != "solace_event":
                continue
            ref = (src.get("instanceKey") or {}).get("configField")
            target = by_key.get(ref)
            if target is None:
                errs.append(f"gate16 {ct}/{m['name']}: instanceKey.configField {ref!r} is not a field of this component")
            elif target["type"] == "secret" or target.get("secret") is True:
                errs.append(f"gate16 {ct}/{m['name']}: instanceKey.configField {ref!r} is a secret field, "
                            f"which is never read for attribution")

    # gate 15 - a metric name is unique across ALL of a package's components (plan 011 M-Q5: the key the Data
    # Plane stores a metric under has no component in it). Gate 9 already covers a repeat WITHIN one component,
    # so only names that an EARLIER component declared are reported here.
    declared_by = {}   # metric name -> (index, type) of the first component that declared it
    for idx, comp in enumerate(pkg.get("components", [])):
        own = set()
        for m in comp.get("metrics", []):
            name = m["name"]
            first = declared_by.get(name)
            if first is not None and first[0] != idx and name not in own:
                errs.append(f"gate15 {comp['type']}: metric {name!r} is already declared by component {first[1]!r}")
            own.add(name)
        for name in own:
            declared_by.setdefault(name, (idx, comp["type"]))
    return errs


def validate(name, pkg, all_pkgs, dev_mode=False):
    errs = gate1_schema(pkg)
    if errs:
        return errs  # later gates assume a well-formed document
    return gate2_signature(pkg, dev_mode) + semantic_gates(pkg)


# Gates 1, 6, 7, 8 and 11 are expressed in the JSON Schema itself (module docstring), so a
# negative named for gate 6/7/8/11 is actually rejected by gate1's schema validator, not by a
# same-numbered semantic gate — measured 2026-09-29 (`gate1_schema` short-circuits `validate`
# before any semantic gate runs). Every other gate number is rejected by its own same-numbered
# check.
SCHEMA_ONLY_GATE_NUMBERS = {6, 7, 8, 11}

GATE_NAME_RE = re.compile(r"^gate(\d{2})([a-z]?)-")


def expected_gate(filename):
    """The gate a negative fixture's own file name claims to exercise, e.g.
    'gate05b-when-value-not-an-option.json' -> 'gate5b', 'gate07-metric-missing-unit.json' ->
    'gate1' (schema-only). Returns None if the name does not follow the `gateNN[x]-` convention
    (`--expect-fail` then falls back to the old any-error behaviour for that file, rather than
    refusing to run)."""
    m = GATE_NAME_RE.match(filename)
    if not m:
        return None
    number, suffix = int(m.group(1)), m.group(2)
    if number in SCHEMA_ONLY_GATE_NUMBERS:
        return "gate1"
    return f"gate{number}{suffix}"


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    expect_fail = "--expect-fail" in sys.argv
    dev_mode = "--dev-mode" in sys.argv
    paths = [p for a in args for p in sorted(glob.glob(a))]
    pkgs = [(os.path.basename(p), json.load(open(p))) for p in paths]

    rc = 0
    for name, pkg in pkgs:
        errs = validate(name, pkg, pkgs, dev_mode)
        if expect_fail:
            if errs:
                actual = errs[0].split()[0] if errs[0].split() else ""
                want = expected_gate(name)
                if want is None or actual == want:
                    print(f"  PASS (rejected as intended) {name}")
                    print(f"         -> {errs[0]}")
                else:
                    print(f"  FAIL (rejected by {actual}, expected {want}) {name}")
                    print(f"         -> {errs[0]}")
                    rc = 1
            else:
                print(f"  FAIL (accepted, should have been rejected) {name}")
                rc = 1
        else:
            if errs:
                print(f"  FAIL {name}  ({len(errs)} gate violations)")
                for e in errs[:12]:
                    print(f"         {e}")
                rc = 1
            else:
                nf = sum(len(c.get("fields", [])) for c in pkg.get("components", []))
                na = sum(len(c.get("actions", [])) for c in pkg.get("components", []))
                nm = sum(len(c.get("metrics", [])) for c in pkg.get("components", []))
                print(f"  PASS {name}  components={len(pkg['components'])} fields={nf} actions={na} metrics={nm}")

    if not expect_fail and len(pkgs) > 1:
        o = gate3_overlap(pkgs)
        for e in o:
            print(f"  FAIL {e}")
            rc = 1
    sys.exit(rc)


if __name__ == "__main__":
    main()
