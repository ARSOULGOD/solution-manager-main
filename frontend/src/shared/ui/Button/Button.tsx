/**
 * One button API covering the reference kit's five weights (design §8.2,
 * §15, UI §A.1) — including the two danger weights high-consequence
 * confirmations need (`FE-D9`). A danger button always carries a
 * non-colour marker (an icon) alongside its label text, so it never reads
 * on colour alone (design §18's "status is text + icon + colour" rule,
 * applied here to the danger affordance itself).
 */
import { Button as MuiButton, IconButton as MuiIconButton, SvgIcon } from '@mui/material';
import type { SvgIconProps } from '@mui/material';

import type {
  ButtonProps,
  ButtonSize,
  ButtonVariant,
  IconButtonProps,
  IconButtonSize,
} from './Button.types';

const HEIGHT_BY_SIZE: Record<ButtonSize, number> = { sm: 32, md: 40, lg: 46 };
const ICON_BUTTON_SIZE: Record<IconButtonSize, number> = { sm: 32, md: 40 };

const MUI_SIZE: Record<ButtonSize, 'small' | 'medium' | 'large'> = {
  sm: 'small',
  md: 'medium',
  lg: 'large',
};

const VARIANT_TO_MUI: Record<
  ButtonVariant,
  { muiVariant: 'contained' | 'outlined' | 'text'; color: 'primary' | 'error' }
> = {
  contained: { muiVariant: 'contained', color: 'primary' },
  outlined: { muiVariant: 'outlined', color: 'primary' },
  text: { muiVariant: 'text', color: 'primary' },
  danger: { muiVariant: 'outlined', color: 'error' },
  dangerContained: { muiVariant: 'contained', color: 'error' },
};

// The Material "error" glyph — a filled circle with an exclamation mark.
// Inlined rather than pulled from @mui/icons-material, which is not a
// dependency of this app (only @mui/material is).
function DangerMarkerIcon(props: SvgIconProps) {
  return (
    <SvgIcon {...props} aria-hidden focusable="false">
      <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z" />
    </SvgIcon>
  );
}

export function Button({
  children,
  variant = 'contained',
  size = 'md',
  icon,
  iconRight,
  block = false,
  disabled = false,
  type = 'button',
  autoFocus,
  onClick,
  'aria-haspopup': ariaHasPopup,
  'aria-expanded': ariaExpanded,
  'aria-controls': ariaControls,
}: ButtonProps) {
  const isDanger = variant === 'danger' || variant === 'dangerContained';
  const { muiVariant, color } = VARIANT_TO_MUI[variant];
  const startIcon = icon ?? (isDanger ? <DangerMarkerIcon fontSize="small" /> : undefined);

  return (
    <MuiButton
      variant={muiVariant}
      color={color}
      size={MUI_SIZE[size]}
      disabled={disabled}
      type={type}
      fullWidth={block}
      {...(startIcon !== undefined ? { startIcon } : {})}
      {...(iconRight !== undefined ? { endIcon: iconRight } : {})}
      {...(autoFocus !== undefined ? { autoFocus } : {})}
      {...(onClick !== undefined ? { onClick } : {})}
      {...(ariaHasPopup !== undefined ? { 'aria-haspopup': ariaHasPopup } : {})}
      {...(ariaExpanded !== undefined ? { 'aria-expanded': ariaExpanded } : {})}
      {...(ariaControls !== undefined ? { 'aria-controls': ariaControls } : {})}
      sx={{
        height: HEIGHT_BY_SIZE[size],
        ...(disabled ? { opacity: 0.5, boxShadow: 'none' } : {}),
      }}
    >
      {children}
    </MuiButton>
  );
}

export function IconButton({
  children,
  size = 'md',
  disabled = false,
  type = 'button',
  autoFocus,
  onClick,
  'aria-label': ariaLabel,
  'aria-haspopup': ariaHasPopup,
  'aria-expanded': ariaExpanded,
  'aria-controls': ariaControls,
}: IconButtonProps) {
  return (
    <MuiIconButton
      aria-label={ariaLabel}
      size={size === 'sm' ? 'small' : 'medium'}
      disabled={disabled}
      type={type}
      {...(autoFocus !== undefined ? { autoFocus } : {})}
      {...(onClick !== undefined ? { onClick } : {})}
      {...(ariaHasPopup !== undefined ? { 'aria-haspopup': ariaHasPopup } : {})}
      {...(ariaExpanded !== undefined ? { 'aria-expanded': ariaExpanded } : {})}
      {...(ariaControls !== undefined ? { 'aria-controls': ariaControls } : {})}
      sx={{
        width: ICON_BUTTON_SIZE[size],
        height: ICON_BUTTON_SIZE[size],
        ...(disabled ? { opacity: 0.5, boxShadow: 'none' } : {}),
      }}
    >
      {children}
    </MuiIconButton>
  );
}
