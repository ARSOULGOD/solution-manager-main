/**
 * Target inventory fixture (task 033, UI §A.12.6). `TargetsRepository` is
 * read-only — no write method exists anywhere in this seam (§6.0.5):
 * credential entry is intentionally absent, blocked on the `dp-remote`
 * apply protocol (`DECISIONS.md` open item 1).
 */
import type { DriverKey, LocusKey } from '@shared/vocab';

export interface TargetRow {
  id: string;
  location: string;
  driver: DriverKey;
  locus: LocusKey;
  ownershipScope: readonly string[];
}

export const TARGETS: readonly TargetRow[] = [
  {
    id: 'tgt-ledger-k8s',
    location: 'k8s://payments-prod/ledger',
    driver: 'k8s',
    locus: 'dp_remote',
    ownershipScope: ['Acme Financial', 'Payments', 'PROD'],
  },
  {
    id: 'tgt-quotes-vm',
    location: 'vm://trading-quotes-01.internal',
    driver: 'systemd',
    locus: 'host_local',
    ownershipScope: ['Acme Financial', 'Trading', 'PROD'],
  },
  {
    id: 'tgt-eu-observe',
    location: 'k8s://logistics-eu-west/obs',
    driver: 'k8s',
    locus: 'observe_only',
    ownershipScope: ['Acme Financial', 'Logistics'],
  },
];
