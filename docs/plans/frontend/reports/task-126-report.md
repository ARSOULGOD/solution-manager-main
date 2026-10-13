# FE-126 · Fix the `useEcharts` StrictMode race — Report

## What this closes

The first and most visible defect in plan `126`: on `/monitoring` and in Explore the ECharts canvas was
*absent* — the host `<div>` carried `_echarts_instance_=""` with no `<canvas>` child. The instance was
being created and then immediately disposed, so every subsequent `setOption` was a silent no-op against a
dead chart. Nothing downstream in the plan (`128`'s grid and axis work, `129`'s sizing) can be judged on
screen until a chart actually draws, which is why this landed first.

## Root cause — confirmed by a failing test, not inferred

`main.tsx` renders the app inside `<StrictMode>`, so React invokes every effect, runs its cleanup, and
invokes it again within a single commit. `useEcharts` started an async `createInstance(container)` on
*each* pass, and disposed only via a `created` local that was still `null` when the first cleanup fired:

- pass 1 starts promise A → cleanup 1 sets `cancelled1 = true` (`created` is `null`, nothing disposed)
- pass 2 starts promise B
- A resolves → `echarts.init(container)` creates instance **X**
- B's continuation calls `echarts.init` on the *same node* → ECharts returns the **existing** instance X
- A's `.then` sees `cancelled1` → `X.dispose()`
- B's `.then` sees not-cancelled → `setInstance(X)` — X is already disposed

The collision turns on a property of `echarts.init` that `useEcharts` deliberately cannot see (`FE-D16`
keeps the factory caller-supplied and this file `echarts`-free): initialising a node that already carries
an instance hands back that same instance rather than a second one. That is also why the symptom was
intermittent — whichever mount won the module-cache race sometimes rendered.

## The fix

Creation is serialised through a per-hook promise chain held in a `useRef` (refs survive StrictMode's
double-invoke, which is what makes one usable as the serialisation point). Pass *n+1*'s callback cannot
start until pass *n*'s has settled, so **pass 1 reaches its `cancelled` check before calling the factory at
all** and returns without constructing anything. One instance is created per mount, and it is never the one
that was disposed.

Both existing guarantees are preserved: dispose-on-unmount, and dispose-and-recreate when the factory
identity changes (`ChartRenderer` memoizes the factory per MUI theme, since ECharts themes are
init-time-only). The file still imports no `echarts`.

**Chain-poisoning handled rather than ignored.** Chaining introduces a hazard the old code did not have: a
rejected factory would leave `chainRef.current` permanently rejected, and no later mount could ever create
a chart again. The rejection is caught to keep the chain live and reported through `shared/observability`'s
`logger` — before this was chained the rejection floated to the browser as an unhandled rejection, and
going silent would have been a worse trade than one log line. (`eslint.config.js` forbids raw `console`
outside that module; the boundaries rules allow `@features/monitoring` → `@shared/observability`, verified
by a clean `npm run lint`.)

## TDD — the test failed first

Per `superpowers:test-driven-development`, three cases were added to `useEcharts.test.ts` **before** the
fix and confirmed failing against the old implementation (`3 failed | 8 passed`):

| Test | Failure before the fix |
|---|---|
| does not publish a disposed instance when StrictMode double-invokes the effect | `dispose` called on the published instance |
| creates exactly one instance across a StrictMode double-invoke | factory called twice |
| still disposes exactly once on unmount under StrictMode | `dispose` called 2 times, not 1 |

They model the two passes as one shared object precisely because that is what `echarts.init` returns.

## One existing test's premise had to change

`disposes a resolved instance if unmounted before creation settles` began failing after the fix — with
`createInstance` called **0** times, so there was no instance to dispose. That is the fix working, not a
regression: the test unmounted synchronously after mount, and the serialised callback now observes
`cancelled` before invoking the factory, so nothing is ever created.

The guarantee it exists to protect is still real, so rather than delete it the test now waits for creation
to be genuinely in flight (`await waitFor(() => expect(createInstance).toHaveBeenCalledTimes(1))`) before
unmounting. A new sibling test pins the *other* half explicitly — when cleanup precedes the chain's turn,
the factory is never called at all — so the behaviour change is asserted rather than merely absorbed.

## Files

| File | Change |
|---|---|
| `src/features/monitoring/chart/useEcharts.ts` | Creation serialised through `chainRef`; factory rejection logged instead of poisoning the chain; header extended with the root cause. |
| `src/features/monitoring/chart/useEcharts.test.ts` | Three new StrictMode tests (failed first); one existing test's premise corrected; one new test pinning the never-created path. |

## Verification

- `npx vitest run src/features/monitoring/chart/useEcharts.test.ts` — **12 passed**
- `npx vitest run src/features/monitoring` — **64 files, 463 tests, all passed** (no regression in
  `ChartRenderer`, `ChartRenderer.a11y`, `WidgetFrame`, `ExploreResult` or any other consumer)
- `npm run lint` — clean at `--max-warnings=0`
- `npx tsc --noEmit` — clean

Visual proof (a `<canvas>` child with non-zero size under `[role="application"]` at 1440px) is the plan's
own task-`132` verification step and is not claimed here — this task is verified at the unit level, where
the defect was reproduced.
