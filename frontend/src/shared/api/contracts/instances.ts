/**
 * Hand-typed instance wire shapes (task 115) — same seam and same rules as
 * `common.ts`: replaced wholesale when a generated contract lands, never
 * extended into a shadow contract.
 *
 * // CONTRACT: TBD — §16 Q10 defines no declare API, no field set and no
 * // entitlement-check shape. `DeclareInstanceInput` is this module's own
 * // placeholder, not a promise about the real one. It carries the
 * // IDENTITY of an instance and nothing else; every observed fact
 * // (health, config state, `version.observed`, `lastSeen`) is derived by
 * // the server, because those are reported by an instance rather than
 * // asserted by whoever declares it.
 */
import type { Env } from '@shared/testdoubles/data/instances';
import type { DriverKey, LocusKey } from '@shared/vocab';

export interface DeclareInstanceInput {
  name: string;
  /**
   * The package version this instance is configured against (FE-118).
   * REQUIRED, and it belongs here — unlike health, config state or
   * `version.observed`, this is IDENTITY: nothing observes it, the
   * operator chooses it, and the Designer cannot render the right field
   * set without it. There is deliberately no default: a wrongly-defaulted
   * package version renders fields that do not exist for this instance,
   * silently.
   */
  packageVersion: string | number;
  env: Env;
  org: string;
  subOrg: string;
  driver: DriverKey;
  locus: LocusKey;
  /** At least one, unique. NAMES only — a replica's health is observed, never declared. */
  replicas: readonly string[];
  /** Omitted when the operator doesn't know it yet — never guessed. */
  declaredVersion?: string;
  /** Present only for the local SSH Docker demo transport. The repository uploads the key separately. */
  sshDocker?: SshDockerDeclaration;
}

export interface SshDockerDeclaration {
  host: string;
  port: number;
  user: string;
  keyFile: File;
  pinnedHostKeyFingerprint: string;
  containerName: string;
  remoteConfigDirectory: string;
  containerConfigPath: string;
  configFileName: string;
}
