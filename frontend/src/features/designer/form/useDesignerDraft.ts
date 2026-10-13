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
  Catalog,
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

import type { RenderedEntry } from '../configRepository';

import { applyPackageDefaults, mergeReset } from './mergeReset';

export interface UseDesignerDraftArgs {
  pkg: SolutionPackage;
  componentSelection: ComponentSelection;
  observedState: ObservedState;
  /** The configuration-of-record as loaded — RHF's `defaultValues` and the dirty-tracking baseline. Stable across the draft's lifetime; a new baseline (e.g. after save) means a new hook instance. */
  initialDraft: DraftValues;
  /** The instance's package version (`FE-118`) — reaches every `project()` call this hook makes, and the reset-by-merge defaults, so the render, the seeded defaults and the save payload can never disagree about which fields exist (`FE-D37`). */
  packageVersion: PackageVersionId;
  instanceRole?: string | undefined;
  /**
   * SPECIFIED by `UI-D27`/`UI-D28`. Resolved `sm-catalog` entries, threaded into every
   * `project()` call this hook makes. Optional, defaulting to `{}` (no catalog work at all —
   * the exact pre-`UI-D28` behaviour), so an existing caller that composes no `valueFrom`
   * field keeps compiling unchanged.
   */
  catalog?: Catalog;
}

export interface DesignerDraft {
  /** Re-run on every draft change, so a changed discriminator's descendants re-evaluate for free (`project()`'s own obligation 4). */
  viewModel: ViewModel;
  /** True if any instance has a visible-field change against `initialDraft` (see module header). */
  isDirty: boolean;
  /** Instances with at least one changed *visible* value, for a save bar's "N unsaved changes". */
  dirtyInstanceCount: number;
  getInstanceValues: (instanceId: string) => Readonly<Record<string, FieldValue>>;
  /** Apply a batch of YAML-derived field values through the same RHF draft as the form. */
  applyValues: (values: DraftValues) => void;
  /** `SectionCard.onFieldChange`'s own signature, instance-scoped. */
  onFieldChange: (instanceId: string, fieldKey: string, value: FieldValue) => void;
  /**
   * `UI-D28` — detach one field from its connection, seeding it with the currently resolved
   * value (read through the view model, never from a catalog entry's attribute directly, so
   * this can never diverge from what the operator currently sees). A no-op for a field with no
   * resolved value.
   */
  onOverride: (instanceId: string, fieldKey: string) => void;
  /**
   * Re-bind an overridden field to its connection: removes the literal from the raw draft
   * (never sets it to `undefined` in place — `FieldValue` itself excludes `undefined`, and an
   * actually-absent key is what `project()`'s own pass 1 already treats as "resolve the
   * binding") so the next `project()` run falls back to the CURRENT connection value, not
   * whatever was overridden.
   */
  onRevert: (instanceId: string, fieldKey: string) => void;
  /** The `FE-D17` save payload: `instanceId -> (fieldKey -> value)`, visible fields only. Never `getValues()`. A field bound to a connection (`ViewFieldValue.source === 'connection'`) contributes a `{ $from: 'connectionId#attribute' }` reference instead of the resolved value — the value itself is never persisted, only how to re-derive it (`UI-D28`); an override contributes its literal, same as any other field. */
  payload: DraftValues;
  /** `stageConfig`'s `rendered[]` (plan 008 Task 2) — see `renderedEntriesFor`'s own header. */
  rendered: readonly RenderedEntry[];
}

function deepEqual(a: unknown, b: unknown): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

function isDiscriminatorField(pkg: SolutionPackage, componentType: string, fieldKey: string): boolean {
  const component = pkg.components.find((candidate) => candidate.type === componentType);
  return component?.fields.some((field) => field.key === fieldKey && field.discriminator === true) ?? false;
}

/** `UI-D28` — the declared `valueFrom` for one field, or `undefined` for an ordinary field. */
function valueFromFor(pkg: SolutionPackage, componentType: string, fieldKey: string) {
  const component = pkg.components.find((candidate) => candidate.type === componentType);
  return component?.fields.find((field) => field.key === fieldKey)?.valueFrom;
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
  catalog: Catalog,
  instanceRole: string | undefined,
): ComponentValues {
  const instanceDraft = draft[selection.instanceId] ?? {};
  const scoped = project(
    pkg,
    [selection],
    { [selection.instanceId]: instanceDraft },
    observedState,
    packageVersion,
    catalog,
    instanceRole,
  );
  const values: Record<string, FieldValue> = {};
  for (const section of scoped.sections) {
    for (const field of section.fields) {
      if (!field.visible || field.value.kind !== 'value') {
        continue;
      }
      // `UI-D28`: a field still bound to its connection pays out a reference, not the
      // resolved value — the value is re-derivable from the connection at render/apply
      // time, and persisting it would let it silently drift once the connection changes.
      // An override (a literal already written into the raw draft) pays out normally.
      if (field.value.source === 'connection') {
        const bound = valueFromFor(pkg, selection.type, field.key);
        const connectionId = bound !== undefined ? instanceDraft[bound.ref] : undefined;
        if (bound !== undefined && typeof connectionId === 'string') {
          values[field.key] = { $from: `${connectionId}#${bound.attribute}` };
          continue;
        }
      }
      values[field.key] = field.value.value;
    }
  }
  return values;
}

/**
 * `stageConfig`'s `rendered[]` (plan 008 Task 2) — the literal values actually destined for the
 * config file, WITH each one's `renderedPath` (never read off `payload`/`viewModel` directly: the
 * flat `sections[]` this engine's own `ViewModel` output carries has no per-instance grouping, per
 * `DesignerForm.tsx`'s own documented limitation — correct for Cache/single-component and for any
 * package whose field keys are unique across the whole package, same assumption `fieldOwner`
 * there already makes). A per-instance SCOPED `project()` pass is what recovers the grouping,
 * exactly the technique `visibleInstanceValues` above already uses for the save payload.
 *
 * Unlike `payload` (which keeps a `$from` reference for a connection-bound field, so the stored
 * draft can re-resolve it later), `rendered` carries the field's CURRENT resolved literal — that
 * is what actually lands in the config file, and `stageConfig`'s server-side recomputation checks
 * against exactly that.
 */
function renderedEntriesFor(
  pkg: SolutionPackage,
  componentSelection: ComponentSelection,
  draft: DraftValues,
  observedState: ObservedState,
  packageVersion: PackageVersionId,
  catalog: Catalog,
  instanceRole: string | undefined,
): readonly RenderedEntry[] {
  const out: RenderedEntry[] = [];
  for (const selection of componentSelection) {
    const scoped = project(
      pkg,
      [selection],
      { [selection.instanceId]: draft[selection.instanceId] ?? {} },
      observedState,
      packageVersion,
      catalog,
      instanceRole,
    );
    for (const section of scoped.sections) {
      for (const field of section.fields) {
        if (!field.visible || field.renderedPath === null || field.value.kind !== 'value') {
          continue;
        }
        out.push({
          instanceKey: selection.instanceId,
          renderedPath: field.renderedPath,
          value: field.value.value,
        });
      }
    }
  }
  return out;
}

function visibleValues(
  pkg: SolutionPackage,
  componentSelection: ComponentSelection,
  draft: DraftValues,
  observedState: ObservedState,
  packageVersion: PackageVersionId,
  catalog: Catalog,
  instanceRole: string | undefined,
): DraftValues {
  const result: Record<string, ComponentValues> = {};
  for (const selection of componentSelection) {
    result[selection.instanceId] = visibleInstanceValues(pkg, selection, draft, observedState, packageVersion, catalog, instanceRole);
  }
  return result;
}

/**
 * The field's CURRENT resolved value, read the same way the render does — scoped to one
 * instance so a same-keyed field in another instance can never leak in (same reasoning as
 * `visibleInstanceValues`). `undefined` when the field has no resolved value at all
 * (`kind !== 'value'`) — `onOverride` is a no-op in that case, there being nothing to copy.
 */
function currentResolvedValue(
  pkg: SolutionPackage,
  selection: ComponentSelectionEntry,
  draft: DraftValues,
  observedState: ObservedState,
  packageVersion: PackageVersionId,
  catalog: Catalog,
  instanceRole: string | undefined,
  fieldKey: string,
): FieldValue | undefined {
  const scoped = project(
    pkg,
    [selection],
    { [selection.instanceId]: draft[selection.instanceId] ?? {} },
    observedState,
    packageVersion,
    catalog,
    instanceRole,
  );
  for (const section of scoped.sections) {
    for (const field of section.fields) {
      if (field.key === fieldKey) {
        return field.value.kind === 'value' ? field.value.value : undefined;
      }
    }
  }
  return undefined;
}

export function useDesignerDraft({
  pkg,
  componentSelection,
  observedState,
  initialDraft,
  packageVersion,
  instanceRole,
  catalog = {},
}: UseDesignerDraftArgs): DesignerDraft {
  const form = useForm<FieldValues>({ defaultValues: initialDraft, shouldUnregister: false });
  const draftValues = (useWatch({ control: form.control }) ?? initialDraft) as DraftValues;

  const viewModel = useMemo(
    () => project(pkg, componentSelection, draftValues, observedState, packageVersion, catalog, instanceRole),
    [pkg, componentSelection, draftValues, observedState, packageVersion, catalog, instanceRole],
  );

  const baselinePayload = useMemo(
    () => visibleValues(pkg, componentSelection, initialDraft, observedState, packageVersion, catalog, instanceRole),
    [pkg, componentSelection, initialDraft, observedState, packageVersion, catalog, instanceRole],
  );
  const payload = useMemo(
    () => visibleValues(pkg, componentSelection, draftValues, observedState, packageVersion, catalog, instanceRole),
    [pkg, componentSelection, draftValues, observedState, packageVersion, catalog, instanceRole],
  );

  const rendered = useMemo(
    () =>
      renderedEntriesFor(pkg, componentSelection, draftValues, observedState, packageVersion, catalog, instanceRole),
    [pkg, componentSelection, draftValues, observedState, packageVersion, catalog, instanceRole],
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

  const applyValues = useCallback(
    (values: DraftValues) => {
      for (const [instanceId, nextValues] of Object.entries(values)) {
        const currentInstance = (form.getValues(instanceId) as ComponentValues | undefined) ?? {};
        form.setValue(instanceId, { ...currentInstance, ...nextValues }, { shouldDirty: true });
      }
    },
    [form],
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

  const onOverride = useCallback(
    (instanceId: string, fieldKey: string) => {
      const selection = componentSelection.find((candidate) => candidate.instanceId === instanceId);
      if (selection === undefined) {
        return;
      }
      // Read the CURRENT resolution through the view model, never the connection's attribute
      // directly (`I6` also rules out ever reading a secret attribute's value into the
      // browser this way — a secret field never reaches this path at all, see `FieldRow`'s
      // `Replace`-not-`Override` rule). Writing it back through `onFieldChange` is what flips
      // `source` to `'override'` on the next `project()` run — an ordinary field change whose
      // value happens to be copied from the resolution, not a new draft-shape concept.
      const resolved = currentResolvedValue(pkg, selection, draftValues, observedState, packageVersion, catalog, instanceRole, fieldKey);
      if (resolved === undefined) {
        return;
      }
      onFieldChange(instanceId, fieldKey, resolved);
    },
    [pkg, componentSelection, draftValues, observedState, packageVersion, catalog, instanceRole, onFieldChange],
  );

  const onRevert = useCallback(
    (instanceId: string, fieldKey: string) => {
      // Deletes the key rather than setting it to `undefined` in place: `FieldValue` itself
      // excludes `undefined`, and a truly ABSENT key is what pass 1 (`resolveValueFromBindings`)
      // already treats as "no override, fall back to the connection" — see that function's own
      // `hasDraft` check.
      const currentInstance = (form.getValues(instanceId) as ComponentValues | undefined) ?? {};
      const rest = { ...currentInstance };
      delete rest[fieldKey];
      form.setValue(instanceId, rest, { shouldDirty: true });
    },
    [form],
  );

  return {
    viewModel,
    isDirty,
    dirtyInstanceCount,
    getInstanceValues,
    applyValues,
    onFieldChange,
    onOverride,
    onRevert,
    payload,
    rendered,
  };
}
