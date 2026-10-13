package com.solace.sm.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * What the live multi-placement gate (plan 008 S5 Step 2, live gate L2) adds to the walkthrough's own target
 * ({@link WalkthroughTarget}, the {@code sm.walkthrough.ssh.*} properties, reused as they are): a SECOND Cache container on
 * the same host, which the customer's CI/CD has already deployed (SM deploys nothing, {@code OR-29}), and whether the
 * disruptive steps may run.
 *
 * <p>Properties ({@code -Dsm.walkthrough.multi....}; {@code run-e2e-multi-placement.sh} maps {@code SM_E2E_*} onto them):
 * <ul>
 *   <li>{@code second.container}, {@code second.config-dir}, {@code second.host-port}: all three REQUIRED. The second
 *       container's name must start with the walkthrough container's name (the registered envelope's prefix) and differ
 *       from it; its directory must lie strictly UNDER the registered config root; its host port (the one the Cache's
 *       container port 8081 is published on, so both its health port and its metrics host port) must differ from the first
 *       Cache's. It needs its own container, its own directory and its own ports, because a placement is one container;</li>
 *   <li>{@code disruptive}: exactly {@code yes} lets the run STOP the second placement, STOP the whole instance and RESTART
 *       both containers, then restore. It is never the default: without it the run only registers, writes the two
 *       configuration files (restored afterwards) and retires. The plan's host is the owner's, and a stop of its Cache is
 *       not something this class does unasked.</li>
 * </ul>
 * Every refusal names the offending PROPERTY and never a value (I6).
 */
final class MultiPlacementTarget {

    /** The walkthrough's own gate: without {@code sm.walkthrough.ssh.host} the live IT is skipped and no host is looked for. */
    static final String GATE_PROPERTY = WalkthroughTarget.GATE_PROPERTY;

    private static final String P = "sm.walkthrough.multi.";
    private static final Pattern CONTAINER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");
    private static final Pattern DIRECTORY = Pattern.compile("^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$");

    /** The second Cache the run adds as a placement: where it lives on the host and which host port its 8081 is on. */
    record Second(String container, String configDirectory, int hostPort) {

        /** A Cache publishes one port (container 8081): the health actuator and the metrics API are both on it. */
        int healthPort() {
            return hostPort;
        }

        /** The {@code addPlacement} body's {@code metricsPorts}: the package's container port 8081 to this host port. */
        Map<String, Integer> metricsPorts() {
            return Map.of("8081", hostPort);
        }
    }

    private final Second second;
    private final boolean disruptive;

    private MultiPlacementTarget(Second second, boolean disruptive) {
        this.second = second;
        this.disruptive = disruptive;
    }

    static MultiPlacementTarget fromSystem() {
        return parse(System::getProperty, WalkthroughTarget.fromSystem());
    }

    /**
     * The same shape with SYNTHETIC values, for the local rehearsal against an in-memory host: no property is read. Valid
     * against {@link WalkthroughTarget#rehearsal}, and disruptive, because nothing real can be disturbed.
     */
    static MultiPlacementTarget rehearsal(WalkthroughTarget base) {
        return new MultiPlacementTarget(new Second(base.container() + "-2", base.configDirectory() + "/second",
                base.healthPort() + 10_000), true);
    }

    /**
     * @throws IllegalArgumentException naming every missing or malformed PROPERTY (never a value) at once
     */
    static MultiPlacementTarget parse(Function<String, String> properties, WalkthroughTarget base) {
        List<String> problems = new ArrayList<>();
        String container = value(properties, "second.container");
        String directory = value(properties, "second.config-dir");
        String port = value(properties, "second.host-port");

        if (container == null) {
            problems.add(P + "second.container (required)");
        } else if (!CONTAINER.matcher(container).matches() || container.equals(base.container())
                || !container.startsWith(base.container())) {
            problems.add(P + "second.container (must be a container name that starts with the walkthrough container's "
                    + "name and differs from it: that prefix is the registered envelope)");
            container = null;
        }
        if (directory == null) {
            problems.add(P + "second.config-dir (required)");
        } else if (!underRoot(directory, base.configDirectory())) {
            problems.add(P + "second.config-dir (must lie strictly under the registered config root, no '..')");
            directory = null;
        }
        int hostPort = -1;
        if (port == null) {
            problems.add(P + "second.host-port (required)");
        } else {
            hostPort = port(port);
            if (hostPort < 0) {
                problems.add(P + "second.host-port (not a port number)");
            } else if (hostPort == base.healthPort()) {
                problems.add(P + "second.host-port (must differ from the first Cache's host port and health port)");
                hostPort = -1;
            }
        }
        String disruptiveValue = value(properties, "disruptive");
        boolean disruptive = "yes".equals(disruptiveValue);
        if (disruptiveValue != null && !disruptive) {
            problems.add(P + "disruptive (exactly 'yes' or unset: it stops and restarts containers)");
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("the live multi-placement target is incomplete or malformed; fix: "
                    + String.join(", ", problems));
        }
        return new MultiPlacementTarget(new Second(container, directory, hostPort), disruptive);
    }

    private static boolean underRoot(String directory, String root) {
        return DIRECTORY.matcher(directory).matches() && !directory.contains("..") && directory.startsWith(root + "/");
    }

    private static int port(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 1 && parsed <= 65535 ? parsed : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String value(Function<String, String> properties, String name) {
        String v = properties.apply(P + name);
        return v == null || v.isBlank() ? null : v.strip();
    }

    Second second() {
        return second;
    }

    boolean disruptive() {
        return disruptive;
    }

    @Override
    public String toString() {
        return "MultiPlacementTarget[second container " + second.container() + ", directory " + second.configDirectory()
                + ", host port " + second.hostPort() + ", disruptive " + disruptive + "]";
    }
}
