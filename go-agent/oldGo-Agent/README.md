# oldGo-Agent — archived scaffold

The `go-agent` packages as they stood before the rewrite against the
[agent-gateway design](../../docs/design/backend/agent-gateway/design.md):
`internal/config`, `internal/docker`, `internal/persist`, `internal/telemetry`,
the two demo CLIs in `cmd/sm-agent/main.go`, and the `java-demo` fixtures.

**Not built, not tested, not maintained.** It is a nested Go module purely so the
parent module's `go build ./...` skips it; its imports no longer resolve and it is
not expected to compile.

**Kept for reference, superseded for authority.** The gateway design's fork
inventory named three structural incompatibilities: the journal phases
(`PREFLIGHT|STAGING|RENAMED|RESTARTING|INDETERMINATE` against the baseline
`RECEIVED→STAGED→PERSISTED→REPORTED`), a config shape carrying a separate
telemetry endpoint where the design has one connection, and `WriteFileAtomic`
without the parent-directory fsync that design §5.1 requires.

Still worth reading here: how `internal/docker` drove the Engine API, and the
scrape/validate/store shape in `internal/telemetry` that slice 2's telemetry role
will need. What was correct was re-derived in the new packages — the atomic write
(now with the parent fsync) and the symlink-escape refusal — not copied.
