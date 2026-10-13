# D-KEY: the DP secret store's key hierarchy, rotation, backup and the keyed config hash

**Status: PROPOSED, UNBASELINED.** Written 2026-10-05 by [plan 010](../../plans/backend/010-dp-data-model-and-remote-flow.md)
Task T2.3, against `integration/dp-remote-demo` @ `71e1ac2`. Docs only: it touches no code, no migration and no contract.
It designs what [`OR-30`](../DECISIONS.md) (credentials are envelope-encrypted in PostgreSQL, master key from the
environment, SM owns master-key storage, rotation and backup) and [`OR-32`](../DECISIONS.md) (the config-file integrity
hash is an HMAC under a DP-held key) left to "the slice". It is the input to T2.4 (the store, `V009`, rotation), T2.6
(the launcher boot) and T5.1 (the keyed hash seam).

**Decisions drafted here** carried the placeholder `«next free BE-D»` (§12) until the integrator allocated the real ids in
merge order (plan 010 Global Constraints; `OR-11`, `OR-17`): drafts K-1 to K-4 are now `BE-D28` to `BE-D31`, registered, with
their bodies, in [`decisions.md`](decisions.md).

**Binding inputs, implemented and not re-decided:**

| Ruling | What it fixes here |
|---|---|
| [`OR-30`](../DECISIONS.md) | Envelope encryption in PostgreSQL, in a schema separate from config and journal tables; the master key comes from the environment; config rows, history, exports and logs carry only references. |
| [`OR-32`](../DECISIONS.md) | The write-verification, crash-recovery and ladder hash is an HMAC under a DP-held key. It may share OR-30's key machinery. |
| [`OR-49`](../DECISIONS.md) (owner ruling 2026-09-29, "Q-T") | The second tenant list, used by the key re-wrap command. §5.4 shows what it does and does not reach. |
| `I6` | No secret value in config storage, history, diffs, exports, logs. Hashes are over references, except OR-32's keyed hash, whose dependence on resolved values is the flagged reading. |
| `I5`, `I7`, `I8` | Tenant scope is server-side; `sm_secret` is DP-owned and the CP never touches it; every dependency open source and commercially free ([`REQ-014`](../../requirements/REQ-014-oss-commercial-licences.md)). |
| Plan 010 Global Constraints | "Keep Flyway and schema-owner credentials out of the running application" (the migrator never reaches the app process, `SmDataProperties`' javadoc), and setup commands are standalone plane-local `main`s that never boot a Spring context. |

**Flagged, not resolved:** `OR-31` (amends LOCKED `D2`) and `OR-32` (names `I6`) are "for architect-group ratification".
This design builds against both and relaxes neither. Nothing here amends a LOCKED decision. It adds no verb, no event and
no contract field.

**What the design does not do.** It does not decide the owner questions in §11. Where it recommends, it says so, and says
what stays unchanged under each answer.

---

## 1. The running scenarios

Every option in this document is run through the same eight cases. The plan's Review Focus 5 is the first three.

| # | Scenario | Why it is real |
|---|---|---|
| **K1** | **First boot.** A fresh install, correct key, empty `sm_secret`. | The first thing an operator does. |
| **K2** | **Wrong key at boot.** A populated install (1,000 secrets across 40 tenants). The operator pastes last quarter's key under the current key id. | The plan's Review Focus 5. It must fail at boot, not at the first SSH connection. |
| **K3** | **Rotate** the same populated install while an apply that crashed is being resumed and reads an SSH key between two steps of the rotation. | Review Focus 5: "no window in which a resumed apply cannot decrypt". |
| **K4** | **A stolen database backup**, with no environment and no KEK. | The reason OR-30 chose a separate key over plain columns. |
| **K5** | **A tampered row.** Someone with write access to the database copies tenant A's `SSH_PRIVATE_KEY` row into tenant B's row, or relabels a `CONFIG_FIELD` row as `SSH_PRIVATE_KEY`. | The `aCiphertextMovedToAnotherTenantOrRowFailsAuthentication` test. |
| **K6** | **The KEK is lost**, database intact. | OR-30 gives SM "backup"; the plan says losing it loses every credential. |
| **K7** | **Restoring a pre-rotation database backup** after a rotation retired the old key. | The case no one writes down until it happens. |
| **K8** | **The config hash.** Cache's `application.yml` carries a broker password. The attacker has read access to the database only. Separately: two tenants stage byte-identical config. | OR-32's reason to exist. |

**The status quo** is what the tree does today, measured: a placement names a private-key *file path*
(`SshDockerPlacement.privateKeyFile`, `SshDockerPlacement.java:16`; `ssh.loadKeys(p.privateKeyFile().toString())`,
`SshjDockerTransport.java:304`), so the credential lives in a file on the DP host and there is no store; and the config
hash is unkeyed SHA-256, computed **on the customer host** by `sha256sum` over SSH (`SshjDockerTransport.java:53`) and
again in-process by `RenderedConfig.sha256` (`RenderedConfig.java:41-44`). Under the status quo, K1 to K3 and K5 to K7 do
not arise (there is nothing to boot, rotate or restore), K4 is "steal the key file", and K8 is a dictionary attack on
a bare SHA-256 of a file that contains the password.

---

## 2. Measured fact M7 (filled)

`I2`: each row is a fetched sentence or a local measurement, with its source and the date, 2026-10-05.
This section is the content of plan 010's M7 row; the plan file itself is not edited by T2.3 (the result is in the task's
report and the plan owner transcribes it).

### 2.1 Fetched

| Fact | Verbatim | Source |
|---|---|---|
| `GCM` is a standard cipher mode. | "Galois/Counter Mode, as defined in NIST Special Publication SP 800-38D." | `https://docs.oracle.com/en/java/javase/17/docs/specs/security/standard-names.html`, "Cipher Algorithm Modes" |
| `AES` is a standard algorithm, keys 128/192/256. | "AES is a 128-bit block cipher supporting keys of 128, 192, and 256 bits." | same page, "Cipher Algorithm Names" |
| `HmacSHA256` is a standard MAC name. | The entry lists "HmacSHA1 HmacSHA224 HmacSHA256 HmacSHA384 HmacSHA512 …", "as defined in RFC 2104". | same page, "Mac Algorithms" |
| The standard-names page says nothing about the GCM IV or tag length. | (The page's GCM entry only references SP 800-38D; no length is stated.) | same page. IV and tag length therefore come from the two rows below. |
| **IV uniqueness is a caller obligation.** | "Note that GCM mode has a uniqueness requirement on IVs used in encryption with a given key. When IVs are repeated for GCM encryption, such usages are subject to forgery attacks. Thus, after each encryption operation using GCM mode, callers should re-initialize the cipher objects with GCM parameters which have a different IV value." | `https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/crypto/Cipher.html`, class description |
| **AAD** goes in before the data, and a failed decrypt is a typed exception. | All AAD must be supplied by `updateAAD()` before `update`/`doFinal`; decryption throws `AEADBadTagException` if the tag does not match. | same page |
| **The mandatory transformation is `AES/GCM/NoPadding` at 128 bits only.** | The "every implementation of the Java platform is required to support" list contains `AES/GCM/NoPadding (128)`. | same page |
| `GCMParameterSpec` takes the **tag length in bits**. | `GCMParameterSpec(int tLen, byte[] src)`: "tLen - the authentication tag length (in bits)"; "src - the IV source buffer. The contents of the buffer are copied". | `https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/crypto/spec/GCMParameterSpec.html` |
| **Recommended IV length is 96 bits.** | "For IVs, it is recommended that implementations restrict support to the length of 96 bits, to promote interoperability, efficiency, and simplicity of design." (SP 800-38D, 5.2.1.1) | `https://nvlpubs.nist.gov/nistpubs/Legacy/SP/nistspecialpublication800-38d.pdf` (text extracted with `pdftotext`) |
| **Random IVs cap the invocations per key.** | "The total number of invocations of the authenticated encryption function shall not exceed 2^32, including all IV lengths and all instances of the authenticated encryption function with the given key." It applies to "the RBG-based construction, for IVs of any length." (SP 800-38D, 8.3; the source prints the exponent as `232`) | same PDF |
| **Tag lengths.** | "t may be any one of the following five values: 128, 120, 112, 104, or 96." (SP 800-38D, 5.2.1.2) | same PDF |

### 2.2 Measured locally

Command: `java M7.java`, `M7b.java`, `M7c.java` (scratch programs; the lines below are their output) on OpenJDK
`17.0.17+10` (Microsoft build), the JDK the reactor builds with.

| Measurement | Output |
|---|---|
| `AES/GCM/NoPadding` and `HmacSHA256` exist, both from `SunJCE`. | `GCM provider=SunJCE`, `HmacSHA256 len=32 provider=SunJCE` |
| A 256-bit AES key works here. | `maxAES=2147483647` (unlimited policy); encryption under a 32-byte key succeeds. |
| The provider-generated GCM IV is 12 bytes, the default tag is 128 bits. | `provider IV len=12 params tLen=128` |
| The JDK accepts tag lengths 96 to 128 only. | `tLen 64 Unsupported TLen value.  Must be one of {128, 120, 112, 104, 96}` |
| GCM ciphertext is plaintext plus a 16-byte tag. | 5-byte plaintext gives `ct.len=21`. So a wrapped 32-byte DEK is **48 bytes**. |
| **The JDK's reuse guard is per `Cipher` object, not per key.** Re-initialising the *same* `Cipher` with the same key and IV is refused; a *new* `Cipher` with the same key and IV is accepted and produces **identical ciphertext**. | `REINIT same key+iv: java.security.InvalidAlgorithmParameterException: Cannot reuse iv for GCM encryption`; `two fresh Cipher objects, same key+iv: both ok, identical ct=true` |
| Authentication binds the AAD. | Decrypting with a different AAD: `javax.crypto.AEADBadTagException: Tag mismatch!` |
| A wrong-size key is refused by the JDK. | `7-byte key: InvalidKeyException: Invalid AES key length: 7 bytes` |
| `HmacSHA256` output is 32 bytes (64 hex characters). | `HmacSHA256 len=32` |
| AES key wrap is available as `AESWrap`, `AES/KW/NoPadding`, `AES/KWP/NoPadding`. A 32-byte DEK wraps to 40 bytes. | `AESWrap available`, …, `KW wrapped len for 32-byte DEK=40` |
| **A JDK key object cannot be zeroed.** `SecretKeySpec.destroy()` throws, and the constructor copies its input, so zeroing the source does not zero the key. | `SecretKeySpec.destroy: javax.security.auth.DestroyFailedException`; `copy retained after zeroing source: true` |
| A 32-byte key as standard base64 is 44 characters ending in `=`. The standard decoder refuses a trailing newline and the URL-safe alphabet. | `b64 len=44 endsWith=A=`; `trailing newline: Input byte array has incorrect ending byte at 44`; `url alphabet in std decoder: Illegal base64 character 2d` |

### 2.3 What M7 settles for the design

- **Primitive:** `AES/GCM/NoPadding`, 256-bit keys, 96-bit random IVs, 128-bit tags; `HmacSHA256`. All from the JDK
  itself, so **no new dependency** (`I8`, §10). 256-bit keys are not in the JDK's mandatory list (only 128-bit GCM is), so the
  boot canary (§4.3) doubles as the self-test that the running JVM can do 256-bit GCM.
- **IV uniqueness is ours to guarantee**, because the JDK's guard only covers one `Cipher` object (measured). Tests
  must reuse an IV across two `Cipher` instances to prove the check fires (§9, note 2).
- **The 2^32 bound** applies to the key that wraps (the KEK), not to a DEK that encrypts one message (§3.2).
- **AAD must be fed before data** and a mismatch surfaces as `AEADBadTagException`, which the store maps to a
  classifier carrying no bytes (§3.3).
- **Zeroisation is partial** by the JDK's own design (§10, residuals).

M16 (relaxed binding of `SM_DP_MASTER_KEY` to `sm.dp.master-key`) is **not** filled here: it is T2.6/T6.2's row.
This design needs only that the provider read one string property; §4.1 makes that robust to either answer.

---

## 3. The key hierarchy

### 3.1 Options

| | **W0 status quo** | **W1 KEK + per-secret DEK, GCM-wrapped, AAD on both** | **W2 one key, encrypt directly** | **W3 DEK wrapped by AES key wrap** | **W4 KEK + per-tenant DEK** |
|---|---|---|---|---|---|
| What is stored | a key file path on the host | `{key_id, wrapped_dek, dek_iv, value_iv, ciphertext, aad_version}` per secret | `{key_id, iv, ciphertext}` per secret | as W1, but `wrapped_dek` from `AESWrap` and no `dek_iv` | `tenant_dek` table + per-secret `{value_iv, ciphertext}` |
| **K2 wrong key** | no boot check | canary (§4.3), at boot | canary, at boot | canary, at boot | canary, at boot |
| **K3 rotate** (1,000 secrets, 40 tenants) | edit the file | re-wrap 1,000 DEKs; **the value ciphertext is byte-identical before and after** (§5); a read mid-rotation sees one row either way | **decrypt and re-encrypt all 1,000 values**; the command handles every plaintext, and every ciphertext changes | as W1 | re-wrap **40** DEKs |
| **K4 stolen backup** | the file was never in the backup | ciphertext only | ciphertext only | ciphertext only | ciphertext only |
| **K5 tampered row** | n/a | the wrap *and* the value authenticate tenant, secret and purpose; either fails on its own | the value authenticates | **the wrap binds nothing** (no AAD); the value still authenticates, so the move is caught one step later | value authenticates; DEK is tenant-level |
| Blast radius of one leaked DEK | n/a | one secret | n/a (one key) | one secret | **a tenant's every secret** |
| Runtime grant needed to rotate | n/a | `UPDATE (key_id, wrapped_dek, dek_iv)` only; the value columns are **not writable by the runtime role** | `UPDATE` of the ciphertext columns | as W1 | as W1 on one more table |
| GCM invocations under the KEK (bound 2^32, §2.1) | n/a | one per `put`, one per re-wrap | one per `put`, one per re-encrypt | none (wrap is not GCM) | one per tenant |
| New objects | none | one table (`secret`) | one table | one table | two tables |

**Recommendation: W1.** It is the plan's recommendation and, run through the scenarios, the only option where rotation
(K3) never handles a plaintext and where the privilege that enables rotation can be column-scoped so it *cannot* touch
the value (a privilege, not a discipline). W2 fails that on K3. W3 gives up the one cheap defence the wrap can carry
(K5). W4 is a reasonable later optimisation (fewer wraps, a bigger blast radius per DEK), and nothing in W1 prevents it:
`key_id` and `aad_version` are already in the row.

### 3.2 The row, the IVs and the bound

- **One KEK per key id** (§4.1) wraps a **fresh 32-byte DEK per secret** (`SecureRandom`).
  The value is encrypted under the DEK with a fresh 96-bit random IV (`value_iv`), tag 128 bits.
- **A DEK encrypts exactly one message**, so IV uniqueness *under a DEK* holds trivially. The IV that matters is the
  **wrap IV** (`dek_iv`): the KEK performs one GCM invocation per `put` and per re-wrap. NIST's cap is 2^32 invocations per key
  with random IVs (§2.1), about 4.29 billion. At the scenario's 1,000 secrets that is more than four million full
  rotations of headroom. The design therefore does not need a counter or a deterministic IV construction. It does state
  the cap as a **rotation trigger**: a KEK that has wrapped on the order of a billion DEKs is rotated.
- **Lengths are fixed and the schema checks them** (T2.4's `V009`): `dek_iv` and `value_iv` 12 bytes; `wrapped_dek` 48
  bytes (32 + the 16-byte tag, measured §2.2); `ciphertext` at least 16 bytes and at most a stated cap (a private key
  is a few kilobytes; T2.4 picks the cap and records it, it is a value, not a design question).
- **A secret row is write-once for its value.** Changing a credential inserts a **new row with a new `secret_id`** and
  repoints the referrer. (§11, OQ-4 asks what happens to the superseded row.)

### 3.3 The AAD

The same additional-authenticated-data bytes are fed to the wrap and to the value encryption:

`AAD = aad_version (1 byte) || tenant_id (16 bytes) || secret_id (16 bytes) || len(purpose) (1 byte) || purpose (UTF-8 name of SecretPurpose)`

Fixed-width and length-prefixed fields, so there is no ambiguity between fields. Three consequences:

1. A ciphertext moved to another tenant, another secret id or another purpose fails with `AEADBadTagException` (K5).
2. **`key_id` is deliberately not in the AAD.** A re-wrap changes `key_id`, and the value ciphertext must stay
   byte-identical (K3). `key_id` is a selector, not an authenticated claim: a lie about it picks the wrong key and fails
   authentication anyway.
3. `aad_version` exists so the AAD layout can change without a flag day; a row is decrypted with the layout it names.

The store catches `AEADBadTagException` and rethrows a classifier (`secret.authentication.failed`) carrying **neither
ciphertext nor plaintext nor the AAD**; the cause is not chained, because a chained cause's message is a leak path.

**What the AAD does not give.** It does not stop a *replay* of an older ciphertext for the same `secret_id`. A row is
write-once for its value (§3.2), and the runtime role has no `UPDATE` on the value columns (§8), so a replay needs the
owner credential or a restore. That is the same threat as K7, not a new one.

---

## 4. The master key: source, id, boot

### 4.1 Source and shape (ruled: environment)

`OR-30` says the master key comes from the environment. Recommended shape, matching the plan:

- `SM_DP_MASTER_KEY`: standard base64 of exactly 32 random bytes (`openssl rand -base64 32` produces 44 characters,
  measured §2.2 to decode strictly, so the provider does **not** trim; a pasted trailing newline is a loud failure, not
  a silent success).
- `SM_DP_MASTER_KEY_ID`: the operator's label for it (`^[A-Za-z0-9._-]{1,64}$`). It is a selector and not a secret, and
  it appears in log lines and error messages.
- During a rotation only: `SM_DP_MASTER_KEY_PREVIOUS` and `SM_DP_MASTER_KEY_PREVIOUS_ID` (§5).

**The provider reads the value as a plain string and decodes it itself.** It is not bound into a typed or converted
`@ConfigurationProperties` field, so there is no framework conversion failure whose own message could echo the value;
every refusal is the provider's own `IllegalStateException`. It names the **variable** and the **rule**
("must be standard base64 of 32 bytes") and prints **no character of the value and not the length it found**.
The actuator `env`/`configprops` endpoints sanitise every value by default ("by default values are always fully sanitized
(replaced by `******`)", Spring Boot 3.5 reference, fetched 2026-10-05) and expose only `health` over the web by default,
so the key is not served there unless someone turns both defaults off. T2.6 keeps the defaults.

**Id: explicit or derived?**

| | **Explicit id (recommended; the plan's)** | **Id derived from the key** (a fingerprint) |
|---|---|---|
| K2, right id, wrong key | caught by the canary | not possible to mislabel |
| K2, wrong id, right key | caught: no canary for that id, §4.3 | the id would change silently |
| Stores key-derived data in the database | no | yes (a hash of the key, in every row) |
| Runbook can say "rotate from `2026-q3` to `2026-q4`" | yes | no |

A derived id removes one operator error and adds a key-dependent value to every row. The canary already catches the error,
so the recommendation keeps the explicit id.

### 4.2 What the application refuses at boot

Absent, empty, not base64, or not 32 bytes: the context fails to start with a message naming the variable.
`SM_DP_MASTER_KEY_ID` absent or malformed, the same. Current and previous with the same id, the same. A previous key
set without its id, the same. All before the worker or the recovery sweep exists (the plan's
`aMissingMalformedOrShortMasterKeyRefusesStartupWithoutEchoingIt`).

### 4.3 The canary: options, then the rule

| | **(a) canary row per KEK id (recommended; the plan's)** | **(b) decrypt a real secret at boot** | **(c) store a key check value (a hash of the key)** | **(d) no check; fail on first use (status quo behaviour)** |
|---|---|---|---|---|
| **K1** fresh install, no secrets yet | works: the canary is the only thing to test | **cannot work**: nothing to decrypt, so a typo'd key on day one is accepted | works | works |
| **K2** wrong key, 1,000 secrets | refused at boot | refused, **but the read is a cross-tenant read** at boot (a third `I5` exception) | refused | boots; the first apply fails at SSH time; the plan's Review Focus 5 is violated |
| Reads tenant data at boot | no: the row is install-level, no `tenant_id` (B4) | yes | no | no |
| Puts key-derived data in the database | no (a real encryption) | no | **yes** | no |
| Exercises unwrap and value decrypt and 256-bit GCM on this JVM | yes | yes | no | no |

**The canary is an ordinary envelope** (a wrapped DEK, a value encryption of a fixed known plaintext) in
`sm_secret.kek_canary(key_id, wrapped_dek, dek_iv, value_iv, ciphertext)`, no `tenant_id`, purpose `CANARY`. Its AAD is
`aad_version || purpose || key_id` (there is no tenant or secret id), and it is never re-wrapped: a rotation mints a new canary
for the new id and leaves the old one. It is never a `SecretStore` entry.

**The decision table** (the check runs as `sm_dp_runtime`, before the worker, reading only `kek_canary`):

| State of `kek_canary` | Previous key configured? | Boot does |
|---|---|---|
| **empty** (first boot ever) | n/a | mint a canary for the current id. `INSERT … ON CONFLICT (key_id) DO NOTHING`, then **re-read and verify**, so two racing boots cannot disagree. |
| has a row for the current id | any | decrypt it with the current key. `AEADBadTagException` means refuse: "`SM_DP_MASTER_KEY` is not the key that `SM_DP_MASTER_KEY_ID` names". |
| non-empty, **no row for the current id** | **yes, and its canary verifies under the previous key** | this is a **rotation starting**: mint the current id's canary (below). |
| non-empty, no row for the current id | no, or its canary is absent or does not verify | **refuse**: "no canary for `<id>`; if rotating, set `SM_DP_MASTER_KEY_PREVIOUS`". |
| any, and a previous key is configured whose canary is absent or fails | n/a | **refuse**, naming `SM_DP_MASTER_KEY_PREVIOUS`. |

The two properties this buys: **an application that has been given the wrong key never writes a canary for it**
(the one case where it mints on a non-empty install is when it first proves the previous key is the real old one), and
**an empty-canary-table install is by construction a fresh install**, because every KEK ever used has a canary. That second
property is what lets the check avoid reading `sm_secret.secret` to ask "is the install empty?", which would be a
cross-tenant read.

The alternative to the app minting is an explicit `KeyRotationMain init` the operator runs once, so that **boot is
read-only**. The delta: boot needs no `INSERT` grant on `kek_canary`, and every demo, test and fresh install gains one
required step. The recommendation keeps self-minting under the guard above, since the guard already stops the dangerous
cases and the extra step is exactly the kind an operator forgets.

---

## 5. Rotation

### 5.1 Options

| | **R1 operator command re-wraps all DEKs (recommended; the plan's)** | **R2 lazy re-wrap on read** | **R3 offline: stop, re-encrypt, start** | **R0 status quo** |
|---|---|---|---|---|
| **K3**: an apply resumes mid-rotation | decrypts under whichever id its row names; no window (§5.2) | same | **the application is down**; the resume simply waits | replace the file; resumed applies read whichever was there |
| Can the old key ever be retired? | **yes**: the command reports what is left under it | **no**: a secret nobody reads is never re-wrapped, so the count never reaches zero | yes | n/a |
| Needs a write grant on the hot read path | no | **yes**: every `resolve` may `UPDATE` | no | n/a |
| Plaintext handled by the rotating process | none (§3.1 W1) | none | all, if W2 | n/a |

**Recommendation: R1.** R2 never finishes, so the old key can never be retired, and it turns every read into a write.
R3 is only needed with W2.

### 5.2 The procedure (R1), and why there is no window

A KEK id has three possible roles: **current** (wraps new DEKs), **previous** (loaded for unwrap only), or neither.

1. **Start.** The operator sets `SM_DP_MASTER_KEY`/`_ID` to the **new** key, and `SM_DP_MASTER_KEY_PREVIOUS`/`_PREVIOUS_ID`
   to the **old** one, and restarts the application. Boot verifies the old canary under the previous key, mints the new
   canary (§4.3), and from now on wraps new secrets under the new id. Old rows are still readable through the previous key.
2. **Re-wrap.** The operator runs `KeyRotationMain` (a standalone `main`, the runtime DP credential and both keys from the
   environment, **no Spring context**, so it can never start a second worker). For each tenant it takes batches of rows
   `WHERE tenant_id = ? AND key_id = <old> … FOR UPDATE`, unwraps with the previous key, wraps with the new key and a
   fresh IV, and writes **one `UPDATE` per row that sets `key_id`, `wrapped_dek` and `dek_iv` together, guarded by
   `AND key_id = <old>`**. One statement means a concurrent reader (READ COMMITTED) sees the row entirely old or entirely
   new, each a consistent triple. The guard makes two concurrent commands, or a re-run after a crash, harmless: the second
   updates zero rows.
3. **Retire.** When the report says nothing remains under the old id **for every tenant that holds secrets** (§5.4),
   the operator removes the previous key from the environment and restarts. The retired key is **kept in custody**
   per §6 (K7).

**K3, run through it.** The apply that resumes between steps 1 and 2 reads a row that names the old id and unwraps with the
previous key. Between 2 and 3 it reads a row that names either id, and the application holds both. The one thing that
would open a window is a running application that holds only the *old* key while the command moves rows to the *new* one:
the procedure forbids it by making step 1 (give the application both keys) precede step 2, and the boot rule refuses to
start with the new key as current and no previous (§4.3).

**Rollback is the same procedure with the roles swapped.** If the operator wants the old key back after step 1, the
new key's id stays loadable as `PREVIOUS`, because rows (including every secret written since step 1) may name it.
Setting the old key current with *no* previous would **boot** (its canary exists), while every row written under the
new id is unreadable. The boot rule cannot see rows, so **the runbook states the rule: never drop a key id until the report
says nothing remains under it**, and the store fails closed, naming the key id and no secret, if a row names an id that is
not loaded (`secret.key.unavailable`).

### 5.3 What the command reports

Per tenant: rows re-wrapped, rows still under the old id. A total. It exits non-zero if any row it could see remains. It
prints counts and key ids only: no key, no secret id, no tenant data beyond the tenant id it was iterating. Batches are
tenant-bound: **it never reads a DEK without its tenant** (the plan's own rule), and the run stops on the first
authentication failure, naming the tenant and key id, never the bytes.

### 5.4 ⚠️ What "every tenant" means, and the gap in the plan's wording

The plan says the command iterates the second tenant list ([`OR-49`](../DECISIONS.md)) and so reaches every tenant. It
does not. That list is `SELECT DISTINCT tenant_id FROM sm_data.instance_state WHERE lifecycle = 'active'`: a tenant with
**at least one active instance**. But the first secret a tenant ever stores is its target's SSH key, written by
`TargetConnectionMain` (T2.7) **before** any instance is declared. A tenant whose instances are all `pending`,
`abandoned` or undeclared has secrets and is **not on the list**.

The consequence if nothing changes: the command re-wraps the listed tenants, its report says "none remain" for them,
the operator retires the old key, and the unlisted tenants' secrets become **permanently unreadable**, with nothing
at boot to catch it (§4.3 cannot see rows).

| | **(a) a third cross-tenant read** | **(b) widen OR-49's predicate to "has an active instance or holds a secret"** | **(c) the operator names the stragglers** | **(d) as (c), plus the operator's own SQL as the retire gate** |
|---|---|---|---|---|
| What changes | `SELECT key_id, count(*) FROM sm_secret.secret GROUP BY key_id`, run by the application | the second list's text changes | the command accepts `--tenant <uuid>` (repeatable), in addition to the second list | (c), and the runbook's step 3 is a query the DBA runs with the **owner** credential: `SELECT key_id, count(*) FROM sm_secret.secret GROUP BY key_id` |
| New application read | **yes**, a third, returns no tenant id | no new statement, but **re-spells the second list**, which the plan forbids without a ruling | none | none |
| Needs an owner ruling | **yes** (Global Constraints: "No task adds a third without an owner ruling") | **yes** ("no task re-spells the second") | **no**: the operator names ids as OR-49's own commands take them (`TargetConnectionMain` already does) | no |
| K3, with an unlisted tenant | the count shows leftovers; but nothing re-wraps them unless (b) or (c) also exists | works | works if the operator knows to name it | works, and the DBA query is how the operator **knows** |
| Failure if the operator forgets | n/a | n/a | the old key is dropped and a tenant is stranded | the same, but the runbook's gate is the DBA query, so skipping it is a visible skipped step |

**This design does not decide it** (§11, OQ-1). What T2.4 builds meanwhile needs no ruling: `KeyRotationMain` iterates the
second list **and** accepts `--tenant`, and **it must not print an unqualified "none remain"**: its report says "covered
N tenants (the second list plus K named); tenants outside this set are NOT covered".

---

## 6. Backup, loss and restore

1. **The KEK is backed up by the operator, outside SM.** SM never stores the KEK, not even encrypted: `OR-30` keeps it out of the
   database. The install runbook must carry: custody of every KEK id **separately from the database backups** (so one stolen
   backup set is ciphertext only: K4), a record of which id is current, and who may read it.
2. **A database backup without the KEK is ciphertext only** (K4). With only the backup, an attacker holds wrapped DEKs and
   AES-256-GCM ciphertext. SM's own contribution to that: the AAD (§3.3) and the keyed hash (§7), so the backup also does not
   carry a dictionary-attackable digest of the config file (K8).
3. **Retired keys are kept as long as any backup names them (K7).** After a rotation retires `2026-q3`, a backup taken
   before the rotation still has rows under `2026-q3`. Restoring it needs that key loaded as `PREVIOUS`, and then the
   operator re-runs the re-wrap. The runbook states the rule: **retain a retired KEK for the retention life of the oldest
   database backup that names it.** The converse is a feature: destroying a retired KEK makes every older backup's
   credentials permanently unreadable (crypto-shredding), and the operator may choose that.
4. **Losing every copy of the KEK loses every credential, and the only recovery is re-entering them**: `TargetConnectionMain`
   for each target, `setConfigSecret` for each `secretRef`. Stated, not softened. Recovery needs a new key and an empty
   `kek_canary`, because the boot rule would otherwise refuse it (§4.3). **The runtime role has no `DELETE`** (§8), so the
   purge is a DBA action with the owner credential, consistent with the "owner credential never reaches the application".
   The runbook gives the exact statements.
5. **The keyed config hash makes loss worse than "re-enter the credentials".** `CONFIG_HASH_KEY` is itself a secret (§7), so its
   loss makes **every stored `hmacsha256v<n>:` hash unverifiable**. The recovery purge therefore **does not delete
   `CONFIG_HASH_KEY` rows**: they stay as unreadable tombstones naming a key id that is no longer loadable, so the next
   key is minted as **version `max + 1`** and a version number is **never reused**. A reused number with a new key would make
   every old hash look like a mismatch, and the ladder would report `CONFIG_DIVERGED` for instances that did not change.
   An old version whose key is not loadable must produce a **distinct outcome** (`hash.key.unavailable`), not "mismatch"
   (note 5, §9). After loss, every instance reads as unverifiable until a new generation is applied and re-baselined.

---

## 7. The OR-32 keyed hash

### 7.1 Where the key lives

| | **(a) a second environment secret** `SM_DP_CONFIG_HASH_KEY` + id | **(b) one more envelope-encrypted secret, purpose `CONFIG_HASH_KEY` (recommended; the plan's)** | **(c) derived from the KEK** |
|---|---|---|---|
| Roots of trust | two environment secrets to provision, back up and rotate | **one** | one |
| Table of what an attacker with the **database only** (K8) can do | nothing: no key | nothing: the key is a ciphertext | nothing |
| Attacker with the **KEK** | the KEK does not open the hash key; it needs the second variable too | opens it, **but** the KEK already opens every SSH key and every config secret, so the hash key adds nothing the attacker lacks | opens it |
| **K3**: a KEK rotation | the hash key is unaffected, but the second variable needs its own procedure | the hash key's **DEK** is re-wrapped by §5's command; the key and every hash stay as they were | **every stored hash becomes invalid at once**, unless every old KEK is kept loaded forever |
| Per-tenant keys | would need a table anyway | natural: one row per tenant (§7.2) | derivable per tenant |
| New operator burden | a second secret, a second backup | none | none |

**Recommendation: (b).** One root of trust, and rotation reuses §5. (a) adds an operator secret without removing any
attacker the KEK already stops. (c) fails K3: a derived key moves when the KEK does, so rotating the KEK would invalidate
every hash a resumed apply is bound to. (c) is not relied on, so what a JDK 17 key-derivation API does or does not offer is
not asserted either way (the plan's m7).

### 7.2 Scope, spelling, computation

- **One key per tenant** (the plan's m5). `put(tenantId, …)` binds the tenant into the AAD, so a key is already a tenant's.
  **K8, second half:** two tenants staging identical bytes produce **different** hashes, so no tenant can tell that another
  staged the same file. With one install-wide key they would be equal. The hasher is addressed by tenant,
  `ConfigHasher.hash(TenantId, byte[])` (T5.1).
- **Key material:** 32 bytes from `SecureRandom`, stored as base64 text in the `char[]` that `SecretStore.put` takes
  (44 characters), decoded to `byte[]` for the HMAC, zeroised after. The 32-byte length is a design choice that matches the
  digest length. M7 measured `HmacSHA256` output at 32 bytes and, with it, the 64-hex-character tail below.
- **Spelling: `hmacsha256v<keyVersion>:<64 lowercase hex>`**, `keyVersion` a positive decimal, per tenant, never reused (§6.5).
  It fits the model grammar `^[a-z0-9]+:[0-9a-f]{16,128}$` (`ValueGrammar.java:35`) and V002's `resolution_is_evidence`
  element predicate (`V002__apply_request.sql:105`) **unchanged**. No migration widens a grammar. A spelling with `-` or a
  second `:` (e.g. `hmac-sha256:<id>:<hex>`) fails both.
- **Where the version lives.** The row of a `CONFIG_HASH_KEY` secret carries a `key_version` column, non-null exactly for
  that purpose (a CHECK), with `UNIQUE (tenant_id, key_version)`. That one column lets the hasher find "the latest
  version" and "version *n*" without parsing anything, and gives the lazy mint (below) a conflict to race on.
- **Minted lazily**, on the first call that needs a key for a tenant. The plan says "on a tenant's first stage"; the first
  caller may in fact be the adapter's read-back during a health poll, a path that has no operator behind it, so the mint is
  **on first use by whichever caller**, as a system action (`created_by` null, permitted by a CHECK for this purpose only).
  Two callers racing: `INSERT … ON CONFLICT (tenant_id, key_version) DO NOTHING`, then re-read the winner; the loser's
  key is discarded.
- **Computed in the DP, never on the customer host.** The key cannot leave the DP, so the transport returns **bytes** and
  the adapter hashes them (T5.1). A remote `sha256sum` is exactly the unkeyed digest OR-32 forbids, and an HMAC on the host
  would need the key on the host.
- **Not cached in v1.** `HashKeySource` resolves the key per hash call, which fits the store's "decrypt for ONE use, close
  the result" rule (the plan's `SecretResolver`). The cost, one read and two GCM operations per hash, has not been measured
  and this document does not claim it is negligible; T5.1's `HashKeySource` interface is the seam that lets a
  bounded per-tenant cache be added later without touching a caller, if T7.2's poller shows it matters.

### 7.3 Verification across rotation, and rotation of the hash key itself

- **KEK rotation** changes nothing about the hash key or any hash (§7.1 (b), K3).
- **The verifier keeps every key version**: a superset of the plan's "every version still named by a non-terminal journal
  row". Versions are 32-byte secrets; pruning one needs a scan of every journal and generation row that spells it, so v1
  never prunes, and a later optimisation may.
- **Rotating the hash key itself** (bumping the version) is **not built in v1** (OQ-5). A version bump would also have to
  re-hash every stored baseline, and a baseline is a hash of *resolved* bytes, so it would mean re-rendering with every
  secret resolved. The spelling carries the version, so adding the procedure later changes no stored byte. Until then, a
  suspected hash-key compromise is answered by rotating the KEK (which protects the key at rest) and re-staging.

---

## 8. Schema and grants

**Phase 0** (`sm_data_phase0.sql`) gains `CREATE SCHEMA sm_secret AUTHORIZATION sm_dp_owner; GRANT USAGE ON SCHEMA sm_secret
TO sm_dp_runtime;`, because a migrator cannot create a schema (measured for `sm_control`, the comment at the head of `cp-core`'s `V001__scope.sql`).
The **tables** are created by `sm_data` `V009` through `SmDataMigrationMain`, schema-qualified, as `sm_dp_owner`.

**No default read-baseline.** `sm_data`'s `V001` runs `ALTER DEFAULT PRIVILEGES … IN SCHEMA sm_data GRANT SELECT ON TABLES
TO sm_dp_runtime` (`V001__sm_data_defaults.sql:13-14`). That statement is scoped to `sm_data`, so `sm_secret` gets none,
and **it must not**: a read-baseline on a table that holds wrapped keys is the wrong default. Every grant below is
explicit and per table.

| Table | `sm_dp_runtime` gets | Why |
|---|---|---|
| `sm_secret.secret` | `SELECT`; `INSERT (secret_id, tenant_id, purpose, key_id, wrapped_dek, dek_iv, value_iv, ciphertext, aad_version, key_version, created_at, created_by)`; **`UPDATE (key_id, wrapped_dek, dek_iv)`** | Rotation (§5.2) changes exactly those three columns, **in one statement**. The value columns (`value_iv`, `ciphertext`), the identity (`tenant_id`, `secret_id`, `purpose`) and `aad_version` are **not writable by the runtime role**, so "the ciphertext of the value never changes" is a privilege and not a promise. |
| `sm_secret.kek_canary` | `SELECT`, `INSERT` | Boot mints (§4.3); nothing updates or deletes a canary. |
| both | **no `DELETE`** | The runtime never needs it. Purging is the owner's act (§6.4). |

Defence in depth for the same property: a `BEFORE UPDATE` trigger on `secret` may refuse any change to a column other than
those three (T2.4's call; the privilege already blocks the runtime role, the trigger would also bind the owner).

**This design never uses the migrator or the owner credential at runtime.** `KeyRotationMain`, the boot canary and the
hasher's lazy mint all run as `sm_dp_runtime` with the grants above. Nothing in this design requires giving the running
application, or an operator command that runs beside it, a schema-owner or Flyway credential.

---

## 9. Notes for the tasks that build this

1. **T2.4's test `theRuntimeRoleCannotDeleteOrReadAcrossTenants` overpromises.** The runtime role holds a table-level `SELECT`,
   so the database does **not** stop it reading another tenant's row (nothing in `sm_data` or `sm_control` uses row-level
   security; measured: no migration or bootstrap mentions it). What holds is the repository (`WHERE tenant_id = ? AND
   secret_id = ?`, `I5`) and the AAD (a row read for the wrong tenant fails authentication). The test should assert
   **no `DELETE`, no write to the value columns, and that `resolve` for the wrong tenant fails**, not a database privilege that
   does not exist. A genuine database-level read fence would be row-level security with a per-session tenant setting, a
   new mechanism the plan does not contemplate; it is not recommended here.
2. **`everyEncryptionUsesAFreshIv`'s mutation** ("Reuse one IV → red") must reuse the IV **across two `Cipher` instances**.
   Within one instance the JDK itself refuses (measured, §2.2), so a mutation that reuses within an instance is red for the
   wrong reason and would not prove the store's check.
3. **Bind the key as a string** (§4.1). If T2.6's M16 measurement shows the environment variable binds to
   `sm.dp.master-key`, the provider reads that one property as a `String`. No typed `@ConfigurationProperties` field, and
   no record with a generated `toString`, may hold it (the `MeshProperties`/`SmDataProperties.Credentials` precedent).
4. **The check must run before the worker starts.** The canary check is a bean the `ApplyWorker` and the recovery sweep
   depend on, so an authentication failure aborts context refresh and nothing starts. T2.4's
   `aWrongMasterKeyFailsTheCanaryBeforeTheWorkerStarts` is the test.
5. **`ConfigHasher.verifies` needs a third outcome.** `boolean` cannot say "I could not check, the key is gone" (§6.5), and
   mapping that to `false` makes the ladder report `CONFIG_DIVERGED` for an instance nobody changed. T5.1 owns the spelling
   (a typed exception or a small result type). The requirement is that the ladder can tell *mismatch* from *unverifiable*.
6. **T2.7's `TargetConnectionMain` and T2.4's `KeyRotationMain` are the two commands that must learn one more thing:**
   `--tenant` for stragglers (§5.4), and that both build their `SecretStore` by hand against the runtime credential, with
   the master key from the environment, **without a Spring context**.
7. **Where the zeroisation guarantee ends.** `SecretMaterial` zeroes the buffers it owns. It cannot reach a `String` the
   environment already made for the KEK, or the copy a `SecretKeySpec` takes (measured, §2.2), and it ends wherever sshj needs
   the private key as a `String` rather than a `char[]` (M8, T6.1, not measured here). §10 lists this as a residual.

---

## 10. Invariant audit

| Invariant | Holds because |
|---|---|
| **`I1`** no MI development | Nothing here touches an MI. The keyed hash is computed by the DP over bytes the transport already reads; no MI endpoint or emission is needed. |
| **`I5`** server-side tenant scope | Every `sm_secret` query is bind-parameterised and tenant-scoped; the AAD binds the tenant a second time. This design adds **no** cross-tenant read: the canary has no `tenant_id` (B4), the re-wrap iterates OR-49's list per tenant, and the one gap is routed to the owner (§5.4, OQ-1) instead of closed with a third read. |
| **`I6`** no secrets in config storage | Values exist only as ciphertext in `sm_secret` and transiently in rendered bytes. The §3.3 classifier carries no bytes; §4.1 never echoes the key or its length; `secret.key.unavailable` names a key id (a label), never a secret. The keyed hash still depends on resolved values, which is OR-32's flagged reading, and this design says so rather than hiding it. |
| **`I7`** plane boundary | `sm_secret` is DP-owned; the CP never touches it; no cross-schema FK or join is created. The canary and the hash key live wholly in the DP. |
| **`I8`** OSS, commercially free | **No new dependency**: AES-GCM, AES key wrap and HmacSHA256 are `SunJCE` in the JDK the reactor already builds with (measured, §2.2). BouncyCastle or any KMS client is not needed. |
| Flyway and schema-owner credentials stay out of the application | §8: the schema by Phase 0, the tables by `SmDataMigrationMain`; the boot check, the lazy mint and `KeyRotationMain` use `sm_dp_runtime` with column-scoped grants, and a purge is the owner's separate act. |

**Residuals, stated and not softened.**

- **An application compromise is a full decryption.** The process holds the KEK in memory and the runtime role can read every
  row. The envelope protects the **backup** and a **database-only** attacker (K4, K8), not a compromised application.
  It still *limits* the blast radius to what the runtime role can reach: it cannot alter the schema, the triggers or the
  value columns.
- **The KEK is in the process environment and in heap for the life of the process.** The JDK cannot zero a key object
  (`SecretKeySpec.destroy()` throws, measured). Wherever the platform records a container's or a process's environment,
  whoever can read that record can read the key. That is not measured here; it is why OQ-2 exists.
- **A superseded secret row stays** until the owner purges it (§3.2, OQ-4).

---

## 11. Owner questions this design leaves

None of these is decided here. For each, the design states what stays unchanged under every answer.

✅ **All five were ruled by the owner on 2026-10-05 and are registered as [`OR-85`](../DECISIONS.md)** (the owner decides
on behalf of the architect group, `OR-69`). The questions below are kept as asked; the rulings are:

| Question | Ruling (`OR-85`) |
|---|---|
| OQ-1 | **(d)** — the re-wrap command reports the tenants `OR-49`'s list could not reach, the operator adds them with `--tenant`, and the old key is retired only after a database check shows nothing still uses it. No new cross-tenant read. |
| OQ-2 | **Yes** — `SM_DP_MASTER_KEY` or `SM_DP_MASTER_KEY_FILE` (a mounted secret file), identical validation. §4.1/§4.2's "environment" includes the `_FILE` form. |
| OQ-3 | **Out of v1** — no KMS/HSM/Vault; `MasterKeyProvider` stays the seam. |
| OQ-4 | **(a) plus a runbook purge** — superseded rows are kept (re-wrapped on every rotation); the runbook gives an owner-run SQL purge; the runtime role gets no `DELETE`. |
| OQ-5 | **Both confirmed** — the `OR-32` hash key is per tenant; v1 builds no hash-key rotation. |

Implemented by plan 010 T2.4 (secret store, `V009`, `KeyRotationMain`).

**OQ-1. How does key rotation reach tenants that have secrets but no active instance? (§5.4)**
OR-49's list is "has an active instance"; a tenant's first secret precedes its first instance. Four routes are priced in §5.4:
(a) a third cross-tenant read, (b) re-spelling the second list, (c) the operator names the stragglers with `--tenant`, (d) (c)
plus the DBA's own query as the retire gate. **Unchanged under every answer:** the per-tenant, tenant-bound re-wrap; the
single `UPDATE` of three columns; the unqualified "none remain" is never printed. **Recommended, as a default T2.4 can build
without a ruling:** (d). It adds no read and no exception, and it turns the dangerous step (dropping the old key) into a
step with a visible gate.

**OQ-2. Is a `_FILE` form of the master key in scope for v1, alongside the environment variable? (§4.1, §10)**
`SM_DP_MASTER_KEY_FILE` would point at a file (for example a mounted secret) holding the same base64 text, with identical
validation. **Delta:** the key then need not be in the process environment, which matters where the platform records
environment variables; the cost is one more input to validate and a file-permission failure mode. OR-30 says "from the
environment", and a file path *is* supplied by the environment, but whether the owner reads it that way is the question.
**Unchanged:** the validation rules, the id, the canary, the rotation procedure.

**OQ-3. Is an external key service (KMS, HSM, Vault) out of v1, with `MasterKeyProvider` as the seam?** The plan already
has the interface. **Delta if "out of v1":** none. **If "in":** a new dependency and a new failure mode at boot, each needing an
`I8` licence check. **Unchanged:** everything below the provider.

**OQ-4. What happens to a superseded secret row?** A credential change inserts a new row (§3.2). The runtime role has no
`DELETE`. Options: (a) rows accumulate until the owner purges them by SQL (what the plan implies); (b) grant the runtime role
`DELETE` on rows no referrer points to, which needs a referrer check and gives the running application a destructive
privilege; (c) a retention job run with the owner credential. **Delta:** (a) leaves an old SSH key as ciphertext under a
possibly-retired KEK for as long as nobody purges it; (b) removes that and adds a destructive grant; (c) adds an owner-side job.
**Unchanged:** the envelope, the AAD, rotation.

**OQ-5. Confirm two things the plan chose and the owner has not ruled: (i) the hash key is per tenant** (the plan's m5; OR-32
says only "a DP-held key"), **and (ii) v1 builds no hash-key rotation** (§7.3). **Delta of install-wide for (i):** one key and
one row, and identical bytes in two tenants produce identical hashes (K8). **Delta of building rotation for (ii):** a procedure
that re-renders every instance's baseline with every secret resolved. **Unchanged:** the spelling, the grammar, the
location in `sm_secret`, KEK rotation.

---

## 12. Decisions (drafted as K-1 to K-4; ids allocated at integration, registered in `decisions.md`)

### `BE-D28` (was draft K-1): DP secrets are two-level envelopes with an AAD that binds tenant, secret and purpose

A secret is encrypted with `AES/GCM/NoPadding` (256-bit key, 96-bit random IV, 128-bit tag) under a fresh per-secret data key,
and that key is wrapped by the install's key-encryption key the same way. Both operations feed the same AAD
(`aad_version || tenant_id || secret_id || len || purpose`); `key_id` is not in the AAD. The row is
`{key_id, wrapped_dek, dek_iv, value_iv, ciphertext, aad_version}`. A secret's value is write-once. The runtime role holds
`UPDATE` on `key_id`, `wrapped_dek`, `dek_iv` only, and no `DELETE`. **Rationale:** rotation never handles a plaintext,
and the one privilege rotation needs cannot touch a value (§3, K3, K5). **Status: PROPOSED.** Implements `OR-30`.

### `BE-D29` (was draft K-2): the master key is validated at boot against a canary, and the canary is minted only on a fresh install or a proven rotation

`SM_DP_MASTER_KEY` (standard base64, 32 bytes) and its id come from the environment, are read as strings and never echoed.
A canary envelope per key id lives in `sm_secret.kek_canary` with no tenant. Boot mints a canary only when the canary table
is empty or when a previous key's canary verifies; otherwise a missing or failing canary refuses to start, before the
worker. **Rationale:** a wrong key must fail at boot and must never be able to establish itself as the right one (§4, K1,
K2). **Status: PROPOSED.** Implements `OR-30`. **Flags** OQ-2 and OQ-3.

### `BE-D30` (was draft K-3): rotation re-wraps data keys with an operator command that holds both keys, and never drops a key it cannot prove empty

A rotation gives the application the new key as current and the old as previous **before** any row moves, then
`KeyRotationMain` re-wraps per tenant in batches, one `UPDATE` per row, guarded by the old id. It reports counts and never an
unqualified "none remain". A retired key is kept as long as any backup names it. **Rationale:** §5, K3, K7. **Status:
PROPOSED.** Implements `OR-30` and relies on `OR-49`. **Flags** OQ-1 (the list does not reach every tenant with secrets).

### `BE-D31` (was draft K-4): the config hash key is a per-tenant envelope secret, versioned, never pruned, and the hash is computed in the DP

`CONFIG_HASH_KEY` secrets carry a `key_version`, unique per tenant, never reused. The hash is spelt
`hmacsha256v<n>:<64 hex>`, which fits the existing grammar and CHECK unchanged. It is computed by the adapter from bytes
the transport returns, never on the customer host. A version whose key is not loadable verifies as **unverifiable**, a
distinct outcome from a mismatch. The key is minted on first use by whichever caller. **Rationale:** §7, K3, K8.
**Status: PROPOSED.** Implements `OR-32`, which stays flagged for the architect group because it names `I6`.
**Flags** OQ-5.
