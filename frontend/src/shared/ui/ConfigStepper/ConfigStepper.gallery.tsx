/** Dev-only gallery demo (task 010's registry) — every reachable rung. */
import type { ConfigLadderReachedKey } from '../../vocab';

import { ConfigStepper } from './ConfigStepper';

const REACHABLE_KEYS: readonly ConfigLadderReachedKey[] = [
  'desired',
  'rendered',
  'persisted',
  'restart-observed',
];

function ConfigStepperGalleryDemo() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      {REACHABLE_KEYS.map((reached) => (
        <div key={reached}>
          <div style={{ fontWeight: 600, marginBottom: 4 }}>reached: {reached}</div>
          <ConfigStepper reached={reached} />
        </div>
      ))}
    </div>
  );
}

export const configStepperGallerySection = {
  name: 'ConfigStepper',
  element: <ConfigStepperGalleryDemo />,
};
