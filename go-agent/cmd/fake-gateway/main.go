package main

import (
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/solace/solution-manager/go-agent/internal/fakegw"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:8443", "listen address")
	scenarioPath := flag.String("scenario", "", "scenario YAML (default: built-in accept-only)")
	tokens := flag.String("enroll-token", "demo-token", "comma-separated single-use enrollment tokens")
	caOut := flag.String("ca-out", "", "write the fake CA PEM here (the agent's --trust-bundle)")
	flag.Parse()

	log := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))
	sc := fakegw.DefaultScenario()
	if *scenarioPath != "" {
		var err error
		if sc, err = fakegw.LoadScenario(*scenarioPath); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(2)
		}
	}
	s, err := fakegw.Start(fakegw.Config{Listen: *listen, Tokens: strings.Split(*tokens, ","), Scenario: sc, Log: log})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if *caOut != "" {
		if err := os.WriteFile(*caOut, s.CAPEM(), 0o644); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	}
	log.Info("fake gateway listening", "endpoint", s.Endpoint(), "ca", *caOut)
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	s.Stop()
	for _, e := range s.Transcript() {
		fmt.Printf("%s %-3s %s\n", e.At.Format("15:04:05.000"), e.Dir, e.Kind)
	}
}
