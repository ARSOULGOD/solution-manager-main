/**
 * The English message catalogue — the only locale in v1 (design §18: i18n).
 * Every user-visible string is a key here so a view-model value is never
 * glued into a sentence by a component; `t()` (./t.ts) resolves keys.
 */

export interface PluralMessage {
  one: string;
  other: string;
}

export type MessageValue = string | PluralMessage;

export const messagesEn = {
  retry: 'Retry',
  errorGeneric: 'Something went wrong.',

  showPassword: 'Show password',
  hidePassword: 'Hide password',

  replicaCount: { one: '{count} replica', other: '{count} replicas' },
  advancedFieldCount: { one: '{count} advanced field', other: '{count} advanced fields' },
  fieldMatchCount: {
    one: '{count} field matches “{query}”',
    other: '{count} fields match “{query}”',
  },

  close: 'Close',
  cancel: 'Cancel',
  confirm: 'Confirm',
  save: 'Save',
  dismiss: 'Dismiss',

  navHome: 'Home',
  navSolutions: 'Solutions',
  navLabel: 'Solution Manager',
  navPlatform: 'Platform',
  navOpenMenu: 'Open navigation',
  navCloseMenu: 'Close navigation',
  navCollapse: 'Collapse navigation',
  navExpand: 'Expand navigation',
  breadcrumb: 'Breadcrumb',
  breadcrumbInstances: 'Instances',
  breadcrumbMore: 'Show earlier pages',
  categoryDesigner: 'Designer',
  categoryGovernance: 'Governance',
  categoryMonitoring: 'Monitoring',
  solutionCount: { one: '{count} solution', other: '{count} solutions' },
  instanceCount: { one: '{count} instance', other: '{count} instances' },

  consequenceOutage:
    'This action causes an outage. Confirm only if you intend every affected replica to restart.',
  consequenceHigh:
    'This is a high-consequence action. Confirm only if you intend the change named below.',

  secretMaskedTag: 'secret · masked',

  diffNeedsMapping: 'needs mapping',
  diffRenameNeedsMapping: 'Renamed · needs mapping — an unmapped rename silently drops a value.',

  correlationId: 'Correlation id {id}',
  correlationIdLabel: 'Correlation id',
  toastRegion: 'Notifications',

  expandRow: 'Expand row details',
  collapseRow: 'Collapse row details',
  tableSelectedCount: { one: '{count} selected', other: '{count} selected' },
  tableRowCount: '{shown} of {total} rows',
  csvExport: 'Export CSV',
  columnVisibility: 'Show columns',
  tableColumns: 'Columns',
  density: 'Density',
  densityStandard: 'Standard',
  densityCompact: 'Compact',
  tableSearch: 'Search table',
  tableCardListLabel: 'Table, card view',
  tableSelectRow: 'Select {label}',

  search: 'Search',
  filterBar: 'Filters',
  resultCount: { one: '{count} result', other: '{count} results' },

  skipToMain: 'Skip to main content',
  // `D20`: Organization is the top hierarchy and server-derived isolation
  // boundary, so the app bar names the ORGANIZATION.
  organizationLabel: 'Organization',
  organizationTooltip:
    'Organization is a display string the server returns — it is not switchable here.',
  /** Appended after the first organization's name when more than one is visible (an unscoped admin). */
  organizationDisplayMore: '+{count} more',
  organizationDisplayNone: 'No organization',
  themeLabel: 'Theme',
  lightTheme: 'Light theme',
  darkTheme: 'Dark theme',
  supportAndVersion: 'Support and version',
  supportCaption: 'Support',
  uiVersion: 'UI version',
  copyId: 'Copy id',
  idCopied: 'Copied',
  supportCorrelationNote: 'Quote the correlation id on a support ticket.',
  account: 'Account',
  signOut: 'Sign out',
  /**
   * Replaces the account menu's old role chip (`noRoleAssigned`, removed):
   * the real `Identity` wire response has no role-name field to show, only
   * the flattened effective permission list, so this shows a count rather
   * than fabricate a role name the contract can't back up
   * (`docs/plans/integration/reports/real-e2e-2026-10-08.md`).
   */
  accountPermissionCount: {
    one: '{count} permission',
    other: '{count} permissions',
  },
  navConnections: 'Connections',
  navAdmin: 'Admin',
  previewTag: 'preview',

  loginHeading: 'Sign in',
  loginSubtitle: 'Solace Solution Manager',
  loginBrandTagline: 'Solace Console',
  usernameLabel: 'Username',
  passwordLabel: 'Password',
  signIn: 'Sign in',
  signingIn: 'Signing in…',
  loginRejected: 'Username or password is incorrect.',
  loginUnreachableTitle: 'Can’t reach Solution Manager',
  loginUnreachableBody:
    'The console couldn’t reach the server. Check your connection, then try again.',
  loginRateLimitedTitle: 'Too many sign-in attempts',
  loginRateLimitedBody: 'Wait a moment, then try again.',
  loginRateLimitedBodyWithSeconds: {
    one: 'Wait {count} second, then try again.',
    other: 'Wait {count} seconds, then try again.',
  },
  loginSessionTitle: 'Your session ended',
  loginSessionBody:
    'The server no longer recognised your session, so you were signed out. Sign in to continue.',
  loginIdleTitle: {
    one: 'Signed out after {count} minute idle',
    other: 'Signed out after {count} minutes idle',
  },
  loginIdleBody:
    'For security, the console signed you out after inactivity. Your in-progress configuration edits were saved on this device and will reload after you sign in.',
  loginFooterAccounts:
    'Accounts are created by your administrator. There’s no self-service sign-up or password reset in this release.',
  loginFooterIdle: {
    one: 'Sessions end after {count} minute of inactivity.',
    other: 'Sessions end after {count} minutes of inactivity.',
  },

  // RotateCredentialDialog (task 132-5, backend `mustRotate` / 428
  // `MUST_ROTATE`). Blocking by design — no dismiss path, because the
  // backend fails every other authenticated call closed until rotation
  // succeeds.
  rotateCredentialTitle: 'Update your credential',
  rotateCredentialExplanation:
    'Your administrator requires a new credential before you can continue. Enter your current credential and choose a new one — at least 12 characters.',
  currentCredentialLabel: 'Current credential',
  newCredentialLabel: 'New credential',
  rotateCredentialSubmit: 'Update credential',
  rotatingCredential: 'Updating…',
  rotateCredentialUnreachable:
    'The console couldn’t reach the server. Check your connection, then try again.',

  homeTitle: 'Home',
  homeSubtitle:
    'What needs your attention right now — not an aggregate dashboard. You’re here to find the one broken thing.',
  homeErrorTitle: 'Couldn’t load your home view',
  homeErrorDetail:
    'The console reached the server but the state query failed. This is an error — not an empty console and not “all clear”.',
  homeStatSolutions: 'Solutions managed',
  homeStatInstances: 'Instances declared',
  homeStatAttention: 'Need attention now',
  homeStatConfigStaged: 'Config staged · not applied',
  homeAttentionHeading: 'Needs attention',
  homeAttentionCount: {
    one: '{count} item · newest first',
    other: '{count} items · newest first',
  },
  homeAttentionEmptyTitle: 'Nothing needs attention.',
  homeAttentionEmptyBody: 'All declared instances are healthy, applied and on a known version.',
  homeFooterNoAlerting:
    'Solution Manager surfaces these here. It does not notify or page anyone — no alerting is configured in this release.',
  homeFirstRunTitle: 'Load a Solution Package to begin',
  homeFirstRunBody:
    'Every screen here renders from a Solution Package — published by Solace, loaded by you. Until one is loaded, there’s nothing to configure, govern or monitor.',
  homeFirstRunTileSolutions: 'Solutions',
  homeFirstRunTileInstances: 'Instances',
  homeFirstRunTileTargets: 'Targets',
  homeFirstRunAction: 'Load a package',
  homeFirstRunAdminOnly:
    'Loading a package is a Solace super-admin action. Ask your administrator to publish and load one.',
  homePartialTitle: 'Live state for one target is unavailable',
  homePartialBody:
    '{host}’s host hasn’t reported for several minutes, so any figure including it may be stale. Missing values read “—”, never 0.',

  solutionsTitle: 'Solutions',
  solutionsSubtitle:
    'The solution types this deployment can manage. Each renders entirely from its Solution Package — the console holds no per-solution code.',
  solutionsErrorTitle: 'Couldn’t load the catalog',
  solutionsErrorDetail:
    'The console reached the server but the package list failed to load. Retry, or check the control-plane status.',
  solutionsEmptyTitle: 'No solution packages loaded',
  solutionsEmptyBody:
    'A Solution Package — published by Solace, loaded here — defines every solution this deployment can manage.',
  solutionsEntitled: 'Entitled',
  solutionsNotEntitled: 'Not entitled',
  solutionsSigned: 'Signed · Solace',
  solutionsSignedTip: 'Solace signature {fingerprint} — verified.',
  solutionsUnsigned: 'Unsigned · dev-mode',
  solutionsUnsignedTip:
    'No Solace signature. Loadable only in an explicit development mode, and never trusted like a signed package.',
  solutionsDevBanner: 'Unsigned · development mode — not trusted like a signed package',
  solutionsPackageVersion: 'Package version',
  solutionsLatestPackageVersionChip: '{version} · latest',
  solutionsSignatureUnsigned: 'Unsigned (v1 loads no signed packages)',
  solutionsAppliesTo: 'Applies to',
  solutionsAppliesToTip:
    'Semver range of MI versions this package serves — the package’s own revision, not the MI’s.',
  solutionsSource: 'Source',
  solutionsOpen: 'Open',
  solutionsOpenDisabledTip: 'Unsigned — loadable and manageable only in development mode.',
  solutionsReviewHeading: 'Authoring review',
  solutionsReviewFully: 'Fully reviewed',
  solutionsReviewNone: 'Not reviewed',
  solutionsReviewPartial: '{pct}% reviewed',
  solutionsReviewCaption: '{reviewed} / {total} rows reviewed',
  solutionsReviewCaptionPartial:
    '{reviewed} / {total} rows reviewed · the remainder auto-extracted — a normal state',
  solutionsReviewCaptionZero:
    '{reviewed} / {total} rows reviewed · assembled by automated extraction, unreviewed',

  solutionsLoadAction: 'Load a package',
  solutionsLoadAdminOnlyAlert: 'Ask a Solace super-admin to load one.',
  solutionsLoadDialogTitle: 'Load a Solution Package',
  solutionsLoadDialogAdminOnlyNote: 'Visible to Solace super-admins only.',
  solutionsLoadDropzoneLabel: 'Drop a package file here',
  solutionsLoadDropzoneHint: 'or click to browse · Solution Package JSON',
  solutionsLoadChooseAnother: 'Choose another',
  solutionsLoadValidating: 'Validating package structure and metadata…',
  solutionsLoadValidTitle: 'Package format is valid',
  solutionsLoadValidDetail:
    'The package structure, components, fields, metadata, predicates, actions and metrics passed validation.',
  solutionsLoadInvalidTitle: 'Package format needs correction',
  solutionsLoadInvalidDetailOne: 'Fix this validation issue before loading the package.',
  solutionsLoadInvalidDetail: 'Fix these {count} validation issues before loading the package.',
  solutionsLoadIssueListLabel: 'Package validation issues',
  solutionsLoadGate: 'Gate {gate}',
  solutionsLoadPackageVersion: 'Package versions {version}',
  solutionsLoadAppliesTo: 'Applies to runtime versions {range}',
  solutionsLoadComponents: 'Components',
  solutionsLoadFields: 'Fields',
  solutionsLoadActions: 'Actions',
  solutionsLoadMetrics: 'Metrics',
  solutionsLoadComponentCounts: 'Fields: {fields} · Actions: {actions} · Metrics: {metrics}',
  solutionsLoadComponentDetailsHint: 'Click a component to view its declared fields, actions and metrics.',
  solutionsLoadFieldRequired: 'Required',
  solutionsLoadFieldSecret: 'Secret',
  solutionsLoadFieldType: 'Type: {type}',
  solutionsLoadFieldDefaultValue: 'Default: {value}',
  solutionsLoadFieldNone: 'No fields declared.',
  solutionsLoadActionKind: 'Kind: {kind}',
  solutionsLoadActionNone: 'No actions declared.',
  solutionsLoadMetricMeta: '{metricType} · {unit}',
  solutionsLoadMetricNone: 'No metrics declared.',
  solutionsLoadMetadataValidated: 'Field keys, types and UI metadata are valid.',
  solutionsLoadAdmitted: 'Loaded',
  solutionsLoadVerifying: 'Verifying signature…',
  solutionsLoadVerifiedSigned: 'Signature verified · Solace-signed',
  solutionsLoadVerifiedFingerprint: 'Fingerprint {fingerprint}',
  solutionsLoadDevModeAdmitted: 'Unsigned · admitted in development mode',
  solutionsLoadDevModeAdmittedDetail:
    'No Solace signature. Admitted only because the console is running in development mode — it will be marked conspicuously wherever it appears afterwards.',
  solutionsLoadRejected: 'Unsigned — not admitted',
  solutionsLoadRejectedDetail:
    'This console is not running in development mode, so an unsigned package cannot be loaded.',
  solutionsLoadNetworkError: 'Verification failed',
  solutionsLoadInfoBanner:
    'Solace-signed packages load normally. An unsigned (dev-mode) package is admitted only when the console runs in development mode, and is marked conspicuously wherever it appears.',
  solutionsLoadSubmit: 'Confirm and load',
  solutionsLoadSubmitting: 'Loading…',
  solutionsLoadSuccessTitle: 'Package loaded',
  solutionsLoadSuccessDetail: '{message}',
  solutionsLoadSuccessPackageVersions: 'Package version(s) {versions} loaded.',

  instancesSubtitle:
    'Instances of this solution. An instance is declared here; the artifact is deployed by your own CI/CD.',
  instancesErrorTitle: 'Couldn’t load instances',
  instancesEmptyTitle: 'No instances declared yet',
  instancesEmptyBody:
    'An instance is declared here and deployed by your own CI/CD — Solution Manager applies configuration, never artifacts. After you declare one, the sequence is declare → apply → restart → observable: it stays unobservable until after that first restart, so an early "missing" is expected, not a fault.',
  instancesNoMatch: 'No instances match this filter.',
  instancesDeclareAction: 'Declare instance',
  instancesDeclareDialogTitle: 'Declare instance',
  instancesDeclareDialogIntro:
    'Tell Solution Manager about an instance your own CI/CD has deployed. Configuration is applied to it afterwards, in the Designer — Solution Manager never deploys the artifact.',

  instancesDeclareName: 'Name',
  instancesDeclareNameHelp: 'How this instance is identified everywhere in the console.',
  instancesDeclarePackageVersion: 'Package version',
  instancesDeclarePackageVersionHelp:
    'The configuration schema this instance is configured against — it decides which fields the Designer shows.',
  instancesDeclareEnv: 'Environment',
  instancesDeclareOrg: 'Organization',
  instancesDeclareSubOrg: 'Sub-organization',
  /** The cascade's gated / loading / failed states (`FE-D46`). */
  instancesDeclareSubOrgGated: 'Choose an organization first.',
  instancesDeclareEnvGated: 'Choose a sub-organization first.',
  instancesDeclareHierarchyLoading: 'Loading…',
  instancesDeclareHierarchyError: 'Could not load these. Retry, or check your connection.',
  /** Deployment mode and the remote-server Connection picker (integration plan 014). */
  instancesDeclareDeploymentMode: 'Deployment mode',
  instancesDeclareDeploymentModeHelp:
    'Remote (dp-remote) is the only mode available today. Host-local and federated are coming soon.',
  instancesDeclareDeploymentModeDpRemote: 'Remote (dp-remote)',
  instancesDeclareDeploymentModeHostLocal: 'Host-local (coming soon)',
  instancesDeclareDeploymentModeFederated: 'Federated (coming soon)',
  instancesDeclareConnection: 'Remote server connection',
  instancesDeclareConnectionHelp:
    'An active remote-server Connection. Its host and credentials are never shown or sent from here.',
  instancesDeclareConnectionNone:
    'No active remote-server Connection is available. Add one under Connections first.',
  instancesDeclareContainerName: 'Container name',
  instancesDeclareRole: 'Role',
  instancesDeclareRoleHelp: 'Select the package-defined role for this instance.',
  instancesDeclareContainerNameHelp: 'Must start with the connection’s allowed container prefix.',
  instancesDeclareConfigDirectory: 'Config directory',
  instancesDeclareConfigDirectoryHelp: 'Must be inside the connection’s allowed config root.',
  instancesDeclareContainerConfigPath: 'Container config path',
  instancesDeclareFileName: 'File name',
  instancesDeclareHealthPort: 'Health port',
  instancesDeclareAcquisitionMode: 'Acquisition mode',
  instancesDeclareAcquisitionModeHelp:
    'How Solution Manager reaches this instance for health and metrics. Cannot be changed later without re-targeting.',
  instancesDeclareAcquisitionDirectHttp: 'Direct HTTP (the target’s own address)',
  instancesDeclareAcquisitionViaSsh: 'Via SSH (the target’s host loopback)',
  instancesDeclareSubmit: 'Declare',
  instancesDeclareSubmitting: 'Declaring…',

  instancesDeclareErrRequired: 'Required.',
  instancesDeclareErrNameFormat:
    'Use lowercase letters, digits and hyphens, starting and ending with a letter or digit.',
  instancesDeclareErrNameTaken: 'An instance with this name is already declared.',
  instancesDeclareErrPackageVersion:
    'This solution’s package doesn’t serve that version. Choose one from the list.',
  instancesDeclareErrHealthPortRange: 'Enter a port between 1 and 65535.',
  instancesDeclareErrDeploymentModeUnavailable: 'Only Remote (dp-remote) can be declared today.',
  instancesDeclareDisabledLoadingTip:
    'Loading this solution’s package versions. An instance is declared against one of them, so they have to be known first.',
  instancesDeclareFailedTitle: 'Couldn’t declare the instance',
  instancesDeclareSucceededTitle: 'Instance declared',
  instancesDeclareSucceededDetail:
    '{name} is declared. It stays unobservable until after its first apply and restart.',
  instancesUndeclareTrigger: {
    one: 'Remove {count}…',
    other: 'Remove {count}…',
  },
  instancesUndeclareTitle: {
    one: 'Remove {count} instance from Solution Manager',
    other: 'Remove {count} instances from Solution Manager',
  },
  instancesUndeclareConfirm: { one: 'Remove {count}', other: 'Remove {count}' },
  instancesUndeclareLead:
    'This removes Solution Manager’s record of the instances below — their configuration of record, staged changes and history.',
  instancesUndeclareNotStopped:
    'The running workload is not touched. Solution Manager doesn’t deploy, stop or delete instances — your own CI/CD owns that. Whatever is running keeps running, unmanaged, until you declare it again.',
  instancesUndeclareReversible:
    'Declaring the same instance again re-adds it, but its configuration history does not come back.',
  instancesUndeclareSucceededTitle: {
    one: '{count} instance removed',
    other: '{count} instances removed',
  },
  instancesUndeclareFailedTitle: 'Couldn’t remove every instance',
  instancesUndeclareFailedDetail: 'Still declared: {names}.',

  instancesEnvFilter: 'Filter by environment',
  instancesEnvAll: 'All',

  instancesColInstance: 'Instance',
  instancesColEnv: 'Env',
  instancesColPackageVersion: 'Package version',
  instancesColReplicas: 'Placements',

  instancesBulkRestartTrigger: { one: 'Restart {count}…', other: 'Restart {count}…' },
  instancesBulkRestartTitle: 'Restart selected instances',
  instancesBulkRestartConfirm: { one: 'Restart {count}', other: 'Restart {count}' },
  instancesBulkRestartRule:
    'Writing configuration is not the same as applying it. These instances will restart to pick up staged config.',
  instancesBulkRestartUnavailability: 'expect ~10–20s unavailability per replica',
  instancesBulkRestartClosingNote:
    'Restart is operator-initiated. The console confirms only that it wrote config and observed a restart — never that the process is using it.',

  instanceTabDesigner: 'Designer',
  instanceTabGovernance: 'Governance',
  instanceTabMonitoring: 'Monitoring',
  instanceVersionBlockedBadge: 'Version drift · Designer read-only',
  instanceVersionBlockedNote:
    'Governance and Monitoring still work — two of three categories remain available while this version state blocks configuration edits.',
  instanceErrorTitle: 'Couldn’t load this instance',

  // GovernancePage (task 064, `REQ-009`, `UI-D9`) — answers one question,
  // "is the endpoint up?", and refuses to answer any other. Five rollups,
  // never a synthetic score. See `governance/healthRollup.ts` for why the
  // rollup keys here (`GREEN`/`RED`/…) differ from `healthVocab`'s.
  governanceTitle: 'Governance',
  governanceSub: 'Health & configuration state for this instance.',
  governanceErrorTitle: 'Couldn’t load health for this instance',
  governanceOpenMonitoring: 'Open in Monitoring',
  governanceReplicasSectionTitle: 'Replicas',
  governanceRollupBadge: 'rollup',
  governanceParagraphGreen:
    'The endpoint reports up. The platform does not judge whether the MI is actually working — if it reports healthy while malfunctioning, that is the MI’s defect.',
  governanceParagraphRed:
    'The health endpoint is not answering. Open Monitoring to confirm with the graphs — a gap there is what proves it is genuinely down.',
  governanceParagraphRestartExpected:
    'A platform-initiated restart is in progress. This is expected activity, not an outage.',
  governanceParagraphInactiveReplica:
    'Every replica is a standby: up, and deliberately processing nothing. The instance is fine — a standby is not a failure and its zero KPIs are expected.',
  governanceParagraphNotApplicable:
    'This instance declares no health block, so health is not applicable. This is normal — it is never treated as an outage.',
  governanceParagraphUnreachable:
    'The host could not be reached. This is a host alert, not a confirmed MI failure — the state is "unknown", never assumed down.',
  governanceParagraphNotExposed:
    'Declared but not exposed for discovery. This is a configuration finding, not necessarily an outage.',
  governanceParagraphMissing:
    'Declared but not found anywhere it was expected. This is an instance alert.',
  governanceParagraphBootstrap:
    'Declared, not yet applied — awaiting its first restart before it becomes observable.',
  governanceNonGoalTitle: 'Why this screen is simple',
  governanceNonGoalBody:
    'It answers one question — is the endpoint up? The workflow is red badge → open the graphs → no data confirms it is genuinely down. That cross-check only works because a gap renders as a gap, so there is deliberately no synthetic health score here.',
  governanceReplicasCaption: {
    one: '{count} replica · standby excluded from availability math',
    other: '{count} replicas · standby excluded from availability math',
  },
  governanceReplicaNoteHealthy: 'Endpoint reports up.',
  governanceReplicaNoteNotResponding: 'Endpoint not answering.',
  governanceReplicaNoteRestartExpected: 'Restart in progress (expected).',
  governanceReplicaNoteInactiveReplica:
    'Up · processing nothing. Excluded from availability math; not a healthy zero.',
  governanceReplicaNoteNotApplicable: 'No health declared for this replica.',
  governanceReplicaNoteUnreachable: 'Host alert — could not be reached. Unknown, not confirmed down.',
  governanceReplicaNoteNotExposed: 'Configuration finding — declared but not exposed for discovery.',
  governanceReplicaNoteMissing: 'Instance alert — declared but not found anywhere it was expected.',
  governanceReplicaNoteBootstrap: 'Declared, not yet applied — awaiting its first restart.',
  governanceConfigStateSectionTitle: 'Configuration state',
  governanceConfigStateCaption: 'five states, never collapsed',
  governanceLogsSectionTitle: 'Logs',
  governanceLogsPackageErrorTitle: 'Couldn’t load components for the log viewer',

  // LogViewer (task 066, Dec `UI-D10` `I6` `I1`, `dynamic-ui` §5.2).
  logViewerNeverPersistedNote: 'Tailed on request — never stored by the platform.',
  logViewerComponentLabel: 'Component',
  logViewerPlacementLabel: 'Placement',
  logViewerLevelLabel: 'Level',
  logViewerStream: 'Stream logs',
  logViewerPause: 'Pause',
  // `mode: live` answers `501`/`422` — not built (`OR-103`, `T9.4`). Only
  // `mode: config` is ever submitted; this is the disabled option's tooltip.
  logViewerLiveModeUnavailable: 'Live mode is not available — changes are staged as configuration and take effect at the next restart.',
  logViewerDebugAuditedChip: 'DEBUG · audited',
  logViewerWaiting: 'Waiting for output…',
  logViewerIdle: 'Press Stream logs to tail this component.',
  logViewerViewportLabel: 'Log output',
  logViewerNoEndpointTitle: 'This component exposes no log endpoint',
  logViewerNoEndpointBody:
    '{component} does not expose a streamable log endpoint, so logs can’t be tailed here. This is a stated degraded condition — not an error, and not an empty stream implying silence.',
  logViewerLoadErrorTitle: 'Couldn’t load the log level for this component',
  logViewerDebugDialogTitle: 'Raise log level to DEBUG',
  logViewerDebugDialogBody:
    'DEBUG logging on a live process is verbose and can affect performance. The change and your identity are written to the audit log. Lower it again when you’re done.',
  logViewerDebugConfirm: 'Raise to DEBUG',
  logViewerDebugOutcomeTitle: 'Log level raised to DEBUG',
  logViewerDebugOutcomeDetail:
    '{component} is emitting DEBUG on a production process — this action is recorded in the audit log.',
  // Announced to a live region on stream start/stop only (task 067) — never
  // per line, which would flood a screen reader.
  logViewerStreamingStartedAnnouncement: 'Streaming logs.',
  logViewerStreamingStoppedAnnouncement: 'Log stream stopped.',

  // DesignerPage (task 123, `REQ-001`/`REQ-006`) — the state router around
  // the ~25 already-built Designer components: package version chip, the
  // load-error and empty-package states. Everything else on screen has its
  // own component and its own copy already.
  designerPackageVersionChip: 'pkg v{version}',
  designerLoadErrorTitle: 'Couldn’t load the configuration model',
  designerEmptyTitle: 'This package declares nothing for this version',
  designerEmptyBody:
    'The Solution Package has no component that applies to version {version}. Check the package file, or declare this instance against a version it actually serves.',
  designerStaleProjectionTitle: 'The configuration model changed',
  designerStaleProjectionReload: 'Reload',
  designerStaleProjectionDetail:
    'The server could not verify this draft against the current package and connection data. Reload the Designer and try again.',
  designerFormView: 'Form view',
  designerYamlView: 'YAML view',
  designerConfigurationViewLabel: 'Configuration view',
  designerYamlTitle: 'YAML configuration',
  designerYamlDescription:
    'Edit the same package-defined configuration shown by the form. Changes are synchronized immediately when the YAML is valid.',
  designerYamlCopy: 'Copy YAML',
  designerYamlPaste: 'Paste YAML',
  designerYamlPasteError: 'Could not read YAML from the clipboard. Paste it into the editor manually.',
  designerYamlAriaLabel: 'YAML configuration editor',
  designerYamlSecretsTitle: 'Sensitive fields omitted',
  designerYamlSecretsDetail:
    '{count} sensitive field(s) are not exported to YAML. They remain unchanged; edit them in Form view.',
  designerYamlInvalidTitle: 'Invalid YAML configuration',

  // The rollout/apply half (task 124, `G11`, `DECISIONS.md` open item 1) —
  // the strategy-picker dialog wrapping `StrategyPicker` before `RestartConfirm`.
  designerApplyStrategyDialogTitle: 'Choose a restart strategy',
  designerApplyDialogContinue: 'Continue',

  // FE-D27 (proposed): never names the missing permission — see
  // src/app/Forbidden.tsx's header for the reasoning.
  forbiddenTitle: 'You don’t have permission to do this',
  forbiddenBody:
    'Solution Manager checked and this action isn’t permitted for your account. Ask an administrator if you believe this is wrong.',
  forbiddenCorrelationId: 'Correlation id: {correlationId}',
  forbiddenBackHome: 'Back to Home',

  // Control registry (task 047, `UI-D14`/`UI-D22`/`UI-D23`/`FE-D23`) — the
  // ten `controlKind -> component` entries every Designer field renders
  // through. `duration`/`list`/`map`/`table`/`yaml` have no earlier
  // component, so their strings live here.
  controlDurationHelper: 'Spring Duration · 120m · PT2H',
  controlListItemLabel: 'Item {index}',
  controlListRemove: 'Remove item {index}',
  controlListAdd: 'Add item',
  controlMapKeyLabel: 'Key {index}',
  controlMapValueLabel: 'Value {index}',
  controlMapRemove: 'Remove entry {index}',
  controlMapAdd: 'Add entry',
  controlTableNoColumns: 'This table’s columns are declared by the Solution Package.',
  controlTableCellLabel: '{column} · Row {index}',
  controlTableRemoveRow: 'Remove row {index}',
  controlTableAddRow: 'Add row',
  controlYamlDisabled: 'The raw YAML editor is disabled in this release.',

  // FieldRow (task 048, `UI-D4`/`UI-D16`/`UI-D17`, Design §10/§18, UI §A.8.4)
  // — the row every control renders inside: discriminator hint, rendered
  // path, Default/Modified, and the validation-message list.
  fieldRowDiscriminatorHint: 'controls fields below',
  fieldRowDiscriminatorTip:
    'Choosing this changes which fields below apply. Values in a hidden branch are kept — switch back and they return.',
  fieldRowDefault: 'Default',
  fieldRowModified: 'Modified',
  fieldRowWarnRecommendation: 'a Solace recommendation, not a hard limit — you can proceed',

  // `UI-D28` connection provenance (task 131-10) — a field bound to a
  // connection shows where its value comes from and an explicit way off
  // it; a bound secret never gets `Override` (`I6` — no value to copy).
  fieldRowFromConnection: 'from {label}',
  fieldRowOverriddenWas: 'overridden · was {label}',
  fieldRowOverride: 'Override',
  fieldRowRevertToConnection: 'Revert to connection',
  fieldRowReplace: 'Replace',

  // SectionCard (task 049, `UI-D15`'s advanced obligation, Design §10,
  // UI §A.8.6) — the accordion header, empty state and advanced-field
  // disclosure toggle every Designer section renders through.
  sectionCardFieldCount: { one: '{count} field', other: '{count} fields' },
  sectionCardAdvancedChip: 'Advanced',
  sectionCardEmpty: 'No fields apply here with the current selections.',
  sectionCardShowAdvanced: {
    one: 'Show {count} advanced field',
    other: 'Show {count} advanced fields',
  },
  sectionCardHideAdvanced: {
    one: 'Hide {count} advanced field',
    other: 'Hide {count} advanced fields',
  },
  sectionCardBlockCount: { one: '{count} blocking issue', other: '{count} blocking issues' },
  sectionCardWarnCount: { one: '{count} warning', other: '{count} warnings' },

  // SectionNav (task 050, Design §10, UI §A.8.7) — the left rail that
  // jumps to a section. Its per-item status text reuses SectionCard's own
  // `sectionCardBlockCount`/`sectionCardWarnCount`/`sectionCardFieldCount`
  // keys rather than duplicating them, so the two never drift.
  sectionNavLabel: 'Sections',
  sectionNavJumpButton: 'Jump to section',

  // FieldSearch (task 051, Design §10/§17, UI §A.8.8) — the debounced
  // cross-section search box and its result/empty copy.
  fieldSearchLabel: { one: 'Search {count} field…', other: 'Search {count} fields…' },
  fieldSearchResultCount: {
    one: '{count} field matches "{query}"',
    other: '{count} fields match "{query}"',
  },
  fieldSearchEmpty: 'No fields match "{query}".',

  // ValidationSummary (task 054, Design §10/§18, UI §A.8.9) — the
  // form-level rollup of every offending field. Reuses `sectionCardBlockCount`
  // *logic* (`groupSectionFields`) but not its strings: this alert's header
  // is its own full sentence per variant rather than a concatenation of
  // fragments (design §18: "no concatenated sentences") — the both-tiers
  // case gets its own secondary sentence (`validationSummaryWarnAlso`)
  // instead of gluing the warn count onto the block header.
  validationSummaryBlockHeader: {
    one: '{count} field must be fixed before saving',
    other: '{count} fields must be fixed before saving',
  },
  validationSummaryWarnHeader: {
    one: '{count} recommendation to review',
    other: '{count} recommendations to review',
  },
  validationSummaryWarnAlso: {
    one: '{count} recommendation also needs review',
    other: '{count} recommendations also need review',
  },

  // SaveBar (task 055, Dec `FE-D17` `FE-D18` clause A `UI-D4`, Design §10.1/§13,
  // UI §A.8 save bar). "Save" and "Apply" are two different things and never
  // collapse (`FE-D18`) — every string below keeps that distinction explicit
  // rather than letting either button's copy blur into the other's.
  saveBarNoChanges: 'No unsaved changes',
  saveBarDirtyCount: { one: '{count} unsaved change', other: '{count} unsaved changes' },
  saveBarBlockingChip: '{count} blocking',
  saveBarRecommendationChip: { one: '{count} recommendation', other: '{count} recommendations' },
  saveBarSaveAction: 'Save draft',
  saveBarSaveTooltip:
    "Writes the draft to the platform's own record and sends the last-seen generation. Writes nothing to the MI.",
  saveBarApplyAction: 'Apply…',
  saveBarApplyTooltip: 'Writes the saved config to the MI. Usually needs a restart to take effect.',
  saveBarApplyDisabledTip:
    'Save the draft first — Apply sends what is currently saved, not what is on screen.',
  saveBarSuccessTitle: 'Draft saved',
  saveBarSuccessDetail:
    'Draft saved to the platform record. This wrote nothing to the MI — use Apply for that.',
  saveBarErrorTitle: 'Could not save the draft',
  saveBarErrorDetail:
    'The platform did not save your changes. Nothing here was overwritten — try again.',

  // ConflictDialog (task 056, Dec `FE-D18` clause A `I6`, Design §10.1/§16,
  // UI §A.8 conflict dialog). The `409` contract specifies no resolution —
  // every string here says so plainly rather than implying one exists.
  conflictDialogTitle: 'Someone else saved first',
  conflictDialogAlertTitle: 'Nothing was overwritten',
  conflictDialogExplain:
    "Another edit landed on this instance's configuration since you loaded it. Your draft was not saved and the current record was not changed — review what's different before deciding what to do next.",
  conflictDialogDiffLabelMine: 'Your draft',
  conflictDialogDiffLabelTheirs: 'Current record',
  conflictDialogOverwriteAction: 'Overwrite anyway',
  conflictDialogTakeTheirsAction: 'Take theirs',
  conflictDialogMergeAction: 'Merge per field',
  conflictDialogNotSpecifiedTooltip:
    'Not specified by the platform — this resolution does not exist yet.',
  conflictDialogRefetchAction: 'Refetch & review',
  conflictDialogRefetchPending: 'Refetching…',
  conflictDialogRefetchErrorTitle: 'Could not refetch the current record',
  conflictDialogRefetchErrorDetail: 'The platform did not return the current record. Try again.',

  // OverrideReasonDialog (task 057, Dec `UI-D16`, Design §10/§16, UI §A.8
  // override reason dialog). Saving with semantic (recommendation-tier)
  // warnings outstanding requires a recorded reason — these are Solace
  // recommendations the MI will accept, not hard limits, so the copy never
  // implies the save itself is blocked by the values.
  overrideReasonDialogTitle: 'Save with recommendations overridden',
  overrideReasonDialogAlertTitle: {
    one: '{count} field outside Solace recommendations',
    other: '{count} fields outside Solace recommendations',
  },
  overrideReasonDialogExplain:
    'These are recommendations, not hard limits — the MI will accept the config. Your reason is recorded in configuration history alongside this change.',
  overrideReasonDialogReasonLabel: 'Reason for override',
  overrideReasonDialogReasonHelp: 'Recorded in configuration history. Minimum a few words.',
  overrideReasonDialogConfirmAction: 'Save & record reason',

  // PackageActions (task 058, Dec `UI-D22` `I6` `FE-D23`, Design §10, UI
  // §A.8 package actions). The package names only a `key` and its `inputs`
  // (design.md §3.5) — never a label — so display copy is keyed off the
  // closed `action.kind` vocabulary here, not trusted from the package.
  packageActionsSectionTitle: 'Actions',
  packageActionsDescription:
    'Runs server-side against {inputs} and returns a verdict — never a credential.',
  packageActionsTestConnectionLabel: 'Test connection',
  packageActionsManageCatalogEntryLabel: 'Manage catalog entry',
  packageActionsRunPending: 'Running…',
  packageActionsErrorDetail: 'The platform could not run this action. Try again.',

  // VersionPill (task 059, Dec `UI-D12`, Design §10/§15, UI §A.8 version
  // pill). Mono caption alongside the state chip — never the only channel
  // stating declared/observed, since `StateChip` already carries the tone.
  versionPillDeclaredOnly: 'decl {declared}',
  versionPillDeclaredAndObserved: 'decl {declared} · obs {observed}',

  // BlockedBanner (task 059, Dec `UI-D12`, Design §10/§15, UI §A.8 blocked
  // banner). `drift`/`unknown` block editing; `unverified` never reaches
  // this component (`UI-D12` — it must not be styled as a fault).
  blockedBannerTitleDrift: 'Editing blocked · version drift',
  blockedBannerTitleUnknown: 'Editing blocked · version unknown',
  blockedBannerReasonWithObserved:
    'Declared {declared} but observed {observed} — the two must match before editing resumes.',
  blockedBannerReasonNoObserved:
    'Declared {declared}, but nothing observed corroborates it — editing resumes once it does.',
  blockedBannerLegibilityNote:
    'The configuration below stays fully legible — this is read-only, not unavailable.',
  blockedBannerAcceptAction: 'Accept the runtime…',
  blockedBannerRestoreAction: 'Restore the runtime…',

  // AcceptRuntimeDialog (task 059, Design §10/§15, UI §A.8 accept runtime;
  // rewired 2026-10-09 against the real `acceptRuntimeConfig` contract —
  // `AcceptedRuntimeConfig.notAccepted[]`, never a rename-mapping diff,
  // which had no contract basis).
  acceptRuntimeDialogTitle: 'Accept the runtime',
  acceptRuntimeDialogExplain:
    'The platform will read back this placement’s configuration file and adopt what it can map as the new declaration. Secret values are never read; any field that could not be mapped is listed below and not changed.',
  acceptRuntimeDialogConfirmAction: 'Accept the runtime',
  acceptRuntimeDialogPending: 'Accepting…',
  acceptRuntimeDialogErrorDetail: 'The platform could not accept the runtime configuration. Try again.',
  acceptRuntimeDialogUnchanged: 'Nothing on the host differs from the current declaration.',
  acceptRuntimeDialogNotAcceptedHeading: 'Not accepted',
  acceptRuntimeDialogNotAcceptedEmpty: 'Every mappable field on the host was accepted.',
  // One line per `NotAcceptedField.reason` (console.yaml's closed enum) — never the field's value (`I6`).
  acceptRuntimeDialogReasonSecretNotRead: '{path} — a secret value, never read back',
  acceptRuntimeDialogReasonUndeclaredKey: '{path} — not a key this package declares',
  acceptRuntimeDialogReasonSmOwnedOverlay: '{path} — owned by Solution Manager, not the operator',
  acceptRuntimeDialogReasonInactiveField: '{path} — not active for this instance’s version',
  acceptRuntimeDialogReasonYamlFieldNotAccepted: '{path} — a yaml-typed field, never accepted from the host',

  // RestoreRuntimeDialog (task 059, Design §10/§15, UI §A.8). High
  // consequence: confirm even when authorised (`FE-D9`). Rewired 2026-10-09
  // against the real `restoreRuntimeConfig` contract — `{placementId,
  // strategy: 'manual'}`, admitted as an apply (`202`), not awaited here.
  restoreRuntimeDialogTitle: 'Restore the runtime',
  restoreRuntimeDialogExplain:
    'This re-applies the current declaration to the host, overwriting whatever is there now. Every replica of this placement restarts and is briefly unavailable.',
  restoreRuntimeDialogConfirmAction: 'Restore {declared}',
  restoreRuntimeDialogPending: 'Restoring…',
  restoreRuntimeDialogErrorDetail: 'The platform could not start the restore. Try again.',

  // ConfigStepper (task 060/065, Dec `SM-D22` `UI-D9`, Design §10.1, UI
  // §A.8/§A.9 config stepper) — the five-rung ladder shared by the
  // Designer rollout card (task 060) and the governance configuration
  // ladder (task 065). Done/current/pending are stated in words on each
  // rung's `aria-label`, not carried by chip colour alone.
  configStepperListLabel: 'Config state',
  configStepperStateDone: 'done',
  configStepperStateCurrent: 'current',
  configStepperStatePending: 'pending',
  configStepperStepAriaLabel: '{label} — {state}',
  configStepperIndeterminateNote:
    'A completed restart reaches restart-observed, not effective-verified: the platform knows the process came back with the file, not that it is using it. Outcome is APPLY_INDETERMINATE.',

  // ReplicaTable (task 060, Design §10.1, UI §A.8 replica table).
  // `generation`/`restartObserved` are invented column names — the
  // contract names them in prose with no row schema (§16 Q12) — so both
  // headers carry an info tip saying that plainly rather than presenting
  // them as specified.
  replicaTableReplicaHeader: 'Replica',
  replicaTableRenderedVersionHeader: 'Rendered config version',
  replicaTableRenderedVersionInfo:
    'Invented column name — the contract names this in prose but defines no row schema for it.',
  replicaTableRestartObservedHeader: 'Restart observed',
  replicaTableRestartObservedInfo:
    "Invented column name — same caveat. 'Observed' means the process came back with the file, not that it is using it.",
  replicaTableHealthHeader: 'Apply result',
  replicaTableHealthInfo:
    "What the apply observed for this replica at the time, not a live health check — it is never re-checked afterward.",
  replicaTableRestartObservedYes: 'Observed',
  replicaTableRestartObservedPending: 'Pending',

  // RolloutCard (task 060, Design §10.1, UI §A.8 rollout card) — the
  // collapsible wrapper pairing `ConfigStepper` with `ReplicaTable`.
  rolloutCardTitle: 'Rollout & replicas',
  rolloutCardDivergedChip: 'Diverged',
  rolloutCardConfigStateHeading: 'Config state',
  rolloutCardPerReplicaHeading: 'Per replica',

  // StrategyPicker (task 061, Dec `SM-D15` `SM-D22` `FE-D18` `FE-D20`,
  // Design §10.1/§16/§25, UI §A.8 strategy picker). Suppression is logic
  // (`strategyAvailability.ts`), not copy — every disabled strategy states
  // its own reason rather than relying on the banner alone.
  strategyPickerHeading: 'Restart strategy',
  strategyPickerUniformBanner:
    'This package requires all replicas to hold identical config, so both rolling strategies are suppressed.',
  strategyPickerDefaultChip: 'Default',
  strategyPickerHighConsequenceChip: 'High-consequence · confirm even when authorized',
  strategyPickerOutageChip: 'Full outage · confirm even when authorized',
  strategyPickerUnavailableChip: 'Unavailable',
  strategyPickerOrderingNote:
    'Default multi-replica ordering is stage everywhere, then restart — a halt leaves config uniform but unapplied, never half-applied.',
  strategyPickerReasonOutOfRelease: 'Out of this release.',
  strategyPickerReasonNoHealthMapping:
    'The package declares no health mapping, so a health gate has nothing to read.',
  strategyPickerReasonRequiresUniform:
    'This instance requires uniform configuration across every replica, so this strategy is unavailable.',

  // RestartConfirm (task 061, Design §10.1 — copy prescribed verbatim).
  // Fixed severity/copy regardless of strategy — the reference's own
  // `RestartConfirm` does not vary by consequence, only by replica count
  // and, when not `manual`, the strategy name.
  restartConfirmTitle: 'Apply requires a restart',
  restartConfirmAlertTitle: {
    one: 'Requires restart · {count} replica · expect unavailability · continue?',
    other: 'Requires restart · {count} replicas · expect unavailability · continue?',
  },
  restartConfirmBodyManual:
    'Writing configuration is not the same as applying it, and applying needs a restart. Each replica is briefly unavailable while it restarts.',
  restartConfirmBodyWithStrategy:
    'Writing configuration is not the same as applying it, and applying needs a restart ({strategyLabel}). Each replica is briefly unavailable while it restarts.',
  restartConfirmObservedCaveat:
    'The console confirms only that it wrote config and observed a restart — never that the process is using it (restart-observed ≠ effective-verified).',
  restartConfirmAction: {
    one: 'Apply & restart {count} replica',
    other: 'Apply & restart {count} replicas',
  },
  restartConfirmPending: 'Applying…',
  restartConfirmErrorDetail: 'The platform could not start the apply. Try again.',

  // ApplyOutcomePanel (task 061, Design §10.1/§16/§25 — "the load-bearing
  // part"). `DIVERGED` is deliberately not rendered here — the remediation
  // banner/diff/rollback flow for `CONFIG_DIVERGED` is task 062's, not
  // this one's, and building it here would be scope this task's own Deps
  // (060, 016, 032) don't cover.
  applyOutcomeIndeterminateTitle: 'Outcome not confirmed',
  applyOutcomeIndeterminateBody:
    'A dp-remote apply has no ordering or idempotency guarantee (open item 1). This did not report success or failure — resolve what actually happened from the observed replica state below, then confirm it.',
  applyOutcomeFailedTitle: 'Apply failed',
  applyOutcomeFailedBody:
    'The backend reports this as a measured outcome, not a guess. Review the reason below, then confirm to clear this request before retrying.',
  applyOutcomeObservedAt: 'Observed {observedAt}',
  applyOutcomeConfirmAction: 'Confirm actual outcome',
  applyOutcomeConfirmedTitle: 'Confirmed by operator',
  applyOutcomeConfirmedBody: 'Confirmed {confirmedAt} from the observed replica state above.',
  applyOutcomeRetryAction: 'Retry apply',
  applyOutcomeRetryHint:
    'An automatic retry would risk a second write with no idempotency key — retry only after reviewing the replica state above.',

  // DivergedBanner (task 062, Dec `B/D12` `SM-D22`, Design §10.1 (R2
  // finding 5), UI §A.8 diverged banner). Exactly three remediations, no
  // fourth — in particular no "force healthy".
  divergedBannerTitle: 'Configuration diverged',
  divergedBannerBody:
    'Replicas are holding different config generations after a halted rollout. Pick one remediation — there is no "force healthy".',
  divergedBannerRestageAction: 'Re-stage everywhere',
  divergedBannerRestartAction: 'Restart the remaining',
  divergedBannerRollbackAction: 'Roll back to last uniform generation…',

  // RollbackDialog (task 062, Design §10.1 R1 finding 5 — a rollback is a
  // config change like any other, no lighter confirmation for being a
  // revert). Copy prescribed verbatim by the reference's own rollback
  // modal.
  rollbackDialogTitle: 'Roll back to the last uniform generation',
  rollbackDialogAlertTitle: 'A rollback is a full change — reviewed like any other',
  rollbackDialogAlertBody:
    'This reverts every replica to {targetGeneration}. It gets no lighter confirmation for being a revert; review the diff first.',
  rollbackDialogGenerationHeading: '{currentGeneration} (current) → {targetGeneration} (target)',
  rollbackDialogDiffLabelCurrent: 'Current · {generation}',
  rollbackDialogDiffLabelTarget: 'Target · {generation}',
  rollbackDialogConfirmAction: 'Roll back & restart all',
  rollbackDialogPending: 'Rolling back…',
  rollbackDialogErrorDetail: 'The platform could not start the rollback. Try again.',

  // Composer (task 063, Dec `UI-D20` `UI-D21` (flagged) `D1` 🔒, Design
  // §10, UI §A.8.18). Three panes for a multi-component solution (File
  // Mesh); Cache stays on the flat single-component form. Node positions
  // are presentation only (`UI-D20`) — excluded from the config hash and
  // never trigger a restart.
  composerViewToggleLabel: 'Composer view',
  composerViewCanvas: 'Composer',
  composerViewList: 'All fields',
  composerPaletteHeading: 'Component types',
  composerPaletteItemHint:
    "Select to view this component's configuration fields. Adding a new instance isn't available in this release.",
  composerPaletteResizeLabel: 'Resize component types panel',
  composerInspectorResizeLabel: 'Resize configuration fields panel',
  composerCanvasHint:
    "Node positions are presentation only — moving a node isn't a config change and won't prompt a restart.",
  composerNodeDragHint: 'Drag to reposition, or focus and use the arrow keys.',
  composerNodeRoleSource: 'Source',
  composerNodeRoleSink: 'Sink',
  composerNodeRoleGeneric: 'Component',
  composerNodeFieldCount: { one: '{count} field', other: '{count} fields' },
  composerNodeRequiredCount: {
    one: '{count} required field empty',
    other: '{count} required fields empty',
  },
  composerNodeOkLabel: 'ok',
  composerInspectorEmpty: 'This component declares no fields.',
  composerCanvasUnavailableReason:
    'The canvas needs more room than this screen provides — showing all fields instead.',

  // Chart accessibility (task 075, `dynamic-metrics` design §18). A canvas
  // chart is otherwise unreachable by keyboard; these strings drive the
  // cursor announcer and the tabular alternative, never a claim that the
  // canvas itself is accessible.
  chartKeyboardHint:
    'Left and right arrow keys move along the time axis, up and down switch the focused series, Enter opens a data table.',
  chartCursorAnnouncement: '{series}, {timestamp}: {value}',
  chartCursorGap: 'gap',
  chartDataTableButtonLabel: 'Open data table',
  chartDataTableTitle: '{metric} — data table',
  chartDataTableSummaryRange: 'Range: {from} to {to} ({zone})',
  chartDataTableSummaryNoRange: 'No data points in range.',
  chartDataTableSeriesCount: '{count} series',
  chartDataTableSeriesStat: 'min {min} · max {max} · last {last}',
  chartDataTableSeriesStatEmpty: 'no data points',
  chartDataTableColumnTimestamp: 'Timestamp',
  chartDataTableColumnValue: 'Value',
  chartDataTableColumnCoverage: 'Coverage',
  chartDataTableLowCoverage: 'Low coverage',
  chartDataTableFullCoverage: 'Full coverage',
  chartDataTableEmptySeries: 'No points rendered for this series in the selected range.',

  // Single-stat and gauge marks (task 076, `DM-D12` `DM-D22`, `dynamic-metrics`
  // §7, UI §A.10.7). Both marks show exactly one number — the shared empty
  // copy is deliberately the same as line rendering's own "no zero-fill"
  // posture: an all-gap range states so, it never shows a 0.
  markNoDataTitle: 'No data in range',
  markNoDataBody: 'The selected range has no rendered point for this metric.',
  gaugeMarkAriaLabel: 'Gauge: {value}',
  singleStatMarkDeltaUp: '▲ {value}',
  singleStatMarkDeltaDown: '▼ {value}',

  // Explore shell and gated control steps (task 077, `DM-D9` `DM-D23`,
  // `dynamic-metrics` §9.1/§11.1, UI §A.10). The five step titles/subtitles
  // mirror the visual reference's own `.ctl-step` labels; the lock-reason
  // strings are this task's own copy, not carried over from the
  // reference (its locked steps render no reason at all — the defect
  // `exploreGating.ts`'s acceptance box exists to fix). Steps 1, 4 and 5
  // stay stated placeholders — the real controls are tasks 079-081. Steps
  // 2 and 3 got theirs in task 078 (`InstanceSelector`, seriesBy toggle).
  exploreTitle: 'Explore',
  // Draft persistence (task 083, Dec `FE-D21`). Stated up front rather than
  // implied away — R1 finding 3 corrected an earlier over-claim that a
  // copied Explore link reproduces the sender's view; it only restores the
  // range and instances, never the exploration itself, until it's saved.
  exploreDraftShareLimitationNote:
    'This link restores your time range and instances in this browser tab, but not the exploration itself. Save as a widget to share this view with someone else.',
  exploreLockReasonMetricNotSelected: 'Pick a metric first.',
  exploreLockReasonMetricNeedsTyping:
    'This metric has no declared type yet — type it before continuing.',
  exploreLockReasonAggregationNotSet: 'Turn on aggregation across instances first.',
  exploreStepMetricTitle: 'Metric',
  exploreStepMetricSubtitle: 'single-select',
  exploreStepInstancesTitle: 'Instances',
  exploreStepInstancesSubtitle: 'to a cap',
  exploreStepSeriesByTitle: 'Series by',
  exploreStepAggregationTitle: 'Aggregation',
  exploreStepGroupByTitle: 'Group by',
  exploreStepGroupBySubtitle: 'needs aggregation',
  exploreSeriesByInstance: 'Instance',
  exploreSeriesByReplica: 'Replica',

  // Explore result states (task 082, Dec `DM-D16` `FE-D10` `FE-D20`
  // `SM-D27`, `dynamic-metrics` §5.8/§11.2, UI §A.10). Copy for the rows
  // this task itself renders — gaps/type-suspect/pending-config-change/
  // inactive-replica/unverified-durability are already carried by
  // `ChartRenderer` (tasks 073/074) and `MetricSelector` (task 079); see
  // `ExploreResult.tsx`'s own header for why those rows need no new copy
  // here.
  exploreResultEmptyMetricTitle: 'Select a metric',
  exploreResultEmptyMetricBody: 'Nothing is plotted until a metric is chosen in step 1.',
  exploreResultEmptyInstancesTitle: 'Select at least one instance',
  exploreResultEmptyInstancesBody: 'Nothing is plotted until you choose what to compare.',
  exploreResultLoadingAriaLabel: 'Loading chart',
  exploreResultRefreshingAriaLabel: 'Refreshing chart',
  exploreResultNoDataTitle: 'No samples in this range',
  exploreResultNoDataBody:
    'Served tier: {served} · bucket {bucketSeconds}s. Nearest data is outside the selected window.',
  exploreResultPartialTitle: 'Partial data — some instances did not resolve',
  exploreResultPartialResidualNote:
    'The shape of this list and its reason text are not specified in the contract — shown as returned.',
  exploreResultRetentionTruncated:
    'Data before {time} is outside the retention window; the chart starts at the earliest retained point.',
  exploreResultErrorTitle: 'The request failed',
  exploreResult422Title: 'Request refused — not retried',
  exploreResult422ResidualNote:
    'The shape of this error body is not specified in the contract — shown as returned.',
  exploreChartAriaLabel: 'Chart: {metric}',
  exploreSaveWidgetTrigger: 'Save as widget',
  exploreSaveWidgetDisabledTip: 'Select a metric and at least one instance first.',
  exploreSaveWidgetSucceededTitle: 'Widget saved',
  exploreSaveWidgetSucceededDetail: '"{title}" was added to this instance’s dashboard.',

  // SaveWidgetDialog (task 084, Dec `DM-D6` `DM-D12` `DM-D21` `SM-D31`,
  // `dynamic-metrics` §7, Conflict C7). Only `title` is editable — no
  // axes/legend/threshold/color editor exists anywhere yet (§11.1 step 3
  // has no `FE-*` task), so the rest of the draft renders as a read-only
  // summary of what Explore already resolved. No "create alert" affordance
  // (`DM-D15`) — omitted, not stated-and-shown, per this task's own text.
  saveWidgetDialogTitle: 'Save as widget',
  saveWidgetDialogIntro:
    'This widget is added to this instance’s dashboard, in the operator layer.',
  saveWidgetDialogDevNote:
    'The route this saves to, and which dashboard it targets, are invented — the widget-persistence contract does not exist yet (§16 Q15).',
  saveWidgetDialogTitleFieldLabel: 'Widget title',
  saveWidgetDialogTitleRequired: 'A title is required.',
  saveWidgetDialogSummaryMetric: 'Metric',
  saveWidgetDialogSummaryMark: 'Mark',
  saveWidgetDialogSummaryInstances: 'Instances',
  saveWidgetDialogSummaryRange: 'Range (default — a dashboard overrides it)',
  saveWidgetDialogSummaryResolution: 'Resolution',
  saveWidgetDialogScopeLabel: 'Scope',
  saveWidgetDialogScopeValue: 'This instance',
  saveWidgetDialogLayerLabel: 'Layer',
  saveWidgetDialogLayerValue: 'Shared (operator)',
  saveWidgetDialogScopeNote:
    'v1 saves to this instance’s shared layer only — entity, organization and personal scopes are not built.',
  saveWidgetDialogSubmit: 'Save widget',
  saveWidgetDialogSubmitting: 'Saving…',
  saveWidgetDialogFailedTitle: 'Save failed',

  // DashboardToolbar / DashboardView (task 085, Dec `DM-D17` `DM-D21`,
  // `dynamic-metrics` §9.1/§14).
  dashboardToolbarRefreshLabel: 'Refresh',
  dashboardToolbarRefreshOff: 'Off',
  dashboardToolbarRefresh10s: '10s',
  dashboardToolbarRefresh30s: '30s',
  dashboardToolbarRefresh1m: '1m',
  dashboardToolbarRefresh5m: '5m',
  dashboardToolbarRefreshDisabledNote:
    'Refresh is disabled for a fixed range — there is nothing new to fetch.',
  dashboardToolbarRefreshFlooredNote:
    'Flooring to {seconds}s — polling faster than the tier this range would serve just re-fetches the same bucket.',
  dashboardToolbarRefreshPausedNote: 'Refresh paused — this tab is hidden.',
  dashboardToolbarLayerLabel: 'Layer',
  dashboardToolbarLayerAll: 'All layers',
  dashboardToolbarLayerProductDefault: 'Package defaults',
  dashboardToolbarLayerShared: 'Shared',

  dashboardViewTitle: 'Dashboard',
  // Task 130 — the Monitoring sub-tab bar (reference `.itabs`). Plural
  // "Dashboards" deliberately differs from `dashboardViewTitle`: the tab
  // names the section, the heading names the one dashboard on screen.
  monitoringTabsAriaLabel: 'Monitoring sections',
  monitoringTabDashboards: 'Dashboards',
  monitoringTabExplore: 'Explore',
  dashboardViewLandingNote:
    'Monitoring renders the package’s default dashboard directly. A dashboards index isn’t assumed — the contract has no list route.',
  dashboardToolbarAuthoritativeNote:
    'The dashboard’s range and refresh are authoritative for every widget — a widget’s own settings are defaults it overrides. There is deliberately no per-widget time pinning.',
  dashboardViewErrorTitle: 'Dashboard failed to load',
  dashboardViewEmptyTitle: 'No widgets saved yet',
  dashboardViewEmptyBody: 'Build your first widget from Explore, then save it to this dashboard.',
  dashboardViewEmptyAction: 'Open Explore',
  dashboardViewLayerProductDefault: 'package',
  dashboardViewLayerShared: 'shared',

  // MonitoringFirstRun (task 089, Dec `DM-D9`, UI §A.11.6)
  monitoringFirstRunTitle: 'No dashboards saved yet',
  monitoringFirstRunBody:
    'The empty console does not show an empty grid. Start from the entity types below and open Explore to build your first view.',
  monitoringFirstRunReplicasLabel: 'replicas',
  monitoringFirstRunMetricsLabel: 'metrics',
  monitoringFirstRunAction: 'Open Explore',

  // DashboardGrid / WidgetFrame (task 086, Dec `DM-D6` `FE-D20`, Design
  // §12/§17, `dynamic-metrics` §8/§17, UI §A.11).
  dashboardGridEditOn: 'Edit layout',
  dashboardGridEditOff: 'Done',
  dashboardGridResetDefaults: 'Reset defaults',
  // Task 131 — the explicit front door to Explore from a populated board.
  dashboardAddWidget: 'Add widget',
  widgetFrameMenuAriaLabel: 'Widget menu',
  widgetFrameMenuEditPendingNote: 'Editing a widget (via Explore) lands in a following task.',
  widgetFrameMenuHide: 'Hide (keep default)',
  widgetFrameOrderTooltip: 'Reading order (accessibility) — separate from visual position',
  widgetFrameMoveEarlier: 'Move earlier',
  widgetFrameMoveLater: 'Move later',
  widgetFrameSchemaTooNewTitle: 'Read-only',
  widgetFrameSchemaTooNewBody:
    'This widget’s schema (v{declared}) is newer than this console (v{current}). It renders read-only and whole — never partially — and unknown fields are preserved on save.',
  widgetFrameQueryErrorTitle: 'This widget failed to load',
  widgetFrameRefreshingAriaLabel: 'Refreshing widget',
  widgetFrameLoadingAriaLabel: 'Loading widget',
  widgetFrameChartAriaLabel: '{title} ({metric})',

  // Export CSV (task 088, Dec `DM-D14` `DM-D18`, Design §13/§15,
  // `dynamic-metrics` §5.8). Gated on `capabilities.features.streamingExport`
  // alone, never on the `metrics.export` verb — that verb is `proposed`, and
  // C12 forbids gating a v1 feature on a non-grantable verb (see the
  // component's own `TODO(verb)`).
  widgetFrameMenuExportCsv: 'Export CSV',
  widgetFrameExportConfirmTitle: 'Export widget as CSV?',
  widgetFrameExportConfirmBody:
    'A streaming export can strain the deployment. The file streams directly to disk for the range currently shown on this widget.',
  widgetFrameExportConfirmAction: 'Start export',

  // HiddenDefaultWidget (task 087, Dec `SM-D31` `DM-D18`, Design §12,
  // Conflict C7). A `product-default` widget is hidden, never deleted —
  // this card is what a hidden slot renders instead of the live widget.
  hiddenDefaultWidgetTitle: 'Hidden default · {title}',
  hiddenDefaultWidgetBody:
    'Hidden, not deleted — a later package can still improve this default and your choice survives.',
  hiddenDefaultWidgetRestore: 'Restore',

  // InstanceSelector (task 078, `DM-D7`, `dynamic-metrics` §11.4, UI
  // §A.10.3). "N sr" abbreviates "N series" — carried over from the
  // reference's own row label, kept terse because it repeats once per row.
  instanceSelectorProjectionSuffix: '/ {cap} series projected',
  instanceSelectorPerInstance: 'per instance',
  instanceSelectorPerReplica: 'per replica',
  instanceSelectorRowSeriesCount: '{count} sr',
  instanceSelectorCapReachedTitle: 'Series cap reached — two ways forward',
  instanceSelectorNarrowByLabel: 'Narrow by label',
  instanceSelectorAggregateIntoOneLine: 'Aggregate into one line',

  // AggregationControl and GroupByControl (task 080, `DM-D8` `DM-D22`,
  // `dynamic-metrics` §5.7). Option copy mirrors the reference's own
  // `<option>` text. The reference's own `<select>` has no visible field
  // label at all; `fieldLabel` gives it a real one distinct from the step
  // header's own "Aggregation" text one line above — reusing that exact
  // word would print it twice in the same step, which duplicate-text a11y
  // checks would (rightly) flag as confusing, not merely redundant.
  aggregationControlFieldLabel: 'Across instances',
  aggregationControlNoneLabel: 'Across instances: none (one line each)',
  aggregationControlSumLabel: 'Across instances: sum',
  aggregationControlAvgLabel: 'Across instances: average',
  aggregationControlMinLabel: 'Across instances: minimum',
  aggregationControlMaxLabel: 'Across instances: maximum',
  aggregationControlForcedCaption:
    'A {mark} shows one number — aggregation is forced to a single value.',
  aggregationControlHistogramUnsupportedCaption: 'Histogram aggregation is not supported yet.',
  groupByControlNoLabelsCaption: 'This metric has no label keys to group by.',

  // TimeRangePicker (task 081, `DM-D10` `B/D15` 🔒, `dynamic-metrics` §5.4).
  // Preset labels mirror the reference's own "Last …" text, baked per key
  // rather than interpolated — same convention `AggregationControl` set
  // for its own option set.
  timeRangePickerFieldLabel: 'Time range',
  timeRangePickerPreset15m: 'Last 15m',
  timeRangePickerPreset1h: 'Last 1h',
  timeRangePickerPreset6h: 'Last 6h',
  timeRangePickerPreset24h: 'Last 24h',
  timeRangePickerPreset7d: 'Last 7d',
  timeRangePickerPreset30d: 'Last 30d',
  timeRangePickerPreset90d: 'Last 90d',
  timeRangePickerPreset6mo: 'Last 6mo',
  timeRangePickerCustomLabel: 'Custom range',
  timeRangePickerFromLabel: 'From',
  timeRangePickerToLabel: 'To',

  // ResolutionControl (task 081, `SM-D31`, `dynamic-metrics` §5.6/§9.1).
  // `Raw` is gated on `features.forceRaw` alone, never on the
  // `metrics.forceRaw` verb — that verb is `proposed`, and C12 forbids
  // gating a v1 feature on a non-grantable verb (see the component's own
  // `TODO(verb)`).
  resolutionControlAriaLabel: 'Resolution',
  resolutionControlAutoLabel: 'Auto',
  resolutionControlRawLabel: 'Raw',
  resolutionControlRawConfirmTitle: 'Switch to Raw resolution?',
  resolutionControlRawConfirmBody:
    'A forced-Raw query over a long range can strain the deployment. Auto resolution is downsampled server-side and is the safe default for most investigations.',
  resolutionControlRawConfirmAction: 'Switch to Raw',

  // MetricSelector, needs-typing, type-metric dialog (task 079, `DM-D23`
  // `SM-D30` `UI-D5`, `dynamic-metrics` §5.5/§11.2, UI §A.10). Copy states
  // the single-select-replaces rule and the no-suggested-type limitation
  // explicitly, rather than leaving either silently implied.
  metricSelectorLayerPkg: 'pkg',
  metricSelectorLayerExt: 'ext',
  metricSelectorStatePlottable: 'plottable',
  metricSelectorStateNeedsTyping: 'needs typing',
  metricSelectorStateTypeSuspect: 'type-suspect',
  metricSelectorTypeAction: 'Type…',
  metricSelectorSingleSelectNote:
    'Selecting a different metric replaces this one — it never adds a second series group. To compare two metrics, save this as a widget and build a second one beside it.',
  metricSelectorTypedCaption: 'type {type} · unit {unit}',
  metricSelectorTypeSuspectTitle: 'Type-suspect — plotted, never auto-corrected',
  typeMetricDialogTitle: 'Type {metric}',
  typeMetricDialogForbiddenNotice:
    'This metric arrived unclassified. Inferring a type is forbidden — you must declare it. Until then it can’t be plotted.',
  typeMetricDialogTypeLabel: 'Metric type',
  typeMetricDialogOptionCounter: 'counter',
  typeMetricDialogOptionGauge: 'gauge',
  typeMetricDialogOptionHistogram: 'histogram',
  typeMetricDialogUnitLabel: 'Unit',
  typeMetricDialogUnitHelp:
    'Free text — there is no unit vocabulary anywhere in the contract, so this control has no list to offer.',
  typeMetricDialogNoSuggestionNote:
    'The catalog entry carries neither a first-seen timestamp nor a suggested-type field, so none is offered here — a stated limitation, not an omission (§16 Q14).',
  typeMetricDialogSaveAction: 'Save type',
  typeMetricDialogPending: 'Saving…',
  typeMetricDialogErrorDetail: 'The type could not be saved. Try again.',
  typeMetricDialogDeferredDetail:
    'Declaring a type is not available yet — quarantine and typing are deferred in this release. The metric stays undeclared.',

  // AdminShell (task 090, Req `REQ-006`, Dec `D10` 🔒 `DM-D18`, Design
  // §14/§15, UI §A.12). Every tab now renders its real view (FE-091–FE-094).
  adminShellTitle: 'Admin',
  adminBadgeLaterIncrement: 'Later increment',
  adminBadgeNotInNav: 'Not in v1 nav',
  adminFindingsToggle: 'Findings',
  adminFindingsBannerTitle: 'The model is locked. The catalog and identity are not.',
  adminFindingsBannerBody:
    'The initial D10 operation catalog is grantable here, and is pending architect-group ratification. Adding a new operation is still a policy change: define its scope and server-side deny-by-default check, then amend the catalog — so the permission catalog is policy-as-code, and there is deliberately no catalog editor here.',
  adminTabRoles: 'Roles',
  adminTabAssignments: 'Assignments',
  adminTabAccess: 'Access check',
  adminTabUsers: 'Users',

  // Roles + RoleEditor (task 091, `D10` 🔒, UI §A.12). Built-in roles are
  // read-only and cloneable; the permission list marks high-consequence
  // and two-sided verbs and renders each verb's catalog status. The verb
  // catalog is unratified (§16 Q4) — the copy says so rather than hiding
  // it.
  adminRolesHeading: 'Role definitions',
  adminRolesSubheading:
    'A role is a permission set with a name. Assignment is a separate screen — a role never references a principal.',
  adminRolesNewCustomRole: 'New custom role',
  adminRolesBuiltInSection: 'Built-in · read-only, cloneable',
  adminRolesCustomSection: 'Custom',
  adminRolesErrorTitle: 'Roles could not be loaded',
  adminRoleKindBuiltIn: 'built-in',
  adminRoleKindCustom: 'custom',
  adminRoleCardAllPerms: 'all permissions',
  adminRoleCardPermCount: { one: '{count} permission', other: '{count} permissions' },
  adminRoleCardInclHighConsequence: 'includes high-consequence',
  adminRoleHighConsequence: 'high-consequence',
  adminRoleTwoSided: 'two-sided',
  adminRoleStatusGrantableLabel: 'grantable',
  adminRoleStatusGrantableTip: 'In the ratified catalog — can be granted today.',
  adminRoleStatusProposedLabel: 'proposed · not grantable',
  adminRoleStatusProposedTip:
    'Named, but the catalog has not been amended — so it cannot be granted yet.',
  adminRoleStatusAbsentLabel: 'candidate · absent',
  adminRoleStatusAbsentTip:
    'Not in the catalog at all — a placeholder name so the screen has something to lay out.',
  adminRoleEditorTitleInspect: 'Inspect role',
  adminRoleEditorTitleEdit: 'Edit role',
  adminRoleEditorTitleClone: 'Clone role',
  adminRoleEditorTitleCreate: 'New custom role',
  adminRoleEditorBuiltInTitle: 'Built-in roles can’t be edited',
  adminRoleEditorBuiltInBody:
    'They’re fully inspectable, and cloneable — “the built-in one but without restart” is the common real request.',
  adminRoleEditorNameLabel: 'Role name',
  adminRoleEditorDescriptionLabel: 'Description',
  adminRoleEditorClonedFrom: 'Cloned from {source}.',
  adminRoleEditorPermissionsHeading: 'Permissions · grouped by resource',
  adminRoleEditorSelectedCount: { one: '{count} selected', other: '{count} selected' },
  adminRoleEditorGroupAll: 'All',
  adminRoleEditorGroupClear: 'Clear',
  adminRoleEditorCloneAction: 'Clone…',
  adminRoleEditorSaveAction: 'Save role',
  adminRoleEditorSavePending: 'Saving…',
  adminRoleEditorSaveError: 'The role could not be saved. Try again.',
  adminRoleCloneNameSuffix: '{name} (copy)',
  adminRoleSavedToast: 'Role “{name}” saved',
  adminRoleSavedToastDetail: { one: '{count} permission.', other: '{count} permissions.' },
  adminRoleCatalogFindingTitle: 'Initial catalog enabled',
  adminRoleCatalogFindingBody:
    'Every operation in the initial D10 catalog can be assigned to a role. Adding a new operation is a policy and server-enforcement change — this layout does not move.',

  // FE-092 · Assignments and ScopeTree (D10, SM-D23, UI §A.12)
  adminAssignmentsHeading: 'Assignments',
  adminAssignmentsSubheading: 'Who can do what, where — the triple (principal, role, scope).',
  adminAssignmentsErrorTitle: 'Assignments could not be loaded',
  adminAssignmentsNew: 'New assignment',
  adminAssignmentsDirLabel: 'View assignments by',
  adminAssignmentsByPrincipal: 'By principal',
  adminAssignmentsByScope: 'By scope',
  adminAssignmentsColPrincipal: 'Principal',
  adminAssignmentsColRole: 'Role',
  adminAssignmentsColScope: 'Scope',
  adminAssignmentsSystemManaged: 'System-managed',
  adminAssignmentsSystemManagedHint:
    'Granted across all organizations at installation (D19). The console can inspect these but never creates one.',
  adminAssignmentsEmpty: 'No assignments yet.',
  adminAssignmentsPickScope: 'Pick a scope',
  adminAssignmentsReachHeading: 'Who can reach {scope}',
  adminAssignmentsNoneReach:
    'No assignment reaches this scope. Deny-by-default: every action here is denied.',
  adminAssignmentsGrantDirect: 'direct',
  adminAssignmentsGrantInherited: 'inherited',
  adminAssignmentsGrantedAt: '{role} · granted at {scope}',
  adminAssignmentsInheritNote:
    'Inherited rows come from an assignment higher in the tree. Removing one changes access here — showing it as “direct” is how someone deletes an assignment and is surprised nothing changed.',
  adminPrincipalKindUser: 'user',
  adminPrincipalKindGroup: 'group',
  adminPrincipalIdpGroup: 'IdP group',
  adminScopeTreeExpand: 'Expand {name}',
  /** The empty scope path — a grant that reaches every organization (`FE-D46`). */
  adminScopeAllOrganizations: 'All organizations',
  adminScopeTreeCollapse: 'Collapse {name}',
  adminNewAssignmentTitle: 'New assignment',
  adminNewAssignmentPrincipalLabel: 'Principal',
  adminNewAssignmentPrincipalHint: 'user or group — the same picker',
  adminNewAssignmentPrincipalPlaceholder: 'Select a principal…',
  adminNewAssignmentRoleLabel: 'Role',
  adminNewAssignmentRolePlaceholder: 'Select a role…',
  adminNewAssignmentRoleBuiltInSuffix: '{name} (built-in)',
  adminNewAssignmentScopeLabel: 'Scope',
  adminNewAssignmentScopeHint: 'grants here and everything below',
  adminNewAssignmentSelected: 'Selected: {scope}',
  adminNewAssignmentSubmit: 'Create assignment',
  adminNewAssignmentTwoSided:
    'This role grants target.use / instance.bindTarget. Placing an instance on a target needs a permission on both the instance’s scope and the target’s ownership_scope — the one two-sided rule.',
  adminNewAssignmentCreatedToast: 'Assignment created',
  adminNewAssignmentCreatedDetail: '{role} → {principal} at {scope}.',
  adminNewAssignmentError: 'The assignment could not be created. Try again.',

  // Access check — effective permissions (task 093, `D10` 🔒, UI §A.12).
  // "Why was I denied?" — deny-by-default is only operable if it can be
  // debugged, so every grant names the assignment that conferred it and
  // an inherited grant is never shown as direct. The server is the
  // authority (design §15); this screen only renders what it returns.
  adminAccessHeading: 'Effective permissions',
  adminAccessSubheading:
    'Given a principal and a scope: exactly what they have, and which assignment conferred it. The screen for “why was I denied?”',
  adminAccessPrincipalLabel: 'Principal',
  adminAccessScopeLabel: 'At scope',
  adminAccessAtScope: 'at',
  adminAccessColPermission: 'Permission',
  adminAccessColConferredBy: 'Conferred by',
  adminAccessGrantDirect: 'direct',
  adminAccessGrantInherited: 'inherited',
  adminAccessGrantedAt: 'at {scope}',
  adminAccessNoneTitle: 'No permissions here',
  adminAccessNoneBody:
    'Deny-by-default: with no assignment reaching this scope, every action is denied.',
  adminAccessErrorTitle: 'The access check could not be loaded',
  adminAccessGrantsErrorTitle: 'Effective permissions could not be computed',
  adminAccessDenialExplained:
    'A permission that isn’t granted is denied; one that doesn’t exist can’t be granted at all.',
  adminAccessDenialExample:
    'Example: {permission} is denied here — no assignment for this principal confers it.',

  // Users — local accounts (task 094, Dec `D13` 🔒 deferred, `FE-D38`,
  // §16 Q5, UI §A.12). A stopgap an IdP will replace: deactivate never
  // delete, so history stays attributable; creating a user does not
  // assign a role (identity and authorization are separate screens on
  // purpose). `FE-D38` picks the admin-set temporary credential path.
  adminUsersHeading: 'Users',
  adminUsersSubheading: 'Local accounts — a stopgap that an identity provider will replace.',
  adminUsersErrorTitle: 'Users could not be loaded',
  adminUsersAdd: 'Add user',
  adminUsersIdpBannerTitle: 'Everything on this screen is a stopgap — an IdP will replace it',
  adminUsersIdpBannerBody:
    'When identities come from a customer’s IdP this becomes a read-only list the provider supplies: Add user and Deactivate move to the provider and disappear here; the list itself survives. Assignments — a separate screen — are untouched.',
  adminUsersColUser: 'User',
  adminUsersColOrganization: 'Organization',
  adminUsersColStatus: 'Status',
  adminUsersColLastSignIn: 'Last sign-in',
  adminUsersColCreatedBy: 'Created by',
  adminUsersColActions: 'Actions',
  adminUsersStatusActive: 'Active',
  adminUsersStatusDeactivated: 'Deactivated',
  adminUsersDeactivate: 'Deactivate',
  adminUsersBootstrapChip: 'Bootstrap admin',
  adminUsersLastBootstrapAdminReason:
    'The last active bootstrap super admin cannot be deactivated \u2014 an installation with no active root administrator cannot be recovered from this console.',
  adminUsersBootstrapBackendNote:
    'Password hashing, forced replacement at first sign-in, the audit record and session invalidation are install-time and backend responsibilities. This console never sees a credential.',
  adminUsersReactivate: 'Reactivate',
  adminUsersNeverSignedIn: 'never',
  adminUsersNoDeleteNote:
    'Deactivate, never delete — an account that authored configuration history stays attributable.',
  adminUsersBootstrapNote:
    'The bootstrap super admin is created directly in the database at installation and holds the super-administrator role across all organizations (D19). This console never creates one, and the last active one cannot be deactivated here.',
  adminAddUserTitle: 'Add a user',
  adminAddUserNameLabel: 'Full name',
  adminAddUserEmailLabel: 'Email',
  adminAddUserOrganizationHint:
    'Which organization the account belongs to. It decides which groups the account may join — it does not grant anything on its own.',
  adminAddUserNoRoleNote:
    'Creating a user does not assign a role — identity and authorization are separate screens, on purpose, so identity can become IdP-backed later without touching assignments.',
  adminAddUserCredentialHeading: 'Credential',
  adminAddUserCredentialLegend: 'How the first credential reaches the user',
  adminAddUserCredTempLabel: 'Admin-set temporary credential',
  adminAddUserCredTempDesc:
    'Forced change on first sign-in. No external dependency — works air-gapped.',
  adminAddUserCredTempBadge: 'chosen',
  adminAddUserCredInviteLabel: 'Emailed invitation link',
  adminAddUserCredInviteDesc:
    'Cleanest UX, but introduces an email capability this console does not have.',
  adminAddUserCredInviteBadge: 'needs email',
  adminAddUserCredOobLabel: 'Out-of-band handoff',
  adminAddUserCredOobDesc: 'Communicate the credential yourself. Low-tech, no dependency.',
  adminAddUserSubmit: 'Create user',
  adminAddUserSubmitPending: 'Creating…',
  adminAddUserError: 'The user could not be created. Try again.',
  adminAddUserCreatedToast: 'User {name} created',
  adminAddUserCreatedDetailTemp:
    'A temporary credential is set — they must change it on first sign-in. Assign a role separately on the Assignments screen.',
  adminAddUserCreatedDetailInvite:
    'An invitation will be emailed. Assign a role separately on the Assignments screen.',
  adminAddUserCreatedDetailOob:
    'Hand the credential over out of band. Assign a role separately on the Assignments screen.',

  // ── Groups (Groups management, `D10` 🔒, `SM-D23`, `FE-D46`) ──────────
  // Local groups: a named collection of local users that holds role
  // assignments, so an administrator grants once rather than N times. A
  // group is owned by exactly ONE organization — Organization →
  // Sub-organization → Environment; Organization is the top isolation level.
  adminTabGroups: 'Groups',
  adminGroupsHeading: 'Groups',
  adminGroupsSubheading: 'Manage user groups and assign role-based access collectively.',
  adminGroupsAdd: 'Add group',
  adminGroupsErrorTitle: 'Groups could not be loaded',
  adminGroupsIdpBannerTitle: 'Local groups are a temporary identity-management capability',
  adminGroupsIdpBannerBody:
    'When an identity provider supplies groups, externally managed groups and memberships will become read-only.',
  adminGroupsSearchLabel: 'Search groups',
  adminGroupsFilterOrganization: 'Organization',
  adminGroupsFilterOrganizationAll: 'All organizations',
  adminGroupsFilterStatus: 'Status',
  adminGroupsFilterStatusAll: 'All',
  adminGroupsFilterStatusActive: 'Active',
  adminGroupsFilterStatusDeactivated: 'Deactivated',
  adminGroupsColName: 'Group name',
  adminGroupsColDescription: 'Description',
  adminGroupsColOrganization: 'Organization',
  adminGroupsColMembers: 'Members',
  adminGroupsColAssignments: 'Role assignments',
  adminGroupsColStatus: 'Status',
  adminGroupsColCreatedBy: 'Created by',
  adminGroupsColUpdated: 'Last updated',
  adminGroupsColActions: 'Actions',
  adminGroupsRowActions: 'Actions for {name}',
  adminGroupsActionView: 'View',
  adminGroupsActionEdit: 'Edit',
  adminGroupsActionMembers: 'Manage members',
  adminGroupsActionAssignments: 'Manage role assignments',
  adminGroupsActionDeactivate: 'Deactivate',
  adminGroupsActionReactivate: 'Reactivate',
  adminGroupsStatusActive: 'Active',
  adminGroupsStatusDeactivated: 'Deactivated',
  adminGroupsSourceIdp: 'IdP-managed',
  adminGroupsSourceLocal: 'Local',
  adminGroupsNoDescription: 'No description',
  adminGroupsEmptyTitle: 'No groups yet',
  adminGroupsEmptyBody:
    'A group lets you grant a role once to a set of people instead of once per person. Add a group to start.',
  adminGroupsNoResultsTitle: 'No groups match these filters',
  adminGroupsNoResultsBody:
    'Nothing matches the current search, organization and status. Clear the filters to see every group.',
  adminGroupsClearFilters: 'Clear filters',
  adminGroupsNoDeleteNote:
    'Deactivate, never delete — a group that held access stays attributable in the audit trail, and its historical assignments remain readable.',
  adminGroupsMemberCount: { one: '{count} member', other: '{count} members' },
  adminGroupsAssignmentCount: { one: '{count} assignment', other: '{count} assignments' },
  adminGroupsDeactivateTitle: 'Deactivate {name}?',
  adminGroupsDeactivateBody:
    'Members stop receiving every permission this group confers, immediately. The group, its members and its assignments stay on record and can be reactivated.',
  adminGroupsDeactivateConfirm: 'Deactivate group',
  adminGroupsReactivateTitle: 'Reactivate {name}?',
  adminGroupsReactivateBody:
    'Every member regains the permissions this group\u2019s active role assignments confer.',
  adminGroupsReactivateConfirm: 'Reactivate group',
  adminGroupsDeactivatedToast: 'Group {name} deactivated',
  adminGroupsReactivatedToast: 'Group {name} reactivated',
  adminGroupsStatusError: 'The group\u2019s status could not be changed. Try again.',

  // Create / edit form
  adminGroupCreateTitle: 'Add a group',
  adminGroupEditTitle: 'Edit group',
  adminGroupNameLabel: 'Group name',
  adminGroupDescriptionLabel: 'Description',
  adminGroupDescriptionHint: 'Optional. What this group is for, in one line.',
  adminGroupOrganizationLabel: 'Organization',
  adminGroupOrganizationHint:
    'The group belongs to one organization and can only be granted inside it.',
  adminGroupOrganizationLockedHint:
    'Set at creation and not editable — changing it would invalidate every member and every role assignment.',
  adminGroupMembersLabel: 'Members',
  adminGroupMembersHint: 'Only active users of the selected organization can be added.',
  adminGroupMembersPickOrganizationFirst: 'Choose an organization first.',
  adminGroupMembersNoneAvailable: 'No active users in this organization yet.',
  adminGroupMembersDropped: {
    one: '{count} selected member is not in {organization} and was removed from the selection.',
    other:
      '{count} selected members are not in {organization} and were removed from the selection.',
  },
  adminGroupNameRequired: 'A group name is required.',
  adminGroupNameTooLong: 'Keep the name to {max} characters or fewer.',
  adminGroupNameInvalidChars:
    "Use letters, numbers, spaces and ( ) . _ - & ' only. A name is read back in audit lines, so /, \\ and : are not allowed.",
  adminGroupNameWhitespace: 'A group name cannot begin or end with a space.',
  adminGroupNameDuplicate: 'A group called this already exists in {organization}.',
  adminGroupDescriptionTooLong: 'Keep the description to {max} characters or fewer.',
  adminGroupOrganizationRequired: 'An organization is required.',
  adminGroupCreateSubmit: 'Create group',
  adminGroupCreateSubmitPending: 'Creating\u2026',
  adminGroupEditSubmit: 'Save changes',
  adminGroupEditSubmitPending: 'Saving\u2026',
  adminGroupCreateError: 'The group could not be created. Try again.',
  adminGroupEditError: 'The group could not be saved. Try again.',
  adminGroupCreatedToast: 'Group {name} created',
  adminGroupCreatedDetail: 'Owned by {organization}. Assign it a role to grant anything.',
  adminGroupUpdatedToast: 'Group {name} saved',
  adminGroupUpdatedDetail: 'Name, description and membership updated.',
  adminGroupIdpReadOnly:
    'This group is supplied by the identity provider. Its name and membership are read-only here.',

  // Details
  adminGroupDetailsTitle: '{name}',
  adminGroupDetailsOverview: 'Overview',
  adminGroupDetailsMembers: 'Members',
  adminGroupDetailsAssignments: 'Role assignments',
  adminGroupDetailsErrorTitle: 'The group could not be loaded',
  adminGroupFieldName: 'Group name',
  adminGroupFieldDescription: 'Description',
  adminGroupFieldOrganization: 'Organization',
  adminGroupFieldStatus: 'Status',
  adminGroupFieldSource: 'Source',
  adminGroupFieldCreatedBy: 'Created by',
  adminGroupFieldCreatedAt: 'Created',
  adminGroupFieldUpdatedBy: 'Last updated by',
  adminGroupFieldUpdatedAt: 'Last updated',
  adminGroupFieldUnknown: '\u2014',
  adminGroupMembersColUser: 'User',
  adminGroupMembersColEmail: 'Email',
  adminGroupMembersColStatus: 'User status',
  adminGroupMembersColAddedAt: 'Date added',
  adminGroupMembersColAddedBy: 'Added by',
  adminGroupMembersColActions: 'Actions',
  adminGroupMembersAdd: 'Add members',
  adminGroupMembersRemove: 'Remove',
  adminGroupMembersEmpty: 'No members yet. Adding one grants them every role this group holds.',
  adminGroupMemberDeactivated: 'Deactivated user \u00b7 grants nothing while deactivated',
  adminGroupMemberActive: 'Active',
  adminGroupMemberRemoveTitle: 'Remove {user} from {group}?',
  adminGroupMemberRemoveBody:
    'They immediately lose every permission they hold only through this group. Permissions from their own direct assignments are untouched.',
  adminGroupMemberRemoveConfirm: 'Remove member',
  adminGroupMemberRemovedToast: 'Removed {user} from {group}',
  adminGroupMemberRemoveError: 'The member could not be removed. Try again.',
  adminGroupAddMembersTitle: 'Add members to {group}',
  adminGroupAddMembersSearchLabel: 'Search users',
  adminGroupAddMembersSubmit: 'Add members',
  adminGroupAddMembersSubmitPending: 'Adding\u2026',
  adminGroupAddMembersNone: 'Every active user in this organization is already a member.',
  adminGroupAddMembersError: 'The members could not be added. Try again.',
  adminGroupAddMembersToast: 'Members added to {group}',
  adminGroupAddMembersDetail: { one: '{count} user added.', other: '{count} users added.' },
  adminGroupAddMembersSelected: { one: '{count} selected', other: '{count} selected' },

  adminGroupAssignmentsColRole: 'Role',
  adminGroupAssignmentsColScopeType: 'Scope type',
  adminGroupAssignmentsColOrganization: 'Organization',
  adminGroupAssignmentsColSubOrganization: 'Sub-organization',
  adminGroupAssignmentsColEnvironment: 'Environment',
  adminGroupAssignmentsColAssignedBy: 'Assigned by',
  adminGroupAssignmentsColAssignedAt: 'Assigned date',
  adminGroupAssignmentsColStatus: 'Status',
  adminGroupAssignmentsColActions: 'Actions',
  adminGroupAssignmentsAssign: 'Assign role',
  adminGroupAssignmentsEmpty:
    'No role assignments. Until this group holds a role it grants its members nothing.',
  adminGroupAssignmentsStatusActive: 'Active',
  adminGroupAssignmentsStatusRevoked: 'Revoked',
  adminGroupAssignmentRemove: 'Remove',
  adminGroupAssignmentRemoveTitle: 'Remove {role} at {scope} from {group}?',
  adminGroupAssignmentRemoveBody:
    'Every member loses the permissions this assignment confers. The record stays readable for audit \u2014 it is revoked, not deleted.',
  adminGroupAssignmentRemoveConfirm: 'Remove assignment',
  adminGroupAssignmentRemovedToast: 'Assignment removed from {group}',
  adminGroupAssignmentRemoveError: 'The assignment could not be removed. Try again.',
  adminGroupAssignTitle: 'Assign a role to {group}',
  adminGroupAssignSubmit: 'Assign role',
  adminGroupAssignSubmitPending: 'Assigning\u2026',
  adminGroupAssignError: 'The role could not be assigned. Try again.',
  adminGroupAssignToast: 'Role assigned to {group}',
  adminGroupAssignDetail: '{role} at {scope}.',
  adminGroupAssignDeactivatedBlocked:
    'This group is deactivated. Reactivate it before assigning a role \u2014 a deactivated group confers nothing.',

  // Scope selection (Organization \u2192 Sub-organization \u2192 Environment \u2192 Instance)
  adminScopeTypeLabel: 'Scope type',
  adminScopeTypeHint:
    'Access granted at a level applies to everything below it. Grants across all organizations are set up at installation and cannot be created here.',
  adminScopeOrganizationLabel: 'Organization',
  adminScopeOrganizationLockedHint:
    'Fixed to the group\u2019s own organization \u2014 a group can never be granted access outside it.',
  adminScopeSubOrganizationLabel: 'Sub-organization',
  adminScopeEnvironmentLabel: 'Environment',
  adminScopePickOrganizationFirst: 'Choose an organization first.',
  adminScopePickSubOrganizationFirst: 'Choose a sub-organization first.',
  adminScopeInstanceLabel: 'Instance',
  adminScopePickEnvironmentFirst: 'Choose an environment first.',
  adminScopeNoInstances: 'No instances in this environment yet.',
  adminScopeLevelAll: 'All organizations',
  adminScopeLevelOrganization: 'Organization',
  adminScopeLevelSubOrganization: 'Sub-organization',
  adminScopeLevelEnvironment: 'Environment',
  adminScopeLevelInstance: 'Instance',
  adminScopeIncomplete: 'Choose every level of the scope.',

  // Assignments: principal type (§7)
  adminAssignmentsPrincipalTypeLabel: 'Principal type',
  adminAssignmentsPrincipalTypeUser: 'User',
  adminAssignmentsPrincipalTypeGroup: 'Group',
  adminAssignmentsColPrincipalType: 'Type',
  adminAssignmentsDuplicate: 'That principal already holds that role at exactly that scope.',
  adminAssignmentsPrincipalDeactivated:
    'That principal is deactivated and can hold no new assignment. Reactivate it first.',
  adminAssignmentsCrossOrganization:
    'A group owned by {organization} cannot be granted access inside another organization.',
  adminAssignmentsNoPrincipalsOfType: 'There are no active principals of this type to assign to.',
  adminAssignmentsRevoked: 'revoked',
  adminAssignmentsColStatus: 'Status',

  // Access check: where a grant came from (§8)
  adminAccessColSource: 'Source',
  adminAccessSourceDirect: 'Direct assignment',
  adminAccessSourceGroup: 'Group: {group}',
  adminAccessInheritedFrom: 'Inherited from {level}',
  adminAccessGrantedAtLevel: 'Granted at this {level}',
  adminAccessSourcesHeading: 'Every source of this permission',
  adminAccessDeniedNoGrant:
    'No direct assignment and no group this principal belongs to grants anything here.',
  adminAccessPrincipalDeactivated:
    'This principal is deactivated. Its historical assignments are still readable, but it holds no access.',

  // Shared with the Declare-instance target picker's "no scope" label
  // (integration plan 013 Task 6 — the Targets preview screen that
  // originally defined this string is removed, but Connections below
  // still uses it).
  targetsPreviewScopeOrganizationWide: 'Organization-wide',

  // Connections catalog (task 7, `UI-D27`, spec §8.1). Not in v1 nav —
  // reachable via `AccountMenu`'s DEV-only preview link.
  connectionsTitle: 'Connections',
  connectionsSub: 'Reusable connections, shared across instances of any solution.',
  connectionsNewAction: '+ New connection',
  connectionsShowInactive: 'Show inactive',
  connectionsInactiveChip: 'inactive',
  connectionsColName: 'Name',
  connectionsColScope: 'Scope',
  connectionsColUsed: 'Used',
  connectionsEmptyTitle: 'No connections yet',
  connectionsEmptyBody: 'Create one to reuse it across instances.',
  connectionsErrorTitle: 'Connections could not be loaded',

  // Connection detail drawer (task 8, `UI-D27`, spec §8.2). Standalone —
  // `ConnectionsPage` does not open it yet (task 9).
  connectionsDrawerNameLabel: 'Name',
  connectionsDrawerDescriptionLabel: 'Description',
  connectionsDrawerScopeLabel: 'Scope',
  connectionsDrawerSecretCurrentlySet: 'Currently set — leave blank to keep it',
  connectionsDrawerSecretUnset: 'Not set',
  connectionsDrawerSaveAction: 'Save changes',
  connectionsDrawerTestAndSaveAction: 'Test & save',
  connectionsDrawerSaveErrorTitle: 'Could not save this connection',
  connectionsDrawerTestConnection: 'Test connection',
  connectionsDrawerTestConnectionReason: 'Available when server support lands.',
  connectionsDrawerImpactTitle: 'Confirm this change',
  connectionsDrawerImpactBody: {
    one: '{count} instance references this connection. Their configuration-of-record will change; the change reaches each MI only when someone applies it.',
    other: '{count} instances reference this connection. Their configuration-of-record will change; the change reaches each MI only when someone applies it.',
  },
  connectionsDrawerUsedBy: {
    one: 'Used by {count} instance',
    other: 'Used by {count} instances',
  },
  connectionsDrawerStatusLabel: 'Status',
  connectionsDrawerActivate: 'Activate',
  connectionsDrawerDeactivate: 'Deactivate',
  connectionsDrawerDelete: 'Delete',
  connectionsDrawerDeleteBlockedReason:
    'Referenced by at least one instance. Deactivate it instead, or remove every reference first.',
  connectionsDrawerDeleteConfirmTitle: 'Delete this connection?',
  connectionsDrawerDeleteConfirmBody: 'This cannot be undone.',
} as const satisfies Record<string, MessageValue>;

export type MessageKey = keyof typeof messagesEn;
export type Messages = typeof messagesEn;
