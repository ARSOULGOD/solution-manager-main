/**
 * The RBAC overlay (task 059, Dec `FE-D22`, Design §15, UI §A.8). Applied
 * strictly AFTER `project()` — never merged into it — so `project()`'s own
 * fixture suite (shared with the Java implementation, `UI-D15`) stays
 * auth-free and portable:
 *
 *   viewModel = project(package, selection, draft, observed)   // no auth
 *   chrome    = overlayRbac(viewModel, permissions)             // omit/disable
 *
 * The catalog now makes Designer operations grantable, but this pure data
 * overlay remains a no-op until the Designer's field/action permission
 * mapping is ratified. The server still decides each request.
 *
 * TODO(mapping): omit or disable sections/fields/actions here once their
 * field/action mapping is ratified — `project()` itself must never change
 * to do it, per `FE-D22`.
 */
import type { ViewModel } from '@contracts/view-model/types';
import type { PermissionApi } from '@shared/authz/permissions';

// The parameter exists so a future grantable verb can gate on it here without changing this
// function's signature or its callers.
// eslint-disable-next-line @typescript-eslint/no-unused-vars
export function overlayRbac(viewModel: ViewModel, permissions: PermissionApi): ViewModel {
  return viewModel;
}
