/**
 * Maps canonical nested UI metadata to the projection engine's presentation
 * fields. Version arrays pass through unchanged: one uploaded package serves
 * several package schema versions, and projection selects exactly one.
 */
import type {
  FieldDeclaration,
  ActionDeclaration as ViewModelAction,
  SolutionPackage as ViewModelPackage,
} from '../view-model/types';

import type { CanonicalSolutionPackage, PackageAction, PackageField } from './types';

function normalizeField(field: PackageField): FieldDeclaration {
  const normalized: FieldDeclaration = {
    key: field.key,
    type: field.type,
    ...(field.version === undefined ? {} : { version: field.version }),
    ...(field.options === undefined ? {} : { options: field.options.map(String) }),
    ...(field.optionsSource === undefined ? {} : { optionsSource: field.optionsSource }),
    ...(field.default === undefined ? {} : { default: field.default }),
    ...(field.discriminator === undefined ? {} : { discriminator: field.discriminator }),
    ...(field.when === undefined ? {} : { when: field.when }),
    ...(field.secret === undefined && field.type !== 'secret'
      ? {}
      : { secret: field.type === 'secret' || field.secret === true }),
    ...(field.required === undefined ? {} : { required: field.required }),
    ...(field.min === undefined ? {} : { min: field.min }),
    ...(field.max === undefined ? {} : { max: field.max }),
    ...(field.pattern === undefined ? {} : { pattern: field.pattern }),
    ...(field.virtual === undefined ? {} : { virtual: field.virtual }),
    ...(field.columns === undefined
      ? {}
      : {
          columns: field.columns.map((column) => ({
            key: column.key,
            type: column.type,
            ...(column.options === undefined ? {} : { options: column.options.map(String) }),
            ...(column.label === undefined ? {} : { label: column.label }),
          })),
        }),
    ...(field.ui?.label === undefined ? {} : { label: field.ui.label }),
    ...(field.ui?.advanced === undefined ? {} : { advanced: field.ui.advanced }),
    ...(field.ui?.helperText === undefined ? {} : { helperText: field.ui.helperText }),
    ...(field.ui === undefined
      ? {}
      : {
          ui: {
            ...(field.ui.section === undefined ? {} : { section: field.ui.section }),
            ...(field.ui.order === undefined ? {} : { order: field.ui.order }),
            ...(field.ui.readOnly === undefined ? {} : { readOnly: field.ui.readOnly }),
          },
        }),
  };
  return normalized;
}

function normalizeAction(action: PackageAction): ViewModelAction {
  return {
    key: action.key,
    kind: action.kind,
    ...(action.entity === undefined ? {} : { entity: action.entity }),
    ...(action.inputs === undefined ? {} : { inputs: action.inputs }),
    ...(action.when === undefined ? {} : { when: action.when }),
  };
}

export function toFrontendPackage(pkg: CanonicalSolutionPackage): ViewModelPackage {
  return {
    solution: pkg.solution,
    packageVersion: pkg.packageVersion,
    appliesTo: pkg.appliesTo,
    provenance: {
      author: pkg.provenance?.author ?? 'dev-mode',
      ...(pkg.provenance?.signature === undefined ? {} : { signature: pkg.provenance.signature }),
    },
    authoring: {
      reviewedRows: pkg.authoring?.reviewedRows ?? 0,
      totalRows: pkg.authoring?.totalRows ?? 0,
    },
    components: pkg.components.map((component) => ({
      type: component.type,
      cardinality: component.cardinality,
      ...(component.version === undefined ? {} : { version: component.version }),
      ...(component.role === undefined ? {} : { role: component.role }),
      configPath: component.configPath,
      loggerPrefix: component.loggerPrefix ?? '',
      fields: (component.fields ?? []).map(normalizeField),
      ...(component.actions === undefined
        ? {}
        : { actions: component.actions.map(normalizeAction) }),
      ...(component.metrics === undefined ? {} : { metrics: component.metrics }),
    })),
  };
}
