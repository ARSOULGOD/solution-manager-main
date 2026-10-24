package configview

import (
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"
)

var defaultAllow = []string{"server.port", "management.*", "info.*", "logging.level.root"}

var lim = Limits{MaxBytes: 1 << 20, MaxDepth: 32, MaxNodes: 10000, MaxKeys: 1000}

func paths(r Result) []string {
	var p []string
	for _, e := range r.Entries {
		p = append(p, e.Path)
	}
	return p
}

func TestSecretUnderUnexpectedKeyIsHidden(t *testing.T) {
	doc := "server:\n  port: 8080\ndatasource:\n  password: hunter2\napp:\n  api-key: abc\n"
	r, err := Filter([]byte(doc), defaultAllow, lim)
	if err != nil {
		t.Fatal(err)
	}
	if len(r.Entries) != 1 || r.Entries[0] != (Entry{"server.port", "8080"}) {
		t.Fatalf("entries=%v", r.Entries)
	}
	if r.Hidden != 2 {
		t.Fatalf("hidden=%d", r.Hidden)
	}
}

func TestListsFlattenToIndexedPaths(t *testing.T) {
	doc := "management:\n  endpoints:\n    web:\n      exposure:\n        include: [health, info]\n"
	r, err := Filter([]byte(doc), defaultAllow, lim)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"management.endpoints.web.exposure.include[0]", "management.endpoints.web.exposure.include[1]"}
	if got := paths(r); strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("got %v", got)
	}
	// list of maps, hidden element
	r, _ = Filter([]byte("users:\n  - name: a\n    pw: b\n"), []string{"users[0].name"}, lim)
	if len(r.Entries) != 1 || r.Entries[0].Path != "users[0].name" || r.Hidden != 1 {
		t.Fatalf("%v %d", r.Entries, r.Hidden)
	}
}

func TestPrefixWildcardMatchesSubtreeOnly(t *testing.T) {
	doc := "management:\n  server:\n    port: 9\nmanagementx:\n  a: 1\n"
	r, _ := Filter([]byte(doc), []string{"management.*"}, lim)
	if got := paths(r); len(got) != 1 || got[0] != "management.server.port" || r.Hidden != 1 {
		t.Fatalf("%v %d", got, r.Hidden)
	}
	// wildcard does not match the bare scalar key
	r, _ = Filter([]byte("management: x\n"), []string{"management.*"}, lim)
	if len(r.Entries) != 0 || r.Hidden != 1 {
		t.Fatalf("%v %d", r.Entries, r.Hidden)
	}
}

func TestExactPathMatchesOnlyItself(t *testing.T) {
	doc := "server:\n  port: 1\n  port2: 2\n  port:x: 3\n"
	r, _ := Filter([]byte(doc), []string{"server.port"}, lim)
	if len(r.Entries) != 1 || r.Entries[0].Value != "1" || r.Hidden != 2 {
		t.Fatalf("%v %d", r.Entries, r.Hidden)
	}
}

func TestAliasBombRefused(t *testing.T) {
	var b strings.Builder
	b.WriteString("a0: &a0 [x,x,x,x,x,x,x,x,x]\n")
	for i := 1; i < 10; i++ {
		p := fmt.Sprintf("*a%d", i-1)
		fmt.Fprintf(&b, "a%d: &a%d [%s]\n", i, i, strings.Repeat(p+",", 8)+p)
	}
	b.WriteString("management: {b: *a9}\n")
	done := make(chan error, 1)
	go func() { _, err := Filter([]byte(b.String()), defaultAllow, lim); done <- err }()
	select {
	case err := <-done:
		if !errors.Is(err, ErrTooManyKeys) {
			t.Fatalf("err=%v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("alias bomb not bounded")
	}
}

func TestSelfReferentialAliasRefused(t *testing.T) {
	_, err := Filter([]byte("a: &x [*x]\n"), defaultAllow, lim)
	if !errors.Is(err, ErrInvalidYAML) {
		t.Fatalf("err=%v", err)
	}
}

func TestDepthLimit(t *testing.T) {
	doc := strings.Repeat("[", 100) + strings.Repeat("]", 100)
	_, err := Filter([]byte(doc), defaultAllow, Limits{MaxBytes: 1 << 20, MaxDepth: 10, MaxNodes: 10000, MaxKeys: 10})
	if !errors.Is(err, ErrTooLarge) {
		t.Fatalf("err=%v", err)
	}
}

func TestInvalidYAML(t *testing.T) {
	_, err := Filter([]byte("a: [unclosed\n  secretkey: :\n"), defaultAllow, lim)
	if !errors.Is(err, ErrInvalidYAML) {
		t.Fatalf("err=%v", err)
	}
	if strings.Contains(err.Error(), "secretkey") {
		t.Fatal("error leaks content")
	}
}

func TestOversizedRefusedBeforeParse(t *testing.T) {
	_, err := Filter([]byte("a: [unclosed"), defaultAllow, Limits{MaxBytes: 4, MaxDepth: 5, MaxNodes: 5, MaxKeys: 5})
	if !errors.Is(err, ErrTooLarge) {
		t.Fatalf("err=%v", err)
	}
}

func TestMaxKeysTruncates(t *testing.T) {
	doc := "info:\n  a: 1\n  b: 2\n  c: 3\n"
	r, err := Filter([]byte(doc), defaultAllow, Limits{MaxBytes: 1 << 20, MaxDepth: 8, MaxNodes: 100, MaxKeys: 2})
	if err != nil || len(r.Entries) != 2 || !r.Truncated || r.Entries[0].Path != "info.a" {
		t.Fatalf("%v %v %v", r, err, r.Truncated)
	}
	r, _ = Filter([]byte(doc), defaultAllow, Limits{MaxBytes: 1 << 20, MaxDepth: 8, MaxNodes: 100, MaxKeys: 3})
	if r.Truncated || len(r.Entries) != 3 {
		t.Fatalf("%v", r)
	}
}

func TestHiddenKeyNamesNeverAppear(t *testing.T) {
	doc := "server:\n  port: 1\nsupersecretname:\n  tokenzzz: v\n"
	r, err := Filter([]byte(doc), defaultAllow, lim)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range r.Entries {
		if strings.Contains(e.Path+e.Value, "supersecretname") || strings.Contains(e.Path, "tokenzzz") {
			t.Fatalf("leak %v", e)
		}
	}
	_, err = Filter([]byte(strings.Repeat("supersecretname: 1\n", 50)), defaultAllow, Limits{MaxBytes: 1 << 20, MaxDepth: 5, MaxNodes: 5, MaxKeys: 5})
	if err == nil || strings.Contains(err.Error(), "supersecretname") {
		t.Fatalf("err=%v", err)
	}
}

func TestAliasedHugeKeyOutputBounded(t *testing.T) {
	big := strings.Repeat("k", 900*1024)
	var b strings.Builder
	b.WriteString("base: &m\n  ? " + big + "\n  : v\nmanagement:\n")
	for i := 0; i < 1000; i++ {
		fmt.Fprintf(&b, "  a%d: *m\n", i)
	}
	l := Limits{MaxBytes: 2 << 20, MaxDepth: 32, MaxNodes: 10000, MaxKeys: 1000}
	type out struct {
		r   Result
		err error
	}
	done := make(chan out, 1)
	go func() { r, err := Filter([]byte(b.String()), defaultAllow, l); done <- out{r, err} }()
	select {
	case o := <-done:
		total := 0
		for _, e := range o.r.Entries {
			total += len(e.Path) + len(e.Value)
		}
		if total > l.MaxBytes {
			t.Fatalf("emitted %d bytes", total)
		}
		if o.err != nil && !errors.Is(o.err, ErrTooLarge) {
			t.Fatalf("err=%v", o.err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("unbounded work")
	}
}
