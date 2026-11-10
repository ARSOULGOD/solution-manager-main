"""Render the preview model (the content of preview.json) as markdown, for people.

render(model, previous) is pure: the same model always gives the same text. With `previous` (the last
preview.json of the same solution and role) the text starts with what changed since then. The model holds
no secret value (I6), so neither does the text.
"""
import json
import os

ROW_COLUMNS = ("type", "default", "section", "pos", "shown", "required", "notes")
MOVED_LISTED = 10   # more rows than this changing position alone are summarised per component


def cell(value):
    """A table cell: pipes escaped, one line."""
    return str(value).replace("|", "\\|").replace("\n", " ")


def plural(n, word):
    return f"{n} {word}" + ("" if n == 1 else "s")


def as_list(values):
    return "[" + ",".join(str(v) for v in values or []) + "]"


def qnum(model):
    return {q["id"]: q["n"] for q in model["questions"]}


def rows_of(model):
    return {(r["component"], r["field"]): r for c in model["components"] for r in c["rows"]}


def plain(row):
    """A row's columns as plain values, for comparing two previews."""
    return {"type": row["type"], "default": row["default_display"], "section": row["section"], "pos": row["order"],
            "shown": row["shown"], "required": "yes" if row["required"] else "no",
            "notes": "; ".join(row["notes"]) or "-"}


# ---------------------------------------------------------------- the changes since the last preview
def changes(model, previous):
    now, before = rows_of(model), rows_of(previous)
    L = ["## Changes since the last preview", ""]
    changed, moved = [], {}
    for ident, row in now.items():
        old = before.get(ident)
        name = f"{ident[0]}/{ident[1]}"
        if old is None:
            changed.append(f"{name}: added")
            continue
        a, b = plain(old), plain(row)
        cols = [col for col in ROW_COLUMNS if a[col] != b[col]]
        if cols == ["pos"]:
            moved.setdefault(ident[0], []).append(f"{name}: pos {a['pos']} → {b['pos']}")
        changed += [f"{name}: {col} {a[col]} → {b[col]}" for col in cols if cols != ["pos"]]
    changed += [f"{c}/{f}: no longer placed" for c, f in before if (c, f) not in now]
    if sum(len(v) for v in moved.values()) > MOVED_LISTED:   # a renumbering (the restyle): one line per component
        changed += [f"{c}: pos renumbered on {len(v)} rows" for c, v in moved.items()]
    else:
        changed += [x for v in moved.values() for x in v]
    old_ids = [q["id"] for q in previous.get("questions") or []]
    new_ids = [q["id"] for q in model["questions"]]
    resolved = [i for i in old_ids if i not in new_ids]
    raised = [i for i in new_ids if i not in old_ids]
    if not (changed or resolved or raised or model["counts"] != previous.get("counts")):
        return "\n".join(L + ["Nothing changed.", ""])
    c, p = model["counts"], previous.get("counts") or {}
    L.append("Keys: " + " · ".join(f"{k} {p.get(k)} → {c[k]}" if p.get(k) != c[k] else f"{k} {c[k]}"
                                   for k in ("placed", "removed", "open")))
    L += ["", f"Row changes ({len(changed)}):"] + ([f"- {x}" for x in changed] or ["- none"])
    L += ["", f"Questions resolved ({len(resolved)}):"] + ([f"- `{i}`" for i in resolved] or ["- none"])
    L += ["", f"New questions ({len(raised)}):"] + ([f"- `{i}` (Q{qnum(model)[i]})" for i in raised] or ["- none"])
    still = len(model["questions"])
    L += ["", f"Still open: {still} question(s), listed below." if still else "Nothing is open.", ""]
    return "\n".join(L)


# ---------------------------------------------------------------- the preview itself
def header(model):
    c, s = model["counts"], model["samples"] or {}
    solution = model["solution"]
    roles = model["roles"]
    mine = ", ".join(f"{x['type']} ({x['cardinality']})" for x in roles["this_role"]) or "no component yet"
    pkg = f"`{model['package']}`" if model["package"] else "none yet (this creates one)"
    target = as_list(model["target_versions"])
    short = sorted({f"{r['component']}/{r['field']}" for comp in model["components"] for r in comp["rows"]
                    if not set(model["target_versions"]) <= set(r.get("versions") or [])})
    versions = f"every field it feeds carries {target}" if not short else \
        f"{len(short)} field(s) it feeds lack some of them: {', '.join(short)}"
    if s.get("dir"):
        samples = f"`{s['dir']}`: {s.get('packages', 0)} packages, {s.get('fields', 0)} distinct fields " \
                  f"({solution} itself not used)"
    else:
        samples = "none found, so nothing is suggested"
    return [
        f"## {solution} / {model['role']}: PREVIEW (nothing created yet)", "",
        f"- YAML: `{os.path.basename(model['yaml'])}`, {c['keys']} keys · profile: "
        f"`{os.path.basename(model['profile'])}` (confirmed)",
        f"- Package: {pkg} · packageVersion {as_list(model['packageVersion'])} · appliesTo "
        f"{json.dumps(model['appliesTo'])}",
        f"- Roles: {model['role']} → {mine} · shared (none): {', '.join(roles['shared']) or 'none'}",
        f"- Versions: this YAML targets {target}; {versions}",
        f"- Samples: {samples}",
        f"- Keys: {c['keys']} → {c['placed']} placed · {c['removed']} removed by you · {c['open']} open",
    ]


def suggested(row, attribute, n):
    """How an open, accepted or rejected suggestion shows in its column."""
    hint = row["suggested"].get(attribute)
    if attribute == "required":
        base = "yes" if row["required"] else "no"
        word = "yes"
    else:
        base = row["shown"]
        word = "advanced"
    q = n.get(f"suggest:{row['component']}/{row['field']}/{attribute}")
    if hint is True and q is not None:
        return f"{base} → suggested {word} (Q{q})"
    if hint is False:
        return f"{base} (suggestion rejected)"
    return base


def table(model, comp):
    n = qnum(model)
    head = f"### {comp['type']} (shared)" if comp["shared"] else f"### {comp['type']} (role {comp['role']})"
    L = [f"{head}: {len(comp['rows'])} fields", "",
         "| YAML key (line) | type | default | section · pos | shown | required | notes |",
         "|---|---|---|---|---|---|---|"]
    for r in comp["rows"]:
        key = f"`{r['key']}`" if r["field"] == r["key"] else f"`{r['key']}` → `{r['field']}`"
        notes = list(r["notes"])
        if comp["shared"] and r.get("role") not in (None, "none"):
            notes.append(f"for role {r['role']} only")
        line = f" (L{r['line']})" if r.get("line") is not None else ""
        L.append("| " + " | ".join(cell(x) for x in (
            key + line, r["type"], r["default_display"], f"{r['section']} · {r['order']}",
            suggested(r, "advanced", n), suggested(r, "required", n), "; ".join(notes) or "")) + " |")
    return L + [""]


def question(q):
    where = f"`{q['key']}`" if q["key"] is not None else "(whole package)"
    if q.get("line") is not None:
        where += f" (L{q['line']})"
    L = [f"{q['n']}. **{q['kind']}** {where}: {q['text']}",
         f"   Choices: {' · '.join(q['choices'])} · answer id `{q['id']}`"]
    ctx = q.get("context") or {}
    if q["kind"] == "suggest" and ctx.get("evidence"):
        L.append(f"   Evidence ({ctx.get('support')} of {ctx.get('matched')} matching sample fields):")
        L += [f"   - {e}" for e in ctx["evidence"]]
    if q["kind"] == "restyle" and ctx.get("components"):
        L.append("   Per component (positions changed · stock sentences removed · custom kept):")
        L += [f"   - {t}: {k['order_changed']} · {k['stock_removed']} · {k['custom_kept']}"
              for t, k in ctx["components"].items()]
        if ctx.get("preserved"):
            L.append(f"   - restyled too, though the profile preserves them: {', '.join(ctx['preserved'])}")
    return L


def information(model):
    """The non-blocking lines. What a table already shows (a shared default the YAML differs on, a secret flag on
    a field with a row) is counted in the summary, not listed again."""
    info = model["info"]
    shown = {f"{r['component']}/{r['field']}" for c in model["components"] for r in c["rows"]}

    def count(prefix):
        return sum(1 for x in info if x.startswith(prefix))
    summary = [f"{plural(count('shared default differs'), 'shared default')} differ (package kept; "
               "\"YAML says\" in the tables)",
               f"{plural(count('coerced: '), 'value')} coerced",
               f"secret: true added to {plural(count('secret: true added: '), 'field')}",
               f"{plural(count('in the package, not in this YAML'), 'package field')} not in this YAML (kept)"]
    L = ["### For your information", "", "- " + " · ".join(summary)]
    for x in info:
        if x.startswith("shared default differs"):
            continue
        if x.startswith("secret: true added: ") and x[len("secret: true added: "):] in shown:
            continue
        L.append(f"- {x}")
    for r in model["removed"]:
        line = f" (L{r['line']})" if r.get("line") is not None else " (package field)"
        drops = f"; drops {', '.join(r['component'] + '/' + k for k in r['field_keys'])}" \
            if r.get("component") and r.get("field_keys") else ""
        L.append(f"- removed by you: `{r['key']}`{line}: {r['reason']}{drops}")
    L += [f"- samples: {x}" for x in (model["samples"] or {}).get("notes") or []]
    return L + [""]


def body(model):
    L = header(model) + [""]
    for comp in model["components"]:
        L += table(model, comp)
    L += ["### Questions (answer by number)", ""]
    for q in model["questions"]:
        L += question(q)
    if not model["questions"]:
        L.append("None: everything is answered. Create package.json? (yes/no)")
    L.append("")
    L += information(model)
    if model["profile_changes"]:
        L += ['### Profile changes saved on "create"', "", "```diff"] + model["profile_changes"] + ["```", ""]
    else:
        L += ['### Profile changes saved on "create": none yet', ""]
    return "\n".join(L)


def render(model, previous=None):
    """The preview as markdown; with `previous`, it starts with the changes since that preview."""
    text = body(model)
    return text if previous is None else changes(model, previous) + "\n" + text
