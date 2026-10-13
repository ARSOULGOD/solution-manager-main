/**
 * Builds a REAL console.yaml `Designer` envelope (`{stateVersion, generation, viewModel, layout}`)
 * out of the SAME fixture inputs (`packageForSolution`/`draftValuesFor`/`observedStateFor`) and
 * the SAME local `project()` engine every other fixture path already runs — plan 008 Tasks 1/2.
 *
 * This is the forward direction (local engine output -> wire shape); `configRepository.ts`'s
 * `mapWireDesignerToLocal` is the reverse. Building the fixture this way (rather than
 * hand-authoring wire JSON) is what makes a round trip through `configRepository.loadDesigner()`
 * meaningful to test against `handlers/dp.ts`'s default `/designer` route — a real backend would
 * also be running `project()` under `OR-34`.
 *
 * **Scoped per component instance**, not one global pass: this engine's own `ViewModel.sections`
 * is FLAT (no per-instance grouping — `DesignerForm.tsx`'s own documented limitation), so the only
 * way to recover `ComponentView.sections` per `instanceKey` is to call `project()` once per
 * `ComponentSelectionEntry`, exactly the technique `useDesignerDraft.ts`'s `renderedEntriesFor`
 * uses on the save side.
 */
import { project } from '@contracts/view-model/project';
import type {
  Catalog,
  ComponentSelection,
  ConfigState,
  ControlKind,
  DraftValues,
  HealthKey,
  ObservedState,
  PackageVersionId,
  SolutionPackage,
  Validation,
  ViewFieldValue,
  VersionState,
} from '@contracts/view-model/types';

const WIRE_CONTROL_KIND: Readonly<Record<ControlKind, string>> = {
  text: 'text',
  number: 'number',
  toggle: 'switch',
  select: 'select',
  duration: 'duration',
  list: 'string-list',
  map: 'key-value',
  table: 'table',
  yaml: 'yaml-editor',
  secret: 'secret',
};

const WIRE_HEALTH_ROLLUP: Readonly<Record<HealthKey, string>> = {
  healthy: 'GREEN',
  not_responding: 'RED',
  restart_expected: 'RESTART_EXPECTED',
  inactive_replica: 'INACTIVE_REPLICA',
  not_applicable: 'NOT_APPLICABLE',
};

const WIRE_VERSION_STATE: Readonly<Record<VersionState, string>> = {
  ok: 'OK',
  drift: 'VERSION_DRIFT',
  unknown: 'VERSION_UNKNOWN',
  unverified: 'VERSION_UNVERIFIED',
};

function wireFieldValue(value: ViewFieldValue): unknown {
  switch (value.kind) {
    case 'value':
      // Wire `FieldValue.source` is only `draft | default` (console.yaml) — this engine's own
      // richer `'connection' | 'override'` collapse onto the nearer of the two: an explicit
      // literal (`override`) reads as `draft`; a connection-resolved value (never itself typed
      // in by the operator) reads as `default`.
      return {
        kind: 'value',
        value: value.value,
        source: value.source === 'draft' || value.source === 'override' ? 'draft' : 'default',
      };
    case 'unset':
      return { kind: 'unset' };
    case 'secretRef':
      return { kind: 'secretRef', ref: value.ref };
    case 'secretUnset':
      return { kind: 'secretUnset' };
  }
}

function wireValidation(validation: Validation): unknown {
  return {
    severity: validation.tier === 'bind' ? 'block' : 'warn',
    code: validation.code,
    message: validation.message,
  };
}

/**
 * The degenerate `exactly-one` composition `componentSelection.ts`'s own `deriveComponentSelection`
 * documents — duplicated here rather than imported (`shared/testdoubles` may not depend on a
 * feature, per `eslint.config.js`'s boundaries policy) since it is one filter-and-map, not a rule
 * worth cross-importing for.
 */
function fixtureComponentSelection(
  pkg: SolutionPackage,
  packageVersion: PackageVersionId,
  componentTypes: readonly string[] | undefined,
): ComponentSelection {
  const bound = componentTypes === undefined ? undefined : new Set(componentTypes);
  return pkg.components
    .filter((component) => component.version === undefined || component.version.includes(packageVersion))
    .filter((component) => bound === undefined || bound.has(component.type))
    .map((component) => ({
      type: component.type,
      instanceId: component.type,
      ...(component.role === undefined ? {} : { role: component.role }),
    }));
}

/** Forward of `configRepository.ts`'s `deriveLocalConfigState` — see that function's own header for why only `'na'`/`'diverged'` round-trip exactly and everything else collapses to `'in_sync'`. */
function wireConfigStateOf(configState: ConfigState): Record<string, string> {
  if (configState === 'na') {
    return {};
  }
  if (configState === 'diverged') {
    return { persisted: 'rendered-config', restartObserved: 'stale-restart-observed-config' };
  }
  return { persisted: 'rendered-config', restartObserved: 'rendered-config' };
}

export interface BuiltWireDesigner {
  stateVersion: number;
  generation: number | null;
  viewModel: {
    engineVersion: number;
    solution: string;
    packageVersion: PackageVersionId | null;
    components: readonly unknown[];
    rendered: readonly unknown[];
    excluded: readonly unknown[];
    metrics: readonly unknown[];
    status: Record<string, unknown>;
    diagnostics: readonly unknown[];
  };
  layout: Readonly<Record<string, { x: number; y: number }>>;
}

export function buildWireDesigner(args: {
  pkg: SolutionPackage;
  packageVersion: PackageVersionId;
  draftValues: DraftValues;
  observed: ObservedState;
  componentTypes?: readonly string[] | undefined;
  catalog?: Catalog;
  stateVersion: number;
  generation: number | null;
}): BuiltWireDesigner {
  const { pkg, packageVersion, draftValues, observed, componentTypes, stateVersion, generation } = args;
  const catalog = args.catalog ?? {};
  const componentSelection = fixtureComponentSelection(pkg, packageVersion, componentTypes);

  const full = project(pkg, componentSelection, draftValues, observed, packageVersion, catalog);

  const components = componentSelection.map((selection) => {
    const scoped = project(
      pkg,
      [selection],
      { [selection.instanceId]: draftValues[selection.instanceId] ?? {} },
      observed,
      packageVersion,
      catalog,
    );
    return {
      instanceKey: selection.instanceId,
      type: selection.type,
      role: selection.role ?? 'none',
      sections: scoped.sections.map((section) => {
        const visibleFields = section.fields.filter((field) => field.visible);
        return {
          title: section.title,
          order: section.order,
          fields: section.fields.map((field) => ({
            key: field.key,
            renderedPath: field.renderedPath,
            controlKind: WIRE_CONTROL_KIND[field.controlKind],
            value: wireFieldValue(field.value),
            ...(field.options === undefined ? {} : { options: field.options }),
            visible: field.visible,
            enabled: field.enabled,
            virtual: field.renderedPath === null,
            advanced: field.advanced,
            masked: field.masked,
            discriminator: field.discriminator,
            ...(field.label === undefined ? {} : { label: field.label }),
            ...(field.helperText === undefined ? {} : { helperText: field.helperText }),
            validations: field.validations.map(wireValidation),
            hiddenBy: [],
          })),
          actions: section.actions.map((action) => ({
            key: action.key,
            kind: action.kind,
            enabled: action.enabled,
            visible: true,
            inputs: action.inputs,
          })),
          allVisibleFieldsAdvanced: visibleFields.length > 0 && visibleFields.every((field) => field.advanced),
        };
      }),
    };
  });

  return {
    stateVersion,
    generation,
    viewModel: {
      engineVersion: 1,
      solution: pkg.solution,
      packageVersion,
      components,
      rendered: [],
      excluded: [],
      metrics: [],
      status: {
        version: {
          state: WIRE_VERSION_STATE[observed.versionState],
          ...(observed.declaredVersion === undefined ? {} : { declared: observed.declaredVersion }),
          ...(observed.observedVersion === undefined ? {} : { observed: observed.observedVersion }),
          // Mirrors `DesignerPage/observedState.ts`'s own `isEditingBlocked` rule exactly — this
          // engine's `ViewStatus` output carries no `editingBlocked` field of its own to read.
          editingBlocked: observed.versionState === 'drift' || observed.versionState === 'unknown',
        },
        config: wireConfigStateOf(observed.configState),
        replicas: [],
        healthRollup: WIRE_HEALTH_ROLLUP[observed.health],
        healthAvailability: 'declared',
      },
      diagnostics: [],
    },
    layout: {},
  };
}
