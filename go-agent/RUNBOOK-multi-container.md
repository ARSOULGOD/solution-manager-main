# Runbook: one agent, two Docker containers

Run in `go-agent/`. You need Docker and Go. Use three terminals: **gateway**, **agent**, **shell**.

## 1. Check the machine is clean

```bash
pgrep -af 'bin/(sm-agent|fake-gateway)'                                       # prints nothing
docker ps -a --filter label=com.solace.sm.binding-id=multi-a --format '{{.Names}}'   # prints nothing
docker ps -a --filter label=com.solace.sm.binding-id=multi-b --format '{{.Names}}'   # prints nothing
```

Ports `8443` and `9464` must be free. A binding label must be on exactly one container, stopped ones included.

## 2. Build (shell)

```bash
make build
```

## 3. Start the two containers (shell)

Each container needs its own label, `SPRING_CONFIG_ADDITIONAL_LOCATION`, a bind-mounted config directory and a published port.

```bash
mkdir -p ~/sm-multi/a ~/sm-multi/b

docker run -d --name sm-multi-a --label com.solace.sm.binding-id=multi-a \
  -e SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/app/ -v ~/sm-multi/a:/etc/app \
  -p 127.0.0.1:18091:8080 busybox:1.36 \
  sh -c 'mkdir -p /www && echo "{\"hitRate\":0.91,\"missCount\":11}" > /www/stats.json && httpd -f -p 8080 -h /www'

docker run -d --name sm-multi-b --label com.solace.sm.binding-id=multi-b \
  -e SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/app/ -v ~/sm-multi/b:/etc/app \
  -p 127.0.0.1:18092:8080 busybox:1.36 \
  sh -c 'mkdir -p /www && echo "{\"hitRate\":0.92,\"missCount\":22}" > /www/stats.json && httpd -f -p 8080 -h /www'
```

## 4. Start the gateway (gateway terminal)

```bash
./bin/fake-gateway --interactive --scenario examples/two-containers.yaml \
  --listen 127.0.0.1:8443 --enroll-token demo-token --ca-out bin/ca.pem --telemetry-push 5s
```

It prints `fake gateway listening`. The scenario grants a lease for `multi-a` and `multi-b`.

## 5. Start the agent (agent terminal)

```bash
rm -rf bin/agent-data
./bin/sm-agent --data-dir bin/agent-data \
  --connect-endpoint 127.0.0.1:8443 \
  --enrollment-token demo-token \
  --trust-bundle bin/ca.pem \
  --roles control,telemetry --telemetry-config examples/telemetry-two-containers.yaml
```

The gateway prints `agent connected`. The agent may log `declared binding is not in the lease` once for each
binding before the lease arrives. Ignore it. Telemetry lines for both bindings then appear in the gateway terminal:

```
[hh:mm:ss] telemetry multi-a seq=1 cache.hit_rate=0.91 cache.miss_count=11 -> acked
[hh:mm:ss] telemetry multi-b seq=1 cache.hit_rate=0.92 -> acked
```

## 6. Send commands (gateway terminal)

A line with no prefix goes to the lease's first binding (`multi-a`). `@multi-b` addresses `multi-b`.

```
preflight
@multi-b preflight
apply /home/<you>/a.yml
@multi-b apply /home/<you>/b.yml
@multi-b restart
@multi-b stop
```

Create the two files first (shell): `printf 'server.port: 9001\n' > ~/a.yml` and `printf 'server.port: 9002\n' > ~/b.yml`.

Each command prints `offered`, then its outcome. Expected: `PREFLIGHT_OK` for the preflights, `APPLIED` for the rest.
Command ids for `multi-b` carry its name (`multi-b-apply-2`). Each binding counts its own generations.

## 7. Verify (shell)

```bash
cat ~/sm-multi/a/application.yml     # server.port: 9001
cat ~/sm-multi/b/application.yml     # server.port: 9002
docker ps -a --filter name=sm-multi --format '{{.Names}} {{.Status}}'
```

After `@multi-b restart`, `sm-multi-b` has a newer start time than `sm-multi-a`. After `@multi-b stop`, `sm-multi-b`
is `Exited` (the stop takes up to 10 s), `sm-multi-a` is still `Up`, and only `multi-a` telemetry keeps printing.

## 8. Clean up

Stop the agent and the gateway (Ctrl-C in each terminal), then:

```bash
docker rm -f sm-multi-a sm-multi-b
rm -rf ~/sm-multi ~/a.yml ~/b.yml bin/agent-data
```

## If it goes wrong

| Symptom | Cause | Fix |
|---|---|---|
| Gateway prints `binding "x" is not in the lease` | The `@name` is not in the scenario's `binding_ids`. | Use `multi-a` or `multi-b`. |
| `FAILED_NO_EFFECT` or `PREFLIGHT_REFUSED` with `no container carries the binding label` | The container is missing or lacks the label. | Redo step 3 for that container. |
| The same, with `more than one container carries the binding label` | Another container, a stopped one included, has the same label. | Remove or relabel it. |
| Agent logs `declared binding is not in the lease` and keeps logging it | `telemetry-two-containers.yaml` names a binding the scenario does not grant. | Use the files from this runbook. |
| Agent will not connect after you restarted the gateway | Each gateway start mints a new CA. | Rerun step 5 from `rm -rf bin/agent-data`. |
