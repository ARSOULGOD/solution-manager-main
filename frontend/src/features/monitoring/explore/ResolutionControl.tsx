/**
 * `<ResolutionControl>` (task 081, Dec `SM-D31` `DM-D11`, `dynamic-metrics`
 * §5.6/§9.1, UI §A.10 — mirrors the reference's `Auto`/`Raw` `Tbg` in
 * `docs/design/frontend/reference/app/dashboards.jsx`; `explore.jsx` never
 * built one). `Auto | Raw` only — **no free bucket size** (`SM-D31`).
 *
 * **Gating.** `'Raw'` renders only when `capabilities.features.forceRaw`
 * is advertised. It is deliberately NOT gated on the `metrics.forceRaw`
 * permission: that verb is `proposed`, not `grantable`
 * (`shared/authz/catalog.ts`), and `permissions.ts`'s C12 rule forbids
 * gating a v1 feature on a non-`grantable` verb — doing so would deny
 * every principal, since no identity can hold a verb the catalog doesn't
 * grant yet (§16 Q4). Same posture `designer/overlayRbac.ts` already takes
 * for its own unratified verbs.
 *
 * TODO(verb): once `metrics.forceRaw` is ratified `grantable`, wrap the
 * `'raw'` option in `Can` here — this component's shape doesn't otherwise
 * change.
 *
 * **Confirmed, not just gated.** Forced-Raw over a long range is
 * `dynamic-metrics` §8's own named high-consequence case (`highConsequence:
 * true` on `metrics.forceRaw` in the catalog even while `proposed`) — a
 * forced-Raw query over six months can strain the deployment. Switching
 * `auto → raw` opens a `HighConsequenceDialog`; the caller's `onChange`
 * fires only on confirm, so the toggle itself never flips until the
 * operator has seen the warning. Switching back to `auto` is not
 * high-consequence and commits immediately.
 */
import { useState } from 'react';

import { t } from '@shared/i18n';
import { HighConsequenceDialog, ToggleGroup } from '@shared/ui';
import type { ToggleOption } from '@shared/ui';

export type Resolution = 'auto' | 'raw';

export interface ResolutionControlProps {
  value: Resolution;
  onChange: (value: Resolution) => void;
  /** `capabilities.features.forceRaw` — never hardcoded `true`. */
  forceRawAdvertised: boolean;
  disabled?: boolean;
}

export function ResolutionControl({
  value,
  onChange,
  forceRawAdvertised,
  disabled = false,
}: ResolutionControlProps) {
  const [confirming, setConfirming] = useState(false);

  const options: ToggleOption<Resolution>[] = [
    { value: 'auto', label: t('resolutionControlAutoLabel') },
    ...(forceRawAdvertised ? [{ value: 'raw' as const, label: t('resolutionControlRawLabel') }] : []),
  ];

  function handleToggle(next: Resolution) {
    if (next === 'raw') {
      setConfirming(true);
    } else {
      onChange('auto');
    }
  }

  return (
    <>
      <ToggleGroup
        value={value}
        onChange={handleToggle}
        options={options}
        aria-label={t('resolutionControlAriaLabel')}
        disabled={disabled}
      />
      <HighConsequenceDialog
        open={confirming}
        title={t('resolutionControlRawConfirmTitle')}
        consequence="high"
        onConfirm={() => {
          setConfirming(false);
          onChange('raw');
        }}
        onCancel={() => setConfirming(false)}
        confirmLabel={t('resolutionControlRawConfirmAction')}
      >
        {t('resolutionControlRawConfirmBody')}
      </HighConsequenceDialog>
    </>
  );
}
