package cadence

import (
	"reflect"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
)

func d(v time.Duration) *time.Duration { return &v }

func TestResolve(t *testing.T) {
	const age = time.Hour
	refused := func(v time.Duration, reason string) *Ignored {
		return &Ignored{Value: v, Reason: reason, Refused: true}
	}
	noRoom := &Ignored{Reason: "default not below the buffer age cap"}
	for _, tc := range []struct {
		name string
		push *time.Duration
		age  time.Duration
		want time.Duration
		ig   *Ignored
	}{
		{"unset: OR-73's default", nil, age, time.Minute, nil},
		{"zero: at once", d(0), age, 0, nil},
		{"valid", d(30 * time.Second), age, 30 * time.Second, nil},
		{"at the floor", d(time.Second), age, time.Second, nil},
		{"just below the age cap", d(age - time.Nanosecond), age, age - time.Nanosecond, nil},
		// Review Focus 1: a gateway that mis-encodes 60 s as 60 ns
		{"nanoseconds", d(60 * time.Nanosecond), age, time.Minute, refused(60*time.Nanosecond, "below 1s")},
		{"just below the floor", d(999 * time.Millisecond), age, time.Minute, refused(999*time.Millisecond, "below 1s")},
		{"negative", d(-time.Second), age, time.Minute, refused(-time.Second, "negative")},
		{"equal to the age cap", d(age), age, time.Minute, refused(age, "not below the buffer age cap")},
		{"beyond the age cap", d(2 * age), age, time.Minute, refused(2*age, "not below the buffer age cap")},
		// Review Focus 2: an age cap of 1 min or less leaves no room for the default
		{"unset, age cap 1 min", nil, time.Minute, 0, noRoom},
		{"unset, age cap 30 s", nil, 30 * time.Second, 0, noRoom},
		{"valid, age cap 30 s", d(10 * time.Second), 30 * time.Second, 10 * time.Second, nil},
		{"refused, age cap 30 s", d(time.Hour), 30 * time.Second, 0, refused(time.Hour, "not below the buffer age cap")},
		// No age cap (as buffer.Options reads 0): no upper bound
		{"unset, no age cap", nil, 0, time.Minute, nil},
		{"two days, no age cap", d(48 * time.Hour), 0, 48 * time.Hour, nil},
	} {
		got, ig := Resolve(msg.Limits{ReportInterval: time.Minute, TelemetryPushInterval: tc.push}, tc.age)
		if got != tc.want || !reflect.DeepEqual(ig, tc.ig) {
			t.Errorf("%s: got %v %+v, want %v %+v", tc.name, got, ig, tc.want, tc.ig)
		}
	}
}
