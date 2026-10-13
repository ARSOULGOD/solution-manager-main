/** Dev-only gallery demo (task 010's registry). */
import { Button } from '../Button';

import { PageHeader } from './PageHeader';

function PageHeaderGalleryDemo() {
  return (
    <PageHeader
      title="Instances"
      sub="An instance is declared here; the artifact is deployed by your own CI/CD."
      actions={
        <>
          <Button variant="outlined">Export</Button>
          <Button>Declare</Button>
        </>
      }
    />
  );
}

export const pageHeaderGallerySection = {
  name: 'PageHeader',
  element: <PageHeaderGalleryDemo />,
};
