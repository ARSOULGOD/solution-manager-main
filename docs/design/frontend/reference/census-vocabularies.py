#!/usr/bin/env python3
"""Enumerate every state-vocabulary structure in the approved sm-v2 mockup.

WHY THIS EXISTS
---------------
The vocabulary was mis-enumerated three times in a row. Each round a reviewer found tables the
previous round had missed, because each round I searched for the shape I remembered:

  round 2  missed  STATUS_META, CAT_STATE        (looked only in the three files I knew)
  round 3  missed  designer.jsx VersionPill map  (used ABBREVIATED keys t/i/l, so a `tone:`
                   governance.jsx LADDER          grep was structurally blind to it)
                   charts.jsx STATE_LABEL         (label map with tones assigned at the call site)

A count asserted in prose is a claim; this script is a measurement (`L17`). It is deliberately
OVER-inclusive: it reports candidates and the human classifies them. A false positive costs a
line of prose. A false negative ships a screen that cannot render its own states.

USAGE
-----
    python3 docs/design/frontend/reference/census-vocabularies.py            # human-readable
    python3 docs/design/frontend/reference/census-vocabularies.py --json     # machine-readable

Run it after ANY change to the reference, and reconcile STATE-VOCABULARY.md against its output.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

APP = Path(__file__).parent / "sm-v2" / "app"

# The seven chip tones (theme.css:152-158). Any of these appearing as a quoted value is the
# strongest single signal that a structure is a state vocabulary.
TONES = ("ok", "warn", "err", "info", "neutral", "idle", "purple")

TONE_VALUE = re.compile(r'"(%s)"' % "|".join(TONES))
# A tone can be keyed as `tone:` (most tables) or `t:` (designer.jsx's abbreviated map).
TONE_KEY = re.compile(r'\b(?:tone|t)\s*:\s*"(%s)"' % "|".join(TONES))
# A label can be keyed as `label:` or `l:` (abbreviated).
LABEL_KEY = re.compile(r'\b(?:label|l)\s*:\s*"((?:[^"\\]|\\.)*)"')
# One object-literal entry: `key: { ... }`, non-greedy to the matching close on the same nesting.
ENTRY = re.compile(r'"?[A-Za-z_][A-Za-z0-9_-]*"?\s*:\s*\{[^{}]*\}')
# A tone at a JSX call site rather than stored in a table.
INLINE_TONE = re.compile(r'tone=\{?"(%s)"' % "|".join(TONES))
# Declarations, including `const X = {`, `const X = [`, and inner `const map = {`.
DECL = re.compile(r"^(\s*)(?:const|let|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*[\{\[]", re.M)
# An ordered ladder: array entries keyed by `k:` with a label.
LADDER_ENTRY = re.compile(r'\{\s*k\s*:\s*"([A-Za-z0-9_-]+)"')


def declarations(src: str) -> list[tuple[int, str, int]]:
    """(line_no, name, char_offset) for every declaration in file order."""
    return [(src[: m.start()].count("\n") + 1, m.group(2), m.start()) for m in DECL.finditer(src)]


def owning_declaration(decls: list[tuple[int, str, int]], offset: int) -> tuple[int, str]:
    """The nearest declaration at or before `offset`. Returns (line, name)."""
    best = (0, "<file scope>")
    for line, name, start in decls:
        if start <= offset:
            best = (line, name)
        else:
            break
    return best


def census() -> dict:
    findings: dict[str, dict] = {}
    tonefree: dict[str, dict] = {}
    unclassified: list[dict] = []

    for path in sorted(APP.glob("*.jsx")):
        src = path.read_text(encoding="utf-8")
        decls = declarations(src)

        # Iterate over ENTRIES, not lines. `designer.jsx:316` holds three entries on one
        # line, and a per-line scan reported one of them three times with the first label.
        for em in ENTRY.finditer(src):
            body_txt, start = em.group(0), em.start()
            keyed = TONE_KEY.search(body_txt)      # `tone:` or `t:` ONLY — never `dot:`
            labelled = LABEL_KEY.search(body_txt)
            if not (keyed and labelled):
                continue
            line = src[:start].count("\n") + 1
            decl_line, decl_name = owning_declaration(decls, start)
            key = f"{path.name}:{decl_name}"
            findings.setdefault(
                key,
                {"file": path.name, "table": decl_name, "declared_at": decl_line, "entries": []},
            )["entries"].append({
                "file": path.name, "line": line, "declaration": decl_name,
                "declared_at": decl_line, "tone": keyed.group(1), "label": labelled.group(1),
            })

        # Inline tone uses at call sites (`<Chip tone="warn">`) — consumers, not tables.
        for m in INLINE_TONE.finditer(src):
            line = src[: m.start()].count("\n") + 1
            unclassified.append({"file": path.name, "line": line, "tone": m.group(1)})

        # BLIND SPOT FIX: label+icon(+tip) maps with NO tone key at all — `DRIVER` and `LOCUS`
        # (data.jsx:55-60), whose tips are load-bearing security copy. Neither the table sweep
        # nor the label-map sweep could see them, because both keyed on a tone.
        for em in ENTRY.finditer(src):
            body_txt, start = em.group(0), em.start()
            if TONE_KEY.search(body_txt):
                continue
            if not (LABEL_KEY.search(body_txt) and re.search(r'\bicon\s*:\s*"', body_txt)):
                continue
            decl_line, decl_name = owning_declaration(decls, start)
            key = f"{path.name}:{decl_name}"
            tonefree.setdefault(
                key,
                {"file": path.name, "table": decl_name, "declared_at": decl_line, "entries": []},
            )["entries"].append({
                "line": src[:start].count("\n") + 1,
                "label": LABEL_KEY.search(body_txt).group(1),
                "has_tip": bool(re.search(r'\btip\s*:\s*"', body_txt)),
            })

    # Ordered ladders: arrays of {k: "...", label: "..."} — no tones, so the sweep above misses them.
    ladders: list[dict] = []
    for path in sorted(APP.glob("*.jsx")):
        src = path.read_text(encoding="utf-8")
        decls = declarations(src)
        for m in LADDER_ENTRY.finditer(src):
            decl_line, decl_name = owning_declaration(decls, m.start())
            key = f"{path.name}:{decl_name}"
            entry = next((l for l in ladders if l["key"] == key), None)
            if entry is None:
                entry = {"key": key, "file": path.name, "table": decl_name,
                         "declared_at": decl_line, "steps": []}
                ladders.append(entry)
            entry["steps"].append(m.group(1))

    # Label maps with no inline tone (charts.jsx STATE_LABEL): a bare object of string->string
    # whose values read as operator-facing state words. Reported as candidates only.
    label_maps: list[dict] = []
    for path in sorted(APP.glob("*.jsx")):
        src = path.read_text(encoding="utf-8")
        for m in re.finditer(
            r"^const ([A-Z][A-Z0-9_]*(?:LABEL|LABELS|STATE|STATES|META)[A-Z0-9_]*)\s*=\s*\{(.*?)\};",
            src, re.M | re.S,
        ):
            name, body = m.group(1), m.group(2)
            if TONE_KEY.search(body):
                continue  # already captured as a tone table
            label_maps.append({
                "file": path.name,
                "table": name,
                "declared_at": src[: m.start()].count("\n") + 1,
                "keys": re.findall(r'"?([A-Za-z0-9_-]+)"?\s*:', body),
            })

    return {
        "tone_tables": sorted(findings.values(), key=lambda t: (t["file"], t["declared_at"])),
        "tonefree_label_icon_maps": sorted(tonefree.values(), key=lambda t: (t["file"], t["declared_at"])),
        "ordered_ladders": sorted(ladders, key=lambda l: (l["file"], l["declared_at"])),
        "label_maps_without_tones": sorted(label_maps, key=lambda l: (l["file"], l["declared_at"])),
        "inline_tone_uses": unclassified,
    }


def main() -> int:
    result = census()
    if "--json" in sys.argv:
        print(json.dumps(result, indent=2))
        return 0

    tt, ll, lm, inline = (
        result["tone_tables"], result["ordered_ladders"],
        result["label_maps_without_tones"], result["inline_tone_uses"],
    )

    print("TONE/LABEL TABLES")
    for t in tt:
        print(f"  {t['file']}:{t['declared_at']:<5} {t['table']:<16} {len(t['entries'])} entries")
        for e in t["entries"]:
            print(f"      {e['line']:<5} {e['tone']:<8} {e['label']}")
    print(f"\n  -> {len(tt)} tables, {sum(len(t['entries']) for t in tt)} entries, "
          f"{len({t['file'] for t in tt})} files")

    print("\nORDERED LADDERS (position is semantic; no tones)")
    for l in ll:
        print(f"  {l['file']}:{l['declared_at']:<5} {l['table']:<16} {len(l['steps'])} steps: "
              f"{', '.join(l['steps'])}")
    print(f"\n  -> {len(ll)} ladders")

    print("\nLABEL+ICON MAPS WITH NO TONE (the census used to be blind to these)")
    for t in result["tonefree_label_icon_maps"]:
        tips = sum(1 for e in t["entries"] if e["has_tip"])
        print(f"  {t['file']}:{t['declared_at']:<5} {t['table']:<18} {len(t['entries'])} entries, {tips} with tips")
        for e in t["entries"]:
            print(f"      {e['line']:<5} {e['label']}{'  (tip)' if e['has_tip'] else ''}")

    print("\nLABEL MAPS WITH NO INLINE TONE (candidates — classify by hand)")
    for l in lm:
        print(f"  {l['file']}:{l['declared_at']:<5} {l['table']:<16} keys: {', '.join(l['keys'])}")

    print(f"\nINLINE TONE USES (consumers, not tables): {len(inline)} sites")
    files = sorted({f"{u['file']}" for u in inline})
    print(f"  in {len(files)} files: {', '.join(files)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
