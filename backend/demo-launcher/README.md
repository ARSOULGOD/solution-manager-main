# Local Cache Demo

This module is the narrow, local-only browser demo composition. It is not the
production control plane and it does not make Plan 005 complete.

## Start

From the repository root, start the isolated local PostgreSQL project:

```sh
docker compose -p sm-demo-control -f backend/demo-launcher/compose.yaml up -d
```

Build the current reactor artifacts, then run the launcher:

```sh
mvn -f backend/pom.xml -pl demo-launcher -am package -DskipTests
java -jar backend/demo-launcher/target/demo-launcher-0.1.0-SNAPSHOT.jar
```

In another terminal, start the browser application:

```sh
cd frontend
npm run dev
```

Open the Vite URL. The first screen creates the local Bootstrap Super Admin.
Its password wraps the local SSH-key vault. The browser then validates and
confirms a Cache package, uploads a private key into the encrypted vault, and
declares an SSH Docker instance. The SSH preflight must succeed before that
instance is persisted.

## Boundaries

- PostgreSQL is published only on loopback for this demo.
- Package, instance metadata, key ciphertext, and audit entries are persisted
  in `demo_control`; PEM bytes are never returned by the HTTP API.
- The UI accepts only target metadata and a key document. It cannot issue an
  arbitrary SSH or Docker command.
- The only editable runtime value is Cache TTL. Restart remains a separate,
  audited operation.
- Use the existing `colima` context. Do not switch contexts, prune Docker, or
  touch containers outside the `sm-demo-control` project.
