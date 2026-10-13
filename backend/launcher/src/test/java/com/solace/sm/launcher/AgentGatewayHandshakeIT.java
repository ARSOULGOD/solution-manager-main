package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.protobuf.util.Timestamps;
import com.solace.sm.agw.AgentGatewayServer;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.stub.StreamObserver;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import solace.sm.agentgateway.v1draft.AgentGatewayGrpc;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;

/**
 * G-A0 (plan 015 T3.2 Step 4, LOCAL ONLY): ONE JVM runs the production launcher with the agent gRPC/mTLS listener composed AND a
 * connected JCSMP session to a local Solace broker started by this build; both serve. Proof of the listener is a Java gRPC client
 * (this file: the Go {@code fakegw} is a server, not a client, P15-22) that presents a certificate issued by the test keystore's CA,
 * completes the mTLS handshake and one RPC. The RPC is {@code Connect}: a {@code SessionOpen} from a certificate no enrolment issued
 * is answered {@code UNAUTHENTICATED_IDENTITY}. That proves: the composed listener accepted a TLS connection whose client certificate
 * chains to the test CA, and a {@code Connect} RPC reached the service, which answered with the wire's code. It does NOT by itself prove
 * the certificate was read and resolved (the no-certificate client gets the same answer); the foreign-CA case shows the trust manager
 * verifies a presented certificate. A successful Connect needs an enrolled identity (an enrolment token), which is T4.4/G-A1.
 *
 * <p>No E2E host is contacted. Without the broker image locally the test is SKIPPED with its reason, never silently green.
 */
@Tag("broker")
// The cached context holds a JCSMP session to the broker: it closes with the class (Spring's own afterAll), while the broker is still up,
// not at the JVM's shutdown hook, where the session would retry a dead port and surefire would kill the fork after 30 s (measured).
// It is NOT closed by hand: Spring's afterTestClass needs it open.
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentGatewayHandshakeIT {

    private static LocalMeshBroker broker;
    private static AgentGatewayTestPki pki;
    private static int gatewayPort;

    @Autowired
    private ApplicationContext context;

    @BeforeAll
    static void startTheBrokerAndThePki() throws Exception {
        assumeTrue(LocalMeshBroker.imageAvailable(), "the local image " + LocalMeshBroker.IMAGE
                + " is not in this Docker daemon (it is loaded from the Solace support-portal tarball, never pulled)");
        broker = LocalMeshBroker.start();
        pki = new AgentGatewayTestPki(Files.createTempDirectory("g-a0-pki"), true);
        try (ServerSocket socket = new ServerSocket(0)) {
            gatewayPort = socket.getLocalPort();
        }
    }

    // No @AfterAll stops the broker. JUnit runs a class's @AfterAll methods BEFORE the Spring extension's own afterAll, so stopping it
    // there would leave the cached context's JCSMP session reconnecting (reconnectRetries=-1) while @DirtiesContext closes it, and that
    // close parks in JCSMP's reconnect wait (E2eStack, FLAG T6.6-1). The context closes with the class while the broker is still up;
    // Testcontainers' Ryuk reaps the broker container at JVM exit.

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        broker.meshProperties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.agent.gateway.enabled", () -> "true");
        registry.add("sm.agent.gateway.port", () -> gatewayPort);
        registry.add("sm.agent.gateway.certificate-file", () -> pki.serverCertificate.toString());
        registry.add("sm.agent.gateway.private-key-file", () -> pki.serverKey.toString());
        registry.add("sm.agent.gateway.trust-bundle-file", () -> pki.trustBundle.toString());
        registry.add("sm.control.agent-gateway.ca.connect-endpoint", () -> "localhost:" + gatewayPort);
        registry.add("sm.control.agent-gateway.ca.key-store-file", () -> pki.keystore.toString());
        registry.add("sm.control.agent-gateway.ca.key-alias", () -> AgentGatewayTestPki.CA_ALIAS);
        registry.add("sm.control.agent-gateway.ca.key-store-password", () -> AgentGatewayTestPki.PASSWORD);
    }

    private static ManagedChannel channel(Path[] clientMaterial) throws Exception {
        var ssl = GrpcSslContexts.forClient().trustManager(pki.trustBundle.toFile());
        if (clientMaterial != null) {
            ssl.keyManager(clientMaterial[0].toFile(), clientMaterial[1].toFile());
        }
        SslContext sslContext = ssl.build();
        return NettyChannelBuilder.forAddress("localhost", gatewayPort).sslContext(sslContext).build();
    }

    private static AgentGatewayOuterClass.AgentToGateway sessionOpen() {
        return AgentGatewayOuterClass.AgentToGateway.newBuilder().setSessionOpen(AgentGatewayOuterClass.SessionOpen.newBuilder()
                .setEnvelope(AgentGatewayOuterClass.Envelope.newBuilder().setMessageId(UUID.randomUUID().toString())
                        .setSentAt(Timestamps.fromMillis(Instant.now().toEpochMilli())))
                .setProtocolVersion(1).setAgentVersion("g-a0-java-client")
                .setAgentClock(Timestamps.fromMillis(Instant.now().toEpochMilli()))).build();
    }

    @Test
    void oneJvmServesTheMtlsListenerAndHoldsAConnectedJcsmpSession() throws Exception {
        // 1. The JCSMP session: the broker itself reports the launcher's mesh identity as a connected client.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        int connected = 0;
        while (System.nanoTime() < deadline && (connected = broker.connectedClients(LocalMeshBroker.PUBLISHER_USERNAME)) < 1) {
            Thread.sleep(500);
        }
        assertTrue(connected >= 1, "the launcher's JCSMP session never connected to the local broker");
        System.out.println("G-A0 evidence: broker reports " + connected + " connected client(s) for '" + LocalMeshBroker.PUBLISHER_USERNAME
                + "' (the launcher's JCSMP session), same JVM as the listener");

        // 2. The listener is the composed bean, running.
        assertTrue(context.getBean(AgentGatewayServer.class).isRunning());

        // 3. mTLS handshake and one RPC with a certificate issued by the test keystore's CA.
        ManagedChannel channel = channel(pki.client("g-a0-client"));
        try {
            CompletableFuture<AgentGatewayOuterClass.GatewayToAgent> first = new CompletableFuture<>();
            CompletableFuture<Void> closed = new CompletableFuture<>();
            StreamObserver<AgentGatewayOuterClass.AgentToGateway> up = AgentGatewayGrpc.newStub(channel)
                    .connect(new StreamObserver<>() {
                        @Override public void onNext(AgentGatewayOuterClass.GatewayToAgent value) { first.complete(value); }
                        @Override public void onError(Throwable t) { first.completeExceptionally(t); closed.completeExceptionally(t); }
                        @Override public void onCompleted() { closed.complete(null); }
                    });
            up.onNext(sessionOpen());
            AgentGatewayOuterClass.GatewayToAgent answer = first.get(30, TimeUnit.SECONDS);
            assertTrue(answer.hasError(), "an unenrolled certificate is refused with a coded Error: " + answer);
            assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_UNAUTHENTICATED_IDENTITY, answer.getError().getCode());
            up.onCompleted();
            closed.get(30, TimeUnit.SECONDS);
            System.out.println("G-A0 evidence: Java gRPC client with a test-CA certificate completed the mTLS handshake and the Connect RPC "
                    + "on localhost:" + gatewayPort + "; answer = Error(" + answer.getError().getCode() + ")");
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    void aCertificateFromAnotherCaDoesNotCompleteTheHandshake() throws Exception {
        ManagedChannel channel = channel(pki.clientFromAnotherCa("stranger"));
        try {
            CompletableFuture<Throwable> failure = new CompletableFuture<>();
            StreamObserver<AgentGatewayOuterClass.AgentToGateway> up = AgentGatewayGrpc.newStub(channel)
                    .connect(new StreamObserver<>() {
                        @Override public void onNext(AgentGatewayOuterClass.GatewayToAgent value) { failure.complete(null); }
                        @Override public void onError(Throwable t) { failure.complete(t); }
                        @Override public void onCompleted() { failure.complete(null); }
                    });
            up.onNext(sessionOpen());
            Throwable t = failure.get(30, TimeUnit.SECONDS);
            assertNotNull(t, "the gateway must not answer a peer certificate it does not trust");
            assertEquals(Status.Code.UNAVAILABLE, Status.fromThrowable(t).getCode(), t.toString());
        } finally {
            channel.shutdownNow();
        }
    }

    @Test
    void aClientWithNoCertificateReachesTheListenerButIsNotAuthenticated() throws Exception {
        // ClientAuth.OPTIONAL: Enroll has no client certificate yet. Connect without one is refused as unauthenticated.
        ManagedChannel channel = channel(null);
        try {
            CompletableFuture<AgentGatewayOuterClass.GatewayToAgent> first = new CompletableFuture<>();
            StreamObserver<AgentGatewayOuterClass.AgentToGateway> up = AgentGatewayGrpc.newStub(channel)
                    .connect(new StreamObserver<>() {
                        @Override public void onNext(AgentGatewayOuterClass.GatewayToAgent value) { first.complete(value); }
                        @Override public void onError(Throwable t) { first.completeExceptionally(t); }
                        @Override public void onCompleted() { }
                    });
            up.onNext(sessionOpen());
            AgentGatewayOuterClass.GatewayToAgent answer = first.get(30, TimeUnit.SECONDS);
            assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_UNAUTHENTICATED_IDENTITY, answer.getError().getCode());
        } finally {
            channel.shutdownNow();
        }
    }
}
