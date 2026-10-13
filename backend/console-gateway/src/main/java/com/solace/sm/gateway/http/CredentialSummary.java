package com.solace.sm.gateway.http;

/**
 * What a target's stored key is, without the key (console.yaml {@code CredentialSummary}).
 *
 * <p>⛔ {@code I6}: {@code publicKeyFingerprint} is the fingerprint of the PUBLIC half, which is what {@code ssh-keygen -l}
 * prints for the {@code .pub} file. There is no field for private-key material, the {@code credential_ref}, or a hash of
 * the private key. {@code setAt} is an ISO-8601 instant, spelt as a {@code String} like every other timestamp on this wire.
 */
public record CredentialSummary(boolean present, @WireOptional String publicKeyFingerprint, @WireOptional String setAt) {
}
