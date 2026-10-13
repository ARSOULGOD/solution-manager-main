/** Dev-only gallery demo (task 010's registry). */
import { useState } from 'react';

import { ToggleGroup } from './ToggleGroup';

function ToggleGroupGalleryDemo() {
  const [value, setValue] = useState('all');
  return (
    <ToggleGroup
      aria-label="Environment"
      value={value}
      onChange={setValue}
      options={[
        { value: 'all', label: 'All' },
        { value: 'dev', label: 'DEV' },
        { value: 'test', label: 'TEST' },
        { value: 'prod', label: 'PROD' },
      ]}
    />
  );
}

export const toggleGroupGallerySection = {
  name: 'ToggleGroup',
  element: <ToggleGroupGalleryDemo />,
};
