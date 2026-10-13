package com.solace.sm.gateway.archfixture.credentials;

/**
 * Planted violation (plan 013 T1.4, D-REG section 6.2): a gateway request record with a field named {@code privateKey}
 * that does NOT override {@code toString}, so the compiler-generated one prints the key. Never executed: it exists only
 * to be analysed. <b>Do not copy this pattern into production code.</b>
 */
public record BadCredentialRequest(String name, String privateKey) {
}
