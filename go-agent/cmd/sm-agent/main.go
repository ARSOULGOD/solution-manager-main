package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/agent"
	"github.com/solace/solution-manager/go-agent/internal/runtime/docker"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/declare"
)

var version = "dev" // -ldflags "-X main.version=..."

func main() {
	dataDir := flag.String("data-dir", envOr("SM_AGENT_DATA_DIR", "/var/lib/sm-agent"), "state directory")
	endpoint := flag.String("connect-endpoint", "", "bootstrap DP endpoint host:port (required until enrolled)")
	token := flag.String("enrollment-token", "", "single-use enrollment token; prefer env SM_AGENT_ENROLL_TOKEN (argv is visible in ps and docker inspect)")
	trust := flag.String("trust-bundle", "", "PEM file with the DP server trust bundle or pin")
	level := flag.String("log-level", "info", "debug|info|warn|error")
	adminAddr := flag.String("admin-addr", "127.0.0.1:9464", "/healthz and /metrics listener; loopback by default — a non-loopback address exposes both unauthenticated, at the operator's choice; empty disables")
	roles := flag.String("roles", envOr("SM_AGENT_ROLES", "control"), "comma list: control, telemetry, logs")
	telemetryCfg := flag.String("telemetry-config", "", "development-only metric declarations file (AG-D22); the DP pushes declarations on the wire (AG-D28)")
	bufBytes := flag.String("telemetry-buffer-bytes", "64MiB", "AG-D9 byte cap across all streams (provisional default)")
	bufAge := flag.Duration("telemetry-buffer-age", 24*time.Hour, "AG-D9 time horizon (provisional default)")
	logsDefault := flag.String("logs-default-max-bytes", envOr("SM_AGENT_LOGS_DEFAULT_MAX_BYTES", "16MiB"), "logs role: a placement's log ring cap until the DP sets one; 0 leaves collection off until then (provisional default)")
	flag.Parse()
	*token = resolveToken(*token, os.Getenv)

	var lvl slog.Level
	if err := lvl.UnmarshalText([]byte(*level)); err != nil {
		fmt.Fprintln(os.Stderr, "bad --log-level:", err)
		os.Exit(2)
	}
	r, err := agent.ParseRoles(*roles)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	var tc *agent.TelemetryConfig
	if r.Telemetry {
		// The DP pushes the declarations on the wire (AG-D28); the file only seeds
		// them, for development.
		var decl *declare.File
		if *telemetryCfg != "" {
			var err error
			if decl, err = declare.Load(*telemetryCfg); err != nil {
				fmt.Fprintln(os.Stderr, err)
				os.Exit(2)
			}
		}
		n, err := parseBytes(*bufBytes)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(2)
		}
		if *bufAge <= 0 {
			fmt.Fprintln(os.Stderr, "--telemetry-buffer-age must be positive with the telemetry role")
			os.Exit(2)
		}
		tc = &agent.TelemetryConfig{Declarations: decl, BufferBytes: n, BufferAge: *bufAge}
	}
	var lc *agent.LogsConfig
	if r.Logs {
		var n int64
		if *logsDefault != "0" {
			var err error
			if n, err = parseBytes(*logsDefault); err != nil {
				fmt.Fprintln(os.Stderr, "--logs-default-max-bytes:", err)
				os.Exit(2)
			}
		}
		lc = &agent.LogsConfig{DefaultMaxBytes: uint64(n)}
	}
	log := slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{Level: lvl}))

	var bundle []byte
	if *trust != "" {
		b, err := os.ReadFile(*trust)
		if err != nil {
			log.Error("trust bundle", "err", err)
			os.Exit(2)
		}
		bundle = b
	}
	adapter, closeDocker, err := docker.NewFromEnv()
	if err != nil {
		log.Error("docker", "err", err)
		os.Exit(1)
	}
	defer closeDocker()

	l, _, err := agent.Build(agent.BuildConfig{
		DataDir: *dataDir, Endpoint: *endpoint, Token: *token, TrustBundlePEM: bundle, AgentVersion: version, AdminAddr: *adminAddr, Adapter: adapter, Log: log, Roles: r, Telemetry: tc, Logs: lc,
	})
	if err != nil {
		log.Error("build", "err", err)
		os.Exit(1)
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	if err := l.Run(ctx); err != nil {
		log.Error("agent stopped", "err", err)
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

// resolveToken returns the enrolment token: the flag if given, else the environment. The environment is read after
// flag.Parse, never as a flag default, because flag prints defaults in usage and error output (a secret leak).
func resolveToken(flagVal string, getenv func(string) string) string {
	if flagVal != "" {
		return flagVal
	}
	return getenv("SM_AGENT_ENROLL_TOKEN")
}
