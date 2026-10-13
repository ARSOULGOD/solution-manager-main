package com.solace.sm.gateway.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Wraps every UNSAFE request in a {@link PeekableBodyRequest} (plan 013 T1.4), so a {@link ResourceResolver} that must
 * authorize at a scope the body names can read it. The wrapper reads nothing unless a resolver peeks, so for every other
 * route this filter is a pass-through; it matches on no path, which is why no variant of a path (a trailing {@code ;x=y},
 * a repeated slash) can reach a body-scoped handler without it. {@link OwnershipScopeResolver} fails CLOSED when the
 * request it is handed is not one of these.
 */
final class PeekableBodyFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        PeekableBodyRequest peekable = new PeekableBodyRequest(request);
        try {
            chain.doFilter(peekable, response);
        } finally {
            // An async request may still be read after this returns; its buffer is left for the dispatch that ends it.
            if (!request.isAsyncStarted()) {
                peekable.scrub();
            }
        }
    }
}
