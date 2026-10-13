package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Request body of {@code issueAgentEnrolment} (console.yaml {@code IssueAgentEnrolmentRequest}; {@code additionalProperties:
 * false}). A STRUCTURAL fault (unparseable, a required field missing or the wrong type, a stray key) is {@code 400}; an envelope VALUE
 * outside its grammar is {@code 422} ({@link #envelopeFault()}); nothing is written in either case. The grammars are {@code registerTarget}'s and {@code V021}'s, so an agent target's envelope cannot be wider than a
 * registered one's.
 *
 * <p>⛔ {@code I5}: there is no organization and no environment here; both are the session's and the path's. The capabilities an
 * agent is issued are fixed by the server, not a field.
 */
public record IssueAgentEnrolmentRequest(String targetName, String allowedConfigRoot, String allowedContainerPrefix,
        UUID solutionId, JsonNode packageVersion) {

    private static final Pattern CONFIG_ROOT = Pattern.compile("^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$");
    private static final Pattern CONTAINER_PREFIX = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

    public IssueAgentEnrolmentRequest {
        if (targetName == null || targetName.isBlank() || targetName.length() > 100) {
            throw new IllegalArgumentException("targetName is required and 1 to 100 characters");
        }
        if (allowedConfigRoot == null || allowedConfigRoot.isEmpty() || allowedConfigRoot.length() > 4096) {
            throw new IllegalArgumentException("allowedConfigRoot is required");
        }
        if (allowedContainerPrefix == null || allowedContainerPrefix.isEmpty() || allowedContainerPrefix.length() > 256) {
            throw new IllegalArgumentException("allowedContainerPrefix is required");
        }
        if (solutionId == null) {
            throw new IllegalArgumentException("solutionId is required");
        }
        if (packageVersion == null || !DeclareInstanceRequest.isPackageVersionId(packageVersion)) {
            throw new IllegalArgumentException("packageVersion must be an integer >= 1 or a non-empty string");
        }
    }

    /**
     * The envelope field whose VALUE is outside its grammar, or null. A value that fails its rule is {@code 422} (as
     * {@code registerTarget}'s is), unlike a structural fault, which is {@code 400}: the integrator's ruling of the W4 lane B review.
     */
    public String envelopeFault() {
        if (!CONFIG_ROOT.matcher(allowedConfigRoot).matches() || allowedConfigRoot.contains("..")) {
            return "allowedConfigRoot";
        }
        return CONTAINER_PREFIX.matcher(allowedContainerPrefix).matches() ? null : "allowedContainerPrefix";
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
