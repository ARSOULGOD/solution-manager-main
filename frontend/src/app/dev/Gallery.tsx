/**
 * One place to review every shared primitive in both themes (design §9,
 * UI §A.1) — the surface the automated a11y sweep (task 098) walks. DEV
 * only; `gallery.routes.ts` is what keeps it out of a production build.
 *
 * Each Phase 1 task (011-025) registers its own component here — import
 * its gallery demo and push a `GallerySection` — as its own file, own
 * name, own review surface (design §9's "one section per Phase-1
 * component showing default / hover / focus / disabled / error / loading,
 * in both themes").
 */
import { type ReactNode } from 'react';

import { useUiStore } from '@shared/state/uiStore';
import { alertGallerySection } from '@shared/ui/Alert/Alert.gallery';
import { autocompleteGallerySection } from '@shared/ui/Autocomplete/Autocomplete.gallery';
import { breadcrumbsGallerySection } from '@shared/ui/Breadcrumbs/Breadcrumbs.gallery';
import { buttonGallerySection } from '@shared/ui/Button/Button.gallery';
import { checkboxGallerySection } from '@shared/ui/Checkbox/Checkbox.gallery';
import { chipGallerySection } from '@shared/ui/Chip/Chip.gallery';
import { configStepperGallerySection } from '@shared/ui/ConfigStepper/ConfigStepper.gallery';
import { dataTableGallerySection } from '@shared/ui/DataTable/DataTable.gallery';
import { dialogGallerySection } from '@shared/ui/Dialog/Dialog.gallery';
import { diffViewGallerySection } from '@shared/ui/DiffView/DiffView.gallery';
import { emptyStateGallerySection } from '@shared/ui/EmptyState/EmptyState.gallery';
import { envChipGallerySection } from '@shared/ui/EnvChip/EnvChip.gallery';
import { filterBarGallerySection } from '@shared/ui/FilterBar/FilterBar.gallery';
import { infoTipGallerySection } from '@shared/ui/InfoTip/InfoTip.gallery';
import { menuGallerySection } from '@shared/ui/Menu/Menu.gallery';
import { numberFieldGallerySection } from '@shared/ui/NumberField/NumberField.gallery';
import { pageHeaderGallerySection } from '@shared/ui/PageHeader/PageHeader.gallery';
import { paperGallerySection } from '@shared/ui/Paper/Paper.gallery';
import { routeTabsGallerySection } from '@shared/ui/RouteTabs/RouteTabs.gallery';
import { secretFieldGallerySection } from '@shared/ui/SecretField/SecretField.gallery';
import { sectionHeadGallerySection } from '@shared/ui/SectionHead/SectionHead.gallery';
import { selectGallerySection } from '@shared/ui/Select/Select.gallery';
import { sidebarNavGallerySection } from '@shared/ui/SidebarNav/SidebarNav.gallery';
import { skeletonGallerySection } from '@shared/ui/Skeleton/Skeleton.gallery';
import { stateChipGallerySection } from '@shared/ui/StateChip/StateChip.gallery';
import { switchGallerySection } from '@shared/ui/Switch/Switch.gallery';
import { textAreaGallerySection } from '@shared/ui/TextArea/TextArea.gallery';
import { textFieldGallerySection } from '@shared/ui/TextField/TextField.gallery';
import { toasterGallerySection } from '@shared/ui/Toaster/Toaster.gallery';
import { toggleGroupGallerySection } from '@shared/ui/ToggleGroup/ToggleGroup.gallery';
import { tooltipGallerySection } from '@shared/ui/Tooltip/Tooltip.gallery';

export interface GallerySection {
  name: string;
  element: ReactNode;
}

const sections: GallerySection[] = [
  buttonGallerySection,
  textFieldGallerySection,
  numberFieldGallerySection,
  selectGallerySection,
  textAreaGallerySection,
  autocompleteGallerySection,
  switchGallerySection,
  checkboxGallerySection,
  chipGallerySection,
  stateChipGallerySection,
  alertGallerySection,
  tooltipGallerySection,
  infoTipGallerySection,
  paperGallerySection,
  pageHeaderGallerySection,
  sectionHeadGallerySection,
  dialogGallerySection,
  menuGallerySection,
  toggleGroupGallerySection,
  routeTabsGallerySection,
  emptyStateGallerySection,
  envChipGallerySection,
  skeletonGallerySection,
  dataTableGallerySection,
  configStepperGallerySection,
  toasterGallerySection,
  diffViewGallerySection,
  secretFieldGallerySection,
  breadcrumbsGallerySection,
  sidebarNavGallerySection,
  filterBarGallerySection,
];

export default function Gallery() {
  const mode = useUiStore((state) => state.mode);
  const toggleMode = useUiStore((state) => state.toggleMode);

  return (
    <main id="main-content">
      <h1>Component gallery</h1>
      <p>
        Current theme: {mode}.{' '}
        <button type="button" onClick={toggleMode}>
          Switch to {mode === 'light' ? 'dark' : 'light'}
        </button>
      </p>
      {sections.length === 0 ? (
        <p>No components registered yet — Phase 1 tasks add their own section here.</p>
      ) : (
        sections.map((section) => (
          <section key={section.name}>
            <h2>{section.name}</h2>
            {section.element}
          </section>
        ))
      )}
    </main>
  );
}
