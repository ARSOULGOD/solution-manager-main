package persist

import (
	"encoding/json"
	"os"
	"path/filepath"
	"time"
)

type Cursor struct {
	LastAppliedGeneration     uint64    `json:"last_applied_generation"`
	LastAppliedIdempotencyKey string    `json:"last_applied_idempotency_key"`
	LastAppliedConfigSHA256   string    `json:"last_applied_config_sha256"`
	InFlight                  *InFlight `json:"in_flight"`
	SupersededCleaned         []uint64  `json:"superseded_cleaned"`
}

type InFlight struct {
	Generation       uint64    `json:"generation"`
	IdempotencyKey   string    `json:"idempotency_key"`
	BindingID        string    `json:"binding_id"`
	Phase            string    `json:"phase"` // PREFLIGHT|STAGING|RENAMED|RESTARTING|INDETERMINATE
	StepDeadline     time.Time `json:"step_deadline"`
	StagingPath      string    `json:"staging_path"`
	TargetConfigPath string    `json:"target_config_path"`
}

func SaveCursor(root string, c Cursor) error {
	path := filepath.Join(root, "apply", "cursor.json")
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return WriteFileAtomic(path, data, 0600)
}

func LoadCursor(root string) (Cursor, error) {
	path := filepath.Join(root, "apply", "cursor.json")
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return Cursor{}, ErrNotFound
		}
		return Cursor{}, err
	}
	var c Cursor
	if err := json.Unmarshal(data, &c); err != nil {
		return Cursor{}, err
	}
	return c, nil
}
