/**
 * Instance inventory fixture (task 033, UI §A.13.2). Ported field-for-field
 * from `docs/design/frontend/reference/app/data.jsx`'s `CACHE_INSTANCES`
 * and `ATTENTION` — that file's own header cites `architecture.md` as the
 * source for which solution is v1 (Cache) vs phase 2 (File Mesh). State
 * KEYS below reuse `shared/vocab` (task 013) rather than re-typing the
 * vocabulary; this module supplies only the per-instance DATA the reference
 * drew, none of which has a real endpoint yet.
 *
 * SPECIFIED: the state vocabularies themselves (`shared/vocab`, cited
 * there). INVENTED (no endpoint anywhere — §9.2, §16 Q10): every field
 * below — env/org/sub-org/driver/locus/replica composition/lastSeen — and
 * the entire attention feed, its severity vocabulary, ordering and dedupe
 * rules.
 */
import type { PackageVersionId } from '@contracts/view-model/types';
import type { DriverKey, HealthKey, LocusKey, NotFoundKey, VersionKey } from '@shared/vocab';

export type Env = 'DEV' | 'TEST' | 'PROD';

export interface ReplicaRow {
  name: string;
  health: HealthKey;
}

export interface InstanceRow {
  id: string;
  name: string;
  solutionId: string;
  /**
   * The package version this instance is CONFIGURED AGAINST (FE-118) — the
   * schema `project()` renders its Designer from, not the MI build it runs
   * (that is `version.declared`). Two different things, both called
   * "version"; the declare form's copy says which is which.
   */
  packageVersion: PackageVersionId;
  env: Env;
  org: string;
  subOrg: string;
  driver: DriverKey;
  locus: LocusKey;
  replicas: readonly ReplicaRow[];
  /** `'notfound'` defers to `notFoundReason` for the specific condition (never one "missing" badge). */
  healthRollup: HealthKey | 'notfound';
  notFoundReason?: NotFoundKey;
  version: { state: VersionKey; observed: string; declared: string };
  configState: 'in_sync' | 'staged' | 'rendered' | 'diverged' | 'drift' | 'na';
  /** `'never'` for a not-yet-observed (bootstrap) instance — never a fabricated timestamp. */
  lastSeen: string;
}

// Twelve instances spanning EVERY health / not-found / version / config
// state (§A.13.2's own enumeration, reproduced in the comment on each row).
export const CACHE_INSTANCES: readonly InstanceRow[] = [
  {
    // healthy multi-replica WITH a standby
    id: 'cache-prod-01',
    name: 'cache-prod-01',
    solutionId: 'cache',
    packageVersion: 7,
    env: 'PROD',
    org: 'Acme Financial',
    subOrg: 'Payments',
    driver: 'k8s',
    locus: 'dp_remote',
    replicas: [
      { name: 'ledger-0', health: 'healthy' },
      { name: 'ledger-1', health: 'healthy' },
      { name: 'ledger-2', health: 'inactive_replica' },
    ],
    healthRollup: 'healthy',
    version: { state: 'ok', observed: '2.3.0', declared: '2.3.0' },
    configState: 'in_sync',
    lastSeen: '12s ago',
  },
  {
    // version drift
    id: 'cache-prod-02',
    name: 'cache-prod-02',
    solutionId: 'cache',
    packageVersion: 7,
    env: 'PROD',
    org: 'Acme Financial',
    subOrg: 'Payments',
    driver: 'k8s',
    locus: 'dp_remote',
    replicas: [
      { name: 'ledger-b-0', health: 'healthy' },
      { name: 'ledger-b-1', health: 'healthy' },
    ],
    healthRollup: 'healthy',
    version: { state: 'drift', observed: '2.4.1', declared: '2.3.0' },
    configState: 'in_sync',
    lastSeen: '9s ago',
  },
  {
    // version unverified on a plain VM
    id: 'cache-prod-03',
    name: 'cache-prod-03',
    solutionId: 'cache',
    packageVersion: 6,
    env: 'PROD',
    org: 'Acme Financial',
    subOrg: 'Trading',
    driver: 'systemd',
    locus: 'host_local',
    replicas: [{ name: 'app01', health: 'healthy' }],
    healthRollup: 'healthy',
    version: { state: 'unverified', observed: '—', declared: '2.3.0' },
    configState: 'in_sync',
    lastSeen: '20s ago',
  },
  {
    // four-replica healthy instance
    id: 'cache-prod-04',
    name: 'cache-prod-04',
    solutionId: 'cache',
    packageVersion: 7,
    env: 'PROD',
    org: 'Acme Financial',
    subOrg: 'Trading',
    driver: 'k8s',
    locus: 'dp_remote',
    replicas: [
      { name: 'quotes-0', health: 'healthy' },
      { name: 'quotes-1', health: 'healthy' },
      { name: 'quotes-2', health: 'healthy' },
      { name: 'quotes-3', health: 'healthy' },
    ],
    healthRollup: 'healthy',
    version: { state: 'ok', observed: '2.3.0', declared: '2.3.0' },
    configState: 'in_sync',
    lastSeen: '3s ago',
  },
  {
    // staged config with one replica down
    id: 'cache-stage-01',
    name: 'cache-stage-01',
    solutionId: 'cache',
    packageVersion: 6,
    env: 'TEST',
    org: 'Acme Financial',
    subOrg: 'Trading',
    driver: 'docker',
    locus: 'dp_remote',
    replicas: [
      { name: 'c1', health: 'healthy' },
      { name: 'c2', health: 'not_responding' },
    ],
    healthRollup: 'not_responding',
    version: { state: 'ok', observed: '2.3.0', declared: '2.3.0' },
    configState: 'staged',
    lastSeen: '6s ago',
  },
  {
    // restart-expected replica
    id: 'cache-stage-02',
    name: 'cache-stage-02',
    solutionId: 'cache',
    packageVersion: 7,
    env: 'TEST',
    org: 'Acme Financial',
    subOrg: 'Risk',
    driver: 'k8s',
    locus: 'host_local',
    replicas: [
      { name: 'batch-0', health: 'healthy' },
      { name: 'batch-1', health: 'restart_expected' },
      { name: 'batch-2', health: 'healthy' },
    ],
    healthRollup: 'restart_expected',
    version: { state: 'ok', observed: '2.3.0', declared: '2.3.0' },
    configState: 'in_sync',
    lastSeen: '4s ago',
  },
  {
    // diverged config
    id: 'cache-qa-01',
    name: 'cache-qa-01',
    solutionId: 'cache',
    packageVersion: 6,
    env: 'TEST',
    org: 'Acme Financial',
    subOrg: 'Onboarding',
    driver: 'docker',
    locus: 'dp_remote',
    replicas: [
      { name: 'kyc-a', health: 'healthy' },
      { name: 'kyc-b', health: 'healthy' },
    ],
    healthRollup: 'healthy',
    version: { state: 'ok', observed: '2.3.0', declared: '2.3.0' },
    configState: 'diverged',
    lastSeen: '5s ago',
  },
  {
    // unreachable host, version unknown
    id: 'cache-edge-01',
    name: 'cache-edge-01',
    solutionId: 'cache',
    packageVersion: 6,
    env: 'PROD',
    org: 'Acme Financial',
    subOrg: 'Logistics',
    driver: 'docker',
    locus: 'dp_remote',
    replicas: [
      { name: 'eu-1', health: 'not_responding' },
      { name: 'eu-2', health: 'not_responding' },
    ],
    healthRollup: 'notfound',
    notFoundReason: 'unreachable',
    version: { state: 'unknown', observed: '?', declared: '2.2.0' },
    configState: 'in_sync',
    lastSeen: '8m ago',
  },
  {
    // observe-only target, config N/A
    id: 'cache-edge-02',
    name: 'cache-edge-02',
    solutionId: 'cache',
    packageVersion: 7,
    env: 'PROD',
    org: 'Acme Financial',
    subOrg: 'Logistics',
    driver: 'k8s',
    locus: 'observe_only',
    replicas: [
      { name: 'obs-0', health: 'not_applicable' },
      { name: 'obs-1', health: 'not_applicable' },
    ],
    healthRollup: 'not_applicable',
    version: { state: 'unverified', observed: '—', declared: '2.3.0' },
    configState: 'na',
    lastSeen: '15s ago',
  },
  {
    // never-observed bootstrap instance
    id: 'cache-dev-01',
    name: 'cache-dev-01',
    solutionId: 'cache',
    packageVersion: 7,
    env: 'DEV',
    org: 'Acme Financial',
    subOrg: 'Risk',
    driver: 'docker',
    locus: 'dp_remote',
    replicas: [{ name: 'dev-0', health: 'restart_expected' }],
    healthRollup: 'notfound',
    notFoundReason: 'bootstrap',
    version: { state: 'unverified', observed: '—', declared: '2.4.0' },
    configState: 'rendered',
    lastSeen: 'never',
  },
  {
    // config-drift instance that is "missing"
    id: 'cache-dev-02',
    name: 'cache-dev-02',
    solutionId: 'cache',
    packageVersion: 6,
    env: 'DEV',
    org: 'Acme Financial',
    subOrg: 'Onboarding',
    driver: 'systemd',
    locus: 'host_local',
    replicas: [{ name: 'vm-dev', health: 'not_responding' }],
    healthRollup: 'notfound',
    notFoundReason: 'missing',
    version: { state: 'ok', observed: '2.3.0', declared: '2.3.0' },
    configState: 'drift',
    lastSeen: '41m ago',
  },
  {
    // health not exposed
    id: 'cache-dev-03',
    name: 'cache-dev-03',
    solutionId: 'cache',
    packageVersion: 7,
    env: 'DEV',
    org: 'Acme Financial',
    // Was the placeholder '—' ("no sub-org"). `FE-D46` makes Sub-org a
    // mandatory level of the cascade, which has no room for a hole here.
    subOrg: 'Sandbox',
    driver: 'systemd',
    locus: 'host_local',
    replicas: [{ name: 'sbx', health: 'not_responding' }],
    healthRollup: 'notfound',
    notFoundReason: 'not_exposed',
    version: { state: 'unverified', observed: '—', declared: '2.3.0' },
    configState: 'in_sync',
    lastSeen: '30s ago',
  },
] as const;

export interface AttentionItem {
  id: string;
  severity: 'err' | 'warn' | 'info';
  title: string;
  detail: string;
  instanceId: string;
  href: string;
}

// The reference's own fixture set (§A.4) — severity vocabulary, ordering
// and dedupe rules have nothing behind them (Q10).
export const ATTENTION_FEED: readonly AttentionItem[] = [
  {
    id: 'att-1',
    severity: 'err',
    title: 'cache-stage-01 · replica not responding',
    detail: '1 of 2 replicas stopped answering health checks — TEST · Acme Financial / Trading.',
    instanceId: 'cache-stage-01',
    href: '/solutions/cache/instances?focus=cache-stage-01',
  },
  {
    id: 'att-2',
    severity: 'err',
    title: 'cache-edge-01 · host unreachable',
    detail:
      'Both replicas unreachable for 8m in PROD · Acme Financial / Logistics. A host or network problem — not the instance.',
    instanceId: 'cache-edge-01',
    href: '/solutions/cache/instances?focus=cache-edge-01',
  },
  {
    id: 'att-3',
    severity: 'err',
    title: 'cache-qa-01 · configuration diverged',
    detail:
      'Replicas are split across config versions after a halted rollout. Remediation required.',
    instanceId: 'cache-qa-01',
    href: '/solutions/cache/instances?focus=cache-qa-01',
  },
  {
    id: 'att-4',
    severity: 'warn',
    title: 'Configuration staged, not applied',
    detail:
      '1 instance has config written but awaiting restart. Written is not working — a restart is required to apply it.',
    instanceId: 'cache-stage-01',
    href: '/solutions/cache/instances?focus=cache-stage-01',
  },
  {
    id: 'att-5',
    severity: 'warn',
    title: 'Version drift · cache-prod-02',
    detail:
      'Deployed 2.4.1, declared 2.3.0. Config is read-only for this instance until reconciled.',
    instanceId: 'cache-prod-02',
    href: '/solutions/cache/instances?focus=cache-prod-02',
  },
  {
    id: 'att-6',
    severity: 'warn',
    title: 'Version unknown · cache-edge-01',
    detail:
      'No published package covers the running version. Config editing is blocked; monitoring continues.',
    instanceId: 'cache-edge-01',
    href: '/solutions/cache/instances?focus=cache-edge-01',
  },
  {
    id: 'att-7',
    severity: 'info',
    title: '12 metric series arriving unclassified',
    detail:
      'Ingest is receiving series that match no declared metric. Classify them into the contract, or they are dropped and counted.',
    instanceId: 'cache-prod-01',
    href: '/solutions/cache/instances',
  },
] as const;
