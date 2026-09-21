/** Dev-only gallery demo (task 010's registry). */
import { useState } from 'react';

import { Button } from '../Button';

import { ConfirmDialog, HighConsequenceDialog } from './index';

function DialogGalleryDemo() {
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [highOpen, setHighOpen] = useState(false);

  return (
    <div style={{ display: 'flex', gap: 8 }}>
      <Button variant="outlined" onClick={() => setConfirmOpen(true)}>
        Confirm
      </Button>
      <Button variant="danger" onClick={() => setHighOpen(true)}>
        High consequence
      </Button>
      <ConfirmDialog
        open={confirmOpen}
        title="Discard draft"
        confirmLabel="Discard"
        onConfirm={() => setConfirmOpen(false)}
        onCancel={() => setConfirmOpen(false)}
      >
        Unsaved edits will be lost.
      </ConfirmDialog>
      <HighConsequenceDialog
        open={highOpen}
        title="Restart all"
        consequence="outage"
        confirmLabel="Restart all"
        onConfirm={() => setHighOpen(false)}
        onCancel={() => setHighOpen(false)}
      >
        Every replica will restart.
      </HighConsequenceDialog>
    </div>
  );
}

export const dialogGallerySection = {
  name: 'Dialog',
  element: <DialogGalleryDemo />,
};
