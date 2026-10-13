# D-VM: the plain-VM runtime adapter (`ssh-systemd`)

**Status: PROPOSED, UNBASELINED — RULED by the owner on 2026-10-07 ([`OR-121`](../DECISIONS.md)); plan 014 is APPROVED
AS A PLAN and its implementation is deferred to a later PR into `dev`. Nothing here is built.** Its decisions are
`BE-D58`–`BE-D65` (PROPOSED, §11).

> **Rulings (`OR-121`).** Named in the ruling: **Q-V0 (c)** (a new owner reading beside LOCKED `B/D5`, its text
> unedited: for dp-remote runtime-adapter work the gate is met by the backend Docker end-to-end runs; the screen,
> graph, role and live notify stay required before v1 acceptance — flagged, not ratified); **Q-V4 (a)** (systemd 248
> or later only); **Q-V8** (*"Use same Ec2 host"*: the live gate runs on the E2E host, and `OR-110` is extended to a
> service account, a sudoers file with exact arguments and a systemd unit, each removed after the gate); **Q-V12 (b)**
> (the PR into `dev` goes once plans 008/012/013 are executed and this plan is approved; the VM lands later). Every
> other question takes the recommendation: Q-V1 (a), Q-V2 (a), Q-V3 (e), Q-V5 (a), Q-V6 (a), Q-V7 (a+), Q-V9 (a),
> Q-V10 (a), Q-V11 (b), Q-V13 (b) — the Docker file-mode fix is a separate task before the `OR-115` PR (task X1 of the
> [combined schedule](../../plans/backend/combined-008-012-013.md)). ⚠️ Asked back by name, not assumed: Q-V5's
> explicit acceptance of the breaking response change, and Q-V10's new owner row naming `OR-62` — both before plan 014
> T2. Where the text below says "recommended", read "ruled".

Written 2026-10-07 on branch `d/vm`
off `integration/dp-remote-demo` @ `36af1df6`, for owner work order [`OR-114`](../DECISIONS.md) item (4) and
[`OR-111`](../DECISIONS.md)'s sequence (*"VM also, we need to cover but once multi-placement is done"*). It designs
how SM manages an MI that runs as a **process on a VM under systemd**, reached over SSH, with no Docker. Its plan is
[`014-vm-adapter.md`](../../plans/backend/014-vm-adapter.md) (DRAFT). The owner decides for the architect group
([`OR-69`](../DECISIONS.md)); §12 lists what the owner must rule before plan 014 can be approved. **Revised the same
day after an adversarial review (2 BLOCKER, 10 MAJOR, 11 MINOR); §14 records each finding's disposition.**

**This note edits no other file.** `decisions.md`, `DECISIONS.md`, `architecture.md`, the contract and the README
indexes are the integrator's to change (§13). Decisions drafted here were written under a
`«next free BE-D»` placeholder (§11) and were allocated `BE-D58`–`BE-D65` at the 2026-10-07 integration ([`OR-11`, `OR-17`](../DECISIONS.md)).

**Binding inputs this design implements and does not re-decide:**

| Ruling / decision | What it fixes here |
|---|---|
| [`OR-111`](../DECISIONS.md) | Docker first; then plan 008's multi-placement; then the plain-VM adapter. Kubernetes gets nothing. |
| [`OR-114`](../DECISIONS.md), [`OR-115`](../DECISIONS.md), [`OR-119`](../DECISIONS.md) | This is item (4). Items (1)–(4) close before the PR into `dev`; the owner then tests from `dev`. Q-V12 asks how "close" applies to item (4). |
| [`OR-28`](../DECISIONS.md), [`OR-29`](../DECISIONS.md), [`OR-89`](../DECISIONS.md) | Targets: a CP governance row plus DP connection details. Placements are immutable and unique per target. One live target per host key per organization. |
| [`OR-32`](../DECISIONS.md), [`OR-102`](../DECISIONS.md), [`OR-113`](../DECISIONS.md) | The keyed config hash is computed in the DP over bytes. The retained pre-image carries a keyed fingerprint, per-path digests and a remainder digest, re-checked before use. |
| [`OR-60`](../DECISIONS.md), [`OR-64`](../DECISIONS.md) | `rollbackApply` is the operator's restore. A failed gate and a read-back mismatch land `CONFIG_DIVERGED`, with **no automatic rollback** (`OR-64` Q-G2; `ApplyExecutor`'s gate branch). |
| [`OR-71`](../DECISIONS.md), [`OR-82`](../DECISIONS.md) | Acquisition (`direct-http` / `via-ssh`) is per instance. No address ever comes from the browser. |
| [`OR-101`](../DECISIONS.md), [`OR-112`](../DECISIONS.md) (1) | No declared probe means `NOT_APPLICABLE`. An ungated apply reaches `restart-observed` from a host-clock start later than the write's own host-clock baseline. |
| [`BE-D12`](decisions.md), [`BE-D21`](decisions.md), [`BE-D22`](decisions.md), [`BE-D24`](decisions.md), [`BE-D25`](decisions.md), [`BE-D34`](decisions.md), [`BE-D35`](decisions.md), [`BE-D39`](decisions.md) | The `RuntimeAdapter` seam as built: ten operations (`replicas`, `prepare`, `materializeConfig`, `rematerializeIfCurrent`, `startOrUpdate`, `stop`, `status`, `assessRollback`, `restore`, `readConfig`) and the contract kit `AbstractRuntimeAdapterContractTest`. |
| [`BE-D23`](decisions.md) and plan 008 | Today's engine is single-placement. A VM instance on N hosts is N placements, so multi-host VM applies need plan 008. |
| `SM-D11` (group-confirmed), `SM-D11a` | Least-privilege accounts that *write only the external config dir and restart only that service — not root*. Pinned host keys, argument vectors and no string concatenation (read as in flag 5), SFTP temp-then-rename, canonical-path checks, **restricted `sudoers`**. |
| `SM-D2`, `SM-D3`, `SM-D15`, `SM-D19`, `SM-D21` | SM owns configuration only. Apply means replacing the file and restarting. Restart is operator-selected and `manual` by default. Lifecycle verbs are declared. The config path is per runtime and checked by pre-flight. |
| `I1`, `I2`, `I5`, `I6`, `I7` | No MI development. Facts are verified. Tenant scope stays server-side. No secret reaches config storage. The plane boundary holds. |

**Flagged, not resolved:**

1. **`B/D5` 🔒 bars this work today, on the record.** Its gate: *"one generated screen, one graph, one role, one
   Docker `service` deploy with health + telemetry, accepted end-to-end — must pass before any systemd/K8s/scheduled/
   File-Mesh work begins"*. Plan 010 T6.6 says, in its scope, *"Must not claim: browser live notify, plan 007's
   event-driven skeleton complete, B/D5's skeleton, multi-placement"*, and its live-run Results repeat *"Not claimed:
   … B/D5's skeleton"*. Plan 011 P11-4.4 claims nothing about `B/D5`. No ruling records the skeleton as passed.
   `OR-94` read `B/D5` as not holding plan 011's broker-push path, on the ground that the path was not File-Mesh work.
   The VM adapter **is** systemd work in `B/D5`'s own words, so a reading that the gate does not hold it would run
   against the text. **Q-V0** puts this to the owner with priced options.
2. **`OR-62`** rules *"v1's one pair is `transport: ssh-docker` ⇒ `kind: docker`"*. The tree enforces it in three
   places: `TargetService` (*"v1 supports exactly one target pair"*), contract check 16 (`validate-openapi.py` pins
   `Target.transport` to `['ssh-docker']` as an owner-ruled shape), and the `sm_control` CHECK. A VM target needs a
   second pair, which **amends `OR-62`**. Under `I3` that is a new owner row, with `OR-62` left unedited (**Q-V10**).
3. **A timed-out restart lands `CONFIG_DIVERGED` today.** The engine closes any `RuntimeAdapterException` from
   `startOrUpdate` as `FAILED` and lands `CONFIG_DIVERGED` at once (`ApplyExecutor`, the `startOrUpdate` catch). That
   is a terminal claim made while the systemd job may still be finishing on the host. Changing that routing is an
   engine change, which plan 014's Global Constraint 1 forbids without the owner's ruling (**Q-V11**).
4. **`SM-D21`** says a launch-command mismatch **blocks** enrollment. §6.4 shows why a strict check would refuse
   correctly configured units, and recommends an advisory check (Q-V7). That recommendation is a proposal against
   a proposal; it is flagged here.
5. **`SM-D11a`** says *"No shell … argument vector — never string concatenation"*. sshj's `exec` sends one string
   that the remote account's shell parses. This design follows the Docker transport's precedent: the string is a
   **fixed template** whose only variable parts are grammar-checked values that contain no shell metacharacter
   (§8). That is a **reading** of the PROPOSED `SM-D11a`, not its letter, and it is flagged as one.
6. **`OR-89`'s host-key uniqueness** makes a host that runs both Docker MIs and systemd MIs registrable once only,
   with one transport. §5.3 and Q-V9 deal with this.
7. **The PROPOSED `SM-D-era v1` row** orders the runtimes *"Docker + `dp-remote` … then `host-local` → systemd →
   Kubernetes"*. `OR-111` and `OR-114` put the `dp-remote` VM adapter **before** `host-local` (the agent gateway).
   The owner's later rulings stand. The `SM-D-era v1` row is not edited.

---

## 1. The running scenarios

Every option in §12 is run through the same scenarios. Host names are illustrative.

| # | Scenario |
|---|---|
| **S1** | **First apply, one VM, `manual`.** `cache-engine.service` runs Cache as `java -jar … --spring.config.additional-location=/etc/solace/cache/sm/`. The operator applies a TTL change. SM writes the file and restarts nothing. Later the operator runs `systemctl restart` by hand, and the ladder must reach `restart-observed`. |
| **S2** | **`rolling-health-gated` across three VMs** (needs plan 008). Per host: write, restart, gate on the declared probe, then the next host. |
| **S3** | **Bad config.** The new file makes Cache exit at start-up. The unit is `Type=simple`, so `systemctl restart` exits 0. The health gate must catch it. As built, a failed gate lands `CONFIG_DIVERGED` and **does not restore** (`OR-64` Q-G2, `SM-D15`). The operator then runs `rollbackApply` (`OR-60`), which restores the prior file; its `VERIFY` row records the restored hash. |
| **S4** | **DP crash mid-restart.** The DP dies between journalling the `LIFECYCLE` intent and reading the result. Resume must decide from evidence alone. |
| **S5** | **Somebody edits the file on the host** between SM's observation and SM's write. SM must not overwrite it silently. |
| **S6** | **The sudoers rule is missing or wrong** (host rebuilt, rule mistyped). SM must refuse **before** writing anything. |
| **S7** | **The unit is gone** (renamed or removed). |
| **S8** | **Operator stops the instance.** |
| **S9** | **The VM reboots.** The unit starts again on boot with the file SM last wrote. |
| **S10** | **The host's clock steps** (NTP correction) around a restart. |
| **S11** | **Operator reads logs** for the instance. |
| **S12** | **Accept-runtime** reads the live file back (`BE-D39`). |
| **S13** | **Two instances on one VM** (two units, two config directories, two ports). |
| **S14** | **One VM runs a Docker Cache and a systemd Cache.** |
| **S15** | **The restart outlasts its bound** (a slow `Type=notify` start, or a wedged `systemctl`). |

---

## 2. Verified external facts (I2)

Fetched 2026-10-07. **WebFetch returned HTTP 403 for `freedesktop.org`.** The same URLs were then fetched with
`curl` (HTTP 200) and searched as text, and the systemd project's own man sources
(`github.com/systemd/systemd/main/man/*.xml`) and `man7.org` were used as cross-checks. The pages document
**systemd 262**. Each "Added in version" below is the page's own note. The sudo pages are from `sudo.ws`
(documenting sudo 1.9.x). **Anything not in this table is either measured in plan 014 T0 (§2.1) or not claimed.**

| # | Fact | Source (exact text, abridged only by ellipsis) |
|---|---|---|
| F1 | `systemctl show` is the machine interface; `status` is for humans. | systemctl(1), `show`: *"This command is intended to be used whenever computer-parsable output is required. Use status if you are looking for formatted human-readable output."* `status`: *"This function is intended to generate human-readable output."* |
| F2 | `-p/--property=` limits output to named properties; `--value`/`-P` print values only. | systemctl(1): *"limit display to properties specified in the argument. The argument should be a comma-separated list of property names"*. `--value` added in 230, `-P` in 246. |
| F3 | `--timestamp=` changes the printed timestamp format: `pretty` (default) `"Day YYYY-MM-DD HH:MM:SS TZ"`, `us+utc` `"Day YYYY-MM-DD HH:MM:SS.UUUUUU UTC"` (added in 248), `unix` `"@seconds-since-the-epoch"` (added in 251). The option itself was added in 247. | systemctl(1), `--timestamp=`. |
| F4 | A remote host's local-timezone timestamps are not reliably parsable elsewhere. | systemd.time(7): *"timestamps displayed by remote systems with a non-matching timezone are usually not parsable locally, as the timezone component is not understood (unless it happens to be UTC)."* |
| F5 | `restart`: *"Stop and then start one or more units … If the units are not running yet, they will be started."* `try-restart`: *"… if the units are running. This does nothing if units are not running."* | systemctl(1). |
| F6 | By default systemctl waits for the job. | systemctl(1), `--no-block`: *"Do not synchronously wait for the requested operation to finish. If this is not specified, the job will be verified, enqueued and systemctl will wait until the unit's start-up is completed."* |
| F7 | "Started" depends on `Type=`. `simple`: *"the service manager will consider the unit started immediately after the main service process has been forked off"*. `exec`: *"…immediately after the main service binary has been executed"*. `notify`: start-up completes when the service sends `READY=1`. | systemd.service(5). **So for `Type=simple` or `exec`, a zero exit from `restart` says nothing about whether the MI came up** (S3). |
| F8 | Exit codes follow LSB: 0 active, 3 not active, 4 no such unit. The page warns: *"it is better to not rely on those return values but to look for specific unit states and substates instead."* | systemctl(1), Exit status. |
| F9 | `ActiveState` ∈ `active`, `inactive`, `failed`, `activating`, `deactivating`, `maintenance`, `reloading`, `refreshing`. `SubState` is finer-grained, *"not documented here, and … more likely to be extended later"*. | org.freedesktop.systemd1(5). |
| F10 | `LoadState` (D-Bus text): `loaded`, `error`, `masked`. systemctl's LOAD column: `loaded`, `not-found`, `bad-setting`, `error`, `masked`. | org.freedesktop.systemd1(5); systemctl(1). |
| F11 | `ActiveEnterTimestamp`/`…Monotonic` *"contain CLOCK_REALTIME and CLOCK_MONOTONIC 64-bit microsecond timestamps of the last time a unit … entered the active state … The fields are 0 in case such a transition has not yet been recorded on this boot."* | org.freedesktop.systemd1(5). |
| F12 | `ExecMainStartTimestamp` *"record[s] when the main process of the service is created"*. It *"deviates for services with Type=forking"*, and the `ExecMain*` fields *"either contain information of the last run of the process or of the current running process."* | org.freedesktop.systemd1(5). |
| F13 | Invocation ID: *"a randomized, unique 128-bit ID identifying each runtime cycle of the unit, formatted as 32 character hexadecimal string. A new ID is assigned each time the unit changes from an inactive state into an activating or active state"*. Added in 232. | systemd.exec(5), `$INVOCATION_ID`. The `InvocationID` property is listed on the Unit object in org.freedesktop.systemd1(5). |
| F14 | Read access to unit state is unprivileged. Changing unit state needs `org.freedesktop.systemd1.manage-units` through polkit. | org.freedesktop.systemd1(5), Security: *"Read access is generally granted to all clients."* and *"Operations which modify unit state (StartUnit(), StopUnit(), … RestartUnit() and similar …) require org.freedesktop.systemd1.manage-units."* |
| F15 | Unit names: the prefix is ASCII letters, digits, `:`, `-`, `_`, `.`, `\`, plus a single `@` for templates. The total length is at most 255. Types include `.service`. | systemd.unit(5): *"The total length of the unit name including the suffix must not exceed 255 characters."* |
| F16 | Reading the system journal is restricted: *"only root and users who are members of a few special groups are granted access … Members of the groups "systemd-journal", "adm", and "wheel" can read all journal files."* | journalctl(1). **Group membership grants every unit's logs**, not one unit's. |
| F17 | `journalctl -u/--unit=` filters by unit; `-S/--since=`/`-U/--until=` are inclusive (*"on or newer"*, *"on or older"*); `-n/--lines=` keeps the most recent N; `-o short-iso-precise` (added in 234); `--utc` (added in 217); `--no-pager`; `-q/--quiet`. | journalctl(1). The exact line layout is measured (M-V6), not taken from the page. |
| F18 | A time given as `@<integer>` is *"evaluated relative to the UNIX epoch – 1970-01-01 00:00:00 UTC"*. | systemd.time(7). |
| F19 | sudoers: *"If a Cmnd has associated command line arguments, the arguments in the Cmnd must match those given by the user on the command line."* With no arguments listed, any arguments are allowed. Wildcards in arguments *"should be used with care … it is safer to use a regular expression"*. *"Starting with version 1.9.10, it is possible to use regular expressions for path names and command line arguments."* | sudoers(5), sudo.ws (WebFetch, 2026-10-07). |
| F20 | `sudo -n`: *"Avoid prompting the user for input of any kind. If a password is required for the command to run, sudo will display an error message and exit."* `sudo -l <command>`: if permitted, *"the fully-qualified path to the command is displayed along with any args."* `NOPASSWD` lifts the default authentication requirement. `requiretty`: *"If set, sudo will only run when the user is logged in to a real tty … This flag is off by default."* | sudo(8), sudoers(5). The upstream default is off; a distribution may set it, so M-V4 measures it. |
| F21 | sudoers argument escaping: *"Unless a regular expression is specified, the following characters must be escaped with a '\' if they are used in command arguments: ',', ':', '=', '\'."* | sudoers(5), sudo.ws (WebFetch, 2026-10-07). **§5.2's unit grammar admits none of the four**, so the generated rule needs no escaping (§6.1). |
| F22 | systemd formats displayed timestamps in the local time zone, and the page sets that zone through the environment in its own example (*"assuming … the timezone was UTC+8, for example `TZ=:Asia/Shanghai`"*). | systemd.time(7): *"On display, systemd will format these in the local timezone"*. Whether a `TZ=UTC` prefix makes `systemctl show` on a pre-248 host print UTC is **not** claimed; it is M-V10. |

### 2.1 Not verified here; plan 014 T0 measures each before the task that needs it

| Id | Question | Why it matters |
|---|---|---|
| **M-V1** | Does `--timestamp=us+utc` change the `ActiveEnterTimestamp=` line of `systemctl show` (F3 says "printed timestamps")? What exactly is printed for a never-active unit (F11's `0`)? The exact format, which the parser accepts and nothing else. | §4.4's start token. |
| **M-V2** | `systemctl show` with several `-p`: output order, and the output plus exit status for a unit that does not exist. | §4.3 parser; S7. |
| **M-V3** | `InvocationID` changes on `systemctl restart` and on reboot (F13 implies both). | §4.4; S4, S9. |
| **M-V4** | `sudo -n -l <argv>` exit status and output when permitted, when not, and when a password would be needed; that the bare name `systemctl` resolves to the path the rule names; whether the host sets `Defaults requiretty` (sshj's `exec` allocates no tty); that a rule line with an `@` in the unit argument matches unescaped. | §6.1, §6.2 pre-flight; S6. |
| **M-V5** | The mode of a file after an explicit SFTP `setattr` on the temp file under OpenSSH's `sftp-server`, and setgid-directory group inheritance. | §6.3; I6. |
| **M-V6** | `sudo -n journalctl …` under the rule shape Q-V3 picks: the exact line layout of `short-iso-precise --utc` (hostname and `identifier[pid]:` prefix), and that `--since=@<s>` is accepted. | §4.7 logs. |
| **M-V7** | Cache under systemd: a reference unit with `--spring.config.additional-location`, its actuator on loopback, and its start-up time with `Type=simple` versus `exec`. | S1–S3, the live gate. |
| **M-V8** | The systemd and sudo versions on the hosts the owner names (live-gate host **and** the customer distributions Q-V4 asks for), measured on the host, because a distribution may backport or patch. | §2.2's table is the package version only. |
| **M-V9** | `timeout --kill-after=2 <n> sudo -n systemctl restart <unit>` when `<n>` expires: sudo's exit status, whether `systemctl` dies, and whether the systemd **job still completes** (`InvocationID` changes afterwards). | §4.5, S15. |
| **M-V10** | Only if Q-V4 picks (d): `TZ=UTC systemctl show -p ActiveEnterTimestamp <unit>` on a pre-248 host prints a stamp ending in ` UTC`, and its exact format. | §4.4's fallback. |
| **M-V11** | `systemctl show -p FragmentPath,NeedDaemonReload <unit>` output, and that `NeedDaemonReload=yes` appears after editing a unit file without `daemon-reload`. | §6.2 step 5, Q-V7. |

### 2.2 Which distributions meet each floor (package versions, fetched 2026-10-07)

Sources: `packages.ubuntu.com` and `packages.debian.org` (WebFetch), `repology.org/project/{systemd,sudo}/versions`
(curl), and the AWS *AL2023.12 all-packages* release-notes page (curl). **Red Hat Enterprise Linux itself was not
fetched.** AlmaLinux and Rocky Linux rebuild it, and their rows are shown as rebuild rows, not as RHEL facts. A
package version says what upstream release a distribution started from; it does not prove a backported option
behaves as upstream documents. M-V8 measures the hosts that matter.

| Distribution | systemd | `us+utc` (≥ 248) | `unix` (≥ 251) | sudo | sudoers regex (≥ 1.9.10) |
|---|---|---|---|---|---|
| Ubuntu 20.04 (focal) | 245.4 | no | no | 1.8.31 | no |
| Ubuntu 22.04 (jammy) | 249.11 | yes | no | 1.9.9 | **no** |
| Ubuntu 24.04 (noble) | 255.4 | yes | yes | 1.9.15p5 | yes |
| Debian 11 (bullseye) | 247.3 | no | no | 1.9.5p2 | no |
| Debian 12 (bookworm) | 252.39 | yes | yes | 1.9.13p3 | yes |
| Debian 13 (trixie) | 257.13 | yes | yes | 1.9.16p2 | yes |
| AlmaLinux 8 / Rocky Linux 8 (RHEL 8 rebuilds) | 239 | no (no `--timestamp=` at all, upstream 247) | no | 1.9.5p2 | no |
| AlmaLinux 9 / Rocky Linux 9 (RHEL 9 rebuilds) | 252 (Alma also lists 250) | yes | yes | 1.9.17p2 (CentOS Stream 9 also lists 1.9.5p2) | yes on the current package |
| CentOS Stream 10 | 257 | yes | yes | 1.9.17 | yes |
| Amazon Linux 2 | 219 | no | no | 1.8.23 | no |
| Amazon Linux 2023 (AL2023.12) | 252.23 | yes | yes | 1.9.15p5 | yes |
| openSUSE Leap 15.5 / 15.6 | 249.17 / 254.27 | yes / yes | no / yes | 1.9.12p1 / 1.9.15p5 | yes / yes |

**What this means for the owner's questions:** a 248 floor (Q-V4 (a)) excludes the RHEL 8 family, Ubuntu 20.04,
Debian 11 and Amazon Linux 2. A sudoers regex rule for the journal (Q-V3 (a)) excludes the same four families plus
**Ubuntu 22.04**, a common customer host.

---

## 3. What "apply" means on a VM

SM owns **one file** at a **declared directory** on the host (`SM-D3`, `SM-D21`). For systemd the workload sees
the same path SM writes, because there is no container boundary. Apply works as it does on Docker:

1. **Pre-flight** (§6.2), before every apply, never once at enrollment, on **one** SSH connection.
2. **Materialize:** capture the pre-image with its `OR-102`/`OR-113` fingerprint, write the rendered bytes to a
   temp file in the same directory, set its mode explicitly and check it **before** the rename (§6.3), rename it
   over the target, read it back, and compare the keyed hash computed in the DP (`OR-32`). The SFTP code path is the
   Docker adapter's, shared rather than copied (§5.4).
3. **Lifecycle**, only under an operator-selected restart strategy (`SM-D15`): `sudo -n systemctl restart <unit>`
   on one host, bounded on the host and on the DP (§4.5). The verb is fixed, not a free-form command (Q-V2).
4. **Observe:** a read-only `systemctl show` (§4.3) supplies liveness and the start token. Health comes **only**
   from the package's declared probe, through the same `HostProbe`/`DirectHttpProbe` as Docker (`OR-101`).

**What SM never does on a VM** (`I1`, `SM-D2`, least privilege): write or edit a unit file or drop-in, run
`daemon-reload`, `enable`, `disable`, `mask`, `kill`, `set-property`, edit an `EnvironmentFile=`, install packages,
or replace the MI's jar. The unit is **the customer's deployment configuration**. The one-time launch-spec change
(pointing `--spring.config.additional-location` at the SM directory) is fair game under `I1` because it is
deployment configuration, but **the customer makes it**, and the runbook documents it (§9).

---

## 4. The seam, operation by operation

The adapter is `SshSystemdRuntimeAdapter implements RuntimeAdapter`, beside `SshDockerRuntimeAdapter` in
`dp-core`'s `apply.runtime.ssh` package. **No seam operation is added, removed or re-typed, and no engine file
changes** (but see flag 3 and Q-V11). A placement has exactly **one replica, the unit**. `ReplicaRef` is the unit
name (`cache-engine.service`), inside `BE-D19`'s grammar, and `RuntimeRef` uses keys that already exist in the
closed set: `UNIT_NAME`, `HOST`, `MOUNT_PATH` (`RuntimeRef.java`: *"systemd unit, for the VM adapter"*). Every
`RuntimeRef` value is checked against `ValueGrammar.VALUE` `^[A-Za-z0-9._:/@+-]{1,128}$`, which is why §5.2 caps the
unit name at 128 characters. There is no new key and no image digest.

### 4.1 Operation table

| Operation (`BE-D*`) | Docker over SSH (built) | systemd over SSH (this design) |
|---|---|---|
| `replicas` (`BE-D25`) | one replica from the catalog, no round trip | the same: one replica, `ReplicaRef(unitName)`, from the catalog |
| `prepare` | `docker inspect` succeeds; config dir canonical, inside the root, writable | §6.2, on one connection: systemd version ≥ floor; unit `LoadState=loaded`; dir canonical, inside the root, writable; `sudo -n -l` permits the exact restart and stop argv; launch evidence (Q-V7). **Mutates nothing.** `healthSignalDeclared` = a probe is declared (`OR-101`). |
| `materializeConfig` | pre-image + fingerprint, SFTP temp → atomic rename, read-back, keyed hash, prune | **identical**, plus an explicit `setattr` mode on the temp file and a check of it **before** the rename (§6.3) |
| `rematerializeIfCurrent` (`BE-D35`) | re-read, refuse `reconcile.target.moved`, write | **identical** |
| `startOrUpdate` | `docker restart` | `timeout --kill-after=2 <n> sudo -n systemctl restart <unit>`, blocking (F6), with a DP-side deadline as backstop (§4.5). Returns `issued=true`. |
| `stop` | `docker stop` if running, else `issued=false` | `show` first: `inactive`/`failed` gives `issued=false` (kit idempotence); otherwise the bounded `sudo -n systemctl stop <unit>` |
| `status` | `docker inspect` + SFTP read, one connection | `systemctl show` (§4.3) + SFTP read, one connection; liveness from §4.2; baseline from §4.4; health from the declared probe |
| `assessRollback` (`BE-D22`) | pre-image + `OR-102` check, classify against two journal-known hashes | **identical** (file logic only) |
| `restore` (`BE-D22`) | re-check pre-image and target hash, write | **identical** |
| `readConfig` (`BE-D39`) | bounded SFTP read, keyed hash | **identical** |

**Seven of ten operations are runtime-neutral file logic.** That is the argument for §5.4's refactor: share the
file half, and keep only `prepare`, `startOrUpdate`, `stop` and `status` runtime-specific.

### 4.2 Liveness mapping (F9, F10)

| Observed | `Liveness` | Baseline |
|---|---|---|
| `LoadState=not-found` (or the M-V2 equivalent) | `ABSENT` | `null` |
| `LoadState` ∈ `masked`, `error`, `bad-setting` | `STOPPED` (and `prepare` refuses `ssh.systemd.unit.not.loaded`) | `null` |
| `ActiveState` ∈ `active`, `reloading`, `refreshing` | `RUNNING` | §4.4 |
| `ActiveState` ∈ `inactive`, `failed`, `activating`, `deactivating`, `maintenance` | `STOPPED` | `null` |

`activating` maps to `STOPPED` because the Docker adapter reports `running=false` until the container is up, and
`RuntimeStatus` says a `null` baseline is the `APPLY_INDETERMINATE` trigger on resume, never a reason to restart
again. `SubState` is never interpreted (F9 says it is undocumented and extensible). It is not even requested.

### 4.3 The status read, and the one other read `prepare` makes

```
timeout --kill-after=2 <n> systemctl show --timestamp=us+utc --property=LoadState,ActiveState,InvocationID,ActiveEnterTimestamp <unit>
```

- **Unprivileged** (F14). It needs no sudo, so the most frequent command adds nothing to the host's policy.
- **The property set is closed and named.** `status` asks for these four. `prepare` makes one more read, of
  `FragmentPath` and `NeedDaemonReload` (C2b, §8). Those six are the whole set: it never reads `Environment=`,
  `ExecStart=` or `EnvironmentFile=`, which can carry secrets. A property never read cannot leak into
  `runtime_ref`, a log line or an error class (`I6`). The kit's sentinel test (§7) pins the set.
- The parser reads `Key=Value` lines into a map (order is M-V2), refuses unknown keys and duplicates, bounds the
  output (8 KiB, as Docker's inspect), and maps any malformed line to `ssh.systemd.show.invalid`.

### 4.4 The start token: `ActiveEnterTimestamp` in UTC plus `InvocationID`

The ladder's ungated route (`OR-112` (1), V018/V020) compares **a start the host reported** with **the start the
host reported when the write was read back**, on the host's own clock. Docker supplies `State.StartedAt`. On systemd:

- `PreBaseline.startedAt` = `ActiveEnterTimestamp` (F11, `CLOCK_REALTIME`, microseconds) printed under
  `--timestamp=us+utc` (F3). F3 gives the format as `Day YYYY-MM-DD HH:MM:SS.UUUUUU UTC`, which is **not** RFC 3339.
  The parser accepts exactly the M-V1-measured layout, requires the literal ` UTC` zone, and converts it to an
  `Instant`; anything else is `ssh.systemd.show.invalid`. `0` or empty means not recorded on this boot, so the
  baseline is `null`, as Docker's `0001-01-01`.
- `PreBaseline.processRef` = `InvocationID` (F13: 32 hex characters, new on every inactive → activating
  transition, so it is expected to be new on every restart and boot; M-V3 confirms it). `PreBaseline` checks
  `processRef` against `ValueGrammar.VALUE` `^[A-Za-z0-9._:/@+-]{1,128}$`, which 32 hex characters satisfy. (The
  V020 `write_baseline` CHECK allows 255; the Java grammar is the tighter one.)

**Why `ActiveEnterTimestamp` and not `ExecMainStartTimestamp`:** F12 says the latter deviates for `Type=forking`
and may describe *the last run* of a process that is gone. `ActiveEnterTimestamp` is defined for every service
type, and under `Type=notify` it is set only after `READY=1` (F7), which is closer to "the new file is loaded".
For `simple`/`exec` both are within milliseconds of the fork.

**Why a UTC stamp, and the four ways to get one.** The default `pretty` format prints the host's local time zone
(F3, F22), and systemd itself says such stamps are generally not parsable elsewhere (F4). Parsing them would mean
guessing the host's zone, which is the class of confident guess `I2` forbids. The ways to get a UTC stamp, and the
hosts each leaves out (§2.2), are Q-V4's options: `us+utc` (≥ 248), `unix` (≥ 251), or a constant `TZ=UTC` prefix on
older hosts (M-V10), which prints seconds only.

**Precision matters because the engine compares by equality.** As built, resume and the health gate compare
`startedAt` for **equality** with the recorded baseline (`ResumePlanner.java:120`, `ApplyExecutor.java:1061`,
`HealthGate.java:93`), and the ladder compares by "different and later than". An equal stamp reads as **no
restart**. With microseconds, two activations would have to fall in the same microsecond to collide. With
seconds (the `TZ=UTC` fallback), a restart inside the same wall second as the previous activation collides:

- **S1 (ladder):** stays at `persisted`. Safe: nothing claims a load that is unproven.
- **S2/S3 (gate):** the gate sees no restart, waits out its budget, and fails `gate.no.restart`: `CONFIG_DIVERGED`
  for a restart that did happen. Operator-visible, not silent.
- **S4 (resume):** `ResumePlanner` answers `REISSUE`: **a second restart**, the one outcome its rule exists to
  avoid. It needs the previous activation less than a second before the restart, which happens with a
  crash-looping unit (`Restart=` with a sub-second `RestartSec=`) or two back-to-back restarts.

The `InvocationID` would tell the cases apart, but the engine does not compare `processRef`. Making it do so is an
engine change (Global Constraint 1), so it is not proposed here. `prepare` reads `systemctl --version` and refuses
below the ruled floor with `ssh.systemd.version.unsupported`.

**Clock steps (S10).** Both values are the host's `CLOCK_REALTIME`. A backward step across a restart could make a
real restart look earlier than the write baseline, so `restart-observed` would not be reached. That is the safe
direction: the ladder stays at `persisted`. A forward step cannot make an unrestarted unit look restarted, because
a new `ActiveEnterTimestamp` exists only after a real activation. Resume and the gate compare for equality, so any
changed stamp reads as a restart, whatever the step. `processRef` is recorded as evidence and is not compared by the
engine. That F13's new-ID-per-activation holds across `restart` and reboot is M-V3.

### 4.5 `startOrUpdate`: blocking, bounded, and not a health verdict

`sudo -n systemctl restart <unit>` waits for the job by default (F6). A `Type=notify` start-up may take as long as
the MI needs (F7), so the adapter gets its own **lifecycle bound** `n` (a new `SshSystemdProperties.lifecycleTimeout`,
default 120 s).

**How the bound is enforced.** The 15 s `SSHClient.setTimeout` in `SshjDockerTransport` does **not** bound an exec:
the tree records as measured (W2-03, `HostConnection`/`HostMetricProbe` javadoc) that *"sshj's channel streams have
no read timeout"*, and `exec` reads with `readNBytes` and calls `join()` with no deadline. Two bounds are therefore
specified, as the Docker metrics inspect already does:

1. **On the host:** every systemd command is prefixed with a constant `timeout --kill-after=2 <n>` placed
   **outside** `sudo` (§8, C1–C7). sudo matches the argv it is given (`systemctl restart <unit>`), so the sudoers rule
   is unchanged by the wrapper. M-V9 measures what a TERM to `sudo` does to `systemctl` and to the job.
2. **On the DP:** the exec runs under a deadline of `n + 5 s` (`Command.join(deadline)`, then closing the channel),
   as `HostConnection.probe` does. This is the backstop if `timeout` itself does not return.

**Outcomes, as the engine handles them today** (`ApplyExecutor`, the `startOrUpdate` catch and the gate branch):

| What happens | Adapter result | Engine outcome as built |
|---|---|---|
| Exit 0 (the job finished) | `issued=true` | gated: the health gate decides (F7: under `simple`/`exec` exit 0 is not health). Gate passes → read-back → `APPLIED`; gate fails → `CONFIG_DIVERGED`, no restore (`OR-64`, S3; classifier below); health unreadable → `APPLY_INDETERMINATE`. Ungated: `restart-requested`, read-back, next replica. |
| Non-zero exit (the start job failed) | `ssh.systemd.restart.failed` (Docker's `ssh.docker.command.failed`) | the attempt closes `FAILED` and the request lands **`CONFIG_DIVERGED`** |
| The bound passes (S15) | `ssh.systemd.restart.timeout` | **`CONFIG_DIVERGED`** at once: a terminal claim while the job may still complete on the host. Docker has the same routing today for any `docker restart` failure. |

**S3's classifier on a VM.** `HealthGate` names a failure `gate.unhealthy` when it saw a changed start and an
unhealthy probe, and `gate.no.restart` when it saw no changed start. A `Type=simple` unit that crashes at start-up
ends `failed`, which §4.2 maps to `STOPPED` with a `null` baseline, so the gate's last word is **`gate.no.restart`**,
not `gate.unhealthy`, although a restart did happen; a unit with `Restart=` seen between crashes while `active`
gives `gate.unhealthy`. The terminal state (`CONFIG_DIVERGED`) is right either way; the classifier can mislead an
operator. Keeping the baseline for a `failed` unit would fix it, but departs from Docker's `null`-when-not-running
parity that `RuntimeStatus` documents; plan 014 T5 records the behaviour in a test and the runbook explains it,
and it is not changed here.

Resume and its start-token comparison apply only to a DP crash (S4), not to a timeout. **Q-V11** asks whether
the timeout row is acceptable, whether the adapter should first look again before throwing (no engine change), or
whether the engine should route a lifecycle timeout to `APPLY_INDETERMINATE` (an engine change).

`--no-block` was considered and rejected. It returns before the start, so the first `status` would often read
`activating` with a `null` baseline, and a DP crash in that window would push routine restarts into
`APPLY_INDETERMINATE`.

### 4.6 Health and metrics

Health is **unchanged in mechanism**. The package's declared probe is read through `via-ssh` (curl on the VM's
loopback, the existing closed command) or `direct-http` (the target's registered address, `OR-82`). On a VM
`healthPort` is the port the MI actually listens on, because there is no publish mapping (S13, §4.9).
`NOT_APPLICABLE` holds with no probe (`OR-101`). `curl` must exist on the host for `via-ssh`, as for Docker today.

Metrics path 1 is runtime-neutral **except** its counter-reset token, and the token is wired to Docker in four
places, all of which change:

- `HostMetricProbe` builds the only lifecycle-shaped command the session pool runs: a typed `Inspect` over the
  `docker inspect` grammar. `SshSessionPool` accepts only a `Curl` or an `Inspect` value (SM-D11a), and
  `HostMetricExec` exposes `curl` and `inspect`. A VM needs a **new typed command** (`SystemdShow`, C2 with only
  `InvocationID,ActiveEnterTimestamp`), a pool method and a `HostMetricExec` method for it.
- `IngestService.detection` maps every `via-ssh` poll to `RestartDetection.DOCKER` regardless of transport. It must
  read the placement's transport (`IngestRows.PlacementContext` gains it).
- `RestartDetection` lives in **`dp-api`** (`dp-api/.../metrics/ingest/RestartDetection.java`), and
  `CounterSemantics` maps it to the stored signal.
- `console-gateway`'s `CatalogComposer.detectionOf` restates the same rule for the catalog (`via-ssh` ⇒ `"docker"`);
  it must see the target's transport too.

The stored signal needs a value for a VM: Q-V6 decides between widening the vocabulary with `systemd` and the
alternatives. Path 2 (broker push, `OR-107`) has no runtime dependency.

### 4.7 Logs (S11)

`readLogs` and `recentLogs` on Docker run `docker logs`. On a VM the logs are in the journal or in a file, and
reading the journal is **not** unprivileged (F16). The source is the subject of **Q-V3**. Whatever is chosen:

- The command is closed and built only from a grammar-checked unit name, an `int` line bound and integer epoch
  seconds (F18, `--since=@<s>`). Fractional bounds are widened to whole seconds and the DP trims, as
  `OR-108` (5) already does for Docker windows.
- The output is bounded (512 KiB, as Docker), parsed in the DP into `(timestamp, message)` lines, and never stored.
  A `short-iso-precise` line carries the hostname and `identifier[pid]:` before the message; the parser **strips
  that prefix** to the M-V6-measured layout, so neither the host name nor the PID reaches the page.
- **Redaction parity is with the live log path, which does not redact.** `LogService` (`dp-api`) says *"The lines
  are the managed integration's own output; SM does not redact them"*, and `LogServiceImpl` follows it.
  `SshDockerDiagnostics` carries a `SENSITIVE_LINE` redaction, but it has no production caller (measured: only its
  own test and `ApplyPathBoundaryTest` reference it). The I6 residual is Docker's: a log line may carry whatever the
  MI printed. This is a read path with the existing `logs.read` verb, not a new exposure class.
- `OR-108` (5)'s backward paging by bounded windows carries over unchanged (`--since=@(before−W) --until=@before`),
  except under Q-V3 (e), which has no time bounds.

### 4.8 Pre-image retention and the `OR-102` fingerprint

The pre-image, fingerprint and absence files keep **their names and grammar**
(`.<file>.sm-preimage-<g>`, `.<file>.sm-fp-<g>`, `.<file>.sm-preimage-<g>.absent`) in the declared directory.
Retention pruning (APPLY-02) and the `OR-113` remainder digest are unchanged. A pre-image holds the plaintext of
every secret the file held, so it gets the same explicit mode and pre-rename check as the config file (§6.3).
**The Docker adapter sets no mode today**, so its config file and pre-images are created under the host account's
umask. That is an existing `I6` gap on the built path; **Q-V13** puts the fix to the owner.

### 4.9 Stop, reboot, and two instances on one VM (S8, S9, S13)

- **S8 then S9.** SM's `stop` runs `systemctl stop` and never `disable` (§3). An **enabled** unit therefore starts
  again on the next boot, and `status` reports `RUNNING` for an instance SM last stopped. That is the truth about
  the host, and the runbook says so: an operator who wants a stopped instance to stay stopped across reboots
  disables the unit themselves.
- **S9's own premise** (the unit comes back with SM's file) holds only if the customer **enabled** the unit. A
  disabled unit stays down after a reboot and `status` reports `STOPPED`. The runbook states the requirement.
- **S13.** Two units on one VM have two config directories and two ports. There is no publish mapping, and SM's
  overlay writes no port (`OverlayKeys`: *"It writes NO management port"*). So `healthPort` and each
  `metricsPorts` entry on a VM are **the ports the MI actually listens on**, which the customer's own configuration
  sets. The per-target unique indexes (`placement_health_port_once`, the config-directory index, and `OR-110`'s
  metrics-port rule) refuse two placements that declare the same port or directory. SM cannot see what port an MI
  listens on, so a **mis-declared** port probes whatever answers there. That residual is the same as a wrong publish
  mapping on Docker. Organizations sharing one VM partition ports by the runbook rule (`OR-92` OQ-23).

---

## 5. The target and placement model

### 5.1 Today (measured in the tree at `36af1df6`)

- `sm_control.target`: `kind IN ('docker','kubernetes','vm')`, `transport IN ('ssh-docker')` (V013).
- `cp-core`: `TargetService.register` refuses any pair but `docker`/`ssh-docker` (*"v1 supports exactly one target
  pair"*, `OR-62`); `TargetRegistrationMain` defaults `kind` to `docker` and `transport` to `ssh-docker`.
  `cp-api`'s `TargetView` documents `ssh-docker` as the one value.
- `console-gateway`: `TargetTransport` has only `SSH_DOCKER`, and `fromWire` throws `IllegalArgumentException`
  ("unknown target transport"). `TargetController.listTargets` maps every listed target through it, so **one
  `ssh-systemd` row would make `listTargets` fail for its whole organization**. (There is no `getTarget` operation
  in `console.yaml`; `listTargets` is the one target read.)
- `sm_data.target_connection`: `transport = 'ssh-docker'`, `allowed_container_prefix NOT NULL` (V007).
  `JdbcTargetConnectionRepository.insert` hard-codes `'ssh-docker'` in its SQL. `dp-api`'s `TargetEnvelope` carries
  `allowedConfigRoot` and `allowedContainerPrefix` only.
- `sm_data.placement`: `container_name NOT NULL`, `container_config_path NOT NULL`, plus unique live indexes per
  target on container, config directory and health port (V007, `OR-29`).
- `console.yaml`: `Target.transport` enum `[ssh-docker]`, pinned by check 16 (`validate-openapi.py`, *"Target.kind
  keeps docker/kubernetes/vm beside transport ssh-docker (Q-C3)"*). The declare body requires `containerName` and
  `containerConfigPath`. `Instance.placements[]` requires `containerName`.
- `sm_metrics` V001 `signal IN ('declared','docker','inferred')`; contract `restartDetection` enum the same.

### 5.2 The delta

| Where | Change | Migration / file |
|---|---|---|
| `sm_control.target` | `transport IN ('ssh-docker','ssh-systemd')`, plus a pairing CHECK: `(kind='docker' AND transport='ssh-docker') OR (kind='vm' AND transport='ssh-systemd')`. Existing rows satisfy it. Needs Q-V10 (the `OR-62` amendment). | new `sm_control` migration (integrator numbers it; V013 is frozen, `OR-22`/`OR-105` (e)) |
| `cp-core`, `cp-api` | `TargetService`'s one-pair check becomes the two-pair check; `TargetRegistrationMain` keeps its defaults and accepts the `vm`/`ssh-systemd` pair; `TargetView`'s javadoc names both. | `TargetService.java`, `TargetRegistrationMain.java`, `TargetView.java` |
| `console-gateway` | `TargetTransport` gains `SSH_SYSTEMD("ssh-systemd")`, **in the same slice as the `sm_control` migration**, so no state exists in which a stored value cannot be listed. `Target` gains `allowedUnitPrefix`. `DeclareOrchestrator` refuses a declare whose CP `target.transport` differs from the DP connection's transport (m10, below). | `TargetTransport.java`, `TargetController.java`, `DeclareOrchestrator.java` |
| `sm_data.target_connection` | `transport IN ('ssh-docker','ssh-systemd')`. `allowed_container_prefix` becomes nullable and a new `allowed_unit_prefix` is added; a CHECK requires exactly the one that matches the transport. Both stay immutable (no UPDATE grant). `JdbcTargetConnectionRepository.insert` binds the transport instead of the literal. `TargetEnvelope` gains `transport` and `allowedUnitPrefix`. | new `sm_data` migration; `JdbcTargetConnectionRepository.java`, `TargetEnvelope.java` |
| `sm_data.placement` | `container_name` becomes nullable; new `unit_name text NULL`; CHECK exactly one of `container_name`/`unit_name`. **Unit grammar:** `^[A-Za-z0-9][A-Za-z0-9_.@-]{0,119}\.service$` (**at most 128 characters in total**, so it fits `ValueGrammar.VALUE`), **plus** at most one `@`, and not `@.service` (a template with no instance cannot be restarted). Against F15 it drops `\` (which reaches a shell string) and `:` (which sudoers would need escaped, F21). The envelope trigger is replaced (`CREATE OR REPLACE` in the new migration) so that `ssh-systemd` requires `unit_name` inside `allowed_unit_prefix` **and** `container_config_path = remote_config_directory` (the workload sees the host path). New index `placement_unit_once (tenant_id, target_id, unit_name) WHERE retired_at IS NULL`. The same grammar is enforced in the declare service and the contract (`pattern`, `maxLength: 128`). | same migration; declare service; `console.yaml` |
| `sm_metrics` | signal vocabulary per Q-V6 | new `sm_metrics` migration |
| `console.yaml` | `Target.transport` gains `ssh-systemd`; `Target.allowedUnitPrefix`; the declare `placement` and `Instance.placements[]` per Q-V5; `restartDetection` per Q-V6. **Check 16's pin changes** from `['ssh-docker']` to `['ssh-docker','ssh-systemd']`, citing the Q-V10 ruling. The Postman collection is regenerated and `check-postman.py` run. No new operation, so `ENDPOINT-INVENTORY.md` gets no row. | `contracts/openapi/console.yaml`, `validate-openapi.py`, `postman/` |
| Registration | `TargetConnectionMain` and item (3)'s registration API (being designed on `d/regapi`) accept `transport: ssh-systemd` with `allowedUnitPrefix`. | coordination, §13 |

**Which plane's transport is read where (m10).** The CP `target.transport` and the DP `target_connection.transport`
are written independently, with no cross-plane reference (`I7`). The **DP row is the one that drives behaviour**: the
catalog, the routing adapter and the adapter pre-flight read only `target_connection.transport`. The CP value is
governance and display. A mismatch is refused where both are visible, in the gateway, which composes both planes
through their ports: `DeclareOrchestrator` (it already reads the CP target and the DP envelope) refuses with
`target.transport.mismatch`, and item (3)'s registration API refuses at registration. `TargetConnectionMain`, a
DP-only operator command, cannot read the CP row; the runbook pairs the two commands, and the declare refusal is
the backstop.

`ConfigLocation(containerPath, RemoteSftp(dir), fileName, mechanism)` needs no change: for systemd
`containerPath` is the host directory, which is what the workload reads (`BE-D24`'s distinction is writer access,
not container-ness).

### 5.3 One host, two runtimes (S14)

`target_connection_host_once` (`OR-89`) allows one live target per host key per organization, and a target has one
transport. A VM that runs a Docker Cache **and** a systemd Cache therefore cannot be registered twice. The options
(Q-V9) are: (a) state the limit (one runtime per host per organization); (b) widen the index to include the
transport, which silently defeats the per-host collision indexes (config directory, health port), because they
are per target; (c) move the runtime from the target to the placement, which changes `Target`'s contract, `OR-28`'s
governance shape and every per-target index. Recommended: **(a) now**, revisit with a customer case. The design
loses nothing by deferring (c).

### 5.4 Inside `dp-core`: a sealed SSH placement, one SFTP half

`grep -rl SshDockerPlacement` over `backend/*/src/main` finds **22 files**. Each is listed with the task that changes
it (plan 014):

| File | What it does with the placement | Change | Task |
|---|---|---|---|
| `SshDockerPlacement` | the record | implements `SshPlacement`; constructor and grammar byte for byte | T3 |
| `SshDockerPlacementCatalog` | lookup | renamed `SshPlacementCatalog`, returns `SshPlacement` | T3 |
| `inventory/TablePlacementCatalog` | builds placements from rows | builds per the DP transport; refuses a row whose columns do not match it | T3, T5 |
| `SshDockerRuntimeAdapter` | the Docker adapter | narrows to `SshDockerPlacement` from the sealed type | T3, T5 |
| `SshDockerTransport`, `SshjDockerTransport` | Docker exec + SFTP | SFTP half extracted to `SftpConfigFiles` | T4 |
| `HostConnector`, `SshjHostConnector` | open a pooled connection | take `SshHost` | T3 |
| `SshSessionPool` | probe, curl, inspect | take `SshHost`; **new** `systemdShow` method | T3, T6 |
| `HostProbe` | loopback probe | takes `SshHost` | T3 |
| `HostMetricExec` | `curl`, `inspect` | take `SshHost`; **new** `systemdShow` | T3, T6 |
| `HostMetricProbe` | builds `Curl`, `Inspect` | **new** typed `SystemdShow` command | T6 |
| `HostLogs` | bounded log read | takes `SshPlacement`; systemd arm | T3, T7 |
| `metrics/collect/fetch/SshMetricFetcher` | curl + reset token | switch on the sealed type for the token | T3, T6 |
| `health/StartObserver` | start observation | switch on the sealed type | T3, T6 |
| `health/HealthProber` | health poll | takes `SshHost` | T3 |
| `logs/LogServiceImpl` | log page | switch on the sealed type | T3, T7 |
| `logs/LogLevelServiceImpl` | log-level read | takes `SshPlacement` (file and loopback only) | T3 |
| `SshDockerDiagnostics` | old redacted log reader, **no production caller** | type rename only; no systemd arm | T3 |
| `SshDockerConfigEditor` | old Cache field editor, **no production caller** | type rename only; no systemd arm | T3 |
| `apply/persistence/TargetConnectionMain` | operator registration command | transport and unit-prefix inputs | T8 |
| `apply/production/ProductionApplyConfiguration` | wiring | wires the router | T5 |

Outside `dp-core`'s SSH package, the metrics reset token also touches `metrics/ingest/IngestService`,
`IngestRows`, `CounterSemantics`, `dp-api`'s `RestartDetection` and `console-gateway`'s `CatalogComposer` (§4.6, T6).

The shape:

- `SshHost(tenant, host, port, user, credentialRef, pinnedHostKeyFingerprint)` holds the connection half, which is
  what the loopback probe, the session pool and SFTP need.
- `sealed interface SshPlacement permits SshDockerPlacement, SshSystemdPlacement` exposes `host()`, the config
  location and retention. `SshDockerPlacement` keeps its constructor and grammar **byte for byte**.
- `SshPlacementCatalog.lookup(PlacementRef) → SshPlacement`. `TablePlacementCatalog` builds whichever the DP
  connection's transport names, and refuses a row whose columns do not match it (a second enforcer beside the DB
  CHECK).
- `SftpConfigFiles`: the SFTP half of `SshjDockerTransport` (`readFile`, `writeFileAtomically` + mode,
  `deleteFile`, `listHiddenFileNames`, `verifyDirectory`), extracted unchanged and used by both transports.
- Runtime-specific reads (the start token, logs) go through one small strategy per transport, chosen from the
  sealed type with a `switch` and no default (so a third transport is a compile error, not a silent fallthrough).

**The engine keeps its single `RuntimeAdapter`.** A `TransportRoutingRuntimeAdapter` implements the seam and
delegates each call to the Docker or systemd adapter according to the placement's DP transport. It holds no logic
of its own, and it passes the kit by delegation plus a routing test (§7). The alternative, an adapter registry the
engine consults, changes engine code at every call site for no gain.

---

## 6. Privilege on the host (SM-D11, SM-D11a)

### 6.1 The account

A dedicated, non-root SSH account per host (the runbook calls it `sm-agent`; the name is the customer's), with a
pinned host key and a key-only login. It needs exactly four things:

| Need | Mechanism | Privileged? |
|---|---|---|
| Read unit state | `systemctl show` | **No** (F14) |
| Write the config file and SM's hidden files | Filesystem permission on the **one** declared directory: owned by `sm-agent`, group = the MI's service group, mode `2750` (setgid so new files take the group; M-V5) | No sudo |
| Restart and stop **one** unit | `sudoers`, exact argv, `NOPASSWD` (F19, F20) | Yes, two commands |
| Read the unit's logs | per Q-V3 | depends |

**No root login, no shell sudo, no `ALL`, no wildcards in the restart/stop rules.** Example (illustrative; an SM
operator command generates it per placement, and `prepare` proves the host matches it):

```
# /etc/sudoers.d/solution-manager  (customer-owned; checked with visudo -c)
Cmnd_Alias SM_CACHE_ENGINE = /usr/bin/systemctl restart cache-engine.service, \
                             /usr/bin/systemctl stop cache-engine.service
sm-agent ALL=(root) NOPASSWD: SM_CACHE_ENGINE
```

**The generator (`SudoersRuleMain`).** It has no host contact, so it cannot discover `systemctl`'s path. The path is
a **required operator input** (absolute, grammar-checked), which the runbook tells the operator to read on the host
(`command -v systemctl`). The unit argument comes from the same builder the transport executes (`SystemdCommands`).
Because §5.2's grammar admits none of `,` `:` `=` `\` (F21), the argument needs no escaping, and the generator
**refuses** any argument containing one rather than escaping it, so "printed argv = executed argv" holds literally.
`@` is not in F21's list; M-V4 confirms an `@` argument matches unescaped.

**How the path is proved.** SM invokes the bare name through `sudo -n`. `prepare`'s `sudo -n -l systemctl restart
<unit>` returns the fully-qualified path sudo matched (F20). If the operator printed a rule with a different path,
sudo does not match it and `prepare` refuses `ssh.systemd.sudo.not.permitted` (S6). The path sudo reports is kept
as evidence. That the bare name resolves the way the rule expects (`secure_path`) is M-V4.

**`requiretty`.** sshj's `exec` allocates no tty. The upstream default is off (F20), but a distribution may set
`Defaults requiretty`. M-V4 measures it on the named hosts. The runbook adds `Defaults:sm-agent !requiretty` to the
generated file when the host sets it, and `prepare`'s `sudo -n -l` fails visibly if it is still in force.

### 6.2 Pre-flight (`prepare`), all read-only, in this order, on one connection

`prepare` opens **one** SSH connection and runs every step on it, as `statusSnapshot` does, rather than the
Docker transport's `withClient` per call (about six handshakes per host per apply otherwise).

1. `systemctl --version` must report ≥ the floor (Q-V4); otherwise `ssh.systemd.version.unsupported`.
2. §4.3's `show` must report `LoadState=loaded`; otherwise `ssh.systemd.unit.not.loaded` (S7). `ABSENT` is not
   ready: `replicas()` still returns the one catalog replica, and the kit's "`replicas` agrees with `prepare` for a
   ready placement" clause is the only one that applies.
3. `verifyDirectory` (the canonicalized directory is inside `allowed_config_root`) and `test -d … && test -w …`:
   the existing commands, unchanged.
4. `sudo -n -l systemctl restart <unit>` and `sudo -n -l systemctl stop <unit>` must both be permitted (M-V4);
   otherwise `ssh.systemd.sudo.not.permitted` (S6). **No write happens if any check fails.**
5. Launch evidence (Q-V7), C2b: `FragmentPath` and `NeedDaemonReload`. `NeedDaemonReload=yes` means the unit file
   on disk was edited and not reloaded, so a restart would run the **stale** `ExecStart=`, and a customer's
   `additional-location` change would silently not apply while the ladder still reached `restart-observed`. Q-V7
   asks whether that refuses (`ssh.systemd.unit.reload.pending`) or is reported as evidence. Both properties are
   unprivileged and carry no secret.

The kit's `prepareNeverMutatesTheTarget` covers 1–5 with the transport double. That `sudo -l` writes to the host's
sudo log is logging, not target state, and the runbook says so.

### 6.3 The file on disk holds secrets (I6)

The rendered file carries every secret SM resolved, as on Docker. On a VM other local users exist, so nothing
secret-bearing may reach the target path with an "other" bit set, not even for a moment:

1. Write the temp file in the declared directory, **created with `0640`** (the SFTP create request carries the attributes;
   a create without them is `0666&~umask` and would hold the secret world-readable until step 2).
   *(Dated note, 2026-10-09, X1 review X1-1: the Docker half, task X1, was corrected to this; the VM half does the same.)*
2. **Set its mode explicitly** with an SFTP `setattr` to `0640`. The mode is not left to create-time attributes and
   `sftp-server`'s umask.
3. **Stat the temp file before the rename.** If any "other" bit is set, **delete the temp file and refuse**
   `ssh.config.mode.too.open`. Nothing reached the target, so the refusal is a `MATERIALIZE` failure with the old
   file still in place; it routes to `BE-D22`'s exhaustion journal as any `MATERIALIZE` failure does, and no
   secret-bearing byte was ever readable at the target path.
4. Rename over the target, then stat the target as a **second** check.

The pre-image and fingerprint files follow the same four steps (§4.8). M-V5 measures `setattr` against the host's
`sftp-server`. The kit gains these as systemd-specific tests. Whether Docker gets the same is Q-V13.

### 6.4 `SM-D21`'s launch-spec check, and why it is not blocking

`SM-D21` wants pre-flight to inspect the launch command and block on a mismatch. On systemd the place to look is
`ExecStart=` plus `Environment=`/`EnvironmentFile=`. But (i) those properties can carry secrets, so reading them
widens `I6`'s surface; (ii) an `EnvironmentFile=`'s **contents** are not visible through `systemctl show`, so a
correct unit that sets `SPRING_CONFIG_ADDITIONAL_LOCATION` there would be refused; (iii) the Spring precedence trap
`SM-D21` was written about cannot be fully evaluated from a command line anyway. Q-V7 offers three postures.

### 6.5 Polkit, and why it is not recommended

F14 shows systemd can grant `manage-units` to an unprivileged user through polkit, which would avoid sudo. Whether
a polkit rule can be scoped to **one unit and one verb** depends on polkit's rule API and the action details
systemd passes. **That was not verified here** (no polkit page was fetched), so this design does not rely on it.
`SM-D11a` names restricted `sudoers` explicitly, and sudo's exact-argv matching is verified (F19). Q-V1 records polkit
as an option the owner could ask to have verified.

---

## 7. The contract kit the adapter must pass

`SshSystemdRuntimeAdapterContractTest extends AbstractRuntimeAdapterContractTest` over a deterministic
`FakeSystemdTransport`. Every inherited obligation applies unchanged:

| Kit obligation (as named in the tree) | How the systemd adapter meets it |
|---|---|
| `prepareIsReadyAndNamesEveryReplicaItWillAddress`, `prepareNeverMutatesTheTarget`, `replicasAgreesWithPrepareAndMutatesNothing` | §6.2's read-only checks; one replica |
| `materializeWritesTheBytesAndReportsTheirHash`, `theAdapterReportsBackTheHashItsOwnHasherComputes`, `materializeHasZeroLifecycleEffect`, `materializeIsIdempotentUnderTheSameGenerationAndBytes` | shared `SftpConfigFiles`; no `systemctl` command in the write path (the fake records every exec) |
| `rematerializeIfCurrent…` (five) | shared logic |
| `startOrUpdateMovesTheStartBaselineAndNothingElse`, `startOrUpdateNeverChangesTheArtifact` | the fake's restart moves `InvocationID` and `ActiveEnterTimestamp`; no file is touched |
| `stopIsIdempotentAndReportsWhetherItActed` | `show` first; `issued=false` when already inactive |
| `theHealthSignalIsNeverInventedWhileTheReplicaIsDown` | as on Docker: no declared probe gives `NOT_DECLARED`; a declared probe on a `STOPPED` unit gives `UNHEALTHY` without spending a session on the probe |
| `assessRollback…`, `aRestoreTargetOfAbsence…`, `aValueThisApplyNeverWrote…`, `restore…` (six) | shared logic, including the `OR-102` checks |
| `readConfig…` (four) | shared logic |

**Systemd-specific tests, each with the planted violation that turns it red:**

1. **Closed command set.** Every exec string the adapter can emit equals one of §8's templates, wrapper included;
   planting a `daemon-reload`, dropping the `timeout` wrapper or adding an unquoted extra argument fails.
2. **The sudoers argv equals the executed argv.** The generator and the transport build the restart/stop argv from
   one function, and the generator refuses any argument containing an F21 character; changing either fails.
3. **No secret property is read.** The fake's `show` output carries sentinel secrets in `Environment=` and
   `ExecStart=`; the test asserts the requested property set is exactly §4.3's six and that no sentinel appears in
   `RuntimeRef`, `PreBaseline`, an `ErrorClass` or a log line (the `I6` content obligation `RuntimeRef.java`
   assigns to each adapter).
4. **Start token.** A restart changes `processRef`; an `ActiveEnterTimestamp=` of `0`/empty gives a `null`
   baseline; a stamp without the literal ` UTC` is refused, never guessed.
5. **Version floor and sudo refusal** refuse in `prepare` and write nothing.
6. **Mode.** A temp file whose mode has an "other" bit after `setattr` is deleted and refused, and the target is
   never renamed onto.
7. **Bounds.** A restart past the bound yields `ssh.systemd.restart.timeout` at the DP deadline even when the fake
   never closes its stream.
8. **Routing.** `TransportRoutingRuntimeAdapter` sends each `PlacementRef` to exactly one adapter, refuses an
   unknown transport with a classifier, and is itself listed in `PlaneBoundaryTest`'s certified inventory with its
   kit subclass.

`PlaneBoundaryTest`'s certified set gains the two new classes. An arch rule confines `sshj` to the `ssh` package,
as today.

**What the fake cannot prove (m6).** Every test above runs against `FakeSystemdTransport`, so the parsing of real
`systemctl show`, `sudo -n -l` and `journalctl` output is proved **only by the live gate (plan 014 T10), on one
host**. The fixtures the parsers are tested against are copied from T0's measured outputs, which narrows the gap
but does not close it. A Testcontainers IT with a systemd + sshd + sudo image would close more of it, but it needs
an image approved by exact tag under `OR-67`'s rule, and a systemd-in-container image needs privileges the build
does not use today. It is not planned; Q-V8 records it as an option.

---

## 8. The closed command set

Built only from: the unit name (§5.2 grammar), the config directory (existing grammar), the bound `n` (an `int`
from configuration), an `int` line bound and integer epoch seconds. Nothing else reaches the exec string, and none
of these can contain a shell metacharacter (flag 5).

| # | Command (exact template; `<n>` is the lifecycle bound for C5/C6 and the 15 s operation bound otherwise) | Privilege | Used by |
|---|---|---|---|
| C1 | `timeout --kill-after=2 <n> systemctl --version` | none | `prepare` |
| C2 | `timeout --kill-after=2 <n> systemctl show --timestamp=us+utc --property=LoadState,ActiveState,InvocationID,ActiveEnterTimestamp <unit>` | none | `prepare`, `status`, `stop`, start observer |
| C2b | `timeout --kill-after=2 <n> systemctl show --property=FragmentPath,NeedDaemonReload <unit>` | none | `prepare` (Q-V7) |
| C2m | `timeout --kill-after=2 <n> systemctl show --timestamp=us+utc --property=InvocationID,ActiveEnterTimestamp <unit>` | none | metrics reset token (`HostMetricProbe.SystemdShow`) |
| C3 | `timeout --kill-after=2 <n> sudo -n -l systemctl restart <unit>` / `… stop <unit>` | sudo policy read | `prepare` |
| C4 | `test -d <dir> && test -w <dir>` | none | `prepare` (existing, unchanged) |
| C5 | `timeout --kill-after=2 <n> sudo -n systemctl restart <unit>` | sudoers line 1 | `startOrUpdate` |
| C6 | `timeout --kill-after=2 <n> sudo -n systemctl stop <unit>` | sudoers line 2 | `stop` |
| C7 | logs, per Q-V3, with the same wrapper | per Q-V3 | `readLogs` |
| C8 | `curl …http://127.0.0.1:<port><path>` | none | health, log level, metrics (existing `HostProbe`/`HostMetricProbe`, `--max-time` bound) |

Under Q-V4 (b) the `--timestamp=` value is `unix`; under (d) C2/C2m drop `--timestamp=` and gain a constant
`TZ=UTC ` prefix (after `timeout … `, before `systemctl`). Every exec also runs under the DP-side deadline of §4.5.

SFTP operations (read, temp create, `setattr` mode, stat, `posix-rename`, remove, list, canonicalize) are the
existing ones plus `setattr`.

---

## 9. What the customer prepares (the runbook, plan 014)

1. A systemd unit for the MI, **written by the customer**, whose launch makes the MI read SM's file (for Cache:
   `--spring.config.additional-location=<dir>/`, so a later `.yml` wins under Spring's ordering, which `SM-D3`
   covers). After editing it: `systemctl daemon-reload` (otherwise `NeedDaemonReload=yes`, §6.2 step 5).
2. Whether the unit is **enabled**: required for S9 (it comes back after a reboot); and that SM's `stop` does not
   disable it, so a stopped instance comes back after a reboot unless the operator disables it (§4.9).
3. The SM account, its authorized key, and the pinned host-key fingerprint.
4. The config directory (owner, group, mode `2750`) inside the root the target is registered with.
5. The `sudoers.d` file, generated by `SudoersRuleMain` from the placement and the host's `systemctl` path, never
   hand-composed; `visudo -c`; `!requiretty` for the account when the host sets `requiretty`.
6. Log access per Q-V3.
7. `curl` and `timeout` (coreutils) on the host; `curl` for `via-ssh`, or the health port reachable from the DP for
   `direct-http` (with `OR-82`'s residual: restrict it to the DP's address).
8. The MI's ports: `healthPort` and `metricsPorts` are the ports the MI listens on; two instances need two ports;
   organizations sharing a host partition ports (`OR-92` OQ-23).
9. The supported systemd/sudo floor (Q-V4, Q-V3) and the distributions it excludes (§2.2).

---

## 10. Invariant audit

| Invariant | Holds because |
|---|---|
| **I1** | SM writes one config file and runs two systemctl verbs. The unit, its drop-ins, its environment and the jar stay the customer's. The launch-spec line is deployment configuration that the customer applies. No new MI endpoint and no MI build change. |
| **I2** | §2's table is quoted from the fetched pages; §2.2's versions are fetched package versions, labelled as such; everything else is an M-V item measured in plan 014 T0 before use. Polkit, RHEL itself and backported behaviour are explicitly not claimed. |
| **I5** | Lookups bind `(tenant, instance, placement)`. The unit name, directory and prefixes come from rows the registrant fixed (B2: service, DB trigger, adapter pre-flight). Nothing about the host comes from the browser (`OR-82`). |
| **I6** | Hashes are computed in the DP (`OR-32`). `runtime_ref`/`pre_baseline` hold a unit name, host, directory, an invocation id and a timestamp. Secret-bearing properties are never requested (§4.3). The file and its pre-images never reach the target path with an "other" bit (§6.3). Logs are read, not stored. Docker's existing gap is Q-V13. |
| **I7** | DP changes land in `dp-core`/`dp-api`; CP changes in `cp-core`/`cp-api` (`sm_control` CHECK, `TargetService`, `TargetRegistrationMain`, `TargetView`); the gateway, which already composes both planes through their ports, gets the enum and the mismatch check. No cross-schema reference, no CP import of DP code or the reverse; the two transports are compared only in the gateway (§5.2). |
| **SM-D11 / SM-D11a** | No root; restart/stop of one unit by exact argv; writes confined by filesystem permission to one directory; pinned host key; fixed exec templates with grammar-checked values (a reading, flag 5); SFTP temp-then-rename; canonical-path check. **Per-operation audit with host-key fingerprint and argv** is `SM-D11a`'s last row. Today's Docker path should be checked for parity, and the VM path matches whatever it has (§13). |

---

## 11. Draft decisions (ids allocated 2026-10-07: `BE-D58`–`BE-D65`)

### `BE-D58` (draft V-1): the plain-VM runtime is reached over SSH as transport `ssh-systemd`, paired with target kind `vm`

A target of kind `vm` is reached only by `ssh-systemd`: pinned-host-key SSH, SFTP for the one config file, and the
closed systemctl command set of §8. A placement on such a target names a `.service` unit inside the target's
immutable `allowed_unit_prefix`. The adapter is `SshSystemdRuntimeAdapter`, certified by the contract kit, and the
engine reaches it through a routing adapter keyed by the DP connection's transport. **Rejected:** a local-exec
systemd driver (only `host-local` needs that, and that locus is the Go agent's); free-form lifecycle commands
(Q-V2). **Rests on** the owner's `OR-62` amendment (Q-V10). It adds a second implementation of `BE-D12`'s
`RuntimeAdapter` operations; it does not change those operations.

### `BE-D59` (draft V-2): a systemd replica's start token is `ActiveEnterTimestamp` in UTC and its `InvocationID`

`PreBaseline.startedAt` is `ActiveEnterTimestamp` printed as a UTC stamp (the format Q-V4 rules); `processRef` is
`InvocationID`. A zero or empty stamp is no baseline. The adapter refuses hosts below the ruled floor and never
parses a local-zone stamp. **Rejected:** `ExecMainStartTimestamp` (deviates for `forking`, may describe a past run);
parsing `pretty` in the host's zone (F4); monotonic time (resets on boot, so not comparable with the V020 write
baseline across S9).

### `BE-D60` (draft V-3): on a VM, SM's account is unprivileged except for two exact-argv sudo rules per unit

Reads use unprivileged `systemctl show`. Writes are confined by filesystem permission to the declared directory.
Restart and stop run through `sudo -n` against `sudoers` lines with **exact arguments**, generated by SM from an
operator-supplied `systemctl` path and proven by `prepare`'s `sudo -n -l` before every apply. No root login, no
wildcard, no shell. This is `SM-D11`/`SM-D11a` made concrete for systemd; it supersedes nothing.

### `BE-D61` (draft V-4): secret-bearing files are given an explicit mode and checked before they reach the target path

The rendered file and SM's retained copies get their mode by an explicit SFTP `setattr` on the temp file, which is
stat-checked before the rename and deleted with a refusal if any "other" bit is set; the target is stat-checked
again after. Scope (VM only, or VM and Docker) per Q-V13.

### `BE-D62` (draft V-5): one runtime per host per organization in v1

The consequence of `OR-89` for mixed hosts is stated, not worked around (Q-V9 (a)).

### `BE-D63` (draft V-6, Q-V3 (e)): VM logs are one exact-argv `journalctl` tail of a constant line count

`readLogs` on `ssh-systemd` runs one more exact sudoers line, `journalctl -u <unit> -o short-iso-precise --utc
--no-pager -q -n <N>` with a constant `N`, in the separate-argument form; the DP filters the lines by time, so paging
back stops at line `N`. A sudoers regex rule (Q-V3 (a)) may be added later where sudo ≥ 1.9.10.

### `BE-D64` (draft V-7, Q-V6 (a)): the metrics reset-signal vocabulary gains `systemd`

`restartDetection` and the `sm_metrics` signal CHECK gain the additive value `systemd`; stored rows are unchanged.

### `BE-D65` (draft V-8, Q-V11 (b)): a restart that outlasts its bound is looked at once more before the adapter throws

On the bound, the adapter reads C2 (bounded) for a short window: a changed `InvocationID` with `ActiveState=active`
returns `issued=true`; `failed` throws `restart.failed`; anything else throws `restart.timeout` → `CONFIG_DIVERGED`, as
today. No engine change.

*(These three were written after the ruling, as this section said they would be.)*

---

## 12. Owner questions this design raises

✅ **All ruled 2026-10-07 by `OR-121`** — see the box at the top. Kept as written.

Each row states what does not change across the options, the status quo, each option's concrete delta, and a
recommendation. **None is decided here.** Scenario numbers refer to §1.

**Across every option below, these do not change:** the seam (ten operations); the file mechanism (SFTP
temp-then-rename, keyed hash in the DP, `OR-102` fingerprint); health from the declared probe only; the B2 envelope;
no SM write to unit files; no root. **Status quo for all:** `OR-111`, Docker only. A `vm` target cannot be
registered (`transport` CHECK, `TargetService`, check 16) and no VM placement can be declared.

| Id | Question | Options: concrete delta, and the scenarios run through each | Recommendation |
|---|---|---|---|
| **Q-V0** | **`B/D5` 🔒's walking-skeleton gate bars "any systemd work" until a skeleton passes, and no record says it has. How does the VM adapter proceed?** | **What `B/D5` requires and what the record shows:** *one generated screen*: none (the demo's screens were retired by `OR-112` (4); the frontend integrates against the Postman collection; `origin/dev/frontend` is not merged, open item 34). *One graph*: the metrics backend exists (plan 011, P11-4.4 live run); no rendered graph. *One role*: production authorization is built (plan 010); not accepted end to end in a browser. *One Docker `service` with health + telemetry*: measured on the backend (T6.6 run 3, P11-4.4). *Accepted end to end*: T6.6 says *"Not claimed: … B/D5's skeleton"*; the browser live notify was skipped (open items 32/33/35); plan 007's event-driven skeleton is not claimed complete. **(a) Owner reading**, as `OR-94` did: the gate does not hold the `dp-remote` systemd adapter, because the adapter sits behind the `RuntimeAdapter` seam and neither depends on nor changes the missing screen, graph or notify. Cost: none to build. Weakness: unlike `OR-94` (path 2 was argued not to be File-Mesh work), this work **is** systemd work in `B/D5`'s own words, so the reading runs against the text. Plan 014 may execute once approved. **(b) Build or record the missing elements first:** a generated screen and a graph in a browser, the live notify (open items 32/33/35, which are narrowed but unmeasured), and plan 007's skeleton. Cost: frontend work outside `OR-114`'s list, owned by the frontend team, not estimable here; mesh-enforcement measurement. **What waits:** item (4), and with it the `OR-119` PR and the owner's testing from `dev`, unless Q-V12 (b) decouples them. **(c) Owner amendment of `B/D5`**: a new owner row (`B/D5`'s text unedited) saying that for `dp-remote` runtime-adapter work the gate is met by the backend Docker end-to-end runs (T6.6, P11-4.4), while the screen, graph, role and notify elements remain required before v1 is accepted. Cost: none to build; the same outcome as (a) for plan 014, but recorded as what it is. S1–S15 run identically under (a) and (c); under (b) none of them runs until the skeleton passes. | **(c).** (a) asks a reading to carry what the text says the opposite of; (b) holds `OR-119` on work outside `OR-114`. |
| **Q-V1** | **How does SM's account get permission to restart and stop one unit?** | **(a) sudoers, exact argv** (§6.1): two lines per unit, no wildcard; `prepare` proves them (S6 refused before any write). S2: one line pair per host. **(b) polkit rule** for `manage-units`: no sudo, but per-unit/per-verb scoping is unverified (§6.5), so S6's proof and the least-privilege claim would wait on that verification. **(c) root login**: no host setup, violates `SM-D11` (group-confirmed). | **(a)** |
| **Q-V2** | **Which lifecycle verb?** | **(a) `systemctl restart`** (F5: starts a stopped unit), Docker parity; S1's manual restart and S2 behave alike. **(b) `try-restart`**: does nothing when stopped, so an apply to a stopped unit reports `issued` while nothing started, and the ladder never moves. **(c) a declared free-form command** per placement: any lifecycle, but a shell string from data (`SM-D11a`'s first rule) and an unbounded sudoers rule. | **(a)** |
| **Q-V3** | **Where do VM logs come from?** | §2.2: a sudoers regex needs sudo ≥ 1.9.10, which **Ubuntu 22.04 (1.9.9), Debian 11, the RHEL 8 family, Ubuntu 20.04 and Amazon Linux 2 lack**. **(a) journal through a sudoers regex rule** for `journalctl --unit=<unit> …`: S11 works with time-window paging and reads one unit only; one more rule; M-V6 must pass; **not available on the hosts just listed**. **(b) add the account to `systemd-journal`**: no sudo, works everywhere, but reads **every** unit's logs on the host (F16), against `SM-D11`. **(c) file only**: the placement declares a log file inside an allowed log root and SM reads it over SFTP (a new placement column and envelope, rotation handled as a bounded tail). No journal privilege, works everywhere; an MI that logs only to the journal shows none. **(d) no VM logs in v1**: `readLogs` answers `unavailable` on `ssh-systemd`. **(e) exact-argv tail:** one more **exact** sudoers line, `journalctl -u <unit> -o short-iso-precise --utc --no-pager -q -n <N>` with a constant `N` (for example 2000), written in the separate-argument form so no argument holds an F21 character that would need escaping: works on every listed sudo version, reads one unit; S11 gets the most recent `N` lines only and the DP filters them by time, so paging back stops at line `N`. | **(e)** for v1 (works on every listed host, least privilege); **(a)** added where sudo ≥ 1.9.10 if deeper paging is needed; (c) later for file-only MIs. |
| **Q-V4** | **Which systemd versions are supported?** | §2.2 lists what each option excludes; M-V8 measures the named hosts. **(a) ≥ 248** (`--timestamp=us+utc`, F3), microseconds: **excludes the RHEL 8 family, Ubuntu 20.04, Debian 11, Amazon Linux 2**. **(b) ≥ 251** (`--timestamp=unix`), seconds: excludes those and **Ubuntu 22.04 and openSUSE Leap 15.5**; gains nothing over (a). **(c) any version, parse `pretty`** in an operator-declared host zone: everything supported, but a wrong declaration silently misjudges S1's `restart-observed` (F4). **(d) (a) on ≥ 248, plus a constant `TZ=UTC` prefix below it** (F22; M-V10 must show it prints ` UTC`): covers the four excluded families if M-V10 passes, at **seconds** precision there. Consequence there (§4.4): S1 stays `persisted` when a restart falls in the same second as the previous activation (safe); S2/S3's gate reads no restart and lands `CONFIG_DIVERGED` (visible); **S4's resume re-issues a restart** (the unsafe one). S10 is unchanged in every option. On RHEL 8, which has no `--timestamp=` at all, (d) is the only option that is not (c). | **(a)** if the owner's customers are on the ≥ 248 rows; **(d)** if RHEL 8 / Ubuntu 20.04 / Debian 11 / AL2 hosts must be supported, with the seconds residual stated in the runbook. Owner: which distributions do customers run? |
| **Q-V5** | **Contract shape of a VM placement.** | **What a client sees today:** every placement response carries `containerName` (required string). **(a) flat fields:** declare body gains optional `unitName`; `containerName`/`containerConfigPath` stop being schema-required and the server requires the pair the target's transport needs (`422` naming the field). `Instance.placements[]` gains optional `unitName`, and `containerName` becomes optional. **This is a breaking response change:** a generated client or a Postman assertion that relies on `containerName` being present fails on a VM placement. For Docker-only data every response is byte-identical, so nothing breaks until the first VM target exists. **(a′) as (a), but `containerName` stays required and becomes `[string, "null"]`:** the key is always present; a client typed `string` still breaks on `null`. Same exposure as (a). **(b) discriminated `workload: {kind, name}`**: cleaner; a new required shape every client must adopt now, even for Docker. **(c) overload `containerName`** to mean the unit: zero contract change, no client breaks; a field whose name lies, and the declare validation must branch on the target. | **(a)**, with the breaking effect stated in the contract description and to the frontend team (who use the Postman collection, `OR-112` (4)); the owner accepts the change explicitly. |
| **Q-V6** | **Metrics reset-signal vocabulary** (`restartDetection`, `sm_metrics` signal). | **(a) add `systemd`**: additive enum and CHECK widening (a client switching exhaustively on the enum sees a new value); stored rows unchanged. **(b) rename `docker` → `runtime`**: one generic value, but rewrites stored rows and breaks the contract enum. **(c) VM counters use `inferred` only**: no change, and every VM restart becomes an inferred reset (less exact deltas). | **(a)** |
| **Q-V7** | **`SM-D21`'s launch-spec check on a VM.** | **(a) advisory:** `prepare` reads C2b (`FragmentPath`, `NeedDaemonReload`; no secrets) and reports both as evidence; the runbook carries the launch requirement; a pending reload is reported, not refused. **(a+) as (a), but `NeedDaemonReload=yes` refuses** (`ssh.systemd.unit.reload.pending`): stops the stale-`ExecStart` case (§6.2 step 5) before any write; costs one refusal the operator clears with `daemon-reload`. **(b) blocking on `ExecStart`/`Environment`:** catches a missing flag, refuses units that use `EnvironmentFile=` (§6.4), and reads secret-bearing properties. **(c) none.** | **(a+)**; it diverges from PROPOSED `SM-D21` and is flagged as such. |
| **Q-V8** | **Where does the live gate run?** | **(a) the E2E EC2 host**: install a systemd-managed Cache beside the Docker one, and remove it after. `OR-110` covers restarts, extra Cache containers and config changes; it does **not** cover **root-level host changes** (an OS account, a `sudoers.d` file, a systemd unit), so (a) needs the owner's **explicit permission** for those. Per Q-V9 (a) the gate needs a second target identity, which that host cannot have in the same organization, so it uses a second test organization, which must partition ports from the first (`OR-92` OQ-23). **(b) a separate small VM** the owner provides, ideally on a distribution the customers run: clean, at a cost. **(c) no live gate**: the kit only, which proves nothing about real sudo, journal or timestamp output (§7). **(c′) a Testcontainers systemd + sshd + sudo IT** in addition to (a) or (b): an image approved by exact tag (`OR-67`), privileges the build does not use today. | **(b)** if available, else **(a)** with the explicit host-change permission. Not (c). |
| **Q-V9** | **One host running both Docker and systemd MIs** (S14). | **(a) one runtime per host per organization** (state the limit; no change). **(b) per-transport uniqueness**: allows it, and blinds the per-target collision indexes across runtimes (two placements on one port). **(c) runtime per placement**: a model change to `Target`, `OR-28` and every per-target index. | **(a)** |
| **Q-V10** | **Amend `OR-62` to allow a second target pair?** | **Status quo:** `OR-62` fixes v1's one pair as `ssh-docker` ⇒ `docker`; `TargetService`, check 16 and the `sm_control` CHECK enforce it. **(a) New owner row:** v1's pairs are `ssh-docker` ⇒ `docker` and `ssh-systemd` ⇒ `vm` (`OR-62` text unedited); plan 014 T2 changes `TargetService`'s check, check 16's pin and the CHECK, each citing the new row. **(b) No amendment:** a `vm` target stays unregistrable and item (4) cannot be delivered. | **(a)** |
| **Q-V11** | **A restart that outlasts its bound (S15) lands `CONFIG_DIVERGED` today. Is that acceptable for systemd?** | **Status quo (built, Docker too):** any `startOrUpdate` exception closes the attempt `FAILED` and lands `CONFIG_DIVERGED`, while the job may still complete. **(a) Accept:** no change; the operator sees `CONFIG_DIVERGED`, reads `status`, and either re-applies or runs `rollbackApply`. S15's request may say "diverged" for a restart that did finish. **(b) Look again before throwing (adapter only, no engine change):** on the bound, the adapter reads C2 (bounded) up to a short window; a changed `InvocationID` with `ActiveState=active` returns `issued=true` and the gate or ladder proceeds normally; `failed` throws `restart.failed`; anything else throws `restart.timeout` → `CONFIG_DIVERGED` as in (a). Narrows (a)'s window; adds one more read. **(c) Engine change:** route a lifecycle timeout to `APPLY_INDETERMINATE`, so resume decides from the start token. Breaches plan 014's Global Constraint 1 and changes Docker's routing too; needs the owner's explicit ruling and its own review. | **(b)** |
| **Q-V12** | **When does the `OR-115` PR into `dev` go?** (`OR-119`: items (1)–(4) close first, then the owner tests from `dev`.) | **Status quo reading:** "close" means executed. **(a) PR after plan 014 is executed** (T0–T11): the owner's testing waits for plan 008, the 501 operations, the registration API **and** the VM adapter; plan 014 alone needs about 14–19 working days of execution once its gates open (plan 014 Estimates, an estimate, not measured), and its gates include a VM, ten rulings and two other merges. **(b) PR once the VM design and plan are approved**, with VM execution in a follow-up PR into `dev`: the owner tests items (1)–(3) from `dev` as soon as they are executed; the VM lands later with its own test. Needs a new owner row refining `OR-119` for item (4). **(c) PR after plan 014's S1–S3** (built and kit-certified) with T10's live gate run from `dev` by the owner: one PR, an earlier one, and the owner's testing is the live gate. Mixes the integrator's gate with the owner's testing. | **(b)**: the owner's testing of items (1)–(3) does not wait on a VM host and the systemd rulings. |
| **Q-V13** | **The Docker adapter writes secret-bearing files with no explicit mode** (§4.8). Fix it now? | **Status quo:** the Docker config file and pre-images take the host account's umask; on a typical `022` umask they are world-readable on the host. **(a) Fix in plan 014 T4** for both transports: Docker gets the same `setattr` + pre-rename check; a Docker test changes (`noModeRequestedLeavesDockerBehaviourUnchanged` is replaced), which relaxes Global Constraint 2 for this one behaviour. **(b) A separate small Docker task** before the `OR-115` PR, dated: the same change, reviewed on its own. **(c) Defer:** record it, with a date for the owner to revisit. | **(b)**, dated before the `OR-115` PR, so the VM work does not carry a Docker behaviour change. |

---

## 13. What this design asks of later tasks (for the integrator)

- **Plan 014** (DRAFT) carries the tasks. It depends on plan 008 (multi-placement) **merged** for S2, and on item
  (3)'s registration API for the `ssh-systemd` registration path. A single-host VM works without plan 008.
- **Register** drafts V-1…V-5 in `decisions.md` with allocated ids at merge, plus the Q-V3/Q-V6/Q-V11 drafts once
  ruled, and the owner rows for Q-V0 (if (c)), Q-V10 and Q-V12 (if (b)).
- **Indexes:** add this design to `docs/design/backend/` README/index rows and to `architecture.md` §4's driver
  list reference; `REQ-005`'s trace gains the VM adapter.
- **Contract coordination:** `d/regapi` (item 3) defines the target registration body. Whichever merges second
  widens `transport` to `ssh-systemd` and adds `allowedUnitPrefix`. Plan 014 T2 owns that widening if item 3 lands
  first.
- **Docker follow-ups:** (i) the explicit file mode (Q-V13); (ii) confirm `SM-D11a`'s per-operation audit row
  (host-key fingerprint, argv, outcome) exists for the SSH-Docker path, so the VM path can match it.
- **Not designed here:** a `host-local` (agent) systemd driver, which is `AG-D*`'s; Kubernetes (`OR-111`); unit
  reload (`ExecReload=`) as a lifecycle verb; `scheduled` workloads (systemd timers, `B/D14`).

---

## 14. Review dispositions (adversarial review, 2026-10-07)

Each finding was checked against the tree or the source before it was applied. "Fixed" names where.

| Id | Sev. | Disposition |
|---|---|---|
| B1 | BLOCKER | **Fixed.** Confirmed: T6.6's scope and live-run Results both disclaim `B/D5`. Flag 1 quotes them; Q-V0 lists each `B/D5` element against the record and offers priced (a) reading, (b) build first, (c) amendment; recommends (c), not "record it passed". Plan 014 G-B rewritten. |
| B2 | BLOCKER | **Fixed.** Confirmed in `ApplyExecutor`: a failed gate lands `CONFIG_DIVERGED` with no restore; `exhausted()` is reached only from `MATERIALIZE` and resume; any `startOrUpdate` exception lands `CONFIG_DIVERGED`. S3, §4.5's outcome table and plan T10 rewritten to the built behaviour (operator `rollbackApply`, `OR-60`); flag 3 and Q-V11 put the timeout routing to the owner. Found while checking: on a VM, S3's gate classifier is `gate.no.restart` (a `failed` unit has no baseline), stated in §4.5. |
| M1 | MAJOR | **Fixed.** Flag 2 and Q-V10 (new row, `OR-62` unedited); §5.2 names `TargetService` and check 16's pin; plan T2 changes both. |
| M2 | MAJOR | **Fixed.** §5.1/§5.2 add `TargetTransport`/`TargetController`, `TargetService`, `TargetRegistrationMain`, `TargetView`, `JdbcTargetConnectionRepository`, plus `TargetEnvelope` (found while checking). Gateway enum lands with the migration (plan T2) and a `listTargets` IT. §10's I7 row corrected. There is no `getTarget` operation, so the IT covers `listTargets` only. |
| M3 | MAJOR | **Fixed.** §5.4 re-inventories all 22 files with their tasks; §4.6 adds the typed `SystemdShow` command, pool and `HostMetricExec` methods, `IngestService.detection`, `dp-api`'s `RestartDetection`, and `CatalogComposer` (found while checking). `SshDockerDiagnostics`/`SshDockerConfigEditor` measured as having no production caller. |
| M4 | MAJOR | **Fixed.** §4.5 states the W2-03 fact and two bounds (host `timeout --kill-after=2 <n>` outside sudo; DP `join(deadline)`); §8 carries exact templates; M-V9 measures a killed restart. |
| M5 | MAJOR | **Fixed.** Grammar capped at 128 in total, one `@` at most, `@.service` refused; `processRef` quote corrected (Java `VALUE` 128, V020 DDL 255). |
| M6 | MAJOR | **Fixed** by dropping `:` from the grammar (no F21 character remains; the generator refuses rather than escapes), the `systemctl` path as a required generator input proved by `sudo -n -l`, and `requiretty` in M-V4 and the runbook. F21 fetched. |
| M7 | MAJOR | **Fixed.** §6.3: `setattr` on the temp file, stat before rename, delete and refuse; post-rename stat as second check; same for pre-image and fingerprint files. |
| M8 | MAJOR | **Fixed.** §2.2 fetched version table; Q-V4 gains (d) `TZ=UTC` with its seconds consequence run through S1/S2/S3/S4/S10; Q-V3 and Q-V4 name the excluded hosts; Q-V3 gains (e), an exact-argv tail that works on every listed sudo. |
| M9 | MAJOR | **Fixed.** Q-V5 states the breaking response effect, adds (a′) required-nullable, and asks the owner to accept; plan GC7 corrected; T2 runs `check-postman.py`. |
| M10 | MAJOR | **Fixed.** Q-V12 (PR timing, three options with the owner's testing timeline); plan 014 gains per-slice estimates and a critical-path statement. |
| m1 | MINOR | **Fixed.** §4.4: the parser accepts the M-V1 layout only; the RFC 3339 claim is removed (also from F17). |
| m2 | MINOR | **Fixed.** Closed set of six named properties; C2b; `NeedDaemonReload` in §6.2 and Q-V7 (a+); runbook item 1. |
| m3 | MINOR | **Fixed.** §4.9 (S8→S9, S9's enable premise, S13's ports); runbook items 2 and 8. |
| m4 | MINOR | **Fixed.** Q-V8 (a) asks for explicit root-level host-change permission and notes `OR-92`'s port partition. |
| m5 | MINOR | **Fixed** in plan T2 Step 4: the parity is pinned by a backend test that reads `console.yaml` (no new contracts-to-migration coupling); check 16's edit is named. |
| m6 | MINOR | **Fixed** by statement: §7 says the live gate is the only proof of real output parsing; the Testcontainers IT is Q-V8 (c′). |
| m7 | MINOR | **Fixed.** Flag 5 and §8 flag the fixed-template reading of `SM-D11a`. |
| m8 | MINOR | **Fixed.** §6.2: `prepare` runs on one connection. |
| m9 | MINOR | **Fixed.** §4.7: prefix stripped; redaction parity is with `LogServiceImpl` (not redacted, per `LogService`), not with the unwired `SshDockerDiagnostics`. |
| m10 | MINOR | **Fixed.** §5.2 states the DP transport drives behaviour and the gateway refuses a mismatch; V-1 reworded. |
| m11 | MINOR | **Fixed.** Q-V13 (dated Docker mode question); flag 7 cites the `SM-D-era v1` ordering change. |
