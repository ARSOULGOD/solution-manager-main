package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/solace/solution-manager/go-agent/internal/agent"
)

var version = "dev" // -ldflags "-X main.version=..."

func main() {
	dataDir := flag.String("data-dir", envOr("SM_AGENT_DATA_DIR", "/var/lib/sm-agent"), "state directory")
	endpoint := flag.String("connect-endpoint", "", "bootstrap DP endpoint host:port (required until enrolled)")
	token := flag.String("enrollment-token", os.Getenv("SM_AGENT_ENROLL_TOKEN"), "single-use enrollment token (env SM_AGENT_ENROLL_TOKEN)")
	trust := flag.String("trust-bundle", "", "PEM file with the DP server trust bundle or pin")
	level := flag.String("log-level", "info", "debug|info|warn|error")
	adminAddr := flag.String("admin-addr", "127.0.0.1:9464", "loopback /healthz and /metrics listener; empty disables")
	flag.Parse()

	var lvl slog.Level
	if err := lvl.UnmarshalText([]byte(*level)); err != nil {
		fmt.Fprintln(os.Stderr, "bad --log-level:", err)
		os.Exit(2)
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
	l, _, err := agent.Build(agent.BuildConfig{
		DataDir: *dataDir, Endpoint: *endpoint, Token: *token, TrustBundlePEM: bundle, AgentVersion: version, AdminAddr: *adminAddr, Log: log,
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
