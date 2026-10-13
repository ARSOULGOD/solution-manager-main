#!/usr/bin/env python3
"""Generate the Postman collection + environment for the Solution Manager console API.

Single source of truth: contracts/openapi/console.yaml. The set of operations the production
launcher actually SERVES is read from contracts/openapi/ENDPOINT-INVENTORY.md (every table row
under a heading that contains "Served now"). Nothing here is hand-maintained per operation except
the small, explicit tables below (variable captures, body overrides), which are keyed by
operationId and are checked by check-postman.py.

Usage:  python3 contracts/openapi/postman/generate-postman.py [--out DIR]
Stdlib + PyYAML only. Output is deterministic (no timestamps, no random ids).
"""
import argparse
import copy
import json
import os
import re
import sys
import uuid

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
OPENAPI_DIR = os.path.dirname(HERE)
CONSOLE_YAML = os.path.join(OPENAPI_DIR, "console.yaml")
INVENTORY_MD = os.path.join(OPENAPI_DIR, "ENDPOINT-INVENTORY.md")
GATEWAY_SRC = os.path.join(OPENAPI_DIR, "..", "..", "backend", "console-gateway", "src", "main", "java")
FIXTURE_PACKAGE = os.path.join(OPENAPI_DIR, "..", "validation", "fixtures", "cache-2.4.package.json")

COLLECTION_FILE = "solution-manager.postman_collection.json"
ENVIRONMENT_FILE = "solution-manager.local.postman_environment.json"
SCHEMA_URL = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"

# The launcher sets no server.port, so Spring Boot's default applies (backend/launcher/src/main/
# resources/application.properties). The contract's server url is "/api".
DEFAULT_ORIGIN = "http://localhost:8080"
DEFAULT_BASE_URL = DEFAULT_ORIGIN + "/api"

UNSAFE = {"post", "put", "patch", "delete"}

# Path / query parameter name -> collection variable.
PARAM_VARS = {
    "organizationId": "organizationId",
    "subOrganizationId": "unitId",
    "environmentId": "environmentId",
    "solutionId": "solutionId",
    "instanceId": "instanceId",
    "placementId": "placementId",
    "fleetId": "fleetId",
    "dashboardId": "dashboardId",
    "widgetId": "widgetId",
    "component": "component",
    "fieldKey": "fieldKey",
    "entityType": "entityType",
    "labelKey": "labelKey",
    "name": "metricName",
    "metric": "metricName",
    "applyId": "applyId",
    "scheduleId": "scheduleId",
    "solution": "solution",
    "userId": "userId",
    "assignmentId": "assignmentId",
    "agentId": "agentId",
}

# (operationId, query parameter) -> literal example for a REQUIRED query parameter no variable covers. The export range matches
# the queryRange body's example range below, so the two metrics calls agree.
QUERY_DEFAULTS = {
    ("exportRange", "from"): "2026-01-01T00:00:00Z",
    ("exportRange", "to"): "2026-01-01T01:00:00Z",
    ("retirePlacement", "workload"): "leave-running",   # the only value; retiring never touches the host (BE-D53)
    ("undeclareInstance", "workload"): "leave-running",  # the only value; undeclare never touches the host (BE-D49)
}

# Body property name -> variable (only applied to uuid / id-ish / version properties).
BODY_VARS = {
    "solutionId": "solutionId",
    "targetId": "targetId",
    "connectionId": "connectionId",
    "placementId": "placementId",
    "instanceId": "instanceId",
    "environmentId": "environmentId",
    "applyId": "applyId",
    "fleetId": "fleetId",
    "baseStateVersion": "stateVersion",
    "generation": "generation",
    "declaredPackageVersion": "packageVersion",
    "packageVersion": "packageVersion",
}

# Every variable the collection can capture or consume. Declared (empty) in the collection and
# in the environment so a frontend engineer sees the full list.
ID_VARIABLES = [
    "csrfToken", "organizationId", "unitId", "environmentId", "solutionId", "packageVersion",
    "targetId", "connectionId", "instanceId", "placementId", "stateVersion", "generation", "applyId", "fleetId",
    "dashboardId", "dashboardRevision", "widgetId", "component", "fieldKey", "entityType",
    "labelKey", "metricName", "scheduleId",
    "solution", "entitlementMax", "organizationName", "unitName", "environmentName",
    "userId", "roleId", "assignmentId", "agentId",
]

# operationId -> [(variable, JS expression over `j` (parsed JSON body), human note)]
CAPTURES = {
    "login": [("csrfToken", "j.csrfToken", "the CSRF token (also in the __Host-SM_CSRF cookie)")],
    # plan 013 T3.2: the user the folder creates (reset, grant and deactivate act on it), the Operator role (the first role is the
    # Administrator, which is never granted over the API) and the assignment the grant makes (revoke takes it)
    "createUser": [("userId", "j.id", "the new user")],
    "listRoles": [("roleId", "(j.items.find(function (r) { return r.name === 'Operator'; }) || {}).id", "the Operator role")],
    "grantAssignment": [("assignmentId", "j.assignmentId", "the assignment just granted")],
    "getIdentity": [("csrfToken", "j.csrfToken", "refreshed CSRF token")],
    # plan 015 T4.3: the agent the issue created (getAgent, drainAgent and revokeAgent take it); the token itself is shown once and is
    # deliberately NOT captured into a variable. listAgents deliberately captures NOTHING: its first item is the organization's oldest
    # agent, and a folder run would then drain it and revoke it for good (W4 lane B review, MAJOR 2)
    "issueAgentEnrolment": [("agentId", "j.agentId", "the agent just enrolled (its token is in this response only)")],
    "listOrganizations": [
        ("organizationId", "j.items && j.items[0] && j.items[0].id", "first organization"),
        ("organizationName", "j.items && j.items[0] && j.items[0].name", "its name (renameOrganization sends it back, a rename to the node's own name, which writes nothing)"),
    ],
    "listSubOrganizations": [
        ("unitId", "j.items && j.items[0] && j.items[0].id", "first organization unit (subOrganizationId)"),
        ("unitName", "j.items && j.items[0] && j.items[0].name", "its name (renameSubOrganization sends it back, which writes nothing)"),
    ],
    "listEnvironments": [
        ("environmentId", "j.items && j.items[0] && j.items[0].id", "first environment"),
        ("environmentName", "j.items && j.items[0] && j.items[0].name", "its name (renameEnvironment sends it back, which writes nothing)"),
    ],
    # plan 013 T2.3: the first entitlement; setEntitlement (next in the folder) sets the limit it already holds, which writes nothing
    "listEntitlements": [
        ("solution", "j.items && j.items[0] && j.items[0].solution", "first entitled solution"),
        ("entitlementMax", "j.items && j.items[0] && j.items[0].maxInstances", "its current limit"),
    ],
    "listSolutions": [
        ("solutionId", "j.items && j.items[0] && j.items[0].solutionId", "first catalog solution"),
        ("packageVersion", "j.items && j.items[0] && j.items[0].latestPackageVersion", "its latest package version"),
    ],
    "loadPackage": [
        ("solutionId", "j.solutionId", "the catalog id of the loaded solution"),
        ("packageVersion", "j.packageVersion && j.packageVersion[0]", "first loaded package version"),
    ],
    "listTargets": [("targetId", "j.items && j.items[0] && j.items[0].targetId", "first target")],
    "listConnections": [("connectionId", "j.items && j.items[0] && j.items[0].connectionId", "first connection")],
    "createConnection": [("connectionId", "j.connectionId", "the created connection")],
    "listInstances": [
        ("instanceId", "j.items && j.items[0] && j.items[0].instanceId", "first instance"),
        ("placementId", "j.items && j.items[0] && j.items[0].placements && j.items[0].placements[0] && j.items[0].placements[0].placementId", "its first placement"),
    ],
    "declareInstance": [
        ("instanceId", "j.instanceId", "the new instance"),
        ("placementId", "j.placements && j.placements[0] && j.placements[0].placementId", "its first placement"),
        ("stateVersion", "j.stateVersion", "its stateVersion"),
    ],
    # the NEWEST placement, so retirePlacement (next in the folder) retires the one just added, never the instance's first
    "addPlacement": [
        ("placementId", "j.placements && j.placements[j.placements.length - 1] && j.placements[j.placements.length - 1].placementId", "the placement just added"),
        ("stateVersion", "j.stateVersion", "its stateVersion"),
    ],
    "getInstance": [
        ("placementId", "j.placements && j.placements[0] && j.placements[0].placementId", "first placement"),
        ("stateVersion", "j.stateVersion", "its stateVersion"),
    ],
    "getDesigner": [
        ("stateVersion", "j.stateVersion", "baseStateVersion for the next stageConfig"),
        ("generation", "j.generation", "the current staged/applied generation, if any"),
    ],
    "stageConfig": [
        ("generation", "j.generation", "the generation just staged (submitApply takes it)"),
        ("stateVersion", "j.stateVersion", "new baseStateVersion"),
    ],
    "setConfigSecret": [
        ("generation", "j.generation", "the generation just staged"),
        ("stateVersion", "j.stateVersion", "new baseStateVersion"),
    ],
    "submitApply": [("applyId", "j.applyId", "the apply to observe")],
    "restoreRuntimeConfig": [("applyId", "j.applyId", "the apply to observe")],
    "rollbackApply": [("applyId", "j.applyId", "the rollback apply to observe")],
    "stopInstance": [("applyId", "j.applyId", "the stop to observe")],
    "setLogLevel": [("applyId", "j.applyId", "the apply to observe")],
    "submitFleetApply": [("fleetId", "j.fleetId", "the fleet apply to observe")],
    # plan 012 T7: the newest schedule (cancelApplySchedule, next in the folder, takes it); empty until one is created by a
    # submitApply / rollbackApply that carries a `schedule`, in which case the cancel answers its declared 404
    "listApplySchedules": [("scheduleId", "j.items && j.items[0] && j.items[0].scheduleId", "the newest schedule")],
    "getDashboard": [
        ("dashboardRevision", "j.revision", "X-SM-Dashboard-Revision for the next write"),
        ("widgetId", "j.widgets && j.widgets[0] && j.widgets[0].id", "first widget"),
    ],
    "resetDashboard": [("dashboardRevision", "j.revision", "X-SM-Dashboard-Revision for the next write")],
    "saveWidget": [("widgetId", "j.id", "the saved widget")],
    "listEntityTypes": [("entityType", "Array.isArray(j) && j[0] && j[0].id", "first entity type")],
    "getMetricsCatalog": [("metricName", "Array.isArray(j) && j[0] && j[0].name", "first catalog metric")],
}

# Extra JS run after captures, per operation (dashboard id is derived from the instance id, M-Q10).
DERIVED_AFTER = {
    "listInstances": "if (get('instanceId')) set('dashboardId', 'db_' + get('instanceId'));",
    "declareInstance": "if (j.instanceId) set('dashboardId', 'db_' + j.instanceId);",
    "getInstance": "if (j.instanceId) { set('instanceId', j.instanceId); set('dashboardId', 'db_' + j.instanceId); }",
}

# Body overrides for operations whose bodies cannot be derived from "required fields" alone.
# Values may contain "@@var@@" which becomes {{var}} (an unquoted placeholder for numbers).
BODY_OVERRIDES = {
    # plan 013 T2.3: creates use fixed names (a re-run is the identical replay, 200); renames send the node's own name back
    # (captured by the list before it), so running the folder top to bottom never renames a real node
    "createSubOrganization": {"name": "Postman sub-organization"},
    "createEnvironment": {"name": "Postman environment"},
    "renameOrganization": {"name": "{{organizationName}}"},
    "renameSubOrganization": {"name": "{{unitName}}"},
    "renameEnvironment": {"name": "{{environmentName}}"},
    "setEntitlement": {"maxInstances": "@@entitlementMax@@"},
    "login": {"username": "{{username}}", "credential": "{{password}}"},
    # plan 013 T3.2: a fresh username per run ({{$timestamp}} is Postman's), so a second run is not a 409; the provisional
    # credential is the environment's; the grant is the Operator role at the environment the hierarchy folder captured
    "createUser": {"username": "postman-user-{{$timestamp}}", "initialCredential": "{{newUserCredential}}"},
    "resetUserCredential": {"resetCredential": "{{newUserCredential}}"},
    "grantAssignment": {"principalId": "{{userId}}", "roleId": "{{roleId}}",
                        "scope": {"subOrganizationId": "{{unitId}}", "environmentId": "{{environmentId}}"}},
    "rotateOwnCredential": {"currentCredential": "{{password}}", "newCredential": "{{newPassword}}"},
    "rollbackApply": {"applyId": "@@applyId@@placeholder", "placementId": "{{placementId}}", "strategy": "manual"},
    "stopInstance": {"placementId": "{{placementId}}"},
    # plan 015 T4.3: a fresh name per run (a taken name is 409); the envelope is the host's and the solution is the loaded one
    "issueAgentEnrolment": {"targetName": "postman-agent-{{$timestamp}}", "allowedConfigRoot": "/srv/sm",
                            "allowedContainerPrefix": "sm-", "solutionId": "{{solutionId}}", "packageVersion": "@@packageVersion@@"},
    # plan 013 T1.5: at least one field is required (an empty body is 400); a change of sshUser alone is not scanned.
    "editTargetConnection": {"sshUser": "<ssh user>"},
    # plan 013 T1: at least one field is required (an empty body is 400)
    "updateConnection": {"name": "<name>"},
    "stageConfig": {
        "baseStateVersion": "@@stateVersion@@",
        "componentSelection": {"instances": [{"instanceKey": "<instanceKey from getDesigner viewModel>", "type": "<component type>"}]},
        "draftValues": {"<instanceKey>": {"<fieldKey>": "<value>"}},
        "rendered": [],
    },
    "submitApply": {"generation": "@@generation@@", "strategy": "manual", "placementId": "{{placementId}}"},
    "restoreRuntimeConfig": {"placementId": "{{placementId}}", "strategy": "manual"},
    "acceptRuntimeConfig": {"baseStateVersion": "@@stateVersion@@", "placementId": "{{placementId}}"},
    "setConfigSecret": {"value": "<secret-value>", "baseStateVersion": "@@stateVersion@@"},
    "submitFleetApply": {
        "solutionId": "{{solutionId}}",
        "members": [{"instanceId": "{{instanceId}}", "placementId": "{{placementId}}", "generation": "@@generation@@"}],
        "strategy": "manual",
    },
    "setLogLevel": {"placementId": "{{placementId}}", "level": "INFO", "mode": "config"},   # mode live answers 501 (OR-103, OR-109)
    "setDashboardLayout": {"layout": [{"widgetId": "{{widgetId}}", "x": 0, "y": 0, "w": 6, "h": 4, "order": 0}]},
    "queryRange": {
        "queryVersion": 1, "metric": "{{metricName}}",
        "selector": {"entityType": "{{entityType}}", "instanceIds": ["{{instanceId}}"], "labels": {}},
        "range": {"from": "2026-01-01T00:00:00Z", "to": "2026-01-01T01:00:00Z"},
        "resolution": "auto", "seriesBy": "instance",
        "aggregation": {"acrossReplicas": "auto", "acrossInstances": "none"},
    },
}
BODY_OVERRIDES["queryInstant"] = BODY_OVERRIDES["queryRange"]

# Operations that take the collection's shared instance away from every request after them (plan 012 T5). They are listed LAST
# in their folder and say so in the request's description: a collection run that reached them early would 404 the rest.
RUN_LAST_NOTE = {
    "revokeAgent": ("Run this one LAST in the folder. Revocation is sticky: it ends the agent `{{agentId}}`'s session and refuses its next "
                    "connection; the remedy is a fresh `issueAgentEnrolment`."),
    "undeclareInstance": ("Run this one LAST. It releases the instance `{{instanceId}}` from management; every request that "
                          "uses that instance afterwards answers `404`. Declare a fresh instance (`declareInstance`) to try it "
                          "without losing the one the rest of the collection uses. The container on the host is never touched."),
}

# Query parameters left enabled in the example (optional ones are disabled by default).
ENABLED_QUERY_DEFAULTS = {}

# The third state (W4-02): a handler exists and is tested, but the measurement that lets the operation be counted served
# (plan 011 P11-8.1 Step 6b, the 105 M-row stream, M-Q18) has not been recorded.
WIRED_MARKER = ("WIRED — NOT COUNTED SERVED until plan 011 P11-8.1 records the 105 M-row stream (M-Q18). A handler exists and is "
                "tested, but do not build the feature on it yet: the export is not shipped until that measurement exists.")

MD_LINK = re.compile(r"\[([^\]]+)\]\([^)]+\)")


# --------------------------------------------------------------------------- loading

def load_contract():
    with open(CONSOLE_YAML, encoding="utf-8") as f:
        return yaml.safe_load(f)


def load_inventory_tables(contract):
    """Return ({(method, contract_path)} under a 'Served now' heading, {...} under a 'Wired, not counted served' heading)."""
    with open(INVENTORY_MD, encoding="utf-8") as f:
        lines = f.read().splitlines()
    served, wired, active = set(), set(), None
    for line in lines:
        if line.startswith("#"):
            low = line.lower()
            active = served if "served now" in low else wired if "wired, not counted served" in low else None
            continue
        if line.strip() == "---":
            active = None
            continue
        if active is None or not line.startswith("|"):
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 2 or cells[0].upper() not in {"GET", "POST", "PUT", "PATCH", "DELETE"}:
            continue
        m = re.search(r"`([^`]+)`", cells[1])
        if not m:
            continue
        path = m.group(1)
        if path.startswith("/api/"):
            path = path[len("/api"):]
        active.add((cells[0].lower(), path))
    ops = {(m, p) for p, item in contract["paths"].items() for m in item if m in
           ("get", "post", "put", "patch", "delete")}
    unknown = sorted((served | wired) - ops)
    if unknown:
        raise SystemExit("ENDPOINT-INVENTORY.md lists operations that console.yaml does not "
                         "contract: %r" % (unknown,))
    both = sorted(served & wired)
    if both:
        raise SystemExit("ENDPOINT-INVENTORY.md lists %r as both served and wired-not-counted-served" % (both,))
    return served, wired


def load_served(contract):
    """Return {(method, contract_path)} read from every 'Served now' table of the inventory."""
    return load_inventory_tables(contract)[0]


CONSOLE_OPERATION = re.compile(r'^\s*@ConsoleOperation\(\s*(?:value\s*=\s*)?"([A-Za-z0-9]+)"')


def scan_code_served(contract):
    """Operations with a real handler: `@ConsoleOperation("<operationId>")` in backend/console-gateway.

    ENDPOINT-INVENTORY.md's "Served now" table can lag the code (it listed 7 while 32 handlers existed
    on the branch this was written against). The code is what the launcher actually maps, so an
    operation is marked SERVED when EITHER source says so; the difference is reported, never hidden.
    """
    found = set()
    for root, _dirs, names in os.walk(GATEWAY_SRC):
        for n in sorted(names):
            if not n.endswith(".java"):
                continue
            with open(os.path.join(root, n), encoding="utf-8") as f:
                for line in f:
                    m = CONSOLE_OPERATION.match(line)
                    if m:
                        found.add(m.group(1))
    by_id = {op["operationId"]: (m, p) for p, item in contract["paths"].items()
             for m, op in item.items() if m in ("get", "post", "put", "patch", "delete")}
    return {by_id[i] for i in found if i in by_id}


def served_sets(contract):
    """(inventory_served, code_served, served, wired_not_served).

    ``served`` is inventory-or-code MINUS the wired set: an operation the inventory holds in its 'Wired, not counted served'
    table has a handler (so the code scan finds it) and is tested, but plan 011 P11-8.1 has not recorded the measurement that
    lets it be counted served (W4-02). That third state cannot be expressed as a union, so it is subtracted explicitly.
    """
    inv, wired = load_inventory_tables(contract)
    code = scan_code_served(contract)
    return inv, code, (inv | code) - wired, wired


# --------------------------------------------------------------------------- schema helpers

class Resolver:
    def __init__(self, doc):
        self.doc = doc

    def ref(self, ref):
        node = self.doc
        for part in ref.lstrip("#/").split("/"):
            node = node[part]
        return node

    def deref(self, node):
        seen = 0
        while isinstance(node, dict) and "$ref" in node and seen < 20:
            node = self.ref(node["$ref"])
            seen += 1
        return node


def example_value(res, schema, name=None, depth=0):
    """A small example for `schema`: required fields only, enum first, formats to placeholders."""
    schema = res.deref(schema)
    if not isinstance(schema, dict) or depth > 6:
        return None
    if "allOf" in schema:
        merged = {}
        for part in schema["allOf"]:
            v = example_value(res, part, name, depth + 1)
            if isinstance(v, dict):
                merged.update(v)
            elif v is not None and not merged:
                return v
        return merged
    for key in ("oneOf", "anyOf"):
        if key in schema:
            return example_value(res, schema[key][0], name, depth + 1)
    if "const" in schema:
        return schema["const"]
    if "enum" in schema:
        return schema["enum"][0]
    if "example" in schema:
        return schema["example"]
    t = schema.get("type")
    if isinstance(t, list):
        t = next((x for x in t if x != "null"), t[0])
    fmt = schema.get("format")
    if t == "object" or "properties" in schema:
        out = {}
        props = schema.get("properties", {})
        required = schema.get("required", [])
        for pname in required:
            if pname in props:
                out[pname] = example_value(res, props[pname], pname, depth + 1)
        return out
    if t == "array":
        item = example_value(res, schema.get("items", {}), name, depth + 1)
        return [] if item in (None, {}) else [item]
    if t == "string":
        if name in BODY_VARS and fmt == "uuid":
            return "{{%s}}" % BODY_VARS[name]
        if name == "entityType":
            return "{{entityType}}"
        if name == "metric":
            return "{{metricName}}"
        if fmt == "uuid" and name == "instanceIds":
            return "{{instanceId}}"
        if fmt == "uuid":
            return "00000000-0000-4000-8000-000000000000"
        if fmt == "date-time":
            return "2026-01-01T00:00:00Z"
        if fmt == "password":
            return "<credential>"
        if fmt in ("uri", "uri-reference"):
            return "https://example.invalid/"
        if "pattern" in schema:
            return "<matches %s>" % schema["pattern"]
        return "<%s>" % (name or "string")
    if t in ("integer", "number"):
        if name in BODY_VARS:
            return "@@%s@@" % BODY_VARS[name]
        return schema.get("minimum", schema.get("default", 1))
    if t == "boolean":
        return schema.get("default", False)
    return None


def body_text(value):
    text = json.dumps(value, indent=2, ensure_ascii=False)
    # "@@var@@" -> {{var}} unquoted (numbers); "@@var@@placeholder" -> quoted variable text.
    text = re.sub(r'"@@([A-Za-z]+)@@placeholder"', r'"{{\1}}"', text)
    text = re.sub(r'"@@([A-Za-z]+)@@"', r"{{\1}}", text)
    return text


def first_sentence(text, limit=240):
    text = " ".join((text or "").split())
    text = MD_LINK.sub(r"\1", text)
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


# --------------------------------------------------------------------------- scripts

# operationId -> variables cleared before the captures run (W4 review W4D-2). `set` ignores an empty value, so without this
# an empty list leaves the previous run's value in the environment file. setEntitlement writes with what listEntitlements
# captured, and a solution name means the same thing on every install, so a stale pair is a real write (it would create
# an entitlement the organization never had); cleared, setEntitlement sends an empty path and an invalid body (404/400).
CLEAR_FIRST = {"listEntitlements": ["solution", "entitlementMax"]}



SET_GET = (
    "const set = (k, v) => { if (v === undefined || v === null || v === '') return; "
    "if (pm.environment.name) { pm.environment.set(k, String(v)); } "
    "else { pm.collectionVariables.set(k, String(v)); } };\n"
    "const clear = (k) => { if (pm.environment.name) { pm.environment.set(k, ''); } "
    "else { pm.collectionVariables.set(k, ''); } };\n"
    "const get = (k) => pm.variables.get(k);\n"
)


def test_script(op_id, codes, captures, is_unsafe, not_served=False, after=None):
    lines = [SET_GET.rstrip()]
    # 428 MUST_ROTATE is global. 501 is allowed only where the contract declares it, or where the operation is not served at
    # all; an unexpected 501 from a served route must FAIL (W4-10).
    extra = ["428"] + (["501"] if not_served else [])
    allowed = codes + [c for c in extra if c not in codes]
    for var in CLEAR_FIRST.get(op_id, []):
        lines.append("clear('%s');" % var)
    lines.append("pm.test('status is one this operation declares (%s), or %s', function () {" % (
        ", ".join(codes), "/".join(extra)))
    lines.append("  pm.expect([%s]).to.include(pm.response.code);" % ", ".join(allowed))
    lines.append("});")
    lines.append("if (pm.response.code === 428) { console.warn('MUST_ROTATE: run rotateOwnCredential in \"0. Start here\" first.'); }")
    if "501" in codes or not_served:
        lines.append("if (pm.response.code === 501) { console.warn('%s answered 501 NOT_IMPLEMENTED: %s.'); }" % (
            op_id, "contracted, not served yet" if not_served else "a refusal the contract declares"))
    lines.append("if (pm.response.code >= 400) { try { const p = pm.response.json(); console.warn(p.code + ' correlationId=' + p.correlationId + ' ' + (p.detail || '')); } catch (e) {} }")
    after = after if after is not None else DERIVED_AFTER.get(op_id)
    if captures or after:
        lines.append("if (pm.response.code >= 200 && pm.response.code < 300) {")
        lines.append("  let j = {}; try { j = pm.response.json(); } catch (e) { j = {}; }")
        lines.append("  if (j && typeof j === 'object') {")
        for var, expr, _note in captures:
            lines.append("    set('%s', %s);" % (var, expr))
        if after:
            lines.append("    " + after)
        lines.append("  }")
        lines.append("}")
    if op_id == "login":
        lines.append("// Optional fallback (cookieMode=manual): keep the two Set-Cookie values for the collection pre-request script.")
        lines.append("pm.response.headers.each(function (h) { if (h.key.toLowerCase() !== 'set-cookie') return;")
        lines.append("  const nv = String(h.value).split(';')[0];")
        lines.append("  if (nv.indexOf('__Host-SM_SESSION=') === 0) set('sessionCookie', nv);")
        lines.append("  if (nv.indexOf('__Host-SM_CSRF=') === 0) set('csrfCookie', nv); });")
        lines.append("try { if (pm.response.json().mustRotate) console.warn('mustRotate=true: rotate the credential before anything else.'); } catch (e) {}")
    if op_id == "rotateOwnCredential":
        lines.append("if (pm.response.code === 204) { set('password', get('newPassword')); }  // the new credential is now the one to log in with")
    if op_id == "logout":
        lines.append("if (pm.response.code === 204) { clear('csrfToken'); }")
    return {"listen": "test", "script": {"type": "text/javascript", "exec": "\n".join(lines).split("\n")}}


COLLECTION_PREREQUEST = """\
// Optional fallback for HTTP-localhost cookie handling. Default cookieMode is 'jar' (Postman's own cookie jar).
// Set the variable cookieMode=manual ONLY if the jar does not send the __Host- cookies to http://localhost.
// Never mix the two: two session cookies in one request are refused 401 UNAUTHENTICATED.
if (pm.variables.get('cookieMode') === 'manual') {
  const parts = [pm.variables.get('sessionCookie'), pm.variables.get('csrfCookie')].filter(Boolean);
  if (parts.length) { pm.request.headers.upsert({ key: 'Cookie', value: parts.join('; ') }); }
}""".split("\n")


# --------------------------------------------------------------------------- the Setup folder (plan 013 T4.1)

SETUP_FOLDER_NAME = "Setup"

# Defaults the Setup folder reads. They are ordinary (non-secret) variables: edit them in the environment before the run.
SETUP_VARIABLES = [
    ("setupOrganizationName", "ACME", "The display name the install's organization is renamed to (renameOrganization)."),
    ("setupUnitName", "ACME-Sub-1", "The sub-organization createSubOrganization makes."),
    ("setupEnvironmentName", "Dev", "The environment createEnvironment makes under it."),
    ("setupSolution", "cache", "The catalog solution name the entitlement is set for: the `solution` field of the package loaded two steps later."),
    ("setupEntitlementMax", "5", "How many instances of that solution the organization may run (1 to 1 000 000)."),
    ("setupOperatorUsername", "local-operator", "The username of the Operator createUser makes. A second run answers 409 for it, by design."),
    ("targetName", "ssh-docker-target", "The target's name (unique among live targets of the organization)."),
    ("sshHost", "<host>", "The SSH host of the target: a DNS name or an address the data plane may connect to."),
    ("sshPort", "22", "Its SSH port."),
    ("sshUser", "<ssh user>", "The account SM signs in as; it must be allowed to run `docker`."),
    ("hostKeyFingerprint", "<SHA256:...>", "The host key you CONFIRM, verified out of band (`ssh-keygen -lf` on the host). scanHostKeys lists what the host offers in the console; it is never copied here for you."),
    ("allowedConfigRoot", "<config root>", "The envelope: the one absolute directory every declared configuration lives under. Immutable once registered."),
    ("allowedContainerPrefix", "<container prefix>", "The envelope: the prefix every declared container name starts with. Immutable once registered."),
]
# Declared too, but with no value: a secret (sshPrivateKey, in the environment only) and a run-time scratch variable.
SETUP_SECRET_VARIABLES = [
    ("sshPrivateKey", "<the unencrypted private key file's text>", "The SSH private key, OpenSSH or PEM, UNENCRYPTED, pasted as it is (newlines and all). Sent once by registerTarget, never stored by the collection."),
]
SETUP_SCRATCH_VARIABLES = ["sshPrivateKeyJson"]

# Runs first in every Setup step: a required variable that still holds its <placeholder> default fails the step clearly
# instead of sending a request that would fail with a confusing server answer.
SETUP_PLACEHOLDER_GUARD = [
    "// Setup guard: stop before sending when a variable this step uses still holds a <placeholder> value",
    "(function () {",
    "  var text = String((pm.request.body && pm.request.body.raw) || '') + ' ' + pm.request.url.toString() + ' ' +",
    "    pm.request.headers.map(function (h) { return h.value; }).join(' ');",
    "  var names = {}, m, re = /\\{\\{([A-Za-z0-9_]+)\\}\\}/g;",
    "  while ((m = re.exec(text)) !== null) { names[m[1]] = true; }",
    "  var bad = Object.keys(names).filter(function (n) {",
    "    var v = pm.variables.get(n);",
    "    return typeof v === 'string' && /^<.*>$/.test(v.trim());",
    "  });",
    "  if (bad.length) {",
    "    throw new Error('Setup step cannot run: set the variable(s) ' + bad.join(', ') + ' in the environment (they still hold a <placeholder> value)');",
    "  }",
    "})();",
]

_SCOPE_OF_NEW_ENVIRONMENT = {"subOrganizationId": "{{unitId}}", "environmentId": "{{environmentId}}"}

# The sequence a fresh install walks, top to bottom (OR-114 item (3)): every request is the operation's ordinary request with the
# body, captures and variable names this walk needs. Each is a SECOND use of its operationId; the folders below this one still
# hold each operation exactly once, and check-postman.py checks both.
SETUP_STEPS = [
    {"op": "login", "note": "Sign in as `bootstrap-admin` with the single-use credential whoever installed the system gave you "
                            "(`username`, `password`). On a fresh install the body omits `organization` (`OR-131` Q3).",
     "body": {"username": "{{username}}", "credential": "{{password}}"}},
    {"op": "rotateOwnCredential", "note": "The bootstrap credential is single-use: until it is rotated every other route answers `428 MUST_ROTATE`. "
                                         "Set `newPassword` first. Skip this step on an install that is already rotated."},
    {"op": "getIdentity", "note": "`mustRotate` is now false and `organizations` is filled: the install's one organization is captured.",
     "captures": [("csrfToken", "j.csrfToken", "refreshed CSRF token"),
                  ("organizationId", "j.organizations && j.organizations[0] && j.organizations[0].id", "the install's organization"),
                  ("organizationName", "j.organizations && j.organizations[0] && j.organizations[0].name", "its current name")]},
    {"op": "renameOrganization", "note": "Name the organization. Only an install-bound principal may (the bootstrap administrator); `409` if another organization already has the name.",
     "body": {"name": "{{setupOrganizationName}}"}},
    {"op": "createSubOrganization", "note": "Create the sub-organization; the same name again is the identical replay (`200`).",
     "body": {"name": "{{setupUnitName}}"},
     "captures": [("unitId", "j.id", "the sub-organization just created")]},
    {"op": "createEnvironment", "note": "Create the environment under it. A new environment can own a target at once.",
     "body": {"name": "{{setupEnvironmentName}}"},
     "captures": [("environmentId", "j.id", "the environment just created")]},
    {"op": "setEntitlement", "note": "Entitle the organization to the solution you are about to load; without it `loadPackage` answers `409 ENTITLEMENT_REFUSED`.",
     "path_vars": {"solution": "setupSolution"}, "body": {"maxInstances": "@@setupEntitlementMax@@"}, "captures": []},
    {"op": "loadPackage", "note": "Load the committed Cache package fixture.",
     "captures": [("solutionId", "j.solutionId", "the catalog id of the loaded solution"),
                  ("packageVersion", "j.packageVersion && j.packageVersion[0]", "first loaded package version")]},
    {"op": "scanHostKeys", "note": "Ask the host which SSH host keys it offers (writes nothing, pins nothing). The Postman console lists them; "
                                  "compare one with what you verified on the host and put THAT in `hostKeyFingerprint`. The scan budget is 10 a minute per session.",
     "body": {"host": "{{sshHost}}", "port": "@@sshPort@@", "ownershipScope": _SCOPE_OF_NEW_ENVIRONMENT},
     "captures": [],
     "after": "(j.offered || []).forEach(function (o) { console.log('offered host key: ' + o.algorithm + ' ' + o.fingerprint); });"},
    {"op": "registerTarget", "note": "Register the target in both planes with its SSH key and the host key you confirmed. SM connects to the host once "
                                    "(a fresh scan, no authentication) before it writes anything. Plain HTTP is accepted only from a loopback peer, "
                                    "a TLS connection or a trusted proxy that reports `https` (`403 INSECURE_TRANSPORT` otherwise).",
     "body": {"name": "{{targetName}}", "kind": "docker", "transport": "ssh-docker", "ownershipScope": _SCOPE_OF_NEW_ENVIRONMENT,
              "connection": {"host": "{{sshHost}}", "port": "@@sshPort@@", "sshUser": "{{sshUser}}",
                             "hostKeyFingerprint": "{{hostKeyFingerprint}}", "allowedConfigRoot": "{{allowedConfigRoot}}",
                             "allowedContainerPrefix": "{{allowedContainerPrefix}}"},
              "privateKey": "{{sshPrivateKeyJson}}"},
     "prerequest": ["// the key is pasted as text with real newlines; JSON needs them escaped, so the body reads a run-time copy",
                    "pm.variables.set('sshPrivateKeyJson', JSON.stringify(pm.variables.get('sshPrivateKey') || '').slice(1, -1));"],
     "captures": [("targetId", "j.targetId", "the target just registered")]},
    {"op": "testTargetConnection", "note": "One fixed command (`docker version`) over the pinned host key and the stored key. A failure is a result, "
                                          "never a 5xx: read which step was not reached. Draws on the scan budget.",
     "captures": []},
    {"op": "createUser", "note": "Create the Operator user (a provisional credential it must rotate at first sign-in: `newUserCredential`).",
     "body": {"username": "{{setupOperatorUsername}}", "initialCredential": "{{newUserCredential}}"},
     "captures": [("userId", "j.id", "the new user")]},
    {"op": "listRoles", "note": "The built-in roles; the Operator's id is captured (the Administrator is never granted over the API).",
     "captures": [("roleId", "(j.items.find(function (r) { return r.name === 'Operator'; }) || {}).id", "the Operator role")]},
    {"op": "grantAssignment", "note": "Bind the Operator at the environment. Run `declareInstance` (folder 7) next.",
     "body": {"principalId": "{{userId}}", "roleId": "{{roleId}}", "scope": _SCOPE_OF_NEW_ENVIRONMENT},
     "captures": [("assignmentId", "j.assignmentId", "the assignment just granted")]},
]

SETUP_FOLDER_DESCRIPTION = (
    "A fresh install, from the bootstrap credential to a registered target and a bound Operator, in the order to run it. "
    "Everything here is an ordinary request of the folders below with a body that suits the walk; each operation still has its "
    "one place in its own folder. Fill the Setup variables in the environment first (`setup*`, `ssh*`, `hostKeyFingerprint`, "
    "`allowedConfigRoot`, `allowedContainerPrefix`, `targetName`, and the secret `sshPrivateKey`), then run the folder top to "
    "bottom (or step by step: the host key is a decision, not a default). Steps are replay-safe except `rotateOwnCredential` "
    "(an already-rotated install skips it) and `createUser` (a second run answers 409 for the same username). "
    "`backend/local/sm-local.sh up` runs the same sequence through the API for the local stack; with the environment defaults "
    "this folder there replays the seeded nodes (the creates answer 200 and replay; `createUser` answers 409 for the seeded "
    "username, so `grantAssignment` needs a new username); change the `setup*` names to add new ones. A step whose request "
    "still holds a `<placeholder>` variable value stops before sending, naming the variable.")

# --------------------------------------------------------------------------- build

def collect_responses(res, op):
    rows = []
    for code, r in op.get("responses", {}).items():
        r = res.deref(r)
        rows.append((str(code), first_sentence(r.get("description", ""), 300)))
    return rows


def op_parameters(res, path_item, op):
    out = []
    for p in list(path_item.get("parameters", [])) + list(op.get("parameters", [])):
        out.append(res.deref(p))
    return out


def build_item(contract, res, served, wired, path, method, op, path_item, setup=None):
    """One request. ``setup`` (a SETUP_STEPS entry plus its 1-based ``step``) makes it a Setup-folder copy: its own body,
    captures, path-variable names, pre-request script and a numbered name; the description keeps the ``operationId:`` first
    line that check-postman.py reads."""
    op_id = op["operationId"]
    setup = setup or {}
    is_served = (method, path) in served
    is_wired = (method, path) in wired
    unsafe = method in UNSAFE
    params = op_parameters(res, path_item, op)

    # --- URL
    segs = []
    for seg in path.strip("/").split("/"):
        segs.append(":" + seg[1:-1] if seg.startswith("{") and seg.endswith("}") else seg)
    path_vars, query, headers = [], [], []
    for p in params:
        if p["in"] == "path":
            var = setup.get("path_vars", {}).get(p["name"]) or PARAM_VARS.get(p["name"], p["name"])
            path_vars.append({"key": p["name"], "value": "{{%s}}" % var,
                              "description": first_sentence(p.get("description", ""))})
        elif p["in"] == "query":
            var = PARAM_VARS.get(p["name"])
            value = "{{%s}}" % var if var else QUERY_DEFAULTS.get((op_id, p["name"]), "")
            query.append({"key": p["name"], "value": value,
                          "disabled": not p.get("required", False),
                          "description": first_sentence(p.get("description", ""))})
        elif p["in"] == "header":
            if p["name"] == "X-SM-CSRF":
                headers.append({"key": "X-SM-CSRF", "value": "{{csrfToken}}",
                                "description": "Session-bound CSRF token (login/identity response)."})
            elif p["name"] == "X-SM-Dashboard-Revision":
                headers.append({"key": "X-SM-Dashboard-Revision", "value": "{{dashboardRevision}}",
                                "description": "The dashboard revision this client last read (required on every dashboard write)."})
            else:
                headers.append({"key": p["name"], "value": "", "description": first_sentence(p.get("description", ""))})
    if unsafe:
        headers.append({"key": "Origin", "value": "{{origin}}",
                        "description": "Non-browser clients must send a trusted Origin on every state-changing request (login included). Must equal an entry of sm.gateway.trusted-origins."})

    # --- body
    body = None
    rb = op.get("requestBody")
    if rb:
        content = res.deref(rb)["content"]
        media = "application/json" if "application/json" in content else next(iter(content))
        if "body" in setup:
            example = copy.deepcopy(setup["body"])
        elif op_id in BODY_OVERRIDES:
            example = copy.deepcopy(BODY_OVERRIDES[op_id])
        elif op_id == "loadPackage" and os.path.exists(FIXTURE_PACKAGE):
            with open(FIXTURE_PACKAGE, encoding="utf-8") as f:
                example = {"package": json.load(f)}
        else:
            example = example_value(res, content[media].get("schema", {}))
        headers.append({"key": "Content-Type", "value": media})
        body = {"mode": "raw", "raw": body_text(example),
                "options": {"raw": {"language": "json"}}}

    # --- description
    codes = [c for c, _ in collect_responses(res, op)]
    perm = op.get("x-sm-permission", "")
    also = op.get("x-sm-permission-also") or []
    marker = ("SERVED — implemented by the production launcher today." if is_served else
              WIRED_MARKER if is_wired else
              "CONTRACTED, NOT YET SERVED (answers 501) — the shape below is the agreed contract; "
              "per the contract and ENDPOINT-INVENTORY.md the call answers `501 NOT_IMPLEMENTED` until it is "
              "implemented (a route that is not mapped at all answers `404 NOT_FOUND` — both mean not served).")
    desc = ["operationId: `%s`" % op_id, "", "**%s**" % marker, "",
            "`%s /api%s`" % (method.upper(), path), "", "**%s**" % op.get("summary", ""), "",
            (op.get("description") or "").strip(), "",
            "**Permission:** `%s`%s" % (perm, (" + also " + ", ".join("`%s`" % a for a in also)) if also else ""),
            "**Scope:** `%s`" % op.get("x-sm-scope", ""), "",
            "**Responses:**", ""]
    for code, text in collect_responses(res, op):
        desc.append("- `%s` — %s" % (code, text))
    if op_id in RUN_LAST_NOTE and not setup:
        desc = desc[:2] + ["⚠️ **%s**" % RUN_LAST_NOTE[op_id], ""] + desc[2:]
    if setup:
        desc = desc[:2] + ["**Setup step %d of %d.** %s" % (setup["step"], len(SETUP_STEPS), setup["note"]), ""] + desc[2:]
    desc.append("")
    desc.append("Auth: session cookie (`__Host-SM_SESSION`) from the login call%s." % (
        " plus the `X-SM-CSRF` header and a trusted `Origin` (both pre-filled)" if unsafe else ""))
    if is_served:
        pass
    description = "\n".join(desc)

    name = "%s · %s" % (op_id, op.get("summary", ""))
    if setup:
        name = "%d. %s" % (setup["step"], name)
    if is_wired:
        name += " (wired, not counted served)"
    elif not is_served:
        name += " (501)"

    url_raw = "{{baseUrl}}/" + "/".join(segs)
    enabled_q = [q for q in query if not q.get("disabled")]
    if enabled_q:
        url_raw += "?" + "&".join("%s=%s" % (q["key"], q["value"]) for q in enabled_q)
    request = {
        "method": method.upper(),
        "header": headers,
        "url": {"raw": url_raw, "host": ["{{baseUrl}}"], "path": segs,
                "query": query, "variable": path_vars},
        "description": {"content": description, "type": "text/markdown"},
    }
    if not query:
        del request["url"]["query"]
    if not path_vars:
        del request["url"]["variable"]
    if body:
        request["body"] = body
    if op.get("security") == []:
        request["auth"] = {"type": "noauth"}
    captures = setup["captures"] if "captures" in setup else CAPTURES.get(op_id, [])
    # the test script stays first: check-postman.py reads event[0]
    events = [test_script(op_id, codes, captures, unsafe, not_served=not is_served and not is_wired,
                          after=setup.get("after"))]
    if setup:
        events.append({"listen": "prerequest", "script": {"type": "text/javascript",
                       "exec": list(SETUP_PLACEHOLDER_GUARD) + list(setup.get("prerequest") or [])}})
    item = {"name": name, "request": request, "event": events, "response": []}
    return item, is_served, is_wired


FOLDER0_NAME = "0. Start here"
FOLDER0_ORDER = ["login", "getIdentity", "rotateOwnCredential", "logout"]


def build_collection(contract, served, wired):
    res = Resolver(contract)
    tags = [t["name"] for t in contract["tags"]]
    tag_desc = {t["name"]: t.get("description", "") for t in contract["tags"]}
    by_tag = {t: [] for t in tags}
    counts = {"total": 0, "served": 0, "wired": 0, "not_served": 0}
    for path, path_item in contract["paths"].items():
        for method, op in path_item.items():
            if method not in ("get", "post", "put", "patch", "delete"):
                continue
            item, is_served, is_wired = build_item(contract, res, served, wired, path, method, op, path_item)
            by_tag[op["tags"][0]].append((op["operationId"], item))
            counts["total"] += 1
            counts["served" if is_served else "wired" if is_wired else "not_served"] += 1

    for tag_items in by_tag.values():
        tag_items.sort(key=lambda pair: pair[0] in RUN_LAST_NOTE)     # stable: only moves the run-last operations to the end
    folders = []
    auth = dict(by_tag["auth"])
    ordered = [auth[o] for o in FOLDER0_ORDER if o in auth] + [i for o, i in by_tag["auth"] if o not in FOLDER0_ORDER]
    by_id = {}
    for path, path_item in contract["paths"].items():
        for method, op in path_item.items():
            if method in ("get", "post", "put", "patch", "delete"):
                by_id[op["operationId"]] = (path, method, op, path_item)
    setup_items = []
    for n, step in enumerate(SETUP_STEPS, 1):
        path, method, op, path_item = by_id[step["op"]]
        item, _s, _w = build_item(contract, res, served, wired, path, method, op, path_item, setup=dict(step, step=n))
        setup_items.append(item)
    folders.append({
        "name": FOLDER0_NAME,
        "description": ("The `auth` tag, in the order to run it. 1) `login` with `{{username}}` / `{{password}}` "
                       "(sets the session + CSRF cookies; the test script stores `csrfToken`). 2) `getIdentity` — "
                       "check `mustRotate` and the permission list. 3) If `mustRotate` is true (the bootstrap "
                       "admin always starts that way) run `rotateOwnCredential`: until then every other route "
                       "answers `428 MUST_ROTATE`. After a rotation set the environment `password` to the new "
                       "credential. 4) `logout` when done. See the README for the cookie-jar notes."),
        "item": ordered,
    })
    folders.append({"name": SETUP_FOLDER_NAME, "description": SETUP_FOLDER_DESCRIPTION, "item": setup_items})
    n = 1
    for tag in tags:
        if tag == "auth":
            continue
        folders.append({"name": "%d. %s" % (n, tag), "description": tag_desc[tag].strip(),
                        "item": [i for _, i in by_tag[tag]]})
        n += 1

    variables = [{"key": "baseUrl", "value": DEFAULT_BASE_URL, "type": "string"},
                 {"key": "origin", "value": DEFAULT_ORIGIN, "type": "string"},
                 {"key": "cookieMode", "value": "jar", "type": "string"}]
    variables += [{"key": k, "value": "", "type": "string"} for k in ID_VARIABLES]
    variables += [{"key": k, "value": v, "type": "string"} for k, v, _ in SETUP_VARIABLES]
    variables += [{"key": k, "value": "", "type": "string"} for k, _, _ in SETUP_SECRET_VARIABLES]
    variables += [{"key": k, "value": "", "type": "string"} for k in SETUP_SCRATCH_VARIABLES]

    info_desc = (
        "%s\n\nGenerated from `contracts/openapi/console.yaml` (contract version %s) by "
        "`contracts/openapi/postman/generate-postman.py` — do not edit by hand; regenerate. "
        "%d operations: %d SERVED by the production launcher today, %d WIRED but NOT COUNTED SERVED (plan 011 P11-8.1), "
        "%d CONTRACTED, NOT YET SERVED (answer 501). "
        "Start with folder `0. Start here`; see `contracts/openapi/postman/README.md`." % (
            contract["info"]["title"], contract["info"]["version"],
            counts["total"], counts["served"], counts["wired"], counts["not_served"]))
    collection = {
        "info": {
            "_postman_id": str(uuid.uuid5(uuid.NAMESPACE_URL, "solution-manager/console-api/postman")),
            "name": "Solution Manager — console API",
            "description": info_desc,
            "schema": SCHEMA_URL,
        },
        "item": folders,
        "event": [{"listen": "prerequest", "script": {"type": "text/javascript", "exec": COLLECTION_PREREQUEST}}],
        "variable": variables,
    }
    return collection, counts


def build_environment():
    values = [
        ("baseUrl", DEFAULT_BASE_URL, "default", "The launcher's local default: Spring Boot port 8080 (the launcher sets no server.port) + the contract's /api server."),
        ("origin", DEFAULT_ORIGIN, "default", "Sent as Origin on state-changing requests. Must equal an entry of sm.gateway.trusted-origins."),
        ("username", "<username>", "default", "Local login name. A fresh install's first admin is `bootstrap-admin` (V007). The login request omits `organization` (OR-131 Q3: optional on an install holding exactly one organization); on an install with several, add `\"organization\": \"<id or name>\"` to the login body."),
        ("password", "<password>", "secret", "Your credential. For a fresh install: the installer's single-use bootstrap secret; after rotation: the new credential."),
        ("newPassword", "<new-credential>", "secret", "Used only by rotateOwnCredential (min length 12)."),
        ("newUserCredential", "<provisional-credential>", "secret", "The provisional credential createUser and resetUserCredential set (12 to 1024 characters). The user must rotate it at first sign-in."),
        ("cookieMode", "jar", "default", "jar (Postman cookie jar) or manual (fallback; see README)."),
        ("sessionCookie", "", "secret", "Only used when cookieMode=manual."),
        ("csrfCookie", "", "secret", "Only used when cookieMode=manual."),
    ] + [(k, "", "default", "") for k in ID_VARIABLES]
    values += [(k, v, "default", d) for k, v, d in SETUP_VARIABLES]
    values += [(k, v, "secret", d) for k, v, d in SETUP_SECRET_VARIABLES]
    values += [(k, "", "default", "Run-time scratch for the Setup folder; leave empty.") for k in SETUP_SCRATCH_VARIABLES]
    return {
        "id": str(uuid.uuid5(uuid.NAMESPACE_URL, "solution-manager/console-api/postman-env-local")),
        "name": "Solution Manager — local",
        "values": [{"key": k, "value": v, "type": t, "enabled": True} for k, v, t, _ in values],
        "_postman_variable_scope": "environment",
    }


def render(obj):
    return json.dumps(obj, indent=2, ensure_ascii=False) + "\n"


def generate(out_dir):
    contract = load_contract()
    inv, code, served, wired = served_sets(contract)
    collection, counts = build_collection(contract, served, wired)
    counts["code_only"] = len(code - inv - wired)
    counts["inventory_only"] = len(inv - code)
    os.makedirs(out_dir, exist_ok=True)
    files = {COLLECTION_FILE: render(collection), ENVIRONMENT_FILE: render(build_environment())}
    for name, text in files.items():
        with open(os.path.join(out_dir, name), "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
    return counts


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", default=HERE, help="output directory (default: this directory)")
    args = ap.parse_args()
    counts = generate(args.out)
    print("wrote %s and %s to %s: %d operations (%d served, %d wired-not-counted-served, %d not served)" % (
        COLLECTION_FILE, ENVIRONMENT_FILE, args.out, counts["total"], counts["served"], counts["wired"], counts["not_served"]))
    if counts["code_only"] or counts["inventory_only"]:
        print("NOTE: %d operation(s) have a console-gateway handler but are not in ENDPOINT-INVENTORY.md's "
              "'Served now' table (marked SERVED); %d are in the table but have no handler (marked SERVED "
              "too — investigate)." % (counts["code_only"], counts["inventory_only"]))


if __name__ == "__main__":
    sys.exit(main())
