/**
 * The MSW node server every component/route test runs behind
 * (`src/test/setup.ts` starts it with `onUnhandledRequest: "error"` —
 * an unmocked request fails the test rather than drifting invisibly).
 * Task 033 registers the repository-interface handlers as the default set;
 * a feature test overrides one for a specific scenario via `server.use(...)`
 * (reset to these defaults after each test by `src/test/setup.ts`).
 */
import { setupServer } from 'msw/node';

import { handlers } from './handlers';

export const server = setupServer(...handlers);
