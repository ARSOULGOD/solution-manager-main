package com.solace.sm.dp.logs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.logs.LogLevelService;
import com.solace.sm.dp.api.logs.LogLevelView;
import com.solace.sm.dp.api.logs.LogLevelView.Availability;
import com.solace.sm.dp.api.logs.LogLevelView.State;
import com.solace.sm.dp.api.logs.LoggerLevel;
import com.solace.sm.dp.api.logs.LoggerTarget;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacementCatalog;
import com.solace.sm.dp.config.OverlayKeys;
import java.io.IOException;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@link LogLevelService} over SSH (plan 010 T9.3, {@code OR-103} option Rd, {@code BE-D36}).
 *
 * <p><b>A read, and only a read.</b> It asks the placement's own Spring Boot loggers endpoint, over the pooled SSH
 * session, from the host's loopback, for the level of one logger: {@code GET <path>/<prefix>} through
 * {@link HostProbe}, the same fixed {@code curl} command the health poller runs. There is no write verb in this class or
 * behind it: the overlay exposes the endpoint {@code read-only} ({@code OverlayKeys}), measured to answer {@code 405} to
 * a write, so a level change is configuration (T9.4) and there is no journaled operation to build.
 *
 * <p><b>What reaches the shell.</b> An {@code int} port the placement stored, and a path built from the package's
 * declared loggers path and its declared logger prefix after both pass their grammars here, before any lookup or
 * session. The grammar is stricter than a Java identifier on purpose: {@code $} is not a member, because the probe
 * command does not quote the URL and a {@code $} would be expanded by the host's shell. {@code ROOT} is refused: Boot
 * treats it specially and a package has no business reading it.
 *
 * <p><b>Availability, never silence.</b> Every outcome except a clean level is a state with a reason, and a reason is
 * fixed text: it quotes nothing the package declared and nothing the host returned (the body is the MI's own output).
 * A host that cannot answer is not an error here, because the console shows it beside the answer it has.
 */
public final class LogLevelServiceImpl implements LogLevelService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Slash-separated segments of the probe grammar's characters; no empty segment, no trailing slash, no {@code ..}. */
    private static final Pattern ENDPOINT_PATH = Pattern.compile("/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*");

    /** The probe path grammar's own bound ({@code ^/[A-Za-z0-9/_.-]{0,255}$}): 256 characters in all. */
    private static final int MAX_PATH_CHARS = 256;

    private final SshDockerPlacementCatalog catalog;
    private final HostProbe probe;
    private final Duration timeout;
    private final ConfiguredLevels configured;

    /** The level the instance's latest generation configures for one logger (T9.4). */
    @FunctionalInterface
    public interface ConfiguredLevels {
        Optional<LoggerLevel> of(UUID organizationId, UUID instanceId, String loggerPrefix);
    }

    public LogLevelServiceImpl(SshDockerPlacementCatalog catalog, HostProbe probe, Duration timeout,
            ConfiguredLevels configured) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.configured = Objects.requireNonNull(configured, "configured");
    }

    /**
     * The live answer, then the configured level beside it. The configured level is the platform's own record (the
     * latest generation's overlay), so it is reported even when the host cannot answer; it is looked up only after the
     * placement resolved, so an unknown placement reads nothing, and only for a logger name inside the grammar.
     */
    @Override
    public LogLevelView read(UUID organizationId, UUID instanceId, UUID placementId, LoggerTarget target) {
        LogLevelView live = readLive(organizationId, instanceId, placementId, target);
        if (!OverlayKeys.isLoggerName(target.loggerPrefix())) {
            return live;
        }
        Optional<LoggerLevel> level = configured.of(organizationId, instanceId, target.loggerPrefix());
        return level.isEmpty() ? live : new LogLevelView(live.placementId(), live.component(), live.liveLevel(), level,
                live.runtimeOverride(), live.availability());
    }

    private LogLevelView readLive(UUID organizationId, UUID instanceId, UUID placementId, LoggerTarget target) {
        Objects.requireNonNull(organizationId, "organization");
        Objects.requireNonNull(instanceId, "instance");
        Objects.requireNonNull(placementId, "placement");
        Objects.requireNonNull(target, "target");

        // Before any lookup or session: what the package declared must be a name and a path.
        String name = target.loggerPrefix();
        if (!OverlayKeys.isLoggerName(name)) {
            return unavailable(placementId, target, "the package's declared logger prefix is not a usable logger name");
        }
        String path = target.path();
        if (!ENDPOINT_PATH.matcher(path).matches() || path.contains("..")
                || path.length() + 1 + name.length() > MAX_PATH_CHARS) {
            return unavailable(placementId, target, "the package's declared loggers path is outside the allowed grammar");
        }

        PlacementRef ref = new PlacementRef(new TenantId(organizationId), new InstanceId(instanceId),
                new PlacementId(placementId));
        SshDockerPlacement placement;
        try {
            placement = catalog.lookup(ref);
        } catch (RuntimeAdapterException e) {
            if ("placement.unresolvable".equals(e.errorClass().token())) {
                throw new NoSuchElementException("placement is not a live placement of this instance");
            }
            return unavailable(placementId, target, "the placement's host could not be resolved");
        }
        if (placement.acquisition() != AcquisitionMode.VIA_SSH) {
            return unavailable(placementId, target, "a direct-HTTP placement has no SSH path to read from; "
                    + "change the level through configuration");
        }
        HealthProbeSpec declared = placement.probe();
        if (declared == null) {
            return unavailable(placementId, target, "the placement declares no application port to read from");
        }

        HostProbe.ProbeResult result;
        try {
            result = probe.probe(placement, declared.hostPort(), path + "/" + name, timeout);
        } catch (RuntimeAdapterException e) {
            return switch (e.errorClass().token()) {
                case "ssh.pool.busy", "ssh.pool.closed" -> view(placementId, target, Optional.empty(),
                        new Availability(State.DEGRADED, "the host's SSH capacity is busy; read again shortly"));
                case "ssh.probe.refused" -> unavailable(placementId, target,
                        "the declared loggers path is outside the allowed grammar");
                default -> unavailable(placementId, target, "the host could not be reached over SSH");
            };
        }
        return interpret(placementId, target, result);
    }

    private static LogLevelView interpret(UUID placementId, LoggerTarget target, HostProbe.ProbeResult result) {
        if (result.exitCode() != 0) {
            return unavailable(placementId, target, result.exitCode() == 7
                    ? "the application port refused the connection on the host"
                    : "the application did not answer in time");
        }
        int status = result.httpStatus();
        if (status == 404) {
            return view(placementId, target, Optional.empty(), new Availability(State.DEGRADED,
                    "the loggers endpoint did not answer for this logger: it is not exposed yet (a package that declares "
                            + "loggers exposes it at the next apply and restart) or the logger is not known to the "
                            + "running process"));
        }
        if (status != 200) {
            return view(placementId, target, Optional.empty(), new Availability(State.DEGRADED,
                    "the loggers endpoint answered HTTP " + status));
        }
        JsonNode body;
        try {
            body = JSON.readTree(result.body());
        } catch (IOException e) {
            // the parser's message can quote the body, which is the MI's own output: dropped on purpose
            body = null;
        }
        JsonNode effective = body == null ? null : body.get("effectiveLevel");
        if (effective == null || !effective.isTextual()) {
            return view(placementId, target, Optional.empty(), new Availability(State.DEGRADED,
                    "the loggers endpoint answered with something that is not a level"));
        }
        Optional<LoggerLevel> level = LoggerLevel.fromBoot(effective.textValue());
        if (level.isEmpty()) {
            return view(placementId, target, Optional.empty(), new Availability(State.DEGRADED,
                    "the running level is outside the levels the console offers"));
        }
        return view(placementId, target, level, new Availability(State.LIVE, null));
    }

    private static LogLevelView unavailable(UUID placementId, LoggerTarget target, String reason) {
        return view(placementId, target, Optional.empty(), new Availability(State.UNAVAILABLE, reason));
    }

    private static LogLevelView view(UUID placementId, LoggerTarget target, Optional<LoggerLevel> live,
            Availability availability) {
        // runtimeOverride is false by construction: under OR-103 SM has no live write, so it never sets an override.
        return new LogLevelView(placementId, target.component(), live, Optional.empty(), false, availability);
    }

    /** Names nothing. */
    @Override
    public String toString() {
        return "LogLevelServiceImpl";
    }
}
