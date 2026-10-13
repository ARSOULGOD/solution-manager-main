package com.solace.sm.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.solace.sm.dp.meshbridge.JcsmpEventPublisher;
import com.solace.sm.dp.meshbridge.MeshProperties;
import com.solacesystems.jcsmp.JCSMPException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
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
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A Solace PubSub+ Enterprise broker started by THIS build, from the image that is already in the local Docker daemon
 * (plan 010 T6.6). The walkthrough's SM mesh events go here and nowhere else: not to a remote broker, and not to the
 * Cache's own broker (which is a property of the host under test, not of SM).
 *
 * <p><b>Why not the G1 spike's compose stack, which the other broker ITs use.</b> Those take {@code -Dbroker.host} and
 * assume someone started the stack and ran {@code provision.sh}. The walkthrough has to START the broker itself,
 * provision its own VPN and identities over SEMP, and stop and start it again mid-test (resilience scenarios 1-2), so
 * the lifecycle belongs to the test. The shape it provisions is the spike's own, minus everything OAuth: a VPN with
 * basic authentication, one internal identity that may publish and subscribe on {@code sm/v1/>} (the identity
 * {@code sm.mesh.client-username} names), and one observer that may only subscribe there.
 *
 * <p><b>Ports are fixed for the life of the object</b>, chosen free at start and bound explicitly. Docker reassigns
 * random host ports across a stop and a start, and the SM application's {@code sm.mesh.host} is configured once, so
 * a stable port is what lets a stopped broker come back to the same publisher.
 *
 * <p><b>Credentials are per-run random</b> and never printed ({@link #toString()} names none). The broker is local,
 * the credentials are local, and the image is never pulled: an absent image is a skip with its reason.
 */
final class LocalMeshBroker implements AutoCloseable {

    static final String IMAGE = "solace-pubsub-enterprise:10.25.0.177";
    static final String VPN = "sm-e2e";
    static final String PUBLISHER_USERNAME = "sm-internal";
    static final String OBSERVER_USERNAME = "sm-observer";

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASSWORD = "admin";
    private static final int SEMP_PORT = 8080;
    private static final int SMF_WEBSOCKET_PORT = 8008;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private final GenericContainer<?> container;
    private final int sempHostPort;
    private final int smfHostPort;
    private final String publisherPassword;
    private final String observerPassword;

    private LocalMeshBroker(GenericContainer<?> container, int sempHostPort, int smfHostPort, String publisherPassword,
            String observerPassword) {
        this.container = container;
        this.sempHostPort = sempHostPort;
        this.smfHostPort = smfHostPort;
        this.publisherPassword = publisherPassword;
        this.observerPassword = observerPassword;
    }

    /** Whether the broker image is in the local daemon. The image is loaded from a tarball and is never pulled. */
    static boolean imageAvailable() {
        try {
            DockerClient client = DockerClientFactory.instance().client();
            client.inspectImageCmd(IMAGE).exec();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Starts the broker, waits for SEMP, provisions the VPN and the two identities, and waits until one connects. */
    static LocalMeshBroker start() {
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
        LocalMeshBroker broker = new LocalMeshBroker(container, semp, smf, randomSecret(), randomSecret());
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

    // ---- what a client needs ---------------------------------------------------------------------------------

    /** The JCSMP host URI: SMF over plaintext WebSocket, the transport the other mesh ITs use. */
    String smfHost() {
        return "ws://" + container.getHost() + ":" + smfHostPort;
    }

    String vpn() {
        return VPN;
    }

    String publisherUsername() {
        return PUBLISHER_USERNAME;
    }

    /** The internal identity's secret, for {@code sm.mesh.password}. Per-run random; never printed. */
    String publisherPassword() {
        return publisherPassword;
    }

    /** The properties that point an SM application at this broker (no password in any log line: see MeshProperties). */
    Map<String, String> meshProperties() {
        return Map.of("sm.mesh.enabled", "true", "sm.mesh.host", smfHost(), "sm.mesh.vpn", VPN,
                "sm.mesh.client-username", PUBLISHER_USERNAME, "sm.mesh.password", publisherPassword);
    }

    /** A direct, eagerly connected publisher as the internal identity (throws when the broker is unreachable). */
    JcsmpEventPublisher publisher() {
        return new JcsmpEventPublisher(new MeshProperties(smfHost(), VPN, PUBLISHER_USERNAME, publisherPassword));
    }

    /** A subscriber as the observer identity, recording everything delivered on {@code subscription}. */
    MeshEventLog observe(String subscription) throws JCSMPException {
        return MeshEventLog.open(smfHost(), VPN, OBSERVER_USERNAME, observerPassword, subscription);
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    boolean isRunning() {
        return Boolean.TRUE.equals(container.getDockerClient().inspectContainerCmd(container.getContainerId()).exec()
                .getState().getRunning());
    }

    /** Stops the broker process's container; its configuration and its ports survive for {@link #startBroker()}. */
    void stopBroker() {
        container.getDockerClient().stopContainerCmd(container.getContainerId()).withTimeout(10).exec();
    }

    /** Starts the stopped container again on the same ports and waits until an identity can connect. */
    void startBroker() throws InterruptedException {
        container.getDockerClient().startContainerCmd(container.getContainerId()).exec();
        awaitSemp(Duration.ofMinutes(4));
        awaitConnectable(Duration.ofMinutes(2));
    }

    /** Disconnects every client of the internal identity through SEMP and returns how many it cut. */
    int disconnectPublishers() throws IOException, InterruptedException {
        int cut = 0;
        for (String clientName : clientNames(PUBLISHER_USERNAME)) {
            HttpResponse<String> response = semp("PUT", "/SEMP/v2/action/msgVpns/" + VPN + "/clients/"
                    + encode(clientName) + "/disconnect", "{}");
            requireOk(response, "disconnect a client");
            cut++;
        }
        return cut;
    }

    /** How many clients of {@code username} the broker currently has connected. */
    int connectedClients(String username) throws IOException, InterruptedException {
        return clientNames(username).size();
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
        return "LocalMeshBroker[" + IMAGE + ", vpn=" + VPN + ", smf=" + smfHost() + "]";
    }

    // ---- SEMP ------------------------------------------------------------------------------------------------

    private void provision() {
        try {
            requireOk(semp("POST", "/SEMP/v2/config/msgVpns", JSON.writeValueAsString(Map.of("msgVpnName", VPN,
                    "enabled", true, "maxMsgSpoolUsage", 500, "serviceWebPlainTextEnabled", true,
                    "authenticationBasicEnabled", true, "authenticationBasicType", "internal"))),
                    "create the message VPN");
            String base = "/SEMP/v2/config/msgVpns/" + VPN;
            acl(base, "acl-internal", true);
            acl(base, "acl-observer", false);
            clientUsername(base, PUBLISHER_USERNAME, publisherPassword, "acl-internal");
            clientUsername(base, OBSERVER_USERNAME, observerPassword, "acl-observer");
        } catch (IOException e) {
            throw new IllegalStateException("could not provision the local broker over SEMP: " + e.getClass()
                    .getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted provisioning the local broker");
        }
    }

    /** An ACL profile that allows connect, denies everything by default, and excepts {@code sm/v1/>} as asked. */
    private void acl(String base, String name, boolean mayPublish) throws IOException, InterruptedException {
        requireOk(semp("POST", base + "/aclProfiles", JSON.writeValueAsString(Map.of("msgVpnName", VPN,
                "aclProfileName", name, "clientConnectDefaultAction", "allow", "publishTopicDefaultAction",
                "disallow", "subscribeTopicDefaultAction", "disallow"))), "create ACL profile " + name);
        requireOk(semp("POST", base + "/aclProfiles/" + name + "/subscribeTopicExceptions", JSON.writeValueAsString(
                Map.of("msgVpnName", VPN, "aclProfileName", name, "subscribeTopicExceptionSyntax", "smf",
                        "subscribeTopicException", "sm/v1/>"))), "subscribe exception for " + name);
        if (mayPublish) {
            requireOk(semp("POST", base + "/aclProfiles/" + name + "/publishTopicExceptions", JSON.writeValueAsString(
                    Map.of("msgVpnName", VPN, "aclProfileName", name, "publishTopicExceptionSyntax", "smf",
                            "publishTopicException", "sm/v1/>"))), "publish exception for " + name);
        }
    }

    private void clientUsername(String base, String username, String password, String acl)
            throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("msgVpnName", VPN).put("clientUsername", username).put("password", password)
                .put("aclProfileName", acl).put("clientProfileName", "default").put("enabled", true);
        requireOk(semp("POST", base + "/clientUsernames", body.toString()), "create client username "
                + username);
    }

    private List<String> clientNames(String username) throws IOException, InterruptedException {
        HttpResponse<String> response = semp("GET", "/SEMP/v2/monitor/msgVpns/" + VPN + "/clients?count=100&where="
                + encode("clientUsername==" + username), null);
        requireOk(response, "list clients");
        List<String> names = new java.util.ArrayList<>();
        JsonNode data = JSON.readTree(response.body()).get("data");
        if (data != null) {
            for (JsonNode client : data) {
                names.add(client.get("clientName").asText());
            }
        }
        return names;
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

    /** The VPN is up when the internal identity can connect: that is what the application and the observer need. */
    private void awaitConnectable(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        RuntimeException last = null;
        while (Instant.now().isBefore(deadline)) {
            try (MeshEventLog probe = observe("sm/v1/readiness-probe")) {
                return;
            } catch (JCSMPException | RuntimeException e) {
                last = e instanceof RuntimeException r ? r : new IllegalStateException(e.getClass().getSimpleName());
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException("no identity could connect to the local broker within " + timeout
                + (last == null ? "" : ": " + last.getClass().getSimpleName()));
    }

    private HttpResponse<String> semp(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://" + container.getHost() + ":"
                        + sempHostPort + path))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (ADMIN_USER + ":" + ADMIN_PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(30));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** SEMP answers 200 with a {@code meta.responseCode} even for refusals; both are checked. Never echoes a body. */
    private static void requireOk(HttpResponse<String> response, String what) throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("SEMP refused to " + what + " (HTTP " + response.statusCode() + ")");
        }
        JsonNode meta = JSON.readTree(response.body()).get("meta");
        if (meta != null && meta.has("responseCode") && meta.get("responseCode").asInt() / 100 != 2) {
            throw new IllegalStateException("SEMP refused to " + what + " (responseCode "
                    + meta.get("responseCode").asInt() + ")");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
