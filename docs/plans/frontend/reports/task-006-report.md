# FE-006 · Error boundaries and the error taxonomy — Report

## What I implemented

The one error surface plus two scoped boundaries, so a widget error never takes down a dashboard
and a route error never takes down the shell.

### Files created

- `frontend/src/shared/ui/ErrorPanel.tsx` — `ErrorPanel({ title, detail, code?, correlationId?,
  onRetry? })`. Renders a MUI `Alert severity="error" role="alert"` with an `AlertTitle`, the
  detail text, an optional "Error code: …" line, an optional "Correlation id: …" line, and an
  optional Retry button. Always visually/semantically distinct from an empty state — `FE-D10`
  ("empty is never a silent success") — so no other component may reuse it to mean "no data."
  Built from `theme.palette` via MUI's `Alert`/`Typography`/`Button`, not a raw hex, consistent
  with `G8` even though the lint rule that would enforce that (task 009) isn't implemented yet.
- `frontend/src/app/errors/WidgetErrorBoundary.tsx` and `RouteErrorBoundary.tsx` — near-identical
  class-based `React.Component` error boundaries (`getDerivedStateFromError` +
  `componentDidCatch` + `render`), each catching a render throw in its own subtree only and
  replacing it with an `ErrorPanel`. Kept as two separate, small, duplicated implementations
  rather than one shared base class — each is ~50 lines, they're conceptually distinct (widget
  vs. route scope, different default titles), and Files explicitly names them as two components;
  a shared abstraction wasn't justified for this much logic. Both duck-type an optional
  `code`/`correlationId` off the thrown value via a local `describeError(error: unknown)` helper
  (`error instanceof Error` check, then reads `.code`/`.correlationId` if they're strings) —
  this exists so a future typed error from task 028's error codec is surfaced through `FE-D13`
  without this task taking a dependency on that codec existing yet.
- `ErrorPanel.test.tsx`, `WidgetErrorBoundary.test.tsx`, `RouteErrorBoundary.test.tsx` — see
  Testing below.

### Why no router/AppShell wiring yet

The brief's Files list is Create-only (no Modify), and there's no `AppShell` (task 036) for
`RouteErrorBoundary` to wrap an `<Outlet>` inside yet, nor a `DashboardGrid` (task 086) for
`WidgetErrorBoundary` to wrap per-widget. Both boundaries are therefore built as
consumption-ready, generically testable components — proven against synthetic parents in their
own tests (a `<div>` standing in for "the shell", two sibling boundaries standing in for "the
dashboard") — for those later tasks to wire in, rather than reaching into `router.tsx` to bolt on
a premature integration.

`RouteErrorBoundary` deliberately does **not** wrap its output in a `<main>` landmark (unlike
`NotFound.tsx`): once `AppShell` exists it will provide the page's one `<main>`, and nesting a
second `<main>` inside it would be invalid landmark structure. `ErrorPanel`'s own `role="alert"`
is enough for now.

## Testing

- `ErrorPanel.test.tsx`: renders as `role="alert"` with title/detail; omits the code/correlation
  lines when not supplied; shows both when supplied; invokes `onRetry` on click; renders no
  button when `onRetry` is absent.
- `WidgetErrorBoundary.test.tsx`: a throwing child renders `ErrorPanel` with its message, code
  and correlation id surfaced (via a `CorrelatedError` test double); **a throwing widget leaves a
  sibling `WidgetErrorBoundary`'s content mounted**, proving boundary instances are independent;
  a non-throwing child renders unchanged, no `role="alert"` present.
- `RouteErrorBoundary.test.tsx`: **a throwing route renders `ErrorPanel` while sibling `<nav>`/
  `<header>` content (standing in for the shell) stays mounted**; correlation id surfaces; a
  non-throwing route renders unchanged.
- All four component tests use real `react-dom`/`act` (no RTL — test harness is task 008),
  following `router.test.tsx`'s established pattern (`IS_REACT_ACT_ENVIRONMENT` set locally).

```
$ npm run lint && npm run typecheck && npm test && npm run build
```
All four green. 98 tests passing across the suite (10 new in this task).

## Status

DONE

## Commit

`031f8bc..1911cf3` on `dev/frontend`. Register:
[`execution.md` §7](../execution.md#7-task-register--all-125), row `006`. Issue
[#26](https://github.com/parikshit-coe/solution-manager/issues/26) (closed).
