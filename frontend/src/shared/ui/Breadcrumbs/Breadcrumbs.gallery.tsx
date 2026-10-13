/** Dev-only gallery demo (task 010's registry). */
import { Breadcrumbs } from './Breadcrumbs';

export const breadcrumbsGallerySection = {
  name: 'Breadcrumbs',
  element: (
    <Breadcrumbs
      items={[
        { label: 'Home', to: '/' },
        { label: 'Solutions', to: '/solutions' },
        { label: 'Cache' },
      ]}
    />
  ),
};
