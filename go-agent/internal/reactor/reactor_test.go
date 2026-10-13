package reactor

import (
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

func TestSortKeepsPersistFirstAndIsStable(t *testing.T) {
	effs := []Effect{
		Send{Msg: msg.Heartbeat{}},
		ArmTimer{Kind: HeartbeatDue, After: time.Second},
		Persist{Rec: store.Blob{RelPath: "b"}},
		Send{Msg: msg.Teardown{}},
		Persist{Rec: store.Blob{RelPath: "a"}},
		Close{Reason: "x"},
	}
	got := Sort(effs)
	want := []int{0, 0, 2, 2, 4, 6}
	for i, e := range got {
		if e.effectOrder() != want[i] {
			t.Fatalf("pos %d: order %d want %d", i, e.effectOrder(), want[i])
		}
	}
	if got[0].(Persist).Rec.Rel() != "b" || got[1].(Persist).Rec.Rel() != "a" {
		t.Fatal("sort not stable")
	}
}

func TestDPNow(t *testing.T) {
	e := Env{Now: time.Unix(100, 0), Offset: 5 * time.Second}
	if e.DPNow() != time.Unix(105, 0) {
		t.Fatal("DPNow must add the offset")
	}
}
