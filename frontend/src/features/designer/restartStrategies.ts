/**
 * The restart-strategy catalog the Designer's apply flow renders (UI §A.8.14).
 *
 * **Why this is not in `shared/testdoubles/`.** It used to be, and FE-101's
 * `no-fixture-dataset` assertion caught it shipping: `DesignerForm.tsx`
 * imported it as a VALUE, which pulled `shared/testdoubles/data/designer.ts`
 * — invented rows, secret-mask diffs, replica observations and all — into
 * the production bundle. A type-only import would have been erased; a value
 * import is not.
 *
 * The catalog itself is legitimately shipped code: it is a vocabulary the
 * console renders, the same kind of thing `shared/vocab` holds, not data
 * standing in for a backend's answer. It lives under `features/designer/`
 * rather than `shared/vocab/` because nothing outside this feature reads it
 * and `shared` must not grow a feature's vocabulary (`FE-D3`).
 *
 * ⚠️ CONTRACT: TBD — the strategy keys, their consequences and the
 * `needs*` / `outOfRelease` flags are this plan's, not a contract's. The
 * apply protocol they describe is `DECISIONS.md` open item 3 (the
 * `dp-remote` apply protocol, still unspecified), so when it lands this
 * catalog is replaced WHOLESALE by whatever the server serves — the shape
 * below must not become load-bearing (§9.3 rule 3).
 */
export interface RestartStrategy {
  key: string;
  label: string;
  default?: boolean;
  consequence: 'recommended' | 'gated' | 'high' | 'outage' | 'unavailable';
  description: string;
  needsHealthMapping?: boolean;
  needsNonUniform?: boolean;
  confirmEvenWhenAuthorized?: boolean;
  outOfRelease?: boolean;
}

export const RESTART_STRATEGIES: readonly RestartStrategy[] = [
  {
    key: 'manual',
    label: 'Manual',
    default: true,
    consequence: 'recommended',
    description:
      'Stage config to every replica, then wait. Nothing restarts until you say so; the instance keeps serving on the current config.',
  },
  {
    key: 'rolling-health-gated',
    label: 'Rolling · health-gated',
    consequence: 'gated',
    description: 'Restart one replica at a time, advancing only when the last reports healthy.',
    needsHealthMapping: true,
    needsNonUniform: true,
  },
  {
    key: 'rolling-no-validation',
    label: 'Rolling · no validation',
    consequence: 'high',
    description:
      'Restart replicas one at a time without waiting for a health signal. Faster, but a bad config rolls across every replica.',
    needsNonUniform: true,
    confirmEvenWhenAuthorized: true,
  },
  {
    key: 'restart-all',
    label: 'Restart all',
    consequence: 'outage',
    description: 'Restart every replica at once. Full outage for the instance until they return.',
    confirmEvenWhenAuthorized: true,
  },
  {
    key: 'scheduled',
    label: 'Scheduled',
    consequence: 'unavailable',
    description: 'Restart within a maintenance window.',
    outOfRelease: true,
  },
];
