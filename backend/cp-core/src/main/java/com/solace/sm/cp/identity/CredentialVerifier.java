package com.solace.sm.cp.identity;

/** Verifies local credentials against a persisted, adaptive credential hash. */
public interface CredentialVerifier {

    String hash(char[] credential);

    boolean verify(char[] credential, String credentialHash);
}
