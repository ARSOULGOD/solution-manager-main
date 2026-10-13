/**
 * Credential entry (task 023, `FE-D23`). A plain labelled password field —
 * `TextField` with `reveal` forced on, nothing else. No separate
 * set/replace/clear step and no secret-reference indirection: until a real
 * secret backend exists to resolve a reference against, the typed value
 * *is* the field's value, so hiding it behind extra states bought nothing.
 *
 * ⚠️ This is a deliberate, temporary simplification against `I6`
 * ("secret values must never be persisted in config tables, version
 * history, field-level diffs, exports, archives, or logs") — today's
 * `DraftValues` still runs every field through the same pipeline, secret
 * or not. Revisit this component (write-only entry, no prefill, reference
 * display instead of value) once a real vault/secret-store integration
 * lands.
 */
import { TextField } from '../TextField';

import type { SecretFieldProps } from './SecretField.types';

export function SecretField(props: SecretFieldProps) {
  return <TextField {...props} reveal />;
}
