import { fireEvent } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { PackageValidationResult } from '@contracts/solution-package';

import { renderWithProviders, screen, within } from '../../../test/renderWithProviders';

import { PackageValidationSummary } from './PackageValidationSummary';

function validResult(): PackageValidationResult {
  return {
    valid: true,
    package: {
      solution: 'widget-mesh',
      packageVersion: [1],
      appliesTo: '>=1.0 <2.0',
      components: [
        {
          type: 'widget-core',
          cardinality: 'exactly-one',
          role: 'none',
          configPath: 'widget-core',
          fields: [
            { key: 'DISPLAY_NAME', type: 'string', required: true },
            { key: 'API_KEY', type: 'secret', secret: true, default: 'do-not-show-me' },
          ],
          actions: [{ key: 'TEST_CONNECTION', kind: 'test-connection' }],
          metrics: [{ name: 'requests_total', metricType: 'counter', unit: 'count', source: { kind: 'http_json', onUnavailable: 'degrade' } }],
        },
      ],
    },
    summary: {
      solution: 'widget-mesh',
      packageVersion: [1],
      appliesTo: '>=1.0 <2.0',
      componentCount: 1,
      fieldCount: 2,
      actionCount: 1,
      metricCount: 1,
      components: [
        { type: 'widget-core', cardinality: 'exactly-one', fieldCount: 2, actionCount: 1, metricCount: 1 },
      ],
    },
  };
}

describe('PackageValidationSummary', () => {
  it('does not show a component\'s field/action/metric detail until its row is clicked', () => {
    renderWithProviders(<PackageValidationSummary result={validResult()} />);

    expect(screen.queryByText('DISPLAY_NAME')).not.toBeInTheDocument();
  });

  it("reveals a component's declared fields, actions and metrics on click, without needing the raw JSON", () => {
    renderWithProviders(<PackageValidationSummary result={validResult()} />);

    fireEvent.click(screen.getByRole('button', { name: /widget-core/ }));

    const fieldRow = screen.getByText('DISPLAY_NAME').closest('[data-package-field]') as HTMLElement;
    expect(within(fieldRow).getByText('Required')).toBeInTheDocument();

    expect(screen.getByText('TEST_CONNECTION')).toBeInTheDocument();
    expect(screen.getByText('requests_total')).toBeInTheDocument();
  });

  it('marks a secret field, and never displays its declared default value', () => {
    renderWithProviders(<PackageValidationSummary result={validResult()} />);

    fireEvent.click(screen.getByRole('button', { name: /widget-core/ }));

    const secretRow = screen.getByText('API_KEY').closest('[data-package-field]') as HTMLElement;
    expect(within(secretRow).getByText('Secret')).toBeInTheDocument();
    expect(screen.queryByText('do-not-show-me')).not.toBeInTheDocument();
  });

  it('collapses the detail again on a second click', () => {
    renderWithProviders(<PackageValidationSummary result={validResult()} />);

    const toggle = screen.getByRole('button', { name: /widget-core/ });
    fireEvent.click(toggle);
    expect(screen.getByText('DISPLAY_NAME')).toBeInTheDocument();

    fireEvent.click(toggle);
    expect(screen.queryByText('DISPLAY_NAME')).not.toBeInTheDocument();
  });
});
