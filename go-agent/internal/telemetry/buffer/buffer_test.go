package buffer

import (
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/store"
)

var t0 = time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC)

func at(sec int) time.Time { return t0.Add(time.Duration(sec) * time.Second) }

func samples() []Sample {
	return []Sample{{Name: "cache.hit_rate", Type: "gauge", TS: t0, Value: 0.5, ReplicaRef: "java1", Incarnation: "c1"}}
}

func open(t *testing.T, dir string, opt Options) *Buffer {
	t.Helper()
	b, err := Open(store.MustOpen(dir), opt)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func mustAppend(t *testing.T, b *Buffer, stream string, produced time.Time) uint64 {
	t.Helper()
	seq, err := b.Append(stream, produced, samples())
	if err != nil {
		t.Fatal(err)
	}
	return seq
}

// oneBatch measures one batch's stored size, so caps are expressed in batches.
func oneBatch(t *testing.T) int64 {
	b := open(t, t.TempDir(), Options{})
	mustAppend(t, b, "a", at(1))
	return b.Stats().Bytes
}

func TestAppendNumbersEachStreamFromOne(t *testing.T) {
	b := open(t, t.TempDir(), Options{})
	got := []uint64{mustAppend(t, b, "a", at(1)), mustAppend(t, b, "a", at(2)), mustAppend(t, b, "b", at(3))}
	if !reflect.DeepEqual(got, []uint64{1, 2, 1}) {
		t.Fatalf("seqs %v", got)
	}
}

func TestReopenContinuesAboveTheHighestFile(t *testing.T) {
	dir := t.TempDir()
	b := open(t, dir, Options{})
	for i := 1; i <= 3; i++ {
		mustAppend(t, b, "a", at(i))
	}
	b2 := open(t, dir, Options{})
	if h := b2.Heads(); len(h) != 1 || h[0].Seq != 1 {
		t.Fatalf("heads after reopen %+v", h)
	}
	if seq := mustAppend(t, b2, "a", at(4)); seq != 4 {
		t.Fatalf("a reopened buffer reused a seq: %d", seq)
	}
}

func TestTrimDeletesThroughTheWatermarkAndSurvivesReopen(t *testing.T) {
	dir := t.TempDir()
	b := open(t, dir, Options{})
	for i := 1; i <= 3; i++ {
		mustAppend(t, b, "a", at(i))
	}
	b.Trim("a", 2)
	for _, seq := range []uint64{1, 2} {
		if _, err := os.Stat(filepath.Join(dir, "telemetry", "a", seqName(seq))); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("batch %d still on disk: %v", seq, err)
		}
	}
	b2 := open(t, dir, Options{})
	if r := b2.Resume(); r["a"] != 2 {
		t.Fatalf("resume %v", r)
	}
	if h := b2.Heads(); len(h) != 1 || h[0].Seq != 3 {
		t.Fatalf("heads %+v", h)
	}
}

func TestACrashBetweenStateAndDeleteIsRepairedOnLoad(t *testing.T) {
	dir := t.TempDir()
	b := open(t, dir, Options{})
	mustAppend(t, b, "a", at(1))
	mustAppend(t, b, "a", at(2))
	// Trim's first write landed; its deletes did not.
	st := store.MustOpen(dir)
	if err := st.Save(store.JSON{RelPath: "telemetry/a/state.json", Value: streamState{SchemaVersion: 1, AckedSeq: 2}}); err != nil {
		t.Fatal(err)
	}
	b2 := open(t, dir, Options{})
	if h := b2.Heads(); len(h) != 0 {
		t.Fatalf("files at or below acked_seq must be removed on load: %+v", h)
	}
	if seq := mustAppend(t, b2, "a", at(3)); seq != 3 {
		t.Fatalf("seq %d", seq)
	}
}

func TestByteCapDropsTheOldestBatchAcrossStreams(t *testing.T) {
	size := oneBatch(t)
	var dropped []string
	b := open(t, t.TempDir(), Options{MaxBytes: 2*size + size/2, OnDrop: func(r string, n int) { dropped = append(dropped, r) }})
	mustAppend(t, b, "a", at(1))
	mustAppend(t, b, "b", at(2))
	mustAppend(t, b, "a", at(3)) // three batches over a two-batch cap: a/1 is the oldest
	if !reflect.DeepEqual(dropped, []string{ReasonDroppedOldest}) {
		t.Fatalf("drops %v", dropped)
	}
	if g := b.GapsBefore("a", 99); !reflect.DeepEqual(g, []Gap{{From: 1, To: 1, Reason: ReasonDroppedOldest}}) {
		t.Fatalf("gaps %+v", g)
	}
	if h := b.Heads(); len(h) != 2 || h[0].Stream != "b" || h[1].Stream != "a" || h[1].Seq != 2 {
		t.Fatalf("heads %+v", h)
	}
}

func TestAnInFlightBatchIsNeverDropped(t *testing.T) {
	size := oneBatch(t)
	b := open(t, t.TempDir(), Options{MaxBytes: 2*size + size/2})
	mustAppend(t, b, "a", at(1))
	if !b.MarkInFlight("a", 1) {
		t.Fatal("MarkInFlight refused an existing batch")
	}
	mustAppend(t, b, "a", at(2))
	mustAppend(t, b, "a", at(3)) // over the cap: a/1 is in flight, so a/2 goes
	if g := b.GapsBefore("a", 99); !reflect.DeepEqual(g, []Gap{{From: 2, To: 2, Reason: ReasonDroppedOldest}}) {
		t.Fatalf("gaps %+v", g)
	}
	if _, err := b.Read("a", 1); err != nil {
		t.Fatalf("the in-flight batch was dropped: %v", err)
	}
}

func TestContiguousDropsMergeIntoOneGap(t *testing.T) {
	size := oneBatch(t)
	b := open(t, t.TempDir(), Options{MaxBytes: size + size/2})
	for i := 1; i <= 4; i++ {
		mustAppend(t, b, "a", at(i))
	}
	if g := b.GapsBefore("a", 99); !reflect.DeepEqual(g, []Gap{{From: 1, To: 3, Reason: ReasonDroppedOldest}}) {
		t.Fatalf("gaps %+v", g)
	}
}

func TestAgeCapExpiresOldBatchesOnSweep(t *testing.T) {
	now := at(0)
	b := open(t, t.TempDir(), Options{MaxAge: time.Hour, Now: func() time.Time { return now }})
	mustAppend(t, b, "a", at(0))
	now = at(2 * 3600)
	b.Sweep()
	if g := b.GapsBefore("a", 99); !reflect.DeepEqual(g, []Gap{{From: 1, To: 1, Reason: ReasonExpired}}) {
		t.Fatalf("gaps %+v", g)
	}
}

// Review Focus 3: the clock stepped back, so produced_at lies in the future.
// Such a batch is not "old", but it still occupies the byte cap.
func TestFutureTimestampsStillObeyTheByteCap(t *testing.T) {
	size := oneBatch(t)
	b := open(t, t.TempDir(), Options{MaxBytes: 2*size + size/2, MaxAge: time.Hour, Now: func() time.Time { return at(0) }})
	for i := 1; i <= 5; i++ {
		mustAppend(t, b, "a", at(3600+i)) // an hour ahead of the agent's clock
	}
	b.Sweep()
	if s := b.Stats(); s.Bytes > 2*size+size/2 || s.Batches != 2 {
		t.Fatalf("stats %+v over a cap of %d", s, 2*size+size/2)
	}
	if g := b.GapsBefore("a", 99); !reflect.DeepEqual(g, []Gap{{From: 1, To: 3, Reason: ReasonDroppedOldest}}) {
		t.Fatalf("gaps %+v", g)
	}
}

func TestAWriteFailureIsReportedNotFatal(t *testing.T) {
	dir := t.TempDir()
	st := store.MustOpen(dir)
	fail := true
	var reported []error
	b, err := Open(st, Options{
		Save: func(r store.Record) error {
			if fail {
				return errors.New("disk full")
			}
			return st.Save(r)
		},
		OnWriteError: func(err error) { reported = append(reported, err) },
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := b.Append("a", at(1), samples()); err == nil {
		t.Fatal("Append must return the write error")
	}
	if len(reported) != 1 {
		t.Fatalf("reported %v", reported)
	}
	fail = false
	if seq := mustAppend(t, b, "a", at(2)); seq != 1 {
		t.Fatalf("a failed write must not consume a seq: %d", seq)
	}
}

func TestDropAsGapReleasesTheInFlightMark(t *testing.T) {
	b := open(t, t.TempDir(), Options{})
	mustAppend(t, b, "a", at(1))
	mustAppend(t, b, "a", at(2))
	b.MarkInFlight("a", 1)
	b.DropAsGap("a", 1, "UNKNOWN_REPLICA")
	if h := b.Heads(); len(h) != 1 || h[0].Seq != 2 {
		t.Fatalf("heads %+v", h)
	}
	if g := b.GapsBefore("a", 2); !reflect.DeepEqual(g, []Gap{{From: 1, To: 1, Reason: "UNKNOWN_REPLICA"}}) {
		t.Fatalf("gaps %+v", g)
	}
}

func TestTrimClearsDeliveredGaps(t *testing.T) {
	dir := t.TempDir()
	b := open(t, dir, Options{})
	mustAppend(t, b, "a", at(1))
	mustAppend(t, b, "a", at(2))
	b.DropAsGap("a", 1, ReasonOversize)
	b.Trim("a", 2) // batch 2 carried the gap and was acked
	if g := open(t, dir, Options{}).GapsBefore("a", 99); len(g) != 0 {
		t.Fatalf("delivered gaps must not survive: %+v", g)
	}
}

func TestACorruptBatchFileBecomesAGap(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "telemetry", "a"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "telemetry", "a", seqName(1)), []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	b := open(t, dir, Options{})
	if g := b.GapsBefore("a", 99); !reflect.DeepEqual(g, []Gap{{From: 1, To: 1, Reason: ReasonCorrupt}}) {
		t.Fatalf("gaps %+v", g)
	}
	if seq := mustAppend(t, b, "a", at(2)); seq != 2 {
		t.Fatalf("seq %d", seq)
	}
}

func TestTrimAboveNextMovesNextPastTheGatewayWatermark(t *testing.T) {
	b := open(t, t.TempDir(), Options{})
	b.Trim("a", 10)
	if seq := mustAppend(t, b, "a", at(1)); seq != 11 {
		t.Fatalf("seq %d would be treated as a duplicate", seq)
	}
}

func TestMarkInFlightRefusesAMissingBatch(t *testing.T) {
	if open(t, t.TempDir(), Options{}).MarkInFlight("a", 5) {
		t.Fatal("MarkInFlight accepted a batch that does not exist")
	}
}

func TestTrimWithAFailedStateWriteKeepsFilesSoSeqsAreNotReused(t *testing.T) {
	dir := t.TempDir()
	st := store.MustOpen(dir)
	failState := false
	b, err := Open(st, Options{Save: func(r store.Record) error {
		if j, ok := r.(store.JSON); ok && failState && filepath.Base(j.RelPath) == "state.json" {
			return errors.New("disk full")
		}
		return st.Save(r)
	}})
	if err != nil {
		t.Fatal(err)
	}
	for i := 1; i <= 3; i++ {
		mustAppend(t, b, "a", at(i))
	}
	failState = true
	b.Trim("a", 3)
	if h := b.Heads(); len(h) != 0 {
		t.Fatalf("in-memory index must drop trimmed batches: %+v", h)
	}
	b2 := open(t, dir, Options{})
	if seq := mustAppend(t, b2, "a", at(4)); seq != 4 {
		t.Fatalf("seq %d reused after a failed state write", seq)
	}
}

func TestATruncatedStateFileIsReportedAndOpenSucceeds(t *testing.T) {
	dir := t.TempDir()
	b := open(t, dir, Options{})
	mustAppend(t, b, "a", at(1))
	if err := os.WriteFile(filepath.Join(dir, "telemetry", "a", "state.json"), []byte("{trunc"), 0o600); err != nil {
		t.Fatal(err)
	}
	var reported []error
	if _, err := Open(store.MustOpen(dir), Options{OnWriteError: func(err error) { reported = append(reported, err) }}); err != nil {
		t.Fatal(err)
	}
	if len(reported) != 1 {
		t.Fatalf("reported %v", reported)
	}
}

func TestUnsafeStreamIdsAreRefused(t *testing.T) {
	dir := t.TempDir()
	var reported []error
	b := open(t, dir, Options{OnWriteError: func(err error) { reported = append(reported, err) }})
	for _, id := range []string{".", "..", "", "a/b"} {
		if _, err := b.Append(id, at(1), samples()); err == nil {
			t.Fatalf("Append accepted %q", id)
		}
	}
	if len(reported) != 0 {
		t.Fatalf("a caller bug is not a storage fault: %v", reported)
	}
	b.Trim("..", 5)
	b.DropAsGap("..", 1, ReasonOversize)
	if _, err := os.Stat(filepath.Join(dir, "state.json")); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("Trim wrote outside telemetry/: %v", err)
	}
	if len(b.Resume()) != 0 {
		t.Fatalf("Trim created a stream for an invalid id: %v", b.Resume())
	}
}

func TestTrimClearsAGapAtTheWatermarkAndClampsOneStraddlingIt(t *testing.T) {
	b := open(t, t.TempDir(), Options{})
	b.mu.Lock()
	s := b.stream("a")
	s.state.Gaps = []Gap{{From: 1, To: 1, Reason: ReasonOversize}}
	b.mu.Unlock()
	b.Trim("a", 1)
	if g := b.GapsBefore("a", 99); len(g) != 0 {
		t.Fatalf("a gap at the watermark is false: %+v", g)
	}
	b.mu.Lock()
	s.state.Gaps = []Gap{{From: 2, To: 4, Reason: ReasonOversize}}
	b.mu.Unlock()
	b.Trim("a", 3)
	if g := b.GapsBefore("a", 99); len(g) != 1 || g[0].From != 4 || g[0].To != 4 {
		t.Fatalf("straddling gap must clamp to {4,4}: %+v", g)
	}
}
