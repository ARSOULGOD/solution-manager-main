"""The profile's decisions block, the answers file, and the rules that follow from both."""
import copy
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import StrictLoader  # noqa: E402

BEGIN = "# >>> decisions: written by the skill from your preview answers; do not edit by hand >>>"
END = "# <<< decisions <<<"

SECTIONS = ("remove", "place", "store", "suggestions", "amend", "restyle", "provenance", "forget")
AMENDABLE = ("type", "default", "required", "discriminator", "when", "pattern", "min", "max", "options",
             "ui.label", "ui.section", "ui.order", "ui.advanced", "ui.helperText", "ui.readOnly")

PLACE_KINDS = {"no-component", "wrong-role", "doc2", "excluded", "shared-new", "role-exclusive-shared"}
SHARED_KINDS = {"shared-new", "role-exclusive-shared"}
CARDINALITIES = {"exactly-one", "one-of-many", "zero-or-more"}
KEYED = ("removed", "placed", "stored")
ORDER = ("removed", "placed", "stored", "suggestions", "amendments", "style", "provenance")


# ---- the block -------------------------------------------------------------------------------

def _locate(text):
    begin, end = text.find(BEGIN), text.find(END)
    if begin < 0 and end < 0:
        return None
    if begin < 0 or end < 0 or end < begin:
        raise ValueError("decisions block has only one marker (or the markers are out of order)")
    return begin, end


def read_block(text):
    found = _locate(text)
    if found is None:
        return {}
    begin, end = found
    body = text[begin + len(BEGIN):end]
    data = yaml.load(body, Loader=StrictLoader)
    if not isinstance(data, dict) or set(data) != {"decisions"} or not isinstance(data["decisions"], dict):
        raise ValueError("decisions block must be a mapping with a single 'decisions' mapping")
    return data["decisions"]


def write_block(text, decisions):
    block = (BEGIN + "\n"
             + yaml.safe_dump({"decisions": decisions}, sort_keys=False, allow_unicode=True, width=110)
             + END + "\n")
    found = _locate(text)
    if found is not None:
        begin, end = found
        after = end + len(END)
        if text[after:after + 1] == "\n":
            after += 1
        return text[:begin] + block + text[after:]
    sep = "" if (not text or text.endswith("\n")) else "\n"
    return text + sep + block


# ---- the answers file ------------------------------------------------------------------------

def load_answers(path):
    if path is None:
        return {}
    data = yaml.load(Path(path).read_text(encoding="utf-8"), Loader=StrictLoader)
    if data is None:
        return {}
    if not isinstance(data, dict):
        raise ValueError("answers file must be a mapping")
    unknown = [k for k in data if k not in SECTIONS]
    if unknown:
        raise ValueError("unknown answers section(s): " + ", ".join(str(k) for k in unknown))
    return data


def _entries(answers, section):
    value = answers.get(section) or []
    return [e for e in value if isinstance(e, dict)] if isinstance(value, list) else []


def _name(entry):
    if entry.get("key") is not None:
        return str(entry["key"])
    return "%s/%s" % (entry.get("component"), entry.get("field"))


def _target_ok(entry, ctx):
    if entry.get("key") is not None:
        return entry["key"] in ctx["yaml_keys"]
    return (entry.get("component"), entry.get("field")) in ctx["package_fields"]


def _remembered_keys(remembered):
    return {e.get("key") for s in KEYED for e in (remembered.get(s) or []) if e.get("key") is not None}


LIST_SECTIONS = ("remove", "place", "store", "suggestions", "amend", "forget")


def _shape_errors(answers):
    errors = []
    for section in LIST_SECTIONS:
        value = answers.get(section)
        if value is None:
            continue
        if not isinstance(value, list):
            errors.append("%s: must be a list of mappings" % section)
            continue
        for n, item in enumerate(value):
            if not isinstance(item, dict):
                errors.append("%s item %d: not a mapping" % (section, n))
    return errors


def validate_answers(answers, ctx):
    errors = _shape_errors(answers)
    open_items = ctx.get("open", {})
    remembered = ctx.get("remembered", {})

    for e in _entries(answers, "remove"):
        if not _target_ok(e, ctx):
            errors.append("remove %s: not a key in the YAML or a field in the package" % _name(e))
        reason = e.get("reason")
        if not isinstance(reason, str) or not reason.strip():
            errors.append("remove %s: a reason is required" % _name(e))

    for e in _entries(answers, "place"):
        key = e.get("key")
        kinds = {i.get("kind") for i in open_items.values() if i.get("key") == key}
        asked = bool(kinds & PLACE_KINDS)
        if not asked and key not in _remembered_keys(remembered):
            errors.append("place %s: no open question or remembered decision for this key" % key)
        if not isinstance(e.get("component"), str) or not e["component"]:
            errors.append("place %s: component missing" % key)
        new = e.get("new")
        if new is not None:
            if not isinstance(new, dict) or new.get("cardinality") not in CARDINALITIES or "role" not in new \
                    or "configPath" not in new:
                errors.append("place %s: new needs role, configPath and a cardinality of %s"
                              % (key, ", ".join(sorted(CARDINALITIES))))
        elif isinstance(e.get("component"), str) and e["component"] and e["component"] not in ctx["components"]:
            errors.append("place %s: component %r is not in the package" % (key, e.get("component")))
        if "scope" in e:
            if e["scope"] not in ("all-roles", "this-role"):
                errors.append("place %s: scope must be all-roles or this-role" % key)
            elif not (kinds & SHARED_KINDS):
                errors.append("place %s: scope applies only to shared keys" % key)

    for e in _entries(answers, "store"):
        key = e.get("key")
        items = [i for i in open_items.values() if i.get("key") == key]
        if not items:
            errors.append("store %s: no open question for this key" % key)
        elif not any(e.get("as") in (i.get("choices") or []) for i in items):
            errors.append("store %s: %r was not offered" % (key, e.get("as")))

    for e in _entries(answers, "suggestions"):
        key = e.get("key")
        target = ctx.get("fields", {}).get(key)
        item_id = "suggest:%s/%s/%s" % (target[0], target[1], e.get("attribute")) if target else None
        if target is None or item_id not in open_items:
            errors.append("suggestions %s: no open suggestion for %s" % (key, e.get("attribute")))
        elif e.get("decision") not in ("accept", "reject"):
            errors.append("suggestions %s: decision must be accept or reject" % key)

    for e in _entries(answers, "amend"):
        name = _name(e)
        if not _target_ok(e, ctx):
            errors.append("amend %s: not a key in the YAML or a field in the package" % name)
            continue
        setting = e.get("set")
        if not isinstance(setting, dict) or not setting:
            errors.append("amend %s: set must be a non-empty mapping" % name)
            continue
        for attr in setting:
            if attr not in AMENDABLE:
                errors.append("amend %s: %s cannot be amended" % (name, attr))
        if "default" in setting:
            if e.get("key") is not None:
                target = ctx.get("fields", {}).get(e["key"])
            else:
                target = (e.get("component"), e.get("field"))
            secret = setting.get("type") == "secret" or target in ctx.get("secrets", set())
            if secret and setting["default"] is not None:
                errors.append("amend %s: a secret field cannot take a default" % name)
            elif isinstance(setting["default"], (dict, list)):
                errors.append("amend %s: default must be a scalar or null" % name)

    if "restyle" in answers:
        if "restyle" not in open_items:
            errors.append("restyle restyle: no open restyle question")
        elif answers["restyle"] not in ("accept", "refuse"):
            errors.append("restyle restyle: must be accept or refuse")
    if "provenance" in answers:
        if "provenance" not in open_items:
            errors.append("provenance provenance: no open provenance question")
        elif answers["provenance"] not in ("keep", "dev-mode"):
            errors.append("provenance provenance: must be keep or dev-mode")

    for e in _entries(answers, "forget"):
        if e.get("key") is not None:
            hit = e["key"] in _remembered_keys(remembered)
        else:
            hit = any((s.get("component"), s.get("field"), s.get("attribute"))
                      == (e.get("component"), e.get("field"), e.get("attribute"))
                      for s in remembered.get("suggestions") or [])
        if not hit:
            errors.append("forget %s: nothing remembered under this name" % _name(e))
    return errors


# ---- remembered decisions + answers ----------------------------------------------------------

def _ident(entry):
    return ("key", entry["key"]) if entry.get("key") is not None else ("cf", entry.get("component"), entry.get("field"))


def _sug_ident(entry):
    return (entry.get("component"), entry.get("field"), entry.get("attribute"))


def _survivors(remembered, answers, new_role):
    """Remembered entries per section, minus those an answer replaces or forgets.

    A key-named answer replaces a remembered entry only when that entry's role is None or equals
    new_role(key); entries of other roles survive."""
    replaced_keys = {e["key"] for s in ("remove", "place", "store") for e in _entries(answers, s) if e.get("key") is not None}
    replaced_keys |= {e["key"] for e in _entries(answers, "forget") if e.get("key") is not None}
    replaced_cf = {_ident(e) for e in _entries(answers, "remove") + _entries(answers, "amend") if e.get("key") is None}
    forgotten_sug = {_sug_ident(e) for e in _entries(answers, "forget") if e.get("key") is None}
    amend_idents = {_ident(e) for e in _entries(answers, "amend")}
    out = {}
    for section in KEYED:
        out[section] = [e for e in remembered.get(section) or []
                        if (not (e["key"] in replaced_keys and e.get("role") in (None, new_role(e["key"])))
                            if e.get("key") is not None else _ident(e) not in replaced_cf)]
    out["suggestions"] = [e for e in remembered.get("suggestions") or []
                          if _sug_ident(e) not in forgotten_sug]
    out["amendments"] = [e for e in remembered.get("amendments") or []
                         if _ident(e) not in amend_idents and _ident(e) not in replaced_cf]
    return out


def _applies(entry, role):
    return entry.get("role") is None or entry.get("role") == role


def _restyle_word(value):
    return {"accept": "accepted", "refuse": "refused"}.get(value, value)


def effective(remembered, answers, role, *, fields=None):
    fields = fields or {}
    kept = _survivors(remembered, answers, lambda key: role)
    removed, placed, stored, sugs = {}, {}, {}, {}
    stale = []
    for e in kept["removed"]:
        if _applies(e, role):
            removed[_name(e)] = e.get("reason")
    for e in kept["placed"]:
        if _applies(e, role):
            placed[e["key"]] = {k: e[k] for k in ("component", "scope", "new") if k in e}
    for e in kept["stored"]:
        if _applies(e, role):
            stored[e["key"]] = e.get("as")
    for section in KEYED:
        for e in kept[section]:
            if e.get("role") == role and e.get("key") is not None and e["key"] not in stale:
                stale.append(e["key"])
    for e in kept["suggestions"]:
        sugs[_sug_ident(e)] = e.get("decision")
    for e in _entries(answers, "suggestions"):
        if e.get("key") in fields:
            sugs[(fields[e["key"]][0], fields[e["key"]][1], e.get("attribute"))] = e.get("decision")
    answered_cf = set()
    for e in _entries(answers, "amend"):
        if e.get("key") in fields:
            answered_cf.add(("cf",) + tuple(fields[e["key"]]))
    amendments = [copy.deepcopy(e) for e in kept["amendments"]
                  if _applies(e, role) and _ident(e) not in answered_cf]

    for e in _entries(answers, "remove"):
        removed[_name(e)] = e.get("reason")
    for e in _entries(answers, "place"):
        placed[e["key"]] = {k: e[k] for k in ("component", "scope", "new") if k in e}
    for e in _entries(answers, "store"):
        stored[e["key"]] = e.get("as")
    amendments += [copy.deepcopy(e) for e in _entries(answers, "amend")]

    style = (remembered.get("style") or {}).get("restyle")
    prov = (remembered.get("provenance") or {}).get("choice")
    if "restyle" in answers:
        style = _restyle_word(answers["restyle"])
    if "provenance" in answers:
        prov = answers["provenance"]
    return {"removed": removed, "placed": placed, "stored": stored, "suggestions": sugs,
            "amendments": amendments, "restyle": style, "provenance": prov, "stale_candidates": stale}


def merge(remembered, answers, *, today, placements, evidence, role=None):
    def role_of(key):
        return placements[key][2] if key in placements else role

    kept = _survivors(remembered, answers, role_of)

    def target(e):
        """(component, field) for an answer that names a key or a component+field."""
        if e.get("key") is not None and e["key"] in placements:
            return placements[e["key"]][0], placements[e["key"]][1]
        return e.get("component"), e.get("field")

    removed, placed, stored = list(kept["removed"]), list(kept["placed"]), list(kept["stored"])
    for e in _entries(answers, "remove"):
        if e.get("key") is not None:
            removed.append({"key": e["key"], "role": role_of(e["key"]), "date": today, "reason": e["reason"]})
        else:
            removed.append({"component": e["component"], "field": e["field"], "role": None, "date": today,
                            "reason": e["reason"]})
    for e in _entries(answers, "place"):
        entry = {"key": e["key"], "component": e["component"], "role": role_of(e["key"])}
        for extra in ("scope", "new"):
            if extra in e:
                entry[extra] = e[extra]
        entry["date"] = today
        placed.append(entry)
    for e in _entries(answers, "store"):
        stored.append({"key": e["key"], "as": e["as"], "role": role_of(e["key"]), "date": today})

    sugs = list(kept["suggestions"])
    for e in _entries(answers, "suggestions"):
        comp, fld = target(e)
        ident = (comp, fld, e["attribute"])
        sugs = [s for s in sugs if _sug_ident(s) != ident]
        sugs.append({"component": comp, "field": fld, "attribute": e["attribute"], "decision": e["decision"],
                     "date": today, "evidence": list(evidence.get(ident, []))})

    amends = list(kept["amendments"])
    for e in _entries(answers, "amend"):
        comp, fld = target(e)
        entry = {"component": comp, "field": fld} if comp is not None else {"key": e.get("key")}
        entry.update({"set": copy.deepcopy(e["set"]), "date": today, "reason": e.get("reason")})
        amends = [a for a in amends if _ident(a) != _ident(entry)]
        amends.append(entry)

    out = {"removed": removed, "placed": placed, "stored": stored, "suggestions": sugs, "amendments": amends}
    if remembered.get("style"):
        out["style"] = copy.deepcopy(remembered["style"])
    if remembered.get("provenance"):
        out["provenance"] = copy.deepcopy(remembered["provenance"])
    if "restyle" in answers:
        out["style"] = {"restyle": _restyle_word(answers["restyle"]), "date": today}
    if "provenance" in answers:
        out["provenance"] = {"choice": answers["provenance"], "date": today}
    return {s: out[s] for s in ORDER if out.get(s)}
