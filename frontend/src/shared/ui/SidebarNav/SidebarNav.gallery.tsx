/** Dev-only gallery demo (task 010's registry). */
import { Box } from '@mui/material';

import { SidebarNav } from './SidebarNav';

const SOLUTIONS = [
  { id: 'cache', name: 'Cache', instanceCount: 12, icon: 'bolt' },
  { id: 'file-mesh', name: 'File Mesh', instanceCount: 3, icon: 'files' },
];

export const sidebarNavGallerySection = {
  name: 'SidebarNav',
  element: (
    // Both widths side by side — the rail is the state most likely to
    // regress unseen, since nothing routes to it by default.
    <Box sx={{ display: 'flex', gap: 2, alignItems: 'flex-start' }}>
      <SidebarNav solutionCount={2} solutions={SOLUTIONS} onToggleCollapsed={() => {}} />
      {/* `aria-hidden` on this second copy only — two live `<nav
          aria-label="Solution Manager">` landmarks on one page fails
          axe's `landmark-unique` rule (task 098's Gallery a11y sweep),
          purely a side-effect of the gallery showing both widths at
          once; the real app never renders more than one `SidebarNav`
          simultaneously. `SidebarNav.a11y.test.tsx` already covers the
          collapsed state's own accessibility in isolation, so nothing is
          lost by taking this SECOND copy out of the tree here — it
          stays visible for the side-by-side visual comparison this
          section exists for. */}
      <Box aria-hidden="true">
        <SidebarNav solutionCount={2} solutions={SOLUTIONS} collapsed onToggleCollapsed={() => {}} />
      </Box>
    </Box>
  ),
};
