# Package-driven Designer role filtering

The Designer loads the package named by the real Designer API response and runs the shared
frontend projection engine over that package. The persisted instance role is passed into the
projection; it is not interpreted by individual React controls.

For a selected role, a field is rendered when its package role is the selected role, absent, or
`none`. Fields for another role are omitted. When no instance role is present, no role filtering
is applied. This keeps packages that never declared roles backward compatible.

Role metadata is optional on both components and fields. Future packages may use any role names;
the implementation does not contain File Mesh-specific role values.
