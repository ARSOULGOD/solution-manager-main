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
