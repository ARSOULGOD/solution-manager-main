package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.designer.PackageModel;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Package-driven role discovery. The legacy {@code none} marker means shared, not a real role. */
final class PackageRoleDiscovery {
    private PackageRoleDiscovery() {}

    static List<String> roles(PackageModel.SolutionPackage pkg, Object version) {
        Set<String> roles = new LinkedHashSet<>();
        for (PackageModel.PackageComponent component : MetricsDeclarationBuilder.componentsIn(pkg, version)) {
            String role = component.role();
            if (role != null && !role.isBlank() && !"none".equals(role)) roles.add(role.trim());
        }
        return List.copyOf(roles);
    }

    static String resolve(PackageModel.SolutionPackage pkg, Object version, String requested) {
        List<String> roles = roles(pkg, version);
        String chosen = requested == null || requested.isBlank() ? null : requested.trim();
        if (roles.isEmpty()) {
            if (chosen != null) throw new DeclareValidationException("role");
            return null;
        }
        if (roles.size() == 1) {
            if (chosen != null && !roles.get(0).equals(chosen)) throw new DeclareValidationException("role");
            return roles.get(0);
        }
        if (chosen == null || !roles.contains(chosen)) throw new DeclareValidationException("role");
        return chosen;
    }
}
