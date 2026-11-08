# Round-trip: file-mesh / sink @ v3: FAIL

YAML keys: 102 · rendered keys: 105 · equal: 77 · allowed differences: 30 · unexplained: 2 · console drift: 0

## Unexplained differences (bugs in the package or the profile)

- `logging.level.com.solace.connector.jms`: rendered from logging but absent from the YAML
- `logging.level.com.solace.connector.core`: rendered from logging but absent from the YAML

## Allowed differences (from report.json / the profile)

- `server.port`: shared default kept: package 8094 vs YAML 8093
- `server.ssl.enabled`: shared default kept: package true vs YAML false
- `server.ssl.key-store-password`: secret: withheld, rendered as a placeholder
- `spring.application.name`: shared default kept: package "SOURCE_TEST_V1" vs YAML "fm-file-sink"
- `spring.boot.admin.client.instance.prefer-ip`: coerced: string 'true' coerced to bool
- `spring.boot.admin.client.enabled`: coerced: string 'true' coerced to bool
- `spring.boot.admin.client.auto-registration`: coerced: string 'true' coerced to bool
- `spring.boot.admin.client.prefer-ip`: coerced: string 'true' coerced to bool
- `spring.boot.admin.client.password`: secret: withheld, rendered as a placeholder
- `spring.cloud.stream.bindings.input-0.destination`: shared default kept: package "/home/banala/Music/entityFolders/updated/SOURCE_TEST_V1/file_paths.cfg" vs YAML "SOURCE/DEFAULT/Alice/FF/NWFILE/SEP15/1"
- `spring.cloud.stream.bindings.input-0.binder`: shared default kept: package "file" vs YAML "solace"
- `spring.cloud.stream.bindings.output-0.destination`: shared default kept: package "file/test" vs YAML "/app/external/file-output"
- `spring.cloud.stream.bindings.output-0.binder`: shared default kept: package "solace" vs YAML "file"
- `spring.cloud.stream.solace.default.consumer.queue-name-expression`: flagged (decision pending): role-exclusive (FM-D2: the Solace binder consumer defaults appear only in the sink YAML.) but would land in shared component spring-cloud-stream; decide: field role 'sink' in spring-cloud-stream, a new component, or exclude
- `spring.cloud.stream.solace.default.consumer.provision-durable-queue`: flagged (decision pending): role-exclusive (FM-D2: the Solace binder consumer defaults appear only in the sink YAML.) but would land in shared component spring-cloud-stream; decide: field role 'sink' in spring-cloud-stream, a new component, or exclude
- `spring.cloud.stream.solace.default.consumer.add-destination-as-subscription-to-queue`: flagged (decision pending): role-exclusive (FM-D2: the Solace binder consumer defaults appear only in the sink YAML.) but would land in shared component spring-cloud-stream; decide: field role 'sink' in spring-cloud-stream, a new component, or exclude
- `file.sink.sftp_settings.password`: secret: withheld, rendered as a placeholder
- `file.sink.ftp_settings.password`: secret: withheld, rendered as a placeholder
- `solace.connector.management.leader-election.mode`: shared default kept: package "standalone" vs YAML "active_standby"
- `solace.connector.management.queue`: shared default kept: package "" vs YAML "fm-file-sink-management"
- `solace.connector.management.session.host`: shared default kept: package "tcp://localhost:55555" vs YAML "tcp://host.docker.internal:55554"
- `solace.connector.management.session.client-password`: secret: withheld, rendered as a placeholder
- `solace.java.host`: shared default kept: package "tcp://localhost:55555" vs YAML "tcp://host.docker.internal:55554"
- `solace.java.client-password`: secret: withheld, rendered as a placeholder
- `management.endpoints.web.exposure.include`: shared default kept: package "*" vs YAML "health,info,metrics"
- `cache.CACHE_ENABLED`: from preserved component cache (no YAML source)
- `cache.CACHE_PROVIDER`: from preserved component cache (no YAML source)
- `cache.CACHE_TTL`: from preserved component cache (no YAML source)
- `cache.REDIS_URL`: from preserved component cache (no YAML source)
- `<renderedPath>`: 101 console renderedPath values start with '.' (empty configPath known defect); the YAML renderer drops the empty segment
