package fakegw

import (
	"fmt"
	"strings"
	"sync"
	"time"
)

type Entry struct {
	At   time.Time
	Dir  string // "in" (agent → fake) | "out" (fake → agent)
	Kind string // Go type name without package, e.g. "SessionOpen"
	Msg  any
}

type transcript struct {
	mu      sync.Mutex
	entries []Entry
	cond    *sync.Cond
}

func newTranscript() *transcript {
	t := &transcript{}
	t.cond = sync.NewCond(&t.mu)
	return t
}

func kindOf(m any) string {
	s := fmt.Sprintf("%T", m)
	if i := strings.LastIndex(s, "."); i >= 0 {
		return s[i+1:]
	}
	return s
}

func (t *transcript) add(dir string, m any) {
	t.mu.Lock()
	t.entries = append(t.entries, Entry{At: time.Now(), Dir: dir, Kind: kindOf(m), Msg: m})
	t.mu.Unlock()
	t.cond.Broadcast()
}

func (t *transcript) all() []Entry {
	t.mu.Lock()
	defer t.mu.Unlock()
	out := make([]Entry, len(t.entries))
	copy(out, t.entries)
	return out
}

func (t *transcript) count(kind string) int {
	n := 0
	for _, e := range t.all() {
		if e.Dir == "in" && e.Kind == kind {
			n++
		}
	}
	return n
}

// received returns any (not just msg.Outbound), because the transcript also
// carries the unary Enroll RPC's msg.EnrollRequest/msg.EnrollResponse, which
// are deliberately outside the Outbound/Inbound stream-message vocabulary
// (Enroll precedes Connect and has no Envelope). Narrowing this to
// []msg.Outbound made every "EnrollRequest"/"EnrollResponse" lookup an
// unconditional type assertion to an interface those types never satisfy —
// go vet flags it as an impossible conversion, and at runtime it would
// panic.
func (t *transcript) received(kind string) []any {
	var out []any
	for _, e := range t.all() {
		if e.Dir == "in" && e.Kind == kind {
			out = append(out, e.Msg)
		}
	}
	return out
}

// waitFor blocks until n inbound entries of kind exist or timeout passes.
func (t *transcript) waitFor(kind string, n int, timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	timer := time.AfterFunc(timeout, func() { t.cond.Broadcast() })
	defer timer.Stop()
	t.mu.Lock()
	defer t.mu.Unlock()
	for {
		c := 0
		for _, e := range t.entries {
			if e.Dir == "in" && e.Kind == kind {
				c++
			}
		}
		if c >= n {
			return true
		}
		if time.Now().After(deadline) {
			return false
		}
		t.cond.Wait()
	}
}

// waitForFrom blocks until an inbound entry of kind exists at index ≥ from,
// returning the index after it. This is what scripted `expect` steps use, so
// a message that arrived before the step started still matches.
func (t *transcript) waitForFrom(kind string, from int, timeout time.Duration) (int, bool) {
	deadline := time.Now().Add(timeout)
	timer := time.AfterFunc(timeout, func() { t.cond.Broadcast() })
	defer timer.Stop()
	t.mu.Lock()
	defer t.mu.Unlock()
	for {
		for i := from; i < len(t.entries); i++ {
			if t.entries[i].Dir == "in" && t.entries[i].Kind == kind {
				return i + 1, true
			}
		}
		if time.Now().After(deadline) {
			return from, false
		}
		t.cond.Wait()
	}
}

func (t *transcript) length() int {
	t.mu.Lock()
	defer t.mu.Unlock()
	return len(t.entries)
}

// waitScan blocks until scan, given the inbound-and-outbound entries from index
// `from` on, reports a match; it returns the index after the matching entry.
// Used by the log expectations, which match on a request id and on totals
// across several entries rather than on a kind alone.
func (t *transcript) waitScan(from int, scan func(es []Entry) (end int, ok bool), timeout time.Duration) (int, bool) {
	deadline := time.Now().Add(timeout)
	timer := time.AfterFunc(timeout, func() { t.cond.Broadcast() })
	defer timer.Stop()
	t.mu.Lock()
	defer t.mu.Unlock()
	for {
		if from <= len(t.entries) {
			if end, ok := scan(t.entries[from:]); ok {
				return from + end, true
			}
		}
		if time.Now().After(deadline) {
			return from, false
		}
		t.cond.Wait()
	}
}
