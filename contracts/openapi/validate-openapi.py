#!/usr/bin/env python3
"""Check 11 — the console OpenAPI against the ratified permission catalog.

The point of this check is that an endpoint cannot quietly ship against a verb
nobody approved. `docs/design/D10-G2-APPROVAL-2026-09-15.md` is the authority:
its §1.1 and §1.7 tables are the 38-member catalog, and this script reads them
rather than carrying its own copy, so the two cannot drift.

What it asserts:
  1. The document parses, and is OpenAPI 3.1.
  2. The catalog extracted from the approval record has exactly 38 members.
  3. Every operation declares `x-sm-permission`, and its value is either a
     catalog verb or one of three declared non-catalog values.
  4. Every operation declares `x-sm-scope`, naming a level of the FIVE-level
     grant hierarchy (ruling OR-16) or `self`/`derived`/`install`.
     `placement` is rejected: OR-16 makes it operational, not grantable.
  5. Every `x-sm-permission-also` entry is a catalog verb.
  6. No operation references a verb the approval withheld (open item 39).
  7. Every operationId is unique.
  8. Every $ref resolves.
  9. Every authenticated mutation declares the CSRF header in the contract.
 10. Non-catalog markers are pinned to the exact operations approved for them.
 11. OpenAPI 3.1 schemas do not use legacy nullable or malformed property nodes.
 12. Only apply, rollback and stop return an ApplyAccepted payload.
"""
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    print("  SKIP pyyaml not installed (pip install -r contracts/requirements.txt)")
    sys.exit(0)

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
APPROVAL = ROOT / "docs/design/D10-G2-APPROVAL-2026-09-15.md"
SPEC = HERE / "console.yaml"

# Not catalog verbs, and deliberately so. Each is justified at its operation.
NON_CATALOG = {
    "none.establishesSession",   # login: it is what creates the session
    "none.authenticationOnly",   # identity (OR-15), logout, hierarchy reads
    "local.rotateOwnCredential", # LocalOperation.ROTATE_OWN_CREDENTIAL, cp-core-local
}

EXPECTED_NON_CATALOG = {
    "login": "none.establishesSession",
    "logout": "none.authenticationOnly",
    "getIdentity": "none.authenticationOnly",
    "rotateOwnCredential": "local.rotateOwnCredential",
    "listOrganizations": "none.authenticationOnly",
    "listSubOrganizations": "none.authenticationOnly",
    "listEnvironments": "none.authenticationOnly",
}

# The five grantable levels (OR-16) plus the three non-tree values.
# `placement` is deliberately absent — it is operational, not grantable.
SCOPES = {
    "organization", "sub-organization", "environment", "solution", "instance",
    "self", "derived", "install",
}

# Withheld by the approval — open item 39's six remaining letters. An operation
# needing one of these answers 501 and must not appear in this document.
WITHHELD = {
    "instance.undeclare", "instance.version.redeclare",
    "instance.restart.overrideSafety", "package.action.execute",
    "group.view", "group.create", "group.edit", "group.status.edit",
    "group.member.manage", "group.deactivate",
}

rc = 0


def fail(msg):
    global rc
    print(f"  FAIL {msg}")
    rc = 1


def catalog():
    """The 38 verbs, read from the approval record's own numbered tables."""
    rows = []
    for line in APPROVAL.read_text(encoding="utf-8").splitlines():
        m = re.match(r"^\|\s*(\d+)\s*\|\s*`([a-zA-Z.]+)`\s*\|", line)
        if m:
            rows.append((int(m.group(1)), m.group(2)))
    rows.sort()
    return [v for _, v in rows]


def operations(doc):
    methods = {"get", "put", "post", "delete", "patch", "options", "head", "trace"}
    for path, item in (doc.get("paths") or {}).items():
        for method, op in item.items():
            if method in methods:
                yield path, method, op


def refs(node, out):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == "$ref" and isinstance(v, str):
                out.append(v)
            else:
                refs(v, out)
    elif isinstance(node, list):
        for v in node:
            refs(v, out)


def resolve(doc, ref):
    if not ref.startswith("#/"):
        return False
    node = doc
    for part in ref[2:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        if not isinstance(node, dict) or part not in node:
            return False
        node = node[part]
    return True


def validate_schema_shape(node, path="$"):
    """Catch structural mistakes our focused checker must not silently accept."""
    if isinstance(node, dict):
        if "nullable" in node:
            fail(f"{path}: nullable is an OpenAPI 3.0 keyword; use a JSON Schema null union in 3.1")
        properties = node.get("properties")
        if properties is not None:
            if not isinstance(properties, dict):
                fail(f"{path}.properties must be an object")
            else:
                for name, schema in properties.items():
                    if not isinstance(schema, dict):
                        fail(f"{path}.properties.{name} must be a schema object, found {schema!r}")
        for key, value in node.items():
            validate_schema_shape(value, f"{path}.{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            validate_schema_shape(value, f"{path}[{index}]")


def main():
    if not SPEC.exists():
        fail(f"{SPEC.relative_to(ROOT)} is missing")
        return rc
    try:
        doc = yaml.safe_load(SPEC.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        fail(f"console.yaml does not parse: {e}")
        return rc

    version = str(doc.get("openapi", ""))
    if not version.startswith("3.1"):
        fail(f"expected OpenAPI 3.1, found {version!r}")

    verbs = catalog()
    if len(verbs) != 38:
        fail(f"the approval record yielded {len(verbs)} verbs, expected 38 "
             f"— §1.1 + §1.7 are the authority and one of them has drifted")
        return rc
    if len(set(verbs)) != 38:
        dupes = {v for v in verbs if verbs.count(v) > 1}
        fail(f"the catalog has duplicates: {sorted(dupes)}")
    print(f"  PASS catalog read from the approval record: {len(verbs)} verbs")

    allowed = set(verbs) | NON_CATALOG
    ops = list(operations(doc))
    if not ops:
        fail("no operations found")
        return rc

    seen_ids = {}
    actual_non_catalog = {}
    apply_accepted = set()
    for path, method, op in ops:
        where = f"{method.upper()} {path}"

        oid = op.get("operationId")
        if not oid:
            fail(f"{where}: no operationId")
        elif oid in seen_ids:
            fail(f"{where}: operationId {oid!r} already used by {seen_ids[oid]}")
        else:
            seen_ids[oid] = where

        perm = op.get("x-sm-permission")
        if perm is None:
            fail(f"{where}: no x-sm-permission — every operation must name the "
                 f"verb that authorizes it")
        elif perm in WITHHELD:
            fail(f"{where}: x-sm-permission {perm!r} was WITHHELD by the approval "
                 f"(open item 39). Such an operation answers 501, and must not "
                 f"appear in this document")
        elif perm not in allowed:
            fail(f"{where}: x-sm-permission {perm!r} is not in the ratified "
                 f"catalog and is not a declared non-catalog value")
        elif perm in NON_CATALOG and oid:
            actual_non_catalog[oid] = perm

        for also in op.get("x-sm-permission-also", []):
            if also in WITHHELD:
                fail(f"{where}: x-sm-permission-also {also!r} was withheld")
            elif also not in set(verbs):
                fail(f"{where}: x-sm-permission-also {also!r} is not a catalog verb")

        scope = op.get("x-sm-scope")
        if scope is None:
            fail(f"{where}: no x-sm-scope")
        elif scope == "placement":
            fail(f"{where}: x-sm-scope 'placement' is rejected — ruling OR-16 makes "
                 f"Placement operational, not grantable. A placement-targeted "
                 f"operation authorizes at 'instance'")
        elif scope not in SCOPES:
            fail(f"{where}: x-sm-scope {scope!r} is not a grant level "
                 f"(expected one of {sorted(SCOPES)})")

        if method in {"post", "put", "patch", "delete"} and oid != "login":
            parameter_refs = {
                p.get("$ref") for p in op.get("parameters", []) if isinstance(p, dict)
            }
            if "#/components/parameters/CsrfToken" not in parameter_refs:
                fail(f"{where}: authenticated mutation does not declare the X-SM-CSRF header")

        response_refs = []
        refs(op.get("responses", {}), response_refs)
        if "#/components/schemas/ApplyAccepted" in response_refs and oid:
            apply_accepted.add(oid)

    print(f"  PASS {len(ops)} operations, each with a catalog verb and a grant scope")

    if actual_non_catalog != EXPECTED_NON_CATALOG:
        fail("non-catalog operation markers drifted: expected "
             f"{EXPECTED_NON_CATALOG}, found {actual_non_catalog}")
    else:
        print("  PASS non-catalog markers are pinned to the seven approved operations")

    expected_apply_accepted = {"submitApply", "rollbackApply", "stopInstance"}
    if apply_accepted != expected_apply_accepted:
        fail("ApplyAccepted must be returned only by apply, rollback and stop: expected "
             f"{sorted(expected_apply_accepted)}, found {sorted(apply_accepted)}")
    else:
        print("  PASS ApplyAccepted is confined to apply, rollback and stop")

    validate_schema_shape(doc.get("components", {}).get("schemas", {}), "components.schemas")

    bad = []
    found = []
    refs(doc, found)
    for r in sorted(set(found)):
        if not resolve(doc, r):
            bad.append(r)
    if bad:
        for r in bad:
            fail(f"unresolved $ref: {r}")
    else:
        print(f"  PASS {len(set(found))} internal $refs resolve")

    used = {op.get("x-sm-permission") for _, _, op in ops} - NON_CATALOG
    for _, _, op in ops:
        used.update(op.get("x-sm-permission-also", []))
    print(f"  INFO {len(used)} of 38 catalog verbs are exercised by this document")
    return rc


if __name__ == "__main__":
    sys.exit(main())
