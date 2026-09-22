package persist

import (
	"os"
	"path/filepath"
)

// EnsureLayout creates the necessary subdirectories for the agent's durable state
func EnsureLayout(root string) error {
	dirs := []string{
		"identity",
		"session",
		filepath.Join("apply", "staging"),
		filepath.Join("telemetry", "buffer"),
	}

	for _, dir := range dirs {
		if err := os.MkdirAll(filepath.Join(root, dir), 0700); err != nil {
			return err
		}
	}
	return nil
}
