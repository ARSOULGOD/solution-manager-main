/**
 * Designer OBSERVATION fixtures (task 033, UI §A.13.3; trimmed by FE-122
 * and again by FE-101). Ported from
 * `docs/design/frontend/reference/app/designer_data.jsx`'s rollout / diff
 * fixtures.
 *
 * The Designer's CONFIG-SCHEMA half — what used to live here as `VmField`
 * / `VmSection` / `VmComponent` / `DesignerViewModel`, `CACHE_VIEW_MODEL`
 * and `FILE_MESH_VIEW_MODEL` — is gone (`FE-D33`, task 122): the browser
 * now projects a real `SolutionPackage` (`contracts/packages/<solution>/package.json`)
 * through `project()` itself, rather than reading a pre-baked view model.
 * `RolloutCard`, `ReplicaTable` and `ConflictDialog` still need OBSERVED
 * facts `project()` cannot produce — replica health, a draft-vs-deployed
 * diff — which is what stays below.
 *
 * The **restart-strategy catalog** also used to live here, and FE-101 caught
 * it shipping: `DesignerForm.tsx` imported it as a VALUE, which pulled this
 * entire module into the production bundle (every other importer uses
 * `import type`, which is erased). It is a vocabulary the console renders
 * rather than a stand-in for a backend's answer, so it moved to
 * `src/features/designer/restartStrategies.ts` — see that file's header.
 *
 * ⚠️ **Nothing in this module may be imported as a value by shipped code.**
 * `npm run verify:production`'s `no-fixture-dataset` assertion fails the
 * build if it is.
 */

// ---- Rollout / replica fixtures (§A.13.3). renderedConfigVersion and
// restartObserved are INVENTED column names — no row schema exists (§16 Q12).
export type ReplicaHealth = 'GREEN' | 'RED' | 'RESTART_EXPECTED' | 'INACTIVE_REPLICA';

export interface ReplicaObservationFixture {
  id: string;
  generation: string;
  restartObserved: boolean;
  health: ReplicaHealth;
}

export const ROLLOUT_REPLICAS: readonly ReplicaObservationFixture[] = [
  { id: 'ledger-0', generation: 'gen-41', restartObserved: true, health: 'GREEN' },
  { id: 'ledger-1', generation: 'gen-41', restartObserved: true, health: 'GREEN' },
  { id: 'ledger-2', generation: 'gen-41', restartObserved: false, health: 'RESTART_EXPECTED' },
  { id: 'ledger-3', generation: 'gen-40', restartObserved: false, health: 'INACTIVE_REPLICA' },
];
export const DIVERGED_REPLICAS: readonly ReplicaObservationFixture[] = [
  { id: 'ledger-0', generation: 'gen-41', restartObserved: true, health: 'GREEN' },
  { id: 'ledger-1', generation: 'gen-41', restartObserved: true, health: 'GREEN' },
  { id: 'ledger-2', generation: 'gen-40', restartObserved: true, health: 'GREEN' },
  { id: 'ledger-3', generation: 'gen-40', restartObserved: true, health: 'RED' },
];

// ---- Diff fixtures — draft-vs-deployed, and a 409 "theirs" set. ----
export const SECRET_MASK_TOKEN = '•••••••• (secret)';

export interface DiffRowFixture {
  path: string;
  kind: 'add' | 'remove' | 'change' | 'rename';
  old: string | null;
  new: string;
  secret?: boolean;
  note?: string;
}

export const DIFF_DRAFT: readonly DiffRowFixture[] = [
  { path: 'cache-engine.namespace', kind: 'change', old: 'ledger', new: 'ledger-v2' },
  { path: 'cache-engine.max-size', kind: 'change', old: '100000', new: '5000000' },
  { path: 'cache-engine.ttl', kind: 'add', old: null, new: '120m' },
  {
    path: 'cache-engine.solace.password',
    kind: 'change',
    secret: true,
    old: SECRET_MASK_TOKEN,
    new: SECRET_MASK_TOKEN,
  },
];

export const CONFLICT_THEIRS: readonly DiffRowFixture[] = [
  {
    path: 'cache-engine.namespace',
    kind: 'change',
    old: 'ledger-v2',
    new: 'ledger-prod',
    note: 'yours → theirs',
  },
  {
    path: 'cache-engine.max-size',
    kind: 'change',
    old: '5000000',
    new: '250000',
    note: 'yours → theirs',
  },
  {
    path: 'cache-engine.solace.password',
    kind: 'change',
    secret: true,
    old: SECRET_MASK_TOKEN,
    new: SECRET_MASK_TOKEN,
    note: 'yours → theirs',
  },
];
