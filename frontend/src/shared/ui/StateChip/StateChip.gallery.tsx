/** Dev-only gallery demo (task 010's registry) — every vocabulary entry, grouped by category. */
import {
  configStateVocab,
  driverVocab,
  healthVocab,
  locusVocab,
  notFoundVocab,
  versionVocab,
} from '../../vocab';

import { StateChip } from './StateChip';

const categories = [
  { name: 'Health', entries: Object.values(healthVocab) },
  { name: 'Declared but not found', entries: Object.values(notFoundVocab) },
  { name: 'Version', entries: Object.values(versionVocab) },
  { name: 'Config', entries: Object.values(configStateVocab) },
  { name: 'Driver', entries: Object.values(driverVocab) },
  { name: 'Locus', entries: Object.values(locusVocab) },
];

function StateChipGalleryDemo() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {categories.map((category) => (
        <div key={category.name}>
          <div style={{ fontWeight: 600, marginBottom: 4 }}>{category.name}</div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {category.entries.map((entry) => (
              <StateChip key={entry.key} entry={entry} />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

export const stateChipGallerySection = {
  name: 'StateChip',
  element: <StateChipGalleryDemo />,
};
