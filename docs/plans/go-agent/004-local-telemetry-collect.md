# sm-agent local telemetry collect Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `sm-agent collect` that HTTP-scrapes configured service targets, validates samples, and writes segregated JSON under `$SM_AGENT_DATA_DIR/telemetry/<id>/` (local filesystem only; no DP push).

**Architecture:** New `internal/telemetry` package owns target loading, HTTP scrape, validation, and atomic file store. `cmd/sm-agent` only detects `collect` and wires flags → telemetry.Run. Docker lifecycle CLI and no-subcommand long-running mode stay unchanged.

**Tech Stack:** Go 1.26 · stdlib `net/http` · YAML targets (`gopkg.in/yaml.v3`) · existing `internal/persist` atomic write helpers where practical · `httptest` for unit tests

**Spec:** `docs/superpowers/specs/2026-09-12-sm-agent-local-telemetry-collect-design.md`

## Global Constraints

- Storage = local filesystem only under agent data dir — **no DP push**.
- Java services are examples only; targets are opaque `id` + `url`.
- Do not expand Docker lifecycle features; leave `internal/docker` as-is.
- Preserve exact no-subcommand behavior (config load + signal wait).
- Lifecycle subcommands (`start|stop|restart|status`) remain working.
- Validate: transport OK, HTTP 2xx, non-empty body, max body size (default 1 MiB).
- On failure: do not overwrite `latest.json`; write under `_failures/`.
- Exit non-zero if any target fails in a collect cycle.
- Do not modify unrelated monorepo components (backend/frontend/etc.).
- Unit tests must not require Docker or live services (`httptest`).

---

## File map

| Path | Responsibility |
|------|----------------|
| `go-agent/internal/telemetry/types.go` | `Target`, `Sample` structs |
| `go-agent/internal/telemetry/targets.go` | Load YAML targets file |
| `go-agent/internal/telemetry/validate.go` | Validation rules |
| `go-agent/internal/telemetry/store.go` | Segregated filesystem writes |
| `go-agent/internal/telemetry/scrape.go` | HTTP GET scrape |
| `go-agent/internal/telemetry/collect.go` | Orchestrate one cycle |
| `go-agent/internal/telemetry/*_test.go` | Unit tests |
| `go-agent/cmd/sm-agent/main.go` | Wire `collect` subcommand only |
| `testdata/java-demo/targets.yaml` | Example targets for java1/2/3 |
| `go-agent/README.md` | Manager demo commands |
| `testdata/java-demo/README.md` | Link collect demo |

---

### Task 1: Types, targets load, validate, store (TDD)

**Files:**
- Create: `go-agent/internal/telemetry/types.go`
- Create: `go-agent/internal/telemetry/targets.go`
- Create: `go-agent/internal/telemetry/validate.go`
- Create: `go-agent/internal/telemetry/store.go`
- Create: corresponding `*_test.go`

**Interfaces:**
- Produces:
  - `type Target struct { ID string; URL string }`
  - `type Sample struct { ServiceID, URL, ScrapedAt string; HTTPStatus int; ContentType, Body, Error string; OK bool }`
  - `LoadTargets(path string) ([]Target, error)`
  - `Validate(sample *Sample, maxBody int64) error` — sets OK/Error; returns error if invalid
  - `Store struct` with `SaveSuccess(dataDir string, s Sample) error` and `SaveFailure(dataDir string, s Sample) error`

- [ ] **Step 1:** Write failing tests for LoadTargets (valid YAML, missing file, empty id/url)
- [ ] **Step 2:** Implement LoadTargets; tests pass
- [ ] **Step 3:** Write failing tests for Validate (2xx+body OK; 500 fail; empty body fail; oversize fail)
- [ ] **Step 4:** Implement Validate; tests pass
- [ ] **Step 5:** Write failing tests for Store (creates `telemetry/<id>/latest.json` + history; failure → `_failures/`; success does not leave stale failure as latest)
- [ ] **Step 6:** Implement Store using atomic write (reuse `internal/persist` atomic helper if it fits; otherwise local temp+rename); tests pass
- [ ] **Step 7:** `go test ./internal/telemetry/...`

---

### Task 2: Scrape + Collect orchestration (TDD)

**Files:**
- Create: `go-agent/internal/telemetry/scrape.go`
- Create: `go-agent/internal/telemetry/collect.go`
- Create: `scrape_test.go`, `collect_test.go`

**Interfaces:**
- Consumes: Target, Sample, Validate, Store
- Produces:
  - `Scrape(ctx context.Context, client *http.Client, t Target) Sample`
  - `Run(ctx context.Context, dataDir string, targets []Target, client *http.Client, maxBody int64) error` — scrapes all; persists; returns error if any failed

- [ ] **Step 1:** httptest tests: 200 body → OK sample; 503 → not OK; timeout/connection → not OK
- [ ] **Step 2:** Implement Scrape (timeout via client; UTC scraped_at RFC3339)
- [ ] **Step 3:** Collect test with two httptest servers → two segregated latest.json files; one failing URL → failure file + overall error
- [ ] **Step 4:** Implement Run; tests pass
- [ ] **Step 5:** `go test ./internal/telemetry/...`

---

### Task 3: CLI `collect` wiring

**Files:**
- Modify: `go-agent/cmd/sm-agent/main.go`
- Optional: `go-agent/internal/telemetry/cli.go` for flag parsing if main would get thick

**Interfaces:**
- Consumes: `telemetry.LoadTargets`, `telemetry.Run`
- CLI: `sm-agent collect --targets <path> [--data-dir <path>]`
- Default data-dir: `/var/lib/sm-agent` or `SM_AGENT_DATA_DIR` if set (match config package convention)

- [ ] **Step 1:** If `os.Args[1] == "collect"`, parse flags, load targets, call Run, exit 0/1 — do not call config.Load for this path
- [ ] **Step 2:** Ensure docker lifecycle branch still takes precedence for start/stop/restart/status; no-subcommand path unchanged
- [ ] **Step 3:** `go test ./...` and `go build -o /tmp/sm-agent ./cmd/sm-agent`

---

### Task 4: Example targets + docs

**Files:**
- Create: `testdata/java-demo/targets.yaml`
- Modify: `testdata/java-demo/README.md`
- Modify: `go-agent/README.md`

- [ ] **Step 1:** Add targets.yaml pointing at 8081/8082/8083 `/hello`
- [ ] **Step 2:** Document manager demo: start java demos → `sm-agent collect` → show segregated files → break one service → re-collect
- [ ] **Step 3:** Note Docker lifecycle CLI is separate/not required for this proof

---

### Task 5: Verification

- [ ] **Step 1:** `go test ./...` (all green)
- [ ] **Step 2:** If Docker available: build/start java-demo, run collect against `/tmp/sm-demo-data`, show `telemetry/java1|java2|java3/latest.json`
- [ ] **Step 3:** If Docker unavailable: document manual steps; unit tests remain the automated proof

---

## Manual manager demo (from spec)

```bash
# terminals: java1/2/3 up (testdata/java-demo)
cd go-agent
go build -o sm-agent ./cmd/sm-agent
./sm-agent collect --targets ../testdata/java-demo/targets.yaml --data-dir /tmp/sm-demo-data
find /tmp/sm-demo-data/telemetry -type f | sort
cat /tmp/sm-demo-data/telemetry/java1/latest.json
```
