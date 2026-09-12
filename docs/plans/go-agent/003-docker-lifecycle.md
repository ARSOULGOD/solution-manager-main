# sm-agent Docker container lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the VM/JAR demo; add Docker Engine API control so `sm-agent start|stop|restart|status <name>` manages pre-created containers `java1`/`java2`/`java3`.

**Architecture:** `internal/docker` exposes `Controller` + `Info`; SDK client implements it. `cmd/sm-agent` only detects subcommands and wires calls. No-subcommand path unchanged. Test image + compose under `testdata/java-demo/` pre-creates named containers; agent never creates them.

**Tech Stack:** Go 1.26 · Docker Go SDK (`github.com/docker/docker`) · Java 21 demo image · docker compose

**Spec:** `docs/superpowers/specs/2026-09-12-sm-agent-docker-lifecycle-design.md`

## Global Constraints

- Agent only start/stop/restart/status **pre-created** containers (no create/pull/remove).
- Official Docker API/SDK — no `os/exec` of `docker` CLI; no PID/`kill -9`/systemd.
- `Controller` interface + `Info` struct; lifecycle logic not embedded in `main` beyond CLI wiring.
- Preserve exact no-subcommand behavior (config load + signal wait).
- Do not expand into DP protocol / enroll / apply.
- Do not modify unrelated monorepo components.
- Unit tests must not require a live Docker daemon.

---

## File map

| Path | Responsibility |
|------|----------------|
| DELETE `testdata/java-test-service/` | Remove incorrect VM/JAR demo |
| `go-agent/internal/docker/controller.go` | `Controller`, `Info` |
| `go-agent/internal/docker/client.go` | SDK-backed implementation |
| `go-agent/internal/docker/errors.go` | Sentinel / mapping helpers |
| `go-agent/internal/docker/client_test.go` | Fake API unit tests |
| `go-agent/cmd/sm-agent/main.go` | Subcommand detection + wire to Controller |
| `testdata/java-demo/` | One Java image + compose for java1/2/3 |
| `go-agent/README.md` | Demo commands (socket mount, lifecycle CLI) |

---

### Task 1: Cleanup VM/JAR testdata

- [ ] Delete entire `testdata/java-test-service/` tree
- [ ] Confirm no remaining references that require those files (ignore historical plan mentions)

### Task 2: `internal/docker` Controller + client (TDD)

- [ ] Define `Info` and `Controller` in `controller.go`
- [ ] Implement SDK client in `client.go` (`NewFromEnv`, Start/Stop/Restart/Status by name)
- [ ] Map not-found / daemon / permission errors clearly in `errors.go`
- [ ] Unit tests with fake backend implementing a narrow API surface used by the client
- [ ] `go test ./internal/docker/...`

### Task 3: CLI wiring in `cmd/sm-agent`

- [ ] If `os.Args[1]` ∈ {start,stop,restart,status}: require name arg, construct client, call Controller, print result, exit
- [ ] Else: existing `config.Load` + signal wait **unchanged**
- [ ] `go test ./...`

### Task 4: `testdata/java-demo` image + compose

- [ ] Minimal Java HTTP app + Dockerfile → `sm-java-demo:local`
- [ ] `docker-compose.yml`: containers `java1`/`java2`/`java3` on ports 8081/8082/8083
- [ ] README: build, `up --no-start`, agent CLI demo steps

### Task 5: Docs + verification

- [ ] Update `go-agent/README.md` with lifecycle CLI + socket mount notes
- [ ] Run `go test ./...`
- [ ] If Docker daemon available: run manual checklist from spec; else document blocked

---

## Manual checklist (from spec)

1. Start java1/2/3 → all running  
2. Stop java2 → only java2 stopped  
3. Restart java2 → all running  
4. Status each  
5. Restart sm-agent → apps unchanged  
6. Invalid name → clear error  
