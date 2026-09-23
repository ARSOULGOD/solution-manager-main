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
