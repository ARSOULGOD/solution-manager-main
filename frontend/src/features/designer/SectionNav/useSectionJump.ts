/**
 * The expand-then-scroll-then-focus behaviour behind `SectionNav` (task
 * 050, design §18: "the target field receives focus, not just scroll").
 * Owns the accordion's `expanded` set — the same controlled shape
 * `SectionCard` (task 049) takes — so a nav click and a section's own
 * header both drive one piece of state.
 *
 * Scrolling and focusing happen in a `useEffect`, not inline in the click
 * handler: a section that was collapsed has no field rows in the DOM yet,
 * so the jump target only exists once React has committed the re-render
 * that the same click's `setExpanded` call triggered. The pending target
 * itself lives in a ref, not state — an effect must not call `setState`
 * on itself (`react-hooks/set-state-in-effect`), so a `jumpToken` counter
 * is what re-triggers the effect, and the ref is what it reads and clears.
 *
 * `jumpToField` (task 054, `ValidationSummary`) targets one specific field
 * by key rather than "the first applicable field" — `jumpToSection` is now
 * a thin wrapper over it so both callers share one expand+scroll+focus path
 * instead of `ValidationSummary` growing a second copy of this mechanism.
 */
import { useCallback, useEffect, useRef, useState } from 'react';

import type { ViewSection } from '@contracts/view-model/types';

const FOCUSABLE_SELECTOR = 'input, textarea, select, button, [tabindex]:not([tabindex="-1"])';

export interface UseSectionJumpResult {
  /** Attach to the scrollable element containing every rendered `FieldRow` (identified by its `data-field-row`/`data-field-key`, task 048). */
  containerRef: React.RefObject<HTMLDivElement | null>;
  expanded: ReadonlySet<number>;
  isExpanded: (index: number) => boolean;
  toggle: (index: number) => void;
  /** Expands `sections[index]`, then scrolls and focuses its first applicable field once it's in the DOM. A no-op if the section has no applicable field. */
  jumpToSection: (index: number) => void;
  /** Expands `sections[index]`, then scrolls and focuses the specific field `fieldKey` once it's in the DOM. A no-op if that field isn't found (e.g. it was hidden by a discriminator change since the caller last read the view model). */
  jumpToField: (index: number, fieldKey: string) => void;
}

export function useSectionJump(sections: readonly ViewSection[]): UseSectionJumpResult {
  const containerRef = useRef<HTMLDivElement>(null);
  const [expanded, setExpanded] = useState<ReadonlySet<number>>(() => new Set());
  const pendingFieldKeyRef = useRef<string | null>(null);
  const [jumpToken, setJumpToken] = useState(0);

  const toggle = useCallback((index: number) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(index)) {
        next.delete(index);
      } else {
        next.add(index);
      }
      return next;
    });
  }, []);

  const jumpToField = useCallback(
    (index: number, fieldKey: string) => {
      const exists = sections[index]?.fields.some((field) => field.key === fieldKey) ?? false;
      if (!exists) {
        return;
      }
      setExpanded((prev) => new Set(prev).add(index));
      pendingFieldKeyRef.current = fieldKey;
      setJumpToken((token) => token + 1);
    },
    [sections],
  );

  const jumpToSection = useCallback(
    (index: number) => {
      const firstApplicable = sections[index]?.fields.find((field) => field.visible);
      if (firstApplicable === undefined) {
        return;
      }
      jumpToField(index, firstApplicable.key);
    },
    [sections, jumpToField],
  );

  useEffect(() => {
    const key = pendingFieldKeyRef.current;
    if (key === null) {
      return;
    }
    const rows = containerRef.current?.querySelectorAll<HTMLElement>('[data-field-row]') ?? [];
    const row = Array.from(rows).find((candidate) => candidate.dataset['fieldKey'] === key);
    if (row !== undefined) {
      if (typeof row.scrollIntoView === 'function') {
        row.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
      row.querySelector<HTMLElement>(FOCUSABLE_SELECTOR)?.focus();
    }
    pendingFieldKeyRef.current = null;
  }, [jumpToken]);

  const isExpanded = useCallback((index: number) => expanded.has(index), [expanded]);

  return { containerRef, expanded, isExpanded, toggle, jumpToSection, jumpToField };
}
