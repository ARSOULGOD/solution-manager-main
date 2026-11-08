#!/usr/bin/env python3
"""Stage 3.5: run a profile's `smoke_test:` against deployed MIs over SSH.

    smoke_test.py --profile references/profiles/file-mesh.yaml \
        --host H --port P --user U --identity KEY --known-hosts FILE \
        --role source=<install dir>:<rendered application.yml> --role sink=<dir>:<yml> \
        --path-map source:/app/external=<host path> --path-map sink:/app/external=<host path> \
        [--accept-unconfirmed] [--out smoke.md]

Paths in the config and the spec are the MI's own (container) paths; --path-map translates them to paths on the
SSH target. Step kinds (all generic): write_text, put_random_file, expect_log, expect_file (same_checksum).
A spec with `status: unconfirmed` is refused unless --accept-unconfirmed is given, and the report then says the
result rests on an unconfirmed assumption. Exit 0 PASS, 1 FAIL, 2 bad input, 3 refused.
"""
import argparse
import hashlib
import os
import secrets
import shlex
import subprocess
import sys
import tempfile
import time

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402


def flat(d, p=""):
    out = {}
    for k, v in (d or {}).items():
        key = f"{p}.{k}" if p else str(k)
        out.update(flat(v, key) if isinstance(v, dict) else {key: v})
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--profile", required=True)
    for o in ("--host", "--user", "--known-hosts"):
        ap.add_argument(o, required=True)
    ap.add_argument("--port", default="22")
    ap.add_argument("--identity")
    ap.add_argument("--role", action="append", default=[], help="role=<install dir>:<rendered yml>")
    ap.add_argument("--path-map", action="append", default=[], help="role:<container prefix>=<target path>")
    ap.add_argument("--accept-unconfirmed", action="store_true")
    ap.add_argument("--out")
    a = ap.parse_args()

    prof = yaml.load(open(a.profile, encoding="utf-8"), Loader=common.StrictLoader)
    spec = prof.get("smoke_test")
    if not spec:
        sys.exit("error: the profile has no smoke_test section; draft one with the user first")
    unconfirmed = spec.get("status") != "confirmed"
    if unconfirmed and not a.accept_unconfirmed:
        print(f"refused: smoke_test is {spec.get('status')!r}. Assumption to confirm with the user:\n  {spec.get('assumption')}")
        sys.exit(3)
    roles = {}
    for r in a.role:
        name, rest = r.split("=", 1)
        d, yml = rest.split(":", 1)
        roles[name] = {"dir": d, "cfg": flat(yaml.safe_load(open(yml)))}
    missing = [r for r in spec.get("needs_roles", []) if r not in roles]
    if missing:
        sys.exit(f"error: smoke test needs role(s) {missing}")
    maps = {}
    for m in a.path_map:
        role, rest = m.split(":", 1)
        prefix, target = rest.split("=", 1)
        maps.setdefault(role, []).append((prefix.rstrip("/"), target.rstrip("/")))

    ssh = ["ssh", "-p", a.port, "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
           "-o", f"UserKnownHostsFile={a.known_hosts}", "-o", "GlobalKnownHostsFile=/dev/null"]
    if a.identity:
        ssh += ["-i", a.identity, "-o", "IdentitiesOnly=yes"]
    dest = f"{a.user}@{a.host}"

    def run(cmd, stdin=None):
        return subprocess.run(ssh + [dest, "--", cmd], input=stdin, capture_output=True, text=True)

    def tpath(role, p):
        for prefix, target in sorted(maps.get(role, []), key=lambda x: -len(x[0])):
            if p == prefix or p.startswith(prefix + "/"):
                return target + p[len(prefix):]
        return p

    vars_ = dict(spec.get("vars") or {})
    fmt = lambda s: str(s).format(**vars_)
    results, ok = [], True
    for st in spec.get("steps", []):
        role, kind, sid = st.get("role"), st["kind"], st["id"]
        t0 = time.time()
        try:
            if kind == "write_text":
                p = tpath(role, str(roles[role]["cfg"][st["path_from"]]))
                r = run(f"mkdir -p {shlex.quote(os.path.dirname(p))} && cat > {shlex.quote(p)}", fmt(st["text"]))
                good, detail = r.returncode == 0, f"wrote {p}" if r.returncode == 0 else r.stderr.strip()
            elif kind == "put_random_file":
                data = secrets.token_bytes(int(st.get("bytes", 1024)))
                vars_["file"] = f"sm-smoke-{secrets.token_hex(4)}.bin"
                vars_["sha256"] = hashlib.sha256(data).hexdigest()
                p = tpath(role, fmt(st["dir"]) + "/" + vars_["file"])
                with tempfile.NamedTemporaryFile(delete=False) as fh:
                    fh.write(data)
                r = subprocess.run(["scp", "-q", "-P", a.port] + ssh[3:] + [fh.name, f"{dest}:{p}"], capture_output=True, text=True)
                os.unlink(fh.name)
                good, detail = r.returncode == 0, f"{vars_['file']} ({len(data)} bytes, sha256 {vars_['sha256'][:12]}…) -> {p}"
            elif kind == "expect_log":
                logf = os.path.join(roles[role]["dir"], "work", str(roles[role]["cfg"].get("logging.file.name") or ""))
                pat, good = fmt(st["pattern"]), False
                while time.time() - t0 < st.get("timeout_s", 30) and not good:
                    good = run(f"grep -F -q {shlex.quote(pat)} {shlex.quote(logf)}").returncode == 0
                    good or time.sleep(2)
                detail = f"'{pat}' in {logf}" + ("" if good else " not found")
            elif kind == "expect_file":
                d = tpath(role, str(roles[role]["cfg"][st["dir_from"]]))
                p, got = f"{d}/{vars_['file']}", ""
                while time.time() - t0 < st.get("timeout_s", 60):
                    got = run(f"sha256sum {shlex.quote(p)} 2>/dev/null | cut -d' ' -f1").stdout.strip()
                    if got:
                        break
                    time.sleep(2)
                good = bool(got) and (got == vars_["sha256"] or not st.get("same_checksum"))
                detail = f"{p}: " + ("absent" if not got else f"sha256 {'matches' if got == vars_['sha256'] else 'DIFFERS'}")
            else:
                good, detail = False, f"unknown step kind {kind!r}"
        except KeyError as e:
            good, detail = False, f"missing config key or role {e}"
        ok &= good
        results.append((sid, "PASS" if good else "FAIL", detail, round(time.time() - t0, 1)))
        if not good:
            break

    verdict = "PASS" if ok else "FAIL"
    L = [f"# Smoke test: {prof['solution']}: {verdict}" + (" (UNCONFIRMED spec)" if unconfirmed else ""), ""]
    if unconfirmed:
        L += [f"> Ran with --accept-unconfirmed. Assumption: {spec.get('assumption')}", ""]
    L += ["| step | verdict | detail | s |", "|---|---|---|---|"] + [f"| {s} | {v} | {d} | {t} |" for s, v, d, t in results]
    text = "\n".join(L) + "\n"
    if a.out:
        open(a.out, "w").write(text)
    print(text)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
