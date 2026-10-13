package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Request body of {@code declareInstance} (console.yaml, {@code additionalProperties: false} at every level it is
 * declared).
 *
 * <p>Structural faults (a missing field, a wrong type, a bound, a stray key) are {@code 400}, exactly as
 * {@link LoadPackageRequest}. Semantic faults the contract names as {@code 422 VALIDATION_FAILED} are NOT decided
 * here: they need the catalog, the target and the package. {@code acquisition} is therefore kept as a
 * {@link JsonNode}: the contract makes an {@code acquisition} carrying any property but {@code mode} (a host, in
 * particular, {@code OR-82}) a {@code 422} naming the field, which a typed record with a refusing any-setter would
 * turn into a {@code 400}.
 *
 * <p>⛔ There is no {@code organizationId} and no host of any kind: the tenant is the session's ({@code I5}), and
 * nothing about where the data plane connects comes from the browser.
 *
 * <p><b>Plan 014: the host binding.</b> {@code targetId} (legacy) and {@code connectionId} (a remote-server Connection)
 * are both schema-optional; that EXACTLY ONE is present is a semantic rule the orchestrator answers as a {@code 422}
 * naming the field, because JSON Schema cannot say "exactly one of" beside {@code additionalProperties: false} without
 * restructuring the body into a {@code oneOf}. {@code deploymentMode} is a member of the closed vocabulary or the body is
 * a {@code 400}; absent means {@code dp-remote}. A reserved mode ({@code host-local}, {@code federated}) is well-formed
 * and refused by the orchestrator as a {@code 422}.
 */
public record DeclareInstanceRequest(UUID solutionId, String name, @WireOptional UUID targetId,
        @WireOptional UUID connectionId, @WireOptional String deploymentMode, JsonNode declaredPackageVersion,
        Placement placement, JsonNode acquisition, @WireOptional String role) {

    public DeclareInstanceRequest(UUID solutionId, String name, UUID targetId, UUID connectionId,
            String deploymentMode, JsonNode declaredPackageVersion, Placement placement, JsonNode acquisition) {
        this(solutionId, name, targetId, connectionId, deploymentMode, declaredPackageVersion, placement, acquisition,
                null);
    }

    /** console.yaml's {@code DeploymentMode} enum. */
    static final java.util.Set<String> DEPLOYMENT_MODES = java.util.Set.of("dp-remote", "host-local", "federated");

    public DeclareInstanceRequest {
        if (solutionId == null || placement == null) {
            throw new IllegalArgumentException("solutionId and placement are required");
        }
        if (deploymentMode != null && !DEPLOYMENT_MODES.contains(deploymentMode)) {
            throw new IllegalArgumentException("deploymentMode is not a member of the vocabulary");
        }
        LoginRequest.requireLength(name, 1, 128, "name");
        if (declaredPackageVersion == null || !isPackageVersionId(declaredPackageVersion)) {
            throw new IllegalArgumentException("declaredPackageVersion must be an integer >= 1 or a non-empty string");
        }
        if (acquisition == null || acquisition.isNull()) {
            throw new IllegalArgumentException("acquisition is required");
        }
        role = role == null || role.isBlank() ? null : role.trim();
        if (role != null) {
            LoginRequest.requireLength(role, 1, 128, "role");
        }
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }

    /** {@code PackageVersionId}: an integer {@code >= 1} or a non-empty string. */
    static boolean isPackageVersionId(JsonNode node) {
        if (node.isIntegralNumber()) {
            return node.canConvertToLong() && node.longValue() >= 1;
        }
        return node.isTextual() && !node.textValue().isEmpty();
    }

    /**
     * The host-side placement a declare names; each field is checked against the target's envelope by the data plane.
     *
     * <p>{@code metricsPorts} (plan 011 P11-1.2, M-Q14 {@code OR-74}) maps a package source port, as the key, to the
     * host port that source is published on, as the value; each is 1 to 65535, and an absent key defaults to the source
     * port itself. It sits beside {@code healthPort}, which it mirrors, and names a port on the target's registered
     * address (direct mode) or on the host loopback (SSH mode): the body carries no address ({@code OR-82}). Accepted and
     * not yet acted on: P11-4.1 acts on it. {@code null} means absent, and an empty map means the same: every port is its
     * own.
     */
    public record Placement(String containerName, String configDirectory, String containerConfigPath,
            String fileName, int healthPort, @WireOptional Map<String, Integer> metricsPorts) {

        /**
         * A TCP port in canonical decimal, 1 to 65535: no sign, no leading zero, no exponent. The same regular
         * expression as {@code console.yaml}'s {@code propertyNames.pattern}, so the two cannot disagree on a key.
         */
        static final Pattern SOURCE_PORT = Pattern.compile(
                "^([1-9][0-9]{0,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])$");

        public Placement {
            LoginRequest.requireLength(containerName, 1, Integer.MAX_VALUE, "containerName");
            LoginRequest.requireLength(configDirectory, 1, Integer.MAX_VALUE, "configDirectory");
            LoginRequest.requireLength(containerConfigPath, 1, Integer.MAX_VALUE, "containerConfigPath");
            LoginRequest.requireLength(fileName, 1, Integer.MAX_VALUE, "fileName");
            if (healthPort < 1 || healthPort > 65_535) {
                throw new IllegalArgumentException("healthPort must be between 1 and 65535");
            }
            if (metricsPorts != null) {
                for (Map.Entry<String, Integer> port : metricsPorts.entrySet()) {
                    if (port.getKey() == null || !SOURCE_PORT.matcher(port.getKey()).matches()) {
                        throw new IllegalArgumentException("a metricsPorts key must be a source port, 1 to 65535");
                    }
                    if (port.getValue() == null || port.getValue() < 1 || port.getValue() > 65_535) {
                        throw new IllegalArgumentException("a metricsPorts value must be a host port, 1 to 65535");
                    }
                }
                metricsPorts = Map.copyOf(metricsPorts);
            }
        }

        @JsonAnySetter
        void refuseUnknown(String name, Object value) {
            throw new IllegalArgumentException("unknown property");
        }
    }
}
