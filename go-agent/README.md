# go-agent

Agent for the `host-local` locus: enrollment, mTLS, protocol, collectors, runtime version introspection, K8s leader election.

**Stack:** Go · **Status:** no code yet.

## Before writing code here

This component does not own cross-cutting architecture. Read, in order:

| What | Where |
|---|---|
| Cross-cutting decisions | [`../docs/design/DECISIONS.md`](../docs/design/DECISIONS.md) |
| Overall architecture | [`../docs/design/architecture.md`](../docs/design/architecture.md) |
| **This component's design** | [`docs/design.md`](docs/design.md) |
| This component's decisions | [`docs/decisions.md`](docs/decisions.md), ID family `AG-D*` |
| **Sequenced workplan** | [`docs/workplan.md`](docs/workplan.md) — contract-spine path to first vertical slice |
| **P2 wire assumptions** | [`docs/p2-wire-assumptions.md`](docs/p2-wire-assumptions.md) — non-binding ASSUMED sketch until backend owns `.proto` |
| **P2 ASSUMED baseline** | [`docs/p2-baseline.md`](docs/p2-baseline.md) — **surrogate `.proto`** until backend; ASSUMED, not `contracts/` |
| **P3 mechanism gaps** | [`docs/p3-mechanism-gaps.md`](docs/p3-mechanism-gaps.md) — `AG-D2` persist + renew backoff + Cache introspection |
| **P2 assumptions audit** | [`docs/p2-wire-assumptions-audit.md`](docs/p2-wire-assumptions-audit.md) — adversarial review of the assumptions doc |
| **Agent context** | [`agent.md`](agent.md) — whole-monorepo durable context; update via skill `agent-md-context` on wrap-up |
| Approved plan | [`../docs/plans/go-agent/001-host-local-vertical-slice.md`](../docs/plans/go-agent/001-host-local-vertical-slice.md) — **DRAFT** (P4) |
| Setup checklist | [`../docs/checklists/new-module.md`](../docs/checklists/new-module.md) |

⚠️ **The baseline is UNBASELINED** — `SM-D*` decisions are PROPOSED and the agent↔DP wire protocol does not exist yet.
Do not start implementing against them. See [`../docs/tasks/codex-review-triage.md`](../docs/tasks/codex-review-triage.md).

**If the design is wrong or missing, raise it — do not invent architecture here.** Component-level decisions get an
ID in the `AG-D*` family and are recorded in [`docs/decisions.md`](docs/decisions.md), not in code comments.

## Build / run image

Distribution image for host-local `sm-agent` (distroless, non-root). Build context is this directory.

### Build (multi-arch)

```bash
cd go-agent
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t sm-agent:dev \
  .
```

For a local single-platform smoke image:

```bash
docker buildx build --platform linux/amd64 -t sm-agent:local --load .
```

### Run (operator contract)

- **Data dir:** mount a volume at `/var/lib/sm-agent` (or set `SM_AGENT_DATA_DIR` and mount that path).
- **Docker socket:** mount `/var/run/docker.sock` and pass the host docker group GID via `--group-add` / Compose `group_add`. Without `group_add`, socket access will fail with permission denied — that is expected.
- **Config:** pass CLI flags after the image name; env overrides include `SM_AGENT_DATA_DIR` and `SM_AGENT_ENROLL_TOKEN`.
- **Secrets:** never bake enrollment tokens into the image or commit real tokens in examples (`I6`). Prefer env or a secrets mount at run time.
- **Read-only root FS:** recommended at run time (`--read-only` plus a writable data volume); not forced by the Dockerfile.

Example (placeholders only):

```bash
DOCKER_GID="$(stat -c '%g' /var/run/docker.sock)"
docker run --rm \
  --read-only \
  --volume sm-agent-data:/var/lib/sm-agent \
  --volume /var/run/docker.sock:/var/run/docker.sock \
  --group-add "${DOCKER_GID}" \
  -e SM_AGENT_ENROLL_TOKEN \
  sm-agent:local \
  -dp-enroll-url "https://dp.example:443/..." \
  -dp-connect-addr "dp.example:443" \
  -dp-telemetry-addr "dp.example:443"
```

Compose sketch (GID must match the host):

```yaml
services:
  sm-agent:
    image: sm-agent:local
    user: nonroot
    read_only: true
    group_add:
      - "${DOCKER_GID}"
    volumes:
      - sm-agent-data:/var/lib/sm-agent
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      SM_AGENT_ENROLL_TOKEN: "${SM_AGENT_ENROLL_TOKEN}"
volumes:
  sm-agent-data:
```
