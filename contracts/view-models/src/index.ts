/**
 * `@solution-manager/view-models` — the pure view-model engine.
 *
 * The frontend imports `project` from here (`FE-D12`); the Java implementation must pass
 * the same `fixtures/viewmodel/*.json`. Nothing in this entry point imports React, the DOM,
 * or `node:*`.
 */
export { project, hashInput, ENGINE_VERSION } from './project.js';
export type * from './types.js';
