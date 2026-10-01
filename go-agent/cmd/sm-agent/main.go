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
	token := flag.String("enrollment-token", os.Getenv("SM_AGENT_ENROLL_TOKEN"), "single-use enrollment token (env SM_AGENT_ENROLL_TOKEN)")
	trust := flag.String("trust-bundle", "", "PEM file with the DP server trust bundle or pin")
	level := flag.String("log-level", "info", "debug|info|warn|error")
	adminAddr := flag.String("admin-addr", "127.0.0.1:9464", "/healthz and /metrics listener; loopback by default — a non-loopback address exposes both unauthenticated, at the operator's choice; empty disables")
	roles := flag.String("roles", envOr("SM_AGENT_ROLES", "control"), "comma list: control, telemetry")
	telemetryCfg := flag.String("telemetry-config", "", "metric declarations file; required with the telemetry role (AG-D22)")
	bufBytes := flag.String("telemetry-buffer-bytes", "64MiB", "AG-D9 byte cap across all streams (provisional default)")
	bufAge := flag.Duration("telemetry-buffer-age", 24*time.Hour, "AG-D9 time horizon (provisional default)")
	flag.Parse()

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
		if *telemetryCfg == "" {
			fmt.Fprintln(os.Stderr, "--telemetry-config is required with the telemetry role")
			os.Exit(2)
		}
		decl, err := declare.Load(*telemetryCfg)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(2)
		}
		n, err := parseBytes(*bufBytes)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(2)
		}
		tc = &agent.TelemetryConfig{Declarations: decl, BufferBytes: n, BufferAge: *bufAge}
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
		DataDir: *dataDir, Endpoint: *endpoint, Token: *token, TrustBundlePEM: bundle, AgentVersion: version, AdminAddr: *adminAddr, Adapter: adapter, Log: log, Roles: r, Telemetry: tc,
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
