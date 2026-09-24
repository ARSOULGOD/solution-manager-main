# go-agent

Solution Manager's `host-local` agent: it enrolls outbound-only to the Data
Plane, holds a CP-issued lease the DP renews, applies configuration and
lifecycle commands to a managed integration on this host, and reports actual
state back.

**Stack:** Go 1.26 · gRPC/mTLS · Docker Engine API
**Status:** control path (slice 1) — enroll, session, lease, heartbeat, command
journal, actual-state reports. **No telemetry stream and no Kubernetes yet.**

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
| `--admin-addr` | — | loopback `/healthz` + `/metrics`; default `127.0.0.1:9464` |
| `--log-level` | — | `debug` \| `info` \| `warn` \| `error` |

## Where the design lives

Read [`CLAUDE.md`](CLAUDE.md) first — it states which documents are
authoritative and which are archived.

## Container image

Distroless, non-root, built from this directory. Mount a data volume at
`/var/lib/sm-agent`, mount `/var/run/docker.sock`, and pass the host docker
group with `--group-add`. Never bake an enrollment token into an image (`I6`).
