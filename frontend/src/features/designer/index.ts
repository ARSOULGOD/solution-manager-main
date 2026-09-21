/**
 * The Designer feature's public surface (task 123) — what another feature
 * (`@features/instance`) or an app-layer route may import. Everything else
 * under `src/features/designer/**` is internal.
 */
export { DesignerPage, deriveComponentSelection, type DesignerPageProps } from './DesignerPage';

export {
  createConfigRepository,
  type ConfigHistoryEntry,
  type ConfigRepository,
  type DesignerLoad,
  type RenameMap,
  type SaveConfigBody,
  type SaveConfigResult,
  type SecretIntent,
  type VersionDiff,
} from './configRepository';

export {
  createApplyRepository,
  confirmActual,
  type ApplyOutcome,
  type ApplyRepository,
} from './applyRepository';

export {
  createActionsRepository,
  type ActionResult,
  type ActionsRepository,
} from './actionsRepository';
