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
import type { DeclareInstanceInput } from '@shared/api/contracts/instances';
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

const ENVS: readonly Env[] = ['DEV', 'TEST', 'PROD'];
const DRIVERS: readonly DriverKey[] = ['docker', 'k8s', 'systemd'];
const LOCI: readonly LocusKey[] = ['dp_remote', 'host_local', 'observe_only'];

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

/** The validated input, or `undefined` with no partial result. */
export function readDeclareInput(value: unknown): DeclareInstanceInput | undefined {
  if (!isRecord(value)) return undefined;

  const { name, packageVersion, env, org, subOrg, driver, locus, replicas, declaredVersion } =
    value;
  if (!nonEmptyString(name) || !NAME_PATTERN.test(name.trim())) return undefined;
  // Identity, not an observation (see `DeclareInstanceInput.packageVersion`)
  // — so it is validated here alongside the name rather than derived as
  // "not yet observed" below. Not checked for MEMBERSHIP of the package's
  // own version list: this module has no package to check against, and the
  // form does that check where it can enumerate them.
  if (!nonEmptyString(packageVersion) && typeof packageVersion !== 'number') return undefined;
  if (typeof env !== 'string' || !ENVS.includes(env as Env)) return undefined;
  if (!nonEmptyString(org) || !nonEmptyString(subOrg)) return undefined;
  if (typeof driver !== 'string' || !DRIVERS.includes(driver as DriverKey)) return undefined;
  if (typeof locus !== 'string' || !LOCI.includes(locus as LocusKey)) return undefined;
  if (!Array.isArray(replicas) || replicas.length === 0) return undefined;
  if (!replicas.every(nonEmptyString)) return undefined;
  const replicaNames = replicas.map((replica) => replica.trim().toLowerCase());
  if (new Set(replicaNames).size !== replicaNames.length) return undefined;
  if (declaredVersion !== undefined && typeof declaredVersion !== 'string') return undefined;

  return {
    name: name.trim(),
    packageVersion: typeof packageVersion === 'number' ? packageVersion : packageVersion.trim(),
    env: env as Env,
    org: org.trim(),
    subOrg: subOrg.trim(),
    driver: driver as DriverKey,
    locus: locus as LocusKey,
    replicas: replicas.map((replica) => replica.trim()),
    ...(nonEmptyString(declaredVersion) ? { declaredVersion: declaredVersion.trim() } : {}),
  };
}

/**
 * `id` is the name. The routes key on the id and the grid shows the name,
 * so making them one thing is what stops a declared instance from having
 * two identities an operator has to keep straight.
 */
export function buildDeclaredInstance(
  solutionId: string,
  input: DeclareInstanceInput,
): InstanceRow {
  return {
    id: input.name,
    name: input.name,
    solutionId,
    packageVersion: input.packageVersion,
    env: input.env,
    org: input.org,
    subOrg: input.subOrg,
    driver: input.driver,
    locus: input.locus,
    replicas: input.replicas.map((name) => ({ name, health: UNOBSERVED_REPLICA_HEALTH })),
    ...unobservedRuntimeFacts(input.declaredVersion),
  };
}
