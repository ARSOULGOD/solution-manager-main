/**
 * Console client (task 027, `dynamic-metrics` §5.2). Same-origin
 * `${apiBasePath}/console/v1` — the Dynamic Metrics query surface
 * (`capabilities`, `entity-types`, `metrics/catalog`, `labels/{k}/values`,
 * `query/range`, `query/instant`, `export/range`; §9.2's SPECIFIED table).
 *
 * ⚠️ **Not an architectural decision.** Whether `console` is genuinely a
 * third plane, a DP sub-route, or something the composed-read routes in
 * `dynamic-metrics` §5.2 fold back into CP/DP, is an **open question
 * upstream** — this module does not answer it. It exists so the Dynamic
 * Metrics endpoints (which are specified to the endpoint level today) have
 * somewhere typed to live without pretending the three-client split below
 * them has been ratified. If that question resolves to "console is just
 * DP", this file collapses into `dpClient.ts` with a prefix change — no
 * feature-facing repository interface changes, because those only ever see
 * a `PlaneClient`.
 */
import { createPlaneClient, type PlaneClient } from './planeClientFactory';

export function createConsoleClient(apiBasePath: string): PlaneClient {
  return createPlaneClient(`${apiBasePath}/console/v1`);
}
