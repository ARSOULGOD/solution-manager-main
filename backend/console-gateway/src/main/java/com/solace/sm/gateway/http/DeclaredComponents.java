package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.dp.api.inventory.InstanceView;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** The log endpoints' shared rule: the path's {@code component} must be one the instance's declared package declares. */
final class DeclaredComponents {

    private final SolutionCatalog catalog;
    private final DesignerCodec codec;

    DeclaredComponents(SolutionCatalog catalog, DesignerCodec codec) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** {@code 404} unless the instance's declared package declares a component of exactly this type; an unreadable package is the same 404. */
    void require(UUID organization, InstanceView view, String component) {
        boolean declared = catalog.documentOf(organization, view.catalogSolutionId(), view.declaredPackageVersion())
                .map(this::componentTypes)
                .map(types -> types.contains(component))
                .orElse(false);
        if (!declared) {
            throw new ResourceNotFoundException(ResourceKind.INSTANCE);
        }
    }

    private List<String> componentTypes(String document) {
        try {
            return codec.readPackage(document).components().stream().map(PackageModel.PackageComponent::type).toList();
        } catch (IllegalArgumentException unreadable) {
            return List.of();
        }
    }
}
