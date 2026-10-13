package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Supplies the resolved {@link Session} to any handler method parameter typed {@code Session}.
 *
 * <p>Reads the value {@link AuthenticationInterceptor} placed on the request — argument
 * resolution runs strictly after {@code preHandle}, so a handler declaring this parameter can
 * only be reached once the session is genuinely resolved.
 */
public final class SessionArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Session.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();
        Object session = request.getAttribute(AuthenticationInterceptor.SESSION_ATTRIBUTE);
        if (!(session instanceof Session resolved)) {
            throw new GatewayRejectionException(ErrorCode.UNAUTHENTICATED);
        }
        return resolved;
    }
}
