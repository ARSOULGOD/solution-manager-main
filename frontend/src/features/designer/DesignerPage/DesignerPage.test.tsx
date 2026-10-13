import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import type { DraftValues, ObservedState, SolutionPackage } from '@contracts/view-model/types';
import { ApiError } from '@shared/api';

import { fireEvent, renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { ActionsRepository } from '../actionsRepository';
import type { ApplyRepository } from '../applyRepository';
import type { ConfigRepository, DesignerLoad } from '../configRepository';

import { DesignerPage } from './DesignerPage';

const OBSERVED_OK: ObservedState = {
  health: 'healthy',
  versionState: 'ok',
  configState: 'in_sync',
  declaredVersion: '2.3.0',
  observedVersion: '2.3.0',
};

const OBSERVED_DRIFT: ObservedState = {
  ...OBSERVED_OK,
  versionState: 'drift',
  observedVersion: '2.4.0',
};

function testPackage(): SolutionPackage {
  return {
    solution: 'widget-mesh',
    packageVersion: [6, 7],
    appliesTo: '>=1.0 <2.0',
    provenance: { author: 'acme', signature: 'test-fixture' },
    authoring: { reviewedRows: 1, totalRows: 1 },
    components: [
      {
        type: 'widget-connector',
        cardinality: 'exactly-one',
        configPath: 'widget-connector',
        loggerPrefix: 'com.acme.widget',
        fields: [
          {
            key: 'MODE',
            type: 'enum',
            options: ['push', 'pull'],
            default: 'pull',
            discriminator: true,
            required: true,
            label: 'Mode',
            ui: { section: 'General', order: 0 },
          },
          {
            key: 'POLL_INTERVAL',
            type: 'duration',
            default: '10s',
            when: { MODE: 'pull' },
            label: 'Poll interval',
            ui: { section: 'General', order: 0 },
          },
          {
            key: 'V7_ONLY',
            type: 'string',
            version: [7],
            default: 'v7-default',
            label: 'V7 only field',
            ui: { section: 'General', order: 0 },
          },
        ],
      },
    ],
  };
}

/** A package with a component scoped to a version this instance is not configured against. */
function emptyForVersionPackage(): SolutionPackage {
  const pkg = testPackage();
  return {
    ...pkg,
    components: pkg.components.map((component) => ({ ...component, version: [7] })),
  };
}

function load(overrides: Partial<DesignerLoad> = {}): DesignerLoad {
  return {
    stateVersion: 1,
    package: testPackage(),
    packageVersion: 7,
    values: {},
    observed: OBSERVED_OK,
    configGeneration: 'gen-1',
    ...overrides,
  };
}

function stubConfigRepository(overrides: Partial<ConfigRepository> = {}): ConfigRepository {
  return {
    loadDesigner: () => Promise.reject(new Error('not used in this test')),
    save: () => Promise.reject(new Error('not used in this test')),
    history: () => Promise.resolve([]),
    setSecret: () => Promise.reject(new Error('not used in this test')),
    acceptRuntime: () => Promise.reject(new Error('not used in this test')),
    restoreRuntime: () => Promise.reject(new Error('not used in this test')),
    ...overrides,
  };
}

function stubActionsRepository(): ActionsRepository {
  return { run: () => Promise.reject(new Error('not used in this test')) };
}

function stubApplyRepository(): ApplyRepository {
  return {
    stage: () => Promise.reject(new Error('not used in this test')),
    observe: () => Promise.reject(new Error('not used in this test')),
    rollback: () => Promise.reject(new Error('not used in this test')),
  };
}

function renderDesignerPage(configRepository: ConfigRepository, applyRepository: ApplyRepository = stubApplyRepository()) {
  return renderWithProviders(
    <DesignerPage
      instanceId="widget-mesh-dev-01"
      placementId="placement-0"
      configRepository={configRepository}
      applyRepository={applyRepository}
      actionsRepository={stubActionsRepository()}
    />,
  );
}

/** Two `one-of-many` components — file-mesh-shaped, so the Composer canvas is eligible. */
function multiComponentPackage(): SolutionPackage {
  return {
    solution: 'file-mesh',
    packageVersion: [1],
    appliesTo: '>=1.0 <2.0',
    provenance: { author: 'acme', signature: 'test-fixture' },
    authoring: { reviewedRows: 1, totalRows: 1 },
    components: [
      {
        type: 'COMP_SOURCE',
        cardinality: 'one-of-many',
        role: 'source',
        configPath: 'source',
        loggerPrefix: 'com.acme.source',
        fields: [{ key: 'REMOTE_PATH', type: 'string', required: true, label: 'Remote path' }],
      },
      {
        type: 'COMP_SINK',
        cardinality: 'one-of-many',
        role: 'sink',
        configPath: 'sink',
        loggerPrefix: 'com.acme.sink',
        fields: [{ key: 'TOPIC', type: 'string', required: true, label: 'Topic' }],
      },
    ],
  };
}

/**
 * Sections start collapsed (`useSectionJump`'s own default) — expand
 * before asserting field visibility. `SectionCard`'s own toggle carries
 * `aria-expanded`; `SectionNav`'s jump button (same visible text) does
 * not, which is what disambiguates the two.
 */
async function expandGeneralSection() {
  fireEvent.click(screen.getByRole('button', { name: /General/, expanded: false }));
  await waitFor(() => {
    expect(screen.getByRole('button', { name: /General/, expanded: true })).not.toBeNull();
  });
}

describe('DesignerPage — the five states', () => {
  it('renders a skeleton while the load is in flight', () => {
    renderDesignerPage(stubConfigRepository({ loadDesigner: () => new Promise(() => undefined) }));

    expect(document.querySelectorAll('[data-skeleton]').length).toBeGreaterThan(0);
  });

  it('renders an error panel with retry when the load fails', async () => {
    let attempt = 0;
    const configRepository = stubConfigRepository({
      loadDesigner: () => {
        attempt += 1;
        return attempt === 1
          ? Promise.reject(new ApiError({ status: 500, message: 'boom' }))
          : Promise.resolve(load());
      },
    });

    renderDesignerPage(configRepository);

    await waitFor(() => {
      expect(screen.getByText('boom')).not.toBeNull();
    });

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Save draft' })).not.toBeNull();
    });
  });

  it('states that the package declares nothing for this version, rather than rendering blank', async () => {
    renderDesignerPage(
      stubConfigRepository({
        loadDesigner: () => Promise.resolve(load({ package: emptyForVersionPackage(), packageVersion: 6 })),
      }),
    );

    await waitFor(() => {
      expect(screen.getByText('This package declares nothing for this version')).not.toBeNull();
    });
  });

  it('renders the form from the package alone — zero per-solution React', async () => {
    renderDesignerPage(stubConfigRepository({ loadDesigner: () => Promise.resolve(load()) }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /General/, expanded: false })).not.toBeNull();
    });
    await expandGeneralSection();

    expect(screen.getByLabelText('Mode')).not.toBeNull();
    expect(screen.getByLabelText('Poll interval', { exact: false })).not.toBeNull();
  });
});

describe('DesignerPage — version threading (FE-121/FE-122 wired end to end)', () => {
  it('renders no v7-only field for a v6 instance', async () => {
    renderDesignerPage(
      stubConfigRepository({ loadDesigner: () => Promise.resolve(load({ packageVersion: 6 })) }),
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /General/, expanded: false })).not.toBeNull();
    });
    await expandGeneralSection();

    expect(screen.getByLabelText('Mode')).not.toBeNull();
    expect(screen.queryByLabelText('V7 only field', { exact: false })).toBeNull();
  });

  it('renders the v7-only field for a v7 instance', async () => {
    renderDesignerPage(
      stubConfigRepository({ loadDesigner: () => Promise.resolve(load({ packageVersion: 7 })) }),
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /General/, expanded: false })).not.toBeNull();
    });
    await expandGeneralSection();

    expect(screen.getByLabelText('V7 only field', { exact: false })).not.toBeNull();
  });
});

describe('DesignerPage — draft behaviour', () => {
  it('a discriminator change reveals a dependent field with no network request', async () => {
    const loadDesigner = vi.fn(() => Promise.resolve(load()));
    const user = userEvent.setup();
    renderDesignerPage(stubConfigRepository({ loadDesigner }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /General/, expanded: false })).not.toBeNull();
    });
    await expandGeneralSection();

    expect(screen.getByLabelText('Mode')).not.toBeNull();
    expect(screen.getByLabelText('Poll interval', { exact: false })).not.toBeNull();
    expect(loadDesigner).toHaveBeenCalledTimes(1);

    await user.click(screen.getByLabelText('Mode'));
    await user.click(await screen.findByRole('option', { name: 'push' }));

    await waitFor(() => {
      expect(screen.queryByLabelText('Poll interval', { exact: false })).toBeNull();
    });
    // Purely client-side projection — the discriminator flip never refetches.
    expect(loadDesigner).toHaveBeenCalledTimes(1);
  });

  it('a saved value beats the package default', async () => {
    const values: DraftValues = { 'widget-connector': { MODE: 'pull', POLL_INTERVAL: '90s' } };
    renderDesignerPage(
      stubConfigRepository({ loadDesigner: () => Promise.resolve(load({ values })) }),
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /General/, expanded: false })).not.toBeNull();
    });
    await expandGeneralSection();

    expect(screen.getByLabelText('Poll interval', { exact: false })).toHaveValue('90s');
  });

  it('drift disables editing and hides the save bar', async () => {
    renderDesignerPage(
      stubConfigRepository({
        loadDesigner: () => Promise.resolve(load({ observed: OBSERVED_DRIFT })),
      }),
    );

    await waitFor(() => {
      expect(screen.getByText('Editing blocked · version drift')).not.toBeNull();
    });
    expect(screen.queryByRole('button', { name: 'Save draft' })).toBeNull();

    await expandGeneralSection();
    expect(screen.getByLabelText('Mode')).toHaveAttribute('aria-disabled', 'true');
  });
});

describe('DesignerPage — the composer branch (FE-124, one draft, two views)', () => {
  it('single-component solutions show no Composer/All fields toggle', async () => {
    renderDesignerPage(stubConfigRepository({ loadDesigner: () => Promise.resolve(load()) }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Save draft' })).not.toBeNull();
    });
    expect(screen.queryByRole('button', { name: 'Composer' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'All fields' })).toBeNull();
  });

  it('a multi-component solution renders the Composer canvas, not the flat section nav', async () => {
    renderDesignerPage(
      stubConfigRepository({
        loadDesigner: () =>
          Promise.resolve(load({ package: multiComponentPackage(), packageVersion: 1 })),
      }),
    );

    await waitFor(() => {
      expect(screen.getByRole('navigation', { name: 'Component types' })).not.toBeNull();
    });
    expect(screen.queryByRole('navigation', { name: 'Sections' })).toBeNull();
  });

  it('offers Apply for a multi-component package when the saved generation is valid', async () => {
    renderDesignerPage(
      stubConfigRepository({
        loadDesigner: () =>
          Promise.resolve(
            load({
              package: multiComponentPackage(),
              packageVersion: 1,
              values: {
                COMP_SOURCE: { REMOTE_PATH: '/inbound' },
                COMP_SINK: { TOPIC: 'events' },
              },
            }),
          ),
      }),
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Apply…' })).not.toBeNull();
    });
    expect(screen.getByRole('button', { name: 'Apply…' })).not.toBeDisabled();
  });

  it('switching between the canvas and "All fields" loses no edit — one draft, two views', async () => {
    const user = userEvent.setup();
    renderDesignerPage(
      stubConfigRepository({
        loadDesigner: () =>
          Promise.resolve(load({ package: multiComponentPackage(), packageVersion: 1 })),
      }),
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Source · COMP_SOURCE/ })).not.toBeNull();
    });
    await user.click(screen.getByRole('button', { name: /Source · COMP_SOURCE/ }));
    await user.type(screen.getByLabelText('Remote path', { exact: false }), '/inbound');

    await user.click(screen.getByRole('button', { name: 'All fields' }));

    expect(screen.getByLabelText('Remote path', { exact: false })).toHaveValue('/inbound');
  });
});

describe('DesignerPage — rollout and apply (FE-124; G11; open item 1)', () => {
  function stageResolvesIndeterminate(): ApplyRepository {
    return {
      ...stubApplyRepository(),
      stage: () =>
        Promise.resolve({
          kind: 'INDETERMINATE',
          reason: 'No generation ordering or idempotency key exists for dp-remote (open item 1).',
          observedAt: '2026-08-27T00:00:00Z',
          definite: false,
          replicas: [{ id: 'r0', generation: 'gen-1', restartObserved: true, health: 'GREEN' }],
        }),
    };
  }

  it('Apply is offered only once nothing is unsaved', async () => {
    // Draft matches the saved values exactly — dirtyCount is 0.
    const values: DraftValues = { 'widget-connector': { MODE: 'pull', POLL_INTERVAL: '10s' } };
    renderDesignerPage(stubConfigRepository({ loadDesigner: () => Promise.resolve(load({ values })) }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Apply…' })).not.toBeNull();
    });
    expect(screen.getByRole('button', { name: 'Apply…' })).not.toBeDisabled();

    await expandGeneralSection();
    fireEvent.change(screen.getByLabelText('Poll interval', { exact: false }), { target: { value: '20s' } });

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Apply…' })).toBeDisabled();
    });
  });

  it('restart is confirmed with a replica count, and applying renders APPLY_INDETERMINATE with no success verdict anywhere', async () => {
    const user = userEvent.setup();
    const values: DraftValues = { 'widget-connector': { MODE: 'pull', POLL_INTERVAL: '10s' } };
    renderDesignerPage(
      stubConfigRepository({ loadDesigner: () => Promise.resolve(load({ values })) }),
      stageResolvesIndeterminate(),
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Apply…' })).not.toBeNull();
    });
    await user.click(screen.getByRole('button', { name: 'Apply…' }));

    await waitFor(() => {
      expect(screen.getByText('Choose a restart strategy')).not.toBeNull();
    });
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    await waitFor(() => {
      expect(screen.getByText('Apply requires a restart')).not.toBeNull();
    });
    expect(screen.getByRole('button', { name: /Apply & restart 1 replica/ })).not.toBeNull();

    await user.click(screen.getByRole('button', { name: /Apply & restart 1 replica/ }));

    await waitFor(() => {
      expect(screen.getByText('Outcome not confirmed')).not.toBeNull();
    });
    expect(document.body.textContent).not.toMatch(/applied successfully|apply succeeded/i);
  });

  it('the diverged banner offers exactly its three remediations, and no "force healthy"', async () => {
    renderDesignerPage(
      stubConfigRepository({
        loadDesigner: () =>
          Promise.resolve(load({ observed: { ...OBSERVED_OK, configState: 'diverged' } })),
      }),
    );

    await waitFor(() => {
      expect(screen.getByText('Configuration diverged')).not.toBeNull();
    });
    expect(screen.getByRole('button', { name: 'Re-stage everywhere' })).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Restart the remaining' })).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Roll back to last uniform generation…' })).not.toBeNull();
    // Exactly three remediations — no fourth "force healthy" button. The
    // banner's own copy names that absence explicitly ('there is no "force
    // healthy"'), so this checks BUTTON count, not the word's absence from
    // the page.
    expect(
      screen.getAllByRole('button').filter((button) => /force healthy/i.test(button.textContent ?? '')),
    ).toHaveLength(0);
  });
});
