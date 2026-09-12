# Design: sm-agent local telemetry collect (filesystem demo)

**Date:** 2026-09-12  
**Status:** Approved in brainstorming (pending user review of this written spec)  
**Component:** `go-agent`

## Goal

Prove to stakeholders that **sm-agent** can:

1. Run as the agent process  
2. Extract metrics/health payloads from N application services over HTTP  
3. Store results on the **local filesystem**  
4. Validate each sample  
5. Segregate data per service (`java1` → its files, `java2` → its files, …)

Java services are **examples only** — any HTTP scrape targets work.  
**No Data Plane push** in this demo (storage = local files only).

```text
sm-agent (telemetry)
    → HTTP scrape targets
    → validate
    → $SM_AGENT_DATA_DIR/telemetry/<service-id>/
```

## Context vs prior work

| Prior direction | Disposition for this demo |
|-----------------|---------------------------|
| VM `java -jar` / `kill -9` | Incorrect path; already removed |
| Docker start/stop/restart/status | Control-role capability; **keep code**, not the success criteria |
| This telemetry filesystem path | **Success criteria** for manager demo |

Product design already separates **control** and **telemetry** roles (`AG-D1`). This slice implements a **minimal local telemetry collector** aligned with `local-collector` / HTTP scrape (`AG-D5`), without enroll/lease/DP ingest.

## Decisions locked

| Topic | Choice |
|-------|--------|
| Storage | Local filesystem only under agent data dir |
| DP push | Out of scope |
| Acquisition | HTTP GET per configured target |
| Segregation | One directory per target `id` |
| Service type | Opaque; not Java-specific |
| Docker lifecycle CLI | Leave as-is; unused for proof |

## Targets configuration

File (YAML), e.g. `targets.yaml`:

```yaml
targets:
  - id: java1
    url: http://127.0.0.1:8081/hello
  - id: java2
    url: http://127.0.0.1:8082/hello
  - id: java3
    url: http://127.0.0.1:8083/hello
```

- `id`: filesystem-safe identifier (segregation key)  
- `url`: absolute HTTP(S) scrape URL  
- Example ports match `testdata/java-demo` compose; any URLs may be substituted  

## Filesystem layout

Under `$SM_AGENT_DATA_DIR` (default `/var/lib/sm-agent`, already used by config):

```text
telemetry/
  <id>/
    latest.json       # last successful sample only
    history/
      <utc-timestamp>.json   # optional append of successful samples
  _failures/
    <id>-<utc-timestamp>.json  # failed scrape/validation (demo clarity)
```

Atomic write for `latest.json` (temp + rename), consistent with existing persist helpers where practical.

## Sample record (JSON)

```json
{
  "service_id": "java1",
  "url": "http://127.0.0.1:8081/hello",
  "scraped_at": "2026-09-12T10:00:00Z",
  "http_status": 200,
  "content_type": "text/plain; charset=utf-8",
  "body": "Hello from java1",
  "ok": true,
  "error": ""
}
```

On failure: `ok: false`, `error` set, body may be truncated/empty; do **not** replace `latest.json`.

## Validation rules (demo-simple)

A sample is valid when all hold:

1. Request completes without transport error (timeout bounded, e.g. 5s)  
2. HTTP status is 2xx  
3. Response body is non-empty  
4. Body size ≤ configured max (e.g. 1 MiB)  

No schema registry / PromQL in this slice.

## Package / CLI

**Package:** `go-agent/internal/telemetry/`

- Load targets file  
- Scrape each target  
- Validate  
- Write segregated files  

**CLI:**

```bash
sm-agent collect --targets /path/to/targets.yaml [--data-dir /var/lib/sm-agent]
```

- One scrape cycle across all targets, then exit (clear demo)  
- **No subcommand:** preserve existing long-running config + signal-wait behavior exactly  
- Docker lifecycle subcommands (`start|stop|restart|status`) remain available; not required for this demo  

Exit code: non-zero if **any** target fails scrape/validation (so demos can show partial success via files + logs).

## Demo checklist (manager)

1. Ensure example services respond (e.g. `java1/2/3` from `testdata/java-demo`, or any HTTP apps).  
2. Run `sm-agent collect --targets targets.yaml --data-dir /tmp/sm-agent-data`.  
3. Show segregated files under `telemetry/java1|java2|java3/latest.json`.  
4. Show `ok: true` and UTC `scraped_at`.  
5. Stop one service; re-run collect; show that service under `_failures/` (or error log) while others still refresh `latest.json`.  

## Out of scope

- DP enroll, lease, telemetry push, report-actual wire protocol  
- Expanding Docker lifecycle features  
- Prometheus remote-write, OpenTelemetry export  
- Discovering targets via Docker API (targets stay config-driven for this demo)  
- Changing backend/frontend or unrelated monorepo components  

## Implementation order (for later plan)

1. `internal/telemetry` — types, validate, store, scrape (unit tests with `httptest`)  
2. Wire `collect` subcommand in `cmd/sm-agent` (thin)  
3. Example `targets.yaml` under `testdata/java-demo/`  
4. README section for manager demo commands  
5. `go test ./...` + manual collect against running demo services when Docker/HTTP targets available  
