package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Mirrors console.yaml's {@code FleetRefused}: {@code Problem} plus {@code refusedMembers[]}. A {@code 403} whose
 * {@code code} is {@code FORBIDDEN}, as on the reach pre-check - there is no distinct fleet code (Q-F5 (a)).
 *
 * <p>⛔ As {@link ProblemBody}: {@code title} is fixed per code and nothing a caller supplied reaches it.
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record FleetRefusedBody(String type, String title, int status, ErrorCode code, UUID correlationId,
        List<FleetRefusedException.RefusedMember> refusedMembers) {

    public FleetRefusedBody {
        Objects.requireNonNull(correlationId, "correlationId");
        refusedMembers = List.copyOf(refusedMembers);
    }

    public static FleetRefusedBody of(UUID correlationId, List<FleetRefusedException.RefusedMember> refused) {
        ProblemBody problem = ProblemBody.of(ErrorCode.FORBIDDEN, correlationId);
        return new FleetRefusedBody(problem.type(), problem.title(), problem.status(), problem.code(), correlationId,
                refused);
    }
}
