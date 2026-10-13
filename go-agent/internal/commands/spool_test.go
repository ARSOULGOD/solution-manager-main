package commands

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/reactor"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/store"
)

// execute applies an effect batch the way the loop does: in effect order,
// Persist and Delete against the real store (Delete of an extension-less path is
// DeleteAll, as in loop.execute).
func execute(t *testing.T, s *store.Store, effs []reactor.Effect) {
	t.Helper()
	for _, e := range reactor.Sort(effs) {
		switch e := e.(type) {
		case reactor.Persist:
			if err := s.Save(e.Rec); err != nil {
				t.Fatal(err)
			}
		case reactor.Delete:
			del := s.Delete
			if filepath.Ext(e.Rel) == "" {
				del = s.DeleteAll
			}
			if err := del(e.Rel); err != nil {
				t.Fatal(err)
			}
		}
	}
}

func mode(t *testing.T, p string) os.FileMode {
	t.Helper()
	fi, err := os.Stat(p)
	if err != nil {
		t.Fatal(err)
	}
	return fi.Mode().Perm()
}

const secretPayload = "password: hunter2-spool\n"

// OR-129 / AG-D29 item 1: the spool file is 0600 in a 0700 directory.
func TestSpoolFileIs0600InA0700Directory(t *testing.T) {
	st := store.MustOpen(t.TempDir())
	_, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: offer(1, secretPayload, msg.OpApply)}, env(t0), okAuth)
	execute(t, st, effs)
	payload := st.Path(PayloadRel("b1", 1))
	if got := mode(t, payload); got != 0o600 {
		t.Fatalf("spool file mode = %o, want 600", got)
	}
	for _, d := range []string{filepath.Dir(payload), filepath.Dir(filepath.Dir(payload))} {
		if got := mode(t, d); got != 0o700 {
			t.Fatalf("spool directory %s mode = %o, want 700", d, got)
		}
	}
	if b, _ := os.ReadFile(payload); string(b) != secretPayload {
		t.Fatal("the spool holds the payload")
	}
}

// AG-D29 item 2: the file is purged in the batch that makes the record terminal,
// on EVERY terminal path.
func TestSpoolIsPurgedOnEveryTerminalOutcome(t *testing.T) {
	expected := "d0d0"
	cases := []struct {
		name  string
		op    msg.Operation
		drive func(s State) (State, []reactor.Effect)
	}{
		{"ACKED (applied)", msg.OpApply, func(s State) (State, []reactor.Effect) {
			return Reduce(s, reactor.OpFinished{At: t0, CommandID: "c1", Result: runtime.OpResult{Outcome: msg.OutcomeApplied}}, env(t0), okAuth)
		}},
		{"REFUSED", msg.OpApply, func(s State) (State, []reactor.Effect) {
			return Reduce(s, reactor.OpFinished{At: t0, CommandID: "c1", Result: runtime.OpResult{Outcome: msg.OutcomeRefused}}, env(t0), okAuth)
		}},
		{"FAILED_NO_EFFECT", msg.OpApply, func(s State) (State, []reactor.Effect) {
			return Reduce(s, reactor.OpFinished{At: t0, CommandID: "c1", Result: runtime.OpResult{Outcome: msg.OutcomeFailedNoEffect}}, env(t0), okAuth)
		}},
		{"TARGET_MOVED", msg.OpRematerialize, func(s State) (State, []reactor.Effect) {
			return Reduce(s, reactor.OpFinished{At: t0, CommandID: "c1", Result: runtime.OpResult{Outcome: msg.OutcomeRefused,
				Detail: msg.AckDetail{Code: msg.DetailOf(msg.ErrTargetMoved)}}}, env(t0), okAuth)
		}},
		{"RENDER_CHANGED", msg.OpApply, func(s State) (State, []reactor.Effect) {
			return Reduce(s, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrRenderChanged, CorrelationID: "c1"}}, env(t0), okAuth)
		}},
		{"superseded", msg.OpApply, func(s State) (State, []reactor.Effect) {
			return Reduce(s, reactor.WireIn{At: t0, Msg: msg.Error{Code: msg.ErrSuperseded, CorrelationID: "c1"}}, env(t0), okAuth)
		}},
		{"deadline", msg.OpApply, func(s State) (State, []reactor.Effect) {
			return Reduce(s, reactor.TimerFired{At: t0.Add(3 * time.Minute), Kind: reactor.StepDeadline}, env(t0.Add(3*time.Minute)), okAuth)
		}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			st := store.MustOpen(t.TempDir())
			o := offer(1, secretPayload, c.op)
			if c.op == msg.OpRematerialize {
				o.ExpectedCurrentSHA256 = &expected
			}
			s, effs := Reduce(newState(), reactor.WireIn{At: t0, Msg: o}, env(t0), okAuth)
			execute(t, st, effs)
			if !st.Exists(PayloadRel("b1", 1)) {
				t.Fatal("the payload must be spooled while the command is live")
			}
			_, effs = c.drive(s)
			execute(t, st, effs)
			if st.Exists(PayloadRel("b1", 1)) {
				t.Fatalf("%s: the spool file survived the terminal outcome", c.name)
			}
			r, _ := s.Journal.Get("b1", 1)
			_ = r
		})
	}
}

func putRecord(t *testing.T, st *store.Store, b string, gen uint64, step msg.ProgressStep, payload string) Record {
	t.Helper()
	r := Record{SchemaVersion: 1, BindingID: b, RolloutGeneration: gen, CommandID: "c-" + b + "-" + string(rune('0'+gen)), Operation: msg.OpApply,
		ConfigHash: sha(payload), Step: step}
	if step == msg.StepReported {
		r.Outcome, r.AckSent = msg.OutcomeApplied, true
	}
	if err := st.Save(r); err != nil {
		t.Fatal(err)
	}
	if payload != "" {
		if err := st.Save(store.Blob{RelPath: PayloadRel(b, gen), Data: []byte(payload)}); err != nil {
			t.Fatal(err)
		}
	}
	return r
}

// AG-D29 item 3: at start the agent deletes every spool file whose record is
// terminal, acknowledged, superseded or absent, and KEEPS a live RECEIVED or
// STAGED file.
func TestStartupSweepDeletesTerminalAndUnknownKeepsLive(t *testing.T) {
	st := store.MustOpen(t.TempDir())
	putRecord(t, st, "b1", 1, msg.StepReceived, secretPayload)
	putRecord(t, st, "b1", 2, msg.StepStaged, secretPayload)
	putRecord(t, st, "b3", 1, msg.StepPersisted, secretPayload) // its own binding: a PERSISTED generation supersedes the lower ones of its binding
	putRecord(t, st, "b2", 4, msg.StepReported, secretPayload)  // terminal (another binding: an applied generation also supersedes the lower ones of its own): a crash between the record and the purge left the file
	// unknown: a spool file whose record never became durable
	if err := st.Save(store.Blob{RelPath: PayloadRel("b9", 7), Data: []byte(secretPayload)}); err != nil {
		t.Fatal(err)
	}
	// a stray temp file of a crashed write inside a live directory
	tmp := filepath.Join(st.Path(DirRel("b1", 2)), ".payload.123.tmp")
	if err := os.WriteFile(tmp, []byte(secretPayload), 0o600); err != nil {
		t.Fatal(err)
	}

	state, err := Load(st)
	if err != nil {
		t.Fatal(err)
	}
	for _, k := range [][2]string{{"b1", "1"}, {"b1", "2"}, {"b3", "1"}} {
		if !st.Exists("apply/" + k[0] + "/" + k[1] + "/payload") {
			t.Errorf("%v: a live spool file must be kept", k)
		}
	}
	if st.Exists(PayloadRel("b2", 4)) || st.Exists(DirRel("b2", 4)) {
		t.Error("a terminal record's spool must be swept")
	}
	if st.Exists(PayloadRel("b9", 7)) || st.Exists(DirRel("b9", 7)) {
		t.Error("an unknown spool file (no live record) must be swept")
	}
	if _, err := os.Stat(tmp); err == nil {
		t.Error("a crashed write's temp file must be swept")
	}
	if !state.Spool[key("b1", 1)] || !state.Spool[key("b1", 2)] || !state.Spool[key("b3", 1)] || state.Spool[key("b2", 4)] {
		t.Errorf("Load reports the live spool wrongly: %v", state.Spool)
	}
}

// The sweep re-checks the modes: a live spool file or directory that is wider
// than 0600/0700 is tightened, never trusted.
func TestStartupSweepTightensModes(t *testing.T) {
	st := store.MustOpen(t.TempDir())
	putRecord(t, st, "b1", 1, msg.StepStaged, secretPayload)
	dir, file := st.Path(DirRel("b1", 1)), st.Path(PayloadRel("b1", 1))
	if err := os.Chmod(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(file, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := Load(st); err != nil {
		t.Fatal(err)
	}
	if mode(t, dir) != 0o700 || mode(t, file) != 0o600 {
		t.Fatalf("modes after sweep: dir %o file %o", mode(t, dir), mode(t, file))
	}
}

// A symlink planted in a live spool directory is not followed or trusted.
func TestStartupSweepRefusesANonRegularSpoolEntry(t *testing.T) {
	st := store.MustOpen(t.TempDir())
	putRecord(t, st, "b1", 1, msg.StepStaged, "")
	dir := st.Path(DirRel("b1", 1))
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	outside := filepath.Join(t.TempDir(), "elsewhere")
	if err := os.WriteFile(outside, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(dir, "payload")); err != nil {
		t.Skip("no symlinks here")
	}
	state, err := Load(st)
	if err != nil {
		t.Fatal(err)
	}
	if st.Exists(DirRel("b1", 1)) || state.Spool[key("b1", 1)] {
		t.Fatal("a spool directory holding a symlink must be removed")
	}
	if _, err := os.Stat(outside); err != nil {
		t.Fatal("the sweep must not touch what the link pointed at")
	}
}

// OR-129: after a restart a live RECEIVED command resumes from the spool WITHOUT
// a CommandPull; STAGED does too. A RECEIVED record whose spool is missing or does
// not match its hash is discarded for the DP to redeliver.
func TestResumeFromSpoolAfterRestartWithoutCommandPull(t *testing.T) {
	for _, step := range []msg.ProgressStep{msg.StepReceived, msg.StepStaged} {
		t.Run(string(step), func(t *testing.T) {
			st := store.MustOpen(t.TempDir())
			putRecord(t, st, "b1", 1, step, secretPayload)
			loaded, err := Load(st) // the restart
			if err != nil {
				t.Fatal(err)
			}
			_, effs := Reduce(loaded, reactor.Resumed{At: t0}, env(t0), okAuth)
			op, ok := has[reactor.RunOp](effs)
			if !ok || op.PayloadRel != PayloadRel("b1", 1) || op.SHA256 != sha(secretPayload) {
				t.Fatalf("the command must run from the spool: %+v", effs)
			}
			for _, e := range effs {
				if snd, ok := e.(reactor.Send); ok {
					if _, pull := snd.Msg.(msg.CommandPull); pull {
						t.Fatal("a resume from the spool must not CommandPull")
					}
				}
			}
		})
	}
}

func TestResumeOfReceivedWithACorruptSpoolIsDiscarded(t *testing.T) {
	st := store.MustOpen(t.TempDir())
	r := putRecord(t, st, "b1", 1, msg.StepReceived, secretPayload)
	if err := st.Save(store.Blob{RelPath: PayloadRel("b1", 1), Data: []byte("tampered")}); err != nil {
		t.Fatal(err)
	}
	loaded, _ := Load(st)
	_, effs := Reduce(loaded, reactor.Resumed{At: t0}, env(t0), okAuth)
	if _, run := has[reactor.RunOp](effs); run {
		t.Fatal("a spool that does not hash to the record must not run")
	}
	if d, ok := has[reactor.Delete](effs); !ok || !strings.HasPrefix(d.Rel, "apply/b1/") {
		t.Fatalf("the corrupt RECEIVED record must be discarded: %+v (%+v)", effs, r)
	}
}
