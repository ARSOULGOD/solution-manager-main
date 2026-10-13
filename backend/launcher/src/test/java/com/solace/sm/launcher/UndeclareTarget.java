package com.solace.sm.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * What the live undeclare gate (plan 012 T5, live gate L5) adds to the walkthrough's own target ({@link WalkthroughTarget},
 * the {@code sm.walkthrough.ssh.*} properties, reused as they are): the SECOND Cache container the gate declares an instance
 * on and then undeclares, which the customer's CI/CD has already deployed (SM deploys nothing, {@code OR-29}; {@code OR-110}
 * allows a second Cache on the E2E host). The first Cache is never declared, written, stopped or restarted by this gate.
 *
 * <p>Properties ({@code -Dsm.walkthrough.undeclare....}; {@code run-e2e-undeclare.sh} maps {@code SM_E2E_*} onto them), all
 * three REQUIRED: {@code second.container} (starts with the walkthrough container's name, the registered envelope's prefix,
 * and differs from it), {@code second.config-dir} (strictly UNDER the registered config root) and {@code second.host-port}
 * (the host port its container port 8081 is published on, both its health port and its metrics host port; differs from the
 * first Cache's). Every refusal names the offending PROPERTY and never a value ({@code I6}).
 */
final class UndeclareTarget {

    private static final String P = "sm.walkthrough.undeclare.";
    private static final Pattern CONTAINER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");
    private static final Pattern DIRECTORY = Pattern.compile("^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$");

    /** The second Cache: where it lives on the host and which host port its 8081 is on. */
    record Second(String container, String configDirectory, int hostPort) {

        int healthPort() {
            return hostPort;
        }

        /** The declare body's {@code metricsPorts}: the package's container port 8081 to this host port. */
        Map<String, Integer> metricsPorts() {
            return Map.of("8081", hostPort);
        }
    }

    private final Second second;

    private UndeclareTarget(Second second) {
        this.second = second;
    }

    static UndeclareTarget fromSystem() {
        return parse(System::getProperty, WalkthroughTarget.fromSystem());
    }

    /** The same shape with SYNTHETIC values for the local rehearsal against an in-memory host: no property is read. */
    static UndeclareTarget rehearsal(WalkthroughTarget base) {
        return new UndeclareTarget(new Second(base.container() + "-2", base.configDirectory() + "/second",
                base.healthPort() + 10_000));
    }

    /** @throws IllegalArgumentException naming every missing or malformed PROPERTY (never a value) at once */
    static UndeclareTarget parse(Function<String, String> properties, WalkthroughTarget base) {
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
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("the live undeclare target is incomplete or malformed; fix: "
                    + String.join(", ", problems));
        }
        return new UndeclareTarget(new Second(container, directory, hostPort));
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

    @Override
    public String toString() {
        return "UndeclareTarget[second container " + second.container() + ", directory " + second.configDirectory()
                + ", host port " + second.hostPort() + "]";
    }
}
