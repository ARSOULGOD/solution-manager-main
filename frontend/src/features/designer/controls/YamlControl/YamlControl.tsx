/**
 * `yaml` control (task 047, `FE-D23` C10) — a gated, non-editing
 * placeholder. Renders no `<textarea>`, no `contentEditable`, nothing a
 * DOM query or an axe scan could mistake for an editor: a raw-YAML editor
 * is out of scope for this release, and a component that quietly rendered
 * one anyway would be a worse defect than the missing feature it stands
 * in for. Plain static text inside `Alert` is correct and sufficient.
 */
import { t } from '@shared/i18n';
import { Alert } from '@shared/ui';

import type { YamlControlProps } from './YamlControl.types';

export function YamlControl({ label, helperText }: YamlControlProps) {
  const message =
    helperText !== undefined ? `${t('controlYamlDisabled')} ${helperText}` : t('controlYamlDisabled');

  return (
    <Alert severity="neutral" title={label}>
      {message}
    </Alert>
  );
}
