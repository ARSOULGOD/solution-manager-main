package com.solace.sm.dp.apply.runtime.docker;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigFileStore;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Containers shaped like the Cache: an {@code additional-location} on the command line, a
 * bind-mounted external config directory, and a compose-style healthcheck where one is wanted.
 *
 * <p>Every container is registered so a test class can stop exactly what it started — no prune, no
 * blanket cleanup, nothing that could touch another session's containers.
 */
public final class DockerFixtures implements AutoCloseable {

    public static final String CONFIG_PATH = "/app/external/spring/config";
    public static final String FILE_NAME = "application.yml";

    private final List<GenericContainer<?>> started = new ArrayList<>();

    /** The daemon Testcontainers itself uses — never {@code fromEnvironment()}. */
    public static URI daemon() {
        return URI.create(
                DockerClientFactory.instance().getTransportConfig().getDockerHost().toString());
    }

    public static DockerEngineClient client() {
        return DockerEngineClient.at(daemon(), Duration.ofSeconds(5), Duration.ofSeconds(30));
    }

    public static DockerProperties props() {
        return new DockerProperties(daemon(), Duration.ofSeconds(5), Duration.ofSeconds(30), 5);
    }

    public static PlacementRef newPlacement() {
        return new PlacementRef(new TenantId(UUID.randomUUID()), new InstanceId(UUID.randomUUID()),
                new PlacementId(UUID.randomUUID()));
    }

    public static DockerPlacement placement(String namePrefix, Path localDir) {
        return new DockerPlacement(namePrefix, CONFIG_PATH, localDir, FILE_NAME,
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 3);
    }

    public static DockerPlacementCatalog catalogFor(PlacementRef ref, DockerPlacement placement) {
        return p -> {
            if (!p.equals(ref)) {
                throw new IllegalArgumentException("no declared Docker binding for this placement");
            }
            return placement;
        };
    }

    public static ConfigFileStore store() {
        return new ConfigFileStore(3);
    }

    /**
     * A config directory the container will actually see.
     *
     * <p><b>Not {@code @TempDir}, and that is measured, not stylistic.</b> On macOS
     * {@code java.io.tmpdir} is {@code /var/folders/...}; under Colima a bind mount of a directory
     * created there is visible as a mount point but host writes into it do NOT propagate into the
     * container — {@code docker exec ls} shows the directory empty while the host shows the file,
     * and {@code HEAD /archive} answers {@code 404}. A directory under {@code $HOME} (which is what
     * the module's own {@code target/} is) propagates correctly. Measured 2026-09-12, Colima
     * 29.2.1, virtiofs.
     *
     * <p>{@code target/} is gitignored, so nothing here reaches the working tree.
     */
    public static Path newConfigDir() {
        try {
            Path dir = Path.of("target", "it-config", UUID.randomUUID().toString())
                    .toAbsolutePath();
            java.nio.file.Files.createDirectories(dir);
            return dir;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not create an IT config directory");
        }
    }

    /** How a container should be shaped, relative to what SM declares. */
    public enum Shape {
        /** Declares the path on its command line and bind-mounts it. The good case. */
        DECLARED_AND_MOUNTED,
        /** Declares the path but does NOT mount it: SM-D21's round-2 correction. */
        DECLARED_NOT_MOUNTED,
        /** Declares it, mounts it, and also sets SPRING_CONFIG_LOCATION: a competing source. */
        COMPETING_ENV,
        /** Mounts a DIFFERENT host directory from the one SM writes to. */
        MOUNTED_ELSEWHERE
    }

    public GenericContainer<?> start(String name, Path localDir, Shape shape, boolean withHealthcheck) {
        return start(name, localDir, localDir, shape, withHealthcheck);
    }

    public GenericContainer<?> start(String name, Path smWritesTo, Path containerReads, Shape shape,
            boolean withHealthcheck) {
        GenericContainer<?> c = new GenericContainer<>(DockerImageName.parse("busybox:1.36"))
                .withCreateContainerCmdModifier(cmd -> cmd.withName(name));

        List<String> command = new ArrayList<>(
                List.of("sh", "-c", "while true; do sleep 3600; done"));
        if (shape != Shape.MOUNTED_ELSEWHERE) {
            // The extra argument lands in $0 for the shell and in Config.Cmd for the daemon, which
            // is where LaunchSource reads it -- the same place Cache's own flag appears.
            command.add("--spring.config.additional-location=" + CONFIG_PATH + "/");
        } else {
            command.add("--spring.config.additional-location=" + CONFIG_PATH + "/");
        }
        c.withCommand(command.toArray(String[]::new));

        if (shape == Shape.COMPETING_ENV) {
            c.withEnv("SPRING_CONFIG_LOCATION", "/somewhere/else/");
        }
        if (shape != Shape.DECLARED_NOT_MOUNTED) {
            Path source = shape == Shape.MOUNTED_ELSEWHERE ? containerReads : smWritesTo;
            c.withFileSystemBind(source.toAbsolutePath().toString(), CONFIG_PATH,
                    BindMode.READ_WRITE);
        }
        if (withHealthcheck) {
            c.withCreateContainerCmdModifier(cmd -> cmd.withHealthcheck(
                    new com.github.dockerjava.api.model.HealthCheck()
                            .withTest(List.of("CMD-SHELL", "true"))
                            .withInterval(1_000_000_000L)
                            .withRetries(3)));
        }
        c.start();
        started.add(c);
        return c;
    }

    @Override
    public void close() {
        for (GenericContainer<?> c : started) {
            try {
                c.stop();
            } catch (RuntimeException ignored) {
                // Best effort: Ryuk removes anything this JVM started regardless.
            }
        }
        started.clear();
    }
}
