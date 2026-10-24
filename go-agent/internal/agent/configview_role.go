package agent

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"time"

	"github.com/solace/solution-manager/go-agent/internal/configview"
	"github.com/solace/solution-manager/go-agent/internal/msg"
	"github.com/solace/solution-manager/go-agent/internal/runtime"
	"github.com/solace/solution-manager/go-agent/internal/runtime/configwriter"
)

// ---- the MI config view (plan 017) ----
//
// A ConfigViewRequest rides the Connect stream and is answered on it with
// exactly one ConfigViewReply: the allow-listed scalar settings of the
// placement's config file, or a refusal that carries one reason and no
// entries. The file path comes from the lease's placement (HostPath) and never
// from the request. Nothing about the file — path, key names, values — is
// logged (I6).

const (
	// configViewHardCap bounds the bytes read from the file and the bytes the
	// filter may emit, whatever the request asks for.
	configViewHardCap = 1 << 20
	// configViewDefaultMax applies when the request names no max_bytes.
	configViewDefaultMax = 256 << 10

	configViewMaxDepth = 64
	configViewMaxNodes = 100_000
	configViewMaxKeys  = 2_000

	// configViewConcurrency bounds requests served at once.
	configViewConcurrency = 4
)

// ConfigViewRole answers config view requests. Handle may block on disk and
// must not be called on the loop goroutine.
type ConfigViewRole interface {
	Handle(ctx context.Context, m msg.ConfigViewRequest, inLease bool) msg.ConfigViewReply
}

type configViewRole struct {
	resolver runtime.Adapter
	log      *slog.Logger
	sem      chan struct{}
}

func newConfigViewRole(resolver runtime.Adapter, log *slog.Logger) *configViewRole {
	return &configViewRole{resolver: resolver, log: log, sem: make(chan struct{}, configViewConcurrency)}
}

// refusedReply is the only constructor of a refusal: one reason, no entries.
func refusedReply(id string, reason msg.ConfigViewReason) msg.ConfigViewReply {
	return msg.ConfigViewReply{RequestID: id, Status: msg.ConfigViewRefused, Reason: reason}
}

// okReply is the only constructor of a success: no reason.
func okReply(id string, res configview.Result) msg.ConfigViewReply {
	entries := make([]msg.ConfigEntry, len(res.Entries))
	for i, e := range res.Entries {
		entries[i] = msg.ConfigEntry{Path: e.Path, Value: e.Value}
	}
	return msg.ConfigViewReply{RequestID: id, Status: msg.ConfigViewOK, Entries: entries,
		HiddenCount: uint32(max(res.Hidden, 0)), Truncated: res.Truncated}
}

func (r *configViewRole) Handle(ctx context.Context, m msg.ConfigViewRequest, inLease bool) (reply msg.ConfigViewReply) {
	defer func() {
		if p := recover(); p != nil { // a hostile file must not take the agent down
			r.log.Error("config view: handler panicked", "request_id", m.RequestID)
			reply = refusedReply(m.RequestID, msg.ConfigViewFileUnreadable)
		}
		if reply.Status == msg.ConfigViewRefused {
			r.log.Debug("config view refused", "request_id", m.RequestID, "reason", string(reply.Reason))
		}
	}()
	select {
	case r.sem <- struct{}{}:
		defer func() { <-r.sem }()
	case <-ctx.Done():
		return refusedReply(m.RequestID, msg.ConfigViewFileUnreadable)
	}
	if !inLease {
		return refusedReply(m.RequestID, msg.ConfigViewPlacementNotInLease)
	}
	p, err := r.resolver.Resolve(ctx, m.PlacementID)
	if err != nil {
		return refusedReply(m.RequestID, msg.ConfigViewFileUnreadable)
	}
	maxBytes := int64(m.MaxBytes)
	if maxBytes == 0 {
		maxBytes = configViewDefaultMax
	}
	maxBytes = min(maxBytes, configViewHardCap)
	// Read at most the hard cap (+1 to see an oversized file): refused before parsing.
	b, err := configwriter.ReadChecked(p.HostPath, configViewHardCap)
	switch {
	case errors.Is(err, configwriter.ErrSymlinkEscape), errors.Is(err, configwriter.ErrNotRegular):
		return refusedReply(m.RequestID, msg.ConfigViewNotPermitted)
	case errors.Is(err, os.ErrNotExist):
		return refusedReply(m.RequestID, msg.ConfigViewFileMissing)
	case err != nil:
		return refusedReply(m.RequestID, msg.ConfigViewFileUnreadable)
	case int64(len(b)) > configViewHardCap:
		return refusedReply(m.RequestID, msg.ConfigViewFileTooLarge)
	}
	res, err := configview.Filter(b, m.Allow, configview.Limits{MaxBytes: int(maxBytes),
		MaxDepth: configViewMaxDepth, MaxNodes: configViewMaxNodes, MaxKeys: configViewMaxKeys})
	switch {
	case errors.Is(err, configview.ErrTooLarge):
		return refusedReply(m.RequestID, msg.ConfigViewFileTooLarge)
	case errors.Is(err, configview.ErrInvalidYAML):
		return refusedReply(m.RequestID, msg.ConfigViewInvalidYAML)
	case errors.Is(err, configview.ErrTooManyKeys):
		return refusedReply(m.RequestID, msg.ConfigViewTooManyKeys)
	case err != nil:
		return refusedReply(m.RequestID, msg.ConfigViewFileUnreadable)
	}
	return okReply(m.RequestID, res)
}

// configViewDone carries a finished reply back to the loop goroutine, the only
// one that may write to the connection's send channel. gen names the
// connection the request arrived on.
type configViewDone struct {
	at    time.Time
	gen   int
	reply msg.ConfigViewReply
}

func (e configViewDone) EventAt() time.Time { return e.at }

// serveConfigView answers m off the loop goroutine. Exactly one reply is
// pushed per request; one for a connection that has since gone is dropped by
// translate.
func (l *Loop) serveConfigView(ctx context.Context, m msg.ConfigViewRequest) {
	l.mu.Lock()
	inLease := false
	if g := l.state.Authority.Grant; g != nil {
		for _, b := range g.BindingIDs {
			if b == m.PlacementID {
				inLease = true
				break
			}
		}
	}
	l.mu.Unlock()
	gen := l.connGen
	go func() {
		rep := l.d.ConfigView.Handle(ctx, m, inLease)
		l.Push(configViewDone{at: l.d.Now(), gen: gen, reply: rep})
	}()
}
