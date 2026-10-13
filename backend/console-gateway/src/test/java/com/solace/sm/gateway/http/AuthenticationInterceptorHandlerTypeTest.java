package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit-level proof that the interceptor's handler-type gate is fail-closed: the ONLY
 * non-{@link org.springframework.web.method.HandlerMethod} handler ever let through is
 * {@link NoRouteHandler} — every other handler this interceptor happens to see (a
 * {@code ResourceHttpRequestHandler}, a stray user {@code HandlerMapping}'s handler, anything
 * else) is rendered as {@code NOT_FOUND} directly (via {@link NoRouteHandler#render}, since
 * {@link GatewayErrorAdvice}'s {@code @ExceptionHandler} advice never applies to a
 * non-{@code HandlerMethod} handler) rather than silently authenticated-bypassed. This is
 * deliberately a direct unit test of {@link AuthenticationInterceptor#preHandle}, independent of
 * Spring wiring, because no {@code HandlerMapping} in the real test contexts ever actually
 * dispatches a non-{@code HandlerMethod}, non-{@code NoRouteHandler} handler through this
 * interceptor in practice (that is exactly what
 * {@link OperationCoverageValidatorTest} proves is refused at startup instead) — this test is the
 * one that pins the interceptor's OWN behaviour as a second, independent layer of defense.
 */
class AuthenticationInterceptorHandlerTypeTest {

    private final AuthenticationInterceptor interceptor;
    private final ObjectMapper mapper = new ObjectMapper();

    AuthenticationInterceptorHandlerTypeTest() {
        GatewayProperties properties = new GatewayProperties();
        properties.setTrustedOrigins(List.of("https://console.example.test"));
        // Neither test here ever reaches a HandlerMethod, so classification, authorization,
        // resource resolution and the rate gate never run — a null ApplicationContext is never
        // dereferenced, and a real (but never-invoked) AuthRateGate needs no fakes of its own.
        GatewayTestSupport.FakeAuthorizationService authorization = new GatewayTestSupport.FakeAuthorizationService();
        interceptor = new AuthenticationInterceptor(properties,
                new GatewayTestSupport.FakeSessionAuthentication(), new GatewayTestSupport.FakeForcedRotationGate(),
                authorization, authorization, null,
                new AuthRateGate(new ClientAddressResolver(properties), properties, System::nanoTime));
    }

    @Test
    void theNoRouteHandlerIsLetThrough() throws Exception {
        boolean result = interceptor.preHandle(
                new MockHttpServletRequest("GET", "/whatever"), new MockHttpServletResponse(), new NoRouteHandler());
        assertTrue(result);
    }

    @Test
    void anyOtherNonHandlerMethodHandlerIsRenderedAsNotFoundDirectly() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/whatever");
        UUID correlationId = UUID.randomUUID();
        request.setAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE, correlationId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean result = interceptor.preHandle(request, response, new Object());

        assertFalse(result);
        assertEquals(ErrorCode.NOT_FOUND.status(), response.getStatus());
        assertEquals("application/problem+json", response.getContentType());
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertEquals(ErrorCode.NOT_FOUND.name(), body.get("code").asText());
        assertEquals(correlationId.toString(), body.get("correlationId").asText());
    }
}
