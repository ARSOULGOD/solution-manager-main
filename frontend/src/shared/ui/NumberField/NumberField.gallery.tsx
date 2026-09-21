/** Dev-only gallery demo (task 010's registry). */
import { useState } from 'react';

import { NumberField } from './NumberField';

function NumberFieldGalleryDemo() {
  const [value, setValue] = useState<number | ''>(2);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 240 }}>
      <NumberField
        label="Replica count"
        value={value}
        onChange={setValue}
        helperText="Between 1 and 8."
        min={1}
        max={8}
      />
      <NumberField
        label="Replica count"
        value={0}
        onChange={() => {}}
        error="Must be at least 1."
      />
      <NumberField label="Disabled" value={1} onChange={() => {}} disabled />
    </div>
  );
}

export const numberFieldGallerySection = {
  name: 'NumberField',
  element: <NumberFieldGalleryDemo />,
};
