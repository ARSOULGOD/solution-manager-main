package main

import (
	"context"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/config"
	"github.com/solace/solution-manager/go-agent/internal/docker"
	"github.com/solace/solution-manager/go-agent/internal/telemetry"
)

const defaultDataDir = "/var/lib/sm-agent"

func main() {
	if len(os.Args) > 1 && docker.IsLifecycleCommand(os.Args[1]) {
		if err := runLifecycle(os.Args[1:]); err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
		return
	}

	if len(os.Args) > 1 && os.Args[1] == "collect" {
		if err := runCollect(os.Args[2:]); err != nil {
			fmt.Fprintf(os.Stderr, "Error: %v\n", err)
			os.Exit(1)
		}
		return
	}

	cfg, err := config.Load(os.Args)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error loading config: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Starting sm-agent with config: %+v\n", cfg)

	// Keep the agent running until it receives a termination signal.
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)

	sig := <-sigChan
	fmt.Printf("Received signal %v, shutting down sm-agent\n", sig)
}

func runCollect(args []string) error {
	fs := flag.NewFlagSet("collect", flag.ContinueOnError)
	fs.SetOutput(os.Stderr)

	dataDir := defaultDataDir
	if envDir := os.Getenv("SM_AGENT_DATA_DIR"); envDir != "" {
		dataDir = envDir
	}

	var targetsPath string
	fs.StringVar(&targetsPath, "targets", "", "Path to targets YAML file (required)")
	fs.StringVar(&dataDir, "data-dir", dataDir, "Agent data directory")

	if err := fs.Parse(args); err != nil {
		return err
	}

	if envDir := os.Getenv("SM_AGENT_DATA_DIR"); envDir != "" {
		dataDir = envDir
	}

	if targetsPath == "" {
		return fmt.Errorf("--targets is required")
	}

	targets, err := telemetry.LoadTargets(targetsPath)
	if err != nil {
		return err
	}

	client := &http.Client{Timeout: 5 * time.Second}
	return telemetry.Run(context.Background(), dataDir, targets, client, 0)
}

func runLifecycle(args []string) error {
	cli, err := docker.NewFromEnv()
	if err != nil {
		return err
	}
	defer cli.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	return docker.RunCLI(ctx, cli, args, os.Stdout)
}
