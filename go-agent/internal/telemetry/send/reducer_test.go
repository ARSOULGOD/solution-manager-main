package send

import (
	"reflect"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/telemetry/buffer"
)

var t0 = time.Date(2026, 10, 1, 7, 0, 0, 0, time.UTC)

func noJitter(d time.Duration) time.Duration { return d }

func env(now time.Time, heads ...Head) Env { return Env{Now: now, Heads: heads, Jitter: noJitter} }

func head(stream string, seq uint64, producedSec int) Head {
	return Head{Stream: stream, Seq: seq, ProducedAt: t0.Add(time.Duration(producedSec) * time.Second), Bytes: 300, IDsKnown: true}
}

// durp is a *time.Duration literal.
func durp(d time.Duration) *time.Duration { return &d }

// limits pushes at once (an explicit 0, fake-gateway's default), as every test
// written before AG-D30 assumed.
var limits = msg.Limits{InitialCredits: 2, MaxBatchBytes: 65536, ReportInterval: time.Minute, TelemetryPushInterval: durp(0)}

// up returns a state with an open stream and the given credits.
func up(credits uint32) State {
	s, _ := Reduce(NewState(), SessionUp{ID: "s1", Limits: limits}, env(t0))
	s, _ = Reduce(s, StreamOpened{}, env(t0))
	s.Credits = credits
	return s
}

func sends(effs []Effect) []Send {
	var out []Send
	for _, e := range effs {
		if s, ok := e.(Send); ok {
			out = append(out, s)
		}
	}
	return out
}

func TestSessionUpOpensTheStreamAndNothingIsSentUntilItOpens(t *testing.T) {
	s, effs := Reduce(NewState(), SessionUp{ID: "s1", Limits: limits}, env(t0, head("a", 1, 0)))
	if !reflect.DeepEqual(effs, []Effect{OpenStream{}}) || s.Credits != 2 || s.Up {
		t.Fatalf("state %+v effects %+v", s, effs)
	}
	_, effs = Reduce(s, StreamOpened{}, env(t0, head("a", 1, 0)))
	if !reflect.DeepEqual(sends(effs), []Send{{Stream: "a", Seq: 1}}) {
		t.Fatalf("effects %+v", effs)
	}
}

func TestCreditsAreSpentOnTheOldestHeadsFirst(t *testing.T) {
	s, effs := Reduce(up(2), Wake{}, env(t0, head("a", 1, 1), head("b", 1, 2), head("c", 1, 3)))
	if !reflect.DeepEqual(sends(effs), []Send{{"a", 1}, {"b", 1}}) || s.Credits != 0 {
		t.Fatalf("credits %d effects %+v", s.Credits, effs)
	}
}

func TestOneBatchInFlightPerStream(t *testing.T) {
	s := up(4)
	s.InFlight["a"] = 1
	if _, effs := Reduce(s, Wake{}, env(t0, head("a", 2, 1))); len(sends(effs)) != 0 {
		t.Fatalf("sent a second batch on a busy stream: %+v", effs)
	}
}

func TestAnAckTrimsFreesTheStreamAndSkipsTheStaleHead(t *testing.T) {
	s := up(0)
	s.InFlight["a"] = 1
	// heads were read before the trim, so a/1 is still listed
	s, effs := Reduce(s, Ack{Stream: "a", HWM: 1, Credits: 1}, env(t0, head("a", 1, 1), head("b", 1, 2)))
	want := []Effect{Trim{Stream: "a", HWM: 1}, Send{Stream: "b", Seq: 1}}
	if !reflect.DeepEqual(effs, want) || len(s.InFlight) != 1 {
		t.Fatalf("effects %+v inflight %v", effs, s.InFlight)
	}
}

func TestAWatermarkAckBelowTheInFlightBatchKeepsItInFlight(t *testing.T) {
	s := up(0)
	s.InFlight["a"] = 5
	s, _ = Reduce(s, Ack{Stream: "a", HWM: 4}, env(t0))
	if s.InFlight["a"] != 5 {
		t.Fatalf("inflight %v", s.InFlight)
	}
}

func TestATerminalRejectDropsTheBatchAsAGapAndRestoresTheCredit(t *testing.T) {
	s := up(0)
	s.InFlight["a"] = 3
	s, effs := Reduce(s, Reject{Stream: "a", Code: msg.ErrUnknownReplica}, env(t0))
	if !reflect.DeepEqual(effs, []Effect{DropAsGap{Stream: "a", Seq: 3, Reason: "UNKNOWN_REPLICA"}}) || s.Credits != 1 {
		t.Fatalf("effects %+v credits %d", effs, s.Credits)
	}
}

func TestARetryableRejectWaitsOutRetryAfter(t *testing.T) {
	s := up(0)
	s.InFlight["a"] = 3
	s, effs := Reduce(s, Reject{Stream: "a", Code: msg.ErrInternalUnavailable, RetryAfter: 5 * time.Second}, env(t0))
	if !reflect.DeepEqual(effs, []Effect{Requeue{Stream: "a"}}) || s.Credits != 1 {
		t.Fatalf("effects %+v credits %d", effs, s.Credits)
	}
	_, effs = Reduce(s, Wake{}, env(t0.Add(time.Second), head("a", 3, 0)))
	if len(sends(effs)) != 0 || !reflect.DeepEqual(effs, []Effect{ArmRetry{At: t0.Add(5 * time.Second)}}) {
		t.Fatalf("before retry_after: %+v", effs)
	}
	_, effs = Reduce(s, Wake{}, env(t0.Add(6*time.Second), head("a", 3, 0)))
	if !reflect.DeepEqual(sends(effs), []Send{{"a", 3}}) {
		t.Fatalf("after retry_after: %+v", effs)
	}
}

func TestOutOfCreditsZeroesTheCount(t *testing.T) {
	s := up(3)
	s.InFlight["a"] = 1
	s, effs := Reduce(s, Reject{Stream: "a", Code: msg.ErrOutOfCredits}, env(t0, head("b", 1, 0)))
	if s.Credits != 0 || !reflect.DeepEqual(effs, []Effect{Requeue{Stream: "a"}}) {
		t.Fatalf("credits %d effects %+v", s.Credits, effs)
	}
}

func TestAnOversizeHeadIsDroppedWithoutSpendingACredit(t *testing.T) {
	big := head("a", 1, 0)
	big.Bytes = 70000
	s, effs := Reduce(up(1), Wake{}, env(t0, big))
	if !reflect.DeepEqual(effs, []Effect{DropAsGap{Stream: "a", Seq: 1, Reason: buffer.ReasonOversize}}) || s.Credits != 1 {
		t.Fatalf("effects %+v credits %d", effs, s.Credits)
	}
}

// Review Focus 1.
func TestUnresolvedIdsAreDroppedAfterTwoReportIntervals(t *testing.T) {
	h := head("a", 1, 0)
	h.IDsKnown = false
	s := up(1) // stream opened at t0
	if _, effs := Reduce(s, Wake{}, env(t0.Add(90*time.Second), h)); len(effs) != 0 {
		t.Fatalf("dropped too early: %+v", effs)
	}
	if _, effs := Reduce(s, Wake{}, env(t0.Add(121*time.Second), h)); !reflect.DeepEqual(effs, []Effect{DropAsGap{Stream: "a", Seq: 1, Reason: buffer.ReasonUnregistered}}) {
		t.Fatalf("effects %+v", effs)
	}
}

func TestAStreamBreakRequeuesRestoresCreditsAndReopensWithBackoff(t *testing.T) {
	s := up(0)
	s.InFlight["a"] = 1
	s.InFlight["b"] = 4
	s, effs := Reduce(s, StreamBroken{}, env(t0))
	want := []Effect{Requeue{Stream: "a"}, Requeue{Stream: "b"}, CloseStream{}, OpenStream{After: time.Second}}
	if !reflect.DeepEqual(effs, want) || s.Credits != 2 || s.Up {
		t.Fatalf("effects %+v state %+v", effs, s)
	}
	_, effs = Reduce(s, StreamBroken{}, env(t0))
	if effs[len(effs)-1] != (OpenStream{After: 2 * time.Second}) {
		t.Fatalf("second break: %+v", effs)
	}
}

func TestSessionDownStopsEverything(t *testing.T) {
	s := up(2)
	s.InFlight["a"] = 1
	s, effs := Reduce(s, SessionDown{}, env(t0, head("b", 1, 0)))
	if !reflect.DeepEqual(effs, []Effect{Requeue{Stream: "a"}, CloseStream{}}) || s.Credits != 0 || s.SessionID != "" {
		t.Fatalf("effects %+v state %+v", effs, s)
	}
	if _, effs := Reduce(s, Wake{}, env(t0, head("b", 1, 0))); len(effs) != 0 {
		t.Fatalf("sent with no session: %+v", effs)
	}
}

// Review Focus 4: six streams, four credits; each ack frees one credit.
func TestMoreStreamsThanCreditsNeverStarves(t *testing.T) {
	s := up(4)
	streams := []string{"a", "b", "c", "d", "e", "f"}
	next := map[string]uint64{"a": 1, "b": 1, "c": 1, "d": 1, "e": 1, "f": 1}
	produced := map[string]int{"a": 1, "b": 2, "c": 3, "d": 4, "e": 5, "f": 6}
	heads := func() []Head {
		var hs []Head
		for _, st := range streams {
			if _, busy := s.InFlight[st]; !busy {
				hs = append(hs, head(st, next[st], produced[st]))
			}
		}
		sortHeads(hs)
		return hs
	}
	sent := map[string]int{}
	var effs []Effect
	s, effs = Reduce(s, Wake{}, env(t0, heads()...))
	for round := 0; round < 12; round++ {
		for _, x := range sends(effs) {
			sent[x.Stream]++
		}
		// ack the oldest in-flight batch; its stream's next batch is newer than every head
		var oldest string
		for st := range s.InFlight {
			if oldest == "" || produced[st] < produced[oldest] {
				oldest = st
			}
		}
		next[oldest]++
		produced[oldest] += 10
		s, effs = Reduce(s, Ack{Stream: oldest, HWM: next[oldest] - 1, Credits: 1}, env(t0, heads()...))
	}
	for _, st := range streams {
		if sent[st] < 2 {
			t.Fatalf("stream %s starved: %v", st, sent)
		}
	}
}

func TestUnsendableGatesTheStreamWithRetryDefault(t *testing.T) {
	s := up(0)
	s.InFlight["a"] = 1
	s, effs := Reduce(s, Unsendable{Stream: "a"}, env(t0, head("a", 1, 0)))
	if !reflect.DeepEqual(effs, []Effect{ArmRetry{At: t0.Add(retryDefault)}}) || s.Credits != 1 {
		t.Fatalf("effects %+v credits %d", effs, s.Credits)
	}
}

func TestReduceDoesNotMutateItsInput(t *testing.T) {
	s := up(1)
	s.InFlight["a"] = 1
	_, _ = Reduce(s, Ack{Stream: "a", HWM: 1, Credits: 1}, env(t0, head("b", 1, 0)))
	if s.InFlight["a"] != 1 || len(s.Acked) != 0 {
		t.Fatalf("input mutated: %+v", s)
	}
}

func TestAnUnresolvedBatchWaitsTwoReportIntervalsFromWhenItWasProduced(t *testing.T) {
	h := head("a", 1, 600) // produced 10 minutes into a long-open stream
	h.IDsKnown = false
	s := up(4)
	if _, effs := Reduce(s, Wake{}, env(t0.Add(601*time.Second), h)); len(effs) != 0 {
		t.Fatalf("dropped on first sight: %+v", effs)
	}
	if _, effs := Reduce(s, Wake{}, env(t0.Add(721*time.Second), h)); !reflect.DeepEqual(effs, []Effect{DropAsGap{Stream: "a", Seq: 1, Reason: buffer.ReasonUnregistered}}) {
		t.Fatalf("effects %+v", effs)
	}
}
func withPush(push time.Duration) msg.Limits {
	l := limits
	l.TelemetryPushInterval = durp(push)
	return l
}

// upWithPush is up() with a push interval from the session's limits, a one-hour
// age cap and the cutoff at t0, as the runner starts it.
func upWithPush(credits uint32, push time.Duration) State {
	s := NewState()
	s.BufferAge, s.DueBy = time.Hour, t0
	s, _ = Reduce(s, SessionUp{ID: "s1", Limits: withPush(push)}, env(t0))
	s, _ = Reduce(s, StreamOpened{}, env(t0))
	s.Credits = credits
	return s
}

func TestANotYetDueHeadWaitsForThePushTick(t *testing.T) {
	s := upWithPush(4, time.Minute)
	s, effs := Reduce(s, Wake{}, env(t0.Add(10*time.Second), head("a", 1, 5)))
	if len(effs) != 0 {
		t.Fatalf("sent before the push tick: %+v", effs)
	}
	_, effs = Reduce(s, PushDue{}, env(t0.Add(60*time.Second), head("a", 1, 5)))
	if !reflect.DeepEqual(effs, []Effect{Send{Stream: "a", Seq: 1}}) {
		t.Fatalf("effects %+v", effs)
	}
}

func TestAPushTickReleasesEveryHeadProducedAtOrBeforeIt(t *testing.T) {
	s := upWithPush(4, time.Minute)
	_, effs := Reduce(s, PushDue{}, env(t0.Add(60*time.Second), head("a", 1, 5), head("b", 1, 10), head("c", 1, 60)))
	if !reflect.DeepEqual(effs, []Effect{Send{"a", 1}, Send{"b", 1}, Send{"c", 1}}) {
		t.Fatalf("effects %+v", effs)
	}
}

func TestTheCutoffAdvancesOverAnUnfinishedPush(t *testing.T) {
	s := upWithPush(1, time.Minute)
	s, effs := Reduce(s, PushDue{}, env(t0.Add(60*time.Second), head("a", 1, 5), head("b", 1, 10)))
	if !reflect.DeepEqual(effs, []Effect{Send{"a", 1}}) {
		t.Fatalf("first tick: %+v", effs)
	}
	// b was due at the tick and stays due; c, produced after it, is not
	s, effs = Reduce(s, Credit{N: 2}, env(t0.Add(70*time.Second), head("b", 1, 10), head("c", 1, 65)))
	if !reflect.DeepEqual(effs, []Effect{Send{"b", 1}}) {
		t.Fatalf("credit: %+v", effs)
	}
	_, effs = Reduce(s, PushDue{}, env(t0.Add(120*time.Second), head("c", 1, 65)))
	if !reflect.DeepEqual(effs, []Effect{Send{"c", 1}}) {
		t.Fatalf("second tick: %+v", effs)
	}
}

func TestDropsDoNotWaitForThePushTick(t *testing.T) {
	big := head("a", 1, 5)
	big.Bytes = 70000
	lost := head("b", 1, 5)
	lost.IDsKnown = false
	s := upWithPush(4, 30*time.Minute)
	_, effs := Reduce(s, Wake{}, env(t0.Add(130*time.Second), big, lost))
	want := []Effect{DropAsGap{Stream: "a", Seq: 1, Reason: buffer.ReasonOversize}, DropAsGap{Stream: "b", Seq: 1, Reason: buffer.ReasonUnregistered}}
	if !reflect.DeepEqual(effs, want) {
		t.Fatalf("effects %+v", effs)
	}
}

func TestSessionUpArmsThePushTickerOnlyWhenTheIntervalChanges(t *testing.T) {
	s := NewState()
	s.BufferAge = time.Hour
	s, effs := Reduce(s, SessionUp{ID: "s1", Limits: withPush(time.Minute)}, env(t0))
	if !reflect.DeepEqual(effs, []Effect{OpenStream{}, ArmPush{Every: time.Minute}}) || s.PushInterval != time.Minute {
		t.Fatalf("first session: %+v", effs)
	}
	s, _ = Reduce(s, SessionDown{}, env(t0))
	if s.PushInterval != time.Minute {
		t.Fatalf("a session drop changed the push interval to %v", s.PushInterval)
	}
	// Review Focus 3: the same value again re-arms nothing
	s, effs = Reduce(s, SessionUp{ID: "s2", Limits: withPush(time.Minute)}, env(t0))
	if !reflect.DeepEqual(effs, []Effect{OpenStream{}}) {
		t.Fatalf("the same interval re-armed the ticker: %+v", effs)
	}
	s, _ = Reduce(s, SessionDown{}, env(t0))
	s, effs = Reduce(s, SessionUp{ID: "s3", Limits: limits}, env(t0))
	if !reflect.DeepEqual(effs, []Effect{OpenStream{}, ArmPush{Every: 0}}) || s.PushInterval != 0 {
		t.Fatalf("push turned off: %+v", effs)
	}
}

// AG-D30: today's Java gateway sends no field; the agent pushes every minute.
func TestAnUnsetPushIntervalIsOneMinute(t *testing.T) {
	s := NewState()
	s.BufferAge = time.Hour
	l := limits
	l.TelemetryPushInterval = nil
	s, effs := Reduce(s, SessionUp{ID: "s1", Limits: l}, env(t0))
	if !reflect.DeepEqual(effs, []Effect{OpenStream{}, ArmPush{Every: time.Minute}}) || s.PushInterval != time.Minute {
		t.Fatalf("effects %+v push %v", effs, s.PushInterval)
	}
}

func TestARefusedPushIntervalFallsBackToTheDefault(t *testing.T) {
	s := NewState()
	s.BufferAge = time.Hour
	s, effs := Reduce(s, SessionUp{ID: "s1", Limits: withPush(time.Hour)}, env(t0))
	if !reflect.DeepEqual(effs, []Effect{OpenStream{}, ArmPush{Every: time.Minute}}) || s.PushInterval != time.Minute {
		t.Fatalf("effects %+v push %v", effs, s.PushInterval)
	}
}

func TestOverdueHeadsGoAtOnceAfterAReconnect(t *testing.T) {
	s := upWithPush(0, time.Minute) // no credits: the tick's batch stays stored
	s, _ = Reduce(s, PushDue{}, env(t0.Add(60*time.Second), head("a", 1, 30)))
	s, _ = Reduce(s, SessionDown{}, env(t0.Add(61*time.Second)))
	s, _ = Reduce(s, SessionUp{ID: "s2", Limits: withPush(time.Minute)}, env(t0.Add(70*time.Second)))
	_, effs := Reduce(s, StreamOpened{}, env(t0.Add(71*time.Second), head("a", 1, 30), head("b", 1, 65)))
	if !reflect.DeepEqual(effs, []Effect{Send{Stream: "a", Seq: 1}}) {
		t.Fatalf("effects %+v", effs)
	}
}

// Batches stored while push was immediate were already due.
func TestSwitchingFromImmediateKeepsWhatIsStoredDue(t *testing.T) {
	s := NewState()
	s.BufferAge, s.DueBy = time.Hour, t0
	s, _ = Reduce(s, SessionUp{ID: "s1", Limits: limits}, env(t0))
	s, _ = Reduce(s, SessionDown{}, env(t0.Add(10*time.Second)))
	s, _ = Reduce(s, SessionUp{ID: "s2", Limits: withPush(time.Minute)}, env(t0.Add(40*time.Second)))
	_, effs := Reduce(s, StreamOpened{}, env(t0.Add(41*time.Second), head("a", 1, 30), head("b", 1, 41)))
	if !reflect.DeepEqual(effs, []Effect{Send{Stream: "a", Seq: 1}}) {
		t.Fatalf("effects %+v", effs)
	}
}

func TestTurningThePushIntervalOffReleasesEverythingStored(t *testing.T) {
	s := upWithPush(0, time.Minute)
	s, _ = Reduce(s, SessionDown{}, env(t0.Add(30*time.Second)))
	s, _ = Reduce(s, SessionUp{ID: "s2", Limits: limits}, env(t0.Add(40*time.Second)))
	_, effs := Reduce(s, StreamOpened{}, env(t0.Add(41*time.Second), head("a", 1, 20), head("b", 1, 35)))
	if !reflect.DeepEqual(sends(effs), []Send{{"a", 1}, {"b", 1}}) {
		t.Fatalf("effects %+v", effs)
	}
}

// The wall clock stepped back, so a stored batch claims a produced_at after the
// latest tick. It waits, bounded by the step, and goes at the first tick after
// the clock passes it (spec §4.3).
func TestABatchFromTheFutureIsSentOnceTheClockPassesIt(t *testing.T) {
	s := upWithPush(4, time.Minute)
	future := head("a", 1, 100)
	s, effs := Reduce(s, PushDue{}, env(t0.Add(60*time.Second), future))
	if len(effs) != 0 {
		t.Fatalf("sent a batch produced after the tick: %+v", effs)
	}
	_, effs = Reduce(s, PushDue{}, env(t0.Add(120*time.Second), future))
	if !reflect.DeepEqual(effs, []Effect{Send{Stream: "a", Seq: 1}}) {
		t.Fatalf("effects %+v", effs)
	}
}
