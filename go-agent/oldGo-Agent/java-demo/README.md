# java-demo

One Java image, three pre-created containers (`java1`, `java2`, `java3`) for
**sm-agent** Docker lifecycle demos. The agent does **not** create containers.

## Required setup steps

1. Export the Docker Desktop socket.
2. Build the Go agent binary.
3. Build the Java demo image and create the demo containers once.

```bash
export DOCKER_HOST=unix:///home/arnav-rinawa/.docker/desktop/docker.sock

cd /home/arnav-rinawa/Documents/projects/solaceSolutionManager/solution-manager-main/go-agent
go build -o sm-agent ./cmd/sm-agent

cd /home/arnav-rinawa/Documents/projects/solaceSolutionManager/solution-manager-main/testdata/java-demo
docker build -t sm-java-demo:local .
docker compose up -d --force-recreate
docker compose ps -a
```

If you need to reset the services before a new test run:

```bash
docker stop java1 java2 java3 2>/dev/null || true
docker compose up -d --force-recreate
```

## HTTP checks (when running)

```bash
curl -sS http://127.0.0.1:8081/hello  
curl -sS http://127.0.0.1:8082/hello
curl -sS http://127.0.0.1:8083/hello
```

## Local telemetry collect demo

Example targets for **sm-agent collect** live in [`targets.yaml`](targets.yaml) (java1/2/3 →
`http://127.0.0.1:8081|8082|8083/metrics`). Each Java service returns JSON containing uptime,
request count, active threads, heap usage, and available processors. The `/hello` endpoint remains
available for a simple lifecycle check.

### Use go-agent only for lifecycle + telemetry

After bootstrap, keep the test loop entirely in the Go agent.

> Important: not all services must be running at the same time. If a target is down, `sm-agent` writes a failure record under `telemetry/_failures/<id>-<timestamp>.json` and still continues scraping the remaining targets. The overall command exits non-zero whenever any configured target fails.

```bash
export DOCKER_HOST=unix:///home/arnav-rinawa/.docker/desktop/docker.sock

cd /home/arnav-rinawa/Documents/projects/solaceSolutionManager/solution-manager-main/go-agent
./sm-agent status java1
./sm-agent start java2
./sm-agent status java2
./sm-agent collect \
  --targets ../testdata/java-demo/targets.yaml \
  --data-dir /tmp/sm-java-telemetry
```

Inspect the persisted telemetry files:

```bash
find /tmp/sm-java-telemetry/telemetry -type f | sort
cat /tmp/sm-java-telemetry/telemetry/java1/latest.json
cat /tmp/sm-java-telemetry/telemetry/java2/latest.json
cat /tmp/sm-java-telemetry/telemetry/java3/latest.json
```

Each `latest.json` should show `"ok": true`, a UTC `"scraped_at"`, and a JSON `body` containing
`"service":"java1"` plus JVM runtime measurements.

### Lifecycle test sequence through go-agent only

```bash
cd /home/arnav-rinawa/Documents/projects/solaceSolutionManager/solution-manager-main/go-agent
./sm-agent status java1
./sm-agent stop java2
./sm-agent status java2
./sm-agent start java2
./sm-agent status java2
./sm-agent collect \
  --targets ../testdata/java-demo/targets.yaml \
  --data-dir /tmp/sm-java-telemetry
```

### Direct Java endpoint check

This is optional and only for sanity checking the raw endpoint:

```bash
curl -sS http://127.0.0.1:8081/metrics
```

### Partial failure (break one service)

Stop one container, then re-run collect:

```bash
./sm-agent stop java2
./sm-agent collect --targets ../testdata/java-demo/targets.yaml --data-dir /tmp/sm-java-telemetry
echo exit=$?   # non-zero when any target fails
```

- **java1** and **java3** — `telemetry/java1/latest.json` and `telemetry/java3/latest.json` refresh.
- **java2** — failure sample under `telemetry/_failures/java2-<timestamp>.json`; existing
  `telemetry/java2/latest.json` is **not** overwritten on failure.

To collect only the service that is still healthy, point the agent at a subset file such as `java3-only.yaml`:

```bash
cat > /tmp/java3-only.yaml <<'EOF'
targets:
  - id: java3
    url: http://127.0.0.1:8083/metrics
EOF

./sm-agent collect --targets /tmp/java3-only.yaml --data-dir /tmp/sm-java-telemetry
```

Bring java2 back and collect again to restore a successful `latest.json`:

```bash
./sm-agent start java2
./sm-agent collect --targets ../testdata/java-demo/targets.yaml --data-dir /tmp/sm-java-telemetry
```

Full manager demo commands: [`../../go-agent/README.md`](../../go-agent/README.md).

## Cleanup

```bash
docker compose down --remove-orphans
```
