package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.cp.api.audit.RequestOrigin;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Unit-level proof, independent of MockMvc, that {@link RequestOriginFactory} reads the server
 * correlation id from the current request's attribute and delegates address resolution — never
 * inventing either value itself.
 */
class RequestOriginFactoryTest {

    @AfterEach
    void resetContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void createsFromTheCurrentRequestsCorrelationIdAndResolvedAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");
        UUID correlationId = UUID.randomUUID();
        request.setAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE, correlationId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        RequestOriginFactory factory = new RequestOriginFactory(new ClientAddressResolver(new GatewayProperties()));

        RequestOrigin origin = factory.create();

        assertEquals("203.0.113.9", origin.clientAddress());
        assertEquals(correlationId, origin.correlationId());
    }

    @Test
    void throwsIfNoCorrelationIdAttributeIsPresentOnTheCurrentRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestOriginFactory factory = new RequestOriginFactory(new ClientAddressResolver(new GatewayProperties()));

        assertThrows(IllegalStateException.class, factory::create);
    }
}
