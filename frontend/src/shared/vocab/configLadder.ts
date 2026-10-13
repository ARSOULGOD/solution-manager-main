/**
 * The five-rung config-state ladder (task 060/065, Dec `SM-D22` `UI-D9`,
 * Design §10.1, UI §A.8/§A.9) — desired → rendered → persisted →
 * restart-observed → effective-verified. Ordered data only, ported from
 * `docs/design/frontend/reference/app/designer_data.jsx`'s `CONFIG_STATES`
 * — the render side (`ConfigStepper`, `shared/ui`) is a separate module,
 * same split as every other vocabulary here (data vs. `StateChip`).
 *
 * `ConfigLadderReachedKey` excludes `'effective-verified'` by
 * construction: a solution with no protected config endpoint leaves the
 * fifth rung permanently unreached (`APPLY_INDETERMINATE`), so no caller
 * — task 060's Designer rollout card or task 065's governance ladder —
 * can even express marking it reached. `ConfigStepper` takes this type as
 * its `reached` prop rather than trusting a per-step boolean array.
 */
export const CONFIG_LADDER_STEP_KEYS = [
  'desired',
  'rendered',
  'persisted',
  'restart-observed',
  'effective-verified',
] as const;

export type ConfigLadderStepKey = (typeof CONFIG_LADDER_STEP_KEYS)[number];

export type ConfigLadderReachedKey = Exclude<ConfigLadderStepKey, 'effective-verified'>;

export interface ConfigLadderStepEntry {
  key: ConfigLadderStepKey;
  label: string;
  tip: string;
}

export const configLadderSteps: readonly ConfigLadderStepEntry[] = [
  {
    key: 'desired',
    label: 'Desired',
    tip: "The operator's intended config — the draft.",
  },
  {
    key: 'rendered',
    label: 'Rendered',
    tip: 'Solution Manager produced the config file.',
  },
  {
    key: 'persisted',
    label: 'Persisted',
    tip: 'The file landed on the target.',
  },
  {
    key: 'restart-observed',
    label: 'Restart-observed',
    tip: 'The process restarted and came back with the file — not proof it is using it.',
  },
  {
    key: 'effective-verified',
    label: 'Effective-verified',
    tip: 'The running process is confirmed using the config. Requires a protected endpoint the flagship MI does not expose, so this rung stays unreached whenever that endpoint is absent.',
  },
];
