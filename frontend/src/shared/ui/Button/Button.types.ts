import type { MouseEventHandler, ReactNode } from 'react';

export type ButtonVariant = 'contained' | 'outlined' | 'text' | 'danger' | 'dangerContained';
export type ButtonSize = 'sm' | 'md' | 'lg';
export type IconButtonSize = 'sm' | 'md';

export interface ButtonProps {
  children: ReactNode;
  /** Default `contained`. `danger`/`dangerContained` are the two high-consequence weights (`FE-D9`). */
  variant?: ButtonVariant;
  /** Default `md` (40px) — the hit-target floor for a primary action (design §18). */
  size?: ButtonSize;
  /** Leading icon. Danger variants fall back to a built-in marker when omitted, so a danger
   *  action never reads on colour alone. */
  icon?: ReactNode;
  iconRight?: ReactNode;
  /** Full width of its container. */
  block?: boolean;
  disabled?: boolean;
  type?: 'button' | 'submit' | 'reset';
  autoFocus?: boolean;
  onClick?: MouseEventHandler<HTMLButtonElement>;
  /** Menu trigger wiring (FE-017). */
  'aria-haspopup'?: 'menu';
  'aria-expanded'?: boolean;
  'aria-controls'?: string;
}

export interface IconButtonProps {
  /** The icon itself — an `IconButton` renders no visible text. */
  children: ReactNode;
  /** Required: an icon-only control with no accessible name is a defect (`FE-D9`). */
  'aria-label': string;
  /** Default `md` (40px); `sm` is 32px. */
  size?: IconButtonSize;
  disabled?: boolean;
  type?: 'button' | 'submit' | 'reset';
  autoFocus?: boolean;
  onClick?: MouseEventHandler<HTMLButtonElement>;
  /** Menu trigger wiring (FE-017) — same as `ButtonProps`, for an icon-only trigger. */
  'aria-haspopup'?: 'menu';
  'aria-expanded'?: boolean;
  'aria-controls'?: string;
}
