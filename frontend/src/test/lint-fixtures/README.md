# Lint fixtures (task 009)

Every file in this tree exists to **violate** one architecture-guardrail rule.
`npm run lint` ignores the directory. `npm run lint:fixtures` must exit
non-zero. `npm run lint:fixtures:assert` checks that each file still fails
on the rule named next to it — a passing default lint run of these files
means the guardrail is dead, not that the tree is clean.

| File | Rule |
|---|---|
| `feature/inventory/deep-import.ts` | `boundaries/dependencies` — cross-feature deep import |
| `shared/imports-feature.ts` | `boundaries/dependencies` — shared must not import features |
| `app/imports-echarts.ts` | `no-restricted-imports` — echarts outside ChartRenderer |
| `shared-ui/calls-api.ts` | `no-restricted-imports` — `@shared/api` from `shared/ui` |
| `app/imports-pro.ts` | `no-restricted-imports` — MUI X Pro/Premium |
| `app/raw-colour.ts` | `no-restricted-syntax` — raw hex / rgb / hsl |
| `app/raw-media.ts` | `no-restricted-syntax` — raw `@media` outside theme/layout |
| `app/vite-plane-host.ts` | `no-restricted-syntax` — `VITE_*_URL` plane-host env var (task 027) |
| `app/demo-states.ts` | `no-restricted-syntax` — `DEMO_STATES` outside `shared/testdoubles` (task 036) |
| `app/logs-secret.ts` | `no-restricted-syntax` — a secret-shaped field passed to `logger` (task 102, `I6`) |
| `app/raw-console.ts` | `no-restricted-syntax` — `console.*` outside `shared/observability` (task 102) |
| `observability/log-transport.ts` | `no-restricted-syntax` — a transport inside the logger (task 102, design §20) |
| `contracts/imports-react.ts` | `no-restricted-imports` — contracts importing React |

Companion files (`feature/*/index.ts`, `feature/designer/internal.ts`) are
resolution targets, not the violations.
