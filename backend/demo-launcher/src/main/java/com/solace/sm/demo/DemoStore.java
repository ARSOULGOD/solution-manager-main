package com.solace.sm.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PostgreSQL-backed local package metadata; credentials and remote configuration are never stored here. */
@Service
final class DemoStore {
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final Map<String, byte[]> pendingBySession = new ConcurrentHashMap<>();

    DemoStore(ObjectMapper mapper, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    synchronized PackageSummary save(byte[] bytes) throws java.io.IOException {
        JsonNode root = validate(bytes);
        String id = UUID.randomUUID().toString();
        String digest = sha256(bytes);
        Instant createdAt = Instant.now();
        jdbc.update("insert into demo_control.solution (solution_id, package_name, package_version, package_json, package_digest, created_at) values (?, ?, ?::jsonb, ?::jsonb, ?, ?)",
                UUID.fromString(id), root.path("solution").asText(), root.path("packageVersion").toString(),
                root.toString(), digest, Timestamp.from(createdAt));
        return new PackageSummary(id, "cache", root.path("packageVersion").toString(), digest, createdAt);
    }

    void stage(String sessionId, byte[] bytes) throws java.io.IOException {
        validate(bytes);
        pendingBySession.put(sessionId, bytes.clone());
    }

    synchronized PackageSummary confirm(String sessionId) throws java.io.IOException {
        byte[] bytes = pendingBySession.remove(sessionId);
        if (bytes == null) {
            throw new DemoBadRequestException("Choose a package before confirming it.");
        }
        try {
            return save(bytes);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    void discard(String sessionId) {
        byte[] removed = pendingBySession.remove(sessionId);
        if (removed != null) {
            java.util.Arrays.fill(removed, (byte) 0);
        }
    }

    List<PackageSummary> list() {
        return jdbc.query("select solution_id, package_name, package_version::text, package_digest, created_at from demo_control.solution order by created_at desc",
                (rs, ignored) -> new PackageSummary(rs.getObject(1, UUID.class).toString(), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()));
    }

    JsonNode packageJson(UUID solutionId) {
        return jdbc.query("select package_json::text from demo_control.solution where solution_id = ?",
                (rs, ignored) -> parseStoredPackage(rs.getString(1)), solutionId).stream().findFirst()
                .orElseThrow(DemoNotFoundException::new);
    }

    List<ConsoleSolution> catalog() {
        return jdbc.query("select s.solution_id, s.package_json::text, s.created_at, count(i.instance_id) from demo_control.solution s left join demo_control.instance i on i.solution_id = s.solution_id group by s.solution_id, s.package_json, s.created_at order by s.created_at desc",
                (rs, ignored) -> {
                    JsonNode root = parseStoredPackage(rs.getString(2));
                    List<String> components = new java.util.ArrayList<>();
                    for (JsonNode component : root.path("components")) {
                        String type = component.path("type").asText();
                        if (!type.isBlank()) components.add(type);
                    }
                    return new ConsoleSolution(rs.getObject(1, UUID.class).toString(), root.path("solution").asText(),
                            "Cache Engine", "#015B82", "bolt", "Distributed cache - Spring Boot",
                            root.path("packageVersion"), root.path("appliesTo").asText("unspecified"),
                            root.path("provenance"), root.path("authoring"), components, rs.getInt(4), true,
                            "SSH Docker", rs.getTimestamp(3).toInstant().toString(), true);
                });
    }

    private JsonNode validate(byte[] bytes) throws java.io.IOException {
        JsonNode root = mapper.readTree(bytes);
        if (root == null || !"cache".equals(root.path("solution").asText())
                || !root.path("packageVersion").isArray() || root.path("components").isEmpty()) {
            throw new DemoBadRequestException("The uploaded file is not a Cache Solution package.");
        }
        return root;
    }

    private JsonNode parseStoredPackage(String source) {
        try {
            return mapper.readTree(source);
        } catch (java.io.IOException malformedStoredValue) {
            throw new IllegalStateException("stored package is unreadable", malformedStoredValue);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record PackageSummary(String id, String solution, String packageVersion, String digest, Instant createdAt) {}
    record ConsoleSolution(String id, String solution, String name, String color, String icon, String kind,
            JsonNode packageVersion, String appliesTo, JsonNode provenance, JsonNode authoring,
            List<String> components, int instanceCount, boolean entitled, String telemetryMode, String published,
            boolean devMode) {}
}

final class DemoBadRequestException extends RuntimeException {
    DemoBadRequestException(String message) { super(message); }
}
