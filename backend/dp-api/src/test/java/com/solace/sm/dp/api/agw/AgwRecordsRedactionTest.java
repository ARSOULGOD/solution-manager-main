package com.solace.sm.dp.api.agw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plan 015 Global constraint 9 / I6: no token, nonce, rendered byte or plain digest reaches a {@code toString}. */
class AgwRecordsRedactionTest {
    private static final String TOKEN = "enroll-token-SECRET-4f7a91";
    private static final String NONCE = "lease-nonce-SECRET-91bc02";
    private static final String BYTES = "rendered-config-body-SECRET-aa77";
    private static final String PLAIN_DIGEST = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String EXPECTED_DIGEST = "sha256:fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
    private static final String EFFECTIVE = "sha256:aaaabbbbccccddddaaaabbbbccccddddaaaabbbbccccddddaaaabbbbccccdddd";
    private static final String CSR = "csr-der-SECRET-77";

    private static final OperationCorrelation CORRELATION = new OperationCorrelation("apply", "intent", "key", "attempt", "step");

    private static void assertNoSecret(String rendered) {
        assertThat(rendered)
                .doesNotContain(TOKEN, NONCE, BYTES, PLAIN_DIGEST, EXPECTED_DIGEST, EFFECTIVE, CSR)
                .doesNotContain(PLAIN_DIGEST.substring("sha256:".length()))
                .doesNotContain(EXPECTED_DIGEST.substring("sha256:".length()))
                .doesNotContain("[B@");
    }

    @Test
    void enrollmentRequestPrintsNeitherTokenNorCsr() {
        var request = new EnrollmentRequest("req-1", TOKEN, CSR.getBytes(StandardCharsets.UTF_8), "1.0",
                List.of(1), "linux", "amd64", "docker",
                new AgentRequestOrigin(UUID.randomUUID(), "10.0.0.1"));
        assertNoSecret(request.toString());
        assertThat(request.toString()).contains("req-1");
    }

    @Test
    void leaseGrantPrintsNoNonce() {
        var lease = new LeaseGrant(UUID.randomUUID(), "thumb", UUID.randomUUID(), List.of(UUID.randomUUID()), 1, 1,
                Instant.now(), Instant.now().plusSeconds(60), "dp", NONCE, List.of(AgentCapability.APPLY_CONFIG),
                Map.of(AgentOperation.APPLY, Duration.ofSeconds(30)), Duration.ZERO, Instant.now());
        assertNoSecret(lease.toString());
        assertNoSecret(new IdentityReconciliation(new AgentSessionRef(UUID.randomUUID(), 1),
                IdentityReconciliation.Status.REISSUED, lease, 1, 1).toString());
    }

    @Test
    void commandNoticePrintsNoDigest() {
        var notice = new CommandNotice(new CommandId(UUID.randomUUID()), UUID.randomUUID(), 3, AgentOperation.APPLY,
                PLAIN_DIGEST, Instant.now().plusSeconds(60), CORRELATION);
        assertNoSecret(notice.toString());
        assertThat(notice.toString()).contains("generation=3");
    }

    @Test
    void commandPayloadAndOfferPrintNeitherBytesNorDigests() {
        var payload = new CommandPayload(BYTES.getBytes(StandardCharsets.UTF_8), PLAIN_DIGEST, EXPECTED_DIGEST);
        assertNoSecret(payload.toString());
        var offer = new CommandOffer(new CommandId(UUID.randomUUID()), 4, "key", UUID.randomUUID(),
                AgentOperation.REMATERIALIZE, null, Instant.now().plusSeconds(60), CORRELATION, payload);
        assertNoSecret(offer.toString());
        assertThat(offer.toString()).contains("generation=4");
    }

    @Test
    void reportItemAndObservationPrintNoPlainDigest() {
        var item = new ReportItem("item-1", 1, "binding", "replica", "inc", 2, null, EFFECTIVE,
                "m", "v", "c", CORRELATION, null, null);
        assertNoSecret(item.toString());
        var observation = new AgentObservation(true, null, EFFECTIVE, 3L, Instant.now(), Instant.now());
        assertNoSecret(observation.toString());
    }

    @Test
    void ackAndCompletionPrintNoAgentFreeText() {
        var ack = new AgentAck("req", 2, AgentOutcome.REFUSED, "CODE", "free-text-SECRET-detail", Instant.now());
        assertThat(ack.toString()).doesNotContain("free-text-SECRET-detail").contains("CODE");
        var completion = new CommandCompletion(new CommandId(UUID.randomUUID()), 2, AgentOperation.APPLY,
                AgentOutcome.REFUSED, "CODE", "free-text-SECRET-detail", Instant.now());
        assertThat(completion.toString()).doesNotContain("free-text-SECRET-detail").contains("CODE");
    }

    @Test
    void enrollmentResultAndIssuancePrintNoCertificateBytesAndTheResultCarriesNoTenant() {
        byte[] der = CSR.getBytes(StandardCharsets.UTF_8);
        var envelope = new EnrollmentEnvelope("/etc/sm", "sm-");
        var result = new EnrollmentResult(UUID.randomUUID(), List.of(der), List.of(der), Instant.now(),
                Instant.now().plusSeconds(60), "gw:443", UUID.randomUUID(), UUID.randomUUID(), envelope,
                List.of(AgentCapability.APPLY_CONFIG), 0, 0);
        assertNoSecret(result.toString());
        var issuance = new EnrollmentIssuance(UUID.randomUUID(), UUID.randomUUID(), "cache", "2.4.0", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), List.of(der), List.of(der), Instant.now(),
                Instant.now().plusSeconds(60), "gw:443", envelope, UUID.randomUUID(), List.of(), 0, 0);
        assertNoSecret(issuance.toString());
        assertThat(EnrollmentResult.class.getRecordComponents()).extracting("name")
                .noneMatch(n -> n.toString().toLowerCase().contains("tenant"));
    }

    @Test
    void aSessionOpeningNeedsAnAgentClockAndAnOriginAddressMustBeBare() {
        assertThatThrownBy(() -> new SessionOpening(1, "v", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentRequestOrigin(UUID.randomUUID(), "/10.0.0.1:5555"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AgentRequestOrigin(UUID.randomUUID(), "fe80::1%eth0").clientAddress()).isEqualTo("fe80::1%eth0");
    }

    @Test
    void anIntentKeyMustMatchItsCorrelation() {
        var placement = new AgentPlacementRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> new AgentCommandIntent(UUID.randomUUID(), placement, "other", AgentOperation.APPLY,
                null, Instant.now(), CORRELATION, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AgentCommandIntent(UUID.randomUUID(), placement, "key", AgentOperation.APPLY, null,
                Instant.now(), CORRELATION, null).intentKey()).isEqualTo("key");
    }

    // ---- the structural guard: a new record cannot silently leak or carry an agent-supplied tenant ----

    /** Records that legitimately hold a tenant: all DP-internal, none built from an agent-supplied value (I5). */
    private static final java.util.Set<String> TENANT_HOLDERS = java.util.Set.of(
            "AgentPlacementRef", "AgentIdentity", "EnrollmentIssuance");
    private static final java.util.Set<String> SECRET_COMPONENTS = java.util.Set.of(
            "token", "nonce", "csrDer", "bytes", "configSha256", "expectedCurrentSha256", "reportedSha256",
            "effectiveHash", "detailMessage");

    /**
     * Reflection cannot tell a record's synthesized {@code toString} from an explicit one (both are declared methods),
     * so the guard reads the type's source file. A type with no source file (a planted local record) has no override.
     */
    static boolean declaresToString(Class<?> type) {
        java.nio.file.Path source = java.nio.file.Path.of("src/main/java", type.getName().replace('.', '/') + ".java");
        try {
            return java.nio.file.Files.exists(source)
                    && java.nio.file.Files.readString(source).contains("public String toString()");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static List<String> violations(List<Class<?>> records) {
        var out = new java.util.ArrayList<String>();
        for (Class<?> type : records) {
            boolean overridesToString = declaresToString(type);
            for (var component : type.getRecordComponents()) {
                String name = component.getName();
                boolean bytes = component.getType() == byte[].class || component.getGenericType().getTypeName().contains("byte[]");
                if ((bytes || SECRET_COMPONENTS.contains(name)) && !overridesToString) {
                    out.add(type.getSimpleName() + "." + name + ": secret-bearing component without a toString override");
                }
                if (name.toLowerCase().contains("tenant") && !TENANT_HOLDERS.contains(type.getSimpleName())) {
                    out.add(type.getSimpleName() + "." + name + ": a tenant component outside the DP-internal allow-list");
                }
            }
        }
        return out;
    }

    private static List<Class<?>> agwRecords() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of(AgentSessionRef.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve("com/solace/sm/dp/api/agw");
        var found = new java.util.ArrayList<Class<?>>();
        try (var files = java.nio.file.Files.walk(root)) {
            for (var path : (Iterable<java.nio.file.Path>) files::iterator) {
                String file = path.getFileName().toString();
                if (!file.endsWith(".class")) continue;
                Class<?> type = Class.forName("com.solace.sm.dp.api.agw." + root.relativize(path).toString()
                        .replace(java.io.File.separatorChar, '.').replaceAll("\\.class$", ""));
                if (type.isRecord()) found.add(type);
            }
        }
        return found;
    }

    @Test
    void everyAgwRecordIsRedactedAndTenantFree() throws Exception {
        var records = agwRecords();
        assertThat(records).hasSizeGreaterThan(20);
        assertThat(violations(records)).isEmpty();
    }

    @Test
    void theGuardReportsAPlantedLeakyRecordAndAPlantedTenantComponent() {
        record PlantedLeak(byte[] bytes, String nonce) { }
        record PlantedTenant(java.util.UUID tenantId) { }
        assertThat(violations(List.of(PlantedLeak.class, PlantedTenant.class))).hasSize(3);
    }

    @Test
    void thePayloadDefensivelyCopiesItsBytes() {
        byte[] source = BYTES.getBytes(StandardCharsets.UTF_8);
        var payload = new CommandPayload(source, PLAIN_DIGEST, null);
        source[0] = 'X';
        assertThat(payload.bytes()).startsWith((byte) BYTES.charAt(0));
    }

    @Test
    void aMalformedWireCommandIdIsAnUnknownCommand() {
        assertThatThrownBy(() -> CommandId.parse("cmd-1")).isInstanceOfSatisfying(GatewayPortException.class,
                e -> assertThat(e.code()).isEqualTo(GatewayCode.UNKNOWN_COMMAND));
    }

    @Test
    void theSessionRefAndIdentityCarryNoAgentSuppliedTenant() {
        // AgentSessionRef has exactly (agentId, fencingEpoch): no tenant, environment, target or label component.
        assertThat(AgentSessionRef.class.getRecordComponents()).extracting("name").containsExactly("agentId", "fencingEpoch");
    }
}
