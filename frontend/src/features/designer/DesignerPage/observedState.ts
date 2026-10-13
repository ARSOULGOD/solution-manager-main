/**
 * `isEditingBlocked` (task 123, `UI-D12`) — mirrors `project()`'s own
 * internal `editingEnabledFor` exactly, so `SaveBar`-hiding and the
 * `disabled` flag threaded to every field-editing component can never
 * disagree with what `project()` itself already decided when it computed
 * `ViewField.enabled`. Computed once, here, rather than re-derived at each
 * call site.
 */
import type { ObservedState } from '@contracts/view-model/types';

export function isEditingBlocked(observed: ObservedState): boolean {
  return observed.versionState === 'drift' || observed.versionState === 'unknown';
}
