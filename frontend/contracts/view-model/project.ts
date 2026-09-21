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
  ComponentDeclaration,
  ComponentSelection,
  ComponentValues,
  ControlKind,
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
function projectValue(field: FieldDeclaration, rawDraft: ComponentValues): ViewFieldValue {
  if (field.secret === true) {
    // See `ViewField.value`'s doc comment (types.ts): distinguishing a real
    // `secretRef` from an accidentally-raw draft string needs a
    // `DraftValues`-level contract this task doesn't have — always
    // `secretUnset` is the conservative, obligation-7-safe choice.
    return { kind: 'secretUnset' };
  }
  if (field.key in rawDraft) {
    return { kind: 'value', value: rawDraft[field.key] ?? null, source: 'draft' };
  }
  if (field.default !== undefined) {
    return { kind: 'value', value: field.default, source: 'default' };
  }
  return { kind: 'unset' };
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

function projectField(
  field: FieldDeclaration,
  values: Record<string, FieldValue>,
  rawDraft: ComponentValues,
  configPath: string,
  enabled: boolean,
): ViewField {
  const visible = isVisible(field, values);
  const isSecret = field.secret === true;

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
    value: projectValue(field, rawDraft),
    // `optionsSource` (a live `sm-catalog` lookup, §3.5) is not resolved
    // here — `project()` is pure and does no HTTP. A field declaring it
    // gets an empty static `options`; resolving and merging the live list
    // is a later Designer-task concern.
    options: field.options ?? [],
    enabled: enabled && field.ui?.readOnly !== true,
    // Obligation 5: inactive branches keep their draft value (above) but
    // are marked not-visible — a later task (055) renders only visible
    // fields into the config file.
    visible,
    masked: isSecret,
    // Obligation 6: `advanced` is a disclosure flag, independent of
    // `visible` — it never filters a field out.
    advanced: field.advanced === true,
    validations: evaluateValidations(field, values[field.key] ?? null),
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
): void {
  // ⚠️ ORDER MATTERS, and this is the one detail of FE-118 that does.
  // Version filtering happens HERE — before `applyDefaults`, not after —
  // so a field that does not belong to this version cannot contribute its
  // `default` to the values map that obligation 1's `when:` evaluator
  // reads. Filtering later would leave a v7-only field silently steering
  // the visibility of a v6 field that names it.
  const fields = component.fields.filter((field) => inVersion(field.version, selectedVersion));
  const values = applyDefaults(fields, draft);

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
    bucket.fields.push(projectField(field, values, draft, component.configPath, editingEnabled));
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
): ViewModel {
  const editingEnabled = editingEnabledFor(observedState);
  const sections = new Map<string, ViewSectionBuilder>();

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

  return {
    sections: orderedSections,
    graphs: projectGraphs(pkg, componentSelection, packageVersion),
    status,
  };
}
