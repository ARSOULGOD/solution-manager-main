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


def is_shared(spec):
    return bool(spec.get("shared")) or spec.get("role") in (None, "none")


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
        removed = [r["key"] for r in self.removed]
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
        if shared and comp is not None and not any(f["key"] == fkey for f in comp.get("fields", [])):
            return ("shared-new", f"not in shared component {ctype}; adding it affects every role. Add it for "
                                  f"every role, for role {self.role!r} only, move it to this role's component, "
                                  "or remove it?", {"component": ctype}, tgt)
        return tgt

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
        self.yaml_keys = [leaf["key"] for leaf in leaves]
        self.key_count = len(set(self.yaml_keys))
        if parsed["documents"] > 1:
            self.notes.append(f"YAML has {parsed['documents']} documents; keys from document 2+ are questions")
        removed, placed = self.decided["removed"], self.decided["placed"]
        duplicates = {}
        for d in parsed["duplicates"]:
            duplicates.setdefault(d["key"], []).append(d)

        candidates = []
        for leaf in leaves:
            key = leaf["key"]
            if key in removed:
                self.remove(leaf, removed[key])
                continue
            nat = self.natural(leaf)
            decision = placed.get(key)
            tgt = None
            if decision and "scope" not in decision:   # shared-component scopes are applied later
                tgt = self.answered(leaf, decision)
            elif isinstance(nat, dict):
                tgt = nat
            else:
                kind, text, context, tentative = nat
                self.ask(kind, key, leaf["line"], text, CHOICES[kind], context)
                if tentative:
                    self.note_placement(key, tentative)
            vkind = self.value_kind(leaf)
            if vkind:
                self.ask(vkind, key, leaf["line"], self.VALUE_TEXT[vkind], CHOICES[vkind])
            for d in duplicates.get(key, []):
                self.ask("dup-key", key, d["first_line"],
                         f"written twice in one mapping (lines {d['first_line']} and {d['line']}). Use the first "
                         "value, the second, or remove it?", CHOICES["dup-key"],
                         {"lines": [d["first_line"], d["line"]]})
            if tgt is None:
                continue
            self.note_placement(key, tgt)
            field = None if vkind else self.derive(leaf, tgt)
            if field is not None:
                self.check_against_package(leaf, tgt, field)
            candidates.append({"leaf": leaf, "component": tgt["component"], "field": field,
                               "shared": tgt["shared"], "fkey": tgt["fkey"]})
        for d in parsed["duplicates"]:   # a duplicated mapping (not a leaf) still must not vanish
            if d["key"] not in set(self.yaml_keys):
                self.ask("dup-key", d["key"], d["first_line"],
                         f"a mapping written twice (lines {d['first_line']} and {d['line']}); the keys under the "
                         "second copy are not read. Use the first, the second, or remove it?", CHOICES["dup-key"],
                         {"lines": [d["first_line"], d["line"]]})

        self.ask_spelling(candidates)
        held = self.open_keys()
        # fields an open key would feed: not "in the package but not in this YAML"
        self.held_fields = {self.placements[k][:2] for k in held if k in self.placements}
        self.mapped = [c for c in candidates if c["leaf"]["key"] not in held]
        self.placed = [{"key": m["leaf"]["key"], "line": m["leaf"]["line"], "component": m["component"],
                        "field": m["fkey"], "shared": m["shared"], "field_keys": [m["fkey"]]} for m in self.mapped]
        placed_keys = {m["leaf"]["key"] for m in self.mapped}
        self.coerced = [c for c in self.coerced if c["key"] in placed_keys]
        self.withheld = [w for w in self.withheld if w["key"] in placed_keys]
        present = set(self.yaml_keys)
        for key in self.decided["stale_candidates"]:
            if key not in present:
                self.info.append(f"remembered decision for {key} (role {self.role}): not in this YAML")

    def note_placement(self, key, tgt):
        role = None if tgt["shared"] else tgt["spec"].get("role")
        self.placements[key] = (tgt["component"], tgt["fkey"], role)

    def remove(self, leaf, reason):
        nat = self.natural(leaf, honour_exclude=False)
        tgt = nat if isinstance(nat, dict) else (nat[3] if nat[0] in decisions.SHARED_KINDS else None)
        if tgt is not None:
            self.note_placement(leaf["key"], tgt)
        place = self.placements.get(leaf["key"])
        self.removed.append({"key": leaf["key"], "line": leaf["line"], "reason": reason,
                             "role": place[2] if place else self.role,
                             "component": place[0] if place else None,
                             "field": place[1] if place else None})

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

    def check_against_package(self, leaf, tgt, field):
        """enum-value and type-conflict: questions only the target component can raise."""
        key, options = leaf["key"], field.get("options")
        if options is not None and leaf["value"] is not None and leaf["value"] not in options:
            self.ask("enum-value", key, leaf["line"], f"value is not one of the profile's enum options {options}. "
                     "Add it to the options, make the field a string, or remove it?", CHOICES["enum-value"],
                     {"options": list(options)})
            return
        comp = self.find_comp(tgt["component"])
        f = next((g for g in (comp or {}).get("fields", []) if g["key"] == tgt["fkey"]), None)
        if f is None:
            return
        if not compatible(f["type"], field["type"], tgt["shared"]):
            self.ask("type-conflict", key, leaf["line"], f"the package declares {tgt['component']}/{f['key']} as "
                     f"{f['type']!r}; the YAML value is {field['type']!r}. Keep the package's type, use the YAML's, "
                     "or remove it?", CHOICES["type-conflict"],
                     {"component": tgt["component"], "package_type": f["type"], "yaml_type": field["type"]})
            return
        takes_yaml = not tgt["shared"] and \
            (self.profile.get("defaults") or {}).get("role_components", "yaml") == "yaml"
        if takes_yaml and f.get("options") and not f.get("valueFrom") and f["type"] != "secret" \
                and field["default"] is not None and field["default"] not in f["options"]:
            self.ask("enum-value", key, leaf["line"], f"value is not one of the package's options {f['options']}. "
                     "Add it to the options, make the field a string, or remove it?", CHOICES["enum-value"],
                     {"options": list(f["options"])})

    def derive(self, leaf, tgt):
        p, key, spec, pattern = self.profile, leaf["key"], tgt["spec"], tgt["pattern"]
        override = common.lookup(p.get("types") or {}, key)
        options = common.lookup(p.get("enums") or {}, key)
        ftype, note, _ = common.infer_type(leaf, p.get("secret_patterns") or [])
        if override:
            ftype = override
        default = common.coerce_default(ftype, leaf["value"]) if leaf["kind"] != "list" else leaf["value"]
        if leaf["kind"] == "scalar-joined":
            default = leaf["value"]
        if options is not None:
            ftype = "enum"
        if note:
            self.coerced.append({"key": key, "line": leaf["line"], "note": note})
        if leaf.get("raw") and leaf["raw"].lower() in common.YAML11_BOOL_WORDS \
                and not leaf["quoted"]:
            self.coerced.append({"key": key, "line": leaf["line"],
                                 "note": f"unquoted {leaf['raw']!r} kept as a string (a YAML 1.1 reader would make it a bool)"})
        if ftype in ("list", "map") and default is not None:
            # the schema allows only scalar defaults; the cache reference package leaves these null too
            self.coerced.append({"key": key, "line": leaf["line"],
                                 "note": f"{ftype} default {json.dumps(default)} not representable as a field default; left null"})
            default = None
        if ftype == "secret":
            if default not in (None, ""):
                self.withheld.append({"key": key, "line": leaf["line"]})
            default = None

        n = common.root_len(pattern, leaf["path"])
        mid, root = leaf["path"][n:-1], leaf["path"][:n]
        fallback = spec.get("section_default") or common.title_case(common.split_words(spec["type"]), self.acronyms)
        section = common.lookup(p.get("sections") or {}, key) or common.section_for(mid, root, fallback, self.acronyms)
        label = common.label_for(leaf["path"][-1], section, self.acronyms)
        field = {"key": tgt["fkey"], "type": ftype, "default": default,
                 "ui": {"section": section, "order": None, "label": label,
                        "helperText": common.helper_text(ftype, label, section, leaf["comment"])}}
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

    def drop_removed(self):
        """Removing a key also removes its field when the package already has one."""
        for r in self.removed:
            comp = self.find_comp(r["component"]) if r["component"] else None
            if comp is None or not any(f["key"] == r["field"] for f in comp.get("fields", [])):
                continue
            comp["fields"] = [f for f in comp["fields"] if f["key"] != r["field"]]
            rows = self.diff_rows.setdefault(comp["type"], {"added": [], "changed": [], "package_only": []})
            rows.setdefault("removed", []).append(r["field"])
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
        if d["type"] != f["type"]:   # an incompatible type was asked as a type-conflict question
            self.notes.append(f"{ctype}/{f['key']}: type {f['type']!r} kept from the package (YAML infers {d['type']!r})")
        policy = (self.profile.get("defaults") or {}).get("role_components", "yaml")
        if f.get("valueFrom"):
            if f.get("default") != d["default"] and f["type"] != "secret":
                changes.append(("default (valueFrom field, kept)", f.get("default"), d["default"]))
        elif f["type"] == "secret":
            if f.get("default") not in (None, ""):
                new["default"] = None
                changes.append(("default (secret withheld)", f.get("default"), None))
        elif policy == "yaml" and f.get("default") != d["default"]:
            if not (f.get("options") and d["default"] is not None and d["default"] not in f["options"]):
                # a value outside the options was asked as an enum-value question
                new["default"] = d["default"]
                changes.append(("default", f.get("default"), d["default"]))
        if d.get("options") and f.get("options") and d["options"] != f["options"]:
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
                   and not (f.get("virtual") or f.get("valueFrom"))]
        if missing:
            # every instance of this role renders these from the shared component, yet its YAML does not carry them
            self.questions.append(f"shared component {comp['type']} renders {len(missing)} field(s) this {self.role} "
                                  f"YAML does not carry ({', '.join(missing[:5])}). Every {self.role} instance gets "
                                  "them. Keep them shared, give them a field role, or move them to a role component?")
        for m in items:
            f, d = existing.get(m["field"]["key"]), m["field"]
            if f is None:   # only a key the user agreed to add reaches here (shared-new was asked)
                d = ordered_field(dict(d, version=list(self.target)))
                comp["fields"].append(d)
                self.assign_order(comp, {d["key"]}, True)
                self.touched.add(comp["type"])
                self.diff_rows.setdefault(comp["type"], {"added": [], "changed": [], "package_only": []})["added"].append(d["key"])
                continue
            if f["type"] == "secret" and f.get("default") not in (None, ""):
                # I6 outranks "shared components are report-only": a secret value is removed even here.
                rows = self.diff_rows.setdefault(comp["type"], {"added": [], "changed": [], "package_only": []})
                rows["changed"].append({"key": f["key"], "changes": [("default (secret withheld)", f["default"], None)]})
                f["default"] = None
                self.touched.add(comp["type"])
                continue
            if f["type"] != "secret" and f.get("default") != d["default"]:
                self.shared_diffs.append({"component": comp["type"], "key": f["key"],
                                          "package": f.get("default"), "yaml": d["default"],
                                          "valueFrom": bool(f.get("valueFrom"))})

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
        open(os.path.join(self.args.out_dir, "package.json"), "w", encoding="utf-8").write(text)
        if self.old is not None:
            old_text = open(self.args.package, encoding="utf-8").read()
            diff = "".join(difflib.unified_diff(old_text.splitlines(True), text.splitlines(True),
                                                fromfile=self.args.package, tofile="package.json (generated)", n=2))
            open(os.path.join(self.args.out_dir, "package.diff"), "w", encoding="utf-8").write(diff)
        report = self.report_json()
        json.dump(report, open(os.path.join(self.args.out_dir, "report.json"), "w", encoding="utf-8"),
                  indent=2, ensure_ascii=False, default=str)
        open(os.path.join(self.args.out_dir, "report.md"), "w", encoding="utf-8").write(self.report_md(report))

    def ascii_style(self):
        if self.args.package:
            return "\\u201c" in open(self.args.package, encoding="utf-8").read()
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
