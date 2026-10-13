package com.solace.sm.dp.meshbridge;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
 * <p><b>{@code enabled} (plan 010 T3.3, default {@code false}).</b> The production data plane imports the mesh
 * bridge, so every boot reaches this record. Disabled, host/vpn/username are not required and the transport is a
 * dropping publisher that counts every drop - a <i>declared degraded</i> state, never a refusal to boot. Enabled,
 * every requirement below applies unchanged, so a <i>partial</i> configuration still refuses to boot (fail closed
 * on a typo, not silently dropped).
 *
 * @param host           JCSMP host URI including scheme, e.g. {@code tcps://broker:55443}
 * @param vpn            Message VPN name
 * @param clientUsername the internal client username (the only kind of identity allowed to publish)
 * @param password       that identity's secret — never log it, never store it
 * @param enabled        {@code sm.mesh.enabled}; {@code false} when absent
 * @param sendTimeout    {@code sm.mesh.send-timeout}: the longest any one publish may hold its caller (plan 010
 *                       T6.6-1). A send still unfinished after this is abandoned and counted as a drop - a broker
 *                       outage must never block a REST request thread or the apply worker. Defaults to
 *                       {@link #DEFAULT_SEND_TIMEOUT} when absent; must be positive.
 */
@ConfigurationProperties(prefix = "sm.mesh")
public record MeshProperties(String host, String vpn, String clientUsername, String password, boolean enabled,
        Duration sendTimeout) {

    /** The default bound on one publish: short enough that a request thread never notices, long enough for a healthy send. */
    public static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofMillis(400);

    /** As the canonical form with the default {@link #sendTimeout()}. */
    public MeshProperties(String host, String vpn, String clientUsername, String password, boolean enabled) {
        this(host, vpn, clientUsername, password, enabled, DEFAULT_SEND_TIMEOUT);
    }

    /**
     * The explicit-construction form: a caller that builds a {@code MeshProperties} by hand has already decided to
     * connect, so this is {@code enabled = true}. Spring binds the canonical constructor ({@code @ConstructorBinding}: with two constructors Boot will not guess).
     */
    public MeshProperties(String host, String vpn, String clientUsername, String password) {
        this(host, vpn, clientUsername, password, true);
    }

    @ConstructorBinding
    public MeshProperties {
        if (enabled) {
            requireText("sm.mesh.host", host);
            requireText("sm.mesh.vpn", vpn);
            requireText("sm.mesh.client-username", clientUsername);
        }
        // password may be empty (client-certificate authentication supplies no password), but never
        // null — JCSMP's property map rejects a null value.
        if (password == null) {
            password = "";
        }
        if (sendTimeout == null) {
            sendTimeout = DEFAULT_SEND_TIMEOUT;
        }
        if (sendTimeout.isZero() || sendTimeout.isNegative()) {
            throw new IllegalArgumentException("sm.mesh.send-timeout must be positive, got " + sendTimeout);
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
        return "MeshProperties[enabled=" + enabled + ", host=" + host + ", vpn=" + vpn + ", clientUsername="
                + clientUsername + ", password=****, sendTimeout=" + sendTimeout + "]";
    }
}
