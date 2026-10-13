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
