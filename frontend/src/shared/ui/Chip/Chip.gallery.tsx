/** Dev-only gallery demo (task 010's registry). */
import { Chip } from './Chip';
import type { ChipTone } from './Chip.types';

const tones: ChipTone[] = ['ok', 'warn', 'err', 'info', 'idle', 'neutral', 'purple'];

function ChipGalleryDemo() {
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
      {tones.map((tone) => (
        <Chip key={tone} label={tone} tone={tone} dot />
      ))}
      {/* `mono` — for a label that is an identifier, not a word. */}
      <Chip label="pkg v7" tone="neutral" mono />
    </div>
  );
}

export const chipGallerySection = {
  name: 'Chip',
  element: <ChipGalleryDemo />,
};
