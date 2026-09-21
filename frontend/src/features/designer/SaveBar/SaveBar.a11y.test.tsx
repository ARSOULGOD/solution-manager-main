import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';
import type { ConfigRepository } from '../configRepository';
import type { ValidationEntry } from '../ValidationSummary/collectValidationEntries';

import { SaveBar } from './SaveBar';

const WARNING: ValidationEntry = {
  sectionIndex: 0,
  fieldKey: 'cacheSizeMb',
  label: 'Cache size (MB)',
  message: 'Above the range Solace recommends (1–1000000).',
};

function stubRepository(): ConfigRepository {
  return {
    loadDesigner: () => Promise.reject(new Error('not used')),
    save: () => Promise.reject(new Error('not used')),
    history: () => Promise.resolve([]),
    setSecret: () => Promise.reject(new Error('not used')),
    acceptRuntime: () => Promise.reject(new Error('not used')),
    restoreRuntime: () => Promise.reject(new Error('not used')),
  };
}

const modes = ['light', 'dark'] as const;

describe('SaveBar a11y', () => {
  it.each(modes)(
    'has no violations in %s mode: clean, blocking, and recommendation-only states',
    async (mode) => {
      const { container } = renderWithProviders(
        <div>
          <SaveBar
            instanceId="cache-prod-01"
            repository={stubRepository()}
            payload={{}}
            generation="gen-41"
            dirtyCount={0}
            blockingCount={0}
            warnings={[]}
            onSaved={() => undefined}
          />
          <SaveBar
            instanceId="cache-prod-01"
            repository={stubRepository()}
            payload={{}}
            generation="gen-41"
            dirtyCount={2}
            blockingCount={1}
            warnings={[WARNING]}
            onSaved={() => undefined}
          />
        </div>,
        { mode },
      );

      expect(await axe(container)).toHaveNoViolations();
    },
  );
});
