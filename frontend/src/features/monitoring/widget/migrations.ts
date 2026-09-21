/**
 * Widget config migration engine (task 084, Dec `DM-D6`, `dynamic-metrics`
 * §7's "Migration" subsection).
 *
 * - `schemaVersion` is a monotonic integer; migrations are forward-only,
 *   registered in an ORDERED CHAIN keyed by the version a step migrates
 *   FROM — so `v1 -> v4` runs three functions, never one bespoke jump.
 * - **Unknown fields are preserved on round-trip.** A migration step must
 *   spread its input rather than reconstruct it field-by-field — an older
 *   console must not silently destroy a newer console's widget by saving
 *   it back. This file's own engine does not enforce that by itself (it
 *   cannot inspect what a step function does); `migrations.test.ts` proves
 *   it holds for every registered step, the same "the contract is on the
 *   author, verified by the suite" posture `DM-D20`'s shared fixture suite
 *   uses elsewhere.
 * - A document newer than `CURRENT_WIDGET_SCHEMA_VERSION` migrates to
 *   nothing — `migrateWidgetDocument` reports `too-new` rather than
 *   guessing backwards, so the caller can render it read-only with a
 *   stated reason (`UI-D12`'s rule, mirrored by `DM-D6`).
 *
 * **No live call site exists yet.** Every widget this console creates
 * starts at `CURRENT_WIDGET_SCHEMA_VERSION` (`buildWidgetConfig`), and no
 * "load a saved widget" screen exists yet (`DashboardGrid`, task 086, is
 * where a persisted document first gets read back and where `too-new`
 * actually renders something). This file is real and fully tested against
 * a synthetic chain, not a stub — the same "real code, no live trigger
 * yet" posture `AggregationControl`'s forcing logic (task 080) already
 * set for this feature.
 */
import { CURRENT_WIDGET_SCHEMA_VERSION } from './widgetConfig';

/** The minimum shape any widget document — of any version — must carry. */
export type WidgetDocument = { schemaVersion: number } & Record<string, unknown>;

/** Migrates a document FROM the version this function is keyed under, TO the next one. */
export type MigrationStep = (doc: WidgetDocument) => WidgetDocument;

/**
 * Keyed by the version a step migrates FROM — `MIGRATIONS[1]` turns a `v1`
 * document into a `v2` one. Empty today: the schema is still `v1`
 * (`CURRENT_WIDGET_SCHEMA_VERSION`, `widgetConfig.ts`). A future `v2`
 * registers its own step here and nothing else in this file changes.
 */
export const MIGRATIONS: Readonly<Record<number, MigrationStep>> = {};

export type MigrateResult =
  | { status: 'current'; config: WidgetDocument }
  | { status: 'migrated'; config: WidgetDocument; fromVersion: number }
  /** Declared newer than the console can produce — render read-only, whole, with a stated reason (`DM-D6`). */
  | { status: 'too-new'; declaredVersion: number };

/**
 * Runs the ordered chain from `doc.schemaVersion` up to `targetVersion`
 * (defaults to the console's own current version). `migrations` is
 * injectable so a test can exercise the engine against a synthetic chain
 * without waiting for a real `v2` to exist.
 */
export function migrateWidgetDocument(
  doc: WidgetDocument,
  targetVersion: number = CURRENT_WIDGET_SCHEMA_VERSION,
  migrations: Readonly<Record<number, MigrationStep>> = MIGRATIONS,
): MigrateResult {
  if (doc.schemaVersion > targetVersion) {
    return { status: 'too-new', declaredVersion: doc.schemaVersion };
  }
  if (doc.schemaVersion === targetVersion) {
    return { status: 'current', config: doc };
  }

  let current = doc;
  while (current.schemaVersion < targetVersion) {
    const step = migrations[current.schemaVersion];
    if (step === undefined) {
      // No registered step from this version — the chain cannot reach
      // `targetVersion`. Unreachable in a `v1`-only console; guarded
      // rather than looping forever if a future gap is ever registered.
      break;
    }
    current = step(current);
  }
  return { status: 'migrated', config: current, fromVersion: doc.schemaVersion };
}
