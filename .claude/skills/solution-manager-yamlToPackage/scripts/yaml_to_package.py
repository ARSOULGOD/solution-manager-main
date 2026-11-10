#!/usr/bin/env python3
"""Generic engine: turn one role's YAML into Solution Package fields through a CONFIRMED mapping
profile and merge them into an existing package. Two commands:

    python3 yaml_to_package.py preview --profile references/profiles/file-mesh.yaml --role source \
        --yaml application-source-file-v2.yml --target-versions 1,2,3 \
        --package frontend/contracts/packages/file-mesh/package.json --out-dir /tmp/out [--answers answers.yaml]
    python3 yaml_to_package.py apply   ...the same arguments...

Every YAML key ends up as exactly one of: placed (it becomes a field), open (a question with fixed
choices) or removed by you (a recorded decision). Answers come from --answers and from the
profile's decisions block.

`preview` writes preview.json and preview.md to --out-dir and never a package. `apply` refuses while
anything is open, then writes package.json, report.md, report.json, profile.yaml (the profile with the
answers merged into its decisions block) and, with --package, package.diff; then it reads the package back
(every placed key a field of its component, every removed key gone) and validates it. Neither command ever
writes over an input; copying the results into place is a separate, deliberate step.

Exit codes: 0 done (apply) or nothing open (preview), 1 validation or read-back failed, 2 bad input (a bad
answers file included), 3 the profile is a draft, 4 a version decision is needed, 5 questions still open.
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
import learn_samples  # noqa: E402
import preview_md  # noqa: E402
import style  # noqa: E402
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
KEY_KINDS = set(CHOICES)   # the questions about one YAML key; suggest, restyle and provenance are package-level
PACKAGE_CHOICES = {"suggest": ["accept", "reject"], "restyle": ["accept", "refuse"], "provenance": ["keep", "dev-mode"]}
DB_DESIGNER = ("within-section", "sparse")   # the style of a new package, and of every package after a restyle
NO_COUNTS = {"order_changed": 0, "stock_removed": 0, "custom_kept": 0}
MASK = "***"   # what a secret value prints as wherever a default is shown (I6)
# info lines another part of the report already shows: report.md and report.json leave them out of the notes
REPORTED_ELSEWHERE = ("coerced: ", "in the package, not in this YAML (kept): ", "reviewed labels kept: ",
                      "shared default differs (package kept): ")
NO_SIGNATURE_QUESTION = "provenance: no signature question is asked (the package is unsigned, or this build leaves it " \
                        "unchanged)"
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


def load_answers(path):
    try:
        return decisions.load_answers(path)
    except (ValueError, yaml.YAMLError, OSError) as e:
        die(2, f"answers {path}: {e}")


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
    def __init__(self, args, answers=None):
        """answers: the answers to use instead of reading --answers (a dict already loaded and validated)."""
        self.args = args
        self.profile = load_profile(args.profile)
        self.role = args.role
        self.today = args.today
        try:
            with open(args.profile, encoding="utf-8") as fh:
                self.remembered = decisions.read_block(fh.read())
        except (ValueError, yaml.YAMLError) as e:
            die(2, f"profile {args.profile}: decisions block: {e}")
        if answers is not None:
            self.answers = answers
        else:
            self.answers = load_answers(args.answers)
        self.decided = decisions.effective(self.remembered, self.answers, self.role)
        # the three outcomes
        self.placed, self.removed, self.open = [], [], {}
        # bookkeeping
        self.key_count, self.yaml_keys, self.info, self.placements = 0, [], [], {}
        self.mapped, self.coerced, self.withheld = [], [], []
        self.notes, self.shared_diffs, self.ui_diffs = [], [], []
        self.diff_rows, self.touched, self.relocated, self.extra_specs = {}, set(), {}, {}
        self.held_fields = set()
        # what this build's answers settled: (key, as) per store answer used, keys a place answer placed
        self.used_store, self.used_place, self.provenance_asked = set(), set(), False
        # package-level items
        self.samples, self.styles, self.restyling, self.rows = {}, {}, False, []
        self.marked, self.package_only, self.suggestion_evidence, self.suggestion_decisions = set(), [], {}, {}
        self.acronyms = common.ACRONYMS | {a.lower() for a in self.profile.get("acronyms", [])}

    # ---------------------------------------------------------------- the three outcomes
    def ask(self, kind, key, line, text, choices, context=None, qid=None):
        """Record an open question. A key of None makes a package-level item whose id is the kind alone."""
        if qid is None and key is None:
            qid = kind
        elif qid is None:
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
        self.load_samples()
        self.detect_styles()

    # ---------------------------------------------------------------- samples and style
    def load_samples(self):
        """The sample packages suggestions are learned from; the solution being generated is never one of them."""
        folder = self.args.samples or learn_samples.find_samples_dir(os.getcwd())
        self.index = learn_samples.build_index(folder, self.profile["solution"])
        self.samples = {"dir": folder, "packages": self.index.packages, "fields": len(self.index.fields),
                        "notes": list(self.index.notes)}

    def detect_styles(self):
        """Each input component's own style (a profile's ui_order is ignored), and whether a restyle is offered:
        some component is not numbered within its sections, or carries stock helper sentences."""
        for comp in (self.old or {}).get("components", []):
            fields = comp.get("fields") or []
            self.styles[comp["type"]] = (style.detect_order_style(fields), style.detect_helper_style(fields))
        self.restyle_needed = any(o not in ("within-section", None) or h == "stock" for o, h in self.styles.values())
        self.restyling = self.restyle_needed and self.decided["restyle"] == "accepted"

    def style_of(self, ctype):
        """(order style, helper style) new fields of this component follow."""
        if self.restyling or ctype not in self.styles:
            return DB_DESIGNER
        return self.styles[ctype]

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
                found = next((h for h in hows if h != "remove" and h in choices), None)
                if found:
                    self.used_store.add((key, found))
                return found

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
            if tgt is not None:
                self.used_place.add(key)
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
            field.pop("secret", None)
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
        helper = style.new_helper(ftype, label, section, comment, self.style_of(tgt["component"])[1])
        ui = {"section": section, "order": None, "label": label}
        if helper is not None:
            ui["helperText"] = helper
        field = {"key": tgt["fkey"], "type": ftype, "default": default, "ui": ui}
        if options is not None:
            field["options"] = list(options)
        if ftype == "secret":
            field["secret"] = True
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
                    self.notes.append(f"shared component {comp['type']} has no fields left after relocation; it was "
                                      "kept so instances that already select it stay valid")

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
                              if not created else None)
            self.package_only += [(ctype, k) for k in rows["package_only"]]

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
                                            "changes": [("default (secret withheld)", MASK, None)]})
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
        if f.get("type") != "secret":
            f.pop("secret", None)   # mark_secrets() adds the flag back to a field that becomes a secret
        return [(k, before[k], f.get(k)) for k in before if before[k] != f.get(k)]

    def assign_order(self, comp, new_keys):
        """Number new fields in the component's own style. new_keys None means the component was created now: it
        is numbered db-designer style, each field's position inside its section."""
        fields = comp["fields"]
        if new_keys is None:
            nxt = {}
            for f in fields:
                s = f["ui"]["section"]
                f["ui"]["order"], nxt[s] = nxt.get(s, 0), nxt.get(s, 0) + 1
            return
        order_style = self.style_of(comp["type"])[0]
        known = [f for f in fields if f["key"] not in new_keys]
        for f in fields:
            if f["key"] in new_keys:
                f["ui"]["order"] = style.order_for_new(known, f["ui"]["section"], order_style)
                known.append(f)

    def check_shared(self, comp, items):
        existing = {f["key"]: f for f in comp["fields"]}
        fed = {m["field"]["key"] for m in items}
        missing = [k for k, f in existing.items() if k not in fed and (comp["type"], k) not in self.held_fields
                   and not (f.get("virtual") or f.get("valueFrom") or self.foreign(f))]
        # every instance of this role renders these from the shared component, yet its YAML does not carry them
        self.package_only += [(comp["type"], k) for k in missing]
        if missing:
            self.diff_rows.setdefault(comp["type"], {"added": [], "changed": [], "package_only": []})[
                "package_only"].extend(missing)
        for m in items:
            f, d = existing.get(m["field"]["key"]), m["field"]
            if f is None:   # only a key the user agreed to add reaches here (shared-new was asked)
                extra = {"role": m["field_role"]} if m.get("field_role") else {}   # scope this-role
                d = ordered_field(dict(d, version=list(self.target), **extra))
                comp["fields"].append(d)
                self.assign_order(comp, {d["key"]})
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

    # ---------------------------------------------------------------- package-level items
    def field_at(self, ctype, fkey):
        comp = self.find_comp(ctype) if ctype else None
        return next((f for f in (comp or {}).get("fields", []) if f["key"] == fkey), None)

    @staticmethod
    def reorder(f):
        """Put f's attributes back in the package's order, in place."""
        out = ordered_field(dict(f))
        f.clear()
        f.update(out)

    @staticmethod
    def has_attribute(f, attribute):
        return attribute in f if attribute == "required" else attribute in (f.get("ui") or {})

    @staticmethod
    def set_attribute(f, attribute):
        if attribute == "required":
            f["required"] = True
        else:
            f.setdefault("ui", {})[attribute] = True

    def package_items(self):
        """After the merge: the restyle, the secret flag, suggestions and amendments (last), then the questions
        they leave open and the information lines."""
        if self.restyling:
            for comp in self.pkg["components"]:   # every component, the profile's `preserve` ones included
                style.restyle(comp)
        for comp in self.pkg["components"]:
            for key in style.mark_secrets(comp):
                self.marked.add((comp["type"], key))
                self.info.append(f"secret: true added: {comp['type']}/{key}")
        # this run's answers name a YAML key: resolve it to the field it was placed as
        fields = {p["key"]: (p["component"], p["field"]) for p in self.placed}
        late = decisions.effective(self.remembered, self.answers, self.role, fields=fields)
        self.suggestion_decisions = late["suggestions"]
        pending = self.apply_suggestions()
        self.apply_amendments(late["amendments"])
        self.ask_suggestions(pending)
        self.ask_restyle()
        self.info += [f"coerced: {c['key']}: {c['note']}" for c in self.coerced]
        self.info += [f"in the package, not in this YAML (kept): {c}/{k}" for c, k in self.package_only]
        if self.ui_diffs:
            self.info.append(f"reviewed labels kept: {len(self.ui_diffs)}")
        self.info = list(dict.fromkeys(self.info))
        self.note_preserved(late["amendments"])

    def note_preserved(self, amendments):
        """The profile's `preserve` components: untouched only when nothing changed them."""
        old = {c["type"]: c for c in (self.old or {}).get("components", [])}
        for ctype in (self.profile.get("preserve") or {}).get("components", []):
            comp = self.find_comp(ctype)
            if comp is None:
                continue
            if comp == old.get(ctype):
                self.notes.append(f"component {ctype} preserved untouched (profile `preserve`)")
                continue
            rows = self.diff_rows.get(ctype) or {}
            fed = bool(rows.get("added") or rows.get("removed")) or any(
                what != "default (secret withheld)" for ch in rows.get("changed") or [] for what, _, _ in ch["changes"])
            amended = {self.placements.get(a["key"], (None,))[0] if a.get("key") is not None else a.get("component")
                       for a in amendments}
            why = [w for w, hit in (
                ("this YAML's fields", fed),
                ("the accepted restyle", self.restyling),
                ("secret: true added", any(c == ctype for c, _ in self.marked)),
                ("secret default withheld (I6)", any(n.startswith(f"secret default withheld: {ctype}/")
                                                     for n in self.notes)),
                ("your amendments", ctype in amended)) if hit]
            self.notes.append(f"component {ctype} preserved (profile `preserve`), but this run changes it: "
                              f"{', '.join(why) or 'see the tables'}")

    def suggestible(self):
        """(placed entry, field) for every placed field a suggestion may name: an `index` key's items are not."""
        for p in self.placed:
            f = self.field_at(p["component"], p["field"]) if p["field"] in p["field_keys"] else None
            if f is not None:
                yield p, f

    def apply_suggestions(self):
        """Accepted suggestions give the field the attribute; returns the undecided ones as (entry, Suggestion)."""
        decided, pending = self.suggestion_decisions, []
        for p, f in self.suggestible():
            for attribute in ("required", "advanced"):
                if decided.get((p["component"], p["field"], attribute)) == "accept" and \
                        not self.has_attribute(f, attribute):
                    self.set_attribute(f, attribute)
                    self.reorder(f)
            for sug in learn_samples.suggest(self.index, p["key"].split(".")[-1]):
                ident = (p["component"], p["field"], sug.attribute)
                self.suggestion_evidence[ident] = list(sug.evidence)
                if ident not in decided:
                    pending.append((p, sug))
        return pending

    def ask_suggestions(self, pending):
        """One question per undecided suggestion on a field that (after the amendments) still lacks the attribute."""
        for p, sug in pending:
            f = self.field_at(p["component"], p["field"])
            if f is None or self.has_attribute(f, sug.attribute):
                continue
            word = "required" if sug.attribute == "required" else "advanced (hidden until the user expands it)"
            self.ask("suggest", p["key"], p["line"],
                     f"{sug.support} of {sug.matched} matching sample fields are {word}. Mark {p['component']}/"
                     f"{p['field']} {sug.attribute}? Accept or reject?", PACKAGE_CHOICES["suggest"],
                     {"component": p["component"], "field": p["field"], "attribute": sug.attribute,
                      "evidence": list(sug.evidence), "support": sug.support, "matched": sug.matched},
                     qid=f"suggest:{p['component']}/{p['field']}/{sug.attribute}")

    def amend_target(self, a):
        """(component, field), name and the field an amendment names; the field is None when there is none."""
        if a.get("key") is not None:
            place = self.placements.get(a["key"])
            target, name = (place[:2] if place else None), a["key"]
        else:
            target, name = (a.get("component"), a.get("field")), f"{a.get('component')}/{a.get('field')}"
        return target, name, (self.field_at(*target) if target else None)

    def apply_amendments(self, amendments):
        """The user's amendments, applied last. `ui.<x>` sets field["ui"][x]; null removes an attribute (a null
        default stays a null default). A secret field never takes a non-null default (I6)."""
        for a in amendments:
            target, name, f = self.amend_target(a)
            setting = a.get("set")
            if f is None or not isinstance(setting, dict):
                self.info.append(f"amendment not applied: {name}: no such field in this package")
                continue
            secret = setting.get("type", f.get("type")) == "secret"
            for attribute, value in setting.items():
                if attribute == "default" and secret:
                    if value is not None:
                        self.notes.append(f"amendment refused (I6): {name}: a secret field takes no default")
                    continue
                if attribute.startswith("ui."):
                    ui = f.setdefault("ui", {})
                    if value is None:
                        ui.pop(attribute[3:], None)
                    else:
                        ui[attribute[3:]] = value
                elif value is None and attribute != "default":
                    f.pop(attribute, None)
                else:
                    f[attribute] = copy.deepcopy(value)
            if f.get("type") == "secret":
                f["default"] = None   # a field made a secret keeps no value (I6)
                if not f.get("secret"):
                    f["secret"] = True
                    self.marked.add((target[0], f["key"]))
            else:
                f.pop("secret", None)
            self.reorder(f)

    def restyle_counts(self):
        """(per component, totals): what a restyle changes, counted over copies of the input components."""
        per, totals = {}, dict(NO_COUNTS)
        for comp in (self.old or {}).get("components", []):
            counts = style.restyle(copy.deepcopy(comp))
            per[comp["type"]] = counts
            for k in totals:
                totals[k] += counts[k]
        return per, totals

    def ask_restyle(self):
        """One block for the whole package; its totals are counted over copies of the input components."""
        if not self.restyle_needed or self.decided["restyle"] is not None:
            return
        per, totals = self.restyle_counts()
        n = sum(len(c.get("fields") or []) for c in self.old["components"])
        preserved = [c for c in (self.profile.get("preserve") or {}).get("components", []) if c in per]
        self.ask("restyle", None, None,
                 f"restyle the package to db-designer style: new positions on {totals['order_changed']} of {n} fields, "
                 f"stock helper text removed from {totals['stock_removed']}, {totals['custom_kept']} custom kept. "
                 "Accept or refuse? Refused, every component keeps its own style and new fields follow it.",
                 PACKAGE_CHOICES["restyle"], {"totals": totals, "components": per, "fields": n, "preserved": preserved})

    def changed(self):
        """Does the build change the package (its provenance and authoring counts aside)?"""
        def body(pkg):
            return {k: v for k, v in pkg.items() if k not in ("provenance", "authoring")}
        return self.old is not None and body(self.pkg) != body(self.old)

    def settle_provenance(self):
        """A signed package this build changes, or would change through the open restyle, asks the signature
        question now rather than one round late."""
        prov = self.pkg.get("provenance") or {}
        if not (prov.get("signature") and (self.changed() or "restyle" in self.open)):
            return
        self.provenance_asked = True
        choice = self.decided["provenance"]
        if choice == "dev-mode":
            self.pkg["provenance"] = {"author": "dev-mode"}
            self.notes.append("provenance set to dev-mode (unsigned) at the user's request")
        elif choice is None:
            how = "this build changes it" if self.changed() else "an accepted restyle would change it"
            self.ask("provenance", None, None,
                     f"the package is signed and {how}, so the signature would no longer cover it. Keep the "
                     "provenance as it is, or switch to dev-mode (unsigned)?", PACKAGE_CHOICES["provenance"],
                     {"author": prov.get("author")})

    def build_rows(self):
        """One row per placed field key, for the preview. No row and no note holds a secret value (I6)."""
        old_fields = {(c["type"], f["key"]): f for c in (self.old or {}).get("components", [])
                      for f in c.get("fields", [])}
        yaml_says = {(d["component"], d["key"]): d["yaml"] for d in self.shared_diffs}
        open_ids = set(self.open)
        self.rows = []
        for p in self.placed:
            ctype = p["component"]
            comp = self.find_comp(ctype) or {}
            for fkey in p["field_keys"]:
                f = self.field_at(ctype, fkey)
                if f is None:
                    continue
                ui, old = f.get("ui") or {}, old_fields.get((ctype, fkey))
                secret = f.get("type") == "secret"
                if secret:
                    shown_default = "withheld (I6)"
                elif f.get("valueFrom"):
                    shown_default = "from connection"
                else:
                    shown_default = json.dumps(f.get("default"), ensure_ascii=False)
                notes = []
                if old is None:
                    notes.append("new")
                elif not secret and old.get("type") != "secret" and old.get("default") != f.get("default"):
                    notes.append("was " + json.dumps(old.get("default"), ensure_ascii=False))
                if (ctype, fkey) in yaml_says and not secret:
                    notes.append("YAML says " + json.dumps(yaml_says[(ctype, fkey)], ensure_ascii=False))
                if f.get("valueFrom"):
                    notes.append("valueFrom")
                if (ctype, fkey) in self.marked:
                    notes.append("secret: true added")
                suggested = {}
                for attribute in ("required", "advanced"):
                    decision = self.suggestion_decisions.get((ctype, fkey, attribute))
                    if f"suggest:{ctype}/{fkey}/{attribute}" in open_ids:
                        suggested[attribute] = True   # still a question
                    elif decision is not None:
                        suggested[attribute] = decision == "accept"
                self.rows.append({
                    "component": ctype, "key": p["key"], "line": p["line"], "field": fkey, "type": f.get("type"),
                    "default_display": shown_default, "section": ui.get("section"), "order": ui.get("order"),
                    "shown": "advanced" if ui.get("advanced") else "up front", "required": f.get("required") is True,
                    "suggested": suggested, "role": f.get("role") or comp.get("role"),
                    "versions": f.get("version") or comp.get("version"), "notes": notes})

    # ---------------------------------------------------------------- finish
    def finish(self):
        # reviewed = the fields carried over unchanged (the restyle, the secret flag, suggestions and amendments
        # count as changes), capped at the previous count; a new package starts at 0
        total = sum(len(c.get("fields", [])) for c in self.pkg["components"])
        old_fields = {(c["type"], f["key"]): f for c in (self.old or {}).get("components", [])
                      for f in c.get("fields", [])}
        unchanged = sum(1 for c in self.pkg["components"] for f in c.get("fields", [])
                        if old_fields.get((c["type"], f["key"])) == f)
        old_reviewed = (self.old or {}).get("authoring", {}).get("reviewedRows", 0)
        reviewed = min(old_reviewed, unchanged) if self.old else 0
        auth = self.pkg.setdefault("authoring", {})
        auth["reviewedRows"], auth["totalRows"] = reviewed, total
        self.settle_provenance()
        self.build_rows()
        if (self.profile.get("defaults") or {}).get("role_components", "yaml") == "yaml" and \
                any(r["changed"] for r in self.diff_rows.values()):
            self.notes.append("defaults were taken from the YAML (profile defaults.role_components: yaml). This "
                              "conflicts with docs/design/dynamic-ui/design.md section 7.3 (UI-D*, PROPOSED): defaults "
                              "come from field initializers, never from a sample application.yml. Flagged, not resolved.")
        starts = [x for x in (self.args.package, self.args.out_dir, os.getcwd(), os.path.dirname(os.path.abspath(__file__)))
                  if x]
        self.schema = self.args.schema or validate_package.find_schema(*starts)
        self.validation = validate_package.validate(self.pkg, self.schema)
        self.balance_ok = self.balanced()

    # ---------------------------------------------------------------- the preview
    def unsettled_answers(self):
        """Every answer of this build's answers file that settles nothing in it, one message each (exit 2): the
        final check after the staged validation, since a later answer can take away what an earlier one named."""
        errors, a = [], self.answers
        removed_keys = {r["key"] for r in self.removed if r["line"] is not None}
        removed_fields = {(r["component"], r["field"]) for r in self.removed if r["line"] is None}
        for e in a.get("remove") or []:
            if e.get("key") is not None and e["key"] not in removed_keys or \
                    e.get("key") is None and (e.get("component"), e.get("field")) not in removed_fields:
                name = e["key"] if e.get("key") is not None else f"{e.get('component')}/{e.get('field')}"
                errors.append(f"remove {name}: removes nothing in this build")
        for e in a.get("place") or []:
            if e["key"] in removed_keys:
                errors.append(f"place {e['key']}: the key is also removed by a remove answer; give one of the two")
            elif e["key"] not in self.used_place:
                errors.append(f"place {e['key']}: settles no question in this build")
        for e in a.get("store") or []:
            if (e["key"], e["as"]) not in self.used_store:
                errors.append(f"store {e['key']}: {e['as']!r} settles no question in this build")
        for e in a.get("amend") or []:
            _, name, f = self.amend_target(e)
            if f is None:
                errors.append(f"amend {name}: no field to amend in this build (a key stored as one field per item "
                              "is amended by component and field)")
        placed = {p["key"]: (p["component"], p["field"]) for p in self.placed}
        for e in a.get("suggestions") or []:
            target = placed.get(e["key"])
            if target is None or (target[0], target[1], e["attribute"]) not in self.suggestion_evidence:
                errors.append(f"suggestions {e['key']}: no {e['attribute']} suggestion in this build")
        if "restyle" in a and not self.restyle_needed:
            errors.append("restyle: no restyle is offered in this build")
        if "provenance" in a and not self.provenance_asked:
            errors.append(NO_SIGNATURE_QUESTION)
        return errors

    def answer_context(self):
        """What decisions.validate_answers checks an answers file against: this build's questions and fields."""
        comps = self.pkg["components"]
        fields = {k: v[:2] for k, v in self.placements.items()}
        fields.update({p["key"]: (p["component"], p["field"]) for p in self.placed})
        return {
            "yaml_keys": set(self.yaml_keys), "open": self.open, "fields": fields,
            "package_fields": {(c["type"], f["key"]) for c in comps for f in c.get("fields", [])},
            "secrets": {(c["type"], f["key"]) for c in comps for f in c.get("fields", []) if f.get("type") == "secret"},
            "components": {c["type"] for c in comps} | {c["type"] for c in self.profile["components"]},
            "shared_components": {c["type"] for c in comps if is_shared(c)}
            | {c["type"] for c in self.profile["components"] if is_shared(c)},
            "remembered": self.remembered,
        }

    def ordered_questions(self):
        """Key questions by YAML line, then suggestions, the restyle and the signature."""
        items = list(self.open.values())
        keyed = sorted((q for q in items if q["kind"] in KEY_KINDS), key=lambda q: (q["line"] is None, q["line"] or 0))
        tail = [q for kind in ("suggest", "restyle", "provenance") for q in items if q["kind"] == kind]
        rest = [q for q in items if q not in keyed and q not in tail]
        return keyed + rest + tail

    def merged_decisions(self, answers):
        """The decisions block the remembered one becomes with these answers."""
        return decisions.merge(self.remembered, answers, today=self.today, placements=self.placements,
                               evidence=self.suggestion_evidence, role=self.role)

    def profile_text(self):
        """The profile `apply` writes: the original text byte for byte, then the merged decisions block."""
        return decisions.write_block(read_text(self.args.profile), self.merged_decisions(self.answers))

    def profile_changes(self):
        """The decisions block before and after "create", as unified diff lines (nothing when they agree)."""
        before = self.merged_decisions({})
        after = self.merged_decisions(self.answers)

        def text(block):
            if not block:
                return []
            return yaml.safe_dump({"decisions": block}, sort_keys=False, allow_unicode=True, width=110).splitlines()
        return list(difflib.unified_diff(text(before), text(after), fromfile="decisions (profile now)",
                                         tofile='decisions (saved on "create")', lineterm=""))

    def model(self):
        """The preview: the content of preview.json. It holds no secret value (I6)."""
        comps = self.pkg["components"]
        prof = self.profile["components"]
        in_pkg = {c["type"] for c in comps}
        mine = [c for c in comps if not is_shared(c) and c.get("role") == self.role] + \
               [c for c in prof if c["type"] not in in_pkg and not is_shared(c) and c.get("role") == self.role]
        shared = [c["type"] for c in comps if is_shared(c)] + \
                 [c["type"] for c in prof if c["type"] not in in_pkg and is_shared(c)]
        components = []
        for c in comps:
            rows = [r for r in self.rows if r["component"] == c["type"]]
            if rows:
                components.append({"type": c["type"], "role": c.get("role", "none"), "shared": is_shared(c),
                                   "rows": rows})
        questions = [dict(n=n, **{k: q[k] for k in ("id", "kind", "key", "line", "text", "choices", "context")})
                     for n, q in enumerate(self.ordered_questions(), 1)]
        info = list(self.info) + list(self.notes)
        if not self.validation["ok"]:
            info += [f"validation would fail: {e}" for e in self.validation["errors"]]
        if not self.balance_ok:
            info.append("not every YAML key is exactly one of placed, open or removed: investigate before creating")
        return {
            "solution": self.profile["solution"], "role": self.role, "profile": self.args.profile,
            "yaml": self.args.yaml, "package": self.args.package, "target_versions": self.target,
            "packageVersion": self.pkg.get("packageVersion"), "appliesTo": self.pkg.get("appliesTo"),
            "roles": {"all": list(self.profile.get("roles") or []),
                      "this_role": [{"type": c["type"], "cardinality": c.get("cardinality")} for c in mine],
                      "shared": shared},
            "samples": self.samples,
            "counts": {"keys": self.key_count, "placed": len(self.placed),
                       "removed": len([r for r in self.removed if r["line"] is not None]),
                       "open": len(self.open_keys())},
            "components": components, "questions": questions, "removed": self.removed,
            "info": list(dict.fromkeys(info)), "profile_changes": self.profile_changes(), "ready": not self.open,
        }

    # ---------------------------------------------------------------- apply: the files it writes
    def ascii_style(self):
        if self.args.package:
            return "\\u201c" in read_text(self.args.package)
        return False

    def package_text(self, pkg=None):
        """A package as package.json is written: the input package's JSON style (`ensure_ascii` as it uses)."""
        return json.dumps(self.pkg if pkg is None else pkg, indent=2, ensure_ascii=self.ascii_style()) + "\n"

    def secret_fields(self):
        """(component, field) of every secret field of the input package or the new one."""
        return {(c["type"], f["key"]) for pkg in (self.old or {}, self.pkg) for c in pkg.get("components", [])
                for f in c.get("fields", []) if f.get("type") == "secret" or f.get("secret")}

    def package_diff(self):
        """The unified diff from the input package to the new one. The input side is a copy whose secret defaults
        print as *** (I6), written in the same JSON style as package.json."""
        masked, secrets = copy.deepcopy(self.old), self.secret_fields()
        for c in masked.get("components", []):
            for f in c.get("fields", []):
                if (c["type"], f["key"]) in secrets and f.get("default") not in (None, ""):
                    f["default"] = MASK
        if self.package_text(self.old) != read_text(self.args.package):
            self.notes.append(f"package.diff compares content: {self.args.package} is formatted differently from "
                              "the generated package.json, and that formatting change is not shown")
        return "".join(difflib.unified_diff(
            self.package_text(masked).splitlines(True), self.package_text().splitlines(True),
            fromfile=f"{self.args.package} (secret defaults shown as {MASK})", tofile="package.json (generated)", n=2))

    def report_json(self, validation=None):
        """report.json. validation: the gate's result (apply); this build's own validation otherwise. Every
        default of a secret field prints as *** (I6); nothing is listed twice."""
        secrets = self.secret_fields()
        diff = copy.deepcopy(self.diff_rows)
        for ctype, rows in diff.items():
            for ch in rows["changed"]:
                if (ctype, ch["key"]) in secrets:
                    ch["changes"] = [(what, *(MASK if what.startswith("default") and v not in (None, "") else v
                                              for v in (old, new))) for what, old, new in ch["changes"]]
        placed = []
        for p in self.placed:
            f = self.field_at(p["component"], p["field_keys"][0]) if p["field_keys"] else None
            placed.append({"key": p["key"], "line": p["line"], "component": p["component"], "field": p["field"],
                           "type": (f or {}).get("type"), "shared": p["shared"]})
        own = {(p["component"], p["field"]) for p in self.placed}
        restyle = {"offered": self.restyle_needed, "decision": self.decided["restyle"]}
        if self.restyle_needed:
            restyle["counts"] = self.restyle_counts()[1]
        notes = self.notes + [i for i in self.info if not i.startswith(REPORTED_ELSEWHERE)]
        return {
            "solution": self.profile["solution"], "role": self.role, "profile": self.args.profile,
            "yaml": self.args.yaml, "package": self.args.package, "target_versions": self.target,
            "counts": {"keys": self.key_count, "placed": len(self.placed),
                       "removed": len([r for r in self.removed if r["line"] is not None]),
                       "open": len(self.open_keys()), "coerced": len(self.coerced),
                       "secrets_withheld": len(self.withheld), "balanced": self.balance_ok},
            "placed": placed,
            "removed": [{k: r[k] for k in ("key", "line", "reason", "role", "component")} for r in self.removed],
            "coerced": self.coerced, "secrets_withheld": self.withheld, "diff": diff,
            "shared_differences": self.shared_diffs, "ui_differences": self.ui_diffs,
            "notes": list(dict.fromkeys(notes)), "validation": validation or self.validation,
            "authoring": self.pkg.get("authoring"), "restyle": restyle,
            "suggestions": [{"component": c, "field": f, "attribute": a, "decision": d}
                            for (c, f, a), d in self.suggestion_decisions.items() if (c, f) in own],
        }

    @staticmethod
    def report_md(r):
        """report.md: the same data as report.json."""
        c, none = r["counts"], "(none)"

        def show(value):
            return json.dumps(value, ensure_ascii=False)
        L = [f"# yamlToPackage report: {r['solution']} / {r['role']}", "",
             f"- Profile: `{r['profile']}`", f"- YAML: `{r['yaml']}`",
             f"- Package: `{r['package'] or '(new package)'}`", f"- Target versions: {r['target_versions']}", "",
             "## Coverage", "",
             f"{c['keys']} YAML keys → {c['placed']} fields, {c['removed']} removed by you "
             f"(balanced: {'yes' if c['balanced'] else 'NO'})", "",
             f"{c['open']} open · {c['coerced']} coerced · {c['secrets_withheld']} secret default(s) withheld (I6)", ""]
        by = {}
        for p in r["placed"]:
            by.setdefault(p["component"], []).append(p)
        L += ["### Placed, by component", ""]
        if by:
            L += ["| component | keys | shared |", "|---|---|---|"]
            L += [f"| {k} | {len(v)} | {'yes' if v[0]['shared'] else 'no'} |" for k, v in by.items()]
        else:
            L.append(none)
        L += ["", "### Removed by you", ""]
        L += [f"- `{e['key']}` (line {e['line']}): {e['reason']}" if e["line"] is not None else
              f"- `{e['component']}/{e['key']}` (a package field this YAML does not carry): {e['reason']}"
              for e in r["removed"]] or [none]
        L += ["", "### Coerced / assumed", ""]
        L += [f"- `{x['key']}` (line {x['line']}): {x['note']}" for x in r["coerced"]] or [none]
        L += ["", "### Secret defaults withheld (I6)", ""]
        L += [f"- `{x['key']}` (line {x['line']})" for x in r["secrets_withheld"]] or [none]
        L += ["", "## Diff against the existing package", ""]
        if not r["package"]:
            L += ["New package; no diff.", ""]
        for ct, rows in r["diff"].items():
            L += [f"### {ct}", "", f"- added: {len(rows['added'])}" + (f": {', '.join(rows['added'])}" if rows["added"] else "")]
            if rows.get("removed"):
                L.append(f"- removed (by you, or relocated by the profile): {len(rows['removed'])}: "
                         f"{', '.join(rows['removed'])}")
            L.append(f"- changed: {len(rows['changed'])}")
            for ch in rows["changed"]:
                for what, old, new in ch["changes"]:
                    L.append(f"  - `{ch['key']}` {what}: `{show(old)}` -> `{show(new)}`")
            L.append(f"- in the package, not in this YAML (kept): {len(rows['package_only'])}"
                     + (f": {', '.join(rows['package_only'])}" if rows["package_only"] else ""))
            L.append("")
        if r["shared_differences"]:
            L += ["### Shared components: YAML vs package defaults (package kept)", ""]
            L += [f"- {d['component']} `{d['key']}`: package `{show(d['package'])}`, YAML `{show(d['yaml'])}`"
                  + (" (valueFrom)" if d["valueFrom"] else "") for d in r["shared_differences"]]
            L.append("")
        if r["ui_differences"]:
            L += [f"### Reviewed labels kept where the derived one differs ({len(r['ui_differences'])})", ""]
            L += [f"- {d['component']} `{d['key']}`: package {d['package']}, derived {d['derived']}"
                  for d in r["ui_differences"][:15]]
            if len(r["ui_differences"]) > 15:
                L.append(f"- ...and {len(r['ui_differences']) - 15} more in report.json")
            L.append("")
        L += ["## Your decisions", "", "### Suggestions", ""]
        L += [f"- {s['component']}/{s['field']} {s['attribute']}: {s['decision']}" for s in r["suggestions"]] or [none]
        rs = r["restyle"]
        L += ["", "### Restyle", ""]
        if rs["offered"]:
            n = rs["counts"]
            L.append(f"{rs['decision']}: new positions on {n['order_changed']} field(s), stock helper text removed "
                     f"from {n['stock_removed']}, {n['custom_kept']} custom kept")
        else:
            L.append("not offered: the package is already in db-designer style, or new")
        v = r["validation"]
        L += ["", "## Validation and read-back", "", f"**{'PASS' if v['ok'] else 'FAIL'}**", ""]
        L += [f"- note: {n}" for n in v["notes"]] + [f"- error: {e}" for e in v["errors"]] + \
             [f"- warning: {w}" for w in v["warnings"]]
        L += ["", "## Notes", ""] + ([f"- {n}" for n in r["notes"]] or [none])
        L += ["", f"authoring: {json.dumps(r['authoring'])}", ""]
        return "\n".join(L)


def add_shared(ap, out_dir_required=False):
    """The arguments every command shares."""
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
    ap.add_argument("--out-dir", required=out_dir_required)
    return ap


def parser():
    """The shared arguments alone: what build() parses."""
    return add_shared(argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter))


def run_build(args, answers=None):
    for flag, path in (("--profile", args.profile), ("--yaml", args.yaml), ("--package", args.package),
                       ("--answers", args.answers)):
        if path and not os.path.isfile(path):
            die(2, f"{flag} {path}: no such file")
    run = Run(args, answers)
    run.check_inputs()
    run.relocate()
    run.classify()
    run.merge()
    run.package_items()
    run.finish()
    return run


def build(argv):
    """Parse the shared arguments and run the build: every YAML key placed, open or removed by you."""
    return run_build(parser().parse_args(argv))


DUP_PICKS = tuple(CHOICES["dup-key"][:2])   # `first` / `second`: they decide which value the other questions see


# Why the stages fall where they do: each one is checked against a build with every stage before it applied,
# and a stage holds only answers that a later stage's questions can depend on, never the other way round.
#   1. remove / place / forget decide where a key goes (or that it goes nowhere): the questions a target raises
#      (type-conflict, enum-value, shared scope) exist only after them.
#   2. the dup-key picks (`first` / `second`) decide which value the value questions see (a list only in copy 2).
#   3. the other `store` answers shape the field: `index` makes the `<key>.<n>` fields an amendment can name.
#   4. amend comes after every key-level answer, so it can name any field they create.
#   5. suggestions and restyle look at the fields amend leaves (an amended `required` takes a suggestion away).
#   6. provenance last: whether the signed package changes depends on the restyle and on everything before it.
# After the last stage, Run.unsettled_answers() checks the final build: an answer that settles nothing is an error.
def answer_stages(answers):
    """The answers in the order they act on the build, one dict per stage (see the comment above)."""
    store = answers.get("store")
    if isinstance(store, list):
        picks = [e for e in store if isinstance(e, dict) and e.get("as") in DUP_PICKS]
        rest = [e for e in store if not (isinstance(e, dict) and e.get("as") in DUP_PICKS)]
    else:
        picks, rest = None, store
    stages = [{s: answers[s] for s in ("remove", "place", "forget") if s in answers},
              {"store": picks},
              {"store": rest},
              {"amend": answers.get("amend")},
              {s: answers[s] for s in ("suggestions", "restyle") if s in answers},
              {s: answers[s] for s in ("provenance",) if s in answers}]
    return [{s: v for s, v in stage.items() if v is not None and v != []} for stage in stages]


def validate_answers(args, answers):
    """Every problem in the answers file, as one message each, and the final build ([] and the run when the
    answers are valid). Input errors come first (exit 2), before the open-questions gate (exit 5)."""
    given, run = {}, None
    for stage in answer_stages(answers):
        if not stage:
            continue
        if run is None or run.answers != given:
            run = run_build(args, copy.deepcopy(given))
        if "provenance" in stage and "provenance" not in run.open:
            return [NO_SIGNATURE_QUESTION], None
        errors = decisions.validate_answers(stage, run.answer_context())
        if errors:
            return errors, None
        for section, value in stage.items():
            given[section] = given[section] + value if isinstance(given.get(section), list) else value
    run = run_build(args, answers)
    return run.unsettled_answers(), run


def summary(model):
    c = model["counts"]
    return (f"{model['solution']}/{model['role']}: keys={c['keys']} placed={c['placed']} removed={c['removed']} "
            f"open={c['open']} questions={len(model['questions'])}")


def read_previous(out_dir, model):
    """(the last preview of the same solution and role in out_dir, or None; an info line when one was ignored)."""
    path = os.path.join(out_dir, "preview.json")
    if not os.path.exists(path):
        return None, None
    try:
        with open(path, encoding="utf-8") as fh:
            previous = json.load(fh)
        if (previous.get("solution"), previous.get("role")) != (model["solution"], model["role"]):
            return None, None   # another run's scratch folder: nothing to compare
        preview_md.changes(model, previous)   # the shape a comparison reads
    except (OSError, ValueError, KeyError, TypeError, AttributeError, IndexError):
        return None, f"the earlier {path} is not a preview this engine wrote; it was not compared, and is replaced"
    return previous, None


def preview(run):
    """Write preview.json and preview.md; never package.json. 0 when nothing is open, 5 otherwise."""
    out = run.args.out_dir
    model = json.loads(json.dumps(run.model(), ensure_ascii=False, default=str))   # exactly what preview.json holds
    previous, note = read_previous(out, model)
    if note:
        model["info"].append(note)
    os.makedirs(out, exist_ok=True)
    write_text(os.path.join(out, "preview.json"), json.dumps(model, indent=2, ensure_ascii=False) + "\n")
    write_text(os.path.join(out, "preview.md"), preview_md.render(model, previous))
    print(summary(model))
    return 0 if model["ready"] else 5


def read_back(package_path, placed, removed):
    """The no-trim gate: the written package read back. One problem per placed field key missing from its component
    (every key in `field_keys`), and per removed field key still present. A removed entry is checked by its `field`
    and every key in its `field_keys` (by its YAML key when it has neither): in its `component`, or, when that is
    None, in the components of the entry's role and the shared ones, never in another role's component or a shared
    field that serves another role only (Ruling 12)."""
    with open(package_path, encoding="utf-8") as fh:
        pkg = json.load(fh)
    comps = pkg.get("components") or []
    by_type = {c.get("type"): c for c in comps}

    def keys(comp):
        return {f.get("key") for f in (comp or {}).get("fields") or []}
    problems = []
    for p in placed:
        have = keys(by_type.get(p["component"]))
        problems += [f"missing: {p['component']}/{k}" for k in p.get("field_keys") or [] if k not in have]
    for r in removed:
        names = list(dict.fromkeys(([r["field"]] if r.get("field") else []) + list(r.get("field_keys") or []))) \
            or [r["key"]]
        if r.get("component") is not None:
            have = keys(by_type.get(r["component"]))
            problems += [f"still present: {r['component']}/{k}" for k in names if k in have]
            continue
        role = r.get("role")
        for c in comps:
            if not (is_shared(c) or c.get("role") == role):
                continue
            problems += [f"still present: {c['type']}/{f['key']}" for f in c.get("fields") or []
                         if f.get("key") in names and f.get("role") in (None, "none", role)]
    return problems


def gate(run, package_path):
    """The validation of the written package, with the read-back and the balance folded in: report.json's
    `validation`, and exit 1 unless it is ok."""
    with open(package_path, encoding="utf-8") as fh:
        validation = validate_package.validate(json.load(fh), run.schema)
    problems = read_back(package_path, run.placed, run.removed)
    errors = list(validation["errors"]) + [f"read-back: {p}" for p in problems]
    if not run.balance_ok:
        errors.append("not every YAML key is exactly one of placed, open or removed")
    notes = list(validation["notes"])
    if not problems:
        notes.append(f"read-back: {sum(len(p['field_keys']) for p in run.placed)} placed field(s) present, "
                     f"{len(run.removed)} removed key(s) absent")
    return {"ok": not errors, "errors": errors, "warnings": list(validation["warnings"]), "notes": notes}


OUTPUTS = {"preview": ("preview.json", "preview.md"),
           "apply": ("package.json", "package.diff", "report.md", "report.json", "profile.yaml")}


def check_outputs(args):
    """No output may be one of the inputs: the engine never overwrites its YAML, package, profile or answers."""
    inputs = {os.path.realpath(p) for p in (args.profile, args.yaml, args.package, args.answers) if p}
    for name in OUTPUTS[args.command]:
        if os.path.realpath(os.path.join(args.out_dir, name)) in inputs:
            die(2, f"--out-dir {args.out_dir}: {name} would overwrite an input; choose another --out-dir")


def apply(run):
    """Refuse while anything is open (5). Otherwise write package.json, package.diff (with --package), report.md,
    report.json and profile.yaml to --out-dir, then read the package back: 1 on a read-back problem or a validation
    error, else 0."""
    if run.open:
        print(f"error: {len(run.open)} question(s) still open; answer them in a preview first:", file=sys.stderr)
        for q in run.ordered_questions():
            print(f"open: {q['id']}", file=sys.stderr)
        return 5
    out = run.args.out_dir
    os.makedirs(out, exist_ok=True)
    package_path = os.path.join(out, "package.json")
    write_text(package_path, run.package_text())
    if run.old is not None:
        write_text(os.path.join(out, "package.diff"), run.package_diff())
    validation = gate(run, package_path)
    report = run.report_json(validation)
    write_text(os.path.join(out, "report.json"), json.dumps(report, indent=2, ensure_ascii=False, default=str) + "\n")
    write_text(os.path.join(out, "report.md"), run.report_md(json.loads(json.dumps(report, default=str))))
    write_text(os.path.join(out, "profile.yaml"), run.profile_text())
    print(summary(run.model()))
    for e in validation["errors"]:
        print(f"error: {e}", file=sys.stderr)
    return 0 if validation["ok"] else 1


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command", required=True, metavar="{preview,apply}")
    for name, text in (("preview", "show every field and the open questions; writes preview.md and preview.json"),
                       ("apply", "create package.json once nothing is open")):
        add_shared(sub.add_parser(name, help=text, description=text), out_dir_required=True)
    args = ap.parse_args(argv)
    check_outputs(args)
    if args.answers and not os.path.isfile(args.answers):
        die(2, f"--answers {args.answers}: no such file")
    answers = load_answers(args.answers)
    errors, run = validate_answers(args, answers)
    if errors:
        for e in errors:
            print(f"error: answers: {e}", file=sys.stderr)
        return 2
    return preview(run) if args.command == "preview" else apply(run)


if __name__ == "__main__":
    sys.exit(main())
