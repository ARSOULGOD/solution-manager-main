package com.solace.sm.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.agent.AgentRegistry;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Collection;
import org.junit.jupiter.api.Test;

/**
 * Plan 015 T2.2 review finding 10 (I5). Every {@link AgentRegistry} read is bound by organization except one:
 * {@code findAll}, which backs {@code AgentRegistrationSource.current}, a reconcile that spans sessions and names no
 * organization. Like {@code resolveByThumbprint} (TenantEnumerationBoundaryTest) it is named here as the single allowed unbound
 * read, and its single allowed caller is the service that answers that port.
 */
@AnalyzeClasses(packages = "com.solace.sm", importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class AgentRegistryBoundaryTest {

    private static final String ONLY_CALLER = "com.solace.sm.cp.agent.ControlPlaneAgentGatewayService";

    @ArchTest
    static final ArchRule onlyTheGatewayServiceCallsTheUnboundRegistryRead =
            noClasses().that().doNotHaveFullyQualifiedName(ONLY_CALLER)
                    .should().callMethod(AgentRegistry.class, "findAll", Collection.class);

    @Test
    void aPlantedSecondCallerOfTheUnboundReadTurnsTheRuleRed() {
        AssertionError failure = assertThrows(AssertionError.class, () -> onlyTheGatewayServiceCallsTheUnboundRegistryRead
                .check(new ClassFileImporter().importPackages("com.solace.sm")));
        assertTrue(failure.getMessage().contains("BadFindAllCaller"), failure::getMessage);
    }

    @Test
    void theRealCallerExistsSoTheRuleProvesSomething() {
        JavaClasses production = new ClassFileImporter().withImportOption(new PlaneBoundaryTest.ExcludeArchFixtures())
                .importPackages("com.solace.sm");
        assertTrue(production.contain(ONLY_CALLER));
        onlyTheGatewayServiceCallsTheUnboundRegistryRead.check(production);
        assertTrue(production.get(ONLY_CALLER).getMethodCallsFromSelf().stream()
                .anyMatch(call -> call.getName().equals("findAll")), "the service really does call it");
    }
}
