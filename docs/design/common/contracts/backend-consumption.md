# Backend consumption of `contracts/` — the drift-prevention architecture

**Status: PROPOSED** (decisions `BE-D10`, `BE-D11` — recorded in
[`../../backend/decisions.md`](../../backend/decisions.md), status governed only by the ratification matrix in
[`../../DECISIONS.md`](../../DECISIONS.md)). Drafted 2026-08-28 on branch `contracts-backend-consumption`.

**Problem.** `contracts/` is language-neutral and normative; the backend is Java. Wherever a contract rule has a
Java restatement, the two can drift — Python accepts what Java rejects, or Java emits what the schema forbids —
and nothing fails until a wire incident. Plan 001 built the first Java bindings (`mesh-events`, the
`dp-core` publisher seam) and reviews R7/R8 **measured** two such drifts escaping to the wire (a null-stripped
required key; an unenforced `producer` enum). This document decides how the backend consumes every contract
artifact so that drift is a red build, not a wire incident — specific enough that implementation follows it
without inventing structure.

**What this document does NOT decide.** The four schema-ahead-of-design items in
[`contracts/events/README.md` Known gaps](../../../../contracts/events/README.md) stay with the pending `BE-D4`
amendment; open items 32/33 stay with the architect group; nothing here touches auth (G2 still gates that).
This is consumption *mechanics* only.

Every recommendation below names: **authoritative source → consuming module → enforcement point → failure mode
prevented → implementation shape.** "Should validate" without those five is a defect in this document.

---

## 1. The authority map — what is authoritative, for what

| Artifact | Authoritative for | Status | Consumed today by |
|---|---|---|---|
| `contracts/solution-package.schema.json` | Package shape + **every closed vocabulary** (`UI-D14`): `type`, `source.kind`, `optionsSource.kind`, `action.kind`, `mark` | Normative, executable | Python gates (checks 1–3), TS engine types. **No Java consumer yet** — arrives with package load (§9) |
| `contracts/events/envelope.schema.json` | The common event envelope (`BE-D4`): required fields, `producer` enum, `event` enum, `entity.kind` enum, `additionalProperties: false` | Normative, executable | Python (check 10). Java **mirrors** it (`EventEnvelope`) without reading it — the gap §3 closes |
| `contracts/events/notify-events.schema.json` | The six v1 payload shapes: per-event required/allowed keys, payload enums, entity-scoping (`required`/`not: required`) | Normative, executable | Python (check 10). Java **mirrors** it (`NotifyEventShapes`) without reading it — same gap |
| `contracts/events/asyncapi.yaml` | The topic **taxonomy as a document** — for humans and future tooling | Descriptive; honesty-checked (parses, every address starts `sm/v1/`) | Nobody executes it. It references the schemas rather than duplicating them; keep it that way |
| `contracts/events/fixtures/*.json` | One valid instance per catalog event — the **shared golden inputs** | Normative by freeze (byte-frozen) | Python check 10 **and** Java: `EnvelopeGoldenTest` (round-trip), `NotifyEventShapesTest`, `EventPublisherContractTest` (wire honesty), `JcsmpEventPublisherIT` |
| `contracts/events/fixtures/expected-topics.json` | The topic each fixture must be published on — **the** cross-language golden for derivation | Normative | Python check 10 (bidirectional) and Java `NotifyTopicsTest` (bidirectional). Both derivers are goldened to this one manifest |
| `contracts/events/negative/*.json` + `bad-topics.json` | What the contract must **refuse**, one per load-bearing constraint | Normative | Python (reason-matched per branch) and Java (`NotifyEventShapesTest`'s `NEGATIVE_REASON` map + unmapped-file guard; `bad-topics` non-derivability in `NotifyTopicsTest`) |
| `contracts/events/validate-events.py` | Nothing. It is the **Python implementation**, not the authority — its `derive_topic` and `NEGATIVE_REASON` are one side of a two-implementation pair goldened to the manifest and negatives | Executable check | `run-checks.sh` check 10 |
| `contracts/view-models/fixtures/viewmodel/*.json` | The 33 `(package, selection, draft, observed) → viewModel` goldens — **the future Java `project()` implementation's target** (`FE-D12`, `DM-D20`) | Normative by freeze, regeneration-checked (check 8) | TS engine. No Java consumer yet (§9) |
| `docs/design/frontend/reference/STATE-VOCABULARY.md`, `contracts/view-models/src/types.ts` | The canonical vocabularies the event schema **copies** (health states, `VersionState`) | Canonical sources (`UI-D14`) | The schemas copy from them; Java copies from the schemas — one canonical chain, §3 locks each link |

Two consequences worth stating plainly:

- **The design documents are the authority on *meaning*; the schemas are the authority on *shape*.** When they
  disagree, that is a flagged gap routed to a decision (the four Known-gaps items are exactly this), never
  something a consumer resolves locally by picking a side.
- **`validate-events.py` is deliberately not "the reference implementation" for Java to transcribe.** Java
  transcribing Python reproduces Python's bugs. Both implement the same normative artifacts (schemas, manifest,
  negatives) independently, and the shared goldens arbitrate. That is the `UI-D15`/`DM-D20` pattern and it
  already caught real divergence (R8's two wire escapes were found by strengthening exactly this arbitration).

---

## 2. The consumption model — `BE-D10`

Three models were on the table. Run the same three scenarios through each before reading the verdict:
**(S1)** the architect group amends `BE-D4` and a seventh event is added to the catalog; **(S2)** a reviewer
renames a payload key in the schema (`reached` → `state`) without touching Java; **(S3)** a new health enum
member is added to `STATE-VOCABULARY.md` and copied into the schema.

**Option A — generate Java types from the schemas at build time.**
What stays the same: fixtures remain the goldens; the wire-honesty tests remain. The delta: a codegen plugin in
`mesh-events`, generated sources on the classpath, the hand-written records deleted. S1/S2/S3 all "just work" —
*if* the generator can express the schema. It cannot, cleanly: the notify schema's dispatch is
`oneOf`-discriminated by **two** fields (`event` const + `entity.kind` const), entity scoping uses two
`not` shapes (`not.required` and `not.anyOf[].required`), and `packageVersion` is a deliberate any-type hole
(Known gap 4) that must not be narrowed by a tool default. What falls out of this schema still needs
hand-written dispatch and contract-lock tests around the generated half, plus a build toolchain to own.
Against the status quo this buys S2/S3 protection at the cost of a new toolchain **and still needs the
lock/golden tests** to prove the generated half means what the schema means. Rejected for v1: the schema is two
files and six branches; the toolchain outweighs the transcription. Revisit at the repo split (`BE-D11` keeps
that door open), if the catalog grows past ~15 events, or after a verified codegen spike against these exact
schema features.

**Option B — validate JSON against the schemas at runtime.**
What stays the same: hand-written records. The delta: a Java JSON-Schema engine dependency in `mesh-events`,
`EventPublisher` validating each emitted document against `notify-events.schema.json` before publish. S2/S3
are caught — at the first *runtime* emission of the affected event, in the customer's environment, not at
build time. S1 needs Java code anyway (a topic rule, a branch). The failure it prevents is one construction
paths already prevent: producers build `EventEnvelope` records whose compact constructor and
`NotifyEventShapes.validate()` enforce the same constraints at the same chokepoint, with **zero** runtime
dependencies. Runtime schema validation adds a dependency, a per-publish cost, and — decisive — moves the
red from `mvn verify` to production. Rejected for the **producer** side. (It is the *right* model where the
input is user-supplied at runtime — package upload, §9 — because there validation is the feature, not a test.)

**Option C — hand-mirrored Java, locked to the schema files by tests, arbitrated by shared goldens (chosen).**
What stays the same: everything plan 001 built — the records, `NotifyTopics`, `NotifyEventShapes`,
`EventJson`, the golden/negative/wire-honesty suites. The first delta is `SchemaMirrorLockTest` (§3), which
reads the schema JSON in `mvn verify` and asserts the mirrored keys, scoping, primitive types and bounds equal
the schema's, both directions. S1: the lock test goes red the moment the schema gains a branch Java does not
handle (and vice versa). S2: red — the schema's required/allowed key sets no longer match Java's. S3: red —
the enum sets differ. A bound/type change goes red too. No new runtime dependency (Jackson tree navigation
only, already on the test classpath), no toolchain, and the red lands in the build. Cost, honestly stated: the
mirror stays hand-maintained — a schema change is still a two-file change — but the lock turns "remember to
update Java" into "the build tells you", which is the actual failure mode observed (R7 F1, R8 F1/F2 were all
mirror gaps nothing pointed at).

> **`BE-D10` (PROPOSED):** the backend consumes `contracts/events/` by **mirror-and-lock** — typed hand-written
> bindings in `mesh-events`, kept honest by (i) schema-lock tests that parse the schema files and assert
> equality with the mirrored keys, enums, entity scoping, primitive types and numeric/string bounds,
> (ii) the shared fixture/negative/manifest goldens both
> implementations already run, and (iii) wire-honesty tests on emitted bytes. No code generation and no runtime
> schema validation on the producer path in v1.

The deliberate Java/Python asymmetry on `packageVersion` (Java rejects a null-valued required key because
`NON_NULL` serialization cannot emit it faithfully; Python accepts any type) **survives this model** and must:
the lock tests pin the schema's `{}` shape but do not pretend to adjudicate Java's null-on-wire serialization
semantics. Do not "fix" either side; the asymmetry dissolves when the `BE-D4` amendment types the field.

---

## 3. The schema-lock tests — the one new mechanism this design mandates

**Authoritative source:** `contracts/events/envelope.schema.json`, `contracts/events/notify-events.schema.json`.
**Consuming module:** `mesh-events`, test scope only.
**Enforcement point:** `mvn verify` (surefire; the class name must end in `*Test` — surefire silently skips
anything else, a trap plan 001 hit).
**Failure mode prevented:** the schema and the Java mirror change independently — either direction — and both
suites stay green because fixtures only exercise the old shape. This is exactly how R7 F1 (schema-invalid Java
events) lived long enough for an external review to find it.
**Implementation shape:**

```java
package com.solace.sm.events;            // same package as the mirrors — reads their
                                          // package-private constants directly; the one sanctioned
                                          // reflective read is Class.getRecordComponents(), so the
                                          // envelope/entity field lists are never restated as
                                          // test literals (a literal list is a third mirror)
class SchemaMirrorLockTest {
    // Loaded once via ContractArtifacts (§4) + EventJson.mapper() — no new dependency.
    static JsonNode envelope;             // envelope.schema.json
    static JsonNode notify;               // notify-events.schema.json

    // Envelope locks — each assertion names the schema pointer it read (I2: read, not recalled):
    //   required            == the record's components (all eight, spelled identically)
    //   properties keys     == required ∪ {}   (additionalProperties:false has no optional fields today;
    //                                            if that changes, this line is WHERE the change surfaces)
    //   producer.enum       == EventEnvelope.PRODUCERS
    //   event.enum          == NotifyEventShapes.handledEvents()  (== NotifyTopics' switch, transitively:
    //                          the existing six-fixture bidirectional manifest test covers the topic side)
    //   entity.kind.enum    == the kinds NotifyEventShapes' branch table uses
    //   entity.properties   == EntityRef's components
    //   organization.type/minLength/pattern == String + the non-blank rule. The schema gains
    //                                  `"pattern": "\\S"` in the same reviewed contract change
    //                                  (plan 002 Task 4): before it, "   " validated in Python and
    //                                  was refused by Java's isBlank() — cross-language drift on an
    //                                  ACL-bearing topic level. \S and isBlank() agree on ASCII and
    //                                  common Unicode whitespace; exotic codepoints where the two
    //                                  engines' whitespace classes differ are an accepted residual,
    //                                  named here so nobody silently "fixes" one side.
    //   stateVersion.type/minimum/maximum == long + >= 0 rule. The maximum (Long.MAX_VALUE, added
    //                                  by R12) is Java's long-ness itself, pinned so schema validity
    //                                  and JVM representability agree — before it, 2**63 validated in
    //                                  Python and failed Jackson's long binding. Forward note: that
    //                                  bound is not IEEE-754-representable, so when the TS consumer
    //                                  lands, its validator sees 2**63 and reopens the same split one
    //                                  language over — route with that consumer's work, not here.

    // Per-branch locks — for each of notify's oneOf branches, keyed by its event const:
    //   payload.required           == the branch's required-key set in NotifyEventShapes
    //   payload.properties keys    == the branch's allowed-key set (additionalProperties:false makes
    //                                  these the SAME set today for every branch except none — assert
    //                                  allowed ⊇ required explicitly so a future optional key is a
    //                                  deliberate edit here, not an accident)
    //   each typed payload field's type/minimum/maximum == the Java primitive checker it routes to
    //                                  (stringValue, integerAtLeast, or enumValue; integer fields
    //                                  also pin maximum == Long.MAX_VALUE, per stateVersion above)
    //   each enum-typed payload field's members == the mirrored Set (HEALTH_STATE, CONFIG_REACHED,
    //                                  VERSION_STATE, ROLLOUT_STEP)
    //   entity.required            == the branch's required entity ids
    //   forbidden entity ids       == every id parsed from BOTH shapes the schema uses:
    //                                  `not:{required:[...]}` and
    //                                  `not:{anyOf:[{required:[...]}, ...]}`
    //   the branch's scoping nodes are KEYWORD-CLOSED, level by level, because the lock reads only
    //                                  what it navigates to: `entity` == {required, properties}
    //                                  plus `not` when present, `entity.properties` == exactly
    //                                  {kind}, `entity.properties.kind` == {const}, and the
    //                                  discriminator `event` == {const}. A per-id constraint at
    //                                  branch level (`instanceId: {maxLength: 64}`) has no column
    //                                  in the Java branch table, so it is Python-enforced and
    //                                  Java-blind — model it in BranchRule or do not write it.
    //   every `not.anyOf` child is a SINGLETON `required` — one key, one id. A multi-element child
    //                                  means "not BOTH", which is a different rule from the Java
    //                                  forbidden set's "not EACH" and one the mirror does not
    //                                  model; flattening it would launder the divergence into
    //                                  equality, so the lock fails on it (as it already does on a
    //                                  multi-element `not.required`).
    //   `packageVersion` asserted to be `{}` — the lock test PINS the known gap so its silent
    //                                  hardening or widening is also a red, in either language
}
```

Rules that make the lock a lock and not decoration:

1. **Set-equality, not containment**, in every assertion — containment lets one side grow silently.
2. **The load-bearing Java data becomes package-private** — `PRODUCERS` and the branch table (with its
   `FieldRule` types) — with a one-line comment naming this test as the reader. The four payload-enum sets
   **may stay `private`**: the branch table carries the enforced members, so the lock reads them through it
   and a separate widening would be a second door to the same data. Envelope and entity field lists are
   derived from `getRecordComponents()`, never restated. No getters on the public API — the lock is a
   test-scope concern.
3. `NotifyEventShapes` becomes **table-driven** — its branch constraints move into a package-private
   declarative table that `validate()` itself consumes — and gains `static Set<String> handledEvents()`
   returning the table's keys. The refactor is what the lock requires: it must compare the schema against
   data the validator actually enforces; a table nothing reads, or constraint literals restated in the
   test, would each be a third mirror.
4. **Vacuity guards**, matching the house pattern: the test fails if either schema file is missing, parses to
   zero branches, a branch lacks an `event` const, a branch yields no explicitly parsed forbidden-entity set
   (empty is valid only when parsed as empty), or a typed schema property is not mapped to exactly one Java
   checker — a lock that locks nothing must be red, never green (`L7`).
5. **Prove-it-fires evidence at introduction:** the PR adding this class must demonstrate one planted
   divergence per assertion *group* (mutate a copy of the schema in a temp dir, point the resolver override at
   it, watch the red) in its test output or review notes — the same standard `run-checks.sh` and the ArchUnit
   rules were held to. A permanent planted-violation fixture is not required; the resolver override (§4) makes
   the demonstration reproducible on demand.

**The lock is inherently asymmetric, and that is a rule, not a caveat: any schema keyword the lock does not
name is unenforced Java-side — adding a keyword to the contract means adding a pin to the lock in the same
commit.** The Java side is code, not data, so nothing derives the keyword set for the lock; a `maxLength` or
`maximum` added to a schema property is enforced by the Python validator, honoured by no Java check, and
invisible to both gates unless the lock names it. This is why every pinned schema object asserts its keyword
set by **set-equality with annotation keywords (`description`, `$comment`, `title`) allowed through**, rather
than asserting the keywords it happens to know about are present.

What the lock deliberately does **not** check: `format: uuid`/`date-time` semantics (the round-trip golden
test already proves those on real bytes), topic derivation (the manifest test owns it, bidirectionally), and
the null serialization asymmetry around `packageVersion` (the deliberate asymmetry above). Types, minimums and
`minLength` are **not** excluded; a retyped field or relaxed bound is a breaking contract change and must turn
`SchemaMirrorLockTest` red.

---

## 4. Artifact plumbing — `BE-D11`

### 4.1 One resolver, test scope, exported

**Authoritative source:** the `contracts/` tree itself.
**Consuming modules:** every backend module's *tests* (today: `mesh-events`, `dp-core`; tomorrow: `cp-core`).
**Enforcement point:** compilation — there is exactly one class to import; the three current copies are deleted.
**Failure mode prevented:** three hand-rolled path resolvers drifting (one already differs: the IT uses a
brittle `../../` relative path that breaks the moment the module nests differently), and — later — a repo
split that has to find and rewrite every resolver instead of one.
**Implementation shape:** promote the existing walk-up logic out of `EnvelopeGoldenTest.ContractFixtures` into
a top-level test class, exported via a Maven **test-jar**:

```java
package com.solace.sm.events.contracttest;   // in mesh-events src/test/java

/** Locates contracts/ artifacts for Java tests. Order: -Dsm.contracts.root, else walk up
 *  from the working directory to the first ancestor containing contracts/events/. */
public final class ContractArtifacts {
    public static Path dir(String sub);                       // e.g. dir("events/fixtures")
    public static List<Path> jsonFiles(Path dir, String... excludeNames);
    public static JsonNode json(Path file);                   // parsed with EventJson.mapper()
}
```

```xml
<!-- mesh-events/pom.xml: export test classes -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId>
  <executions><execution><goals><goal>test-jar</goal></goals></execution></executions>
</plugin>
<!-- dp-core/pom.xml (and later cp-core): -->
<dependency>
  <groupId>com.solace.sm</groupId><artifactId>mesh-events</artifactId>
  <version>${project.version}</version><type>test-jar</type><scope>test</scope>
</dependency>
```

The `-Dsm.contracts.root` override is the **repo-split seam**: when contracts become a versioned artifact,
consumption changes inside this one class (unpack the artifact, point the property at it) and no test moves.
It is also what makes §3's prove-it-fires demonstration reproducible (point it at a mutated copy).

### 4.2 Runtime consumers get a build-time copy with a byte-equality check

`mesh-events` never reads contract files at runtime — §2 settled that. But §9's package-upload validation
**must** have the solution-package schema at runtime, and a deployed jar has no `../../contracts/`. The rule,
set now so the first runtime consumer doesn't improvise:

- The schema is **copied into the consuming module's resources at build time** (maven-resources-plugin,
  copying from `../../contracts/` while the monorepo exists; after repo split, copying from the versioned
  contracts artifact), never committed as a second copy in `src/`.
- A test in that module asserts the packaged copy is **byte-identical** to the in-tree source — the
  "generated code is regenerated in CI with a diff check" rule from [`README.md`](README.md), applied to a
  copy instead of a generation. After repo split, "source" means the unpacked versioned contracts artifact
  selected by the module build. A copy without the check is how two-sources-of-truth starts.

> **`BE-D11` (PROPOSED):** contract artifacts reach Java **tests** only through
> `ContractArtifacts` (mesh-events test-jar; walk-up resolution with a `-Dsm.contracts.root` override), and
> reach Java **runtime** only as build-time resource copies guarded by a byte-equality test against the
> monorepo source now, or the selected versioned contracts artifact after repo split. No schema or fixture is
> ever committed under `backend/`.

---

## 5. Which module may consume what

| Module | May consume | Must not | Enforced by |
|---|---|---|---|
| `mesh-events` | Schemas + fixtures + negatives + manifest, **test scope**, via `ContractArtifacts`. Nothing at runtime (zero runtime deps beyond Jackson) | Import either plane (`cp-*`, `dp-*`) | ArchUnit `eventsNeverImportCp`/`eventsNeverImportDp` (exist, planted-proofs) · pom edges |
| `dp-core` | `mesh-events` main types; fixtures via the test-jar for its wire-honesty/IT suites; the shared publisher seam once §11 moves it out of `dp-core` | Parse contract JSON at runtime; construct event JSON outside `EventJson`; hold a second `ObjectMapper` in `meshbridge` | pom edges · publisher contract-test kit (§5.2) · **new ArchUnit rule, §5.1** |
| `cp-core` | `mesh-events` main types; the shared publisher seam when it grows catalog notify. Later: the packaged solution-package schema, runtime, per §4.2/§9 | Import `dp-core` / `..dp.meshbridge..`; create a second publisher contract; publish without the shared contract-test kit | pom edges · publisher contract-test kit (§5.2) · ArchUnit I7 rules |
| `console-gateway` | **Nothing from `contracts/` and nothing from `mesh-events`.** The `/mesh` proxy is byte-level (`BE-D7`); the gateway never parses an event. Its contract surface is the future OpenAPI document | Depend on `mesh-events` | pom (no edge today — **keep it out**) · ArchUnit rule below |
| `arch-tests` | Everything, as classes under test | — | is the enforcement |
| Future `go-agent` / `frontend` | Same normative artifacts, own bindings, same golden arbitration (the TS side already lives this way via `UI-D15`) | Transcribe the Java or Python implementations | out of scope here; noted so nobody "helpfully" exports Java types to them |

### 5.1 Two ArchUnit additions

Both go in `arch-tests/PlaneBoundaryTest` (or a sibling `ContractConsumptionTest`), each with a
planted-violation fixture proving it fires — a rule that cannot fail is decoration:

1. **`gatewayNeverImportsEvents`** — `noClasses().that().resideInAPackage("com.solace.sm.gateway..")
   .should().dependOnClassesThat().resideInAPackage("com.solace.sm.events..")`. Failure mode: someone adds
   event parsing to the proxy "just to log the event type", and the gateway silently becomes a third
   implementation of the contract with no goldens watching it.
2. **`meshbridgeHoldsNoOwnMapper`** — no class in `..dp.meshbridge..` (and, when it exists, the cp-side
   publisher package) may depend on a mapper at all:
   `.should().dependOnClassesThat().areAssignableTo(ObjectMapper.class)`. This is plan 001's
   "one `EventJson` mapper, never a second" invariant promoted from prose to a rule. **Dependency-shaped
   rather than call-shaped**, deliberately: a call-shaped rule names the constructors it knows about, so it
   catches `new ObjectMapper()` and `JsonMapper.builder()` and misses a mapper obtained any other way.
   Because `JsonMapper` extends `ObjectMapper`, the assignability form covers both — and it also bars a
   **held `EventJson.mapper()` copy** in the publisher package, which is intended: publishers emit through
   `EventJson.write()`/`writer()` and read through `reader()`, all mapper-free signatures, so a publisher
   holding a mapper is a publisher acquiring the ability to diverge. Scoped to the publisher packages
   deliberately: REST layers elsewhere will legitimately own mappers.

### 5.2 One publisher seam, one contract-test kit

Plan 001 put `EventPublisher` in `com.solace.sm.dp.meshbridge` because `dp-core` had the only implementation.
That placement is temporary. The first `cp-core` publisher would otherwise have two bad choices: import
`dp-core` and violate `I7`, or invent a second interface and lose the wire-honesty guarantee.

Before any second publisher lands, the transport-free seam moves to `mesh-events` (for example
`com.solace.sm.events.publisher.EventPublisher`), because it depends only on `EventEnvelope` and the event
contract. `dp-core` keeps the JCSMP implementation; `cp-core` can add a catalog publisher without a plane edge.
This is not a broker or auth decision — it is the mechanical consequence of making events a shared contract.

The same move creates a reusable test kit in the `mesh-events` test-jar:

```java
package com.solace.sm.events.contracttest;

public abstract class AbstractEventPublisherContractTest {
    protected abstract EventPublisher publisher(CapturingTransport transport);

    // Exercises every golden fixture, every negative, serialization failure,
    // transport failure, drop counting, and emitted-byte contract checks.
}
```

Every `EventPublisher` implementation extends this kit. A publisher-specific test that only exercises its own
happy path is not enough; that is how a future `cp-core` publisher bypasses `NotifyEventShapes.validate()` while
the existing `JcsmpEventPublisher` suite stays green.

**Enforcement plan:** when backend implementation is authorized, `arch-tests` must pin the concrete
`EventPublisher` implementation set (`PlaneBoundaryTest.PUBLISHER_IMPLEMENTATIONS`) as soon as the
second implementation is introduced. An implementation must not be added without updating the pinned
inventory; that edit is the reviewed moment at which the kit-subclass pairing is checked. `arch-tests`
cannot see other modules' test classes, so the pairing itself remains a review obligation with a forced
review point.

---

## 6. What belongs in `contracts/` vs `backend/`

The strict placement rules, stated as invariant-shaped sentences:

- **`contracts/` holds only language-neutral normative artifacts and their executable checks.** Schemas,
  fixtures, negatives, manifests, AsyncAPI, the Python validators (checks, not authorities — §1), and the TS
  view-model engine as the one sanctioned code exception (it is a shared build input for two toolchains —
  the reason is recorded in [`contracts/README.md`](../../../../contracts/README.md), don't extend it by
  analogy). **No Java lands in `contracts/`, ever** — a Java helper there would make the neutral tree
  toolchain-bearing for one consumer and start the slide the view-model exception was carefully argued past.
- **`backend/` holds bindings, never restatements of authority.** Java records, the topic deriver, the shape
  validator — each one carries a javadoc naming the contract file it mirrors (already the house style) and a
  lock/golden test tying it there. A constant in Java whose value came from a contract file but which no test
  compares to that file is a defect this design defines as such.
- **No copies.** No schema or fixture committed under `backend/` (test access is §4.1; runtime access is
  §4.2's checked copy). The R6-era lesson generalizes: every uncontrolled copy eventually disagrees with its
  source, and the disagreement is always discovered by the consumer, in the field.
- **Contract changes stay single-writer.** The events README's change checklist (catalog row → schema branch →
  fixture → manifest entry → `derive_topic` rule → negative + reason) **gains one Java step**: the mirror +
  its lock-test row, in the same reviewed commit. The lock test is what makes forgetting this step impossible
  rather than impolite.

---

## 7. Check ownership — `run-checks.sh --strict` vs `mvn verify`

No CI exists yet; these two commands run manually and are the whole gate. The split is by *question asked*,
and the overlap on negatives/goldens is deliberate redundancy across implementations, not waste:

| Question | Owner | Mechanism |
|---|---|---|
| Is the contract self-consistent? (fixtures validate, negatives rejected **for their declared reason**, topics derive bidirectionally, bad-topics non-derivable, AsyncAPI honest, deps meet floors) | `./contracts/run-checks.sh --strict` (check 10 + preflight) | `validate-events.py`, language-neutral |
| Does the **Java mirror equal the schema**? | `mvn verify` | §3 `SchemaMirrorLockTest` — **new** |
| Does Java accept every golden and round-trip it byte-for-byte? | `mvn verify` | `EnvelopeGoldenTest` (exists) |
| Does Java derive the manifest's topics, bidirectionally, and none of `bad-topics.json`? | `mvn verify` | `NotifyTopicsTest` (exists) |
| Does Java reject every negative for its declared reason — including negatives added later? | `mvn verify` | `NotifyEventShapesTest`'s reason map + unmapped-file guard (exists) |
| Do the **emitted bytes** obey the contract (not just the in-memory object), for every publisher implementation? | `mvn verify` | §5.2 abstract publisher contract-test kit; today's `EventPublisherContractTest` is the first concrete case |
| Do module boundaries hold? | `mvn verify` | ArchUnit + planted proofs (exists; §5.1 adds two) |
| Is the runtime schema copy identical to source? | `mvn verify` (when §9 lands) | §4.2 byte-equality test |

**Java tests consume contract fixtures directly: yes, and only via `ContractArtifacts`.** Copying fixtures
into `src/test/resources` is prohibited by §6.

**Planted-negative / prove-it-fires inventory required by this design** (each new check ships with its own):
§3's per-assertion-group planted divergence; §5.1's two planted ArchUnit fixtures; §4.2's check demonstrated
against a mutated copy; §5.2's planted non-validating publisher proving the abstract kit fails when
`NotifyEventShapes.validate()` is bypassed. The existing guards (13 + 9 contract negatives, frontend guard self-tests, ArchUnit
fixtures, run-checks' vacuity rules) are the precedent, not the ceiling.

**Follow-up recorded, not designed here:** wiring both commands into CI. Until then every contracts-touching
PR runs both by hand — the plan template's verification section should name them verbatim.

---

## 8. Versioning and compatibility

- **Schema identity:** `$id: sm/events/v1/<name>`. The `v1` in `$id` and the `sm/v1/` topic prefix version the
  **same contract** and move together — a v2 payload on a v1 topic is the confusion the prefix exists to
  prevent (`BE-D4`).
- **What is breaking:** under `additionalProperties: false` and closed enums, *almost everything* — a new
  payload key, a removed key, a retyped field. That strictness is deliberate (`I6` depends on it: an unlisted
  key in a notify payload is how a value leaks). The two non-breaking moves inside v1 are:
  **(a) a new enum member** whose canonical source (§1's vocabulary chain) gained it — wire-compatible for
  producers; consumers must render unknown members through a defined degradation (the frontend's state
  vocabulary already mandates an unknown-state presentation; a consumer that throws on an unknown enum member
  is the consumer's bug), and **(b) a new event** — new branch, new topic under `sm/v1/`, invisible to
  subscribers that never subscribe to it. Both still walk the full §6 change checklist.
- **Breaking = `sm/v2/`:** new `$id`s, new topic prefix, dual-publish through a declared compatibility window
  (deployed browsers and — later — field agents upgrade on the customer's schedule, not ours;
  [`README.md`](README.md) already fixes this rule). v1 artifacts are then frozen, marked deprecated in the
  READMEs with the retirement decision's ID, and **deleted only by a decision**, never by tidy-up.
- **AsyncAPI evolves in lockstep** and stays reference-only (payloads by `$ref`, §1). Its honesty checks
  (parses; every address under the current prefix) extend to cover v2 channels when they exist.
- **The deliberate hole is pinned:** §3 asserts `packageVersion` **is** `{}` so that the `BE-D4` amendment's
  resolution is a visible two-sided change (schema + Java + Python + the asymmetry's removal), not a quiet
  hardening on one side.

---

## 9. The other two contracts, when the backend reaches them

Neither is v1-now work; both get their consumption model fixed here so the first implementer doesn't invent one.

**Solution package (`cp-core`, package load / quarantine).** The package is **user-supplied runtime input**, so
this is the one place Option B is correct: `cp-core` validates uploads against the packaged schema copy (§4.2)
with a Java JSON-Schema engine, then re-runs the semantic gates the schema can't express (the Python
`validate-package.py` gates 2–13 have Java equivalents server-side). Parity strategy: the **same**
`validation/fixtures/*.package.json` must pass and the **same 17 negatives must fail, per-gate**, via
`ContractArtifacts` — the two-implementation pattern again, with the gate catalog as the reason map.
Library choice is deliberately **not** made here: it must support draft 2020-12 `$ref`/`$defs` resolution, and
per `I2` the candidate's actual draft support gets verified by WebFetch against its own docs before the plan
names it. What is decided: the model (runtime-validate + shared goldens), the packaging (§4.2), and that
vocabulary enforcement cites `UI-D14`'s single canonical home.

**View-models (`project()` in Java, Designer phase).** The 33 committed viewmodel fixtures are already the
declared target (`FE-D12`, `DM-D20`). The Java implementation is goldened against them via `ContractArtifacts`
— never transcribed from `project.ts` with the fixtures as an afterthought. Check 8's regeneration guard keeps
the goldens honest on the TS side; the Java side inherits them as read-only inputs.

---

## 10. Open questions

**Must be decided before implementing this design:** nothing outside it — the design is self-contained and
touches no gated area (no auth, no broker). Its two decisions ratify with the `BE-D*` family in the normal
order.

**Decided here, flagged for ratification:** `BE-D10` (mirror-and-lock; no codegen; no producer-side runtime
validation) and `BE-D11` (resolver seam + checked runtime copies) — both PROPOSED, cited from
[`backend/decisions.md`](../../backend/decisions.md).

**Deliberately deferred, with the trigger named:**

| Deferred | Trigger to revisit |
|---|---|
| Codegen for event types | Repo split (contracts become versioned artifacts) or the catalog outgrowing hand-maintenance (~15+ events) |
| Java JSON-Schema library selection | The `cp-core` package-load plan; verify draft-2020-12 support per `I2` before naming it |
| CI wiring of `run-checks.sh --strict` + `mvn verify` | First CI work item, any component |
| `.proto` (agent) and OpenAPI (console) consumption models | Those contracts don't exist yet ([`README.md`](README.md)); they inherit §4's plumbing and §7's split when written |
| The four Known-gaps items and the pinned `packageVersion` hole | The `BE-D4` amendment (architect group) |

---

## 11. Implementation inventory (feeds the plan, in order)

1. `ContractArtifacts` in `mesh-events` test scope + test-jar export; migrate the three existing resolvers to
   it; delete the copies (`EnvelopeGoldenTest.ContractFixtures` becomes a thin alias or is inlined away).
2. Visibility changes: `EventEnvelope.PRODUCERS` and `NotifyEventShapes`' branch table → package-private
   (the enum sets stay `private` — the table carries them); add `NotifyEventShapes.handledEvents()`.
3. `SchemaMirrorLockTest` per §3, with the per-assertion-group planted-divergence demonstration.
4. Shared publisher seam + abstract publisher contract-test kit per §5.2; migrate `JcsmpEventPublisher` to it
   before any `cp-core` publisher exists.
5. Harden the schema's organization rule to match Java (`"pattern": "\\S"` — before it, `"   "` validated in Python
   and was refused by `EventEnvelope`, R11), and add shared contract negatives for the blank organization, one enum
   violation and one bound violation, wiring both Python and Java reason maps to all three. This is the
   fixture-side backstop for §3's type/bound locks, and the one reviewed contract change in the inventory.
6. The two ArchUnit rules per §5.1, each with a planted fixture.
7. Index updates ship with this doc; `backend/README.md`'s status/plan rows corrected.
8. Future completion gate: `./contracts/run-checks.sh --strict` and `mvn -f backend/pom.xml verify` must both
   be green. Historical Java test counts are evidence only; there is no current backend reactor.

Items 1–6 are mechanical against this document and sized for a single small plan (or one reviewed PR) — no
task in them touches a gated area.
