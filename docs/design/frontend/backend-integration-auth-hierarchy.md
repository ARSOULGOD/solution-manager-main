# Frontend↔backend integration — Auth and Hierarchy (first slice)

**Status:** DRAFT specification. Not yet a plan; see [`docs/plans/integration/001-auth-hierarchy.md`](../../plans/integration/001-auth-hierarchy.md) for the implementation plan derived from this spec.

**Relationship to other design docs.** This is a companion to [`design.md`](design.md) (frontend) and [`../backend/design.md`](../backend/design.md) (backend) — it does not restate either, only the seam between them. Read `design.md` §15 (auth) and §6.0.5 (contract-TBD seams) first.

## 1. Why this exists

`dev/frontend` is fully built but fixture-backed with no server behind it (`CLAUDE.md`). `backend/console-gateway` now exists with working `AuthController` and `HierarchyController` endpoints (integrated 2026-09-22 from `integration/dp-remote-demo`). This is the first attempt to actually connect the two, starting with the only two areas console-gateway currently serves: Auth and Hierarchy. Everything else (Solutions/Instances, Designer, Apply, Monitoring) stays fixture-backed — their backend counterparts don't exist yet (`docs/plans/backend/007`'s HTTP apply layer is unbuilt, `docs/plans/backend/009` console-api hasn't started).

**Which backend module.** `demo-launcher`'s `DemoController` also exposes overlapping `/api/cp/*` auth and hierarchy routes, but it is demo/manual-test infrastructure, not the production module. This spec targets `console-gateway` exclusively.

## 2. Contract mismatches found (frontend expectation vs backend reality)

A field-by-field comparison of the frontend's existing (currently-unused) `localSessionAdapter`/`hierarchyRepository` seams against `console-gateway`'s actual DTOs turned up the following. Every item below is a real, verified gap — not a hypothetical — found by reading both sides' source.

### Auth

1. **Identity shape.** Frontend `Identity` (`shared/auth/AuthAdapter.ts`): `{name, email, role, organization, superAdmin, permissions: string[]}`. Backend `Identity` (`console-gateway/.../Identity.java`): `{principalId: UUID, username, source, mustRotate, permissions: [{permission, scopes}], csrfToken}`. No field names overlap except `permissions`, and even that differs in shape.
   - **Decision (revised after schema check):** `sm_control.principal` has no `name`/`email` column, and `sm_control.organization` has no `name` column at all — confirmed by reading `V001__scope.sql`/`V002__identity.sql` and by the backend's own `ScopeNode` javadoc, which documents this as a known gap ("do not fix this by inventing a naming scheme here; add the column instead"). `role` and `superAdmin` ARE honestly derivable — `sm_control.role_binding` joins principal → role → scope, with built-in `Administrator`/`Operator`/`Viewer` roles. For this slice: extend `console-gateway`'s `Identity` response with `role` (the bound role name at the principal's broadest/install-scope binding, or `null` if none) and `superAdmin` (true iff bound to the built-in `Administrator` role at the install scope) only. `name` and `email` are dropped from the frontend `Identity` type for this slice; the UI falls back to `username` wherever it displayed `name`, and drops the email field entirely. `organization` is also dropped from `Identity` — the org chip continues to use whatever the Hierarchy cascade already returns (the id-string placeholder), not a separate Identity field. A future slice can add real name/email/org-label columns via migration; not assumed or invented here.
2. **Permissions format.** Frontend consumes a flat `string[]` of permission ids (`shared/authz/permissions.ts`). Backend sends scoped grants `{permission, scopes: ScopePath[]}[]`. Flatten to ids for v1's cosmetic RBAC gate; scopes are discarded (matches current UI capability — no scope-aware gating exists yet).
3. **Login request field name.** Frontend posts `{username, password}`. Backend's `LoginRequest` expects `{username, credential}`. Rename on the frontend side.
4. **`accessToken` fallback is dead code.** `localSessionAdapter`'s `LoginResponse.accessToken` path never fires — `AuthController.login` returns exactly `Identity`, no token field, ever (session is cookie-only). Remove the dead path or leave it inert; it does no harm either way, but should not be relied on.
5. **CSRF is unwired.** Backend requires header `X-SM-CSRF` on every unsafe method except login (`AuthenticationInterceptor`), value bound to the session, delivered via non-httpOnly cookie `__Host-SM_CSRF` and echoed in `identity.csrfToken`. Frontend has a `configureCsrf()` seam (`shared/api/http.ts`) with a default header name (`X-CSRF-Token`) that doesn't match, and it is never called anywhere in the app. Every real POST except login will 403 (`CSRF_REJECTED`) until this is wired with the correct header name and token source.
6. **Cookie is `Secure` unconditionally.** `__Host-SM_SESSION` is `httpOnly`, `SameSite=Strict`, `Secure`, `Path=/`. A plain-HTTP origin cannot receive it — dev must run over TLS (see §4).
7. **`mustRotate` has no frontend surface.** Backend's `POST /credential` (`RotateOwnCredentialRequest{currentCredential, newCredential}`, 12-char minimum) and `Identity.mustRotate` exist; `AuthenticationInterceptor` fails closed (`MUST_ROTATE`, 403) on every authenticated call once set. `AuthAdapter` has no `rotateCredential` method and no screen reacts to this. This needs a minimal forced-rotation dialog, not just a type change.
8. **Error body shape.** Backend's `ProblemBody` is `{type, title, status, code, correlationId, detail?, instance?}` — `detail`/`instance` are always null in production (fixed strings per `ErrorCode`, never exception-derived). Frontend's `parseErrorBody` reads a `message` field that does not exist on the wire; `ApiError.message` will always be `undefined` against the real backend. Confirm `ERROR_CODE_REGISTRY` covers the backend's actual `ErrorCode` enum values.
9. **Rate limiting (`OR-24`).** Login and credential rotation are token-bucket rate-gated, returning `RATE_LIMITED` (429) with `Retry-After`. No frontend path currently distinguishes this from a generic error.
10. **`MUST_ROTATE` and `CSRF_REJECTED`** are both 403s the frontend currently treats identically to any other error; they need distinct handling (redirect to rotation dialog / silent retry-with-fresh-token respectively).
11. **`AuthProvider.selectAdapter` hardcodes `devMockAdapter` under `import.meta.env.DEV`** with no override — there is currently no way to run the Vite dev server against the real gateway without a source change.

### Hierarchy

12. **Envelope vs bare array.** Backend `GET /organizations` (and the two nested routes) returns `ScopeNodeList {stateVersion, availability?, items: [{id: UUID, name: string}]}`. `HierarchyRepository` expects a bare `string[]`. Needs unwrapping `items`.
13. **Names vs UUIDs in the URL path.** Frontend builds nested URLs from `encodeURIComponent(displayName)`. Backend path variables are typed `UUID`. A name-based segment 400s. The cascade must carry `id` forward from the level-one response into level-two/three calls, not the display name.
14. **`Env` is a fixed literal union; backend environments are free-form names.** `hierarchyRepository`'s `Env[]` return type is sourced from a closed union (`'PROD'|'TEST'|'DEV'`, etc.) in test-double data. Backend environments are just `{id, name}` with no fixed vocabulary, same shape as organizations/sub-orgs. Widen `Env` to accept arbitrary names; `EnvChip`'s tone map needs a fallback for unrecognized ones (already anticipated in the fixture file's own comment).
15. **`stateVersion`/`availability` are unconsumed.** Backend's `stateVersion` is presently a hardcoded constant (`STATIC_STATE_VERSION = 1L`, documented backend-side as a stub). Out of scope for v1 — ignore both fields.

### Transport / dev environment

16. `credentials: 'same-origin'` is already correct on the frontend's request layer — no change needed there.
17. 401 detection (`onUnauthorized`) already matches the backend's `UNAUTHENTICATED` response — no change needed.
18. **No dev proxy exists.** `vite.config.ts` has no `server.proxy` block routing `/api/*` to the backend.
19. **No CORS support exists, deliberately.** `console-gateway` answers every `OPTIONS` request with `405` and validates `Origin`/`Referer` against an exact-match allowlist (`GatewayProperties.trustedOrigins`) — there is no cross-origin path, by design ("the console is same-origin only and supports no CORS preflight"). Combined with the unconditionally-`Secure` cookie (#6), frontend and backend must be served from **one origin** in every environment, dev included — a classic two-port split cannot work.
20. `trustedOrigins` needs exactly one dev entry once the serving strategy (§4) picks a concrete origin.

### Setup / first-run

21. Confirmed: `console-gateway` has no `/setup` or `/setup-status` routes — those exist only in `demo-launcher`'s `DemoController`, which is out of scope here.
22. Confirmed: the frontend has no first-run/bootstrap screen today. Not needed for this slice; flagged only so a future demo-mode setup flow (if ever exposed) is understood to need separate wiring against `demo-launcher`, not `console-gateway`.

## 3. Decisions made (this spec)

| # | Decision | Rationale |
|---|---|---|
| D1 | Target `console-gateway`, not `demo-launcher`. | Production module vs. manual-test harness. |
| D2 | Extend backend `Identity` with only `role` and `superAdmin` (both honestly derivable from `role_binding`); drop `name`/`email`/`organization` from the frontend `Identity` type for this slice — `username` substitutes for name, email is removed from the UI, org chip keeps using Hierarchy's own (placeholder) label. | `sm_control.organization`/`principal` have no name/email columns — confirmed by reading the schema and the backend's own `ScopeNode` javadoc. Inventing values would violate this codebase's own "no fabricated labels" rule; a real fix needs a migration, out of scope here. |
| D3 | Flatten scoped permissions to a flat id list for v1's RBAC gate; scopes discarded. | Matches current (non-scope-aware) frontend capability; scope-aware gating is future work, not blocking this slice. |
| D4 | Dev serving via Vite proxy (`server.proxy`) over HTTPS, one trusted origin added to `console-gateway`. **Implemented** (task 9 of plan 132): `frontend/vite.config.ts` adds `@vitejs/plugin-basic-ssl` and a `/api` proxy to `http://localhost:8080` with `changeOrigin: false` (so the backend's `trusted-origins` check sees the browser's real `https://localhost:5173` origin, not a rewritten one); `backend/launcher/src/main/resources/application-dev.yml` sets `sm.gateway.trusted-origins: [https://localhost:5173]` and `sm.gateway.direct-client-connections: true`. Note the config lives under `backend/launcher` (the actual Spring Boot composition root that hosts `console-gateway` via `@ComponentScan`/`@Import` — `console-gateway` itself is a library module with no `main` class and no `spring-boot-maven-plugin`, confirmed by inspection), not `backend/console-gateway`. | Backend's no-CORS, `Secure`-cookie design forces same-origin; the proxy keeps Vite's HMR dev loop instead of requiring a full rebuild per change (the alternative — backend-served static build — was considered and rejected for dev velocity). |
| D5 | Widen `Env` from a closed literal union to accept arbitrary backend-returned names, with a fallback tone for unrecognized ones. | Backend has no fixed environment vocabulary; the union was always a fixture-era simplification. |
| D6 | Hierarchy cascade keys on `id` (UUID), not display name. | Backend path variables are typed UUIDs; names cannot round-trip through the URL. |

## 4. Out of scope for this slice

- Solutions/Instances, Designer, Apply, Monitoring — no real backend surface yet.
- Scope-aware (as opposed to flat) permission gating.
- Any change to `demo-launcher`.
- Production deployment topology (this spec's dev-serving decision is dev-only; production's single-origin story is `docs/design/runtime/`'s concern, not yet written).

## 5. Open questions carried to implementation

- Exact source of the projected `name`/`email`/`role`/`organization`/`superAdmin` fields in `cp-core`'s data model — first discovery step of the backend task, not resolved here.
- Whether `ERROR_CODE_REGISTRY` already covers every backend `ErrorCode` value, or needs additions — verify during implementation, not assumed.

## 6. Self-review notes

- No placeholders/TBDs left unresolved above except the two explicitly deferred to implementation-time discovery (§5), which is intentional — they depend on reading `cp-core` code, not on a decision this spec can make.
- Scope is deliberately narrow (Auth + Hierarchy only) to keep this a single implementation plan; broader integration (Solutions/Instances/Monitoring) is intentionally excluded pending their own backend work.
