/** Dev-only gallery demo (task 010's registry). */
import { Box } from '@mui/material';

import { EnvChip } from './EnvChip';

export const envChipGallerySection = {
  name: 'EnvChip',
  element: (
    <Box sx={{ display: 'flex', gap: 1 }}>
      <EnvChip env="DEV" />
      <EnvChip env="TEST" />
      <EnvChip env="PROD" />
    </Box>
  ),
};
