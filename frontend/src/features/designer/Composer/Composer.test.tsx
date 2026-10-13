import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type {
  ComponentSelection,
  DraftValues,
  FieldValue,
  ObservedState,
  SolutionPackage,
} from '@contracts/view-model/types';
import { reflowFloorPx } from '@shared/theme/breakpoints';

import { setViewportWidth } from '../../../test/matchMedia';
import { assertReflow } from '../../../test/reflow';
import { renderWithProviders, screen, within } from '../../../test/renderWithProviders';

import { Composer } from './Composer';

const OBSERVED: ObservedState = { health: 'healthy', versionState: 'ok', configState: 'in_sync' };

function fileMeshPackage(): SolutionPackage {
  return {
    solution: 'file-mesh',
    packageVersion: [1],
    appliesTo: '>=1.0 <2.0',
    provenance: { author: 'solace', signature: 'test-fixture' },
    authoring: { reviewedRows: 4, totalRows: 4 },
    components: [
      {
        type: 'COMP_SOURCE_FTP',
        cardinality: 'one-of-many',
        role: 'source',
        configPath: 'source',
        loggerPrefix: 'com.solace.solutions.filemesh.source',
        fields: [
          { key: 'ENABLED', type: 'bool', default: true },
          { key: 'REMOTE_PATH', type: 'string', required: true },
        ],
      },
      {
        type: 'COMP_SINK_SOLACE',
        cardinality: 'one-of-many',
        role: 'sink',
        configPath: 'sink',
        loggerPrefix: 'com.solace.solutions.filemesh.sink',
        fields: [
          { key: 'ENABLED', type: 'bool', default: true },
          { key: 'TOPIC', type: 'string', required: true },
        ],
      },
    ],
  };
}

const SELECTION: ComponentSelection = [
  { type: 'COMP_SOURCE_FTP', instanceId: 'src', role: 'source' },
  { type: 'COMP_SINK_SOLACE', instanceId: 'snk', role: 'sink' },
];

const CACHE_SELECTION: ComponentSelection = [{ type: 'cache-engine', instanceId: 'cache-0' }];

// A package with THREE declared component types (two source, one sink) so a
// role-bound selection can be a strict subset of `pkg.components` while
// still satisfying `isComposerEligible` (length > 1) — proving the palette
// follows the SELECTION, not the whole package.
function threeComponentPackage(): SolutionPackage {
  const pkg = fileMeshPackage();
  return {
    ...pkg,
    components: [
      ...pkg.components,
      {
        type: 'COMP_SOURCE_SFTP',
        cardinality: 'one-of-many',
        role: 'source',
        configPath: 'source-sftp',
        loggerPrefix: 'com.solace.solutions.filemesh.source-sftp',
        fields: [{ key: 'ENABLED', type: 'bool', default: true }],
      },
    ],
  };
}

const SOURCE_ONLY_SELECTION: ComponentSelection = [
  { type: 'COMP_SOURCE_FTP', instanceId: 'src', role: 'source' },
  { type: 'COMP_SOURCE_SFTP', instanceId: 'src-sftp', role: 'source' },
];

function draftValuesOf(overrides: DraftValues = {}): DraftValues {
  return {
    src: { REMOTE_PATH: '/inbound' },
    snk: {},
    ...overrides,
  };
}

interface HarnessOptions {
  componentSelection?: ComponentSelection;
  pkg?: SolutionPackage;
  draftValues?: DraftValues;
  onFieldChange?: (instanceId: string, fieldKey: string, value: FieldValue) => void;
}

function renderComposer({
  componentSelection = SELECTION,
  pkg = fileMeshPackage(),
  draftValues = draftValuesOf(),
  onFieldChange = vi.fn(),
}: HarnessOptions = {}) {
  const getInstanceValues = (instanceId: string) => draftValues[instanceId] ?? {};
  const rendered = renderWithProviders(
    <Composer
      pkg={pkg}
      componentSelection={componentSelection}
      packageVersion={1}
      getInstanceValues={getInstanceValues}
      observedState={OBSERVED}
      onFieldChange={onFieldChange}
    />,
  );
  return { ...rendered, onFieldChange };
}

describe('Composer', () => {
  it('renders nothing for a single-component selection (the package-capability gate)', () => {
    const { container } = renderWithProviders(
      <Composer
        pkg={fileMeshPackage()}
        componentSelection={CACHE_SELECTION}
        packageVersion={1}
        getInstanceValues={() => ({})}
        observedState={OBSERVED}
        onFieldChange={() => {}}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("derives the palette from the package's declared component types", () => {
    renderComposer();
    const palette = screen.getByRole('navigation', { name: 'Component types' });
    expect(within(palette).getByText('COMP_SOURCE_FTP')).toBeInTheDocument();
    expect(within(palette).getByText('COMP_SINK_SOLACE')).toBeInTheDocument();
  });

  it('scopes the "Component types" palette to the current role selection, never the whole package', () => {
    renderComposer({
      pkg: threeComponentPackage(),
      componentSelection: SOURCE_ONLY_SELECTION,
      draftValues: { src: { REMOTE_PATH: '/inbound' }, 'src-sftp': {} },
    });
    const palette = screen.getByRole('navigation', { name: 'Component types' });
    expect(within(palette).getByText('COMP_SOURCE_FTP')).toBeInTheDocument();
    expect(within(palette).getByText('COMP_SOURCE_SFTP')).toBeInTheDocument();
    expect(within(palette).queryByText('COMP_SINK_SOLACE')).not.toBeInTheDocument();
  });

  it('renders one canvas node per composed instance, labelled with its own field/required counts', () => {
    renderComposer();
    // Source: 2 fields, REMOTE_PATH required and empty in the raw package
    // default (draft supplies '/inbound', so 0 required-empty here).
    expect(
      screen.getByRole('button', { name: 'Source · COMP_SOURCE_FTP · 2 fields · ok' }),
    ).toBeInTheDocument();
    // Sink: 2 fields, TOPIC required and never supplied by the draft.
    expect(
      screen.getByRole('button', {
        name: 'Sink · COMP_SINK_SOLACE · 2 fields · 1 required field empty',
      }),
    ).toBeInTheDocument();
  });

  it('hides the inspector entirely until a node or palette type is selected', () => {
    renderComposer();
    expect(screen.queryByLabelText('REMOTE_PATH')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('TOPIC', { exact: false })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Close' })).not.toBeInTheDocument();
  });

  it('closes the inspector via its close button, giving the canvas the full width back', () => {
    renderComposer();
    fireEvent.click(
      screen.getByRole('button', { name: 'Source · COMP_SOURCE_FTP · 2 fields · ok' }),
    );
    expect(screen.getByLabelText('REMOTE_PATH')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Close' }));

    expect(screen.queryByLabelText('REMOTE_PATH')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Close' })).not.toBeInTheDocument();
  });

  it('selects a node on click and scopes the inspector to that component only', () => {
    renderComposer();
    fireEvent.click(
      screen.getByRole('button', { name: 'Source · COMP_SOURCE_FTP · 2 fields · ok' }),
    );
    expect(screen.getByLabelText('REMOTE_PATH')).toBeInTheDocument();
    expect(screen.queryByLabelText('TOPIC')).not.toBeInTheDocument();

    fireEvent.click(
      screen.getByRole('button', {
        name: 'Sink · COMP_SINK_SOLACE · 2 fields · 1 required field empty',
      }),
    );

    // TOPIC's own label carries a required asterisk (it's empty and
    // required, unlike REMOTE_PATH above, which the draft already fills)
    // — { exact: false } matches the label regardless of that suffix.
    expect(screen.getByLabelText('TOPIC', { exact: false })).toBeInTheDocument();
    expect(screen.queryByLabelText('REMOTE_PATH')).not.toBeInTheDocument();
  });

  it('selects a component type from the palette and opens the inspector for it', () => {
    renderComposer();
    const palette = screen.getByRole('navigation', { name: 'Component types' });

    fireEvent.click(within(palette).getByText('COMP_SINK_SOLACE'));

    expect(screen.getByLabelText('TOPIC', { exact: false })).toBeInTheDocument();
    expect(screen.queryByLabelText('REMOTE_PATH')).not.toBeInTheDocument();
  });

  it('never calls onFieldChange from a pointer drag — a node move is presentation only', () => {
    const { onFieldChange } = renderComposer();
    const node = screen.getByRole('button', { name: 'Source · COMP_SOURCE_FTP · 2 fields · ok' });
    const handle = within(node).getByTitle('Drag to reposition, or focus and use the arrow keys.');

    fireEvent.pointerDown(handle, { clientX: 0, clientY: 0 });
    fireEvent.pointerMove(window, { clientX: 40, clientY: 10 });
    fireEvent.pointerUp(window);

    expect(onFieldChange).not.toHaveBeenCalled();
  });

  it('never calls onFieldChange from a keyboard nudge — the same presentation-only rule applies from the keyboard', () => {
    const { onFieldChange } = renderComposer();
    const node = screen.getByRole('button', { name: 'Source · COMP_SOURCE_FTP · 2 fields · ok' });
    node.focus();

    fireEvent.keyDown(node, { key: 'ArrowRight' });
    fireEvent.keyDown(node, { key: 'ArrowDown' });

    expect(onFieldChange).not.toHaveBeenCalled();
  });

  it('edits a field through the inspector, routed to the selected instance id', () => {
    const { onFieldChange } = renderComposer();
    fireEvent.click(
      screen.getByRole('button', { name: 'Source · COMP_SOURCE_FTP · 2 fields · ok' }),
    );
    fireEvent.change(screen.getByLabelText('REMOTE_PATH'), { target: { value: '/outbound' } });
    expect(onFieldChange).toHaveBeenCalledWith('src', 'REMOTE_PATH', '/outbound');
  });

  it('the "All fields" toggle renders every composed component as the non-pointer path, with no node selection required', () => {
    renderComposer();
    fireEvent.click(screen.getByRole('button', { name: 'All fields' }));

    expect(screen.getByLabelText('REMOTE_PATH')).toBeInTheDocument();
    expect(screen.getByLabelText('TOPIC', { exact: false })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: /COMP_SOURCE_FTP/ })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: /COMP_SINK_SOLACE/ })).toBeInTheDocument();
  });

  // `fireEvent.pointerDown`/`pointerMove` can't carry `clientX` under
  // jsdom — it has no global `PointerEvent`, so testing-library's
  // `createEvent` falls back to a plain `Event` whose init dict drops
  // pointer/mouse coordinates (`ComposerNode.test.tsx`'s own drag test
  // hits the same gap and, for the same reason, never asserts a
  // resulting coordinate either). The keyboard path exercises the exact
  // same `onResize`/clamp logic a pointer drag calls, with no such gap,
  // so it is what verifies the arithmetic here.
  it('resizes the "Component types" palette from the keyboard, arrow-right growing it', () => {
    renderComposer();
    const handle = screen.getByRole('separator', { name: 'Resize component types panel' });
    expect(handle).toHaveAttribute('aria-valuenow', '210');
    handle.focus();

    fireEvent.keyDown(handle, { key: 'ArrowRight' });
    expect(handle).toHaveAttribute('aria-valuenow', '226');

    fireEvent.keyDown(handle, { key: 'ArrowLeft' });
    expect(handle).toHaveAttribute('aria-valuenow', '210');
  });

  it('clamps the palette resize to its declared min/max', () => {
    renderComposer();
    const handle = screen.getByRole('separator', { name: 'Resize component types panel' });
    handle.focus();

    for (let i = 0; i < 20; i += 1) {
      fireEvent.keyDown(handle, { key: 'ArrowLeft' });
    }
    expect(handle).toHaveAttribute('aria-valuemin', '160');
    expect(handle).toHaveAttribute('aria-valuenow', '160');

    for (let i = 0; i < 20; i += 1) {
      fireEvent.keyDown(handle, { key: 'ArrowRight' });
    }
    expect(handle).toHaveAttribute('aria-valuemax', '360');
    expect(handle).toHaveAttribute('aria-valuenow', '360');
  });

  it('resizes the "Configuration fields" inspector from the keyboard, growing it leftward', () => {
    renderComposer();
    fireEvent.click(
      screen.getByRole('button', { name: 'Source · COMP_SOURCE_FTP · 2 fields · ok' }),
    );
    const handle = screen.getByRole('separator', { name: 'Resize configuration fields panel' });
    expect(handle).toHaveAttribute('aria-valuenow', '400');
    handle.focus();

    // The inspector sits on the RIGHT of the canvas, so growing it means
    // moving its own left edge further left — the opposite key from the
    // palette's own grow-right handle.
    fireEvent.keyDown(handle, { key: 'ArrowLeft' });
    expect(handle).toHaveAttribute('aria-valuenow', '416');

    fireEvent.keyDown(handle, { key: 'ArrowRight' });
    expect(handle).toHaveAttribute('aria-valuenow', '400');
  });

  it('never renders the inspector resize handle before a node is selected', () => {
    renderComposer();
    expect(
      screen.queryByRole('separator', { name: 'Resize configuration fields panel' }),
    ).not.toBeInTheDocument();
  });
});

describe('Composer below md (task 108, FE-D29) — no mobile canvas', () => {
  it('forces the "All fields" list view below md, even though the operator never touched the toggle', () => {
    setViewportWidth(reflowFloorPx);
    renderComposer();

    expect(screen.queryByRole('button', { name: 'Composer' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'All fields' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('REMOTE_PATH')).toBeInTheDocument();
    expect(screen.getByLabelText('TOPIC', { exact: false })).toBeInTheDocument();
  });

  it('states why the canvas is unavailable, rather than silently substituting the list', () => {
    setViewportWidth(reflowFloorPx);
    renderComposer();

    expect(
      screen.getByText(
        'The canvas needs more room than this screen provides — showing all fields instead.',
      ),
    ).toBeInTheDocument();
  });

  it('reflows at the 320px floor with no horizontal scroll', () => {
    setViewportWidth(reflowFloorPx);
    const { container } = renderComposer();

    const root = container.firstElementChild;
    expect(root).toBeInstanceOf(HTMLElement);
    expect(() => {
      assertReflow(root as HTMLElement, reflowFloorPx);
    }).not.toThrow();
  });

  it('stays on the canvas at md and up, toggle intact', () => {
    setViewportWidth(905);
    renderComposer();

    expect(screen.getByRole('button', { name: 'Composer' })).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Component types' })).toBeInTheDocument();
  });
});
