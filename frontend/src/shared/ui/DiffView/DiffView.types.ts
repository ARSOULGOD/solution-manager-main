/**
 * Field-level diff (architecture.md §7.2 — "the load-bearing UX").
 * A `secret: true` row can only carry the mask token on both sides (I6).
 */
export const SECRET_MASK: unique symbol = Symbol('SECRET_MASK');
export type SecretMask = typeof SECRET_MASK;

export type DiffKind = 'add' | 'remove' | 'change' | 'rename';

export type DiffRow =
  | {
      path: string;
      kind: DiffKind;
      secret?: false;
      old: string | null;
      new: string;
    }
  | {
      path: string;
      kind: DiffKind;
      secret: true;
      old: SecretMask;
      new: SecretMask;
    };

export interface DiffViewProps {
  rows: readonly DiffRow[];
  labels?: readonly [string, string];
}
