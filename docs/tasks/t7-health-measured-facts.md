# Plan 010 S7 - measured facts (M3, M8, M12, M19) and the OR-89 residual

Written by T7.1, extended by T7.2. The plan keeps these in its "Measured facts" table; plan 010 is not edited by
the lanes, so the integrator folds this file's rows into that table. `I2`: every fact below names where it came from.

## M8 - sshj 0.40.0 (needed by T6.1, T7.1) - filled 2026-10-05

| Question | Answer | Source |
|---|---|---|
| Several `Session`s on one connected `SSHClient`? | Yes. `ConnectionImpl` keeps channels in `private final Map<Integer, Channel> channels = new ConcurrentHashMap<>()`; no channel-count limit in the code (only per-channel window and packet size). The server may still cap them (OpenSSH `MaxSessions`, default 10), hence `sm.dp.ssh.pool.max-probes-per-session` (default 4). | WebFetch `https://raw.githubusercontent.com/hierynomus/sshj/v0.40.0/src/main/java/net/schmizz/sshj/connection/ConnectionImpl.java` |
| Keep-alive | `Config.setKeepAliveProvider(KeepAliveProvider.KEEP_ALIVE)` before connect; `SSHClient.getConnection().getKeepAlive().setKeepAliveInterval(int seconds)` (`KeepAlive` is a `Thread`; the interval defaults to off). On an exception while sending, the thread calls `conn.getTransport().die(e)` unless it was interrupted; the client then reports `isConnected() == false`. | WebFetch `.../net/schmizz/keepalive/KeepAlive.java` at `v0.40.0`; `javap` of `~/.m2/.../sshj-0.40.0.jar` (`KeepAliveProvider.HEARTBEAT` and `KEEP_ALIVE`, `KeepAlive.setKeepAliveInterval(int)`, `Config.setKeepAliveProvider`) |
| Restrict the host-key algorithm | `Config.setKeyAlgorithms(List<Factory.Named<KeyAlgorithm>>)`; factories in `com.hierynomus.sshj.key.KeyAlgorithms` (`EdDSA25519`, `ECDSASHANistp256/384/521`, `RSASHA512`, `RSASHA256`, `SSHRSA`, ...). Used by `SshHostKeyScanner`. | `javap` of the jar |
| Fingerprint spelling | `FingerprintVerifier.getInstance("SHA256:<base64, unpadded>")`; `SshHostKeyScanner.fingerprintOf` (SHA-256 of `Buffer.PlainBuffer.putPublicKey(key).getCompactData()`) is accepted by it for RSA and ECDSA keys (`SshHostKeyScannerTest`). | executed test |

Still from T6.1 and not re-measured here: `loadKeys(String, String, PasswordFinder)` for the in-memory key, and
`SFTPClient.canonicalize`.

## M12 - where the management port is reachable from the host - LOCAL SOURCES ONLY; the live-host part is pending for W9

Measured from the Cache fixture's own sources (`/Users/parikshit/Work/Development/solace-cache-engine`, read 2026-10-05):

* `pom.xml:17` - `<spring.boot.version>3.5.16</spring.boot.version>`. **Cache's Boot version is 3.5.16, not the 3.3.5 that
  `CLAUDE.md` says.** The M2/M3 pages below were therefore read at `3.5`.
* `src/main/resources/application.yml` - `server.port: 8081`; `management.endpoints.web.exposure.include: health,info,metrics`;
  no `management.server.port`, so the actuator is served on the **application port 8081** under `/actuator`.
* `Dockerfile` - no `EXPOSE`; nothing publishes a port. Whether `8081` is reachable on the **host's loopback** is decided by the
  customer's launch spec (`-p 127.0.0.1:<host>:8081` or `--network host`), which SM does not own (`I1`).

**Consequence implemented:** the via-ssh probe is `curl http://127.0.0.1:<health_port><health_path>` on the host. If the
launch spec does not publish the container port on the host loopback, curl exits 7 (refused) and the placement reads
`not_exposed` - which is exactly the "configuration finding" state `STATE-VOCABULARY.md` defines. There is no
`docker exec` fallback (the image may have no `curl`; `I1`).

**Pending for W9 (needs the E2E host, which this lane must not contact):** `docker inspect <container> --format
'{{json .HostConfig.PortBindings}}'` on the demo target, and `command -v curl`
on the host.

## M3 - Spring Boot actuator health at 3.5 - filled 2026-10-05

WebFetch `https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html` and
`.../api/rest/actuator/health.html`:

* `status` is one of `UP`, `DOWN`, `OUT_OF_SERVICE`, `UNKNOWN`.
* Default HTTP mapping: `DOWN` -> `503`, `OUT_OF_SERVICE` -> `503`, `UP` -> no mapping (`200`), `UNKNOWN` -> no mapping (`200`).
* `management.endpoint.health.show-details` defaults to `never` (the body is `{"status":"UP"}` and nothing else).

The mapping built on it is under "T7.2 mapping" below.

## M19 - curl exit codes

Already measured by T6.1 (`SshjDockerTransportProbeTest`, curl 8.7.1): without `--fail` an HTTP error is an answer (exit 0, status
after the body); connection refused exits 7 and a timeout 28, both with status `000`.

## OR-89's residual, closed by T7.1

`OR-89` made the uniqueness of a live target's PINNED host key per organization and carried one hole to T7.1: a host that
offers several key types has one fingerprint per type, so the same machine could be registered twice in one organization by
pinning two different types.

**Fix (a registration-command change, flagged for the owner):**

* `sm_data` V012 section 1: `target_host_key(tenant_id, target_id, fingerprint, retired_at)` with
  `UNIQUE (tenant_id, fingerprint) WHERE retired_at IS NULL`; the pinned fingerprint is mirrored in by triggers on
  `target_connection` (insert and re-pin), and retiring a target retires its keys.
* `TargetConnectionMain` now records **every** key type the host presents: it asks the host (`SshHostKeyScanner`: one
  connection per key algorithm, aborted at the host-key exchange, no credential involved), or takes the operator's explicit
  `SM_TARGET_HOST_KEY_FINGERPRINTS` (comma separated, must include the pinned one; the host is then not contacted). The pinned
  fingerprint must be one of them. **A host that cannot be asked and has no list is refused** - registering without knowing the
  other keys would reopen the gap. A re-run of an identical registration still records a key type it has newly learned.
* `TargetConnectionSpec` gained `otherHostKeyFingerprints` (the 6-argument constructor remains, with none).
* Accepted residual: a key type the host does not currently offer (added later) is unknown until the registration is re-run.

## T7.2 - the mapping, and what was measured for it

`ProbeResultMapper` (pure; `ProbeResultMapperTest`):

| Observation | State |
|---|---|
| connection refused on the declared port (curl exit 7 on the host loopback; a refused TCP connect for direct) | `not_exposed` |
| any SSH-level failure (connect, pin, credential, a connection lost twice); direct: name does not resolve, connect times out, no route | `unreachable` (OR-39: UNKNOWN, not DOWN) |
| reachable and accepting, but no usable answer in time (curl 28, 52, 56; a stalled listener; an oversized body) | `RED` |
| answered 404 / 401 / 403 | `not_exposed` |
| `actuator-health`: 2xx and body `status` is `UP` | `GREEN` |
| `actuator-health`: anything else answered (503 DOWN / OUT_OF_SERVICE, 200 with DOWN or UNKNOWN, no JSON status, other 4xx/5xx/3xx) | `RED` (the BODY decides; a 200 with DOWN is red, a 503 with UP is red) |
| `http-status`: 2xx / anything else answered | `GREEN` / `RED` |

**Flagged for the owner (M3 does not settle them; chosen, not guessed):**

1. `UNKNOWN` (Boot maps it to HTTP 200) is read as not green: `RED`.
2. `401` / `403` / `404` on the health path read `not_exposed`, from `STATE-VOCABULARY.md` ("reachable, but the health endpoint is absent or
   unexposed - a configuration finding").
3. A reachable service that does not answer in time is `RED` over SSH and over direct HTTP alike (the TCP connect proved the host is up).
4. `missing` and `bootstrap` are never produced: both need facts the poller does not read (absent beyond N intervals; first restart not yet
   observed). A freshly declared instance whose container is not up yet therefore reads `not_exposed` until it is.
5. The designer's `replicas` (`ReplicaHealth` has four values and the projection reads any other as GREEN) get `RED` for every
   no-answer state (`unreachable`, `not_exposed`, `missing`, `bootstrap`), so an unreachable placement can never read healthy there.
   `getHealth` keeps the full vocabulary.
6. `getHealth` lists only placements the poller has visited; the rest are counted in `availability` (`degraded`, `polling`, with the reason).

Measured, JDK 17 `HttpClient` (2026-10-05, `java M.java`): a refused port and an unresolvable name both throw
`ConnectException` with no message; only an unroutable address throws `HttpConnectTimeoutException`. So the direct probe resolves the name
and makes a plain socket connect itself (refused -> `not_exposed`, anything else -> `unreachable`), and only then sends the request.

Backoff: after the n-th consecutive `unreachable` for a (tenant, target) the target is not probed for `min(maxBackoff, interval * 2^n)`;
one failure counted per target per round.

Detection bound, stated: a dying service is seen within `interval + timeout` (default 30 s + 5 s), because the MI pushes nothing (`I1`).
