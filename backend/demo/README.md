# Plan 005's Cache walkthrough environment

One real [Solace Cache Engine](../../docs/design/backend/design.md) container, the external config
directory SM owns bind-mounted into it, and the broker that integration turns out to need. The
apply engine runs **from the test JVM** — there is no SM container here, and there cannot be one:
no application class exists, every endpoint is G2's, and inventing a deployable to host it would
be the largest unrequested thing plan 005 could do.

## Run it

**Prerequisites: two images.** `solace-cache-engine:local`, built in step 1 below, and a Solace
PubSub+ broker image. The compose file defaults to `solace-pubsub-enterprise:10.25.0.177` because
that is what was local on the machine this was measured on; any PubSub+ image will do. Override
either with `SM_DEMO_CACHE_IMAGE` / `SM_DEMO_BROKER_IMAGE`.

```bash
# 1. Build the Cache image from the sibling repository. -DskipTests on purpose: that project's
#    own test suite starts a PubSub+ broker, Redis and Hazelcast through Testcontainers, and the
#    only thing needed here is the jar.
mvn -f ~/Work/Development/solace-cache-engine/pom.xml package -DskipTests
docker build -t solace-cache-engine:local ~/Work/Development/solace-cache-engine

# 2. Seed the bind-mounted working directory from the committed template. The directory is
#    gitignored: SM overwrites application.yml by design, and ConfigFileStore retains pre-images
#    beside it.
cd backend/demo
mkdir -p config/cache-1 && cp cache-application.yml config/cache-1/application.yml

# 3. Bring up the broker, then the Cache. The Cache waits for the broker to report healthy.
docker compose up -d

# 4. Run the walkthrough. It is NOT part of `mvn verify`'s gate — see below.
cd ../..
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
  mvn -f backend/pom.xml -pl dp-core -am verify \
      -Dsm.demo.cache=true -Dit.test=CacheApplyWalkthroughIT

# 5. Tear down. The project name is this worktree's own, so this stops nothing else.
cd backend/demo && docker compose down
```

`sm_data` comes from Testcontainers, exactly as it does for every other integration test in
`dp-core`. There is deliberately no PostgreSQL service here: one bootstrap path, not two that can
drift.

## What the walkthrough proves, and what it does not

**It proves the target.** The Cache's own image, its own `--spring.config.additional-location` on
its own `ENTRYPOINT`, its own container-layer config path, and its own health signal:

- pre-flight resolves the declared location from the container's launch spec, confirms the bind is
  persistent, and round-trips a probe through it;
- a `manual` apply materializes SM's bytes into that path and terminates `STAGED`, and the hash is
  read **back** from the runtime rather than echoed from what SM wrote;
- materialization moves nothing about the process — the start baseline is byte-identical
  afterwards — and only an **operator**-issued restart moves it, which SM then observes;
- a `rolling-health-gated` apply has SM issue the restart and gate on the container's live
  `State.Health` before landing `APPLIED`, with the `LIFECYCLE` attempt carrying the baseline
  captured before the verb.

**On "health signal", precisely.** The Cache's image declares no `HEALTHCHECK` — the probe is the
compose-level one in this directory, a TCP connect to the Cache's own port, declared by SM as
deployment configuration because `I1` forbids changing the image. So the signal the gate reads is
genuinely the daemon's `State.Health`, and what makes it appear is SM's own healthcheck, not
something the Cache ships. It is deliberately not the Cache's `/actuator/health`: that would be a
richer signal and is available, but a port probe is what a runtime-neutral adapter can read
through `inspect` on any container.

**It does not prove the mechanism.** The mechanism is proven against a **stand-in container** by
`DockerAdapterContractIT`, `DockerPrepareIT`, `DockerMaterializeAndStatusIT`, `DockerLifecycleIT`,
`DockerEvidenceSecrecyIT` and `ManualApplyDockerWalkthroughIT`, and against the in-memory adapter —
no container at all — by `ManualApplyIT`, `RollingApplyIT`, `ResumeIT` and `StopDuringApplyIT`.
Those two lists are different things and are listed separately on purpose: only the first group
touches a daemon. **There is no unconditional test that drives `HealthGate` against a real
daemon's `State.Health`** — that combination exists only here, which is part of why this
walkthrough is worth running.

Those suites carry `@Testcontainers(disabledWithoutDocker = true)`, so "on every build" holds
because plan 004's `DockerAvailabilityPreflightIT` — unconditional — fails the build when Docker is
unreachable. That paired check is what makes the skips safe, and plan 005's Results carry the
watch item that its own Docker-absent probe is inconclusive on this host.

**It is not the completion gate.** Lesson `R10` does not forbid a conditional skip; it requires a
suite that a plan *calls* its completion gate to carry a **paired mandatory check** that fails
loudly when the precondition is missing. This suite is gated on `-Dsm.demo.cache=true`, has no
paired check, and is therefore **not** called a gate — here, in its class javadoc, or in plan 005.
It needs a sibling repository built and two images present, which no gate can guarantee.

**A live, authenticated, browser-driven control demo cannot ship without G2 ratification or the
documented waiver.** Nothing here is a step toward one: there is no endpoint, no session, no
identity, and no UI.

## Two measured facts about the Cache, recorded because they contradict plan 005's own

Both were measured on 2026-09-14 while building this environment, and both correct the plan.

1. **The Cache cannot be started broker-free.** Plan 005 recorded that
   `cache-engine.solace.enabled: false` switches off `JcsmpSolaceConsumer`, `JcsmpSolaceProducer`
   and `LeaderElection`, and concluded the walkthrough needs no broker. The three
   `@ConditionalOnProperty` annotations are real, but `CacheManagerService` is a plain `@Service`
   with a hard constructor dependency on `JcsmpSolaceProducer`, so the context fails to refresh:

   > Parameter 2 of constructor in `com.solace.solutions.cache.lifecycle.CacheManagerService`
   > required a bean of type `com.solace.solutions.cache.dispatch.JcsmpSolaceProducer` that could
   > not be found.

   No property makes that bean appear. Making one appear would be development on the managed
   integration, which `I1` forbids. A broker is therefore a **prerequisite of running the Cache at
   all**, not a feature of this demo — and it is the integration's own messaging, not SM's event
   mesh, so `BE-D5` and the two open ACL-shape items are untouched by it.

2. **The queue bind is synchronous, and it is reached only through the management tier.** With the
   Cache's own default `cache-engine.solace.management.enabled: true`, start-up dies on
   `JCSMPErrorResponseException: 503: Unknown Queue [Subcode:20]` when `orders-cache-q` does not
   exist. The path is: `LeaderElection` (`@ConditionalOnProperty` on **both**
   `cache-engine.solace.enabled` *and* `cache-engine.solace.management.enabled`) has the
   `@PostConstruct` that calls `JcsmpSolaceConsumer.startConsumer()`, which self-invokes its own
   `@Async startQueueConsumerAsync` — a self-invocation bypasses Spring's proxy, so the bind runs
   on the main thread. `JcsmpSolaceConsumer`'s own `@PostConstruct` is **empty**.

   The SM-owned file therefore sets `management.enabled: false`, and **no queue provisioning is
   needed at all**. That was measured rather than reasoned: probed on 2026-09-14 against a broker
   whose SEMP queue list returned `{"data":[]}`, the Cache reached `healthy` in 3 s with no
   `LeaderElection` line in its log. An earlier revision of this environment shipped a `provision`
   service that created `orders-cache-q`; it was removed once the probe showed nothing bound it.

   **This correction is itself an instance of the lesson the first one bought.** The first draft of
   this section inferred the mechanism from two annotations in one class without tracing the
   caller, which is `L22` — measuring an input, not the outcome — one layer down from where the
   lesson was written. An independent audit caught it.

The plan's stated risk — that the Cache's `application.yml` carries
`cache-hit-response.template-mappings.ivr` pointing at an absolute path on another developer's
machine — **did not materialize**: those lines are commented out in the Cache's own resource, and
the SM-owned file does not reproduce them.

## Compose project identity

The project is named `sm-plan005-demo` in the compose file itself. Docker is shared state on this
machine and more than one Solution Manager stream uses it; a project name derived from the
directory would collide the moment two worktrees both have a `backend/demo`. Every container is
prefixed accordingly (`sm-plan005-broker`, `sm-plan005-cache-1`), and
`docker compose down` from this directory stops those and nothing else.
