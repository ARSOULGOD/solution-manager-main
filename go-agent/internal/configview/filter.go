// Package configview filters an MI config file down to an allow-list of
// scalar settings. It is pure: no I/O, no logging, and fixed-text errors only
// (I6: a key name or value outside the allow-list never leaves via an error).
package configview

import (
	"errors"
	"strconv"
	"strings"

	"gopkg.in/yaml.v3"
)

// Fixed-text errors. They never embed document content.
var (
	// ErrTooLarge: the input exceeds MaxBytes, or nesting exceeds MaxDepth.
	ErrTooLarge = errors.New("config document too large")
	// ErrInvalidYAML: the document does not parse, or has a cyclic alias.
	ErrInvalidYAML = errors.New("config document is not valid YAML")
	// ErrTooManyKeys: the document (aliases expanded) exceeds MaxNodes. This is
	// a refusal of the whole document and is distinct from Result.Truncated,
	// which only means the allow-listed output was cut at MaxKeys.
	ErrTooManyKeys = errors.New("config document has too many nodes")
)

// Limits bounds the work done for one document.
type Limits struct{ MaxBytes, MaxDepth, MaxNodes, MaxKeys int }

// Entry is one emitted scalar setting.
type Entry struct{ Path, Value string }

// Result: Entries in document order; Hidden counts every scalar leaf not
// emitted (not allow-listed, or beyond MaxKeys); Truncated is set when
// allow-listed entries were dropped at the MaxKeys cap.
type Result struct {
	Entries   []Entry
	Hidden    int
	Truncated bool
}

type measure struct {
	size, height int
}

type walker struct {
	allow   []string
	lim     Limits
	memo    map[*yaml.Node]measure
	active  map[*yaml.Node]bool
	res     Result
	cyclic  bool
	tooMany bool
	tooDeep bool
}

// Filter parses doc and returns allow-listed scalars. Allow entries match
// exactly, or as "prefix.*" which matches only paths strictly below "prefix.".
func Filter(doc []byte, allow []string, lim Limits) (Result, error) {
	if len(doc) > lim.MaxBytes {
		return Result{}, ErrTooLarge
	}
	var root yaml.Node
	if err := yaml.Unmarshal(doc, &root); err != nil {
		return Result{}, ErrInvalidYAML
	}
	if root.Kind == 0 {
		return Result{}, nil
	}
	w := &walker{allow: allow, lim: lim, memo: map[*yaml.Node]measure{}, active: map[*yaml.Node]bool{}}
	// Pass 1: measure the alias-expanded size and height WITHOUT expanding,
	// memoised per node, so a billion-laughs shape costs O(distinct nodes).
	m := w.measure(&root, 1)
	switch {
	case w.cyclic:
		return Result{}, ErrInvalidYAML
	case w.tooDeep || m.height > lim.MaxDepth:
		return Result{}, ErrTooLarge
	case w.tooMany || m.size > lim.MaxNodes:
		return Result{}, ErrTooManyKeys
	}
	// Pass 2: the expanded tree is now provably within MaxNodes.
	w.walk(&root, "", false)
	return w.res, nil
}

func (w *walker) measure(n *yaml.Node, depth int) measure {
	if w.cyclic || w.tooDeep || w.tooMany {
		return measure{}
	}
	if depth > w.lim.MaxDepth {
		w.tooDeep = true
		return measure{}
	}
	if m, ok := w.memo[n]; ok {
		return m
	}
	if w.active[n] {
		w.cyclic = true
		return measure{}
	}
	w.active[n] = true
	m := measure{size: 1, height: 1}
	children := n.Content
	if n.Kind == yaml.AliasNode {
		children = []*yaml.Node{n.Alias}
		if n.Alias == nil {
			w.cyclic = true
			return measure{}
		}
		m = measure{}
	}
	for _, c := range children {
		cm := w.measure(c, depth+1)
		m.size += cm.size
		if m.size > w.lim.MaxNodes {
			w.tooMany = true
			m.size = w.lim.MaxNodes + 1 // saturate; no overflow
		}
		if cm.height+1 > m.height {
			m.height = cm.height + 1
		}
	}
	delete(w.active, n)
	w.memo[n] = m
	return m
}

func (w *walker) walk(n *yaml.Node, path string, hideAll bool) {
	switch n.Kind {
	case yaml.DocumentNode:
		for _, c := range n.Content {
			w.walk(c, path, hideAll)
		}
	case yaml.AliasNode:
		w.walk(n.Alias, path, hideAll)
	case yaml.MappingNode:
		for i := 0; i+1 < len(n.Content); i += 2 {
			k := n.Content[i]
			if k.Kind != yaml.ScalarNode {
				w.walk(n.Content[i+1], path, true) // complex key: hide subtree
				continue
			}
			p := k.Value
			if path != "" {
				p = path + "." + k.Value
			}
			w.walk(n.Content[i+1], p, hideAll)
		}
	case yaml.SequenceNode:
		for i, c := range n.Content {
			w.walk(c, path+"["+strconv.Itoa(i)+"]", hideAll)
		}
	case yaml.ScalarNode:
		if hideAll || !w.allowed(path) {
			w.res.Hidden++
			return
		}
		if len(w.res.Entries) >= w.lim.MaxKeys {
			w.res.Truncated = true
			w.res.Hidden++
			return
		}
		w.res.Entries = append(w.res.Entries, Entry{Path: path, Value: n.Value})
	}
}

func (w *walker) allowed(path string) bool {
	for _, a := range w.allow {
		if strings.HasSuffix(a, ".*") {
			if strings.HasPrefix(path, a[:len(a)-1]) {
				return true
			}
		} else if a == path {
			return true
		}
	}
	return false
}
