# 001 — Host-local vertical slice (go-agent)

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Execution gate:** Do **not** run Tasks 1–N product code until (1) this plan is **APPROVED**, and (2) the platform walking skeleton (Docker + `dp-remote`) is **accepted** — workplan parallelism B. Paper review of this plan may proceed now.

| | |
|---|---|
| **Component** | `go-agent` |
| **Design** | [`go-agent/docs/design.md`](../../go-agent/docs/design.md) · [`p2-wire-assumptions.md`](../../go-agent/docs/p2-wire-assumptions.md) · [`p3-mechanism-gaps.md`](../../go-agent/docs/p3-mechanism-gaps.md) · [`p2-baseline.md`](../../go-agent/docs/p2-baseline.md) |
| **Requirements** | REQ-001 (config layer) · REQ-005 (three runtimes — Docker first) · REQ-008 (host-local path) · REQ-009 / `I1` (no MI development) |
| **Decisions** | `D8` LOCKED (Go) · `B/D1` `B/D2` `B/D8` `B/D10` `B/D15` `B/D16` LOCKED · `SM-D3` `SM-D7` `SM-D14` `SM-D15` `SM-D21` `SM-D22` `SM-D24` `SM-D28` PROPOSED · `AG-D1`…`AG-D6` PROPOSED · `I1` `I4` `I6` |
| **Status** | **DRAFT** — not APPROVED; not executable as product code until skeleton + approval |
| **Workplan** | P4 of [`go-agent/docs/workplan.md`](../../go-agent/docs/workplan.md) |

**Goal:** Build the go-agent **001** binary for Docker host-local Cache: enroll → mTLS session → lease renew → one generation apply → report-actual → HTTP telemetry scrape/push → lease expiry/revoke untrusted reports — against the **surrogate wire** until backend replaces it.

**Architecture:** One Go module, one binary, both roles enabled (`AG-D1`). Control path uses gRPC unary enroll + bidi `Connect` to DP; telemetry/report use a **separate** channel (ASSUMED). Durable state under `$SM_AGENT_DATA_DIR` per P3. DP interactions go through a `wire.Client` interface so tests use an in-process fake DP.

**Tech Stack (ASSUMED picks for 001 — `AG-D6`):** Go **1.22+** · module path `github.com/solace/solution-manager/go-agent` (adjust if org path differs) · `google.golang.org/grpc` · `google.golang.org/protobuf` · `crypto/tls` · `crypto/x509` · `encoding/json` · Docker Engine API via `github.com/docker/docker/client` for introspection/restart · `log/slog` for structured logs · standard `testing` and `net/http/httptest` for scrape tests.

**Wire stand-in:** Until `contracts/*.proto` exists, check in **hand-written** Go structs + JSON (or provisional `.proto` under `go-agent/internal/wire/surrogate/` marked NON-AUTHORITATIVE) matching [`p2-wire-assumptions.md`](../../go-agent/docs/p2-wire-assumptions.md). When backend lands contracts, replace this package and run a compatibility checklist — do **not** put surrogate proto in monorepo `contracts/`.

---

## Global Constraints

- **No MI development (`I1`):** no Cache code/build-info/new endpoints; only external config file + restart + observe.
- **`I4`:** SM-D* remain PROPOSED — implement behind ASSUMED wire; do not claim ratification.
- **`I6`:** never log secret values, report bearer, or private keys; config hash over content written; secrets in files mode `0600`.
- **Surrogate wire:** kinds/fields from `p2-wire-assumptions.md`; persist from `p3-mechanism-gaps.md`.
- **001 runtime:** Docker host-local, single node, both roles on, Cache only, `manual` restart.
- **Out of 001:** K8s leader election (`AG-D3`), DaemonSet, multi-replica barriers, `solace_event`, auto cert rotation, HTTP/1.1 fallback, `scheduled`, GitOps ConfigMap writer, real secret-backend beyond writing resolved bytes from DP offer.
- **UTC only** for timestamps on wire and disk (`B/D15`).
- **Closed capability enum** (ASSUMED): `CAP_APPLY_CONFIG`, `CAP_LIFECYCLE`, `CAP_LOCAL_COLLECTOR_HTTP`, `CAP_REPORT_ONLY`.
- **Protocol version:** `protocol_version = 1` for 001.
- **Default data dir:** `/var/lib/sm-agent` (`$SM_AGENT_DATA_DIR`).
- **No product commits to main without skeleton gate** — local feature branch OK for development after approval.

---

## Scope

### In (must ship for 001)

1. Module scaffold, `cmd/sm-agent`, config flags, role flags (`control`, `telemetry`, default both).
2. Persist package: identity, `authority.json`, `cursor.json`, telemetry buffer, atomic write helpers.
3. Surrogate wire types + `FakeDP` for tests.
4. Enroll (token metadata, CSR PEM, pinned/system CA server TLS, store certs + report bearer).
5. Session open/accept + capability mismatch hard-fail.
6. Lease state machine: `ACTIVE`, `RENEW_DEGRADED`, `LEASE_EXPIRED`, `REVOKED`, `CLOCK_SUSPECT`, `RE_ENROLL_REQUIRED`; renew at 50% with backoff+jitter; persist authority before success.
7. Admission: refuse start of `write-config` / `restart` if `expires_at - now < ceiling + margin`; emit `ApplyProgress` status `ADMISSION_BLOCKED` (not an authority state).
8. Apply one generation: pre-flight launch/mount path, staging, atomic rename (`AG-D4`), restart via Docker API, cursor phases, `APPLY_INDETERMINATE` on ambiguous crash.
9. `DesiredStatePull` / `DesiredStateSnapshot` after lease recovery before apply.
10. `ReportActual` on telemetry channel with report bearer metadata; `untrusted=true` when authority is expired/revoked/clock-suspect.
11. Cache introspection per P3 §7; emit `OK` / `VERSION_DRIFT` / `VERSION_UNKNOWN` / `MISSING…` honestly.
12. HTTP scrape of declared Cache actuator/metrics URLs; normalize minimal envelope; buffer; push `TelemetryBatch` with gap markers.
13. `DrainRequest` / `Teardown` handling.
14. Integration test suite against `FakeDP` + optional docker-compose Cache fixture (document as optional if Cache image unavailable).

### Out (explicit)

- Real `contracts/` ownership, buf breaking CI, Java DP.
- K8s, systemd drivers, multi-replica, broker collector.
- Hot reload, rolling health-gated restart for Cache.
- Agent self-upgrade / fleet canary.

---

## Prerequisites

| Prerequisite | Status for this plan |
|---|---|
| Component design + AG-D* | Present under `go-agent/docs/` |
| Surrogate wire + audit remediation | Present (`p2-*`) |
| P3 persist + introspection | Present (`p3-mechanism-gaps.md`) |
| Authoritative `contracts/*.proto` | **Not required for 001 coding against surrogate**; required to mark P2 “protocol specified” |
| Walking skeleton accepted | **Required before merging/running product agent in shared envs (P5 gate)** |
| SM-D* ratification | **Not required** to write code; flag PROPOSED in logs/docs (`I4`) |
| Digest→version mapping data from DP | **ASSUMED** FakeDP supplies a table; production needs DP |

---

## Target file map

```text
go-agent/
  go.mod
  go.sum
  cmd/sm-agent/main.go
  internal/config/config.go
  internal/persist/{dir.go,atomic.go,authority.go,cursor.go,telemetrybuf.go,identity.go}
  internal/wire/surrogate/{types.go,codec.go,README.md}   # NON-AUTHORITATIVE
  internal/wire/{client.go,grpc_client.go,metadata.go}
  internal/enrollment/{csr.go,enroll.go}
  internal/session/{machine.go,renew.go,admission.go}
  internal/apply/{preflight.go,writer.go,restart_docker.go,engine.go}
  internal/report/{reporter.go}
  internal/introspect/{cache_docker.go,mapping.go}
  internal/collect/{scrape.go,normalize.go,pusher.go}
  internal/app/{app.go,run.go}
  internal/testutil/fakedp/{server.go}
  testdata/fixtures/...
  README.md   # extend with build/run
```

---

## Tasks

### Task 1: Module scaffold + config

**Files:**
- Create: `go-agent/go.mod`, `go-agent/cmd/sm-agent/main.go`, `go-agent/internal/config/config.go`, `go-agent/internal/config/config_test.go`

**Interfaces:**
- Produces: `config.Load(args []string) (Config, error)`
- `Config` fields (all required unless noted):

```go
type Config struct {
    DataDir            string   // default /var/lib/sm-agent; env SM_AGENT_DATA_DIR
    DPEnrollURL        string   // https://dp.example:443 — unary enroll endpoint host
    DPConnectAddr      string   // host:443 for bidi Connect
    DPTelemetryAddr    string   // separate report/telemetry endpoint
    DPServerCAFile     string   // optional pin; empty = system roots
    EnrollmentToken    string   // single-use; prefer env SM_AGENT_ENROLL_TOKEN
    Roles              []string // "control","telemetry"; default both
    AgentSoftwareVersion string
    ProtocolVersion    uint32   // must be 1
    LogLevel           string   // slog level
}
```

- [ ] **Step 1: Write failing test** `TestLoadDefaults`

```go
func TestLoadDefaults(t *testing.T) {
    cfg, err := config.Load([]string{"sm-agent"})
    if err != nil { t.Fatal(err) }
    if cfg.DataDir != "/var/lib/sm-agent" { t.Fatalf("got %q", cfg.DataDir) }
    if cfg.ProtocolVersion != 1 { t.Fatalf("got %d", cfg.ProtocolVersion) }
}
```

- [ ] **Step 2: Run** `cd go-agent && go test ./internal/config/ -run TestLoadDefaults -v` — expect FAIL (no module/package).

- [ ] **Step 3: Implement** `go.mod` (`module …/go-agent`, `go 1.22`), `config.Load` using `flag` + env overrides (`SM_AGENT_DATA_DIR`, `SM_AGENT_ENROLL_TOKEN`), default roles `control`+`telemetry`.

- [ ] **Step 4: Re-run test** — expect PASS.

- [ ] **Step 5: Commit** `feat(go-agent): scaffold module and config loader`

---

### Task 2: Atomic persist helpers + directory layout

**Files:**
- Create: `internal/persist/atomic.go`, `dir.go`, `atomic_test.go`, `dir_test.go`

**Interfaces:**
- Produces: `persist.EnsureLayout(root string) error` creating subdirs from P3 §2 with `0700`.
- Produces: `persist.WriteFileAtomic(path string, data []byte, mode os.FileMode) error` — write `path.tmp`, `Sync`, `Rename`, chmod `mode` (default `0600`).

- [ ] **Step 1: Failing test** `TestWriteFileAtomic_RoundTrip` using `t.TempDir()`.

- [ ] **Step 2: Run** — FAIL.

- [ ] **Step 3: Implement** EnsureLayout + WriteFileAtomic; refuse if parent missing until EnsureLayout.

- [ ] **Step 4: PASS** + test that mode is `0600`.

- [ ] **Step 5: Commit** `feat(go-agent): atomic persist and data dir layout`

---

### Task 3: Authority + cursor persistence

**Files:**
- Create: `internal/persist/authority.go`, `cursor.go`, `*_test.go`

**Interfaces:**
- Types must match P3 §§3–4 field names exactly (JSON tags snake_case as in P3):

```go
type Authority struct {
    TargetID               string            `json:"target_id"`
    BindingIDs             []string          `json:"binding_ids"`
    AgentRevocationEpoch   uint64            `json:"agent_revocation_epoch"`
    FleetRevocationEpoch   uint64            `json:"fleet_revocation_epoch"`
    IssuedAt               time.Time         `json:"issued_at"`
    ExpiresAt              time.Time         `json:"expires_at"`
    Audience               string            `json:"audience"`
    Nonce                  string            `json:"nonce"`
    Capabilities           []string          `json:"capabilities"`
    DPWallTime             time.Time         `json:"dp_wall_time"`
    ClockOffsetMs          int64             `json:"clock_offset_ms"`
    VerbCeilings           map[string]string `json:"verb_ceilings"` // verb -> duration string e.g. "2m"
    AdmissionMargin        string            `json:"admission_margin"`  // e.g. "30s"
    AuthorityState         string            `json:"authority_state"`
    ReportBearerPath       string            `json:"report_bearer_path"`
}

type Cursor struct {
    LastAppliedGeneration     uint64    `json:"last_applied_generation"`
    LastAppliedIdempotencyKey string    `json:"last_applied_idempotency_key"`
    LastAppliedConfigSHA256   string    `json:"last_applied_config_sha256"`
    InFlight                  *InFlight `json:"in_flight"`
    SupersededCleaned         []uint64  `json:"superseded_cleaned"`
}

type InFlight struct {
    Generation       uint64    `json:"generation"`
    IdempotencyKey   string    `json:"idempotency_key"`
    BindingID        string    `json:"binding_id"`
    Phase            string    `json:"phase"` // PREFLIGHT|STAGING|RENAMED|RESTARTING|INDETERMINATE
    StepDeadline     time.Time `json:"step_deadline"`
    StagingPath      string    `json:"staging_path"`
    TargetConfigPath string    `json:"target_config_path"`
}
```

- Produces: `SaveAuthority(root string, a Authority) error`, `LoadAuthority(root string) (Authority, error)`
- Produces: `SaveCursor`, `LoadCursor` likewise under `apply/cursor.json`.

- [ ] **Step 1: Tests** round-trip; corrupt JSON returns error; missing file returns typed `ErrNotFound`.

- [ ] **Step 2–4: Implement + PASS.**

- [ ] **Step 5: Commit** `feat(go-agent): authority and apply cursor persistence`

---

### Task 4: Surrogate wire types + FakeDP

**Files:**
- Create: `internal/wire/surrogate/{types.go,codec.go,README.md}`, `internal/testutil/fakedp/server.go`, tests

**Interfaces:**
- Document in `surrogate/README.md`: **NON-AUTHORITATIVE**; superseded by `contracts/`.
- Define Go structs for every **001=yes** kind in assumptions §3.1 with fields from §4 (explicit JSON/protobuf tags). Minimum message set:

| Kind | Required fields (explicit) |
|---|---|
| `EnrollRequest` | `protocol_version`, `csr_pem`, `capabilities[]`, `agent_software_version` |
| `EnrollResponse` | `client_cert_pem`, `trust_bundle_pem`, `report_bearer`, `dp_instance_id`, `clock_skew_tolerance`, optional `lease` |
| `SessionOpen` | `capabilities[]`, `protocol_version` |
| `SessionAccept` | `assigned_target_id`, `required_capabilities[]` |
| `CapabilityMismatch` | `missing[]`, `message` |
| `LeaseRenew` | `nonce`, `clock_offset_ms` |
| `LeaseUpdate` | full lease claims + `dp_wall_time` + `verb_ceilings` + `admission_margin` |
| `Revoke` | `agent_revocation_epoch`, `reason` |
| `GenerationOffer` | `generation`, `idempotency_key`, `binding_id`, `config_sha256`, `payload` bytes OR `blob_ref`, `secret_render_locus` (`DP_PLAINTEXT`\|`AGENT_REFS`), `supersedes_generation`, restart verb metadata |
| `ApplyProgress` | `generation`, `idempotency_key`, `status`, `step_deadline`, `reason` |
| `DesiredStatePull` | `last_applied_generation` |
| `DesiredStateSnapshot` | embedded `GenerationOffer` equivalent + mapping table `digest_to_version map[string]string` |
| `ReportActual` | `config_sha256`, `restart_observed`, `version_state`, `version_evidence`, `untrusted`, `generation` |
| `TelemetryBatch` | `samples[]` (`series_key`, `ts`, `value_numeric`, `gap`, `dedupe_key`, `source_key`), `dropped_count` |
| `DrainRequest` | `reason` |
| `Teardown` | `reason` (`OPERATOR`\|`RE_ENROLL`\|`SHUTDOWN`) |
| `Error` | `code`, `message` |

- Produces: `type Client interface { Enroll(ctx, EnrollRequest) (EnrollResponse, error); Connect(ctx) (ControlStream, error); OpenTelemetry(ctx) (TelemetryStream, error) }`
- `ControlStream`: `SendApplyProgress`, `SendDesiredStatePull`, `SendTeardown`, `Recv() (ControlMsg, error)` multiplexing LeaseUpdate/Revoke/GenerationOffer/Snapshot/Drain/Error/SessionAccept/CapabilityMismatch
- `FakeDP`: in-memory; scriptable sequence for tests.

- [ ] **Step 1: Test** FakeDP enroll returns cert + bearer; Connect yields SessionAccept then LeaseUpdate.

- [ ] **Step 2–4: Implement types + FakeDP + PASS.**

- [ ] **Step 5: Commit** `feat(go-agent): surrogate wire types and FakeDP`

---

### Task 5: Enrollment + identity store

**Files:**
- Create: `internal/enrollment/csr.go`, `enroll.go`, `*_test.go`, `internal/persist/identity.go`

**Interfaces:**
- `enrollment.GenerateKeyAndCSR() (keyPEM, csrPEM []byte, err error)` — RSA-2048 or ECDSA P-256 (pick **ECDSA P-256**, document in comment).
- `enrollment.Run(ctx, cfg, client) error` — builds EnrollRequest with token in metadata key `x-sm-enroll-token` (ASSUMED); on response writes `identity/client.crt`, `client.key`, `trust-bundle.pem`, `report.bearer` via atomic writes; if response includes lease, `SaveAuthority`.
- Server TLS: use `DPServerCAFile` if set, else system cert pool.

- [ ] **Step 1: Test** CSR non-empty; FakeDP enroll persists files with `0600`.

- [ ] **Step 2–4: Implement + PASS.** Never log bearer or key material.

- [ ] **Step 5: Commit** `feat(go-agent): enrollment CSR and identity persistence`

---

### Task 6: Session + authority state machine + renew

**Files:**
- Create: `internal/session/machine.go`, `renew.go`, `admission.go`, `*_test.go`

**Interfaces:**
- `type State string` constants exactly: `ACTIVE`, `RENEW_DEGRADED`, `LEASE_EXPIRED`, `REVOKED`, `CLOCK_SUSPECT`, `RE_ENROLL_REQUIRED` (no `ADMISSION_BLOCKED` here).
- `Machine.HandleLeaseUpdate(u)`, `HandleRevoke(r)`, `DeriveOnBoot(a Authority, now time.Time, skewTol time.Duration) State`
- Clock: if `|offset| > skewTol` → `CLOCK_SUSPECT` (treat like expired for apply).
- `RenewLoop(ctx)`: sleep until `issued + (expires-issued)/2`; on failure exponential backoff 1s…60s with full jitter per P3 §6; persist authority **before** returning success to caller.
- `Admission.CanStart(verb string, now time.Time) error` — parse ceilings; if insufficient remaining → return typed `ErrAdmissionBlocked` (caller maps to ApplyProgress status).

- [ ] **Step 1: Table tests** for each state transition in assumptions §5 (001-relevant rows).

- [ ] **Step 2–4: Implement + PASS.**

- [ ] **Step 5: Commit** `feat(go-agent): lease authority state machine and renew loop`

---

### Task 7: Apply engine (preflight, write, restart, indeterminate)

**Files:**
- Create: `internal/apply/preflight.go`, `writer.go`, `restart_docker.go`, `engine.go`, `*_test.go`

**Interfaces:**
- `Preflight(ctx, bindingID, desiredLaunchSpec) (targetConfigPath string, err error)` — ASSUMED input: desired state includes Docker inspect hints + path `/app/external/spring/config/application.yml` for Cache (document constant `DefaultCacheConfigPath`); refuse symlink escape outside declared dir (`AG-D4`).
- `WriteConfigAtomic(targetPath string, payload []byte) (sha256hex string, err error)` — stage under `apply/staging/`, fsync, rename into target; update cursor phases `STAGING`→`RENAMED`.
- `RestartCacheContainer(ctx, containerID string) error` — Docker API restart; cursor `RESTARTING`.
- `Engine.Apply(ctx, offer GenerationOffer) error` — check State==ACTIVE; admission for write-config then restart; ignore offer if `RENEW_DEGRADED`; on crash mid-rename path expose `APPLY_INDETERMINATE` and set cursor phase `INDETERMINATE`; send ApplyProgress statuses: `ACCEPTED`, `PREFLIGHT_FAILED`, `STAGED`, `RESTARTING`, `SUCCEEDED`, `FAILED`, `APPLY_INDETERMINATE`, `ADMISSION_BLOCKED`.
- Secret locus: if `DP_PLAINTEXT`, write payload bytes as received; if `AGENT_REFS`, **001 returns Failed** with reason `secret_locus_agent_refs_unsupported` (explicit — no silent half-support).

- [ ] **Step 1: Tests** with temp dirs: atomic write; symlink escape refused; admission blocked; indeterminate when phase RENAMED and restart not confirmed (simulate).

- [ ] **Step 2–4: Implement + PASS.**

- [ ] **Step 5: Commit** `feat(go-agent): config apply engine with crash-safe cursor`

---

### Task 8: Desired state recovery pull

**Files:**
- Modify: `internal/session/machine.go`, `internal/apply/engine.go`, `internal/app/run.go`
- Test: `internal/app/recovery_test.go`

**Interfaces:**
- After boot or transition into `ACTIVE` from `LEASE_EXPIRED` via successful renew: **must** `DesiredStatePull{LastAppliedGeneration: cursor.LastAppliedGeneration}` and wait for `DesiredStateSnapshot` before accepting new apply from cache alone.
- Snapshot may include `digest_to_version` map → pass to introspect mapping store.

- [ ] **Step 1: FakeDP test** — expire lease, renew, assert Pull sent before Apply.

- [ ] **Step 2–4: Implement + PASS.**

- [ ] **Step 5: Commit** `feat(go-agent): desired-state pull after lease recovery`

---

### Task 9: ReportActual + introspect Cache

**Files:**
- Create: `internal/report/reporter.go`, `internal/introspect/cache_docker.go`, `mapping.go`, tests

**Interfaces:**
- `introspect.ObserveCache(ctx, containerID string, mapping map[string]string) (state string, evidence string, err error)` — order P3 §7.1; never invent version from mutable tag.
- `report.Send(ctx, stream, ReportActual)` — attach metadata `x-sm-report-bearer: <token>`; set `Untrusted: true` iff state ∈ {LEASE_EXPIRED, REVOKED, CLOCK_SUSPECT}.
- Fields: `config_sha256` from cursor; `restart_observed` from Docker inspect StartedAt change after apply; `version_state` / `version_evidence` from introspect.

- [ ] **Step 1: Tests** mapping hit → OK; unknown digest → VERSION_UNKNOWN; untrusted flag when expired.

- [ ] **Step 2–4: Implement + PASS.**

- [ ] **Step 5: Commit** `feat(go-agent): report-actual and Cache introspection`

---

### Task 10: Telemetry scrape, buffer, push

**Files:**
- Create: `internal/persist/telemetrybuf.go`, `internal/collect/scrape.go`, `normalize.go`, `pusher.go`, tests

**Interfaces:**
- Scrape ASSUMED URL from desired state key `telemetry.scrape_urls[]` (FakeDP fixture includes Cache actuator URL).
- Normalize: for 001, accept Prometheus text or JSON gauge/counter → samples with `series_key`, `ts` UTC, `value_numeric`, `source_key=hostname`, `dedupe_key=series_key+"|"+ts`.
- Buffer per P3 §5 (8 MiB segments, 256 MiB / 1h drop oldest, gap flag on batch).
- Pusher sends `TelemetryBatch` on **telemetry channel only**; include `dropped_count`.

- [ ] **Step 1: Tests** `net/http/httptest` scrape fixture; buffer drop increments; gap true after drop.

- [ ] **Step 2–4: Implement + PASS.**

- [ ] **Step 5: Commit** `feat(go-agent): local-collector scrape buffer and push`

---

### Task 11: App wiring + DrainRequest/Teardown + main

**Files:**
- Create: `internal/app/app.go`, `run.go`, `cmd/sm-agent/main.go`
- Modify: README build/run section

**Interfaces:**
- `app.Run(ctx, cfg) error` — EnsureLayout → if no client cert then Enroll → Connect control + OpenTelemetry → SessionOpen → renew loop + apply loop + collect loop; on `DrainRequest` stop apply, flush reports untrusted if needed, send `Teardown`, exit 0.
- Flags documented in README: all `Config` fields; example enroll command.
- Roles: if `telemetry` only, skip apply; if `control` only, skip scrape (still may report).

- [ ] **Step 1: Integration test** `TestApp_EnrollApplyReport` with FakeDP end-to-end (no real Docker if restart mocked).

- [ ] **Step 2–4: Implement + PASS** `go test ./...`

- [ ] **Step 5: Commit** `feat(go-agent): wire app mainloop for 001 vertical slice`

---

### Task 12: Verification matrix + plan review sign-off

**Files:**
- Create: `go-agent/docs/001-verification.md` (checklist results template)
- Modify: this plan **Review** section when done

**Verification (must all pass before Status→DONE):**

| # | Criterion | How |
|---|---|---|
| V1 | Enroll stores certs+bearer mode 0600 | unit + FakeDP |
| V2 | Renew at ~50%; backoff on failure | unit fake clock |
| V3 | RENEW_DEGRADED ignores new GenerationOffer | unit |
| V4 | Admission blocks short lease | unit |
| V5 | Atomic config write + symlink refuse | unit |
| V6 | APPLY_INDETERMINATE on RENAMED without restart confirm | unit |
| V7 | Pull after LEASE_EXPIRED recovery | FakeDP |
| V8 | Untrusted reports when expired | unit |
| V9 | VERSION_UNKNOWN when no mapping | unit |
| V10 | Telemetry gaps after buffer drop | unit |
| V11 | `go test ./...` green | CI/local |
| V12 | No secrets in logs (grep test hooks) | unit with slog test handler |

- [ ] **Step 1: Run** full `go test ./...` and fill `001-verification.md`.

- [ ] **Step 2: Run** plan-review checklist [`docs/checklists/plan-review.md`](../checklists/plan-review.md) against this document; fix gaps.

- [ ] **Step 3: Commit** `docs(go-agent): 001 verification results`

---

## Verification (plan complete)

Plan is **DRAFT** until a human marks **APPROVED**. Execution of Tasks 1–12 product code waits on skeleton acceptance + approval. Surrogate wire replacement: add a follow-up plan when `contracts/` lands.

## Review

_(Filled after execution: deviations from this plan and why.)_

---

## Spec coverage self-check

| Spec source | Tasks |
|---|---|
| Workplan §3 steps 1–6 | 5–11 |
| P2 assumptions 001 kinds | 4–11 |
| P3 persist + backoff + Cache introspect | 2,3,6,9,10 |
| AG-D1 roles | 1,11 |
| AG-D4 writes | 7 |
| AG-D5 HTTP only | 10 |
| I1 / no build-info | 9 |
| Out-of-scope K8s/solace_event | Global Constraints |

**Placeholder scan:** none intentional. Secret agent-refs unsupported is an explicit Failed path, not TBD.

---

## Changelog

| Date | Change |
|---|---|
| 2026-09-02 | Initial DRAFT P4 plan 001 host-local vertical slice. |
