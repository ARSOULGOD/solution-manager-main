package com.solace.sm.dp.api.inventory;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The connection fields of a registered target (OR-28) and its envelope (B2): {@code allowedConfigRoot} and
 * {@code allowedContainerPrefix} bound what a declare body may name, and are immutable once registered.
 * The private key is NOT a field: it travels as a separate {@code char[]} the caller zeroises.
 *
 * <p>{@code hostKeyFingerprint} is the key the transport PINS. {@code otherHostKeyFingerprints} are the fingerprints
 * of the host's OTHER key types (OR-89's residual, plan 010 T7.1): they are never trusted for a connection, only
 * recorded so that the same machine cannot be registered a second time in one organization by pinning a different
 * one of its keys. The registration command fills them by asking the host, or from the operator's explicit list.
 *
 * <p>{@code hostKeyType} is the TYPE of the pinned key (OR-125 (2)), when the caller learned it from a scan of the host:
 * stored beside the fingerprint so every SSH connector negotiates that type directly. {@code null} when it is not known
 * (the operator command scans fingerprints only); a connector then discovers it and records it on the first success. It is
 * never client-supplied: the gateway takes it from the data plane's own fresh scan.
 */
public record TargetConnectionSpec(String host, int port, String sshUser, String hostKeyFingerprint,
        String allowedConfigRoot, String allowedContainerPrefix, Set<String> otherHostKeyFingerprints,
        HostKeyType hostKeyType) {

    public TargetConnectionSpec {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(sshUser, "sshUser");
        Objects.requireNonNull(hostKeyFingerprint, "hostKeyFingerprint");
        Objects.requireNonNull(allowedConfigRoot, "allowedConfigRoot");
        Objects.requireNonNull(allowedContainerPrefix, "allowedContainerPrefix");
        Set<String> others = new LinkedHashSet<>(Objects.requireNonNull(otherHostKeyFingerprints, "other keys"));
        others.remove(hostKeyFingerprint);
        otherHostKeyFingerprints = Set.copyOf(others);
    }

    /** A spec whose pinned key's type is not known: every caller before OR-125 (2). */
    public TargetConnectionSpec(String host, int port, String sshUser, String hostKeyFingerprint,
            String allowedConfigRoot, String allowedContainerPrefix, Set<String> otherHostKeyFingerprints) {
        this(host, port, sshUser, hostKeyFingerprint, allowedConfigRoot, allowedContainerPrefix,
                otherHostKeyFingerprints, null);
    }

    /** A target whose host offers no other key type (or whose other keys are not known to the caller). */
    public TargetConnectionSpec(String host, int port, String sshUser, String hostKeyFingerprint,
            String allowedConfigRoot, String allowedContainerPrefix) {
        this(host, port, sshUser, hostKeyFingerprint, allowedConfigRoot, allowedContainerPrefix, Set.of());
    }

    /** Every fingerprint this registration claims for the host: the pinned one first. */
    public Set<String> allHostKeyFingerprints() {
        Set<String> all = new LinkedHashSet<>();
        all.add(hostKeyFingerprint);
        all.addAll(otherHostKeyFingerprints);
        return all;
    }
}
