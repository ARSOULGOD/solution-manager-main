#!/usr/bin/env python3
"""Generate one deliberately-invalid package per load-time gate, by mutating the real
Cache fixture. Derived rather than hand-written so they cannot drift from it.

    python3 generate.py && python3 ../validate-package.py '*.json' --expect-fail
"""
import copy, json, os

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = json.load(open(os.path.join(HERE, "..", "fixtures", "cache-2.4.package.json")))


def comp(p):     return p["components"][0]
def field(p, k): return next(f for f in comp(p)["fields"] if f["key"] == k)


def m(fn, name, why):
    p = copy.deepcopy(BASE)
    fn(p)
    p["_why"] = why
    out = os.path.join(HERE, name)
    d = {k: v for k, v in p.items() if k != "_why"}
    json.dump(d, open(out, "w"), indent=2)
    open(out, "a").write("\n")
    return name, why


def g4(p):  field(p, "solace.host")["when"] = {"no-such-field": "x"}
def g5(p):  field(p, "solace.management.management-queue")["when"] = {"solace.management.enabled": True}
def g5c(p): field(p, "solace.host")["when"] = {"solace.enabled": False}          # contradicts nothing... use a real chain
def g5b(p): field(p, "payload-key-json-pointer")["when"] = {"key-source": "PAYLAOD"}
def g6(p):  field(p, "namespace")["discriminator"] = True                         # free-text string
def g7a(p): del comp(p)["metrics"][0]["unit"]
def g7b(p): del comp(p)["metrics"][0]["source"]["onUnavailable"]
def g8(p):  comp(p)["metrics"][0]["source"]["kind"] = "cache-stats"
def g9(p):  comp(p)["metrics"][1]["name"] = comp(p)["metrics"][0]["name"]
def g9f(p): comp(p)["fields"].append(copy.deepcopy(field(p, "namespace")))
def g10(p):
    comp(p)["fields"].append({"key": "solace.extra", "type": "yaml", "yamlSubtree": "solace"})
def g11(p):
    f = field(p, "provider"); f["optionsSource"] = {"kind": "sm-catalog", "entity": "broker"}
def g11u(p):
    f = field(p, "provider"); del f["options"]
    f["optionsSource"] = {"kind": "sm-catalog", "entity": "broker", "url": "https://evil/opts"}
def g2(p):  p["provenance"] = {"author": "solace"}                                # signed author, no signature
def g12(p):
    f = field(p, "solace.enabled"); f.pop("default", None); f.setdefault("ui", {})["readOnly"] = True
def g13(p):
    f = field(p, "solace.password"); f["type"] = "secret"; f["secret"] = False
def g14a(p):
    field(p, "provider")["version"] = [2]
def g14b(p):
    p["packageVersion"] = [1, 2]
    field(p, "provider")["version"] = [1]


CASES = [
    (g4,   "gate04-when-unknown-field.json",        "when: names a field that does not exist"),
    (g5,   "gate05-when-omits-ancestor.json",       "when: omits an ancestor its target requires"),
    (g5b,  "gate05b-when-value-not-an-option.json", "when: value is a typo, not an option member"),
    (g6,   "gate06-discriminator-free-text.json",   "discriminator on a free-text string"),
    (g7a,  "gate07-metric-missing-unit.json",       "metric declares metricType but no unit"),
    (g7b,  "gate07-metric-missing-onunavailable.json", "metric source omits onUnavailable"),
    (g8,   "gate08-solution-specific-kind.json",    "source.kind: cache-stats - the REQ-001 guard"),
    (g9,   "gate09-duplicate-metric-name.json",     "two metrics share a name in one component"),
    (g9f,  "gate09-duplicate-field-key.json",       "two fields share a key in one component"),
    (g10,  "gate10-yaml-subtree-collision.json",    "yaml subtree collides with declared keys"),
    (g11,  "gate11-options-and-optionssource.json", "field declares both options and optionsSource"),
    (g11u, "gate08-optionssource-supplies-url.json","optionsSource supplies a URL instead of an entity"),
    (g2,   "gate02-signed-author-no-signature.json","author=solace with no signature"),
    (g12,  "gate12-unreachable-readonly-discriminator.json", "predicate driver has no default and is read-only"),
    (g13,  "gate13-contradictory-secret.json",       "type=secret contradicts secret=false"),
    (g14a, "gate14-undeclared-field-version.json",  "field version is absent from packageVersion"),
    (g14b, "gate14-versioned-reference-gap.json",   "predicate driver is absent in an owner version"),
]

if __name__ == "__main__":
    for fn, name, why in CASES:
        m(fn, name, why)
        print(f"  wrote {name:<44} {why}")
    # gate 3 needs a second package, not a mutation
    a = copy.deepcopy(BASE); a["appliesTo"] = ">=2.0 <2.4"; a["packageVersion"] = [9]
    json.dump(a, open(os.path.join(HERE, "..", "fixtures", "overlap", "cache-2.0.package.json"), "w"), indent=2)
    print("  wrote fixtures/overlap/cache-2.0.package.json           overlaps cache-2.4's >=2.2 <2.5")
