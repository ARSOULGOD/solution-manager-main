#!/usr/bin/env python3
"""Executable checks for the v1 mesh event contract (docs/design/backend/design.md §3-§4, `BE-D4`).

Gate G3 is closed in-spec; this script is the executable half. Four checks:

  1. every ``fixtures/*.json`` — except ``expected-topics.json``, which is the topic manifest,
     not an event — validates against BOTH ``envelope.schema.json`` and ``notify-events.schema.json``;
  2. every ``negative/*.json`` — except ``bad-topics.json``, a manifest handled in check 4 — is
     REJECTED, **and rejected for its declared reason**. Any-red-is-the-right-red is the R6
     false-green class; each negative names the substring its rejection must mention.
  3. topic derivation: design §4's rules are implemented here, each fixture's topic is derived
     from its body, and must equal its ``expected-topics.json`` entry — bidirectionally (every
     fixture has an entry, every entry has a fixture) — and start with ``sm/v1/``. An interpolated
     value (``tenant``, ``entity.instanceId``, ``payload.applyId``) that contains ``/``, ``*`` or
     ``>`` is rejected rather than silently changing the topic's shape; that guard carries a
     planted-value self-test because every committed fixture is clean. Mirrored in the Java
     deriver (``backend/mesh-events`` ``NotifyTopics``), so the two stay one implementation.
  4. no ``bad-topics.json`` entry is derivable: the deriver cannot be talked into an ``sm/v2``
     address, nor into dropping a topic level (R6 finding 4).

Plus: ``asyncapi.yaml`` parses and every channel ``address`` starts with ``sm/v1/``, which keeps
the document honest without an AsyncAPI toolchain in CI.

    python3 validate-events.py

Exit 0 = the contract holds. Any miss prints a ``gate10 <file>: <reason>`` line and exits 1.
"""
import json, sys, glob, os, copy, re
from datetime import datetime
from urllib.parse import urljoin

from jsonschema import Draft202012Validator
from referencing import Registry, Resource
import yaml

HERE = os.path.dirname(os.path.abspath(__file__))

ENVELOPE = json.load(open(os.path.join(HERE, "envelope.schema.json")))
NOTIFY = json.load(open(os.path.join(HERE, "notify-events.schema.json")))

# The `$id`s are relative URI references (contract names — `sm/events/v1/envelope` — not URLs),
# so `$ref` resolution inside notify-events.schema.json urljoins the ref against the notify
# document's own base and asks for a different key than the envelope's declared `$id`. Register
# the envelope under both so the ref resolves without rewriting either `$id`.
_env_resource = Resource.from_contents(ENVELOPE)
REGISTRY = Registry().with_resources([
    (ENVELOPE["$id"], _env_resource),
    (urljoin(NOTIFY["$id"], ENVELOPE["$id"]), _env_resource),
])

# `format` is annotation-only in JSON Schema unless a checker is supplied, so the envelope's
# `uuid` and `date-time` would otherwise be decoration. jsonschema enforces `uuid` from stdlib
# but leaves `date-time` to the optional `rfc3339-validator`, which is not a dependency of this
# repo — an unenforceable constraint in a committed contract is the `L7` failure mode.
FORMAT_CHECKER = copy.deepcopy(Draft202012Validator.FORMAT_CHECKER)

# An RFC 3339 timestamp: date, `T`, time, and an EXPLICIT offset. `fromisoformat` alone is far
# too lenient to be this gate — verified on Python 3.14.4, it accepts every one of
# '2026-08-27', '20260827', '2026-08-27T10:00', '2026-08-27T10:00:00' (no offset) and
# '2026-08-27 10:00:00+00:00' (space separator). A Java producer using `Instant.parse` rejects
# all of those, so leaning on `fromisoformat` would let through precisely the producer/consumer
# drift this contract exists to catch. The shape is therefore pinned here and `fromisoformat`
# is used only for the calendar arithmetic the regex cannot do (month 13, Feb 30, offset 25:00).
#
# Deliberately stricter than RFC 3339 in one respect: the spec permits lowercase `t`/`z`, but
# whether the Java producer accepts them is unverified, so this gate requires uppercase rather
# than bless a form no implementation here has been tested against (`I2`).
_RFC3339 = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$")


# Registered unconditionally, overriding `rfc3339-validator` where that happens to be installed,
# so every machine running the suite enforces the same rule rather than a locally-varying one.
@FORMAT_CHECKER.checks("date-time", raises=ValueError)
def _is_rfc3339_date_time(value):
    if not isinstance(value, str):
        return True  # `type` handles non-strings; format must not double-report
    if not _RFC3339.match(value):
        raise ValueError("not an RFC 3339 timestamp with an explicit offset")
    datetime.fromisoformat(value[:-1] + "+00:00" if value.endswith("Z") else value)
    return True

VALIDATORS = {
    "envelope.schema.json": Draft202012Validator(
        ENVELOPE, registry=REGISTRY, format_checker=FORMAT_CHECKER),
    "notify-events.schema.json": Draft202012Validator(
        NOTIFY, registry=REGISTRY, format_checker=FORMAT_CHECKER),
}

# Each negative must be rejected for ITS reason, not merely rejected.
#
# Matching this against the WHOLE error set would be a false green: `oneOf` reports every
# branch's failures, so a document that mentions `placementId` anywhere collects an error
# mentioning `placementId` from some unrelated branch even when the constraint under test has
# been deleted. Reasons are therefore matched only against **envelope errors plus the errors of
# the single branch whose `event` const matches the document** — the branch that was supposed to
# accept it. Every other branch fails trivially on `event` and says nothing about this negative.
#
#   <negative> : (error location, substrings that must ALL appear in that one error)
NEGATIVE_REASON = {
    # I6: a metric value smuggled into a notify payload
    "value-in-notify.json": ("payload", ["Additional properties", "'cpuPercent'"]),
    # B/D15: the ordering field is mandatory
    "missing-state-version.json": ("<root>", ["'stateVersion' is a required property"]),
    # R5 finding 3: instance.health is placement-scoped
    "health-without-placement.json": ("entity", ["'placementId' is a required property"]),
    # catalog.package is solution-scoped — the entity-level `not` must reject instance identity
    "catalog-with-instance-id.json": ("entity", ["should not be valid", "instanceId"]),
    # the schema half of EventEnvelope's isBlank() guard — tenant is an ACL-bearing topic level (I5)
    "blank-tenant.json": ("tenant", ["does not match"]),
    # UI-D14: a member outside the closed health vocabulary must be refused by the enum itself
    "health-bad-state.json": ("payload/state", ["is not one of", "'YELLOW'"]),
    # the schema minimum on quarantine's count (the bound the Java lock also pins)
    "quarantine-bad-count.json": ("payload/count", ["less than the minimum of 1"]),
    # the OTHER end of the same bound, and the R12 finding: `minimum: 1` alone let
    # 2**63 validate here while Java's `asIntegral` refused it (64-bit boundary) — the
    # schema now carries the maximum, so both sides agree on what an integer IS.
    "quarantine-overflow-count.json": (
        "payload/count", ["is greater than the maximum of 9223372036854775807"]),
    # same class on the envelope's own integer: EventEnvelope.stateVersion is a `long`,
    # so Jackson refuses 2**63 at read while Python accepted it without this maximum.
    "stateversion-overflow.json": (
        "stateVersion", ["is greater than the maximum of 9223372036854775807"]),
}

# Manifests that live among the events but are not events.
FIXTURE_MANIFEST = "expected-topics.json"
NEGATIVE_MANIFEST = "bad-topics.json"


# ---------------------------------------------------------------- topic derivation (design §4)

# sm/v1/<tenant>/notify/instance/<instanceId>/<aspect>
ASPECT_BY_EVENT = {
    "instance.health": "health",
    "instance.config-state": "config-state",
    "instance.version": "version",
    "metrics.quarantine": "quarantine",
}


# Characters that would change the SHAPE of the topic a value is interpolated into: `/` adds
# levels, `*` and `>` are Solace subscription wildcards. Tenant is a topic level precisely so
# broker ACLs can prefix-match on it (the mesh half of `I5`), so a tenant of 'acme/../evil' or
# '>' is an isolation defect, not a cosmetic one. Mirrored in the Java deriver
# (backend/mesh-events NotifyTopics.levelSafe) so the two implementations stay one rule.
UNSAFE_IN_LEVEL = ("/", "*", ">")


def _level_safe(field, value):
    """The interpolated value, or ValueError if it would add levels or wildcards."""
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be a non-blank string, got {value!r}")
    for ch in UNSAFE_IN_LEVEL:
        if ch in value:
            raise ValueError(
                f"{field} must not contain a topic separator or wildcard "
                f"({', '.join(repr(c) for c in UNSAFE_IN_LEVEL)}): {value!r}")
    return value


def derive_topic(ev):
    """design §4 / `BE-D4`: the topic a producer must publish this event on.

    Raises ``KeyError`` for an event with no topic rule and ``ValueError`` for an interpolated
    value that would change the topic's shape; check 3 turns either into a ``gate10`` line.
    """
    tenant = _level_safe("tenant", ev["tenant"])
    entity = ev.get("entity") or {}
    event = ev["event"]
    if event in ASPECT_BY_EVENT:
        instance_id = _level_safe("entity.instanceId", entity["instanceId"])
        return f"sm/v1/{tenant}/notify/instance/{instance_id}/{ASPECT_BY_EVENT[event]}"
    if event == "instance.rollout":
        instance_id = _level_safe("entity.instanceId", entity["instanceId"])
        apply_id = _level_safe("payload.applyId", (ev.get("payload") or {})["applyId"])
        return f"sm/v1/{tenant}/notify/instance/{instance_id}/rollout/{apply_id}"
    if event == "catalog.package":
        return f"sm/v1/{tenant}/notify/catalog/package"
    raise KeyError(f"no topic rule for event {event!r}")


# ---------------------------------------------------------------- helpers

def _flatten(err):
    yield err
    for sub in err.context or []:
        yield from _flatten(sub)


def schema_errors(doc, validator_name):
    """Every violation of one schema, flattened through oneOf/anyOf context."""
    out = []
    for e in sorted(VALIDATORS[validator_name].iter_errors(doc), key=lambda e: list(e.path)):
        for sub in _flatten(e):
            where = "/".join(str(p) for p in sub.absolute_path) or "<root>"
            out.append(f"{validator_name} {where}: {sub.message}")
    return out


def branch_errors(doc):
    """Errors from the envelope plus the ONE `oneOf` branch that should have accepted `doc`.

    Returns ``(errors, None)``, or ``([], reason)`` when no branch claims this document's
    `event` — in which case there is no meaningful branch to blame.
    """
    branch = None
    for b in NOTIFY["oneOf"]:
        if b.get("properties", {}).get("event", {}).get("const") == doc.get("event"):
            branch = b
            break
    if branch is None:
        return [], f"no notify branch declares event {doc.get('event')!r}"
    errs = []
    for v in (VALIDATORS["envelope.schema.json"], Draft202012Validator(branch)):
        for e in sorted(v.iter_errors(doc), key=lambda e: list(e.path)):
            for sub in _flatten(e):
                where = "/".join(str(p) for p in sub.absolute_path) or "<root>"
                errs.append((where, sub.message))
    return errs, None


def load(path):
    with open(path) as fh:
        return json.load(fh)


# ---------------------------------------------------------------- the four checks

def check1_fixtures_valid(fixtures):
    errs = []
    for name, ev in fixtures:
        for vname in VALIDATORS:
            for e in schema_errors(ev, vname):
                errs.append(f"gate10 fixtures/{name}: {e}")
    if not errs:
        fmts = ", ".join(f for f in ("uuid", "date-time") if f in FORMAT_CHECKER.checkers)
        print(f"  PASS {len(fixtures)} fixtures validate against both schemas "
              f"(formats enforced: {fmts or 'none'})")
    return errs


def check2_negatives_rejected(negatives):
    errs = []
    for name, ev in negatives:
        # 1. it must be rejected by the contract as a whole ...
        if not (schema_errors(ev, "envelope.schema.json")
                + schema_errors(ev, "notify-events.schema.json")):
            errs.append(f"gate10 negative/{name}: ACCEPTED, should have been rejected")
            continue
        want = NEGATIVE_REASON.get(name)
        if want is None:
            errs.append(f"gate10 negative/{name}: no declared rejection reason — add one to "
                        f"NEGATIVE_REASON so 'any red' cannot pass as the right red")
            continue
        # 2. ... and rejected by the constraint it exists to exercise.
        where_want, substrings = want
        found, no_branch = branch_errors(ev)
        if no_branch:
            errs.append(f"gate10 negative/{name}: {no_branch}")
            continue
        hit = [m for (w, m) in found
               if w == where_want and all(s in m for s in substrings)]
        if not hit:
            errs.append(f"gate10 negative/{name}: rejected, but NOT by the constraint under test "
                        f"— no error at {where_want!r} matching {substrings}. Errors from the "
                        f"branch that should have accepted it: "
                        f"{[f'{w}: {m}' for w, m in found] or 'none — it satisfied that branch'}")
            continue
        print(f"  PASS (rejected at {where_want} by the constraint under test) negative/{name}")
    return errs


def _level_guard_self_test():
    """Prove the level-safety guard fires — every committed fixture is clean, so without this the
    rule could be deleted and check 3 would stay green (a rule that cannot fire is decoration)."""
    errs, planted = [], 0
    base = {"tenant": "acme", "entity": {"instanceId": "cache-prod-01"},
            "event": "instance.health", "payload": {"applyId": "apply-7"}}
    cases = [
        ("tenant", lambda d, v: d.update(tenant=v)),
        ("entity.instanceId", lambda d, v: d["entity"].update(instanceId=v)),
    ]
    rollout = [("payload.applyId", lambda d, v: d["payload"].update(applyId=v))]
    for field, plant in cases + rollout:
        for value in ("acme/evil", "x>", "y*"):
            doc = copy.deepcopy(base)
            if field == "payload.applyId":
                doc["event"] = "instance.rollout"
            plant(doc, value)
            planted += 1
            try:
                topic = derive_topic(doc)
            except ValueError as exc:
                if field not in str(exc):
                    errs.append(f"gate10 <level-guard>: rejected {field}={value!r} but the message "
                                f"does not name the field: {exc}")
                continue
            except KeyError as exc:
                errs.append(f"gate10 <level-guard>: {field}={value!r} raised the wrong error: {exc}")
                continue
            errs.append(f"gate10 <level-guard>: {field}={value!r} was ACCEPTED, deriving {topic!r} "
                        f"— a value that adds topic levels must be rejected")
    if not errs:
        print(f"  PASS level-safety guard rejects all {planted} planted separator/wildcard values")
    return errs


def check3_topics(fixtures, expected):
    errs, derived = [], {}
    errs.extend(_level_guard_self_test())
    for name, ev in fixtures:
        try:
            topic = derive_topic(ev)
        except (KeyError, ValueError) as exc:
            errs.append(f"gate10 fixtures/{name}: {exc}")
            continue
        derived[name] = topic
        if not topic.startswith("sm/v1/"):
            errs.append(f"gate10 fixtures/{name}: derived topic {topic!r} does not start with 'sm/v1/'")
        if name not in expected:
            errs.append(f"gate10 fixtures/{name}: no entry in {FIXTURE_MANIFEST}")
        elif expected[name] != topic:
            errs.append(f"gate10 fixtures/{name}: derived {topic!r} != expected {expected[name]!r}")
    for name in expected:
        if name not in derived:
            errs.append(f"gate10 {FIXTURE_MANIFEST}: entry {name!r} has no fixture")
    if not errs:
        print(f"  PASS {len(derived)} topics derive exactly as declared in {FIXTURE_MANIFEST}")
    return errs, derived


def check4_bad_topics_undecidable(derived, bad):
    errs = []
    for name, wrong in bad.items():
        if name not in derived:
            errs.append(f"gate10 {NEGATIVE_MANIFEST}: entry {name!r} has no fixture — "
                        f"the assertion below would be vacuous")
            continue
        if derived[name] == wrong:
            errs.append(f"gate10 {NEGATIVE_MANIFEST}: deriver produced the forbidden topic "
                        f"{wrong!r} for {name}")
    if not errs:
        print(f"  PASS deriver produces none of the {len(bad)} forbidden topics")
    return errs


def check_asyncapi():
    path = os.path.join(HERE, "asyncapi.yaml")
    errs = []
    try:
        with open(path) as fh:
            doc = yaml.safe_load(fh)
    except Exception as exc:  # noqa: BLE001 — any parse failure is a contract failure
        return [f"gate10 asyncapi.yaml: does not parse as YAML: {exc}"]
    channels = (doc or {}).get("channels") or {}
    if not channels:
        return ["gate10 asyncapi.yaml: no channels declared"]
    for cname, chan in channels.items():
        address = (chan or {}).get("address")
        if not address:
            errs.append(f"gate10 asyncapi.yaml: channel {cname!r} has no address")
        elif not address.startswith("sm/v1/"):
            errs.append(f"gate10 asyncapi.yaml: channel {cname!r} address {address!r} "
                        f"does not start with 'sm/v1/'")
    if not errs:
        print(f"  PASS asyncapi.yaml parses; all {len(channels)} channel addresses are sm/v1/")
    return errs


def main():
    fixtures, negatives = [], []
    for p in sorted(glob.glob(os.path.join(HERE, "fixtures", "*.json"))):
        b = os.path.basename(p)
        if b != FIXTURE_MANIFEST:
            fixtures.append((b, load(p)))
    for p in sorted(glob.glob(os.path.join(HERE, "negative", "*.json"))):
        b = os.path.basename(p)
        if b != NEGATIVE_MANIFEST:
            negatives.append((b, load(p)))

    expected = load(os.path.join(HERE, "fixtures", FIXTURE_MANIFEST))
    bad = load(os.path.join(HERE, "negative", NEGATIVE_MANIFEST))

    if not fixtures or not negatives or not bad:
        print("gate10 <suite>: fixtures, negatives and bad-topics must all be non-empty — "
              "an empty suite is a check that cannot fail")
        sys.exit(1)

    errs = []
    errs += check1_fixtures_valid(fixtures)
    errs += check2_negatives_rejected(negatives)
    e3, derived = check3_topics(fixtures, expected)
    errs += e3
    errs += check4_bad_topics_undecidable(derived, bad)
    errs += check_asyncapi()

    for e in errs:
        print(f"  FAIL {e}")
    sys.exit(1 if errs else 0)


if __name__ == "__main__":
    main()
