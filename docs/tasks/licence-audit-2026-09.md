# Licence audit of the backend Maven reactor (`REQ-014`, invariant `I8`)

**Task:** Plan 010 Task **T0.4a** (the audit only — the gate is T0.4b, in W1). **Date:** 2026-09-29 (rounds 2–6:
review dispositions below, same date; rounds 7 and 8 on 2026-09-30). **Tree:** `integration/dp-remote-demo` at `953ef44` (T0.0 committed; T0.1's
contract delta uncommitted in the tree at measurement time — this audit covers `backend/`, which that delta does
not touch). **No `backend/pom.xml` or any module `pom.xml` was changed to produce this report** — the candidate
plugin was invoked directly from the command line, against the already-checked-out reactor, and nothing was
reverted because nothing was written to a POM.

**The one online Maven resolution this task is allowed:** used to resolve `org.codehaus.mojo:license-maven-plugin`
at `2.7.1` and its own build-time tooling (never `-o`, for this one invocation only). Every subsequent command in
this report — including every command added in this round's revision — ran with `mvn -o` (the plugin jar, the
default-lifecycle plugins, and their own dependencies were already in `~/.m2`, either from the first call or from
the reactor's own earlier builds).

## ⚠️ Status: open findings from the ninth review (round 9, 2026-09-30) — NOT dispositioned

The review loop did not converge: rounds 7–9 each found embedded third-party content one layer deeper, and the owner
table grew from 31 to 40 rows. At the owner's instruction ("Commit with open list"), this audit is committed **with
the round-9 findings below open**. It does **not** claim to be complete. The three MAJOR findings are carried into
the owner table as rows 41–43 by the integrator, using the reviewer's measured evidence; the implementer has not
re-derived them. Every row, and every open finding below, goes to the owner at the `T0.1 Step 7` licence sitting
(Q-L1/Q-L2). The integrator re-verified one external fact itself: busybox.net/license.html states "BusyBox is
licensed under the GNU General Public License, version 2" and "Version 2 of the GPL is the only version of the GPL
which current versions of BusyBox may be distributed under" (fetched 2026-09-30).

1. **MAJOR** — `docs/tasks/licence-audit-2026-09.md: whole document (no section covers it); compare the 'Other open questions' Maven-distribution bullet (~line 1495)`: The audit never mentions the container images the build pulls during `verify`. Failsafe is bound to `integration-test`/`verify` in cp-core/pom.xml:110, dp-core/pom.xml:235 and launcher/pom.xml:91. The ITs start `postgres:14` (for example cp-core SmControlPostgres.java:68 and launcher TwoSchemaTestDatabase.java:65) and `busybox:1.36` (dp-core DockerFixtures.java:113, DockerEvidenceSecrecyIT.java:79, DockerEngineClientIT.java:38). Testcontainers 1.21.4 also starts its default `testcontainers/ryuk` image: `TestcontainersConfiguration.class` has the `RYUK_IMAGE` and `testcontainers/ryuk` constants, and tc-env.sh does not disable it. BusyBox is GPL-2.0: busybox.net/license.html says 'BusyBox is licensed under the GNU General Public License, version 2'. That is a name in REQ-014's 'Refused' family, on the test path that `REQ-014`'s 'builds with' covers, and it is neither a row nor an open question. The audit already asks the owner the same kind of scope question about the Maven distribution and the JDK. Fix: add an owner-facing scope question, or rows, for the IT images, naming at least busybox:1.36 (GPL-2.0), postgres:14 and the Ryuk image by tag. The demo images (`postgres:16-alpine` in demo-launcher/compose.yaml; the broker and cache images in demo/docker-compose.yml) could be named too, for completeness.
2. **MAJOR** — `docs/tasks/licence-audit-2026-09.md Appendix D row for com.github.ben-manes.caffeine:caffeine:3.1.8 (~line 1900); owner table rows 31/33`: `caffeine:3.1.8` ships `com/github/benmanes/caffeine/cache/StripedBuffer.class`. Its source at tag v3.1.8, read with `gh api repos/ben-manes/caffeine/contents/caffeine/src/main/java/com/github/benmanes/caffeine/cache/StripedBuffer.java?ref=v3.1.8`, carries 'Written by Doug Lea with assistance from members of JCP JSR-166 Expert Group and released to the public domain, as explained at http://creativecommons.org/publicdomain/zero/1.0/' (lines 17–19) under Ben Manes's Apache header. That is the same content, author and terms as row 33 (Striped64/LongAdder in testcontainers' shaded Guava) and row 31 (Netty ThreadLocalRandom). The row-33 search keyed on the class names `Striped64`/`LongAdder`, so it could not see this renamed derivative. Appendix D still reads 'Apache License, Version 2.0 … Allowed (mapping only)'. Scope: build-only, in the licence plugin's realm (license-maven-plugin → odfdom-java → jena-core → jena-base → caffeine). Approval is by exact coordinate, so without a row this coordinate passes without an owner decision. Fix: add a row (Public Domain / CC0, build-only), or add `caffeine:3.1.8` to row 33's coordinates, and correct the Appendix D cell.
3. **MAJOR** — `docs/tasks/licence-audit-2026-09.md row 27 (~line 1457), round-6 disposition item 4 (~lines 487–491), Appendix E.1 tomcat bullet (~lines 2009–2020)`: The audit calls the 16 schema/DTD files in the shipped `tomcat-embed-core:10.1.55` that the LICENSE does not name 'Tomcat's own content … Apache-2.0', because they carry an ASF header. At least nine of them are not ASF-authored:
- `jakarta/servlet/resources/xml.xsd` (3,707 bytes) is the W3C `http://www.w3.org/XML/1998/namespace` schema. It is the same document row 30 adds for doxia-core, on the stated ground of 'a W3C-authored schema document shipped as a file'.
- `XMLSchema.dtd` is the W3C 'DTD for XML Schemas: Part 1' (Public ID `-//W3C//DTD XMLSCHEMA 200102//EN`), and `datatypes.dtd` is W3C Part 2.
- `web-app_2_3.dtd` and `web-jsptaglibrary_1_2.dtd` carry `-//Sun Microsystems, Inc.//DTD` identifiers.
- `j2ee_web_services_1_1.xsd` and `j2ee_web_services_client_1_1.xsd` carry '(C) Copyright International Business Machines Corporation 2002'.
The only licence statement is the ASF header Tomcat added on top. That is the same kind of relicensing assertion over third-party-authored content that row 40(b) passes to the owner for POI's eID Applet code, and this content ships in both exec jars. Fix: widen row 30, or add a row, for Tomcat's W3C-authored files. Record the Sun/IBM-authored ASF-headered files as a provenance fact or row on the row-40(b) standard. Strike 'Tomcat's own content' in the three places it appears.
4. **MINOR** — `docs/tasks/licence-audit-2026-09.md row 40 (~line 1470), E.5`: Row 40 covers only `poi-ooxml-lite` and `poi-ooxml`. `org.apache.poi:poi:5.5.1`, a sibling in the same realm, ships `org/apache/poi/sl/draw/geom/presetShapeDefinitions.xml` (538,971 bytes). That is the ECMA-376 DrawingML preset-geometry file (`presetShapeDefinitons` root, `http://schemas.openxmlformats.org/drawingml/2006/main` namespace) with no licence header. It is the same ECMA-376-derived content that row 40(a) sends to the owner, and POI's LICENSE covers it only through the ECMA bylaws paragraph. Fix: add `poi:5.5.1` to row 40(a).
5. **MINOR** — `docs/tasks/licence-audit-2026-09.md round-6 disposition item 4 (~line 485), row 27, E.1`: The audit says two of the 14 EPL names in Tomcat's LICENSE are typos (`web-commonn_5_0/6_0.xsd`), so 'a future exact-string re-run would miss them'. Measured, there are four mismatches. The LICENSE also lists `jakarta_web-services_2_0.xsd` and `jakarta_web-services_client_2_0.xsd`, but the jar ships `jakartaee_web_services_2_0.xsd` and `jakartaee_web_services_client_2_0.xsd`. Both carry 'Copyright (c) 2009, 2020 Oracle … SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0'. The 35/16 split and the 21/14 counts are correct: by header, 16 files are ASF, 14 EPL and 21 CDDL. Only the typo inventory is short.
6. **MINOR** — `docs/tasks/licence-audit-2026-09.md ~line 875 (M17(i)), ~line 1358 (build plugins section), ~lines 1972–1978 (Appendix D footer), ~line 154 (round-4 disposition)`: Four places still make unstruck 'zero new' claims about the plugin and licence-plugin realms:
- ~line 875: '62 new … every one resolving to Apache-2.0, MIT … zero new off-allow-list'.
- ~line 1358: 'zero new off-allow-list artifacts found beyond the ones already in the consolidated owner decision table'.
- ~lines 1972–1978: 'Zero new owner-decision artifacts among the 62'.
- ~line 154: the round-4 disposition's 'no new off-allow-list-by-substance artifact was found'.
Round 8 struck the same claim near line 1479 but not in these places. Rows 29 (1.26.1/1.27.1/1.28.0, surefire-shared-utils), 30 (doxia-core), 32 (plexus-xml/plexus-utils), 37 (httpclient5, httpclient), 38 (commons-math3) and 40 (poi-ooxml*) all contradict them. This is the same half-applied correction pattern earlier rounds flagged.
7. **MINOR** — `docs/tasks/licence-audit-2026-09.md Appendix E.2 / F.8 fact lists`: Some content in shipped jars that is on-list but undisclosed in the jar is not recorded, although the Netty/jzlib standard of rounds 7 and 8 would record it:
- `sshj:0.40.0` (shipped, and its jar has no LICENSE/NOTICE at all) ships `com/hierynomus/sshj/userauth/keyprovider/bcrypt/BCrypt`. Its source at v0.40.0 is jBCrypt, 'Copyright (c) 2006 Damien Miller', ISC terms. sshj's upstream NOTICE at v0.40.0 also declares JCraft 3-clause BSD code and Bouncy Castle (MIT-text) code, and this was never read.
- `spring-security-crypto:6.5.11` (shipped) ships the same ISC jBCrypt in `crypto/bcrypt/BCrypt`, per its source at tag 6.5.11.
- The shipped `docker-java-transport-zerodep:3.4.2` shades commons-codec with 160 `language/bm/` Beider-Morse rule entries. Its NOTICE says they were 'translated from the original php source … with permission from the original authors' (Beider & Morse), a provenance fact on the row-40(b) standard.
None of these needs a new row, since ISC and BSD-3 are on the allow-list, but they should be recorded as facts.
8. **MINOR** — `docs/plans/backend/010-dp-data-model-and-remote-flow.md line 332 (M17 row) and the T0.4a Results block (~lines 1333–1420)`: This one is still open and only the integrator can fix it. The plan's M17 row still reads '⚠️ partially filled 2026-09-29 (downgraded in round 3 …)', although the audit restored it to filled in round 4. The T0.4a Results block stops at round 3 and a '13' owner-line count, against the audit's current 40 rows. The audit correctly leaves this to the integrator. Recorded so it isn't lost at commit (Step 3).

## Addendum 2026-10-08 — plan 015 (agent gateway): `OR-127` applied, coverage diff (T0.2)

**Authority:** `OR-127` (owner, 2026-10-08: L1–L4) and `OR-128` (2) (dev tools approved once their licences are read at the
pinned version); plan 015 T0.2. **Not re-litigated:** the audit that `OR-127` answers is
`recovered-scratchpad/AGW-LICENCE-AUDIT-2026-10-08.md` (cited as **[Au]**); this section applies its rulings and records
what it did not cover. **Subject of the diff:** `origin/dp-agent-integration` @ `87e338e1`.

### A. `OR-127` applied

| Ruling | Applied how, and when |
|---|---|
| **L1** `javax.annotation-api 1.3.2` not taken | The port's `agent-gateway/pom.xml` drops the dependency (plan 015 §5 ADAPT row); the plugin parameter `@generated=omit` is proved in T1.2's first build. If `protobuf-maven-plugin 0.6.1` cannot forward it, the question returns to the owner. **No exception line is written.** |
| **L2** `bcpkix-jdk18on 1.86`, `bcutil-jdk18on 1.86` | Measured below (§C.1) to be needed by the gate: the two exact lines `org.bouncycastle--bcpkix-jdk18on--1.86=Owner-approved (shipped)` and `org.bouncycastle--bcutil-jdk18on--1.86=Owner-approved (shipped)` land **in T2.2's commit** (HF13), not here. |
| **L3** `grpc-netty-shaded 1.68.1` incl. BoringSSL | Notice obligations go to `backend/THIRD-PARTY-NOTICES.md` with T1.2. **Measured: the gate passes this coordinate without an exception line** (its POM says "Apache 2.0", which the allow-list maps), so the [Au §4] proposed line is not required to turn the build green; whether to add it as a record is a T1.2 call. |
| **L4** the two shipped images | The corresponding-source-offer sentence goes to the release runbook (T5.2). |
| The gate stays (headline 1 of [Au]) | Now an executable fact: `backend/scripts/test-licence-gate-present.sh` (run-checks step 16). |
| The native executables | Now pinned: `backend/scripts/check-protoc-executables.py` (run-checks step 17). |

Both new checks are green on the untouched tree and each carries planted proofs (13 and 10 scenarios).
`[Au]` says "five licence files and `check-licences.py`"; the tree has **four** `licence-*` files
(`allowlist`, `exceptions`, `artifact-filters`, `third-party.ftl`) plus `check-licences.py`, which is the five of [Au]'s
headline 1. The check requires those four, `check-licences.py`, `test-check-licences.sh`, the plugin with both executions
bound to `verify`, and the `Proprietary - Solace` block.

**Finding for the port (HF13):** the branch writes the protoc coordinates through properties
(`com.google.protobuf:protoc:${protobuf.version}:exe:...`). `check-protoc-executables.py` requires the **literal**
`3.25.5` / `1.68.1`, because a `-Dprotobuf.version=` flag would swap the audited binary without touching the pom. The
ported plugin configuration must spell the versions out.

### B. Method of the coverage diff (I2: measured, not recalled)

- **Java shipped set:** a scratch project (outside the worktree) with parent `spring-boot-starter-parent 3.5.16` and
  `grpc-netty-shaded`, `grpc-protobuf`, `grpc-stub` `1.68.1`, `protobuf-java-util 3.25.5`, `bcpkix-jdk18on 1.86`,
  `spring-boot-starter`; `mvn dependency:list` gives the **resolved** versions (the [Au §1.4] rows were POM-named).
  Each resolved coordinate was run through `check-licences.py`'s own `pom_licences`/`classify` against the real
  allow-list and exceptions.
- **Build plugins:** `mvn dependency:resolve-plugins` of a scratch project declaring the two plugins; the same classifier.
- **Go:** Go `1.26.4` (the version `go.mod` names) downloaded from `go.dev/dl` into scratch, **sha256-verified**
  (`b62ad2b6...a9bf53`), **not installed**; `go list -deps` at `87e338e1` for `linux/amd64`, `linux/arm64` and
  `darwin/arm64`, `CGO_ENABLED=0` (the Dockerfile's build).
- **Tools:** each licence file read at the version named, by WebFetch of the tag, or from the Go module proxy zip (the
  bytes `go install` verifies), or from the extracted toolchain.

### C. What the audit did not cover

**C.1 Shipped Java (resolved set: 45 jars, 25 of them outside the Spring Boot starter's already-audited tree).** Every [Au §1.1/§1.4] row resolves at the audited version except:

| Coordinate | Finding | Gate effect |
|---|---|---|
| `com.google.code.gson:gson` | resolves **2.13.2** (Spring Boot BOM), [Au] named 2.11.0 / 2.8.9; POM says Apache-2.0 | passes |
| `org.checkerframework:checker-qual:3.42.0` | not in [Au]; POM "The MIT License" | passes |
| `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` | not in [Au]; POM Apache | passes |
| `com.google.guava:guava` | `33.2.1-android` is selected (grpc's); `32.0.1-jre` is not on the resolved path; `failureaccess 1.0.2` is a real edge | passes |
| `org.codehaus.mojo:animal-sniffer-annotations:1.24` | [Au] rated it MIT (clear). Its POM names the licence **"MIT license"**, which is **not a mapped name** in `licence-allowlist.txt` | **would FAIL the shipped gate** |
| `bcpkix 1.86`, `bcutil 1.86` | as `OR-127` L2 | fail until the two lines land (T2.2) |

**C.2 Build-plugin realms** ([Au §1.5] marked the plugins' own dependencies UNVERIFIED). Measured (26 jars for
`protobuf-maven-plugin 0.6.1` including itself, 14 for `os-maven-plugin 1.7.1` including itself): all are Apache-2.0 or already approved **except four,
none named by `OR-127`, all build-tool-only, none shipped:**

| Coordinate | Licence (POM) | Why the gate stops |
|---|---|---|
| `javax.annotation:jsr250-api:1.0` | **CDDL 1.0** | off the allow-list |
| `org.eclipse.sisu:org.eclipse.sisu.inject:0.3.3` | EPL 1.0 | exceptions hold only `0.9.0.M3` |
| `org.eclipse.sisu:org.eclipse.sisu.plexus:0.3.3` | EPL 1.0 | same |
| `org.codehaus.plexus:plexus-utils:3.1.0` | Apache-2.0 | version not approved (`3.5.1`, `4.0.1`, `4.0.2` are) |

**C.3 Go modules compiled into `sm-agent`.** `linux/amd64` and `linux/arm64`: **32 third-party modules, identical sets**;
`darwin/arm64`: 31 (`prometheus/procfs` is Linux-only). **All 32 are rows of [Au §2] at the exact version**
(`docker/docker 27.1.2+incompatible`, `grpc 1.84.0`, `protobuf 1.36.12`, `prometheus/client_golang 1.24.1`,
`otel 1.46.0`, `x/net 0.58.0`, ... all Apache-2.0 / BSD / MIT per [Au]). No gap. The test build adds one module,
`kylelemons/godebug 1.1.0`, also in [Au]. The `go.sum`-only modules [Au] left UNVERIFIED are not in the binary (nor the
test build) and are out of the gate's scope. The standard library is statically linked: BSD-3-Clause, read from the
verified 1.26.4 distribution's `LICENSE` ("Copyright 2009 The Go Authors") and from the `go1.27.1` tag.

**C.4 Tools T0.4 installs (none was in [Au]).** All permissive and commercially free; nothing returns on licence grounds.

| Tool | Pinned version | Where the pin comes from | Licence, read at that version |
|---|---|---|---|
| Go toolchain | `1.26.4` (`go.mod`); Homebrew offers **1.27.1** | `go.mod`; the branch's evidence ran 1.27.1 | BSD-3-Clause (distribution `LICENSE`, 1.26.4; WebFetch of tag `go1.27.1`) |
| `buf` | **none on the branch** (docs say `@latest`); Homebrew offers **1.73.0** | T0.4 must record the version it installs | Apache-2.0, "Copyright 2020-2026 Buf Technologies, Inc.", no field-of-use clause (WebFetch of tag `v1.73.0`). **Not walked:** `buf`'s own Go dependency set (a dev tool, not shipped or redistributed) |
| `protoc-gen-go` | `v1.36.12` (generated header; = `google.golang.org/protobuf`, the runtime module [Au §2] already audited); Homebrew offers 1.36.12 | generated `*.pb.go` header | BSD-3-Clause, "Copyright (c) 2018 The Go Authors" (WebFetch of tag `v1.36.12`) |
| `protoc-gen-go-grpc` | `v1.5.1` (generated header); Homebrew offers **1.6.2** | generated `*_grpc.pb.go` header | Apache-2.0 at **both** (module-proxy zips; identical `LICENSE`, sha256 `cfc7749b...523d30`); its dependency modules are grpc/protobuf/x/* already in [Au] |
| `protoc` (only if a step calls it) | not installed | `buf` compiles `.proto` itself | not read; `3.25.5` Maven POM says BSD-3-Clause ([Au] row 11, re-read) |
| `protoc`/`protoc-gen-grpc-java` Maven executables | `3.25.5` / `1.68.1` | plan 015 | POMs re-read: BSD-3-Clause / Apache 2.0; `osx-aarch_64` `.exe` artifacts exist (7.7 MB / 6.2 MB). **The binaries themselves, and the C++ libraries statically linked into them, were not inspected** (unchanged from [Au]) |

Homebrew's `protoc` 36.2 and `abseil`, pulled in by `protoc-gen-go`, are accepted by **`OR-130` L-c** as unused dev-machine tooling,
never invoked by a build step and never shipped.

*Note (W0 fix round, `I2`): the sentence in §A above that a `-Dprotobuf.version=` flag "would swap the audited binary" is a reading, not a
measurement; the plugin's compile-mojo page (fetched 2026-10-09) documents `protocArtifact`/`protocExecutable` as command-line user
properties, and the exact-string rule is kept as the conservative position.*

For T0.4 and T1.4, not licence matters but found here: (i) Homebrew's `protoc-gen-go-grpc` is **1.6.2**, the committed
`*_grpc.pb.go` says **1.5.1**, and T1.4 Step 2 expects the regenerated code to differ only in the source-path comment, so
the plugin should be installed at `v1.5.1` (`go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.5.1`) rather than
by Homebrew; (ii) the committed `agent_gateway.pb.go` header reads `protoc v4.25.5`, a version string a `protoc` run
writes, so whether `buf generate` reproduces that line is **unmeasured** until T0.4 installs `buf`.

### D. What returns to the owner (before the named task merges)

1. **`animal-sniffer-annotations 1.24`** (shipped, MIT in substance): map the variant name "MIT license" to `MIT` in the
   allow-list, or approve the exact coordinate with a `Owner-approved (shipped)` line. The second leaves the allow-list
   narrow. Needed before **T1.2** merges. **Ruled: `OR-130` L-a** (exact-coordinate exception, MIT text).
2. **The four build-tool coordinates of §C.2**, of which `jsr250-api 1.0` is **CDDL-1.0** and two are EPL-1.0
   (weak, file-level copyleft; used only inside the build, never shipped). Needed before **T1.2** merges (it adds the
   plugins). **Ruled: `OR-130` L-b** (build-only exceptions). The lines land in T1.2's commit, not earlier (`check-licences.py` rule 4
   fails a build-tool approval nothing resolves yet).
3. **Five Java coordinates the audit did not name at the resolved version** (found by the review of W0 and the T1.2 review; T0.2 Step 3's own rule
   is that anything the audit does not name, or names at another version, returns to the owner): `com.google.code.gson:gson` **2.13.2**
   (POM Apache-2.0; [Au] named 2.11.0 / 2.8.9), `org.checkerframework:checker-qual:3.42.0` (POM "The MIT License"),
   `com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` (Apache-2.0, via guava-parent 26.0-android),
   `io.micrometer:micrometer-core:1.15.12` (Apache-2.0) and `org.springframework.boot:spring-boot-actuator:3.5.16` (Apache-2.0); the last two are new to
   the reactor with `agent-gateway`. The `I8` gate passes all five by POM licence name and no exception line is needed; **the confirmation is a record,
   not a gate line, and a gate pass is not the owner's confirmation. OPEN — owner confirmation needed before T1.2 merges** (plan 015 §10 item 20).
4. **Nothing on the Go modules or on the tools**: no licence was found that is not commercially free. `OR-128` (2) is
   thereby satisfied for `buf` (at the version read, 1.73.0 or a re-read at whatever T0.4 installs), `protoc-gen-go`
   1.36.12 and `protoc-gen-go-grpc` 1.5.1 / 1.6.2.

Still **UNVERIFIED** after this pass: the per-package licences inside the two images (L4 carries the obligation
regardless); jar-embedded content beyond `grpc-netty-shaded` for the Java set; the `protoc` binaries' statically linked
libraries; `buf`'s dependency set. The scratch resolutions ran against a throwaway project; the real reactor's
first gated build (T1.2) remains the authority on resolved versions.

## Disposition of the second review (round 2, 2026-09-29)

Six findings (5 MAJOR, 1 MINOR + a second MINOR bundled with it) came back on the first version of this report.
All six are **CONFIRMED** against the plugin's own source (re-read from the scratchpad copies of
`AbstractAddThirdPartyMojo.java`, `DefaultThirdPartyTool.java`, `DefaultThirdPartyHelper.java`) and against fresh
offline measurements of this reactor. Disposition, in the findings' own order:

1. **MAJOR, M17(iv) regex claim — FIXED.** The report's "different goal" dismissal was wrong; `excludedGroups` /
   `includedGroups` / `excludedArtifacts` / `includedArtifacts` are `@Parameter`s on `AbstractAddThirdPartyMojo`
   itself (the base class `add-third-party`/`aggregate-add-third-party` extend), documented as unanchored,
   partial-match regexes. Corrected in (iv) below, with the exact javadoc quoted.
2. **MAJOR, M17(ii)/(iii) source-reading errors — FIXED.** `addLicense()` does put a no-licence dependency into
   `licenseMap` under `LicenseMap.UNKNOWN_LICENSE_MESSAGE`; `checkForbiddenLicenses()` does flag that key once
   `includedLicenses` is non-empty; and the whitelist-clearing step is gated on `whiteLicenses` being non-empty, so
   blacklist-only mode does **not** silently clear an LGPL half. All three re-verified line-by-line below, with the
   `useRepositoryMissingFiles`/`missingFile`/`missingLicensesFileArtifact`/`overrideUrl`/`overrideFile` channels
   added.
3. **MAJOR, build-plugin audit incomplete — FIXED.** Resolved every plugin version with `mvn -o
   dependency:resolve-plugins` (offline; the reactor already had them from its own earlier builds) instead of
   reading `~/.m2` residue; audited the two new off-list transitive dependencies this turns up
   (`org.tukaani:xz:1.9`, `net.java.dev.jna:jna`/`jna-platform:5.17.0`); verified the six previously-unverified
   default-lifecycle plugins' own licence claim by the same file-header method as the two already checked. The
   candidate plugin's *own* runtime dependency tree (poi/jena/xmlbeans/…) remains unchecked — see the explicit
   scoping note where that gap is now stated instead of implied.
4. **MAJOR, refused-family conclusion — FIXED.** "Zero refused-family licences" is retracted. `GPL2 w/ CPE`
   (`jakarta.annotation-api`'s second declared licence) is a `GPL-*` name, which `REQ-014` line 28 lists under
   "Refused". Whether a GPL-with-Classpath-Exception half of a dual-licensed POM counts as "refused" under
   `REQ-014`/Q-L2 is now recorded as an **open owner question**, not decided by omission.
5. **MAJOR, missing per-artifact record — FIXED.** The full 123-artifact table, with per-module, per-scope
   breakdown (from an offline `dependency:tree` over every module), is now Appendix A of this document — not left
   in the ephemeral scratchpad. It also separates the **7 rows that are Solution Manager's own reactor modules**
   (carrying an *inherited* Apache-2.0 claim from `spring-boot-starter-parent`, not a declared one) from the **116
   actual third-party artifacts**, which the original "123 third-party" count conflated.
6. **MINOR (BouncyCastle) — FIXED in round 2, then found still wrong in round 3.** ⚠️ **Round 3 correction:** this
   bullet's "the shipped `launcher`/`demo-launcher` classpath" conflated two modules that do not resolve the same
   way. Re-measured per module (`dependency:tree -Dincludes=org.bouncycastle`, scoped to `launcher` and
   `demo-launcher` separately): **`launcher`** carries `bcprov-jdk18on:1.86` (nearest-wins, via `cp-core`) together
   with `bcpkix-jdk18on:1.80` and `bcutil-jdk18on:1.80.2` — the mixed set this bullet describes. **`demo-launcher`**
   has no `cp-core` dependency to trigger that override, so it resolves `bcprov-jdk18on:1.80` (not `1.86`) plus the
   same `bcpkix-jdk18on:1.80`/`bcutil-jdk18on:1.80.2` — one clean `1.80`-line release, matching Appendix A's own
   row. The mixed-version finding is real, but it is **`launcher`-only**; see the corrected M17(iii) table row
   below.
7. **MINOR (header/scope overstatement + citation) — FIXED.** "All 8 reactor modules" → 9 modules + the parent (10
   total, matching the build log's `[n/10]`). "Compile, every production module" for logback/jakarta.annotation-api
   is corrected to name the exact 5 of 8 production modules that resolve it (`cp-api`/`dp-api`/`mesh-events` do
   not). The `dp-core:jar:tests` "could not be resolved" warning is now stated as a coverage caveat. The (v)
   citation is corrected to the aggregate goal's own class.

No finding was disputed. Everything below is the corrected report; struck-through claims are kept struck rather
than deleted so the correction is visible against the original.

## Disposition of the third review (round 3, 2026-09-29)

A third-round review returned 3 MAJOR + 3 MINOR findings (2 against this report, 1 against the plan's own T0.4a
Results text). Five of six are **CONFIRMED** against fresh offline re-measurement and the plugin's own source
(re-read again from the scratchpad copies); one MINOR sub-claim is **disputed with evidence** (the exact count,
not the underlying point). No new online Maven resolution was used — everything below is `mvn -o`, `dependency
:resolve-plugins`, `dependency:tree`, and `-X` provider-classpath output already cached from this reactor's own
earlier builds, plus WebFetch of Central POMs (not a Maven resolution). Disposition, in the findings' own order:

1. **MAJOR, M17(ii) `failOnMissing` mechanics — CONFIRMED, FIXED.** Round 2's claim that `failOnMissing`/
   `failIfWarning` gate only "a separate, softer WARN-only report" is wrong. Re-read from the scratchpad copy of
   `AbstractAddThirdPartyMojo.java`, `checkMissing(boolean unsafe)` (lines 1069–1075) throws
   `MojoFailureException` when `unsafe && (isFailOnMissing() || failIfWarning)`; `AggregatorAddThirdPartyMojo`
   (`rev3/AggregatorAddThirdPartyMojo.java:277–281`) calls `checkMissing(CollectionUtils.isNotEmpty(unsafeDependencies))`
   unconditionally on every run, and `unsafeDependencies` is populated by `getProjectsWithNoLicense(licenseMap)`
   (line 779) independently of `includedLicenses`. So `failOnMissing=true` **does** fail the build on a no-licence
   dependency, allow-list armed or not — corrected below.
2. **MAJOR, build-plugin audit still incomplete — CONFIRMED, FIXED.** Re-ran `mvn -o -f backend/pom.xml
   dependency:resolve-plugins` (offline, already cached) and read every plugin's transitive tree in full rather
   than only the two artifacts round 2 stopped at. `org.jdom:jdom2:2.0.6.1`, `org.antlr:antlr4-runtime:4.7.2` and
   `com.github.luben:zstd-jni:1.5.5-11` are added below, plus a second, different-in-kind gap: the JUnit-platform
   test **provider** classpath (`surefire-junit-platform`, `common-java5`, `org.junit.platform:junit-platform-launcher`)
   that Surefire resolves for itself at test-execution time, outside `dependency:resolve-plugins` entirely —
   measured with `mvn -o -X -f backend/pom.xml -pl mesh-events test`.
3. **MAJOR, M17(i) "own runtime dependencies" left unchecked while M17 is marked filled — CONFIRMED, FIXED (scope
   narrowed, not closed).** The eight specific artifacts the review named (`org.nuiton.processor:nuiton-processor:1.3`,
   `org.eclipse.sisu:org.eclipse.sisu.inject:0.9.0.M3`, `xalan:serializer:2.7.3`, `org.codehaus.plexus:plexus-i18n:1.0-beta-10`,
   `org.json:json:20231013`, `org.tukaani:xz:1.10`, `com.github.virtuald:curvesapi:1.08`, `net.rootdev:java-rdfa:1.0.0-BETA1`,
   `xml-apis:xml-apis:1.4.01`) are now individually verified below, each against its own POM (and parent chain
   where the immediate POM has none), by WebFetch of Central — not from memory. The wider class realm (72
   artifacts total for the `:help` goal alone, including the `doxia`/`velocity`/reporting toolchain, a different
   and larger set than the `poi`/`jena`/`xmlbeans` toolchain M17(i) already named) is **still not individually
   checked artifact-by-artifact** — stated as a residual gap, not implied clean, per the review's own suggested
   fix. **M17's header is downgraded from "filled" to "partially filled"** to make that residual gap visible
   rather than buried under a "✅ filled" status.
4. **MINOR, M17(iii) leftover contradicting sentence — CONFIRMED, FIXED.** The unstruck "even then... Either path
   is 'any', not 'every'" sentence is struck below; it restated the exact claim the very next paragraph (added in
   round 2) had already re-read from source and found false for blacklist-only mode.
5. **MINOR, BouncyCastle disposition-item-6 vs. body contradiction — CONFIRMED, FIXED.** Re-measured with
   `dependency:tree -Dincludes=org.bouncycastle` scoped to each module separately: `demo-launcher` alone resolves
   `bcprov-jdk18on:1.80` (no `cp-core` dependency to trigger nearest-wins), not `1.86`. Only `launcher` — which
   depends on both `cp-core` (pulling `1.86`) and `dp-core` (pulling `1.80` via `sshj`) — gets the mixed
   `1.86`/`1.80`/`1.80.2` set. `demo-launcher`'s own shipped set is `1.80`/`1.80`/`1.80.2`, all one release line.
   The round-2 disposition bullet and the M17(iii) body row both conflated the two modules; both corrected below.
6. **MINOR, plan T0.4a round-1 Results not struck through — CONFIRMED for the five named phrases, FIXED in the
   plan file.** **Disputed in part**, with evidence: re-counting Appendix A.1 (`awk`-parsed by column, cross-checked
   by two independent methods) gives **83** Apache-only rows, matching this report's own "~83" — not "84" as the
   finding states. The other half of this finding — that the ASF-plugin bullets undersell their own evidence — is
   confirmed: `maven-jar-plugin`/`maven-failsafe-plugin`'s effective POM, via `maven-plugins:42` → `maven-parent:42`
   → `org.apache:apache:32`, inherits an explicit `<licenses>Apache-2.0</licenses>` element (fetched from Central
   and quoted below) — not merely a file-header inference, the same evidential standing as SM's own
   inherited-from-`spring-boot-starter-parent` claim elsewhere in this report. Fixed below; the count dispute does
   not change the fix (the plan's round-1 "~90" phrase is still wrong and still struck).

## Disposition of the fourth review (round 4, 2026-09-29)

A fourth-round review returned 1 MAJOR + 3 MINOR findings, all against the completeness and bookkeeping of this
report rather than against any licence claim already made. All four are **CONFIRMED**, and all four are now fixed
by direct offline re-measurement (no online resolution used). Disposition, in the findings' own order:

1. **MAJOR, build-plugin audit still incomplete — CONFIRMED, FIXED.** Round 3 audited plugin-realm dependencies by
   reading the tree "in full" but did not mechanically enumerate every resolved GAV against the allow-list; it
   sampled the ones that looked interesting. Re-run this round: `mvn -o -f backend/pom.xml dependency:resolve-plugins`
   over the **whole reactor** (all 10 modules, not just the root POM — the previous rounds' single-module
   invocation silently missed every plugin declared only in a child module) gives **96 resolved-artifact lines**,
   **86 distinct `group:artifact:version` coordinates** (10 of the 96 are the same plugin GAV listed twice — once
   as its `maven-plugin` descriptor, once as the `jar` dependency of its own class realm; same artifact, not a
   second one). Every one of the 86 is walked below in **Appendix C**, POM-and-parent-chain, from the local
   `~/.m2` cache (two — `maven-deploy-plugin:3.1.4`, `maven-site-plugin:3.12.1` — had no cached POM; fetched from
   `repo1.maven.org` by WebFetch, per `I2`, not assumed). The finding's own example,
   `org.sonatype.plexus:plexus-build-api:0.0.7`, is confirmed exactly as stated: its own POM has no `<licenses>`
   block; its parent `org.sonatype.spice:spice-parent:15` declares `<license><name>Apache Public License
   2.0</name><url>http://www.apache.org/licenses/LICENSE-2.0</url></license>` — the URL is the canonical Apache-2.0
   licence text, so this is Apache-2.0 **in substance**, under a non-standard name (an eighth spelling variant,
   joining the six already catalogued under M17(iv) and `error_prone_annotations`' "Apache 2.0" below). ⚠️ **Round
   5 correction:** the sentence that followed here — "**not** a new owner-decision item," decided by this audit on
   its own authority — is exactly the self-allow-listing a later review flagged: a name variant, however good the
   equivalence evidence, still goes to the owner for confirmation before `licenseMerges` treats it as the same
   licence (Q-L1's own approval discipline: approve by exact coordinates, not by pattern-match). Struck; this
   audit records the evidence and defers the mapping decision — see **Appendix B**, which now inventories every
   variant string this reactor uses (not just this one) with its equivalence evidence, for the owner to confirm.
   ~~item.~~ **One correction to the finding itself, per `I2` — verify, don't restate:** measured (`grep -B2` around
   every `plexus-build-api` line in the resolve-plugins output, all 10 modules), it is pulled **only** by
   `maven-resources-plugin:3.3.1`; it does **not** appear as a dependency of `maven-compiler-plugin:3.14.1` in this
   reactor's resolution — the two plugins are adjacent in the printed output (both bind by default in every
   module) but `plexus-build-api` nests under `maven-resources-plugin` alone. The substantive point the finding
   raises — coverage was incomplete — stands and is now fixed regardless of that one attribution detail. **Result
   of the complete walk: no new off-allow-list-by-substance artifact was found** (i.e. nothing whose *substance* is
   outside REQ-014's allow-list; see round 5 for the separate question of which *spelling variants* still need
   owner confirmation, and Appendix E for embedded/shaded content the plugin's own scan cannot see at all). The
   only **five** plugin-realm coordinates needing an owner decision are the ones already on record from rounds 2–3
   plus `zstd-jni` (`net.java.dev.jna:jna`/`jna-platform:5.17.0`, `org.antlr:antlr4-runtime:4.7.2`,
   `org.jdom:jdom2:2.0.6.1`, `org.tukaani:xz:1.9`, `com.github.luben:zstd-jni:1.5.5-11` — ⚠️ **round 5 correction:**
   round 4 said "four" here, omitting `zstd-jni`, which is itself a plugin-realm coordinate (pulled via
   `maven-jar-plugin`) and not merely a third-party-table row) — see the consolidated table below. Completeness
   was the defect, not a hidden violation.
2. **MINOR, M17(i) downgraded to "partially filled" while Step 1 was ticked — CONFIRMED, FIXED; M17 upgraded to
   filled.** Round 3 individually verified 8 of the class realm's members and left "~60-odd" unverified. Re-run
   this round with the actual goal this audit and T0.4b both bind — `mvn -o -N -f backend/pom.xml -X
   org.codehaus.mojo:license-maven-plugin:2.7.1:aggregate-add-third-party -Dlicense.excludedScopes=system
   -Dlicense.outputDirectory=<scratchpad> -Dlicense.sortArtifactByName=true` — and `grep -c "Included:"` on the
   `-X` log gives exactly **72** (confirming round 3's count was the right number, from the right realm). All 72
   are now walked, POM-and-parent-chain, in **Appendix D**: 10 were already individually verified in the M17(i)
   table above (including the licence-plugin's own self-reference); the other **62 are new this round**. Result:
   **zero** new off-allow-list-by-substance or no-licence artifacts among the 62 — every one resolves to
   Apache-2.0, MIT, or a `licenseMerges`-mapping variant of one of those. One residual ambiguity from round 3 is
   now closed by direct measurement: `org.codehaus.plexus:plexus-i18n:1.0-beta-10`, left "one hop short of
   certainty" in round 3, is walked here to its full chain (`plexus-i18n:1.0-beta-10` → `plexus-components:1.1.12`
   → `plexus:1.0.10`) and **confirmed to have no `<licenses>` block anywhere in the chain** — the same "no licence
   at all" case as `xalan:serializer:2.7.3`, not merely unmapped. **M17's header is upgraded from "partially
   filled" to "filled"**: every member of the report goal's own class realm is now individually walked, not
   sampled, and the residual-gap caveat is retired.
3. **MINOR, "Left open for the owner" tally wrong and scattered — CONFIRMED, FIXED.** The finding is correct on
   both counts: the "thirteen" tally omitted `org.tukaani:xz:1.9` and `net.java.dev.jna:jna`/`jna-platform:5.17.0`
   (both already in the "New in round 2" bullets, never folded into the headline count) and never counted
   `org.codehaus.mojo:license-maven-plugin:2.7.1` itself (flagged in M17(i), never tabulated). **Fixed by
   replacing every "needs a decision" table and bullet list in this document with one consolidated table, counted
   once** — see "Owner decision table (consolidated, round 4)" below, which supersedes the two tables under
   "Needs an explicit, recorded owner decision" and every "Left open for the owner" bullet.
4. **MINOR, shipped exec-jar loader/jarmode code not recorded — CONFIRMED, FIXED.** Verified directly on the
   already-built artifacts in `backend/launcher/target/` and `backend/demo-launcher/target/` (read-only `unzip -l`,
   no build triggered — the jars pre-existed from earlier work in this tree). `launcher-0.1.0-SNAPSHOT-exec.jar`
   contains `BOOT-INF/lib/spring-boot-jarmode-tools-3.5.16.jar` (53,753 bytes) and **113**
   `org/springframework/boot/loader/*` classes; `demo-launcher-0.1.0-SNAPSHOT.jar` (the demo module's own
   repackage output, no separate `-exec` classifier) carries the same jarmode jar and **113** loader classes (114
   matched entries including the package directory marker). The **thin** `launcher-0.1.0-SNAPSHOT.jar` (5.8 KB, the
   reactor-internal dependency artifact) has **zero** — confirming the injection is specific to the repackaged
   executable, not the plain jar. Both injected pieces come from `spring-boot-loader-tools:3.5.16`
   (`spring-boot-maven-plugin`'s `repackage` goal); the declared string is `Apache License, Version 2.0`, one of
   the Appendix B spelling variants — Apache-2.0 in substance, with the spelling mapping left to the owner's
   Appendix B sign-off (⚠️ round 8: this used to read "**Allowed**, no owner decision needed", which is the
   self-allow-listing wording round 6 said it had removed). Recorded here as a **third distinct ship-path gap** (alongside the build-plugin gap and
   the Surefire test-provider gap already on record): repackaging **injects code that never appears in
   `dependency:tree`, `dependency:resolve-plugins`, or the third-party report**, because it is written directly
   into the jar by the plugin's own repackage logic rather than resolved as a POM dependency. **T0.4b's "second
   check" must account for all three gaps** (build plugins, test-provider classpath, repackage-injected loader
   code), not just the first two already named.

## Disposition of the fifth review (round 5, 2026-09-29)

A fifth-round review returned 3 MAJOR + 3 MINOR findings, all against completeness (the plugin's own resolved-GAV
scan is not the same thing as what a jar actually contains or embeds) and bookkeeping. **All six are CONFIRMED**
against direct, read-only inspection of the already-built jars (`unzip -l`/`unzip -p`, no build triggered — the
same method round 4 used for the jarmode/loader finding), fresh offline `dependency:tree`, and WebFetch of the
plugin/POM/build-config sources actually named (per `I2`, no name asserted from memory). **No new online Maven
resolution was used** — every `mvn` command this round ran `-o`; WebFetch of Central POMs, project NOTICE files
and a project's own build configuration is not a Maven resolution, the same distinction earlier rounds drew.
Disposition, in the findings' own order:

1. **MAJOR, embedded LICENSE/NOTICE content invisible to the POM-only scan — CONFIRMED, FIXED.** Every prior round
   read licences from `<licenses>` elements (POM chain) or the plugin's own `THIRD-PARTY.txt`; none opened the
   jars actually shipped. Opening `backend/launcher/target/launcher-0.1.0-SNAPSHOT-exec.jar` read-only:
   - **`com.fasterxml.jackson.core:jackson-core:2.21.4`** carries `META-INF/FastDoubleParser-LICENSE` (the
     bundled Java port itself, MIT, Copyright Werner Randelshofer) **and**
     `META-INF/FastDoubleParser-ThirdParty-LICENSE`, which states verbatim: "Third-party license for fast_float
     ... Boost Software License - Version 1.0" for the Daniel Lemire `fast_float` algorithm the port is based on.
     Confirmed by reading both files. ⚠️ **Round 7 correction, per `I2` — verify, don't restate:** the quotation
     above is inaccurate. `FastDoubleParser-ThirdParty-LICENSE` has three sections: (1) `# FastDoubleParser` — "a Java
     port of Daniel Lemire's fast_float project", `Copyright (c) Daniel Lemire`, **Boost Software License - Version 1.0**;
     (2) "Third-party license for fast_float, Copyright (c) 2021 The fast_float authors. MIT License."; (3)
     "Third-party license for bigint, Copyright 2020 Tim Buktu. 2-clause BSD License." Boost belongs to section (1),
     not to `fast_float`; row 26 stands on section (1). ⚠️ **One correction to the finding, per `I2` — verify, don't restate:** the
     finding reads this as automatically landing in `REQ-014`'s "Refused: ... BSL/BUSL" line because "'BSL'
     literally matches." Read against `REQ-014`'s own text, that line names BSL/BUSL in the same breath as SSPL
     and Elastic — the **Business Source License** family (a source-available, delayed-open-source *commercial*
     licence, which MariaDB itself abbreviates "BSL" and which other adopters call "BUSL"), not the unrelated,
     OSI-approved **Boost Software License** (SPDX `BSL-1.0`) actually found here. Two different licences share
     the letters "BSL" — exactly the kind of name collision `I2` exists to catch, and asserting the "Refused"
     reading from the string match alone would repeat the mistake this project has already made three times
     (memory note, `I2`). **This does not shrink the finding**: `BSL-1.0` (Boost) is still not on `REQ-014`'s
     six-item allow-list and still needs an explicit, recorded owner decision — it is a **needs-a-decision** case
     under the requirement's own middle tier, not a refused one, and the owner should read the actual Boost text
     (quoted in Appendix E) rather than either label.
   - **`org.apache.tomcat.embed:tomcat-embed-core:10.1.55`** carries a top-level Apache-2.0 `META-INF/LICENSE`,
     but that file's own "APACHE TOMCAT SUBCOMPONENTS" section (read in full) puts 21 named Java-EE deployment-
     descriptor schemas under **CDDL-1.0** and 14 named Jakarta-EE schemas under **EPL-2.0**. Counted directly in
     the jar: **51** files ship under `jakarta/servlet/resources/` (one further `.dtd` file, under
     `org/apache/tomcat/util/modeler/`, is Tomcat's own Apache-2.0 content, not part of this subcomponent set) —
     ⚠️ the finding said "52 ... under `jakarta/servlet/resources/`"; measured, it is 51 there plus 1 elsewhere,
     52 in total across the whole jar. ⚠️ **Round 6 correction, per `I2` — verify, don't restate:** "51 `.xsd`
     files" is itself wrong — the 51 are **45 `.xsd` + 6 `.dtd`**, and only **35** of the 51 (21 CDDL + 14 EPL) are
     actually named in the "SUBCOMPONENTS" section; the other 16 carry Tomcat's own ASF Apache-2.0 header. See the
     round 6 disposition and the corrected row 27 / Appendix E.1 below. The substance is unchanged: CDDL-1.0 and
     EPL-2.0 content ships inside an artifact whose own top-level licence file reads "Apache-2.0" at a glance.
   - **On-list-in-substance shaded content, found but not previously recorded** (no new owner decision — kept
     separate from the two rows above): `org.postgresql:postgresql:42.7.11` shades `com.ongres:scram-client`/
     `scram-common:3.2` and `saslprep`/`stringprep:2.2` under `org/postgresql/shaded/`, each with its own
     `META-INF/licenses/.../LICENSE` read and confirmed **BSD-2-Clause** (2-clause text, no endorsement clause);
     `com.github.docker-java:docker-java-transport-zerodep:3.4.2` shades `org.apache.hc`/`org.apache.commons`
     under `com/github/dockerjava/zerodep/shaded/` (Apache-2.0 in substance — same licence as the unshaded
     coordinates already `Allowed` elsewhere in this report); `com.solacesystems:sol-jcsmp:10.30.1` ships
     `org/apache/commons/lang/exception/*` classes (third-party Apache Commons Lang code bundled inside the
     first-party JCSMP exception). All three confirmed by directly reading the jar; recorded in **Appendix E**.
   Fixed by adding jackson-core/FastDoubleParser and tomcat-embed-core/subcomponents as their own owner-decision
   rows (26–27) and recording the three on-list shaded findings as new facts, not new decisions, in Appendix E.
2. **MAJOR, the same blind spot in test- and build-scope artifacts — CONFIRMED, FIXED.** `REQ-014` reaches "ships
   **or builds with**," so the same embedded-content gap applies there too:
   - **`org.testcontainers:testcontainers:1.21.4`** (test scope) shades a large amount of third-party code under
     `org/testcontainers/shaded/`. Counted directly: **6,999** `org.bouncycastle.*` classes — ⚠️ the finding said
     "about 4,000"; measured, it is 6,999 — plus `com.google` (Guava), `com.fasterxml` (Jackson),
     `org.checkerframework`, `org.yaml` (SnakeYAML), `org.awaitility`, `com.trilead`, `org.zeroturnaround` (zt-exec)
     packages under the same shaded prefix. ⚠️ **Round 6 correction, per `I2` — verify, don't restate:** "6,999"
     conflated entries and `.class` files; measured precisely by base vs. `META-INF/versions` path, it is 5,621
     `.class` files (5,901 entries) at the base plus 1,098 versioned copies, i.e. **6,719 `.class` files / 6,999
     entries in total** — the entry total happens to match the number this bullet used for classes. The package
     list was also incomplete: `com.google.errorprone`/`com.google.thirdparty`, `com.github.dockerjava`
     (docker-java-core) and `org.apache.commons.{io,lang3}` also shade under the same prefix and are missing here
     — see the round 6 disposition and Appendix E.3 for the complete, twelve-package inventory. None of this is
     recorded anywhere in this report; the jar itself
     ships **no** `META-INF/LICENSE`/`NOTICE` for any of it (confirmed: only `MANIFEST.MF` and three
     `META-INF/services/` entries exist at the top level). WebFetch of the project's own `core/build.gradle` at
     tag `1.21.4` explains why: its `shadowJar` block explicitly **excludes**
     `META-INF/NOTICE`/`META-INF/NOTICE.txt`/`META-INF/LICENSE`/`META-INF/LICENSE.txt` from the shaded
     dependencies before repackaging — the licence provenance is deliberately stripped from what ships, not
     merely unread by this audit's tooling. The same file names the shaded coordinates, including
     `org.glassfish.main.external:trilead-ssh2-repackaged:4.1.2` (not upstream `com.trilead:trilead-ssh2` — a
     GlassFish repackage). Its own POM (fetched) has no `<licenses>` block, but its header states the content is
     "subject to the terms of either the GNU General Public License Version 2 only or the Common Development and
     Distribution License" (CDDL-1.0 OR GPL-2.0-with-Classpath-Exception) — a genuine new off-list dual licence,
     distinct from the BouncyCastle/Guava/Jackson/SnakeYAML/checkerframework content (all already allow-listed or
     already an owner row in substance). **New owner-decision row (28)** for the shaded
     `trilead-ssh2-repackaged`; the BouncyCastle/Guava/Jackson/checkerframework/SnakeYAML shaded copies are
     recorded in Appendix E as facts (same licences already on record for the unshaded coordinates), not new rows.
   - **`org.apache.commons:commons-compress:1.24.0`** (test scope) states in its own `META-INF/NOTICE.txt`
     (read in full) that "the files in the package `org.apache.commons.compress.archivers.sevenz` ... derived
     from the LZMA SDK, version 9.20 ... which has been placed in the public domain." Confirmed. **New
     owner-decision row (29)** — the same "Public Domain" case already open for `org.tukaani:xz` (rows 12/22),
     now also found embedded inside an otherwise-Apache-2.0 dependency.
   - **`jakarta.activation:jakarta.activation-api:2.1.4`** (test scope) — its `META-INF/NOTICE.md` (read in full)
     states the project's own licence as `SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause OR
     GPL-2.0-only with Classpath-exception-2.0` — **stronger** evidence for row 7's existing EDL-1.0→BSD-3-Clause
     mapping than the Eclipse Foundation web page round 2 cited (an OR-licence means the owner can pick the
     BSD-3-Clause leg outright), not a new problem. The same NOTICE also lists third-party content — Apache Ant,
     Apache Commons Lang, font-awesome, jsoup, JTHarness, normalize.css, SigTest — under EPL/W3C/public-domain/MIT
     terms. ⚠️ **Correction, verified this round:** the jar's own file listing (every non-`.class` entry read)
     contains only `.class` files, two `mailcap`/`mimetypes` default-config resources, and the licence/POM
     metadata itself — **none** of the NOTICE's named third-party content (font-awesome, jsoup, JTHarness,
     normalize.css, SigTest) is actually embedded in this shipped artifact; that NOTICE describes the upstream
     `jaf-api` source repository's own website/TCK tooling, not this jar's contents. Recorded in Appendix E as a
     bookkeeping fact, **not** a new owner-decision row — the finding's implication that this NOTICE content
     ships is not borne out by what the jar actually contains.
   - **`org.apache.poi:poi/poi-ooxml/poi-ooxml-lite:5.5.1`** and **`org.apache.xmlbeans:xmlbeans:5.3.0`**
     (build-tool only, in the candidate plugin's own class realm) each carry a `NOTICE` stating "This product
     contains W3C XML Schema documents. Copyright 2001-2003 ... World Wide Web Consortium." Measured directly:
     **`poi`/`poi-ooxml`/`poi-ooxml-lite` embed zero `.xsd`/`.dtd` files** (the NOTICE describes content elsewhere
     in the wider POI project, not these three jars); **`xmlbeans:5.3.0` embeds six** —
     `org/apache/xmlbeans/metadata/src/{XML,XMLSchema,ltgfmt,substwsdl,xmlconfig,xsdownload}.xsd` — read directly,
     these are the W3C-copyrighted schema documents themselves. **New owner-decision row (30)** for
     `xmlbeans`'s embedded W3C-licensed schema files, build-tool-only; no row added for poi/poi-ooxml/poi-ooxml-lite,
     whose NOTICE over-describes their own jar's contents in the same direction as jakarta.activation-api's did.
     ⚠️ **Round 8 struck the "no row for poi" half of this**: the jars hold no `.xsd` *files* but `poi-ooxml-lite`
     holds the schemas compiled and code-generated — row 40; see E.5.
3. **MAJOR, licence-name variants allow-listed on this audit's own authority — CONFIRMED, FIXED.** Every
   "Allowed (mapping only)" cell in Appendices C and D is this audit's own substance read, not an owner
   confirmation — and round 4's own prose (now corrected above) explicitly called one of them "not a new
   owner-decision item," which is the review rule violated. Re-inventoried mechanically across every literal
   licence string measured in this report (Appendices A, C, D and the "Allowed outright" section): **8** distinct
   Apache-2.0 strings — `Apache-2.0`, `Apache License, Version 2.0`, `The Apache Software License, Version 2.0`,
   `The Apache License, Version 2.0`, `Apache License 2.0`, `Apache 2.0`, `Apache 2` (`odfdom-java:0.12.0`,
   `RoaringBitmap:1.0.0`), `Apache Public License 2.0` (`plexus-build-api:0.0.7`) — not "six" (line 434) nor "six
   plus an eighth" (round 4's `plexus-build-api` sentence, now struck above): all eight are counted here at once,
   as one inventory. **3** MIT strings — `MIT` (bare, Appendix A's plugin output), `MIT License`
   (`slf4j-api:1.7.36`, `dexx:collection:0.7`, `slf4j-simple:1.7.36`), `The MIT License` (`checker-qual:3.37.0`).
   **2** BSD-2-Clause strings — `BSD-2-Clause` (`postgresql:42.7.11`), `BSD 2-Clause License`
   (`zstd-jni:1.5.5-11` **and** `zstd-jni:1.5.7-6`). The inconsistency the finding names is confirmed: row 16 of
   the owner table already sends `zstd-jni:1.5.5-11` to the owner for exactly this string, while Appendix D marked
   the identical string on `zstd-jni:1.5.7-6` "Allowed" — fixed by pointing D's row at row 16 (both versions, one
   decision) rather than treating the newer version as self-evidently clean. **Fix, applied:** a new **Appendix
   B** inventories all thirteen variant strings (eight Apache, three MIT, two BSD-2-Clause) with the coordinate(s)
   each was measured on and the equivalence evidence, explicitly for owner confirmation — Appendices C and D keep
   their per-coordinate "Allowed (mapping only)" cells as this audit's own read (useful triage), but a note now
   precedes each table pointing at Appendix B as the actual sign-off artifact, and the `plexus-build-api` sentence
   in M17(i) above no longer decides its own variant unilaterally.
4. **MINOR, four bookkeeping contradictions — CONFIRMED, FIXED** (in the sub-findings' own order):
   1. The plugin-realm coordinate count is corrected from "four" to **five** in the M17(i) round-4-disposition
      text above (`zstd-jni:1.5.5-11` is itself pulled by `maven-jar-plugin`'s plugin realm, not only a
      third-party-table row) — fixed above, and Appendix C's closing note (below) is fixed to match.
   2. Appendix C's `net.java.dev.jna:jna`/`jna-platform:5.17.0` rows cited "row 6/13" — row 6 is the *different*
      coordinate `jna:5.13.0` (M17(iii)'s example, a separate finding at a separate version); citing both numbers
      on the 5.17.0 rows reads as if either applies. **Fixed**: both rows now cite row 13 only.
   3. M17(i)'s residual-gap paragraph said "the same eight already confirmed here, plus 62 new" (= 70); the
      footer of Appendix D and the round-4 disposition text both correctly say "10 ... plus 62" (= 72, matching
      the measured `grep -c "Included:"` count). **Fixed**: "eight" → "ten" (the eight individually-verified
      M17(i) rows plus the licence-plugin's own self-reference and `plexus-i18n`, both already tabulated
      elsewhere, make ten already-known coordinates among the 72). ⚠️ **Round 6 correction to this fix itself, per
      `I2` — verify, don't restate:** the M17(i) table in fact has **nine** individually-verified rows, not eight
      — `plexus-i18n` is already one of the nine rows in that table (`nuiton-processor`, `sisu.inject`,
      `xalan:serializer`, `plexus-i18n`, `json`, `xz:1.10`, `curvesapi`, `java-rdfa`, `xml-apis`), not a tenth item
      added on top of it. The correct composition is **nine (including `plexus-i18n`) plus the licence-plugin's
      own self-reference = ten**, matching M17(i)'s own later phrasing ("the nine individually verified above
      plus the licence-plugin's own self-reference") rather than the "eight ... plus `plexus-i18n` ... plus the
      self-reference" composition this bullet stated. The total (ten) was always right; the arithmetic behind it
      was wrong here.
   4. The M17(i) table's `plexus-i18n` row still read "requires one more hop to confirm" after round 4 closed it
      to "no licence anywhere in the chain" — **fixed above**, at the row itself.
5. **MINOR, coverage-caveat names the wrong consumer of `dp-core`'s test-jar — CONFIRMED, FIXED.** Measured by
   reading every module `pom.xml` for a `<type>test-jar</type>` dependency on `dp-core`: only
   `backend/launcher/pom.xml` declares one (test scope, with its own comment explaining why). `arch-tests/pom.xml`
   depends on `dp-core` too, but on its ordinary `jar` artifact — confirmed with an offline
   `mvn -o -f backend/pom.xml -pl arch-tests dependency:tree -Dincludes=com.solace.sm:dp-core`, which resolves to
   `com.solace.sm:dp-core:jar:0.1.0-SNAPSHOT:test`, no `test-jar` classifier anywhere in the tree. **Fixed** at
   the coverage-caveat paragraph itself (below): "consumed by `arch-tests`" → "consumed by `launcher`."
6. **MINOR, the Maven runtime (and JDK) itself out of scope, unstated — CONFIRMED, FIXED.** There is no `mvnw`/
   `.mvn/` in this tree (confirmed: `ls` finds neither), so every command in this report — and any future
   customer build — runs whatever Maven is on `PATH`. Measured on this machine: Homebrew's Maven `3.9.16`, whose
   own `lib/` ships `org.eclipse.sisu.inject-1.0.0.jar`/`org.eclipse.sisu.plexus-1.0.0.jar` (`.license` file:
   Eclipse Public License v2.0), `guice-5.1.0-classes.jar`, `guava-33.6.0-jre.jar`, `jansi-2.4.3.jar`,
   `plexus-cipher-2.0.jar`, `plexus-sec-dispatcher-2.0.jar` (`.license` files: Apache License, Version 2.0, for
   all five). ⚠️ **Round 6 addition, per `I2` — this `ls` was itself incomplete:** the same directory also ships
   `javax.annotation-api-1.3.2.jar` (`.license`: "COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL) Version
   1.1") and `aopalliance-1.0.jar` (`.license`: "Public Domain") — both off-list, neither previously named; see
   the round 6 disposition and the "Other open questions" section below. ⚠️ Also measured, not previously noted
   anywhere in this report: `JAVA_HOME` is **unset** in this
   shell, and `mvn -version`'s reported JDK (Homebrew OpenJDK `26.0.2.1`) does not match the JDK `java -version`
   resolves on `PATH` (Homebrew OpenJDK `21.0.12.1`) — which JDK a build actually uses on this machine is itself
   ambiguous, before any licence question is asked about it. **Fixed** by adding this as a new, explicit open
   owner question (below): `REQ-014` says "builds with," and neither the Maven distribution's own bundled
   libraries nor the JDK have been asked about — Maven's own EPL-2.0 (Sisu) component is the same tier as
   `logback`/`jakarta.annotation-api` already on the owner table, just never previously in scope.

## Disposition of the sixth review (round 6, 2026-09-29)

A sixth-round review returned 3 MAJOR + 8 MINOR findings, against the completeness of the round-5 embedded-content
sweep, the disjunctive-vs-conjunctive reading of several dual-licensed artifacts, self-allow-listing language
surviving in the document's own framing text around the tables, and eight further bookkeeping gaps. **All eleven
are CONFIRMED** against direct, read-only inspection of the cached jars (`unzip -l`/`unzip -p`, no build
triggered), fresh offline `mvn -o dependency:tree`, `ls` of `~/.m2` and the Homebrew Maven distribution, and
WebFetch of Central POMs, project NOTICE files and upstream source at pinned tags (per `I2`, nothing asserted from
memory). No new online Maven resolution was used. Disposition, in the findings' own order:

1. **MAJOR, embedded-content sweep not systematic — CONFIRMED, FIXED.** Round 5 opened jars it already suspected;
   it never swept the full set. This round mechanically parsed every coordinate out of Appendix A.1 (116
   third-party), Appendix C (86 plugin-realm) and Appendix D (72 class-realm) — 258 distinct coordinates — located
   each one's jar in `~/.m2` (all 258 found; zero missing) and scanned it (`unzip -l | grep -iE
   "LICENSE|NOTICE|COPYING"`) for embedded licence files, then re-scanned every jar's file listing for `/shaded?/`
   or `shadow` path segments. Full methodology and the complete results are new **Appendix F**. Confirmed and
   fixed:
   - **`io.netty:netty-codec-base:4.2.13.Final`** ships `io/netty/handler/codec/base64/Base64*.class` and
     **`io.netty:netty-common:4.2.13.Final`** ships `io/netty/util/internal/ThreadLocalRandom*.class` — both with
     **zero** `LICENSE`/`NOTICE`/`COPYING` entries anywhere in the jar (measured: 0 of 0 across all 13 netty jars
     this reactor resolves, not just these two). WebFetched both source files at tag `netty-4.2.13.Final`:
     `Base64.java`'s header reads "Written by Robert Harder and released to the public domain";
     `ThreadLocalRandom.java`'s reads "Written by Doug Lea with assistance from members of JCP JSR-166 Expert
     Group and released to the public domain." Netty's own `NOTICE.txt` at that tag lists both under
     `license/LICENSE.base64.txt (Public Domain)` and `license/LICENSE.jsr166y.txt (Public Domain)`. **New
     owner-decision row 31** — the same "Public Domain" treatment already open for rows 12/21/29 (`xz`, `json`,
     `commons-compress`'s LZMA-SDK), now for content shipped inside `dp-core`/`launcher`/`demo-launcher`'s own
     compile-scope Netty jars.
   - **A second, larger undisclosed-shading case in the same jar, found by the sweep rather than named by the
     finding:** `netty-common` also shades `org.jctools` (138 `/shaded/` path entries) under
     `io/netty/util/internal/shaded/org/jctools/`, with the same zero-disclosure gap. WebFetched JCTools' own
     `LICENSE`: Apache License, Version 2.0 — on-list in substance, so **no new row**; recorded as a fact in
     Appendix F so a future re-run does not re-flag it.
   - Every other embedded-content item the finding named is confirmed and recorded as a **fact, no new row** (all
     resolve to a licence already allow-listed or already an owner row): `jackson-core:2.21.4`'s
     `META-INF/Schubfach-LICENSE` (MIT, Copyright Raffaello Giulietti); `spring-core:6.2.19`'s
     `META-INF/license.txt` "SUBCOMPONENTS" section (ASM 9.9.1 BSD-3-Clause — 37 shaded classes under
     `org/springframework/asm/`; CGLIB — 229 classes under `org/springframework/cglib/`; Objenesis — both
     Apache-2.0; **JavaPoet's text is present but zero JavaPoet classes are actually shaded in this jar**, the
     same "NOTICE over-describes" pattern round 5 found for `jakarta.activation-api`/`poi`); `byte-buddy:1.17.8`'s
     shaded ASM (102 classes) — **self-disclosed** via its own `META-INF/licenses/ASM` (BSD-3-Clause text), unlike
     Netty's undisclosed cases; `junit-jupiter-params:5.12.2`'s shaded `univocity-parsers` (292 classes,
     `META-INF/LICENSE-univocity-parsers.md`, Apache-2.0). One further self-disclosed case the finding did not
     name: **`org.vafer:jdependency:2.10`** (plugin-realm, pulled by `spring-boot-maven-plugin`'s buildpack
     support) shades Apache Commons IO under `org/vafer/jdeb/shaded/commons/io/`, disclosed in its own
     `META-INF/NOTICE.txt` — Apache-2.0, on-list, fact only.
   - **False positives dismissed** (matched the `/shaded?/|shadow` grep but are first-party code, not third-party
     shading): `spring-aop`/`spring-context`'s own `ShadowMatch*`/`ShadowingClassLoader` AOP/instrumentation
     classes; `xercesImpl`'s own `Shadowed{GrammarPool,SymbolTable}` classes; `poi`/`poi-ooxml`'s own PowerPoint
     drop-shadow-effect classes (`Shadow`, `XSLFShadow`); `maven-shade-plugin`'s own
     `org.apache.maven.plugins.shade` package name.
   - ~~**Round 4's `commons-compress` finding does not generalise across versions**: only the test-scope
     `commons-compress:1.24.0` (row 29) carries the LZMA-SDK public-domain `NOTICE.txt` text; the three
     build-tool-only versions in Appendix D (`1.26.1`, `1.27.1`, `1.28.0`) carry a plain ASF `NOTICE.txt` with no
     such text — confirmed by reading each. No new row for those three.~~ ⚠️ **Struck in round 8.** The premise
     (the words are missing from the NOTICE) is true; the inference (so the content is not there) is not. Upstream
     deleted the paragraph in a release-prep commit that touches no source file, and the `sevenz` package is
     shipped unchanged in size (row 29). Also corrected: the three versions are not all "in Appendix D" —
     `1.26.1` and `1.27.1` are in Appendix C (`maven-jar-plugin:3.4.2` and `spring-boot-maven-plugin:3.5.16`), only
     `1.28.0` is in Appendix D. Row 29 now lists all six coordinates.
   - **Total: 31 rows** (30 → 31; row 31 new this round).
2. **MAJOR, dual-licence rows read as conjunctive when the artifact's own text is disjunctive — CONFIRMED,
   FIXED.** Read directly (not from memory): `jakarta.annotation-api:2.1.1`'s `META-INF/NOTICE.md` states
   "SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0" verbatim. `jna:5.13.0`'s and
   `jna:5.17.0`/`jna-platform:5.17.0`'s `META-INF/LICENSE` each state "SPDX-License-Identifier: Apache-2.0 OR
   LGPL-2.1[-or-later]" and, in prose, "You can freely decide which license you want to apply to the project."
   Rows 2, 6 and 13 previously read "the LGPL/GPL half is real regardless of the [other] half" — that is the AND
   reading; the artifacts' own text is OR, so the licensee may elect the allow-listed leg outright. **Fixed**:
   rows 2, 6, 13 now quote the OR statement verbatim as evidence and record that electing the allow-listed leg is
   available, though `REQ-014`'s "record where introduced" still asks that election to be an explicit, recorded
   owner act, not a default. This is consistent with how row 7 (`jakarta.activation-api`, EPL-2.0 OR BSD-3-Clause
   OR GPL-2.0-only-with-CPE) was already read — the inconsistency was specific to rows 2/6/13. The
   "Refused-family licences found" section's scope is also widened: it previously said "zero licence names among
   the other 115 third-party artifacts... the open question is scoped to this one artifact's second declared
   licence" — false once row 28 (`trilead-ssh2-repackaged`, CDDL-1.0 OR GPL-2.0-only WITH Classpath-exception-2.0,
   already recorded with OR framing) and row 7's own GPL-2.0-only-with-CPE leg are counted. **Fixed**: the open
   question now applies to every row carrying a GPL/AGPL name on any leg of a disjunctive licence (rows 2, 7, 28),
   not just row 2.
3. **MAJOR, self-allow-listing survives in the framing text around the tables — CONFIRMED, FIXED.** The
   owner-table footer (previously: "the 25 rows above... resolves cleanly to Apache-2.0, MIT, or BSD-2/3-Clause
   (possibly under a non-canonical spelling that needs only a `licenseMerges` mapping, not an owner decision)")
   contradicts Appendix B's own "none of the above is allow-listed by this audit," undercounts the table (30 rows
   before this round, 31 after), and pre-decides every spelling mapping on the audit's own authority — exactly
   what Appendix B exists to stop. **Fixed**: rewritten to point at Appendix B for every non-canonical spelling
   and corrected to 31. The "Allowed outright" section's "six different literal strings" phrasing (also repeated
   in M17(iv)) is scoped correctly for the 116-artifact third-party report alone (mechanically re-counted from
   Appendix A.1: exactly six Apache-2.0 strings appear there) but reads, unqualified, as if it were the
   reactor-wide count — Appendix B's reactor-wide sweep (which also covers the plugin realms in Appendices C/D)
   found eight. **Fixed**: both places now say "six, within the third-party report; Appendix B's reactor-wide
   count is eight," and the section is retitled from "Allowed outright" to "Candidate for the allow-list (owner
   confirmation via Appendix B, not decided here)". Appendix D's footer ("62 new this round — all confirmed
   Allowed") and Appendix C's closing text are reworded the same way: these resolve **in substance** to an
   allow-listed licence and zero required a new owner-decision row, but the spelling itself still needs Appendix
   B's sign-off before `licenseMerges` may treat it as such.
4. **MINOR, `tomcat-embed-core` subcomponent counts wrong — CONFIRMED, FIXED.** Measured directly:
   `jakarta/servlet/resources/` holds **45 `.xsd` + 6 `.dtd` = 51 files** (not "51 `.xsd` files"); the one file
   elsewhere is `org/apache/tomcat/util/modeler/mbeans-descriptors.dtd` (a `.dtd`). `META-INF/LICENSE`'s "APACHE
   TOMCAT SUBCOMPONENTS" section names exactly **21** files under CDDL-1.0 and **14** under EPL-2.0 (**35** total,
   not "these 51... are separately licensed") — two of the 14 EPL names carry a typo in Tomcat's own upstream
   file (`web-commonn_5_0.xsd`/`_6_0.xsd`, double "n", vs the shipped `web-common_5_0.xsd`/`_6_0.xsd`), worth
   recording since a future exact-string re-run would miss them. The remaining files (`datatypes.dtd`,
   `XMLSchema.dtd`, `xml.xsd` and 13 older J2EE-era schemas/DTDs, all measured directly) each carry the standard
   ASF "Licensed to the Apache Software Foundation... Apache License, Version 2.0" header comment — Tomcat's own
   Apache-2.0 content, not part of the CDDL/EPL subcomponent set. Row 27's evidence (Appendix E.1) is corrected to
   these exact counts.
5. **MINOR, `testcontainers` BouncyCastle count and shaded-package inventory both wrong — CONFIRMED, FIXED.**
   Measured directly (not "about 4,000" nor the finding's own "6,999"):
   `org/testcontainers/shaded/org/bouncycastle/` holds **5,621 `.class` files (5,901 entries including
   directories)** at the jar's base classes; a further **1,098** versioned copies live under
   `META-INF/versions/{9,11,15}/.../bouncycastle/`, for **6,719 `.class` files (6,999 entries) in total**. The
   shaded-package inventory is completed by listing every top-level package under `org/testcontainers/shaded/`
   and counting classes in each: `com.fasterxml` (Jackson, 677), `com.github.dockerjava` (docker-java-core, 284),
   `com.google.common` (Guava, 2016), `com.google.errorprone`/`com.google.thirdparty` (27 + 3), `com.trilead`
   (141, the GlassFish repackage — row 28), `org.apache.commons.{io,lang3}` (674), `org.awaitility` (76),
   `org.bouncycastle` (5,621), `org.checkerframework` (369), `org.hamcrest` (108), `org.yaml` (SnakeYAML, 225),
   `org.zeroturnaround` (zt-exec, 52 — WebFetched its POM's licence comment: Apache License, Version 2.0). Every
   one resolves to a licence already allow-listed or already an owner row (BouncyCastle: row 3; trilead: row 28)
   — **no new rows**, but both the round-4/5 disposition text and Appendix E.3's list are corrected to this
   single, complete, mutually consistent inventory (the disposition previously named `com.trilead` but not
   docker-java; E.3 named docker-java but not `com.google.errorprone`/`thirdparty`/`commons` — both now list all
   twelve packages).
6. **MINOR, `trilead-ssh2-repackaged` evidence taken from the wrong POM level — CONFIRMED, FIXED.** WebFetched
   the full parent chain: `trilead-ssh2-repackaged:4.1.2`'s own POM has no `<licenses>` block and its `<parent>`
   is `org.glassfish.main.external:nucleus-external:4.1.2`, which also has none and whose own `<parent>` is
   `org.glassfish.main:glassfish-nucleus-parent:4.1.2` — **this** POM declares
   `<license><name>CDDL + GPLv2 with classpath exception</name><url>http://glassfish.java.net/nonav/public/CDDL+GPL.html</url>...</license>`
   verbatim, exactly the audit's own stated POM-and-parent-chain method. Row 28 and Appendix E.3 are corrected to
   cite this declared `<licenses>` element instead of the artifact's own POM header comment (which licenses the
   POM document, not the shaded code); the OR reading already recorded for this row is unchanged.
7. **MINOR, `docker-java-transport-zerodep`'s "no embedded LICENSE" claim is false — CONFIRMED, FIXED.** Measured:
   the jar carries `META-INF/LICENSE` + `META-INF/NOTICE` (Apache HttpClient) and `META-INF/LICENSE.txt` +
   `META-INF/NOTICE.txt` (Apache Commons Codec), plus `META-INF/DEPENDENCIES` — five files, not zero. Appendix
   E.2's bullet is corrected; the conclusion (Apache-2.0, already allow-listed, no new row) is unchanged since the
   shaded content is `org.apache.hc` (client5/core5) and `commons-codec` specifically, not "`org.apache.commons`
   in general."
8. **MINOR, Maven-distribution inventory incomplete and self-approving — CONFIRMED, FIXED.** `ls` of
   `/opt/homebrew/Cellar/maven/3.9.16/libexec/lib/` shows two more off-list `.jar`+`.license` pairs not previously
   named: `javax.annotation-api-1.3.2.jar` (`.license`: "COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL)
   Version 1.1") and `aopalliance-1.0.jar` (`.license`: "Public Domain"). Both are added to the open
   Maven-distribution question. The bullet's "no decision [needed]" framing for the Apache-2.0-licensed Maven
   libraries is also corrected — that is the audit deciding on the owner's behalf; reworded to "no decision
   *requested*," pending the owner's ruling on whether the Maven distribution is in scope at all.
9. **MINOR, `maven-deploy-plugin`/`maven-site-plugin`'s transitive realm was never walked — CONFIRMED, FIXED.**
   Measured: `~/.m2/repository/org/apache/maven/plugins/maven-{deploy,site}-plugin/{3.1.4,3.12.1}/` holds only the
   plugin's own `.jar` — no `.pom` file is cached for either, confirming `dependency:resolve-plugins` could only
   have listed their own coordinate, never a transitive tree (consistent with Appendix C's own note that these
   two POMs were fetched by WebFetch, not read from a resolved cache). Appendix C's header now states this scope
   limit explicitly: the two plugins' own transitive dependencies are not walked by this audit — low risk because
   neither plugin runs under `verify`, but unaudited rather than confirmed clean.
10. **MINOR, M17 status text internally inconsistent — CONFIRMED, FIXED.** M17(i)'s residual-gap paragraph says
    "the same **ten** already confirmed (the **nine** individually verified above plus the licence-plugin's own
    self-reference)"; the round-5 disposition's bookkeeping item 4.3 says "the **eight** individually-verified
    M17(i) rows plus the licence-plugin's own self-reference **and** `plexus-i18n`... make ten" — two different
    compositions (9+1 vs 8+1+1) reaching the same total by coincidence, because the M17(i) table in fact has
    **nine** rows including `plexus-i18n` (counted directly: `nuiton-processor`, `sisu.inject`,
    `xalan:serializer`, `plexus-i18n`, `json`, `xz:1.10`, `curvesapi`, `java-rdfa`, `xml-apis`). **Fixed**: the
    round-5 disposition's item 4.3 text is corrected in place, below, to match M17(i)'s own composition (nine,
    `plexus-i18n` included, plus the self-reference).
11. **MINOR, M17(v)'s coverage caveat still incomplete — CONFIRMED, FIXED.** An offline `mvn -o -f
    backend/pom.xml -pl dp-core dependency:tree -Dincludes=com.solace.sm:mesh-events` (this round) resolves
    `com.solace.sm:mesh-events:test-jar:tests:0.1.0-SNAPSHOT:test` — a second reactor-internal test-jar edge the
    aggregation-time warning's method cannot see, alongside `dp-core`'s own test-jar (consumed by `launcher`,
    already on record). `dp-core/pom.xml`'s own comment (lines 126–135) explains the dependency: it needs
    `mesh-events`' test sources. **Fixed** at the coverage-caveat paragraph: both reactor-internal test-jar edges
    (`mesh-events` → `dp-core`, `dp-core` → `launcher`) are now named.

## Disposition of the seventh review (round 7, 2026-09-30)

A seventh-round review returned 4 MAJOR + 3 MINOR findings: one reading rule applied to three rows but not the
fourth (logback), and three places where the embedded-content sweep of rounds 5–6 was not actually exhaustive
(a licence file inside a jar, a native library inside a jar, and code shaded under a name the round-6 heuristic did
not look for). **All seven are CONFIRMED** against direct, read-only inspection of the cached jars (`unzip`, Python
`zipfile`, `strings` on extracted native libraries, no build triggered), and against WebFetch of upstream licence
text and source headers at pinned tags (per `I2`, nothing asserted from memory). **No new online Maven resolution was
used.** The only Maven commands this round were offline read goals that write no `target/`:
`mvn -o -f backend/pom.xml dependency:resolve-plugins -DoutputFile=<scratchpad>` (whole reactor, to re-measure which
plugin pulls which coordinate) and `mvn -o ... dependency:tree -Dincludes=...`. Disposition, in the findings' own
order:

1. **MAJOR, row 1 (logback) still read as AND — CONFIRMED, FIXED.** The round-6 OR-vs-AND fix reached rows 2, 6 and
   13 only. Logback's own `LICENSE.txt` at tag `v_1.5.34` (WebFetched) reads: "This program and the accompanying
   materials are dual-licensed under either the terms of the Eclipse Public License v2.0 as published by the Eclipse
   Foundation or (per the licensee's choosing) under the terms of the GNU Lesser General Public License version 2.1
   as published by the Free Software Foundation." That is OR. Neither logback jar ships a `LICENSE`/`NOTICE`
   (measured: `META-INF/` holds only `MANIFEST.MF` and `INDEX.LIST` in both), and `logback-parent-1.5.34.pom`
   lists `EPL-2.0` and `LGPL-2.1-only` as two `<license>` elements with no connective — which is why the POM-only
   method could not tell. **Fixed** at row 1, at the evidence-trail table's logback and `jakarta.annotation-api`
   rows, at the `jna:5.13.0` evidence row ("the LGPL half is real even if the Apache half also is") and at the
   `jna:5.17.0`/`jna-platform:5.17.0` M17(i) bullet ("the LGPL half is real regardless of the Apache half") — the
   last two were the AND framing round 6 said it had retired. Rows 1, 2, 6 and 13 now read the same way: the owner is
   asked to **elect** the allow-listed (or already-listed) leg, as an explicit recorded act. The rest of the table was
   re-read for the same defect: row 7 (`jakarta.activation-api`) and row 28 (`trilead`) were already OR; row 8
   declares one licence; row 9 (ArchUnit: Apache-2.0 for its own code, BSD for the ASM-derived part) and row 25
   (`xml-apis`: three licences for three different bodies of content) are genuinely conjunctive.
2. **MAJOR, `plexus-xml`/`plexus-utils` embed the Indiana University Extreme! Lab licence — CONFIRMED, FIXED (new
   row 32).** Read in full from the jars: `licenses/extreme.indiana.edu.license.TXT` is "Indiana University Extreme!
   Lab Software License Version 1.1.1" — a five-condition BSD-like text with an end-user-acknowledgment clause
   (condition 3) and two naming clauses (4, 5), the same Apache-1.1 shape row 14 (`jdom2`) already sends to the
   owner. Measured: `org/codehaus/plexus/util/xml/pull/MXParser.class` is present in `plexus-xml:3.0.1` and
   `plexus-utils:3.5.1` (6 classes under `xml/pull/`), and **absent** from `plexus-utils:4.0.1` and `4.0.2` (0
   classes there) — all four jars nevertheless carry the same three licence files, byte-identical (md5 compared).
   `MXParser.java` at `plexus-utils-3.5.1` (WebFetched) opens "Copyright (c) 2003 Extreme! Lab, Indiana University.
   All rights reserved. This software is open source. See the bottom of this file for the licence." Consumers,
   re-measured from an offline whole-reactor `dependency:resolve-plugins`: `plexus-xml:3.0.1` ←
   `maven-compiler-plugin:3.14.1` and `maven-install-plugin:3.1.4`; `plexus-utils:3.5.1` ←
   `maven-resources-plugin:3.3.1` and `spring-boot-maven-plugin:3.5.16`; `plexus-utils:4.0.1` ← `maven-clean-plugin`,
   `maven-install-plugin`, `maven-jar-plugin`; `plexus-utils:4.0.2` ← `maven-compiler-plugin` — every one is
   build-with on every build. The same directory's other two files are on-list and recorded as facts:
   `javolution.license.TXT` (Javolution, 2-condition BSD, `BSD-2-Clause` in substance) and `thoughtworks.TXT`
   (CruiseControl, 3-condition BSD with a no-endorsement clause, `BSD-3-Clause` in substance). Row 32 lists all four
   coordinates so that no version is left to a silent pass; the two `4.0.x` versions are marked as "licence files
   present, covered class absent" so the owner can rule on them separately. Appendices C and D's "Allowed (mapping
   only)" cells for these four coordinates are corrected to point at row 32. **This finding also exposed a counting
   error in Appendix F:** it says 17 jars had three or more licence-ish entries and that "the rest are enumerated";
   re-measured over the same 258 coordinates with the same `LICENSE|NOTICE|COPYING` pattern, **18** do, and the
   round-6 enumeration was never written. Appendix F now carries the full 18-row table (Pass 1 table).
3. **MAJOR, `zstd-jni` embeds a dual-licensed native library — CONFIRMED, FIXED (row 16 extended in place; the
   coordinates are unchanged, so no new row).** `zstd-jni:1.5.5-11` and `1.5.7-6` carry 17 and 18 native libraries
   respectively, and no `LICENSE`/`NOTICE`/`COPYING` at all (measured: the only entries that are neither classes nor
   libraries are `META-INF/MANIFEST.MF`). Extracting `darwin/aarch64/libzstd-jni-<v>.dylib` (773 KB / 631 KB) and
   running `strings` shows zstd internals — `HUF_writeCTable error`, `FSE_normalizeCount error with offcodeCount`,
   `ZSTD_trace_compress_begin` — so zstd is compiled in statically. zstd's own `lib/zstd.h` at tags `v1.5.5` and
   `v1.5.7` (both WebFetched, identical): "This source code is licensed under both the BSD-style license (found in the
   LICENSE file in the root directory of this source tree) and the GPLv2 (found in the COPYING file in the root
   directory of this source tree). You may select, at your option, one of the above-listed licenses." zstd's `LICENSE`
   at `v1.5.5` (WebFetched) is a 3-condition BSD text with a no-endorsement clause. The binding's own `LICENSE`
   (`luben/zstd-jni` at `v1.5.5-11`, WebFetched) is a separate BSD text, and the POMs say `BSD 2-Clause License` — which
   describes the JNI binding, not the library statically linked into it. **Two consequences, both recorded at row
   16:** (a) the owner's decision is no longer only a spelling mapping, it is an election between a BSD leg and a GPL-2.0
   leg for the embedded library; (b) the GPL-leg open question ("Refused-family licences found") now covers row 16
   as well. **Root cause, fixed as a method change:** Appendix F's two passes looked for licence-file names and
   `shaded`/`shadow` path segments and never inspected native libraries. A new **Pass 3** (below) enumerates them;
   measured over the same 258 jars: `jna:5.13.0` 26 (19 `.so`, 3 `.dll`, 2 `.jnilib`, 2 `.a`), `jna:5.17.0` 28,
   `zstd-jni` 17/18, `netty-transport-native-epoll` (`-linux-x86_64` classifier jar) 1, `byte-buddy-agent` 2
   (`attach_hotspot_windows.dll`). The jna libraries are covered by JNA's own disjunctive `META-INF/LICENSE` (rows 6,
   13 — ⚠️ round 8: except the statically embedded libffi, an MIT-notice fact, see F.4); `byte-buddy-agent` and the Netty epoll library carry no licence or copyright string beyond a GCC compiler
   comment in the latter (`strings` on both), and are Apache-2.0 first-party code of their projects — recorded as
   facts, not rows.
4. **MAJOR, `testcontainers`' shaded Guava and SnakeYAML contain non-Apache content — CONFIRMED, FIXED (new rows 33
   and 34).** E.3 said the shaded Guava "resolves to licences already allow-listed" and recorded SnakeYAML as simply
   on-list; the round-6 standard (row 31: Doug Lea's Public Domain `ThreadLocalRandom` inside Netty) was not applied.
   Measured in `testcontainers-1.21.4.jar`: `org/testcontainers/shaded/com/google/common/{cache,hash}/` each hold
   `Striped64`, `Striped64$1`, `Striped64$Cell` and `LongAdder` (8 classes). `core/build.gradle` at `1.21.4` names the
   shaded Guava as `33.3.1-jre` and SnakeYAML as `1.33` (WebFetched). Guava `v33.3.1`'s `cache/Striped64.java`,
   `cache/LongAdder.java`, `hash/Striped64.java` and `hash/LongAdder.java` (all four WebFetched) each open "Written by
   Doug Lea with assistance from members of JCP JSR-166 Expert Group and released to the public domain, as explained
   at http://creativecommons.org/publicdomain/zero/1.0/". **Row 33.** The jar also contains
   `org/testcontainers/shaded/org/yaml/snakeyaml/external/biz/base64Coder/Base64Coder.class`; SnakeYAML `1.33`'s
   `Base64Coder.java` (WebFetched from the `snakeyaml-1.33` tag) is headed "This module is multi-licensed and may be
   used under the terms of any of the following licenses: EPL, Eclipse Public License, V1.0 or later; LGPL, GNU Lesser
   General Public License, V2.1 or later; GPL, GNU General Public License, V2 or later; AL, Apache License, V2.0 or
   later; BSD, BSD License" — a disjunctive licence with a GPL leg and an unqualified-BSD leg. **Row 34**, and the
   GPL-leg question now covers it. The shipped `snakeyaml:2.4` does **not** contain `Base64Coder` (measured: its
   `org/yaml/snakeyaml/external/` holds only `com/google/gdata/util/common/base/{Escaper,PercentEscaper,UnicodeEscaper}`,
   whose header, WebFetched from tag `snakeyaml-2.4`, is "Copyright (c) 2008 Google Inc. ... Licensed under the Apache
   License, Version 2.0") — so the shipped SnakeYAML is unaffected. Also checked: the shaded Jackson inside
   `testcontainers` contains no `doubleparser`/`fdp` classes (0 matches), so row 26's Boost content is not
   duplicated there.
5. **MINOR, row 28 still cited the wrong POM level — CONFIRMED, FIXED.** Round 6 corrected Appendix E.3 but not the
   row. Row 28 now cites `org.glassfish.main:glassfish-nucleus-parent:4.1.2`'s declared `<license><name>CDDL +
   GPLv2 with classpath exception</name>` (re-fetched this round: `repo1.maven.org/.../glassfish-nucleus-parent-4.1.2.pom`,
   `<parent>` `net.java:jvnet-parent:5`) and says why the artifact's own POM header comment is not the evidence.
6. **MINOR, the "verbatim" FastDoubleParser quotation was inaccurate — CONFIRMED, FIXED.** Re-read in full:
   `META-INF/FastDoubleParser-ThirdParty-LICENSE` has three sections. (1) `# FastDoubleParser` — "This is a Java port
   of Daniel Lemire's fast_float project", `Copyright (c) Daniel Lemire`, **Boost Software License - Version 1.0 -
   August 17th, 2003** (the content row 26 asks the owner about). (2) "Third-party license for fast_float, Copyright
   (c) 2021 The fast_float authors. **MIT License.**" (3) "Third-party license for bigint, Copyright 2020 Tim Buktu.
   **2-clause BSD License.**" ("We only use those portions of the bigint project that can be licensed under 2-clause
   BSD License."). Round 5's disposition and E.1 attributed the Boost text to `fast_float` ("Third-party license for
   fast_float ... Boost Software License") and said the port "is itself under the Boost Software License" as a
   description of the file — the file attributes Boost to the port's own section and MIT to `fast_float`. Row 26
   stands (Boost is present and off-list); its evidence, E.1 and the round-5 quotation are corrected, and the MIT and
   BSD-2-Clause sections are recorded as on-list facts.
7. **MINOR, the sweep was described as complete but missed relocations under other names, and dropped most of
   Netty's NOTICE — CONFIRMED, FIXED (new rows 35 and 36, both found this round; Appendix F rewritten).**
   - `com.tngtech.archunit:archunit:1.5.0` (test) carries **2,009** classes under `com/tngtech/archunit/thirdparty/`:
     Guava (1,967 under `com/google/common` + 4 under `com/google/thirdparty`) and ASM (38 under `org/objectweb/asm`),
     with one licence file, `thirdparty/org/objectweb/asm/asm.license`. Unlike `testcontainers`, its Guava contains
     **no** `Striped64`/`LongAdder` class (only `cache/LongAddables$JavaUtilConcurrentLongAdder`, which delegates to the
     JDK), so it carries none of row 33's Public Domain content. Recorded in Appendix F; row 9 already covers the ASM
     licence. The `/shaded?/|shadow` heuristic could never see `thirdparty/`.
   - Netty's `NOTICE.txt` at `4.2.13.Final` (WebFetched) lists 31 third-party components, 16 of them with "contains"
     wording. Only three were carried over (the two Public Domain items and JCTools). The full list is now in Appendix F; the per-component reading against the
     13 Netty jars this reactor resolves: JSR-166 and Robert Harder's Base64 (Public Domain — row 31); JCTools (Apache,
     already recorded); jbzip2 (MIT), libdivsufsort (MIT), jfastlz (MIT) — `netty-codec-compression` holds the matching
     `Bzip2*`/`Bzip2DivSufSort`/`FastLz*` classes, on-list facts; Apache Harmony, Apache Commons Lang, SLF4J (MIT) —
     "contains a modified portion of" with no separately named class, on-list facts; HPACK, Protocol Buffers, dnsinfo.h,
     Quiche, boringssl, Maven Wrapper — their carriers (`netty-codec-http2`, `netty-codec-protobuf`,
     `netty-resolver-dns-native-macos`, the QUIC modules, the source tree) are **not** among the 13 resolved Netty jars
     (measured), so nothing to record. **Webbit** ("contains a modified portion of", licence label "BSD License"):
     `WebSocket08FrameDecoder.java` at `4.2.13.Final` (WebFetched) says "This code was forked from webbit and
     modified", and `netty-codec-http` ships that class (measured). `license/LICENSE.webbit.txt` (WebFetched) is
     a 3-condition BSD text (copyright Joe Walnes, Aslak Hellesøy and contributors, with a no-endorsement clause) but
     the NOTICE label is the unqualified "BSD License" — the same class of string as rows 9/15/23/24. **Row 35.**
   - **A second, unrelated wrapper case found while widening the heuristics: `org.apache.servicemix.bundles:
     org.apache.servicemix.bundles.jzlib:1.1.3_2`** (shipped: compile in `dp-core`, `launcher`, `demo-launcher`, via
     `com.solacesystems:sol-jcsmp:10.30.1`; test in `arch-tests`) is an OSGi wrapper whose 26 classes are all
     `com/jcraft/jzlib/*` — JCraft's JZlib 1.1.3, not ServiceMix code. Its POM, `META-INF/LICENSE`, `NOTICE` and
     `Bundle-License` all say Apache-2.0 (which is why Appendix A listed it as such); its own `META-INF/DEPENDENCIES`
     discloses "JZlib ... com.jcraft:jzlib:jar:1.1.3 License: BSD (http://www.jcraft.com/jzlib/LICENSE.txt)", and
     `ymnk/jzlib` at tag `1.1.3` `LICENSE.txt` (WebFetched) is a 3-condition BSD text (copyright ymnk, JCraft, Inc.,
     2000–2011, with a no-endorsement clause). It moves from the "candidate for the allow-list" count to **row 36**. The
     other 257 coordinates were checked for the same pattern by reading every `META-INF/DEPENDENCIES` `License:` line
     (no other wrapper whose embedded content differs from its declared licence); this is a heuristic, not a proof.
   - **What the sweep still cannot see, stated plainly:** it can find (a) licence files inside jars, (b) shading under
     the path segments and class names listed in Appendix F, (c) native libraries by extension and file magic, (d)
     wrappers that disclose themselves in `META-INF/DEPENDENCIES`. It cannot find code copied into a jar's own
     packages with no licence file and no distinguishing path, and it does not read class or source headers except
     where a heuristic pointed at one. The round-6 conclusion "no undisclosed off-allow-list shading was found beyond
     row 31" is replaced by a claim scoped to those four methods.
8. **MINOR, the plan's M17 status disagrees with this report — CONFIRMED, NOT FIXABLE HERE.** The plan
   (`docs/plans/backend/010-dp-data-model-and-remote-flow.md`, M17 row) still reads "partially filled 2026-09-29
   (downgraded in round 3 — (i)'s wider 72-artifact class realm is a stated residual gap)". This report's M17 header
   says "upgraded back to filled in round 4" because Appendix D walks all 72 class-realm members individually. The
   report is the one that is right; the plan's row is stale. This task may edit only the audit report, so the plan row
   is left to the integrator (the paragraph returned with this round says so).

**Total: 36 rows** (31 → 36: rows 32–36 new; rows 1, 16, 26 and 28 corrected in place). Nothing here is allow-listed;
every new row is an owner decision, evidence as cited. ⚠️ **Round 8 takes this to 40** (rows 37–40 new; rows 29 and 30 widened in place) — see the round 8 disposition.

```bash
source <scratchpad>/tc-env.sh &&
mvn -f backend/pom.xml org.codehaus.mojo:license-maven-plugin:2.7.1:aggregate-add-third-party \
  -Dlicense.excludedScopes=system \
  -Dlicense.outputDirectory=<scratchpad>/licence-audit \
  -Dlicense.sortArtifactByName=true
# BUILD SUCCESS; <scratchpad>/licence-audit/THIRD-PARTY.txt: "Lists of 123 third-party dependencies."
```

Everything below is measured from that run, from `mvn -o -f backend/pom.xml dependency:tree -Dincludes=<gav>`
(scope confirmation), and from WebFetch/curl of the plugin's own source and Maven Central POMs — no licence name
below is asserted from memory (`I2`).

---

## Disposition of the eighth review (round 8, 2026-09-30)

An eighth-round review returned 4 MAJOR + 7 MINOR findings. **All eleven are CONFIRMED** against direct, read-only
inspection of the cached jars (Python `zipfile`, `nm -D`/`strings`), `gh api` reads of upstream commits, tags and
files (`apache/commons-compress`, `google/guava`, `java-native-access/jna`, `apache/poi`, `e-Contract/eid-applet`),
and `curl` of the live W3C and publicsuffix.org files (per `I2`, nothing asserted from memory). **No new online
Maven resolution and no `target/` write**: the only Maven commands were offline read goals,
`mvn -o -f backend/pom.xml dependency:resolve-plugins -DoutputFile=<scratchpad> -DappendOutput=true` and
`dependency:tree -DoutputFile=<scratchpad> -DappendOutput=true` over the whole reactor. Beyond the eleven, the review's
own method (read the data, not the licence file) was applied to **all 261 jars** instead of the reviewer's four
examples (F.8); no hit outside the reviewer's findings produced a new row of its own (the extra items — `BesselJ`, the eID Applet lineage, Guava's generated list — ride inside rows 37, 38 and 40), and F.8 records what each hit was.
Disposition, in the findings' order:

1. **MAJOR, MPL-2.0 Public Suffix List inside `httpclient5:5.5.2` and `httpclient:4.5.14` — CONFIRMED, FIXED (new
   row 37).** `org/publicsuffix/list/effective_tld_names.dat` (328,040 bytes) and `mozilla/public-suffix-list.txt`
   (245,071 bytes) both open "This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0";
   both jars' `META-INF/LICENSE` (11,358 bytes) and `NOTICE` (174 bytes) are plain Apache. Consumers re-measured:
   `httpclient5:5.5.2` ← `spring-boot-maven-plugin:3.5.16` (bound only in `launcher` and `demo-launcher`; absent from
   both exec jars, 0 matches), `httpclient:4.5.14` ← `license-maven-plugin:2.7.1` directly. Also searched all 261 jars
   for the same list: `docker-java-transport-zerodep` shades HttpClient 5 classes but ships **no** list file; Guava's
   generated `PublicSuffixPatterns` (a compiled trie of the same list, Apache header, list not named) sits in
   `testcontainers` and `archunit` — carried in row 37 as a flagged item rather than dismissed. `grep -iwE
   'MPL|Mozilla|publicsuffix'` over the audit now returns row 37, this item and the Appendix C/D cells.
2. **MAJOR, `commons-math3:3.6.1` appended Minpack section — CONFIRMED, FIXED (new row 38).** The 24,038-byte
   `META-INF/LICENSE.txt` has 12,670 bytes after the Apache boilerplate. Read in full: Minpack (University of
   Chicago; condition 3 is an end-user-acknowledgment clause, so the row 14/32 shape), and — **not in the
   review** — `BesselJ`, which the file itself says derives from netlib Fortran with "no license or copyright
   statement included with the original Fortran sources". The Hairer, Mersenne Twister, Kuo/Joe and SciPy texts are
   BSD-2/3 and are recorded as facts. Consumer chain measured: `license-maven-plugin` → `poi-ooxml` → `poi` →
   `commons-math3`; absent from the reactor's `dependency:tree`. Root cause and the size check are in F.8(f): **18**
   jars have an appended section (the review said 17); `commons-math3` was the only one never read.
3. **MAJOR, `spring-web:6.2.19` Public Domain `mime.types` — CONFIRMED, FIXED (new row 39).** 26,975 bytes, header
   "has been placed in the public domain for unlimited redistribution"; `license.txt` (15,563 bytes) SUBCOMPONENTS
   names ASM, CGLIB, JavaPoet, Objenesis only; `notice.txt` names none. Scope re-measured: compile in
   `console-gateway`, `launcher`, `demo-launcher` and present in both exec jars' `BOOT-INF/lib`. F.2's "no such
   component named" is corrected in place to say it read the licence text, not the data files. The other `mime.types`
   / `mimetypes` files in the 261 jars are `jakarta.activation-api` (EDL header, row 7/8 family) and `spring-boot`'s
   `mime-mappings.properties` (Apache header).
4. **MAJOR, LZMA-SDK finding does generalise across `commons-compress` versions — CONFIRMED, FIXED (row 29 widened
   to six coordinates; no new row number).** `gh api repos/apache/commons-compress/commits/5ecccd9838f0`: "Prepare
   for the next release candidate", 2023-11-12, deletes the LZMA-SDK and libbzip2 paragraphs from `NOTICE.txt` and
   touches zero `.java` files. Measured file counts (directory entry excluded, so 44/46/46/46 where the review, which
   counted the directory, says 45/47/47/47): `LZMADecoder.class` 4,083 and `LZMA2Decoder.class` 3,775 bytes in
   1.24.0, 1.26.1, 1.27.1 and 1.28.0; in `surefire-shared-utils:3.5.6` the relocated copy has 46 files (4,237/3,901
   bytes after relocation), and its embedded `pom.properties` names `commons-compress` 1.28.0. Both bookkeeping errors
   fixed: `1.26.1`/`1.27.1` are Appendix C (`maven-jar-plugin`, `spring-boot-maven-plugin`), only `1.28.0` is Appendix D;
   and the five shaded libraries in `surefire-shared-utils` (`compress` 589 classes, `io` 428, `lang3` 421, `codec`
   115, `utils` 79; `pom.properties` versions `commons-compress` 1.28.0, `commons-io` 2.22.0, `commons-lang3` 3.20.0,
   `commons-codec` 1.19.0, `maven-shared-utils` 3.3.4) are recorded in F.3. One honest gap stays open, stated in row 29:
   the commit does not say *why* the paragraph went, and the ASF-headed `LZMADecoder` delegates to `org.tukaani.xz`,
   so how much of the package is truly LZMA-SDK-derived is not settled here.
5. **MINOR, POI and doxia held to a different standard than row 30 — CONFIRMED, FIXED (new row 40; row 30
   widened).** Chose "add rows", not "record why not". `poi-ooxml-lite:5.5.1`: 1,601 `.xsb`, 24 XMLDSig, 79 XAdES,
   2,247 ECMA-376 and 199 Microsoft generated classes (my counts exclude directory entries; the review's 26/83 include
   them). Its LICENSE covers ECMA only by the bylaws' "without restriction". `doxia-core:2.0.0`'s `xml.xsd` is the
   W3C file with no licence text (the live w3.org copy has none either) — added to row 30. **Found while checking
   this:** `poi-ooxml:5.5.1`'s 47 `poifs/crypt/dsig` classes are from the eID Applet project, whose own `LICENSE.txt`
   is LGPL-3.0 while POI's source header says "an ASLv2 licensed version" — carried in row 40 (b) as a provenance fact.
6. **MINOR, `jna:5.13.0` scope understated — CONFIRMED, FIXED.** Offline `dependency:tree`: compile in `dp-core`,
   `launcher`, `demo-launcher` (all via `docker-java-transport-zerodep`), test in `cp-core`, `arch-tests`;
   `jna-5.13.0.jar` is in both exec jars' `BOOT-INF/lib`. Row 6 and the evidence-trail row corrected.
7. **MINOR, JNA's embedded libffi — CONFIRMED, FIXED (fact, F.4/F.7, no row).** `nm -D` on `libjnidispatch.so` in
   both `jna:5.13.0` and `jna:5.17.0` shows `ffi_call`, `ffi_prep_cif` etc. as defined symbols; JNA's tags `5.13.0` and
   `5.17.0` carry libffi 3.4.4, whose `LICENSE` is the MIT permission notice; JNA's 788-byte `META-INF/LICENSE` does not
   reproduce it. On-list in substance, recorded as a fact on the row 16 standard.
8. **MINOR, self-approval wording survives — CONFIRMED, FIXED.** All six places, not only the three named: the
   "third ship-path gap" text (was line 195), `spring-boot-maven-plugin` and the `maven-failsafe`/`maven-jar` bullet
   (both "**Allowed.**"), the six-plugin bullet (line 1185), the Surefire provider bullet (line 1234) and the
   injected-loader paragraph (line 1259) — pre-round-8 line numbers — now say "Apache-2.0 in substance, spelling mapping per Appendix B" and quote
   the old wording.
9. **MINOR, sweep set narrower than stated — CONFIRMED, FIXED.** Appendix F's Method and F.5 now name both limits (the
   258 omit the three Surefire provider jars; nested jars were never opened). F.8 sweeps the 261 and the three nested
   jars: nothing off-list, and the nested jars are 11,359-byte Apache `LICENSE.txt` plus the Spring Boot `NOTICE.txt`.
10. **MINOR, `maven-site-plugin` and `maven-deploy-plugin` realms unaudited but only in an appendix — CONFIRMED,
    FIXED.** New owner-facing open question. Confirmed offline: no POM for either in `~/.m2`. Each jar's own
    `META-INF/DEPENDENCIES` is the only evidence: site plugin lists 38 BSD-2-Clause, 2 MIT, `xz:1.9`, `aether-util`
    (EPL-1.0) and nine Jetty 9.4.46 coordinates with two licences each; deploy plugin lists Apache only. Not turned
    into rows because none of it can be tied to a resolved coordinate here.
11. **MINOR, the plan is stale — CONFIRMED, NOT FIXABLE HERE.** This task may edit only the audit. The integrator
    must (a) restore `docs/plans/backend/010-dp-data-model-and-remote-flow.md` line 332's M17 row from "partially
    filled" to "filled" (round 4), and (b) extend the T0.4a Results block, which stops at round 3 / 13 owner lines,
    to round 8 / **40 rows** — the paragraph returned with this round is written for that.

**Two corrections to this audit's own earlier statements, made by this round:** (i) round 4's "complete
plugin-realm coverage found no 26th row" and round 7's "every one of the 86/72 coordinates not already a row resolves
in substance to Apache-2.0/MIT/BSD" were both wrong again (this round: nine of those coordinates plus one shipped
Spring artifact); (ii) round 6's "does not generalise across versions" for `commons-compress` is struck.

**Total: 40 rows** (36 → 40: rows 37–40 new; rows 29 and 30 widened in place; row 6 scope corrected). Nothing here
is allow-listed; every new or widened row is an owner decision, evidence as cited. The class-level questions the
rows raise (MPL-2.0 data files; Public Domain content; acknowledgment clauses; content with no licence stated) are
each better answered once by class than coordinate by coordinate, but approval remains by exact coordinate.

---

## M17 — filled (⚠️ downgraded to "partially filled" in round 3; upgraded back to filled in round 4 — see round 4 disposition item 2: all 72 members of the report goal's own class realm are now individually walked in Appendix D, not sampled)

**(i) The candidate plugin's own licence, version, and the goal that fails the build.**
`org.codehaus.mojo:license-maven-plugin:2.7.1` (Maven Central: release = latest = `2.7.1`, published
2026-01-05 — measured from `maven-metadata.xml`). Its own `pom.xml` (fetched at tag `2.7.1`, quoted verbatim):

```xml
<licenses>
  <license>
    <name>The GNU Lesser General Public License, Version 3.0</name>
    <url>https://www.gnu.org/licenses/lgpl-3.0.txt</url>
    <distribution>repo</distribution>
  </license>
</licenses>
```

🔴 **The candidate itself is `LGPL-3.0`** — one of REQ-014's own named "weak copyleft… needs an explicit, recorded
owner decision" licences (`LGPL-*`), confirmed from `raw.githubusercontent.com/mojohaus/license-maven-plugin/2.7.1/LICENSE.txt`
(the GPLv3-incorporating LGPLv3 text) and the pom above. **This was not anticipated by the plan's Q-L1 list** (which
named five *shipped* dependencies; this is the *build tool itself*, in scope under REQ-014's "ships **or builds
with**"). It does not block this audit (T0.4a commits no POM change and ships nothing), but it is a new fact the
owner sitting needs: **if T0.4b adopts this exact plugin permanently, the plugin's own licence needs the same kind
of owner decision as any other off-list dependency** — it cannot be self-approved by the task that binds it.

The plugin's own build-time resolution pulled a large transitive toolchain (measured from the one online
resolution's download log): `org.apache.poi:poi/poi-ooxml/poi-ooxml-lite:5.5.1`,
`org.apache.jena:jena-core/jena-base/jena-iri:4.10.0`, `org.apache.xmlbeans:xmlbeans:5.3.0`,
`org.apache.httpcomponents:httpclient:4.5.14`/`httpcore:4.4.16`, `org.apache.commons:commons-{collections4,csv,math3}`,
`commons-{cli,digester,validator,logging}`, `org.freemarker:freemarker:2.3.34`, `xerces:xercesImpl:2.12.2`,
`xalan:serializer:2.7.3`, `org.json:json:20231013`, `org.codehaus.plexus:plexus-component-annotations:2.1.0`,
`org.nuiton.processor:nuiton-processor:1.3`, `org.apache.maven:maven-archiver:3.6.6`, plus several small utility
jars. None of this is shipped in the SM backend; it exists only while the report/gate mojo runs.

⚠️ **Round 3: eight of these (plus one not previously named at all) individually verified, not left as "not
exhaustively licence-checked."** Measured with `mvn -o -N -f backend/pom.xml -X org.codehaus.mojo:license-maven-plugin:2.7.1:help`
(offline — same class realm, already cached) and WebFetch of each artifact's own POM (walking the `<parent>`
chain where the immediate POM declares no `<licenses>`), quoted verbatim, no name asserted from memory (`I2`):

| Artifact | Declared licence (verbatim, from the POM actually carrying it) | Chain |
|---|---|---|
| `org.nuiton.processor:nuiton-processor:1.3` | `Lesser General Public License (LGPL) v 3.0` | `nuiton-processor:1.3` → `org.nuiton:processor:1.3` → `mavenpom4redmineAndCentral:3.4.4` → `mavenpom4redmine:3.4.4` → `org.nuiton:mavenpom:3.4.4` (5 hops; none of the intermediate 4 POMs declares its own `<licenses>`) |
| `org.eclipse.sisu:org.eclipse.sisu.inject:0.9.0.M3` | `Eclipse Public License, Version 2.0` | `org.eclipse.sisu.inject:0.9.0.M3` → `org.eclipse.sisu:sisu-inject:0.9.0.M3` |
| `xalan:serializer:2.7.3` | **none** — a minimal stub POM (`groupId`/`artifactId`/`version` only, no `<parent>`, no `<licenses>`) | n/a — this is the "no licence at all" case M17(ii) describes, not merely unmapped |
| `org.codehaus.plexus:plexus-i18n:1.0-beta-10` | **none anywhere in the chain** — ⚠️ **round 5 correction:** this row still read "requires one more hop to confirm" after round 4 closed it; walked to `plexus-i18n:1.0-beta-10` → `plexus-components:1.1.12` → `plexus:1.0.10` in Appendix D, no `<licenses>` block at any hop | closed: "no licence at all," the same case as `xalan:serializer:2.7.3` — owner decision table row 20 |
| `org.json:json:20231013` | `Public Domain` | declared directly |
| `org.tukaani:xz:1.10` | `0BSD` | declared directly — **a different version and a different licence than the build-plugin's own `xz:1.9` ("Public Domain")**; these are two separately-resolved coordinates, not the same finding twice |
| `com.github.virtuald:curvesapi:1.08` | `BSD License` (unqualified, URL points to `opensource.org/licenses/BSD-3-Clause`) | declared directly |
| `net.rootdev:java-rdfa:1.0.0-BETA1` | `BSD` (unqualified, URL points to the project's own `COPYING` file, not a standard licence page) | declared directly |
| `xml-apis:xml-apis:1.4.01` | `The Apache Software License, Version 2.0` **+** `The SAX License` **+** `The W3C License` | declared directly, three licences at once — a new multi-licence case distinct from the dual-licence rows already in the tables below |

**Residual gap — closed in round 4, see Appendix D.** Round 3 measured the `:help` goal's class realm at **72
artifacts** and individually verified only the eight named above, leaving the rest an explicit, unclaimed gap.
Round 4 re-measured directly against the goal this audit and T0.4b actually bind —
`aggregate-add-third-party -X` — and got the same count, **72** `"Included:"` lines (confirming round 3's number
came from the right realm). All 72 are now walked in **Appendix D**: ⚠️ **round 5 correction** — not "the same
eight already confirmed here" (8 + 62 = 70, short of 72); it is the same **ten** already confirmed (the nine
individually verified above plus the licence-plugin's own self-reference, matching Appendix D's own footer and
the round-4 disposition's "10 ... plus 62" elsewhere in this document), plus **62 new this round**, every one
resolving to Apache-2.0, MIT, or a `licenseMerges`-mapping variant of one of those — zero new off-allow-list or
no-licence artifacts. This is why M17 is marked **filled** above.

**Goal that gates a build:** `license:add-third-party` (single module) / `license:aggregate-add-third-party`
(reactor — what this audit used). Read from
`AbstractAddThirdPartyMojo.java` at tag `2.7.1`: `failOnMissing` (default `false`) and `failOnBlacklist` (default
`false`) are the two build-breaking flags; **both default off** — a plain `verify` binding with no explicit
`true` passes silently regardless of what is found. `checkMissing`/`checkBlacklist` (lines 1069–1080) are the
methods that throw `MojoFailureException`; `add-third-party` calls both after `consolidate()`.

**(ii) A dependency with no licence at all, and a name the plugin cannot map.** ⚠️ **Corrected in round 2 —
the original text below was wrong; struck through, replaced by the re-read source.**

~~a dependency with **no** `<licenses>` entry at all never enters `licenseMap`~~ — **false.**
`DefaultThirdPartyTool.addLicense(LicenseMap, MavenProject, List<?>)` (tag `2.7.1`, lines 328-338, quoted
verbatim from the scratchpad copy):

```java
if (CollectionUtils.isEmpty(licenses)) {
    // no license found for the dependency
    licenseMap.put(LicenseMap.UNKNOWN_LICENSE_MESSAGE, project);
    return;
}
```

A no-licence dependency **is** put into `licenseMap`, under the key `LicenseMap.UNKNOWN_LICENSE_MESSAGE`
("Unknown license"). `getProjectsWithNoLicense(licenseMap)` (`DefaultThirdPartyTool.java:172-175`) is a plain
**read** of that same key (`licenseMap.get(LicenseMap.UNKNOWN_LICENSE_MESSAGE)`) — it does not remove the entry,
so the key stays in `licenseMap` for every later check, `checkForbiddenLicenses()` included.

Consequence for `checkForbiddenLicenses()` (`AbstractAddThirdPartyMojo.java:919-988`, re-read in full this round):
once `includedLicenses` (the allow-list) is **non-empty** — the mode T0.4b will run in — the method's second loop
iterates every key in `licenseMap.keySet()`, including `"Unknown license"`, and fails closed on any key not
literally in `whiteLicenses`:

```java
for (String dependencyLicense : dependencyLicenses) {
    if (!whiteLicenses.contains(dependencyLicense) && CollectionUtils.isNotEmpty(licenseMap.get(dependencyLicense))) {
        for (MavenProject dependency : licenseMap.get(dependencyLicense)) {
            boolean islicenseWhitelistedAndUsed = isDependencyWhitelisted(dependency, dependencyLicense, whiteLicenses);
            if (!islicenseWhitelistedAndUsed) {
                unsafeLicenses.put(dependencyLicense, dependency);
                break;
            }
        }
    }
}
```

So with `includedLicenses` populated (the allow-list armed) **and** `failOnBlacklist=true`, a no-licence
dependency does fail the build via `checkForbiddenLicenses()` — `"Unknown license"` is simply one more licence
name, and it is never on the allow-list.

⚠️ **Corrected in round 3 — the text that followed here was wrong on the exact question this sub-item asks
(does `failOnMissing` fail the build, or only warn?), struck through.** ~~It does **not** need
`failOnMissing`/`failIfWarning` for this path; those two flags gate a *separate*, softer WARN-only report over the
same `unsafeDependencies` set, used by the `missingFile` workflow (below), not by `checkForbiddenLicenses`. ⚠️
T0.4b still needs `failOnBlacklist=true` with a non-empty `includedLicenses` — that combination is what catches
"no licence" today, not `failOnMissing` as the original text implied; setting only `failOnMissing=true` with an
empty allow-list would **not** fail the build on an unknown licence, only warn.~~ — **false**, re-read from the
scratchpad copy of `AbstractAddThirdPartyMojo.java`. `checkMissing(boolean unsafe)` (lines 1069–1075, quoted
verbatim):

```java
void checkMissing(boolean unsafe) throws MojoFailureException {
    if (unsafe && (isFailOnMissing() || failIfWarning)) {
        throw new MojoFailureException(
                "There are some dependencies with no license, please fill the file " + missingFile);
    }
}
```

This **throws `MojoFailureException`** — it is not a softer WARN-only report; that describes a different method,
`checkUnsafeDependencies()` (`AbstractAddThirdPartyMojo.java:904-916`), which only `LOG.warn`s and never throws.
Round 2 conflated the two. `AggregatorAddThirdPartyMojo.execute()` calls both, unconditionally, in this order:
`checkUnsafeDependencies()` (warns), `checkForbiddenLicenses()` (returns `safeLicense`), `checkBlacklist(safeLicense)`
(throws on blacklist), `writeThirdPartyFile()`, then `checkMissing(CollectionUtils.isNotEmpty(unsafeDependencies))`
(throws on missing) — the last call in the goal, always reached, never gated on `includedLicenses`. `unsafeDependencies`
itself is set from `getHelper().getProjectsWithNoLicense(licenseMap)` (line 779), a plain scan for the
`UNKNOWN_LICENSE_MESSAGE` key that has no dependency on `whiteLicenses` at all.

**So `failOnMissing=true` fails the build on a no-licence dependency by itself, allow-list armed or not** — the
opposite of what round 2 said. T0.4b in fact has **two independent** ways to catch "no licence": `checkForbiddenLicenses()`
(needs `failOnBlacklist=true` *and* a non-empty `includedLicenses`) and `checkMissing()` (needs only
`failOnMissing=true`). Setting both remains the right T0.4b design, not because either one alone is insufficient
for the no-licence case, but because `failOnBlacklist` is also what catches a *declared-but-off-list* licence,
which `failOnMissing` never reaches.

**A second, real gap this round's re-read surfaces (not in the original report at all): channels that can supply
a licence from outside the POM, silently.** `AbstractAddThirdPartyMojo`'s own `@Parameter` defaults (source lines
cited): `useRepositoryMissingFiles` defaults to **`true`**; `missingFile` defaults to
`src/license/THIRD-PARTY.properties` (read whenever it exists, even with `useMissingFile=false`, per
`consolidate()`'s `resolveUnsafeDependenciesFromFile(missingFile)` call, which is unconditional once
`unsafeDependencies` is non-empty); `missingLicensesFileArtifact` and `overrideUrl`/`overrideFile` are empty by
default but, if set, resolve entries from an external artifact or URL. Any of these can turn an
`UNKNOWN_LICENSE_MESSAGE` entry into an allow-listed one **before** `checkForbiddenLicenses` ever runs, without
that assignment appearing anywhere in `backend/licence-exceptions.txt`. **T0.4b must audit which of these files
exist in the tree (none do today) and either delete/ignore them or bring them under the same owner-approval
discipline as the exceptions file** — this is a new open item for T0.4b's design, not decided here.

A licence **name the plugin's mapping does not recognise** is not a distinct case in the source — there is no
"unknown SPDX id" table it consults. Every declared name becomes a literal key in `licenseMap` (optionally folded
by `licenseMerges`, see (iv)); if `includedLicenses` (the allow-list) is non-empty, **anything not literally equal
to an entry in it fails** the moment `checkForbiddenLicenses` runs (see the loop over `dependencyLicenses` at line
956) — so an unmapped/unrecognised name is treated the same as an explicitly blacklisted one, **as long as
`includedLicenses` is populated**. Measured on this run (no `includedLicenses` set): nothing failed, because the
gate wasn't armed — that is the correct, unremarkable behaviour of an *unconfigured* audit run, not evidence about
the gate. This part of the original text was correct and is unchanged.

**(iii) A POM that declares several licences.** 🔴 **The native behaviour is "any", not "every".** ⚠️ **Corrected
in round 2 — the "blacklist-only is also 'any'" sentence below was wrong; struck through.**
`isDependencyWhitelisted()` (`AbstractAddThirdPartyMojo.java:1002-1027`) walks a flagged-unsafe dependency's
*other* declared licences and clears the flag the moment **any one** of them is on `includedLicenses` — even
though the *primary* flagged licence is off-list and the dependency is never re-checked against every one of its
declared licences together. Concretely, measured in this reactor: `net.java.dev.jna:jna:5.13.0` declares
`Apache-2.0` **and** `LGPL-2.1-or-later`; under the plugin's native logic it would pass cleanly (Apache-2.0 is
on-list) with no warning at all about the LGPL half. **This is the opposite of Q-L2 option (a)'s "fail closed:
passes only if every declared licence is on the allow-list"** — the plugin cannot express (a) through
`includedLicenses`/`excludedLicenses` alone. If the owner picks Q-L2 (a) (the plan's recommended default), **T0.4b
needs a second, custom check for any dependency with more than one declared licence** — the native mojo cannot be
configured into fail-closed multi-licence semantics through `includedLicenses`/`excludedLicenses` alone.
⚠️ **Round 3: the sentence that followed here was left unstruck in round 2 and contradicts the very next
paragraph (also added in round 2); struck now.** ~~it would need to be run with `includedLicenses` empty and
`excludedLicenses` listing every off-list licence, and even then a dependency with an on-list *and* an off-list
licence passes via the same "any" logic, just reached from the exclusion path instead of the inclusion path.
Either path is "any", not "every".~~ — as the next paragraph itself establishes, blacklist-only mode is
fail-closed on the blacklisted name, not "any"; it is *whitelist* mode specifically that has the "any-one-clears-it"
behaviour. The conclusion is unchanged: Q-L2 (a) needs a custom multi-licence check regardless, because T0.4b's
design uses an allow-list (whitelist mode), where the "any" problem is real.

~~In blacklist-only mode (empty `includedLicenses`, non-empty `excludedLicenses`), the same "any" clearing applies,
so a dependency with a blacklisted licence *and* a clean one still passes.~~ — **false, re-read this round.** The
whitelist-clearing block that calls `isDependencyWhitelisted()` in `checkForbiddenLicenses()` is nested entirely
inside `if (CollectionUtils.isNotEmpty(whiteLicenses))` (line 935). With `includedLicenses` empty — blacklist-only
mode — that whole block, clearing included, never executes: an artifact whose blacklisted licence matched
`excludedLicenses` is added to `unsafeLicenses` by the **first** block (lines 923-931, gated only on
`excludedLicenses`) and is never removed. So in blacklist-only mode, a dependency's blacklisted licence stays
flagged **regardless of any other licence it also declares** — the "any" semantics is real, but it is specific to
**whitelist mode** (`includedLicenses` non-empty); blacklist-only mode is fail-closed on the blacklisted name, not
"any" in the sense the original text claimed. This does not change T0.4b's conclusion (Q-L2 (a) still needs a
custom multi-licence check, because T0.4b's design uses an allow-list, i.e. whitelist mode) — only the stated
mechanics of the alternative it rejected.

**(iv) Exact-string vs regex matching.** 🟢 **Exact, case-sensitive string equality only — no regex, in the
licence-name check** (unchanged from round 1). 🔴 **But round 1's dismissal of the plugin's exclusion mechanism
was wrong — corrected here, struck through.** `checkForbiddenLicenses()` uses `Set.contains`/`List.contains` on
the raw licence-name strings (`licenseMap.keySet()`, `includedLicenses.getData()`), never `.matches()` or a
compiled pattern. There is **no unanchored-regex risk in the *licence-name* comparison itself** — that half of
M17(iv)'s worry does not materialise in `checkForbiddenLicenses`.

~~It **does** materialise elsewhere in the plugin: `download-licenses`'s
`includedArtifacts`/`excludedArtifacts`/`includedGroups`/`excludedGroups` **are** documented regexes against
artifact/group ids — but that is a different goal (dependency *filtering*, not licence-name matching) and T0.4b's
design (per the plan text) binds `add-third-party`, not `download-licenses`.~~ — **false.** Re-read from the
scratchpad copy of `AbstractAddThirdPartyMojo.java`, lines 183-247: `excludedGroups`, `includedGroups`,
`excludedArtifacts` and `includedArtifacts` are `@Parameter`s declared directly on `AbstractAddThirdPartyMojo`
itself — the base class of **both** `AddThirdPartyMojo` (`add-third-party`) and `AggregatorAddThirdPartyMojo`
(`aggregate-add-third-party`, the goal this audit and T0.4b both use), not a `download-licenses`-only mechanism.
Their javadoc, quoted verbatim:

> A regular expression (not glob pattern) used as filter to exclude artifacts that have matching groupId. Match
> could be also partial for example `^org\.`

So the plugin's own dependency-filtering mechanism **is** exactly the unanchored-substring allow-listing M17(iv)
and T0.4b Step 1 forbid — a value like `Apache` in `includedGroups` would partially match any groupId containing
that substring anywhere, not just a groupId equal to it. **T0.4b must not use these four parameters with
unanchored patterns**, and if it uses them at all (T0.4b's own goals may need `excludedArtifacts` to carve out an
exception, for example), every pattern must be anchored `^…$` and a near-miss test proves a partial match is
rejected — exactly as T0.4b Step 1 already requires for "where the plugin only offers a regex." This is now that
case: it applies to `excludedGroups`/`includedGroups`/`excludedArtifacts`/`includedArtifacts` on the goal T0.4b
binds, not to a different, unused goal. Measured consequence for T0.4b's mapping file (unchanged from round 1):
raw licence-name strings are **not normalised** by the plugin on its own —
the third-party report alone (the 116 artifacts in Appendix A.1) declares Apache-2.0 under **six** different
literal strings (`Apache-2.0`, `Apache 2.0`, `Apache License 2.0`, `Apache License, Version 2.0`, `The Apache
License, Version 2.0`, `The Apache Software License, Version 2.0` — all six counted in this run's output, listed
in the classification below). ⚠️ **Round 6 correction — scope, per `I2`:** "this reactor alone" overstated a
reactor-wide count; the plugin realms (Appendices C and D) add two more spellings not found in the third-party
report itself (`Apache 2` and `Apache Public License 2.0`) — **Appendix B**'s reactor-wide inventory counts
**eight**, not six. The six here is correct and unchanged for the scope this sentence actually measured (the
third-party report). Folding them
into one allow-list entry is `licenseMerges`' job (`ThirdPartyHelper.mergeLicenses`, called from `consolidate()`
**before** `checkForbiddenLicenses`, confirmed by call order in `AddThirdPartyMojo.execute()` at lines 176–182) —
`licenseMerges` entries are also exact strings (`CanonicalName|AltName1|AltName2`), not regexes. So **every** raw
string variant a dependency might use has to be listed once in `licenseMerges`; a variant nobody wrote down fails
closed as "not on the allow-list" (correctly — that is the wanted failure, not a defect, but it means the mapping
file needs maintenance whenever a new dependency spells a known licence differently).

**(v) Test scope and build plugins.** 🟢 **Test scope is covered by default; 🔴 build plugins are not.**
`AbstractLicenseMojo`'s `excludedScopes` defaults to `"system"` only — `compile`, `runtime`, `provided` and `test`
are all included unless a task overrides it. Measured directly in this run: `junit`, `junit-jupiter*`, `mockito-*`,
`assertj-core`, `awaitility`, `hamcrest*`, `archunit*`, `testcontainers*` all appear in the 123-entry report with
their scope confirmed `test` (`dependency:tree`).

**Coverage caveat, added this round.** ⚠️ **Round 5 correction — wrong consumer named.** the build log for the
`aggregate-add-third-party` run carries `[WARNING] The following dependencies could not be resolved at this point
of the build but seem to be part of the reactor: o com.solace.sm:dp-core:jar:tests:0.1.0-SNAPSHOT (test)`.
~~`dp-core`'s test-jar (consumed by `arch-tests`)~~ — **false, measured this round.** Every module `pom.xml` was
read for a `<type>test-jar</type>` dependency on `dp-core`: only `backend/launcher/pom.xml` declares one (test
scope; its own comment explains it needs one fake from `dp-core`'s test sources). `backend/arch-tests/pom.xml`
also depends on `dp-core`, but on the ordinary `jar` artifact — confirmed with an offline `mvn -o -f
backend/pom.xml -pl arch-tests dependency:tree -Dincludes=com.solace.sm:dp-core`, which resolves to
`com.solace.sm:dp-core:jar:0.1.0-SNAPSHOT:test` with no `test-jar` classifier anywhere in the tree. So it is
`dp-core`'s test-jar (consumed by **`launcher`**) that was not resolvable at aggregation time — its own third-party
dependencies (already counted via `dp-core`'s own main scope) are not separately re-scanned under that artifact,
and this run does not claim they are. No dependency unique to that test-jar was found missing by cross-check
against `dp-core`'s full `dependency:tree`, but the warning is a real coverage gap in the *method*, not just in
this report's prose, and T0.4b should not assume `aggregate-add-third-party` sees every reactor-internal test-jar
cleanly. ⚠️ **Round 6 addition — a second reactor-internal test-jar edge, not previously named.**
`backend/dp-core/pom.xml` (lines 126–135) itself declares a `<type>test-jar</type>` dependency on `mesh-events`
(its own comment: it needs `mesh-events`' test sources), confirmed resolving with an offline `mvn -o -f
backend/pom.xml -pl dp-core dependency:tree -Dincludes=com.solace.sm:mesh-events`, which returns
`com.solace.sm:mesh-events:test-jar:tests:0.1.0-SNAPSHOT:test` alongside the ordinary compile-scope
`mesh-events:jar`. This is the same kind of edge as `dp-core`'s own test-jar (consumed by `launcher`) — a
reactor-internal test-jar that `aggregate-add-third-party` does not separately re-scan — so T0.4b's caveat now
names **both** edges: `mesh-events` → `dp-core` and `dp-core` → `launcher`.

**Build plugins are structurally invisible to this goal.** ⚠️ **Citation corrected this round.** The goal this
audit ran is `aggregate-add-third-party` (`AggregatorAddThirdPartyMojo`), whose own `loadDependencies()` returns
`getHelper().getArtifactCache()` — a cache populated, per reactor module, by each module's own
`AddThirdPartyMojo`-style resolution (`resolveDependencyArtifacts()`, `AddThirdPartyMojo.java:232-238`, building
its set from `project.getArtifacts()`, the resolved **dependency** graph). `project.getBuild().getPlugins()` is
never consulted by either class, so the gap is the same one either way; the original citation just named the
wrong of the two cooperating classes for the goal actually run. Measured: this reactor's own explicitly-declared
build plugins (`maven-failsafe-plugin`, `maven-jar-plugin`, `spring-boot-maven-plugin`) do **not** appear
anywhere in the 123-entry report, confirming the gap by omission as well as by source reading. **T0.4b's "second
check… for the part it misses" must cover build plugins**; test scope needs no second check, the one caveat above
aside.

---

## Reactor-wide result: 123 artifacts, 9 modules + the parent, `excludedScopes=system` (i.e. every real scope)

⚠️ **Corrected this round:** the reactor is **9 modules plus the parent POM** (`backend`, `cp-api`, `dp-api`,
`mesh-events`, `cp-core`, `dp-core`, `console-gateway`, `launcher`, `demo-launcher`, `arch-tests` — 10 total,
matching the build log's `[1/10]`…`[10/10]`), not "8 reactor modules" as round 1 said.

⚠️ **Corrected this round: the "123" count mixes two different kinds of thing.** **7 of the 123 rows are
Solution Manager's own reactor artifacts** (`cp-api`, `cp-core`, `dp-api`, `dp-core`, `console-gateway`,
`launcher`, `mesh-events` — each appears once, as a dependency of a sibling module, e.g. `launcher` depends on
`cp-core`), not third-party code. `THIRD-PARTY.txt` reports them as `(Apache License, Version 2.0) Solution
Manager :: cp-core (com.solace.sm:cp-core:0.1.0-SNAPSHOT -
https://spring.io/projects/spring-boot/backend-parent/cp-core)` — measured, this comes from
`spring-boot-starter-parent:3.5.16`'s inherited `<licenses>` block, because `backend/pom.xml` declares no
`<licenses>` of its own. **This is a fact the owner needs, separate from any third-party approval**: SM's own
artifacts currently carry an *inherited* Apache-2.0 licence claim, not a declared one, and nobody has confirmed
that is the licence SM intends to ship its own code under. So: **116 actual third-party artifacts**, not 123 —
see Appendix A for the full per-artifact, per-module, per-scope breakdown (previously only in the ephemeral
scratchpad `THIRD-PARTY.txt`, now committed with this report).

Zero artifacts had no licence at all (no `unsafeDependencies` warning was emitted in this run — measured by its
absence in the build log). ~~Zero artifacts declared a refused-family licence (GPL/AGPL/SSPL/BSL/Elastic/
source-available) among the 123.~~ — **retracted, see "Refused-family licences found" below; this is now an open
owner question, not a clean zero.** The full raw list is `<scratchpad>/licence-audit/THIRD-PARTY.txt`
(regenerable by the command above) and, in full with module/scope, Appendix A of this committed report. Classified
below by the **REQ-014** allow-list (Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC, PostgreSQL Licence) plus the
named first-party exception (JCSMP, the WebApp SDK).

### Candidate for the allow-list — resolves in substance to one of the six licences, owner confirmation via
Appendix B, not decided here (⚠️ retitled in round 6 from "Allowed outright" — that title self-approved the
spelling mapping; see round 6 disposition item 3)

Every artifact in the **116-artifact third-party report** (Appendix A.1) whose *only* declared licence is one of:
`Apache-2.0` / `Apache 2.0` / `Apache License 2.0` / `Apache License, Version 2.0` / `The Apache License, Version
2.0` / `The Apache Software License, Version 2.0` (all six strings measured within this 116-artifact scope —
Appendix B's reactor-wide inventory, which also covers the plugin realms, counts **eight** — ~83 of the **116
actual third-party** artifacts (⚠️ ~90 of "123" in round 1 double-counted 7 rows that are SM's own modules under
this same Apache-2.0-by-inheritance label — see above and Appendix A), including Spring Framework/Boot
6.2.19/3.5.16, Jackson 2.21.x, Netty 4.2.13.Final (all modules — confirmed no 4.1.x downgrade slipped through,
matching `backend/pom.xml`'s netty-override comment), Flyway 11.7.2, HikariCP 6.3.3, sshj 0.40.0, docker-java*
3.4.2, Micrometer 1.15.12, Tomcat embed 10.1.55, SnakeYAML 2.4, log4j-to-slf4j 2.24.3, Solace `solace-serdes`
1.0.0); `MIT` (SLF4J API 2.0.18, jul-to-slf4j, Mockito 5.17.0, Testcontainers 1.21.4 family, Duct Tape);
`BSD-2-Clause` (PostgreSQL JDBC 42.7.11); `BSD-3-Clause` (Hamcrest/Hamcrest Core 3.0, ASM 9.7.1 direct entry).
**None of this is allow-listed by this audit** — this is this audit's own substance read; **Appendix B** is the
one place the owner confirms every spelling mapping in a single sitting. ⚠️ **Round 7:** one artifact counted in the
Apache-2.0 group above on its POM string alone — `org.apache.servicemix.bundles:org.apache.servicemix.bundles.jzlib:
1.1.3_2` — ships JCraft's BSD-licensed JZlib and is owner-table row 36; the count is otherwise unchanged.
⚠️ **Round 8:** `org.springframework:spring-web:6.2.19` (row 39, Public Domain `mime.types`) is another artifact in that
Apache group whose embedded content is not Apache; the group's ~83 is otherwise unchanged (the 3 commons-math3/httpclient
coordinates named in rows 37–38 sit in Appendices C/D, not in the 116).

**Action needed (T0.4b, not this task, and only after the owner's Appendix B sign-off):** one `licenseMerges`
entry folding the confirmed Apache spellings into a single canonical name before `includedLicenses` is checked
(see (iv)).

### First-party (named exception, `REQ-014`)

`com.solacesystems:sol-jcsmp:10.30.1` — "Solace API License Agreement Version 1.1" (measured in the report). The
WebApp SDK does not appear (frontend-only; out of this reactor).

### Needs an explicit, recorded owner decision — the five already anticipated by Q-L1, versions corrected

*(Evidence detail for rows 1–5 of the round-4 "Owner decision table" below, which is the single counted tally —
this table and the one after it are kept as the per-artifact evidence trail, not a second count.)*

| Artifact | Declared licence(s) (verbatim) | Scope / module | Correction vs. the plan's Q-L1 text |
|---|---|---|---|
| `ch.qos.logback:logback-classic:1.5.34`, `logback-core:1.5.34` | `EPL-2.0` **OR** `LGPL-2.1-only` (⚠️ round 7: the POM lists both with no connective; logback's own `LICENSE.txt` says "either ... or (per the licensee's choosing)" — see row 1) | **compile**, in 5 of the 8 non-test-only modules — `cp-core`, `dp-core`, `console-gateway`, `launcher`, `demo-launcher` (via `spring-boot-starter-logging`); **not** `cp-api`, `dp-api`, `mesh-events` (no Spring dependency in any of the three — measured, `dependency:tree` returns no match) | ⚠️ **corrected this round**: Q-L1/round-1 said "every production module" — 3 of 8 do not resolve it. |
| `jakarta.annotation:jakarta.annotation-api:2.1.1` | `EPL 2.0` **OR** `GPL2 w/ CPE` (⚠️ round 7: the POM lists both; the jar's own `NOTICE.md` says `EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0` — see row 2) | **compile**, same 5 modules as logback, same 3 exceptions | ⚠️ **corrected this round**, same overstatement as above. Also see "Refused-family licences found" below — the `GPL2 w/ CPE` half is a new open question, not a clean pass. |
| `org.bouncycastle:bcprov-jdk18on` — **two versions**: `1.80` and `1.86` (+ `bcpkix-jdk18on:1.80`, `bcutil-jdk18on:1.80.2`) | `Bouncy Castle Licence` | `1.80` family: **runtime**, `dp-core` and `demo-launcher` (via `sshj`, no conflicting `cp-core` dependency in either to override it). `1.86`: **compile**, `cp-core` and, via nearest-wins, `launcher` and `arch-tests` | Q-L1 named only `1.86`; **`1.80`/`1.80.2` is a second, separately-resolved version family**, pulled transitively via `sshj`. ⚠️ **Corrected in round 2, then corrected again in round 3 — round 2 still conflated two modules.** `launcher` — the module that actually ships — resolves `bcprov` at **`1.86`** (nearest-wins over `sshj`'s transitive `1.80`, confirmed by `dependency:tree` on `launcher` alone: only `1.86` appears) but still resolves **`bcpkix-jdk18on:1.80`** and **`bcutil-jdk18on:1.80.2`** (no `1.86`-line equivalents exist to override them), so **`launcher`'s own** shipped classpath is `bcprov:1.86` + `bcpkix:1.80` + `bcutil:1.80.2` together — a genuinely mixed version set. ⚠️ **Round 3, re-measured with `dependency:tree` scoped to `demo-launcher` alone**: `demo-launcher` does **not** depend on `cp-core`, so nothing overrides `sshj`'s transitive `bcprov-jdk18on:1.80` — `demo-launcher`'s own shipped classpath is `bcprov:1.80` + `bcpkix:1.80` + `bcutil:1.80.2`, one clean `1.80`-line release, matching its row in Appendix A. Round 2's "the shipped `launcher`/`demo-launcher` classpath is `bcprov:1.86`+`bcpkix:1.80`+`bcutil:1.80.2` together" wrongly applied `launcher`'s mixed set to `demo-launcher` too. An owner approval by exact coordinates needs **all four** resolved coordinate/version pairs across the two modules: `launcher` → `bcprov:1.86`/`bcpkix:1.80`/`bcutil:1.80.2`; `demo-launcher` → `bcprov:1.80`/`bcpkix:1.80`/`bcutil:1.80.2`. |
| `org.junit.jupiter:junit-jupiter*:5.12.2`, `org.junit.platform:junit-platform-*:1.12.2` | `Eclipse Public License v2.0` | **test**, every module with tests | Q-L1 said "`junit-bom` 5.9.3" — **the reactor actually resolves 5.12.2** (and there is no `junit-bom` artifact in the report; the plan's figure came from a `~/.m2` directory listing, not the resolved build). Approve by the resolved version. |
| `junit:junit:4.13.2` | `Eclipse Public License 1.0` (**not** v2.0) | **test**, pulled transitively via `org.testcontainers:testcontainers:1.21.4` (`junit-jupiter` → `testcontainers` → `junit:junit`) | **Not previously distinguished from the v2.0 entry above.** `EPL-1.0` and `EPL-2.0` are different licence versions; Q-L1's approval-by-coordinates needs its own line for this one — approving "JUnit" by name would silently cover a licence version nobody looked at. |

### Needs an explicit, recorded owner decision — new, not in the plan's Q-L1 list

*(Evidence detail for rows 6–11, 14–16 of the round-4 "Owner decision table" below — same note as above.)*

| Artifact | Declared licence(s) (verbatim) | Scope / module | Why it needs a decision, and what's verified |
|---|---|---|---|
| `net.java.dev.jna:jna:5.13.0` | `Apache-2.0` + `LGPL-2.1-or-later` | **compile**, `dp-core`, `launcher` and `demo-launcher` (all via `docker-java-transport-zerodep:3.4.2`; offline `dependency:tree`, and `jna-5.13.0.jar` is present in both repackaged exec jars' `BOOT-INF/lib` — measured); **test** in `cp-core` and `arch-tests` (⚠️ round 8: the round-7 text said "`dp-core` … also test elsewhere", which understated the two artifacts that actually ship) | Dual-licensed with one off-list half (`LGPL-2.1-or-later`). Passes today only because the gate isn't armed — see M17(iii): the plugin's native "any" semantics would let this through even under an armed gate, which is the wrong answer for Q-L2 option (a). ⚠️ **Round 7:** the two legs are alternatives — JNA's own `META-INF/LICENSE` reads `Apache-2.0 OR LGPL-2.1` and "You can freely decide which license you want to apply to the project" (row 6) — so the owner may elect Apache-2.0; the mechanism-level concern above stands regardless of which leg is elected. Needs its own recorded election line regardless of which Q-L2 option is picked. |
| `jakarta.activation:jakarta.activation-api:2.1.4` | `EDL 1.0` (i.e. "Eclipse Distribution License - v 1.0") | **test** only | Not textually equal to any allow-list entry. **Verified from the Eclipse Foundation's own page** (`eclipse.org/org/documents/edl-v10.php`): EDL-1.0's text is the standard 3-clause BSD form, and the page itself states the EDL is assigned SPDX id `BSD-3-Clause`. That is evidence for a *mapping* (map the literal string "Eclipse Distribution License - v 1.0" to `BSD-3-Clause` in `licenseMerges`), not an allow-listing — I am reporting the sourced fact, not deciding the mapping. |
| `jakarta.xml.bind:jakarta.xml.bind-api:4.0.5` | `Eclipse Distribution License - v 1.0` | **test** only | Same licence, same evidence, as the row above (different literal string spelling — see (iv): these do not automatically fold together either). |
| `com.tngtech.archunit:archunit:1.5.0` | `BSD` (unqualified) + `The Apache Software License, Version 2.0` | **test**, `arch-tests` | "BSD" alone is not an exact allow-list entry (only `BSD-2-Clause`/`BSD-3-Clause` are named). **Verified**: ArchUnit's own POM licence URL for the "BSD" entry is `http://asm.ow2.io/license.html` — literally ASM's own licence page, and this same report separately shows `org.ow2.asm:asm:9.7.1` under `BSD-3-Clause` directly. That is sourced evidence the "BSD" label is ASM's BSD-3-Clause (ArchUnit shades/bundles ASM and copied its licence block) — again offered as evidence for a mapping decision, not decided here. |
| `org.codehaus.mojo:license-maven-plugin:2.7.1` itself | `LGPL-3.0` (see M17(i)) | build tool only, not shipped, not added to any POM | New: the *candidate audit/gate tool* is itself an `LGPL-*` dependency under `REQ-014`'s own text. Only matters if T0.4b adopts this exact plugin permanently; flagged so it isn't waved through as "the tooling, not a dependency." |
| `org.jdom:jdom2:2.0.6.1` | `Similar to Apache License but with the acknowledgment clause removed` | build tool only, via `maven-shade-plugin:3.6.0` ← `spring-boot-maven-plugin:3.5.16` | **New in round 3.** Not a textual allow-list match; the "acknowledgment clause removed" variant is close to but not identical to plain Apache-2.0 and needs its own reading, not an assumption. |
| `org.antlr:antlr4-runtime:4.7.2` | `The BSD License` (unqualified, via the `antlr4-master:4.7.2` parent) | build tool only, via `org.tomlj:tomlj:1.0.0` ← `spring-boot-maven-plugin:3.5.16` | **New in round 3.** Same unqualified-"BSD" problem as ArchUnit's row above — which BSD variant is not stated by the plugin's own metadata. |
| `com.github.luben:zstd-jni:1.5.5-11` | `BSD 2-Clause License` | build tool only, via `plexus-archiver`/`commons-compress` ← `maven-jar-plugin:3.4.2` | **New in round 3** — the sibling of the already-flagged `org.tukaani:xz:1.9` from the same `commons-compress` codec set; substance maps to allow-listed `BSD-2-Clause`, but the literal string needs a `licenseMerges` mapping, not a silent pass. |
| `org.junit.platform:junit-platform-launcher:1.12.2` | `Eclipse Public License v2.0` | **test**, resolved by Surefire itself as the JUnit-Platform provider (not visible to `dependency:resolve-plugins` or the third-party report) | **New in round 3.** Same licence/version as its already-listed siblings `junit-platform-engine`/`junit-platform-commons`, but this exact coordinate was never itself listed — an approval "by exact coordinates" would have missed it. |

### Refused-family licences found

⚠️ **Retracted this round.** Round 1 said "None" — that is false. `jakarta.annotation:jakarta.annotation-api:2.1.1`
declares two licences, `EPL 2.0` **and** `GPL2 w/ CPE` (measured, verbatim, in `THIRD-PARTY.txt`, Appendix A). `GPL2
w/ CPE` is the Classpath-Exception variant of GPL-2.0. `REQ-014` line 28 lists "GPL/AGPL" under **Refused**, with
no textual carve-out for the Classpath Exception, while the same requirement's line 26 sends dual-licensed
artifacts to an owner decision generally. This dependency is already in the "needs an explicit, recorded owner
decision" table above (it was there in round 1 too, correctly) — what round 1 got wrong was the separate summary
claiming a clean zero for refused-family licences, which pre-empted the owner's own reading of `REQ-014` against
this specific case.

**Left as an open owner question, not decided here:** does `REQ-014`'s "Refused: GPL/AGPL" reach a `GPL-2.0`
licence that is only one half of a dual licence whose other half (`EPL-2.0`) is itself merely "needs a decision"
(not refused)? Two readings are available and this audit picks neither: (a) any GPL-family name anywhere in a
POM's `<licenses>` list is refused, full stop, regardless of what else is declared — under this reading
`jakarta.annotation-api` is **refused**, not merely "needs a decision", and Solution Manager cannot use Spring
Boot's default JSR-305/`@PostConstruct` annotations API without a replacement; (b) the Classpath Exception's own
purpose (it exists specifically so consumers who don't modify the GPL'd code aren't bound by GPL's copyleft) means
`GPL2 w/ CPE` behaves like a permissive licence for an unmodified, statically-linked dependency such as this one,
and Q-L2's per-coordinate owner-approval path (already open for this exact artifact) is the right and sufficient
mechanism. ~~This audit found **zero** licence names among the other 115 third-party artifacts that name
GPL/AGPL/SSPL/BSL/BUSL/Elastic/other source-available terms — the open question is scoped to this one artifact's
second declared licence.~~

⚠️ **Round 6 correction — the "one artifact" scoping is false, and the AND/OR reading matters here too.**
`jakarta.annotation-api:2.1.1`'s own `META-INF/NOTICE.md` (read in full, verbatim): "This program and the
accompanying materials are made available under the terms of the Eclipse Public License v. 2.0... This Source
Code may also be made available under the following Secondary Licenses when the conditions for such availability
set forth in the Eclipse Public License v. 2.0 are satisfied: GNU General Public License, version 2 with the GNU
Classpath Exception... SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0." This is a
disjunctive (OR) licence, the same structure as row 7 (`jakarta.activation-api`, already read this way) and row 28
(`trilead-ssh2-repackaged`, `CDDL-1.0 OR GPL-2.0-only WITH Classpath-exception-2.0`) — **both of which also name a
GPL leg**. So "zero licence names among the other 115... that name GPL/AGPL" was already false before this round
(row 28's GPL leg was on record since round 5), and "the open question is scoped to this one artifact" understates
it further. **Fixed:** the open question — whether a GPL-family name on one leg of a disjunctive licence falls
under `REQ-014`'s "Refused: GPL/AGPL" or is covered by the ordinary per-coordinate owner-approval path — applies
to every row carrying such a leg: row 2 (this one), row 7, and row 28. Reading (b) above is, if anything,
*stronger* for a genuinely disjunctive licence than the audit originally framed it: an OR-licensed artifact lets
the licensee elect the non-GPL leg outright (EPL-2.0 here, CDDL-1.0 for row 28, BSD-3-Clause for row 7), so under
reading (b) none of the three rows needs to be treated as "refused" at all — they need only a recorded election of
which leg Solution Manager is using, which is the same "record where introduced" `REQ-014` already asks for every
new dependency. This audit still picks neither reading; it is the owner's call, now made on the full set of rows
it actually touches.

⚠️ **Round 7 addition — two more rows carry a GPL-family leg.** Row 16 (`zstd-jni`): the statically embedded libzstd is
licensed "under both the BSD-style license ... and the GPLv2 ... You may select, at your option, one of the
above-listed licenses" (zstd's `lib/zstd.h`, tags `v1.5.5` and `v1.5.7`). Row 34 (`testcontainers`' shaded SnakeYAML
`1.33` `Base64Coder`): "multi-licensed and may be used under the terms of any of the following licenses: EPL ...
LGPL ... GPL ... AL ... BSD". Both are OR licences whose non-GPL legs are available (BSD; Apache-2.0, BSD or EPL), so
reading (b) applies exactly as for rows 2, 7 and 28 — a recorded election of the non-GPL leg — and under reading (a)
both would be refused outright. Neither ships: row 16 is build-tool-only, row 34 is test-only, which bears on what
reading (a) would cost but is not this audit's call. The question now applies to **rows 2, 7, 16, 28 and 34**; this
audit still picks neither reading.

### Build plugins — not covered by the candidate goal (M17(v)), audited separately here

⚠️ **Corrected this round: round 1 read plugin versions off `~/.m2` residue instead of measuring what the reactor
actually resolves, and never looked at a build plugin's own dependencies.** Fixed by running `mvn -o -f
backend/pom.xml dependency:resolve-plugins` (offline — every plugin was already in `~/.m2` from this reactor's
own earlier builds, so this needed no online resolution) over the whole reactor. Explicitly declared in the
reactor (`grep` over every module `pom.xml`): `maven-failsafe-plugin` (`cp-core`, `dp-core`, `launcher`),
`maven-jar-plugin` (`dp-core`, `mesh-events`), `spring-boot-maven-plugin` (`launcher`, `demo-launcher`). None
pins its own version (inherited from the `spring-boot-starter-parent:3.5.16` / Super POM default bindings) — the
**resolved** versions, measured directly:

| Plugin | Resolved version | Bound where |
|---|---|---|
| `maven-failsafe-plugin` | **`3.5.6`** | `cp-core`, `dp-core`, `launcher` |
| `maven-jar-plugin` | **`3.4.2`** | `dp-core`, `mesh-events` |
| `spring-boot-maven-plugin` | **`3.5.16`** | `launcher`, `demo-launcher` |
| `maven-compiler-plugin` (default lifecycle) | **`3.14.1`** | every module |
| `maven-resources-plugin` (default lifecycle) | **`3.3.1`** | every module |
| `maven-surefire-plugin` (default lifecycle) | **`3.5.6`** | every module |
| `maven-install-plugin` (default lifecycle) | **`3.1.4`** | every module |
| `maven-deploy-plugin` (default lifecycle) | **`3.1.4`** | every module |
| `maven-clean-plugin` (default lifecycle) | **`3.4.1`** | every module |
| `maven-site-plugin` (default lifecycle, present but unbound to a phase by default) | **`3.12.1`** | every module |

⚠️ **Round 1 reported `maven-failsafe-plugin` at `3.5.4`** — that version is not what this build resolves; the
correct, measured version is `3.5.6`, same as `maven-surefire-plugin`.

- `spring-boot-maven-plugin:3.5.16` — **verified**: its own POM (`repo1.maven.org/.../spring-boot-maven-plugin/3.5.16/...pom`)
  declares `<licenses><license><name>Apache License, Version 2.0</name>...` directly. Substance reads as Apache-2.0;
  the literal string is an Appendix B variant, so the mapping is the owner's to confirm there (⚠️ round 8: was
  "**Allowed.**").
- `maven-failsafe-plugin:3.5.6` and `maven-jar-plugin:3.4.2` — **verified**: neither *immediate* POM carries its
  own `<licenses>` block; both jars carry the standard ASF header ("Licensed to the Apache Software Foundation
  (ASF) ... under the Apache License, Version 2.0"). Substance reads as Apache-2.0; spelling mapping per Appendix B
  (⚠️ round 8: was "**Allowed.**").
  ⚠️ **Corrected in round 3 — the sentence claiming this is only file-header evidence, not a `<licenses>` element,
  is wrong; struck.** ~~this reads the *file header*, not a `<licenses>` element, because the immediate POM has
  none; note this if a stricter standard than "the file itself says so" is wanted for build plugins.~~ — walking
  the parent chain (`maven-jar-plugin`/`maven-failsafe-plugin` → `maven-plugins:42` → `maven-parent:42` →
  `org.apache:apache:32`) finds an *explicit* `<licenses>` element after all, at the ASF root parent, fetched from
  Central: `<license><name>Apache-2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license>`.
  The **effective** POM (parent-merged, which is what Maven and any licence-scanning tool actually resolves) does
  carry a declared `<licenses>` element — the same evidential standing as any other inherited-licence artifact in
  this report (SM's own reactor modules, Appendix A.2, included), not weaker "file says so" evidence.
- ⚠️ **Fixed this round — previously left unverified.** `maven-compiler-plugin:3.14.1`, `maven-resources-plugin:3.3.1`,
  `maven-surefire-plugin:3.5.6`, `maven-install-plugin:3.1.4`, `maven-deploy-plugin:3.1.4`, `maven-clean-plugin:3.4.1`:
  each POM individually fetched from Central and read (not inferred from the pattern, per `I2`) — none of the six
  carries its own `<licenses>` block; each inherits, through the same `maven-plugins` → `maven-parent` →
  `org.apache:apache` chain confirmed above, the explicit ASF `Apache-2.0` `<licenses>` element. Apache-2.0 in substance, same
  corrected evidential standing as the two plugins above (⚠️ round 8: was "**Allowed**"; the `Apache-2.0` string is
  the canonical spelling, so nothing beyond the owner's general Q-L1 sign-off is asked).
- ⚠️ **New this round — M17(i) asked for the candidate's "own runtime dependencies"; extended here to every build
  plugin's own dependencies, which `REQ-014`'s "ships or builds with" also reaches.** `dependency:resolve-plugins`
  surfaces two off-list licences that round 1 never looked for, because it never resolved plugins at all:
  - **`org.tukaani:xz:1.9`** (pulled by `maven-jar-plugin:3.4.2` via `plexus-archiver`/`commons-compress`) — its
    own POM (`repo1.maven.org/maven2/org/tukaani/xz/1.9/xz-1.9.pom`, fetched) declares `<license><name>Public
    Domain</name></license>`, with no URL. "Public Domain" is not on `REQ-014`'s allow-list and is not a named
    exception. **Needs an explicit, recorded owner decision** — build-tool-only, not shipped, same status as
    M17(i)'s finding about the licence plugin itself.
  - **`net.java.dev.jna:jna:5.17.0`** and **`net.java.dev.jna:jna-platform:5.17.0`** (pulled by
    `spring-boot-maven-plugin:3.5.16`, for its buildpack support) — both POMs (fetched from
    `repo1.maven.org/maven2/net/java/dev/jna/...`) declare the same dual licence already found at a different
    version in the third-party table above: `LGPL-2.1-or-later` **and** `Apache-2.0` (⚠️ round 7: JNA's own `META-INF/LICENSE` on both 5.17.0 jars states `Apache-2.0 OR LGPL-2.1-or-later` — alternatives, not cumulative; see row 13). Same off-list leg as
    `net.java.dev.jna:jna:5.13.0` (M17(iii)'s example), different version, different (build-tool) role. **Needs
    an explicit, recorded owner decision** (a recorded election of the Apache-2.0 leg, row 13), same reasoning as the
    5.13.0 entry: ~~the LGPL half is real regardless of the Apache half~~ — struck in round 7, the legs are
    alternatives.

  ⚠️ **Round 3: this list was still incomplete — three more off-list artifacts turned up in the same
  `dependency:resolve-plugins` output, missed because round 2 stopped reading the tree once it found `xz`/`jna`.**
  Re-read the full tree this round (offline, same cached resolution):
  - **`org.jdom:jdom2:2.0.6.1`** (pulled by `org.apache.maven.plugins:maven-shade-plugin:3.6.0`, itself pulled by
    `spring-boot-maven-plugin:3.5.16`) — its own POM declares `<license><name>Similar to Apache License but with
    the acknowledgment clause removed</name>...</license>`. Not textually an allow-list entry and not a licence
    this report has seen elsewhere — a licence *name*, not a missing one, but one that needs its own reading
    against `REQ-014` rather than being folded into "Apache-2.0" by assumption. **Needs an explicit, recorded
    owner decision.**
  - **`org.antlr:antlr4-runtime:4.7.2`** (pulled by `org.tomlj:tomlj:1.0.0`, itself pulled by
    `spring-boot-maven-plugin:3.5.16` for TOML support) — no `<licenses>` in its own POM; its parent,
    `org.antlr:antlr4-master:4.7.2`, declares `<license><name>The BSD License</name><url>http://www.antlr.org/license.html</url></license>` —
    unqualified "BSD", the same unmapped case M17(i)/ArchUnit already flags, at a different artifact. **Needs a
    mapping decision** (which BSD variant), same as the ArchUnit row above.
  - **`com.github.luben:zstd-jni:1.5.5-11`** — pulled by the **same** `plexus-archiver`/`commons-compress` chain
    that already produced the `org.tukaani:xz:1.9` finding (both are `commons-compress` optional codec
    dependencies of `maven-jar-plugin`); round 2 found one sibling and missed the other. Its own POM declares
    `<license><name>BSD 2-Clause License</name><url>https://opensource.org/licenses/BSD-2-Clause</url></license>` —
    the substance maps to `REQ-014`'s allow-listed `BSD-2-Clause`, but the literal string does not match
    (`licenseMerges` case, per M17(iv)), so it still needs a recorded mapping/approval line, not a silent pass.

  **A second, different-in-kind gap this round surfaces: the test-execution **provider** classpath, which
  `dependency:resolve-plugins` never sees at all.** Measured with `mvn -o -X -f backend/pom.xml -pl mesh-events
  test` (offline, already cached): Surefire resolves its JUnit-Platform provider at test-run time via its own
  mechanism, not as a declared plugin dependency —
  `org.apache.maven.surefire:surefire-junit-platform:3.5.6`, `surefire-api:3.5.6`, `surefire-logger-api:3.5.6`,
  `surefire-shared-utils:3.5.6`, `common-java5:3.5.6`, `maven-surefire-common:3.5.6`, `surefire-booter:3.5.6`,
  `surefire-extensions-api:3.5.6`, `surefire-extensions-spi:3.5.6`, and — the one that actually matters for the
  allow-list — **`org.junit.platform:junit-platform-launcher:1.12.2`**. The `surefire-*`/`common-java5` artifacts
  all inherit `Apache-2.0` through `org.apache.maven.surefire:surefire:3.5.6` → `maven-parent:47` → the same
  ASF-root `<licenses>` element confirmed above — Apache-2.0 in substance, not self-approved (⚠️ round 8: was
  "**Allowed**"). `junit-platform-launcher:1.12.2` declares
  `Eclipse Public License v2.0` directly (fetched, verified) — the same licence and version already in the
  third-party table for its sibling `junit-platform-engine`/`junit-platform-commons`, but **this exact coordinate
  has never appeared in any table in this document**, so an owner approval "by exact coordinates" (Q-L1's own
  stated method) would not have covered it. **Needs its own line**, same reasoning as `junit:junit:4.13.2`'s row
  above.

  These six (three build-plugin transitive artifacts, plus the one test-provider coordinate that needs a new
  line — `jdom2`, `antlr4-runtime`, `zstd-jni`, and `junit-platform-launcher`; the five `surefire-*`/`common-java5`
  artifacts are ASF Apache-2.0 and need no decision) are new rows for the owner sitting (T0.1 Step 7), not
  previously listed anywhere in this document or the plan's Q-L1 list. **Round 4 closes the "not exhaustively
  licence-checked" caveat that followed here** — see the round 4 disposition above and Appendices C/D: every
  plugin-realm and licence-plugin-realm coordinate is now individually walked, with zero new off-allow-list
  artifacts found beyond the ones already in the consolidated owner decision table.

**A third, different-in-kind ship-path gap, found in round 4: `spring-boot-maven-plugin`'s `repackage` goal
injects code directly into the shipped executable jar, bypassing every dependency-based check above.** Measured
read-only on the already-built artifacts (`unzip -l`, no build triggered): `backend/launcher/target/`
`launcher-0.1.0-SNAPSHOT-exec.jar` — the classifier that is actually shipped — contains
`BOOT-INF/lib/spring-boot-jarmode-tools-3.5.16.jar` (53,753 bytes) and **113**
`org/springframework/boot/loader/*` classes; the sibling plain `launcher-0.1.0-SNAPSHOT.jar` (5.8 KB, the
reactor-internal dependency artifact) has **zero** of either — confirming the injection is specific to the
repackaged executable. `backend/demo-launcher/target/demo-launcher-0.1.0-SNAPSHOT.jar` (repackaged in place, no
separate `-exec` classifier) carries the same jarmode jar and the same 113 loader classes. Both pieces come from
`org.springframework.boot:spring-boot-loader-tools:3.5.16` (Appendix C: `Apache License, Version 2.0`, declared
directly; an Appendix B spelling variant, so Apache-2.0 in substance with the mapping left to the owner — ⚠️ round 8:
was "**Allowed**, no owner decision needed") — but neither piece appears in `dependency:tree`,
`dependency:resolve-plugins`, or the `aggregate-add-third-party` report, because `repackage` writes them into the
jar directly rather than resolving them as POM dependencies of the module being built. **T0.4b's "second check"
must therefore cover three gaps, not two**: build plugins (M17(v), Appendix C), the Surefire test-provider
classpath (round 3), and this repackage-injection path.

---

## Corrections this audit makes to the plan's own prior text

1. **`HdrHistogram` is not actually resolved by the backend reactor.** Q-L1's list (drawn from a `~/.m2` directory
   listing) named it alongside logback/JUnit/jakarta.annotation/BouncyCastle. Measured here twice — absent from the
   123-artifact `aggregate-add-third-party` report, and `mvn -o -f backend/pom.xml dependency:tree
   -Dincludes=org.hdrhistogram:HdrHistogram` returns **no match in any of the 9 built modules**. Its `~/.m2`
   presence is residual from something else on this machine, not from this build. **Recommend dropping it from
   the Q-L1 decision list** — there is nothing here for the owner to approve.
2. **`junit-jupiter` resolves at `5.12.2`, not the `junit-bom 5.9.3` Q-L1 named** (see the table above).
3. **BouncyCastle is two version families, not one** (`1.80`/`1.80.2` via `sshj` in `dp-core`, `1.86` direct in
   `cp-core`) — Q-L1 named only `1.86`.
4. **`junit:junit:4.13.2` (EPL-1.0) is a distinct dependency from the JUnit 5 family (EPL-2.0)**, pulled
   transitively via Testcontainers, not previously called out.

## Owner decision table (consolidated, round 8; rows 41–43 added from the round-9 review) — feeds `T0.1 Step 7`

⚠️ **Round 4 replaced every earlier "needs a decision" table and bullet list with one table, counted once; rounds
5 and 6 extend it, still counted once, rather than opening a parallel list.** The round-3 tally ("thirteen
third-party exact-coordinate lines") omitted `org.tukaani:xz:1.9`, `net.java.dev.jna:jna`/`jna-platform:5.17.0`,
and `org.codehaus.mojo:license-maven-plugin:2.7.1` itself — all three were already written up somewhere in rounds
2–3 but never folded into the headline count; round 4 closed that to 25. **Round 5 added five more rows
(26–30)**, found by opening the already-built jars read-only rather than relying on the plugin's POM-only scan
(see the round 5 disposition above and Appendix E for the full evidence): `jackson-core`'s bundled FastDoubleParser
third-party code, `tomcat-embed-core`'s bundled Java-EE/Jakarta-EE schema subcomponents, a shaded
`trilead-ssh2-repackaged` build inside `testcontainers`, `commons-compress`'s embedded LZMA-SDK sevenz code, and
`xmlbeans`'s embedded W3C schema documents. **Round 6 adds one more row (31)**, found by the complete,
mechanical embedded-content sweep in Appendix F: Netty's own bundled Public Domain content (Base64,
ThreadLocalRandom). **Round 7 adds five more (32–36)**, found by re-running the embedded-content sweep with the
gaps the seventh review named closed and two further methods added (Appendix F): the Indiana University Extreme!
Lab licence inside `plexus-xml`/`plexus-utils`; Doug Lea's Public Domain `Striped64`/`LongAdder` inside
`testcontainers`' shaded Guava; the multi-licensed `Base64Coder` inside `testcontainers`' shaded SnakeYAML; Netty's
`Webbit`-derived `WebSocket08FrameDecoder` under an unqualified "BSD License" label; and the JCraft JZlib code inside
the ServiceMix `jzlib` wrapper. **Round 8 adds four more (37–40)** and widens rows 29 and 30 in place: the Mozilla
Public Suffix List (MPL-2.0) data files inside `httpclient5`/`httpclient`; `commons-math3`'s appended Minpack
(acknowledgment clause) and no-licence-stated `BesselJ` sections; Public Domain `mime.types` inside `spring-web`;
and the compiled/derived W3C, ETSI and ECMA schema content plus the LGPL-lineage eID Applet package in POI —
plus five more coordinates carrying row 29's `sevenz` package and `doxia-core`'s W3C `xml.xsd` for row 30. Rows 1 and 16 are also corrected in place (row 1: OR, not AND; row 16: an embedded
BSD-or-GPL-2.0 library behind the POM's "BSD 2-Clause"). This table is the single source of truth: every coordinate
below, and only these, need an owner decision. **Total: 40 rows.** This audit approves nothing and adds nothing to any allow-list or exceptions
file — `backend/licence-allowlist.txt` and `backend/licence-exceptions.txt` are T0.4b's files, written after
Q-L1/Q-L2 are answered. **Separately, Appendix B (round 5) lists thirteen licence-name spelling variants (eight
Apache-2.0, three MIT, two BSD-2-Clause) that this audit read as equivalent in substance but does not
self-approve** — those are evidence for the owner's `licenseMerges` sign-off, not additional coordinate rows,
because the underlying licence is already allow-listed once the spelling is mapped. ⚠️ **Round 6 note on rows 2, 6
and 13 (row 1 added in round 7):** each declares its licences **disjunctively** (`... OR ...`, the artifact's own words, not this audit's
inference) — the owner may elect the allow-listed leg outright rather than treat the off-list leg as binding
regardless. See each row's evidence column and the "Refused-family licences found" section above for row 2
specifically. Rows 7, 16 (embedded library), 28 and 34 are disjunctive too, and each has a GPL-family leg. ⚠️ **Round 8:** row 37 is `MPL-2.0` (weak copyleft, off the allow-list, not a GPL-family leg), and rows 29 and 39 are further cases of the 'Public Domain' class (beside 12/21/22/31/33) and row 38 of the acknowledgment-clause class (beside 14/32) — an owner ruling on the *class* would settle several at once, but approval remains by exact coordinate.

| # | Coordinate(s) | Declared licence(s) (verbatim) | Scope | Evidence / why it needs a decision |
|---|---|---|---|---|
| 1 | `ch.qos.logback:logback-classic:1.5.34`, `logback-core:1.5.34` | `EPL-2.0` + `LGPL-2.1-only` (the POM's `<licenses>` lists both with no connective; **read as OR, see evidence**) | **shipped**, compile in 5 of 8 modules (`cp-core`, `dp-core`, `console-gateway`, `launcher`, `demo-launcher`) | ⚠️ **Round 7: disjunctive, not conjunctive — the same reading rows 2, 6 and 13 got in round 6, which was not applied here.** Neither jar carries a `LICENSE`/`NOTICE` (measured: `META-INF/` holds only `MANIFEST.MF` and `INDEX.LIST`), and `logback-parent-1.5.34.pom` lists the two licences with no connective, so the POM alone cannot tell AND from OR. Logback's own `LICENSE.txt` at tag `v_1.5.34` (WebFetched) says (verbatim): "This program and the accompanying materials are dual-licensed under either the terms of the Eclipse Public License v2.0 as published by the Eclipse Foundation or (per the licensee's choosing) under the terms of the GNU Lesser General Public License version 2.1 as published by the Free Software Foundation." That is OR. The decision put to the owner is whether to **elect EPL-2.0** — the licence already in row 4 for the JUnit family — for logback, rather than to accept an LGPL leg; the election is still an explicit, recorded owner act (`REQ-014`'s "record where introduced"), not a default. The evidence is the upstream repository at the pinned tag; the shipped jars disclose nothing. |
| 2 | `jakarta.annotation:jakarta.annotation-api:2.1.1` | `EPL 2.0` + `GPL2 w/ CPE` | **shipped**, compile, same 5 modules | ⚠️ **Round 6: the artifact's own `META-INF/NOTICE.md` declares this disjunctively** — `SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0` (verbatim) — not a conjunctive dual licence; the owner may elect the EPL-2.0 leg outright. `GPL2 w/ CPE` may also trip `REQ-014`'s "Refused: GPL/AGPL" line — see "Refused-family licences found" above (scope widened in round 6 to rows 2, 7 and 28, and in round 7 to rows 16 and 34); two readings offered there, neither decided here. |
| 3 | `org.bouncycastle:bcprov-jdk18on:1.80`+`1.86`, `bcpkix-jdk18on:1.80`, `bcutil-jdk18on:1.80.2` | `Bouncy Castle Licence` | **shipped**, runtime/compile — `launcher`: `bcprov:1.86`+`bcpkix:1.80`+`bcutil:1.80.2`; `demo-launcher`: `bcprov:1.80`+`bcpkix:1.80`+`bcutil:1.80.2` | Not on the six-item allow-list; mixed version families across the two shipped modules — approval needs all four coordinate/version pairs, per module. |
| 4 | `org.junit.jupiter:junit-jupiter*:5.12.2`, `org.junit.platform:junit-platform-{engine,commons}:1.12.2` | `Eclipse Public License v2.0` | test, every module with tests | `EPL-2.0` off allow-list; approve by the resolved version (not the plan's stale `5.9.3`). |
| 5 | `junit:junit:4.13.2` | `Eclipse Public License 1.0` | test, via Testcontainers | Distinct licence *version* from row 4 (`EPL-1.0` vs `EPL-2.0`) — needs its own line. |
| 6 | `net.java.dev.jna:jna:5.13.0` | `Apache-2.0` + `LGPL-2.1-or-later` | **shipped** — compile in `dp-core`, `launcher` and `demo-launcher` (present in both repackaged exec jars' `BOOT-INF/lib`, measured); test in `cp-core`, `arch-tests` (⚠️ round 8: was "compile, `dp-core` / test elsewhere") | ⚠️ **Round 6: JNA's own `META-INF/LICENSE` declares this disjunctively** — `SPDX-License-Identifier: Apache-2.0 OR LGPL-2.1` and, in prose, "You can freely decide which license you want to apply to the project" (verbatim) — not "the LGPL half is real regardless of the Apache half" as earlier rounds framed it; the owner may elect Apache-2.0 outright. Separately: native plugin "any" semantics would pass this silently under an armed gate (M17(iii)) regardless of which leg is elected, which remains the mechanism-level concern for Q-L2 (a). |
| 7 | `jakarta.activation:jakarta.activation-api:2.1.4` | `EDL 1.0` | test only | Not textually on the allow-list; Eclipse Foundation's own page maps EDL-1.0 to SPDX `BSD-3-Clause` — evidence for a mapping, not a self-approval. |
| 8 | `jakarta.xml.bind:jakarta.xml.bind-api:4.0.5` | `EDL 1.0` | test only | Same evidence and reasoning as row 7, different literal string. |
| 9 | `com.tngtech.archunit:archunit:1.5.0` | `BSD` (unqualified) + `The Apache Software License, Version 2.0` | test, `arch-tests` | Unqualified "BSD" is not an exact allow-list entry; ArchUnit's own licence URL is ASM's BSD-3-Clause page (shaded/bundled) — evidence for a mapping. |
| 10 | `org.junit.platform:junit-platform-launcher:1.12.2` | `Eclipse Public License v2.0` | test, resolved by Surefire itself as the JUnit-Platform provider — invisible to `dependency:resolve-plugins` and the third-party report | Same licence/version as its siblings in row 4, but this exact coordinate never appeared in any table — an approval "by exact coordinates" would have missed it. |
| 11 | `org.codehaus.mojo:license-maven-plugin:2.7.1` (the candidate audit/gate tool itself) | `LGPL-3.0` | build-tool only, not shipped | Off allow-list; only matters if T0.4b adopts this exact plugin permanently — cannot be self-approved by the task that binds it. |
| 12 | `org.tukaani:xz:1.9` | `Public Domain` | build-tool only, via `maven-jar-plugin` → `plexus-archiver`/`commons-compress` | Not on the allow-list (permissive, but not one of the six named licences). |
| 13 | `net.java.dev.jna:jna:5.17.0`, `jna-platform:5.17.0` | `Apache-2.0` + `LGPL-2.1-or-later` | build-tool only, via `spring-boot-maven-plugin`'s buildpack support | ⚠️ **Round 6: same disjunctive licence as row 6, confirmed on both `jna:5.17.0` and `jna-platform:5.17.0`'s own `META-INF/LICENSE`** — `SPDX-License-Identifier: Apache-2.0 OR LGPL-2.1-or-later`, "you can freely decide" (verbatim). Same evidence, different version, different (build-tool) role. |
| 14 | `org.jdom:jdom2:2.0.6.1` | `Similar to Apache License but with the acknowledgment clause removed` | build-tool only, via `maven-shade-plugin` ← `spring-boot-maven-plugin` | Not a textual allow-list match; the "acknowledgment clause removed" variant needs its own reading, not an assumption of equivalence to Apache-2.0. |
| 15 | `org.antlr:antlr4-runtime:4.7.2` | `The BSD License` (unqualified, via `antlr4-master:4.7.2` parent) | build-tool only, via `tomlj` ← `spring-boot-maven-plugin` | Same unqualified-BSD problem as row 9 — which BSD variant is not stated by the plugin's own metadata. |
| 16 | `com.github.luben:zstd-jni:1.5.5-11` **and** `1.5.7-6` | `BSD 2-Clause License` (both POMs, describing the JNI binding) **+ embedded libzstd: `BSD-style` OR `GPLv2`, "You may select, at your option, one of the above-listed licenses"** (zstd's `lib/zstd.h`) | build-tool only, via `maven-jar-plugin` → `plexus-archiver`/`commons-compress` (both versions resolve into the plugin's own realm — Appendix C and Appendix D respectively) | Substance of the POM string maps to allow-listed `BSD-2-Clause`; literal string needs a `licenseMerges` mapping, not a silent pass. ⚠️ **Round 5:** Appendix D had marked `1.5.7-6` "Allowed" on the identical string while this row already sent `1.5.5-11` to the owner — one decision covers both versions; fixed at Appendix D. ⚠️ **Round 7 — the POM string is not the whole licence picture.** Each jar embeds natively compiled zstd (17 native libraries in `1.5.5-11`, 18 in `1.5.7-6`; the `darwin/aarch64` `.dylib`s are 773 KB / 631 KB and `strings` shows `HUF_`/`FSE_`/`ZSTD_` internals, so zstd is statically compiled in) and neither jar has any `LICENSE`/`NOTICE`/`COPYING` (measured). zstd's own `lib/zstd.h` at `v1.5.5` and `v1.5.7` (WebFetched, identical): "licensed under both the BSD-style license (found in the LICENSE file ...) and the GPLv2 (found in the COPYING file ...). You may select, at your option, one of the above-listed licenses." zstd's `LICENSE` at `v1.5.5` (WebFetched) is a 3-condition BSD text with a no-endorsement clause; the binding's own `LICENSE` (`luben/zstd-jni` at `v1.5.5-11`, WebFetched) is a separate BSD text. So the decision is an **election of the BSD leg for the embedded library** (and of `BSD-2-Clause` for the binding), and this row carries a GPL-2.0 leg, so it belongs to the "Refused-family licences found" open question with rows 2, 7, 28 and 34. |
| 17 | `org.nuiton.processor:nuiton-processor:1.3` | `Lesser General Public License (LGPL) v 3.0` | build-tool only, the candidate plugin's own class realm | Off allow-list. |
| 18 | `org.eclipse.sisu:org.eclipse.sisu.inject:0.9.0.M3` | `Eclipse Public License, Version 2.0` | build-tool only | Off allow-list. |
| 18(b) | `org.eclipse.sisu:org.eclipse.sisu.plexus:0.9.0.M3` | `Eclipse Public License, Version 2.0` (inherited from `sisu-inject:0.9.0.M3`) | build-tool only, in `license-maven-plugin:2.7.1`'s own realm beside row 18 | Off allow-list; found by T0.4b's build-plugin check after this audit (the audit named only `sisu.inject`). ✅ **Approved, build-tool-only, never shipped** — owner ruling 2026-10-05, `OR-83` (answer "Approve, build-only (Recommended)"), by exact coordinate and version. Not a counted row (the table's total of 40 is unchanged; this is the sibling of row 18, recorded beside it). |
| 19 | `xalan:serializer:2.7.3` | **none** (minimal stub POM, no `<parent>`, no `<licenses>`) | build-tool only | No licence at all — the case M17(ii) describes. |
| 20 | `org.codehaus.plexus:plexus-i18n:1.0-beta-10` | **none** — confirmed this round through the full chain (`plexus-i18n:1.0-beta-10` → `plexus-components:1.1.12` → `plexus:1.0.10`) | build-tool only | No licence anywhere in the chain — round 3 left this "one hop short of certainty"; round 4 closes it. |
| 21 | `org.json:json:20231013` | `Public Domain` | build-tool only | Off allow-list. |
| 22 | `org.tukaani:xz:1.10` | `0BSD` | build-tool only | Different version *and* different licence than row 12's `xz:1.9`; `0BSD` is not one of the six named licences. |
| 23 | `com.github.virtuald:curvesapi:1.08` | `BSD License` (unqualified, URL → `BSD-3-Clause`) | build-tool only | Unqualified BSD, same class of gap as rows 9/15. |
| 24 | `net.rootdev:java-rdfa:1.0.0-BETA1` | `BSD` (unqualified, URL → project's own `COPYING`) | build-tool only | Unqualified BSD, non-standard URL. |
| 25 | `xml-apis:xml-apis:1.4.01` | `Apache-2.0` + `SAX License` + `W3C License` | build-tool only | Three licences at once — a distinct multi-licence case from the dual-licence rows above. |
| 26 | `com.fasterxml.jackson.core:jackson-core:2.21.4`'s bundled FastDoubleParser third-party code | `Boost Software License - Version 1.0 - August 17th, 2003` (`META-INF/FastDoubleParser-ThirdParty-LICENSE`, its first section: the Java port of Daniel Lemire's fast_float, `Copyright (c) Daniel Lemire`) | **shipped**, compile, same modules as row-less `jackson-core` itself (Appendix A) | New in round 5 — embedded, not a separate Maven coordinate; not textually on the allow-list. Not the Business Source License `REQ-014` names under "Refused" despite the shared "BSL" initials — see the round 5 disposition's `I2` correction — so this is a **needs-a-decision** case, not a refused one. ⚠️ **Round 7 correction to the evidence:** the file (read in full) has three sections, not one — (1) the port itself, Boost as above; (2) "Third-party license for fast_float, Copyright (c) 2021 The fast_float authors. MIT License."; (3) "Third-party license for bigint, Copyright 2020 Tim Buktu. 2-clause BSD License." Rounds 5 and E.1 attributed the Boost text to `fast_float`; the file attributes it to the port's own section and MIT to `fast_float`. Sections (2) and (3) are on-list in substance and are recorded as facts (Appendix E.1), not rows. |
| 27 | `org.apache.tomcat.embed:tomcat-embed-core:10.1.55`'s bundled Java-EE/Jakarta-EE schema subcomponents | `CDDL-1.0` (21 named files) + `EPL-2.0` (14 named files) (`META-INF/LICENSE`, "APACHE TOMCAT SUBCOMPONENTS") | **shipped**, same modules as `tomcat-embed-core` itself (Appendix A) | New in round 5, counts corrected in round 6 — the jar's own top-level licence reads Apache-2.0; `jakarta/servlet/resources/` ships **45 `.xsd` + 6 `.dtd` = 51 files** (⚠️ not "51 `.xsd` files"), of which the LICENSE names exactly **35** (21 CDDL + 14 EPL, two of the EPL names typo'd in Tomcat's own upstream file) as separately licensed; the remaining ~16 carry Tomcat's own ASF Apache-2.0 header (measured directly) and one further `.dtd` elsewhere in the jar (`org/apache/tomcat/util/modeler/mbeans-descriptors.dtd`) is also Tomcat's own Apache-2.0 content. |
| 28 | `org.testcontainers:testcontainers:1.21.4`'s shaded `org.glassfish.main.external:trilead-ssh2-repackaged:4.1.2` | `CDDL-1.0` **OR** `GPL-2.0-only WITH Classpath-exception-2.0` (declared `<licenses>` element of the grandparent POM `org.glassfish.main:glassfish-nucleus-parent:4.1.2`, reached via `nucleus-external:4.1.2`: `<name>CDDL + GPLv2 with classpath exception</name>`; the artifact's own POM has no `<licenses>` block and its header comment, which licenses the POM document, reads "either ... GPL ... or ... CDDL"; all three POMs WebFetched — re-fetched in round 7) | test only | New in round 5 — a GlassFish repackage of trilead-ssh2, shaded under `org/testcontainers/shaded/com/trilead/`; the project's own `shadowJar` config strips `META-INF/LICENSE`/`NOTICE` from shaded content before it ships, so nothing in the jar itself discloses this. Neither CDDL nor a bare GPL choice is on the allow-list. |
| 29 | **Six coordinates carry the same LZMA-SDK-derived `sevenz` package** (⚠️ round 8 — was one coordinate). (a) `org.apache.commons:commons-compress:1.24.0` (**test**): `META-INF/NOTICE.txt` says so in words. (b) `commons-compress:1.26.1` (**build-tool only**, every module: `maven-jar-plugin:3.4.2` → `plexus-archiver:4.9.2`/`maven-archiver:3.6.2`). (c) `commons-compress:1.27.1` (**build-tool only**, `launcher` and `demo-launcher`: `spring-boot-maven-plugin:3.5.16`). (d) `commons-compress:1.28.0` (**build-tool only**, only if the licence plugin is adopted: `license-maven-plugin:2.7.1` → `poi-ooxml:5.5.1`; Appendix D). (e) `org.apache.maven.surefire:surefire-shared-utils:3.5.6` (**test**, every test run: `maven-surefire-plugin:3.5.6`, `maven-failsafe-plugin:3.5.6`), which shades and relocates commons-compress `1.28.0` (its own embedded `META-INF/maven/org.apache.commons/commons-compress/pom.properties`) to `org/apache/maven/surefire/shared/compress/archivers/sevenz/` | `Public Domain` (1.24.0's `META-INF/NOTICE.txt`: "The files in the package org.apache.commons.compress.archivers.sevenz were derived from the LZMA SDK, version 9.20 (C/ and CPP/7zip/), which has been placed in the public domain"). The other five coordinates carry **no such words** (their `NOTICE.txt` is the plain ASF text) — the disclosure was removed upstream, the code was not | see each coordinate above | New in round 5 for 1.24.0; ⚠️ **widened in round 8**: the round-6 statement that the finding "does not generalise across versions … No row is needed for those" rested only on the missing NOTICE text. Upstream commit `5ecccd9838f0` ("Prepare for the next release candidate", 2023-11-12, read with `gh api`) deletes exactly that paragraph from `NOTICE.txt` — and the same commit touches **no `.java` file** (files: `NOTICE.txt`, `README.md`, `RELEASE-NOTES.txt`, `pom.xml`, `src/changes/*`, `src/site/xdoc/*`); `NOTICE.txt` at tag `rel/commons-compress-1.28.0` is the four-line ASF text. Measured: the `sevenz` package holds 44 files in 1.24.0 and **46** in 1.26.1, 1.27.1 and 1.28.0 (directory entry excluded), and `LZMADecoder.class` (4,083 bytes) / `LZMA2Decoder.class` (3,775 bytes) are **byte-size identical in all four**; in `surefire-shared-utils` the relocated package holds 46 files (`LZMADecoder` 4,237 / `LZMA2Decoder` 3,901 bytes — different only because the relocation rewrites package strings). What the commit does not say is *why* the paragraph went (withdrawn as inaccurate, or merely dropped): the sources at `rel/commons-compress-1.28.0` carry an ASF header and `LZMADecoder` delegates to `org.tukaani.xz.LZMA*`, so how much of the package is truly LZMA-SDK-derived is not settled by this audit. Same "Public Domain" case as rows 12/22/31; approval is by exact coordinate, so all six are listed. |
| 30 | `org.apache.xmlbeans:xmlbeans:5.3.0`'s embedded W3C XML Schema documents (`XML.xsd`, `XMLSchema.xsd`, `ltgfmt.xsd`, `substwsdl.xsd`, `xmlconfig.xsd`, `xsdownload.xsd`); **and, round 8, `org.apache.maven.doxia:doxia-core:2.0.0`'s `xml.xsd`** (9,056 bytes, the W3C `http://www.w3.org/XML/1998/namespace` schema document, at the jar root) | `W3C License` (xmlbeans: per the jar's own `META-INF/NOTICE.txt`). doxia-core: **no licence statement at all** — the file carries no copyright or licence text (searched; the live copy at `https://www.w3.org/2001/xml.xsd` carries none either) and the jar's `LICENSE`/`NOTICE` are plain ASF | build-tool only (both: licence plugin's own class realm, Appendix D; `doxia-core` ← `license-maven-plugin:2.7.1`) | New in round 5 — six schema files confirmed embedded directly (`unzip -l`). ⚠️ **Round 8:** `poi`/`poi-ooxml`/`poi-ooxml-lite`'s NOTICE makes the same claim and their jars embed zero `.xsd`/`.dtd` files — round 5 added no row for that; the compiled/derived schema content those jars *do* carry is now **row 40**. `doxia-core`'s `xml.xsd` is the same kind of content as the xmlbeans files (a W3C-authored schema document shipped as a file) and was missed because Pass 1 looked for licence-named entries and doxia-core discloses nothing. `doxia-module-xdoc:2.0.0`'s `xdoc-2.0.xsd` is Apache-authored; it only *names* W3C XHTML entity sets in comments and imports `xml.xsd` by URL — a fact, not a row. |
| 31 | `io.netty:netty-codec-base:4.2.13.Final`'s bundled Base64 codec, `io.netty:netty-common:4.2.13.Final`'s bundled `ThreadLocalRandom` | `Public Domain` (Netty's own `NOTICE.txt` at tag `4.2.13.Final`: `license/LICENSE.base64.txt (Public Domain)`, `license/LICENSE.jsr166y.txt (Public Domain)`; source headers WebFetched: "Written by Robert Harder and released to the public domain" / "Written by Doug Lea ... released to the public domain") | **shipped**, compile, `dp-core`/`launcher`/`demo-launcher` | New in round 6 (Appendix F's complete sweep) — same "Public Domain" case already open for rows 12/21/29 (`xz`, `json`, `commons-compress`'s LZMA-SDK); neither jar carries any `LICENSE`/`NOTICE` of its own (0 of 0 across all 13 netty jars this reactor resolves), so this is invisible to every method used so far except opening the jar and reading the actual class headers. ⚠️ **Round 7:** the same NOTICE lists more "contains a modified portion of" components than the two carried over here; the per-component reading against the 13 Netty jars is in Appendix F, and the one off-label item is row 35. |
| 32 | `org.codehaus.plexus:plexus-xml:3.0.1` and `org.codehaus.plexus:plexus-utils:3.5.1` (embedded `org/codehaus/plexus/util/xml/pull/MXParser`); the same three licence files are also present in `plexus-utils:4.0.1` and `4.0.2` (covered class **absent**) | `Indiana University Extreme! Lab Software License Version 1.1.1` (`licenses/extreme.indiana.edu.license.TXT`, read in full; five conditions, including an end-user-acknowledgment clause and two naming clauses). The jars' own top-level `META-INF/LICENSE` reads Apache-2.0 | build-tool only, every build: `plexus-xml:3.0.1` ← `maven-compiler-plugin:3.14.1`, `maven-install-plugin:3.1.4`; `plexus-utils:3.5.1` ← `maven-resources-plugin:3.3.1`, `spring-boot-maven-plugin:3.5.16`; `plexus-utils:4.0.1` ← `maven-clean-plugin`, `maven-install-plugin`, `maven-jar-plugin`; `plexus-utils:4.0.2` ← `maven-compiler-plugin` (offline `dependency:resolve-plugins`, whole reactor) | New in round 7. Not on the allow-list; a BSD-like text with an acknowledgment clause, the same Apache-1.1 shape as row 14 (`jdom2`) — read, not assumed equivalent to `BSD-3-Clause`. `MXParser.java` at `plexus-utils-3.5.1` (WebFetched): "Copyright (c) 2003 Extreme! Lab, Indiana University. All rights reserved. This software is open source. See the bottom of this file for the licence." `MXParser.class` is present (with `MXSerializer`, `EntityReplacementMap`, `XmlPullParser`, `XmlPullParserException`, `XmlSerializer`: 6 classes under `xml/pull/`) in `plexus-xml:3.0.1` and `plexus-utils:3.5.1`; **0** classes there in `4.0.1`/`4.0.2`, whose three licence files are byte-identical (md5) to the others — the `4.0.x` pair is listed so no version passes silently, and the owner may rule on it separately. Two sibling files in the same directory are on-list in substance and recorded as facts: `javolution.license.TXT` (2-condition BSD) and `thoughtworks.TXT` (CruiseControl, 3-condition BSD with a no-endorsement clause). The `META-INF/NOTICE` also carries five "This product includes software developed by" credits (Indiana University Extreme! Lab, ASF, ThoughtWorks, javolution, Rome). |
| 33 | `org.testcontainers:testcontainers:1.21.4`'s shaded Guava `33.3.1-jre` — `com/google/common/cache/{Striped64,Striped64$1,Striped64$Cell,LongAdder}` and `com/google/common/hash/{Striped64,Striped64$1,Striped64$Cell,LongAdder}` (8 classes) | `Public Domain` — each source file (Guava `v33.3.1`, all four WebFetched) is headed "Written by Doug Lea with assistance from members of JCP JSR-166 Expert Group and released to the public domain, as explained at http://creativecommons.org/publicdomain/zero/1.0/" | test only | New in round 7 — the same author, terms and "undisclosed in the jar" situation as row 31 (`core/build.gradle` at `1.21.4`, WebFetched, excludes `META-INF/{NOTICE,NOTICE.txt,LICENSE,LICENSE.txt}` from shaded content). Appendix E.3 said the shaded Guava "resolves to licences already allow-listed"; that was wrong for these eight classes. The rest of the shaded Guava (2,016 classes) is Apache-2.0. `archunit`'s shaded Guava (row 9's coordinate) has no `Striped64`/`LongAdder` (Appendix F). |
| 34 | `org.testcontainers:testcontainers:1.21.4`'s shaded SnakeYAML `1.33` — `org/yaml/snakeyaml/external/biz/base64Coder/Base64Coder` | multi-licensed, "any of": `EPL` V1.0 or later, `LGPL` V2.1 or later, `GPL` V2 or later, `AL` (Apache) V2.0 or later, `BSD` (`Base64Coder.java` at tag `snakeyaml-1.33`, WebFetched: "This module is multi-licensed and may be used under the terms of any of the following licenses") | test only | New in round 7 — E.3 recorded the shaded SnakeYAML as simply on-list. A disjunctive licence with a **GPL leg** (open question: rows 2, 7, 16, 28, 34) and an unqualified "BSD" leg (same class as rows 9/15/23/24); the election available here is the Apache-2.0 leg. **The shipped `snakeyaml:2.4` is unaffected** — measured, it contains no `Base64Coder` (only `external/com/google/gdata/util/common/base/{Escaper,PercentEscaper,UnicodeEscaper}`, Google Inc. 2008, Apache-2.0 header, WebFetched at tag `snakeyaml-2.4`). |
| 35 | `io.netty:netty-codec-http:4.2.13.Final`'s `io/netty/handler/codec/http/websocketx/WebSocket08FrameDecoder` (forked from Webbit) | `BSD License` (Netty's `NOTICE.txt` at tag `4.2.13.Final`: "contains a modified portion of 'Webbit'", `license/LICENSE.webbit.txt (BSD License)`) — the file itself is a 3-condition BSD text, copyright Joe Walnes, Aslak Hellesøy and contributors, with a no-endorsement clause | **shipped**, compile, `dp-core`/`launcher`/`demo-launcher`; test in `arch-tests` | New in round 7. Unqualified "BSD License" label (same class of string as rows 9/15/23/24) on code the shipped jar does not disclose (0 `LICENSE`/`NOTICE` in any of the 13 Netty jars). Class-level provenance: `WebSocket08FrameDecoder.java` at `4.2.13.Final` (WebFetched) says "This code was forked from webbit and modified"; the class is present in the jar (with `$1` and `$State`), and the file carries Netty's own Apache-2.0 header. Substance reads as `BSD-3-Clause`; not self-approved. |
| 36 | `org.apache.servicemix.bundles:org.apache.servicemix.bundles.jzlib:1.1.3_2` (26 classes, all `com/jcraft/jzlib/*` — JCraft's JZlib 1.1.3) | POM, `META-INF/LICENSE`, `NOTICE` and `Bundle-License`: Apache-2.0 (the wrapper). Embedded content: the jar's own `META-INF/DEPENDENCIES` — "JZlib ... com.jcraft:jzlib:jar:1.1.3  License: BSD  (http://www.jcraft.com/jzlib/LICENSE.txt)"; `ymnk/jzlib` at tag `1.1.3` `LICENSE.txt` (WebFetched): 3-condition BSD, copyright ymnk, JCraft, Inc. 2000–2011, with the condition "The names of the authors may not be used to endorse or promote products derived from this software without specific prior written permission" | **shipped**, compile, `dp-core`/`launcher`/`demo-launcher` (via `com.solacesystems:sol-jcsmp:10.30.1`, the first-party exception's own transitive dependency); test in `arch-tests` | New in round 7 — listed in Appendix A as "The Apache Software License, Version 2.0" because that is what the wrapper declares. The code it ships is not ServiceMix's. Unqualified "BSD" label, same class as rows 9/15/23/24/35; substance reads as `BSD-3-Clause`; not self-approved. |
| 37 | (a) `org.apache.httpcomponents.client5:httpclient5:5.5.2` — `org/publicsuffix/list/effective_tld_names.dat` (328,040 bytes); (b) `org.apache.httpcomponents:httpclient:4.5.14` — `mozilla/public-suffix-list.txt` (245,071 bytes); (c) *flagged for the owner, provenance undisclosed in the file:* Guava's generated `com/google/thirdparty/publicsuffix/PublicSuffixPatterns` (a compiled trie of the same list) inside `org.testcontainers:testcontainers:1.21.4` (shaded: `org/testcontainers/shaded/com/google/thirdparty/publicsuffix/`) and `com.tngtech.archunit:archunit:1.5.0` (`com/tngtech/archunit/thirdparty/com/google/thirdparty/publicsuffix/`) | `Mozilla Public License, v. 2.0` — verbatim, the first lines of both data files: "This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/." (the upstream list at `publicsuffix.org` opens with the same header — fetched). The jars' own `META-INF/LICENSE` (11,358 bytes, standard Apache text) and `NOTICE` (174 bytes) do not mention Mozilla, MPL or the Public Suffix List. Guava's file (`v33.3.1`, `gh api`) is headed "GENERATED FILE - DO NOT EDIT" and Apache-2.0 and does not name the list | (a) **build-tool only**: `spring-boot-maven-plugin:3.5.16` → `httpclient5:5.5.2`, bound in `launcher` and `demo-launcher` (the only two POMs that declare that plugin; not in either exec jar's `BOOT-INF/lib`, measured 0 matches); (b) **build-tool only**, licence plugin's own class realm (direct dependency of `license-maven-plugin:2.7.1`), only if T0.4b adopts it; (c) **test** only (`arch-tests`, and every module with Testcontainers) | New in round 8 — not visible to Passes 1–4 (licence-file names, shading paths, native libraries, `DEPENDENCIES`) because the licence text lives in a **data file's first three lines**. MPL-2.0 is a file-level weak-copyleft licence, off the allow-list and not one of `REQ-014`'s "Refused" families (GPL/AGPL/SSPL/BSL/Elastic). The list is data, unmodified, and not shipped in any exec jar — but that is the owner's ruling to make. `docker-java-transport-zerodep:3.4.2` shades HttpClient 5 classes yet ships **no** list file (searched: no `effective_tld_names.dat` or `public-suffix-list.txt`), so it is not part of this row. |
| 38 | `org.apache.commons:commons-math3:3.6.1` — two pieces of embedded content, both disclosed only in an **appended section** of the jar's own `META-INF/LICENSE.txt` (24,038 bytes against the standard 11,358): (a) the **Minpack** licence, for `LevenbergMarquardtOptimizer` (three classes in `optim/nonlinear/vector/jacobian`, `fitting/leastsquares` and `optimization/general`); (b) `org.apache.commons.math3.special.BesselJ` (+ `BesselJ$BesselJResult`) | (a) "Minpack Copyright Notice (1999) University of Chicago. All rights reserved" — five numbered conditions; **condition 3: "The end-user documentation included with the redistribution, if any, must include the following acknowledgment: 'This product includes software developed by the University of Chicago, as Operator of Argonne National Laboratory.' Alternately, this acknowledgment may appear in the software itself"**, plus warranty-disclaimer and limitation-of-liability clauses (4, 5) — the same acknowledgment-clause shape as rows 14 (`jdom2`) and 32 (Extreme! Lab). (b) verbatim: "an adapted version of code translated from the netlib Fortran program, rjbesl … by R.J. Cody at Argonne National Laboratory (USA). **There is no license or copyright statement included with the original Fortran sources.**" — i.e. no licence is stated for that code at all. The POM and the jar's top-level licence read Apache-2.0 | **build-tool only**, licence plugin's own class realm: `license-maven-plugin:2.7.1` → `poi-ooxml:5.5.1` → `poi:5.5.1` → `commons-math3:3.6.1` (absent from the 9-module `dependency:tree`, measured) | New in round 8. Appendix D had read it as "Apache License, Version 2.0" and Appendix F's "2-entry" bucket never opened it. Same appended section, **on-list in substance, recorded as facts (no row)**: Ernst Hairer's `odex` (2-condition BSD, `GraggBulirschStoerIntegrator`), the Matsumoto/Nishimura Mersenne Twister (3-clause BSD, `MersenneTwister`), Kuo/Joe's Sobol direction numbers (3-clause BSD, `SobolSequenceGenerator` and `assets/.../new-joe-kuo-6.1000`), and SciPy/Enthought (3-clause BSD, `BracketFinder`, `PowellOptimizer`); plus Apache-licensed grants (`Erf`, the Google simplex classes, Orekit). |
| 39 | `org.springframework:spring-web:6.2.19` — `org/springframework/http/mime.types` (26,975 bytes) | `Public Domain` — verbatim, the file's own header: "Although created for httpd, this file is used by many software systems and has been placed in the public domain for unlimited redistribution." (and: "This file was retrieved from https://svn.apache.org/viewvc/httpd/httpd/trunk/docs/conf/mime.types?revision=1918129&view=co"). The jar's `META-INF/license.txt` is the 15,563-byte Spring text whose SUBCOMPONENTS section names only ASM, CGLIB, JavaPoet and Objenesis, and `notice.txt` names none: the file is undisclosed | **shipped**: compile in `console-gateway`, `launcher`, `demo-launcher` (`spring-web-6.2.19.jar` is in both repackaged exec jars' `BOOT-INF/lib`, measured); test in `arch-tests` | New in round 8 — the same "Public Domain content inside an otherwise-Apache jar" case as row 31, found by reading a **data file** rather than a licence file. Appendix F.2's earlier conclusion ("the Spring family's `license.txt` boilerplate … no such component named") was about the licence text and never opened the jar's data files. Searched all 261 jars for other `mime.types`/`mimetypes` files: `jakarta.activation-api` (row 7/8 family, EDL header) and `spring-boot`'s `mime-mappings.properties` (Apache-2.0 header) only. |
| 40 | (a) `org.apache.poi:poi-ooxml-lite:5.5.1` — compiled and generated schema content: **1,601** `.xsb` compiled-schema binaries (`org/apache/poi/schemas/...`) and XMLBeans-generated classes for the W3C XML-Signature schema (24 classes under `org/w3/x2000/x09/xmldsig`), the ETSI XAdES schema (79 under `org/etsi/uri/x01903`), and the ECMA-376 / Microsoft schemas (2,247 under `org/openxmlformats`, 199 under `com/microsoft/schemas`); (b) `org.apache.poi:poi-ooxml:5.5.1` — `org/apache/poi/poifs/crypt/dsig/**` (47 classes) derived from the **eID Applet** project | (a) the jar's `META-INF/NOTICE`: "This product contains W3C XML Schema documents. Copyright 2001-2003 (c) World Wide Web Consortium (Massachusetts Institute of Technology, European Research Consortium for Informatics and Mathematics, Keio University)"; its `META-INF/LICENSE` SUBCOMPONENTS section covers the ECMA-376 schemas only by the ECMA bylaws' "9.4 All documents when approved shall be made available to all interested parties without restriction", plus Microsoft/Adobe patent statements — not a named licence; nothing is said about ETSI. (b) POI's own source header (`TimeStampService.java`, `apache/poi`, `gh api`): "This product contains an ASLv2 licensed version of the OOXML signer package from the eID Applet project … Copyright (C) 2008-2014 FedICT"; the eID Applet repository's own `LICENSE.txt` is the **GNU LGPL v3** (`e-Contract/eid-applet`, `gh api`), and `poi-ooxml`'s NOTICE says only "contains parts of the eID Applet project" | **build-tool only**, licence plugin's own class realm: `license-maven-plugin:2.7.1` → `poi-ooxml:5.5.1` → `poi-ooxml-lite:5.5.1` | New in round 8. Round 5 (E.5) added no row for POI because the jars hold **zero `.xsd`/`.dtd` files**, yet added row 30 for `xmlbeans` on the same NOTICE claim; that drew the line at *file format*. The content is here in compiled and derived form (a compiled `.xsb` is the W3C/ECMA schema, compiled). Either the owner treats compiled/derived schema content like row 30, or records that it does not need a licence; this audit does not choose. For (b), the ASLv2 relicensing is POI's own assertion about code whose upstream is LGPL-3.0 — a provenance fact the owner should see, not one this audit can verify. `poi`/`poi-ooxml` also embed an MIT text (`ReplacingInputStream`, Inbot) and an Apache-2.0 Scala-derived `ExceptionUtils` — on-list facts. |
| 41 | **Container images the build pulls during `verify`** (not Maven coordinates): `busybox:1.36` (dp-core `DockerFixtures.java:113`, `DockerEvidenceSecrecyIT.java:79`, `DockerEngineClientIT.java:38`), `postgres:14` (cp-core `SmControlPostgres.java:68`, launcher `TwoSchemaTestDatabase.java:65`), and Testcontainers 1.21.4's default `testcontainers/ryuk` image (not disabled by the scratchpad `tc-env.sh`); for completeness, the demo images (`postgres:16-alpine` in `demo-launcher/compose.yaml`; the broker and cache images in `demo/docker-compose.yml`) | `busybox`: **GPL-2.0 only** — busybox.net/license.html, re-verified by the integrator 2026-09-30 (a name in `REQ-014`'s refused family); `postgres:14` and `ryuk`: not read by this audit | **test/build only** — pulled by failsafe (`integration-test`/`verify`, cp-core `pom.xml:110`, dp-core `pom.xml:235`, launcher `pom.xml:91`); never shipped | Added by the integrator from round-9 MAJOR 1. **Owner scope question:** does `REQ-014`'s "builds with" cover container images a test pulls (the same kind of scope question this audit already asks about the Maven distribution and the JDK)? If yes, `busybox` needs a replacement or an explicit exception. |
| 42 | `com.github.ben-manes.caffeine:caffeine:3.1.8` — `com/github/benmanes/caffeine/cache/StripedBuffer.class` | Public Domain / CC0 — its source at tag `v3.1.8` (lines 17–19): "Written by Doug Lea with assistance from members of JCP JSR-166 Expert Group and released to the public domain, as explained at http://creativecommons.org/publicdomain/zero/1.0/", under Ben Manes's Apache header (reviewer's measurement, `gh api`) | **build-tool only**, licence plugin's class realm: `license-maven-plugin` → `odfdom-java` → `jena-core` → `jena-base` → `caffeine` | Added by the integrator from round-9 MAJOR 2 — the same content, author and terms as rows 31 and 33, missed because the row-33 search keyed on the class names `Striped64`/`LongAdder`. ⚠️ Appendix D's "Apache License, Version 2.0 … Allowed (mapping only)" cell for this coordinate is **not yet corrected** (open finding). |
| 43 | `org.apache.tomcat.embed:tomcat-embed-core:10.1.55` — schema/DTD files carrying Tomcat's ASF header but **not ASF-authored**: W3C `jakarta/servlet/resources/xml.xsd`, `XMLSchema.dtd` (`-//W3C//DTD XMLSCHEMA 200102//EN`), `datatypes.dtd`; Sun `web-app_2_3.dtd`, `web-jsptaglibrary_1_2.dtd`; IBM `j2ee_web_services_1_1.xsd`, `j2ee_web_services_client_1_1.xsd` (at least nine files, reviewer's count) | The only licence statement is the ASF header Tomcat added — a relicensing assertion over third-party-authored content, the row-40(b) standard; `xml.xsd` is the same W3C document as row 30 | **shipped** — in both repackaged exec jars | Added by the integrator from round-9 MAJOR 3. ⚠️ The text elsewhere in this audit that calls these files "Tomcat's own content … Apache-2.0" (row 27, round-6 disposition item 4, Appendix E.1) is **not yet struck** (open finding). |

**Complete plugin-realm coverage (round 4) found no 26th row** by resolved-GAV scanning — true of that method,
round 5 found five more by opening jars directly (rows 26–30), round 6 found one further row (31) by the same
method, applied completely rather than to a sample, and round 7 found five more (32–36) after closing the gaps the
seventh review named — none of these eleven is a Maven coordinate a resolved-GAV scan missed; they are content
embedded *inside* a resolved jar's own bytes (the coordinates behind rows 32 and 36 were in fact listed, as Apache-2.0,
with the embedded licence unread). Every one of the 86 distinct coordinates in Appendix C and
every one of the 72 in Appendix D that is not already covered by one of the 40 rows above resolves **in substance** to
Apache-2.0, MIT, or `BSD-2/3-Clause` — ⚠️ **round 8: this claim was wrong a second time.** Round 8 opened the
*data files and appended licence sections* of those coordinates (not just licence-named files) and found that
`httpclient5:5.5.2` and `httpclient:4.5.14` (row 37), `commons-math3:3.6.1` (row 38), `poi-ooxml-lite:5.5.1` and
`poi-ooxml:5.5.1` (row 40), `doxia-core:2.0.0` (row 30, widened), and `commons-compress:1.26.1`/`1.27.1`/`1.28.0`
plus `surefire-shared-utils:3.5.6` (row 29, widened) do not. ⚠️ **Round 7: this used to read "zero of them needed a new owner-decision
row"; four of them did (`plexus-xml:3.0.1`, `plexus-utils:3.5.1/4.0.1/4.0.2`, row 32), because their embedded
licence files were not read.** The rest needed none by the methods listed in Appendix F. ⚠️ **Round 6 correction — this used to read "...possibly under a non-canonical spelling
that needs only a `licenseMerges` mapping, not an owner decision," which is exactly the self-allow-listing
Appendix B exists to stop, and it contradicts Appendix B's own "none of the above is allow-listed by this audit."
Struck.** Every non-canonical spelling among those coordinates still goes to the owner via **Appendix B**, which
inventories all of them in one place — see the appendices for the full per-coordinate accounting and Appendix B
for the spelling sign-off.

**Other open questions, not coordinate-specific (unchanged from round 3 unless marked, kept separate from the
table above):**

- **New in round 5 — whether the Maven distribution itself (and the JDK) is in scope.** There is no `mvnw`/
  `.mvn/` in this tree, so every build on this reactor runs whatever Maven is on the machine's `PATH`; measured on
  this machine, that is Homebrew's Maven `3.9.16`, whose own `lib/` ships `org.eclipse.sisu.inject`/
  `org.eclipse.sisu.plexus:1.0.0` under `EPL-2.0` (plus Apache-2.0 `guice`/`guava`/`jansi`/`plexus-cipher`/
  `plexus-sec-dispatcher`, none of which the owner has been asked about either — "need no decision" below was this
  audit deciding on the owner's behalf). `REQ-014` says "builds with," and Maven's own bundled EPL-2.0 component
  has never been asked about, on the same footing as `logback`/`jakarta.annotation-api`. ⚠️ **Round 6 addition,
  per `I2` — the round-5 inventory of this same `lib/` directory was incomplete:** `ls` of that directory also
  shows `javax.annotation-api-1.3.2.jar`, whose own `.license` file reads "COMMON DEVELOPMENT AND DISTRIBUTION
  LICENSE (CDDL) Version 1.1", and `aopalliance-1.0.jar`, whose own `.license` file reads "Public Domain" —
  neither previously named, both off `REQ-014`'s allow-list. If the owner rules the Maven distribution in scope,
  these two join the EPL-2.0 Sisu component as needing a decision; the Apache-2.0-licensed libraries in this same
  `lib/` directory need no *decision* only in the sense that their substance is already on the allow-list — that
  is still the owner's ruling to make once the scope question itself is answered, not something this audit
  settles by omission. Separately,
  and not previously measured anywhere in this report: `JAVA_HOME` is unset on this machine, and `mvn -version`'s
  own reported JDK does not match plain `java -version` on `PATH` — which JDK actually builds this reactor is
  itself ambiguous here, before any licence question about it is even asked. Neither Maven's own distribution nor
  the JDK is pinned by anything in this tree; the owner should say explicitly whether that is in scope for
  `REQ-014` or deliberately excluded (e.g. by adopting a Maven Wrapper that pins a version, which would also make
  this reactor's builds reproducible independent of the licence question).
- **New in round 8 — whether `maven-site-plugin:3.12.1` and `maven-deploy-plugin:3.1.4` are inside `REQ-014`'s
  "builds with".** Both are bound in every module's effective POM (the site and deploy lifecycles) and neither has a
  POM cached anywhere in `~/.m2` (only the `.jar`, `.jar.sha1` and `_remote.repositories`), so offline
  `dependency:resolve-plugins` lists only the plugin's own coordinate and **their transitive realms are unaudited** —
  not confirmed clean. The only evidence available offline is each jar's own `META-INF/DEPENDENCIES`: for
  `maven-site-plugin:3.12.1` it lists 38 BSD-2-Clause coordinates (`com.vladsch.flexmark:*`), `org.nibor.autolink:autolink:0.6.0`
  and `org.jsoup:jsoup:1.10.2` (MIT), `org.tukaani:xz:1.9` (Public Domain — row 12),
  **`org.eclipse.aether:aether-util:1.0.0.v20140518` (Eclipse Public License, Version 1.0 — off the allow-list, not in the table)**
  and **nine `org.eclipse.jetty:*:9.4.46.v20220331` coordinates (`jetty-http`, `-io`, `-security`, `-server`, `-servlet`,
  `-util`, `-util-ajax`, `-webapp`, `-xml`), each listed with two licences, "Apache Software License - Version 2.0" and
  "Eclipse Public License - Version 1.0"** (the listing gives no connective; not read as OR without checking Jetty's own text);
  for `maven-deploy-plugin:3.1.4` only Apache-2.0 lines. That listing is the plugin authors' own, from the plugin's build, and may
  differ from what would resolve here. The owner should rule the two plugins **in scope** (then the realms must be
  resolved online once and walked, adding at least the `aether-util` row) or **out of scope** (for example because the
  site and deploy phases are never run), instead of the gap sitting only in an appendix note.
- **Whether `GPL2 w/ CPE` (row 2's second declared licence) counts as `REQ-014`'s "Refused: GPL/AGPL"** or is
  covered by the same per-coordinate approval as its `EPL-2.0` half — see "Refused-family licences found" above.
  This changes whether row 2 needs a *refused-licence exception* (a different, presumably higher, bar) or an
  ordinary approval. ⚠️ Round 7: the same question applies to rows 7, 16, 28 and 34 (the round-6 widening already
  named 7 and 28; rows 16 and 34 are new this round).
- **Whether the `missingFile`/`overrideUrl`/`overrideFile`/`missingLicensesFileArtifact` channels need to be
  locked down or brought under owner-approval discipline** in T0.4b's design — see M17(ii)'s new paragraph. None
  of these files exist in the tree today, but the mechanism exists and a future PR could add one without anyone
  noticing it bypasses the exceptions file.
- **Whether Solution Manager's own reactor artifacts' inherited Apache-2.0 licence claim (via
  `spring-boot-starter-parent`, since `backend/pom.xml` declares no `<licenses>` of its own) is the licence the
  owner intends SM's own code to ship under**, or whether `backend/pom.xml` should declare its own `<licenses>`
  block explicitly rather than inherit one. Not a `REQ-014` third-party question, but a fact this audit surfaced
  that has no home in Q-L1/Q-L2 as currently framed.

Also left open, as T0.4b's own design questions, not decided here: which Q-L2 option the owner picks (M17(iii)
shows the native plugin cannot express option (a) through its own `includedLicenses`/`excludedLicenses` alone — a
custom multi-licence check is needed under (a) regardless), how T0.4b's "second check" for build plugins is
implemented (M17(v)), and — new this round — that `excludedGroups`/`includedGroups`/`excludedArtifacts`/
`includedArtifacts` (M17(iv)) are unanchored-substring regexes on the very goal T0.4b binds, so any use of them
in T0.4b's own configuration needs the anchored-pattern-plus-near-miss-test discipline Step 1 already requires.

---

## Appendix A — full per-artifact record (measured, this round)

Every artifact `aggregate-add-third-party` resolved (123 total: 116 third-party + 7 Solution
Manager's own reactor modules — see the note above), coordinates sorted, with the exact module(s)
and scope(s) it resolves in (from an offline `mvn -o -f backend/pom.xml dependency:tree`, one run
per module, over all 9 modules — no additional online resolution). Licence name(s) are the plugin's
own `THIRD-PARTY.txt` output, verbatim. This replaces the ephemeral scratchpad file as the
committed record the task's Files block requires.

### A.1 — Third-party (116 artifacts)

| Coordinates | Declared licence(s) (verbatim) | Module : scope |
|---|---|---|
| `ch.qos.logback:logback-classic:1.5.34` | (EPL-2.0) (LGPL-2.1-only) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `ch.qos.logback:logback-core:1.5.34` | (EPL-2.0) (LGPL-2.1-only) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `com.fasterxml.jackson.core:jackson-annotations:2.21` | (The Apache Software License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile; mesh-events:compile |
| `com.fasterxml.jackson.core:jackson-core:2.21.4` | (The Apache Software License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile; mesh-events:compile |
| `com.fasterxml.jackson.core:jackson-databind:2.21.4` | (The Apache Software License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile; mesh-events:compile |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.21.4` | (The Apache Software License, Version 2.0) | arch-tests:test; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.4` | (The Apache Software License, Version 2.0) | arch-tests:test; console-gateway:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.21.4` | (The Apache Software License, Version 2.0) | arch-tests:test; launcher:compile; local Cache demo launcher:compile |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.4` | (The Apache Software License, Version 2.0) | arch-tests:test; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile; mesh-events:compile |
| `com.fasterxml.jackson.module:jackson-module-parameter-names:2.21.4` | (The Apache Software License, Version 2.0) | arch-tests:test; launcher:compile; local Cache demo launcher:compile |
| `com.github.docker-java:docker-java-api:3.4.2` | (The Apache Software License, Version 2.0) | cp-core:test; dp-core:test; launcher:test |
| `com.github.docker-java:docker-java-transport:3.4.2` | (The Apache Software License, Version 2.0) | arch-tests:test; cp-core:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `com.github.docker-java:docker-java-transport-zerodep:3.4.2` | (The Apache Software License, Version 2.0) | arch-tests:test; cp-core:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `com.hierynomus:asn-one:0.6.0` | (The Apache License, Version 2.0) | arch-tests:test; dp-core:runtime; launcher:runtime; local Cache demo launcher:runtime |
| `com.hierynomus:sshj:0.40.0` | (The Apache License, Version 2.0) | arch-tests:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `com.jayway.jsonpath:json-path:2.9.0` | (The Apache Software License, Version 2.0) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `com.solace:solace-serdes:1.0.0` | (Apache-2.0) | arch-tests:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `com.solacesystems:sol-jcsmp:10.30.1` | (Solace API License Agreement Version 1.1) | arch-tests:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `com.tngtech.archunit:archunit:1.5.0` | (BSD) (The Apache Software License, Version 2.0) | arch-tests:test |
| `com.tngtech.archunit:archunit-junit5:1.5.0` | (The Apache Software License, Version 2.0) | arch-tests:test |
| `com.tngtech.archunit:archunit-junit5-api:1.5.0` | (The Apache Software License, Version 2.0) | arch-tests:test |
| `com.tngtech.archunit:archunit-junit5-engine:1.5.0` | (The Apache Software License, Version 2.0) | arch-tests:test |
| `com.tngtech.archunit:archunit-junit5-engine-api:1.5.0` | (The Apache Software License, Version 2.0) | arch-tests:test |
| `com.vaadin.external.google:android-json:0.0.20131108.vaadin1` | (Apache License 2.0) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `com.zaxxer:HikariCP:6.3.3` | (The Apache Software License, Version 2.0) | arch-tests:test; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `commons-logging:commons-logging:1.2` | (The Apache Software License, Version 2.0) | arch-tests:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `io.micrometer:micrometer-commons:1.15.12` | (The Apache Software License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `io.micrometer:micrometer-observation:1.15.12` | (The Apache Software License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `io.netty:netty-buffer:4.2.13.Final` | (Apache License, Version 2.0) | arch-tests:runtime; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `io.netty:netty-codec-base:4.2.13.Final` | (Apache License, Version 2.0) | arch-tests:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `io.netty:netty-codec-compression:4.2.13.Final` | (Apache License, Version 2.0) | arch-tests:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `io.netty:netty-codec-http:4.2.13.Final` | (Apache License, Version 2.0) | arch-tests:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `io.netty:netty-codec-socks:4.2.13.Final` | (Apache License, Version 2.0) | arch-tests:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `io.netty:netty-common:4.2.13.Final` | (Apache License, Version 2.0) | arch-tests:runtime; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `io.netty:netty-handler:4.2.13.Final` | (Apache License, Version 2.0) | arch-tests:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `io.netty:netty-handler-proxy:4.2.13.Final` | (Apache License, Version 2.0) | arch-tests:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `io.netty:netty-resolver:4.2.13.Final` | (Apache License, Version 2.0) | arch-tests:runtime; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `io.netty:netty-transport:4.2.13.Final` | (Apache License, Version 2.0) | arch-tests:runtime; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `io.netty:netty-transport-classes-epoll:4.2.13.Final` | (Apache License, Version 2.0) | arch-tests:runtime; dp-core:runtime; launcher:runtime; local Cache demo launcher:runtime |
| `io.netty:netty-transport-native-epoll:4.2.13.Final` | (Apache License, Version 2.0) | arch-tests:runtime; dp-core:runtime; launcher:runtime; local Cache demo launcher:runtime |
| `io.netty:netty-transport-native-unix-common:4.2.13.Final` | (Apache License, Version 2.0) | arch-tests:runtime; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `jakarta.activation:jakarta.activation-api:2.1.4` | (EDL 1.0) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `jakarta.annotation:jakarta.annotation-api:2.1.1` | (EPL 2.0) (GPL2 w/ CPE) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `jakarta.xml.bind:jakarta.xml.bind-api:4.0.5` | (Eclipse Distribution License - v 1.0) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `junit:junit:4.13.2` | (Eclipse Public License 1.0) | cp-core:test; dp-core:test; launcher:test |
| `net.bytebuddy:byte-buddy:1.17.8` | (Apache License, Version 2.0) | console-gateway:test; cp-api:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `net.bytebuddy:byte-buddy-agent:1.17.8` | (Apache License, Version 2.0) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `net.java.dev.jna:jna:5.13.0` | (Apache-2.0) (LGPL-2.1-or-later) | arch-tests:test; cp-core:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `net.minidev:accessors-smart:2.5.2` | (The Apache Software License, Version 2.0) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `net.minidev:json-smart:2.5.2` | (The Apache Software License, Version 2.0) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `org.apache.commons:commons-compress:1.24.0` | (Apache-2.0) | cp-core:test; dp-core:test; launcher:test |
| `org.apache.logging.log4j:log4j-api:2.24.3` | (Apache-2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.apache.logging.log4j:log4j-to-slf4j:2.24.3` | (Apache-2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.apache.servicemix.bundles:org.apache.servicemix.bundles.jzlib:1.1.3_2` | (The Apache Software License, Version 2.0) — ⚠️ round 7: the wrapper's own declaration; the 26 classes are JCraft's JZlib 1.1.3 (BSD), **owner-table row 36** | arch-tests:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.apache.tomcat.embed:tomcat-embed-core:10.1.55` | (Apache License, Version 2.0) | arch-tests:test; console-gateway:provided; launcher:compile; local Cache demo launcher:compile |
| `org.apache.tomcat.embed:tomcat-embed-el:10.1.55` | (Apache License, Version 2.0) | arch-tests:test; launcher:compile; local Cache demo launcher:compile |
| `org.apache.tomcat.embed:tomcat-embed-websocket:10.1.55` | (Apache License, Version 2.0) | arch-tests:test; launcher:compile; local Cache demo launcher:compile |
| `org.apiguardian:apiguardian-api:1.1.2` | (The Apache License, Version 2.0) | arch-tests:test; console-gateway:test; cp-api:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test; mesh-events:test |
| `org.assertj:assertj-core:3.27.7` | (Apache-2.0) | console-gateway:test; cp-api:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `org.awaitility:awaitility:4.2.2` | (Apache 2.0) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `org.bouncycastle:bcpkix-jdk18on:1.80` | (Bouncy Castle Licence) | arch-tests:test; dp-core:runtime; launcher:runtime; local Cache demo launcher:runtime |
| `org.bouncycastle:bcprov-jdk18on:1.80` | (Bouncy Castle Licence) | dp-core:runtime; local Cache demo launcher:runtime |
| `org.bouncycastle:bcprov-jdk18on:1.86` | (Bouncy Castle Licence) | arch-tests:test; cp-core:compile; launcher:compile |
| `org.bouncycastle:bcutil-jdk18on:1.80.2` | (Bouncy Castle Licence) | arch-tests:test; dp-core:runtime; launcher:runtime; local Cache demo launcher:runtime |
| `org.flywaydb:flyway-core:11.7.2` | (Apache License, Version 2.0) | arch-tests:test; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.flywaydb:flyway-database-postgresql:11.7.2` | (Apache License, Version 2.0) | arch-tests:test; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.hamcrest:hamcrest:3.0` | (BSD-3-Clause) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `org.hamcrest:hamcrest-core:3.0` | (BSD-3-Clause) | cp-core:test; dp-core:test; launcher:test |
| `org.jetbrains:annotations:17.0.0` | (The Apache Software License, Version 2.0) | cp-core:test; dp-core:test; launcher:test |
| `org.junit.jupiter:junit-jupiter:5.12.2` | (Eclipse Public License v2.0) | arch-tests:test; console-gateway:test; cp-api:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test; mesh-events:test |
| `org.junit.jupiter:junit-jupiter-api:5.12.2` | (Eclipse Public License v2.0) | arch-tests:test; console-gateway:test; cp-api:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test; mesh-events:test |
| `org.junit.jupiter:junit-jupiter-engine:5.12.2` | (Eclipse Public License v2.0) | arch-tests:test; console-gateway:test; cp-api:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test; mesh-events:test |
| `org.junit.jupiter:junit-jupiter-params:5.12.2` | (Eclipse Public License v2.0) | arch-tests:test; console-gateway:test; cp-api:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test; mesh-events:test |
| `org.junit.platform:junit-platform-commons:1.12.2` | (Eclipse Public License v2.0) | arch-tests:test; console-gateway:test; cp-api:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test; mesh-events:test |
| `org.junit.platform:junit-platform-engine:1.12.2` | (Eclipse Public License v2.0) | arch-tests:test; console-gateway:test; cp-api:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test; mesh-events:test |
| `org.mockito:mockito-core:5.17.0` | (MIT) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `org.mockito:mockito-junit-jupiter:5.17.0` | (MIT) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `org.objenesis:objenesis:3.3` | (Apache License, Version 2.0) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `org.opentest4j:opentest4j:1.3.0` | (The Apache License, Version 2.0) | arch-tests:test; console-gateway:test; cp-api:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test; mesh-events:test |
| `org.osgi:org.osgi.annotation:6.0.0` | (Apache License, Version 2.0) | arch-tests:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.ow2.asm:asm:9.7.1` | (BSD-3-Clause) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `org.postgresql:postgresql:42.7.11` | (BSD-2-Clause) | arch-tests:test; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.rnorth.duct-tape:duct-tape:1.0.8` | (MIT) | cp-core:test; dp-core:test; launcher:test |
| `org.skyscreamer:jsonassert:1.5.3` | (The Apache Software License, Version 2.0) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `org.slf4j:jul-to-slf4j:2.0.18` | (MIT) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.slf4j:slf4j-api:2.0.18` | (MIT) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework:spring-aop:6.2.19` | (Apache License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework:spring-beans:6.2.19` | (Apache License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework:spring-context:6.2.19` | (Apache License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework:spring-core:6.2.19` | (Apache License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework:spring-expression:6.2.19` | (Apache License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework:spring-jcl:6.2.19` | (Apache License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework:spring-jdbc:6.2.19` | (Apache License, Version 2.0) | arch-tests:test; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework:spring-test:6.2.19` | (Apache License, Version 2.0) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `org.springframework:spring-tx:6.2.19` | (Apache License, Version 2.0) | arch-tests:test; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework:spring-web:6.2.19` | (Apache License, Version 2.0) — ⚠️ round 8: `org/springframework/http/mime.types` is headed "placed in the public domain", **row 39** | arch-tests:test; console-gateway:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework:spring-webmvc:6.2.19` | (Apache License, Version 2.0) | arch-tests:test; console-gateway:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework.boot:spring-boot:3.5.16` | (Apache License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework.boot:spring-boot-autoconfigure:3.5.16` | (Apache License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework.boot:spring-boot-starter:3.5.16` | (Apache License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework.boot:spring-boot-starter-jdbc:3.5.16` | (Apache License, Version 2.0) | arch-tests:test; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework.boot:spring-boot-starter-json:3.5.16` | (Apache License, Version 2.0) | arch-tests:test; launcher:compile; local Cache demo launcher:compile |
| `org.springframework.boot:spring-boot-starter-logging:3.5.16` | (Apache License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.springframework.boot:spring-boot-starter-test:3.5.16` | (Apache License, Version 2.0) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `org.springframework.boot:spring-boot-starter-tomcat:3.5.16` | (Apache License, Version 2.0) | arch-tests:test; launcher:compile; local Cache demo launcher:compile |
| `org.springframework.boot:spring-boot-starter-web:3.5.16` | (Apache License, Version 2.0) | arch-tests:test; launcher:compile; local Cache demo launcher:compile |
| `org.springframework.boot:spring-boot-test:3.5.16` | (Apache License, Version 2.0) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `org.springframework.boot:spring-boot-test-autoconfigure:3.5.16` | (Apache License, Version 2.0) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `org.springframework.security:spring-security-crypto:6.5.11` | (Apache License, Version 2.0) | arch-tests:test; cp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `org.testcontainers:database-commons:1.21.4` | (MIT) | cp-core:test; dp-core:test; launcher:test |
| `org.testcontainers:jdbc:1.21.4` | (MIT) | cp-core:test; dp-core:test; launcher:test |
| `org.testcontainers:junit-jupiter:1.21.4` | (MIT) | cp-core:test; dp-core:test |
| `org.testcontainers:postgresql:1.21.4` | (MIT) | cp-core:test; dp-core:test; launcher:test |
| `org.testcontainers:testcontainers:1.21.4` | (MIT) | cp-core:test; dp-core:test; launcher:test |
| `org.xmlunit:xmlunit-core:2.10.4` | (The Apache Software License, Version 2.0) | console-gateway:test; cp-core:test; dp-core:test; launcher:test; local Cache demo launcher:test |
| `org.yaml:snakeyaml:2.4` | (Apache License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |

### A.2 — Solution Manager's own reactor artifacts (7, inherited Apache-2.0, not third-party)

| Coordinates | Declared licence(s) (verbatim, inherited from `spring-boot-starter-parent`) | Consumed by (module : scope) |
|---|---|---|
| `com.solace.sm:console-gateway:0.1.0-SNAPSHOT` | (Apache License, Version 2.0) | arch-tests:test; launcher:compile |
| `com.solace.sm:cp-api:0.1.0-SNAPSHOT` | (Apache License, Version 2.0) | arch-tests:test; console-gateway:compile; cp-core:compile; launcher:compile |
| `com.solace.sm:cp-core:0.1.0-SNAPSHOT` | (Apache License, Version 2.0) | arch-tests:test; launcher:compile |
| `com.solace.sm:dp-api:0.1.0-SNAPSHOT` | (Apache License, Version 2.0) | arch-tests:test; console-gateway:compile; dp-core:compile; launcher:compile; local Cache demo launcher:compile |
| `com.solace.sm:dp-core:0.1.0-SNAPSHOT` | (Apache License, Version 2.0) | arch-tests:test; launcher:compile; local Cache demo launcher:compile |
| `com.solace.sm:launcher:0.1.0-SNAPSHOT` | (Apache License, Version 2.0) | arch-tests:test |
| `com.solace.sm:mesh-events:0.1.0-SNAPSHOT` | (Apache License, Version 2.0) | arch-tests:test; dp-core:compile; launcher:compile; local Cache demo launcher:compile |

**Source of this appendix:** `mvn -f backend/pom.xml org.codehaus.mojo:license-maven-plugin:2.7.1:aggregate-add-third-party` (the single online resolution) for coordinates and licence names, cross-joined with a fresh offline `mvn -o -f backend/pom.xml dependency:tree -Dscope=test` (this round, no online resolution) for the module/scope column. All 123 rows matched by exact group:artifact:version between the two runs; none required a manual guess.


---

## Appendix B — licence-name variant inventory (round 5)

**Why this appendix exists.** Appendices C and D mark many rows "Allowed (mapping only)" — this audit's own read
that a non-canonical string is the same licence in substance as one on `REQ-014`'s six-item allow-list. A review
found that treatment self-approving: a name variant, however good the equivalence evidence, still goes to the
owner for confirmation before `licenseMerges` may fold it into the canonical name (the same "approve by exact
coordinates, not by pattern" discipline `REQ-014`/Q-L1 already applies to coordinates). This appendix inventories
every variant string measured anywhere in this report, once, so the owner confirms the whole set in one sitting
rather than each mapping being decided piecemeal by whichever appendix happened to record it first.

**Apache-2.0 — 8 distinct strings** (not "six," and not "six plus an eighth" as earlier rounds said in two
different places; all eight counted together here):

| Literal string measured | Found on (example coordinate) |
|---|---|
| `Apache-2.0` | `com.solace:solace-serdes:1.0.0` (Appendix A), most `org.apache.maven.plugins:*` (Appendix C) |
| `Apache License, Version 2.0` | `org.springframework:*:6.2.19` (Appendix A) |
| `The Apache Software License, Version 2.0` | `com.fasterxml.jackson.core:jackson-core:2.21.4` (Appendix A) |
| `The Apache License, Version 2.0` | `com.hierynomus:sshj:0.40.0` (Appendix A) |
| `Apache License 2.0` | `com.vaadin.external.google:android-json:0.0.20131108.vaadin1` (Appendix A) |
| `Apache 2.0` | `org.awaitility:awaitility:4.2.2` (Appendix A) |
| `Apache 2` (no ".0") | `org.odftoolkit:odfdom-java:0.12.0`, `org.roaringbitmap:RoaringBitmap:1.0.0` (Appendix D) |
| `Apache Public License 2.0` | `org.sonatype.plexus:plexus-build-api:0.0.7` (Appendix C) — evidence: no `<licenses>` in its own POM; its parent `org.sonatype.spice:spice-parent:15` declares this name with `<url>http://www.apache.org/licenses/LICENSE-2.0</url>`, the canonical Apache-2.0 text |

**MIT — 3 distinct strings:**

| Literal string measured | Found on (example coordinate) |
|---|---|
| `MIT` (bare) | `org.mockito:mockito-core:5.17.0`, `org.testcontainers:*:1.21.4` family, `org.rnorth.duct-tape:duct-tape:1.0.8` (Appendix A, via the plugin's own `THIRD-PARTY.txt` output) |
| `MIT License` | `org.slf4j:slf4j-api:1.7.36`, `org.slf4j:slf4j-simple:1.7.36` (Appendix C/D), `com.github.andrewoma.dexx:collection:0.7` (Appendix D) |
| `The MIT License` | `org.checkerframework:checker-qual:3.37.0` (Appendix D) |

**BSD-2-Clause — 2 distinct strings:**

| Literal string measured | Found on (example coordinate) |
|---|---|
| `BSD-2-Clause` | `org.postgresql:postgresql:42.7.11` (Appendix A) |
| `BSD 2-Clause License` | `com.github.luben:zstd-jni:1.5.5-11` **and** `1.5.7-6` (owner table row 16; both versions, one decision — see the round 5 disposition above for the inconsistency this corrects; ⚠️ round 7: this string is the POM's description of the JNI binding — the statically embedded libzstd is `BSD-style` OR `GPLv2`, also row 16) |

**Not in this appendix, and why:** unqualified `"BSD"` (ArchUnit, `curvesapi`, `java-rdfa`, `antlr4-runtime`) is
**not** treated as a spelling variant of `BSD-2-Clause`/`BSD-3-Clause` here — which BSD text applies is not
self-evident from the string the way the Apache/MIT/BSD-2-Clause variants above are, so each stays its own
owner-table row (9, 15, 23, 24 and, from round 7, 35 and 36) with its own evidence, rather than being folded into this inventory.

**Disposition:** none of the above is allow-listed by this audit. The owner confirms each mapping (or rejects it,
sending that coordinate to its own owner-table row instead) before `T0.4b`'s `licenseMerges` configuration is
written.

---

## Appendix C — complete plugin-realm coverage (round 4)

Every plugin-realm coordinate this reactor resolves, from an offline `mvn -o -f backend/pom.xml
dependency:resolve-plugins` run over the **whole reactor** (all 10 modules — the earlier rounds' single-module
invocation on `backend/pom.xml` alone only reached the root POM's own default-lifecycle bindings and missed
every plugin declared only in a child module, e.g. `maven-failsafe-plugin`, `spring-boot-maven-plugin`).
**96 resolved-artifact lines, 86 distinct `group:artifact:version` coordinates** (10 lines are the same
plugin GAV counted twice — once as its own `maven-plugin` descriptor, once as the `jar` dependency of its
own realm). Each coordinate below is walked, POM-and-parent-chain, from `~/.m2` (two — `maven-deploy-plugin`,
`maven-site-plugin` — had no cached POM; fetched from `repo1.maven.org` by WebFetch, per `I2`). No name is
asserted from memory.

⚠️ **Round 5 note on the "Status" column:** "Allowed (mapping only)" below is this audit's own read that the
literal string resolves in substance to an allow-listed licence — it is evidence, not an owner confirmation.
Every non-canonical spelling still needs the owner's sign-off before `licenseMerges` may treat it as such;
**Appendix B** inventories every spelling variant this report found, in one place, for that sign-off.

⚠️ **Round 6 scope note:** "complete plugin-realm coverage" above means every coordinate `dependency:resolve-plugins`
itself surfaces, not every coordinate those two plugins could in principle pull. `maven-deploy-plugin:3.1.4` and
`maven-site-plugin:3.12.1` have **no POM cached anywhere in `~/.m2`** (confirmed: only their own `.jar` is
present) — `dependency:resolve-plugins` could only ever have listed their own coordinate for them, never a
transitive tree, and none was walked. The risk is low because neither plugin is bound to a phase `verify` runs
(`maven-site-plugin` is present but unbound by default; `maven-deploy-plugin` binds only to `deploy`), but this is
an unaudited scope limit, not a confirmed-clean one — the licence-plugin's own class realm (Appendix D) shows what
a large, previously-invisible toolchain can look like once actually resolved (`doxia`/`velocity`/reporting, 72
artifacts), and `maven-site-plugin` binds a structurally similar `doxia`/`velocity` toolchain of its own that this
audit has not walked. ⚠️ **Round 8:** this limit now also stands in the owner-facing "Other open questions" (with what
the two plugin jars' own `META-INF/DEPENDENCIES` files disclose), rather than only here.

| Coordinate | Declared licence (verbatim, from the POM actually carrying it) | Status |
|---|---|---|
| `com.fasterxml.jackson.core:jackson-annotations:2.21` | The Apache Software License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `com.fasterxml.jackson.core:jackson-core:2.21.4` | The Apache Software License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `com.fasterxml.jackson.core:jackson-databind:2.21.4` | The Apache Software License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `com.fasterxml.jackson.module:jackson-module-parameter-names:2.21.4` | The Apache Software License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `com.github.luben:zstd-jni:1.5.5-11` | BSD 2-Clause License (https://opensource.org/licenses/BSD-2-Clause) | **Needs owner decision** — row 16 |
| `com.google.code.findbugs:jsr305:3.0.2` | The Apache Software License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `com.thoughtworks.qdox:qdox:2.2.0` | The Apache Software License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `commons-codec:commons-codec:1.16.1` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `commons-codec:commons-codec:1.17.1` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `commons-io:commons-io:2.11.0` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `commons-io:commons-io:2.16.1` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `io.micrometer:micrometer-commons:1.15.12` | The Apache Software License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `io.micrometer:micrometer-observation:1.15.12` | The Apache Software License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `javax.inject:javax.inject:1` | The Apache Software License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `net.java.dev.jna:jna-platform:5.17.0` | LGPL-2.1-or-later (https://www.gnu.org/licenses/old-licenses/lgpl-2.1); Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | **Needs owner decision** — row 13 (⚠️ round 5: not "row 6/13" — row 6 is the different coordinate `jna:5.13.0`) |
| `net.java.dev.jna:jna:5.17.0` | LGPL-2.1-or-later (https://www.gnu.org/licenses/old-licenses/lgpl-2.1); Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | **Needs owner decision** — row 13 (⚠️ round 5: not "row 6/13" — row 6 is the different coordinate `jna:5.13.0`) |
| `org.antlr:antlr4-runtime:4.7.2` | The BSD License (http://www.antlr.org/license.html) | **Needs owner decision** — row 15 |
| `org.apache.commons:commons-compress:1.26.1` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Apache-2.0 for its own code (mapping only) — ⚠️ **round 8: embedded `sevenz` package, row 29** |
| `org.apache.commons:commons-compress:1.27.1` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Apache-2.0 for its own code (mapping only) — ⚠️ **round 8: embedded `sevenz` package, row 29** |
| `org.apache.commons:commons-lang3:3.12.0` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.commons:commons-lang3:3.14.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.commons:commons-lang3:3.16.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.httpcomponents.client5:httpclient5:5.5.2` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Apache-2.0 for its own code (mapping only) — ⚠️ **round 8: ships the Mozilla Public Suffix List under MPL-2.0 (`effective_tld_names.dat`), row 37** |
| `org.apache.httpcomponents.core5:httpcore5-h2:5.3.6` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.httpcomponents.core5:httpcore5:5.3.6` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.plugins:maven-clean-plugin:3.4.1` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.plugins:maven-compiler-plugin:3.14.1` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.plugins:maven-deploy-plugin:3.1.4` | Apache-2.0 (via effective POM, org.apache.maven.plugins:maven-plugins:43 -> org.apache.maven:maven-parent -> org.apache:apache) | Allowed (mapping only) |
| `org.apache.maven.plugins:maven-failsafe-plugin:3.5.6` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.plugins:maven-install-plugin:3.1.4` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.plugins:maven-jar-plugin:3.4.2` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.plugins:maven-resources-plugin:3.3.1` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.plugins:maven-shade-plugin:3.6.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.plugins:maven-site-plugin:3.12.1` | Apache-2.0 (via effective POM, org.apache.maven.plugins:maven-plugins:36 -> org.apache.maven:maven-parent -> org.apache:apache) | Allowed (mapping only) |
| `org.apache.maven.plugins:maven-surefire-plugin:3.5.6` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.resolver:maven-resolver-api:1.4.1` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.resolver:maven-resolver-api:1.9.22` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.resolver:maven-resolver-util:1.4.1` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.resolver:maven-resolver-util:1.9.22` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.shared:file-management:3.1.0` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.shared:maven-common-artifact-filters:3.4.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.shared:maven-filtering:3.3.1` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.shared:maven-shared-incremental:1.1` | The Apache Software License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.shared:maven-shared-utils:3.4.2` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.surefire:maven-surefire-common:3.5.6` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.surefire:surefire-api:3.5.6` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.surefire:surefire-booter:3.5.6` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.surefire:surefire-extensions-api:3.5.6` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.surefire:surefire-extensions-spi:3.5.6` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.surefire:surefire-logger-api:3.5.6` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.apache.maven.surefire:surefire-shared-utils:3.5.6` | The Apache Software License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Apache-2.0 for its own code (mapping only) — ⚠️ **round 8: shades five libraries (Appendix F.3) including `commons-compress:1.28.0`'s `sevenz` package, row 29** |
| `org.apache.maven:maven-archiver:3.6.2` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.codehaus.plexus:plexus-archiver:4.9.2` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.codehaus.plexus:plexus-compiler-api:2.15.0` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.codehaus.plexus:plexus-compiler-javac:2.15.0` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.codehaus.plexus:plexus-compiler-manager:2.15.0` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.codehaus.plexus:plexus-interpolation:1.26` | Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.codehaus.plexus:plexus-interpolation:1.27` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.codehaus.plexus:plexus-io:3.4.2` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.codehaus.plexus:plexus-java:1.5.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.codehaus.plexus:plexus-java:1.5.2` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.codehaus.plexus:plexus-utils:3.5.1` | Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | **Needs owner decision — row 32** (⚠️ round 7: the POM string is Apache-2.0, but the jar also carries the Indiana University Extreme! Lab licence text; see the round 7 disposition item 2) |
| `org.codehaus.plexus:plexus-utils:4.0.1` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | **Needs owner decision — row 32** (⚠️ round 7: the POM string is Apache-2.0, but the jar also carries the Indiana University Extreme! Lab licence text; see the round 7 disposition item 2) |
| `org.codehaus.plexus:plexus-utils:4.0.2` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | **Needs owner decision — row 32** (⚠️ round 7: the POM string is Apache-2.0, but the jar also carries the Indiana University Extreme! Lab licence text; see the round 7 disposition item 2) |
| `org.codehaus.plexus:plexus-xml:3.0.1` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | **Needs owner decision — row 32** (⚠️ round 7: the POM string is Apache-2.0, but the jar also carries the Indiana University Extreme! Lab licence text; see the round 7 disposition item 2) |
| `org.iq80.snappy:snappy:0.4` | Apache License 2.0 (http://www.apache.org/licenses/LICENSE-2.0.html) | Allowed (mapping only) |
| `org.jdom:jdom2:2.0.6.1` | Similar to Apache License but with the acknowledgment clause removed (https://raw.github.com/hunterhacker/jdom/master/LICENSE.txt) | **Needs owner decision** — row 14 |
| `org.ow2.asm:asm-commons:9.7` | BSD-3-Clause (https://asm.ow2.io/license.html) | Allowed (exact match) |
| `org.ow2.asm:asm-tree:9.7` | BSD-3-Clause (https://asm.ow2.io/license.html) | Allowed (exact match) |
| `org.ow2.asm:asm:9.7` | BSD-3-Clause (https://asm.ow2.io/license.html) | Allowed (exact match) |
| `org.ow2.asm:asm:9.8` | BSD-3-Clause (https://asm.ow2.io/license.html) | Allowed (exact match) |
| `org.ow2.asm:asm:9.9.1` | BSD-3-Clause (https://asm.ow2.io/license.html) | Allowed (exact match) |
| `org.slf4j:slf4j-api:1.7.36` | MIT License (http://www.opensource.org/licenses/mit-license.php) | Allowed (mapping only) |
| `org.sonatype.plexus:plexus-build-api:0.0.7` | Apache Public License 2.0 (http://www.apache.org/licenses/LICENSE-2.0) | Allowed in substance — variant string, see Appendix B (⚠️ round 5: was self-approved as "not a new owner-decision item" in round 4; corrected in the M17(i) disposition above) |
| `org.springframework.boot:spring-boot-buildpack-platform:3.5.16` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0) | Allowed (mapping only) |
| `org.springframework.boot:spring-boot-loader-tools:3.5.16` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0) | Allowed (mapping only) |
| `org.springframework.boot:spring-boot-maven-plugin:3.5.16` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0) | Allowed (mapping only) |
| `org.springframework:spring-aop:6.2.19` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0) | Allowed (mapping only) |
| `org.springframework:spring-beans:6.2.19` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0) | Allowed (mapping only) |
| `org.springframework:spring-context:6.2.19` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0) | Allowed (mapping only) |
| `org.springframework:spring-core:6.2.19` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0) | Allowed (mapping only) |
| `org.springframework:spring-expression:6.2.19` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0) | Allowed (mapping only) |
| `org.springframework:spring-jcl:6.2.19` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0) | Allowed (mapping only) |
| `org.tomlj:tomlj:1.0.0` | The Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |
| `org.tukaani:xz:1.9` | Public Domain | **Needs owner decision** — row 12 |
| `org.vafer:jdependency:2.10` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only) |

Total: 86 distinct coordinates (96 raw resolved lines — 10 are the same plugin GAV listed once as its `maven-plugin` descriptor and once as the `jar` dependency of its own class realm).

**This closes MAJOR finding #1 of the round-4 review** by resolved-GAV coverage. ⚠️ **Round 5 correction:** "the
four already-known off-list items" undercounts by one — `com.github.luben:zstd-jni:1.5.5-11` is also a
plugin-realm coordinate (pulled by `maven-jar-plugin` → `plexus-archiver`/`commons-compress`, see its row above),
not only a third-party-table entry. The correct count is **five**: `net.java.dev.jna:jna`/`jna-platform:5.17.0`,
`org.antlr:antlr4-runtime:4.7.2`, `org.jdom:jdom2:2.0.6.1`, `org.tukaani:xz:1.9`, and `zstd-jni:1.5.5-11` — see
the consolidated table above (rows 13–14, 12, 15, and 16). No
artifact in this appendix that was not already in that table needed one.

---

## Appendix D — complete walk of the candidate plugin's own class realm (round 4)

Every artifact loaded into `org.codehaus.mojo:license-maven-plugin:2.7.1`'s own class realm when running
the goal this audit and T0.4b both bind — measured with `mvn -o -N -f backend/pom.xml -X
org.codehaus.mojo:license-maven-plugin:2.7.1:aggregate-add-third-party -Dlicense.excludedScopes=system
-Dlicense.outputDirectory=<scratchpad> -Dlicense.sortArtifactByName=true`, then `grep "Included:"` on the
`-X` log (72 lines, matching round 3's count from the `:help` goal — the same realm, now measured directly
against the goal actually used). This is the M17(i) "poi/jena/xmlbeans/doxia/velocity/reporting" toolchain,
now individually walked in full rather than sampled. All build-tool-only — none of this is shipped in the SM
backend; it exists only while the report/gate mojo runs.

⚠️ **Round 5 note on the "Status" column:** same caveat as Appendix C — "Allowed (mapping only, if spelling
differs...)" is this audit's own substance read, not an owner sign-off; see **Appendix B** for the variant
inventory the owner actually confirms.

| Coordinate | Declared licence (verbatim) | Status |
|---|---|---|
| `com.github.andrewoma.dexx:collection:0.7` | MIT License (http://www.opensource.org/licenses/mit-license.php) | Allowed (mapping only, if spelling differs from the canonical string) |
| `com.github.ben-manes.caffeine:caffeine:3.1.8` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `com.github.cliftonlabs:json-simple:3.0.2` | The Apache Software License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `com.github.luben:zstd-jni:1.5.7-6` | BSD 2-Clause License (https://opensource.org/licenses/BSD-2-Clause) | Already in the owner decision table above — row 16 (⚠️ round 5: previously marked "Allowed" here on the identical string that row 16 already sends `1.5.5-11` to the owner for; one decision covers both versions) |
| `com.github.virtuald:curvesapi:1.08` | BSD License (http://opensource.org/licenses/BSD-3-Clause) | Already in the owner decision table above (see M17(i) / row list) |
| `com.google.errorprone:error_prone_annotations:2.21.1` | Apache 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `com.zaxxer:SparseBitSet:1.3` | The Apache Software License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `commons-beanutils:commons-beanutils:1.9.4` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `commons-cli:commons-cli:1.5.0` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `commons-codec:commons-codec:1.11` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `commons-collections:commons-collections:3.2.2` | Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `commons-digester:commons-digester:2.1` | The Apache Software License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `commons-io:commons-io:2.21.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `commons-logging:commons-logging:1.1.1` | The Apache Software License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `commons-validator:commons-validator:1.7` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `io.airlift:aircompressor:0.27` | Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0.html) | Allowed (mapping only, if spelling differs from the canonical string) |
| `net.rootdev:java-rdfa:1.0.0-BETA1` | BSD (https://github.com/iteggmbh/java-rdfa/blob/master/COPYING) | Already in the owner decision table above (see M17(i) / row list) |
| `org.apache.commons:commons-collections4:4.5.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.commons:commons-compress:1.28.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Apache-2.0 for its own code (mapping only) — ⚠️ **round 8: embedded `sevenz` package, row 29** |
| `org.apache.commons:commons-csv:1.10.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.commons:commons-digester3:3.2` | The Apache Software License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.commons:commons-lang3:3.20.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.commons:commons-math3:3.6.1` | Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Apache-2.0 for its own code (mapping only) — ⚠️ **round 8: appended Minpack (acknowledgment clause) and no-licence-stated `BesselJ` sections in `META-INF/LICENSE.txt`, row 38** |
| `org.apache.commons:commons-text:1.12.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.httpcomponents:httpclient:4.5.14` | Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Apache-2.0 for its own code (mapping only) — ⚠️ **round 8: ships the Mozilla Public Suffix List under MPL-2.0 (`mozilla/public-suffix-list.txt`), row 37** |
| `org.apache.httpcomponents:httpcore:4.4.16` | Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.jena:jena-base:4.10.0` | The Apache Software License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.jena:jena-core:4.10.0` | The Apache Software License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.jena:jena-iri:4.10.0` | The Apache Software License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.logging.log4j:log4j-api:2.25.3` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.logging.log4j:log4j-to-slf4j:2.25.3` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.maven.doxia:doxia-core:2.0.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Apache-2.0 for its own code (mapping only) — ⚠️ **round 8: embeds W3C `xml.xsd`, row 30** |
| `org.apache.maven.doxia:doxia-integration-tools:2.0.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.maven.doxia:doxia-module-apt:2.0.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.maven.doxia:doxia-module-xdoc:2.0.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.maven.doxia:doxia-module-xhtml5:2.0.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.maven.doxia:doxia-sink-api:2.0.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.maven.doxia:doxia-site-model:2.0.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.maven.doxia:doxia-site-renderer:2.0.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.maven.doxia:doxia-skin-model:2.0.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.maven.reporting:maven-reporting-api:4.0.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.maven.reporting:maven-reporting-impl:4.0.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.maven.shared:maven-shared-utils:3.4.2` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.maven:maven-archiver:3.6.6` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.poi:poi-ooxml-lite:5.5.1` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Apache-2.0 for its own code (mapping only) — ⚠️ **round 8: compiled/derived W3C, ETSI, ECMA schema content, row 40** |
| `org.apache.poi:poi-ooxml:5.5.1` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Apache-2.0 for its own code (mapping only) — ⚠️ **round 8: eID-Applet-derived signer package (upstream LGPL-3.0), row 40** |
| `org.apache.poi:poi:5.5.1` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.velocity.tools:velocity-tools-generic:3.1` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.velocity:velocity-engine-core:2.4` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.apache.xmlbeans:xmlbeans:5.3.0` | The Apache Software License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.checkerframework:checker-qual:3.37.0` | The MIT License (http://opensource.org/licenses/MIT) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.codehaus.mojo:license-maven-plugin:2.7.1` | The GNU Lesser General Public License, Version 3.0 (https://www.gnu.org/licenses/lgpl-3.0.txt) | Already in the owner decision table above (see M17(i) / row list) |
| `org.codehaus.plexus:plexus-archiver:4.10.4` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.codehaus.plexus:plexus-component-annotations:2.1.0` | Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.codehaus.plexus:plexus-i18n:1.0-beta-10` | NONE_FOUND | Already in the owner decision table above (see M17(i) / row list) |
| `org.codehaus.plexus:plexus-interpolation:1.27` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.codehaus.plexus:plexus-io:3.6.0` | Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.codehaus.plexus:plexus-utils:4.0.2` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | **Needs owner decision — row 32** (⚠️ round 7: the POM string is Apache-2.0, but the jar also carries the Indiana University Extreme! Lab licence text; see the round 7 disposition item 2) |
| `org.codehaus.plexus:plexus-velocity:2.2.0` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.codehaus.plexus:plexus-xml:3.0.1` | Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt) | **Needs owner decision — row 32** (⚠️ round 7: the POM string is Apache-2.0, but the jar also carries the Indiana University Extreme! Lab licence text; see the round 7 disposition item 2) |
| `org.eclipse.sisu:org.eclipse.sisu.inject:0.9.0.M3` | Eclipse Public License, Version 2.0 (https://www.eclipse.org/legal/epl-v20.html) | Already in the owner decision table above (see M17(i) / row list) |
| `org.freemarker:freemarker:2.3.34` | Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.json:json:20231013` | Public Domain (https://github.com/stleary/JSON-java/blob/master/LICENSE) | Already in the owner decision table above (see M17(i) / row list) |
| `org.nuiton.processor:nuiton-processor:1.3` | Lesser General Public License (LGPL) v 3.0 (http://www.gnu.org/licenses/lgpl-3.0.txt) | Already in the owner decision table above (see M17(i) / row list) |
| `org.odftoolkit:odfdom-java:0.12.0` | Apache 2 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.osgi:org.osgi.core:6.0.0` | Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.roaringbitmap:RoaringBitmap:1.0.0` | Apache 2 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.slf4j:slf4j-simple:1.7.36` | MIT License (http://www.opensource.org/licenses/mit-license.php) | Allowed (mapping only, if spelling differs from the canonical string) |
| `org.tukaani:xz:1.10` | 0BSD | Already in the owner decision table above (see M17(i) / row list) |
| `xalan:serializer:2.7.3` | NONE_FOUND | Already in the owner decision table above (see M17(i) / row list) |
| `xerces:xercesImpl:2.12.2` | The Apache Software License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt) | Allowed (mapping only, if spelling differs from the canonical string) |
| `xml-apis:xml-apis:1.4.01` | The Apache Software License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0.txt); The SAX License (http://www.saxproject.org/copying.html); The W3C License (http://www.w3.org/TR/2004/REC-DOM-Level-3-Core-20040407/java-binding.zip) | Already in the owner decision table above (see M17(i) / row list) |

Total: 72 (10 already individually verified earlier in this document; 62 new this round — zero new owner-decision
items among them). ⚠️ **Round 6 correction:** "all confirmed Allowed" overstated this table's own authority — the
"Allowed"/"Allowed (mapping only...)" cells are this audit's own substance read, not an owner confirmation; the
round-5 note above the table already says so, this footer just hadn't caught up. Reworded to "zero new
owner-decision items"; the non-canonical spellings among the 62 still need Appendix B's sign-off.

**This closes MINOR finding #2 of the round-4 review and the M17(i) residual gap from round 3.** Zero new
owner-decision artifacts among the 62 newly-walked rows; the 10 that do need a decision were already known
and are rows 11, 17–25 of the consolidated owner decision table above. ⚠️ **Round 5:** this closes coverage by
*resolved-GAV licence name* — it does not see content embedded inside a resolved jar's own bytes, which is a
structurally different question. `xmlbeans:5.3.0` above is "Allowed (mapping only)" by its own declared licence,
and separately carries embedded W3C-licensed schema files that resolved-GAV scanning cannot see at all — see
**Appendix E** and owner-table row 30.

---

## Appendix E — embedded and shaded licence content beyond the plugin's own scan (round 5)

**Why this appendix exists.** Every appendix above reads licences from `<licenses>` elements — the plugin's own
method, walking POMs and parent chains. That method cannot see a licence file embedded *inside* a jar's own
bytes for content the jar bundles, shades, or repackages under a different licence than the one its own POM
declares. This round opened the already-built artifacts read-only (`unzip -l`/`unzip -p`, no build triggered) to
check. Findings that create a new owner-table row are cross-referenced there, not repeated in full here; this
appendix also records findings that do **not** create a new row, because they resolve to a licence already on
record, so T0.4b's future re-runs of this method know not to re-flag them as new.

### E.1 — Shipped, new owner-decision rows (26–27)

- **`jackson-core:2.21.4`** (shipped, compile, `arch-tests`/`console-gateway`/`cp-core`/`dp-core`/`launcher`/
  `demo-launcher`/`mesh-events`). `META-INF/FastDoubleParser-LICENSE` is the bundled Java port's own licence
  (MIT, Werner Randelshofer). `META-INF/FastDoubleParser-ThirdParty-LICENSE`, read in full, has three sections
  (⚠️ round 7 correction — rounds 5–6 quoted it as one): (1) the port of Daniel Lemire's `fast_float`, `Copyright (c)
  Daniel Lemire`, under the **Boost Software License - Version 1.0**; (2) "Third-party license for fast_float,
  Copyright (c) 2021 The fast_float authors. MIT License."; (3) "Third-party license for bigint, Copyright 2020 Tim
  Buktu. 2-clause BSD License." Sections (2) and (3) are on-list in substance — recorded as facts. Row 26 rests on
  section (1). See the round 5 disposition above for why this is not automatically `REQ-014`'s "Refused: BSL/BUSL" —
  that line names the unrelated Business Source License family.
- **`tomcat-embed-core:10.1.55`** (shipped, compile, `launcher`/`demo-launcher`; provided, `console-gateway`).
  `META-INF/LICENSE`'s own "APACHE TOMCAT SUBCOMPONENTS" section, read in full, puts 21 named Java-EE
  deployment-descriptor files under **CDDL-1.0** and 14 named Jakarta-EE files under **EPL-2.0** — 35 named in
  total, not 51. ⚠️ **Round 6 correction, per `I2` — verify, don't restate:** counted directly in the jar,
  `jakarta/servlet/resources/` holds **45 `.xsd` + 6 `.dtd` = 51 files** (not "51 `.xsd` files"); only the 35
  named above are separately licensed (two of the 14 EPL names carry a typo in Tomcat's own upstream file —
  `web-commonn_5_0.xsd`/`_6_0.xsd`, double "n" — against the shipped `web-common_5_0.xsd`/`_6_0.xsd`). The
  remaining 16 files (e.g. `datatypes.dtd`, `XMLSchema.dtd`, `xml.xsd`, and 13 older J2EE-era schemas/DTDs, all
  read directly) each carry the standard ASF "Licensed to the Apache Software Foundation... Apache License,
  Version 2.0" header — Tomcat's own content, not part of the subcomponent set — plus one further `.dtd`
  elsewhere in the jar (`org/apache/tomcat/util/modeler/mbeans-descriptors.dtd`), also Apache-2.0. Row 27.

### E.2 — Shipped, on-list in substance, no new row (facts recorded for completeness)

- **`postgresql:42.7.11`** shades `com.ongres:scram-client`/`scram-common:3.2` and `saslprep`/`stringprep:2.2`
  under `org/postgresql/shaded/`. Each carries its own `META-INF/licenses/<coordinate>/META-INF/LICENSE`; read in
  full — 2-clause text, no endorsement clause — **BSD-2-Clause**, already allow-listed.
- **`docker-java-transport-zerodep:3.4.2`** shades `org.apache.hc`/`commons-codec` under
  `com/github/dockerjava/zerodep/shaded/`. ⚠️ **Round 6 correction, per `I2` — verify, don't restate:** "No
  embedded LICENSE for the shaded content in this jar" is false — measured directly, the jar carries
  `META-INF/LICENSE` + `META-INF/NOTICE` (Apache HttpClient) and `META-INF/LICENSE.txt` + `META-INF/NOTICE.txt`
  (Apache Commons Codec), plus `META-INF/DEPENDENCIES` — five files, self-disclosing exactly what is shaded. The
  conclusion is unchanged: `org.apache.hc` (client5/core5) and `commons-codec` are both **Apache-2.0**, already
  on record for the unshaded coordinates elsewhere in this report — the shaded content is these two specifically,
  not "`org.apache.commons` in general" as this bullet previously said.
- **`sol-jcsmp:10.30.1`** ships `org/apache/commons/lang/exception/{Nestable,NestableException,
  NestableDelegate,NestableError,NestableRuntimeException,ExceptionUtilsTruncated}.class` — third-party Apache
  Commons Lang code bundled inside the first-party JCSMP artifact. Commons Lang is **Apache-2.0**, already
  allow-listed; recorded because a future re-run of this method should recognise it as already-seen, not new.

### E.3 — Test scope, new owner-decision rows (28–29)

- **`testcontainers:1.21.4`** shades a complete, mechanically-enumerated set of packages under
  `org/testcontainers/shaded/` (listed by walking every top-level package directory and counting `.class` files
  in each — ⚠️ **round 6 correction, per `I2` — verify, don't restate:** neither the "about 4,000" estimate nor
  the "6,999" figure a later review supplied reproduces; measured directly, `org.bouncycastle` has **5,621
  `.class` files (5,901 entries with directories) at the jar's base**, plus **1,098** further versioned copies
  under `META-INF/versions/{9,11,15}/.../bouncycastle/` — **6,719 `.class` files / 6,999 entries in total**):
  `com.fasterxml` (Jackson, 677 classes), `com.github.dockerjava` (docker-java-core, 284), `com.google.common`
  (Guava, 2016), `com.google.errorprone`/`com.google.thirdparty` (27 + 3), `com.trilead` (141, the GlassFish
  repackage discussed below), `org.apache.commons.{io,lang3}` (674), `org.awaitility` (76), `org.bouncycastle`
  (5,621, per above), `org.checkerframework` (369), `org.hamcrest` (108), `org.yaml` (SnakeYAML, 225), and
  `org.zeroturnaround` (zt-exec, 52). The jar's own top level carries no `META-INF/LICENSE`/`NOTICE` for any of
  it — only `MANIFEST.MF` and three `META-INF/services/` entries. WebFetch of the project's `core/build.gradle`
  at tag `1.21.4` shows why: its `shadowJar` block explicitly excludes
  `META-INF/{NOTICE,NOTICE.txt,LICENSE,LICENSE.txt}` from shaded dependencies before repackaging — a deliberate
  omission upstream, not a gap in this audit's tooling. Of the shaded packages, BouncyCastle/Guava/Jackson/
  errorprone/thirdparty/checkerframework/Hamcrest/SnakeYAML/Commons(io+lang3)/awaitility/docker-java-core/zt-exec
  all resolve to licences already allow-listed or already an owner row in substance — ⚠️ **round 7: with two exceptions
  this sentence used to hide, owner-table row 33 (the shaded Guava's eight Public Domain `Striped64`/`LongAdder`
  classes) and row 34 (the shaded SnakeYAML 1.33's multi-licensed `Base64Coder`)** — (BouncyCastle is owner-table
  row 3; zt-exec's own POM, WebFetched, declares "The Apache Software License, Version 2.0") — no new row for
  those. The same `build.gradle` names the shaded ssh dependency as
  `org.glassfish.main.external:trilead-ssh2-repackaged:4.1.2` — **not** upstream `com.trilead:trilead-ssh2`, but a
  GlassFish repackage. ⚠️ **Round 6 correction to the evidence, per `I2` — the audit's own stated
  POM-and-parent-chain method, applied one level short:** its own POM has no `<licenses>` block and its `<parent>`
  is `org.glassfish.main.external:nucleus-external:4.1.2`, which also has none; that POM's own `<parent>`,
  `org.glassfish.main:glassfish-nucleus-parent:4.1.2`, **does** declare
  `<license><name>CDDL + GPLv2 with classpath exception</name><url>http://glassfish.java.net/nonav/public/CDDL+GPL.html</url>...</license>`
  verbatim (WebFetched) — the actual declared-licence evidence, not the artifact's own POM header comment (which
  licenses the POM document, not the shaded code, even though it states the same substance). Read together with
  the header comment's "either... GPL2... or... CDDL" wording: **CDDL-1.0 OR GPL-2.0-only WITH
  Classpath-exception-2.0**, neither leg on the allow-list. **Row 28.**
- **`commons-compress:1.24.0`** — `META-INF/NOTICE.txt`, read in full: "The files in the package
  `org.apache.commons.compress.archivers.sevenz` ... were derived from the LZMA SDK, version 9.20 ... which has
  been placed in the public domain." **Public Domain**, the same case already open for `org.tukaani:xz` (rows
  12/22). **Row 29.** ~~⚠️ **Round 6 addition:** this does not generalise to the other `commons-compress` versions
  this reactor resolves. The three build-tool-only versions in Appendix D (`1.26.1`, `1.27.1`, `1.28.0`, all
  pulled into the candidate plugin's own class realm) each carry a plain ASF `NOTICE.txt` ("Apache Commons
  Compress / Copyright ... The Apache Software Foundation") with **no** LZMA-SDK public-domain text — read
  directly on all three. No row is needed for those; they stay "Allowed" in Appendix D as measured.~~
  ⚠️ **Round 8: struck.** The plain-ASF `NOTICE.txt` in those three is true and beside the point: upstream commit
  `5ecccd9838f0` (2023-11-12) removed the LZMA-SDK paragraph from `NOTICE.txt` without touching any source file,
  and the `sevenz` package remains in `1.26.1` (via `maven-jar-plugin`, Appendix C), `1.27.1` (via
  `spring-boot-maven-plugin`, Appendix C), `1.28.0` (Appendix D) and in the copy that `surefire-shared-utils:3.5.6`
  shades under `org/apache/maven/surefire/shared/compress/` — `LZMADecoder.class` 4,083 bytes and
  `LZMA2Decoder.class` 3,775 bytes in all four `commons-compress` versions. Row 29 lists all six coordinates.

### E.4 — Test scope, no new row (NOTICE content confirmed not actually shipped)

- **`jakarta.activation-api:2.1.4`** / **`jakarta.xml.bind-api:4.0.5`** (owner-table rows 7–8, unchanged).
  `jakarta.activation-api`'s `META-INF/NOTICE.md`, read in full, states the project's own licence as
  `SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause OR GPL-2.0-only with Classpath-exception-2.0` — stronger
  evidence for rows 7/8's existing EDL-1.0→BSD-3-Clause mapping (an OR-licence lets the owner pick the
  BSD-3-Clause leg outright) than the Eclipse Foundation web page cited previously, not a new problem. The same
  NOTICE also lists third-party content (Apache Ant, Apache Commons Lang, font-awesome, jsoup, JTHarness,
  normalize.css, SigTest) under EPL/W3C/public-domain/MIT terms. **Checked and not carried forward as a new
  row:** every non-`.class` entry in the jar was read (`unzip -l`) — only `MANIFEST.MF`, two `mailcap`/
  `mimetypes` default-config resources, and the licence/POM metadata itself are present. None of the named
  third-party content is embedded in this shipped artifact; the NOTICE describes the upstream `jaf-api` source
  repository's own website/TCK tooling, which this published API jar does not carry.

### E.5 — Build-tool-only, new owner-decision row (30), and one non-row for comparison

- **`xmlbeans:5.3.0`** — `META-INF/NOTICE.txt` states "This product contains W3C XML Schema documents." Measured
  directly: six `.xsd` files are embedded — `org/apache/xmlbeans/metadata/src/{XML,XMLSchema,ltgfmt,substwsdl,
  xmlconfig,xsdownload}.xsd`. These are the W3C-copyrighted schema documents themselves, not merely mentioned.
  **W3C License**, not on the allow-list. **Row 30.**
- **`poi:5.5.1`, `poi-ooxml:5.5.1`, `poi-ooxml-lite:5.5.1`** — each carries the same NOTICE sentence about W3C
  XML Schema documents. Measured directly: all three jars embed **zero** `.xsd`/`.dtd` files. ~~**No row added**
  for these three — unlike `xmlbeans`, their own NOTICE over-describes their own jar's contents (the W3C content
  it refers to lives elsewhere in the wider Apache POI project, not in these three published artifacts).~~
  ⚠️ **Round 8: this held the POI jars to a different standard from `xmlbeans` (row 30) and was wrong about the
  substance.** "Zero `.xsd`/`.dtd` files" is true and only shows the schemas are not present *as files*.
  `poi-ooxml-lite:5.5.1` carries them compiled: 1,601 `.xsb` compiled-schema binaries and XMLBeans-generated
  classes for W3C XML-Signature (24), ETSI XAdES (79), ECMA-376 (2,247) and Microsoft (199) — and its own NOTICE
  says "This product contains W3C XML Schema documents". Its LICENSE licenses the ECMA schemas only by the ECMA
  bylaws' "available to all interested parties without restriction", which is not a named licence. **Row 40.**
  `doxia-core:2.0.0` embeds W3C's `xml.xsd` as a file — added to row 30.

**Scope note, unchanged from earlier rounds:** every artifact in this appendix is either build-tool-only or
test-scope except `jackson-core` and `tomcat-embed-core` (E.1) and the three on-list shaded facts (E.2), which
are shipped. This audit approves nothing here either — rows 26, 27, 28, 29 and 30 are additions to the same
owner decision table above, not a separate approval track. Rows 32–36 (round 7) are recorded in the round 7
disposition and **Appendix F**; rows 37–40 (round 8) in the round 8 disposition and **F.8**. Row 31 (Netty's Public Domain content, shipped) is
recorded in **Appendix F**, which documents the round-6 complete sweep this appendix's round-5 method sampled
rather than exhausted.

---

## Appendix F — embedded/shaded-content sweep, all 258 coordinates (round 6; extended and corrected in round 7, F.2–F.7; round 8, F.8)

**Why this appendix exists.** Round 5 opened jars it already had a reason to suspect (named in a review finding,
or already flagged for another reason). That is sampling, not sweeping. This round mechanically parsed every
coordinate out of Appendix A.1 (the 116-artifact third-party report), Appendix C (86 plugin-realm coordinates)
and Appendix D (72 class-realm coordinates) — 258 distinct `group:artifact:version` triples after de-duplication
— and checked each one systematically, rather than sampling.

**Method.**
1. Each table's coordinate column was extracted by script (regex over the committed Markdown, not re-typed by
   hand) into three lists (116 / 86 / 72), then merged and de-duplicated to 258.
2. Every coordinate's jar was located under `~/.m2/repository/<groupId-as-path>/<artifactId>/<version>/` — **all
   258 found**; zero missing, zero required a guess. ⚠️ **Round 7 bookkeeping:** one of the 258,
   `io.netty:netty-transport-native-epoll:4.2.13.Final`, exists only as the `-linux-x86_64` classifier jar (there is no
   plain `<artifact>-<version>.jar`), so "found by plain name" holds for 257.
   ⚠️ **Round 8 limit, stated:** the 258 come from Appendices A.1, C and D only, so the sweep set omitted the three
   Surefire provider-classpath jars (`surefire-junit-platform:3.5.6`, `common-java5:3.5.6`,
   `junit-platform-launcher:1.12.2`, which `dependency:resolve-plugins` never lists) and never looked *inside* nested
   jars (`spring-boot-loader-tools:3.5.16` carries `spring-boot-loader.jar`, `spring-boot-loader-classic.jar` and
   `spring-boot-jarmode-tools.jar`). F.8 sweeps both; nothing off-list was found in them.
3. **Pass 1 — embedded licence files.** `unzip -l <jar> | grep -iE "LICENSE|NOTICE|COPYING"` on each of the 258
   jars, counting matches. Distribution: **85 jars with 0 matches, 24 with 1, 131 with 2 (almost always a plain
   `LICENSE`+`NOTICE` pair — the normal ASF/Eclipse disclosure shape, not investigated further unless the
   `NOTICE` itself names embedded third-party content), and 17 jars with 3 or more** (`license-maven-plugin`
   itself: 214, from its own shaded runtime toolchain, not investigated further since it is build-tool-only and
   already row 11; the rest are enumerated in the round 6 disposition and this appendix). ⚠️ **Round 7 correction:**
   re-measured, the distribution is **85 / 24 / 131 / 18** (sums to 258) — 18, not 17, have three or more; the
   "enumerated in the round 6 disposition and this appendix" was never true; the 18 are now tabulated in **F.2**; and
   the 214 in `license-maven-plugin` are not shading — 151 are its own `org/codehaus/mojo/license/...` package names,
   which match the pattern because they contain "license", and the rest are a bundled corpus of licence *templates*
   under `META-INF/licenses/<id>/` (`agpl_v3`, `apache_v2`, `bsd_2`, `bsd_3`, `cddl_v1`, `epl_v1`, `epl_v2`, `gpl_v1`–`v3`,
   `lgpl_v2_1`, `lgpl_v3`, `mit`, `eupl_v1_1`, `fdl_v1_3`, `epl_only_v1`, `epl_only_v2`) — data it writes into file
   headers, not code it embeds.
4. **Pass 2 — shaded-path heuristic.** `unzip -l <jar> | grep -iE "/shaded?/|shadow"` on all 258 jars, to catch
   shading that carries no accompanying licence file at all (Pass 1 would miss it). 15 jars matched; each was
   opened and read to tell a genuine third-party shading path from a first-party class merely named
   "Shadow*"/"*Shade*".

**Pass-2 results, all 15 matches accounted for:**

| Coordinate | Matches | Genuine shading? | Disposition |
|---|---|---|---|
| `com.fasterxml.jackson.core:jackson-core:2.21.4` | 58 | Yes — `com/fasterxml/jackson/core/internal/shaded/fdp/` (FastDoubleParser) | Already row 26 (Boost SW Licence) plus the Schubfach-LICENSE fact, both recorded above |
| `com.github.docker-java:docker-java-transport-zerodep:3.4.2` | 1523 | Yes — `com/github/dockerjava/zerodep/shaded/` | Already recorded in E.2 (Apache-2.0, on-list, fact only; corrected this round — see round 6 disposition item 7) |
| `io.netty:netty-common:4.2.13.Final` | 138 | Yes — `io/netty/util/internal/shaded/org/jctools/` | **New this round** — JCTools, Apache-2.0 (WebFetched), on-list, fact only |
| `org.apache.maven.plugins:maven-shade-plugin:3.6.0` | 68 | No — `org/apache/maven/plugins/shade/*` is the plugin's own package name | False positive, dismissed |
| `org.apache.poi:poi-ooxml-lite:5.5.1` | 13 | No — `com/microsoft/schemas/vml/{CT,ST}Shadow*` are OOXML drop-shadow-effect classes | False positive, dismissed |
| `org.apache.poi:poi-ooxml:5.5.1` | 1 | No — `org/apache/poi/xslf/usermodel/XSLFShadow.class`, same reason | False positive, dismissed |
| `org.apache.poi:poi:5.5.1` | 1 | No — `org/apache/poi/sl/usermodel/Shadow.class`, same reason | False positive, dismissed |
| `org.junit.jupiter:junit-jupiter-params:5.12.2` | 293 | Yes — `META-INF/LICENSE-univocity-parsers.md` + shaded classes | **New this round** — univocity-parsers, Apache-2.0, on-list, fact only |
| `org.odftoolkit:odfdom-java:0.12.0` | 13 | No — `Dr3dShadowAttribute`/`DrawShadowAttribute` are ODF drawing-shadow-attribute classes | False positive, dismissed |
| `org.postgresql:postgresql:42.7.11` | 65 | Yes — `org/postgresql/shaded/` (scram/saslprep) | Already recorded in E.2 |
| `org.springframework:spring-aop:6.2.19` | 3 | No — `ShadowMatch*`/`ShadowMatchUtils` are Spring's own AspectJ-pointcut-matching classes | False positive, dismissed |
| `org.springframework:spring-context:6.2.19` | 3 | No — `ShadowingClassLoader`/`ResourceOverridingShadowingClassLoader` are Spring's own instrumentation classes | False positive, dismissed |
| `org.testcontainers:testcontainers:1.21.4` | 12005 | Yes — `org/testcontainers/shaded/` (12 packages) | Already row 28 (trilead) + facts (E.3); counts corrected this round |
| `org.vafer:jdependency:2.10` | 212 | Yes — `org/vafer/jdeb/shaded/commons/io/`, **self-disclosed** in its own `META-INF/NOTICE.txt` | **New this round** — Apache Commons IO, Apache-2.0, on-list, fact only, build-tool-only (plugin-realm, Appendix C, already "Allowed") |
| `xerces:xercesImpl:2.12.2` | 2 | No — `CachingParserPool$ShadowedGrammarPool`/`ShadowedSymbolTable` are Xerces' own classes | False positive, dismissed |

**Net result of the complete sweep:** one new owner-decision row (31, Netty's Public Domain `Base64`/
`ThreadLocalRandom`) and four new on-list facts not previously recorded (Netty's shaded JCTools;
`junit-jupiter-params`' shaded univocity-parsers; `jdependency`'s shaded Commons IO; `spring-core`'s
"SUBCOMPONENTS" NOTICE section, found in Pass 1, not Pass 2 — see below). Every other Pass-2 hit was either
already on record from round 5 (jackson-core, docker-java-transport-zerodep, postgresql, testcontainers) or a
first-party class whose name merely contains "Shadow"/"Shade" (six coordinates, dismissed above). **No
undisclosed off-allow-list shading was found beyond row 31.** ⚠️ **Struck in round 7** — rows 32–36 were found by
widening the method; the claim that survives is scoped in F.5.

**Pass-1 addenda found while investigating jars with 3+ licence-ish entries** (the 17-jar set from step 3 above,
minus the ones already covered by Appendix E or the Pass-2 table):

- **`org.springframework:spring-core:6.2.19`** — `META-INF/license.txt`'s "SPRING FRAMEWORK 6.2.19 SUBCOMPONENTS"
  section (read in full) names ASM 9.9.1 (BSD-3-Clause, confirmed shaded: 37 classes under
  `org/springframework/asm/`), CGLIB 3.3 (Apache-2.0, confirmed shaded: 229 classes under
  `org/springframework/cglib/`), JavaPoet 1.13.0 (Apache-2.0) and Objenesis 3.5 (Apache-2.0, confirmed shaded: 54
  classes). **JavaPoet's licence text is present but zero JavaPoet classes are shaded in this jar** (measured: 0
  matches for `squareup` in the jar's file listing) — the same "NOTICE over-describes the jar's own contents"
  pattern round 5 found for `jakarta.activation-api`'s NOTICE and `poi`/`poi-ooxml`'s. All four named
  subcomponents resolve to a licence already on the six-item allow-list — **no new row**, recorded as a fact.
- **`net.bytebuddy:byte-buddy:1.17.8`** — carries its own `META-INF/licenses/ASM` file (read in full: the
  standard ASM BSD-3-Clause text), disclosing the 102 classes shaded under `net/bytebuddy/jar/asm/`
  (`META-INF/versions/9/...`). Unlike Netty's undisclosed cases, this artifact discloses its own shading —
  **no new row**, recorded as a fact.

**Total owner-table rows after this appendix: 31 after round 6; 36 after round 7 (F.2–F.7 below); 40 after round 8 (F.8)** (30 carried forward from round 5, plus row 31). Total on-list
facts recorded across Appendix E and this appendix: `postgresql` (scram/saslprep), `docker-java-transport-zerodep`
(Apache HttpClient/Commons Codec), `sol-jcsmp` (Commons Lang exception classes), `jakarta.activation-api` (NOTICE
over-description, nothing actually embedded), Netty (JCTools), `spring-core` (ASM/CGLIB/Objenesis, JavaPoet
over-description), `byte-buddy` (ASM, self-disclosed), `junit-jupiter-params` (univocity-parsers), `jdependency`
(Commons IO, self-disclosed) — nine facts, none of them a new owner-decision row.


### F.2 — Round 7: Pass 1 completed (the 18 jars with three or more licence-named entries)

Same pattern (`LICENSE|NOTICE|COPYING`, case-insensitive) as Pass 1, applied to every entry name including directories
and class names — which is why several of these "hits" are class names, not licence files. Measured over the same
258 coordinates (Python `zipfile`): **85 jars with 0, 24 with 1, 131 with 2, 18 with 3 or more.**

| Coordinate | Entries | What they are | Disposition |
|---|---|---|---|
| `jackson-core:2.21.4` | 5 | `LICENSE`, `NOTICE`, `FastDoubleParser-LICENSE` (MIT port), `FastDoubleParser-ThirdParty-LICENSE` (Boost + MIT + BSD-2-Clause sections), `Schubfach-LICENSE` (MIT) | Row 26 + facts |
| `docker-java-transport-zerodep:3.4.2` | 4 | `LICENSE`, `NOTICE`, `LICENSE.txt`, `NOTICE.txt` (HttpClient, Commons Codec) | E.2 |
| `byte-buddy:1.17.8` | 6 | `LICENSE`, `NOTICE`, `licenses/ASM` (BSD-3-Clause, self-disclosed shaded ASM) + two class names containing "Copying" | Fact (round 6) |
| `maven-shade-plugin:3.6.0` | 4 | `LICENSE`, `NOTICE` + `ApacheLicenseResourceTransformer`, `ApacheNoticeResourceTransformer` class names | False positive; plugin's own Apache-2.0 |
| `poi-ooxml-lite:5.5.1` | 3 | `LICENSE`, `NOTICE` + one `.xsb` named `SPUserNotice` | False positive; NOTICE W3C claim already E.5 (no embedded `.xsd`) |
| `bcprov-jdk18on:1.80` | 3 | **No licence file** — three class names (`LICENSE.class`, `NoticeReference`, `UserNotice`) | False positive; row 3 |
| `bcprov-jdk18on:1.86` | 4 | `META-INF/LICENSE.md` + the same three class names | Row 3 |
| `license-maven-plugin:2.7.1` | 214 | Own package names + a licence-template corpus (see step 3 above) | Row 11; build-tool-only |
| `plexus-utils:3.5.1`, `4.0.1`, `4.0.2`; `plexus-xml:3.0.1` | 6 each | `LICENSE`, `NOTICE`, `licenses/` + `extreme.indiana.edu.license.TXT`, `javolution.license.TXT`, `thoughtworks.TXT` | **Row 32** (new); javolution (BSD-2) and thoughtworks (BSD-3) are on-list facts |
| `flyway-core:11.7.2` | 17 | `META-INF/LICENSE.txt` (Apache-2.0, "Copyright (C) Red Gate Software Ltd 2010-2024") + the `internal/license/` directory + 15 class names in `org/flywaydb/core/internal/license/` and `extensibility/` (Flyway's own edition/licence-key gating classes) | False positives; Apache-2.0 |
| `junit-jupiter-params:5.12.2` | 3 | `LICENSE.md` (EPL-2.0), `LICENSE-notice.md`, `LICENSE-univocity-parsers.md` (Apache-2.0) | Row 4; fact (round 6) |
| `odfdom-java:0.12.0` | 4 | `LICENSE`, `NOTICE` + two `TextNoteContinuationNotice*` class names | False positives; Apache-2.0 |
| `postgresql:42.7.11` | 16 | `LICENSE` (BSD-2-Clause) + `licenses/` for scram-client, scram-common, saslprep, stringprep | E.2 |
| `testcontainers:1.21.4` | 6 | **No licence file** — six class names (`LicenseAcceptance`, BouncyCastle's `LICENSE.class`, `UserNotice`, ...) | False positives — consistent with E.3 (upstream strips licence files); rows 28, 33, 34 |
| `xml-apis:xml-apis:1.4.01` | 8 | `license/` with `LICENSE`, `NOTICE`, `LICENSE.dom-documentation.txt`, `LICENSE.dom-software.txt`, `LICENSE.sax.txt`, `README.dom.txt`, `README.sax.txt` | Row 25 |

A mechanical read of every text file named `LICENSE*`/`NOTICE*`/`COPYING*`/`DEPENDENCIES`/`licenses/*` (≤ 400 KB) in
all 258 jars for off-list licence names added, beyond rows already recorded: `xercesImpl:2.12.2`'s `NOTICE` credits
"software copyright (c) 1999, Sun Microsystems" (Xerces's "originally based on" credit for ASF-donated code — fact);
`maven-*`/`doxia-*`/`odfdom-java` `DEPENDENCIES` files listing `Public Domain`, `EPL-2.0`, `BSD` and `MIT` for their
own **resolved dependencies** (`xz`, `json`, `sisu.inject`, `java-rdfa`, ... — rows 12/18/21/24, not embedded
content); the Spring family's `license.txt` boilerplate ("To the extent any open source components are licensed under
the EPL and/or ...", no such component named — ⚠️ **round 8: that reading was of the licence text only; `spring-web`'s
data file `mime.types` is Public Domain and undisclosed there — row 39**); and, in the ServiceMix `jzlib` wrapper's `DEPENDENCIES`, the one case
where the listed dependency **is** the embedded content — row 36.

### F.3 — Round 7: Pass 2 widened (relocation under other names)

The round-6 heuristic (`/shaded?/|shadow`) is replaced by a wider path-segment list — `thirdparty`, `third_party`,
`third-party`, `repackaged`, `relocated`, `vendor(ed)`, `embedded`, `bundled`, `deps`, `dependencies`, `external`,
`internal/shaded`, `shaded`, `shadow` — plus class-name probes for the specific public-domain and multi-licensed
files found so far (`Striped64`, `LongAdder`, `Base64Coder`, `MXParser`, `doubleparser`, `/fdp/`), and a
root-package-versus-coordinate mismatch check. New results beyond F's original 15:

| Coordinate | Hits | Reading | Disposition |
|---|---|---|---|
| `com.tngtech.archunit:archunit:1.5.0` | 2,009 under `com/tngtech/archunit/thirdparty/` | Guava (1,967 in `com/google/common` + 4 in `com/google/thirdparty`) and ASM (38 in `org/objectweb/asm`); one licence file, `asm.license`. **No** `Striped64`/`LongAdder` class (only `cache/LongAddables$JavaUtilConcurrentLongAdder`, a JDK-delegating class) | Fact; row 9 covers the ASM BSD label. Shaded Guava: Apache-2.0 |
| `org.testcontainers:testcontainers:1.21.4` | `Striped64`/`LongAdder` ×8, `Base64Coder` ×1 | Shaded Guava 33.3.1-jre and SnakeYAML 1.33 (Public Domain and multi-licensed content, respectively) | **Rows 33 and 34** |
| `org.yaml:snakeyaml:2.4` | 5 under `org/yaml/snakeyaml/external/` | Google Inc. 2008 gdata escapers, Apache-2.0 header (WebFetched at tag `snakeyaml-2.4`) | Fact; no row |
| `org.apache.maven.surefire:surefire-shared-utils:3.5.6` | 1,632 classes under `org/apache/maven/surefire/shared/{compress,io,lang3,codec,utils}/` (589 / 428 / 421 / 115 / 79) | ⚠️ **Round 8.** Five libraries relocated into one jar, none by a `shaded`/`shadow` segment (which is why Pass 2 never saw them). Embedded `pom.properties`: `commons-compress` 1.28.0, `commons-io` 2.22.0, `commons-lang3` 3.20.0, `commons-codec` 1.19.0, `maven-shared-utils` 3.3.4. One `META-INF/NOTICE` (Codec, IO, Lang, Compress: plain ASF text) and no `LICENSE`. Four are Apache-2.0 on-list facts; the shaded `commons-compress` carries row 29's `sevenz` package (46 files) | **Row 29** (widened); the other four are facts |
| `spring-boot`, `spring-boot-autoconfigure`, `spring-jdbc`, `netty-transport` | 91, 23, 22, 11 under `.../embedded/` | Each project's own "embedded server / database / channel" packages | False positives, first-party |
| `jsr305:3.0.2`, `tomcat-embed-core`, `tomcat-embed-el`, `xalan:serializer`, `xercesImpl`, `xml-apis`, `poi-ooxml-lite` | root-package mismatch | `javax.annotation`, `jakarta.*`, `org.apache.xml/wml/html`, `org.w3c.dom`, `com.microsoft.schemas` — each project's own or spec-mandated package names | Already recorded, or first-party |
| `org.apache.servicemix.bundles.jzlib:1.1.3_2` | not flagged by path | ⚠️ **The root-package check does not flag it:** the artifactId contains "jzlib", which matches `com/jcraft/jzlib`. Found instead by F.2's `DEPENDENCIES` read. **Row 36** | Documented limit of the heuristic |

### F.4 — Round 7: Pass 3, native libraries, and Pass 4, wrapper self-disclosure

**Pass 3.** Every non-class entry in each of the 258 jars was checked by extension (`.so`, `.dylib`, `.dll`, `.jnilib`,
`.a`) and by file magic (ELF, Mach-O, PE). Measured: `zstd-jni:1.5.5-11` 17 and `1.5.7-6` 18 (row 16); `jna:5.13.0` 26
(19 `.so`, 3 `.dll`, 2 `.jnilib`, 2 `.a`) and `jna:5.17.0` 28 (rows 6, 13 — JNA's own `META-INF/LICENSE` covers them — ⚠️ **round 8, one thing that file does not cover:** `libjnidispatch` statically embeds **libffi**
(`nm -D` on `com/sun/jna/linux-x86-64/libjnidispatch.so` in both `jna:5.13.0` and `jna:5.17.0` shows `ffi_call`,
`ffi_prep_cif`, `ffi_closure_alloc` etc. as *defined* `T` symbols; JNA's tags `5.13.0` and `5.17.0`
`native/libffi/README.md` say libffi-3.4.4). libffi's own `LICENSE` there is "libffi - Copyright (c) 1996-2022 Anthony
Green, Red Hat, Inc and others … Permission is hereby granted, free of charge …" — the MIT permission notice, which
JNA's `META-INF/LICENSE` (788 bytes in `5.13.0`: Apache-2.0 OR LGPL-2.1, nothing else) does not reproduce. On-list in
substance, so **recorded as a fact, not a row**, the same standard row 16 applied to zstd inside `zstd-jni`; shipped in
`dp-core`, `launcher`, `demo-launcher` (`jna:5.13.0`));
`netty-transport-native-epoll:4.2.13.Final` (linux-x86_64 classifier) 1 — Netty's own JNI code; `strings` finds only a
GCC compiler-version comment; `byte-buddy-agent:1.17.8` 2 (`attach_hotspot_windows.dll`) — the project's own code,
with `LICENSE`/`NOTICE` in the jar; `mockito-core:5.17.0` one `.raw` file that is a class-file image, not a native
library. No other jar among the 258 contains a native library. Facts, except `zstd-jni` (row 16).

**Pass 4.** Every `META-INF/DEPENDENCIES` file's `License:` lines were read; the only jar where a listed dependency is
also the jar's own class content is the ServiceMix `jzlib` wrapper (row 36).

### F.5 — Round 7: what the sweep can and cannot see

It can find (a) licence files inside jars, (b) shading under the listed path segments and class names, (c) native
libraries by extension and magic, (d) wrappers that disclose themselves in `META-INF/DEPENDENCIES`. It cannot find
third-party code copied into a jar's own packages with no licence file, no distinguishing path and no disclosure, and it
does not read class or source headers except where one of these heuristics pointed at one. The round-6 sentence "No
undisclosed off-allow-list shading was found beyond row 31" is replaced by: **none was found by methods (a)–(d);
rows 31–36 are what they found.** A future re-run of T0.4b's second check should treat this list as a floor. ⚠️ **Round 8 replaces this paragraph's
claim again:** methods (a)–(d) also missed content whose licence is stated only in a **data file's header** (rows 37,
39), only in an **appended section of a jar's top-level `LICENSE`** (row 38), only **inside a shaded artifact under a
path segment the heuristic did not list** (`surefire-shared-utils`), or only in **compiled form** (row 40). F.8
adds three methods (e) to (g); the floor is now (a)–(g), and the two limits the round-8 review named —
the sweep set (258, omitting three Surefire provider jars) and nested jars — are named in the Method above and
swept in F.8.

### F.6 — Round 7: Netty's `NOTICE.txt` in full, read against the 13 Netty jars this reactor resolves

`NOTICE.txt` at tag `4.2.13.Final` (WebFetched) lists 31 third-party components. The 13 resolved jars are
`netty-{buffer, codec-base, codec-compression, codec-http, codec-socks, common, handler, handler-proxy, resolver,
transport, transport-classes-epoll, transport-native-epoll, transport-native-unix-common}`; none carries a
`LICENSE`/`NOTICE`. Per component:

- **"Contains" (bundled), present in a resolved jar:** JSR-166 extensions (`ThreadLocalRandom`) and Robert Harder's
  Base64 — Public Domain, **row 31**; **Webbit** — "BSD License", `WebSocket08FrameDecoder`, **row 35**; JCTools —
  "ASL2 License", 140 entries, recorded in round 6 (Apache-2.0); jbzip2 (`Bzip2*`, 23 entries), libdivsufsort
  (`Bzip2DivSufSort`, 4), jfastlz (`FastLz*`, 5) — "MIT License", all in `netty-codec-compression`, on-list in substance,
  facts; SLF4J (MIT), Apache Harmony (Apache-2.0), Apache Commons Lang (Apache-2.0) — "contains a modified portion of",
  no separately named class, on-list in substance, facts.
- **"Contains", carrier not resolved here (measured — no such jar among the 13):** HPACK ×3 (`netty-codec-http2`),
  Protocol Buffers (`netty-codec-protobuf`), dnsinfo.h (Apple Public Source License 2.0; `netty-resolver-dns-native-macos`),
  Maven Wrapper (source tree only). **"Statically linked against":** Quiche (BSD2), boringssl (Apache-2.0) — QUIC and
  `tcnative` modules, not resolved. Nothing to record for this reactor.
- **"Optionally depends on" (an external jar, not bundled):** JZlib, Compress-LZF, lz4, lzma-java, zstd-jni, Bouncy Castle,
  Snappy, JBoss Marshalling, Caliper, Commons Logging, Log4J, Aalto XML, Brotli4j. Not embedded in any Netty jar. Where
  this reactor resolves one independently it has its own line: `zstd-jni` (row 16, build-tool only), BouncyCastle
  (row 3), Log4J, Commons Logging, and — reached through `sol-jcsmp` rather than through Netty — **JZlib, in the
  ServiceMix wrapper (row 36)**, which is exactly the optional dependency `netty-codec-compression`'s `JZlib*` classes
  call.

### F.7 — Round 7: on-list facts added (no row)

`plexus-*`: `javolution.license.TXT` (2-condition BSD) and `thoughtworks.TXT` (CruiseControl, 3-condition BSD) ·
`jackson-core`: `fast_float` (MIT) and `bigint` (2-clause BSD) sections of `FastDoubleParser-ThirdParty-LICENSE` ·
`archunit`: shaded Guava with no public-domain classes, shaded ASM · `netty-codec-compression`: jbzip2, libdivsufsort,
jfastlz (MIT) · `netty-*`: SLF4J, Apache Harmony, Commons Lang portions · `snakeyaml:2.4`: Google gdata escapers
(Apache-2.0) · `xercesImpl:2.12.2`: Sun Microsystems 1999 credit · `byte-buddy-agent`, `netty-transport-native-epoll`:
first-party native code · `license-maven-plugin`: licence-template corpus (data). None is a new owner-decision row.

**Round 8 additions to the fact list (no row):** `jna:5.13.0` / `5.17.0` — libffi 3.4.4 statically embedded in `libjnidispatch` (MIT permission notice; F.4) · `jackson-dataformat-toml:2.21.4` — `META-INF/LICENSE` is Apache-2.0 plus two appended on-list texts: the TOML grammar (MIT, "Copyright (c) Tom Preston-Werner") and the JFlex lexer generator (3-clause BSD-style) · `poi`/`poi-ooxml`/`poi-ooxml-lite:5.5.1` — `META-INF/LICENSE` SUBCOMPONENTS also carries Inbot's `ReplacingInputStream` (MIT) and a Scala-derived `ExceptionUtils` (Apache-2.0) · `commons-math3:3.6.1` — Hairer (BSD-2), Matsumoto/Nishimura (BSD-3), Kuo/Joe (BSD-3), SciPy/Enthought (BSD-3) in the appended section (row 38 carries the two that are not on-list) · `surefire-shared-utils:3.5.6` — shaded Commons Codec, IO, Lang3, `maven-shared-utils` (Apache-2.0; F.3) · `spring-boot-loader-tools:3.5.16` — its three nested jars (`spring-boot-loader.jar`, `spring-boot-loader-classic.jar`, `spring-boot-jarmode-tools.jar`) each carry a standard 11,359-byte Apache `LICENSE.txt` and the Spring Boot `NOTICE.txt`, nothing else, so the injected loader/jarmode code is Apache-2.0 first-party · `org.osgi:org.osgi.core:6.0.0` / `org.osgi.annotation:6.0.0` — `about.html`: Apache-2.0 (Sun/W3C hits are trademark and Import-Package text) · `maven-shared-incremental:1.1` — its `DEPENDENCIES` lists `backport-util-concurrent:3.1` (Public Domain), which is **not resolved** anywhere in this reactor (0 matches in `dependency:tree`, `dependency:resolve-plugins` and this report), so it is a listing, not content.

### F.8 — Round 8: three further methods, and the sweep set widened

**(e) Data-file content sweep.** Every entry that is not a class, image, font or native library (≤ 6 MB) of every jar in
the widened set — the 258 plus the three Surefire provider jars (**261**), and, recursively to depth 3, every nested
jar (only `spring-boot-loader-tools:3.5.16` has any: three) — was read and searched, case-insensitively, for licence
vocabulary: `public domain`, `Mozilla Public`, `MPL`, `GNU General/Lesser/Library`, `GPL`/`LGPL`, `Creative Commons`/
`CC0`, `CDDL`, `Common Development`, `Eclipse Public`/`Distribution`, `EPL`/`EDL`, `acknowledg(e)ment`, `Apache License
1.x`, `Boost`, `Unicode, Inc`, `Sun Microsystems`, `no license`, `without restriction`, `University of`, `W3C`,
`Artistic`, `Common Public`, `Academic Free`, `zlib`, `SIL Open Font`, `JSON License`, `netlib`, `permission is granted`,
`MIT`/`BSD` variants. 278 (jar, entry) hits over roughly 120 jars, each triaged:

| Class of hit | Where | Disposition |
|---|---|---|
| **New content, new rows** | `httpclient5`, `httpclient` (MPL-2.0 data files); `commons-math3` (`LICENSE.txt` appended sections); `spring-web` (`mime.types`); `poi-ooxml-lite`, `poi-ooxml` (NOTICE/LICENSE + derived schema content) | Rows 37, 38, 39, 40 |
| **New coordinate, existing row** | `doxia-core` (`xml.xsd`); `commons-compress` 1.26.1/1.27.1/1.28.0, `surefire-shared-utils` | Rows 30 and 29, widened |
| Already a row | `tomcat-embed-core` (74 raw hits), `xmlbeans`, `jakarta.*`, `xml-apis`, `jna*` (`LGPL2.1` file), `nuiton-processor`, `jdom2`, `sisu.inject`, `json`, `java-rdfa`, `plexus-*`, `jackson-core`, `commons-compress:1.24.0`, `license-maven-plugin` (templates and `plugin.xml` licence-name lists), `logback-*` (`*-version.properties`) | Rows 1, 2, 6–8, 11, 13, 14, 17, 18, 21, 24–27, 29, 30, 32 |
| On-list fact | `jackson-dataformat-toml` (MIT + JFlex BSD), `checker-qual` (MIT), `hamcrest` (BSD-3), `mockito` (MIT), `slf4j-api`/`jul-to-slf4j` (MIT), `postgresql` (F.2), `byte-buddy`, `asm` `MANIFEST` (`Bundle-License` BSD-3), `poi` LICENSE sub-sections, `osgi.*` `about.html`, `spring-*` `license.txt` (`EPL` boilerplate) | F.7 |
| Noise | `MANIFEST.MF` `Import-Package: org.w3c.dom…` (W3C), bouncycastle `.SF`/`.DSA` signature files, `Encodings.properties` (`UNICODE` charset names), `Parser.jjt`, `DOMMessages*.properties`, `spring-configuration-metadata.json` ("acknowledgment"), `xdoc-2.0.xsd` (W3C entity names in comments) | none |
| Listing of a *resolved dependency's* licence, not content | the `META-INF/DEPENDENCIES` files (`maven-site-plugin`, `maven-reporting-impl`, `doxia-*`, `maven-jar-plugin`, `maven-archiver`, `odfdom-java`, `maven-shared-incremental`) | rows 12, 18, 21, 24; `backport-util-concurrent` is unresolved |
| First-party | `sol-jcsmp:10.30.1` `META-INF/LICENSE` ("Solace API License Agreement Version 1.1") | the named exception |

**(f) Appended-section check on every `LICENSE`/`NOTICE`/`COPYING` file.** For each such entry that contains the
Apache-2.0 text, whatever follows the boilerplate's last sentence ("limitations under the License.") was extracted.
Non-empty residue in exactly **18** jars: `tomcat-embed-core` (33,894 bytes; row 27), `spring-*` ×11 (4,201; ASM, CGLIB,
JavaPoet, Objenesis), `poi`/`poi-ooxml`/`poi-ooxml-lite` (3,054; ECMA-376, Inbot MIT, Scala), `xmlbeans` (3,879; Gradle
wrapper, row 30's W3C), `jakarta.annotation-api` (4,054; row 2), and **`commons-math3` (12,670 bytes) — the one whose
appended content had not been read before round 8** (row 38). Root cause of the miss: F's method sent every jar with
exactly two licence-named entries to "not investigated further unless the NOTICE itself names embedded third-party
content", and `commons-math3`'s two entries (`LICENSE.txt`, `NOTICE.txt`) fell in that bucket; its `NOTICE.txt` names
nothing off-list.

**(g) Sweep-set completion.** The three Surefire provider jars and the three `spring-boot-loader-tools` nested jars
were swept with (e) and (f): nothing off-list (`surefire-junit-platform`'s `DEPENDENCIES` lists `EPL` for its resolved
`junit-platform-launcher`, which is row 10; the nested jars are standard Apache).

**Still not seen, stated plainly.** (e) cannot see content that carries its licence only in a **class file's** source
header, in a binary resource, or in another language's text; a data file over 6 MB was skipped; and none of this reads
Maven-Central-only artifacts that are not cached (`maven-site-plugin:3.12.1`'s and `maven-deploy-plugin:3.1.4`'s
transitive realms — see the open question). Treat rows 29–40 as what methods (a)–(g) found, not as a bound.
