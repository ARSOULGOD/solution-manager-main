package com.solace.sm.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local-demo implementation of the console's existing inventory routes.
 * SSH specifics stay inside the declaration object and the runtime adapter;
 * no browser route executes an SSH or Docker command itself.
 */
@RestController
@RequestMapping("/api/dp")
final class DemoDataPlaneController {
    private final DemoSessionService sessions;
    private final DemoInstanceService instances;
    private final DemoStore store;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    DemoDataPlaneController(DemoSessionService sessions, DemoInstanceService instances, DemoStore store, JdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.sessions = sessions;
        this.instances = instances;
        this.store = store;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/solutions/{solutionId}/instances")
    PagedInstances list(@PathVariable String solutionId, HttpServletRequest request) {
        require(request, false);
        UUID parsed = parseUuid(solutionId, "The selected solution does not exist.");
        List<InstanceRow> rows = jdbc.query("""
                select instance_id, solution_id, display_name, package_version::text, environment,
                       organization_name, sub_organization_name, driver, locus, replicas::text,
                       declared_version, created_at
                  from demo_control.instance where solution_id = ? order by created_at desc
                """, (rs, ignored) -> row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                rs.getTimestamp(12).toInstant()), parsed);
        return new PagedInstances(rows, rows.size());
    }

    @GetMapping("/instances/{instanceId}")
    InstanceRow get(@PathVariable String instanceId, HttpServletRequest request) {
        require(request, false);
        UUID parsed = parseUuid(instanceId, "The selected instance does not exist.");
        return jdbc.query("""
                select instance_id, solution_id, display_name, package_version::text, environment,
                       organization_name, sub_organization_name, driver, locus, replicas::text,
                       declared_version, created_at
                  from demo_control.instance where instance_id = ?
                """, (rs, ignored) -> row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                rs.getTimestamp(12).toInstant()), parsed).stream().findFirst()
                .orElseThrow(DemoNotFoundException::new);
    }

    @PostMapping("/solutions/{solutionId}/instances")
    ResponseEntity<InstanceRow> declare(@PathVariable String solutionId, @RequestBody DeclareRequest requestBody,
            HttpServletRequest request) throws IOException {
        require(request, true);
        if (requestBody.sshDocker() == null) {
            throw new DemoBadRequestException("An SSH Docker connection is required for the local demo.");
        }
        SshDocker ssh = requestBody.sshDocker();
        DemoInstanceService.InstanceView saved = instances.register(new DemoInstanceService.InstanceDeclaration(
                solutionId, requestBody.name(), ssh.host(), ssh.port(), ssh.user(), ssh.sshKeyReference(),
                ssh.pinnedHostKeyFingerprint(), ssh.containerName(), ssh.remoteConfigDirectory(),
                ssh.containerConfigPath(), ssh.configFileName()), vaultKey(request));
        jdbc.update("""
                update demo_control.instance set package_version = ?::jsonb, environment = ?, organization_name = ?,
                    sub_organization_name = ?, driver = ?, locus = ?, replicas = ?::jsonb, declared_version = ?
                where instance_id = ?
                """, json(requestBody.packageVersion()), requestBody.env(), requestBody.org(), requestBody.subOrg(),
                requestBody.driver(), requestBody.locus(), json(replicaNodes(requestBody.replicas())), requestBody.declaredVersion(),
                saved.instanceId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(row(saved.instanceId(), saved.solutionId(), requestBody.name(), json(requestBody.packageVersion()),
                        requestBody.env(), requestBody.org(), requestBody.subOrg(), requestBody.driver(), requestBody.locus(),
                        json(replicaNodes(requestBody.replicas())), requestBody.declaredVersion(), saved.registeredAt()));
    }

    /** The uploaded Cache package remains the source of the authenticated Designer form. */
    @GetMapping("/instances/{instanceId}/designer")
    DesignerPayload designer(@PathVariable String instanceId, HttpServletRequest request) throws IOException {
        require(request, false);
        UUID id = parseUuid(instanceId, "The selected instance does not exist.");
        InstanceContext context = context(id);
        ConfigurationRecord configuration = configuration(id);
        DemoInstanceService.InstanceView status = instances.status(id, vaultKey(request));
        // The imported console owns a flat ObservedState HTTP contract. Do
        // not send the root project's nested runtime status here.
        ObjectNode observed = mapper.createObjectNode()
                .put("health", replicaHealth(status))
                .put("versionState", "unverified")
                .put("configState", configuration.stagedGeneration() == null ? "rendered" : "staged");
        if (context.declaredVersion() != null && !context.declaredVersion().isBlank()) {
            observed.put("declaredVersion", context.declaredVersion());
        }
        return new DesignerPayload(designerPackage(context.solutionId()), context.packageVersion(),
                configuration.values(), observed, String.valueOf(configuration.generation()));
    }

    @PostMapping("/instances/{instanceId}/config")
    SaveConfigResult saveConfiguration(@PathVariable String instanceId, @RequestBody SaveConfigRequest body,
            HttpServletRequest request) {
        require(request, true);
        UUID id = parseUuid(instanceId, "The selected instance does not exist.");
        ConfigurationRecord current = configuration(id);
        long generation;
        try {
            generation = Long.parseLong(body.generation());
        } catch (RuntimeException invalid) {
            throw new DemoBadRequestException("The configuration generation is invalid.");
        }
        if (generation != current.generation()) {
            throw new DemoConflictException();
        }
        JsonNode solaceEnabled = body.fields().path("cache-engine").get("solace.enabled");
        if (solaceEnabled != null && solaceEnabled.isBoolean() && !solaceEnabled.booleanValue()) {
            throw new DemoBadRequestException(
                    "This Cache Engine build requires Solace to remain enabled; disabling it prevents the application from starting.");
        }
        ObjectNode values;
        try {
            values = instances.validateConfiguration(body.fields());
        } catch (RuntimeAdapterException invalid) {
            throw new DemoBadRequestException("Configuration contains an invalid or unsupported Cache field.");
        }
        long next = generation + 1;
        if (jdbc.update("update demo_control.instance_configuration set generation = ?, values = ?::jsonb, updated_at = ? where instance_id = ? and generation = ?",
                next, json(values), Timestamp.from(Instant.now()), id, generation) != 1) {
            throw new DemoConflictException();
        }
        return new SaveConfigResult(String.valueOf(next));
    }

    /** Staging writes validated non-secret Cache fields and never restarts the remote container. */
    @PostMapping("/instances/{instanceId}/apply")
    ApplyOutcome apply(@PathVariable String instanceId, @RequestBody ApplyRequest body,
            HttpServletRequest request) throws IOException {
        require(request, true);
        if (!"manual".equals(body.strategy())) {
            throw new DemoBadRequestException("The local demo supports manual staging only.");
        }
        UUID id = parseUuid(instanceId, "The selected instance does not exist.");
        ConfigurationRecord current = configuration(id);
        DemoInstanceService.InstanceView status = instances.applyConfiguration(id, current.values(), vaultKey(request));
        jdbc.update("update demo_control.instance_configuration set staged_generation = ?, updated_at = ? where instance_id = ?",
                current.generation(), Timestamp.from(Instant.now()), id);
        return new ApplyOutcome("INDETERMINATE",
                "Configuration was staged and read back. Restart remains an explicit operator action.",
                Instant.now().toString(), List.of(observation(context(id).containerName(), current.generation(), false, status)));
    }

    @PostMapping("/instances/{instanceId}/restart")
    InstanceRestart restart(@PathVariable String instanceId, HttpServletRequest request) throws IOException {
        require(request, true);
        UUID id = parseUuid(instanceId, "The selected instance does not exist.");
        DemoInstanceService.InstanceView status = instances.restart(id, vaultKey(request));
        ConfigurationRecord current = configuration(id);
        boolean restartObserved = false;
        if (current.stagedGeneration() != null && current.stagedGeneration().equals(current.generation())) {
            jdbc.update("update demo_control.instance_configuration set restart_observed_generation = ?, updated_at = ? where instance_id = ?",
                    current.generation(), Timestamp.from(Instant.now()), id);
            restartObserved = true;
        }
        return new InstanceRestart(status.startedAt() == null ? null : status.startedAt().toString(), status.observedHash(),
                observation(context(id).containerName(), current.generation(), restartObserved, status));
    }

    @GetMapping("/instances/{instanceId}/apply/observe")
    List<ReplicaObservation> observe(@PathVariable String instanceId, HttpServletRequest request) throws IOException {
        require(request, false);
        UUID id = parseUuid(instanceId, "The selected instance does not exist.");
        ConfigurationRecord current = configuration(id);
        return List.of(observation(context(id).containerName(), current.generation(),
                current.restartObservedGeneration() != null && current.restartObservedGeneration().equals(current.generation()),
                instances.status(id, vaultKey(request))));
    }

    @GetMapping("/instances/{instanceId}/health")
    HealthSnapshot health(@PathVariable String instanceId, HttpServletRequest request) throws IOException {
        require(request, false);
        UUID id = parseUuid(instanceId, "The selected instance does not exist.");
        DemoInstanceService.InstanceView status = instances.status(id, vaultKey(request));
        return new HealthSnapshot(rollup(status), status.healthSignalDeclared() ? "declared" : "not-declared",
                List.of(new HealthReplica(context(id).containerName(), replicaHealth(status))));
    }

    @GetMapping("/instances/{instanceId}/config-ladder")
    ConfigLadder configLadder(@PathVariable String instanceId, HttpServletRequest request) {
        require(request, false);
        UUID id = parseUuid(instanceId, "The selected instance does not exist.");
        ConfigurationRecord current = configuration(id);
        boolean staged = current.stagedGeneration() != null && current.stagedGeneration().equals(current.generation());
        boolean restarted = current.restartObservedGeneration() != null
                && current.restartObservedGeneration().equals(current.generation());
        return new ConfigLadder(List.of(
                new ConfigLadderStep("desired", "Desired configuration saved", current.generation() > 0),
                new ConfigLadderStep("rendered", "Rendered configuration staged", staged),
                new ConfigLadderStep("persisted", "Remote configuration read back", staged),
                new ConfigLadderStep("restart-observed", "Explicit restart observed", restarted),
                new ConfigLadderStep("effective-verified", "Effective runtime verified", false)));
    }

    @GetMapping("/instances/{instanceId}/log-levels/{component}")
    LogLevelState logLevel(@PathVariable String instanceId, @PathVariable String component,
            HttpServletRequest request) {
        require(request, false);
        parseUuid(instanceId, "The selected instance does not exist.");
        return new LogLevelState(component, "INFO", true);
    }

    @PostMapping("/instances/{instanceId}/log-levels/{component}")
    ResponseEntity<Void> setLogLevel(@PathVariable String instanceId, @PathVariable String component,
            @RequestBody SetLogLevel body, HttpServletRequest request) {
        require(request, true);
        parseUuid(instanceId, "The selected instance does not exist.");
        if (!List.of("ERROR", "WARN", "INFO", "DEBUG").contains(body.level())) {
            throw new DemoBadRequestException("Unsupported log level.");
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/instances/{instanceId}/logs/{component}")
    LogPage logs(@PathVariable String instanceId, @PathVariable String component,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) String before,
            HttpServletRequest request) throws IOException {
        require(request, false);
        UUID id = parseUuid(instanceId, "The selected instance does not exist.");
        if (limit < 1 || limit > 500 || (after != null && before != null)) {
            throw new DemoBadRequestException("The log page request is invalid.");
        }
        var page = instances.logs(id, vaultKey(request), limit, cursor(after), cursor(before));
        return new LogPage(page.lines().stream()
                .map(line -> new LogLine(line.timestamp().toString(), "INFO", line.message())).toList(),
                page.nextBefore() == null ? null : page.nextBefore().toString(), page.hasOlder());
    }

    private static Instant cursor(String value) {
        if (value == null) return null;
        try { return Instant.parse(value); }
        catch (RuntimeException invalid) { throw new DemoBadRequestException("The log cursor is invalid."); }
    }

    @DeleteMapping("/instances/{instanceId}")
    ResponseEntity<Void> undeclare(@PathVariable String instanceId, HttpServletRequest request) {
        require(request, true);
        UUID parsed = parseUuid(instanceId, "The selected instance does not exist.");
        if (jdbc.update("delete from demo_control.instance where instance_id = ?", parsed) == 0) {
            throw new DemoNotFoundException();
        }
        return ResponseEntity.noContent().build();
    }

    private InstanceRow row(UUID id, UUID solutionId, String name, String packageVersion, String environment,
            String organization, String subOrganization, String driver, String locus, String replicas,
            String declaredVersion, Instant createdAt) {
        return new InstanceRow(id.toString(), name, solutionId.toString(), node(packageVersion), environment,
                organization, subOrganization, driver, locus, replicaNodes(node(replicas)), "notfound", "bootstrap",
                new Version("unverified", "-", declaredVersion == null || declaredVersion.isBlank() ? "-" : declaredVersion),
                "rendered", createdAt.toString());
    }

    private JsonNode node(String json) {
        try { return mapper.readTree(json); }
        catch (IOException error) { throw new IllegalStateException("stored demo data is unreadable", error); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (IOException error) { throw new IllegalStateException("demo data could not be encoded", error); }
    }

    private JsonNode replicaNodes(List<String> names) {
        var result = mapper.createArrayNode();
        for (String name : names) {
            result.add(mapper.createObjectNode().put("name", name).put("health", "restart_expected"));
        }
        return result;
    }

    private JsonNode replicaNodes(JsonNode stored) {
        var result = mapper.createArrayNode();
        for (JsonNode item : stored) {
            String name = item.isObject() ? item.path("name").asText() : item.asText();
            result.add(mapper.createObjectNode().put("name", name).put("health", "restart_expected"));
        }
        return result;
    }

    private InstanceContext context(UUID instanceId) {
        return jdbc.query("select solution_id, container_name, package_version::text, declared_version from demo_control.instance where instance_id = ?",
                (rs, ignored) -> new InstanceContext(rs.getObject(1, UUID.class), rs.getString(2),
                        node(rs.getString(3)), rs.getString(4)), instanceId).stream().findFirst()
                .orElseThrow(DemoNotFoundException::new);
    }

    private ConfigurationRecord configuration(UUID instanceId) {
        return jdbc.query("select generation, values::text, staged_generation, restart_observed_generation from demo_control.instance_configuration where instance_id = ?",
                (rs, ignored) -> new ConfigurationRecord(rs.getLong(1), node(rs.getString(2)),
                        rs.getObject(3, Long.class), rs.getObject(4, Long.class)), instanceId).stream().findFirst()
                .orElseThrow(DemoNotFoundException::new);
    }

    /** Returns every uploaded field; rendering is constrained by the fixed Cache allowlist. */
    private JsonNode designerPackage(UUID solutionId) {
        JsonNode stored = store.packageJson(solutionId);
        if (!(stored instanceof ObjectNode root)) {
            throw new IllegalStateException("stored package is unreadable");
        }
        ObjectNode projected = root.deepCopy();
        for (JsonNode candidate : projected.path("components")) {
            if (!"cache-engine".equals(candidate.path("type").asText()) || !(candidate instanceof ObjectNode component)) {
                continue;
            }
            component.remove("actions");
        }
        return projected;
    }

    private String replicaHealth(DemoInstanceService.InstanceView status) {
        if (!"RUNNING".equals(status.liveness())) return "not_responding";
        return switch (status.health()) {
            case "HEALTHY" -> "healthy";
            case "UNHEALTHY" -> "not_responding";
            case "STARTING" -> "restart_expected";
            default -> "not_applicable";
        };
    }

    private String rollup(DemoInstanceService.InstanceView status) {
        if (!"RUNNING".equals(status.liveness())) return "RED";
        return switch (status.health()) {
            case "HEALTHY" -> "GREEN";
            case "UNHEALTHY" -> "RED";
            case "STARTING" -> "RESTART_EXPECTED";
            default -> "NOT_APPLICABLE";
        };
    }

    private ReplicaObservation observation(String containerName, long generation, boolean restartObserved,
            DemoInstanceService.InstanceView status) {
        return new ReplicaObservation(containerName, String.valueOf(generation), restartObserved, observationHealth(status));
    }

    private String observationHealth(DemoInstanceService.InstanceView status) {
        return switch (replicaHealth(status)) {
            case "healthy" -> "GREEN";
            case "not_responding" -> "RED";
            case "restart_expected" -> "RESTART_EXPECTED";
            default -> "INACTIVE_REPLICA";
        };
    }

    private void require(HttpServletRequest request, boolean mutation) {
        sessions.require(cookie(request, DemoSessionService.SESSION_COOKIE), request.getHeader("X-SM-CSRF"), mutation);
    }

    private byte[] vaultKey(HttpServletRequest request) {
        return sessions.vaultKey(cookie(request, DemoSessionService.SESSION_COOKIE));
    }

    private static UUID parseUuid(String value, String message) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException error) { throw new DemoBadRequestException(message); }
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    record PagedInstances(List<InstanceRow> items, int total) {}
    record InstanceRow(String id, String name, String solutionId, JsonNode packageVersion, String env, String org,
            String subOrg, String driver, String locus, JsonNode replicas, String healthRollup,
            String notFoundReason, Version version, String configState, String lastSeen) {}
    record Version(String state, String observed, String declared) {}
    record DeclareRequest(String name, Object packageVersion, String env, String org, String subOrg, String driver,
            String locus, List<String> replicas, String declaredVersion, SshDocker sshDocker) {}
    record SshDocker(String host, int port, String user, String sshKeyReference, String pinnedHostKeyFingerprint,
            String containerName, String remoteConfigDirectory, String containerConfigPath, String configFileName) {}
    record DesignerPayload(@JsonProperty("package") JsonNode packageValue, JsonNode packageVersion, JsonNode values,
            JsonNode observed, String configGeneration) {}
    record SaveConfigRequest(JsonNode fields, String generation, String overrideReason) {}
    record SaveConfigResult(String generation) {}
    record ApplyRequest(String strategy) {}
    record ApplyOutcome(String kind, String reason, String observedAt, List<ReplicaObservation> replicas) {}
    record ReplicaObservation(String id, String generation, boolean restartObserved, String health) {}
    record InstanceRestart(String startedAt, String observedHash, ReplicaObservation replica) {}
    record HealthSnapshot(String rollup, String availability, List<HealthReplica> replicas) {}
    record HealthReplica(String id, String health) {}
    record ConfigLadder(List<ConfigLadderStep> steps) {}
    record ConfigLadderStep(String key, String label, boolean reached) {}
    record LogLevelState(String component, String level, boolean supported) {}
    record SetLogLevel(String level) {}
    record LogLine(String ts, String level, String message) {}
    record LogPage(List<LogLine> lines, String nextBefore, boolean hasOlder) {}
    private record InstanceContext(UUID solutionId, String containerName, JsonNode packageVersion, String declaredVersion) {}
    private record ConfigurationRecord(long generation, JsonNode values, Long stagedGeneration,
            Long restartObservedGeneration) {}

    @ExceptionHandler(DemoUnauthorizedException.class)
    ResponseEntity<DemoController.ApiError> unauthorized() { return error(HttpStatus.UNAUTHORIZED, "Authentication is required."); }
    @ExceptionHandler(DemoBadRequestException.class)
    ResponseEntity<DemoController.ApiError> badRequest(DemoBadRequestException error) { return error(HttpStatus.BAD_REQUEST, error.getMessage()); }
    @ExceptionHandler(DemoNotFoundException.class)
    ResponseEntity<DemoController.ApiError> notFound() { return error(HttpStatus.NOT_FOUND, "The selected instance does not exist."); }
    @ExceptionHandler(DemoUnavailableException.class)
    ResponseEntity<DemoController.ApiError> unavailable() { return error(HttpStatus.SERVICE_UNAVAILABLE, "The configured instance is unavailable."); }
    @ExceptionHandler(RuntimeAdapterException.class)
    ResponseEntity<DemoController.ApiError> runtimeUnavailable() { return error(HttpStatus.SERVICE_UNAVAILABLE, "The configured instance is unavailable."); }
    @ExceptionHandler(DemoConflictException.class)
    ResponseEntity<DemoController.ApiError> conflict() { return error(HttpStatus.CONFLICT, "The configuration changed. Reload and review it before saving."); }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<DemoController.ApiError> invalidConnection() { return error(HttpStatus.BAD_REQUEST, "The instance connection details are invalid."); }
    private static ResponseEntity<DemoController.ApiError> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new DemoController.ApiError(message));
    }
}

final class DemoConflictException extends RuntimeException {}
