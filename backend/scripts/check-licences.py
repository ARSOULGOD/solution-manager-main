#!/usr/bin/env python3
"""I8 licence gate, second check (REQ-014, plan 010 T0.4b): build plugins, and the lists that must agree.

WHY THIS EXISTS. backend/pom.xml binds license-maven-plugin to `verify`; it gates every dependency a module
resolves, in every scope (see the comment above the plugin in the pom). Measured: it cannot see BUILD PLUGINS and
their own dependencies, which REQ-014 puts in scope ("ships or builds with"). This script covers that part. It is
NOT bound to Maven: no exec-capable plugin is available offline, so it runs as a command, like
check-frozen-migrations.py.

WHAT IT CHECKS (exit 0 only when every one holds; exit 1 and a message per problem otherwise):
  0. No module pom declares a system-scope dependency (the Maven gate cannot see one: measured).
  1. The three copies of the allow-list agree: licence-allowlist.txt, backend/pom.xml's <includedLicenses> (both
     executions), and licence-third-party.ftl's allowList. The exception names are the four known ones.
  2. licence-exceptions.txt is well formed: exact `groupId--artifactId--version=Name` lines, no duplicates, no
     wildcard, a known Name.
  3. Every artifact in the build-plugin realm (`mvn -o dependency:resolve-plugins`, offline) either has exactly
     one licence that maps, by EXACT string, to an allow-list name, or several licences that all map to one, or
     is an exact coordinate+version line in licence-exceptions.txt. An unknown licence, an unmapped name, several
     licences with one off the list, and a version that differs from the approved one all fail. The licence of a
     plugin-realm artifact is read from its POM in the local repository, walking <parent> until a <licenses>
     element is found.
  4. Every `Owner-approved (build-tool-only)` line is still in the realm (a stale approval fails: the realm
     changed and nobody re-audited).
  5. org.junit.platform:junit-platform-launcher is resolved by Surefire itself, so it is in no tree: its approved
     version must equal the approved junit-platform-engine version.
  6. The proprietary licence block (OR-68): the root pom declares exactly one <license>, named `Proprietary - Solace`,
     with no <url>. Without it the reactor modules inherit spring-boot-starter-parent's Apache-2.0 and pass the
     Maven gate as if Solace's own code were open source (measured: removing the block stayed green).
  7. Every plugin parameter that could weaken the Maven gate is pinned in the pom to its expected value, the
     executions override none of them, and licence-artifact-filters.txt has no filter line. A -Dlicense.* flag
     beats an unpinned parameter (measured, see backend/README.md).
  8. licence-allowlist.txt has no `#` line containing a pipe: the plugin does not treat `#` as a comment, it splits
     every line on the pipe and registers a merge (measured from the plugin's bytecode).
  9. An owner WAIVER (an artifact that states no licence) is recorded under its own approval name, accepted only
     when the artifact's POM chain still declares no licence, and each waiver is named in the OK line.

OUT OF SCOPE BY OWNER RULING (2026-10-05): the Maven installation, the JDK, and the never-run site and deploy
plugins (OUT_OF_SCOPE below, by exact coordinate+version; their POMs are absent from the local repository).

Usage: check-licences.py [--backend-dir DIR] [--realm-file FILE] [--repo DIR]
  --realm-file  read `dependency:resolve-plugins` output from FILE instead of running Maven (self-test)
  --repo        local Maven repository (default $M2_REPO, else ~/.m2/repository)
"""
import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

SIX = ["Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "PostgreSQL"]
FIRST_PARTY = "Solace first-party"
SHIPPED = "Owner-approved (shipped)"
TEST = "Owner-approved (test-only)"
BUILD = "Owner-approved (build-tool-only)"
# A waiver is not a licence: it records that the owner accepted an artifact that STATES NO licence. It is a
# distinct name so the two cannot be confused, and no Maven execution whitelists it.
WAIVER = "Owner waiver (build-tool-only, no licence stated)"
NAMES = {SHIPPED, TEST, BUILD, WAIVER, FIRST_PARTY}
PROPRIETARY = "Proprietary - Solace"
# The plugin parameters pinned in backend/pom.xml, by expected text. A -Dlicense.<param> flag beats a parameter
# the pom leaves unset.
PINNED = {
    "failOnBlacklist": "true", "failOnMissing": "true", "force": "true", "skipAddThirdParty": "false",
    "acceptPomPackaging": "false", "includeTransitiveDependencies": "true", "includeOptional": "true",
    "useRepositoryMissingFiles": "false", "useMissingFile": "false", "deployMissingFile": "false",
    "excludeTransitiveDependencies": "false",
    "excludedGroups": "(?!)", "excludedArtifacts": "(?!)", "excludedTypes": "__none__",
    "includedGroups": ".*", "includedArtifacts": ".*", "includedTypes": "jar,test-jar,pom,bundle",
    "artifactFiltersUrl": "file://${project.basedir}/../licence-artifact-filters.txt",
    "licenseMergesUrl": "file://${project.basedir}/../licence-allowlist.txt",
    "overrideUrl": "file://${project.basedir}/../licence-exceptions.txt",
    "fileTemplate": "${project.basedir}/../licence-third-party.ftl",
}
EXECUTION_MAY_SET = {"includedScopes", "excludedScopes", "includedLicenses", "outputDirectory", "thirdPartyFilename"}
OUT_OF_SCOPE = {  # owner ruling 2026-10-05: never-run plugins, outside "builds with"
    "org.apache.maven.plugins:maven-site-plugin:3.12.1",
    "org.apache.maven.plugins:maven-deploy-plugin:3.1.4",
}
NS = "{http://maven.apache.org/POM/4.0.0}"
errors = []


def err(msg):
    errors.append(msg)


def read_lines(path):
    with open(path, encoding="utf-8") as f:
        return f.read().split("\n")


def parse_allowlist(path):
    """-> (canonical names in file order, variant -> canonical)."""
    canon, mapping = [], {}
    for n, line in enumerate(read_lines(path), 1):
        s = line.strip()
        if s.startswith("#") and "|" in s:
            err(f"licence-allowlist.txt:{n}: a # line contains a pipe. The plugin does not treat # as a comment: "
                "it splits the line on the pipe and registers a merge")
        if not s or s.startswith("#"):
            continue
        parts = [p.strip() for p in s.split("|")]
        canon.append(parts[0])
        mapping[parts[0]] = parts[0]
        for v in parts[1:]:
            if v in mapping and mapping[v] != parts[0]:
                err(f"licence-allowlist.txt: '{v}' is mapped to two names")
            mapping[v] = parts[0]
    return canon, mapping


def parse_exceptions(path):
    """-> {groupId--artifactId--version: Name}"""
    out = {}
    for n, raw in enumerate(read_lines(path), 1):
        s = raw.strip()
        if not s or s.startswith("#"):
            continue
        if "=" not in s:
            err(f"licence-exceptions.txt:{n}: not 'key=Name': {s!r}")
            continue
        key, name = (x.strip() for x in s.split("=", 1))
        parts = key.split("--")
        if len(parts) != 3 or not all(parts) or re.search(r"[\s*?\[\]()^$|\\]", key):
            err(f"licence-exceptions.txt:{n}: key must be exact groupId--artifactId--version, got {key!r}")
        if key in out:
            err(f"licence-exceptions.txt:{n}: duplicate key {key}")
        if name not in NAMES:
            err(f"licence-exceptions.txt:{n}: unknown name {name!r}; allowed: {sorted(NAMES)}")
        out[key] = name
    return out


def check_lists_agree(backend, canon):
    expect_six = [c for c in canon if c != FIRST_PARTY]
    if expect_six != SIX:
        err(f"licence-allowlist.txt canonical names {expect_six} differ from REQ-014's {SIX}")
    root = ET.parse(os.path.join(backend, "pom.xml")).getroot()
    found = 0
    for ex in root.iter(NS + "execution"):
        eid = ex.findtext(NS + "id")
        if eid not in ("licence-gate-shipped", "licence-gate-test"):
            continue
        found += 1
        names = [(x.text or "").strip() for x in ex.iter(NS + "includedLicense")]
        want = SIX + [SHIPPED, FIRST_PARTY] + ([TEST] if eid.endswith("test") else [])
        if names != want:
            err(f"pom.xml execution {eid}: includedLicenses {names} differ from {want}")
    if found != 2:
        err(f"pom.xml: expected the 2 licence-gate executions, found {found}")
    for dirpath, dirs, files in os.walk(backend):
        dirs[:] = [d for d in dirs if d not in ("target", "spikes", "node_modules", ".git")]
        if "pom.xml" in files:
            body = open(os.path.join(dirpath, "pom.xml"), encoding="utf-8").read()
            body = re.sub(r"<!--.*?-->", "", body, flags=re.S)
            if re.search(r"<scope>\s*system\s*</scope>", body):
                err(f"{os.path.relpath(os.path.join(dirpath, 'pom.xml'), backend)}: <scope>system</scope> is "
                    "refused: a jar named by file path has no POM, so license-maven-plugin cannot see its licence")
    check_proprietary_block(root)
    check_pins(root, backend)
    ftl = "\n".join(read_lines(os.path.join(backend, "licence-third-party.ftl")))
    m = re.search(r"<#assign allowList = \[(.*?)\]>", ftl)
    tpl = re.findall(r'"([^"]*)"', m.group(1)) if m else None
    if tpl != SIX:
        err(f"licence-third-party.ftl allowList {tpl} differs from {SIX}")


def check_proprietary_block(root):
    lics = root.find(NS + "licenses")
    found = [] if lics is None else list(lics.findall(NS + "license"))
    if len(found) != 1:
        err(f"pom.xml: the root pom must declare exactly one <license> ({PROPRIETARY!r}, OR-68), found {len(found)}: "
            "without it every module inherits the Spring Boot parent's Apache-2.0")
        return
    if (found[0].findtext(NS + "name") or "").strip() != PROPRIETARY:
        err(f"pom.xml: the root pom's <license> name must be exactly {PROPRIETARY!r}")
    if found[0].find(NS + "url") is not None:
        err("pom.xml: the proprietary <license> must have no <url>: no licence-text URL exists, and none is invented")


def check_pins(root, backend):
    plugin = next((p for p in root.iter(NS + "plugin")
                   if p.findtext(NS + "artifactId") == "license-maven-plugin"), None)
    if plugin is None:
        err("pom.xml: license-maven-plugin is not declared")
        return
    cfg = plugin.find(NS + "configuration")
    have = {} if cfg is None else {c.tag.replace(NS, ""): (c.text or "").strip() for c in cfg}
    for k, want in PINNED.items():
        if have.get(k) != want:
            err(f"pom.xml: license-maven-plugin <{k}> must be pinned to {want!r} (a -Dlicense.{k} flag beats an "
                f"unpinned parameter), found {have.get(k)!r}")
    for ex in plugin.iter(NS + "execution"):
        ecfg = ex.find(NS + "configuration")
        for c in ([] if ecfg is None else list(ecfg)):
            tag = c.tag.replace(NS, "")
            if tag not in EXECUTION_MAY_SET:
                err(f"pom.xml: execution {ex.findtext(NS + 'id')} sets <{tag}>, which could weaken a pinned "
                    "plugin-level parameter")
    ffile = os.path.join(backend, "licence-artifact-filters.txt")
    if not os.path.isfile(ffile):
        err("licence-artifact-filters.txt is missing (pom.xml pins artifactFiltersUrl to it)")
    else:
        body = [l for l in read_lines(ffile) if l.strip() and not l.strip().startswith("#")]
        if body:
            err(f"licence-artifact-filters.txt must hold no filter line (it would drop artifacts from the gate): {body}")


def local(tag):
    return tag.rsplit("}", 1)[-1]


def child(el, name):
    """First child by local name: some old POMs in the realm declare no XML namespace."""
    return next((c for c in el if local(c.tag) == name), None)


def text(el, name):
    c = child(el, name)
    return (c.text or "").strip() if c is not None else ""


def pom_licences(repo, g, a, v, depth=0):
    """Licence names declared by g:a:v, walking <parent>; None if a POM in the chain is missing."""
    path = os.path.join(repo, *g.split("."), a, v, f"{a}-{v}.pom")
    if depth > 12 or not os.path.isfile(path):
        return None
    root = ET.parse(path).getroot()
    lic = child(root, "licenses")
    if lic is not None:
        return [text(x, "name") for x in lic if local(x.tag) == "license"]
    parent = child(root, "parent")
    if parent is None:
        return []
    return pom_licences(repo, text(parent, "groupId"), text(parent, "artifactId"), text(parent, "version"),
                        depth + 1)


REALM_RE = re.compile(r"^\[INFO\]\s+([^:\s]+):([^:\s]+):(?:jar|maven-plugin|pom):([^:\s]+)\s*$")


def realm_from(text):
    return sorted({m.groups() for m in (REALM_RE.match(l) for l in text.split("\n")) if m})


def classify(repo, g, a, v, mapping, exceptions):
    key = f"{g}--{a}--{v}"
    if key in exceptions:
        if exceptions[key] != WAIVER:
            return None
        names = pom_licences(repo, g, a, v)
        if names is None:
            return f"{g}:{a}:{v}: holds an owner waiver but its POM is not in {repo}, so 'states no licence' is unproven"
        if names:
            return (f"{g}:{a}:{v}: holds an owner WAIVER (for an artifact that states no licence) but now declares "
                    f"{names}: re-audit it and record it as a licence approval, not a waiver")
        return None
    if any(k.startswith(f"{g}--{a}--") for k in exceptions):
        return (f"{g}:{a}:{v} resolves to a version licence-exceptions.txt does not approve "
                f"(approved: {sorted(k.split('--')[2] for k in exceptions if k.startswith(f'{g}--{a}--'))})")
    names = pom_licences(repo, g, a, v)
    if names is None:
        return f"{g}:{a}:{v}: POM (or a parent POM) not found in {repo}: licence unknown"
    if not names:
        return (f"{g}:{a}:{v}: declares NO licence and is not an exact exception: unknown fails. Owner waiver "
                f"missing: it needs a line with the approval name '{WAIVER}'")
    bad = [n for n in names if mapping.get(n) not in SIX]
    if bad:
        return (f"{g}:{a}:{v}: licence {bad} is off the allow-list or not an exact mapped name"
                f"{' (it declares ' + str(names) + ')' if len(names) > 1 else ''}, and is not an exact exception")
    return None


def main():
    ap = argparse.ArgumentParser()
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--backend-dir", default=os.path.dirname(here))
    ap.add_argument("--realm-file")
    ap.add_argument("--repo", default=os.environ.get("M2_REPO") or os.path.expanduser("~/.m2/repository"))
    args = ap.parse_args()
    backend = args.backend_dir

    canon, mapping = parse_allowlist(os.path.join(backend, "licence-allowlist.txt"))
    exceptions = parse_exceptions(os.path.join(backend, "licence-exceptions.txt"))
    check_lists_agree(backend, canon)

    if args.realm_file:
        text = open(args.realm_file, encoding="utf-8").read()
    else:
        p = subprocess.run(["mvn", "-o", "-f", os.path.join(backend, "pom.xml"), "dependency:resolve-plugins"],
                           capture_output=True, text=True)
        if p.returncode != 0:
            print(p.stdout[-3000:] + p.stderr[-2000:])
            err("mvn -o dependency:resolve-plugins failed")
        text = p.stdout
    realm = realm_from(text)
    if not realm and not errors:
        err("the build-plugin realm is empty: the output was not parsed")
    for g, a, v in realm:
        if f"{g}:{a}:{v}" in OUT_OF_SCOPE:
            continue
        problem = classify(args.repo, g, a, v, mapping, exceptions)
        if problem:
            err(problem)

    in_realm = {f"{g}--{a}--{v}" for g, a, v in realm}
    for key, name in sorted(exceptions.items()):
        if name in (BUILD, WAIVER) and key not in in_realm:
            kind = "an owner waiver" if name == WAIVER else "a build-tool-only approval"
            err(f"{key}: {kind} that no longer resolves in the plugin realm (stale)")

    eng = [k.split("--")[2] for k in exceptions if k.startswith("org.junit.platform--junit-platform-engine--")]
    lau = [k.split("--")[2] for k in exceptions if k.startswith("org.junit.platform--junit-platform-launcher--")]
    if sorted(eng) != sorted(lau) or not lau:
        err(f"junit-platform-launcher approved versions {lau} must equal junit-platform-engine's {eng} "
            "(Surefire resolves the launcher itself; it is in no tree)")

    if errors:
        print(f"check-licences: FAIL ({len(errors)} problem(s))")
        for e in errors:
            print("  - " + e)
        return 1
    waivers = sorted(k.replace("--", ":") for k, n in exceptions.items() if n == WAIVER)
    print(f"check-licences: OK ({len(realm)} build-plugin realm artifacts, {len(exceptions)} exception lines)")
    print(f"  owner waivers (no licence stated, each named): {', '.join(waivers) if waivers else 'none'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
