/** Dev-only gallery demo (task 010's registry). */
import { Button } from '../Button';

import { EmptyState } from './EmptyState';

export const emptyStateGallerySection = {
  name: 'EmptyState',
  element: (
    <EmptyState
      title="No instances declared yet"
      body="Declare an instance, apply its config, then restart. It stays unobservable until after that first restart."
      action={<Button>Declare</Button>}
    />
  ),
};
