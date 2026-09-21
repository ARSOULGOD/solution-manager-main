/** Dev-only gallery demo (task 010's registry). */
import { useState } from 'react';

import { Select } from './Select';

const options = [
  { value: 'docker', label: 'Docker' },
  { value: 'k8s', label: 'Kubernetes' },
  { value: 'systemd', label: 'systemd · VM' },
];

function SelectGalleryDemo() {
  const [value, setValue] = useState('docker');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 240 }}>
      <Select label="Driver" value={value} onChange={setValue} options={options} />
      <Select
        label="Driver"
        value="docker"
        onChange={() => {}}
        options={options}
        error="Unsupported driver."
      />
      <Select label="Disabled" value="docker" onChange={() => {}} options={options} disabled />
    </div>
  );
}

export const selectGallerySection = {
  name: 'Select',
  element: <SelectGalleryDemo />,
};
