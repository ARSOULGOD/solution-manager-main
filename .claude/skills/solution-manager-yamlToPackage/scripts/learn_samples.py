"""Learn required/advanced suggestions from the sample Solution Packages."""
import json
import os
from dataclasses import dataclass

import common


def words_of(segment):
    return tuple(w.lower() for w in common.split_words(segment))


@dataclass(frozen=True)
class SampleField:
    ref: str
    words: tuple
    required: "bool | None"
    advanced: bool
    when: "dict | None"


@dataclass(frozen=True)
class Index:
    fields: tuple
    packages: int
    notes: tuple
    dir: "str | None"


@dataclass(frozen=True)
class Suggestion:
    attribute: str
    support: int
    matched: int
    evidence: tuple


def find_samples_dir(start):
    cur = os.path.abspath(start)
    while True:
        cand = os.path.join(cur, "frontend", "contracts", "packages")
        if os.path.isdir(cand):
            return cand
        parent = os.path.dirname(cur)
        if parent == cur:
            return None
        cur = parent


def build_index(samples_dir, exclude_solution):
    fields, notes, seen, packages = [], [], set(), 0
    if samples_dir and os.path.isdir(samples_dir):
        for name in sorted(os.listdir(samples_dir)):
            path = os.path.join(samples_dir, name, "package.json")
            if not os.path.isfile(path):
                continue
            rel = f"{name}/package.json"
            try:
                with open(path, encoding="utf-8") as fh:
                    pkg = json.load(fh)
                comps = pkg.get("components") if isinstance(pkg, dict) else None
                if not isinstance(comps, list):
                    notes.append(f"skipped {rel}: no components list")
                    continue
            except (OSError, ValueError) as exc:
                notes.append(f"skipped {rel}: {exc}")
                continue
            solution = pkg.get("solution")
            if solution == exclude_solution:
                continue
            packages += 1
            for comp in comps:
                if not isinstance(comp, dict):
                    continue
                ctype = comp.get("type")
                for f in comp.get("fields") or []:
                    if not isinstance(f, dict) or f.get("virtual"):
                        continue
                    key = f.get("key")
                    if not isinstance(key, str) or (ctype, key) in seen:
                        continue
                    seen.add((ctype, key))
                    req = f.get("required")
                    when = f.get("when")
                    ui = f.get("ui") if isinstance(f.get("ui"), dict) else {}
                    fields.append(SampleField(
                        ref=f"{solution}/{ctype}/{key}",
                        words=words_of(key.split(".")[-1]),
                        required=req if isinstance(req, bool) else None,
                        advanced=bool(ui.get("advanced")),
                        when=when if isinstance(when, dict) and when else None))
    if packages == 0:
        notes.append("no samples found; no suggestions")
    return Index(tuple(fields), packages, tuple(notes), samples_dir)


def _required_line(f):
    state = {True: "required", False: "optional"}.get(f.required, "not marked")
    line = f"{f.ref}: {state}"
    if f.when:
        line += ", only when " + " and ".join(f"{k} = {v}" for k, v in f.when.items())
    return line


def suggest(index, segment):
    target = words_of(segment)
    hits = [f for f in index.fields if f.words == target]
    n = len(hits)
    out = []
    req = sum(1 for f in hits if f.required is True)
    if n and 2 * req > n:
        out.append(Suggestion("required", req, n, tuple(_required_line(f) for f in hits)))
    adv = sum(1 for f in hits if f.advanced)
    if n and 2 * adv > n:
        out.append(Suggestion("advanced", adv, n, tuple(
            f"{f.ref}: " + ("advanced" if f.advanced else "up front") for f in hits)))
    return out
