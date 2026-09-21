/**
 * Compile-only proof for FE-058's own acceptance line: no action request
 * body can contain a resolved secret. `ActionsRepository.run` (task 033)
 * takes only `instanceId` and the action's own `key` (`I6`, `UI-D22`) —
 * there is no third parameter for a caller to smuggle field VALUES
 * through, so this is a structural guarantee, not a runtime check.
 * `@ts-expect-error` fails `tsc` if the next line does *not* error.
 */
import { describe, expect, it } from 'vitest';

import { createDpClient } from '@shared/api';

import { createActionsRepository } from './actionsRepository';

describe('ActionsRepository.run (compile-time)', () => {
  it('accepts no body beyond instanceId and actionKey', () => {
    const repo = createActionsRepository(createDpClient('/api'));

    // @ts-expect-error — a third argument would be a request body; the
    // signature has none, so a resolved secret cannot cross this boundary.
    void repo.run('cache-prod-01', 'TEST_BROKER_CONNECTION', { 'solace.password': 'hunter2' });

    expect(repo).toBeDefined();
  });
});
