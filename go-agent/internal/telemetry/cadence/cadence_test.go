package cadence

import (
	"reflect"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func TestResolve(t *testing.T) {
	const age = time.Hour
	for _, tc := range []struct {
		name       string
		pull, push time.Duration
		want       Settings
		ignored    []Ignored
	}{
		{"absent", 0, 0, Settings{}, nil},
		{"valid", 5 * time.Second, time.Minute, Settings{Pull: 5 * time.Second, Push: time.Minute}, nil},
		// Review Focus 4: the boundaries
		{"pull at the floor", time.Second, 0, Settings{Pull: time.Second}, nil},
		{"pull below the floor", 999 * time.Millisecond, 0, Settings{}, []Ignored{{Limit: "pull", Value: 999 * time.Millisecond, Reason: "below 1s"}}},
		{"negative pull", -time.Second, 0, Settings{}, []Ignored{{Limit: "pull", Value: -time.Second, Reason: "below 1s"}}},
		{"smallest push", 0, time.Nanosecond, Settings{Push: time.Nanosecond}, nil},
		{"push just below the age cap", 0, age - time.Nanosecond, Settings{Push: age - time.Nanosecond}, nil},
		{"push equal to the age cap", 0, age, Settings{}, []Ignored{{Limit: "push", Value: age, Reason: "not below the buffer age cap"}}},
		{"push beyond the age cap", 0, 2 * age, Settings{}, []Ignored{{Limit: "push", Value: 2 * age, Reason: "not below the buffer age cap"}}},
		{"negative push", 0, -time.Second, Settings{}, []Ignored{{Limit: "push", Value: -time.Second, Reason: "negative"}}},
		{"both refused", 10 * time.Millisecond, age, Settings{}, []Ignored{
			{Limit: "pull", Value: 10 * time.Millisecond, Reason: "below 1s"},
			{Limit: "push", Value: age, Reason: "not below the buffer age cap"}}},
	} {
		got, ig := Resolve(msg.Limits{TelemetryPullInterval: tc.pull, TelemetryPushInterval: tc.push}, age)
		if got != tc.want || !reflect.DeepEqual(ig, tc.ignored) {
			t.Errorf("%s: got %+v %+v, want %+v %+v", tc.name, got, ig, tc.want, tc.ignored)
		}
	}
}

// Deviation 2: no age cap, so no upper bound on push.
func TestWithNoAgeCapAnyPositivePushIsValid(t *testing.T) {
	if got, ig := Resolve(msg.Limits{TelemetryPushInterval: 48 * time.Hour}, 0); got.Push != 48*time.Hour || ig != nil {
		t.Fatalf("got %+v %+v", got, ig)
	}
}

func TestHolderSignalsAChange(t *testing.T) {
	h := NewHolder()
	ch := h.Changed()
	if !h.Set(Settings{Push: time.Minute}) {
		t.Fatal("Set reported no change")
	}
	select {
	case <-ch:
	default:
		t.Fatal("Changed was not closed by Set")
	}
	if h.Current() != (Settings{Push: time.Minute}) {
		t.Fatalf("current %+v", h.Current())
	}
}

// Review Focus 1: a reconnect that repeats the values must signal nothing, or
// every ticker resets and a session flapping faster than the push interval
// postpones every push.
func TestSettingTheSameValuesDoesNotSignalAChange(t *testing.T) {
	h := NewHolder()
	h.Set(Settings{Pull: 5 * time.Second})
	ch := h.Changed()
	if h.Set(Settings{Pull: 5 * time.Second}) {
		t.Fatal("Set reported a change for equal values")
	}
	select {
	case <-ch:
		t.Fatal("Changed was closed for equal values")
	default:
	}
}
