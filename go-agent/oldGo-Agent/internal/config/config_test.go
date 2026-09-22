package config_test

import (
	"testing"

	"github.com/solace/solution-manager/go-agent/internal/config"
)

func TestLoadDefaults(t *testing.T) {
	cfg, err := config.Load([]string{"sm-agent"})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.DataDir != "/var/lib/sm-agent" {
		t.Fatalf("got %q", cfg.DataDir)
	}
	if cfg.ProtocolVersion != 1 {
		t.Fatalf("got %d", cfg.ProtocolVersion)
	}
	if len(cfg.Roles) != 2 || cfg.Roles[0] != "control" || cfg.Roles[1] != "telemetry" {
		t.Fatalf("got roles %v", cfg.Roles)
	}
}
