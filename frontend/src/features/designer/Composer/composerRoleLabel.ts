import { t } from '@shared/i18n';

/** `ComponentDeclaration.role`/`ComponentSelectionEntry.role` is an authored `string | undefined`, not a closed vocabulary (`UI-D1`) — only `source`/`sink` carry a specific label anywhere in the evidence base; anything else (or nothing) reads as the generic term. */
export function composerRoleLabel(role: string | undefined): string {
  if (role === 'source') {
    return t('composerNodeRoleSource');
  }
  if (role === 'sink') {
    return t('composerNodeRoleSink');
  }
  return t('composerNodeRoleGeneric');
}
