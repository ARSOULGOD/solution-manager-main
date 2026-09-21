/**
 * The `FE-D17` rule, implemented once: React Hook Form holds the *whole*
 * Designer draft — every component instance, every field, including
 * inactive discriminator branches (`UI-D4` retention) — and the save
 * payload is never read from it directly.
 *
 * Two things follow from that:
 *
 * 1. `shouldUnregister: false` plus seeding `defaultValues` with the
 *    entire loaded draft (not just the currently-visible fields) is what
 *    makes retention hold without extra bookkeeping: RHF's internal
 *    values object carries every instance/field key from mount, whether
 *    or not a `FieldRow` for it is currently on screen.
 * 2. A discriminator change still needs an explicit `reset` **by merge**
 *    (`mergeReset.ts`) — not for retention (1 already gives that for
 *    free) but so a branch's fields get their declared `default`s
 *    written in as real draft values the moment it first becomes active,
 *    merged over rather than replacing every other instance's own
 *    values. A plain `reset(next)` would drop everything `next` doesn't
 *    mention and is the exact defect `FE-D17` names.
 *
 * Dirty tracking and the save payload both read `visibleValues()` below —
 * **never** the raw draft and never RHF's own `formState.isDirty`. Two
 * independent reasons converge on the same fix:
 *   - `formState.isDirty` would be cleared by the reset-by-merge call
 *     above on every discriminator flip, undercounting real changes.
 *   - Comparing the *raw* draft (rather than only its visible fields)
 *     against the baseline would also misfire the other way: switching a
 *     discriminator away and back writes a newly-active branch's declared
 *     defaults into the raw draft (point 2), so the raw draft no longer
 *     matches the original baseline byte-for-byte even though nothing the
 *     operator can currently see has changed. Scoping the comparison (and
 *     the payload) to *visible* fields only — the same `UI-D4` filter
 *     `project()` itself applies — makes both correct at once.
 *
 * `getValues` is called exactly once, below — `eslint.config.js`'s
 * `no-restricted-syntax` rule forbids it everywhere else in `src/`
 * (task FE-052 acceptance).
 *
 * `useForm` is instantiated against RHF's own loose `FieldValues`, not
 * `DraftValues` directly: `FieldValue`'s recursive array/object union
 * makes RHF's own path-inference types (`FieldPath<T>`, used by
 * `setValue`/`getValues`) recurse deeply enough to hit TypeScript's
 * instantiation-depth limit (`TS2589`) once dotted paths like
 * `` `${instanceId}.${fieldKey}` `` enter the mix. Every value that
 * crosses the RHF boundary is cast to/from `DraftValues` at that boundary
 * (this module only) — the external contract stays exactly `DraftValues`.
 */
import { useCallback, useMemo } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import type { FieldValues } from 'react-hook-form';

import { project } from '@contracts/view-model/project';
import type {
  ComponentSelection,
  ComponentSelectionEntry,
  ComponentValues,
  DraftValues,
  FieldValue,
  ObservedState,
  PackageVersionId,
  SolutionPackage,
  ViewModel,
} from '@contracts/view-model/types';

import { applyPackageDefaults, mergeReset } from './mergeReset';

export interface UseDesignerDraftArgs {
  pkg: SolutionPackage;
  componentSelection: ComponentSelection;
  observedState: ObservedState;
  /** The configuration-of-record as loaded — RHF's `defaultValues` and the dirty-tracking baseline. Stable across the draft's lifetime; a new baseline (e.g. after save) means a new hook instance. */
  initialDraft: DraftValues;
  /** The instance's package version (`FE-118`) — reaches every `project()` call this hook makes, and the reset-by-merge defaults, so the render, the seeded defaults and the save payload can never disagree about which fields exist (`FE-D37`). */
  packageVersion: PackageVersionId;
}

export interface DesignerDraft {
  /** Re-run on every draft change, so a changed discriminator's descendants re-evaluate for free (`project()`'s own obligation 4). */
  viewModel: ViewModel;
  /** True if any instance has a visible-field change against `initialDraft` (see module header). */
  isDirty: boolean;
  /** Instances with at least one changed *visible* value, for a save bar's "N unsaved changes". */
  dirtyInstanceCount: number;
  getInstanceValues: (instanceId: string) => Readonly<Record<string, FieldValue>>;
  /** `SectionCard.onFieldChange`'s own signature, instance-scoped. */
  onFieldChange: (instanceId: string, fieldKey: string, value: FieldValue) => void;
  /** The `FE-D17` save payload: `instanceId -> (fieldKey -> value)`, visible fields only. Never `getValues()`. */
  payload: DraftValues;
}

function deepEqual(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

function isDiscriminatorField(pkg: SolutionPackage, componentType: string, fieldKey: string): boolean {
  const component = pkg.components.find((candidate) => candidate.type === componentType);
  return component?.fields.some((field) => field.key === fieldKey && field.discriminator === true) ?? false;
}

/**
 * One instance's fields, `project()`-resolved and filtered to `visible`
 * (`UI-D4` obligation 5 — "only visible fields render into config") —
 * reusing `project()` itself, scoped to a single-entry `componentSelection`,
 * rather than re-deriving visibility here, so this can never drift from
 * the engine's own obligations 1–4. A secret field never appears: its
 * `ViewField.value` is always `secretUnset` (`I6`, obligation 7), which
 * `kind !== 'value'` already excludes.
 */
function visibleInstanceValues(
  pkg: SolutionPackage,
  selection: ComponentSelectionEntry,
  draft: DraftValues,
  observedState: ObservedState,
  packageVersion: PackageVersionId,
): ComponentValues {
  const scoped = project(
    pkg,
    [selection],
    { [selection.instanceId]: draft[selection.instanceId] ?? {} },
    observedState,
    packageVersion,
  );
  const values: Record<string, FieldValue> = {};
  for (const section of scoped.sections) {
    for (const field of section.fields) {
      if (field.visible && field.value.kind === 'value') {
        values[field.key] = field.value.value;
      }
    }
  }
  return values;
}

function visibleValues(
  pkg: SolutionPackage,
  componentSelection: ComponentSelection,
  draft: DraftValues,
  observedState: ObservedState,
  packageVersion: PackageVersionId,
): DraftValues {
  const result: Record<string, ComponentValues> = {};
  for (const selection of componentSelection) {
    result[selection.instanceId] = visibleInstanceValues(pkg, selection, draft, observedState, packageVersion);
  }
  return result;
}

export function useDesignerDraft({
  pkg,
  componentSelection,
  observedState,
  initialDraft,
  packageVersion,
}: UseDesignerDraftArgs): DesignerDraft {
  const form = useForm<FieldValues>({ defaultValues: initialDraft, shouldUnregister: false });
  const draftValues = (useWatch({ control: form.control }) ?? initialDraft) as DraftValues;

  const viewModel = useMemo(
    () => project(pkg, componentSelection, draftValues, observedState, packageVersion),
    [pkg, componentSelection, draftValues, observedState, packageVersion],
  );

  const baselinePayload = useMemo(
    () => visibleValues(pkg, componentSelection, initialDraft, observedState, packageVersion),
    [pkg, componentSelection, initialDraft, observedState, packageVersion],
  );
  const payload = useMemo(
    () => visibleValues(pkg, componentSelection, draftValues, observedState, packageVersion),
    [pkg, componentSelection, draftValues, observedState, packageVersion],
  );

  const isDirty = useMemo(() => !deepEqual(baselinePayload, payload), [baselinePayload, payload]);

  const dirtyInstanceCount = useMemo(
    () =>
      componentSelection.filter(
        (selection) => !deepEqual(baselinePayload[selection.instanceId], payload[selection.instanceId]),
      ).length,
    [componentSelection, baselinePayload, payload],
  );

  const getInstanceValues = useCallback(
    (instanceId: string) => draftValues[instanceId] ?? {},
    [draftValues],
  );

  const onFieldChange = useCallback(
    (instanceId: string, fieldKey: string, value: FieldValue) => {
      // Never `setValue('${instanceId}.${fieldKey}', value)`: react-hook-form
      // parses a dot in a NAME string as a nested-path separator, but a
      // package's own field `key` may legitimately contain one (Cache's
      // real package.json: `solace.host`, `cache-hit-response.*`, …) —
      // `ComponentValues` is a FLAT map, dots included literally. Setting
      // the whole instance object under the dot-free `instanceId` name
      // keeps react-hook-form's path parser out of the field key entirely;
      // it only ever sees the opaque object as a value, never as a path to
      // parse. (Found live, driving a real `solace.host` field through the
      // real Cache package for the first time — task 125.)
      const currentInstance = (form.getValues(instanceId) as ComponentValues | undefined) ?? {};
      form.setValue(instanceId, { ...currentInstance, [fieldKey]: value }, { shouldDirty: true });

      const selection = componentSelection.find((candidate) => candidate.instanceId === instanceId);
      if (selection === undefined || !isDiscriminatorField(pkg, selection.type, fieldKey)) {
        return;
      }

      // `FE-D17` reset-by-merge (see module header point 2).
      const current = form.getValues() as DraftValues;
      const derived = applyPackageDefaults(pkg, componentSelection, current, packageVersion);
      form.reset(mergeReset(current, derived), { keepDirty: false, keepTouched: true });
    },
    [form, pkg, componentSelection, packageVersion],
  );

  return {
    viewModel,
    isDirty,
    dirtyInstanceCount,
    getInstanceValues,
    onFieldChange,
    payload,
  };
}
