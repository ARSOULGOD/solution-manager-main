# yamlToPackage report: file-mesh / sink

- Profile: `<scratch>/e2e/source/profile.yaml`
- YAML: `.claude/skills/solution-manager-yamlToPackage/examples/file-mesh-sink/input/application-sink-file-v2.yml`
- Package: `<scratch>/e2e/source/package.json`
- Target versions: [1, 2, 3]

## Coverage

102 YAML keys → 102 fields, 0 removed by you (balanced: yes)

0 open · 5 coerced · 6 secret default(s) withheld (I6)

### Placed, by component

| component | keys | shared |
|---|---|---|
| server | 6 | yes |
| application | 1 | yes |
| spring-boot-admin | 9 | yes |
| file-sink | 48 | no |
| solace | 20 | yes |
| logging | 8 | yes |
| management | 10 | yes |

### Removed by you

(none)

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

## Diff against the existing package

### file-sink

- added: 7: spring.cloud.stream.bindings.input-0.destination, spring.cloud.stream.bindings.input-0.binder, spring.cloud.stream.bindings.output-0.destination, spring.cloud.stream.bindings.output-0.binder, spring.cloud.stream.solace.default.consumer.queue-name-expression, spring.cloud.stream.solace.default.consumer.provision-durable-queue, spring.cloud.stream.solace.default.consumer.add-destination-as-subscription-to-queue
- changed: 1
  - `file.sink.general.adapter_id` default: `"SINK_CONN_ID1"` -> `"fm-file-sink"`
- in the package, not in this YAML (kept): 0

### logging

- added: 0
- changed: 0
- in the package, not in this YAML (kept): 2: logging.level.com.solace.connector.jms, logging.level.com.solace.connector.core

### Shared components: YAML vs package defaults (package kept)

- server `server.port`: package `8094`, YAML `8093`
- server `server.ssl.enabled`: package `true`, YAML `false`
- application `spring.application.name`: package `"SOURCE_TEST_V1"`, YAML `"fm-file-sink"`
- solace `solace.connector.management.leader-election.mode`: package `"standalone"`, YAML `"active_standby"`
- solace `solace.connector.management.queue`: package `""`, YAML `"fm-file-sink-management"`
- solace `solace.connector.management.session.host`: package `"tcp://localhost:55555"`, YAML `"tcp://host.docker.internal:55554"` (valueFrom)
- solace `solace.java.host`: package `"tcp://localhost:55555"`, YAML `"tcp://host.docker.internal:55554"` (valueFrom)
- management `management.endpoints.web.exposure.include`: package `"*"`, YAML `"health,info,metrics"`

### Reviewed labels kept where the derived one differs (16)

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

## Your decisions

### Suggestions

- application/spring.application.name required: reject
- solace/solace.connector.management.session.host required: accept
- solace/solace.java.host required: accept
- logging/logging.file.name required: reject

### Restyle

not offered: the package is already in db-designer style, or new

## Validation and read-back

**PASS**

- note: schema: contracts/solution-package.schema.json
- note: read-back: 102 placed field(s) present, 0 removed key(s) absent
- warning: latest package version 4 is carried by only 1 of 10 components

## Notes

- component cache preserved untouched (profile `preserve`)
- defaults were taken from the YAML (profile defaults.role_components: yaml). This conflicts with docs/design/dynamic-ui/design.md section 7.3 (UI-D*, PROPOSED): defaults come from field initializers, never from a sample application.yml. Flagged, not resolved.
- package.diff compares content: <scratch>/e2e/source/package.json is formatted differently from the generated package.json, and that formatting change is not shown
- signature: keep (remembered 2026-11-10)

authoring: {"reviewedRows": 0, "totalRows": 163}
