# go-agent Control Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the `host-local` agent's control path — enroll, session, lease, heartbeat, command journal with Docker execution, actual-state reports — against the agent-gateway wire, runnable end-to-end against a scripted fake gateway.

**Architecture:** An event-driven reactor: one inbox, pure reducers (`session → authority → commands → reports`) returning `(state, effects)`, effects executed in the order `Persist → Send → RunOp → timers`, one runtime worker. A DRAFT `.proto` transcribed from the gateway design's §4.3 is the wire; only `internal/wire` touches generated code. A scenario-driven fake gateway is the test double and the demo.

**Tech Stack:** Go 1.26 (`go.mod`), `google.golang.org/grpc` + `google.golang.org/protobuf` (buf codegen), `crypto/tls` + `crypto/x509`, `github.com/docker/docker` v27 Engine API client, `github.com/google/uuid` (UUIDv7), `github.com/prometheus/client_golang`, `gopkg.in/yaml.v3`, `log/slog`.

**Spec:** `docs/superpowers/specs/2026-09-21-go-agent-control-path-design.md` (read it first; the plan argues from it). Authority above the spec: `docs/design/backend/agent-gateway/design.md` §4, `docs/design/backend/agent-gateway/decisions.md`, `docs/design/go-agent/decisions.md` (`AG-D1`–`AG-D12`).

## Global Constraints

- Module `github.com/solace/solution-manager/go-agent`, `go 1.26.4` — never lower the floor.
- **Reducers are pure:** no clock reads, no I/O, no goroutines, no randomness; every impurity comes through `reactor.Env` (`Now`, `Offset`, `NewID`, `Jitter`, `Limits`).
- **Effect order per event is `Persist → Delete → Send → RunOp → timers → connection`.** A failed `Persist` is fatal.
- **`internal/wire` and `internal/wire/codec` are the only packages that import `internal/wire/pb`.**
- All lease/time arithmetic uses DP time: `dpNow = env.Now + env.Offset`.
- Files under `$SM_AGENT_DATA_DIR`: directories `0700`, files `0600`, written only through `store.WriteAtomic` (temp → fsync → chmod → rename → fsync parent).
- Secrets (`payload`, `staged`, `previous`, enrollment token, private key, nonces) never appear in logs; they pass through `slog.LogValuer` redaction.
- Stdlib `flag` + env for configuration (`AG-D7`); `log/slog` for logging (`AG-D8`); no Viper, no third-party loggers.
- The DRAFT `.proto` lives under `go-agent/proto/`, never under `contracts/`. Proto enum values carry the enum-name prefix (`ERROR_CODE_*`) because proto scoping requires it; the design's bare names are the semantic names and the Go `msg` package uses the bare names.
- Every commit message ends with `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- Run `go vet ./... && go test ./...` before every commit; integration tests are `-tags integration`, Docker E2E is `-tags docker`.

---

## File Structure

Two refinements of spec §2.1, both to avoid import cycles: the internal message catalogue lives in `internal/msg` (shared by reducers, `wire` and `fakegw`), and the reactor vocabulary (`Event`, `Effect`, `Env`, `TimerKind`) lives in `internal/reactor` so `authority`/`commands`/`reports` can import it without importing the loop.

| Path | Responsibility |
|---|---|
| `oldGo-Agent/` | the pre-rewrite scaffold, archived with its history — nested `go.mod` keeps it out of `go build ./...`; reference only, no authority |
| `Makefile` | `proto`, `build`, `test`, `test-integration`, `test-docker`, `fake-gateway` targets |
| `buf.yaml`, `buf.gen.yaml` | buf module rooted at `proto/agentgateway/v1draft`, generating into `internal/wire/pb` |
| `proto/agentgateway/v1draft/agent_gateway.proto` | DRAFT transcription of gateway §4.3/§4.11 |
| `internal/wire/pb/*.pb.go` | generated; committed |
| `internal/msg/msg.go` | the message catalogue as Go structs; enums as string types with bare names |
| `internal/reactor/reactor.go` | `Event`, `Effect`, `Env`, `TimerKind` |
| `internal/store/store.go` | `WriteAtomic`, `Store` (root, layout), `JSON` record, `LoadJSON`, `Blob`, `ErrNotFound`, `ErrSchemaVersion` |
| `internal/identity/identity.go` | key, CSR (persisted before first enroll), cert chain, trust bundle, thumbprint, `tls.Config` builders |
| `internal/clock/clock.go` | latest `clock_offset_ms` / `clock_state` |
| `internal/agent/state.go` | composite `State`, top-level `Reduce` |
| `internal/agent/session.go` | the `session` reducer (phases, enroll, dial, backoff, halt, heartbeat cadence) |
| `internal/agent/loop.go` | inbox, effect executor, timers, connection/reader/writer, runtime worker |
| `internal/agent/backoff.go` | exponential backoff with full jitter |
| `internal/agent/admin.go` | loopback `/healthz` + `/metrics` |
| `internal/authority/authority.go` | lease reducer |
| `internal/commands/commands.go`, `journal.go` | command reducer, journal records, resume |
| `internal/reports/reports.go` | report reducer, `item_id`/`sequence`, registry |
| `internal/runtime/runtime.go` | `Adapter` interface and value types |
| `internal/runtime/configwriter/writer.go` | atomic config write with symlink refusal and rollback copy |
| `internal/runtime/docker/docker.go` | Docker `Adapter` |
| `internal/wire/codec/codec.go` | pb ↔ `msg` both directions |
| `internal/wire/client.go` | dial, `Enroll`, `Connect` stream, reader/writer |
| `internal/wire/classify.go` | error-code classes (§8.4) |
| `internal/fakegw/*.go` | CA, enroll handler, scenario engine, transcript |
| `cmd/sm-agent/main.go`, `cmd/fake-gateway/main.go` | binaries |
| `internal/agent/integration_test.go` (`-tags integration`) | scenarios against in-process `fakegw` |
| `internal/runtime/docker/e2e_test.go` (`-tags docker`) | real container |

---

## Phase P1 — the agent reaches `ACTIVE` against the fake gateway

### Task 1: Archive the old scaffold, add dependencies and tooling

**Files:**
- Move (never delete): `internal/config/`, `internal/docker/`, `internal/persist/`, `internal/telemetry/`, `cmd/sm-agent/main.go`, `../testdata/java-demo/` → `oldGo-Agent/`
- Create: `oldGo-Agent/go.mod`, `oldGo-Agent/README.md`, `Makefile`, `cmd/sm-agent/main.go` (placeholder that compiles), `.gitignore` (append)
- Modify: `go.mod`, `go.sum`

**Interfaces:**
- Produces: a module that builds with the new dependencies available; `make test` runs `go vet` + `go test`; the superseded scaffold preserved and readable under `go-agent/oldGo-Agent/` but excluded from the build.

- [ ] **Step 1: Move the old scaffold into `oldGo-Agent/`**

The scaffold is structurally incompatible with the gateway design (journal phases, config topology, the missing parent-directory fsync), but it still documents how the Docker Engine API was driven and what the local telemetry shape was. It is archived, not deleted.

```bash
cd go-agent
mkdir -p oldGo-Agent/cmd/sm-agent
git mv internal oldGo-Agent/internal
git mv cmd/sm-agent/main.go oldGo-Agent/cmd/sm-agent/main.go
git mv ../testdata/java-demo oldGo-Agent/java-demo
```

- [ ] **Step 2: Exclude the archive from the build**

An archived directory inside a Go module is still compiled by `go build ./...`, and these packages no longer resolve their own imports (they import `…/go-agent/internal/persist`, which has moved). Giving the archive its own `go.mod` makes it a nested module, which the parent's `./...` skips.

`oldGo-Agent/go.mod`:

```
// Archived scaffold — a nested module so `go build ./...` in go-agent skips it.
// Not built, not tested, not maintained. See README.md.
module github.com/solace/solution-manager/go-agent/oldGo-Agent

go 1.26.4
```

`oldGo-Agent/README.md`:

```markdown
# oldGo-Agent — archived scaffold

The `go-agent` packages as they stood before the rewrite against the
[agent-gateway design](../../docs/design/backend/agent-gateway/design.md):
`internal/config`, `internal/docker`, `internal/persist`, `internal/telemetry`,
the two demo CLIs in `cmd/sm-agent/main.go`, and the `java-demo` fixtures.

**Not built, not tested, not maintained.** It is a nested Go module purely so the
parent module's `go build ./...` skips it; its imports no longer resolve and it is
not expected to compile.

**Kept for reference, superseded for authority.** The gateway design's fork
inventory named three structural incompatibilities: the journal phases
(`PREFLIGHT|STAGING|RENAMED|RESTARTING|INDETERMINATE` against the baseline
`RECEIVED→STAGED→PERSISTED→REPORTED`), a config shape carrying a separate
telemetry endpoint where the design has one connection, and `WriteFileAtomic`
without the parent-directory fsync that design §5.1 requires.

Still worth reading here: how `internal/docker` drove the Engine API, and the
scrape/validate/store shape in `internal/telemetry` that slice 2's telemetry role
will need. What was correct was re-derived in the new packages — the atomic write
(now with the parent fsync) and the symlink-escape refusal — not copied.
```

- [ ] **Step 3: Write a compiling placeholder main**

`cmd/sm-agent/main.go`:

```go
package main

import (
	"fmt"
	"os"
)

func main() {
	fmt.Fprintln(os.Stderr, "sm-agent: not wired yet")
	os.Exit(2)
}
```

- [ ] **Step 4: Add dependencies**

```bash
go get google.golang.org/grpc@latest google.golang.org/protobuf@latest \
       github.com/google/uuid@latest github.com/prometheus/client_golang@latest
go mod tidy
```

Expected: `go.mod` now lists `google.golang.org/grpc`, `google.golang.org/protobuf`, `github.com/google/uuid`, `github.com/prometheus/client_golang` as direct requirements alongside the existing `github.com/docker/docker v27.1.2+incompatible`, `github.com/containerd/errdefs`, `gopkg.in/yaml.v3`.

- [ ] **Step 5: Install proto tooling**

```bash
go install github.com/bufbuild/buf/cmd/buf@latest
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
buf --version && protoc-gen-go --version && protoc-gen-go-grpc --version
```

Expected: three version strings. `$(go env GOPATH)/bin` must be on `PATH`.

- [ ] **Step 6: Write the Makefile**

`Makefile`:

```makefile
.PHONY: proto build test test-integration test-docker fake-gateway

proto:
	buf generate

build:
	go build -o bin/sm-agent ./cmd/sm-agent
	go build -o bin/fake-gateway ./cmd/fake-gateway

test:
	go vet ./...
	go test ./...

test-integration:
	go test -tags integration ./internal/agent/ -run Integration -v

test-docker:
	go test -tags docker ./internal/runtime/docker/ -run E2E -v

fake-gateway: build
	./bin/fake-gateway --listen 127.0.0.1:8443 --scenario internal/fakegw/scenarios/demo.yaml --enroll-token demo-token --ca-out bin/ca.pem
```

Append to `.gitignore`:

```
/bin/
```

- [ ] **Step 7: Verify the module builds and the archive is excluded**

```bash
go build ./... && go vet ./...
go list ./... | grep -c oldGo-Agent   # expect 0: the nested module is not in ./...
git log --oneline -1 -- oldGo-Agent/internal/docker/client.go   # history followed the move
```

Expected: the build is clean, `grep -c` prints `0`, and the archived file still has its history.

- [ ] **Step 8: Commit**

```bash
git add -A .
git add ../testdata
git commit -m "go-agent: archive the demo scaffold, add wire/uuid/prometheus deps and Makefile

Per AG-D15 the pre-rewrite packages are moved to go-agent/oldGo-Agent/
rather than deleted: the lifecycle and collect demos, internal/{config,
docker,persist,telemetry} and the java-demo fixtures stay readable while
the rewrite lands. A nested go.mod keeps them out of go build ./...

Adds grpc/protobuf, uuid and prometheus as direct deps and a Makefile
with proto/test targets.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: The message catalogue — `internal/msg`

**Files:**
- Create: `internal/msg/msg.go`, `internal/msg/msg_test.go`

**Interfaces:**
- Produces: every type below, used verbatim by reducers, `codec`, `wire`, `fakegw`. The names are the gateway's §4.3 field names in Go casing.

- [ ] **Step 1: Write the failing test**

`internal/msg/msg_test.go`:

```go
package msg

import "testing"

func TestCatalogueDirections(t *testing.T) {
	var out []Outbound = []Outbound{
		SessionOpen{}, LeaseRenew{}, CommandPull{}, CommandReceived{}, CommandAck{},
		ApplyProgress{}, ActualStateReportBatch{}, Heartbeat{}, Teardown{},
	}
	var in []Inbound = []Inbound{
		SessionAccept{}, SessionReject{}, LeaseGrant{}, Revoke{}, GenerationAvailable{},
		CommandOffer{}, ReportAck{}, HeartbeatAck{}, TrustBundleUpdate{}, Drain{},
		Teardown{}, Error{},
	}
	if len(out) != 9 || len(in) != 12 {
		t.Fatalf("catalogue changed: out=%d in=%d", len(out), len(in))
	}
}

func TestOperationCapability(t *testing.T) {
	cases := map[Operation]Capability{
		OpPreflight: CapApplyConfig, OpApply: CapApplyConfig,
		OpRestart: CapLifecycle, OpStop: CapLifecycle,
	}
	for op, want := range cases {
		if got := op.RequiredCapability(); got != want {
			t.Errorf("%s: got %s want %s", op, got, want)
		}
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `go test ./internal/msg/`
Expected: FAIL — package does not exist.

- [ ] **Step 3: Write the catalogue**

`internal/msg/msg.go`:

```go
// Package msg is the agent's internal form of the agent-gateway message
// catalogue (gateway design §4.3, §4.11). No package outside internal/wire
// touches generated protobuf types; everything else speaks these structs.
package msg

import "time"

type Operation string

const (
	OpPreflight Operation = "preflight"
	OpApply     Operation = "apply"
	OpRestart   Operation = "restart"
	OpStop      Operation = "stop"
)

type Capability string

const (
	CapApplyConfig Capability = "apply-config"
	CapLifecycle   Capability = "lifecycle"
	CapReportOnly  Capability = "report-only"
)

// RequiredCapability mirrors gateway §4.6: apply-config for preflight/apply,
// lifecycle for restart/stop.
func (o Operation) RequiredCapability() Capability {
	switch o {
	case OpRestart, OpStop:
		return CapLifecycle
	default:
		return CapApplyConfig
	}
}

type Outcome string

const (
	OutcomeApplied            Outcome = "APPLIED"
	OutcomeFailedNoEffect     Outcome = "FAILED_NO_EFFECT"
	OutcomeFailedRolledBack   Outcome = "FAILED_ROLLED_BACK"
	OutcomeSuperseded         Outcome = "SUPERSEDED"
	OutcomeRefused            Outcome = "REFUSED"
	OutcomeApplyIndeterminate Outcome = "APPLY_INDETERMINATE"
	OutcomePreflightOK        Outcome = "PREFLIGHT_OK"
	OutcomePreflightRefused   Outcome = "PREFLIGHT_REFUSED"
)

type ProgressStep string

const (
	StepReceived  ProgressStep = "RECEIVED"
	StepStaged    ProgressStep = "STAGED"
	StepPersisted ProgressStep = "PERSISTED"
	StepReported  ProgressStep = "REPORTED"
)

// ErrorCode is gateway §4.11's closed enum plus Unknown for forward compatibility.
type ErrorCode string

const (
	ErrUnknown                    ErrorCode = "UNKNOWN"
	ErrUnauthenticatedIdentity    ErrorCode = "UNAUTHENTICATED_IDENTITY"
	ErrIdentityRevoked            ErrorCode = "IDENTITY_REVOKED"
	ErrTokenConsumed              ErrorCode = "TOKEN_CONSUMED"
	ErrProtocolVersionUnsupported ErrorCode = "PROTOCOL_VERSION_UNSUPPORTED"
	ErrUpgradeRequired            ErrorCode = "UPGRADE_REQUIRED"
	ErrCapabilityMismatch         ErrorCode = "CAPABILITY_MISMATCH"
	ErrCapabilityMissing          ErrorCode = "CAPABILITY_MISSING"
	ErrBindingNotInLease          ErrorCode = "BINDING_NOT_IN_LEASE"
	ErrTargetMismatch             ErrorCode = "TARGET_MISMATCH"
	ErrLeaseKeyMismatch           ErrorCode = "LEASE_KEY_MISMATCH"
	ErrGrantInvalid               ErrorCode = "GRANT_INVALID"
	ErrRequestIDReused            ErrorCode = "REQUEST_ID_REUSED"
	ErrRequestExpired             ErrorCode = "REQUEST_EXPIRED"
	ErrInProgress                 ErrorCode = "IN_PROGRESS"
	ErrLeaseRenewRequired         ErrorCode = "LEASE_RENEW_REQUIRED"
	ErrReEnrollRequired           ErrorCode = "RE_ENROLL_REQUIRED"
	ErrIssueNotAllowed            ErrorCode = "ISSUE_NOT_ALLOWED"
	ErrReplicaRefInvalid          ErrorCode = "REPLICA_REF_INVALID"
	ErrReplicaCapExceeded         ErrorCode = "REPLICA_CAP_EXCEEDED"
	ErrReporterDisplaced          ErrorCode = "REPORTER_DISPLACED"
	ErrUndeclaredLabel            ErrorCode = "UNDECLARED_LABEL"
	ErrLabelInvalid               ErrorCode = "LABEL_INVALID"
	ErrUnknownReplica             ErrorCode = "UNKNOWN_REPLICA"
	ErrIncarnationRetired         ErrorCode = "INCARNATION_RETIRED"
	ErrAdmissionLimited           ErrorCode = "ADMISSION_LIMITED"
	ErrSessionSuperseded          ErrorCode = "SESSION_SUPERSEDED"
	ErrLeaseRenewRefused          ErrorCode = "LEASE_RENEW_REFUSED"
	ErrStaleGeneration            ErrorCode = "STALE_GENERATION"
	ErrUnknownCommand             ErrorCode = "UNKNOWN_COMMAND"
	ErrSuperseded                 ErrorCode = "SUPERSEDED"
	ErrKeyHashMismatch            ErrorCode = "KEY_HASH_MISMATCH"
	ErrTenantMismatch             ErrorCode = "TENANT_MISMATCH"
	ErrPayloadTooLarge            ErrorCode = "PAYLOAD_TOO_LARGE"
	ErrOutOfCredits               ErrorCode = "OUT_OF_CREDITS"
	ErrClockSuspect               ErrorCode = "CLOCK_SUSPECT"
	ErrDraining                   ErrorCode = "DRAINING"
	ErrInternalUnavailable        ErrorCode = "INTERNAL_UNAVAILABLE"
)

// DetailCode is CommandAck.detail.code: gateway §4.4 rule 5 names
// ADMISSION_BLOCKED and §4.8 names LEASE_NOT_ACTIVE there; every ErrorCode is
// also valid.
type DetailCode string

const (
	DetailNone            DetailCode = ""
	DetailAdmissionBlocked DetailCode = "ADMISSION_BLOCKED"
	DetailLeaseNotActive   DetailCode = "LEASE_NOT_ACTIVE"
)

func DetailOf(c ErrorCode) DetailCode { return DetailCode(c) }

type Trust string

const (
	TrustTrusted   Trust = "TRUSTED"
	TrustUntrusted Trust = "UNTRUSTED"
)

type Disposition string

const (
	DispApplied Disposition = "APPLIED"
	DispStale   Disposition = "STALE"
	DispRefused Disposition = "REFUSED"
)

type ClockState string

const (
	ClockOK      ClockState = "OK"
	ClockSuspect ClockState = "SUSPECT"
)

// Envelope is on every stream message. SentAt is display-only (B/D15).
type Envelope struct {
	MessageID string
	SentAt    time.Time
}

type Outbound interface{ outbound() }
type Inbound interface{ inbound() }

// ---- Enroll (unary) ----

type HostFacts struct {
	OS, Arch, RuntimeKind string
}

type EnrollRequest struct {
	CSRDER                    []byte
	AgentVersion              string
	ProtocolVersionsSupported []uint32
	HostFacts                 HostFacts
}

type TrustBundle struct {
	CertsPEM  [][]byte
	ValidFrom time.Time
	ValidTo   time.Time
}

type EnrollResponse struct {
	AgentID          string
	CertificateChain [][]byte // DER, leaf first
	TrustBundle      TrustBundle
	ConnectEndpoint  string
}

// ---- Session ----

type Resume struct {
	LastAckedGenerationByBinding map[string]uint64
	LastTelemetrySeqByStream     map[string]uint64
}

type SessionOpen struct {
	Envelope
	ProtocolVersion       uint32
	AgentVersion          string
	CapabilitiesSupported []Capability
	Resume                Resume
	AgentClock            time.Time
}

func (SessionOpen) outbound() {}

type Limits struct {
	MaxMessageBytes   uint64
	MaxBatchBytes     uint64
	InitialCredits    uint32
	HeartbeatInterval time.Duration
	KeepaliveInterval time.Duration
	SkewTolerance     time.Duration
	ReportInterval    time.Duration
}

type LeaseGrant struct {
	Envelope
	AgentID              string
	CnfThumbprint        string // hex SHA-256 of the leaf certificate DER
	TargetID             string
	BindingIDs           []string
	AgentRevocationEpoch uint64
	FleetRevocationEpoch uint64
	IssuedAt             time.Time
	ExpiresAt            time.Time
	Audience             string
	Nonce                string
	Capabilities         []Capability
	VerbCeilings         map[Operation]time.Duration
	AdmissionMargin      time.Duration
	ServerClock          time.Time
}

func (LeaseGrant) inbound() {}

func (g LeaseGrant) Has(c Capability) bool {
	for _, x := range g.Capabilities {
		if x == c {
			return true
		}
	}
	return false
}

func (g LeaseGrant) HasBinding(id string) bool {
	for _, x := range g.BindingIDs {
		if x == id {
			return true
		}
	}
	return false
}

type SessionAccept struct {
	Envelope
	SessionID            string
	AssignedTargetID     string
	RequiredCapabilities []Capability
	Lease                LeaseGrant
	Limits               Limits
	ServerClock          time.Time
}

func (SessionAccept) inbound() {}

type SessionReject struct {
	Envelope
	Code            ErrorCode
	RetryAfter      *time.Duration
	MinAgentVersion string
}

func (SessionReject) inbound() {}

// ---- Steady ----

type LeaseRenew struct {
	Envelope
	NonceConsumed string
	AgentClock    time.Time
}

func (LeaseRenew) outbound() {}

type Revoke struct {
	Envelope
	AgentRevocationEpoch *uint64
	FleetRevocationEpoch *uint64
	Reason               string
}

func (Revoke) inbound() {}

type GenerationAvailable struct {
	Envelope
	CommandID    string
	BindingID    string
	Generation   uint64
	Operation    Operation
	ConfigSHA256 string
	Deadline     time.Time
}

func (GenerationAvailable) inbound() {}

type CommandPull struct {
	Envelope
	CommandID string
}

func (CommandPull) outbound() {}

type CommandOffer struct {
	Envelope
	CommandID            string
	Generation           uint64
	IdempotencyKey       string
	BindingID            string
	Operation            Operation
	Payload              []byte
	ConfigSHA256         string
	SupersedesGeneration *uint64
	Deadline             time.Time
}

func (CommandOffer) inbound() {}

type CommandReceived struct {
	Envelope
	CommandID  string
	Generation uint64
}

func (CommandReceived) outbound() {}

type PreflightResult struct {
	ContainerID           string
	ImageDigest           string
	ConfigPathInContainer string
	HostPath              string
	Mechanism             string
	CompetingSources      []string
	Writable              bool
}

type AckDetail struct {
	Code            DetailCode
	Message         string
	PreflightResult *PreflightResult
}

type CommandAck struct {
	Envelope
	CommandID  string
	Generation uint64
	Outcome    Outcome
	Detail     AckDetail
	AgentClock time.Time
}

func (CommandAck) outbound() {}

type ApplyProgress struct {
	Envelope
	CommandID  string
	Step       ProgressStep
	ReplicaRef string
	At         time.Time
}

func (ApplyProgress) outbound() {}

type VersionObservation struct {
	Mechanism  string
	Value      string
	Confidence string
}

type ActualStateReport struct {
	ItemID             string
	Sequence           uint64
	BindingID          string
	ReplicaRef         string
	Incarnation        string
	PersistedGeneration uint64
	RestartObservedAt  *time.Time
	EffectiveHash      string
	VersionObservation VersionObservation
}

type ActualStateReportBatch struct {
	Envelope
	Items []ActualStateReport
}

func (ActualStateReportBatch) outbound() {}

type ReportResult struct {
	InReplyToItemID string
	Sequence        uint64
	ReplicaID       string
	IncarnationID   string
	Trust           Trust
	Disposition     Disposition
	RefusedReason   ErrorCode
}

type ReportAck struct {
	Envelope
	InReplyToMessageID string
	Results            []ReportResult
}

func (ReportAck) inbound() {}

type Heartbeat struct {
	Envelope
	AgentClock           time.Time
	BufferDepthBytes     uint64
	DropCount            uint64
	JournalDepth         uint32
	LeaseRemainingAtSend time.Duration
}

func (Heartbeat) outbound() {}

type HeartbeatAck struct {
	Envelope
	ServerClock   time.Time
	ClockOffsetMs int64
	ClockState    ClockState
}

func (HeartbeatAck) inbound() {}

type TrustBundleUpdate struct {
	Envelope
	TrustBundle TrustBundle
}

func (TrustBundleUpdate) inbound() {}

type Drain struct {
	Envelope
	Reason         string
	ReconnectAfter time.Duration
}

func (Drain) inbound() {}

type Teardown struct {
	Envelope
	Reason string
}

func (Teardown) outbound() {}
func (Teardown) inbound()  {}

type Error struct {
	Envelope
	Code          ErrorCode
	RetryAfter    *time.Duration
	CorrelationID string
	Message       string
}

func (Error) inbound() {}
```

- [ ] **Step 4: Run the tests**

Run: `go test ./internal/msg/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add internal/msg
git commit -m "go-agent: add internal message catalogue mirroring gateway §4.3/§4.11

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: DRAFT `.proto` and codegen (`AG-D13`)

**Files:**
- Create: `buf.yaml`, `buf.gen.yaml`, `proto/agentgateway/v1draft/agent_gateway.proto`, `internal/wire/pb/doc.go`, `internal/wire/pb/pb_test.go`
- Generated: `internal/wire/pb/agent_gateway.pb.go`, `internal/wire/pb/agent_gateway_grpc.pb.go` (committed)

**Interfaces:**
- Produces: package `pb` with `AgentGatewayClient`/`AgentGatewayServer`, messages `EnrollRequest`, `EnrollResponse`, `AgentToGateway` (oneof `msg`), `GatewayToAgent` (oneof `msg`), and every message/enum below. Task 8 (`codec`) maps these to `internal/msg`.

- [ ] **Step 1: buf configuration**

`buf.yaml`:

```yaml
version: v2
modules:
  - path: proto/agentgateway/v1draft
lint:
  use:
    - STANDARD
breaking:
  use:
    - FILE
```

`buf.gen.yaml`:

```yaml
version: v2
plugins:
  - local: protoc-gen-go
    out: internal/wire/pb
    opt: paths=source_relative
  - local: protoc-gen-go-grpc
    out: internal/wire/pb
    opt: paths=source_relative
```

- [ ] **Step 2: Write the proto — a transcription, not a design**

`proto/agentgateway/v1draft/agent_gateway.proto`:

```proto
// DRAFT — NON-AUTHORITATIVE.
//
// Transcribed 1:1 from docs/design/backend/agent-gateway/design.md §4.3
// (message catalogue) and §4.11 (error enum) so the agent can be built while
// open item 6 / AG-1 / AGW-2 (the reviewed .proto under contracts/) is open.
// When that file lands, this one is deleted and internal/wire/codec is
// repointed (AG-D13). Evolution rules from §4.12 apply from this commit:
// fields only added, never renumbered or retyped; enums only appended.
//
// Two transcription notes:
//  * Proto enum values carry the enum-name prefix (ERROR_CODE_SUPERSEDED)
//    because proto3 scopes enum values at package level and the design uses
//    SUPERSEDED for both an error code and an outcome. The bare names in the
//    design are the semantic names; the Go msg package uses them.
//  * AckDetailCode is the one enum the design leaves implicit: §4.4 rule 5
//    puts ADMISSION_BLOCKED in CommandAck.detail.code and §4.8 uses
//    LEASE_NOT_ACTIVE as a reason. Both are carried here beside a reused
//    ErrorCode.
syntax = "proto3";

package solace.sm.agentgateway.v1draft;

import "google/protobuf/duration.proto";
import "google/protobuf/timestamp.proto";

option go_package = "github.com/solace/solution-manager/go-agent/internal/wire/pb;pb";

service AgentGateway {
  // The only RPC accepted without a client certificate. The enrollment
  // token travels as gRPC metadata "x-sm-enrollment-token".
  rpc Enroll(EnrollRequest) returns (EnrollResponse);
  rpc Connect(stream AgentToGateway) returns (stream GatewayToAgent);
  // Declared per §4.1; not implemented in slice 1.
  rpc Telemetry(stream TelemetryUp) returns (stream TelemetryDown);
}

message Envelope {
  string message_id = 1; // UUIDv7
  google.protobuf.Timestamp sent_at = 2; // display and diagnostics only
}

enum ErrorCode {
  ERROR_CODE_UNSPECIFIED = 0;
  ERROR_CODE_UNAUTHENTICATED_IDENTITY = 1;
  ERROR_CODE_IDENTITY_REVOKED = 2;
  ERROR_CODE_TOKEN_CONSUMED = 3;
  ERROR_CODE_PROTOCOL_VERSION_UNSUPPORTED = 4;
  ERROR_CODE_UPGRADE_REQUIRED = 5;
  ERROR_CODE_CAPABILITY_MISMATCH = 6;
  ERROR_CODE_CAPABILITY_MISSING = 7;
  ERROR_CODE_BINDING_NOT_IN_LEASE = 8;
  ERROR_CODE_TARGET_MISMATCH = 9;
  ERROR_CODE_LEASE_KEY_MISMATCH = 10;
  ERROR_CODE_GRANT_INVALID = 11;
  ERROR_CODE_REQUEST_ID_REUSED = 12;
  ERROR_CODE_REQUEST_EXPIRED = 13;
  ERROR_CODE_IN_PROGRESS = 14;
  ERROR_CODE_LEASE_RENEW_REQUIRED = 15;
  ERROR_CODE_RE_ENROLL_REQUIRED = 16;
  ERROR_CODE_ISSUE_NOT_ALLOWED = 17;
  ERROR_CODE_REPLICA_REF_INVALID = 18;
  ERROR_CODE_REPLICA_CAP_EXCEEDED = 19;
  ERROR_CODE_REPORTER_DISPLACED = 20;
  ERROR_CODE_UNDECLARED_LABEL = 21;
  ERROR_CODE_LABEL_INVALID = 22;
  ERROR_CODE_UNKNOWN_REPLICA = 23;
  ERROR_CODE_INCARNATION_RETIRED = 24;
  ERROR_CODE_ADMISSION_LIMITED = 25;
  ERROR_CODE_SESSION_SUPERSEDED = 26;
  ERROR_CODE_LEASE_RENEW_REFUSED = 27;
  ERROR_CODE_STALE_GENERATION = 28;
  ERROR_CODE_UNKNOWN_COMMAND = 29;
  ERROR_CODE_SUPERSEDED = 30;
  ERROR_CODE_KEY_HASH_MISMATCH = 31;
  ERROR_CODE_TENANT_MISMATCH = 32;
  ERROR_CODE_PAYLOAD_TOO_LARGE = 33;
  ERROR_CODE_OUT_OF_CREDITS = 34;
  ERROR_CODE_CLOCK_SUSPECT = 35;
  ERROR_CODE_DRAINING = 36;
  ERROR_CODE_INTERNAL_UNAVAILABLE = 37;
}

enum Capability {
  CAPABILITY_UNSPECIFIED = 0;
  CAPABILITY_APPLY_CONFIG = 1;
  CAPABILITY_LIFECYCLE = 2;
  CAPABILITY_REPORT_ONLY = 3;
}

enum Operation {
  OPERATION_UNSPECIFIED = 0;
  OPERATION_PREFLIGHT = 1;
  OPERATION_APPLY = 2;
  OPERATION_RESTART = 3;
  OPERATION_STOP = 4;
}

enum Outcome {
  OUTCOME_UNSPECIFIED = 0;
  OUTCOME_APPLIED = 1;
  OUTCOME_FAILED_NO_EFFECT = 2;
  OUTCOME_FAILED_ROLLED_BACK = 3;
  OUTCOME_SUPERSEDED = 4;
  OUTCOME_REFUSED = 5;
  OUTCOME_APPLY_INDETERMINATE = 6;
  OUTCOME_PREFLIGHT_OK = 7;
  OUTCOME_PREFLIGHT_REFUSED = 8;
}

enum ProgressStep {
  PROGRESS_STEP_UNSPECIFIED = 0;
  PROGRESS_STEP_RECEIVED = 1;
  PROGRESS_STEP_STAGED = 2;
  PROGRESS_STEP_PERSISTED = 3;
  PROGRESS_STEP_REPORTED = 4;
}

enum Trust {
  TRUST_UNSPECIFIED = 0;
  TRUST_TRUSTED = 1;
  TRUST_UNTRUSTED = 2;
}

enum Disposition {
  DISPOSITION_UNSPECIFIED = 0;
  DISPOSITION_APPLIED = 1;
  DISPOSITION_STALE = 2;
  DISPOSITION_REFUSED = 3;
}

enum ClockState {
  CLOCK_STATE_UNSPECIFIED = 0;
  CLOCK_STATE_OK = 1;
  CLOCK_STATE_SUSPECT = 2;
}

// ---- Enroll ----

message HostFacts {
  string os = 1;
  string arch = 2;
  string runtime_kind = 3;
}

message EnrollRequest {
  bytes csr_der = 1;
  string agent_version = 2;
  repeated uint32 protocol_versions_supported = 3;
  HostFacts host_facts = 4;
}

message TrustBundle {
  repeated bytes certs_pem = 1;
  google.protobuf.Timestamp valid_from = 2;
  google.protobuf.Timestamp valid_to = 3;
}

message EnrollResponse {
  string agent_id = 1;
  repeated bytes certificate_chain = 2; // DER, leaf first
  TrustBundle trust_bundle = 3;
  string connect_endpoint = 4;
}

// ---- Session ----

message Resume {
  map<string, uint64> last_acked_generation_by_binding = 1;
  map<string, uint64> last_telemetry_seq_by_stream = 2;
}

message SessionOpen {
  Envelope envelope = 1;
  uint32 protocol_version = 2;
  string agent_version = 3;
  repeated Capability capabilities_supported = 4;
  Resume resume = 5;
  google.protobuf.Timestamp agent_clock = 6;
}

message Limits {
  uint64 max_message_bytes = 1;
  uint64 max_batch_bytes = 2;
  uint32 initial_credits = 3;
  google.protobuf.Duration heartbeat_interval = 4;
  google.protobuf.Duration keepalive_interval = 5;
  google.protobuf.Duration skew_tolerance = 6;
  google.protobuf.Duration report_interval = 7;
}

message LeaseGrant {
  Envelope envelope = 1;
  string agent_id = 2;
  string cnf_thumbprint = 3;
  string target_id = 4;
  repeated string binding_ids = 5;
  uint64 agent_revocation_epoch = 6;
  uint64 fleet_revocation_epoch = 7;
  google.protobuf.Timestamp issued_at = 8;
  google.protobuf.Timestamp expires_at = 9;
  string audience = 10;
  string nonce = 11;
  repeated Capability capabilities = 12;
  map<string, google.protobuf.Duration> verb_ceilings = 13; // key: Operation name
  google.protobuf.Duration admission_margin = 14;
  google.protobuf.Timestamp server_clock = 15;
}

message SessionAccept {
  Envelope envelope = 1;
  string session_id = 2;
  string assigned_target_id = 3;
  repeated Capability required_capabilities = 4;
  LeaseGrant lease = 5;
  Limits limits = 6;
  google.protobuf.Timestamp server_clock = 7;
}

message SessionReject {
  Envelope envelope = 1;
  ErrorCode code = 2;
  optional google.protobuf.Duration retry_after = 3;
  optional string min_agent_version = 4;
}

// ---- Steady ----

message LeaseRenew {
  Envelope envelope = 1;
  string nonce_consumed = 2;
  google.protobuf.Timestamp agent_clock = 3;
}

message Revoke {
  Envelope envelope = 1;
  optional uint64 agent_revocation_epoch = 2;
  optional uint64 fleet_revocation_epoch = 3;
  string reason = 4;
}

message GenerationAvailable {
  Envelope envelope = 1;
  string command_id = 2;
  string binding_id = 3;
  uint64 generation = 4;
  Operation operation = 5;
  optional string config_sha256 = 6;
  google.protobuf.Timestamp deadline = 7;
}

message CommandPull {
  Envelope envelope = 1;
  string command_id = 2;
}

message CommandOffer {
  Envelope envelope = 1;
  string command_id = 2;
  uint64 generation = 3;
  string idempotency_key = 4;
  string binding_id = 5;
  Operation operation = 6;
  bytes payload_bytes = 7;
  string config_sha256 = 8;
  optional uint64 supersedes_generation = 9;
  google.protobuf.Timestamp deadline = 10;
}

message CommandReceived {
  Envelope envelope = 1;
  string command_id = 2;
  uint64 generation = 3;
}

enum AckDetailCode {
  ACK_DETAIL_CODE_UNSPECIFIED = 0;
  ACK_DETAIL_CODE_ADMISSION_BLOCKED = 1;
  ACK_DETAIL_CODE_LEASE_NOT_ACTIVE = 2;
  ACK_DETAIL_CODE_ERROR = 3; // see error_code
}

message PreflightResult {
  string container_id = 1;
  string image_digest = 2;
  string config_path_in_container = 3;
  string host_path = 4;
  string mechanism = 5;
  repeated string competing_sources = 6;
  bool writable = 7;
}

message AckDetail {
  AckDetailCode code = 1;
  ErrorCode error_code = 2; // when code == ACK_DETAIL_CODE_ERROR
  string message = 3;       // code-keyed template, never free text
  optional PreflightResult preflight_result = 4;
}

message CommandAck {
  Envelope envelope = 1;
  string command_id = 2;
  uint64 generation = 3;
  Outcome outcome = 4;
  AckDetail detail = 5;
  google.protobuf.Timestamp agent_clock = 6;
}

message ApplyProgress {
  Envelope envelope = 1;
  string command_id = 2;
  ProgressStep step = 3;
  string replica_ref = 4;
  google.protobuf.Timestamp at = 5;
}

message VersionObservation {
  string mechanism = 1;
  string value = 2;
  string confidence = 3;
}

message ActualStateReport {
  string item_id = 1;
  uint64 sequence = 2;
  string binding_id = 3;
  string replica_ref = 4;
  string incarnation = 5;
  uint64 persisted_generation = 6;
  optional google.protobuf.Timestamp restart_observed_at = 7;
  optional string effective_hash = 8;
  VersionObservation version_observation = 9;
}

message ActualStateReportBatch {
  Envelope envelope = 1;
  repeated ActualStateReport items = 2;
}

message ReportResult {
  string in_reply_to_item_id = 1;
  uint64 sequence = 2;
  optional string replica_id = 3;
  optional string incarnation_id = 4;
  Trust trust = 5;
  Disposition disposition = 6;
  ErrorCode refused_reason = 7;
}

message ReportAck {
  Envelope envelope = 1;
  string in_reply_to_message_id = 2;
  repeated ReportResult results = 3;
}

message Heartbeat {
  Envelope envelope = 1;
  google.protobuf.Timestamp agent_clock = 2;
  uint64 buffer_depth_bytes = 3;
  uint64 drop_count = 4;
  uint32 journal_depth = 5;
  google.protobuf.Duration lease_remaining_at_send = 6;
}

message HeartbeatAck {
  Envelope envelope = 1;
  google.protobuf.Timestamp server_clock = 2;
  int64 clock_offset_ms = 3;
  ClockState clock_state = 4;
}

message TrustBundleUpdate {
  Envelope envelope = 1;
  TrustBundle trust_bundle = 2;
}

message Drain {
  Envelope envelope = 1;
  string reason = 2;
  google.protobuf.Duration reconnect_after = 3;
}

message Teardown {
  Envelope envelope = 1;
  string reason = 2;
}

message Error {
  Envelope envelope = 1;
  ErrorCode code = 2;
  optional google.protobuf.Duration retry_after = 3;
  string correlation_id = 4;
  string message = 5;
}

// ---- Stream envelopes ----

message AgentToGateway {
  oneof msg {
    SessionOpen session_open = 1;
    LeaseRenew lease_renew = 2;
    CommandPull command_pull = 3;
    CommandReceived command_received = 4;
    CommandAck command_ack = 5;
    ApplyProgress apply_progress = 6;
    ActualStateReportBatch actual_state_report_batch = 7;
    Heartbeat heartbeat = 8;
    Teardown teardown = 9;
  }
}

message GatewayToAgent {
  oneof msg {
    SessionAccept session_accept = 1;
    SessionReject session_reject = 2;
    LeaseGrant lease_grant = 3;
    Revoke revoke = 4;
    GenerationAvailable generation_available = 5;
    CommandOffer command_offer = 6;
    ReportAck report_ack = 7;
    HeartbeatAck heartbeat_ack = 8;
    TrustBundleUpdate trust_bundle_update = 9;
    Drain drain = 10;
    Teardown teardown = 11;
    Error error = 12;
  }
}

// ---- Telemetry stream (declared, unimplemented in slice 1) ----

message TelemetryUp {
  Envelope envelope = 1;
}

message TelemetryDown {
  Envelope envelope = 1;
}
```

- [ ] **Step 3: Generate and pin the package**

`internal/wire/pb/doc.go`:

```go
// Package pb holds the generated Go for the DRAFT agent-gateway proto.
// Regenerate with `make proto`. Only internal/wire and internal/wire/codec
// may import this package.
package pb
```

Run: `buf lint && make proto`
Expected: `internal/wire/pb/agent_gateway.pb.go` and `agent_gateway_grpc.pb.go` appear; `buf lint` prints nothing.

- [ ] **Step 4: Write the failing round-trip test**

`internal/wire/pb/pb_test.go`:

```go
package pb

import (
	"testing"

	"google.golang.org/protobuf/proto"
)

func TestOfferRoundTrip(t *testing.T) {
	// protoc-gen-go does not upper-case acronyms: command_id → CommandId.
	in := &GatewayToAgent{Msg: &GatewayToAgent_CommandOffer{CommandOffer: &CommandOffer{
		Envelope:     &Envelope{MessageId: "m1"},
		CommandId:    "c1",
		Generation:   7,
		Operation:    Operation_OPERATION_APPLY,
		PayloadBytes: []byte("server.port: 8080\n"),
		ConfigSha256: "abc",
	}}}
	b, err := proto.Marshal(in)
	if err != nil {
		t.Fatal(err)
	}
	var out GatewayToAgent
	if err := proto.Unmarshal(b, &out); err != nil {
		t.Fatal(err)
	}
	got := out.GetCommandOffer()
	if got.GetCommandId() != "c1" || got.GetGeneration() != 7 || got.GetOperation() != Operation_OPERATION_APPLY {
		t.Fatalf("round trip lost fields: %+v", got)
	}
}
```

- [ ] **Step 5: Run the test**

Run: `go test ./internal/wire/pb/`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add buf.yaml buf.gen.yaml proto internal/wire/pb
git commit -m "go-agent: DRAFT agent-gateway .proto transcribed from design §4.3/§4.11 (AG-D13)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Durable store — `internal/store`

**Files:**
- Create: `internal/store/store.go`, `internal/store/store_test.go`

**Interfaces:**
- Produces:
  - `func WriteAtomic(path string, data []byte, mode os.FileMode) error` — temp in same dir → fsync → chmod → rename → fsync parent.
  - `type Store struct{ Root string }`; `func Open(root string) (*Store, error)` creates `root`, `identity/`, `session/`, `apply/`, `reports/` at `0700`.
  - `func (s *Store) Path(rel string) string`; `func (s *Store) Save(r Record) error`; `func (s *Store) Delete(rel string) error`; `func (s *Store) Exists(rel string) bool`.
  - `type Record interface{ Rel() string; Marshal() ([]byte, error) }`; `type JSON struct{ RelPath string; Value any }`; `type Blob struct{ RelPath string; Data []byte }`.
  - `func LoadJSON[T any](s *Store, rel string, wantVersion int) (T, error)` — `ErrNotFound`, `ErrSchemaVersion`; `T` must have a `SchemaVersion int \`json:"schema_version"\`` field, checked via a `Versioned` interface.
  - `type Versioned interface{ Version() int }`.

- [ ] **Step 1: Write the failing tests**

`internal/store/store_test.go`:

```go
package store

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

type rec struct {
	SchemaVersion int    `json:"schema_version"`
	Name          string `json:"name"`
}

func (r rec) Version() int { return r.SchemaVersion }

func TestWriteAtomicModeAndParentSync(t *testing.T) {
	dir := t.TempDir()
	var synced []string
	old := syncDir
	syncDir = func(d string) error { synced = append(synced, d); return nil }
	defer func() { syncDir = old }()

	p := filepath.Join(dir, "x.json")
	if err := WriteAtomic(p, []byte("hi"), 0o600); err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(p)
	if err != nil {
		t.Fatal(err)
	}
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("mode %o", st.Mode().Perm())
	}
	if len(synced) != 1 || synced[0] != dir {
		t.Fatalf("parent dir not fsynced: %v", synced)
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 1 {
		t.Fatalf("temp file left behind: %v", entries)
	}
}

func TestOpenLayout(t *testing.T) {
	root := filepath.Join(t.TempDir(), "data")
	s, err := Open(root)
	if err != nil {
		t.Fatal(err)
	}
	for _, d := range []string{"identity", "session", "apply", "reports"} {
		st, err := os.Stat(s.Path(d))
		if err != nil || !st.IsDir() || st.Mode().Perm() != 0o700 {
			t.Fatalf("%s: %v %v", d, err, st)
		}
	}
}

func TestJSONRoundTripAndVersion(t *testing.T) {
	s, _ := Open(filepath.Join(t.TempDir(), "d"))
	if err := s.Save(JSON{RelPath: "session/r.json", Value: rec{SchemaVersion: 1, Name: "a"}}); err != nil {
		t.Fatal(err)
	}
	got, err := LoadJSON[rec](s, "session/r.json", 1)
	if err != nil || got.Name != "a" {
		t.Fatalf("%v %+v", err, got)
	}
	if _, err := LoadJSON[rec](s, "session/r.json", 2); !errors.Is(err, ErrSchemaVersion) {
		t.Fatalf("want ErrSchemaVersion, got %v", err)
	}
	if _, err := LoadJSON[rec](s, "session/missing.json", 1); !errors.Is(err, ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

func TestBlobAndDelete(t *testing.T) {
	s, _ := Open(filepath.Join(t.TempDir(), "d"))
	if err := s.Save(Blob{RelPath: "apply/b/1/payload", Data: []byte("secret")}); err != nil {
		t.Fatal(err)
	}
	if !s.Exists("apply/b/1/payload") {
		t.Fatal("blob missing")
	}
	if err := s.Delete("apply/b/1/payload"); err != nil || s.Exists("apply/b/1/payload") {
		t.Fatalf("delete: %v", err)
	}
}
```

- [ ] **Step 2: Run to verify failure**

Run: `go test ./internal/store/`
Expected: FAIL — package missing.

- [ ] **Step 3: Implement**

`internal/store/store.go`:

```go
// Package store is the agent's durable state under $SM_AGENT_DATA_DIR.
// Every write goes through WriteAtomic; every record carries a schema
// version and is refused, not migrated, when it does not match.
package store

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

var (
	ErrNotFound      = errors.New("store: not found")
	ErrSchemaVersion = errors.New("store: schema version mismatch")
)

// syncDir is a variable so tests can observe the parent-directory fsync.
var syncDir = func(dir string) error {
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}

// WriteAtomic writes data to path via a temp file in the same directory:
// write → fsync → chmod → rename → fsync parent. Partial writes are never
// visible and the rename is durable after return.
func WriteAtomic(path string, data []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+".*.tmp")
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	cleanup := func() { _ = tmp.Close(); _ = os.Remove(tmpPath) }
	if _, err := tmp.Write(data); err != nil {
		cleanup()
		return err
	}
	if err := tmp.Sync(); err != nil {
		cleanup()
		return err
	}
	if err := tmp.Chmod(mode); err != nil {
		cleanup()
		return err
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	if err := os.Rename(tmpPath, path); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	return syncDir(dir)
}

type Store struct{ Root string }

var layout = []string{"identity", "session", "apply", "reports"}

func Open(root string) (*Store, error) {
	if err := os.MkdirAll(root, 0o700); err != nil {
		return nil, err
	}
	if err := os.Chmod(root, 0o700); err != nil {
		return nil, err
	}
	for _, d := range layout {
		if err := os.MkdirAll(filepath.Join(root, d), 0o700); err != nil {
			return nil, err
		}
	}
	return &Store{Root: root}, nil
}

func (s *Store) Path(rel string) string { return filepath.Join(s.Root, filepath.FromSlash(rel)) }

func (s *Store) Exists(rel string) bool {
	_, err := os.Stat(s.Path(rel))
	return err == nil
}

type Record interface {
	Rel() string
	Marshal() ([]byte, error)
}

// JSON persists Value as indented JSON at RelPath.
type JSON struct {
	RelPath string
	Value   any
}

func (j JSON) Rel() string               { return j.RelPath }
func (j JSON) Marshal() ([]byte, error)  { return json.MarshalIndent(j.Value, "", "  ") }

// Blob persists raw bytes (payloads, PEM) at RelPath.
type Blob struct {
	RelPath string
	Data    []byte
}

func (b Blob) Rel() string              { return b.RelPath }
func (b Blob) Marshal() ([]byte, error) { return b.Data, nil }

func (s *Store) Save(r Record) error {
	data, err := r.Marshal()
	if err != nil {
		return fmt.Errorf("store: marshal %s: %w", r.Rel(), err)
	}
	return WriteAtomic(s.Path(r.Rel()), data, 0o600)
}

func (s *Store) Delete(rel string) error {
	err := os.Remove(s.Path(rel))
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return syncDir(filepath.Dir(s.Path(rel)))
}

type Versioned interface{ Version() int }

func LoadJSON[T Versioned](s *Store, rel string, wantVersion int) (T, error) {
	var v T
	data, err := os.ReadFile(s.Path(rel))
	if errors.Is(err, os.ErrNotExist) {
		return v, ErrNotFound
	}
	if err != nil {
		return v, err
	}
	if err := json.Unmarshal(data, &v); err != nil {
		return v, fmt.Errorf("store: %s: %w", rel, err)
	}
	if v.Version() != wantVersion {
		return v, fmt.Errorf("%w: %s has %d, want %d", ErrSchemaVersion, rel, v.Version(), wantVersion)
	}
	return v, nil
}
```

- [ ] **Step 4: Run the tests**

Run: `go test ./internal/store/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add internal/store
git commit -m "go-agent: durable store with atomic writes and parent-dir fsync

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Reactor vocabulary — `internal/reactor`

**Files:**
- Create: `internal/reactor/reactor.go`, `internal/reactor/reactor_test.go`

**Interfaces:**
- Produces (used by every reducer and the loop):

```go
type Event interface{ EventAt() time.Time }
// events: WireIn{At, Msg msg.Inbound} · TimerFired{At, Kind} · OpFinished{At, CommandID, Result runtime.OpResult}
//         Connected{At} · Disconnected{At, Cause error} · EnrollResult{At, Resp *msg.EnrollResponse, Err error}
//         Resumed{At} · Shutdown{At, Cause error}
type Effect interface{ effectOrder() int }
// effects, in execution order: Persist{Rec store.Record} · Delete{Rel} · Send{Msg msg.Outbound}
//         RunOp{CommandID, BindingID string; Op msg.Operation; PayloadRel, SHA256 string; Deadline time.Time}
//         ArmTimer{Kind, After} · CancelTimer{Kind} · Enroll{Endpoint} · Dial{Endpoint} · Close{Reason} · Halt{Code}
type TimerKind string // RenewDue, LeaseExpiry, HeartbeatDue, ReportDue, StepDeadline, ReconnectDue
type Env struct{ Now time.Time; Offset time.Duration; NewID func() string; Jitter func(time.Duration) time.Duration; Limits msg.Limits }
func (e Env) DPNow() time.Time
func Sort(effects []Effect) []Effect // stable, by effectOrder
```

`runtime.OpResult` is defined in Task 16; until then `OpFinished` carries `Result any` — Task 16 narrows it. (This is the one forward reference in the plan; it is a type substitution, not a behaviour change.)

- [ ] **Step 1: Write the failing test**

`internal/reactor/reactor_test.go`:

```go
package reactor

import (
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

func TestSortKeepsPersistFirstAndIsStable(t *testing.T) {
	effs := []Effect{
		Send{Msg: msg.Heartbeat{}},
		ArmTimer{Kind: HeartbeatDue, After: time.Second},
		Persist{Rec: store.Blob{RelPath: "b"}},
		Send{Msg: msg.Teardown{}},
		Persist{Rec: store.Blob{RelPath: "a"}},
		Close{Reason: "x"},
	}
	got := Sort(effs)
	want := []int{0, 0, 2, 2, 4, 6}
	for i, e := range got {
		if e.effectOrder() != want[i] {
			t.Fatalf("pos %d: order %d want %d", i, e.effectOrder(), want[i])
		}
	}
	if got[0].(Persist).Rec.Rel() != "b" || got[1].(Persist).Rec.Rel() != "a" {
		t.Fatal("sort not stable")
	}
}

func TestDPNow(t *testing.T) {
	e := Env{Now: time.Unix(100, 0), Offset: 5 * time.Second}
	if e.DPNow() != time.Unix(105, 0) {
		t.Fatal("DPNow must add the offset")
	}
}
```

- [ ] **Step 2: Run to verify failure**

Run: `go test ./internal/reactor/`
Expected: FAIL — package missing.

- [ ] **Step 3: Implement**

`internal/reactor/reactor.go`:

```go
// Package reactor is the vocabulary the loop and the reducers share: events
// in, effects out, and the Env that carries every impurity a reducer may use.
package reactor

import (
	"sort"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// ---- Events ----

type Event interface{ EventAt() time.Time }

type WireIn struct {
	At  time.Time
	Msg msg.Inbound
}

type TimerKind string

const (
	RenewDue     TimerKind = "RenewDue"
	LeaseExpiry  TimerKind = "LeaseExpiry"
	HeartbeatDue TimerKind = "HeartbeatDue"
	ReportDue    TimerKind = "ReportDue"
	StepDeadline TimerKind = "StepDeadline"
	ReconnectDue TimerKind = "ReconnectDue"
)

type TimerFired struct {
	At   time.Time
	Kind TimerKind
}

type OpFinished struct {
	At        time.Time
	CommandID string
	Result    any // runtime.OpResult once Task 16 lands
}

type Connected struct{ At time.Time }

type Disconnected struct {
	At    time.Time
	Cause error
}

type EnrollResult struct {
	At   time.Time
	Resp *msg.EnrollResponse
	Err  error
}

type Resumed struct{ At time.Time }

type Shutdown struct {
	At    time.Time
	Cause error
}

func (e WireIn) EventAt() time.Time       { return e.At }
func (e TimerFired) EventAt() time.Time   { return e.At }
func (e OpFinished) EventAt() time.Time   { return e.At }
func (e Connected) EventAt() time.Time    { return e.At }
func (e Disconnected) EventAt() time.Time { return e.At }
func (e EnrollResult) EventAt() time.Time { return e.At }
func (e Resumed) EventAt() time.Time      { return e.At }
func (e Shutdown) EventAt() time.Time     { return e.At }

// ---- Effects ----

// Effect ordering per event: Persist(0) → Delete(1) → Send(2) → RunOp(3) →
// timers(4) → Enroll/Dial(5) → Close(6) → Halt(7).
type Effect interface{ effectOrder() int }

type Persist struct{ Rec store.Record }
type Delete struct{ Rel string }
type Send struct{ Msg msg.Outbound }
type RunOp struct {
	CommandID  string
	BindingID  string
	Op         msg.Operation
	PayloadRel string
	SHA256     string
	Deadline   time.Time
}
type ArmTimer struct {
	Kind  TimerKind
	After time.Duration
}
type CancelTimer struct{ Kind TimerKind }
type Enroll struct{ Endpoint string }
type Dial struct{ Endpoint string }
type Close struct{ Reason string }
type Halt struct{ Code msg.ErrorCode }

func (Persist) effectOrder() int     { return 0 }
func (Delete) effectOrder() int      { return 1 }
func (Send) effectOrder() int        { return 2 }
func (RunOp) effectOrder() int       { return 3 }
func (ArmTimer) effectOrder() int    { return 4 }
func (CancelTimer) effectOrder() int { return 4 }
func (Enroll) effectOrder() int      { return 5 }
func (Dial) effectOrder() int        { return 5 }
func (Close) effectOrder() int       { return 6 }
func (Halt) effectOrder() int        { return 7 }

func Sort(effects []Effect) []Effect {
	out := make([]Effect, len(effects))
	copy(out, effects)
	sort.SliceStable(out, func(i, j int) bool { return out[i].effectOrder() < out[j].effectOrder() })
	return out
}

// ---- Env ----

// Env is every impurity a reducer may consult. Now is the event's stamp;
// Offset is the latest DP clock offset; NewID mints message/item ids;
// Jitter perturbs a backoff duration; Limits are the session's.
type Env struct {
	Now    time.Time
	Offset time.Duration
	NewID  func() string
	Jitter func(time.Duration) time.Duration
	Limits msg.Limits
}

func (e Env) DPNow() time.Time { return e.Now.Add(e.Offset) }
```

- [ ] **Step 4: Run the tests**

Run: `go test ./internal/reactor/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add internal/reactor
git commit -m "go-agent: reactor event/effect vocabulary with ordered effects

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: Identity — key, CSR, certificate, trust bundle, TLS configs

**Files:**
- Create: `internal/identity/identity.go`, `internal/identity/identity_test.go`

**Interfaces:**
- Produces:
  - `type Identity struct{ store *store.Store; key *ecdsa.PrivateKey; leaf *x509.Certificate; chain [][]byte; bundle *x509.CertPool; agentID string }`
  - `func Load(s *store.Store) (*Identity, error)` — loads key (creating one if absent), CSR, cert chain, trust bundle, agent id; `Enrolled()` reports whether a cert exists.
  - `func (id *Identity) CSR() ([]byte, error)` — returns the persisted CSR DER, generating and persisting it on first call.
  - `func (id *Identity) StoreEnrollment(resp msg.EnrollResponse) error` — persists chain, trust bundle, agent id; reloads leaf.
  - `func (id *Identity) StoreTrustBundle(b msg.TrustBundle) error`.
  - `func (id *Identity) AgentID() string`; `func (id *Identity) Thumbprint() string` — hex SHA-256 of leaf DER.
  - `func (id *Identity) EnrollTLS(bootstrapBundlePEM []byte) (*tls.Config, error)` — no client cert; server verified by the bootstrap bundle.
  - `func (id *Identity) ConnectTLS() (*tls.Config, error)` — client cert + stored bundle.
  - `func Thumbprint(der []byte) string`.

- [ ] **Step 1: Write the failing tests**

`internal/identity/identity_test.go`:

```go
package identity

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"path/filepath"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// testCA signs the CSR the way the fake gateway will.
func testCA(t *testing.T) (*x509.Certificate, *ecdsa.PrivateKey, []byte) {
	t.Helper()
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "test-ca"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(24 * time.Hour),
		IsCA: true, KeyUsage: x509.KeyUsageCertSign, BasicConstraintsValid: true,
	}
	der, _ := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	cert, _ := x509.ParseCertificate(der)
	return cert, key, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
}

func sign(t *testing.T, ca *x509.Certificate, caKey *ecdsa.PrivateKey, csrDER []byte) []byte {
	t.Helper()
	csr, err := x509.ParseCertificateRequest(csrDER)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(2), Subject: csr.Subject,
		NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, ca, csr.PublicKey, caKey)
	if err != nil {
		t.Fatal(err)
	}
	return der
}

func TestCSRIsPersistedAndStable(t *testing.T) {
	s, _ := store.Open(filepath.Join(t.TempDir(), "d"))
	id, err := Load(s)
	if err != nil {
		t.Fatal(err)
	}
	if id.Enrolled() {
		t.Fatal("fresh identity must not be enrolled")
	}
	a, _ := id.CSR()
	b, _ := id.CSR()
	if string(a) != string(b) {
		t.Fatal("CSR must be stable across calls")
	}
	if !s.Exists("identity/csr.der") || !s.Exists("identity/key.pem") {
		t.Fatal("CSR and key must be persisted before enroll")
	}
	id2, _ := Load(s)
	c, _ := id2.CSR()
	if string(a) != string(c) {
		t.Fatal("CSR must survive a restart")
	}
}

func TestEnrollmentRoundTrip(t *testing.T) {
	ca, caKey, caPEM := testCA(t)
	s, _ := store.Open(filepath.Join(t.TempDir(), "d"))
	id, _ := Load(s)
	csr, _ := id.CSR()
	leaf := sign(t, ca, caKey, csr)
	err := id.StoreEnrollment(msg.EnrollResponse{
		AgentID: "agent-1", CertificateChain: [][]byte{leaf},
		TrustBundle: msg.TrustBundle{CertsPEM: [][]byte{caPEM}}, ConnectEndpoint: "dp:443",
	})
	if err != nil {
		t.Fatal(err)
	}
	if !id.Enrolled() || id.AgentID() != "agent-1" || id.Thumbprint() != Thumbprint(leaf) {
		t.Fatalf("enrolled=%v agent=%s", id.Enrolled(), id.AgentID())
	}
	cfg, err := id.ConnectTLS()
	if err != nil || len(cfg.Certificates) != 1 || cfg.RootCAs == nil {
		t.Fatalf("ConnectTLS: %v", err)
	}
	ecfg, err := id.EnrollTLS(caPEM)
	if err != nil || len(ecfg.Certificates) != 0 {
		t.Fatalf("EnrollTLS must carry no client cert: %v", err)
	}
	id2, _ := Load(s)
	if !id2.Enrolled() || id2.Thumbprint() != id.Thumbprint() {
		t.Fatal("enrollment must survive a restart")
	}
}
```

- [ ] **Step 2: Run to verify failure**

Run: `go test ./internal/identity/`
Expected: FAIL — package missing.

- [ ] **Step 3: Implement**

`internal/identity/identity.go`:

```go
// Package identity owns the agent's private key, CSR, certificate chain and
// trust bundle. The CSR is generated once and persisted before the first
// Enroll so a retry after a lost reply sends identical bytes.
package identity

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"os"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

const (
	relKey    = "identity/key.pem"
	relCSR    = "identity/csr.der"
	relCert   = "identity/cert.pem"
	relBundle = "identity/trust-bundle.pem"
	relAgent  = "identity/agent_id"
)

type Identity struct {
	s       *store.Store
	key     *ecdsa.PrivateKey
	csr     []byte
	leaf    *x509.Certificate
	chain   [][]byte // DER, leaf first
	bundle  *x509.CertPool
	bundleP []byte
	agentID string
}

func Thumbprint(der []byte) string {
	sum := sha256.Sum256(der)
	return hex.EncodeToString(sum[:])
}

func Load(s *store.Store) (*Identity, error) {
	id := &Identity{s: s}
	keyPEM, err := os.ReadFile(s.Path(relKey))
	switch {
	case errors.Is(err, os.ErrNotExist):
		k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if err != nil {
			return nil, err
		}
		der, err := x509.MarshalECPrivateKey(k)
		if err != nil {
			return nil, err
		}
		if err := s.Save(store.Blob{RelPath: relKey, Data: pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: der})}); err != nil {
			return nil, err
		}
		id.key = k
	case err != nil:
		return nil, err
	default:
		blk, _ := pem.Decode(keyPEM)
		if blk == nil {
			return nil, fmt.Errorf("identity: %s is not PEM", relKey)
		}
		id.key, err = x509.ParseECPrivateKey(blk.Bytes)
		if err != nil {
			return nil, err
		}
	}
	if b, err := os.ReadFile(s.Path(relCSR)); err == nil {
		id.csr = b
	}
	if b, err := os.ReadFile(s.Path(relCert)); err == nil {
		if err := id.loadChainPEM(b); err != nil {
			return nil, err
		}
	}
	if b, err := os.ReadFile(s.Path(relBundle)); err == nil {
		id.bundleP = b
		id.bundle = x509.NewCertPool()
		if !id.bundle.AppendCertsFromPEM(b) {
			return nil, fmt.Errorf("identity: %s holds no certificates", relBundle)
		}
	}
	if b, err := os.ReadFile(s.Path(relAgent)); err == nil {
		id.agentID = string(b)
	}
	return id, nil
}

func (id *Identity) loadChainPEM(b []byte) error {
	id.chain = nil
	for {
		var blk *pem.Block
		blk, b = pem.Decode(b)
		if blk == nil {
			break
		}
		id.chain = append(id.chain, blk.Bytes)
	}
	if len(id.chain) == 0 {
		return errors.New("identity: cert.pem holds no certificates")
	}
	leaf, err := x509.ParseCertificate(id.chain[0])
	if err != nil {
		return err
	}
	id.leaf = leaf
	return nil
}

func (id *Identity) Enrolled() bool { return id.leaf != nil && id.agentID != "" }
func (id *Identity) AgentID() string { return id.agentID }

func (id *Identity) Thumbprint() string {
	if id.leaf == nil {
		return ""
	}
	return Thumbprint(id.leaf.Raw)
}

// CSR returns the persisted CSR, generating it once.
func (id *Identity) CSR() ([]byte, error) {
	if id.csr != nil {
		return id.csr, nil
	}
	tmpl := &x509.CertificateRequest{Subject: pkix.Name{CommonName: "sm-agent"}}
	der, err := x509.CreateCertificateRequest(rand.Reader, tmpl, id.key)
	if err != nil {
		return nil, err
	}
	if err := id.s.Save(store.Blob{RelPath: relCSR, Data: der}); err != nil {
		return nil, err
	}
	id.csr = der
	return der, nil
}

func (id *Identity) StoreEnrollment(resp msg.EnrollResponse) error {
	if len(resp.CertificateChain) == 0 || resp.AgentID == "" {
		return errors.New("identity: enroll response lacks chain or agent id")
	}
	var chainPEM []byte
	for _, der := range resp.CertificateChain {
		chainPEM = append(chainPEM, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})...)
	}
	if err := id.StoreTrustBundle(resp.TrustBundle); err != nil {
		return err
	}
	if err := id.s.Save(store.Blob{RelPath: relCert, Data: chainPEM}); err != nil {
		return err
	}
	if err := id.s.Save(store.Blob{RelPath: relAgent, Data: []byte(resp.AgentID)}); err != nil {
		return err
	}
	id.agentID = resp.AgentID
	return id.loadChainPEM(chainPEM)
}

func (id *Identity) StoreTrustBundle(b msg.TrustBundle) error {
	var pemBytes []byte
	for _, c := range b.CertsPEM {
		pemBytes = append(pemBytes, c...)
	}
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(pemBytes) {
		return errors.New("identity: trust bundle holds no certificates")
	}
	if err := id.s.Save(store.Blob{RelPath: relBundle, Data: pemBytes}); err != nil {
		return err
	}
	id.bundle, id.bundleP = pool, pemBytes
	return nil
}

// EnrollTLS verifies the server against the install-time bundle and sends
// no client certificate — Enroll is the one cert-less RPC.
func (id *Identity) EnrollTLS(bootstrapBundlePEM []byte) (*tls.Config, error) {
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(bootstrapBundlePEM) {
		return nil, errors.New("identity: bootstrap bundle holds no certificates")
	}
	return &tls.Config{RootCAs: pool, MinVersion: tls.VersionTLS13, NextProtos: []string{"h2"}}, nil
}

func (id *Identity) ConnectTLS() (*tls.Config, error) {
	if !id.Enrolled() || id.bundle == nil {
		return nil, errors.New("identity: not enrolled")
	}
	return &tls.Config{
		Certificates: []tls.Certificate{{Certificate: id.chain, PrivateKey: id.key, Leaf: id.leaf}},
		RootCAs:      id.bundle,
		MinVersion:   tls.VersionTLS13,
		NextProtos:   []string{"h2"},
	}, nil
}
```

- [ ] **Step 4: Run the tests**

Run: `go test ./internal/identity/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add internal/identity
git commit -m "go-agent: identity store — key, persisted CSR, cert chain, trust bundle, TLS configs

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: Error classes and the `session` reducer

**Files:**
- Create: `internal/msg/classify.go`, `internal/msg/classify_test.go`, `internal/agent/backoff.go`, `internal/agent/session.go`, `internal/agent/session_test.go`

Error classification lives in `msg`, not `wire` as the spec's file table says, because reducers must not import `wire` (which imports `pb`). Same content, one directory over.

**Interfaces:**
- Produces:
  - `msg.Class` ∈ `ClassRetry | ClassAct | ClassPerCommand | ClassHalt`; `func Classify(c ErrorCode) Class`; `type ProtocolError struct{ Code ErrorCode; RetryAfter *time.Duration; Message string }` implementing `error`; `func CodeOf(err error) (ErrorCode, *time.Duration)` — `ErrUnknown` for non-protocol errors.
  - `agent.Phase` ∈ `IDLE ENROLLING CONNECTING OPENING ACTIVE DRAINING CLOSED HALTED`; `agent.SessionState`; `agent.SessionInputs{Resume msg.Resume; LeaseRemaining time.Duration; JournalDepth uint32}`; `agent.Config{BootstrapEndpoint, AgentVersion string; Capabilities []msg.Capability}`.
  - `func reduceSession(cfg Config, s SessionState, ev reactor.Event, env reactor.Env, in SessionInputs) (SessionState, []reactor.Effect)`.
  - `func backoffFor(attempt int) time.Duration` — `min(5m, 1s·2^attempt)`.
  - `const maxMissedHeartbeats = 3`.

- [ ] **Step 1: Write the failing classify test**

`internal/msg/classify_test.go`:

```go
package msg

import (
	"errors"
	"testing"
	"time"
)

func TestClassify(t *testing.T) {
	cases := map[ErrorCode]Class{
		ErrAdmissionLimited: ClassRetry, ErrInternalUnavailable: ClassRetry, ErrDraining: ClassRetry,
		ErrInProgress: ClassRetry, ErrRequestExpired: ClassRetry, ErrUnknown: ClassRetry,
		ErrLeaseRenewRequired: ClassAct, ErrSessionSuperseded: ClassAct, ErrLeaseRenewRefused: ClassAct, ErrClockSuspect: ClassAct,
		ErrStaleGeneration: ClassPerCommand, ErrKeyHashMismatch: ClassPerCommand, ErrPayloadTooLarge: ClassPerCommand,
		ErrIdentityRevoked: ClassHalt, ErrTokenConsumed: ClassHalt, ErrReEnrollRequired: ClassHalt,
		ErrIssueNotAllowed: ClassHalt, ErrUpgradeRequired: ClassHalt, ErrProtocolVersionUnsupported: ClassHalt,
		ErrUnauthenticatedIdentity: ClassHalt,
	}
	for code, want := range cases {
		if got := Classify(code); got != want {
			t.Errorf("%s: got %v want %v", code, got, want)
		}
	}
}

func TestCodeOf(t *testing.T) {
	ra := 3 * time.Second
	code, retry := CodeOf(&ProtocolError{Code: ErrDraining, RetryAfter: &ra})
	if code != ErrDraining || retry == nil || *retry != ra {
		t.Fatal("protocol error not unwrapped")
	}
	if code, _ := CodeOf(errors.New("dial tcp: refused")); code != ErrUnknown {
		t.Fatal("transport error must classify as unknown")
	}
}
```

- [ ] **Step 2: Run to verify failure**

Run: `go test ./internal/msg/ -run 'Classify|CodeOf'`
Expected: FAIL — undefined symbols.

- [ ] **Step 3: Implement classification**

`internal/msg/classify.go`:

```go
package msg

import (
	"errors"
	"fmt"
	"time"
)

// Class sorts gateway §4.11 codes into the four agent behaviours of spec §8.4.
type Class int

const (
	ClassRetry      Class = iota // reconnect/resend on backoff, retry_after overriding
	ClassAct                     // a specific action: renew, reconnect now, degrade, fail closed
	ClassPerCommand              // close one journal record; session unaffected
	ClassHalt                    // HALTED: no reconnect, operator problem
)

func Classify(c ErrorCode) Class {
	switch c {
	case ErrLeaseRenewRequired, ErrSessionSuperseded, ErrLeaseRenewRefused, ErrClockSuspect:
		return ClassAct
	case ErrStaleGeneration, ErrUnknownCommand, ErrSuperseded, ErrKeyHashMismatch, ErrPayloadTooLarge,
		ErrCapabilityMissing, ErrBindingNotInLease, ErrTargetMismatch:
		return ClassPerCommand
	case ErrIdentityRevoked, ErrTokenConsumed, ErrReEnrollRequired, ErrIssueNotAllowed,
		ErrUpgradeRequired, ErrProtocolVersionUnsupported, ErrUnauthenticatedIdentity:
		return ClassHalt
	default:
		// ADMISSION_LIMITED, INTERNAL_UNAVAILABLE, DRAINING, IN_PROGRESS, REQUEST_EXPIRED,
		// and any code a newer gateway adds (§4.12 forward compatibility).
		return ClassRetry
	}
}

// ProtocolError is a gateway refusal carried as a Go error (Enroll status,
// stream close).
type ProtocolError struct {
	Code       ErrorCode
	RetryAfter *time.Duration
	Message    string
}

func (e *ProtocolError) Error() string { return fmt.Sprintf("gateway: %s: %s", e.Code, e.Message) }

func CodeOf(err error) (ErrorCode, *time.Duration) {
	var pe *ProtocolError
	if errors.As(err, &pe) {
		return pe.Code, pe.RetryAfter
	}
	return ErrUnknown, nil
}
```

- [ ] **Step 4: Run the classify tests**

Run: `go test ./internal/msg/`
Expected: PASS.

- [ ] **Step 5: Write the failing session tests**

`internal/agent/session_test.go`:

```go
package agent

import (
	"errors"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
)

var t0 = time.Date(2026, 9, 22, 10, 0, 0, 0, time.UTC)

func testEnv() reactor.Env {
	n := 0
	return reactor.Env{
		Now:    t0,
		NewID:  func() string { n++; return "id-" + string(rune('0'+n)) },
		Jitter: func(d time.Duration) time.Duration { return d },
		Limits: msg.Limits{HeartbeatInterval: 10 * time.Second},
	}
}

var cfg = Config{BootstrapEndpoint: "boot:443", AgentVersion: "0.1.0", Capabilities: []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle}}

func has[T reactor.Effect](effs []reactor.Effect) (T, bool) {
	for _, e := range effs {
		if v, ok := e.(T); ok {
			return v, true
		}
	}
	var zero T
	return zero, false
}

func TestResumedNotEnrolledEnrolls(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseIdle}, reactor.Resumed{At: t0}, testEnv(), SessionInputs{})
	if s.Phase != PhaseEnrolling {
		t.Fatal(s.Phase)
	}
	if e, ok := has[reactor.Enroll](effs); !ok || e.Endpoint != "boot:443" {
		t.Fatalf("want Enroll{boot:443}, got %+v", effs)
	}
}

func TestResumedEnrolledDials(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseIdle, Enrolled: true, Endpoint: "dp:443"}, reactor.Resumed{At: t0}, testEnv(), SessionInputs{})
	if s.Phase != PhaseConnecting {
		t.Fatal(s.Phase)
	}
	if e, ok := has[reactor.Dial](effs); !ok || e.Endpoint != "dp:443" {
		t.Fatalf("want Dial{dp:443}, got %+v", effs)
	}
}

func TestEnrollResultPersistsEndpointAndDials(t *testing.T) {
	ev := reactor.EnrollResult{At: t0, Resp: &msg.EnrollResponse{AgentID: "a", ConnectEndpoint: "dp:443"}}
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseEnrolling, Endpoint: "boot:443"}, ev, testEnv(), SessionInputs{})
	if s.Phase != PhaseConnecting || !s.Enrolled || s.Endpoint != "dp:443" {
		t.Fatalf("%+v", s)
	}
	if p, ok := has[reactor.Persist](effs); !ok || p.Rec.Rel() != "session/endpoint" {
		t.Fatalf("endpoint not persisted: %+v", effs)
	}
	if _, ok := has[reactor.Dial](effs); !ok {
		t.Fatal("no Dial")
	}
}

func TestEnrollResultHaltCodeHalts(t *testing.T) {
	ev := reactor.EnrollResult{At: t0, Err: &msg.ProtocolError{Code: msg.ErrTokenConsumed}}
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseEnrolling}, ev, testEnv(), SessionInputs{})
	if s.Phase != PhaseHalted || s.HaltCode != msg.ErrTokenConsumed {
		t.Fatalf("%+v", s)
	}
	if h, ok := has[reactor.Halt](effs); !ok || h.Code != msg.ErrTokenConsumed {
		t.Fatal("no Halt effect")
	}
}

func TestEnrollResultTransportErrorRetriesWithBackoff(t *testing.T) {
	ev := reactor.EnrollResult{At: t0, Err: errors.New("dial tcp: connection refused")}
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseEnrolling, Attempt: 2}, ev, testEnv(), SessionInputs{})
	if s.Phase != PhaseEnrolling || s.Attempt != 3 {
		t.Fatalf("%+v", s)
	}
	if a, ok := has[reactor.ArmTimer](effs); !ok || a.Kind != reactor.ReconnectDue || a.After != 4*time.Second {
		t.Fatalf("want ReconnectDue in 4s (1s·2^2), got %+v", effs)
	}
	s2, effs2 := reduceSession(cfg, s, reactor.TimerFired{At: t0, Kind: reactor.ReconnectDue}, testEnv(), SessionInputs{})
	if s2.Phase != PhaseEnrolling {
		t.Fatal(s2.Phase)
	}
	if _, ok := has[reactor.Enroll](effs2); !ok {
		t.Fatal("retry must re-enroll")
	}
}

func TestConnectedSendsSessionOpen(t *testing.T) {
	in := SessionInputs{Resume: msg.Resume{LastAckedGenerationByBinding: map[string]uint64{"b1": 4}}}
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseConnecting, Enrolled: true}, reactor.Connected{At: t0}, testEnv(), in)
	if s.Phase != PhaseOpening {
		t.Fatal(s.Phase)
	}
	snd, ok := has[reactor.Send](effs)
	if !ok {
		t.Fatal("no Send")
	}
	so, ok := snd.Msg.(msg.SessionOpen)
	if !ok || so.ProtocolVersion != 1 || so.AgentVersion != "0.1.0" || so.Resume.LastAckedGenerationByBinding["b1"] != 4 || so.AgentClock != t0 || so.MessageID == "" {
		t.Fatalf("%+v", so)
	}
}

func TestSessionAcceptActivatesAndArmsHeartbeat(t *testing.T) {
	acc := msg.SessionAccept{SessionID: "s1", AssignedTargetID: "tgt", Limits: msg.Limits{HeartbeatInterval: 7 * time.Second}}
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseOpening, Attempt: 5}, reactor.WireIn{At: t0, Msg: acc}, testEnv(), SessionInputs{})
	if s.Phase != PhaseActive || s.SessionID != "s1" || s.TargetID != "tgt" || s.Attempt != 0 || s.Limits.HeartbeatInterval != 7*time.Second {
		t.Fatalf("%+v", s)
	}
	if a, ok := has[reactor.ArmTimer](effs); !ok || a.Kind != reactor.HeartbeatDue || a.After != 7*time.Second {
		t.Fatalf("heartbeat not armed: %+v", effs)
	}
}

func TestSessionRejectReEnrollHalts(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseOpening}, reactor.WireIn{At: t0, Msg: msg.SessionReject{Code: msg.ErrReEnrollRequired}}, testEnv(), SessionInputs{})
	if s.Phase != PhaseHalted {
		t.Fatal(s.Phase)
	}
	if _, ok := has[reactor.Close](effs); !ok {
		t.Fatal("must close")
	}
}

func TestErrorSupersededReconnectsNow(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseActive}, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrSessionSuperseded}}, testEnv(), SessionInputs{})
	if s.Phase != PhaseClosed {
		t.Fatal(s.Phase)
	}
	a, ok := has[reactor.ArmTimer](effs)
	if !ok || a.Kind != reactor.ReconnectDue || a.After != 0 {
		t.Fatalf("want immediate reconnect, got %+v", effs)
	}
	if _, ok := has[reactor.Close](effs); !ok {
		t.Fatal("must close")
	}
}

func TestErrorRetryHonoursRetryAfter(t *testing.T) {
	ra := 42 * time.Second
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseActive}, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrInternalUnavailable, RetryAfter: &ra}}, testEnv(), SessionInputs{})
	if s.Phase != PhaseClosed {
		t.Fatal(s.Phase)
	}
	if a, _ := has[reactor.ArmTimer](effs); a.After != ra {
		t.Fatalf("retry_after not honoured: %+v", a)
	}
}

func TestErrorLeaseRenewRequiredIsNotSessionBusiness(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseOpening}, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrLeaseRenewRequired}}, testEnv(), SessionInputs{})
	if s.Phase != PhaseOpening || len(effs) != 0 {
		t.Fatalf("session must leave LEASE_RENEW_REQUIRED to authority: %+v %+v", s, effs)
	}
}

func TestDisconnectedFromActiveBacksOff(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseActive, Attempt: 0}, reactor.Disconnected{At: t0, Cause: errors.New("eof")}, testEnv(), SessionInputs{})
	if s.Phase != PhaseClosed || s.Attempt != 1 {
		t.Fatalf("%+v", s)
	}
	if a, _ := has[reactor.ArmTimer](effs); a.Kind != reactor.ReconnectDue || a.After != time.Second {
		t.Fatalf("%+v", a)
	}
	s2, effs2 := reduceSession(cfg, s, reactor.TimerFired{At: t0, Kind: reactor.ReconnectDue}, testEnv(), SessionInputs{})
	if s2.Phase != PhaseConnecting {
		t.Fatal(s2.Phase)
	}
	if _, ok := has[reactor.Dial](effs2); !ok {
		t.Fatal("no Dial on ReconnectDue")
	}
}

func TestDisconnectedInClosedIsIgnored(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseClosed, Attempt: 1}, reactor.Disconnected{At: t0}, testEnv(), SessionInputs{})
	if s.Phase != PhaseClosed || s.Attempt != 1 || len(effs) != 0 {
		t.Fatalf("a Close we issued must not re-arm: %+v %+v", s, effs)
	}
}

func TestDrainSchedulesReconnectAfter(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseActive}, reactor.WireIn{At: t0, Msg: msg.Drain{ReconnectAfter: 30 * time.Second}}, testEnv(), SessionInputs{})
	if s.Phase != PhaseDraining {
		t.Fatal(s.Phase)
	}
	if a, _ := has[reactor.ArmTimer](effs); a.After != 30*time.Second {
		t.Fatalf("%+v", a)
	}
	if _, ok := has[reactor.Close](effs); !ok {
		t.Fatal("must close after draining sends")
	}
	s2, _ := reduceSession(cfg, s, reactor.Disconnected{At: t0}, testEnv(), SessionInputs{})
	if s2.Phase != PhaseClosed {
		t.Fatal(s2.Phase)
	}
}

func TestHeartbeatCadenceAndMissDetection(t *testing.T) {
	env := testEnv()
	s := SessionState{Phase: PhaseActive, Limits: env.Limits}
	var effs []reactor.Effect
	for i := 0; i < maxMissedHeartbeats; i++ {
		s, effs = reduceSession(cfg, s, reactor.TimerFired{At: t0, Kind: reactor.HeartbeatDue}, env, SessionInputs{LeaseRemaining: time.Minute, JournalDepth: 2})
		snd, ok := has[reactor.Send](effs)
		if !ok {
			t.Fatalf("beat %d: no Send", i)
		}
		hb := snd.Msg.(msg.Heartbeat)
		if hb.LeaseRemainingAtSend != time.Minute || hb.JournalDepth != 2 {
			t.Fatalf("%+v", hb)
		}
		if a, _ := has[reactor.ArmTimer](effs); a.Kind != reactor.HeartbeatDue {
			t.Fatal("cadence not re-armed")
		}
	}
	s, effs = reduceSession(cfg, s, reactor.TimerFired{At: t0, Kind: reactor.HeartbeatDue}, env, SessionInputs{})
	if s.Phase != PhaseClosed {
		t.Fatalf("after %d unanswered beats the connection is dead: %s", maxMissedHeartbeats, s.Phase)
	}
	if _, ok := has[reactor.Close](effs); !ok {
		t.Fatal("must close")
	}
	s = SessionState{Phase: PhaseActive, MissedHeartbeats: 2}
	s, _ = reduceSession(cfg, s, reactor.WireIn{At: t0, Msg: msg.HeartbeatAck{}}, env, SessionInputs{})
	if s.MissedHeartbeats != 0 {
		t.Fatal("ack must reset the miss counter")
	}
}

func TestRevokeHalts(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseActive}, reactor.WireIn{At: t0, Msg: msg.Revoke{Reason: "compromised"}}, testEnv(), SessionInputs{})
	if s.Phase != PhaseHalted || s.HaltCode != msg.ErrIdentityRevoked {
		t.Fatalf("%+v", s)
	}
	if _, ok := has[reactor.Halt](effs); !ok {
		t.Fatal("no Halt")
	}
}

func TestShutdownTearsDown(t *testing.T) {
	s, effs := reduceSession(cfg, SessionState{Phase: PhaseActive}, reactor.Shutdown{At: t0}, testEnv(), SessionInputs{})
	if s.Phase != PhaseClosed {
		t.Fatal(s.Phase)
	}
	snd, ok := has[reactor.Send](effs)
	if !ok {
		t.Fatal("no Teardown sent")
	}
	if _, ok := snd.Msg.(msg.Teardown); !ok {
		t.Fatal("not a Teardown")
	}
}

func TestBackoffCapsAtFiveMinutes(t *testing.T) {
	if backoffFor(0) != time.Second || backoffFor(3) != 8*time.Second || backoffFor(20) != 5*time.Minute {
		t.Fatal("backoff schedule wrong")
	}
}
```

- [ ] **Step 6: Run to verify failure**

Run: `go test ./internal/agent/`
Expected: FAIL — undefined symbols.

- [ ] **Step 7: Implement backoff and the session reducer**

`internal/agent/backoff.go`:

```go
package agent

import "time"

const (
	backoffBase = time.Second
	backoffCap  = 5 * time.Minute
)

// backoffFor is min(cap, base·2^attempt); the reducer applies env.Jitter.
func backoffFor(attempt int) time.Duration {
	if attempt < 0 {
		attempt = 0
	}
	if attempt > 20 {
		attempt = 20
	}
	d := backoffBase << uint(attempt)
	if d > backoffCap || d <= 0 {
		return backoffCap
	}
	return d
}
```

`internal/agent/session.go`:

```go
package agent

import (
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

const (
	protocolVersion     uint32 = 1
	maxMissedHeartbeats        = 3
)

type Phase string

const (
	PhaseIdle       Phase = "IDLE"
	PhaseEnrolling  Phase = "ENROLLING"
	PhaseConnecting Phase = "CONNECTING"
	PhaseOpening    Phase = "OPENING"
	PhaseActive     Phase = "ACTIVE"
	PhaseDraining   Phase = "DRAINING"
	PhaseClosed     Phase = "CLOSED"
	PhaseHalted     Phase = "HALTED"
)

type Config struct {
	BootstrapEndpoint string
	AgentVersion      string
	Capabilities      []msg.Capability
}

type SessionState struct {
	Phase            Phase
	Enrolled         bool
	Endpoint         string
	SessionID        string
	TargetID         string
	Limits           msg.Limits
	Attempt          int
	HaltCode         msg.ErrorCode
	MissedHeartbeats int
}

// SessionInputs are facts from sibling reducers the session needs on the wire.
type SessionInputs struct {
	Resume         msg.Resume
	LeaseRemaining time.Duration
	JournalDepth   uint32
}

type endpointRecord struct {
	SchemaVersion int    `json:"schema_version"`
	Endpoint      string `json:"endpoint"`
}

func (r endpointRecord) Version() int { return r.SchemaVersion }

func reduceSession(cfg Config, s SessionState, ev reactor.Event, env reactor.Env, in SessionInputs) (SessionState, []reactor.Effect) {
	if s.Phase == PhaseHalted {
		return s, nil
	}
	switch e := ev.(type) {
	case reactor.Resumed:
		if s.Endpoint == "" {
			s.Endpoint = cfg.BootstrapEndpoint
		}
		if !s.Enrolled {
			s.Phase = PhaseEnrolling
			return s, []reactor.Effect{reactor.Enroll{Endpoint: s.Endpoint}}
		}
		s.Phase = PhaseConnecting
		return s, []reactor.Effect{reactor.Dial{Endpoint: s.Endpoint}}

	case reactor.EnrollResult:
		if e.Err != nil {
			code, retry := msg.CodeOf(e.Err)
			if msg.Classify(code) == msg.ClassHalt {
				return halt(s, code)
			}
			return retryLater(s, env, retry)
		}
		s.Enrolled = true
		if e.Resp.ConnectEndpoint != "" {
			s.Endpoint = e.Resp.ConnectEndpoint
		}
		s.Phase = PhaseConnecting
		s.Attempt = 0
		return s, []reactor.Effect{
			reactor.Persist{Rec: store.JSON{RelPath: "session/endpoint", Value: endpointRecord{SchemaVersion: 1, Endpoint: s.Endpoint}}},
			reactor.Dial{Endpoint: s.Endpoint},
		}

	case reactor.TimerFired:
		switch e.Kind {
		case reactor.ReconnectDue:
			if s.Phase == PhaseEnrolling {
				return s, []reactor.Effect{reactor.Enroll{Endpoint: s.Endpoint}}
			}
			if s.Phase == PhaseClosed {
				s.Phase = PhaseConnecting
				return s, []reactor.Effect{reactor.Dial{Endpoint: s.Endpoint}}
			}
		case reactor.HeartbeatDue:
			if s.Phase != PhaseActive {
				return s, nil
			}
			if s.MissedHeartbeats >= maxMissedHeartbeats {
				return closeAndRetry(s, env, nil)
			}
			s.MissedHeartbeats++
			return s, []reactor.Effect{
				reactor.Send{Msg: msg.Heartbeat{
					Envelope:             msg.Envelope{MessageID: env.NewID(), SentAt: env.Now},
					AgentClock:           env.Now,
					JournalDepth:         in.JournalDepth,
					LeaseRemainingAtSend: in.LeaseRemaining,
				}},
				reactor.ArmTimer{Kind: reactor.HeartbeatDue, After: s.Limits.HeartbeatInterval},
			}
		}
		return s, nil

	case reactor.Connected:
		if s.Phase != PhaseConnecting {
			return s, nil
		}
		s.Phase = PhaseOpening
		return s, []reactor.Effect{reactor.Send{Msg: msg.SessionOpen{
			Envelope:              msg.Envelope{MessageID: env.NewID(), SentAt: env.Now},
			ProtocolVersion:       protocolVersion,
			AgentVersion:          cfg.AgentVersion,
			CapabilitiesSupported: cfg.Capabilities,
			Resume:                in.Resume,
			AgentClock:            env.Now,
		}}}

	case reactor.Disconnected:
		switch s.Phase {
		case PhaseDraining:
			s.Phase = PhaseClosed
			return s, nil
		case PhaseClosed, PhaseIdle, PhaseEnrolling:
			return s, nil
		default:
			return closeAndRetry(s, env, nil)
		}

	case reactor.Shutdown:
		var effs []reactor.Effect
		if s.Phase == PhaseActive || s.Phase == PhaseOpening {
			effs = append(effs, reactor.Send{Msg: msg.Teardown{Envelope: msg.Envelope{MessageID: env.NewID(), SentAt: env.Now}, Reason: "shutdown"}})
		}
		s.Phase = PhaseClosed
		return s, append(effs, reactor.Close{Reason: "shutdown"})

	case reactor.WireIn:
		return reduceSessionWire(s, e.Msg, env)
	}
	return s, nil
}

func reduceSessionWire(s SessionState, m msg.Inbound, env reactor.Env) (SessionState, []reactor.Effect) {
	switch m := m.(type) {
	case msg.SessionAccept:
		if s.Phase != PhaseOpening {
			return s, nil
		}
		s.Phase = PhaseActive
		s.SessionID, s.TargetID, s.Limits = m.SessionID, m.AssignedTargetID, m.Limits
		s.Attempt, s.MissedHeartbeats = 0, 0
		return s, []reactor.Effect{reactor.ArmTimer{Kind: reactor.HeartbeatDue, After: m.Limits.HeartbeatInterval}}

	case msg.SessionReject:
		if msg.Classify(m.Code) == msg.ClassHalt {
			s, effs := halt(s, m.Code)
			return s, append([]reactor.Effect{reactor.Close{Reason: string(m.Code)}}, effs...)
		}
		return closeAndRetry(s, env, m.RetryAfter)

	case msg.Error:
		switch msg.Classify(m.Code) {
		case msg.ClassHalt:
			s, effs := halt(s, m.Code)
			return s, append([]reactor.Effect{reactor.Close{Reason: string(m.Code)}}, effs...)
		case msg.ClassRetry:
			return closeAndRetry(s, env, m.RetryAfter)
		case msg.ClassAct:
			if m.Code == msg.ErrSessionSuperseded {
				s.Phase = PhaseClosed
				return s, []reactor.Effect{
					reactor.Close{Reason: string(m.Code)},
					reactor.ArmTimer{Kind: reactor.ReconnectDue, After: 0},
				}
			}
		}
		return s, nil // LEASE_RENEW_REQUIRED etc. belong to authority; per-command codes to commands

	case msg.Drain:
		s.Phase = PhaseDraining
		return s, []reactor.Effect{
			reactor.Close{Reason: "drain: " + m.Reason},
			reactor.ArmTimer{Kind: reactor.ReconnectDue, After: env.Jitter(m.ReconnectAfter)},
		}

	case msg.Teardown:
		return closeAndRetry(s, env, nil)

	case msg.Revoke:
		s, effs := halt(s, msg.ErrIdentityRevoked)
		return s, append([]reactor.Effect{reactor.Close{Reason: "revoked: " + m.Reason}}, effs...)

	case msg.HeartbeatAck:
		s.MissedHeartbeats = 0
		return s, nil
	}
	return s, nil
}

func halt(s SessionState, code msg.ErrorCode) (SessionState, []reactor.Effect) {
	s.Phase = PhaseHalted
	s.HaltCode = code
	return s, []reactor.Effect{reactor.Halt{Code: code}}
}

// retryLater keeps the phase (ENROLLING retries enroll, CLOSED retries dial).
func retryLater(s SessionState, env reactor.Env, retryAfter *time.Duration) (SessionState, []reactor.Effect) {
	after := env.Jitter(backoffFor(s.Attempt))
	if retryAfter != nil {
		after = *retryAfter
	}
	s.Attempt++
	return s, []reactor.Effect{reactor.ArmTimer{Kind: reactor.ReconnectDue, After: after}}
}

func closeAndRetry(s SessionState, env reactor.Env, retryAfter *time.Duration) (SessionState, []reactor.Effect) {
	s.Phase = PhaseClosed
	s.MissedHeartbeats = 0
	s, effs := retryLater(s, env, retryAfter)
	return s, append([]reactor.Effect{reactor.Close{Reason: "reconnect"}}, effs...)
}
```

- [ ] **Step 8: Run the tests**

Run: `go test ./internal/agent/ ./internal/msg/`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add internal/msg/classify.go internal/msg/classify_test.go internal/agent
git commit -m "go-agent: session reducer — enroll, dial, open, heartbeat cadence, backoff, halt

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: The loop — inbox, ordered effects, timers, connection, worker

**Files:**
- Create: `internal/testca/testca.go`, `internal/agent/state.go`, `internal/agent/loop.go`, `internal/agent/loop_test.go`

**Interfaces:**
- Produces:
  - `testca.CA{Cert *x509.Certificate; Key *ecdsa.PrivateKey}`; `func testca.New() (*CA, error)`; `func (c *CA) Sign(csrDER []byte) ([]byte, error)`; `func (c *CA) PEM() []byte`; `func (c *CA) ServerTLS(host string) (*tls.Config, error)` (used by `fakegw` in Task 10).
  - `agent.State{Session SessionState}` — later tasks add `Authority`, `Commands`, `Reports`.
  - `func Reduce(cfg Config, s State, ev reactor.Event, env reactor.Env) (State, []reactor.Effect)`.
  - `agent.Conn` interface `{ Send(msg.Outbound) error; Recv() (msg.Inbound, error); Close() error }`.
  - `agent.Wire` interface `{ Enroll(ctx, endpoint string, req msg.EnrollRequest) (*msg.EnrollResponse, error); Dial(ctx, endpoint string) (Conn, error) }`.
  - `agent.OpRunner` interface `{ Run(ctx context.Context, op reactor.RunOp) any }` — Task 19 supplies the Docker-backed implementation.
  - `agent.Deps{Config; Store; Identity; Wire; Ops OpRunner; Offset func() time.Duration; NewID; Jitter; Now; Log; Observe func(reactor.Event, []reactor.Effect)}`.
  - `func New(d Deps, initial State) *Loop`; `func (l *Loop) Push(ev reactor.Event)`; `func (l *Loop) Run(ctx) error`; `func (l *Loop) Snapshot() State` (for admin); `func (l *Loop) Halted() msg.ErrorCode`.
  - `func LoadState(cfg Config, s *store.Store, id *identity.Identity) (State, error)` — builds the initial state from disk (later tasks extend).

- [ ] **Step 1: The test CA helper**

`internal/testca/testca.go`:

```go
// Package testca is a throwaway certificate authority for tests and the fake
// gateway. Never used by the agent binary.
package testca

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"net"
	"time"
)

type CA struct {
	Cert *x509.Certificate
	Key  *ecdsa.PrivateKey
	der  []byte
}

func New() (*CA, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "sm-fake-ca"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(365 * 24 * time.Hour),
		IsCA:                  true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return nil, err
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		return nil, err
	}
	return &CA{Cert: cert, Key: key, der: der}, nil
}

func (c *CA) PEM() []byte {
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: c.der})
}

// Sign issues a client-auth leaf for the CSR, valid one hour.
func (c *CA) Sign(csrDER []byte) ([]byte, error) {
	csr, err := x509.ParseCertificateRequest(csrDER)
	if err != nil {
		return nil, err
	}
	if err := csr.CheckSignature(); err != nil {
		return nil, err
	}
	serial, _ := rand.Int(rand.Reader, big.NewInt(1<<62))
	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject:      csr.Subject,
		NotBefore:    time.Now().Add(-time.Minute),
		NotAfter:     time.Now().Add(time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
	}
	return x509.CreateCertificate(rand.Reader, tmpl, c.Cert, csr.PublicKey, c.Key)
}

// ServerTLS issues a server certificate for host and returns a TLS config
// that requests (but does not require) a client certificate, so Enroll can be
// cert-less while Connect can check for one.
func (c *CA) ServerTLS(host string) (*tls.Config, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(2),
		Subject:      pkix.Name{CommonName: host},
		DNSNames:     []string{host},
		NotBefore:    time.Now().Add(-time.Minute),
		NotAfter:     time.Now().Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	if ip := net.ParseIP(host); ip != nil {
		tmpl.IPAddresses = []net.IP{ip}
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, c.Cert, &key.PublicKey, c.Key)
	if err != nil {
		return nil, err
	}
	pool := x509.NewCertPool()
	pool.AddCert(c.Cert)
	return &tls.Config{
		Certificates: []tls.Certificate{{Certificate: [][]byte{der, c.der}, PrivateKey: key}},
		ClientCAs:    pool,
		ClientAuth:   tls.VerifyClientCertIfGiven,
		MinVersion:   tls.VersionTLS13,
		NextProtos:   []string{"h2"},
	}, nil
}
```

- [ ] **Step 2: Write the failing loop test**

`internal/agent/loop_test.go`:

```go
package agent

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/testca"
)

// fakeConn is a channel-backed Conn: the test reads what the agent sends and
// injects what the gateway would say.
type fakeConn struct {
	sent   chan msg.Outbound
	inject chan msg.Inbound
	closed chan struct{}
	once   sync.Once
}

func newFakeConn() *fakeConn {
	return &fakeConn{sent: make(chan msg.Outbound, 64), inject: make(chan msg.Inbound, 64), closed: make(chan struct{})}
}
func (c *fakeConn) Send(m msg.Outbound) error { c.sent <- m; return nil }
func (c *fakeConn) Recv() (msg.Inbound, error) {
	select {
	case m := <-c.inject:
		return m, nil
	case <-c.closed:
		return nil, io.EOF
	}
}
func (c *fakeConn) Close() error { c.once.Do(func() { close(c.closed) }); return nil }

type fakeWire struct {
	ca       *testca.CA
	enrolls  int
	conns    []*fakeConn
	mu       sync.Mutex
	failNext error
}

func (w *fakeWire) Enroll(_ context.Context, _ string, req msg.EnrollRequest) (*msg.EnrollResponse, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.enrolls++
	if w.failNext != nil {
		err := w.failNext
		w.failNext = nil
		return nil, err
	}
	leaf, err := w.ca.Sign(req.CSRDER)
	if err != nil {
		return nil, err
	}
	return &msg.EnrollResponse{AgentID: "agent-1", CertificateChain: [][]byte{leaf},
		TrustBundle: msg.TrustBundle{CertsPEM: [][]byte{w.ca.PEM()}}, ConnectEndpoint: "dp:443"}, nil
}

func (w *fakeWire) Dial(context.Context, string) (Conn, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	c := newFakeConn()
	w.conns = append(w.conns, c)
	return c, nil
}

func (w *fakeWire) conn(i int) *fakeConn {
	for {
		w.mu.Lock()
		if len(w.conns) > i {
			c := w.conns[i]
			w.mu.Unlock()
			return c
		}
		w.mu.Unlock()
		time.Sleep(5 * time.Millisecond)
	}
}

func expect[T msg.Outbound](t *testing.T, c *fakeConn) T {
	t.Helper()
	select {
	case m := <-c.sent:
		v, ok := m.(T)
		if !ok {
			t.Fatalf("expected %T, got %T", *new(T), m)
		}
		return v
	case <-time.After(2 * time.Second):
		t.Fatalf("timed out waiting for %T", *new(T))
	}
	panic("unreachable")
}

func newTestLoop(t *testing.T, w *fakeWire) (*Loop, *store.Store) {
	t.Helper()
	s, err := store.Open(filepath.Join(t.TempDir(), "d"))
	if err != nil {
		t.Fatal(err)
	}
	id, err := identity.Load(s)
	if err != nil {
		t.Fatal(err)
	}
	st, err := LoadState(cfg, s, id)
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	var mu sync.Mutex
	l := New(Deps{
		Config: cfg, Store: s, Identity: id, Wire: w,
		Offset: func() time.Duration { return 0 },
		NewID:  func() string { mu.Lock(); defer mu.Unlock(); n++; return "id-" + time.Duration(n).String() },
		Jitter: func(d time.Duration) time.Duration { return d },
		Now:    time.Now,
		Log:    slog.New(slog.NewTextHandler(io.Discard, nil)),
	}, st)
	return l, s
}

func TestLoopEnrollsOpensAndHeartbeats(t *testing.T) {
	ca, _ := testca.New()
	w := &fakeWire{ca: ca}
	l, s := newTestLoop(t, w)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- l.Run(ctx) }()

	c := w.conn(0)
	so := expect[msg.SessionOpen](t, c)
	if so.ProtocolVersion != 1 {
		t.Fatalf("%+v", so)
	}
	if !s.Exists("identity/cert.pem") || !s.Exists("session/endpoint") {
		t.Fatal("enrollment and endpoint must be persisted before dialing")
	}
	c.inject <- msg.SessionAccept{SessionID: "s1", Limits: msg.Limits{HeartbeatInterval: 20 * time.Millisecond}}
	hb := expect[msg.Heartbeat](t, c)
	if hb.MessageID == "" {
		t.Fatal("heartbeat has no message id")
	}
	expect[msg.Heartbeat](t, c)
	if l.Snapshot().Session.Phase != PhaseActive {
		t.Fatal(l.Snapshot().Session.Phase)
	}

	cancel()
	expect[msg.Teardown](t, c)
	select {
	case <-c.closed:
	case <-time.After(time.Second):
		t.Fatal("conn not closed on shutdown")
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestLoopReconnectsAfterDisconnect(t *testing.T) {
	ca, _ := testca.New()
	w := &fakeWire{ca: ca}
	l, _ := newTestLoop(t, w)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = l.Run(ctx) }()

	c0 := w.conn(0)
	expect[msg.SessionOpen](t, c0)
	c0.inject <- msg.SessionAccept{SessionID: "s1", Limits: msg.Limits{HeartbeatInterval: time.Hour}}
	c0.Close() // gateway drops us → backoff 1s → dial again
	c1 := w.conn(1)
	expect[msg.SessionOpen](t, c1)
}

func TestLoopStaleDisconnectIsIgnored(t *testing.T) {
	ca, _ := testca.New()
	w := &fakeWire{ca: ca}
	l, _ := newTestLoop(t, w)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = l.Run(ctx) }()
	c0 := w.conn(0)
	expect[msg.SessionOpen](t, c0)
	c0.inject <- msg.Error{Code: msg.ErrSessionSuperseded} // Close + reconnect now
	c1 := w.conn(1)
	expect[msg.SessionOpen](t, c1)
	c0.Close() // late EOF from the old conn must not disturb the new one
	c1.inject <- msg.SessionAccept{SessionID: "s2", Limits: msg.Limits{HeartbeatInterval: time.Hour}}
	time.Sleep(50 * time.Millisecond)
	if p := l.Snapshot().Session.Phase; p != PhaseActive {
		t.Fatalf("stale disconnect disturbed the session: %s", p)
	}
}

func TestLoopHaltsOnTokenConsumed(t *testing.T) {
	ca, _ := testca.New()
	w := &fakeWire{ca: ca, failNext: &msg.ProtocolError{Code: msg.ErrTokenConsumed}}
	l, _ := newTestLoop(t, w)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = l.Run(ctx) }()
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		if l.Halted() == msg.ErrTokenConsumed {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatal("loop did not halt")
}

func TestLoopPersistFailureIsFatal(t *testing.T) {
	ca, _ := testca.New()
	w := &fakeWire{ca: ca}
	l, s := newTestLoop(t, w)
	// Make session/ unwritable so the endpoint persist fails.
	if err := os.Chmod(s.Path("session"), 0o500); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.Chmod(s.Path("session"), 0o700) })
	err := l.Run(context.Background())
	if err == nil || !errors.Is(err, ErrFatalPersist) {
		t.Fatalf("want ErrFatalPersist, got %v", err)
	}
}
```

- [ ] **Step 3: Run to verify failure**

Run: `go test ./internal/agent/ -run Loop`
Expected: FAIL — undefined `Loop`, `Deps`, `LoadState`.

- [ ] **Step 4: Implement state.go**

`internal/agent/state.go`:

```go
package agent

import (
	"errors"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// State is the composite the reducers share. Later tasks add Authority,
// Commands and Reports; Reduce dispatches in that fixed order.
type State struct {
	Session SessionState
}

func Reduce(cfg Config, s State, ev reactor.Event, env reactor.Env) (State, []reactor.Effect) {
	in := SessionInputs{}
	var effs []reactor.Effect
	s.Session, effs = reduceSession(cfg, s.Session, ev, env, in)
	return s, effs
}

func LoadState(cfg Config, s *store.Store, id *identity.Identity) (State, error) {
	st := State{Session: SessionState{Phase: PhaseIdle, Enrolled: id.Enrolled(), Endpoint: cfg.BootstrapEndpoint}}
	rec, err := store.LoadJSON[endpointRecord](s, "session/endpoint", 1)
	switch {
	case err == nil:
		st.Session.Endpoint = rec.Endpoint
	case errors.Is(err, store.ErrNotFound):
	default:
		return st, err
	}
	return st, nil
}
```

- [ ] **Step 5: Implement loop.go**

`internal/agent/loop.go`:

```go
package agent

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"runtime"
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

var ErrFatalPersist = errors.New("agent: persist failed; refusing to continue without durability")

type Conn interface {
	Send(msg.Outbound) error
	Recv() (msg.Inbound, error)
	Close() error
}

type Wire interface {
	Enroll(ctx context.Context, endpoint string, req msg.EnrollRequest) (*msg.EnrollResponse, error)
	Dial(ctx context.Context, endpoint string) (Conn, error)
}

// OpRunner executes one runtime operation; the loop guarantees at most one
// in flight. Task 19 supplies the Docker-backed implementation.
type OpRunner interface {
	Run(ctx context.Context, op reactor.RunOp) any
}

type Deps struct {
	Config   Config
	Store    *store.Store
	Identity *identity.Identity
	Wire     Wire
	Ops      OpRunner
	Offset   func() time.Duration
	NewID    func() string
	Jitter   func(time.Duration) time.Duration
	Now      func() time.Time
	Log      *slog.Logger
	Observe  func(reactor.Event, []reactor.Effect)
}

// loop-private events
type dialResult struct {
	at   time.Time
	conn Conn
	err  error
}
type connClosed struct {
	at  time.Time
	gen int
	err error
}

func (e dialResult) EventAt() time.Time { return e.at }
func (e connClosed) EventAt() time.Time { return e.at }

// writer commands
type flushAndClose struct{}

type Loop struct {
	d      Deps
	inbox  chan reactor.Event
	timers map[reactor.TimerKind]*time.Timer
	ops    chan reactor.RunOp

	mu     sync.Mutex
	state  State
	halted msg.ErrorCode

	conn    Conn
	connGen int
	sendCh  chan any // msg.Outbound | flushAndClose
	writerDone chan struct{}
}

func New(d Deps, initial State) *Loop {
	if d.Now == nil {
		d.Now = time.Now
	}
	if d.Offset == nil {
		d.Offset = func() time.Duration { return 0 }
	}
	if d.Log == nil {
		d.Log = slog.Default()
	}
	return &Loop{
		d:      d,
		inbox:  make(chan reactor.Event, 1024),
		timers: map[reactor.TimerKind]*time.Timer{},
		ops:    make(chan reactor.RunOp, 16),
		state:  initial,
	}
}

func (l *Loop) Push(ev reactor.Event) { l.inbox <- ev }

func (l *Loop) Snapshot() State {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.state
}

func (l *Loop) Halted() msg.ErrorCode {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.halted
}

func (l *Loop) Run(ctx context.Context) error {
	go l.worker(ctx)
	go func() {
		<-ctx.Done()
		l.Push(reactor.Shutdown{At: l.d.Now(), Cause: ctx.Err()})
	}()
	l.Push(reactor.Resumed{At: l.d.Now()})

	for raw := range l.inbox {
		ev, ok := l.translate(raw)
		if !ok {
			continue
		}
		l.mu.Lock()
		st := l.state
		l.mu.Unlock()
		env := reactor.Env{Now: ev.EventAt(), Offset: l.d.Offset(), NewID: l.d.NewID, Jitter: l.d.Jitter, Limits: st.Session.Limits}
		next, effs := Reduce(l.d.Config, st, ev, env)
		effs = reactor.Sort(effs)
		l.mu.Lock()
		l.state = next
		l.mu.Unlock()
		if l.d.Observe != nil {
			l.d.Observe(ev, effs)
		}
		l.d.Log.Debug("event", "type", fmt.Sprintf("%T", ev), "effects", len(effs))
		if err := l.execute(ctx, effs); err != nil {
			l.closeConn()
			return err
		}
		if _, isShutdown := ev.(reactor.Shutdown); isShutdown {
			l.stopTimers()
			return nil
		}
	}
	return nil
}

// translate turns loop-private events into public ones, dropping stale
// connection events.
func (l *Loop) translate(raw reactor.Event) (reactor.Event, bool) {
	switch e := raw.(type) {
	case dialResult:
		if e.err != nil {
			return reactor.Disconnected{At: e.at, Cause: e.err}, true
		}
		l.attach(e.conn)
		return reactor.Connected{At: e.at}, true
	case connClosed:
		if e.gen != l.connGen {
			return nil, false
		}
		l.closeConn() // the peer dropped us: stop the writer too, never just dereference
		return reactor.Disconnected{At: e.at, Cause: e.err}, true
	default:
		return raw, true
	}
}

func (l *Loop) attach(c Conn) {
	l.closeConn()
	l.connGen++
	gen := l.connGen
	l.conn = c
	l.sendCh = make(chan any, 256)
	l.writerDone = make(chan struct{})
	go l.writer(c, l.sendCh, l.writerDone)
	go func() {
		for {
			m, err := c.Recv()
			if err != nil {
				l.Push(connClosed{at: l.d.Now(), gen: gen, err: err})
				return
			}
			l.Push(reactor.WireIn{At: l.d.Now(), Msg: m})
		}
	}()
}

func (l *Loop) writer(c Conn, ch chan any, done chan struct{}) {
	defer close(done)
	for item := range ch {
		switch v := item.(type) {
		case flushAndClose:
			_ = c.Close()
			return
		case msg.Outbound:
			if err := c.Send(v); err != nil {
				l.d.Log.Warn("send failed", "err", err)
			}
		}
	}
}

func (l *Loop) closeConn() {
	if l.conn == nil {
		return
	}
	l.sendCh <- flushAndClose{}
	close(l.sendCh)
	<-l.writerDone
	l.conn = nil
}

func (l *Loop) execute(ctx context.Context, effs []reactor.Effect) error {
	for _, eff := range effs {
		switch e := eff.(type) {
		case reactor.Persist:
			if err := l.d.Store.Save(e.Rec); err != nil {
				l.d.Log.Error("persist failed", "rel", e.Rec.Rel(), "err", err)
				return fmt.Errorf("%w: %s: %v", ErrFatalPersist, e.Rec.Rel(), err)
			}
		case reactor.Delete:
			if err := l.d.Store.Delete(e.Rel); err != nil {
				return fmt.Errorf("%w: delete %s: %v", ErrFatalPersist, e.Rel, err)
			}
		case reactor.Send:
			if l.conn == nil {
				l.d.Log.Warn("send with no connection", "type", fmt.Sprintf("%T", e.Msg))
				continue
			}
			l.sendCh <- e.Msg
		case reactor.RunOp:
			l.ops <- e
		case reactor.ArmTimer:
			l.arm(e.Kind, e.After)
		case reactor.CancelTimer:
			if t := l.timers[e.Kind]; t != nil {
				t.Stop()
				delete(l.timers, e.Kind)
			}
		case reactor.Enroll:
			go l.enroll(ctx, e.Endpoint)
		case reactor.Dial:
			go func() {
				c, err := l.d.Wire.Dial(ctx, e.Endpoint)
				l.Push(dialResult{at: l.d.Now(), conn: c, err: err})
			}()
		case reactor.Close:
			l.closeConn()
		case reactor.Halt:
			l.mu.Lock()
			l.halted = e.Code
			l.mu.Unlock()
			l.d.Log.Error("halted", "code", e.Code)
		}
	}
	return nil
}

func (l *Loop) arm(kind reactor.TimerKind, after time.Duration) {
	if t := l.timers[kind]; t != nil {
		t.Stop()
	}
	l.timers[kind] = time.AfterFunc(after, func() {
		l.Push(reactor.TimerFired{At: l.d.Now(), Kind: kind})
	})
}

func (l *Loop) stopTimers() {
	for k, t := range l.timers {
		t.Stop()
		delete(l.timers, k)
	}
}

func (l *Loop) enroll(ctx context.Context, endpoint string) {
	csr, err := l.d.Identity.CSR()
	if err != nil {
		l.Push(reactor.EnrollResult{At: l.d.Now(), Err: err})
		return
	}
	req := msg.EnrollRequest{
		CSRDER:                    csr,
		AgentVersion:              l.d.Config.AgentVersion,
		ProtocolVersionsSupported: []uint32{protocolVersion},
		HostFacts:                 msg.HostFacts{OS: runtime.GOOS, Arch: runtime.GOARCH, RuntimeKind: "docker"},
	}
	resp, err := l.d.Wire.Enroll(ctx, endpoint, req)
	if err == nil {
		err = l.d.Identity.StoreEnrollment(*resp)
	}
	l.Push(reactor.EnrollResult{At: l.d.Now(), Resp: resp, Err: err})
}

func (l *Loop) worker(ctx context.Context) {
	for op := range l.ops {
		if l.d.Ops == nil {
			l.d.Log.Error("RunOp with no OpRunner", "command", op.CommandID)
			continue
		}
		res := l.d.Ops.Run(ctx, op)
		l.Push(reactor.OpFinished{At: l.d.Now(), CommandID: op.CommandID, Result: res})
	}
}
```

- [ ] **Step 6: Run the tests**

Run: `go test ./internal/agent/ -race`
Expected: PASS, no races. (`TestLoopReconnectsAfterDisconnect` takes ~1 s for the backoff.)

- [ ] **Step 7: Commit**

```bash
git add internal/testca internal/agent
git commit -m "go-agent: reactor loop — inbox, ordered effects, timers, connection lifecycle, op worker

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: Wire codec and gRPC client

**Files:**
- Modify: `internal/msg/msg.go` (add `Token string` to `EnrollRequest` — carried as metadata, never persisted), `internal/agent/session.go` (`Config` gains `EnrollmentToken string`), `internal/agent/loop.go` (`enroll` sets `req.Token`)
- Create: `internal/wire/codec/codec.go`, `internal/wire/codec/codec_test.go`, `internal/wire/client.go`

**Interfaces:**
- Produces:
  - `codec.EnrollRequestToPB/FromPB`, `codec.EnrollResponseToPB/FromPB`
  - `codec.AgentToPB(msg.Outbound) (*pb.AgentToGateway, error)`, `codec.AgentFromPB(*pb.AgentToGateway) (msg.Outbound, error)`
  - `codec.GatewayToPB(msg.Inbound) (*pb.GatewayToAgent, error)`, `codec.GatewayFromPB(*pb.GatewayToAgent) (msg.Inbound, error)`
  - `codec.StatusFromError(msg.Error) error` (gRPC status with the `pb.Error` as detail), `codec.ErrorFromStatus(error) error` (→ `*msg.ProtocolError` when a detail is present, else the input)
  - `wire.Client` with `New(id *identity.Identity, bootstrapBundlePEM []byte, log *slog.Logger) *Client`, `Enroll(ctx, endpoint, req) (*msg.EnrollResponse, error)`, `Dial(ctx, endpoint) (*Conn, error)`; `*wire.Conn` satisfies `agent.Conn`.
  - `const MetadataTokenKey = "x-sm-enrollment-token"`.

- [ ] **Step 1: Add the token field and config**

In `internal/msg/msg.go`, change `EnrollRequest` to:

```go
type EnrollRequest struct {
	Token                     string // gRPC metadata x-sm-enrollment-token; never persisted, never logged
	CSRDER                    []byte
	AgentVersion              string
	ProtocolVersionsSupported []uint32
	HostFacts                 HostFacts
}
```

In `internal/agent/session.go`, change `Config` to:

```go
type Config struct {
	BootstrapEndpoint string
	EnrollmentToken   string
	AgentVersion      string
	Capabilities      []msg.Capability
}
```

In `internal/agent/loop.go` `enroll`, add `Token: l.d.Config.EnrollmentToken,` as the first field of `req`.

Run: `go test ./internal/agent/ ./internal/msg/` — Expected: PASS (no behaviour changed).

- [ ] **Step 2: Write the failing codec round-trip test**

`internal/wire/codec/codec_test.go`:

```go
package codec

import (
	"errors"
	"reflect"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

var now = time.Date(2026, 9, 22, 12, 0, 0, 0, time.UTC)

func u64(v uint64) *uint64 { return &v }

func TestAgentMessagesRoundTrip(t *testing.T) {
	ra := 5 * time.Second
	_ = ra
	env := msg.Envelope{MessageID: "m", SentAt: now}
	cases := []msg.Outbound{
		msg.SessionOpen{Envelope: env, ProtocolVersion: 1, AgentVersion: "v", CapabilitiesSupported: []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle},
			Resume: msg.Resume{LastAckedGenerationByBinding: map[string]uint64{"b": 3}, LastTelemetrySeqByStream: map[string]uint64{}}, AgentClock: now},
		msg.LeaseRenew{Envelope: env, NonceConsumed: "n", AgentClock: now},
		msg.CommandPull{Envelope: env, CommandID: "c"},
		msg.CommandReceived{Envelope: env, CommandID: "c", Generation: 2},
		msg.CommandAck{Envelope: env, CommandID: "c", Generation: 2, Outcome: msg.OutcomeRefused,
			Detail: msg.AckDetail{Code: msg.DetailAdmissionBlocked, Message: "x"}, AgentClock: now},
		msg.CommandAck{Envelope: env, CommandID: "c", Generation: 2, Outcome: msg.OutcomePreflightOK,
			Detail: msg.AckDetail{PreflightResult: &msg.PreflightResult{ContainerID: "id", HostPath: "/h", Writable: true, CompetingSources: []string{"a"}}}, AgentClock: now},
		msg.CommandAck{Envelope: env, CommandID: "c", Generation: 2, Outcome: msg.OutcomeRefused,
			Detail: msg.AckDetail{Code: msg.DetailOf(msg.ErrKeyHashMismatch)}, AgentClock: now},
		msg.ApplyProgress{Envelope: env, CommandID: "c", Step: msg.StepPersisted, ReplicaRef: "r", At: now},
		msg.ActualStateReportBatch{Envelope: env, Items: []msg.ActualStateReport{{
			ItemID: "i", Sequence: 9, BindingID: "b", ReplicaRef: "r", Incarnation: "abc", PersistedGeneration: 2,
			RestartObservedAt: &now, EffectiveHash: "h", VersionObservation: msg.VersionObservation{Mechanism: "image-digest", Value: "sha", Confidence: "low"}}}},
		msg.Heartbeat{Envelope: env, AgentClock: now, JournalDepth: 1, LeaseRemainingAtSend: time.Minute},
		msg.Teardown{Envelope: env, Reason: "bye"},
	}
	for _, in := range cases {
		p, err := AgentToPB(in)
		if err != nil {
			t.Fatalf("%T: %v", in, err)
		}
		out, err := AgentFromPB(p)
		if err != nil {
			t.Fatalf("%T: %v", in, err)
		}
		if !reflect.DeepEqual(in, out) {
			t.Errorf("%T round trip:\n in=%+v\nout=%+v", in, in, out)
		}
	}
}

func TestGatewayMessagesRoundTrip(t *testing.T) {
	ra := 5 * time.Second
	env := msg.Envelope{MessageID: "m", SentAt: now}
	grant := msg.LeaseGrant{Envelope: env, AgentID: "a", CnfThumbprint: "tp", TargetID: "t", BindingIDs: []string{"b"},
		AgentRevocationEpoch: 1, FleetRevocationEpoch: 2, IssuedAt: now, ExpiresAt: now.Add(time.Hour), Audience: "dp", Nonce: "n",
		Capabilities: []msg.Capability{msg.CapApplyConfig}, VerbCeilings: map[msg.Operation]time.Duration{msg.OpApply: 2 * time.Minute},
		AdmissionMargin: 30 * time.Second, ServerClock: now}
	cases := []msg.Inbound{
		msg.SessionAccept{Envelope: env, SessionID: "s", AssignedTargetID: "t", RequiredCapabilities: []msg.Capability{msg.CapLifecycle}, Lease: grant,
			Limits: msg.Limits{MaxMessageBytes: 1, MaxBatchBytes: 2, InitialCredits: 3, HeartbeatInterval: time.Second, KeepaliveInterval: 2 * time.Second, SkewTolerance: 3 * time.Second, ReportInterval: 4 * time.Second}, ServerClock: now},
		msg.SessionReject{Envelope: env, Code: msg.ErrReEnrollRequired, RetryAfter: nil, MinAgentVersion: "2"},
		msg.SessionReject{Envelope: env, Code: msg.ErrAdmissionLimited, RetryAfter: &ra},
		grant,
		msg.Revoke{Envelope: env, AgentRevocationEpoch: u64(5), Reason: "r"},
		msg.GenerationAvailable{Envelope: env, CommandID: "c", BindingID: "b", Generation: 3, Operation: msg.OpRestart, ConfigSHA256: "h", Deadline: now},
		msg.CommandOffer{Envelope: env, CommandID: "c", Generation: 3, IdempotencyKey: "k", BindingID: "b", Operation: msg.OpApply, Payload: []byte("x"), ConfigSHA256: "h", SupersedesGeneration: u64(2), Deadline: now},
		msg.ReportAck{Envelope: env, InReplyToMessageID: "m0", Results: []msg.ReportResult{{InReplyToItemID: "i", Sequence: 9, ReplicaID: "rid", IncarnationID: "iid", Trust: msg.TrustTrusted, Disposition: msg.DispApplied},
			{InReplyToItemID: "j", Sequence: 10, Trust: msg.TrustUntrusted, Disposition: msg.DispRefused, RefusedReason: msg.ErrReplicaCapExceeded}}},
		msg.HeartbeatAck{Envelope: env, ServerClock: now, ClockOffsetMs: -12, ClockState: msg.ClockSuspect},
		msg.TrustBundleUpdate{Envelope: env, TrustBundle: msg.TrustBundle{CertsPEM: [][]byte{[]byte("pem")}, ValidFrom: now, ValidTo: now.Add(time.Hour)}},
		msg.Drain{Envelope: env, Reason: "restart", ReconnectAfter: 7 * time.Second},
		msg.Teardown{Envelope: env, Reason: "bye"},
		msg.Error{Envelope: env, Code: msg.ErrDraining, RetryAfter: &ra, CorrelationID: "x", Message: "m"},
	}
	for _, in := range cases {
		p, err := GatewayToPB(in)
		if err != nil {
			t.Fatalf("%T: %v", in, err)
		}
		out, err := GatewayFromPB(p)
		if err != nil {
			t.Fatalf("%T: %v", in, err)
		}
		if !reflect.DeepEqual(in, out) {
			t.Errorf("%T round trip:\n in=%+v\nout=%+v", in, in, out)
		}
	}
}

func TestUnknownErrorCodeMapsToUnknown(t *testing.T) {
	p, _ := GatewayToPB(msg.Error{Code: msg.ErrorCode("FUTURE_CODE")})
	out, _ := GatewayFromPB(p)
	if out.(msg.Error).Code != msg.ErrUnknown {
		t.Fatalf("got %s", out.(msg.Error).Code)
	}
}

func TestStatusRoundTrip(t *testing.T) {
	ra := time.Second
	err := StatusFromError(msg.Error{Code: msg.ErrTokenConsumed, RetryAfter: &ra, Message: "used"})
	got := ErrorFromStatus(err)
	var pe *msg.ProtocolError
	if !errors.As(got, &pe) || pe.Code != msg.ErrTokenConsumed || pe.RetryAfter == nil || *pe.RetryAfter != ra {
		t.Fatalf("%v", got)
	}
	plain := errors.New("dial: refused")
	if ErrorFromStatus(plain) != plain {
		t.Fatal("non-status errors must pass through")
	}
}
```

- [ ] **Step 3: Run to verify failure**

Run: `go test ./internal/wire/codec/`
Expected: FAIL — package missing.

- [ ] **Step 4: Implement the codec**

`internal/wire/codec/codec.go`:

```go
// Package codec maps the generated DRAFT protobuf to internal/msg and back.
// Both directions exist because the fake gateway speaks the same wire.
package codec

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/durationpb"
	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

// ---- enum helpers: proto values carry the enum-name prefix ----

func enumTo[E ~int32](values map[string]int32, prefix, name string) E {
	return E(values[prefix+strings.ToUpper(strings.ReplaceAll(name, "-", "_"))])
}

func enumFrom[E ~int32](names map[int32]string, prefix string, v E) string {
	return strings.TrimPrefix(names[int32(v)], prefix)
}

func opToPB(o msg.Operation) pb.Operation   { return enumTo[pb.Operation](pb.Operation_value, "OPERATION_", string(o)) }
func opFromPB(p pb.Operation) msg.Operation { return msg.Operation(strings.ToLower(enumFrom(pb.Operation_name, "OPERATION_", p))) }

func capToPB(c msg.Capability) pb.Capability { return enumTo[pb.Capability](pb.Capability_value, "CAPABILITY_", string(c)) }
func capFromPB(p pb.Capability) msg.Capability {
	return msg.Capability(strings.ToLower(strings.ReplaceAll(enumFrom(pb.Capability_name, "CAPABILITY_", p), "_", "-")))
}
func capsToPB(cs []msg.Capability) []pb.Capability {
	out := make([]pb.Capability, 0, len(cs))
	for _, c := range cs {
		out = append(out, capToPB(c))
	}
	return out
}
func capsFromPB(ps []pb.Capability) []msg.Capability {
	if len(ps) == 0 {
		return nil
	}
	out := make([]msg.Capability, 0, len(ps))
	for _, p := range ps {
		out = append(out, capFromPB(p))
	}
	return out
}

func outcomeToPB(o msg.Outcome) pb.Outcome   { return enumTo[pb.Outcome](pb.Outcome_value, "OUTCOME_", string(o)) }
func outcomeFromPB(p pb.Outcome) msg.Outcome { return msg.Outcome(enumFrom(pb.Outcome_name, "OUTCOME_", p)) }
func stepToPB(s msg.ProgressStep) pb.ProgressStep {
	return enumTo[pb.ProgressStep](pb.ProgressStep_value, "PROGRESS_STEP_", string(s))
}
func stepFromPB(p pb.ProgressStep) msg.ProgressStep {
	return msg.ProgressStep(enumFrom(pb.ProgressStep_name, "PROGRESS_STEP_", p))
}
func trustToPB(t msg.Trust) pb.Trust   { return enumTo[pb.Trust](pb.Trust_value, "TRUST_", string(t)) }
func trustFromPB(p pb.Trust) msg.Trust { return msg.Trust(enumFrom(pb.Trust_name, "TRUST_", p)) }
func dispToPB(d msg.Disposition) pb.Disposition {
	return enumTo[pb.Disposition](pb.Disposition_value, "DISPOSITION_", string(d))
}
func dispFromPB(p pb.Disposition) msg.Disposition {
	return msg.Disposition(enumFrom(pb.Disposition_name, "DISPOSITION_", p))
}
func clockToPB(c msg.ClockState) pb.ClockState {
	return enumTo[pb.ClockState](pb.ClockState_value, "CLOCK_STATE_", string(c))
}
func clockFromPB(p pb.ClockState) msg.ClockState {
	return msg.ClockState(enumFrom(pb.ClockState_name, "CLOCK_STATE_", p))
}

func errToPB(c msg.ErrorCode) pb.ErrorCode { return enumTo[pb.ErrorCode](pb.ErrorCode_value, "ERROR_CODE_", string(c)) }
func errFromPB(p pb.ErrorCode) msg.ErrorCode {
	if p == pb.ErrorCode_ERROR_CODE_UNSPECIFIED {
		return msg.ErrUnknown
	}
	s := enumFrom(pb.ErrorCode_name, "ERROR_CODE_", p)
	if s == "" {
		return msg.ErrUnknown
	}
	return msg.ErrorCode(s)
}

// ---- scalar helpers ----

func ts(t time.Time) *timestamppb.Timestamp {
	if t.IsZero() {
		return nil
	}
	return timestamppb.New(t)
}
func tsFrom(p *timestamppb.Timestamp) time.Time {
	if p == nil {
		return time.Time{}
	}
	return p.AsTime()
}
func tsPtr(t *time.Time) *timestamppb.Timestamp {
	if t == nil {
		return nil
	}
	return timestamppb.New(*t)
}
func tsPtrFrom(p *timestamppb.Timestamp) *time.Time {
	if p == nil {
		return nil
	}
	t := p.AsTime()
	return &t
}
func dur(d time.Duration) *durationpb.Duration { return durationpb.New(d) }
func durFrom(p *durationpb.Duration) time.Duration {
	if p == nil {
		return 0
	}
	return p.AsDuration()
}
func durPtr(d *time.Duration) *durationpb.Duration {
	if d == nil {
		return nil
	}
	return durationpb.New(*d)
}
func durPtrFrom(p *durationpb.Duration) *time.Duration {
	if p == nil {
		return nil
	}
	d := p.AsDuration()
	return &d
}
func strOrEmpty(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}
func strPtr(s string) *string {
	if s == "" {
		return nil
	}
	return proto.String(s)
}

func envToPB(e msg.Envelope) *pb.Envelope { return &pb.Envelope{MessageId: e.MessageID, SentAt: ts(e.SentAt)} }
func envFromPB(p *pb.Envelope) msg.Envelope {
	if p == nil {
		return msg.Envelope{}
	}
	return msg.Envelope{MessageID: p.GetMessageId(), SentAt: tsFrom(p.GetSentAt())}
}

func bundleToPB(b msg.TrustBundle) *pb.TrustBundle {
	return &pb.TrustBundle{CertsPem: b.CertsPEM, ValidFrom: ts(b.ValidFrom), ValidTo: ts(b.ValidTo)}
}
func bundleFromPB(p *pb.TrustBundle) msg.TrustBundle {
	if p == nil {
		return msg.TrustBundle{}
	}
	return msg.TrustBundle{CertsPEM: p.GetCertsPem(), ValidFrom: tsFrom(p.GetValidFrom()), ValidTo: tsFrom(p.GetValidTo())}
}

// ---- Enroll ----

func EnrollRequestToPB(r msg.EnrollRequest) *pb.EnrollRequest {
	return &pb.EnrollRequest{
		CsrDer: r.CSRDER, AgentVersion: r.AgentVersion, ProtocolVersionsSupported: r.ProtocolVersionsSupported,
		HostFacts: &pb.HostFacts{Os: r.HostFacts.OS, Arch: r.HostFacts.Arch, RuntimeKind: r.HostFacts.RuntimeKind},
	}
}

func EnrollRequestFromPB(p *pb.EnrollRequest) msg.EnrollRequest {
	return msg.EnrollRequest{
		CSRDER: p.GetCsrDer(), AgentVersion: p.GetAgentVersion(), ProtocolVersionsSupported: p.GetProtocolVersionsSupported(),
		HostFacts: msg.HostFacts{OS: p.GetHostFacts().GetOs(), Arch: p.GetHostFacts().GetArch(), RuntimeKind: p.GetHostFacts().GetRuntimeKind()},
	}
}

func EnrollResponseToPB(r msg.EnrollResponse) *pb.EnrollResponse {
	return &pb.EnrollResponse{AgentId: r.AgentID, CertificateChain: r.CertificateChain, TrustBundle: bundleToPB(r.TrustBundle), ConnectEndpoint: r.ConnectEndpoint}
}

func EnrollResponseFromPB(p *pb.EnrollResponse) msg.EnrollResponse {
	return msg.EnrollResponse{AgentID: p.GetAgentId(), CertificateChain: p.GetCertificateChain(), TrustBundle: bundleFromPB(p.GetTrustBundle()), ConnectEndpoint: p.GetConnectEndpoint()}
}

// ---- Agent → Gateway ----

func AgentToPB(m msg.Outbound) (*pb.AgentToGateway, error) {
	switch v := m.(type) {
	case msg.SessionOpen:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_SessionOpen{SessionOpen: &pb.SessionOpen{
			Envelope: envToPB(v.Envelope), ProtocolVersion: v.ProtocolVersion, AgentVersion: v.AgentVersion,
			CapabilitiesSupported: capsToPB(v.CapabilitiesSupported),
			Resume:     &pb.Resume{LastAckedGenerationByBinding: v.Resume.LastAckedGenerationByBinding, LastTelemetrySeqByStream: v.Resume.LastTelemetrySeqByStream},
			AgentClock: ts(v.AgentClock),
		}}}, nil
	case msg.LeaseRenew:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_LeaseRenew{LeaseRenew: &pb.LeaseRenew{Envelope: envToPB(v.Envelope), NonceConsumed: v.NonceConsumed, AgentClock: ts(v.AgentClock)}}}, nil
	case msg.CommandPull:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_CommandPull{CommandPull: &pb.CommandPull{Envelope: envToPB(v.Envelope), CommandId: v.CommandID}}}, nil
	case msg.CommandReceived:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_CommandReceived{CommandReceived: &pb.CommandReceived{Envelope: envToPB(v.Envelope), CommandId: v.CommandID, Generation: v.Generation}}}, nil
	case msg.CommandAck:
		d := &pb.AckDetail{Message: v.Detail.Message}
		switch v.Detail.Code {
		case msg.DetailNone:
		case msg.DetailAdmissionBlocked:
			d.Code = pb.AckDetailCode_ACK_DETAIL_CODE_ADMISSION_BLOCKED
		case msg.DetailLeaseNotActive:
			d.Code = pb.AckDetailCode_ACK_DETAIL_CODE_LEASE_NOT_ACTIVE
		default:
			d.Code = pb.AckDetailCode_ACK_DETAIL_CODE_ERROR
			d.ErrorCode = errToPB(msg.ErrorCode(v.Detail.Code))
		}
		if pr := v.Detail.PreflightResult; pr != nil {
			d.PreflightResult = &pb.PreflightResult{ContainerId: pr.ContainerID, ImageDigest: pr.ImageDigest, ConfigPathInContainer: pr.ConfigPathInContainer,
				HostPath: pr.HostPath, Mechanism: pr.Mechanism, CompetingSources: pr.CompetingSources, Writable: pr.Writable}
		}
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_CommandAck{CommandAck: &pb.CommandAck{
			Envelope: envToPB(v.Envelope), CommandId: v.CommandID, Generation: v.Generation, Outcome: outcomeToPB(v.Outcome), Detail: d, AgentClock: ts(v.AgentClock)}}}, nil
	case msg.ApplyProgress:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_ApplyProgress{ApplyProgress: &pb.ApplyProgress{
			Envelope: envToPB(v.Envelope), CommandId: v.CommandID, Step: stepToPB(v.Step), ReplicaRef: v.ReplicaRef, At: ts(v.At)}}}, nil
	case msg.ActualStateReportBatch:
		items := make([]*pb.ActualStateReport, 0, len(v.Items))
		for _, it := range v.Items {
			items = append(items, &pb.ActualStateReport{
				ItemId: it.ItemID, Sequence: it.Sequence, BindingId: it.BindingID, ReplicaRef: it.ReplicaRef, Incarnation: it.Incarnation,
				PersistedGeneration: it.PersistedGeneration, RestartObservedAt: tsPtr(it.RestartObservedAt), EffectiveHash: strPtr(it.EffectiveHash),
				VersionObservation: &pb.VersionObservation{Mechanism: it.VersionObservation.Mechanism, Value: it.VersionObservation.Value, Confidence: it.VersionObservation.Confidence},
			})
		}
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_ActualStateReportBatch{ActualStateReportBatch: &pb.ActualStateReportBatch{Envelope: envToPB(v.Envelope), Items: items}}}, nil
	case msg.Heartbeat:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_Heartbeat{Heartbeat: &pb.Heartbeat{
			Envelope: envToPB(v.Envelope), AgentClock: ts(v.AgentClock), BufferDepthBytes: v.BufferDepthBytes, DropCount: v.DropCount,
			JournalDepth: v.JournalDepth, LeaseRemainingAtSend: dur(v.LeaseRemainingAtSend)}}}, nil
	case msg.Teardown:
		return &pb.AgentToGateway{Msg: &pb.AgentToGateway_Teardown{Teardown: &pb.Teardown{Envelope: envToPB(v.Envelope), Reason: v.Reason}}}, nil
	}
	return nil, fmt.Errorf("codec: unsupported outbound %T", m)
}

func AgentFromPB(p *pb.AgentToGateway) (msg.Outbound, error) {
	switch v := p.GetMsg().(type) {
	case *pb.AgentToGateway_SessionOpen:
		m := v.SessionOpen
		return msg.SessionOpen{Envelope: envFromPB(m.GetEnvelope()), ProtocolVersion: m.GetProtocolVersion(), AgentVersion: m.GetAgentVersion(),
			CapabilitiesSupported: capsFromPB(m.GetCapabilitiesSupported()),
			Resume:     msg.Resume{LastAckedGenerationByBinding: m.GetResume().GetLastAckedGenerationByBinding(), LastTelemetrySeqByStream: m.GetResume().GetLastTelemetrySeqByStream()},
			AgentClock: tsFrom(m.GetAgentClock())}, nil
	case *pb.AgentToGateway_LeaseRenew:
		m := v.LeaseRenew
		return msg.LeaseRenew{Envelope: envFromPB(m.GetEnvelope()), NonceConsumed: m.GetNonceConsumed(), AgentClock: tsFrom(m.GetAgentClock())}, nil
	case *pb.AgentToGateway_CommandPull:
		return msg.CommandPull{Envelope: envFromPB(v.CommandPull.GetEnvelope()), CommandID: v.CommandPull.GetCommandId()}, nil
	case *pb.AgentToGateway_CommandReceived:
		m := v.CommandReceived
		return msg.CommandReceived{Envelope: envFromPB(m.GetEnvelope()), CommandID: m.GetCommandId(), Generation: m.GetGeneration()}, nil
	case *pb.AgentToGateway_CommandAck:
		m := v.CommandAck
		d := msg.AckDetail{Message: m.GetDetail().GetMessage()}
		switch m.GetDetail().GetCode() {
		case pb.AckDetailCode_ACK_DETAIL_CODE_ADMISSION_BLOCKED:
			d.Code = msg.DetailAdmissionBlocked
		case pb.AckDetailCode_ACK_DETAIL_CODE_LEASE_NOT_ACTIVE:
			d.Code = msg.DetailLeaseNotActive
		case pb.AckDetailCode_ACK_DETAIL_CODE_ERROR:
			d.Code = msg.DetailOf(errFromPB(m.GetDetail().GetErrorCode()))
		}
		if pr := m.GetDetail().GetPreflightResult(); pr != nil {
			d.PreflightResult = &msg.PreflightResult{ContainerID: pr.GetContainerId(), ImageDigest: pr.GetImageDigest(), ConfigPathInContainer: pr.GetConfigPathInContainer(),
				HostPath: pr.GetHostPath(), Mechanism: pr.GetMechanism(), CompetingSources: pr.GetCompetingSources(), Writable: pr.GetWritable()}
		}
		return msg.CommandAck{Envelope: envFromPB(m.GetEnvelope()), CommandID: m.GetCommandId(), Generation: m.GetGeneration(),
			Outcome: outcomeFromPB(m.GetOutcome()), Detail: d, AgentClock: tsFrom(m.GetAgentClock())}, nil
	case *pb.AgentToGateway_ApplyProgress:
		m := v.ApplyProgress
		return msg.ApplyProgress{Envelope: envFromPB(m.GetEnvelope()), CommandID: m.GetCommandId(), Step: stepFromPB(m.GetStep()), ReplicaRef: m.GetReplicaRef(), At: tsFrom(m.GetAt())}, nil
	case *pb.AgentToGateway_ActualStateReportBatch:
		m := v.ActualStateReportBatch
		items := make([]msg.ActualStateReport, 0, len(m.GetItems()))
		for _, it := range m.GetItems() {
			items = append(items, msg.ActualStateReport{
				ItemID: it.GetItemId(), Sequence: it.GetSequence(), BindingID: it.GetBindingId(), ReplicaRef: it.GetReplicaRef(), Incarnation: it.GetIncarnation(),
				PersistedGeneration: it.GetPersistedGeneration(), RestartObservedAt: tsPtrFrom(it.GetRestartObservedAt()), EffectiveHash: strOrEmpty(it.EffectiveHash),
				VersionObservation: msg.VersionObservation{Mechanism: it.GetVersionObservation().GetMechanism(), Value: it.GetVersionObservation().GetValue(), Confidence: it.GetVersionObservation().GetConfidence()},
			})
		}
		return msg.ActualStateReportBatch{Envelope: envFromPB(m.GetEnvelope()), Items: items}, nil
	case *pb.AgentToGateway_Heartbeat:
		m := v.Heartbeat
		return msg.Heartbeat{Envelope: envFromPB(m.GetEnvelope()), AgentClock: tsFrom(m.GetAgentClock()), BufferDepthBytes: m.GetBufferDepthBytes(),
			DropCount: m.GetDropCount(), JournalDepth: m.GetJournalDepth(), LeaseRemainingAtSend: durFrom(m.GetLeaseRemainingAtSend())}, nil
	case *pb.AgentToGateway_Teardown:
		return msg.Teardown{Envelope: envFromPB(v.Teardown.GetEnvelope()), Reason: v.Teardown.GetReason()}, nil
	}
	return nil, fmt.Errorf("codec: unsupported agent message %T", p.GetMsg())
}

// ---- Gateway → Agent ----

func grantToPB(g msg.LeaseGrant) *pb.LeaseGrant {
	vc := map[string]*durationpb.Duration{}
	for op, d := range g.VerbCeilings {
		vc[string(op)] = dur(d)
	}
	return &pb.LeaseGrant{
		Envelope: envToPB(g.Envelope), AgentId: g.AgentID, CnfThumbprint: g.CnfThumbprint, TargetId: g.TargetID, BindingIds: g.BindingIDs,
		AgentRevocationEpoch: g.AgentRevocationEpoch, FleetRevocationEpoch: g.FleetRevocationEpoch, IssuedAt: ts(g.IssuedAt), ExpiresAt: ts(g.ExpiresAt),
		Audience: g.Audience, Nonce: g.Nonce, Capabilities: capsToPB(g.Capabilities), VerbCeilings: vc, AdmissionMargin: dur(g.AdmissionMargin), ServerClock: ts(g.ServerClock),
	}
}

func grantFromPB(p *pb.LeaseGrant) msg.LeaseGrant {
	if p == nil {
		return msg.LeaseGrant{}
	}
	var vc map[msg.Operation]time.Duration
	if len(p.GetVerbCeilings()) > 0 {
		vc = map[msg.Operation]time.Duration{}
		for k, d := range p.GetVerbCeilings() {
			vc[msg.Operation(k)] = durFrom(d)
		}
	}
	return msg.LeaseGrant{
		Envelope: envFromPB(p.GetEnvelope()), AgentID: p.GetAgentId(), CnfThumbprint: p.GetCnfThumbprint(), TargetID: p.GetTargetId(), BindingIDs: p.GetBindingIds(),
		AgentRevocationEpoch: p.GetAgentRevocationEpoch(), FleetRevocationEpoch: p.GetFleetRevocationEpoch(), IssuedAt: tsFrom(p.GetIssuedAt()), ExpiresAt: tsFrom(p.GetExpiresAt()),
		Audience: p.GetAudience(), Nonce: p.GetNonce(), Capabilities: capsFromPB(p.GetCapabilities()), VerbCeilings: vc, AdmissionMargin: durFrom(p.GetAdmissionMargin()), ServerClock: tsFrom(p.GetServerClock()),
	}
}

func GatewayToPB(m msg.Inbound) (*pb.GatewayToAgent, error) {
	switch v := m.(type) {
	case msg.SessionAccept:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_SessionAccept{SessionAccept: &pb.SessionAccept{
			Envelope: envToPB(v.Envelope), SessionId: v.SessionID, AssignedTargetId: v.AssignedTargetID, RequiredCapabilities: capsToPB(v.RequiredCapabilities), Lease: grantToPB(v.Lease),
			Limits: &pb.Limits{MaxMessageBytes: v.Limits.MaxMessageBytes, MaxBatchBytes: v.Limits.MaxBatchBytes, InitialCredits: v.Limits.InitialCredits,
				HeartbeatInterval: dur(v.Limits.HeartbeatInterval), KeepaliveInterval: dur(v.Limits.KeepaliveInterval), SkewTolerance: dur(v.Limits.SkewTolerance), ReportInterval: dur(v.Limits.ReportInterval)},
			ServerClock: ts(v.ServerClock)}}}, nil
	case msg.SessionReject:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_SessionReject{SessionReject: &pb.SessionReject{Envelope: envToPB(v.Envelope), Code: errToPB(v.Code), RetryAfter: durPtr(v.RetryAfter), MinAgentVersion: strPtr(v.MinAgentVersion)}}}, nil
	case msg.LeaseGrant:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_LeaseGrant{LeaseGrant: grantToPB(v)}}, nil
	case msg.Revoke:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_Revoke{Revoke: &pb.Revoke{Envelope: envToPB(v.Envelope), AgentRevocationEpoch: v.AgentRevocationEpoch, FleetRevocationEpoch: v.FleetRevocationEpoch, Reason: v.Reason}}}, nil
	case msg.GenerationAvailable:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_GenerationAvailable{GenerationAvailable: &pb.GenerationAvailable{
			Envelope: envToPB(v.Envelope), CommandId: v.CommandID, BindingId: v.BindingID, Generation: v.Generation, Operation: opToPB(v.Operation), ConfigSha256: strPtr(v.ConfigSHA256), Deadline: ts(v.Deadline)}}}, nil
	case msg.CommandOffer:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_CommandOffer{CommandOffer: &pb.CommandOffer{
			Envelope: envToPB(v.Envelope), CommandId: v.CommandID, Generation: v.Generation, IdempotencyKey: v.IdempotencyKey, BindingId: v.BindingID, Operation: opToPB(v.Operation),
			PayloadBytes: v.Payload, ConfigSha256: v.ConfigSHA256, SupersedesGeneration: v.SupersedesGeneration, Deadline: ts(v.Deadline)}}}, nil
	case msg.ReportAck:
		rs := make([]*pb.ReportResult, 0, len(v.Results))
		for _, r := range v.Results {
			rs = append(rs, &pb.ReportResult{InReplyToItemId: r.InReplyToItemID, Sequence: r.Sequence, ReplicaId: strPtr(r.ReplicaID), IncarnationId: strPtr(r.IncarnationID),
				Trust: trustToPB(r.Trust), Disposition: dispToPB(r.Disposition), RefusedReason: errToPB(r.RefusedReason)})
		}
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_ReportAck{ReportAck: &pb.ReportAck{Envelope: envToPB(v.Envelope), InReplyToMessageId: v.InReplyToMessageID, Results: rs}}}, nil
	case msg.HeartbeatAck:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_HeartbeatAck{HeartbeatAck: &pb.HeartbeatAck{Envelope: envToPB(v.Envelope), ServerClock: ts(v.ServerClock), ClockOffsetMs: v.ClockOffsetMs, ClockState: clockToPB(v.ClockState)}}}, nil
	case msg.TrustBundleUpdate:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_TrustBundleUpdate{TrustBundleUpdate: &pb.TrustBundleUpdate{Envelope: envToPB(v.Envelope), TrustBundle: bundleToPB(v.TrustBundle)}}}, nil
	case msg.Drain:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_Drain{Drain: &pb.Drain{Envelope: envToPB(v.Envelope), Reason: v.Reason, ReconnectAfter: dur(v.ReconnectAfter)}}}, nil
	case msg.Teardown:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_Teardown{Teardown: &pb.Teardown{Envelope: envToPB(v.Envelope), Reason: v.Reason}}}, nil
	case msg.Error:
		return &pb.GatewayToAgent{Msg: &pb.GatewayToAgent_Error{Error: &pb.Error{Envelope: envToPB(v.Envelope), Code: errToPB(v.Code), RetryAfter: durPtr(v.RetryAfter), CorrelationId: v.CorrelationID, Message: v.Message}}}, nil
	}
	return nil, fmt.Errorf("codec: unsupported inbound %T", m)
}

func GatewayFromPB(p *pb.GatewayToAgent) (msg.Inbound, error) {
	switch v := p.GetMsg().(type) {
	case *pb.GatewayToAgent_SessionAccept:
		m := v.SessionAccept
		l := m.GetLimits()
		return msg.SessionAccept{Envelope: envFromPB(m.GetEnvelope()), SessionID: m.GetSessionId(), AssignedTargetID: m.GetAssignedTargetId(),
			RequiredCapabilities: capsFromPB(m.GetRequiredCapabilities()), Lease: grantFromPB(m.GetLease()),
			Limits: msg.Limits{MaxMessageBytes: l.GetMaxMessageBytes(), MaxBatchBytes: l.GetMaxBatchBytes(), InitialCredits: l.GetInitialCredits(),
				HeartbeatInterval: durFrom(l.GetHeartbeatInterval()), KeepaliveInterval: durFrom(l.GetKeepaliveInterval()), SkewTolerance: durFrom(l.GetSkewTolerance()), ReportInterval: durFrom(l.GetReportInterval())},
			ServerClock: tsFrom(m.GetServerClock())}, nil
	case *pb.GatewayToAgent_SessionReject:
		m := v.SessionReject
		return msg.SessionReject{Envelope: envFromPB(m.GetEnvelope()), Code: errFromPB(m.GetCode()), RetryAfter: durPtrFrom(m.GetRetryAfter()), MinAgentVersion: strOrEmpty(m.MinAgentVersion)}, nil
	case *pb.GatewayToAgent_LeaseGrant:
		return grantFromPB(v.LeaseGrant), nil
	case *pb.GatewayToAgent_Revoke:
		m := v.Revoke
		return msg.Revoke{Envelope: envFromPB(m.GetEnvelope()), AgentRevocationEpoch: m.AgentRevocationEpoch, FleetRevocationEpoch: m.FleetRevocationEpoch, Reason: m.GetReason()}, nil
	case *pb.GatewayToAgent_GenerationAvailable:
		m := v.GenerationAvailable
		return msg.GenerationAvailable{Envelope: envFromPB(m.GetEnvelope()), CommandID: m.GetCommandId(), BindingID: m.GetBindingId(), Generation: m.GetGeneration(),
			Operation: opFromPB(m.GetOperation()), ConfigSHA256: strOrEmpty(m.ConfigSha256), Deadline: tsFrom(m.GetDeadline())}, nil
	case *pb.GatewayToAgent_CommandOffer:
		m := v.CommandOffer
		return msg.CommandOffer{Envelope: envFromPB(m.GetEnvelope()), CommandID: m.GetCommandId(), Generation: m.GetGeneration(), IdempotencyKey: m.GetIdempotencyKey(), BindingID: m.GetBindingId(),
			Operation: opFromPB(m.GetOperation()), Payload: m.GetPayloadBytes(), ConfigSHA256: m.GetConfigSha256(), SupersedesGeneration: m.SupersedesGeneration, Deadline: tsFrom(m.GetDeadline())}, nil
	case *pb.GatewayToAgent_ReportAck:
		m := v.ReportAck
		rs := make([]msg.ReportResult, 0, len(m.GetResults()))
		for _, r := range m.GetResults() {
			rr := msg.ReportResult{InReplyToItemID: r.GetInReplyToItemId(), Sequence: r.GetSequence(), ReplicaID: strOrEmpty(r.ReplicaId), IncarnationID: strOrEmpty(r.IncarnationId),
				Trust: trustFromPB(r.GetTrust()), Disposition: dispFromPB(r.GetDisposition())}
			if r.GetRefusedReason() != pb.ErrorCode_ERROR_CODE_UNSPECIFIED {
				rr.RefusedReason = errFromPB(r.GetRefusedReason())
			}
			rs = append(rs, rr)
		}
		return msg.ReportAck{Envelope: envFromPB(m.GetEnvelope()), InReplyToMessageID: m.GetInReplyToMessageId(), Results: rs}, nil
	case *pb.GatewayToAgent_HeartbeatAck:
		m := v.HeartbeatAck
		return msg.HeartbeatAck{Envelope: envFromPB(m.GetEnvelope()), ServerClock: tsFrom(m.GetServerClock()), ClockOffsetMs: m.GetClockOffsetMs(), ClockState: clockFromPB(m.GetClockState())}, nil
	case *pb.GatewayToAgent_TrustBundleUpdate:
		return msg.TrustBundleUpdate{Envelope: envFromPB(v.TrustBundleUpdate.GetEnvelope()), TrustBundle: bundleFromPB(v.TrustBundleUpdate.GetTrustBundle())}, nil
	case *pb.GatewayToAgent_Drain:
		return msg.Drain{Envelope: envFromPB(v.Drain.GetEnvelope()), Reason: v.Drain.GetReason(), ReconnectAfter: durFrom(v.Drain.GetReconnectAfter())}, nil
	case *pb.GatewayToAgent_Teardown:
		return msg.Teardown{Envelope: envFromPB(v.Teardown.GetEnvelope()), Reason: v.Teardown.GetReason()}, nil
	case *pb.GatewayToAgent_Error:
		m := v.Error
		return msg.Error{Envelope: envFromPB(m.GetEnvelope()), Code: errFromPB(m.GetCode()), RetryAfter: durPtrFrom(m.GetRetryAfter()), CorrelationID: m.GetCorrelationId(), Message: m.GetMessage()}, nil
	}
	return nil, fmt.Errorf("codec: unsupported gateway message %T", p.GetMsg())
}

// ---- gRPC status carrying an Error (used by Enroll and stream close) ----

func StatusFromError(e msg.Error) error {
	code := codes.PermissionDenied
	if msg.Classify(e.Code) == msg.ClassRetry {
		code = codes.Unavailable
	}
	st := status.New(code, string(e.Code))
	withDetails, err := st.WithDetails(&pb.Error{Envelope: envToPB(e.Envelope), Code: errToPB(e.Code), RetryAfter: durPtr(e.RetryAfter), CorrelationId: e.CorrelationID, Message: e.Message})
	if err != nil {
		return st.Err()
	}
	return withDetails.Err()
}

// ErrorFromStatus returns a *msg.ProtocolError when err carries a pb.Error
// detail, otherwise err unchanged.
func ErrorFromStatus(err error) error {
	st, ok := status.FromError(err)
	if !ok || st == nil {
		return err
	}
	for _, d := range st.Details() {
		if e, ok := d.(*pb.Error); ok {
			return &msg.ProtocolError{Code: errFromPB(e.GetCode()), RetryAfter: durPtrFrom(e.GetRetryAfter()), Message: e.GetMessage()}
		}
	}
	return err
}
```

Remove `"errors"` from the import block — nothing in the file uses it.

- [ ] **Step 5: Run the codec tests**

Run: `go test ./internal/wire/codec/`
Expected: PASS. If `reflect.DeepEqual` fails on `nil` vs empty slices/maps, the offending case is the one to fix in the codec (`capsFromPB` returns `nil` for empty; `Resume` maps must round-trip as given — the test passes an empty map for `LastTelemetrySeqByStream` on purpose, and protobuf returns an empty map for an empty map, so keep it that way).

- [ ] **Step 6: Write the client**

`internal/wire/client.go`:

```go
// Package wire is the gRPC/mTLS binding of the DRAFT agent-gateway protocol.
// It and codec are the only packages that import pb.
package wire

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/keepalive"
	"google.golang.org/grpc/metadata"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/codec"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

const MetadataTokenKey = "x-sm-enrollment-token"

const maxRecvBytes = 16 << 20 // conservative; Limits.max_message_bytes is enforced in reducers

type Client struct {
	id       *identity.Identity
	bootstrap []byte
	log      *slog.Logger
}

func New(id *identity.Identity, bootstrapBundlePEM []byte, log *slog.Logger) *Client {
	return &Client{id: id, bootstrap: bootstrapBundlePEM, log: log}
}

// Enroll is the one cert-less RPC: a short-lived connection verified by the
// install-time bundle, the token as metadata, closed after the reply.
func (c *Client) Enroll(ctx context.Context, endpoint string, req msg.EnrollRequest) (*msg.EnrollResponse, error) {
	tlsCfg, err := c.id.EnrollTLS(c.bootstrap)
	if err != nil {
		return nil, err
	}
	cc, err := grpc.NewClient(endpoint, grpc.WithTransportCredentials(credentials.NewTLS(tlsCfg)))
	if err != nil {
		return nil, err
	}
	defer cc.Close()
	ctx = metadata.AppendToOutgoingContext(ctx, MetadataTokenKey, req.Token)
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	resp, err := pb.NewAgentGatewayClient(cc).Enroll(ctx, codec.EnrollRequestToPB(req))
	if err != nil {
		return nil, codec.ErrorFromStatus(err)
	}
	r := codec.EnrollResponseFromPB(resp)
	return &r, nil
}

// Conn is one Connect stream over one mTLS connection. It satisfies
// agent.Conn without importing agent.
type Conn struct {
	cc     *grpc.ClientConn
	stream pb.AgentGateway_ConnectClient
	cancel context.CancelFunc
}

func (c *Client) Dial(ctx context.Context, endpoint string) (*Conn, error) {
	tlsCfg, err := c.id.ConnectTLS()
	if err != nil {
		return nil, err
	}
	cc, err := grpc.NewClient(endpoint,
		grpc.WithTransportCredentials(credentials.NewTLS(tlsCfg)),
		grpc.WithKeepaliveParams(keepalive.ClientParameters{Time: 30 * time.Second, Timeout: 10 * time.Second, PermitWithoutStream: true}),
		grpc.WithDefaultCallOptions(grpc.MaxCallRecvMsgSize(maxRecvBytes)),
	)
	if err != nil {
		return nil, err
	}
	sctx, cancel := context.WithCancel(ctx)
	stream, err := pb.NewAgentGatewayClient(cc).Connect(sctx)
	if err != nil {
		cancel()
		_ = cc.Close()
		return nil, fmt.Errorf("wire: connect: %w", codec.ErrorFromStatus(err))
	}
	return &Conn{cc: cc, stream: stream, cancel: cancel}, nil
}

func (c *Conn) Send(m msg.Outbound) error {
	p, err := codec.AgentToPB(m)
	if err != nil {
		return err
	}
	return c.stream.Send(p)
}

func (c *Conn) Recv() (msg.Inbound, error) {
	p, err := c.stream.Recv()
	if err != nil {
		return nil, codec.ErrorFromStatus(err)
	}
	return codec.GatewayFromPB(p)
}

func (c *Conn) Close() error {
	_ = c.stream.CloseSend()
	c.cancel()
	return c.cc.Close()
}
```

- [ ] **Step 7: Build and vet**

Run: `go build ./... && go vet ./...`
Expected: clean. (`*wire.Conn` must satisfy `agent.Conn` — add to `client.go` if the compiler doesn't already prove it: `var _ interface{ Send(msg.Outbound) error; Recv() (msg.Inbound, error); Close() error } = (*Conn)(nil)`.)

- [ ] **Step 8: Commit**

```bash
git add internal/msg internal/agent internal/wire
git commit -m "go-agent: wire codec (pb<->msg both directions) and mTLS gRPC client

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: The fake gateway (`AG-D16`)

**Files:**
- Create: `internal/fakegw/scenario.go`, `internal/fakegw/server.go`, `internal/fakegw/session.go`, `internal/fakegw/transcript.go`, `internal/fakegw/server_test.go`, `internal/fakegw/scenarios/demo.yaml`, `cmd/fake-gateway/main.go`

A wire-level double, not a Data Plane: no ports, no persistence. It implements gateway §4.5/§4.6 enrollment and lease semantics faithfully enough to drive the agent through every session path, and runs a scripted scenario on `Connect`.

**Interfaces:**
- Produces:
  - `fakegw.Scenario` (YAML-loadable) with `Lease`, `Limits`, `Policy`, `Steps`; `func LoadScenario(path string) (*Scenario, error)`; `func DefaultScenario() *Scenario`.
  - `fakegw.Config{Listen string; Tokens []string; Scenario *Scenario; Log *slog.Logger}`; `func Start(cfg Config) (*Server, error)`; `(*Server).Endpoint() string`; `(*Server).CAPEM() []byte`; `(*Server).Stop()`.
  - `(*Server).Transcript() []Entry`; `(*Server).Count(kind string) int`; `(*Server).WaitFor(kind string, n int, timeout time.Duration) bool`; `(*Server).Received(kind string) []msg.Outbound`; `(*Server).Sessions() int`.
  - `Entry{At time.Time; Dir string /* "in" | "out" */; Kind string /* Go type name without package */; Msg any}`.
  - Step vocabulary (YAML keys): `expect{kind, timeout}` · `offer{command_id, binding_id, generation, operation, idempotency_key, payload, supersedes_generation}` · `superseded{command_id}` · `error{code, retry_after}` · `revoke{reason}` · `drain{reconnect_after}` · `disconnect: true` · `sleep` · `send_grant: true` · `renew_policy{mode}` · `heartbeat_policy{offset_ms, state}` · `report_policy{mode, reason}`.
  - Policy: `on_open ∈ accept | renew-required | reject:<CODE>`; `renew ∈ grant | refuse:<CODE> | silent`; `heartbeat{offset_ms, state}`; `report{mode ∈ auto | refuse:<CODE> | silent}`.
  - A pull for a superseded command is answered `Error{Code: SUPERSEDED, CorrelationID: command_id}` — Task 18's reducer keys on that.

- [ ] **Step 1: Scenario types and loader**

`internal/fakegw/scenario.go`:

```go
// Package fakegw is a scenario-driven, wire-level double of the agent
// gateway for tests and demos. It is not a Data Plane and is not evidence
// about the plane's correctness (AG-D16).
package fakegw

import (
	"fmt"
	"os"
	"time"

	"gopkg.in/yaml.v3"
)

type LeaseSpec struct {
	TargetID        string                   `yaml:"target_id"`
	BindingIDs      []string                 `yaml:"binding_ids"`
	Capabilities    []string                 `yaml:"capabilities"`
	Duration        time.Duration            `yaml:"duration"`
	VerbCeilings    map[string]time.Duration `yaml:"verb_ceilings"`
	AdmissionMargin time.Duration            `yaml:"admission_margin"`
}

type LimitsSpec struct {
	MaxMessageBytes   uint64        `yaml:"max_message_bytes"`
	HeartbeatInterval time.Duration `yaml:"heartbeat_interval"`
	KeepaliveInterval time.Duration `yaml:"keepalive_interval"`
	SkewTolerance     time.Duration `yaml:"skew_tolerance"`
	ReportInterval    time.Duration `yaml:"report_interval"`
}

type HeartbeatPolicy struct {
	OffsetMs int64  `yaml:"offset_ms"`
	State    string `yaml:"state"` // OK | SUSPECT
}

type ReportPolicy struct {
	Mode   string `yaml:"mode"`   // auto | refuse:<CODE> | silent
	Reason string `yaml:"reason"` // for refuse
}

type Policy struct {
	OnOpen    string          `yaml:"on_open"` // accept | renew-required | reject:<CODE>
	Renew     string          `yaml:"renew"`   // grant | refuse:<CODE> | silent
	Heartbeat HeartbeatPolicy `yaml:"heartbeat"`
	Report    ReportPolicy    `yaml:"report"`
}

type ExpectStep struct {
	Kind    string        `yaml:"kind"`
	Timeout time.Duration `yaml:"timeout"`
}

type OfferStep struct {
	CommandID            string  `yaml:"command_id"`
	BindingID            string  `yaml:"binding_id"`
	Generation           uint64  `yaml:"generation"`
	Operation            string  `yaml:"operation"`
	IdempotencyKey       string  `yaml:"idempotency_key"`
	Payload              string  `yaml:"payload"`
	SupersedesGeneration *uint64 `yaml:"supersedes_generation"`
	// BadHash makes the offer's config_sha256 disagree with the payload.
	BadHash bool `yaml:"bad_hash"`
	// Deadline relative to send time; zero → 5 minutes.
	Deadline time.Duration `yaml:"deadline"`
}

type ErrorStep struct {
	Code       string         `yaml:"code"`
	RetryAfter *time.Duration `yaml:"retry_after"`
}

type Step struct {
	Expect          *ExpectStep      `yaml:"expect"`
	Offer           *OfferStep       `yaml:"offer"`
	Superseded      *string          `yaml:"superseded"`
	Error           *ErrorStep       `yaml:"error"`
	Revoke          *string          `yaml:"revoke"`
	Drain           *time.Duration   `yaml:"drain"`
	Disconnect      *bool            `yaml:"disconnect"`
	Sleep           *time.Duration   `yaml:"sleep"`
	SendGrant       *bool            `yaml:"send_grant"`
	RenewPolicy     *string          `yaml:"renew_policy"`
	HeartbeatPolicy *HeartbeatPolicy `yaml:"heartbeat_policy"`
	ReportPolicy    *ReportPolicy    `yaml:"report_policy"`
}

type Scenario struct {
	Lease  LeaseSpec  `yaml:"lease"`
	Limits LimitsSpec `yaml:"limits"`
	Policy Policy     `yaml:"policy"`
	Steps  []Step     `yaml:"steps"`
}

func DefaultScenario() *Scenario {
	return &Scenario{
		Lease: LeaseSpec{
			TargetID: "target-1", BindingIDs: []string{"binding-1"},
			Capabilities: []string{"apply-config", "lifecycle"}, Duration: time.Hour,
			VerbCeilings:    map[string]time.Duration{"preflight": time.Minute, "apply": 2 * time.Minute, "restart": 5 * time.Minute, "stop": 2 * time.Minute},
			AdmissionMargin: 30 * time.Second,
		},
		Limits: LimitsSpec{MaxMessageBytes: 1 << 20, HeartbeatInterval: 10 * time.Second, KeepaliveInterval: 30 * time.Second, SkewTolerance: 30 * time.Second, ReportInterval: time.Minute},
		Policy: Policy{OnOpen: "accept", Renew: "grant", Heartbeat: HeartbeatPolicy{State: "OK"}, Report: ReportPolicy{Mode: "auto"}},
	}
}

func LoadScenario(path string) (*Scenario, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	sc := DefaultScenario()
	if err := yaml.Unmarshal(b, sc); err != nil {
		return nil, fmt.Errorf("scenario %s: %w", path, err)
	}
	return sc, nil
}
```

- [ ] **Step 2: Transcript**

`internal/fakegw/transcript.go`:

```go
package fakegw

import (
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

type Entry struct {
	At   time.Time
	Dir  string // "in" (agent → fake) | "out" (fake → agent)
	Kind string // Go type name without package, e.g. "SessionOpen"
	Msg  any
}

type transcript struct {
	mu      sync.Mutex
	entries []Entry
	cond    *sync.Cond
}

func newTranscript() *transcript {
	t := &transcript{}
	t.cond = sync.NewCond(&t.mu)
	return t
}

func kindOf(m any) string {
	s := fmt.Sprintf("%T", m)
	if i := strings.LastIndex(s, "."); i >= 0 {
		return s[i+1:]
	}
	return s
}

func (t *transcript) add(dir string, m any) {
	t.mu.Lock()
	t.entries = append(t.entries, Entry{At: time.Now(), Dir: dir, Kind: kindOf(m), Msg: m})
	t.mu.Unlock()
	t.cond.Broadcast()
}

func (t *transcript) all() []Entry {
	t.mu.Lock()
	defer t.mu.Unlock()
	out := make([]Entry, len(t.entries))
	copy(out, t.entries)
	return out
}

func (t *transcript) count(kind string) int {
	n := 0
	for _, e := range t.all() {
		if e.Dir == "in" && e.Kind == kind {
			n++
		}
	}
	return n
}

func (t *transcript) received(kind string) []msg.Outbound {
	var out []msg.Outbound
	for _, e := range t.all() {
		if e.Dir == "in" && e.Kind == kind {
			out = append(out, e.Msg.(msg.Outbound))
		}
	}
	return out
}

// waitFor blocks until n inbound entries of kind exist or timeout passes.
func (t *transcript) waitFor(kind string, n int, timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	timer := time.AfterFunc(timeout, func() { t.cond.Broadcast() })
	defer timer.Stop()
	t.mu.Lock()
	defer t.mu.Unlock()
	for {
		c := 0
		for _, e := range t.entries {
			if e.Dir == "in" && e.Kind == kind {
				c++
			}
		}
		if c >= n {
			return true
		}
		if time.Now().After(deadline) {
			return false
		}
		t.cond.Wait()
	}
}

// waitForFrom blocks until an inbound entry of kind exists at index ≥ from,
// returning the index after it. This is what scripted `expect` steps use, so
// a message that arrived before the step started still matches.
func (t *transcript) waitForFrom(kind string, from int, timeout time.Duration) (int, bool) {
	deadline := time.Now().Add(timeout)
	timer := time.AfterFunc(timeout, func() { t.cond.Broadcast() })
	defer timer.Stop()
	t.mu.Lock()
	defer t.mu.Unlock()
	for {
		for i := from; i < len(t.entries); i++ {
			if t.entries[i].Dir == "in" && t.entries[i].Kind == kind {
				return i + 1, true
			}
		}
		if time.Now().After(deadline) {
			return from, false
		}
		t.cond.Wait()
	}
}

func (t *transcript) length() int {
	t.mu.Lock()
	defer t.mu.Unlock()
	return len(t.entries)
}
```

- [ ] **Step 3: Server, CA and enrollment**

`internal/fakegw/server.go`:

```go
package fakegw

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"fmt"
	"log/slog"
	"net"
	"sync"
	"time"

	"github.com/google/uuid"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/peer"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/testca"
	"github.com/solace/solution-manager/go-agent/internal/wire/codec"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

const tokenHeader = "x-sm-enrollment-token"

type Config struct {
	Listen   string
	Tokens   []string
	Scenario *Scenario
	Log      *slog.Logger
}

type enrolledAgent struct {
	agentID    string
	csrHash    string
	resp       msg.EnrollResponse
	thumbprint string
	lease      *msg.LeaseGrant
	renewIDs   map[string]msg.LeaseGrant // message_id → grant, for idempotent resends
	revoked    bool
	epoch      uint64
	sessions   int
	nextID     uint64
}

type Server struct {
	pb.UnimplementedAgentGatewayServer
	cfg  Config
	ca   *testca.CA
	srv  *grpc.Server
	lis  net.Listener
	tr   *transcript
	log  *slog.Logger

	mu        sync.Mutex
	tokens    map[string]*enrolledAgent // token → agent (nil until consumed)
	byThumb   map[string]*enrolledAgent
	replicas  map[string]string // binding|replica_ref → replica_id
	incarn    map[string]string // binding|replica_ref|incarnation → incarnation_id
	sessions  int
}

func Start(cfg Config) (*Server, error) {
	if cfg.Scenario == nil {
		cfg.Scenario = DefaultScenario()
	}
	if cfg.Log == nil {
		cfg.Log = slog.Default()
	}
	ca, err := testca.New()
	if err != nil {
		return nil, err
	}
	lis, err := net.Listen("tcp", cfg.Listen)
	if err != nil {
		return nil, err
	}
	host, _, _ := net.SplitHostPort(lis.Addr().String())
	tlsCfg, err := ca.ServerTLS(host)
	if err != nil {
		return nil, err
	}
	s := &Server{
		cfg: cfg, ca: ca, lis: lis, tr: newTranscript(), log: cfg.Log,
		tokens: map[string]*enrolledAgent{}, byThumb: map[string]*enrolledAgent{},
		replicas: map[string]string{}, incarn: map[string]string{},
	}
	for _, t := range cfg.Tokens {
		s.tokens[t] = nil
	}
	s.srv = grpc.NewServer(grpc.Creds(credentials.NewTLS(tlsCfg)))
	pb.RegisterAgentGatewayServer(s.srv, s)
	go func() { _ = s.srv.Serve(lis) }()
	return s, nil
}

func (s *Server) Endpoint() string { return s.lis.Addr().String() }
func (s *Server) CAPEM() []byte     { return s.ca.PEM() }
func (s *Server) Stop()             { s.srv.Stop() }

func (s *Server) Transcript() []Entry                     { return s.tr.all() }
func (s *Server) Count(kind string) int                   { return s.tr.count(kind) }
func (s *Server) Received(kind string) []msg.Outbound     { return s.tr.received(kind) }
func (s *Server) WaitFor(kind string, n int, d time.Duration) bool { return s.tr.waitFor(kind, n, d) }
func (s *Server) Sessions() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.sessions
}

func (s *Server) newID() string {
	id, err := uuid.NewV7()
	if err != nil {
		return uuid.NewString()
	}
	return id.String()
}

func csrHash(der []byte) string {
	h := sha256.Sum256(der)
	return hex.EncodeToString(h[:])
}

// Enroll: single-use token + CSR; the same (token, CSR) replays the stored
// reply; a different CSR or a second distinct request is TOKEN_CONSUMED.
func (s *Server) Enroll(ctx context.Context, req *pb.EnrollRequest) (*pb.EnrollResponse, error) {
	md, _ := metadata.FromIncomingContext(ctx)
	var token string
	if v := md.Get(tokenHeader); len(v) > 0 {
		token = v[0]
	}
	r := codec.EnrollRequestFromPB(req)
	s.tr.add("in", r)
	s.mu.Lock()
	defer s.mu.Unlock()
	ag, known := s.tokens[token]
	if !known {
		return nil, codec.StatusFromError(msg.Error{Code: msg.ErrUnauthenticatedIdentity, Message: "unknown token"})
	}
	h := csrHash(r.CSRDER)
	if ag != nil {
		if ag.csrHash == h {
			s.tr.add("out", ag.resp)
			return codec.EnrollResponseToPB(ag.resp), nil
		}
		return nil, codec.StatusFromError(msg.Error{Code: msg.ErrTokenConsumed, Message: "token already used"})
	}
	leaf, err := s.ca.Sign(r.CSRDER)
	if err != nil {
		return nil, codec.StatusFromError(msg.Error{Code: msg.ErrUnauthenticatedIdentity, Message: "bad csr"})
	}
	ag = &enrolledAgent{
		agentID: "agent-" + s.newID()[:8], csrHash: h,
		thumbprint: thumbprint(leaf), renewIDs: map[string]msg.LeaseGrant{},
	}
	ag.resp = msg.EnrollResponse{
		AgentID: ag.agentID, CertificateChain: [][]byte{leaf},
		TrustBundle:     msg.TrustBundle{CertsPEM: [][]byte{s.ca.PEM()}, ValidFrom: time.Now().Add(-time.Hour), ValidTo: time.Now().Add(365 * 24 * time.Hour)},
		ConnectEndpoint: s.Endpoint(),
	}
	s.tokens[token] = ag
	s.byThumb[ag.thumbprint] = ag
	s.tr.add("out", ag.resp)
	return codec.EnrollResponseToPB(ag.resp), nil
}

func thumbprint(der []byte) string {
	h := sha256.Sum256(der)
	return hex.EncodeToString(h[:])
}

// agentFor resolves the mTLS peer to an enrolled agent.
func (s *Server) agentFor(ctx context.Context) (*enrolledAgent, error) {
	p, ok := peer.FromContext(ctx)
	if !ok {
		return nil, fmt.Errorf("no peer")
	}
	ti, ok := p.AuthInfo.(credentials.TLSInfo)
	if !ok || len(ti.State.PeerCertificates) == 0 {
		return nil, fmt.Errorf("no client certificate")
	}
	tp := thumbprint(ti.State.PeerCertificates[0].Raw)
	s.mu.Lock()
	defer s.mu.Unlock()
	ag := s.byThumb[tp]
	if ag == nil {
		return nil, fmt.Errorf("unknown certificate")
	}
	return ag, nil
}

var _ = tls.VersionTLS13
var _ = x509.ErrUnsupportedAlgorithm
```

- [ ] **Step 4: The Connect scenario engine**

`internal/fakegw/session.go`:

```go
package fakegw

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/wire/codec"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

type cmdState struct {
	offer      msg.CommandOffer
	superseded bool
	state      string // OFFERED | DELIVERED | ACKED | REFUSED | SUPERSEDED
}

type session struct {
	s      *Server
	ag     *enrolledAgent
	stream pb.AgentGateway_ConnectServer
	sc     *Scenario
	policy Policy

	sendMu    sync.Mutex
	commands  map[string]*cmdState
	inbox     chan msg.Outbound
	done      chan struct{}
	cursor    int  // transcript index the next `expect` scans from
	mustClose bool // set by disconnect/drain/revoke steps: drop the stream when steps finish
}

func (s *Server) Connect(stream pb.AgentGateway_ConnectServer) error {
	ag, err := s.agentFor(stream.Context())
	if err != nil {
		return codec.StatusFromError(msg.Error{Code: msg.ErrUnauthenticatedIdentity, Message: err.Error()})
	}
	s.mu.Lock()
	if ag.revoked {
		s.mu.Unlock()
		return codec.StatusFromError(msg.Error{Code: msg.ErrIdentityRevoked, Message: "revoked"})
	}
	ag.sessions++
	ag.epoch++
	s.sessions++
	s.mu.Unlock()

	ss := &session{s: s, ag: ag, stream: stream, sc: s.cfg.Scenario, policy: s.cfg.Scenario.Policy,
		commands: map[string]*cmdState{}, inbox: make(chan msg.Outbound, 256), done: make(chan struct{}),
		cursor: s.tr.length()}
	go ss.reader()
	return ss.run()
}

func (ss *session) reader() {
	defer close(ss.done)
	for {
		p, err := ss.stream.Recv()
		if err != nil {
			return
		}
		m, err := codec.AgentFromPB(p)
		if err != nil {
			ss.s.log.Warn("fakegw: undecodable", "err", err)
			continue
		}
		ss.s.tr.add("in", m)
		ss.inbox <- m
	}
}

func (ss *session) send(m msg.Inbound) {
	ss.sendMu.Lock()
	defer ss.sendMu.Unlock()
	p, err := codec.GatewayToPB(m)
	if err != nil {
		ss.s.log.Error("fakegw: encode", "err", err)
		return
	}
	ss.s.tr.add("out", m)
	_ = ss.stream.Send(p)
}

func (ss *session) env() msg.Envelope {
	return msg.Envelope{MessageID: ss.s.newID(), SentAt: time.Now()}
}

func (ss *session) newGrant(now time.Time) msg.LeaseGrant {
	l := ss.sc.Lease
	caps := make([]msg.Capability, 0, len(l.Capabilities))
	for _, c := range l.Capabilities {
		caps = append(caps, msg.Capability(c))
	}
	vc := map[msg.Operation]time.Duration{}
	for k, d := range l.VerbCeilings {
		vc[msg.Operation(k)] = d
	}
	return msg.LeaseGrant{
		Envelope: ss.env(), AgentID: ss.ag.agentID, CnfThumbprint: ss.ag.thumbprint, TargetID: l.TargetID, BindingIDs: l.BindingIDs,
		AgentRevocationEpoch: 1, FleetRevocationEpoch: 1, IssuedAt: now, ExpiresAt: now.Add(l.Duration), Audience: "dp-logical",
		Nonce: ss.s.newID(), Capabilities: caps, VerbCeilings: vc, AdmissionMargin: l.AdmissionMargin, ServerClock: now,
	}
}

func (ss *session) limits() msg.Limits {
	l := ss.sc.Limits
	return msg.Limits{MaxMessageBytes: l.MaxMessageBytes, HeartbeatInterval: l.HeartbeatInterval, KeepaliveInterval: l.KeepaliveInterval,
		SkewTolerance: l.SkewTolerance, ReportInterval: l.ReportInterval}
}

// next waits for the next inbound message or the stream ending.
func (ss *session) next(timeout time.Duration) (msg.Outbound, bool) {
	select {
	case m := <-ss.inbox:
		return m, true
	case <-ss.done:
		return nil, false
	case <-time.After(timeout):
		return nil, false
	}
}

func (ss *session) run() error {
	// 1. SessionOpen
	m, ok := ss.next(30 * time.Second)
	if !ok {
		return nil
	}
	if _, isOpen := m.(msg.SessionOpen); !isOpen {
		ss.send(msg.Error{Envelope: ss.env(), Code: msg.ErrUnauthenticatedIdentity, Message: "SessionOpen expected first"})
		return nil
	}
	if strings.HasPrefix(ss.policy.OnOpen, "reject:") {
		ss.send(msg.SessionReject{Envelope: ss.env(), Code: msg.ErrorCode(strings.TrimPrefix(ss.policy.OnOpen, "reject:"))})
		return nil
	}
	now := time.Now()
	ss.s.mu.Lock()
	lease := ss.ag.lease
	ss.s.mu.Unlock()
	switch {
	case lease == nil: // never granted → issue
		g := ss.newGrant(now)
		ss.setLease(g)
		lease = &g
	case now.Before(lease.ExpiresAt) && ss.policy.OnOpen != "renew-required": // live → return as is
	default: // expired (or forced) → renew under the nonce
		ss.send(msg.Error{Envelope: ss.env(), Code: msg.ErrLeaseRenewRequired})
		r, ok := ss.next(30 * time.Second)
		if !ok {
			return nil
		}
		lr, isRenew := r.(msg.LeaseRenew)
		if !isRenew || lr.NonceConsumed != lease.Nonce {
			ss.send(msg.SessionReject{Envelope: ss.env(), Code: msg.ErrReEnrollRequired})
			return nil
		}
		g := ss.grantForRenew(lr, now)
		lease = &g
	}
	ss.send(msg.SessionAccept{Envelope: ss.env(), SessionID: "sess-" + ss.s.newID()[:8], AssignedTargetID: ss.sc.Lease.TargetID,
		RequiredCapabilities: nil, Lease: *lease, Limits: ss.limits(), ServerClock: now})

	// 2. steady state: auto-responders + scripted steps
	steps := ss.sc.Steps
	stepDone := make(chan struct{})
	go func() {
		defer close(stepDone)
		for _, st := range steps {
			if !ss.runStep(st) {
				return
			}
		}
	}()
	for {
		select {
		case m := <-ss.inbox:
			ss.respond(m)
		case <-ss.done:
			return nil
		case <-stepDone:
			if ss.mustClose {
				return nil // returning ends the stream: the agent sees EOF
			}
			// keep responding until the agent goes away
			for {
				select {
				case m := <-ss.inbox:
					ss.respond(m)
				case <-ss.done:
					return nil
				}
			}
		}
	}
}

func (ss *session) setLease(g msg.LeaseGrant) {
	ss.s.mu.Lock()
	ss.ag.lease = &g
	ss.s.mu.Unlock()
}

// grantForRenew is idempotent per message_id: a resend gets the same grant.
func (ss *session) grantForRenew(lr msg.LeaseRenew, now time.Time) msg.LeaseGrant {
	ss.s.mu.Lock()
	if g, seen := ss.ag.renewIDs[lr.MessageID]; seen {
		ss.s.mu.Unlock()
		return g
	}
	ss.s.mu.Unlock()
	g := ss.newGrant(now)
	ss.s.mu.Lock()
	ss.ag.renewIDs[lr.MessageID] = g
	ss.ag.lease = &g
	ss.s.mu.Unlock()
	return g
}

func (ss *session) respond(m msg.Outbound) {
	switch v := m.(type) {
	case msg.Heartbeat:
		st := msg.ClockOK
		if strings.EqualFold(ss.policy.Heartbeat.State, "SUSPECT") {
			st = msg.ClockSuspect
		}
		ss.send(msg.HeartbeatAck{Envelope: ss.env(), ServerClock: time.Now(), ClockOffsetMs: ss.policy.Heartbeat.OffsetMs, ClockState: st})
	case msg.LeaseRenew:
		ss.s.mu.Lock()
		cur := ss.ag.lease
		_, seen := ss.ag.renewIDs[v.MessageID]
		ss.s.mu.Unlock()
		switch {
		case ss.policy.Renew == "silent":
		case strings.HasPrefix(ss.policy.Renew, "refuse:"):
			ss.send(msg.Error{Envelope: ss.env(), Code: msg.ErrorCode(strings.TrimPrefix(ss.policy.Renew, "refuse:")), CorrelationID: v.MessageID})
		case seen: // resend of a request already granted → same grant (AGW-D15 adoption)
			ss.send(ss.grantForRenew(v, time.Now()))
		case cur == nil || v.NonceConsumed != cur.Nonce:
			ss.send(msg.Error{Envelope: ss.env(), Code: msg.ErrLeaseRenewRefused, CorrelationID: v.MessageID, Message: "nonce"})
		default:
			ss.send(ss.grantForRenew(v, time.Now()))
		}
	case msg.CommandPull:
		c := ss.commands[v.CommandID]
		switch {
		case c == nil:
			ss.send(msg.Error{Envelope: ss.env(), Code: msg.ErrUnknownCommand, CorrelationID: v.CommandID})
		case c.superseded:
			ss.send(msg.Error{Envelope: ss.env(), Code: msg.ErrSuperseded, CorrelationID: v.CommandID})
		default:
			o := c.offer
			o.Envelope = ss.env()
			ss.send(o)
		}
	case msg.CommandReceived:
		if c := ss.commands[v.CommandID]; c != nil && c.state == "OFFERED" {
			c.state = "DELIVERED"
		}
	case msg.CommandAck:
		if c := ss.commands[v.CommandID]; c != nil {
			if v.Outcome == msg.OutcomeRefused {
				c.state = "REFUSED"
			} else {
				c.state = "ACKED"
			}
		}
	case msg.ActualStateReportBatch:
		ss.ackReports(v)
	}
}

func (ss *session) ackReports(b msg.ActualStateReportBatch) {
	mode := ss.policy.Report.Mode
	if mode == "silent" {
		return
	}
	ack := msg.ReportAck{Envelope: ss.env(), InReplyToMessageID: b.MessageID}
	for _, it := range b.Items {
		r := msg.ReportResult{InReplyToItemID: it.ItemID, Sequence: it.Sequence, Trust: msg.TrustTrusted, Disposition: msg.DispApplied}
		if strings.HasPrefix(mode, "refuse:") {
			r.Disposition = msg.DispRefused
			r.RefusedReason = msg.ErrorCode(strings.TrimPrefix(mode, "refuse:"))
		} else {
			ss.s.mu.Lock()
			rk := it.BindingID + "|" + it.ReplicaRef
			if _, ok := ss.s.replicas[rk]; !ok {
				ss.s.replicas[rk] = "rep-" + ss.s.newID()[:8]
			}
			ik := rk + "|" + it.Incarnation
			if _, ok := ss.s.incarn[ik]; !ok {
				ss.s.incarn[ik] = "inc-" + ss.s.newID()[:8]
			}
			r.ReplicaID, r.IncarnationID = ss.s.replicas[rk], ss.s.incarn[ik]
			ss.s.mu.Unlock()
		}
		ack.Results = append(ack.Results, r)
	}
	ss.send(ack)
}

// runStep returns false when the session should stop.
func (ss *session) runStep(st Step) bool {
	switch {
	case st.Expect != nil:
		to := st.Expect.Timeout
		if to == 0 {
			to = 10 * time.Second
		}
		next, ok := ss.s.tr.waitForFrom(st.Expect.Kind, ss.cursor, to)
		if !ok {
			ss.s.log.Error("fakegw: expect timed out", "kind", st.Expect.Kind)
			return false
		}
		ss.cursor = next
	case st.Offer != nil:
		o := st.Offer
		sum := sha256.Sum256([]byte(o.Payload))
		h := hex.EncodeToString(sum[:])
		if o.BadHash {
			h = "deadbeef"
		}
		dl := o.Deadline
		if dl == 0 {
			dl = 5 * time.Minute
		}
		key := o.IdempotencyKey
		if key == "" {
			key = "key-" + o.CommandID
		}
		ss.commands[o.CommandID] = &cmdState{state: "OFFERED", offer: msg.CommandOffer{
			CommandID: o.CommandID, Generation: o.Generation, IdempotencyKey: key, BindingID: o.BindingID,
			Operation: msg.Operation(o.Operation), Payload: []byte(o.Payload), ConfigSHA256: h,
			SupersedesGeneration: o.SupersedesGeneration, Deadline: time.Now().Add(dl),
		}}
		ss.send(msg.GenerationAvailable{Envelope: ss.env(), CommandID: o.CommandID, BindingID: o.BindingID, Generation: o.Generation,
			Operation: msg.Operation(o.Operation), ConfigSHA256: h, Deadline: time.Now().Add(dl)})
	case st.Superseded != nil:
		if c := ss.commands[*st.Superseded]; c != nil {
			c.superseded = true
			c.state = "SUPERSEDED"
		}
	case st.Error != nil:
		code := msg.ErrorCode(st.Error.Code)
		ss.send(msg.Error{Envelope: ss.env(), Code: code, RetryAfter: st.Error.RetryAfter, Message: "scripted"})
		// A session-level error ends the stream from the gateway side, as the
		// real gateway would; per-command codes and LEASE_RENEW_REQUIRED do not.
		if msg.Classify(code) != msg.ClassPerCommand && code != msg.ErrLeaseRenewRequired && code != msg.ErrLeaseRenewRefused {
			ss.mustClose = true
			return false
		}
	case st.Revoke != nil:
		ss.s.mu.Lock()
		ss.ag.revoked = true
		ss.s.mu.Unlock()
		e := uint64(2)
		ss.send(msg.Revoke{Envelope: ss.env(), AgentRevocationEpoch: &e, Reason: *st.Revoke})
		ss.mustClose = true
		return false
	case st.Drain != nil:
		ss.send(msg.Drain{Envelope: ss.env(), Reason: "planned restart", ReconnectAfter: *st.Drain})
		ss.mustClose = true
		return false
	case st.Disconnect != nil && *st.Disconnect:
		ss.mustClose = true
		return false
	case st.Sleep != nil:
		time.Sleep(*st.Sleep)
	case st.SendGrant != nil && *st.SendGrant:
		g := ss.newGrant(time.Now())
		ss.setLease(g)
		ss.send(g)
	case st.RenewPolicy != nil:
		ss.policy.Renew = *st.RenewPolicy
	case st.HeartbeatPolicy != nil:
		ss.policy.Heartbeat = *st.HeartbeatPolicy
	case st.ReportPolicy != nil:
		ss.policy.Report = *st.ReportPolicy
	}
	return true
}

var _ = fmt.Sprintf
```

`runStep` returning `false` ends the scripted steps. When `mustClose` was set (`disconnect`, `drain`, `revoke`, a session-level `error`), `run` returns and the agent sees the stream end; otherwise `run` keeps answering the auto-responders until the agent closes.

- [ ] **Step 5: Failing server test — enrollment replay**

`internal/fakegw/server_test.go`:

```go
package fakegw

import (
	"context"
	"errors"
	"testing"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/metadata"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/wire/codec"
	"github.com/solace/solution-manager/go-agent/internal/wire/pb"
)

func enroll(t *testing.T, s *Server, token string, csr []byte) (*msg.EnrollResponse, error) {
	t.Helper()
	st, _ := store.Open(t.TempDir())
	id, _ := identity.Load(st)
	tlsCfg, _ := id.EnrollTLS(s.CAPEM())
	cc, err := grpc.NewClient(s.Endpoint(), grpc.WithTransportCredentials(credentials.NewTLS(tlsCfg)))
	if err != nil {
		t.Fatal(err)
	}
	defer cc.Close()
	ctx := metadata.AppendToOutgoingContext(context.Background(), tokenHeader, token)
	resp, err := pb.NewAgentGatewayClient(cc).Enroll(ctx, codec.EnrollRequestToPB(msg.EnrollRequest{CSRDER: csr, AgentVersion: "t"}))
	if err != nil {
		return nil, codec.ErrorFromStatus(err)
	}
	r := codec.EnrollResponseFromPB(resp)
	return &r, nil
}

func TestEnrollReplayAndConsumption(t *testing.T) {
	s, err := Start(Config{Listen: "127.0.0.1:0", Tokens: []string{"tok"}})
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	st, _ := store.Open(t.TempDir())
	id, _ := identity.Load(st)
	csr, _ := id.CSR()

	r1, err := enroll(t, s, "tok", csr)
	if err != nil {
		t.Fatal(err)
	}
	r2, err := enroll(t, s, "tok", csr) // same (token, CSR) → replay
	if err != nil || r2.AgentID != r1.AgentID || string(r2.CertificateChain[0]) != string(r1.CertificateChain[0]) {
		t.Fatalf("replay must return the stored reply: %v", err)
	}
	st2, _ := store.Open(t.TempDir())
	id2, _ := identity.Load(st2)
	csr2, _ := id2.CSR()
	_, err = enroll(t, s, "tok", csr2) // different CSR → consumed
	var pe *msg.ProtocolError
	if !errors.As(err, &pe) || pe.Code != msg.ErrTokenConsumed {
		t.Fatalf("want TOKEN_CONSUMED, got %v", err)
	}
	_, err = enroll(t, s, "nope", csr)
	if !errors.As(err, &pe) || pe.Code != msg.ErrUnauthenticatedIdentity {
		t.Fatalf("want UNAUTHENTICATED_IDENTITY, got %v", err)
	}
	if s.Count("EnrollRequest") != 4 {
		t.Fatalf("transcript count %d", s.Count("EnrollRequest"))
	}
}

func TestScenarioLoads(t *testing.T) {
	sc, err := LoadScenario("scenarios/demo.yaml")
	if err != nil {
		t.Fatal(err)
	}
	if len(sc.Steps) == 0 || sc.Lease.TargetID == "" {
		t.Fatalf("%+v", sc)
	}
}
```

- [ ] **Step 6: The demo scenario and the binary**

`internal/fakegw/scenarios/demo.yaml`:

```yaml
lease:
  target_id: target-1
  binding_ids: [binding-1]
  capabilities: [apply-config, lifecycle]
  duration: 1h
  verb_ceilings: {preflight: 1m, apply: 2m, restart: 5m, stop: 2m}
  admission_margin: 30s
limits:
  max_message_bytes: 1048576
  heartbeat_interval: 5s
  keepalive_interval: 30s
  skew_tolerance: 30s
  report_interval: 20s
policy:
  on_open: accept
  renew: grant
  heartbeat: {offset_ms: 0, state: OK}
  report: {mode: auto}
steps:
  - expect: {kind: ActualStateReportBatch, timeout: 30s}
  - offer: {command_id: cmd-1, binding_id: binding-1, generation: 1, operation: preflight, payload: ""}
  - expect: {kind: CommandAck, timeout: 60s}
  - offer: {command_id: cmd-2, binding_id: binding-1, generation: 1, operation: apply, payload: "server.port: 8081\nmanagement.endpoints.web.exposure.include: health\n"}
  - expect: {kind: CommandAck, timeout: 60s}
  - offer: {command_id: cmd-3, binding_id: binding-1, generation: 1, operation: restart, payload: ""}
  - expect: {kind: CommandAck, timeout: 120s}
  - expect: {kind: ActualStateReportBatch, timeout: 60s}
```

`cmd/fake-gateway/main.go`:

```go
package main

import (
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/solace/solution-manager/go-agent/internal/fakegw"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:8443", "listen address")
	scenarioPath := flag.String("scenario", "", "scenario YAML (default: built-in accept-only)")
	tokens := flag.String("enroll-token", "demo-token", "comma-separated single-use enrollment tokens")
	caOut := flag.String("ca-out", "", "write the fake CA PEM here (the agent's --trust-bundle)")
	flag.Parse()

	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))
	sc := fakegw.DefaultScenario()
	if *scenarioPath != "" {
		var err error
		if sc, err = fakegw.LoadScenario(*scenarioPath); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(2)
		}
	}
	s, err := fakegw.Start(fakegw.Config{Listen: *listen, Tokens: strings.Split(*tokens, ","), Scenario: sc, Log: log})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if *caOut != "" {
		if err := os.WriteFile(*caOut, s.CAPEM(), 0o644); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	}
	log.Info("fake gateway listening", "endpoint", s.Endpoint(), "ca", *caOut)
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	s.Stop()
	for _, e := range s.Transcript() {
		fmt.Printf("%s %-3s %s\n", e.At.Format("15:04:05.000"), e.Dir, e.Kind)
	}
}
```

- [ ] **Step 7: Run the tests**

Run: `go test ./internal/fakegw/ -race && go build ./cmd/fake-gateway`
Expected: PASS, binary builds.

- [ ] **Step 8: Commit**

```bash
git add internal/fakegw cmd/fake-gateway
git commit -m "go-agent: scenario-driven fake gateway with faithful enroll/lease semantics (AG-D16)

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 11: `sm-agent` binary and P1 integration — the agent reaches `ACTIVE`

**Files:**
- Create: `internal/agent/wiring.go`, `internal/agent/integration_test.go` (`//go:build integration`), `internal/agent/inttest_helpers_test.go` (`//go:build integration`)
- Modify: `cmd/sm-agent/main.go`

**Interfaces:**
- Produces: `agent.Build(cfg BuildConfig) (*Loop, *store.Store, error)` with `BuildConfig{DataDir, Endpoint, Token, TrustBundlePEM []byte, AgentVersion string; Log *slog.Logger; Observe func(...)}` — the one place the real dependencies are assembled; `main` and the integration tests both call it.
- Integration helper `startAgent(t, gw *fakegw.Server, opts ...func(*BuildConfig)) (*Loop, context.CancelFunc)`.

- [ ] **Step 1: Wiring**

`internal/agent/wiring.go`:

```go
package agent

import (
	"log/slog"
	"math/rand/v2"
	"time"

	"github.com/google/uuid"

	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
	"github.com/solace/solution-manager/go-agent/internal/wire"
)

type BuildConfig struct {
	DataDir        string
	Endpoint       string
	Token          string
	TrustBundlePEM []byte
	AgentVersion   string
	Log            *slog.Logger
	Observe        func(reactor.Event, []reactor.Effect)
}

func newUUIDv7() string {
	id, err := uuid.NewV7()
	if err != nil {
		return uuid.NewString()
	}
	return id.String()
}

// fullJitter returns a uniform duration in [0, d] — "full jitter" backoff.
func fullJitter(d time.Duration) time.Duration {
	if d <= 0 {
		return 0
	}
	return time.Duration(rand.Int64N(int64(d) + 1))
}

func Build(bc BuildConfig) (*Loop, *store.Store, error) {
	s, err := store.Open(bc.DataDir)
	if err != nil {
		return nil, nil, err
	}
	id, err := identity.Load(s)
	if err != nil {
		return nil, nil, err
	}
	cfg := Config{
		BootstrapEndpoint: bc.Endpoint,
		EnrollmentToken:   bc.Token,
		AgentVersion:      bc.AgentVersion,
		Capabilities:      []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle},
	}
	st, err := LoadState(cfg, s, id)
	if err != nil {
		return nil, nil, err
	}
	l := New(Deps{
		Config:   cfg,
		Store:    s,
		Identity: id,
		Wire:     wire.New(id, bc.TrustBundlePEM, bc.Log),
		NewID:    newUUIDv7,
		Jitter:   fullJitter,
		Now:      time.Now,
		Log:      bc.Log,
		Observe:  bc.Observe,
	}, st)
	return l, s, nil
}
```

`*wire.Client` must satisfy `agent.Wire`: its `Dial` returns `*wire.Conn`, not `agent.Conn`. Add an adapter in `wiring.go`:

```go
type wireAdapter struct{ c *wire.Client }

func (w wireAdapter) Enroll(ctx context.Context, endpoint string, req msg.EnrollRequest) (*msg.EnrollResponse, error) {
	return w.c.Enroll(ctx, endpoint, req)
}

func (w wireAdapter) Dial(ctx context.Context, endpoint string) (Conn, error) {
	c, err := w.c.Dial(ctx, endpoint)
	if err != nil {
		return nil, err
	}
	return c, nil
}
```

and use `Wire: wireAdapter{wire.New(id, bc.TrustBundlePEM, bc.Log)}` (add `"context"` to the imports). Returning a nil `*wire.Conn` as a non-nil `Conn` interface is the classic Go trap; the explicit `nil, err` return above is why the adapter exists.

- [ ] **Step 2: The binary**

`cmd/sm-agent/main.go`:

```go
package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/solace/solution-manager/go-agent/internal/agent"
)

var version = "dev" // -ldflags "-X main.version=..."

func main() {
	dataDir := flag.String("data-dir", envOr("SM_AGENT_DATA_DIR", "/var/lib/sm-agent"), "state directory")
	endpoint := flag.String("connect-endpoint", "", "bootstrap DP endpoint host:port (required until enrolled)")
	token := flag.String("enrollment-token", os.Getenv("SM_AGENT_ENROLL_TOKEN"), "single-use enrollment token (env SM_AGENT_ENROLL_TOKEN)")
	trust := flag.String("trust-bundle", "", "PEM file with the DP server trust bundle or pin")
	level := flag.String("log-level", "info", "debug|info|warn|error")
	flag.Parse()

	var lvl slog.Level
	if err := lvl.UnmarshalText([]byte(*level)); err != nil {
		fmt.Fprintln(os.Stderr, "bad --log-level:", err)
		os.Exit(2)
	}
	log := slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{Level: lvl}))

	var bundle []byte
	if *trust != "" {
		b, err := os.ReadFile(*trust)
		if err != nil {
			log.Error("trust bundle", "err", err)
			os.Exit(2)
		}
		bundle = b
	}
	l, _, err := agent.Build(agent.BuildConfig{
		DataDir: *dataDir, Endpoint: *endpoint, Token: *token, TrustBundlePEM: bundle, AgentVersion: version, Log: log,
	})
	if err != nil {
		log.Error("build", "err", err)
		os.Exit(1)
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	if err := l.Run(ctx); err != nil {
		log.Error("agent stopped", "err", err)
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
```

- [ ] **Step 3: Integration helpers**

`internal/agent/inttest_helpers_test.go`:

```go
//go:build integration

package agent

import (
	"context"
	"io"
	"log/slog"
	"path/filepath"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/fakegw"
	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func startGateway(t *testing.T, sc *fakegw.Scenario) *fakegw.Server {
	t.Helper()
	gw, err := fakegw.Start(fakegw.Config{Listen: "127.0.0.1:0", Tokens: []string{"tok"}, Scenario: sc,
		Log: slog.New(slog.NewTextHandler(io.Discard, nil))})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(gw.Stop)
	return gw
}

type agentHandle struct {
	loop   *Loop
	dir    string
	cancel context.CancelFunc
	done   chan error
}

func startAgent(t *testing.T, gw *fakegw.Server, dir string, mutate func(*BuildConfig)) *agentHandle {
	t.Helper()
	if dir == "" {
		dir = filepath.Join(t.TempDir(), "data")
	}
	bc := BuildConfig{DataDir: dir, Endpoint: gw.Endpoint(), Token: "tok", TrustBundlePEM: gw.CAPEM(), AgentVersion: "test",
		Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	if mutate != nil {
		mutate(&bc)
	}
	l, _, err := Build(bc)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	h := &agentHandle{loop: l, dir: dir, cancel: cancel, done: make(chan error, 1)}
	go func() { h.done <- l.Run(ctx) }()
	t.Cleanup(func() { cancel(); <-h.done })
	return h
}

func (h *agentHandle) stop() {
	h.cancel()
	<-h.done
}

func waitPhase(t *testing.T, l *Loop, want Phase, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if l.Snapshot().Session.Phase == want {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("phase %s not reached (at %s)", want, l.Snapshot().Session.Phase)
}

func waitHalt(t *testing.T, l *Loop, want msg.ErrorCode, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if l.Halted() == want {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("halt %s not reached (halted=%q phase=%s)", want, l.Halted(), l.Snapshot().Session.Phase)
}
```

- [ ] **Step 4: P1 integration scenarios**

`internal/agent/integration_test.go`:

```go
//go:build integration

package agent

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"path/filepath"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/fakegw"
	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func TestIntegrationHappyPathReachesActive(t *testing.T) {
	gw := startGateway(t, nil)
	h := startAgent(t, gw, "", nil)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if gw.Count("SessionOpen") != 1 || gw.Count("EnrollRequest") != 1 {
		t.Fatalf("open=%d enroll=%d", gw.Count("SessionOpen"), gw.Count("EnrollRequest"))
	}
	if !gw.WaitFor("Heartbeat", 1, 15*time.Second) {
		t.Fatal("no heartbeat")
	}
}

// lostReplyWire performs the real Enroll but reports the first reply as lost.
type lostReplyWire struct {
	Wire
	dropped bool
}

func (w *lostReplyWire) Enroll(ctx context.Context, ep string, req msg.EnrollRequest) (*msg.EnrollResponse, error) {
	resp, err := w.Wire.Enroll(ctx, ep, req)
	if err == nil && !w.dropped {
		w.dropped = true
		return nil, errors.New("simulated: reply lost in transit")
	}
	return resp, err
}

func TestIntegrationLostEnrollReplyReplaysSameCSR(t *testing.T) {
	gw := startGateway(t, nil)
	// Build by hand so the wire can be wrapped before Run — Build is in this
	// package, so l.d is reachable.
	l, _, err := Build(BuildConfig{DataDir: filepath.Join(t.TempDir(), "data"), Endpoint: gw.Endpoint(), Token: "tok",
		TrustBundlePEM: gw.CAPEM(), AgentVersion: "test", Log: slog.New(slog.NewTextHandler(io.Discard, nil))})
	if err != nil {
		t.Fatal(err)
	}
	l.d.Wire = &lostReplyWire{Wire: l.d.Wire}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = l.Run(ctx) }()
	waitPhase(t, l, PhaseActive, 15*time.Second)
	// Two EnrollRequests reached the gateway with the same CSR; the second was
	// the stored replay (no TOKEN_CONSUMED), so the agent still came up.
	if gw.Count("EnrollRequest") < 2 {
		t.Fatalf("expected a replayed enroll, got %d", gw.Count("EnrollRequest"))
	}
	reqs := gw.Received("EnrollRequest")
	a := reqs[len(reqs)-2].(msg.EnrollRequest).CSRDER
	b := reqs[len(reqs)-1].(msg.EnrollRequest).CSRDER
	if string(a) != string(b) {
		t.Fatal("retry must resend the identical CSR")
	}
	// TOKEN_CONSUMED would have halted the agent instead of activating it.
	if l.Halted() != "" {
		t.Fatalf("halted: %s", l.Halted())
	}
}

func TestIntegrationRestartReusesIdentityWithoutEnroll(t *testing.T) {
	gw := startGateway(t, nil)
	h := startAgent(t, gw, "", nil)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	h.stop()
	h2 := startAgent(t, gw, h.dir, nil)
	waitPhase(t, h2.loop, PhaseActive, 10*time.Second)
	if gw.Count("EnrollRequest") != 1 {
		t.Fatalf("a restarted agent must not re-enroll: %d", gw.Count("EnrollRequest"))
	}
	if gw.Count("SessionOpen") != 2 {
		t.Fatalf("SessionOpen=%d", gw.Count("SessionOpen"))
	}
}

func TestIntegrationRejectReEnrollHalts(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Policy.OnOpen = "reject:RE_ENROLL_REQUIRED"
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	waitHalt(t, h.loop, msg.ErrReEnrollRequired, 10*time.Second)
}
```

- [ ] **Step 5: Run**

Run: `go build ./... && go test -race -tags integration ./internal/agent/ -run Integration -v`
Expected: all four PASS. `TestIntegrationLostEnrollReplyReplaysSameCSR` takes ~1 s (one backoff).

- [ ] **Step 6: Commit**

```bash
git add cmd/sm-agent internal/agent
git commit -m "go-agent: sm-agent binary wiring and P1 integration — enroll, replay, restart, halt

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

**Phase P1 exit:** `make test` green, `make test-integration` green, `make build` produces both binaries.

---

## Phase P2 — authority, clock, admin surface

### Task 12: `internal/clock`

**Files:**
- Create: `internal/clock/clock.go`, `internal/clock/clock_test.go`

**Interfaces:**
- Produces: `clock.Clock` with `Offset() time.Duration`, `State() msg.ClockState`, `Update(offsetMs int64, state msg.ClockState)`, `Set(serverClock, localAt time.Time)` (from `LeaseGrant.server_clock` when no heartbeat has run yet). Thread-safe; zero value usable.

- [ ] **Step 1: Failing test**

`internal/clock/clock_test.go`:

```go
package clock

import (
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func TestOffsetAndState(t *testing.T) {
	var c Clock
	if c.Offset() != 0 || c.State() != msg.ClockOK {
		t.Fatal("zero value must be OK/0")
	}
	c.Update(-1500, msg.ClockSuspect)
	if c.Offset() != -1500*time.Millisecond || c.State() != msg.ClockSuspect {
		t.Fatalf("%v %s", c.Offset(), c.State())
	}
	local := time.Unix(1000, 0)
	c.Set(local.Add(3*time.Second), local)
	if c.Offset() != 3*time.Second || c.State() != msg.ClockOK {
		t.Fatalf("Set must derive offset and reset state: %v %s", c.Offset(), c.State())
	}
}
```

- [ ] **Step 2: Implement**

`internal/clock/clock.go`:

```go
// Package clock tracks the DP-reported clock offset. Reducers never read a
// clock; the loop reads Offset() into Env for every event.
package clock

import (
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

type Clock struct {
	mu     sync.Mutex
	offset time.Duration
	state  msg.ClockState
}

func (c *Clock) Offset() time.Duration {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.offset
}

func (c *Clock) State() msg.ClockState {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.state == "" {
		return msg.ClockOK
	}
	return c.state
}

// Update stores the latest HeartbeatAck values verbatim — the DP computed
// them; no smoothing.
func (c *Clock) Update(offsetMs int64, state msg.ClockState) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.offset = time.Duration(offsetMs) * time.Millisecond
	c.state = state
}

// Set derives the offset from a server clock observed at localAt.
func (c *Clock) Set(serverClock, localAt time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.offset = serverClock.Sub(localAt)
	c.state = msg.ClockOK
}
```

- [ ] **Step 3: Run, commit**

Run: `go test ./internal/clock/` — Expected: PASS.

```bash
git add internal/clock
git commit -m "go-agent: clock offset tracker

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 13: The `authority` reducer

**Files:**
- Create: `internal/authority/authority.go`, `internal/authority/authority_test.go`

**Interfaces:**
- Produces:
  - `authority.Phase` ∈ `NONE ACTIVE RENEW_DEGRADED LEASE_EXPIRED CLOCK_SUSPECT REVOKED RE_ENROLL_REQUIRED`.
  - `authority.State{Phase; Grant *msg.LeaseGrant; Pending *PendingRenew; LeafThumbprint string}`; `PendingRenew{MessageID, Nonce string}`.
  - `func Load(s *store.Store, leafThumbprint string) (State, error)` — reads `session/lease.json` (schema 1).
  - `func Reduce(s State, ev reactor.Event, env reactor.Env) (State, []reactor.Effect)`.
  - Read-only helpers for sibling reducers: `(State).Active() bool`, `Allows(msg.Capability) bool`, `HasBinding(string) bool`, `Admissible(op msg.Operation, dpNow time.Time) bool`, `StepDeadline(op, dpNow) time.Time`, `Remaining(dpNow) time.Duration`, `RefusalDetail(op, bindingID, dpNow) msg.DetailCode` (`""` when admissible).
  - `const renewRetry = 30 * time.Second` — cadence for resending an unanswered `LeaseRenew` (same `message_id`).

- [ ] **Step 1: Failing tests**

`internal/authority/authority_test.go`:

```go
package authority

import (
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

var t0 = time.Date(2026, 9, 22, 10, 0, 0, 0, time.UTC)

const leaf = "leafthumb"

func env(now time.Time, offset time.Duration) reactor.Env {
	n := 0
	return reactor.Env{Now: now, Offset: offset, NewID: func() string { n++; return "mid-" + time.Duration(n).String() },
		Jitter: func(d time.Duration) time.Duration { return d }, Limits: msg.Limits{SkewTolerance: 30 * time.Second}}
}

func grant(issued time.Time, nonce string) msg.LeaseGrant {
	return msg.LeaseGrant{AgentID: "a", CnfThumbprint: leaf, TargetID: "t", BindingIDs: []string{"b1"},
		AgentRevocationEpoch: 1, FleetRevocationEpoch: 1, IssuedAt: issued, ExpiresAt: issued.Add(time.Hour), Audience: "dp", Nonce: nonce,
		Capabilities: []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle},
		VerbCeilings: map[msg.Operation]time.Duration{msg.OpApply: 2 * time.Minute, msg.OpRestart: 5 * time.Minute}, AdmissionMargin: 30 * time.Second,
		ServerClock: issued}
}

func has[T reactor.Effect](effs []reactor.Effect) (T, bool) {
	for _, e := range effs {
		if v, ok := e.(T); ok {
			return v, true
		}
	}
	var zero T
	return zero, false
}

func timer(effs []reactor.Effect, kind reactor.TimerKind) (time.Duration, bool) {
	for _, e := range effs {
		if a, ok := e.(reactor.ArmTimer); ok && a.Kind == kind {
			return a.After, true
		}
	}
	return 0, false
}

func TestSessionAcceptActivatesPersistsAndArmsTimers(t *testing.T) {
	g := grant(t0, "n1")
	s, effs := Reduce(State{LeafThumbprint: leaf}, reactor.WireIn{At: t0, Msg: msg.SessionAccept{Lease: g}}, env(t0, 0))
	if s.Phase != PhaseActive || s.Grant == nil || s.Grant.Nonce != "n1" {
		t.Fatalf("%+v", s)
	}
	if p, ok := has[reactor.Persist](effs); !ok || p.Rec.Rel() != "session/lease.json" {
		t.Fatal("lease must be persisted")
	}
	if d, ok := timer(effs, reactor.RenewDue); !ok || d != 30*time.Minute {
		t.Fatalf("renew at 50%%: %v %v", d, ok)
	}
	if d, ok := timer(effs, reactor.LeaseExpiry); !ok || d != time.Hour {
		t.Fatalf("expiry timer: %v %v", d, ok)
	}
}

func TestTimersUseDPTime(t *testing.T) {
	g := grant(t0, "n1")
	// agent clock is 10 minutes behind DP: offset +10m; the lease is 30m old in DP time
	s, effs := Reduce(State{LeafThumbprint: leaf}, reactor.WireIn{At: t0.Add(20 * time.Minute), Msg: msg.SessionAccept{Lease: g}}, env(t0.Add(20*time.Minute), 10*time.Minute))
	if s.Phase != PhaseActive {
		t.Fatal(s.Phase)
	}
	if d, _ := timer(effs, reactor.RenewDue); d != 0 {
		t.Fatalf("renew already due in DP time, got %v", d)
	}
	if d, _ := timer(effs, reactor.LeaseExpiry); d != 30*time.Minute {
		t.Fatalf("expiry in 30m DP time, got %v", d)
	}
}

func TestRenewDueSendsRenewAndResendsSameID(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseActive, Grant: &g, LeafThumbprint: leaf}
	s, effs := Reduce(s, reactor.TimerFired{At: t0.Add(30 * time.Minute), Kind: reactor.RenewDue}, env(t0.Add(30*time.Minute), 0))
	snd, ok := has[reactor.Send](effs)
	if !ok {
		t.Fatal("no LeaseRenew")
	}
	lr := snd.Msg.(msg.LeaseRenew)
	if lr.NonceConsumed != "n1" || lr.MessageID == "" || s.Pending == nil || s.Pending.MessageID != lr.MessageID {
		t.Fatalf("%+v %+v", lr, s.Pending)
	}
	if _, ok := has[reactor.Persist](effs); !ok {
		t.Fatal("pending renew must be persisted")
	}
	if d, ok := timer(effs, reactor.RenewDue); !ok || d != renewRetry {
		t.Fatal("unanswered renew must be retried")
	}
	s2, effs2 := Reduce(s, reactor.TimerFired{At: t0.Add(31 * time.Minute), Kind: reactor.RenewDue}, env(t0.Add(31*time.Minute), 0))
	lr2 := has2[msg.LeaseRenew](t, effs2)
	if lr2.MessageID != lr.MessageID || s2.Pending.MessageID != lr.MessageID {
		t.Fatal("resend must reuse the same message_id")
	}
}

func has2[M msg.Outbound](t *testing.T, effs []reactor.Effect) M {
	t.Helper()
	snd, ok := has[reactor.Send](effs)
	if !ok {
		t.Fatalf("no Send")
	}
	m, ok := snd.Msg.(M)
	if !ok {
		t.Fatalf("Send is %T", snd.Msg)
	}
	return m
}

func TestLeaseGrantReplacesAndClearsPending(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseActive, Grant: &g, Pending: &PendingRenew{MessageID: "m", Nonce: "n1"}, LeafThumbprint: leaf}
	g2 := grant(t0.Add(30*time.Minute), "n2")
	s, effs := Reduce(s, reactor.WireIn{At: t0.Add(30 * time.Minute), Msg: g2}, env(t0.Add(30*time.Minute), 0))
	if s.Grant.Nonce != "n2" || s.Pending != nil || s.Phase != PhaseActive {
		t.Fatalf("%+v", s)
	}
	if d, _ := timer(effs, reactor.RenewDue); d != 30*time.Minute {
		t.Fatalf("renew re-armed from the new grant: %v", d)
	}
}

func TestSessionAcceptWithSameLeaseKeepsPending(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseLeaseExpired, Grant: &g, Pending: &PendingRenew{MessageID: "m", Nonce: "n1"}, LeafThumbprint: leaf}
	s, _ = Reduce(s, reactor.WireIn{At: t0.Add(time.Minute), Msg: msg.SessionAccept{Lease: g}}, env(t0.Add(time.Minute), 0))
	if s.Pending == nil || s.Pending.MessageID != "m" {
		t.Fatal("failover returned the same lease; the pending renewal is still owed under the same id")
	}
}

func TestRenewRefusedDegrades(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseActive, Grant: &g, Pending: &PendingRenew{MessageID: "m", Nonce: "n1"}, LeafThumbprint: leaf}
	s, _ = Reduce(s, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrLeaseRenewRefused, CorrelationID: "m"}}, env(t0, 0))
	if s.Phase != PhaseRenewDegraded || s.Pending != nil {
		t.Fatalf("%+v", s)
	}
	if s.Active() {
		t.Fatal("degraded is not active")
	}
}

func TestExpiryTimerExpires(t *testing.T) {
	g := grant(t0, "n1")
	for _, from := range []Phase{PhaseActive, PhaseRenewDegraded} {
		s := State{Phase: from, Grant: &g, LeafThumbprint: leaf}
		s, effs := Reduce(s, reactor.TimerFired{At: t0.Add(time.Hour), Kind: reactor.LeaseExpiry}, env(t0.Add(time.Hour), 0))
		if s.Phase != PhaseLeaseExpired {
			t.Fatalf("from %s: %s", from, s.Phase)
		}
		if c, ok := has[reactor.CancelTimer](effs); !ok || c.Kind != reactor.RenewDue {
			t.Fatal("renew timer must be cancelled on expiry")
		}
	}
}

func TestRenewRequiredWhenExpiredSendsRenew(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseLeaseExpired, Grant: &g, LeafThumbprint: leaf}
	s, effs := Reduce(s, reactor.WireIn{At: t0.Add(2 * time.Hour), Msg: msg.Error{Code: msg.ErrLeaseRenewRequired}}, env(t0.Add(2*time.Hour), 0))
	lr := has2[msg.LeaseRenew](t, effs)
	if lr.NonceConsumed != "n1" || s.Pending == nil {
		t.Fatalf("%+v", lr)
	}
}

func TestInvalidGrantsAreIgnored(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseActive, Grant: &g, LeafThumbprint: leaf}
	bad := grant(t0.Add(time.Minute), "n2")
	bad.CnfThumbprint = "someone-else"
	s2, effs := Reduce(s, reactor.WireIn{At: t0.Add(time.Minute), Msg: bad}, env(t0.Add(time.Minute), 0))
	if s2.Grant.Nonce != "n1" || len(effs) != 0 {
		t.Fatal("wrong thumbprint must be ignored")
	}
	older := grant(t0.Add(time.Minute), "n3")
	older.AgentRevocationEpoch = 0
	s3, _ := Reduce(s, reactor.WireIn{At: t0.Add(time.Minute), Msg: older}, env(t0.Add(time.Minute), 0))
	if s3.Grant.Nonce != "n1" {
		t.Fatal("lower epoch must be ignored")
	}
}

func TestClockSuspectFailsClosedAndRecovers(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseActive, Grant: &g, LeafThumbprint: leaf}
	s, _ = Reduce(s, reactor.WireIn{At: t0, Msg: msg.HeartbeatAck{ClockState: msg.ClockSuspect}}, env(t0, 0))
	if s.Phase != PhaseClockSuspect || s.Active() {
		t.Fatal(s.Phase)
	}
	s, effs := Reduce(s, reactor.WireIn{At: t0.Add(time.Minute), Msg: msg.HeartbeatAck{ClockState: msg.ClockOK}}, env(t0.Add(time.Minute), 0))
	if s.Phase != PhaseActive {
		t.Fatal(s.Phase)
	}
	if _, ok := timer(effs, reactor.RenewDue); !ok {
		t.Fatal("timers re-armed on recovery")
	}
	// offset beyond skew tolerance is suspect even if the DP says OK
	s, _ = Reduce(s, reactor.WireIn{At: t0.Add(time.Minute), Msg: msg.HeartbeatAck{ClockState: msg.ClockOK}}, env(t0.Add(time.Minute), 2*time.Minute))
	if s.Phase != PhaseClockSuspect {
		t.Fatal("offset > skew_tolerance must fail closed")
	}
}

func TestRevokeDropsLease(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseActive, Grant: &g, LeafThumbprint: leaf}
	s, effs := Reduce(s, reactor.WireIn{At: t0, Msg: msg.Revoke{Reason: "x"}}, env(t0, 0))
	if s.Phase != PhaseRevoked || s.Grant != nil {
		t.Fatalf("%+v", s)
	}
	if d, ok := has[reactor.Delete](effs); !ok || d.Rel != "session/lease.json" {
		t.Fatal("lease record must be deleted")
	}
}

func TestAdmissionArithmetic(t *testing.T) {
	g := grant(t0, "n1")
	s := State{Phase: PhaseActive, Grant: &g, LeafThumbprint: leaf}
	// apply needs 2m + 30s margin: admissible at T+57m29s, not at T+57m31s
	if !s.Admissible(msg.OpApply, t0.Add(57*time.Minute+29*time.Second)) {
		t.Fatal("should be admissible")
	}
	if s.Admissible(msg.OpApply, t0.Add(57*time.Minute+31*time.Second)) {
		t.Fatal("should be blocked")
	}
	if s.StepDeadline(msg.OpApply, t0.Add(59*time.Minute)) != t0.Add(time.Hour) {
		t.Fatal("deadline is capped by expires_at")
	}
	if s.StepDeadline(msg.OpApply, t0) != t0.Add(2*time.Minute) {
		t.Fatal("deadline is start + ceiling")
	}
	if s.RefusalDetail(msg.OpStop, "b1", t0) != msg.DetailNone || s.RefusalDetail(msg.OpApply, "nope", t0) != msg.DetailOf(msg.ErrBindingNotInLease) {
		t.Fatal("refusal detail")
	}
	s.Grant.Capabilities = []msg.Capability{msg.CapReportOnly}
	if s.RefusalDetail(msg.OpRestart, "b1", t0) != msg.DetailOf(msg.ErrCapabilityMissing) {
		t.Fatal("capability missing")
	}
	s.Phase = PhaseLeaseExpired
	if s.RefusalDetail(msg.OpApply, "b1", t0) != msg.DetailLeaseNotActive {
		t.Fatal("lease not active")
	}
}

func TestLoadAndResume(t *testing.T) {
	st, _ := store.Open(t.TempDir())
	g := grant(t0, "n1")
	if err := st.Save(store.JSON{RelPath: "session/lease.json", Value: Record{SchemaVersion: 1, Grant: g, Pending: &PendingRenew{MessageID: "m", Nonce: "n1"}}}); err != nil {
		t.Fatal(err)
	}
	s, err := Load(st, leaf)
	if err != nil || s.Grant == nil || s.Pending == nil {
		t.Fatalf("%v %+v", err, s)
	}
	live, effs := Reduce(s, reactor.Resumed{At: t0.Add(10 * time.Minute)}, env(t0.Add(10*time.Minute), 0))
	if live.Phase != PhaseActive {
		t.Fatal(live.Phase)
	}
	if d, _ := timer(effs, reactor.RenewDue); d != 20*time.Minute {
		t.Fatalf("renew from stored grant: %v", d)
	}
	dead, _ := Reduce(s, reactor.Resumed{At: t0.Add(2 * time.Hour)}, env(t0.Add(2*time.Hour), 0))
	if dead.Phase != PhaseLeaseExpired {
		t.Fatal(dead.Phase)
	}
	empty, _ := Load(store.MustOpen(t.TempDir()), leaf)
	none, _ := Reduce(empty, reactor.Resumed{At: t0}, env(t0, 0))
	if none.Phase != PhaseNone {
		t.Fatal(none.Phase)
	}
}
```

Add to `internal/store/store.go` a test convenience used above:

```go
// MustOpen is for tests.
func MustOpen(root string) *Store {
	s, err := Open(root)
	if err != nil {
		panic(err)
	}
	return s
}
```

- [ ] **Step 2: Run to verify failure**

Run: `go test ./internal/authority/`
Expected: FAIL — package missing.

- [ ] **Step 3: Implement**

`internal/authority/authority.go`:

```go
// Package authority is the lease reducer: SM-D28's authority machine as the
// agent-gateway design consumes it (§4.6, §4.8, §4.9). All arithmetic is in DP
// time (env.DPNow()).
package authority

import (
	"errors"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

const (
	relLease   = "session/lease.json"
	renewRetry = 30 * time.Second
)

type Phase string

const (
	PhaseNone             Phase = "NONE"
	PhaseActive           Phase = "ACTIVE"
	PhaseRenewDegraded    Phase = "RENEW_DEGRADED"
	PhaseLeaseExpired     Phase = "LEASE_EXPIRED"
	PhaseClockSuspect     Phase = "CLOCK_SUSPECT"
	PhaseRevoked          Phase = "REVOKED"
	PhaseReEnrollRequired Phase = "RE_ENROLL_REQUIRED"
)

// PendingRenew is a LeaseRenew whose terminal reply has not arrived. Its
// message_id is reused on every resend (AGW-D15 adoption/takeover).
type PendingRenew struct {
	MessageID string `json:"message_id"`
	Nonce     string `json:"nonce"`
}

type State struct {
	Phase          Phase
	Grant          *msg.LeaseGrant
	Pending        *PendingRenew
	LeafThumbprint string
}

type Record struct {
	SchemaVersion int            `json:"schema_version"`
	Grant         msg.LeaseGrant `json:"grant"`
	Pending       *PendingRenew  `json:"pending,omitempty"`
}

func (r Record) Version() int { return r.SchemaVersion }

func Load(s *store.Store, leafThumbprint string) (State, error) {
	st := State{Phase: PhaseNone, LeafThumbprint: leafThumbprint}
	rec, err := store.LoadJSON[Record](s, relLease, 1)
	if errors.Is(err, store.ErrNotFound) {
		return st, nil
	}
	if err != nil {
		return st, err
	}
	g := rec.Grant
	st.Grant, st.Pending = &g, rec.Pending
	return st, nil
}

// ---- read-only helpers ----

func (s State) Active() bool { return s.Phase == PhaseActive && s.Grant != nil }

func (s State) Allows(c msg.Capability) bool { return s.Grant != nil && s.Grant.Has(c) }

func (s State) HasBinding(id string) bool { return s.Grant != nil && s.Grant.HasBinding(id) }

func (s State) Remaining(dpNow time.Time) time.Duration {
	if s.Grant == nil {
		return 0
	}
	if d := s.Grant.ExpiresAt.Sub(dpNow); d > 0 {
		return d
	}
	return 0
}

func (s State) ceiling(op msg.Operation) time.Duration {
	if s.Grant == nil {
		return 0
	}
	return s.Grant.VerbCeilings[op]
}

// Admissible is gateway §4.6's inequality: expires_at − now ≥ ceiling + margin.
func (s State) Admissible(op msg.Operation, dpNow time.Time) bool {
	if s.Grant == nil {
		return false
	}
	return s.Grant.ExpiresAt.Sub(dpNow) >= s.ceiling(op)+s.Grant.AdmissionMargin
}

// StepDeadline is min(start + ceiling, expires_at).
func (s State) StepDeadline(op msg.Operation, start time.Time) time.Time {
	d := start.Add(s.ceiling(op))
	if s.Grant != nil && s.Grant.ExpiresAt.Before(d) {
		return s.Grant.ExpiresAt
	}
	return d
}

// RefusalDetail mirrors the DP's offer gate locally; "" means admissible.
func (s State) RefusalDetail(op msg.Operation, bindingID string, dpNow time.Time) msg.DetailCode {
	if !s.Active() {
		return msg.DetailLeaseNotActive
	}
	if !s.Allows(op.RequiredCapability()) {
		return msg.DetailOf(msg.ErrCapabilityMissing)
	}
	if !s.HasBinding(bindingID) {
		return msg.DetailOf(msg.ErrBindingNotInLease)
	}
	if !s.Admissible(op, dpNow) {
		return msg.DetailAdmissionBlocked
	}
	return msg.DetailNone
}

// ---- reducer ----

func Reduce(s State, ev reactor.Event, env reactor.Env) (State, []reactor.Effect) {
	if s.Phase == PhaseRevoked || s.Phase == PhaseReEnrollRequired {
		return s, nil
	}
	switch e := ev.(type) {
	case reactor.Resumed:
		return s.recompute(env, nil)

	case reactor.TimerFired:
		switch e.Kind {
		case reactor.RenewDue:
			if s.Phase != PhaseActive || s.Grant == nil {
				return s, nil
			}
			return s.sendRenew(env)
		case reactor.LeaseExpiry:
			if s.Phase == PhaseActive || s.Phase == PhaseRenewDegraded {
				s.Phase = PhaseLeaseExpired
				return s, []reactor.Effect{reactor.CancelTimer{Kind: reactor.RenewDue}}
			}
		}
		return s, nil

	case reactor.WireIn:
		switch m := e.Msg.(type) {
		case msg.SessionAccept:
			return s.acceptGrant(m.Lease, env, true)
		case msg.LeaseGrant:
			return s.acceptGrant(m, env, false)
		case msg.Error:
			switch m.Code {
			case msg.ErrLeaseRenewRequired:
				if s.Grant == nil {
					return s, nil
				}
				return s.sendRenew(env)
			case msg.ErrLeaseRenewRefused:
				s.Pending = nil
				if s.Phase == PhaseActive {
					s.Phase = PhaseRenewDegraded
				}
				return s, []reactor.Effect{s.persist(), reactor.CancelTimer{Kind: reactor.RenewDue}}
			}
		case msg.HeartbeatAck:
			suspect := m.ClockState == msg.ClockSuspect || absDur(env.Offset) > env.Limits.SkewTolerance
			if suspect {
				if s.Phase == PhaseActive || s.Phase == PhaseRenewDegraded {
					s.Phase = PhaseClockSuspect
					return s, []reactor.Effect{reactor.CancelTimer{Kind: reactor.RenewDue}}
				}
				return s, nil
			}
			if s.Phase == PhaseClockSuspect {
				return s.recompute(env, nil)
			}
		case msg.Revoke:
			s.Phase, s.Grant, s.Pending = PhaseRevoked, nil, nil
			return s, []reactor.Effect{reactor.Delete{Rel: relLease}, reactor.CancelTimer{Kind: reactor.RenewDue}, reactor.CancelTimer{Kind: reactor.LeaseExpiry}}
		case msg.SessionReject:
			if m.Code == msg.ErrReEnrollRequired {
				s.Phase, s.Grant, s.Pending = PhaseReEnrollRequired, nil, nil
				return s, []reactor.Effect{reactor.Delete{Rel: relLease}, reactor.CancelTimer{Kind: reactor.RenewDue}, reactor.CancelTimer{Kind: reactor.LeaseExpiry}}
			}
		}
	}
	return s, nil
}

func absDur(d time.Duration) time.Duration {
	if d < 0 {
		return -d
	}
	return d
}

func (s State) persist() reactor.Effect {
	rec := Record{SchemaVersion: 1, Pending: s.Pending}
	if s.Grant != nil {
		rec.Grant = *s.Grant
	}
	return reactor.Persist{Rec: store.JSON{RelPath: relLease, Value: rec}}
}

func (s State) valid(g msg.LeaseGrant) bool {
	if g.CnfThumbprint != s.LeafThumbprint || !g.ExpiresAt.After(g.IssuedAt) {
		return false
	}
	if s.Grant != nil && (g.AgentRevocationEpoch < s.Grant.AgentRevocationEpoch || g.FleetRevocationEpoch < s.Grant.FleetRevocationEpoch) {
		return false
	}
	return true
}

// acceptGrant stores a validated grant and re-derives phase and timers. A
// grant carrying the nonce of a pending renewal is the same lease returned
// on failover, so the pending request is still owed under its id.
func (s State) acceptGrant(g msg.LeaseGrant, env reactor.Env, fromAccept bool) (State, []reactor.Effect) {
	if !s.valid(g) {
		return s, nil
	}
	if s.Pending != nil && s.Pending.Nonce != g.Nonce {
		s.Pending = nil
	}
	s.Grant = &g
	return s.recompute(env, []reactor.Effect{s.persist()})
}

// recompute derives the phase from the stored grant and DP time, arming timers.
func (s State) recompute(env reactor.Env, effs []reactor.Effect) (State, []reactor.Effect) {
	if s.Grant == nil {
		s.Phase = PhaseNone
		return s, effs
	}
	dpNow := env.DPNow()
	if !dpNow.Before(s.Grant.ExpiresAt) {
		s.Phase = PhaseLeaseExpired
		return s, append(effs, reactor.CancelTimer{Kind: reactor.RenewDue}, reactor.CancelTimer{Kind: reactor.LeaseExpiry})
	}
	s.Phase = PhaseActive
	renewAt := s.Grant.IssuedAt.Add(s.Grant.ExpiresAt.Sub(s.Grant.IssuedAt) / 2)
	renewIn := renewAt.Sub(dpNow)
	if renewIn < 0 {
		renewIn = 0
	}
	return s, append(effs,
		reactor.ArmTimer{Kind: reactor.RenewDue, After: renewIn},
		reactor.ArmTimer{Kind: reactor.LeaseExpiry, After: s.Grant.ExpiresAt.Sub(dpNow)},
	)
}

func (s State) sendRenew(env reactor.Env) (State, []reactor.Effect) {
	if s.Pending == nil || s.Pending.Nonce != s.Grant.Nonce {
		s.Pending = &PendingRenew{MessageID: env.NewID(), Nonce: s.Grant.Nonce}
	}
	return s, []reactor.Effect{
		s.persist(),
		reactor.Send{Msg: msg.LeaseRenew{Envelope: msg.Envelope{MessageID: s.Pending.MessageID, SentAt: env.Now}, NonceConsumed: s.Pending.Nonce, AgentClock: env.Now}},
		reactor.ArmTimer{Kind: reactor.RenewDue, After: env.Jitter(renewRetry)},
	}
}
```

- [ ] **Step 4: Run the tests**

Run: `go test ./internal/authority/ ./internal/store/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add internal/authority internal/store/store.go
git commit -m "go-agent: authority reducer — lease phases, DP-time timers, same-id renewal, admission

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 14: Wire `authority` and `clock` into the loop; admin `/healthz` + `/metrics`

**Files:**
- Modify: `internal/agent/state.go`, `internal/agent/loop.go`, `internal/agent/wiring.go`, `cmd/sm-agent/main.go`
- Create: `internal/agent/admin.go`, `internal/agent/admin_test.go`, `internal/agent/state_test.go`

**Interfaces:**
- `agent.State` gains `Authority authority.State`; `Reduce` dispatches `session → authority`, feeding `SessionInputs.LeaseRemaining` from the authority state.
- `Deps` gains `Clock *clock.Clock`; the loop updates it from every `HeartbeatAck` and `LeaseGrant`/`SessionAccept.server_clock` **before** building `Env`, so the reducers see the new offset on the same event.
- `LoadState` loads `authority.Load(s, id.Thumbprint())`.
- `agent.Metrics` (Prometheus registry + counters/gauges named in spec §8.3); `func ServeAdmin(ctx, addr string, l *Loop, m *Metrics, log) error` — loopback HTTP with `/healthz` (JSON) and `/metrics`.
- `BuildConfig` gains `AdminAddr string`; `Build` starts the admin server when non-empty.

- [ ] **Step 1: Failing state test**

`internal/agent/state_test.go`:

```go
package agent

import (
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/authority"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
)

func TestReduceFeedsLeaseRemainingIntoHeartbeat(t *testing.T) {
	env := testEnv()
	g := msg.LeaseGrant{CnfThumbprint: "leaf", IssuedAt: t0.Add(-10 * time.Minute), ExpiresAt: t0.Add(50 * time.Minute), Nonce: "n"}
	st := State{
		Session:   SessionState{Phase: PhaseActive, Limits: env.Limits},
		Authority: authority.State{Phase: authority.PhaseActive, Grant: &g, LeafThumbprint: "leaf"},
	}
	_, effs := Reduce(cfg, st, reactor.TimerFired{At: t0, Kind: reactor.HeartbeatDue}, env)
	snd, ok := has[reactor.Send](effs)
	if !ok {
		t.Fatal("no heartbeat")
	}
	if hb := snd.Msg.(msg.Heartbeat); hb.LeaseRemainingAtSend != 50*time.Minute {
		t.Fatalf("lease remaining %v", hb.LeaseRemainingAtSend)
	}
}

func TestReduceSessionAcceptReachesBothReducers(t *testing.T) {
	env := testEnv()
	g := msg.LeaseGrant{CnfThumbprint: "leaf", IssuedAt: t0, ExpiresAt: t0.Add(time.Hour), Nonce: "n"}
	st := State{Session: SessionState{Phase: PhaseOpening}, Authority: authority.State{LeafThumbprint: "leaf"}}
	next, effs := Reduce(cfg, st, reactor.WireIn{At: t0, Msg: msg.SessionAccept{Lease: g, Limits: msg.Limits{HeartbeatInterval: time.Second}}}, env)
	if next.Session.Phase != PhaseActive || next.Authority.Phase != authority.PhaseActive {
		t.Fatalf("%s %s", next.Session.Phase, next.Authority.Phase)
	}
	kinds := map[reactor.TimerKind]bool{}
	for _, e := range effs {
		if a, ok := e.(reactor.ArmTimer); ok {
			kinds[a.Kind] = true
		}
	}
	if !kinds[reactor.HeartbeatDue] || !kinds[reactor.RenewDue] || !kinds[reactor.LeaseExpiry] {
		t.Fatalf("timers: %v", kinds)
	}
}
```

- [ ] **Step 2: Update state.go**

Replace `internal/agent/state.go` with:

```go
package agent

import (
	"errors"

	"github.com/solace/solution-manager/go-agent/internal/authority"
	"github.com/solace/solution-manager/go-agent/internal/identity"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// State is the composite the reducers share. Reduce dispatches in the fixed
// order session → authority (→ commands → reports in later tasks); each later
// reducer sees the earlier ones' new state.
type State struct {
	Session   SessionState
	Authority authority.State
}

func Reduce(cfg Config, s State, ev reactor.Event, env reactor.Env) (State, []reactor.Effect) {
	var all, effs []reactor.Effect
	in := SessionInputs{LeaseRemaining: s.Authority.Remaining(env.DPNow())}
	s.Session, effs = reduceSession(cfg, s.Session, ev, env, in)
	all = append(all, effs...)
	s.Authority, effs = authority.Reduce(s.Authority, ev, env)
	all = append(all, effs...)
	return s, all
}

func LoadState(cfg Config, s *store.Store, id *identity.Identity) (State, error) {
	st := State{Session: SessionState{Phase: PhaseIdle, Enrolled: id.Enrolled(), Endpoint: cfg.BootstrapEndpoint}}
	rec, err := store.LoadJSON[endpointRecord](s, "session/endpoint", 1)
	switch {
	case err == nil:
		st.Session.Endpoint = rec.Endpoint
	case errors.Is(err, store.ErrNotFound):
	default:
		return st, err
	}
	st.Authority, err = authority.Load(s, id.Thumbprint())
	if err != nil {
		return st, err
	}
	return st, nil
}
```

- [ ] **Step 3: Update the loop**

In `internal/agent/loop.go`:

1. Add `Clock *clock.Clock` to `Deps` (import `internal/clock`); in `New`, `if d.Clock == nil { d.Clock = &clock.Clock{} }` and `if d.Offset == nil { d.Offset = d.Clock.Offset }` (remove the previous zero-offset default).
2. In `Run`, immediately after `ev, ok := l.translate(raw)` and before building `env`, add:

```go
		if w, isWire := ev.(reactor.WireIn); isWire {
			switch m := w.Msg.(type) {
			case msg.HeartbeatAck:
				l.d.Clock.Update(m.ClockOffsetMs, m.ClockState)
			case msg.SessionAccept:
				if !m.ServerClock.IsZero() {
					l.d.Clock.Set(m.ServerClock, w.At)
				}
			case msg.LeaseGrant:
				if !m.ServerClock.IsZero() {
					l.d.Clock.Set(m.ServerClock, w.At)
				}
			}
		}
```

3. Add a `Halted()`-style accessor for the admin page: `func (l *Loop) Clock() *clock.Clock { return l.d.Clock }`.

- [ ] **Step 4: Admin surface**

`internal/agent/admin.go`:

```go
package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"

	"github.com/solace/solution-manager/go-agent/internal/reactor"
)

// Metrics hang off the loop's Observe hook — one increment per event and per
// effect — never inside reducers (spec §8.3).
type Metrics struct {
	reg          *prometheus.Registry
	events       *prometheus.CounterVec
	effects      *prometheus.CounterVec
	reconnects   prometheus.Counter
	leaseState   *prometheus.GaugeVec
	leaseRemain  prometheus.Gauge
	clockOffset  prometheus.Gauge
	halted       *prometheus.GaugeVec
	journalOpen  prometheus.Gauge
	reportsPend  prometheus.Gauge
	commands     *prometheus.CounterVec
}

func NewMetrics() *Metrics {
	m := &Metrics{reg: prometheus.NewRegistry()}
	m.events = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "sm_agent_events_total"}, []string{"kind"})
	m.effects = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "sm_agent_effects_total"}, []string{"kind"})
	m.reconnects = prometheus.NewCounter(prometheus.CounterOpts{Name: "sm_agent_reconnects_total"})
	m.leaseState = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "sm_agent_lease_state"}, []string{"state"})
	m.leaseRemain = prometheus.NewGauge(prometheus.GaugeOpts{Name: "sm_agent_lease_remaining_seconds"})
	m.clockOffset = prometheus.NewGauge(prometheus.GaugeOpts{Name: "sm_agent_clock_offset_ms"})
	m.halted = prometheus.NewGaugeVec(prometheus.GaugeOpts{Name: "sm_agent_halted"}, []string{"reason"})
	m.journalOpen = prometheus.NewGauge(prometheus.GaugeOpts{Name: "sm_agent_journal_open_records"})
	m.reportsPend = prometheus.NewGauge(prometheus.GaugeOpts{Name: "sm_agent_reports_pending"})
	m.commands = prometheus.NewCounterVec(prometheus.CounterOpts{Name: "sm_agent_commands_total"}, []string{"op", "outcome"})
	m.reg.MustRegister(m.events, m.effects, m.reconnects, m.leaseState, m.leaseRemain, m.clockOffset, m.halted, m.journalOpen, m.reportsPend, m.commands)
	return m
}

// kindName is the Go type name without package: "reactor.WireIn" → "WireIn".
func kindName(v any) string {
	s := fmt.Sprintf("%T", v)
	if i := strings.LastIndex(s, "."); i >= 0 {
		return s[i+1:]
	}
	return s
}

// Observe is the hook to pass as Deps.Observe.
func (m *Metrics) Observe(l *Loop) func(reactor.Event, []reactor.Effect) {
	return func(ev reactor.Event, effs []reactor.Effect) {
		m.events.WithLabelValues(kindName(ev)).Inc()
		for _, e := range effs {
			m.effects.WithLabelValues(kindName(e)).Inc()
			if d, ok := e.(reactor.Dial); ok && d.Endpoint != "" {
				m.reconnects.Inc()
			}
		}
		st := l.Snapshot()
		m.leaseState.Reset()
		m.leaseState.WithLabelValues(string(st.Authority.Phase)).Set(1)
		m.leaseRemain.Set(st.Authority.Remaining(time.Now().Add(l.Clock().Offset())).Seconds())
		m.clockOffset.Set(float64(l.Clock().Offset().Milliseconds()))
		m.halted.Reset()
		if h := l.Halted(); h != "" {
			m.halted.WithLabelValues(string(h)).Set(1)
		}
	}
}

type health struct {
	SessionPhase   string    `json:"session_phase"`
	AuthorityPhase string    `json:"authority_phase"`
	ExpiresAt      time.Time `json:"expires_at,omitempty"`
	ClockOffsetMs  int64     `json:"clock_offset_ms"`
	HaltedReason   string    `json:"halted_reason,omitempty"`
	At             time.Time `json:"at"`
}

func ServeAdmin(ctx context.Context, addr string, l *Loop, m *Metrics, log *slog.Logger) error {
	mux := http.NewServeMux()
	mux.Handle("/metrics", promhttp.HandlerFor(m.reg, promhttp.HandlerOpts{}))
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		st := l.Snapshot()
		h := health{SessionPhase: string(st.Session.Phase), AuthorityPhase: string(st.Authority.Phase),
			ClockOffsetMs: l.Clock().Offset().Milliseconds(), HaltedReason: string(l.Halted()), At: time.Now().UTC()}
		if st.Authority.Grant != nil {
			h.ExpiresAt = st.Authority.Grant.ExpiresAt
		}
		w.Header().Set("Content-Type", "application/json")
		if h.HaltedReason != "" {
			w.WriteHeader(http.StatusServiceUnavailable)
		}
		_ = json.NewEncoder(w).Encode(h)
	})
	lis, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	srv := &http.Server{Handler: mux, ReadHeaderTimeout: 5 * time.Second}
	go func() {
		<-ctx.Done()
		_ = srv.Close()
	}()
	log.Info("admin listening", "addr", lis.Addr().String())
	err = srv.Serve(lis)
	if err == http.ErrServerClosed {
		return nil
	}
	return err
}
```

`internal/agent/admin_test.go`:

```go
package agent

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/testca"
)

func TestAdminHealthAndMetrics(t *testing.T) {
	ca, _ := testca.New()
	w := &fakeWire{ca: ca}
	l, _ := newTestLoop(t, w)
	m := NewMetrics()
	l.d.Observe = m.Observe(l)
	lis, _ := net.Listen("tcp", "127.0.0.1:0")
	addr := lis.Addr().String()
	lis.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go func() { _ = ServeAdmin(ctx, addr, l, m, slog.New(slog.NewTextHandler(io.Discard, nil))) }()
	go func() { _ = l.Run(ctx) }()
	c := w.conn(0)
	expect[msg.SessionOpen](t, c)

	time.Sleep(50 * time.Millisecond)
	resp, err := http.Get("http://" + addr + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	var h health
	_ = json.NewDecoder(resp.Body).Decode(&h)
	if h.SessionPhase != string(PhaseOpening) {
		t.Fatalf("%+v", h)
	}
	resp2, err := http.Get("http://" + addr + "/metrics")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp2.Body)
	if !strings.Contains(string(body), "sm_agent_events_total") {
		t.Fatal("metrics missing")
	}
}
```

- [ ] **Step 5: Wiring and main**

In `internal/agent/wiring.go`, add `AdminAddr string` to `BuildConfig`; in `Build`, after `New(...)`:

```go
	if bc.AdminAddr != "" {
		m := NewMetrics()
		l.d.Observe = m.Observe(l)
		go func() {
			if err := ServeAdmin(context.Background(), bc.AdminAddr, l, m, bc.Log); err != nil {
				bc.Log.Error("admin server", "err", err)
			}
		}()
	}
```

In `cmd/sm-agent/main.go`, add `adminAddr := flag.String("admin-addr", "127.0.0.1:9464", "loopback /healthz and /metrics listener; empty disables")` and pass `AdminAddr: *adminAddr`.

- [ ] **Step 6: Run everything**

Run: `go build ./... && go test -race ./internal/agent/ ./internal/authority/`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add internal/agent cmd/sm-agent
git commit -m "go-agent: wire authority and clock into the loop; loopback healthz/metrics

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 15: P2 integration scenarios

**Files:**
- Modify: `internal/agent/integration_test.go`

- [ ] **Step 1: Add the scenarios**

Append to `internal/agent/integration_test.go`:

```go
func TestIntegrationRenewAtHalfLifeAndSameIDOnResend(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Lease.Duration = 2 * time.Second // renew due at 1s
	sc.Policy.Renew = "silent"          // first renew unanswered → agent resends the same id
	sc.Steps = []fakegw.Step{
		{Expect: &fakegw.ExpectStep{Kind: "LeaseRenew", Timeout: 5 * time.Second}},
		{RenewPolicy: strPtr("grant")},
	}
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("LeaseRenew", 2, 40*time.Second) {
		t.Fatal("expected a resend of the renewal")
	}
	renews := gw.Received("LeaseRenew")
	first := renews[0].(msg.LeaseRenew)
	for _, r := range renews[1:] {
		if r.(msg.LeaseRenew).MessageID != first.MessageID {
			t.Fatal("resends must reuse the message_id")
		}
	}
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		st := h.loop.Snapshot()
		if st.Authority.Grant != nil && st.Authority.Grant.Nonce != "" && st.Authority.Pending == nil {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("grant never landed")
}

func TestIntegrationExpiredLeaseReconnectRenewsBeforeAccept(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Policy.OnOpen = "renew-required" // every reconnect takes the §4.6 case-3 path
	sc.Steps = []fakegw.Step{{Sleep: durPtr(300 * time.Millisecond)}, {Disconnect: boolPtr(true)}}
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	// after the scripted disconnect the agent reconnects (≈1 s backoff)
	if !gw.WaitFor("SessionOpen", 2, 15*time.Second) || !gw.WaitFor("LeaseRenew", 1, 15*time.Second) {
		t.Fatal("reconnect must renew under the nonce before SessionAccept")
	}
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		if h.loop.Snapshot().Session.Phase == PhaseActive && gw.Sessions() >= 2 {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("not active after renew: %s", h.loop.Snapshot().Session.Phase)
}

func TestIntegrationRenewRefusedDegradesThenExpires(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Lease.Duration = 2 * time.Second
	sc.Policy.Renew = "refuse:LEASE_RENEW_REFUSED"
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	waitAuthority(t, h.loop, authority.PhaseRenewDegraded, 10*time.Second)
	waitAuthority(t, h.loop, authority.PhaseLeaseExpired, 10*time.Second)
	if h.loop.Snapshot().Session.Phase != PhaseActive {
		t.Fatal("the session stays up in LEASE_EXPIRED (report-only); only authority changes")
	}
}

func TestIntegrationRevokeHalts(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{{Revoke: strPtr("compromised")}}
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	waitHalt(t, h.loop, msg.ErrIdentityRevoked, 10*time.Second)
	if h.loop.Snapshot().Authority.Grant != nil {
		t.Fatal("revoked agent must drop its lease")
	}
	time.Sleep(1500 * time.Millisecond)
	if gw.Sessions() != 1 {
		t.Fatal("revoked agent must not reconnect")
	}
}

func TestIntegrationSupersededReconnectsImmediately(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{{Error: &fakegw.ErrorStep{Code: "SESSION_SUPERSEDED"}}}
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	start := time.Now()
	if !gw.WaitFor("SessionOpen", 2, 5*time.Second) {
		t.Fatal("no immediate reconnect")
	}
	if time.Since(start) > 3*time.Second {
		t.Fatal("reconnect after SESSION_SUPERSEDED must not wait for backoff")
	}
	_ = h
}

func TestIntegrationDrainHonoursReconnectAfter(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{{Drain: durPtr(2 * time.Second)}}
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	if !gw.WaitFor("SessionOpen", 1, 5*time.Second) {
		t.Fatal("no session")
	}
	firstOpen := time.Now()
	if !gw.WaitFor("SessionOpen", 2, 10*time.Second) {
		t.Fatal("no reconnect after drain")
	}
	if elapsed := time.Since(firstOpen); elapsed < 1500*time.Millisecond {
		t.Fatalf("reconnected too early after Drain: %v", elapsed)
	}
	_ = h
}

func TestIntegrationClockSuspectFailsClosedThenRecovers(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Limits.HeartbeatInterval = 200 * time.Millisecond
	sc.Policy.Heartbeat = fakegw.HeartbeatPolicy{State: "SUSPECT"}
	sc.Steps = []fakegw.Step{
		{Expect: &fakegw.ExpectStep{Kind: "Heartbeat", Timeout: 5 * time.Second}},
		{Sleep: durPtr(600 * time.Millisecond)},
		{HeartbeatPolicy: &fakegw.HeartbeatPolicy{State: "OK"}},
	}
	gw := startGateway(t, sc)
	h := startAgent(t, gw, "", nil)
	waitAuthority(t, h.loop, authority.PhaseClockSuspect, 10*time.Second)
	waitAuthority(t, h.loop, authority.PhaseActive, 10*time.Second)
}

func strPtr(s string) *string              { return &s }
func boolPtr(b bool) *bool                 { return &b }
func durPtr(d time.Duration) *time.Duration { return &d }

func waitAuthority(t *testing.T, l *Loop, want authority.Phase, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if l.Snapshot().Authority.Phase == want {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("authority %s not reached (at %s)", want, l.Snapshot().Authority.Phase)
}
```

Add `"github.com/solace/solution-manager/go-agent/internal/authority"` to the file's imports.

- [ ] **Step 2: Run**

Run: `go test -race -tags integration ./internal/agent/ -run Integration -v`
Expected: all PASS. If `TestIntegrationClockSuspectFailsClosedThenRecovers` flakes, the `Sleep` step is shorter than three heartbeat intervals — raise it, never lower the assertion.

- [ ] **Step 3: Commit**

```bash
git add internal/agent
git commit -m "go-agent: P2 integration — renew same-id, expired-lease reconnect, refusal, revoke, supersede, drain, clock

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

**Phase P2 exit:** every lease path in spec §5 has a passing scenario; `/healthz` reports phases.

---

## Phase P3 — commands, journal, Docker runtime

### Task 16: Runtime contract and the config writer

**Files:**
- Create: `internal/runtime/runtime.go`, `internal/runtime/configwriter/writer.go`, `internal/runtime/configwriter/writer_test.go`
- Modify: `internal/reactor/reactor.go` (add `OpProgress` event; narrow `OpFinished.Result` to `runtime.OpResult`)

**Interfaces:**
- Produces (`internal/runtime`):

```go
type Placement struct {
	BindingID, ContainerID, Name  string // Name is the replica_ref
	ConfigPathInContainer, HostPath string
	Mechanism string // "additional-location" | "overwrite-in-place"
	UID, GID  int    // -1 when unknown
}
type ReplicaFacts struct {
	ReplicaRef, Incarnation string
	Running   bool
	StartedAt time.Time
	ExitCode  int
	ImageDigest string
}
type Progress func(step msg.ProgressStep) error // synchronous; journals before the writer proceeds
type WriteResult struct{ Hash string }
type Adapter interface {
	Resolve(ctx context.Context, bindingID string) (Placement, error)
	Preflight(ctx context.Context, p Placement) (msg.PreflightResult, error)
	WriteConfig(ctx context.Context, p Placement, payload []byte, sha256 string, previousPath string, progress Progress) (WriteResult, error)
	Restart(ctx context.Context, p Placement) error
	Stop(ctx context.Context, p Placement) error
	Observe(ctx context.Context, p Placement) (ReplicaFacts, error)
}
type OpResult struct {
	Outcome       msg.Outcome
	Detail        msg.AckDetail
	Facts         *ReplicaFacts
	EffectiveHash string
	Placement     *Placement
}
```

- `configwriter.Write(hostPath string, payload []byte, wantSHA, previousPath string, uid, gid int, progress runtime.Progress) (runtime.WriteResult, error)`; errors are `*configwriter.WriteError{Phase, Err}` with `Phase ∈ before-rename | after-rename-rolled-back | after-rename-unknown`; `ErrSymlinkEscape`; `configwriter.SHA256(b []byte) string`.
- `reactor.OpProgress{At, CommandID string; Step msg.ProgressStep}`.

- [ ] **Step 1: Reactor additions**

In `internal/reactor/reactor.go`: add

```go
// OpProgress is pushed by the runtime worker when the adapter reaches a
// journal step mid-operation (the step is already on disk when this fires).
type OpProgress struct {
	At        time.Time
	CommandID string
	Step      msg.ProgressStep
}

func (e OpProgress) EventAt() time.Time { return e.At }
```

and change `OpFinished.Result any` to `Result runtime.OpResult` (import `internal/runtime`; `runtime` imports only `msg`, so there is no cycle).

- [ ] **Step 2: Runtime types**

`internal/runtime/runtime.go`:

```go
// Package runtime is the seam between the command reducer and the host: the
// agent decides what to do, an Adapter does it to a container.
package runtime

import (
	"context"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

type Placement struct {
	BindingID             string
	ContainerID           string
	Name                  string // replica_ref (RFC 1123 hostname grammar)
	ConfigPathInContainer string
	HostPath              string
	Mechanism             string
	UID, GID              int
}

type ReplicaFacts struct {
	ReplicaRef  string
	Incarnation string // container ID
	Running     bool
	StartedAt   time.Time
	ExitCode    int
	ImageDigest string
}

type Progress func(step msg.ProgressStep) error

type WriteResult struct{ Hash string }

type Adapter interface {
	Resolve(ctx context.Context, bindingID string) (Placement, error)
	Preflight(ctx context.Context, p Placement) (msg.PreflightResult, error)
	WriteConfig(ctx context.Context, p Placement, payload []byte, sha256 string, previousPath string, progress Progress) (WriteResult, error)
	Restart(ctx context.Context, p Placement) error
	Stop(ctx context.Context, p Placement) error
	Observe(ctx context.Context, p Placement) (ReplicaFacts, error)
}

type OpResult struct {
	Outcome       msg.Outcome
	Detail        msg.AckDetail
	Facts         *ReplicaFacts
	EffectiveHash string
	Placement     *Placement
}
```

- [ ] **Step 3: Failing writer tests**

`internal/runtime/configwriter/writer_test.go`:

```go
package configwriter

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func TestWriteHappyPathJournalsStagedThenPersisted(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "application.yml")
	_ = os.WriteFile(target, []byte("old"), 0o644)
	prev := filepath.Join(t.TempDir(), "previous")
	var steps []msg.ProgressStep
	res, err := Write(target, []byte("new"), SHA256([]byte("new")), prev, -1, -1, func(s msg.ProgressStep) error { steps = append(steps, s); return nil })
	if err != nil {
		t.Fatal(err)
	}
	if res.Hash != SHA256([]byte("new")) {
		t.Fatal("hash")
	}
	if len(steps) != 2 || steps[0] != msg.StepStaged || steps[1] != msg.StepPersisted {
		t.Fatalf("steps %v", steps)
	}
	got, _ := os.ReadFile(target)
	if string(got) != "new" {
		t.Fatal("content")
	}
	st, _ := os.Stat(target)
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("mode %o", st.Mode().Perm())
	}
	p, _ := os.ReadFile(prev)
	if string(p) != "old" {
		t.Fatal("previous copy must hold the old content")
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 1 {
		t.Fatalf("temp left behind: %v", entries)
	}
}

func TestWriteRefusesSymlinkEscape(t *testing.T) {
	dir := t.TempDir()
	outside := filepath.Join(t.TempDir(), "victim")
	_ = os.WriteFile(outside, []byte("x"), 0o644)
	link := filepath.Join(dir, "application.yml")
	if err := os.Symlink(outside, link); err != nil {
		t.Skip("symlinks unsupported")
	}
	_, err := Write(link, []byte("new"), SHA256([]byte("new")), filepath.Join(dir, "prev"), -1, -1, func(msg.ProgressStep) error { return nil })
	if !errors.Is(err, ErrSymlinkEscape) {
		t.Fatalf("want ErrSymlinkEscape, got %v", err)
	}
	v, _ := os.ReadFile(outside)
	if string(v) != "x" {
		t.Fatal("victim was overwritten")
	}
}

func TestWriteHashMismatchFailsBeforeRename(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "a.yml")
	_ = os.WriteFile(target, []byte("old"), 0o644)
	_, err := Write(target, []byte("new"), "0000", filepath.Join(dir, "prev"), -1, -1, func(msg.ProgressStep) error { return nil })
	var we *WriteError
	if !errors.As(err, &we) || we.Phase != PhaseBeforeRename {
		t.Fatalf("want before-rename failure, got %v", err)
	}
	got, _ := os.ReadFile(target)
	if string(got) != "old" {
		t.Fatal("target must be untouched")
	}
}

func TestWriteIsIdempotentWhenTargetAlreadyMatches(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "a.yml")
	_ = os.WriteFile(target, []byte("new"), 0o600)
	var steps []msg.ProgressStep
	_, err := Write(target, []byte("new"), SHA256([]byte("new")), filepath.Join(dir, "prev"), -1, -1, func(s msg.ProgressStep) error { steps = append(steps, s); return nil })
	if err != nil {
		t.Fatal(err)
	}
	if len(steps) != 1 || steps[0] != msg.StepPersisted {
		t.Fatalf("a matching target is reported PERSISTED without rewriting: %v", steps)
	}
}

func TestWriteProgressErrorAbortsBeforeTouchingTarget(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "a.yml")
	_ = os.WriteFile(target, []byte("old"), 0o644)
	_, err := Write(target, []byte("new"), SHA256([]byte("new")), filepath.Join(dir, "prev"), -1, -1, func(msg.ProgressStep) error { return errors.New("journal down") })
	var we *WriteError
	if !errors.As(err, &we) || we.Phase != PhaseBeforeRename {
		t.Fatalf("%v", err)
	}
	got, _ := os.ReadFile(target)
	if string(got) != "old" {
		t.Fatal("target must be untouched when the journal cannot record STAGED")
	}
}
```

- [ ] **Step 4: Implement the writer**

`internal/runtime/configwriter/writer.go`:

```go
// Package configwriter writes a rendered configuration file the way baseline
// go-agent design §5.1 requires: temp in the same directory → fsync → chmod
// 0600 → rename → fsync parent, refusing symlink escapes, keeping the
// previous content for rollback, and verifying by read-back.
package configwriter

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
)

var ErrSymlinkEscape = errors.New("configwriter: target escapes its declared directory via symlink")

const (
	PhaseBeforeRename          = "before-rename"
	PhaseAfterRenameRolledBack = "after-rename-rolled-back"
	PhaseAfterRenameUnknown    = "after-rename-unknown"
)

type WriteError struct {
	Phase string
	Err   error
}

func (e *WriteError) Error() string { return fmt.Sprintf("configwriter: %s: %v", e.Phase, e.Err) }
func (e *WriteError) Unwrap() error { return e.Err }

func SHA256(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

func before(err error) error { return &WriteError{Phase: PhaseBeforeRename, Err: err} }

// checkPath canonicalises the declared directory and refuses a target whose
// resolved location is outside it.
func checkPath(hostPath string) (dir, base string, err error) {
	if !filepath.IsAbs(hostPath) {
		return "", "", fmt.Errorf("configwriter: host path must be absolute: %q", hostPath)
	}
	hostPath = filepath.Clean(hostPath)
	dir, base = filepath.Dir(hostPath), filepath.Base(hostPath)
	realDir, err := filepath.EvalSymlinks(dir)
	if err != nil {
		return "", "", err
	}
	if fi, err := os.Lstat(hostPath); err == nil && fi.Mode()&os.ModeSymlink != 0 {
		return "", "", ErrSymlinkEscape
	}
	if resolved, err := filepath.EvalSymlinks(hostPath); err == nil {
		if filepath.Dir(resolved) != realDir {
			return "", "", ErrSymlinkEscape
		}
	}
	return realDir, base, nil
}

func Write(hostPath string, payload []byte, wantSHA, previousPath string, uid, gid int, progress runtime.Progress) (runtime.WriteResult, error) {
	if SHA256(payload) != wantSHA {
		return runtime.WriteResult{}, before(fmt.Errorf("payload hash %s != declared %s", SHA256(payload), wantSHA))
	}
	dir, base, err := checkPath(hostPath)
	if err != nil {
		return runtime.WriteResult{}, before(err)
	}
	target := filepath.Join(dir, base)

	// Idempotent: a target that already holds the bytes is PERSISTED, never rewritten.
	if cur, err := os.ReadFile(target); err == nil && SHA256(cur) == wantSHA {
		if err := progress(msg.StepPersisted); err != nil {
			return runtime.WriteResult{}, before(err)
		}
		return runtime.WriteResult{Hash: wantSHA}, nil
	}

	// Retain the previous content for FAILED_ROLLED_BACK.
	prev, prevErr := os.ReadFile(target)
	hadPrev := prevErr == nil
	if hadPrev {
		if err := os.MkdirAll(filepath.Dir(previousPath), 0o700); err != nil {
			return runtime.WriteResult{}, before(err)
		}
		if err := os.WriteFile(previousPath, prev, 0o600); err != nil {
			return runtime.WriteResult{}, before(err)
		}
	}

	// STAGED is journaled before the temp file exists (write-before-act).
	if err := progress(msg.StepStaged); err != nil {
		return runtime.WriteResult{}, before(err)
	}
	tmp, err := os.CreateTemp(dir, "."+base+".*.sm-tmp")
	if err != nil {
		return runtime.WriteResult{}, before(err)
	}
	tmpPath := tmp.Name()
	fail := func(err error) (runtime.WriteResult, error) {
		_ = tmp.Close()
		_ = os.Remove(tmpPath)
		return runtime.WriteResult{}, before(err)
	}
	if _, err := tmp.Write(payload); err != nil {
		return fail(err)
	}
	if err := tmp.Sync(); err != nil {
		return fail(err)
	}
	if err := tmp.Chmod(0o600); err != nil {
		return fail(err)
	}
	if uid >= 0 && gid >= 0 {
		if err := tmp.Chown(uid, gid); err != nil {
			return fail(err)
		}
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmpPath)
		return runtime.WriteResult{}, before(err)
	}
	if err := os.Rename(tmpPath, target); err != nil {
		_ = os.Remove(tmpPath)
		return runtime.WriteResult{}, before(err)
	}

	// After the rename the new content is visible; any failure from here is
	// rolled back from the retained copy.
	rollback := func(cause error) (runtime.WriteResult, error) {
		if !hadPrev {
			if rmErr := os.Remove(target); rmErr != nil {
				return runtime.WriteResult{}, &WriteError{Phase: PhaseAfterRenameUnknown, Err: errors.Join(cause, rmErr)}
			}
			return runtime.WriteResult{}, &WriteError{Phase: PhaseAfterRenameRolledBack, Err: cause}
		}
		if wErr := os.WriteFile(target, prev, 0o600); wErr != nil {
			return runtime.WriteResult{}, &WriteError{Phase: PhaseAfterRenameUnknown, Err: errors.Join(cause, wErr)}
		}
		return runtime.WriteResult{}, &WriteError{Phase: PhaseAfterRenameRolledBack, Err: cause}
	}
	if err := syncDir(dir); err != nil {
		return rollback(err)
	}
	back, err := os.ReadFile(target)
	if err != nil {
		return rollback(err)
	}
	if !bytes.Equal(back, payload) {
		return rollback(errors.New("read-back differs from payload"))
	}
	if err := progress(msg.StepPersisted); err != nil {
		// The file is durably in place; a journal failure here is reported, not rolled back.
		return runtime.WriteResult{Hash: wantSHA}, &WriteError{Phase: PhaseAfterRenameUnknown, Err: err}
	}
	return runtime.WriteResult{Hash: wantSHA}, nil
}

func syncDir(dir string) error {
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}
```

- [ ] **Step 5: Run**

Run: `go build ./... && go test ./internal/runtime/... ./internal/reactor/`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add internal/runtime internal/reactor
git commit -m "go-agent: runtime adapter contract and atomic config writer with symlink refusal and rollback

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 17: The apply journal (`AG-D4`)

**Files:**
- Create: `internal/commands/journal.go`, `internal/commands/journal_test.go`

**Interfaces:**
- Produces:

```go
type Record struct {
	SchemaVersion       int              `json:"schema_version"` // 1
	RolloutGeneration   uint64           `json:"rollout_generation"`
	IdempotencyKey      string           `json:"idempotency_key"`
	CommandID           string           `json:"command_id"`
	BindingID           string           `json:"binding_id"`
	Operation           msg.Operation    `json:"operation"`
	TargetPath          string           `json:"target_path"`
	ConfigHash          string           `json:"config_hash"`
	Step                msg.ProgressStep `json:"step"`
	StepDeadline        time.Time        `json:"step_deadline"`
	TempPath            string           `json:"temp_path,omitempty"`
	Outcome             msg.Outcome      `json:"outcome,omitempty"`
	Detail              msg.AckDetail    `json:"detail,omitempty"`
	ReceivedAck         bool             `json:"received_ack"` // CommandReceived has been sent
	AckSent             bool             `json:"ack_sent"`     // terminal CommandAck has been sent
	ReplicaRef          string           `json:"replica_ref,omitempty"`
	EffectiveHash       string           `json:"effective_hash,omitempty"`
}
func (r Record) Version() int
func (r Record) Rel() string                 // apply/<binding>/<generation>.json
func (r Record) Terminal() bool              // Step == REPORTED
func PayloadRel(binding string, gen uint64) string   // apply/<binding>/<gen>/payload
func PreviousRel(binding string, gen uint64) string  // apply/<binding>/<gen>/previous
func DirRel(binding string, gen uint64) string       // apply/<binding>/<gen>
type Journal struct{ Records map[string]Record /* key: binding|gen */; Watermark map[string]uint64 }
func LoadJournal(s *store.Store) (Journal, error)
func (j Journal) Get(binding string, gen uint64) (Record, bool)
func (j Journal) ByCommand(commandID string) (Record, bool)
func (j *Journal) Put(r Record)
func (j Journal) OpenCount() int
func (j Journal) SupersededPartials(binding string, belowGen uint64) []string // dir rels to delete
```

- [ ] **Step 1: Failing tests**

`internal/commands/journal_test.go`:

```go
package commands

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

func rec(binding string, gen uint64, step msg.ProgressStep) Record {
	return Record{SchemaVersion: 1, RolloutGeneration: gen, IdempotencyKey: "k", CommandID: "c", BindingID: binding,
		Operation: msg.OpApply, TargetPath: "/etc/app/application.yml", ConfigHash: "h", Step: step, StepDeadline: time.Now().Add(time.Minute)}
}

func TestRecordPaths(t *testing.T) {
	r := rec("b1", 7, msg.StepReceived)
	if r.Rel() != "apply/b1/7.json" || PayloadRel("b1", 7) != "apply/b1/7/payload" || PreviousRel("b1", 7) != "apply/b1/7/previous" {
		t.Fatalf("%s %s %s", r.Rel(), PayloadRel("b1", 7), PreviousRel("b1", 7))
	}
}

func TestLoadJournalWatermarkAndLookup(t *testing.T) {
	s, _ := store.Open(filepath.Join(t.TempDir(), "d"))
	for _, r := range []Record{rec("b1", 1, msg.StepReported), rec("b1", 3, msg.StepStaged), rec("b2", 9, msg.StepReported)} {
		r2 := r
		r2.CommandID = "cmd-" + r.BindingID + "-" + time.Duration(r.RolloutGeneration).String()
		if err := s.Save(store.JSON{RelPath: r2.Rel(), Value: r2}); err != nil {
			t.Fatal(err)
		}
	}
	j, err := LoadJournal(s)
	if err != nil {
		t.Fatal(err)
	}
	if j.Watermark["b1"] != 3 || j.Watermark["b2"] != 9 {
		t.Fatalf("watermarks %v", j.Watermark)
	}
	if _, ok := j.Get("b1", 3); !ok {
		t.Fatal("lookup by binding/gen")
	}
	if r, ok := j.ByCommand("cmd-b1-3ns"); !ok || r.RolloutGeneration != 3 {
		t.Fatalf("lookup by command id: %v %+v", ok, r)
	}
	if j.OpenCount() != 1 {
		t.Fatalf("open %d", j.OpenCount())
	}
}

func TestSupersededPartials(t *testing.T) {
	j := Journal{Records: map[string]Record{}, Watermark: map[string]uint64{}}
	j.Put(rec("b1", 1, msg.StepReported))
	j.Put(rec("b1", 2, msg.StepStaged))
	j.Put(rec("b1", 5, msg.StepPersisted))
	got := j.SupersededPartials("b1", 5)
	if len(got) != 2 {
		t.Fatalf("%v", got)
	}
	for _, g := range got {
		if g == DirRel("b1", 5) {
			t.Fatal("the current generation must never be swept")
		}
	}
}

func TestSchemaVersionMismatchIsFatal(t *testing.T) {
	s, _ := store.Open(filepath.Join(t.TempDir(), "d"))
	bad := rec("b1", 1, msg.StepReported)
	bad.SchemaVersion = 99
	_ = s.Save(store.JSON{RelPath: bad.Rel(), Value: bad})
	if _, err := LoadJournal(s); err == nil {
		t.Fatal("an unknown record version must refuse to load, not be migrated")
	}
}
```

- [ ] **Step 2: Implement**

`internal/commands/journal.go`:

```go
// Package commands owns the apply journal (AG-D4) and the command reducer.
package commands

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

const schemaVersion = 1

type Record struct {
	SchemaVersion     int              `json:"schema_version"`
	RolloutGeneration uint64           `json:"rollout_generation"`
	IdempotencyKey    string           `json:"idempotency_key"`
	CommandID         string           `json:"command_id"`
	BindingID         string           `json:"binding_id"`
	Operation         msg.Operation    `json:"operation"`
	TargetPath        string           `json:"target_path"`
	ConfigHash        string           `json:"config_hash"`
	Step              msg.ProgressStep `json:"step"`
	StepDeadline      time.Time        `json:"step_deadline"`
	TempPath          string           `json:"temp_path,omitempty"`
	Outcome           msg.Outcome      `json:"outcome,omitempty"`
	Detail            msg.AckDetail    `json:"detail,omitempty"`
	ReceivedAck       bool             `json:"received_ack"`
	AckSent           bool             `json:"ack_sent"`
	ReplicaRef        string           `json:"replica_ref,omitempty"`
	EffectiveHash     string           `json:"effective_hash,omitempty"`
}

func (r Record) Version() int   { return r.SchemaVersion }
func (r Record) Terminal() bool { return r.Step == msg.StepReported }

func DirRel(binding string, gen uint64) string {
	return fmt.Sprintf("apply/%s/%d", binding, gen)
}
func PayloadRel(binding string, gen uint64) string  { return DirRel(binding, gen) + "/payload" }
func PreviousRel(binding string, gen uint64) string { return DirRel(binding, gen) + "/previous" }

func (r Record) Rel() string { return fmt.Sprintf("apply/%s/%d.json", r.BindingID, r.RolloutGeneration) }

func key(binding string, gen uint64) string { return binding + "|" + strconv.FormatUint(gen, 10) }

type Journal struct {
	Records   map[string]Record
	Watermark map[string]uint64 // highest durably recorded generation per binding
}

func LoadJournal(s *store.Store) (Journal, error) {
	j := Journal{Records: map[string]Record{}, Watermark: map[string]uint64{}}
	root := s.Path("apply")
	entries, err := os.ReadDir(root)
	if os.IsNotExist(err) {
		return j, nil
	}
	if err != nil {
		return j, err
	}
	for _, binding := range entries {
		if !binding.IsDir() {
			continue
		}
		files, err := os.ReadDir(filepath.Join(root, binding.Name()))
		if err != nil {
			return j, err
		}
		for _, f := range files {
			if f.IsDir() || !strings.HasSuffix(f.Name(), ".json") {
				continue
			}
			rel := "apply/" + binding.Name() + "/" + f.Name()
			r, err := store.LoadJSON[Record](s, rel, schemaVersion)
			if err != nil {
				return j, fmt.Errorf("journal %s: %w", rel, err)
			}
			j.Put(r)
		}
	}
	return j, nil
}

func (j *Journal) Put(r Record) {
	j.Records[key(r.BindingID, r.RolloutGeneration)] = r
	if r.RolloutGeneration > j.Watermark[r.BindingID] {
		j.Watermark[r.BindingID] = r.RolloutGeneration
	}
}

func (j Journal) Get(binding string, gen uint64) (Record, bool) {
	r, ok := j.Records[key(binding, gen)]
	return r, ok
}

func (j Journal) ByCommand(commandID string) (Record, bool) {
	for _, r := range j.Records {
		if r.CommandID == commandID {
			return r, true
		}
	}
	return Record{}, false
}

func (j Journal) OpenCount() int {
	n := 0
	for _, r := range j.Records {
		if !r.Terminal() {
			n++
		}
	}
	return n
}

// SupersededPartials lists the per-generation directories below belowGen for
// a binding; their payload/previous/temp files are a disk leak and a future
// resume's ambiguous input (AG-D4 §5.2).
func (j Journal) SupersededPartials(binding string, belowGen uint64) []string {
	var out []string
	for _, r := range j.Records {
		if r.BindingID == binding && r.RolloutGeneration < belowGen {
			out = append(out, DirRel(r.BindingID, r.RolloutGeneration))
		}
	}
	return out
}
```

`store.Delete` removes a file; sweeping a directory needs a recursive delete. Add to `internal/store/store.go`:

```go
// DeleteAll removes a directory and its contents, then fsyncs the parent.
func (s *Store) DeleteAll(rel string) error {
	if err := os.RemoveAll(s.Path(rel)); err != nil {
		return err
	}
	return syncDir(filepath.Dir(s.Path(rel)))
}
```

and in the loop's effect executor make `reactor.Delete` use it when the path has no extension:

```go
		case reactor.Delete:
			del := l.d.Store.Delete
			if filepath.Ext(e.Rel) == "" {
				del = l.d.Store.DeleteAll
			}
			if err := del(e.Rel); err != nil {
				return fmt.Errorf("%w: delete %s: %v", ErrFatalPersist, e.Rel, err)
			}
```

(add `"path/filepath"` to `loop.go`'s imports).

- [ ] **Step 3: Run**

Run: `go test ./internal/commands/ ./internal/store/ && go build ./...`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add internal/commands internal/store internal/agent/loop.go
git commit -m "go-agent: apply journal records, watermark, superseded-partial sweep

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 18: The `commands` reducer

**Files:**
- Create: `internal/commands/commands.go`, `internal/commands/commands_test.go`

**Interfaces:**
- Produces:
  - `commands.State{Journal Journal; InFlight string /* command id */}`
  - `func Load(s *store.Store) (State, error)`
  - `func Reduce(s State, ev reactor.Event, env reactor.Env, auth Authority) (State, []reactor.Effect)` where `type Authority interface { RefusalDetail(msg.Operation, string, time.Time) msg.DetailCode; StepDeadline(msg.Operation, time.Time) time.Time; Active() bool }` — `authority.State` satisfies it, and `commands` does not import `authority`.
  - `func (s State) ResumeEffects(env reactor.Env) []reactor.Effect` — invoked from `Reduce` on `reactor.Resumed`.
  - `func (s State) PersistedGeneration(binding string) uint64`, `func (s State) OpenCount() int` — read by `reports` and the heartbeat.

- [ ] **Step 1: Failing tests**

`internal/commands/commands_test.go`:

```go
package commands

import (
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

var t0 = time.Date(2026, 9, 22, 10, 0, 0, 0, time.UTC)

type auth struct {
	detail   msg.DetailCode
	deadline time.Time
	active   bool
}

func (a auth) RefusalDetail(msg.Operation, string, time.Time) msg.DetailCode { return a.detail }
func (a auth) StepDeadline(_ msg.Operation, start time.Time) time.Time {
	if a.deadline.IsZero() {
		return start.Add(2 * time.Minute)
	}
	return a.deadline
}
func (a auth) Active() bool { return a.active }

var okAuth = auth{detail: msg.DetailNone, active: true}

func env(now time.Time) reactor.Env {
	n := 0
	return reactor.Env{Now: now, NewID: func() string { n++; return "mid-" + time.Duration(n).String() },
		Jitter: func(d time.Duration) time.Duration { return d }, Limits: msg.Limits{MaxMessageBytes: 1024}}
}

func newState() State { return State{Journal: Journal{Records: map[string]Record{}, Watermark: map[string]uint64{}}} }

func has[T reactor.Effect](effs []reactor.Effect) (T, bool) {
	for _, e := range effs {
		if v, ok := e.(T); ok {
			return v, true
		}
	}
	var zero T
	return zero, false
}

func sent[M msg.Outbound](t *testing.T, effs []reactor.Effect) M {
	t.Helper()
	for _, e := range effs {
		if s, ok := e.(reactor.Send); ok {
			if m, ok := s.Msg.(M); ok {
				return m
			}
		}
	}
	t.Fatalf("no %T sent in %+v", *new(M), effs)
	panic("unreachable")
}

func offer(gen uint64, payload string, op msg.Operation) msg.CommandOffer {
	return msg.CommandOffer{CommandID: "c1", Generation: gen, IdempotencyKey: "k1", BindingID: "b1", Operation: op,
		Payload: []byte(payload), ConfigSHA256: sha(payload), Deadline: t0.Add(5 * time.Minute)}
}

func sha(s string) string { return sha256Hex([]byte(s)) }

func TestNoticePullsWhenAdmissible(t *testing.T) {
	s, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: msg.GenerationAvailable{CommandID: "c1", BindingID: "b1", Generation: 1, Operation: msg.OpApply}}, env(t0), okAuth)
	p := sent[msg.CommandPull](t, effs)
	if p.CommandID != "c1" {
		t.Fatal(p)
	}
	_ = s
}

func TestNoticeRefusedByLocalGate(t *testing.T) {
	a := auth{detail: msg.DetailOf(msg.ErrCapabilityMissing), active: true}
	_, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: msg.GenerationAvailable{CommandID: "c1", BindingID: "b1", Generation: 1, Operation: msg.OpRestart}}, env(t0), a)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeRefused || ack.Detail.Code != msg.DetailOf(msg.ErrCapabilityMissing) {
		t.Fatalf("%+v", ack)
	}
	if _, ok := has[reactor.RunOp](effs); ok {
		t.Fatal("a refused notice must not run anything")
	}
}

func TestNoticeBelowWatermarkRefuses(t *testing.T) {
	s := newState()
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 5, Step: msg.StepReported})
	_, effs := Reduce(s, reactor.WireIn{At: t0, Msg: msg.GenerationAvailable{CommandID: "c9", BindingID: "b1", Generation: 4, Operation: msg.OpApply}}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeRefused || ack.Detail.Code != msg.DetailOf(msg.ErrStaleGeneration) {
		t.Fatalf("%+v", ack)
	}
}

func TestOfferJournalsReceivedBeforeAckAndRuns(t *testing.T) {
	s, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: offer(1, "server.port: 1\n", msg.OpApply)}, env(t0), okAuth)
	// effect order is enforced by the loop, but Persist must be present with
	// the payload blob and the RECEIVED record.
	var persists []string
	for _, e := range effs {
		if p, ok := e.(reactor.Persist); ok {
			persists = append(persists, p.Rec.Rel())
		}
	}
	if len(persists) != 2 || persists[0] != PayloadRel("b1", 1) || persists[1] != "apply/b1/1.json" {
		t.Fatalf("payload then record: %v", persists)
	}
	if r := sent[msg.CommandReceived](t, effs); r.CommandID != "c1" {
		t.Fatal(r)
	}
	pr := sent[msg.ApplyProgress](t, effs)
	if pr.Step != msg.StepReceived {
		t.Fatal(pr)
	}
	op, ok := has[reactor.RunOp](effs)
	if !ok || op.PayloadRel != PayloadRel("b1", 1) || op.SHA256 != sha("server.port: 1\n") || !op.Deadline.Equal(t0.Add(2*time.Minute)) {
		t.Fatalf("%+v", op)
	}
	if s.InFlight != "c1" {
		t.Fatal("in flight not recorded")
	}
	rec, _ := s.Journal.Get("b1", 1)
	if rec.Step != msg.StepReceived || !rec.ReceivedAck {
		t.Fatalf("%+v", rec)
	}
}

func TestOfferHashMismatchRefuses(t *testing.T) {
	o := offer(1, "a", msg.OpApply)
	o.ConfigSHA256 = "deadbeef"
	_, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: o}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeRefused || ack.Detail.Code != msg.DetailOf(msg.ErrKeyHashMismatch) {
		t.Fatalf("%+v", ack)
	}
	if _, ok := has[reactor.RunOp](effs); ok {
		t.Fatal("must not run")
	}
}

func TestOfferTooLargeRefuses(t *testing.T) {
	big := make([]byte, 2048)
	o := msg.CommandOffer{CommandID: "c1", Generation: 1, IdempotencyKey: "k", BindingID: "b1", Operation: msg.OpApply, Payload: big, ConfigSHA256: sha256Hex(big)}
	_, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: o}, env(t0), okAuth)
	if ack := sent[msg.CommandAck](t, effs); ack.Detail.Code != msg.DetailOf(msg.ErrPayloadTooLarge) {
		t.Fatalf("%+v", ack)
	}
}

func TestRedeliveryOfTerminalRecordReplaysStoredAck(t *testing.T) {
	s := newState()
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", IdempotencyKey: "k1",
		ConfigHash: sha("x"), Step: msg.StepReported, Outcome: msg.OutcomeApplied, AckSent: true, EffectiveHash: sha("x")})
	o := offer(1, "x", msg.OpApply)
	s2, effs := Reduce(s, reactor.WireIn{At: t0, Msg: o}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeApplied {
		t.Fatalf("stored outcome must be replayed: %+v", ack)
	}
	if _, ok := has[reactor.RunOp](effs); ok {
		t.Fatal("a completed command must never run twice")
	}
	if s2.InFlight != "" {
		t.Fatal("no new work")
	}
}

func TestSameKeyDifferentHashIsProtocolError(t *testing.T) {
	s := newState()
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", IdempotencyKey: "k1",
		ConfigHash: sha("x"), Step: msg.StepReported, Outcome: msg.OutcomeApplied, AckSent: true})
	o := offer(1, "different", msg.OpApply)
	_, effs := Reduce(s, reactor.WireIn{At: t0, Msg: o}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeRefused || ack.Detail.Code != msg.DetailOf(msg.ErrKeyHashMismatch) {
		t.Fatalf("%+v", ack)
	}
}

func TestSupersededErrorClosesRecord(t *testing.T) {
	s := newState()
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", Step: msg.StepReceived})
	s2, effs := Reduce(s, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrSuperseded, CorrelationID: "c1"}}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeSuperseded {
		t.Fatalf("%+v", ack)
	}
	r, _ := s2.Journal.Get("b1", 1)
	if !r.Terminal() || r.Outcome != msg.OutcomeSuperseded {
		t.Fatalf("%+v", r)
	}
}

func TestOpProgressJournalsStep(t *testing.T) {
	s := newState()
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", Step: msg.StepReceived})
	s.InFlight = "c1"
	s2, effs := Reduce(s, reactor.OpProgress{At: t0, CommandID: "c1", Step: msg.StepStaged}, env(t0), okAuth)
	if _, ok := has[reactor.Persist](effs); !ok {
		t.Fatal("step must be journaled")
	}
	if pr := sent[msg.ApplyProgress](t, effs); pr.Step != msg.StepStaged {
		t.Fatal(pr)
	}
	r, _ := s2.Journal.Get("b1", 1)
	if r.Step != msg.StepStaged {
		t.Fatal(r.Step)
	}
}

func TestOpFinishedAcksAndSweeps(t *testing.T) {
	s := newState()
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c0", Step: msg.StepReported, Outcome: msg.OutcomeApplied})
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 2, CommandID: "c1", Operation: msg.OpApply, Step: msg.StepPersisted})
	s.InFlight = "c1"
	res := runtimeResult(msg.OutcomeApplied, sha("x"))
	s2, effs := Reduce(s, reactor.OpFinished{At: t0, CommandID: "c1", Result: res}, env(t0), okAuth)
	ack := sent[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeApplied || ack.Generation != 2 {
		t.Fatalf("%+v", ack)
	}
	r, _ := s2.Journal.Get("b1", 2)
	if !r.Terminal() || !r.AckSent || r.EffectiveHash != sha("x") {
		t.Fatalf("%+v", r)
	}
	var deleted []string
	for _, e := range effs {
		if d, ok := e.(reactor.Delete); ok {
			deleted = append(deleted, d.Rel)
		}
	}
	// the finished generation's payload, and the superseded generation 1's dir
	if len(deleted) < 2 {
		t.Fatalf("sweep: %v", deleted)
	}
	if s2.InFlight != "" {
		t.Fatal("in flight must clear")
	}
}

func TestStepDeadlineWhileInFlightIsIndeterminateOnlyWhenLeaseLeftActive(t *testing.T) {
	base := newState()
	base.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", Operation: msg.OpApply, Step: msg.StepStaged})
	base.InFlight = "c1"

	// lease still ACTIVE → a blown deadline is an ordinary failure
	s1, effs1 := Reduce(base, reactor.TimerFired{At: t0, Kind: reactor.StepDeadline}, env(t0), okAuth)
	if ack := sent[msg.CommandAck](t, effs1); ack.Outcome != msg.OutcomeFailedNoEffect {
		t.Fatalf("%+v", ack)
	}
	_ = s1

	// lease left ACTIVE with a step in flight → APPLY_INDETERMINATE (AG-D5)
	s2, effs2 := Reduce(base, reactor.TimerFired{At: t0, Kind: reactor.StepDeadline}, env(t0), auth{detail: msg.DetailLeaseNotActive})
	if ack := sent[msg.CommandAck](t, effs2); ack.Outcome != msg.OutcomeApplyIndeterminate {
		t.Fatalf("%+v", ack)
	}
	r, _ := s2.Journal.Get("b1", 1)
	if r.Outcome != msg.OutcomeApplyIndeterminate {
		t.Fatalf("%+v", r)
	}
}

func TestResumeTable(t *testing.T) {
	cases := []struct {
		step        msg.ProgressStep
		wantRunOp   bool
		wantDelete  bool
		wantAck     bool
		wantRecvAck bool
	}{
		{step: msg.StepReceived, wantDelete: true},               // discard; the DP redelivers
		{step: msg.StepStaged, wantRunOp: true},                  // verify temp and complete
		{step: msg.StepPersisted, wantRunOp: true},               // re-read, verify, re-report — never rewrite
		{step: msg.StepReported, wantAck: false},                 // closed
	}
	for _, c := range cases {
		s := newState()
		r := Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", Operation: msg.OpApply, Step: c.step, ConfigHash: sha("x")}
		if c.step == msg.StepReported {
			r.Outcome, r.AckSent = msg.OutcomeApplied, true
		}
		s.Journal.Put(r)
		_, effs := Reduce(s, reactor.Resumed{At: t0}, env(t0), okAuth)
		_, gotRun := has[reactor.RunOp](effs)
		_, gotDel := has[reactor.Delete](effs)
		if gotRun != c.wantRunOp {
			t.Errorf("%s: RunOp=%v want %v", c.step, gotRun, c.wantRunOp)
		}
		if c.wantDelete && !gotDel {
			t.Errorf("%s: expected the partial to be discarded", c.step)
		}
	}
}

func TestResumeResendsUnacknowledgedTerminalOutcome(t *testing.T) {
	s := newState()
	s.Journal.Put(Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 1, CommandID: "c1", Step: msg.StepReported,
		Outcome: msg.OutcomeApplied, AckSent: false})
	_, effs := Reduce(s, reactor.Resumed{At: t0}, env(t0), okAuth)
	if ack := sent[msg.CommandAck](t, effs); ack.Outcome != msg.OutcomeApplied {
		t.Fatalf("%+v", ack)
	}
}

func TestLoadAndPersistedGeneration(t *testing.T) {
	st, _ := store.Open(t.TempDir())
	_ = st.Save(store.JSON{RelPath: "apply/b1/4.json", Value: Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 4,
		CommandID: "c", Step: msg.StepReported, Outcome: msg.OutcomeApplied}})
	_ = st.Save(store.JSON{RelPath: "apply/b1/5.json", Value: Record{SchemaVersion: 1, BindingID: "b1", RolloutGeneration: 5,
		CommandID: "c2", Step: msg.StepStaged}})
	s, err := Load(st)
	if err != nil {
		t.Fatal(err)
	}
	if s.PersistedGeneration("b1") != 4 {
		t.Fatalf("only a persisted generation counts: %d", s.PersistedGeneration("b1"))
	}
	if s.OpenCount() != 1 {
		t.Fatal(s.OpenCount())
	}
}
```

`sha256Hex` comes from `commands.go` (same package). Add this helper at the bottom of `commands_test.go` and import `internal/runtime`:

```go
func runtimeResult(o msg.Outcome, hash string) runtime.OpResult {
	return runtime.OpResult{Outcome: o, EffectiveHash: hash, Facts: &runtime.ReplicaFacts{ReplicaRef: "r1", Incarnation: "abc"}}
}
```

- [ ] **Step 2: Run to verify failure**

Run: `go test ./internal/commands/`
Expected: FAIL — `Reduce`, `State`, `Load` undefined.

- [ ] **Step 3: Implement**

`internal/commands/commands.go`:

```go
package commands

import (
	"crypto/sha256"
	"encoding/hex"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

func sha256Hex(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

// Authority is the read-only slice of the lease reducer this one consults.
// authority.State satisfies it; this package does not import authority.
type Authority interface {
	RefusalDetail(op msg.Operation, bindingID string, dpNow time.Time) msg.DetailCode
	StepDeadline(op msg.Operation, start time.Time) time.Time
	Active() bool
}

type State struct {
	Journal  Journal
	InFlight string
}

func Load(s *store.Store) (State, error) {
	j, err := LoadJournal(s)
	return State{Journal: j}, err
}

// PersistedGeneration is the highest generation durably written for a binding.
func (s State) PersistedGeneration(binding string) uint64 {
	var out uint64
	for _, r := range s.Journal.Records {
		if r.BindingID != binding {
			continue
		}
		if (r.Step == msg.StepPersisted || r.Step == msg.StepReported) && r.Outcome != msg.OutcomeRefused && r.RolloutGeneration > out {
			out = r.RolloutGeneration
		}
	}
	return out
}

func (s State) OpenCount() int { return s.Journal.OpenCount() }

func Reduce(s State, ev reactor.Event, env reactor.Env, auth Authority) (State, []reactor.Effect) {
	switch e := ev.(type) {
	case reactor.Resumed:
		return s.resume(env)

	case reactor.TimerFired:
		if e.Kind != reactor.StepDeadline || s.InFlight == "" {
			return s, nil
		}
		r, ok := s.Journal.ByCommand(s.InFlight)
		if !ok {
			s.InFlight = ""
			return s, nil
		}
		// AG-D5: APPLY_INDETERMINATE only when a step was in flight and the
		// lease left ACTIVE; otherwise a blown deadline is an ordinary failure.
		outcome := msg.OutcomeFailedNoEffect
		detail := msg.AckDetail{Message: "step deadline exceeded"}
		if !auth.Active() {
			outcome = msg.OutcomeApplyIndeterminate
			detail = msg.AckDetail{Code: msg.DetailLeaseNotActive, Message: "lease left ACTIVE with a step in flight"}
		}
		return s.terminate(r, outcome, detail, "", env)

	case reactor.OpProgress:
		r, ok := s.Journal.ByCommand(e.CommandID)
		if !ok {
			return s, nil
		}
		r.Step = e.Step
		s.Journal.Put(r)
		return s, []reactor.Effect{
			reactor.Persist{Rec: r},
			reactor.Send{Msg: msg.ApplyProgress{Envelope: env.Env(), CommandID: r.CommandID, Step: e.Step, ReplicaRef: r.ReplicaRef, At: env.Now}},
		}

	case reactor.OpFinished:
		r, ok := s.Journal.ByCommand(e.CommandID)
		if !ok {
			return s, nil
		}
		if e.Result.Facts != nil {
			r.ReplicaRef = e.Result.Facts.ReplicaRef
		}
		return s.terminate(r, e.Result.Outcome, e.Result.Detail, e.Result.EffectiveHash, env)

	case reactor.WireIn:
		switch m := e.Msg.(type) {
		case msg.GenerationAvailable:
			return s.notice(m, env, auth)
		case msg.CommandOffer:
			return s.offer(m, env, auth)
		case msg.Error:
			return s.wireError(m, env)
		}
	}
	return s, nil
}

func (s State) refuse(commandID, bindingID string, gen uint64, code msg.DetailCode, env reactor.Env) (State, []reactor.Effect) {
	return s, []reactor.Effect{reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: commandID, Generation: gen,
		Outcome: msg.OutcomeRefused, Detail: msg.AckDetail{Code: code}, AgentClock: env.Now}}}
}

func (s State) notice(m msg.GenerationAvailable, env reactor.Env, auth Authority) (State, []reactor.Effect) {
	if d := auth.RefusalDetail(m.Operation, m.BindingID, env.DPNow()); d != msg.DetailNone {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, d, env)
	}
	if r, ok := s.Journal.Get(m.BindingID, m.Generation); ok && r.Terminal() {
		// redelivery of a finished generation: replay the stored outcome
		return s, []reactor.Effect{reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: r.CommandID, Generation: r.RolloutGeneration,
			Outcome: r.Outcome, Detail: r.Detail, AgentClock: env.Now}}}
	}
	if m.Generation < s.Journal.Watermark[m.BindingID] {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, msg.DetailOf(msg.ErrStaleGeneration), env)
	}
	return s, []reactor.Effect{reactor.Send{Msg: msg.CommandPull{Envelope: env.Env(), CommandID: m.CommandID}}}
}

func (s State) offer(m msg.CommandOffer, env reactor.Env, auth Authority) (State, []reactor.Effect) {
	if d := auth.RefusalDetail(m.Operation, m.BindingID, env.DPNow()); d != msg.DetailNone {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, d, env)
	}
	if env.Limits.MaxMessageBytes > 0 && uint64(len(m.Payload)) > env.Limits.MaxMessageBytes {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, msg.DetailOf(msg.ErrPayloadTooLarge), env)
	}
	if sha256Hex(m.Payload) != m.ConfigSHA256 {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, msg.DetailOf(msg.ErrKeyHashMismatch), env)
	}
	if prev, ok := s.Journal.Get(m.BindingID, m.Generation); ok {
		if prev.IdempotencyKey == m.IdempotencyKey && prev.ConfigHash != m.ConfigSHA256 {
			// same key, different bytes: a DP bug, never resolved by retry
			return s.refuse(m.CommandID, m.BindingID, m.Generation, msg.DetailOf(msg.ErrKeyHashMismatch), env)
		}
		if prev.Terminal() {
			return s, []reactor.Effect{reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: prev.CommandID, Generation: prev.RolloutGeneration,
				Outcome: prev.Outcome, Detail: prev.Detail, AgentClock: env.Now}}}
		}
		if prev.ReceivedAck {
			// in flight and already acknowledged: re-announce, do not re-run
			return s, []reactor.Effect{reactor.Send{Msg: msg.CommandReceived{Envelope: env.Env(), CommandID: prev.CommandID, Generation: prev.RolloutGeneration}}}
		}
	}
	if m.Generation < s.Journal.Watermark[m.BindingID] {
		return s.refuse(m.CommandID, m.BindingID, m.Generation, msg.DetailOf(msg.ErrStaleGeneration), env)
	}

	r := Record{
		SchemaVersion: schemaVersion, RolloutGeneration: m.Generation, IdempotencyKey: m.IdempotencyKey, CommandID: m.CommandID,
		BindingID: m.BindingID, Operation: m.Operation, ConfigHash: m.ConfigSHA256, Step: msg.StepReceived,
		StepDeadline: auth.StepDeadline(m.Operation, env.DPNow()), ReceivedAck: true,
	}
	s.Journal.Put(r)
	s.InFlight = m.CommandID
	effs := []reactor.Effect{
		reactor.Persist{Rec: store.Blob{RelPath: PayloadRel(m.BindingID, m.Generation), Data: m.Payload}},
		reactor.Persist{Rec: r},
		reactor.Send{Msg: msg.CommandReceived{Envelope: env.Env(), CommandID: m.CommandID, Generation: m.Generation}},
		reactor.Send{Msg: msg.ApplyProgress{Envelope: env.Env(), CommandID: m.CommandID, Step: msg.StepReceived, At: env.Now}},
		reactor.RunOp{CommandID: m.CommandID, BindingID: m.BindingID, Op: m.Operation, PayloadRel: PayloadRel(m.BindingID, m.Generation),
			SHA256: m.ConfigSHA256, Deadline: r.StepDeadline},
		reactor.ArmTimer{Kind: reactor.StepDeadline, After: r.StepDeadline.Sub(env.DPNow())},
	}
	return s, effs
}

func (s State) wireError(m msg.Error, env reactor.Env) (State, []reactor.Effect) {
	if m.CorrelationID == "" {
		return s, nil
	}
	r, ok := s.Journal.ByCommand(m.CorrelationID)
	if !ok {
		return s, nil
	}
	switch m.Code {
	case msg.ErrSuperseded:
		return s.terminate(r, msg.OutcomeSuperseded, msg.AckDetail{Code: msg.DetailOf(msg.ErrSuperseded)}, "", env)
	case msg.ErrUnknownCommand, msg.ErrStaleGeneration:
		return s.terminate(r, msg.OutcomeRefused, msg.AckDetail{Code: msg.DetailOf(m.Code)}, "", env)
	}
	return s, nil
}

// terminate closes a record, acks it, and sweeps superseded partials.
func (s State) terminate(r Record, outcome msg.Outcome, detail msg.AckDetail, effectiveHash string, env reactor.Env) (State, []reactor.Effect) {
	r.Step, r.Outcome, r.Detail, r.AckSent = msg.StepReported, outcome, detail, true
	if effectiveHash != "" {
		r.EffectiveHash = effectiveHash
	}
	s.Journal.Put(r)
	if s.InFlight == r.CommandID {
		s.InFlight = ""
	}
	effs := []reactor.Effect{
		reactor.Persist{Rec: r},
		reactor.CancelTimer{Kind: reactor.StepDeadline},
		reactor.Send{Msg: msg.ApplyProgress{Envelope: env.Env(), CommandID: r.CommandID, Step: msg.StepReported, ReplicaRef: r.ReplicaRef, At: env.Now}},
		reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: r.CommandID, Generation: r.RolloutGeneration,
			Outcome: outcome, Detail: detail, AgentClock: env.Now}},
		reactor.Delete{Rel: PayloadRel(r.BindingID, r.RolloutGeneration)},
	}
	if outcome == msg.OutcomeApplied {
		for _, dir := range s.Journal.SupersededPartials(r.BindingID, r.RolloutGeneration) {
			effs = append(effs, reactor.Delete{Rel: dir})
		}
	}
	return s, effs
}

// resume replays the journal per the AG-D4 table.
func (s State) resume(env reactor.Env) (State, []reactor.Effect) {
	var effs []reactor.Effect
	for _, r := range s.Journal.Records {
		switch r.Step {
		case msg.StepReceived:
			// nothing was written; discard and let the DP redeliver
			effs = append(effs, reactor.Delete{Rel: DirRel(r.BindingID, r.RolloutGeneration)})
		case msg.StepStaged, msg.StepPersisted:
			// re-enter the adapter: it verifies the temp / reads back the file
			// and never rewrites a PERSISTED target
			if s.InFlight == "" {
				s.InFlight = r.CommandID
			}
			effs = append(effs,
				reactor.RunOp{CommandID: r.CommandID, BindingID: r.BindingID, Op: r.Operation,
					PayloadRel: PayloadRel(r.BindingID, r.RolloutGeneration), SHA256: r.ConfigHash, Deadline: r.StepDeadline},
				reactor.ArmTimer{Kind: reactor.StepDeadline, After: time.Until(r.StepDeadline)},
			)
		case msg.StepReported:
			if !r.AckSent {
				effs = append(effs, reactor.Send{Msg: msg.CommandAck{Envelope: env.Env(), CommandID: r.CommandID,
					Generation: r.RolloutGeneration, Outcome: r.Outcome, Detail: r.Detail, AgentClock: env.Now}})
			}
		}
	}
	return s, effs
}
```

Add to `internal/reactor/reactor.go` the envelope helper the reducers use:

```go
// Env mints one envelope per outbound message.
func (e Env) Env() msg.Envelope { return msg.Envelope{MessageID: e.NewID(), SentAt: e.Now} }
```

and use it in `session.go`/`authority.go` in place of the inline `msg.Envelope{MessageID: env.NewID(), SentAt: env.Now}` literals (behaviour identical; do it now so there is one spelling).

- [ ] **Step 4: Run**

Run: `go test ./internal/commands/ ./internal/agent/ ./internal/authority/ ./internal/reactor/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add internal/commands internal/reactor internal/agent internal/authority
git commit -m "go-agent: commands reducer — offer gate, idempotent redelivery, journal resume, AG-D5 outcomes

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 19: Docker adapter (`AG-D20`) and the op runner

**Files:**
- Create: `internal/runtime/docker/docker.go`, `internal/runtime/docker/docker_test.go`, `internal/runtime/docker/e2e_test.go` (`//go:build docker`), `internal/agent/ops.go`, `internal/agent/ops_test.go`
- Modify: `internal/agent/state.go`, `internal/agent/wiring.go`, `cmd/sm-agent/main.go`

**Interfaces:**
- Produces:
  - `docker.Adapter` implementing `runtime.Adapter`; `func New(cli engineAPI) *Adapter`; `func NewFromEnv() (*Adapter, func() error, error)`.
  - `const BindingLabel = "com.solace.sm.binding-id"`.
  - `agent.opRunner` implementing `OpRunner` over a `runtime.Adapter` + `*store.Store`, pushing `reactor.OpProgress` through the loop as the journal steps pass.

- [ ] **Step 1: Failing adapter tests (fake engine API)**

`internal/runtime/docker/docker_test.go`:

```go
package docker

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/filters"
	"github.com/solace/solution-manager/go-agent/internal/msg"
)

type fakeEngine struct {
	list      []types.Container
	inspect   map[string]types.ContainerJSON
	restarted []string
	stopped   []string
	failOp    error
}

func (f *fakeEngine) ContainerList(_ context.Context, opts container.ListOptions) ([]types.Container, error) {
	want := opts.Filters.Get("label")
	if len(want) == 0 {
		return f.list, nil
	}
	var out []types.Container
	for _, c := range f.list {
		for _, w := range want {
			if c.Labels[BindingLabel] != "" && (w == BindingLabel+"="+c.Labels[BindingLabel]) {
				out = append(out, c)
			}
		}
	}
	return out, nil
}
func (f *fakeEngine) ContainerInspect(_ context.Context, id string) (types.ContainerJSON, error) {
	c, ok := f.inspect[id]
	if !ok {
		return types.ContainerJSON{}, errors.New("no such container")
	}
	return c, nil
}
func (f *fakeEngine) ContainerRestart(_ context.Context, id string, _ container.StopOptions) error {
	if f.failOp != nil {
		return f.failOp
	}
	f.restarted = append(f.restarted, id)
	c := f.inspect[id]
	c.State.StartedAt = time.Now().Add(time.Second).Format(time.RFC3339Nano)
	c.State.Running = true
	f.inspect[id] = c
	return nil
}
func (f *fakeEngine) ContainerStop(_ context.Context, id string, _ container.StopOptions) error {
	if f.failOp != nil {
		return f.failOp
	}
	f.stopped = append(f.stopped, id)
	c := f.inspect[id]
	c.State.Running = false
	f.inspect[id] = c
	return nil
}
func (f *fakeEngine) Close() error { return nil }

var _ = filters.NewArgs

func engineWith(t *testing.T, hostDir string) (*fakeEngine, string) {
	t.Helper()
	id := "abc123def456"
	return &fakeEngine{
		list: []types.Container{{ID: id, Names: []string{"/java1"}, Labels: map[string]string{BindingLabel: "b1"}}},
		inspect: map[string]types.ContainerJSON{id: {
			ContainerJSONBase: &types.ContainerJSONBase{ID: id, Name: "/java1", Image: "sha256:img",
				State: &types.ContainerState{Running: true, StartedAt: time.Now().Format(time.RFC3339Nano)},
				HostConfig: &container.HostConfig{}},
			Config: &container.Config{
				Cmd: []string{"java", "-jar", "/app.jar", "--spring.config.additional-location=file:/etc/app/"},
				Env: []string{"FOO=bar"},
			},
			Mounts: []types.MountPoint{{Type: "bind", Source: hostDir, Destination: "/etc/app", RW: true}},
		}},
	}, id
}

func TestResolveFindsContainerPathAndMount(t *testing.T) {
	hostDir := t.TempDir()
	eng, id := engineWith(t, hostDir)
	a := New(eng)
	p, err := a.Resolve(context.Background(), "b1")
	if err != nil {
		t.Fatal(err)
	}
	if p.ContainerID != id || p.Name != "java1" {
		t.Fatalf("%+v", p)
	}
	if p.ConfigPathInContainer != "/etc/app/application.yml" {
		t.Fatalf("config path %q", p.ConfigPathInContainer)
	}
	if p.HostPath != filepath.Join(hostDir, "application.yml") {
		t.Fatalf("host path %q", p.HostPath)
	}
	if p.Mechanism != "additional-location" {
		t.Fatal(p.Mechanism)
	}
}

func TestResolveFromEnvVariable(t *testing.T) {
	hostDir := t.TempDir()
	eng, id := engineWith(t, hostDir)
	c := eng.inspect[id]
	c.Config.Cmd = []string{"java", "-jar", "/app.jar"}
	c.Config.Env = []string{"SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/app/"}
	eng.inspect[id] = c
	p, err := New(eng).Resolve(context.Background(), "b1")
	if err != nil || p.HostPath != filepath.Join(hostDir, "application.yml") {
		t.Fatalf("%v %+v", err, p)
	}
}

func TestResolveRefusesWithoutDeclaredPath(t *testing.T) {
	hostDir := t.TempDir()
	eng, id := engineWith(t, hostDir)
	c := eng.inspect[id]
	c.Config.Cmd = []string{"java", "-jar", "/app.jar"}
	c.Config.Env = nil
	eng.inspect[id] = c
	if _, err := New(eng).Resolve(context.Background(), "b1"); !errors.Is(err, ErrNoDeclaredPath) {
		t.Fatalf("want ErrNoDeclaredPath, got %v", err)
	}
}

func TestResolveRefusesWithoutBindMount(t *testing.T) {
	eng, id := engineWith(t, t.TempDir())
	c := eng.inspect[id]
	c.Mounts = []types.MountPoint{{Type: "volume", Name: "vol", Destination: "/etc/app"}}
	eng.inspect[id] = c
	if _, err := New(eng).Resolve(context.Background(), "b1"); !errors.Is(err, ErrNoBindMount) {
		t.Fatalf("want ErrNoBindMount, got %v", err)
	}
}

func TestResolveAmbiguousAndMissing(t *testing.T) {
	eng, _ := engineWith(t, t.TempDir())
	eng.list = append(eng.list, types.Container{ID: "second", Names: []string{"/java2"}, Labels: map[string]string{BindingLabel: "b1"}})
	if _, err := New(eng).Resolve(context.Background(), "b1"); !errors.Is(err, ErrAmbiguous) {
		t.Fatalf("want ErrAmbiguous, got %v", err)
	}
	eng2, _ := engineWith(t, t.TempDir())
	if _, err := New(eng2).Resolve(context.Background(), "nope"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

func TestPreflightReportsFacts(t *testing.T) {
	hostDir := t.TempDir()
	eng, _ := engineWith(t, hostDir)
	a := New(eng)
	p, _ := a.Resolve(context.Background(), "b1")
	pr, err := a.Preflight(context.Background(), p)
	if err != nil {
		t.Fatal(err)
	}
	if !pr.Writable || pr.HostPath != p.HostPath || pr.ConfigPathInContainer != p.ConfigPathInContainer || pr.Mechanism != "additional-location" {
		t.Fatalf("%+v", pr)
	}
}

func TestPreflightReportsCompetingSources(t *testing.T) {
	hostDir := t.TempDir()
	eng, id := engineWith(t, hostDir)
	c := eng.inspect[id]
	c.Config.Env = append(c.Config.Env, "SPRING_APPLICATION_JSON={}", "SPRING_CONFIG_LOCATION=file:/other/")
	eng.inspect[id] = c
	a := New(eng)
	p, _ := a.Resolve(context.Background(), "b1")
	pr, _ := a.Preflight(context.Background(), p)
	if len(pr.CompetingSources) != 2 {
		t.Fatalf("competing sources: %v", pr.CompetingSources)
	}
}

func TestRestartObservesNewStartedAt(t *testing.T) {
	eng, _ := engineWith(t, t.TempDir())
	a := New(eng)
	p, _ := a.Resolve(context.Background(), "b1")
	before, _ := a.Observe(context.Background(), p)
	if err := a.Restart(context.Background(), p); err != nil {
		t.Fatal(err)
	}
	after, _ := a.Observe(context.Background(), p)
	if !after.Running || !after.StartedAt.After(before.StartedAt) {
		t.Fatalf("before=%v after=%v", before.StartedAt, after.StartedAt)
	}
	if after.Incarnation != p.ContainerID || after.ReplicaRef != "java1" {
		t.Fatalf("%+v", after)
	}
}

func TestStopObservesNotRunning(t *testing.T) {
	eng, _ := engineWith(t, t.TempDir())
	a := New(eng)
	p, _ := a.Resolve(context.Background(), "b1")
	if err := a.Stop(context.Background(), p); err != nil {
		t.Fatal(err)
	}
	f, _ := a.Observe(context.Background(), p)
	if f.Running {
		t.Fatal("still running")
	}
}

func TestWriteConfigDelegatesToWriter(t *testing.T) {
	hostDir := t.TempDir()
	eng, _ := engineWith(t, hostDir)
	a := New(eng)
	p, _ := a.Resolve(context.Background(), "b1")
	var steps []msg.ProgressStep
	res, err := a.WriteConfig(context.Background(), p, []byte("x: 1\n"), sha("x: 1\n"), filepath.Join(t.TempDir(), "prev"),
		func(s msg.ProgressStep) error { steps = append(steps, s); return nil })
	if err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(p.HostPath)
	if string(got) != "x: 1\n" || res.Hash != sha("x: 1\n") || len(steps) != 2 {
		t.Fatalf("%q %+v %v", got, res, steps)
	}
}
```

Add `func sha(s string) string { return configwriter.SHA256([]byte(s)) }` to the test file and import `internal/runtime/configwriter`.

- [ ] **Step 2: Implement the adapter**

`internal/runtime/docker/docker.go`:

```go
// Package docker implements runtime.Adapter against the Docker Engine API.
// Binding → container resolution is AG-D20: the label com.solace.sm.binding-id
// identifies the container, the config path comes from the artifact's own
// launch arguments or environment (SM-D21: SM discovers, never invents), and
// the host path is that container path mapped through a bind mount.
package docker

import (
	"context"
	"errors"
	"fmt"
	"path"
	"path/filepath"
	"strings"
	"time"

	"github.com/docker/docker/api/types"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/filters"
	dockerclient "github.com/docker/docker/client"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/runtime/configwriter"
)

const (
	BindingLabel  = "com.solace.sm.binding-id"
	configFileName = "application.yml"

	argAdditionalLocation = "--spring.config.additional-location="
	argConfigLocation     = "--spring.config.location="
	envAdditionalLocation = "SPRING_CONFIG_ADDITIONAL_LOCATION"
	envConfigLocation     = "SPRING_CONFIG_LOCATION"
	envApplicationJSON    = "SPRING_APPLICATION_JSON"
)

var (
	ErrNotFound       = errors.New("docker: no container carries the binding label")
	ErrAmbiguous      = errors.New("docker: more than one container carries the binding label")
	ErrNoDeclaredPath = errors.New("docker: the container declares no external config location")
	ErrNoBindMount    = errors.New("docker: the declared config directory is not a bind mount")
)

type engineAPI interface {
	ContainerList(ctx context.Context, opts container.ListOptions) ([]types.Container, error)
	ContainerInspect(ctx context.Context, id string) (types.ContainerJSON, error)
	ContainerRestart(ctx context.Context, id string, opts container.StopOptions) error
	ContainerStop(ctx context.Context, id string, opts container.StopOptions) error
	Close() error
}

type Adapter struct{ cli engineAPI }

func New(cli engineAPI) *Adapter { return &Adapter{cli: cli} }

func NewFromEnv() (*Adapter, func() error, error) {
	cli, err := dockerclient.NewClientWithOpts(dockerclient.FromEnv, dockerclient.WithAPIVersionNegotiation())
	if err != nil {
		return nil, nil, err
	}
	return &Adapter{cli: cli}, cli.Close, nil
}

func (a *Adapter) Resolve(ctx context.Context, bindingID string) (runtime.Placement, error) {
	f := filters.NewArgs()
	f.Add("label", BindingLabel+"="+bindingID)
	list, err := a.cli.ContainerList(ctx, container.ListOptions{All: true, Filters: f})
	if err != nil {
		return runtime.Placement{}, err
	}
	switch {
	case len(list) == 0:
		return runtime.Placement{}, fmt.Errorf("%w: %s=%s", ErrNotFound, BindingLabel, bindingID)
	case len(list) > 1:
		return runtime.Placement{}, fmt.Errorf("%w: %s=%s matches %d", ErrAmbiguous, BindingLabel, bindingID, len(list))
	}
	insp, err := a.cli.ContainerInspect(ctx, list[0].ID)
	if err != nil {
		return runtime.Placement{}, err
	}
	dir, mechanism := declaredConfigDir(insp)
	if dir == "" {
		return runtime.Placement{}, fmt.Errorf("%w (container %s)", ErrNoDeclaredPath, name(insp))
	}
	host, err := hostPathFor(insp, dir)
	if err != nil {
		return runtime.Placement{}, err
	}
	return runtime.Placement{
		BindingID: bindingID, ContainerID: insp.ID, Name: name(insp),
		ConfigPathInContainer: path.Join(dir, configFileName),
		HostPath:              filepath.Join(host, configFileName),
		Mechanism:             mechanism, UID: -1, GID: -1,
	}, nil
}

func name(insp types.ContainerJSON) string { return strings.TrimPrefix(insp.Name, "/") }

// declaredConfigDir reads the artifact's own launch spec; SM never invents one.
func declaredConfigDir(insp types.ContainerJSON) (dir, mechanism string) {
	if insp.Config == nil {
		return "", ""
	}
	args := append(append([]string{}, insp.Config.Entrypoint...), insp.Config.Cmd...)
	for _, a := range args {
		if v, ok := cut(a, argAdditionalLocation); ok {
			return normalizeDir(v), "additional-location"
		}
		if v, ok := cut(a, argConfigLocation); ok {
			return normalizeDir(v), "overwrite-in-place"
		}
	}
	for _, e := range insp.Config.Env {
		k, v, found := strings.Cut(e, "=")
		if !found {
			continue
		}
		switch k {
		case envAdditionalLocation:
			return normalizeDir(v), "additional-location"
		case envConfigLocation:
			return normalizeDir(v), "overwrite-in-place"
		}
	}
	return "", ""
}

func cut(arg, prefix string) (string, bool) {
	if strings.HasPrefix(arg, prefix) {
		return strings.TrimPrefix(arg, prefix), true
	}
	return "", false
}

// normalizeDir turns "file:/etc/app/" or "/etc/app/application.yml" into "/etc/app".
func normalizeDir(v string) string {
	v = strings.TrimPrefix(v, "file:")
	if i := strings.IndexByte(v, ','); i >= 0 {
		v = v[:i] // only the first location is SM's
	}
	if strings.HasSuffix(v, "/") {
		return path.Clean(v)
	}
	if path.Ext(v) != "" {
		return path.Dir(v)
	}
	return path.Clean(v)
}

// hostPathFor maps a container directory through a bind mount.
func hostPathFor(insp types.ContainerJSON, containerDir string) (string, error) {
	best := ""
	bestLen := -1
	for _, m := range insp.Mounts {
		if m.Type != "bind" {
			continue
		}
		dest := path.Clean(m.Destination)
		if dest == containerDir || strings.HasPrefix(containerDir+"/", dest+"/") {
			if len(dest) > bestLen {
				rel := strings.TrimPrefix(containerDir, dest)
				best, bestLen = filepath.Join(m.Source, filepath.FromSlash(rel)), len(dest)
			}
		}
	}
	if best == "" {
		return "", fmt.Errorf("%w: %s", ErrNoBindMount, containerDir)
	}
	return best, nil
}

func (a *Adapter) Preflight(ctx context.Context, p runtime.Placement) (msg.PreflightResult, error) {
	insp, err := a.cli.ContainerInspect(ctx, p.ContainerID)
	if err != nil {
		return msg.PreflightResult{}, err
	}
	res := msg.PreflightResult{
		ContainerID: p.ContainerID, ImageDigest: imageDigest(insp),
		ConfigPathInContainer: p.ConfigPathInContainer, HostPath: p.HostPath, Mechanism: p.Mechanism,
		CompetingSources: competingSources(insp), Writable: writable(insp, p),
	}
	return res, nil
}

func competingSources(insp types.ContainerJSON) []string {
	var out []string
	if insp.Config == nil {
		return nil
	}
	for _, e := range insp.Config.Env {
		k, _, _ := strings.Cut(e, "=")
		switch k {
		case envApplicationJSON, envConfigLocation:
			out = append(out, k)
		}
	}
	return out
}

func writable(insp types.ContainerJSON, p runtime.Placement) bool {
	dir := path.Dir(p.ConfigPathInContainer)
	for _, m := range insp.Mounts {
		if m.Type == "bind" && (path.Clean(m.Destination) == dir || strings.HasPrefix(dir+"/", path.Clean(m.Destination)+"/")) {
			return m.RW
		}
	}
	return false
}

func imageDigest(insp types.ContainerJSON) string {
	if insp.ContainerJSONBase == nil {
		return ""
	}
	return insp.Image
}

func (a *Adapter) WriteConfig(_ context.Context, p runtime.Placement, payload []byte, sha string, previousPath string, progress runtime.Progress) (runtime.WriteResult, error) {
	return configwriter.Write(p.HostPath, payload, sha, previousPath, p.UID, p.GID, progress)
}

func (a *Adapter) Restart(ctx context.Context, p runtime.Placement) error {
	return a.cli.ContainerRestart(ctx, p.ContainerID, container.StopOptions{})
}

func (a *Adapter) Stop(ctx context.Context, p runtime.Placement) error {
	return a.cli.ContainerStop(ctx, p.ContainerID, container.StopOptions{})
}

func (a *Adapter) Observe(ctx context.Context, p runtime.Placement) (runtime.ReplicaFacts, error) {
	insp, err := a.cli.ContainerInspect(ctx, p.ContainerID)
	if err != nil {
		return runtime.ReplicaFacts{}, err
	}
	f := runtime.ReplicaFacts{ReplicaRef: name(insp), Incarnation: insp.ID, ImageDigest: imageDigest(insp)}
	if insp.State != nil {
		f.Running, f.ExitCode = insp.State.Running, insp.State.ExitCode
		if t, err := time.Parse(time.RFC3339Nano, insp.State.StartedAt); err == nil {
			f.StartedAt = t.UTC()
		}
	}
	return f, nil
}
```

- [ ] **Step 3: The op runner**

`internal/agent/ops.go`:

```go
package agent

import (
	"context"
	"errors"
	"os"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/commands"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/runtime/configwriter"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// opRunner executes one runtime operation and maps it to an outcome. Journal
// steps reached mid-write are pushed back through the loop as OpProgress, so
// the record is on disk before the writer proceeds (write-before-act).
type opRunner struct {
	adapter runtime.Adapter
	store   *store.Store
	push    func(reactor.Event)
	now     func() time.Time
}

func (r *opRunner) Run(ctx context.Context, op reactor.RunOp) runtime.OpResult {
	p, err := r.adapter.Resolve(ctx, op.BindingID)
	if err != nil {
		return refusedResult(op, err)
	}
	ctx, cancel := context.WithDeadline(ctx, op.Deadline)
	defer cancel()

	switch op.Op {
	case msg.OpPreflight:
		pr, err := r.adapter.Preflight(ctx, p)
		if err != nil {
			return runtime.OpResult{Outcome: msg.OutcomePreflightRefused, Detail: msg.AckDetail{Message: err.Error()}, Placement: &p}
		}
		if !pr.Writable {
			return runtime.OpResult{Outcome: msg.OutcomePreflightRefused, Detail: msg.AckDetail{Message: "config directory is not writable", PreflightResult: &pr}, Placement: &p}
		}
		facts, _ := r.adapter.Observe(ctx, p)
		return runtime.OpResult{Outcome: msg.OutcomePreflightOK, Detail: msg.AckDetail{PreflightResult: &pr}, Facts: &facts, Placement: &p}

	case msg.OpApply:
		payload, err := os.ReadFile(r.store.Path(op.PayloadRel))
		if err != nil {
			return runtime.OpResult{Outcome: msg.OutcomeFailedNoEffect, Detail: msg.AckDetail{Message: "payload unreadable"}, Placement: &p}
		}
		prev := r.store.Path(commands.PreviousRel(op.BindingID, generationOf(op)))
		res, err := r.adapter.WriteConfig(ctx, p, payload, op.SHA256, prev, func(step msg.ProgressStep) error {
			r.push(reactor.OpProgress{At: r.now(), CommandID: op.CommandID, Step: step})
			return nil
		})
		facts, _ := r.adapter.Observe(ctx, p)
		if err != nil {
			var we *configwriter.WriteError
			outcome := msg.OutcomeFailedNoEffect
			if errors.As(err, &we) {
				switch we.Phase {
				case configwriter.PhaseAfterRenameRolledBack:
					outcome = msg.OutcomeFailedRolledBack
				case configwriter.PhaseAfterRenameUnknown:
					outcome = msg.OutcomeApplyIndeterminate
				}
			}
			return runtime.OpResult{Outcome: outcome, Detail: msg.AckDetail{Message: err.Error()}, Facts: &facts, Placement: &p}
		}
		return runtime.OpResult{Outcome: msg.OutcomeApplied, EffectiveHash: res.Hash, Facts: &facts, Placement: &p}

	case msg.OpRestart, msg.OpStop:
		before, _ := r.adapter.Observe(ctx, p)
		var opErr error
		if op.Op == msg.OpRestart {
			opErr = r.adapter.Restart(ctx, p)
		} else {
			opErr = r.adapter.Stop(ctx, p)
		}
		after, obsErr := r.adapter.Observe(ctx, p)
		if obsErr != nil {
			return runtime.OpResult{Outcome: msg.OutcomeApplyIndeterminate, Detail: msg.AckDetail{Message: "post-state unobservable"}, Placement: &p}
		}
		if reached(op.Op, before, after) {
			return runtime.OpResult{Outcome: msg.OutcomeApplied, Facts: &after, Placement: &p}
		}
		if opErr != nil {
			return runtime.OpResult{Outcome: msg.OutcomeFailedNoEffect, Detail: msg.AckDetail{Message: opErr.Error()}, Facts: &after, Placement: &p}
		}
		// AG-D19: the verb reported success but the post-state is not the one
		// asked for. AG-D5 has no outcome for "changed and still failed", so
		// the honest answer is a failure plus the observed facts in the report.
		return runtime.OpResult{Outcome: msg.OutcomeFailedNoEffect, Detail: msg.AckDetail{Message: "post-state not reached"}, Facts: &after, Placement: &p}
	}
	return runtime.OpResult{Outcome: msg.OutcomeRefused, Detail: msg.AckDetail{Message: "unknown operation"}}
}

func reached(op msg.Operation, before, after runtime.ReplicaFacts) bool {
	if op == msg.OpStop {
		return !after.Running
	}
	return after.Running && after.StartedAt.After(before.StartedAt)
}

func refusedResult(op reactor.RunOp, err error) runtime.OpResult {
	outcome := msg.OutcomeFailedNoEffect
	if op.Op == msg.OpPreflight {
		outcome = msg.OutcomePreflightRefused
	}
	return runtime.OpResult{Outcome: outcome, Detail: msg.AckDetail{Message: err.Error()}}
}

// generationOf recovers the generation from the payload path the reducer set.
func generationOf(op reactor.RunOp) uint64 { return commands.GenerationFromRel(op.PayloadRel) }
```

Add to `internal/commands/journal.go`:

```go
// GenerationFromRel parses apply/<binding>/<gen>/payload.
func GenerationFromRel(rel string) uint64 {
	parts := strings.Split(rel, "/")
	if len(parts) < 3 {
		return 0
	}
	g, _ := strconv.ParseUint(parts[2], 10, 64)
	return g
}
```

Change `OpRunner` in `loop.go` to `Run(ctx context.Context, op reactor.RunOp) runtime.OpResult` (import `internal/runtime`) and the worker to `l.Push(reactor.OpFinished{At: l.d.Now(), CommandID: op.CommandID, Result: res})` unchanged.

`internal/agent/ops_test.go`:

```go
package agent

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/commands"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

type stubAdapter struct {
	runtime.Adapter
	place    runtime.Placement
	resolve  error
	facts    []runtime.ReplicaFacts
	restart  error
	writeErr error
	wrote    []byte
}

func (s *stubAdapter) Resolve(context.Context, string) (runtime.Placement, error) {
	return s.place, s.resolve
}
func (s *stubAdapter) Observe(context.Context, runtime.Placement) (runtime.ReplicaFacts, error) {
	f := s.facts[0]
	if len(s.facts) > 1 {
		s.facts = s.facts[1:]
	}
	return f, nil
}
func (s *stubAdapter) Restart(context.Context, runtime.Placement) error { return s.restart }
func (s *stubAdapter) WriteConfig(_ context.Context, _ runtime.Placement, payload []byte, _ string, _ string, p runtime.Progress) (runtime.WriteResult, error) {
	_ = p(msg.StepStaged)
	if s.writeErr != nil {
		return runtime.WriteResult{}, s.writeErr
	}
	s.wrote = payload
	_ = p(msg.StepPersisted)
	return runtime.WriteResult{Hash: "h"}, nil
}

func TestOpRunnerRestartNeedsNewStartedAt(t *testing.T) {
	t1 := time.Unix(100, 0)
	a := &stubAdapter{facts: []runtime.ReplicaFacts{{Running: true, StartedAt: t1}, {Running: true, StartedAt: t1}}}
	r := &opRunner{adapter: a, store: store.MustOpen(t.TempDir()), push: func(reactor.Event) {}, now: time.Now}
	res := r.Run(context.Background(), reactor.RunOp{Op: msg.OpRestart, Deadline: time.Now().Add(time.Minute)})
	if res.Outcome != msg.OutcomeFailedNoEffect {
		t.Fatalf("an unchanged StartedAt is not a restart: %+v", res)
	}
	a2 := &stubAdapter{facts: []runtime.ReplicaFacts{{Running: true, StartedAt: t1}, {Running: true, StartedAt: t1.Add(time.Second)}}}
	r2 := &opRunner{adapter: a2, store: store.MustOpen(t.TempDir()), push: func(reactor.Event) {}, now: time.Now}
	if res := r2.Run(context.Background(), reactor.RunOp{Op: msg.OpRestart, Deadline: time.Now().Add(time.Minute)}); res.Outcome != msg.OutcomeApplied {
		t.Fatalf("%+v", res)
	}
}

func TestOpRunnerApplyPushesProgress(t *testing.T) {
	st := store.MustOpen(t.TempDir())
	_ = st.Save(store.Blob{RelPath: commands.PayloadRel("b1", 3), Data: []byte("x")})
	a := &stubAdapter{facts: []runtime.ReplicaFacts{{Running: true}}}
	var steps []msg.ProgressStep
	r := &opRunner{adapter: a, store: st, now: time.Now,
		push: func(ev reactor.Event) { steps = append(steps, ev.(reactor.OpProgress).Step) }}
	res := r.Run(context.Background(), reactor.RunOp{CommandID: "c", BindingID: "b1", Op: msg.OpApply,
		PayloadRel: commands.PayloadRel("b1", 3), SHA256: "h", Deadline: time.Now().Add(time.Minute)})
	if res.Outcome != msg.OutcomeApplied || res.EffectiveHash != "h" {
		t.Fatalf("%+v", res)
	}
	if len(steps) != 2 || steps[0] != msg.StepStaged {
		t.Fatalf("%v", steps)
	}
	if string(a.wrote) != "x" {
		t.Fatal("payload not read from the store")
	}
}

func TestOpRunnerResolveFailureIsNoEffect(t *testing.T) {
	a := &stubAdapter{resolve: errors.New("no such container"), facts: []runtime.ReplicaFacts{{}}}
	r := &opRunner{adapter: a, store: store.MustOpen(t.TempDir()), push: func(reactor.Event) {}, now: time.Now}
	if res := r.Run(context.Background(), reactor.RunOp{Op: msg.OpApply}); res.Outcome != msg.OutcomeFailedNoEffect {
		t.Fatalf("%+v", res)
	}
	if res := r.Run(context.Background(), reactor.RunOp{Op: msg.OpPreflight}); res.Outcome != msg.OutcomePreflightRefused {
		t.Fatalf("%+v", res)
	}
}
```

- [ ] **Step 4: Wire commands into the state and the runner into Build**

In `internal/agent/state.go`: add `Commands commands.State` to `State`; in `Reduce`, after the authority dispatch:

```go
	s.Commands, effs = commands.Reduce(s.Commands, ev, env, s.Authority)
	all = append(all, effs...)
```

and set `in.JournalDepth = uint32(s.Commands.OpenCount())` before the session dispatch. In `LoadState`, `st.Commands, err = commands.Load(s)`.

In `internal/agent/wiring.go`: add `Adapter runtime.Adapter` to `BuildConfig`; in `Build`, `l.d.Ops = &opRunner{adapter: bc.Adapter, store: s, push: l.Push, now: l.d.Now}` when `bc.Adapter != nil`.

In `cmd/sm-agent/main.go`: construct the Docker adapter and pass it:

```go
	adapter, closeDocker, err := docker.NewFromEnv()
	if err != nil {
		log.Error("docker", "err", err)
		os.Exit(1)
	}
	defer closeDocker()
```

- [ ] **Step 5: Docker E2E (optional tag)**

`internal/runtime/docker/e2e_test.go`:

```go
//go:build docker

package docker

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

// TestE2EAgainstRealContainer needs a Docker socket. It creates a container
// labelled for binding "e2e-b1" with a bind-mounted config dir, then exercises
// Resolve → Preflight → WriteConfig → Restart → Observe.
func TestE2EAgainstRealContainer(t *testing.T) {
	hostDir := t.TempDir()
	if err := os.Chmod(hostDir, 0o777); err != nil {
		t.Fatal(err)
	}
	name := "sm-agent-e2e"
	_ = exec.Command("docker", "rm", "-f", name).Run()
	cmd := exec.Command("docker", "run", "-d", "--name", name,
		"--label", BindingLabel+"=e2e-b1",
		"-v", hostDir+":/etc/app",
		"alpine:3", "sh", "-c", "--spring.config.additional-location=file:/etc/app/ ; while true; do sleep 1; done")
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Skipf("docker unavailable: %v: %s", err, out)
	}
	t.Cleanup(func() { _ = exec.Command("docker", "rm", "-f", name).Run() })

	a, closeFn, err := NewFromEnv()
	if err != nil {
		t.Skipf("docker client: %v", err)
	}
	defer closeFn()
	ctx := context.Background()
	p, err := a.Resolve(ctx, "e2e-b1")
	if err != nil {
		t.Fatal(err)
	}
	if p.HostPath != filepath.Join(hostDir, configFileName) {
		t.Fatalf("%+v", p)
	}
	if _, err := a.Preflight(ctx, p); err != nil {
		t.Fatal(err)
	}
	payload := []byte("server.port: 8081\n")
	if _, err := a.WriteConfig(ctx, p, payload, shaOf(payload), filepath.Join(t.TempDir(), "prev"), func(msg.ProgressStep) error { return nil }); err != nil {
		t.Fatal(err)
	}
	st, err := os.Stat(p.HostPath)
	if err != nil || st.Mode().Perm() != 0o600 {
		t.Fatalf("%v %v", err, st)
	}
	before, _ := a.Observe(ctx, p)
	if err := a.Restart(ctx, p); err != nil {
		t.Fatal(err)
	}
	time.Sleep(time.Second)
	after, _ := a.Observe(ctx, p)
	if !after.Running || !after.StartedAt.After(before.StartedAt) {
		t.Fatalf("restart not observed: %v → %v", before.StartedAt, after.StartedAt)
	}
}
```

Add `func shaOf(b []byte) string { return configwriter.SHA256(b) }` to that file.

The `docker run` command above embeds the Spring argument in the container's command line so `declaredConfigDir` finds it; that is exactly the "deployment configuration is fair game, MI code is not" line `I1` draws.

- [ ] **Step 6: Run**

Run: `go build ./... && go test -race ./internal/... && go test -tags docker ./internal/runtime/docker/ -run E2E` (the last skips without a socket)
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add internal/runtime internal/agent internal/commands cmd/sm-agent
git commit -m "go-agent: Docker adapter with label-based binding resolution (AG-D20) and the op runner

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 20: P3 integration and crash/resume

**Files:**
- Create: `internal/agent/resume_test.go`
- Modify: `internal/agent/integration_test.go`, `internal/agent/inttest_helpers_test.go`

**Interfaces:**
- Produces: `fakeAdapter` (integration-scoped, writes to a temp dir); `crashStore` (a `*store.Store` wrapper that fails the Nth `Save`) for the crash tests.

- [ ] **Step 1: Crash/resume tests**

`internal/agent/resume_test.go`:

```go
package agent

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/commands"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/runtime/configwriter"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// A resume test drives the reducers directly: it writes a journal state to
// disk, reloads it, and asserts the AG-D4 table row.
func loadAfterCrash(t *testing.T, dir string) State {
	t.Helper()
	s, err := store.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	cs, err := commands.Load(s)
	if err != nil {
		t.Fatal(err)
	}
	return State{Commands: cs}
}

func TestResumeFromStagedCompletesTheWrite(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "d")
	s, _ := store.Open(dir)
	hostDir := t.TempDir()
	target := filepath.Join(hostDir, "application.yml")
	_ = os.WriteFile(target, []byte("old"), 0o644)
	payload := []byte("new: 1\n")
	hash := configwriter.SHA256(payload)
	_ = s.Save(store.Blob{RelPath: commands.PayloadRel("b1", 2), Data: payload})
	_ = s.Save(store.JSON{RelPath: "apply/b1/2.json", Value: commands.Record{SchemaVersion: 1, RolloutGeneration: 2,
		IdempotencyKey: "k", CommandID: "c1", BindingID: "b1", Operation: msg.OpApply, TargetPath: target,
		ConfigHash: hash, Step: msg.StepStaged, StepDeadline: time.Now().Add(time.Minute)}})

	st := loadAfterCrash(t, dir)
	_, effs := Reduce(cfg, st, resumedAt(time.Now()), testEnv())
	op, ok := has[runOpEffect](effs)
	if !ok || op.CommandID != "c1" {
		t.Fatalf("STAGED must re-enter the adapter: %+v", effs)
	}
	// the writer is idempotent: running it now lands the file exactly once
	res, err := configwriter.Write(target, payload, hash, filepath.Join(dir, "prev"), -1, -1, func(msg.ProgressStep) error { return nil })
	if err != nil || res.Hash != hash {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(target)
	if string(got) != string(payload) {
		t.Fatal("resume did not complete the write")
	}
}

func TestResumeFromPersistedDoesNotRewrite(t *testing.T) {
	hostDir := t.TempDir()
	target := filepath.Join(hostDir, "application.yml")
	payload := []byte("done\n")
	_ = os.WriteFile(target, payload, 0o600)
	before, _ := os.Stat(target)
	var steps []msg.ProgressStep
	_, err := configwriter.Write(target, payload, configwriter.SHA256(payload), filepath.Join(t.TempDir(), "prev"), -1, -1,
		func(s msg.ProgressStep) error { steps = append(steps, s); return nil })
	if err != nil {
		t.Fatal(err)
	}
	after, _ := os.Stat(target)
	if !before.ModTime().Equal(after.ModTime()) {
		t.Fatal("a PERSISTED target must be re-read, never rewritten")
	}
	if len(steps) != 1 || steps[0] != msg.StepPersisted {
		t.Fatalf("%v", steps)
	}
}

func TestResumeFromReceivedDiscardsThePartial(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "d")
	s, _ := store.Open(dir)
	_ = s.Save(store.Blob{RelPath: commands.PayloadRel("b1", 4), Data: []byte("x")})
	_ = s.Save(store.JSON{RelPath: "apply/b1/4.json", Value: commands.Record{SchemaVersion: 1, RolloutGeneration: 4,
		CommandID: "c1", BindingID: "b1", Operation: msg.OpApply, Step: msg.StepReceived, ConfigHash: "h"}})
	st := loadAfterCrash(t, dir)
	_, effs := Reduce(cfg, st, resumedAt(time.Now()), testEnv())
	var deleted []string
	for _, e := range effs {
		if d, ok := e.(deleteEffect); ok {
			deleted = append(deleted, d.Rel)
		}
	}
	if len(deleted) == 0 {
		t.Fatal("RECEIVED must be discarded; the DP redelivers")
	}
}

func TestResumeResendsUnsentTerminalAck(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "d")
	s, _ := store.Open(dir)
	_ = s.Save(store.JSON{RelPath: "apply/b1/6.json", Value: commands.Record{SchemaVersion: 1, RolloutGeneration: 6,
		CommandID: "c9", BindingID: "b1", Operation: msg.OpApply, Step: msg.StepReported, Outcome: msg.OutcomeApplied, AckSent: false}})
	st := loadAfterCrash(t, dir)
	_, effs := Reduce(cfg, st, resumedAt(time.Now()), testEnv())
	ack := sentMsg[msg.CommandAck](t, effs)
	if ack.Outcome != msg.OutcomeApplied || ack.CommandID != "c9" {
		t.Fatalf("%+v", ack)
	}
}
```

(The "a journal that cannot be written is fatal" property is already covered by `TestLoopPersistFailureIsFatal` from Task 8; do not add a second test for it. Drop the `runtime` import from this file — it is unused.)

Define these shared test helpers once, in `internal/agent/state_test.go`:

```go
type runOpEffect = reactor.RunOp
type deleteEffect = reactor.Delete

func resumedAt(t time.Time) reactor.Event { return reactor.Resumed{At: t} }

func sentMsg[M msg.Outbound](t *testing.T, effs []reactor.Effect) M {
	t.Helper()
	for _, e := range effs {
		if s, ok := e.(reactor.Send); ok {
			if m, ok := s.Msg.(M); ok {
				return m
			}
		}
	}
	t.Fatalf("no %T sent", *new(M))
	panic("unreachable")
}
```

- [ ] **Step 2: Integration adapter and scenarios**

Add to `internal/agent/inttest_helpers_test.go`:

```go
// fakeAdapter is a filesystem-only runtime: it writes where the test can see
// it and simulates restarts by advancing StartedAt.
type fakeAdapter struct {
	dir       string
	started   time.Time
	running   bool
	failWrite error
}

func newFakeAdapter(t *testing.T) *fakeAdapter {
	return &fakeAdapter{dir: t.TempDir(), started: time.Now(), running: true}
}

func (a *fakeAdapter) target() string { return filepath.Join(a.dir, "application.yml") }

func (a *fakeAdapter) Resolve(_ context.Context, binding string) (runtime.Placement, error) {
	return runtime.Placement{BindingID: binding, ContainerID: "container-abc", Name: "java1",
		ConfigPathInContainer: "/etc/app/application.yml", HostPath: a.target(), Mechanism: "additional-location", UID: -1, GID: -1}, nil
}

func (a *fakeAdapter) Preflight(_ context.Context, p runtime.Placement) (msg.PreflightResult, error) {
	return msg.PreflightResult{ContainerID: p.ContainerID, ImageDigest: "sha256:test", ConfigPathInContainer: p.ConfigPathInContainer,
		HostPath: p.HostPath, Mechanism: p.Mechanism, Writable: true}, nil
}

func (a *fakeAdapter) WriteConfig(_ context.Context, p runtime.Placement, payload []byte, sha string, prev string, pr runtime.Progress) (runtime.WriteResult, error) {
	if a.failWrite != nil {
		_ = pr(msg.StepStaged)
		return runtime.WriteResult{}, a.failWrite
	}
	return configwriter.Write(p.HostPath, payload, sha, prev, -1, -1, pr)
}

func (a *fakeAdapter) Restart(context.Context, runtime.Placement) error {
	a.started = time.Now().Add(time.Second)
	a.running = true
	return nil
}

func (a *fakeAdapter) Stop(context.Context, runtime.Placement) error { a.running = false; return nil }

func (a *fakeAdapter) Observe(context.Context, runtime.Placement) (runtime.ReplicaFacts, error) {
	return runtime.ReplicaFacts{ReplicaRef: "java1", Incarnation: "container-abc", Running: a.running,
		StartedAt: a.started, ImageDigest: "sha256:test"}, nil
}
```

and give `startAgent` an adapter: add `adapter runtime.Adapter` to the mutate closure's reach by setting `bc.Adapter = ad` in a new variant:

```go
func startAgentWith(t *testing.T, gw *fakegw.Server, dir string, ad runtime.Adapter) *agentHandle {
	return startAgent(t, gw, dir, func(bc *BuildConfig) { bc.Adapter = ad })
}
```

Append to `internal/agent/integration_test.go`:

```go
func applyScenario(payload string) *fakegw.Scenario {
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{
		{Offer: &fakegw.OfferStep{CommandID: "cmd-pf", BindingID: "binding-1", Generation: 1, Operation: "preflight"}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
		{Offer: &fakegw.OfferStep{CommandID: "cmd-ap", BindingID: "binding-1", Generation: 1, Operation: "apply", Payload: payload, IdempotencyKey: "k1"}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
		{Offer: &fakegw.OfferStep{CommandID: "cmd-rs", BindingID: "binding-1", Generation: 1, Operation: "restart"}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
	}
	return sc
}

func acksByCommand(gw *fakegw.Server) map[string]msg.CommandAck {
	out := map[string]msg.CommandAck{}
	for _, m := range gw.Received("CommandAck") {
		a := m.(msg.CommandAck)
		out[a.CommandID] = a
	}
	return out
}

func TestIntegrationPreflightApplyRestart(t *testing.T) {
	payload := "server.port: 8081\n"
	gw := startGateway(t, applyScenario(payload))
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 3, 30*time.Second) {
		t.Fatalf("expected three acks, got %d", gw.Count("CommandAck"))
	}
	acks := acksByCommand(gw)
	if acks["cmd-pf"].Outcome != msg.OutcomePreflightOK || acks["cmd-pf"].Detail.PreflightResult == nil {
		t.Fatalf("preflight: %+v", acks["cmd-pf"])
	}
	if acks["cmd-ap"].Outcome != msg.OutcomeApplied {
		t.Fatalf("apply: %+v", acks["cmd-ap"])
	}
	if acks["cmd-rs"].Outcome != msg.OutcomeApplied {
		t.Fatalf("restart: %+v", acks["cmd-rs"])
	}
	got, err := os.ReadFile(ad.target())
	if err != nil || string(got) != payload {
		t.Fatalf("config not written: %v %q", err, got)
	}
	st, _ := os.Stat(ad.target())
	if st.Mode().Perm() != 0o600 {
		t.Fatalf("mode %o", st.Mode().Perm())
	}
	// delivery and outcome are two acks, and progress was reported
	if gw.Count("CommandReceived") != 3 || gw.Count("ApplyProgress") < 6 {
		t.Fatalf("received=%d progress=%d", gw.Count("CommandReceived"), gw.Count("ApplyProgress"))
	}
}

func TestIntegrationDuplicateOfferIsAnsweredFromTheJournal(t *testing.T) {
	payload := "a: 1\n"
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{
		{Offer: &fakegw.OfferStep{CommandID: "cmd-1", BindingID: "binding-1", Generation: 1, Operation: "apply", Payload: payload, IdempotencyKey: "k1"}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
		{Offer: &fakegw.OfferStep{CommandID: "cmd-1", BindingID: "binding-1", Generation: 1, Operation: "apply", Payload: payload, IdempotencyKey: "k1"}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
	}
	gw := startGateway(t, sc)
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 2, 30*time.Second) {
		t.Fatal("no second ack")
	}
	for _, m := range gw.Received("CommandAck") {
		if a := m.(msg.CommandAck); a.Outcome != msg.OutcomeApplied {
			t.Fatalf("both acks are APPLIED (the second replayed): %+v", a)
		}
	}
	// executed at most once: exactly one apply reached the adapter
	if n := gw.Count("ApplyProgress"); n > 8 {
		t.Fatalf("looks like it ran twice: %d progress messages", n)
	}
}

func TestIntegrationHashMismatchRefused(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{
		{Offer: &fakegw.OfferStep{CommandID: "cmd-bad", BindingID: "binding-1", Generation: 1, Operation: "apply", Payload: "x", BadHash: true}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
	}
	gw := startGateway(t, sc)
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 1, 20*time.Second) {
		t.Fatal("no ack")
	}
	a := gw.Received("CommandAck")[0].(msg.CommandAck)
	if a.Outcome != msg.OutcomeRefused || a.Detail.Code != msg.DetailOf(msg.ErrKeyHashMismatch) {
		t.Fatalf("%+v", a)
	}
	if _, err := os.Stat(ad.target()); err == nil {
		t.Fatal("nothing may be written for a mismatched hash")
	}
}

func TestIntegrationSupersededPullIsRecordedNotActed(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Steps = []fakegw.Step{
		{Offer: &fakegw.OfferStep{CommandID: "cmd-old", BindingID: "binding-1", Generation: 1, Operation: "apply", Payload: "old\n"}},
		{Superseded: strPtr("cmd-old")},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
	}
	gw := startGateway(t, sc)
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 1, 20*time.Second) {
		t.Fatal("no ack")
	}
	if a := gw.Received("CommandAck")[0].(msg.CommandAck); a.Outcome != msg.OutcomeSuperseded {
		t.Fatalf("%+v", a)
	}
	if _, err := os.Stat(ad.target()); err == nil {
		t.Fatal("a superseded command must not be applied")
	}
}

func TestIntegrationCapabilityMissingRefusedLocally(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Lease.Capabilities = []string{"apply-config"} // no lifecycle
	sc.Steps = []fakegw.Step{
		{Offer: &fakegw.OfferStep{CommandID: "cmd-rs", BindingID: "binding-1", Generation: 1, Operation: "restart"}},
		{Expect: &fakegw.ExpectStep{Kind: "CommandAck", Timeout: 20 * time.Second}},
	}
	gw := startGateway(t, sc)
	h := startAgentWith(t, gw, "", newFakeAdapter(t))
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 1, 20*time.Second) {
		t.Fatal("no ack")
	}
	a := gw.Received("CommandAck")[0].(msg.CommandAck)
	if a.Outcome != msg.OutcomeRefused || a.Detail.Code != msg.DetailOf(msg.ErrCapabilityMissing) {
		t.Fatalf("%+v", a)
	}
	if gw.Count("CommandPull") != 0 {
		t.Fatal("a command the lease cannot authorize is refused without pulling it")
	}
}

func TestIntegrationApplyRestartSurvivesRestartOfTheAgent(t *testing.T) {
	payload := "b: 2\n"
	gw := startGateway(t, applyScenario(payload))
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("CommandAck", 3, 30*time.Second) {
		t.Fatal("first run did not finish")
	}
	h.stop()
	h2 := startAgentWith(t, gw, h.dir, ad)
	waitPhase(t, h2.loop, PhaseActive, 10*time.Second)
	// the journal is terminal for every command, so nothing re-runs and the
	// file is untouched
	got, _ := os.ReadFile(ad.target())
	if string(got) != payload {
		t.Fatalf("%q", got)
	}
	if h2.loop.Snapshot().Commands.OpenCount() != 0 {
		t.Fatal("no open records after a clean finish")
	}
}
```

Add `"os"`, `"path/filepath"`, `"github.com/solace/solution-manager/go-agent/internal/runtime"` and `".../internal/runtime/configwriter"` to the helper file's imports.

- [ ] **Step 3: Run**

Run: `go test -race ./internal/... && go test -race -tags integration ./internal/agent/ -run Integration -v`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add internal/agent
git commit -m "go-agent: P3 integration — preflight/apply/restart, idempotent redelivery, refusals, crash resume

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

**Phase P3 exit:** a config file lands at the declared path at mode 0600 through the wire; every `AG-D4` resume row and `AG-D5` outcome has a test.

---

## Phase P4 — actual-state reports

### Task 21: The `reports` reducer (`AG-D17`, `AG-D18`)

**Files:**
- Create: `internal/reports/reports.go`, `internal/reports/reports_test.go`

**Interfaces:**
- Produces:

```go
type Cursor struct {
	SchemaVersion int               `json:"schema_version"` // 1
	Sequences     map[string]uint64 `json:"sequences"`      // binding|replica_ref → last issued
}
type Pending struct {
	SchemaVersion int                     `json:"schema_version"`
	Items         []msg.ActualStateReport `json:"items"`
	SentMessageID string                  `json:"sent_message_id,omitempty"`
}
type Registry struct {
	SchemaVersion int               `json:"schema_version"`
	ReplicaIDs    map[string]string `json:"replica_ids"`    // binding|replica_ref → DP-minted stable id
	IncarnationIDs map[string]string `json:"incarnation_ids"` // binding|replica_ref|incarnation → DP-minted id
}
type Observation struct {
	BindingID, ReplicaRef, Incarnation string
	PersistedGeneration uint64
	RestartObservedAt   *time.Time
	EffectiveHash       string
	VersionObservation  msg.VersionObservation
}
type State struct { Cursor Cursor; Pending Pending; Registry Registry; lastSeen map[string]Observation }
func Load(s *store.Store) (State, error)
func Reduce(s State, ev reactor.Event, env reactor.Env) (State, []reactor.Effect)
func (s State) PendingCount() int
func (s State) ReplicaID(binding, replicaRef string) string
func (s State) IncarnationID(binding, replicaRef, incarnation string) string
```

- Observations reach the reducer through `reactor.OpFinished` (the runtime returned facts) and a new `reactor.Observed{At, Obs Observation}` event the loop emits on the report timer.
- `sequence` is written to the cursor **before** the item exists; a missing cursor reseeds from `dpNow.UnixNano()`.

- [ ] **Step 1: Reactor addition**

In `internal/reactor/reactor.go` add:

```go
// Observed carries a runtime observation the report reducer turns into an
// item. The loop produces it on the report timer and after every operation.
type Observed struct {
	At  time.Time
	Obs any // reports.Observation; typed as any to keep reactor free of reports
}

func (e Observed) EventAt() time.Time { return e.At }
```

`reports` type-asserts it. (`reports` imports `reactor`; the reverse would cycle.)

- [ ] **Step 2: Failing tests**

`internal/reports/reports_test.go`:

```go
package reports

import (
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

var t0 = time.Date(2026, 9, 22, 10, 0, 0, 0, time.UTC)

func env(now time.Time) reactor.Env {
	n := 0
	return reactor.Env{Now: now, NewID: func() string { n++; return "id-" + time.Duration(n).String() },
		Jitter: func(d time.Duration) time.Duration { return d }, Limits: msg.Limits{ReportInterval: time.Minute}}
}

func obs(gen uint64) Observation {
	return Observation{BindingID: "b1", ReplicaRef: "java1", Incarnation: "abc123", PersistedGeneration: gen,
		EffectiveHash: "h", VersionObservation: msg.VersionObservation{Mechanism: "image-digest", Value: "sha256:x", Confidence: "low"}}
}

func newState() State {
	return State{
		Cursor:   Cursor{SchemaVersion: 1, Sequences: map[string]uint64{}},
		Pending:  Pending{SchemaVersion: 1},
		Registry: Registry{SchemaVersion: 1, ReplicaIDs: map[string]string{}, IncarnationIDs: map[string]string{}},
	}
}

func sentBatch(t *testing.T, effs []reactor.Effect) msg.ActualStateReportBatch {
	t.Helper()
	for _, e := range effs {
		if s, ok := e.(reactor.Send); ok {
			if b, ok := s.Msg.(msg.ActualStateReportBatch); ok {
				return b
			}
		}
	}
	t.Fatal("no batch sent")
	panic("unreachable")
}

func persisted(effs []reactor.Effect) []string {
	var out []string
	for _, e := range effs {
		if p, ok := e.(reactor.Persist); ok {
			out = append(out, p.Rec.Rel())
		}
	}
	return out
}

func TestObservationMintsItemAndPersistsCursorBeforeSending(t *testing.T) {
	s, effs := Reduce(newState(), reactor.Observed{At: t0, Obs: obs(3)}, env(t0))
	rels := persisted(effs)
	if len(rels) < 2 || rels[0] != relCursor || rels[1] != relPending {
		t.Fatalf("cursor then pending, before the send: %v", rels)
	}
	b := sentBatch(t, effs)
	if len(b.Items) != 1 {
		t.Fatal(b.Items)
	}
	it := b.Items[0]
	if it.ItemID == "" || it.Sequence == 0 || it.BindingID != "b1" || it.PersistedGeneration != 3 {
		t.Fatalf("%+v", it)
	}
	if s.Cursor.Sequences["b1|java1"] != it.Sequence || len(s.Pending.Items) != 1 {
		t.Fatalf("%+v", s)
	}
}

func TestSequenceIsMonotonicPerReplica(t *testing.T) {
	s := newState()
	var last uint64
	for i := 0; i < 3; i++ {
		var effs []reactor.Effect
		s, effs = Reduce(s, reactor.Observed{At: t0, Obs: obs(uint64(i))}, env(t0))
		it := sentBatch(t, effs).Items[len(sentBatch(t, effs).Items)-1]
		if it.Sequence <= last {
			t.Fatalf("sequence went backwards: %d after %d", it.Sequence, last)
		}
		last = it.Sequence
	}
	other := obs(1)
	other.ReplicaRef = "java2"
	s2, effs := Reduce(s, reactor.Observed{At: t0, Obs: other}, env(t0))
	if s2.Cursor.Sequences["b1|java2"] == 0 {
		t.Fatal("a second replica has its own counter")
	}
	_ = effs
}

func TestItemIsImmutableAcrossResends(t *testing.T) {
	s, effs := Reduce(newState(), reactor.Observed{At: t0, Obs: obs(1)}, env(t0))
	first := sentBatch(t, effs).Items[0]
	// a reconnect re-sends the pending item unchanged
	s2, effs2 := Reduce(s, reactor.WireIn{At: t0.Add(time.Minute), Msg: msg.SessionAccept{Limits: msg.Limits{ReportInterval: time.Minute}}}, env(t0.Add(time.Minute)))
	resent := sentBatch(t, effs2).Items[0]
	if resent.ItemID != first.ItemID || resent.Sequence != first.Sequence || resent.PersistedGeneration != first.PersistedGeneration {
		t.Fatalf("resend must be byte-identical:\n%+v\n%+v", first, resent)
	}
	if b := sentBatch(t, effs2); b.MessageID == sentBatch(t, effs).MessageID {
		t.Fatal("the batch message_id is fresh per send; only the item is stable")
	}
	_ = s2
}

func TestAckClosesItemsAndStoresMintedIDs(t *testing.T) {
	s, effs := Reduce(newState(), reactor.Observed{At: t0, Obs: obs(1)}, env(t0))
	b := sentBatch(t, effs)
	ack := msg.ReportAck{InReplyToMessageID: b.MessageID, Results: []msg.ReportResult{{
		InReplyToItemID: b.Items[0].ItemID, Sequence: b.Items[0].Sequence, ReplicaID: "rep-1", IncarnationID: "inc-1",
		Trust: msg.TrustTrusted, Disposition: msg.DispApplied}}}
	s2, effs2 := Reduce(s, reactor.WireIn{At: t0, Msg: ack}, env(t0))
	if s2.PendingCount() != 0 {
		t.Fatal("acked items must close")
	}
	if s2.ReplicaID("b1", "java1") != "rep-1" || s2.IncarnationID("b1", "java1", "abc123") != "inc-1" {
		t.Fatal("DP-minted ids must be stored")
	}
	rels := persisted(effs2)
	if len(rels) < 2 {
		t.Fatalf("registry and pending must be persisted: %v", rels)
	}
}

func TestRefusedAckClosesItemWithoutIDs(t *testing.T) {
	s, effs := Reduce(newState(), reactor.Observed{At: t0, Obs: obs(1)}, env(t0))
	b := sentBatch(t, effs)
	ack := msg.ReportAck{InReplyToMessageID: b.MessageID, Results: []msg.ReportResult{{
		InReplyToItemID: b.Items[0].ItemID, Trust: msg.TrustTrusted, Disposition: msg.DispRefused, RefusedReason: msg.ErrReplicaCapExceeded}}}
	s2, _ := Reduce(s, reactor.WireIn{At: t0, Msg: ack}, env(t0))
	if s2.PendingCount() != 0 {
		t.Fatal("a refused item is closed, not retried forever")
	}
	if s2.ReplicaID("b1", "java1") != "" {
		t.Fatal("a refusal mints no ids")
	}
}

func TestUntrustedAckIsRecordedNotRetried(t *testing.T) {
	s, effs := Reduce(newState(), reactor.Observed{At: t0, Obs: obs(1)}, env(t0))
	b := sentBatch(t, effs)
	ack := msg.ReportAck{InReplyToMessageID: b.MessageID, Results: []msg.ReportResult{{
		InReplyToItemID: b.Items[0].ItemID, Trust: msg.TrustUntrusted, Disposition: msg.DispApplied}}}
	s2, _ := Reduce(s, reactor.WireIn{At: t0, Msg: ack}, env(t0))
	if s2.PendingCount() != 0 {
		t.Fatal("the agent never retries to change a trust label")
	}
}

func TestAckMatchesByItemIDNotPosition(t *testing.T) {
	s := newState()
	s, _ = Reduce(s, reactor.Observed{At: t0, Obs: obs(1)}, env(t0))
	second := obs(2)
	second.ReplicaRef = "java2"
	s, effs := Reduce(s, reactor.Observed{At: t0, Obs: second}, env(t0))
	b := sentBatch(t, effs)
	if len(b.Items) != 2 {
		t.Fatalf("both pending items travel in one batch: %d", len(b.Items))
	}
	// answer them in reverse order
	ack := msg.ReportAck{InReplyToMessageID: b.MessageID, Results: []msg.ReportResult{
		{InReplyToItemID: b.Items[1].ItemID, ReplicaID: "rep-2", Trust: msg.TrustTrusted, Disposition: msg.DispApplied},
		{InReplyToItemID: b.Items[0].ItemID, ReplicaID: "rep-1", Trust: msg.TrustTrusted, Disposition: msg.DispApplied},
	}}
	s2, _ := Reduce(s, reactor.WireIn{At: t0, Msg: ack}, env(t0))
	if s2.ReplicaID("b1", "java1") != "rep-1" || s2.ReplicaID("b1", "java2") != "rep-2" {
		t.Fatal("results must be matched by item id, never by position")
	}
}

func TestReportTimerFiresEvenWithNothingNew(t *testing.T) {
	s := newState()
	s, _ = Reduce(s, reactor.WireIn{At: t0, Msg: msg.SessionAccept{Limits: msg.Limits{ReportInterval: 30 * time.Second}}}, env(t0))
	_, effs := Reduce(s, reactor.TimerFired{At: t0.Add(30 * time.Second), Kind: reactor.ReportDue}, env(t0.Add(30*time.Second)))
	var armed bool
	for _, e := range effs {
		if a, ok := e.(reactor.ArmTimer); ok && a.Kind == reactor.ReportDue {
			armed = true
		}
	}
	if !armed {
		t.Fatal("AG-D18: the cadence must re-arm even when nothing changed")
	}
}

func TestSessionAcceptArmsReportTimer(t *testing.T) {
	_, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: msg.SessionAccept{Limits: msg.Limits{ReportInterval: 45 * time.Second}}}, env(t0))
	for _, e := range effs {
		if a, ok := e.(reactor.ArmTimer); ok && a.Kind == reactor.ReportDue && a.After == 45*time.Second {
			return
		}
	}
	t.Fatalf("report timer not armed: %+v", effs)
}

func TestCursorSurvivesRestartAndReseedsWhenLost(t *testing.T) {
	st, _ := store.Open(t.TempDir())
	s := newState()
	s, effs := Reduce(s, reactor.Observed{At: t0, Obs: obs(1)}, env(t0))
	for _, e := range effs {
		if p, ok := e.(reactor.Persist); ok {
			_ = st.Save(p.Rec)
		}
	}
	loaded, err := Load(st)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.Cursor.Sequences["b1|java1"] != s.Cursor.Sequences["b1|java1"] || loaded.PendingCount() != 1 {
		t.Fatalf("%+v", loaded)
	}
	// a lost data dir reseeds from DP time, so the new counter is above any old one
	fresh, _ := Load(store.MustOpen(t.TempDir()))
	f2, effs2 := Reduce(fresh, reactor.Observed{At: t0, Obs: obs(1)}, env(t0))
	seq := sentBatch(t, effs2).Items[0].Sequence
	if seq < uint64(t0.UnixNano()) {
		t.Fatalf("reseed must exceed any plausible previous counter: %d", seq)
	}
	_ = f2
}
```

- [ ] **Step 3: Implement**

`internal/reports/reports.go`:

```go
// Package reports owns actual-state reporting: item minting (AG-D17), the
// per-replica sequence counter, the periodic cadence (AG-D18) and the
// DP-minted id registry the telemetry role will label with.
package reports

import (
	"errors"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

const (
	relCursor   = "reports/cursor.json"
	relPending  = "reports/pending.json"
	relRegistry = "reports/registry.json"
)

type Cursor struct {
	SchemaVersion int               `json:"schema_version"`
	Sequences     map[string]uint64 `json:"sequences"`
}

func (c Cursor) Version() int { return c.SchemaVersion }

type Pending struct {
	SchemaVersion int                     `json:"schema_version"`
	Items         []msg.ActualStateReport `json:"items"`
	SentMessageID string                  `json:"sent_message_id,omitempty"`
}

func (p Pending) Version() int { return p.SchemaVersion }

type Registry struct {
	SchemaVersion  int               `json:"schema_version"`
	ReplicaIDs     map[string]string `json:"replica_ids"`
	IncarnationIDs map[string]string `json:"incarnation_ids"`
}

func (r Registry) Version() int { return r.SchemaVersion }

type Observation struct {
	BindingID           string
	ReplicaRef          string
	Incarnation         string
	PersistedGeneration uint64
	RestartObservedAt   *time.Time
	EffectiveHash       string
	VersionObservation  msg.VersionObservation
}

type State struct {
	Cursor   Cursor
	Pending  Pending
	Registry Registry
}

func replicaKey(binding, replica string) string { return binding + "|" + replica }
func incarnationKey(binding, replica, inc string) string {
	return binding + "|" + replica + "|" + inc
}

func Load(s *store.Store) (State, error) {
	st := State{
		Cursor:   Cursor{SchemaVersion: 1, Sequences: map[string]uint64{}},
		Pending:  Pending{SchemaVersion: 1},
		Registry: Registry{SchemaVersion: 1, ReplicaIDs: map[string]string{}, IncarnationIDs: map[string]string{}},
	}
	// The cursor and the pending list self-heal; a corrupt one is not fatal
	// (the counter reseeds, items are re-observed), unlike identity or apply.
	if c, err := store.LoadJSON[Cursor](s, relCursor, 1); err == nil {
		if c.Sequences == nil {
			c.Sequences = map[string]uint64{}
		}
		st.Cursor = c
	} else if !errors.Is(err, store.ErrNotFound) && !errors.Is(err, store.ErrSchemaVersion) {
		return st, err
	}
	if p, err := store.LoadJSON[Pending](s, relPending, 1); err == nil {
		st.Pending = p
	} else if !errors.Is(err, store.ErrNotFound) && !errors.Is(err, store.ErrSchemaVersion) {
		return st, err
	}
	if r, err := store.LoadJSON[Registry](s, relRegistry, 1); err == nil {
		if r.ReplicaIDs == nil {
			r.ReplicaIDs = map[string]string{}
		}
		if r.IncarnationIDs == nil {
			r.IncarnationIDs = map[string]string{}
		}
		st.Registry = r
	} else if !errors.Is(err, store.ErrNotFound) && !errors.Is(err, store.ErrSchemaVersion) {
		return st, err
	}
	return st, nil
}

func (s State) PendingCount() int { return len(s.Pending.Items) }

func (s State) ReplicaID(binding, replicaRef string) string {
	return s.Registry.ReplicaIDs[replicaKey(binding, replicaRef)]
}

func (s State) IncarnationID(binding, replicaRef, incarnation string) string {
	return s.Registry.IncarnationIDs[incarnationKey(binding, replicaRef, incarnation)]
}

func Reduce(s State, ev reactor.Event, env reactor.Env) (State, []reactor.Effect) {
	switch e := ev.(type) {
	case reactor.Observed:
		obs, ok := e.Obs.(Observation)
		if !ok {
			return s, nil
		}
		return s.observe(obs, env)

	case reactor.TimerFired:
		if e.Kind != reactor.ReportDue {
			return s, nil
		}
		// AG-D18: the cadence runs whether or not anything changed; the loop
		// supplies a fresh Observed alongside this tick when it can.
		effs := s.flush(env)
		return s, append(effs, reactor.ArmTimer{Kind: reactor.ReportDue, After: env.Limits.ReportInterval})

	case reactor.WireIn:
		switch m := e.Msg.(type) {
		case msg.SessionAccept:
			// Boot order: a full report precedes any telemetry, and the pending
			// list is re-sent on every new session.
			effs := s.flush(env)
			after := m.Limits.ReportInterval
			if after <= 0 {
				after = env.Limits.ReportInterval
			}
			return s, append(effs, reactor.ArmTimer{Kind: reactor.ReportDue, After: after})
		case msg.ReportAck:
			return s.ack(m, env)
		}
	}
	return s, nil
}

// observe mints an immutable item: the counter is persisted before the item
// exists, so a crash cannot reissue a sequence (AG-D17).
func (s State) observe(o Observation, env reactor.Env) (State, []reactor.Effect) {
	k := replicaKey(o.BindingID, o.ReplicaRef)
	next := s.Cursor.Sequences[k] + 1
	if next == 1 {
		// no cursor for this replica: seed from DP time so any previous
		// counter (from a lost data dir) is necessarily lower
		next = uint64(env.DPNow().UnixNano())
	}
	s.Cursor.Sequences[k] = next
	item := msg.ActualStateReport{
		ItemID: env.NewID(), Sequence: next, BindingID: o.BindingID, ReplicaRef: o.ReplicaRef, Incarnation: o.Incarnation,
		PersistedGeneration: o.PersistedGeneration, RestartObservedAt: o.RestartObservedAt, EffectiveHash: o.EffectiveHash,
		VersionObservation: o.VersionObservation,
	}
	s.Pending.Items = append(s.Pending.Items, item)
	effs := []reactor.Effect{
		reactor.Persist{Rec: store.JSON{RelPath: relCursor, Value: s.Cursor}},
		reactor.Persist{Rec: store.JSON{RelPath: relPending, Value: s.Pending}},
	}
	return s, append(effs, s.send(env)...)
}

// flush re-sends everything still pending, unchanged.
func (s State) flush(env reactor.Env) []reactor.Effect {
	if len(s.Pending.Items) == 0 {
		return nil
	}
	return s.send(env)
}

func (s State) send(env reactor.Env) []reactor.Effect {
	items := make([]msg.ActualStateReport, len(s.Pending.Items))
	copy(items, s.Pending.Items)
	return []reactor.Effect{reactor.Send{Msg: msg.ActualStateReportBatch{Envelope: env.Env(), Items: items}}}
}

func (s State) ack(m msg.ReportAck, env reactor.Env) (State, []reactor.Effect) {
	if len(m.Results) == 0 {
		return s, nil
	}
	answered := map[string]msg.ReportResult{}
	for _, r := range m.Results {
		answered[r.InReplyToItemID] = r
	}
	byItem := map[string]msg.ActualStateReport{}
	for _, it := range s.Pending.Items {
		byItem[it.ItemID] = it
	}
	var remaining []msg.ActualStateReport
	changedRegistry := false
	for _, it := range s.Pending.Items {
		r, ok := answered[it.ItemID]
		if !ok {
			remaining = append(remaining, it)
			continue
		}
		// Every answered item is closed: APPLIED, STALE, REFUSED and an
		// UNTRUSTED verdict alike. The agent never retries to change a label.
		if r.ReplicaID != "" {
			s.Registry.ReplicaIDs[replicaKey(it.BindingID, it.ReplicaRef)] = r.ReplicaID
			changedRegistry = true
		}
		if r.IncarnationID != "" {
			s.Registry.IncarnationIDs[incarnationKey(it.BindingID, it.ReplicaRef, it.Incarnation)] = r.IncarnationID
			changedRegistry = true
		}
	}
	s.Pending.Items = remaining
	effs := []reactor.Effect{reactor.Persist{Rec: store.JSON{RelPath: relPending, Value: s.Pending}}}
	if changedRegistry {
		effs = append([]reactor.Effect{reactor.Persist{Rec: store.JSON{RelPath: relRegistry, Value: s.Registry}}}, effs...)
	}
	return s, effs
}
```

- [ ] **Step 4: Run**

Run: `go test ./internal/reports/ ./internal/reactor/`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add internal/reports internal/reactor
git commit -m "go-agent: reports reducer — immutable item_id, persisted sequence, periodic cadence, DP-minted id registry

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 22: Wire `reports` into the loop and P4 integration

**Files:**
- Modify: `internal/agent/state.go`, `internal/agent/loop.go`, `internal/agent/ops.go`, `internal/agent/integration_test.go`

**Interfaces:**
- `State` gains `Reports reports.State`; `Reduce` dispatches it last.
- The loop turns an `OpFinished` carrying facts into a `reactor.Observed` (pushed after the event is reduced, so the journal is terminal first), and on `ReportDue` asks the adapter for fresh facts for every binding in the lease.

- [ ] **Step 1: State**

In `internal/agent/state.go`: add `Reports reports.State`; after the commands dispatch:

```go
	s.Reports, effs = reports.Reduce(s.Reports, ev, env)
	all = append(all, effs...)
```

and in `LoadState`, `st.Reports, err = reports.Load(s)`.

- [ ] **Step 2: Observation plumbing**

In `internal/agent/loop.go`, after `l.execute(...)` succeeds, add:

```go
		if obs, ok := l.observationFor(ev, next); ok {
			l.Push(reactor.Observed{At: l.d.Now(), Obs: obs})
		}
```

and implement:

```go
// observationFor turns a finished operation into an actual-state observation.
// It runs after the event was reduced, so the journal record is terminal and
// PersistedGeneration is the durable one.
func (l *Loop) observationFor(ev reactor.Event, st State) (reports.Observation, bool) {
	fin, ok := ev.(reactor.OpFinished)
	if !ok || fin.Result.Facts == nil || fin.Result.Placement == nil {
		return reports.Observation{}, false
	}
	f, p := fin.Result.Facts, fin.Result.Placement
	obs := reports.Observation{
		BindingID: p.BindingID, ReplicaRef: f.ReplicaRef, Incarnation: f.Incarnation,
		PersistedGeneration: st.Commands.PersistedGeneration(p.BindingID),
		EffectiveHash:       fin.Result.EffectiveHash,
		VersionObservation:  versionOf(f),
	}
	if f.Running && !f.StartedAt.IsZero() {
		at := f.StartedAt.Add(l.d.Clock.Offset()) // host time → DP time
		obs.RestartObservedAt = &at
	}
	return obs, true
}

// versionOf reports what the runtime shows and nothing more (AG-D12).
func versionOf(f *runtime.ReplicaFacts) msg.VersionObservation {
	if f.ImageDigest == "" {
		return msg.VersionObservation{Mechanism: "none", Confidence: "none"}
	}
	return msg.VersionObservation{Mechanism: "image-digest", Value: f.ImageDigest, Confidence: "low"}
}
```

For the periodic cadence, in the `reactor.TimerFired` branch of the executor add: when `e.Kind == reactor.ReportDue` and an adapter is configured, run the observation off the loop:

```go
		case reactor.ArmTimer:
			l.arm(e.Kind, e.After)
```

stays as is; instead, in `Run`, alongside `observationFor`, add:

```go
		if tf, ok := ev.(reactor.TimerFired); ok && tf.Kind == reactor.ReportDue {
			go l.observeAll(ctx, next)
		}
```

and:

```go
// observeAll asks the adapter for current facts for every binding the lease
// names, so a healthy unchanged workload still reports (AG-D18 / AGW-19).
func (l *Loop) observeAll(ctx context.Context, st State) {
	if l.d.Adapter == nil || st.Authority.Grant == nil {
		return
	}
	for _, binding := range st.Authority.Grant.BindingIDs {
		p, err := l.d.Adapter.Resolve(ctx, binding)
		if err != nil {
			l.d.Log.Warn("periodic report: resolve", "binding", binding, "err", err)
			continue
		}
		f, err := l.d.Adapter.Observe(ctx, p)
		if err != nil {
			l.d.Log.Warn("periodic report: observe", "binding", binding, "err", err)
			continue
		}
		obs := reports.Observation{BindingID: binding, ReplicaRef: f.ReplicaRef, Incarnation: f.Incarnation,
			PersistedGeneration: st.Commands.PersistedGeneration(binding), VersionObservation: versionOf(&f)}
		if f.Running && !f.StartedAt.IsZero() {
			at := f.StartedAt.Add(l.d.Clock.Offset()) // host time → DP time
			obs.RestartObservedAt = &at
		}
		l.Push(reactor.Observed{At: l.d.Now(), Obs: obs})
	}
}
```

Add `Adapter runtime.Adapter` to `Deps` (set from `BuildConfig.Adapter` in `Build`) and the imports for `reports`/`runtime`.

- [ ] **Step 3: P4 integration**

Append to `internal/agent/integration_test.go`:

```go
func TestIntegrationReportsAfterApplyAndReceivesMintedIDs(t *testing.T) {
	payload := "c: 3\n"
	gw := startGateway(t, applyScenario(payload))
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("ActualStateReportBatch", 1, 30*time.Second) {
		t.Fatal("no report")
	}
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		st := h.loop.Snapshot()
		if st.Reports.ReplicaID("binding-1", "java1") != "" && st.Reports.PendingCount() == 0 {
			b := gw.Received("ActualStateReportBatch")[0].(msg.ActualStateReportBatch)
			if len(b.Items) == 0 || b.Items[0].ItemID == "" || b.Items[0].Sequence == 0 {
				t.Fatalf("%+v", b)
			}
			if b.Items[0].VersionObservation.Mechanism != "image-digest" {
				t.Fatalf("version observation: %+v", b.Items[0].VersionObservation)
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("ids never landed: pending=%d", h.loop.Snapshot().Reports.PendingCount())
}

func TestIntegrationPeriodicReportWithNothingChanged(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Limits.ReportInterval = 300 * time.Millisecond
	gw := startGateway(t, sc)
	h := startAgentWith(t, gw, "", newFakeAdapter(t))
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("ActualStateReportBatch", 3, 20*time.Second) {
		t.Fatalf("AG-D18: a healthy unchanged workload must keep reporting; got %d", gw.Count("ActualStateReportBatch"))
	}
}

func TestIntegrationReportRetryAfterCrashReusesItemID(t *testing.T) {
	sc := fakegw.DefaultScenario()
	sc.Policy.Report = fakegw.ReportPolicy{Mode: "silent"} // never ack → the item stays pending
	sc.Limits.ReportInterval = 500 * time.Millisecond
	gw := startGateway(t, sc)
	ad := newFakeAdapter(t)
	h := startAgentWith(t, gw, "", ad)
	waitPhase(t, h.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("ActualStateReportBatch", 1, 20*time.Second) {
		t.Fatal("no report")
	}
	first := gw.Received("ActualStateReportBatch")[0].(msg.ActualStateReportBatch).Items[0]
	h.stop()

	// Same gateway, same data dir: the pending item must come back unchanged.
	h2 := startAgentWith(t, gw, h.dir, ad)
	waitPhase(t, h2.loop, PhaseActive, 10*time.Second)
	if !gw.WaitFor("ActualStateReportBatch", 2, 20*time.Second) {
		t.Fatal("pending item was not re-sent after restart")
	}
	for _, m := range gw.Received("ActualStateReportBatch") {
		for _, it := range m.(msg.ActualStateReportBatch).Items {
			if it.ItemID == first.ItemID && it.Sequence != first.Sequence {
				t.Fatal("a re-sent item must be byte-identical")
			}
		}
	}
	var found bool
	for _, m := range gw.Received("ActualStateReportBatch")[1:] {
		for _, it := range m.(msg.ActualStateReportBatch).Items {
			if it.ItemID == first.ItemID {
				found = true
			}
		}
	}
	if !found {
		t.Fatal("AG-D17: the same item_id must survive a restart")
	}
}
```

- [ ] **Step 4: Run**

Run: `go test -race ./internal/... && go test -race -tags integration ./internal/agent/ -run Integration -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add internal/agent
git commit -m "go-agent: wire reports into the loop; periodic and post-apply observations

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

**Phase P4 exit:** reports carry stable `item_id`s and monotonic sequences across restarts, the DP's minted ids are stored, and the cadence runs with nothing changed.

---

## Phase P5 — decisions and docs

### Task 23: Record `AG-D13`–`AG-D20`

**Files:**
- Modify: `docs/design/go-agent/decisions.md`

The repo owns decisions (root `CLAUDE.md`); this task is the one that makes the code's choices citable. Append to the decision table and add a body per decision, in the style of the existing `AG-D1`–`AG-D12` entries.

- [ ] **Step 1: Extend the table**

Add these rows to the `AG-D*` table in `docs/design/go-agent/decisions.md`:

```markdown
| `AG-D13` | The agent↔DP `.proto` is DRAFTed in-repo under `go-agent/proto/agentgateway/v1draft/`, transcribed 1:1 from the agent-gateway design §4.3/§4.11; only `internal/wire` imports the generated code | PROPOSED |
| `AG-D14` | The agent is an event-driven reactor: one inbox, pure reducers `session → authority → commands → reports`, effects executed `Persist → Delete → Send → RunOp → timers`, one runtime worker | PROPOSED |
| `AG-D15` | `internal/` is rewritten against the gateway design; the superseded packages, the Docker-lifecycle and telemetry-collect demos and the `java-demo` fixtures are **archived** under `go-agent/oldGo-Agent/`, not deleted | PROPOSED |
| `AG-D16` | The DP test double is a scenario-driven **fake gateway** at wire level, shared by tests and a binary; it is not evidence about the plane | PROPOSED |
| `AG-D17` | `item_id` is a UUIDv7 minted once per report item and immutable across retries; `sequence` is a per-`(binding, replica)` counter persisted before use and reseeded from DP time when lost — the agent's answer to `AGW-20` | PROPOSED |
| `AG-D18` | A full `ActualStateReport` is sent at `SessionAccept.limits.report_interval` even when nothing changed — the agent's answer to `AGW-19` | PROPOSED |
| `AG-D19` | Lifecycle-verb outcomes are decided by **observation**, not by the verb's return; a changed-but-failed state has no `AG-D5` outcome and is routed | PROPOSED |
| `AG-D20` | On Docker a binding's container is found by the label `com.solace.sm.binding-id`, its config path read from the container's own launch args/env, and the host path mapped through a required bind mount | PROPOSED |
```

- [ ] **Step 2: Write the bodies**

Append to the same file (abbreviated here to their load-bearing content; write each in full):

```markdown
## `AG-D13` — The `.proto` is drafted in-repo, under `go-agent/proto/`, never `contracts/`

Open item 6 / `AG-1` / `AGW-2` routes the authoritative `.proto` to the architect group **with the `AG-D*` owners**, and the gateway design's §4 is explicitly "the input" to it. Building the agent needs an encoding now. So `go-agent/proto/agentgateway/v1draft/agent_gateway.proto` transcribes §4.3's catalogue and §4.11's enum one-for-one — same message names, same field names, nothing invented — under a header that says DRAFT and non-authoritative. It is **not** placed under `contracts/`, which is single-writer and normative.

Two transcription notes are recorded because they are the only places the transcription had to decide anything. Proto scopes enum values at package level, so values carry the enum-name prefix (`ERROR_CODE_SUPERSEDED`); the bare names in the design stay the semantic names and `internal/msg` uses them. And `CommandAck.detail.code` needs an enum the design leaves implicit: §4.4 rule 5 puts `ADMISSION_BLOCKED` there and §4.8 uses `LEASE_NOT_ACTIVE`, neither of which is in §4.11 — so `AckDetailCode` carries those two plus a reused `ErrorCode`.

**Containment.** Only `internal/wire` and `internal/wire/codec` import the generated package; every other package speaks `internal/msg` structs. When the reviewed contract lands, the swap is one directory and one codec file, and no reducer changes. **Exit condition:** this decision is superseded the day `contracts/` carries the real proto.

## `AG-D14` — The agent is an event-driven reactor with pure reducers

Every input — an inbound message, a timer, a finished operation, a connection event, a signal — becomes an `Event` on one inbox. One loop drains it and passes it to pure reducers that return `(state, []Effect)`; the loop executes the effects in the fixed order **`Persist → Delete → Send → RunOp → timers → connection`**. Reducers never read a clock, never do I/O, never spawn goroutines: the only impurities arrive through `reactor.Env` (`Now`, `Offset`, `NewID`, `Jitter`, `Limits`).

**Rationale.** The properties this component must prove are sequencing properties — "no `GenerationAvailable` is acted on unless the lease is `ACTIVE`", "the journal entry is fsynced before `CommandReceived` leaves the process", "a resent `LeaseRenew` reuses its `message_id`". Under a reducer they are table tests over `(state, event) → effects`; under goroutines with shared state they are timing arguments. The effect order **is** the agent-side write-before-act rule, in one place instead of at every call site.

**Rejected.** *Goroutine per stream with a mutex* — idiomatic, but scatters lease and journal transitions across five goroutines. *Full event sourcing* — the design requires only the command journal and the lease grant to be durable (`AG-D4`, `AGW-D15`); journaling heartbeats and credits is persistence with no requirement behind it, and the repo already holds the "events are notices, rows are the record" position (`BE-D1`).

**Consequence:** a failed `Persist` is fatal. The agent stops rather than act without durability.

## `AG-D15` — `internal/` is rewritten; the superseded scaffold is archived, not deleted

The scaffold in `go-agent/internal/` was written against the surrogate wire. The gateway design's fork inventory named three concrete incompatibilities — journal phases (`PREFLIGHT|STAGING|RENAMED|RESTARTING|INDETERMINATE` vs the baseline `RECEIVED→STAGED→PERSISTED→REPORTED`), a config shape encoding a separate telemetry endpoint where the design has one connection, and `WriteFileAtomic` without the parent-directory fsync §5.1 requires. All three are structural, so the packages are replaced rather than patched.

**Replaced, but kept.** `internal/{config,docker,persist,telemetry}`, the two demo CLIs and the `java-demo` fixtures move to **`go-agent/oldGo-Agent/`** with their git history; nothing is deleted. The Docker-lifecycle CLI (`start|stop|restart|status`) and the telemetry-collect CLI were proofs that predate the wire, and their jobs now belong to `internal/runtime/docker` (driven by commands, never by an operator at a prompt) and to slice 2's telemetry role — but they remain the clearest record of how the Engine API was driven and what shape local telemetry took, which is worth having while those are rebuilt.

The archive carries its own `go.mod` so the parent module's `go build ./...` skips it: archived packages no longer resolve their imports and are not expected to compile. It is reference material with no authority — where it disagrees with the gateway design or the baseline `AG-D*` family, they win.

What survives into the new packages is **re-derived, not copied**: the atomic-write primitive (now with the parent fsync) and the symlink-escape refusal the gateway design explicitly credits as a genuine addition.

## `AG-D16` — The DP test double is a scenario-driven fake gateway

`internal/fakegw` implements the DRAFT proto's server side over real mTLS/h2: it mints a CA, signs CSRs, enforces single-use enrollment tokens with `(token, CSR)` replay, issues and renews leases under the single-use nonce, and runs an ordered YAML **scenario** (`expect`, `offer`, `superseded`, `error`, `revoke`, `drain`, `disconnect`, policy switches) while recording a transcript. The same engine runs in-process from `go test` and as `cmd/fake-gateway` for demos.

**What it is not.** It has no ports, no persistence and no `dp-core`. **It is not evidence about the plane's correctness** — the gateway design's own §8 failure matrix and its PostgreSQL execution harness are. A green scenario here means the agent obeys the protocol as this repo reads it, nothing more.

## `AG-D17` — `item_id` and `sequence` (the agent's answer to `AGW-20`)

The gateway design asks the `AG-D*` owners for two things it cannot invent: a stable per-item report identity, and `sequence` reset/persistence semantics.

**`item_id`** is a UUIDv7 minted once when an observation becomes a report item. The item is written to `reports/pending.json` **before** it is sent and is re-sent byte-identical — same id, same sequence, same content — across reconnects and process restarts until a `ReportAck` names it. A new observation of the same replica is a new item; a pending item is never edited. That answers AGW-20's four properties: type (UUIDv7 string), uniqueness within one agent (globally, in practice), persistence across restarts (the pending file), and a non-reuse horizon (unbounded — ids are never reissued).

**`sequence`** is a per-`(binding_id, replica_ref)` `uint64`, incremented and **persisted to `reports/cursor.json` before the item exists**. A restart therefore cannot reset it, which removes the case AGW-20 names as unsolved from this agent's behaviour by construction. If the cursor is lost with the data directory, the counter is **reseeded from `dpNow.UnixNano()`**: any previous value is necessarily smaller, so monotonicity holds without the agent knowing what it was.

⚠️ **This is the agent half only.** The DP-side watermark, the canonical projection and the stored fingerprint remain AGW-20's, and this decision does not close it.

## `AG-D18` — A periodic report at `report_interval` (the agent's answer to `AGW-19`)

The agent sends a full `ActualStateReport` for every binding at `SessionAccept.limits.report_interval` **even when nothing has changed**, and once immediately after every `SessionAccept`.

**Why it is not optional.** The DP's replica registry retires an incarnation whose `last_seen_at` has gone stale, and `last_seen_at` is refreshed **only** by an actual-state report — `Heartbeat` deliberately carries no replica or incarnation identity. Without a cadence, a healthy unchanged workload is silence-retired; taking liveness from the heartbeat instead would keep a vanished replica alive for as long as its agent lives, which is the exhaustion the sweep exists to prevent. The DP delivers the interval, so the plane sets the rate and the agent does not guess it.

## `AG-D19` — Lifecycle outcomes are decided by observation

`restart` and `stop` report `APPLIED` only when the **post-state is observed** — for `restart`, running with a `StartedAt` newer than before; for `stop`, not running — and `FAILED_NO_EFFECT` when the call failed and the pre-state is unchanged. The verb's own return value is never the verdict: a Docker `restart` that returns success while the container fails its health check has changed the world and not achieved the goal.

⚠️ **The gap, stated rather than papered over.** `AG-D5`'s outcome set has no member for "the verb changed state and still failed". This design reports `FAILED_NO_EFFECT` with the observed facts and lets the next `ActualStateReport` — which is what convergence actually reads (gateway §4.8) — carry the truth. Adding an outcome is a wire change and therefore the `AG-D*` owners' and `AGW`'s jointly; **routed, not decided here.**

## `AG-D20` — Docker binding resolution by label, path by discovery

Nothing in gateway §4.3 carries placement facts to the agent: a command names a `binding_id`, and the agent must find the container, the declared config path and a writable host path itself. On Docker, for v1:

| Fact | Source |
|---|---|
| the container | the label `com.solace.sm.binding-id=<id>`, exactly one match (zero → `ErrNotFound`, many → `ErrAmbiguous`) |
| the config path | the container's own `--spring.config.additional-location=` / `--spring.config.location=` argument, else `SPRING_CONFIG_ADDITIONAL_LOCATION` / `SPRING_CONFIG_LOCATION` |
| the host path | that directory mapped through a **bind mount** (required — the agent writes on the host) |
| `replica_ref` / `incarnation` | container name (RFC 1123-valid) / container ID (hex) |

**`I1` check.** All four are **deployment configuration** — a label, a launch argument, a mount — which `I1` names as fair game. None requires an MI code or build change. Pre-flight refuses (`PREFLIGHT_REFUSED`) when any of them cannot be established, and reports competing Spring sources (`SPRING_APPLICATION_JSON`, `SPRING_CONFIG_LOCATION`) rather than writing beside them (`SM-D21`).

⚠️ **Flagged to the `AGW` owners:** this convention exists because the plane carries no placement facts. If the protocol later carries them, this decision is superseded rather than extended.
```

- [ ] **Step 3: Changelog and answered-asks rows**

Add to that file's changelog:

```markdown
| 2026-09-22 | `AG-D13`–`AG-D20` from `docs/superpowers/specs/2026-09-21-go-agent-control-path-design.md`: DRAFT proto, reactor architecture, clean rewrite, fake gateway, the answers to `AGW-19` and `AGW-20`, lifecycle outcomes and Docker binding resolution. |
```

and, in the conflicts/dependencies table, rows recording that `AG-D17` answers `AGW-20`'s agent half, `AG-D18` answers `AGW-19`, and `AG-D19`/`AG-D20` route gaps back to the `AGW` owners.

- [ ] **Step 4: Verify links and IDs**

Run:

```bash
cd /home/arnav-rinawa/Documents/projects/solaceSolutionManager/solution-manager-main
grep -c '^| `AG-D' docs/design/go-agent/decisions.md   # expect 20
grep -n 'AG-D1[3-9]\|AG-D20' docs/design/go-agent/decisions.md | head -30
```

Expected: the table has twenty rows, each new ID appears in both the table and a body heading.

- [ ] **Step 5: Commit**

```bash
git add docs/design/go-agent/decisions.md
git commit -m "go-agent: record AG-D13..AG-D20 for the control-path implementation

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 24: Retire the fork docs, rewrite the component READMEs

**Files:**
- Move: `go-agent/docs/` → `docs/design/archive/go-agent-fork/`
- Create: `docs/design/archive/go-agent-fork/README.md`
- Modify: `go-agent/README.md`, `go-agent/CLAUDE.md`

- [ ] **Step 1: Move the superseded docs**

```bash
cd /home/arnav-rinawa/Documents/projects/solaceSolutionManager/solution-manager-main
mkdir -p docs/design/archive
git mv go-agent/docs docs/design/archive/go-agent-fork
```

- [ ] **Step 2: Explain the archive**

`docs/design/archive/go-agent-fork/README.md`:

```markdown
# go-agent fork docs — ARCHIVED 2026-09-22

These files were `go-agent/docs/` — a component design (`design.md`), a decision
family (`decisions.md`, `AG-D1`–`AG-D6`), a contract-spine workplan and the
ASSUMED surrogate wire (`p2-wire-assumptions.md`, `p2-baseline.md`,
`p2-wire-assumptions-audit.md`, `p3-mechanism-gaps.md`).

**They are superseded and must not be cited.** Authority is:

| Topic | Now lives in |
|---|---|
| The agent↔DP wire, session, lease, commands, reports, telemetry ingest | [`../../backend/agent-gateway/design.md`](../../backend/agent-gateway/design.md) §4, [`decisions.md`](../../backend/agent-gateway/decisions.md) (`AGW-D1`–`AGW-D16`) |
| The agent's own decisions | [`../../go-agent/decisions.md`](../../go-agent/decisions.md) (`AG-D1`–`AG-D20`) |
| The implementation | [`../../../plans/go-agent/005-control-path.md`](../../../plans/go-agent/005-control-path.md) and [`../../../superpowers/specs/2026-09-21-go-agent-control-path-design.md`](../../../superpowers/specs/2026-09-21-go-agent-control-path-design.md) |

⚠️ **ID collision.** This family's `AG-D2`–`AG-D6` mean different things from the
baseline family's. The baseline is what the agent-gateway design was written
against and what the code implements. Cite `AGW-D*` or the baseline `AG-D*`;
never the IDs in this folder.

Kept because the gateway design's "Fork inventory" cites these paths, and
because `p2-wire-assumptions-audit.md` enumerates questions §4.3 answers —
reading one beside the other shows what was settled.
```

- [ ] **Step 3: Rewrite `go-agent/README.md`**

```markdown
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
```

- [ ] **Step 4: Update `go-agent/CLAUDE.md`**

Replace its "Commands", "Architecture" and doc-table sections so they describe what now exists: the `make` targets; the reactor (`cmd/sm-agent` → `internal/agent` loop → reducers `session`/`authority`/`commands`/`reports` → effects); `internal/wire` + DRAFT proto; `internal/runtime` + `docker` + `configwriter`; `internal/store`; `internal/fakegw`. Keep the precedence section unchanged, and replace the "Local, superseded" table with two rows: `docs/design/archive/go-agent-fork/` for the superseded design docs, and `oldGo-Agent/` for the archived scaffold — both reference-only.

Drop the "Still no `.proto`" caveat's claim that nothing is built. The `internal/persist`/`internal/config` "known gaps" paragraphs are replaced by a one-line pointer: those packages now live under `oldGo-Agent/` and the gaps they described (journal phases, config topology, the missing parent-directory fsync) are closed in `internal/commands`, `internal/store` and `internal/runtime/configwriter`.

- [ ] **Step 5: Verify every link resolves**

```bash
cd /home/arnav-rinawa/Documents/projects/solaceSolutionManager/solution-manager-main
grep -oE '\]\([^)#][^)]*\)' go-agent/README.md go-agent/CLAUDE.md docs/design/archive/go-agent-fork/README.md \
  | sed -E 's/.*\]\(([^)]*)\)/\1/' | sort -u | while read -r link; do
      case "$link" in http*) continue;; esac
      base=$(dirname "$(grep -rl "$link" go-agent/README.md go-agent/CLAUDE.md docs/design/archive/go-agent-fork/README.md | head -1)")
      [ -e "$base/$link" ] || echo "BROKEN: $link (from $base)"
    done
```

Expected: no `BROKEN` lines.

- [ ] **Step 6: Full verification**

```bash
go build ./... && go vet ./...
go test -race ./...
go test -race -tags integration ./internal/agent/ -run Integration
```

Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add -A go-agent docs/design/archive docs/design/go-agent
git commit -m "go-agent: archive the fork docs, rewrite README and CLAUDE.md for the new layout

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

**Phase P5 exit:** one `AG-D*` family, no dangling citations, READMEs describing the code that exists.

---

## Success criteria

The baseline design's §12 criteria for this path, each mapped to the test that proves it:

| Criterion | Proven by |
|---|---|
| Enroll + mTLS | `TestIntegrationHappyPathReachesActive`, `TestEnrollReplayAndConsumption` |
| A lost enroll reply does not consume the token | `TestIntegrationLostEnrollReplyReplaysSameCSR` |
| Lease renews at 50 %, resends reuse the `message_id` | `TestIntegrationRenewAtHalfLifeAndSameIDOnResend`, `TestRenewDueSendsRenewAndResendsSameID` |
| An expired lease renews under its nonce before `SessionAccept` | `TestIntegrationExpiredLeaseReconnectRenewsBeforeAccept` |
| Refusal → `RENEW_DEGRADED` → `LEASE_EXPIRED`, report-only | `TestIntegrationRenewRefusedDegradesThenExpires` |
| Revocation stops the agent and it does not reconnect | `TestIntegrationRevokeHalts` |
| Clock suspect fails closed and recovers | `TestIntegrationClockSuspectFailsClosedThenRecovers` |
| Crash mid-apply resumes, or surfaces a narrow `APPLY_INDETERMINATE` | `TestResumeFromStagedCompletesTheWrite`, `TestResumeFromPersistedDoesNotRewrite`, `TestStepDeadlineWhileInFlightIsIndeterminateOnlyWhenLeaseLeftActive` |
| Config at the declared path, mode 0600, symlink escape refused | `TestIntegrationPreflightApplyRestart`, `TestWriteRefusesSymlinkEscape`, `TestE2EAgainstRealContainer` |
| Execution at most once under redelivery | `TestIntegrationDuplicateOfferIsAnsweredFromTheJournal` |
| `restart-observed` reported | `TestIntegrationReportsAfterApplyAndReceivesMintedIDs`, `TestRestartObservesNewStartedAt` |
| Periodic reporting with nothing changed (`AGW-19`) | `TestIntegrationPeriodicReportWithNothingChanged` |
| `item_id` survives a restart (`AGW-20`) | `TestIntegrationReportRetryAfterCrashReusesItemID` |
| No secret values in logs | the redaction test added in Task 24's verification run (see below) |

**One test this plan owes and must not skip:** a redaction test. Add it in Task 24 Step 6 as `internal/agent/redaction_test.go` — run a loop with a `slog` handler writing to a buffer, drive an apply whose payload contains `super-secret-password`, and assert the buffer contains neither that string nor the enrollment token. If it fails, the fix is a `LogValuer` on the payload-bearing types, not a weaker assertion.

---

## Deliberate non-goals

Named so a reviewer does not read their absence as an oversight: the `Telemetry` stream, credits and the disk buffer (`AG-D9`, slice 2); Kubernetes leader election (`AG-D6`) and the ConfigMap writer (`SM-D25`, slice 3); the `solace_event` collector (open item 35 / `AGW-3`, cgo); the report-only credential (`AGW-12`, undesigned plane-side); multi-replica stage-everywhere (`SM-D15`, one binding in v1); agent self-upgrade; the HTTP/1.1 fallback (`AGW-6`).
