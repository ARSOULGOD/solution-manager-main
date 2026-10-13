package com.solace.sm.gateway.http;

import java.util.List;
import java.util.UUID;

/**
 * A fleet with at least one refused member was refused whole ({@code OR-57}, {@code fleet-apply.md} section 3.2): either
 * by default, or because EVERY member was refused and nothing is left to apply even with
 * {@code proceedWithAuthorized}. Carries the refused members for the {@code 403}'s {@code refusedMembers} extension.
 *
 * <p>Each member's {@code code} is exactly what a single {@code submitApply} at that instance would have answered
 * ({@code FORBIDDEN} or {@code NOT_FOUND}) for an id the caller named, so listing it reveals nothing the member's own
 * call would not. The message is fixed text: no id is ever in it ({@code I6}).
 */
public final class FleetRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** One refused member. {@code code} is {@code FORBIDDEN} or {@code NOT_FOUND}. */
    public record RefusedMember(int ordinal, UUID instanceId, UUID placementId, String code) {
    }

    private final transient List<RefusedMember> refused;

    public FleetRefusedException(List<RefusedMember> refused) {
        super("the fleet has refused members");
        this.refused = List.copyOf(refused);
    }

    public List<RefusedMember> refused() {
        return refused;
    }
}
