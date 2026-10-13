/**
 * `project()` — the pure Designer view-model projector.
 *
 * Implements docs/design/dynamic-ui/design.md §6.5 (`UI-D15`), corrected against `DM-D*`
 * and `FE-D*`. Data in, data out: no DOM, no component library, no HTTP, no RBAC
 * (`FE-D22` overlays after), no transform, no resolution, no gap evaluation
 * (`DM-D11`/`DM-D16`/`DM-D23` decide those on the server).
 */
import type {
  ActionView, Catalog, CatalogEntry, CollectionState, ComponentSelection, ComponentView, ControlKind,
  Diagnostic, DraftValues, ExcludedEntry, FieldValue, FieldView, HealthRollup, MetricView,
  ObservedState, PackageComponent, PackageField, Predicate, RenderedEntry, SectionView,
  PackageVersionId, SolutionPackage, StatusView, Validation, ViewModel,
} from './types.js';

export const ENGINE_VERSION = 2;

/**
 * Code-point comparison, NOT `localeCompare`.
 *
 * Every ordering in the view model is part of the contract, and the Java implementation
 * sorts with `String.compareTo`, which is ordinal. `localeCompare` is ICU/locale dependent
 * and disagrees with ordinal in both directions — `'solace_out'` vs `'solace.out'` and
 * `'TEST_X'` vs `'namespace'` flip. Using it made 20 of 21 fixtures demand en-locale
 * collation from Java, and the ordering test could not see it because it used the same
 * comparator.
 */
function cmp(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

const CONTROL: Readonly<Record<PackageField['type'], ControlKind>> = {
  string: 'text', int: 'number', bool: 'switch', enum: 'select', duration: 'duration',
  list: 'string-list', map: 'key-value', table: 'table', yaml: 'yaml-editor', secret: 'secret',
};

/** A missing declaration scope means every version supported by the package. */
export function inVersion(
  declared: readonly PackageVersionId[] | undefined,
  selected: PackageVersionId,
): boolean {
  return declared === undefined || declared.includes(selected);
}

/** Spring's simple form: `<number>` (millis) or `<number><unit>`. */
const SIMPLE_DURATION = /^[-+]?\d+(?:ns|us|ms|s|m|h|d)?$/;
/** ISO-8601, with the sections captured so emptiness can be rejected. */
const ISO_DURATION = /^[-+]?P(\d+D)?(T(\d+H)?(\d+M)?(\d+(?:\.\d+)?S)?)?$/;

/**
 * Java's `Duration.parse` requires at least one section, and at least one section after `T`
 * if `T` is present — so bare `P` and `PT` are invalid. A regex with every section optional
 * accepted both, which meant SM green-lit a config the MI's binder rejects: exactly the
 * failure the bind tier exists to prevent.
 */
function isDuration(v: string): boolean {
  if (SIMPLE_DURATION.test(v)) return true;
  const m = ISO_DURATION.exec(v);
  if (!m) return false;
  const [, days, tMarker, hours, minutes, seconds] = m;
  if (tMarker !== undefined && hours === undefined && minutes === undefined && seconds === undefined) return false;
  return days !== undefined || hours !== undefined || minutes !== undefined || seconds !== undefined;
}

/** UI-D18: secrecy has one meaning; the schema tolerates two spellings. */
function isSecret(f: PackageField): boolean {
  return f.secret === true || f.type === 'secret';
}

/**
 * I6: a secret field carries a REFERENCE, never a value. Enforced in code rather than by
 * convention, because a literal typed into the field would otherwise flow verbatim into
 * `rendered` and into `FieldValue.ref` — the union's arm names would still look right.
 */
const SECRET_REF = /^secretRef:\/\//;

/**
 * Normalize a raw draft value to its declared type BEFORE predicates run.
 * Deliberately narrow: `"true"` normalizes for a bool because form controls emit strings,
 * but `"yes"` does not — UI-D2's grammar is strict equality, and silently coercing
 * truthy-looking strings is how a predicate starts lying.
 */
function normalize(f: PackageField, raw: unknown): { ok: true; value: unknown } | { ok: false; reason: string } {
  if (raw === undefined || raw === null || raw === '') return { ok: true, value: undefined };
  switch (f.type) {
    case 'bool': {
      if (typeof raw === 'boolean') return { ok: true, value: raw };
      if (raw === 'true') return { ok: true, value: true };
      if (raw === 'false') return { ok: true, value: false };
      return { ok: false, reason: `expected a boolean, got ${JSON.stringify(raw)}` };
    }
    case 'int': {
      const n = typeof raw === 'number' ? raw
        : (typeof raw === 'string' && /^[-+]?\d+$/.test(raw) ? Number(raw) : Number.NaN);
      if (!Number.isInteger(n)) return { ok: false, reason: `expected an integer, got ${JSON.stringify(raw)}` };
      // Beyond 2^53 the value is no longer exactly representable here, so SM would write a
      // number it did not read. Spring's binder would reject it too.
      if (!Number.isSafeInteger(n)) return { ok: false, reason: `integer ${JSON.stringify(raw)} exceeds the exactly-representable range` };
      return { ok: true, value: n };
    }
    case 'duration': {
      if (typeof raw === 'string' && isDuration(raw)) return { ok: true, value: raw };
      return { ok: false, reason: `expected a Spring Duration, got ${JSON.stringify(raw)}` };
    }
    default:
      return { ok: true, value: raw };
  }
}

/** UI-D2: equality + AND only, over defaults-applied values. */
function evaluate(when: Predicate | undefined, values: ReadonlyMap<string, unknown>): string[] {
  if (!when) return [];
  const failed: string[] = [];
  for (const [k, expected] of Object.entries(when)) {
    if (values.get(k) !== expected) failed.push(k);
  }
  return failed;
}

function renderedPathFor(configPath: string, key: string): string {
  return configPath === '' ? key : `${configPath}.${key}`;
}

function bindAndSemanticValidations(f: PackageField, value: unknown, bindError?: string): Validation[] {
  const out: Validation[] = [];
  if (bindError) {
    // UI-D16 bind tier: the MI's binder would fail and the app would not start.
    out.push({ severity: 'block', code: 'BIND_TYPE', message: bindError });
    return out;
  }
  if (value === undefined) {
    if (f.required) out.push({ severity: 'block', code: 'REQUIRED', message: 'This field is required.' });
    return out;
  }
  if (f.type === 'enum' && f.options && !f.options.includes(value as never)) {
    out.push({ severity: 'block', code: 'NOT_AN_OPTION', message: `${JSON.stringify(value)} is not one of ${JSON.stringify(f.options)}.` });
  }
  // UI-D16 semantic tier: SM's assertion, not the MI's. Warn, never block.
  if (typeof value === 'number') {
    if (f.min !== undefined && value < f.min) {
      out.push({ severity: 'warn', code: 'BELOW_RECOMMENDED', message: `Below the range Solace recommends (${f.min}–${f.max ?? '∞'}).` });
    }
    if (f.max !== undefined && value > f.max) {
      out.push({ severity: 'warn', code: 'ABOVE_RECOMMENDED', message: `Above the range Solace recommends (${f.min ?? 0}–${f.max}).` });
    }
  }
  if (f.pattern !== undefined && typeof value === 'string' && !new RegExp(f.pattern).test(value)) {
    out.push({ severity: 'warn', code: 'PATTERN', message: `Does not match the pattern Solace recommends (${f.pattern}).` });
  }
  if (f.type === 'yaml' && typeof value === 'string' && /(?:password|secret|token|apikey|api_key)\s*[:=]/i.test(value)) {
    // UI-D23 item 3: cannot be masked, so the operator must be told it is stored in clear.
    out.push({ severity: 'warn', code: 'YAML_SECRET_SCAN', message: 'This block looks like it contains a credential. yaml content is stored, diffed and exported in clear.' });
  }
  return out;
}

function healthRollupOf(
  declared: boolean,
  replicas: readonly { readonly health: string }[],
): HealthRollup {
  if (!declared) return 'NOT_APPLICABLE';               // REQ-009 / I1 — never red
  // A declared probe with zero replica observations means the instance exists and nothing
  // answered. UI-D9 says red on no response, and this IS no response — but it is also the
  // state a freshly created instance sits in before the first poll, so callers must supply
  // replicas as soon as they have them. `health-declared-no-replicas-is-red` pins it.
  if (replicas.length === 0) return 'RED';
  if (replicas.some((r) => r.health === 'RED')) return 'RED';
  if (replicas.some((r) => r.health === 'RESTART_EXPECTED')) return 'RESTART_EXPECTED';
  if (replicas.every((r) => r.health === 'INACTIVE_REPLICA')) return 'INACTIVE_REPLICA';
  return 'GREEN';
}

export function project(
  pkg: SolutionPackage | null,
  selection: ComponentSelection,
  draft: DraftValues,
  observed: ObservedState,
  catalog: Catalog = {},
  requestedPackageVersion?: PackageVersionId,
): ViewModel {
  const diagnostics: Diagnostic[] = [];
  const versionBlocks = observed.version.state === 'VERSION_DRIFT' || observed.version.state === 'VERSION_UNKNOWN';

  const status: StatusView = {
    version: {
      state: observed.version.state,
      ...(observed.version.declared !== undefined ? { declared: observed.version.declared } : {}),
      ...(observed.version.observed !== undefined ? { observed: observed.version.observed } : {}),
      editingBlocked: versionBlocks,
      ...(versionBlocks
        ? { reason: observed.version.state === 'VERSION_DRIFT'
            ? `Declared ${observed.version.declared ?? '?'} but observed ${observed.version.observed ?? '?'}.`
            : 'No loaded package covers the declared version.' }
        : {}),
    },
    config: observed.config ?? {},
    replicas: observed.replicas ?? [],
    healthRollup: 'NOT_APPLICABLE',
    healthAvailability: 'not-declared',
  };

  if (pkg === null) {
    return {
      engineVersion: ENGINE_VERSION, solution: '', packageVersion: null,
      components: [], rendered: [], excluded: [], metrics: [],
      status, diagnostics: [{ code: 'VERSION_UNKNOWN', message: 'No package resolved for the declared version; nothing can be rendered.' }],
    };
  }

  const packageVersion = requestedPackageVersion ?? pkg.packageVersion.at(-1);
  if (packageVersion === undefined || !pkg.packageVersion.includes(packageVersion)) {
    return {
      engineVersion: ENGINE_VERSION,
      solution: pkg.solution,
      packageVersion: packageVersion ?? null,
      components: [],
      rendered: [],
      excluded: [],
      metrics: [],
      status,
      diagnostics: [
        {
          code: 'PACKAGE_VERSION_NOT_DECLARED',
          message: `Package version ${String(packageVersion)} is not declared by this package; nothing can be rendered.`,
        },
      ],
    };
  }

  const byType = new Map<string, PackageComponent>(
    pkg.components
      .filter((component) => inVersion(component.version, packageVersion))
      .map((component) => [component.type, component]),
  );
  const components: ComponentView[] = [];
  const rendered: RenderedEntry[] = [];
  const excluded: ExcludedEntry[] = [];
  const metrics: MetricView[] = [];
  let anyHealthDeclared = false;

  for (const inst of selection.instances) {
    const comp = byType.get(inst.type);
    if (!comp) {
      diagnostics.push({ code: 'UNKNOWN_COMPONENT', message: `Selection names component type ${inst.type} which the package does not declare.`, instanceKey: inst.instanceKey });
      continue;
    }
    if (comp.health) anyHealthDeclared = true;

    const fields = (comp.fields ?? []).filter((field) => inVersion(field.version, packageVersion));
    const instDraft = draft[inst.instanceKey] ?? {};

    // ---- pass 1: resolve values under UI-D28 precedence
    //   override -> selected connection -> declared default -> unset
    const values = new Map<string, unknown>();
    const sources = new Map<string, 'draft' | 'default' | 'connection' | 'override'>();
    const bindErrors = new Map<string, string>();

    // Selectors resolve FIRST. A `valueFrom` field may be declared before its
    // selector in the document, and a one-pass loop would then read an
    // unresolved selector. Declaration order must not change behaviour.
    const chosen = new Map<string, CatalogEntry>();
    for (const f of fields) {
      if (f.optionsSource?.entity !== 'connection') continue;
      const picked = instDraft[f.key];
      if (picked === undefined || picked === null || picked === '') continue;
      const entry = (catalog.connection ?? []).find((c) => c.id === String(picked));
      if (entry === undefined) {
        diagnostics.push({
          code: 'CONNECTION_UNRESOLVED',
          message: `${f.key} selects connection ${String(picked)}, which the catalog does not hold.`,
          instanceKey: inst.instanceKey, key: f.key,
        });
        continue;
      }
      chosen.set(f.key, entry);
    }

    for (const f of fields) {
      const raw = instDraft[f.key];
      const bound = f.valueFrom;
      const hasDraft = raw !== undefined && raw !== null && raw !== '';

      // A literal in the draft on a BOUND field is an explicit override
      // (UI-D28). The operator clicked Override and typed; that beats the
      // connection and survives every later connection edit.
      if (hasDraft) {
        const n = normalize(f, raw);
        if (n.ok) { values.set(f.key, n.value); sources.set(f.key, bound ? 'override' : 'draft'); }
        else { bindErrors.set(f.key, n.reason); values.set(f.key, undefined); }
        continue;
      }

      if (bound) {
        const entry = chosen.get(bound.ref);
        const attrIsSecret = entry?.secretAttributes?.includes(bound.attribute) === true;
        // I6: the engine refuses this on its OWN evidence rather than trusting that
        // validation gate 17 ran. A non-secret field bound to an attribute the catalog
        // marks secret never gets to see the resolved value — it falls through exactly
        // as if the connection had left the attribute unset.
        if (attrIsSecret && !isSecret(f)) {
          diagnostics.push({
            code: 'SECRET_BINDING_REFUSED',
            message: `${f.key} is not a secret field but binds ${bound.ref}#${bound.attribute}, which the catalog marks secret. Refused (I6).`,
            instanceKey: inst.instanceKey, key: f.key,
          });
        } else {
          const attrValue = entry?.attributes[bound.attribute];
          // A selected connection PARTICIPATES in precedence; it does not
          // short-circuit it. An attribute the connection leaves unset falls
          // through to the field's own default rather than writing an empty
          // string into the MI's config file.
          if (attrValue !== undefined && attrValue !== null && attrValue !== '') {
            const n = normalize(f, attrValue);
            if (n.ok) { values.set(f.key, n.value); sources.set(f.key, 'connection'); }
            else { bindErrors.set(f.key, n.reason); values.set(f.key, undefined); }
            continue;
          }
        }
      }

      if (f.default !== undefined && f.default !== null) {
        values.set(f.key, f.default);
        sources.set(f.key, 'default');
      } else {
        values.set(f.key, undefined);
      }
    }

    // orphan draft keys are preserved in the draft but never rendered
    const declared = new Set(fields.map((f) => f.key));
    for (const k of Object.keys(instDraft)) {
      if (!declared.has(k)) {
        excluded.push({ instanceKey: inst.instanceKey, key: k, reason: 'orphan' });
        diagnostics.push({ code: 'ORPHAN_DRAFT_KEY', message: `Draft holds ${k}, which the package does not declare.`, instanceKey: inst.instanceKey, key: k });
      }
    }

    // ---- pass 2: visibility, then the field views
    // Section order is the declaration index of the first member. `ui.order` is a
    // WITHIN-section field order that restarts at 1 per section, so using it for the
    // section itself tied every section and fell through to an alphabetical tiebreak —
    // which reordered the flagship package's form and froze that into the fixtures.
    let sectionSeq = 0;
    const sectionsMap = new Map<string, { order: number; fields: { v: FieldView; idx: number }[]; actions: ActionView[] }>();
    const bucketFor = (title: string): { order: number; fields: { v: FieldView; idx: number }[]; actions: ActionView[] } => {
      let b = sectionsMap.get(title);
      if (!b) { b = { order: sectionSeq++, fields: [], actions: [] }; sectionsMap.set(title, b); }
      return b;
    };
    fields.forEach((f, idx) => {
      const hiddenBy = evaluate(f.when, values);
      const visible = hiddenBy.length === 0;
      const virtual = f.virtual === true;
      const secret = isSecret(f);
      const raw = values.get(f.key);
      const src = sources.get(f.key);

      let value: FieldValue;
      let secretMalformed = false;
      if (secret) {
        // I6 / FE-D23: no arm of this union can carry a resolved value.
        if (raw === undefined) {
          value = { kind: 'secretUnset' };
        } else if (SECRET_REF.test(String(raw))) {
          value = { kind: 'secretRef', ref: String(raw) };
        } else {
          // A literal was typed where a reference belongs. Refuse it rather than carry it.
          secretMalformed = true;
          value = { kind: 'secretUnset' };
        }
      } else if (raw === undefined) {
        value = { kind: 'unset' };
      } else {
        value = { kind: 'value', value: raw, source: src ?? 'draft' };
      }

      // Entries carry an id (the selector value, matched against the draft) and a label
      // (display text). The option LIST is the ids, because that is what the draft holds
      // and what enum validation compares against — presenting labels here while the
      // draft stores ids would make every selection fail NOT_AN_OPTION.
      let opts: readonly (string | number | boolean)[] | undefined = f.options;
      if (opts === undefined && f.optionsSource) {
        const entries = catalog[f.optionsSource.entity];
        if (entries === undefined) {
          diagnostics.push({ code: 'CATALOG_UNRESOLVED', message: `Options for ${f.key} come from the ${f.optionsSource.entity} catalog, which was not supplied.`, instanceKey: inst.instanceKey, key: f.key });
        } else {
          const wantType = f.optionsSource.connectionType;
          opts = entries
            .filter((e) => wantType === undefined || e.connectionType === wantType)
            .map((e) => e.id);
        }
      }

      const boundReadOnly = src === 'connection';

      const view: FieldView = {
        key: f.key,
        // UI-D24: a virtual field reaches no config file, so it has no path.
        renderedPath: virtual ? null : renderedPathFor(comp.configPath, f.key),
        controlKind: secret ? 'secret' : CONTROL[f.type],
        value,
        ...(opts !== undefined ? { options: opts } : {}),
        visible,
        // `readOnly` disables the control but the value still reaches config. A bound
        // field sourced from the connection is likewise read-only until overridden
        // (UI-D28) — the operator edits the connection, not the field, to change it.
        enabled: !versionBlocks && f.ui?.readOnly !== true && !boundReadOnly,
        virtual,
        advanced: f.ui?.advanced === true,
        masked: secret,
        discriminator: f.discriminator === true,
        ...(f.ui?.label !== undefined ? { label: f.ui.label } : {}),
        ...(f.ui?.helperText !== undefined ? { helperText: f.ui.helperText } : {}),
        validations: visible
          ? (secretMalformed
              ? [{ severity: 'block' as const, code: 'SECRET_NOT_A_REFERENCE', message: 'A secret field accepts only a secretRef:// reference. A literal value would be persisted, diffed and exported in clear (I6).' }]
              : bindAndSemanticValidations(f, raw, bindErrors.get(f.key)))
          : [],
        hiddenBy,
      };

      bucketFor(f.ui?.section ?? 'General').fields.push({ v: view, idx });

      // ---- the rendered set: visible, non-virtual, non-action, with a value
      if (!visible) {
        excluded.push({ instanceKey: inst.instanceKey, key: f.key, reason: 'hidden' });
      } else if (virtual) {
        excluded.push({ instanceKey: inst.instanceKey, key: f.key, reason: 'virtual' });
      } else if (secretMalformed) {
        // never let a literal secret reach the payload
        excluded.push({ instanceKey: inst.instanceKey, key: f.key, reason: 'hidden' });
      } else if (raw !== undefined && view.renderedPath !== null) {
        rendered.push({ instanceKey: inst.instanceKey, renderedPath: view.renderedPath, value: raw });
      }
    });

    for (const a of comp.actions ?? []) {
      const hiddenBy = evaluate(a.when, values);
      const av: ActionView = {
        key: a.key, kind: a.kind,
        visible: hiddenBy.length === 0,
        enabled: hiddenBy.length === 0 && !versionBlocks,
        inputs: a.inputs ?? [],
        ...(a.ui?.label !== undefined ? { label: a.ui.label } : {}),
      };
      bucketFor(a.ui?.section ?? 'General').actions.push(av);
      // an action holds no value and appears in no config file
      excluded.push({ instanceKey: inst.instanceKey, key: a.key, reason: 'action' });
    }

    // ---- metrics: declaration-level only. No mark, transform or resolution (DM-D11/D16/D23).
    // UI-D7: gating reads the restart-observed config UNIONED with persisted virtual values.
    // These arrive from a properties file, so `'true'` is a string where the draft would hold
    // a boolean. Normalizing through the SAME per-field function is what keeps one grammar
    // from having two value domains — otherwise a gated metric is pending-config-change
    // forever while nothing is actually pending.
    const rawGating = observed.restartObservedValues?.[inst.instanceKey];
    const gating = rawGating === undefined ? undefined : (() => {
      const g = new Map<string, unknown>();
      const byKey = new Map(fields.map((f) => [f.key, f] as const));
      for (const [k, v] of Object.entries(rawGating)) {
        const f = byKey.get(k);
        if (!f) { g.set(k, v); continue; }
        const n = normalize(f, v);
        g.set(k, n.ok ? n.value : undefined);
      }
      return g;
    })();

    for (const m of comp.metrics ?? []) {
      let state: CollectionState = 'collectable';
      if (m.when) {
        if (gating === undefined) {
          // Nothing observed yet. "pending" overstates it, but there is no third state in
          // UI-D7 and asserting `collectable` would be worse.
          state = 'pending-config-change';
          diagnostics.push({ code: 'GATING_STATE_UNKNOWN', message: `No restart-observed values for ${inst.instanceKey}; ${m.name} is reported pending rather than asserted collectable.`, instanceKey: inst.instanceKey });
        } else {
          const runningApplies = evaluate(m.when, gating).length === 0;
          const draftApplies = evaluate(m.when, values).length === 0;
          // Compare COLLECTABILITY, not the number of failed keys. With a multi-key
          // predicate the counts can differ while both verdicts are "not collectable",
          // and can tie while the verdicts differ.
          state = runningApplies !== draftApplies
            ? 'pending-config-change'
            : (runningApplies ? 'collectable' : 'not-applicable');
        }
      }
      metrics.push({ instanceKey: inst.instanceKey, name: m.name, metricType: m.metricType, unit: m.unit, collectionState: state });
    }

    // ---- deterministic ordering: section order then title; field ui.order then declaration index
    const sections: SectionView[] = [...sectionsMap.entries()]
      .map(([title, b]) => {
        const ordered = [...b.fields]
          .sort((x, y) => (fieldOrder(fields, x.idx) - fieldOrder(fields, y.idx)) || (x.idx - y.idx))
          .map((x) => x.v);
        const vis = ordered.filter((f) => f.visible);
        return {
          title, order: b.order, fields: ordered, actions: b.actions,
          allVisibleFieldsAdvanced: vis.length > 0 && vis.every((f) => f.advanced),
        };
      })
      .sort((a, b) => a.order - b.order || cmp(a.title, b.title));

    components.push({
      instanceKey: inst.instanceKey,
      type: comp.type,
      ...(comp.role === undefined || comp.role === null || comp.role === '' ? {} : { role: comp.role }),
      sections,
    });
  }

  // FE-D17: byte-stable payload. Sorted so the same logical draft hashes identically
  // regardless of key insertion order. UI-D20: layout never enters this.
  rendered.sort((a, b) => cmp(a.instanceKey, b.instanceKey) || cmp(a.renderedPath, b.renderedPath));
  excluded.sort((a, b) => cmp(a.instanceKey, b.instanceKey) || cmp(a.key, b.key) || cmp(a.reason, b.reason));

  return {
    engineVersion: ENGINE_VERSION,
    solution: pkg.solution,
    packageVersion,
    components,
    rendered,
    excluded,
    metrics,
    status: {
      ...status,
      healthAvailability: anyHealthDeclared ? 'declared' : 'not-declared',
      healthRollup: healthRollupOf(anyHealthDeclared, status.replicas),
    },
    diagnostics,
  };
}

function fieldOrder(fields: readonly PackageField[], idx: number): number {
  return fields[idx]?.ui?.order ?? Number.MAX_SAFE_INTEGER;
}

/**
 * Recursively sort object keys so the same logical value serializes identically.
 * `JSON.stringify` preserves key INSERTION order, so a `map`-typed field re-entered in a
 * different order would otherwise hash differently and look like a config change.
 */
function canonicalize(v: unknown): unknown {
  if (Array.isArray(v)) return v.map(canonicalize);
  if (v === null || typeof v !== 'object') return typeof v === 'number' && !Number.isFinite(v) ? null : v;
  const out: Record<string, unknown> = {};
  for (const k of Object.keys(v as Record<string, unknown>).sort(cmp)) {
    out[k] = canonicalize((v as Record<string, unknown>)[k]);
  }
  return out;
}

/** FE-D17: the exact bytes a save/diff/hash consumes. */
export function hashInput(vm: ViewModel): string {
  return JSON.stringify(vm.rendered.map((r) => [r.instanceKey, r.renderedPath, canonicalize(r.value)]));
}
