package config

import (
	"flag"
	"os"
	"strings"
)

type Config struct {
	DataDir              string   // default /var/lib/sm-agent; env SM_AGENT_DATA_DIR
	DPEnrollURL          string   // https://dp.example:443 — unary enroll endpoint host
	DPConnectAddr        string   // host:443 for bidi Connect
	DPTelemetryAddr      string   // separate report/telemetry endpoint
	DPServerCAFile       string   // optional pin; empty = system roots
	EnrollmentToken      string   // single-use; prefer env SM_AGENT_ENROLL_TOKEN
	Roles                []string // "control","telemetry"; default both
	AgentSoftwareVersion string
	ProtocolVersion      uint32   // must be 1
	LogLevel             string   // slog level
}

func Load(args []string) (Config, error) {
	fs := flag.NewFlagSet("sm-agent", flag.ContinueOnError)
	
	var cfg Config
	
	fs.StringVar(&cfg.DataDir, "data-dir", "/var/lib/sm-agent", "Data directory")
	fs.StringVar(&cfg.DPEnrollURL, "dp-enroll-url", "", "DP enroll URL")
	fs.StringVar(&cfg.DPConnectAddr, "dp-connect-addr", "", "DP connect address")
	fs.StringVar(&cfg.DPTelemetryAddr, "dp-telemetry-addr", "", "DP telemetry address")
	fs.StringVar(&cfg.DPServerCAFile, "dp-server-ca-file", "", "DP server CA file")
	fs.StringVar(&cfg.EnrollmentToken, "enrollment-token", "", "Enrollment token")
	
	rolesStr := fs.String("roles", "control,telemetry", "Comma-separated roles")
	fs.StringVar(&cfg.AgentSoftwareVersion, "agent-software-version", "", "Agent software version")
	
	// Protocol version must be 1
	cfg.ProtocolVersion = 1
	
	fs.StringVar(&cfg.LogLevel, "log-level", "info", "Log level")
	
	var parseArgs []string
	if len(args) > 1 {
		parseArgs = args[1:]
	}
	err := fs.Parse(parseArgs)
	if err != nil {
		return cfg, err
	}
	
	if envDir := os.Getenv("SM_AGENT_DATA_DIR"); envDir != "" {
		cfg.DataDir = envDir
	}
	
	if envToken := os.Getenv("SM_AGENT_ENROLL_TOKEN"); envToken != "" {
		cfg.EnrollmentToken = envToken
	}
	
	if *rolesStr != "" {
		cfg.Roles = strings.Split(*rolesStr, ",")
	}
	
	return cfg, nil
}
