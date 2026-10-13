# go-agent distribution Dockerfile — design

| | |
|---|---|
| **Status** | Approved in brainstorming (2026-09-10); awaiting implementation plan |
| **Component** | `go-agent` |
| **Related** | `go-agent/docs/design.md` · `docs/plans/go-agent/001-host-local-vertical-slice.md` · `runtime/` (compose later, out of scope) |

## Goal

Ship a **distribution/runtime** container image for the host-local `sm-agent` binary: multi-arch, minimal attack surface, runnable by operators with Docker socket access and a persistent data volume.

## Decisions locked in brainstorming

| Topic | Choice |
|---|---|
| Purpose | Distribution/runtime image (not local-dev-only) |
| Location | `go-agent/Dockerfile` (build context = `go-agent/`) |
| Base | Multi-stage → `gcr.io/distroless/static-debian13:nonroot` (explicit Debian pin per upstream) |
| Docker access | Non-root process + operator supplies host docker group GID (`group_add`) |
| Architectures | `linux/amd64` + `linux/arm64` via `docker buildx` |
| Build approach | Single multi-stage Dockerfile; BuildKit `TARGETOS`/`TARGETARCH` cross-compile |

## Image layout & build

### Builder stage

- Base: official `golang` image, **pin minor to match** `go-agent/go.mod` (currently Go **1.26.x**).
- Working directory and module root: `/src` (or equivalent) with `COPY` of module sources.
- Build:

  ```text
  CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH \
    go build -trimpath -ldflags="-s -w" -o /out/sm-agent ./cmd/sm-agent
  ```

- Use BuildKit args `TARGETOS` / `TARGETARCH` (set automatically by `buildx`).

### Runtime stage

- Base: `gcr.io/distroless/static-debian13:nonroot` (verified against Distroless README: prefer explicit `-debian13` so the default distro bump does not silently move the pin; `nonroot` tag is published for this image).
- `COPY --from=builder /out/sm-agent /usr/local/bin/sm-agent`
- `USER nonroot` (use the image’s nonroot identity; do not invent a custom UID in the Dockerfile).
- `ENTRYPOINT` in **exec/vector form** only (Distroless has no shell — shell-form ENTRYPOINT fails).
- Example: `ENTRYPOINT ["/usr/local/bin/sm-agent"]`
- No shell, no package manager, no default `CMD` beyond letting operators pass flags.

### Context hygiene

- Add `go-agent/.dockerignore` excluding at least: `.git`, `*.zip`, `docs/` (design markdown not required to compile), IDE junk, and other non-source noise.
- Keep `go.mod` / `go.sum` / `cmd/` / `internal/` in context.

### Multi-arch publish shape

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t <registry>/<repo>/sm-agent:<tag> \
  -f Dockerfile \
  .
```

(run from `go-agent/`). Local smoke may `--load` a single platform.

## Runtime contract

### Persistence

- Default data directory: `/var/lib/sm-agent` (matches existing config: `-data-dir` / `SM_AGENT_DATA_DIR`).
- Operators must mount a volume or bind mount at that path (or override the path and mount there).
- Image layers must never contain enrollment tokens, private keys, or resolved secrets (`I6`).

### Docker Engine access

- Expected mount: `/var/run/docker.sock:/var/run/docker.sock`.
- Process runs as distroless `nonroot`. Operators must add the host’s docker group GID, e.g.:

  - `docker run --group-add <docker_gid> ...`
  - Compose: `group_add: ["<docker_gid>"]`

- Permission denied on the socket without `group_add` is an operator configuration error, not an image defect.
- Full apply/introspection against the Docker API may land after the packaging slice; the image still documents the contract now.

### Configuration surface

- Primary: CLI flags after `ENTRYPOINT` (compose `command` / `docker run ... <flags>`).
- Env already supported by the binary: `SM_AGENT_DATA_DIR`, `SM_AGENT_ENROLL_TOKEN`.
- No new in-image config file format in this work.

### Security defaults

- Distroless + static binary + non-root.
- Recommend read-only root filesystem in run documentation; do **not** hard-require it in the Dockerfile (data volume and future sock usage need writable mounts).
- Do not bake DP addresses or tokens into the image.

## Deliverables

| Path | Action |
|---|---|
| `go-agent/Dockerfile` | Create |
| `go-agent/.dockerignore` | Create |
| `go-agent/README.md` | Add short **Build / run image** section (buildx, volume, sock, `group_add`, env/flags) |

## Explicitly out of scope

- `runtime/` compose, Helm, DaemonSet, or `RT-D*` decisions
- Registry push / CI release pipeline
- Changes to `sm-agent` application behavior beyond what packaging needs
- New `AG-D*` (optional later if base image / UID policy becomes product policy)

## Verification

1. `docker buildx build --platform linux/amd64,linux/arm64` completes successfully from `go-agent/`.
2. Single-platform load/run: container starts as nonroot; flag parsing / `-h` or equivalent config load path works.
3. Documented run with volume + sock + `group_add` is consistent with the runtime contract (socket open can be verified when Docker client code exists).

## Constraints / invariants

- **I4:** packaging does not ratify `SM-D*`; host-local agent image is still under PROPOSED locus design.
- **I6:** no secrets in image layers, history, or example commands that embed real tokens in committed docs.
- Build context stays inside `go-agent/`; monorepo root is not required as context for this image.
