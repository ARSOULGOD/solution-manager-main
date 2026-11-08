# yamlToPackage report: file-mesh / sink

- Profile: `.claude/skills/solution-manager-yamlToPackage/references/profiles/file-mesh.yaml`
- YAML: `.claude/skills/solution-manager-yamlToPackage/examples/file-mesh-sink/input/application-sink-file-v2.yml`
- Package: `frontend/contracts/packages/file-mesh/package.json`
- Target versions: [1, 2, 3]

## Coverage

102 YAML leaves: **99 mapped**, **3 flagged**, **0 excluded**. Every leaf is in exactly one bucket: yes. 5 coerced, 6 secret default(s) withheld.

### Flagged (needs a decision)

- `spring.cloud.stream.solace.default.consumer.queue-name-expression` (line 37): role-exclusive (FM-D2: the Solace binder consumer defaults appear only in the sink YAML.) but would land in shared component spring-cloud-stream; decide: field role 'sink' in spring-cloud-stream, a new component, or exclude
- `spring.cloud.stream.solace.default.consumer.provision-durable-queue` (line 38): role-exclusive (FM-D2: the Solace binder consumer defaults appear only in the sink YAML.) but would land in shared component spring-cloud-stream; decide: field role 'sink' in spring-cloud-stream, a new component, or exclude
- `spring.cloud.stream.solace.default.consumer.add-destination-as-subscription-to-queue` (line 39): role-exclusive (FM-D2: the Solace binder consumer defaults appear only in the sink YAML.) but would land in shared component spring-cloud-stream; decide: field role 'sink' in spring-cloud-stream, a new component, or exclude

### Excluded (by the profile)

- none

### Coerced / assumed

- `spring.boot.admin.client.instance.prefer-ip` (line 16): string 'true' coerced to bool
- `spring.boot.admin.client.enabled` (line 19): string 'true' coerced to bool
- `spring.boot.admin.client.auto-registration` (line 21): string 'true' coerced to bool
- `spring.boot.admin.client.prefer-ip` (line 22): string 'true' coerced to bool
- `management.endpoint.health.status.order` (line 159): list ['DOWN', 'OUT_OF_SERVICE', 'RECONNECTING', 'UP', 'UNKNOWN'] joined with ',' into one string

### Secret defaults withheld (I6)

- `server.ssl.key-store-password` (line 6)
- `spring.boot.admin.client.password` (line 24)
- `file.sink.sftp_settings.password` (line 66)
- `file.sink.ftp_settings.password` (line 73)
- `solace.connector.management.session.client-password` (line 115)
- `solace.java.client-password` (line 120)

### Mapped, by component

| component | fields | shared |
|---|---|---|
| server | 6 | yes |
| application | 1 | yes |
| spring-boot-admin | 9 | yes |
| spring-cloud-stream | 4 | yes |
| file-sink | 41 | no |
| solace | 20 | yes |
| logging | 8 | yes |
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

### file-sink

- added: 0
- changed: 3
  - `file.sink.general.adapter_id` default: `"SINK_CONN_ID1"` -> `"fm-file-sink"`
  - `file.sink.sftp_settings.password` default (secret withheld): `"SFTP_PASSWORD"` -> `null`
  - `file.sink.ftp_settings.password` default (secret withheld): `"FTP_PASSWORD"` -> `null`
- in package, not in YAML (kept): 0

### solace

- added: 0
- changed: 2
  - `solace.connector.management.session.client-password` default (secret withheld): `"test"` -> `null`
  - `solace.java.client-password` default (secret withheld): `"test"` -> `null`
- in package, not in YAML (kept): 0

### Shared components: YAML vs package defaults (package kept)

- server `server.port`: package `8094`, YAML `8093`
- server `server.ssl.enabled`: package `true`, YAML `false`
- application `spring.application.name`: package `"SOURCE_TEST_V1"`, YAML `"fm-file-sink"`
- spring-cloud-stream `spring.cloud.stream.bindings.input-0.destination`: package `"/home/banala/Music/entityFolders/updated/SOURCE_TEST_V1/file_paths.cfg"`, YAML `"SOURCE/DEFAULT/Alice/FF/NWFILE/SEP15/1"`
- spring-cloud-stream `spring.cloud.stream.bindings.input-0.binder`: package `"file"`, YAML `"solace"`
- spring-cloud-stream `spring.cloud.stream.bindings.output-0.destination`: package `"file/test"`, YAML `"/app/external/file-output"`
- spring-cloud-stream `spring.cloud.stream.bindings.output-0.binder`: package `"solace"`, YAML `"file"`
- solace `solace.connector.management.leader-election.mode`: package `"standalone"`, YAML `"active_standby"`
- solace `solace.connector.management.queue`: package `""`, YAML `"fm-file-sink-management"`
- solace `solace.connector.management.session.host`: package `"tcp://localhost:55555"`, YAML `"tcp://host.docker.internal:55554"` (valueFrom)
- solace `solace.java.host`: package `"tcp://localhost:55555"`, YAML `"tcp://host.docker.internal:55554"` (valueFrom)
- management `management.endpoints.web.exposure.include`: package `"*"`, YAML `"health,info,metrics"`

### Reviewed ui kept where the derived one differs (16)

- file-sink `file.sink.general.dest_file_name_type`: package ['General', 'Destination file name type'], derived ['General', 'Dest file name type']
- file-sink `file.sink.gcs.projectId`: package ['GCS', 'GCS project ID'], derived ['GCS', 'Project ID']
- file-sink `file.sink.gcs.credentialsFilePath`: package ['GCS', 'GCS credentials file path'], derived ['GCS', 'Credentials file path']
- file-sink `file.sink.gcs.enableBulkComposeStrategy`: package ['GCS', 'GCS bulk compose strategy'], derived ['GCS', 'Enable bulk compose strategy']
- file-sink `file.sink.gcs.retrySettings.maxAttempts`: package ['GCS Retry', 'GCS max attempts'], derived ['GCS Retry', 'Max attempts']
- file-sink `file.sink.gcs.retrySettings.retryDelayMultiplier`: package ['GCS Retry', 'GCS retry delay multiplier'], derived ['GCS Retry', 'Retry delay multiplier']
- file-sink `file.sink.gcs.retrySettings.maxDurationMinutes`: package ['GCS Retry', 'GCS max duration (minutes)'], derived ['GCS Retry', 'Max duration (minutes)']
- file-sink `file.sink.sftp_settings.ip`: package ['SFTP', 'SFTP host'], derived ['SFTP', 'SFTP IP']
- file-sink `file.sink.ftp_settings.ip`: package ['FTP', 'FTP host'], derived ['FTP', 'FTP IP']
- file-sink `file.sink.command_center.enabled`: package ['Command Center', 'Command Center enabled'], derived ['Command Center', 'Command center enabled']
- file-sink `file.sink.command_center.useOutputDestinationSolaceCredentials`: package ['Command Center', 'Use output destination Solace credentials'], derived ['Command Center', 'Use output destination solace credentials']
- file-sink `file.sink.command_center.solace_base_publish_topic`: package ['Command Center', 'Publish topic'], derived ['Command Center', 'Solace base publish topic']
- file-sink `file.sink.command_center.solace_messaging_mode`: package ['Command Center', 'Messaging mode'], derived ['Command Center', 'Solace messaging mode']
- file-sink `file.sink.command_center.solace_base_publish_topic_heartbeat`: package ['Command Center', 'Heartbeat publish topic'], derived ['Command Center', 'Solace base publish topic heartbeat']
- file-sink `file.sink.command_center.events.fileStart`: package ['Command Center Events', 'File start event'], derived ['Command Center Events', 'File start']
- ...and 1 more in report.json

## Validation

**PASS**

- note: schema: /home/arnav-rinawa/Documents/solution_manager/solution-manager-main-main/contracts/solution-package.schema.json
- warning: file-source/file.source.sftp_settings.password: secret field carries a default value (I6: secret values never land in config storage)
- warning: file-source/file.source.ftp_settings.password: secret field carries a default value (I6: secret values never land in config storage)
- warning: latest package version 4 is carried by only 1 of 10 components

## Notes

- component cache preserved untouched (profile `preserve`)
- defaults were taken from the YAML (profile defaults.role_components: yaml). This conflicts with docs/design/dynamic-ui/design.md section 7.3 (UI-D*, PROPOSED): defaults come from field initializers, never from a sample application.yml. Flagged, not resolved.

## Open questions

1. shared component logging renders 2 field(s) this sink YAML does not carry (logging.level.com.solace.connector.jms, logging.level.com.solace.connector.core). Every sink instance gets them. Keep them shared, give them a field role, or move them to a role component?
2. provenance carries a signature over content this run changed, so it no longer covers the package. Keep it as-is, or re-run with --provenance dev-mode?
3. `spring.cloud.stream.solace.default.consumer.queue-name-expression`, `spring.cloud.stream.solace.default.consumer.provision-durable-queue`, `spring.cloud.stream.solace.default.consumer.add-destination-as-subscription-to-queue`: role-exclusive (FM-D2: the Solace binder consumer defaults appear only in the sink YAML.) but would land in shared component spring-cloud-stream; decide: field role 'sink' in spring-cloud-stream, a new component, or exclude

authoring: {"reviewedRows": 149, "totalRows": 156}
