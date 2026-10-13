/** Dev-only gallery demo (task 010's registry). */
import { useState } from 'react';

import { ToggleGroup } from '../ToggleGroup';

import { FilterBar } from './FilterBar';

function FilterBarGalleryDemo() {
  const [q, setQ] = useState('');
  const [env, setEnv] = useState('all');
  return (
    <FilterBar resultCount={12} search={{ value: q, onChange: setQ }}>
      <ToggleGroup
        aria-label="Environment"
        value={env}
        onChange={setEnv}
        options={[
          { value: 'all', label: 'All' },
          { value: 'dev', label: 'DEV' },
          { value: 'prod', label: 'PROD' },
        ]}
      />
    </FilterBar>
  );
}

export const filterBarGallerySection = {
  name: 'FilterBar',
  element: <FilterBarGalleryDemo />,
};
