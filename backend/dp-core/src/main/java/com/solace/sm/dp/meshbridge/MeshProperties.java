package com.solace.sm.dp.meshbridge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the backend's own mesh identity, bound from {@code sm.mesh.*}.
 *
 * <p>This is the <b>server-side</b> identity: basic auth (or, later, a client certificate) as an
 * internal client username with publish rights on {@code sm/v1/>}. It is deliberately not the
 * browser path — browsers reach the mesh through the gateway-proxied {@code wss} endpoint with a
 * post-RBAC JWT ticket and are subscribe-only ({@code BE-D5}, design §3/§5). Nothing in this record
 * is reachable from a browser.
 *
 * <p>{@code host} carries the scheme JCSMP expects, e.g. {@code tcps://broker:55443} in production
 * or {@code ws://localhost:8008} against the G1 spike broker (both schemes verified present in
 * {@code com.solacesystems.common.HostInfo} of sol-jcsmp 10.30.1 — read from the SDK, not recalled,
 * {@code I2}).
 *
 * <p><b>{@code password} is a deployment secret</b> ({@code I6}): it is supplied by the environment
 * or the secret backend, never persisted into config storage, and never logged. {@link #toString()}
 * is overridden so that a debug log or a Spring startup dump of this bean cannot leak it — records
 * generate a component-printing {@code toString} by default, which for a credential-holding record
 * is a defect waiting to happen.
 *
 * @param host           JCSMP host URI including scheme, e.g. {@code tcps://broker:55443}
 * @param vpn            Message VPN name
 * @param clientUsername the internal client username (the only kind of identity allowed to publish)
 * @param password       that identity's secret — never log it, never store it
 */
@ConfigurationProperties(prefix = "sm.mesh")
public record MeshProperties(String host, String vpn, String clientUsername, String password) {

    public MeshProperties {
        requireText("sm.mesh.host", host);
        requireText("sm.mesh.vpn", vpn);
        requireText("sm.mesh.client-username", clientUsername);
        // password may be empty (client-certificate authentication supplies no password), but never
        // null — JCSMP's property map rejects a null value.
        if (password == null) {
            password = "";
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be set");
        }
    }

    /** Redacted on purpose — see the class javadoc ({@code I6}). */
    @Override
    public String toString() {
        return "MeshProperties[host=" + host + ", vpn=" + vpn + ", clientUsername=" + clientUsername
                + ", password=****]";
    }
}
