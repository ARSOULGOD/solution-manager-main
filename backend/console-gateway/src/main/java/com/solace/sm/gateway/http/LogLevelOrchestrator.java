package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.apply.ApplyAcceptedView;
import com.solace.sm.dp.api.apply.ApplyService;
import com.solace.sm.dp.api.apply.SetLogLevelCommand;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.logs.LogLevelService;
import com.solace.sm.dp.api.logs.LogLevelView;
import com.solace.sm.dp.api.logs.LoggerLevel;
import com.solace.sm.dp.api.logs.LoggerTarget;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code getLogLevel} and {@code setLogLevel} (plan 010 T9.3, {@code OR-103}), composed here because the gateway is the
 * one module permitted both {@code -api}s: the package (control plane) names the logger, the data plane reads it
 * ({@code I7}).
 *
 * <p><b>The package names the logger, the browser never does.</b> A request carries a component and, at most, a
 * placement. The logger is that component's declared {@code loggerPrefix}, and only for a package that declares
 * {@code logs.loggers} ({@code BE-D38}): nothing is inferred from a prefix, and a package that declares nothing is told
 * so, with no host touched. The data plane does not trust what the gateway resolves: it checks the prefix and the path
 * against their grammars before any session opens.
 *
 * <p><b>A change is configuration, never a live write.</b> Under {@code OR-103} every level change is a normal config
 * change applied with a restart ({@code T9.4}): {@link #change} admits it through the data plane's apply path
 * ({@code 202 ApplyAccepted}). {@code mode: live} is refused: {@code 422} on a direct-HTTP instance (the contract states
 * it), {@code 501} otherwise.
 *
 * <p>{@code I5}: the organization is {@code Session.organizationId()} on every call; it is never a request field.
 */
@Component
public class LogLevelOrchestrator {

    static final String DEFAULT_LOGGERS_PATH = "/actuator/loggers";

    private static final Set<String> LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
    private static final Set<String> MODES = Set.of("live", "config");

    private final DeclareOrchestrator instances;
    private final SolutionCatalog catalog;
    private final DesignerCodec codec;
    private final LogLevelService logLevels;
    private final ApplyService apply;

    public LogLevelOrchestrator(DeclareOrchestrator instances, SolutionCatalog catalog, DesignerCodec codec,
            LogLevelService logLevels, ApplyService apply) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.logLevels = Objects.requireNonNull(logLevels, "logLevels");
        this.apply = Objects.requireNonNull(apply, "apply");
    }

    // ---- getLogLevel ---------------------------------------------------------------------------------------

    public LogLevel read(Session session, UUID instanceId, String component, UUID placementId) {
        UUID organization = session.organizationId();
        InstanceView view = requireInstance(session, instanceId);
        PackageModel.PackageComponent declared = requireComponent(organization, view, component);
        UUID placement = resolvePlacement(view, placementId);
        AgentPlacements.refuseIfAgent(view, placement);          // plan 015 T3.3: 501 before any host is touched

        Optional<LoggerTarget> target = targetOf(declared);
        if (target.isEmpty()) {
            return new LogLevel(new Availability(AvailabilityState.UNAVAILABLE, null,
                    "this package does not declare logger support, so the level is not readable", null),
                    component, placement, null, null, false);
        }
        LogLevelView result;
        try {
            result = logLevels.read(organization, instanceId, placement, target.get());
        } catch (NoSuchElementException gone) {
            throw new ResourceNotFoundException(ResourceKind.INSTANCE);
        }
        Availability availability = result.availability().state() == LogLevelView.State.LIVE ? null
                : new Availability(switch (result.availability().state()) {
                    case DEGRADED -> AvailabilityState.DEGRADED;
                    default -> AvailabilityState.UNAVAILABLE;
                }, null, result.availability().reason(), null);
        return new LogLevel(availability, result.component(), result.placementId(),
                result.liveLevel().map(LoggerLevel::name).orElse(null),
                result.configuredLevel().map(LoggerLevel::name).orElse(null), result.runtimeOverride());
    }

    // ---- setLogLevel ---------------------------------------------------------------------------------------

    /**
     * Validates in the contract's order (the instance, the body, the placement, the component), then admits the change
     * through configuration ({@code mode: config}) as a normal apply, or refuses ({@code mode: live}: no live write
     * exists, {@code OR-103}). The logger is the package's declared {@code loggerPrefix}, never a request field; a package
     * that declares no {@code logs.loggers} cannot have a level changed ({@code 422}), and nothing is inferred.
     *
     * <p>{@code logs.setLevel} at the instance has passed in the interceptor. The apply it admits restarts nothing
     * ({@code manual}), so no second verb is asked: the level takes effect at the next restart, which the operator
     * triggers under {@code instance.restart} like any restart. The data plane runs the scope fence and answers
     * {@code SCOPE_BUSY} while an apply holds the placement.
     */
    public ApplyAccepted change(Session session, UUID instanceId, String component, SetLogLevelRequest request) {
        UUID organization = session.organizationId();
        InstanceView view = requireInstance(session, instanceId);
        if (request.placementId() == null || request.level() == null || !LEVELS.contains(request.level())
                || request.mode() == null || !MODES.contains(request.mode())) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        UUID placement = resolvePlacement(view, request.placementId());
        PackageModel.PackageComponent declared = requireComponent(organization, view, component);
        AgentPlacements.refuseIfAgent(view, placement);          // plan 015 T3.3: a config change that no agent applies in v1

        if (request.mode().equals("live")) {
            throw new LogLevelChangeRefusedException(view.acquisition() == AcquisitionMode.DIRECT_HTTP
                    ? LogLevelChangeRefusedException.Reason.LIVE_ON_DIRECT_HTTP
                    : LogLevelChangeRefusedException.Reason.LIVE_NOT_BUILT);
        }
        LoggerTarget target = targetOf(declared)
                .orElseThrow(() -> new LogLevelChangeRefusedException(
                        LogLevelChangeRefusedException.Reason.NO_LOGGER_SUPPORT));
        ApplyAcceptedView accepted = apply.setLogLevel(organization, instanceId, new SetLogLevelCommand(placement,
                target.loggerPrefix(), LoggerLevel.valueOf(request.level()), session.principal().id()));
        return new ApplyAccepted(accepted.stateVersion(), null, accepted.applyId());
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** Authorization at the instance has passed; only an ACTIVE row is readable, else the same 404 as out of scope. */
    private InstanceView requireInstance(Session session, UUID instanceId) {
        return instances.get(session, instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));
    }

    /** The package's component of this type; a package that cannot be read or has no such component is the same 404. */
    private PackageModel.PackageComponent requireComponent(UUID organization, InstanceView view, String component) {
        PackageModel.SolutionPackage pkg = catalog
                .documentOf(organization, view.catalogSolutionId(), view.declaredPackageVersion())
                .map(codec::readPackage)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));
        return pkg.components().stream().filter(c -> component.equals(c.type())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));
    }

    /**
     * The placement the request means: the named one, which must be a live placement of THIS instance, or the only one.
     * Two or more with no name is a {@code 400} ("required when the instance has more than one live placement").
     */
    private static UUID resolvePlacement(InstanceView view, UUID requested) {
        List<PlacementView> live = view.placements();
        if (requested != null) {
            return live.stream().map(PlacementView::placementId).filter(requested::equals).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.PLACEMENT));
        }
        if (live.isEmpty()) {
            throw new ResourceNotFoundException(ResourceKind.PLACEMENT);
        }
        if (live.size() > 1) {
            throw new GatewayRejectionException(ErrorCode.BAD_REQUEST);
        }
        return live.get(0).placementId();
    }

    /**
     * What the package declares, or empty when it declares no {@code logs.loggers} (or no logger prefix to read): nothing is
     * inferred. The path defaults to the schema's default, and the data plane validates it.
     */
    private static Optional<LoggerTarget> targetOf(PackageModel.PackageComponent component) {
        Map<String, Object> logs = component.logs();
        if (logs == null || !(logs.get("loggers") instanceof Map<?, ?> loggers) || component.loggerPrefix() == null) {
            return Optional.empty();
        }
        String path = loggers.get("path") instanceof String declared ? declared : DEFAULT_LOGGERS_PATH;
        return Optional.of(new LoggerTarget(component.type(), component.loggerPrefix(), path));
    }
}
