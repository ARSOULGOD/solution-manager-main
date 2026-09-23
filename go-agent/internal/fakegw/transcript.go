package fakegw

import (
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
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

func (t *transcript) received(kind string) []msg.Outbound {
	var out []msg.Outbound
	for _, e := range t.all() {
		if e.Dir == "in" && e.Kind == kind {
			out = append(out, e.Msg.(msg.Outbound))
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
