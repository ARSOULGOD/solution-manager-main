/**
 * "Off-screen widgets do not query" (task 086, `dynamic-metrics` §13,
 * design §17). An `IntersectionObserver` drives a boolean that
 * `WidgetFrame` feeds straight into its `useQuery`'s `enabled` — the FIRST
 * query for a below-the-fold widget never fires until it scrolls into
 * (near) view.
 *
 * `120px` `rootMargin` mirrors the visual reference's own `WidgetCell`
 * (`docs/design/frontend/reference/app/dashboards.jsx`) — a widget starts
 * loading slightly before it is actually on screen, so scrolling to it
 * does not show a bare skeleton.
 *
 * **Sticky, not reactive** — once visible, always visible. `DM-D17`'s own
 * pause/resume mechanism (`document.hidden`, handled by TanStack Query's
 * `refetchInterval`/`refetchOnWindowFocus` defaults, not this hook) is
 * what governs an already-visited widget scrolled back off-screen; this
 * hook only guards the widget that has never been seen yet, the same
 * "avoid the query nobody has looked at" cost the design text names.
 */
import { useEffect, useState, type RefObject } from 'react';

export function useWidgetVisibility(containerRef: RefObject<Element | null>): boolean {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (visible) {
      return;
    }
    const node = containerRef.current;
    if (node === null) {
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setVisible(true);
        }
      },
      { rootMargin: '120px' },
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, [containerRef, visible]);

  return visible;
}
