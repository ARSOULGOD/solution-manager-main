import { useMemo } from 'react';
import { useParams } from 'react-router';

import { AdminShell, createAdminRepository } from '@features/admin';
import { createCpClient } from '@shared/api/cpClient';

import { useRuntimeConfig } from '../boot/RuntimeConfig';
import { NotFound } from '../NotFound';
import { isAdminTab } from '../routes';

// `:tab` is validated here, not by the route pattern — an unknown value
// 404s rather than falling through, the same rule `InstanceCategoryRoute`
// applies to `:category` (design §14).
//
// Repository construction lives here, not in the feature, for the same
// reason `InstanceCategoryRoute.tsx` documents: `RuntimeConfig` is an
// app-layer concern a feature may not import directly (boundaries).
export default function AdminRoute() {
  const { tab } = useParams<{ tab: string }>();
  const config = useRuntimeConfig();
  const repository = useMemo(
    () => createAdminRepository(createCpClient(config.apiBasePath)),
    [config.apiBasePath],
  );

  if (tab === undefined || !isAdminTab(tab)) {
    return <NotFound />;
  }

  return <AdminShell tab={tab} repository={repository} />;
}
