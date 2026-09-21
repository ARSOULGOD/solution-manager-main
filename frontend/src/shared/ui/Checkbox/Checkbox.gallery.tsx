/** Dev-only gallery demo (task 010's registry). */
import { useState } from 'react';

import { Checkbox } from './Checkbox';
import type { CheckboxValue } from './Checkbox.types';

function CheckboxGalleryDemo() {
  const [checked, setChecked] = useState<CheckboxValue>('mixed');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <Checkbox
        label="Select all replicas"
        checked={checked}
        onChange={setChecked}
        helperText="Applies the action to every replica in this instance."
      />
      <Checkbox label="Checked" checked onChange={() => {}} />
      <Checkbox label="Unchecked" checked={false} onChange={() => {}} />
      <Checkbox label="Disabled" checked={false} onChange={() => {}} disabled />
    </div>
  );
}

export const checkboxGallerySection = {
  name: 'Checkbox',
  element: <CheckboxGalleryDemo />,
};
