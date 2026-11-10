#!/usr/bin/env python3
"""Render a role's config from a package, and check it round-trips to the input YAML.

    render_config.py render    --package P --profile F --role R --version V [--values draft.json] [--out app.yml]
    render_config.py roundtrip --package P --profile F --role R --version V --yaml input.yml --report report.json
                               [--console-artifact <solution>-<role>.rendered.json] [--out roundtrip.md]

render: the same rules as the console (src/features/designer/yamlConfiguration.ts): path = configPath + key,
virtual fields skipped, nested by '.', unset -> null. Secrets become `${SM_SECRET_<NAME>}` placeholders that the
MI resolves from its environment at start (I6: no secret value is ever written). --values overlays a Designer
draft ({key: value}); a secret in it is refused.

roundtrip: compares the rendered defaults with the input YAML, ignoring comments and key order. Allowed
differences come ONLY from the engine's report.json (removed by you, coerced, withheld secrets, shared
defaults kept) and the profile (preserved components). Anything else is a bug in the package or the profile.
With --console-artifact (written by the generated contract test), the console's own rendering is compared
with this one and any disagreement fails too, so the two renderers cannot drift.
Exit 0 PASS, 1 FAIL, 2 bad input.
"""
import argparse
import json
import os
import re
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402
import decisions  # noqa: E402


def effective(declared, inherited):
    return list(inherited) if declared is None else [v for v in declared if v in inherited]


def secret_placeholder(key):
    return "${SM_SECRET_" + re.sub(r"[^A-Za-z0-9]+", "_", key).strip("_").upper() + "}"


def members(pkg, role, version, only=None, skip=()):
    """Components a `role` instance renders at `version`: its role component(s) and the shared ones.
    `only` (an explicit instance component selection) wins; otherwise `skip` drops components that no YAML
    feeds (the profile's preserved ones, such as an optional env-style component)."""
    pv = pkg["packageVersion"]
    out = []
    for c in pkg["components"]:
        crole = c.get("role") or "none"
        if crole not in (role, "none") or version not in effective(c.get("version"), pv):
            continue
        if (only is not None and c["type"] not in only) or (only is None and c["type"] in skip):
            continue
        cv = effective(c.get("version"), pv)
        out.append((c, [f for f in c.get("fields", []) if version in effective(f.get("version"), cv)]))
    return out


def flat_render(pkg, role, version, values=None, secrets="placeholder", only=None, skip=()):
    """{dotted path: value} for one role at one version."""
    values = values or {}
    flat, origin = {}, {}
    for c, fields in members(pkg, role, version, only, skip):
        cp = c.get("configPath", "")
        for f in fields:
            if f.get("virtual"):
                continue
            path = f"{cp}.{f['key']}" if cp else f["key"]
            if f["type"] == "secret" or f.get("secret"):
                if f["key"] in values:
                    sys.exit(f"error: --values carries a value for secret {f['key']!r}; secrets are never rendered")
                if secrets == "omit":
                    continue
                flat[path] = secret_placeholder(path)
            else:
                flat[path] = values.get(f["key"], f.get("default"))
            origin[path] = c["type"]
    return flat, origin


def nest(flat):
    root = {}
    for path, v in flat.items():
        segs = [s for s in path.split(".") if s]
        node = root
        for s in segs[:-1]:
            node = node.setdefault(s, {})
        node[segs[-1]] = v
    return root


def _load_text(text, list_policy=lambda k: "flag"):
    import tempfile
    with tempfile.NamedTemporaryFile("w", suffix=".yml", delete=False) as fh:
        fh.write(text)
    try:
        return common.load_yaml_leaves(fh.name, list_policy=list_policy)
    finally:
        os.unlink(fh.name)


def flatten_yaml_text(text, list_policy=lambda k: "flag"):
    return {l["key"]: l["value"] for l in _load_text(text, list_policy)["leaves"]}


def stored_answers(profile_path, role):
    """{key: [as, ...]} from the decisions block of the profile, for entries that apply to `role`."""
    with open(profile_path, encoding="utf-8") as fh:
        block = decisions.read_block(fh.read())
    out = {}
    for e in block.get("stored") or []:
        if e.get("key") is not None and e.get("role") in (None, role) and e.get("as") not in out.get(e["key"], []):
            out.setdefault(e["key"], []).append(e.get("as"))
    return out


def explain_stored(key, yv, rendered, as_list, other):
    """(note, extra_keys) when a remembered `stored` answer accounts for the difference, else None."""
    for how in as_list:
        rv = rendered.get(key)
        if how == "index":
            n = len(yv) if isinstance(yv, list) else 0
            items = [f"{key}.{i}" for i in range(n)]
            if n and key not in rendered and all(i in rendered for i in items) \
                    and [rendered[i] for i in items] == yv:
                return f"stored by you as {how}", set(items)
        elif how == "string" and key in rendered and rv == str(yv):
            return f"stored by you as {how}", set()
        elif how == "map" and key in rendered and rv is None and yv == {}:
            return f"stored by you as {how}", set()
        elif how == "join-comma" and isinstance(yv, list) and rv == ",".join(map(str, yv)):
            return f"stored by you as {how}", set()
        elif how == "list" and key in rendered and rv == yv:
            return f"stored by you as {how}", set()
        elif how in ("first", "second") and key in other and rv in other[key] + [yv]:
            return f"stored by you as {how}", set()
        elif how in ("add-option", "yaml-type", "package-type") and key in rendered:
            return f"stored by you as {how}", set()
    return None


def _load_json(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def load_profile(path):
    with open(path, encoding="utf-8") as fh:
        return yaml.load(fh, Loader=common.StrictLoader)


def cmd_render(a):
    pkg = _load_json(a.package)
    values = _load_json(a.values) if a.values else {}
    only = [c for c in a.components.split(",") if c] if a.components else None
    skip = set(((load_profile(a.profile).get("preserve") or {}).get("components") or [])) if a.profile else set()
    flat, origin = flat_render(pkg, a.role, a.version, values, only=only, skip=skip)
    print(f"components rendered: {', '.join(dict.fromkeys(origin.values()))}"
          + (f" (skipped, no YAML source: {', '.join(sorted(skip))}; pass --components to include)" if skip and only is None else ""),
          file=sys.stderr)
    text = ("# Rendered by solution-manager-yamlToPackage. Secrets are ${SM_SECRET_*} placeholders resolved from\n"
            "# the MI's environment at start; no secret value is written here (I6).\n"
            + yaml.safe_dump(nest(flat), sort_keys=False, allow_unicode=True, width=1000))
    if a.out:
        with open(a.out, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"wrote {a.out}: {len(flat)} keys, {sum(1 for v in flat.values() if isinstance(v, str) and v.startswith('${SM_SECRET_'))} secret placeholders")
    else:
        print(text)


def cmd_roundtrip(a):
    pkg = _load_json(a.package)
    prof = load_profile(a.profile)
    rep = _load_json(a.report)
    lists = prof.get("lists") or {}
    with open(a.yaml, encoding="utf-8") as fh:
        yaml_text = fh.read()
    loaded = _load_text(yaml_text, list_policy=lambda k: common.lookup(lists, k) or "flag")
    src = {l["key"]: l["value"] for l in loaded["leaves"]}
    other = {}
    for d in loaded["duplicates"]:
        other.setdefault(d["key"], []).append(d["value"])
    stored = stored_answers(a.profile, a.role)
    kept_in_package = set()
    if rep.get("package") and os.path.isfile(rep["package"]):
        for c in _load_json(rep["package"]).get("components", []):
            kept_in_package |= {f["key"] for f in c.get("fields", [])}
    explained = set()
    rendered, origin = flat_render(pkg, a.role, a.version)
    removed = {x["key"]: x["reason"] for x in rep.get("removed", [])}
    coerced = {x["key"]: x["note"] for x in rep.get("coerced", [])}
    withheld = {x["key"] for x in rep.get("secrets_withheld", [])}
    shared = {d["key"]: d for d in rep.get("shared_differences", [])}
    preserved = set((prof.get("preserve") or {}).get("components") or [])
    ok, allowed, bad = 0, [], []

    for key, yv in src.items():
        if key in removed:
            (bad if key in rendered else allowed).append((key, f"removed by you: {removed[key]}" +
                                                          (" BUT it is rendered" if key in rendered else "")))
            continue
        got = explain_stored(key, yv, rendered, stored[key], other) if key in stored and rendered.get(key) != yv else None
        if got:
            allowed.append((key, got[0]))
            explained |= got[1]
            continue
        if key not in rendered:
            bad.append((key, "in the YAML but not rendered from the package"))
            continue
        rv = rendered[key]
        if isinstance(rv, str) and rv.startswith("${SM_SECRET_"):
            allowed.append((key, "secret: withheld, rendered as a placeholder"))
            continue
        if rv == yv:
            ok += 1
        elif key in coerced and isinstance(yv, str) and yv.lower() in ("true", "false") and rv == (yv.lower() == "true"):
            allowed.append((key, f"coerced: {coerced[key]}"))
        elif key in coerced and isinstance(yv, list) and rv == ",".join(map(str, yv)):
            allowed.append((key, f"coerced: {coerced[key]}"))
        elif key in shared:
            allowed.append((key, f"shared default kept: package {json.dumps(rv)} vs YAML {json.dumps(yv)}"))
        else:
            bad.append((key, f"value differs: rendered {json.dumps(rv)} vs YAML {json.dumps(yv)}"))
    for key in rendered:
        if key not in src and key not in explained:
            if origin[key] in preserved:
                allowed.append((key, f"from preserved component {origin[key]} (no YAML source)"))
            elif key in kept_in_package and key not in removed:
                allowed.append((key, "in the package, not in this YAML (kept)"))
            else:
                bad.append((key, f"rendered from {origin[key]} but absent from the YAML"))

    drift = []
    if a.console_artifact:
        art = _load_json(a.console_artifact)
        if art.get("version") != a.version:
            drift.append(("<artifact>", f"console artifact is for version {art.get('version')}, not {a.version}"))
        console = flatten_yaml_text(art["yaml"])
        mine, _ = flat_render(pkg, a.role, a.version, secrets="omit")
        for k in sorted(set(console) | set(mine)):
            if console.get(k, "<absent>") != mine.get(k, "<absent>"):
                drift.append((k, f"console {json.dumps(console.get(k, '<absent>'))} vs render_config "
                                 f"{json.dumps(mine.get(k, '<absent>'))}"))
        lead = [p for _, _, p in art.get("renderedPaths", []) if p and p.startswith(".")]
        if lead:
            allowed.append(("<renderedPath>", f"{len(lead)} console renderedPath values start with '.' "
                                              "(empty configPath known defect); the YAML renderer drops the empty segment"))

    verdict = "PASS" if not bad and not drift else "FAIL"
    L = [f"# Round-trip: {pkg['solution']} / {a.role} @ v{a.version}: {verdict}", "",
         f"YAML keys: {len(src)} · rendered keys: {len(rendered)} · equal: {ok} · allowed differences: "
         f"{len(allowed)} · unexplained: {len(bad)}" + (f" · console drift: {len(drift)}" if a.console_artifact else ""), ""]
    if bad:
        L += ["## Unexplained differences (bugs in the package or the profile)", ""] + [f"- `{k}`: {r}" for k, r in bad] + [""]
    if drift:
        L += ["## Console renderer vs render_config.py", ""] + [f"- `{k}`: {r}" for k, r in drift] + [""]
    L += ["## Allowed differences (from report.json / the profile)", ""] + ([f"- `{k}`: {r}" for k, r in allowed] or ["- none"])
    text = "\n".join(L) + "\n"
    if a.out:
        with open(a.out, "w", encoding="utf-8") as fh:
            fh.write(text)
    print(text if not a.out else f"{verdict}: wrote {a.out} (unexplained {len(bad)}, drift {len(drift)}, allowed {len(allowed)})")
    sys.exit(0 if verdict == "PASS" else 1)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("render", "roundtrip"):
        p = sub.add_parser(name)
        p.add_argument("--package", required=True)
        p.add_argument("--role", required=True)
        p.add_argument("--version", required=True, type=int)
        p.add_argument("--out")
        if name == "render":
            p.add_argument("--profile", help="drops the profile's preserved (non-YAML) components unless --components")
            p.add_argument("--values")
            p.add_argument("--components", help="comma list: the instance's component selection, rendered exactly")
        else:
            p.add_argument("--profile", required=True)
            p.add_argument("--yaml", required=True)
            p.add_argument("--report", required=True)
            p.add_argument("--console-artifact")
    a = ap.parse_args()
    (cmd_render if a.cmd == "render" else cmd_roundtrip)(a)


if __name__ == "__main__":
    main()
