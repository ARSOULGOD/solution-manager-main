# Round-trip: file-mesh / source @ v3: PASS

YAML keys: 110 · rendered keys: 113 · equal: 90 · allowed differences: 25 · unexplained: 0 · console drift: 0

## Allowed differences (from report.json / the profile)

- `server.port`: shared default kept: package 8094 vs YAML 8092
- `server.ssl.enabled`: shared default kept: package true vs YAML false
- `server.ssl.key-store-password`: secret: withheld, rendered as a placeholder
- `spring.boot.admin.client.instance.prefer-ip`: coerced: string 'true' coerced to bool
- `spring.boot.admin.client.enabled`: coerced: string 'true' coerced to bool
- `spring.boot.admin.client.auto-registration`: coerced: string 'true' coerced to bool
- `spring.boot.admin.client.prefer-ip`: coerced: string 'true' coerced to bool
- `spring.boot.admin.client.password`: secret: withheld, rendered as a placeholder
- `spring.application.name`: shared default kept: package "SOURCE_TEST_V1" vs YAML "fm-file-source"
- `spring.cloud.stream.bindings.input-0.destination`: shared default kept: package "/home/banala/Music/entityFolders/updated/SOURCE_TEST_V1/file_paths.cfg" vs YAML "/app/external/classpath/file_paths.cfg"
- `spring.cloud.stream.bindings.output-0.destination`: shared default kept: package "file/test" vs YAML "SOURCE/DEFAULT/Alice/FF/NWFILE/SEP15/1"
- `file.source.sftp_settings.password`: secret: withheld, rendered as a placeholder
- `file.source.ftp_settings.password`: secret: withheld, rendered as a placeholder
- `file.source.command_center.useoutputdestinationsolacecredentials`: excluded: FM-D1: lowercase duplicate of useOutputDestinationSolaceCredentials (string 'true' vs bool true).
- `solace.connector.management.queue`: shared default kept: package "" vs YAML "fm-file-source-management"
- `solace.connector.management.session.host`: shared default kept: package "tcp://localhost:55555" vs YAML "tcp://host.docker.internal:55554"
- `solace.connector.management.session.client-password`: secret: withheld, rendered as a placeholder
- `solace.java.host`: shared default kept: package "tcp://localhost:55555" vs YAML "tcp://host.docker.internal:55554"
- `solace.java.client-password`: secret: withheld, rendered as a placeholder
- `management.endpoints.web.exposure.include`: shared default kept: package "*" vs YAML "health,info,metrics"
- `cache.CACHE_ENABLED`: from preserved component cache (no YAML source)
- `cache.CACHE_PROVIDER`: from preserved component cache (no YAML source)
- `cache.CACHE_TTL`: from preserved component cache (no YAML source)
- `cache.REDIS_URL`: from preserved component cache (no YAML source)
- `<renderedPath>`: 109 console renderedPath values start with '.' (empty configPath known defect); the YAML renderer drops the empty segment
