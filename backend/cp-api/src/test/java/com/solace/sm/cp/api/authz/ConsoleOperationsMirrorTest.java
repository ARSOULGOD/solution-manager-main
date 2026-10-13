package com.solace.sm.cp.api.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The server-side registry against the published contract.
 *
 * <p>Check 11 proves {@code console.yaml} names only approved verbs. It cannot prove the
 * server enforces them, because it never sees Java. This test closes that half: every
 * operation the contract publishes must be registered here against the same verb, and every
 * operation registered here must appear in the contract. A handler added without a contract
 * entry, or a contract entry added without a registration, turns this red.
 */
class ConsoleOperationsMirrorTest {

    /** The four operations the session authorizes, not a verb. */
    private static final Set<String> SESSION_AUTHORIZED =
            Set.of("login", "logout", "getIdentity", "rotateOwnCredential");

    /**
     * Reads authentication-only reads that carry no catalog verb by design (rows 8-10, §5.4 row 53, and plan 013
     * T4's {@code getConnectionTypes}, {@code UI-D27}: identical for every caller, SM-owned, never package-supplied).
     */
    private static final Set<String> AUTHENTICATION_ONLY =
            Set.of("listOrganizations", "listSubOrganizations", "listEnvironments", "getCapabilities",
                    "getConnectionTypes");

    private static final Map<String, String> NON_CATALOG_MARKERS = Map.of(
            "login", "none.establishesSession",
            "logout", "none.authenticationOnly",
            "getIdentity", "none.authenticationOnly",
            "rotateOwnCredential", "local.rotateOwnCredential",
            "listOrganizations", "none.authenticationOnly",
            "listSubOrganizations", "none.authenticationOnly",
            "listEnvironments", "none.authenticationOnly",
            "getCapabilities", "none.authenticationOnly",
            "getConnectionTypes", "none.authenticationOnly");

    private static final Pattern OPERATION_ID = Pattern.compile("^\\s*operationId:\\s*(\\S+)\\s*$");
    private static final Pattern PERMISSION =
            Pattern.compile("^\\s*x-sm-permission:\\s*(\\S+)\\s*$");

    private static Path contract() {
        Path fromModule = Path.of("../../contracts/openapi/console.yaml");
        return Files.exists(fromModule) ? fromModule.normalize()
                : Path.of("contracts/openapi/console.yaml");
    }

    /** operationId → x-sm-permission, in document order. */
    private static Map<String, String> published() throws IOException {
        Path doc = contract();
        assertThat(doc).as("console.yaml is the contract this registry mirrors").exists();

        Map<String, String> out = new LinkedHashMap<>();
        String pending = null;
        for (String line : Files.readAllLines(doc, StandardCharsets.UTF_8)) {
            Matcher id = OPERATION_ID.matcher(line);
            if (id.matches()) {
                pending = id.group(1);
                continue;
            }
            Matcher perm = PERMISSION.matcher(line);
            if (perm.matches() && pending != null) {
                out.put(pending, perm.group(1));
                pending = null;
            }
        }
        return out;
    }

    @Test
    void everyContractedOperationWithAVerbIsRegisteredAgainstThatSameVerb() throws IOException {
        List<String> mismatches = new ArrayList<>();

        published().forEach((operationId, verbId) -> {
            boolean carriesNoVerb = verbId.startsWith("none.") || verbId.startsWith("local.");
            if (carriesNoVerb) {
                if (ConsoleOperations.permissionFor(operationId).isPresent()) {
                    mismatches.add(operationId + " is session-authorized in the contract ("
                            + verbId + ") but registered with a verb");
                }
                return;
            }
            ConsoleOperations.permissionFor(operationId).ifPresentOrElse(registered -> {
                if (!registered.id().equals(verbId)) {
                    mismatches.add(operationId + ": contract says " + verbId
                            + ", registry says " + registered.id());
                }
            }, () -> mismatches.add(operationId + ": contracted against " + verbId
                    + " but not registered — the handler would be unauthorized"));
        });

        assertThat(mismatches).as("contract and registry must agree operation-for-operation")
                .isEmpty();
    }

    @Test
    void everyRegisteredOperationIsPublishedInTheContract() throws IOException {
        Map<String, String> published = published();
        assertThat(ConsoleOperations.registry().all().keySet())
                .as("a registered operation that no contract publishes is a server surface "
                        + "nobody agreed to")
                .allMatch(published::containsKey);
    }

    @Test
    void theOnlyUnregisteredOperationsAreTheOnesAuthorizedWithoutAVerb() throws IOException {
        List<String> unregistered = published().keySet().stream()
                .filter(id -> ConsoleOperations.permissionFor(id).isEmpty())
                .sorted()
                .toList();

        assertThat(unregistered)
                .as("exactly the session-authorized four, the three authentication-only "
                        + "hierarchy reads and getCapabilities. If this set grows, an operation has "
                        + "quietly lost its check.")
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.Stream.concat(SESSION_AUTHORIZED.stream(),
                                AUTHENTICATION_ONLY.stream()).sorted().toList());
    }

    @Test
    void everyNonCatalogOperationUsesItsExactApprovedMarker() throws IOException {
        Map<String, String> actual = new LinkedHashMap<>();
        published().forEach((operationId, marker) -> {
            if (marker.startsWith("none.") || marker.startsWith("local.")) {
                actual.put(operationId, marker);
            }
        });

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(NON_CATALOG_MARKERS);
    }

    @Test
    void noRegisteredOperationUsesAVerbTheApprovalWithheld() {
        // Belt and braces: Permission cannot hold a withheld verb (the catalog mirror proves
        // that), so this asserts the registry has not found some other way to name one.
        assertThat(ConsoleOperations.registry().all().values())
                .allMatch(p -> p.consequence() != ConsequenceClass.UNDECIDED);
    }

    @Test
    void theStopOperationIsRegisteredAndHighConsequence() {
        assertThat(ConsoleOperations.permissionFor("stopInstance"))
                .as("ruling OR-18 assigns the stop endpoint to plan 007; it must be enforceable")
                .contains(Permission.INSTANCE_STOP);
        assertThat(Permission.INSTANCE_STOP.consequence()).isEqualTo(ConsequenceClass.HIGH);
    }
}
