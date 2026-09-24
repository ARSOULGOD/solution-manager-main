package telemetry

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/persist"
)

// Store persists telemetry samples to the local filesystem.
type Store struct{}

func (Store) SaveSuccess(dataDir string, s Sample) error {
	if s.ServiceID == "" {
		return fmt.Errorf("service_id is required")
	}

	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}

	base := filepath.Join(dataDir, "telemetry", s.ServiceID)
	historyDir := filepath.Join(base, "history")
	if err := os.MkdirAll(historyDir, 0700); err != nil {
		return err
	}

	ts := historyTimestamp(s.ScrapedAt)
	historyPath := filepath.Join(historyDir, ts+".json")
	if err := persist.WriteFileAtomic(historyPath, data, 0600); err != nil {
		return err
	}

	latestPath := filepath.Join(base, "latest.json")
	return persist.WriteFileAtomic(latestPath, data, 0600)
}

func (Store) SaveFailure(dataDir string, s Sample) error {
	if s.ServiceID == "" {
		return fmt.Errorf("service_id is required")
	}

	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}

	failDir := filepath.Join(dataDir, "telemetry", "_failures")
	if err := os.MkdirAll(failDir, 0700); err != nil {
		return err
	}

	ts := historyTimestamp(s.ScrapedAt)
	failPath := filepath.Join(failDir, fmt.Sprintf("%s-%s.json", s.ServiceID, ts))
	return persist.WriteFileAtomic(failPath, data, 0600)
}

func historyTimestamp(scrapedAt string) string {
	if scrapedAt != "" {
		if t, err := time.Parse(time.RFC3339, scrapedAt); err == nil {
			return t.UTC().Format("20060102T150405Z")
		}
	}
	return time.Now().UTC().Format("20060102T150405Z")
}
