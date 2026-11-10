## file-mesh / sink: PREVIEW (nothing created yet)

- YAML: `application-sink-file-v2.yml`, 102 keys · profile: `profile.yaml` (confirmed)
- Package: `/tmp/claude-1000/-home-arnav-rinawa-Documents-solution-manager-solution-manager-main-main/f006b63b-389c-48c0-9bac-370ecfe1d448/scratchpad/t11/e2e/source/package.json` · packageVersion [1,2,3,4] · appliesTo ">=1.0 <2.0"
- Roles: sink → file-sink (one-of-many) · shared (none): cache, server, spring-boot-admin, spring-cloud-stream, solace, application, logging, management
- Versions: this YAML targets [1,2,3]; every field it feeds carries [1,2,3]
- Samples: `frontend/contracts/packages`: 8 packages, 103 distinct fields (file-mesh itself not used)
- Keys: 102 → 102 placed · 0 removed by you · 0 open

### file-sink (role sink): 48 fields

| YAML key (line) | type | default | section · pos | shown | required | notes |
|---|---|---|---|---|---|---|
| `spring.cloud.stream.bindings.input-0.destination` (L29) | string | "SOURCE/DEFAULT/Alice/FF/NWFILE/SEP15/1" | Input Binding · 0 | up front | no | new |
| `spring.cloud.stream.bindings.input-0.binder` (L30) | enum | "solace" | Input Binding · 1 | up front | no | new |
| `spring.cloud.stream.bindings.output-0.destination` (L32) | string | "/app/external/file-output" | Output Binding · 0 | up front | no | new |
| `spring.cloud.stream.bindings.output-0.binder` (L33) | enum | "file" | Output Binding · 1 | up front | no | new |
| `spring.cloud.stream.solace.default.consumer.queue-name-expression` (L37) | string | "'fm-file-sink-queue'" | Solace Consumer · 0 | up front | no | new |
| `spring.cloud.stream.solace.default.consumer.provision-durable-queue` (L38) | bool | false | Solace Consumer · 1 | up front | no | new |
| `spring.cloud.stream.solace.default.consumer.add-destination-as-subscription-to-queue` (L39) | bool | false | Solace Consumer · 2 | up front | no | new |
| `file.sink.general.adapter_id` (L43) | string | "fm-file-sink" | General · 0 | up front | no | was "SINK_CONN_ID1" |
| `file.sink.general.auto_create_directory` (L44) | int | 1 | General · 1 | up front | no |  |
| `file.sink.general.dest_file_name_type` (L45) | int | 3 | General · 2 | up front | no |  |
| `file.sink.general.storeBackupStateRemotely` (L46) | bool | false | General · 3 | up front | no |  |
| `file.sink.general.state_backup_path` (L47) | string | "./backup_state.cfg" | General · 4 | up front | no |  |
| `file.sink.general.clear_state_on_eod` (L48) | int | 0 | General · 5 | up front | no |  |
| `file.sink.general.eod_time_sec` (L49) | int | -1 | General · 6 | up front | no |  |
| `file.sink.general.alwaysVerifyData` (L50) | bool | true | General · 7 | up front | no |  |
| `file.sink.general.alwaysMatchCompleteEvent` (L51) | bool | true | General · 8 | up front | no |  |
| `file.sink.gcs.enabled` (L53) | int | 0 | GCS · 0 | up front | no |  |
| `file.sink.gcs.projectId` (L54) | string | "GCS_PROJECT_ID" | GCS · 1 | up front | no |  |
| `file.sink.gcs.credentialsFilePath` (L55) | string | "/home/centos/pubsubplus-connector-file-2.2.0/configs/gcs/creds.json" | GCS · 2 | up front | no |  |
| `file.sink.gcs.enableBulkComposeStrategy` (L56) | bool | true | GCS · 3 | up front | no |  |
| `file.sink.gcs.retrySettings.maxAttempts` (L58) | int | 5 | GCS Retry · 0 | up front | no |  |
| `file.sink.gcs.retrySettings.retryDelayMultiplier` (L59) | int | 3 | GCS Retry · 1 | up front | no |  |
| `file.sink.gcs.retrySettings.maxDurationMinutes` (L60) | int | 2 | GCS Retry · 2 | up front | no |  |
| `file.sink.sftp_settings.enabled` (L62) | int | 0 | SFTP · 0 | up front | no |  |
| `file.sink.sftp_settings.ip` (L63) | string | "SFTP_HOST_ADDRESS" | SFTP · 1 | up front | no |  |
| `file.sink.sftp_settings.port` (L64) | int | 22 | SFTP · 2 | up front | no |  |
| `file.sink.sftp_settings.user` (L65) | string | "SFTP_USER" | SFTP · 3 | up front | no |  |
| `file.sink.sftp_settings.password` (L66) | secret | withheld (I6) | SFTP · 4 | up front | no |  |
| `file.sink.sftp_settings.strictHostKeyChecking` (L67) | enum | "no" | SFTP · 5 | up front | no |  |
| `file.sink.ftp_settings.enabled` (L69) | int | 0 | FTP · 0 | up front | no |  |
| `file.sink.ftp_settings.ip` (L70) | string | "FTP_HOST_ADDRESS" | FTP · 1 | up front | no |  |
| `file.sink.ftp_settings.port` (L71) | int | 21 | FTP · 2 | up front | no |  |
| `file.sink.ftp_settings.user` (L72) | string | "FTP_USER" | FTP · 3 | up front | no |  |
| `file.sink.ftp_settings.password` (L73) | secret | withheld (I6) | FTP · 4 | up front | no |  |
| `file.sink.ftp_settings.secured` (L74) | bool | true | FTP · 5 | up front | no |  |
| `file.sink.file_owner_permissions.copy_source_file_mode_permissions` (L76) | int | 0 | File Owner Permissions · 0 | up front | no |  |
| `file.sink.file_owner_permissions.copy_source_file_owner` (L77) | int | 0 | File Owner Permissions · 1 | up front | no |  |
| `file.sink.file_owner_permissions.override_username` (L78) | string | "centos" | File Owner Permissions · 2 | up front | no |  |
| `file.sink.file_owner_permissions.override_group` (L79) | string | "centos" | File Owner Permissions · 3 | up front | no |  |
| `file.sink.command_center.enabled` (L81) | int | 0 | Command Center · 0 | up front | no |  |
| `file.sink.command_center.useOutputDestinationSolaceCredentials` (L82) | bool | true | Command Center · 1 | up front | no |  |
| `file.sink.command_center.solace_base_publish_topic` (L83) | string | "solace/dd/STORE-MANGEMENT/fa-assembler/cc/se/fa/assembler/Frankfurt" | Command Center · 2 | up front | no |  |
| `file.sink.command_center.solace_messaging_mode` (L84) | enum | "PERSISTENT" | Command Center · 3 | up front | no |  |
| `file.sink.command_center.heartbeat_enabled` (L85) | int | 0 | Command Center · 4 | up front | no |  |
| `file.sink.command_center.heartbeat_interval` (L86) | int | 3 | Command Center · 5 | up front | no |  |
| `file.sink.command_center.solace_base_publish_topic_heartbeat` (L87) | string | "solace/dd/STORE-MANGEMENT/fa-assembler/cc/se/fa/assembler/Frankfurt" | Command Center · 6 | up front | no |  |
| `file.sink.command_center.events.fileStart` (L89) | bool | true | Command Center Events · 0 | up front | no |  |
| `file.sink.command_center.events.fileComplete` (L90) | bool | true | Command Center Events · 1 | up front | no |  |

### server (shared): 6 fields

| YAML key (line) | type | default | section · pos | shown | required | notes |
|---|---|---|---|---|---|---|
| `server.port` (L2) | int | 8094 | Server · 0 | up front | no | YAML says 8093 |
| `server.ssl.enabled` (L4) | bool | true | SSL · 0 | up front | no | YAML says false |
| `server.ssl.key-store` (L5) | string | "file:/home/banala/Music/entityFolders/download_binaries/SOURCE_NOSUBORG_Alice_FF_NWFILE_T3/keystore.p12" | SSL · 1 | up front | no |  |
| `server.ssl.key-store-password` (L6) | secret | withheld (I6) | SSL · 2 | up front | no |  |
| `server.ssl.key-store-type` (L7) | string | "PKCS12" | SSL · 3 | up front | no |  |
| `server.ssl.key-alias` (L8) | string | "myalias" | SSL · 4 | up front | no |  |

### spring-boot-admin (shared): 9 fields

| YAML key (line) | type | default | section · pos | shown | required | notes |
|---|---|---|---|---|---|---|
| `spring.boot.admin.client.instance.prefer-ip` (L16) | bool | true | Instance · 0 | up front | no |  |
| `spring.boot.admin.client.instance.management-url` (L17) | string | "http://localIP:${server.port}/actuator" | Instance · 1 | up front | no |  |
| `spring.boot.admin.client.instance.service-url` (L18) | string | "http://localIP:${server.port}/" | Instance · 2 | up front | no |  |
| `spring.boot.admin.client.enabled` (L19) | bool | true | Client · 0 | up front | no |  |
| `spring.boot.admin.client.url` (L20) | string | "http://50.17.36.43:9500/admin" | Client · 1 | up front | no |  |
| `spring.boot.admin.client.auto-registration` (L21) | bool | true | Client · 2 | up front | no |  |
| `spring.boot.admin.client.prefer-ip` (L22) | bool | true | Client · 3 | up front | no |  |
| `spring.boot.admin.client.username` (L23) | string | "user" | Authentication · 0 | up front | no |  |
| `spring.boot.admin.client.password` (L24) | secret | withheld (I6) | Authentication · 1 | up front | no |  |

### solace (shared): 20 fields

| YAML key (line) | type | default | section · pos | shown | required | notes |
|---|---|---|---|---|---|---|
| `solace.connector.security.enabled` (L94) | bool | false | Security · 0 | up front | no |  |
| `solace.connector.error.handle` (L96) | string | "stop_all" | Error · 0 | up front | no |  |
| `solace.connector.workflows.0.enabled` (L99) | bool | true | Workflow · 0 | up front | no |  |
| `solace.connector.workflows.0.acknowledgment.publish-async` (L101) | bool | true | Workflow · 1 | up front | no |  |
| `solace.connector.management.leader-election.mode` (L104) | enum | "standalone" | Leader Election · 0 | up front | no | YAML says "active_standby" |
| `solace.connector.management.leader-election.fail-over.max-attempts` (L106) | int | 3 | Leader Election · 1 | up front | no |  |
| `solace.connector.management.leader-election.fail-over.back-off-initial-interval` (L107) | int | 1000 | Leader Election · 2 | up front | no |  |
| `solace.connector.management.leader-election.fail-over.back-off-max-interval` (L108) | int | 10000 | Leader Election · 3 | up front | no |  |
| `solace.connector.management.leader-election.fail-over.back-off-multiplier` (L109) | int | 2 | Leader Election · 4 | up front | no |  |
| `solace.connector.management.queue` (L110) | string | "" | Management · 0 | up front | no | YAML says "fm-file-sink-management" |
| `solace.connector.management.session.host` (L112) | string | from connection | Management Session · 1 | up front | yes | YAML says "tcp://host.docker.internal:55554"; valueFrom |
| `solace.connector.management.session.msg-vpn` (L113) | string | from connection | Management Session · 2 | up front | no | valueFrom |
| `solace.connector.management.session.client-username` (L114) | string | from connection | Management Session · 3 | up front | no | valueFrom |
| `solace.connector.management.session.client-password` (L115) | secret | withheld (I6) | Management Session · 4 | up front | no | valueFrom |
| `solace.java.host` (L117) | string | from connection | Java Client · 1 | up front | yes | YAML says "tcp://host.docker.internal:55554"; valueFrom |
| `solace.java.msg-vpn` (L118) | string | from connection | Java Client · 2 | up front | no | valueFrom |
| `solace.java.client-username` (L119) | string | from connection | Java Client · 3 | up front | no | valueFrom |
| `solace.java.client-password` (L120) | secret | withheld (I6) | Java Client · 4 | up front | no | valueFrom |
| `solace.java.connectRetries` (L121) | int | -1 | Java Client · 5 | up front | no |  |
| `solace.java.reconnectRetries` (L122) | int | -1 | Java Client · 6 | up front | no |  |

### application (shared): 1 fields

| YAML key (line) | type | default | section · pos | shown | required | notes |
|---|---|---|---|---|---|---|
| `spring.application.name` (L11) | string | "SOURCE_TEST_V1" | Application · 0 | up front | no (suggestion rejected) | YAML says "fm-file-sink" |

### logging (shared): 8 fields

| YAML key (line) | type | default | section · pos | shown | required | notes |
|---|---|---|---|---|---|---|
| `logging.level.root` (L125) | string | "INFO" | Log Levels · 0 | up front | no |  |
| `logging.level.com.solace.spring.cloud.stream.binder` (L126) | string | "INFO" | Log Levels · 3 | up front | no |  |
| `logging.level.com.solacecoe.spring.cloud.stream.binders.file` (L127) | string | "TRACE" | Log Levels · 4 | up front | no |  |
| `logging.file.name` (L129) | string | "myapp.log" | File · 0 | up front | no (suggestion rejected) |  |
| `logging.logback.rollingpolicy.file-name-pattern` (L132) | string | "${LOG_FILE}.%d{yyyy-MM-dd}.%i.gz" | Rolling Policy · 0 | up front | no |  |
| `logging.logback.rollingpolicy.max-file-size` (L133) | string | "100MB" | Rolling Policy · 1 | up front | no |  |
| `logging.logback.rollingpolicy.max-history` (L134) | int | 7 | Rolling Policy · 2 | up front | no |  |
| `logging.logback.rollingpolicy.total-size-cap` (L135) | string | "1GB" | Rolling Policy · 3 | up front | no |  |

### management (shared): 10 fields

| YAML key (line) | type | default | section · pos | shown | required | notes |
|---|---|---|---|---|---|---|
| `management.security.enabled` (L138) | bool | false | Security · 0 | up front | no |  |
| `management.simple.metrics.export.enabled` (L142) | bool | true | Metrics · 0 | up front | no |  |
| `management.endpoints.web.exposure.include` (L146) | string | "*" | Web Endpoints · 0 | up front | no | YAML says "health,info,metrics" |
| `management.endpoints.web.cors.allowed-origins` (L148) | string | "*" | CORS · 0 | up front | no |  |
| `management.endpoints.web.cors.allowed-methods` (L149) | string | "*" | CORS · 1 | up front | no |  |
| `management.endpoints.web.cors.allowed-headers` (L150) | string | "*" | CORS · 2 | up front | no |  |
| `management.endpoint.logfile.external-file` (L153) | string | "myapp.log" | Logfile · 0 | up front | no |  |
| `management.endpoint.prometheus.enabled` (L155) | bool | false | Prometheus · 0 | up front | no |  |
| `management.endpoint.health.show-details` (L157) | string | "ALWAYS" | Health · 0 | up front | no |  |
| `management.endpoint.health.status.order` (L159) | string | "DOWN,OUT_OF_SERVICE,RECONNECTING,UP,UNKNOWN" | Health · 1 | up front | no |  |

### Questions (answer by number)

None: everything is answered. Create package.json? (yes/no)

### For your information

- 8 shared defaults differ (package kept; "YAML says" in the tables) · 5 values coerced · secret: true added to 0 fields · 2 package fields not in this YAML (kept)
- coerced: spring.boot.admin.client.instance.prefer-ip: string 'true' coerced to bool
- coerced: spring.boot.admin.client.enabled: string 'true' coerced to bool
- coerced: spring.boot.admin.client.auto-registration: string 'true' coerced to bool
- coerced: spring.boot.admin.client.prefer-ip: string 'true' coerced to bool
- coerced: management.endpoint.health.status.order: list ['DOWN', 'OUT_OF_SERVICE', 'RECONNECTING', 'UP', 'UNKNOWN'] joined with ',' into one string
- in the package, not in this YAML (kept): logging/logging.level.com.solace.connector.jms
- in the package, not in this YAML (kept): logging/logging.level.com.solace.connector.core
- reviewed labels kept: 16
- component cache preserved untouched (profile `preserve`)
- defaults were taken from the YAML (profile defaults.role_components: yaml). This conflicts with docs/design/dynamic-ui/design.md section 7.3 (UI-D*, PROPOSED): defaults come from field initializers, never from a sample application.yml. Flagged, not resolved.

### Profile changes saved on "create": none yet
