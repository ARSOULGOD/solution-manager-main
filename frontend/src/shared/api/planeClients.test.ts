import { HttpResponse, http } from 'msw';
import { describe, expect, it } from 'vitest';

import { server } from '@shared/testdoubles/server';

import { createConsoleClient } from './consoleClient';
import { createCpClient } from './cpClient';
import { createDpClient } from './dpClient';

describe('plane clients', () => {
  it('cpClient prefixes with /cp', async () => {
    let seenUrl = '';
    server.use(
      http.get('*/api/cp/packages', ({ request: req }) => {
        seenUrl = new URL(req.url).pathname;
        return HttpResponse.json({ ok: true });
      }),
    );

    await createCpClient('/api').get('/packages');

    expect(seenUrl).toBe('/api/cp/packages');
  });

  it('dpClient prefixes with /dp', async () => {
    let seenUrl = '';
    server.use(
      http.get('*/api/dp/health', ({ request: req }) => {
        seenUrl = new URL(req.url).pathname;
        return HttpResponse.json({ ok: true });
      }),
    );

    await createDpClient('/api').get('/health');

    expect(seenUrl).toBe('/api/dp/health');
  });

  it('consoleClient prefixes with /console/v1', async () => {
    let seenUrl = '';
    server.use(
      http.get('*/api/console/v1/capabilities', ({ request: req }) => {
        seenUrl = new URL(req.url).pathname;
        return HttpResponse.json({ ok: true });
      }),
    );

    await createConsoleClient('/api').get('/capabilities');

    expect(seenUrl).toBe('/api/console/v1/capabilities');
  });

  it('joins a path without a leading slash the same way', async () => {
    let seenUrl = '';
    server.use(
      http.get('*/api/cp/packages', ({ request: req }) => {
        seenUrl = new URL(req.url).pathname;
        return HttpResponse.json({ ok: true });
      }),
    );

    await createCpClient('/api').get('packages');

    expect(seenUrl).toBe('/api/cp/packages');
  });

  it.each([
    ['cp', createCpClient],
    ['dp', createDpClient],
    ['console', createConsoleClient],
  ] as const)('%s client rejects an absolute URL', (_name, factory) => {
    const client = factory('/api');
    expect(() => client.get('https://evil.example.test/steal')).toThrow(/absolute URL/);
  });

  it.each([
    ['cp', createCpClient],
    ['dp', createDpClient],
    ['console', createConsoleClient],
  ] as const)('%s client rejects a protocol-relative URL', (_name, factory) => {
    const client = factory('/api');
    expect(() => client.get('//evil.example.test/steal')).toThrow(/absolute URL/);
  });

  it('resolve() builds a same-origin URL without issuing a request', () => {
    expect(createConsoleClient('/api').resolve('/export/range?metric=x')).toBe(
      '/api/console/v1/export/range?metric=x',
    );
  });

  it('resolve() rejects an absolute URL the same as every other method', () => {
    expect(() => createCpClient('/api').resolve('https://evil.example.test')).toThrow(/absolute URL/);
  });

  it('post/put/patch/delete all prefix correctly', async () => {
    const seen: string[] = [];
    server.use(
      http.post('*/api/dp/instances', ({ request: req }) => {
        seen.push(`POST ${new URL(req.url).pathname}`);
        return HttpResponse.json({ ok: true });
      }),
      http.put('*/api/dp/instances/1', ({ request: req }) => {
        seen.push(`PUT ${new URL(req.url).pathname}`);
        return HttpResponse.json({ ok: true });
      }),
      http.patch('*/api/dp/instances/1', ({ request: req }) => {
        seen.push(`PATCH ${new URL(req.url).pathname}`);
        return HttpResponse.json({ ok: true });
      }),
      http.delete('*/api/dp/instances/1', ({ request: req }) => {
        seen.push(`DELETE ${new URL(req.url).pathname}`);
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const client = createDpClient('/api');
    await client.post('/instances', { name: 'x' });
    await client.put('/instances/1', { name: 'y' });
    await client.patch('/instances/1', { name: 'z' });
    await client.delete('/instances/1');

    expect(seen).toEqual([
      'POST /api/dp/instances',
      'PUT /api/dp/instances/1',
      'PATCH /api/dp/instances/1',
      'DELETE /api/dp/instances/1',
    ]);
  });

  it('postWithCorrelation() prefixes correctly and resolves the server correlation id', async () => {
    server.use(
      http.post('*/api/cp/solutions/load', () =>
        HttpResponse.json({ verified: true }, { headers: { 'X-Correlation-Id': 'corr-load-1' } }),
      ),
    );

    const result = await createCpClient('/api').postWithCorrelation<{ verified: boolean }>(
      '/solutions/load',
      { fileName: 'x.sompkg' },
    );

    expect(result).toEqual({ data: { verified: true }, correlationId: 'corr-load-1' });
  });
});
