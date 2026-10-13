package com.solace.sm.launcher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * What the live collection gate (plan 011 P11-4.4) adds to the walkthrough's own target ({@link WalkthroughTarget}, the
 * {@code sm.walkthrough.ssh.*} properties, reused as they are): the Cache's metrics host port, how long to let it collect,
 * an optional SECOND Cache on the same host (S-G), and whether the disruptive scenarios may run.
 *
 * <p>Properties ({@code -Dsm.walkthrough.metrics....}; {@code run-e2e-metrics-collection.sh} maps {@code SM_E2E_*} onto them):
 * <ul>
 *   <li>{@code minutes} (default 10, 1..180): how long the collector runs before the assertions;</li>
 *   <li>{@code host-port} (default the walkthrough's {@code health-port}): the HOST port the Cache's container port 8081 is
 *       published on, for the Cache the walkthrough already names;</li>
 *   <li>{@code second.container}, {@code second.config-dir}, {@code second.host-port}: all three or none. A second Cache on
 *       the same host: its container name must start with the walkthrough container's name (the registered envelope's
 *       prefix), its directory must lie UNDER the registered config root, and its host port (also its health port) must
 *       differ from the first's. {@code second.mode} ({@code via-ssh} default, or {@code direct-http}) reads it the other
 *       way, which is how both acquisition modes are exercised on one host;</li>
 *   <li>{@code traffic}: exactly {@code yes} sends {@code traffic.count} (default 30, 1..1000) {@code GET /api/cache?key=
 *       sm-attribution-<n>} requests (absent keys: a 404 and a miss, nothing written) to the SECOND Cache only, so its miss
 *       counter moves and attribution can be told by value;</li>
 *   <li>{@code disruptive}: exactly {@code yes} lets the run RESTART one Cache and STOP one (S-F) and start it again. It is
 *       never the default: without it the run only READS.</li>
 * </ul>
 * Every refusal names the offending PROPERTY and never a value.
 */
final class MetricsLiveTarget {

    private static final String P = "sm.walkthrough.metrics.";
    private static final Pattern CONTAINER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");
    private static final Pattern DIRECTORY = Pattern.compile("^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$");

    /** One Cache the run declares: where it lives on the host, which host port its 8081 is on, how SM reads it. */
    record Cache(String container, String configDirectory, int hostPort, String mode) {
        /** The declare body's {@code metricsPorts}: the package's container port 8081 to this Cache's host port. */
        Map<String, Integer> metricsPorts() {
            return Map.of("8081", hostPort);
        }
    }

    private final Duration window;
    private final boolean disruptive;
    private final boolean traffic;
    private final int trafficCount;
    private final List<Cache> caches;

    private MetricsLiveTarget(Duration window, boolean disruptive, boolean traffic, int trafficCount, List<Cache> caches) {
        this.window = window;
        this.disruptive = disruptive;
        this.traffic = traffic;
        this.trafficCount = trafficCount;
        this.caches = List.copyOf(caches);
    }

    static MetricsLiveTarget fromSystem() {
        return parse(System::getProperty, WalkthroughTarget.fromSystem());
    }

    /**
     * @throws IllegalArgumentException naming every missing or malformed PROPERTY (never a value) at once
     */
    static MetricsLiveTarget parse(Function<String, String> properties, WalkthroughTarget base) {
        List<String> problems = new ArrayList<>();
        int minutes = integer(properties, "minutes", 10, 1, 180, problems);
        int firstPort = integer(properties, "host-port", base.healthPort(), 1, 65535, problems);
        boolean disruptive = "yes".equals(value(properties, "disruptive"));
        boolean traffic = "yes".equals(value(properties, "traffic"));
        int trafficCount = integer(properties, "traffic.count", 30, 1, 1000, problems);
        List<Cache> caches = new ArrayList<>();
        caches.add(new Cache(base.container(), base.configDirectory(), firstPort, base.acquisition()));

        String container = value(properties, "second.container");
        String directory = value(properties, "second.config-dir");
        String port = value(properties, "second.host-port");
        if (container != null || directory != null || port != null) {
            if (container == null) {
                problems.add(P + "second.container (required with the other second.* properties)");
            }
            if (directory == null) {
                problems.add(P + "second.config-dir (required with the other second.* properties)");
            }
            if (port == null) {
                problems.add(P + "second.host-port (required with the other second.* properties)");
            }
            if (container != null && (!CONTAINER.matcher(container).matches() || container.equals(base.container())
                    || !container.startsWith(base.container()))) {
                problems.add(P + "second.container (must be a container name that starts with the walkthrough container's "
                        + "name and differs from it: that prefix is the registered envelope)");
            }
            if (directory != null && !underRoot(directory, base.configDirectory())) {
                problems.add(P + "second.config-dir (must lie strictly under the registered config root, no '..')");
            }
            int secondPort = integer(properties, "second.host-port", -1, 1, 65535, problems);
            if (port != null && secondPort > 0 && (secondPort == firstPort || secondPort == base.healthPort())) {
                problems.add(P + "second.host-port (must differ from the first Cache's host port and health port)");
            }
            String mode = value(properties, "second.mode");
            if (mode != null && !mode.equals("via-ssh") && !mode.equals("direct-http")) {
                problems.add(P + "second.mode (via-ssh or direct-http)");
            }
            if (problems.isEmpty()) {
                caches.add(new Cache(container, directory, secondPort, mode == null ? base.acquisition() : mode));
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("the live collection target is incomplete or malformed; fix: "
                    + String.join(", ", problems));
        }
        return new MetricsLiveTarget(Duration.ofMinutes(minutes), disruptive, traffic, trafficCount, caches);
    }

    private static boolean underRoot(String directory, String root) {
        return DIRECTORY.matcher(directory).matches() && !directory.contains("..") && directory.startsWith(root + "/");
    }

    private static String value(Function<String, String> properties, String name) {
        String v = properties.apply(P + name);
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static int integer(Function<String, String> properties, String name, int fallback, int min, int max,
            List<String> problems) {
        String v = value(properties, name);
        if (v == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(v);
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // falls through to the refusal below
        }
        problems.add(P + name + " (not a number between " + min + " and " + max + ")");
        return fallback;
    }

    Duration window() {
        return window;
    }

    /** True only when the operator said {@code yes}: the run may restart and stop containers of the host. */
    boolean disruptive() {
        return disruptive;
    }

    /**
     * True only when the operator said {@code yes}: before the attribution step the run sends {@link #trafficCount()} GETs of
     * absent keys to the SECOND Cache's own loopback port (reads that only miss), so the Caches' miss counters differ.
     */
    boolean traffic() {
        return traffic;
    }

    int trafficCount() {
        return trafficCount;
    }

    /** The Cache the walkthrough names first, then the optional second one. */
    List<Cache> caches() {
        return caches;
    }

    @Override
    public String toString() {
        return "MetricsLiveTarget[" + caches.size() + " cache(s), window " + window + ", disruptive " + disruptive + "]";
    }
}
