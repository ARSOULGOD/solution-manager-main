/** Dev-only gallery demo (task 010's registry). */
import { useState } from 'react';

import { SecretField } from './index';

function SecretFieldGalleryDemo() {
  const [value, setValue] = useState('');
  return (
    <SecretField
      label="Solace password"
      value={value}
      onChange={(event) => setValue(event.target.value)}
    />
  );
}

export const secretFieldGallerySection = {
  name: 'SecretField',
  element: <SecretFieldGalleryDemo />,
};
