package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.regex.Pattern;

/**
 * Request body of the five hierarchy writes (console.yaml {@code NodeNameRequest}: {@code required: [name]},
 * {@code additionalProperties: false}): the display name of a sub-organization, an environment or an organization.
 *
 * <p>The SHAPE is checked here so a bad name is {@code 400} and reaches no port: 1 to 100 characters (code points, as the
 * schema counts them) once surrounding whitespace is stripped, not blank, no control character. That is the shape
 * {@code V015}'s {@code CHECK} and {@code HierarchyAdministrationService} enforce; the gateway cannot import the service,
 * so the rule is written twice and the service stays the authority. UNIQUENESS is not checked here or anywhere in Java: it
 * is the database's ({@code OR-123}), and a clash is {@code 409} from the service.
 *
 * <p>The name is passed on exactly as received: the service strips it and returns the stored name, which is what the
 * response carries. There is no organization in the body ({@code I5}); Spring Boot disables Jackson's
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} globally, so the any-setter that refuses is what makes {@code additionalProperties:
 * false} real.
 *
 * <p>The component is a {@link JsonNode} on purpose ({@code SubmitApplyRequest} is the precedent): a {@code String}
 * component would let Jackson coerce the number {@code 7} to the name {@code "7"}, and the contract says a name is a string.
 */
public record NodeNameRequest(JsonNode name) {

    /** V015's {@code organization_name_shape}: Unicode Cc, which includes NUL (PostgreSQL cannot store it either). */
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u001f\\u007f-\\u009f]");

    /** The longest stored name, in characters. */
    public static final int MAX_NAME_LENGTH = 100;

    public NodeNameRequest {
        if (name == null || !name.isTextual()) {
            throw new IllegalArgumentException("name is required and a string");
        }
        String stripped = name.textValue().strip();
        if (stripped.isEmpty() || stripped.codePointCount(0, stripped.length()) > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name is 1 to " + MAX_NAME_LENGTH + " characters");
        }
        if (CONTROL.matcher(stripped).find()) {
            throw new IllegalArgumentException("name has no control characters");
        }
    }

    /** A request for {@code name} (for a caller that is not a JSON body). */
    public static NodeNameRequest of(String name) {
        return new NodeNameRequest(name == null ? null : TextNode.valueOf(name));
    }

    /** The name as received, to be passed to the service, which strips it. */
    public String value() {
        return name.textValue();
    }

    @JsonAnySetter
    void refuseUnknown(String unknown, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
