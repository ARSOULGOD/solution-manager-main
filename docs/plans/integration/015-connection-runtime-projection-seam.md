# Connection runtime projection seam — Integration Plan 015

## Bug this plan fixes

## Deferred instance-placement validation

For the current phase, `declareInstance` validates that `connectionId` names an active control-plane
`remote-server` Connection, but it does not require a live runtime projection and does not perform container-name,
config-path, container-config-path, file-name, or
connection-envelope validation. Those placement checks are intentionally deferred until runtime-specific placement
support is introduced. The request fields remain present so the declaration shape is unchanged; this is a temporary
phase decision and must be revisited before enabling deployment/apply for generic or non-Docker services. The V040 and V041
schema migration makes the same distinction: target-backed rows retain the existing grammar and envelope guards,
while connection-backed rows are allowed to carry runtime-specific values without a runtime projection until that later
validation phase. `ConnectionRuntimePolicy.REQUIRE_LIVE_PROJECTION` is the code toggle for restoring this gate.

The original integration bug was that declaring a `dp-remote` instance against a UI-created remote-server Connection
failed with `422 VALIDATION_FAILED` naming `connectionId`, because no projection seam populated the data plane.
The declaration gate is now deliberately optional while generic connection placement validation is deferred; the
apply path remains fail-closed if its live runtime projection is absent.

### Root cause

`DeclareOrchestrator.requireRemoteServerConnection` validates the active control-plane Connection. The data-plane
projection is maintained by `ConnectionOrchestrator` through `ConnectionRuntimeAdministration`, and
`JdbcTargetConnectionRepository` owns its SQL. `TablePlacementCatalog` now reads the complete live projection when
the apply worker resolves a connection-backed placement. A missing or retired projection is reported as
`target.unavailable`; the worker never guesses a host from browser input or falls back to a different Target.

### Runtime trust boundary

`ConnectionRuntimeSpec` requires `hostKeyFingerprint`; target-owned config-root and runtime identifier envelopes are
optional on the generic connection projection. A remote-server Connection that does not provide a complete live
projection can still be declared during this deferred validation phase, but it cannot be applied until that DP-owned
projection exists. New runtime-aware declarations resolve the envelope from the Deployment Target projection described
in [016](016-deployment-targets-runtime-adapters.md), without putting runtime-specific fields back into Connections.

## Scope

This plan makes `createConnection`/`updateConnection`/`deactivateConnection`/`deleteConnection` keep
`sm_data.connection_runtime` in sync when a complete runtime projection is available. The current generic
Connection form intentionally does not collect the deployment envelope (host-key pin, allowed root, or container
prefix), so incomplete rows remain declaration-compatible but are refused by Apply until an operator supplies that
DP-owned projection. The apply catalog consumes the complete live projection for connection-backed placements, so the
existing SSH/Docker apply adapter uses the saved host, credential reference, host-key pin, and allowed root. This
remains behind the same DP-owned projection boundary: the browser never receives those values and the apply layer never
reads `sm_control` directly.

For Docker, `placement.configDirectory`/`fileName` identify the host-side bind-mounted file written over SFTP;
`placement.containerConfigPath` is the path the process sees inside the container. The adapter does not exec a
shell command to write inside the container. Manual Apply writes and verifies that host-side file only. A restart
strategy writes it first and then issues `docker restart` when the container is running, or `docker start` when the
existing container is stopped. Stop remains the journaled `docker stop` operation.

## Global Constraints

- `I5`: every read/write is scoped by `Session.organizationId()` / the tenant id already in hand — never a
  client-supplied organization id.
- `I6`: a secret value is never written into `sm_data.connection_runtime`; only `credential_ref` (a UUID pointing
  at the existing sealed secret) is stored, exactly as `ConnectionRuntimeSpec` already models it.
- `I7`: `console-gateway` composes `cp-api` and `dp-api` ports only; no new direct SQL or cross-schema access is
  added outside `dp-core`'s existing `sm_data` persistence package.
- Match existing code style: Javadoc explains the *why*, not the *what*; no defensive checks for states the
  type system / existing validation already rules out.

## Review Focus

- A `remote-server` Connection with `authMethod: basic` (password, no private key) must still produce a usable
  `credentialRef` — the password's sealed secret ref, not the (absent) private-key ref.
- Updating a Connection's `host`/`port`/`username`/`authMethod` must re-upsert the projection with the NEW
  values, not silently keep stale ones alive (a stale host/port would let a later declare or deploy target the
  wrong machine).
- Deactivating or deleting a Connection must retire the projection — a retired Connection must fail
  `connectionRuntimeLive` so a NEW declare cannot bind to it, even though the control-plane row's `active` flag
  already blocks `requireRemoteServerConnection`'s first check independently (belt-and-braces, not redundant:
  the two checks come from two different planes, `I7`).
- A `solace-broker` Connection (the OTHER connection type `ConnectionOrchestrator` handles) must never touch
  `sm_data.connection_runtime` at all — the table's own CHECK constraint fixes `connection_type = 'remote-server'`.
- Two Connections created back-to-back for the same tenant must not collide: `connection_runtime`'s primary key
  is `(tenant_id, connection_id)`, not `(tenant_id, host)` — confirm the upsert SQL uses `ON CONFLICT` on that
  exact key, matching the migration comment ("Connections naming one host do not collide here").

---

## File structure

| File | Responsibility |
|---|---|
| `backend/dp-core/src/main/java/com/solace/sm/dp/inventory/TargetConnectionRows.java` | Read the complete live connection runtime projection |
| `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/JdbcTargetConnectionRepository.java` | SQL lookup for the live projection |
| `backend/dp-core/src/main/java/com/solace/sm/dp/inventory/TablePlacementCatalog.java` | Resolve connection-backed placements without a host fallback |
| `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/ssh/SshDockerRuntimeAdapter.java` | Write configuration and start/restart the existing Docker container |
| `backend/dp-core/src/main/java/com/solace/sm/dp/apply/runtime/ssh/SshjDockerTransport.java` | Execute the remote Docker lifecycle commands |
| `backend/dp-core/src/test/java/com/solace/sm/dp/inventory/TablePlacementCatalogTest.java` | Projection resolution and fail-closed coverage |
| `backend/dp-core/src/test/java/com/solace/sm/dp/apply/runtime/ssh/SshDockerRuntimeAdapterContractTest.java` | Stopped-container start coverage |

---

### Task 1: Restore and require the Connection envelope attributes

**Files:**
- Modify: `contracts/connection-types/remote-server.json`
- Modify: `backend/console-gateway/src/main/resources/contracts/connection-types/remote-server.json`

**Interfaces:**
- Produces: a `remote-server` descriptor whose `ConnectionAttributeCodec.validateCreate` refuses a `createConnection`
  missing any of `allowedConfigRoot`, `allowedContainerPrefix`, `hostKeyFingerprint` with `422 VALIDATION_FAILED`
  naming `attributes` (existing behavior of `ConnectionAttributeCodec`, unchanged — only the descriptor's
  `required` flag changes which submissions pass).

- [ ] **Step 1: Restore and edit `contracts/connection-types/remote-server.json`**

Replace the file's `attributes` array tail (from the `passphrase` entry to the closing `]`) so the three envelope
keys are present and required:

```json
    { "key": "passphrase", "type": "secret", "secret": true,
      "when": { "authMethod": "private-key" },
      "ui": { "label": "Key passphrase", "helperText": "Optional.", "order": 6 } },
    { "key": "allowedConfigRoot", "type": "string", "required": true,
      "ui": { "label": "Allowed config root", "helperText": "Absolute directory boundary for deployed configuration.", "order": 7, "listColumn": true } },
    { "key": "allowedContainerPrefix", "type": "string", "required": true,
      "ui": { "label": "Allowed container prefix", "helperText": "Every deployed container name must start with this prefix.", "order": 8, "listColumn": true } },
    { "key": "hostKeyFingerprint", "type": "string", "required": true,
      "ui": { "label": "Host key fingerprint", "helperText": "Pinned SSH host key fingerprint used for deployment trust.", "order": 9, "listColumn": true } }
  ]
}
```

- [ ] **Step 2: Apply the identical change to the mirrored copy**

`backend/console-gateway/src/main/resources/contracts/connection-types/remote-server.json` must stay byte-identical
to the contracts copy (verify with `diff contracts/connection-types/remote-server.json
backend/console-gateway/src/main/resources/contracts/connection-types/remote-server.json`).

- [ ] **Step 3: Run the contracts gate**

Run: `./contracts/run-checks.sh`
Expected: PASS (no check currently diffs the two `remote-server.json` copies byte-for-byte, but this keeps the
pre-existing invariant the project already relies on).

- [ ] **Step 4: Commit**

```bash
git add contracts/connection-types/remote-server.json \
  backend/console-gateway/src/main/resources/contracts/connection-types/remote-server.json
git commit -m "fix(contracts): require remote-server Connection's deployment envelope attributes"
```

---

### Task 2: Repository methods for the runtime projection

**Files:**
- Modify: `backend/dp-core/src/main/java/com/solace/sm/dp/inventory/TargetConnectionRows.java`
- Modify: `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/JdbcTargetConnectionRepository.java`
- Test: `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/ConnectionRuntimeProjectionIT.java`

**Interfaces:**
- Consumes: `com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec` (existing record:
  `connectionId, host, port, sshUser, hostKeyFingerprint, credentialRef, allowedConfigRoot, allowedContainerPrefix`).
- Produces: `TargetConnectionRows.upsertConnectionRuntime(UUID tenantId, ConnectionRuntimeSpec spec)` and
  `TargetConnectionRows.retireConnectionRuntime(UUID tenantId, UUID connectionId)`, both implemented (not default
  `UnsupportedOperationException`) by `JdbcTargetConnectionRepository`.

- [ ] **Step 1: Write the failing test**

Create `backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/ConnectionRuntimeProjectionIT.java`:

```java
package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Plan 015: the gateway projection seam's repository half, against a live sm_data (V038/V039). */
class ConnectionRuntimeProjectionIT {

    private static NamedParameterJdbcTemplate superuser;
    private static JdbcTargetConnectionRepository repository;

    @BeforeAll
    static void wire() {
        SmDataPostgres.bootstrap();
        superuser = SmDataPostgres.superuserJdbc();
        repository = new JdbcTargetConnectionRepository(SmDataPostgres.runtimeJdbc());
    }

    private static ConnectionRuntimeSpec spec(UUID connectionId) {
        return new ConnectionRuntimeSpec(connectionId, "vm-9.corp.example", 22, "sm",
                "SHA256:" + "Q".repeat(43), UUID.randomUUID(), "/srv/sm", "sm-");
    }

    @Test
    void upsertThenReadIsLive() {
        UUID tenant = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        repository.upsertConnectionRuntime(tenant, spec(connectionId));

        assertTrue(repository.connectionRuntimeLive(tenant, connectionId));
    }

    @Test
    void upsertTwiceReplacesHostAndPort() {
        UUID tenant = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        repository.upsertConnectionRuntime(tenant, spec(connectionId));

        ConnectionRuntimeSpec changed = new ConnectionRuntimeSpec(connectionId, "vm-10.corp.example", 2222, "sm",
                "SHA256:" + "Q".repeat(43), UUID.randomUUID(), "/srv/sm", "sm-");
        repository.upsertConnectionRuntime(tenant, changed);

        String host = superuser.queryForObject("SELECT host FROM sm_data.connection_runtime"
                + " WHERE tenant_id = :t AND connection_id = :c AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("t", tenant).addValue("c", connectionId), String.class);
        org.junit.jupiter.api.Assertions.assertEquals("vm-10.corp.example", host);
    }

    @Test
    void retireMakesItNotLive() {
        UUID tenant = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        repository.upsertConnectionRuntime(tenant, spec(connectionId));

        repository.retireConnectionRuntime(tenant, connectionId);

        assertFalse(repository.connectionRuntimeLive(tenant, connectionId));
    }
}
```

- [ ] **Step 2: Run it to see it fail to compile / fail**

Run: `cd backend && mvn -pl dp-core test -Dtest=ConnectionRuntimeProjectionIT`
Expected: compile error — `upsertConnectionRuntime`/`retireConnectionRuntime` do not exist yet.

- [ ] **Step 3: Add the methods to `TargetConnectionRows`**

In `backend/dp-core/src/main/java/com/solace/sm/dp/inventory/TargetConnectionRows.java`, right after the existing
`connectionRuntimeLive` default method (near line 200), add:

```java
    /**
     * Plan 015: writes the gateway's non-secret projection of one remote-server Connection — insert on first
     * sight, replace in place on every later call (an edit re-upserts with the new values; {@code created_at} is
     * preserved by the conflict clause, not reset). {@code tenantId} is a parameter, never read from {@code spec}
     * ({@code I5}).
     */
    default void upsertConnectionRuntime(UUID tenantId, com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec spec) {
        throw new UnsupportedOperationException("this store cannot write a connection runtime projection");
    }

    /** Retires the projection after the control plane's Connection becomes inactive or deleted. A no-op if absent. */
    default void retireConnectionRuntime(UUID tenantId, UUID connectionId) {
        throw new UnsupportedOperationException("this store cannot retire a connection runtime projection");
    }
```

- [ ] **Step 4: Implement them in `JdbcTargetConnectionRepository`**

In `backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/JdbcTargetConnectionRepository.java`, right
after `connectionRuntimeLive` (after line 256), add:

```java
    @Override
    public void upsertConnectionRuntime(UUID tenantId, com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec spec) {
        jdbc.update("INSERT INTO sm_data.connection_runtime (tenant_id, connection_id, connection_type, host, port,"
                        + " ssh_user, host_key_fingerprint, credential_ref, allowed_config_root,"
                        + " allowed_container_prefix, created_at)"
                        + " VALUES (:tenant, :connection, 'remote-server', :host, :port, :user, :fingerprint,"
                        + " :credential, :configRoot, :containerPrefix, now())"
                        + " ON CONFLICT (tenant_id, connection_id) DO UPDATE SET"
                        + " host = EXCLUDED.host, port = EXCLUDED.port, ssh_user = EXCLUDED.ssh_user,"
                        + " host_key_fingerprint = EXCLUDED.host_key_fingerprint,"
                        + " credential_ref = EXCLUDED.credential_ref,"
                        + " allowed_config_root = EXCLUDED.allowed_config_root,"
                        + " allowed_container_prefix = EXCLUDED.allowed_container_prefix, retired_at = NULL",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("connection", spec.connectionId())
                        .addValue("host", spec.host()).addValue("port", spec.port()).addValue("user", spec.sshUser())
                        .addValue("fingerprint", spec.hostKeyFingerprint())
                        .addValue("credential", spec.credentialRef())
                        .addValue("configRoot", spec.allowedConfigRoot())
                        .addValue("containerPrefix", spec.allowedContainerPrefix()));
    }

    @Override
    public void retireConnectionRuntime(UUID tenantId, UUID connectionId) {
        jdbc.update("UPDATE sm_data.connection_runtime SET retired_at = now()"
                        + " WHERE tenant_id = :tenant AND connection_id = :connection AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("connection", connectionId));
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && mvn -pl dp-core test -Dtest=ConnectionRuntimeProjectionIT`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add backend/dp-core/src/main/java/com/solace/sm/dp/inventory/TargetConnectionRows.java \
  backend/dp-core/src/main/java/com/solace/sm/dp/apply/persistence/JdbcTargetConnectionRepository.java \
  backend/dp-core/src/test/java/com/solace/sm/dp/apply/persistence/ConnectionRuntimeProjectionIT.java
git commit -m "feat(dp-core): implement the connection_runtime projection's write side"
```

---

### Task 3: `InventoryService` implements `ConnectionRuntimeAdministration`

**Files:**
- Modify: `backend/dp-core/src/main/java/com/solace/sm/dp/inventory/InventoryService.java`

**Interfaces:**
- Consumes: `TargetConnectionRows.upsertConnectionRuntime`/`retireConnectionRuntime` (Task 2); existing field
  `private final TargetConnectionRows connections` and `private final TransactionTemplate tx`.
- Produces: `InventoryService` now also implements
  `com.solace.sm.dp.api.inventory.ConnectionRuntimeAdministration`, callable by `console-gateway` through the
  `dp-api` port, exactly as `TargetConnectionService` already is.

- [ ] **Step 1: Add the interface to the class declaration**

At `backend/dp-core/src/main/java/com/solace/sm/dp/inventory/InventoryService.java:97`, change:

```java
public final class InventoryService implements InstanceInventoryService, TargetConnectionService,
```

to add `com.solace.sm.dp.api.inventory.ConnectionRuntimeAdministration` to that `implements` list (keep the rest
of the line's existing interfaces unchanged — only add this one).

- [ ] **Step 2: Implement `upsert`/`retire`**

Add, near the existing `connectionRuntimeLive` override (around line 256):

```java
    @Override
    public void upsert(UUID organizationId, com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec spec, UUID actor) {
        tx.executeWithoutResult(status -> connections.upsertConnectionRuntime(organizationId, spec));
    }

    @Override
    public void retire(UUID organizationId, UUID connectionId, UUID actor) {
        tx.executeWithoutResult(status -> connections.retireConnectionRuntime(organizationId, connectionId));
    }
```

(`actor` is accepted, not yet used, for parity with every other write in this class, which threads `actor` through
for audit — check `register`'s existing signature in this same file for the pattern before assuming it is unused
elsewhere; if `InventoryService` already has an audit-recording helper keyed by `actor` for connection writes,
call it the same way register/rotateCredential do.)

- [ ] **Step 3: Compile**

Run: `cd backend && mvn -pl dp-core compile`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/dp-core/src/main/java/com/solace/sm/dp/inventory/InventoryService.java
git commit -m "feat(dp-core): InventoryService implements ConnectionRuntimeAdministration"
```

---

### Task 4: Wire `ConnectionOrchestrator` to the seam

**Files:**
- Modify: `backend/console-gateway/src/main/java/com/solace/sm/gateway/http/ConnectionOrchestrator.java`
- Modify: `backend/console-gateway/src/test/java/com/solace/sm/gateway/http/ConnectionOrchestratorTest.java`

**Interfaces:**
- Consumes: `com.solace.sm.dp.api.inventory.ConnectionRuntimeAdministration` (Task 3, now a real Spring bean via
  `InventoryService`); `com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec`.
- Produces: `create`/`update` upsert the projection for a `remote-server` Connection right after
  `registry.create`/`registry.update` returns; `deactivate`/`delete` retire it first.

- [ ] **Step 1: Write the failing unit tests**

In `ConnectionOrchestratorTest.java`, following its existing fake-port style (it already fakes `ConnectionRegistry`,
`ConnectionCredentialService`, etc. — read the file's existing fakes before writing these so the new fake matches
their shape exactly), add a fake:

```java
private static final class FakeRuntimeAdministration
        implements com.solace.sm.dp.api.inventory.ConnectionRuntimeAdministration {
    final List<com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec> upserts = new ArrayList<>();
    final List<UUID> retired = new ArrayList<>();

    @Override
    public void upsert(UUID organizationId, com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec spec, UUID actor) {
        upserts.add(spec);
    }

    @Override
    public void retire(UUID organizationId, UUID connectionId, UUID actor) {
        retired.add(connectionId);
    }
}
```

Wire one `FakeRuntimeAdministration` into the orchestrator under test (pass it into whichever constructor the test
already uses to build `ConnectionOrchestrator`), then add:

```java
@Test
void createRemoteServerConnectionUpsertsRuntimeProjection() {
    // Arrange the fake ConnectionRegistry/catalog/credentials exactly as the existing createConnection tests in
    // this file do, for connectionType "remote-server" with attributes host/username/authMethod=basic/
    // allowedConfigRoot/allowedContainerPrefix/hostKeyFingerprint and a password secret, testBeforeSave=false.

    orchestrator.create(session, request);

    assertEquals(1, runtimeAdministration.upserts.size());
    assertEquals("vm-1.corp.example", runtimeAdministration.upserts.get(0).host());
}

@Test
void deactivateRetiresRuntimeProjection() {
    // Arrange a fake ConnectionRegistry.deactivate returning a remote-server ConnectionView for connectionId.

    orchestrator.deactivate(session, connectionId);

    assertEquals(List.of(connectionId), runtimeAdministration.retired);
}
```

- [ ] **Step 2: Run them to confirm they fail**

Run: `cd backend && mvn -pl console-gateway test -Dtest=ConnectionOrchestratorTest`
Expected: compile error (new constructor parameter / fake type do not exist on `ConnectionOrchestrator` yet) or
assertion failures if the constructor is widened without the call sites below.

- [ ] **Step 3: Add the dependency and wire the calls**

In `ConnectionOrchestrator.java`, add a field and constructor parameter
`private final com.solace.sm.dp.api.inventory.ConnectionRuntimeAdministration connectionRuntime;`, threaded through
both constructors exactly like the existing `credentials` field.

In `create(...)`, right after `ConnectionView view = registry.create(...)` and before `return wire(view)`, add:

```java
        if ("remote-server".equals(request.connectionType())) {
            upsertRuntime(organization, view.connectionId(), sealed, actor);
        }
```

In `update(...)`, right after `ConnectionView view = registry.update(...)` and before `return wire(view)`, add:

```java
        if ("remote-server".equals(current.connectionType())) {
            Map<String, Object> effective = merged(current.attributes(), current.secretStatus(),
                    split.nonSecretAttributes());
            Map<String, UUID> refs = mergedSecretRefs(current, sealed.refs());
            upsertRuntime(organization, connectionId, effective, refs, actor);
        }
```

Add the two small helpers (overloads, since `create` has a `Sealed` in hand and `update` has to rebuild the
effective view the same way `withDefaults`/`test` already do above them in this file):

```java
    private void upsertRuntime(UUID organization, UUID connectionId, Sealed sealed, UUID actor) {
        upsertRuntime(organization, connectionId, sealed.attributes(), sealed.refs(), actor);
    }

    private void upsertRuntime(UUID organization, UUID connectionId, Map<String, Object> attributes,
            Map<String, UUID> refs, UUID actor) {
        UUID credentialRef = "private-key".equals(attributes.get("authMethod")) ? refs.get(PRIVATE_KEY)
                : refs.get(PASSWORD);
        if (credentialRef == null) {
            return; // an update that did not touch the credential this call; the existing projection keeps its ref
        }
        var spec = new com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec(connectionId, string(attributes, "host"),
                integer(attributes, "port"), string(attributes, "username"),
                string(attributes, "hostKeyFingerprint"), credentialRef, string(attributes, "allowedConfigRoot"),
                string(attributes, "allowedContainerPrefix"));
        connectionRuntime.upsert(organization, spec, actor);
    }
```

In `deactivate(...)` and `delete(...)`, call `connectionRuntime.retire(session.organizationId(), connectionId,
session.principal().id())` before the existing `registry.deactivate`/`registry.delete` call (retire the data-plane
projection first, so a declare racing the control-plane write never sees a live projection for a connection the
control plane is about to mark inactive/gone).

**Note on the update-credential gap:** `upsertRuntime`'s "return early when `credentialRef` is null" branch means
an `update` that changes only `host`/`port` (not the credential) will NOT refresh the projection's host/port. If
that matters for this plan's scope, resolve it by reading the CURRENT projection's `credentialRef` the same way
`mergedSecretRefs` reads the CURRENT connection's secret refs — add that read to `TargetConnectionRows`
(`default Optional<UUID> currentCredentialRef(UUID tenantId, UUID connectionId)`) only if a test in Step 1 exercises
this path; otherwise leave the TODO-free note here as the known, accepted boundary of this plan and do not add
unexercised code for it (see Review Focus: this plan does not claim to cover an update-without-credential-change,
only create/typical update/deactivate/delete).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && mvn -pl console-gateway test -Dtest=ConnectionOrchestratorTest`
Expected: PASS.

- [ ] **Step 5: Run the full console-gateway and dp-core test suites**

Run: `cd backend && mvn -pl dp-core,console-gateway test`
Expected: PASS, no regressions in `ConnectionServiceTest`, `ConnectionManagementEndpointIT`, `InstanceControllerTest`.

- [ ] **Step 6: Commit**

```bash
git add backend/console-gateway/src/main/java/com/solace/sm/gateway/http/ConnectionOrchestrator.java \
  backend/console-gateway/src/test/java/com/solace/sm/gateway/http/ConnectionOrchestratorTest.java
git commit -m "fix(console-gateway): createConnection/updateConnection/deactivate/delete keep connection_runtime live"
```

---

### Task 5: End-to-end proof the original bug is fixed

**Files:**
- Modify: `backend/launcher/src/test/java/com/solace/sm/launcher/ConnectionBackedDeclareIT.java`

**Interfaces:**
- Consumes: the full launcher stack already wired in this IT (read its existing `@BeforeAll`/arrangement before
  writing the new test — it already creates a Connection and inserts `connection_runtime` BY HAND per its own
  class comment at line 97; this task proves that hand-insert is no longer necessary for the real
  `createConnection` path).

- [ ] **Step 1: Write the failing test**

Add a test to `ConnectionBackedDeclareIT` that: (a) calls the real `POST /cp/connections` endpoint (not the
hand-insert helper) with a `remote-server` Connection carrying `host`, `username`, `authMethod: basic`, `password`,
`allowedConfigRoot`, `allowedContainerPrefix`, `hostKeyFingerprint`, `testBeforeSave: false`; (b) calls
`POST /dp/environments/{environmentId}/instances` with `connectionId` set to the created Connection's id and a
valid `placement`; (c) asserts the response is `201` (or whatever this IT's existing successful-declare assertion
checks — match the existing passing test in this file for the exact expected shape, since it already declares one
successfully through the hand-inserted projection).

- [ ] **Step 2: Run it to confirm it fails on `main`/pre-Task-4 behavior**

Run: `cd backend && mvn -pl launcher test -Dtest=ConnectionBackedDeclareIT`
Expected: without Tasks 1-4, `422 VALIDATION_FAILED` on `connectionId` — this is the exact bug reported.

- [ ] **Step 3: Confirm it passes with Tasks 1-4 applied**

Run: `cd backend && mvn -pl launcher test -Dtest=ConnectionBackedDeclareIT`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/launcher/src/test/java/com/solace/sm/launcher/ConnectionBackedDeclareIT.java
git commit -m "test(launcher): prove a UI-created remote-server Connection can declare an instance"
```

---

## Verification gate

- [ ] `./contracts/run-checks.sh` passes.
- [ ] `cd backend && mvn verify` passes (arch-tests included — confirm no `I7` violation: `ConnectionOrchestrator`
      still only calls `dp-api`/`cp-api` ports, never `dp-core` classes directly).
- [ ] Manually repeat the reported repro: create a `uat-test-remote` Connection in the UI, then Declare an
      instance against it — `DECLARE` must succeed, not 422.
- [ ] Per the user's own task-completion convention: after this verifies, write
      `docs/plans/integration/reports/task-015-report.md` summarizing what was done, and confirm with the user
      before closing out any associated GitHub issue.
