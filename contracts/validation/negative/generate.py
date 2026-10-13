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
    f = field(p, "provider"); f["optionsSource"] = {"kind": "sm-catalog", "entity": "connection", "connectionType": "solace-broker"}
def g11u(p):
    f = field(p, "provider"); del f["options"]
    f["optionsSource"] = {"kind": "sm-catalog", "entity": "connection", "connectionType": "solace-broker", "url": "https://evil/opts"}
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
def gct(p):
    comp(p)["fields"].append({
        "key": "solaceBroker", "type": "enum", "virtual": True,
        "optionsSource": {"kind": "sm-catalog", "entity": "connection"},
        "ui": {"label": "Broker", "section": "Broker", "order": 9},
    })
def gvf15(p):
    comp(p)["fields"].append({
        "key": "brokerHost", "type": "string",
        "valueFrom": {"ref": "noSuchSelector", "attribute": "host"},
        "ui": {"label": "Broker host", "section": "Broker", "order": 9},
    })
def gvf17(p):
    comp(p)["fields"].append({
        "key": "solaceBrokerRef", "type": "enum", "virtual": True,
        "optionsSource": {"kind": "sm-catalog", "entity": "connection", "connectionType": "solace-broker"},
        "ui": {"label": "Broker", "section": "Broker", "order": 9},
    })
    comp(p)["fields"].append({
        "key": "brokerPasswordPlain", "type": "string",
        "valueFrom": {"ref": "solaceBrokerRef", "attribute": "password"},
        "ui": {"label": "Password (plain)", "section": "Broker", "order": 10},
    })
def g01a(p): del comp(p)["health"]["probe"]["strength"]
def g01b(p): comp(p)["health"]["probe"]["strength"] = "startup"
def g01c(p): del comp(p)["loggerPrefix"]                                          # loggers declared, nothing to validate a name against
def g01d(p): comp(p)["logs"]["loggers"]["path"] = "/actuator/loggers;rm"          # outside the probe-path grammar: it reaches a shell
def g01f(p): del comp(p)["logs"]["baselineExposure"]                              # SM's exposure write would withdraw what the MI exposes today
def g01e(p): comp(p)["logs"]["loggers"]["access"] = "read-write"                  # a write surface is not a package's to declare
# Fable review (code-GW-2): both patterns must end with (?![\s\S]), not $ (MF24): Python's $ accepts ONE trailing newline, the Java gate refuses it
def g01g(p): comp(p)["logs"]["loggers"]["path"] = "/actuator/loggers\n"
def g01h(p): comp(p)["logs"]["loggers"]["levelProperty"] = "logging.level\n"

# ---- P11-1.1 (M-Q4, M-Q9, OQ-7): per-kind metric sources, the restart signal, name/unit bounds --------------
# Every new pattern ends with (?![\s\S]) rather than $: Python jsonschema accepts ONE trailing newline under
# $ and the Java validator refuses it, so a `$` here would break parity (MF24).

def metric(p, name):  return next(x for x in comp(p)["metrics"] if x["name"] == name)
def size_src(p):      return metric(p, "cache.size")["source"]


# the nine bad characters/sequences a queryParams key or value must refuse (keys AND values)
BAD_QP = [
    ("subshell",         "$(id)"),
    ("ampersand",        "&"),
    ("quote",            "'"),
    ("semicolon",        ";"),
    ("backtick",         "`"),
    ("space",            " "),
    ("percent",          "%"),
    ("trailing-newline", "\n"),          # the one case a `$` anchor lets through in the Python (MF24)
    ("embedded-newline", "\n"),
]


def _token(label, bad, stem):
    if label == "trailing-newline": return stem + "\n"
    if label == "embedded-newline": return stem[:1] + "\n" + stem[1:]
    return stem[:1] + bad + stem[1:]


def qp_key(label, bad):
    def fn(p): size_src(p)["queryParams"] = {_token(label, bad, "abc"): "false"}
    return fn


def qp_val(label, bad):
    def fn(p): size_src(p)["queryParams"] = {"filterByNamespace": _token(label, bad, "false")}
    return fn


def g1_prom_qp(p):
    comp(p)["metrics"][0]["source"] = {"kind": "prometheus", "path": "/metrics", "queryParams": {"a": "b"}, "onUnavailable": "degrade"}
def g1_event_qp(p):
    # P11-5.1: valid in every other way, so queryParams is the only reason it is refused
    with_event(queryParams={"a": "b"})(p)
def g1_interval_lo(p):   size_src(p)["intervalSeconds"] = 5
def g1_interval_hi(p):   size_src(p)["intervalSeconds"] = 3601
def g1_port_hi(p):       size_src(p)["port"] = 70000
def g1_ptr_dotdot(p):    metric(p, "cache.hit_rate")["source"]["pointer"] = "/measurements/../value"
def g1_ptr_space(p):     metric(p, "cache.hit_rate")["source"]["pointer"] = "/hit Rate"
def g1_path_and_topic(p): size_src(p)["topicPattern"] = "a/b/c"
def g1_restart_qp(p):
    comp(p)["restartSignal"] = {"kind": "http_json", "path": "/actuator/metrics/process.start.time", "port": 8081,
                                "queryParams": {"a": "b"}, "onUnavailable": "degrade"}
def g1_restart_interval(p):
    comp(p)["restartSignal"] = {"kind": "http_json", "path": "/actuator/metrics/process.start.time", "port": 8081,
                                "intervalSeconds": 30, "onUnavailable": "degrade"}
def g1_restart_noport(p):
    comp(p)["restartSignal"] = {"kind": "http_json", "path": "/actuator/metrics/process.start.time",
                                "pointer": "/measurements/0/value", "onUnavailable": "degrade"}
def g1_default_chart(p): metric(p, "cache.hit_rate")["defaultChart"] = "no"
def g1_name(bad):
    def fn(p): metric(p, "cache.hit_rate")["name"] = bad
    return fn
def g1_unit(bad):
    def fn(p): metric(p, "cache.hit_rate")["unit"] = bad
    return fn

# ---- P11-5.1 (OR-106, OR-107, OQ-5 (a) payload form, OQ-29 (b)): the solace_event branch and gate 16 ------------
# `namespace` is a plain string field of the Cache component, so it is a legal instanceKey.configField. Every case
# below starts from a source that is valid in every other way, so the one thing it changes is the only reason it is
# refused (the same discipline as g1_event_qp).

def event_source(**over):
    src = {"kind": "solace_event", "topicPattern": "a/b/*/heartbeat",
           "instanceKey": {"configField": "namespace", "pointer": "/header/adapterID"},
           "extract": "count", "onUnavailable": "degrade"}
    for k, v in over.items():
        if v is None:
            src.pop(k, None)
        else:
            src[k] = v
    return src


def with_event(metric_type="counter", **over):
    def fn(p):
        mt = comp(p)["metrics"][0]
        mt["metricType"] = metric_type
        mt["source"] = event_source(**over)
    return fn


def ev_topic(pattern):         return with_event(topicPattern=pattern)
def ev_key(key):               return with_event(instanceKey=key)
g1e_no_key             = with_event(instanceKey=None)
g1e_value_no_pointer   = with_event(extract=None)                          # value extraction by default, so valuePointer is required
# (W3-05: a count WITH a valuePointer is NOT a negative: OR-106 makes valuePointer optional for a count, so it is valid and
# ignored; MutantParityTest.aCountWithAValuePointerIsAcceptedByBothValidators... holds it as a positive in both validators.)
g1e_count_on_gauge     = with_event(metric_type="gauge")
g1e_unknown_extract    = with_event(extract="rate", valuePointer="/header/size")   # otherwise valid, so the enum is the only reason
g1e_with_path          = with_event(path="/x")
g1e_filter_no_equals   = with_event(eventFilter={"pointer": "/header/eventType"})
g1e_filter_dotdot      = with_event(eventFilter={"pointer": "/header/../eventType", "equals": "6"})


def g16_secret_type(p):
    with_event()(p); field(p, "namespace")["type"] = "secret"
def g16_secret_flag(p):
    with_event()(p); field(p, "namespace")["secret"] = True
def g16_no_such_field(p):
    with_event(instanceKey={"configField": "no.such.field", "pointer": "/header/adapterID"})(p)


def g15(p):
    # a second component declaring a metric the first already declares: M-Q5's key has no component. The second
    # component carries no fields and no `when`, so no other gate can be the one that fires.
    extra = copy.deepcopy(comp(p)["metrics"][0])
    extra.pop("when", None)
    p["components"].append({"type": "cache-sidecar", "cardinality": "zero-or-more", "role": "none",
                            "configPath": "sidecar", "metrics": [extra]})


CASES = [
    (g01a, "gate01-probe-missing-strength.json",    "health probe declares no strength (S0-10)"),
    (g01b, "gate01-probe-unknown-strength.json",     "health probe strength is not a declared member"),
    (g01c, "gate01-loggers-without-loggerprefix.json", "logs.loggers declared on a component with no loggerPrefix (OR-103)"),
    (g01d, "gate01-loggers-path-outside-grammar.json", "logs.loggers.path leaves the path grammar (OR-103)"),
    (g01f, "gate01-loggers-without-baselineexposure.json", "logs.loggers declared with no logs.baselineExposure (OR-103)"),
    (g01e, "gate01-loggers-unknown-member.json",     "logs.loggers carries a member the schema does not define (OR-103)"),
    (g01g, "gate01-loggers-path-trailing-newline.json", "logs.loggers.path ends in a newline: Python's $ would accept it (MF24)"),
    (g01h, "gate01-loggers-levelproperty-trailing-newline.json", "logs.loggers.levelProperty ends in a newline: Python's $ would accept it (MF24)"),
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

    # ---- P11-1.1 ----
]
for _label, _bad in BAD_QP:
    CASES.append((qp_key(_label, _bad), f"gate01-queryparams-key-{_label}.json", f"queryParams KEY carries {_label} (closed grammar, M-Q4)"))
for _label, _bad in BAD_QP:
    CASES.append((qp_val(_label, _bad), f"gate01-queryparams-value-{_label}.json", f"queryParams VALUE carries {_label} (closed grammar, M-Q4)"))
CASES += [
    (g1_prom_qp,        "gate01-queryparams-on-prometheus.json",     "queryParams exists only on http_json (M-Q4)"),
    (g1_event_qp,       "gate01-queryparams-on-solace-event.json",   "queryParams exists only on http_json (M-Q4)"),
    (g1_interval_lo,    "gate01-interval-below-minimum.json",        "intervalSeconds 5 < 10 (OQ-9 (a))"),
    (g1_interval_hi,    "gate01-interval-above-maximum.json",        "intervalSeconds 3601 > 3600 (OQ-9 (a))"),
    (g1_port_hi,        "gate01-port-out-of-range.json",             "port 70000 is not a port"),
    (g1_ptr_dotdot,     "gate01-pointer-dot-dot-segment.json",       "pointer carries a .. segment"),
    (g1_ptr_space,      "gate01-pointer-space.json",                 "pointer carries a space"),
    (g1_path_and_topic, "gate01-source-path-and-topicpattern.json",  "an http_json source also names a topicPattern"),
    (g1_restart_qp,     "gate01-restartsignal-queryparams.json",     "restartSignal has no queryParams (OQ-1 (a))"),
    (g1_restart_interval, "gate01-restartsignal-interval.json",      "restartSignal has no intervalSeconds (OQ-1 (a))"),
    (g1_restart_noport, "gate01-restartsignal-without-port.json",    "restartSignal names no port: it could never be fetched (W2-05, OQ-1 (a))"),
    (g1_default_chart,  "gate01-defaultchart-not-boolean.json",      "defaultChart must be a boolean (OQ-7 (b))"),
    (g1_name("Cache.Hits"),   "gate01-metric-name-uppercase.json",   "metric name must match P11-2.2's DB CHECK"),
    (g1_name("1cache"),       "gate01-metric-name-digit-first.json", "metric name must start with a letter"),
    (g1_name("cache hits"),   "gate01-metric-name-space.json",       "metric name has a space"),
    (g1_name("c" * 129),      "gate01-metric-name-129-chars.json",   "metric name over 128 characters"),
    (g1_unit(""),             "gate01-metric-unit-empty.json",       "metric unit is empty (DB CHECK length 1..64)"),
    (g1_unit("u" * 65),       "gate01-metric-unit-65-chars.json",    "metric unit over 64 characters"),

    # ---- P11-5.1: the solace_event branch (OQ-5 payload form, OQ-29 (b) count extraction) ----
    (ev_topic("a/b/>"),         "gate01-solace-event-last-level-greater-than.json", "I6: the last level is a literal, so a start event is never subscribed"),
    (ev_topic("a/b/*"),         "gate01-solace-event-last-level-star.json",         "I6: the last level is a literal (no *)"),
    (ev_topic("a/b/{instanceKey}"), "gate01-solace-event-last-level-substitution.json", "no per-instance subscriptions (OR-107): no substitution token"),
    (ev_topic("a/{instanceKey}/heartbeat"), "gate01-solace-event-middle-level-substitution.json", "no per-instance subscriptions (OR-107): no substitution token anywhere"),
    (ev_topic("a/#/heartbeat"), "gate01-solace-event-topic-hash.json",              "topicPattern carries #"),
    (ev_topic("a/+/heartbeat"), "gate01-solace-event-topic-plus.json",              "topicPattern carries +"),
    (ev_topic("a//heartbeat"),  "gate01-solace-event-topic-empty-level.json",       "topicPattern has an empty level"),
    (g1e_no_key,                "gate01-solace-event-no-instancekey.json",          "an event with no attribution key could never be attributed (OQ-5 (a))"),
    (ev_key({"configField": "namespace", "pointer": "/header/../adapterID"}), "gate01-solace-event-instancekey-pointer-dotdot.json", "instanceKey.pointer carries a .. segment"),
    (ev_key({"configField": "namespace", "pointer": "/header/adapterID", "topicLevel": 7}), "gate01-solace-event-instancekey-extra-member.json", "instanceKey is exactly {configField, pointer}"),
    (ev_key({"configField": "namespace"}), "gate01-solace-event-instancekey-no-pointer.json", "instanceKey names the payload pointer it is matched against"),
    (g1e_value_no_pointer,      "gate01-solace-event-value-extraction-no-pointer.json", "value extraction reads a declared pointer (OQ-29 (a))"),
    (g1e_count_on_gauge,        "gate01-solace-event-count-on-a-gauge.json",        "a count of events is a counter (OR-106)"),
    (g1e_unknown_extract,       "gate01-solace-event-unknown-extraction.json",      "extract is a closed set: value | count"),
    (g1e_with_path,             "gate01-solace-event-with-path.json",               "path belongs to http_json"),
    (g1e_filter_no_equals,      "gate01-solace-event-filter-no-equals.json",        "eventFilter is {pointer, equals}"),
    (g1e_filter_dotdot,         "gate01-solace-event-filter-pointer-dotdot.json",   "eventFilter.pointer carries a .. segment"),
]

# HELD, not written: gate 15's negative. frontend/contracts/solution-package/validate.test.ts iterates EVERY file in
# validation/negative/ and expects each rejected, and validate.ts (frontend-owned; this plan may not edit frontend/)
# has no gate 15 - so the negative turns the frontend `contracts` project red (measured 2026-10-06: 72 of 73 pass).
# That is plan 011 P11-1.1 Step 0b's stop condition (OQ-24), routed to the owner. Gate 15 itself is implemented in
# validate-package.py and SemanticGates.java and proven by CrossComponentMetricGateTest (Java, with the Python as
# oracle). To land the negative: add gate 15 to frontend validate.ts, then move this entry into CASES.
HELD_CASES = [
    (g15,  "gate15-metric-name-repeated-across-components.json", "two components each declare the same metric name (M-Q5)"),
    # P11-5.1: gate 16 is semantic too, and validate.ts has no gate 16 either (same reason, same route: OQ-24). Proven
    # by InstanceKeyGateTest (Java, with the Python as oracle) until the frontend gains the gate.
    (g16_secret_type, "gate16-instancekey-names-a-secret-field.json", "instanceKey.configField names a field of type secret (I6)"),
    (g16_secret_flag, "gate16-instancekey-names-a-secret-flagged-field.json", "instanceKey.configField names a field flagged secret (I6)"),
    (g16_no_such_field, "gate16-instancekey-names-no-field.json", "instanceKey.configField names no field of the component"),
]

if __name__ == "__main__":
    for fn, name, why in CASES:
        m(fn, name, why)
        print(f"  wrote {name:<44} {why}")
    # gate 3 needs a second package, not a mutation
    a = copy.deepcopy(BASE); a["appliesTo"] = ">=2.0 <2.4"; a["packageVersion"] = [9]
    json.dump(a, open(os.path.join(HERE, "..", "fixtures", "overlap", "cache-2.0.package.json"), "w"), indent=2)
    print("  wrote fixtures/overlap/cache-2.0.package.json           overlaps cache-2.4's >=2.2 <2.5")

