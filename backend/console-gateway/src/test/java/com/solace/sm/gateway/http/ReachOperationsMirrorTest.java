package com.solace.sm.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.api.authz.ConsoleOperations;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The server's reach set against the published contract (plan 010 T1.3, S0 point 24).
 *
 * <p>{@code ConsoleOperationsMirrorTest} proves every operation carries the contract's verb. It
 * cannot see {@code x-sm-scope}, which decides WHICH check the interceptor makes for that verb: the
 * single-resource {@code require} or the reach pre-check. An operation the contract calls
 * {@code reach} but the server classifies as {@code Catalog} would 403 every principal bound below the
 * organization; the converse would let an organization-scoped catalog operation through on a
 * mere "held anywhere". Both directions are pinned here.
 */
class ReachOperationsMirrorTest {

    private static final Pattern OPERATION_ID = Pattern.compile("^\\s*operationId:\\s*(\\S+)\\s*$");
    private static final Pattern SCOPE = Pattern.compile("^\\s*x-sm-scope:\\s*(\\S+)\\s*$");
    private static final Pattern PERMISSION = Pattern.compile("^\\s*x-sm-permission:\\s*(\\S+)\\s*$");

    @Test
    void theReachSetMirrorsConsoleYamlExactly() throws IOException {
        Map<String, String> scopes = new LinkedHashMap<>();
        String current = null;
        for (String line : Files.readAllLines(contract(), StandardCharsets.UTF_8)) {
            Matcher id = OPERATION_ID.matcher(line);
            if (id.matches()) {
                current = id.group(1);
                continue;
            }
            Matcher scope = SCOPE.matcher(line);
            if (scope.matches() && current != null) {
                scopes.put(current, scope.group(1));
            }
        }
        Set<String> contractedReach = new TreeSet<>();
        scopes.forEach((operationId, scope) -> {
            if ("reach".equals(scope)) {
                contractedReach.add(operationId);
            }
        });

        assertThat(contractedReach).as("console.yaml must publish at least the seven S0-24/OR-57 operations")
                .hasSizeGreaterThanOrEqualTo(7);
        assertThat(new TreeSet<>(OperationPolicy.reachOperations()))
                .as("the operations OperationPolicy classifies as Reach are exactly those console.yaml marks"
                        + " `x-sm-scope: reach`")
                .isEqualTo(contractedReach);
        for (String operationId : contractedReach) {
            assertThat(OperationPolicy.classify(operationId))
                    .as(operationId + " is `reach` in the contract").isInstanceOf(OperationClassification.Reach.class);
        }
        for (String operationId : scopes.keySet()) {
            if (!contractedReach.contains(operationId) && ConsoleOperations.permissionFor(operationId).isPresent()) {
                assertThat(OperationPolicy.classify(operationId))
                        .as(operationId + " is `" + scopes.get(operationId) + "` in the contract, not reach")
                        .isInstanceOf(OperationClassification.Catalog.class);
            }
        }
    }

    @Test
    void everyReachOperationCarriesTheContractsVerb() throws IOException {
        Map<String, String> verbs = new LinkedHashMap<>();
        String current = null;
        for (String line : Files.readAllLines(contract(), StandardCharsets.UTF_8)) {
            Matcher id = OPERATION_ID.matcher(line);
            if (id.matches()) {
                current = id.group(1);
                continue;
            }
            Matcher permission = PERMISSION.matcher(line);
            if (permission.matches() && current != null) {
                verbs.put(current, permission.group(1));
            }
        }
        for (String operationId : OperationPolicy.reachOperations()) {
            OperationClassification.Reach reach = (OperationClassification.Reach) OperationPolicy.classify(operationId);
            assertThat(reach.permission().id()).as(operationId).isEqualTo(verbs.get(operationId));
        }
    }

    private static Path contract() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("contracts/openapi/console.yaml");
            if (Files.exists(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("cannot locate contracts/openapi/console.yaml from "
                + Path.of("").toAbsolutePath());
    }
}
