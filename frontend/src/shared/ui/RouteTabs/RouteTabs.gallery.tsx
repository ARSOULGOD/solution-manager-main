/** Dev-only gallery demo (task 010's registry). */
import { RouteTabs } from './RouteTabs';

export const routeTabsGallerySection = {
  name: 'RouteTabs',
  element: (
    <RouteTabs
      tabs={[
        { to: '/', label: 'Designer' },
        { to: '/governance', label: 'Governance' },
        { to: '/monitoring', label: 'Monitoring' },
      ]}
    >
      Category panel
    </RouteTabs>
  ),
};
