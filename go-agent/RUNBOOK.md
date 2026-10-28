# Drive java1 through the fake gateway, one typed command at a time

| | |
|---|---|
| **Applies to** | `go-agent` slice 1 (control path), DRAFT wire (`AG-D13`), `fake-gateway --interactive` (`AG-D16`) |
| **Severity** | routine — development and demo |
| **Est. duration** | 2 minutes to start; then 1–11 s per command |
| **Reversible?** | `preflight`: nothing changes. `apply`: **no** — it replaces `~/sm-conf/application.yml` and keeps no copy on disk. `restart`/`stop`: yes, type the other one. |
| **Status** | **Executed end to end on 2026-10-01**, by script typing into the gateway's stdin, against a stand-in built exactly like `java1` except for its binding id and ports. The commands as written here, against `java1` itself, have not been run yet. |

This is a developer procedure against a test double, not an operator runbook — which is why it lives here and
not in [`../docs/runbooks/`](../docs/runbooks/README.md), whose README reserves that folder for operators.

## When to use this

You want to send `preflight`, `apply`, `restart` or `stop` to the agent, have it carry each one out on the `java1`
container, and see each outcome — as many commands as you like, in any order.

## How it works, in four facts

1. **You start the gateway and the agent once, then type commands into the gateway's terminal.** One command per
   line. The gateway prints `offered` at once, and the outcome when the agent acks it.
2. **The gateway numbers the commands.** Generations go up by one per command for the binding, and are never reused
   while the gateway runs: the agent refuses a generation below the highest it has seen, and answers a repeated one
   from its journal without acting. The built-in scenario's lease has one binding, `binding-1` (`java1`'s label), and
   a command goes there. When a lease has more than one, each command names its target: see
   [More than one container](#more-than-one-container).
3. **Restarting the agent alone is fine; restarting the gateway is not.** The agent reconnects with the identity in
   its data dir and the next command just works. But every gateway start mints a new CA and forgets every enrolled
   agent (`internal/fakegw/server.go`, `Start`), so after a gateway restart the agent must start again **from an
   empty data dir**.
4. **There is no `start` command.** The wire has exactly `preflight`, `apply`, `restart` and `stop` (gateway
   [`design.md`](../docs/design/backend/agent-gateway/design.md) §4.7). To start a stopped `java1`, type `restart`.

## Preconditions

1. **Docker Desktop is running:** `docker version` shows a `Server:` section.
2. **The binaries are built**, from `go-agent/`:

   ```bash
   make build          # bin/sm-agent, bin/fake-gateway
   ```

3. **`java1` exists and carries everything the agent resolves it by** (`AG-D20`). The agent looks the container
   up before **every** command, `stop` included, so a container missing any of these fails all four:

   | Requirement | On `java1` |
   |---|---|
   | binding label, **unique across all containers, stopped ones included** | `com.solace.sm.binding-id=binding-1` |
   | a declared config location | env `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/app/` |
   | that directory bind-mounted from the host, read-write | `~/sm-conf` → `/etc/app` |
   | the host directory under `$HOME` | Docker Desktop refuses to mount `/tmp` |

   Check with:

   ```bash
   docker ps -a --filter label=com.solace.sm.binding-id=binding-1 --format '{{.Names}}'   # exactly: java1
   ```

   If it does not exist, create it:

   ```bash
   mkdir -p ~/sm-conf
   docker run -d --name java1 \
     --label com.solace.sm.binding-id=binding-1 \
     -e SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/app/ \
     -v ~/sm-conf:/etc/app \
     alpine:3 sleep infinity
   ```

   `java1` is a **stand-in**: nothing inside it reads `application.yml`. An `apply` proves the agent wrote the
   file, not that an application consumed it.

4. **Ports `8443` and `9464` are free**, and no earlier `sm-agent` or `fake-gateway` is still running:

   ```bash
   pgrep -af 'bin/(sm-agent|fake-gateway)'   # prints nothing
   ```

## Steps

Open two terminals, both in `go-agent/`.

### 1. Terminal 1 — start the gateway

```bash
./bin/fake-gateway --interactive --listen 127.0.0.1:8443 \
  --enroll-token demo-token --ca-out bin/ca.pem
```

It prints `type: preflight | apply <file> | restart | stop  [@<binding>|@all] | view <placement> <allow,comma,list> | logs <placement> [limit] | export <placement> [from-seq] | pause | resume | disconnect | set push <duration>|unset` and `fake gateway listening`. No `--scenario` is needed:
the built-in one grants a one-hour lease for `binding-1` with both capabilities, and sends nothing on its own.

### 2. Terminal 2 — start the agent from an empty data dir

⚠️ This **deletes `bin/agent-data`**: the agent's key, certificate and command journal from an earlier gateway run.
All three belong to a gateway that no longer exists (fact 3).

```bash
export DOCKER_HOST=unix://$HOME/.docker/desktop/docker.sock   # Docker Desktop only; see below
rm -rf bin/agent-data
./bin/sm-agent --data-dir bin/agent-data \
  --connect-endpoint 127.0.0.1:8443 \
  --enrollment-token demo-token \
  --trust-bundle bin/ca.pem
```

**Do not drop the `export`.** The agent's Docker SDK reads `DOCKER_HOST`, not the `docker` CLI's context. Without
it the agent dials `/var/run/docker.sock`, which Docker Desktop does not create — and the `docker` CLI keeps
working, which hides the cause.

Within a second, **terminal 1** logs `msg="fakegw: agent connected" agent=agent-…`. Type nothing before that line
appears.

### 3. Terminal 1 — type commands

Type a command and press Enter. The number in each line is the generation, so it counts up across commands — the
examples below are the first four of a fresh gateway.

| Type | Prints | Confirm on the container | Took (verified run) |
|---|---|---|---|
| `preflight` | `preflight-binding-1-1 gen=1 -> PREFLIGHT_OK` | nothing changed: `docker inspect -f '{{.State.Running}} {{.State.StartedAt}}' java1` is the same as before | 1 s |
| `apply <file>` | `apply-binding-1-2 gen=2 -> APPLIED` | `cat ~/sm-conf/application.yml` shows the file's content; `stat -c %a ~/sm-conf/application.yml` is `600` | 1 s |
| `restart` | `restart-binding-1-3 gen=3 -> APPLIED` | `docker inspect -f '{{.State.Running}} {{.State.StartedAt}}' java1` is `true` with a **newer** `StartedAt` | 11 s running · 1 s stopped |
| `stop` | `stop-binding-1-4 gen=4 -> APPLIED` | `docker inspect -f '{{.State.Running}} {{.State.ExitCode}}' java1` is `false 137` | 11 s |

Each command first prints `<op>-<binding>-<n> gen=<n>: offered`; the `->` line follows when the agent acks. (The 2026-10-01 run
printed ids without the binding, `preflight-1`; the binding was added to the id on 2026-10-14.)

- **You need not wait for one result before typing the next.** The agent runs commands one at a time, in the order
  you typed them.
- **`apply <file>` reads the file when you press Enter**, with the path relative to `go-agent/`. Before the first
  apply, back up the file it replaces — the agent keeps the previous content only in memory, for rollback (`I6`):

  ```bash
  cp ~/sm-conf/application.yml ~/sm-conf-application.yml.bak
  ```

- **On Docker Desktop, confirm an `apply` on the host file, not with `docker exec java1 cat`.** The agent replaces
  the file by rename, and after a rename the container's view of that path lagged the host — by about 5 s in one
  measured run and by minutes in another (2026-10-01) — while `ls` inside the container already showed the new
  size. A write into the existing file showed at once. This is Docker Desktop's VM file sharing, not the agent.
- **`restart` and `stop` of a running `java1` take about 10 s longer.** `sleep infinity` runs as PID 1, which
  ignores `SIGTERM`, so Docker waits out its 10 s stop timeout and sends `SIGKILL` — hence exit code `137`. A
  `restart` of a **stopped** `java1` starts it in about a second.
- A failure prints the agent's reason on the same line, for example
  `-> FAILED_NO_EFFECT: Cannot connect to the Docker daemon …`. See [If it goes wrong](#if-it-goes-wrong).

### More than one container

**Status: executed on 2026-10-14**, against two busybox stand-ins `cont1` and `cont2` (bindings of the same names), with
gateway `127.0.0.1:18443` and admin `127.0.0.1:19464` because another run held the defaults.

Give the gateway a `--scenario` whose lease names every binding, for example `lease: {binding_ids: [cont1, cont2]}`.
Each container needs the label, the declared config location and the bind mount from precondition 3, under its own
binding id and its own host directory. A command then names its target with an `@` word, anywhere on the line:

| Type | Prints |
|---|---|
| `preflight` | `which binding? add @cont1 \| @cont2 \| @all`: nothing is offered without a target |
| `restart @nope` | `binding "nope" is not in the lease (cont1, cont2)` |
| `preflight @all` | `preflight-cont1-1 gen=1 -> PREFLIGHT_OK`, then `preflight-cont2-1 gen=1 -> PREFLIGHT_OK`: one command per binding, in lease order |
| `apply <file> @cont2` | `apply-cont2-2 gen=2 -> APPLIED`; only `cont2`'s `application.yml` changes |
| `stop @cont1` | `stop-cont1-2 gen=2 -> APPLIED`: generations count per binding |

The agent still runs one command at a time, so `@all` restarts the containers one after the other, not together.

### 4. Stop

`Ctrl-C` the agent (terminal 2), then the gateway (terminal 1). On exit the gateway prints its transcript, one line
per wire message. Next time, start again from step 1 — including the `rm -rf` (fact 3).

## Verification

- Each command's `->` line names the expected outcome.
- The container check in the same row agrees with it.

## If it goes wrong

Rows marked *observed* were reproduced on 2026-10-01; the others are read from the code and not reproduced.

| Symptom | Cause | Fix |
|---|---|---|
| `restart-binding-1-1 gen=1 -> FAILED_NO_EFFECT: Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?` (a `preflight` says `PREFLIGHT_REFUSED` instead). *Observed.* | `DOCKER_HOST` is not exported in the agent's terminal. | `Ctrl-C` the agent, `export` it, start the agent again **without** the `rm -rf` — the gateway is still the same one. |
| `-> PREFLIGHT_REFUSED: docker: no container carries the binding label: com.solace.sm.binding-id=binding-1` (`FAILED_NO_EFFECT` for the other commands). *Observed.* | `java1` lacks the label. | Precondition 3. |
| The same, with `more than one container carries the binding label`. | Another container — a stopped one counts — carries `binding-1`. | Precondition 3's check must print only `java1`. |
| The same, with `declares no external config location` or `is not a bind mount`. | `java1` lacks the env var or the mount. | Recreate it with precondition 3's `docker run`. |
| `-> PREFLIGHT_REFUSED: config directory is not writable`. | `~/sm-conf` is mounted read-only. | Mount it read-write. |
| Every command prints `no agent connected`, and terminal 1 never logged `agent connected`. With the agent running, `curl -s localhost:9464/healthz` shows `"session_phase":"CLOSED"`. *Observed.* | The agent started from an earlier gateway's data dir; that gateway's CA and enrolment are gone (fact 3). | `Ctrl-C` the agent and redo step 2 — including the `rm -rf`. |
| `apply: open <file>: no such file or directory`. | The path is relative to `go-agent/`, where the gateway runs. | Give an absolute path. |
| `<op>-<binding>-<n> gen=<n>: no ack within 5m0s`. | The agent went away while the command was in flight. | Check terminal 2; restart the agent (no `rm -rf`) and type the command again. |
| The gateway exits with `bind: address already in use`. | An earlier `fake-gateway` or `sm-agent` is still running. | `pkill -f bin/fake-gateway; pkill -f bin/sm-agent`, then start again from step 1. |
| `"msg":"send failed","err":"EOF"` when you `Ctrl-C` the agent. *Observed, every run.* | The stream closes under it. | Nothing — expected. |

## Telemetry

The agent can also scrape a binding's container and send the numbers to the gateway (`AG-D22`, interim). This
section runs that against the same fake gateway, with a stand-in container that serves a fixed `stats.json`.

**Status: executed on 2026-10-02**, all four demonstrations below, but not with the commands exactly as written
here. A session was already using the defaults (gateway `127.0.0.1:8443`, admin `127.0.0.1:9464`, `bin/`, binding
`binding-1`), so the run used a scratch directory, gateway `127.0.0.1:18443`, admin `127.0.0.1:19464`, a throwaway
container `sm-telemetry-demo` published on `127.0.0.1:18080`, and the binding `tele-demo-1`
(a copy of `examples/telemetry.yaml` with the binding renamed, and a one-line `--scenario` of
`lease: {binding_ids: [tele-demo-1]}`). The steps below keep the standard names, because that is what you run; the
lines in the output table carry the binding id as recorded (`tele-demo-1`), so read it as `binding-1`.

### 1. The stand-in container

Check nothing else carries the label, then start it. Docker Desktop shares only `$HOME`.

```bash
docker ps -a --filter label=com.solace.sm.binding-id=binding-1     # must print only your own container
mkdir -p ~/sm-telemetry-demo
docker run -d --name sm-telemetry-demo --label com.solace.sm.binding-id=binding-1 \
  -e SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/app/ -v ~/sm-telemetry-demo:/etc/app \
  -p 127.0.0.1:18080:8080 busybox:1.36 \
  sh -c 'mkdir -p /www && echo "{\"hitRate\":0.93,\"missCount\":12}" > /www/stats.json && httpd -f -p 8080 -h /www'
```

The port must be **published** (`-p`): the agent reaches the container through the host port Docker assigned.

### 2. The declarations file

[`examples/telemetry.yaml`](examples/telemetry.yaml) declares two metrics for `binding-1`, scraped every 5 s from
`/stats.json` on container port 8080: `cache.hit_rate` (gauge, `/hitRate`) and `cache.miss_count` (counter,
`/missCount`). A metric whose pointer is absent produces no sample, never a zero.

### 3. Start the gateway and the agent

Terminal 1 is step 1 above, unchanged. Terminal 2 is step 2 with two flags added:

```bash
rm -rf bin/agent-data
./bin/sm-agent --data-dir bin/agent-data \
  --connect-endpoint 127.0.0.1:8443 \
  --enrollment-token demo-token \
  --trust-bundle bin/ca.pem \
  --roles control,telemetry --telemetry-config examples/telemetry.yaml
```

**The first line appears about one report interval after the agent connects** (one minute with the built-in
scenario). The agent observes nothing at `SessionAccept`; the first actual-state report, which mints the ids every
sample carries, goes out one interval later. Expect no `telemetry …` line until then. In the recorded run the
session opened at 08:38:02 and the lines arrived at about 08:39:03, all twelve batches collected meanwhile (seq 1–12) at once, then one
every 5 s.

On start the agent may log `telemetry: declared binding is not in the lease; not collecting` once, before the
session opens. It is not an error if lines follow (they did).

### 4. What terminal 1 prints

Each line now starts with the local time, `[hh:mm:ss]` (plan 008); the recorded lines below predate the prefix.

| When | Terminal 1 (recorded) |
|---|---|
| Steady state | `telemetry tele-demo-1 seq=12 cache.hit_rate=0.93 cache.miss_count=12 -> acked` |
| After `docker exec sm-telemetry-demo sh -c 'echo "{\"hitRate\":0.5,\"missCount\":13}" > /www/stats.json'` | `telemetry tele-demo-1 seq=13 cache.hit_rate=0.5 cache.miss_count=13 -> acked` (first line with the new values) |
| `pause` | `telemetry paused: acks grant no credits`, then four more lines (`seq=16`…`seq=19`, the four credits the agent held), then silence |
| `resume` | `telemetry resumed: 4 credits granted`, then the buffered batches in order: `seq=20`, `seq=21`, `seq=22` … `seq=29`, then live lines |
| `disconnect` | `session closed; the agent will reconnect`, then `fakegw: agent connected`, then `seq=30`, `seq=31` … with no `duplicate` line |

While paused, watch the agent's gauges (terminal 3):

```bash
curl -s localhost:9464/metrics | grep sm_agent_telemetry_
```

- `sm_agent_telemetry_credits` falls to `0`.
- `sm_agent_telemetry_buffer_batches` rises, one per 5 s (recorded: 4 at about 45 s, 9 at about 100 s), and
  `sm_agent_telemetry_buffer_bytes` with it. After `resume` they return to `0` and credits to `4`.
- `sm_agent_telemetry_dropped_batches_total{reason}` has no series until the first drop; a series appears when the byte cap, the age cap or another gap reason drops a batch.
- `sm_agent_telemetry_scrape_errors_total{reason}` counts failed scrapes.

### 5. The push interval set by the gateway

The gateway can set how often the agent pushes what it has stored (`AG-D30`). It travels on `SessionAccept`, so a change applies
when the agent next connects: until you `disconnect`, the live session keeps the value it was accepted with. `fake-gateway` sends
an explicit `0` unless told otherwise, which is why step 4's lines arrive every 5 s; the real gateway sends **no** value today,
and the agent then pushes every minute. `set` stores any value, including one the agent will refuse: the gateway is a test double.

**Status: not yet executed.**

In terminal 1, with step 4's lines arriving every 5 s:

```
set push 1m
disconnect
```

For a quicker demo use `set push 15s`: three lines per burst instead of twelve.

| When | Terminal 1 |
|---|---|
| `set push 1m` | `next session: push=1m0s (disconnect to apply)` |
| `disconnect` | `session closed; the agent will reconnect`; whatever was stored while it reconnected is sent at once |
| Then, once a minute | about twelve `[hh:mm:ss] telemetry binding-1 seq=…` lines with the same time, then a minute of silence |

In terminal 3:

```bash
curl -s localhost:9464/metrics | grep -E 'sm_agent_telemetry_(push_interval_seconds|limit_ignored_total)'
```

Expect `sm_agent_telemetry_push_interval_seconds 60`. `sm_agent_telemetry_limit_ignored_total` has no series until a value is
refused.

**What the real gateway sends.** `set push unset` and `disconnect`: the field is left out, and the gauge stays at `60`.

**A refused value.** `set push 500ms` and `disconnect`. A push below 1 s is refused, and so is one not below
`--telemetry-buffer-age` (24 h by default). Terminal 2 logs `telemetry: DP push interval refused; using the default`, naming the
`value` and the `reason` (`below 1s`); terminal 3 shows `sm_agent_telemetry_limit_ignored_total{limit="push"} 1` and the gauge
at the default `60`.

`set push 0` and `disconnect` return to sending each batch as soon as it is written.

### 6. Clean up

`Ctrl-C` both processes, then `docker rm -f sm-telemetry-demo` and `rm -rf ~/sm-telemetry-demo`.

### Telemetry: if it goes wrong

| Symptom | Cause | Fix |
|---|---|---|
| `sm_agent_telemetry_scrape_errors_total{reason="addr"}` rises (the log line, `docker: port is neither published nor reachable on a container network: 8080 (container …)`, is DEBUG-level, so run with `--log-level debug` to see it) | The container does not publish the port. | Recreate it with `-p 127.0.0.1:<host>:8080`. |
| No `telemetry …` lines at all | Still inside the first report interval, or every scrape fails. | Wait one minute; then `curl -s localhost:9464/metrics \| grep scrape_errors_total` and read the `reason` label. |
| Agent log: `declared binding is not in the lease; not collecting`, and it stays | `examples/telemetry.yaml` names a binding the gateway's lease does not grant. | Rename the binding to one in the lease, or give the gateway a `--scenario` whose `lease: {binding_ids: [...]}` lists it. |

## Logs

**Status: end to end in code and demoed against `fake-gateway` on 2026-10-29; not yet run against a real gateway.** The
agent answers log requests on the `Logs` stream by reading the managed integration's (MI's) `/actuator/logfile` over HTTP
when the DP asks, and **stores nothing** (plan 018, design
[`2026-10-28-agent-mi-logs-from-actuator-design.md`](../docs/design/backend/agent-gateway/specs/2026-10-28-agent-mi-logs-from-actuator-design.md)).
The gateway and DP side exist (plan 016 Tasks 7-10; plan 018 dropped the DP-set size limit), but no real agent, gateway and container
have been run together. The DP half, and what the MI must be configured with, is in
[`docs/runbooks/backend/read-and-export-agent-logs.md`](../docs/runbooks/backend/read-and-export-agent-logs.md#mi-preconditions).

**Enable it.** Add `logs` to the roles: `--roles control,telemetry,logs` (or `SM_AGENT_ROLES`). The agent advertises
`CAPABILITY_LOGS` to the gateway and opens the `Logs` stream after the session is accepted. Between requests it does
nothing: no collector, no schedule, no files.

**Where it reads.** `http://<host>:<port>/actuator/logfile`: the path is fixed (a moved base path, path mapping or context
path is not supported). The container is the one labelled `com.solace.sm.binding-id=<placement>`, resolved as `apply`
resolves it (`AG-D20`); a container that declares no config location, or does not bind-mount it, is `NOT_COLLECTING` (read
from the code, not reproduced). The port comes from the container's own launch spec, in this order:

| Order | Source |
|---|---|
| 1 | the `--management.server.port=` argument, else the env `MANAGEMENT_SERVER_PORT` |
| 2 | the `--server.port=` argument, else the env `SERVER_PORT` |
| 3 | `8080` |

Arguments are the container's entrypoint and command in exec form. A JVM `-Dserver.port=`, `JAVA_TOOL_OPTIONS`/`JAVA_OPTS`
and a shell-form `CMD` are **not read**. A declared value that is empty or not 1-65535 is refused (`NOT_COLLECTING`), never
replaced by a later one. The port goes through the same host mapping as telemetry scraping (`ScrapeAddr`), so the container
must **publish** it (`-p 127.0.0.1::<port>`). No proxy, no redirects, no credentials, a 4 s timeout on every call. The MI
facts behind this (`logfile` must be exposed, `Range`, and `404` for a missing log file) are verified in the design's §3.

**A page** (`logs`). The agent reads the end of the file in ranged steps and replies with one `LogPage`: the newest lines,
oldest first. `stream` is `file`. A line's time is its entry's time in UTC; the continuation lines of a stack trace take
their entry's.

**A download** (`export`). The download is a snapshot: the file up to the size the first ranged reply reports. It sends one
chunk per credit, each one ranged read of up to `max_batch_bytes`, cut back to the last whole line. **`seq` is a byte offset
in the current file**, and a chunk's `seq` is where `export <placement> <seq>` continues. Only the current file is served,
never a rotated archive.

**Gaps.** A reply can carry gap markers:

| Reason | Meaning |
|---|---|
| `DROPPED_OLDEST` | The `from-seq` was beyond the file's size, so the file was replaced: the download starts again at byte 0 and the gap comes first. |
| `COLLECTION_PAUSED` | A read failed, or the file shrank, during a download; the final chunk carries it (also what the DP prints as "incomplete … resume from seq N"). |

`OVERSIZE`, `CONTAINER_UNREADABLE` and `CAP_REDUCED` are never emitted: a line over the size limit is cut and flagged
`truncated`.

**Unavailable page.** A page with an unavailable reason carries no lines:

| Reason | When |
|---|---|
| `PLACEMENT_NOT_IN_LEASE` | The gateway's lease does not grant the placement. |
| `BUSY` | More than 4 requests are in flight. |
| `CONTAINER_MISSING` | No container carries the label. |
| `NOT_COLLECTING` | The port is unusable, the endpoint is unreachable or times out, it answers `401`, `403` or `404`, or it answers a ranged request with a whole-file `200` (no `Range` support). |

**Known limits** are in the design's §9 and the DP runbook's
[Known limits](../docs/runbooks/backend/read-and-export-agent-logs.md#known-limits): the download is the current file only;
resuming across a rotation can mix two files; a log in a non-default pattern repeats lines in the console's live poll.

**Privacy.** Nothing is redacted: served lines are the MI's file as written. Log text never goes to the agent's own log or
into an error string; a refusal carries an enum reason only.

### Try it against the fake gateway

**Status: executed 2026-10-29**, steps 1 to 8 as written, by piping the step 6 commands into the gateway. Ports: the gateway
listened on `18443` (`--listen`, and the agent's `--connect-endpoint`) and the agent's admin listener on `19464`
(`--admin-addr`), in place of `8443` and the default `9464` below. The code blocks of steps 1 to 5, 7 and 8 were then
extracted from this file and run unedited apart from those ports. The stand-in is `nginx:alpine` (nginx 1.31.8 the day it was
pulled). Agent and fake gateway only; no DP, console or REST layer. The commands are also unit-tested against a scripted
agent (`internal/fakegw/console_logs_test.go`).

| Type | Sends | Prints |
|---|---|---|
| `logs <placement> [limit]` | a page of the newest `limit` lines (default 20) | the lines oldest first (`time stream text`, `[truncated]` where cut), then any gaps. The time column is local time; the text carries the file's own UTC stamp |
| `export <placement> [from-seq]` | an export from byte offset `from-seq` (default 0) | each chunk as it arrives, then `complete, N lines in M chunks`; credits come from the scenario's `logs.credits_per_chunk` (1 by default) |

A chunk's `seq` is its resume point: if an export stops early, `export <placement> <seq>` continues after the last chunk printed.
The gateway also prints its own one-line summary of every page and chunk (`logs page …`, `logs chunk …`).

Every command below runs from `go-agent/`, in three terminals: **T1** the gateway (you type commands here), **T2** the agent,
**T3** Docker and checks.

1. **T3: build, and check nothing is in the way.**

   ```bash
   export DOCKER_HOST=unix://$HOME/.docker/desktop/docker.sock                          # Docker Desktop only
   make build                                                                            # bin/sm-agent, bin/fake-gateway
   pgrep -af 'bin/(sm-agent|fake-gateway)'                                               # must print nothing
   docker ps -a --filter label=com.solace.sm.binding-id=logs-1 --format '{{.Names}}'     # must print nothing
   docker ps -a --filter label=com.solace.sm.binding-id=nolog-1 --format '{{.Names}}'    # must print nothing
   ```

   If `pgrep` prints a process, `Ctrl-C` it in its terminal. If `docker ps` prints `logs-demo` or `logs-nolog`, it is left from
   an earlier run: `docker rm -f logs-demo logs-nolog`. The label must be unique across all containers, stopped ones included.

2. **T3: the containers.** Neither is an MI (`I1` does not apply to them); they stand in for the one thing the agent reads.
   - `logs-demo` appends one `INFO` line a second to a file in Spring Boot's default pattern, and a `WARN` with a two-line
     `\tat` continuation every fifth, and nginx serves that file at `/actuator/logfile` with `Range` support.
   - `logs-nolog` is plain nginx: it carries the label but has no `/actuator/logfile`.
   - `SERVER_PORT=80` is the container port the agent reads, `-p 127.0.0.1::80` publishes it, and the label, the declared
     config location and the bind mount under `$HOME` are what the agent resolves a container by (`AG-D20`).

   ```bash
   mkdir -p ~/sm-logs-demo
   cat > ~/sm-logs-demo/mi.sh <<'EOF'
   # Stands in for an MI: nginx serves a file that grows, at /actuator/logfile, with Range support.
   mkdir -p /usr/share/nginx/html/actuator
   f=/usr/share/nginx/html/actuator/logfile
   (i=0
    while :; do
      i=$((i+1)); t=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)
      echo "$t  INFO 1 --- [main] demo.App : request $i served" >> $f
      if [ $((i % 5)) -eq 0 ]; then
        printf "%s  WARN 1 --- [main] demo.App : slow downstream on request %s\n\tat demo.App.call(App.java:42)\n\tat demo.App.main(App.java:17)\n" "$t" "$i" >> $f
      fi
      sleep 1
    done) &
   exec nginx -g "daemon off;"
   EOF
   docker run -d --name logs-demo --label com.solace.sm.binding-id=logs-1 \
     -e SERVER_PORT=80 -p 127.0.0.1::80 \
     -e SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/app/ -v ~/sm-logs-demo:/etc/app \
     nginx:alpine sh /etc/app/mi.sh
   docker run -d --name logs-nolog --label com.solace.sm.binding-id=nolog-1 \
     -e SERVER_PORT=80 -p 127.0.0.1::80 \
     -e SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/app/ -v ~/sm-logs-demo:/etc/app \
     nginx:alpine
   ```

   Check both endpoints yourself, as the agent will (busybox `date` has no milliseconds, so the stamps end in `.000Z`):

   ```bash
   P=$(docker port logs-demo 80/tcp | head -1 | sed 's/.*://')                    # the host port Docker picked
   curl -si -r -200 http://127.0.0.1:$P/actuator/logfile | head -20               # 206 Partial Content, e.g. Content-Range: bytes 378-577/578, the last 200 bytes
   P2=$(docker port logs-nolog 80/tcp | head -1 | sed 's/.*://')
   curl -si http://127.0.0.1:$P2/actuator/logfile | head -3                       # HTTP/1.1 404 Not Found
   ```

3. **T3: the lease.** The gateway's built-in lease grants only `binding-1`; this one grants `logs-1` and `nolog-1`.
   `max_batch_bytes: 4096` makes an export several chunks; at the built-in 64 KiB this small file is a single chunk
   (observed: 192 lines in one chunk).

   ```bash
   cat > bin/logs-lease.yaml <<'EOF'
   lease: {target_id: target-1, binding_ids: [logs-1, nolog-1], capabilities: [apply-config, lifecycle], duration: 1h}
   limits: {max_batch_bytes: 4096}
   EOF
   ```

4. **T1: the gateway.** Wait for `fake gateway listening`.

   ```bash
   ./bin/fake-gateway --interactive --scenario bin/logs-lease.yaml --listen 127.0.0.1:8443 \
     --enroll-token demo-token --ca-out bin/ca.pem
   ```

5. **T2: the agent, from an empty data dir, with the logs role.** Both matter: a new gateway forgets every agent (fact 3),
   and without `logs` in `--roles` every `logs` and `export` prints `the session did not advertise logs`.

   ```bash
   export DOCKER_HOST=unix://$HOME/.docker/desktop/docker.sock
   rm -rf bin/agent-data
   ./bin/sm-agent --data-dir bin/agent-data --connect-endpoint 127.0.0.1:8443 \
     --enrollment-token demo-token --trust-bundle bin/ca.pem --roles control,logs
   ```

   T1 logs `fakegw: agent connected`. There is nothing to wait for: the agent reads the file when asked.

6. **T1: type.**

   | Type | Expect |
   |---|---|
   | `logs logs-1 5` | the five newest lines, `file` as the stream. A `WARN` and its two `\tat` lines count as three lines. Observed: `request 110`'s `INFO`, `WARN` and two `at demo.App…` lines, then `request 111`'s `INFO` |
   | `logs logs-1` | the newest 20 lines, or a few fewer (observed 18): a page ends on an entry boundary, see the DP runbook's [Known limits](../docs/runbooks/backend/read-and-export-agent-logs.md#known-limits) |
   | `export logs-1` | the file as it was when the download began, in chunks of up to 4096 bytes (about 31 lines), each with its `seq`, then `complete, N lines in M chunks`. Observed: `seq=2007` … `seq=15930`, a final `seq=16966` with 16 lines, `complete, 264 lines in 9 chunks` |
   | `export logs-1 <seq>`, with a `seq` from the line above | the lines after that chunk only. Observed with `15930`: the first chunk (`seq=17940`, 30 lines) began at `request 156` |
   | `export logs-1 99999999` | a `from-seq` beyond the file: the first chunk carries `gap DROPPED_OLDEST from 00:48:00 to 00:48:00` and the download starts at byte 0. Observed: `complete, 290 lines in 10 chunks` |
   | `logs nope` | `unavailable: PLACEMENT_NOT_IN_LEASE` |
   | `logs nolog-1` | `unavailable: NOT_COLLECTING`: the container is there and answers, but `/actuator/logfile` is `404` |
   | `docker stop logs-demo` (T3), then `logs logs-1 3` | `unavailable: NOT_COLLECTING` |
   | `docker start logs-demo` (T3), wait 3 s, then `logs logs-1 3` | lines again. Docker picked a new host port (`33787`, then `42583`) and the agent followed, because it looks the port up on every request. The stand-in's counter restarts at `request 1` |
   | `docker rm -f logs-demo` (T3), then `logs logs-1 3` | `unavailable: CONTAINER_MISSING`. Run step 2's `docker run … logs-demo` again, wait 5 s: lines return, from the new, empty file |

7. **T3: nothing is stored.** After the pages and downloads above, the agent's data dir holds only its identity, journal and
   session; there is no `logs` directory.

   ```bash
   ls bin/agent-data                                                                  # apply  identity  reports  session
   ```

8. **Stop and clean up.** `Ctrl-C` T2, then T1. Then in T3:

   ```bash
   docker rm -f logs-demo logs-nolog
   rm -rf ~/sm-logs-demo bin/logs-lease.yaml
   ```

### Logs: if it goes wrong

Every symptom was reproduced on 2026-10-29 (ports `18443`/`19464`, against the containers of step 2), and the fix was run for every row but the `404` one, whose fix is configuration of a real MI that the stand-in does not have.

| Symptom | Cause | Fix |
|---|---|---|
| `logs` or `export` prints `fakegw: the session did not advertise logs`. | The agent was started without `logs` in `--roles`, so its session never advertised `CAPABILITY_LOGS`. | `Ctrl-C` the agent and start it again with `--roles control,logs`, **without** the `rm -rf`: the gateway is still the same one (fact 3). The next `logs` returns lines. |
| `logs logs-1` prints `unavailable: PLACEMENT_NOT_IN_LEASE`. | The gateway was started without `--scenario bin/logs-lease.yaml`; the built-in lease grants only `binding-1`. | `Ctrl-C` both and redo steps 3 to 5: the gateway with the scenario, then the agent from an empty data dir, `rm -rf` included (fact 3). |
| `logs nolog-1` (or any placement whose container is running) prints `unavailable: NOT_COLLECTING`, and `curl -si http://127.0.0.1:<port>/actuator/logfile \| head -1` prints `HTTP/1.1 404 Not Found`. | The MI has no log file to serve: no `logging.file.name`/`logging.file.path`, or the file is unreadable, which the design's §3 verifies answers `404`. `nolog-1` is plain nginx and stands in for that case only. The agent maps `401`, `403` and `404` to `NOT_COLLECTING`, and refuses any answer other than `206`/`416`, or an empty `200`, the same way. | Not an agent fault: the MI's logging and exposure settings are its own, and SM writing them is not in scope (design §9). Set them as the DP runbook's [MI preconditions](../docs/runbooks/backend/read-and-export-agent-logs.md#mi-preconditions) say, restart the MI, and check `curl -si -r -200 http://127.0.0.1:<port>/actuator/logfile \| head -1` prints `HTTP/1.1 206 Partial Content`. |
| `logs logs-1` prints `unavailable: NOT_COLLECTING`, and the same `curl` against the port the MI serves on answers. | The container declares no `SERVER_PORT` (or `--server.port=`) for the port it serves on, so the agent tried `8080`, which is not published. Reproduced by recreating `logs-demo` without `-e SERVER_PORT=80`. A stopped container gives the same answer. | Declare the port in the container's exec-form args or env, publish it (`-p 127.0.0.1::<port>`), and recreate the container; `docker start` a stopped one. |
| `logs logs-1` prints `unavailable: CONTAINER_MISSING`. | No container carries `com.solace.sm.binding-id=logs-1` (it was removed, or never created). | Create it (step 2). The next `logs` returns lines, with no agent restart. |

## Config view

**Status: in code, not yet run against a real gateway.** The agent answers a `ConfigViewRequest` (wire `GatewayToAgent` 17,
reply `AgentToGateway` 12) with the allow-listed entries of a placement's YAML config file (plan 017 Tasks 1-3, run against
`fake-gateway` scenarios). The DP half is in
[`docs/runbooks/backend/read-agent-config.md`](../docs/runbooks/backend/read-agent-config.md).

**Enable it.** Nothing to set: the role is wired whenever the agent has an adapter, and it advertises
`CAPABILITY_CONFIG_VIEW` (8) only then. A gateway sends nothing to an agent without that capability.

**What it reads.** The request names only a placement and an allow list; the file path comes from the lease's placement,
never from the request. On Docker the agent resolves it as `apply` does (`AG-D20`, `internal/runtime/docker`):

1. the one container labelled `com.solace.sm.binding-id=<placement>`;
2. the config directory that container declares: a `--spring.config.additional-location=` or `--spring.config.location=`
   launch argument first, else the env `SPRING_CONFIG_ADDITIONAL_LOCATION` or `SPRING_CONFIG_LOCATION` (only the first
   location of a comma list);
3. that directory mapped to the host through its bind mount;
4. `application.yml` in it. The file name is fixed.

A placement that does not resolve (no such container, two of them, no declared directory, no bind mount) is
`FILE_UNREADABLE` (read from the code, not reproduced). The agent reads the **host** file, not through the container, and
reads it again on every request: an edit shows in the next view with no restart. The read refuses a symlink, a FIFO
or any path that escapes the placement directory (`NOT_PERMITTED`), and reads at most the limit plus one byte. The allow
list comes with the request and the agent filters by it; the DP re-checks the reply. `hidden` counts every scalar the
allow list leaves out, not only secrets.

**Limits (fixed in the agent).**

| Limit | Value |
|---|---|
| Hard cap on bytes read and emitted | 1 MiB (the request's `max_bytes` is clamped to it; `0` means 256 KiB) |
| Nesting depth | 64 |
| Expanded nodes (aliases counted) | 100000 |
| Emitted keys | 2000 (more sets `truncated`) |
| Path bytes built (hidden included) | 4 x `max_bytes` |
| Concurrent reads | 4 (a fifth is refused at once with `FILE_UNREADABLE`; the wire has no busy reason) |
| Allow list accepted | 256 patterns and 16 KiB total (more is `NOT_PERMITTED`) |

**Privacy.** The agent never logs the file's content, path or keys; only the request id and the reason code. A secret under
a key that is not on the allow list is hidden and only counted in `hidden_count`.

### Try it against the fake gateway

**Status: executed 2026-10-26** (agent and fake gateway only; no DP, console or REST layer). **Re-run 2026-10-27** by piping
the commands into the gateway (ports `18443`/`19464`, binding `cfgview-verify`): every row of step 7's table except
`management.*` printed as written, and step 3's `docker inspect` lines printed the label, the env and the mount. The `view`
console command sends a `ConfigViewRequest` to the live agent and prints `view-<n> -> <status> <reason> hidden=<n>
truncated=<bool>`, then one `    <path> = <value>` line per entry.

Every command below runs from `go-agent/`, in three terminals: **T1** the gateway (you type commands here), **T2** the agent,
**T3** Docker, the config file and checks.

1. **T3: build, and check nothing is in the way.**

   ```bash
   export DOCKER_HOST=unix://$HOME/.docker/desktop/docker.sock                          # Docker Desktop only
   make build                                                                            # bin/sm-agent, bin/fake-gateway
   pgrep -af 'bin/(sm-agent|fake-gateway)'                                               # must print nothing
   docker ps -a --filter label=com.solace.sm.binding-id=cfgview-1 --format '{{.Names}}'  # must print nothing
   ```

   If `pgrep` prints a process, `Ctrl-C` it in its terminal: a gateway left from the Logs demo does not grant `cfgview-1`.
   If `docker ps` prints `cfgview-demo`, it is left from an earlier run: `docker rm -f cfgview-demo`. The label must be
   unique across all containers, stopped ones included.

2. **T3: the config file and the container.** The file holds two secrets, a Datadog `api-key` and a datasource password.
   Back it up: step 7 replaces it.

   ```bash
   mkdir -p ~/sm-cfgview-demo
   cat > ~/sm-cfgview-demo/application.yml <<'EOF'
   server: {port: 8080}
   management: {server: {port: 8081}, endpoints: {web: {exposure: {include: [health, info]}}}, datadog: {metrics: {export: {api-key: SUPER-SECRET-KEY}}}}
   info: {app: cache-engine}
   spring: {datasource: {password: hunter2}}
   EOF
   docker run -d --name cfgview-demo --label com.solace.sm.binding-id=cfgview-1 \
     -e SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/app/ -v ~/sm-cfgview-demo:/etc/app alpine:3 sleep infinity
   cp ~/sm-cfgview-demo/application.yml ~/sm-cfgview-demo-application.yml.bak
   ```

3. **T3: show where the agent will read from.** This follows [What it reads](#config-view) by hand:

   ```bash
   docker inspect cfgview-demo --format '{{index .Config.Labels "com.solace.sm.binding-id"}}'    # cfgview-1
   docker inspect cfgview-demo --format '{{json .Config.Env}}'                                     # SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/app/
   docker inspect cfgview-demo --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{end}}'  # <your $HOME>/sm-cfgview-demo -> /etc/app
   cat ~/sm-cfgview-demo/application.yml                                                           # the file the agent reads
   ```

4. **T3: the lease.** The gateway's built-in lease grants only `binding-1`; this one grants `cfgview-1`.

   ```bash
   cat > bin/cfgview-lease.yaml <<'EOF'
   lease: {target_id: target-1, binding_ids: [cfgview-1], capabilities: [apply-config, lifecycle], duration: 1h}
   EOF
   ```

5. **T1: the gateway.** Wait for `fake gateway listening`.

   ```bash
   ./bin/fake-gateway --interactive --scenario bin/cfgview-lease.yaml --listen 127.0.0.1:8443 \
     --enroll-token demo-token --ca-out bin/ca.pem
   ```

6. **T2: the agent, from an empty data dir.** A new gateway forgets every agent (fact 3). Config view needs no `--roles`
   flag: it is on whenever the agent has an adapter.

   ```bash
   export DOCKER_HOST=unix://$HOME/.docker/desktop/docker.sock
   rm -rf bin/agent-data
   ./bin/sm-agent --data-dir bin/agent-data --connect-endpoint 127.0.0.1:8443 \
     --enrollment-token demo-token --trust-bundle bin/ca.pem
   ```

   T1 logs `fakegw: agent connected`.

7. **T1: type, in this order**, changing the file in T3 first where the first column says so:

   | Change the file (T3) | Type (T1) | Prints |
   |---|---|---|
   | | `view cfgview-1 server.port,management.server.port,management.endpoints.*,info.*` | `OK hidden=2` and five entries (the API key and the password are counted, not shown) |
   | | `view cfgview-1 management.*` | `OK` and the Datadog `api-key` **is shown**: the documented limit for a listed prefix |
   | `sed -i 's/port: 8080/port: 9090/' ~/sm-cfgview-demo/application.yml` | `view cfgview-1 server.port` | `OK hidden=6` and `server.port = 9090`: the edit shows with no restart, and `hidden` counts the five values not asked for |
   | `echo 'server: [' > ~/sm-cfgview-demo/application.yml` | `view cfgview-1 server.port` | `REFUSED INVALID_YAML` |
   | `ln -sf /etc/passwd ~/sm-cfgview-demo/application.yml` | `view cfgview-1 server.port` | `REFUSED NOT_PERMITTED` |
   | `rm ~/sm-cfgview-demo/application.yml` | `view cfgview-1 server.port` | `REFUSED FILE_MISSING` |
   | | `view nope server.port` | `REFUSED PLACEMENT_NOT_IN_LEASE` |

8. **T3: restore the file.**

   ```bash
   cp ~/sm-cfgview-demo-application.yml.bak ~/sm-cfgview-demo/application.yml
   ```

9. **Stop and clean up.** `Ctrl-C` T2, then T1. Then in T3:

   ```bash
   docker rm -f cfgview-demo
   rm -rf ~/sm-cfgview-demo ~/sm-cfgview-demo-application.yml.bak bin/cfgview-lease.yaml
   ```

The fake gateway's transcript (printed on exit) records only message names, never the reply content; read the `view` output.

### Logs and config view on one gateway

**Status: executed 2026-10-27** on ports `18443`/`19464`, with a stand-in binding `cfgview-verify` for `cfgview-1`:
`logs logs-1 3`, `view cfgview-verify server.port` and `preflight @all` all answered in one session. That run used the
earlier `logs-demo`, which wrote container output. It has **not been re-run** since the logs role was rebuilt on `/actuator/logfile` (the
steps below are edited to match [Logs](#logs), which was run on 2026-10-29 on its own).

To show both without restarting anything between them, grant both bindings in one lease and give the agent the logs role.

1. **T3:** [Logs](#logs) steps 1 and 2 (the `logs-demo` and `logs-nolog` containers), then [Config view](#config-view) steps 2
   and 3 (the `cfgview-demo` container and its file).
2. **T3: one lease for both.**

   ```bash
   cat > bin/both-lease.yaml <<'EOF'
   lease: {target_id: target-1, binding_ids: [logs-1, nolog-1, cfgview-1], capabilities: [apply-config, lifecycle], duration: 1h}
   EOF
   ```

3. **T1: the gateway.**

   ```bash
   ./bin/fake-gateway --interactive --scenario bin/both-lease.yaml --listen 127.0.0.1:8443 \
     --enroll-token demo-token --ca-out bin/ca.pem
   ```

4. **T2: the agent, from an empty data dir, with the logs role.**

   ```bash
   export DOCKER_HOST=unix://$HOME/.docker/desktop/docker.sock
   rm -rf bin/agent-data
   ./bin/sm-agent --data-dir bin/agent-data --connect-endpoint 127.0.0.1:8443 \
     --enrollment-token demo-token --trust-bundle bin/ca.pem --roles control,logs
   ```

5. **T1:** type either table: Logs step 6, Config view step 7. With two bindings in the lease, `preflight`, `apply`,
   `restart` and `stop` need a target (`@logs-1`, `@cfgview-1` or `@all`); `logs` and `view` already name one.
6. **Clean up.** `Ctrl-C` T2, then T1. Then in T3:

   ```bash
   docker rm -f logs-demo logs-nolog cfgview-demo
   rm -rf ~/sm-logs-demo ~/sm-cfgview-demo ~/sm-cfgview-demo-application.yml.bak bin/both-lease.yaml
   ```

## Rollback

- `preflight` — nothing to undo.
- `apply` — restore the backup you took: `cp ~/sm-conf-application.yml.bak ~/sm-conf/application.yml`. Without
  one, the previous content is gone.
- `restart` — nothing to undo; the container is running, as before.
- `stop` — type `restart`.

## Scripted runs

`--scenario` still works, with or without `--interactive`: each field a scenario file sets overrides the built-in
scenario's, the rest keep their defaults, and its steps run as before. [`internal/fakegw/scenarios/demo.yaml`](internal/fakegw/scenarios/demo.yaml)
walks `preflight → apply → restart` with no typing. Typed commands take the next generation after any a scenario
used.

## What is still awkward

**Restarting the gateway costs the agent its identity**, because the gateway keeps its CA and enrolment table in
memory. Keep the gateway running and restart only the agent where you can. Not filed; it is test-double
ergonomics, not an agent defect.
