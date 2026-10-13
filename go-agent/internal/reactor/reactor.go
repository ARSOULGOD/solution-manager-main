// Package reactor is the vocabulary the loop and the reducers share: events
// in, effects out, and the Env that carries every impurity a reducer may use.
package reactor

import (
	"sort"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
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
	// DrainDeadline forces a planned drain to complete when in-flight work has
	// not finished in time (AGW-D18).
	DrainDeadline TimerKind = "DrainDeadline"
)

type TimerFired struct {
	At   time.Time
	Kind TimerKind
}

type OpFinished struct {
	At        time.Time
	CommandID string
	Result    runtime.OpResult
}

// OpProgress is pushed by the runtime worker when the adapter reaches a
// journal step mid-operation. The step is journaled when this is reduced, so
// the filesystem may already be ahead of it — see runtime.Progress.
type OpProgress struct {
	At        time.Time
	CommandID string
	Step      msg.ProgressStep
}

type Connected struct {
	At              time.Time
	TelemetryResume map[string]uint64 // SessionOpen.resume.last_telemetry_seq_by_stream
}

type Disconnected struct {
	At    time.Time
	Cause error
}

type EnrollResult struct {
	At   time.Time
	Resp *msg.EnrollResponse
	Err  error
}

// Observed carries a runtime observation the report reducer turns into an
// item. The loop produces it on the report timer and after every operation.
type Observed struct {
	At  time.Time
	Obs any // reports.Observation; typed as any to keep reactor free of reports
}

// Discovered carries a scan of the containers that opted in (AG-D26). The loop
// produces it after SessionAccept and on the report cadence; the discovery
// reducer sends a DiscoveryReport only when the set changed.
type Discovered struct {
	At        time.Time
	Items     []msg.DiscoveredContainer
	Truncated int
}

type Resumed struct{ At time.Time }

type Shutdown struct {
	At    time.Time
	Cause error
}

func (e WireIn) EventAt() time.Time       { return e.At }
func (e TimerFired) EventAt() time.Time   { return e.At }
func (e OpFinished) EventAt() time.Time   { return e.At }
func (e OpProgress) EventAt() time.Time   { return e.At }
func (e Connected) EventAt() time.Time    { return e.At }
func (e Disconnected) EventAt() time.Time { return e.At }
func (e EnrollResult) EventAt() time.Time { return e.At }
func (e Observed) EventAt() time.Time     { return e.At }
func (e Resumed) EventAt() time.Time      { return e.At }
func (e Discovered) EventAt() time.Time   { return e.At }
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
	// ExpectedCurrentSHA256 is OPERATION_REMATERIALIZE's precondition; nil
	// elsewhere.
	ExpectedCurrentSHA256 *string
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

// Env mints one envelope per outbound message.
func (e Env) Env() msg.Envelope { return msg.Envelope{MessageID: e.NewID(), SentAt: e.Now} }
