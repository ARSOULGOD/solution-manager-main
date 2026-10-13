package view

import (
	"sync"
	"testing"
)

type ids map[string][2]string

func (m ids) IDs(b, r, i string) (string, string, bool) {
	v, ok := m[b+"|"+r+"|"+i]
	return v[0], v[1], ok
}

func TestPublishWakesWaitersAndReplacesTheSnapshot(t *testing.T) {
	h := NewHub()
	ch := h.Changed()
	h.Publish(Snapshot{Bindings: map[string]bool{"binding-1": true}})
	select {
	case <-ch:
	default:
		t.Fatal("Publish did not close the previous Changed channel")
	}
	if !h.Current().InLease("binding-1") {
		t.Fatal("Current is not the published snapshot")
	}
}

func TestTheZeroSnapshotIsSafeAndEmpty(t *testing.T) {
	var s Snapshot
	if s.InLease("binding-1") {
		t.Fatal("zero snapshot claims a binding")
	}
	if _, _, ok := s.Lookup("b", "r", "i"); ok {
		t.Fatal("zero snapshot resolves ids")
	}
}

func TestAHaltedSnapshotLeasesNothing(t *testing.T) {
	s := Snapshot{Bindings: map[string]bool{"binding-1": true}, Halted: true}
	if s.InLease("binding-1") {
		t.Fatal("a halted agent must collect nothing")
	}
}

func TestLookupGoesThroughTheTable(t *testing.T) {
	s := Snapshot{IDs: ids{"b|r|i": {"rep", "inc"}}}
	if r, i, ok := s.Lookup("b", "r", "i"); !ok || r != "rep" || i != "inc" {
		t.Fatalf("%q %q %v", r, i, ok)
	}
}

func TestConcurrentReadersAndPublisher(t *testing.T) {
	h := NewHub()
	var wg sync.WaitGroup
	for range 4 {
		wg.Go(func() {
			for range 1000 {
				_ = h.Current().InLease("b")
				_ = h.Changed()
			}
		})
	}
	for range 1000 {
		h.Publish(Snapshot{Bindings: map[string]bool{"b": true}})
	}
	wg.Wait()
}
