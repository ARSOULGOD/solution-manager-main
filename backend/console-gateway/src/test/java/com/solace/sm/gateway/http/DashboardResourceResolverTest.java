package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.gateway.http.metrics.DashboardResourceResolver;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/** Plan 011 P11-7.2: a dashboard id is read from the PATH alone and names its instance with no read of anything. */
class DashboardResourceResolverTest {

    private final DashboardResourceResolver resolver = new DashboardResourceResolver();
    private final Session session = GatewayTestSupport.newSession(UUID.randomUUID(), UUID.randomUUID(), false);

    private MockHttpServletRequest request(String dashboardId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/console/v1/dashboards/x/widgets");
        if (dashboardId != null) {
            request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("dashboardId", dashboardId));
        }
        return request;
    }

    @Test
    void aDashboardIdResolvesToItsInstanceScopeFromThePathAlone() {
        UUID instance = UUID.randomUUID();
        MockHttpServletRequest request = request("db_" + instance);
        // A body naming ANOTHER instance, a query parameter and a header naming a third: none of them is read.
        request.setContent(("{\"query\":{\"selector\":{\"instanceIds\":[\"" + UUID.randomUUID() + "\"]}}}").getBytes());
        request.setParameter("instanceId", UUID.randomUUID().toString());
        request.addHeader("X-Instance", UUID.randomUUID().toString());
        ResourceRef resource = resolver.resolve(request, session);
        assertEquals(ResourceKind.INSTANCE, resource.kind());
        assertEquals(instance, resource.id());
        assertEquals(session.organizationId(), resource.tenant().value(), "the tenant is the session's (I5)");
    }

    @Test
    void aMalformedDashboardIdIs404LikeAnUnknownInstanceAndTheMessageNamesNoPath() {
        for (String bad : new String[] {"db_", "db_not-a-uuid", "xx_" + UUID.randomUUID(), UUID.randomUUID().toString(),
                "db_" + UUID.randomUUID().toString().toUpperCase(), "db_" + UUID.randomUUID() + " ", "DB_" + UUID.randomUUID()}) {
            ResourceNotFoundException refused = assertThrows(ResourceNotFoundException.class,
                    () -> resolver.resolve(request(bad), session), bad);
            assertEquals(false, String.valueOf(refused.getMessage()).contains(bad.length() > 3 ? bad.substring(3) : "~"),
                    "the exception is logged, so its message is never built from the path");
        }
        assertThrows(ResourceNotFoundException.class, () -> resolver.resolve(request(null), session));
    }
}
