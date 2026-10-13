#!/usr/bin/env python3
"""Drift + coverage check for the generated Postman files (stdlib + PyYAML).

1. Regenerates into a temp dir and fails if the committed files differ (drift).
2. Every operationId in console.yaml appears exactly once in the collection's operation folders (and nothing else does). The
   "Setup" folder (plan 013 T4.1) is the one place an operation may appear a second time: it must hold exactly
   the generator's SETUP_STEPS, in order, each naming a contracted operation.
3. Served / wired-not-counted-served / not-served markers agree with ENDPOINT-INVENTORY.md.
4. Every {{variable}} used in the collection is declared (collection variables or environment).

Run:  python3 contracts/openapi/postman/check-postman.py     (exit 0 = clean)
"""
import importlib.util
import json
import os
import re
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))


def load_generator():
    spec = importlib.util.spec_from_file_location("generate_postman", os.path.join(HERE, "generate-postman.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def walk(items):
    for it in items:
        if "item" in it:
            yield from walk(it["item"])
        else:
            yield it


def main():
    gen = load_generator()
    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        gen.generate(tmp)
        for name in (gen.COLLECTION_FILE, gen.ENVIRONMENT_FILE):
            committed = os.path.join(HERE, name)
            if not os.path.exists(committed):
                problems.append("missing committed file: %s" % name)
                continue
            with open(os.path.join(tmp, name), encoding="utf-8") as a, open(committed, encoding="utf-8") as b:
                if a.read() != b.read():
                    problems.append("DRIFT: %s differs from a fresh generation — rerun generate-postman.py" % name)

    contract = gen.load_contract()
    inv, code, served, wired = gen.served_sets(contract)
    for m, pth in sorted(inv - code):
        problems.append('inventory lists %s %s as served but console-gateway has no handler for it' % (m, pth))
    for m, pth in sorted(wired - code):
        problems.append('inventory lists %s %s as wired but console-gateway has no handler for it' % (m, pth))
    with open(os.path.join(HERE, gen.COLLECTION_FILE), encoding="utf-8") as f:
        collection = json.load(f)
    with open(os.path.join(HERE, gen.ENVIRONMENT_FILE), encoding="utf-8") as f:
        environment = json.load(f)

    if collection["info"]["schema"] != gen.SCHEMA_URL:
        problems.append("collection schema url is not v2.1.0")

    expected = {}
    for path, item in contract["paths"].items():
        for method, op in item.items():
            if method in ("get", "post", "put", "patch", "delete"):
                expected[op["operationId"]] = (method, path)

    setup_folders = [f for f in collection["item"] if f["name"] == gen.SETUP_FOLDER_NAME]
    if len(setup_folders) != 1:
        problems.append("the collection must hold exactly one '%s' folder (found %d)" % (gen.SETUP_FOLDER_NAME, len(setup_folders)))
    setup_items = list(walk(setup_folders[0]["item"])) if setup_folders else []
    setup_ids = []
    for it in setup_items:
        m = re.match(r"operationId: `([^`]+)`", it["request"]["description"]["content"])
        setup_ids.append(m.group(1) if m else None)
    if setup_ids != [step["op"] for step in gen.SETUP_STEPS]:
        problems.append("the Setup folder holds %r, want %r" % (setup_ids, [step["op"] for step in gen.SETUP_STEPS]))
    for op_id in setup_ids:
        if op_id not in expected:
            problems.append("the Setup folder names %s, which console.yaml does not define" % op_id)

    seen = {}
    for it in walk(collection["item"]):
        desc = it["request"]["description"]["content"]
        m = re.match(r"operationId: `([^`]+)`", desc)
        if not m:
            problems.append("item without an operationId line: %s" % it["name"])
            continue
        op_id = m.group(1)
        if not any(it is s for s in setup_items):
            seen[op_id] = seen.get(op_id, 0) + 1
        if op_id in expected:
            is_served = expected[op_id] in served
            is_wired = expected[op_id] in wired
            marked_served = "**SERVED —" in desc
            marked_wired = "**WIRED — NOT COUNTED SERVED" in desc
            marked_not = "CONTRACTED, NOT YET SERVED" in desc
            want = "served" if is_served else "wired" if is_wired else "not"
            got = [k for k, on in (("served", marked_served), ("wired", marked_wired), ("not", marked_not)) if on]
            if got != [want]:
                problems.append("%s: marker %s disagrees with ENDPOINT-INVENTORY.md (want %s)" % (op_id, got or "none", want))
    # W4-10: a REQUIRED query parameter is never emitted empty, and 501 is accepted only where the contract declares it
    # (or the operation is not served at all), so an unexpected 501 from a served route fails the test.
    declared = {}
    for path, item in contract["paths"].items():
        for method, op in item.items():
            if method in ("get", "post", "put", "patch", "delete"):
                declared[op["operationId"]] = {str(c) for c in op["responses"]}
    for it in walk(collection["item"]):
        desc = it["request"]["description"]["content"]
        m = re.match(r"operationId: `([^`]+)`", desc)
        if not m or m.group(1) not in expected:
            continue
        op_id = m.group(1)
        for q in it["request"]["url"].get("query", []):
            if not q.get("disabled") and q["value"] == "":
                problems.append("%s: required query parameter '%s' is emitted empty" % (op_id, q["key"]))
        script = "\n".join(it["event"][0]["script"]["exec"])
        allowed = re.search(r"pm\.expect\(\[([0-9, ]+)\]\)\.to\.include\(pm\.response\.code\)", script)
        listed = {c.strip() for c in allowed.group(1).split(",")} if allowed else set()
        unserved = expected[op_id] not in served and expected[op_id] not in wired
        if "501" in listed and "501" not in declared[op_id] and not unserved:
            problems.append("%s: the test script allows 501 but the contract does not declare it" % op_id)
    for op_id in expected:
        if seen.get(op_id, 0) != 1:
            problems.append("operationId %s appears %d times (want exactly 1)" % (op_id, seen.get(op_id, 0)))
    for op_id in seen:
        if op_id not in expected:
            problems.append("collection has %s, which console.yaml does not define" % op_id)

    declared = {v["key"] for v in collection["variable"]} | {v["key"] for v in environment["values"]}
    text = json.dumps(collection)
    used = set(re.findall(r"\{\{([A-Za-z0-9_]+)\}\}", text))
    for v in sorted(used - declared):
        problems.append("variable {{%s}} is used but not declared" % v)
    # every collection variable must be in the environment too, so the two stay a matching set
    for v in sorted({x["key"] for x in collection["variable"]} - {x["key"] for x in environment["values"]}):
        problems.append("collection variable %s missing from the environment file" % v)

    if problems:
        print("check-postman: FAILED")
        for p in problems:
            print("  - " + p)
        return 1
    n_served = sum(1 for e in expected.values() if e in served)
    n_wired = sum(1 for e in expected.values() if e in wired)
    print("check-postman: OK — %d operations, each exactly once (%d served, %d wired-not-counted-served, %d not served);"
          " no drift; variables consistent" % (len(expected), n_served, n_wired, len(expected) - n_served - n_wired))
    return 0


if __name__ == "__main__":
    sys.exit(main())
