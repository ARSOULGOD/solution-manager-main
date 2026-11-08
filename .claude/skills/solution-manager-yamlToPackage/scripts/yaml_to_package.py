#!/usr/bin/env python3
"""Generic engine: turn one role's YAML into Solution Package fields through a CONFIRMED mapping
profile, merge them into an existing package, validate, and report.

    python3 yaml_to_package.py --profile references/profiles/file-mesh.yaml --role source \
        --yaml application-source-file-v2.yml --target-versions 1,2,3 \
        --package frontend/contracts/packages/file-mesh/package.json --out-dir /tmp/out

Writes to --out-dir: package.json, report.md, report.json and (with --package) package.diff.
It never writes over --package; copying the result into place is a separate, deliberate step.

Exit codes: 0 done (read the report: FLAGGED leaves still need decisions), 1 the result fails
validation, 2 bad input, 3 the profile is a draft, 4 a version decision is needed.
"""
import argparse
import copy
import difflib
import json
import os
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402
import validate_package  # noqa: E402

FIELD_ATTR_ORDER = ["key", "type", "default", "ui", "options", "required", "secret", "discriminator", "when",
                    "pattern", "min", "max", "role", "version"]


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


class Run:
    def __init__(self, args):
        self.args = args
        self.profile = load_profile(args.profile)
        self.role = args.role
        self.mapped, self.flagged, self.excluded, self.coerced, self.withheld = [], [], [], [], []
        self.notes, self.questions, self.shared_diffs, self.ui_diffs = [], [], [], []
        self.diff_rows = {}
        self.acronyms = common.ACRONYMS | {a.lower() for a in self.profile.get("acronyms", [])}

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
        self.target = sorted({int(v) for v in self.args.target_versions.split(",") if v.strip()})
        if not self.target:
            die(4, "--target-versions is required: ask the user which package version(s) this YAML targets")
        if self.args.package:
            self.old = json.load(open(self.args.package, encoding="utf-8"))
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
    def comp_spec(self, ctype):
        return next(c for c in self.profile["components"] if c["type"] == ctype)

    def classify(self):
        p = self.profile
        lists, types = p.get("lists") or {}, p.get("types") or {}
        parsed = common.load_yaml_leaves(
            self.args.yaml,
            list_policy=lambda k: common.lookup(lists, k) or "flag",
            map_leaf=lambda k: common.lookup(types, k) == "map")
        self.leaf_count = len(parsed["leaves"])
        for d in parsed["duplicates"]:
            self.flagged.append({"key": d["key"], "line": d["line"], "reason":
                                 f"duplicate key in the YAML (first at line {d['first_line']}); the parser keeps the first"})
        if parsed["documents"] > 1:
            self.notes.append(f"YAML has {parsed['documents']} documents; leaves from document 2+ are flagged")

        patterns = {c["type"]: c.get("match") or [] for c in p["components"]}
        all_patterns = [(pat, t) for t, pats in patterns.items() for pat in pats]
        seen_norm = {}
        for leaf in parsed["leaves"]:
            key = leaf["key"]
            if leaf["doc"] > 0:
                self.flag(leaf, f"in YAML document {leaf['doc'] + 1}; multi-document YAML needs a decision")
                continue
            ex = next((e for e in p.get("exclude") or [] if common.matches(e["match"], key)), None)
            if ex:
                self.excluded.append({"key": key, "line": leaf["line"], "reason": ex["reason"]})
                continue
            rex = next((r for r in p.get("role_exclusive") or [] if common.matches(r["match"], key)), None)
            if rex and self.role not in rex["roles"]:
                self.flag(leaf, f"profile marks this exclusive to role(s) {rex['roles']}, found in a {self.role} YAML")
                continue
            hit = common.best_match([pat for pat, _ in all_patterns], key)
            if hit is None:
                self.flag(leaf, "no profile component matches this path")
                continue
            ctype = next(t for pat, t in all_patterns if pat == hit)
            spec = self.comp_spec(ctype)
            shared = spec.get("shared") or spec.get("role") in (None, "none")
            if not shared and spec.get("role") != self.role:
                self.flag(leaf, f"matches component {ctype} of role {spec.get('role')!r}, not {self.role!r}")
                continue
            if rex and shared:
                self.flag(leaf, f"role-exclusive ({rex['reason']}) but would land in shared component {ctype}; "
                                f"decide: field role {self.role!r} in {ctype}, a new component, or exclude")
                continue
            field = self.derive(leaf, spec, hit)
            if field is None:
                continue
            norm = (ctype, common.normalise_key(field["key"]))
            if norm in seen_norm:
                self.flag(leaf, f"near-duplicate of {seen_norm[norm]!r} in {ctype} (same key ignoring case and -_)")
                continue
            seen_norm[norm] = field["key"]
            self.mapped.append({"leaf": leaf, "component": ctype, "field": field, "shared": shared})

    def flag(self, leaf, reason):
        self.flagged.append({"key": leaf["key"], "line": leaf["line"], "reason": reason})

    def derive(self, leaf, spec, pattern):
        p, key = self.profile, leaf["key"]
        override = common.lookup(p.get("types") or {}, key)
        options = common.lookup(p.get("enums") or {}, key)
        ftype, note, problem = common.infer_type(leaf, p.get("secret_patterns") or [])
        if problem and override not in ("list", "map"):
            self.flag(leaf, f"{problem}; needs a field type decision (profile `types`/`lists`)")
            return None
        if override:
            ftype = override
        default = common.coerce_default(ftype, leaf["value"]) if leaf["kind"] != "list" else leaf["value"]
        if leaf["kind"] == "scalar-joined":
            default = leaf["value"]
        if options is not None:
            if leaf["value"] is not None and leaf["value"] not in options:
                self.flag(leaf, f"value {leaf['value']!r} is not one of the profile's enum options {options}")
                return None
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

        style = spec.get("key_style") or p.get("key_style") or "full-dotted"
        cp = spec.get("configPath", "")
        fkey = key
        if style == "relative-to-configPath":
            if cp and key.startswith(cp + "."):
                fkey = key[len(cp) + 1:]
            elif cp:
                self.flag(leaf, f"key_style relative but path is not under configPath {cp!r}")
                return None

        n = common.root_len(pattern, leaf["path"])
        mid, root = leaf["path"][n:-1], leaf["path"][:n]
        fallback = spec.get("section_default") or common.title_case(common.split_words(spec["type"]), self.acronyms)
        section = common.lookup(p.get("sections") or {}, key) or common.section_for(mid, root, fallback, self.acronyms)
        label = common.label_for(leaf["path"][-1], section, self.acronyms)
        field = {"key": fkey, "type": ftype, "default": default,
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

    def merge(self):
        p = self.profile
        explicit = p.get("explicit_versions", True)
        preserve_ui = p.get("preserve_reviewed_ui", True)
        by_comp = {}
        for m in self.mapped:
            by_comp.setdefault(m["component"], []).append(m)
        self.touched = set()
        for ctype, items in by_comp.items():
            spec = self.comp_spec(ctype)
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
                    if not (f.get("virtual") or f.get("valueFrom")):
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

    def insert_component(self, comp):
        order = [c["type"] for c in self.profile["components"]]
        pos = len(self.pkg["components"])
        for i, c in enumerate(self.pkg["components"]):
            if c["type"] in order and order.index(c["type"]) > order.index(comp["type"]):
                pos = i
                break
        self.pkg["components"].insert(pos, comp)

    def merge_field(self, f, m, preserve_ui, ctype):
        new, changes = copy.deepcopy(f), []
        d = m["field"]
        if d["type"] != f["type"]:
            compatible = (f["type"] == "enum" and d["type"] in ("string", "enum")) or \
                         (f["type"] == "secret") or {f["type"], d["type"]} == {"string", "duration"}
            if not compatible:
                self.flag(m["leaf"], f"type conflict: package says {f['type']!r}, YAML infers {d['type']!r}; "
                                     "package declaration kept unchanged")
                self.mapped.remove(m)
                return new, []
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
            if f.get("options") and d["default"] is not None and d["default"] not in f["options"]:
                self.flag(m["leaf"], f"YAML value {d['default']!r} is not in the package's options {f['options']}")
            else:
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
        missing = [k for k, f in existing.items() if k not in fed and not (f.get("virtual") or f.get("valueFrom"))]
        if missing:
            # every instance of this role renders these from the shared component, yet its YAML does not carry them
            self.questions.append(f"shared component {comp['type']} renders {len(missing)} field(s) this {self.role} "
                                  f"YAML does not carry ({', '.join(missing[:5])}). Every {self.role} instance gets "
                                  "them. Keep them shared, give them a field role, or move them to a role component?")
        for m in items:
            f, d = existing.get(m["field"]["key"]), m["field"]
            if f is None:
                if self.args.update_shared:
                    d = ordered_field(dict(d, version=list(self.target)))
                    comp["fields"].append(d)
                    self.assign_order(comp, {d["key"]}, True)
                    self.touched.add(comp["type"])
                    self.diff_rows.setdefault(comp["type"], {"added": [], "changed": [], "package_only": []})["added"].append(d["key"])
                else:
                    self.mapped.remove(m)
                    self.flag(m["leaf"], f"not in shared component {comp['type']}; adding it affects every role "
                                         "(re-run with --update-shared once agreed)")
                continue
            if f["type"] != d["type"] and not (f["type"] in ("enum", "secret") or {f["type"], d["type"]} == {"string", "duration"}):
                self.mapped.remove(m)
                self.flag(m["leaf"], f"type conflict with shared {comp['type']}: package {f['type']!r}, YAML {d['type']!r}")
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
        if self.args.provenance == "dev-mode":
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
        schema = self.args.schema or validate_package.find_schema(self.args.package or self.args.out_dir, os.getcwd())
        self.validation = validate_package.validate(self.pkg, schema)
        # every YAML leaf in exactly one bucket
        accounted = len(self.mapped) + len(self.flagged) + len(self.excluded)
        dup_count = sum(1 for f in self.flagged if f["reason"].startswith("duplicate key"))
        self.balance_ok = accounted - dup_count == self.leaf_count

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
            "counts": {"leaves": self.leaf_count, "mapped": len(self.mapped), "flagged": len(self.flagged),
                       "excluded": len(self.excluded), "coerced": len(self.coerced),
                       "secrets_withheld": len(self.withheld), "balanced": self.balance_ok},
            "mapped": [{"key": m["leaf"]["key"], "line": m["leaf"]["line"], "component": m["component"],
                        "field": m["field"]["key"], "type": m["field"]["type"], "shared": m["shared"]}
                       for m in self.mapped],
            "flagged": self.flagged, "excluded": self.excluded, "coerced": self.coerced,
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
             f"{c['leaves']} YAML leaves: **{c['mapped']} mapped**, **{c['flagged']} flagged**, "
             f"**{c['excluded']} excluded**. Every leaf is in exactly one bucket: "
             f"{'yes' if c['balanced'] else 'NO, investigate'}. "
             f"{c['coerced']} coerced, {c['secrets_withheld']} secret default(s) withheld.", ""]
        L += ["### Flagged (needs a decision)", ""]
        L += [f"- `{f['key']}` (line {f['line']}): {f['reason']}" for f in r["flagged"]] or ["- none"]
        L += ["", "### Excluded (by the profile)", ""]
        L += [f"- `{e['key']}` (line {e['line']}): {e['reason']}" for e in r["excluded"]] or ["- none"]
        L += ["", "### Coerced / assumed", ""]
        L += [f"- `{x['key']}` (line {x['line']}): {x['note']}" for x in r["coerced"]] or ["- none"]
        L += ["", "### Secret defaults withheld (I6)", ""]
        L += [f"- `{x['key']}` (line {x['line']})" for x in r["secrets_withheld"]] or ["- none"]
        by = {}
        for m in r["mapped"]:
            by.setdefault(m["component"], []).append(m)
        L += ["", "### Mapped, by component", "", "| component | fields | shared |", "|---|---|---|"]
        L += [f"| {k} | {len(v)} | {'yes' if v[0]['shared'] else 'no'} |" for k, v in by.items()]
        L += ["", "## Diff against the existing package", ""]
        if not r["package"]:
            L.append("New package; no diff.")
        for ct, rows in r["diff"].items():
            L += [f"### {ct}", "", f"- added: {len(rows['added'])}" + (f": {', '.join(rows['added'])}" if rows["added"] else "")]
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
        L += ["", "## Notes", ""] + ([f"- {n}" for n in r["notes"]] or ["- none"])
        grouped = {}
        for f in r["flagged"]:
            grouped.setdefault(f["reason"], []).append(f"`{f['key']}`")
        q = list(r["questions"]) + [f"{', '.join(keys)}: {reason}" for reason, keys in grouped.items()]
        L += ["", "## Open questions", ""] + ([f"{i}. {x}" for i, x in enumerate(q, 1)] or ["none"])
        L += ["", f"authoring: {json.dumps(r['authoring'])}", ""]
        return "\n".join(L)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--profile", required=True)
    ap.add_argument("--role", required=True)
    ap.add_argument("--yaml", required=True)
    ap.add_argument("--target-versions", required=True, help="comma-separated, e.g. 1,2,3 (ask the user)")
    ap.add_argument("--package", help="existing package.json to merge into (never overwritten)")
    ap.add_argument("--applies-to", help="semver range; required when creating a new package")
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--update-shared", action="store_true", help="add new leaves to existing shared components")
    ap.add_argument("--provenance", choices=["keep", "dev-mode"], default="keep")
    ap.add_argument("--schema")
    args = ap.parse_args()
    for flag, path in (("--profile", args.profile), ("--yaml", args.yaml), ("--package", args.package)):
        if path and not os.path.isfile(path):
            die(2, f"{flag} {path}: no such file")
    run = Run(args)
    run.check_inputs()
    run.classify()
    run.merge()
    run.finish()
    run.write()
    c = run.report_json()["counts"]
    print(f"{run.profile['solution']}/{run.role}: leaves={c['leaves']} mapped={c['mapped']} flagged={c['flagged']} "
          f"excluded={c['excluded']} balanced={c['balanced']} validation={'PASS' if run.validation['ok'] else 'FAIL'}")
    print(f"wrote {run.args.out_dir}/report.md")
    sys.exit(0 if run.validation["ok"] else 1)


if __name__ == "__main__":
    main()
