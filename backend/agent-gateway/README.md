# Agent Gateway

> **Taken at `87e338e1` as history (plan 015 T1.2).** The wire is now `contracts/agent/v1/agent_gateway.proto` (not `go-agent/proto/`);
> the migrations named below are dropped or rewritten (plan 015 §7: `VDA`/`VDB`/`VCA`), and the `sm.dp.agent.runtime.enabled` switch is
> **not ported** (Global constraint 5: the adapter is chosen per placement). T5.2 rewrites this README.

`agent-gateway` is the transport-only Java boundary for the Go `host-local` agent. It is generated from
`contracts/agent/v1/agent_gateway.proto` (moved from `go-agent/proto/agentgateway/v1draft/`); the historical `v1draft` package name is retained for
wire compatibility, but this file is the versioned production contract for this repository.

The module may depend on `dp-api`, gRPC and protobuf only. It must not acquire JDBC, Flyway, JPA, broker, CP or
Spring bean-lookup dependencies. Durable identity, session, lease, command, report, telemetry and audit state is
implemented by `dp-core` through the `dp-api` ports.

## Local checks

```bash
cd backend
mvn -pl agent-gateway -am test
mvn -pl launcher -am -DskipTests compile
```

The listener is disabled by default. To enable it, provide all of:

```properties
sm.agent.gateway.enabled=true
sm.agent.gateway.certificate-file=/run/secrets/dp-agent-gateway.crt
sm.agent.gateway.private-key-file=/run/secrets/dp-agent-gateway.key
sm.agent.gateway.trust-bundle-file=/run/secrets/agent-ca.pem
sm.agent.gateway.readiness-port=9080
```

When enabled, the optional readiness port is a mute TCP socket: it accepts and closes connections only after the
mTLS listener has started, and closes before shutdown. The gateway also publishes a `HealthIndicator` and bounded
Micrometer counters/gauges (active sessions, outstanding credits, telemetry batches, rejections, command delivery);
neither carries payloads or credentials.

**No actuator HTTP endpoint is exposed by the launcher.** The launcher depends on `spring-boot-actuator` (for the
`HealthIndicator` type) but not `spring-boot-starter-actuator`: the starter's web-endpoint `HandlerMapping` beans
sit outside the console's authenticated chain, and `OperationCoverageValidator` refuses to start with one present. That
left operators with a TCP socket and nothing to scrape, so the gateway offers its own, **separate and authenticated**
management listener (`AgentGatewayManagementServer`, `AGW-D22`) — not a console route:

```properties
sm.agent.gateway.management-port=9445            # 0 (default) = off
sm.agent.gateway.management-bind-address=127.0.0.1   # loopback by default
sm.agent.gateway.management-token=<at least 16 characters, from your secret mechanism>
```

`GET /management/health` (JSON) and `GET /management/metrics` (Prometheus text, gateway meters plus JVM heap/threads/CPU)
need `Authorization: Bearer <token>`; the process refuses to start with the port set and no token; any other method or
path is refused. The L4 readiness socket remains the deployment probe, and it closes first when a planned stop begins.

## Behaviour operators should know (all executed against the real Go agent — see `docs/plans/backend/evidence/`)

- **Planned drain (`AGW-D18`).** A stop (SIGTERM) closes the readiness socket, refuses new sessions `DRAINING`, tells every live agent to
  finish what it is doing and reconnect on a staggered schedule, closes each session on its answer, and forces any that have not
  answered at `drain-window`. The enclosing process must be allowed that long (`spring.lifecycle.timeout-per-shutdown-phase` ≥
  `drain-window + shutdown-grace`).
- **Revocation and permission changes (`AGW-D19`).** The gateway re-reads the control plane's registration for every live session each
  `revocation-check-interval` (5 s) and at every claim and renewal. A revoked agent is told to stop, its streams end and it cannot
  reconnect; a narrowed or widened registration re-issues the lease under the live session. Revocation is sticky.
- **Admission (`AGW-D20`).** `max-sessions`, a claim token bucket and a per-agent minimum claim interval refuse new sessions with
  `ADMISSION_LIMITED` and a jittered `retry_after` before any database work.
- **Liveness (`AGW-D21`).** An unfinished command is re-notified every `notice-retry-interval`; an expired lease is renewed with its last nonce at
  session open; a telemetry stream held at zero credits is re-granted after `telemetry-credit-recovery-interval`.

The certificate and key are deployment inputs. Enrollment and lease issuance are delegated to injectable
`EnrollmentCertificateIssuer` and `LeaseIssuer` implementations. The launcher now supplies CP-backed adapters:
`Pkcs12AgentCertificateAuthority` signs the CSR with the configured CP CA key, while the CP registry checks the
one-time token, target, binding, capability and revocation epochs before issuing a deterministic, retry-safe lease
nonce. The CA keystore is still an operator-provided secret; if it is not configured, enrollment fails closed.

Required CP-side properties are:

```properties
sm.control.agent-gateway.ca.key-store-file=/run/secrets/sm-agent-ca.p12
sm.control.agent-gateway.ca.key-store-type=PKCS12
sm.control.agent-gateway.ca.key-store-password=${SM_AGENT_CA_PASSWORD}
sm.control.agent-gateway.ca.key-alias=agent-ca
sm.control.agent-gateway.connect-endpoint=dp.example.internal:9443
```

Provision `sm_control.agent_registration` through the control-plane bootstrap/admin path. The enrollment token is
stored as SHA-256, never as plaintext. A local provisioning example is:

```sql
insert into sm_control.agent_registration
  (agent_id, enrollment_token_digest, target_id, binding_ids, capabilities,
   audience, lease_ttl_seconds, admission_margin_seconds)
values
  ('00000000-0000-0000-0000-000000000001', decode('<sha256(token)>', 'hex'),
   'target-1', array['binding-1'], array['APPLY_CONFIG','LIFECYCLE'],
   'solution-manager', 900, 30);
```

Do not put the plaintext token in SQL history or logs. The CA private key and password must be delivered through the
deployment secret mechanism and must not be committed.

After the first successful enrollment, CP records the CSR digest and issued certificate material. A retry with the
same token and CSR returns the same material after a lost response; a different CSR is refused.

## Database

Run `sm_data` Phase 0 once as the cluster/bootstrap administrator, then run the normal `SmDataMigrator` as
`sm_dp_migrator`. The CP migration head is `sm_control/V015__agent_enrollment_replay.sql`; it adds durable
same-CSR enrollment replay. `V018__agent_gateway.sql` creates the durable gateway state. The gateway process uses only the
`sm_dp_runtime` role and never runs migrations. `V020__agent_runtime_binding.sql` adds the explicit placement-to-agent
resolver and canonical telemetry base tables; `V021__agent_telemetry_projection.sql` adds the agent-to-series mapping;
`V022__agent_operation_correlation.sql` stores apply/intent/attempt correlation; and `V023__agent_rendered_generation.sql`
stores the hash-verified rendered bytes consumed by the apply engine.
Provision both mappings before enabling apply or telemetry for an agent. The runtime adapter is opt-in:

```properties
sm.dp.agent.runtime.enabled=true
```

It resolves only rows from `sm_data.agent_placement_binding`; it never guesses from a target or certificate. Before
enabling it, the upstream configuration publisher must insert one row per `(tenant, placement, generation)` into
`sm_data.agent_config_generation` with `config_sha256 = sha256(rendered_config)` using the `sha256:<64 hex>` form.
The runtime role has SELECT only, and a missing or hash-mismatched row fails preflight without contacting the agent.
Rollback
is deliberately returned as the coded `agent.rollback.unsupported` result because the current wire contract has no
rollback verb.

Maintenance (`ensure_metrics_raw_partitions` and `agent_gateway_maintenance`) is owned by the migration role and runs as a
**separate one-shot process**, `SmDataMaintenanceMain` (image entrypoint `sm-maintain`), scheduled by the deployment — cron, a systemd
timer or a Kubernetes `CronJob`; examples in `runtime/deploy/`, procedure in `docs/runbooks/backend/maintain-sm-data.md`. It is idempotent
and advisory-locked, so a concurrent, retried or missed run is harmless, and the application's runtime role cannot run it.

Capacity is **measured, not promised**: `docs/plans/backend/evidence/011-capacity.md` records the hardware, workload and results; the defaults
(`max-sessions`, admission rate) are bounds informed by it.
