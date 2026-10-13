/**
 * How a declared instance becomes an `InstanceRow` — the fixture-side half
 * of the declare flow (task 115), shared by the default `dpHandlers` and
 * the DEV session store (`app/dev/devDeclaredInstances.ts`) so the two
 * cannot disagree about what a just-declared instance looks like.
 *
 * **The rule this file obeys.** Identity comes from the operator;
 * everything observable is derived here as NOT YET OBSERVED. Each value
 * below is its vocabulary's own "not yet / not applicable" member, never a
 * plausible-looking default:
 *
 * - `not_applicable` health — its vocabulary entry says exactly this
 *   ("a normal condition ... never treated as an outage"). Never
 *   `healthy`, which would assert an observation nobody made.
 * - `unverified` version (`UI-D12`: "nothing observable corroborates the
 *   declaration") — which, unlike `drift`/`unknown`, does NOT block
 *   editing, so the Designer stays usable on a fresh instance.
 * - `na` config state — nothing has been rendered anywhere.
 * - `lastSeen: 'never'` — `instances.ts`'s own value for a not-yet-observed
 *   instance, never a fabricated timestamp.
 *
 * The sequence is declare → apply → restart → observable, so an instance
 * reading "never / unverified / na" right after declaring is correct, not
 * a fault — which is what `instancesEmptyBody` promises the operator.
 */
import type { AcquisitionMode, DeclareInstanceInput } from '@shared/api/contracts/instances';
import type { DriverKey, HealthKey, LocusKey } from '@shared/vocab';

import type { Env, InstanceRow } from './instances';

export const UNOBSERVED_REPLICA_HEALTH: HealthKey = 'not_applicable';

/** What every unobserved version field reads — `instances.ts`'s em dash. */
export const UNOBSERVED = '—';

type UnobservedFacts = Pick<InstanceRow, 'healthRollup' | 'version' | 'configState' | 'lastSeen'>;

/**
 * `declaredVersion` is the ONE fact here an operator may legitimately know
 * before anything runs. Absent, it reads `—` rather than a guess.
 */
export function unobservedRuntimeFacts(declaredVersion?: string): UnobservedFacts {
  const declared =
    declaredVersion === undefined || declaredVersion.trim() === ''
      ? UNOBSERVED
      : declaredVersion.trim();
  return {
    healthRollup: 'not_applicable',
    version: { state: 'unverified', observed: UNOBSERVED, declared },
    configState: 'na',
    lastSeen: 'never',
  };
}

/**
 * Restated here rather than imported from the form: a server validates the
 * request it received, not the rules the client claims to have applied.
 * The dialog checks the same shape, but a client-side check is a courtesy
 * to the operator, never the guarantee.
 */
const NAME_PATTERN = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function nonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== '';
}

/**
 * ⚠️ DEGRADED (integration plan 007, 2026-10-09): the real `declareInstance`
 * body carries only `solutionId`/`name`/`targetId` | `connectionId` (+ `deploymentMode`, plan 014)/`declaredPackageVersion`/
 * `placement`/`acquisition` — no `env`/`org`/`subOrg`/`driver`/`locus`, which
 * `InstanceRow` (this DEV sandbox's display type) still requires. This
 * validator accepts the real shape; `buildDeclaredInstance` below fills the
 * now-unsourced display fields with fixed placeholders rather than guessing
 * real-looking ones. The DEV sandbox's declared-instance flow is flagged,
 * not redesigned, as part of this plan — see the plan's closing report for
 * why a fuller fix was out of scope.
 */
export function readDeclareInput(value: unknown): DeclareInstanceInput | undefined {
  if (!isRecord(value)) return undefined;

  const { solutionId, name, targetId, connectionId, deploymentMode, declaredPackageVersion, placement, acquisition } =
    value;
  if (!nonEmptyString(name) || !NAME_PATTERN.test(name.trim())) return undefined;
  if (!nonEmptyString(solutionId)) return undefined;
  // Plan 014: exactly one of `targetId` (legacy) and `connectionId`, and
  // only `dp-remote` (or absent, which means `dp-remote`) is accepted —
  // mirroring the real server's 422s, not the form's own rules.
  const hasTarget = nonEmptyString(targetId);
  const hasConnection = nonEmptyString(connectionId);
  if (hasTarget === hasConnection) return undefined;
  if (deploymentMode !== undefined && deploymentMode !== 'dp-remote') return undefined;
  if (!nonEmptyString(declaredPackageVersion) && typeof declaredPackageVersion !== 'number') {
    return undefined;
  }
  if (!isRecord(placement)) return undefined;
  const { containerName, configDirectory, containerConfigPath, fileName, healthPort } = placement;
  if (
    !nonEmptyString(containerName) ||
    !nonEmptyString(configDirectory) ||
    !nonEmptyString(containerConfigPath) ||
    !nonEmptyString(fileName) ||
    typeof healthPort !== 'number'
  ) {
    return undefined;
  }
  if (!isRecord(acquisition) || (acquisition['mode'] !== 'direct-http' && acquisition['mode'] !== 'via-ssh')) {
    return undefined;
  }

  const mode: AcquisitionMode = acquisition['mode'];
  const common = {
    solutionId: solutionId.trim(),
    name: name.trim(),
    declaredPackageVersion:
      typeof declaredPackageVersion === 'number' ? declaredPackageVersion : declaredPackageVersion.trim(),
    placement: {
      containerName: containerName.trim(),
      configDirectory: configDirectory.trim(),
      containerConfigPath: containerConfigPath.trim(),
      fileName: fileName.trim(),
      healthPort,
    },
    acquisition: { mode },
  };
  return hasConnection
    ? { ...common, connectionId: connectionId.trim(), deploymentMode: 'dp-remote' }
    : { ...common, targetId: (targetId as string).trim() };
}

/**
 * `id` is the name. The routes key on the id and the grid shows the name,
 * so making them one thing is what stops a declared instance from having
 * two identities an operator has to keep straight.
 */
/**
 * `appliedConnectionRevisions` is a SNAPSHOT the caller already computed —
 * this function only places it on the row, exactly like `componentTypes`.
 * It never looks a connection up itself: that would make the snapshot
 * silently track the LIVE catalog on every rebuild (`devDeclaredInstances`'s
 * `toRow()` calls this on every read), which would make drift
 * undetectable — the applied revision must stay frozen from the moment it
 * was recorded (`UI-D29`).
 */
/** Placeholder-only — see this module's DEGRADED note above. */
const PLACEHOLDER_ENV: Env = 'DEV';
const PLACEHOLDER_ORG = 'Unresolved org';
const PLACEHOLDER_SUB_ORG = 'Unresolved sub-org';
const PLACEHOLDER_DRIVER: DriverKey = 'docker';
const PLACEHOLDER_LOCUS: LocusKey = 'dp_remote';

export function buildDeclaredInstance(input: DeclareInstanceInput): InstanceRow {
  return {
    id: input.name,
    name: input.name,
    solutionId: input.solutionId,
    packageVersion: input.declaredPackageVersion,
    env: PLACEHOLDER_ENV,
    org: PLACEHOLDER_ORG,
    subOrg: PLACEHOLDER_SUB_ORG,
    driver: PLACEHOLDER_DRIVER,
    locus: PLACEHOLDER_LOCUS,
    replicas: [{ name: input.placement.containerName, health: UNOBSERVED_REPLICA_HEALTH }],
    ...unobservedRuntimeFacts(),
  };
}
