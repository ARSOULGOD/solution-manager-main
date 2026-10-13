/**
 * Generic chart-instance lifecycle: create once a container is mounted,
 * dispose on unmount, keep it sized via `ResizeObserver`. Deliberately
 * knows nothing about ECharts — `createInstance` is a caller-supplied
 * factory, so this file never imports `echarts` (`FE-D16`, task 009's
 * `no-restricted-imports` exemption names only `ChartRenderer.tsx`).
 *
 * Recreation is keyed on `createInstance`'s identity, not on data:
 * `ChartRenderer` memoizes the factory per MUI theme (ECharts themes are
 * init-time-only, so a mode change needs a real dispose+recreate) and
 * leaves it stable across a data-only rerender, which applies its change
 * via `setOption` on the existing instance instead.
 *
 * **Task 126 — creation is serialised through `chainRef`.** `main.tsx`
 * renders the app inside `<StrictMode>`, so React invokes this effect,
 * runs its cleanup, and invokes it again, all within one commit. When both
 * passes were allowed to have a `createInstance` call in flight at once,
 * the two collided on a property of `echarts.init` that this file cannot
 * see: initialising a node that already carries an instance returns the
 * EXISTING instance rather than a second one. Pass 1's continuation then
 * observed its own `cancelled` flag and disposed that shared object, while
 * pass 2's continuation published it — so `ChartRenderer` held a disposed
 * chart, every later `setOption` was a silent no-op, and the host `<div>`
 * kept its `_echarts_instance_` attribute with no `<canvas>` child. That
 * was the missing-chart defect on `/monitoring` and in Explore; it was
 * intermittent because whichever pass won the module-cache race sometimes
 * rendered.
 *
 * Chaining fixes it without teaching this file anything about ECharts:
 * pass *n+1*'s callback cannot start until pass *n*'s has settled, so
 * pass 1 reaches its `cancelled` check BEFORE calling the factory at all
 * and returns without creating anything. One instance is created per
 * mount, and it is never the one that was disposed.
 */
import { useEffect, useRef, useState } from 'react';

import { logger } from '@shared/observability';

export interface ChartInstanceLike {
  resize(): void;
  dispose(): void;
}

export function useEcharts<T extends ChartInstanceLike>(
  containerRef: React.RefObject<HTMLDivElement | null>,
  createInstance: (container: HTMLDivElement) => Promise<T>,
): T | null {
  const [instance, setInstance] = useState<T | null>(null);
  // Survives StrictMode's double-invoke (refs are not re-created for it),
  // which is exactly what makes it usable as the serialisation point.
  const chainRef = useRef<Promise<void>>(Promise.resolve());

  useEffect(() => {
    const container = containerRef.current;
    if (container === null) {
      return;
    }

    let cancelled = false;
    // Cleanup disposes from this closure, not from `setInstance`'s
    // updater — React 18 skips running a state updater at all once the
    // fiber is already unmounted, so disposing that way silently never
    // fires (caught by the "disposes on unmount" test).
    let created: T | null = null;

    const run = chainRef.current.then(async () => {
      // Reached only once every earlier effect run has finished creating
      // and disposing. A pass whose cleanup already fired stops here,
      // before the factory is ever called — see this file's header.
      if (cancelled) {
        return;
      }
      const resolved = await createInstance(container);
      if (cancelled) {
        resolved.dispose();
        return;
      }
      created = resolved;
      setInstance(resolved);
    });

    // A rejected factory (`ChartRenderer`'s dynamic `import('echarts')`
    // failing, say) must not leave the chain permanently rejected, or no
    // later mount could ever create a chart again. Reported rather than
    // swallowed: before this was chained the rejection floated, and going
    // silent would be a worse trade than one log line.
    chainRef.current = run.catch((error: unknown) => {
      logger.error('chart.instance_create_failed', { message: String(error) });
    });

    return () => {
      cancelled = true;
      created?.dispose();
      // Harmless once actually unmounted (React drops state updates on an
      // unmounted fiber) — while still mounted (a factory-identity
      // change), this clears the stale, now-disposed instance rather than
      // leaving a consumer holding a reference to it until the next one
      // resolves.
      setInstance(null);
    };
  }, [containerRef, createInstance]);

  useEffect(() => {
    const container = containerRef.current;
    if (container === null || instance === null) {
      return;
    }

    const observer = new ResizeObserver(() => instance.resize());
    observer.observe(container);
    return () => observer.disconnect();
  }, [containerRef, instance]);

  return instance;
}
