/**
 * The Monitoring feature's public surface (task 077) — what `@app/routes`
 * or another feature may import. Everything else under
 * `src/features/monitoring/**` is internal.
 */
export { DashboardView, type DashboardViewProps } from './dashboard/DashboardView';
export { defaultDashboardId } from './dashboardRepository';
export { ExplorePage, type ExplorePageProps } from './explore/ExplorePage';
export { MetricsProvider, type MetricsProviderProps } from './MetricsProvider';
