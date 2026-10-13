package com.solace.sm.cp.api.hierarchy;

import java.util.Objects;
import java.util.UUID;

/**
 * One node of the scope tree the caller can see — an organization, sub-organization or
 * environment (console-integration.md §5.2 rows 8-10). {@code id} is the immutable surrogate key;
 * {@code name} is the mutable display name ({@code OR-120}, {@code D11-AMENDMENT-PROPOSAL.md} §2),
 * read from {@code sm_control}'s {@code name} column — for a row nothing ever named it is the id's
 * own string form.
 */
public record ScopeNode(UUID id, String name) {

    public ScopeNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}
