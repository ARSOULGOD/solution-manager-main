/**
 * Public barrel for the shared component kit (design §9, UI §A.1). Each
 * Phase 1 task (011-025) adds its own component's exports here.
 */
export { Button, IconButton } from './Button';
export type {
  ButtonProps,
  ButtonSize,
  ButtonVariant,
  IconButtonProps,
  IconButtonSize,
} from './Button';

export { Alert } from './Alert';
export type { AlertProps, AlertSeverity } from './Alert';

export { Autocomplete } from './Autocomplete';
export type { AutocompleteProps } from './Autocomplete';

export { Breadcrumbs } from './Breadcrumbs';
export type { BreadcrumbCrumb, BreadcrumbLabels, BreadcrumbsProps } from './Breadcrumbs';

export { Checkbox } from './Checkbox';
export type { CheckboxProps, CheckboxValue } from './Checkbox';

export { Chip } from './Chip';
export type { ChipProps, ChipTone } from './Chip';

export { ConfigStepper } from './ConfigStepper';
export type { ConfigStepperProps } from './ConfigStepper';

export { ConfirmDialog, Dialog, HighConsequenceDialog } from './Dialog';
export type {
  ConfirmDialogProps,
  Consequence,
  DialogProps,
  HighConsequenceDialogProps,
} from './Dialog';

export { DataTable } from './DataTable';
export type { ColumnDef, DataTableProps, TableDensity } from './DataTable';

export { DiffView, SECRET_MASK } from './DiffView';
export type { DiffKind, DiffRow, DiffViewProps, SecretMask } from './DiffView';

export { EmptyState } from './EmptyState';
export type { EmptyStateProps } from './EmptyState';

export { EnvChip } from './EnvChip';
export type { Env, EnvChipProps } from './EnvChip';

export { FilterBar, SearchField } from './FilterBar';
export type { FilterBarProps, SearchFieldProps } from './FilterBar';

export { InfoTip } from './InfoTip';
export type { InfoTipProps } from './InfoTip';

export { Menu } from './Menu';
export type {
  MenuCaptionDef,
  MenuContentDef,
  MenuDividerDef,
  MenuEntry,
  MenuItemDef,
  MenuProps,
} from './Menu';

export { NumberField } from './NumberField';
export type { NumberFieldProps } from './NumberField';

export { PageHeader } from './PageHeader';
export type { PageHeaderProps } from './PageHeader';

export { Paper } from './Paper';
export type { PaperProps, PaperSurface } from './Paper';

export { RouteTabs } from './RouteTabs';
export type { RouteTab, RouteTabsProps } from './RouteTabs';

export { SecretField } from './SecretField';
export type { SecretFieldProps, SecretFieldState } from './SecretField';

export { SectionHead } from './SectionHead';
export type { SectionHeadProps } from './SectionHead';

export { Select } from './Select';
export type { SelectOption, SelectProps } from './Select';

export { SidebarNav } from './SidebarNav';
export type { SidebarNavProps, SidebarNavSolution } from './SidebarNav';

export { Skeleton } from './Skeleton';
export type { SkeletonProps } from './Skeleton';

export { StateChip } from './StateChip';
export type { StateChipProps } from './StateChip';

export { Switch } from './Switch';
export type { SwitchProps } from './Switch';

export { TextArea } from './TextArea';
export type { TextAreaProps } from './TextArea';

export { TextField } from './TextField';
export type { TextFieldProps } from './TextField';

export {
  Toaster,
  OutcomeBadge,
  pushToast,
  useToastStore,
  pushOutcome,
  useOutcome,
  useOutcomeStore,
} from './Toaster';
export type { OutcomeBadgeProps, ToastInput, ToastRecord } from './Toaster';

export { ToggleGroup } from './ToggleGroup';
export type { ToggleGroupProps, ToggleOption } from './ToggleGroup';

export { Tooltip } from './Tooltip';
export type { TooltipProps } from './Tooltip';

// Task 038 (Home) is the first *feature* consumer of the tone-coloured
// icon set `Alert`/`StateChip` already share internally — barrelled here
// rather than reached into `internal/` directly.
export { ToneIcon } from './internal/toneIcons';

// The per-solution glyph set, shared by the Solutions catalog (task 039)
// and the drawer's solution list (task 024/112) — `shared/ui` may not
// import a feature, so the glyphs live here and both consumers read one
// set. Same barrelling rationale as `ToneIcon` above.
export { PackageGlyphIcon, SolutionGlyph } from './internal/solutionGlyphs';
