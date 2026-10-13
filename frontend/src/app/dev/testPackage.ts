/**
 * A minimal, VALID Solution Package for the dev-loader tests, plus a
 * helper for mutating one field at a time.
 *
 * Deliberately synthetic: these tests are about the loader, and a fixture
 * small enough to read in one screen makes "this field is what broke it"
 * obvious. The equivalence between a package and the Designer it produces
 * is `cachePackageEquivalence.test.ts`'s job, and that one uses a real
 * package.
 *
 * DEV-only, and test-only: nothing outside `*.test.ts` imports this.
 */

export const TEST_PACKAGE = {
  solution: 'widget-mesh',
  packageVersion: [2],
  appliesTo: '>=1.0 <2.0',
  provenance: { author: 'acme', signature: 'ab12…cd34' },
  authoring: { reviewedRows: 3, totalRows: 4 },
  components: [
    {
      type: 'widget-connector',
      cardinality: 'exactly-one',
      configPath: 'widget-connector',
      loggerPrefix: 'com.acme.widget',
      fields: [
        {
          key: 'MODE',
          type: 'enum',
          options: ['push', 'pull'],
          default: 'pull',
          discriminator: true,
          required: true,
          label: 'Mode',
          ui: { section: 'General', order: 0 },
        },
        {
          key: 'POLL_INTERVAL',
          type: 'duration',
          default: '10s',
          when: { MODE: 'pull' },
          label: 'Poll interval',
          ui: { section: 'General', order: 1 },
        },
        {
          key: 'BATCH_SIZE',
          type: 'int',
          default: 50,
          min: 1,
          max: 500,
          advanced: true,
          label: 'Batch size',
          ui: { section: 'Tuning', order: 0 },
        },
      ],
      actions: [{ key: 'TEST_CONNECTION', kind: 'test-connection', inputs: ['MODE'] }],
      metrics: [
        {
          name: 'widget.processed',
          metricType: 'counter',
          unit: 'widgets',
          source: { kind: 'http_json', path: '/metrics', onUnavailable: 'degrade' },
        },
      ],
    },
  ],
};

/** Canonical upload form of the same multi-version package. */
export const CANONICAL_TEST_PACKAGE = {
  solution: TEST_PACKAGE.solution,
  packageVersion: TEST_PACKAGE.packageVersion,
  appliesTo: TEST_PACKAGE.appliesTo,
  provenance: { author: 'solace', signature: 'ab12…cd34' },
  authoring: TEST_PACKAGE.authoring,
  components: TEST_PACKAGE.components.map((component) => ({
    type: component.type,
    cardinality: component.cardinality,
    configPath: component.configPath,
    loggerPrefix: component.loggerPrefix,
    fields: component.fields.map((declaration) => {
      const { label, helperText, advanced, ui, ...field } = declaration as typeof declaration & {
        label?: string;
        helperText?: string;
        advanced?: boolean;
      };
      return {
        ...field,
        ui: {
          ...ui,
          ...(label === undefined ? {} : { label }),
          ...(helperText === undefined ? {} : { helperText }),
          ...(advanced === undefined ? {} : { advanced }),
        },
      };
    }),
    actions: component.actions,
    metrics: component.metrics,
  })),
};

/** A deep clone with one top-level key replaced (or removed, with `undefined`). */
export function packageWith(overrides: Record<string, unknown>): unknown {
  const clone = structuredClone(TEST_PACKAGE) as Record<string, unknown>;
  for (const [key, value] of Object.entries(overrides)) {
    if (value === undefined) {
      delete clone[key];
    } else {
      clone[key] = value;
    }
  }
  return clone;
}

/** The same package as a JSON `File`, as the load dialog would hand it over. */
export function packageFile(name = 'package.json', body: unknown = CANONICAL_TEST_PACKAGE): File {
  return new File([JSON.stringify(body)], name, { type: 'application/json' });
}
