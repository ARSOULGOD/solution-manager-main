# Plan 010 T3.0 — the JSON-Schema library: licence, keyword measurement, recommendation (Steps 0-1)

Date 2026-10-05. Branch `p010/t30-t31`. Status: **T3.0 Steps 0-3 done and T3.1 done** (one commit, as Step 3 says). The
"Steps 2-3 results" section at the end records what the online resolution, the gate and the jars showed; the earlier
sections are the Steps 0-1 record and are kept as written, with the two places the later measurement corrected them
marked. Every external fact below was read by WebFetch on
2026-10-05 (`I2`); each cites its URL.

## Recommendation

**`com.networknt:json-schema-validator:2.0.8`** (Apache-2.0), added to `backend/cp-core/pom.xml` only.

- It is the **Jackson 2** line. The `3.0.x` line (latest `3.0.8`, 2026-09-30) requires Jackson 3 (`tools.jackson`,
  release note "Jackson 3.2.1"), which this reactor (Spring Boot 3.5.16, `jackson-bom` **2.21.4**, measured with
  `help:evaluate`) does not use. `3.0.x` is therefore out.
- `2.0.8` (2026-10-02) vs `2.0.7`/`2.0.6`/`2.0.5`: release notes (GitHub releases page) show 2.0.5 raised Jackson to
  2.22.1 for a security fix, 2.0.6 added `Error.isCustomMessage()`, 2.0.7 kept `Error` constructor binary compatibility,
  2.0.8 changed `nullable` resolution through composition (a keyword this schema does not use). 2.0.8 is the newest of
  the 2.x line and contains the Jackson security fix; nothing in it touches a keyword the schema uses.
- ⚠️ **Open until T3.1 measures it:** 2.0.8 is *built against* Jackson 2.22.1; the reactor resolves 2.21.4 (Boot
  manages it). Binary compatibility across that minor is expected, not measured. T3.1's first red/green run is the
  measurement; if it fails, the fallback is to override `jackson-bom` for cp-core only, which is a decision to route,
  not to make silently.
- Alternative considered: none run. No other 2020-12 Java validator is in `~/.m2` (searched), and the plan names this
  one; a second candidate would need its own licence and keyword pass.

## Step 0 — licences (`I8` / `REQ-014`), at the pinned version

| Artifact | Version | Declared licence (published POM) | Source |
|---|---|---|---|
| `com.networknt:json-schema-validator` | 2.0.8 | `Apache License Version 2.0` (no comma) | `https://repo1.maven.org/maven2/com/networknt/json-schema-validator/2.0.8/json-schema-validator-2.0.8.pom`; repository `LICENSE` at tag 2.0.8 is the Apache License 2.0 text |
| `com.ethlo.time:itu` | 1.14.0 | `Apache License, Version 2.0` | `https://repo1.maven.org/maven2/com/ethlo/time/itu/1.14.0/itu-1.14.0.pom` (no parent; its dependencies are test-scope only) |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` | 2.21.4 (Boot-managed; networknt asks 2.22.1) | `The Apache Software License, Version 2.0` | already resolved by the reactor; audit row in `licence-audit-2026-09.md` (line 1612) |
| `org.yaml:snakeyaml` | 2.4 | `Apache License, Version 2.0` | already in cp-core's tree (audit line 1721) |
| `org.slf4j:slf4j-api` | 2.0.18 resolved (networknt asks 2.0.17) | MIT | already in cp-core's tree (audit line 1691) |
| `jackson-databind`, `jackson-core`, `jackson-annotations` | 2.21.4 | Apache-2.0 (as audited) | already in cp-core |

networknt's other dependencies are **optional** (`joni`, `graal-sdk`, `js`) or **test** scope (`logback-classic`,
`hamcrest`, JUnit, JMH) and are not pulled into cp-core. **New to the tree: exactly two artifacts**, both Apache-2.0:
`json-schema-validator:2.0.8` and `itu:1.14.0`. `REQ-014`'s recorded reading (both Apache-2.0) is re-verified at these
versions.

**Gate consequence (found, not yet fixed):** `backend/licence-allowlist.txt` maps eight Apache spellings and
`Apache License Version 2.0` (no comma) is **not** one of them. With networknt added, `license-maven-plugin` would
report an unmapped name and `verify` would fail closed, by design (`T0.4b` step 1(c)). The fix is one exact variant on
the `Apache-2.0|…` line, in the same commit as the pom line (the literal name is verified above against the
Apache-2.0 licence text in the artifact's own repository). That line is the T0.4b file's; the integrator should confirm
adding a ninth spelling is within the T0.4b mapping mandate (it is the same kind as Appendix B's eight) or have the
owner approve it. No new owner licence question exists *for the declared dependency licences* (corrected below: the jar's bundled Unicode data file is off the six).

Also owed once the jars resolve (as `licence-audit` Appendix E did for jackson-core): list the jars' contents for
bundled third-party code (`META-INF/*LICENSE*`, shaded packages) and record it. Not possible offline. **Done below;
it found content this table did not (one off-list data licence) — see "Bundled third-party content".**

## Step 1 — M1: the keywords the schema uses, and support

Measured with a schema-aware walk of `contracts/solution-package.schema.json` (`$schema` =
`https://json-schema.org/draft/2020-12/schema`, `$id` = `https://solace.com/solution-manager/solution-package.schema.json`).
Property *names* inside `properties` maps are excluded; the walk found no unknown keyword.

Keywords used (24): `$schema $id $ref $defs type enum const properties required additionalProperties items minItems
minLength minProperties minimum uniqueItems pattern allOf anyOf oneOf not if then title description`.

- `$ref`: 18 uses, **all** local fragments `#/$defs/<name>` (13 distinct targets); no remote or `$id`-relative `$ref`.
- `pattern`: exactly one, `^[a-z][a-z0-9-]*$` — identical meaning under JDK regex and ECMA-262, so the library's
  default JDK engine (README: "not ECMA 262 compliant") is sufficient; `joni` is **not** needed.
- Not used: `format`, `unevaluatedProperties`, `unevaluatedItems`, `$dynamicRef`, `dependentSchemas`,
  `patternProperties`, `prefixItems`, `contains`, `else`.
- `else` unused; `if/then` appear 6 times each.

Support at 2020-12, from the library's `doc/compatibility.md` at tag 2.0.8 (`raw.githubusercontent.com/networknt/json-schema-validator/2.0.8/doc/compatibility.md`):
fully implemented — `$id $ref $defs type enum const properties required additionalProperties items minItems uniqueItems
minimum minLength minProperties pattern allOf anyOf oneOf not if-then-else`; official-suite result stated as
"pass: r:1261 (100.0%) o:651 (100.0%)". `$schema`, `title`, `description` are not in the keyword table; the same page
says unknown keywords are treated as annotations, which is the correct treatment for metadata. README states draft
v4/v6/v7/2019-09/2020-12 support, Java 8+, and local `$ref` resolution without network access. **No keyword the schema
uses is unsupported; the candidate is not disqualified.** This is documentation, not behaviour: T3.1's parity tests
(19 negatives, all positives) are the behavioural proof.

## Measured: not in `~/.m2`

`mvn -o dependency:get -Dartifact=com.networknt:json-schema-validator:2.0.8` and
`… com.ethlo.time:itu:1.14.0` both end `BUILD FAILURE` (could not be resolved). No other JSON-Schema validator jar is
in `~/.m2` (`find` over names `*schema*`, `*networknt*`, `*everit*`, `*victools*`, `*saasquatch*`). T3.1 therefore cannot
start offline.

## The one online command (owner, Step 2)

After adding `json-schema-validator` `2.0.8` to `backend/cp-core/pom.xml` (version pinned there), run once:

```
mvn -f backend/pom.xml -pl cp-core dependency:resolve
```

(no `-o`; it fetches the two missing artifacts and their poms). Then the reactor builds offline again.

## Draft decision row (`«next free BE-D»`)

Library `com.networknt:json-schema-validator:2.0.8`, Apache-2.0, with `com.ethlo.time:itu:1.14.0` (Apache-2.0);
consumer-side load-time validation of Solution Packages in `cp-core` (`backend-consumption.md` §9, `BE-D11`); `BE-D10`'s
"mirror-and-lock, no codegen, no producer-side runtime validation" is unaffected. The Java validator is a second
implementation of the gates, and the Python gate remains normative until T3.1's parity test passes.

## Steps 2-3 results (2026-10-05, after OR-86)

**Step 2.** The integrator ran the one online resolution; `json-schema-validator-2.0.8` and `itu-1.14.0` are in
`~/.m2`. Everything after ran `mvn -o`.

**Step 3, the pom and the gate.** `backend/cp-core/pom.xml` gains `com.networknt:json-schema-validator:2.0.8` (version
pinned in the module, which is its only consumer). `backend/licence-allowlist.txt` gains the ninth Apache-2.0
spelling `Apache License Version 2.0` (OR-86). Measured: `mvn -o -f backend/pom.xml -DskipTests verify` is
`BUILD SUCCESS` on all ten modules; cp-core's `THIRD-PARTY-shipped.txt` lists
`(Apache-2.0) com.networknt:json-schema-validator:2.0.8` and `(Apache-2.0) com.ethlo.time:itu:1.14.0`. Proof the
allow-list line is what makes it pass: with the spelling removed, `mvn -o -pl cp-core -DskipTests verify` fails
(`License: 'Apache License Version 2.0' used by 1 dependencies` ... `There are some forbidden licenses used`); restored
byte-for-byte (sha1 `7e46760b...`), green. `backend/scripts/check-licences.py` and `test-check-licences.sh` pass.
`jackson-dataformat-toml:2.21.4`, which appears under the new dependency in `dependency:tree`, is not new — it is in
the audit (row at line 1611, `cp-core:compile` already).

**Jackson 2.21.4 vs the library's 2.22.1 — measured, compatible.** `dependency:tree` shows cp-core resolving
`jackson-databind/-core 2.21.4`, `-annotations 2.21`, `jackson-dataformat-yaml 2.21.4`. T3.1's 57 tests (3 fixtures,
19 negatives, overlap, 30 mutants in one oracle test, 8 no-echo cases) run the library over the full schema on that classpath and pass.
Nothing was overridden.

**Bundled third-party content (Appendix F method, jars opened read-only).** Pass 1 (`LICENSE|NOTICE|COPYING` in the
file list): 0 matches in either jar. Pass 2 (`/shaded?/|shadow`): 0 matches. Both passes are therefore silent, yet the
jars do carry third-party content, found by listing the non-class entries and the package roots:

| Jar | Content | Licence (source) | On the six? |
|---|---|---|---|
| networknt 2.0.8 | `com/networknt/org/apache/commons/validator/routines/` — a relocated copy of Apache Commons Validator (`DomainValidator`, `EmailValidator`, `InetAddressValidator`, `RegexValidator`); the pom excludes the path from JaCoCo | Apache-2.0 (commons-validator `LICENSE.txt`, WebFetch) | yes |
| networknt 2.0.8 | `draft-04/06/07`, `draft/2019-09/**`, `draft/2020-12/**` meta-schema files | JSON Schema specification repository `LICENSE`: BSD-3-Clause **and** Academic Free License 3.0 (WebFetch) | yes (BSD-3-Clause) |
| networknt 2.0.8 | `ucd/extracted/DerivedJoiningType.txt` — Unicode 15.0.0 Character Database file, header "© 2022 Unicode, Inc." | **Unicode License v3** (`unicode.org/license.txt`, WebFetch: covers "data files and any associated documentation"; keep the copyright and permission notice; no warranty; no use of the holder's name in promotion) | **no** |
| networknt 2.0.8 | `ucd/RFC5892-appendix-B.txt` — the IDNA code-point table from RFC 5892 appendix B | IETF Trust legal provisions (not fetched; a data table, no licence text in the file) | not determined |
| networknt 2.0.8 | `jsv-messages*.properties` (31 locales) | first-party | n/a |
| itu 1.14.0 | `leap_second_dates.csv` (factual date list), `THIRD-PARTY.txt` = "The project has no dependencies." | n/a | n/a |

The two `ucd/` files back the `idn-hostname`/`idn-email` formats. **`solution-package.schema.json` uses no `format`**
(Step 1), so neither is read by this code path, but both ship in the jar. The Unicode License v3 is permissive and
data-only, in the same family as the audit's other on-list-in-substance facts, but it is not one of the six names, so
under `REQ-014` whether it is accepted is the owner's to rule — see the report's open items. The library is not
disqualified by it in the way an off-list *dependency* would be (the gate reads the declared POM licence, which is
Apache-2.0); this row exists so the fact is on record.

## T3.1 — what was built and what it found

`com.solace.sm.cp.catalog.validation`: `PackageValidator` (gate 1, then gates 2 and the semantic gates, the Python's
order), `SchemaGate` (the library; remote fetching off; its message is discarded — it quotes property names, measured
by mutation — and a failure is `path + keyword`), `SignatureGate`, `SemanticGates` (4, 5, 5b, 9, 10, 12, 13, 14 in the
Python's own check order, because the Python prints only `errs[0]`), `OverlapGate` (3), `GateFailure`,
`ValidationResult`, and a small `Py` helper. The schema is a constructor argument; the mirror that supplies it in
production is T3.2's (`SchemaMirrorLockTest`).

**Quirks of the Python kept on purpose, each pinned by a mutant the Python judges** (`MutantParityTest`; the Python is
the normative gate until parity, so the port agrees with it rather than with a cleaner reading):
- `isinstance(v, int)` is true for a JSON `true`, so a `when` value of `true` on an `int` field passes gate 5b.
- `==` treats `true == 1` and `1 == 1.0`, so those are "in options" / "equal" for gates 5 and 5b.
- Gate 3's range parser is the Python's minimal one: an unreadable bound is open, not an error.
These are Python bugs, not decisions. They are routed to whoever owns `contracts/validation` as candidates for
fixing in the Python *and* the port together; the port does not diverge first.
