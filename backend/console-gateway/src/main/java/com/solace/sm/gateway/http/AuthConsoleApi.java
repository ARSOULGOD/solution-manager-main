package com.solace.sm.gateway.http;

/** Hand-written handler signatures for the bounded authentication OpenAPI slice. */
public interface AuthConsoleApi {

    Identity login(LoginRequest request);

    void logout(String csrfToken);

    Identity getIdentity();

    void rotateOwnCredential(RotateOwnCredentialRequest request, String csrfToken);
}
