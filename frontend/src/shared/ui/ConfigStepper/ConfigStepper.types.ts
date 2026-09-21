import type { ConfigLadderReachedKey } from '../../vocab';

export interface ConfigStepperProps {
  /**
   * The highest rung reached. `'effective-verified'` is excluded from
   * the type — see `shared/vocab/configLadder.ts` — so no caller can
   * compile a claim that the fifth rung is reached.
   */
  reached: ConfigLadderReachedKey;
}
