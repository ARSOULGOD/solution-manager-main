import type { ViewStatus } from '@contracts/view-model/types';

export interface BlockedBannerProps {
  status: ViewStatus;
  onAccept: () => void;
  onRestore: () => void;
}
