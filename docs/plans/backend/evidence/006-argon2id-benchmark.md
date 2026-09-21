# Plan 006 — Argon2id cost measurement

**Status: MEASURED, 2026-09-16.** This closes plan 006's one measurement blocker. Every number below was produced
by the commands in §2 on the host named in §1; nothing here is estimated or recalled.

⚠️ **This host is a development laptop, not the deployment target.** §5 states the re-measurement obligation that
follows from that, and it is a task obligation rather than a footnote.

---

## 1. Environment, measured

| | Measured value | How |
|---|---|---|
| JDK used for the benchmark | **OpenJDK 17.0.17+10-LTS**, Microsoft build, `aarch64` | `java -version`; the project targets Java 17, so the benchmark ran on 17 rather than the 21 on `PATH` |
| JVM | OpenJDK 64-Bit Server VM | `System.getProperty("java.vm.name")` |
| CPU threads visible to the JVM | **14** | `Runtime.availableProcessors()` |
| Default `MaxHeapSize` on this host | **12,288 MiB** | `java -XX:+PrintFlagsFinal -version \| grep MaxHeapSize` |
| `spring-security-crypto` | **6.5.11** — *managed* by the Spring Boot 3.5.16 BOM | `mvn dependency:tree` against a POM importing `spring-boot-dependencies:3.5.16` |
| `bcprov-jdk18on` | **1.86** — ⚠️ **NOT managed by the BOM; pinned explicitly** | see §3 |
| `spring-jcl` | 6.2.19 (transitive; required — see §4 item 2) | `mvn dependency:tree` |

## 2. Exact commands

Run in a scratchpad directory outside the repository, against a synthetic POM that imports the reactor's own
Spring Boot BOM version:

```
# 1. Is bcprov managed by the BOM?  (declare it with NO version and see whether it resolves)
mvn -o -B dependency:tree

# 2. With the version pinned, confirm what resolves
mvn -B dependency:tree

# 3. Compile and run the sweep on JDK 17
mvn -B compile
CP=$(mvn -o -q -B dependency:build-classpath -Dmdep.outputFile=/dev/stdout -DincludeScope=runtime)
/Users/parikshit/Library/Java/JavaVirtualMachines/ms-17.0.17/Contents/Home/bin/java \
    -cp "target/classes:$CP" bench.PwBench

# 4. Concurrency cost at the candidate parameters
/…/ms-17.0.17/…/bin/java -cp "target/classes:$CP" bench.Conc <memoryMiB> <iterations> <concurrency>
```

**Method:** each encoder is warmed with one discarded `encode`, then `encode`, a matching `matches` and a
**mismatching** `matches` are each run five times and the **median** reported. The mismatch column exists because a
verification that short-circuits on a wrong password would be a timing oracle — it does not (see §4 item 3).

## 3. ⚠️ Dependency finding: the BOM does not manage Bouncy Castle

Declaring `org.bouncycastle:bcprov-jdk18on` with no version against `spring-boot-dependencies:3.5.16` **fails**:

```
[ERROR] 'dependencies.dependency.version' for org.bouncycastle:bcprov-jdk18on:jar is missing.
```

So the reactor must pin it. **Pin `1.86`** — verified as the current release from
`https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/maven-metadata.xml`
(`<release>1.86</release>`, 2026-09-16), and it is the version the timings below were produced with.

## 4. Three findings that change what plan 006 must write

1. ⛔ **`PasswordEncoderFactories.createDelegatingPasswordEncoder()` does NOT default to Argon2id.** Measured: its
   default prefix is **`{bcrypt}`**, and `routes to argon2id = false`. The owner ruling is *"Argon2id through
   `DelegatingPasswordEncoder`"*, so the encoder must be **constructed with `argon2id` as the default id** — the
   convenience factory is the wrong entry point and would silently give bcrypt. This is the single most likely way
   to implement the ruling and miss it.
2. **`spring-security-crypto` alone is not enough to construct the delegating encoder.** It needs a
   commons-logging binding (`spring-jcl`) because `BCryptPasswordEncoder`'s constructor initialises a logger:
   `NoClassDefFoundError: org/apache/commons/logging/LogFactory`. In the reactor this arrives with
   `spring-boot-starter`; in a crypto-only module it does not.
3. **Verification is not short-circuiting.** Matching and mismatching `matches` cost the same at every parameter
   set (e.g. 19 MiB: 19 ms vs 20 ms; 384 MiB: 1081 ms vs 1031 ms) — there is no timing oracle to worry about here.

## 5. The sweep — median of 5, JDK 17.0.17, bcprov 1.86

`saltLength = 16`, `hashLength = 32`, `parallelism = 1` throughout (the ruling's floor for `p`).

| Memory | Iterations | encode (ms) | match (ms) | mismatch (ms) |
|---|---|---|---|---|
| **19 MiB — the ruling's FLOOR** | **2** | **18** | **19** | 20 |
| 19 MiB | 4 | 35 | 36 | 37 |
| 32 MiB | 4 | 61 | 61 | 62 |
| 64 MiB | 3 | 104 | 101 | 102 |
| 64 MiB | 4 | 137 | 136 | 136 |
| 96 MiB | 4 | 214 | 216 | 215 |
| 128 MiB | 4 | 294 | 293 | 295 |
| 192 MiB | 4 | 471 | 488 | 480 |
| 256 MiB | 4 | 655 | 659 | 673 |
| 320 MiB | 4 | 846 | 853 | 882 |
| **384 MiB** | **4** | **1021** | **1081** | 1031 |
| 64 MiB | 16 | 545 | 535 | 539 |
| 96 MiB | 12 | 653 | 646 | 648 |

Two runs of the whole sweep agreed within noise (floor 18–20 ms both runs; 192 MiB 471–488 ms both runs).
For reference, `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()` encodes in **45–46 ms** on this host.

### Concurrency cost at the candidates

| Parameters | Concurrency | Wall time | Effective per verification | Transient memory |
|---|---|---|---|---|
| 384 MiB / t=4 | 1 | 1,201 ms | 1,201 ms | 384 MiB |
| 384 MiB / t=4 | 4 | 1,752 ms | 438 ms | **~1.5 GiB** |
| 96 MiB / t=12 | 4 | 719 ms | 180 ms | ~384 MiB |
| 19 MiB / t=2 | 4 | 39 ms | 10 ms | 76 MiB |

Concurrent verifications parallelise across the 14 available cores, so four at 384 MiB cost 1.75 s of wall time
rather than 4.8 s — but they hold **~1.5 GiB simultaneously**.

## 6. Recommendation

**`memory = 384 MiB, iterations = 4, parallelism = 1`, salt 16 / hash 32** — the memory-first point that meets the
ruling's *"approximately one-second verification"* (1,021 ms encode / 1,081 ms match) while staying far above the
19 MiB / t=2 floor. Memory-first is the right lever because Argon2's security argument *is* memory-hardness;
buying the same second with iterations (96 MiB / t=12 → 653 ms) is four times cheaper for an attacker with memory
to spare.

⛔ **Two conditions come with it, and neither is optional:**

1. **The login path must be concurrency-capped.** At 384 MiB per in-flight verification, an uncapped login endpoint
   is a memory-exhaustion denial of service that needs no credentials to trigger. A bounded semaphore around
   verification — with a fast refusal when saturated — is part of implementing this parameter set, not a later
   hardening item.
2. **A heap floor must be declared.** This host's default 12 GiB hides the cost; a 1 GiB production heap would not
   survive three concurrent logins. The install prerequisites must state the floor alongside the cap.

⚠️ **Re-measurement obligation.** These numbers are from an Apple Silicon laptop with 14 cores. Argon2 cost is
hardware-specific, and a smaller production host will be **slower** at the same parameters — the opposite of the
safe direction, because verification latency becomes user-visible. Plan 006 Task 3 therefore carries an
install-time obligation: re-measure on the target and tune, **never below the 19 MiB / t=2 / p=1 floor**.

⚠️ **If the owner prefers a memory-lighter deployment**, `96 MiB / t=12 / p=1` (≈650 ms, ~4× less memory) is the
measured alternative. It is a posture choice, not an engineering one, and the numbers for both are above.
