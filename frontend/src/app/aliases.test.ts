import { describe, expect, it } from 'vitest';

import { NotFound } from '@app/NotFound';
import { project } from '@contracts/view-model/project';
import { FEATURES_PLACEHOLDER } from '@features/index';
import { SHARED_PLACEHOLDER } from '@shared/index';

// Proves the @app/*, @shared/*, @features/* and @contracts/* path aliases
// resolve under Vitest (which shares vite.config.ts's resolve.alias) and
// under tsc (this file is included by tsconfig.json). @app/* is additionally
// exercised by the production build graph via src/app/main.tsx. @contracts/*
// now points at `frontend/contracts/` (task 046) — its own placeholder
// (`src/contracts/`, task 001) is retired; see task 046's report for why.
describe('path aliases', () => {
  it('resolve @app/*', () => {
    expect(typeof NotFound).toBe('function');
  });

  it('resolve @shared/*', () => {
    expect(SHARED_PLACEHOLDER).toBe('shared');
  });

  it('resolve @features/*', () => {
    expect(FEATURES_PLACEHOLDER).toBe('features');
  });

  it('resolve @contracts/*', () => {
    expect(typeof project).toBe('function');
  });
});
