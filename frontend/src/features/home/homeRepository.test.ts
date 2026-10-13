import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';

import { createCpClient, createDpClient } from '@shared/api';

import { createHomeRepository } from './homeRepository';

// Task 0 finding (2026-10-08, re-checked against contracts/openapi/console.yaml
// against the merged current contract): `HomeSummary.solutions[]` carries
// `solutionId`, `name`, `instanceCount`, `byHealth`, `stateVersion` — no
// top-level `configStagedNotApplied` exists anywhere on the wire (D-2 is
// still open: it renders null/"—"). `HomeAttention.items[]` carries
// `instanceId`, optional `placementId`, `reason`, optional `state`,
// `stateVersion` — no `instanceName`/`solutionId` (D-1 option A is NOT
// taken; the frontend would have to resolve them via `getInstance`,
// option B, to build a correct instance-scoped link).
const server = setupServer();

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});
afterEach(() => {
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});

const cpClient = createCpClient('/api');
const dpClient = createDpClient('/api');

describe('createHomeRepository — real contract envelope', () => {
  it('maps HomeSummary.solutions[] into the derived counters, never trusting an invented field', async () => {
    server.use(
      http.get('*/cp/home/summary', () =>
        HttpResponse.json({
          solutions: [
            {
              solutionId: 'sol-1',
              name: 'Cache Engine',
              instanceCount: 10,
              byHealth: { GREEN: 7, RED: 2, unreachable: 1 },
              stateVersion: 1,
            },
            {
              solutionId: 'sol-2',
              name: 'File Mesh',
              instanceCount: 2,
              byHealth: { GREEN: 2 },
              stateVersion: 1,
            },
          ],
        }),
      ),
    );

    const repo = createHomeRepository(cpClient, dpClient);
    const summary = await repo.summary();

    expect(summary.solutionsManaged).toBe(2);
    expect(summary.instancesDeclared).toBe(12);
    // err-tone states only: RED (2) + unreachable (1) = 3.
    expect(summary.needAttentionNow).toBe(3);
    // D-2: no wire field carries this — never fabricated.
    expect(summary.configStagedNotApplied).toBeNull();
    expect(summary.partial).toBeUndefined();
  });

  it('treats a solution with no byHealth key as contributing zero, not an error', async () => {
    server.use(
      http.get('*/cp/home/summary', () =>
        HttpResponse.json({
          solutions: [
            { solutionId: 'sol-1', name: 'Cache Engine', instanceCount: 0, stateVersion: 1 },
          ],
        }),
      ),
    );

    const repo = createHomeRepository(cpClient, dpClient);
    const summary = await repo.summary();

    expect(summary.instancesDeclared).toBe(0);
    expect(summary.needAttentionNow).toBe(0);
  });

  it('treats an empty solutions array as solvable zero counts, not first-run-by-exception', async () => {
    server.use(
      http.get('*/cp/home/summary', () => HttpResponse.json({ solutions: [] })),
    );

    const repo = createHomeRepository(cpClient, dpClient);
    const summary = await repo.summary();

    expect(summary.solutionsManaged).toBe(0);
    expect(summary.instancesDeclared).toBe(0);
    expect(summary.needAttentionNow).toBe(0);
  });

  it('maps HomeAttention.items[] into the view model, resolving a link via getInstance (D-1 option B)', async () => {
    server.use(
      http.get('*/cp/home/attention', () =>
        HttpResponse.json({
          items: [
            { instanceId: 'inst-1', reason: 'health', state: 'RED', stateVersion: 3 },
            { instanceId: 'inst-2', reason: 'config-diverged', stateVersion: 1 },
          ],
        }),
      ),
      http.get('*/dp/instances/:instanceId', ({ params }) => {
        if (params['instanceId'] === 'inst-1') {
          return HttpResponse.json({ instanceId: 'inst-1', name: 'cache-stage-01', solutionId: 'sol-1' });
        }
        return HttpResponse.json({ instanceId: 'inst-2', name: 'cache-qa-01', solutionId: 'sol-1' });
      }),
    );

    const repo = createHomeRepository(cpClient, dpClient);
    const items = await repo.attention();

    expect(items).toHaveLength(2);
    expect(items[0]?.severity).toBe('err');
    expect(items[0]?.title).toContain('cache-stage-01');
    expect(items[0]?.href).toBe('/solutions/sol-1/instances/inst-1/governance');
    expect(items[1]?.severity).toBe('err');
    expect(items[1]?.title).toContain('cache-qa-01');
    expect(items[1]?.href).toBe('/solutions/sol-1/instances/inst-2/governance');
  });

  it('degrades to a non-navigable row — never a wrong or fabricated link — when the instance lookup fails', async () => {
    server.use(
      http.get('*/cp/home/attention', () =>
        HttpResponse.json({
          items: [{ instanceId: 'inst-missing', reason: 'apply-indeterminate', stateVersion: 1 }],
        }),
      ),
      http.get('*/dp/instances/:instanceId', () => new HttpResponse(null, { status: 404 })),
    );

    const repo = createHomeRepository(cpClient, dpClient);
    const items = await repo.attention();

    expect(items).toHaveLength(1);
    expect(items[0]?.href).toBeUndefined();
    expect(items[0]?.title).toContain('inst-missing');
  });

  it('maps every reason to a severity without inventing an icon field the wire does not carry', async () => {
    server.use(
      http.get('*/cp/home/attention', () =>
        HttpResponse.json({
          items: [
            { instanceId: 'a', reason: 'health', state: 'unreachable', stateVersion: 1 },
            { instanceId: 'b', reason: 'health', state: 'INACTIVE_REPLICA', stateVersion: 1 },
            { instanceId: 'c', reason: 'apply-indeterminate', stateVersion: 1 },
          ],
        }),
      ),
      http.get('*/dp/instances/:instanceId', () => new HttpResponse(null, { status: 404 })),
    );

    const repo = createHomeRepository(cpClient, dpClient);
    const items = await repo.attention();

    expect(items[0]?.severity).toBe('warn'); // unreachable — NOTFOUND table, STATE-VOCABULARY §2
    expect(items[1]?.severity).toBe('idle'); // INACTIVE_REPLICA — GOV_HEALTH table
    expect(items[2]?.severity).toBe('warn'); // apply-indeterminate — no state to read, treated as warn
  });
});
