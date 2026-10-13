/** Dev-only gallery demo (task 010's registry). */
import { useState } from 'react';

import { Switch } from './Switch';

function SwitchGalleryDemo() {
  const [checked, setChecked] = useState(true);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <Switch
        label="Auto-restart"
        checked={checked}
        onChange={setChecked}
        helperText="Restarts a not-responding replica automatically."
      />
      <Switch
        label="Auto-restart"
        checked
        onChange={() => {}}
        error="Cannot be enabled without a health check configured."
      />
      <Switch label="Disabled" checked={false} onChange={() => {}} disabled />
    </div>
  );
}

export const switchGallerySection = {
  name: 'Switch',
  element: <SwitchGalleryDemo />,
};
