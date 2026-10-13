/**
 * The Governance feature's public surface (task 064) — what `@features/instance`
 * or an app-layer route may import. Everything else under
 * `src/features/governance/**` is internal.
 */
export { GovernancePage, type GovernancePageProps } from './GovernancePage';

export {
  createGovernanceRepository,
  type ApplyAccepted,
  type ConfigLadder,
  type ConfigLadderPlacement,
  type ConfigLadderRung,
  type ConfigLadderRungKey,
  type GovernanceRepository,
  type HealthPlacement,
  type HealthSnapshot,
  type HealthState,
  type LogLevel,
  type LogLevelState,
  type LogLine,
  type LogPage,
  type ReadLogsParams,
  type SetLogLevelInput,
  type SetLogLevelMode,
  type SetLogLevelResult,
} from './governanceRepository';
