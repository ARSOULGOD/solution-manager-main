package com.solace.sm.cp.api.identity;

/** CP-owned local authentication port for a gateway that depends only on {@code cp-api}. */
public interface LocalAuthenticationService {

    /**
     * @param origin server-determined client address and correlation id — the second throttle dimension
     *               ({@code OR-20}) and the audit trail's join key ({@code OR-19})
     */
    LocalLoginResult authenticate(LocalCredentials credentials, com.solace.sm.cp.api.audit.RequestOrigin origin);
}
