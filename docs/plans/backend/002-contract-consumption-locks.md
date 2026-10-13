# Backend Plan 002 - Contract Consumption Locks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `BE-D10`/`BE-D11`: Java consumes `contracts/events/` through mirror-and-lock tests, one contract artifact resolver, one shared publisher seam with a mandatory contract-test kit, and shared contract negatives, so schema/Java drift turns red in `mvn verify`.

**Architecture:** `contracts/` remains the language-neutral authority. `backend/mesh-events` holds the Java bindings, the shared publisher seam, and all test-only contract plumbing (exported as a test-jar); `dp-core` holds the JCSMP implementation; `arch-tests` enforces module boundaries. The one structural change to main code: `NotifyEventShapes` becomes **table-driven** — its branch constraints move from inline switch literals into a package-private declarative table that `validate()` itself consumes — because the lock test must compare the schema against *the data Java actually enforces*, never against a third copy written into the test.

**Tech Stack:** Java 17 (no preview features — `instanceof` patterns yes, `switch` patterns no), Spring Boot 3.x parent BOM, Maven, Jackson, JUnit 5, ArchUnit, the existing Python contract checks behind `contracts/run-checks.sh`.

**Spec:** [`../../design/common/contracts/backend-consumption.md`](../../design/common/contracts/backend-consumption.md) (`BE-D10`/`BE-D11`) plus [`../../design/backend/decisions.md`](../../design/backend/decisions.md). Executors must read both before starting.

## Global Constraints

- `contracts/events/envelope.schema.json` and `contracts/events/notify-events.schema.json` are authoritative for event shape; Java mirrors them and the lock test proves the mirror, both directions.
- Producer-side runtime JSON Schema validation is out of scope by `BE-D10`; typed construction plus build-time lock tests.
- No schema or fixture is committed under `backend/`; Java tests read `contracts/` only through `ContractArtifacts` (`BE-D11`).
- Runtime contract copies (§4.2 of the design) are out of scope: no runtime consumer exists yet. This plan creates only the test-side plumbing.
- `console-gateway` must not import `mesh-events`; `/mesh` is byte-level per `BE-D7`.
- `cp-core` and `dp-core` must not import each other; `mesh-events` stays plane-neutral (existing ArchUnit rules must stay green).
- Event **emission** serializes only through `EventJson.write()`/`EventJson.writer()`; `EventJson.reader()` deserializes; `EventJson.mapper()` hands out defensive copies for tests and tree work. No second `ObjectMapper` in any publisher package.
- `EventPublisher.publish()` fails loud only for malformed/unaddressable events and never throws for broker or serialization failures. Existing failure messages in `NotifyEventShapes` are frozen: the refactor in Task 2 must not change any message text (60 existing tests pin them).
- **Out of scope, explicitly:** auth, mesh tickets, RBAC, package-upload validation, websocket proxying, runtime schema validation, real notify emission from product state, and metrics wiring. Note: `JcsmpEventPublisher`'s javadoc says "plan 002 must expose" the drop counter as a metric — that sentence means the next *feature* plan (where the metrics stack is chosen), not this plan. Do not add Micrometer here.
- `./contracts/run-checks.sh --strict` and `mvn -f backend/pom.xml verify` are the completion gates. The R8 baseline of 126 Java tests only grows.
- Commits are per the task's commit steps, scoped to listed files only. Do not push.

## Code Execution Contract for Sub-Agents

Sub-agents implementing this plan must run on **Claude Opus 5**. Model downgrades require Parikshit's explicit approval.

Every sub-agent is bound by these rules:

- Implement exactly one task from this plan. Do not begin later tasks.
- Before editing, read `CLAUDE.md`, `docs/design/common/contracts/backend-consumption.md`, `docs/design/backend/decisions.md`, this plan, and the files listed in your task.
- Use the package names, class names, and signatures specified here. Do not invent alternate module names, topic shapes, event fields, or resolver behavior.
- Write or strengthen a failing test first where the task specifies one, run it, and confirm the failure reason.
- Negative tests must fail for the intended reason; inspect messages, do not accept any red.
- If a check is intentionally skipped, report it as skipped with the condition. Do not count skipped checks as proof.
- Commit only the task's files, with an imperative commit subject.
- Report changed files, commands run, failures observed before the fix, deviations from the plan, and commit hash.

### Dispatch Template

> You are implementing Task N from `docs/plans/backend/002-contract-consumption-locks.md`.
> Use Claude Opus 5.
> Implement only Task N. Do not start later tasks.
> Follow the Code Execution Contract in the plan.
> Read `CLAUDE.md`, `docs/design/common/contracts/backend-consumption.md`, `docs/design/backend/decisions.md`, this plan, and the files listed under Task N.
> Preserve gates: do not implement auth, tickets, RBAC, runtime package upload validation, websocket proxying, runtime schema validation, metrics wiring, or real notify emission from product state.
> Run the task's verification commands and report real output.
> Commit only Task N files and report the commit hash.

---

### Task 1: Create `ContractArtifacts` and Export It as a Test Jar

**Files:**
- Create: `backend/mesh-events/src/test/java/com/solace/sm/events/contracttest/ContractArtifacts.java`
- Create: `backend/mesh-events/src/test/java/com/solace/sm/events/contracttest/ContractArtifactsTest.java`
- Modify: `backend/mesh-events/src/test/java/com/solace/sm/events/EnvelopeGoldenTest.java`
- Modify: `backend/mesh-events/src/test/java/com/solace/sm/events/NotifyEventShapesTest.java`
- Modify: `backend/mesh-events/src/test/java/com/solace/sm/events/NotifyTopicsTest.java`
- Modify: `backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/EventPublisherContractTest.java`
- Modify: `backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/JcsmpEventPublisherIT.java`
- Modify: `backend/mesh-events/pom.xml`
- Modify: `backend/dp-core/pom.xml`

**Interfaces:**
- Produces: `com.solace.sm.events.contracttest.ContractArtifacts`
  - `public static Path dir(String sub)`
  - `public static List<Path> jsonFiles(Path dir, String... excludeNames)`
  - `public static JsonNode json(Path file)`
- Consumes: `EventJson.mapper()`
- Later tasks consume the resolver from the `mesh-events` test-jar.

The design names **three** existing resolver copies to delete: `EnvelopeGoldenTest.ContractFixtures`, `EventPublisherContractTest.contractDir(...)`, and `JcsmpEventPublisherIT`'s brittle `Path.of("../../contracts/events/fixtures")`. All three go in this task.

- [ ] **Step 1: Add the dependency edge first and verify the missing class fails**

Add this test-scope dependency to `backend/dp-core/pom.xml` (the module already depends on `mesh-events` at compile scope; this is an additional test-jar edge):

```xml
<dependency>
  <groupId>com.solace.sm</groupId>
  <artifactId>mesh-events</artifactId>
  <version>${project.version}</version>
  <type>test-jar</type>
  <scope>test</scope>
</dependency>
```

Temporarily add one import to `EventPublisherContractTest`:

```java
import com.solace.sm.events.contracttest.ContractArtifacts;
```

Run:

```bash
mvn -f backend/pom.xml -pl dp-core -am test
```

Expected: FAIL — either dependency resolution (no test-jar attached yet) or compilation (`ContractArtifacts` not found). Keep the failure text in the task report.

- [ ] **Step 2: Configure `mesh-events` to publish test classes**

`backend/mesh-events/pom.xml` currently has **no** `<build>` section; add one (version managed by the Boot parent):

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-jar-plugin</artifactId>
      <executions>
        <execution>
          <goals>
            <goal>test-jar</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

- [ ] **Step 3: Implement `ContractArtifacts`**

```java
package com.solace.sm.events.contracttest;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.events.EventJson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * THE test-scope resolver for contract artifacts (BE-D11) — the only way Java tests reach
 * contracts/. Resolution order: -Dsm.contracts.root (the repo-split seam, and the lever the
 * lock test's planted-divergence demonstrations use), then ancestor walk from the working
 * directory to the first directory containing contracts/<sub>.
 */
public final class ContractArtifacts {

    private ContractArtifacts() {
    }

    /** Locates {@code contracts/<sub>}, e.g. {@code dir("events/fixtures")}. */
    public static Path dir(String sub) {
        String override = System.getProperty("sm.contracts.root");
        if (override != null && !override.isBlank()) {
            Path candidate = Path.of(override).resolve(sub);
            if (!Files.isDirectory(candidate)) {
                throw new IllegalStateException(
                        "sm.contracts.root=" + override + " has no " + sub + ": " + candidate);
            }
            return candidate;
        }
        Path from = Path.of("").toAbsolutePath();
        for (Path p = from; p != null; p = p.getParent()) {
            Path candidate = p.resolve("contracts").resolve(sub);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("contracts/" + sub + " not found walking up from " + from);
    }

    /**
     * The .json files in {@code dir}, sorted, minus {@code excludeNames}. Throws when the result
     * is empty — a golden suite that finds nothing must fail, never pass vacuously (L7).
     */
    public static List<Path> jsonFiles(Path dir, String... excludeNames) {
        Set<String> excluded = Set.copyOf(Arrays.asList(excludeNames));
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> files = s
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .filter(p -> !excluded.contains(p.getFileName().toString()))
                    .sorted()
                    .toList();
            if (files.isEmpty()) {
                throw new IllegalStateException("no JSON files found under " + dir
                        + " (excluding " + excluded + ")");
            }
            return files;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses one artifact with the contract mapper configuration. */
    public static JsonNode json(Path file) {
        try {
            return EventJson.mapper().readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 4: Add resolver tests including override behavior**

```java
package com.solace.sm.events.contracttest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContractArtifactsTest {

    @TempDir Path tmp;

    @Test
    void findsEventsFixturesFromTheRepository() {
        Path fixtures = ContractArtifacts.dir("events/fixtures");
        assertTrue(Files.isDirectory(fixtures), fixtures.toString());
        assertFalse(ContractArtifacts.jsonFiles(fixtures, "expected-topics.json").isEmpty());
    }

    @Test
    void overridePointsAtTheContractsRoot() throws Exception {
        Path events = Files.createDirectories(tmp.resolve("events/fixtures"));
        Files.writeString(events.resolve("one.json"), "{}\n");
        String old = System.getProperty("sm.contracts.root");
        try {
            System.setProperty("sm.contracts.root", tmp.toString());
            assertEquals(events, ContractArtifacts.dir("events/fixtures"));
        } finally {
            restore(old);
        }
    }

    @Test
    void overrideFailsLoudlyWhenTheSubtreeIsMissing() {
        String old = System.getProperty("sm.contracts.root");
        try {
            System.setProperty("sm.contracts.root", tmp.toString());
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> ContractArtifacts.dir("events/fixtures"));
            assertTrue(e.getMessage().contains("events/fixtures"), e.getMessage());
        } finally {
            restore(old);
        }
    }

    @Test
    void anEmptyDirectoryIsARedResultNotAnEmptyList() throws Exception {
        Path empty = Files.createDirectories(tmp.resolve("events/empty"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ContractArtifacts.jsonFiles(empty));
        assertTrue(e.getMessage().contains("no JSON files"), e.getMessage());
    }

    private static void restore(String old) {
        if (old == null) {
            System.clearProperty("sm.contracts.root");
        } else {
            System.setProperty("sm.contracts.root", old);
        }
    }
}
```

- [ ] **Step 5: Migrate all three existing resolvers**

In `EnvelopeGoldenTest`: delete the nested `ContractFixtures` class entirely and delegate:

```java
import com.solace.sm.events.contracttest.ContractArtifacts;

static Path fixtureDir() {
    return ContractArtifacts.dir("events/fixtures");
}
```

Every former `ContractFixtures.dir(...)`/`ContractFixtures.jsonFiles(...)` call site in `EnvelopeGoldenTest`, `NotifyEventShapesTest`, and `NotifyTopicsTest` becomes the `ContractArtifacts` equivalent — note the sub-path gains the `events/` prefix (`dir("fixtures")` → `dir("events/fixtures")`, `dir("negative")` → `dir("events/negative")`). Keep the existing non-empty assertions in the callers even though the resolver now also throws — the caller-side message names the suite that went vacuous.

In `EventPublisherContractTest`: delete the private `contractDir(String sub)` helper and its walk-up code; replace the two fields:

```java
private static final Path FIXTURES = ContractArtifacts.dir("events/fixtures");
private static final Path NEGATIVES = ContractArtifacts.dir("events/negative");
```

In `JcsmpEventPublisherIT`: replace

```java
private static final Path FIXTURES = Path.of("../../contracts/events/fixtures");
```

with

```java
private static final Path FIXTURES = ContractArtifacts.dir("events/fixtures");
```

- [ ] **Step 6: Run tests**

```bash
mvn -f backend/pom.xml -pl mesh-events,dp-core -am test
```

Expected: PASS. Also confirm the test-jar attaches: the `mesh-events` build log names `mesh-events-0.1.0-SNAPSHOT-tests.jar`.

- [ ] **Step 7: Commit**

```bash
git add backend/mesh-events/pom.xml backend/dp-core/pom.xml \
  backend/mesh-events/src/test/java/com/solace/sm/events \
  backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/EventPublisherContractTest.java \
  backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/JcsmpEventPublisherIT.java
git commit -m "Add shared contract artifact resolver for backend tests"
```

---

### Task 2: Make `NotifyEventShapes` Table-Driven, Then Lock the Table to the Schema

**Files:**
- Modify: `backend/mesh-events/src/main/java/com/solace/sm/events/EventEnvelope.java`
- Modify: `backend/mesh-events/src/main/java/com/solace/sm/events/NotifyEventShapes.java`
- Create: `backend/mesh-events/src/test/java/com/solace/sm/events/SchemaMirrorLockTest.java`

**Interfaces:**
- Produces (all package-private, for same-package tests only):
  - `EventEnvelope.PRODUCERS`
  - `NotifyEventShapes.BRANCHES` — `Map<String, NotifyEventShapes.BranchRule>`
  - `NotifyEventShapes.BranchRule(String kind, Set<String> requiredEntity, Set<String> forbiddenEntity, Map<String, FieldRule> payload)`
  - `NotifyEventShapes.FieldRule` — sealed: `EnumOf(Set<String> members)` · `IsString()` · `IntegerAtLeast(long min)` · `AnyType()`
  - `NotifyEventShapes.handledEvents()`
- Consumes: `ContractArtifacts.json(...)`, `ContractArtifacts.dir(...)`

**Why the refactor is not optional.** A lock test that compares the schema against literals written *in the test* is a third mirror: `NotifyEventShapes` could drift and the test stays green because Java was never consulted. `BE-D10`'s lock is schema ↔ **the data `validate()` actually enforces**, so that data must exist as data. The table is package-private and `validate()` consumes it — a decorative table nothing reads would be the same false green one step removed.

- [ ] **Step 1: Widen `PRODUCERS` to package-private**

In `EventEnvelope`, change `private static final List<String> PRODUCERS` to:

```java
/** Package-private for SchemaMirrorLockTest; not part of the public API. */
static final List<String> PRODUCERS = List.of("cp-core", "dp-core");
```

Keep the existing javadoc above it.

- [ ] **Step 2: Introduce the branch table and rewrite `validate()` to consume it**

In `NotifyEventShapes`, keep the class javadoc, the four enum-set constants (unchanged and `private`; they are reachable through the table), and every private helper (`entity`, `idOf`, `payload`, `enumValue`, `stringValue`, `integerAtLeast`, `asIntegral`, `describe`, `ordered`) **byte-for-byte — no message text changes**. Add:

```java
/** One payload field's constraint, as the schema states it. Package-private for the lock test. */
sealed interface FieldRule permits EnumOf, IsString, IntegerAtLeast, AnyType {
}

/** Schema {@code enum} of strings. */
record EnumOf(Set<String> members) implements FieldRule {
}

/** Schema {@code "type": "string"}. */
record IsString() implements FieldRule {
}

/** Schema {@code "type": "integer", "minimum": min}. */
record IntegerAtLeast(long min) implements FieldRule {
}

/** Schema {@code {}} — required, any JSON type. The pinned Known-gap-4 hole; presence-only. */
record AnyType() implements FieldRule {
}

/** One {@code oneOf} branch: entity scoping plus the closed payload. */
record BranchRule(String kind, Set<String> requiredEntity, Set<String> forbiddenEntity,
        Map<String, FieldRule> payload) {
}

/**
 * The six branches, copied from notify-events.schema.json (I2: copied, never recalled) and
 * LOCKED to it by SchemaMirrorLockTest — schema edits and edits here fail the build until the
 * other side moves in the same reviewed commit. validate() consumes THIS table; nothing else
 * defines a branch.
 */
static final Map<String, BranchRule> BRANCHES = Map.of(
        "instance.health", new BranchRule("placement",
                Set.of("instanceId", "placementId"), Set.of(),
                Map.of("state", new EnumOf(HEALTH_STATE))),
        "instance.config-state", new BranchRule("instance",
                Set.of("instanceId"), Set.of("placementId"),
                Map.of("reached", new EnumOf(CONFIG_REACHED))),
        "instance.version", new BranchRule("instance",
                Set.of("instanceId"), Set.of("placementId"),
                Map.of("state", new EnumOf(VERSION_STATE))),
        "instance.rollout", new BranchRule("placement",
                Set.of("instanceId", "placementId"), Set.of(),
                Map.of("applyId", new IsString(), "replicaId", new IsString(),
                        "step", new EnumOf(ROLLOUT_STEP))),
        // packageVersion is `{}` in the schema — required, any JSON type. Deliberately AnyType:
        // see contracts/events/README.md Known gap 4 (BE-D4 amendment territory).
        "catalog.package", new BranchRule("solution",
                Set.of("solution"), Set.of("instanceId", "placementId"),
                Map.of("solution", new IsString(), "packageVersion", new AnyType())),
        // NOTE: unlike config-state and version, this branch does NOT forbid placementId.
        // That is the schema's asymmetry, reproduced as-is (BE-D4 amendment territory).
        "metrics.quarantine", new BranchRule("instance",
                Set.of("instanceId"), Set.of(),
                Map.of("count", new IntegerAtLeast(1))));

/** The branch keys — the Java side of the envelope's {@code event} enum. Package-private. */
static Set<String> handledEvents() {
    return BRANCHES.keySet();
}
```

Replace the body of `validate(EventEnvelope e)` (same signature, same javadoc) with:

```java
public static void validate(EventEnvelope e) {
    BranchRule rule = BRANCHES.get(e.event());
    if (rule == null) {
        throw new IllegalArgumentException(
                "unknown event '" + e.event() + "' — not one of the six v1 notify events");
    }
    entity(e, rule.requiredEntity(), rule.forbiddenEntity(), rule.kind());
    payload(e, rule.payload().keySet());
    for (String key : ordered(rule.payload().keySet())) {
        FieldRule fr = rule.payload().get(key);
        if (fr instanceof EnumOf en) {
            enumValue(e, key, en.members());
        } else if (fr instanceof IsString) {
            stringValue(e, key);
        } else if (fr instanceof IntegerAtLeast m) {
            integerAtLeast(e, key, m.min());
        }
        // AnyType: presence already asserted by payload(...); the hole stays open on purpose.
    }
}
```

(`instanceof` patterns are Java 16+; do **not** use `switch` pattern matching — it is preview in Java 17.)

- [ ] **Step 3: Prove the refactor changed no behavior**

```bash
mvn -f backend/pom.xml -pl mesh-events test
```

Expected: PASS — all existing tests, message expectations included, stay green. If any message expectation fails, the refactor changed behavior: fix the refactor, never the test.

- [ ] **Step 4: Commit the refactor**

```bash
git add backend/mesh-events/src/main/java/com/solace/sm/events/EventEnvelope.java \
  backend/mesh-events/src/main/java/com/solace/sm/events/NotifyEventShapes.java
git commit -m "Drive NotifyEventShapes from a declarative branch table"
```

- [ ] **Step 5: Write `SchemaMirrorLockTest` — complete, no helpers left to invent**

```java
package com.solace.sm.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.events.contracttest.ContractArtifacts;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * BE-D10's lock: the schema files and the Java mirror must be EQUAL — both directions. A schema
 * edit without the matching Java edit fails here; a Java edit without the schema edit fails here.
 * Everything is set-equality, never containment (containment lets one side grow silently).
 *
 * Deliberately NOT checked here: format semantics (EnvelopeGoldenTest round-trips real bytes),
 * topic derivation (NotifyTopicsTest owns the manifest bidirectionally), and the null-on-wire
 * asymmetry around packageVersion (pinned as AnyType below, adjudicated by the BE-D4 amendment).
 */
class SchemaMirrorLockTest {

    private static final JsonNode ENVELOPE =
            ContractArtifacts.json(ContractArtifacts.dir("events").resolve("envelope.schema.json"));
    private static final JsonNode NOTIFY =
            ContractArtifacts.json(ContractArtifacts.dir("events").resolve("notify-events.schema.json"));

    // ---- envelope ----------------------------------------------------------------------------

    @Test
    void envelopeRequiredEqualsPropertiesAndMatchesTheRecord() {
        // Derived from the record, never restated as a literal list — a literal here is a third
        // mirror: add traceId to EventEnvelope, forget the schema, and a literal lock stays green
        // while a real producer emits a schema-invalid top-level property.
        Set<String> components = recordComponents(EventEnvelope.class);
        assertEquals(components, textSet(ENVELOPE.get("required")));
        assertEquals(components, fieldNames(ENVELOPE.get("properties")));
        assertTrue(ENVELOPE.get("additionalProperties").isBoolean()
                        && !ENVELOPE.get("additionalProperties").asBoolean(),
                "the envelope must stay additionalProperties: false");
    }

    @Test
    void producerEnumEqualsTheJavaConstant() {
        assertEquals(sorted(EventEnvelope.PRODUCERS),
                textSet(ENVELOPE.at("/properties/producer/enum")));
    }

    @Test
    void eventEnumEqualsTheJavaBranchTable() {
        assertEquals(sorted(NotifyEventShapes.handledEvents()),
                textSet(ENVELOPE.at("/properties/event/enum")));
    }

    @Test
    void entityShapeMatchesEntityRefAndTheKindsTheTableUses() {
        JsonNode entity = ENVELOPE.at("/properties/entity");
        // 'kind' is the schema's requiredness choice, not derivable from the record (all EntityRef
        // components are nullable Java-side) — the one deliberate literal here, pinned:
        assertEquals(set("kind"), textSet(entity.get("required")));
        assertEquals(recordComponents(EntityRef.class), fieldNames(entity.get("properties")));
        assertTrue(!entity.get("additionalProperties").asBoolean(),
                "entity must stay additionalProperties: false");
        Set<String> kindsInTable = new TreeSet<>();
        for (NotifyEventShapes.BranchRule r : NotifyEventShapes.BRANCHES.values()) {
            kindsInTable.add(r.kind());
        }
        assertEquals(kindsInTable, textSet(entity.at("/properties/kind/enum")));
    }

    @Test
    void tenantAndStateVersionBoundsMatchTheJavaChecks() {
        // EventEnvelope's compact constructor: stateVersion >= 0 <-> minimum 0; tenant non-blank
        // <-> minLength 1 PLUS the "\S" pattern that Task 4's reviewed contract change adds (as of
        // this task the schema still says only minLength: 1, which "   " satisfies while Java's
        // isBlank() refuses it — that drift is closed in Task 4, which also extends this test with
        // the pattern pin). The Java side is code, not data, so the schema keywords are pinned
        // here: relaxing or tightening either is a contract change and must turn this red.
        assertEquals("string", ENVELOPE.at("/properties/tenant/type").asText());
        assertEquals(1, ENVELOPE.at("/properties/tenant/minLength").asInt());
        assertEquals("integer", ENVELOPE.at("/properties/stateVersion/type").asText());
        assertEquals(0, ENVELOPE.at("/properties/stateVersion/minimum").asInt());
        assertEquals("object", ENVELOPE.at("/properties/payload/type").asText());
    }

    // ---- the six branches ---------------------------------------------------------------------

    @Test
    void everyBranchMatchesTheJavaTableExactly() {
        Map<String, JsonNode> schemaBranches = branchesByEvent();
        assertEquals(sorted(NotifyEventShapes.handledEvents()),
                new TreeSet<>(schemaBranches.keySet()),
                "schema branches and Java branch table must name the same events");

        for (Map.Entry<String, JsonNode> e : schemaBranches.entrySet()) {
            String event = e.getKey();
            JsonNode branch = e.getValue();
            NotifyEventShapes.BranchRule java = NotifyEventShapes.BRANCHES.get(event);

            // entity: kind const, required ids (minus 'kind'), forbidden ids from BOTH not-shapes
            JsonNode entity = branch.at("/properties/entity");
            assertEquals(java.kind(), entity.at("/properties/kind/const").asText(),
                    event + ": entity.kind const");
            Set<String> required = textSet(entity.get("required"));
            assertTrue(required.remove("kind"), event + ": entity.required must include 'kind'");
            assertEquals(sorted(java.requiredEntity()), required, event + ": required entity ids");
            assertEquals(sorted(java.forbiddenEntity()), forbiddenEntityIds(entity),
                    event + ": forbidden entity ids");

            // payload: required == properties == the Java key set; additionalProperties: false
            JsonNode payload = branch.at("/properties/payload");
            assertTrue(!payload.get("additionalProperties").asBoolean(),
                    event + ": payload must stay additionalProperties: false");
            Set<String> javaKeys = sorted(java.payload().keySet());
            assertEquals(javaKeys, textSet(payload.get("required")), event + ": required payload");
            assertEquals(javaKeys, fieldNames(payload.get("properties")), event + ": payload keys");

            // every payload property's constraint == the Java FieldRule it routes to
            payload.get("properties").properties().forEach(prop -> assertFieldRule(event,
                    prop.getKey(), prop.getValue(), java.payload().get(prop.getKey())));
        }
    }

    /**
     * Classifies one schema payload property and asserts the Java rule is the same one. Fails on
     * any schema shape this method does not recognize — an unmapped shape must be a red, never a
     * silently unlocked field (L7).
     */
    private static void assertFieldRule(String event, String key, JsonNode schema,
            NotifyEventShapes.FieldRule java) {
        assertNotNull(java, event + ": no Java rule for payload." + key);
        String at = event + ": payload." + key;
        if (schema.has("enum")) {
            assertTrue(java instanceof NotifyEventShapes.EnumOf, at + " must be EnumOf, was " + java);
            assertEquals(textSet(schema.get("enum")),
                    sorted(((NotifyEventShapes.EnumOf) java).members()), at + " enum members");
        } else if ("string".equals(schema.path("type").asText())) {
            assertTrue(java instanceof NotifyEventShapes.IsString, at + " must be IsString, was " + java);
        } else if ("integer".equals(schema.path("type").asText()) && schema.has("minimum")) {
            assertTrue(java instanceof NotifyEventShapes.IntegerAtLeast,
                    at + " must be IntegerAtLeast, was " + java);
            assertEquals(schema.get("minimum").asLong(),
                    ((NotifyEventShapes.IntegerAtLeast) java).min(), at + " minimum");
        } else if (schema.isObject() && schema.isEmpty()) {
            // The pinned Known-gap-4 hole. Located by event const, never by oneOf index, so a
            // branch reorder cannot turn this assertion vacuous.
            assertTrue(java instanceof NotifyEventShapes.AnyType, at + " must stay the pinned {} "
                    + "any-type hole until the BE-D4 amendment types it — do not narrow either side");
        } else {
            fail(at + ": schema shape not recognized by the lock — extend assertFieldRule in the "
                    + "same commit that introduces the new shape: " + schema);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Every oneOf branch, keyed by its event const. Vacuity-guarded. */
    private static Map<String, JsonNode> branchesByEvent() {
        JsonNode oneOf = NOTIFY.get("oneOf");
        assertNotNull(oneOf, "notify-events.schema.json must dispatch through oneOf");
        assertEquals(6, oneOf.size(), "the v1 notify catalog must have exactly six branches");
        Map<String, JsonNode> byEvent = new LinkedHashMap<>();
        for (JsonNode branch : oneOf) {
            JsonNode constNode = branch.at("/properties/event/const");
            assertFalse(constNode.isMissingNode() || constNode.asText().isBlank(),
                    "every branch must carry an event const: " + branch);
            byEvent.put(constNode.asText(), branch);
        }
        assertEquals(6, byEvent.size(), "duplicate event consts across branches");
        return byEvent;
    }

    /** Both not-shapes the schema uses: not.required and not.anyOf[].required. */
    private static Set<String> forbiddenEntityIds(JsonNode entity) {
        Set<String> ids = new TreeSet<>();
        JsonNode not = entity.get("not");
        if (not == null) {
            return ids; // explicitly parsed as empty — valid (health, rollout, quarantine)
        }
        if (not.has("required")) {
            ids.addAll(textSet(not.get("required")));
        } else if (not.has("anyOf")) {
            for (JsonNode child : not.get("anyOf")) {
                ids.addAll(textSet(child.get("required")));
            }
        } else {
            fail("entity 'not' uses a shape the lock does not parse: " + not);
        }
        assertFalse(ids.isEmpty(), "a present 'not' must forbid at least one id: " + not);
        return ids;
    }

    private static Set<String> set(String... members) {
        return new TreeSet<>(Set.of(members));
    }

    /** The record's component names — the Java wire shape, read reflectively so it is never
     *  restated as a test literal. Vacuity-guarded: a non-record or empty record is a red. */
    private static Set<String> recordComponents(Class<? extends Record> record) {
        java.lang.reflect.RecordComponent[] components = record.getRecordComponents();
        assertNotNull(components, record + " is not a record");
        assertTrue(components.length > 0, record + " has no components");
        Set<String> out = new TreeSet<>();
        for (java.lang.reflect.RecordComponent c : components) {
            out.add(c.getName());
        }
        return out;
    }

    private static Set<String> sorted(java.util.Collection<String> c) {
        return new TreeSet<>(c);
    }

    private static Set<String> textSet(JsonNode array) {
        assertNotNull(array, "expected a JSON array, found nothing");
        assertTrue(array.isArray() && !array.isEmpty(), "expected a non-empty array: " + array);
        Set<String> out = new TreeSet<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static Set<String> fieldNames(JsonNode object) {
        assertNotNull(object, "expected a JSON object, found nothing");
        Set<String> out = new TreeSet<>();
        object.fieldNames().forEachRemaining(out::add);
        assertFalse(out.isEmpty(), "expected a non-empty object");
        return out;
    }
}
```

(If the Jackson version in the Boot BOM lacks `JsonNode.properties()`, use `payload.get("properties").fields()` with an iterator loop — same assertions.)

- [ ] **Step 6: Run the lock against the real contracts**

```bash
mvn -f backend/pom.xml -pl mesh-events test
```

Expected: PASS.

- [ ] **Step 7: Prove it fires — one planted divergence per assertion group**

Run four demonstrations, each against a fresh mutated copy, using the resolver override. The mutation script (uses only stdlib; run from the repo root):

```bash
demo() {
  MUT="$(mktemp -d)"
  cp -R contracts/events "$MUT/events"
  python3 - "$MUT/events/notify-events.schema.json" "$MUT/events/envelope.schema.json" "$1" <<'PY'
import json, sys
notify_path, envelope_path, mode = sys.argv[1], sys.argv[2], sys.argv[3]
notify = json.load(open(notify_path))
envelope = json.load(open(envelope_path))
def branch(event):
    return next(b for b in notify["oneOf"]
                if b["properties"]["event"]["const"] == event)
if mode == "enum":       # group: enum members
    branch("instance.health")["properties"]["payload"]["properties"]["state"]["enum"].remove("bootstrap")
elif mode == "scoping":  # group: entity scoping
    branch("instance.health")["properties"]["entity"]["required"].remove("placementId")
elif mode == "bound":    # group: primitive types and bounds
    branch("metrics.quarantine")["properties"]["payload"]["properties"]["count"]["minimum"] = 2
elif mode == "pin":      # group: the packageVersion {} pin
    branch("catalog.package")["properties"]["payload"]["properties"]["packageVersion"] = {"type": "string"}
json.dump(notify, open(notify_path, "w"), indent=2)
json.dump(envelope, open(envelope_path, "w"), indent=2)
PY
  # The demo must FAIL to succeed: a green build against a mutated schema means the lock did not
  # fire, and piping mvn through tail would hide that (the pipeline exits with tail's status).
  LOG="$MUT/mvn.log"
  if mvn -f backend/pom.xml -pl mesh-events test -Dsm.contracts.root="$MUT" >"$LOG" 2>&1; then
    echo "DEMO FAILED ($1): BUILD SUCCESS against a mutated schema — the lock did not fire"
    tail -30 "$LOG"
    return 1
  fi
  if ! grep -q -E '(FAILURE|ERROR)!.*SchemaMirrorLockTest' "$LOG"; then
    echo "DEMO FAILED ($1): build red, but the failure is not attributed to SchemaMirrorLockTest"
    tail -30 "$LOG"
    return 1
  fi
  echo "DEMO OK ($1) — lock fired:"
  grep -m 4 -A 1 '<<< FAILURE!' "$LOG"
}
demo enum && demo scoping && demo bound && demo pin && echo 'ALL FOUR LOCK DEMOS FIRED'
```

Expected: the final line `ALL FOUR LOCK DEMOS FIRED` — each run asserted that Maven **failed** and that the failure is attributed to `SchemaMirrorLockTest` (name the failing assertion per run in the task report). Other suites may also redden on some mutations; that is fine as long as the lock is among the failures. A `DEMO FAILED` line means the lock has a hole — fix the lock, never the demo. Then re-run without the override:

```bash
mvn -f backend/pom.xml -pl mesh-events test
```

Expected: PASS.

- [ ] **Step 8: Commit the lock**

```bash
git add backend/mesh-events/src/test/java/com/solace/sm/events/SchemaMirrorLockTest.java
git commit -m "Lock the Java event mirror to the schema files"
```

---

### Task 3: Move the Publisher Seam to `mesh-events` and Add the Contract-Test Kit

**Files:**
- Create: `backend/mesh-events/src/main/java/com/solace/sm/events/publisher/EventPublisher.java`
- Create: `backend/mesh-events/src/test/java/com/solace/sm/events/contracttest/CapturingTransport.java`
- Create: `backend/mesh-events/src/test/java/com/solace/sm/events/contracttest/AbstractEventPublisherContractTest.java`
- Create: `backend/mesh-events/src/test/java/com/solace/sm/events/contracttest/PublisherContractKitSelfTest.java`
- Modify: `backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/JcsmpEventPublisher.java`
- Delete: `backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/EventPublisher.java`
- Modify: `backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/EventPublisherContractTest.java`

**Interfaces:**
- Produces: `com.solace.sm.events.publisher.EventPublisher` (design §5.2 — the shared, plane-neutral seam)
- Produces the kit: `CapturingTransport`, `AbstractEventPublisherContractTest` (both in `com.solace.sm.events.contracttest`, test-jar)
- Consumes: `NotifyTopics`, `NotifyEventShapes`, `EventJson`, `ContractArtifacts`
- `JcsmpEventPublisher`'s package-private seam constructor `JcsmpEventPublisher(BiConsumer<String,String>)` is **unchanged** — the concrete dp-core test lives in the same package and can reach it; `CapturingTransport implements BiConsumer<String,String>` slots straight in.

- [ ] **Step 1: Create the shared interface in `mesh-events`**

Move the interface, preserving the current `dp-core` `EventPublisher`'s full javadoc (the single-method rationale, the loud/quiet contract, the "implementations must call `NotifyEventShapes.validate`" clause). Only the package line changes:

```java
package com.solace.sm.events.publisher;

import com.solace.sm.events.EventEnvelope;

public interface EventPublisher {
    void publish(EventEnvelope event);
}
```

- [ ] **Step 2: Repoint `JcsmpEventPublisher` and delete the old interface**

In `JcsmpEventPublisher`, add `import com.solace.sm.events.publisher.EventPublisher;` (the class declaration `implements EventPublisher, AutoCloseable` is otherwise unchanged). Delete `backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/EventPublisher.java`. Confirm nothing else references the old type:

```bash
rg -n 'meshbridge\.EventPublisher' backend
```

Expected: no matches.

- [ ] **Step 3: Add `CapturingTransport`**

```java
package com.solace.sm.events.contracttest;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** Test transport: captures the one (topic, body) pair handed over, or throws on demand. */
public final class CapturingTransport implements BiConsumer<String, String> {

    private final AtomicReference<Map.Entry<String, String>> sent = new AtomicReference<>();
    private RuntimeException failure;

    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    @Override
    public void accept(String topic, String body) {
        if (failure != null) {
            throw failure;
        }
        sent.set(Map.entry(topic, body));
    }

    /** The captured (topic, body), or null if nothing reached the transport. */
    public Map.Entry<String, String> sent() {
        return sent.get();
    }
}
```

- [ ] **Step 4: Write `AbstractEventPublisherContractTest` by porting the generic tests**

Move these test methods from the current `dp-core` `EventPublisherContractTest` into the abstract kit, **logic unchanged** (they already run against contract artifacts, not dp-core internals):

- `serializesViaMeshEventsAndDerivesTopicFromNotifyTopics`
- `transportFailureIsLoggedAndDroppedNeverThrownAtTheCaller`
- `aSerializationFailureIsLoggedAndDroppedNotThrownAtTheCaller`
- `aShapeInvalidEventPropagatesAndNeverReachesTheTransport`
- `anUnaddressableEventPropagatesRatherThanBeingSilentlyDropped`
- `everyPayloadKeyThatSurvivedValidationAlsoSurvivesSerialization` (parameterized over the fixtures)
- `aNullValuedRequiredPayloadKeyIsRefusedAndNeverReachesTheTransport`

These two stay in dp-core (they exercise JCSMP internals, not the contract): `theJcsmpSendCatchCountsItsDropAndSwallowsTheException`, `theAsynchronousPublishErrorHandlerCountsItsDrop`.

Kit skeleton — the two abstract methods, the artifact plumbing, and the one check that must also be callable by the self-test:

```java
package com.solace.sm.events.contracttest;

// imports: junit assertions, JsonNode/ObjectMapper, EventEnvelope/EventJson,
// com.solace.sm.events.publisher.EventPublisher, java.nio.file.*, java.io.IOException,
// plus whatever the ported tests already import.

/**
 * The publisher contract (design §5.2): EVERY EventPublisher implementation must have a test
 * class extending this kit. A publisher-specific suite that only exercises its own happy path is
 * how a future implementation bypasses NotifyEventShapes.validate() while the existing suites
 * stay green. PublisherContractKitSelfTest proves this kit fails a non-validating publisher.
 */
public abstract class AbstractEventPublisherContractTest {

    protected static final Path FIXTURES = ContractArtifacts.dir("events/fixtures");
    protected static final Path NEGATIVES = ContractArtifacts.dir("events/negative");

    /** The implementation under test, wired to the given transport. */
    protected abstract EventPublisher publisher(CapturingTransport transport);

    /** The implementation's swallow counter (not on the interface by design). */
    protected abstract long droppedCount(EventPublisher publisher);

    /** The manifest's topic for one fixture — emitted-byte contract checks compare against it. */
    protected static String expectedTopicFor(String fixtureName) {
        return ContractArtifacts.json(FIXTURES.resolve("expected-topics.json"))
                .get(fixtureName).asText();
    }

    @Test
    void aShapeInvalidEventPropagatesAndNeverReachesTheTransport() throws IOException {
        CapturingTransport transport = new CapturingTransport();
        assertShapeInvalidEventNeverReachesTheTransport(publisher(transport), transport);
    }

    /**
     * Static and public so PublisherContractKitSelfTest can turn it on a deliberately
     * non-validating publisher and watch it fail — the kit's own prove-it-fires.
     */
    public static void assertShapeInvalidEventNeverReachesTheTransport(
            EventPublisher p, CapturingTransport transport) throws IOException {
        EventEnvelope bad = EventJson.reader()
                .readValue(Files.readString(NEGATIVES.resolve("health-without-placement.json")));
        IllegalArgumentException boom =
                assertThrows(IllegalArgumentException.class, () -> p.publish(bad));
        assertTrue(boom.getMessage().contains("entity.placementId is required"), boom.getMessage());
        assertNull(transport.sent(), "a shape-invalid event must never reach the transport");
    }

    // ... the six other ported @Test methods, using publisher(...)/droppedCount(...) in place of
    // the old `new JcsmpEventPublisher(transport)` / `p.droppedCount()` calls, and
    // expectedTopicFor(...) in place of any locally-parsed manifest lookup.
}
```

Porting rules: fixture/negative paths come from the kit's `FIXTURES`/`NEGATIVES`; every construction of the implementation goes through `publisher(transport)`; every drop-count read goes through `droppedCount(p)`; nothing in the kit may import anything from `com.solace.sm.dp..` (it lives in plane-neutral `mesh-events` — the existing `eventsNeverImportDp` ArchUnit rule enforces this for main scope; keep test scope clean too).

**Enforcement honesty (carried from design §5.2):** with one implementation, "every `EventPublisher` implementation extends the kit" is a **review gate**, not an executable check — `arch-tests` sees other modules' main classes but not their test classes, so a kit-inventory test cannot run today. The trigger is already named in the design: the PR adding a second implementation must also add an executable inventory check pairing each non-fixture implementation with a kit subclass. Nothing in this task pretends otherwise; the self-test below proves the kit *can* catch a bypass, not that every future implementation uses it.

- [ ] **Step 5: Make the dp-core test a concrete implementation**

`EventPublisherContractTest` keeps its name and package and becomes:

```java
package com.solace.sm.dp.meshbridge;

import com.solace.sm.events.contracttest.AbstractEventPublisherContractTest;
import com.solace.sm.events.contracttest.CapturingTransport;
import com.solace.sm.events.publisher.EventPublisher;

class EventPublisherContractTest extends AbstractEventPublisherContractTest {

    @Override
    protected EventPublisher publisher(CapturingTransport transport) {
        return new JcsmpEventPublisher(transport);
    }

    @Override
    protected long droppedCount(EventPublisher publisher) {
        return ((JcsmpEventPublisher) publisher).droppedCount();
    }

    // theJcsmpSendCatchCountsItsDropAndSwallowsTheException and
    // theAsynchronousPublishErrorHandlerCountsItsDrop stay here unchanged — they exercise
    // JcsmpTransport / DirectPublishEvents, which are this implementation's internals.
}
```

- [ ] **Step 6: Add the kit's prove-it-fires self-test**

```java
package com.solace.sm.events.contracttest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.NotifyTopics;
import com.solace.sm.events.publisher.EventPublisher;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * Proves the kit itself fires: a publisher that skips NotifyEventShapes.validate() must FAIL
 * AbstractEventPublisherContractTest's shape check. Without this, the kit is decoration (L7).
 * Deliberately NOT a subclass of the kit — subclassing would run every kit test against the
 * bad publisher and redden the build; only the bypass check is exercised, inverted.
 */
class PublisherContractKitSelfTest {

    /** Derives the topic but never validates shape — the exact bypass the kit exists to catch. */
    private static final class NonValidatingPublisher implements EventPublisher {
        private final CapturingTransport transport;

        NonValidatingPublisher(CapturingTransport transport) {
            this.transport = transport;
        }

        @Override
        public void publish(EventEnvelope event) {
            transport.accept(NotifyTopics.topicFor(event), EventJson.write(event));
        }
    }

    @Test
    void theKitFailsAPublisherThatSkipsShapeValidation() {
        CapturingTransport transport = new CapturingTransport();
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> AbstractEventPublisherContractTest
                        .assertShapeInvalidEventNeverReachesTheTransport(
                                new NonValidatingPublisher(transport), transport));
        assertTrue(failure.getMessage() != null && !failure.getMessage().isBlank(),
                "the kit must name what the bad publisher got wrong");
    }
}
```

Note the negative used by the check (`health-without-placement.json`) is topic-derivable — `NotifyTopics.topicFor` needs only `instanceId` — so the bypass genuinely reaches the transport and the failure comes from the kit's assertion, not from an accidental earlier throw. If a JUnit version difference makes the inner failure surface as `AssertionError` rather than `AssertionFailedError`, catch `AssertionError` — the point is that the kit's own assertion fired.

- [ ] **Step 7: Run tests**

```bash
mvn -f backend/pom.xml -pl mesh-events,dp-core -am test
```

Expected: PASS. The kit's tests run once in dp-core (via the concrete subclass) and the self-test runs in mesh-events. Total test count must not shrink: every ported method still executes.

- [ ] **Step 8: Commit**

```bash
git add backend/mesh-events/src/main/java/com/solace/sm/events/publisher \
  backend/mesh-events/src/test/java/com/solace/sm/events/contracttest \
  backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge \
  backend/dp-core/src/test/java/com/solace/sm/dp/meshbridge/EventPublisherContractTest.java
git add -u backend/dp-core/src/main/java/com/solace/sm/dp/meshbridge/EventPublisher.java
git commit -m "Move the publisher seam to mesh-events with a mandatory contract-test kit"
```

---

### Task 4: Harden the Tenant Rule and Add Shared Enum, Bound, and Blank-Tenant Negatives

**Files:**
- Modify: `contracts/events/envelope.schema.json`
- Create: `contracts/events/negative/health-bad-state.json`
- Create: `contracts/events/negative/quarantine-bad-count.json`
- Create: `contracts/events/negative/blank-tenant.json`
- Modify: `contracts/events/validate-events.py`
- Modify: `contracts/events/README.md`
- Modify: `backend/mesh-events/src/test/java/com/solace/sm/events/NotifyEventShapesTest.java`
- Modify: `backend/mesh-events/src/test/java/com/solace/sm/events/SchemaMirrorLockTest.java`

**Interfaces:**
- Produces: the tenant `pattern` hardening plus three language-neutral negative fixtures — the fixture-side backstop for Task 2's type/bound locks and the closure of the blank-tenant drift.
- Consumes: Python `NEGATIVE_REASON` (tuple-valued: `(where, [substrings])`) and Java `NEGATIVE_REASON` (`Map<String, String>` of message substrings).

This is a **single-writer `contracts/` change**: run the events README's change checklist, and run `./contracts/run-checks.sh` before and after. (`EventPublisherContractTest` is *not* touched — it references specific negative files by name and never globs the directory.)

**Why the schema edit (R11 finding):** `EventEnvelope` refuses a blank tenant (`isBlank()`), but the schema said only `"minLength": 1` — so `"tenant": "   "` validated in Python and was refused by Java. Tenant is an ACL-bearing topic level (the mesh half of `I5`), so the schema hardens to match Java, never the reverse. `"pattern": "\\S"` (unanchored: at least one non-whitespace character) is the schema half of `isBlank()`; the two agree on ASCII and common Unicode whitespace, and the residual exotic-codepoint difference between the engines' whitespace classes is accepted and documented in the design (§3).

- [ ] **Step 1: Harden the tenant rule in the envelope schema**

In `contracts/events/envelope.schema.json`, change the `tenant` property to:

```json
    "tenant": { "type": "string", "minLength": 1, "pattern": "\\S" },
```

Every committed fixture has `"tenant": "acme"`, so nothing existing goes red from this alone.

- [ ] **Step 2: Add the blank-tenant negative**

Create `contracts/events/negative/blank-tenant.json` — a health event valid in every respect except the whitespace-only tenant:

```json
{
  "eventId": "3f1c9a52-0000-4000-8000-000000000103",
  "occurredAtUtc": "2026-08-27T10:00:00Z",
  "producer": "dp-core",
  "tenant": "   ",
  "entity": {
    "kind": "placement",
    "instanceId": "cache-prod-01",
    "placementId": "pl-eu-1"
  },
  "stateVersion": 41,
  "event": "instance.health",
  "payload": {
    "state": "GREEN"
  }
}
```

- [ ] **Step 3: Add the enum negative**

Create `contracts/events/negative/health-bad-state.json` — valid in every respect except the enum member (`YELLOW` is the exact wrong value R5 finding 6 called out as never having existed in the vocabulary):

```json
{
  "eventId": "3f1c9a52-0000-4000-8000-000000000101",
  "occurredAtUtc": "2026-08-27T10:00:00Z",
  "producer": "dp-core",
  "tenant": "acme",
  "entity": {
    "kind": "placement",
    "instanceId": "cache-prod-01",
    "placementId": "pl-eu-1"
  },
  "stateVersion": 41,
  "event": "instance.health",
  "payload": {
    "state": "YELLOW"
  }
}
```

- [ ] **Step 4: Add the bound negative**

Create `contracts/events/negative/quarantine-bad-count.json` — valid except `count` below the schema's `minimum: 1`:

```json
{
  "eventId": "3f1c9a52-0000-4000-8000-000000000102",
  "occurredAtUtc": "2026-08-27T10:00:00Z",
  "producer": "dp-core",
  "tenant": "acme",
  "entity": {
    "kind": "instance",
    "instanceId": "cache-prod-01"
  },
  "stateVersion": 41,
  "event": "metrics.quarantine",
  "payload": {
    "count": 0
  }
}
```

- [ ] **Step 5: Confirm both suites go red for the right reason — unmapped negatives**

```bash
./contracts/run-checks.sh --strict
mvn -f backend/pom.xml -pl mesh-events test
```

Expected: check 10 FAILS with `no declared rejection reason — add one to NEGATIVE_REASON` for each of the three new files, and `NotifyEventShapesTest` FAILS with its own `has no declared expected reason` guard. Both unmapped-file guards firing **is the point** — record the messages in the task report.

- [ ] **Step 6: Map the reasons in Python**

`NEGATIVE_REASON` in `validate-events.py` is a dict of `filename -> (where, [substrings])`, where `where` is the joined error path and the substrings must all appear in one error message **from the branch that should have accepted the document**. Add:

```python
    # the schema half of EventEnvelope's isBlank() guard — tenant is an ACL-bearing topic level (I5)
    "blank-tenant.json": ("tenant", ["does not match"]),
    # UI-D14: a member outside the closed health vocabulary must be refused by the enum itself
    "health-bad-state.json": ("payload/state", ["is not one of", "'YELLOW'"]),
    # the schema minimum on quarantine's count (the bound the Java lock also pins)
    "quarantine-bad-count.json": ("payload/count", ["less than the minimum of 1"]),
```

The `where` for the payload two is the *field* path (`payload/state`, `payload/count`), not `payload` — enum and minimum errors attach to the property; the pattern error attaches to `tenant`. If a substring does not match your jsonschema version's wording, check 2's failure output prints every error from the claiming branch with its path — copy the exact wording from there; never loosen to a bare field name, which any error on that field would match.

- [ ] **Step 7: Map the reasons in Java, including the construction-rejection shape**

The blank tenant is refused by `EventEnvelope`'s compact constructor **during deserialization** (Jackson wraps the `IllegalArgumentException` in a `ValueInstantiationException`), so it never reaches `NotifyEventShapes.validate()`. Extend `NotifyEventShapesTest`:

Add to `NEGATIVE_REASON`:

```java
// the envelope half: EventEnvelope's compact constructor, reached via Jackson during read
"blank-tenant.json", "tenant must be present and non-blank",
// UI-D14: a member outside the closed health vocabulary
"health-bad-state.json", "payload.state must be one of",
// the schema minimum on quarantine's count — the bound SchemaMirrorLockTest pins schema-side
"quarantine-bad-count.json", "payload.count must be an integer >= 1",
```

In the parameterized negative test, wrap the `read(doc)` call so a construction-time refusal is a valid rejection shape — but only for its declared reason:

```java
EventEnvelope e;
try {
    e = read(doc);
} catch (IOException constructionFailure) {
    // Envelope-level negatives are refused by EventEnvelope's compact constructor during
    // deserialization — Jackson wraps the IllegalArgumentException. That is a valid rejection
    // shape, but only for the declared reason: assert it appears in the cause chain, then stop —
    // there is no envelope left to validate.
    assertTrue(causeMessages(constructionFailure).contains(reason),
            "negative/" + name + " was refused at construction, but not for its declared reason '"
                    + reason + "'. Causes: " + causeMessages(constructionFailure));
    return;
}
```

with the helper:

```java
private static String causeMessages(Throwable t) {
    StringBuilder all = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
        all.append(c.getMessage()).append('\n');
    }
    return all.toString();
}
```

The existing flow (validate must throw with the declared reason; `ACCEPTED_WIRE_LEVEL_DEFECT_ONLY` special case) is unchanged for every document that constructs.

- [ ] **Step 8: Pin the pattern in the lock**

In `SchemaMirrorLockTest.tenantAndStateVersionBoundsMatchTheJavaChecks`, add after the `minLength` assertion:

```java
assertEquals("\\S", ENVELOPE.at("/properties/tenant/pattern").asText(),
        "tenant must require a non-whitespace character — the schema half of EventEnvelope's"
                + " isBlank() guard; removing it reopens the blank-tenant drift (R11)");
```

and update that test's leading comment: the drift note is now history — the schema carries the pattern and this pin keeps it there.

- [ ] **Step 9: Extend the events README change checklist**

In `contracts/events/README.md`'s "Changing anything here" section, add one sentence: when a branch adds or changes a closed enum, a primitive type, or a numeric/string bound, at least one `negative/*.json` must exercise that constraint class — `SchemaMirrorLockTest` locks the *declarations* equal, the negative proves the *rejection* actually fires in both implementations.

- [ ] **Step 10: Run checks green**

```bash
./contracts/run-checks.sh --strict
mvn -f backend/pom.xml -pl mesh-events,dp-core -am test
```

Expected: both PASS; check 10 prints `PASS (rejected at tenant ...)`, `PASS (rejected at payload/state ...)` and `PASS (rejected at payload/count ...)` lines; the Java parameterized negative suite grows by three cases.

- [ ] **Step 11: Commit**

```bash
git add contracts/events/envelope.schema.json \
  contracts/events/negative/blank-tenant.json \
  contracts/events/negative/health-bad-state.json \
  contracts/events/negative/quarantine-bad-count.json \
  contracts/events/validate-events.py contracts/events/README.md \
  backend/mesh-events/src/test/java/com/solace/sm/events/NotifyEventShapesTest.java \
  backend/mesh-events/src/test/java/com/solace/sm/events/SchemaMirrorLockTest.java
git commit -m "Close the blank-tenant drift and add enum, bound, and tenant negatives"
```


---

### Task 5: Add Contract-Consumption ArchUnit Rules

**Files:**
- Modify: `backend/arch-tests/src/test/java/com/solace/sm/arch/PlaneBoundaryTest.java`
- Create: `backend/arch-tests/src/test/java/com/solace/sm/gateway/archfixture/BadGatewayEventsClass.java`
- Create: `backend/arch-tests/src/test/java/com/solace/sm/dp/meshbridge/archfixture/BadMeshbridgeMapperClass.java`
- Create: `backend/arch-tests/src/test/java/com/solace/sm/dp/meshbridge/archfixture/BadMeshbridgeJsonMapperClass.java`

**Interfaces:**
- Produces `@ArchTest` rules `gatewayNeverImportsEvents` and `meshbridgeHoldsNoOwnMapper` in the existing `PlaneBoundaryTest` (which already runs every `@ArchTest` rule against production classes with `ExcludeArchFixtures` filtering the fixtures out, and proves each rule on a fixture-inclusive import).
- Consumes: the existing `withPlantedViolations()` importer and the `..archfixture..` fixture convention (new fixtures are automatically excluded from the real run).

**Rule shape decision (deviation from the earlier draft, on purpose):** the mapper rule is a *dependency* rule, not a constructor/method-call rule. `noClasses()...dependOnClassesThat().areAssignableTo(ObjectMapper.class)` catches every `ObjectMapper` constructor (any arity), `JsonMapper.builder()` (a dependency on `JsonMapper`, which extends `ObjectMapper`), and a *held* mapper of either type — which is exactly the module matrix's prohibition ("hold a second `ObjectMapper` in `meshbridge`"). It also intentionally bars `EventJson.mapper()` copies inside `..dp.meshbridge..`: publishers there emit through `EventJson.write()`/`writer()` and read through `reader()`; a mapper copy held in the publisher package is a second mapper. This uses only ArchUnit API already imported by the file's style (`noClasses`, `dependOnClassesThat`), avoiding the member-level `callConstructor`/`callMethod` API entirely.

- [ ] **Step 1: Add planted violations**

`BadGatewayEventsClass.java`:

```java
package com.solace.sm.gateway.archfixture;

import com.solace.sm.events.EventEnvelope;

/** Planted violation: the gateway is a byte-level proxy (BE-D7) and must never parse events. */
public final class BadGatewayEventsClass {
    EventEnvelope event;
}
```

`BadMeshbridgeMapperClass.java`:

```java
package com.solace.sm.dp.meshbridge.archfixture;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Planted violation: a second ObjectMapper in the publisher package (plan 001 invariant). */
public final class BadMeshbridgeMapperClass {
    ObjectMapper mapper = new ObjectMapper();
}
```

`BadMeshbridgeJsonMapperClass.java`:

```java
package com.solace.sm.dp.meshbridge.archfixture;

import com.fasterxml.jackson.databind.json.JsonMapper;

/** Planted violation: the builder path to a second mapper. */
public final class BadMeshbridgeJsonMapperClass {
    Object mapper = JsonMapper.builder().build();
}
```

(`arch-tests` already has jackson-databind on its classpath transitively through `mesh-events`.)

- [ ] **Step 2: Add the rules to `PlaneBoundaryTest`**

Add the import `com.fasterxml.jackson.databind.ObjectMapper` and, beside the existing rules (same `@ArchTest` style):

```java
// The /mesh proxy is byte-level (BE-D7): a gateway that parses events would be a third
// implementation of the event contract with no goldens watching it.
@ArchTest
static final ArchRule gatewayNeverImportsEvents =
        noClasses().that().resideInAPackage("com.solace.sm.gateway..")
                .should().dependOnClassesThat().resideInAPackage("com.solace.sm.events..");

// Plan 001's "one EventJson mapper, never a second" invariant, as a rule. Dependency-shaped on
// purpose: it catches every ObjectMapper constructor, JsonMapper.builder() (JsonMapper extends
// ObjectMapper), and a held mapper of either type — including an EventJson.mapper() copy held
// here, which is deliberate: publishers emit via EventJson.write()/writer() and read via
// reader(), all of which are mapper-free signatures. Scoped to the publisher package only;
// REST layers elsewhere legitimately own mappers.
@ArchTest
static final ArchRule meshbridgeHoldsNoOwnMapper =
        noClasses().that().resideInAPackage("com.solace.sm.dp.meshbridge..")
                .should().dependOnClassesThat().areAssignableTo(ObjectMapper.class);
```

- [ ] **Step 3: Add prove-it-fires tests**

Follow the file's existing pattern exactly (assertThrows + message-contains-the-fixture):

```java
@Test
void gatewayEventsRuleCatchesAPlantedViolation() {
    AssertionError failure = assertThrows(AssertionError.class,
            () -> gatewayNeverImportsEvents.check(withPlantedViolations()));
    assertTrue(failure.getMessage().contains("com.solace.sm.gateway.archfixture.BadGatewayEventsClass"),
            "gatewayNeverImportsEvents must fail because of the planted fixture. Was: "
                    + failure.getMessage());
}

@Test
void meshbridgeMapperRuleCatchesBothPlantedViolations() {
    AssertionError failure = assertThrows(AssertionError.class,
            () -> meshbridgeHoldsNoOwnMapper.check(withPlantedViolations()));
    assertTrue(failure.getMessage().contains("BadMeshbridgeMapperClass"),
            "the constructed-mapper fixture must trip the rule. Was: " + failure.getMessage());
    assertTrue(failure.getMessage().contains("BadMeshbridgeJsonMapperClass"),
            "the builder-path fixture must trip the rule too. Was: " + failure.getMessage());
}
```

- [ ] **Step 4: Run ArchUnit tests**

```bash
mvn -f backend/pom.xml -pl arch-tests -am test
```

Expected: PASS — the two new `@ArchTest` rules green against production classes (fixtures excluded by `ExcludeArchFixtures`), both planted proofs firing. The existing five rules and their proofs stay green — in particular, the new gateway fixture must **not** trip `gatewayUsesApisOnly` (it depends on `events`, not on a plane).

- [ ] **Step 5: Commit**

```bash
git add backend/arch-tests/src/test/java/com/solace/sm/arch/PlaneBoundaryTest.java \
  backend/arch-tests/src/test/java/com/solace/sm/gateway/archfixture/BadGatewayEventsClass.java \
  backend/arch-tests/src/test/java/com/solace/sm/dp/meshbridge/archfixture
git commit -m "Enforce contract-consumption boundaries with ArchUnit rules"
```

---

### Task 6: Final Verification and Results

**Files:**
- Modify: `docs/plans/backend/002-contract-consumption-locks.md` (append the Results section)
- Modify: `docs/design/common/contracts/backend-consumption.md` **only** if implementation discovered a genuine mismatch in its §11 inventory (record what and why in the Results section).

- [ ] **Step 1: Stale-wording scan**

```bash
rg -n 'emitted-byte schema validation|ContractFixtures|No backend code exists|not yet recorded|reserved `BE-D\*|reserved `AG-D\*' \
  docs/design backend
```

Expected — exactly these acceptable hits and nothing else: `docs/design/common/contracts/backend-consumption.md` mentions `ContractFixtures` twice (§4.1 and §11), describing the class this plan deleted; that is the design's own migration instruction and stays. Any hit in `backend/` (the class or references to it surviving Task 1) or any other pattern hit is a defect — fix it before proceeding.

- [ ] **Step 2: Contract checks**

```bash
./contracts/run-checks.sh --strict
```

Expected: `== RESULT: ALL CHECKS PASSED`, with check 10 listing the three Task 4 negatives among its PASS lines.

- [ ] **Step 3: Backend verification**

```bash
mvn -f backend/pom.xml verify
```

Expected: `BUILD SUCCESS`; the failsafe-run `JcsmpEventPublisherIT` reported as skipped unless `-Dbroker.host` is supplied (report it as skipped-with-condition, never as passing).

- [ ] **Step 4: Optional live-broker IT**

```bash
mvn -f backend/pom.xml -pl dp-core -am verify -Dbroker.host=localhost
```

Only if the G1 spike's compose stack is already running. If it is not, do **not** start it; report the command as not run and why.

- [ ] **Step 5: Append the Results section to this plan**

```markdown
## Results

- Contract checks: `<command>` -> `<result>`
- Backend verify: `<command>` -> `<result>` (IT skipped: <condition>)
- Live broker IT: `<command>` -> `<result or "not run — broker unavailable">`
- Final Java test count: `<count from Maven output>` (baseline was 126)
- Lock prove-it-fires: <the four failing assertions from Task 2 Step 7, one line each>
- Kit prove-it-fires: PublisherContractKitSelfTest green
- Final commit range: `<first task commit>..<last task commit>`
- Deviations from the plan: <list, or "none">
```

- [ ] **Step 6: Commit**

```bash
git add docs/plans/backend/002-contract-consumption-locks.md docs/design/common/contracts/backend-consumption.md
git commit -m "Record contract-consumption lock verification results"
```

---

## Self-Review Checklist

- [x] Every `backend-consumption.md` §11 item maps to a task: item 1 → Task 1 (all **three** resolver copies, IT included) · item 2 → Task 2 Steps 1–2 (as the table refactor the lock requires) · item 3 → Task 2 Steps 5–7 · item 4 → Task 3 · item 5 → Task 4 · item 6 → Task 5 · item 7 → shipped with the design · item 8 → Task 6.
- [x] The lock compares the schema to **data Java actually carries** — `BRANCHES` (which `validate()` consumes) and `getRecordComponents()` for the envelope/entity field lists — never to literals local to the test. The only schema-side literals are `entity.required == ["kind"]` and the tenant/stateVersion bound-and-pattern pins, each commented as deliberate.
- [x] The lock covers: envelope required/properties/additionalProperties, producer enum, event enum (vs the Java table), entity kinds (vs the Java table), tenant type+minLength, stateVersion type+minimum, per-branch required/allowed payload keys, payload additionalProperties, per-field enums/types/minimums, both `not:` shapes, and the `packageVersion: {}` pin located by event const (never by `oneOf` index).
- [x] Prove-it-fires: four scripted schema mutations for the lock's assertion groups — each demo **asserts Maven failed and attributes the failure to `SchemaMirrorLockTest`** (a piped `mvn | tail` would exit with tail's status and let a dead lock "pass" the demo); a non-validating publisher for the kit; planted ArchUnit fixtures for both new rules; both unmapped-negative guards demonstrated red in Task 4 Step 5.
- [x] The blank-tenant drift (R11) is closed schema-side (`"pattern": "\\S"`, Task 4 Step 1), backstopped by a shared negative in both reason maps, handled in Java's negative suite as a construction-time rejection matched to its declared reason, and pinned in the lock (Task 4 Step 8).
- [x] The kit mandate is stated honestly: a review gate until a second implementation exists, with the executable inventory check assigned to that PR (design §5.2).
- [x] Python `NEGATIVE_REASON` additions use the file's actual `(where, [substrings])` tuple shape with field-level paths.
- [x] No task implements auth, tickets, RBAC, package-upload validation, websocket proxying, runtime schema validation, metrics wiring, or real notify emission.
- [x] Java-17-safe: `instanceof` patterns only, no `switch` pattern matching; `JcsmpEventPublisher`'s package-private seam constructor is reached only from its own package.
- [x] Shell snippets are zsh-safe: backticks appear only inside single quotes; no bare `=` words; the mutation script is stdlib Python via heredoc.
- [x] Sub-agent instructions require Claude Opus 5.
- [x] Completion gates: `./contracts/run-checks.sh --strict` and `mvn -f backend/pom.xml verify`, test count ≥ 126 and growing.

---

## Results

**Status: EXECUTED** (2026-08-28, branch `contracts-backend-consumption`). All six tasks implemented and
independently reviewed. This section is the completion record.

- Contract checks: `./contracts/run-checks.sh --strict` -> `== RESULT: ALL CHECKS PASSED`. Check 10 now
  rejects **9** negatives — the three Task 4 additions:
  `PASS (rejected at tenant by the constraint under test) negative/blank-tenant.json` ·
  `PASS (rejected at payload/state by the constraint under test) negative/health-bad-state.json` ·
  `PASS (rejected at payload/count by the constraint under test) negative/quarantine-bad-count.json` —
  plus the two R12 additions:
  `PASS (rejected at payload/count by the constraint under test) negative/quarantine-overflow-count.json` ·
  `PASS (rejected at stateVersion by the constraint under test) negative/stateversion-overflow.json`.
- Backend verify: `mvn -f backend/pom.xml clean verify` -> `BUILD SUCCESS` (8/8 modules).
  IT skipped-with-condition: failsafe reported
  `Tests run: 1, Failures: 0, Errors: 0, Skipped: 1 -- in JcsmpEventPublisherIT`, disabled by
  `@EnabledIfSystemProperty(named = "broker.host")`. Never counted as passing.
- Live broker IT: `mvn -f backend/pom.xml -pl dp-core -am verify -Dbroker.host=localhost` ->
  **`BUILD SUCCESS`, IT ran and passed** (`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`). The G1 spike
  stack was already running (`sm-spike-broker`, up 20h; VPN `sm-spike` and client-username `sm-internal`
  confirmed present via SEMP before running), so the optional step was executable without starting anything.
  JCSMP connected over `ws://localhost:8008` and the round trip asserted topic, body and byte equality
  against `contracts/events/fixtures/`.
- Stale-wording scan: `grep -rnE --exclude-dir=target '…' docs/design backend` -> only the two acceptable
  hits, both in `docs/design/common/contracts/backend-consumption.md` (§4.1 line 212, §11 line 453),
  describing the `EnvelopeGoldenTest.ContractFixtures` class this plan deleted. No hit in `backend/`, and no
  hit for any other pattern. No defect found; **no change to `backend-consumption.md` was needed** — its §11
  inventory matched what was implemented, item for item.
- `git diff --check` -> clean (no whitespace errors).
- Final Java test count: **149** (baseline 126) — `mesh-events` 120 · `dp-core` 14 (+1 failsafe IT) ·
  `arch-tests` 15. `mesh-events` breakdown: `NotifyEventShapesTest` 65 · `NotifyTopicsTest` 24 ·
  `EnvelopeGoldenTest` 17 · `SchemaMirrorLockTest` 8 · `ContractArtifactsTest` 5 ·
  `PublisherContractKitSelfTest` 1.
  (Historical: the six implementation tasks landed at 145; the final-review fix wave took it to 147 and
  the R12 wave below to 149.)
- Lock prove-it-fires (Task 2 Step 7 — four scripted schema mutations, each demo asserting Maven failed
  *and* attributing the failure to `SchemaMirrorLockTest`):
  - enum -> `instance.health: payload.state enum members`
  - scoping -> `instance.health: required entity ids`
  - bound -> `metrics.quarantine: payload.count minimum`
  - pin -> `catalog.package: payload.packageVersion must be IsString, was AnyType[]`
- Kit prove-it-fires: `PublisherContractKitSelfTest` green; the bypass was measured to reach the transport,
  proving the kit is not a no-op.
- Final commit range: `b107600..1ba992f` (nine commits) plus the R12 fix-wave commit below.
  - `b107600` Add shared contract artifact resolver for backend tests (Task 1)
  - `30eea8d` Drive NotifyEventShapes from a declarative branch table (Task 2)
  - `0cccf74` Lock the Java event mirror to the schema files (Task 2)
  - `7b207f9` Move the publisher seam to mesh-events with a mandatory contract-test kit (Task 3)
  - `075fa48` Close the blank-tenant drift and add enum, bound, and tenant negatives (Task 4)
  - `f02006e` Enforce contract-consumption boundaries with ArchUnit rules (Task 5)
  - `00df96b` Record contract-consumption lock verification results (Task 6)
  - `ae7c9ef` Close four proven schema-lock escape routes and sweep plan-002 wording
    (**review wave 1** — the whole-branch final review)
  - `1ba992f` Apply the re-review's three residual one-liners (**review wave 2** — the scoped re-review)
  - plus the **R12 external-Codex fix wave** recorded below, which is the last commit on the branch.

### Deviations from the plan

- Task 2 (controller-ruled): `ContractArtifacts.dir` hardened to throw on a set-but-blank `-Dsm.contracts.root` (+1 test) — closes a demo false-green found in Task 1's review.
- Task 2 (report correction, adjudicated in review): the schema `pattern` addition does NOT redden the lock; the test comment was corrected in Task 4.
- Task 4 (controller-ruled, three lock hardenings): missing-keyword guards on all envelope pins (`requireInt`/`requireText`; the `stateVersion/minimum` MissingNode false-green was proven pre/post); strict `isFalseBoolean` at all three `additionalProperties` sites; exact keyword-set closure per recognized payload field shape (envelope side deliberately excluded — description keywords; **superseded by the final-review fix wave below**, whose annotation allow-list made envelope-side closure safe).
- Task 3: one additional `{@link}`→`{@code}` degradation in a ported javadoc (plane-neutrality), under-declared in the implementer report, caught in review; +2 accurate javadoc paragraphs on the moved interface beyond the literal "only the package line changes".
- Task 3/6: `rg` unavailable on this machine — scans ran via `grep -rnE`.
- Task 6 (controller-ruled): `backend/README.md`'s stale "dp-core EventPublisher seam" phrase corrected.
- Measured during Task 4: the `\S`-vs-`isBlank()` residual is exactly `{U+00A0, U+0085}`, schema-stricter (the safe direction) — consistent with the design §3 note.
- **R12 (external Codex review):** integer boundary split closed schema-side on BOTH integer fields
  (Codex found `count`; `stateVersion` was the same class); kit's parameterized test promoted to a full
  per-fixture topic+body contract; `textSet` hardened against non-textual members.
- **R13 (external Codex review):** the final-review wave's keyword closure extended from the envelope side
  to the **branch** side — `entity` / `entity.properties` / `entity.properties.kind` / the `event`
  discriminator — and its A4 not-both / not-each guard extended from `not.required` down into
  `not.anyOf`'s children (singleton-`required` only). Both were live false-greens, measured green on the
  pre-fix lock. Test scope and docs only; no schema keyword and no production source changed.
- Watch items (no action): `blank-tenant.json`'s Python substring `["does not match"]` is the loosest reason entry (unambiguous today); the meshbridge mapper ArchUnit rule covers production classes only (dp-core test sources are not imported by arch-tests) and does not catch the documented `writer().getFactory().getCodec()` reach-through.

### Final-review fix wave

The whole-branch review found no Critical issues but **proved** three schema/Java drifts that keep both gates
green, plus one semantic trap the lock laundered into equality. All four are now closed in
`SchemaMirrorLockTest`, each with its own red-then-green demonstration against a mutated scratch copy of
`contracts/events/` (`-Dsm.contracts.root`).

| # | Escape route | Proven mutation | Now caught by |
|---|---|---|---|
| A1 | Envelope-side keyword addition — the lock pinned the keywords Java mirrors but not that there were no others | `tenant.maxLength: 4` (Python enforces, Java does not, both gates green) | new `everyEnvelopePropertyPinsItsKeywordSetExactly`: every envelope and `entity` property's keyword set pinned by set-equality, annotation keywords (`description`, `$comment`, `title`) allowed through |
| A2 | Branch-level property narrowing — the lock navigated **into** `/properties/{event,entity,payload}` and saw nothing else | `oneOf[catalog.package].properties.producer = {"const":"cp-core"}` (every dp-core `catalog.package` becomes schema-invalid; Java unchanged) | `everyBranchMatchesTheJavaTableExactly`: branch field-name set `== {properties}`, and its properties `== {event, entity, payload}` |
| A3 | `allOf` deletion — the catalog silently stops being a complete event schema (no `eventId`, `producer`, `tenant`, `stateVersion`) | delete `allOf` | new `theNotifyCatalogComposesTheEnvelopeExactlyOnce`: `allOf` present, size 1, `allOf[0].$ref == envelope.$id` |
| A4 | Multi-element `not.required` laundering — `{"required":["a","b"]}` forbids only the **combination**, while the Java forbidden set forbids **each**; flattening made them compare equal | collapse `catalog.package`'s `not.anyOf` of singletons into `not.required: ["instanceId","placementId"]` | `forbiddenEntityIds` now `fail()`s on a multi-element `not.required`, naming the not-BOTH / not-EACH divergence and the `not.anyOf`-of-singletons form to use |

**Prove-it-fires results** (each `mvn -f backend/pom.xml -pl mesh-events test -Dsm.contracts.root=$MUT` →
`BUILD FAILURE`, failure attributed to `SchemaMirrorLockTest`; the real tree re-runs green):

- A1 → `everyEnvelopePropertyPinsItsKeywordSetExactly:105 … envelope.tenant … expected: <[minLength, pattern, type]> but was: <[maxLength, minLength, pattern, type]>`
- A2 → `everyBranchMatchesTheJavaTableExactly:194 catalog.package: a branch constrains exactly event, entity and payload … expected: <[entity, event, payload]> but was: <[entity, event, payload, producer]>`
- A3 → `theNotifyCatalogComposesTheEnvelopeExactlyOnce:163 notify-events.schema.json must compose the envelope through allOf ==> expected: not <null>`
- A4 → `everyBranchMatchesTheJavaTableExactly:205->forbiddenEntityIds:305 entity 'not': a multi-element not.required means "not BOTH", while the Java forbidden set means "not EACH" …`

Also in this wave: one-line comments recording why the `AnyType` arm needs no fifth `assertOnlyKeywords`
call (`isEmpty()` *is* the exactness check) and why the two `6` literals in `branchesByEvent` are deliberate
friction; the parked per-task polish (kit `@Test` methods and `fixtureDocuments()` `protected` so
cross-package subclasses can override — `dp-core`'s subclass still runs its 14 tests unchanged;
`PublisherContractKitSelfTest` now pins *which* assertion fired; `causeMessages` no longer renders a literal
`null` line; `EventEnvelope`'s dangling javadoc block merged; `ContractArtifactsTest` marked `@Isolated`);
and the **"plan 002" wording sweep**, replacing stale-by-anticipation number references with role-based
forward references ("the metrics plan", "the mesh-provisioning plan", "the walking-skeleton plan") across
`JcsmpEventPublisher`, the contract-test kit, `mesh-events/pom.xml` and the G1 spike.

**Fix-wave verification:** `./contracts/run-checks.sh --strict` → `ALL CHECKS PASSED` ·
`mvn -f backend/pom.xml verify` → `BUILD SUCCESS`, **147** tests (145 + the two new lock tests;
`mesh-events` 118 · `dp-core` 14 (+1 failsafe IT skipped-with-condition) · `arch-tests` 15) ·
`git diff --check` clean ·
`grep -rn --exclude-dir=target --exclude-dir=node_modules 'plan 002' backend/ | grep -v 002-contract`
returns nothing.

**Added to the Deviations list:**

- The plan-002 wording sweep (§B of the fix-wave brief) — not in the plan; the number collision only became
  visible once 002 was this plan. `mesh-events/pom.xml` was swept too, beyond the brief's file list, because
  the brief's own sweep-check grep hits it.
- `backend/README.md` brought to post-002 state (status row and approved-plans row now name both plans).
- `docs/design/common/contracts/backend-consumption.md` §5.1: rule name `meshbridgeBuildsNoOwnMapper` →
  `meshbridgeHoldsNoOwnMapper`, described as dependency-shaped (`areAssignableTo(ObjectMapper.class)`) and
  noting it also bars held `EventJson.mapper()` copies; §7's prove-it-fires inventory `13 + 4` → `13 + 7`.
- §3 gains the lock's asymmetry as an explicit rule: *any schema keyword the lock does not name is unenforced
  Java-side — adding a keyword to the contract means adding a pin to the lock in the same commit.*
- **Envelope keyword closure extended beyond the original Task 4 ruling.** This supersedes that deviation's
  "envelope side deliberately excluded — description keywords" line: the annotation allow-list
  (`description`, `$comment`, `title`) is what makes envelope-side closure safe, and it resolves the
  false-red-on-`description` concern on the payload side at the same time.
- `docs/tasks/lessons.md` gains **R6** — a plan number already cited in the tree by anticipation must be
  reconciled at execution time.

### R12 (external Codex review) fix wave

An external Codex post-execution review of `ce09d37..1ba992f` raised four findings. All four are closed
here; none required a change to a frozen `NotifyEventShapes` / `NotifyTopics` / `EventEnvelope` failure
message.

| # | Finding | Disposition |
|---|---|---|
| F1 (MAJOR) | **Integer validity boundary split.** The schema's `minimum` alone let `9223372036854775808` (2**63) validate in Python while Java refused it — so the two implementations disagreed about what an *integer* is. | **Fixed, on BOTH integer fields.** Codex proved it on `metrics.quarantine`'s `count` (`NotifyEventShapes.asIntegral` returns `null` past the 64-bit boundary); the same class holds one level up on `stateVersion`, whose record component is a primitive `long`, so Jackson refuses 2**63 at read. Both schemas now carry `"maximum": 9223372036854775807`. Direction: **schema hardens to Java**, the same rationale as R11's `tenant` pattern — Java's boundary is the real one. |
| F2 (MAJOR) | The kit's all-fixture test asserted only that payload **keys** survived serialization, so five of six fixtures were unchecked on the topic they went out on and on every value in the body. | **Fixed.** `everyPayloadKeyThatSurvivedValidationAlsoSurvivesSerialization` is renamed `everyFixturePublishesOnItsManifestTopicWithItsExactBody` and now also asserts, per fixture, the manifest topic and whole-document tree equality against the fixture. The key-set assertion is kept and runs first (its message names the dropped key); `serializesViaMeshEventsAndDerivesTopicFromNotifyTopics` stays as the golden smoke test. Every implementation extending the kit inherits it — `dp-core`'s subclass is unchanged and still runs 14 tests. (Task 3's step list at §Task 3 still names the old method; this row is the supersession.) |
| F3 (MINOR) | `SchemaMirrorLockTest.textSet` called `asText()` on every array member, so a non-string `enum` / `required` member was laundered into a string instead of reported. | **Fixed.** Each member is asserted `isTextual()` first, with a message saying a non-string member is a schema shape the lock does not model — Java's enums and key sets are `String`-typed. |
| F4 (MINOR) | The `Results` block's headline figures predated the two review waves. | **Fixed** above: 9 negatives, 149 tests, commit range `b107600..1ba992f` with both review-wave commits listed, plus this wave. |

**What changed.** Two schema keywords (`envelope.schema.json` `stateVersion.maximum`,
`notify-events.schema.json` `metrics.quarantine.payload.count.maximum`, one keyword each); two new
negatives, valid but for the overflow — `negative/quarantine-overflow-count.json` and
`negative/stateversion-overflow.json`, taking the event catalog from **7 to 9** negatives; both
`NEGATIVE_REASON` maps extended; and three lock changes (`assertFieldRule`'s `IntegerAtLeast` closure set
is now `{type, minimum, maximum}` with the maximum pinned to `Long.MAX_VALUE`, the envelope
`stateVersion` pin likewise, and `textSet`'s textuality guard). Both maximum pins read through a new
`requireLong` helper: `asInt()` truncates `Long.MAX_VALUE` to `-1`, and `canConvertToLong()` is asserted
before `longValue()` so a bound raised *past* the long boundary is a red rather than a silent truncation.

**One imperfection, recorded rather than fixed.** `quarantine-overflow-count.json` is refused Java-side by
the existing frozen message `payload.count must be an integer >= 1` — accurate as a classification (2**63
is not a Java-representable integer) but poor wording for a value that is far *above* 1. The message is
frozen, so this is documented in `NotifyEventShapesTest`'s reason map rather than reworded.

**Red-first evidence** (both negatives, both sides, before any mapping):

- Python, negatives added and schemas unchanged — Codex's finding reproduced on both fields:
  `FAIL gate10 negative/quarantine-overflow-count.json: ACCEPTED, should have been rejected` ·
  `FAIL gate10 negative/stateversion-overflow.json: ACCEPTED, should have been rejected`
- Java unmapped-negative guard, both files:
  `NotifyEventShapesTest.everyContractNegativeIsHandledForItsDeclaredReason:130 negative/<file> has no
  declared expected reason — add one to NEGATIVE_REASON …`
- Python unmapped-reason guard, after the schema maximums and before the map entries:
  `FAIL gate10 negative/<file>: no declared rejection reason — add one to NEGATIVE_REASON …`

**Prove-it-fires** (scratch copy of `contracts/` via `-Dsm.contracts.root`; each run asserted
`BUILD FAILURE` with the failure attributed, and the real tree re-runs green):

- delete `count.maximum` → `assertFieldRule → assertOnlyKeywords … metrics.quarantine: payload.count …
  expected: <[maximum, minimum, type]> but was: <[minimum, type]>`
- `count.maximum = 2147483647` → `assertFieldRule:266 … an IntegerAtLeast field must cap at
  Long.MAX_VALUE … expected: <9223372036854775807> but was: <2147483647>`
- `stateVersion.maximum = 9223372036854775808` → `requireLong … must fit in a Java long — a bound outside
  the 64-bit range is exactly the schema/Java divergence this pin guards` (the overflow-safety guard)
- delete `stateVersion.maximum` → both `everyEnvelopePropertyPinsItsKeywordSetExactly` and
  `requireLong`'s presence assertion
- one health enum member replaced with the number `1` → `textSet … a non-string enum/required member is a
  schema shape the lock does not model …`
- F2's promotion, proven non-vacuous on a **non-health** fixture: `expected-topics.json`'s
  `config-state.json` entry repointed at the `version` topic →
  `EventPublisherContractTest>AbstractEventPublisherContractTest.everyFixturePublishesOnItsManifestTopicWithItsExactBody:310
  config-state.json: topic must be the one … declares` — a divergence the pre-R12 key-set assertion passed.

**R12 verification:** `./contracts/run-checks.sh --strict` → `ALL CHECKS PASSED` (check 10 lists **9**
event negatives) · `mvn -f backend/pom.xml verify` → `BUILD SUCCESS`, **149** tests
(`mesh-events` 120 · `dp-core` 14 (+1 failsafe IT skipped-with-condition) · `arch-tests` 15) ·
`git diff --check` clean.

### R13 (external Codex review) fix wave

> **R13 re-review residual (N1), closed in the follow-up commit:** the re-review measured one last
> sibling escape — the branch **`payload` node itself** was not keyword-closed, so a `not` hung on it
> (e.g. forbidding `state: GREEN`, a member Java's `HEALTH_STATE` legitimately produces) was
> Python-enforced with both gates green. Closed by keyword-closing the payload node
> (`{type, required, properties, additionalProperties}`), proven firing on exactly that mutation
> (red, attributed, single failure), real tree green at 149. With it, **every node the lock
> navigates is keyword-closed**: envelope properties + entity sub-properties, branch, event, entity,
> entity.kind, payload node, payload fields. Also corrected: one comment that cited a dead guard as
> the empty-`not` backstop (the unparsed-shape fail is the actual red).

Codex's third post-execution pass raised two MAJOR findings, both false-green paths in
`SchemaMirrorLockTest` — the branch side of the same asymmetry the final-review wave closed on the envelope
side. Test scope and docs only: no schema keyword changed, no production source, so no frozen-message risk.

| # | Finding | Disposition |
|---|---|---|
| F1 (MAJOR) | **`not.anyOf` children were not required to be singletons.** The final-review wave (A4) closed the multi-element `not.required` shape but left the `not.anyOf` arm flattening every child's `required` array without a size check. Collapsing `catalog.package`'s two singleton children into one `{"required":["instanceId","placementId"]}` keeps the `anyOf` shape while flipping the meaning to "not BOTH" — the schema then permits a `catalog.package` carrying `instanceId` alone, the Java forbidden set still refuses each id on its own, and flattening made the two sets compare equal. Codex reproduced it; measured green here on the pre-fix lock. | **Fixed.** The `not.anyOf` arm now requires a non-empty array whose every child is an object with exactly one key, `required`, holding an array of exactly one textual entry — `fail()` otherwise, with a message naming the not-both / not-each divergence and the singleton-per-id form to use. Textuality still comes from `textSet`. Any other keyword on a child (an annotation included — here the object is a subschema, not a pinned keyword set) is an unparsed shape and therefore a red (`L7`). |
| F2 (MAJOR) | **The branch-level entity surface was unpinned.** The lock read `entity.required`, `entity.not` and `entity.properties.kind.const` and navigated nowhere else, so `"maxLength": 64` on `instance.health`'s `entity.properties.instanceId` was enforced by the Python validator, mirrored by no Java check, and invisible to both gates — §3's own asymmetry rule, unenforced on the branch side. | **Fixed** inside `everyBranchMatchesTheJavaTableExactly`, per branch, at every level of the scoping surface: `entity` keyword-closed to `{required, properties}` plus `not` **only when present** (its content is separately locked by `forbiddenEntityIds`, and an empty `not` is already a red there); `entity.properties` set-equal to exactly `{kind}`, with a message saying a per-id constraint has no column in `BranchRule` and must be modelled there in the same commit or not written; `entity.properties.kind` closed to `{const}`; and, for same-class completeness, the discriminator `branch.properties.event` closed to `{const}`. |

**Verified against the files while writing (I2):** the branch `entity` nodes carry no `type` keyword (unlike
the envelope's), and `properties.kind` / `properties.event` carry only `const` — which is why the closure
sets are the ones above.

**Prove-it-fires** (mutated scratch copies of `contracts/` via `-Dsm.contracts.root`; each escape measured
**green on the pre-fix lock first**, then red-and-attributed after the fix, then the real tree green):

- F1 — `catalog.package`'s `not` rewritten as `{"anyOf":[{"required":["instanceId","placementId"]}]}`:
  pre-fix `Tests run: 120, Failures: 0` / `BUILD SUCCESS`; post-fix `BUILD FAILURE`,
  `SchemaMirrorLockTest.everyBranchMatchesTheJavaTableExactly:244->forbiddenEntityIds:381 entity 'not':
  every not.anyOf child must be exactly one singleton {"required": ["<id>"]} — a multi-element anyOf child
  means "not both"; Java's forbidden set means "not each" …`
- F2 — `"instanceId": {"maxLength": 64}` added to `instance.health`'s `entity.properties`: pre-fix
  `Tests run: 120, Failures: 0` / `BUILD SUCCESS` **with `contracts/run-checks.sh` check 10 fully green on
  the same mutated tree** (the fixtures' instance ids are short, so the Python gate never sees the
  narrowing either) — the false-green in both directions; post-fix `BUILD FAILURE`,
  `everyBranchMatchesTheJavaTableExactly:231 instance.health: a branch's entity constrains ONLY 'kind' …
  expected: <[kind]> but was: <[instanceId, kind]>`

**R13 verification:** `./contracts/run-checks.sh --strict` → `ALL CHECKS PASSED` (nothing in `contracts/`
changed; run clean anyway, single-writer hygiene) · `mvn -f backend/pom.xml verify` → `BUILD SUCCESS`,
**149** tests, unchanged (`mesh-events` 120 · `dp-core` 14 (+1 failsafe IT skipped-with-condition) ·
`arch-tests` 15 — every R13 assertion was added inside existing test methods, so no count moved) ·
`git diff --check` clean.
