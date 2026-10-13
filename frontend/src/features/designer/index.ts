/**
 * The Designer feature's public surface (task 123) — what another feature
 * (`@features/instance`) or an app-layer route may import. Everything else
 * under `src/features/designer/**` is internal.
 */
export { DesignerPage, deriveComponentSelection, type DesignerPageProps } from './DesignerPage';

export {
  createConfigRepository,
  type AcceptedRuntimeConfig,
  type AcceptRuntimeIntent,
  type ConfigHistoryEntry,
  type ConfigRepository,
  type DesignerLoad,
  type ConnectionCatalogRow,
  type GetConnectionCatalog,
  type GetSolutionPackage,
  type NotAcceptedField,
  type RenderedEntry,
  type RestoreRuntimeAccepted,
  type RestoreRuntimeIntent,
  type SaveConfigBody,
  type SaveConfigResult,
  type SecretIntent,
  type StagedConfig,
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
