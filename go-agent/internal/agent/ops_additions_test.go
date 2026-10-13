package agent

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/commands"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/runtime/configwriter"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// realWriteAdapter writes through the real config writer, so the precondition and
// the file on disk are the real thing.
type realWriteAdapter struct {
	stubAdapter
	writes int
}

func (a *realWriteAdapter) WriteConfig(_ context.Context, p runtime.Placement, payload []byte, sha string, pr runtime.Progress) (runtime.WriteResult, error) {
	a.writes++
	return configwriter.Write(p.HostPath, payload, sha, -1, -1, pr)
}

func rematRun(t *testing.T, current *string, expected string, payload string) (runtime.OpResult, *realWriteAdapter, string) {
	t.Helper()
	host := filepath.Join(t.TempDir(), "application.yml")
	if current != nil {
		if err := os.WriteFile(host, []byte(*current), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	st := store.MustOpen(t.TempDir())
	_ = st.Save(store.Blob{RelPath: commands.PayloadRel("b1", 1), Data: []byte(payload)})
	a := &realWriteAdapter{stubAdapter: stubAdapter{place: runtime.Placement{BindingID: "b1", HostPath: host},
		facts: []runtime.ReplicaFacts{{Running: true, StartedAt: time.Unix(500, 0)}}}}
	r := &opRunner{adapter: a, store: st, push: func(reactor.Event) {}, now: time.Now}
	res := r.Run(context.Background(), reactor.RunOp{CommandID: "c", BindingID: "b1", Op: msg.OpRematerialize,
		PayloadRel: commands.PayloadRel("b1", 1), SHA256: configwriter.SHA256([]byte(payload)), ExpectedCurrentSHA256: &expected,
		Deadline: time.Now().Add(time.Minute)})
	return res, a, host
}

// AG-D27 item 3: an offer whose expected_current_sha256 differs from the file
// writes NOTHING and acks REFUSED with TARGET_MOVED.
func TestRematerializeWithADifferingPreconditionWritesNothing(t *testing.T) {
	cur := "operator edited this\n"
	res, a, host := rematRun(t, &cur, configwriter.SHA256([]byte("what the DP rendered against")), "new: 1\n")
	if res.Outcome != msg.OutcomeRefused || res.Detail.Code != msg.DetailOf(msg.ErrTargetMoved) {
		t.Fatalf("%+v", res)
	}
	if a.writes != 0 {
		t.Fatal("the writer must not be reached")
	}
	if b, _ := os.ReadFile(host); string(b) != cur {
		t.Fatalf("the host file changed: %q", b)
	}
}

func TestRematerializeWithAMatchingPreconditionWritesLikeApply(t *testing.T) {
	cur := "current: 1\n"
	res, a, host := rematRun(t, &cur, configwriter.SHA256([]byte(cur)), "new: 1\n")
	if res.Outcome != msg.OutcomeApplied || a.writes != 1 {
		t.Fatalf("%+v writes=%d", res, a.writes)
	}
	if b, _ := os.ReadFile(host); string(b) != "new: 1\n" {
		t.Fatalf("%q", b)
	}
	if res.Facts == nil || res.Facts.StartedAt.IsZero() {
		t.Fatal("the verified write reads StartedAt back for the ack (the OR-112 baseline)")
	}
}

// Present and empty means the file must still be ABSENT.
func TestRematerializeEmptyExpectationRequiresAnAbsentFile(t *testing.T) {
	res, _, host := rematRun(t, nil, "", "new: 1\n")
	if res.Outcome != msg.OutcomeApplied {
		t.Fatalf("absent file + empty expectation must write: %+v", res)
	}
	if _, err := os.Stat(host); err != nil {
		t.Fatal(err)
	}
	cur := "someone made it\n"
	res, a, _ := rematRun(t, &cur, "", "new: 1\n")
	if res.Outcome != msg.OutcomeRefused || res.Detail.Code != msg.DetailOf(msg.ErrTargetMoved) || a.writes != 0 {
		t.Fatalf("a present file with an empty expectation is TARGET_MOVED: %+v", res)
	}
	// and a missing file against a non-empty expectation is TARGET_MOVED too
	res, a, _ = rematRun(t, nil, configwriter.SHA256([]byte("x")), "new: 1\n")
	if res.Outcome != msg.OutcomeRefused || a.writes != 0 {
		t.Fatalf("%+v", res)
	}
}

// A replay after a crash between the rename and the journal finds the file already
// holding the offer's own payload: that completes (the writer is idempotent), it is
// not TARGET_MOVED.
func TestRematerializeReplayAfterTheRenameCompletes(t *testing.T) {
	payload := "new: 1\n"
	res, _, _ := rematRun(t, &payload, configwriter.SHA256([]byte("the old file")), payload)
	if res.Outcome != msg.OutcomeApplied {
		t.Fatalf("%+v", res)
	}
}

// Plan 015 S4: no config-writer or Docker error text can carry payload bytes (or
// a digest of them) into the ack detail. The detail is a code-keyed template,
// bounded and printable.
func TestAckDetailNeverCarriesPayloadBytesFromWriterOrDockerErrors(t *testing.T) {
	const secret = "hunter2-S4-secret"
	leaky := []error{
		&configwriter.WriteError{Phase: configwriter.PhaseBeforeRename, Err: fmt.Errorf("yaml: line 3: could not parse %q", "password: "+secret)},
		&configwriter.WriteError{Phase: configwriter.PhaseAfterRenameUnknown, Err: errors.New("payload hash " + configwriter.SHA256([]byte(secret)) + " != declared abc")},
		fmt.Errorf("docker: container exited: %s\nstack:\n%s", secret, strings.Repeat("x", 4096)),
		errors.New(secret),
	}
	for i, e := range leaky {
		st := store.MustOpen(t.TempDir())
		_ = st.Save(store.Blob{RelPath: commands.PayloadRel("b1", 1), Data: []byte("x")})
		a := &stubAdapter{facts: []runtime.ReplicaFacts{{Running: true, StartedAt: time.Unix(1, 0)}, {Running: true, StartedAt: time.Unix(1, 0)}},
			writeErr: e, restart: e}
		r := &opRunner{adapter: a, store: st, push: func(reactor.Event) {}, now: time.Now}
		for _, op := range []reactor.RunOp{
			{CommandID: "c", BindingID: "b1", Op: msg.OpApply, PayloadRel: commands.PayloadRel("b1", 1), SHA256: "h", Deadline: time.Now().Add(time.Minute)},
			{CommandID: "c", BindingID: "b1", Op: msg.OpRestart, Deadline: time.Now().Add(time.Minute)},
		} {
			res := r.Run(context.Background(), op)
			m := res.Detail.Message
			if strings.Contains(m, secret) || strings.Contains(m, configwriter.SHA256([]byte(secret))) {
				t.Fatalf("case %d %s: payload bytes or a digest reached the ack detail: %q", i, op.Op, m)
			}
			if len(m) > MaxDetailMessage {
				t.Fatalf("case %d %s: detail is not bounded (%d bytes)", i, op.Op, len(m))
			}
			if m == "" {
				t.Fatalf("case %d %s: a failure still says what class it was", i, op.Op)
			}
		}
	}
	// the resolver's error is bounded and sanitised the same way
	r := &opRunner{adapter: &stubAdapter{resolve: errors.New(secret)}, store: store.MustOpen(t.TempDir()), push: func(reactor.Event) {}, now: time.Now}
	res := r.Run(context.Background(), reactor.RunOp{Op: msg.OpApply, Deadline: time.Now().Add(time.Minute)})
	if strings.Contains(res.Detail.Message, secret) {
		t.Fatalf("%q", res.Detail.Message)
	}
	if got := boundDetail("a\x00b\nc" + strings.Repeat("z", 1000)); len(got) > MaxDetailMessage || strings.ContainsAny(got, "\x00\n") {
		t.Fatalf("boundDetail: %q", got)
	}
}

// The health observation rides on the operation result when the adapter can
// observe it (OR-130 D-4); an adapter that cannot leaves it nil, never an empty
// non-nil observation.
type healthAdapter struct {
	stubAdapter
	probeSeen *msg.HealthProbe
}

func (h *healthAdapter) ObserveHealth(_ context.Context, _ runtime.Placement, probe *msg.HealthProbe) msg.HealthObservation {
	h.probeSeen = probe
	return msg.HealthObservation{State: msg.HealthHealthy, HTTPStatus: 200, ActuatorStatus: "UP"}
}

func TestOpResultCarriesTheDeclaredProbesFacts(t *testing.T) {
	probe := &msg.HealthProbe{Kind: msg.ProbeActuatorHealth, Path: "/actuator/health", HostPort: 18080}
	a := &healthAdapter{stubAdapter: stubAdapter{facts: []runtime.ReplicaFacts{{Running: true, StartedAt: time.Unix(1, 0)}, {Running: true, StartedAt: time.Unix(2, 0)}}}}
	r := &opRunner{adapter: a, store: store.MustOpen(t.TempDir()), push: func(reactor.Event) {}, now: time.Now,
		probe: func(string) *msg.HealthProbe { return probe }}
	res := r.Run(context.Background(), reactor.RunOp{Op: msg.OpRestart, BindingID: "b1", Deadline: time.Now().Add(time.Minute)})
	if res.Health == nil || res.Health.HTTPStatus != 200 || a.probeSeen != probe {
		t.Fatalf("%+v probe=%v", res.Health, a.probeSeen)
	}
	plain := &opRunner{adapter: &stubAdapter{facts: []runtime.ReplicaFacts{{Running: true, StartedAt: time.Unix(1, 0)}, {Running: true, StartedAt: time.Unix(2, 0)}}},
		store: store.MustOpen(t.TempDir()), push: func(reactor.Event) {}, now: time.Now}
	if res := plain.Run(context.Background(), reactor.RunOp{Op: msg.OpRestart, Deadline: time.Now().Add(time.Minute)}); res.Health != nil {
		t.Fatalf("an adapter without health observation leaves Health nil: %+v", res.Health)
	}
}
