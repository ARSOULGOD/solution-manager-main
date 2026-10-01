// Package jsonptr resolves RFC 6901 JSON Pointers over a document decoded by
// encoding/json with UseNumber — the package schema's source.pointer (UI-D14).
package jsonptr

import (
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
)

var ErrNotFound = errors.New("jsonptr: no such member")

// Valid reports whether p is a JSON Pointer: "" (the whole document), or
// "/"-prefixed tokens in which every "~" is followed by "0" or "1".
func Valid(p string) bool {
	if p == "" {
		return true
	}
	if p[0] != '/' {
		return false
	}
	for i := 0; i < len(p); i++ {
		if p[i] == '~' && (i+1 >= len(p) || (p[i+1] != '0' && p[i+1] != '1')) {
			return false
		}
	}
	return true
}

func Resolve(doc any, p string) (any, error) {
	if !Valid(p) {
		return nil, fmt.Errorf("jsonptr: invalid pointer %q", p)
	}
	if p == "" {
		return doc, nil
	}
	cur := doc
	for _, raw := range strings.Split(p[1:], "/") {
		tok := strings.ReplaceAll(strings.ReplaceAll(raw, "~1", "/"), "~0", "~") // RFC 6901 §4 order
		switch v := cur.(type) {
		case map[string]any:
			next, ok := v[tok]
			if !ok {
				return nil, fmt.Errorf("%w: %q", ErrNotFound, tok)
			}
			cur = next
		case []any:
			i, ok := index(tok, len(v))
			if !ok {
				return nil, fmt.Errorf("%w: index %q", ErrNotFound, tok)
			}
			cur = v[i]
		default:
			return nil, fmt.Errorf("%w: %q is below a scalar", ErrNotFound, tok)
		}
	}
	return cur, nil
}

// index accepts RFC 6901 array indexes: "0", or digits without a leading
// zero. "-" names the element after the last, which a read cannot resolve.
func index(tok string, n int) (int, bool) {
	if tok == "" || (len(tok) > 1 && tok[0] == '0') {
		return 0, false
	}
	for _, c := range tok {
		if c < '0' || c > '9' {
			return 0, false
		}
	}
	i, err := strconv.Atoi(tok)
	return i, err == nil && i < n
}

// Number reports a JSON number as a float64. A string, bool, null, object or
// array is not one — and the collector emits no sample for it, never a zero.
func Number(v any) (float64, bool) {
	n, ok := v.(json.Number)
	if !ok {
		return 0, false
	}
	f, err := n.Float64()
	return f, err == nil
}
