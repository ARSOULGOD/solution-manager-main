package com.solace.sm.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * The broker push path's own boundaries (plan 011 P11-5.1), each with a planted-violation proof (the repo's guard-self-test
 * discipline: a rule that cannot fire is decoration). The path's general boundaries are the existing rules'
 * ({@code onlyMeshbridgeTouchesJcsmp}, {@code meshbridgeHoldsNoOwnMapper}, {@code jdbcIsConfinedToPersistencePackages}); these
 * three are the ones it adds:
 * <ul>
 *   <li><b>No fifth caller of the tenant list</b> (OQ-6 (a), {@code OR-92}): the push reconcile is the metrics loop's third
 *       per-tenant step, fed by the loop's one {@code tenantsWithActiveInstances()} call.</li>
 *   <li><b>JCSMP stays in the SDK-facing classes</b> and bytes plus a topic cross out: nothing else in
 *       {@code meshbridge.ingest} may name an SDK type, so {@code RawBrokerMessage}, the sink and the control surface are
 *       SDK-free by construction.</li>
 *   <li><b>The plan, the decoder, the attribution and the service never reach the secret store</b>: a secret-typed
 *       {@code instanceKey} can therefore never be resolved into anything (I6); the credential is resolved in one place, the
 *       session manager.</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.solace.sm", importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class PushPathBoundaryTest {

    @ArchTest
    static final ArchRule pushPathAsksNoOneForTheTenantList =
            noClasses().that().resideInAnyPackage("com.solace.sm.dp.metrics.push..", "com.solace.sm.dp.meshbridge.ingest..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            "com.solace.sm.dp.apply.persistence.TenantEnumerationRepository");

    @ArchTest
    static final ArchRule onlyTheSdkFacingClassesNameAnSdkType =
            noClasses().that().resideInAPackage("com.solace.sm.dp.meshbridge.ingest..")
                    .and().haveNameNotMatching("com\\.solace\\.sm\\.dp\\.meshbridge\\.ingest\\.Jcsmp.*")
                    .should().dependOnClassesThat().resideInAPackage("com.solacesystems..");

    @ArchTest
    static final ArchRule thePushServicesNeverReachTheSecretStore =
            noClasses().that().resideInAPackage("com.solace.sm.dp.metrics.push..")
                    .should().dependOnClassesThat().resideInAPackage("com.solace.sm.dp.secret..");

    private static JavaClasses withPlantedViolations() {
        return new ClassFileImporter().importPackages("com.solace.sm");
    }

    private static void firesOn(ArchRule rule, String fixture) {
        AssertionError failure = assertThrows(AssertionError.class, () -> rule.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains(fixture), "the rule must fail because of the planted fixture " + fixture
                + ", not for some other reason. Was: " + failure.getMessage());
    }

    @Test
    void theTenantListRuleCatchesAPlantedFifthCaller() {
        firesOn(pushPathAsksNoOneForTheTenantList, "com.solace.sm.dp.metrics.push.archfixture.BadPushTenantListClass");
    }

    @Test
    void theSdkRuleCatchesAPlantedLeakAndSparesTheSdkFacingClasses() {
        firesOn(onlyTheSdkFacingClassesNameAnSdkType, "com.solace.sm.dp.meshbridge.ingest.archfixture.BadIngestJcsmpLeakClass");
        AssertionError failure = assertThrows(AssertionError.class,
                () -> onlyTheSdkFacingClassesNameAnSdkType.check(withPlantedViolations()));
        assertTrue(withPlantedViolations().stream()
                        .anyMatch(c -> c.getName().equals("com.solace.sm.dp.meshbridge.ingest.JcsmpSampleSubscriber")),
                "the SDK-facing class must be in the analysed set, or the absence below proves nothing");
        assertFalse(failure.getMessage().contains("JcsmpSampleSubscriber"),
                "the rule spares the SDK-facing classes: " + failure.getMessage());
    }

    @Test
    void theSecretRuleCatchesAPlantedReader() {
        firesOn(thePushServicesNeverReachTheSecretStore, "com.solace.sm.dp.metrics.push.archfixture.BadPushSecretReaderClass");
    }
}
