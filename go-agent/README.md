# go-agent

Solution Manager's `host-local` agent: it enrolls outbound-only to the Data
Plane, holds a CP-issued lease the DP renews, applies configuration and
lifecycle commands to a managed integration on this host, and reports actual
state back.

**Stack:** Go 1.26 · gRPC/mTLS · Docker Engine API
**Status:** control path (slice 1) — enroll, session, lease, heartbeat, command
journal, actual-state reports — the telemetry role (slice 2), and, with plan 015
(W1 and W2), discovery by label, pushed telemetry declarations, health and
container start time. Built on `integration/go-agent` (the agent PR into `UAT`),
not merged. The live gates against the real Data Plane (G-A1, G-A2) are plan
015 W5. **No Kubernetes yet.**

**Operators:** enrolment is the agent's **first start with a token** (there is no
`enroll` subcommand); the token is issued through the console API, and the
containers it manages opt in by the labels `com.solace.sm.instance`,
`com.solace.sm.config-file` and (optionally) `com.solace.sm.health-port`, which is
a **host** port (the probe dials `127.0.0.1:<host port>`). The procedures are the
runbooks `enable-agent-gateway.md` and `revoke-or-change-an-agent.md` in
`docs/runbooks/backend/`, which land with the backend PR (each `UNTESTED` until G-A1).

**Telemetry.** With `--roles control,telemetry` (or `telemetry`) the agent scrapes
the `http_json` metrics the Data Plane declares to it on the wire (a local file, `--telemetry-config`, is an optional development seed), buffers each batch on disk
under a byte and an age cap, and sends them on the Telemetry stream within the
gateway's credits. Samples carry only `binding_id`, `replica_id` and
`incarnation_id`. The first batch waits for the first actual-state report, about
one report interval after the session opens. See [`RUNBOOK.md`](RUNBOOK.md).

**The wire** is `../contracts/agent/v1/agent_gateway.proto` (plan 015 T1.2; `AG-D13`'s in-repo draft is superseded by
its exit condition, OR-126 A1). It is append-only, enforced by step 18 of `../contracts/run-checks.sh`. There is no
`proto/` directory in this module.

## Build and test

```bash
make proto      # regenerate internal/wire/pb from ../contracts/agent/v1 (needs buf 1.73.0 + protoc-gen-go v1.36.12, protoc-gen-go-grpc v1.5.1)
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
| `--enrollment-token` | `SM_AGENT_ENROLL_TOKEN` | single-use, never persisted. **Prefer the environment variable (or a file the wrapper reads into it):** argv is visible in `ps` and `docker inspect`. The env value is read after flag parsing and never appears in usage or error output. |
| `--trust-bundle` | — | PEM the DP server is verified against |
| `--admin-addr` | — | `/healthz` + `/metrics`; default `127.0.0.1:9464` (loopback). Both are unauthenticated, so binding a non-loopback address — e.g. for a Prometheus scrape — exposes them; that is the operator's decision |
| `--roles` | `SM_AGENT_ROLES` | `control` (default), `control,telemetry` or `telemetry`; a telemetry-only agent refuses commands `CAPABILITY_MISSING` |
| `--telemetry-config` | — | optional, development only. A declarations file that seeds bindings, intervals and `http_json` metrics (`AG-D22`); the Data Plane pushes the real declarations on the wire (`AG-D28`) |
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

**Network.** The agent probes the managed container's health at `127.0.0.1:<host
port>` and scrapes every published port at `127.0.0.1:<published port>`. Inside a
container on Docker's default bridge network, `127.0.0.1` is the agent
container's own loopback, so a containerised agent needs **host networking**
(`--network host`, Linux; Docker Desktop's host networking is not the supported
shape), or run the binary on the host. Reasoned from the code, not run; the
containerised form is for plan 015 T4.4 or a later gate to prove.

**Data directory.** The image runs as the `nonroot` user, so the volume at
`/var/lib/sm-agent` must be writable by it (a fresh named volume is likely
root-owned; reasoned, not run).
