import type {
  Catalog,
  ComponentSelection,
  FieldValue,
  ObservedState,
  PackageVersionId,
  SolutionPackage,
} from '@contracts/view-model/types';

export interface ComposerProps {
  pkg: SolutionPackage;
  /** The instances this solution instance composes — `ComponentSelection.length > 1` is exactly `isComposerEligible` (`nodeCanvas.ts`). */
  componentSelection: ComponentSelection;
  /** The instance's package version (`FE-118`) — reaches every `project()` call the composer makes (`FE-D37`). */
  packageVersion: PackageVersionId;
  instanceRole?: string | undefined;
  /** Catalog data used by package-declared dynamic selectors. */
  catalog?: Catalog;
  /** Display labels for catalog-backed selector ids. */
  optionLabelsByField?: Readonly<Record<string, Readonly<Record<string, string>>>>;
  /** `DesignerDraft.getInstanceValues` (`useDesignerDraft.ts`, task 052) — the same seam `SectionCard`/`FieldSearch` take their `values` from, scoped per instance rather than merged. */
  getInstanceValues: (instanceId: string) => Readonly<Record<string, FieldValue>>;
  observedState: ObservedState;
  /** `DesignerDraft.onFieldChange`'s own signature (`useDesignerDraft.ts`) — instance-scoped, so a future page can pass the hook's own callback straight through. */
  onFieldChange: (instanceId: string, fieldKey: string, value: FieldValue) => void;
  disabled?: boolean;
}
