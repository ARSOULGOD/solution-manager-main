package com.solace.sm.dp.meshbridge.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.solacesystems.jcsmp.DeliveryMode;
import com.solacesystems.jcsmp.JCSMPException;
import com.solacesystems.jcsmp.JCSMPFactory;
import com.solacesystems.jcsmp.JCSMPProperties;
import com.solacesystems.jcsmp.JCSMPSession;
import com.solacesystems.jcsmp.JCSMPStreamingPublishCorrelatingEventHandler;
import com.solacesystems.jcsmp.TextMessage;
import com.solacesystems.jcsmp.XMLMessageProducer;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A Solace PubSub+ Enterprise broker started by THIS build from the image already in the local Docker daemon (plan 011 P11-5.1),
 * for the broker push path's ITs. The {@code LocalMeshBroker} pattern of {@code launcher} (plan 010 T6.6), reshaped for a
 * RECEIVING Data Plane: a VPN with basic authentication and two identities, {@code sm-push} (the one under test, which
 * subscribes) and {@code fm-publisher} (a stand-in for the managed integration, which publishes), both on the default ACL
 * profile (the customer's broker is the customer's: SM brings no ACL of its own).
 *
 * <p>Never a remote broker, never a shared one, never the {@code oms-*} containers: the broker is created here and removed at
 * {@link #close()}, and the image is never pulled (an absent image is a skip with its reason). Ports are fixed for the life of
 * the object so a stopped broker comes back where its subscribers expect it. Credentials are per-run random and never printed.
 */
public final class LocalPushBroker implements AutoCloseable {

    public static final String IMAGE = "solace-pubsub-enterprise:10.25.0.177";
    public static final String VPN = "sm-push-it";
    public static final String SUBSCRIBER_USERNAME = "sm-push";
    public static final String PUBLISHER_USERNAME = "fm-publisher";

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASSWORD = "admin";
    private static final int SEMP_PORT = 8080;
    private static final int SMF_WEBSOCKET_PORT = 8008;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private final GenericContainer<?> container;
    private final int sempHostPort;
    private final int smfHostPort;
    private final String subscriberPassword;
    private final String publisherPassword;

    private LocalPushBroker(GenericContainer<?> container, int sempHostPort, int smfHostPort, String subscriberPassword,
            String publisherPassword) {
        this.container = container;
        this.sempHostPort = sempHostPort;
        this.smfHostPort = smfHostPort;
        this.subscriberPassword = subscriberPassword;
        this.publisherPassword = publisherPassword;
    }

    /** Whether the broker image is in the local daemon. It is loaded from a tarball and never pulled. */
    public static boolean imageAvailable() {
        try {
            DockerClient client = DockerClientFactory.instance().client();
            client.inspectImageCmd(IMAGE).exec();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static LocalPushBroker start() {
        int semp = freePort();
        int smf = freePort();
        if (!imageAvailable()) {
            throw new IllegalStateException("the local broker image " + IMAGE + " is not in this Docker daemon; it is loaded "
                    + "from the Solace support-portal tarball and is never pulled");
        }
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withImagePullPolicy(name -> false)   // never pull: no registry is contacted, ever
                .withEnv("username_admin_globalaccesslevel", "admin")
                .withEnv("username_admin_password", ADMIN_PASSWORD)
                .withEnv("system_scaling_maxconnectioncount", "100")
                .withSharedMemorySize(1024L * 1024 * 1024)
                .withExposedPorts(SEMP_PORT, SMF_WEBSOCKET_PORT)
                .withStartupTimeout(Duration.ofMinutes(4));
        container.setPortBindings(List.of(semp + ":" + SEMP_PORT, smf + ":" + SMF_WEBSOCKET_PORT));
        container.start();
        LocalPushBroker broker = new LocalPushBroker(container, semp, smf, randomSecret(), randomSecret());
        try {
            broker.awaitSemp(Duration.ofMinutes(4));
            broker.provision();
            broker.awaitConnectable(Duration.ofMinutes(2));
        } catch (RuntimeException | InterruptedException e) {
            broker.close();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw e instanceof RuntimeException r ? r : new IllegalStateException("interrupted starting the broker");
        }
        return broker;
    }

    // ---- what a client needs ----------------------------------------------------------------------------------

    public String host() {
        return container.getHost();
    }

    public int smfPort() {
        return smfHostPort;
    }

    /** The Data Plane's coordinates for this broker: SMF over plaintext WebSocket, the transport the local image exposes. */
    public BrokerEndpoint endpoint() {
        return new BrokerEndpoint("ws", host(), smfHostPort, VPN, SUBSCRIBER_USERNAME);
    }

    /** The subscriber identity's secret. Per-run random; never printed. */
    public char[] subscriberPassword() {
        return subscriberPassword.toCharArray();
    }

    /** A publisher session as the integration's identity (DIRECT or PERSISTENT per message). */
    public Publisher publisher() throws JCSMPException {
        JCSMPProperties props = new JCSMPProperties();
        props.setProperty(JCSMPProperties.HOST, "ws://" + host() + ":" + smfHostPort);
        props.setProperty(JCSMPProperties.VPN_NAME, VPN);
        props.setProperty(JCSMPProperties.USERNAME, PUBLISHER_USERNAME);
        props.setProperty(JCSMPProperties.PASSWORD, publisherPassword);
        JCSMPSession session = JCSMPFactory.onlyInstance().createSession(props);
        session.connect();
        XMLMessageProducer producer = session.getMessageProducer(new JCSMPStreamingPublishCorrelatingEventHandler() {
            @Override
            public void responseReceivedEx(Object key) {
            }

            @Override
            public void handleErrorEx(Object key, JCSMPException cause, long timestamp) {
            }
        });
        return new Publisher(session, producer);
    }

    /** One publishing session. */
    public static final class Publisher implements AutoCloseable {
        private final JCSMPSession session;
        private final XMLMessageProducer producer;

        private Publisher(JCSMPSession session, XMLMessageProducer producer) {
            this.session = session;
            this.producer = producer;
        }

        public void publish(String topic, String body, DeliveryMode mode) throws JCSMPException {
            TextMessage message = JCSMPFactory.onlyInstance().createMessage(TextMessage.class);
            message.setText(body);
            message.setDeliveryMode(mode);
            producer.send(message, JCSMPFactory.onlyInstance().createTopic(topic));
        }

        public void publish(String topic, String body) throws JCSMPException {
            publish(topic, body, DeliveryMode.DIRECT);
        }

        @Override
        public void close() {
            try {
                producer.close();
                session.closeSession();
            } catch (RuntimeException e) {
                // a session whose broker is already gone must not mask a test's own failure
            }
        }
    }

    // ---- lifecycle --------------------------------------------------------------------------------------------

    public void stopBroker() {
        container.getDockerClient().stopContainerCmd(container.getContainerId()).withTimeout(10).exec();
    }

    /** Starts the stopped container again on the same ports and waits until an identity can connect. */
    public void startBroker() throws InterruptedException {
        container.getDockerClient().startContainerCmd(container.getContainerId()).exec();
        awaitSemp(Duration.ofMinutes(4));
        awaitConnectable(Duration.ofMinutes(2));
    }

    @Override
    public void close() {
        try {
            container.stop();
        } catch (RuntimeException e) {
            // removing a container that is already gone is not a failure of the test that used it
        }
    }

    @Override
    public String toString() {
        return "LocalPushBroker[" + IMAGE + ", vpn=" + VPN + "]";
    }

    // ---- SEMP -------------------------------------------------------------------------------------------------

    private void provision() {
        try {
            requireOk(semp("POST", "/SEMP/v2/config/msgVpns", JSON.writeValueAsString(Map.of("msgVpnName", VPN,
                    "enabled", true, "maxMsgSpoolUsage", 500, "serviceWebPlainTextEnabled", true,
                    "authenticationBasicEnabled", true, "authenticationBasicType", "internal"))), "create the message VPN");
            String base = "/SEMP/v2/config/msgVpns/" + VPN;
            // File Mesh publishes PERSISTENT by default (SOLACE_MESSAGING_MODE): the publisher identity may send guaranteed messages
            requireOk(semp("PATCH", base + "/clientProfiles/default", JSON.writeValueAsString(Map.of(
                    "allowGuaranteedMsgSendEnabled", true, "allowGuaranteedMsgReceiveEnabled", true,
                    "allowGuaranteedEndpointCreateEnabled", true))), "allow guaranteed messaging on the default client profile");
            clientUsername(base, SUBSCRIBER_USERNAME, subscriberPassword);
            clientUsername(base, PUBLISHER_USERNAME, publisherPassword);
        } catch (IOException e) {
            throw new IllegalStateException("could not provision the local broker over SEMP: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted provisioning the local broker");
        }
    }

    private void clientUsername(String base, String username, String password) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("msgVpnName", VPN).put("clientUsername", username).put("password", password)
                .put("aclProfileName", "default").put("clientProfileName", "default").put("enabled", true);
        requireOk(semp("POST", base + "/clientUsernames", body.toString()), "create client username " + username);
    }

    private void awaitSemp(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (semp("GET", "/SEMP/v2/config/about/api", null).statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // not listening yet
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("the local broker's SEMP did not come up within " + timeout);
    }

    /** The VPN is up when the subscriber identity can connect: that is what the Data Plane needs. */
    private void awaitConnectable(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        RuntimeException last = null;
        while (Instant.now().isBefore(deadline)) {
            try (Publisher probe = publisher()) {
                return;
            } catch (JCSMPException | RuntimeException e) {
                last = e instanceof RuntimeException r ? r : new IllegalStateException(e.getClass().getSimpleName());
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("no identity could connect to the local broker within " + timeout
                + (last == null ? "" : ": " + last.getClass().getSimpleName()));
    }

    private HttpResponse<String> semp(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://" + container.getHost() + ":" + sempHostPort + path))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (ADMIN_USER + ":" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(30));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** SEMP answers 200 with a {@code meta.responseCode} even for refusals; both are checked. Never echoes a body. */
    private static void requireOk(HttpResponse<String> response, String what) throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("SEMP refused to " + what + " (HTTP " + response.statusCode() + ")");
        }
        JsonNode meta = JSON.readTree(response.body()).get("meta");
        if (meta != null && meta.has("responseCode") && meta.get("responseCode").asInt() / 100 != 2) {
            throw new IllegalStateException("SEMP refused to " + what + " (responseCode " + meta.get("responseCode").asInt() + ")");
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("no free local port");
        }
    }

    private static String randomSecret() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
