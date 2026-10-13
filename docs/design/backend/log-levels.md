# D-LOG: live log levels without a network-exposed write surface

> **Ruled 2026-10-06 (`OR-103`).** The owner ruled on §12: **Q-L1 (c)** — build option **Rd** now and leave **Ra** as the documented later step; **Q-L4 G2** — the package declares
> `logs.loggers`; **Q-L6 (a)** — `architecture.md` §10's "no separate port to bind" sentence is corrected. **Q-L2, Q-L3 and Q-L5 are not ruled**: they apply only to Ra and are asked
> again if Ra is scheduled. What is built (plan 010 T9.3, 2026-10-06) is therefore: the live **READ** over SSH from the placement's loopback (§3 option Rd, §7's closed command through
> `HostProbe`); **every level change is configuration** (T9.4); `setLogLevel` admits nothing in this release (`501`, and `422` for `mode: live` on a direct-HTTP instance as the contract
> states); the overlay (§4.1) is the single function `OverlayKeys`, adding `loggers` to the exposure list and `management.endpoint.loggers.access: read-only` for a package that declares
> logger support, with **no management port, no actuator move, no live write and no journaled live-set operation** (so §5 and `V013` are not built, and §6's override report is moot:
> `runtimeOverride` is `false`). Drafts **L-2** and **L-5** became `BE-D37` and `BE-D38`; the ruling's own decision is `BE-D36`. Drafts **L-1** (Ra's prerequisites), **L-3** (the
> journaled live set) and **L-4** (the derived override report) are not allocated: they describe Ra. Where this note says "recommended" or "proposed" for Ra, the ruling supersedes it.
> One refinement the build made to §7: the logger grammar excludes **`$`** (Java identifiers may contain it), because the probe command does not quote the URL and the host's shell would
> expand it. See [`decisions.md`](decisions.md) `BE-D36`, `BE-D37` and `BE-D38`.

**Status: PROPOSED, UNBASELINED.** Written 2026-10-05 by [plan 010](../../plans/backend/010-dp-data-model-and-remote-flow.md)
Task T9.2, on branch `p010/designs` off `integration/dp-remote-demo` @ `2ac941a`. It designs the answer to
[`OR-43`](../DECISIONS.md) (log levels: live first, config second) and decides nothing the owner has not ruled. The
owner decides on behalf of the architect group ([`OR-69`](../DECISIONS.md)). This note **edits no other file**:
the plan, `decisions.md`, `architecture.md` and the contract are the integrator's to change (§13).

**Decisions drafted here** carry the literal placeholder `«next free BE-D»` (§11). No id is reserved
([`OR-11`, `OR-17`](../DECISIONS.md)).

**Binding inputs, which this design implements and does not re-decide:**

| Ruling | What it fixes here |
|---|---|
| [`OR-43`](../DECISIONS.md) | P1: management endpoints bound to `127.0.0.1` by SM-written config and reached only via SSH; `/actuator/loggers` GET/SET as an audited durable engine operation; a live change is a temporary override shown as such. P2: `logging.level.*` as a config field through a normal apply. Direct-HTTP-only placements get P2 only. |
| [`OR-71`, `OR-82`](../DECISIONS.md) | Acquisition mode is chosen per instance and is instance-level state. In `direct-http` mode the DP polls the target's registered address at the placement's health port. Nothing about the address comes from the browser. |
| [`OR-34`](../DECISIONS.md) | The server re-projects a draft and refuses an undeclared key. This note keeps that rule intact (§4). |
| [`OR-53`](../DECISIONS.md) | Strategies and packages are generic. No branch on the solution name. |
| [`UI-D10`](../dynamic-ui/decisions.md) | The exposure list SM writes is *SM's required set ∪ the package's declared `baselineExposure`*; "read the existing value" was retracted as unimplementable. A component declares `loggerPrefix`. |
| [`BE-D21`, `BE-D22`, `BE-D25`](decisions.md) | The `RuntimeAdapter` seam and its contract kit. |
| `I1`, `I2`, `I5`, `I6`, `I7` | No MI development; facts are measured; tenant scope; no secret in config storage; plane boundary. |

**Flagged, not resolved:** (1) `D10`'s ratified amendment §1.4 names `apply_request` as the audit record for three
verbs only; `logs.setLevel` is already a catalog verb (`Permission.java:41`, `HIGH`), but making its audit record an
`apply_request` row is a fourth use (§5, Q-L3). (2) [`architecture.md`](../architecture.md) §10 says Cache has "no
separate port to bind" for its management endpoints. §2 measures that `management.server.port` is a Spring Boot
property SM can write, so the sentence is wrong as a statement about what SM may configure. It is right that **Cache
itself does not set one** and right that nothing here fixes Cache's existing unauthenticated business API (§1, S4).
That correction is the integrator's. (3) §3 measures that giving the management server its own port moves **every**
actuator endpoint, health included, off the application port. That costs more than OR-43 priced, and it is the
reason for owner questions Q-L1 and Q-L2.

**What this does not design:** `readLogs` (T9.1); the log *content* path; any change to Cache's own endpoints; any
authentication of the Cache's business API (`I1`). It does not close the residual in `architecture.md` §10 that a
collector reaching the actuator port also reaches Cache's mutating API. That residual is unchanged by every option
below.

---

## 1. The running scenarios

Every option below is run through these seven cases. They are real: S1 and S2 are the two launch shapes SM already
meets, S2 measured from the demo's own compose file.

| # | Scenario |
|---|---|
| **S1** | **Host-network Cache over SSH.** The customer's launch spec runs the Cache container with `--network host`. The instance's acquisition mode is `via-ssh`. The Cache's application listens on `8081` (its own classpath `application.yml:1-2`). |
| **S2** | **The compose demo.** [`backend/demo/docker-compose.yml:86`](../../../backend/demo/docker-compose.yml) publishes `"58081:8081"` on the default compose (bridge) network. The container has its own network namespace. |
| **S3** | **A direct-HTTP placement.** The instance's mode is `direct-http` (`OR-71`). The DP polls the target's registered address at the placement's health port (`OR-82`). |
| **S4** | **A co-tenant container.** Another container on the same Docker network, or any process on the same host, tries `POST /actuator/loggers/ROOT` with `{"configuredLevel":"TRACE"}` against the Cache. (A flood of trace lines is a denial of service and fills the host's log volume.) |
| **S5** | **Override, then restart.** At 10:00 an operator sets `com.solace.solutions.cache` to `DEBUG` live. At 10:20 an unrelated apply restarts the container. At 10:25 the operator opens the log-level panel. What does it say? |
| **S6** | **A package that declares nothing about logging.** A solution whose package has no `loggerPrefix` and no logs declaration. |
| **S7** | **An apply is running.** A `rolling-health-gated` apply is `RUNNING` on the placement when the `setLogLevel` call arrives. |

**The status quo (measured).** Nothing is served. `getLogLevel` and `setLogLevel` are contracted
([`console.yaml:1356-1420`](../../../contracts/openapi/console.yaml)) and unbuilt. Cache's classpath `application.yml`
exposes `health,info,metrics` only (`:10`), has no `logging:` (`:47` is a comment), and sets no
`management.server.*`. The package's `logs` block declares `logfile`, `baselineExposure`, `requiresConfig`
(`cache-2.4.package.json`; schema `solution-package.schema.json:669-696`). The component carries `loggerPrefix:
com.solace.solutions.cache`. An operator who wants DEBUG today edits the file on the host and restarts.

**The perspective that bounds this whole note.** Cache's application port already carries an unauthenticated
*mutating* API (`POST`/`DELETE /api/cache*`) and Cache has no Spring Security (`architecture.md` §10, measured:
`grep -in security solace-cache-engine/pom.xml` returns nothing). So S4 is already possible against `/api/cache` on
8081 in S2's published shape. The aim here is narrower than "secure Cache": it is **not to add a second, more
powerful unauthenticated surface** (the loggers endpoint can silence logs or flood them), as `OR-43` requires.

---

## 2. Measured facts (M2, M12, M13 and the local probe)

`I2`: each row is a URL or command, the verbatim line that settles it, and the date.

### 2.1 M2: Spring Boot at Cache's own version (filled 2026-10-05)

Cache's version, measured: `solace-cache-engine/pom.xml:17` `<spring.boot.version>3.5.16</spring.boot.version>` (the
plan's "3.3.5" in `CLAUDE.md` is stale). The jar's own libraries confirm it: `spring-boot-actuator-3.5.16.jar`,
`logback-classic-1.5.34.jar`, `tomcat-embed-core-10.1.55.jar` (`unzip -l` of
`solace-cache-engine/target/solace-cache-engine-1.0.0-SNAPSHOT.jar`).

| Fact | Source and verbatim line (fetched 2026-10-05) |
|---|---|
| The `loggers` endpoint's URLs and write shape | `https://docs.spring.io/spring-boot/3.5/api/rest/actuator/loggers.html`: `GET /actuator/loggers`; `GET /actuator/loggers/{logger.name}`; `POST /actuator/loggers/{logger.name}`; request field `configuredLevel`, `String`, *"Level for the logger. May be omitted to clear the level."*; levels `[ "OFF", "FATAL", "ERROR", "WARN", "INFO", "DEBUG", "TRACE" ]`; *"To clear the level of a logger, make a `POST` request to `/actuator/loggers/{logger.name}` with a JSON body containing an empty object"*. |
| The write operation's signature at **3.5.16** | `https://raw.githubusercontent.com/spring-projects/spring-boot/v3.5.16/spring-boot-project/spring-boot-actuator/src/main/java/org/springframework/boot/actuate/logging/LoggersEndpoint.java`: `@Endpoint(id = "loggers")`; `public void configureLogLevel(@Selector String name, @Nullable LogLevel configuredLevel)`; an unknown logger name on read returns `null`. |
| Web exposure default | `https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html`: *"By default, only the health endpoint is exposed over HTTP and JMX."*; `management.endpoints.web.exposure.include` default `health`. |
| Securing the HTTP endpoints | same page: *"ensure that the exposed actuators do not contain sensitive information, are secured by placing them behind a firewall, or are secured by something like Spring Security."*; *"If Spring Security is on the classpath and no other SecurityFilterChain bean is present, all actuators other than `/health` are secured"*. Cache has no Spring Security, so the second sentence does not apply. |
| Access control per endpoint (3.5) | same page: *"By default, access to all endpoints except for `shutdown` and `heapdump` is unrestricted."* Property `management.endpoint.<id>.access`. The same page shows read-only access to `loggers` as its example. |
| A separate management server | `https://docs.spring.io/spring-boot/3.5/reference/actuator/monitoring.html`: *"You can customize the address on which the management endpoints are available by setting the `management.server.address` property. Doing so can be useful if you want to listen only on an internal or ops-facing network or to listen only for connections from `localhost`."*; *"You can listen on a different address only when the port differs from the main server port."*; example `management.server.port=8081` with `management.server.address=127.0.0.1`. |

### 2.2 M13: `logging.level.<logger>` at Cache's version (filled 2026-10-05)

`https://docs.spring.io/spring-boot/3.5/reference/features/logging.html`: *"All the supported logging systems can have
the logger levels set in the Spring Environment (for example, in `application.properties`) by using
`logging.level.<logger-name>=<level>` where `level` is one of TRACE, DEBUG, INFO, WARN, ERROR, FATAL, or OFF. The
`root` logger can be configured by using `logging.level.root`."* The page says nothing about changing levels at
runtime. It says *"Since relaxed binding always converts environment variables to lowercase, it is not possible to
configure logging for an individual class in this way"*, which is why P2 is a file entry, not an environment
variable (SM writes a file; it does not control the launch spec).

### 2.3 M12: where the management port is reachable from the host (partly filled 2026-10-05)

| Fact | Source |
|---|---|
| The demo's published-port shape | measured: `backend/demo/docker-compose.yml:86` `- "58081:8081"`, default compose network, no `network_mode`. |
| Host-network mode shares the host's stack and discards `-p` | `https://docs.docker.com/engine/network/drivers/host/`: *"If you use the `host` network mode for a container, that container's network stack isn't isolated from the Docker host"*; *"the container doesn't get its own IP-address allocated"*; *"Published ports are discarded when using host network mode"*; supported on *"Docker Engine on Linux"* and Docker Desktop 4.34 and later with the feature enabled. |
| Publishing to the host's loopback only | `https://docs.docker.com/engine/network/port-publishing/`: *"By default, when a container's ports are mapped without any specific host address, the Docker daemon publishes ports to all host addresses (`0.0.0.0` and `[::]`)."*; *"If you include the localhost IP address (`127.0.0.1`, or `::1`) with the publish flag, only the Docker host can access the published container port."* |
| Co-tenant reach on a user-defined bridge | `https://docs.docker.com/engine/network/drivers/bridge/`: *"Containers connected to the same user-defined bridge network effectively expose all ports to each other."* |
| A loopback bind **inside** a bridge container is unreachable through a published port | **Not stated by Docker's documentation** (the bridge page says so explicitly: "no statement about services bound to a container's loopback"). It follows from network namespaces and is what `OR-43`'s own question implies, but it is **not measured here**: running a container is outside this note's remit (docs only). ⚠️ **Open M12 sub-row, carried to T9.3's first step:** on the demo target, run a container whose management server binds `127.0.0.1` behind a `-p` mapping and show the host's `curl` is refused. `command -v curl` on the target is likewise unmeasured here; the existing transport already depends on it (`SshjDockerTransport.java:241-269`). |

### 2.4 The local probe: what Boot 3.5.16 does with these properties (measured 2026-10-05)

⚠️ **What this measures and what it does not.** The Cache **cannot start without a broker** (its own demo compose
records the constructor failure, `backend/demo/docker-compose.yml:29-45`), and starting a broker is out of scope. So
the probe is a 12-line `@SpringBootApplication` with one `@RestController` (`/api/ping` on the main port), compiled
against the **exact libraries inside the Cache jar** (spring-boot-actuator 3.5.16, logback 1.5.34, tomcat-embed 10.1.55,
JDK 17.0.17) and started on loopback ports 18081/18082. It proves what the **framework** does under these
properties. It does not prove the Cache starts with them; that is T9.3's `logLevelsAreReachableInTheProductionBoot`.
Reproduction: §14.

| # | Setup | Observed |
|---|---|---|
| P-1 | `server.port=18081`, `exposure.include=health,info,metrics` (the Cache's shape) | `GET :18081/actuator/loggers` is `404`. |
| P-2 | the same, plus `loggers` in `include`, same port, no authentication | `POST {"configuredLevel":"DEBUG"}` from `curl` is **`204`**; the next `GET` shows `"configuredLevel":"DEBUG","effectiveLevel":"DEBUG"`. **The unauthenticated write surface is real.** |
| P-3 | P-2, invalid level `LOUD` | `400`. Lower-case `debug` is accepted (`204`). Content type `text/plain` is `415`. Body `{}` clears: the next `GET` shows only `"effectiveLevel":"INFO"`. |
| P-4 | P-2, names | **`POST` accepts any name.** `ROOT`, `a.b.c` and `org.springframework.web;rm` all answered `204`, and `GET a.b.c` then returned a configured level. A `GET` on a name never set is `404`. So the DP must validate the logger name itself. Boot will not. |
| P-5 | `management.server.port=18082`, `management.server.address=127.0.0.1` | Listeners: `*:18081` and `127.0.0.1:18082` (`lsof`). `GET :18082/actuator/loggers…` works (`204` on `POST`). **`GET :18081/actuator/health` is `404`, and `GET :18082/api/ping` is `404`.** The management port carries **all** actuator endpoints (health, metrics, loggers) and **none** of the application's. |
| P-6 | `management.server.address=127.0.0.1` **without** a different port | **The application fails to start**: `IllegalStateException: Management-specific server address cannot be configured as the management server is not listening on a separate port`. |
| P-7 | `management.endpoint.loggers.access=read-only` (and `read_only`), `loggers` exposed on the **main** port | `GET` is `200`; `POST` is **`405`**. Loggers can be exposed read-only with no write surface at all. |
| P-8 | a file `logging.level.com.solace.solutions.cache: debug` under `--spring.config.additional-location`, then a live `POST ERROR`, then a restart | Started at `DEBUG` (the debug line is logged). After the live `POST`, `ERROR`. **After the restart, `DEBUG` again.** A live override does not survive a restart, and the file level wins. |
| P-9 | P-8 then a live clear (`{}`) | The effective level becomes the inherited `INFO`, **not** the file's `DEBUG`. A live clear is not "restore the configured level". |

---

## 3. Point 1: reachability. Can OR-43's answer hold under Docker?

**It holds, in one launch shape, at a price OR-43 did not state.** The question is where the management server
listens, and who can reach that address.

**The price (P-5, P-6).** To bind the loggers endpoint to `127.0.0.1`, the management server needs its own port. That
moves *health, info and metrics too*. On a placement given this overlay:

- the package's declared probe (`actuator-health` on `8081`) is no longer served there. The health poller, the
  gate of `rolling-health-gated` (T6.9) and the collector's actuator metrics must use the management port;
- the transport's hard-coded `curl http://127.0.0.1:8081/actuator/metrics/…` (`SshjDockerTransport.java:251-269`)
  would return `404`. The `/api/cache/stats` and `/api/cache/size` calls are application endpoints and are unaffected;
- a `direct-http` instance (S3) polls the target's registered address. A loopback-bound management server is
  **unreachable** to it, and its health would go dark. So the overlay may be written **only** for a `via-ssh`
  instance. The mode is immutable in v1, so this is a stable rule.

### 3.1 The options, with the concrete delta from the status quo

| | **R0: status quo** | **Ra: OR-43 as ruled** | **Rb: Ra's fallback** | **Rc: struck (M11)** | **Rd: loggers read-only on the app port** |
|---|---|---|---|---|---|
| **What changes** | nothing | The customer's launch spec uses the host's network namespace (a stated prerequisite SM *checks*, never creates). SM writes `management.server.port: <M>`, `management.server.address: 127.0.0.1`, and adds `loggers` to `management.endpoints.web.exposure.include`. SM reads and sets levels with a fixed `curl` over SSH to `127.0.0.1:<M>`. | Where Ra's prerequisite is absent, the placement gets **P2 only** and `getLogLevel` reports the configured level. | The earlier draft: bind the management server to the container's *network* interface and publish it to the host's loopback. | SM adds `loggers` to `include` and `management.endpoint.loggers.access: read-only`. No separate port, no loopback bind. `GET` is live over SSH to `127.0.0.1:8081`. A level change is P2 only. |
| **Files SM writes** | none | the overlay above (§4) | none (P2 entries only) | the overlay above, with a non-loopback bind | `include` + `access` |
| **Customer prerequisite** | none | host network namespace; a free host port `<M>` | none | `-p 127.0.0.1:<M>:<M>` | none |
| **What it moves** | nothing | **all actuator endpoints leave 8081** (P-5) | nothing | the same | nothing |
| **Write surface added** | none | one, on the host's loopback | none | one, on the container network | **none** (P-7) |

### 3.2 The scenarios through each option

| | **R0** | **Ra** | **Rb** | **Rc** | **Rd** |
|---|---|---|---|---|---|
| **S1** host-network, SSH | no log level; edit the file and restart | `SET` and `GET` live. Health, metrics move to `<M>`. | not applicable (Ra applies) | not applicable (`-p` is discarded under host mode, §2.3) | live `GET`; `SET` through P2 and a restart |
| **S2** compose demo (bridge) | as S1 | **cannot work**: the container's loopback is not the host's (§2.3, unmeasured sub-row). Falls to Rb. | **P2 only.** `GET` shows the configured level, `availability` says why. | works, **but** every container on the compose network reaches `<M>` and can `POST` (the bridge page, §2.3). | live `GET` through `127.0.0.1:58081` (the published port, host side); `SET` through P2 |
| **S3** direct-HTTP | as S1 | the overlay is **not** written (it would hide health). P2 only. | **P2 only** | the overlay would hide health; not applicable | `GET` would need the endpoint to be exposed to the DP's address: that is a new network exposure of a logger listing. **Not recommended**; P2 only |
| **S4** co-tenant `POST ROOT TRACE` | not possible (endpoint not exposed) | **Refused on the network.** Reachable only from the host's own network namespace, which includes **other host-network containers and local host users** (a residual this note states). | not possible | **possible** from any container on the network | **`405`**: no write surface |
| **S5** override then restart | n/a | see §6 | n/a | see §6 | no live override can exist, so nothing to show |
| **S6** package declares nothing | no change | `getLogLevel` and `setLogLevel` say `unavailable` | same | same | same |
| **S7** apply `RUNNING` | n/a | see §5 | n/a | see §5 | `setLogLevel` is P2 and is itself an apply: the scope fence answers `SCOPE_BUSY` |

**Reading the matrix.** Ra is OR-43's answer and it holds for S1 only, at the price of moving the actuator. Rb is the
fallback for everything else. Rc is struck and stays struck: it is a network exposure that `OR-43` and Q11 forbid. Rd
is **new**: the measurements make a no-write-surface design possible that the plan did not consider. It gives live
*reads* everywhere SSH reaches, with **no** added write surface and **no** movement of the actuator. It does not give
the live *set* that `OR-43` P1 rules. Which of Ra and Rd serves the owner is Q-L1.

### 3.3 What Ra obliges (the chain of prerequisites)

Ra is not one switch. P1 is available for a placement only if **all five** hold, and SM must **probe** them, not
assume them (SM cannot read the effective configuration, `SM-D22`; a launch-spec environment variable can outrank
the file):

1. the instance's acquisition mode is `via-ssh` (S3 gets P2);
2. the package declares that its MI speaks the Boot loggers endpoint (§8);
3. the container shares the host's network namespace (the customer's prerequisite);
4. SM holds a management port `<M>` for the placement (§4.2), free on the host;
5. a live `GET /actuator/loggers/<prefix>` over SSH returns `200` (this proves 2, 3 and 4 together, and that the
   file was applied and the process restarted since).

A `404` means the endpoint is not exposed. Connection refused (`curl` exit 7, M19) means the bind or port is wrong.
Either one degrades the placement to P2 with the reason in `availability`, and is never an error to the operator.

---

## 4. Point 2: the SM-owned overlay

Record, and flag for the owner, this reading: **the overlay is a set of keys SM adds at render. They are not package
fields.** `OR-34`'s "refuse an undeclared key" is a rule about keys the *client* supplies, and it is untouched,
because the client never supplies an overlay key. The precedent is already in the design:
`architecture.md` §7.1 has SM write `management.info.env.enabled` and `management.endpoints.web.exposure.include`
(merged with the declared baseline) for correlation.

### 4.1 The overlay set, as one function

`OverlayKeys.forPlacement(package, placement, generation)` returns the exact set of `(path, value)` pairs SM adds. It
is **one function with two callers**: the renderer (T6.2, T9.4) and D-ACCEPT's read-back, which strips these keys
(`docs/design/backend/accept-runtime.md`, T10.3's note, §4.3). Two lists would drift, and a drift here would make accept treat SM's
own keys as operator edits, or the reverse.

| Key | Written when | Value |
|---|---|---|
| `management.endpoints.web.exposure.include` | the package declares logger support or `logfile` | the package's `logs.baselineExposure` ∪ SM's required names (`UI-D10`); for Ra, `loggers` is in SM's required set |
| `management.server.port` | Ra holds for the placement | `<M>` (§4.2) |
| `management.server.address` | Ra holds for the placement | `127.0.0.1` |
| `management.endpoint.loggers.access` | Rd | `read-only` |
| `logging.level.<loggerPrefix>` | a P2 level is set | the level |
| any `requiresConfig` key (e.g. `logging.file.name`) | the package declares it | the package's value |

These keys sit **outside the component's `configPath`** (`cache-engine`): `renderedPathFor(configPath, key)`
(`contracts/view-models/src/project.ts:120`) prefixes the component path, and `management` and `logging` are
top-level Spring keys. The renderer writes the overlay as top-level entries beside the component sections.

### 4.2 Where `<M>` comes from

`placement` is immutable and its columns are fixed by the merged `V007` (never edited; the plan says so). A
management port must be remembered somewhere stable.

| Option | What it is | Delta from the status quo | Through S1 / S2 / S3 |
|---|---|---|---|
| **M-a: the operator supplies it** | a new immutable `placement.management_port` column and a declare-body field; unique per live target like `placement_health_port_once` | one migration (the integrator allocates the number), one contract field, one `422` rule (≠ health port, 1024–65535). The browser supplies an integer. It is loopback-only, so the value is not a network input. | S1: the operator names a free port. S2: the field is ignored (Rb). S3: ignored. |
| **M-b: the package declares it** | `logs.managementPort` in the package | a reviewed schema change; the same value for every instance on a host | S1: **two instances on one host collide** (host network namespace) and the second fails to bind. Not viable for host mode. |
| **M-c: SM derives it** | for example `health_port + 10000`, refused when above 65535 or equal to another live placement's health port | no migration, no field. An arbitrary convention SM invents, which the customer cannot see until it fails. | S1: works if free; collision with a customer's own use of that port is found only at start-up. |

**Recommendation: M-a**, because the operator is the only party who knows what is free on the host, and a port
collision otherwise surfaces as a container that fails to start during an unrelated apply. It is also the option that
adds the largest piece of work, which is part of Q-L2's weighing.

### 4.3 The deltas Ra adds to an apply, in full

For S1, the file SM writes gains (top level):

```yaml
management:
  server: { port: 18081, address: 127.0.0.1 }      # <M> from M-a
  endpoints: { web: { exposure: { include: "health,info,metrics,loggers" } } }
```

and `/actuator/*` is no longer on 8081 once the container restarts (P-5). `rolling-health-gated`'s gate for this
placement must read the management port: **the probe SM uses for a Ra placement is `<M>`, not the package's
`health.probe.port`**. The package field stays the *application-side* declaration, and the overlay is the DP's own
record of where it moved the actuator. T6.9/T7.2 must take the port from one helper, not from two places.

---

## 5. Point 3: the journaled operation

OR-43 says the live `SET` is "an audited durable engine operation". The plan recommends a new
`apply_request.operation = 'set-log-level'`. This section measures what that touches.

**What `apply_request` and the journal enforce today (measured in the merged migrations):**

- `V002__apply_request.sql:16` `op_known CHECK (operation IN ('apply','restart','stop'))`;
- `:46` `apply_has_generation`: `(operation = 'apply') = (generation IS NOT NULL)`: a `set-log-level` row (generation `NULL`) satisfies it unchanged;
- `:47` `lifecycle_has_strategy`: `(operation IN ('apply','restart')) = (strategy IS NOT NULL)`: a `set-log-level` row (strategy `NULL`) satisfies it **unchanged**. The plan lists this CHECK as touched; measured, it is not;
- `:49` `state_fits_operation`: the `ELSE` branch would admit states with no meaning for a log-level change (`CONFIG_DIVERGED`, `SUPERSEDED`); it needs its own `WHEN`;
- `resolution_is_evidence` is a `CASE operation` with `apply` and `restart` arms; a third operation needs an arm or must be unable to reach `APPLY_INDETERMINATE`;
- `V004__apply_step_intent.sql` `step_known` lists seven steps; a log-level `SET` has none of them that is truthful.

### 5.1 The options

| | **J1: a new `apply_request` operation** (the plan's recommendation) | **J2: its own small journal table** | **J3: synchronous, audit line only** |
|---|---|---|---|
| **What it is** | `operation = 'set-log-level'` plus `log_level_target jsonb`; a `RuntimeAdapter.setLogLevel` operation and its contract-kit test; one new step label | `sm_data.log_level_change(change_id, tenant, instance, placement, logger, level, state, requested_by, requested_at, observed_level, completed_at, state_version)`, written before the SSH call and updated after; stamped by the `OR-35` trigger | the request thread calls SSH and returns `200` with the verified level; the audit is a structured log line |
| **Migration surface** | `op_known`, `state_fits_operation`, `resolution_is_evidence`, `step_known`, `placement_scoped_steps`, the DDL-replay baseline and the frozen anchors; the executor (`ApplyExecutor`) and recovery learn a new operation; `console.yaml`'s `ApplyObservation.operation` closed set gains a member | one new table and its grants. The engine is untouched. | none |
| **Durable before the call** | yes (`202` before any adapter call) | yes (insert `REQUESTED`, commit, then call) | **no** |
| **Audit record** | `apply_request` (`D10` amendment §1.4 names it for three verbs; this would be a fourth) | the table is the record; **a different audit surface** than the other mutating verbs | none durable. Contradicts "audited durable". |
| **Contract** | `setLogLevel` keeps `202 ApplyAccepted`; `/apply/observe` polls it | `ApplyAccepted` carries an apply id; this needs its own observe path or a widened `ApplyAccepted` | `200`, not `202` (a reviewed contract change) |

### 5.2 The scenarios through each

| | **J1** | **J2** | **J3** |
|---|---|---|---|
| **S7** apply `RUNNING` | admission's blocking check refuses with `SCOPE_BUSY` (`409`). Honest: during a rolling apply the container may be restarting and a live set would be lost. | not fenced by `apply_scope`. The `SET` can land on a container that restarts a moment later; the override report (§6) tells the truth afterwards. | as J2 |
| **Crash between the SSH call and the record** | the engine's resume rules apply: the journal says `RUNNING`; recovery re-reads the live level and lands `APPLIED` or `FAILED_NO_EFFECT`. Needs the new arm in the evidence CHECK or a rule that this operation resolves itself by re-reading. | the row stays `REQUESTED`. A lazy sweep marks it `unknown` after a bound. The same re-read applies. | nothing records it |
| **Double submit** | two requests; the second hits the scope fence while the first is `REQUESTED`/`RUNNING` | two rows, both execute, idempotent by value | two calls, idempotent by value |
| **"Who set DEBUG, when?"** | `apply_request.requested_by`/`requested_at`: the existing audit answer | the table's columns | the log retention decides |

### 5.3 Recommendation

**J1**, because it is the only option that is literally "an audited durable *engine* operation" and it keeps one audit
record for every mutating DP verb. It is also the largest option, and its size is the argument for deciding Q-L1
**first**. If the owner chooses Rd for v1 (live reads, level changes through P2), no journaled live operation exists
and none of §5 is built. J2 is the cheaper alternative that still satisfies "audited durable" if "engine operation"
may be read as "a durable DP record". That reading is the owner's (Q-L3).

The adapter operation, if J1 or J2 is chosen: `RuntimeAdapter.setLogLevel(TargetReplica, String logger, String level)`
and its read twin `readLogLevel(TargetReplica, String logger)`; the certified-adapter inventory
(`PlaneBoundaryTest.java:224-228`) is unchanged because the same two classes implement them. The transport's command
is a **closed template** with validated tokens (§7), never a caller-supplied string.

**The verified outcome.** The Boot reference does not state the `POST` success status; P-2 measured `204`. The DP
treats any `2xx` as "sent", then **reads back** `GET …/loggers/<name>` and lands `APPLIED` only if `configuredLevel`
equals the request (or, for a clear, is absent). `400` is a refusal (`FAILED_NO_EFFECT`). `404` means the endpoint
is not exposed (`FAILED_NO_EFFECT`, capability degraded). A send with an unreadable read-back is the indeterminate
case; a log-level set is idempotent, so it resolves by re-reading, not by an operator's evidence.

---

## 6. Point 4: "runtime override active"

A live set is lost when the process restarts (P-8). The report has to be true in S5.

**The rule.** For one `(placement, logger)`:

- `liveLevel` = the last successful live read, when one can be had;
- `configuredLevel` = the level in the overlay of the generation the placement's file was **last written from** (the `persisted` rung's generation, T6.5), else absent;
- `runtimeOverride` = `liveLevel` is present and differs from the level the file would give at start (`configuredLevel`, else the inherited level, which the endpoint reports as `effectiveLevel` for an unset logger);
- when a live read is not possible: `runtimeOverride` is `true` **iff** a successful live set is journaled for the logger **and no container start has been observed since it completed** (T7.2's observation, `State.StartedAt` per M18). The panel then says "inferred, not read". Err toward showing the override: a false "none" is the dangerous error.

**S5 through it.** At 10:25: a live read says `INFO` (the restart re-read the file) while the journal shows a set at
10:00 and a container start at 10:20. `runtimeOverride: false`, `liveLevel: INFO`, and the panel notes the
override ended at the 10:20 restart. A reader of the *old* design (journal only) would have shown an override that
no longer exists.

**What the UI is told.** `LogLevel` already carries `liveLevel`, `configuredLevel` and `runtimeOverride`
(`console.yaml:2578`); this needs no contract change. Two rules for the copy: an override says "lost at the next
restart"; a clear (P-9) says "back to the inherited level, not the configured one".

---

## 7. Point 5: `GET`, and the closed command

`getLogLevel` reads live over SSH (a bounded read), falling back to the configured level with `availability`
degraded. The fixed template, tokens validated before any SSH session opens:

```
curl --silent --show-error --max-time 5 --write-out '%{http_code}' \
     'http://127.0.0.1:<M>/actuator/loggers/<logger>'
```

and, for the set, the same with `--request POST --header 'Content-Type: application/json' --data '{"configuredLevel":"<LEVEL>"}'`.
The body is **built from an enum token**, never from caller text.

| Validated token | Rule (P-4 shows Boot will not do this) |
|---|---|
| `<logger>` | must equal a declared component's `loggerPrefix`, or that prefix followed by `.` and Java identifier segments (`[A-Za-z_$][A-Za-z0-9_$]*`), total length ≤ 255. `ROOT`, empty, anything else is refused **before** a session opens (`aLoggerNameOutsideTheGrammarIsRefusedBeforeAnySession`). |
| `<LEVEL>` | `TRACE, DEBUG, INFO, WARN, ERROR` (the contract's enum, `console.yaml:2578`), or the clear token. `FATAL` and `OFF` are not offered: they silence the log an operator needs to diagnose with. |
| `<M>` | the stored integer (§4.2), never request input |

Reading `curl`'s result: M19 (T6.1) settles the exit codes and `--write-out` behaviour. This note adds only that a
`404` from the endpoint is a **capability** answer (not exposed), not an error.

---

## 8. Generic, not Cache-coded: how a package declares it

`loggerPrefix` is already a component field (`solution-package.schema.json:143`) and `logs.baselineExposure` /
`requiresConfig` exist. **Nothing declares that the MI speaks the Boot loggers endpoint or the `logging.level`
property.** Inferring either from `loggerPrefix` would be a guess about the MI (a non-Boot MI could carry a prefix).
Options:

| | **G1: infer** from `loggerPrefix` | **G2: a new optional `logs.loggers` block** | **G3: none; Cache-coded** |
|---|---|---|---|
| **What it is** | any component with a prefix gets P1 and P2 | `logs.loggers: { path?: "/actuator/loggers", levelProperty?: "logging.level" }`; each member independently optional | a branch on the solution name |
| **Delta** | no schema change | one **additive, optional** schema member, reviewed (`contracts/` is single-writer); the Cache fixture gains the block | no schema change |
| **S6** | a package with no prefix: nothing | nothing: no block means neither mode | n/a |
| **A non-Boot MI with a prefix** | wrongly offered P1/P2; the probe (§3.3, item 5) catches P1, nothing catches P2 until a restart | declares nothing; correct | n/a |
| **OR-53** | holds | holds | **violated** |

**Recommendation: G2.** It is the only option that is both generic and honest. The block's shape above is a
draft; the reviewed schema change is routed (§13). Until it lands, T9.3/T9.4 have no declaration to read, and per the
plan a task whose precondition is absent stops.

---

## 9. P2: the level as configuration (what T9.4 inherits)

A P2 level is a **generation-resident overlay value**, not a draft field:

| Option | Where the level lives | Delta | Through S5 |
|---|---|---|---|
| **L-i (recommended)** | an overlay entry in the generation's `rendered_entries` (`logging.level.<prefix>: LEVEL`), flagged as overlay; `stage` and `copyForward` **carry overlay entries forward** | no schema change; the digest changes, so guard 1 (`OR-33`) sees a new generation; rollback copies it forward | the level is in history and in the keyed digest; a later Designer edit keeps it |
| **L-ii** | a declared package field with a `renderedPath` | `renderedPathFor` prefixes the component's `configPath`, so the path would be `cache-engine.logging…`, wrong. Needs a schema change. | n/a |
| **L-iii** | a per-instance side record outside config history, like `OR-96`'s layout | not "through a normal apply": it would not be in the hash or the ladder, against `OR-43` | the level silently differs from the file's hash |

L-i's hazard is the one the S5 review class finds: **a Designer `stageConfig` re-projects the draft and stores the
server's own `rendered`** (`OR-34`), which has no overlay entries. `stage` must therefore copy the latest
generation's overlay entries into the new one, or the next unrelated edit drops the level. T9.4's test list must
include `aDesignerEditKeepsTheConfiguredLogLevel`.

The level takes effect at the next restart (P-8). The ladder reports only what the journal proves (M4); the copy says
"takes effect when the service restarts".

---

## 10. Invariant audit

| Invariant | How it holds |
|---|---|
| **`I1`** (no MI development) | Every change is a file SM writes (Spring properties, which `I1` names as fair game) or a deployment property the customer owns (host network). Nothing is asked of the Cache's code, build or image. No tool inside the image is required: the `curl` runs on the **host** over SSH. `management.server.*`, `management.endpoints.web.exposure.include`, `management.endpoint.loggers.access` and `logging.level.*` are all Spring Boot properties (§2). **Not claimed:** that the Cache starts with them; that is T9.3's boot test. |
| **`I2`** (verify external facts) | §2: every Boot and Docker claim is a fetched URL with the verbatim line and a date, or a local run. The one unmeasured claim (the loopback-in-a-bridge-container) is labelled and carried to T9.3. |
| **`I5`** (tenant scope) | The placement is looked up by `(tenant, instance, placement)` (`F20`). The tenant comes from the session. No request names a host, port, command or URL. |
| **`I6`** (secrets) | A log level is not a secret. No credential is in the command. The only value the transport reads is the endpoint's JSON. |
| **`I7`** (plane boundary) | All of it is data-plane. Authorization (`logs.setLevel`, `logs.read`) is decided in the gateway before the DP is called, as for every verb. |
| **No new network exposure** | Ra: loopback only, in the host's namespace. Rd: none added. The one residual: Ra's endpoint is reachable from **other host-network containers and local host users** (§3.2, S4). Without Spring Security (`I1`) nothing can narrow it. |
| **`OR-43`'s "temporary override shown as such"** | §6. |

---

## 11. Draft decisions (the integrator allocates the ids; T0.3 registers them)

### `«next free BE-D»` (draft L-1): a live log-level change is available only where the management server is loopback-bound in the host's namespace; otherwise P2

**Decision.** P1 for a placement requires, and SM **probes**, five conditions (§3.3): `via-ssh` acquisition; the
package declares logger support; the container shares the host's network namespace; SM holds a management port; a
live `GET` returns `200`. Where any fails, the placement gets P2 only and `availability` states which. SM never
publishes the management port, never binds it to a container interface, and never writes the overlay for a
`direct-http` instance. **Flagged:** this moves every actuator endpoint for a P1 placement (§3, P-5), so the DP's
health and actuator-metrics reads for it use the management port. **Status: PROPOSED.** Implements `OR-43`; waits on Q-L1.

### `«next free BE-D»` (draft L-2): the overlay is one function, shared by the renderer and by read-back

**Decision.** `OverlayKeys.forPlacement(...)` is the single source of the keys SM adds at render beside the
package's fields (§4.1). They are not package fields, and `OR-34`'s undeclared-key rule is unchanged. The renderer
and `accept-runtime`'s read-back are its only callers. **Status: PROPOSED.** Read together with D-ACCEPT's draft A-3.

### `«next free BE-D»` (draft L-3): a live level change is a journaled, audited operation, verified by read-back

**Decision.** The live set is one durable DP operation (J1 or J2, Q-L3), durable before the SSH call, with a closed
command template, validated tokens (§7), and a landed state of `APPLIED` only when a read-back shows the requested
level. The logger name is validated against the package's `loggerPrefix` grammar before any session opens. `ROOT`,
`FATAL` and `OFF` are not offered. **Status: PROPOSED.** Waits on Q-L1 and Q-L3.

### `«next free BE-D»` (draft L-4): the override report is derived, and errs toward showing an override

**Decision.** `runtimeOverride` follows §6: from a live read where possible, else from the journal and the last
observed container start, labelled "inferred". **Status: PROPOSED.**

### `«next free BE-D»` (draft L-5): a package declares logger support in `logs.loggers`; nothing is inferred

**Decision.** A package declares the loggers endpoint path and the level property in an optional `logs.loggers`
block (§8). A package that declares neither gets neither mode. **Status: PROPOSED.** Needs a reviewed schema change (§13); waits on Q-L4.

---

## 12. Owner questions this design raises

Each has a status quo, the options with their delta, and a recommendation. **None is decided here.**

| Id | Question | Status quo (measured) | Options: delta | Recommendation | Gates |
|---|---|---|---|---|---|
| **Q-L1** | **Is `OR-43`'s live `SET` worth moving the actuator?** | No log level at all. | **(a) Ra + Rb as ruled.** Live set where host-network and SSH hold, P2 elsewhere. Costs: the actuator leaves 8081 for those placements, a management port per placement (§4.2), a launch-spec prerequisite, §5's operation. **(b) Rd.** Live *read* everywhere SSH reaches, no write surface, no actuator move, no journaled operation; every level *change* is P2 (a normal apply and a restart). Costs: no live set, which `OR-43` P1 ruled. **(c) Rd now, Ra later.** Ship (b); Ra needs only the additions of §3.3, none of which (b) forecloses. | **(a)** is what the owner ruled and the design supports it. **(c)** is the cheaper path to the same end state. | T9.3 (what it builds) |
| **Q-L2** | **Where does the management port come from?** | n/a | **M-a** operator-supplied, new column, one migration; **M-b** package-declared, collides on a shared host; **M-c** derived, no schema, arbitrary. | **M-a** | T9.3, a new migration and contract field |
| **Q-L3** | **How is the live set journaled, and is a fourth `D10` audit use acceptable?** | `D10` §1.4 names `apply_request` for three verbs. | **J1** a new `apply_request` operation; **J2** its own table; **J3** synchronous, audit line only. | **J1**; **J2** if "engine operation" may mean "durable DP record" | T9.3, `D10` amendment reading |
| **Q-L4** | **May the package schema gain an optional `logs.loggers` block?** | No declaration exists. | **G1** infer from `loggerPrefix`; **G2** the block; **G3** Cache-coded. | **G2**. G3 violates `OR-53`. | T9.3, T9.4 |
| **Q-L5** | **Is the Ra residual acceptable?** Other host-network containers and local users of the host can reach the loopback management port. | Today the application port is open to anyone who reaches it (§1). | **(a)** accept and state it. **(b)** require that no other container shares the host namespace (unverifiable by SM). **(c)** use Rd. | **(a)** | ratification |
| **Q-L6** | **Correct `architecture.md` §10?** Its "no separate port to bind" statement is contradicted by §2.4 (P-5). | stands | **(a)** correct it at ratification and keep the residual. **(b)** leave it. | **(a)** | ratification |

---

## 13. What this design asks of later tasks (for the integrator)

1. **T9.3**: first step is M12's open sub-row (§2.3): a container whose management server binds `127.0.0.1` behind `-p`, refused from the host. Then, per Q-L1, build either Ra+Rb (§3, §5) or Rd.
2. **Contracts (owned by whoever holds `console.yaml` and `contracts/`)**: an optional `logs.loggers` block (Q-L4) in the schema; `acceptRuntimeConfig` and `setLogLevel` text unchanged by this note. `ApplyObservation.operation` gains a member only if J1.
3. **A migration** (the integrator allocates the number; `V013` is reserved conditionally in the plan): J1 or J2, and, for M-a, a `placement.management_port` column. All three are new files. Never edit `V001`-`V013` of `sm_control` or `V001`-`V010` of `sm_data`.
4. **T6.9, T7.2, T9.1 and the collector (plan 011)**: the actuator port for a Ra placement is the management port, from one helper (§4.3). `SshjDockerTransport.java:251-269` hard-codes `8081`.
5. **T9.4**: `stage` and `copyForward` carry overlay entries forward (§9); `aDesignerEditKeepsTheConfiguredLogLevel`.
6. **T10.3 (D-ACCEPT)** shares `OverlayKeys` (§4.1).
7. **The plan text** lists `lifecycle_has_strategy` among the CHECKs a J1 migration touches. Measured (§5), it needs no change. `resolution_is_evidence` and `step_known` do, and the plan omits them.

---

## 14. Reproducing the local probe

All under the session scratchpad, nothing in the repository. In a directory `m/`:

```
unzip -q solace-cache-engine-1.0.0-SNAPSHOT.jar 'BOOT-INF/lib/*'
# Probe.java: a @SpringBootApplication + @RestController in package com.solace.solutions.cache,
#   GET /api/ping, and an slf4j logger named com.solace.solutions.cache.Probe
javac -parameters -cp 'BOOT-INF/lib/*' -d out Probe.java
java -cp "out:BOOT-INF/lib/*" com.solace.solutions.cache.Probe \
  --server.port=18081 --management.endpoints.web.exposure.include=health,info,metrics,loggers \
  [--management.server.port=18082 --management.server.address=127.0.0.1] \
  [--management.endpoint.loggers.access=read-only] [--spring.config.additional-location=file:cfg/]
curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  -d '{"configuredLevel":"DEBUG"}' http://127.0.0.1:18081/actuator/loggers/com.solace.solutions.cache
```

The `/actuator/health` status in the probe was `503` (`{"status":"DOWN"}`) because the probe has no real
application; nothing in §2.4 relies on it, and M3 (the Cache's own health semantics) is not settled by this probe.
