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
