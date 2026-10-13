package com.solace.sm.cp.api.identity;

import com.solace.sm.cp.api.session.Session;

/**
 * Describes the principal behind a resolved session.
 *
 * <p>⚠️ Authentication only — no catalog verb is required to read one's own identity (ruling
 * {@code OR-15}). This port must never be used to describe another principal.
 */
public interface IdentityDirectory {

    IdentityView describe(Session session);
}
