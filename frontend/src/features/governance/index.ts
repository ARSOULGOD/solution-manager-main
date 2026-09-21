/**
 * The Governance feature's public surface (task 064) — what `@features/instance`
 * or an app-layer route may import. Everything else under
 * `src/features/governance/**` is internal.
 */
export { GovernancePage, type GovernancePageProps } from './GovernancePage';

export {
  createGovernanceRepository,
  type ConfigLadder,
  type ConfigLadderStep,
  type GovernanceRepository,
  type HealthRollupState,
  type HealthSnapshot,
  type LogLevel,
  type LogLevelState,
  type LogLine,
} from './governanceRepository';
