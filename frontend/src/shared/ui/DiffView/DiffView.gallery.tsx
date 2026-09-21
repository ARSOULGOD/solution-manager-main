/** Dev-only gallery demo (task 010's registry). */
import { DiffView } from './DiffView';
import { SECRET_MASK } from './DiffView.types';

export const diffViewGallerySection = {
  name: 'DiffView',
  element: (
    <DiffView
      labels={['Deployed', 'Draft']}
      rows={[
        { path: 'cache.ttl', kind: 'change', old: '60s', new: '120s' },
        { path: 'cache.host', kind: 'rename', old: 'hostname', new: 'host' },
        {
          path: 'broker.password',
          kind: 'change',
          secret: true,
          old: SECRET_MASK,
          new: SECRET_MASK,
        },
      ]}
    />
  ),
};
