# Plan 010 T6.6 — The first dp-remote end-to-end walkthrough (OR-46 gate): evidence

**Status: LIVE RUN DONE (run 3, 2026-10-06). The OR-46 claim is made, for the first E2E slice only (§0, §13).**

This file records the integrator's live run of `SkeletonWalkthroughIT` and `MeshResilienceIT` against an operator-provided
SSH-Docker host running Cache. It is built from the two evidence files the ITs wrote, **already redacted of host
coordinates by the integrator**; the placeholders `<e2e-host>`, `<ssh-user>`, `<customer-broker>`, `<customer-vpn>` and
`<config-dir>` stand for values that are deliberately not in the repository. Where a section's source was the operator's
own host commands (`docker ps`, `ls -la`, `docker inspect`, the M4 observation) and the run did not capture it, the section
says **"not captured"** rather than supplying a value.

> **Do not paste secrets.** The Cache broker's password, the SSH key (or its path), and the local broker's per-run
> credentials never appear here, and neither does any host address, broker address, VPN name, user name or key. The
> evidence log the ITs write refuses a line that carries a registered secret. Hashes, ids and topics are evidence;
> envelopes are recorded as ids, never as bodies.

---

## 0. What this run may and must not claim

**Claims (the first E2E slice):** declare → stage → apply → observe on one SSH-Docker Cache instance, REST authoritative,
`instance.apply-outcome` emitted after commit and received on a local broker.

**Does not claim:** browser live notify, plan 007's event-driven skeleton complete, B/D5's skeleton, multi-placement.

**Not demonstrated, recorded as skips (§11):** live notify for the browser (open items 32/33/35); scenarios 4-10 of plan 007
Task 9 (the frontend's); scenario 3's *exported-metric* assertion (no exported metric exists in this build).
Scenarios 1b and 2 **did run as designed**: FLAG T6.6-1 was fixed before this run (§10).

---

## 1. The run

| | |
|---|---|
| Date and time (UTC) | `SkeletonWalkthroughIT` evidence file stamped `20261006T123925Z`; `MeshResilienceIT` stamped `20261006T125412Z` |
| Operator | the integrator |
| SM commit | run 3 is the first run after `0e15cca7` (`test(launcher): the walkthrough evidence never prints the Cache broker username`), the commit that fixed run 2's failure (§10, item 5); the exact `git rev-parse HEAD` of the run was **not captured** in the redacted evidence |
| Branch | `integration/dp-remote-demo` |
| Command | `backend/launcher/scripts/run-e2e-walkthrough.sh` with the `SM_E2E_*` variables (host, port, user, fingerprint, key file, container, config dir, `SM_E2E_CONFIRM_WRITE=yes`, and the Cache's own broker settings from the environment); the printed Maven line was **not captured** |
| Host under test | `ssh <ssh-user>@<e2e-host>:22`, container `cache-solution`, config `<config-dir>/application.yml` (mounted at `/app/external/spring/config`), health port `8081`; the Cache's own broker `tcp://<customer-broker>`, VPN `<customer-vpn>` |
| SM mesh events went to | a LOCAL broker started by the run from `solace-pubsub-enterprise:10.25.0.177` (VPN `sm-e2e`), never a remote broker |
| Database | `postgres:14` (Testcontainers), both schemas, the real migration mains |
| Result of run 3 | `SkeletonWalkthroughIT`: 9 run, 0 failures, 1 skipped (browser live notify). `MeshResilienceIT`: 7 run, 0 failures, 0 skipped |
| Runs 1 and 2 | failed **before any host write** (§10, item 5) |
| Run twice for order independence (plan Step 4) | **No.** The live ITs ran green once (run 3). The order-independence run (alphabetical, then reverse) was made on the rehearsals in the preparation lane (plan 010 T6.6 Results); it is not claimed for the live ITs |

The ITs write one evidence file each under `backend/launcher/target/walkthrough-evidence/`; their content is the source for the
sections below.

---

## 2. Host before the run (operator's own commands, no secrets)

| | |
|---|---|
| `docker ps --filter name=<container>` | not captured (the ITs' own read, §3, found the container running) |
| `docker inspect <container> --format '{{.Config.Image}} {{.State.StartedAt}}'` | not captured |
| `ls -la <config-dir>` | not captured |
| Cache version / image digest | not captured |
| Is the Cache healthy (actuator at the health port) | `SkeletonWalkthroughIT` read `HEALTHY` before its first write; `MeshResilienceIT` read `UNREADABLE` (§3) |

## 3. Original host state (the IT reads this BEFORE any write of the run)

| | |
|---|---|
| Keyed hash of the host file (`SkeletonWalkthroughIT`) | `hmacsha256v1:05abbb40cbfacf45d51257fb9a2485ef8aa4ebf2ecda674bf49ea711215c0b21` |
| Keyed hash of the host file (`MeshResilienceIT`) | `hmacsha256v1:1db777cddf04a051003f34bb33ccf6a884fcb7edcd718f51b585e3e4e422ea2e` |
| Container running; health signal | `SkeletonWalkthroughIT`: true; `HEALTHY`. `MeshResilienceIT`: true; `UNREADABLE` |

**The two hashes differ, and this file does not claim why.** Each IT runs against its own fresh database with its own
organization (`9bedcb25-...` and `95106911-...`), and the keyed hash "can only be compared with another hash from the same
install" (the template's own caution), so the two values are not comparable. What is proved is the comparison **inside each IT**:
its own pre-run hash equals its own post-restore hash (§8). The `UNREADABLE` health signal of the second IT is recorded as
observed; the run did not investigate it. The file's plain bytes are not recorded anywhere in the repository or here.

---

## 4. `SkeletonWalkthroughIT` — the walk

Source: the IT's evidence file. Result is PASS, FAIL or SKIPPED (with the reason in §11).

| # | Step (actor) | Result | Observed |
|---|---|---|---|
| Setup | installing operator: `EntitlementMain` (cache, max 20 instances), `TargetConnectionMain`; bootstrap admin: `TargetRegistrationMain`, the target **owned at the walkthrough environment** `[org, unit, env]` (S0 point 22) | PASS | org `9bedcb25-4bd9-419a-bdbd-32384736f2f6`, unit `edeb26df-50a6-439c-8ee3-f2182df5e8d6`, environment `ee6dfb01-e008-4a9f-8d9f-2f7d88a8581b` (sibling `87d8e13a-dc5c-4c66-86ea-e9f4e565da29`); target `5334a4e9-52a5-4fa2-a4d1-d44bccbe1faf`, envelope root `<config-dir>`, prefix `cache-solution` |
| 1 | bootstrap admin loads the Cache package over HTTP; `demo-operator`'s attempt is `403` (it is the admin's leg, OR-14) | PASS | solution `2dce7df6-7f11-42af-a2a5-f2ece8def482`; `catalog.package` notice `eventId 5f7899ee-0e23-49c0-86f5-01a97e606ed8`, `stateVersion 1`; the operator's attempt: `403` |
| 2 | `demo-operator` lists solutions and targets (reach classification, S0 point 24); the sibling environment's target and another organization's are absent | PASS | 1 solution, 2 targets; the registered target is among them with the envelope root and prefix above |
| 3 | declare outside the envelope: `422` naming the field, and **nothing written** (B2, against the real host's envelope) | PASS | a config directory outside the root: `422` naming `configDirectory`; a container not matching the prefix: `422` naming `containerName`; placement rows 0 before / 0 after; instance rows 0 / 0 |
| 4 | declare one instance (`201`), raise this run's generation floor, stage a secret-free change | PASS | instance `01a11136-761f-767b-a5be-32c9fab9be53`, placement `01a11136-761f-73b6-840f-4b9f80b4cc15`, floor `179129014400`, generation `179129014401` (`solace.enabled=false`, a changed `namespace`) |
| 5 | `manual` apply: `202` + `applyId` before any adapter call; observed to `STAGED` by REST; `instance.apply-outcome` for that `applyId` received on the local broker | PASS | applyId `01a11136-9693-705a-b708-d2f7d771e68a` (first observation `RUNNING`, then `STAGED`, `stateVersion 8`); topic `sm/v1/9bedcb25-4bd9-419a-bdbd-32384736f2f6/notify/instance/01a11136-761f-767b-a5be-32c9fab9be53/apply-outcome/01a11136-9693-705a-b708-d2f7d771e68a`; `eventId 8d3eaa26-4eba-4d8f-81d3-7e715d9c8405`; `stateVersion 8` (= the request's own stamp); rollout `staged` for replica `cache-solution`; config-state `persisted` |
| 6 | **B1 on the production `SshjDockerTransport`:** `apply_attempt.observed_hash` is spelt `hmacsha256v<n>:` and equals the intent's `config_hash`; the host read now returns the same hash; ladder `persisted` | PASS | observed `hmacsha256v1:0ef7d1420261cbd11d04985b142c0bcd3fc0bb13717d0f719bf19fd0f7a85470` = the intent's `config_hash`; the live host read returned the same keyed hash; ladder reached `persisted` |
| 7 | the sibling environment's instance is `404` for operator one, absent from the list; ours is `404` for operator two; declare into the sibling environment `404` | PASS | sibling instance `01a11137-a19a-7151-84ef-1eacdf5d1c4e` (declared by its own operator) |
| 8 | one `rolling-health-gated` apply on the real host | PASS | applyId `01a11137-a1e2-759c-bbe7-5b52652c6dd7`, generation `179129014402`; landed `APPLIED`; **`gateProved = liveness`** (the API's value); ladder reached `restart-observed`; rollout notices `staged`, `restart-requested`, `restart-observed` once each, then the apply-outcome (`APPLIED`); the Cache's own broker staged **with its password as a secret reference** |
| 9 | live notify for the browser | SKIPPED | open items 32/33/35 (see §11) |

### 4a. M4 — does the running Cache re-read `application.yml` without a restart? (observation about the E2E fixture)

The ladder reports only what the journal proves: after the `manual` apply it reports `persisted` and no `restart-observed`.
Whether the **running** Cache picked the new file up is the operator's observation; no code branches on the answer.

| | |
|---|---|
| How observed (command, no secret) | **not observed in run 3** |
| Before the manual apply | not captured |
| After the manual apply, **no restart** | not captured |
| Conclusion for the fixture | **could not be determined**; M4 stays open for the fixture |

---

## 5. `MeshResilienceIT` — the backend scenarios

Plan 007 Task 9's scenarios **1-3 and 11**, plan 010's **R1**, and (since FLAG T6.6-1 was fixed) **2 and 1b as designed**, against
the real SSH-Docker adapter. Scenarios 4-10 are the frontend's handoff and are not backend evidence. Organization
`95106911-d394-4e5f-bb46-2aeed129b92d`, environment `c1afdae7-6aa0-4463-a9ca-0e3b6ed04f0c`, target
`eae21cc9-d183-4970-a3f5-88f19e0b220b`, instance `01a1113a-78a4-70cf-9f77-c08130e8ac8f`, placement
`01a1113a-78a4-7f6c-ad56-c13818769d0a`, generation floor `179129040700`.

| Scenario | Result | Observed |
|---|---|---|
| **1** broker down *before* an apply (the broker stopped, then the application started: SM never held a session) | PASS | applyId `01a1113a-cc7c-7d9f-8d2c-a09b3fb96250` landed `STAGED` via REST; journal `PREFLIGHT#1:SUCCEEDED, MATERIALIZE#1:SUCCEEDED`; `droppedCount 0 -> 3`; no worker/emitter complaint; ladder `persisted` |
| **FLAG T6.6-1** (broker stopped while SM held a session; fixed: bounded publish) | PASS | `submitApply` for generation `179129040702` answered in **430 ms** (applyId `01a1113c-4238-728c-8d5f-f74c09a9d0ff`) and landed `STAGED` via REST; threads parked in `waitUntilSessionReconnectDone`: none (`[]`); `droppedCount 0 -> 3`; after the broker returned publishing resumed by itself (applyId `01a1113d-9063-77ad-9745-193919758969`'s outcome reached the observer; journal `PREFLIGHT#1:SUCCEEDED, MATERIALIZE#1:SUCCEEDED`) |
| **3** one notice dropped (the transport throws once, injected in front of the real JCSMP transport) | PASS | applyId `01a1113e-9018-7fa1-ab7c-db6a13f1a116`; owed three notices (`apply-outcome 01a1113d-... SUPERSEDED`, `rollout ... staged`, `apply-outcome 01a1113e-... STAGED`); the first was lost, the other two delivered; `droppedCount 0 -> 1`; the apply landed `STAGED`; no exception reached the engine or the worker |
| **11** the process is killed mid-apply (after the write, before the observation), restarted: the startup sweep lands it — **against the real SSH-Docker adapter** (closes plan 005's third routed watch item) | PASS | applyId `01a11141-6229-79a4-ac02-4101686d9883`; at the kill: `RUNNING`, 1 open attempt, journal `PREFLIGHT#1:SUCCEEDED, MATERIALIZE#1:OPEN`, 1 config write by the dead process; after the restart's sweep: `STAGED`, journal `PREFLIGHT#1:SUCCEEDED, MATERIALIZE#1:SUCCEEDED, PREFLIGHT#2:SUCCEEDED, MATERIALIZE#2:SUCCEEDED`; per-step attempts within the budget of 3; the host holds the intended keyed hash; one apply-outcome notice |
| **R1** verify-then-retry on the real host: the connection is lost after the write lands → `APPLY_INDETERMINATE`; restored; the reconciler inspects, finds the intended file (keyed hash) and, under `manual`, **confirms with no second host write** | PASS | applyId `01a1113f-823d-77a9-a226-8e5879f44ee8` went `APPLY_INDETERMINATE` after its write landed (reconciliation ledger rows: 1); found the intended file (keyed hash `hmacsha256v1:27baae75df0fe2486a466dadd12ba2805f703494eccedd041df8c981f553ca86`) and **CONFIRMED** it: `INDETERMINATE_RESOLVED`; config-writing adapter operations when the connection was restored: 3, after the reconciliation: 3 (**unchanged**); rematerialize 0, restore 0, restart 0; notices `APPLY_INDETERMINATE` then `INDETERMINATE_RESOLVED`; journal `PREFLIGHT#1:SUCCEEDED, MATERIALIZE#1:FAILED(ssh.connection.failed), MATERIALIZE#2:FAILED(ssh.connection.failed), MATERIALIZE#3:FAILED(ssh.connection.failed), ROLLBACK_ASSESS#1:FAILED(ssh.connection.failed)` |
| **2** broker dies mid-apply (as designed) | PASS | gated applyId `01a11143-1fbe-7a51-8e21-4c1a4587964c`: `staged` delivered once before the broker stopped; the rest dropped and counted (`0 -> 2`); the request landed `APPLIED`, ladder `restart-observed`, journal `PREFLIGHT#1:SUCCEEDED, MATERIALIZE#1:SUCCEEDED, LIFECYCLE#1:SUCCEEDED, VERIFY#1:SUCCEEDED` |
| **1b** broker dies while SM is connected (as designed) | PASS | applyId `01a11145-8bee-7389-aab5-b92631e523f9` landed `STAGED` with the broker down; `droppedCount 0 -> 1` |

**R1's mechanism, as it ran:** a test-scope bean post-processor wraps the production `RuntimeAdapter` bean in
`FaultInjectingRuntimeAdapter`. The window opens when `materializeConfig` returns; while open, `status(...)` **and
`assessRollback(...)`** throw `RuntimeAdapterException(ssh.connection.failed)`; it closes on the first refused
`assessRollback`, i.e. before the reconciler's first inspection. The real adapter performs every real write. R1 landed
`APPLY_INDETERMINATE` and was **not** skipped.

---

## 6. Counts

| | |
|---|---|
| Notices received on the local broker, by event, for each apply | `SkeletonWalkthroughIT` manual apply: apply-outcome 1, rollout 1 (`staged`), config-state 1 (`persisted`). Gated apply: rollout 3 (`staged`, `restart-requested`, `restart-observed`), apply-outcome 1. `MeshResilienceIT`: per scenario in §5 (the evidence does not total them) |
| `droppedCount` per scenario (`MeshResilienceIT`) | scenario 1: `0 -> 3`; FLAG T6.6-1: `0 -> 3`; scenario 3: `0 -> 1`; scenario 2: `0 -> 2`; scenario 1b: `0 -> 1` |
| Generations staged by the run | `SkeletonWalkthroughIT`: 2 (`179129014401`, `179129014402`; floor `179129014400`). `MeshResilienceIT`: floor `179129040700`; generations `...701` (restored from) and `...702` among those staged; the evidence does not total the rest |
| Config-writing adapter operations (materialize / rematerialize / restore) | R1: 3 / 0 / 0 at both observation points (unchanged by the reconciliation); other scenarios not totalled |
| Container restarts caused by the run | `SkeletonWalkthroughIT`: the gated apply (restart requested and observed) and the final restore (yes). `MeshResilienceIT`: scenario 2's gated apply and the final restore (yes) |

## 7. Observed topics and envelope ids (ids only)

```
sm/v1/9bedcb25-4bd9-419a-bdbd-32384736f2f6/notify/catalog/package                eventId 5f7899ee-0e23-49c0-86f5-01a97e606ed8  stateVersion 1
sm/v1/9bedcb25-4bd9-419a-bdbd-32384736f2f6/notify/instance/01a11136-761f-767b-a5be-32c9fab9be53/apply-outcome/01a11136-9693-705a-b708-d2f7d771e68a
                                                                                  eventId 8d3eaa26-4eba-4d8f-81d3-7e715d9c8405  stateVersion 8   (STAGED)
instance.rollout (step staged, replica cache-solution) and instance.config-state (persisted) for the same apply: received; their eventIds are not in the evidence file
MeshResilienceIT scenario 3 owed: apply-outcome 01a1113d-9063-77ad-9745-193919758969 SUPERSEDED (lost),
  rollout 01a1113e-9018-7fa1-ab7c-db6a13f1a116 staged, apply-outcome 01a1113e-9018-7fa1-ab7c-db6a13f1a116 STAGED (delivered)
```

---

## 8. The final restore of the host's original configuration

Each IT ends by restoring the file through the engine's own `RuntimeAdapter.restore` (it re-reads the retained pre-image of the
first generation the run wrote, refuses unless it still hashes to the original, writes it atomically), verifying by an
observation, and — when the run restarted the container and `restore.restart` is on — restarting the container so the
**running** Cache is back on the original config too.

| | `SkeletonWalkthroughIT` | `MeshResilienceIT` |
|---|---|---|
| Restore path used | engine restore, from the pre-image of generation `179129014401` | engine restore, from the pre-image of generation `179129040701` |
| Host file keyed hash after the step | `hmacsha256v1:05abbb40...c0b21` (**EQUALS** the original) | `hmacsha256v1:1db777cd...ea2e` (**EQUALS** the original) |
| Container restarted by the restore | yes | yes |

Neither evidence file says `MANUAL:`: the engine's own restore returned the host file to its original **both times**, and each
IT's own keyed hash equalled its original.

| Manual restore needed by the run | no |
|---|---|

## 9. Host after the run

| | |
|---|---|
| `ls -la <config-dir>` | not captured (SM's hidden `.<file>.sm-preimage-<generation>` files were expected to remain when this run was made, because the SSH adapter did not yet prune them; since the Fable APPLY-02 fix it keeps the newest `placement.retained_generations` generations and removes older ones after a verified write) |
| `docker ps --filter name=<container>` | not captured at the time of the run. **Observed afterwards (see §10, item 6):** the Cache crash-looped on a broker `401` after the faithful restore |
| Leftover `.sm-preimage-*` files removed by the operator? | not captured |

## 10. Flags and deviations (read before relying on any row above)

1. **FLAG T6.6-1 — a mid-session broker outage blocked the publishing thread. RESOLVED before this run** by
   `fix(mesh): a broker outage never blocks a caller - bounded send, counted as a drop (T6.6-1)` (`sm.mesh.send-timeout`, default
   400 ms; the parked send is cancelled, counted on `droppedCount()` and WARN-logged). **Measured live in run 3:** `submitApply`
   answered in 430 ms with the broker stopped, no thread parked in `waitUntilSessionReconnectDone`, `droppedCount 0 -> 3`, and
   publishing resumed by itself. Scenarios 1b and 2 are therefore no longer gated and passed as designed (§5).
2. **R1's window also loses `assessRollback`.** The plan's decorator loses `status(...)` only; with only that, the engine's
   exhaustion path assesses the host, restores the pre-image and lands `FAILED_ROLLED_BACK` (BE-D22's own path), never
   `APPLY_INDETERMINATE`. A dropped SSH session fails the assessment as well, so the window refuses both. Run 3's R1 landed
   `APPLY_INDETERMINATE` as intended.
3. **"SFTP write count" is counted at the adapter operation, not the SSH channel:** `SshjDockerTransport` is package-private
   to `dp-core`. The counted operations (materialize, conditional rematerialize, restore) are exactly the ones that issue the
   SFTP write.
4. **Generation floor.** A retained pre-image is named by generation only and a fresh database counts from 1, so each run raises
   the instance's `last_generation` (as the database owner, test setup only) to a per-run value so a previous run's
   pre-image on the host can never be taken for this run's original.
5. **Runs 1 and 2 failed before any host write.** Run 1: a stale failsafe summary. Run 2: the evidence guard refused a line
   because it matched a registered secret value; fixed in `0e15cca7`. Run 3, the one recorded here, passed.
6. **Post-run observation (not a test result): the host's ORIGINAL `application.yml` carried a stale broker password.** After the
   engine's faithful restore, the Cache crash-looped on a broker `401`. The owner chose to replace that one line with the current
   credential; the integrator did so, and the original file was backed up on the host. So the host file now differs from the
   original in that one line by the owner's decision, after both ITs had proved their restores. No credential value appears
   here. This is also why the restore's equality proof and "the Cache is running" are different facts: the engine restored the
   bytes it was given, and those bytes could not authenticate.
7. **The two ITs' original keyed hashes differ** (§3); not explained by this run, not claimed to be.

## 11. Skips, each with its reason

| Skipped | Reason |
|---|---|
| Live notify for the browser (a browser session subscribing to the mesh) | open items 32/33/35: a notify ticket grants organization-wide subscription, which an environment-scoped principal's REST scope forbids, so the subscription shape that enforces the approved scope model does not exist yet; plan 007 Task 10 is hard-gated |
| Scenario 3's exported-metric assertion | no exported metric exists in this build; `JcsmpEventPublisher.droppedCount()` is a counter only (only the counter is asserted) |
| Scenarios 4-10 | the frontend's (`frontend/src/shared/mesh/resilience.test.ts`), not backend evidence |
| M4 (does the running Cache re-read `application.yml` without a restart) | not observed in run 3 (§4a) |

## 12. Docker state

| | Before | After |
|---|---|---|
| `docker ps -a` on the machine that ran the ITs (containers of this run only) | not captured | not captured; the throwaway PostgreSQL and the local broker are Testcontainers-managed and removed by the run |
| Containers started by this run | — | not captured |

(Only this run's containers are cleaned up; nothing else on the daemon is touched. This section's before/after listings were
not part of the redacted evidence the integrator supplied, so none is asserted.)

## 13. Sign-off

| | |
|---|---|
| All sections above filled from a live run | **partly**: every test-derived section (§1, §3-§8, §10, §11) is filled from the two evidence files; §2, §4a, §9 and §12 are operator-command sections the run did not capture, and say so |
| The OR-46 gate | **MET for: declare → stage → apply → observe on one SSH-Docker Cache instance, REST authoritative, `instance.apply-outcome` emitted after commit and received on a local broker.** NOT claimed: browser live notify, plan 007's event-driven skeleton complete, B/D5's skeleton, multi-placement, M4 |
| Reviewer | pending (the integrator's wave review) |
