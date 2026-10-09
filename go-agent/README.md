# go-agent

Solution Manager's `host-local` agent: it enrolls outbound-only to the Data
Plane, holds a CP-issued lease the DP renews, applies configuration and
lifecycle commands to a managed integration on this host, and reports actual
state back.

**Stack:** Go 1.26 · gRPC/mTLS · Docker Engine API
**Status:** control path (slice 1) — enroll, session, lease, heartbeat, command
journal, actual-state reports — and the telemetry role (slice 2), built and
reviewed on branch `feat/go-agent-telemetry`, not merged. **No Kubernetes yet.**

**Telemetry.** With `--roles control,telemetry` (or `telemetry`) the agent scrapes
the `http_json` metrics declared in a local file, buffers each batch on disk
under a byte and an age cap, and sends them on the Telemetry stream within the
gateway's credits. The Data Plane may set how often every binding is pulled and
how often stored batches are pushed, on `SessionAccept.limits` (`AG-D26`, a
DRAFT ask); without them the declared intervals apply and each batch is sent as
soon as it is written. Samples carry only `binding_id`, `replica_id` and
`incarnation_id`. The first batch waits for the first actual-state report, about
one report interval after the session opens. See [`RUNBOOK.md`](RUNBOOK.md).

⚠️ **The wire is a DRAFT.** `proto/agentgateway/v1draft/agent_gateway.proto` is
transcribed from the agent-gateway design §4.3/§4.11 while open item 6 / `AG-1`
/ `AGW-2` — the reviewed `.proto` under `contracts/` — is open (`AG-D13`).

## Build and test

```bash
make proto      # regenerate internal/wire/pb (needs buf + protoc-gen-go[-grpc])
make build      # bin/sm-agent, bin/fake-gateway
make test       # go vet + unit tests
make test-integration   # scenarios against an in-process fake gateway
make test-docker        # adapter against a real container (needs a socket)
```

## Run it against the fake gateway

The fake gateway (`AG-D16`) speaks the DRAFT wire over real mTLS and runs a
scripted scenario. It is a test double, not a Data Plane.

To type commands — `preflight`, `apply <file>`, `restart`, `stop` — into a
running gateway (`--interactive`) and see each outcome, follow
[`RUNBOOK.md`](RUNBOOK.md). The walk-through below runs the whole `demo.yaml`
sequence in one go.

```bash
make build
./bin/fake-gateway --listen 127.0.0.1:8443 \
  --scenario internal/fakegw/scenarios/demo.yaml \
  --enroll-token demo-token --ca-out /tmp/sm-ca.pem
```

Give it something to manage — any container carrying the binding label, with a
bind-mounted config directory and its config location on the command line:

```bash
mkdir -p /tmp/sm-conf
docker run -d --name java1 \
  --label com.solace.sm.binding-id=binding-1 \
  -v /tmp/sm-conf:/etc/app \
  <your-image> --spring.config.additional-location=file:/etc/app/
```

Then:

```bash
./bin/sm-agent \
  --data-dir /tmp/sm-agent-data \
  --connect-endpoint 127.0.0.1:8443 \
  --enrollment-token demo-token \
  --trust-bundle /tmp/sm-ca.pem \
  --log-level debug
```

The scenario walks preflight → apply → restart → report. Watch it:

```bash
curl -s localhost:9464/healthz | jq
cat /tmp/sm-conf/application.yml
```

## Configuration

| Flag | Env | Meaning |
|---|---|---|
| `--data-dir` | `SM_AGENT_DATA_DIR` | durable state; default `/var/lib/sm-agent` |
| `--connect-endpoint` | — | bootstrap DP endpoint, replaced by the one enrollment returns |
| `--enrollment-token` | `SM_AGENT_ENROLL_TOKEN` | single-use, never persisted |
| `--trust-bundle` | — | PEM the DP server is verified against |
| `--admin-addr` | — | `/healthz` + `/metrics`; default `127.0.0.1:9464` (loopback). Both are unauthenticated, so binding a non-loopback address — e.g. for a Prometheus scrape — exposes them; that is the operator's decision |
| `--roles` | `SM_AGENT_ROLES` | `control` (default), `control,telemetry` or `telemetry`; a telemetry-only agent refuses commands `CAPABILITY_MISSING` |
| `--telemetry-config` | — | no default; required with the telemetry role. The declarations file: bindings, intervals and `http_json` metrics (`AG-D22`) |
| `--telemetry-buffer-bytes` | — | default `64MiB`. Global byte cap on the on-disk telemetry buffer; oldest batch dropped first (`AG-D24`) |
| `--telemetry-buffer-age` | — | default `24h`; must be positive. Global age cap on buffered batches; older ones dropped as gaps (`AG-D24`) |
| `--log-level` | — | `debug` \| `info` \| `warn` \| `error` |

## Where the design lives

Read [`CLAUDE.md`](CLAUDE.md) first — it states which documents are
authoritative and which are archived.

## Container image

Distroless, non-root, built from this directory. Mount a data volume at
`/var/lib/sm-agent`, mount `/var/run/docker.sock`, and pass the host docker
group with `--group-add`. Never bake an enrollment token into an image (`I6`).

**Also mount each managed config directory, at the same absolute path it has
on the host.** The agent finds the file to write from the managed container's
bind mount, whose source is a *host* path. Running on the host, it writes there
directly. Running in a container, that path must exist inside the agent's
container too — `-v /srv/cache/conf:/srv/cache/conf` — or every apply fails
before the rename with `FAILED_NO_EFFECT`. The cause is not obvious from that
error, which is why it is spelled out here.
