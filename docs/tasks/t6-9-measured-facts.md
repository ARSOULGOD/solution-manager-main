# Plan 010 T6.9 Step 0 — measured facts M3, M12, M18, M19

Date 2026-10-05, branch `p010/s6`. Every external fact was fetched (`I2`); each cites its URL. Anything that needs the
live demo host is recorded **pending for W9** and is not claimed.

## M3 — Spring Boot actuator health (✅ for the fixture; the host-reachability half is M12)

- **Cache's Boot version is `3.5.16`, not the `3.3.5` that `CLAUDE.md` says.** `solace-cache-engine/pom.xml:17`
  `<spring.boot.version>3.5.16</spring.boot.version>`, imported into `dependencyManagement` (`:31-32`). (`3.3.5` appears
  only as two other dependencies' versions, `:132`, `:145`.)
- `https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html`: *"By default, `OUT_OF_SERVICE` and `DOWN`
  map to 503. Any unmapped health statuses, including `UP`, map to 200."*; `management.endpoint.health.show-details`
  default is `never`.
- The JSON `status` field is a `String` (`https://docs.spring.io/spring-boot/3.5/api/rest/actuator/health.html`, "Response
  Structure": `status` — "Overall status of the application"); the example body is `{"status": "UP", ...}`.
- Cache's own config exposes `health,info,metrics` and sets no `management.server.port`
  (`solace-cache-engine/src/main/resources/application.yml:3-10`), so the actuator shares the application port `8081`.
- **Consequence for the mapping (T6.9 point 2).** `actuator-health`: HTTP `200` **and** body `status: "UP"` is `HEALTHY`;
  any other answer (`503`, `DOWN`, `OUT_OF_SERVICE`, `UNKNOWN`, a `200` whose body is not `UP` or is not JSON) is
  `UNHEALTHY`. With `show-details: never` the body is still `{"status": "..."}`, so the body check works without details.
  `http-status` (no schema text defines it beyond its name): any `2xx` is `HEALTHY`, anything else answered is `UNHEALTHY`
  — **this reading is the implementer's, flagged for the owner**.
- Cache's declared `strength` stays `liveness`; this fact **never upgrades the declaration** (T0.1a).

## M12 — where the management port is reachable from the host (⚠️ partly pending W9)

- Local: Cache serves the actuator on the application port `8081` (above), so the probe port is the host-side port the
  customer's launch spec publishes for `8081`.
- Docs (`https://docs.docker.com/engine/network/port-publishing/`) were not re-fetched for this task: the schema column
  `placement.health_port` already records "the HOST-side port (M12)" and T6.9 uses it as given for both modes.
- **Pending W9:** `docker inspect <container> --format '{{json .HostConfig.PortBindings}}'` and `command -v curl` on the
  demo target. Not run (the task forbids contacting a non-local host).

## M18 — Docker `State.StartedAt` (✅ locally, Docker Desktop, `busybox:1.36`; demo-target repeat pending W9)

Measured with `docker inspect -f '{{.State.StartedAt}} running={{.State.Running}} restarting={{.State.Restarting}}'`
on a throw-away container (removed afterwards):

| Event | `StartedAt` | `Running` | `Restarting` | container id |
|---|---|---|---|---|
| created | `…15:54:56.020Z` | true | false | `0527…` |
| `docker restart` | `…15:54:59.350Z` (**changed**) | true | false | same |
| `docker stop` | `…15:54:59.350Z` (unchanged — the last start) | false | false | same |
| `docker start` (stop then start) | `…15:55:04.849Z` (**changed**) | true | false | same |
| removed and re-run (new container) | `…15:55:05.217Z` | true | false | `159c…` (**new id**) |

- The host clock at the end (`date -u`) was `15:55:05Z`, matching `StartedAt` to the second: it is the daemon's clock.
- **Not measured:** the values *during* a restart in progress (the window is milliseconds on this image). The gate already
  treats a baseline it cannot read as unreadable, so this does not change T6.9. Official page
  (`https://docs.docker.com/reference/cli/docker/container/inspect/`) was not fetched.
- Consequence: "the baseline moved" (`StartedAt` differs from the pre-restart value) is a sound restart signal for both a
  restart and a stop-then-start; a recreated container is told apart by its id (`CONTAINER_ID` in the runtime ref).

## M19 — `curl` exit codes (✅ locally, curl 8.7.1; demo-target repeat pending W9)

`https://curl.se/docs/manpage.html`: `--fail` — *"Fail with error code 22 and with no response body output at all for
HTTP transfers returning HTTP response codes at 400 or greater"*; exit `7` couldn't connect; `28` operation timeout.
Measured against a local stub that answers `503` with `{"status":"DOWN"}` and a closed port, with the fixed probe command
`curl --silent --show-error --max-time 2 --output - --write-out '\n%{http_code}' <url>`:

| Case | stdout | exit |
|---|---|---|
| `503` stub, **no** `--fail` | body, then `503` | **0** |
| `503` stub, `--fail` | `503` only (no body) | **22** |
| closed port | `000` | **7** |

So without `--fail` a `DOWN` actuator is an answer (exit 0, status `503`, body present) and is told apart from a
transport failure (exit non-zero, status `000`). `--fail` would make a `DOWN` exit 22 with no body — indistinguishable
from a failure by exit code alone and without the body to read — which is why the probe command must not carry it.
