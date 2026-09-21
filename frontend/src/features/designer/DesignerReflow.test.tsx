import { describe, expect, it, vi } from 'vitest';

import type { FieldDeclaration, ObservedState, SolutionPackage } from '@contracts/view-model/types';
import { reflowFloorPx } from '@shared/theme/breakpoints';

import { setViewportWidth } from '../../test/matchMedia';
import { assertReflow } from '../../test/reflow';
import {
  fireEvent,
  renderWithProviders,
  screen,
  waitFor,
  within,
} from '../../test/renderWithProviders';

import type { ActionsRepository } from './actionsRepository';
import type { ApplyRepository } from './applyRepository';
import type { ConfigRepository, DesignerLoad } from './configRepository';
import { DesignerPage } from './DesignerPage';

/**
 * Designer reflow at the 320px floor (task 125, `G16`/`G17`, `FE-D29`,
 * WCAG 2.2 AA SC 1.4.10) — drives the REAL `DesignerPage` (task 123/124),
 * not hand-built `ViewSection` literals and a stub repository, so this is
 * the same code path production renders. Keeps task 108's own 100-field /
 * 10-section scale and its "one section at a time" constraint: mounting
 * every `FieldRow` at once starves the rest of the vitest pool, and — now
 * that `SectionCard`'s accordion can hold several sections open at
 * once — a section explicitly collapsed here before the next is opened,
 * not implicitly by a single-select harness the way the pre-`FE-125`
 * version did.
 *
 * **Found here: `DesignerForm` was hiding `SectionNav` outright below
 * `md`.** `SectionNav` already reflows itself (task 108) — a persistent
 * rail at `lg` and up, a "Jump to section" button opening a `Drawer`
 * below it, all the way to 320px. The blanket hide duplicated (and, at
 * the 320px floor, broke) a decision `SectionNav` already owned correctly
 * — fixed in `DesignerForm.tsx` alongside this rewrite, not worked around
 * here.
 */
const FIELD_COUNT = 100;
const SECTION_COUNT = 10;
const FIELDS_PER_SECTION = FIELD_COUNT / SECTION_COUNT;

function hundredFields(): readonly FieldDeclaration[] {
  const declarations: FieldDeclaration[] = [];
  for (let sectionIndex = 0; sectionIndex < SECTION_COUNT; sectionIndex += 1) {
    for (let offset = 0; offset < FIELDS_PER_SECTION; offset += 1) {
      const n = sectionIndex * FIELDS_PER_SECTION + offset + 1;
      declarations.push({
        key: `field_${String(n)}`,
        type: 'string',
        label: `Field ${String(n)}`,
        ui: { section: `Section ${String(sectionIndex + 1)}`, order: sectionIndex },
      });
    }
  }
  return declarations;
}

function reflowPackage(): SolutionPackage {
  return {
    solution: 'cache',
    packageVersion: [7],
    appliesTo: '>=1.0 <2.0',
    provenance: { author: 'solace', signature: 'reflow-fixture' },
    authoring: { reviewedRows: 0, totalRows: FIELD_COUNT },
    components: [
      {
        type: 'cache-engine',
        cardinality: 'exactly-one',
        configPath: 'cache-engine',
        loggerPrefix: 'com.solace.solutions.cache',
        fields: hundredFields(),
      },
    ],
  };
}

const OBSERVED: ObservedState = { health: 'healthy', versionState: 'ok', configState: 'in_sync' };

function reflowLoad(): DesignerLoad {
  return {
    package: reflowPackage(),
    packageVersion: 7,
    values: {},
    observed: OBSERVED,
    configGeneration: 'gen-41',
  };
}

function stubConfigRepository(): ConfigRepository {
  return {
    loadDesigner: () => Promise.resolve(reflowLoad()),
    save: () => Promise.reject(new Error('not used in this test')),
    history: () => Promise.resolve([]),
    setSecret: () => Promise.reject(new Error('not used in this test')),
    acceptRuntime: () => Promise.reject(new Error('not used in this test')),
    restoreRuntime: () => Promise.reject(new Error('not used in this test')),
  };
}

function stubApplyRepository(): ApplyRepository {
  return {
    stage: () => Promise.reject(new Error('not used in this test')),
    observe: () => Promise.reject(new Error('not used in this test')),
    rollback: () => Promise.reject(new Error('not used in this test')),
  };
}

function stubActionsRepository(): ActionsRepository {
  return { run: () => Promise.reject(new Error('not used in this test')) };
}

/** Anchored + not-followed-by-a-digit: "Field 1" would otherwise also match "Field 10"/"Field 100". */
function fieldLabelPattern(n: number): RegExp {
  return new RegExp(`^Field ${String(n)}(?!\\d)`);
}

function renderReflowDesigner() {
  return renderWithProviders(
    <DesignerPage
      instanceId="reflow-fixture-01"
      configRepository={stubConfigRepository()}
      applyRepository={stubApplyRepository()}
      actionsRepository={stubActionsRepository()}
    />,
  );
}

describe('Designer reflow (task 125, G16/G17, FE-D29) — 100-field fixture at 320px, through the real DesignerPage', () => {
  it('every field is reachable and editable at the 320px floor, with save ≠ apply unchanged, no two-dimensional scroll', async () => {
    setViewportWidth(reflowFloorPx);
    const { container } = renderReflowDesigner();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Save draft' })).not.toBeNull();
    });
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Apply…' })).not.toBeDisabled();

    for (let sectionIndex = 0; sectionIndex < SECTION_COUNT; sectionIndex += 1) {
      const title = `Section ${String(sectionIndex + 1)}`;
      // Anchored + not-followed-by-a-digit: "Section 1" would otherwise
      // also match the accessible name of "Section 10".
      const titlePattern = new RegExp(`^${title}(?!\\d)`);
      const start = sectionIndex * FIELDS_PER_SECTION + 1;

      fireEvent.click(screen.getByRole('button', { name: titlePattern, expanded: false }));
      await waitFor(() => {
        expect(screen.getByLabelText(fieldLabelPattern(start))).not.toBeNull();
      });

      const rows = document.querySelectorAll('[data-field-row]');
      expect(rows).toHaveLength(FIELDS_PER_SECTION);
      for (let offset = 0; offset < FIELDS_PER_SECTION; offset += 1) {
        const n = start + offset;
        expect(rows[offset]?.getAttribute('data-field-key')).toBe(`field_${String(n)}`);
      }

      fireEvent.change(screen.getByLabelText(fieldLabelPattern(start)), {
        target: { value: `v${String(start)}` },
      });

      // Collapse before the next section opens — keeps only one section's
      // ~10 `FieldRow`s mounted at a time (the whole point of this scale).
      fireEvent.click(screen.getByRole('button', { name: titlePattern, expanded: true }));
      await waitFor(() => {
        expect(screen.queryByLabelText(fieldLabelPattern(start))).toBeNull();
      });
    }

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Save draft' })).not.toBeDisabled();
    });
    expect(screen.getByRole('button', { name: 'Apply…' })).toBeDisabled();

    expect(() => {
      assertReflow(container.firstElementChild as HTMLElement, reflowFloorPx);
    }).not.toThrow();
  });

  it('section jump from the collapsed nav drawer still expands the target and moves focus, at the 320px floor', async () => {
    setViewportWidth(reflowFloorPx);
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    renderReflowDesigner();

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Jump to section' })).not.toBeNull();
    });
    expect(screen.queryByLabelText(fieldLabelPattern(91))).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Jump to section' }));
    const nav = screen.getByRole('navigation', { name: 'Sections' });
    fireEvent.click(within(nav).getByRole('button', { name: /Section 10/ }));

    const input = await screen.findByLabelText<HTMLInputElement>(fieldLabelPattern(91));
    await waitFor(() => {
      expect(document.activeElement).toBe(input);
    });
    expect(scrollIntoView).toHaveBeenCalled();

    // Selecting a section closes the drawer (`SectionNav`'s own rule).
    await waitFor(() => {
      expect(screen.queryByRole('navigation', { name: 'Sections' })).toBeNull();
    });
  });
});
