package telemetry

import (
	"fmt"
	"os"

	"gopkg.in/yaml.v3"
)

type targetsFile struct {
	Targets []targetEntry `yaml:"targets"`
}

type targetEntry struct {
	ID  string `yaml:"id"`
	URL string `yaml:"url"`
}

// LoadTargets reads a YAML targets file and returns validated targets.
func LoadTargets(path string) ([]Target, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	var file targetsFile
	if err := yaml.Unmarshal(data, &file); err != nil {
		return nil, fmt.Errorf("parse targets: %w", err)
	}

	targets := make([]Target, 0, len(file.Targets))
	for i, entry := range file.Targets {
		if entry.ID == "" {
			return nil, fmt.Errorf("target %d: id is required", i)
		}
		if entry.URL == "" {
			return nil, fmt.Errorf("target %d (%s): url is required", i, entry.ID)
		}
		targets = append(targets, Target{
			ID:  entry.ID,
			URL: entry.URL,
		})
	}

	return targets, nil
}
