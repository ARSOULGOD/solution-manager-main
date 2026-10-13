#!/usr/bin/env python3
"""Gates 15-19 (UI-D28). Structural checks the JSON Schema cannot express,
because they are cross-field and cross-document.

  15  valueFrom.ref resolves to a connection selector in the SAME component
  16  valueFrom.attribute exists in that connectionType's descriptor, and
      its type is assignable to the target field's type
  17  secret binds only to secret, and non-secret never to secret (I6)
  18  two fields binding the same (ref, attribute) WARN - never fail
  19  a required field with valueFrom needs its selector required too

Usage: no args checks the real packages (fixtures + shipped). Args, if
given, replace the default glob patterns - used by run-checks.sh to point
this script at the valueFrom-*.json negatives and require them to fail.
"""
import json, pathlib, sys

root = pathlib.Path(__file__).resolve().parents[2]
descriptors = {
    json.loads(p.read_text())["connectionType"]: json.loads(p.read_text())
    for p in (root / "contracts" / "connection-types").glob("*.json")
    if p.name != "descriptor.schema.json"
}

# Gate 16's assignability table. A descriptor attribute type on the left may
# populate a field type on the right. Deliberately narrow: widening it is a
# decision, not a convenience.
ASSIGNABLE = {
    "string": {"string", "enum"},
    "int": {"int", "string"},
    "bool": {"bool"},
    "enum": {"enum", "string"},
    "duration": {"duration", "string"},
    "secret": {"secret"},
}

failures, warnings = [], []

def check_package(path, doc):
    for comp in doc.get("components", []):
        fields = comp.get("fields", [])
        by_key = {f["key"]: f for f in fields}
        seen_bindings = {}
        where = f"{path.name}:{comp.get('type', '?')}"

        for f in fields:
            vf = f.get("valueFrom")
            if not vf:
                continue
            ref, attribute = vf["ref"], vf["attribute"]

            # gate 15
            selector = by_key.get(ref)
            if selector is None:
                failures.append(f"{where}: {f['key']}.valueFrom.ref '{ref}' is not a field in this component (gate 15)")
                continue
            src = selector.get("optionsSource") or {}
            if src.get("entity") != "connection":
                failures.append(f"{where}: {f['key']}.valueFrom.ref '{ref}' is not a connection selector (gate 15)")
                continue

            conn_type = src.get("connectionType")
            descriptor = descriptors.get(conn_type)
            if descriptor is None:
                failures.append(f"{where}: selector '{ref}' names unknown connectionType '{conn_type}' (gate 15)")
                continue

            attrs = {a["key"]: a for a in descriptor["attributes"]}
            attr = attrs.get(attribute)

            # gate 16
            if attr is None:
                failures.append(f"{where}: {f['key']}.valueFrom.attribute '{attribute}' is not in the {conn_type} descriptor (gate 16)")
                continue
            if f["type"] not in ASSIGNABLE.get(attr["type"], set()):
                failures.append(f"{where}: {f['key']} is type '{f['type']}' but attribute '{attribute}' is '{attr['type']}' (gate 16)")

            # gate 17 - I6, checked both directions
            field_secret = f.get("secret") is True or f["type"] == "secret"
            attr_secret = attr.get("secret") is True or attr["type"] == "secret"
            if field_secret != attr_secret:
                failures.append(
                    f"{where}: {f['key']} (secret={field_secret}) bound to attribute "
                    f"'{attribute}' (secret={attr_secret}) - I6 forbids either direction (gate 17)"
                )

            # gate 19
            if f.get("required") and not selector.get("required"):
                failures.append(f"{where}: {f['key']} is required but its selector '{ref}' is not (gate 19)")

            # gate 18 - WARNING ONLY. Two MI properties may legitimately need
            # the same attribute (a host in both a data-plane and a
            # management-plane property). It is also what a copy-paste looks
            # like, so it is reported and never fatal.
            binding = (ref, attribute)
            if binding in seen_bindings:
                warnings.append(f"{where}: {f['key']} and {seen_bindings[binding]} both bind {ref}#{attribute} (gate 18)")
            else:
                seen_bindings[binding] = f["key"]

patterns = sys.argv[1:] or (
    "contracts/validation/fixtures/*.json",
    "frontend/contracts/packages/*/package.json",
)
for pattern in patterns:
    for path in sorted(root.glob(pattern)):
        try:
            doc = json.loads(path.read_text())
        except json.JSONDecodeError as exc:
            failures.append(f"{path.name}: not valid JSON: {exc}")
            continue
        if "components" in doc:
            check_package(path, doc)

for w in warnings:
    print(f"  WARN {w}")
if failures:
    print("\n".join(f"  FAIL {f}" for f in failures))
    sys.exit(1)
print(f"  OK: gates 15-19 pass ({len(warnings)} warning(s))")
