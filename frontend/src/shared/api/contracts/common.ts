/**
 * The hand-typed contract seam (task 034, `FE-D7`, design §7, §13).
 * `generated/` (`src/generated/`, `scripts/generate-types.mjs`) will hold
 * OpenAPI output once a real contract exists; until then, every wire type
 * this app depends on that ISN'T yet SPECIFIED to endpoint level lives
 * here, colocated, and is **replaced wholesale** by the generated
 * equivalent when it lands — never extended into a shadow contract.
 *
 * `common.ts` holds the one shape multiple repository files were each
 * declaring their own copy of before this task: paging.
 *
 * // CONTRACT: TBD — the paging model itself (offset? cursor? page token?)
 * // is unspecified anywhere (§9.2: "Route; paging model" listed as
 * // unknown for `InstancesRepository.list()`). `{ items, total }` is this
 * // module's own placeholder shape, not a promise about the real one.
 */
export interface Paged<T> {
  items: readonly T[];
  total: number;
}
