/**
 * Data-plane client (task 027, design §13). Same-origin
 * `${apiBasePath}/dp` — configuration-of-record, apply, health, metrics
 * query, dashboards. Same-origin-only guarantee as `cpClient` — the SPA
 * never sends a second DP audience URL (`FE-D19`).
 */
import { createPlaneClient, type PlaneClient } from './planeClientFactory';

export function createDpClient(apiBasePath: string): PlaneClient {
  return createPlaneClient(`${apiBasePath}/dp`);
}
