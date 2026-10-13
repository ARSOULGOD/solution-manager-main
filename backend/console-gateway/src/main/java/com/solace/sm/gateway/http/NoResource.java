package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.session.Session;
import jakarta.servlet.http.HttpServletRequest;

/**
 * The default value of {@link ConsoleOperation#resource()}: "this operation declares no resource
 * resolver."
 *
 * <p>⛔ <b>Never instantiated and never registered as a bean.</b> It exists purely as a
 * type-level sentinel {@link OperationCoverageValidator} compares
 * {@link ConsoleOperation#resource()} against at startup: a CATALOG operation must name
 * something other than this (a real resolver), and a SESSION_ESTABLISHING or LOCAL operation must
 * name exactly this (nothing else — those are never authorized against a resource at all).
 */
public final class NoResource implements ResourceResolver {

    private NoResource() {
        throw new UnsupportedOperationException("NoResource is a marker type and is never instantiated");
    }

    @Override
    public ResourceRef resolve(HttpServletRequest request, Session session) {
        throw new UnsupportedOperationException("NoResource is a marker type and is never invoked");
    }
}
