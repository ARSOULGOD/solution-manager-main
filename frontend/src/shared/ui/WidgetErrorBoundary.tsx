/**
 * Isolates one dashboard widget's render failure so its siblings keep
 * rendering and refreshing (design §16 "Widget throw" row; UI §A.11).
 * `WidgetFrame` (task 086) wraps each widget in its own instance — a
 * throw here never reaches a sibling's boundary.
 *
 * **Moved here from `src/app/errors/` (task 086).** It was written there
 * (task 006) alongside `RouteErrorBoundary`, but only `RouteErrorBoundary`
 * is genuinely app-layer (`AppShell` wraps its routed `<Outlet>`, task
 * 036). This one has zero app-layer dependencies — same imports as
 * `ErrorPanel.tsx`, which already lives here — and `WidgetFrame` is a
 * `@features/monitoring` component: the module-boundaries policy
 * (`eslint.config.js`) has no `feature -> app` edge, only `app -> feature`
 * and `feature -> shared`, so a feature genuinely cannot import it from
 * `src/app/errors/`. Relocating it, not duplicating it, is what
 * `chartStateTags.ts`'s own "one copy, not two that can drift" convention
 * already asks for elsewhere in this codebase.
 */
import { Component, type ErrorInfo, type ReactNode } from 'react';

import { logger } from '@shared/observability';
import { ErrorPanel } from '@shared/ui/ErrorPanel';

interface WidgetErrorBoundaryProps {
  /** Defaults to a generic widget-scoped message. */
  title?: string;
  children: ReactNode;
}

interface WidgetErrorBoundaryState {
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

export class WidgetErrorBoundary extends Component<
  WidgetErrorBoundaryProps,
  WidgetErrorBoundaryState
> {
  override state: WidgetErrorBoundaryState = { error: undefined };

  static getDerivedStateFromError(error: unknown): WidgetErrorBoundaryState {
    return { error };
  }

  override componentDidCatch(error: unknown, info: ErrorInfo) {
    // Narrowed to primitives before logging (task 102) — a widget's thrown
    // error is one `cause` hop from the metric payload that produced it,
    // and design §20 forbids logging a full metric payload.
    logger.error('widget.render-failed', {
      title: this.props.title ?? undefined,
      message: error instanceof Error ? error.message : String(error),
      componentStack: info.componentStack ?? undefined,
    });
  }

  override render() {
    const { error } = this.state;

    if (error !== undefined) {
      return (
        <ErrorPanel
          title={this.props.title ?? 'This widget failed to render'}
          {...describeError(error)}
        />
      );
    }

    return this.props.children;
  }
}
