# yamlToPackage report: file-mesh / source

- Profile: `/tmp/claude-1000/-home-arnav-rinawa-Documents-solution-manager-solution-manager-main-main/f006b63b-389c-48c0-9bac-370ecfe1d448/scratchpad/t11/e2e/profile.yaml`
- YAML: `.claude/skills/solution-manager-yamlToPackage/examples/file-mesh-source/input/application-source-file-v2.yml`
- Package: `frontend/contracts/packages/file-mesh/package.json`
- Target versions: [1, 2, 3]

## Coverage

110 YAML keys → 109 fields, 1 removed by you (balanced: yes)

0 open · 6 coerced · 6 secret default(s) withheld (I6)

### Placed, by component

| component | keys | shared |
|---|---|---|
| server | 6 | yes |
| spring-boot-admin | 9 | yes |
| application | 1 | yes |
| file-source | 53 | no |
| solace | 20 | yes |
| logging | 10 | yes |
| management | 10 | yes |

### Removed by you

- `file.source.command_center.useoutputdestinationsolacecredentials` (line 97): case variant; kept useOutputDestinationSolaceCredentials

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

## Diff against the existing package

### spring-cloud-stream

- added: 0
- removed (by you, or relocated by the profile): 4: spring.cloud.stream.bindings.input-0.destination, spring.cloud.stream.bindings.input-0.binder, spring.cloud.stream.bindings.output-0.destination, spring.cloud.stream.bindings.output-0.binder
- changed: 0
- in the package, not in this YAML (kept): 0

### file-source

- added: 4: spring.cloud.stream.bindings.input-0.destination, spring.cloud.stream.bindings.input-0.binder, spring.cloud.stream.bindings.output-0.destination, spring.cloud.stream.bindings.output-0.binder
- changed: 3
  - `file.source.sftp_settings.password` default (secret withheld): `"***"` -> `null`
  - `file.source.ftp_settings.password` default (secret withheld): `"***"` -> `null`
  - `file.source.general.adapter_id` default: `"SOURCE_TEST_V1"` -> `"fm-file-source"`
- in the package, not in this YAML (kept): 0

### file-sink

- added: 0
- changed: 2
  - `file.sink.sftp_settings.password` default (secret withheld): `"***"` -> `null`
  - `file.sink.ftp_settings.password` default (secret withheld): `"***"` -> `null`
- in the package, not in this YAML (kept): 0

### server

- added: 0
- changed: 1
  - `server.ssl.key-store-password` default (secret withheld): `"***"` -> `null`
- in the package, not in this YAML (kept): 0

### spring-boot-admin

- added: 0
- changed: 1
  - `spring.boot.admin.client.password` default (secret withheld): `"***"` -> `null`
- in the package, not in this YAML (kept): 0

### solace

- added: 0
- changed: 2
  - `solace.connector.management.session.client-password` default (secret withheld): `"***"` -> `null`
  - `solace.java.client-password` default (secret withheld): `"***"` -> `null`
- in the package, not in this YAML (kept): 0

### Shared components: YAML vs package defaults (package kept)

- server `server.port`: package `8094`, YAML `8092`
- server `server.ssl.enabled`: package `true`, YAML `false`
- application `spring.application.name`: package `"SOURCE_TEST_V1"`, YAML `"fm-file-source"`
- solace `solace.connector.management.queue`: package `""`, YAML `"fm-file-source-management"`
- solace `solace.connector.management.session.host`: package `"tcp://localhost:55555"`, YAML `"tcp://host.docker.internal:55554"` (valueFrom)
- solace `solace.java.host`: package `"tcp://localhost:55555"`, YAML `"tcp://host.docker.internal:55554"` (valueFrom)
- management `management.endpoints.web.exposure.include`: package `"*"`, YAML `"health,info,metrics"`

### Reviewed labels kept where the derived one differs (19)

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

## Your decisions

### Suggestions

- application/spring.application.name required: reject
- solace/solace.connector.management.session.host required: accept
- solace/solace.java.host required: accept
- logging/logging.file.name required: reject

### Restyle

accepted: new positions on 138 field(s), stock helper text removed from 154, 2 custom kept

## Validation and read-back

**PASS**

- note: schema: /home/arnav-rinawa/Documents/solution_manager/solution-manager-main-main/contracts/solution-package.schema.json
- note: read-back: 109 placed field(s) present, 1 removed key(s) absent
- warning: latest package version 4 is carried by only 1 of 10 components

## Notes

- 4 field(s) matching 'spring.cloud.stream.bindings' relocated out of shared component spring-cloud-stream (FM-D11: the bindings run in opposite directions per role, so one shared default cannot fit both)
- shared component spring-cloud-stream has no fields left after relocation; it was kept so instances that already select it stay valid
- secret default withheld: file-source/file.source.sftp_settings.password
- secret default withheld: file-source/file.source.ftp_settings.password
- secret default withheld: file-sink/file.sink.sftp_settings.password
- secret default withheld: file-sink/file.sink.ftp_settings.password
- secret default withheld: server/server.ssl.key-store-password
- secret default withheld: spring-boot-admin/spring.boot.admin.client.password
- secret default withheld: solace/solace.connector.management.session.client-password
- secret default withheld: solace/solace.java.client-password
- component cache preserved (profile `preserve`), but this run changes it: the accepted restyle
- defaults were taken from the YAML (profile defaults.role_components: yaml). This conflicts with docs/design/dynamic-ui/design.md section 7.3 (UI-D*, PROPOSED): defaults come from field initializers, never from a sample application.yml. Flagged, not resolved.
- secret: true added: file-source/file.source.sftp_settings.password
- secret: true added: file-source/file.source.ftp_settings.password
- secret: true added: file-sink/file.sink.sftp_settings.password
- secret: true added: file-sink/file.sink.ftp_settings.password
- secret: true added: server/server.ssl.key-store-password
- secret: true added: spring-boot-admin/spring.boot.admin.client.password
- secret: true added: solace/solace.connector.management.session.client-password
- secret: true added: solace/solace.java.client-password

authoring: {"reviewedRows": 0, "totalRows": 156}
