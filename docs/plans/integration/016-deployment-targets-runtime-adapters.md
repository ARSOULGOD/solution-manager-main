# Deployment targets and runtime adapters

Status: implemented; live remote E2E pending test infrastructure, 2026-10-12.

This plan records the target model that follows the Connections work. A Connection answers “how can Solution
Manager authenticate to this remote server?” A Deployment Target answers “which runtime on that server may this
instance operate?” The relationship is:

```text
Instance -> Deployment Target -> remote-server Connection
```

The browser never receives or resubmits the SSH private key/password. The backend resolves the parent Connection's
sealed credential reference and pinned host-key fingerprint when the data plane performs an operation.

## Model and persistence

Legacy `Target` rows remain supported. New target-managed rows use:

- `sm_control.target.connection_id`, `runtime` (`docker` or `systemd`) and non-secret `runtime_settings`.
- `sm_data.deployment_target_runtime`, keyed by tenant and target, containing the parent connection id, runtime and
  the same non-secret settings for the data-plane adapter.
- `sm_data.connection_runtime` remains the SSH projection. Runtime-specific envelope values are target-owned; the
  connection projection may therefore have null `allowed_config_root`/`allowed_container_prefix`.

Migrations:

- `sm_control` V028 adds the linked-target columns and runtime vocabulary.
- `sm_control` V029 grants the runtime role the narrow metadata-update columns used by target editing.
- `sm_data` V047 adds the target-runtime projection.
- `sm_data` V048 removes the old connection-level envelope requirement.
- `sm_data` V049 grants the runtime role the narrow projection-update columns used by target editing.
- `sm_data` V050 makes placement admission accept either a legacy `target_connection` or a live linked target
  (`deployment_target_runtime` plus its parent `connection_runtime`) while retaining `placement.target_id`.

All migrations are additive/compatibility-preserving for existing target-backed instances. Apply the normal Phase 0
and migration runners; do not edit an already-applied checksum.

## API and UI flow

Connections contain only host, SSH port, username, authentication/secret fields and the pinned host-key fingerprint.
The Connections screen owns “Test SSH Connection”. Runtime fields do not belong there.

`POST /api/cp/targets` supports the linked form:

```json
{
  "name": "File Connector Docker",
  "kind": "docker",
  "transport": "ssh",
  "ownershipScope": { "subOrganizationId": "…", "environmentId": "…" },
  "connectionId": "…",
  "runtime": "docker",
  "configRoot": "/home/ubuntu",
  "containerPrefix": "fm-"
}
```

For systemd use `kind: "systemd"` and `serviceNamePattern` instead of `containerPrefix`:

```json
{
  "name": "File Connector Service",
  "kind": "systemd",
  "transport": "ssh",
  "connectionId": "…",
  "runtime": "systemd",
  "configRoot": "/etc/file-connector",
  "serviceNamePattern": "file-connector-"
}
```

The Declare Instance dialog loads targets and displays `Instance -> Target`; legacy clients can still use the
connection-backed declaration shape. Docker asks for container name, host config directory, container config path,
file name and health port. systemd asks for service/unit name, host config directory, file name and health port; the
existing placement contract carries the host directory as its compatibility config path because the systemd adapter
does not write inside a container.

The Connections screen renders the Deployment Targets section only while the Remote Server tab is selected. It
supports create, list, edit and safe-delete (retire). Editing preserves the parent remote Connection and runtime;
deleting calls the existing retire workflow, so live instance references remain protected and the table refreshes only
after the server confirms the change.

The backend repeats the target envelope checks. Docker names must start with `containerPrefix`; systemd unit names
must satisfy `serviceNamePattern` (a trailing `-` is treated as a prefix, otherwise it is a regular expression), and
the host directory must equal or be below `configRoot` without `..` traversal.

## Apply and runtime behavior

The existing Apply engine remains the single workflow:

```text
instance -> placement -> target runtime metadata -> parent connection projection
         -> pinned SSH/SFTP write -> selected strategy -> status/health observation
```

The SSH transport uses a closed command set and bounded timeouts. It writes the host-side configuration atomically,
reads it back, and only then executes lifecycle commands:

- Docker: `docker inspect`, `docker start`, `docker restart`, `docker stop`, and bounded `docker logs`.
- systemd: `systemctl show`, `systemctl start`, `systemctl restart`, `systemctl stop`, and bounded `journalctl`.

Manual strategy materializes configuration and does not restart. A restart/start strategy acts only after a successful
write. The existing apply journal, per-instance locking and health gate continue to prevent overlapping operations and
make failures/retries observable. A missing target projection, missing runtime resource, SSH failure, rejected path,
or failed lifecycle command is fail-closed and is not reported as success.

The target references an existing Docker container or systemd unit. Image/container creation and service installation
remain outside this change. For Docker, the declared host file must be reachable by the container's existing mount;
the adapter cannot repair an incompatible mount. For systemd, the unit must already read the declared host file.

## Security and lifecycle rules

- Host-key verification is pinned from the Connection and is checked on every SSH operation.
- Secrets are sealed in the existing secret store and represented only by a credential reference in data-plane rows.
- Target settings are non-secret JSON; they never contain passwords/private keys.
- Target/connection references are tenant-scoped and authorization is checked before writes.
- A connection or target with live instance placements must remain protected by the existing retirement guards.
- Remote commands are constructed only from grammar-validated identifiers; arbitrary shell text is not accepted.

## Verification performed

- Clean Maven compile of `console-gateway` and `dp-core` with their dependencies: passed.
- Focused DP runtime/catalog tests: 56/56 passed, including Docker and systemd command paths.
- Frontend target repository, connections page, declare-form and dialog tests: 69/69 passed.
- Frontend production build and OpenAPI validator: passed.
- Full backend verification and real remote Docker/systemd deployment were not run in this environment because no
  approved test SSH host/runtime was available and the repository's full suite has existing local-socket/JDK/Testcontainers
  environment failures. Do not treat unit/compile results as a remote deployment result.

## Remaining follow-up

- Add dedicated contract/integration tests for linked target CRUD, systemd command parsing, service health probes and
  reference-protection errors.
- Add a browser E2E fixture backed by a disposable SSH host with both an existing Docker container and systemd unit.
- Add dedicated browser E2E coverage for target edit/retire and a disposable SSH host with both an existing Docker
  container and systemd unit. The UI/API CRUD path is implemented; live remote runtime verification remains pending
  approved test infrastructure.
