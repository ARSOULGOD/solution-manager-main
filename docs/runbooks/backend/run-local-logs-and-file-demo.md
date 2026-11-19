# Run the local logs and file-read demo

| | |
|---|---|
| **Applies to** | `backend/demo-launcher` + `frontend/` (local demo composition, not the production control plane) |
| **Severity** | routine |
| **Est. duration** | 15 min first run (image pulls and builds), 2 min after |
| **Reversible?** | yes — teardown removes every container and the demo Postgres volume. The volume holds the demo admin, uploaded package, vaulted key and audit rows; those are destroyed. |

> **Status: UNTESTED end to end.** The target, backend and frontend were each started and checked from the
> command line; the browser flow in step 6 has not been driven by anyone. Change this line once someone has.

## When to use this

You want to see, in a browser, the instance **log viewer** (`docker logs` over SSH) and the **config file
read-back** (SFTP) without a real Solace Cache host.

The target is a **stand-in** (`backend/demo-launcher/standin/`): an sshd container with the Docker CLI plus a small
Python container that prints log lines, reads `application.yml`, and answers the metrics URLs with random numbers.
It is not the Cache engine. The log-level endpoints in the launcher are stubs that always return `INFO`.

## Preconditions

- Docker with Compose v2, and permission to use `/var/run/docker.sock` as your user.
- Network access for the first run (`alpine`, `postgres:16-alpine`, `maven`, `node:22` images; Maven and npm packages).
- Free ports on loopback: `5173` (UI), `8080` (backend), `55433` (Postgres), `2222` (stand-in SSH).
- You do **not** need Java, Maven or Node on the host. The host's Node (18) is too old; the repo needs ≥22.12.
- Run every command from the repository root.

## Steps

### 1. Start the demo Postgres

```sh
docker compose -p sm-demo-control -f backend/demo-launcher/compose.yaml up -d
```

### 2. Start the stand-in target

```sh
cd backend/demo-launcher/standin
export DOCKER_GID=$(stat -c %g /var/run/docker.sock) DEMO_UID=$(id -u)
mkdir -p config/cache-1
printf 'cache-engine:\n  ttl-seconds: 300\n' > config/cache-1/application.yml
[ -f demo_key ] || ssh-keygen -q -t ed25519 -N '' -f demo_key
cp demo_key.pub authorized_keys && chmod 644 authorized_keys
docker compose up -d --build
cd ../../..
```

Get the host-key fingerprint you will see in the UI:

```sh
ssh-keyscan -p 2222 -t ed25519 127.0.0.1 2>/dev/null | ssh-keygen -lf -
```

Note: the host keys are generated when the image is built, so the fingerprint changes whenever the `sshd` image is
rebuilt without cache.

### 3. Build and start the backend

```sh
docker run --rm -u $(id -u):$(id -g) -e HOME=/tmp -v "$PWD":/work -v ~/.m2:/tmp/.m2 -w /work \
  maven:3.9-eclipse-temurin-17 \
  mvn -q -Dmaven.repo.local=/tmp/.m2/repository -f backend/pom.xml -pl demo-launcher -am package -DskipTests

mkdir -p /tmp/sm-launcher-home
docker run -d --name sm-demo-launcher --network host -u $(id -u):$(id -g) -e HOME=/home/demo \
  -v /tmp/sm-launcher-home:/home/demo \
  -v "$PWD/backend/demo-launcher/target/demo-launcher-0.1.0-SNAPSHOT.jar":/app.jar:ro \
  eclipse-temurin:17 java -jar /app.jar
```

`--network host` is what lets the backend reach Postgres on `127.0.0.1:55433` and the stand-in on `127.0.0.1:2222`.

### 4. Start the frontend (Node 22 in a container)

```sh
docker run --rm -u $(id -u):$(id -g) -e HOME=/tmp -v "$PWD/frontend":/app -w /app node:22 npm ci --no-audit --no-fund
docker run -d --name sm-demo-frontend --network host -u $(id -u):$(id -g) -e HOME=/tmp \
  -v "$PWD/frontend":/app -w /app node:22 npm run dev -- --host 127.0.0.1
```

Run `npm ci` in the Node 22 container, not on the host: an install under Node 18 skips a native binding and Vite
then fails with *"Cannot find native binding"*. `npm ci` deletes and recreates `frontend/node_modules`.

### 5. Check all four pieces are up

```sh
curl -s localhost:8080/api/cp/setup-status          # {"initialized":false} on a fresh database
curl -s -o /dev/null -w '%{http_code}\n' localhost:5173/   # 200
ssh -p 2222 -i backend/demo-launcher/standin/demo_key -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null demo@127.0.0.1 'docker logs --tail 3 sm-standin-cache'
```

### 6. Drive the browser

Open <http://127.0.0.1:5173>.

| Screen | Enter |
|---|---|
| Setup | Create the bootstrap admin. **Remember the password** — it wraps the SSH-key vault and there is no recovery. |
| Load package | Upload `contracts/validation/fixtures/cache-2.4.package.json`, validate, confirm. |
| SSH key | Upload `backend/demo-launcher/standin/demo_key` (private key, no passphrase). |
| Declare instance | Host `127.0.0.1`, port `2222`, user `demo`; probe the host key and check it matches step 2. |
| Container and paths | Container `sm-standin-cache`; remote config directory `/srv/config/cache-1`; container config path `/app/external/spring/config`; file `application.yml`. |

(Field labels are taken from the code, not from a click-through; if one differs, trust the screen.)

Then:

- **Logs** — open the instance's Governance log viewer. Expect a new INFO/WARN/DEBUG line about every 2 s; page back
  to older lines.
- **File read-back** — open the Designer / config ladder, change the Cache TTL, apply. Then restart as a separate
  operator action.

## Verification

- Logs: the lines in the viewer match `docker logs --timestamps --tail 20 sm-standin-cache`.
- File: after an apply, the file content on the target matches what the UI shows, and the stand-in prints a new
  `config sha=<n> bytes` line within 20 s:

  ```sh
  cat backend/demo-launcher/standin/config/cache-1/application.yml
  docker logs --tail 5 sm-standin-cache | grep config
  ```

## Rollback

Stop and remove everything. **This destroys** the demo Postgres volume (admin account, package, vaulted key, audit
rows) and the stand-in's config file.

```sh
docker rm -f sm-demo-frontend sm-demo-launcher
docker compose -p sm-demo-control -f backend/demo-launcher/compose.yaml down -v
(cd backend/demo-launcher/standin && docker compose down)
rm -rf /tmp/sm-launcher-home backend/demo-launcher/standin/config
```

To rerun only the first-run setup screen, drop the Postgres volume (`down -v`, then step 1) and restart
`sm-demo-launcher`.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Vite: `Cannot find native binding` | `node_modules` was installed with the host's Node 18. Redo step 4's `npm ci`. |
| `docker: permission denied` inside the stand-in | `DOCKER_GID` was unset or wrong when building. Re-export it and `docker compose up -d --build`. |
| SSH preflight fails on declare | Host key changed (stand-in sshd recreated) or `authorized_keys` doesn't match `demo_key`. Redo step 2. |
| Config write refused | The directory `standin/config/cache-1` must be owned by your uid (the stand-in `demo` user uses `DEMO_UID`). |
| Port already in use | Another process holds 5173/8080/55433/2222; stop it or the old containers above. |

## Escalation

This is a local demo; there is no on-call. Report problems against the demo-launcher plan's owner with the output of
`docker logs sm-demo-launcher`.
