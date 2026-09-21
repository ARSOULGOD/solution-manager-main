import { describe, expect, it } from 'vitest';

import { reflowFloorPx } from '@shared/theme/breakpoints';

import { assertReflow } from '../../../test/reflow';
import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import { Button } from '../Button';
import { PageWidth } from '../layout/PageWidth';

import { PageHeader } from './PageHeader';

describe('PageHeader', () => {
  it('renders title, sub and the action slot', () => {
    renderWithProviders(
      <PageHeader
        title="Instances"
        sub="Declared here; deployed by your CI/CD."
        actions={<Button>Declare</Button>}
      />,
    );

    expect(screen.getByRole('heading', { name: 'Instances' })).not.toBeNull();
    expect(screen.getByText('Declared here; deployed by your CI/CD.')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Declare' })).not.toBeNull();
  });

  it('omits the action slot when the caller passes nothing — never a disabled stand-in', () => {
    renderWithProviders(<PageHeader title="Instances" />);
    expect(screen.queryByRole('button')).toBeNull();
    expect(document.querySelector('[data-page-header-actions]')).toBeNull();
  });

  it('does not force horizontal page-body scroll at 320px with wrapping actions', () => {
    const { container } = renderWithProviders(
      <PageWidth>
        <PageHeader
          title="A reasonably long page title that must wrap rather than overflow"
          sub="Sub-line also wraps."
          actions={
            <>
              <Button>Declare</Button>
              <Button variant="outlined">Export</Button>
              <Button variant="danger">Restart all</Button>
            </>
          }
        />
      </PageWidth>,
    );

    const root = container.querySelector('[data-layout="page-width"]');
    expect(root).toBeInstanceOf(HTMLElement);
    if (!(root instanceof HTMLElement)) {
      return;
    }
    expect(() => {
      assertReflow(root, reflowFloorPx);
    }).not.toThrow();
  });
});
