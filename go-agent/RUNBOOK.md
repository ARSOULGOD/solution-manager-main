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
   from its journal without acting. Commands always go to the lease's first binding — `binding-1` by default, which
   is `java1`'s label.
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

It prints `type: preflight | apply <file> | restart | stop` and `fake gateway listening`. No `--scenario` is needed:
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
| `preflight` | `preflight-1 gen=1 -> PREFLIGHT_OK` | nothing changed: `docker inspect -f '{{.State.Running}} {{.State.StartedAt}}' java1` is the same as before | 1 s |
| `apply <file>` | `apply-2 gen=2 -> APPLIED` | `cat ~/sm-conf/application.yml` shows the file's content; `stat -c %a ~/sm-conf/application.yml` is `600` | 1 s |
| `restart` | `restart-3 gen=3 -> APPLIED` | `docker inspect -f '{{.State.Running}} {{.State.StartedAt}}' java1` is `true` with a **newer** `StartedAt` | 11 s running · 1 s stopped |
| `stop` | `stop-4 gen=4 -> APPLIED` | `docker inspect -f '{{.State.Running}} {{.State.ExitCode}}' java1` is `false 137` | 11 s |

Each command first prints `<op>-<n> gen=<n>: offered`; the `->` line follows when the agent acks.

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
| `restart-1 gen=1 -> FAILED_NO_EFFECT: Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?` (a `preflight` says `PREFLIGHT_REFUSED` instead). *Observed.* | `DOCKER_HOST` is not exported in the agent's terminal. | `Ctrl-C` the agent, `export` it, start the agent again **without** the `rm -rf` — the gateway is still the same one. |
| `-> PREFLIGHT_REFUSED: docker: no container carries the binding label: com.solace.sm.binding-id=binding-1` (`FAILED_NO_EFFECT` for the other commands). *Observed.* | `java1` lacks the label. | Precondition 3. |
| The same, with `more than one container carries the binding label`. | Another container — a stopped one counts — carries `binding-1`. | Precondition 3's check must print only `java1`. |
| The same, with `declares no external config location` or `is not a bind mount`. | `java1` lacks the env var or the mount. | Recreate it with precondition 3's `docker run`. |
| `-> PREFLIGHT_REFUSED: config directory is not writable`. | `~/sm-conf` is mounted read-only. | Mount it read-write. |
| Every command prints `no agent connected`, and terminal 1 never logged `agent connected`. With the agent running, `curl -s localhost:9464/healthz` shows `"session_phase":"CLOSED"`. *Observed.* | The agent started from an earlier gateway's data dir; that gateway's CA and enrolment are gone (fact 3). | `Ctrl-C` the agent and redo step 2 — including the `rm -rf`. |
| `apply: open <file>: no such file or directory`. | The path is relative to `go-agent/`, where the gateway runs. | Give an absolute path. |
| `<op>-<n> gen=<n>: no ack within 5m0s`. | The agent went away while the command was in flight. | Check terminal 2; restart the agent (no `rm -rf`) and type the command again. |
| The gateway exits with `bind: address already in use`. | An earlier `fake-gateway` or `sm-agent` is still running. | `pkill -f bin/fake-gateway; pkill -f bin/sm-agent`, then start again from step 1. |
| `"msg":"send failed","err":"EOF"` when you `Ctrl-C` the agent. *Observed, every run.* | The stream closes under it. | Nothing — expected. |

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
