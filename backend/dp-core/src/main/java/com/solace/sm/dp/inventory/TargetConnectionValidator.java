package com.solace.sm.dp.inventory;

import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import java.util.regex.Pattern;

/**
 * The field grammars of a target connection, in ONE place (plan 013 T1.1): the operator command
 * ({@code TargetConnectionMain}) and the service ({@link InventoryService#register}) both ask it, so a rule changed
 * here cannot be left behind in a copy. They are V007's CHECKs for {@code sm_data.target_connection} (and
 * {@code SshDockerPlacement}'s patterns), stated once for code: the database remains the second enforcer, and the
 * refusal here is a clear message rather than a constraint name.
 *
 * <p>The predicates take the value and answer; they never echo it. {@link #validate} names the FIELD of the first
 * violation, never its value, so the text is safe to return to a caller (I6 is about keys, but a host or a user is
 * not worth echoing either).
 *
 * <p>The envelope fields ({@code allowedConfigRoot}, {@code allowedContainerPrefix}) are B2's: {@code /} is no
 * envelope and neither is a path that climbs out of one. They are immutable once registered.
 */
public final class TargetConnectionValidator {

    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,252}[A-Za-z0-9])?$");
    private static final Pattern SSH_USER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_-]{0,31}$");
    private static final Pattern FINGERPRINT = Pattern.compile("^SHA256:[A-Za-z0-9+/]{20,86}={0,2}$");
    private static final Pattern CONFIG_ROOT = Pattern.compile("^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$");
    private static final Pattern CONTAINER_PREFIX = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

    private TargetConnectionValidator() {
    }

    public static boolean isHost(String value) {
        return value != null && HOST.matcher(value).matches();
    }

    public static boolean isSshUser(String value) {
        return value != null && SSH_USER.matcher(value).matches();
    }

    /** {@code SHA256:<base64>}: a pin, or one of the host's other key fingerprints. */
    public static boolean isFingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    public static boolean isConfigRoot(String value) {
        return value != null && CONFIG_ROOT.matcher(value).matches() && !value.contains("..");
    }

    public static boolean isContainerPrefix(String value) {
        return value != null && CONTAINER_PREFIX.matcher(value).matches();
    }

    public static boolean isPort(int value) {
        return value >= 1 && value <= 65535;
    }

    /**
     * @throws IllegalArgumentException naming the first offending field (never its value)
     */
    public static void validate(TargetConnectionSpec spec) {
        if (!isHost(spec.host())) {
            throw new IllegalArgumentException("host is not a host name or address");
        }
        if (!isPort(spec.port())) {
            throw new IllegalArgumentException("port is not a port number");
        }
        if (!isSshUser(spec.sshUser())) {
            throw new IllegalArgumentException("sshUser is not a login name");
        }
        if (!isFingerprint(spec.hostKeyFingerprint())) {
            throw new IllegalArgumentException("hostKeyFingerprint is not SHA256:<base64>");
        }
        if (!isConfigRoot(spec.allowedConfigRoot())) {
            throw new IllegalArgumentException("allowedConfigRoot is not an absolute directory below the filesystem "
                    + "root (not '/'), with no '..' segment");
        }
        if (!isContainerPrefix(spec.allowedContainerPrefix())) {
            throw new IllegalArgumentException("allowedContainerPrefix is not a non-empty container name prefix");
        }
        for (String other : spec.otherHostKeyFingerprints()) {
            if (!isFingerprint(other)) {
                throw new IllegalArgumentException("otherHostKeyFingerprints holds a value that is not SHA256:<base64>");
            }
        }
    }
}
