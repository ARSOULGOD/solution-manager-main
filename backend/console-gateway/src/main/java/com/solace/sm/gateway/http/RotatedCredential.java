package com.solace.sm.gateway.http;

/**
 * The response of {@code rotateTargetCredential}: the stored key's summary after the call (console.yaml, an object with
 * one required property, {@code credential}). The same key again answers the same summary and wrote nothing.
 */
public record RotatedCredential(CredentialSummary credential) {
}
