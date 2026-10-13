# Backend Plan 013 — registration through the API: targets, SSH keys, host keys, entitlements, the scope tree, the operator user

> **Status: APPROVED (`OR-121`, 2026-10-07).** The owner approved this plan for execution under `OR-70`; every open
> question of [D-REG](../../design/backend/registration-api.md) §13 takes the design's recommendation (none of them is
> among the seven `OR-121` names), the owner having directed execution (*"close plan-12, plan013"*). Written
> 2026-10-07 against `integration/dp-remote-demo` @ `36af1df6` (branch `d/regapi`) and revised once after an
> adversarial review (D-REG §15). The one gating rule below is therefore met. The execution order, waves, single-owner
> files and migration numbers are the [combined schedule](combined-008-012-013.md), which controls where it differs
> from [Dispatch](#dispatch).
>
> **One gating rule (revised, R20) — met.** No task below starts until two things hold: the owner has ruled **every** open
> question of D-REG §13 (OQ-1 to OQ-9 and OQ-11 to OQ-15; OQ-10 is withdrawn), and the owner has approved this plan.
> Both hold since `OR-121`. A task whose ruling excludes it is deleted, not skipped.

> **Rulings applied (`OR-121`, 2026-10-07) — each the design's recommendation.**
>
> | Question | Ruled answer | What it changes here |
> |---|---|---|
> | OQ-1 verbs | **(a)** three new verbs: `target.edit` (HIGH), `hierarchy.edit` (STANDARD), `entitlement.set` (HIGH, install) — rows 41–43 of the `D10` amendment's [§1.9](../../design/D10-G2-APPROVAL-2026-09-15.md) | Their catalog code lands in **plan 012 T1** (one combined migration, `OPS-Q13` (a)), not in T2.1 |
> | OQ-2 host-key trust | **(b)** scan, then confirm one explicitly | T1.2, T1.4 |
> | OQ-3 addresses the DP may connect to | **(d)** check at registration **and at every use**, with an optional allow-list | T1.2 |
> | OQ-4 entitlements over HTTP | **(a)** `PUT /cp/entitlements/{solution}`, `entitlement.set` at install, refused below `declaredInstances` (`409 ENTITLEMENT_REFUSED`) — changes `OR-48`'s "operator command only" (named by the design; `OR-121` adopts it, and `OR-122` amends `OR-48` and `D10`'s catalog by name) | T2.2 |
> | OQ-5 creating organizations | **(a)** not in v1; rename only | — |
> | OQ-6 the operator user | **(a)** W3 is in; the API grants and revokes Viewer and Operator only, never an Administrator | **W3 runs**; T2.1 includes W3's grants and operation names |
> | OQ-7 who holds the registration verbs | **(a)** Administrator only (the v1 posture) | Catalog counts **43 / 24 / 8** (the Operator's 24 come from plan 012) |
> | OQ-8 keys | **(a)** per target | — |
> | OQ-9 `docker version` in the test | **(a)** yes, one fixed command | T1.5 |
> | OQ-11 a retired target's name | **(b)** freed (partial unique index) | T2.1 |
> | OQ-12 edits while work is in flight | **(a)** refuse `409` under the admission lock | T1.5 |
> | OQ-13 who reads the connection record | **(a)** the management verb, `target.edit` | T1.4 |
> | OQ-14 credentials over plain HTTP | **(b)** refuse unless secure, a trusted proxy with `https`, or loopback | T1.4, T3.2 |
> | OQ-15 SSH test fixture | **(a)** one SSH-server image, by exact tag, test-only; Docker-dependent checks live on the E2E host | T0.2 — ⚠️ the **image's licence row** (`OR-67` form, exact tag) is still the owner's to approve |

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development` (recommended) or
> `superpowers:executing-plans`. Steps use checkbox (`- [ ]`) syntax. Read [Dispatch](#dispatch) before starting.

| | |
|---|---|
| **Component** | backend (`cp-api`, `cp-core`, `dp-api`, `dp-core`, `console-gateway`, `launcher`, `arch-tests`) + `contracts/openapi/` + docs. **No frontend change** (owner standing rule); the frontend team receives the contract and the Postman collection. |
| **Authority** | `OR-114` item (3), `OR-116` (entitlements join it), `OR-119` (closes before the `OR-115` PR into `dev`). The owner rules for the architect group (`OR-69`). |
| **Sequence and merge prerequisites** | The [combined schedule](combined-008-012-013.md) runs this plan beside plans 008 and 012 (`OR-121`). It builds on **plan 012 T1** (the catalog at 43, including this plan's three verbs, §1.9; Operator 24) and **plan 012 T4** (`record_change_audit()` carrying `INSTANCE_UNDECLARED`; `countInstances` excluding retired instances), both in wave 1. `OR-116` (`Identity.organizations`) is already in the base (squash-merged before `c8da188a`). **`OR-120`** (`p/names`: the `name` columns, `sm_control` **V015**) must merge **before W2**. **Migration numbers** are allocated in the combined schedule: `sm_data` **V022** (T1.3), `sm_control` **V018** (T2.1); the rule is *next free at merge* (`OR-22`), and no published migration is edited. |
| **Requirements** | [`REQ-006`](../../requirements/REQ-006-rbac.md) (RBAC) · [`REQ-014`](../../requirements/REQ-014-oss-commercial-licences.md) / `I8` (**one possible test-only image**, T0.2 / OQ-15; no runtime dependency) · `I1`, `I5`, `I6`, `I7` |
| **Decisions this plan records** | The four drafted in D-REG §12, allocated **`BE-D54`–`BE-D57`** (PROPOSED) on 2026-10-07. The owner's rulings are `OR-121`. |

**Goal.** Everything an install needs between "the bootstrap administrator has rotated the credential" and "an
Operator declares an instance" is possible through authenticated, audited HTTP, so the console needs no terminal and
the local stack (`OR-118`) needs no superuser SQL. The operator commands stay.

**Architecture.** The gateway composes `cp-api` and `dp-api` (`OR-10`, `I7`). Target registration happens in four
steps:

1. Authorize `target.create` without writing, through the existing interceptor and `AuthorizationService.require`.
2. Run every DP pre-check read-only: key parse, address policy, fresh scan with the pin offered, host-key uniqueness.
3. Write the CP governance row.
4. Write the DP connection record and its sealed key.

The private key crosses the gateway as a `char[]` for one in-process call. `dp-core` parses, fingerprints, seals and
references it (`OR-30`, `I6`). Host keys are scanned by the DP, untrusted, and pinned only by an explicit field that
the DP re-checks against a fresh scan. `sm_control` writes are trigger-audited (`OR-19`/`OR-25`). DP connection
writes gain an append-only event table in the same transaction.

**Tech stack** (unchanged from plan 011's measurement at `bd802fc`, to be re-measured in W0): Java 17 · Spring Boot
3.5.16 · PostgreSQL 14 · Flyway 11.7.2 · sshj 0.40.0 · Testcontainers 1.21.4 · ArchUnit 1.5.0. **No new runtime
dependency.** One test-only image only under OQ-15 (a) or (c).

---

## Global constraints

1. **No secret in any output.** No response, log line, exception message, audit row, event or metric carries a
   private key, a credential, a `credential_ref`, or a hash of a private key. Every request record holding one has a
   redacting `toString` and refuses unknown properties. Every refusal text is fixed.
2. **`I5`.** The organization is `Session.organizationId()`; no request body names one; a path organization id is
   compared with the session and is never authority.
3. **`I7`.** The gateway owns no database (its planted JDBC fixture stays green); `cp-*` never imports `dp-*` and the
   reverse; no cross-schema SQL.
4. **`OR-22`.** New migrations only; the frozen-migration check (check 12) and the anchor file are updated in the
   same commit as each new migration is published.
5. **`OR-25`.** Every new `sm_control` mutation sets the audit context (actor, a known operation, correlation id,
   client address); a test proves a write without it is refused.
6. **Check 11.** No operation enters `console.yaml` with a verb the owner has not ruled into the catalog.
7. **No write before every refusal is decided** (D-REG §8). A registration refused for any reason leaves no row in
   either plane.
8. **Full reactor build at the end of each wave** (`OR-70`), offline (`-o`).

## Owner gate (W0 — docs only)

### T0.1 — Register the rulings and the contract diff

- [x] Step 1 (*T0.1r, 2026-10-07*): Re-read plan 012 T1/T4 **as planned** (wave 1 had not merged: lane B builds them in
      parallel) and `OR-120`'s merge state. Confirm the catalog count (43), the role counts (43/24/8) and the
      `record_change_audit()` base body (plan 012's `V017`). Recorded at the top of this plan's Results.
- [x] Step 2: *Done:* the owner's answers are `OR-121` (every question as recommended). `OR-121` adopts OQ-1 (a)
      and OQ-4 (a) through its blanket clause, and `OR-122` (2026-10-07) then names both: it amends `D10`'s catalog by
      name (+5 verbs, 43) and `OR-48` by name (entitlements over HTTP). Nothing here still waits on a confirmation.
- [x] Step 3: *Done:* this plan's verbs are rows 41–43 of the combined **§1.9** (not a separate §1.10), written on
      2026-10-07 with forward pointers only (`I3`).
- [x] Step 4 (*T0.1r: drafted 2026-10-07, review pending (step 6)*): Write the reviewed `console.yaml` diff for the
      operations the rulings admit (D-REG §5), including — **the draft is
      [`registration-api-contract-draft.yaml`](../../design/backend/registration-api-contract-draft.yaml)**, 22
      operations, outside `contracts/` so no check sees it until an operation enters with its handler:
      - `x-sm-permission` and `x-sm-scope` on each;
      - `writeOnly` on `privateKey`, `initialCredential` and `resetCredential`;
      - the new `ErrorCode` members (`CREDENTIAL_INVALID`, `HOST_KEY_NOT_OFFERED`, `TARGET_ADDRESS_REFUSED`, and
        `INSECURE_TRANSPORT` under OQ-14 (b));
      - the new schemas (`TargetDetail`, `TargetConnection`, `CredentialSummary`, `Entitlement`, and under OQ-6
        `User`, `Role`, `Assignment`).
- [x] Step 5: *Moved to plan 012 T1*, which takes `validate-openapi.py` from 38 to 43 in one step for all five
      verbs. Step 4's diff is a **reviewed draft, not a commit to `console.yaml`**: each operation enters
      `console.yaml`, `ConsoleOperations`, the Postman collection and the launcher's route set in the task that adds its
      handler (`ConsoleOperationsMirrorTest` fails on a contracted operation with no registration).
- [ ] Step 6: One adversarial review of D-REG + this plan + the contract diff (Opus). Dispositions are appended to
      D-REG §15.
- [x] Step 7: `./contracts/run-checks.sh` green; markdown links resolve. *(2026-10-07, `--strict`, with this wave's docs.)*

**Done when:** the recomputation is recorded, the contract diff is reviewed, and check 11 passes on it.

### T0.2 — The SSH test fixture (OQ-15 (a), ruled)

- [x] Choose the candidate image. Fetch its licence **at the exact tag** (`I2`, WebFetch, never from memory), and
      record a licence row for the owner in the `OR-67` form (test-only, exact tag). ⚠️ **Owner gate:** `OR-121` rules
      OQ-15 (a) but cannot approve an image nobody has named yet; until the owner approves the row, T1.2/T1.4/T1.5 run
      their real-SSH ITs against the OQ-15 (b) fakes and the E2E host, and add the fixture ITs when the approval lands.
- [x] After the owner approves (`OR-124`): `dp-core/src/test/.../ssh/SshdFixture.java` (written in W3 lane C with T1.4, compiled, not run: see T1.4's Results), a Testcontainers wrapper that starts
      the image with a generated host key set (ed25519, ecdsa, rsa) and an `authorized_keys` the test controls
      (add or remove a public key at runtime, for S3).
- [ ] Docker is **not** in this fixture under (a). Every check that needs a Docker daemon over SSH (T1.5's
      `docker version`, MF-R2, and S2's "the next apply uses the new host") is a **live gate on the E2E host**
      (`OR-110`), recorded in the Results with its evidence.
- *If OQ-15 (b):* this task is deleted. T1.2, T1.4 and T1.5 use fake `HostKeyScanService`/connector beans in the
  reactor, and every real-SSH scenario below becomes a live gate on the E2E host.

**Results (T0.2, licence candidate only; W1 lane D, 2026-10-07, Sonnet 5.5).** The candidate is named and its licence
read at the tag; **nothing is added** (no image reference in any test, no `SshdFixture.java`, no pom change) until the
owner approves the row (combined schedule section 8, item 4). Every fact below was fetched, not recalled (`I2`):
`gh api repos/panubo/docker-sshd/tags` and `/releases` (latest `v1.10.0`, published 2026-01-28), WebFetch of
`raw.githubusercontent.com/panubo/docker-sshd/v1.10.0/LICENSE` and `.../v1.10.0/Dockerfile`, and WebFetch of
`quay.io/api/v1/repository/panubo/sshd/tag/` for the registry tag and its digest.

> **Licence row for the owner (`OR-67` form: a test image is a tool, approved by exact tag, test-only).**
>
> | | |
> |---|---|
> | **Image** | `quay.io/panubo/sshd:1.10.0`, manifest digest `sha256:6d54f749fe1817628528ad75a88ab368bb6b9b5a7ad0fe08e299560606a20672` (the registry's `1.10.0`, `1.10` and `latest` all point at it on 2026-10-07). Approve by the tag **and** pin the digest in the fixture. |
> | **What it is** | "Minimal Alpine Linux Docker image with `sshd` exposed and `rsync` installed" (README). One program under test: OpenSSH `sshd`, with host keys mounted at `/etc/ssh/keys` and per-user `authorized_keys` at `/etc/authorized_keys/<user>`, so the fixture can supply a generated key set (ed25519, ecdsa, rsa) and change a user's key at run time (scenario S3). |
> | **Licence of the image project** | **MIT** (`LICENSE` at tag `v1.10.0`: "The MIT License (MIT) / Copyright (c) 2015-2026 Volt Grid Pty Ltd"). On `REQ-014`'s allow-list. |
> | **What is inside it (read from the `Dockerfile` at the tag, not from memory)** | `FROM alpine:3.22`; `apk add --no-cache bash git openssh rsync augeas shadow rssh`. **The licences of those packages were not read here.** Several are copyleft programs the image merely contains; under `OR-67` ("an image a test runs is a separate program, not linked code") that is the same footing as `busybox:1.36` (GPL-2.0, approved as a tool), but it is the owner's call, not this row's. |
> | **Scope** | Test-only, started by a Testcontainers fixture in `dp-core/src/test` (`SshdFixture`), never shipped, never on a runtime path, no Maven dependency (so `license-maven-plugin` does not see it). It needs the registry reachable once on a machine without the image cached, as `postgres:14` does. |
> | **What it cannot do** | Run Docker. Every Docker-over-SSH check (`docker version`, `MF-R2`, "the next apply uses the new host") stays a live gate on the E2E host, as ruled (`OQ-15` (a)). |
> | **If refused** | `OQ-15` (b): the reactor ITs use fake `HostKeyScanService` and connector beans, and every real-SSH scenario is a live gate. Nothing in T1.2's own tests needs the image (see its Results). |

**Measured without the image (so the candidate is not the only evidence).** On 2026-10-07 a throw-away system `sshd` on
`127.0.0.1:2299`, with three generated host keys, was scanned by T1.2's `SshHostKeyScanService`; see T1.2's Results.
That run was a measurement on the developer's own machine, not a fixture, and it is not repeatable in CI, which is
what the image is for.

## W1 — Targets

### T1.1 — One validation path in `dp-core`, the key parse and the public fingerprint

Files: `dp-core/.../inventory/InventoryService.java`, a new `dp-core/.../inventory/TargetConnectionValidator.java`,
a new `dp-core/.../inventory/PrivateKeyInspector.java`, `dp-core/.../apply/persistence/TargetConnectionMain.java`,
`dp-api/.../inventory/TargetConnectionService.java`, `TargetConnectionSpec.java`.

- [x] Move the field grammars (host, user, fingerprint, config root, container prefix) from `TargetConnectionMain`
      into `TargetConnectionValidator`, used by the command and the service alike (one grammar beside the CHECKs).
- [x] `PrivateKeyInspector` opens the key with sshj `loadKeys(String, null, null)`. It refuses anything that does not
      open as an unencrypted private key with a fixed-text `CredentialInvalidException`, drops the cause, and returns
      the **public** key's `SHA256:` fingerprint (`SshHostKeyScanner.fingerprintOf`).
- [x] **MF-R1:** measure, in a test with generated keys (ed25519 and RSA, OpenSSH and PEM, with and without a
      passphrase), where an encrypted key fails; record the result in this plan's Results.
- [~] Replay and rotation compare **public-key fingerprints** (D-REG §8, R11). ⚠️ **Done except the back-fill, which waits for `V022`** (see Results). The candidate's comes from the
      inspector, the stored one from `credential_public_fingerprint`. For a legacy `NULL` row, open the stored
      secret in `dp-core`, derive its fingerprint with the same inspector, compare, and back-fill the column in the
      same transaction, with the plaintext closed in a `finally`. `SecretCompare` is not used (it is `CONFIG_FIELD`
      only and compares text).
- [x] `rotateCredential`: compare first; same key → no write.
- Tests:
  - grammar refusals (unit);
  - parse refusals (each MF-R1 case);
  - **the same key re-exported** (OpenSSH vs PEM, CRLF vs LF, a trailing newline) is a replay, not a `409`;
  - replay with the same and a different key (Testcontainers IT);
  - a legacy `NULL` row is back-filled on replay;
  - rotating to the same key writes no `sm_secret.secret` row (count before and after);
  - no exception message contains a key line (assert over every thrown message).

**Results (T1.1, 2026-10-07, wave 1 lane C, branch `x/w1c`).** Done except one item, which cannot be done yet.

- **What exists.** `dp-core` `inventory/TargetConnectionValidator` (the five grammars plus the port, one place;
  `TargetConnectionMain` now holds none, and a test fails if it grows one again), `inventory/PrivateKeyInspector`
  (parse, then the PUBLIC fingerprint), `dp-api` `CredentialInvalidException` (fixed text, no cause) and
  `TargetCredentialConflictException`. `InventoryService.register` validates the grammar, parses the key, and only then
  reads or writes a row; a replay with the same record compares keys by public fingerprint (same: nothing written;
  different: `TargetCredentialConflictException`, the future `409`). `rotateCredential` compares first: the same key
  writes no `sm_secret.secret` row. A stored key is opened inside `dp-core` for the compare and its material closed in a
  `finally`.
- **`InventoryService` has a new canonical constructor (8 parameters)**: the store that seals a key and the
  `SecretResolver` that opens a stored one (`EnvelopeSecretStore` is both). `InventoryConfiguration` and
  `TargetConnectionMain` use it; the 6- and 7-parameter ones remain for tests and derive the resolver when the store is
  one. `MetricsDeclarationConstructorTest` pins the canonical arity (7 → 8): edited with a comment saying why.
- **Behaviour change for the operator command, deliberate.** `TargetConnectionMain` used to treat an identical record as
  a replay without looking at the key (`credential unchanged`). With a **different** key it now exits 1 ("registered with
  a different private key; rotate its credential instead"), and a key that does not open exits 2 ("not accepted"). The
  same key, in any encoding, is still the quiet replay.
- **MF-R1, measured (sshj 0.40.0, JDK 17, 2026-10-07).** `SSHClient.loadKeys(String, null, null)` **never fails at
  load**: it returns a lazy `KeyProvider` for every format, encrypted or not. The failure comes at `getPrivate()` (what
  authentication asks for first): `KeyDecryptionFailedException` for an encrypted OpenSSH-container key, for an encrypted
  PKCS#8 key (RSA and Ed25519), and a plain `IOException` for a legacy encrypted PEM (`Proc-Type: 4,ENCRYPTED`). It
  also fails at `getPrivate()` with `IOException` for an **unencrypted** SEC1 `-----BEGIN EC PRIVATE KEY-----` (ssh-keygen
  `-t ecdsa -m PEM`) and for a JDK-written Ed25519 PKCS#8 — so those could never have been used by the transport either,
  and they are refused. Opened fine, with the right public fingerprint: OpenSSH container (ed25519, rsa, ecdsa), PEM
  PKCS#1 RSA, PKCS#8 RSA and ssh-keygen's Ed25519 PKCS#8, with CRLF or without a trailing newline. No exception message in
  the matrix contained a key line, but the inspector drops every cause regardless. `PrivateKeyInspectorTest` pins all of it
  (and an executable "load alone never fails" test, so a later sshj that changes this is noticed).
- **Not done: the back-fill of a legacy `NULL` fingerprint.** It needs `target_connection.credential_public_fingerprint`,
  which is `sm_data` **V022** (T1.3, wave 2). Until then the stored key's fingerprint is derived on every compare
  (`InventoryService.storesKey`: the legacy path of D-REG §8 is the only path). **T1.3/T1.4 must wire the column:**
  read it first, fall back to `storesKey` on `NULL`, back-fill in the same transaction, and write it on insert and
  rotation. The test "a legacy `NULL` row is back-filled on replay" is theirs; this task's ITs already cover the legacy
  row whose stored key does not even parse (a replay is a conflict, rotation replaces it).
- **Residual for T1.5.** The rotate compare-then-write holds no row lock, so two *concurrent* rotations to the same new
  key can each store a row; a *sequential* double submit writes one. T1.5 owns the edit/rotate locking
  (`SELECT … FOR UPDATE`) and should add it here with its race IT. `TargetConnectionRows` has no lock method today.
- **Tests (targeted, see the report for counts).** `PrivateKeyInspectorTest` (12), `TargetConnectionValidatorTest` (9),
  `TargetCredentialReplayIT` (12, Testcontainers), `TargetConnectionMainIT` (+3: different key, re-exported key,
  unopenable key), the existing `TargetConnectionMainTest` (9) unchanged and green. Red first (20 of 20 new unit tests; 7
  of 11 of the first IT cut), then green, then eleven mutations, each caught: no force-open of the key, parser cause kept,
  size guard removed (twice: the first oversized test also failed on garbage, so it was rewritten to a VALID key padded
  past the limit), the public fingerprint ignored, compare skipped on replay, rotate always writing, parse skipped on
  register, compare by text, grammar skipped in the service, config root `/` allowed, a pattern back in the command,
  stored material not closed. **Not mutation-proved:** the `Arrays.fill` of the opened plaintext (not observable
  from outside); the try-with-resources closing is.
- **Test keys** are generated per test run by `SshTestKeys` from the JDK alone (no new dependency, no key material in the
  repository); the expected fingerprints are hand-encoded from the SSH wire blob, or are `ssh-keygen -l`'s own output.
  The encrypted formats, and the OpenSSH container for RSA and ECDSA, need `ssh-keygen` and are skipped (JUnit
  assumption) if it is absent: **present on this machine, 0 skipped**.
- **Files outside the task's list that had to change:** `InventoryConfiguration.java` (the resolver bean),
  `TargetConnectionMain.java` (the canonical constructor), `MetricsDeclarationConstructorTest.java` (the arity),
  `InventoryServiceIT`/`DeclareSweepIT`/`TargetConnectionMainIT` (their sentinel key strings are no longer keys).
  `TargetConnectionSpec.java` needed no change.

### T1.2 — The host-key scan port and the address rule

Files: new `dp-api/.../inventory/HostKeyScanService.java`; `dp-core/.../apply/runtime/ssh/SshHostKeyScanner.java`;
new `dp-core/.../inventory/TargetAddressPolicy.java` (interface) and `StrictTargetAddressPolicy.java`; a **new**
`dp-core/.../inventory/TargetAddressProperties.java` (`@ConfigurationProperties("sm.dp.targets")`,
`allowed-networks`), alongside the per-area classes such as `FleetProperties` (there is no `DataPlaneProperties`);
`SshjDockerTransport`, `SshjHostConnector`, `DirectHttpProbe`, `DirectHttpFetcher` (check-at-use, OQ-3 (d)).

- [x] `StrictTargetAddressPolicy.check(host)`: resolve, and refuse if any address is loopback, link-local,
      unspecified or multicast (IPv4 and IPv6), or falls outside a configured allow-list. Return the checked
      addresses.
- [x] **The test seam (R7):** the permissive implementation lives **only** in `src/test` and is wired by the ITs'
      `@TestConfiguration`. Add an ArchUnit rule in `arch-tests` that no main-source class other than
      `StrictTargetAddressPolicy` implements `TargetAddressPolicy`. There is no property that relaxes the strict
      policy.
- [x] **Check at every use (OQ-3 (d)):** the four connectors call the policy on the stored host immediately before
      connecting. A refusal is a fixed-classifier connection failure (`target.address.refused`), never a
      connection. *If OQ-3 is ruled (b) or (c):* this step is deleted, and the residual is recorded in the runbook.
      *If (e):* the host grammar is narrowed to literals, with a CHECK-widening migration for IPv6.
- [x] **MF-R4:** prove sshj connects to the checked `InetAddress` while the `HostKeyVerifier` still performs the pin
      check. If it cannot, connect by name after the check and record the narrower guarantee. That is the residual
      D-REG §7.3 already states.
- [x] `HostKeyScanService.scan(host, port)` → offered `{fingerprint, algorithm}`; the scanner records the algorithm
      name it already iterates.
- Tests:
  - a policy unit table: `127.0.0.1`, `::1`, `169.254.169.254`, `fe80::1`, `0.0.0.0`, `224.0.0.1`, a name resolving
    to loopback, and an allow-list miss;
  - each connector refuses a stored host whose name now resolves to loopback (a stub resolver), with no connection
    attempted;
  - the ArchUnit rule's planted violation fails;
  - scanning the T0.2 fixture returns every key type (under OQ-15 (b), a fake scanner).

**Results (T1.2, executed 2026-10-07 on `x/w1d`, Sonnet 5.5; reviewed in the wave review, not yet).**

*Built.* `dp-api`: `HostKeyScanService` (with `OfferedHostKey(fingerprint, algorithm)`), `TargetAddressRefusedException`,
`HostKeyScanUnavailableException` (both with fixed messages naming no host). `dp-core`: `TargetAddressPolicy`,
`StrictTargetAddressPolicy`, `TargetAddressProperties` (`sm.dp.targets.allowed-networks`), `TargetAddressConfiguration`
(the strict bean and the scan service; `@Import`ed by every configuration whose connector needs a policy and, for HF4, one
line in `DataPlaneConfiguration`), `SshHostKeyScanService`, and the check at use in `SshjDockerTransport`,
`SshjHostConnector` (so the session pool), `DirectHttpProbe` and `DirectHttpFetcher`. A refusal is
`RuntimeAdapterException target.address.refused` (SSH and the probe, which the health and apply callers already map to
unreachable/unreadable) and `FetchResult.Transport("target.address.refused")` (the fetcher's closed vocabulary, one
member longer). `HostLogs.over`, `HostProbe.over`, `SshSessionPool`, `SshDockerRuntimeAdapter`, `SshDockerDiagnostics` and
`SshDockerConfigEditor` gained the policy parameter so no connector can be built without one. One arch rule:
`TargetAddressPolicyBoundaryTest` (`arch-tests`), two planted fixtures.

*Red first.* The new tests against a stub policy (every method `UnsupportedOperationException`): `StrictTargetAddressPolicyTest`
45, `TargetAddressAtUseTest` 9, `DirectHttpFetcherAddressTest` 4, `SshHostKeyScannerAddressTest` 7 = **65 run, 55 red**
(38 failures, 17 errors); the 10 green were the controls, MF-R4's on-the-wire test and the key-type naming test, which do
not touch the policy. **Green:** 65 of 65. The table found a real defect on its first run: the JDK calls `::1` and `::`
"IPv4-compatible" by their bytes, so the policy read them as `0.0.0.1` and `0.0.0.0` and accepted loopback; fixed by
judging both the address and the IPv4 it embeds. Added after: `TargetAddressConfigurationTest` 6 (binding, the strict
bean is what boots, no property relaxes it, a test's `@Primary` bean wins). **Arch:** `TargetAddressPolicyBoundaryTest` 4
(the rule and three proofs); all 10 `arch-tests` suites green.

*Open for T1.4 (W1 review D-6).* `com.solace.sm.dp.inventory` and `com.solace.sm.dp.apply.runtime.ssh` now depend on each other (the policy is in `inventory`; the connectors that call it, and the scan service that implements an `inventory` port, are in `ssh`). Not refactored here; T1.4 decides where the seam moves.

*Existing tests that now need a policy.* `dp-core` surefire in full: 126 suites, 1171 tests, 0 failures, 1 skipped (as
before). Targeted failsafe, `dp-core`: `DirectHttpFetcherIT`, `CollectWiringIT`, `MetricsCollectorIT`, `HealthPollerIT`: 49 of
49. Targeted failsafe, `launcher`: `HealthEndpointsIT`, `LogsEndpointsIT`, `LogLevelEndpointsIT`, `ProductionWorkerLiveIT`,
`ProductionAuthenticationIT`: 29 of 29, plus `SolutionManagerConfigurationTest`. The ITs that point a target at a loopback stub
now `@Import` `LoopbackTargetAddressConfiguration` (a `@TestConfiguration` with a `@Primary` bean in `dp-core`'s
test-jar); `E2eStack` adds it for `Mode.REHEARSAL` only (LIVE keeps the strict policy). `DataPlaneConfigurationTest` gained
the one import it asserts (the plan 011 precedent).

*Mutations, each red and reverted.* The strict table's link-local check removed (6 red); the allow-list ignored (4 red);
the first (un-embedded) address check removed (2 red, the `::1` defect); `SshjDockerTransport` connecting by name (2 red);
`SshjHostConnector` by name (1 red); `DirectHttpProbe` resolving without the policy (2 red); `DirectHttpFetcher` skipping
the policy (3 red, the control stays green); `SshHostKeyScanService` connecting to an address other than the checked one (1
red); the arch rule weakened to accept any class (1 red; the anonymous fixture stays reported by its modifier, so the
class fixture is the proof); and, end to end, `HealthEndpointsIT` without its `@Import` (**4 of 8 red**: the production
boot is strict).

*MF-R4, measured.* sshj 0.40.0 (`javap -c` of the jar): `SocketClient.connect(InetAddress, int)` connects to that
address and `getRemoteHostname()` is `getRemoteAddress().getHostName()`; `connect(String, int)` instead resolves inside the
call. So connecting to `InetAddress.getByAddress(name, bytes)` goes to the checked address and hands the verifier the
name. Proven on the wire two ways. In the reactor (`SshHostKeyScannerAddressTest`): a listener on loopback sees the
connection although the name `registered-name.invalid` resolves nowhere, and `getRemoteHostname()` is that name. Against
a real `sshd` (a system `sshd` on `127.0.0.1:2299`, three generated host keys, measurement only): the verifier received
`hostname=registered-name.invalid port=2299`, and the scan returned all three keys, whose fingerprints equal
`ssh-keygen -lf` for each (ed25519, ecdsa-sha2-nistp256, rsa). The pin check is untouched: `FingerprintVerifier` still
runs on the connection. **Residual, as D-REG section 7.3 states it:** the JDK `HttpClient` used by `DirectHttpProbe` and
`DirectHttpFetcher` cannot be told an address, so it re-resolves the name when it sends; for the probe the TCP connect that
decides reachability goes to the checked address, and the check-to-send window remains. An IP-literal registration closes
it. The SSH paths have no such window.

*Decisions made in the task (for the wave review).* (1) `TargetAddressPolicy` has **two** abstract methods
(`check`, `connectable`), so it is not a functional interface: a lambda implementing it is invisible to the arch rule, an
anonymous class is not. Tests use `StubTargetAddressPolicy`. (2) `OfferedHostKey.algorithm` is the **key type**
(`ssh-ed25519`, `ecdsa-sha2-nistp256`, `ssh-rsa`), not the signature algorithm the scan iterated: an RSA key answers three
of those with one key, so the first would be an arbitrary label (plan text: "the algorithm name it already iterates"). (3)
The operator command's `SshHostKeyScanner.scan(host, port)` keeps connecting by name with no policy (it runs on the
operator's machine and that is the status quo, OQ-3's own text); only `SshHostKeyScanService` reaches `scanChecked`. (4) An
unresolvable name is `UnknownHostException` from the policy and keeps each connector's old behaviour (`ssh.connection.failed`,
probe exit 6, fetcher `connect.failed`); only a policy refusal is `target.address.refused`. (5) The package-private
`SshDockerRuntimeAdapter(catalog, transport, hasher)` used by 13 test sites defaults its direct probe to the strict policy.

*Not done here.* The fixture IT ("scanning the T0.2 fixture returns every key type") waits for the owner's approval of the
image; the key types are covered meanwhile by the real-`sshd` measurement above and by `algorithmOf`'s unit test. The scan's
host grammar and its per-session rate limit are T1.1's validator and T1.4's route. No `console.yaml`, `ErrorCode`,
`ConsoleOperations`, Postman or inventory change was needed, so no contract check was run; `ErrorCode`'s
`TARGET_ADDRESS_REFUSED` and `HOST_KEY_NOT_OFFERED` arrive with T1.4.

### T1.3 — `sm_data` migration **`V022__target_connection_events.sql`** (combined schedule; next free at merge)

- [x] `target_connection.credential_public_fingerprint` (nullable, grammar CHECK), added to the runtime's
      **`UPDATE` column grant only**. V007 grants table-level `SELECT, INSERT` already.
- [x] `target_connection_event` (D-REG §9): `SELECT, INSERT` to `sm_dp_runtime`, no `UPDATE`/`DELETE`, tenant first.
- [x] Update the pinned migration/CHECK/grant counts the existing tests assert.
- [ ] Pin the migration's checksum anchor (`backend/scripts/frozen-migration-anchors.txt`) **when published** (owed: V022 is
      unpublished, so no anchor exists yet, and `check-frozen-migrations.py` reports it as "not covered by any anchor"; this is
      the OR-22 follow-up commit that makes a later edit of V022 detectable; W2 review W2C-3).
- Tests: the runtime cannot `UPDATE` or `DELETE` an event row (42501); the grant/CHECK count tests updated.

*Results (2026-10-07 night, wave W2 lane C, `x/w2c`; Docker down: no Testcontainers PostgreSQL 14 run happened. The ITs named below ran only on a local PostgreSQL 16 harness, indicative, PG16 harness outside the repo; the postgres:14 run is owed):* **Built:** `sm_data`
**`V022__target_connection_events.sql`** (unpublished, so no anchor yet: it joins V021 and sm_control V015-V017 in
`check-frozen-migrations.py`'s "not covered by any anchor" note). `target_connection.credential_public_fingerprint text NULL`
with `target_connection_credential_fp` (the `host_key_fingerprint` grammar), in the runtime's **`UPDATE` column grant only**
(V007's table-level `SELECT, INSERT` already cover the rest). `target_connection_event` (D-REG §9): `(tenant_id, target_id,
event_id)` key, `kind` closed to the five values, `actor` and `correlation_id` uuid, `client_address` one short token,
`changed_fields text[]` (names only: a per-element grammar, at most 16, no NULL element), `public_key_fingerprint`, `recorded_at`;
a foreign key **within** `sm_data` to `target_connection` (`I7` holds) and two shape CHECKs beyond the grammars: the public
fingerprint rides `registered` and `credential_rotated` and no other kind (`target_connection_event_fp_iff_key_set`), and
`edited`/`repinned` name at least one field (`target_connection_event_names_fields`). Grants: `SELECT, INSERT` at table level and
nothing else, so no column is runtime-updatable and nothing is deletable. **Pinned counts moved:** `sm_data` migrations 21 -> 22
(`ApplyPersistenceConfigurationIT` x2, `SmDataBootstrapIT`, `SmMetricsMigratorIT` x4), tables 23 -> 24 (with
`flyway_schema_history`), CHECKs 105 -> 112 (`RefusalShapesIT`: 1 on the connection, 6 on the event table),
`InventorySchemaIT`'s runtime `UPDATE` surface on `target_connection` gains the new column; `CHECKSUMS.sha256` line added.
New `TargetConnectionEventIT` (10 tests, Testcontainers, `disabledWithoutDocker`).
**Measured (this night, with Docker down, on a throwaway local PostgreSQL 16.15 cluster in the scratch directory):** first with
the real `sm_data_phase0.sql` and V001-V022 applied through `psql` as `sm_dp_migrator` after `SET ROLE sm_dp_owner`, then probed as
`sm_dp_runtime`; and, later the same night, the real IT: `TargetConnectionEventIT` **10 of 10 green** (with V023 also present) and
the count-pinned ITs that moved (run with plan 012 T3's V023 also present, so against that task's counts 23 / 25 / 117 rather than V022's 22 / 24 / 112; the same assertions one step on: `SmDataBootstrapIT` except its two PostgreSQL 14 assertions, `RefusalShapesIT` 7 of 7,
`InventorySchemaIT` 82 of 82, `ApplyPersistenceConfigurationIT` 5 of 5, `SmMetricsMigratorIT` except the one wrong-password case a
trust-auth cluster cannot refuse) through a TEMPORARY, never-committed edit of the harness that pointed `SmDataPostgres` at that
cluster; it is **indicative, PG16 harness outside the repo; the postgres:14 run is owed**, and it is not the Testcontainers PostgreSQL 14 the floor names. The CHECK count is 112 at V022 and the table count 23 (+ history).
Nothing here uses a feature newer than 9.5 (`array_position`, `cardinality`, `array_to_string`).
**Owed (Docker down 2026-10-07 night), on the Testcontainers PostgreSQL 14:** `TargetConnectionEventIT` (10, red-first against V021
and one mutation each: drop `fp_iff_key_set`, drop `names_fields`, widen the grant to `UPDATE`, drop the foreign key - **none of the
mutations was run**; the grammars' bad inputs and every named refusal were seen to fire in the local run) and the pinned-count ITs
above. `DataPlaneMigrationChecksumsTest` (4 of 4) and `mvn -o -f backend/pom.xml test-compile` ran as ordinary offline tests.
**Not done here, by design:** the wiring. **T1.4 must wire the column** exactly as T1.1's Results say (read it first, fall back
to `storesKey` on `NULL`, back-fill in the same transaction, write it on insert and rotation) and write `registered`, and T1.5
the other four kinds: `InventoryService.java` is HF7's and plan 008 S3's in wave 2, so lane C does not touch it. No readiness
stage was added for V022 because no binary reads either object yet (T1.4 adds it with the first reader, as V021's did).

### T1.4 — Gateway: scan, register, read

**Needs plan 012 T1** (`target.edit`, which `getTargetConnection` requires under OQ-13 (a)).

Files:
- `console-gateway/.../http/TargetController.java`, plus `RegisterTargetRequest`, `HostKeyScanRequest`,
  `TargetDetail`, `TargetConnection` and `CredentialSummary`;
- a new `TargetRegistrationOrchestrator`;
- `OperationPolicy`/`ConsoleOperations` entries, and a `ResourceResolver` entry for `ownershipScope` bodies;
- `dp-api` `TargetConnectionService.precheckRegistration`;
- a **new `cp-api` port `TargetProbeAudit`** with its `cp-core` implementation over `AuditLog` (the
  `ExportAudit`/`ExportAuditConfiguration` precedent; the gateway cannot reach `cp-core`'s `AuditLog`, `OR-10`);
- under OQ-14 (b), a new `CredentialTransportFilter`.

- [x] `scanHostKeys`: `target.create` at the body's `ownershipScope`; per-session token bucket (default 10/min,
      `429 RATE_LIMITED`, the `MetricsRateGate` pattern); recorded through `TargetProbeAudit` (verb, scope,
      outcome; never the keys).
- [x] `registerTarget`, in D-REG §8's order:
      1. The interceptor authorizes, writing nothing.
      2. `precheckRegistration`: grammar, key parse, address policy, scan with the pin offered, and a host-key
         uniqueness probe.
      3. CP `TargetRegistration.register`.
      4. DP `register`.

      Map replay and conflict per §8. **Catch a unique violation in either insert (`target_name_once`, the DP
      primary key) and re-run the replay path.** Zeroise the `char[]` in a `finally`. Responses:
      `201`/`200`/`409`/`422`/`503`.
- [x] `getTargetConnection`: under the OQ-13 verb (not `target.view` under (a)) at the target's scope; `404` when
      invisible or retired.
- [x] Under OQ-14 (b): `CredentialTransportFilter` on `registerTarget`, `rotateTargetCredential`, `createUser` and
      `editUser`. It answers `403 INSECURE_TRANSPORT` before the body is read unless one of these holds:
      `isSecure()`; the peer is a trusted proxy and `X-Forwarded-Proto` is `https`; the peer is loopback.
- [x] **MF-R3:** a launcher IT registers a key through HTTP with every appender captured at the production levels
      and asserts no line of the key, and no `credential_ref`, appears anywhere.
- [x] ArchUnit: every gateway request record with a field named `privateKey`, `*Credential` or `value` overrides
      `toString`.
- Tests (launcher ITs, real PostgreSQL; the T0.2 fixture or the OQ-15 (b) fakes):
  - S1's register path end to end.
  - **Every refusal leaves nothing.** For each of `CREDENTIAL_INVALID`, `HOST_KEY_NOT_OFFERED`,
    `TARGET_ADDRESS_REFUSED`, `503` and the host-key-held `409`: no CP row, no DP row, no secret row, and an
    immediate corrected retry with a different `kind` succeeds.
  - **S5 sequence:** a failure injected after the CP commit, then the same `POST` completes (`201`), then a replay
    (`200`), then a different key (`409`), then the same key re-exported as PEM (`200`).
  - **Concurrent double-submit:** two identical `POST`s released by a barrier; both answer `2xx` (one `201`, one
    `200`), and there is exactly one CP row, one DP row and one secret row.
  - A Viewer gets `403`; another organization's scope gets `404`; `listTargets` shows the degraded row between the
    two writes.
  - Under OQ-14 (b): plain HTTP from a non-loopback peer gets `403`; loopback passes; a trusted proxy with
    `X-Forwarded-Proto: https` passes.

**Results (T1.4, 2026-10-08, wave W3 lane C, `x/w3c`, Sonnet 5.5; Docker down: no Testcontainers PostgreSQL 14 run happened).**
Built, in two commits. **Data and control plane:** `dp-api` `TargetConnectionAdministration` (a **separate port**, not the
plan's `TargetConnectionService.precheckRegistration`: widening that port would have forced every implementer and fake of it
to change in a wave in which other lanes edit them; one `InventoryService` bean answers both) with `precheckRegistration`
(grammar, key parse, address policy, a fresh scan that must offer the pin, a host-key uniqueness probe that does not count
the replayed target's own keys; no write, no secret), `registerConnection` (record, sealed key, host keys and the
`registered` event in ONE transaction; an insert race rolls back whole, secret included, and re-runs as the replay) and the
management `connection` read; `InventoryService` now compares the recorded `credential_public_fingerprint` first (no secret
opened on the normal path), back-fills a legacy row, and writes the event with the request's correlation id and client address
(the operator command writes the same event under `operator-cli`); its canonical constructor gains the scan service (11
parameters; `MetricsDeclarationConstructorTest` edited 10 -> 11 with its reason, as W1 did 7 -> 8); a readiness stage for
V022 (the first reader of its two objects); `cp-api` `TargetProbeAudit` + `cp-core` `AuditLogTargetProbeAudit` (a scan is
one `target.create` audit row, `completed`/`unreachable`/`address_refused`/`rate_limited`, never a host or a key);
`TargetService` retries once on a `target_name_once` violation and answers the winner's row. **Gateway:**
`TargetRegistrationController` (a new class beside `TargetController`, whose constructor its tests pin) with
`scanHostKeys`, `registerTarget`, `getTargetConnection`; `TargetRegistrationOrchestrator`; `ScopeRef`, `HostKeyScan(Request)`,
`OfferedHostKey`, `TargetConnectionSpecRequest`, `RegisterTargetRequest` (redacting `toString`, unknown keys refused),
`TargetConnection`, `CredentialSummary`, `TargetDetail`; `ErrorCode` +4 (`CREDENTIAL_INVALID`, `HOST_KEY_NOT_OFFERED`,
`TARGET_ADDRESS_REFUSED` 422; `INSECURE_TRANSPORT` 403); a per-session `ScanRateGate` (10 a minute, `sm.gateway.target-scan`).
**Contract:** `console.yaml` +3 operations (51) with `x-sm-scope: named` adopted in `validate-openapi.py`'s `SCOPES`, the
schemas, parameter `TargetId`, the four `ErrorCode` members and their bullets; `ConsoleOperations` +3 (`target.create` x2,
`target.edit`); `ENDPOINT-INVENTORY.md` 3 rows and the count 48 -> 51; Postman regenerated (`check-postman.py` OK: 51
operations, 50 served, 1 wired); the launcher's route set +3. `run-checks.sh --strict`: every check passes except 14 (the known
`ajv/dist/2020` environment gap).

*Decisions made in the task (for the wave review).* (1) **Body-scoped authorization.** `ResourceResolver`'s own text says a body
is unreadable in `preHandle`, yet D-REG section 8 and this task put the resolver on the body's `ownershipScope`. Resolved without
moving authorization out of the interceptor: `PeekableBodyFilter` wraps every UNSAFE request in `PeekableBodyRequest`, which
reads nothing unless asked and, when `OwnershipScopeResolver` asks, holds at most 64 KiB and replays it unchanged; it matches
no path, so no URL variant can reach a body-scoped handler without it, and a request that did not pass through it fails CLOSED
(`IllegalStateException`, never a guess). The authorized node is recorded on the request and the handler's
`requireAuthorized` refuses to act on any other (a parser differential becomes a refusal). `ResourceResolver`'s javadoc is not
edited: it remains true of every other resolver. (2) **`CredentialTransportInterceptor`, not a `CredentialTransportFilter`**,
keyed on a `@CredentialBearing` mark on the handler (an arch rule fails the build if a handler that takes `privateKey`,
`initialCredential` or `resetCredential` lacks it), so no spelling of the path can skip it; it is registered before
`AuthenticationInterceptor`. **Narrower than the plan's three alternatives read independently:** when the peer is a configured
trusted proxy only its `X-Forwarded-Proto` (the last value) counts, so a proxy on the same machine forwarding `http` is refused
rather than passing as loopback; a peer that is not a trusted proxy cannot assert `https` with a header. `rotateTargetCredential`,
`createUser` and `resetUserCredential` add the mark on their handlers (T1.5, T3.2). (3) **`OperationPolicy` needed no entry**:
the three are plain catalog operations; `x-sm-scope: named` has no policy effect. (4) **Package cycle (W1 review D-6, decided).**
`dp.inventory` and `dp.apply.runtime.ssh` still depend on each other: `ssh` -> `inventory` through `TargetAddressPolicy`,
`StrictTargetAddressPolicy`, `TargetAddressProperties` and `TargetConnectionValidator` (13 production files); `inventory` ->
`ssh` through `PrivateKeyInspector`, `TablePlacementCatalog` and `TargetAddressConfiguration`. **T1.4 adds no edge in either
direction** (the pre-check reaches the scan through the `dp-api` port `HostKeyScanService`, never through `ssh`). The seam to
move is the policy trio (plus the host grammar) into a neutral package, but the move edits about 45 files across
`health`, `logs`, `metrics`, `apply.production` and `apply.runtime.ssh` that three other lanes edit this wave, so it is
**deferred to a quiet wave (W6)** rather than done here; nothing enforces acyclicity today, and no rule was added. (5) **The 35 s
blackholed scan** (7 attempts x 5 s read timeout on a peer that accepts and says nothing): decided and fixed, because a scan is
now on the request path. `SshHostKeyScanner.scanKeys` gets a 10 s budget for the whole scan (each attempt's timeout is the smaller of 5 s and
what is left) and stops at the first attempt that timed out without seeing a key (a peer silent to the first is silent to the
rest); `SshHostKeyScannerSilentHostTest` proves one accepted connection and under 9 s. (6) **`ownershipScope` derivation:** the wire
`ScopeRef` takes the deepest id; the sub-organization of an environment is looked up through `HierarchyDirectory` (ids sent
together must agree, else `404`); a solution or an instance is `422`. (7) `CredentialSummary.setAt` is a `String` (the gateway
has no `java.time` Jackson module on its test classpath, and every other timestamp on this wire is a `String`).

*Tests that ran (offline, this night).* `dp-core` unit suites green (including `InventoryRegistrationTest` 28,
`InventoryReadinessTest` 4, `SshHostKeyScannerSilentHostTest` 2); `cp-core` `AuditLogTargetProbeAuditTest` 5 and
`TargetServiceRegistrationRaceTest` 4; gateway `TargetRegistrationControllerTest` 36, `CredentialTransportInterceptorTest` 11,
`ScanRateGateTest` 5, `PeekableBodyRequestTest` 5, `OwnershipScopeResolverTest` 8, `TargetRegistrationSchemaMirrorTest` 10,
`TargetRegistrationLogCaptureTest` 1 (root logger at TRACE, every path: no byte of the key in any message, argument, MDC value or
stack); `arch-tests` `CredentialBearingRecordsTest` (two rules, each with planted violations and legal forms). **Written
after the code, not before** (the gateway half): the red-first proof is replaced by mutation proofs, **each run, red, and reverted**:
differential guard removed (1 red); control-plane row written before the pre-check (7 red); key not zeroised (1);
transport interceptor admitting everything (9); the `@CredentialBearing` mark removed (3); the rate gate never refusing (5); a
different registration under the name treated as a replay (1); a trusted proxy's `http` believed (3); the resolver
authorizing at a guess when the body is not peekable (1); the rate-limited scan not audited (1); the grammar message echoed
(1). **Real-PostgreSQL ITs, run ONLY on a throwaway local PostgreSQL 16.15 cluster in the scratch directory through a TEMPORARY,
never-committed edit of the three harness classes (`SmDataPostgres`, `SmControlPostgres`, `TwoSchemaTestDatabase`) that pointed them at it:
INDICATIVE, PG16 harness; the postgres:14 run is owed, and none of this is a pass of the floor.** `TargetRegistrationEndpointIT`
(launcher, the real boot over HTTP) **14 of 14** after it found one real defect (`TargetRegistrationOrchestrator` had two
constructors and no `@Autowired`, so the production context did not start; fixed); `TargetRegistrationIT` 11, `TargetCredentialReplayIT` 12,
`TargetConnectionEventIT` 10, `InventoryServiceIT` 17, `ApplyPersistenceConfigurationIT` 5, `InventorySchemaIT` 82,
`InventoryConfigurationIT` 2, `RefusalShapesIT` 7 (all `dp-core`), `SmDataBootstrapIT` 9 of 11 (its two PostgreSQL-14 assertions
cannot pass on 16), `AuditLogTargetProbeAuditIT` 1, `TargetServiceIT` 15 (with the new concurrent-registration test, which was
red without the retry: `DuplicateKeyException` on `target_name_once`, and green with it). The non-loopback plain-HTTP case ran live
(`403 INSECURE_TRANSPORT` from the machine's own LAN address). Not run anywhere: the cp-core test
`SmControlLockoutRecoveryMainTest.aUsernameSelectorWithSurroundingWhitespaceIsTrimmedBeforeHashing` needs Docker (pre-existing).

*Whole offline build, `mvn -o test` over the reactor, failures ignored to see them all:* `cp-core` 500 (1 error: the pre-existing
Docker-needing `SmControlLockoutRecoveryMainTest.aUsernameSelectorWithSurroundingWhitespaceIsTrimmedBeforeHashing`), `dp-core`
1289 (1 error: the pre-existing Docker-needing `IngestServiceContractTest`), `console-gateway` 743 (the run found two failures of
`DeclaredBadRequestsTest`, both mine and both fixed: `getTargetConnection` takes a UUID path value so the contract now declares its
`400`, and the test's tripwire map of explicit-400 sites gained `OwnershipScopeResolver` 2 and `PeekableBodyRequest` 3; re-run green),
`arch-tests` green, `launcher` unit tests green.

*OWED (Docker down), on the Testcontainers PostgreSQL 14 unless noted.* `TargetRegistrationEndpointIT` (14: the register path,
five refusals that leave nothing, S5, concurrent double submit, authorization at the scope, the connection read, the scan and its
audit and rate limit, MF-R3 against the real appenders, the plain-HTTP refusal) and `ProductionAuthenticationIT#
productionContextCarriesNoDemoNoMigratorAndNoExtraRoute` (the route set, 51) and its three siblings; `TargetRegistrationIT`,
`TargetCredentialReplayIT`, `TargetConnectionEventIT`, `InventoryServiceIT`, `InventorySchemaIT`, `ApplyPersistenceConfigurationIT`,
`InventoryConfigurationIT`, `RefusalShapesIT`, `SmDataBootstrapIT`, `AuditLogTargetProbeAuditIT`, `TargetServiceIT`. The mutation proofs of
the data-plane half were not re-run for this task beyond those named. **Real-SSH ITs (T0.2's fixture, now written under `OR-124`):**
`SshdFixtureIT#scanningTheFixtureReturnsEveryKeyTypeItOffersWithTheFingerprintsOfItsOwnPubFiles` (the three host keys
`ssh-keygen -A` generates, by the fingerprints of the `.pub` files) and `#aKeyInAuthorizedKeysAuthenticatesAndOnceRemovedIsRefusedAtOnce`
(the old key refused after `setAuthorizedKeys`, which also settles the one image fact its README does not state: that `sshd`
re-reads `/etc/authorized_keys/<user>` on each authentication). Both were compiled, never run. A registration through the
fixture end to end is owed with T1.5's S3.

*Results, W3 review fix round (2026-10-08, wave W3 lane C, `x/w3c`; Docker down: no Testcontainers run happened).* The Opus review
found 9 items (1 MAJOR, 5 MINOR, 3 NIT); all were addressed except where marked. Each commit cites "W3 review <ID>".

- **W3C-1 (MAJOR, fixed).** The review's claim was checked in the jars, and the first draft of the proof was wrong: the Boot
  `TomcatWebServerFactoryCustomizer` is registered by `EmbeddedWebServerFactoryCustomizerAutoConfiguration`, not by
  `ServletWebServerFactoryAutoConfiguration`, so a test context importing only the latter passes whatever the properties say
  (measured: valve absent with the property removed). With the right import and `spring.main.cloud-platform=kubernetes`, the
  unpinned launcher installs `RemoteIpValve` and a LAN peer's `X-Forwarded-Proto: https` and `X-Forwarded-For: 127.0.0.1` both
  reached a `@CredentialBearing` handler with `200` (red). Fix: `server.forward-headers-strategy=none` in the launcher's
  `application.properties`; `ForwardedHeadersLockdown` (launcher) refuses to boot on anything but `none`, and on either
  `server.tomcat.remoteip.protocol-header` / `.remote-ip-header` (read from the jar: `hasText(protocolHeader) ||
  hasText(remoteIpHeader) || getOrDeduceUseForwardHeaders()` installs the valve whatever the strategy). `CredentialTransportInterceptor`
  already trusted `X-Forwarded-Proto` only from a configured trusted proxy; its tests gain the spoofed `X-Forwarded-For` / `Forwarded`
  cases from an untrusted private peer. `ForwardedHeadersSpoofTest` (launcher, 8 tests, a real embedded Tomcat, no database)
  green; with the property removed and the lockdown disabled, 6 of its then 7 tests failed, including both `403` expectations
  (answered `200`).
  The same pin closes the identical exposure in `OR-20`'s `ClientAddressResolver`.
- **W3C-2 (MINOR, fixed, flagged for the owner).** `registerTarget` takes a token from the `ScanRateGate` before the pre-check and
  records a `TargetProbeAudit` row for its scan (`completed`, `unreachable`, `address_refused`, or `rate_limited` as a `DENY`);
  a refusal before any host is contacted (key parse, grammar) is not recorded, as in `scanHostKeys`. The design text names only
  T1 as rate-limited (section 7.3); it does not forbid this, so a dated rider records it. **Owner question:** confirm the extension,
  or rule that the pre-check should have its own budget; the cost of the shared bucket is that a session registering ten targets
  in a minute meets `429`.
- **W3C-3 (MINOR, fixed).** Every scan attempt now has a hard deadline: a daemon watchdog closes the attempt's socket (sshj's
  `SocketClient.socket` is assigned before `connect`, so the close also unblocks a pending connect). Name resolution is not covered
  (the data plane connects to an address it already resolved). Red proof: with the watchdog disabled the dribbling-peer test
  (`SshHostKeyScannerSilentHostTest#aPeerThatDribblesBannerBytesCannotHoldTheScanPastItsBudget`) hangs until surefire kills the
  fork; with it, the scan returns inside the budget.
- **W3C-4 (MINOR, fixed).** Two new `dp-api` exceptions, `TargetCredentialUnreadableException` and
  `TargetConnectionChangedException` (both `IllegalStateException`, fixed text), replace the package-private subclass and the two
  bare `IllegalStateException`s. The orchestrator maps them to their own `409` details (`CREDENTIAL_UNREADABLE`: rotate to repair;
  `RECORD_CHANGED`: retry) instead of "a different connection record". The contract's `409` text and the inventory are widened.
- **W3C-5 (MINOR, fixed).** The three-argument `updateCredential` now clears `credential_public_fingerprint`, so no path repoints a
  key and leaves the old key's fingerprint. IT owed (below).
- **W3C-6 (MINOR, recorded).** See the OWED list: the integrator merges B (+2 operations, 50) then C (+3), so every literal "51" in
  this lane's files becomes 53. Where the lane's files hold the count: `ENDPOINT-INVENTORY.md` line 9 (twice); this plan's T1.4
  Results ("(51)", "48 -> 51", "51 operations") and OWED ("the route set, 51"). `check-postman.py`, `validate-openapi.py`,
  `ConsoleOperationsMirrorTest` and the launcher route-set assertion derive from the contract and the handlers, so they need no
  edit, only a regenerate and a re-run.
- **W3C-7 (NIT, fixed).** `registerConnection` retries once on a `ConcurrencyFailureException` (PostgreSQL `40P01` is mapped to
  `DeadlockLoserDataAccessException` by Spring's `sql-error-codes.xml`, read from the 6.2.19 jar), like a lost race; a second one
  answers `TargetConnectionChangedException` (`409`, retry), not a `500`. Unit-tested with the in-memory rows (2 tests). The real
  two-session deadlock is not reproduced (owed).
- **W3C-8 (NIT, fixed).** `PeekableBodyFilter` zeroes the peek buffer when the request ends (not when async has started).
- **W3C-9 (NIT, fixed).** (a) `TargetRegistrationEndpointIT`'s javadoc no longer cites a fixture IT that does not exist, nor OQ-15 (b);
  it states the OQ-15 (a) ruling and that registration through the real sshd is owed with T1.5's S3. (b) The proxy must overwrite
  `X-Forwarded-Proto`: stated in `application.properties`, `backend/README.md`, the interceptor's javadoc and the design rider.

*Tests run in this round.* **Offline, no Docker** (`mvn -o -f backend/pom.xml -Dmaven.test.failure.ignore=true test`, the whole
reactor, on the final code): `cp-api` 49, `mesh-events` 160, `cp-core` 500 (1 error, the pre-existing Docker-needing
`SmControlLockoutRecoveryMainTest.aUsernameSelectorWithSurroundingWhitespaceIsTrimmedBeforeHashing`), `dp-core` 1292 (1 error, the
pre-existing Docker-needing `IngestServiceContractTest`; 1 skipped, `InMemoryRuntimeAdapterContractTest`), `console-gateway` 755,
`launcher` unit tests 94 (8 of them the new `ForwardedHeadersSpoofTest`), `arch-tests` 94; no new failure. New or changed unit tests: `ForwardedHeadersSpoofTest` 8,
`CredentialTransportInterceptorTest` +2 (13), `SshHostKeyScannerSilentHostTest` +1 (3), `TargetRegistrationControllerTest` +8 (44),
`InventoryRegistrationTest` +2 (30), `InventoryStoredKeyUnreadableTest` (assertion added), `PeekableBodyRequestTest` +2 (7).
`contracts/openapi`: `validate-openapi.py` PASS, `generate-postman.py` + `check-postman.py` OK (51 operations, 50 served, 1 wired),
`run-checks.sh --strict` every check passes except 14 (the known `ajv/dist/2020` gap; the symlink to the primary checkout's
`node_modules` was removed before committing). **INDICATIVE, on a throwaway PostgreSQL 16.15 cluster in the scratch directory
through a TEMPORARY, never-committed edit of `SmDataPostgres` and `TwoSchemaTestDatabase`** (no Testcontainers; the postgres:14 run
is owed and this is not a pass of the floor): `TargetRegistrationIT` 12 of 12 (with the new W3C-5 test, which failed with the
fix reverted: the stale fingerprint stayed), and `TargetRegistrationEndpointIT` 15 of 15 (with the new W3C-2 test; this also booted
the real `SolutionManagerApplication` with `ForwardedHeadersLockdown` and the pinned property). Not run: `ProductionAuthenticationIT`
(its own provisioning, not the edited harness), the real-SSH ITs, and any two-session deadlock.

*OWED after this round (add to the T1.4 list above; none ran):* **(1)** after rebasing onto `x/w3b`: regenerate Postman
(`generate-postman.py`), set the inventory count (line 9) and this plan's literals to **53**, and re-run `validate-openapi.py`,
`check-postman.py`, `ConsoleOperationsMirrorTest`, `ProductionAuthenticationIT#productionContextCarriesNoDemoNoMigratorAndNoExtraRoute`
(and its three siblings) and `run-checks.sh --strict` before merging (W3C-6). **(2)** On Testcontainers PostgreSQL 14 (each ran once on the
PG16 harness above, indicative): `TargetRegistrationEndpointIT#aRegistrationsScanIsAuditedLikeAScanAndSharesTheSessionsScanBudget` (a refused registration leaves one
`target.create` audit row and the eleventh in a session is `429` with `Retry-After` and no control-plane row; W3C-2);
`TargetRegistrationIT#repointingTheKeyWithoutItsFingerprintClearsTheOldOnesSoAReplayOfTheOldKeyIsNotCalledTheSame` (the three-argument
`updateCredential` clears the stale fingerprint, so a replay of the old key is `409` and of the new key is `200`; W3C-5). **(3)** A
real two-session deadlock IT for W3C-7 (two registrations of one machine under two names with different pins, released together:
exactly one `201`, the other `409 HOST_KEY_HELD`, never `500`), and a deadlock-injection proof of the retry. **(4)** The earlier OWED list stands, with `TargetRegistrationEndpointIT` now 15 tests and `TargetRegistrationIT` 12. **(5)** `ForwardedHeadersSpoofTest` ran offline; its LAN-peer cases need a
site-local IPv4 address on the build host and are skipped (assumption) without one, so a CI host with none runs 6 of 8.

### T1.5 — Gateway + DP: edit, re-pin, rotate, test, retire

- [~] `editTargetConnection` (`target.edit`): host/port/user/pin. Any change of host, port or pin re-scans. The
      envelope fields are `400`. Writes a `target_connection_event` (`edited` or `repinned`, field names only).
      **S2 live (gate L3 `l3-edit`) OWED**: written, never run against a host (W4 review `W4C-8`; re-designed by `W4C-6`).
      *(2026-10-09 note: `l3-edit` is NOT RUN, pending the owner's permission for its root-level fence (`l3-rotate`, S3, passed). See the [combined schedule's live gate results](combined-008-012-013.md#live-gate-results-2026-10-09).)*
- [x] **No edit while work is in flight (OQ-12 (a), R4):** for host/port/pin changes, `SELECT … FOR UPDATE` the
      `sm_data.apply_scope` row of every instance with a live placement on the target, in `instance_id` order. Then
      refuse `409` if one of those placements is named by an `apply_request` with `terminal_at IS NULL` (`REQUESTED`,
      `RUNNING`, `APPLY_INDETERMINATE`) or by a `pending` `fleet_member` of a `RUNNING` `fleet_action`. Otherwise commit under the lock.
- [x] **Re-pin host keys (R9):** in the same transaction, set `retired_at` on the target's `target_host_key` rows
      the fresh scan no longer offers. Set `retired_at = NULL` on its own retired rows the scan offers again (the
      primary key makes the pinned-key trigger's `DO NOTHING` keep them retired otherwise). Insert the rest. This
      uses V012's existing `UPDATE (retired_at)` grant.
- [x] `rotateTargetCredential` (`target.edit`): T1.1's compare-then-rotate; event `credential_rotated` with the new
      public fingerprint. **No pool change** (R3): the pool's `Identity(credentialRef, pin)` already replaces a
      session on its next lease, and the apply path reads fresh per operation.
      **S3 live (gate L3 `l3-rotate`) OWED**: written, never run against a host (W4 review `W4C-8`).
      *(2026-10-09 note: `l3-rotate` PASSED; see the [combined schedule's live gate results](combined-008-012-013.md#live-gate-results-2026-10-09).)*
- [x] `testTargetConnection` (`target.use`): connect with the pin, authenticate, and under OQ-9 (a) run the fixed
      `docker version --format '{{.Server.Version}}'`. **MF-R2** runs on the E2E host: stop the daemon, and use a user
      without the `docker` group. Map each to `docker.available=false` without a `5xx`. ⚠️ **Owner gate:** stopping
      the Docker daemon and creating an OS account are root-level host changes; `OR-110` covers Cache containers, and
      `OR-121`'s extension of it names plan 014's gate only. MF-R2 runs only with the owner's explicit permission
      (restoring the daemon and removing the account after), and last among that wave's live gates.
      ⚠️ **Done except MF-R2's two live measurements (L4), which are OWED:** see the Results.
      *(2026-10-09 note: live gate L4 PASSED, both measurements; see the [combined schedule's live gate results](combined-008-012-013.md#live-gate-results-2026-10-09).)*
- [x] `retireTarget` (`target.edit`): DP first, then CP (`TARGET_RETIRED`); replay `200`.
      - Map V007's `target_connection_retire_guard` exception (`a target cannot be retired while it has live
        placements`) to `409`. The service-side pre-check exists only for the error text (R10).
      - **A CP row with no DP row retires CP-only (R2).**
      - Under OQ-11 (b), the retired name is free for a new registration.
- Tests:
  - **S2 (reactor):** a new host and a re-pin keep placements; un-offered keys are retired, a re-offered key is
    revived, and `target_host_key_once` still holds for both.
  - **S2 (live, E2E host):** the next health probe and the next apply use the new record.
  - **S2′:** an edit while an apply is `RUNNING` is `409`; the same edit after the apply ends is `200`; and a race IT
    in which admission and edit contend on the lock has exactly one winner, with neither straddling.
  - An `sshUser`-only edit and a T5 rotation are **not** refused while work is in flight.
  - **S3:** rotate two targets. The fixture's `authorized_keys` then holds only the new key, so the old key is
    refused by the fixture (under OQ-15 (b), this check is live on the E2E host). The next operation uses the new
    key, and a double submit writes no second secret row.
  - Retire: with a live placement it is `409` (via the trigger); a CP-only orphan retires `200`; the event rows are
    written in the same transaction (rollback test).

**Results (T1.5, 2026-10-08, wave W4 lane C, `x/w4c`, Sonnet 5.5; Docker was down: no Testcontainers run happened, and no live gate ran).**
Built, in two commits on the W3-integrated base (`c0b9d49a`). **No migration**: V007 grants `UPDATE (host, port, ssh_user,
host_key_fingerprint, credential_ref, retired_at)` on `target_connection` and V012 `UPDATE (retired_at)` on `target_host_key`, which
is all that was needed. Operations in `console.yaml`: 53 -> **57** (the integrator recomputes after lane D's seven).

*What exists.* **`dp-api`:** `TargetConnectionManagement` (`planEdit`, `editConnection`, `rotateConnectionCredential`,
`retireConnection`), `TargetConnectionTester` + `TargetConnectionTestResult`, `TargetConnectionEdit`, `EditPlan`,
`ConnectionRetirement`, `TargetWorkInFlightException`, `TargetHasLivePlacementsException`. **`dp-core`:** `InventoryService` implements
the new port (the HF7 file; its constructor is unchanged, so `MetricsDeclarationConstructorTest` needed no edit);
`TargetConnectionRows` +5 and `PlacementRows` +3 methods (defaults that fail loudly, as T1.4's); the JDBC stores; the
SSH half `SshTargetConnectionTester` + `SshjTargetSshProbe`; `TargetConnectionTestConfiguration`, imported by `InventoryConfiguration`
(so `DataPlaneConfiguration`, HF4, is not touched). **`cp-api`/`cp-core`:** `TargetRegistration.retire` + `TargetService.retire`
(`target.edit` at the target, one `TARGET_RETIRED` audited write, replay `200` with the original time), `TargetRepository.markRetired`,
`TargetProbeAudit.rescanned` and `.tested`. **Gateway:** `TargetManagementController` (a new class, so `TargetRegistrationController`
and the test that pins its one credential-bearing handler are untouched), `TargetManagementOrchestrator`,
`EditTargetConnectionRequest`, `RotateTargetCredentialRequest` (redacting `toString`; the handler is `@CredentialBearing`),
`RotatedCredential`, `ConnectionTestResult`, `Target.retiredAt`, `TargetDetail.retiredAt`. **Contract (HF3):** `console.yaml` +4
operations and 5 schemas, `ConsoleOperations` +4, the inventory (+4 rows, 57), the launcher's route set, `generate-postman.py`
(one body override: an empty edit is `400`) and the regenerated collection. `validate-openapi.py` PASS (57 operations);
`check-postman.py` OK (57, 56 served, 1 wired); `run-checks.sh --strict` passes every check but 14 (the known `ajv/dist/2020`
environment gap); `check-frozen-migrations.py` passes; `git diff --check` clean.

*Decisions made in the task (for the wave review).* (1) **`planEdit` before `editConnection`.** The gateway must charge a scan it is
about to cause, and only the data plane knows whether the host, port or pin changes, so the plan is a read-only, I/O-free call
and the edit repeats it under the lock; the plan is never trusted. (2) **No lock across a scan.** The scan is made before the
`apply_scope` locks; under the locks the edit is applied only to the very (host, port, pin) that was scanned, and a record that
moved since is `409 RECORD_CHANGED` (retry). A deadlock the database finds is mapped the same way. (3) **The edit's rescan
follows W3C-2** (open owner item, not decided, not worsened): it draws a token from the same bucket and leaves an audit row, but
of `target.edit` on the target (`rescanned`), not `target.create`; an `sshUser`-only edit is neither charged nor audited. The test
is audited as `target.use` with the furthest step reached and is not rate-limited *(superseded by the wave 4 review fix round, `W4C-4`: it now draws on the same per-session budget and runs one at a time per target)*. If the owner rules W3C-2 the other way, one
method (`chargeRescan`) changes. (4) **`PlacementRows`, not a new lock repository** (the plan names one): the three methods sit
beside `lockApplyScope` and `inFlightOn`, which already use the same table and the same SQL, and a new repository would have
meant a twelfth `InventoryService` constructor parameter. (5) **In-flight is the fleet reservation's own predicate** (`pending` or
`admitted`, not settled) rather than the plan's "`pending` of a `RUNNING` fleet": it is a superset that adds only an admitted
member whose request is in flight anyway, and it is the predicate `retirePlacement` and `fleet_member_reservation` use. (6) **The
operator command's `rotateCredential` now writes the `credential_rotated` event** (origin `operator-cli`): every change of a record
leaves one. (7) **`TargetRegistration.Stored` carries `retiredAt`** (the old boolean is derived), because a retry of a retirement
answers the original time. (8) **`retireConnection` answers `NO_CONNECTION` for an absent record** and the gateway then retires the
control-plane row on its own (R2); it never invents a data-plane row.

*Measured, flagged for the owner* **(fixed in the wave 4 review round, `W4C-1`; see "Wave 4 review fix round" below).** **A pin of a host-key type the transport does not negotiate is unusable.** Against a throwaway
system `sshd` with ed25519, ecdsa and rsa host keys (below), sshj 0.40.0's default configuration negotiated ed25519 and verified
only that key: pinning the ecdsa or the rsa fingerprint gave `hostKeyPinned=false` (so, by the same construction, a refused
connection in apply, health and logs; not measured there). The scan offers all three types (OR-89) and registration accepts any
one as the pin. The connection test reports the truth. The fix (restrict the client's host-key algorithms to the pinned key's type
in the connectors, or refuse such a pin at registration) touches files outside T1.5; recorded as a rider in D-REG section 5.1.
**Residual (stated in the same rider):** the scope locks cover the instances with a live placement on the target when they are
enumerated.

*Tests that ran (offline, no Docker).* Red first for the unit tests means red by **compilation** (the types did not exist), then
green; the ITs were written with the code and have no red-first proof. `dp-core`: `InventoryTargetManagementTest` 37,
`SshTargetConnectionTesterTest` 12, `SshjTargetSshProbeTest` 1, `TargetConnectionTestConfigurationTest` 1 (strict policy: a
loopback target is never connected to); `cp-core`: `TargetServiceRetireTest` 5, `AuditLogTargetProbeAuditTest` +4;
`console-gateway`: `TargetManagementControllerTest` 33 (the real filter, interceptor, resolver, controller, orchestrator and advice;
every port faked), `TargetManagementSchemaMirrorTest` 9 (contract equality), plus the existing mirrors (`SchemaMirrorLockTest`,
`DeclaredBadRequestsTest`, `TargetRegistrationSchemaMirrorTest`) updated for the new operations. Whole reactor, offline
(`mvn -o -f backend/pom.xml -Dmaven.test.failure.ignore=true test`): `cp-api` 49, `mesh-events` 160, `cp-core` 556 (1 error: the
pre-existing Docker-needing `SmControlLockoutRecoveryMainTest.aUsernameSelectorWithSurroundingWhitespaceIsTrimmedBeforeHashing`),
`dp-core` 1375 (1 error: the pre-existing Docker-needing `IngestServiceContractTest`; 1 skipped), `console-gateway` 835, `launcher` 94,
`arch-tests` 102; no other failure. **Mutations, each run, red, and reverted** (a textual mutation, the named tests, a restored file):
`InventoryService`: in-flight check removed, scope lock removed, revive removed, retire-of-unoffered removed, rotation reading the
record unlocked, a stale scan trusted, live-placement pre-check removed (7 of 7 red); the tester: probe exception escaping, authenticated
without the pin, any line taken for a version, retired target tested (4 of 4); `TargetService.retire`: verb check, replay guard, audit
context, wrong verb (4 of 4); the orchestrator and controller: rescan not charged, key not zeroised, test not audited, in-flight
rescan not audited, wrong refusal reason, control plane first, rotation not credential-bearing, outcome mapping (8 of 8).
(One mutation, "authenticated without the pin", was first not caught; a test with an inconsistent probe was added, then it was.)

*INDICATIVE, PostgreSQL 16 on a throwaway local cluster in the scratch directory, NOT the Testcontainers PostgreSQL 14 floor; the
postgres:14 run is owed.* Through TEMPORARY, never-committed edits of `SmDataPostgres`, `SmControlPostgres` and `TwoSchemaTestDatabase`
that pointed them at the cluster and of the `@Testcontainers(disabledWithoutDocker)` line of the ITs run (all restored with
`git checkout`; the cluster was recreated for every run because roles are cluster-scoped). `dp-core`: **`TargetManagementIT` 17 of 17** (the final cut; the first run, with the 16 tests then written, passed beside the
regression ITs `InventoryServiceIT` 17, `TargetRegistrationIT` 12, `TargetCredentialReplayIT` 12, `TargetConnectionMainIT` 17,
`InventoryConfigurationIT` 2, `InventorySchemaIT` 82, `TargetConnectionEventIT` 10, `AddPlacementIT` 11 and `RetirePlacementIT` 13:
192 of 192 in all). `cp-core`:
`TargetServiceIT` 20 of 20 (5 new), `AuditLogTargetProbeAuditIT` 2 of 2, `TargetRegistrationMainIT` 6 of 6. `launcher`:
**`TargetManagementEndpointIT` 14 of 14**, `TargetRegistrationEndpointIT` 15 of 15, `TargetEndpointsIT` 5 of 5. **IT mutations (PG16),
red and reverted:** the scope rows not locked (both race ITs red: the first run of that mutation was NOT caught, because the upsert
itself made the admission wait, and the ITs were changed to create the scope row first), a fleet member ignored, an instance-wide
request ignored, the record read unlocked (two concurrent rotations stored two secrets), no revive, V007's guard not mapped, and
a lock order that differs between two edits (a deadlock, mapped to `409 RECORD_CHANGED`: `twoEditsOfTwoTargetsThatShareInstances...`).
**INDICATIVE, a throwaway system `sshd` on `127.0.0.1:2299`** (three generated host keys, a forced command standing in for Docker; not
the fixture image, not a remote host; stopped afterwards), driving `SshTargetConnectionTester` + `SshjTargetSshProbe` with the
scenarios of `TargetConnectionTestIT`: pin ed25519 -> pinned, authenticated, `docker.available=true`, `28.5.1`; pin ecdsa and pin rsa
-> `hostKeyPinned=false` (the finding above); command exits 1 with "Cannot connect to the Docker daemon" (the stopped-daemon shape),
exits 1 with "permission denied" (the no-group shape) and prints a non-version line -> pinned, authenticated, `docker.available=false`,
no version; wrong pin -> all false; a key the host does not know -> pinned, not authenticated; S3 (authorized_keys replaced) -> the
old key refused, the new key authenticated; nothing listening -> all false; the strict policy on loopback -> all false and no
connection; a command that never answers -> bounded at **25 s** by the watchdog (the request thread is not held), authenticated,
Docker unavailable. The scratch test is not committed (`TargetConnectionTestIT` is its Docker-fixture counterpart).

*OWED (precisely).* **On the Testcontainers PostgreSQL 14:** `TargetManagementIT` (17: `anSshUserOnlyEdit...`, `aRepinKeepsPlacements...`,
`aPinTheHostDoesNotOffer...`, `aKeyAnotherLiveTargetHolds...`, `theEnvelopeHasNoStatement...` (the runtime's `42501` on the envelope),
`anApplyInFlightOnAPlacement...`, `anInstanceWideRequestAndAPendingFleetMember...`, `workOnAnotherTargetOrAnotherTenant...`,
`anEditHoldsTheApplyScopeSoAnAdmissionWaits...`, `anAdmissionInFlightMakesTheEditWait...` (S2′'s race: one winner, neither
straddles), `twoEditsOfTwoTargetsThatShareInstances...`, `twoConcurrentRotationsToTheSameNewKey...`, `aRotationWritesTheNewSecret...`,
`retiringATargetWithNoPlacement...`, `aLivePlacementRefusesTheRetirement...` (pre-check and V007's guard), `aFailureAfterTheRetirementWrite...`
and `aFailureAfterAnEditOrARotationWrite...` (one transaction with the event)); `TargetManagementEndpointIT` (14, the four operations
over HTTP: authorization at the target, the refusals, the rescan audit and budget, MF-R3 for the rotation, the retirement end to end
with the name freed); `TargetServiceIT` (the five `retir...` tests, including two retirements at once -> one audit row);
`AuditLogTargetProbeAuditIT#aRescanAndAConnectionTestAreOneRowEachOfTheirOwnVerbOnTheTarget`; and the regression ITs above that this
change touched (`rotateCredential` now writes an event; `Target`/`TargetDetail`). **Against the real fixture image:**
`TargetConnectionTestIT` (6: the right pin and key, a wrong pin, a key the host refuses, S3's old key refused through the tester,
nothing listening, the strict policy) and the T1.4/W3 `SshdFixtureIT` (still owed). **Launcher:**
`ProductionAuthenticationIT#productionContextCarriesNoDemoNoMigratorAndNoExtraRoute` and its three siblings (the route set, now 57
plus lane D's: it provisions its own database and was not run). **Live gates, procedures and code written, NEVER RUN:** see below. *(2026-10-09 note: L4 and `l3-rotate` have since PASSED and `l3-edit` is not run; see the [combined schedule's live gate results](combined-008-012-013.md#live-gate-results-2026-10-09).)*
**The integrator** recomputes the literal operation counts after lanes D and B (this lane's files say 57: `ENDPOINT-INVENTORY.md`
lines 6 and 16, `postman/README.md`, this paragraph) and regenerates Postman. Not written: an IT of an edit racing an `addPlacement`
(the residual above). *(Update, wave 4 review: a retirement racing an `addPlacement` now has `TargetRetirePlacementRaceIT`, written, owed.)*

*Live gates L3 and L4: procedure and code (OWED).* *(2026-10-09 note: L4 and `l3-rotate` PASSED; `l3-edit` is NOT RUN, pending the owner's permission; see the [combined schedule's live gate results](combined-008-012-013.md#live-gate-results-2026-10-09).)* `backend/local/live-gate-t15.sh` drives ONLY the Solution Manager API against a
running local stack and prints each MANUAL host-side step (daemon stop and start, an OS account, `authorized_keys`) for a human to do:
it never contacts the E2E host, reads no coordinates file, and refuses the root-level L4 steps unless
`SM_LIVE_GATE_L4_PERMITTED=yes` (combined schedule section 8 item 5). `backend/local/test-live-gate-t15.sh` (offline, contacts
nothing) proves its guard rails; its request plumbing (login, CSRF, cookie, bodies, JSON paths, the failure when the host still
accepts the old key) was run against a local mock API, never against a host or a stack. **L3** (`OR-110`): `l3-edit` (S2, **re-designed by `W4C-6`**: the record moves to a second sshd port with the original port fenced
for the stack's address; a control step must see the old record fail; then the new record must pass, every placement must be
GREEN, and the apply must reach `APPLIED` or `STAGED`; needs `SM_LIVE_GATE_L3_FENCE_PERMITTED=yes`, a root-level firewall rule on
the host; the original text of this step was: test; edit the host to another name of the same machine; test; poll `getHealth`; submit
one apply and observe it to a terminal state; edit back; test, which could not tell the new record from the old) and `l3-rotate` (S3: add the new public key on the host; rotate; test authenticated and compare the public fingerprint with
`ssh-keygen -lf`; remove the old key on the host; rotate back to the old key and see the host refuse it; restore the new key).
**L4** (MF-R2, last and alone, needs the owner's permission): `l4-nogroup` (an OS account outside the `docker` group; `PATCH` the SSH
user to it; test -> `200`, authenticated, `docker.available=false`; patch back; remove the account), then `l4-daemon` (stop the daemon;
test -> `200`, pinned, authenticated, `docker.available=false`, never a `5xx`; start it), then `l4-restore-check`. The connection
test cannot tell a stopped daemon from a missing group (both exit 1 and the test reads stdout only), and does not try to.

**Wave 4 review fix round for T1.5 (OR-70: one round per wave; 2026-10-08, Sonnet 5.5; Docker down; worktree `w4c`, branch `x/w4c`; commits cite
"W4 review <ID>").** The Opus review found 8 findings (2 MAJOR, 4 MINOR, 2 NIT). All eight are addressed; two of them (`W4C-1`, `W4C-4`) were
marked `needsOwner` and were resolved by the lane note into a safe interim, with the owner questions recorded below. No migration was added
(none is allocated in W4); no published migration was touched.

- **W4C-1 (MAJOR; interim fix, owner question open).** Confirmed, then measured against a real OpenSSH 9.9p2 on loopback with all three
  host-key types and three user-key types (indicative: not the fixture image, not a remote host): the old default-config connect failed for 6
  of the 9 combinations (every non-Ed25519 host key), the new one passes 9 of 9, for the probe and the pooled health connector. One shared
  helper, `PinnedHostKeyConnect`, now serves `SshjDockerTransport`, `SshjHostConnector` and `SshjTargetSshProbe` (the only sshj connectors
  that verify a pin; `SshHostKeyScanner` verifies nothing). It prefers one key family at a time (Ed25519, ECDSA, RSA) until the pinned
  fingerprint verifies and remembers the family per fingerprint in memory. **Deviation from the lane note's wording, with the reason:** the
  family LEADS sshj's algorithm list instead of restricting it, because a list restricted to ECDSA could not sign with an Ed25519 USER key and
  authentication then failed (measured on the first attempt of the matrix). The rider's wrong attribution (the server) is corrected in
  `registration-api.md` (client order decides); the contract's `hostKeyPinned` description and `backend/local/README.md` say what is true now.
  **Owner question:** the preferred long-term fix is to persist the pinned key's algorithm beside the fingerprint (`OfferedHostKey.algorithm`
  is in hand at registration and at a re-pin), so a pin is verified with one connection and no discovery. That is a new `sm_data` column, so
  a migration number; **note the agent gateway was planned to start at `sm_data` V026**, so the number is to be taken from the allocation.
  Cost of the interim, stated: a pin that verifies for no family (a changed host key, or a man in the middle) costs up to three
  pre-authentication connections per attempt instead of one, and OpenSSH 9.8+ `PerSourcePenalties` penalises each (measured: in my
  harness, many aborted connections in quick succession made the daemon refuse the next one; the harness then ran with that feature off,
  and the 9 of 9 is with it off). The reviewer's scenario (pin the ECDSA fingerprint of a host that also has Ed25519; 201, then every apply, probe and log
  read fails) is the 6-of-9.
- **W4C-2 (MAJOR; fixed, with one deviation).** Reproduced on PostgreSQL 16 with two `psql` sessions replaying the original statements:
  retired target, one live placement. **The reviewer's mechanism was partly different** (V016's `BEFORE INSERT` trigger on `placement` takes the
  target row `FOR UPDATE`, so the foreign key's `FOR KEY SHARE` is not the whole story), **but the race is real** in one direction: V007's
  envelope trigger reads `retired_at` before V016's lock, so a placement that meets a retirement in flight waits, then proceeds without looking
  again. Fix: `addPlacement` and `reserve` hold the record live (`holdLive`: `FOR UPDATE`, re-read of `retired_at`) before the insert, and
  `retireConnection` reads the record `FOR UPDATE` first. **Deviation:** the reviewer's `FOR KEY SHARE` for the re-read was not used: it
  deadlocks two placements on one target against V016's `FOR UPDATE` (V016's own comment says so; reproduced, "deadlock detected"), and
  `FOR UPDATE` costs nothing extra because that trigger already takes it. Measured after the fix: a retirement first leaves the target retired
  and no placement; a placement first makes the retirement refuse (V007's guard); and a plain `UPDATE` retirement is also safe once the
  placement side re-reads, so the retire-side `FOR UPDATE` is defence in depth (said so in the code). Two comments that said an edit does
  not block a placement insert are corrected (V016's trigger makes them wait; no cycle, the edit holds `apply_scope` first).
- **W4C-3 (MINOR; fixed).** The data plane now tells the caller about its scan (`ScanAccounting.beforeScan` / `afterScan`, a new argument of
  `editConnection`; the five-argument form remains and accounts nothing). The gateway charges in `beforeScan` and audits once in a `finally`,
  so (a) a `409 RECORD_CHANGED` or unclassified failure after the scan still leaves its row, (b) a scan the plan did not predict is charged and
  audited, (c) a planned scan the data plane does not make is neither. `planEdit` stays for the `404` and the grammar refusal. An unclassified
  failure inside a scan that never answered is audited `unreachable` (the one fixed code that says the host gave no keys).
- **W4C-4 (MINOR; interim fix, owner question open, with `W3C-2`).** `testTargetConnection` draws on the same per-session budget as the scans
  and runs one at a time per target (a second is `429`, not queued); either refusal is a `target.use` `DENY` row, reason `rate_limited` (a new
  fixed `TestResult` code). The contract gains the `429`. **Owner question:** whether tests should have a bucket of their own; whether the bound
  should also be per principal or global (the bucket is per session, and the single-flight is per target, so many sessions against many targets
  are still not bounded together).
- **W4C-5 (MINOR; fixed).** `live-gate-t15.sh` keeps everything in one private directory (umask 077) that an `EXIT` trap removes on every exit
  (`INT`, `TERM` and `HUP` exit through it); login and request bodies are built with `json.dumps`. The offline test plants unique markers in the
  keys and the password, fails a gate on purpose against a stand-in API and searches every temp directory: red against the previous script (it
  left the login body and both keys), green now.
- **W4C-6 (MINOR; fixed in design; the gate is still OWED).** The S2 gate could not fail. It now makes the old record unusable: a second sshd
  listener port (`L3_ALT_PORT`), the original port fenced for the stack's address by a firewall rule (the host's sshd is not stopped),
  a CONTROL step that must see the old record fail (`hostKeyPinned=false`), then the new record must pass, every placement must be `GREEN`, and
  the apply must reach `APPLIED` or `STAGED`; no terminal state is a failure. **It needs the owner's permission for one root-level step**
  (`SM_LIVE_GATE_L3_FENCE_PERMITTED=yes`, refused offline without it, tested), because the reviewer's alternative (stop the original listener)
  would lock the operator out. Never run against a host.
- **W4C-7 (NIT; fixed).** The scope-row upsert inserts `ORDER BY instance_id` (statement checked on PostgreSQL 16; the deadlock itself was not
  reproduced, and the IT that pre-creates the scope rows was left as it is).
- **W4C-8 (NIT; fixed).** `editTargetConnection` and `rotateTargetCredential` are `[~]` with their live halves named OWED; the `hostKeyPinned`
  description is accurate now that the pin may be any key type.

*Tests that ran (offline, no Docker), counts from this round:* `PinnedHostKeyConnectTest` 9 (new); `InventoryTargetManagementTest` 42 (was 37; 4
new for the scan accounting, 1 for the retire lock order); `InventoryPlacementOperationsTest` 22 (1 new, 1 adjusted); `console-gateway`
`TargetManagementControllerTest` 39 (was 33; 4 new for W4C-3, 2 for W4C-4), `TargetManagementSchemaMirrorTest` 9 (the `429` added to the expected
statuses); `AuditLogTargetProbeAuditTest` 10 (was 9); `test-live-gate-t15.sh` ok. Whole reactor offline, `mvn -o -f backend/pom.xml
-Dmaven.test.failure.ignore=true test`: `cp-api` 49, `mesh-events` 160, `cp-core` 557 (1 error: the known Docker-needing
`SmControlLockoutRecoveryMainTest`), `dp-core` 1390 (1 error: the known Docker-needing `IngestServiceContractTest`; 1 skipped),
`console-gateway` 841 after one expected-status fix (the full run showed that single failure, `TargetManagementSchemaMirrorTest`, because
the contract gained the `429`; re-run: 841 of 841), `launcher` 94, `arch-tests` 102. `validate-openapi.py` passes, `check-postman.py` is OK (57
operations), `contracts/run-checks.sh` passes with check 14 (the frontend's Ajv) skipped, the known environment gap.
**Red then green (reverting only the main source):** the three tests for `W4C-2` (one new and one adjusted in `InventoryPlacementOperationsTest`, one new in
`InventoryTargetManagementTest`) failed on the old service; the four `W4C-3` and the two `W4C-4` gateway tests failed on the old orchestrator (4 and 2 failures); the
`W4C-5` marker test failed against the old script. `PinnedHostKeyConnectTest` is new code (red by compilation only).
*INDICATIVE, local and throwaway, not the postgres:14 floor and not the fixture image:* (1) PostgreSQL 16 built from this repository's own `sm_data`
migrations (V001 to V025, applied by `psql` as `sm_dp_owner`), two `psql` sessions per scenario: original statements (retired, 1 live
placement); fix, retirement first (retired, 0); fix, placement first (retirement refused by V007's guard); plain-`UPDATE` retirement with the
placement-side re-read, both directions (safe); the reviewer's `FOR KEY SHARE` variant (deadlock detected); `FOR UPDATE` variant (both placements
land). (2) A system OpenSSH 9.9p2 `sshd` on `127.0.0.1:2299` with Ed25519, ECDSA and RSA host keys and Ed25519, RSA and ECDSA user keys: the 9
combinations through `SshjTargetSshProbe`, and the three pins through `SshjHostConnector` (alive); a wrong pin gives all false. Both daemons were
stopped afterwards; the scratch test is not committed.

**OWED (this round; Docker was down, so none of these ran):**
1. `TargetRetirePlacementRaceIT` (3 tests: add meets a retirement in flight, retirement meets an add in flight, 25 rounds of both released
   together), on Testcontainers `postgres:14`. Its mutation proofs: remove `holdTargetLive` from `addPlacement` (test 1 and the stress test must
   go red); `findForRetirement` back to `find` is NOT expected to turn any test red (defence in depth, said so); run on the floor, not PG16.
2. `TargetConnectionTestIT#aPinToAnyOfTheHostsThreeKeyTypesVerifiesAndTheTestGoesOnToAuthenticate` and
   `SshdFixtureIT#theHealthConnectorConnectsWithAPinToAnyOfTheHostsThreeKeyTypes` on the approved `OR-124` image
   (`quay.io/panubo/sshd:1.10.0@sha256:6d54f749...`). Their mutation proof: replace `PinnedHostKeyConnect.connect` in the probe and the
   connector by the old default-config connect and see the ecdsa and rsa cases go red. The per-operation transport
   (`SshjDockerTransport.withClient`) uses the same helper but has no fixture IT of its own (the image has no Docker to run its commands)
   and was not run against the local daemon either; it is covered by the shared helper's unit tests and by compilation.
3. The gateway-level and launcher-level ITs that mention the connection test (`TargetManagementEndpointIT`) were not re-run this round
   (they need PostgreSQL); the `429` on the test and the changed `editConnection` signature are covered by unit tests only.
4. Gate L3 (`l3-edit` as re-designed, `l3-rotate`) and L4 on the E2E host, with the owner's permission variables; none has ever run. *(2026-10-09 note: `l3-rotate` and L4 PASSED; `l3-edit` is not run, pending the owner's permission; see the [combined schedule's live gate results](combined-008-012-013.md#live-gate-results-2026-10-09).)*
5. The earlier OWED list of T1.5 stands (PostgreSQL 14 runs of the T1.5 ITs, the mutation proofs on the container, the Opus wave review, a full
   reactor `verify`).

## W2 — Scope tree and entitlements

### T2.1 — `sm_control` migration **`V018__registration_api.sql`** (combined schedule; after `OR-120`'s V015 and plan 012's V016/V017)

- [x] ~~`name` on `organization`, `organization_unit`, `environment`~~ — **the columns are `OR-120`'s** (`V015`:
      back-filled with the id text, `NOT NULL`, 1–100 characters with a shape CHECK, and a `BEFORE INSERT` trigger that
      names an unnamed row with its id). T2.1 adds only the **per-parent unique constraints** (`(organization_id, name)`
      for units, `(organization_id, organization_unit_id, name)` for environments) after re-measuring that the seeded
      data has no duplicate. D-REG's 1–128 length yields to `OR-120`'s published 1–100.
- [x] Grants (D-REG §10.1). The unconditional set is always granted. **W3's grants (`INSERT`/`UPDATE (status,
      deactivated_at)` on `principal`, `INSERT, DELETE` on `role_binding`) are granted only if OQ-6 admits W3**, so
      no `DELETE` is granted without a caller (R20).
- [x] ~~`permission_catalog` + Administrator `role_permission` rows~~ — **moved to plan 012 T1** (`V016`, all five
      verbs of §1.9; OQ-7 (a), so no Operator row for this plan's three).
- [x] `CREATE OR REPLACE record_change_audit()` once. The body is **plan 012's body (with `INSTANCE_UNDECLARED`)
      plus** `HIERARCHY_NODE_CREATED` and `HIERARCHY_NODE_RENAMED`, and plus W3's five operation names only if OQ-6
      admits W3. **This is a merge of two bodies, not a renumbering.** The row keys exist already (V013). Narrow the
      `ENTITLEMENT_SET` `NULL`-actor exemption to `client_address = 'operator-cli'` (R16). Extend the readiness
      probe's `prosrc` check.
- [x] Under OQ-11 (b): replace `target_name_once` with a partial unique index `WHERE retired_at IS NULL`.
- Tests:
  - the checksum anchor;
  - the trigger refuses each new **and each of plan 012's** operations' writes without context (`OR-25`);
  - a `NULL`-actor `ENTITLEMENT_SET` from any client address other than `operator-cli` is refused, and
    `EntitlementMain` still succeeds;
  - the readiness check fails on the previous function body;
  - `BuiltInRoleSeedIT` still asserts **43/24/8** (set by plan 012 T1), and Administrator holds every catalog verb (a
    query, not a list);
  - the per-parent unique constraints refuse a duplicate sibling name and accept the same name under two parents.

**Results (T2.1, 2026-10-07/08, wave 2 lane D, `x/w2d`; Docker down).** `sm_control` **`V018__registration_api.sql`** written
(sha pinned in `CHECKSUMS.sha256`; the checksum anchor is **not** added: it is a follow-up commit once the commit reaches
origin). **What it contains:** the hierarchy grants (`INSERT` on `environment`; `INSERT (organization_id,
organization_unit_id, name)` on `organization_unit`; `UPDATE (name)` on the three node tables); W3's grants, because OQ-6
(a) is ruled (`INSERT` and `UPDATE (status, deactivated_at)` on `principal`, `INSERT, DELETE` on `role_binding`, the only
`DELETE` the runtime role holds); `record_change_audit()` replaced once (**V017's body verbatim plus the seven operation
names** `HIERARCHY_NODE_CREATED`, `HIERARCHY_NODE_RENAMED`, `PRINCIPAL_CREATED`, `PRINCIPAL_DEACTIVATED`,
`PRINCIPAL_CREDENTIAL_RESET`, `ROLE_BINDING_GRANTED`, `ROLE_BINDING_REVOKED`, in the allow-list and the `audit_operation`
CHECK, with the no-actor `ENTITLEMENT_SET` exemption narrowed to `client_address = 'operator-cli'`); and `target_name_once`
replaced by a partial unique index (same name, so a `23505` reports the same string). `AuditOperations` carries the seven
constants; `ControlPlanePersistenceConfiguration` has a **sixth readiness stage** (function body markers incl.
`operator-cli`, the runtime privileges incl. *no* `INSERT` on `parent_unit_id`, the partial index);
`SmControlPostgres.LATEST_VERSION` is `018`.

**Deviations, each for the reviewer:**
1. **The per-parent unique names are not in `V018`.** `V015` (`OR-120`/`OR-123`) already carries all three
   case-insensitive indexes (`organization_name_ci_uq`, `organization_unit_name_ci_uq`, `environment_name_ci_uq`), and the
   plan's "after re-measuring that the seeded data has no duplicate" is moot (the back-fill is the unique ids). A second
   constraint would be redundant and would give a `23505` two names. The T2.1 test line is covered by
   `RegistrationApiSchemaIT.theRuntimeRoleCannotCreateADuplicateSiblingNameIgnoringCaseButMayReuseANameUnderAnotherParent`,
   which proves it *through the runtime role*.
2. **`INSERT` on `organization_unit` is column-scoped** (no `parent_unit_id`), tighter than the plan's "INSERT on
   `organization_unit`": a sub-organization is top-level only (`SM-D32`) by privilege, not just by code. A later nested
   depth would need a migration, as any other grant change does.
3. **The 1-128 name length of D-REG yields to `OR-120`'s published 1-100** (as the plan already said); the service
   validates 1-100 code points.
4. **`target_name_once` is no longer a table constraint but an index of the same name.** `JdbcTargetRepository.findByName`
   now returns the live row first, and `TargetService.register` treats a retired match as free (a retired name is reusable,
   OQ-11 (b)); without that, the migration would have left `findByName` ambiguous. `TargetServiceIT` has the test.

**Pre-existing defects found on the base (`c9e2bfb9`), not caused by this lane, in files this lane owns or works in:**
`ControlPlaneConfigurationIT.aDatabaseThatPredatesV016...` ran against a V014 schema, which readiness refuses for **V015**
first (fixed: it now uses a V015 schema); `HierarchyDirectoryIT.listsAreOrderedByNameThenIdNotById` indexed `envIds[3]` of a
3-element array (fixed: 4 elements); `HierarchyNamesSchemaIT.theRuntimeRoleReadsNamesAndStillWritesNone` is **expected** to
change under `V018` (now `...WritesThemOnlyThroughAnAuditedRename`). **Not touched (not this lane's file):**
`DashboardSchemaIT.theOperatorGainedExactlyTheTwoMetricsVerbsAndNothingElse` asserts 21 Operator verbs at the head schema,
which has held 24 since `V016` (plan 012 T1): it fails on the base too.

**Evidence.** *Offline, run:* `mvn -o test-compile` of the whole reactor clean; arch-tests `com.solace.sm.arch.*` 82/82
(including the `I7` and JDBC-confinement rules and their planted-violation proofs); `MigrationChecksumsTest` 2/2;
`./contracts/run-checks.sh --strict` passes every check except 14 (`ajv/dist/2020`, the known environment gap), including
12 (frozen migrations), 11 and 15 (Postman: 46 operations, unchanged: this task edits no contract).
*Integration, run on a throwaway **native PostgreSQL 16** cluster per cache key (a scratch harness outside the repository
that replaces `new PostgreSQLContainer<>("postgres:14")` in `SmControlPostgres`; not Docker, not committed):*
`RegistrationApiSchemaIT` 18/18, `ControlPlaneConfigurationIT` 16/17 (the one error is `anUnmigratedPhaseZeroOnly...`,
which starts its own `PostgreSQLContainer`), and the other `cp-core` ITs that touch the schema (`GovernanceSchemaIT`,
`EntitlementMainIT`, `HierarchyDirectoryIT`, `HierarchyNamesSchemaIT`, `InstanceRetirement*IT`, `GovernanceServiceIT`,
`AuthorizationIT`, `GrantReachAuthorizationIT`, `RetiredInstanceAuthorizationIT`, `BuiltInRoleSeedIT` (43/24/8), `PrincipalIT`,
`IdentityServiceIT`, `TargetServiceIT`) all green. **Red first:** `RegistrationApiSchemaIT` against an *empty* `V018` was
11 red / 7 green on that harness (the 7 are the "still refused" guards: nothing new is granted that was not meant to be);
against no `V018` at all, 18/18 errored. **Mutation proofs (each run red, then restored):** dropping the narrowing
(`aNoActorEntitlementSet...`), granting `INSERT` on the whole `organization_unit` (`aSubOrganizationIsTopLevelOnly...`),
restoring the full constraint (`aRetiredTargetFreesItsName...`), dropping `HIERARCHY_NODE_CREATED` from the allow-list (3 red).
**What the Postgres 16 run is not:** the ITs are written for PostgreSQL 14 under Testcontainers. **Owed (Docker down
2026-10-07 night):** every IT of this task on the real `postgres:14` container, in particular `RegistrationApiSchemaIT` (18),
the two new `ControlPlaneConfigurationIT` tests (`aDatabaseThatPredatesV018...`, `aV018DatabaseThatLostItsAuditBody...`),
`TargetServiceIT.aRetiredTargetFreesItsName...`, `HierarchyNamesSchemaIT.theRuntimeRoleReadsNamesAndWritesThemOnlyThroughAnAuditedRename`,
`HierarchyDirectoryIT.listsAreOrderedByNameThenIdNotById` and `ControlPlaneConfigurationIT.aDatabaseThatPredatesV016...`
(the two fixes); and `ControlPlaneConfigurationIT.anUnmigratedPhaseZeroOnlyDatabaseRefusesToStartWithAnActionableMessage`,
`SmControlMigrationMainTest.aSuccessfulMigrationAgainstAPhaseZeroOnlyContainer...` (pins "migrated <count of CHECKSUMS>"),
which only a real container can run.

**W2 review fix round (W2D-3), added 2026-10-08 (Docker down; nothing below was run against a database).** New owed ITs,
neither executed: `RegistrationApiSchemaIT#everyWriteV018NewlyGrantsIsRefusedOr025WithoutAuditContextAndChangesNothing` (the
runtime role, no audit context, refused with `OR025` and the row unchanged, for each of: `environment` INSERT; `name`
UPDATE on `organization`, `organization_unit` and `environment`; `principal` INSERT and the `status`/`deactivated_at` UPDATE;
`role_binding` INSERT and DELETE) and `EntitlementMainAtHeadIT#theCommandStillWritesItsNoActorAuditedRowAfterTheExemptionIsNarrowedToOperatorCli`
(`EntitlementMain` against `LATEST_VERSION`, exit 0 and one audit row with client address `operator-cli`; `EntitlementMainIT`
stays pinned at V013). **Run offline:** `OperatorClientAddressLiteralTest` 1/1 (pure: V018's `v_client <> 'operator-cli'`
text, `EntitlementMain.OPERATOR_CLIENT_ADDRESS` and `SmControlLockoutRecoveryMain.OPERATOR_CLIENT_ADDRESS` agree); red first by
changing the constant to `operator-command` (1 red), then restored.

### T2.2 — `cp-core` services

- [x] `HierarchyAdministration` (in `cp-api`, implemented in `cp-core`): create sub-organization and environment,
      and rename organization, sub-organization and environment. Authorized by `hierarchy.edit` at the parent
      (create) or the node (rename). Creates replay on `(parent, name)`. **No `scope` row is inserted.** Scope rows
      are minted by `ScopeStore.canonicalize` when first used, as the target and declare paths do today (R14).
      Name uniqueness is the database's (`OR-123`): map a `23505` on `organization_name_ci_uq`,
      `organization_unit_name_ci_uq` or `environment_name_ci_uq` to the named conflict, and do not pre-check in Java
      with a different case rule (the comparison is PostgreSQL `lower()` under the database's locale).
- [x] ~~Retire `ScopeNode.placeholderNamed`~~ — **done by `OR-120`** (`p/names`), which reads the `name` column in the
      hierarchy reads and `Identity.organizations`. T2.2 adds only the writes (create, rename) and re-reads
      `ScopeVisibleHierarchyDirectory`/`HierarchyDirectory` as `OR-120` left them.
- [x] `EntitlementAdministration`: list (`solution.view`, organization) with `declaredInstances` from **plan 012
      T4's `countInstances`** (retired excluded). Set (`entitlement.set`, install-scope resource), refusing a value
      below `declaredInstances` with **`409 ENTITLEMENT_REFUSED`** under OQ-4 (a), or accepting it under (a′).
      Audit `ENTITLEMENT_SET` with the actor.
- [x] `EntitlementMain` unchanged.
- Tests:
  - an organization-bound Administrator cannot set an entitlement (`403`), and an install-bound one can;
  - a unit-bound principal with `hierarchy.edit` creates environments in its unit and gets `404` for a sibling unit;
  - a newly created environment can be used as a target's `ownershipScope` without any explicit scope insert;
  - an undeclared (retired) instance does not count.

**Results (T2.2, 2026-10-08, wave 2 lane D, `x/w2d`; Docker down).** **cp-api:** `HierarchyAdministration`
(`createSubOrganization`, `createEnvironment`, `renameOrganization`, `renameSubOrganization`, `renameEnvironment`;
`Created(node, created)`), `HierarchyNameConflictException` (the named conflict, kind-carrying, **no request value in the
message and no cause**), `EntitlementAdministration` (`list`, `set`; `Entitlement(solution, maxInstances,
declaredInstances, setAt)`), and `EntitlementRefusedException` gains a `Reason` (`NOT_ENTITLED`, the unchanged default, and
`BELOW_DECLARED`). **cp-core:** `HierarchyAdministrationService` over a new `HierarchyWriteRepository` port with
`JdbcHierarchyWriteRepository` (own `HierarchyPersistenceConfiguration` and `HierarchyConfiguration`, one `@Import` in
`ControlPlaneConfiguration`; `HierarchyTreeRepository` and `ScopeVisibleHierarchyDirectory` are unchanged), and
`EntitlementAdministrationService` (`EntitlementRepository` gains `list`/`find`; bean in `CatalogConfiguration`).
`EntitlementMain` and `EntitlementService` are untouched.

**OR-123, as required.** Name uniqueness is the database's: the service writes, never pre-checks, and never compares two
names in Java. A `23505` on `organization_name_ci_uq` / `organization_unit_name_ci_uq` / `environment_name_ci_uq` (read
from the server's constraint name in `JdbcHierarchyWriteRepository`, after the transaction rolled back) is read one way: if a
node of **exactly** that name (SQL `=`, not `lower()`) exists under the parent it is the identical replay of a create (`200`,
`created = false`, no second audit row); otherwise (a different spelling of a sibling's name, or any rename onto one) it is
`HierarchyNameConflictException`. A test asserts the service performs no `*ByName` lookup before its write
(`HierarchyAdministrationServiceTest.theServiceNeverLooksUpANameBeforeItWrites...`).

**Amended by `OR-125` (3) (wave 5, lane C):** `renameOrganization` above is no longer "authorized at the node": it requires
`hierarchy.edit` at the **install anchor** first and then at the named organization (the same two requires as `entitlement.set`), so an
organization-bound Administrator is refused before any read and the install-wide `organization_name_ci_uq` conflict is reachable only by
install principals. `createSubOrganization`, `createEnvironment`, `renameSubOrganization` and `renameEnvironment` are unchanged. See the
"OR-125 (3)" note under T2.3 for the proof, and for the findings (W2D-2, W4D-1) it closes.

**Decisions made inside the rulings, for the reviewer:** (1) a **case-only difference on create is a conflict, not a
replay** (D-REG's "same name" is read as byte-equal; the contract draft's H1 text "`409` is not used" must be reworded at
T2.3, because `OR-123` makes `409 CONFLICT` reachable on create); (2) a rename to a node's **own** name writes nothing and
leaves no audit row, and a rename that changes only the case is allowed (the node does not clash with itself); (3)
`entitlement.set` is evaluated against the **built-in Administrator role definition**, the install-wide resource
(`ResourceKind.ROLE_DEFINITION` resolves to `ScopePath.INSTALL`), because `ResourceKind` has no install constant and adding
one would change `cp-api` authz beyond this task; (4) setting the limit the row **already holds** writes nothing (a `PUT`
replay changes nothing, S5), and the row is locked `FOR UPDATE` and **counted after the lock**, so a declare in flight
cannot be missed (the same lock `GovernanceService.register` takes, `OR-40`); (5) `maxInstances` is validated **1 to
1 000 000**: the contract draft's `minimum: 0` disagrees with the table's `CHECK (max_instances > 0)` and with
`EntitlementService`, so **T2.3 must change the draft to `minimum: 1`**.

**Evidence.** *Offline, run:* `HierarchyAdministrationServiceTest` 14/14 and `EntitlementAdministrationServiceTest` 9/9
(fake collaborators: authorization first and at the right node, nothing read or written on a refusal, the shape of a name,
the replay/conflict reading); whole-reactor `test-compile` clean; arch-tests 82/82. *Integration, native PostgreSQL 16
harness as above:* `HierarchyAdministrationServiceIT` 14/14 (unit-bound principal creates in its unit and finds a sibling
absent; a created environment is a target's `ownershipScope` with no explicit scope insert, the scope row minted by first
use; replay; case-different conflict; same name under two parents; two identical creates released by a barrier leave one
row; renames, audit rows, cross-organization 404s for every operation) and `EntitlementAdministrationServiceIT` 12/12
(organization-bound Administrator 403, install-bound sets, Operator and Viewer refused even at install, refusal below use,
a retired instance does not count, the idempotent no-op, the lock-before-count race, the list). **Tests were written before
the service code only for `RegistrationApiSchemaIT` (T2.1); for T2.2 the services and their tests were written together, so
the proof is by mutation, each run red then restored:** unit: authorization removed (2 red), control-character check removed
(2), replay replaced by a conflict (1), `<` to `<=` on the declared count (1), the lock removed (2); integration:
`lower()` in the by-name lookup (`aNameThatDiffersOnlyByCase...`), authorization moved to the organization
(`anOrganizationBoundAdministratorCannotRaiseItsOwnQuota...`), the retired filter in `TargetService`
(`aRetiredTargetFreesItsName...`), the lock removed (`aSetTakesTheEntitlementRowLockBeforeItCounts...`; the first version of
that test did **not** catch this mutation and was rewritten). **Owed (Docker down 2026-10-07 night):** `HierarchyAdministrationServiceIT`
(14), `EntitlementAdministrationServiceIT` (12) and the T2.1 list above on the real `postgres:14` container; a full reactor
`verify` (launcher and gateway ITs); the Opus wave review.

**W2 review fix round, 2026-10-08 (Docker down; offline compile and pure unit tests only).**
- **W2D-1 (MAJOR, fixed).** `EntitlementAdministrationService.set` now also calls `authorization.require(entitlement.set,
  ORGANIZATION(organizationId))` after the install-anchor require (the anchor resolves to `ScopePath.INSTALL`, whose
  `organization()` is null, so it never compared the named organization with the caller's own). A foreign or nonexistent
  organization is now `ResourceNotFoundException` (404) before any lock, count, write or audit row, and a nonexistent one no
  longer reaches the entitlement foreign key (23503, a 500). An organization-bound Administrator is still refused (403) by the
  install anchor. **Run (unit, red first with the fix stashed: 2 red):** `EntitlementAdministrationServiceTest` 10/10
  (`aSetIsAuthorizedAtInstallScopeAndThenAtTheNamedOrganizationBeforeAnythingIsReadOrLocked`,
  `aSetOnAnOrganizationTheOrganizationRequireRefusesAsAbsentTouchesNothing`; two older tests updated for the second require).
- **W2D-2 (MINOR, needsOwner, behaviour unchanged). RESOLVED by `OR-125` (3) (owner, 2026-10-08, option (b): an organization is renamed at install scope only); see the "OR-125 (3)" Results note under T2.3. The text below is the finding as raised.** `renameOrganization` is authorized with `hierarchy.edit` at the caller's
  own organization, which an organization-bound Administrator holds, and writes against `V015`'s install-wide
  `organization_name_ci_uq` (`OR-123`). The `409 HierarchyNameConflictException` therefore **reveals that another tenant's
  organization has that name** (case-insensitively), a cross-tenant existence oracle that `OR-123`, the D-REG text and the
  Results above did not record; `HierarchyAdministrationServiceIT#organizationNamesAreUniqueAcrossTheInstallIgnoringCase`
  pins it as intended. Harmless in a one-organization install. **Pending the owner's choice among (a) accept and record it in
  `DECISIONS.md` as a qualification of `OR-123`, (b) authorize an organization rename at install scope only (like
  `entitlement.set`), (c) drop install-wide organization-name uniqueness.** **T2.3 must not document the organization-rename
  `409` as sibling-only** (see the note under T2.3).
- **W2D-3 (MINOR, fixed in tests).** See the T2.1 fix-round paragraph: two new owed ITs and one pure test run.
- **W2D-4 (NIT, fixed).** `EntitlementAdministrationServiceIT#aPrincipalBoundOnlyBelowTheOrganizationFindsTheInstallResourceAbsent`
  asserted 403 under a "found absent" name; renamed `...IsRefusedTheInstallResource` (403 is the real behaviour: the install
  path is visible from an environment-bound grant).
- **Owed, new (not run: Docker down), `EntitlementAdministrationServiceIT`:**
  `#anInstallBoundAdministratorSettingAnotherOrganizationsLimitFindsItAbsentAndNothingIsWritten` (404; no entitlement row for
  the other organization, no audit row, the caller's own limit untouched) and
  `#settingTheLimitOfANonexistentOrganizationIsAbsentNotAForeignKeyViolation` (404, not 23503). The IT class is therefore
  **14** (12 + 2) once run, and the earlier "12/12" is the pre-fix count on the PG16 harness.

### T2.3 — Gateway routes and contract tests

> **RESOLVED by `OR-125` (3) (W2D-2 / W4D-1; see the "OR-125 (3)" Results note below): `renameOrganization` is evaluated at install scope only, so its install-wide `409` is no cross-tenant oracle, and `console.yaml` documents it so. The note below is the instruction that applied until the ruling.**
>
> **Note for T2.3 (W2 review W2D-2, needsOwner).** The `409 CONFLICT` on an organization rename reveals another tenant's
> organization name (install-wide uniqueness, `OR-123`). Until the owner chooses (a) accept and record, (b) rename at install
> scope only, or (c) drop install-wide uniqueness, do not document that `409` as a sibling-only conflict, and do not change the
> service. ~~Sub-organization and environment `409`s are per parent and not affected.~~ **Corrected (W4 review W4D-1):** they are checked against every sibling, including siblings the caller cannot see, so they disclose names too (see the T2.3 fix-round paragraph, W4D-1; recorded beside W2D-2, behaviour unchanged).

- [x] H1–H5, E1–E2 controllers; path organization id compared with the session (`404` otherwise).
- [x] The contract-conformance tests that already walk `console.yaml` cover the new operations (no hand list).
- Tests: S1's hierarchy and entitlement steps through HTTP; names appear in `listSubOrganizations` and (after
  `p/or116`) in `Identity`.

**Results (T2.3, 2026-10-08, wave 4 lane D, `x/w4d`, Sonnet 5.5; Docker down).** **Contract (HF3, after lane C, before lane
B):** `console.yaml` gains the **seven** operations of the draft (`createSubOrganization`, `createEnvironment`,
`renameOrganization`, `renameSubOrganization`, `renameEnvironment`, `listEntitlements`, `setEntitlement`: 53 to 60), the
`entitlements` tag, `NodeNameRequest`, `SetEntitlementRequest`, `Entitlement` and `EntitlementList`, and two sentences in the
`ErrorCode` description (`CONFLICT` and `ENTITLEMENT_REFUSED`); no `ErrorCode` constant is added (409 and 422 exist), so lane C's
four are untouched. Promotion changes the draft in **four** places, each for the reviewer: (1) the creates' "`409` is not
used" is gone: a case-only sibling difference is **`409 CONFLICT`** and only the exact name is the `200` replay (`OR-123`,
the T2.2 decision (1)); (2) `maxInstances` is `minimum: 1`, as T2.2 required (the table's `CHECK (max_instances > 0)`);
(3) the `{solution}` path parameter carries the catalog's grammar `^[a-z][a-z0-9-]{0,63}$` instead of a length bound, so the
gateway can answer `400` instead of letting the service throw `IllegalArgumentException` (a `500`); (4) `Entitlement.setAt`
is required (the service always has it). `Entitlement` carries no principal and no organization.

**W2D-2 recorded, not decided. (Superseded: closed by `OR-125` (3), see the "OR-125 (3)" note after the W4 fix round; the paragraph is the T2.3 record as it stood.)** `renameOrganization`'s `409` is documented as **install-wide, not a sibling clash**, says
that it discloses another organization's name to an organization-bound Administrator, names the open owner question and the
three options (a), (b), (c) without choosing, and states that the text will change with the ruling. The response `detail` for
an organization says "an organization of this installation" (never "sibling"). `HierarchyAdministrationEndpointIT#
anOrganizationRenameOntoAnotherOrganizationsNameIsA409TheOpenOracle` **pins the current behaviour and says it is not an
endorsement**; the service is unchanged. The sub-organization and environment `409`s are per parent and documented as sibling
clashes. The same-name-under-two-parents case is in the contract text and in the IT.

**Gateway (`console-gateway`, no new migration).** `HierarchyAdministrationController` (the five writes; `201` new, `200` the
identical replay, `PATCH` renames answer `200`), `EntitlementController` (`GET /cp/entitlements`, `PUT
/cp/entitlements/{solution}`), the resolvers `OrganizationNodeResolver`, `SubOrganizationNodeResolver` and
`EnvironmentNodeResolver` (the path node under the **session's** tenant, and `404` before the authorizer is asked when the
path's `organizationId` is not the session's: `HierarchyAdministration` builds its tenant from the organization it is handed, so
the gateway must hand it only the session's, `I5`), `InstallResourceResolver` (the install anchor `setEntitlement` is evaluated
at; `EntitlementAdministration.INSTALL_ANCHOR_ROLE` now holds the V003 Administrator id once, for the service and the resolver),
`HierarchyErrorAdvice` (`HierarchyNameConflictException` to `409 CONFLICT`, fixed detail per kind, never the name; a separate
advice so the shared `GatewayErrorAdvice` is not touched), `NodeNameRequest` and `SetEntitlementRequest` (**`JsonNode`
components**, the `SubmitApplyRequest` precedent: the first test run found that an `Integer` component let Jackson coerce `1.5`
to a limit of `1` and a `String` component let `7` become the name `"7"`; both are now `400`), `EntitlementAdministration`
gains `SOLUTION_NAME_PATTERN`. Existing tests whose pinned sets name these routes were updated and nothing else of theirs:
`SchemaMirrorLockTest` (the hierarchy tag now holds eight operations), `DeclaredBadRequestsTest` (one explicit 400 site in
`EntitlementController`), `ProductionAuthenticationIT` (the served route set). `ConsoleOperations` registers the seven (appended
at the end of the registry, away from lane C's block). `ENDPOINT-INVENTORY.md` has seven rows in "Served now"; the Postman
collection is regenerated (creates use fixed names, so a re-run is the `200` replay; renames send back the name the list
before them captured, so running the folder never renames a real node).

**Evidence, run.** *Unit, offline:* `HierarchyAdministrationControllerTest` 18, `EntitlementControllerTest` 14,
`HierarchyNodeResolversTest` 6, `HierarchyAdministrationSchemaMirrorTest` 11, `cp-core` `EntitlementSolutionNameParityTest` 1
(the API's name grammar equals the operator command's), plus `SchemaMirrorLockTest` 52 and `DeclaredBadRequestsTest` 3 after
their update; cp-api 49 incl. `ConsoleOperationsMirrorTest`; arch-tests 102/102; `./contracts/run-checks.sh --strict`: every
check passes except check 14, the known `ajv/dist/2020` environment gap (the same failure as the base, measured before any
edit); `check-postman.py` OK (60 operations, 59 served). The reactor `test-compile` is clean. The whole `console-gateway`
unit run is green at **842/842** (after updating the two pinned tests named above); the whole `cp-core` (548) and `dp-core`
(1323) unit runs executed with errors only in the two Testcontainers tests that need Docker (`SmControlLockoutRecoveryMainTest`,
`IngestServiceContractTest`), which need a container and are not this task. **Red first:** the tests were
written before the controllers, but the controllers were then written before the first run, so the first run is the red/green
evidence: it was red in two places (the two coercions above), which is what drove the `JsonNode` components. *Mutation proofs,
unit (each applied, the four unit classes run red, the source restored):* the path-organization check removed (2 red); the
install resolver replaced by the session-organization resolver (2); the organization `409` detail made to say "sibling" (1); a
replay answered `201` (1); the solution grammar removed (1); the control-character check removed (2). *Integration,
**indicative, PG16** (a Homebrew PostgreSQL 16 under the lane's scratch directory, behind a test-only shim copied in for the run
and removed after; **the `postgres:14` Testcontainers run is owed**):* `HierarchyAdministrationEndpointIT` 9/9 (the real
launcher over real HTTP: an Administrator creates and renames the tree and the names show in the reads and in the `Identity`;
a replay and a rename to the node's own name leave no second audit row; a case-only difference is `409`, never a replay; the
same name under two sub-organizations is fine; a session cannot name another organization on any of the five writes; a
unit-bound principal creates in its unit and finds a sibling unit absent, and `403` at the organization; a Viewer and an
Operator are refused; an organization-bound Administrator cannot set its own limit while an install-bound one can, and the set
is audited with the actor and a replay writes no second row; every write needs a session and a CSRF token; bad names are
`400` and write nothing); `ProductionAuthenticationIT` 4/4 (the served route set now holds the seven) and `HierarchyEndpointsIT`
13/13 on the same harness; `cp-core` `HierarchyAdministrationServiceIT` 14/14 and `EntitlementAdministrationServiceIT` 14/14
(the INSTALL_ANCHOR_ROLE refactor). One **IT mutation** on that
harness: the sub-organization resolver made to authorize at the organization turned
`aUnitBoundAdministratorCreatesInItsOwnUnitAndFindsASiblingUnitAbsent` red (`403` for `201`).

**Not done, and why.** `HierarchyAdministrationEndpointIT` does not declare instances, so "a limit below use is `409`" is proved
by `EntitlementControllerTest` (the port's refusal becomes the contract's `409`) and `EntitlementAdministrationServiceIT` (the
real count), not end to end over HTTP. The Postman "Setup" folder and the stack script are T4.1 (wave 6). The counts in the
READMEs and the header of the inventory are the wave integrator's (they depend on the other lanes' operations).

**Owed (Docker down 2026-10-07 night; nothing here is a claim of a run):** `HierarchyAdministrationEndpointIT` (9),
`ProductionAuthenticationIT` and `HierarchyEndpointsIT` (re-run), `HierarchyAdministrationServiceIT` (14) and
`EntitlementAdministrationServiceIT` (14), all on the real `postgres:14` container; the unit-level mutation proofs are
complete, the IT-level mutation proof above was run on PG16 only; the Opus wave review and the full reactor `verify`.

**W4 review fix round, T2.3, 2026-10-08 (lane D, `x/w4d`; Docker down; offline compile and pure unit tests only).**
- **W4D-1 (MINOR, needsOwner, behaviour unchanged, recorded beside W2D-2). RESOLVED by `OR-125` (3): option (a), accepted and recorded; `console.yaml`'s two rename `409` descriptions now say so. The text below is the finding as raised.** The sub-organization and environment rename `409`s
  are checked against every sibling (`organization_unit_name_ci_uq`, and the environment's per-sub-organization equivalent),
  including siblings the caller cannot see, because `renameSubOrganization` and `renameEnvironment` authorize `hierarchy.edit`
  **at the node itself**. A unit-bound Administrator (the IT's `UNIT_ADMIN` pattern) can therefore probe the names of sibling
  units it cannot list (`listSubOrganizations` hides them, `createEnvironment` on one is `404`), one audit-free request per
  guess (a `409` rolls back); an environment-bound Administrator can probe sibling environments the same way. The earlier
  T2.3 note said these were "not affected"; that held only for a principal bound at or above the parent and is corrected above.
  **Options for the owner (same shape as W2D-2):** (a) accept and record it as a qualification of `OR-123`; (b) authorize a rename
  at the parent; (c) answer `404` when the clashing sibling is not visible to the caller. Rule it together with W2D-2. Changed
  in this round: the two `409` descriptions in `console.yaml` now say the clash covers invisible siblings and name the open
  question; the plan note is corrected. No service, status code or `HierarchyErrorAdvice` change; the response detail text
  ("a sibling ... already has this name") stays.
- **W4D-2 (MINOR, fixed).** `generate-postman.py` gains `CLEAR_FIRST = {"listEntitlements": ["solution", "entitlementMax"]}`,
  emitted at the top of that operation's test script, so an empty list (or a non-2xx) leaves the two variables empty instead of
  the previous run's values; `setEntitlement` then sends an empty path and an invalid body (404/400, no write). Collection
  regenerated; `check-postman.py` OK (60 operations, 59 served, 1 wired-not-counted-served). Not run against a stack (none is up).
- **W4D-3 (NIT, fixed in the IT; not run).** `HierarchyAdministrationEndpointIT` now looks up `INSTALL_ADMIN`'s principal id and
  counts `ENTITLEMENT_SET` rows on `table_name = 'entitlement'` with `row_key` organization and solution `cache` and
  `actor_principal_id` = that id (the same query before and after the PUT replay). Compiled only.
- **W4D-4 (NIT, fixed).** `EnvironmentNodeResolver` now reads `subOrganizationId` through `EnvironmentResolver.pathId`, so a
  malformed sub-organization id is `404` before the authorizer, like every other position (it was `400` after authorization).
  New unit test `HierarchyAdministrationControllerTest#aNonUuidIdInAnyPositionOfTheRenameEnvironmentPathIs404BeforeTheAuthorizer`
  (all three id positions; asserts neither the authorizer nor the port is reached). **Red first** with the resolver change
  stashed: `expected: <404> but was: <400>`; green with it.
- **Run (offline, this round):** `mvn -o -q -f backend/pom.xml test-compile` clean (all modules, including the launcher IT
  sources); `mvn -o ... -pl console-gateway -am test` on the six lane classes: 105/105 (`HierarchyAdministrationControllerTest`
  19, `EntitlementControllerTest` 14, `HierarchyNodeResolversTest` 6, `HierarchyAdministrationSchemaMirrorTest` 11,
  `SchemaMirrorLockTest` 52, `DeclaredBadRequestsTest` 3). `./contracts/run-checks.sh --strict`: every check passes except check
  14 (frontend Ajv `dist/2020` not installed in the worktree, the known environment gap).
- **OWED after this round (Docker down; none of it ran):** on `postgres:14` Testcontainers: `HierarchyAdministrationEndpointIT`
  (9, with the W4D-3 assertion change), `ProductionAuthenticationIT` (4), `HierarchyEndpointsIT` (13), `HierarchyAdministrationServiceIT`
  (14) and `EntitlementAdministrationServiceIT` (14); an IT-level mutation for W4D-3 (service records a non-INSTALL_ADMIN actor, or
  none, expects `HierarchyAdministrationEndpointIT` red; not run); a stack run of the regenerated Postman collection with an
  empty entitlement list (W4D-2); the Opus wave re-review, check 14 where the frontend's `node_modules` exist, and the full
  reactor `verify`. ~~Open for the owner: W4D-1 with W2D-2.~~ Ruled by `OR-125` (3); implemented below.

**OR-125 (3) (wave 5, lane C, `x/w5c`, Sonnet 5.5; Docker available) - an organization is renamed at INSTALL scope only; W2D-2 and
W4D-1 closed.** *Ruling:* the owner chose, for W2D-2, that `renameOrganization` requires `hierarchy.edit` at the install anchor (so an
organization-bound Administrator cannot rename, and the install-wide `organization_name_ci_uq` `409` is reachable only by install
principals: no cross-tenant oracle), and for W4D-1 that the sub-organization and environment rename sibling-name `409` inside one
organization is accepted. *Built:* (1) `HierarchyAdministrationService.renameOrganization` makes **two requires in `entitlement.set`'s
order**: `hierarchy.edit` at the install anchor (`ResourceKind.ROLE_DEFINITION`, `EntitlementAdministration.INSTALL_ANCHOR_ROLE`), then at
the named `ORGANIZATION` (the tenant compare: another organization's id is the same `404` as a nonexistent one, `I5`), both before any
write or audit row. (2) The gateway's `renameOrganization` handler uses `InstallResourceResolver`, which reads nothing from the request, so
the interceptor refuses an organization-bound caller **`403 FORBIDDEN` before the path id is read**. *Why `403` and not `404`:* the verdict
is about the caller's grant and is identical for its own organization, another existing one and a nonexistent one (tested), so it carries no
information about any organization; `404` would have required reading the path first and would then differ by existence for install
callers. An install-bound caller naming an organization that is not its own tenant's gets the existing `404` (the handler's `requireOwn`
and the service's second require). `createSubOrganization` keeps `OrganizationNodeResolver`; the sub-organization and environment renames
keep their per-organization scope. (3) `console.yaml` (HF3 second: my operations only): `renameOrganization` is `x-sm-scope: install` and its
description and `409` say install scope only, `403` for an organization-bound caller, the `409` install-wide and reachable by install
principals only; the `renameSubOrganization` and `renameEnvironment` `409` descriptions say the clash covers siblings the caller cannot see and
that this is **accepted** (`OR-125` (3), W4D-1). No operation, status, schema or `ErrorCode` is added, so lanes B and D's contract edits do
not overlap. Postman regenerated (`check-postman` OK, 64 operations; the collection logs in as the install-bound `bootstrap-admin`, so its
`renameOrganization` request, a rename to the organization's own name, still answers `200`); `ENDPOINT-INVENTORY.md` row, the `D-REG` verb-scope
row and the promoted draft's header comment updated. *Tests, red first:* `HierarchyAdministrationServiceTest` 17 (3 new, 3 red before the
service change, 3 red again with the install require removed), `HierarchyAdministrationControllerTest` 21 and
`HierarchyAdministrationSchemaMirrorTest` 12 (6 red before the gateway and contract change across the two classes; 4 red with the resolver
reverted). *Run on the real `postgres:14` container through the lock:* `HierarchyAdministrationServiceIT` 16/16 (the organization rename
now by an install-bound principal; new `anOrganizationBoundAdministratorCannotRenameItsOrganizationSoTheInstallWideConflictIsNoCrossTenantOracle`
asserts the identical `AccessDeniedException` for a taken name, a fresh name, another and a nonexistent organization and that nothing is
written or audited), `HierarchyAdministrationEndpointIT` 10/10 (the former `...IsA409TheOpenOracle` pin is replaced by two tests: the
organization-bound `ADMIN` gets `403` with the same body for a taken and a fresh name and for a foreign and an absent id and nothing is
written; the install-bound principal gets `409` on a case-folded clash, `200` plus one `HIERARCHY_NODE_RENAMED` audit row on a rename, and `404`
for a foreign or absent id; with the resolver reverted and the service's install require removed, 2 of the 10 are red), `HierarchyEndpointsIT`
13/13 and `ProductionAuthenticationIT` 4/4 (the route set is unchanged). `./contracts/run-checks.sh --strict` all passed (checks 14 and 15
included). *Not done / for the integrator:* the full reactor `verify` and the live gate L5 are the integrator's; a frontend that lets an
organization-bound user rename its organization (none exists) would now meet `403`. `V015`'s install-wide index is unchanged.

**OR-125 (3) fix round (wave 5, lane C, `x/w5c`, Sonnet 5.5; Opus review findings W5C-1 to W5C-4).** *W5C-3 fixed (red first):* the
note above says the handler uses `InstallResourceResolver`; since this round it uses the new `InstallOrganizationResolver`, which
**parses the path's `organizationId` (shape only; `404` for a malformed or missing one, before the authorizer, for every caller)**
and then returns the same install anchor. It deliberately does not compare the id with the session's organization or look it up, so
the organization-bound `403` stays identical for own, other and nonexistent ids; the comparison stays in `requireOwn`/the service,
after the decision. `InstallResourceResolver` is now `setEntitlement`'s alone. New tests: `HierarchyAdministrationControllerTest#
aNonUuidOrganizationIdOnRenameOrganizationIs404BeforeTheAuthorizerForEveryCaller` (red first: `400` for the install-bound caller;
asserts no authorizer call and no port call for an install-bound and an organization-bound caller) and
`HierarchyNodeResolversTest#theInstallOrganizationResolver...` (install anchor under the session's tenant whatever well-formed id is
named; `404` for a malformed or missing one); the schema-mirror test now expects the new resolver; `HierarchyAdministrationEndpointIT` (10/10) asserts `404` for `not-a-uuid` from both the organization-bound and the install-bound caller, and with `HierarchyEndpointsIT` 13/13 and `ProductionAuthenticationIT` 4/4 ran on the real `postgres:14` container through the lock. *W5C-1:* `OR-125`'s row is published and
is not edited; a dated note on `D10-G2-APPROVAL-2026-09-15.md` §1.9 row 42 (both tables) and a dated rider on the `BE-D57` body state that
an organization rename is evaluated at install scope per `OR-125` (3). *W5C-4:* the promoted draft's `renameOrganization` carries an inline
"superseded by `OR-125` (3)" note. *W5C-2, ruled `OR-131` (owner, option (a)): the HTTP rename stays install-scope only and an operator command renames any organization.* **Consequence:** an organization can be renamed over HTTP
only by an install-bound principal naming its own organization (`requireOwn` and the service's second require are `404` for any other).
No API creates an install-scope binding and the only install-bound principal is `sm_control` V007's bootstrap administrator, who belongs to one organization (ACME in the local seed); so every other organization needs the command. *Built:* `OrganizationRenameMain` (cp-core `persistence`, the `EntitlementMain` pattern: environment-only configuration `SM_CONTROL_RENAME_ORGANIZATION_ID`/`_NAME`/`_ACTOR` plus the `sm_cp_runtime` credential, no Spring context, exit codes 2 malformed, 3 unknown actor or organization, 4 name taken, 1 other, fixed-text errors, nothing echoed) over `OrganizationRenameService` (`hierarchy` package; name rules are `HierarchyAdministrationService.validName`, uniqueness is `organization_name_ci_uq` read as a `23505`, so `OR-123`'s case-insensitive rule is the API's own). **Deviation, stated:** the audit row is `HIERARCHY_NODE_RENAMED` with client address `operator-cli` and **an actor**, an active local principal of that organization: V018's trigger refuses a runtime write on `organization` with no actor (the no-actor exemption is `ENTITLEMENT_SET` on `entitlement` only) and published migrations are frozen, so a no-actor row or a new operation name would need a new `sm_control` migration; the owner did not ask for one. *Tests:* `OrganizationRenameMainTest` 5/5 and `OrganizationRenameMainIT` 4/4 on `postgres:14` through the lock (renames another organization with one `operator-cli` audit row; refuses a case-only clash, exit 4, nothing written or audited; actor of another organization or absent organization, exit 3; wrong password, exit 1, never echoed). Documented in `console.yaml`, the inventory row and `docs/runbooks/operations.md`.

## W3 — The operator user (only if OQ-6 (a), (a′) or (a″))

### T3.1 — `cp-core` principal and binding administration

- [x] `PrincipalAdministration`:
      - create a local principal (session organization, Argon2id `OR-7`, `must_rotate`);
      - list;
      - deactivate (`user.deactivate`);
      - reset a credential (`user.edit`, sets `must_rotate`).

      Under OQ-6 (a), **refuse `409` for the caller itself and for any principal holding an Administrator
      binding** (R18).
- [x] `AssignmentAdministration`:
      - list (`role.view`);
      - grant (`role.assign` **at the granted scope**, canonicalized through `ScopeStore.canonicalize`;
        Administrator refused unless OQ-6 (a′));
      - revoke (at the binding's scope; under (a), an Administrator binding is never revoked; under (a″), only the
        last install-scope Administrator binding is protected, counted under a row lock).
- [x] Deactivation revokes the principal's sessions in the same transaction (a deactivated principal must not keep a
      live session).
- Tests:
  - escalation: a principal with `role.assign` at a unit cannot grant at the organization (`403`);
  - a deactivated user's existing session is refused on the next request;
  - one Administrator cannot deactivate, reset, or revoke the binding of another (`409`);
  - the bootstrap admin cannot deactivate itself.

**Results (T3.1, 2026-10-08, wave 3 lane D, `x/w3d`, Sonnet 5.5; Docker down).** **cp-api** (`identity` package):
`PrincipalAdministration` (`list`, `create`, `deactivate`, `resetCredential`), `AssignmentAdministration` (`listRoles`,
`list`, `grant` returning `Granted(assignment, created)`, `revoke`), the views `UserView` (no field for a credential or a
hash), `RoleView`, `AssignmentView`, and three exceptions with fixed per-`Reason` messages, no request value and no cause
(`PrincipalConflictException`, `AssignmentConflictException` 409, `AssignmentInvalidException` 422). **cp-core:**
`PrincipalAdministrationService`, `AssignmentAdministrationService`, the ports `PrincipalAdministrationRepository` and
`AssignmentRepository` with `JdbcPrincipalAdministrationRepository` and `JdbcAssignmentRepository` (own
`IdentityAdministrationConfiguration` and `IdentityAdministrationPersistenceConfiguration`, one `@Import` in
`ControlPlaneConfiguration`), and `BuiltInRoles` (the three V003 ids; an IT compares them with the table). **No migration:**
V018 already grants the runtime role what T3.1 writes (`INSERT` and column `UPDATE` on `principal`, `INSERT, DELETE` on
`role_binding`); the ITs assert it still cannot delete a principal or write a role.

**Decisions inside the rulings, for the reviewer.** (1) **Administrating a user is an organization act** (`ResourceKind.PRINCIPAL`
at the session's organization): `user.view`, `user.create`, `user.deactivate` and `user.edit` are required at the organization,
so a unit-bound Administrator cannot administer users. D-REG 4 records the divergence from `console-integration.md` 5.2
(`install down`); the code builds the organization reading and the owner's call stands open (`listRoles` is built at the organization too, which `console-integration.md` 5.2 row 13 reads as
install: **open owner question W3D-2, below**). (2) **OQ-6 (a), R18:** the
caller itself and every holder of an Administrator binding at any scope are refused `409` for deactivate **and** reset
(`CALLER_ITSELF`, `ADMINISTRATOR_PROTECTED`); an Administrator binding is never revoked (`409`) and the role is never granted
(`422`), whoever asks. (3) **Order:** shape, then `require` (before any read of the principal and before any hash, so a
caller who may not administer users cannot burn Argon2), then the work. A reset repeats its refusals **under the row lock**
(`FOR UPDATE`) and pre-checks them from an unlocked read so a refused reset pays no Argon2. (4) **Sessions:** deactivate and
reset each call `SessionRepository.revokeAllFor` in the same transaction as the change. (5) **Grant authorizes `role.assign`
at the granted scope's own node**, then reads that node's real path and requires the request to equal it (an environment
named under the wrong sub-organization is a 404, never a foreign-key violation or a scope row the tree does not have); the
scope is canonicalized inside the audited transaction by `JdbcScopeStore`, after the principal row lock and its liveness
check. An identical grant is a replay (`created = false`, no audit row). The install scope is not grantable here. (6) **List**
shows only the bindings a `role.view` grant of the caller reaches downward; a binding at a retired instance is neither listed
nor revocable (V017). (7) Create has no idempotent replay: a repeated username is `409 USERNAME_TAKEN` (D-REG S5); the
username is install-wide unique, so another organization's name conflicts too (**an open owner question, W3D-1, below**).

**Evidence.** *Offline, run:* `PrincipalAdministrationServiceTest` 20/20 and `AssignmentAdministrationServiceTest` 20/20
(fake collaborators: authorization first and at the right node, nothing read or written on a refusal, shapes, the Administrator
rules, the pre-check before the hash); whole-reactor `test-compile` clean; `cp-core` surefire 531 run, 1 error, the error being
`SmControlLockoutRecoveryMainTest` needing the `postgres:14` container (Docker down, not this task); arch-tests 89/89.
*Integration, **indicative, PG16 harness** (a Homebrew PostgreSQL 16 under the lane's scratch directory, behind a test-only
shim copied in for the run and removed after; **the `postgres:14` Testcontainers run is owed**):*
`PrincipalAdministrationServiceIT` 16/16 and `AssignmentAdministrationServiceIT` 21/21. They cover the plan's four named
tests: a principal with `role.assign` at a sub-organization cannot grant at the organization nor at a sibling (403/404); a
deactivated user's existing session is refused on the next request; one Administrator cannot deactivate, reset, or revoke the
binding of another (409); the install administrator, like the bootstrap admin, cannot deactivate itself (409). **Proof is by
mutation, not red-first:** the services and their tests were written together, so each of nine mutations was applied to the
main source, the named IT run on the PG16 harness (red), and the source restored (re-run 2026-10-08, logs in the lane's
scratch): grant of Administrator allowed (1 red), Administrator holders not protected (2), grant not authorized at the granted
scope (2), deactivation leaves sessions alive (1), reset leaves sessions alive (1), requested ancestors not compared with the
real path (1), Administrator binding revocable (1), created user not `must_rotate` (1), grant without the audit context (1
error). **Not mutation-proven:** the lock-time re-check of the reset (unit-proven for order only; a race IT was not written).
**Owed (Docker down):** `PrincipalAdministrationServiceIT` (16) and `AssignmentAdministrationServiceIT` (21) on the real
`postgres:14` container, and the nine mutation proofs above on that container; a full reactor `verify`; the Opus wave review;
T3.2 (gateway routes, `CredentialTransportFilter`, the `console.yaml` operations, Postman) is not part of this task.

**Wave 3 review fix round (OR-70, 2026-10-08, Sonnet 5.5; Docker down; commits cite "W3 review <ID>").**

*Fixed.* **W3D-3 (username look-alikes):** a username is now refused if it contains a FORMAT code point (zero-width space
and joiner, word joiner, bidi overrides, soft hyphen, byte-order mark), an unpaired surrogate, a private-use or unassigned
code point, in addition to whitespace and controls, and it must be in Unicode normalization form C. Not done, and said so:
case folding and confusable letters (a Cyrillic "a" beside a Latin one) are not folded, because a case-insensitive
uniqueness would need a new index (a migration, OR-22) and there is no confusables table; a name differing only in case from
another is a different username, as it always was for login. **W3D-4 (a grantor holds what it grants):** `grant` now
requires the caller to hold every verb of the granted role at the granted scope (`403`, naming the first verb it lacks);
`resetCredential` requires the caller to hold every verb the target holds, at the scope the target holds it (`403` as
`user.edit`, naming nothing of the target), checked in the unlocked pre-check before the hash and again under the row lock.
The one rule lives in `VerbCoverage` and uses the same prefix test as `ScopeEvaluatingAuthorizationService`. It refuses no
one in v1 (only the Administrator holds `role.assign` and `user.*`, and it holds the whole catalog). *Decision:* **deactivate
is not guarded this way**: it hands the caller no authority it lacks, and the self and Administrator refusals already cover
lock-out; the reviewer's fix text also named deactivate, so the owner may extend the rule to it (one call in
`deactivate`). `PrincipalAdministrationService` gained an `AuthorizationGrantRepository` constructor argument (wired in
`IdentityAdministrationConfiguration`). **W3D-5:** the 404 for an unknown or invisible assignment now says `role assignment
not found` (a new `ResourceNotFoundException(String subject)` constructor for a thing with no `ResourceKind`; adding a kind
would break the totality of the scope table). The gateway's handler renders every not-found as the same generic
`NOT_FOUND` and never reads the message, so nothing on the wire changes. **W3D-6:** a new IT drives V007's own seeded row
(`...0012`) through deactivate and reset, so the Results' "like the bootstrap admin" is now exercised on the real row.

*Recorded for the owner, no behaviour changed.* **W3D-1 (MAJOR): a cross-tenant username oracle.** Create is authorized at the
organization (D-REG 4's reading of rows 21-23) and `principal_username_unique` is install-wide, so a taken name answers
`409 USERNAME_TAKEN` whichever organization holds it. The `409` rolls back (no audit row; it costs one Argon2 hash). The
reviewer's scenario: with organizations A and B, A's Administrator calls `create('alice.ops')`, gets `409`, and has learned
that an account named `alice.ops` exists somewhere (useful for credential stuffing, since login is install-wide by username);
the same caller can create `bob` first and so stop B ever having its own `bob` (permanent, since principals are never
deleted). Under the `install down` reading (console-integration 5.2 rows 21-23; D10 1.7 row 38) only an install-bound
principal could do this. Options: **(a)** accept it, record it as a known cross-tenant oracle in D-REG 5.4 U2 and in the
T3.2 contract text (T3.2 must not document the `409` as an in-organization conflict until ruled); **(b)** evaluate
`user.create` (at least) at install scope, which confines the oracle to install principals but makes organization
administrators unable to create their own users; **(c)** per-organization username namespaces, which needs login to carry the
organization and a new migration. Same class as W2D-2 (organization names); rule them together. The IT
`aUsernameIsInstallWideUniqueSoAnotherOrganizationsNameConflictsToo` asserts today's behaviour. **W3D-2 (MINOR): `listRoles` at
the organization or at install.** `console-integration.md` 5.2 row 13 (`GET /admin/roles`) says install; D-REG 4 says
`install / organization down` for roles and assignments; the code evaluates `role.view` at the organization. The reviewer's
scenario: an Administrator bound at a sub-organization is authorized to grant (`role.assign` at its node) but gets `403` on
`GET /admin/roles` and on `GET /admin/users`, so a console cannot fill its role or principal picker, while an
organization-bound Administrator lists roles row 13 reserves for install. Options: **(a)** install, per row 13 (then no
organization administrator lists roles; the console needs the ids by another route); **(b)** keep the organization reading
and record the divergence with the `user.*` one; **(c)** keep it and evaluate `listRoles` with
`requireHeldAnywhere(role.view)` as `list()` does, since built-in role definitions are not tenant data (this also unblocks the
sub-organization administrator). Rule rows 13 and 21-23 together; T4.2's forward pointers below now name row 13 as well.

**OWED after the fix round (Docker down; nothing here was run on a container).** On the real `postgres:14` container:
`PrincipalAdministrationServiceIT#theSeededBootstrapAdminItselfCannotDeactivateNorResetItself` (V007's row is refused as
`CALLER_ITSELF` for both, status stays `active`); `PrincipalAdministrationServiceIT#aCallerWithUserEditButNotTheTargetsVerbsCannotResetItsCredentialAndACoveringCallerCan`
(a custom role with `user.edit` and `user.view` cannot reset an Operator, `AccessDeniedException`, hash and session untouched;
it can reset an unbound user; the org Administrator can reset the Operator); `AssignmentAdministrationServiceIT#aGrantorCannotGrantARoleWhoseVerbsItDoesNotHoldAtTheGrantedScope`
(the custom role with `role.assign` and `role.view` is refused Operator, no binding, no scope row; it can grant its own role);
the `IdentityAdministrationFixtures.customRole` / `dropCustomRoles` cleanup (an `@AfterEach` in both ITs deletes the roles
so `theRolesAreTheThreeBuiltIns...` still sees exactly three; **unproven that the migrator's delete of `role_binding`,
`role_permission` and `role` needs no audit context**); the nine mutation proofs of the first Results and the lock-time
re-check, still as listed above; a full reactor `verify`; T3.2.

**Evidence of the fix round, offline.** `mvn -o -q -f backend/pom.xml test-compile` clean; `mvn -o -pl cp-core -am test
-Dtest=PrincipalAdministrationServiceTest,AssignmentAdministrationServiceTest`: 25 + 22 = 47, 0 failures (was 20 + 20). Red
proof by mutation on those unit tests (each mutation applied to the main source, the run red, the source restored): grant
coverage removed (2 red); reset coverage removed from both the pre-check and the locked check (3 red); username type classes
removed (1 red); NFC requirement removed (1 red); assignment 404 noun reverted (1 red). The reset coverage inside the lock is
not separately proven (the unit test covers the pre-check and the lock-time call together). Whole offline unit run (`cp-api`, `cp-core`,
`arch-tests`, `console-gateway`, with their upstream modules; Docker down): cp-api 49, mesh-events 160, cp-core 538 run with
1 error (`SmControlLockoutRecoveryMainTest`, a `postgres:14` container), dp-core 1255 run with 1 error
(`IngestServiceContractTest`, also a container; not this lane), console-gateway 667, launcher 86, arch-tests 89/89.

### T3.2 — Gateway routes

- [x] U1–U7 (eight operations, see the Results). The request records redact `initialCredential` and `resetCredential`.
      MF-R3's capture test and (under OQ-14 (b)) the transport filter are extended to them.
- [x] `OR-125` (4): usernames unique per organization (`sm_control` `V019`), login carries the organization.
- Tests: S1's "create the Operator, bind it at `dev`, sign in as it, declare" through HTTP.

**Results (T3.2 with OR-125 (4), 2026-10-08, wave 5 lane D, `x/w5d`, Sonnet 5.5; Docker up, `postgres:14`).**

*What was built.* **Eight operations, not seven** (the W0 recomputation had already said so: U3 is two): `listUsers`, `createUser`,
`resetUserCredential`, `deactivateUser`, `listRoles`, `listAssignments`, `grantAssignment`, `revokeAssignment`; `console.yaml`
64 to 72, `ConsoleOperations` +8, the inventory's served table +8 rows and §2 narrowed to the four admin operations still
unbuilt, Postman regenerated (`check-postman.py` OK, 72 operations). **Gateway:** `UserAdministrationController` and
`RoleAssignmentController` (translators), the records `User`, `UserList`, `Role`, `RoleList`, `Assignment`, `AssignmentList`,
`CreateUserRequest`, `ResetUserCredentialRequest`, `GrantAssignmentRequest`, the enum `UserStatus`, four resolvers (`UserResolver`
for the `PRINCIPAL` the path names; `GrantedScopeResolver`, which reads the body's `scope` through `PeekableBodyRequest` as
`OwnershipScopeResolver` does and records what it authorized so the handler can refuse to act on another node;
`AssignmentResolver`, which reads the stored binding's scope; the existing organization resolver for the lists and the create)
and `UserAdministrationErrorAdvice` (`PrincipalConflictException` and `AssignmentConflictException` to `409`,
`AssignmentInvalidException` to `422`, each `detail` the exception's fixed text). `createUser` and `resetUserCredential` are
`@CredentialBearing` (`403 INSECURE_TRANSPORT` before the body is read). **cp-api:** `AssignmentAdministration.find(organizationId,
assignmentId)`, an address resolution with no verb, so that a revoke can be authorized at the binding's own scope BEFORE the
handler runs (`x-sm-scope: named`); `revoke` still reads and authorizes for itself.

*OR-125 (4).* `sm_control` **`V019__username_per_organization.sql`**: `principal_username_unique` dropped,
`principal_org_username_ci_uq` unique on `(organization_id, lower(username))` (the case rule `V015` applies to node names; it
also closes the look-alike W3D-3 left open). Readiness gained a seventh stage (`verifyV019UsernamePerOrganization`: the index is
unique and over `lower(username)`, the old constraint is gone). **Login carries the organization:** `login`'s body is
`{organization, username, credential}`; `organization` is an organization id (a canonical UUID, tried first, so a name that looks
like another organization's id cannot take its logins) or its name, compared without regard to case, and the username is
compared without regard to case too. An organization that names nothing runs the identical path as an unknown user (one dummy
Argon2 verification, a failure recorded on both throttle dimensions, the same audit row, the same `401` body); a missing, empty or
oversize `organization` is a malformed request (`400`, which says nothing about any organization). The username throttle key is
now `(resolved organization id, lowercased username)`, and `unresolvedLoginSubjectHash` keeps a counter of its own for an
organization that did not resolve, so a counter existing or not is no tell. **Callers updated:** `LocalCredentials` (a third
argument), `PrincipalRepository` (`resolveOrganization`, `findLocalByUsername(organizationId, username)`), `EntitlementService`
(its `set_by` lookup is now inside the organization), `TargetRegistrationMain` (`SM_TARGET_ORGANIZATION`, required),
`SmControlLockoutRecoveryMain` (`SM_CONTROL_LOCKOUT_ORGANIZATION`, required with the username selector), the Postman login
body and environment (`organization`), `backend/local` (`LocalSeed`, `sm-local.sh` banners, `README`, `live-gate-t15.sh`'s
`SM_ORGANIZATION`), `backend/README.md`, the Postman `README`, and every test helper that logs in (`DeclareFixtures` keeps a
username-to-organization map filled by its own seeding; the others take the organization they seed).

*Decisions inside the rulings, for the reviewer.* (1) **`organization` was first required on the wire** (`400` if absent); **the
owner then ruled it OPTIONAL (`OR-131` Q3)**: omitted, it is the install's only organization, `400` when the install holds none or
several, and a NAMED organization that does not exist stays indistinguishable from a wrong credential (see the W5 fix round
below). (2) **Usernames are compared without regard to case at login as well as in the index** (the ruling allowed "the case rule
the schema already uses"); a user created as `Alice` signs in as `alice`. **Ruled by `OR-131` (Q4): case-insensitive usernames
are CONFIRMED.** (3) **A `409` on
`createUser` means only "a user of THIS organization has it"**; the install-wide namespace, and with it W3D-1, no longer
exists (the `USERNAME_TAKEN` text, the `PrincipalAdministration` and `PrincipalConflictException` javadoc and the inventory say
so; the old test `aUsernameIsInstallWideUniqueSoAnotherOrganizationsNameConflictsToo` became its opposite). (4)
**`listAssignments` is evaluated at the organization** (the contract draft's `x-sm-scope: organization`), which is stricter than
the service's "`role.view` held anywhere in the organization"; the two agree for every principal v1 can create (only the
Administrator holds `role.view`, and the API never grants Administrator), and `OR-125` (6) confirms the organization reading for
user and role administration. (5) **The deactivate path is last in the admin block of `console.yaml`**, so the generated Postman
folder (which runs in document order) ends by deactivating the user it created; `grantAssignment` declares `409` (a
deactivated user), `deactivateUser` and `revokeAssignment` declare `400` (a UUID path value, as `DeclaredBadRequestsTest`
requires of every such operation). No `ErrorCode` constant was added.

*Evidence.* **Red then green:** `UsernamePerOrganizationSchemaIT` 7 of 7 red without `V019` ("No migration with a target version
019"), 7 of 7 green with it; the two V019 readiness tests 2 of 2 red with the stage removed, green with it. **Green, run:**
`cp-core` whole module `verify` on `postgres:14`: surefire 567, failsafe 449, 0 failures (it includes `LocalLoginIT` 16,
`LoginAddressThrottleIT`, `CredentialRotationThrottleIT`, `ChangeAuditIT`, `BootstrapAdminIT`, `TargetRegistrationMainIT`,
`PrincipalAdministrationServiceIT` 18, `SmControlLockoutRecoveryMainTest` 17 and the V018/V019 readiness ITs); `console-gateway`
unit suite 892 + the three new classes (`UserAdministrationControllerTest` 19, `RoleAssignmentControllerTest` 19,
`UserAdministrationSchemaMirrorTest` 10), 0 failures; `arch-tests` 102/102 (`CredentialBearingRecordsTest` governs both new
credential handlers); `UserAdministrationEndpointIT` 9/9 through the real application (S1 end to end: create the user, it signs in
and must rotate, bind it at an environment, it sees that environment's target and not the sibling's, revoke takes effect on the
next request); the launcher batch, 15 surefire classes (`*Test`) and 13 ITs, 115 tests, 0 failures: `ProductionAuthenticationIT`, `HierarchyEndpointsIT`, `HierarchyAdministrationEndpointIT`, `TargetEndpointsIT`, `TargetRegistrationEndpointIT`, `TargetManagementEndpointIT`, `CatalogEndpointsIT`, `ProductionRateGateAndForwardingIT`, `DemoParityChecklistIT`, `InstanceEndpointsIT`, `HomeEndpointsIT`, `ApplyEndpointsIT`, `UserAdministrationEndpointIT` (the other launcher ITs log in through the same `DeclareFixtures.Client.login` and were not re-run). `./contracts/run-checks.sh --strict` passes (see the integrator's note). **Mutation proofs**
(each applied to the main source, the named tests run red, the source restored): gateway: revoke authorized at the organization
(1 red), grant authorized at the organization (3), deactivate/reset authorized at the organization (2), `createUser` without
`@CredentialBearing` (1; and 2 red in `CredentialBearingRecordsTest`), credential not zeroised (1), the handler's
authorized-scope check removed (1, after the first version of that test proved too weak and was fixed), a username conflict as
`422` (2), a grant refusal as `409` (1), login `organization` optional (2), credential floor 1 (4), the service's shape refusal
unmapped (1), the request record printing its credential (1), the principal filter dropped (1); cp-core: an unknown organization
refused early (2), throttle key not lowercased (1), key ignoring the organization (1), unresolved key not length-prefixed (1,
after the first version of that test was fixed), `find` returning empty (1), id lookup disabled (11 errors) and name tried before
id (1), user lookup ignoring the organization (1) and case-sensitive (1), the V019 index without `lower()` (2), the repository
mapping the old constraint name (1).

*Flags and owed.* (a) **`frontend/` was not touched.** Corrected by the W5 review (W5D-5): the frontend already sends `{username, password}` (`frontend/src/shared/auth/AuthAdapter.ts:25-28`, `localSessionAdapter.ts:40`), not `credential`, so its login answered `400` before this change too. The frontend owes the rename `password` to `credential`; `organization` is OPTIONAL (`OR-131` Q3), so on a single-organization install it may omit it, and it is needed only when the install holds several. (b) **Existing databases:** `V019` fails loudly if one organization already holds two usernames differing only by case (only hand-written rows can); `login_throttle` rows written under the old key (`sha256("local-login:" + username)`) are orphaned and simply expire. (c) **Operator commands changed their inputs:** `TargetRegistrationMain` needs `SM_TARGET_ORGANIZATION`, `SmControlLockoutRecoveryMain` needs `SM_CONTROL_LOCKOUT_ORGANIZATION` with the username selector; `docs/runbooks/` has no mention of either (T4.2's runbook edit should say so). (d) **Not run:** the Opus wave review; the live gate L5 (the integrator's); the launcher ITs outside the thirteen named; the Postman folder against a live stack. (e) **Merge notes for the integrator (HF3, my order last):** `console.yaml` (my admin block before `# catalog`, the `UserId` parameter before `CsrfToken`, my schemas before `ScopeRef`, the `admin` tag, the login body), `ConsoleOperations` (the last eight registrations), `ENDPOINT-INVENTORY.md` (eight rows at the end of the served table, the counts 64 to 72 in two sentences, §2), the Postman files (regenerate after merging, do not merge the JSON), `ProductionAuthenticationIT`'s route set and `loginBody`, `DeclaredBadRequestsTest`'s `EXPLICIT_SITES` (two entries). `ErrorCode.java` is untouched. The migration is `sm_control` `V019` only; `CHECKSUMS.sha256` gained its line and `frozen-migration-anchors.txt` is left for the publish.


#### W5 review fix round (`OR-70`), lane D, W5D-1 to W5D-8

Commits cite "W5 review W5D-n". Outcomes by finding:

- **W5D-1 (login equal work): fixed.** `resolveOrganization` is ONE statement (`organization_id = :id OR lower(name) = lower(:name)`
  `ORDER BY (organization_id = :id) DESC LIMIT 1`, bind parameters only; a malformed, empty or over-long value runs the same
  statement and matches nothing), and the principal lookup ALWAYS runs, under `PrincipalRepository.NO_ORGANIZATION` (the nil UUID)
  when nothing resolved. `LoginOrganizationResolutionIT` counts the statements of seven refusal paths (wrong credential, unknown
  user, deactivated user, unknown organization name, unknown id, empty, over-long) through a counting `DataSource` and asserts
  they are all equal.
- **W5D-2 (V019 upgrade): fixed.** V019's header no longer says the old constraint was case-insensitive (V002's `UNIQUE(username)` was
  case-sensitive, so `Alice`/`alice` in one organization were legal at V018); `CHECKSUMS.sha256` regenerated (V019 is this lane's
  unpublished migration). `UsernamePerOrganizationUpgradeIT` builds at 018, seeds the pair, and the migration to 019 fails with
  `23505` naming `principal_org_username_ci_uq`; the positive case (case variants across two organizations plus the bootstrap
  administrator) migrates cleanly. The pre-upgrade detection query is in `docs/runbooks/operations.md` ("Upgrade across
  per-organization usernames"), which T4.2 carries.
- **W5D-3 (one case rule): fixed.** The username throttle dimension is keyed by the resolved principal's id (`principalSubjectHash`),
  so the database's `lower()` is the only case rule for a user that exists; for a name that matches no principal the key is the
  Java `Locale.ROOT` lowercase (documented: nothing exists to ask). `SmControlLockoutRecoveryMain` resolves the same way.
  `console.yaml` `login` and the install prerequisites say that folding follows PostgreSQL `lower()` under the database locale (a
  `C`/`POSIX` database folds ASCII only) and that a UTF-8 locale is required for non-ASCII folding.
- **W5D-4: behaviour kept; javadoc fixed.** `PrincipalAdministrationService`'s `invisibleOrUnsafe` javadoc no longer says case is not
  folded. Decision (2) above is ruled by `OR-131` Q4.
- **W5D-5: the flag is corrected** (flag (a) above).
- **W5D-6: noted.** `login_throttle` rows are never deleted (no `DELETE` grant to the runtime role); only their locks expire, so an
  active username alarm at upgrade time is silently dropped (no `LOCKOUT_CLEARED` row). Recorded in the runbook.
- **W5D-7: the organization is not trimmed anywhere.** `LoginRequest` rejects surrounding whitespace in `organization` (`400`, like a
  shape fault) and `SmControlLockoutRecoveryMain` no longer trims it; tested on both sides.
- **W5D-8: readiness also requires a non-partial index** (`indexdef NOT LIKE '% WHERE %'`); a red-first case with a partial index
  is in `ControlPlaneConfigurationIT`.
- **`OR-131` Q3 (organization optional): implemented.** `console.yaml` `login` drops `organization` from `required` and states the
  rule; `LoginRequest.organization` is `@WireOptional`; an omitted organization on an install without exactly one raises
  `LoginOrganizationRequiredException` (`400 BAD_REQUEST`), after the same statements and before any credential work; the
  Postman login body omits `organization`; `backend/local` and `backend/README.md` banners updated. **The frontend must send
  `credential` (not `password`) and may omit `organization` on single-organization installs.**


## W4 — Close

### T4.1 — The local stack and the Postman collection use the API

- [x] `backend/local/sm-local.sh` (`OR-118`, on `p/local`) keeps its superuser seed only for what the API cannot do
      (none, if W2 and W3 land); otherwise it calls the new routes as `bootstrap-admin` after rotation. Its
      launcher reaches the gateway over loopback, which OQ-14 (b) admits.
- [x] Postman: a "Setup" folder (rotate → name org → sub-org → env → entitlement → load package → scan → register →
      test → create Operator → bind) runnable top to bottom against the local stack.

### T4.2 — Records

- [x] `ENDPOINT-INVENTORY.md`: the new operations in §1; the four admin operations left out stay in §2; the
      frontend's "do not build Admin screens" note narrowed to what remains.
- [x] `console-integration.md` §5: rows for the new operations. Forward pointers, with the text unedited (`I3`), go
      on §5.5's *"`target.create` has no endpoint"* row and on §5.2 rows 21–23 and **row 13** (`GET /admin/roles`),
      whose `install ↓` scope this design reads as organization (D-REG §4, R17; the T3.1 Results, W3D-1 and W3D-2).
- [x] `docs/runbooks/operations.md`:
      - registration through the console, with the commands remaining for install and emergency;
      - the address allow-list and its check-at-use failure classifier;
      - the host-key confirmation step;
      - **S3's order:** add the new public key to `authorized_keys`, rotate (T5), confirm (T6 and the public
        fingerprint), then remove the old key from `authorized_keys` on every host. SM cannot do the last step
        (R3).
      - under OQ-14 (b), `trusted-proxies` for TLS-terminating deployments;
      - the `sm_control` V019 upgrade (done in W5 lane D: detection query, `login_throttle` rows never deleted, operator command inputs).
- [x] `backend/README.md`, `README.md`; the four decisions registered in `backend/decisions.md` with their ids.
- [x] Results section of this plan: the W0 recomputation, MF-R1…MF-R4 measured values, live-gate evidence, build
      evidence, review dispositions.

### T4.3 — Review

- [ ] One Opus review per wave (`OR-70`), one fix round; one combined Fable review at the end.

## Dispatch

**Superseded by the [combined schedule](combined-008-012-013.md)** (waves 1–6 there); the table is kept for the plan's
internal order, which still holds.

| Wave | Tasks | Parallel? | Waits for |
|---|---|---|---|
| W0 | T0.1, T0.2 | T0.2 beside T0.1 | the owner sitting; plan 012's outcome; T0.2 also the licence approval |
| W1 | T1.1, T1.2, T1.3 in parallel → T1.4 → T1.5 | T1.1 ∥ T1.2 ∥ T1.3 | W0; fixture ITs wait for T0.2 |
| W2 | T2.1 → T2.2 → T2.3 | W2 may run beside W1 (different modules and schema) | W0; plan 012 merged; `p/or116` merged before T2.2 |
| W3 | T3.1 → T3.2 | after T2.1 (shares the migration) | OQ-6 |
| W4 | T4.1, T4.2 → T4.3 | — | W1–W3 |

Sonnet implements, Opus reviews each wave, the full offline reactor build closes each wave, and Fable reviews once at
the end (`OR-70`). Nothing is pushed until its wave review passes (the owner's push policy: push after the gates,
fast-forward only).

## Estimate (revised after review, R21)

| Wave | Engineer-days | What changed from the first draft |
|---|---|---|
| W0 (recomputation, rulings, §1.10, contract diff for about 20 operations and 7–10 schemas, check 11, adversarial review and its fixes) | 2 | was 1: the review and the contract diff for about 20 operations do not fit one day |
| T0.2 fixture (OQ-15 (a)) | 0.5–1, plus the owner's licence approval | new (R6) |
| W1 (targets) | 6–7 | was 4. Added: pre-check ordering and the concurrency path (R2, R21); check-at-use in four connectors (OQ-3 (d)); the edit-in-flight lock and its race IT (R4); host-key retire and revive (R9); MF-R1/R4 measurements; fixture ITs; two live gates on the E2E host |
| W2 (scope tree, entitlements) | 2.5 | was 2: the merged `record_change_audit()` body and the `p/or116` conflict |
| W3 (users, bindings) | 3 | was 2.5: the Administrator guards (R18) |
| W4 (stack, Postman, records, reviews) | 2 | was 1.5: the runbook's S3 and transport sections |
| **Total** | **≈ 16–18** (≈ 13–15 without W3) | was ≈ 11 |
| **As ruled (`OR-121`)** | **≈ 15–17**: W3 is in (OQ-6 (a)); W0 shrinks by ≈ 1 (rulings, §1.9 and the validator count are done or moved to plan 012 T1); T2.1 shrinks (the name columns are `OR-120`'s) | — |

Under the agentic model with W1 ∥ W2, roughly **5–7 working days of wall time** including reviews, the owner sitting
and the licence approval. The first draft said 3–4. The largest uncertainties are MF-R4 (whether sshj can pin a
resolved address) and how long the E2E live gates take to stage.

## Scope and non-goals

As D-REG §1: no organization creation, no master-key rotation over HTTP, no broker connections, no custom roles,
no `group.*`, no VM targets, no deletes. The operator commands are not removed. Extending OQ-14's transport rule to
the already-shipped `setConfigSecret` and `rotateOwnCredential` is a separate, flagged change, not done here.

## Results

*(Each task appends its own paragraph under its own section above; this section holds what is plan-wide.)*

### W0 recomputation (T0.1r, 2026-10-07, wave 1 lane C)

Recorded from the plans and the tree, **not** from merged code: plan 012 T1/T4 (lane B) and `OR-120` had not reached the
base (`p/plans` @ `9199216a`) when this was written.

| Fact | Value | Source |
|---|---|---|
| Catalog in the tree today | **38** (`validate-openapi.py`, `PermissionCatalogMirrorTest`) | the base |
| Catalog after plan 012 T1 | **43** — adds `instance.undeclare`, `instance.restart.overrideSafety`, `target.edit`, `hierarchy.edit`, `entitlement.set` (rows 39–43 of §1.9) | plan 012 T1 lines 78–82, §1.9 |
| Built-in role counts after T1 | **Administrator 43 · Operator 24 · Viewer 8** | §1.9 role table; plan 012 T1 (`BuiltInRoleSeedIT`) |
| This plan's three verbs | rows **41–43**; none is in `Permission.java` until plan 012 T1 lands, so **check 11 refuses any operation that uses one until then** — the draft was validated against the 43-verb catalog in a scratch copy | §1.9; the draft's header |
| `record_change_audit()` base body | plan 012's `sm_control` **V017** (`INSTANCE_UNDECLARED` added to V013's/V014's list); T2.1's `V018` is a merge of that body and this plan's names, as the schedule says | plan 012 T4 line 133; combined schedule §5 |
| `sm_control` migrations in the base | up to **V014**. **V015** (`OR-120`, hierarchy display names) is committed on `p/names` @ `bcf1a2aa`, **not merged**; V016/V017 are plan 012's (lane B, wave 1); this plan's is **V018** | `git ls-tree`, schedule §5 |
| `sm_data` migrations in the base | up to **V020**; this plan's is **V022** (after plan 008 S2's V021) | schedule §5 |
| Operations the draft adds | **22**, not 21 (see below) → `console.yaml` 46 → 68 once all land | the draft |

**The contract draft** is [`registration-api-contract-draft.yaml`](../../design/backend/registration-api-contract-draft.yaml)
(operations enter `console.yaml` with their handlers: T1.4, T1.5, T2.3, T3.2). It was spliced into a scratch **copy** of
`console.yaml` and `validate-openapi.py` (catalog widened to 43 the way plan 012 T1 widens it, ErrorCode parity against
the Java file with the four new constants) was run on the copy: **every check passed (exit 0)**. Running it found one
divergence a reading had not (below). `console.yaml`, `ConsoleOperations`, `ErrorCode.java` and the Postman collection are
**unchanged** by this task.

Four things the draft changes from D-REG, each for the reviewer (T0.1 step 6) to rule on; none is silently resolved:

1. **U3 is two operations** (`deactivateUser`, `resetUserCredential`), because `ConsoleOperations` maps one operationId to
   one `Permission` and the interceptor authorizes before the body is read. The users surface is **eight** operations
   and the plan's/schedule's "seven" for T3.2 (W5, HF3) becomes eight.
2. **`x-sm-scope: named`** (new; W1 review C-2 replaced the first draft's overloaded `derived`) for the nine operations
   whose scope is the one the request's subject names. Adopting it at promotion is one line in the contracts `SCOPES`
   list plus the check-11 docstring.
3. **A request-body scope cannot be a `ScopePath`** (check 14, `I5`: no `organizationId` in any request schema; `ScopePath`
   has one). The draft adds `ScopeRef` (the deepest id; the server derives the levels above and the organization is the
   session's) for `ownershipScope` and the granted `scope`. **Found by running the validator.**
4. **`Target.retiredAt`** is added (T7 returns "Target (retiredAt set)"; the schema has none).

Still open from T0.1: **step 6 (the Opus adversarial review of D-REG + this plan + the draft)** — one reviewer per wave, at
wave 1's integration point, not run by the implementer; its dispositions go to D-REG §15. **Step 7 done:**
`./contracts/run-checks.sh --strict` — ALL CHECKS PASSED on this branch (check 15: 46 operations, no Postman drift; the
collection is unchanged because `console.yaml` is), and `check-frozen-migrations.py` passes (no migration touched).

### OR-125 (2) Results (2026-10-08, wave 5 lane A) — the pinned host key's type is persisted and every connector negotiates it

The interim of review `W4C-1` (discover the key family by connecting, up to three attempts) is replaced where the type is known.
`sm_data` **V027** adds `target_connection.host_key_algorithm text NULL`, held by a CHECK to the five types the scan can offer.
**Written with the pin it describes, in one statement:** `registerTarget` stores the type of the pinned key as the data plane's own
pre-check scan reported it (the gateway reads `RegistrationPrecheck.typeOf(pin)`; the request cannot supply it), and
`editTargetConnection` writes the pin and the type of the key the re-pin's scan reported for it in the same `UPDATE`, clearing it
to `NULL` for a type the data plane does not pin by and keeping it when nothing was scanned (an `sshUser`-only edit). **Every
connector** (`SshjDockerTransport`, `SshjHostConnector`, `SshjTargetSshProbe`, through `PinnedHostKeyConnect`) takes the recorded
type from the connection record via `SshDockerPlacement.pinnedHostKeyType` / `Binding.pinnedHostKeyType` and makes ONE attempt
with that type first (the other types stay behind it in the list so the user's key can still sign, as before); a wrong recorded
type fails closed after that attempt instead of being discovered around. **Back-fill rule (scan-free, documented in V027):** none
exists, because a SHA-256 fingerprint cannot be inverted to its key type, so the column is **nullable** and `NULL` means "not
known": `PinnedHostKeyConnect` falls back to the family discovery only while `NULL`, reports the exact type of the key that matched
the pin on its first success to a `HostKeyTypeRecorder` (production: `TargetConnectionRows.recordHostKeyType`, a conditional
`UPDATE ... WHERE host_key_algorithm IS NULL` scoped to the tenant, the live row and that pin, so a recorded type is never
overwritten by a guess and a late report for a pin since replaced changes nothing), and a replay of the registration back-fills it
from its fresh scan. The operator command's registration scans fingerprints only, so its targets start `NULL`. Inventory
readiness now refuses a V026 schema. **Proof:** `PinnedHostKeyConnectTest` (14: five new), `InventoryRegistrationTest` (38, seven
new), `InventoryTargetManagementTest` (48, six new), `TablePlacementCatalogTest` (6, one new), `SshTargetConnectionTesterTest` (13,
one new), `InventoryReadinessTest` (6), `TargetRegistrationControllerTest` (46, two new), `HostKeyTypePersistenceIT` (9, new, real
PostgreSQL 14), and on a real `sshd` `SshdFixtureIT` (7, four new: a recorded type connects in one attempt for each of the host's
three key types; discovery reports the exact type; the pooled connector reports once and only when none was recorded; every
connector fails closed on a wrong recorded type) and `TargetConnectionTestIT` (8, one new). RED was seen first at each layer (5 of
14, 10 of 104 and 9 of 9 failing or erroring). **Mutations killed:** each of the three connectors passing `null` for the type
(`SshdFixtureIT`/`TargetConnectionTestIT`), the edit writing the scanned type when nothing was scanned and the locked record's type
when a scan was made, the replay back-fill without its null guard, the catalog dropping the type, the conditional `UPDATE` without
`IS NULL` or without the tenant, and the CHECK dropped. **Not exposed on the wire:** `TargetConnection` in `console.yaml` does not
carry the type (it is the data plane's own record), so the contract is unchanged by this part.

### T4.1 and T4.2 Results (2026-10-09, wave 6 lane C)

**T4.1, the local stack and the Postman `Setup` folder use the API** (commit `W6 T4.1`). `backend/local/sm-local.sh up` now runs: build,
PostgreSQL, Phase 0 and both migration steps, **the launcher**, then the seed **through the API as `bootstrap-admin`**, then the target.
`LocalSeed.java` is an HTTP client of the launcher (JDK `HttpClient`, the two `__Host-` cookies and `X-SM-CSRF` sent by hand, as
`cookieMode=manual` does); `hash` is the one command left that uses the application's classes, because the migration step needs the
bootstrap credential's hash before any API exists. **The superuser seed is gone entirely** (none of it is something the API cannot do).
The steps: `login` and `rotateOwnCredential`; `listOrganizations` and `renameOrganization` (the install's one organization becomes ACME);
`createSubOrganization` x2 and `createEnvironment` x3 each; `setEntitlement` (`cache`, 5); `createUser`, `listRoles`, `grantAssignment`
(Operator at ACME-Sub-1 / Dev) and the Operator's own sign-in and rotation of its provisional credential; with a complete target file,
`scanHostKeys` (it refuses to go on unless one offered key has the fingerprint in the target file), `registerTarget` and
`testTargetConnection`. **Replay-safe:** each step is idempotent on the server or looked up first; the administrator's rotation is
recorded the moment it succeeds (`.state/admin.rotated`, from a `SM_LOCAL_ADMIN_ROTATED=1` line) even if a later step fails, a run killed
between the rotation and that record recovers by trying the new credential when the initial one is refused, and the administrator's
current credential is kept in `.state/secrets.env` (mode 600). The `Setup` folder in the generated collection is `SETUP_STEPS` in
`generate-postman.py` (14 requests: login, rotateOwnCredential, getIdentity, renameOrganization, createSubOrganization, createEnvironment,
setEntitlement, loadPackage, scanHostKeys, registerTarget, testTargetConnection, createUser, listRoles, grantAssignment); each operation
still appears exactly once in its own folder, and `check-postman.py` verifies both (the folder equals `SETUP_STEPS`, each naming a contracted
operation). The `registerTarget` request builds its JSON body from the pasted key with a pre-request script that escapes newlines. The
host key is a decision: `hostKeyFingerprint` has a `<SHA256:...>` placeholder and nothing copies a scan's key into the pin.

*Evidence.* `bash -n sm-local.sh`; `backend/local/test-sm-local.sh` ok; `sm-local.sh check` through the docker lock (prerequisites ok, the
plan line names the API seed; no `up`, nothing started, the live-gate stack on 55440/8080 untouched); `LocalSeed.java` compiled with
`javac` against jackson 2.18.3 and a stub `CredentialHasher`, then **run against a purpose-built mock of the API** (a Python HTTP server that
enforces the cookie, `X-SM-CSRF` and `Origin` rules and the credential state machine): `seed` end to end from the initial credential
(rotation, rename, hierarchy, entitlement, Operator created and bound, Operator rotated), a replay with `SM_LOCAL_ADMIN_ROTATED=1`, a replay
without it (the recovery path), and `register-target` (scan, register, test); all `rc=0`. `generate-postman.py` regenerates byte-identically and
`check-postman.py` reports OK (73 operations, 72 served, 1 wired); `./contracts/run-checks.sh --strict` ALL CHECKS PASSED (with
`SM_FRONTEND_NODE_MODULES` set and a temporary `contracts/view-models/node_modules` symlink, removed before the commit). Every wire name
LocalSeed and the `Setup` folder use was read from `console.yaml` (field names, status codes, response keys such as `targetId`,
`assignmentId`, `offered`).

**T4.2, records** (commit `W6 T4.2`). `ENDPOINT-INVENTORY.md`: a 2026-10-09 header note (73 operations, 72 served and one wired; what the
console can do by wave; migration state; what is owed), the hierarchy "names are set only by SQL" row corrected, the two "operator command that
registers the target" passages corrected, the "written; not yet run" cells of the hierarchy and entitlement rows replaced with the run that
exists (`HierarchyAdministrationEndpointIT` 10/10 on `postgres:14`, recorded in W4's results), the §1 heading no longer claims 32 operations are
all there is, and §6 item 4 narrowed from "do not build the Admin role/user/assignment screens" to what remains unbuilt (§2's four operations,
custom roles, the group screens). `console-integration.md` §5: **forward pointers, text unedited (`I3`)** on §5.5's *Create a Target* row, on
§5.2 rows 13, 21, 22 and 23 (organization, not install, as built and as `OR-125` (6) confirmed; user `PATCH` became two operations) and on rows
17, 18 and 19 (the assignment operations, built by the same task). `docs/runbooks/operations.md`: "Register a target (SSH host) through the API"
rewritten (preconditions, the address allow-list `sm.dp.targets.allowed-networks` and its check-at-use classifier `target.address.refused`, the
host-key confirmation on the host, the operator commands that remain for install and emergency, the sshd `PerSourcePenaltyExemptList` note
kept); new sections "Edit, re-pin, rotate the key, test and retire a target" (S3's order: add the new public key, rotate, confirm with the public
fingerprint, then remove the old key from `authorized_keys` on every host, a step SM cannot do), "Create users and bind them to a scope" and
"Behind a TLS-terminating proxy: `INSECURE_TRANSPORT`" (`sm.gateway.trusted-proxies`, the proxy must overwrite `X-Forwarded-Proto`); the V019
upgrade and the organization-rename command sections were already present and are cross-referenced. `README.md`, `backend/README.md`,
`backend/local/README.md`, the Postman README and `docs/runbooks/README.md` state the merged tree. The four decisions `BE-D54` to `BE-D57`
were already registered in `docs/design/backend/decisions.md` (lines 99-102).

**Owed (stated exactly, not claimed).** The live gates ran on 2026-10-09: L1, L2, L4, L5 and X1's live apply PASSED; L3 is partial (`l3-rotate` passed, `l3-edit` not run, pending the owner's permission); see the [combined schedule's live gate results](combined-008-012-013.md#live-gate-results-2026-10-09). **The first
live run of `sm-local.sh up` and of the Postman `Setup` folder is owed**: LocalSeed was exercised only against a mock, and the collection's
pre-request script and captures were not run inside Postman. X1's Docker file-mode fix is built and its live apply passed. The combined Fable review is
**owed**. Not built: the agent gateway (plan 015, `OR-126` to `OR-130`), the plain-VM adapter (plan 014), live browser notify. The Opus wave review
of this lane (T4.3) is the integrator's.

*2026-10-09 note: X1 is built; only its live apply is owed.*

### Fable F-2 Results (2026-10-09, fix round `x/fable-fix`) — the organization-rename command is in the `EntitlementMain` pattern

**This implements `OR-131` Q1 as ruled ("in the `EntitlementMain` pattern") and supersedes the earlier named-actor deviation.** The wave-6
build had `OrganizationRenameMain` name an active local principal of the organization as the audit actor
(`SM_CONTROL_RENAME_ORGANIZATION_ACTOR`) because V018's trigger refused a no-actor write on `organization`. `EntitlementMain` instead
writes an `operator-cli` audit row with **no actor** (V018's exemption for `ENTITLEMENT_SET`). Now faithful: `sm_control` **V020**
(`V020__operator_rename_no_actor.sql`, new; V001 to V019 untouched) replaces `record_change_audit()` whole (V018's latest body, every
earlier entry kept; the `audit_operation` CHECK needs no widening because `HIERARCHY_NODE_RENAMED` has been in it since V018) with exactly
one added gate: a no-actor `HIERARCHY_NODE_RENAMED` is admitted on `sm_control.organization` from client address `operator-cli` only; any
other table, other operation or other address is refused `OR025`, so the HTTP path (which names its authenticated principal) is still
database-enforced against a path that forgets its actor (not authentication: any `sm_cp_runtime` session could set the client-address GUC, and the HTTP application cannot produce `operator-cli` only because `ClientAddresses.canonicalize` accepts IP literals only (the same posture as `EntitlementMain`'s V018 exemption)). A V020 readiness stage refuses a V019 schema naming V020. `OrganizationRenameService` and `OrganizationRenameMain` drop
the actor (and `SM_CONTROL_RENAME_ORGANIZATION_ACTOR`, `UnknownActorException`); exit 3 now means only "no such organization".
`CHECKSUMS.sha256`, `SmControlPostgres.LATEST_VERSION` (020) and the pinned counts follow. Tests: `OperatorRenameAuditSchemaIT` (an
operator-cli rename without actor is accepted and audited with a null actor; an HTTP-address write without actor is refused `OR025`;
the exemption covers no other table (`organization_unit`, `environment`) or operation (`HIERARCHY_NODE_CREATED`)),
`OrganizationRenameMainIT` (row has a NULL actor), `ControlPlaneConfigurationIT` (V019 schema refused naming V020).

