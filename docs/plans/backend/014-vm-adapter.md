# Backend Plan 014 — the plain-VM runtime adapter (`ssh-systemd`)

> **Status: APPROVED AS A PLAN (`OR-121`, 2026-10-07); implementation deferred.** The owner approved this plan and
> ruled its questions, and deferred the build: *"let's focus docker only but our VM plan needs to be there, we can do
> implementation later"* (Q-V12 (b)). The `OR-115` PR into `dev` goes once plans 008, 012 and 013 are executed and this
> plan is approved (it is); **the VM adapter lands in a later PR**, after its own execution under `OR-70`. Nothing in
> this plan runs in the [combined 008/012/013 schedule](combined-008-012-013.md) — except **Q-V13 (b)**'s separate
> Docker file-mode task, which that schedule carries as task X1 because it must land before the `OR-115` PR. Written
> 2026-10-07 on branch `d/vm` off `integration/dp-remote-demo` @ `36af1df6`, from the design
> [`docs/design/backend/vm-adapter.md`](../../design/backend/vm-adapter.md), reviewed adversarially the same day (design
> §14). The full `docs/checklists/plan-review.md` pass is still due **before execution starts** (it no longer gates
> approval).

> **Rulings applied (`OR-121`, 2026-10-07).** Named in the ruling: **Q-V0 (c)**, **Q-V4 (a)**, **Q-V8**, **Q-V12 (b)**.
> Every other question takes the design's recommendation.
>
> | Question | Ruled answer |
> |---|---|
> | Q-V0 `B/D5`'s skeleton gate | **(c)** (named): *"a new reading beside LOCKED `B/D5` (its text unedited): for dp-remote runtime-adapter work the gate is met by the backend Docker end-to-end runs (plan 010 T6.6, plan 011 P11-4.4); the generated screen, the graph, the role and live notify stay required before v1 acceptance (flagged, not ratified)"* — **G-B is met** |
> | Q-V1 restart/stop permission | **(a)** sudoers, exact argv (`BE-D60`) |
> | Q-V2 lifecycle verb | **(a)** `systemctl restart` |
> | Q-V3 VM logs | **(e)** one exact-argv `journalctl` tail of a constant `N` (`BE-D63`); (a) may be added later where sudo ≥ 1.9.10 |
> | Q-V4 systemd floor | **(a)** (named): *"systemd 248 or later only; older distributions later"* (`BE-D59`); M-V10 is not applicable |
> | Q-V5 contract shape | **(a)** flat fields; `containerName` becomes optional — the breaking response change is accepted by adoption of the recommendation; ⚠️ the design asked the owner to accept it **explicitly**, so T2 asks for a one-line confirmation before it changes the contract |
> | Q-V6 reset-signal vocabulary | **(a)** add `systemd` (`BE-D64`) |
> | Q-V7 `SM-D21` launch check | **(a+)** advisory, but `NeedDaemonReload=yes` refuses; diverges from PROPOSED `SM-D21`, flagged |
> | Q-V8 live-gate host | **the E2E host** (named): *"Use same Ec2 host"*. `OR-121` **extends `OR-110`** to the root-level changes the gate needs — a service account, a sudoers file with exact arguments, a systemd unit — **each removed after the gate**. With Q-V9 (a), the gate's VM target needs a **second test organization** whose ports are partitioned from the first (`OR-92` OQ-23). **G-E is met** |
> | Q-V9 one host, two runtimes | **(a)** one runtime per host per organization (`BE-D62`) |
> | Q-V10 `OR-62`'s second pair | **(a)** by adoption; ⚠️ the design says this needs **a new owner row** naming `OR-62` — T1 asks for it before T2 changes the CHECK, `TargetService` and check 16 |
> | Q-V11 a restart that outlasts its bound | **(b)** the adapter looks again before throwing; no engine change (`BE-D65`) |
> | Q-V12 when the `dev` PR goes | **(b)** (named): the PR goes after plans 008/012/013 are executed and this plan is approved; the VM lands in a later PR (refines `OR-119`) |
> | Q-V13 Docker file mode | **(b)** a separate, small Docker task before the `OR-115` PR — **task X1 of the [combined schedule](combined-008-012-013.md)**. When this plan executes, Docker already writes with an explicit mode, so T4 keeps X1's Docker behaviour |

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or
> `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for
> tracking. **Read [Gates](#gates--what-must-be-true-before-each-slice) first.**

| | |
|---|---|
| **Component** | backend (`dp-core`, `dp-api`, `cp-core`, `cp-api`, `console-gateway`, `launcher`, `arch-tests`) + `contracts/` + docs + a runbook. **No frontend change** (owner standing rule). |
| **Status** | **APPROVED AS A PLAN** (`OR-121`, 2026-10-07); **implementation deferred** to a later PR into `dev` (Q-V12 (b)). Reviewed once (adversarial, 2026-10-07). |
| **Authority** | [`OR-114`](../../design/DECISIONS.md) item (4) (*"the plain-VM runtime adapter (`OR-111`)"*), sequenced by [`OR-111`](../../design/DECISIONS.md) (*"VM also, we need to cover but once multi-placement is done"*), [`OR-115`](../../design/DECISIONS.md) and [`OR-119`](../../design/DECISIONS.md) (items (1)–(4) close before the PR into `dev`; the owner then tests from `dev`). The owner rules for the architect group ([`OR-69`](../../design/DECISIONS.md)). |
| **Requirements** | [`REQ-005`](../../requirements/REQ-005-three-runtimes.md) (Docker, Kubernetes and bare VM/systemd). This plan delivers the VM leg for the `dp-remote` locus only. |
| **Depends on** | **Plan 008** (multi-placement apply and journal identity, `OR-114` item (1)) **merged**, because a VM instance on N hosts is N placements (`BE-D23`). **`OR-114` item (3)** (the target/SSH-key/host-key registration API, `d/regapi`), because a VM target must be registrable through the API as well as by operator command. **Plans 010 and 011** (executed). |
| **PROPOSED decisions relied on** (checklist item 2, knowingly failed) | The whole `BE-D*` family (the seam, `BE-D12`/`BE-D21`/`BE-D22`/`BE-D24`/`BE-D25`/`BE-D34`/`BE-D35`/`BE-D39`), `SM-D11a` (read as in design flag 5), `SM-D15`, `SM-D19`, `SM-D21`. **`SM-D11` is group-confirmed.** **`B/D5` is LOCKED and bars this plan today** (G-B, Q-V0). **`OR-62` is amended by this plan** only through the owner's Q-V10 ruling. |
| **Decisions this plan records** | Allocated 2026-10-07 at the integration of plans 008/012/013/014: V-1…V-5 are **`BE-D58`–`BE-D62`**, and the Q-V3, Q-V6 and Q-V11 drafts (written once ruled) are **`BE-D63`–`BE-D65`**, all PROPOSED. |
| **Execution model** | `OR-70`: Sonnet 5.5 (high) implements; **one Opus review per slice**; the **full reactor build at the end of each slice**; one combined Fable 5.1 review after the last slice. Independent tasks run in parallel per the [dispatch map](#dispatch-map). Push only after each slice's gates, fast-forward only (owner standing rule); no GitHub Project tasks. |
| **Estimate** | **About 14–19 working days of execution** once the gates are open ([Estimates](#estimates-and-the-critical-path)). An estimate from the task sizes, **not measured**; it excludes waiting for rulings, the VM host and the two other merges. |

**Goal:** Let an operator register a VM target (`kind: vm`, `transport: ssh-systemd`), declare an instance whose
placement is a systemd `.service` unit on that VM, and run every apply, rollback, stop, status, health, metrics,
read-back and log operation the Docker path supports, through the same `RuntimeAdapter` seam and the same contract
kit, with an SSH account that is not root and holds exactly two sudo rules per unit (plus the log rule Q-V3 picks).

**Architecture:** A new `SshSystemdRuntimeAdapter` beside `SshDockerRuntimeAdapter`. The two share one extracted
SFTP half (`SftpConfigFiles`), so seven of the ten seam operations are the same code. A
`TransportRoutingRuntimeAdapter` keeps the engine's single `RuntimeAdapter` bean. A sealed `SshPlacement`
(`SshDockerPlacement` | `SshSystemdPlacement`) with a shared `SshHost` replaces `SshDockerPlacement` as the type the
catalog and the host-side services consume (all 22 files of design §5.4). The schema gains one transport value, a
unit-name column and a unit-prefix envelope; the CP and the gateway learn the second pair. The contract gains one
enum value per vocabulary and the placement fields of Q-V5. The engine, the journal and the apply state machine do
not change.

**Tech stack:** Java 17, Spring Boot, sshj (already a dependency — **no new dependency**, so `I8`/`OR-` licence
gates are untouched), PostgreSQL 14 migrations, Testcontainers, ArchUnit, OpenAPI 3.1.

---

## Global Constraints

1. **No engine change.** No file under `dp-core/.../apply/engine` or `apply/worker`, no journal repository in
   `apply/persistence`, and not the `RuntimeAdapter` interface. The one exception is the operator command
   `TargetConnectionMain` (in `apply/persistence`), which T8 extends, plus `JdbcTargetConnectionRepository`'s
   hard-coded transport literal, which T2 replaces with a bind parameter. **Q-V11 (c) would breach this
   constraint**; it applies only if the owner rules (c) explicitly, and then the engine change is its own task with
   its own review. A task that finds it must edit anything else in those places stops and reports.
2. **Docker behaviour is byte-identical.** Every existing SSH-Docker test passes **unmodified**, except mechanical
   renames that T3 lists by name. The command strings `SshjDockerTransport` emits must not change; T3 adds a test
   that pins them. (Q-V13 was ruled (b): the Docker file mode changes **before** this plan, in the combined schedule's
   task X1, so it is not an exception here — T4 keeps X1's Docker behaviour byte-identical.)
3. **No new dependency** (`-o` offline builds; `I8`).
4. **No secret-bearing systemd property is ever requested** (`Environment`, `ExecStart`, `EnvironmentFile`, …).
   Only the closed, named set of design §4.3: `LoadState`, `ActiveState`, `InvocationID`, `ActiveEnterTimestamp`,
   `FragmentPath`, `NeedDaemonReload` (`I6`).
5. **No shell string from data.** Only fixed templates (design §8) whose variable parts are a grammar-checked unit
   name, a grammar-checked directory, configured `int` bounds and integer epoch seconds (`SM-D11a`, read as in
   design flag 5).
6. **Frozen migrations** (`OR-22`, `OR-105` (e)): no published migration is edited. New migrations use the
   integrator's numbers, written here as `«next sm_control V»`, `«next sm_data V»`, `«next sm_metrics V»`, allocated
   **at this plan's merge** (the rule the [combined schedule](combined-008-012-013.md#5-migration-numbers) states). After
   that schedule, the next free numbers are `sm_control` V019, `sm_data` V026 and `sm_metrics` V004 — if nothing else
   merges first.
7. **No frontend change, but one breaking response change.** Under Q-V5 (a), `Instance.placements[].containerName`
   becomes optional: a generated client or Postman assertion that relies on it fails **on a VM placement**. Docker
   responses stay byte-identical. The owner accepts this in the Q-V5 ruling, and T2 states it in the contract
   description; it is not "additive".
8. **Facts before code:** every M-V item a task reads is filled in [Measured facts](#measured-facts) by T0 first.
   A task whose M-V row is empty does not start.

### Verification commands (used by every task)

```bash
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
VERIFY() { mvn -o -f backend/pom.xml -pl "$1" -am verify; }

VERIFY <module>                     # the task's own module(s)
VERIFY arch-tests                   # boundary rules, adapter inventory
VERIFY launcher                     # when a bean, route or readiness check changes
python3 backend/scripts/check-frozen-migrations.py   # every task that adds a migration
./contracts/run-checks.sh --strict  # every task that touches contracts/
python3 contracts/openapi/postman/check-postman.py    # every task that touches console.yaml
git diff --check
```

---

## Gates — what must be true before each slice

| Gate | Needed before | How it is satisfied |
|---|---|---|
| **G-A** Owner approves this plan | anything | ✅ `OR-121` (approved as a plan; **execution waits for the owner to schedule it**, Q-V12 (b)) |
| **G-B** Q-V0: `B/D5` 🔒 no longer bars systemd work | anything | ✅ `OR-121` Q-V0 (c) |
| **G-C** Q-V1, Q-V2, Q-V4, Q-V5, Q-V9, **Q-V10**, **Q-V11** ruled | S1 | ✅ `OR-121`; ⚠️ Q-V5's explicit acceptance and Q-V10's `OR-62` row are asked for by name before T2 |
| **G-D** Q-V3 (logs), Q-V6 (reset vocabulary), Q-V7 (launch check), Q-V13 (Docker mode) ruled | S2's T4/T6/T7 | ✅ `OR-121` (Q-V13 (b): the combined schedule's X1 lands first) |
| **G-E** Q-V8 (live gate host) ruled, the host provided, and permission for root-level host changes | T0 (M-V items on that host) and T10 | ✅ `OR-121`: the E2E host, with `OR-110` extended to a service account, a sudoers file and a systemd unit, each removed after the gate; coordinates stay in the operator-held file outside the repo |
| **G-F** Plan 008 merged into `integration/dp-remote-demo` | S2's T8 (multi-host) and T10's S2 scenario | merge commit |
| **G-G** `OR-114` item (3) (`d/regapi`) merged, or T2 takes over its `transport` widening | T2's contract step and T8 | merge commit, or T2 note |
| **G-H** Q-V12 (when the `dev` PR goes) ruled | the `OR-115` PR, not this plan's tasks | ✅ `OR-121` Q-V12 (b) |

---

## Estimates and the critical path

Working days of execution, including each slice's Opus review and full build. **Estimates from the task sizes, not
measured.** Plans 010/011 did not record per-task durations, so there is no measured base to calibrate against.

| Slice | Tasks | Estimate | Waits on |
|---|---|---|---|
| S0 | T0 measure ‖ T1 records | 1 day | G-A, G-B; T0 also G-E (the host) |
| S1 | T2 schema + CP + gateway + contract (2 d) ‖ T3 the 22-file generalization (2.5 d) | 3 days | G-C |
| S2 | T4 SFTP + mode (1 d) → T5 adapter + router + kit (3 d) → T6 metrics/start token (2 d) ‖ T7 logs (1.5 d) | 6.5 days | T0's M-V1…M-V5, M-V9; G-D |
| S3 | T8 registration + declare (1.5 d) ‖ T9 runbook + sudoers generator (1 d) | 2 days | G-G; multi-host part G-F |
| S4 | T10 live gate (1–2 d) → T11 results + Fable review + fix round (1.5 d) | 2.5–3.5 days | G-E; S2 scenario G-F |
| **Total** | | **about 15 (range 14–19)** | |

**Critical path to the `OR-115` PR, if Q-V12 (a):** owner rulings (G-A, G-B, G-C) → S1, run in parallel with T0
once the VM exists (G-E) → S2 (needs T0's measurements) → S3 (needs `d/regapi` merged, G-G) → S4 (needs the VM, and
plan 008 merged for the two-host scenario, G-F) → the PR. Of these, only the five slices are estimable. The rulings,
the VM and the two other merges are the owner's and the other items' schedules, and any one of them can dominate.
**Under Q-V12 (b),** none of this sits on the PR's path: the PR waits only for items (1)–(3) and the approval of this
plan.

---

## Measured facts

Filled by **T0** on the Q-V8 host before any task that reads them. Design §2.1 states why each matters.

| Id | Fact to measure | Read by | Result (fill) |
|---|---|---|---|
| M-V1 | `systemctl show --timestamp=us+utc -p ActiveEnterTimestamp <unit>`: exact line for an active unit, for a never-active unit, and whether the option affects `show` at all | T5 (parser), T6 | |
| M-V2 | `systemctl show` with four `-p`: line order; output and exit status for a non-existent, a masked and a bad-setting unit | T5 | |
| M-V3 | `InvocationID` before/after `systemctl restart`, before/after a reboot (or a `stop`+`start` if a reboot is not permitted) | T5, T6 | |
| M-V4 | `sudo -n -l systemctl restart <unit>`: exit status and stdout when permitted, when not listed, when the rule lacks `NOPASSWD`; the path reported for the bare name; whether `Defaults requiretty` is set; that an `@` unit argument matches unescaped | T5 (`prepare`), T9 | |
| M-V5 | sshj SFTP temp create then `setattr` `0640`: resulting mode under the account's umask; group of a file created in a `2750` setgid directory | T4 | |
| M-V6 | Under the Q-V3 ruling's rule shape: `sudo -n journalctl -u <u> -o short-iso-precise --utc --no-pager -q -n 5` (and, under Q-V3 (a), with `--since=@<s>`): accepted, exact line layout (hostname, `identifier[pid]:` prefix), sudo version | T7 | |
| M-V7 | Cache under a customer-style unit (`Type=simple` and `exec`): start-up time to actuator `UP`, that `--spring.config.additional-location=<dir>/` makes it read SM's file, that `systemctl restart` exits 0 while a bad file makes it exit at start-up (S3) | T9, T10 | |
| M-V8 | `systemctl --version` and `sudo -V` first lines on the Q-V8 host and on any customer distribution the owner names for Q-V4 | T5's floor (Q-V4) | |
| M-V9 | `timeout --kill-after=2 5 sudo -n systemctl restart <slow unit>`: sudo's exit status, whether `systemctl` dies, and whether the job still completes (`InvocationID` changes afterwards) | T5 (bounds), Q-V11 | |
| M-V10 | Only if Q-V4 (d): `TZ=UTC systemctl show -p ActiveEnterTimestamp <unit>` on a pre-248 host: does it end in ` UTC`, and its exact layout | T5 | |
| M-V11 | `systemctl show -p FragmentPath,NeedDaemonReload <unit>` before and after editing the unit file without `daemon-reload` | T5 (`prepare` step 5) | |

---

## Migration and id allocation

| Placeholder | Content | Task |
|---|---|---|
| `«next sm_control V»__target_ssh_systemd.sql` | widen `target_transport` to `('ssh-docker','ssh-systemd')`; add `target_kind_transport_pair` CHECK | T2 |
| `«next sm_data V»__ssh_systemd_placement.sql` | widen `target_connection_transport`; `allowed_container_prefix` nullable; add `allowed_unit_prefix` (grammar, immutable, no UPDATE grant); transport-matches-prefix CHECK; `placement.container_name` nullable; add `placement.unit_name` (design §5.2 grammar: ≤ 128 in total, one `@` at most, not `@.service`, no `:` or `\`); exactly-one CHECK; `CREATE OR REPLACE` the envelope trigger function; `placement_unit_once` index | T2 |
| `«next sm_metrics V»__restart_signal_systemd.sql` | widen `signal` CHECK with `'systemd'` (Q-V6 (a)) | T6 |
| `«next sm_data V»__placement_log_file.sql` | **only if Q-V3 (c)** is also ruled: `placement.log_file_path` + log-root envelope | T7 |
| `BE-D` ids | drafts V-1…V-5, and the Q-V3/Q-V6/Q-V11 drafts | T1 at merge |

Plan 008, the 501 operations (`d/ops501`) and the registration API (`d/regapi`) are allocating in the same window.
**The integrator numbers in merge order.** No task here hard-codes a number.

---

## Single-owner files

| File | Owner task | Others |
|---|---|---|
| `contracts/openapi/console.yaml`, `validate-openapi.py`, Postman collection | T2 | T6 adds the `restartDetection` value as a one-line follow-up, reviewed in S2 |
| `TargetTransport.java`, `TargetController.java`, `TargetService.java`, `TargetView.java`, `TargetEnvelope.java`, `JdbcTargetConnectionRepository.java` | T2 | T8 reads |
| `TargetRegistrationMain.java`, `TargetConnectionMain.java`, `DeclareOrchestrator.java` | T8 | — |
| `SshjDockerTransport.java`, `SshDockerPlacement.java`, `TablePlacementCatalog.java`, `SshDockerRuntimeAdapter.java` | T3 in S1, then T4 → T5 in S2, sequentially | read-only |
| `SshSessionPool.java`, `HostMetricExec.java`, `HostMetricProbe.java` | T3 (type), then T6 (systemd command) | — |
| `SshSystemdRuntimeAdapter.java`, `SshjSystemdTransport.java`, `SshSystemdPlacement.java`, `SystemdCommands.java` | T5 | T6/T7 extend through their own strategy classes; T7/T9 add to `SystemdCommands` sequentially |
| `PlaneBoundaryTest.java` | T5 | T3 may rename `SshDockerPlacementCatalog` references |
| `ProductionApplyConfiguration.java` | T5 | — |
| `docs/runbooks/vm-systemd-target.md` | T9 | — |

---

## Dispatch map

| Slice | Tasks | Parallel? | Ends with |
|---|---|---|---|
| **S0** | T0 (measure), T1 (records) | T0 ‖ T1 | the M-V table filled; records reviewed |
| **S1** | T2 (schema, CP, gateway, contract), T3 (SSH generalization, Docker unchanged) | T2 ‖ T3 | full build; Opus review |
| **S2** | T4 (shared SFTP + mode), then T5 (adapter + routing + kit), then T6 (host services) ‖ T7 (logs) | T6 ‖ T7 after T5 | full build; Opus review |
| **S3** | T8 (registration + declare), T9 (runbook + sudoers generator) | T8 ‖ T9 | full build; Opus review |
| **S4** | T10 (live gate), T11 (results, Fable review) | sequential | the Results section |

---

## S0 — Facts and records

### Task T0 — Measure M-V1…M-V11 on the owner's VM

**Files:** `docs/design/backend/evidence/vm-systemd-measurements.md` (new; no host coordinates, `OR-118`'s rule).

- [ ] **Step 1:** Using the operator-held coordinates file (outside the repo), install a throwaway unit
  `sm-probe.service` (`Type=simple`, `ExecStart=/usr/bin/sleep infinity`, a slow `Type=notify`-style unit for M-V9,
  or the Cache jar for M-V7) **with the owner's explicit permission for root-level host changes (Q-V8, G-E)**, an
  `sm-agent` account, and the two-line sudoers file of design §6.1.
- [ ] **Step 2:** Run each M-V command exactly as written in the table. Paste the outputs, redacting host names.
  Keep the raw outputs as parser fixtures for T5/T7 (redacted).
- [ ] **Step 3:** For M-V5, write a temp file over sshj, `setattr` `0640`, in a `2750` directory and record `stat`.
- [ ] **Step 4:** Record the systemd and sudo versions (M-V8). **If the host is below the ruled Q-V4 floor, stop**
  and return to the owner with Q-V4's alternatives.
- [ ] **Step 5:** Remove everything installed in Step 1 and record that the host is back to its prior state.
- [ ] **Step 6:** Fill the [Measured facts](#measured-facts) table with one line per item and a link to the
  evidence file. Commit (docs only).

**Done when:** every M-V row is filled or marked "not applicable by ruling" (M-V6 per Q-V3, M-V10 unless Q-V4 (d)).

### Task T1 — Records

**Files:** `docs/design/DECISIONS.md` (the `OR-` rows for the approval and the Q-V rulings, including the Q-V0,
Q-V10 and Q-V12 rows), `docs/design/backend/decisions.md` (drafts V-1…V-5 with allocated ids),
`docs/design/backend/vm-adapter.md` (ruled banner at the top, as `log-levels.md` has),
`docs/requirements/REQ-005-three-runtimes.md` (trace row), `docs/design/architecture.md` §4 (a pointer from the
`driver` list to the design; no meaning change).

- [ ] **Step 1:** Register each ruling verbatim with its date (`OR-17`). `B/D5`, `OR-62` and `OR-119` are **not
  edited**; the new rows cite them (`I3`).
- [ ] **Step 2:** Allocate `BE-D` ids from the merged register at merge time; replace every
  `«next free BE-D»` in the design with the allocated id. *(Done 2026-10-07: `BE-D58`–`BE-D65`.)*
- [ ] **Step 3:** Record `SM-D21`'s divergence (Q-V7) and the `SM-D11a` reading (design flag 5) as **flagged**,
  citing the rulings, without editing `SM-D21`/`SM-D11a` (`I3`).
- [ ] **Step 4:** Run the markdown link check used by the repo's docs verification and fix any broken link.

---

## S1 — Schema, contract, and the generalization

### Task T2 — Schema, CP, gateway and contract for `ssh-systemd`

**Files:** the two migrations of [allocation](#migration-and-id-allocation) (control, data);
`cp-core/.../target/TargetService.java` (the two-pair check, citing the Q-V10 row);
`cp-api/.../target/TargetView.java` (javadoc); `console-gateway/.../http/TargetTransport.java` (`SSH_SYSTEMD`);
`console-gateway/.../http/TargetController.java` and the `Target` wire record (`allowedUnitPrefix`);
`dp-api/.../inventory/TargetEnvelope.java` (`transport`, `allowedUnitPrefix`);
`dp-core/.../apply/persistence/JdbcTargetConnectionRepository.java` (bind the transport, no literal);
`contracts/openapi/console.yaml`; `contracts/openapi/validate-openapi.py` (check 16); the regenerated Postman
collection; migration ITs; gateway ITs.

- [ ] **Step 1 — failing tests first.** In `dp-core`'s migration IT suite add:
  `sshSystemdTargetRequiresUnitPrefixAndNoContainerPrefix`, `sshDockerTargetUnchanged`,
  `systemdPlacementNeedsUnitNameInsidePrefix`, `systemdPlacementConfigPathEqualsDirectory`,
  `placementCarriesExactlyOneWorkloadName`, `twoLivePlacementsCannotShareAUnitOnOneTarget`,
  `unitNameGrammarRefusesBackslashColonAndNonService`, `unitNameLongerThan128IsRefused`,
  `templateUnitWithoutInstanceIsRefused`, `aSecondAtSignIsRefused`, `connectionInsertStoresTheGivenTransport`.
  In `cp-core`: `vmKindPairsOnlyWithSshSystemd`, `dockerKindPairsOnlyWithSshDocker`. In `console-gateway`:
  `listTargetsSerializesAVmTarget` (one `vm`/`ssh-systemd` target beside a Docker one: `200`, both listed, the
  VM's `allowedUnitPrefix` present; **fails today** with `IllegalArgumentException` from `TargetTransport.fromWire`).
  Run them and confirm they fail for the right reason.
- [ ] **Step 2 — migrations** as the allocation table says. The envelope function is replaced with
  `CREATE OR REPLACE FUNCTION sm_data.placement_inside_target_envelope()`, keeping every existing branch
  byte-identical for `ssh-docker` and adding the `ssh-systemd` branch (design §5.2). Grants: no UPDATE on
  `allowed_unit_prefix` (immutable, as `allowed_container_prefix`); `unit_name` is INSERT-only like every other
  placement column.
- [ ] **Step 3 — CP and gateway, in this task, not later:** `TargetService`'s pair check; `TargetTransport`
  gains `SSH_SYSTEMD`; `TargetEnvelope`/`Target` gain the fields; `JdbcTargetConnectionRepository` binds
  `:transport`. The gateway enum lands with the `sm_control` migration so that no committed state stores a value
  the gateway cannot list.
- [ ] **Step 4 — contract** (Q-V5 (a)): `Target.transport` enum `[ssh-docker, ssh-systemd]` and its description
  (*"`kind: docker` ↔ `ssh-docker`; `kind: vm` ↔ `ssh-systemd`"*); `Target.allowedUnitPrefix` (read-only);
  declare `placement`: `unitName` optional (`pattern` and `maxLength: 128` per design §5.2),
  `containerName`/`containerConfigPath` dropped from `required`, the description stating the per-transport
  requirement and the `422 VALIDATION_FAILED` naming the field; `Instance.placements[]`: `unitName` optional,
  `containerName` optional, **with the description stating that `containerName` is absent for a VM placement (a
  breaking change for clients that assume it, owner-accepted under Q-V5)**. If `d/regapi` has merged, widen its
  registration body's `transport` and add `allowedUnitPrefix` there too (G-G).
- [ ] **Step 5 — pins:** **check 16** in `validate-openapi.py` changes its pin from `['ssh-docker']` to
  `['ssh-docker','ssh-systemd']`, its message citing the Q-V10 row. The DB-to-contract parity is **not** a new
  contracts-side check that reads migrations (that would couple the two toolchains): it is a backend test,
  `TransportVocabularyParityTest` in `dp-core`, that reads `contracts/openapi/console.yaml` and the two CHECK lists
  from the migrations and asserts they agree, with a planted-violation self-test. `allowedUnitPrefix` read-only is
  pinned in check 16's block.
- [ ] **Step 6:** regenerate the Postman collection; `python3 contracts/openapi/postman/check-postman.py`;
  `python3 backend/scripts/check-frozen-migrations.py`; `./contracts/run-checks.sh --strict`; `VERIFY dp-core`,
  `VERIFY cp-core`, `VERIFY console-gateway`. Commit.

### Task T3 — Generalize the SSH placement without changing Docker

**Files** (the 22 files of design §5.4, by role): `apply/runtime/ssh/SshHost.java` (new), `SshPlacement.java` (new,
sealed), `SshPlacementCatalog.java` (renamed from `SshDockerPlacementCatalog`), `SshDockerPlacement.java`
(implements `SshPlacement`, constructor and grammar unchanged), `inventory/TablePlacementCatalog.java`,
`SshDockerRuntimeAdapter.java`, and the consumers `HostConnector`, `SshjHostConnector`, `SshSessionPool`,
`HostProbe`, `HostMetricExec`, `HostMetricProbe`, `HostLogs`, `SshMetricFetcher`, `StartObserver`, `HealthProber`,
`LogServiceImpl`, `LogLevelServiceImpl`, `SshDockerDiagnostics` and `SshDockerConfigEditor` (type rename only;
measured to have no production caller), `ProductionApplyConfiguration` (type only). `SshDockerTransport`/
`SshjDockerTransport` and `TargetConnectionMain` are touched in T4 and T8.

- [ ] **Step 1 — pin Docker first.** Add `SshjDockerTransportCommandPinTest`: every exec string the Docker
  transport can build (inspect, status, restart, stop, logs ×3 shapes, probe, metric curl, metric inspect, the
  directory test) is compared to a literal copied from the tree at `36af1df6`. It passes before any edit.
- [ ] **Step 2:** Introduce `SshHost` and `sealed interface SshPlacement permits SshDockerPlacement,
  SshSystemdPlacement` (the latter a stub record in this task: fields and grammar only, used by nothing yet).
- [ ] **Step 3:** Change the connection consumers (`HostConnector`, `SshjHostConnector`, `SshSessionPool`,
  `HostProbe`, `HostMetricExec`, `HealthProber`, `LogLevelServiceImpl`, the curl half of `SshMetricFetcher`) to take
  `SshHost`/`SshPlacement`. Runtime-specific calls (`docker inspect` in `StartObserver` and `SshMetricFetcher`,
  `docker logs` in `LogServiceImpl`/`HostLogs`) take `SshDockerPlacement` through an exhaustive `switch` over the
  sealed type whose `SshSystemdPlacement` arm throws `UnsupportedOperationException("T6/T7")` **for now**. A test
  asserts that arm is unreachable in production wiring until T5.
- [ ] **Step 4:** `TablePlacementCatalog` reads the DP connection's transport and builds `SshDockerPlacement` for
  `ssh-docker`. For `ssh-systemd` it refuses (`placement.transport.unsupported`) until T5.
- [ ] **Step 5:** Every existing SSH test passes unmodified except listed mechanical renames
  (`SshDockerPlacementCatalog` → `SshPlacementCatalog`, and the parameter type in test doubles). List them in the
  commit message. `VERIFY dp-core`, `VERIFY launcher`, `VERIFY arch-tests`. Commit.

---

## S2 — The adapter and the host-side services

### Task T4 — Extract the SFTP half; set and check the file mode before the rename

**Files:** `apply/runtime/ssh/SftpConfigFiles.java` (new), `SshjDockerTransport.java` (delegates),
`SftpConfigFilesTest.java`.

- [ ] **Step 1:** Move `readRemote`, `writeFileAtomically`, `deleteFile`, `listHiddenFileNames`,
  `verifyDirectory`, `requireDeclaredPath` into `SftpConfigFiles`, keyed by `(SshHost, directory, allowedRoot)`.
  `SshjDockerTransport` delegates. T3's pin test still passes.
- [ ] **Step 2:** *(Dated note, 2026-10-09, X1 review X1-1: the temp file is **created with** the mode in the SFTP create
  request, `sftp.open(path, CREAT|EXCL|WRITE, FileAttributes{permissions})`, not created bare and chmodded; X1's
  `RemoteFiles.create(path, content, mode)` is the shape T4 keeps. X1 also adds `ssh.config.mode.unsupported` (SETSTAT
  rejected) and `ssh.config.mode.unverified` (post-rename stat failed).)* Add an optional mode to `writeFileAtomically` (design §6.3): write the temp file; **`setattr`**
  the mode; **stat the temp file**; on any "other" bit, **delete the temp file and refuse**
  `ssh.config.mode.too.open` **before** the rename; rename; stat the target as a second check. The same path is used
  for the pre-image and fingerprint files. **Docker passes the mode the combined schedule's X1 gave it** (Q-V13 (b),
  ruled), unchanged (Global Constraint 2); the systemd transport passes `0640` (M-V5).
- [ ] **Step 3:** Tests: `aRequestedModeIsSetOnTheTempBeforeTheRename`,
  `aWorldReadableTempIsDeletedAndTheTargetNeverRenamedOnto`, `thePreImageGetsTheSameMode`,
  `aWorldReadableTargetAfterTheRenameIsRefused`, and X1's Docker mode test passing **unmodified** (it replaced
  `noModeRequestedLeavesDockerBehaviourUnchanged`).
  `VERIFY dp-core`. Commit.

### Task T5 — `SshSystemdRuntimeAdapter`, its transport, the routing adapter, the kit

**Files:** `SshSystemdPlacement.java` (complete), `SshSystemdTransport.java` (package-private interface),
`SshjSystemdTransport.java`, `SystemdShow.java` (the §4.3 parser), `SystemdCommands.java` (the one builder of
C1–C6, also used by T9's sudoers generator), `SshSystemdProperties.java`, `SshSystemdRuntimeAdapter.java`,
`apply/runtime/TransportRoutingRuntimeAdapter.java`, `ProductionApplyConfiguration.java`,
`TablePlacementCatalog.java` (the `ssh-systemd` arm), tests below, `PlaneBoundaryTest.java`.

- [ ] **Step 1 — kit subclass first, failing:** `SshSystemdRuntimeAdapterContractTest extends
  AbstractRuntimeAdapterContractTest` over `FakeSystemdTransport` (an in-memory unit with `LoadState`,
  `ActiveState`, `InvocationID`, `ActiveEnterTimestamp`, `FragmentPath`, `NeedDaemonReload`; restart = new id +
  later stamp; stop = `inactive`; every exec recorded; its outputs are T0's measured fixtures). It does not compile
  until Step 3.
- [ ] **Step 2 — systemd-specific tests, failing,** each with its planted violation noted in the test's javadoc
  (design §7): `everyExecIsOneOfTheClosedTemplatesWithItsTimeoutWrapper`,
  `theSudoersArgvAndTheExecutedArgvComeFromOneBuilder`, `theRequestedPropertySetIsExactlyTheNamedSix`,
  `noSecretBearingPropertyIsEverRequested` (sentinels in the fake's `Environment=`/`ExecStart=` never reach
  `RuntimeRef`, `PreBaseline`, `ErrorClass` or a log), `aRestartChangesTheProcessRef`,
  `aZeroActiveEnterTimestampIsNoBaseline`, `aStampWithoutUtcIsRefusedNotGuessed`,
  `belowTheVersionFloorPrepareRefusesAndWritesNothing`, `aSudoRuleThatDoesNotPermitRestartRefusesBeforeAnyWrite`,
  `aPendingDaemonReloadIsHandledPerQV7`, `aUnitThatFailedAtStartUpHasNoBaseline` (pins design §4.5's S3 classifier), `prepareUsesOneConnection`, `aMissingUnitIsAbsent`,
  `aMaskedUnitIsRefusedAtPrepare`, `stopOfAnInactiveUnitIssuesNothing`, `aRestartThatExitsNonZeroIsRestartFailed`,
  `aRestartPastTheBoundIsRestartTimeoutEvenIfTheStreamNeverCloses` (the DP deadline), and, under Q-V11 (b),
  `aTimedOutRestartWhoseJobFinishedIsIssued` and `aTimedOutRestartStillUnknownIsRestartTimeout`.
- [ ] **Step 3 — implement** to design §4–§8: `SystemdShow` (bounded 8 KiB, `Key=Value` map, exactly the requested
  keys, refuse unknown or duplicate keys, the M-V1 stamp layout with the literal ` UTC`, no RFC 3339 parsing);
  `SystemdCommands` (C1–C6 from the unit-name grammar only, each with `timeout --kill-after=2 <n>` outside `sudo`);
  `SshjSystemdTransport` (one connection for `prepare`'s steps and for `status`'s `show` + SFTP read; every exec
  under a DP deadline of `n + 5 s` via `Command.join(deadline)` and closing the channel, as `HostConnection.probe`
  does; `SshSystemdProperties.lifecycleTimeout`, default 120 s); `SshSystemdRuntimeAdapter` (the file operations
  delegate to the same pre-image/fingerprint/prune logic the Docker adapter uses. **Extract that logic into a
  package-private `RetainedConfigFile` helper used by both, with no behaviour change, rather than copy it.** T3's pin
  test and the Docker kit must still pass.)
- [ ] **Step 4 — routing:** `TransportRoutingRuntimeAdapter` resolves the placement's DP transport through
  `SshPlacementCatalog` and delegates; an unknown transport throws `RuntimeAdapterException`
  (`runtime.transport.unsupported`). Its kit subclass runs the inherited kit over a routing instance with one
  fake delegate per transport, plus `eachPlacementReachesExactlyOneAdapter`.
  `ProductionApplyConfiguration.runtimeAdapter` returns the router.
- [ ] **Step 5:** `PlaneBoundaryTest`'s certified inventory gains both new classes, and its planted unkitted-adapter
  test still fires. `VERIFY dp-core`, `VERIFY arch-tests`, `VERIFY launcher`. Commit.

**Not proved by this task:** that the parsers handle real host output beyond T0's captured fixtures. Only T10 proves
that, on one host (design §7).

### Task T6 — Host-side services on a VM: start observer, metrics reset token, health

*(Needs G-D's Q-V6 ruling for Step 4.)*

**Files:** `apply/runtime/ssh/HostMetricProbe.java` (new typed `SystemdShow` command, C2m),
`apply/runtime/ssh/HostMetricExec.java` and `SshSessionPool.java` (a `systemdShow` method, bounded like `inspect`),
`apply/runtime/ssh/HostConnection.java` and its sshj implementation (the matching call), `health/StartObserver.java`,
`metrics/collect/fetch/SshMetricFetcher.java`, `metrics/ingest/IngestService.java` (`detection` reads the transport),
`metrics/ingest/IngestRows.java` (`PlacementContext` gains the transport), `metrics/ingest/CounterSemantics.java`,
**`dp-api/.../metrics/ingest/RestartDetection.java`** (`SYSTEMD`), `console-gateway/.../http/metrics/CatalogComposer.java`
(`detectionOf` per transport), the `sm_metrics` migration, `contracts/openapi/console.yaml` (`restartDetection`
enum, via T2's owner), tests.

- [ ] **Step 1 — failing tests:** `systemdShowIsATypedCommandBuiltFromTheUnitOnly` (the pool refuses anything but
  `Curl`, `Inspect` and `SystemdShow`); `startObserverRecordsAUnitStartFromShow` (C2m's stamp and id become the
  `placement_start_observation` row; an unchanged id writes nothing); `aVmCounterResetIsDetectedByInvocationId`;
  `detectionIsSystemdForAViaSshPollOnAVmPlacement` and `detectionStaysDockerForADockerPlacement` (`IngestService`);
  `catalogDetectionFollowsTheTransport` (`CatalogComposer`); `healthOnAVmUsesTheDeclaredProbeOnly` (no probe gives
  `NOT_APPLICABLE`, `OR-101`).
- [ ] **Step 2:** Add the typed command and the pool/`HostMetricExec` method; replace T3's
  `UnsupportedOperationException` arms in `StartObserver` and `SshMetricFetcher` with the systemd strategy.
- [ ] **Step 3:** `IngestRows.PlacementContext` carries the DP transport; `IngestService.detection` returns
  `SYSTEMD` for a `via-ssh` poll on `ssh-systemd`; `CatalogComposer.detectionOf` does the same from the instance's
  target transport.
- [ ] **Step 4:** `«next sm_metrics V»` widens `signal` with `'systemd'`; `RestartDetection.SYSTEMD` and its
  `CounterSemantics` mapping; the contract enum gains `systemd` (a one-line follow-up in T2's file, reviewed in S2).
- [ ] **Step 5:** `python3 backend/scripts/check-frozen-migrations.py`, `./contracts/run-checks.sh --strict`,
  `check-postman.py`, `VERIFY dp-api`, `VERIFY dp-core`, `VERIFY console-gateway`. Commit.

### Task T7 — Logs on a VM

*(Written for Q-V3 (e), the recommendation. Under (a) Step 2 adds the regex rule and time-window paging; under (c)
or (d) this task is re-written before approval.)*

**Files:** `apply/runtime/ssh/SystemdLogs.java` (C7 builder + parser), `logs/LogServiceImpl.java` and `HostLogs`
(the systemd arm), `SystemdCommands.java` (C7 and its sudoers line), tests.

- [ ] **Step 1 — failing tests:** `journalCommandIsBuiltFromUnitAndConstantLinesOnly`,
  `outputOverTheByteBoundIsRefused`, `theDpFiltersTheTailByTheRequestedWindow`,
  `aShortIsoPreciseLineParsesToTimestampAndMessageWithThePrefixStripped` (the M-V6 layout; neither the hostname nor
  `identifier[pid]` survives), `linesAreNotRedactedAsOnTheDockerLivePath` (parity with `LogServiceImpl`, per
  `LogService`), `theSudoersLineEqualsTheExecutedArgv`.
- [ ] **Step 2:** Implement C7 as `timeout --kill-after=2 <n> sudo -n journalctl -u <u> -o short-iso-precise
  --utc --no-pager -q -n <N>` with a constant `N` (M-V6); the separate-argument form keeps every argument free of
  the characters sudoers would need escaped (design F21). Bounded at 512 KiB, never stored; the page says when the
  window reaches past line `N`.
- [ ] **Step 3:** `VERIFY dp-core`, `VERIFY launcher`. Commit.

---

## S3 — Registration, declare, runbook

### Task T8 — Registering a VM target and declaring a VM placement

*(Needs G-G. Multi-host declare and apply need G-F.)*

**Files:** `cp-core/.../persistence/TargetRegistrationMain.java` (CP operator command: accepts `kind=vm` with
`transport=ssh-systemd`; keeps its Docker defaults), `dp-core/.../apply/persistence/TargetConnectionMain.java`
(DP operator command: `SM_TARGET_TRANSPORT`, `SM_TARGET_ALLOWED_UNIT_PREFIX`), the item (3) registration API service
(the `ssh-systemd` arm and the CP/DP transport-equality refusal), `console-gateway/.../http/DeclareOrchestrator.java`
(`target.transport.mismatch`) and the declare service validation (`unitName` required and
`containerName`/`containerConfigPath` forbidden for `ssh-systemd`, and the converse), `TablePlacementCatalog`,
`console-gateway` ITs.

- [ ] **Step 1 — failing ITs:** `registerVmTargetByOperatorCommands` (CP then DP), `registerVmTargetThroughTheApi`
  (if item 3 merged), `aVmTargetOnAHostAlreadyRegisteredIsRefused` (Q-V9 (a), `OR-89`'s index),
  `declareOnAVmTargetRequiresUnitName` (422 names `unitName`, never echoes the value),
  `declareOnAVmTargetRefusesContainerName`, `aVmPlacementOutsideTheUnitPrefixIs422`,
  `declareRefusesACpDpTransportMismatch` (CP `ssh-systemd`, DP `ssh-docker`, and the reverse),
  `getInstanceShowsUnitNameForAVmPlacement`, `dockerDeclareUnchanged`.
- [ ] **Step 2:** Implement. The three B2 enforcers (service, trigger, adapter pre-flight) all exist for
  `unitName`. The adapter's is `prepare`'s `show` of exactly the declared unit.
- [ ] **Step 3:** With G-F: `aTwoHostVmInstanceAppliesRollingHealthGated` (two fakes behind the router, plan 008's
  identity). `VERIFY cp-core`, `VERIFY console-gateway`, `VERIFY launcher`. Commit.

### Task T9 — The VM runbook and the sudoers generator

**Files:** `docs/runbooks/vm-systemd-target.md` (new), `dp-core/.../SudoersRuleMain.java` (operator command: prints
the sudoers file for one placement from `SystemdCommands`, read-only, no host contact), its test.

- [ ] **Step 1:** `SudoersRuleMain` takes `(tenant, instance, placement)` **and a required `--systemctl-path`**
  (absolute, grammar-checked; the runbook says to read it with `command -v systemctl` on the host), and prints the
  design §6.1 shape plus the Q-V3 log line, and `Defaults:<account> !requiretty` when asked. It **refuses** any
  argument containing `,` `:` `=` `\` (F21) instead of escaping. Test: its output, read back as sudoers argv,
  equals the argv `SystemdCommands` executes, and a planted `:` is refused.
- [ ] **Step 2:** The runbook covers design §9 in order: the customer's unit (with a reference Cache unit from
  M-V7, marked as the customer's file) and `daemon-reload`; **enable/disable** (S9 needs the unit enabled; SM's
  `stop` never disables, so a stopped instance returns after a reboot unless the operator disables it); the
  account, the host key, the directory (`2750`, owner, group), the sudoers file (`visudo -c`, `requiretty`), logs,
  `curl` and `timeout`, `direct-http`'s address restriction (`OR-82`), the ports (health and metrics ports are what
  the MI listens on; distinct per instance; partitioned per organization, `OR-92` OQ-23), the systemd floor and the
  distributions it excludes (Q-V4, design §2.2), and what SM never does on the host (design §3).
- [ ] **Step 3:** Docs link check. Commit.

---

## S4 — Live gate and results

### Task T10 — The live gate on the Q-V8 host

**Files:** `docs/design/backend/evidence/vm-systemd-live-gate.md` (no coordinates).

Run against a real systemd-managed Cache set up by the T9 runbook, through the production launcher and the
Postman collection (`OR-118`'s local stack, pointed at the VM target):

- [ ] **S1** manual apply: file written, nothing restarted, ladder `persisted`; a by-hand `systemctl restart`
  moves it to `restart-observed`.
- [ ] **S3** bad config under `rolling-health-gated`: the gate fails and the request lands **`CONFIG_DIVERGED`**
  (`gate.no.restart` or `gate.unhealthy`, design §4.5) with **no automatic restore** (`OR-64`); the unit is down on the bad file. The operator then
  runs `rollbackApply` (`OR-60`): it restores the prior file, its `VERIFY` row records the restored hash, and after
  a restart the unit runs the prior file.
- [ ] **S4** DP killed during a restart: resume reaches a determinate outcome or `APPLY_INDETERMINATE` with
  evidence, never a second restart.
- [ ] **S5** an edit on the host between observation and rewrite: `reconcile.target.moved`, nothing overwritten.
- [ ] **S6** sudoers line removed: `prepare` refuses, nothing written.
- [ ] **S7** unit masked: refused at `prepare`; `status` reports it.
- [ ] **S8** stop, then stop again (`issued=false`).
- [ ] **S9** (only if the owner permits a reboot) an enabled unit comes back with SM's last file; a stopped-by-SM
  enabled unit reports `RUNNING` afterwards, as the runbook says.
- [ ] **S11** logs: the tail, with the prefix stripped.
- [ ] **S12** accept-runtime read-back.
- [ ] **S13** (only with a second unit) two units on distinct ports and directories; a second declare on the same
  port is refused.
- [ ] **S15** a unit whose start outlasts a lowered bound: the outcome Q-V11 ruled.
- [ ] **S2** (only with G-F and a second VM) two-host rolling apply.
- [ ] **Mode:** the config file and a pre-image are `0640` on the host (`stat`).
- [ ] **Cleanup:** the host's original files are restored and verified by keyed hash (`OR-108`'s T6.6 rule); SM's
  hidden files are removed; the unit, account and sudoers file are removed if the owner asked.

### Task T11 — Results and the combined review

- [ ] **Step 1:** Write this plan's **Results** section: per task, what was built, the commit, and the measured
  outcome. Per finding, the disposition. Never "all closed" (repo lesson `L19`).
- [ ] **Step 2:** The combined Fable 5.1 review (`OR-70`); apply its findings in one fix round, re-run every
  slice's verification, and record dispositions.
- [ ] **Step 3:** Update `README.md`/`backend/README.md` (`OR-114`) to state that VM targets are supported, with
  the floor and the limits (one runtime per host per organization; `dp-remote` only; the logs depth Q-V3 ruled).

---

## Results

*(Empty until T11.)*

## Review

**Adversarial review, 2026-10-07 (design and plan together): 2 BLOCKER, 10 MAJOR, 11 MINOR.** Every finding was
checked against the tree or the fetched source before it was applied; per-finding dispositions are in the design's
[§14](../../design/backend/vm-adapter.md#14-review-dispositions-adversarial-review-2026-10-07). In this plan:
G-B rewritten (B1); T10's S3 rewritten to the built `CONFIG_DIVERGED` + operator `rollbackApply` behaviour and
Global Constraint 1 tied to Q-V11 (B2); T2 gained `TargetService`, check 16, `TargetTransport`/`TargetController`,
`TargetView`, `TargetEnvelope` and `JdbcTargetConnectionRepository`, the gateway enum in the same slice and a
`listTargets` IT (M1, M2); T3 lists all 22 files and T6 gains the typed `SystemdShow` command, the pool method,
`IngestService`/`IngestRows`, `dp-api`'s `RestartDetection`, `CatalogComposer` and `VERIFY dp-api` (M3); T5 specifies
the host-side and DP-side bounds and M-V9 (M4); the unit grammar is ≤ 128, one `@`, no `:` (M5, M6); T9's generator
takes the `systemctl` path and refuses rather than escapes (M6); T4 sets and checks the mode before the rename (M7);
M-V8/M-V10 and the design's §2.2 table (M8); Global Constraint 7 states the breaking response change and T2 runs
`check-postman.py` (M9); Estimates, the critical path and G-H/Q-V12 (M10); and the MINORs m1–m11 as the design
records. **Still due:** the `docs/checklists/plan-review.md` pass before the approval sitting.
