import type { FieldValue, ViewField } from '@contracts/view-model/types';

export interface FieldRowProps {
  field: ViewField;
  /**
   * The currently-edited value — distinct from `field.value`, which is
   * the value as of the last `project()` call (`FE-D17`: `project()` only
   * re-runs on structural changes, e.g. a discriminator, never on every
   * keystroke). Comparing the two is how `Modified` is detected; comparing
   * `field.value`'s own `source` is how `Default` is detected. Neither can
   * be derived from the other alone.
   */
  value: FieldValue;
  onChange: (value: FieldValue) => void;
  disabled?: boolean;
  /** UI §A.8.4: shown only for single-component solutions. */
  showRenderedPath?: boolean;
  /**
   * SPECIFIED by `UI-D28`. The label of the connection this field is bound to (or would
   * override back to) — the caller's job to resolve (from the selector field's own chosen
   * catalog entry), since `ViewField` itself carries no connection label (`I6` keeps a
   * secret's bound source out of `ViewFieldValue` entirely — see `onOverride`'s doc comment).
   * Absent means "not bound to a connection", the exact pre-`UI-D28` behaviour: no chip, no
   * `Override`/`Revert`/`Replace` affordance, whatever `field.value.source` says.
   */
  connectionLabel?: string;
  /**
   * `option id -> display label` for a `select`. The engine's `ViewField.options` are the raw
   * values the draft stores and enum validation compares against — for a connection selector,
   * catalog IDS — and `ViewField` carries no label for them, so the caller (which holds the
   * catalog) supplies this. It changes only what is DISPLAYED: the chosen option still reports
   * its id through `onChange`, and an id the lookup does not know displays as itself.
   */
  optionLabels?: Readonly<Record<string, string>>;
  /**
   * Detach this field from its connection, seeding it with the current resolution
   * (`useDesignerDraft.onOverride`, instance/key already bound by the caller). Never offered
   * for a secret field (`I6` — there is no resolved value to copy); a bound secret gets
   * `Replace` instead, wired to the same callback, since replacing a secret's binding needs no
   * value copied — the operator types a new one directly.
   */
  onOverride?: () => void;
  /** Re-bind an already-overridden field to its connection (`useDesignerDraft.onRevert`). */
  onRevert?: () => void;
}
