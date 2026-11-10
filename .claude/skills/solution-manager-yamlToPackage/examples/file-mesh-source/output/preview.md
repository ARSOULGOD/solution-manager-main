## Changes since the last preview

Keys: placed 108 → 109 · removed 0 → 1 · open 2 → 0

Row changes (11):
- file-source/file.source.command_center.useOutputDestinationSolaceCredentials: added
- solace/solace.connector.management.session.host: pos 10 → 1
- solace/solace.connector.management.session.host: required no → yes
- solace/solace.java.host: pos 14 → 1
- solace/solace.java.host: required no → yes
- file-source: pos renumbered on 52 rows
- server: pos renumbered on 5 rows
- spring-boot-admin: pos renumbered on 6 rows
- solace: pos renumbered on 17 rows
- logging: pos renumbered on 5 rows
- management: pos renumbered on 9 rows

Questions resolved (7):
- `spelling:file.source.commandcenter.useoutputdestinationsolacecredentials`
- `suggest:application/spring.application.name/required`
- `suggest:solace/solace.connector.management.session.host/required`
- `suggest:solace/solace.java.host/required`
- `suggest:logging/logging.file.name/required`
- `restyle`
- `provenance`

New questions (0):
- none

Nothing is open.

## file-mesh / source: PREVIEW (nothing created yet)

- YAML: `application-source-file-v2.yml`, 110 keys · profile: `profile.yaml` (confirmed)
- Package: `frontend/contracts/packages/file-mesh/package.json` · packageVersion [1,2,3,4] · appliesTo ">=1.0 <2.0"
- Roles: source → file-source (one-of-many) · shared (none): cache, server, spring-boot-admin, spring-cloud-stream, solace, application, logging, management
- Versions: this YAML targets [1,2,3]; every field it feeds carries [1,2,3]
- Samples: `frontend/contracts/packages`: 8 packages, 103 distinct fields (file-mesh itself not used)
- Keys: 110 → 109 placed · 1 removed by you · 0 open

### file-source (role source): 53 fields

| YAML key (line) | type | default | section · pos | shown | required | notes |
|---|---|---|---|---|---|---|
| `spring.cloud.stream.bindings.input-0.destination` (L29) | string | "/app/external/classpath/file_paths.cfg" | Input Binding · 0 | up front | no | new |
| `spring.cloud.stream.bindings.input-0.binder` (L30) | enum | "file" | Input Binding · 1 | up front | no | new |
| `spring.cloud.stream.bindings.output-0.destination` (L32) | string | "SOURCE/DEFAULT/Alice/FF/NWFILE/SEP15/1" | Output Binding · 0 | up front | no | new |
| `spring.cloud.stream.bindings.output-0.binder` (L33) | enum | "solace" | Output Binding · 1 | up front | no | new |
| `file.source.scheduler.restart_time_sec` (L37) | int | 10 | Scheduler · 0 | up front | no |  |
| `file.source.general.adapter_id` (L39) | string | "fm-file-source" | General · 0 | up front | no | was "SOURCE_TEST_V1" |
| `file.source.general.file_type` (L40) | int | 4 | General · 1 | up front | no |  |
| `file.source.general.ignoreEmptyFiles` (L41) | bool | false | General · 2 | up front | no |  |
| `file.source.general.state_backup_path` (L42) | string | "" | General · 3 | up front | no |  |
| `file.source.general.copy_file_mode_permissions` (L43) | int | 1 | General · 4 | up front | no |  |
| `file.source.general.max_file_transfer_size` (L44) | int | 999000 | General · 5 | up front | no |  |
| `file.source.general.max_files_allowed` (L45) | int | 99999 | General · 6 | up front | no |  |
| `file.source.general.max_bandwidth_limit` (L46) | int | 100000000 | General · 7 | up front | no |  |
| `file.source.general.clear_state_on_eod` (L47) | int | 1 | General · 8 | up front | no |  |
| `file.source.general.eod_time_sec` (L48) | int | -1 | General · 9 | up front | no |  |
| `file.source.general.multiple_files_flag` (L49) | int | 0 | General · 10 | up front | no |  |
| `file.source.file_to_event.enabled` (L51) | int | 0 | File To Event · 0 | up front | no |  |
| `file.source.file_to_event.fileFormat` (L52) | int | 4 | File To Event · 1 | up front | no |  |
| `file.source.file_to_event.eventDelimiter` (L53) | string | "\|" | File To Event · 2 | up front | no |  |
| `file.source.file_to_event.jsonPath` (L54) | string | "$.Authors[*].Books[*]" | File To Event · 3 | up front | no |  |
| `file.source.file_to_event.xPath` (L55) | string | "/class/student" | File To Event · 4 | up front | no |  |
| `file.source.gcs.enabled` (L57) | int | 0 | GCS · 0 | up front | no |  |
| `file.source.gcs.projectId` (L58) | string | "GCS_PROJECT_ID" | GCS · 1 | up front | no |  |
| `file.source.gcs.credentialsFilePath` (L59) | string | "/home/centos/pubsubplus-connector-file-2.2.0/configs/gcs/creds.json" | GCS · 2 | up front | no |  |
| `file.source.gcs.retrySettings.maxAttempts` (L61) | int | 5 | GCS Retry · 0 | up front | no |  |
| `file.source.gcs.retrySettings.retryDelayMultiplier` (L62) | int | 3 | GCS Retry · 1 | up front | no |  |
| `file.source.gcs.retrySettings.maxDurationMinutes` (L63) | int | 2 | GCS Retry · 2 | up front | no |  |
| `file.source.sftp_settings.enabled` (L65) | int | 0 | SFTP · 0 | up front | no |  |
| `file.source.sftp_settings.ip` (L66) | string | "SFTP_HOST_ADDRESS" | SFTP · 1 | up front | no |  |
| `file.source.sftp_settings.port` (L67) | int | 22 | SFTP · 2 | up front | no |  |
| `file.source.sftp_settings.user` (L68) | string | "SFTP_USER" | SFTP · 3 | up front | no |  |
| `file.source.sftp_settings.password` (L69) | secret | withheld (I6) | SFTP · 4 | up front | no | secret: true added |
| `file.source.sftp_settings.strictHostKeyChecking` (L70) | enum | "no" | SFTP · 5 | up front | no |  |
| `file.source.ftp_settings.enabled` (L72) | int | 0 | FTP · 0 | up front | no |  |
| `file.source.ftp_settings.ip` (L73) | string | "FTP_HOST_ADDRESS" | FTP · 1 | up front | no |  |
| `file.source.ftp_settings.port` (L74) | int | 21 | FTP · 2 | up front | no |  |
| `file.source.ftp_settings.user` (L75) | string | "FTP_USER" | FTP · 3 | up front | no |  |
| `file.source.ftp_settings.password` (L76) | secret | withheld (I6) | FTP · 4 | up front | no | secret: true added |
| `file.source.ftp_settings.secured` (L77) | bool | true | FTP · 5 | up front | no |  |
| `file.source.directory_wildcard.wildcard_type` (L79) | int | 0 | Directory Wildcard · 0 | up front | no |  |
| `file.source.directory_wildcard.config_path` (L80) | string | "" | Directory Wildcard · 1 | up front | no |  |
| `file.source.directory_replication.start_time` (L82) | int | -1 | Directory Replication · 0 | up front | no |  |
| `file.source.solace_out.lvq` (L84) | string | "file.source.lvq" | Solace Output · 0 | up front | no |  |
| `file.source.solace_out.destination` (L85) | string | "file/source/lvq" | Solace Output · 1 | up front | no |  |
| `file.source.command_center.enabled` (L87) | int | 1 | Command Center · 0 | up front | no |  |
| `file.source.command_center.useOutputDestinationSolaceCredentials` (L88) | bool | true | Command Center · 1 | up front | no |  |
| `file.source.command_center.heartbeat_enabled` (L89) | int | 1 | Command Center · 2 | up front | no |  |
| `file.source.command_center.heartbeat_interval` (L90) | int | 20 | Command Center · 3 | up front | no |  |
| `file.source.command_center.solace_base_publish_topic_heartbeat` (L91) | string | "scm/dd/FIN/source/cc/se/fa/source/findata" | Command Center · 4 | up front | no |  |
| `file.source.command_center.solace_base_publish_topic` (L92) | string | "scm/dd/FIN/source/cc/se/fa/source/findata" | Command Center · 5 | up front | no |  |
| `file.source.command_center.solace_messaging_mode` (L93) | enum | "PERSISTENT" | Command Center · 6 | up front | no |  |
| `file.source.command_center.events.fileStart` (L95) | bool | true | Command Center Events · 0 | up front | no |  |
| `file.source.command_center.events.fileComplete` (L96) | bool | true | Command Center Events · 1 | up front | no |  |

### server (shared): 6 fields

| YAML key (line) | type | default | section · pos | shown | required | notes |
|---|---|---|---|---|---|---|
| `server.port` (L2) | int | 8094 | Server · 0 | up front | no | YAML says 8092 |
| `server.ssl.enabled` (L4) | bool | true | SSL · 0 | up front | no | YAML says false |
| `server.ssl.key-store` (L5) | string | "file:/home/banala/Music/entityFolders/download_binaries/SOURCE_NOSUBORG_Alice_FF_NWFILE_T3/keystore.p12" | SSL · 1 | up front | no |  |
| `server.ssl.key-store-password` (L6) | secret | withheld (I6) | SSL · 2 | up front | no | secret: true added |
| `server.ssl.key-store-type` (L7) | string | "PKCS12" | SSL · 3 | up front | no |  |
| `server.ssl.key-alias` (L8) | string | "myalias" | SSL · 4 | up front | no |  |

### spring-boot-admin (shared): 9 fields

| YAML key (line) | type | default | section · pos | shown | required | notes |
|---|---|---|---|---|---|---|
| `spring.boot.admin.client.instance.prefer-ip` (L14) | bool | true | Instance · 0 | up front | no |  |
| `spring.boot.admin.client.instance.management-url` (L15) | string | "http://localIP:${server.port}/actuator" | Instance · 1 | up front | no |  |
| `spring.boot.admin.client.instance.service-url` (L16) | string | "http://localIP:${server.port}/" | Instance · 2 | up front | no |  |
| `spring.boot.admin.client.enabled` (L17) | bool | true | Client · 0 | up front | no |  |
| `spring.boot.admin.client.url` (L18) | string | "http://50.17.36.43:9500/admin" | Client · 1 | up front | no |  |
| `spring.boot.admin.client.auto-registration` (L19) | bool | true | Client · 2 | up front | no |  |
| `spring.boot.admin.client.prefer-ip` (L20) | bool | true | Client · 3 | up front | no |  |
| `spring.boot.admin.client.username` (L21) | string | "user" | Authentication · 0 | up front | no |  |
| `spring.boot.admin.client.password` (L22) | secret | withheld (I6) | Authentication · 1 | up front | no | secret: true added |

### solace (shared): 20 fields

| YAML key (line) | type | default | section · pos | shown | required | notes |
|---|---|---|---|---|---|---|
| `solace.connector.security.enabled` (L101) | bool | false | Security · 0 | up front | no |  |
| `solace.connector.error.handle` (L103) | string | "stop_all" | Error · 0 | up front | no |  |
| `solace.connector.workflows.0.enabled` (L106) | bool | true | Workflow · 0 | up front | no |  |
| `solace.connector.workflows.0.acknowledgment.publish-async` (L108) | bool | true | Workflow · 1 | up front | no |  |
| `solace.connector.management.leader-election.mode` (L111) | enum | "standalone" | Leader Election · 0 | up front | no |  |
| `solace.connector.management.leader-election.fail-over.max-attempts` (L113) | int | 3 | Leader Election · 1 | up front | no |  |
| `solace.connector.management.leader-election.fail-over.back-off-initial-interval` (L114) | int | 1000 | Leader Election · 2 | up front | no |  |
| `solace.connector.management.leader-election.fail-over.back-off-max-interval` (L115) | int | 10000 | Leader Election · 3 | up front | no |  |
| `solace.connector.management.leader-election.fail-over.back-off-multiplier` (L116) | int | 2 | Leader Election · 4 | up front | no |  |
| `solace.connector.management.queue` (L117) | string | "" | Management · 0 | up front | no | YAML says "fm-file-source-management" |
| `solace.connector.management.session.host` (L119) | string | from connection | Management Session · 1 | up front | yes | YAML says "tcp://host.docker.internal:55554"; valueFrom |
| `solace.connector.management.session.msg-vpn` (L120) | string | from connection | Management Session · 2 | up front | no | valueFrom |
| `solace.connector.management.session.client-username` (L121) | string | from connection | Management Session · 3 | up front | no | valueFrom |
| `solace.connector.management.session.client-password` (L122) | secret | withheld (I6) | Management Session · 4 | up front | no | valueFrom; secret: true added |
| `solace.java.host` (L124) | string | from connection | Java Client · 1 | up front | yes | YAML says "tcp://host.docker.internal:55554"; valueFrom |
| `solace.java.msg-vpn` (L125) | string | from connection | Java Client · 2 | up front | no | valueFrom |
| `solace.java.client-username` (L126) | string | from connection | Java Client · 3 | up front | no | valueFrom |
| `solace.java.client-password` (L127) | secret | withheld (I6) | Java Client · 4 | up front | no | valueFrom; secret: true added |
| `solace.java.connectRetries` (L128) | int | -1 | Java Client · 5 | up front | no |  |
| `solace.java.reconnectRetries` (L129) | int | -1 | Java Client · 6 | up front | no |  |

### application (shared): 1 fields

| YAML key (line) | type | default | section · pos | shown | required | notes |
|---|---|---|---|---|---|---|
| `spring.application.name` (L24) | string | "SOURCE_TEST_V1" | Application · 0 | up front | no (suggestion rejected) | YAML says "fm-file-source" |

### logging (shared): 10 fields

| YAML key (line) | type | default | section · pos | shown | required | notes |
|---|---|---|---|---|---|---|
| `logging.level.root` (L132) | string | "INFO" | Log Levels · 0 | up front | no |  |
| `logging.level.com.solace.connector.jms` (L133) | string | "INFO" | Log Levels · 1 | up front | no |  |
| `logging.level.com.solace.connector.core` (L134) | string | "INFO" | Log Levels · 2 | up front | no |  |
| `logging.level.com.solace.spring.cloud.stream.binder` (L135) | string | "INFO" | Log Levels · 3 | up front | no |  |
| `logging.level.com.solacecoe.spring.cloud.stream.binders.file` (L136) | string | "TRACE" | Log Levels · 4 | up front | no |  |
| `logging.file.name` (L138) | string | "myapp.log" | File · 0 | up front | no (suggestion rejected) |  |
| `logging.logback.rollingpolicy.file-name-pattern` (L141) | string | "${LOG_FILE}.%d{yyyy-MM-dd}.%i.gz" | Rolling Policy · 0 | up front | no |  |
| `logging.logback.rollingpolicy.max-file-size` (L142) | string | "100MB" | Rolling Policy · 1 | up front | no |  |
| `logging.logback.rollingpolicy.max-history` (L143) | int | 7 | Rolling Policy · 2 | up front | no |  |
| `logging.logback.rollingpolicy.total-size-cap` (L144) | string | "1GB" | Rolling Policy · 3 | up front | no |  |

### management (shared): 10 fields

| YAML key (line) | type | default | section · pos | shown | required | notes |
|---|---|---|---|---|---|---|
| `management.security.enabled` (L147) | bool | false | Security · 0 | up front | no |  |
| `management.simple.metrics.export.enabled` (L151) | bool | true | Metrics · 0 | up front | no |  |
| `management.endpoints.web.exposure.include` (L155) | string | "*" | Web Endpoints · 0 | up front | no | YAML says "health,info,metrics" |
| `management.endpoints.web.cors.allowed-origins` (L157) | string | "*" | CORS · 0 | up front | no |  |
| `management.endpoints.web.cors.allowed-methods` (L158) | string | "*" | CORS · 1 | up front | no |  |
| `management.endpoints.web.cors.allowed-headers` (L159) | string | "*" | CORS · 2 | up front | no |  |
| `management.endpoint.logfile.external-file` (L162) | string | "myapp.log" | Logfile · 0 | up front | no |  |
| `management.endpoint.prometheus.enabled` (L164) | bool | false | Prometheus · 0 | up front | no |  |
| `management.endpoint.health.show-details` (L166) | string | "ALWAYS" | Health · 0 | up front | no |  |
| `management.endpoint.health.status.order` (L169) | string | "DOWN,OUT_OF_SERVICE,RECONNECTING,UP,UNKNOWN" | Health · 1 | up front | no |  |

### Questions (answer by number)

None: everything is answered. Create package.json? (yes/no)

### For your information

- 7 shared defaults differ (package kept; "YAML says" in the tables) · 6 values coerced · secret: true added to 8 fields · 0 package fields not in this YAML (kept)
- secret: true added: file-sink/file.sink.sftp_settings.password
- secret: true added: file-sink/file.sink.ftp_settings.password
- coerced: spring.boot.admin.client.instance.prefer-ip: string 'true' coerced to bool
- coerced: spring.boot.admin.client.enabled: string 'true' coerced to bool
- coerced: spring.boot.admin.client.auto-registration: string 'true' coerced to bool
- coerced: spring.boot.admin.client.prefer-ip: string 'true' coerced to bool
- coerced: file.source.sftp_settings.strictHostKeyChecking: unquoted 'no' kept as a string (a YAML 1.1 reader would make it a bool)
- coerced: management.endpoint.health.status.order: list ['DOWN', 'OUT_OF_SERVICE', 'RECONNECTING', 'UP', 'UNKNOWN'] joined with ',' into one string
- reviewed labels kept: 19
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
- removed by you: `file.source.command_center.useoutputdestinationsolacecredentials` (L97): case variant; kept useOutputDestinationSolaceCredentials; drops file-source/file.source.command_center.useoutputdestinationsolacecredentials

### Profile changes saved on "create"

```diff
--- decisions (profile now)
+++ decisions (saved on "create")
@@ -0,0 +1,43 @@
+decisions:
+  removed:
+  - key: file.source.command_center.useoutputdestinationsolacecredentials
+    role: source
+    date: '2026-11-10'
+    reason: case variant; kept useOutputDestinationSolaceCredentials
+  suggestions:
+  - component: application
+    field: spring.application.name
+    attribute: required
+    decision: reject
+    date: '2026-11-10'
+    evidence:
+    - 'notifications/notification-template/NAME: required'
+    - 'scheduler/job/NAME: required'
+  - component: solace
+    field: solace.connector.management.session.host
+    attribute: required
+    decision: accept
+    date: '2026-11-10'
+    evidence:
+    - 'cache/cache-engine/solace.host: required'
+  - component: solace
+    field: solace.java.host
+    attribute: required
+    decision: accept
+    date: '2026-11-10'
+    evidence:
+    - 'cache/cache-engine/solace.host: required'
+  - component: logging
+    field: logging.file.name
+    attribute: required
+    decision: reject
+    date: '2026-11-10'
+    evidence:
+    - 'notifications/notification-template/NAME: required'
+    - 'scheduler/job/NAME: required'
+  style:
+    restyle: accepted
+    date: '2026-11-10'
+  provenance:
+    choice: keep
+    date: '2026-11-10'
```
