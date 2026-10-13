import type { AdminRepository } from '../adminRepository';

export interface GroupsViewProps {
  repository: AdminRepository;
}

/** The status filter's three positions (§2). `all` is not a group status — it is "do not filter". */
export type GroupStatusFilter = 'all' | 'active' | 'deactivated';
