package com.solace.sm.cp.api.target;

import java.util.List;
import java.util.UUID;

/**
 * Read side of target governance (OR-28). Plan 010 T2.7.
 *
 * <p>⚠️ <b>Not an authorization.</b> It returns every live target of the organization; a caller that lists them to a
 * principal MUST filter through {@code ReachAuthorization#authorizedSubset} with the operation's own verb. The
 * organization is the session's own ({@code I5}); there is no way to name another.
 */
public interface TargetDirectory {

    /** The organization's targets that are not retired, ordered by name. */
    List<TargetView> listLive(UUID organizationId);
}
