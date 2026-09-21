package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.WriterAccess;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The declared SSH-to-Docker binding for one placement.
 *
 * <p>All fields come from trusted deployment configuration, never the browser or a package. The
 * grammar is still enforced here because a future table-backed catalog is a second writer. This is
 * intentionally a transport binding, not an engine or frontend type.
 */
public record SshDockerPlacement(String host, int port, String user, Path privateKeyFile,
        String pinnedHostKeyFingerprint, String containerName, String remoteConfigDirectory,
        String containerConfigPath, String fileName, ConfigLocation.Mechanism mechanism,
        int retainedGenerations) {

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,252}[A-Za-z0-9])?");
    private static final Pattern USER = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,31}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]{20,86}={0,2}");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]*");

    public SshDockerPlacement {
        require(HOST, host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("an SSH port must be between 1 and 65535");
        }
        require(USER, user, "SSH user");
        Objects.requireNonNull(privateKeyFile, "private key file");
        require(FINGERPRINT, pinnedHostKeyFingerprint, "pinned host-key fingerprint");
        require(TOKEN, containerName, "container name");
        requirePath(remoteConfigDirectory, "remote config directory");
        requirePath(containerConfigPath, "container config path");
        require(TOKEN, fileName, "config file name");
        Objects.requireNonNull(mechanism, "mechanism");
        if (retainedGenerations < 1) {
            throw new IllegalArgumentException("at least one retained generation is required");
        }
    }

    public ConfigLocation location() {
        return new ConfigLocation(containerConfigPath,
                new WriterAccess.RemoteSftp(remoteConfigDirectory), fileName, mechanism);
    }

    String configFile() {
        return remoteConfigDirectory + "/" + fileName;
    }

    private static void require(Pattern grammar, String value, String label) {
        if (value == null || !grammar.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " does not match its declared grammar");
        }
    }

    private static void requirePath(String value, String label) {
        if (value == null || value.contains("..") || value.indexOf('\\') >= 0
                || !ABSOLUTE_PATH.matcher(value).matches() || value.endsWith("/")) {
            throw new IllegalArgumentException(label + " must be a canonical absolute path");
        }
    }
}
