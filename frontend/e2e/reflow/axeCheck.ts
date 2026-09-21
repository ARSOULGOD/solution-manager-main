/**
 * Runs the real `axe-core` engine (not `jest-axe`'s jsdom shim — task 098
 * already covers that layer per-component) against a real rendered page,
 * for FE-110's "axe clean at 320 px in both themes" acceptance line.
 *
 * Scoped to WCAG 2.x A/AA tags, matching design §18's own stated bar
 * ("Bar: WCAG 2.2 Level AA") — axe's un-scoped default run also includes
 * `best-practice`-tagged rules (`region`, `skip-link`, …) that are real
 * and worth fixing when found (task 110 did fix a genuine broken skip
 * link this way — see `AppShell.tsx`/`LoginPage.tsx`), but are not this
 * gate's pass/fail bar; a best-practice rule failing the build would move
 * design's own bar without a decision recorded for it (`I3`/`I4`).
 *
 * `color-contrast` is excluded on top of that scoping, deliberately, not
 * silently: it IS `wcag2aa`-tagged, but design §18 states contrast
 * "cannot be signed off until open item 19 tokens exist" — the interim
 * navy/teal palette is a known, tracked gap (`DECISIONS.md` open item
 * 19), not something this task's reflow gate should fail the build over.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import type { Page } from '@playwright/test';

const axeSource = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), '../../node_modules/axe-core/axe.min.js'),
  'utf8',
);

export interface AxeViolation {
  id: string;
  impact: string | null;
  description: string;
  nodes: number;
}

export async function runAxe(page: Page): Promise<AxeViolation[]> {
  await page.addScriptTag({ content: axeSource });
  return page.evaluate(async () => {
    interface AxeResultNode {
      html: string;
    }
    interface AxeResult {
      id: string;
      impact: string | null;
      description: string;
      nodes: AxeResultNode[];
    }
    interface AxeGlobal {
      run: (
        context: Document,
        options: {
          runOnly: { type: 'tag'; values: string[] };
          rules: Record<string, { enabled: boolean }>;
        },
      ) => Promise<{ violations: AxeResult[] }>;
    }
    const axe = (window as unknown as { axe: AxeGlobal }).axe;
    const results = await axe.run(document, {
      runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
      rules: { 'color-contrast': { enabled: false } },
    });
    return results.violations.map((violation) => ({
      id: violation.id,
      impact: violation.impact,
      description: violation.description,
      nodes: violation.nodes.length,
    }));
  });
}
