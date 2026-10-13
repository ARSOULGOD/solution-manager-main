/**
 * `ActionsRepository` (task 033, §6.0.5, §9.2, UI §A.8.17). CONTRACT: TBD —
 * route, verdict shape and timeout semantics are unspecified. Sends an
 * action key plus field-KEY references only — never a value (G7): a
 * `test-connection` action must not resend a secret through the browser.
 */
import type { PlaneClient } from '@shared/api';

export interface ActionResult {
  ok: boolean;
  message: string;
}

export interface ActionsRepository {
  run(instanceId: string, actionKey: string): Promise<ActionResult>;
}

export function createActionsRepository(dpClient: PlaneClient): ActionsRepository {
  return {
    run(instanceId, actionKey) {
      return dpClient.post<ActionResult>(
        `/instances/${encodeURIComponent(instanceId)}/actions/${encodeURIComponent(actionKey)}`,
      );
    },
  };
}
