#!/usr/bin/env python3
"""Append-only gate for the agent wire (plan 015 T1.2; AG-D13's rule, OR-126 A1).

agent_gateway.proto may only grow: no field, enum value or rpc may be renumbered, renamed, retyped or removed, and
no message, enum or service may disappear. baseline.json records every field (number -> label/type/name), enum
value (number -> name) and rpc (name -> request/response) of the frozen wire. The check parses the .proto with a
small tokenizer (no third-party dependency) and fails on any baseline entry that is absent or different in the
current file. Also recorded and frozen: oneof membership (and the oneof's name) per field, field options such as
json_name, `syntax`, and every file option (go_package, java_package, ...). Additions pass. Intentional growth is recorded with --write-baseline, which refuses to drop entries.

    check_proto_append_only.py                  check the committed proto against baseline.json
    check_proto_append_only.py --write-baseline merge additions into baseline.json (never removes or changes)
    check_proto_append_only.py --self-test      prove the check fires on planted violations
    check_proto_append_only.py --superset       baseline.json must contain every entry of the merge base's baseline.json and,
                                                when the merge base has none, of EVERY committed version in
                                                <merge-base>..HEAD (a hand edit that drops entries is otherwise
                                                unchecked). Reference: $BASELINE_BASE_REF, default origin/UAT. An
                                                unresolvable reference FAILS; --allow-missing-ref opts out explicitly
                                                and is reported as SKIPPED, never as ok.
"""
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROTO = HERE / "agent_gateway.proto"
BASELINE = HERE / "baseline.json"

TOKEN = re.compile(r'"[^"]*"|[A-Za-z_][A-Za-z0-9_.]*|\d+|[{}();=<>,\[\]]')


def tokens(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return TOKEN.findall(text)


def parse(text):
    """Return {"package", "messages": {M: {N: "label type name"}}, "enums": {E: {N: NAME}}, "services": {S: {R: sig}}}."""
    t = tokens(text)
    out = {"syntax": None, "package": None, "options": {}, "messages": {}, "enums": {}, "services": {}}
    i = 0
    stack = []  # (kind, qualified name)

    def skip_statement(j):
        while t[j] != ";":
            j += 1
        return j + 1

    while i < len(t):
        w = t[i]
        if w == "package" and not stack:
            out["package"] = t[i + 1]
            i = skip_statement(i)
        elif w == "syntax" and not stack:
            out["syntax"] = t[i + 2]
            i = skip_statement(i)
        elif w == "option" and not stack:  # file options (go_package, java_package, ...) are wire-adjacent: frozen
            j = skip_statement(i)
            out["options"][t[i + 1]] = " ".join(t[i + 3:j - 1])
            i = j
        elif w in ("import", "option"):
            i = skip_statement(i)
        elif w == "reserved":
            i = skip_statement(i)
        elif w == "message":
            name = ".".join([s[1] for s in stack if s[0] == "message"] + [t[i + 1]])
            out["messages"].setdefault(name, {})
            stack.append(("message", name))
            i += 3
        elif w == "enum":
            name = ".".join([s[1] for s in stack if s[0] == "message"] + [t[i + 1]])
            out["enums"].setdefault(name, {})
            stack.append(("enum", name))
            i += 3
        elif w == "service":
            out["services"].setdefault(t[i + 1], {})
            stack.append(("service", t[i + 1]))
            i += 3
        elif w == "oneof":
            stack.append(("oneof", t[i + 1]))
            i += 3
        elif w == "}":
            stack.pop()
            i += 1
        elif stack and stack[-1][0] == "service" and w == "rpc":
            j = i + 1
            rpc = t[j]
            j += 2
            req = []
            while t[j] != ")":
                req.append(t[j])
                j += 1
            j += 3  # ) returns (
            resp = []
            while t[j] != ")":
                resp.append(t[j])
                j += 1
            j += 1
            out["services"][stack[-1][1]][rpc] = " ".join(req) + " -> " + " ".join(resp)
            if t[j] == "{":  # rpc body with options
                depth = 0
                while True:
                    depth += t[j] == "{"
                    depth -= t[j] == "}"
                    j += 1
                    if depth == 0:
                        break
            else:
                j += 1
            i = j
        elif stack and stack[-1][0] == "enum":
            out["enums"][stack[-1][1]][t[i + 2]] = t[i]
            i = skip_statement(i)
        elif stack and (stack[-1][0] in ("message", "oneof")):
            owner = [s for s in stack if s[0] == "message"][-1][1]
            j = i
            if w == "map":
                j += 6  # map < k , v >
                decl = "map<%s,%s>" % (t[i + 2], t[i + 4])
                label = ""
            else:
                label = ""
                if w in ("repeated", "optional"):
                    label = w
                    j += 1
                decl = t[j]
                j += 1
            name, num = t[j], t[j + 2]
            end = skip_statement(j)
            opts = ""
            if t[j + 3] == "[":  # field options, e.g. [json_name = "x"]: recorded verbatim
                opts = "[" + " ".join(t[j + 4:end - 2]) + "]"
            member = "oneof:%s" % stack[-1][1] if stack[-1][0] == "oneof" else ""
            out["messages"][owner][num] = " ".join(x for x in (member, label, decl, name, opts) if x)
            i = end
        else:
            raise SystemExit("check_proto_append_only: cannot parse near token %r" % w)
    return out


def _plain(entry):
    """An entry without the oneof membership prefix ("oneof:msg Foo foo" -> "Foo foo")."""
    return re.sub(r"^oneof:\S+ ", "", entry) if isinstance(entry, str) else entry


def violations(baseline, current, history=False):
    """history=True compares an OLDER committed baseline (whose format may predate the syntax, option and oneof
    freezes) with a newer one: entries must all still be there, ignoring the newer format fields it never recorded."""
    v = []
    if history:
        for kind in ("messages", "enums", "services"):
            for scope, entries in baseline[kind].items():
                if scope not in current[kind]:
                    v.append("%s %s was removed" % (kind[:-1], scope))
                    continue
                for key, val in entries.items():
                    now = current[kind][scope].get(key)
                    if now is None:
                        v.append("%s %s: entry %s (%s) was removed or renumbered" % (kind[:-1], scope, key, val))
                    elif _plain(now) != _plain(val):
                        v.append("%s %s: entry %s changed %r -> %r" % (kind[:-1], scope, key, val, now))
        return v
    if baseline.get("syntax") != current["syntax"]:
        v.append("syntax changed: %s -> %s" % (baseline.get("syntax"), current["syntax"]))
    if baseline.get("options") != current["options"]:
        v.append("file options changed: %s -> %s (frozen: go_package, java_package etc. alter generated code)"
                 % (baseline.get("options"), current["options"]))
    if baseline["package"] != current["package"]:
        v.append("package changed: %s -> %s" % (baseline["package"], current["package"]))
    for kind in ("messages", "enums", "services"):
        for scope, entries in baseline[kind].items():
            if scope not in current[kind]:
                v.append("%s %s was removed" % (kind[:-1], scope))
                continue
            for key, val in entries.items():
                now = current[kind][scope].get(key)
                if now is None:
                    v.append("%s %s: entry %s (%s) was removed or renumbered" % (kind[:-1], scope, key, val))
                elif now != val:
                    v.append("%s %s: entry %s changed %r -> %r" % (kind[:-1], scope, key, val, now))
    return v


def _git(repo, *a):
    return subprocess.run(["git", *a], cwd=repo or HERE, capture_output=True, text=True)


def baselines_since_merge_base(ref, repo=None):
    """(status, [(label, baseline)]): every baseline.json the branch must still be a superset of.

    status is "ok" or "no-ref" (no merge base with ref: the check cannot run). The list holds the merge base's
    baseline.json if it has one and, when it has none (the file is born on this branch), EVERY committed version
    in <merge-base>..HEAD, so a later commit that drops an entry of an earlier one is caught even before the file
    reaches the reference branch."""
    mb = _git(repo, "merge-base", "HEAD", ref)
    if mb.returncode != 0:
        return "no-ref", []
    mb = mb.stdout.strip()
    rel = _git(repo, "rev-parse", "--show-prefix").stdout.strip() + BASELINE.name
    out = []
    shown = _git(repo, "show", "%s:%s" % (mb, rel))
    if shown.returncode == 0:
        out.append(("merge base " + mb[:8], json.loads(shown.stdout)))
    else:
        commits = _git(repo, "log", "--format=%H", "%s..HEAD" % mb, "--", ":(top)" + rel).stdout.split()
        for c in commits:
            v = _git(repo, "show", "%s:%s" % (c, rel))
            if v.returncode == 0:  # a commit that deleted the file has no version to hold the branch to
                out.append(("commit " + c[:8], json.loads(v.stdout)))
    return "ok", out


def superset_check(allow_missing_ref=False, ref=None, repo=None, head=None):
    """Return (failures, skipped). Every earlier baseline.json must still be contained in this one. An unresolvable
    reference FAILS (fail closed) unless the caller opted out explicitly; it is never reported as a pass."""
    ref = ref or os.environ.get("BASELINE_BASE_REF", "origin/UAT")
    status, versions = baselines_since_merge_base(ref, repo)
    if status != "ok":
        msg = "superset-of-merge-base check cannot run: no merge base with %s (set BASELINE_BASE_REF, or pass --allow-missing-ref)" % ref
        if allow_missing_ref:
            return [], msg
        return [msg], None
    head = head if head is not None else json.loads(BASELINE.read_text())
    bad = []
    for label, base in versions:
        bad += ["%s: %s" % (label, v) for v in violations(base, head, history=True)]
    return bad, None


def check(proto_text, baseline):
    return violations(baseline, parse(proto_text))


def structural_plants(text):
    """Plants that need the file's own anchors: a field moved into/out of a oneof, a removed message, a removed enum."""
    plants = {}
    m = re.search(r"\n  (string|uint64) (\w+) = (\d+);", text)  # a plain top-level-of-message field
    assert m, "no plain field anchor"
    # into a oneof: wrap one plain field in its own oneof
    plants["field moved into a oneof"] = (m.group(0), "\n  oneof moved_in {\n    %s %s = %s;\n  }" % m.groups())
    # out of a oneof: lift the first oneof member out
    o = re.search(r"oneof msg \{\n(\s+)(\w+) (\w+) = (\d+);", text)
    assert o, "no oneof anchor"
    plants["field moved out of a oneof"] = (o.group(0), "string lifted_out = 4242;\n  oneof msg {")
    # removed message / enum: drop a whole small definition
    mm = re.search(r"\nmessage (\w+) \{[^{}]*\}\n", text)
    assert mm, "no flat message anchor"
    plants["removed message"] = (mm.group(0), "\n")
    ee = re.search(r"\nenum (\w+) \{[^{}]*\}\n", text)
    assert ee, "no enum anchor"
    plants["removed enum"] = (ee.group(0), "\n")
    return plants


def all_plants(text):
    plants = {
        "renumbered field": (r"string message_id = 1;", "string message_id = 9;"),
        "renamed field": (r"string message_id = 1;", "string msg_id = 1;"),
        "retyped field": (r"string message_id = 1;", "bytes message_id = 1;"),
        "renumbered enum value": (r"ERROR_CODE_DRAINING = 36;", "ERROR_CODE_DRAINING = 99;"),
        "removed rpc": (r"rpc Enroll(EnrollRequest) returns (EnrollResponse);", ""),
        "label change (optional -> plain)": (r"optional string config_sha256 = 6;", "string config_sha256 = 6;"),
        "map retype": (r"map<string, uint64> last_acked_generation_by_binding = 1;",
                       "map<string, uint32> last_acked_generation_by_binding = 1;"),
        "package change": (r"package solace.sm.agentgateway.v1draft;", "package solace.sm.agentgateway.v1;"),
        "go_package change": (r'github.com/solace/solution-manager/go-agent/internal/wire/pb;pb', "example.com/other;pb"),
        "java_package added": (r'option go_package', 'option java_package = "com.example";\noption go_package'),
        "syntax change": (r'syntax = "proto3";', 'syntax = "proto2";'),
        "json_name field option": (r"string message_id = 1;", 'string message_id = 1 [json_name = "mid"];'),
        "oneof rename": (r"oneof msg {", "oneof body {"),
    }
    plants.update(structural_plants(text))
    return plants


def self_test():
    base = parse(PROTO.read_text())
    n_fields = sum(len(m) for m in base["messages"].values())
    assert n_fields > 100, "parser found only %d fields" % n_fields
    assert not violations(base, base), "baseline must pass against itself"
    text = PROTO.read_text()
    # Planted violations: each must be reported.
    plants = all_plants(text)
    for label, (old, new) in plants.items():
        assert old in text, "plant anchor missing for %s" % label
        bad = text.replace(old, new, 1)
        if not violations(base, parse(bad)):
            print("SELF-TEST FAIL: %s was not reported" % label)
            return 1
        print("  ok  planted %s is reported" % label)
    grown = text.replace("string message_id = 1;", "string message_id = 1;\n  string extra = 3;", 1)
    if violations(base, parse(grown)):
        print("SELF-TEST FAIL: an appended field was rejected")
        return 1
    print("  ok  an appended field passes")
    return superset_self_test(base)


def superset_self_test(base):
    """In a throw-away git repo: (1) the merge base HAS a baseline and the branch drops entries; (2) the merge base has
    NONE, the branch adds one and a later commit drops entries (the real situation until UAT carries the file);
    (3) an unresolvable reference fails closed; (4) the explicit opt-out is reported as skipped, never as a pass."""
    def clone(obj):
        return json.loads(json.dumps(obj))

    enum = next(e for e, vals in base["enums"].items() if len(vals) > 1)
    dropped_entry = clone(base)
    dropped_entry["enums"][enum].pop(sorted(dropped_entry["enums"][enum])[-1])
    dropped_msg = clone(base)
    dropped_msg["messages"].pop(sorted(dropped_msg["messages"])[0])
    grown = clone(base)
    grown["messages"]["PlantedNewMessage"] = {"1": "string x"}

    with tempfile.TemporaryDirectory() as d:
        def git(*a):
            subprocess.run(["git", *a], cwd=d, check=True, capture_output=True)

        def write(obj):
            (Path(d) / BASELINE.name).write_text(json.dumps(obj, indent=1, sort_keys=True))

        git("init", "-q", "-b", "main")
        git("config", "user.email", "t@t")
        git("config", "user.name", "t")
        (Path(d) / "README").write_text("x")
        git("add", ".")
        git("commit", "-q", "-m", "root without a baseline")
        git("branch", "NOBASE")
        write(base)
        git("add", ".")
        git("commit", "-q", "-m", "base")
        git("branch", "WITHBASE")
        git("checkout", "-q", "-b", "work")
        for label, head in (("a baseline with an enum entry removed", dropped_entry), ("a baseline with a message removed", dropped_msg)):
            bad, _ = superset_check(ref="WITHBASE", repo=d, head=head)
            if not bad:
                print("SELF-TEST FAIL: %s was not reported against a merge base that has the file" % label)
                return 1
            print("  ok  planted %s is reported (merge base has the file)" % label)
        for head in (base, grown):
            if superset_check(ref="WITHBASE", repo=d, head=head)[0]:
                print("SELF-TEST FAIL: an unchanged or grown baseline was rejected")
                return 1
        print("  ok  an unchanged or grown baseline passes")
        write(dropped_entry)
        git("add", ".")
        git("commit", "-q", "-m", "drop an entry")
        bad, _ = superset_check(ref="NOBASE", repo=d, head=dropped_entry)
        if not bad:
            print("SELF-TEST FAIL: a later commit that drops an entry of an earlier one was not reported (merge base has no file)")
            return 1
        print("  ok  planted drop of an earlier committed entry is reported (merge base has no file)")
        if superset_check(ref="NOBASE", repo=d, head=grown)[0]:
            print("SELF-TEST FAIL: a superset of every committed version was rejected")
            return 1
        print("  ok  a superset of every committed version passes (merge base has no file)")
        bad, skipped = superset_check(ref="NO-SUCH-REF", repo=d, head=base)
        if not bad or skipped:
            print("SELF-TEST FAIL: an unresolvable reference must fail closed")
            return 1
        print("  ok  an unresolvable reference fails closed")
        bad, skipped = superset_check(ref="NO-SUCH-REF", repo=d, head=base, allow_missing_ref=True)
        if bad or not skipped:
            print("SELF-TEST FAIL: the explicit opt-out must be reported as skipped")
            return 1
        print("  ok  --allow-missing-ref is reported as skipped, not as a pass")
    return 0


def main(argv):
    if "--self-test" in argv:
        return self_test()
    if "--superset" in argv:
        bad, skipped = superset_check(allow_missing_ref="--allow-missing-ref" in argv)
        if bad:
            print("FAIL baseline.json is not a superset of the earlier committed baselines:\n  " + "\n  ".join(bad))
            return 1
        if skipped:
            print("SKIPPED (explicit opt-out): " + skipped)
            return 0
        print("ok   baseline.json contains every entry of the merge base's and of every earlier committed baseline")
        return 0
    current = parse(PROTO.read_text())
    if "--write-baseline" in argv:
        old = json.loads(BASELINE.read_text()) if BASELINE.exists() else None
        if old:
            bad = violations(old, current)
            if bad:
                print("refusing to rewrite the baseline over violations:\n  " + "\n  ".join(bad))
                return 1
        BASELINE.write_text(json.dumps(current, indent=1, sort_keys=True) + "\n")
        print("baseline written: %s" % BASELINE)
        return 0
    baseline = json.loads(BASELINE.read_text())
    bad = violations(baseline, current)
    if bad:
        print("FAIL the agent wire is not append-only:\n  " + "\n  ".join(bad))
        return 1
    print("ok   agent wire is append-only against baseline.json (%d messages, %d enums, %d services)"
          % (len(current["messages"]), len(current["enums"]), len(current["services"])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
