#!/usr/bin/env python3
"""UI-D27 gate: every descriptor validates, attribute keys are unique, and a
`when` predicate names a discriminator declared in the same descriptor."""
import json, pathlib, sys
from jsonschema import Draft202012Validator

here = pathlib.Path(__file__).parent
schema = json.loads((here / "descriptor.schema.json").read_text())
validator = Draft202012Validator(schema)
failures = []

for path in sorted(here.glob("*.json")):
    if path.name == "descriptor.schema.json":
        continue
    doc = json.loads(path.read_text())
    for err in validator.iter_errors(doc):
        failures.append(f"{path.name}: {'/'.join(map(str, err.path))}: {err.message}")

    keys = [a["key"] for a in doc.get("attributes", [])]
    dupes = {k for k in keys if keys.count(k) > 1}
    if dupes:
        failures.append(f"{path.name}: duplicate attribute keys: {sorted(dupes)}")

    discriminators = {a["key"] for a in doc.get("attributes", []) if a.get("discriminator")}
    for attr in doc.get("attributes", []):
        for pred_key in (attr.get("when") or {}):
            if pred_key not in discriminators:
                failures.append(
                    f"{path.name}: {attr['key']}.when names '{pred_key}', "
                    f"which is not a discriminator in this descriptor"
                )

if failures:
    print("\n".join(f"  FAIL {f}" for f in failures))
    sys.exit(1)
print(f"  OK: {len(list(here.glob('*.json'))) - 1} descriptors valid")
