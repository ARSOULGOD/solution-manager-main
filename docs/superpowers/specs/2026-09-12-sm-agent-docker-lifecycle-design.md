# Design: sm-agent Docker container lifecycle demo

**Date:** 2026-09-12  
**Status:** Approved in brainstorming (pending user review of this written spec)  
**Component:** `go-agent`

## Goal

Demonstrate that `sm-agent` can independently **start / stop / restart / status** pre-created Docker containers (`java1`, `java2`, `java3`) via the Docker Engine API (not shell `docker` CLI, not raw JVM PID management).

```text
sm-agent  --Docker API/socket-->  java1 | java2 | java3  (containers)
```

## Cleanup (incorrect prior direction)

### Remove

| Path | Why |
|------|-----|
| `testdata/java-test-service/` | Entire tree created for manual `java -jar` + `kill -9` VM demo; not the Docker lifecycle path |

### Preserve

- `go-agent/internal/config`, `go-agent/internal/persist` (+ tests)
- `go-agent/Dockerfile`, `.dockerignore`, distroless packaging docs in README
- Design/docs/plans under `go-agent/docs/` and `docs/plans/go-agent/`
- Unrelated monorepo components (backend, frontend, etc.)

## Decisions locked

| Topic | Choice |
|-------|--------|
| Approach | Official Docker Go SDK + CLI subcommands |
| Container create | **Out of agent** — operator/compose pre-creates named containers; agent only lifecycle |
| Names | `java1`, `java2`, `java3` |
| Test apps | **One** reusable image, three container instances |
| DP protocol | Not required for this demo |
| Not in scope | `java -jar`, PID tracking, `kill -9`, systemd, shelling out to `docker` |

## Architecture

### Docker control layer

**Path:** `go-agent/internal/docker/`

```text
Controller (interface)
  Start(ctx, name) error
  Stop(ctx, name) error
  Restart(ctx, name) error
  Status(ctx, name) (Info, error)

Info: Name, ID, State, Status (from inspect)

Client: wraps Docker Engine API
  resolve name → container
  ContainerStart / ContainerStop / ContainerRestart / ContainerInspect
```

**Error handling (non-fatal to process; clear CLI exit):**

- container not found
- Docker daemon unavailable / dial error
- permission / socket access failure
- invalid/unexpected container state (surfaced from API)

**Isolation:** no imports from DP wire/apply packages; config/persist unchanged except CLI wiring in `main`.

### CLI

**Path:** `go-agent/cmd/sm-agent/main.go`

| Invocation | Behavior |
|------------|----------|
| `sm-agent start <name>` | Docker Start |
| `sm-agent stop <name>` | Docker Stop |
| `sm-agent restart <name>` | Docker Restart |
| `sm-agent status <name>` | Print name/id/state/status |
| `sm-agent` (no subcommand) | **Preserve** existing long-running mode (load config, wait for SIGINT/SIGTERM) |

Docker host: SDK defaults (`DOCKER_HOST` or `unix:///var/run/docker.sock`).

### Agent packaging

Keep existing distroless `go-agent/Dockerfile`. Demo run mounts:

```bash
-v /var/run/docker.sock:/var/run/docker.sock
--group-add <host-docker-gid>
```

(as already documented for socket access with nonroot).

## Test fixtures

**Path:** `testdata/java-demo/` (new; replaces removed `java-test-service`)

- Single minimal Spring Boot / Java 21 app + Dockerfile → image `sm-java-demo:local`
- `docker-compose.yml` defines containers:

| Name  | Host port | Image            |
|-------|-----------|------------------|
| java1 | 8081      | sm-java-demo:local |
| java2 | 8082      | sm-java-demo:local |
| java3 | 8083      | sm-java-demo:local |

- Prefer `docker compose up --no-start` (or create-then-stop) so Test 1 starts from **all stopped**
- Optional `GET /hello` for independent liveness checks per port
- Distinguish via container name + env (`SERVER_PORT` / service name) if useful in logs

## Tests

1. **Unit:** `internal/docker` against interface/fake (no daemon required)
2. **Regression:** `go test ./...` — existing config/persist tests still pass
3. **Manual / integration (real Docker):** documented procedure covering start all, stop java2 only, restart java2, status, agent restart without disturbing apps, invalid name errors

## Manual verification checklist

1. Start: `java1/2/3` → all running  
2. Stop `java2` → only java2 stopped  
3. Restart `java2` → all running  
4. Status each → clear states  
5. Restart sm-agent → apps unchanged  
6. `start/stop/status does-not-exist` → clear error, no crash  

## Out of scope / limitations (explicit)

- Agent does not create/pull/remove containers or images
- No DP enroll/lease/apply integration in this slice
- Live Docker tests require a running daemon and socket permissions
- Distroless agent image still needs host docker.sock + group_add for in-container demos

## Implementation order (for later plan)

1. Delete `testdata/java-test-service/`
2. Add `internal/docker` + unit tests
3. Wire CLI subcommands in `cmd/sm-agent`
4. Add `testdata/java-demo` image + compose
5. Document run commands; run `go test ./...` and manual Docker procedure when daemon available
