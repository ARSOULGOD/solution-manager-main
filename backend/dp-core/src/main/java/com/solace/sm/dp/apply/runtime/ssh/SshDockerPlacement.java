package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.WriterAccess;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.HostKeyType;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.secret.SecretPurpose;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The declared SSH-to-Docker binding for one placement.
 *
 * <p>The host, user, fingerprint and credential reference come from the target's connection record, which
 * its registrant fixed. The container and the paths may originate in a declare body, but only <b>inside the
 * envelope the target's registrant fixed</b> (B2), which three enforcers check: the service, a database trigger,
 * and — here and in the transport — {@link #allowedConfigRoot} (the record refuses a directory outside it, and
 * the transport's preflight compares the SFTP-canonicalized directory against it). The grammar is enforced here
 * because a table-backed catalog is a second writer. This is intentionally a transport binding, not an engine or
 * frontend type.
 *
 * <p><b>The probe</b> ({@code probe}, plan 010 T6.9) is the placement's copy of the package's declared health probe -
 * {@code (kind, path, hostPort, strength)} - and {@code acquisition} is the instance-level mode that says how it is
 * read ({@code via-ssh}: curl on the host's loopback; {@code direct-http}: the target's registered address, OR-82).
 * A {@code null} probe means the package declares none SM executes, and the adapter then reports
 * {@code NOT_DECLARED} whatever the image's own HEALTHCHECK says (Q-G1 (a)).
 *
 * <p>{@code pinnedHostKeyType} is the type of the pinned key (OR-125 (2)): what the connectors negotiate directly. {@code null}
 * while it is not known (a target registered before {@code sm_data} V027, or by the operator command); the connector then
 * discovers it and reports it to the {@link HostKeyTypeRecorder}.
 *
 * <p>The SSH key is never a field: {@code credentialRef} names a secret that {@link SshCredentialSource}
 * resolves, for one use, in memory, for {@code tenant} (I5).
 */
public record SshDockerPlacement(TenantId tenant, String host, int port, String user, UUID credentialRef,
        String pinnedHostKeyFingerprint, String containerName, String allowedConfigRoot,
        String remoteConfigDirectory, String containerConfigPath, String fileName,
        ConfigLocation.Mechanism mechanism, int retainedGenerations, AcquisitionMode acquisition,
        HealthProbeSpec probe, HostKeyType pinnedHostKeyType, String runtime, SecretPurpose credentialPurpose,
        UUID passphraseRef, String authMethod) {

    /** A placement whose pinned key's type is not known: every binding before OR-125 (2). */
    public SshDockerPlacement(TenantId tenant, String host, int port, String user, UUID credentialRef,
            String pinnedHostKeyFingerprint, String containerName, String allowedConfigRoot,
            String remoteConfigDirectory, String containerConfigPath, String fileName,
            ConfigLocation.Mechanism mechanism, int retainedGenerations, AcquisitionMode acquisition,
            HealthProbeSpec probe) {
        this(tenant, host, port, user, credentialRef, pinnedHostKeyFingerprint, containerName, allowedConfigRoot,
                remoteConfigDirectory, containerConfigPath, fileName, mechanism, retainedGenerations, acquisition, probe,
                null, "docker", SecretPurpose.SSH_PRIVATE_KEY, null, "private-key");
    }

    /** Legacy constructor with a recorded host-key type; legacy targets are Docker targets. */
    public SshDockerPlacement(TenantId tenant, String host, int port, String user, UUID credentialRef,
            String pinnedHostKeyFingerprint, String containerName, String allowedConfigRoot,
            String remoteConfigDirectory, String containerConfigPath, String fileName,
            ConfigLocation.Mechanism mechanism, int retainedGenerations, AcquisitionMode acquisition,
            HealthProbeSpec probe, HostKeyType pinnedHostKeyType) {
        this(tenant, host, port, user, credentialRef, pinnedHostKeyFingerprint, containerName, allowedConfigRoot,
                remoteConfigDirectory, containerConfigPath, fileName, mechanism, retainedGenerations, acquisition, probe,
                pinnedHostKeyType, "docker", SecretPurpose.SSH_PRIVATE_KEY, null, "private-key");
    }

    /** A placement with no executable probe, read over SSH: what every binding was before T6.9. */
    public SshDockerPlacement(TenantId tenant, String host, int port, String user, UUID credentialRef,
            String pinnedHostKeyFingerprint, String containerName, String allowedConfigRoot,
            String remoteConfigDirectory, String containerConfigPath, String fileName,
            ConfigLocation.Mechanism mechanism, int retainedGenerations) {
        this(tenant, host, port, user, credentialRef, pinnedHostKeyFingerprint, containerName, allowedConfigRoot,
                remoteConfigDirectory, containerConfigPath, fileName, mechanism, retainedGenerations,
                AcquisitionMode.VIA_SSH, null, null, "docker", SecretPurpose.SSH_PRIVATE_KEY, null, "private-key");
    }

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,252}[A-Za-z0-9])?");
    private static final Pattern USER = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]{0,31}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]{20,86}={0,2}");
    private static final Pattern PROBE_PATH = Pattern.compile("^/[A-Za-z0-9/_.-]{0,255}$");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("/(?:[A-Za-z0-9._-]+/)*[A-Za-z0-9._-]*");

    public SshDockerPlacement {
        Objects.requireNonNull(tenant, "tenant");
        require(HOST, host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("an SSH port must be between 1 and 65535");
        }
        require(USER, user, "SSH user");
        Objects.requireNonNull(credentialRef, "credential reference");
        require(FINGERPRINT, pinnedHostKeyFingerprint, "pinned host-key fingerprint");
        require(TOKEN, containerName, "container name");
        requirePath(allowedConfigRoot, "allowed config root");
        requirePath(remoteConfigDirectory, "remote config directory");
        if (!insideRoot(remoteConfigDirectory, allowedConfigRoot)) {
            throw new IllegalArgumentException("remote config directory is outside the target's allowed root");
        }
        requirePath(containerConfigPath, "container config path");
        require(TOKEN, fileName, "config file name");
        Objects.requireNonNull(mechanism, "mechanism");
        if (retainedGenerations < 1) {
            throw new IllegalArgumentException("at least one retained generation is required");
        }
        Objects.requireNonNull(acquisition, "acquisition mode");
        if (runtime == null || !(runtime.equals("docker") || runtime.equals("systemd"))) {
            throw new IllegalArgumentException("unsupported runtime");
        }
        if (authMethod == null || !(authMethod.equals("basic") || authMethod.equals("private-key"))) {
            throw new IllegalArgumentException("unsupported SSH authentication method");
        }
        Objects.requireNonNull(credentialPurpose, "credential purpose");
        if (probe != null) {
            if (probe.hostPort() < 1 || probe.hostPort() > 65535) {
                throw new IllegalArgumentException("a probe port must be between 1 and 65535");
            }
            if (!PROBE_PATH.matcher(probe.path()).matches()) {
                throw new IllegalArgumentException("probe path does not match its declared grammar");
            }
        }
    }

    /** Connection-backed placement constructor. Its credential reference is a reusable connection secret. */
    public SshDockerPlacement(TenantId tenant, String host, int port, String user, UUID credentialRef,
            String pinnedHostKeyFingerprint, String containerName, String allowedConfigRoot,
            String remoteConfigDirectory, String containerConfigPath, String fileName,
            ConfigLocation.Mechanism mechanism, int retainedGenerations, AcquisitionMode acquisition,
            HealthProbeSpec probe, SecretPurpose credentialPurpose) {
        this(tenant, host, port, user, credentialRef, pinnedHostKeyFingerprint, containerName, allowedConfigRoot,
                remoteConfigDirectory, containerConfigPath, fileName, mechanism, retainedGenerations, acquisition,
                probe, null, "docker", credentialPurpose, null, "private-key");
    }

    /** Connection-backed placement with an optional encrypted-key passphrase reference. */
    public SshDockerPlacement(TenantId tenant, String host, int port, String user, UUID credentialRef,
            String pinnedHostKeyFingerprint, String containerName, String allowedConfigRoot,
            String remoteConfigDirectory, String containerConfigPath, String fileName,
            ConfigLocation.Mechanism mechanism, int retainedGenerations, AcquisitionMode acquisition,
            HealthProbeSpec probe, SecretPurpose credentialPurpose, UUID passphraseRef) {
        this(tenant, host, port, user, credentialRef, pinnedHostKeyFingerprint, containerName, allowedConfigRoot,
                remoteConfigDirectory, containerConfigPath, fileName, mechanism, retainedGenerations, acquisition,
                probe, null, "docker", credentialPurpose, passphraseRef, "private-key");
    }

    /** Connection-backed placement with the parent connection's authentication method. */
    public SshDockerPlacement(TenantId tenant, String host, int port, String user, UUID credentialRef,
            String pinnedHostKeyFingerprint, String containerName, String allowedConfigRoot,
            String remoteConfigDirectory, String containerConfigPath, String fileName,
            ConfigLocation.Mechanism mechanism, int retainedGenerations, AcquisitionMode acquisition,
            HealthProbeSpec probe, SecretPurpose credentialPurpose, UUID passphraseRef, String authMethod) {
        this(tenant, host, port, user, credentialRef, pinnedHostKeyFingerprint, containerName, allowedConfigRoot,
                remoteConfigDirectory, containerConfigPath, fileName, mechanism, retainedGenerations, acquisition,
                probe, null, "docker", credentialPurpose, passphraseRef, authMethod);
    }

    /** Runtime-aware compatibility constructor used by systemd inspection/tests. */
    public SshDockerPlacement(TenantId tenant, String host, int port, String user, UUID credentialRef,
            String pinnedHostKeyFingerprint, String containerName, String allowedConfigRoot,
            String remoteConfigDirectory, String containerConfigPath, String fileName,
            ConfigLocation.Mechanism mechanism, int retainedGenerations, AcquisitionMode acquisition,
            HealthProbeSpec probe, HostKeyType pinnedHostKeyType, String runtime) {
        this(tenant, host, port, user, credentialRef, pinnedHostKeyFingerprint, containerName, allowedConfigRoot,
                remoteConfigDirectory, containerConfigPath, fileName, mechanism, retainedGenerations, acquisition,
                probe, pinnedHostKeyType, runtime, SecretPurpose.SSH_PRIVATE_KEY, null, "private-key");
    }

    public ConfigLocation location() {
        return new ConfigLocation(containerConfigPath,
                new WriterAccess.RemoteSftp(remoteConfigDirectory), fileName, mechanism);
    }

    /**
     * True when {@code path} is {@code root} or lies beneath it. The boundary is an explicit {@code '/'}, so
     * {@code /srv/smx} is not inside {@code /srv/sm}. Pure string comparison: callers pass an already
     * canonical path when they mean to defeat symbolic links.
     */
    static boolean insideRoot(String path, String root) {
        return path != null && root != null && (path.equals(root) || path.startsWith(root + "/"));
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
