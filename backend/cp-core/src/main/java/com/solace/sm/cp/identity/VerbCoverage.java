package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.authz.AuthorizationGrant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Whether one principal's grants cover verbs at a scope (plan 013 T3.1, review W3D-4). It is the same "held" rule the
 * authorization service applies ({@code grant.scope().authorizes(path)} and the verb in the grant's role), evaluated
 * once over a grant list instead of once per verb.
 *
 * <p>It exists so that <b>nobody can hand out, or take over, standing they do not themselves hold</b>: a grantor must
 * hold every verb of the role it grants at the granted scope, and the caller of a credential reset must hold every verb
 * the target holds, at the scope the target holds it. Today only the Administrator holds {@code role.assign} and
 * {@code user.*}, and the Administrator holds the whole catalog, so the rule never refuses anyone in v1; it makes the
 * safety a property of the code instead of a property of today's role table (an intermediate role, D-REG {@code OQ-7
 * (c)}, would otherwise turn grant and reset into privilege escalation).
 */
final class VerbCoverage {

    private VerbCoverage() {
    }

    /** The first of {@code verbs} (in iteration order) that no grant of {@code held} gives at {@code scope}. */
    static Optional<Permission> firstMissingAt(List<AuthorizationGrant> held, ScopePath scope,
            Collection<Permission> verbs) {
        return verbs.stream().filter(verb -> !holds(held, verb, scope)).findFirst();
    }

    /**
     * The first verb that {@code target} holds somewhere and {@code caller} does not hold at that same scope: a
     * non-empty result means the caller would act on standing it lacks.
     */
    static Optional<Permission> firstTargetVerbMissing(List<AuthorizationGrant> caller,
            List<AuthorizationGrant> target) {
        return target.stream()
                .flatMap(grant -> grant.permissions().stream()
                        .filter(verb -> !holds(caller, verb, grant.scope())))
                .findFirst();
    }

    private static boolean holds(List<AuthorizationGrant> held, Permission verb, ScopePath scope) {
        return held.stream().anyMatch(grant -> grant.permissions().contains(verb) && grant.scope().authorizes(scope));
    }
}
