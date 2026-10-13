package com.solace.sm.agw;

import io.grpc.Context;

/** Resolves identity only from the authenticated TLS peer; the metadata fallback is test-only. */
@FunctionalInterface
public interface CertificateThumbprintResolver {
    String resolve();

    static CertificateThumbprintResolver tlsPeer() {
        return () -> {
            return AgentGatewayService.MetadataContext.THUMBPRINT.get();
        };
    }
}
