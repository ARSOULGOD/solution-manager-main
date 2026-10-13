package com.solace.sm.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the test double every T3.2+ reach-handler test will lean on. The Reach javadoc says a handler
 * owes {@code authorizedSubset}; the double is how a handler test can tell that it paid. A double
 * that echoed its input would pass whether or not the handler called it.
 */
class FakeAuthorizationServiceTest {

    private final PrincipalRef principal = new PrincipalRef(UUID.randomUUID());

    @Test
    void theSubsetIsEmptyUnlessTheTestAuthorizesTheId() {
        GatewayTestSupport.FakeAuthorizationService fake = new GatewayTestSupport.FakeAuthorizationService();
        UUID allowed = UUID.randomUUID();
        UUID denied = UUID.randomUUID();
        fake.authorizedIds.add(allowed);

        assertThat(fake.authorizedSubset(principal, Permission.INSTANCE_VIEW, ResourceKind.INSTANCE,
                List.of(allowed, denied))).containsExactly(allowed);
        assertThat(new GatewayTestSupport.FakeAuthorizationService().authorizedSubset(
                principal, Permission.INSTANCE_VIEW, ResourceKind.INSTANCE, List.of(allowed, denied))).isEmpty();
    }

    @Test
    void everyCallIsRecordedWithTheVerbTheHandlerAskedFor() {
        GatewayTestSupport.FakeAuthorizationService fake = new GatewayTestSupport.FakeAuthorizationService();
        UUID id = UUID.randomUUID();

        fake.authorizedSubset(principal, Permission.CONFIG_APPLY, ResourceKind.ENVIRONMENT, List.of(id));

        assertThat(fake.subsetCalls).containsExactly(new GatewayTestSupport.FakeAuthorizationService.SubsetCall(
                principal, Permission.CONFIG_APPLY, ResourceKind.ENVIRONMENT, java.util.Set.of(id)));
    }
}
