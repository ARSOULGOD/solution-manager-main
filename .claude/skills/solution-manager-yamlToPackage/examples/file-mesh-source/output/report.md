# yamlToPackage report: file-mesh / source

- Profile: `.claude/skills/solution-manager-yamlToPackage/references/profiles/file-mesh.yaml`
- YAML: `.claude/skills/solution-manager-yamlToPackage/examples/file-mesh-source/input/application-source-file-v2.yml`
- Package: `frontend/contracts/packages/file-mesh/package.json`
- Target versions: [1, 2, 3]

## Coverage

110 YAML leaves: **109 mapped**, **0 flagged**, **1 excluded**. Every leaf is in exactly one bucket: yes. 6 coerced, 6 secret default(s) withheld.

### Flagged (needs a decision)

- none

### Excluded (by the profile)

- `file.source.command_center.useoutputdestinationsolacecredentials` (line 97): FM-D1: lowercase duplicate of useOutputDestinationSolaceCredentials (string 'true' vs bool true).

### Coerced / assumed

- `spring.boot.admin.client.instance.prefer-ip` (line 14): string 'true' coerced to bool
- `spring.boot.admin.client.enabled` (line 17): string 'true' coerced to bool
- `spring.boot.admin.client.auto-registration` (line 19): string 'true' coerced to bool
- `spring.boot.admin.client.prefer-ip` (line 20): string 'true' coerced to bool
- `file.source.sftp_settings.strictHostKeyChecking` (line 70): unquoted 'no' kept as a string (a YAML 1.1 reader would make it a bool)
- `management.endpoint.health.status.order` (line 169): list ['DOWN', 'OUT_OF_SERVICE', 'RECONNECTING', 'UP', 'UNKNOWN'] joined with ',' into one string

### Secret defaults withheld (I6)

- `server.ssl.key-store-password` (line 6)
- `spring.boot.admin.client.password` (line 22)
- `file.source.sftp_settings.password` (line 69)
- `file.source.ftp_settings.password` (line 76)
- `solace.connector.management.session.client-password` (line 122)
- `solace.java.client-password` (line 127)

### Mapped, by component

| component | fields | shared |
|---|---|---|
| server | 6 | yes |
| spring-boot-admin | 9 | yes |
| application | 1 | yes |
| spring-cloud-stream | 4 | yes |
| file-source | 49 | no |
| solace | 20 | yes |
| logging | 10 | yes |
| management | 10 | yes |

## Diff against the existing package

### server

- added: 0
- changed: 1
  - `server.ssl.key-store-password` default (secret withheld): `"changeit"` -> `null`
- in package, not in YAML (kept): 0

### spring-boot-admin

- added: 0
- changed: 1
  - `spring.boot.admin.client.password` default (secret withheld): `"pass"` -> `null`
- in package, not in YAML (kept): 0

### file-source

- added: 0
- changed: 3
  - `file.source.general.adapter_id` default: `"SOURCE_TEST_V1"` -> `"fm-file-source"`
  - `file.source.sftp_settings.password` default (secret withheld): `"SFTP_PASSWORD"` -> `null`
  - `file.source.ftp_settings.password` default (secret withheld): `"FTP_PASSWORD"` -> `null`
- in package, not in YAML (kept): 0

### solace

- added: 0
- changed: 2
  - `solace.connector.management.session.client-password` default (secret withheld): `"test"` -> `null`
  - `solace.java.client-password` default (secret withheld): `"test"` -> `null`
- in package, not in YAML (kept): 0

### Shared components: YAML vs package defaults (package kept)

- server `server.port`: package `8094`, YAML `8092`
- server `server.ssl.enabled`: package `true`, YAML `false`
- application `spring.application.name`: package `"SOURCE_TEST_V1"`, YAML `"fm-file-source"`
- spring-cloud-stream `spring.cloud.stream.bindings.input-0.destination`: package `"/home/banala/Music/entityFolders/updated/SOURCE_TEST_V1/file_paths.cfg"`, YAML `"/app/external/classpath/file_paths.cfg"`
- spring-cloud-stream `spring.cloud.stream.bindings.output-0.destination`: package `"file/test"`, YAML `"SOURCE/DEFAULT/Alice/FF/NWFILE/SEP15/1"`
- solace `solace.connector.management.queue`: package `""`, YAML `"fm-file-source-management"`
- solace `solace.connector.management.session.host`: package `"tcp://localhost:55555"`, YAML `"tcp://host.docker.internal:55554"` (valueFrom)
- solace `solace.java.host`: package `"tcp://localhost:55555"`, YAML `"tcp://host.docker.internal:55554"` (valueFrom)
- management `management.endpoints.web.exposure.include`: package `"*"`, YAML `"health,info,metrics"`

### Reviewed ui kept where the derived one differs (19)

- file-source `file.source.file_to_event.xPath`: package ['File To Event', 'XPath'], derived ['File To Event', 'X path']
- file-source `file.source.gcs.projectId`: package ['GCS', 'GCS project ID'], derived ['GCS', 'Project ID']
- file-source `file.source.gcs.credentialsFilePath`: package ['GCS', 'GCS credentials file path'], derived ['GCS', 'Credentials file path']
- file-source `file.source.gcs.retrySettings.maxAttempts`: package ['GCS Retry', 'GCS max attempts'], derived ['GCS Retry', 'Max attempts']
- file-source `file.source.gcs.retrySettings.retryDelayMultiplier`: package ['GCS Retry', 'GCS retry delay multiplier'], derived ['GCS Retry', 'Retry delay multiplier']
- file-source `file.source.gcs.retrySettings.maxDurationMinutes`: package ['GCS Retry', 'GCS max duration (minutes)'], derived ['GCS Retry', 'Max duration (minutes)']
- file-source `file.source.sftp_settings.ip`: package ['SFTP', 'SFTP host'], derived ['SFTP', 'SFTP IP']
- file-source `file.source.ftp_settings.ip`: package ['FTP', 'FTP host'], derived ['FTP', 'FTP IP']
- file-source `file.source.directory_wildcard.config_path`: package ['Directory Wildcard', 'Wildcard config path'], derived ['Directory Wildcard', 'Config path']
- file-source `file.source.directory_replication.start_time`: package ['Directory Replication', 'Replication start time'], derived ['Directory Replication', 'Start time']
- file-source `file.source.solace_out.lvq`: package ['Solace Output', 'LVQ'], derived ['Solace Out', 'LVQ']
- file-source `file.source.solace_out.destination`: package ['Solace Output', 'Destination'], derived ['Solace Out', 'Destination']
- file-source `file.source.command_center.enabled`: package ['Command Center', 'Command Center enabled'], derived ['Command Center', 'Command center enabled']
- file-source `file.source.command_center.useOutputDestinationSolaceCredentials`: package ['Command Center', 'Use output destination Solace credentials'], derived ['Command Center', 'Use output destination solace credentials']
- file-source `file.source.command_center.solace_base_publish_topic_heartbeat`: package ['Command Center', 'Heartbeat publish topic'], derived ['Command Center', 'Solace base publish topic heartbeat']
- ...and 4 more in report.json

## Validation

**PASS**

- note: schema: /home/arnav-rinawa/Documents/solution_manager/solution-manager-main-main/contracts/solution-package.schema.json
- warning: file-sink/file.sink.sftp_settings.password: secret field carries a default value (I6: secret values never land in config storage)
- warning: file-sink/file.sink.ftp_settings.password: secret field carries a default value (I6: secret values never land in config storage)
- warning: latest package version 4 is carried by only 1 of 10 components

## Notes

- component cache preserved untouched (profile `preserve`)

## Open questions

1. provenance carries a signature over content this run changed, so it no longer covers the package. Keep it as-is, or re-run with --provenance dev-mode?

authoring: {"reviewedRows": 149, "totalRows": 156}
