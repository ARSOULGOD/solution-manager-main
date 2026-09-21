import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { ReviewMeter } from './ReviewMeter';

describe('ReviewMeter', () => {
  it('renders the fully-reviewed state (ok) with a plain caption, no qualifier', () => {
    renderWithProviders(<ReviewMeter authoring={{ reviewedRows: 1494, totalRows: 1494 }} />);

    expect(screen.getByText('Fully reviewed')).not.toBeNull();
    expect(screen.getByText('1,494 / 1,494 rows reviewed')).not.toBeNull();
  });

  it('renders the partial state (warn) with copy stating partial is normal', () => {
    renderWithProviders(<ReviewMeter authoring={{ reviewedRows: 1287, totalRows: 1494 }} />);

    expect(screen.getByText('86% reviewed')).not.toBeNull();
    expect(screen.getByText(/the remainder auto-extracted — a normal state/)).not.toBeNull();
  });

  it('renders the zero-review state (err) distinctly from fully reviewed', () => {
    renderWithProviders(<ReviewMeter authoring={{ reviewedRows: 0, totalRows: 1494 }} />);

    expect(screen.getByText('Not reviewed')).not.toBeNull();
    expect(screen.getByText(/assembled by automated extraction, unreviewed/)).not.toBeNull();
    expect(screen.queryByText('Fully reviewed')).toBeNull();
  });
});
