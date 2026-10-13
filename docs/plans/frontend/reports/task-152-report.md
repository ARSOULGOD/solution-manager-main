# FE-152 · The File Mesh source component — Report

Fourth task of [plan 129](../129-filemesh-source-sink-connectors.md) (design:
[`filemesh-source-sink-connectors.md`](../../../design/dynamic-ui/filemesh-source-sink-connectors.md) §3, §11).
Replaces the synthetic `COMP_SOURCE_FTP@FILETOFILE` component (`FILE_MESH_MANAGER_1`, `"value_5"`, …) with the
real `COMP_SOURCE`, authored from the supplied source `application-operator.yml`.

---

## 1 · What was built

`COMP_SOURCE` — `role: source`, `configPath: file.source`, **50 fields: 49 real config keys + the `connectorType`
discriminator.** The subtree tallies were checked programmatically against the YAML, not by eye:

| Subtree | Fields | | Subtree | Fields |
|---|---:|---|---|---:|
| `general` | 11 | | `directory_wildcard` | 2 |
| `command_center` | 9 | | `solace_out` | 2 |
| `gcs` | 6 (3 under `retrySettings`) | | `scheduler` | 1 |
| `sftp_settings` | 6 | | `directory_replication` | 1 |
| `ftp_settings` | 6 | | `file_to_event` | 5 |

The sample has 50 config keys; the 50th is the duplicate lowercase `useoutputdestinationsolacecredentials`,
deliberately **not** authored (spec §11, Q-B — Spring's relaxed binding treats it as the same property as the
camelCase one). `authoring` records `reviewedRows: 49`, `totalRows: 50` and says why.

The package header changes to `appliesTo: ">=2.2 <3.0"` (the sample's credentials path names connector 2.2.0).

## 2 · Design decisions taken while authoring

**One `CONNECTOR` section holds the selector and all three branches.** Found by reading `SectionCard`: a section
whose fields are all hidden still renders as a card reading *"No fields apply here with the current
selections."* Three separate GCS / SFTP / FTP sections would therefore show the operator two empty cards beside the
active one. Putting the selector and every branch in one section means it is never empty and exactly one branch is
visible in it. No engine change — this is only how the package groups its fields. A test pins it.

**A `default` is authored only for connector tunables the sample shows; never for an environment-specific
identifier.** `general.max_files_allowed: 99999` gets a default; `adapter_id`, hosts, users, credential paths,
`projectId`, base publish topics, `jsonPath` and `xPath` do not — those are things the operator must supply, and a
default of `SOURCE_TEST_V1` would silently write a test identifier into a real config. **This deviates from the
plan's table**, which listed `SOURCE_TEST_V1` as `adapter_id`'s default.

**Two enums the plan proposed are `string`s instead**, under `I2`:
- `sftp_settings.strictHostKeyChecking` — the sample shows only `"no"`. `yes` was my recollection, not evidence.
- `command_center.solace_messaging_mode` — the sample shows only `PERSISTENT`; `DIRECT` was likewise recalled.

Each is a one-line change to `enum` once the value set is confirmed. Recorded as open questions Q-E and Q-F below.

**`sftp_settings.enabled` / `ftp_settings.enabled` / `gcs.enabled`** are `int`, `default: 1`, `readOnly`, gated on
their connector. Selecting SFTP therefore writes `sftp_settings.enabled: 1`, and no other branch writes anything —
which is spec §3.5's "absent block means disabled" made concrete.

**`gcs.retrySettings.*` are `advanced`** (disclosure only, never visibility). The one piece of UI judgement in
the package; nothing else is marked advanced.

## 3 · What deliberately did not change

- **The old sink** (`COMP_SINK_SOLACE@FILETOFILE`, synthetic) is still in the file — `FE-153` replaces it. The
  package is a mix for exactly one commit, and validates.
- **`solution-package.schema.json`** and every gate — untouched, and all still pass (gates 4, 5, 5b, 6, 9, 11, 12,
  13 exercised by the new discriminator).
- **No File Mesh identifier in React.** The test file lives in `contracts/`, not `src/`.

## 4 · A trap found on the way

`contracts/packages/**` was **not** in the `contracts` Vitest project's `include` list, so the package test the
plan places beside its package would never have run — and a test that silently does not run is indistinguishable
from one that passes. The glob is added to `vitest.config.ts`, and the test was seen **failing first** (10 red
against the old synthetic package) which is what proves it is picked up. This is the same class of hazard as
`run.test.ts`'s own guard against matching zero fixtures.

## 5 · Evidence

| Check | Result |
|---|---|
| New tests before authoring | 10 failed / 3 passed (the 3 pass vacuously against the old package — nothing to leak) |
| after | **13 / 13** |
| `validate-package.py`, file-mesh | PASS `components=2 fields=88` |
| `validate-package.py`, all nine | 9 / 9 PASS |
| lint on the files this task touched | clean at `--max-warnings=0` (one real catch: a redundant `as` cast, removed) |
| `typecheck` | clean |
| Full frontend suite | see §7 |

## 6 · Open

| # | Question | Effect |
|---|---|---|
| Q-E | What values does `strictHostKeyChecking` accept? | `string` → `enum` |
| Q-F | What values does `solace_messaging_mode` accept? | `string` → `enum` |
| Q-G | Are `credentialsFilePath`, `ip`, `user`, `projectId` genuinely mandatory? | They are `required` because the template carries placeholders for them (`SFTP_HOST_ADDRESS`, `GCS_PROJECT_ID`) — an inference, not a connector statement. A false `required` blocks a valid save; worth confirming. |
| — | Units for `max_file_transfer_size`, `max_bandwidth_limit`, `heartbeat_interval` | No unit is claimed anywhere in the package. |
| Q-B, Q-C | Unchanged from the spec | |

## 7 · Full-suite result

PENDING
