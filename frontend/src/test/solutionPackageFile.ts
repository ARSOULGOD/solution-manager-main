export const VALID_SOLUTION_PACKAGE = {
  solution: 'widget-mesh',
  packageVersion: [1, 2],
  appliesTo: '>=2.0 <3.0',
  provenance: {
    author: 'solace',
    signature: 'test-signature',
  },
  authoring: {
    reviewedRows: 1,
    totalRows: 1,
  },
  components: [
    {
      type: 'widget-core',
      cardinality: 'exactly-one',
      version: [1, 2],
      role: 'none',
      configPath: 'widget-core',
      fields: [
        {
          key: 'DISPLAY_NAME',
          type: 'string',
          version: [1, 2],
          required: true,
          ui: {
            label: 'Display name',
            section: 'General',
            order: 0,
          },
        },
      ],
    },
  ],
} as const;

export function validSolutionPackageFile(name = 'widget-mesh-2.json'): File {
  return new File([JSON.stringify(VALID_SOLUTION_PACKAGE)], name, { type: 'application/json' });
}
