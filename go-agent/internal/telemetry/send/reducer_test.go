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

var limits = msg.Limits{InitialCredits: 2, MaxBatchBytes: 65536, ReportInterval: time.Minute}

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

func TestReduceDoesNotMutateItsInput(t *testing.T) {
	s := up(1)
	s.InFlight["a"] = 1
	_, _ = Reduce(s, Ack{Stream: "a", HWM: 1, Credits: 1}, env(t0, head("b", 1, 0)))
	if s.InFlight["a"] != 1 || len(s.Acked) != 0 {
		t.Fatalf("input mutated: %+v", s)
	}
}
