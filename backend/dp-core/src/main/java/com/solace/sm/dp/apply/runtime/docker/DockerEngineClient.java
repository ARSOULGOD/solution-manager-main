package com.solace.sm.dp.apply.runtime.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * The only class that speaks HTTP to the Docker daemon.
 *
 * <p><b>The API version is pinned in the path, not negotiated.</b> Measured 2026-09-11 against
 * Engine 28.5.1 (API 1.51): {@code /v1.43/...} is served, and a version the daemon does not support
 * is a clean {@code 400} rather than a surprise. Docker's own back-compatibility guarantee is that a
 * newer daemon serves older versioned paths; pinning turns "which daemon is this" into a constant.
 *
 * <p><b>No response body ever escapes this class</b> ({@code I6}). A daemon error body echoes
 * container names, mount paths and sometimes the command line. Non-2xx becomes a
 * {@link RuntimeAdapterException} carrying a {@link DockerErrors} classifier and no cause; the body
 * is drained and discarded without being read into a string.
 */
public final class DockerEngineClient implements Closeable {

    /**
     * The API version this client would like. Every call it makes existed long before this, so the
     * number is a floor of convenience, not of capability.
     */
    private static final int[] PREFERRED = {1, 43};

    private final DockerHttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    /** Resolved on first use from the daemon's own advertised window. Never guessed. */
    private volatile String version;

    private DockerEngineClient(DockerHttpClient http) {
        this.http = http;
    }

    public static DockerEngineClient at(URI dockerHost, Duration connect, Duration response) {
        DockerProperties.requireLocationOnly(dockerHost);
        return new DockerEngineClient(new ZerodepDockerHttpClient.Builder()
                .dockerHost(dockerHost)
                .connectionTimeout(connect)
                .responseTimeout(response)
                .build());
    }

    /** {@code DOCKER_HOST} when set, else the platform's default socket. */
    public static DockerEngineClient fromEnvironment(Duration connect, Duration response) {
        String host = System.getenv("DOCKER_HOST");
        return at(URI.create(host == null || host.isBlank() ? "unix:///var/run/docker.sock" : host),
                connect, response);
    }

    public JsonNode inspect(String container) {
        return getJson(v() + "/containers/" + encode(container) + "/json", "inspect");
    }

    public List<JsonNode> list(String nameFilter) {
        String filters = "{\"name\":[\"" + nameFilter + "\"]}";
        JsonNode array = getJson(v() + "/containers/json?all=true&filters=" + encode(filters), "list");
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    /** @return {@code true} when the daemon acted ({@code 204}). */
    public boolean restart(String container, int timeoutSeconds) {
        return act(v() + "/containers/" + encode(container) + "/restart?t=" + timeoutSeconds, "restart");
    }

    /** @return {@code true} on {@code 204}; {@code false} on {@code 304} — already stopped. */
    public boolean stop(String container, int timeoutSeconds) {
        return act(v() + "/containers/" + encode(container) + "/stop?t=" + timeoutSeconds, "stop");
    }

    /**
     * Stats a path <b>inside</b> the container, from the container's own view of its filesystem.
     *
     * <p>This is the mount-identity probe {@code prepare} needs and the reason no {@code exec} is
     * required: {@code HEAD /archive} answers with a base64 {@code X-Docker-Container-Path-Stat}
     * header and no body, measured, and {@code 404} when the path is not there.
     */
    public Optional<PathStat> statInContainer(String container, String path) {
        // MEASURED: DockerHttpClient.Request.Method has GET/POST/PUT/DELETE/OPTIONS/PATCH and NO
        // HEAD (javap, docker-java-transport 3.4.2). The untyped builder overload carries the verb
        // the /archive stat probe needs; a GET would download the whole tar to read one header.
        DockerHttpClient.Request req = DockerHttpClient.Request.builder()
                .method("HEAD")
                .path(v() + "/containers/" + encode(container) + "/archive?path=" + encode(path))
                .build();
        try (DockerHttpClient.Response res = execute(req, "stat")) {
            if (res.getStatusCode() == 404) {
                return Optional.empty();
            }
            require2xx(res.getStatusCode(), "stat");
            String header = res.getHeader("X-Docker-Container-Path-Stat");
            if (header == null) {
                throw refuse("docker.api.unexpected");
            }
            JsonNode stat = readTree(Base64.getDecoder().decode(header), "stat");
            return Optional.of(new PathStat(stat.path("name").asText(), stat.path("size").asLong(),
                    Instant.parse(stat.path("mtime").asText())));
        }
    }

    /** What the daemon says is at a path inside the container. Metadata only — never content. */
    public record PathStat(String name, long size, Instant mtime) {
    }

    /**
     * The versioned path prefix, resolved once from the daemon's own {@code /version} and cached.
     *
     * <p><b>This was a pinned constant, and the pin was measured wrong.</b> Plan 005's fact table
     * recorded {@code /v1.43} as served, measured against Docker Desktop 28.5.1 whose
     * {@code MinAPIVersion} is 1.24. Colima's daemon is Docker 29.2.1 with
     * {@code ApiVersion 1.53, MinAPIVersion 1.44} — Docker 29 raised the floor — so every
     * {@code /v1.43} call there is a flat {@code 400}. A constant cannot be right for both, and
     * guessing a higher one only moves the cliff.
     *
     * <p>So: ask. {@code /version} is served on the UNVERSIONED path by every daemon, and it
     * advertises the window. The chosen version is {@link #PREFERRED} clamped into
     * {@code [MinAPIVersion, ApiVersion]} — measured at runtime rather than pinned at authoring
     * time, and still a single fixed value for the life of the client, so it keeps the property the
     * pin was for: "which daemon is this" stays a constant within one run.
     */
    private String v() {
        String resolved = version;
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            if (version == null) {
                JsonNode v = getJson("/version", "version");
                int[] max = parse(v.path("ApiVersion").asText(null), PREFERRED);
                int[] min = parse(v.path("MinAPIVersion").asText(null), new int[] {1, 24});
                int[] chosen = PREFERRED;
                if (compare(chosen, min) < 0) {
                    chosen = min;
                }
                if (compare(chosen, max) > 0) {
                    chosen = max;
                }
                version = "/v" + chosen[0] + "." + chosen[1];
            }
            return version;
        }
    }

    /** The version this client resolved. Exposed so a test can assert it, never to branch on. */
    public String negotiatedVersion() {
        return v();
    }

    private static int[] parse(String value, int[] fallback) {
        if (value == null || !value.matches("\\d+\\.\\d+")) {
            return fallback;
        }
        String[] parts = value.split("\\.");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
    }

    private static int compare(int[] a, int[] b) {
        return a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
    }

    private JsonNode getJson(String path, String operation) {
        DockerHttpClient.Request req = DockerHttpClient.Request.builder()
                .method(DockerHttpClient.Request.Method.GET).path(path).build();
        try (DockerHttpClient.Response res = execute(req, operation)) {
            require2xx(res.getStatusCode(), operation);
            try (InputStream body = res.getBody()) {
                return readTree(body.readAllBytes(), operation);
            } catch (IOException e) {
                throw refuse("docker.api.failed");
            }
        }
    }

    private boolean act(String path, String operation) {
        DockerHttpClient.Request req = DockerHttpClient.Request.builder()
                .method(DockerHttpClient.Request.Method.POST).path(path).build();
        try (DockerHttpClient.Response res = execute(req, operation)) {
            int status = res.getStatusCode();
            if (status == 304) {
                return false;            // already in the desired state: success without acting
            }
            require2xx(status, operation);
            return true;
        }
    }

    private DockerHttpClient.Response execute(DockerHttpClient.Request req, String operation) {
        try {
            return http.execute(req);
        } catch (RuntimeException e) {
            // Transport-level: no socket, refused connection, timeout. The exception's own message
            // carries the socket path, so it is classified and dropped, never wrapped.
            throw refuse("docker.api.unreachable");
        }
    }

    private void require2xx(int status, String operation) {
        if (status / 100 != 2) {
            throw new RuntimeAdapterException(DockerErrors.classify(status, operation));
        }
    }

    private JsonNode readTree(byte[] bytes, String operation) {
        try {
            return json.readTree(bytes);
        } catch (IOException e) {
            throw refuse("docker.api.unparseable");
        }
    }

    private static RuntimeAdapterException refuse(String token) {
        return new RuntimeAdapterException(new ErrorClass(token));
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        try {
            http.close();
        } catch (IOException e) {
            // Closing a transport is best-effort; a failure here has nothing a caller can do.
        }
    }
}
