package discovery

import (
	"context"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

var t0 = time.Date(2026, 10, 10, 9, 0, 0, 0, time.UTC)

func env() reactor.Env {
	n := 0
	return reactor.Env{Now: t0, NewID: func() string { n++; return "m" + string(rune('0'+n)) }}
}

func newState() State {
	return State{Bindings: Bindings{SchemaVersion: 1, ByName: map[string]string{}}}
}

func ctrs(ids ...string) []msg.DiscoveredContainer {
	var out []msg.DiscoveredContainer
	for _, id := range ids {
		out = append(out, msg.DiscoveredContainer{ContainerID: id, ContainerName: "name-" + id})
	}
	return out
}

func sentReport(effs []reactor.Effect) (msg.DiscoveryReport, bool) {
	for _, e := range effs {
		if s, ok := e.(reactor.Send); ok {
			if r, ok := s.Msg.(msg.DiscoveryReport); ok {
				return r, true
			}
		}
	}
	return msg.DiscoveryReport{}, false
}

func accept(s State) State {
	s, _ = Reduce(s, reactor.WireIn{At: t0, Msg: msg.SessionAccept{}}, env(), true)
	return s
}

// AG-D26 item 2: a complete snapshot after every SessionAccept, and again whenever
// the set or any container id changes -- and only then.
func TestReportIsSentAfterAcceptAndOnChangeOnly(t *testing.T) {
	s := accept(newState())
	s, effs := Reduce(s, reactor.Discovered{At: t0, Items: ctrs("a", "b")}, env(), true)
	r, ok := sentReport(effs)
	if !ok || len(r.Items) != 2 {
		t.Fatalf("%+v", effs)
	}
	_, effs = Reduce(s, reactor.Discovered{At: t0, Items: ctrs("a", "b")}, env(), true)
	if _, ok := sentReport(effs); ok {
		t.Fatal("an unchanged set is not resent")
	}
	s, effs = Reduce(s, reactor.Discovered{At: t0, Items: ctrs("a", "b2")}, env(), true)
	if r, ok := sentReport(effs); !ok || r.Items[1].ContainerID != "b2" {
		t.Fatal("a changed container id is a new complete snapshot")
	}
	s = accept(s) // reconnect: forgotten, the next scan is sent in full
	if _, effs = Reduce(s, reactor.Discovered{At: t0, Items: ctrs("a", "b2")}, env(), true); len(effs) == 0 {
		t.Fatal("after SessionAccept the same set is sent again")
	}
}

func TestNothingIsSentOnAnInactiveSession(t *testing.T) {
	_, effs := Reduce(newState(), reactor.Discovered{At: t0, Items: ctrs("a")}, env(), false)
	if len(effs) != 0 {
		t.Fatalf("%+v", effs)
	}
}

// AG-D26 item 4: the result for a registered container carries its binding id; the
// agent persists container_name -> binding_id.
func TestAckPersistsNameToBindingAndRetiredRemovesIt(t *testing.T) {
	s := accept(newState())
	s, effs := Reduce(s, reactor.Discovered{At: t0, Items: ctrs("a", "b")}, env(), true)
	rep, _ := sentReport(effs)
	s, effs = Reduce(s, reactor.WireIn{At: t0, Msg: msg.DiscoveryAck{InReplyToMessageID: rep.MessageID, Results: []msg.DiscoveryResult{
		{ContainerID: "a", State: msg.DiscoveryRegistered, BindingID: "placement-1"},
		{ContainerID: "b", State: msg.DiscoveryRefused, Code: "discovery.refused"},
	}}}, env(), true)
	if s.Bindings.ByName["name-a"] != "placement-1" || len(s.Bindings.ByName) != 1 {
		t.Fatalf("%v", s.Bindings.ByName)
	}
	if len(effs) != 1 {
		t.Fatalf("the mapping is persisted: %+v", effs)
	}
	if p, ok := effs[0].(reactor.Persist); !ok || p.Rec.Rel() != "discovery/bindings.json" {
		t.Fatalf("%+v", effs[0])
	}
	// the DP retires the placement
	s, _ = Reduce(s, reactor.Discovered{At: t0, Items: ctrs("a", "c")}, env(), true)
	s, _ = Reduce(s, reactor.WireIn{At: t0, Msg: msg.DiscoveryAck{Results: []msg.DiscoveryResult{
		{ContainerID: "a", State: msg.DiscoveryRetired, BindingID: "placement-1"}}}}, env(), true)
	if len(s.Bindings.ByName) != 0 {
		t.Fatalf("%v", s.Bindings.ByName)
	}
}

func TestAnAckForASupersededSnapshotIsIgnored(t *testing.T) {
	s := accept(newState())
	s, _ = Reduce(s, reactor.Discovered{At: t0, Items: ctrs("a")}, env(), true)
	s, _ = Reduce(s, reactor.Discovered{At: t0, Items: ctrs("a", "b")}, env(), true)
	s, effs := Reduce(s, reactor.WireIn{At: t0, Msg: msg.DiscoveryAck{InReplyToMessageID: "an-older-report", Results: []msg.DiscoveryResult{
		{ContainerID: "a", State: msg.DiscoveryRegistered, BindingID: "p"}}}}, env(), true)
	if len(effs) != 0 || len(s.Bindings.ByName) != 0 {
		t.Fatal("a stale answer must not bind a name")
	}
}

func TestBindingsSurviveARestart(t *testing.T) {
	st := store.MustOpen(t.TempDir())
	s := accept(newState())
	s, _ = Reduce(s, reactor.Discovered{At: t0, Items: ctrs("a")}, env(), true)
	_, effs := Reduce(s, reactor.WireIn{At: t0, Msg: msg.DiscoveryAck{Results: []msg.DiscoveryResult{
		{ContainerID: "a", State: msg.DiscoveryRegistered, BindingID: "placement-1"}}}}, env(), true)
	for _, e := range effs {
		if p, ok := e.(reactor.Persist); ok {
			if err := st.Save(p.Rec); err != nil {
				t.Fatal(err)
			}
		}
	}
	loaded, err := Load(st)
	if err != nil || loaded.Bindings.ByName["name-a"] != "placement-1" {
		t.Fatal(err, loaded.Bindings)
	}
}

type innerAdapter struct {
	runtime.Adapter
	resolved []string
	byName   []string
}

func (i *innerAdapter) Resolve(_ context.Context, b string) (runtime.Placement, error) {
	i.resolved = append(i.resolved, b)
	return runtime.Placement{BindingID: b, Name: "by-label"}, nil
}
func (i *innerAdapter) ResolveByName(_ context.Context, name, b string) (runtime.Placement, error) {
	i.byName = append(i.byName, name)
	return runtime.Placement{BindingID: b, Name: name}, nil
}

// A registered binding resolves by container name; any other binding falls back to
// the adapter's own (development) lookup.
func TestAdapterResolvesRegisteredBindingsByName(t *testing.T) {
	in := &innerAdapter{}
	names := NewNames()
	names.Set(map[string]string{"cache-a": "placement-1"})
	a := Wrap(in, names)
	if p, _ := a.Resolve(context.Background(), "placement-1"); p.Name != "cache-a" || len(in.byName) != 1 || len(in.resolved) != 0 {
		t.Fatalf("%+v %+v", p, in)
	}
	if p, _ := a.Resolve(context.Background(), "dev-binding"); p.Name != "by-label" || len(in.resolved) != 1 {
		t.Fatalf("%+v %+v", p, in)
	}
	if Wrap(nil, names) != nil {
		t.Fatal("no adapter, no wrapper")
	}
	if _, _, err := a.(*Adapter).Discover(context.Background()); err != ErrUnsupported {
		t.Fatal(err)
	}
	if h := a.(*Adapter).ObserveHealth(context.Background(), runtime.Placement{}, nil); h.State != msg.HealthUnspecified {
		t.Fatal("an inner adapter that cannot observe health yields 'nothing observed'")
	}
}

// review finding 8: one binding id is mapped by one container name. A re-registration under a new name
// removes the old name, so NameOf never has two answers.
func TestARegistrationReplacesAnyOtherNameForTheSameBinding(t *testing.T) {
	s := accept(newState())
	s, effs := Reduce(s, reactor.Discovered{At: t0, Items: ctrs("a")}, env(), true)
	rep, _ := sentReport(effs)
	s, _ = Reduce(s, reactor.WireIn{At: t0, Msg: msg.DiscoveryAck{InReplyToMessageID: rep.MessageID, Results: []msg.DiscoveryResult{
		{ContainerID: "a", State: msg.DiscoveryRegistered, BindingID: "placement-1"}}}}, env(), true)
	s, effs = Reduce(s, reactor.Discovered{At: t0, Items: ctrs("c")}, env(), true)
	rep, _ = sentReport(effs)
	s, _ = Reduce(s, reactor.WireIn{At: t0, Msg: msg.DiscoveryAck{InReplyToMessageID: rep.MessageID, Results: []msg.DiscoveryResult{
		{ContainerID: "c", State: msg.DiscoveryRegistered, BindingID: "placement-1"}}}}, env(), true)
	if len(s.Bindings.ByName) != 1 || s.Bindings.ByName["name-c"] != "placement-1" {
		t.Fatalf("%v", s.Bindings.ByName)
	}
	n := NewNames()
	n.Set(map[string]string{"x": "p", "y": "p"})
	if _, ok := n.NameOf("p"); ok {
		t.Fatal("an ambiguous binding resolves to nothing rather than to a map-order accident")
	}
}
