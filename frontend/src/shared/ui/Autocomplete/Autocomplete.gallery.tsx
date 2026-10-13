/** Dev-only gallery demo (task 010's registry). */
import { useState } from 'react';

import { Autocomplete } from './Autocomplete';

interface Org {
  id: string;
  name: string;
}

const orgs: Org[] = [
  { id: 'acme', name: 'Acme' },
  { id: 'globex', name: 'Globex' },
  { id: 'initech', name: 'Initech' },
];

function AutocompleteGalleryDemo() {
  const [value, setValue] = useState<Org | null>(orgs[0] ?? null);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16, maxWidth: 280 }}>
      <Autocomplete<Org>
        label="Organisation"
        value={value}
        onChange={setValue}
        options={orgs}
        getOptionLabel={(org) => org.name}
        helperText="The owning organisation."
      />
      <Autocomplete<Org>
        label="Organisation"
        value={null}
        onChange={() => {}}
        options={orgs}
        getOptionLabel={(org) => org.name}
        error="Organisation is required."
      />
    </div>
  );
}

export const autocompleteGallerySection = {
  name: 'Autocomplete',
  element: <AutocompleteGalleryDemo />,
};
