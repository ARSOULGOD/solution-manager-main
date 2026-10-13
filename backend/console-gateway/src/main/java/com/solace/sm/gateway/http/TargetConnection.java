package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * A target's connection record as the management read returns it (console.yaml {@code TargetConnection}, a union discriminated on
 * {@code transport}; OQ-13 (a): {@code target.edit}, not {@code target.view}). {@code listTargets} keeps returning the
 * envelope-only {@link Target}.
 *
 * <p>One record carries both variants of the contract's {@code oneOf}: an {@code ssh-docker} record sets every SSH field and no
 * {@code agentId}; an {@code agent} record sets {@code agentId} and the envelope and none of the SSH fields (an agent target has no
 * address, user, pin or credential, {@code BE-D68}). The unset fields are omitted from the wire, so each variant is exactly its
 * schema. Build one only through {@link #ssh} or {@link #agent}.
 *
 * <p>⛔ No field carries private-key material, its {@code credential_ref}, or a hash of the private key.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TargetConnection(UUID targetId, TargetTransport transport, @WireOptional String host,
        @WireOptional Integer port, @WireOptional String sshUser, @WireOptional String hostKeyFingerprint,
        @WireOptional List<String> offeredHostKeys, String allowedConfigRoot, String allowedContainerPrefix,
        @WireOptional CredentialSummary credential, @WireOptional UUID agentId) {

    /** The {@code ssh-docker} variant: today's record, every SSH field set. */
    public static TargetConnection ssh(UUID targetId, String host, int port, String sshUser, String hostKeyFingerprint,
            List<String> offeredHostKeys, String allowedConfigRoot, String allowedContainerPrefix,
            CredentialSummary credential) {
        return new TargetConnection(targetId, TargetTransport.SSH_DOCKER, host, port, sshUser, hostKeyFingerprint,
                offeredHostKeys, allowedConfigRoot, allowedContainerPrefix, credential, null);
    }

    /** The {@code agent} variant: the agent and the envelope, nothing else. */
    public static TargetConnection agent(UUID targetId, UUID agentId, String allowedConfigRoot,
            String allowedContainerPrefix) {
        return new TargetConnection(targetId, TargetTransport.AGENT, null, null, null, null, null, allowedConfigRoot,
                allowedContainerPrefix, null, agentId);
    }
}
