import type { ActionsRepository } from '../actionsRepository';
import type { ApplyRepository } from '../applyRepository';
import type { ConfigRepository } from '../configRepository';

export interface DesignerPageProps {
  instanceId: string;
  configRepository: ConfigRepository;
  /** The rollout/apply half (`FE-124`) — `DECISIONS.md` open item 1, resolves only to `INDETERMINATE`. */
  applyRepository: ApplyRepository;
  actionsRepository: ActionsRepository;
}
