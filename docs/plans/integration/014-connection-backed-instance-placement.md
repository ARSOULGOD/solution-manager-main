# Connection-backed instance placement — Integration Plan 014

## Goal

Make a remote-server Connection the deployment binding for newly declared instances. The Declare Instance
flow selects a deployment mode and, for `dp-remote`, stores the selected remote-server `connectionId`.
The connection owns only the SSH coordinates and credential. Deployment-specific placement settings are supplied
by Declare Instance and validated before they are persisted or executed.

This plan supersedes the target-picker portion of Plan 007 and the “Target domain remains the placement
dependency” statement in Plan 013. Plan 013 remains the source of truth for Connections CRUD and secret
handling.

The later target architecture is documented in [016](016-deployment-targets-runtime-adapters.md). New runtime-aware
work should use `Instance -> Deployment Target -> Connection`; the direct `connectionId` declaration shape remains
for compatibility with clients created during this plan.

## Wire model

New declarations use:

```json
{
  "deploymentMode": "dp-remote",
  "connectionId": "<remote-server-connection-id>",
  "placement": {
    "containerName": "sm-file-mesh-01",
    "configDirectory": "/opt/sm/config",
    "containerConfigPath": "/app/config/application.yml",
    "fileName": "application.yml",
    "healthPort": 8080,
    "hostKeyFingerprint": "SHA256:<pinned-host-key>"
  }
}
```

The deployment-mode vocabulary is `dp-remote`, `host-local`, and `federated`, but this implementation enables
only `dp-remote`. New declarations must select a `remote-server` Connection and persist its `connectionId`.
`host-local` (agent on the runtime host) and `federated` (agent-managed external runtime) are reserved for
future work and must not be selectable or accepted as active deployment modes yet.

For `dp-remote`, the selected Connection is the sole source of the remote host and credential at deploy/health/metrics
time; the browser never submits an SSH host or credential. The browser may submit the deployment-specific
container/configuration paths and the pinned host-key fingerprint as part of the placement. The existing acquisition choice (`direct-http` or
`via-ssh`) remains a separate per-instance choice for how health/runtime information is reached.

## Deployment fields

The `remote-server` Connection descriptor contains only connection fields: host, port, username, authentication
method, and credentials. It does not contain deployment envelope fields.

Declare Instance owns these deployment fields:

- `containerName`
- `configDirectory`
- `containerConfigPath`
- `healthPort`
- `hostKeyFingerprint`

The backend validates their syntax, canonical paths, and SSH fingerprint before creating the placement. They must not
be copied into the Connection CRUD form or treated as Connection attributes.

## Plane boundary

Connections are owned by `sm_control.connection`, while the Data Plane currently resolves live deployment hosts
from `sm_data.target_connection`. The implementation must not make `dp-core` read `sm_control` tables directly.
The gateway/registration seam will therefore create or update a DP-owned connection-backed runtime projection,
keyed by `(tenant_id, connection_id)`, containing only the host coordinates, credential reference, and immutable
deployment envelope required by the runtime. Secret material remains in the existing envelope store. Legacy
`target_connection` rows and placement foreign keys remain intact for existing instances.

The additive projection schema is introduced by `sm_data` migration
`V038__connection_runtime_projection.sql`. It is deliberately not populated until the gateway projection seam
is implemented; a missing live projection must fail closed for a connection-backed declaration rather than fall
back to a Target or accept browser-supplied host data.

## Compatibility rules

- Existing instances with `targetId` remain readable and deployable.
- Existing Target APIs remain available for migration and older clients.
- New declarations use `connectionId`; `targetId` is not required for new `dp-remote` declarations.
- New declarations use `deploymentMode: dp-remote`; `host-local` and `federated` are rejected until their agent
  contracts are implemented.
- Existing `direct-http`/`via-ssh` acquisition data remains readable while the deployment-mode migration lands.
- A connection cannot be deleted while referenced by an active placement.
- Permissions and scope checks remain equivalent to the current `instance.bindTarget`/`target.use` checks,
  mapped to the selected Connection resource.

## Implementation order

1. Extend the canonical remote-server descriptor and Connection view/list shape with the envelope.
2. Add declaration-time deployment fields and the instance/placement connection reference and migration, retaining legacy target references.
3. Resolve deployment, health, and metrics host/credential data through the connection-backed placement path.
4. Update the Declare Instance UI to collect and validate the deployment fields.
5. Add contract, backend, migration, frontend, and browser tests; update API inventory and reports.

## Verification gate

The change is complete only when existing Target and Instance tests remain green, new connection-backed declare
tests pass, migration checks pass on a clean local database, and the browser can declare a `dp-remote` instance
using a remote-server Connection without exposing or duplicating its credential.
