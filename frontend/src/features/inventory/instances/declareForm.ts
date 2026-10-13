/**
 * The declare-instance form's state, defaults and validation — pure, so
 * every rule below is asserted without rendering a dialog (design §19's
 * "logic" layer).
 *
 * Rewritten for integration plan 007 (2026-10-09) against the real
 * `declareInstance` contract (`console.yaml`, `POST
 * /dp/environments/{environmentId}/instances`). The previous version's
 * field set was invented (§16 Q10 was open when it was written): `driver`,
 * `locus`, the replica-name array, `role`/`componentTypes`, `connections`
 * and `declaredVersion` are ALL ABSENT from the real request body — see
 * `docs/plans/integration/007-instances-and-placements.md`'s Task 1 and
 * the consolidated-design-session note in `README.md` (item 1).
 *
 * - `driver`/`locus` are properties of the TARGET now (`kind`/`transport`
 *   on `TargetRow`), not asked again once a target is picked.
 * - The replica-name array doesn't map to `declareInstance` at all — one
 *   call creates ONE instance with ONE initial placement. Additional
 *   placements are `addPlacement` calls after declare (plan 007 Task 3),
 *   not part of this form.
 * - `role`/`componentTypes` have no field on the wire body; a package's
 *   component selection happens at `stageConfig` time (Designer,
 *   `componentSelection`), not at declare.
 * - `connections` (`UI-D27` promoted selectors) and `declaredVersion` have
 *   no field either — flagged, not silently kept as dead UI that submits
 *   nothing.
 *
 * What declare now asks for instead: a deployment mode and its host binding,
 * and the placement envelope the real body requires (`containerName`,
 * `configDirectory`, `containerConfigPath`, `fileName`, `healthPort`,
 * `acquisition.mode`).
 *
 * **Integration plan 014 (2026-10-10): the binding is a Connection, not a
 * Target.** `deploymentMode` is chosen first; for `dp-remote` — the only
 * member that is selectable today — the operator picks an active
 * `remote-server` Connection (`ConnectionPickerRepository`) and the body
 * carries `connectionId` + `deploymentMode`, never `targetId`
 * (`console.yaml`: exactly one of the two). Plan 014 supersedes plan 007's
 * target picker for NEW declarations; the legacy `targetId` body shape
 * stays in the wire type for older clients, not on this form.
 */
import type {
  AcquisitionMode,
  DeclareInstanceInput,
  DeploymentMode,
  Instance,
} from '@shared/api/contracts/instances';
import type { Env } from '@shared/testdoubles/data/instances';

import type { ComponentRoleGroup } from './componentRoles';
import type { RemoteServerConnectionRow } from './connectionPickerRepository';
import type { TargetRow } from './targetPickerRepository';

/**
 * Instance names are used verbatim in URLs, CSV exports and (in a real
 * deployment) container and resource names, so the accepted shape is the
 * intersection of what all of those tolerate: lowercase DNS-label
 * characters, no leading or trailing hyphen. Deliberately NOT the widest
 * thing a form could accept — a name that has to be escaped differently in
 * four places is a defect waiting for the first customer with a dot in it.
 */
/**
 * The deployment-mode vocabulary (`console.yaml` `DeploymentMode`), in the
 * order the dropdown lists it, with whether each member can be chosen
 * TODAY. `host-local` and `federated` are reserved until their agent
 * contracts land (plan 014's compatibility rules): shown, so the operator
 * knows they exist, but not selectable — and refused by
 * `validateDeclareForm` if state ever carries one anyway.
 */
export const DEPLOYMENT_MODES: readonly { value: DeploymentMode; selectable: boolean }[] = [
  { value: 'dp-remote', selectable: true },
  { value: 'host-local', selectable: false },
  { value: 'federated', selectable: false },
];

export const INSTANCE_NAME_PATTERN = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/;

export interface DeclareFormState {
  name: string;
  /**
   * The PACKAGE version this instance is configured against — held as a
   * string because that is what a `<select>` value is. `toDeclareInput()`
   * maps it back to the original id, so a numeric version stays a number
   * on the wire. `''` means "not chosen yet", which is a validation error,
   * never a silent default (see `initialDeclareForm`).
   */
  packageVersion: string;
  /** The package-defined role. Empty means no role selected/derived. */
  role: string;
  /**
   * `''` means "not chosen yet". Environment is the THIRD step of a
   * dependent cascade (`FE-D46`) — it cannot be chosen before an
   * organization and a sub-org are, so it has no defensible default at
   * form-open. See `initialDeclareForm`.
   */
  env: Env | '';
  /** Level one of the cascade. `''` = unchosen. */
  org: string;
  /** Level two. Cleared whenever `org` changes. `''` = unchosen. */
  subOrg: string;
  /**
   * Preselected to `dp-remote`: it is the only selectable member, so asking
   * would be a required field with a single answer (the same rule as a
   * single-version package — see `initialDeclareForm`).
   */
  deploymentMode: DeploymentMode;
  /**
   * The remote-server Connection a `dp-remote` instance binds to (plan 014).
   * `''` = unchosen. Cleared whenever `org`/`subOrg`/`env` changes, exactly
   * as the target it replaces was, so the binding is always the last choice
   * made against the scope above it.
   */
  connectionId: string;
  /** New deployment-target binding. Kept alongside connectionId for legacy clients. */
  targetId: string;
  containerName: string;
  configDirectory: string;
  containerConfigPath: string;
  fileName: string;
  /** Held as a string (form input), validated as 1-65535, parsed on submit. */
  healthPort: string;
  /** `''` = unchosen — `direct-http` and `via-ssh` are the only real members; no default (M-Q2). */
  acquisitionMode: AcquisitionMode | '';
}

/** Per-field messages. A field is invalid exactly when its key is present. */
export interface DeclareFormErrors {
  name?: string;
  org?: string;
  subOrg?: string;
  env?: string;
  packageVersion?: string;
  role?: string;
  deploymentMode?: string;
  connectionId?: string;
  targetId?: string;
  containerName?: string;
  configDirectory?: string;
  containerConfigPath?: string;
  fileName?: string;
  healthPort?: string;
  acquisitionMode?: string;
}

/**
 * The first `<solution>-NN` not already declared. A default that collides
 * with an existing instance is worse than no default at all — it puts an
 * error on the form before the operator has typed anything.
 */
export function suggestInstanceName(solutionId: string, taken: readonly string[]): string {
  const slug = solutionId
    .toLowerCase()
    .replace(/[^a-z0-9-]+/g, '-')
    .replace(/^-+|-+$/g, '');
  const base = slug === '' ? 'instance' : slug;
  const used = new Set(taken.map((name) => name.toLowerCase()));
  for (let n = 1; n <= 99; n += 1) {
    const candidate = `${base}-${String(n).padStart(2, '0')}`;
    if (!used.has(candidate)) {
      return candidate;
    }
  }
  return base;
}

/**
 * `packageVersions` is what the SOLUTION declares, in ascending order.
 *
 * Preselected at EXACTLY ONE — there is no choice to make, and asking for
 * one would be a required field with a single answer. At two or more it is
 * `''`, i.e. unchosen: **no silent default to "latest"**. A package version
 * defaulted invisibly is precisely the class of bug this task exists to
 * remove — the Designer would render a field set the instance was never
 * configured against, with nothing on screen saying so.
 */
export function initialDeclareForm(
  solutionId: string,
  existing: readonly Instance[],
  packageVersions: readonly (string | number)[] = [],
): DeclareFormState {
  const name = suggestInstanceName(
    solutionId,
    existing.map((row) => row.name),
  );
  const only = packageVersions.length === 1 ? packageVersions[0] : undefined;
  return {
    name,
    packageVersion: only === undefined ? '' : String(only),
    role: '',
    // UNCHOSEN, not 'DEV'. This used to default to DEV on the reasoning
    // that a one-click default must never be PROD — but under the cascade
    // (`FE-D46`) an environment cannot be defaulted at all, because the
    // set of valid ones is not known until an organization and a sub-org
    // have been picked. Requiring the choice is strictly stronger than
    // defaulting it away from PROD.
    env: '',
    org: '',
    subOrg: '',
    deploymentMode: 'dp-remote',
    connectionId: '',
    targetId: '',
    containerName: '',
    configDirectory: '',
    containerConfigPath: '',
    fileName: '',
    healthPort: '',
    acquisitionMode: '',
  };
}

/**
 * Level one of the cascade. Choosing a different organization invalidates
 * everything below it — the previously chosen sub-org almost certainly
 * does not exist under the new organization, and silently keeping it is
 * how a form submits a triple that does not exist. The connection
 * binding resets along with the rest.
 */
export function setOrg(state: DeclareFormState, org: string): DeclareFormState {
  if (state.org === org) {
    return state;
  }
  return { ...state, org, subOrg: '', env: '', connectionId: '', targetId: '' };
}

/** Level two. Same reasoning one level down: the environment set changes, and the connection with it. */
export function setSubOrg(state: DeclareFormState, subOrg: string): DeclareFormState {
  if (state.subOrg === subOrg) {
    return state;
  }
  return { ...state, subOrg, env: '', connectionId: '', targetId: '' };
}

/** Level three. The connection is chosen against the environment, so it resets with it. */
export function setEnv(state: DeclareFormState, env: Env): DeclareFormState {
  if (state.env === env) {
    return state;
  }
  return { ...state, env, connectionId: '', targetId: '' };
}

/**
 * Target-owned placement defaults. Linked-target responses normally expose
 * these through `allowed*`; `runtimeSettingsJson` remains a compatibility
 * fallback for older target rows.
 */
export function targetPlacementDefaults(target: TargetRow | undefined): {
  configDirectory: string;
  containerName: string;
} {
  if (target === undefined) {
    return { configDirectory: '', containerName: '' };
  }

  let settings: Record<string, unknown> = {};
  if (target.runtimeSettingsJson !== undefined) {
    try {
      const parsed: unknown = JSON.parse(target.runtimeSettingsJson);
      if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
        settings = parsed as Record<string, unknown>;
      }
    } catch {
      // The target envelope remains authoritative if this compatibility
      // payload is malformed.
    }
  }

  const firstNonBlank = (...values: unknown[]): string => {
    for (const value of values) {
      if (typeof value === 'string' && value.trim() !== '') {
        return value.trim();
      }
    }
    return '';
  };

  return {
    configDirectory: firstNonBlank(target.allowedConfigRoot, settings.configRoot),
    // The declare contract has `containerName`, not a separate prefix field.
    // Starting it with the target's prefix/pattern makes the target rule
    // visible without adding a value that the backend does not accept.
    containerName: target.runtime === 'systemd'
      ? firstNonBlank(settings.serviceNamePattern)
      : firstNonBlank(target.allowedContainerPrefix, settings.containerPrefix),
  };
}

/** Select a target and replace both target-owned placement values together. */
export function setTarget(
  state: DeclareFormState,
  targetId: string,
  targets: readonly TargetRow[],
): DeclareFormState {
  const target = targets.find((candidate) => candidate.targetId === targetId);
  const defaults = targetPlacementDefaults(target);
  return {
    ...state,
    targetId,
    connectionId: '',
    configDirectory: defaults.configDirectory,
    containerName: defaults.containerName,
  };
}

/**
 * Only a selectable mode is applied; a reserved one is ignored rather than
 * stored (the dropdown already disables it — this is the logic layer's own
 * guard). Switching mode clears the binding chosen under the previous one.
 */
export function setDeploymentMode(state: DeclareFormState, mode: DeploymentMode): DeclareFormState {
  const member = DEPLOYMENT_MODES.find((entry) => entry.value === mode);
  if (member === undefined || !member.selectable || state.deploymentMode === mode) {
    return state;
  }
  return { ...state, deploymentMode: mode, connectionId: '' };
}

/** `packageVersion` changed. */
export function setPackageVersion(state: DeclareFormState, packageVersion: string): DeclareFormState {
  return { ...state, packageVersion, role: '' };
}

export interface DeclareFormMessages {
  required: string;
  nameFormat: string;
  nameTaken: string;
  packageVersionUnknown: string;
  healthPortRange: string;
  deploymentModeUnavailable: string;
  roleRequired?: string;
  roleUnknown?: string;
}

const MIN_PORT = 1;
const MAX_PORT = 65535;

/**
 * `taken` is the names already declared for THIS solution. Compared
 * case-insensitively: two instances differing only in case would be two
 * URLs a human reads as one.
 *
 * `connections` is the live list of active remote-server Connections —
 * passed in so `connectionId`'s validity can be checked the same way
 * `packageVersion`'s is (a value no longer in the known list is refused,
 * not submitted).
 */
export function validateDeclareForm(
  state: DeclareFormState,
  taken: readonly string[],
  messages: DeclareFormMessages,
  packageVersions: readonly (string | number)[] = [],
  connections: readonly RemoteServerConnectionRow[] = [],
  roleGroups: readonly ComponentRoleGroup[] = [],
  targets: readonly TargetRow[] = [],
): DeclareFormErrors {
  const errors: DeclareFormErrors = {};

  // Two failures, not one. EMPTY is "you have not chosen"; a NON-MEMBER is
  // "this package does not serve that version" — which is reachable when
  // the catalog reloads under an open dialog and the package's version
  // list has changed. Collapsing them into "required" would tell an
  // operator staring at a filled-in select that it is empty.
  if (state.packageVersion === '') {
    errors.packageVersion = messages.required;
  } else if (!packageVersions.some((version) => String(version) === state.packageVersion)) {
    errors.packageVersion = messages.packageVersionUnknown;
  }

  const name = state.name.trim();
  if (name === '') {
    errors.name = messages.required;
  } else if (!INSTANCE_NAME_PATTERN.test(name)) {
    errors.name = messages.nameFormat;
  } else if (taken.some((existing) => existing.toLowerCase() === name.toLowerCase())) {
    errors.name = messages.nameTaken;
  }

  // The cascade, in order. Each level is required, and a level below an
  // unchosen one is left unflagged: telling an operator that Sub-org is
  // required when no organization has been picked names the wrong field.
  if (state.org.trim() === '') {
    errors.org = messages.required;
  } else if (state.subOrg.trim() === '') {
    errors.subOrg = messages.required;
  } else if (state.env === '') {
    errors.env = messages.required;
  } else if (targets.length > 0) {
    if (state.targetId === '') {
      errors.connectionId = messages.required;
    } else if (!targets.some((target) => target.targetId === state.targetId)) {
      errors.connectionId = messages.required;
    }
  } else if (state.connectionId === '') {
    errors.connectionId = messages.required;
  } else if (!connections.some((connection) => connection.connectionId === state.connectionId)) {
    errors.connectionId = messages.required;
  }

  if (roleGroups.length > 1) {
    if (state.role.trim() === '') {
      errors.role = messages.roleRequired ?? messages.required;
    } else if (!roleGroups.some((group) => group.role === state.role)) {
      errors.role = messages.roleUnknown ?? messages.required;
    }
  }

  // Not cascade-gated: the mode is preselected and independent of scope.
  if (state.deploymentMode !== 'dp-remote') {
    errors.deploymentMode = messages.deploymentModeUnavailable;
  }

  if (state.containerName.trim() === '') {
    errors.containerName = messages.required;
  }
  if (state.configDirectory.trim() === '') {
    errors.configDirectory = messages.required;
  }
  const selectedTarget = targets.find((target) => target.targetId === state.targetId);
  const systemdTarget = selectedTarget?.runtime === 'systemd';
  if (!systemdTarget && state.containerConfigPath.trim() === '') {
    errors.containerConfigPath = messages.required;
  }
  if (state.fileName.trim() === '') {
    errors.fileName = messages.required;
  }

  const port = Number(state.healthPort);
  if (state.healthPort.trim() === '') {
    errors.healthPort = messages.required;
  } else if (!Number.isInteger(port) || port < MIN_PORT || port > MAX_PORT) {
    errors.healthPort = messages.healthPortRange;
  }

  if (state.acquisitionMode === '') {
    errors.acquisitionMode = messages.required;
  }

  return errors;
}

export function hasErrors(errors: DeclareFormErrors): boolean {
  return Object.keys(errors).length > 0;
}

/**
 * Trimmed, with `environmentId`/`solutionId` supplied by the caller — they
 * are resolved asynchronously (the hierarchy cascade, the solution's
 * catalog id) and this function stays pure, so it takes them as
 * parameters rather than looking them up itself.
 *
 * `packageVersions` maps the chosen string back to the id the package
 * actually declared: `6` must go over the wire as the number `6`, not the
 * string `'6'`. An unmatched value falls back to the string —
 * `validateDeclareForm` has already refused to submit in that case.
 */
export function toDeclareInput(
  state: DeclareFormState,
  solutionId: string,
  packageVersions: readonly (string | number)[] = [],
  roleGroups: readonly ComponentRoleGroup[] = [],
  targetRuntime?: TargetRow['runtime'],
): DeclareInstanceInput {
  const declaredPackageVersion =
    packageVersions.find((version) => String(version) === state.packageVersion) ??
    state.packageVersion;
  const role = roleGroups.length === 1 ? roleGroups[0]!.role : state.role.trim();
  const containerConfigPath = targetRuntime === 'systemd'
    ? state.configDirectory.trim()
    : state.containerConfigPath.trim();
  if (state.targetId.trim() !== '') {
    return {
      solutionId,
      name: state.name.trim(),
      deploymentMode: 'dp-remote',
      targetId: state.targetId,
      declaredPackageVersion,
      ...(role === '' ? {} : { role }),
      placement: {
        containerName: state.containerName.trim(),
        configDirectory: state.configDirectory.trim(),
        containerConfigPath,
        fileName: state.fileName.trim(),
        healthPort: Number(state.healthPort),
      },
      acquisition: { mode: state.acquisitionMode as AcquisitionMode },
    };
  }
  return {
    solutionId,
    name: state.name.trim(),
    // `validateDeclareForm` refuses any other mode, so this is the only
    // value the caller's own gate lets through. No `targetId`: the body
    // carries exactly one binding (`console.yaml`, plan 014).
    deploymentMode: 'dp-remote',
    connectionId: state.connectionId,
    declaredPackageVersion,
    ...(role === '' ? {} : { role }),
    placement: {
      containerName: state.containerName.trim(),
      configDirectory: state.configDirectory.trim(),
      containerConfigPath,
      fileName: state.fileName.trim(),
      healthPort: Number(state.healthPort),
    },
    // `validateDeclareForm` refuses to submit an unchosen mode, so the
    // narrowing here is guaranteed by the caller's own gate.
    acquisition: { mode: state.acquisitionMode as AcquisitionMode },
  };
}
