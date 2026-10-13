import { HttpResponse, http } from 'msw';
import { describe, expect, it } from 'vitest';

import { createCpClient } from '@shared/api';
import { server } from '@shared/testdoubles/server';

import { createConnectionPickerRepository } from './connectionPickerRepository';

const cpClient = createCpClient('/api');

describe('createConnectionPickerRepository', () => {
  it('returns only active remote-server connections', async () => {
    server.use(
      http.get('*/cp/connections', () =>
        HttpResponse.json({
          items: [
            {
              connectionId: 'remote-1',
              name: 'build-host',
              connectionType: 'remote-server',
              attributes: {
                host: 'build.example.test',
                allowedConfigRoot: '/opt/sm',
                allowedContainerPrefix: 'sm-',
              },
              active: true,
            },
            {
              connectionId: 'broker-1',
              name: 'broker',
              connectionType: 'solace-broker',
              attributes: {},
              active: true,
            },
            {
              connectionId: 'remote-2',
              name: 'retired-host',
              connectionType: 'remote-server',
              attributes: {},
              active: false,
            },
          ],
        }),
      ),
    );

    await expect(createConnectionPickerRepository(cpClient).listRemoteServers()).resolves.toEqual([
      expect.objectContaining({ connectionId: 'remote-1', name: 'build-host' }),
    ]);
  });
});
