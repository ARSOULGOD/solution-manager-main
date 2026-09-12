package persist_test

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/persist"
)

func TestAuthority_RoundTrip(t *testing.T) {
	root := t.TempDir()
	persist.EnsureLayout(root)

	a := persist.Authority{
		TargetID:             "t1",
		BindingIDs:           []string{"b1"},
		AgentRevocationEpoch: 1,
		FleetRevocationEpoch: 2,
		IssuedAt:             time.Now().UTC().Truncate(time.Second),
		ExpiresAt:            time.Now().Add(time.Hour).UTC().Truncate(time.Second),
		Audience:             "dp1",
		Nonce:                "n1",
		Capabilities:         []string{"cap1"},
		DPWallTime:           time.Now().UTC().Truncate(time.Second),
		ClockOffsetMs:        100,
		VerbCeilings:         map[string]string{"restart": "2m"},
		AdmissionMargin:      "30s",
		AuthorityState:       "ACTIVE",
		ReportBearerPath:     "identity/report.bearer",
	}

	err := persist.SaveAuthority(root, a)
	if err != nil {
		t.Fatal(err)
	}

	loaded, err := persist.LoadAuthority(root)
	if err != nil {
		t.Fatal(err)
	}

	if loaded.TargetID != a.TargetID {
		t.Fatalf("expected %q, got %q", a.TargetID, loaded.TargetID)
	}
	if loaded.IssuedAt != a.IssuedAt {
		t.Fatalf("expected %v, got %v", a.IssuedAt, loaded.IssuedAt)
	}
}

func TestAuthority_NotFound(t *testing.T) {
	root := t.TempDir()
	_, err := persist.LoadAuthority(root)
	if err != persist.ErrNotFound {
		t.Fatalf("expected ErrNotFound, got %v", err)
	}
}

func TestAuthority_CorruptJSON(t *testing.T) {
	root := t.TempDir()
	persist.EnsureLayout(root)
	path := filepath.Join(root, "session", "authority.json")
	persist.WriteFileAtomic(path, []byte("{corrupt"), 0600)
	
	_, err := persist.LoadAuthority(root)
	if err == nil || err == persist.ErrNotFound {
		t.Fatalf("expected JSON error, got %v", err)
	}
}
