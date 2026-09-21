/**
 * The declare-instance form's state, defaults and validation — pure, so
 * every rule below is asserted without rendering a dialog (design §19's
 * "logic" layer).
 *
 * The field set is INVENTED (§16 Q10) — see `instancesRepository.ts`'s
 * header. This module holds only the identity fields an operator can know
 * before anything has been observed; nothing here asks for, defaults, or
 * infers an observed fact.
 */
import type { Env, InstanceRow } from '@shared/testdoubles/data/instances';
import type { DriverKey, LocusKey } from '@shared/vocab';

import type { DeclareInstanceInput } from './instancesRepository';

/**
 * Instance names are used verbatim in URLs, CSV exports and (in a real
 * deployment) container and resource names, so the accepted shape is the
 * intersection of what all of those tolerate: lowercase DNS-label
 * characters, no leading or trailing hyphen. Deliberately NOT the widest
 * thing a form could accept — a name that has to be escaped differently in
 * four places is a defect waiting for the first customer with a dot in it.
 */
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
  /**
   * `''` means "not chosen yet", exactly like `packageVersion` above.
   * Environment is the THIRD step of a dependent cascade (`FE-D46`) — it
   * cannot be chosen before an organization and a sub-org are, so it has
   * no defensible default at form-open. See `initialDeclareForm`.
   */
  env: Env | '';
  /** Level one of the cascade. `''` = unchosen. */
  org: string;
  /** Level two. Cleared whenever `org` changes. `''` = unchosen. */
  subOrg: string;
  driver: DriverKey;
  locus: LocusKey;
  replicas: readonly string[];
  /**
   * False while replica names still track `name` (the slug-follows-title
   * pattern). Once the operator edits a replica, renaming the instance
   * stops rewriting their names — an edit is never silently overwritten.
   */
  replicasTouched: boolean;
  declaredVersion: string;
}

/** Per-field messages. A field is invalid exactly when its key is present. */
export interface DeclareFormErrors {
  name?: string;
  org?: string;
  subOrg?: string;
  env?: string;
  packageVersion?: string;
  /** Sparse, index-aligned with `state.replicas`. */
  replicas?: readonly (string | undefined)[];
}

export function replicaNameFor(instanceName: string, index: number): string {
  return instanceName === '' ? '' : `${instanceName}-${String(index)}`;
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
  existing: readonly InstanceRow[],
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
    // UNCHOSEN, not 'DEV'. This used to default to DEV on the reasoning
    // that a one-click default must never be PROD — but under the cascade
    // (`FE-D46`) an environment cannot be defaulted at all, because the
    // set of valid ones is not known until an organization and a sub-org
    // have been picked. Requiring the choice is strictly stronger than
    // defaulting it away from PROD.
    env: '',
    org: '',
    subOrg: '',
    driver: 'docker',
    locus: 'dp_remote',
    replicas: [replicaNameFor(name, 0)],
    replicasTouched: false,
    declaredVersion: '',
  };
}

/** Renaming rewrites the untouched replica defaults with it. */
export function renameInstance(state: DeclareFormState, name: string): DeclareFormState {
  if (state.replicasTouched) {
    return { ...state, name };
  }
  return {
    ...state,
    name,
    replicas: state.replicas.map((_, index) => replicaNameFor(name, index)),
  };
}

export function setReplica(
  state: DeclareFormState,
  index: number,
  value: string,
): DeclareFormState {
  return {
    ...state,
    replicasTouched: true,
    replicas: state.replicas.map((replica, i) => (i === index ? value : replica)),
  };
}

export function addReplica(state: DeclareFormState): DeclareFormState {
  return {
    ...state,
    replicas: [...state.replicas, replicaNameFor(state.name, state.replicas.length)],
  };
}

/** A no-op at one replica — an instance with no replicas is not a thing. */
export function removeReplica(state: DeclareFormState, index: number): DeclareFormState {
  if (state.replicas.length <= 1) {
    return state;
  }
  return {
    ...state,
    replicasTouched: true,
    replicas: state.replicas.filter((_, i) => i !== index),
  };
}

/**
 * Level one of the cascade. Choosing a different organization invalidates
 * everything below it — the previously chosen sub-org almost certainly
 * does not exist under the new organization, and silently keeping it is
 * how a form submits a triple that does not exist.
 */
export function setOrg(state: DeclareFormState, org: string): DeclareFormState {
  if (state.org === org) {
    return state;
  }
  return { ...state, org, subOrg: '', env: '' };
}

/** Level two. Same reasoning one level down: the environment set changes. */
export function setSubOrg(state: DeclareFormState, subOrg: string): DeclareFormState {
  if (state.subOrg === subOrg) {
    return state;
  }
  return { ...state, subOrg, env: '' };
}

export interface DeclareFormMessages {
  required: string;
  nameFormat: string;
  nameTaken: string;
  replicaDuplicate: string;
  packageVersionUnknown: string;
}

/**
 * `taken` is the names already declared for THIS solution. Compared
 * case-insensitively: two instances differing only in case would be two
 * URLs a human reads as one.
 */
export function validateDeclareForm(
  state: DeclareFormState,
  taken: readonly string[],
  messages: DeclareFormMessages,
  packageVersions: readonly (string | number)[] = [],
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
  }

  const seen = new Set<string>();
  const replicaErrors: (string | undefined)[] = state.replicas.map((replica) => {
    const value = replica.trim();
    if (value === '') {
      return messages.required;
    }
    // Flagged on the LATER occurrence — the first one is not the mistake.
    if (seen.has(value.toLowerCase())) {
      return messages.replicaDuplicate;
    }
    seen.add(value.toLowerCase());
    return undefined;
  });
  if (replicaErrors.some((error) => error !== undefined)) {
    errors.replicas = replicaErrors;
  }

  return errors;
}

export function hasErrors(errors: DeclareFormErrors): boolean {
  return Object.keys(errors).length > 0;
}

/**
 * Trimmed, with the optional MI version OMITTED rather than sent as `''`.
 *
 * `packageVersions` is passed so the chosen string can be mapped back to
 * the id the package actually declared: `6` must go over the wire as the
 * number `6`, not the string `'6'`, or the grid, the Designer's version
 * filter and the package file would each be comparing a different type.
 * An unmatched value falls back to the string — `validateDeclareForm`
 * has already refused to submit in that case.
 */
export function toDeclareInput(
  state: DeclareFormState,
  packageVersions: readonly (string | number)[] = [],
): DeclareInstanceInput {
  const declaredVersion = state.declaredVersion.trim();
  const packageVersion =
    packageVersions.find((version) => String(version) === state.packageVersion) ??
    state.packageVersion;
  return {
    name: state.name.trim(),
    packageVersion,
    // `validateDeclareForm` refuses to submit an unchosen environment, so
    // the narrowing here is guaranteed by the caller's own gate.
    env: state.env as Env,
    org: state.org.trim(),
    subOrg: state.subOrg.trim(),
    driver: state.driver,
    locus: state.locus,
    replicas: state.replicas.map((replica) => replica.trim()),
    ...(declaredVersion === '' ? {} : { declaredVersion }),
  };
}
