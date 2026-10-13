package com.solace.sm.launcher;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Process and fixture helpers shared by the real-agent suites (plan 015 T4.4). The PKI is {@link AgentGatewayTestPki} (the enrolment CA
 * keystore with its lease-nonce key, the trust bundle and the gateway's server certificate); nothing here reaches a database.
 */
final class AgentTestSupport {

    /** Where the managed integration reads its config in the container, as the Cache package declares it. */
    static final String CONTAINER_CONFIG_DIR = "/app/external/spring/config";
    static final String CONFIG_FILE_NAME = "application.yml";
    /** The container port the Cache package declares for its probe and its metrics (the fixture's {@code 8081}). */
    static final int MANAGEMENT_PORT = 8081;

    private AgentTestSupport() {
    }

    record Result(boolean ok, String output) {
    }

    static Result run(List<String> command, Duration timeout) {
        return run(command, null, timeout);
    }

    static Result run(List<String> command, Path directory, Duration timeout) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            if (directory != null) {
                builder.directory(directory.toFile());
            }
            Process process = builder.start();
            // Read on a thread: a process that outlives the timeout would otherwise hold this call in readAllBytes.
            java.util.concurrent.CompletableFuture<String> output = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            return "";
                        }
                    });
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(false, "timed out: " + output.getNow(""));
            }
            return new Result(process.exitValue() == 0, output.get(10, TimeUnit.SECONDS));
        } catch (IOException | InterruptedException | java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException e) {
            return new Result(false, e.toString());
        }
    }

    static Result buildAgent(Path output) {
        return run(List.of("go", "build", "-o", output.toString(), "./cmd/sm-agent"), Path.of("../../go-agent"),
                Duration.ofMinutes(5));
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * The managed integration, as an operator deploys one (OR-27): a container that opts in with {@code com.solace.sm.instance} and names
     * its config file with {@code com.solace.sm.config-file}, with the config directory bind-mounted and the management port published.
     * It answers the Cache's own probe and metrics paths with fixed JSON ({@code /actuator/health}, {@code /api/cache/stats},
     * {@code /api/cache/size}, {@code /actuator/metrics/process.start.time}, whose value is the second the container last started).
     * {@code --stop-timeout 1}: the loop ignores SIGTERM, so the default 10 s grace would make every restart 10 s long.
     */
    static Result startManagedContainer(String name, String instanceLabel, Path conf) {
        String handler = String.join("\n",
                "#!/bin/sh",
                "read line",
                "path=$(echo \"$line\" | cut -d' ' -f2)",
                "while read h; do [ \"$h\" = \"$(printf '\\r')\" ] && break; done",
                "case \"$path\" in",
                "  /actuator/health*) body='{\"status\":\"UP\"}';;",
                "  /api/cache/size*) body='3';;",
                "  /actuator/metrics/process.start.time) body=\"{\\\"measurements\\\":[{\\\"value\\\":$(cat /start)}]}\";;",
                "  *) body='{\"hitRate\":0.5,\"hitCount\":10,\"missCount\":10,\"evictionCount\":0}';;",
                "esac",
                "printf 'HTTP/1.1 200 OK\\r\\nContent-Type: application/json\\r\\nContent-Length: %d\\r\\nConnection: close\\r\\n\\r\\n%s' \"${#body}\" \"$body\"",
                "");
        String script = "cat > /h.sh <<'EOF'\n" + handler + "EOF\nchmod +x /h.sh\ndate +%s > /start\nexec nc -lk -p " + MANAGEMENT_PORT
                + " -e /h.sh";
        return run(List.of("docker", "run", "-d", "--name", name, "--stop-timeout", "1",
                "--label", "com.solace.sm.instance=" + instanceLabel,
                "--label", "com.solace.sm.config-file=" + CONTAINER_CONFIG_DIR + "/" + CONFIG_FILE_NAME,
                "-e", "SPRING_CONFIG_ADDITIONAL_LOCATION=file:" + CONTAINER_CONFIG_DIR + "/",
                "-v", conf + ":" + CONTAINER_CONFIG_DIR, "-p", "127.0.0.1::" + MANAGEMENT_PORT, AgentLive.ALPINE,
                "sh", "-c", script), Duration.ofSeconds(60));
    }

    static void removeManagedContainer(String name, Path work) {
        run(List.of("docker", "rm", "-f", name), Duration.ofSeconds(30));
        // The container may have written into the bind mount as root; remove through Docker so deletion cannot fail.
        run(List.of("docker", "run", "--rm", "-v", work + ":/w", AgentLive.ALPINE, "sh", "-c", "rm -rf /w/root-*"),
                Duration.ofSeconds(30));
    }

    static void deleteTree(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
