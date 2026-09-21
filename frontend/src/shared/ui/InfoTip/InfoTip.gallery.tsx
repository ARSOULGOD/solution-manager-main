/** Dev-only gallery demo (task 010's registry). Hover or Tab to see the tip. */
import { InfoTip } from './InfoTip';

function InfoTipGalleryDemo() {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
      <span>Driver</span>
      <InfoTip tip="Driver is data on the row — not a separate view." />
    </div>
  );
}

export const infoTipGallerySection = {
  name: 'InfoTip',
  element: <InfoTipGalleryDemo />,
};
