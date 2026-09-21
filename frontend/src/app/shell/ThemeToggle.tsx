/**
 * Light/dark toggle (task 036, design §8.1's `palette.mode` switch,
 * persisted by task 003's `useUiStore`).
 */
import { t } from '@shared/i18n';
import { useUiStore } from '@shared/state/uiStore';
import { ToggleGroup } from '@shared/ui';

import { MoonIcon, SunIcon } from './icons';

export function ThemeToggle() {
  const mode = useUiStore((state) => state.mode);
  const setMode = useUiStore((state) => state.setMode);

  return (
    <ToggleGroup
      value={mode}
      onChange={setMode}
      aria-label={t('themeLabel')}
      options={[
        { value: 'light', label: <SunIcon fontSize="small" />, 'aria-label': t('lightTheme') },
        { value: 'dark', label: <MoonIcon fontSize="small" />, 'aria-label': t('darkTheme') },
      ]}
    />
  );
}
