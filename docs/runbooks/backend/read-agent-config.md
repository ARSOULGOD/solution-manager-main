# Read an agent placement's configuration

| | |
|---|---|
| **Applies to** | the Data Plane, agent gateway and console gateway (`backend/dp-core`, `backend/agent-gateway`, `backend/console-gateway`) serving a `host-local` agent (plan 017). The agent side is in [`go-agent/RUNBOOK.md`](../../../go-agent/RUNBOOK.md#config-view). |
| **Severity** | routine |
| **Est. duration** | minutes |
| **Reversible?** | Yes; reading changes nothing. |
| **Status** | **`UNTESTED` end to end.** Each piece ran under Go tests, `fake-gateway` and Docker Maven; no real agent, gateway and container were run together, and the launcher integration tests cannot load (see [Known limits](#known-limits)). |

## When to use

An operator wants to see the allow-listed settings of the YAML config file that a placement's agent reads, or a view returned
an error. `GET /api/dp/instances/{instanceId}/placements/{placementId}/config` (`viewConfig`). Agent placements only; SSH
placements are not supported (`501`).

## Preconditions

- The principal holds `config.view` at instance scope. **There is no new verb**: it is the existing verb that `getDesigner`
  and `getConfigHistory` already use, and it belongs to the Viewer, Operator and Administrator built-in roles. So **a Viewer
  sees the allow-listed values**. The allow list is the control; keep it narrow.
- The placement is live (not retired), belongs to that instance, and its target is served by a connected agent that advertises
  the config-view capability. An agent built before plan 017 does not; the gateway then sends nothing and the view is
  unavailable (`503`).

## Properties

Data Plane (`sm.dp.config-view.*`, `ConfigViewConfiguration`):

| Property | Default | Meaning |
|---|---|---|
| `allow` | `server.port,management.*,logging.*,info.*` | The allow list: comma-separated, 1 to 64 entries. |
| `timeout` | `10s` (must be > 0 and at most `1m`) | How long the DP waits for the agent. |
| `max-bytes` | `262144` (1024 to 4194304) | The most the agent may read and return. |

The DP's concurrency limit is a constant, `MAX_CONCURRENT_VIEWS = 8` (not a property): a ninth view in flight is `429`.

Gateway: `sm.agent.gateway.config-view-deadline` (default `10s`, between 100ms and 5m), the gateway's own deadline for the
agent's reply. The gateway also holds one view in flight per agent; a second is refused as busy.

An invalid property fails startup. The message names the entry's position, never its text.

## The allow list

- **Owned by the DP and sent with every request.** The agent filters by it and the DP re-checks the reply; if one returned path
  is off the list the DP discards the **whole** reply (`502`). The agent bounds the list it accepts (256 patterns, 16 KiB
  total) and refuses a larger one.
- **Entry forms.** An exact path (`server.port`) or a prefix wildcard ending in `.*` (`management.*`, which matches strictly
  below `management.`, not `management` itself).
- **Rejected at startup:** an empty list, more than 64 entries, a blank or duplicate entry, an entry over 256 characters,
  any character other than ASCII letters, digits, `-`, `_` and `.` (so list-index brackets are refused), a `*` anywhere but a
  final `.*`, and an empty segment (`.a`, `a.`, `a..b`).
- **Changing it:** set `sm.dp.config-view.allow` and restart the Data Plane. Nothing is stored; the agent is not touched.
- **Lists.** A list value flattens to indexed paths (`a.b[0]`). An exact entry `a.b` does not match `a.b[0]`; use `a.b.*`
  through the parent's prefix, for example `management.*` also matches `management.x[0]`.

## Response

200 body: `{entries:[{path,value}], hiddenCount, truncated}`.

| Status | `code` | Meaning | Operator action |
|---|---|---|---|
| `200` | | The view. | |
| `400` | `BAD_REQUEST` | A path id is not a UUID. | Fix the request. |
| `403` | `FORBIDDEN` | No `config.view` at the instance. | Grant it. |
| `404` | `NOT_FOUND` | **One shared body** for an unknown, other-organization, other-instance or retired placement, and for a missing config file. | Fix the id; if it is right, check the file on the host. The caller cannot tell the cases apart on purpose. |
| `413` | `CONFIG_TOO_LARGE` | The file, its nesting, its expanded size or the filtered output exceeds the limits (the agent's `FILE_TOO_LARGE` or `TOO_MANY_KEYS`). | Shrink the file or raise `max-bytes` (agent hard cap is 1 MiB). |
| `422` | `CONFIG_INVALID_YAML` | The file does not parse, or has a cyclic alias. | Fix the file. |
| `429` | `RATE_LIMITED`, `reason: config-view-concurrency`, `Retry-After: 5` | The DP's 8 views, or the gateway's one per agent, are in flight. | Wait and retry. |
| `501` | `AGENT_OPERATION_UNSUPPORTED` | The placement is on an SSH target. The title reads inverted; it means the *agent* operation does not apply. | None; not supported yet. |
| `502` | `AGENT_REPLY_REJECTED` | The DP's re-check failed (a path off the allow list), the reply was malformed, or the agent refused with `FILE_UNREADABLE`, `NOT_PERMITTED` or a reason the DP does not know. Fixed text. | If the file exists and is readable, check the agent's permissions; otherwise report it: an agent or gateway may be misbehaving. |
| `503` | `SERVICE_UNAVAILABLE` | No agent bound, not connected, no config-view capability, silent past the deadline, session ended, or the placement left the agent's lease. | Check `listAgents`, then retry. |

**Busy is not always `429`.** The agent refuses above its own bound of 4 concurrent reads with `FILE_UNREADABLE`; the wire has
no busy reason, so the DP cannot tell busy from unreadable on that path. It surfaces as `502` `AGENT_REPLY_REJECTED`, the same as an unreadable
file, not as `429`.

Any reason text the DP does not know maps to `UNKNOWN`, which is a `502`, not a view.

## `hiddenCount`, `truncated` and missing keys

- `hiddenCount` counts **every scalar leaf the reply did not emit**: not on the allow list, or allow-listed but dropped by a
  limit. It carries no names.
- `truncated` is true when allow-listed entries were dropped at the agent's cap (2000 keys, or the byte budget).
- **A key may be missing for three reasons:** it is not on the list, the file is missing (that is a `404`, not a view), or the
  filter's limits cut it (`truncated`).
- A null scalar shows its raw text. Only the first YAML document is read.

## I6 guarantees and limits

- **Guarantee:** a secret under a key that is not on the allow list is hidden and only counted. Content, keys and paths are
  never logged by the agent, gateway, DP or console (the controller logs nothing; types print redacted).
- **Limit:** a secret under a **listed** prefix is shown. The allow list is the operator's to own; do not list a prefix that
  may hold secrets. `secretRef` values are references, but a literal secret pasted under a listed key is visible.
- **YAML corner cases fail closed:** merge keys (`<<`) appear as a literal path segment, so merged content is hidden unless
  a pattern matches that literal path; an aliased or non-scalar mapping key hides its subtree; dotted keys (`a.b: 1`)
  flatten to the same path as nested ones (`a: {b: 1}`), so they cannot be told apart.
- The agent refuses a symlink, a FIFO or any path that escapes the placement's directory (`NOT_PERMITTED`, shown as `502`;
  the host path never appears in any body).

## Known limits

- **No audit row for reads** (spec open item 3; owner call, see `DECISIONS.md`).
- **Agent limits (fixed in the agent):** hard cap 1 MiB, default 256 KiB, nesting depth 64, 100000 expanded nodes, 2000
  emitted keys, 4 concurrent reads. A nearer limit (the DP's `max-bytes`, 256 KiB) usually applies first.
- **SSH placements** are not supported; they are planned through the same port.
- **Launcher integration tests cannot load** (`sm_control` migration `V028` drops `'agent'` from the transport check), so
  `ProductionAuthenticationIT`'s edit for `viewConfig` is verified by reading only.
- **Unmeasured:** a real agent, gateway and container together.

## Verification

Call `viewConfig` for a live agent placement whose file holds `server.port`: expect `200` with that entry and a
`hiddenCount` for every other leaf. Call it for a retired placement and for another organization's: expect the same `404`
body. Run nine concurrent views: expect a `429` with `Retry-After: 5`.

## Rollback

Nothing to undo. To stop serving views, revoke `config.view`; the agent has no role flag for it (it answers whenever it has an adapter).

## Escalation

Project owner, for the audit-row and Viewer-visibility questions; the agent-gateway owner for a view that always times out.
