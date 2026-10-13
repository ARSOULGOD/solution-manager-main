/** Dev-only gallery demo (task 010's registry). */
import { Button } from '../Button';

import { OutcomeBadge } from './OutcomeBadge';
import { Toaster } from './Toaster';
import { pushToast } from './toastStore';

function ToasterGalleryDemo() {
  return (
    <div>
      <Button
        onClick={() =>
          pushToast({
            title: 'Draft saved',
            detail: 'Wrote nothing to the MI.',
            correlationId: 'corr-demo',
          })
        }
      >
        Fire toast
      </Button>
      <OutcomeBadge title="Draft saved" correlationId="corr-demo" severity="ok" />
      <Toaster />
    </div>
  );
}

export const toasterGallerySection = {
  name: 'Toaster',
  element: <ToasterGalleryDemo />,
};
