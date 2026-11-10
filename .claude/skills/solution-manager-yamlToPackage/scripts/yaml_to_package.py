#!/usr/bin/env python3
"""Generic engine: turn one role's YAML into Solution Package fields through a CONFIRMED mapping
profile, merge them into an existing package, validate, and report.

    python3 yaml_to_package.py --profile references/profiles/file-mesh.yaml --role source \
        --yaml application-source-file-v2.yml --target-versions 1,2,3 \
        --package frontend/contracts/packages/file-mesh/package.json --out-dir /tmp/out

Every YAML key ends up as exactly one of: placed (it becomes a field), open (a question with fixed
choices) or removed by you (a recorded decision). Answers come from --answers and from the
profile's decisions block.

Writes to --out-dir: package.json, report.md, report.json and (with --package) package.diff.
It never writes over --package; copying the result into place is a separate, deliberate step.

Exit codes: 0 done (read the report: open questions still need answers), 1 the result fails
validation, 2 bad input, 3 the profile is a draft, 4 a version decision is needed.
"""
import argparse
import copy
import datetime
import difflib
import json
import os
import re
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402
import decisions  # noqa: E402
import validate_package  # noqa: E402

FIELD_ATTR_ORDER = ["key", "type", "default", "ui", "options", "required", "secret", "discriminator", "when",
                    "pattern", "min", "max", "role", "version"]

PLACE = ["place", "remove"]
CHOICES = {
    "no-component": PLACE, "wrong-role": PLACE, "doc2": PLACE, "excluded": PLACE,
    "spelling": ["remove"],
    "list": ["list", "join-comma", "index", "remove"],
    "empty-map": ["map", "remove"],
    "decimal": ["string", "remove"],
    "unsupported": ["string", "remove"],
    "enum-value": ["add-option", "string", "remove"],
    "dup-key": ["first", "second", "remove"],
    "type-conflict": ["package-type", "yaml-type", "remove"],
    "shared-new": ["all-roles", "this-role", "role-component", "remove"],
    "role-exclusive-shared": ["this-role", "role-component", "remove"],
}
KEY_KINDS = set(CHOICES)   # the questions about one YAML key (package-level items come later)
# shared-new about a shared field that serves another role only: one component cannot hold two fields with one key
FOREIGN_FIELD_CHOICES = ["all-roles", "role-component", "remove"]
# answers that settle a question raised against the target field (store `as`)
SETTLES = {"enum-value": ("add-option", "string"), "type-conflict": ("package-type", "yaml-type")}


def die(code, msg):
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(code)


def load_profile(path):
    with open(path, encoding="utf-8") as fh:
        prof = yaml.load(fh, Loader=common.StrictLoader)  # keeps `[yes, no]` as strings
    for req in ("solution", "components"):
        if req not in prof:
            die(2, f"profile {path} has no {req!r}")
    return prof


def ordered_field(d):
    out = {k: d[k] for k in FIELD_ATTR_ORDER if k in d}
    out.update({k: v for k, v in d.items() if k not in out})
    return out


def read_text(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def write_text(path, text):
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def is_shared(spec):
    return bool(spec.get("shared")) or spec.get("role") in (None, "none")


def question_choices(kind, context):
    """The choices a key question offers."""
    if kind == "shared-new" and (context or {}).get("field_role"):
        return list(FOREIGN_FIELD_CHOICES)
    return list(CHOICES[kind])


def compatible(package_type, yaml_type, shared):
    """Today's rule: may a YAML value of yaml_type feed a package field declared package_type?"""
    if package_type == yaml_type or {package_type, yaml_type} == {"string", "duration"}:
        return True
    if shared:
        return package_type in ("enum", "secret")
    return package_type == "secret" or (package_type == "enum" and yaml_type in ("string", "enum"))


class Run:
    def __init__(self, args):
        self.args = args
        self.profile = load_profile(args.profile)
        self.role = args.role
        self.today = args.today
        try:
            with open(args.profile, encoding="utf-8") as fh:
                self.remembered = decisions.read_block(fh.read())
        except (ValueError, yaml.YAMLError) as e:
            die(2, f"profile {args.profile}: decisions block: {e}")
        try:
            self.answers = decisions.load_answers(args.answers)
        except (ValueError, yaml.YAMLError, OSError) as e:
            die(2, f"answers {args.answers}: {e}")
        self.decided = decisions.effective(self.remembered, self.answers, self.role)
        # the three outcomes
        self.placed, self.removed, self.open = [], [], {}
        # bookkeeping
        self.key_count, self.yaml_keys, self.info, self.placements = 0, [], [], {}
        self.mapped, self.coerced, self.withheld = [], [], []
        self.notes, self.questions, self.shared_diffs, self.ui_diffs = [], [], [], []
        self.diff_rows, self.touched, self.relocated, self.extra_specs = {}, set(), {}, {}
        self.held_fields = set()
        self.acronyms = common.ACRONYMS | {a.lower() for a in self.profile.get("acronyms", [])}

    # ---------------------------------------------------------------- the three outcomes
    def ask(self, kind, key, line, text, choices, context=None):
        """Record an open question. A key of None makes a package-level item whose id is the kind alone."""
        if key is None:
            qid = kind
        else:
            qid = f"{kind}:{common.normalise_key(key) if kind == 'spelling' else key}"
        self.open[qid] = {"id": qid, "kind": kind, "key": key, "line": line, "text": text,
                          "choices": list(choices), "context": context or {}}

    def open_keys(self):
        keys = set()
        for q in self.open.values():
            if q["kind"] == "spelling":
                keys.update(q["context"]["keys"])
            elif q["kind"] in KEY_KINDS:
                keys.add(q["key"])
        return keys

    def balanced(self):
        """Every YAML key is exactly one of placed, open or removed."""
        keys = set(self.yaml_keys)
        placed = [p["key"] for p in self.placed]
        removed = [r["key"] for r in self.removed if r["line"] is not None]   # line None: a package-only field
        held = self.open_keys()
        if len(set(placed)) != len(placed) or len(set(removed)) != len(removed):
            return False
        groups = (set(placed), set(removed), held)
        return (set().union(*groups) == keys and sum(len(g) for g in groups) == len(keys))

    # ---------------------------------------------------------------- preconditions
    def check_inputs(self):
        p = self.profile
        if p.get("status") != "confirmed":
            die(3, f"profile {self.args.profile} is status {p.get('status')!r}; show it to the user and "
                   "set status: confirmed only after they approve it")
        roles = p.get("roles") or []
        if roles and self.role not in roles:
            die(2, f"role {self.role!r} is not one of the profile's roles {roles}")
        if not roles and self.role != "none":
            die(2, f"profile declares no roles; use --role none")
        try:
            datetime.date.fromisoformat(self.today)
        except ValueError:
            die(2, f"--today {self.today!r} is not a YYYY-MM-DD date")
        self.target = sorted({int(v) for v in self.args.target_versions.split(",") if v.strip()})
        if not self.target:
            die(4, "--target-versions is required: ask the user which package version(s) this YAML targets")
        if self.args.package:
            with open(self.args.package, encoding="utf-8") as fh:
                self.old = json.load(fh)
            if self.old.get("solution") != p["solution"]:
                die(2, f"package solution {self.old.get('solution')!r} != profile solution {p['solution']!r}")
            missing = [v for v in self.target if v not in self.old["packageVersion"]]
            if missing:
                die(4, f"target version(s) {missing} are not in packageVersion {self.old['packageVersion']}. "
                       "Adding a package version is a package-wide decision (every component must say whether it "
                       "carries it); ask the user instead of introducing it on one component.")
            self.pkg = copy.deepcopy(self.old)
        else:
            if not self.args.applies_to:
                die(2, "no --package given, so this creates one: --applies-to (semver range of MI versions) is required")
            self.old = None
            self.pkg = {"solution": p["solution"], "packageVersion": self.target, "appliesTo": self.args.applies_to,
                        "provenance": {"author": "dev-mode"}, "authoring": {"reviewedRows": 0, "totalRows": 0},
                        "components": []}

    # ---------------------------------------------------------------- classification
    def spec_for(self, ctype):
        """The component's description: the profile's, else a `new` answer's, else the package's own."""
        spec = next((c for c in self.profile["components"] if c["type"] == ctype), None) \
            or self.extra_specs.get(ctype)
        if spec is None:
            comp = self.find_comp(ctype)
            if comp is None:
                die(2, f"component {ctype!r} is in neither the profile nor the package")
            spec = {k: comp.get(k) for k in ("type", "role", "cardinality", "configPath", "loggerPrefix")
                    if comp.get(k) is not None}
            self.extra_specs[ctype] = spec
        return spec

    def field_key(self, key, spec):
        """The field key for a YAML key in this component, or None when a relative key falls outside configPath."""
        style = spec.get("key_style") or self.profile.get("key_style") or "full-dotted"
        cp = spec.get("configPath", "")
        if style == "relative-to-configPath" and cp:
            return key[len(cp) + 1:] if key.startswith(cp + ".") else None
        return key

    def make_target(self, ctype, spec, pattern, fkey):
        return {"component": ctype, "spec": spec, "pattern": pattern, "fkey": fkey, "shared": is_shared(spec)}

    def natural(self, leaf, honour_exclude=True):
        """Where the profile puts this key: a target dict, or a question tuple (kind, text, context, target)."""
        p, key = self.profile, leaf["key"]
        if leaf["doc"] > 0:
            return ("doc2", f"in YAML document {leaf['doc'] + 1}; only document 1 is mapped by the profile. "
                            "Place it in a component, or remove it?", {}, None)
        ex = next((e for e in p.get("exclude") or [] if common.matches(e["match"], key)), None) \
            if honour_exclude else None
        if ex:
            found = self.natural(leaf, honour_exclude=False)
            tentative = found if isinstance(found, dict) else found[3]
            return ("excluded", f"the profile used to drop this key because {ex['reason']!r}; keep it (place it) "
                                "or remove it?",
                    {"reason": ex["reason"], "component": (tentative or {}).get("component")}, tentative)
        rex = next((r for r in p.get("role_exclusive") or [] if common.matches(r["match"], key)), None)
        if rex and self.role not in rex["roles"]:
            return ("wrong-role", f"the profile marks this exclusive to role(s) {rex['roles']}, found in a "
                                  f"{self.role} YAML. Place it in this role's component, or remove it?",
                    {"roles": list(rex["roles"])}, None)
        all_patterns = [(pat, c["type"]) for c in p["components"] for pat in c.get("match") or []]
        hit = common.best_match([pat for pat, _ in all_patterns], key)
        if hit is None:
            return ("no-component", "no profile component claims this key. Place it in an existing or a new "
                                    "component, or remove it?", {}, None)
        # several role components may claim the same prefix (one copy per role): the run's own role wins
        cands = [t for pat, t in all_patterns if pat == hit]
        ctype = next((t for t in cands if self.spec_for(t).get("role") == self.role), cands[0])
        spec = self.spec_for(ctype)
        shared = is_shared(spec)
        if not shared and spec.get("role") != self.role:
            return ("wrong-role", f"matches component {ctype} of role {spec.get('role')!r}, not {self.role!r}. "
                                  "Place it in this role's component, or remove it?", {"component": ctype}, None)
        fkey = self.field_key(key, spec)
        if fkey is None:
            return ("no-component", f"key_style is relative but the key is not under {ctype}'s configPath "
                                    f"{spec.get('configPath')!r}. Place it elsewhere, or remove it?",
                    {"component": ctype}, None)
        tgt = self.make_target(ctype, spec, hit, fkey)
        if rex and shared:
            return ("role-exclusive-shared", f"exclusive to role(s) {rex['roles']} ({rex.get('reason', '')}) but "
                                             f"it would land in shared component {ctype}. Give it field role "
                                             f"{self.role!r} there, move it to this role's component, or remove it?",
                    {"component": ctype, "roles": list(rex["roles"])}, tgt)
        comp = self.find_comp(ctype)
        existing = next((f for f in (comp or {}).get("fields", []) if f["key"] == fkey), None)
        if shared and comp is not None and existing is None:
            return ("shared-new", f"not in shared component {ctype}; adding it affects every role. Add it for "
                                  f"every role, for role {self.role!r} only, move it to this role's component, "
                                  "or remove it?", {"component": ctype}, tgt)
        if shared and existing is not None and self.foreign(existing):
            # the field serves another role only, so it is not this run's target
            return ("shared-new", f"shared component {ctype} has this field for role {existing['role']!r} only, so it "
                                  f"does not serve {self.role!r}. Make it serve every role (its field role is "
                                  "dropped), move it to this role's component, or remove it?",
                    {"component": ctype, "field_role": existing["role"]}, tgt)
        return tgt

    def foreign(self, field):
        """Does this field carry a field role other than this run's?"""
        return field.get("role") not in (None, "none", self.role)

    def answered(self, leaf, decision):
        """The target a `place` decision names."""
        key, ctype = leaf["key"], decision["component"]
        if decision.get("new") is not None and ctype not in self.extra_specs \
                and not any(c["type"] == ctype for c in self.profile["components"]):
            self.extra_specs[ctype] = dict(decision["new"], type=ctype)
        spec = self.spec_for(ctype)
        fkey = self.field_key(key, spec)
        if fkey is None:
            die(2, f"place {key}: component {ctype} is relative to configPath {spec.get('configPath')!r}, "
                   "which does not hold this key")
        pattern = common.best_match(spec.get("match") or [], key) or ".".join(leaf["path"][:-1])
        return self.make_target(ctype, spec, pattern, fkey)

    def value_kind(self, leaf):
        """The question an unrepresentable value raises, whatever component it lands in."""
        override = common.lookup(self.profile.get("types") or {}, leaf["key"])
        _, _, problem = common.infer_type(leaf, self.profile.get("secret_patterns") or [])
        if not problem or override in ("list", "map"):
            return None
        if leaf["kind"] == "list":
            return "list"
        if leaf["kind"] == "map":
            return "empty-map"
        if isinstance(leaf["value"], float):
            return "decimal"
        return "unsupported"

    VALUE_TEXT = {
        "list": "a YAML list with no profile `lists` rule. Store it as a list field, one text field joined with ',', "
                "one field per item, or remove it?",
        "empty-map": "an empty mapping. Store it as a map field, or remove it?",
        "decimal": "a decimal value; the schema has no decimal type. Store it as a string field keeping the text, "
                   "or remove it?",
        "unsupported": "a value with no field type. Store it as a string field, or remove it?",
    }

    def classify(self):
        p = self.profile
        lists, types = p.get("lists") or {}, p.get("types") or {}
        parsed = common.load_yaml_leaves(
            self.args.yaml,
            list_policy=lambda k: common.lookup(lists, k) or "flag",
            map_leaf=lambda k: common.lookup(types, k) == "map")
        leaves = parsed["leaves"]
        leaf_keys = {leaf["key"] for leaf in leaves}
        for d in parsed["duplicates"]:
            if d["key"] not in leaf_keys:
                die(2, f"{self.args.yaml}: mapping {d['key']!r} is written twice in one mapping (lines "
                       f"{d['first_line']} and {d['line']}); the keys under the second copy would be lost; merge "
                       "the two blocks into one and run again.")
        self.yaml_keys = [leaf["key"] for leaf in leaves]
        self.key_count = len(set(self.yaml_keys))
        if parsed["documents"] > 1:
            self.notes.append(f"YAML has {parsed['documents']} documents; keys from document 2+ are questions")
        removed, placed, stored = self.decided["removed"], self.decided["placed"], self.decided["stored"]
        duplicates = {}
        for d in parsed["duplicates"]:
            duplicates.setdefault(d["key"], []).append(d)

        candidates, seen = [], set()
        for leaf in leaves:
            key = leaf["key"]
            if key in seen:   # a later document repeats the key: the first occurrence governs, counted once
                continue
            seen.add(key)
            if key in removed:
                self.remove(leaf, removed[key])
                continue
            hows = stored.get(key) or []   # the `store` answers: one `as` per question

            def answer(choices):
                """The `as` among this question's own choices, or None."""
                return next((h for h in hows if h != "remove" and h in choices), None)

            dups = duplicates.get(key, [])
            value_leaf = leaf
            pick = answer(CHOICES["dup-key"]) if dups else None
            if pick:
                if pick == "second":   # the last copy written: the lines the question shows
                    value_leaf = self.occurrence(leaf, dups[-1])
            else:
                for d in dups:
                    self.ask("dup-key", key, d["first_line"],
                             f"written twice in one mapping (lines {d['first_line']} and {d['line']}). Use the first "
                             "value, the second, or remove it?", CHOICES["dup-key"],
                             {"lines": [d["first_line"], d["line"]]})
            nat = self.natural(leaf)
            tgt = self.placed_target(leaf, nat, placed.get(key))
            if tgt is None and isinstance(nat, dict):
                tgt = nat
            elif tgt is None:
                kind, text, context, tentative = nat
                self.ask(kind, key, leaf["line"], text, question_choices(kind, context), context)
                if tentative:
                    self.note_placement(key, tentative)
            vkind = self.value_kind(value_leaf)
            shape = None
            if vkind:
                offered = self.value_choices(value_leaf, vkind)
                shape = answer(offered)
                if shape is None:
                    self.ask(vkind, key, leaf["line"], self.VALUE_TEXT[vkind], offered)
            if tgt is None:
                continue
            self.note_placement(key, tgt)
            base = {"leaf": leaf, "component": tgt["component"], "shared": tgt["shared"], "base": tgt["fkey"],
                    "field_role": tgt.get("field_role"), "clear_role": tgt.get("clear_role", False), "resolution": None}
            if vkind and shape is None:
                candidates.append(dict(base, field=None, fkey=tgt["fkey"]))
                continue
            for t, f, v in self.fields_for(value_leaf, tgt, shape):
                resolution = None
                problem = self.check_against_package(v, t, f)
                if problem:
                    kind, text, context = problem
                    settle = answer(SETTLES[kind])
                    if settle:
                        resolution = (kind, settle)
                        f = self.settle_derived(f, settle, kind)
                    else:
                        self.ask(kind, key, leaf["line"], text, CHOICES[kind], context)
                candidates.append(dict(base, field=f, fkey=t["fkey"], resolution=resolution))
        self.remove_package_fields(set(self.yaml_keys))

        self.ask_spelling(candidates)
        held = self.open_keys()
        # fields an open key would feed: not "in the package but not in this YAML"
        self.held_fields = {self.placements[k][:2] for k in held if k in self.placements}
        self.mapped = [c for c in candidates if c["leaf"]["key"] not in held]
        by_key = {}
        for m in self.mapped:   # one entry per YAML key; `index` gives it one field per item
            entry = by_key.setdefault(m["leaf"]["key"], {
                "key": m["leaf"]["key"], "line": m["leaf"]["line"], "component": m["component"], "field": m["base"],
                "shared": m["shared"], "field_keys": []})
            entry["field_keys"].append(m["fkey"])
        self.placed = list(by_key.values())
        placed_keys = set(by_key)
        self.coerced = [c for c in self.coerced if c["key"] in placed_keys]
        self.withheld = [w for w in self.withheld if w["key"] in placed_keys]
        present = set(self.yaml_keys)
        for key in self.decided["stale_candidates"]:
            if key not in present:
                self.info.append(f"remembered decision for {key} (role {self.role}): not in this YAML")

    def note_placement(self, key, tgt):
        # a field given `role` (scope this-role) belongs to that role even inside a shared component
        role = tgt.get("field_role") or (None if tgt["shared"] else tgt["spec"].get("role"))
        self.placements[key] = (tgt["component"], tgt["fkey"], role)

    def placed_target(self, leaf, nat, decision):
        """The target a `place` decision gives the key, or None when it does not settle the key's question.

        A shared-component question (shared-new, role-exclusive-shared) is settled only by a scope that question
        offers, or by a scopeless place into a component that is not shared (the `role-component` choice)."""
        if not decision:
            return None
        shared_q = not isinstance(nat, dict) and nat[0] in decisions.SHARED_KINDS
        scope = decision.get("scope")
        if scope is not None:
            if not shared_q or scope not in question_choices(nat[0], nat[2]):
                return None
            tgt = self.answered(leaf, decision)
            if not tgt["shared"]:
                return None
            if scope == "this-role":
                return dict(tgt, field_role=self.role)
            # all-roles over a field of another role: the field drops its role and serves every role
            return dict(tgt, clear_role=bool(nat[2].get("field_role")))
        tgt = self.answered(leaf, decision)
        return None if shared_q and tgt["shared"] else tgt

    def occurrence(self, leaf, dup):
        """The leaf as written by a later copy of a duplicated key (the `second` answer)."""
        value = dup["value"]
        out = dict(leaf, value=value, raw=dup.get("raw"), quoted=dup.get("quoted", False), first_value=leaf["value"])
        if isinstance(value, list):
            policy = common.lookup(self.profile.get("lists") or {}, leaf["key"]) or "flag"
            if policy == "join-comma" and all(not isinstance(v, (dict, list)) for v in value):
                out.update(kind="scalar-joined", value=",".join(str(v) for v in value), joined_from=value)
            else:
                out.update(kind="list", list_policy=policy)
        else:
            out["kind"] = "map" if isinstance(value, dict) else "scalar"
        return out

    def item_type(self, leaf, item):
        """The field type one list item would get under `index`, or None."""
        sub = {"path": leaf["path"] + ("0",), "kind": "scalar", "value": item, "raw": None}
        return common.infer_type(sub, self.profile.get("secret_patterns") or [])[0]

    def value_choices(self, leaf, vkind):
        """The choices a value question offers. `join-comma` needs scalar items, `index` items with a field type."""
        choices = list(CHOICES[vkind])
        if vkind == "list":
            items = leaf["value"] or []
            if any(isinstance(v, (dict, list)) for v in items):
                choices.remove("join-comma")
            if not items or any(self.item_type(leaf, v) is None for v in items):
                choices.remove("index")
        return choices

    def fields_for(self, leaf, tgt, shape):
        """[(target, field, YAML value)] for a key: one field, or one per item for `index`."""
        if shape != "index":
            return [(tgt, self.derive(leaf, tgt, shape), leaf["value"])]
        out = []
        for i, item in enumerate(leaf["value"]):
            sub = dict(leaf, path=leaf["path"] + (str(i),), key=f"{leaf['key']}.{i}", kind="scalar", value=item,
                       raw=item if isinstance(item, str) else None, quoted=True)
            t = dict(tgt, fkey=f"{tgt['fkey']}.{i}")
            out.append((t, self.derive(sub, t, parent=leaf, suffix=f" {i}"), item))
        return out

    def settle_derived(self, field, how, kind):
        """The derived field after an answered enum-value question (type-conflict answers act in the merge)."""
        if kind != "enum-value":
            return field
        field = dict(field)
        if how == "add-option":
            opts = list(field.get("options") or [])
            if field["default"] is not None and field["default"] not in opts:
                opts.append(field["default"])
            if field.get("options") is not None:
                field["options"] = opts
        elif how == "string":
            field["type"] = "string"
            field.pop("options", None)
            if field["default"] is not None and not isinstance(field["default"], str):
                field["default"] = str(field["default"])
        return field

    def own(self, spec):
        """Is this a component of this run's role, or a shared one?"""
        return is_shared(spec) or spec.get("role") == self.role

    def place_decision(self, key):
        """The `place` decision for a key, read from the answers and the remembered block directly: an answered
        `remove` has already replaced it in effective()."""
        found = None
        for e in self.remembered.get("placed") or []:
            if isinstance(e, dict) and e.get("key") == key and e.get("role") in (None, self.role):
                found = e
        for e in self.answers.get("place") or []:
            if isinstance(e, dict) and e.get("key") == key:
                found = e
        return found

    def removal_target(self, leaf):
        """The component of this role (or a shared one) that holds or would hold the key's field, or None."""
        key = leaf["key"]
        decision = self.place_decision(key)
        if decision and isinstance(decision.get("component"), str):
            ctype = decision["component"]
            known = any(c["type"] == ctype for c in self.profile["components"]) or self.find_comp(ctype)
            if decision.get("new") is not None and isinstance(decision["new"], dict) and not known:
                spec = dict(decision["new"], type=ctype)
            else:
                spec = self.spec_for(ctype) if known else None
            fkey = self.field_key(key, spec) if spec else None
            if spec and fkey is not None and self.own(spec):
                return self.make_target(ctype, spec, "", fkey)
        nat = self.natural(leaf, honour_exclude=False)
        if isinstance(nat, dict):
            return nat
        if nat[0] in decisions.SHARED_KINDS and not nat[2].get("field_role"):   # never another role's field
            return nat[3]
        for comp in self.pkg["components"]:
            spec = self.spec_for(comp["type"])
            fkey = self.field_key(key, spec)
            if fkey is not None and self.own(spec) and \
                    any(f["key"] == fkey and not self.foreign(f) for f in comp.get("fields", [])):
                return self.make_target(comp["type"], spec, "", fkey)
        return None

    def remove(self, leaf, reason):
        tgt = self.removal_target(leaf)
        if tgt is not None:
            self.note_placement(leaf["key"], tgt)
        place = self.placements.get(leaf["key"])
        self.removed.append({"key": leaf["key"], "line": leaf["line"], "reason": reason,
                             "role": place[2] if place else self.role,
                             "component": place[0] if place else None,
                             "field": place[1] if place else None,
                             "field_keys": [place[1]] if place else []})

    def remove_package_fields(self, yaml_keys):
        """A `remove` naming component + field: a package field the YAML does not carry. Not a YAML key, so it is
        recorded with line None and stays out of key_count and balanced()."""
        named = set()
        for e in self.answers.get("remove") or []:
            if isinstance(e, dict) and e.get("key") is None:
                named.add((e.get("component"), e.get("field")))
        for e in self.remembered.get("removed") or []:
            if isinstance(e, dict) and e.get("key") is None:
                named.add((e.get("component"), e.get("field")))
        for name, reason in self.decided["removed"].items():
            if name in yaml_keys:
                continue
            match = next(((c, f) for c, f in named if f"{c}/{f}" == name), None)
            if match is None:
                continue
            ctype, fkey = match
            spec = self.spec_for(ctype) if (self.find_comp(ctype) or any(
                c["type"] == ctype for c in self.profile["components"])) else {}
            self.removed.append({"key": fkey, "line": None, "reason": reason,
                                 "role": None if not spec or is_shared(spec) else spec.get("role"),
                                 "component": ctype, "field": fkey, "field_keys": [fkey]})

    def ask_spelling(self, candidates):
        groups = {}
        for c in candidates:
            groups.setdefault((c["component"], common.normalise_key(c["fkey"])), []).append(c)
        for (ctype, _), members in groups.items():
            keys = list(dict.fromkeys(m["leaf"]["key"] for m in members))
            if len(keys) < 2:
                continue
            first = members[0]["leaf"]
            self.ask("spelling", first["key"], first["line"],
                     f"{len(keys)} keys of {ctype} differ only in case, '-' or '_' ({', '.join(keys)}). Keep one "
                     "spelling: remove the others?", CHOICES["spelling"], {"keys": keys, "component": ctype})

    def check_against_package(self, value, tgt, field):
        """enum-value and type-conflict: questions only the target component can raise. Returns
        (kind, text, context) or None; the caller asks it unless a `store` answer settles it."""
        options = field.get("options")
        if options is not None and value is not None and value not in options:
            return ("enum-value", f"value is not one of the profile's enum options {options}. Add it to the options, "
                                  "make the field a string, or remove it?", {"options": list(options)})
        comp = self.find_comp(tgt["component"])
        f = next((g for g in (comp or {}).get("fields", []) if g["key"] == tgt["fkey"]), None)
        if f is None:
            return None
        if not compatible(f["type"], field["type"], tgt["shared"]):
            return ("type-conflict", f"the package declares {tgt['component']}/{f['key']} as {f['type']!r}; the YAML "
                                     f"value is {field['type']!r}. Keep the package's type, use the YAML's, or remove "
                                     "it?", {"component": tgt["component"], "package_type": f["type"],
                                             "yaml_type": field["type"]})
        takes_yaml = not tgt["shared"] and \
            (self.profile.get("defaults") or {}).get("role_components", "yaml") == "yaml"
        if takes_yaml and f.get("options") and not f.get("valueFrom") and f["type"] != "secret" \
                and field["default"] is not None and field["default"] not in f["options"]:
            return ("enum-value", f"value is not one of the package's options {f['options']}. Add it to the options, "
                                  "make the field a string, or remove it?", {"options": list(f["options"])})
        return None

    def derive(self, leaf, tgt, shape=None, parent=None, suffix=""):
        """The field for a leaf. `shape` is a value question's `store` answer (list, join-comma, map, string);
        `parent` is the list an `index` item comes from: its key, section and label (plus `suffix`) are used."""
        p, spec, pattern = self.profile, tgt["spec"], tgt["pattern"]
        owner = parent or leaf   # the YAML key: notes are filed under it, the ui is derived from it
        key = owner["key"]
        patterns = p.get("secret_patterns") or []
        secret_named = common.is_secret_name(owner["path"][-1], patterns)
        values = list(owner["value"]) if isinstance(owner["value"], list) else [owner["value"]]
        values.append(owner.get("first_value"))   # a duplicated key: the comment sits on the first copy
        if shape == "list":
            leaf = dict(leaf, list_policy="list")
        elif shape == "join-comma":
            leaf = dict(leaf, kind="scalar-joined", joined_from=leaf["value"],
                        value=",".join(str(v) for v in leaf["value"]))
        override = common.lookup(p.get("types") or {}, leaf["key"])
        options = common.lookup(p.get("enums") or {}, leaf["key"])
        ftype, note, _ = common.infer_type(leaf, patterns)
        if override:
            ftype = override
        if shape in ("map", "string"):
            ftype = shape
        if secret_named and (parent is not None or shape in ("join-comma", "string")):
            ftype = "secret"   # a value reshaped from a secret-named key stays a secret (I6)
        default = common.coerce_default(ftype, leaf["value"]) if leaf["kind"] != "list" else leaf["value"]
        if leaf["kind"] == "scalar-joined":
            default = leaf["value"]
        if shape == "string":   # keep the text as written (`1.10` stays "1.10")
            default = leaf["raw"] if isinstance(leaf.get("raw"), str) else str(leaf["value"])
        elif shape == "map":
            default = None
        if options is not None:
            ftype = "enum"
        if ftype == "secret":
            note = None   # coercion notes quote the value
        if note:
            self.coerced.append({"key": key, "line": leaf["line"], "note": note})
        if ftype != "secret" and leaf.get("raw") and leaf["raw"].lower() in common.YAML11_BOOL_WORDS \
                and not leaf["quoted"]:
            self.coerced.append({"key": key, "line": leaf["line"],
                                 "note": f"unquoted {leaf['raw']!r} kept as a string (a YAML 1.1 reader would make it a bool)"})
        if ftype in ("list", "map") and default is not None:
            # the schema allows only scalar defaults; the cache reference package leaves these null too
            shown = "(not shown: secret-named key)" if secret_named else json.dumps(default)
            self.coerced.append({"key": key, "line": leaf["line"],
                                 "note": f"{ftype} default {shown} not representable as a field default; left null"})
            default = None
        if ftype == "secret":
            if default not in (None, "") and not any(w["key"] == key for w in self.withheld):
                self.withheld.append({"key": key, "line": leaf["line"]})
            default = None

        n = common.root_len(pattern, owner["path"])
        mid, root = owner["path"][n:-1], owner["path"][:n]
        fallback = spec.get("section_default") or common.title_case(common.split_words(spec["type"]), self.acronyms)
        section = common.lookup(p.get("sections") or {}, key) or common.section_for(mid, root, fallback, self.acronyms)
        label = common.label_for(owner["path"][-1], section, self.acronyms) + suffix
        comment = owner["comment"]
        if (ftype == "secret" or secret_named) and comment and \
                any(v is not None and str(v) and str(v) in comment for v in values):
            comment = None   # a comment that repeats the secret value never becomes help text (I6)
        field = {"key": tgt["fkey"], "type": ftype, "default": default,
                 "ui": {"section": section, "order": None, "label": label,
                        "helperText": common.helper_text(ftype, label, section, comment)}}
        if options is not None:
            field["options"] = list(options)
        return field

    # ---------------------------------------------------------------- merge
    def find_comp(self, ctype):
        return next((c for c in self.pkg["components"] if c["type"] == ctype), None)

    def new_component(self, spec):
        comp = {"type": spec["type"], "cardinality": spec["cardinality"], "role": spec.get("role", "none"),
                "configPath": spec.get("configPath", "")}
        if spec.get("loggerPrefix"):
            comp["loggerPrefix"] = spec["loggerPrefix"]
        comp["version"] = list(self.target)
        comp["fields"] = []
        return comp

    def relocate(self):
        """Profile `relocate`: take fields out of a shared component because the profile now gives them to the
        role components. Runs on every role (idempotent); the removed declarations are kept so the role copies
        can reuse their reviewed ui."""
        self.relocated = {}
        for rule in self.profile.get("relocate") or []:
            comp = self.find_comp(rule["from"])
            if comp is None:
                continue
            keep = []
            for f in comp["fields"]:
                if common.matches(rule["match"], f["key"]):
                    self.relocated[f["key"]] = f
                    rows = self.diff_rows.setdefault(comp["type"], {"added": [], "changed": [], "package_only": []})
                    rows.setdefault("removed", []).append(f["key"])
                else:
                    keep.append(f)
            if len(keep) != len(comp["fields"]):
                self.notes.append(f"{len(comp['fields']) - len(keep)} field(s) matching {rule['match']!r} relocated "
                                  f"out of shared component {comp['type']} ({rule.get('reason', 'profile relocate')})")
                comp["fields"] = keep
                self.touched.add(comp["type"])
                if not keep:
                    self.questions.append(f"shared component {comp['type']} has no fields left after relocation. It "
                                          "was kept so instances that already select it stay valid; remove it?")

    def reviewed_ui(self, key, ctype):
        """The reviewed ui of the same key elsewhere: a relocated field first, else a sibling component's copy."""
        if key in self.relocated:
            return self.relocated[key].get("ui")
        for c in self.pkg["components"]:
            if c["type"] != ctype:
                g = next((g for g in c.get("fields", []) if g["key"] == key), None)
                if g is not None and g.get("ui"):
                    return g["ui"]
        return None

    def merge(self):
        p = self.profile
        explicit = p.get("explicit_versions", True)
        preserve_ui = p.get("preserve_reviewed_ui", True)
        self.withhold_secrets()
        self.drop_removed()
        by_comp = {}
        for m in self.mapped:
            by_comp.setdefault(m["component"], []).append(m)
        for ctype, items in by_comp.items():
            spec = self.spec_for(ctype)
            shared = items[0]["shared"]
            comp = self.find_comp(ctype)
            created = comp is None
            if created:
                comp = self.new_component(spec)
                self.insert_component(comp)
            cver = comp.get("version", self.pkg["packageVersion"])
            if not set(self.target) <= set(cver):
                die(4, f"component {ctype} carries versions {cver} but the YAML targets {self.target}; widening one "
                       "component's versions is a package-level decision; ask the user")
            if shared and not created:
                self.check_shared(comp, items)
                continue
            self.touched.add(ctype)
            existing = {f["key"]: f for f in comp["fields"]}
            incoming = {m["field"]["key"]: m for m in items}
            out, rows = [], self.diff_rows.setdefault(ctype, {"added": [], "changed": [], "package_only": []})
            for f in comp["fields"]:
                m = incoming.get(f["key"])
                if m is None:
                    out.append(f)
                    if not (f.get("virtual") or f.get("valueFrom") or (ctype, f["key"]) in self.held_fields):
                        rows["package_only"].append(f["key"])
                    continue
                merged, changes = self.merge_field(f, m, preserve_ui, ctype)
                out.append(merged)
                if changes:
                    rows["changed"].append({"key": f["key"], "changes": changes})
            for k, m in incoming.items():
                if k in existing:
                    continue
                f = m["field"]
                if m.get("field_role"):
                    f["role"] = m["field_role"]
                if explicit:
                    f["version"] = list(self.target)
                prior = self.reviewed_ui(k, ctype) if preserve_ui else None
                if prior:
                    f["ui"] = dict(f["ui"], **prior)
                    f["ui"]["order"] = None   # renumbered below, in this component's sequence
                # insert after the last field of the same section, else at the end
                idx = max((i for i, g in enumerate(out) if (g.get("ui") or {}).get("section") == f["ui"]["section"]),
                          default=len(out) - 1)
                out.insert(idx + 1, ordered_field(f))
                rows["added"].append(k)
            comp["fields"] = out
            self.assign_order(comp, {m["field"]["key"] for m in items if m["field"]["key"] not in existing}
                              if not created else None, preserve_ui)
            if rows["package_only"]:
                self.questions.append(f"{ctype}: {len(rows['package_only'])} field(s) exist in the package but not in "
                                      f"this YAML ({', '.join(rows['package_only'][:5])}...). They were kept. Remove "
                                      f"them, or drop target versions {self.target} from them?")
        for ctype in p.get("preserve", {}).get("components", []):
            if self.find_comp(ctype) is not None:
                self.notes.append(f"component {ctype} preserved untouched (profile `preserve`)")

    def withhold_secrets(self):
        """I6: no secret default survives in the package, in any component, whether or not this YAML feeds it.
        The change is recorded without the value."""
        for comp in self.pkg["components"]:
            for f in comp.get("fields", []):
                if f.get("type") == "secret" and f.get("default") not in (None, ""):
                    f["default"] = None
                    self.notes.append(f"secret default withheld: {comp['type']}/{f['key']}")
                    rows = self.diff_rows.setdefault(comp["type"], {"added": [], "changed": [], "package_only": []})
                    rows["changed"].append({"key": f["key"],
                                            "changes": [("default (secret withheld)", "(not shown)", None)]})
                    self.touched.add(comp["type"])

    def drop_removed(self):
        """Removing a key also removes its field when the package already has one, and the `<field>.<n>` fields an
        earlier `index` answer made of it. Every dropped field key is listed in the entry's field_keys."""
        for r in self.removed:
            comp = self.find_comp(r["component"]) if r["component"] else None
            if comp is None or r["field"] is None:
                continue
            item = re.compile(re.escape(r["field"]) + r"\.\d+")
            gone = [f["key"] for f in comp.get("fields", []) if f["key"] == r["field"] or item.fullmatch(f["key"])]
            if not gone:
                continue
            comp["fields"] = [f for f in comp["fields"] if f["key"] not in gone]
            r["field_keys"] += [k for k in gone if k not in r["field_keys"]]
            rows = self.diff_rows.setdefault(comp["type"], {"added": [], "changed": [], "package_only": []})
            rows.setdefault("removed", []).extend(gone)
            self.touched.add(comp["type"])

    def insert_component(self, comp):
        order = [c["type"] for c in self.profile["components"]]
        pos = len(self.pkg["components"])
        if comp["type"] not in order:   # a component the user created: after the profile's own
            self.pkg["components"].append(comp)
            return
        for i, c in enumerate(self.pkg["components"]):
            if c["type"] in order and order.index(c["type"]) > order.index(comp["type"]):
                pos = i
                break
        self.pkg["components"].insert(pos, comp)

    def merge_field(self, f, m, preserve_ui, ctype):
        new, changes = copy.deepcopy(f), []
        d = m["field"]
        res = m.get("resolution")   # an answered enum-value or type-conflict question
        if d["type"] != f["type"] and (res is None or res[1] == "package-type"):
            self.notes.append(f"{ctype}/{f['key']}: type {f['type']!r} kept from the package (YAML infers {d['type']!r})")
        policy = (self.profile.get("defaults") or {}).get("role_components", "yaml")
        if f.get("valueFrom"):
            if f.get("default") != d["default"] and f["type"] != "secret":
                changes.append(("default (valueFrom field, kept)", f.get("default"), d["default"]))
        elif f["type"] == "secret":
            pass   # a secret takes no default: withhold_secrets() already nulled the package's
        elif res is not None:
            changes += self.apply_resolution(new, d, res)   # package-type: the YAML default is not used
            new = ordered_field(new)
        elif policy == "yaml" and f.get("default") != d["default"]:
            if not (f.get("options") and d["default"] is not None and d["default"] not in f["options"]):
                # a value outside the options was asked as an enum-value question
                new["default"] = d["default"]
                changes.append(("default", f.get("default"), d["default"]))
        if res is None and d.get("options") and f.get("options") and d["options"] != f["options"]:
            self.notes.append(f"{ctype}/{f['key']}: profile options {d['options']} differ from package {f['options']}; package kept")
        if preserve_ui and f.get("ui"):
            if f["ui"].get("label") != d["ui"]["label"] or f["ui"].get("section") != d["ui"]["section"]:
                self.ui_diffs.append({"component": ctype, "key": f["key"],
                                      "package": [f["ui"].get("section"), f["ui"].get("label")],
                                      "derived": [d["ui"]["section"], d["ui"]["label"]]})
        else:
            order = (f.get("ui") or {}).get("order")
            new["ui"] = dict(d["ui"], order=order)
            changes.append(("ui", f.get("ui"), new["ui"]))
        if "version" in f:
            v = sorted(set(f["version"]) | set(self.target))
            if v != f["version"]:
                new["version"] = v
                changes.append(("version", f["version"], v))
        return new, changes

    @staticmethod
    def apply_resolution(f, d, res):
        """Apply an answered enum-value or type-conflict question to package field f, in place. Returns the changes
        as (what, old, new) rows."""
        _, how = res
        before = {k: copy.deepcopy(f.get(k)) for k in ("type", "default", "options")}
        if how == "yaml-type":
            f["type"], f["default"] = d["type"], d["default"]
            if d.get("options") is not None:
                f["options"] = list(d["options"])
            else:
                f.pop("options", None)
        elif how == "add-option":
            opts = list(f.get("options") or d.get("options") or [])
            if d["default"] is not None and d["default"] not in opts:
                opts.append(d["default"])
            f["options"], f["default"] = opts, d["default"]
        elif how == "string":
            f["type"], f["default"] = "string", d["default"]
            f.pop("options", None)
        return [(k, before[k], f.get(k)) for k in before if before[k] != f.get(k)]

    def assign_order(self, comp, new_keys, preserve_ui):
        """new_keys None means the component was created now: number everything."""
        mode = self.profile.get("ui_order", "section-index")
        fields = comp["fields"]
        if new_keys is None:
            sec_idx = {}
            for i, f in enumerate(fields):
                s = f["ui"]["section"]
                sec_idx.setdefault(s, len(sec_idx))
                f["ui"]["order"] = i if mode == "running" else sec_idx[s]
            return
        orders = [f["ui"]["order"] for f in fields if f.get("ui", {}).get("order") is not None]
        nxt = (max(orders) + 1) if orders else 0
        sec_first = {}
        for f in fields:
            if f["key"] not in new_keys and f.get("ui", {}).get("order") is not None:
                sec_first.setdefault(f["ui"]["section"], f["ui"]["order"])
        for f in fields:
            if f["key"] not in new_keys:
                continue
            if mode == "running":
                f["ui"]["order"], nxt = nxt, nxt + 1
            else:
                s = f["ui"]["section"]
                if s not in sec_first:
                    sec_first[s], nxt = nxt, nxt + 1
                f["ui"]["order"] = sec_first[s]

    def check_shared(self, comp, items):
        existing = {f["key"]: f for f in comp["fields"]}
        fed = {m["field"]["key"] for m in items}
        missing = [k for k, f in existing.items() if k not in fed and (comp["type"], k) not in self.held_fields
                   and not (f.get("virtual") or f.get("valueFrom") or self.foreign(f))]
        if missing:
            # every instance of this role renders these from the shared component, yet its YAML does not carry them
            self.questions.append(f"shared component {comp['type']} renders {len(missing)} field(s) this {self.role} "
                                  f"YAML does not carry ({', '.join(missing[:5])}). Every {self.role} instance gets "
                                  "them. Keep them shared, give them a field role, or move them to a role component?")
        for m in items:
            f, d = existing.get(m["field"]["key"]), m["field"]
            if f is None:   # only a key the user agreed to add reaches here (shared-new was asked)
                extra = {"role": m["field_role"]} if m.get("field_role") else {}   # scope this-role
                d = ordered_field(dict(d, version=list(self.target), **extra))
                comp["fields"].append(d)
                self.assign_order(comp, {d["key"]}, True)
                self.touched.add(comp["type"])
                self.diff_rows.setdefault(comp["type"], {"added": [], "changed": [], "package_only": []})["added"].append(d["key"])
                continue
            changes = []
            if m.get("clear_role") and f.get("role") not in (None, "none"):
                changes.append(("role", f.pop("role"), None))   # all-roles: the field now serves every role
            if m.get("resolution"):
                # the user's answer changes the shared declaration (package-type: nothing changes)
                changes += self.apply_resolution(f, d, m["resolution"])
            if changes:
                comp["fields"][comp["fields"].index(f)] = ordered_field(f)
                rows = self.diff_rows.setdefault(comp["type"], {"added": [], "changed": [], "package_only": []})
                rows["changed"].append({"key": f["key"], "changes": changes})
                self.touched.add(comp["type"])
            # the YAML default is not applied unless an answer applied it: report a difference
            if f["type"] != "secret" and d["type"] != "secret" and f.get("default") != d["default"]:
                self.shared_diffs.append({"component": comp["type"], "key": f["key"],
                                          "package": f.get("default"), "yaml": d["default"],
                                          "valueFrom": bool(f.get("valueFrom"))})
                self.info.append(f"shared default differs (package kept): {comp['type']} {f['key']}: package "
                                 f"{json.dumps(f.get('default'))}, YAML {json.dumps(d['default'])}")

    # ---------------------------------------------------------------- finish
    def finish(self):
        # reviewed = every field this run did not add or change, capped at the previous count
        total = sum(len(c.get("fields", [])) for c in self.pkg["components"])
        churn = sum(len(r["added"]) + len(r["changed"]) for r in self.diff_rows.values())
        old_reviewed = (self.old or {}).get("authoring", {}).get("reviewedRows", 0)
        reviewed = max(0, min(old_reviewed, total - churn)) if self.old else 0
        auth = self.pkg.setdefault("authoring", {})
        auth["reviewedRows"], auth["totalRows"] = reviewed, total
        prov = self.pkg.get("provenance") or {}
        if getattr(self.args, "provenance", "keep") == "dev-mode":
            self.pkg["provenance"] = {"author": "dev-mode"}
            self.notes.append("provenance set to dev-mode (unsigned) at the user's request")
        elif prov.get("signature") and churn:
            self.questions.append("provenance carries a signature over content this run changed, so it no longer "
                                  "covers the package. Keep it as-is, or re-run with --provenance dev-mode?")
        if (self.profile.get("defaults") or {}).get("role_components", "yaml") == "yaml" and \
                any(r["changed"] for r in self.diff_rows.values()):
            self.notes.append("defaults were taken from the YAML (profile defaults.role_components: yaml). This "
                              "conflicts with docs/design/dynamic-ui/design.md section 7.3 (UI-D*, PROPOSED): defaults "
                              "come from field initializers, never from a sample application.yml. Flagged, not resolved.")
        starts = [x for x in (self.args.package, self.args.out_dir, os.getcwd(), os.path.dirname(os.path.abspath(__file__)))
                  if x]
        schema = self.args.schema or validate_package.find_schema(*starts)
        self.validation = validate_package.validate(self.pkg, schema)
        self.balance_ok = self.balanced()

    def write(self):
        os.makedirs(self.args.out_dir, exist_ok=True)
        text = json.dumps(self.pkg, indent=2, ensure_ascii=self.ascii_style()) + "\n"
        write_text(os.path.join(self.args.out_dir, "package.json"), text)
        if self.old is not None:
            old_text = read_text(self.args.package)
            diff = "".join(difflib.unified_diff(old_text.splitlines(True), text.splitlines(True),
                                                fromfile=self.args.package, tofile="package.json (generated)", n=2))
            write_text(os.path.join(self.args.out_dir, "package.diff"), diff)
        report = self.report_json()
        write_text(os.path.join(self.args.out_dir, "report.json"),
                   json.dumps(report, indent=2, ensure_ascii=False, default=str))
        write_text(os.path.join(self.args.out_dir, "report.md"), self.report_md(report))

    def ascii_style(self):
        if self.args.package:
            return "\\u201c" in read_text(self.args.package)
        return False

    def report_json(self):
        return {
            "solution": self.profile["solution"], "role": self.role, "profile": self.args.profile,
            "yaml": self.args.yaml, "package": self.args.package, "target_versions": self.target,
            "counts": {"keys": self.key_count, "placed": len(self.placed), "removed": len(self.removed),
                       "open": len(self.open_keys()), "coerced": len(self.coerced),
                       "secrets_withheld": len(self.withheld), "balanced": self.balance_ok},
            "placed": [{"key": m["leaf"]["key"], "line": m["leaf"]["line"], "component": m["component"],
                        "field": m["field"]["key"], "type": m["field"]["type"], "shared": m["shared"]}
                       for m in self.mapped],
            "removed": self.removed, "open": list(self.open.values()), "info": self.info,
            "coerced": self.coerced,
            "secrets_withheld": self.withheld, "diff": self.diff_rows, "shared_differences": self.shared_diffs,
            "ui_differences": self.ui_diffs, "notes": self.notes, "questions": self.questions,
            "validation": self.validation, "authoring": self.pkg.get("authoring"),
        }

    def report_md(self, r):
        c = r["counts"]
        L = [f"# yamlToPackage report: {r['solution']} / {r['role']}", "",
             f"- Profile: `{r['profile']}`", f"- YAML: `{r['yaml']}`",
             f"- Package: `{r['package'] or '(new package)'}`", f"- Target versions: {r['target_versions']}", "",
             "## Coverage", "",
             f"{c['keys']} YAML keys: **{c['placed']} placed**, **{c['open']} open**, "
             f"**{c['removed']} removed by you**. Every key is in exactly one outcome: "
             f"{'yes' if c['balanced'] else 'NO, investigate'}. "
             f"{c['coerced']} coerced, {c['secrets_withheld']} secret default(s) withheld.", ""]
        L += ["### Open (needs an answer)", ""]
        L += [f"- `{q['id']}` (line {q['line']}): {q['text']} Choices: {', '.join(q['choices'])}"
              for q in r["open"]] or ["- none"]
        L += ["", "### Removed by you", ""]
        L += [f"- `{e['key']}` (line {e['line']}): {e['reason']}" for e in r["removed"]] or ["- none"]
        L += ["", "### Coerced / assumed", ""]
        L += [f"- `{x['key']}` (line {x['line']}): {x['note']}" for x in r["coerced"]] or ["- none"]
        L += ["", "### Secret defaults withheld (I6)", ""]
        L += [f"- `{x['key']}` (line {x['line']})" for x in r["secrets_withheld"]] or ["- none"]
        by = {}
        for m in r["placed"]:
            by.setdefault(m["component"], []).append(m)
        L += ["", "### Mapped, by component", "", "| component | fields | shared |", "|---|---|---|"]
        L += [f"| {k} | {len(v)} | {'yes' if v[0]['shared'] else 'no'} |" for k, v in by.items()]
        L += ["", "## Diff against the existing package", ""]
        if not r["package"]:
            L.append("New package; no diff.")
        for ct, rows in r["diff"].items():
            L += [f"### {ct}", "", f"- added: {len(rows['added'])}" + (f": {', '.join(rows['added'])}" if rows["added"] else "")]
            if rows.get("removed"):
                L.append(f"- relocated out (profile `relocate`): {len(rows['removed'])}: {', '.join(rows['removed'])}")
            L.append(f"- changed: {len(rows['changed'])}")
            for ch in rows["changed"]:
                for what, old, new in ch["changes"]:
                    L.append(f"  - `{ch['key']}` {what}: `{json.dumps(old, ensure_ascii=False)}` -> `{json.dumps(new, ensure_ascii=False)}`")
            L.append(f"- in package, not in YAML (kept): {len(rows['package_only'])}"
                     + (f": {', '.join(rows['package_only'])}" if rows["package_only"] else ""))
            L.append("")
        if r["shared_differences"]:
            L += ["### Shared components: YAML vs package defaults (package kept)", ""]
            L += [f"- {d['component']} `{d['key']}`: package `{json.dumps(d['package'])}`, YAML `{json.dumps(d['yaml'])}`"
                  + (" (valueFrom)" if d["valueFrom"] else "") for d in r["shared_differences"]]
            L.append("")
        if r["ui_differences"]:
            L += [f"### Reviewed ui kept where the derived one differs ({len(r['ui_differences'])})", ""]
            L += [f"- {d['component']} `{d['key']}`: package {d['package']}, derived {d['derived']}"
                  for d in r["ui_differences"][:15]]
            if len(r["ui_differences"]) > 15:
                L.append(f"- ...and {len(r['ui_differences']) - 15} more in report.json")
            L.append("")
        v = r["validation"]
        L += ["## Validation", "", f"**{'PASS' if v['ok'] else 'FAIL'}**", ""]
        L += [f"- note: {n}" for n in v["notes"]] + [f"- error: {e}" for e in v["errors"]] + \
             [f"- warning: {w}" for w in v["warnings"]]
        L += ["", "## Notes", ""] + ([f"- {n}" for n in r["notes"] + r["info"]] or ["- none"])
        q = list(r["questions"])
        L += ["", "## Other questions", ""] + ([f"{i}. {x}" for i, x in enumerate(q, 1)] or ["none"])
        L += ["", f"authoring: {json.dumps(r['authoring'])}", ""]
        return "\n".join(L)


def parser():
    """The arguments every command shares."""
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--profile", required=True)
    ap.add_argument("--role", required=True)
    ap.add_argument("--yaml", required=True)
    ap.add_argument("--target-versions", required=True, help="comma-separated, e.g. 1,2,3 (ask the user)")
    ap.add_argument("--package", help="existing package.json to merge into (never overwritten)")
    ap.add_argument("--applies-to", help="semver range; required when creating a new package")
    ap.add_argument("--answers", help="answers.yaml written from the user's replies to the open questions")
    ap.add_argument("--samples", help="folder of sample packages (default: found from the working directory)")
    ap.add_argument("--schema")
    ap.add_argument("--today", default=datetime.date.today().isoformat(), help="YYYY-MM-DD stamped on decisions")
    ap.add_argument("--out-dir")
    return ap


def run_build(args):
    for flag, path in (("--profile", args.profile), ("--yaml", args.yaml), ("--package", args.package),
                       ("--answers", args.answers)):
        if path and not os.path.isfile(path):
            die(2, f"{flag} {path}: no such file")
    run = Run(args)
    run.check_inputs()
    run.relocate()
    run.classify()
    run.merge()
    run.finish()
    return run


def build(argv):
    """Parse the shared arguments and run the build: every YAML key placed, open or removed by you."""
    return run_build(parser().parse_args(argv))


def main():
    # Interim one-shot, kept until the preview/apply subcommands replace it: it writes the package even
    # while questions are open, so read report.md before using the result.
    ap = parser()
    ap.add_argument("--provenance", choices=["keep", "dev-mode"], default="keep")
    args = ap.parse_args()
    if not args.out_dir:
        die(2, "--out-dir is required")
    run = run_build(args)
    run.write()
    c = run.report_json()["counts"]
    print(f"{run.profile['solution']}/{run.role}: keys={c['keys']} placed={c['placed']} open={c['open']} "
          f"removed={c['removed']} balanced={c['balanced']} validation={'PASS' if run.validation['ok'] else 'FAIL'}")
    print(f"wrote {run.args.out_dir}/report.md")
    sys.exit(0 if run.validation["ok"] else 1)


if __name__ == "__main__":
    main()
