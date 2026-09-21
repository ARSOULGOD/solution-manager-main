/** Dev-only gallery demo (task 010's registry). */
import { Button } from '../Button';

import { Menu } from './Menu';

export const menuGallerySection = {
  name: 'Menu',
  element: (
    <Menu
      trigger={<Button variant="outlined">More</Button>}
      entries={[
        { kind: 'caption', label: 'Capabilities' },
        { label: 'Edit' },
        { label: 'Delete', danger: true },
      ]}
    />
  ),
};
