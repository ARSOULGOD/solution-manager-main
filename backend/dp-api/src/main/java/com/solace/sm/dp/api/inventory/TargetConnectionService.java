package com.solace.sm.dp.api.inventory;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Target connection records (OR-28). The private key is stored encrypted behind a reference the record keeps; the
 * caller owns, and zeroises, the {@code char[]}.
 *
 * <p><b>The key is checked, then compared by its public half (plan 013 T1.1).</b> Both writing operations first open
 * the key as an UNENCRYPTED private key and refuse anything else ({@link CredentialInvalidException}, fixed text, no
 * parser message), before anything is stored. A replay and a rotation then compare the fingerprint of the key's PUBLIC
 * half with the stored one's, so the same key in another encoding is the same key, and no second secret row is ever
 * stored for it.
 */
public interface TargetConnectionService {

    /**
     * Idempotent on {@code targetId}: an identical re-registration (the same record AND the same key) writes nothing;
     * a differing record is an {@link IllegalStateException}, and the same record with a different key is a
     * {@link TargetCredentialConflictException} (rotate instead). Any field outside the grammar is an
     * {@link IllegalArgumentException} naming the field, and an unusable key a {@link CredentialInvalidException}.
     */
    void register(UUID organizationId, UUID targetId, TargetConnectionSpec spec, char[] privateKey, UUID actor);

    /**
     * Stores a NEW secret and repoints the record; sessions already open keep the key they resolved. The SAME key again
     * (compared by public fingerprint) stores nothing.
     */
    void rotateCredential(UUID organizationId, UUID targetId, char[] privateKey, UUID actor);

    /**
     * The envelope of each LIVE (not retired) connection record among {@code targetIds}, for the tenant. A target
     * with no live record is absent from the map (the CP lists such a target as degraded, never hides it). Carries no
     * host, user, fingerprint or credential reference.
     */
    Map<UUID, TargetEnvelope> liveEnvelopes(UUID organizationId, Collection<UUID> targetIds);

    /**
     * Plan 014: whether the data plane holds a LIVE runtime projection ({@code sm_data.connection_runtime}) of this
     * remote-server Connection for the tenant. A connection-backed declare is refused without one (fail closed: there is
     * no fallback to a Target and no browser-supplied host). Default {@code false}, so a store that predates the
     * projection refuses rather than admits.
     */
    default boolean connectionRuntimeLive(UUID organizationId, UUID connectionId) {
        return false;
    }
}
