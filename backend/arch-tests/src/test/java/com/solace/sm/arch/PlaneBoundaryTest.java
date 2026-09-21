package com.solace.sm.arch;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.events.publisher.EventPublisher;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@code I7} — the cross-plane boundary — expressed as executable rules.
 *
 * <p>Control-plane code must not import data-plane code or its data-access module, and vice versa;
 * {@code console-gateway} reaches the planes only through their {@code -api} modules; and the shared
 * {@code mesh-events} module stays plane-neutral (backend design §2, {@code BE-D3}).
 *
 * <p><b>Why the neutrality rules are not redundant.</b> ArchUnit's {@code dependOnClassesThat()}
 * checks <b>direct</b> dependencies only — the transitive form is a separate
 * {@code transitivelyDependOnClassesThat()}. So the two plane rules alone would stay silent on
 * {@code dp-core → mesh-events → cp-api}, and Maven would too, since a compile-scope dependency of
 * {@code mesh-events} lands transitively on {@code dp-core}'s classpath. Keeping the shared module
 * neutral is what closes that conduit.
 *
 * <p>Each rule carries a planted-violation proof. This is the repo's guard-self-test discipline: a
 * rule that cannot fire is decoration, so a deliberate violation must prove the rule bites. The
 * fixtures live in {@code ..archfixture..} packages, test-scope inside {@code arch-tests} only, and
 * {@link ExcludeArchFixtures} keeps them out of the real {@code @ArchTest} run below — the proof
 * tests re-import them without that filter.
 */
@AnalyzeClasses(packages = "com.solace.sm", importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class PlaneBoundaryTest {

    // ---------------------------------------------------------------------------------------
    // The rules. These run against production classes only (fixtures excluded).
    // ---------------------------------------------------------------------------------------

    @ArchTest
    static final ArchRule cpNeverImportsDp =
            noClasses().that().resideInAPackage("com.solace.sm.cp..")
                    .should().dependOnClassesThat().resideInAPackage("com.solace.sm.dp..");

    @ArchTest
    static final ArchRule dpNeverImportsCp =
            noClasses().that().resideInAPackage("com.solace.sm.dp..")
                    .should().dependOnClassesThat().resideInAPackage("com.solace.sm.cp..");

    @ArchTest
    static final ArchRule gatewayUsesApisOnly =
            noClasses().that().resideInAPackage("com.solace.sm.gateway..")
                    .should().dependOnClassesThat(
                            resideInAPackage("com.solace.sm.cp..")
                                    .and(not(resideInAPackage("com.solace.sm.cp.api..")))
                                    .or(resideInAPackage("com.solace.sm.dp..")
                                            .and(not(resideInAPackage("com.solace.sm.dp.api..")))));

    // `mesh-events` is plane-NEUTRAL, and the two rules below are what keep it that way.
    //
    // They are not redundant with the plane rules. `dependOnClassesThat()` checks DIRECT
    // dependencies only (ArchUnit's transitive form is a separate `transitivelyDependOnClassesThat()`),
    // so `dpNeverImportsCp` would stay silent on `dp-core -> mesh-events -> cp-api`. Maven stays
    // silent too: a compile-scope dependency of `mesh-events` lands transitively on `dp-core`'s
    // classpath. Holding the shared module neutral is what closes that conduit — and neutrality is
    // exactly what makes it safe for BOTH planes to consume, which Task 9 onward will want.

    @ArchTest
    static final ArchRule eventsNeverImportCp =
            noClasses().that().resideInAPackage("com.solace.sm.events..")
                    .should().dependOnClassesThat().resideInAPackage("com.solace.sm.cp..");

    @ArchTest
    static final ArchRule eventsNeverImportDp =
            noClasses().that().resideInAPackage("com.solace.sm.events..")
                    .should().dependOnClassesThat().resideInAPackage("com.solace.sm.dp..");

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

    // Design §2: "no other module holds a broker session" — the broker SDK is meshbridge-internal.
    // Package-based (com.solacesystems..) rather than class-based so it covers the whole SDK
    // surface, sessions and factories and properties alike.
    @ArchTest
    static final ArchRule onlyMeshbridgeTouchesJcsmp =
            noClasses().that().resideOutsideOfPackage("com.solace.sm.dp.meshbridge..")
                    .should().dependOnClassesThat().resideInAPackage("com.solacesystems..");

    // Each plane owns one persistence package. The seam is only a seam while no other package can
    // reach past it.
    //
    // Six package sets, each closing a different way around: java.sql/javax.sql (a raw Connection or
    // DataSource handed to a service), org.springframework.jdbc (a second JdbcTemplate built
    // anywhere), org.postgresql (vendor types leaking into domain code — PSQLException handling is
    // exactly the temptation), com.zaxxer.hikari (a second pool, so a second credential), and
    // org.flywaydb (migrations run from application code rather than the one startup initializer).
    //
    // The current invariant permits these dependencies only in the two named persistence packages;
    // every other package remains deny-by-default.
    @ArchTest
    static final ArchRule jdbcIsConfinedToPersistencePackages =
            noClasses().that().resideOutsideOfPackage("com.solace.sm.dp.apply.persistence..")
                    .and().resideOutsideOfPackage("com.solace.sm.cp.persistence..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "java.sql..", "javax.sql..", "org.springframework.jdbc..",
                            "org.postgresql..", "com.zaxxer.hikari..", "org.flywaydb..");

    // The apply MODEL is framework-free (eda-walking-skeleton §3; the plan's wave-B constraint).
    //
    // Narrower than jdbcIsConfinedToPersistencePackages and pointed the other way: that rule keeps JDBC
    // OUT of everything except the two persistence packages, this one keeps Spring AND JDBC out of one package
    // specifically. The model is the vocabulary the engine, the repositories and — later — the API
    // and the event mappers all share, so a framework import there is inherited by every consumer,
    // including modules that must never see spring-jdbc at all. java.sql/javax.sql are listed as well
    // as Spring because the model's own temptation is `java.sql.Timestamp` on a row record.
    @ArchTest
    static final ArchRule applyModelIsFrameworkFree =
            noClasses().that().resideInAPackage("com.solace.sm.dp.apply.model..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..", "java.sql..", "javax.sql..");

    // BE-D12 §2.3's named obligation: "core apply packages must not depend on Docker (or any
    // adapter's client library) types, with a planted-violation proof, exactly as
    // onlyMeshbridgeTouchesJcsmp confines JCSMP today."
    //
    // Package-scoped rather than class-scoped, and for the same reason the JCSMP rule is: it must
    // cover the whole client surface, transport and request builders alike, not the handful of
    // types today's adapter happens to name.
    @ArchTest
    static final ArchRule onlyTheDockerAdapterTouchesDockerJava =
            noClasses().that().resideOutsideOfPackage("com.solace.sm.dp.apply.runtime.docker..")
                    .should().dependOnClassesThat().resideInAPackage("com.github.dockerjava..");

    // The seam points ONE way. An adapter that could reach the engine or the journal would be able
    // to decide its own outcome, which is the whole thing BE-D12 §2.1 puts behind an interface:
    // "the core apply engine speaks only these; everything runtime-specific lives behind the seam"
    // is a claim about the direction of the arrow, and this is the arrow.
    @ArchTest
    static final ArchRule theRuntimeSeamNeverReachesBackIntoTheEngine =
            noClasses().that().resideInAPackage("com.solace.sm.dp.apply.runtime..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.solace.sm.dp.apply.engine..", "com.solace.sm.dp.apply.persistence..");

    // Plan 004's carried-forward watch item, in its own words: "ApplyEngineConfiguration's javadoc
    // states the direction as verified by inspection (no apply.engine import under
    // apply/persistence/), but no rule enforces it the way jdbcIsConfinedToApplyPersistence
    // enforces the data-plane/persistence boundary. -> Plan 005 - needs its own planted-violation
    // fixture and review." Here it is.
    @ArchTest
    static final ArchRule persistenceNeverReachesUpIntoTheEngine =
            noClasses().that().resideInAPackage("com.solace.sm.dp.apply.persistence..")
                    .should().dependOnClassesThat().resideInAPackage("com.solace.sm.dp.apply.engine..");

    // ---------------------------------------------------------------------------------------
    // Publisher inventory (design §5.2). Every concrete EventPublisher implementation must be
    // listed here, and listing one is a PROMISE that its module carries a test class extending
    // AbstractEventPublisherContractTest over it. arch-tests cannot see other modules' test
    // classes, so the pairing itself is reviewed at the moment this set is edited — which is
    // exactly what this check exists to force: adding an implementation without touching this
    // file is impossible, and touching this file is what puts the kit obligation in front of
    // the reviewer.
    // ---------------------------------------------------------------------------------------

    /** Kit-certified implementations. Extend ONLY together with a new kit subclass (design §5.2). */
    private static final Set<String> PUBLISHER_IMPLEMENTATIONS = Set.of(
            "com.solace.sm.dp.meshbridge.JcsmpEventPublisher",
            "com.solace.sm.events.publisher.AfterCommitEventPublisher");

    private static Set<String> concretePublisherImplementations(JavaClasses classes) {
        return classes.stream()
                .filter(c -> c.isAssignableTo(EventPublisher.class))
                .filter(c -> !c.isInterface() && !c.getModifiers().contains(
                        com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT))
                .map(c -> c.getName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyEventPublisherImplementationIsInTheCertifiedInventory() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(new ExcludeArchFixtures()).importPackages("com.solace.sm");
        assertEquals(new TreeSet<>(PUBLISHER_IMPLEMENTATIONS),
                concretePublisherImplementations(production),
                "the EventPublisher implementation set moved. A new implementation ships WITH a test "
                        + "class extending AbstractEventPublisherContractTest in its own module "
                        + "(design §5.2) — add both, then list the class here. Removing one: delist "
                        + "it and delete its kit subclass in the same change.");
    }

    // ---------------------------------------------------------------------------------------
    // Runtime adapter inventory (plan 005 Task 2's contract kit). Same purpose and same shape as
    // the publisher inventory above: adding an implementation without touching this file is
    // impossible, and touching this file is what puts the kit obligation in front of a reviewer.
    // ---------------------------------------------------------------------------------------

    /** Kit-certified adapters. Extend ONLY together with a subclass of
     *  AbstractRuntimeAdapterContractTest (plan 005 Task 2). */
    private static final Set<String> RUNTIME_ADAPTER_IMPLEMENTATIONS = Set.of(
            "com.solace.sm.dp.apply.runtime.docker.DockerAdapter",
            "com.solace.sm.dp.apply.runtime.ssh.SshDockerRuntimeAdapter");

    private static Set<String> concreteRuntimeAdapters(JavaClasses classes) {
        return classes.stream()
                .filter(c -> c.isAssignableTo(RuntimeAdapter.class))
                .filter(c -> !c.isInterface() && !c.getModifiers().contains(
                        com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT))
                .map(c -> c.getName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyRuntimeAdapterImplementationIsInTheCertifiedInventory() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(new ExcludeArchFixtures()).importPackages("com.solace.sm");
        assertEquals(new TreeSet<>(RUNTIME_ADAPTER_IMPLEMENTATIONS),
                concreteRuntimeAdapters(production),
                "the RuntimeAdapter implementation set moved. A new adapter ships WITH a test class "
                        + "extending AbstractRuntimeAdapterContractTest in dp-core (plan 005 Task 2) "
                        + "— add both, then list the class here. Removing one: delist it and delete "
                        + "its kit subclass in the same change.");

        // InMemoryRuntimeAdapter is TEST scope in dp-core, so it must not be on arch-tests'
        // production import at all. Asserted rather than assumed: an inventory quietly satisfied by
        // a fake would certify nothing. (arch-tests depends on dp-core's main artifact only.)
        assertFalse(production.stream().anyMatch(c -> c.getName()
                        .equals("com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter")),
                "the in-memory fake must stay test-scope in dp-core — nothing in production may "
                        + "resolve a fake adapter, and the inventory above must not be able to see "
                        + "one");
    }

    // ---------------------------------------------------------------------------------------
    // Proof that each rule fires. Without these the rules above are decoration.
    // ---------------------------------------------------------------------------------------

    /** Every class under {@code com.solace.sm}, fixtures INCLUDED. */
    private static JavaClasses withPlantedViolations() {
        return new ClassFileImporter().importPackages("com.solace.sm");
    }

    @Test
    void ruleCatchesAPlantedViolation() {
        AssertionError failure =
                assertThrows(AssertionError.class, () -> cpNeverImportsDp.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains("com.solace.sm.cp.archfixture.BadCpClass"),
                "cpNeverImportsDp must fail because of the planted fixture, not for some other reason. Was: "
                        + failure.getMessage());
    }

    @Test
    void dpRuleCatchesAPlantedViolation() {
        AssertionError failure =
                assertThrows(AssertionError.class, () -> dpNeverImportsCp.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains("com.solace.sm.dp.archfixture.BadDpClass"),
                "dpNeverImportsCp must fail because of the planted fixture, not for some other reason. Was: "
                        + failure.getMessage());
    }

    @Test
    void gatewayRuleCatchesAPlantedViolation() {
        AssertionError failure =
                assertThrows(AssertionError.class, () -> gatewayUsesApisOnly.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains("com.solace.sm.gateway.archfixture.BadGatewayClass"),
                "gatewayUsesApisOnly must fail because of the planted fixture. Was: " + failure.getMessage());

        // ...and must NOT reject the legal cp-api / dp-api edges, or it would be a rule that
        // rejects everything and proves nothing.
        //
        // The absence assertion below is only meaningful if the class was actually analysed —
        // otherwise it passes for free the day LegalGatewayClass stops being imported (renamed,
        // moved, or swept up by a broadened exclusion). Establish presence first.
        assertTrue(
                withPlantedViolations().stream()
                        .anyMatch(c -> c.getName().equals("com.solace.sm.gateway.archfixture.LegalGatewayClass")),
                "LegalGatewayClass must be in the analysed set, or the assertion below proves nothing");
        assertFalse(failure.getMessage().contains("LegalGatewayClass"),
                "gatewayUsesApisOnly must allow cp-api/dp-api references. Was: " + failure.getMessage());
    }

    @Test
    void eventsCpRuleCatchesAPlantedViolation() {
        AssertionError failure =
                assertThrows(AssertionError.class, () -> eventsNeverImportCp.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains("com.solace.sm.events.archfixture.BadEventsCpClass"),
                "eventsNeverImportCp must fail because of the planted fixture, not for some other reason. Was: "
                        + failure.getMessage());
    }

    @Test
    void eventsDpRuleCatchesAPlantedViolation() {
        AssertionError failure =
                assertThrows(AssertionError.class, () -> eventsNeverImportDp.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains("com.solace.sm.events.archfixture.BadEventsDpClass"),
                "eventsNeverImportDp must fail because of the planted fixture, not for some other reason. Was: "
                        + failure.getMessage());
    }

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

    @Test
    void inventoryCatchesAPlantedUnkittedPublisher() {
        Set<String> withFixture = concretePublisherImplementations(withPlantedViolations());
        assertTrue(withFixture.contains("com.solace.sm.events.archfixture.BadUnkittedPublisherClass"),
                "the scan must SEE the planted implementation, or the inventory can be grown "
                        + "invisibly. Saw: " + withFixture);
        assertFalse(PUBLISHER_IMPLEMENTATIONS.contains(
                        "com.solace.sm.events.archfixture.BadUnkittedPublisherClass"),
                "the planted class must not be in the certified list — that would defeat the demo");
    }

    @Test
    void jcsmpRuleCatchesAPlantedViolation() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> onlyMeshbridgeTouchesJcsmp.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains("com.solace.sm.cp.archfixture.BadCpJcsmpClass"),
                "onlyMeshbridgeTouchesJcsmp must fail because of the planted fixture, not for some "
                        + "other reason. Was: " + failure.getMessage());
    }

    @Test
    void jdbcConfinementRuleCatchesPlantedViolationsWithoutRejectingEitherLegalPackage() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> jdbcIsConfinedToPersistencePackages.check(withPlantedViolations()));
        assertTrue(failure.getMessage()
                        .contains("com.solace.sm.dp.apply.archfixture.BadJdbcOutsidePersistenceClass"),
                "jdbcIsConfinedToPersistencePackages must fail because of the planted fixture, not for "
                        + "some other reason. Was: " + failure.getMessage());
        assertTrue(failure.getMessage()
                        .contains("com.solace.sm.cp.authz.archfixture.BadCpJdbcOutsidePersistenceClass"),
                "the CP authz fixture must still be rejected. Was: " + failure.getMessage());

        // Presence first, then absence — otherwise the assertion below passes for free the day the
        // legal fixture stops being imported (the same trap gatewayUsesApisOnly's proof avoids).
        assertTrue(withPlantedViolations().stream().anyMatch(c -> c.getName().equals(
                        "com.solace.sm.dp.apply.persistence.archfixture.LegalPersistenceJdbcClass")),
                "LegalPersistenceJdbcClass must be in the analysed set, or the assertion below "
                        + "proves nothing");
        assertFalse(failure.getMessage().contains("LegalPersistenceJdbcClass"),
                "the persistence package itself must be allowed to hold JDBC types — that is the "
                        + "point of confining them there. Was: " + failure.getMessage());
        assertTrue(withPlantedViolations().stream().anyMatch(c -> c.getName().equals(
                        "com.solace.sm.cp.persistence.archfixture.LegalCpPersistenceJdbcClass")),
                "LegalCpPersistenceJdbcClass must be in the analysed set, or the assertion below "
                        + "proves nothing");
        assertFalse(failure.getMessage().contains("LegalCpPersistenceJdbcClass"),
                "CP JDBC must be legal only inside cp.persistence. Was: " + failure.getMessage());
    }

    /**
     * Answers the question the plan asked rather than assuming it: does the EXISTING confinement rule
     * already cover the new {@code ..apply.engine} package, so that no second rule is needed?
     *
     * <p>It does. The rule is phrased as "everything outside {@code com.solace.sm.dp.apply.persistence}",
     * and a planted {@code java.sql.Connection} in {@code ..dp.apply.engine.archfixture} makes it fire
     * by name. Recorded as a test rather than as a comment, because "the rule would cover it" is the
     * kind of claim that is true right up until someone rewrites the package predicate.
     */
    @Test
    void theExistingJdbcConfinementRuleAlreadyCoversTheApplyEnginePackage() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> jdbcIsConfinedToPersistencePackages.check(withPlantedViolations()));
        assertTrue(failure.getMessage()
                        .contains("com.solace.sm.dp.apply.engine.archfixture.BadEngineJdbcClass"),
                "the engine fixture must trip the EXISTING rule — if it does not, ..apply.engine "
                        + "needs a rule of its own. Was: " + failure.getMessage());

        // …and the same rule must NOT be what catches the model fixture, which holds no JDBC type:
        // that one belongs to applyModelIsFrameworkFree, below.
        assertFalse(failure.getMessage().contains("BadFrameworkModelClass"),
                "the model fixture holds a spring-tx type, not a JDBC one — if this rule catches it "
                        + "too, the two proofs are not independent. Was: " + failure.getMessage());
    }

    @Test
    void modelFrameworkRuleCatchesAPlantedViolationWithoutRejectingTheRealModel() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> applyModelIsFrameworkFree.check(withPlantedViolations()));
        assertTrue(failure.getMessage()
                        .contains("com.solace.sm.dp.apply.model.archfixture.BadFrameworkModelClass"),
                "applyModelIsFrameworkFree must fail because of the planted fixture, not for some "
                        + "other reason. Was: " + failure.getMessage());

        // Presence first, then absence — the same trap the gateway and JDBC proofs avoid.
        assertTrue(withPlantedViolations().stream().anyMatch(
                        c -> c.getName().equals("com.solace.sm.dp.apply.model.ApplyCommand")),
                "the real model must be in the analysed set, or the assertion below proves nothing");
        assertFalse(failure.getMessage().contains("com.solace.sm.dp.apply.model.ApplyCommand"),
                "the production model must be clean — a rule that also rejects the code it governs "
                        + "is a broken rule, not a strict one. Was: " + failure.getMessage());
    }

    @Test
    void archTestsActuallySeesTheNewPersistencePackageOfDpCore() {
        // The rule above is only meaningful if arch-tests imports dp-core's production classes. It
        // does (dp-core is a test-scope dependency), but the new package arrived after the module
        // list was written, so assert it rather than assume it.
        JavaClasses production = new ClassFileImporter()
                .withImportOption(new ExcludeArchFixtures()).importPackages("com.solace.sm");
        assertTrue(production.stream().anyMatch(c -> c.getName().equals(
                        "com.solace.sm.dp.apply.persistence.SmDataMigrator")),
                "dp-core's apply-persistence package must be on the arch-tests classpath");
    }

    @Test
    void dockerConfinementCatchesAPlantedViolationWithoutRejectingTheAdapterPackage() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> onlyTheDockerAdapterTouchesDockerJava.check(withPlantedViolations()));
        assertTrue(failure.getMessage()
                        .contains("com.solace.sm.dp.apply.engine.archfixture.BadEngineDockerClass"),
                "the rule must fail because of the planted fixture. Was: " + failure.getMessage());

        // Presence first, then absence — the trap gatewayUsesApisOnly's proof avoids.
        assertTrue(withPlantedViolations().stream().anyMatch(c -> c.getName().equals(
                        "com.solace.sm.dp.apply.runtime.docker.archfixture.LegalDockerClientClass")),
                "LegalDockerClientClass must be in the analysed set, or the assertion below proves "
                        + "nothing");
        assertFalse(failure.getMessage().contains("LegalDockerClientClass"),
                "the adapter package itself must be allowed to hold Docker types — that is the "
                        + "point of confining them there. Was: " + failure.getMessage());

        // …and the real adapter must be clean, or the rule rejects the code it governs.
        assertFalse(failure.getMessage().contains("com.solace.sm.dp.apply.engine.ApplyExecutor"),
                "the engine must not be reaching Docker on its own. Was: " + failure.getMessage());
        assertFalse(failure.getMessage().contains("com.solace.sm.dp.apply.runtime.RuntimeAdapter"),
                "nor may the seam itself. Was: " + failure.getMessage());
    }

    @Test
    void theSeamDirectionRuleCatchesAPlantedViolationWithoutRejectingTheSeam() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> theRuntimeSeamNeverReachesBackIntoTheEngine.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains(
                        "com.solace.sm.dp.apply.runtime.archfixture.BadRuntimeSeamEngineClass"),
                "theRuntimeSeamNeverReachesBackIntoTheEngine must fail because of the planted "
                        + "fixture. Was: " + failure.getMessage());

        assertTrue(withPlantedViolations().stream().anyMatch(c -> c.getName()
                        .equals("com.solace.sm.dp.apply.runtime.docker.DockerAdapter")),
                "the real adapter must be in the analysed set, or the assertion below proves nothing");
        assertFalse(failure.getMessage().contains("com.solace.sm.dp.apply.runtime.docker.DockerAdapter"),
                "the shipped adapter must be clean — the seam already points one way, and this rule "
                        + "is what keeps it there. Was: " + failure.getMessage());
    }

    @Test
    void persistenceDirectionRuleCatchesAPlantedViolationWithoutRejectingThePackage() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> persistenceNeverReachesUpIntoTheEngine.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains(
                        "com.solace.sm.dp.apply.persistence.archfixture.BadPersistenceEngineClass"),
                "persistenceNeverReachesUpIntoTheEngine must fail because of the planted fixture. "
                        + "Was: " + failure.getMessage());

        assertTrue(withPlantedViolations().stream().anyMatch(c -> c.getName()
                        .equals("com.solace.sm.dp.apply.persistence.AttemptRepository")),
                "the real repositories must be in the analysed set, or the assertion below proves "
                        + "nothing");
        assertFalse(failure.getMessage().contains("com.solace.sm.dp.apply.persistence.AttemptRepository"),
                "the shipped repositories must be clean — this rule turns a javadoc claim into a "
                        + "gate, it does not retrofit an exception. Was: " + failure.getMessage());
    }

    @Test
    void adapterInventoryCatchesAPlantedUnkittedAdapter() {
        Set<String> withFixture = concreteRuntimeAdapters(withPlantedViolations());
        assertTrue(withFixture.contains(
                        "com.solace.sm.dp.apply.runtime.archfixture.BadUnkittedAdapterClass"),
                "the scan must SEE the planted adapter, or the inventory can be grown invisibly. "
                        + "Saw: " + withFixture);
        assertFalse(RUNTIME_ADAPTER_IMPLEMENTATIONS.contains(
                        "com.solace.sm.dp.apply.runtime.archfixture.BadUnkittedAdapterClass"),
                "the planted class must not be in the certified list — that would defeat the demo");
    }

    /** The filter itself must actually remove the fixtures, or the exclusion is a false green. */
    @Test
    void fixturesAreExcludedFromTheRealRun() {
        JavaClasses production =
                new ClassFileImporter().withImportOption(new ExcludeArchFixtures()).importPackages("com.solace.sm");
        assertTrue(production.stream().anyMatch(c -> c.getName().equals("com.solace.sm.cp.CpCoreMarker")),
                "production import must still contain the module markers");
        assertFalse(production.stream().anyMatch(c -> c.getPackageName().contains("archfixture")),
                "production import must contain no archfixture classes");
    }

    /**
     * Keeps the planted-violation fixtures out of the real {@code @ArchTest} run. Matches on the
     * class file's location, so it works whether the fixtures are read from {@code target/test-classes}
     * or from a jar.
     */
    public static final class ExcludeArchFixtures implements ImportOption {

        @Override
        public boolean includes(Location location) {
            return !location.contains("/archfixture/");
        }
    }
}
