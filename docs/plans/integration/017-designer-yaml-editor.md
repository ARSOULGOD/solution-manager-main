# Designer YAML editor

Status: implemented in the frontend; no API or database change is required.

## Behaviour

The Designer now has Form view and YAML view. Both views use the same
`useDesignerDraft` state, so changing a valid YAML document updates the form
and changing a form field is reflected the next time YAML view is opened.
The package projection remains the source of truth: YAML keys are generated
from each visible field's `renderedPath`, including advanced and role-filtered
fields. No connector or role names are hardcoded in the editor.

The YAML document is a mapping whose dotted package paths become nested YAML
objects. For example, a package field with rendered path
`solace.host` appears as:

```yaml
solace:
  host: broker.example
```

Fields omitted from pasted YAML retain their current form value. This is
intentional for partial edits and prevents a YAML round-trip from clearing a
secret that the API never returns. Sensitive fields are never exported; the
editor displays an informational notice and they remain editable in Form
view.

## Validation and Save/Apply

The editor validates YAML syntax, requires a mapping at the document root, and
checks scalar/list/map/table values against the package-defined control type.
Invalid YAML is not written into the shared draft and contributes a blocking
error in the Save bar. Valid YAML is written through the same draft/projection
path as form edits, so package required, pattern, range, role, connection, and
other existing validation rules continue to apply. Save and Apply therefore
retain their existing rules: invalid or unsaved configuration cannot be
applied, and the existing strategy dialog is unchanged.

Copy uses the browser clipboard API. Paste reads from the clipboard only after
the user presses Paste. The editor uses a monospace textarea with a lightweight
syntax-colour overlay and remains keyboard accessible with an explicit label.

## Compatibility and limits

This is an additional frontend editing surface. The backend continues to
receive the existing `draftValues` and `rendered` Save/Apply payloads; no YAML
string is stored as a second source of truth. Secret values are deliberately
not recoverable from YAML or Designer GET responses. Fields without a rendered
path are not emitted because they are virtual/package UI fields rather than
runtime configuration.

Focused tests cover dotted-path serialization, secret omission, YAML-to-draft
mapping, partial edits, syntax errors, root-shape errors, and type errors.
