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

	"github.com/solace/solution-manager/go-agent/internal/authority"
	"github.com/solace/solution-manager/go-agent/internal/commands"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/runtime/configwriter"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

func leaseWith(ceilings map[msg.Operation]time.Duration) authority.State {
	g := msg.LeaseGrant{Capabilities: []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle}, BindingIDs: []string{"b"},
		ExpiresAt: time.Now().Add(time.Hour), VerbCeilings: ceilings, AdmissionMargin: time.Second}
	return authority.State{Phase: authority.PhaseActive, Grant: &g}
}

// Review finding 2 / AG-D27 item 5 (R-7): a lease with a rematerialize ceiling admits
// OPERATION_REMATERIALIZE; a lease WITHOUT the key gives it a zero ceiling and the
// command is refused with a code BEFORE dispatch, never started and then timed out.
func TestLeaseWithAndWithoutTheRematerializeCeiling(t *testing.T) {
	caps := []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle, msg.CapRematerialize}
	with := gate{State: leaseWith(map[msg.Operation]time.Duration{msg.OpRematerialize: time.Minute}), supported: caps}
	if got := with.RefusalDetail(msg.OpRematerialize, "b", time.Now()); got != msg.DetailNone {
		t.Fatalf("a lease that has the key admits it: %q", got)
	}
	without := gate{State: leaseWith(map[msg.Operation]time.Duration{msg.OpApply: time.Minute}), supported: caps}
	if got := without.RefusalDetail(msg.OpRematerialize, "b", time.Now()); got == msg.DetailNone {
		t.Fatal("a lease without the key must not admit it")
	}
	if got := without.RefusalDetail(msg.OpApply, "b", time.Now()); got != msg.DetailNone {
		t.Fatalf("the keys it has still work: %q", got)
	}
}

// Review finding 13: the gate's feature-capability branch.
func TestGateRefusesRematerializeFromAnAgentThatDidNotAdvertiseIt(t *testing.T) {
	a := leaseWith(map[msg.Operation]time.Duration{msg.OpRematerialize: time.Minute, msg.OpApply: time.Minute})
	g := gate{State: a, supported: []msg.Capability{msg.CapApplyConfig, msg.CapLifecycle}} // no CapRematerialize
	if got := g.RefusalDetail(msg.OpRematerialize, "b", time.Now()); got != msg.DetailOf(msg.ErrCapabilityMissing) {
		t.Fatalf("got %q", got)
	}
	if got := g.RefusalDetail(msg.OpApply, "b", time.Now()); got != msg.DetailNone {
		t.Fatalf("apply is unaffected: %q", got)
	}
}

// Review finding 13: the worker's own guard against a REMATERIALIZE without a precondition.
func TestWorkerRefusesRematerializeWithoutAPrecondition(t *testing.T) {
	st := store.MustOpen(t.TempDir())
	_ = st.Save(store.Blob{RelPath: commands.PayloadRel("b1", 1), Data: []byte("x")})
	a := &realWriteAdapter{stubAdapter: stubAdapter{place: runtime.Placement{BindingID: "b1", HostPath: filepath.Join(t.TempDir(), "a.yml")}}}
	r := &opRunner{adapter: a, store: st, push: func(reactor.Event) {}, now: time.Now}
	res := r.Run(context.Background(), reactor.RunOp{CommandID: "c", BindingID: "b1", Op: msg.OpRematerialize,
		PayloadRel: commands.PayloadRel("b1", 1), SHA256: "h", Deadline: time.Now().Add(time.Minute)})
	if res.Outcome != msg.OutcomeRefused || res.Detail.Code != msg.DetailOf(msg.ErrKeyHashMismatch) || a.writes != 0 {
		t.Fatalf("%+v writes=%d", res, a.writes)
	}
}

// Review finding 15: the resolver's sentinels get their own templates, not "(*fmt.wrapError)".
func TestDetailTemplatesForResolverSentinels(t *testing.T) {
	for _, e := range []error{runtime.ErrContainerNotFound, runtime.ErrContainerAmbiguous, runtime.ErrNoDeclaredPath, runtime.ErrNoBindMount, runtime.ErrPortUnreachable} {
		got := detailMessage(fmt.Errorf("resolve b1: %w: name=secret-ish", e))
		if strings.Contains(got, "wrapError") || strings.Contains(got, "secret-ish") || got == "" {
			t.Errorf("%v -> %q", e, got)
		}
	}
	if a, b := detailMessage(fmt.Errorf("x: %w", runtime.ErrContainerNotFound)), detailMessage(fmt.Errorf("x: %w", runtime.ErrNoBindMount)); a == b {
		t.Fatal("distinct sentinels, distinct templates")
	}
	if got := detailMessage(errors.New("plain")); !strings.Contains(got, "runtime operation failed") {
		t.Fatal(got)
	}
}

// Review finding 7: a conditional writer evaluates the precondition on the very read the
// write is based on; the worker uses it when the adapter offers it.
type conditionalAdapter struct {
	realWriteAdapter
	gotExpected *string
	moved       bool
}

func (c *conditionalAdapter) WriteConfigIf(_ context.Context, p runtime.Placement, payload []byte, sha string, expected *string, pr runtime.Progress) (runtime.WriteResult, error) {
	c.gotExpected = expected
	if c.moved {
		return runtime.WriteResult{}, configwriter.ErrTargetMoved
	}
	return configwriter.WriteIf(p.HostPath, payload, sha, expected, -1, -1, pr)
}

func TestRematerializeUsesTheConditionalWriterWhenOffered(t *testing.T) {
	for _, moved := range []bool{false, true} {
		st := store.MustOpen(t.TempDir())
		_ = st.Save(store.Blob{RelPath: commands.PayloadRel("b1", 1), Data: []byte("n: 1\n")})
		host := filepath.Join(t.TempDir(), "application.yml")
		_ = os.WriteFile(host, []byte("cur\n"), 0o600)
		a := &conditionalAdapter{moved: moved}
		a.place = runtime.Placement{BindingID: "b1", HostPath: host}
		a.facts = []runtime.ReplicaFacts{{Running: true, StartedAt: time.Unix(5, 0)}}
		exp := configwriter.SHA256([]byte("cur\n"))
		r := &opRunner{adapter: a, store: st, push: func(reactor.Event) {}, now: time.Now}
		res := r.Run(context.Background(), reactor.RunOp{CommandID: "c", BindingID: "b1", Op: msg.OpRematerialize, PayloadRel: commands.PayloadRel("b1", 1),
			SHA256: configwriter.SHA256([]byte("n: 1\n")), ExpectedCurrentSHA256: &exp, Deadline: time.Now().Add(time.Minute)})
		if a.gotExpected == nil || *a.gotExpected != exp {
			t.Fatal("the precondition must reach the writer")
		}
		if moved && (res.Outcome != msg.OutcomeRefused || res.Detail.Code != msg.DetailOf(msg.ErrTargetMoved)) {
			t.Fatalf("a writer-side TARGET_MOVED is a REFUSED: %+v", res)
		}
		if !moved && res.Outcome != msg.OutcomeApplied {
			t.Fatalf("%+v", res)
		}
	}
}

// Review finding 2 (optional part): a step whose context is already over never reaches the writer.
func TestExpiredContextNeverReachesTheWriter(t *testing.T) {
	st := store.MustOpen(t.TempDir())
	_ = st.Save(store.Blob{RelPath: commands.PayloadRel("b1", 1), Data: []byte("x")})
	a := &stubAdapter{facts: []runtime.ReplicaFacts{{Running: true}}}
	r := &opRunner{adapter: a, store: st, push: func(reactor.Event) {}, now: time.Now}
	res := r.Run(context.Background(), reactor.RunOp{CommandID: "c", BindingID: "b1", Op: msg.OpApply, PayloadRel: commands.PayloadRel("b1", 1),
		SHA256: "h", Deadline: time.Now().Add(-time.Second)})
	if res.Outcome != msg.OutcomeFailedNoEffect || a.wrote != nil {
		t.Fatalf("%+v wrote=%q", res, a.wrote)
	}
}
