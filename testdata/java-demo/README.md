# java-demo

One Java image, three pre-created containers (`java1`, `java2`, `java3`) for
**sm-agent** Docker lifecycle demos. The agent does **not** create containers.

## Build image

```bash
cd testdata/java-demo
docker build -t sm-java-demo:local .
```

## Create containers (stopped)

```bash
docker compose create
# or: docker compose up --no-start
docker compose ps -a
```

## Optional: stop all before Test 1

```bash
docker stop java1 java2 java3 2>/dev/null || true
```

## HTTP checks (when running)

```bash
curl -sS http://127.0.0.1:8081/hello   # Hello from java1
curl -sS http://127.0.0.1:8082/hello
curl -sS http://127.0.0.1:8083/hello
```

## Local telemetry collect demo

Example targets for **sm-agent collect** live in [`targets.yaml`](targets.yaml) (java1/2/3 →
`http://127.0.0.1:8081|8082|8083/hello`, matching `GET /hello` on each container).

This proof only needs HTTP endpoints up; the Docker lifecycle CLI (`sm-agent start|stop|…`) is a
separate demo and is **not required** here. Start the services however you prefer:

```bash
# Option A: docker compose
docker compose start

# Option B: sm-agent lifecycle (from go-agent/, after build)
./sm-agent start java1
./sm-agent start java2
./sm-agent start java3
```

Build **sm-agent** and run one collect cycle (from repo root or `go-agent/`):

```bash
cd go-agent
go build -o sm-agent ./cmd/sm-agent
./sm-agent collect --targets ../testdata/java-demo/targets.yaml --data-dir /tmp/sm-demo-data
```

Inspect segregated output (one directory per target id):

```bash
find /tmp/sm-demo-data/telemetry -type f | sort
cat /tmp/sm-demo-data/telemetry/java1/latest.json
cat /tmp/sm-demo-data/telemetry/java2/latest.json
cat /tmp/sm-demo-data/telemetry/java3/latest.json
```

Each `latest.json` should show `"ok": true`, a UTC `"scraped_at"`, and a body like
`Hello from java1`.

### Partial failure (break one service)

Stop one container, then re-run collect:

```bash
docker stop java2
# or: ./sm-agent stop java2

./sm-agent collect --targets ../testdata/java-demo/targets.yaml --data-dir /tmp/sm-demo-data
echo exit=$?   # non-zero when any target fails
```

- **java1** and **java3** — `telemetry/java1/latest.json` and `telemetry/java3/latest.json` refresh.
- **java2** — failure sample under `telemetry/_failures/java2-<timestamp>.json`; existing
  `telemetry/java2/latest.json` is **not** overwritten on failure.

Bring java2 back and collect again to restore a successful `latest.json`:

```bash
docker start java2
./sm-agent collect --targets ../testdata/java-demo/targets.yaml --data-dir /tmp/sm-demo-data
```

Full manager demo commands: [`../../go-agent/README.md`](../../go-agent/README.md).

## Cleanup

```bash
docker compose down --remove-orphans
```
