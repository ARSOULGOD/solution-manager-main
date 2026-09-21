/**
 * The Targets feature's public surface (task 095). READ-ONLY preview —
 * `TargetsRepository` has no write method and this screen renders no
 * credential entry (`DECISIONS.md` open items 1, 2).
 */
export { TargetsPreview } from './TargetsPreview';
export type { TargetsPreviewProps } from './TargetsPreview.types';
export { createTargetsRepository, type TargetsRepository } from './targetsRepository';
