package com.solace.sm.cp.api.identity;

/** CP-owned local authentication port for a gateway that depends only on {@code cp-api}. */
public interface LocalAuthenticationService {

    LocalLoginResult authenticate(LocalCredentials credentials);
}
