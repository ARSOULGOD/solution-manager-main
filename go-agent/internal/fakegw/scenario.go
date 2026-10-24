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
	MaxBatchBytes     uint64        `yaml:"max_batch_bytes"`
	InitialCredits    uint32        `yaml:"initial_credits"`
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

type TelemetryPolicy struct {
	CreditsPerAck uint32 `yaml:"credits_per_ack"`
	RejectNext    string `yaml:"reject_next"` // an ErrorCode; the next batch is rejected with it, once
}

// DiscoveryPolicy answers a DiscoveryReport (plan 015 T2.3). The fake is not a
// DP: it registers whatever it is told to and says nothing about correctness.
type DiscoveryPolicy struct {
	Mode     string            `yaml:"mode"`     // register (default) | silent
	Bindings map[string]string `yaml:"bindings"` // container name -> binding id; default "bind-<name>"
}

// MetricStep and ProbeStep are one TelemetryDeclarations (plan 015 T2.3).
type MetricStep struct {
	Name          string `yaml:"name"`
	MetricType    string `yaml:"metric_type"` // counter | gauge
	Unit          string `yaml:"unit"`
	Port          uint32 `yaml:"port"`
	Path          string `yaml:"path"`
	Pointer       string `yaml:"pointer"`
	OnUnavailable string `yaml:"on_unavailable"`
}

type ProbeStep struct {
	Kind     string `yaml:"kind"` // ACTUATOR_HEALTH | HTTP_STATUS
	Path     string `yaml:"path"`
	HostPort uint32 `yaml:"host_port"`
	Strength string `yaml:"strength"` // READINESS | LIVENESS
}

type DeclarationsStep struct {
	BindingID   string        `yaml:"binding_id"`
	Interval    time.Duration `yaml:"interval"`
	Metrics     []MetricStep  `yaml:"metrics"`
	HealthProbe *ProbeStep    `yaml:"health_probe"`
}

// LogsPolicy is how the fake grants credit on the Logs stream (plan 016).
type LogsPolicy struct {
	// CreditsPerChunk is granted back for every non-final LogChunk received;
	// zero grants nothing automatically (Server.GrantLogCredit does).
	CreditsPerChunk uint32 `yaml:"credits_per_chunk"`
}

type Policy struct {
	Discovery DiscoveryPolicy `yaml:"discovery"`
	OnOpen    string          `yaml:"on_open"` // accept | renew-required | reject:<CODE>
	Renew     string          `yaml:"renew"`   // grant | refuse:<CODE> | silent
	Heartbeat HeartbeatPolicy `yaml:"heartbeat"`
	Report    ReportPolicy    `yaml:"report"`
	Telemetry TelemetryPolicy `yaml:"telemetry"`
	Logs      LogsPolicy      `yaml:"logs"`
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
	// ExpectedCurrentSHA256 is OPERATION_REMATERIALIZE's precondition (plan 015
	// T2.3): absent leaves the offer without the field, "" means "must be absent".
	ExpectedCurrentSHA256 *string `yaml:"expected_current_sha256"`
	// BadHash makes the offer's config_sha256 disagree with the payload.
	BadHash bool `yaml:"bad_hash"`
	// Deadline relative to send time; zero → 5 minutes.
	Deadline time.Duration `yaml:"deadline"`
}

// LogRetentionStep sends LogRetention for a placement (a binding id).
type LogRetentionStep struct {
	Placement string `yaml:"placement"`
	MaxBytes  uint64 `yaml:"max_bytes"`
	// Force sends even to a session that did not advertise CAPABILITY_LOGS, to
	// prove the agent ignores what it never asked for. Test-only.
	Force bool `yaml:"-"`
}

type LogPageStep struct {
	Limit  uint32     `yaml:"limit"`
	After  *time.Time `yaml:"after"`
	Before *time.Time `yaml:"before"`
}

type LogExportStep struct {
	FromSeq uint64 `yaml:"from_seq"`
}

// LogRequestStep sends a LogRequest carrying exactly one of Page or Export.
// An empty RequestID becomes "req-<n>" for the session.
type LogRequestStep struct {
	Placement string         `yaml:"placement"`
	RequestID string         `yaml:"request_id"`
	Page      *LogPageStep   `yaml:"page"`
	Export    *LogExportStep `yaml:"export"`
	// Force: see LogRetentionStep.Force.
	Force bool `yaml:"-"`
}

// ExpectLogPageStep waits for the LogPage answering RequestID (default: the
// last log_request's) with at least MinLines lines and, when Unavailable is
// set, that UnavailableReason. A timeout ends the script.
type ExpectLogPageStep struct {
	RequestID   string        `yaml:"request_id"`
	MinLines    int           `yaml:"min_lines"`
	Unavailable string        `yaml:"unavailable"`
	Timeout     time.Duration `yaml:"timeout"`
}

// ExpectLogChunksStep waits until the chunks for RequestID (default: the last
// log_request's) carry at least MinLines lines in all and, when Final is set,
// the final chunk has arrived.
type ExpectLogChunksStep struct {
	RequestID string        `yaml:"request_id"`
	MinLines  int           `yaml:"min_lines"`
	Final     bool          `yaml:"final"`
	Timeout   time.Duration `yaml:"timeout"`
}

type ErrorStep struct {
	Code       string         `yaml:"code"`
	RetryAfter *time.Duration `yaml:"retry_after"`
}

type Step struct {
	Expect          *ExpectStep       `yaml:"expect"`
	Offer           *OfferStep        `yaml:"offer"`
	Declarations    *DeclarationsStep `yaml:"declarations"`
	Superseded      *string           `yaml:"superseded"`
	Error           *ErrorStep        `yaml:"error"`
	Revoke          *string           `yaml:"revoke"`
	Drain           *time.Duration    `yaml:"drain"`
	DrainDeadline   *time.Duration    `yaml:"drain_deadline"` // with drain: AGW-D18 planned drain; the session closes on DrainComplete
	Disconnect      *bool             `yaml:"disconnect"`
	Sleep           *time.Duration    `yaml:"sleep"`
	SendGrant       *bool             `yaml:"send_grant"`
	RenewPolicy     *string           `yaml:"renew_policy"`
	HeartbeatPolicy *HeartbeatPolicy  `yaml:"heartbeat_policy"`
	ReportPolicy    *ReportPolicy     `yaml:"report_policy"`

	// Plan 016: MI logs. The sends are skipped for a session that did not
	// advertise CAPABILITY_LOGS, as the real gateway skips them.
	LogRetention    *LogRetentionStep    `yaml:"log_retention"`
	LogRequest      *LogRequestStep      `yaml:"log_request"`
	ExpectLogPage   *ExpectLogPageStep   `yaml:"expect_log_page"`
	ExpectLogChunks *ExpectLogChunksStep `yaml:"expect_log_chunks"`

	// Plan 017: MI config view; sent to any session (no capability).
	ConfigViewRequest *ConfigViewRequestStep `yaml:"config_view_request"`

	// Hold is a test-only barrier: the runner calls it and continues when it
	// returns. It cannot be written in a scenario file.
	Hold func() `yaml:"-"`
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
			VerbCeilings:    map[string]time.Duration{"preflight": time.Minute, "apply": 2 * time.Minute, "restart": 5 * time.Minute, "stop": 2 * time.Minute, "rematerialize": 2 * time.Minute},
			AdmissionMargin: 30 * time.Second,
		},
		Limits: LimitsSpec{MaxMessageBytes: 1 << 20, MaxBatchBytes: 65536, InitialCredits: 4, HeartbeatInterval: 10 * time.Second, KeepaliveInterval: 30 * time.Second, SkewTolerance: 30 * time.Second, ReportInterval: time.Minute},
		Policy: Policy{OnOpen: "accept", Renew: "grant", Heartbeat: HeartbeatPolicy{State: "OK"}, Report: ReportPolicy{Mode: "auto"}, Telemetry: TelemetryPolicy{CreditsPerAck: 1}, Logs: LogsPolicy{CreditsPerChunk: 1}},
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
