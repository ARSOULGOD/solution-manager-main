/**
 * `normalize()`'s own regression guard (task 9 fix round 1).
 *
 * Every other test that reads `promoteToDeclare`/`valueFrom` off a
 * `FieldDeclaration` builds that object directly in the engine dialect —
 * `declareForm.test.ts`'s `CACHE_PKG`, `DeclareInstanceDialog.test.tsx`'s
 * `packageWithBroker()` — which bypasses `toFrontendPackage()` entirely.
 * This is the one test that runs the REAL shipped
 * `frontend/contracts/packages/cache/package.json` through the REAL
 * conversion, so a re-dropped passthrough (the exact bug task 9 found and
 * fixed) fails here instead of only in a human's hands on the Declare
 * dialog.
 */
import { describe, expect, it } from 'vitest';

import CACHE_PACKAGE from '../packages/cache/package.json';

import { toFrontendPackage } from './normalize';
import type { CanonicalSolutionPackage } from './types';

describe('toFrontendPackage: valueFrom / promoteToDeclare passthrough', () => {
  it('carries valueFrom and promoteToDeclare through the real shipped Cache package', () => {
    const normalized = toFrontendPackage(CACHE_PACKAGE as unknown as CanonicalSolutionPackage);
    const fields = normalized.components.flatMap((component) => component.fields);

    const selector = fields.find((field) => field.key === 'solaceBroker');
    expect(selector?.ui?.promoteToDeclare).toBe(true);

    const host = fields.find((field) => field.key === 'solace.host');
    expect(host?.valueFrom).toEqual({ ref: 'solaceBroker', attribute: 'host' });
  });
});
