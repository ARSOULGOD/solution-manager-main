package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.identity.EffectivePermissionView;
import com.solace.sm.cp.api.identity.IdentityDirectory;
import com.solace.sm.cp.api.identity.IdentityView;
import com.solace.sm.cp.api.identity.PrincipalSource;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.authz.AuthorizationGrant;
import com.solace.sm.cp.authz.AuthorizationGrantRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Describes the principal behind a resolved session, for the console's own identity screen — never
 * for describing another principal ({@code IdentityDirectory}'s own warning).
 *
 * <p>{@code credential_hash} never crosses this class: {@link PrincipalRepository.Principal} carries
 * it, but {@link IdentityView} has no field for it, and this class never reads
 * {@code Principal.credentialHash()}.
 */
public final class IdentityService implements IdentityDirectory {

    private final PrincipalRepository principals;
    private final AuthorizationGrantRepository grants;
    private final RoleBindingRepository roleBindings;

    public IdentityService(PrincipalRepository principals, AuthorizationGrantRepository grants,
            RoleBindingRepository roleBindings) {
        this.principals = Objects.requireNonNull(principals, "principals");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.roleBindings = Objects.requireNonNull(roleBindings, "roleBindings");
    }

    @Override
    public IdentityView describe(Session session) {
        Objects.requireNonNull(session, "session");
        PrincipalRepository.Principal principal = principals.find(session.principal().id())
                .orElseThrow(() -> new IllegalStateException(
                        "session references a principal that no longer exists"));

        Map<Permission, TreeSet<ScopePath>> scopesByPermission = new EnumMap<>(Permission.class);
        for (AuthorizationGrant grant : grants.grantsFor(session.principal())) {
            for (Permission permission : grant.permissions()) {
                scopesByPermission
                        .computeIfAbsent(permission, ignored -> new TreeSet<>(Comparator.comparing(ScopePath::toString)))
                        .add(grant.scope());
            }
        }
        List<EffectivePermissionView> permissions = new ArrayList<>(scopesByPermission.size());
        scopesByPermission.forEach((permission, scopes) ->
                permissions.add(new EffectivePermissionView(permission, List.copyOf(scopes))));

        return new IdentityView(session.principal(), principal.username(), sourceOf(principal.source()),
                session.mustRotate(), permissions, roleBindings.boundRoleNames(session.principal()),
                roleBindings.boundToRoleAtInstallScope(session.principal(), "Administrator"));
    }

    private static PrincipalSource sourceOf(PrincipalRepository.Source source) {
        return switch (source) {
            case LOCAL -> PrincipalSource.LOCAL;
            case IDP -> PrincipalSource.IDP;
        };
    }
}
