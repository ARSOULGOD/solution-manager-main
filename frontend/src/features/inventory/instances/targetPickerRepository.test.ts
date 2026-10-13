import { HttpResponse, http } from 'msw';
import { describe, expect, it } from 'vitest';

import { createCpClient } from '@shared/api';
import { server } from '@shared/testdoubles/server';

import { createTargetsRepository } from './targetPickerRepository';

const cpClient = createCpClient('/api');

describe('createTargetsRepository (console.yaml listTargets)', () => {
  it('unwraps the TargetList envelope into TargetRow[], carrying only Target fields', async () => {
    server.use(
      http.get('*/cp/targets', () =>
        HttpResponse.json({
          items: [
            {
              targetId: 'f47e2f4e-0000-4000-8000-000000000001',
              name: 'tgt-ledger-k8s',
              kind: 'docker',
              transport: 'ssh-docker',
              ownershipScope: { organizationId: 'org-1', environmentId: 'env-1' },
              allowedConfigRoot: '/opt/sm/config',
              allowedContainerPrefix: 'sm-',
            },
          ],
        }),
      ),
    );

    const repo = createTargetsRepository(cpClient);
    const targets = await repo.list();

    expect(targets).toEqual([
      {
        targetId: 'f47e2f4e-0000-4000-8000-000000000001',
        name: 'tgt-ledger-k8s',
        kind: 'docker',
        transport: 'ssh-docker',
        ownershipScope: { organizationId: 'org-1', environmentId: 'env-1' },
        allowedConfigRoot: '/opt/sm/config',
        allowedContainerPrefix: 'sm-',
      },
    ]);
  });

  it('returns an empty list when the envelope carries no items', async () => {
    server.use(http.get('*/cp/targets', () => HttpResponse.json({ items: [] })));

    const repo = createTargetsRepository(cpClient);

    expect(await repo.list()).toEqual([]);
  });

  it('exposes linked-target creation without accepting credentials', async () => {
    const repo = createTargetsRepository(cpClient);

    server.use(http.post('*/cp/targets', async ({ request }) => {
      const body = await request.json();
      expect(body).toMatchObject({
        name: 'file-connector', kind: 'systemd', runtime: 'systemd', connectionId: 'remote-1',
        configRoot: '/etc/file-connector', serviceNamePattern: 'file-connector-', transport: 'ssh',
      });
      return HttpResponse.json({ targetId: 'target-1' }, { status: 201 });
    }));

    await expect(repo.registerLinked({
      name: 'file-connector', kind: 'systemd', connectionId: 'remote-1',
      configRoot: '/etc/file-connector', serviceNamePattern: 'file-connector-', ownershipScope: {},
    })).resolves.toEqual({ targetId: 'target-1' });
    expect('privateKey' in repo).toBe(false);
  });

  it('updates linked target metadata without sending connection secrets', async () => {
    const repo = createTargetsRepository(cpClient);
    server.use(http.patch('*/cp/targets/target-1', async ({ request }) => {
      expect(await request.json()).toEqual({
        name: 'file-connector-renamed',
        connectionId: 'remote-1',
        runtime: 'docker',
        configRoot: '/opt/file-connector',
        containerPrefix: 'fc-',
      });
      return HttpResponse.json({ targetId: 'target-1' });
    }));

    await expect(repo.updateLinked('target-1', {
      name: 'file-connector-renamed', connectionId: 'remote-1', kind: 'docker',
      configRoot: '/opt/file-connector', containerPrefix: 'fc-',
    })).resolves.toEqual({ targetId: 'target-1' });
  });

  it('retires a target through the safe delete endpoint', async () => {
    const repo = createTargetsRepository(cpClient);
    server.use(http.post('*/cp/targets/target-1/retire', () => HttpResponse.json({ targetId: 'target-1' })));

    await expect(repo.retire('target-1')).resolves.toEqual({ targetId: 'target-1' });
  });
});
