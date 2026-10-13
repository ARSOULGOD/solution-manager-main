/**
 * Isolates one route's render failure so the shell (nav, breadcrumbs,
 * header) stays mounted around it (design §16). `AppShell` (task 036) wraps
 * its routed `<Outlet>` in this boundary — it renders only the failing
 * route's content in place, never a fresh full-page reload.
 */
import { Component, type ErrorInfo, type ReactNode } from 'react';

import { ApiError } from '@shared/api';
import { logger } from '@shared/observability';
import { ErrorPanel } from '@shared/ui/ErrorPanel';

interface RouteErrorBoundaryProps {
  children: ReactNode;
}

interface RouteErrorBoundaryState {
  error: unknown;
}

// A thrown value has no fixed shape; a caller from task 028's typed error
// codec may carry `code`/`correlationId` (`FE-D13`) and this surfaces them
// when present, without depending on that codec existing yet.
function describeError(error: unknown): { detail: string; code?: string; correlationId?: string } {
  if (error instanceof Error) {
    const withFields = error as Error & { code?: unknown; correlationId?: unknown };
    return {
      detail: error.message,
      ...(typeof withFields.code === 'string' ? { code: withFields.code } : {}),
      ...(typeof withFields.correlationId === 'string'
        ? { correlationId: withFields.correlationId }
        : {}),
    };
  }
  return { detail: 'An unexpected error occurred.' };
}

export class RouteErrorBoundary extends Component<
  RouteErrorBoundaryProps,
  RouteErrorBoundaryState
> {
  override state: RouteErrorBoundaryState = { error: undefined };

  static getDerivedStateFromError(error: unknown): RouteErrorBoundaryState {
    return { error };
  }

  override componentDidCatch(error: unknown, info: ErrorInfo) {
    // Narrowed to primitives before logging (task 102): a React `ErrorInfo`
    // and a thrown error both reach arbitrary component state, and this
    // boundary sits above screens that hold Designer draft values.
    logger.error('route.render-failed', {
      message: error instanceof Error ? error.message : String(error),
      correlationId: error instanceof ApiError ? error.correlationId : undefined,
      componentStack: info.componentStack ?? undefined,
    });
  }

  override render() {
    const { error } = this.state;

    if (error !== undefined) {
      return <ErrorPanel title="This page failed to load" {...describeError(error)} />;
    }

    return this.props.children;
  }
}
