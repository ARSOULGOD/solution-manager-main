package com.solace.sm.cp.api.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.audit.RequestOrigin;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Global constraint 9: no token, CSR or single-use nonce reaches a log line through a record's default {@code toString}
 * (plan 015 T2.2 review finding 8). Each secret is a distinctive string so a leak is a substring match.
 */
class AgentRecordsRedactionTest {

    private static final String NONCE = "single-use-nonce-0123456789abcdef";
    private static final String TOKEN = "enrolment-token-0123456789abcdef";

    @Test
    void aLeaseRequestDoesNotPrintTheConsumedNonce() {
        AgentLeaseRequest request = new AgentLeaseRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "ab12", 1, 1,
                Instant.now(), "req-1", NONCE, List.of(UUID.randomUUID()));
        assertThat(request.toString()).doesNotContain(NONCE).contains("req-1");
    }

    @Test
    void aLeaseRequestRequiresTheThumbprintTheOrganizationAndTheTarget() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        assertThatThrownBy(() -> new AgentLeaseRequest(id, id, id, " ", 1, 1, now, "r", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentLeaseRequest(id, id, id, null, 1, 1, now, "r", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentLeaseRequest(null, id, id, "ab", 1, 1, now, "r", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLeaseMaterialDoesNotPrintTheNonce() {
        AgentLeaseMaterial material = new AgentLeaseMaterial(UUID.randomUUID(), "ab", UUID.randomUUID(), List.of(), 1, 1,
                Instant.now(), Instant.now().plusSeconds(60), "aud", NONCE, List.of(), Map.of("APPLY", Duration.ofSeconds(1)),
                Duration.ZERO, Instant.now());
        assertThat(material.toString()).doesNotContain(NONCE);
    }

    @Test
    void anEnrollmentRequestDoesNotPrintTheTokenOrTheCsr() {
        AgentEnrollmentRequest request = new AgentEnrollmentRequest(TOKEN, new byte[] {42, 43}, "0.1", List.of(1), "linux", "arm64",
                "docker", new RequestOrigin("203.0.113.5", UUID.fromString("00000000-0000-4000-8000-000000000001")));
        // The CSR's renderings, not a digit pair: a random correlation id contains "42" about one run in seven (a v4 UUID string
        // has 25 free hex pairs), so the id is fixed and the assertion names what a leak would look like.
        assertThat(request.toString()).doesNotContain(TOKEN).doesNotContain("[42, 43]").doesNotContain("Kis=")   // Base64 of 0x2A 0x2B
                .doesNotContain("2a2b").doesNotContain("2A2B");
    }

    @Test
    void anIssuedTokenDoesNotPrintTheToken() {
        assertThat(new AgentEnrolment.IssuedToken(UUID.randomUUID(), TOKEN, Instant.now()).toString()).doesNotContain(TOKEN);
    }
}
