/** Dev-only gallery demo (task 010's registry). */
import { useState } from 'react';

import { SecretField, type SecretFieldState } from './index';

function SecretFieldGalleryDemo() {
  const [state, setState] = useState<SecretFieldState>({ kind: 'unset' });
  return (
    <SecretField
      state={state}
      onSet={() => setState({ kind: 'set', ref: 'secretRef://kv/acme/broker' })}
      onClear={() => setState({ kind: 'unset' })}
    />
  );
}

export const secretFieldGallerySection = {
  name: 'SecretField',
  element: <SecretFieldGalleryDemo />,
};
