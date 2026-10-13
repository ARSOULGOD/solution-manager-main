/**
 * The `inventory` feature's public barrel — required for a sibling
 * feature (e.g. `instance`, task 044) to legally reuse `instances`'
 * repository seam: the boundaries policy only allows cross-feature
 * imports through a feature's top-level `index.ts`, and `inventory` is a
 * multi-module feature (`instances/`, `solutions/`) with no such file
 * until now.
 */
export * from './instances';
