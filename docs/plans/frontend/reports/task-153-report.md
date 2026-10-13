# FE-153 · The File Mesh sink component — Report

Fifth task of [plan 129](../129-filemesh-source-sink-connectors.md) (design:
[`filemesh-source-sink-connectors.md`](../../../design/dynamic-ui/filemesh-source-sink-connectors.md) §3.3, §11).
Replaces the last synthetic component (`COMP_SINK_SOLACE@FILETOFILE`) with the real `COMP_SINK`, authored from the
supplied sink `application-operator.yml`. After this task the package holds **no** synthetic content.

---

## 1 · What was built

`COMP_SINK` — `role: sink`, `configPath: file.sink`, **42 fields: 41 real config keys + the `connectorType`
discriminator.** Subtree tallies were checked programmatically against the sink YAML:

| Subtree | Fields | | Subtree | Fields |
|---|---:|---|---|---:|
| `general` | 9 | | `sftp_settings` | 6 |
| `command_center` | 9 | | `ftp_settings` | 6 |
| `gcs` | **7** (source: 6) | | `file_owner_permissions` | 4 |

**The one role × connector combination field the evidence contains** is `gcs.enableBulkComposeStrategy`
(`bool`, default `true`). It exists in the sink's `gcs:` block and not the source's, so it is authored in
`COMP_SINK` only, carrying `when: { connectorType: "GCS" }`. That is spec §3.3 made concrete: `SINK + GCS` needs no
new grammar and no `all` block — the role axis is the component, the connector axis is the `when`. A test pins it
in both directions (visible on the sink for GCS; absent from the source's GCS branch).

`authoring` now reads `reviewedRows: 90`, `totalRows: 91` — 49 + 41 authored of 50 + 41 present, the one gap being
the source's duplicate lowercase key (Q-B).

## 2 · Design decisions

- **The SFTP and FTP branches are repeated, not shared.** They are the same six keys in each role's own subtree
  (`file.sink.sftp_settings.*` vs `file.source.sftp_settings.*`). A component is self-contained and carries its own
  `configPath`; a cross-component reference would need a mechanism the contract deliberately does not have. The
  repetition was generated from one table, so it cannot drift.
- **Real differences between the roles were preserved, not smoothed over.** `command_center.enabled` defaults
  to `0` on the sink and `1` on the source; `heartbeat_interval` is `3` vs `20`; `state_backup_path` defaults to
  `./backup_state.cfg` vs `""`. Each is what the two samples say.
- **Same defaults policy as the source (`FE-152`).** A default only for connector tunables the sample shows; none for
  environment-specific identifiers. **This deviates from the plan's table**, which listed `"centos"` as the default
  for `override_username` and `override_group` — those are host account names, not connector defaults.
- **`connectorType` and the branch fields sit in one `CONNECTOR` section**, for the same reason as `FE-152`: a
  section whose fields are all hidden renders an empty card.

## 3 · What deliberately did not change

- `solution-package.schema.json`, every gate, and both `project()` implementations.
- The source component from `FE-152`.
- No File Mesh identifier in React: the tests live in `contracts/`.

## 4 · Evidence

| Check | Result |
|---|---|
| Sink tests written before the sink was spliced in | authored first; 13 new cases |
| `contracts` project | **7 files / 101 tests pass** — the 88 from `FE-152` plus the 13 new sink and whole-package tests |
| `validate-package.py`, file-mesh | PASS `components=2 fields=92` |
| all nine packages | 9 / 9 PASS |
| Full suite | see §6 |

## 5 · Open

`Q-E`, `Q-F`, `Q-G` from [`FE-152`](task-152-report.md) apply unchanged to the sink's identical branch fields.
New: **`file_owner_permissions.copy_source_file_mode_permissions` / `copy_source_file_owner`** are `int` with
default `0` because that is what the sample shows; whether they are booleans in disguise (like `enabled: 0/1`) is
not stated anywhere I can see.

## 6 · Full-suite result

PENDING
