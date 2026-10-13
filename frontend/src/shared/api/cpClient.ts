/**
 * Control-plane client (task 027, design §13). Same-origin
 * `${apiBasePath}/cp` — catalog, packages, entitlement, RBAC, identity.
 * `apiBasePath` comes from `RuntimeConfig` (task 005/026), never a
 * `VITE_*` build-time var (`FE-D19`; enforced by the `no-restricted-syntax`
 * rule in `eslint.config.js`).
 */
import { createPlaneClient, type PlaneClient } from './planeClientFactory';

export function createCpClient(apiBasePath: string): PlaneClient {
  return createPlaneClient(`${apiBasePath}/cp`);
}
