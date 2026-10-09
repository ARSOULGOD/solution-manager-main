// Package msg is the agent's internal form of the agent-gateway message
// catalogue (gateway design §4.3, §4.11). No package outside internal/wire
// touches generated protobuf types; everything else speaks these structs.
package msg

import (
	"fmt"
	"log/slog"
	"time"
)

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
	DetailNone             DetailCode = ""
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
	Token                     string // gRPC metadata x-sm-enrollment-token; never persisted, never logged
	CSRDER                    []byte
	AgentVersion              string
	ProtocolVersionsSupported []uint32
	HostFacts                 HostFacts
}

// LogValue and String redact Token from slog output and from fmt's %v/%+v/%s
// verbs alike — LogValuer alone only covers slog call sites, not a bare
// fmt.Sprintf("%v", req) or an error wrap that embeds the value.
func (r EnrollRequest) LogValue() slog.Value {
	return slog.GroupValue(
		slog.String("agent_version", r.AgentVersion),
		slog.Any("protocol_versions_supported", r.ProtocolVersionsSupported),
		slog.Int("csr_der_len", len(r.CSRDER)),
		slog.String("host_os", r.HostFacts.OS),
		slog.String("host_arch", r.HostFacts.Arch),
		slog.String("host_runtime_kind", r.HostFacts.RuntimeKind),
		slog.Bool("token_present", r.Token != ""),
	)
}

func (r EnrollRequest) String() string {
	return fmt.Sprintf("EnrollRequest{agent_version=%s csr_der_len=%d host=%s/%s token=[REDACTED]}",
		r.AgentVersion, len(r.CSRDER), r.HostFacts.OS, r.HostFacts.Arch)
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
	// AG-D26, a DRAFT ask not in gateway §4.3 (AG-23). 0 = the local default.
	TelemetryPullInterval time.Duration
	TelemetryPushInterval time.Duration
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

// LogValue and String redact Payload from slog output and from fmt's
// %v/%+v/%s verbs alike — a config payload can carry embedded secrets
// (secretRef values, credentials), so only its length is ever shown, never
// its bytes. LogValuer alone only covers slog call sites, not a bare
// fmt.Sprintf("%v", offer) elsewhere in the codebase.
func (m CommandOffer) LogValue() slog.Value {
	return slog.GroupValue(
		slog.String("message_id", m.MessageID),
		slog.String("command_id", m.CommandID),
		slog.Uint64("generation", m.Generation),
		slog.String("idempotency_key", m.IdempotencyKey),
		slog.String("binding_id", m.BindingID),
		slog.String("operation", string(m.Operation)),
		slog.String("config_sha256", m.ConfigSHA256),
		slog.Int("payload_len", len(m.Payload)),
	)
}

func (m CommandOffer) String() string {
	return fmt.Sprintf("CommandOffer{command_id=%s binding_id=%s operation=%s generation=%d payload_len=%d}",
		m.CommandID, m.BindingID, m.Operation, m.Generation, len(m.Payload))
}

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

	// Operation is agent-internal and deliberately NOT on the wire: the
	// gateway design's CommandAck field list carries no operation, and the DP
	// already knows it — it issued the command and correlates by command_id.
	// The codec does not marshal it (see TestCommandAckOperationIsNotOnTheWire).
	//
	// It exists because the metrics label sm_agent_commands_total{op} is
	// applied by observing ack effects, and two ack sites have no journal
	// record to look the operation up in: refuse() writes no state at all, and
	// wireError()'s pull-stage path deletes its Pulling entry in the same
	// reduction. Carrying the operation on the ack labels those correctly
	// instead of bucketing real operations under op="unknown".
	Operation Operation
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
	ItemID              string
	Sequence            uint64
	BindingID           string
	ReplicaRef          string
	Incarnation         string
	PersistedGeneration uint64
	RestartObservedAt   *time.Time
	EffectiveHash       string
	VersionObservation  VersionObservation
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

// ---- Telemetry stream (gateway §4.3, §4.10) ----

type MetricType string

const (
	MetricCounter   MetricType = "counter"
	MetricGauge     MetricType = "gauge"
	MetricHistogram MetricType = "histogram"
)

// Sample is gateway §6.1's NormalizedSample. On an agent batch, Labels holds
// exactly binding_id, replica_id and incarnation_id, all DP-minted.
type Sample struct {
	SeriesKey string
	Type      MetricType
	TS        time.Time
	Value     float64
	Labels    map[string]string
}

type GapMarker struct {
	FromSeq, ToSeq uint64
	Reason         string
}

// TelemetryBatch is the only agent → gateway message on the Telemetry stream.
type TelemetryBatch struct {
	Envelope
	StreamID   string
	BatchSeq   uint64
	Samples    []Sample
	GapMarkers []GapMarker
	ProducedAt time.Time
}

// TelemetryInbound is a gateway → agent message on the Telemetry stream.
type TelemetryInbound interface{ telemetryInbound() }

type TelemetryAck struct {
	Envelope
	StreamID         string
	HighWatermarkSeq uint64
	CreditsGranted   uint32
}

type TelemetryCredit struct {
	Envelope
	StreamID       string
	CreditsGranted uint32
}

type TelemetryReject struct {
	Envelope
	StreamID   string
	Code       ErrorCode
	RetryAfter *time.Duration
}

func (TelemetryAck) telemetryInbound()    {}
func (TelemetryCredit) telemetryInbound() {}
func (TelemetryReject) telemetryInbound() {}
