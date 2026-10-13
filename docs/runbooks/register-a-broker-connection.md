# Register a broker connection (broker-pushed business metrics)

| | |
|---|---|
| **Applies to** | Data Plane, plan 011 P11-5.1 (`BrokerConnectionMain`; `OR-107`, `OR-112` (2)) |
| **Severity** | routine |
| **Est. duration** | 5 minutes |
| **Reversible?** | no, not by this command. A registered connection is never edited or deleted by SM (the runtime role holds `SELECT` and `INSERT` only); moving or replacing a broker is a later procedure. Say what is destroyed: nothing |

## When to use this

A managed integration publishes business events (File Mesh heartbeats and file-complete events, for example) to a customer broker,
and its package declares a `solace_event` metric. The Data Plane reads those events directly from that broker, so it needs the
broker's coordinates and a credential, **once per organization and environment**. Until a connection exists for an environment, the
push metrics of its instances are simply not collected (nothing fails; there is nothing to subscribe on).

## The rules (read before you register)

1. **One live connection per broker and message VPN, across the whole install.** A second live connection to the same host, port and
   VPN is refused, whichever environment or organization it is for (host names are compared without regard to case; the scheme does
   not matter). The command says so and writes nothing; the database holds the same line (`broker_connection_live_broker_once`), so
   it also holds under a race and for a row written any other way. Registering another VPN, or another broker, is fine.
   **SM can only compare the exact tuple it is given, so the rest is your duty:** another **port** of the same broker and VPN
   (a `tcp`, `tcps` or `ws` listener of one broker), or the same broker under **another name or an IP address**, is still the
   same broker and VPN, and passes both the command and the database index. Do not register it: it gives the Data Plane two
   sessions on one VPN and lets one organization's adapter ids be attributed to another's instances. **Message VPN names are
   case-sensitive** (measured 2026-10-07 on a local broker, `solace-pubsub-enterprise:10.25.0.177`, SEMP v2.48: `CaseVpn` and
   `casevpn` are two separate VPNs, `CASEVPN` matched neither, and a client logs in only to the VPN whose name matches exactly;
   client usernames are case-sensitive too), so SM compares the VPN name exactly, as the broker does, and a name differing only
   in letter case is a different VPN (`OR-117`).
   Retire the old connection first (see Rollback) to move an environment to a broker that is already taken.
2. **Adapter ids must be unique across everything that shares a VPN.** An adapter id identifies an adapter only within its message VPN.
   The Data Plane attributes an event to an instance by that id, so two adapters on one VPN that publish the same id, whoever runs
   them, cannot be told apart. SM cannot check this (the integrations are not SM's to change, `I1`); it is yours to keep when you
   choose which environments, organizations and adapters share a VPN.
3. **Why one connection per broker and VPN.** The Data Plane subscribes directly on the broker, one broad subscription per
   connection. Two connections on one VPN would each receive every adapter's events of the other, hold two sessions for the same
   bytes, and leave "which organization owns this VPN's traffic" unanswerable. If two environments need the same broker, give them
   different VPNs on that broker.

## Preconditions

- The Data Plane's database is migrated through the `broker_connection` migration and `sm_metrics` `V002` (the application refuses
  to start against a database that is not, and says which objects are missing).
- You hold the **DP runtime** database credential and the install's **master key** (the same two the application runs with), and you
  have started the application once with that key (so the key's canary exists; the command refuses a key it cannot verify, before
  it seals anything).
- The broker client username SM subscribes as has **subscribe** rights on the topics the integrations publish to, and the broker's
  message VPN is reachable from the Data Plane host. Nothing is required of the integration itself (`I1`).
- The credential is in a file only you can read, one line, UTF-8. A trailing line break is ignored.

## Steps

1. Pick the organization (`SM_BROKER_TENANT_ID`) and the environment (`SM_BROKER_ENVIRONMENT_ID`): the two UUIDs the control plane
   shows for the environment's instances.
2. Export the configuration. The credential is a **path**, never a value; the database URL is a bare location with no credential.

   ```sh
   export SM_DATA_RUNTIME_URL=jdbc:postgresql://db.internal:5432/sm
   export SM_DATA_RUNTIME_USERNAME=sm_dp_runtime SM_DATA_RUNTIME_PASSWORD=...      # from your secret manager
   export SM_DP_MASTER_KEY=... SM_DP_MASTER_KEY_ID=...                              # the install's, as the application has them
   export SM_BROKER_TENANT_ID=<org-uuid> SM_BROKER_ENVIRONMENT_ID=<environment-uuid>
   export SM_BROKER_SCHEME=tcps SM_BROKER_HOST=broker.example.com SM_BROKER_PORT=<the broker's SMF TLS port>
   export SM_BROKER_VPN=<message-vpn> SM_BROKER_USERNAME=<client-username>
   export SM_BROKER_CREDENTIAL_FILE=/run/secrets/sm-broker-credential
   ```

   `SM_BROKER_SCHEME` is one of `tcp`, `tcps`, `ws`, `wss`. No port is assumed: say it. `SM_BROKER_CONNECTION_ID` is optional; by
   default the id is derived from the organization and environment, so running the command again names the same connection.
3. Run the command (a standalone `main`; it never starts the application, so it cannot start a second worker):

   ```sh
   java -cp <the data plane's classpath> com.solace.sm.dp.apply.persistence.BrokerConnectionMain
   ```

## Verification

- It prints `broker connection recorded: <id>`. A second run prints `already registered with this record; credential unchanged`
  and writes nothing; a run with different coordinates for the same connection is refused (`a different connection record is
  already registered under this id`); a second live connection for the same environment is refused by name
  (`broker_connection_live_once`), and a second live connection to the same host, port and VPN (any environment, any
  organization) is refused in the command's own words (`one live connection per broker and message VPN`); neither strands a
  credential. The refusal never names the other connection's organization or environment; to find it, as the database owner:
  `SELECT tenant_id, environment_id, broker_connection_id FROM sm_data.broker_connection WHERE retired_at IS NULL AND
  lower(host) = lower('<host>') AND port = <port> AND vpn = '<vpn>'`.
- Within one tick (10 s by default) of an instance declaring a `solace_event` metric whose configuration has been applied, the
  Data Plane holds one session on that broker with one subscription **per distinct topic pattern** (never one per instance; the last
  level of a pattern is a state, never a wildcard, so a `start` event, which may carry a plaintext password, is never delivered).
- Pushed samples are queryable like polled ones (series with `source_discriminator = 'broker-push'`).

## What the counters mean (the blind spot, counted, not hidden)

One broad subscription means every adapter's events on the declared states reach the Data Plane, **including adapters SM does not
manage** (File Mesh: 17,280 heartbeats a day per adapter at the 5 s default). Events SM cannot attribute are dropped and counted on
`sm_metrics.broker_ingest_counter`, per connection, reason and UTC day (counts only; no payload, topic or adapter id is ever stored):

| Reason | Meaning |
|---|---|
| `undeclared_dropped` | no instance of the environment has that adapter id configured, or the instance has no live placement. Expected for adapters SM does not manage; **unexpected and total for a managed instance** means the key field is wrong (the id in the package's `instanceKey.configField` differs, in spelling or in case, from the id the integration publishes: matching is exact and case-sensitive) |
| `ambiguous_placement` | more than one live placement could own the event (a re-target overlap, or one id configured twice). The event is dropped, never guessed |
| `parse_failed` | the body is not one JSON object, or carries no readable key at the declared pointer |
| `buffer_dropped` | the push queue was full |

A topic pattern pins the number of levels. The File Mesh package pins the generated default of nine; an integration with a customised
base topic of another depth selects nothing, silently, until the package declares a pattern of its depth.

## Rollback

Not by this command. To stop the Data Plane subscribing, withdraw the push path with `sm.dp.metrics.push.enabled=false` and restart,
or retire the connection row as the database owner (`UPDATE sm_data.broker_connection SET retired_at = now() WHERE tenant_id = '<org>' AND broker_connection_id = '<id>'`); the next tick
releases the session, and the broker and VPN are free to be registered again.

## Escalation

The Data Plane owner. Say which environment, what the command printed, and the counts above for the connection.
