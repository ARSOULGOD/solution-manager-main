# Plan 010 T9.1 - measured facts (M11) and the transport changes they forced

Written by T9.1. The plan keeps these in its "Measured facts" table; plan 010 is not edited by the lanes (beyond each task's
own Results), so the integrator folds this file's row into that table. `I2`: every fact names where it came from.

## M11 - `docker logs --timestamps --since --until --tail` semantics

Documented (WebFetch `https://docs.docker.com/reference/cli/docker/container/logs/`, 2026-10-06): `--timestamps` adds an
RFC3339Nano stamp (`2014-09-16T06:17:46.000000000Z`) to each entry; `--since`/`--until` accept RFC 3339, UNIX timestamps and Go
durations; `--tail` is "number of lines to show from the end of the logs" and "can be combined with `--since`". The page does
not say which stream a container's output reaches, how `--tail` interacts with `--until`, or whether the bounds are inclusive.

**Measured** (2026-10-06, Docker Engine 28.5.1 on this machine, two `alpine:3.20` containers started and removed by the lane,
output read with and without `2>&1`):

| Question | Measured answer |
|---|---|
| Line format | `<RFC3339Nano UTC stamp, 9 digits><space><the container's line>`, e.g. `2026-10-06T06:25:17.036499796Z out-1` |
| `--since T` | **Inclusive** of a line stamped exactly `T` |
| `--until T` | **Inclusive** of a line stamped exactly `T` |
| `--tail N` with `--since T` | The newest `N` lines at or after `T` (tail-then-since equals since-then-tail, because `since` only cuts a prefix) |
| `--tail N` with `--until T` | ⚠️ **`--tail` is applied to the whole log FIRST, then `--until` filters.** `--until T6 --tail 2` over an 8-line log printed NOTHING (the last two lines are after `T6`); `--since T2 --until T6 --tail 2` also printed nothing; the same window with `--tail 99` printed `l-2 .. l-6`. So a tail cannot bound a backwards (`before`) read. |
| Container stderr | **Written to the exec's stderr, not stdout.** With `2>/dev/null` a container that logged `out-N` on stdout and `err-N` on stderr showed only the `out-` lines, and `--tail 3` then showed ONE stdout line (the tail counts both streams). The transport reads the exec's stdout only, so stderr is merged with `2>&1`. |
| Missing container | `Error response from daemon: No such container: <name>` and exit status 1 (so a failure is not mistaken for a log line: the transport fails on a non-zero exit) |

## What it changed in the transport

`SshjDockerTransport.logsCommand` (new, package-visible, tested without an SSH server) builds the one exec string for
`readLogs`:

* `--tail <limit>` is sent **unless there is a `before`**; with a `before` the host returns the whole window (still
  capped at 512 KiB by the transport, `ssh.docker.output.too.large` beyond it) and the data plane keeps the newest `limit`
  lines. A `before` over a very large log therefore answers a declared `unavailable` page, not a wrong one.
* `2>&1` is appended, so the container's stderr lines are part of the page.
* `after` and `before` may both be supplied (`--since` + `--until`); a window with no interior is refused before any session.

The older `SshDockerTransport.logs(...)` (the demo's) is untouched: it still sends `--tail` with `--until` and does not merge
stderr, so a demo "older page" can come back empty. It is the demo module's, deleted with it (plan 009); flagged, not fixed.
