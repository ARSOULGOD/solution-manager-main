package com.solace.sm.arch;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
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
 * The {@code agent-gateway} module boundary (AGW-D2, plan 015 Global constraint 4, T1.2), as executable rules, each
 * with a planted violation proving it fires. Adapted from the agent branch's PlaneBoundaryTest rules, which had no
 * planted proof. {@code dataPlaneAndConsoleDoNotTouchGrpc} is renamed {@code onlyAgentGatewayTouchesGrpc} and widened
 * from {dp, gateway} to every module but the gateway (P15-22).
 */
@AnalyzeClasses(packages = {"com.solace.sm", "solace.sm.agentgateway"}, importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class AgentGatewayBoundaryTest {

    @ArchTest
    static final ArchRule dpNeverImportsAgentGateway =
            noClasses().that().resideInAPackage("com.solace.sm.dp..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.solace.sm.agw..", "solace.sm.agentgateway..");

    @ArchTest
    static final ArchRule cpNeverImportsAgentGateway =
            noClasses().that().resideInAPackage("com.solace.sm.cp..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.solace.sm.agw..", "solace.sm.agentgateway..");

    @ArchTest
    static final ArchRule agentGatewayUsesDpApiOnly =
            noClasses().that().resideInAPackage("com.solace.sm.agw..")
                    .should().dependOnClassesThat(
                            resideInAPackage("com.solace.sm..")
                                    .and(not(resideInAPackage("com.solace.sm.agw..")))
                                    .and(not(resideInAPackage("com.solace.sm.dp.api.."))));

    @ArchTest
    static final ArchRule agentGatewayHoldsNoDatasource =
            noClasses().that().resideInAPackage("com.solace.sm.agw..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "java.sql..", "javax.sql..", "jakarta.persistence..", "javax.persistence..",
                            "org.springframework.jdbc..", "org.springframework.orm..", "org.springframework.data..",
                            "org.hibernate..", "org.jooq..", "io.r2dbc..", "org.flywaydb..", "com.zaxxer.hikari..",
                            "org.postgresql..");

    @ArchTest
    static final ArchRule onlyAgentGatewayTouchesGrpc =
            noClasses().that().resideOutsideOfPackages("com.solace.sm.agw..", "solace.sm.agentgateway..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.grpc..", "com.google.protobuf..", "solace.sm.agentgateway..");

    /** Plan 015 T2.1b fix round, finding 5: the organization never reaches the gateway, so it can never be echoed from it. */
    @ArchTest
    static final ArchRule agentGatewayNeverReadsTheTenant =
            noClasses().that().resideInAPackage("com.solace.sm.agw..")
                    .should().callMethod(com.solace.sm.dp.api.agw.AgentIdentity.class, "tenantId");

    /** T2.1a review finding 12: these are the data plane's own types (tenant-carrying refs, engine intents, the status read). */
    @ArchTest
    static final ArchRule agentGatewayNeverNamesDataPlaneInternals =
            noClasses().that().resideInAPackage("com.solace.sm.agw..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("com.solace.sm.dp.api.agw.AgentPlacementRef")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("com.solace.sm.dp.api.agw.AgentCommandIntent")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("com.solace.sm.dp.api.agw.AgentObservedStatePort")
                    // plan 015 T4.3: the console's read of a connection, tenant-keyed; a read for the console, not a gateway port
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("com.solace.sm.dp.api.agent.AgentSessionFacts");

    @Test
    void theGatewayNeverReadsTheTenantAndTheRuleCatchesAPlantedRead() {
        mustReport(agentGatewayNeverReadsTheTenant, "PlantedAgwReadsTenant");
    }

    @Test
    void theGatewayNeverNamesDataPlaneInternalsAndTheRuleCatchesAPlantedPlacementRef() {
        mustReport(agentGatewayNeverNamesDataPlaneInternals, "PlantedAgwNamesAPlacementRef");
    }

    @Test
    void theGatewayNeverNamesTheConsolesSessionFactsAndTheRuleCatchesAPlantedUse() {
        mustReport(agentGatewayNeverNamesDataPlaneInternals, "PlantedAgwNamesSessionFacts");
    }

    private static JavaClasses withPlantedViolations() {
        return new ClassFileImporter().importPackages("com.solace.sm", "solace.sm.agentgateway");
    }

    private static void mustReport(ArchRule rule, String fixture) {
        JavaClasses all = withPlantedViolations();
        assertTrue(all.stream().anyMatch(c -> c.getName().endsWith("." + fixture)),
                fixture + " must be in the analysed set, or the proof proves nothing");
        AssertionError e = assertThrows(AssertionError.class, () -> rule.check(all));
        assertTrue(e.getMessage().contains(fixture),
                "the rule must fail because of " + fixture + ", not for another reason. Was: " + e.getMessage());
        assertFalse(e.getMessage().contains("LegalAgwUsesDpApiAndGrpc"),
                "the rule must not reject the legal dp-api and gRPC edges. Was: " + e.getMessage());
    }

    @Test
    void dpApiOnlyRuleCatchesAPlantedDpCoreImport() {
        mustReport(agentGatewayUsesDpApiOnly, "PlantedAgwImportsDpCore");
    }

    @Test
    void dpApiOnlyRuleCatchesAPlantedControlPlaneImport() {
        mustReport(agentGatewayUsesDpApiOnly, "PlantedAgwImportsCp");
    }

    @Test
    void noDatasourceRuleCatchesAPlantedDataSource() {
        mustReport(agentGatewayHoldsNoDatasource, "PlantedAgwHoldsDataSource");
    }

    /**
     * Plan 016 task 7: the log broker and the Logs RPC live in the gateway, so the datasource and dp-api-only rules must hold over
     * them. The rules are proven to fire by the planted violations above; this proves the new classes are inside the analysed set and
     * pass them, and that the AgentLogPort surface they implement names no gRPC type.
     */
    @Test
    void agentGatewayHoldsNoDatasourceStillHoldsOverTheLogBroker() {
        JavaClasses real = new ClassFileImporter().withImportOption(new PlaneBoundaryTest.ExcludeArchFixtures())
                .importPackages("com.solace.sm", "solace.sm.agentgateway");
        assertTrue(real.stream().anyMatch(c -> c.getName().equals("com.solace.sm.agw.AgentLogBroker")),
                "the broker must be in the analysed set, or this proves nothing");
        agentGatewayHoldsNoDatasource.check(real);
        agentGatewayUsesDpApiOnly.check(real);
        onlyAgentGatewayTouchesGrpc.check(real);
    }

    @Test
    void dpNeverImportsAgentGatewayCatchesAPlantedImport() {
        mustReport(dpNeverImportsAgentGateway, "PlantedDpImportsAgw");
    }

    @Test
    void dpNeverImportsTheWireMessageClassesCatchesAPlantedImport() {
        mustReport(dpNeverImportsAgentGateway, "PlantedDpImportsWireMessage");
    }

    @Test
    void cpNeverImportsAgentGatewayCatchesAPlantedImport() {
        mustReport(cpNeverImportsAgentGateway, "PlantedCpImportsAgw");
    }

    @Test
    void dpApiOnlyRuleCatchesAPlantedImportOfAnotherModule() {
        mustReport(agentGatewayUsesDpApiOnly, "PlantedAgwImportsEvents");
    }

    @Test
    void onlyAgentGatewayTouchesGrpcCatchesAPlantedProtobufImport() {
        mustReport(onlyAgentGatewayTouchesGrpc, "PlantedLauncherTouchesProtobuf");
    }

    @Test
    void theGeneratedWireClassesAreInTheAnalysedSet() {
        // The proto has no java_package: the generated classes are in solace.sm.agentgateway.., outside com.solace.sm.
        // The rules above name that package; this proves the class set they run over actually contains it.
        java.util.List<String> packages = java.util.Arrays.asList(
                AgentGatewayBoundaryTest.class.getAnnotation(AnalyzeClasses.class).packages());
        assertTrue(packages.contains("solace.sm.agentgateway"), "@AnalyzeClasses must include the wire package: " + packages);
        assertTrue(new ClassFileImporter().importPackages(packages.toArray(new String[0])).stream()
                        .anyMatch(c -> c.getName().equals("solace.sm.agentgateway.v1draft.AgentGatewayOuterClass")),
                "the generated message class must be imported for analysis");
    }

    @Test
    void theDatasourceRuleNamesTheOrmAndQueryBuilderPackages() {
        // These packages are not on arch-tests' classpath, so no compiled fixture can plant them; the rule text is asserted.
        String d = agentGatewayHoldsNoDatasource.getDescription();
        for (String p : new String[] {"org.springframework.orm..", "org.hibernate..", "javax.persistence..", "io.r2dbc..", "org.jooq.."}) {
            assertTrue(d.contains(p), p + " missing from: " + d);
        }
    }

    @Test
    void onlyAgentGatewayTouchesGrpcCatchesAPlantedLauncherImport() {
        mustReport(onlyAgentGatewayTouchesGrpc, "PlantedLauncherTouchesGrpc");
    }
}
