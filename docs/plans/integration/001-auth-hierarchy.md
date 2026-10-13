# Auth + Hierarchy Backend Integration Implementation Plan

> **Integration plan 001** (formerly "plan 132", `docs/plans/frontend/132-auth-hierarchy-backend-integration.md`). Moved 2026-10-01 into `docs/plans/integration/`, which has its own numbering because `132`/`133` collided with the frontend's `FE-132`/`FE-133…` task and report numbers. Commit messages and older reports that say "plan 132" mean this file.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire the frontend's Auth (login/logout/identity/credential-rotation) and Hierarchy (org/sub-org/environment cascade) screens to the real `backend/console-gateway` module, replacing `devMockAdapter` and the fixture-backed hierarchy cascade — the first slice of frontend↔backend integration.

**Architecture:** Backend gains a small, honestly-scoped `Identity` projection (`role`, `superAdmin`, `organizationId` — all derivable from existing schema; `name`/`email`/organization-label are NOT invented, they don't exist in the schema). Frontend's already-built-but-unused `localSessionAdapter`/`hierarchyRepository` seams get fixed to match the real wire contract (field names, CSRF, cookies, envelope shape, id-keyed cascade) and get switched on via a build-time flag. Local dev serves both through one origin via a Vite proxy, matching production's own documented single-origin topology.

**Tech Stack:** Java 17 / Spring Boot (`console-gateway`, `cp-core`, `cp-api`), PostgreSQL/Flyway (`sm_control` schema), React + Vite + TypeScript (frontend), Vitest, JUnit 5 + MockMvc + Testcontainers (backend).

**Spec:** [`docs/design/frontend/backend-integration-auth-hierarchy.md`](../../design/frontend/backend-integration-auth-hierarchy.md) — read it first; this plan implements its §2/§3.

## Global Constraints

- `I5` — organization scope is never client-derived; every server-sourced field stays server-sourced (this plan adds `organizationId`, never lets the frontend send it back as authority).
- `I6` — no secret value (credential, credential hash) may ever be logged or land in a response body beyond what `AuthController` already redacts.
- Bind parameters only for all new SQL (`I5`).
- No CORS support is being added — `console-gateway` remains same-origin-only by design; do not add a `CorsConfigurationSource` anywhere in this plan.
- `Permission` catalog stays exactly the 38-verb enum in `cp-api`; no new permission is added by this plan.
- Every new/changed backend wire field is additive or a documented breaking rename — no field is removed from `Identity`'s wire shape without a corresponding frontend task in the same plan.

## Review Focus

- **A principal with no role bindings at all** (e.g. deactivated, or a future non-role-bound account) — `role` must come back `null` and `superAdmin` must be `false`, never throw or default to admin.
- **CSRF token rotated mid-session** (`GET /identity` re-issues the CSRF cookie on every call) — a stale in-memory token from an earlier response must not be used for the next POST; the header must always read the latest cookie value at request time, not a value captured once at login.
- **429 (`RATE_LIMITED`) during login** — the UI must show a distinct, non-generic message with the retry window, not the generic "rejected" state that implies wrong credentials.
- **428 (`MUST_ROTATE`) returned on a call other than `/credential`** (e.g. `GET /identity` itself, per `AuthenticationInterceptor`'s `ForcedRotationGate`) — the rotation dialog must be reachable even though the identity fetch that would normally populate the UI is itself blocked.

  > **Correction (final whole-branch review, plan 132):** the example above is factually wrong. `GET /identity` is explicitly EXEMPT from the must-rotate gate — it always succeeds even mid-rotation — confirmed against the actual `ForcedRotationGate`/`LocalOperation` backend source by two separate task reviews. This Review Focus item still holds; the real MUST_ROTATE-reachable calls mid-rotation are the OTHER (non-exempt) authenticated endpoints, not `GET /identity`.
- **Selecting a sub-org/environment whose parent id was never cached** (e.g. a stale cached query re-selecting a name no longer in the freshly-fetched organizations list) — `hierarchyRepository` must throw a clear, catchable error rather than silently building a malformed URL.

---

## Task 1: Backend — project `role`/`superAdmin`/`organizationId` onto `Identity`

**Files:**
- Create: `backend/cp-core/src/main/java/com/solace/sm/cp/identity/RoleBindingRepository.java`
- Create: `backend/cp-core/src/main/java/com/solace/sm/cp/persistence/JdbcRoleBindingRepository.java`
- Create: `backend/cp-core/src/test/java/com/solace/sm/cp/persistence/JdbcRoleBindingRepositoryIT.java`
- Modify: `backend/cp-api/src/main/java/com/solace/sm/cp/api/identity/IdentityView.java` — add `List<String> roleNames`
- Modify: `backend/cp-core/src/main/java/com/solace/sm/cp/identity/IdentityService.java` — populate it
- Modify: `backend/cp-core/src/main/java/com/solace/sm/cp/persistence/ControlPlanePersistenceConfiguration.java` — new bean
- Modify: `backend/cp-core/src/main/java/com/solace/sm/cp/config/ControlPlaneConfiguration.java` — wire it into `identityDirectory`
- Modify: `backend/console-gateway/src/main/java/com/solace/sm/gateway/http/Identity.java` — add `role`/`superAdmin`/`organizationId`
- Modify: `backend/console-gateway/src/main/java/com/solace/sm/gateway/http/AuthController.java` — `toIdentity` derives them
- Modify: `backend/console-gateway/src/test/java/com/solace/sm/gateway/http/GatewayTestSupport.java` — `FakeIdentityDirectory` gets a `roleNames` seam
- Modify: `backend/console-gateway/src/test/java/com/solace/sm/gateway/http/GatewayHttpBoundaryTest.java` — new assertions
- Modify: `backend/cp-core/src/test/java/com/solace/sm/cp/identity/IdentityServiceIT.java` — new assertions
- Test (new file above): `backend/cp-core/src/test/java/com/solace/sm/cp/persistence/JdbcRoleBindingRepositoryIT.java`

**Interfaces:**
- Produces: `RoleBindingRepository.boundRoleNames(PrincipalRef principal): List<String>` — distinct role names the principal is bound to at any scope, `Administrator` first if present, else insertion order.
- Produces: `IdentityView.roleNames(): List<String>`.
- Produces: wire `Identity(UUID principalId, String username, IdentitySource source, boolean mustRotate, List<EffectivePermission> permissions, String csrfToken, String role, boolean superAdmin, UUID organizationId)`.
- Consumes (Task 6 relies on this): the frontend `Identity` type gains matching `role: string | null`, `superAdmin: boolean`, `organizationId: string` fields.

- [x] **Step 1: Write the failing repository test**

```java
// backend/cp-core/src/test/java/com/solace/sm/cp/persistence/JdbcRoleBindingRepositoryIT.java
package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.authz.PrincipalRef;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcRoleBindingRepositoryIT {

    private static final UUID BOOTSTRAP_PRINCIPAL_ID = UUID.fromString("00000000-0000-4000-8000-000000000012");

    private JdbcRoleBindingRepository roleBindings;

    @BeforeEach
    void setUp() {
        NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion("010");
        roleBindings = new JdbcRoleBindingRepository(runtime);
    }

    @Test
    void bootstrapAdminIsBoundToAdministrator() {
        var names = roleBindings.boundRoleNames(new PrincipalRef(BOOTSTRAP_PRINCIPAL_ID));

        assertThat(names).containsExactly("Administrator");
    }

    @Test
    void unboundPrincipalHasNoRoleNames() {
        var names = roleBindings.boundRoleNames(new PrincipalRef(UUID.randomUUID()));

        assertThat(names).isEmpty();
    }
}
```

- [x] **Step 2: Run it to verify it fails**

Run: `mvn -f backend/pom.xml -pl cp-core -am test-compile`
Expected: FAIL — `JdbcRoleBindingRepository` does not exist yet.

- [x] **Step 3: Create the port and its JDBC adapter**

```java
// backend/cp-core/src/main/java/com/solace/sm/cp/identity/RoleBindingRepository.java
package com.solace.sm.cp.identity;

import com.solace.sm.cp.api.authz.PrincipalRef;
import java.util.List;

/** Reads the built-in role names a principal is bound to, for display only — never for authorization. */
public interface RoleBindingRepository {

    List<String> boundRoleNames(PrincipalRef principal);
}
```

```java
// backend/cp-core/src/main/java/com/solace/sm/cp/persistence/JdbcRoleBindingRepository.java
package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.identity.RoleBindingRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** PostgreSQL adapter — distinct role names a principal is bound to, at any scope. */
public final class JdbcRoleBindingRepository implements RoleBindingRepository {

    private static final String SELECT = """
            SELECT DISTINCT r.name
              FROM sm_control.role_binding rb
              JOIN sm_control.role r ON r.role_id = rb.role_id
             WHERE rb.principal_id = :principalId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRoleBindingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<String> boundRoleNames(PrincipalRef principal) {
        Objects.requireNonNull(principal, "principal");
        Set<String> names = new LinkedHashSet<>(
                jdbc.queryForList(SELECT, Map.of("principalId", principal.id()), String.class));
        List<String> ordered = new ArrayList<>();
        if (names.remove("Administrator")) {
            ordered.add("Administrator");
        }
        ordered.addAll(names);
        return List.copyOf(ordered);
    }
}
```

- [x] **Step 4: Run the repository test to verify it passes**

Run: `mvn -f backend/pom.xml -pl cp-core -am verify -Dit.test=JdbcRoleBindingRepositoryIT -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: PASS (2 tests, 0 failures) — needs Docker for Testcontainers, same as the existing `IdentityServiceIT`.

- [x] **Step 5: Write the failing `IdentityService` test**

Add to `backend/cp-core/src/test/java/com/solace/sm/cp/identity/IdentityServiceIT.java` (in the existing class, alongside `bootstrapAdminIsDescribedWithAllThirtyEightPermissionsAtTheInstallScope`):

```java
import com.solace.sm.cp.persistence.JdbcRoleBindingRepository;
// (add to existing imports)

@Test
void bootstrapAdminIsDescribedWithTheAdministratorRoleName() {
    Session session = bootstrapSession();

    IdentityView view = identity.describe(session);

    assertThat(view.roleNames()).containsExactly("Administrator");
}
```

And update `setUp()`'s construction:

```java
identity = new IdentityService(new JdbcPrincipalRepository(runtime), new JdbcAuthorizationGrantRepository(runtime),
        new JdbcRoleBindingRepository(runtime));
```

- [x] **Step 6: Run it to verify it fails**

Run: `mvn -f backend/pom.xml -pl cp-core -am test-compile`
Expected: FAIL — `IdentityService` constructor doesn't take a third argument yet, and `IdentityView.roleNames()` doesn't exist.

- [x] **Step 7: Add the field to `IdentityView` and populate it in `IdentityService`**

In `backend/cp-api/.../IdentityView.java`, change the record to:

```java
public record IdentityView(PrincipalRef principal, String username, PrincipalSource source, boolean mustRotate,
                           List<EffectivePermissionView> permissions, List<String> roleNames) {

    public IdentityView {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(source, "source");
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
        roleNames = List.copyOf(Objects.requireNonNull(roleNames, "roleNames"));
    }
}
```

In `IdentityService.java`, add the field and constructor parameter, and populate it in `describe`:

```java
private final PrincipalRepository principals;
private final AuthorizationGrantRepository grants;
private final RoleBindingRepository roleBindings;

public IdentityService(PrincipalRepository principals, AuthorizationGrantRepository grants,
        RoleBindingRepository roleBindings) {
    this.principals = Objects.requireNonNull(principals, "principals");
    this.grants = Objects.requireNonNull(grants, "grants");
    this.roleBindings = Objects.requireNonNull(roleBindings, "roleBindings");
}
```

```java
// inside describe(), replace the final `return new IdentityView(...)` with:
return new IdentityView(session.principal(), principal.username(), sourceOf(principal.source()),
        session.mustRotate(), permissions, roleBindings.boundRoleNames(session.principal()));
```

Add the import `import com.solace.sm.cp.identity.RoleBindingRepository;` (same package, so actually no import needed — `RoleBindingRepository` lives in `com.solace.sm.cp.identity`, same as `IdentityService`).

- [x] **Step 8: Wire the new bean**

In `ControlPlanePersistenceConfiguration.java`, add beside `authorizationGrantRepository`:

```java
@Bean
RoleBindingRepository roleBindingRepository(@Qualifier("smControlJdbc") NamedParameterJdbcTemplate smControlJdbc) {
    return new JdbcRoleBindingRepository(smControlJdbc);
}
```

(add `import com.solace.sm.cp.identity.RoleBindingRepository;` and `import com.solace.sm.cp.persistence.JdbcRoleBindingRepository;` — already same package, so only the first import is needed there.)

In `ControlPlaneConfiguration.java`, change:

```java
@Bean
IdentityDirectory identityDirectory(PrincipalRepository principalRepository,
        AuthorizationGrantRepository authorizationGrantRepository, RoleBindingRepository roleBindingRepository) {
    return new IdentityService(principalRepository, authorizationGrantRepository, roleBindingRepository);
}
```

(add `import com.solace.sm.cp.identity.RoleBindingRepository;`)

- [x] **Step 9: Run the `IdentityService` tests to verify they pass**

Run: `mvn -f backend/pom.xml -pl cp-core -am verify -Dit.test=IdentityServiceIT -Dfailsafe.failIfNoSpecifiedTests=false`
Expected: PASS, including the new `bootstrapAdminIsDescribedWithTheAdministratorRoleName`.

- [x] **Step 10: Commit**

```bash
git add backend/cp-core backend/cp-api
git commit -m "feat(backend): add RoleBindingRepository and IdentityView.roleNames"
```

- [x] **Step 11: Write the failing gateway test**

Add to `GatewayTestSupport.java`'s `FakeIdentityDirectory`:

```java
private final Map<UUID, List<String>> roleNames = new HashMap<>();

void registerRoleNames(UUID principalId, List<String> names) {
    roleNames.put(principalId, names);
}
```

and change the `describe` body's return to:

```java
List<String> roles = roleNames.getOrDefault(session.principal().id(), List.of());
return new IdentityView(session.principal(), username, PrincipalSource.LOCAL, session.mustRotate(), views, roles);
```

Add to `GatewayHttpBoundaryTest.java` (new test near `loginSuccessSetsBothCookiesWithExactAttributesAndNeedsNoCsrf`):

```java
@Test
void loginResponseCarriesRoleAndSuperAdminAndOrganizationId() throws Exception {
    UUID principalId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    Session session = GatewayTestSupport.newSession(principalId, organizationId, false);
    harness.localAuthenticationService().onAuthenticate("operator",
            () -> new LocalLoginResult(new SessionToken("session-token-value"),
                    new CsrfToken("csrf-token-value"), session));
    harness.identityDirectory().registerRoleNames(principalId, List.of("Administrator"));

    MvcResult result = mvc.perform(post(LOGIN)
                    .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                    .contentType("application/json")
                    .content(loginBody("operator", "correct-password")))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
    assertEquals("Administrator", body.get("role").asText());
    assertTrue(body.get("superAdmin").asBoolean());
    assertEquals(organizationId.toString(), body.get("organizationId").asText());
}

@Test
void loginResponseHasNullRoleAndFalseSuperAdminWhenUnbound() throws Exception {
    UUID principalId = UUID.randomUUID();
    UUID organizationId = UUID.randomUUID();
    Session session = GatewayTestSupport.newSession(principalId, organizationId, false);
    harness.localAuthenticationService().onAuthenticate("operator",
            () -> new LocalLoginResult(new SessionToken("session-token-value"),
                    new CsrfToken("csrf-token-value"), session));
    // no registerRoleNames call — matches a principal with no role bindings.

    MvcResult result = mvc.perform(post(LOGIN)
                    .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                    .contentType("application/json")
                    .content(loginBody("operator", "correct-password")))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
    assertTrue(body.get("role").isNull());
    assertFalse(body.get("superAdmin").asBoolean());
}
```

(`harness.identityDirectory()` must exist and return the `FakeIdentityDirectory` — check `GatewayTestSupport.newHarness()`'s `Harness` record/class; if it doesn't already expose it, add a `identityDirectory()` accessor mirroring `localAuthenticationService()`.)

- [x] **Step 12: Run it to verify it fails**

Run: `mvn -f backend/pom.xml -pl console-gateway -am test`
Expected: FAIL — `Identity` has no `role`/`superAdmin`/`organizationId` fields, `toIdentity` doesn't set them.

- [x] **Step 13: Extend the wire `Identity` record and `toIdentity`**

In `console-gateway/.../Identity.java`, change the record to add the three fields (keep the existing `@Override toString()` redacting `csrfToken` only — the three new fields are not secret):

```java
public record Identity(UUID principalId, String username, IdentitySource source, boolean mustRotate,
                       List<EffectivePermission> permissions, String csrfToken,
                       String role, boolean superAdmin, UUID organizationId) {

    public Identity {
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(source, "source");
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
        Objects.requireNonNull(csrfToken, "csrfToken");
        Objects.requireNonNull(organizationId, "organizationId");
    }
    // role is intentionally nullable (no requireNonNull) — a principal with no role binding has none.

    @Override
    public String toString() {
        return "Identity[principalId=" + principalId + ", username=" + username + ", source=" + source + ", mustRotate="
                + mustRotate + ", permissions=" + permissions + ", csrfToken=[REDACTED]" + ", role=" + role
                + ", superAdmin=" + superAdmin + ", organizationId=" + organizationId + "]";
    }
}
```

`organizationId` does NOT need a new dependency — `Session` (`cp-api`'s `Session` record) already carries `organizationId()` directly, and both `login` (via `result.session()`) and `getIdentity` (which already takes `Session session` as a parameter) have it in hand. No new bean, no `AuthController` constructor change. Update `toIdentity` to take it as a parameter:

```java
private static Identity toIdentity(IdentityView view, String csrfToken, UUID organizationId) {
    List<EffectivePermission> permissions = view.permissions().stream()
            .map(AuthController::toEffectivePermission)
            .toList();
    return new Identity(view.principal().id(), view.username(), toIdentitySource(view.source()),
            view.mustRotate(), permissions, csrfToken, primaryRole(view.roleNames()),
            view.roleNames().contains("Administrator"), organizationId);
}

private static String primaryRole(List<String> roleNames) {
    return roleNames.isEmpty() ? null : roleNames.get(0);
}
```

And update both call sites:

```java
// in login():
Identity identity = toIdentity(view, result.csrfToken().value(), result.session().organizationId());

// in getIdentity():
Identity identity = toIdentity(view, csrfToken.value(), session.organizationId());
```

No changes to `AuthController`'s constructor, `ControlPlaneConfiguration`, or `GatewayTestSupport`'s `authController` bean method are needed for this part — `GatewayTestSupport.newSession(principalId, organizationId, mustRotate)` (already used by every existing test, including the two new ones in Step 11) already sets a real `organizationId` on the `Session` it builds, which flows straight through.

- [x] **Step 14: Run the gateway tests to verify they pass**

Run: `mvn -f backend/pom.xml -pl console-gateway -am test`
Expected: PASS, including both new tests and every pre-existing `GatewayHttpBoundaryTest` case unaffected (they don't assert on `role`/`superAdmin`/`organizationId`, so they still pass with the new fields present).

- [x] **Step 15: Commit**

```bash
git add backend/console-gateway
git commit -m "feat(backend): project role/superAdmin/organizationId onto the Identity wire response"
```

---

## Task 2: Frontend — fix the login request field name and remove the dead access-token path

**Files:**
- Modify: `frontend/src/shared/auth/localSessionAdapter.ts`
- Test: `frontend/src/shared/auth/localSessionAdapter.test.ts` (create if it doesn't exist — check first)

**Interfaces:**
- Consumes: `PlaneClient.post<T>(path, body)` from `@shared/api` (unchanged).
- Produces: `AuthAdapter` (unchanged shape) — `login`'s wire body changes, its TypeScript signature does not.

- [x] **Step 1: Check for an existing test file**

Run: `ls frontend/src/shared/auth/localSessionAdapter.test.ts`

If it exists, read it first and add to it; if not, create it fresh per Step 2.

- [x] **Step 2: Write the failing test**

```typescript
// frontend/src/shared/auth/localSessionAdapter.test.ts
import { describe, expect, it, vi } from 'vitest';

import type { PlaneClient } from '@shared/api';

import { createLocalSessionAdapter } from './localSessionAdapter';

function fakeCpClient(overrides: Partial<PlaneClient> = {}): PlaneClient {
  return {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
    ...overrides,
  } as PlaneClient;
}

describe('createLocalSessionAdapter', () => {
  it('posts the credential under the "credential" field, not "password"', async () => {
    const post = vi.fn().mockResolvedValue({
      principalId: 'p1',
      username: 'operator',
      source: 'LOCAL',
      mustRotate: false,
      permissions: [],
      csrfToken: 'tok',
      role: null,
      superAdmin: false,
      organizationId: 'org1',
    });
    const adapter = createLocalSessionAdapter(fakeCpClient({ post }));

    await adapter.login({ username: 'operator', password: 'secret' });

    expect(post).toHaveBeenCalledWith('/auth/login', { username: 'operator', credential: 'secret' });
  });
});
```

- [x] **Step 3: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/shared/auth/localSessionAdapter.test.ts`
Expected: FAIL — `post` is called with `{username, password}`, not `{username, credential}`.

- [x] **Step 4: Fix the adapter**

```typescript
// localSessionAdapter.ts — replace the login method body
async login(credentials: Credentials): Promise<Identity> {
  const response = await cpClient.post<Identity>('/auth/login', {
    username: credentials.username,
    credential: credentials.password,
  });
  return response;
},
```

Also delete the now-dead `LoginResponse` interface (with its `accessToken` field), the `inMemoryAccessToken` variable, and `getAccessToken`'s body — replace `getAccessToken(): string | undefined { return inMemoryAccessToken; }` with `getAccessToken(): undefined { return undefined; }` (keep the method on the adapter — `AuthAdapter.getAccessToken` is optional but other code may call it — but it now honestly always returns `undefined`, matching "the v1 cookie-session path returns `undefined`" already documented in `AuthAdapter.ts`'s own comment).

Update the file's header comment: replace the `CONTRACT: TBD` block (lines 6-10) with a note that the contract is now real, sourced from `docs/design/frontend/backend-integration-auth-hierarchy.md`.

- [x] **Step 5: Run the test to verify it passes**

Run: `cd frontend && npx vitest run src/shared/auth/localSessionAdapter.test.ts`
Expected: PASS.

- [x] **Step 6: Switch adapter selection to a build-time flag instead of hardcoded DEV**

In `AuthProvider.tsx`, change `selectAdapter`:

```typescript
function selectAdapter(cpClient: PlaneClient): AuthAdapter {
  // Static branch (Vite inlines both flags at build time, so a production
  // build's dead-code elimination still removes the mock import — see
  // devMockAdapter.ts's header). `VITE_USE_REAL_AUTH` is the one new knob:
  // DEV + unset/false -> mock (today's default, e2e-override-friendly);
  // DEV + true -> the real backend, for local integration testing;
  // non-DEV always uses the real adapter, unchanged.
  if (import.meta.env.DEV && import.meta.env['VITE_USE_REAL_AUTH'] !== 'true') {
    return createDevMockAdapter();
  }
  return createLocalSessionAdapter(cpClient);
}
```

Create `frontend/.env.development.local.example` (a template, never committed with real values — check `.gitignore` already excludes `.env*.local`, it does per Vite's default) documenting the flag:

```
# Copy to .env.development.local (gitignored) to run the Vite dev server
# against the real console-gateway backend instead of devMockAdapter.
# Requires Task 9's proxy + HTTPS dev server to be set up first.
VITE_USE_REAL_AUTH=true
```

- [x] **Step 7: Run the full frontend test suite to confirm nothing else broke**

Run: `cd frontend && npx vitest run`
Expected: PASS — `AuthProvider.test.tsx` and `RequireAuth.test.tsx` construct their own adapters via `adapterOverride`, unaffected by `selectAdapter`'s internals; Vitest itself always has `import.meta.env.DEV === true` and no `VITE_USE_REAL_AUTH` set, so `selectAdapter` still picks the mock in every test that doesn't override it.

- [x] **Step 8: Commit**

```bash
git add frontend/src/shared/auth/localSessionAdapter.ts frontend/src/shared/auth/localSessionAdapter.test.ts frontend/src/shared/auth/AuthProvider.tsx frontend/.env.development.local.example
git commit -m "fix(frontend): match login wire field, drop dead access-token path, add real-auth dev flag"
```

---

## Task 3: Frontend — wire CSRF

**Files:**
- Modify: `frontend/src/shared/auth/localSessionAdapter.ts`
- Modify: `frontend/src/shared/api/http.ts` (header name default only)
- Test: `frontend/src/shared/auth/localSessionAdapter.test.ts`

**Interfaces:**
- Consumes: `configureCsrf(config: CsrfConfig): void` from `@shared/api` (`http.ts`, already exists).
- Produces: nothing new — this task only calls existing seams correctly.

- [x] **Step 1: Write the failing test**

Add to `localSessionAdapter.test.ts`:

```typescript
it('configures CSRF with the backend header name and reads the token from the last identity/login response', async () => {
  const post = vi.fn().mockResolvedValue({
    principalId: 'p1', username: 'operator', source: 'LOCAL', mustRotate: false,
    permissions: [], csrfToken: 'first-token', role: null, superAdmin: false, organizationId: 'org1',
  });
  const configureCsrf = vi.fn();
  const adapter = createLocalSessionAdapter(fakeCpClient({ post }), configureCsrf);

  await adapter.login({ username: 'operator', password: 'secret' });

  expect(configureCsrf).toHaveBeenCalledWith(
    expect.objectContaining({ headerName: 'X-SM-CSRF', getToken: expect.any(Function) }),
  );
  const { getToken } = configureCsrf.mock.calls[0][0];
  expect(getToken()).toBe('first-token');
});
```

- [x] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/shared/auth/localSessionAdapter.test.ts`
Expected: FAIL — `createLocalSessionAdapter` doesn't take a second `configureCsrf` argument yet.

- [x] **Step 3: Wire it**

```typescript
// localSessionAdapter.ts
import { configureCsrf as defaultConfigureCsrf, onUnauthorized, type PlaneClient } from '@shared/api';
import type { CsrfConfig } from '@shared/api/types';

export function createLocalSessionAdapter(
  cpClient: PlaneClient,
  configureCsrf: (config: CsrfConfig) => void = defaultConfigureCsrf,
): AuthAdapter {
  let csrfToken: string | undefined;

  function rememberCsrf(identity: Identity & { csrfToken: string }): void {
    csrfToken = identity.csrfToken;
  }

  configureCsrf({ headerName: 'X-SM-CSRF', getToken: () => csrfToken });

  return {
    async getIdentity(): Promise<Identity> {
      const response = await cpClient.get<Identity & { csrfToken: string }>('/auth/identity');
      rememberCsrf(response);
      return response;
    },

    async login(credentials: Credentials): Promise<Identity> {
      const response = await cpClient.post<Identity & { csrfToken: string }>('/auth/login', {
        username: credentials.username,
        credential: credentials.password,
      });
      rememberCsrf(response);
      return response;
    },

    async logout(): Promise<void> {
      await cpClient.post('/auth/logout');
      csrfToken = undefined;
    },

    onSessionExpired(callback: SessionExpiredListener): Unsubscribe {
      return onUnauthorized(callback);
    },

    getAccessToken(): undefined {
      return undefined;
    },
  };
}
```

(`csrfToken` in the wire `Identity` is real per `Identity.java` — the frontend `Identity` type itself does not need a `csrfToken` field, since Task 6 keeps it out of the UI-facing type; the intersection type `Identity & { csrfToken: string }` used above is this file's own private read of the wire shape, matching how the header comment already documents "confined here so a real contract replaces only this file.")

`http.ts` already defaults `headerName` to `'X-CSRF-Token'` only when the caller passes none (`csrfConfig.headerName ?? 'X-CSRF-Token'`, `http.ts:56`) — since this adapter now always passes `'X-SM-CSRF'` explicitly, no change to `http.ts` is needed. (Verify this by reading the current line again before skipping — if a caller-less default was somehow made mandatory in the meantime, adjust the default there instead of only here.)

- [x] **Step 4: Run the test to verify it passes**

Run: `cd frontend && npx vitest run src/shared/auth/localSessionAdapter.test.ts`
Expected: PASS.

- [x] **Step 5: Run the full suite**

Run: `cd frontend && npx vitest run`
Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add frontend/src/shared/auth/localSessionAdapter.ts frontend/src/shared/auth/localSessionAdapter.test.ts
git commit -m "feat(frontend): wire CSRF header/token for the real session adapter"
```

---

## Task 4: Frontend — distinct handling for `RATE_LIMITED` (429) on login

**Files:**
- Modify: `frontend/src/shared/api/types.ts` — `ApiError` gains `retryAfterSeconds`
- Modify: `frontend/src/shared/api/http.ts` — populate it from the `Retry-After` header
- Modify: `frontend/src/features/auth/LoginForm.tsx`
- Modify: `frontend/src/shared/i18n` (wherever login copy strings live — check `t(...)` keys already used in `LoginForm.tsx` to find the right file, e.g. `frontend/src/shared/i18n/en.ts` or similar; add `loginRateLimitedTitle`/`loginRateLimitedBody`)
- Test: `frontend/src/features/auth/LoginForm.test.tsx`
- Test: `frontend/src/shared/api/http.test.ts` (check it exists; add to it)

**Interfaces:**
- Produces: `ApiError.retryAfterSeconds: number | undefined`.
- Consumes: `ApiError.status === 429` in `LoginForm`.

- [x] **Step 1: Write the failing `http.ts` test**

Find the existing test file for `http.ts` (likely `frontend/src/shared/api/http.test.ts`) and read it first to match its mocking style, then add:

```typescript
it('captures Retry-After as ApiError.retryAfterSeconds on a 429', async () => {
  server.use(/* or whatever this file's existing fetch-mock mechanism is — mirror an existing 401/500 test in this file exactly */);
  // response: status 429, header 'Retry-After': '30', body {"code":"RATE_LIMITED","status":429,...}

  await expect(requestWithCorrelation('/auth/login', { method: 'POST' })).rejects.toMatchObject({
    status: 429,
    retryAfterSeconds: 30,
  });
});
```

(Write this against whatever mocking mechanism the existing `http.test.ts` file actually uses — read it first; do not guess a different one.)

- [x] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/shared/api/http.test.ts`
Expected: FAIL — `ApiError` has no `retryAfterSeconds`.

- [x] **Step 3: Add the field and populate it**

In `types.ts`:

```typescript
export interface ApiErrorInit {
  status: number;
  code?: string | undefined;
  message?: string | undefined;
  correlationId?: string | undefined;
  details?: unknown;
  retryAfterSeconds?: number | undefined;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string | undefined;
  readonly correlationId?: string | undefined;
  readonly details?: unknown;
  readonly retryAfterSeconds?: number | undefined;

  constructor(init: ApiErrorInit) {
    super(init.message ?? `Request failed with status ${init.status}`);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.correlationId = init.correlationId;
    this.details = init.details;
    this.retryAfterSeconds = init.retryAfterSeconds;
  }
}
```

In `http.ts`'s `requestWithCorrelation`, where the `ApiError` is thrown:

```typescript
if (!response.ok) {
  const { code, message, details } = await parseErrorBody(response);
  const retryAfterHeader = response.headers.get('Retry-After');
  const retryAfterSeconds =
    retryAfterHeader !== null && /^\d+$/.test(retryAfterHeader) ? Number(retryAfterHeader) : undefined;
  throw new ApiError({
    status: response.status,
    code,
    message,
    details,
    correlationId: responseCorrelationId,
    retryAfterSeconds,
  });
}
```

- [x] **Step 4: Run it to verify it passes**

Run: `cd frontend && npx vitest run src/shared/api/http.test.ts`
Expected: PASS.

- [x] **Step 5: Write the failing `LoginForm` test**

Read `LoginForm.test.tsx` first to match its existing render/mock style, then add a case mocking `login` to throw `new ApiError({ status: 429, code: 'RATE_LIMITED', retryAfterSeconds: 30 })` and asserting the rendered text mentions the retry window (not the generic rejected message).

- [x] **Step 6: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/features/auth/LoginForm.test.tsx`
Expected: FAIL.

- [x] **Step 7: Extend `LoginForm`**

```typescript
type LoginStatus = 'idle' | 'submitting' | 'rejected' | 'unreachable' | 'rateLimited';

// inside the catch block:
} catch (error) {
  if (error instanceof ApiError && error.status === 429) {
    setStatus('rateLimited');
    setRetryAfterSeconds(error.retryAfterSeconds);
  } else {
    setStatus(error instanceof ApiError ? 'rejected' : 'unreachable');
  }
} finally {
  submittingRef.current = false;
}
```

Add `const [retryAfterSeconds, setRetryAfterSeconds] = useState<number | undefined>(undefined);` near the other `useState` calls, and render, alongside the existing `unreachable` alert:

```tsx
{status === 'rateLimited' && (
  <Alert severity="err" title={t('loginRateLimitedTitle')} fullWidth>
    {retryAfterSeconds !== undefined
      ? t('loginRateLimitedBodyWithSeconds', { seconds: retryAfterSeconds })
      : t('loginRateLimitedBody')}
  </Alert>
)}
```

Check `t()`'s actual signature for interpolation (grep other `t('...', { ... })` call sites in the codebase for the exact parameter-passing convention before assuming this shape) and add the three new keys to wherever `LoginForm`'s existing keys (`loginUnreachableTitle` etc.) are defined.

- [x] **Step 8: Run it to verify it passes**

Run: `cd frontend && npx vitest run src/features/auth/LoginForm.test.tsx`
Expected: PASS.

- [x] **Step 9: Commit**

```bash
git add frontend/src/shared/api/types.ts frontend/src/shared/api/http.ts frontend/src/shared/api/http.test.ts frontend/src/features/auth/LoginForm.tsx frontend/src/features/auth/LoginForm.test.tsx
git commit -m "feat(frontend): distinguish RATE_LIMITED (429) from a generic login rejection"
```

---

## Task 5: Frontend — `mustRotate` credential rotation

**Files:**
- Modify: `frontend/src/shared/auth/AuthAdapter.ts` — add `rotateCredential`
- Modify: `frontend/src/shared/auth/localSessionAdapter.ts`
- Modify: `frontend/src/shared/auth/devMockAdapter.ts` — add a no-op implementation so the interface stays satisfied
- Modify: `frontend/src/shared/auth/AuthProvider.tsx` — expose `identity.mustRotate` and a `rotateCredential` action
- Create: `frontend/src/features/auth/RotateCredentialDialog.tsx`
- Create: `frontend/src/features/auth/RotateCredentialDialog.test.tsx`
- Modify: `frontend/src/shared/auth/RequireAuth.tsx` — render the dialog, blocking, when `identity?.mustRotate`
- Test: `frontend/src/shared/auth/RequireAuth.test.tsx`
- Test: `frontend/src/shared/auth/localSessionAdapter.test.ts`

**Interfaces:**
- Produces: `AuthAdapter.rotateCredential(current: string, next: string): Promise<void>`.
- Produces: `AuthContextValue.identity.mustRotate: boolean` (Task 6 adds `mustRotate` to the frontend `Identity` type — this task assumes it exists; if Task 6 hasn't landed yet in execution order, do that field addition first as part of this task instead of waiting).
- Consumes: `POST /credential` via `cpClient`, 12-character minimum enforced server-side (`CREDENTIAL_ROTATION_REFUSED`, 400, on violation — surfaced as a generic `ApiError`, no special UI needed beyond showing its message).

- [x] **Step 1: Add `mustRotate` to the frontend `Identity` type now (pulled forward from Task 6)**

In `AuthAdapter.ts`, add `mustRotate: boolean;` to the `Identity` interface, and add:

```typescript
export interface AuthAdapter {
  getIdentity(): Promise<Identity>;
  login(credentials: Credentials): Promise<Identity>;
  logout(): Promise<void>;
  rotateCredential(current: string, next: string): Promise<void>;
  onSessionExpired(callback: SessionExpiredListener): Unsubscribe;
  getAccessToken?(): string | undefined;
}
```

- [x] **Step 2: Write the failing adapter test**

Add to `localSessionAdapter.test.ts`:

```typescript
it('posts current/new credential to /auth/credential on rotateCredential', async () => {
  const post = vi.fn().mockResolvedValue(undefined);
  const adapter = createLocalSessionAdapter(fakeCpClient({ post }));

  await adapter.rotateCredential('old-secret', 'new-secret-12chars');

  expect(post).toHaveBeenCalledWith('/auth/credential', {
    currentCredential: 'old-secret',
    newCredential: 'new-secret-12chars',
  });
});
```

- [x] **Step 3: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/shared/auth/localSessionAdapter.test.ts`
Expected: FAIL — `rotateCredential` doesn't exist.

- [x] **Step 4: Implement it**

Add to `localSessionAdapter.ts`'s returned object:

```typescript
async rotateCredential(current: string, next: string): Promise<void> {
  await cpClient.post('/auth/credential', { currentCredential: current, newCredential: next });
},
```

Add to `devMockAdapter.ts`'s returned object (mock never actually needs rotation, but must satisfy the interface):

```typescript
rotateCredential(): Promise<void> {
  return Promise.resolve();
},
```

Add `mustRotate: false` to `MOCK_IDENTITY` in `devMockAdapter.ts` (and to every spread copy in `identityForSession`, which already spreads `MOCK_IDENTITY` so no extra change needed there).

- [x] **Step 5: Run the test to verify it passes**

Run: `cd frontend && npx vitest run src/shared/auth/localSessionAdapter.test.ts`
Expected: PASS.

- [x] **Step 6: Expose it through `AuthProvider`**

In `AuthProvider.tsx`, add to `AuthContextValue`:

```typescript
rotateCredential: (current: string, next: string) => Promise<void>;
```

and in the provider body:

```typescript
const rotateCredential = useCallback(
  async (current: string, next: string): Promise<void> => {
    await adapter.rotateCredential(current, next);
    const refreshed = await adapter.getIdentity();
    setIdentity(refreshed);
  },
  [adapter],
);

const value = useMemo<AuthContextValue>(
  () => ({ status, identity, arrival, login, logout, getAccessToken, rotateCredential }),
  [status, identity, arrival, login, logout, getAccessToken, rotateCredential],
);
```

- [x] **Step 7: Write the failing `RotateCredentialDialog` test**

```typescript
// frontend/src/features/auth/RotateCredentialDialog.test.tsx
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import userEvent from '@testing-library/user-event';

import { RotateCredentialDialog } from './RotateCredentialDialog';

describe('RotateCredentialDialog', () => {
  it('submits current and new credential and calls onRotated on success', async () => {
    const onRotate = vi.fn().mockResolvedValue(undefined);
    renderWithProviders(<RotateCredentialDialog open onRotate={onRotate} />);

    await userEvent.type(screen.getByLabelText(/current/i), 'old-secret');
    await userEvent.type(screen.getByLabelText(/new/i), 'new-secret-12chars');
    await userEvent.click(screen.getByRole('button', { name: /rotate|update|submit/i }));

    await waitFor(() => expect(onRotate).toHaveBeenCalledWith('old-secret', 'new-secret-12chars'));
  });
});
```

(Adjust the accessible-name matchers to whatever labels/copy this task's Step 8 component actually uses, once written — the test must match the real implementation, not the other way around; write the component first if it's easier to keep them in sync, but keep both in the same commit either way.)

- [x] **Step 8: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/features/auth/RotateCredentialDialog.test.tsx`
Expected: FAIL — the component doesn't exist.

- [x] **Step 9: Write the minimal dialog**

```tsx
// frontend/src/features/auth/RotateCredentialDialog.tsx
/**
 * Forced credential rotation (backend `mustRotate` / 428 `MUST_ROTATE`).
 * Blocking by design — rendered by `RequireAuth` in place of the normal
 * app shell whenever `identity.mustRotate` is true, with no dismiss path,
 * because the backend itself fails every other authenticated call closed
 * until rotation succeeds.
 */
import { useState, type FormEvent } from 'react';

import { ApiError } from '@shared/api';
import { t } from '@shared/i18n';
import { Alert, Button, Dialog, TextField } from '@shared/ui';
import { Stack } from '@shared/ui/layout';

export interface RotateCredentialDialogProps {
  open: boolean;
  onRotate: (current: string, next: string) => Promise<void>;
}

export function RotateCredentialDialog({ open, onRotate }: RotateCredentialDialogProps) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(undefined);
    try {
      await onRotate(current, next);
    } catch (submitError) {
      setError(submitError instanceof ApiError ? submitError.message : t('rotateCredentialUnreachable'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} title={t('rotateCredentialTitle')} disableClose>
      <form onSubmit={(event) => void handleSubmit(event)}>
        <Stack gap={2.25}>
          <Alert severity="info" title={t('rotateCredentialExplanation')} fullWidth />
          {error !== undefined && <Alert severity="err" title={error} fullWidth />}
          <TextField
            label={t('currentCredentialLabel')}
            required
            reveal
            value={current}
            onChange={(event) => setCurrent(event.target.value)}
            autoComplete="current-password"
          />
          <TextField
            label={t('newCredentialLabel')}
            required
            reveal
            value={next}
            onChange={(event) => setNext(event.target.value)}
            autoComplete="new-password"
          />
          <Button type="submit" size="lg" block disabled={submitting}>
            {submitting ? t('rotatingCredential') : t('rotateCredentialSubmit')}
          </Button>
        </Stack>
      </form>
    </Dialog>
  );
}
```

Check `@shared/ui`'s actual `Dialog` component props (`disableClose` is a guess at the right prop name for "no dismiss" — read `frontend/src/shared/ui/Dialog` first and use its real prop) before finalizing this file, and add the six new `t()` keys used above to the i18n source found in Task 4.

- [x] **Step 10: Run the test to verify it passes**

Run: `cd frontend && npx vitest run src/features/auth/RotateCredentialDialog.test.tsx`
Expected: PASS.

- [x] **Step 11: Wire it into `RequireAuth`**

```tsx
// RequireAuth.tsx
import { RotateCredentialDialog } from '../../features/auth/RotateCredentialDialog';
// (adjust the relative path/alias to match this file's existing import style — shared/ importing from features/
// may be a boundary violation per eslint.config.js; if so, move RotateCredentialDialog under shared/auth/ instead
// of features/auth/ and re-check Step 9's file location before this step — verify with
// `cd frontend && npx eslint src/shared/auth/RequireAuth.tsx` once this import is added.)

export function RequireAuth({ children, idleTimeoutMinutes, loginPath = '/login' }: RequireAuthProps) {
  const { status, arrival, logout, identity, rotateCredential } = useAuth();
  // ...unchanged idle-timer and loading/unauthenticated branches...

  if (status === 'authenticated' && identity?.mustRotate === true) {
    return (
      <RotateCredentialDialog
        open
        onRotate={async (current, next) => {
          await rotateCredential(current, next);
        }}
      />
    );
  }

  return <>{children}</>;
}
```

- [x] **Step 12: Add a `RequireAuth` test for the blocking path**

Read `RequireAuth.test.tsx` first for its existing render/mock conventions, then add a case that supplies an `adapterOverride` whose `getIdentity` resolves with `mustRotate: true` and asserts the dialog renders instead of `children`.

- [x] **Step 13: Run it and the full suite**

Run: `cd frontend && npx vitest run src/shared/auth/RequireAuth.test.tsx && npx vitest run`
Expected: PASS.

- [x] **Step 14: Commit**

```bash
git add frontend/src/shared/auth frontend/src/features/auth/RotateCredentialDialog.tsx frontend/src/features/auth/RotateCredentialDialog.test.tsx
git commit -m "feat(frontend): forced credential rotation dialog, gated on identity.mustRotate"
```

---

## Task 6: Frontend — align `Identity` type with the real wire shape, flatten permissions, update consumers

**Files:**
- Modify: `frontend/src/shared/auth/AuthAdapter.ts`
- Modify: `frontend/src/shared/auth/localSessionAdapter.ts`
- Modify: `frontend/src/shared/auth/devMockAdapter.ts`
- Modify: `frontend/src/app/shell/AccountMenu.tsx`
- Modify: `frontend/src/app/shell/OrganizationDisplay.tsx`
- Modify: `frontend/src/app/shell/AppBar.tsx`
- Test: `frontend/src/app/shell/AccountMenu.test.tsx`, `OrganizationDisplay.test.tsx` (check for existing tests first, update their fixtures)

**Interfaces:**
- Produces (final `Identity` shape for this slice): `{ username: string; source: 'local' | 'idp'; mustRotate: boolean; role: string | null; superAdmin: boolean; organizationId: string; permissions: readonly string[] }`. `name`/`email`/`organization` are removed.

- [x] **Step 1: Update the `Identity` type**

```typescript
// AuthAdapter.ts
export type IdentitySource = 'local' | 'idp';

export interface Identity {
  username: string;
  source: IdentitySource;
  mustRotate: boolean;
  /** `null` when the principal has no role binding at all (Task 1's Review Focus case). */
  role: string | null;
  superAdmin: boolean;
  /** UUID string. Display-only, server-sourced (`I5`) — see the spec's D2 for why this is an id, not a name. */
  organizationId: string;
  permissions: readonly string[];
}
```

Remove `name`/`email`/`organization` from the doc comment above it accordingly (it currently explains why `organizationId` doesn't exist — flip that explanation to describe why `organizationId` exists as an id, not a name).

- [x] **Step 2: Write the failing adapter test for permission flattening**

Add to `localSessionAdapter.test.ts`:

```typescript
it('flattens scoped permissions to a flat id list and passes through role/superAdmin/organizationId', async () => {
  const post = vi.fn().mockResolvedValue({
    principalId: 'p1', username: 'operator', source: 'LOCAL', mustRotate: false,
    permissions: [
      { permission: 'instance.view', scopes: [{}] },
      { permission: 'config.edit', scopes: [{}, {}] },
    ],
    csrfToken: 'tok', role: 'Operator', superAdmin: false, organizationId: 'org-1',
  });
  const adapter = createLocalSessionAdapter(fakeCpClient({ post }));

  const identity = await adapter.login({ username: 'operator', password: 'secret' });

  expect(identity).toMatchObject({
    username: 'operator',
    source: 'local',
    mustRotate: false,
    role: 'Operator',
    superAdmin: false,
    organizationId: 'org-1',
    permissions: ['instance.view', 'config.edit'],
  });
});
```

- [x] **Step 3: Run it to verify it fails**

Run: `cd frontend && npx vitest run src/shared/auth/localSessionAdapter.test.ts`
Expected: FAIL — the adapter still returns the wire shape unmapped.

- [x] **Step 4: Add the mapping**

```typescript
// localSessionAdapter.ts
interface WireEffectivePermission {
  permission: string;
  scopes: unknown[];
}

interface WireIdentity {
  principalId: string;
  username: string;
  source: 'LOCAL' | 'IDP';
  mustRotate: boolean;
  permissions: WireEffectivePermission[];
  csrfToken: string;
  role: string | null;
  superAdmin: boolean;
  organizationId: string;
}

function toIdentity(wire: WireIdentity): Identity {
  return {
    username: wire.username,
    source: wire.source === 'LOCAL' ? 'local' : 'idp',
    mustRotate: wire.mustRotate,
    role: wire.role,
    superAdmin: wire.superAdmin,
    organizationId: wire.organizationId,
    permissions: wire.permissions.map((entry) => entry.permission),
  };
}
```

Update `getIdentity`/`login` to call `toIdentity(response)` before returning, and `rememberCsrf` to take the raw `WireIdentity` (it only reads `.csrfToken`, unaffected by the mapping).

- [x] **Step 5: Run it to verify it passes**

Run: `cd frontend && npx vitest run src/shared/auth/localSessionAdapter.test.ts`
Expected: PASS.

- [x] **Step 6: Update `devMockAdapter.ts`'s fixture identity**

```typescript
const MOCK_IDENTITY: Identity = {
  username: 'devon.ramirez',
  source: 'local',
  mustRotate: false,
  role: 'Administrator',
  superAdmin: true,
  organizationId: '00000000-0000-4000-8000-000000000011',
  permissions: PERMISSION_CATALOG.map((permission) => permission.id),
};
```

- [x] **Step 7: Update `AccountMenu.tsx`**

```tsx
<Avatar sx={{ width: 32, height: 32, fontSize: 13, fontWeight: 600 }}>
  {initials(identity.username)}
</Avatar>
```

```tsx
<Box>
  <Typography variant="body2" sx={{ fontWeight: 600 }}>
    {identity.username}
  </Typography>
</Box>
<Box>
  <Chip label={identity.role ?? t('noRoleAssigned')} tone="info" />
</Box>
```

(Drop the `<Typography variant="caption" ...>{identity.email}</Typography>` block entirely. Add the `noRoleAssigned` i18n key.)

- [x] **Step 8: Update `OrganizationDisplay.tsx` and its caller**

```tsx
// OrganizationDisplay.tsx
export interface OrganizationDisplayProps {
  organizationId: string;
}

export function OrganizationDisplay({ organizationId }: OrganizationDisplayProps) {
  return (
    <Box title={t('organizationTooltip')} sx={{ display: 'flex', alignItems: 'baseline', gap: 0.75 }}>
      <Typography variant="caption" color="text.secondary">
        {t('organizationLabel')}
      </Typography>
      <Typography variant="body2" sx={{ fontWeight: 500 }}>
        {organizationId}
      </Typography>
    </Box>
  );
}
```

Update its header comment: `Identity.organization` → `Identity.organizationId`, and note it's a raw id for this slice, not a display name.

In `AppBar.tsx`, both call sites: `<OrganizationDisplay organization={identity.organization} />` → `<OrganizationDisplay organizationId={identity.organizationId} />`.

- [x] **Step 9: Update the component tests' fixtures**

Read `AccountMenu.test.tsx` and `OrganizationDisplay.test.tsx` (and any `AppBar.test.tsx` that constructs an `Identity`), replace their fixture `Identity` objects with the new shape, and fix any assertion that checked for rendered `name`/`email` text.

- [x] **Step 10: Run the full suite**

Run: `cd frontend && npx vitest run`
Expected: PASS.

- [x] **Step 11: Type-check and lint**

Run: `cd frontend && npx tsc --noEmit && npx eslint src`
Expected: no errors — this step is what actually confirms every `identity.name`/`identity.email`/`identity.organization` reference in the codebase was found and fixed; the earlier `grep` this plan was built from is a starting point, not a guarantee of completeness.

- [x] **Step 12: Commit**

```bash
git add frontend/src/shared/auth frontend/src/app/shell
git commit -m "feat(frontend): align Identity with the real wire shape (role/superAdmin/organizationId, drop name/email)"
```

---

## Task 7: Frontend — hierarchy envelope unwrapping and id-based cascade resolution

**Files:**
- Modify: `frontend/src/shared/hierarchy/hierarchyRepository.ts`
- Modify: `frontend/src/shared/hierarchy/hierarchyRepository.test.ts`

**Interfaces:**
- Unchanged public interface: `HierarchyRepository.organizations(): Promise<string[]>`, `.subOrgs(org: string): Promise<string[]>`, `.environments(org, subOrg): Promise<Env[]>` — every existing caller (`InstancesRoute.tsx`, `DeclareInstanceDialog.tsx`, `InstancesPage.tsx`) is untouched by this task; the id resolution needed for the real backend's UUID path segments happens entirely inside this file's own closure.

- [x] **Step 1: Write the failing tests**

Read `hierarchyRepository.test.ts` first to match its existing mocking style (likely a fake `PlaneClient`), then add/replace cases:

```typescript
it('unwraps the ScopeNodeList envelope for organizations', async () => {
  const get = vi.fn().mockResolvedValue({
    stateVersion: 1, availability: null,
    items: [{ id: 'org-uuid-1', name: 'Acme Financial' }, { id: 'org-uuid-2', name: 'Globex' }],
  });
  const repo = createHierarchyRepository(fakeCpClient({ get }));

  await expect(repo.organizations()).resolves.toEqual(['Acme Financial', 'Globex']);
  expect(get).toHaveBeenCalledWith('/hierarchy/organizations', {});
});

it('resolves the parent organization id from the prior organizations() call when fetching sub-orgs', async () => {
  const get = vi.fn()
    .mockResolvedValueOnce({ stateVersion: 1, items: [{ id: 'org-uuid-1', name: 'Acme Financial' }] })
    .mockResolvedValueOnce({ stateVersion: 1, items: [{ id: 'sub-uuid-1', name: 'Payments' }] });
  const repo = createHierarchyRepository(fakeCpClient({ get }));

  await repo.organizations();
  const subOrgs = await repo.subOrgs('Acme Financial');

  expect(subOrgs).toEqual(['Payments']);
  expect(get).toHaveBeenNthCalledWith(2, '/hierarchy/organizations/org-uuid-1/sub-orgs', {});
});

it('throws a clear error when subOrgs is called for a name not seen in the last organizations() call', async () => {
  const get = vi.fn().mockResolvedValue({ stateVersion: 1, items: [{ id: 'org-uuid-1', name: 'Acme Financial' }] });
  const repo = createHierarchyRepository(fakeCpClient({ get }));

  await repo.organizations();

  await expect(repo.subOrgs('Nonexistent Org')).rejects.toThrow(/Nonexistent Org/);
});

it('resolves the parent sub-org id from the prior subOrgs() call when fetching environments', async () => {
  const get = vi.fn()
    .mockResolvedValueOnce({ stateVersion: 1, items: [{ id: 'org-uuid-1', name: 'Acme Financial' }] })
    .mockResolvedValueOnce({ stateVersion: 1, items: [{ id: 'sub-uuid-1', name: 'Payments' }] })
    .mockResolvedValueOnce({ stateVersion: 1, items: [{ id: 'env-uuid-1', name: 'PROD' }] });
  const repo = createHierarchyRepository(fakeCpClient({ get }));

  await repo.organizations();
  await repo.subOrgs('Acme Financial');
  const environments = await repo.environments('Acme Financial', 'Payments');

  expect(get).toHaveBeenNthCalledWith(3, '/hierarchy/organizations/org-uuid-1/sub-orgs/sub-uuid-1/environments', {});
  expect(environments).toEqual(['PROD']);
});
```

- [x] **Step 2: Run them to verify they fail**

Run: `cd frontend && npx vitest run src/shared/hierarchy/hierarchyRepository.test.ts`
Expected: FAIL — the current implementation expects a bare array and builds URLs from the raw name.

- [x] **Step 3: Rewrite the repository**

```typescript
// hierarchyRepository.ts
import type { PlaneClient } from '@shared/api';
import type { Env } from '@shared/testdoubles/data/instances';

export interface HierarchyRepository {
  organizations(signal?: AbortSignal): Promise<string[]>;
  subOrgs(org: string, signal?: AbortSignal): Promise<string[]>;
  environments(org: string, subOrg: string, signal?: AbortSignal): Promise<Env[]>;
}

interface ScopeNode {
  id: string;
  name: string;
}

interface ScopeNodeList {
  stateVersion: number;
  availability?: unknown;
  items: ScopeNode[];
}

function opts(signal?: AbortSignal): { signal?: AbortSignal } {
  return signal !== undefined ? { signal } : {};
}

function idFor(cache: Map<string, string>, name: string, levelLabel: string): string {
  const id = cache.get(name);
  if (id === undefined) {
    throw new Error(
      `${levelLabel} "${name}" was not present in the last loaded list — the cascade was called out of order or with a stale selection.`,
    );
  }
  return id;
}

export function createHierarchyRepository(cpClient: PlaneClient): HierarchyRepository {
  // Populated by each level's own call, keyed by display name — the cascade is
  // always driven top-down (an operator cannot pick a sub-org before its
  // organization), so by the time a lower level is requested, the level above
  // has already been loaded and cached its ids here.
  const orgIds = new Map<string, string>();
  const subOrgIds = new Map<string, string>();

  return {
    async organizations(signal) {
      const list = await cpClient.get<ScopeNodeList>('/hierarchy/organizations', opts(signal));
      orgIds.clear();
      list.items.forEach((item) => orgIds.set(item.name, item.id));
      return list.items.map((item) => item.name);
    },

    async subOrgs(org, signal) {
      const orgId = idFor(orgIds, org, 'Organization');
      const list = await cpClient.get<ScopeNodeList>(
        `/hierarchy/organizations/${encodeURIComponent(orgId)}/sub-orgs`,
        opts(signal),
      );
      subOrgIds.clear();
      list.items.forEach((item) => subOrgIds.set(item.name, item.id));
      return list.items.map((item) => item.name);
    },

    async environments(org, subOrg, signal) {
      const orgId = idFor(orgIds, org, 'Organization');
      const subOrgId = idFor(subOrgIds, subOrg, 'Sub-organization');
      const list = await cpClient.get<ScopeNodeList>(
        `/hierarchy/organizations/${encodeURIComponent(orgId)}/sub-orgs/${encodeURIComponent(subOrgId)}/environments`,
        opts(signal),
      );
      return list.items.map((item) => item.name as Env);
    },
  };
}
```

(The `item.name as Env` cast is resolved properly by Task 8, which widens `Env`; until Task 8 lands this is a type-level lie consistent with what the file already did before this task — real behavior is unaffected either way, since `Env` is a compile-time-only construct.)

- [x] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx vitest run src/shared/hierarchy/hierarchyRepository.test.ts`
Expected: PASS.

- [x] **Step 5: Run the full suite (confirms the three existing callers are unaffected)**

Run: `cd frontend && npx vitest run`
Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add frontend/src/shared/hierarchy
git commit -m "fix(frontend): unwrap ScopeNodeList envelope and resolve hierarchy cascade by id internally"
```

---

## Task 8: Frontend — widen `Env` beyond the fixed literal union

**Files:**
- Modify: `frontend/src/shared/ui/EnvChip/EnvChip.types.ts`
- Modify: `frontend/src/shared/ui/EnvChip/EnvChip.tsx`
- Modify: `frontend/src/shared/testdoubles/data/instances.ts`
- Test: `frontend/src/shared/ui/EnvChip/EnvChip.test.tsx`

**Interfaces:**
- Produces: `Env = 'DEV' | 'TEST' | 'PROD' | (string & {})` (widened while keeping autocomplete for the three known values — the `& {}` idiom keeps literal-type suggestions in editors without collapsing to plain `string`).
- Produces: `EnvChip` renders any string, falling back to a neutral tone for anything outside the three known ones.

- [x] **Step 1: Write the failing test**

Add to `EnvChip.test.tsx`:

```typescript
it('renders an unrecognized environment name with the neutral fallback tone', () => {
  renderWithProviders(<EnvChip env="STAGING" />);
  const chip = screen.getByText('STAGING');
  expect(chip).not.toBeNull();
});
```

- [x] **Step 2: Run it to verify it fails**

Run: `cd frontend && npx tsc --noEmit`
Expected: FAIL to compile — `"STAGING"` is not assignable to the closed `Env` union yet.

- [x] **Step 3: Widen the type and add the fallback**

```typescript
// EnvChip.types.ts
export type Env = 'DEV' | 'TEST' | 'PROD' | (string & {});

export interface EnvChipProps {
  env: Env;
}
```

```typescript
// EnvChip.tsx
import { Chip } from '../Chip';
import type { ChipTone } from '../Chip';

import type { Env, EnvChipProps } from './EnvChip.types';

const KNOWN_ENV_TONE: Partial<Record<string, ChipTone>> = {
  DEV: 'neutral',
  TEST: 'info',
  PROD: 'purple',
};

export function EnvChip({ env }: EnvChipProps) {
  return <Chip label={env} tone={KNOWN_ENV_TONE[env] ?? 'neutral'} />;
}
```

Update `instances.ts`'s `export type Env = 'DEV' | 'TEST' | 'PROD';` the same way, to `export type Env = 'DEV' | 'TEST' | 'PROD' | (string & {});` (this is the type `hierarchyRepository.ts`'s `environments()` returns — Task 7's cast becomes type-honest once this lands).

- [x] **Step 4: Run the tests to verify they pass**

Run: `cd frontend && npx tsc --noEmit && npx vitest run src/shared/ui/EnvChip`
Expected: PASS.

- [x] **Step 5: Run the full suite**

Run: `cd frontend && npx vitest run`
Expected: PASS — the three existing `EnvChip` tests (`DEV`/`TEST`/`PROD`) still pass unchanged since those keys are untouched.

- [x] **Step 6: Commit**

```bash
git add frontend/src/shared/ui/EnvChip frontend/src/shared/testdoubles/data/instances.ts
git commit -m "feat(frontend): widen Env to accept server-defined environment names beyond the fixture's fixed three"
```

---

## Task 9: Dev serving — Vite proxy, HTTPS, and the backend trusted-origin/topology config

**Files:**
- Modify: `frontend/vite.config.ts`
- Create: `backend/console-gateway/src/main/resources/application-dev.yml` (or add a `dev` profile section to whatever config file already exists — check first with `find backend/console-gateway/src/main/resources -iname "application*"`)
- Modify: `docs/design/frontend/backend-integration-auth-hierarchy.md` (mark D4 as implemented, not a new decision)

**Interfaces:** none — this task is configuration only, no new code interface.

- [x] **Step 1: Check for an existing backend dev profile**

Run: `find backend/console-gateway/src/main/resources -iname "application*"`

Read whatever exists before adding a new file — if `application.yml` already has a `dev` Spring profile section, extend that instead of creating a new file.

- [x] **Step 2: Add the backend dev config**

Add (either to the existing file under a `---` / `spring.config.activate.on-profile: dev` document, or as a new `application-dev.yml`):

```yaml
sm:
  gateway:
    trusted-origins:
      - https://localhost:5173
    direct-client-connections: true
    # Single local developer, no reverse proxy in front of the Vite dev
    # server's own backend proxy — see vite.config.ts's server.proxy and
    # GatewayProperties' class javadoc for why this is the honest choice
    # for local dev (not the documented production topology, which does
    # front the gateway with a real reverse proxy and sets trusted-proxies
    # instead).
```

- [x] **Step 3: Add the Vite proxy and HTTPS dev server**

Check whether `@vitejs/plugin-basic-ssl` (or equivalent) is already a devDependency: `grep -n "basic-ssl\|vite-plugin-mkcert" frontend/package.json`. If not present, this step also needs `npm install --save-dev @vitejs/plugin-basic-ssl` in `frontend/` (a self-signed cert plugin — acceptable for local dev only, never referenced by the production build).

```typescript
// vite.config.ts
import basicSsl from '@vitejs/plugin-basic-ssl';
// ...existing imports...

export default defineConfig({
  plugins: [react(), basicSsl(), stripCspInDev(), dropDevOnlyAssetsFromBuild()],
  resolve: { alias },
  server: {
    https: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: false,
        // changeOrigin: false is deliberate — console-gateway validates the
        // Origin/Referer header against its own trusted-origins allowlist
        // (Step 2), and that check must see the BROWSER's origin
        // (https://localhost:5173), not a rewritten one.
      },
    },
  },
});
```

(Adjust `target` port `8080` to whatever `console-gateway`'s actual dev-run port is — check `application.yml`'s `server.port` if set, else Spring Boot's default `8080` is correct.)

- [x] **Step 4: Manually verify the proxy and cookie path work**

This step has no automated test — it's an infrastructure check, not app logic. Run:

```bash
cd backend && mvn -pl console-gateway -am spring-boot:run -Dspring-boot.run.profiles=dev
```

In a second terminal:

```bash
cd frontend && npm run dev
```

Open `https://localhost:5173` (accept the self-signed cert warning once), and confirm in the browser's Network tab that a request to `/api/cp/hierarchy/organizations` proxies through to the backend and returns `401` (expected — not logged in yet, confirms the proxy path and origin validation both work before Task 10's full login flow is exercised).

- [x] **Step 5: Commit**

```bash
git add frontend/vite.config.ts frontend/package.json frontend/package-lock.json backend/console-gateway/src/main/resources
git commit -m "feat(dev): serve frontend and backend from one HTTPS origin via Vite proxy for local integration"
```

---

## Task 10: End-to-end manual smoke test (acceptance gate)

**Files:** none created — this is the plan's acceptance gate, per `CLAUDE.md`'s "start the dev server and use the feature in a browser" rule and the spec's §2 testing requirement.

- [x] **Step 1: Start both processes**

```bash
cd backend && mvn -pl console-gateway -am spring-boot:run -Dspring-boot.run.profiles=dev
```
```bash
cd frontend && VITE_USE_REAL_AUTH=true npm run dev
```

(Or set `VITE_USE_REAL_AUTH=true` in `.env.development.local`, per Task 2 Step 6, instead of passing it inline.)

- [x] **Step 2: Seed a bootstrap login**

Use the bootstrap admin credential the backend's `V007__bootstrap_admin.sql` migration seeded (the operator running this needs the plaintext credential the installer generated — check `backend/README.md` or whatever startup log/doc records it locally; this plan does not invent one).

- [x] **Step 3: Walk the golden path in the browser**

1. Open `https://localhost:5173/login`. Confirm the login form renders (not the dev mock's auto-authenticated state).
2. Submit the bootstrap credential. Confirm redirect to `/`, and that `AccountMenu` shows the bootstrap username and "Administrator" role chip (no name/email row).
3. Confirm the forced-rotation dialog appears immediately (`must_rotate = true` for the bootstrap principal per `V007`'s migration) and blocks the rest of the UI.
4. Rotate the credential (12+ characters). Confirm the dialog closes and the app shell renders.
5. Navigate to wherever `InstancesRoute`/`DeclareInstanceDialog` exposes the hierarchy cascade. Confirm organizations load, selecting one loads its sub-orgs, selecting one loads its environments — all real network calls, visible in the Network tab hitting `/api/cp/hierarchy/*`.
6. Sign out via `AccountMenu`. Confirm redirect to `/login` and that a subsequent direct navigation to `/` redirects back to `/login` (session cookie cleared).
7. Log back in, then wait out (or simulate) a session expiry / manually delete the session cookie via devtools, then trigger any authenticated request. Confirm the app redirects to `/login?arrival=session` (not a silent hang or a raw error).

- [x] **Step 4: Record the result**

Per this repo's convention (`docs/plans/frontend/reports/`), write `docs/plans/frontend/reports/task-132-report.md` summarizing: which of Step 3's seven checks passed, any deviation from this plan discovered during execution (e.g. an actual backend dev port different from the assumed `8080`, an actual `Dialog` prop name different from the guessed `disableClose`), and any follow-up item this surfaced that is out of this plan's scope (e.g. the deferred `name`/`email` schema gap from the spec's §5).

- [x] **Step 5: Commit the report**

```bash
git add docs/plans/frontend/reports/task-132-report.md
git commit -m "docs: record task 132 end-to-end smoke test results"
```

---

## Review — plan closed 2026-10-01

**Status: ✅ COMPLETE.** All 10 tasks executed and committed (`b42c80b`, `41ac305`, `35e4d68`, `0d4c00d`, `cb71db0`, `e83dbee`, `ef9fa53`, `7bdd1cd`, `eb7e09d`, `1207230`, `fe55303`, `5a848dd`; final whole-branch fixes `dcbbfea`). Every step above is ticked.

**Verification.**
- Wire level (Task 10, original run): login → forced rotation → hierarchy cascade → logout → session expiry all behaved as specified. Report: [`reports/001-task-10-report.md`](reports/001-task-10-report.md).
- **Real browser (Playwright/Chromium, 2026-10-01)** against the real `launcher`, real Postgres, Vite HTTPS proxy: login form, forced-rotation dialog (Esc and × do not dismiss it), wrong-credential refusal (400), successful rotation (204), account menu showing `bootstrap-admin` / `Administrator`, session persists across reload, logout clears both cookies, server-side session loss → `/login?arrival=session` with the banner.
- Hierarchy cascade was exercised **by calling the app's own `hierarchyRepository` in the browser** (3 levels, correct ids, `ScopeNodeList` envelope). It was **not** driven through the UI: the declare-instance dialog needs `listSolutions`/`listInstances`, which the production gateway does not serve yet (→ [plan 002](002-remaining-backend.md)).

**Defect found by the browser run and fixed.** Rotation returned **403 `CSRF_REJECTED`** in the browser although the wire-level run passed. Cause: `app/main.tsx` uses `StrictMode`, which double-invokes the `useMemo` that builds the auth adapter; the discarded twin's `configureCsrf` side effect took over the process-global header hook, so the adapter actually in use sent no `X-SM-CSRF`. Fix: `localSessionAdapter.ts` re-registers the hook on every token received; regression test added in `localSessionAdapter.test.ts`. Landed inside merge commit `66e2bb2` ("Backend integration"), not as its own commit.
**Lesson:** a wire-level smoke test cannot prove client-side wiring. Any plan whose acceptance gate is "the browser works" needs a browser run (recorded in `docs/tasks/lessons.md`).

**Follow-ups found, deliberately NOT fixed here (own tickets/plans):**
1. First-time visitors (never logged in) see "Your session ended" — the initial identity 401 is treated as an expiry. Needs the first-load 401 distinguished from a mid-session 401.
2. No runbook step for generating/recording the bootstrap credential hash (`V007` takes an Argon2id hash as an installer placeholder; nothing in `backend/README.md` tells an operator how).
3. Hierarchy node names and the header organization label are raw UUIDs on the wire (known: `HierarchyController` javadoc on `STATIC_STATE_VERSION`; spec §5 deferred `name`/`email`).
4. Login takes ~5–8 s (Argon2id, 384 MiB). Expected, but the button state is the only feedback.
5. Backend `mvn verify` fails in `mesh-events` (`tenant` vs `organization` contract rename, unreconciled) — pre-existing, outside this plan; see plan 002 Task 0 results.
6. MSW hierarchy fixtures flagged stale in Task 7's report remain stale.

**Deviations recorded:** `console-gateway` is a library, the runnable root is `backend/launcher` (`SolutionManagerApplication`); port 8080 collided with a local Solace container so the verification ran the backend on 8081 with a temporary proxy edit (reverted); a fresh install has no sub-orgs/environments, so verification seeded one of each by SQL, as `HierarchyEndpointsIT` does.
