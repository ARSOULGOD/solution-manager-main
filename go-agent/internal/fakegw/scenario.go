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
	// AG-D26, a DRAFT ask (AG-23); 0 leaves the agent's local defaults.
	TelemetryPullInterval time.Duration `yaml:"telemetry_pull_interval"`
	TelemetryPushInterval time.Duration `yaml:"telemetry_push_interval"`
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

type Policy struct {
	OnOpen    string          `yaml:"on_open"` // accept | renew-required | reject:<CODE>
	Renew     string          `yaml:"renew"`   // grant | refuse:<CODE> | silent
	Heartbeat HeartbeatPolicy `yaml:"heartbeat"`
	Report    ReportPolicy    `yaml:"report"`
	Telemetry TelemetryPolicy `yaml:"telemetry"`
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
		Limits: LimitsSpec{MaxMessageBytes: 1 << 20, MaxBatchBytes: 65536, InitialCredits: 4, HeartbeatInterval: 10 * time.Second, KeepaliveInterval: 30 * time.Second, SkewTolerance: 30 * time.Second, ReportInterval: time.Minute},
		Policy: Policy{OnOpen: "accept", Renew: "grant", Heartbeat: HeartbeatPolicy{State: "OK"}, Report: ReportPolicy{Mode: "auto"}, Telemetry: TelemetryPolicy{CreditsPerAck: 1}},
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
