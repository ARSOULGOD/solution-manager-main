# FE-102 · Observability and secret-leak prevention — Report

## What I implemented

One logger, three independent mechanisms keeping a secret out of it, and no transport.

Task spec: `docs/plans/frontend/implementation.md` §FE-102. Refs `FE-D13`, `I6` · design §20.

| File | Summary |
|---|---|
| `frontend/src/shared/observability/logger.ts` | New. `logger.debug/info/warn/error(event, fields?)`. `debug`/`info` sit behind a literal `import.meta.env.DEV` and fold out of a production build; `warn`/`error` always emit. Formats to a single string, redacts secret-shaped keys, ships nothing. |
| `frontend/src/shared/observability/index.ts` | New. The module's only entry point — `logger` plus what its tests and the lint messages point at. |
| `frontend/src/shared/observability/logger.test.ts` | New. 22 tests across the three mechanisms, including `@ts-expect-error` assertions that fail `typecheck` if the field type is ever widened. |
| `frontend/src/shared/api/http.ts` | `logNetworkFailure` goes through `logger`. The thrown error is narrowed to its message — a `fetch` error's `cause` chain is a place a request body hides. |
| `frontend/src/app/errors/RouteErrorBoundary.tsx` | `componentDidCatch` goes through `logger`, and now logs the **server** `correlationId` when the error is an `ApiError` (`FE-D13`). |
| `frontend/src/shared/ui/WidgetErrorBoundary.tsx` | Same, narrowed to primitives — a widget error is one `cause` hop from the metric payload that produced it. |
| `frontend/eslint.config.js` | Three rules: `NO_SECRET_LOG_FIELD`, `NO_RAW_CONSOLE`, `NO_LOG_TRANSPORT` (scoped). Added to every `no-restricted-syntax` block, including the three override blocks that would otherwise have been holes. |
| `frontend/src/test/lint-fixtures/{app/logs-secret.ts, app/raw-console.ts, observability/log-transport.ts}` | New. One per rule, each proven to still fail by `lint:fixtures:assert` (now 13 fixtures). |
| `frontend/scripts/assert-lint-fixtures.mjs`, `frontend/src/test/lint-fixtures/README.md` | The three new fixtures registered. |

## The design: a type, a lint rule, and a redactor — in that order

The interesting decision is that **the field type does most of the work**:

```ts
export type LogValue = string | number | boolean | null | undefined;
export type LogFields = Readonly<Record<string, LogValue>>;
```

There is no overload taking an object, an `Error`, or an array. So the largest
class of leak — `logger.error('save failed', { values })`, `logger.error('boom', { error })`,
`logger.error('chart', { series })` — is not a mistake you can make and still
compile. `I6` says a secret must never reach a log; a type that cannot carry one
is a stronger guarantee than a redactor that has to recognise one, and it needs
no maintenance as new leak shapes appear.

That leaves exactly one gap the type cannot see: a **primitive under a
secret-shaped name**, `logger.error('login failed', { password })`. That is
`NO_SECRET_LOG_FIELD`'s job, at the call site.

And one gap lint cannot see: a key **assembled at runtime**, `{ [field]: value }`.
That is the runtime redactor's job — the net, never the plan.

Each layer is proven by the only kind of test that can prove it: the type by
`@ts-expect-error` (a runtime test cannot check a compile-time guarantee — if
`LogValue` is widened, those lines stop erroring and `typecheck` fails), the
lint rule by a fixture in `lint:fixtures:assert`, the redactor by unit tests.

## Rulings

**1 · Redaction over-redacts, deliberately.** `SECRET_FIELD` matches as a
substring, so a field named `secretRef` is masked even though `I6` explicitly
permits logging a secret *reference* — it is the resolved value that may never
be logged. Exempting `…Ref` would be a regex making a judgement about intent,
and the failure direction there is a published credential. The cost of the
current behaviour is a slightly less useful log line; a caller that needs the
reference names the field for what it identifies (`configField`, `bindingName`)
rather than for the mechanism. Recorded in the module header and asserted by a
test, so nobody "fixes" it later without seeing the argument.

**2 · The logger formats to a string rather than passing an object to `console`.**
`console.error(msg, obj)` gives the browser console a **live, expandable
reference** to the original object — a later mutation shows up in the log, and
anything the object still points at is one click away in devtools. That defeats
the primitives-only type at the last step. Formatting to text at the call site
severs it, and a test asserts `console.error` receives exactly one argument and
that it is a string.

**3 · `warn` and `error` are not DEV-gated.** Design §20 says "structured
`console` in development", which could be read as *nothing* in production. It
should not be: `warn`/`error` are what an operator reads out over a support call
alongside the correlation id, and a console that goes silent in production is a
console nobody can support. What is DEV-only is the **verbose** half — `debug`
and `info` — which is what the task's own wording ("development-only for
verbose levels") asks for. The DEV-gated calls fold out of a production build
entirely, so neither the call nor the string it would have logged ships.

**4 · No transport, and that is lint-enforced rather than merely absent.**
`NO_LOG_TRANSPORT` forbids `fetch`, `XMLHttpRequest`, `sendBeacon`, `WebSocket`
and `EventSource` inside `shared/observability/**` — the only module where
adding one would look reasonable. Design §20 is explicit that production error
shipping waits on a *backend* log endpoint and that a SaaS RUM product must not
be invented for an air-gapped deployment; `REQ-003` means an install may have no
egress at all, so a logger that phoned home would make the console broken rather
than observable. The dependency half of "no third-party telemetry" is already
covered by `verify:csp`'s `EGRESS_PACKAGES` list (FE-100).

**5 · Three override blocks in `eslint.config.js` were silent holes.**
`no-restricted-syntax` is replaced wholesale by a later block with the same rule
— the file's own comment records that lesson from an earlier task. Three blocks
(`shared/ui/layout/**`, `features/designer/form/**`, `test/matchMedia.test.ts`)
re-list the rule set, so adding the new rules only to the main block would have
left three directories able to `console.log` a password. All three now carry
the full set.

**6 · The lint fixture needed a mirrored directory.** `NO_LOG_TRANSPORT` is
scoped to `src/shared/observability/**`, so a fixture under
`lint-fixtures/shared/` was outside the rule and passed. `lint-fixtures/observability/`
is now in the rule's `files` glob — the same mirror pattern the boundaries
elements already use. Caught by checking the fixture actually failed rather than
assuming it did; a fixture that passes is a dead guardrail advertising itself as
a live one.

## Acceptance

- ☑ **no secret can reach a log** — type (`@ts-expect-error` × 3, enforced by
  `typecheck`), lint (`NO_SECRET_LOG_FIELD` + fixture), redaction (unit tests
  including a runtime-assembled key).
- ☑ **no third-party telemetry** — `NO_LOG_TRANSPORT` + fixture for the code
  half; `verify:csp`'s `EGRESS_PACKAGES` and its runtime "zero foreign requests"
  assertion for the dependency half. No SDK added.
- ☑ **correlation id surfaced for support** — already carried end to end by
  FE-026/FE-040: `readCorrelationId(response)` → `ApiError.correlationId` →
  `ErrorPanel` (covered by `ErrorPanel.test.tsx`). FE-102 adds the missing half —
  `RouteErrorBoundary` now *logs* the server id, so the id on screen and the id
  in the console match. `SupportMenu`'s id stays clearly labelled as a session
  id for stitching a conversation, never as an audit key (`FE-D13`).

## Verification

```
npm run lint                 ✓ 0 problems
npm run typecheck            ✓ clean (the @ts-expect-error assertions are load-bearing here)
npm test                     ⚠ 322 files, 2167 tests — 2165 passed; see note
npm run build                ✓
npm run verify:bundle        ✓ initial JS 134.7 KiB gzip (+0.2 for the logger), ceiling 160.0
npm run verify:production    ✓ 7/7 assertions — the logger adds no secret, no host, no transport
npm run lint:fixtures:assert ✓ 13 fixtures failed on the intended rule (was 10)
```

The 22 new tests are the logger's. The two failures are
`InstancesPage.a11y.test.tsx` timing out — the pre-existing flake FE-101's
report already recorded and reproduced on `main` (3 failed / 348 passed there
with FE-101's changes stashed). Nothing in this task touches that file's import
graph.

## Limits, stated

- **Redaction is a name match.** A credential under an innocent key is not
  caught. Same recorded limit as `FE-D23`'s scanner: this reduces the surface,
  it does not close it. The type is the guarantee; redaction is the net.
- **`NO_RAW_CONSOLE` matches `console` by identifier.** A call routed through an
  alias (`const c = console`) is not caught. Not worth chasing — the rule exists
  to stop the easy reintroduction, and the primitives-only type is what makes
  the leak matter less either way.
- **Nothing is logged in production but `warn` and `error`.** There is no
  ring buffer and no "download diagnostics" affordance. When the backend log
  endpoint exists, both become worth revisiting — behind `config.json`, in this
  module, and nowhere else.
