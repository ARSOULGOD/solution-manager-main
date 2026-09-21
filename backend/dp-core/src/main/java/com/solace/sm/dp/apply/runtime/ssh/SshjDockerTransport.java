package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.FileMode;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RenameFlags;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.FingerprintVerifier;

/** SSHJ implementation with a pinned host key and a closed Docker command set. */
final class SshjDockerTransport implements SshDockerTransport {

    private static final int MAX_INSPECT_BYTES = 8 * 1024;
    private static final int MAX_LOG_BYTES = 512 * 1024;
    private static final int MAX_METRIC_BYTES = 16 * 1024;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 8_000;
    private static final int OPERATION_TIMEOUT_MILLIS = 15_000;

    @Override
    public ContainerSnapshot inspect(SshDockerPlacement p) {
        // Docker's Go template emits the two characters '\\' and 't' for \\t; use a delimiter that
        // none of the strictly grammar-checked identifiers can contain instead.
        String format = "{{.Id}}|{{.Image}}|{{.State.Running}}|{{.State.StartedAt}}|"
                + "{{if .State.Health}}{{.State.Health.Status}}{{else}}not_declared{{end}}";
        String response = command(p, "docker inspect --format '" + format + "' -- "
                + p.containerName());
        return parseContainerSnapshot(response);
    }

    @Override
    public StatusSnapshot statusSnapshot(SshDockerPlacement p) {
        String separator = "__SM_STATUS_SEPARATOR__";
        String format = "{{.Id}}|{{.Image}}|{{.State.Running}}|{{.State.StartedAt}}|"
                + "{{if .State.Health}}{{.State.Health.Status}}{{else}}not_declared{{end}}";
        // The command only contains grammar-checked placement fields and fixed
        // operations. It eliminates separate SSH sessions for inspect and SFTP
        // reads on every Designer/Governance navigation.
        String output = command(p, "docker inspect --format '" + format + "' -- " + p.containerName()
                + "; printf '\\n" + separator + "\\n'; if test -f " + p.configFile()
                + "; then sha256sum -- " + p.configFile() + " | awk '{print $1}'; else printf '__SM_CONFIG_ABSENT__'; fi",
                MAX_INSPECT_BYTES);
        String[] values = output.split("\\n" + separator + "\\n", -1);
        if (values.length != 2) {
            throw failure("ssh.docker.status.invalid");
        }
        String hash = values[1].strip();
        if ("__SM_CONFIG_ABSENT__".equals(hash)) {
            hash = null;
        } else if (!hash.matches("[0-9a-f]{64}")) {
            throw failure("ssh.docker.status.invalid");
        }
        return new StatusSnapshot(parseContainerSnapshot(values[0]), hash == null ? null : "sha256:" + hash);
    }

    private static ContainerSnapshot parseContainerSnapshot(String response) {
        String[] fields = response.strip().split("\\|", -1);
        if (fields.length != 5 || fields[0].isBlank() || fields[1].isBlank()) {
            throw failure("ssh.docker.inspect.invalid");
        }
        return new ContainerSnapshot(fields[0], fields[1], Boolean.parseBoolean(fields[2]),
                fields[3], fields[4]);
    }

    @Override
    public void verifyConfigDirectoryWritable(SshDockerPlacement p) {
        withClient(p, ssh -> {
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                verifyDirectory(sftp, p);
            }
            return null;
        });
        // This is a closed command template. The path was validated at binding construction, and
        // no package or browser value reaches it. It adds the Unix permission check SFTP metadata
        // cannot authoritatively provide for the authenticated user.
        command(p, "test -d " + p.remoteConfigDirectory() + " && test -w "
                + p.remoteConfigDirectory());
    }

    @Override
    public Optional<byte[]> readFile(SshDockerPlacement p, String absolutePath) {
        requireDeclaredPath(p, absolutePath);
        return withClient(p, ssh -> {
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                verifyDirectory(sftp, p);
                FileAttributes attributes = sftp.statExistence(absolutePath);
                if (attributes == null) {
                    return Optional.empty();
                }
                if (attributes.getType() != FileMode.Type.REGULAR) {
                    throw failure("ssh.config.not.regular");
                }
                long length = sftp.size(absolutePath);
                if (length > MAX_FILE_BYTES) {
                    throw failure("ssh.config.too.large");
                }
                byte[] bytes = new byte[(int) length];
                try (RemoteFile file = sftp.open(absolutePath, EnumSet.of(OpenMode.READ))) {
                    int offset = 0;
                    while (offset < bytes.length) {
                        int read = file.read(offset, bytes, offset, bytes.length - offset);
                        if (read <= 0) {
                            throw failure("ssh.config.read.incomplete");
                        }
                        offset += read;
                    }
                }
                return Optional.of(bytes);
            }
        });
    }

    @Override
    public void writeFileAtomically(SshDockerPlacement p, String absolutePath, byte[] content) {
        requireDeclaredPath(p, absolutePath);
        if (content == null || content.length > MAX_FILE_BYTES) {
            throw failure("ssh.config.too.large");
        }
        withClient(p, ssh -> {
            String temporary = absolutePath + ".sm-" + UUID.randomUUID() + ".tmp";
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                verifyDirectory(sftp, p);
                try {
                    try (RemoteFile file = sftp.open(temporary,
                            EnumSet.of(OpenMode.CREAT, OpenMode.EXCL, OpenMode.WRITE))) {
                        int offset = 0;
                        while (offset < content.length) {
                            int count = Math.min(32 * 1024, content.length - offset);
                            file.write(offset, content, offset, count);
                            offset += count;
                        }
                    }
                    // OpenSSH's SFTP implementation supports the POSIX atomic replacement extension.
                    // Refuse rather than degrading to delete-and-rename when the server does not.
                    sftp.rename(temporary, absolutePath,
                            EnumSet.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC));
                } catch (IOException e) {
                    try {
                        sftp.rm(temporary);
                    } catch (IOException ignored) {
                        // A failed cleanup never changes the classified write failure.
                    }
                    throw e;
                }
                return null;
            } catch (IOException e) {
                throw failure("ssh.config.write.failed");
            }
        });
    }

    @Override
    public void deleteFile(SshDockerPlacement p, String absolutePath) {
        requireDeclaredPath(p, absolutePath);
        withClient(p, ssh -> {
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                verifyDirectory(sftp, p);
                try {
                    sftp.rm(absolutePath);
                } catch (java.io.FileNotFoundException ignored) {
                    // Removing an already absent target is the required idempotent result.
                }
                return null;
            } catch (IOException e) {
                throw failure("ssh.config.delete.failed");
            }
        });
    }

    @Override
    public boolean restart(SshDockerPlacement p) {
        command(p, "docker restart -- " + p.containerName());
        return true;
    }

    @Override
    public boolean stop(SshDockerPlacement p) {
        if (!inspect(p).running()) {
            return false;
        }
        command(p, "docker stop -- " + p.containerName());
        return true;
    }

    @Override
    public String logs(SshDockerPlacement p, int tailLines) {
        return logs(p, tailLines, null, null);
    }

    @Override
    public String logs(SshDockerPlacement p, int tailLines, Instant after, Instant before) {
        if (tailLines < 1 || tailLines > 501 || (after != null && before != null)) {
            throw failure("ssh.docker.logs.limit.refused");
        }
        StringBuilder command = new StringBuilder("docker logs --timestamps --tail ").append(tailLines);
        if (after != null) {
            command.append(" --since '").append(after).append("'");
        }
        if (before != null) {
            command.append(" --until '").append(before).append("'");
        }
        return command(p, command.append(" -- ").append(p.containerName()).toString(), MAX_LOG_BYTES);
    }

    @Override
    public String cacheStats(SshDockerPlacement p) {
        return command(p, "curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8081/api/cache/stats",
                MAX_METRIC_BYTES);
    }

    @Override
    public String cacheSize(SshDockerPlacement p) {
        return command(p, "curl --fail --silent --show-error --max-time 5 "
                + "'http://127.0.0.1:8081/api/cache/size?filterByNamespace=false'", MAX_METRIC_BYTES);
    }

    @Override
    public String heapUsed(SshDockerPlacement p) {
        return command(p, "curl --fail --silent --show-error --max-time 5 "
                + "'http://127.0.0.1:8081/actuator/metrics/jvm.memory.used?tag=area:heap'", MAX_METRIC_BYTES);
    }

    @Override
    public CacheMetricResponses cacheMetrics(SshDockerPlacement p) {
        // Every URL and delimiter is fixed in source. This remains a closed command
        // set while avoiding three separate SSH handshakes for one live sample.
        String separator = "__SM_CACHE_METRIC_SEPARATOR__";
        String output = command(p, "set -e; "
                + "curl --fail --silent --show-error --max-time 5 http://127.0.0.1:8081/api/cache/stats; "
                + "printf '\\n" + separator + "\\n'; "
                + "curl --fail --silent --show-error --max-time 5 "
                + "'http://127.0.0.1:8081/api/cache/size?filterByNamespace=false'; "
                + "printf '\\n" + separator + "\\n'; "
                + "curl --fail --silent --show-error --max-time 5 "
                + "'http://127.0.0.1:8081/actuator/metrics/jvm.memory.used?tag=area:heap'", MAX_METRIC_BYTES);
        String[] responses = output.split("\\n" + separator + "\\n", -1);
        if (responses.length != 3) {
            throw failure("ssh.metrics.response.invalid");
        }
        return new CacheMetricResponses(responses[0], responses[1], responses[2]);
    }

    private static void verifyDirectory(SFTPClient sftp, SshDockerPlacement p) throws IOException {
        if (sftp.lstat(p.remoteConfigDirectory()).getType() != FileMode.Type.DIRECTORY
                || !p.remoteConfigDirectory().equals(sftp.canonicalize(p.remoteConfigDirectory()))) {
            throw failure("ssh.config.directory.invalid");
        }
    }

    private String command(SshDockerPlacement p, String fixedCommand) {
        return command(p, fixedCommand, MAX_INSPECT_BYTES);
    }

    private String command(SshDockerPlacement p, String fixedCommand, int maxOutputBytes) {
        return withClient(p, ssh -> {
            try (Session session = ssh.startSession(); Session.Command command = session.exec(fixedCommand)) {
                String output = bounded(command.getInputStream(), maxOutputBytes);
                command.join();
                if (!Integer.valueOf(0).equals(command.getExitStatus())) {
                    throw failure("ssh.docker.command.failed");
                }
                return output;
            } catch (IOException e) {
                throw failure("ssh.docker.command.failed");
            }
        });
    }

    private static String bounded(InputStream source, int limit) throws IOException {
        byte[] bytes = source.readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw failure("ssh.docker.output.too.large");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void requireDeclaredPath(SshDockerPlacement p, String path) {
        if (path == null || !path.startsWith(p.remoteConfigDirectory() + "/")
                || path.contains("..") || path.indexOf('\\') >= 0) {
            throw failure("ssh.config.path.refused");
        }
    }

    private static <T> T withClient(SshDockerPlacement p, IoFunction<SSHClient, T> operation) {
        try (SSHClient ssh = new SSHClient()) {
            // A browser request must never wait forever for a host that has
            // become unreachable after it was declared.
            ssh.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            ssh.setTimeout(OPERATION_TIMEOUT_MILLIS);
            ssh.addHostKeyVerifier(FingerprintVerifier.getInstance(p.pinnedHostKeyFingerprint()));
            ssh.connect(p.host(), p.port());
            ssh.authPublickey(p.user(), ssh.loadKeys(p.privateKeyFile().toString()));
            return operation.apply(ssh);
        } catch (RuntimeAdapterException e) {
            throw e;
        } catch (IOException e) {
            throw failure("ssh.connection.failed");
        }
    }

    @FunctionalInterface
    private interface IoFunction<T, R> {
        R apply(T value) throws IOException;
    }

    private static RuntimeAdapterException failure(String token) {
        return new RuntimeAdapterException(new ErrorClass(token));
    }
}
