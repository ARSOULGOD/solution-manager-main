package persist

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"time"
)

var ErrNotFound = errors.New("not found")

type Authority struct {
	TargetID               string            `json:"target_id"`
	BindingIDs             []string          `json:"binding_ids"`
	AgentRevocationEpoch   uint64            `json:"agent_revocation_epoch"`
	FleetRevocationEpoch   uint64            `json:"fleet_revocation_epoch"`
	IssuedAt               time.Time         `json:"issued_at"`
	ExpiresAt              time.Time         `json:"expires_at"`
	Audience               string            `json:"audience"`
	Nonce                  string            `json:"nonce"`
	Capabilities           []string          `json:"capabilities"`
	DPWallTime             time.Time         `json:"dp_wall_time"`
	ClockOffsetMs          int64             `json:"clock_offset_ms"`
	VerbCeilings           map[string]string `json:"verb_ceilings"` // verb -> duration string e.g. "2m"
	AdmissionMargin        string            `json:"admission_margin"`  // e.g. "30s"
	AuthorityState         string            `json:"authority_state"`
	ReportBearerPath       string            `json:"report_bearer_path"`
}

func SaveAuthority(root string, a Authority) error {
	path := filepath.Join(root, "session", "authority.json")
	data, err := json.MarshalIndent(a, "", "  ")
	if err != nil {
		return err
	}
	return WriteFileAtomic(path, data, 0600)
}

func LoadAuthority(root string) (Authority, error) {
	path := filepath.Join(root, "session", "authority.json")
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return Authority{}, ErrNotFound
		}
		return Authority{}, err
	}
	var a Authority
	if err := json.Unmarshal(data, &a); err != nil {
		return Authority{}, err
	}
	return a, nil
}
