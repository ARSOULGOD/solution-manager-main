package commands

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/solace/solution-manager/go-agent/internal/store"
)

// The command payload spool (OR-129, AG-D29). The rendered payload is the
// configuration file's content and can hold resolved secrets (I6); the agent
// writes that file to the host anyway, so a second on-host copy for the life of
// a command is a bounded, named exception:
//
//   - the file is mode 0600 inside a 0700 directory, owned by the agent's user
//     (store.WriteAtomic creates both; SweepSpool re-checks them at start);
//   - it is deleted in the effect batch that makes the record terminal
//     (terminate), on every terminal path;
//   - at start, before the first event, SweepSpool deletes every spool file whose
//     record is terminal, acknowledged, superseded or absent.
//
// Neither the payload nor any plain digest of it is ever logged.
const (
	SpoolFileMode os.FileMode = 0o600
	SpoolDirMode  os.FileMode = 0o700
)

// SweepSpool runs once at start. It removes every apply/<binding>/<generation>
// directory that has no live journal record (live = not yet REPORTED), removes
// stray temp files from the live ones, tightens or refuses wrong modes and
// owners, and returns the set of live records whose payload is on disk with the
// bytes the record's hash names (resume() runs a RECEIVED record only from such
// a spool).
func SweepSpool(s *store.Store, j Journal) (map[string]bool, error) {
	live := map[string]bool{}
	root := s.Path("apply")
	bindings, err := os.ReadDir(root)
	if errors.Is(err, os.ErrNotExist) {
		return live, nil
	}
	if err != nil {
		return live, err
	}
	for _, b := range bindings {
		if !b.IsDir() {
			continue
		}
		gens, err := os.ReadDir(filepath.Join(root, b.Name()))
		if err != nil {
			return live, err
		}
		for _, g := range gens {
			if !g.IsDir() {
				continue
			}
			gen, perr := strconv.ParseUint(g.Name(), 10, 64)
			rec, ok := Record{}, false
			if perr == nil {
				rec, ok = j.Get(b.Name(), gen)
			}
			rel := "apply/" + b.Name() + "/" + g.Name()
			// superseded: a crash between the newer generation's record and the delete of this one's
			// partials leaves it live on disk; it must not resume over the newer config.
			superseded := ok && !rec.Terminal() && rec.RolloutGeneration < j.AppliedGeneration(b.Name())
			if !ok || rec.Terminal() || superseded {
				// terminal, acknowledged, superseded or unknown: the spool has no business here
				if err := s.DeleteAll(rel); err != nil {
					return live, fmt.Errorf("spool sweep %s: %w", rel, err)
				}
				continue
			}
			if err := secureSpoolDir(s.Path(rel)); err != nil {
				// A wrong-owner or unfixable directory is not trusted: remove it. The record
				// then fails its payload read (STAGED/PERSISTED) or is discarded and redelivered (RECEIVED).
				if rmErr := s.DeleteAll(rel); rmErr != nil {
					return live, fmt.Errorf("spool sweep %s: %v; remove: %w", rel, err, rmErr)
				}
				continue
			}
			if payloadMatches(s, rel+"/payload", rec.ConfigHash) {
				live[key(b.Name(), gen)] = true
			}
		}
	}
	return live, nil
}

// secureSpoolDir enforces 0700/0600 and the agent's own uid on a live spool
// directory and drops temp files left by a crashed write.
func secureSpoolDir(dir string) error {
	fi, err := os.Lstat(dir)
	if err != nil {
		return err
	}
	if !fi.IsDir() {
		return errors.New("not a directory")
	}
	if !ownedByAgent(fi) {
		return errors.New("spool directory is not owned by the agent's user")
	}
	if fi.Mode().Perm() != SpoolDirMode {
		if err := os.Chmod(dir, SpoolDirMode); err != nil {
			return err
		}
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	for _, e := range entries {
		p := filepath.Join(dir, e.Name())
		if strings.HasSuffix(e.Name(), ".tmp") {
			if err := os.Remove(p); err != nil {
				return err
			}
			continue
		}
		info, err := os.Lstat(p)
		if err != nil {
			return err
		}
		if !info.Mode().IsRegular() || !ownedByAgent(info) {
			return fmt.Errorf("spool entry %s is not a regular file of the agent's user", e.Name())
		}
		if info.Mode().Perm() != SpoolFileMode {
			if err := os.Chmod(p, SpoolFileMode); err != nil {
				return err
			}
		}
	}
	return nil
}

func payloadMatches(s *store.Store, rel, wantHash string) bool {
	b, err := os.ReadFile(s.Path(rel))
	return err == nil && sha256Hex(b) == wantHash
}
