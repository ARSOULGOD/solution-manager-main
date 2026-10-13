/**
 * The one place a feature gates a control on a permission (task 032,
 * `FE-D9`). Two modes, never confused:
 *
 * - **omit** — the permission is not granted. The control does not exist
 *   in the DOM at all ("omit, not puzzle-disable" — an operator who can't
 *   see a control can't wonder why it's greyed out).
 * - **disable-with-reason** — the permission IS granted, but a capability
 *   or a cap blocks the action right now (pass `disabledReason`). This is
 *   NOT an authorization concern — it renders the control, disabled, with
 *   the reason surfaced as a keyboard-reachable tooltip (`Tooltip`, task 014).
 *
 * ⚠️ Hiding a control here is not a security boundary — the server denies
 * by default regardless (design §15, `permissions.ts`'s header).
 */
import { cloneElement, type ReactElement } from 'react';

import { Tooltip } from '@shared/ui/Tooltip';

import { usePermissions } from './usePermissions';

export interface CanProps {
  /** A permission id, e.g. `"instance.bindTarget"`. */
  id: string;
  /** Exactly one element — cloned with `disabled: true` in disable-with-reason mode. */
  children: ReactElement<{ disabled?: boolean }>;
  /**
   * Set ONLY for a capability/cap block on an authorized action — never to
   * represent "not authorized". When set and the permission is granted,
   * the control renders disabled with this reason instead of omitted.
   */
  disabledReason?: string;
}

export function Can({ id, children, disabledReason }: CanProps) {
  const permissions = usePermissions();

  if (permissions.gate(id) === 'omit') {
    return null;
  }

  if (disabledReason !== undefined) {
    return (
      <Tooltip title={disabledReason}>
        {cloneElement(children, { disabled: true })}
      </Tooltip>
    );
  }

  return children;
}
