package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.secret.SecretException;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretMaterial;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Locale;
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
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

/** SSHJ implementation with a pinned host key and a closed Docker command set. */
final class SshjDockerTransport implements SshDockerTransport, HostLogs {

    private final SshCredentialSource credentials;
    private final TargetAddressPolicy addressPolicy;
    private final HostKeyTypeRecorder recorder;

    SshjDockerTransport(SshCredentialSource credentials, TargetAddressPolicy addressPolicy) {
        this(credentials, addressPolicy, HostKeyTypeRecorder.NONE);
    }

    SshjDockerTransport(SshCredentialSource credentials, TargetAddressPolicy addressPolicy,
            HostKeyTypeRecorder recorder) {
        this.credentials = java.util.Objects.requireNonNull(credentials, "credentials");
        this.addressPolicy = java.util.Objects.requireNonNull(addressPolicy, "addressPolicy");
        this.recorder = java.util.Objects.requireNonNull(recorder, "recorder");
    }

    /**
     * The address a connector connects to (plan 013 T1.2, OQ-3 (d)): the stored host checked by the policy
     * immediately before the connection, and the very address that was checked, still carrying the registered name
     * (MF-R4: sshj's {@code connect(InetAddress, int)} keeps that name for the host-key verifier). A refusal is the
     * fixed classifier {@code target.address.refused}; a name that does not resolve is {@code ssh.connection.failed},
     * exactly as {@code connect(String, int)} reported it before.
     */
    static java.net.InetAddress checkedAddress(TargetAddressPolicy policy, String host) {
        try {
            return policy.connectable(host);
        } catch (TargetAddressRefusedException e) {
            throw failure(TARGET_ADDRESS_REFUSED);
        } catch (java.net.UnknownHostException e) {
            throw failure("ssh.connection.failed");
        }
    }

    /** The classifier of an address the policy refused, at any connector. */
    static final String TARGET_ADDRESS_REFUSED = "target.address.refused";

    private static final int MAX_INSPECT_BYTES = 8 * 1024;
    private static final int MAX_LOG_BYTES = 512 * 1024;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MILLIS = 8_000;
    private static final int OPERATION_TIMEOUT_MILLIS = 15_000;

    /** The classifier kept for call sites with no sharper diagnosis available (logs, file listing). */
    private static final String DOCKER_COMMAND_FAILED = "ssh.docker.command.failed";
    /** {@code ensureDockerAvailable}'s own command failed: the daemon is down, unreachable, or refuses this user. */
    private static final String DOCKER_UNAVAILABLE = "ssh.docker.unavailable";
    /** {@code inspect} failed AFTER {@code ensureDockerAvailable} succeeded: the daemon answers, the name does not. */
    private static final String DOCKER_CONTAINER_MISSING = "ssh.docker.container.missing";
    /** {@code restart}/{@code start}/{@code stop} failed on a container {@code inspect} already found. */
    private static final String DOCKER_LIFECYCLE_FAILED = "ssh.docker.lifecycle.failed";
    /** The host-side {@code test -d && test -w} refused: the declared directory is missing or not writable by this user. */
    private static final String CONFIG_DIRECTORY_UNWRITABLE = "ssh.config.directory.unwritable";
    /** The SSH channel broke mid-command (not a command that ran and returned non-zero). */
    private static final String CONNECTION_LOST = "ssh.connection.lost";

    @Override
    public void ensureDockerAvailable(SshDockerPlacement p) {
        if ("systemd".equals(p.runtime())) {
            return;
        }
        command(p, dockerVersionCommand(), DOCKER_UNAVAILABLE);
    }

    /** The one exec string {@link #ensureDockerAvailable} runs. Package-visible so a test can read it without an SSH server. */
    static String dockerVersionCommand() {
        return "docker version --format '{{.Server.Version}}'";
    }

    @Override
    public ContainerSnapshot inspect(SshDockerPlacement p) {
        return parseContainerSnapshot(p, command(p, inspectCommand(p), DOCKER_CONTAINER_MISSING));
    }

    /**
     * The one {@code docker inspect} exec string. Docker's Go template emits the two characters '\\' and 't' for
     * \\t; a delimiter that none of the strictly grammar-checked identifiers can contain is used instead.
     * Package-visible so a test can read the built string without an SSH server.
     * <p>{@code State.Health} is absent from the JSON of a container with no HEALTHCHECK. A bare {@code .State.Health}
     * (in {@code if} or {@code with} alike) fails with "map has no entry for key Health" on a real daemon; {@code index
     * .State "Health"} yields nil for the missing key, so the {@code not_declared} branch is reachable. Measured against
     * Docker 28.5.1 for no-healthcheck, healthy, stopped-with-healthcheck and nonexistent containers.
     */
    static String inspectCommand(SshDockerPlacement p) {
        if ("systemd".equals(p.runtime())) {
            return systemdShowCommand(p);
        }
        String format = "{{.Id}}|{{.Image}}|{{.State.Running}}|{{.State.StartedAt}}|"
                + "{{if index .State \"Health\"}}{{.State.Health.Status}}{{else}}not_declared{{end}}";
        return "docker inspect --format '" + format + "' -- " + p.containerName();
    }

    /** The closed systemd inspection command. The unit name is grammar-checked before it reaches this template. */
    static String systemdShowCommand(SshDockerPlacement p) {
        return "systemctl show --property=Id --property=ActiveState --property=ActiveEnterTimestamp --value -- "
                + p.containerName();
    }

    /**
     * The only exec string {@link #statusSnapshot} runs. It runs <b>no hashing program</b>: the config file is
     * read back over SFTP and hashed in the DP with the keyed hash (OR-32). A digest computed on the customer
     * host would be unkeyed, and an HMAC there would need the key there.
     */
    static String statusCommand(SshDockerPlacement p) {
        return inspectCommand(p);
    }

    @Override
    public StatusSnapshot statusSnapshot(SshDockerPlacement p) {
        // One connection for both reads: the reason this overrides the interface default is that it removes a
        // separate SSH handshake on every Designer/Governance navigation.
        return withClient(p, ssh -> {
            ContainerSnapshot container = readStatusContainer(p,
                    (command, limit) -> exec(ssh, command, limit, DOCKER_CONTAINER_MISSING));
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                return new StatusSnapshot(container, readRemote(sftp, p, p.configFile()).orElse(null));
            }
        });
    }

    /** Runs one fixed command on the host and returns its bounded output; the test seam for what is executed. */
    @FunctionalInterface
    interface RemoteExec {
        String run(String fixedCommand, int maxOutputBytes);
    }

    /**
     * The status snapshot's ONLY host exec. Every command {@link #statusSnapshot} runs goes through {@code exec}
     * here, so a test records them all and proves none is a hashing program (OR-32).
     */
    static ContainerSnapshot readStatusContainer(SshDockerPlacement p, RemoteExec exec) {
        return parseContainerSnapshot(p, exec.run(statusCommand(p), MAX_INSPECT_BYTES));
    }

    private static ContainerSnapshot parseContainerSnapshot(SshDockerPlacement p, String response) {
        if ("systemd".equals(p.runtime())) {
            return parseSystemdSnapshot(response);
        }
        String[] fields = response.strip().split("\\|", -1);
        if (fields.length != 5 || fields[0].isBlank() || fields[1].isBlank()) {
            throw failure("ssh.docker.inspect.invalid");
        }
        return new ContainerSnapshot(fields[0], fields[1], Boolean.parseBoolean(fields[2]),
                fields[3], fields[4]);
    }

    private static ContainerSnapshot parseSystemdSnapshot(String response) {
        String[] fields = response.strip().split("\\R", -1);
        if (fields.length != 3 || fields[0].isBlank() || fields[1].isBlank()) {
            throw failure("ssh.systemd.inspect.invalid");
        }
        boolean running = "active".equals(fields[1]);
        String startedAt = systemdTimestamp(fields[2]);
        return new ContainerSnapshot(fields[0], "systemd", running, startedAt, "not_declared");
    }

    private static String systemdTimestamp(String value) {
        if (value == null || value.isBlank() || value.equals("n/a")) {
            return "0001-01-01T00:00:00Z";
        }
        try {
            return ZonedDateTime.parse(value.strip(),
                    DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm:ss z", Locale.ENGLISH)).toInstant().toString();
        } catch (DateTimeParseException ignored) {
            return "0001-01-01T00:00:00Z";
        }
    }

    @Override
    public void verifyConfigDirectoryWritable(SshDockerPlacement p) {
        withClient(p, ssh -> {
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                verifyDirectory(over(sftp), p);
            }
            return null;
        });
        // This is a closed command template. The path was validated at binding construction, and
        // no package or browser value reaches it. It adds the Unix permission check SFTP metadata
        // cannot authoritatively provide for the authenticated user.
        command(p, "test -d " + p.remoteConfigDirectory() + " && test -w "
                + p.remoteConfigDirectory(), CONFIG_DIRECTORY_UNWRITABLE);
    }

    @Override
    public Optional<byte[]> readFile(SshDockerPlacement p, String absolutePath) {
        requireDeclaredPath(p, absolutePath);
        return withClient(p, ssh -> {
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                return readRemote(sftp, p, absolutePath);
            }
        });
    }

    /** A bounded SFTP read inside an open client: absent is empty, a non-regular file is refused. */
    private static Optional<byte[]> readRemote(SFTPClient sftp, SshDockerPlacement p, String absolutePath)
            throws IOException {
        requireDeclaredPath(p, absolutePath);
        verifyDirectory(over(sftp), p);
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

    @Override
    public void writeFileAtomically(SshDockerPlacement p, String absolutePath, byte[] content) {
        requireDeclaredPath(p, absolutePath);
        if (content == null || content.length > MAX_FILE_BYTES) {
            throw failure("ssh.config.too.large");
        }
        withClient(p, ssh -> {
            String temporary = absolutePath + ".sm-" + UUID.randomUUID() + ".tmp";
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                verifyDirectory(over(sftp), p);
                writeAtomically(filesOver(sftp), temporary, absolutePath, content);
                return null;
            } catch (IOException e) {
                throw failure("ssh.config.write.failed");
            }
        });
    }

    /**
     * The mode of every file this transport writes (the rendered config, its pre-images and their fingerprints): owner
     * read/write, group read, <b>no "other" bit</b> (X1, plan 014 Q-V13 (b), {@code BE-D61}). Measured on the E2E host
     * 2026-10-09: the Cache container runs as root and reads the 1000:1000 file whatever its mode, so 0640 loses nothing.
     */
    static final int SECRET_FILE_MODE = 0640;

    /** The classifier of a file that carries an "other" permission bit where it must not. */
    static final String MODE_REFUSED = "ssh.config.mode.too.open";

    /** The classifier of a server that rejects the SFTP SETSTAT of the mode (e.g. {@code sftp-server -P setstat,fsetstat}). */
    static final String MODE_UNSUPPORTED = "ssh.config.mode.unsupported";

    /** The classifier of a target whose mode could not be read back AFTER the rename (the target is already replaced). */
    static final String MODE_UNVERIFIED = "ssh.config.mode.unverified";

    /** The SFTP operations {@link #writeAtomically} needs, so a test can supply a recording double. */
    interface RemoteFiles {
        /**
         * Creates {@code path} exclusively, <b>born with {@code mode}</b> (the create request carries the attributes, so
         * the server's umask never makes it more open, not even before the first byte), and writes {@code content}.
         */
        void create(String path, byte[] content, int mode) throws IOException;

        /** SSH_FXP_SETSTAT of the permission bits. */
        void setMode(String path, int mode) throws IOException;

        /** The permission bits (0777 mask) the server reports for {@code path}. */
        int mode(String path) throws IOException;

        /** POSIX atomic replacement; refused, never degraded, when the server lacks the extension. */
        void rename(String from, String to) throws IOException;

        void remove(String path) throws IOException;
    }

    static RemoteFiles filesOver(SFTPClient sftp) {
        return new RemoteFiles() {
            @Override
            public void create(String path, byte[] content, int mode) throws IOException {
                net.schmizz.sshj.sftp.FileAttributes born =
                        new net.schmizz.sshj.sftp.FileAttributes.Builder().withPermissions(mode).build();
                try (RemoteFile file = sftp.open(path, EnumSet.of(OpenMode.CREAT, OpenMode.EXCL, OpenMode.WRITE), born)) {
                    int offset = 0;
                    while (offset < content.length) {
                        int count = Math.min(32 * 1024, content.length - offset);
                        file.write(offset, content, offset, count);
                        offset += count;
                    }
                }
            }

            @Override
            public void setMode(String path, int mode) throws IOException {
                sftp.chmod(path, mode);
            }

            @Override
            public int mode(String path) throws IOException {
                return sftp.stat(path).getMode().getPermissionsMask();
            }

            @Override
            public void rename(String from, String to) throws IOException {
                // OpenSSH's SFTP implementation supports the POSIX atomic replacement extension.
                // Refuse rather than degrading to delete-and-rename when the server does not.
                sftp.rename(from, to, EnumSet.of(RenameFlags.OVERWRITE, RenameFlags.ATOMIC));
            }

            @Override
            public void remove(String path) throws IOException {
                sftp.rm(path);
            }
        };
    }

    /**
     * Create the temp file <b>with</b> {@link #SECRET_FILE_MODE} (X1-1: a create without attributes is 0666&amp;~umask,
     * so the secret would be world-readable between the write and a later chmod), set it again explicitly (the server's
     * umask is not trusted), stat it BEFORE the rename (an "other" bit deletes the temp and refuses
     * {@value #MODE_REFUSED}, so the target is never replaced by a readable file), rename, and stat the target after.
     * A server that rejects the SETSTAT refuses {@value #MODE_UNSUPPORTED}.
     *
     * <p><b>After the rename the target IS replaced.</b> A refusal from the post-rename check ({@value #MODE_REFUSED}
     * or, when the stat itself fails, {@value #MODE_UNVERIFIED}) therefore means the new file is already live: the
     * pre-image rollback applies, nothing is undone here.
     */
    static void writeAtomically(RemoteFiles files, String temporary, String target, byte[] content) throws IOException {
        try {
            files.create(temporary, content, SECRET_FILE_MODE);
            try {
                files.setMode(temporary, SECRET_FILE_MODE);
            } catch (IOException e) {
                throw failure(MODE_UNSUPPORTED);
            }
            if ((files.mode(temporary) & 07) != 0) {
                throw failure(MODE_REFUSED);
            }
            files.rename(temporary, target);
        } catch (IOException | RuntimeException e) {
            try {
                files.remove(temporary);
            } catch (IOException ignored) {
                // A failed cleanup never changes the classified write failure.
            } catch (RuntimeException ignored) {
                // Likewise.
            }
            throw e;
        }
        int after;
        try {
            after = files.mode(target);
        } catch (IOException e) {
            throw failure(MODE_UNVERIFIED);
        }
        if ((after & 07) != 0) {
            throw failure(MODE_REFUSED);
        }
    }

    @Override
    public void deleteFile(SshDockerPlacement p, String absolutePath) {
        requireDeclaredPath(p, absolutePath);
        withClient(p, ssh -> {
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                verifyDirectory(over(sftp), p);
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

    /** At most this many dot-file names are returned: a directory of strangers cannot make the listing unbounded. */
    private static final int MAX_LISTED_NAMES = 4096;

    @Override
    public java.util.List<String> listHiddenFileNames(SshDockerPlacement p) {
        return withClient(p, ssh -> {
            try (SFTPClient sftp = ssh.newSFTPClient()) {
                verifyDirectory(over(sftp), p);
                java.util.List<String> names = new java.util.ArrayList<>();
                for (net.schmizz.sshj.sftp.RemoteResourceInfo entry : sftp.ls(p.remoteConfigDirectory())) {
                    String name = entry.getName();
                    if (entry.isRegularFile() && name.startsWith(".") && names.size() < MAX_LISTED_NAMES) {
                        names.add(name);
                    }
                }
                return java.util.List.copyOf(names);
            } catch (IOException e) {
                throw failure("ssh.config.list.failed");
            }
        });
    }

    @Override
    public boolean restart(SshDockerPlacement p) {
        command(p, "systemd".equals(p.runtime()) ? "systemctl restart -- " + p.containerName()
                : "docker restart -- " + p.containerName(), DOCKER_LIFECYCLE_FAILED);
        return true;
    }

    @Override
    public boolean start(SshDockerPlacement p) {
        command(p, "systemd".equals(p.runtime()) ? "systemctl start -- " + p.containerName()
                : "docker start -- " + p.containerName(), DOCKER_LIFECYCLE_FAILED);
        return true;
    }

    @Override
    public boolean stop(SshDockerPlacement p) {
        if (!inspect(p).running()) {
            return false;
        }
        command(p, "systemd".equals(p.runtime()) ? "systemctl stop -- " + p.containerName()
                : "docker stop -- " + p.containerName(), DOCKER_LIFECYCLE_FAILED);
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
        if ("systemd".equals(p.runtime())) {
            StringBuilder systemd = new StringBuilder("journalctl --no-pager --output=short-iso --lines=").append(tailLines);
            if (after != null) systemd.append(" --since '").append(after).append("'");
            if (before != null) systemd.append(" --until '").append(before).append("'");
            return command(p, systemd.append(" --unit ").append(p.containerName()).toString(), MAX_LOG_BYTES);
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

    /** The most lines one read may ask for: the contract's {@code readLogs} {@code limit} maximum. */
    private static final int MAX_READ_LOG_LINES = 1000;

    @Override
    public String read(SshDockerPlacement p, int limit, Instant after, Instant before) {
        return command(p, logsCommand(p, limit, after, before), MAX_LOG_BYTES);
    }

    /**
     * The one exec string {@link #read} runs (T9.1). Package-visible so a test can read it without an SSH server.
     * Shaped by what was MEASURED (M11, Docker 28.5.1): {@code --since}/{@code --until} are inclusive; {@code --tail N}
     * is applied to the whole log BEFORE {@code --until} (so a tail with an until answers nothing unless the window
     * is in the last N lines) but before-or-after is the same for {@code --since}; the container's stderr arrives on
     * the exec's stderr, so {@code 2>&1} merges it (a failure still exits non-zero). Hence: a tail unless there is a
     * {@code before}, and the caller trims.
     */
    static String logsCommand(SshDockerPlacement p, int limit, Instant after, Instant before) {
        if (limit < 1 || limit > MAX_READ_LOG_LINES || (after != null && before != null && !after.isBefore(before))) {
            throw failure("ssh.docker.logs.limit.refused");
        }
        StringBuilder command = new StringBuilder("docker logs --timestamps");
        if (before == null) {
            command.append(" --tail ").append(limit);
        }
        if (after != null) {
            command.append(" --since '").append(after).append("'");
        }
        if (before != null) {
            command.append(" --until '").append(before).append("'");
        }
        return command.append(" -- ").append(p.containerName()).append(" 2>&1").toString();
    }

    /** The two SFTP reads the directory preflight makes, so a test can supply a double. */
    interface DirectoryView {
        FileMode.Type typeOf(String path) throws IOException;

        String canonicalize(String path) throws IOException;
    }

    static DirectoryView over(SFTPClient sftp) {
        return new DirectoryView() {
            @Override
            public FileMode.Type typeOf(String path) throws IOException {
                return sftp.lstat(path).getType();
            }

            @Override
            public String canonicalize(String path) throws IOException {
                return sftp.canonicalize(path);
            }
        };
    }

    /**
     * B2's third enforcer (M8): the directory, as the host's own SSH_FXP_REALPATH resolves it (symbolic links
     * followed), must be the target's root or lie beneath it. The declared string is never what is compared, or a
     * link placed inside the root would redirect every write. The declared directory must also be that canonical
     * path and a real directory ({@code lstat} reports a link as a link).
     */
    static void verifyDirectory(DirectoryView view, SshDockerPlacement p) throws IOException {
        String canonical = view.canonicalize(p.remoteConfigDirectory());
        if (!SshDockerPlacement.insideRoot(canonical, p.allowedConfigRoot())) {
            throw failure("ssh.config.outside.root");
        }
        if (!p.remoteConfigDirectory().equals(canonical)
                || view.typeOf(p.remoteConfigDirectory()) != FileMode.Type.DIRECTORY) {
            throw failure("ssh.config.directory.invalid");
        }
    }

    private String command(SshDockerPlacement p, String fixedCommand, String onNonZeroExit) {
        return command(p, fixedCommand, MAX_INSPECT_BYTES, onNonZeroExit);
    }

    private String command(SshDockerPlacement p, String fixedCommand, int maxOutputBytes) {
        return command(p, fixedCommand, maxOutputBytes, DOCKER_COMMAND_FAILED);
    }

    private String command(SshDockerPlacement p, String fixedCommand, int maxOutputBytes, String onNonZeroExit) {
        return withClient(p, ssh -> exec(ssh, fixedCommand, maxOutputBytes, onNonZeroExit));
    }

    /**
     * The one place a command actually runs. {@code onNonZeroExit} is the caller's diagnosis of a command that ran
     * and refused (missing container, unwritable directory, a lifecycle verb Docker rejected); an {@link IOException}
     * is a different failure mode entirely — the channel broke before the exit status could even be read — so it is
     * never folded into the caller's classifier.
     */
    private static String exec(SSHClient ssh, String fixedCommand, int maxOutputBytes, String onNonZeroExit) {
        try (Session session = ssh.startSession(); Session.Command command = session.exec(fixedCommand)) {
            String output = bounded(command.getInputStream(), maxOutputBytes);
            command.join();
            if (!Integer.valueOf(0).equals(command.getExitStatus())) {
                throw failure(onNonZeroExit);
            }
            return output;
        } catch (IOException e) {
            throw failure(CONNECTION_LOST);
        }
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

    @Override
    public ProbeResult probe(SshDockerPlacement p, int port, String path, Duration timeout) {
        // Validated first: a refused request opens no session and resolves no credential.
        String command = probeCommand(port, path, timeout);
        return withClient(p, ssh -> execProbe(ssh, command));
    }

    private static final java.util.regex.Pattern PROBE_PATH =
            java.util.regex.Pattern.compile("^/[A-Za-z0-9/_.-]{0,255}$");
    private static final java.util.regex.Pattern PROBE_STATUS = java.util.regex.Pattern.compile("^[0-9]{3}$");

    /**
     * The only exec string a probe runs (M19, measured). {@code --max-time} is the bound; there is no
     * {@code --fail}, so an HTTP error is an answer (exit 0, its status after the body), a refused connection exits
     * 7 and a timeout 28, both with status {@code 000}. Built from an {@code int} and a grammar-checked path:
     * nothing else reaches the shell (SM-D11a P).
     */
    static String probeCommand(int port, String path, Duration timeout) {
        if (port < 1 || port > 65535 || path == null || !PROBE_PATH.matcher(path).matches() || timeout == null
                || timeout.compareTo(Duration.ofSeconds(HostProbe.MAX_TIMEOUT_SECONDS)) > 0
                || timeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw failure("ssh.probe.refused");
        }
        long seconds = (timeout.toMillis() + 999) / 1000;
        return "curl --silent --show-error --max-time " + seconds + " --output - --write-out '\\n%{http_code}' "
                + "http://127.0.0.1:" + port + path;
    }

    /** Splits curl's stdout into body and the trailing status; {@code 000} (no answer) becomes 0. */
    static ProbeResult parseProbe(int exitCode, byte[] output) {
        int newline = -1;
        for (int i = output.length - 1; i >= 0; i--) {
            if (output[i] == '\n') {
                newline = i;
                break;
            }
        }
        if (newline < 0) {
            if (exitCode != 0) {
                return new ProbeResult(exitCode, 0, new byte[0]);
            }
            throw failure("ssh.probe.output.invalid");
        }
        String status = new String(output, newline + 1, output.length - newline - 1, StandardCharsets.US_ASCII);
        if (!PROBE_STATUS.matcher(status).matches()) {
            if (exitCode != 0) {
                return new ProbeResult(exitCode, 0, new byte[0]);
            }
            throw failure("ssh.probe.output.invalid");
        }
        return new ProbeResult(exitCode, Integer.parseInt(status), java.util.Arrays.copyOf(output, newline));
    }

    private static ProbeResult execProbe(SSHClient ssh, String fixedCommand) {
        try (Session session = ssh.startSession(); Session.Command command = session.exec(fixedCommand)) {
            byte[] output = command.getInputStream().readNBytes(HostProbe.MAX_BODY_BYTES + 16);
            if (output.length > HostProbe.MAX_BODY_BYTES + 8) {
                throw failure("ssh.probe.output.too.large");
            }
            command.join();
            Integer exit = command.getExitStatus();
            if (exit == null) {
                throw failure("ssh.docker.command.failed");
            }
            return parseProbe(exit, output);
        } catch (IOException e) {
            throw failure("ssh.docker.command.failed");
        }
    }

    /**
     * Opens the key from memory (M8: {@code SSHClient.loadKeys(String privateKey, String publicKey,
     * PasswordFinder)} — sshj needs the key as a {@code String}, which the JDK cannot zero; the guarantee ends
     * there, see {@link SecretMaterial}). The material is closed as soon as the provider exists, on every path.
     */
    static KeyProvider loadKey(SSHClient ssh, SshCredentialSource credentials, SshDockerPlacement p)
            throws IOException {
        return loadKey(ssh, credentials, p.tenant(), p.credentialRef(), p.credentialPurpose(), p.passphraseRef());
    }

    /** As above, for a caller that has a tenant and a credential reference but no placement (the connection test). */
    static KeyProvider loadKey(SSHClient ssh, SshCredentialSource credentials, com.solace.sm.dp.apply.model.TenantId tenant,
            UUID credentialRef) throws IOException {
        return loadKey(ssh, credentials, tenant, credentialRef, SecretPurpose.SSH_PRIVATE_KEY);
    }

    /** As above, with the secret purpose attached to the placement or connection. */
    static KeyProvider loadKey(SSHClient ssh, SshCredentialSource credentials,
            com.solace.sm.dp.apply.model.TenantId tenant, UUID credentialRef, SecretPurpose purpose) throws IOException {
        return loadKey(ssh, credentials, tenant, credentialRef, purpose, null);
    }

    /** Loads a key and, when present, its passphrase entirely in memory. */
    static KeyProvider loadKey(SSHClient ssh, SshCredentialSource credentials,
            com.solace.sm.dp.apply.model.TenantId tenant, UUID credentialRef, SecretPurpose purpose,
            UUID passphraseRef) throws IOException {
        char[] passphrase = null;
        try {
            if (passphraseRef != null) {
                try (SecretMaterial passphraseMaterial = credentials.passphrase(tenant, passphraseRef, purpose);
                        SecretMaterial material = credentials.privateKey(tenant, credentialRef, purpose)) {
                    passphrase = passphraseMaterial.chars();
                    return ssh.loadKeys(new String(material.bytes(), StandardCharsets.UTF_8), null,
                            fixed(passphrase));
                }
            }
            try (SecretMaterial material = credentials.privateKey(tenant, credentialRef, purpose)) {
                return ssh.loadKeys(new String(material.bytes(), StandardCharsets.UTF_8), null, null);
            }
        } catch (SecretException e) {
            throw failure("ssh.credential.unavailable");
        } catch (IOException | RuntimeException e) {
            // The cause is deliberately dropped: a parser's message can quote key text.
            throw failure("ssh.credential.invalid");
        } finally {
            if (passphrase != null) {
                Arrays.fill(passphrase, '\0');
            }
        }
    }

    private static net.schmizz.sshj.userauth.password.PasswordFinder fixed(char[] passphrase) {
        return new net.schmizz.sshj.userauth.password.PasswordFinder() {
            @Override
            public char[] reqPassword(net.schmizz.sshj.userauth.password.Resource<?> resource) {
                return passphrase;
            }

            @Override
            public boolean shouldRetry(net.schmizz.sshj.userauth.password.Resource<?> resource) {
                return false;
            }
        };
    }

    private <T> T withClient(SshDockerPlacement p, IoFunction<SSHClient, T> operation) {
        // The address is resolved and checked once, before any attempt (the pin may take up to three: W4C-1).
        java.net.InetAddress address = checkedAddress(addressPolicy, p.host());
        // A browser request must never wait forever for a host that has become unreachable after it was declared.
        try (SSHClient ssh = PinnedHostKeyConnect.connect(p.pinnedHostKeyFingerprint(), p.pinnedHostKeyType(),
                CONNECT_TIMEOUT_MILLIS, OPERATION_TIMEOUT_MILLIS, config -> { }, c -> c.connect(address, p.port()),
                c -> { }, () -> false, type -> recorder.learned(p.tenant(), p.pinnedHostKeyFingerprint(), type))) {
            if ("basic".equals(p.authMethod())) {
                char[] password = loadPassword(credentials, p);
                try {
                    ssh.authPassword(p.user(), password);
                } finally {
                    Arrays.fill(password, '\0');
                }
            } else {
                ssh.authPublickey(p.user(), loadKey(ssh, credentials, p));
            }
            return operation.apply(ssh);
        } catch (RuntimeAdapterException e) {
            throw e;
        } catch (IOException e) {
            throw failure("ssh.connection.failed");
        }
    }

    private char[] loadPassword(SshCredentialSource credentials, SshDockerPlacement p) {
        char[] password;
        try (SecretMaterial material = credentials.password(p.tenant(), p.credentialRef(), p.credentialPurpose())) {
            password = material.chars();
        } catch (SecretException e) {
            throw failure("ssh.credential.unavailable");
        }
        return password;
    }

    @FunctionalInterface
    private interface IoFunction<T, R> {
        R apply(T value) throws IOException;
    }

    private static RuntimeAdapterException failure(String token) {
        return new RuntimeAdapterException(new ErrorClass(token));
    }
}
