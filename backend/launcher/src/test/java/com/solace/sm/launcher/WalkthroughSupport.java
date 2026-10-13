package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.events.EventJson;
import com.solace.sm.launcher.ApplyItSupport.Declared;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The shared spine of the two walkthroughs (plan 010 T6.6): bring the stack up, register the target through the operator
 * commands, watch the local broker, and - whatever happens - put the host's file back and leave the evidence behind.
 *
 * <p>Two concrete families extend the two scenario classes below: the LIVE ITs ({@code SkeletonWalkthroughIT},
 * {@code MeshResilienceIT}, gated on {@code sm.walkthrough.ssh.host}) and the REHEARSALS (the same scenarios against an
 * in-memory host, always run), so the scenario logic is one body of code that has been executed before it ever meets a real
 * host.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class WalkthroughSupport {

    /** The keyed spelling of a config hash: {@code hmacsha256v<n>:<64 hex>} (OR-32, B1). */
    static final Pattern KEYED_HASH = Pattern.compile("^hmacsha256v[0-9]+:[0-9a-f]{64}$");

    static final Duration MANUAL_APPLY = Duration.ofSeconds(180);
    static final Duration GATED_APPLY = Duration.ofMinutes(7);
    static final Duration NOTICE = Duration.ofSeconds(30);

    EvidenceLog evidence;
    E2eStack stack;
    E2eStack.App app;
    MeshEventLog events;

    UUID solutionId;
    Declared declared;
    PlacementRef ref;
    E2eStack.OriginalConfig original;
    boolean containerRestarted;

    abstract E2eStack createStack(EvidenceLog evidence);

    /** The file-name prefix the evidence is written under. */
    abstract String evidencePrefix();

    @BeforeAll
    void bringUp() throws Exception {
        evidence = new EvidenceLog(evidencePrefix());
        stack = createStack(evidence);
        evidence.record("Setup", "mode: " + stack.mode + "; target: " + stack.target.describeForEvidence());
        evidence.record("Setup", "organization " + stack.organization() + ", unit " + stack.world.unit()
                + ", walkthrough environment " + stack.world.envOne() + ", sibling environment " + stack.world.envTwo());
        evidence.record("Setup", "SM mesh events go to a LOCAL broker started by this run from " + LocalMeshBroker.IMAGE
                + " (vpn " + LocalMeshBroker.VPN + "), never a remote broker; database: postgres:14 (Testcontainers)");
        app = stack.boot();
        UUID targetId = stack.registerTarget();
        evidence.record("Setup", "installing operator commands ran: EntitlementMain (cache, max "
                + E2eStack.MAX_INSTANCES + " instances), TargetRegistrationMain (target " + targetId
                + ", owned at the walkthrough environment), TargetConnectionMain (envelope root "
                + stack.target.allowedConfigRoot() + ", container prefix " + stack.target.allowedContainerPrefix() + ")");
        events = observeOrganization();
    }

    @AfterAll
    void tearDown() {
        List<String> restore = List.of();
        try {
            if (stack != null) {
                if (app != null && ref != null) {
                    restore = stack.restoreOriginal(app, ref, original, containerRestarted);
                }
                for (String[] skip : skips()) {
                    evidence.skip(skip[0], skip[1]);
                }
            }
        } finally {
            try {
                if (evidence != null && stack != null) {
                    evidence.writeTo(Path.of(System.getProperty("sm.walkthrough.evidence.dir",
                            "target/walkthrough-evidence")), evidencePrefix());
                }
            } finally {
                if (events != null) {
                    events.close();
                }
                if (stack != null) {
                    stack.close();
                }
            }
        }
        // Loud, and last: a run that leaves the host's file different from the original FAILS, whatever else passed.
        for (String line : restore) {
            if (line.startsWith("MANUAL") || line.contains("DIFFERS from the original")) {
                throw new AssertionError("THE HOST'S ORIGINAL CONFIGURATION WAS NOT RESTORED BY THIS RUN: " + line
                        + " (the evidence file has the full instruction)");
            }
        }
    }

    /** The skips this walkthrough records, each with its reason: {@code {what, reason}}. */
    List<String[]> skips() {
        return List.<String[]>of(new String[] {"live notify for the browser (a browser session subscribing to the mesh)",
                "open items 32/33/35: a ticket minted today grants organization-wide notify subscription, which an "
                        + "environment-scoped principal's REST scope forbids, so the subscription shape that enforces the "
                        + "approved scope model does not exist yet (plan 007 Task 10, hard-gated)"});
    }

    MeshEventLog observeOrganization() throws Exception {
        return stack.broker.observe("sm/v1/" + stack.organization() + "/notify/>");
    }

    // ---- the wire, as the local broker delivered it ------------------------------------------------------------

    String topic(UUID instance, String aspect, UUID applyId) {
        return "sm/v1/" + stack.organization() + "/notify/instance/" + instance + "/" + aspect
                + (applyId == null ? "" : "/" + applyId);
    }

    JsonNode json(MeshEventLog.Received received) {
        try {
            return EventJson.mapper().readTree(received.body());
        } catch (java.io.IOException e) {
            throw new AssertionError("a notice on " + received.topic() + " was not JSON");
        }
    }

    /** Waits for exactly the notices of one topic and returns them; fails with the topic when none arrives. */
    List<MeshEventLog.Received> awaitNotices(MeshEventLog log, String topic, int atLeast) throws InterruptedException {
        List<MeshEventLog.Received> found = log.awaitCount(r -> r.topic().equals(topic), atLeast, NOTICE);
        assertTrue(found.size() >= atLeast, "expected " + atLeast + " notice(s) on " + topic + " within " + NOTICE
                + ", the local broker delivered " + found.size());
        return found;
    }

    /**
     * How many {@code instance.rollout} notices of one step the local broker delivered for the request: waits for the
     * first, then a settling moment so a duplicate would have arrived too, and counts.
     */
    long rolloutNotices(UUID instance, UUID applyId, String step) throws InterruptedException {
        java.util.function.Predicate<MeshEventLog.Received> wanted = r -> r.topic().equals(topic(instance, "rollout", applyId))
                && step.equals(json(r).at("/payload/step").asText());
        events.awaitCount(wanted, 1, NOTICE);
        Thread.sleep(300);
        return events.matching(wanted).size();
    }

    /** The journal of one request as one readable line: {@code STEP#attempt:OUTCOME(error)} in the order it happened. */
    String journalLine(UUID applyId) {
        StringBuilder line = new StringBuilder();
        for (Map<String, Object> row : stack.journal(applyId)) {
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(row.get("step")).append('#').append(row.get("attempt_no")).append(':')
                    .append(row.get("outcome") == null ? "OPEN" : row.get("outcome"));
            if (row.get("error_class") != null) {
                line.append('(').append(row.get("error_class")).append(')');
            }
        }
        return line.toString();
    }

    /** Asserts the {@code instance.apply-outcome} notice for the request and returns its envelope. */
    JsonNode assertApplyOutcomeNotice(MeshEventLog log, UUID instance, UUID applyId, String state, JsonNode observation)
            throws InterruptedException {
        List<MeshEventLog.Received> outcomes = awaitNotices(log, topic(instance, "apply-outcome", applyId), 1);
        JsonNode envelope = json(outcomes.get(outcomes.size() - 1));
        assertEquals("instance.apply-outcome", envelope.get("event").asText());
        assertEquals(stack.organization().toString(), envelope.get("organization").asText());
        assertEquals(applyId.toString(), envelope.at("/payload/applyId").asText());
        assertEquals(state, envelope.at("/payload/state").asText());
        assertEquals(instance.toString(), envelope.at("/entity/instanceId").asText());
        assertNotNull(envelope.at("/entity/placementId").asText(null));
        if (observation != null) {
            assertEquals(observation.get("stateVersion").asLong(), envelope.get("stateVersion").asLong(),
                    "the notice carries the request's own stamp, the only ordering input a consumer has");
        }
        return envelope;
    }

    /** The MATERIALIZE attempt that closed SUCCEEDED, its read-back equal to the hash the intent bound (B1). */
    Map<String, Object> assertMaterializedWithTheKeyedHash(UUID applyId) {
        Map<String, Object> succeeded = stack.journal(applyId).stream()
                .filter(row -> "MATERIALIZE".equals(row.get("step")) && "SUCCEEDED".equals(row.get("outcome")))
                .reduce((first, second) -> second).orElseThrow(() -> new AssertionError(
                        "no MATERIALIZE attempt closed SUCCEEDED for " + applyId + ": " + stack.journal(applyId)));
        String observed = (String) succeeded.get("observed_hash");
        String intended = (String) succeeded.get("config_hash");
        assertNotNull(observed, "the read-back is the evidence");
        assertTrue(KEYED_HASH.matcher(observed).matches(), "the observed hash is spelt hmacsha256v<n>:<hex>: " + observed);
        assertEquals(intended, observed, "the host's read-back equals the intent's bound config_hash");
        return succeeded;
    }
}
