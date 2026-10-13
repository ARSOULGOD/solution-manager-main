/**
 * A TYPE test (task 032's acceptance: "gating on a non-grantable verb is a
 * compile-time type error"). No runtime assertions here — `npm run
 * typecheck` is what proves this file: a `@ts-expect-error` line that
 * stops erroring (the catalog widened, or `requireGrantable`'s signature
 * loosened) fails the build, because an unused `@ts-expect-error` is
 * itself a `tsc` error.
 */
import { requireGrantable, type GrantableVerb } from './permissions';

// ---- must compile: ratified operations (`D10` 🔒 + `D18` 🔒) ----
requireGrantable('target.use');
requireGrantable('instance.bindTarget');
requireGrantable('metrics.query');
requireGrantable('instance.restart');
requireGrantable('role.assign');

// ---- must compile: the five ratified `D18` group verbs ----
requireGrantable('group.view');
requireGrantable('group.create');
requireGrantable('group.edit');
requireGrantable('group.deactivate');
requireGrantable('group.member.manage');

// ---- must NOT compile: the two ids `D18` declined ----
// @ts-expect-error — `group.member.view` is not in the ratified catalog; `group.view` covers it.
requireGrantable('group.member.view');
// @ts-expect-error — `group.role.assign` is not in the ratified catalog; `role.assign` covers it.
requireGrantable('group.role.assign');

// ---- must NOT compile: a bare `string`, even one granted at runtime ----
declare const someRuntimeId: string;
// @ts-expect-error — `string` is not assignable to the closed `GrantableVerb` union.
requireGrantable(someRuntimeId);

export type { GrantableVerb };
