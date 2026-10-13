#!/usr/bin/env python3
"""Pin the native protobuf executables (REQ-014 / I8, OR-127, plan 015 T0.2 Step 2, finding P15-12).

Why this check exists. The protobuf Maven plugin fetches two native executables at execution time, by an `exe`
classifier: `protoc` (the compiler) and `protoc-gen-grpc-java` (the gRPC generator). Neither is a dependency of any
module, so neither is in `license-maven-plugin`'s artifact set, and neither is in `check-licences.py`'s
`dependency:resolve-plugins` realm (whose pattern accepts `jar|maven-plugin|pom` only). The licence gate therefore
cannot see them. They were audited once, by POM licence, at one exact version each
(AGW-LICENCE-AUDIT-2026-10-08, rows 11 and 12). This check turns that audit into a pin: any other executable, or the
same executable at another version, fails here until it has been audited.

The rule, over every `pom.xml` under the repository root (skipping `target/`, `node_modules/`, `.git/`):

  1. The only `<protocArtifact>` value is
        com.google.protobuf:protoc:3.25.5:exe:${os.detected.classifier}
     and the only `<pluginArtifact>` value is
        io.grpc:protoc-gen-grpc-java:1.68.1:exe:${os.detected.classifier}
     Exact strings. NOT property-indirected: a `${protobuf.version}` property could be set by a `-D` flag, which
     would swap the audited binary for an unaudited one without touching the pom. MEASURED (T1.2 review finding 3,
     from protobuf-maven-plugin 0.6.1's plugin.xml and bytecode): `protocExecutable` wins over `protocArtifact`, and
     `pluginExecutable` (user property `protocPluginExecutable`) wins over `pluginArtifact`, whenever non-null;
     `pluginTool`/`pluginToolchain` (`protocPluginTool`/`protocPluginToolchain`) are further overrides. A `-D` flag
     for a parameter the pom does NOT configure (`-DprotocExecutable`, `-DprotocPluginExecutable`) therefore swaps
     the binary; `-DprotocArtifact` does not, because the pom configures that parameter explicitly.
  2. No other element text names an `:exe:` coordinate.
  3. No `<type>exe</type>` and no `<classifier>exe</classifier>` dependency (the same executable fetched as an
     ordinary dependency).
  4. No local-executable or toolchain override, as a configuration element or as a `<properties>` entry:
     protocExecutable, protocCommand, pluginExecutable, protocPluginExecutable, pluginTool, protocPluginTool,
     pluginToolchain, protocPluginToolchain. A binary named by file path has no coordinate to audit.
     The same names (any `-Dprotoc*`) are refused in every `.mvn/maven.config` and `.mvn/jvm.config`, which Maven
     applies to every build.
  5. No `<protocPlugins>` / `<protocPlugin>` element. The compile goal's `protocPlugins` parameter is documented
     (xolstice.org protobuf-maven-plugin compile-mojo page, fetched 2026-10-09) as "one of more custom protoc plugins,
     written in Java and available as Maven artifacts": the plugin resolves those artifacts at execution time and
     generates launchers for them, so they are executable code the licence gate never sees. (The page does not state
     a version; the repository pins 0.6.1.)

Usage: check-protoc-executables.py [--root DIR]      check a tree (default: the repository root)
       check-protoc-executables.py --self-test       plant each violation in a scratch tree and prove it is reported
"""
import argparse
import os
import sys
import tempfile
import xml.etree.ElementTree as ET

PROTOC = "com.google.protobuf:protoc:3.25.5:exe:${os.detected.classifier}"
PLUGIN = "io.grpc:protoc-gen-grpc-java:1.68.1:exe:${os.detected.classifier}"
SKIP_DIRS = {"target", "node_modules", ".git"}
LOCAL_OVERRIDES = {"protocExecutable", "protocCommand", "pluginExecutable", "protocPluginExecutable", "pluginTool",
                   "protocPluginTool", "pluginToolchain", "protocPluginToolchain"}
JVM_PLUGINS = {"protocPlugins", "protocPlugin"}


def local(tag):
    return tag.rsplit("}", 1)[-1]


def poms(root):
    for dp, dn, fn in os.walk(root):
        dn[:] = [d for d in dn if d not in SKIP_DIRS]
        if "pom.xml" in fn:
            yield os.path.join(dp, "pom.xml")


def check_pom(path, rel):
    problems = []
    try:
        tree = ET.parse(path)
    except ET.ParseError as e:
        return [f"{rel}: not parseable XML ({e}); cannot prove it names no unaudited executable"]
    for el in tree.getroot().iter():
        name = local(el.tag)
        txt = (el.text or "").strip()
        if name == "protocArtifact" and txt != PROTOC:
            problems.append(f"{rel}: protocArtifact is {txt!r}; the only audited value is {PROTOC!r}")
        elif name == "pluginArtifact" and txt != PLUGIN:
            problems.append(f"{rel}: pluginArtifact is {txt!r}; the only audited value is {PLUGIN!r}")
        elif name in LOCAL_OVERRIDES:
            problems.append(f"{rel}: <{name}> names a local executable by path; it has no coordinate to audit")
        elif name in JVM_PLUGINS:
            problems.append(f"{rel}: <{name}> fetches JVM protoc plugins from Maven at execution time; they are "
                            "executable code outside the licence gate until audited")
        elif name == "type" and txt == "exe":
            problems.append(f"{rel}: a <type>exe</type> dependency; native executables must be audited first")
        elif name == "classifier" and txt == "exe":
            problems.append(f"{rel}: a <classifier>exe</classifier> dependency; native executables must be audited first")
        elif ":exe:" in txt and name not in ("protocArtifact", "pluginArtifact"):
            problems.append(f"{rel}: <{name}> names an executable coordinate {txt!r}; only protocArtifact and "
                            "pluginArtifact may, and only at the audited pins")
    return problems


def check_mvn_configs(root):
    """Refuse any -Dprotoc* in .mvn/maven.config or .mvn/jvm.config: Maven applies them to every build."""
    problems = []
    for dp, dn, fn in os.walk(root):
        dn[:] = [d for d in dn if d not in SKIP_DIRS]
        if os.path.basename(dp) == ".mvn":
            for name in ("maven.config", "jvm.config"):
                if name in fn:
                    path = os.path.join(dp, name)
                    with open(path, encoding="utf-8", errors="replace") as f:
                        for ln, line in enumerate(f, 1):
                            if "-Dprotoc" in line:
                                problems.append(f"{os.path.relpath(path, root)}:{ln}: a -Dprotoc* flag can swap the audited "
                                                "protoc or plugin binary for every build")
    return problems


def check_tree(root):
    problems, count = [], 0
    for p in sorted(poms(root)):
        count += 1
        problems.extend(check_pom(p, os.path.relpath(p, root)))
    problems.extend(check_mvn_configs(root))
    return problems, count


def self_test():
    """Each planted violation must be reported; the clean fixture must pass. A check that cannot fire is decoration."""
    def pom(body):
        return ("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                f"<build><plugins><plugin><configuration>{body}</configuration></plugin></plugins></build>"
                "</project>")

    clean = f"<protocArtifact>{PROTOC}</protocArtifact><pluginArtifact>{PLUGIN}</pluginArtifact>"
    cases = [
        ("clean (both audited pins)", clean, False),
        ("protoc 3.25.6", clean.replace("protoc:3.25.5", "protoc:3.25.6"), True),
        ("protoc-gen-grpc-java 1.69.0", clean.replace("grpc-java:1.68.1", "grpc-java:1.69.0"), True),
        ("version via a property", clean.replace("3.25.5", "${protobuf.version}"), True),
        ("a different classifier", clean.replace("${os.detected.classifier}", "linux-x86_64", 1), True),
        ("an unaudited extra executable", clean + "<other>com.example:tool:1.0:exe:linux-x86_64</other>", True),
        ("a local protocExecutable", clean + "<protocExecutable>/usr/local/bin/protoc</protocExecutable>", True),
        ("a local pluginExecutable (T1.2 review 3)", clean + "<pluginExecutable>/tmp/evil</pluginExecutable>", True),
        ("a protocPluginExecutable property", clean + "<properties><protocPluginExecutable>/tmp/evil</protocPluginExecutable></properties>", True),
        ("a pluginTool", clean + "<pluginTool>x</pluginTool>", True),
        ("a protocPluginTool property", clean + "<protocPluginTool>x</protocPluginTool>", True),
        ("a pluginToolchain", clean + "<pluginToolchain>x</pluginToolchain>", True),
        ("a protocPluginToolchain property", clean + "<protocPluginToolchain>x</protocPluginToolchain>", True),
        ("a protocExecutable property", clean + "<properties><protocExecutable>/tmp/evil</protocExecutable></properties>", True),
        ("a protocCommand", clean + "<protocCommand>x</protocCommand>", True),
        ("an exe-typed dependency", clean + "<type>exe</type>", True),
        ("a JVM protocPlugins element", clean + "<protocPlugins><protocPlugin><groupId>x</groupId></protocPlugin></protocPlugins>", True),
    ]
    fails = 0
    for label, body, should_fail in cases:
        with tempfile.TemporaryDirectory(prefix="protoc-exe-selftest.") as d:
            os.makedirs(os.path.join(d, "m"))
            with open(os.path.join(d, "m", "pom.xml"), "w", encoding="utf-8") as f:
                f.write(pom(body))
            problems, _ = check_tree(d)
        ok = bool(problems) == should_fail
        print(f"  {'PASS' if ok else 'FAIL'} {label}: {'reported' if problems else 'not reported'}")
        fails += 0 if ok else 1
    # a -Dprotoc* flag in .mvn/maven.config or jvm.config must be reported; a harmless config must pass
    for cfg, text, should_fail in (("maven.config", "-DprotocPluginExecutable=/tmp/evil\n", True),
                                   ("jvm.config", "-DprotocExecutable=/tmp/evil\n", True),
                                   ("maven.config", "-T 1C\n", False)):
        with tempfile.TemporaryDirectory(prefix="protoc-exe-selftest.") as d:
            os.makedirs(os.path.join(d, ".mvn"))
            with open(os.path.join(d, ".mvn", cfg), "w", encoding="utf-8") as f:
                f.write(text)
            problems, _ = check_tree(d)
        ok = bool(problems) == should_fail
        print(f"  {'PASS' if ok else 'FAIL'} .mvn/{cfg} {text.strip()!r}: {'reported' if problems else 'not reported'}")
        fails += 0 if ok else 1
    # an unparseable pom must fail closed
    with tempfile.TemporaryDirectory(prefix="protoc-exe-selftest.") as d:
        with open(os.path.join(d, "pom.xml"), "w", encoding="utf-8") as f:
            f.write("<project><unclosed></project>")
        problems, _ = check_tree(d)
    ok = bool(problems)
    print(f"  {'PASS' if ok else 'FAIL'} unparseable pom fails closed")
    fails += 0 if ok else 1
    # a tree with no protobuf plugin passes (the untouched tree today)
    with tempfile.TemporaryDirectory(prefix="protoc-exe-selftest.") as d:
        with open(os.path.join(d, "pom.xml"), "w", encoding="utf-8") as f:
            f.write("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion></project>")
        problems, _ = check_tree(d)
    ok = not problems
    print(f"  {'PASS' if ok else 'FAIL'} a tree with no protobuf plugin passes")
    fails += 0 if ok else 1
    return fails


def main():
    ap = argparse.ArgumentParser()
    here = os.path.dirname(os.path.abspath(__file__))
    ap.add_argument("--root", default=os.path.abspath(os.path.join(here, "..", "..")))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        n = self_test()
        print("check-protoc-executables self-test: " + ("OK" if n == 0 else f"FAIL ({n})"))
        return 1 if n else 0
    problems, count = check_tree(args.root)
    if problems:
        print(f"check-protoc-executables: FAIL ({len(problems)} problem(s) in {count} pom.xml files)")
        for p in problems:
            print("  - " + p)
        return 1
    print(f"check-protoc-executables: OK ({count} pom.xml files; only the audited protoc/protoc-gen-grpc-java pins, "
          "or none)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
