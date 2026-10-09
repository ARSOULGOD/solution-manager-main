package jsonptr

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

const rfcDoc = `{"foo": ["bar", "baz"], "": 0, "a/b": 1, "c%d": 2, "e^f": 3, "g|h": 4, "i\\j": 5, "k\"l": 6, " ": 7, "m~n": 8}`

func decode(t *testing.T, s string) any {
	t.Helper()
	dec := json.NewDecoder(strings.NewReader(s))
	dec.UseNumber()
	var v any
	if err := dec.Decode(&v); err != nil {
		t.Fatal(err)
	}
	return v
}

func TestResolvesTheRFC6901Examples(t *testing.T) {
	doc := decode(t, rfcDoc)
	for ptr, want := range map[string]float64{"/": 0, "/a~1b": 1, "/c%d": 2, "/e^f": 3, "/g|h": 4, "/i\\j": 5, "/k\"l": 6, "/ ": 7, "/m~0n": 8} {
		v, err := Resolve(doc, ptr)
		got, ok := Number(v)
		if err != nil || !ok || got != want {
			t.Errorf("%q: got %v %v %v, want %v", ptr, v, ok, err, want)
		}
	}
	if v, err := Resolve(doc, "/foo/0"); err != nil || v != "bar" {
		t.Errorf("/foo/0: %v %v", v, err)
	}
	if v, err := Resolve(doc, ""); err != nil || v == nil {
		t.Errorf(`"" must be the whole document: %v %v`, v, err)
	}
}

func TestMissingMembersAndBadIndexesAreNotFound(t *testing.T) {
	doc := decode(t, rfcDoc)
	for _, ptr := range []string{"/x", "/foo/2", "/foo/01", "/foo/-", "/foo/0/x"} {
		if _, err := Resolve(doc, ptr); !errors.Is(err, ErrNotFound) {
			t.Errorf("%q: want ErrNotFound, got %v", ptr, err)
		}
	}
}

func TestValid(t *testing.T) {
	for p, want := range map[string]bool{"": true, "/": true, "/a~0b~1c": true, "a": false, "/~2": false, "/~": false} {
		if Valid(p) != want {
			t.Errorf("Valid(%q) = %v, want %v", p, !want, want)
		}
	}
}

func TestNumberAcceptsOnlyJSONNumbers(t *testing.T) {
	for _, v := range []any{"1.5", true, nil, map[string]any{}} {
		if _, ok := Number(v); ok {
			t.Errorf("Number(%#v) must not be a number", v)
		}
	}
	if f, ok := Number(json.Number("1.5")); !ok || f != 1.5 {
		t.Errorf("json.Number(1.5) → %v %v", f, ok)
	}
}
