import type { ActionsRepository } from '../actionsRepository';
import type { ApplyRepository } from '../applyRepository';
import type { ConfigRepository } from '../configRepository';

export interface DesignerPageProps {
  instanceId: string;
  /** Accept/restore-runtime's `placementId` (`BE-D23`). Absent while the instance query is pending. */
  placementId: string | undefined;
  configRepository: ConfigRepository;
  /** The rollout/apply half (`FE-124`) — `DECISIONS.md` open item 1, resolves only to `INDETERMINATE`. */
  applyRepository: ApplyRepository;
  actionsRepository: ActionsRepository;
}
