// Package commands owns the apply journal (AG-D4) and the command reducer.
package commands

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

const schemaVersion = 1

type Record struct {
	SchemaVersion     int              `json:"schema_version"`
	RolloutGeneration uint64           `json:"rollout_generation"`
	IdempotencyKey    string           `json:"idempotency_key"`
	CommandID         string           `json:"command_id"`
	BindingID         string           `json:"binding_id"`
	Operation         msg.Operation    `json:"operation"`
	TargetPath        string           `json:"target_path"`
	ConfigHash        string           `json:"config_hash"`
	Step              msg.ProgressStep `json:"step"`
	StepDeadline      time.Time        `json:"step_deadline"`
	TempPath          string           `json:"temp_path,omitempty"`
	Outcome           msg.Outcome      `json:"outcome,omitempty"`
	Detail            msg.AckDetail    `json:"detail,omitempty"`
	ReceivedAck       bool             `json:"received_ack"`
	AckSent           bool             `json:"ack_sent"`
	ReplicaRef        string           `json:"replica_ref,omitempty"`
	EffectiveHash     string           `json:"effective_hash,omitempty"`
}

func (r Record) Version() int   { return r.SchemaVersion }
func (r Record) Terminal() bool { return r.Step == msg.StepReported }

func DirRel(binding string, gen uint64) string {
	return fmt.Sprintf("apply/%s/%d", binding, gen)
}
func PayloadRel(binding string, gen uint64) string  { return DirRel(binding, gen) + "/payload" }
func PreviousRel(binding string, gen uint64) string { return DirRel(binding, gen) + "/previous" }

func (r Record) Rel() string { return fmt.Sprintf("apply/%s/%d.json", r.BindingID, r.RolloutGeneration) }

func key(binding string, gen uint64) string { return binding + "|" + strconv.FormatUint(gen, 10) }

type Journal struct {
	Records   map[string]Record
	Watermark map[string]uint64 // highest durably recorded generation per binding
}

func LoadJournal(s *store.Store) (Journal, error) {
	j := Journal{Records: map[string]Record{}, Watermark: map[string]uint64{}}
	root := s.Path("apply")
	entries, err := os.ReadDir(root)
	if os.IsNotExist(err) {
		return j, nil
	}
	if err != nil {
		return j, err
	}
	for _, binding := range entries {
		if !binding.IsDir() {
			continue
		}
		files, err := os.ReadDir(filepath.Join(root, binding.Name()))
		if err != nil {
			return j, err
		}
		for _, f := range files {
			if f.IsDir() || !strings.HasSuffix(f.Name(), ".json") {
				continue
			}
			rel := "apply/" + binding.Name() + "/" + f.Name()
			r, err := store.LoadJSON[Record](s, rel, schemaVersion)
			if err != nil {
				return j, fmt.Errorf("journal %s: %w", rel, err)
			}
			j.Put(r)
		}
	}
	return j, nil
}

func (j *Journal) Put(r Record) {
	j.Records[key(r.BindingID, r.RolloutGeneration)] = r
	if r.RolloutGeneration > j.Watermark[r.BindingID] {
		j.Watermark[r.BindingID] = r.RolloutGeneration
	}
}

func (j Journal) Get(binding string, gen uint64) (Record, bool) {
	r, ok := j.Records[key(binding, gen)]
	return r, ok
}

func (j Journal) ByCommand(commandID string) (Record, bool) {
	for _, r := range j.Records {
		if r.CommandID == commandID {
			return r, true
		}
	}
	return Record{}, false
}

func (j Journal) OpenCount() int {
	n := 0
	for _, r := range j.Records {
		if !r.Terminal() {
			n++
		}
	}
	return n
}

// SupersededPartials lists the per-generation directories below belowGen for
// a binding; their payload/previous/temp files are a disk leak and a future
// resume's ambiguous input (AG-D4 §5.2).
func (j Journal) SupersededPartials(binding string, belowGen uint64) []string {
	var out []string
	for _, r := range j.Records {
		if r.BindingID == binding && r.RolloutGeneration < belowGen {
			out = append(out, DirRel(r.BindingID, r.RolloutGeneration))
		}
	}
	return out
}
