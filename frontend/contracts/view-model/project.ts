/**
 * `project()` — `UI-D15`'s pure projection engine (task 046, `REQ-001`).
 * Data in, data out: no DOM, no component library, no HTTP, no clock.
 * Every rule below traces to a numbered obligation in the task spec
 * (`docs/plans/frontend/implementation.md` §FE-046) and a fixture in
 * `../fixtures/view-model/*.json` proves it.
 *
 * The whole file re-derives its output from its arguments on every
 * call — nothing is memoized or held across calls. That is *why*
 * obligation 4 ("changing a discriminator re-evaluates all descendants")
 * needs no special-case code: a descendant's `when:` is evaluated fresh
 * against the current defaults-applied values every time, so a changed
 * discriminator value is automatically reflected in every field whose
 * `when:` names it, deep or shallow.
 */
import type {
  Catalog,
  CatalogEntry,
  ComponentDeclaration,
  ComponentSelection,
  ComponentValues,
  ControlKind,
  Diagnostic,
  DraftValues,
  FieldDeclaration,
  FieldType,
  FieldValue,
  ObservedState,
  PackageVersionId,
  SolutionPackage,
  ViewAction,
  ViewField,
  ViewFieldValue,
  Validation,
  ViewGraph,
  ViewModel,
  ViewSection,
  ViewStatus,
  WhenClause,
} from './types';

const CONTROL_KIND_BY_FIELD_TYPE: Record<FieldType, ControlKind> = {
  string: 'text',
  int: 'number',
  bool: 'toggle',
  enum: 'select',
  duration: 'duration',
  list: 'list',
  map: 'map',
  table: 'table',
  yaml: 'yaml',
  secret: 'secret',
};

function controlKindFor(type: FieldType): ControlKind {
  return CONTROL_KIND_BY_FIELD_TYPE[type];
}

// Obligation 1: `when:` evaluates against DEFAULTS-APPLIED values, never
// raw draft input — `matchIfMissing`'s only expressible form in this
// grammar (`UI-D2`). A field's own default fills the gap when the draft
// omits it; nothing else about the value changes.
function applyDefaults(
  fields: readonly FieldDeclaration[],
  draft: ComponentValues,
): Record<string, FieldValue> {
  const resolved: Record<string, FieldValue> = { ...draft };
  for (const field of fields) {
    if (!(field.key in resolved) && field.default !== undefined) {
      resolved[field.key] = field.default;
    }
  }
  return resolved;
}

// Obligation 2: equality only, implicitly AND-ed. No OR, no comparison, no
// negation — exactly `design.md` §3.4's generic evaluator.
function matchesWhen(when: WhenClause | undefined, values: Record<string, FieldValue>): boolean {
  if (when === undefined) {
    return true;
  }
  return Object.entries(when).every(([key, expected]) => values[key] === expected);
}

/**
 * Whether a declaration belongs to the version being projected (FE-118).
 * TRUE when either side is `undefined`: a declaration with no `version`
 * belongs to every version the package supports, and a projection with no
 * selected version filters nothing — which is what keeps every package
 * and fixture written before this existed projecting exactly as it did.
 *
 * An empty `declared` array therefore belongs to NO version and never
 * renders. That is deliberate, and `parsePackage.ts` rejects it at load
 * time so it can never happen by accident.
 */
export function inVersion(
  declared: readonly PackageVersionId[] | undefined,
  selected: PackageVersionId | undefined,
): boolean {
  if (declared === undefined || selected === undefined) {
    return true;
  }
  return declared.includes(selected);
}

// Obligation 3: fields are flat, each carrying its own whole ancestor
// chain in `when:` — so activity is one map lookup per key, never a tree
// walk. Obligation 4 falls out of this for free (see module header).
function isVisible(field: FieldDeclaration, values: Record<string, FieldValue>): boolean {
  return matchesWhen(field.when, values);
}

// Task 048: which of a field's `default` or its raw `DraftValues` entry
// resolved the current value — `FieldRow`'s "Default" hint keys off
// `source === 'default'`, so this has to be presence-in-raw-draft, not a
// property of the defaults-applied `values` map (which has already erased
// that distinction for the `when:` evaluator's purposes, obligation 1).
//
// `UI-D28`: `boundSources` carries the two sources a plain draft/default
// lookup cannot produce on its own — `'connection'` and `'override'` — as
// computed by `resolveValueFromBindings` below. A field absent from
// `boundSources` falls through to the pre-existing draft/default logic
// unchanged, so a package with no `valueFrom` field behaves exactly as
// before this task.
function projectValue(
  field: FieldDeclaration,
  rawDraft: ComponentValues,
  values: Record<string, FieldValue>,
  boundSources: ReadonlyMap<string, 'connection' | 'override'>,
): ViewFieldValue {
  if (field.secret === true) {
    // See `ViewField.value`'s doc comment (types.ts): distinguishing a real
    // `secretRef` from an accidentally-raw draft string needs a
    // `DraftValues`-level contract this task doesn't have — always
    // `secretUnset` is the conservative, obligation-7-safe choice.
    return { kind: 'secretUnset' };
  }
  const boundSource = boundSources.get(field.key);
  if (boundSource !== undefined) {
    return { kind: 'value', value: values[field.key] ?? null, source: boundSource };
  }
  if (field.key in rawDraft) {
    return { kind: 'value', value: rawDraft[field.key] ?? null, source: 'draft' };
  }
  if (field.default !== undefined) {
    return { kind: 'value', value: field.default, source: 'default' };
  }
  return { kind: 'unset' };
}

/**
 * `UI-D28`: resolves every `valueFrom` binding among `fields`, under precedence
 * override -> selected connection -> declared default -> unset. Returns only what a plain
 * draft/default lookup (`applyDefaults` + `projectValue`) cannot already produce:
 *
 *  - `values`: `applyDefaults`'s own map, with each bound field's connection-resolved
 *    value written in so obligation 1's `when:` evaluator and `evaluateValidations` see it
 *    exactly as they would see a draft value.
 *  - `sources`: which bound fields resolved from the connection or from an explicit
 *    override, for `projectValue` and the read-only rule below to key off.
 *
 * Selectors resolve in a PRE-PASS (`chosen`), so a `valueFrom` field declared before its
 * selector field in the package still resolves — declaration order must not change
 * behaviour. A package with no `valueFrom` field does no catalog work at all.
 */
function resolveValueFromBindings(
  fields: readonly FieldDeclaration[],
  draft: ComponentValues,
  values: Record<string, FieldValue>,
  catalog: Catalog,
  instanceId: string,
  diagnostics: Diagnostic[],
): ReadonlyMap<string, 'connection' | 'override'> {
  const sources = new Map<string, 'connection' | 'override'>();
  const boundFields = fields.filter((field) => field.valueFrom !== undefined);
  if (boundFields.length === 0) {
    return sources;
  }

  const chosen = new Map<string, CatalogEntry>();
  for (const field of fields) {
    if (field.optionsSource?.entity !== 'connection') {
      continue;
    }
    const picked = draft[field.key];
    // A connection selector's value is always a scalar string id — never an object or array.
    // `typeof picked === 'object'` catches both (arrays are `typeof 'object'` in JS; `null` is
    // already excluded above it). Stringifying an object/array here would silently produce
    // `"[object Object]"` (`@typescript-eslint/no-base-to-string`) rather than failing loudly,
    // so a malformed draft is treated as no selection at all, same as `undefined`/`null`/`''`.
    if (picked === undefined || picked === null || picked === '' || typeof picked === 'object') {
      continue;
    }
    const pickedId = String(picked);
    const entry = (catalog.connection ?? []).find((candidate) => candidate.id === pickedId);
    if (entry === undefined) {
      diagnostics.push({
        code: 'CONNECTION_UNRESOLVED',
        message: `${field.key} selects connection ${pickedId}, which the catalog does not hold.`,
        instanceId,
        key: field.key,
      });
      continue;
    }
    chosen.set(field.key, entry);
  }

  for (const field of boundFields) {
    const bound = field.valueFrom;
    if (bound === undefined) {
      continue;
    }
    const raw = draft[field.key];
    const hasDraft = raw !== undefined && raw !== null && raw !== '';
    if (hasDraft) {
      // A literal draft value on a bound field is an explicit override (`UI-D28`); the
      // operator clicked Override and typed. `applyDefaults` already left it in `values`
      // via its `{...draft}` spread — only the source label is new here.
      sources.set(field.key, 'override');
      continue;
    }

    const entry = chosen.get(bound.ref);
    const attrIsSecret = entry?.secretAttributes?.includes(bound.attribute) === true;
    if (attrIsSecret && field.secret !== true) {
      // `I6`: refused on this engine's own evidence, not on trust that the validation gate
      // ran. The field falls through to its declared default/unset exactly as if the
      // connection had left the attribute unset — the secret value is never read into
      // `values`.
      diagnostics.push({
        code: 'SECRET_BINDING_REFUSED',
        message: `${field.key} is not a secret field but binds ${bound.ref}#${bound.attribute}, which the catalog marks secret. Refused (I6).`,
        instanceId,
        key: field.key,
      });
      continue;
    }

    const attrValue = entry?.attributes[bound.attribute];
    // A selected connection PARTICIPATES in precedence; it does not short-circuit it. An
    // attribute the connection leaves unset falls through to the field's own default,
    // which `applyDefaults` already applied into `values` above.
    if (attrValue !== undefined && attrValue !== null && attrValue !== '') {
      values[field.key] = attrValue as FieldValue;
      sources.set(field.key, 'connection');
    }
  }

  return sources;
}

// Task 053, `UI-D16`: two tiers, never merged. `required` is `bind` — a
// missing value can't be written into a config file at all, independent of
// the MI. `min`/`max`/`pattern` are `semantic`: Cache pulls
// `spring-boot-starter-validation` and uses it *zero* times — no `@Min`,
// `@Max` or `@Pattern` anywhere — so none of the three is an MI-derived
// certainty; they are SM's own assertions, and copy must say so ("Solace
// recommends"), never "invalid" (`UI-D16`'s own rationale). Every literal
// threshold here comes from the `FieldDeclaration` argument, never a value
// baked into this function — `REQ-001`'s "no business rule hard-coded".
function isEmptyValue(value: FieldValue): boolean {
  return value === null || value === '' || (Array.isArray(value) && value.length === 0);
}

function rangeMessage(
  direction: 'Above' | 'Below',
  min: number | undefined,
  max: number | undefined,
): string {
  const bounds =
    min !== undefined && max !== undefined
      ? `${min}–${max}`
      : min !== undefined
        ? `≥ ${min}`
        : `≤ ${max}`;
  return `${direction} the range Solace recommends (${bounds}).`;
}

// No caching, no separate "clear" path: called fresh against the current
// value every time `project()` runs, which is every keystroke (the module
// header's "re-derives its output on every call") — that is the entirety
// of "REQUIRED clears as the operator types" (task 053's own testing line).
function evaluateValidations(field: FieldDeclaration, value: FieldValue): Validation[] {
  const validations: Validation[] = [];

  if (field.required === true && isEmptyValue(value)) {
    validations.push({ tier: 'bind', code: 'REQUIRED', message: 'This field is required.' });
  }

  if (typeof value === 'number') {
    if (field.min !== undefined && value < field.min) {
      validations.push({
        tier: 'semantic',
        code: 'BELOW_RECOMMENDED',
        message: rangeMessage('Below', field.min, field.max),
      });
    }
    if (field.max !== undefined && value > field.max) {
      validations.push({
        tier: 'semantic',
        code: 'ABOVE_RECOMMENDED',
        message: rangeMessage('Above', field.min, field.max),
      });
    }
  }

  if (
    field.pattern !== undefined &&
    typeof value === 'string' &&
    value !== '' &&
    !new RegExp(field.pattern).test(value)
  ) {
    validations.push({
      tier: 'semantic',
      code: 'PATTERN_MISMATCH',
      message: "Doesn't match the pattern Solace recommends.",
    });
  }

  return validations;
}

/**
 * `UI-D27`: resolves a field's rendered `options` list. A static `field.options` always wins
 * (a field cannot declare both per `parsePackage.ts`'s own gate, but a caller-authored static
 * list stays authoritative if it ever does). Otherwise, for an `optionsSource` field, the
 * options come from `CatalogEntry.id` — never `.label` — because that is what the draft
 * stores and what enum validation compares against (settled and verified in Task 4): showing
 * labels while the draft holds ids would make every valid selection fail as "not an option".
 * A selector naming `connectionType` (e.g. `solace-broker`) only ever offers catalog entries
 * of that same connection type.
 */
function resolveFieldOptions(
  field: FieldDeclaration,
  catalog: Catalog,
  instanceId: string,
  diagnostics: Diagnostic[],
): readonly string[] {
  if (field.options !== undefined) {
    return field.options;
  }
  if (field.optionsSource === undefined) {
    return [];
  }
  const entries = catalog[field.optionsSource.entity];
  if (entries === undefined) {
    diagnostics.push({
      code: 'CATALOG_UNRESOLVED',
      message: `Options for ${field.key} come from the ${field.optionsSource.entity} catalog, which was not supplied.`,
      instanceId,
      key: field.key,
    });
    return [];
  }
  const wantType = field.optionsSource.connectionType;
  return entries
    .filter((entry) => wantType === undefined || entry.connectionType === wantType)
    .map((entry) => entry.id);
}

function projectField(
  field: FieldDeclaration,
  values: Record<string, FieldValue>,
  rawDraft: ComponentValues,
  configPath: string,
  enabled: boolean,
  boundSources: ReadonlyMap<string, 'connection' | 'override'>,
  options: readonly string[],
): ViewField {
  const visible = isVisible(field, values);
  const isSecret = field.secret === true;
  // `UI-D28`: a field sourced from the selected connection is read-only until the operator
  // overrides it — they edit the connection, not the field, to change it.
  const boundReadOnly = boundSources.get(field.key) === 'connection';

  const view: ViewField = {
    key: field.key,
    // Authored, falling back to `key` — never humanized (task 048 ruling;
    // the reference package data hand-picks labels that don't follow from
    // the key mechanically, e.g. `max-size` -> "Max entries").
    label: field.label ?? field.key,
    // `virtual` (authored) is the only source of "no rendered path" —
    // `project()` doesn't infer it from anything else.
    renderedPath: field.virtual === true ? null : `${configPath}.${field.key}`,
    discriminator: field.discriminator === true,
    // `secret: true` overrides the control kind regardless of `type` — a
    // plain `type: string, secret: true` field (design.md §3.3's own
    // `GIT_CLOUD_TOKEN` example) renders identically to `type: secret`.
    controlKind: isSecret ? 'secret' : controlKindFor(field.type),
    // Obligation 7 (`I6`): the raw value never appears in the view model
    // for a secret field — not masked-as-a-placeholder-string, simply
    // absent (see `projectValue`).
    value: projectValue(field, rawDraft, values, boundSources),
    // `optionsSource` (a live `sm-catalog` lookup, §3.5) IS resolved, against the `catalog`
    // argument threaded in from `project()` — `project()` is still pure (no HTTP; the caller
    // already fetched the catalog). See `resolveFieldOptions` for the by-id/connectionType
    // rule (`UI-D27`) and `CATALOG_UNRESOLVED`.
    options,
    enabled: enabled && field.ui?.readOnly !== true && !boundReadOnly,
    // Obligation 5: inactive branches keep their draft value (above) but
    // are marked not-visible — a later task (055) renders only visible
    // fields into the config file.
    visible,
    masked: isSecret,
    // Obligation 6: `advanced` is a disclosure flag, independent of
    // `visible` — it never filters a field out.
    advanced: field.advanced === true,
    // A field the operator cannot see must never block the form. Matches the
    // canonical engine (`contracts/view-models/src/project.ts`, `validations:
    // visible ? … : []`) — the divergence was open item 36. Latent until a
    // package paired `required` with an inactive `when:` branch, which a
    // connector-type discriminator does by construction (FE-150).
    validations: visible ? evaluateValidations(field, values[field.key] ?? null) : [],
  };
  if (field.helperText !== undefined) {
    view.helperText = field.helperText;
  }
  return view;
}

function projectAction(
  action: {
    key: string;
    kind: ViewAction['kind'];
    inputs?: readonly string[] | undefined;
    when?: WhenClause | undefined;
  },
  values: Record<string, FieldValue>,
): ViewAction {
  return {
    key: action.key,
    kind: action.kind,
    enabled: matchesWhen(action.when, values),
    inputs: action.inputs ?? [],
  };
}

function sectionKeyFor(field: FieldDeclaration): string {
  return field.ui?.section ?? 'General';
}

function projectComponent(
  component: ComponentDeclaration,
  instanceId: string,
  draft: ComponentValues,
  editingEnabled: boolean,
  sections: Map<string, ViewSectionBuilder>,
  selectedVersion: PackageVersionId | undefined,
  instanceRole: string | undefined,
  catalog: Catalog,
  diagnostics: Diagnostic[],
): void {
  // ⚠️ ORDER MATTERS, and this is the one detail of FE-118 that does.
  // Version filtering happens HERE — before `applyDefaults`, not after —
  // so a field that does not belong to this version cannot contribute its
  // `default` to the values map that obligation 1's `when:` evaluator
  // reads. Filtering later would leave a v7-only field silently steering
  // the visibility of a v6 field that names it.
  const role = instanceRole?.trim();
  const fields = component.fields.filter(
    (field) =>
      inVersion(field.version, selectedVersion) &&
      (!role || !field.role || field.role === 'none' || field.role === role),
  );
  const values = applyDefaults(fields, draft);
  // `UI-D28`: resolves `valueFrom` bindings into `values` (for `when:`/validations) and
  // returns which fields resolved from the connection vs. an explicit override, for
  // `projectField`'s value/read-only computation. A no-`valueFrom` package does no work here.
  const boundSources = resolveValueFromBindings(fields, draft, values, catalog, instanceId, diagnostics);

  // First-occurrence order: the package declares no top-level "sections"
  // list (§3.1/§3.2) — a section is an emergent grouping of `ui.section`
  // strings, so its position is the first field's own `ui.order` seen for
  // it. Not pinned by the design doc; documented here rather than assumed
  // silently.
  let sectionOrderCounter = sections.size;
  for (const field of fields) {
    const key = `${instanceId}::${sectionKeyFor(field)}`;
    let bucket = sections.get(key);
    if (bucket === undefined) {
      bucket = {
        title: sectionKeyFor(field),
        order: field.ui?.order ?? sectionOrderCounter,
        fields: [],
        actions: [],
      };
      sections.set(key, bucket);
      sectionOrderCounter += 1;
    }
    const options = resolveFieldOptions(field, catalog, instanceId, diagnostics);
    bucket.fields.push(
      projectField(field, values, draft, component.configPath, editingEnabled, boundSources, options),
    );
  }

  for (const action of component.actions ?? []) {
    // Actions carry no `ui.section` in the schema (§3.5) — bucketed into
    // this instance's first section (or a fresh default one) since the
    // design doc doesn't specify where an action-only component's actions
    // land. Unexercised by any of task 046's 9 named obligations.
    const key = `${instanceId}::General`;
    let bucket = sections.get(key);
    if (bucket === undefined) {
      bucket = { title: 'General', order: sectionOrderCounter, fields: [], actions: [] };
      sections.set(key, bucket);
      sectionOrderCounter += 1;
    }
    bucket.actions.push(projectAction(action, values));
  }
}

interface ViewSectionBuilder {
  title: string;
  order: number;
  fields: ViewField[];
  actions: ViewAction[];
}

function projectGraphs(
  pkg: SolutionPackage,
  componentSelection: ComponentSelection,
  selectedVersion: PackageVersionId | undefined,
): ViewGraph[] {
  const graphs: ViewGraph[] = [];
  for (const selection of componentSelection) {
    const component = pkg.components.find(
      (candidate) =>
        candidate.type === selection.type && inVersion(candidate.version, selectedVersion),
    );
    // A component excluded by version contributes no sections; it must not
    // contribute graphs either, or the Monitoring tab would chart a
    // component the Designer refuses to render.
    for (const metric of component?.metrics ?? []) {
      // `mark` is a monitoring-widget concept, not declared alongside a
      // metric here — `line` is a placeholder default. Populating this for
      // real is `projectSeries()`'s job (`DM-D20`, task 071), not this
      // engine's; task 046's 9 obligations don't touch `graphs[]`.
      graphs.push({ metric: metric.name, mark: 'line' });
    }
  }
  return graphs;
}

// Obligation 8: `VERSION_DRIFT`/`VERSION_UNKNOWN` block editing with a
// stated reason; `VERSION_UNVERIFIED` must not (`UI-D12` over `SM-D14`).
function editingEnabledFor(observedState: ObservedState): boolean {
  return observedState.versionState !== 'drift' && observedState.versionState !== 'unknown';
}

// Obligation 9: `project()` is auth-free — there is nowhere in this
// signature for a session, a permission set or an RBAC check to go. RBAC
// is a second overlay applied elsewhere (`FE-D22`), never inside this
// function.
export function project(
  pkg: SolutionPackage,
  componentSelection: ComponentSelection,
  draftValues: DraftValues,
  observedState: ObservedState,
  /**
   * The package version this instance is configured against (FE-118).
   * OMITTED means no version filtering at all — every component and field
   * projects, whatever it declares. That is the behaviour every caller and
   * fixture had before versions existed, and it is preserved exactly.
   */
  packageVersion?: PackageVersionId,
  /**
   * SPECIFIED by `UI-D28`. Resolved `sm-catalog` entries, keyed by `OptionsSourceEntity`.
   * Placed after `packageVersion`, not before it, so every existing positional call site
   * (which already passes `packageVersion` 5th) keeps compiling unchanged — a package with
   * no `valueFrom` field never needs this argument.
   */
  catalog: Catalog = {},
  /** Persisted package role; unassigned fields remain visible. */
  instanceRole?: string,
): ViewModel {
  const editingEnabled = editingEnabledFor(observedState);
  const sections = new Map<string, ViewSectionBuilder>();
  const diagnostics: Diagnostic[] = [];

  for (const selection of componentSelection) {
    const component = pkg.components.find((candidate) => candidate.type === selection.type);
    if (component === undefined) {
      // Load-time gates (§6.4) already reject a package/selection mismatch
      // before it reaches the renderer — not this function's job to
      // re-validate.
      continue;
    }
    // A component that does not belong to the selected version is skipped
    // whole: no sections, no fields, no actions (FE-118).
    if (!inVersion(component.version, packageVersion)) {
      continue;
    }
    const draft = draftValues[selection.instanceId] ?? {};
    projectComponent(
      component,
      selection.instanceId,
      draft,
      editingEnabled,
      sections,
      packageVersion,
      instanceRole,
      catalog,
      diagnostics,
    );
  }

  const orderedSections: ViewSection[] = [...sections.values()]
    .sort((a, b) => a.order - b.order)
    .map((bucket) => ({
      title: bucket.title,
      order: bucket.order,
      fields: bucket.fields,
      actions: bucket.actions,
    }));

  const status: ViewStatus = {
    health: observedState.health,
    versionState: observedState.versionState,
    configState: observedState.configState,
  };
  if (observedState.declaredVersion !== undefined) {
    status.declaredVersion = observedState.declaredVersion;
  }
  if (observedState.observedVersion !== undefined) {
    status.observedVersion = observedState.observedVersion;
  }

  const viewModel: ViewModel = {
    sections: orderedSections,
    graphs: projectGraphs(pkg, componentSelection, packageVersion),
    status,
  };
  // Omitted rather than an empty array when nothing fired — the shared fixture suite
  // (`../fixtures/view-model/*.json`, task 046) predates `UI-D28` and its committed
  // `expected` ViewModels declare no `diagnostics` key at all; `toEqual` is exact, so a
  // package that resolves no `valueFrom` binding must produce a byte-identical ViewModel
  // to before this task.
  if (diagnostics.length > 0) {
    viewModel.diagnostics = diagnostics;
  }
  return viewModel;
}
