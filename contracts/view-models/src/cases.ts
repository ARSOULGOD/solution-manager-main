/**
 * The shared view-model fixture cases (`UI-D15`, `FE-D12`, `DM-D20`).
 *
 * Inputs and the property each case pins live here; the expected outputs are GENERATED
 * into `fixtures/viewmodel/*.json` and committed, so the Java implementation has a target
 * it must match byte-for-byte. Same discipline as the File Mesh adapter: generate, then
 * review, then freeze.
 */
import type { Catalog, ComponentSelection, DraftValues, ObservedState } from './types.js';

export interface Case {
  readonly name: string;
  readonly pins: string;
  readonly pkg: 'cache-2.4' | 'filemesh-ftp-solace' | 'engine-edge';
  readonly selection: ComponentSelection;
  readonly draft: DraftValues;
  readonly observed: ObservedState;
  readonly catalog?: Catalog;
  /** Phase-2 cases still run in CI; only the console's control registry gates on this. */
  readonly phase?: 2;
}

const CACHE: ComponentSelection = { instances: [{ instanceKey: 'i1', type: 'cache-engine' }] };
const FTP: ComponentSelection = {
  instances: [
    { instanceKey: 'src', type: 'COMP_SOURCE_FTP@FILETOFILE', layout: { x: 10, y: 20 } },
    { instanceKey: 'snk', type: 'COMP_SINK_SOLACE@FILETOFILE', layout: { x: 300, y: 20 } },
  ],
};
const OK: ObservedState = {
  version: { declared: '2.4', observed: '2.4', state: 'OK' },
  replicas: [{ id: 'r1', health: 'GREEN' }],
};
const UNVERIFIED: ObservedState = { version: { declared: '2.4', state: 'VERSION_UNVERIFIED' } };

export const CASES: readonly Case[] = [
  {
    name: 'defaults-applied-matchIfMissing',
    pins: 'An empty draft yields provider=caffeine from `default` (which came from matchIfMissing, not a Java initializer), so cache.hit_rate is collectable. The exact bug UI-D2 names.',
    pkg: 'cache-2.4', selection: CACHE, draft: { i1: {} },
    observed: { ...OK, restartObservedValues: { i1: { provider: 'caffeine' } } },
  },
  {
    name: 'default-does-not-mask-explicit-value',
    pins: 'An explicit draft value beats the default, and valueSource says which won.',
    pkg: 'cache-2.4', selection: CACHE, draft: { i1: { provider: 'redis' } }, observed: OK,
  },
  {
    name: 'metric-not-applicable-when-provider-switched',
    pins: 'provider=redis in the RUNNING config makes cache.size not-applicable — the plausible-wrong-zero UI-D7 exists to prevent.',
    pkg: 'cache-2.4', selection: CACHE, draft: { i1: { provider: 'redis' } },
    observed: { ...OK, restartObservedValues: { i1: { provider: 'redis' } } },
  },
  {
    name: 'metric-pending-config-change-between-apply-and-restart',
    pins: 'Draft says redis, restart-observed still says caffeine: pending-config-change, NOT retirement. Gating on the draft here would confirm a false outage (UI-D7).',
    pkg: 'cache-2.4', selection: CACHE, draft: { i1: { provider: 'redis' } },
    observed: { ...OK, restartObservedValues: { i1: { provider: 'caffeine' } } },
  },
  {
    name: 'predicate-strict-equality-no-coercion',
    pins: '"true" normalizes to true for a bool field; "yes" does not and raises a bind block. UI-D2 is strict equality.',
    pkg: 'cache-2.4', selection: CACHE,
    draft: { i1: { 'solace.enabled': 'true', 'solace.management.enabled': 'yes' } }, observed: OK,
  },
  {
    name: 'predicate-and-of-two',
    pins: 'solace.management.management-queue needs BOTH chain keys; flipping either hides it (UI-D3 full ancestor chain).',
    pkg: 'cache-2.4', selection: CACHE,
    draft: { i1: { 'solace.enabled': true, 'solace.management.enabled': true, 'solace.management.management-queue': 'mgmt-q' } },
    observed: OK,
  },
  {
    name: 'bool-discriminator-hides-branch-and-action',
    pins: 'solace.enabled=false hides all seven broker fields AND the TEST_BROKER_CONNECTION action. Pins gate 6 widening to enum|bool.',
    pkg: 'cache-2.4', selection: CACHE, draft: { i1: { 'solace.enabled': false } }, observed: OK,
  },
  {
    name: 'inactive-branch-retained-not-rendered',
    pins: 'A value typed under solace.enabled=true survives switching it off — it stays in the draft, leaves `rendered`, and appears in `excluded` as hidden (UI-D4).',
    pkg: 'cache-2.4', selection: CACHE,
    draft: { i1: { 'solace.enabled': false, 'solace.host': 'tcp://kept:55555' } }, observed: OK,
  },
  {
    name: 'secret-never-resolved-in-viewmodel',
    pins: 'solace.password projects to a secretRef/secretUnset union with no arm able to carry a value (I6, FE-D23).',
    pkg: 'cache-2.4', selection: CACHE,
    draft: { i1: { 'solace.enabled': true, 'solace.password': 'secretRef://vault/cache/pw' } }, observed: OK,
  },
  {
    name: 'advanced-is-disclosure-not-visibility',
    pins: "Cache's Expiry section is entirely advanced: all fields visible, all eligible for `rendered`, allVisibleFieldsAdvanced=true.",
    pkg: 'cache-2.4', selection: CACHE,
    draft: { i1: { 'expiry-header-key-pointer': 'expireAfter' } }, observed: OK,
  },
  {
    name: 'bind-blocks-semantic-warns',
    pins: 'max-size=abc BLOCKS — the binder would fail and the MI would not start. ttl=120 is valid Spring simple-duration syntax (millis) so it does not block.',
    pkg: 'cache-2.4', selection: CACHE,
    draft: { i1: { 'max-size': 'abc', ttl: '120' } }, observed: OK,
  },
  {
    name: 'semantic-tier-warns-never-blocks',
    pins: 'max-size=5000000 exceeds the curated ceiling and only WARNS, because the ceiling is Solace’s assertion, not the MI’s rule (UI-D16).',
    pkg: 'cache-2.4', selection: CACHE,
    draft: { i1: { 'max-size': 5_000_000 } }, observed: OK,
  },
  {
    name: 'duration-bare-P-and-PT-block',
    pins: 'Java Duration.parse rejects bare P and PT — at least one section is required, and one after T. A regex with every section optional accepted both and would have green-lit a config that stops the MI starting.',
    pkg: 'cache-2.4', selection: CACHE, draft: { i1: { ttl: 'PT' } }, observed: OK,
  },
  {
    name: 'oversized-int-blocks',
    pins: 'An integer beyond 2^53 is not exactly representable, so SM would write a number it did not read.',
    pkg: 'cache-2.4', selection: CACHE, draft: { i1: { 'max-size': '99999999999999999999' } }, observed: OK,
  },
  {
    name: 'secret-literal-is-refused-and-never-rendered',
    pins: 'A literal typed into a secret field blocks with SECRET_NOT_A_REFERENCE and is excluded from `rendered`. Without this it flowed verbatim into the payload and into FieldValue.ref (I6).',
    pkg: 'cache-2.4', selection: CACHE,
    draft: { i1: { 'solace.enabled': true, 'solace.password': 'hunter2' } }, observed: OK,
  },
  {
    name: 'empty-string-falls-back-to-default',
    pins: 'Clearing a field to "" is treated as unset, so its default reapplies. Stated because it is not obvious and an operator cannot therefore clear a defaulted field to empty.',
    pkg: 'cache-2.4', selection: CACHE, draft: { i1: { mode: '' } }, observed: OK,
  },
  {
    name: 'version-drift-blocks-editing',
    pins: 'VERSION_DRIFT disables every field and action and states a reason; UNVERIFIED does not (UI-D12).',
    pkg: 'cache-2.4', selection: CACHE, draft: { i1: {} },
    observed: { version: { declared: '2.4', observed: '2.3', state: 'VERSION_DRIFT' } },
  },
  {
    name: 'version-unverified-does-not-block',
    pins: "Cache-on-a-VM's permanent state must remain editable, or v1 is unusable (UI-D12).",
    pkg: 'cache-2.4', selection: CACHE, draft: { i1: {} }, observed: UNVERIFIED,
  },
  {
    name: 'health-declared-inactive-replica-is-not-red',
    pins: 'Two standby replicas roll up to INACTIVE_REPLICA, not RED — the active_standby case UI-D9 was corrected for.',
    pkg: 'cache-2.4', selection: CACHE, draft: { i1: {} },
    observed: { ...OK, replicas: [{ id: 'r1', health: 'INACTIVE_REPLICA' }, { id: 'r2', health: 'INACTIVE_REPLICA' }] },
  },
  {
    name: 'orphan-draft-key-preserved-not-rendered',
    pins: 'A draft key the package does not declare is excluded with a diagnostic and never rendered.',
    pkg: 'cache-2.4', selection: CACHE,
    draft: { i1: { 'cache-engine.does-not-exist': 'x' } }, observed: OK,
  },
  {
    name: 'fm-virtual-visible-never-rendered',
    pins: 'File Mesh virtual fields are visible with renderedPath=null and appear in `excluded` as virtual. 54 of them; without this they would be written into the MI config file.',
    pkg: 'filemesh-ftp-solace', selection: FTP, draft: { src: {}, snk: {} }, observed: UNVERIFIED,
  },
  {
    name: 'fm-rendered-path-join-empty-configpath',
    pins: 'File Mesh declares configPath:"" so renderedPath === key; Cache declares cache-engine so it is prefixed.',
    pkg: 'filemesh-ftp-solace', selection: FTP,
    draft: { src: { 'file.source.solace_out.destination': 'topic/out' }, snk: {} }, observed: UNVERIFIED,
  },
  {
    name: 'fm-health-not-declared-is-not-applicable',
    pins: 'All four File Mesh components declare no health block. Rollup is NOT_APPLICABLE, never RED (REQ-009, I1).',
    pkg: 'filemesh-ftp-solace', selection: FTP, draft: { src: {}, snk: {} }, observed: UNVERIFIED,
  },
  {
    name: 'fm-layout-excluded-from-hash',
    pins: 'Two components carry layout coordinates; neither reaches `rendered`, so hashInput is unaffected (UI-D20).',
    pkg: 'filemesh-ftp-solace',
    selection: { instances: [
      { instanceKey: 'src', type: 'COMP_SOURCE_FTP@FILETOFILE', layout: { x: 999, y: 999 } },
      { instanceKey: 'snk', type: 'COMP_SINK_SOLACE@FILETOFILE', layout: { x: 1, y: 1 } },
    ] },
    draft: { src: {}, snk: {} }, observed: UNVERIFIED,
  },
  {
    name: 'health-declared-no-replicas-is-red',
    pins: 'A declared probe with zero replica observations is RED — the instance exists and nothing answered. Deliberate, not incidental: callers must supply replicas as soon as they have them.',
    pkg: 'cache-2.4', selection: CACHE, draft: { i1: {} },
    observed: { version: { declared: '2.4', observed: '2.4', state: 'OK' }, replicas: [] },
  },
  {
    name: 'edge-multi-key-predicate-collectability',
    pins: 'Running config fails one key, draft fails two — both non-collectable, so this is NOT pending. Comparing failed-key COUNTS said pending; comparing collectability says not-applicable. All 21 original fixtures had single-key predicates and passed under either semantics.',
    pkg: 'engine-edge',
    selection: { instances: [{ instanceKey: 'e', type: 'edge' }] },
    draft: { e: { mode: 'sftp', tls: false } },
    observed: { version: { state: 'OK' }, replicas: [{ id: 'r1', health: 'GREEN' }],
                restartObservedValues: { e: { mode: 'sftp', tls: true } } },
  },
  {
    name: 'edge-multi-key-predicate-genuinely-pending',
    pins: 'Running config is collectable, draft is not: THAT is pending-config-change.',
    pkg: 'engine-edge',
    selection: { instances: [{ instanceKey: 'e', type: 'edge' }] },
    draft: { e: { mode: 'sftp', tls: true } },
    observed: { version: { state: 'OK' }, replicas: [{ id: 'r1', health: 'GREEN' }],
                restartObservedValues: { e: { mode: 'ftp', tls: true } } },
  },
  {
    name: 'edge-gating-values-are-normalized',
    pins: "restartObservedValues arrive from a properties file, so tls is the STRING 'true'. Normalizing it through the same per-field function is what stops a permanently-pending metric.",
    pkg: 'engine-edge',
    selection: { instances: [{ instanceKey: 'e', type: 'edge' }] },
    draft: { e: { mode: 'ftp', tls: true } },
    observed: { version: { state: 'OK' }, replicas: [{ id: 'r1', health: 'GREEN' }],
                restartObservedValues: { e: { mode: 'ftp', tls: 'true' } } },
  },
  {
    name: 'edge-section-order-follows-declaration-not-alphabet',
    pins: 'Zulu section is declared first and must render first. Taking section order from the first field’s ui.order tied every section at 1 and fell through to an alphabetical tiebreak.',
    pkg: 'engine-edge',
    selection: { instances: [{ instanceKey: 'e', type: 'edge' }] },
    draft: { e: {} }, observed: { version: { state: 'OK' }, replicas: [{ id: 'r1', health: 'GREEN' }] },
  },
  {
    name: 'edge-both-secrecy-spellings-are-equivalent',
    pins: 'secret:true and type:"secret" produce identical masked/controlKind. One guarantee, two spellings, one behaviour (gate 13).',
    pkg: 'engine-edge',
    selection: { instances: [{ instanceKey: 'e', type: 'edge' }] },
    draft: { e: { 'pw-by-flag': 'secretRef://v/a', 'pw-by-type': 'secretRef://v/b' } },
    observed: { version: { state: 'OK' }, replicas: [{ id: 'r1', health: 'GREEN' }] },
  },
  {
    name: 'edge-catalog-supplied-options',
    pins: 'An optionsSource field takes its options from the supplied catalog; an unsupplied catalog raises CATALOG_UNRESOLVED rather than rendering an empty select silently.',
    pkg: 'engine-edge',
    selection: { instances: [{ instanceKey: 'e', type: 'edge' }] },
    draft: { e: {} }, observed: { version: { state: 'OK' }, replicas: [{ id: 'r1', health: 'GREEN' }] },
    catalog: { broker: ['prod-eu', 'prod-us'] },
  },
  {
    name: 'edge-object-value-hashes-canonically',
    pins: 'A map value re-entered with keys in a different order must hash identically. JSON.stringify preserves insertion order, so an uncanonicalized hash reported a phantom config change.',
    pkg: 'engine-edge',
    selection: { instances: [{ instanceKey: 'e', type: 'edge' }] },
    draft: { e: { labels: { b: '2', a: '1' } } },
    observed: { version: { state: 'OK' }, replicas: [{ id: 'r1', health: 'GREEN' }] },
  },
  {
    name: 'unknown-component-in-selection-diagnoses',
    pins: 'A selection naming a component type the package does not declare produces a diagnostic, not a crash.',
    pkg: 'cache-2.4',
    selection: { instances: [{ instanceKey: 'i1', type: 'cache-engine' }, { instanceKey: 'x', type: 'no-such-component' }] },
    draft: { i1: {} }, observed: OK,
  },
];
