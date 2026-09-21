/**
 * Compile-only: a secret row cannot carry a real string on old/new.
 * `@ts-expect-error` fails tsc if the next line does *not* error.
 */
import { describe, expect, it } from 'vitest';

import { SECRET_MASK, type DiffRow } from './DiffView.types';

describe('DiffRow secret mask (compile-time)', () => {
  it('rejects a plaintext value on a secret row', () => {
    const ok: DiffRow = {
      path: 'broker.password',
      kind: 'change',
      secret: true,
      old: SECRET_MASK,
      new: SECRET_MASK,
    };
    // @ts-expect-error — secret rows may only carry the mask token
    const leaked: DiffRow = {
      path: 'broker.password',
      kind: 'change',
      secret: true,
      old: 'hunter2',
      new: 'hunter2',
    };

    expect(ok).toBeDefined();
    expect(leaked).toBeDefined();
  });
});
