package com.solace.sm.dp.meshbridge.connection;

import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.apply.runtime.connection.SolaceBrokerConnectionTester;
import com.solace.sm.dp.apply.runtime.connection.SolaceBrokerConnectionTester.Binding;
import com.solace.sm.dp.apply.runtime.connection.SolaceBrokerConnectionTester.Steps;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.secret.SecretException;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretResolver;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The JCSMP half of {@code testBeforeSave} for a {@code solace-broker} Connection (plan 013 T3/T4, {@code BE-D66}):
 * open a session with the submitted address and credential, confirm {@link JCSMPSession#connect()} returned, and
 * close it. Nothing is published or subscribed.
 *
 * <p>⛔ <b>Fix round 1, item 0.</b> Moved here from {@code com.solace.sm.dp.apply.runtime.connection} (Task 3):
 * {@code PlaneBoundaryTest.onlyMeshbridgeTouchesJcsmp} confines every use of the JCSMP SDK
 * ({@code com.solacesystems..}) to {@code com.solace.sm.dp.meshbridge..} — "no other module holds a broker
 * session" — and this probe opens one, so it belongs here, not beside the sshj probe. {@link
 * SolaceBrokerConnectionTester} stays in {@code apply.runtime.connection} (it owns no JCSMP import itself); its
 * {@code Probe} seam and {@code Steps}/{@code Binding} records are widened to {@code public} so this class can
 * implement/construct them across the package boundary.
 *
 * <p>Verified against sol-jcsmp 10.30.1 ({@code I2}, the same version {@code JcsmpEventPublisher} is verified
 * against): {@link JCSMPSession#connect()} throws the checked {@link JCSMPException} with the broker's own
 * rejection text (e.g. an unknown VPN, a bad client username/password, or a transport failure) when the session
 * cannot be established, and a session that returns from {@code connect()} is connected.
 *
 * <p><b>Fix round 1, item 4 — reachable vs. rejected.</b> JCSMP's {@code connect()} conflates the transport
 * handshake and the broker's own accept/reject into one call, so there is no built-in two-step signal the way
 * sshj's connect-then-authenticate gives {@code SshjConnectionAuthProbe}. {@link #isTransportFailure} is a
 * best-effort, deliberately bounded heuristic over the exception's cause chain: a {@link ConnectException},
 * {@link SocketTimeoutException}, {@link NoRouteToHostException} or {@link UnknownHostException} anywhere in it
 * means the probe never reached a broker to be rejected by ({@code reachable = false}, the caller answers
 * {@code 503}); anything else (an unknown VPN, a bad username/password — a {@code JCSMPErrorResponseException} or
 * similar) is treated as a broker that WAS reached and said no ({@code reachable = true}, {@code 422}). This is
 * not exhaustive — it is the contract's own two documented codes, nothing broader.
 */
public final class JcsmpBrokerProbe implements SolaceBrokerConnectionTester.Probe {

    /** A URI scheme prefix (e.g. {@code tcps://}), per {@code solace-broker.json}'s host helper text. */
    private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://");

    private final SecretResolver secrets;
    private final TargetAddressPolicy addressPolicy;

    public JcsmpBrokerProbe(SecretResolver secrets, TargetAddressPolicy addressPolicy) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.addressPolicy = Objects.requireNonNull(addressPolicy, "addressPolicy");
    }

    @Override
    public Steps run(Binding binding) {
        // Checked before any credential is resolved or any session is opened (fix round 1, owner ruling): the
        // stored host is a name, and the data plane may never connect wherever that name happens to resolve to
        // (SM-D11a P / SSRF). The attribute's own helper text allows a scheme prefix, so that is stripped before
        // the bare host is handed to the policy — the same discipline SshjDockerTransport.checkedAddress follows
        // for Target registration, at the same bar.
        try {
            addressPolicy.connectable(bareHost(binding.host()));
        } catch (TargetAddressRefusedException refused) {
            return new Steps(false, "host address refused", false);
        } catch (UnknownHostException unresolvable) {
            return new Steps(false, "host does not resolve", false);
        }
        char[] password = new char[0];
        try {
            if (binding.passwordSecretRef() != null) {
                try (SecretMaterial material = secrets.resolve(binding.tenant().value(), binding.passwordSecretRef(),
                        SecretPurpose.CONNECTION_CREDENTIAL)) {
                    password = material.chars();
                }
            }
            return connect(binding, password);
        } catch (SecretException e) {
            // The credential itself is unavailable (not found, wrong purpose, key unavailable): a result, not an
            // exception, the broker is never contacted with half a credential, and this is not a network
            // reachability problem — reachable stays true (422), the same bucket a bad credential lands in.
            return new Steps(false, "connection credential unavailable", true);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    /** Strips a leading URI scheme (e.g. {@code tcps://broker} → {@code broker}) so the bare name can be checked. */
    static String bareHost(String host) {
        Matcher m = SCHEME.matcher(host);
        return m.find() ? host.substring(m.end()) : host;
    }

    private static Steps connect(Binding binding, char[] password) {
        JCSMPProperties props = new JCSMPProperties();
        props.setProperty(JCSMPProperties.HOST, binding.host() + ":" + binding.port());
        props.setProperty(JCSMPProperties.VPN_NAME, binding.vpn());
        props.setProperty(JCSMPProperties.USERNAME, binding.username() == null ? "" : binding.username());
        props.setProperty(JCSMPProperties.PASSWORD, new String(password));
        // Exactly one attempt: a connectivity test must say "unreachable now", never retry into a long hang.
        com.solacesystems.jcsmp.JCSMPChannelProperties channel = (com.solacesystems.jcsmp.JCSMPChannelProperties)
                props.getProperty(JCSMPProperties.CLIENT_CHANNEL_PROPERTIES);
        channel.setConnectRetries(0);
        channel.setReconnectRetries(0);
        JCSMPSession session = null;
        try {
            session = JCSMPFactory.onlyInstance().createSession(props);
            session.connect();
            return new Steps(true, null, true);
        } catch (JCSMPException e) {
            // The broker's own rejection reason (brief: "surfaces the broker's own rejection reason on failure").
            // It never quotes the password back (JCSMP's own exception text does not echo credentials), so
            // forwarding it does not violate I6.
            return new Steps(false, e.getMessage(), !isTransportFailure(e));
        } finally {
            if (session != null) {
                try {
                    session.closeSession();
                } catch (RuntimeException ignored) {
                    // closing a session that never connected, or is already gone
                }
            }
        }
    }

    /** See the class javadoc's "reachable vs. rejected" section: a bounded, best-effort heuristic. */
    private static boolean isTransportFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof SocketTimeoutException
                    || cause instanceof NoRouteToHostException || cause instanceof UnknownHostException) {
                return true;
            }
        }
        return false;
    }
}
