#!/usr/bin/env python3
"""Inspect one or more YAMLs of a solution that has NO mapping profile yet, and draft one.

    python3 profile_proposer.py --yaml app.yml [--yaml other-role.yml] --solution cache-manager \
        --out /tmp/cache-manager.draft.yaml

Prints (1) the YAML structure, (2) which existing profile/package it most resembles and why,
(3) the draft profile, and (4) numbered open questions. The draft is written with
`status: draft`; the engine refuses to run it until a human has answered the questions and the
profile is saved under references/profiles/<solution>.yaml with `status: confirmed`.

Passing two or more YAMLs (one per role) lets it separate groups common to every file (shared
candidates) from groups found in one file only (role candidates). It never names a role itself.
"""
import argparse
import glob
import json
import os
import re
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402
import decisions  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ENV_RX = re.compile(r"^[A-Z][A-Z0-9_]*$")
CONST_RX = re.compile(r"^[A-Z][A-Z0-9_\-]{2,}$")
OPTIONS_HINT_RX = re.compile(r"(?:one of|options?:|values?:|either)\s*(.+)", re.IGNORECASE)


def kebab(s):
    return "-".join(w.lower() for w in common.split_words(s)) or "component"


def leaves_of(path):
    return common.load_yaml_leaves(path, list_policy=lambda k: "flag")


def key_style(leaves):
    top = {l["path"][0] for l in leaves}
    env = [k for k in top if ENV_RX.match(k)]
    flat = all(len(l["path"]) == 1 for l in leaves)
    if top and flat and len(env) / len(top) >= 0.8:
        return "env-style", f"{len(env)}/{len(top)} top-level keys are UPPER_SNAKE scalars"
    if flat:
        return "flat", "every key is top-level but not UPPER_SNAKE"
    return "nested", f"{len(top)} top-level groups, max depth {max(len(l['path']) for l in leaves)}"


def groups(leaves, style):
    """Candidate component roots: descend through single-child namespaces until the tree branches."""
    if style == "env-style":
        by_tok = {}
        for l in leaves:
            by_tok.setdefault(l["key"].split("_")[0], []).append(l)
        multi = {t: ls for t, ls in by_tok.items() if len(ls) >= 2}
        return {f"{t}_*": ls for t, ls in multi.items()} | (
            {"*": [l for t, ls in by_tok.items() if t not in multi for l in ls]}
            if any(t not in multi for t in by_tok) else {})
    out = {}

    def split(prefix_t, ls):
        children = {}
        for l in ls:
            if len(l["path"]) > len(prefix_t):
                children.setdefault(l["path"][len(prefix_t)], []).append(l)
        leafy = [l for l in ls if len(l["path"]) <= len(prefix_t) + 1]
        if len(children) == 1 and not leafy and len(prefix_t) < 3:
            (k, sub), = children.items()
            return split(prefix_t + (k,), sub)
        out[".".join(prefix_t)] = ls

    by_top = {}
    for l in leaves:
        by_top.setdefault(l["path"][0], []).append(l)
    for top, ls in by_top.items():
        split((top,), ls)
    return out


def resemblance(all_leaves, style, profiles_dir, package_glob):
    keys = {l["key"] for l in all_leaves}
    upper = sum(1 for k in keys if ENV_RX.match(k)) / max(len(keys), 1)
    rows = []
    for pf in sorted(glob.glob(os.path.join(profiles_dir, "*.yaml"))):
        with open(pf, encoding="utf-8") as fh:
            prof = yaml.load(fh, Loader=common.StrictLoader)
        pats = [p for c in prof.get("components", []) for p in c.get("match") or []]
        hit = sum(1 for k in keys if common.best_match(pats, k))
        rows.append((hit / max(len(keys), 1), f"profile {os.path.basename(pf)}",
                     f"its component patterns match {hit}/{len(keys)} leaves; key_style {prof.get('key_style')}"))
    for pk in sorted(glob.glob(package_glob)):
        try:
            with open(pk, encoding="utf-8") as fh:
                pkg = json.load(fh)
        except (OSError, ValueError):
            continue
        fkeys, styles = set(), set()
        for c in pkg.get("components", []):
            cp = c.get("configPath", "")
            for f in c.get("fields", []):
                fkeys.add(f["key"])
                if cp:
                    fkeys.add(f"{cp}.{f['key']}")
                styles.add("env" if ENV_RX.match(f["key"]) else "dotted")
        overlap = len(keys & fkeys)
        names = {k.split(".")[-1].lower() for k in keys}
        fnames = {k.split(".")[-1].lower() for k in fkeys}
        shared_names = sorted(names & fnames)
        pkg_upper = sum(1 for k in fkeys if ENV_RX.match(k)) / max(len(fkeys), 1)
        shape = 1 - abs(pkg_upper - upper)
        score = 0.6 * overlap / max(len(keys), 1) + 0.25 * len(shared_names) / max(len(names), 1) + 0.15 * shape
        rows.append((score, f"package {pk}",
                     f"{overlap}/{len(keys)} leaf keys appear as field keys; {len(shared_names)} leaf names shared "
                     f"({', '.join(shared_names[:6]) or 'none'}); key forms {sorted(styles)} vs this "
                     f"YAML's {style}; roles {sorted({str(c.get('role')) for c in pkg.get('components', [])})}"))
    return sorted(rows, reverse=True)[:4]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--yaml", action="append", required=True)
    ap.add_argument("--solution", help="solution name (kebab-case); asked for if absent")
    ap.add_argument("--profiles-dir", default=os.path.join(HERE, "..", "references", "profiles"))
    ap.add_argument("--packages", help="glob of existing package.json files to compare against "
                                       "(default: <repo>/frontend/contracts/packages/*/package.json if found)")
    ap.add_argument("--out", help="where to write the draft profile (never into references/profiles)")
    args = ap.parse_args()

    package_glob = args.packages
    if not package_glob:
        d = os.getcwd()
        while d != os.path.dirname(d) and not os.path.isdir(os.path.join(d, "frontend/contracts/packages")):
            d = os.path.dirname(d)
        package_glob = os.path.join(d, "frontend/contracts/packages/*/package.json")

    parsed = {p: leaves_of(p) for p in args.yaml}
    questions, out = [], []
    P = out.append
    P("# Profile proposal\n")
    P("## 1. Structure\n")
    per_file_groups, styles = {}, set()
    for path, r in parsed.items():
        ls = r["leaves"]
        style, why = key_style(ls)
        styles.add(style)
        g = groups(ls, style)
        per_file_groups[path] = g
        P(f"### `{path}`\n")
        P(f"- {len(ls)} leaves, {r['documents']} document(s); key style **{style}** ({why})")
        P("- groups: " + ", ".join(f"`{k}` ({len(v)})" for k, v in g.items()))
        lists = [l for l in ls if l["kind"] in ("list", "map")]
        if lists:
            P("- lists/maps (asked in the preview): " + ", ".join(f"`{l['key']}`" for l in lists))
        qb = [l for l in ls if l["kind"] == "scalar" and isinstance(l["value"], str) and l["value"].lower() in ("true", "false")]
        if qb:
            P("- quoted booleans (would be coerced): " + ", ".join(f"`{l['key']}`" for l in qb))
        y11 = [l for l in ls if l.get("raw") and l["raw"].lower() in common.YAML11_BOOL_WORDS and not l["quoted"]]
        if y11:
            P("- unquoted yes/no/on/off kept as strings: " + ", ".join(f"`{l['key']}`" for l in y11))
        if r["duplicates"]:
            P("- duplicate keys: " + ", ".join(f"`{d['key']}` (line {d['line']})" for d in r["duplicates"]))
        P("")

    all_leaves = [l for r in parsed.values() for l in r["leaves"]]
    style = styles.pop() if len(styles) == 1 else "mixed"
    P("## 2. Closest existing profile / package\n")
    rows = resemblance(all_leaves, style, args.profiles_dir, package_glob)
    for score, name, why in rows:
        P(f"- **{name}**: score {score:.2f}; {why}")
    if not rows or rows[0][0] < 0.5:
        P("\nNothing matches closely. Treat this as a new shape; do not borrow another solution's component "
          "boundaries or roles.")
    P("")

    # ------------------------------------------------------------- draft
    files = list(per_file_groups)
    group_keys = {}
    for f, g in per_file_groups.items():
        for k in g:
            group_keys.setdefault(k, set()).add(f)
    comps = []
    for gk, in_files in group_keys.items():
        shared = len(files) > 1 and len(in_files) == len(files)
        only = None if shared or len(files) == 1 else os.path.basename(next(iter(in_files)))
        name = kebab(gk.replace("_*", "").replace("*", "general").replace(".", " "))
        comp = {"type": name, "role": "none" if shared else "TODO", "cardinality": "TODO",
                "configPath": "" if style != "nested" else "TODO", "match": [gk]}
        if shared:
            comp["shared"] = True
        if only:
            comp["_note"] = f"only in {only}: role candidate"
        comps.append(comp)

    enums, secrets = {}, []
    for l in all_leaves:
        if l["kind"] != "scalar":
            continue
        if common.is_secret_name(l["path"][-1]):
            secrets.append(l["key"])
        m = OPTIONS_HINT_RX.search(l["comment"] or "")
        if m:
            opts = [o.strip(" .`'\"") for o in re.split(r"[|,/]| or ", m.group(1)) if o.strip(" .`'\"")]
            if 1 < len(opts) <= 12:
                enums[l["key"]] = opts
        elif isinstance(l["value"], str) and CONST_RX.match(l["value"]) and not common.is_secret_name(l["path"][-1]):
            enums.setdefault(l["key"], f"TODO: options? (value {l['value']!r} looks like a constant)")

    draft = {
        "solution": args.solution or "TODO",
        "status": "draft",
        "roles": "TODO",
        "key_style": {"env-style": "env-style", "nested": "TODO (full-dotted | relative-to-configPath)"}.get(style, "TODO"),
        "preserve_reviewed_ui": True,
        "explicit_versions": True,
        "components": comps,
        "role_exclusive": [],
        "lists": {l["key"]: "TODO (flag | index | join-comma | list)" for l in all_leaves if l["kind"] == "list"},
        "enums": enums,
        "types": {},
        "secret_patterns": [],
        "sections": {},
        "preserve": {"components": [], "fields": [], "metrics": []},
        "defaults": {"role_components": "yaml", "shared_components": "package"},
        "discrepancies": [],
        "smoke_test": {"status": "draft", "assumption": "TODO", "needs_roles": [], "vars": {}, "steps": []},
    }
    text = decisions.write_block(yaml.safe_dump(draft, sort_keys=False, allow_unicode=True, width=110), {})
    P("## 3. Draft profile (status: draft)\n")
    P("```yaml\n" + text + "```\n")
    if secrets:
        P("Secret candidates (by name; defaults will be withheld): " + ", ".join(f"`{s}`" for s in secrets) + "\n")

    # ------------------------------------------------------------- questions
    if not args.solution:
        questions.append("What is the solution's name (kebab-case, as in the package's `solution`)?")
    if len(files) == 1:
        questions.append("Which ROLES does this solution run in? This YAML alone cannot tell; if it has none, the "
                         "role is `none`. If there are several, which role is this file, and can you share the others?")
    else:
        questions.append("Which role does each file represent: " + ", ".join(f"`{os.path.basename(f)}`" for f in files) + "?")
    questions.append("Component boundaries: is each group below one component, or should some be merged/split? "
                     + "; ".join(f"`{c['match'][0]}` -> `{c['type']}`" for c in comps))
    if style == "nested":
        questions.append("Key style: full dotted paths with configPath \"\" (File Mesh style), or keys relative "
                         "to a non-empty configPath per component (Cache style)? If relative, what configPath each?")
    questions.append("Cardinality per component: exactly-one, one-of-many (one per instance, chosen by role), or "
                     "zero-or-more (reusable/shared)?")
    if any(c.get("shared") for c in comps):
        questions.append("Groups present in every file were marked shared (role none). Confirm, or name the ones "
                         "whose values legitimately differ per role.")
    for k, v in enums.items():
        questions.append(f"`{k}`: " + (f"enum with options {v}?" if isinstance(v, list) else v))
    for l in all_leaves:
        if l["kind"] == "list":
            questions.append(f"`{l['key']}` is a list: keep it as a `list` field, join it with ',', one field per item, or remove it?")
    parents = {}
    for l in all_leaves:
        if l["kind"] == "scalar" and len(l["path"]) > 1:
            parents.setdefault(l["path"][:-1], []).append(l)
    for parent, ls in parents.items():
        kinds = {type(l["value"]).__name__ for l in ls}
        if len(ls) >= 2 and len(kinds) == 1 and all(re.match(r"^[a-z0-9]+$", l["path"][-1]) for l in ls) \
                and not any(common.split_words(l["path"][-1])[0].lower() in common.GENERIC_LEAVES for l in ls):
            questions.append(f"`{'.'.join(parent)}` has {len(ls)} same-typed children with data-like keys "
                             f"({', '.join(l['path'][-1] for l in ls)}): a fixed record (one field each) or a "
                             "dynamic map (one `map` field)?")
    questions.append("Is there an existing package.json to merge into, and which package version(s) does this YAML target?")
    questions.append("Stage 3 smoke test (only if you will deploy it over SSH): what should the test send or drop, "
                     "where, and what proves it worked (a log line, an output file with the same checksum, a message)?")
    questions.append("Any non-YAML content to preserve (metrics, actions, virtual/valueFrom/optionsSource fields)?")
    P("## 4. Open questions (answer these before anything is generated)\n")
    P("\n".join(f"{i}. {q}" for i, q in enumerate(questions, 1)))

    print("\n".join(out))
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write("# DRAFT profile proposal: not used by the engine until confirmed.\n" + text)
        print(f"\n(draft written to {args.out})")


if __name__ == "__main__":
    main()
