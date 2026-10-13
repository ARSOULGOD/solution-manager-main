/** Dev-only gallery demo (task 010's registry). Hover or Tab to the trigger to see it open. */
import { Tooltip } from './Tooltip';

function TooltipGalleryDemo() {
  return (
    <div style={{ display: 'flex', gap: 16 }}>
      <Tooltip title="Explains this state">
        <span>Hover or Tab to me</span>
      </Tooltip>
    </div>
  );
}

export const tooltipGallerySection = {
  name: 'Tooltip',
  element: <TooltipGalleryDemo />,
};
