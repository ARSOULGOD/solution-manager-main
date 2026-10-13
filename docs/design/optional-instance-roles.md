# Optional, package-defined instance roles

Instance roles are package metadata, not a File Mesh vocabulary. A package component may declare any non-blank string as its role; components without a role (and the legacy `none` marker) are shared components.

## Declaration

The console loads the selected package version and discovers its unique roles dynamically:

- no roles: the Role control is hidden and no role is sent;
- one role: the role is derived automatically and persisted;
- two or more roles: the Role control is shown with package-provided options and a selection is required.

The API accepts an optional `role` property. The gateway resolves it against the selected package version, rejects an unknown role, and rejects a role on a package that has no roles. Existing declarations without a role remain valid.

## Designer

The persisted role is returned with the instance and Designer envelope. When a role exists, the default component selection includes components for that role plus shared components. When no role exists, the existing package-wide/default projection is preserved. The projection uses the role strings and component metadata from the package; it does not contain File Mesh-specific branches.

## Compatibility

`role: "none"` remains accepted as a legacy shared-component marker, but it is not exposed as a selectable instance role. Packages with no role metadata continue to omit the field and use the pre-role Designer behavior. Migration `sm_data V042` adds a nullable role column.

Role-to-component membership is currently expressed by the component `role` metadata. If a future package needs a separate role-to-field mapping, that relationship must be added to the package contract rather than inferred from component names.
