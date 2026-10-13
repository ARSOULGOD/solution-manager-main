import { HttpResponse, http } from 'msw';
import { describe, expect, it, vi } from 'vitest';

import { ApiError, createCpClient } from '@shared/api';
import { server } from '@shared/testdoubles/server';

import { createConnectionsRepository } from './connectionsRepository';

const cpClient = createCpClient('/api');

const ROW = {
  connectionId: 'f47e2f4e-0000-4000-8000-000000000001',
  name: 'prod-broker-01',
  connectionType: 'solace-broker' as const,
  ownershipScope: { environmentId: 'env-1' },
  attributes: { host: 'tcps://broker.acme.internal', port: 55443, password: { set: true } },
  active: true,
  usedByCount: 3,
};

describe('createConnectionsRepository (console.yaml connections tag)', () => {
  it('unwraps the ConnectionList envelope into ConnectionRow[]', async () => {
    server.use(http.get('*/cp/connections', () => HttpResponse.json({ items: [ROW] })));

    const repo = createConnectionsRepository(cpClient);
    expect(await repo.list()).toEqual([ROW]);
  });

  it('sends no query string and no scope at all — listConnections takes no parameters (I5)', async () => {
    let capturedUrl = '';
    server.use(
      http.get('*/cp/connections', ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json({ items: [] });
      }),
    );

    await createConnectionsRepository(cpClient).list();

    expect(capturedUrl.endsWith('/cp/connections')).toBe(true);
    expect(capturedUrl).not.toContain('?');
  });

  it('creates a connection with testBeforeSave and the default (organization) ownershipScope', async () => {
    let capturedBody: unknown;
    server.use(
      http.post('*/cp/connections', async ({ request }) => {
        capturedBody = await request.json();
        return HttpResponse.json(ROW, { status: 201 });
      }),
    );

    const created = await createConnectionsRepository(cpClient).create({
      name: 'dev-broker',
      connectionType: 'solace-broker',
      attributes: { host: 'tcps://dev.example', port: 55555 },
      testBeforeSave: true,
    });

    expect(created).toEqual(ROW);
    expect(capturedBody).toEqual({
      name: 'dev-broker',
      connectionType: 'solace-broker',
      ownershipScope: {},
      attributes: { host: 'tcps://dev.example', port: 55555 },
      testBeforeSave: true,
    });
  });

  it('updates a connection via PATCH, sending only name/attributes/testBeforeSave (no connectionType, no id)', async () => {
    let capturedMethod = '';
    let capturedBody: unknown;
    server.use(
      http.patch('*/cp/connections/:id', async ({ request }) => {
        capturedMethod = request.method;
        capturedBody = await request.json();
        return HttpResponse.json({ ...ROW, name: 'renamed' });
      }),
    );

    const updated = await createConnectionsRepository(cpClient).update(ROW.connectionId, {
      name: 'renamed',
      connectionType: 'solace-broker', // must be dropped, not sent
      attributes: { host: 'tcps://dev.example', port: 55443 },
      testBeforeSave: false,
    });

    expect(capturedMethod).toBe('PATCH');
    expect(capturedBody).toEqual({
      name: 'renamed',
      attributes: { host: 'tcps://dev.example', port: 55443 },
      testBeforeSave: false,
    });
    expect(updated.name).toBe('renamed');
  });

  it('surfaces a 422 CONNECTION_TEST_FAILED as a typed ApiError carrying the echo', async () => {
    server.use(
      http.post('*/cp/connections', () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Validation failed',
            status: 422,
            code: 'CONNECTION_TEST_FAILED',
            correlationId: '11111111-1111-1111-1111-111111111111',
            detail: 'Connection refused by peer.',
            name: 'dev-broker',
            attributes: { host: 'tcps://dev.example' },
          },
          { status: 422 },
        ),
      ),
    );

    const repo = createConnectionsRepository(cpClient);
    await expect(
      repo.create({
        name: 'dev-broker',
        connectionType: 'solace-broker',
        attributes: { host: 'tcps://dev.example' },
        testBeforeSave: true,
      }),
    ).rejects.toMatchObject({
      status: 422,
      code: 'CONNECTION_TEST_FAILED',
      message: 'Connection refused by peer.',
    });
  });

  it('surfaces a 429 RATE_LIMITED with Retry-After seconds', async () => {
    server.use(
      http.post('*/cp/connections', () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Rate limited',
            status: 429,
            code: 'RATE_LIMITED',
            correlationId: '11111111-1111-1111-1111-111111111111',
          },
          { status: 429, headers: { 'Retry-After': '30' } },
        ),
      ),
    );

    const repo = createConnectionsRepository(cpClient);
    const error = await repo
      .create({
        name: 'dev-broker',
        connectionType: 'solace-broker',
        attributes: {},
        testBeforeSave: true,
      })
      .catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(429);
    expect((error as ApiError).retryAfterSeconds).toBe(30);
  });

  it('surfaces a 503 SERVICE_UNAVAILABLE when the test probe cannot reach the peer', async () => {
    server.use(
      http.post('*/cp/connections', () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Service unavailable',
            status: 503,
            code: 'SERVICE_UNAVAILABLE',
            correlationId: '11111111-1111-1111-1111-111111111111',
          },
          { status: 503 },
        ),
      ),
    );

    const repo = createConnectionsRepository(cpClient);
    await expect(
      repo.create({
        name: 'dev-broker',
        connectionType: 'solace-broker',
        attributes: {},
        testBeforeSave: true,
      }),
    ).rejects.toMatchObject({ status: 503, code: 'SERVICE_UNAVAILABLE' });
  });

  it('deactivates one-directionally via POST, with no body and no reactivate counterpart', async () => {
    let capturedMethod = '';
    let capturedBody = '';
    server.use(
      http.post('*/cp/connections/:id/deactivate', async ({ request }) => {
        capturedMethod = request.method;
        capturedBody = await request.text();
        return HttpResponse.json({ ...ROW, active: false });
      }),
    );

    const updated = await createConnectionsRepository(cpClient).deactivate(ROW.connectionId);

    expect(capturedMethod).toBe('POST');
    expect(capturedBody).toBe('');
    expect(updated.active).toBe(false);
  });

  it('refuses to delete a referenced connection before any request is made', async () => {
    const getSpy = vi.fn();
    server.use(http.delete('*/cp/connections/:id', getSpy));
    const repo = createConnectionsRepository(cpClient);

    await expect(repo.remove('conn-7', 3)).rejects.toThrow(/referenced by 3/);
    expect(getSpy).not.toHaveBeenCalled();
  });

  it('deletes when nothing references the connection', async () => {
    server.use(http.delete('*/cp/connections/:id', () => new HttpResponse(null, { status: 204 })));
    await expect(createConnectionsRepository(cpClient).remove('conn-9', 0)).resolves.toBeUndefined();
  });

  it('fetches connection types from getConnectionTypes', async () => {
    server.use(
      http.get('*/cp/connection-types', () =>
        HttpResponse.json([
          { connectionType: 'solace-broker', label: 'Solace broker', descriptorVersion: 1, attributes: [] },
          { connectionType: 'remote-server', label: 'Remote server', descriptorVersion: 1, attributes: [] },
        ]),
      ),
    );

    const descriptors = await createConnectionsRepository(cpClient).getConnectionTypes();
    expect(descriptors.map((descriptor) => descriptor.connectionType)).toEqual([
      'solace-broker',
      'remote-server',
    ]);
  });
});
