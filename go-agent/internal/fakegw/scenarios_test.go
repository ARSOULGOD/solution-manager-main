package fakegw

import (
	"path/filepath"
	"testing"
)

// One scenario per plan 015 T2.3 addition parses and carries what it is about.
func TestT23ScenariosLoad(t *testing.T) {
	remat, err := LoadScenario(filepath.Join("scenarios", "rematerialize.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if o := remat.Steps[1].Offer; o == nil || o.Operation != "rematerialize" || o.ExpectedCurrentSHA256 == nil || remat.Lease.VerbCeilings["rematerialize"] == 0 {
		t.Fatalf("%+v", remat.Steps[1])
	}
	if o := remat.Steps[3].Offer; o == nil || o.ExpectedCurrentSHA256 == nil || *o.ExpectedCurrentSHA256 != "" {
		t.Fatal("present and empty means the file must be absent")
	}
	decl, err := LoadScenario(filepath.Join("scenarios", "declarations.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	d := decl.Steps[0].Declarations
	if d == nil || len(d.Metrics) != 1 || d.HealthProbe == nil || d.HealthProbe.HostPort != 18080 {
		t.Fatalf("%+v", decl.Steps[0])
	}
	disc, err := LoadScenario(filepath.Join("scenarios", "discovery.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if disc.Policy.Discovery.Mode != "register" || disc.Policy.Discovery.Bindings["java1"] != "binding-1" {
		t.Fatalf("%+v", disc.Policy.Discovery)
	}
}

// The plan 016 logs scenario parses into the log steps and policy.
func TestLogsScenarioLoads(t *testing.T) {
	sc, err := LoadScenario(filepath.Join("scenarios", "logs.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if sc.Policy.Logs.CreditsPerChunk != 1 || len(sc.Steps) != 6 {
		t.Fatalf("%+v", sc.Policy.Logs)
	}
	if r := sc.Steps[0].LogRetention; r == nil || r.Placement != "binding-1" || r.MaxBytes != 512000 {
		t.Fatalf("%+v", sc.Steps[0])
	}
	if r := sc.Steps[2].LogRequest; r == nil || r.Page == nil || r.Page.Limit != 50 || r.RequestID != "demo-page" {
		t.Fatalf("%+v", sc.Steps[2])
	}
	if r := sc.Steps[4].LogRequest; r == nil || r.Export == nil || r.Page != nil {
		t.Fatalf("%+v", sc.Steps[4])
	}
	if e := sc.Steps[3].ExpectLogPage; e == nil || e.RequestID != "demo-page" {
		t.Fatalf("%+v", sc.Steps[3])
	}
	if e := sc.Steps[5].ExpectLogChunks; e == nil || !e.Final {
		t.Fatalf("%+v", sc.Steps[5])
	}
}
