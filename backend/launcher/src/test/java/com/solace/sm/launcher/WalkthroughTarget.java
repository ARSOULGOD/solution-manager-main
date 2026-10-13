package com.solace.sm.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The operator-provided SSH-Docker host a live walkthrough runs against (plan 010 T6.6), read from system properties
 * and one environment variable and from nothing else. There is no default host: without {@link #GATE_PROPERTY} the
 * live ITs are skipped with their reason and the OR-46 claim is not made.
 *
 * <p>Properties ({@code -Dsm.walkthrough....}; the run script maps {@code SM_E2E_*} onto them):
 * <ul>
 *   <li>{@code ssh.host}, {@code ssh.port} (22), {@code ssh.user}, {@code ssh.fingerprint} ({@code SHA256:...}, the
 *       pinned host key), {@code ssh.key-file} (a private-key FILE; its content is read once by the registration
 *       command and zeroised), {@code ssh.container} (the Cache's container name);</li>
 *   <li>{@code config-dir} (the host directory the container's config mount reads: also the registered envelope's
 *       root), {@code container-config-path} (default {@code /app/external/spring/config}), {@code file-name}
 *       (default {@code application.yml}), {@code health-port} (default 8081, the Cache's actuator);</li>
 *   <li>{@code acquisition} (default {@code via-ssh}; {@code direct-http} when the data plane can reach the host's
 *       health port itself): how the declared instance's health is read;</li>
 *   <li>{@code cache.namespace} (default {@code e2e-walkthrough}); optionally the Cache's own broker:
 *       {@code cache.broker.host}, {@code .vpn}, {@code .username}, with the PASSWORD ONLY in the environment variable
 *       {@value #PASSWORD_ENV} (a property carrying one is refused, so it can never reach a command line or a log);</li>
 *   <li>{@code restore.restart} (default true): whether the final restore also restarts the container when a run
 *       restarted it, so the running Cache is back on its original configuration, not only the file.</li>
 * </ul>
 *
 * <p>Every refusal names the offending PROPERTY and never a value (I6). The SSH key is a path here and is never opened
 * by this class; the broker password is returned as a fresh {@code char[]} the caller zeroises and appears in no
 * {@code toString}.
 */
final class WalkthroughTarget {

    static final String GATE_PROPERTY = "sm.walkthrough.ssh.host";
    static final String PASSWORD_ENV = "SM_E2E_BROKER_PASSWORD";

    private static final String P = "sm.walkthrough.";
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,252}[A-Za-z0-9])?$");
    private static final Pattern SSH_USER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_-]{0,31}$");
    private static final Pattern FINGERPRINT = Pattern.compile("^SHA256:[A-Za-z0-9+/]{20,86}={0,2}$");
    private static final Pattern CONFIG_ROOT = Pattern.compile("^/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+$");
    private static final Pattern CONTAINER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");
    private static final Pattern FILE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Pattern ACQUISITION = Pattern.compile("^(via-ssh|direct-http)$");
    private static final Pattern NAMESPACE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");
    private static final Pattern BROKER_TOKEN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.:/-]{0,200}$");

    /** The Cache's own broker settings, secret-free; the password is {@link #brokerPassword()}. */
    record CacheBroker(String host, String vpn, String username) {

        @Override
        public String toString() {
            return "CacheBroker[host=" + host + ", vpn=" + vpn + ", username=" + username + ", password=****]";
        }
    }

    private final String host;
    private final int port;
    private final String user;
    private final String fingerprint;
    private final Path keyFile;
    private final String container;
    private final String configDirectory;
    private final String containerConfigPath;
    private final String fileName;
    private final int healthPort;
    private final String acquisition;
    private final String namespace;
    private final CacheBroker broker;
    private final boolean restartOnRestore;
    private final Function<String, String> environment;

    private WalkthroughTarget(String host, int port, String user, String fingerprint, Path keyFile, String container,
            String configDirectory, String containerConfigPath, String fileName, int healthPort, String acquisition,
            String namespace, CacheBroker broker, boolean restartOnRestore, Function<String, String> environment) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.fingerprint = fingerprint;
        this.keyFile = keyFile;
        this.container = container;
        this.configDirectory = configDirectory;
        this.containerConfigPath = containerConfigPath;
        this.fileName = fileName;
        this.healthPort = healthPort;
        this.acquisition = acquisition;
        this.namespace = namespace;
        this.broker = broker;
        this.restartOnRestore = restartOnRestore;
        this.environment = environment;
    }

    /** Whether a live run was asked for at all: the host property is present and not blank. */
    static boolean isRequested(Function<String, String> properties) {
        String value = properties.apply(GATE_PROPERTY);
        return value != null && !value.isBlank();
    }

    /**
     * The same shape with SYNTHETIC values, for the local rehearsal that runs every scenario against an in-memory host
     * (plan 010 T6.6): no property, no environment variable and no real host is read, and {@code keyFile} is a throwaway
     * file the caller made, whose content is never used to authenticate anywhere. The rehearsal's broker password is a
     * fixed synthetic string so the secret-handling path is exercised without a real secret.
     */
    static WalkthroughTarget rehearsal(Path keyFile) {
        return new WalkthroughTarget("rehearsal-host.example.test", 22, "rehearsal", "SHA256:" + "R".repeat(43),
                keyFile, "sm-e2e-cache", "/srv/sm-e2e-cache", "/app/external/spring/config", "application.yml", 8081,
                "via-ssh", "e2e-rehearsal", new CacheBroker("tcp://cache-broker.rehearsal.invalid:55555",
                        "rehearsal-vpn", "rehearsal-client"), true,
                name -> PASSWORD_ENV.equals(name) ? REHEARSAL_BROKER_PASSWORD : null);
    }

    /** The synthetic broker password the rehearsal stages as a secret; the evidence log must never carry it. */
    static final String REHEARSAL_BROKER_PASSWORD = "rehearsal-cache-broker-secret-0001";

    static WalkthroughTarget fromSystem() {
        return parse(System::getProperty, System::getenv);
    }

    /**
     * @throws IllegalArgumentException naming every missing or malformed PROPERTY (never a value) at once, so a run is
     *         not fixed one refusal at a time
     */
    static WalkthroughTarget parse(Function<String, String> properties, Function<String, String> environment) {
        List<String> problems = new ArrayList<>();
        String host = required(properties, "ssh.host", HOST, problems);
        String user = required(properties, "ssh.user", SSH_USER, problems);
        String fingerprint = required(properties, "ssh.fingerprint", FINGERPRINT, problems);
        String container = required(properties, "ssh.container", CONTAINER, problems);
        String configDirectory = required(properties, "config-dir", CONFIG_ROOT, problems);
        if (configDirectory != null && configDirectory.contains("..")) {
            problems.add(P + "config-dir (must not climb with '..')");
            configDirectory = null;
        }
        int port = port(properties, "ssh.port", 22, problems);
        int healthPort = port(properties, "health-port", 8081, problems);
        String containerConfigPath = optional(properties, "container-config-path", "/app/external/spring/config",
                CONFIG_ROOT, problems);
        String fileName = optional(properties, "file-name", "application.yml", FILE_NAME, problems);
        String namespace = optional(properties, "cache.namespace", "e2e-walkthrough", NAMESPACE, problems);
        String acquisition = optional(properties, "acquisition", "via-ssh", ACQUISITION, problems);
        boolean restartOnRestore = !"false".equalsIgnoreCase(blankToNull(properties.apply(P + "restore.restart")));

        Path keyFile = null;
        String keyValue = blankToNull(properties.apply(P + "ssh.key-file"));
        if (keyValue == null) {
            problems.add(P + "ssh.key-file (required)");
        } else {
            try {
                Path candidate = Path.of(keyValue);
                if (Files.isRegularFile(candidate) && Files.isReadable(candidate)) {
                    keyFile = candidate;
                } else {
                    problems.add(P + "ssh.key-file (not a readable file)");
                }
            } catch (RuntimeException e) {
                problems.add(P + "ssh.key-file (not a path)");
            }
        }

        CacheBroker broker = broker(properties, environment, problems);

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("the live walkthrough target is incomplete or malformed; fix: "
                    + String.join(", ", problems));
        }
        return new WalkthroughTarget(host, port, user, fingerprint, keyFile, container, configDirectory,
                containerConfigPath, fileName, healthPort, acquisition, namespace, broker, restartOnRestore,
                environment);
    }

    private static CacheBroker broker(Function<String, String> properties, Function<String, String> environment,
            List<String> problems) {
        if (blankToNull(properties.apply(P + "cache.broker.password")) != null) {
            problems.add(P + "cache.broker.password (never a property: set " + PASSWORD_ENV
                    + " in the environment)");
        }
        String host = blankToNull(properties.apply(P + "cache.broker.host"));
        String vpn = blankToNull(properties.apply(P + "cache.broker.vpn"));
        String username = blankToNull(properties.apply(P + "cache.broker.username"));
        if (host == null && vpn == null && username == null) {
            return null;
        }
        boolean complete = true;
        if (host == null) {
            problems.add(P + "cache.broker.host (required with the other cache.broker.* properties)");
            complete = false;
        }
        if (vpn == null) {
            problems.add(P + "cache.broker.vpn (required with the other cache.broker.* properties)");
            complete = false;
        }
        if (username == null) {
            problems.add(P + "cache.broker.username (required with the other cache.broker.* properties)");
            complete = false;
        }
        if (!complete) {
            return null;
        }
        boolean wellFormed = true;
        for (String[] pair : new String[][] {{"cache.broker.host", host}, {"cache.broker.vpn", vpn},
                {"cache.broker.username", username}}) {
            if (!BROKER_TOKEN.matcher(pair[1]).matches()) {
                problems.add(P + pair[0] + " (malformed)");
                wellFormed = false;
            }
        }
        if (blankToNull(environment.apply(PASSWORD_ENV)) == null) {
            problems.add(PASSWORD_ENV + " (environment variable; required with the Cache broker, "
                    + "the staged password is a secret reference the walkthrough creates from it)");
            wellFormed = false;
        }
        return wellFormed ? new CacheBroker(host, vpn, username) : null;
    }

    private static String required(Function<String, String> properties, String name, Pattern grammar,
            List<String> problems) {
        String value = blankToNull(properties.apply(P + name));
        if (value == null) {
            problems.add(P + name + " (required)");
            return null;
        }
        if (!grammar.matcher(value.strip()).matches()) {
            problems.add(P + name + " (malformed)");
            return null;
        }
        return value.strip();
    }

    private static String optional(Function<String, String> properties, String name, String fallback,
            Pattern grammar, List<String> problems) {
        String value = blankToNull(properties.apply(P + name));
        if (value == null) {
            return fallback;
        }
        if (!grammar.matcher(value.strip()).matches() || value.contains("..")) {
            problems.add(P + name + " (malformed)");
            return fallback;
        }
        return value.strip();
    }

    private static int port(Function<String, String> properties, String name, int fallback, List<String> problems) {
        String value = blankToNull(properties.apply(P + name));
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed >= 1 && parsed <= 65535) {
                return parsed;
            }
        } catch (NumberFormatException e) {
            // falls through to the refusal below
        }
        problems.add(P + name + " (not a port number)");
        return fallback;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    String user() {
        return user;
    }

    String fingerprint() {
        return fingerprint;
    }

    /** The private-key FILE for {@code TargetConnectionMain}'s key reader; this class never opens it. */
    Path keyFile() {
        return keyFile;
    }

    String container() {
        return container;
    }

    String configDirectory() {
        return configDirectory;
    }

    String containerConfigPath() {
        return containerConfigPath;
    }

    String fileName() {
        return fileName;
    }

    int healthPort() {
        return healthPort;
    }

    /** {@code via-ssh} or {@code direct-http}: the declare body's {@code acquisition.mode}. */
    String acquisition() {
        return acquisition;
    }

    String namespace() {
        return namespace;
    }

    boolean restartOnRestore() {
        return restartOnRestore;
    }

    /** The Cache's own broker, or {@code null} when none is configured (the staged config keeps solace disabled). */
    CacheBroker broker() {
        return broker;
    }

    /** A fresh copy of the broker password from the environment, or {@code null}; the caller zeroises it. */
    char[] brokerPassword() {
        if (broker == null) {
            return null;
        }
        String value = blankToNull(environment.apply(PASSWORD_ENV));
        return value == null ? null : value.toCharArray();
    }

    /** B2: the registered envelope's root is the config directory itself, so a sibling directory is outside it. */
    String allowedConfigRoot() {
        return configDirectory;
    }

    /** B2: the registered envelope's prefix is the container's own name. */
    String allowedContainerPrefix() {
        return container;
    }

    /** What the evidence file may say about the target: no key path, no secret. */
    String describeForEvidence() {
        return "ssh " + user + "@" + host + ":" + port + ", container " + container + ", config " + configDirectory
                + "/" + fileName + " (mounted at " + containerConfigPath + "), health port " + healthPort
                + ", Cache broker " + (broker == null ? "none (solace.enabled=false is staged)"
                        : broker.host() + " vpn " + broker.vpn()
                                // The username is not printed: an operator may reuse it as the password, and the
                                // evidence guard then (rightly) refuses the whole line.
                                + " as the configured broker username");
    }

    @Override
    public String toString() {
        return "WalkthroughTarget[" + describeForEvidence() + "]";
    }
}
