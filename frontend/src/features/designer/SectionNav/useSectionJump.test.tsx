import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { FieldValue, ViewField, ViewSection } from '@contracts/view-model/types';

import { renderWithProviders, screen, within } from '../../../test/renderWithProviders';
import { SectionCard } from '../SectionCard';
import { ValidationSummary } from '../ValidationSummary';

import { SectionNav } from './SectionNav';
import { useSectionJump } from './useSectionJump';

function field(overrides: Partial<ViewField> = {}): ViewField {
  return {
    key: overrides.key ?? 'a',
    label: overrides.label ?? 'A',
    renderedPath: 'component.a',
    discriminator: false,
    controlKind: 'text',
    value: { kind: 'unset' },
    options: [],
    enabled: true,
    visible: true,
    masked: false,
    advanced: false,
    validations: [],
    ...overrides,
  };
}

function section(overrides: Partial<ViewSection> = {}): ViewSection {
  return { title: 'General', order: 0, fields: [field()], actions: [], ...overrides };
}

function Harness({ sections }: { sections: readonly ViewSection[] }) {
  const { containerRef, isExpanded, toggle, jumpToSection } = useSectionJump(sections);
  const values: Record<string, FieldValue> = {};
  return (
    <div>
      <SectionNav sections={sections} onJump={jumpToSection} />
      <div ref={containerRef}>
        {sections.map((s, index) => (
          <SectionCard
            key={index}
            section={s}
            values={values}
            onFieldChange={() => {}}
            expanded={isExpanded(index)}
            onToggleExpanded={() => toggle(index)}
          />
        ))}
      </div>
    </div>
  );
}

function SummaryHarness({ sections }: { sections: readonly ViewSection[] }) {
  const { containerRef, isExpanded, toggle, jumpToField } = useSectionJump(sections);
  const values: Record<string, FieldValue> = {};
  return (
    <div>
      <ValidationSummary sections={sections} onJump={jumpToField} />
      <div ref={containerRef}>
        {sections.map((s, index) => (
          <SectionCard
            key={index}
            section={s}
            values={values}
            onFieldChange={() => {}}
            expanded={isExpanded(index)}
            onToggleExpanded={() => toggle(index)}
          />
        ))}
      </div>
    </div>
  );
}

describe('useSectionJump', () => {
  it('expands the target section, scrolls its first applicable field into view, and focuses it', () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;

    renderWithProviders(
      <Harness
        sections={[
          section({ title: 'General', fields: [field({ key: 'namespace', label: 'Namespace' })] }),
          section({ title: 'Broker', fields: [field({ key: 'host', label: 'Host' })] }),
        ]}
      />,
    );

    // Second section starts collapsed — its field isn't rendered yet.
    expect(screen.queryByLabelText('Host')).toBeNull();

    const nav = screen.getByRole('navigation');
    fireEvent.click(within(nav).getByRole('button', { name: /Broker/ }));

    const input = screen.getByLabelText<HTMLInputElement>('Host');
    expect(input).not.toBeNull();
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' });
    expect(document.activeElement).toBe(input);
  });

  it('is a no-op when the target section has no applicable field', () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;

    renderWithProviders(<Harness sections={[section({ fields: [field({ visible: false })] })]} />);

    const nav = screen.getByRole('navigation');
    fireEvent.click(within(nav).getByRole('button', { name: /General/ }));
    expect(scrollIntoView).not.toHaveBeenCalled();
    // Section stayed collapsed — jumping to nothing shouldn't expand it either.
    expect(screen.queryByText('No fields apply here with the current selections.')).toBeNull();
  });

  it('jumpToField (task 054, ValidationSummary) expands the field’s own section, scrolls it into view, and focuses it — even when it is not the first field in that section', () => {
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;

    renderWithProviders(
      <SummaryHarness
        sections={[
          section({ title: 'General', fields: [field({ key: 'namespace', label: 'Namespace' })] }),
          section({
            title: 'Broker',
            fields: [
              field({ key: 'host', label: 'Host' }),
              field({ key: 'port', label: 'Port', validations: [{ tier: 'bind', code: 'REQUIRED', message: 'x' }] }),
            ],
          }),
        ]}
      />,
    );

    // Broker starts collapsed — neither of its fields is rendered yet.
    expect(screen.queryByLabelText('Port')).toBeNull();

    fireEvent.click(screen.getByText('Port'));

    // `Port` is `required` (it carries a `REQUIRED` validation), so MUI adds
    // an asterisk to the label's accessible name — same non-exact match
    // `FieldRow.test.tsx` uses for a required field.
    const input = screen.getByLabelText<HTMLInputElement>(/Port/);
    expect(input).not.toBeNull();
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'start' });
    expect(document.activeElement).toBe(input);
  });
});
