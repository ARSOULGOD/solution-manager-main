# go-agent distribution Dockerfile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a multi-arch, distroless distribution image for `sm-agent` under `go-agent/`, with operator run docs for data volume + Docker socket + `group_add`.

**Architecture:** One multi-stage `Dockerfile` in `go-agent/`: `golang:1.26.4` builder cross-compiles a static binary via BuildKit `TARGETOS`/`TARGETARCH`; runtime is `gcr.io/distroless/static-debian13:nonroot`. `.dockerignore` keeps context lean; README documents buildx and the host-local runtime contract.

**Tech Stack:** Docker Buildx · Go 1.26.4 · Distroless static-debian13:nonroot · existing `cmd/sm-agent`

**Spec:** [`docs/design/go-agent/specs/2026-09-10-go-agent-dockerfile-design.md`](../../design/go-agent/specs/2026-09-10-go-agent-dockerfile-design.md)

## Global Constraints

- Image path: `go-agent/Dockerfile`; build context is `go-agent/` only (not monorepo root).
- Runtime base: `gcr.io/distroless/static-debian13:nonroot` (explicit Debian pin).
- Builder base: `golang:1.26.4` (matches `go-agent/go.mod`).
- Platforms: `linux/amd64` and `linux/arm64`.
- Process user: distroless `nonroot`; Docker sock access via operator `group_add` of host docker GID — do not run as root.
- `ENTRYPOINT` must be exec/vector form only (no shell in distroless).
- `I6`: never bake tokens/secrets into image layers or committed example commands with real secrets.
- `I4`: packaging does not ratify `SM-D*`.
- Out of scope: `runtime/` compose, CI registry push, app behavior changes, new `AG-D*`/`RT-D*`.

---

## File map

| Path | Responsibility |
|---|---|
| `go-agent/Dockerfile` | Multi-stage build + runtime image definition |
| `go-agent/.dockerignore` | Exclude non-build context from `docker build` |
| `go-agent/README.md` | Operator-facing **Build / run image** section |

---

### Task 1: Dockerfile + `.dockerignore`

**Files:**
- Create: `go-agent/.dockerignore`
- Create: `go-agent/Dockerfile`
- Test: local `docker buildx build` (single platform `--load`)

**Interfaces:**
- Consumes: `go-agent/go.mod`, `go-agent/cmd/sm-agent`, `go-agent/internal/**`
- Produces: image tagged `sm-agent:local` containing `/usr/local/bin/sm-agent`, `ENTRYPOINT ["/usr/local/bin/sm-agent"]`, user `nonroot`

- [x] **Step 1: Create `go-agent/.dockerignore`**

```gitignore
.git
.gitignore
*.zip
docs/
agent.md
README.md
**/*_test.go
**/testdata/
.idea/
.vscode/
*.swp
.DS_Store
```

- [x] **Step 2: Create `go-agent/Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1

FROM --platform=$BUILDPLATFORM golang:1.26.4 AS builder

WORKDIR /src

# No third-party requires yet; go.sum may be absent — do not COPY a missing go.sum.
COPY go.mod ./
RUN go mod download

COPY . .

ARG TARGETOS
ARG TARGETARCH

RUN CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} \
    go build -trimpath -ldflags="-s -w" -o /out/sm-agent ./cmd/sm-agent

FROM gcr.io/distroless/static-debian13:nonroot

COPY --from=builder /out/sm-agent /usr/local/bin/sm-agent

USER nonroot

ENTRYPOINT ["/usr/local/bin/sm-agent"]
```

- [x] **Step 3: Single-platform build (smoke compile)**

Run from `go-agent/`:

```bash
docker buildx build --platform linux/amd64 -t sm-agent:local --load .
```

Expected: build succeeds; no error from `go build` or `COPY`.

- [x] **Step 4: Smoke-run config path as nonroot**

```bash
docker run --rm sm-agent:local
```

Expected: process starts, prints config (current `main` behavior), exits 0 or runs briefly without permission errors related to the binary itself.  
Confirm image user is nonroot:

```bash
docker image inspect sm-agent:local --format '{{.Config.User}}'
```

Expected: `nonroot` (or numeric UID used by that tag — typically `65532`).

- [ ] **Step 5: Commit**

```bash
git add go-agent/Dockerfile go-agent/.dockerignore
git commit -m "$(cat <<'EOF'
Add distroless multi-arch Dockerfile for sm-agent.

EOF
)"
```

(Only if the user asked to commit; otherwise leave staged/unstaged per session policy.)

---

### Task 2: README run contract + multi-arch verify

**Files:**
- Modify: `go-agent/README.md` (append section; keep existing “Before writing code” content)
- Test: `docker buildx build --platform linux/amd64,linux/arm64` (no `--load` required if multi-arch; use `--output type=image,push=false` or build without push — if the builder cannot emit a multi-arch load, use `buildx build --platform linux/amd64,linux/arm64 .` and expect success without `--load`)

**Interfaces:**
- Consumes: image from Task 1
- Produces: operator docs matching the approved runtime contract

- [x] **Step 1: Append Build / run image section to `go-agent/README.md`**

Add after the existing content (do not remove the status/design table). Exact markdown to append:

````markdown
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
````

- [x] **Step 2: Multi-arch build verification**

```bash
cd go-agent
docker buildx build --platform linux/amd64,linux/arm64 -t sm-agent:multi .
```

Expected: both platforms build successfully (may warn that multi-platform images cannot `--load` into a single docker engine — that is OK without `--push`).

- [ ] **Step 3: Commit**

```bash
git add go-agent/README.md
git commit -m "$(cat <<'EOF'
Document sm-agent distribution image build and run contract.

EOF
)"
```

(Only if the user asked to commit.)

---

## Spec coverage checklist

| Spec requirement | Task |
|---|---|
| `go-agent/Dockerfile` multi-stage | Task 1 |
| `static-debian13:nonroot` + vector ENTRYPOINT | Task 1 |
| `TARGETOS`/`TARGETARCH` cross-compile | Task 1 |
| `.dockerignore` | Task 1 |
| amd64+arm64 buildx | Task 2 |
| README: volume, sock, `group_add`, env/flags, no secrets | Task 2 |
| Single-platform smoke run / nonroot inspect | Task 1 |
| Out of scope items not implemented | — |
