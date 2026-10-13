#!/usr/bin/env python3
"""Check 11 — the console OpenAPI against the ratified permission catalog.

The point of this check is that an endpoint cannot quietly ship against a verb
nobody approved. `docs/design/D10-G2-APPROVAL-2026-09-15.md` is the authority:
its §1.1, §1.7, §1.9.1, §1.10.1 and §1.11.1 tables are the 51-member catalog (OR-121, confirmed by name in OR-122; §1.10.1 ruled by
OR-130; §1.11.1 by OR-126 A8, renumbered 48-51 by the OR-147 reconciliation), and this script
reads them rather than carrying its own copy, so the two cannot drift.

What it asserts:
  1. The document parses, and is OpenAPI 3.1.
  2. The catalog extracted from the approval record has exactly 51 members, and a self-test proves a 52nd row, a
     missing row and a repeated row are each refused.
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
 12. Only apply, rollback, stop, restore-runtime and set-log-level return an ApplyAccepted payload.
 13. Every `contracts/view-models/fixtures/viewmodel/*.json` fixture's `expected` object
     validates against `components.schemas.ViewModel` (plan 010 T0.1 point 6, `OR-34`).
 14. No operation's request body schema names an `organizationId` property (`I5`; plan 010
     T0.1 point 21) — the tenant is `Session.organizationId()`, never a request field.
 15. Strength parity (plan 010 T0.1 point 26 / T0.1a, referred to in the plan as check (iv)):
     `ApplyObservation.steps[].gateProved`'s enum equals
     `solution-package.schema.json`'s `$defs/health/properties/probe/properties/strength`
     enum, and `Instance.placements[].probeStrength`'s enum equals that set plus `none` — one
     canonical home for the vocabulary (`UI-D14`), so the two documents cannot drift apart.
 16. The shapes the owner ruled at plan 010 T0.1 Step 7 (2026-09-30, batches 1, 2 and 4) and the
     per-instance acquisition mode (M-Q2 / M-Q2b, 2026-09-29; no host in it, OR-82, 2026-10-05) are what the document says:
     the five point-12..19 shapes, the five-token `Strategy`, the nine-value health vocabulary,
     `Target.kind` beside `transport`, the fleet defaults, and ONE `Acquisition` schema shared by
     the declare body and `Instance` (direct listed first), with no per-placement copy, and neither
     the declare body nor `Instance` carries any host/address property at any depth (`OR-82`).
 17. No description in `console.yaml` still presents a Step 7 item as a pending recommendation
     ("not yet ratified", "awaiting the owner", "recommendation (a)") once the sitting has ruled it —
     a contract that says "awaiting the owner" about a ruled point misleads every generated client.
     A genuinely open item is flagged on its own line with `⚠️ OPEN (owner):` (the only exemption, I4), and a
     self-test proves a flagged item passes while a ruled item called pending fails.
 18. The `/api/console/v1` metrics surface, rows 53-66 of `console-integration.md` section 5.4 (plan 011
     P11-1.2): each of the fourteen operations exists at its path with its verb, grant scope and second verb;
     `queryRange` carries `metrics.forceRaw` and row 55 carries `instance.view`; the typing route is a
     contracted `501`; the dashboard id has one pattern; `Capabilities` names no byte figure (`OQ-16`, `I5`);
     `ErrorCode`'s enum and statuses equal the Java `ErrorCode.java`'s, constant for constant.
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
VIEWMODEL_FIXTURES = ROOT / "contracts/view-models/fixtures/viewmodel"
PACKAGE_SCHEMA = ROOT / "contracts/solution-package.schema.json"

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
    # Row 53 (plan 011 P11-1.2): readable by any authenticated principal of any organization, at install scope.
    "getCapabilities": "none.authenticationOnly",
    # Plan 013 T1: static connection-type catalog, identical for every caller (shaped like getCapabilities).
    "getConnectionTypes": "none.authenticationOnly",
}

# The five grantable levels (OR-16) plus the three non-tree values, plus the fifth
# classification `reach` (ruling OR-51): the verb held at >= 1 grant in the session's
# organization (else 403), with items then filtered by the verb at each item's own scope.
# `named` (plan 013 T1.4): the scope the request's SUBJECT names - a body `ownershipScope`, or the stored
# resource's own scope resolved server-side - evaluated by the interceptor before the handler; never client authority.
# `placement` is deliberately absent — it is operational, not grantable.
SCOPES = {
    "organization", "sub-organization", "environment", "solution", "instance",
    "self", "derived", "install", "reach", "named",
}

# The catalog's size, written once. It is the count of §1.1 (35), §1.7 (3), §1.9.1 (5), §1.10.1 (4) and §1.11.1 (4); `Permission.java`,
# `PermissionCatalogMirrorTest` and `BuiltInRoleSeedIT` hold the same number, and a change to it is an owner ruling.
CATALOG_SIZE = 51

# Withheld by the approval — open item 39's three remaining letters (c), (e), (i). An operation
# needing one of these answers 501 and must not appear in this document. OR-121 moved
# `instance.undeclare` and `instance.restart.overrideSafety` into the catalog (§1.9.1).
WITHHELD = {
    "instance.version.redeclare", "package.action.execute",
    "group.view", "group.create", "group.edit", "group.status.edit",
    "group.member.manage", "group.deactivate",
}

rc = 0
failures = 0  # how many fail() calls so far: a check compares before and after, even when rc is already 1


def fail(msg):
    global rc, failures
    print(f"  FAIL {msg}")
    rc = 1
    failures += 1


def catalog_from(text):
    """The catalog verbs, read from the approval record's own numbered tables, in row order."""
    rows = []
    for line in text.splitlines():
        m = re.match(r"^\|\s*(\d+)\s*\|\s*`([a-zA-Z.]+)`\s*\|", line)
        if m:
            rows.append((int(m.group(1)), m.group(2)))
    rows.sort()
    return [v for _, v in rows]


def catalog():
    """The catalog verbs of the approval record (§1.1, §1.7, §1.9.1 and §1.10.1)."""
    return catalog_from(APPROVAL.read_text(encoding="utf-8"))


def catalog_problems(verbs):
    """Why `verbs` is not the ratified catalog: a wrong count, or a repeated verb. Empty when it is sound."""
    problems = []
    if len(verbs) != CATALOG_SIZE:
        problems.append(f"the approval record yielded {len(verbs)} verbs, expected {CATALOG_SIZE} "
                        f"— §1.1 + §1.7 + §1.9.1 + §1.10.1 are the authority and one of them has drifted")
    dupes = {v for v in verbs if verbs.count(v) > 1}
    if dupes:
        problems.append(f"the catalog has duplicates: {sorted(dupes)}")
    return problems


def check_catalog_selftest(text):
    """Check 11's self-test: a 48th row, a missing row and a repeated row are each refused; the real record passes."""
    base = catalog_from(text)
    if catalog_problems(base):
        return  # the real record is reported by the caller; planting on a broken base proves nothing
    before = failures
    planted = catalog_from(text + "\n| 99 | `instance.explode` | planted |\n")
    if not catalog_problems(planted):
        fail(f"check 11 self-test: a {CATALOG_SIZE + 1}th catalog row was NOT refused")
    removed = catalog_from("\n".join(
        line for line in text.splitlines() if not re.match(rf"^\|\s*{CATALOG_SIZE}\s*\|\s*`", line)))
    if not catalog_problems(removed):
        fail("check 11 self-test: a catalog with its last row missing was NOT refused")
    repeated = catalog_from(text + "\n| 98 | `" + base[0] + "` | planted |\n")
    if not any("duplicates" in p for p in catalog_problems(repeated)):
        fail("check 11 self-test: a repeated catalog verb was NOT refused")
    if failures == before:
        print(f"  PASS check 11 self-test: a {CATALOG_SIZE + 1}th row, a missing row and a repeated row are refused")


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


def resolve_node(doc, ref):
    """Like `resolve`, but returns the referenced node itself (or None), not a bool."""
    if not ref.startswith("#/"):
        return None
    node = doc
    for part in ref[2:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    return node


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


def check_view_model_fixtures(doc):
    """Check 13: every viewmodel fixture's `expected` validates against `ViewModel`.

    Plan 010 T0.1 point 6 (`OR-34`): the Java `project()` port's output is transcribed into
    `components.schemas.ViewModel`. If the two drift, a fixture the TS/Java engines already
    agree on would fail against the contract the frontend generates types from — exactly the
    kind of split check 11 exists to catch, one level up (schema vs. schema, not code vs.
    schema).
    """
    schema = doc.get("components", {}).get("schemas", {}).get("ViewModel")
    if schema is None:
        fail("components.schemas.ViewModel is missing")
        return
    try:
        from jsonschema import Draft202012Validator
        from referencing import Registry, Resource
        from referencing.jsonschema import DRAFT202012
    except ImportError:
        print("  SKIP check 13: jsonschema/referencing not installed "
              "(pip install -r contracts/requirements.txt)")
        return
    if not VIEWMODEL_FIXTURES.is_dir():
        fail(f"{VIEWMODEL_FIXTURES.relative_to(ROOT)} is missing")
        return

    import json

    resource = Resource.from_contents(doc, default_specification=DRAFT202012)
    registry = Registry().with_resource("urn:sm-console", resource)
    validator = Draft202012Validator(
        {"$ref": "urn:sm-console#/components/schemas/ViewModel"}, registry=registry)

    fixtures = sorted(VIEWMODEL_FIXTURES.glob("*.json"))
    if not fixtures:
        fail(f"no fixtures found under {VIEWMODEL_FIXTURES.relative_to(ROOT)}")
        return

    checked = 0
    for path in fixtures:
        fixture = json.loads(path.read_text(encoding="utf-8"))
        expected = fixture.get("expected")
        if expected is None:
            fail(f"{path.relative_to(ROOT)}: no top-level 'expected' key")
            continue
        errors = list(validator.iter_errors(expected))
        if errors:
            for e in errors[:3]:
                pointer = "/".join(str(p) for p in e.path) or "<root>"
                fail(f"{path.relative_to(ROOT)} at {pointer}: {e.message}")
        else:
            checked += 1
    if checked == len(fixtures):
        print(f"  PASS {checked} view-model fixtures validate against components.schemas.ViewModel")


def check_no_organization_id_in_request_bodies(doc, ops):
    """Check 14: no request body schema names `organizationId` (`I5`).

    The tenant is `Session.organizationId()`, resolved server-side before the body is read
    (`ResourceResolver` javadoc) — never a request field. A body that named one could never be
    honoured, so its presence in the contract would only mislead a client into sending it.

    Walks the whole body, not just its top level: through `$ref` (resolved against
    `doc`, cycle-guarded) and into nested `properties`/`items`, not only the
    allOf/oneOf/anyOf composition — a `$ref`'d request body (e.g. `FleetApplyRequest`) or a
    nested field (e.g. `members[].placement.organizationId`) is exactly as much a violation
    as an inline top-level property.
    """
    offenders = []
    for path, method, op in ops:
        body = op.get("requestBody", {}).get("content", {}).get("application/json", {}).get("schema", {})
        properties = _all_schema_properties(doc, body)
        if "organizationId" in properties:
            offenders.append(f"{method.upper()} {path}")
    if offenders:
        fail(f"request body names 'organizationId' (I5), which must never be a request field: "
             f"{offenders}")
    else:
        print("  PASS no operation's request body names an organizationId field")


def check_strength_parity(doc):
    """Check 15 (plan 010 T0.1's remaining-work list calls this check (iv)): the strength
    vocabulary has exactly one canonical home — `solution-package.schema.json`'s
    `$defs/health/properties/probe/properties/strength` enum (`UI-D14`) — and both console.yaml
    copies of it must mirror that enum exactly, never drift into their own separate list.

    `ApplyObservation.steps[].gateProved` mirrors it as-is (present only on a step whose gate
    passed on an SM-executed probe of that strength). `Instance.placements[].probeStrength`
    mirrors it plus `none` (a placement whose package declares no probe at all).
    """
    import json

    if not PACKAGE_SCHEMA.exists():
        fail(f"{PACKAGE_SCHEMA.relative_to(ROOT)} is missing")
        return
    package_schema = json.loads(PACKAGE_SCHEMA.read_text(encoding="utf-8"))
    try:
        strength_enum = (
            package_schema["$defs"]["health"]["properties"]["probe"]["properties"]["strength"]["enum"]
        )
    except (KeyError, TypeError):
        fail(
            "solution-package.schema.json: "
            "$defs/health/properties/probe/properties/strength/enum is missing"
        )
        return
    strength_set = set(strength_enum)

    property_of = lambda node, name: _prop(doc, node, name)
    items_of = lambda node: _items(doc, node)

    schemas = doc.get("components", {}).get("schemas", {})
    steps_schema = property_of(schemas.get("ApplyObservation"), "steps")
    step_item_schema = items_of(steps_schema) if steps_schema is not None else None
    gate_proved_schema = (
        property_of(step_item_schema, "gateProved") if step_item_schema is not None else None
    )
    gate_proved = gate_proved_schema.get("enum") if isinstance(gate_proved_schema, dict) else None

    placements_schema = property_of(schemas.get("Instance"), "placements")
    placement_item_schema = items_of(placements_schema) if placements_schema is not None else None
    probe_strength_schema = (
        property_of(placement_item_schema, "probeStrength")
        if placement_item_schema is not None
        else None
    )
    probe_strength = (
        probe_strength_schema.get("enum") if isinstance(probe_strength_schema, dict) else None
    )

    if gate_proved is None:
        fail(
            "components.schemas.ApplyObservation.properties.steps.items.properties."
            "gateProved.enum is missing"
        )
    elif set(gate_proved) != strength_set:
        fail(
            f"ApplyObservation.steps[].gateProved's enum {sorted(gate_proved)} does not match "
            f"solution-package.schema.json's strength enum {sorted(strength_set)}"
        )
    else:
        print("  PASS ApplyObservation.steps[].gateProved mirrors the package schema's strength enum")

    if probe_strength is None:
        fail(
            "components.schemas.Instance.properties.placements.items.properties."
            "probeStrength.enum is missing"
        )
    elif set(probe_strength) != strength_set | {"none"}:
        fail(
            f"Instance.placements[].probeStrength's enum {sorted(probe_strength)} does not "
            f"match solution-package.schema.json's strength enum plus 'none' "
            f"{sorted(strength_set | {'none'})}"
        )
    else:
        print(
            "  PASS Instance.placements[].probeStrength mirrors the package schema's "
            "strength enum plus 'none'"
        )


def _prop(doc, node, name):
    """The named property's schema, resolving `$ref` and merging across `allOf` members."""
    if isinstance(node, dict) and isinstance(node.get("$ref"), str):
        node = resolve_node(doc, node["$ref"])
    if not isinstance(node, dict):
        return None
    props = node.get("properties")
    if isinstance(props, dict) and name in props:
        return props[name]
    for part in node.get("allOf", []) or []:
        found = _prop(doc, part, name)
        if found is not None:
            return found
    return None


def _required(doc, node):
    """Union of `required` across a schema and its `allOf` members (refs resolved)."""
    if isinstance(node, dict) and isinstance(node.get("$ref"), str):
        node = resolve_node(doc, node["$ref"])
    out = set()
    if isinstance(node, dict):
        out.update(node.get("required", []) or [])
        for part in node.get("allOf", []) or []:
            out.update(_required(doc, part))
    return out


def _items(doc, node):
    if isinstance(node, dict) and isinstance(node.get("$ref"), str):
        node = resolve_node(doc, node["$ref"])
    if not isinstance(node, dict):
        return None
    if "items" in node:
        return node["items"]
    for part in node.get("allOf", []) or []:
        found = _items(doc, part)
        if found is not None:
            return found
    return None


def _op(doc, operation_id):
    for _, _, op in operations(doc):
        if op.get("operationId") == operation_id:
            return op
    return None


def _body(doc, operation_id):
    op = _op(doc, operation_id)
    if op is None:
        return None
    return op.get("requestBody", {}).get("content", {}).get("application/json", {}).get("schema")


def check_ruled_shapes(doc):
    """Check 16: the Step 7 sitting's rulings, and M-Q2/M-Q2b, as structure."""
    before = rc
    schemas = doc.get("components", {}).get("schemas", {})
    NINE = {"GREEN", "RED", "RESTART_EXPECTED", "INACTIVE_REPLICA", "NOT_APPLICABLE",
            "unreachable", "not_exposed", "missing", "bootstrap"}

    def need(cond, msg):
        if not cond:
            fail(f"check 16: {msg}")

    # Batch 1 — points 12, 16, 17, 18, 19 and the Strategy enum.
    need({"placementId", "strategy"} <= _required(doc, _body(doc, "rollbackApply")),
         "rollbackApply must require placementId and strategy (point 12)")
    attention_item = _items(doc, _prop(doc, schemas.get("HomeAttention"), "items"))
    need("stateVersion" in _required(doc, attention_item),
         "getHomeAttention items must each require stateVersion (point 16)")
    need(set((_prop(doc, attention_item, "reason") or {}).get("enum", []))
         == {"health", "apply-indeterminate", "config-diverged"},
         "getHomeAttention reason enum must be health / apply-indeterminate / config-diverged (Q-C2)")
    need(set((_prop(doc, attention_item, "state") or {}).get("enum", [])) == NINE,
         "getHomeAttention state must be the nine-value vocabulary (Q-C1-vocab)")
    secret = (_op(doc, "setConfigSecret") or {}).get("responses", {})
    need(set(secret) >= {"200"} and "201" not in secret and "202" not in secret
         and secret.get("200", {}).get("content", {}).get("application/json", {}).get("schema")
         == {"$ref": "#/components/schemas/StagedConfig"},
         "setConfigSecret must answer 200 StagedConfig, one call (point 17)")
    need("placementId" in _required(doc, _body(doc, "acceptRuntimeConfig")),
         "acceptRuntimeConfig must require placementId (point 18)")
    # OR-104 (D-ACCEPT): accept-runtime's 200 is AcceptedRuntimeConfig, its report lists paths and closed reasons, a secret
    # carries at most same/differs, and an unreadable host is a declared 503 (never an undeclared 500).
    accept = _op(doc, "acceptRuntimeConfig") or {}
    need(accept.get("responses", {}).get("200", {}).get("content", {}).get("application/json", {}).get("schema")
         == {"$ref": "#/components/schemas/AcceptedRuntimeConfig"},
         "acceptRuntimeConfig must answer 200 AcceptedRuntimeConfig (OR-104)")
    need("503" in accept.get("responses", {}),
         "acceptRuntimeConfig must declare 503 SERVICE_UNAVAILABLE: an unreadable host (OR-104 Q-A5)")
    accepted = schemas.get("AcceptedRuntimeConfig")
    need({"unchanged", "placementId", "fileBytesDifferFromRendered", "truncated", "notAccepted"}
         <= _required(doc, accepted),
         "AcceptedRuntimeConfig must require unchanged, placementId, fileBytesDifferFromRendered, truncated, notAccepted")
    not_accepted = _prop(doc, accepted, "notAccepted") or {}
    need(not_accepted.get("maxItems") == 200, "AcceptedRuntimeConfig.notAccepted must be capped at 200 (OR-104 Q-A1)")
    entry = _items(doc, not_accepted) or {}
    need(set((_prop(doc, entry, "reason") or {}).get("enum", []))
         == {"secret-not-read", "undeclared-key", "sm-owned-overlay", "inactive-field", "yaml-field-not-accepted"},
         "notAccepted reason must be the five D-ACCEPT reasons")
    need(set((_prop(doc, entry, "comparison") or {}).get("enum", [])) == {"same", "differs"},
         "notAccepted comparison must be exactly same / differs: the secret report names no value (OR-104 Q-A2, I6)")
    need(_prop(doc, entry, "value") is None and entry.get("additionalProperties") is False,
         "a notAccepted entry must carry no value property and be closed (I6)")
    # S811-1: a rollback by applyId UNDOES that apply (the highest eligible generation BELOW it), it is not "equivalent" to
    # naming the generation. The wording is the contract an integrator builds a console from; it must not drift back.
    rollback_op = _op(doc, "rollbackApply") or {}
    rollback_apply_id = _prop(doc, _body(doc, "rollbackApply"), "applyId") or {}
    rollback_text = " ".join(rollback_op.get("description", "").split())
    need("two equivalent ways" not in rollback_text and "undo that apply" in rollback_text
         and "BELOW that apply's generation" in rollback_text,
         "rollbackApply must say applyId undoes that apply (the highest eligible generation BELOW it), not that the two "
         "ways are equivalent (S811-1)")
    apply_id_text = " ".join(rollback_apply_id.get("description", "").split())
    need("Undo that apply" in apply_id_text and "BELOW that apply's generation" in apply_id_text,
         "rollbackApply.applyId must describe 'undo that apply', the highest eligible generation BELOW it (S811-1)")
    # S811-3: two served operations answer 422 VALIDATION_FAILED; an undeclared error is an unclassified one.
    for operation_id in ("setConfigSecret", "restoreRuntimeConfig"):
        unprocessable = (_op(doc, operation_id) or {}).get("responses", {}).get("422", {})
        need(unprocessable.get("content", {}).get("application/problem+json", {}).get("schema")
             == {"$ref": "#/components/schemas/Problem"},
             f"{operation_id} must declare 422 VALIDATION_FAILED as application/problem+json Problem (S811-3)")
    need("NEVER carries the value" in (_op(doc, "setConfigSecret") or {}).get("responses", {}).get("422", {})
         .get("description", ""),
         "setConfigSecret's 422 must say the detail never carries the value (I6, S811-3)")
    # S811-10: plan T9.1 — the contract says SM does not redact log lines, and logs.read is the control.
    logs_description = (_op(doc, "readLogs") or {}).get("description", "")
    need("does not redact" in logs_description and "logs.read" in logs_description,
         "readLogs must state that SM does not redact log lines and that logs.read is the control (plan T9.1, S811-10)")
    restore = _body(doc, "restoreRuntimeConfig")
    need("placementId" in _required(doc, restore) and "strategy" not in _required(doc, restore),
         "restoreRuntimeConfig requires placementId and leaves strategy optional (point 19)")
    restore_strategy = _prop(doc, restore, "strategy") or {}
    need(restore_strategy.get("default") == "manual"
         and {"$ref": "#/components/schemas/Strategy"} in (restore_strategy.get("allOf") or []),
         "restoreRuntimeConfig.strategy must be Strategy with default manual (point 19)")
    need("instance.restart" in (_op(doc, "restoreRuntimeConfig") or {}).get("x-sm-permission-also", []),
         "restoreRuntimeConfig must list instance.restart (required when the strategy restarts)")
    need(schemas.get("Strategy", {}).get("enum")
         == ["manual", "rolling-health-gated", "restart-all", "rolling-no-validation", "scheduled"],
         "Strategy must be all five engine tokens (batch 1)")

    # Batch 2 — Q-C1, Q-C1-vocab, Q-C3. Legacy ssh-docker targets and linked
    # runtime-aware ssh targets are both valid.
    summary_item = _items(doc, _prop(doc, schemas.get("HomeSummary"), "solutions"))
    by_health = _prop(doc, summary_item, "byHealth") or {}
    need(set(by_health.get("propertyNames", {}).get("enum", [])) == NINE,
         "HomeSummary byHealth keys must be the nine-value vocabulary (Q-C1-vocab)")
    need("byHealth" not in _required(doc, summary_item),
         "byHealth must be optional — absent where health.view is held at no visible instance (Q-C1)")
    need({"kind", "transport"} <= _required(doc, schemas.get("Target")),
         "Target must require both kind and transport (Q-C3)")
    need((_prop(doc, schemas.get("Target"), "kind") or {}).get("enum") == ["docker", "systemd", "kubernetes", "vm"]
         and set((_prop(doc, schemas.get("Target"), "transport") or {}).get("enum", [])) == {"ssh-docker", "ssh", "agent"},
         "Target.kind keeps runtime beside legacy ssh-docker, linked ssh, and agent transport (Q-C3)")
    need(set((_prop(doc, schemas.get("RegisterTargetRequest"), "transport") or {}).get("enum", [])) == {"ssh-docker", "ssh"},
         "registerTarget permits direct ssh-docker and linked ssh targets, but not agent targets")
    conn = schemas.get("TargetConnection", {})
    need([r.get("$ref") for r in conn.get("oneOf", [])] == ["#/components/schemas/SshDockerTargetConnection",
                                                              "#/components/schemas/AgentTargetConnection"]
         and conn.get("discriminator", {}).get("propertyName") == "transport"
         and set(conn.get("discriminator", {}).get("mapping", {})) == {"ssh-docker", "agent"},
         "TargetConnection is a union discriminated on transport: SshDockerTargetConnection | AgentTargetConnection (plan 015 T3.5)")
    need((_op(doc, "editTargetConnection") or {}).get("responses", {}).get("200", {}).get("content", {})
         .get("application/json", {}).get("schema") == {"$ref": "#/components/schemas/SshDockerTargetConnection"}
         and any(part.get("properties", {}).get("connection") == {"$ref": "#/components/schemas/SshDockerTargetConnection"}
                 for part in schemas.get("TargetDetail", {}).get("allOf", [])),
         "the two ssh-only answers (editTargetConnection 200, TargetDetail.connection) name SshDockerTargetConnection, not the union")

    # Batch 4 — the contract-visible fleet rulings (Q-F4, Q-F5, Q-F7).
    fleet = schemas.get("FleetApplyRequest", {})
    need(fleet.get("properties", {}).get("members", {}).get("maxItems") == 100, "fleet members maxItems 100 (Q-F4)")
    need(fleet.get("properties", {}).get("batchSize", {}).get("default") == 1, "fleet batchSize default 1 (Q-F4)")
    need(fleet.get("properties", {}).get("onFailure", {}).get("default") == "halt-remaining",
         "fleet onFailure default halt-remaining (Q-F4)")
    need((_op(doc, "submitFleetApply") or {}).get("responses", {}).get("403", {}).get("content", {})
         .get("application/problem+json", {}).get("schema") == {"$ref": "#/components/schemas/FleetRefused"},
         "submitFleetApply 403 is always FleetRefused (Q-F5)")
    fleet_ops = sorted(op.get("operationId") for _, _, op in operations(doc) if "fleet" in op.get("tags", []))
    need(fleet_ops == ["observeFleetApply", "submitFleetApply"],
         f"no fleet cancel/halt operation in v1 (Q-F7), found {fleet_ops}")

    # M-Q2 / M-Q2b — ONE acquisition schema, at instance level, direct offered first.
    acquisition = schemas.get("Acquisition")
    need(isinstance(acquisition, dict), "components.schemas.Acquisition is missing (M-Q2)")
    if isinstance(acquisition, dict):
        mode = (acquisition.get("properties", {}).get("mode") or {})
        need(mode.get("enum") == ["direct-http", "via-ssh", "agent"],
             f"Acquisition.mode must be exactly [direct-http, via-ssh, agent], direct first (M-Q2; agent is plan 015 T4.3's, never operator-chosen), found {mode.get('enum')}")
        need((acquisition.get("required") or []) == ["mode"],
             "Acquisition requires exactly mode (OR-82: the declare body carries no host)")
        need(sorted(acquisition.get("properties", {})) == ["mode"],
             "Acquisition has exactly one property, mode (OR-82): direct-http polls the TARGET's registered "
             f"address, nothing about the address comes from the browser; found {sorted(acquisition.get('properties', {}))}")
        need(not any(k in acquisition for k in ("if", "then", "else", "oneOf", "anyOf", "allOf")),
             "Acquisition must not tie a host to a mode (OR-82): no if/then/else or composition remains")
        need(acquisition.get("additionalProperties") is False, "Acquisition must be closed (additionalProperties: false)")
    ref = {"$ref": "#/components/schemas/Acquisition"}

    def is_acquisition(node):
        """A direct `$ref`, or `allOf: [$ref]` carrying a description (the file's own pattern)."""
        return isinstance(node, dict) and (node == ref or ref in (node.get("allOf") or []))

    declare_body = _body(doc, "declareInstance")
    need("acquisition" in _required(doc, declare_body), "declareInstance must require acquisition (M-Q2)")
    need(is_acquisition(_prop(doc, declare_body, "acquisition")), "declareInstance.acquisition must $ref Acquisition")
    need("acquisition" in _required(doc, schemas.get("Instance")), "Instance must require acquisition (M-Q2)")
    need(is_acquisition(_prop(doc, schemas.get("Instance"), "acquisition")), "Instance.acquisition must $ref Acquisition")
    declare_placement = _prop(doc, declare_body, "placement")
    need("healthAcquisition" not in (declare_placement or {}).get("properties", {}),
         "declare placement must not carry healthAcquisition — M-Q2b makes the mode instance-level, one setting")
    placement_item = _items(doc, _prop(doc, schemas.get("Instance"), "placements"))
    need("healthAcquisition" not in (placement_item or {}).get("properties", {}),
         "Instance.placements[] must not carry healthAcquisition — one setting, one place (M-Q2b)")
    # OR-82: no address of any kind in the declare body or in what Instance returns. Every property name,
    # however deeply nested (through $ref, items, allOf), is scanned: a host smuggled back in as `host`,
    # `acquisitionHost`, `address`, `endpoint` or `url` anywhere on those two shapes fails the check.
    address_like = re.compile(r"host|address|endpoint|url", re.IGNORECASE)
    for label, node in (("the declareInstance body", declare_body), ("Instance", schemas.get("Instance"))):
        found = sorted(n for n in _all_schema_properties(doc, node) if address_like.search(n))
        need(not found,
             f"{label} must carry no host/address property (OR-82: direct-http polls the target's registered "
             f"address, nothing about the address comes from the browser); found {found}")
    # The acquisition schema's own behaviour: a closed two-value mode and nothing else.
    try:
        from jsonschema import Draft202012Validator
        from referencing import Registry, Resource
        from referencing.jsonschema import DRAFT202012
    except ImportError:
        print("  SKIP check 16 (Acquisition behaviour): jsonschema/referencing not installed")
    else:
        registry = Registry().with_resource(
            "urn:sm-console", Resource.from_contents(doc, default_specification=DRAFT202012))
        validator = Draft202012Validator(
            {"$ref": "urn:sm-console#/components/schemas/Acquisition"}, registry=registry)
        accepted = [
            {"mode": "direct-http"},
            {"mode": "via-ssh"},
        ]
        refused = [
            ({"mode": "fallback"}, "an unknown mode"),
            ({}, "no mode"),
            ({"mode": "direct-http", "host": "10.0.0.5"}, "a host under direct-http (OR-82)"),
            ({"mode": "via-ssh", "host": "h"}, "a host under via-ssh"),
            ({"mode": "via-ssh", "extra": 1}, "an undeclared property"),
        ]
        for case in accepted:
            need(not list(validator.iter_errors(case)), f"Acquisition must accept {case}")
        for case, why in refused:
            need(bool(list(validator.iter_errors(case))), f"Acquisition must refuse {why}: {case}")
    if rc == before:
        print("  PASS the Step 7 rulings and the per-instance acquisition mode (no host, OR-82) are what the document says")


PENDING_MARKERS = re.compile(
    r"not\s+(yet\s+)?ratified|awaiting\s+the\s+owner|open\s+for\s+the\s+owner|recommend(ed|ation)\b"
    r"|Step[\s-]7\s+(divergence|\(not)|not\s+(yet\s+)?served",
    re.IGNORECASE)


# An item the owner has NOT ruled is flagged on the line that carries this marker (I4: flag, don't
# silently resolve). The marker exempts only its own line, so a ruled item described as pending
# anywhere else in the file is still refused.
OPEN_FLAG = "\u26a0\ufe0f OPEN (owner):"


def pending_marker_hits(raw_text):
    """Lines that present a ruled item as pending, ignoring lines that carry `OPEN_FLAG`."""
    hits = []
    lines = raw_text.splitlines()
    for number, line in enumerate(lines, 1):
        if OPEN_FLAG in line:
            continue
        if PENDING_MARKERS.search(line):
            hits.append(f"{number}: {line.strip()[:110]}")
    # Fable review (code-GW-1): a marker wrapped across two lines ("awaiting\nthe owner") escapes the per-line scan above, so
    # each paragraph is also scanned with its whitespace collapsed. A paragraph that carries OPEN_FLAG is exempt, as its line is.
    paragraph = []
    first = 1
    for number, line in enumerate(lines + [""], 1):
        if line.strip():
            if not paragraph:
                first = number
            paragraph.append(line.strip())
            continue
        text = " ".join(paragraph)
        paragraph = []
        if text and OPEN_FLAG not in text and PENDING_MARKERS.search(text) \
                and not any(h.startswith(f"{n}:") for n in range(first, number) for h in hits):
            hits.append(f"{first}: {text[:110]}")
    return hits


def check_pending_marker_selftest():
    """Check 17's self-test: a flagged open item passes; a ruled item called pending fails."""
    flagged = f"description: {OPEN_FLAG} the host policy is open for the owner."
    ruled = "description: Strategy is not yet ratified; awaiting the owner."
    ok = True
    if pending_marker_hits(flagged):
        fail("check 17 self-test: a line carrying the open-item flag was refused")
        ok = False
    if not pending_marker_hits(ruled):
        fail("check 17 self-test: a ruled item called pending was NOT refused")
        ok = False
    if not pending_marker_hits("description: |\n  Reading recorded in the inventory, awaiting\n  the owner.\n"):
        fail("check 17 self-test: a marker wrapped across two lines was NOT refused")
        ok = False
    if not pending_marker_hits("description: not yet served by any plan."):
        fail("check 17 self-test: 'not yet served' was NOT refused")
        ok = False
    if not pending_marker_hits(flagged.replace(OPEN_FLAG, "OPEN:")):
        fail("check 17 self-test: an unmarked open-for-the-owner line was NOT refused")
        ok = False
    if ok:
        print("  PASS check 17 self-test: a flagged open item passes, a ruled item called pending fails")


def check_no_pending_markers(raw_text):
    """Check 17: the Step 7 sitting is COMPLETE (batches 1-5, 2026-09-30 and 2026-10-05), so no
    description may still present one of its items as an open recommendation. Scans the raw text
    (comments and prose alike) of `console.yaml`. A genuinely open item is flagged with
    `OPEN_FLAG` on its own line and is the only exemption; the self-test below proves both
    directions."""
    check_pending_marker_selftest()
    hits = pending_marker_hits(raw_text)
    if hits:
        fail(f"check 17: {len(hits)} line(s) still present a ruled item as pending: " + "; ".join(hits[:6])
             + (" ..." if len(hits) > 6 else ""))
    else:
        print("  PASS no description presents a ruled Step 7 item as pending")


def _all_schema_properties(doc, node, _seen_refs=None):
    """Every property name declared anywhere in a schema node.

    Recurses through `$ref` (resolved against `doc`, guarded against cycles),
    `properties` (recursing into each property's own schema, not just collecting its
    name), `items` (array element schema), and the `allOf`/`oneOf`/`anyOf` compositions.

    Known limit (not a live escape — no body in this document uses these constructs
    today): does not follow `additionalProperties` when it is itself a schema,
    `patternProperties`, `prefixItems`, `not`, `if`/`then`/`else`, or `dependentSchemas`.
    A body that named `organizationId` only inside one of those would pass unnoticed.
    Only `application/json` request bodies are read at all.
    """
    if _seen_refs is None:
        _seen_refs = set()
    names = set()
    if isinstance(node, dict):
        ref = node.get("$ref")
        if isinstance(ref, str):
            if ref in _seen_refs:
                return names
            resolved = resolve_node(doc, ref)
            if resolved is not None:
                names.update(_all_schema_properties(doc, resolved, _seen_refs | {ref}))
            return names
        properties = node.get("properties")
        if isinstance(properties, dict):
            for name, schema in properties.items():
                names.add(name)
                names.update(_all_schema_properties(doc, schema, _seen_refs))
        items = node.get("items")
        if isinstance(items, dict):
            names.update(_all_schema_properties(doc, items, _seen_refs))
        for keyword in ("allOf", "oneOf", "anyOf"):
            for part in node.get(keyword, []) or []:
                names.update(_all_schema_properties(doc, part, _seen_refs))
    return names


# Check 18. Rows 53-66 of console-integration.md section 5.4 (plan 011 P11-1.2, M-Q10/M-Q11/M-Q16/M-Q17):
# operationId -> (HTTP method, path under the `/api` server, x-sm-permission, x-sm-scope, x-sm-permission-also).
# The verbs are the ratified catalog's (D10-G2-APPROVAL); the scopes are M-Q17 (OR-79: rows 54-60 are `reach`),
# the plan (row 61 `organization`, rows 62-66 `instance`, resolved from the dashboardId path parameter).
METRICS_SURFACE = {
    "getCapabilities": ("get", "/console/v1/capabilities", "none.authenticationOnly", "install", ()),
    "listEntityTypes": ("get", "/console/v1/entity-types", "metrics.query", "reach", ()),
    "listMetricInstances": ("get", "/console/v1/entity-types/{entityType}/instances", "metrics.query", "reach",
                            ("instance.view",)),
    "getMetricsCatalog": ("get", "/console/v1/metrics/catalog", "metrics.query", "reach", ()),
    "getLabelValues": ("get", "/console/v1/labels/{labelKey}/values", "metrics.query", "reach", ()),
    "queryRange": ("post", "/console/v1/query/range", "metrics.query", "reach", ("metrics.forceRaw",)),
    "queryInstant": ("post", "/console/v1/query/instant", "metrics.query", "reach", ()),
    "exportRange": ("get", "/console/v1/export/range", "metrics.export", "reach", ()),
    "declareMetricType": ("post", "/console/v1/metrics/catalog/{name}/type", "metric.declare", "organization", ()),
    "getDashboard": ("get", "/console/v1/dashboards/{dashboardId}", "dashboard.view", "instance", ()),
    "saveWidget": ("post", "/console/v1/dashboards/{dashboardId}/widgets", "dashboard.edit", "instance", ()),
    "setDashboardLayout": ("put", "/console/v1/dashboards/{dashboardId}/layout", "dashboard.edit", "instance", ()),
    "setWidgetHidden": ("patch", "/console/v1/dashboards/{dashboardId}/widgets/{widgetId}", "dashboard.edit",
                        "instance", ()),
    "resetDashboard": ("post", "/console/v1/dashboards/{dashboardId}/reset", "dashboard.reset", "instance", ()),
}

# The one dashboard id (DemoDashboardStore: `dashboard_id = 'db_' || instance_id`, P11-2.3's CHECK).
DASHBOARD_ID_PATTERN = "^db_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"

# The error codes the metrics surface adds, and the status each carries (plan 011 P11-1.2, item 6).
METRICS_ERROR_CODES = {
    "SERIES_CAP_EXCEEDED": 422, "AGGREGATION_NOT_APPLICABLE": 422, "GROUP_BY_REQUIRES_AGGREGATION": 422,
    "STAT_REQUIRES_AGGREGATION": 422, "METRIC_UNTYPED": 422, "EXPORT_TOO_LARGE": 422,
    "PACKAGE_VERSION_MISMATCH": 409, "METRICS_PORT_CONFLICT": 409,
}

ERROR_CODE_JAVA = (ROOT / "backend/console-gateway/src/main/java/com/solace/sm/gateway/http/ErrorCode.java")


def java_error_codes(text):
    """`NAME(status)` constants of the Java `ErrorCode` enum, in source order (comments stripped)."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    text = re.sub(r"//[^\n]*", "", text)
    return {m.group(1): int(m.group(2)) for m in re.finditer(r"\b([A-Z][A-Z_]+)\((\d{3})\)\s*[,;]", text)}


def check_metrics_surface(doc):
    """Check 18: the `/api/console/v1` surface is exactly rows 53-66, as ruled (plan 011 P11-1.2)."""
    before = failures
    schemas = doc.get("components", {}).get("schemas", {})
    paths = doc.get("paths") or {}

    # 1. Exactly the fourteen operations live under /console/v1/, each at its path, verb and scope.
    under = {}
    for path, method, op in operations(doc):
        if path.startswith("/console/v1/"):
            under[op.get("operationId")] = (method, path)
    if set(under) != set(METRICS_SURFACE):
        fail(f"check 18: the /console/v1 operations are {sorted(map(str, under))}, expected exactly "
             f"{sorted(METRICS_SURFACE)} (console-integration.md section 5.4 rows 53-66)")
    for oid, (method, path, verb, scope, also) in METRICS_SURFACE.items():
        op = (paths.get(path) or {}).get(method)
        if op is None:
            fail(f"check 18: {oid}: no {method.upper()} {path}")
            continue
        if op.get("operationId") != oid:
            fail(f"check 18: {method.upper()} {path} is {op.get('operationId')!r}, expected {oid}")
        if op.get("x-sm-permission") != verb:
            fail(f"check 18: {oid}: verb {op.get('x-sm-permission')!r}, expected {verb!r}")
        if op.get("x-sm-scope") != scope:
            fail(f"check 18: {oid}: scope {op.get('x-sm-scope')!r}, expected {scope!r}")
        if tuple(op.get("x-sm-permission-also", [])) != also:
            fail(f"check 18: {oid}: x-sm-permission-also {op.get('x-sm-permission-also')!r}, expected {list(also)}")
        responses = op.get("responses", {})
        for status in ("401",) + (("403",) if verb != "none.authenticationOnly" else ()):
            if status not in responses:
                fail(f"check 18: {oid}: declares no {status}")
        if scope == "instance" and "404" not in responses:
            fail(f"check 18: {oid}: an instance-scoped operation must declare 404 (existence is not leaked)")

    # 2. Row 61 is a contracted refusal, and the export is CSV with its two refusals.
    declare = (paths.get("/console/v1/metrics/catalog/{name}/type") or {}).get("post") or {}
    if "501" not in declare.get("responses", {}):
        fail("check 18: declareMetricType must answer 501 NOT_IMPLEMENTED (M-Q6, OR-75: quarantine is deferred)")
    elif "metric-declare-deferred" not in str(declare["responses"]["501"].get("description", "")):
        fail("check 18: declareMetricType's 501 must name its reason, metric-declare-deferred (M-Q6)")
    export = (paths.get("/console/v1/export/range") or {}).get("get") or {}
    ok = export.get("responses", {}).get("200", {}).get("content", {})
    if "text/csv" not in ok:
        fail("check 18: exportRange's 200 must be text/csv (discovery default D-12, console-integration row 60)")
    for status in ("422", "429"):
        if status not in export.get("responses", {}):
            fail(f"check 18: exportRange must declare {status} (M-Q22: EXPORT_TOO_LARGE, RATE_LIMITED)")

    # 3. Every dashboard path parameter has the one pattern.
    parameter = doc.get("components", {}).get("parameters", {}).get("DashboardId", {})
    if parameter.get("schema", {}).get("pattern") != DASHBOARD_ID_PATTERN:
        fail(f"check 18: components.parameters.DashboardId must carry pattern {DASHBOARD_ID_PATTERN!r}, found "
             f"{parameter.get('schema', {}).get('pattern')!r}")
    for oid in ("getDashboard", "saveWidget", "setDashboardLayout", "setWidgetHidden", "resetDashboard"):
        _, path, _, _, _ = METRICS_SURFACE[oid]
        item_params = [p.get("$ref") for p in (paths.get(path) or {}).get("parameters", []) if isinstance(p, dict)]
        if "#/components/parameters/DashboardId" not in item_params:
            fail(f"check 18: {path} must reference components.parameters.DashboardId")

    # 3b. W2-06 (P11-7.2): a failed data plane is declared per entry, never an empty-looking list. The array is bare
    # (M-Q23), so the per-surface Availability rides on each entry and is REQUIRED.
    entry = schemas.get("MetricCatalogEntry") or {}
    if "observedAvailability" not in (entry.get("required") or []):
        fail("check 18: MetricCatalogEntry must REQUIRE observedAvailability (W2-06): a data-plane outage is "
             "otherwise indistinguishable from a metric nobody has scraped (OR-10)")
    availability_ref = _prop(doc, entry, "observedAvailability") or {}
    refs = [availability_ref.get("$ref")] + [part.get("$ref") for part in availability_ref.get("allOf", [])
                                             if isinstance(part, dict)]
    if "#/components/schemas/Availability" not in refs:
        fail("check 18: MetricCatalogEntry.observedAvailability must be the shared Availability schema (W2-06)")

    # 3c. W2-07: the dashboard revision is REQUIRED on every write (rows 63-66), so no client can be last-writer-wins.
    revision = (doc.get("components", {}).get("parameters", {}) or {}).get("DashboardRevision") or {}
    if revision.get("required") is not True or revision.get("in") != "header":
        fail("check 18: components.parameters.DashboardRevision must be a REQUIRED header (W2-07): an optional "
             "revision turns P11-7.1's optimistic concurrency into last-writer-wins")
    for oid in ("saveWidget", "setDashboardLayout", "setWidgetHidden", "resetDashboard"):
        method, path = METRICS_SURFACE[oid][0], METRICS_SURFACE[oid][1]
        op = (paths.get(path) or {}).get(method) or {}
        used = [p.get("$ref") for p in op.get("parameters", []) if isinstance(p, dict)]
        if "#/components/parameters/DashboardRevision" not in used:
            fail(f"check 18: {oid} must carry the DashboardRevision header (W2-07)")
        if "400" not in (op.get("responses") or {}):
            fail(f"check 18: {oid} must declare 400 (a missing or malformed revision, W2-07)")

    # 4. No byte figure in Capabilities, at any depth (OQ-16 (a); row 53 is authentication-only at install scope).
    names = _all_schema_properties(doc, schemas.get("Capabilities"))
    if not names:
        fail("check 18: components.schemas.Capabilities is missing or has no properties")
    bytey = sorted(n for n in names if "bytes" in n.lower())
    if bytey:
        fail(f"check 18: Capabilities carries a byte figure {bytey}: install-wide totals across every tenant "
             f"must never reach row 53 (OQ-16 (a), I5)")
    disk = _prop(doc, _prop(doc, schemas.get("Capabilities"), "collector"), "disk")
    if sorted((disk or {}).get("properties", {})) != ["state"]:
        fail(f"check 18: capabilities.collector.disk must carry only `state`, found "
             f"{sorted((disk or {}).get('properties', {}))}")
    elif (disk["properties"]["state"].get("enum") or []) != ["ok", "warning", "critical", "unconfigured"]:
        fail("check 18: capabilities.collector.disk.state must be ok|warning|critical|unconfigured (OQ-16 (a))")

    # 5. ErrorCode: the YAML's enum, its documented statuses and the Java enum are one set.
    error_code = schemas.get("ErrorCode", {})
    yaml_codes = list(error_code.get("enum") or [])
    documented = {m.group(1): int(m.group(2)) for m in re.finditer(
        r"(?m)^\s*-\s+`([A-Z_]+)`\s+\((\d{3})\)", str(error_code.get("description", "")))}
    missing = sorted(set(METRICS_ERROR_CODES) - set(yaml_codes))
    if missing:
        fail(f"check 18: ErrorCode lacks {missing} (plan 011 P11-1.2, item 6)")
    for code, status in METRICS_ERROR_CODES.items():
        if code in yaml_codes and documented.get(code) != status:
            fail(f"check 18: ErrorCode documents {code} as ({documented.get(code)}), expected ({status})")
    if not ERROR_CODE_JAVA.exists():
        fail(f"check 18: {ERROR_CODE_JAVA.relative_to(ROOT)} is missing")
    else:
        java = java_error_codes(ERROR_CODE_JAVA.read_text(encoding="utf-8"))
        if set(java) != set(yaml_codes):
            fail(f"check 18: Java ErrorCode and the YAML enum differ: only in Java {sorted(set(java) - set(yaml_codes))}, "
                 f"only in the YAML {sorted(set(yaml_codes) - set(java))}")
        for code in sorted(set(java) & set(documented)):
            if java[code] != documented[code]:
                fail(f"check 18: {code} is ({java[code]}) in Java and ({documented[code]}) in the YAML")
    # Every metrics 422 that names a code says which one(s), so a client can switch on `code`.
    for oid in ("queryRange", "queryInstant", "exportRange"):
        _, path, _, _, _ = METRICS_SURFACE[oid]
        method = METRICS_SURFACE[oid][0]
        response = (((paths.get(path) or {}).get(method) or {}).get("responses", {}).get("422") or {})
        if isinstance(response.get("$ref"), str):
            response = resolve_node(doc, response["$ref"]) or {}
        description = str(response.get("description", ""))
        if not description:
            fail(f"check 18: {oid} declares no 422 (the refusals of plan 011 P11-1.2, item 6)")
    if failures == before:
        print("  PASS the /api/console/v1 metrics surface is rows 53-66: paths, verbs, scopes, refusals, "
              "no byte figure, ErrorCode parity with Java")


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
    problems = catalog_problems(verbs)
    for problem in problems:
        fail(problem)
    if len(verbs) != CATALOG_SIZE:
        return rc
    check_catalog_selftest(APPROVAL.read_text(encoding="utf-8"))
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
        print("  PASS non-catalog markers are pinned to the eight approved operations")

    expected_apply_accepted = {"submitApply", "rollbackApply", "stopInstance",
                                "restoreRuntimeConfig", "setLogLevel"}
    if apply_accepted != expected_apply_accepted:
        fail("ApplyAccepted must be returned only by apply, rollback, stop, restore-runtime "
             "and set-log-level (OR-45 — each writes to the host, or is durable and observed "
             "the same way): expected "
             f"{sorted(expected_apply_accepted)}, found {sorted(apply_accepted)}")
    else:
        print("  PASS ApplyAccepted is confined to apply, rollback, stop, restore-runtime "
              "and set-log-level")

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

    check_view_model_fixtures(doc)
    check_no_organization_id_in_request_bodies(doc, ops)
    check_strength_parity(doc)
    check_ruled_shapes(doc)
    check_metrics_surface(doc)
    check_no_pending_markers(SPEC.read_text(encoding="utf-8"))

    used = {op.get("x-sm-permission") for _, _, op in ops} - NON_CATALOG
    for _, _, op in ops:
        used.update(op.get("x-sm-permission-also", []))
    print(f"  INFO {len(used)} of {CATALOG_SIZE} catalog verbs are exercised by this document")
    return rc


if __name__ == "__main__":
    sys.exit(main())
