// Package declare reads the agent's interim metric declarations (AG-D22):
// per binding, entries shaped like the Solution Package schema's metric
// object (contracts/solution-package.schema.json), restricted to what this
// slice collects. The plane does not deliver declarations yet (AG-14).
package declare

import (
	"errors"
	"fmt"
	"io"
	"maps"
	"os"
	"regexp"
	"slices"
	"strings"
	"time"

	"gopkg.in/yaml.v3"

	"github.com/solace/solution-manager/go-agent/internal/telemetry/jsonptr"
)

type File struct {
	SchemaVersion int                `yaml:"schema_version"`
	Bindings      map[string]Binding `yaml:"bindings"`
}

type Binding struct {
	Interval time.Duration `yaml:"interval"`
	Timeout  time.Duration `yaml:"timeout"` // default min(interval, 5s)
	Metrics  []Metric      `yaml:"metrics"`
}

// Metric mirrors the schema's metric object: name, metricType, unit and
// source are required there, so they are required here. unit is not sent —
// the DP normalizes units from the declared contract — but an entry without
// it would not survive the move to a plane-delivered source.
type Metric struct {
	Name       string  `yaml:"name"`
	MetricType string  `yaml:"metricType"`
	Unit       string  `yaml:"unit"`
	When       *string `yaml:"when"` // refused in this slice
	Source     Source  `yaml:"source"`
}

type Source struct {
	Kind          string  `yaml:"kind"`
	Port          int     `yaml:"port"`
	Path          string  `yaml:"path"`
	Pointer       *string `yaml:"pointer"` // required; "" is the whole document
	OnUnavailable string  `yaml:"onUnavailable"`
}

// safeID: a binding id names a buffer directory.
var safeID = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)

var laterKinds = map[string]bool{"prometheus": true, "solace_event": true, "exec": true, "jdbc_query": true}

func Load(path string) (*File, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("declarations: %w", err)
	}
	defer f.Close()
	return Parse(f)
}

// Parse decodes strictly — an unknown key is an error, as the schema's
// additionalProperties: false makes it — then validates everything.
func Parse(r io.Reader) (*File, error) {
	dec := yaml.NewDecoder(r)
	dec.KnownFields(true)
	var f File
	if err := dec.Decode(&f); err != nil {
		return nil, fmt.Errorf("declarations: %w", err)
	}
	if err := f.validate(); err != nil {
		return nil, err
	}
	for id, b := range f.Bindings {
		if b.Timeout == 0 {
			b.Timeout = min(b.Interval, 5*time.Second)
			f.Bindings[id] = b
		}
	}
	return &f, nil
}

func (f *File) validate() error {
	if f.SchemaVersion != 1 {
		return fmt.Errorf("declarations: schema_version %d, want 1", f.SchemaVersion)
	}
	if len(f.Bindings) == 0 {
		return errors.New("declarations: no bindings")
	}
	for _, id := range slices.Sorted(maps.Keys(f.Bindings)) {
		b := f.Bindings[id]
		where := fmt.Sprintf("declarations: binding %q", id)
		switch {
		case !safeID.MatchString(id) || id == "." || id == "..":
			return fmt.Errorf("%s: id must match %s", where, safeID)
		case b.Interval < time.Second:
			return fmt.Errorf("%s: interval must be at least 1s", where)
		case b.Timeout < 0 || b.Timeout > b.Interval:
			return fmt.Errorf("%s: timeout must be between 0 and interval", where)
		case len(b.Metrics) == 0:
			return fmt.Errorf("%s: no metrics", where)
		}
		seen := map[string]bool{}
		for i, m := range b.Metrics {
			at := fmt.Sprintf("%s metrics[%d]", where, i)
			switch {
			case m.Name == "":
				return fmt.Errorf("%s: name is required", at)
			case seen[m.Name]:
				return fmt.Errorf("%s: duplicate name %q", at, m.Name)
			case m.MetricType == "histogram":
				return fmt.Errorf("%s: metricType histogram is not in this slice", at)
			case m.MetricType != "counter" && m.MetricType != "gauge":
				return fmt.Errorf("%s: metricType must be counter or gauge", at)
			case m.Unit == "":
				return fmt.Errorf("%s: unit is required", at)
			case m.When != nil:
				return fmt.Errorf("%s: when is not in this slice", at)
			}
			seen[m.Name] = true
			if err := m.Source.validate(); err != nil {
				return fmt.Errorf("%s: source: %w", at, err)
			}
		}
	}
	return nil
}

func (s Source) validate() error {
	switch {
	case laterKinds[s.Kind]:
		return fmt.Errorf("kind %s is not in this slice", s.Kind)
	case s.Kind != "http_json":
		return fmt.Errorf("kind %q is not a schema source kind", s.Kind)
	case s.Port < 1 || s.Port > 65535:
		return fmt.Errorf("port %d out of range", s.Port)
	case !strings.HasPrefix(s.Path, "/"):
		return fmt.Errorf("path %q must start with /", s.Path)
	case s.Pointer == nil:
		return errors.New("pointer is required")
	case !jsonptr.Valid(*s.Pointer):
		return fmt.Errorf("pointer %q is not a JSON Pointer (RFC 6901)", *s.Pointer)
	case s.OnUnavailable != "degrade" && s.OnUnavailable != "error":
		return errors.New("onUnavailable must be degrade or error")
	}
	return nil
}
