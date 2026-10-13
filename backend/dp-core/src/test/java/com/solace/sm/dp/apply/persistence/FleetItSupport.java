package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.fleet.FleetApplyCommand;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.GenerationBinding;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.fleet.FleetRows;
import com.solace.sm.dp.fleet.FleetService;
import com.solace.sm.dp.fleet.MemberRecord;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The rig the fleet ITs share (plan 010 T11.2): an organization with active instances, each with one live placement and
 * generation 1 staged, a real {@link ApplyExecutor} over the real journal, and a {@link FleetService} over the real
 * {@code fleet_*} tables. Nothing about the fleet is faked; the only fake is the host, which no fleet test reaches (the
 * fleet admits, and the durable worker - not built here - is what executes).
 *
 * <p>A request's outcome is driven through the real {@link OutcomeService} ({@code start}, {@code land}), so the
 * watermark a landing raises is the real one: the re-admission tests depend on that to exercise the fence, not just the
 * blocking check.
 */
final class FleetItSupport {

    static final String DIGEST = "hmacsha256v1:" + "c".repeat(64);
    static final UUID ACTOR = UUID.randomUUID();
    private static final RenderedConfig CONFIG =
            TestHashers.rendered("generation: 1\n".getBytes(StandardCharsets.UTF_8));

    /** One declared, active instance with a live placement and generation 1 staged. */
    record Inst(UUID instance, UUID placement) {
    }

    final UUID tenant = SmDataPostgres.freshTenant();
    /** What {@link InventoryFixtures} stamps on every instance of a tenant: one catalog solution per organization. */
    final UUID solution = UUID.nameUUIDFromBytes(("catalog-" + tenant).getBytes());
    final NamedParameterJdbcTemplate superuser = SmDataPostgres.superuserJdbc();
    final NamedParameterJdbcTemplate runtime = SmDataPostgres.runtimeJdbc();
    final JdbcFleetRepository rows = new JdbcFleetRepository(SmDataPostgres.runtimeJdbc());
    final InstanceRows instanceRows = new JdbcInstanceStateRepository(SmDataPostgres.runtimeJdbc());
    final PlacementRows placementRows = new JdbcPlacementRepository(SmDataPostgres.runtimeJdbc());
    final ConfigGenerationRows generationRows = new JdbcConfigGenerationRepository(SmDataPostgres.runtimeJdbc());
    final OutcomeService outcomes = ApplyEngineHarness.outcomes();
    final ApplyExecutor executor;
    final AtomicInteger wakes = new AtomicInteger();

    FleetItSupport() {
        SmDataPostgres.bootstrap();
        InMemoryRuntimeAdapter adapter = new InMemoryRuntimeAdapter(new PlacementRef(new TenantId(tenant),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID())), true, "replica-1");
        this.executor = ApplyEngineHarness.executor(adapter, (p, g) -> CONFIG);
    }

    /**
     * The service the gateway would call, over {@code fleetRows} (a test may wrap them) and the given admission seam. The
     * settle delay is ZERO here so a test that is not about it never waits 30 s between batches; the delay's own tests
     * ({@code FleetBatchSafetyTest} and {@code FleetBatchSafetyIT}) build a service with a real one.
     */
    FleetService service(FleetRows fleetRows, FleetService.Admitter admitter) {
        return service(fleetRows, admitter, Clock.systemUTC(), Duration.ZERO);
    }

    /** As above, with the fleet's own clock and a real {@code rolling-no-validation} settle delay (plan 012 T6). */
    FleetService service(FleetRows fleetRows, FleetService.Admitter admitter, Clock clock, Duration noValidationSettle) {
        return new FleetService(ApplyEngineHarness.transactions(), fleetRows, instanceRows, placementRows,
                generationRows, admitter, clock, noValidationSettle, wakes::incrementAndGet);
    }

    /** The production seam: the executor's own admission, binding the generation to the config of record. */
    FleetService.Admitter realAdmitter() {
        return (command, id) -> executor.admit(command, GenerationBinding.MUST_BE_STAGED, id);
    }

    FleetService service() {
        return service(rows, realAdmitter());
    }

    // ---- the world -------------------------------------------------------------------------------------------

    Inst inst() {
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        UUID placement = InventoryFixtures.placement(tenant, instance, InventoryFixtures.target(tenant));
        generation(instance, 1);
        return new Inst(instance, placement);
    }

    /** Stages {@code generation} as the config of record (and the instance head) - as the superuser, like ApplyServiceIT. */
    void generation(UUID instance, long generation) {
        superuser.update("INSERT INTO sm_data.config_generation (tenant_id, instance_id, generation, package_solution,"
                        + " package_version, component_selection, draft_values, rendered_entries, content_digest,"
                        + " origin, created_by, created_at) VALUES (:t, :i, :g, 'cache', '2.4', '{}'::jsonb,"
                        + " '{}'::jsonb, '[]'::jsonb, :d, 'edit', :u, now())",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance).addValue("g", generation)
                        .addValue("d", DIGEST + generation).addValue("u", UUID.randomUUID()));
        superuser.update("UPDATE sm_data.instance_state SET last_generation = :g WHERE tenant_id = :t"
                        + " AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance).addValue("g", generation));
    }

    static FleetApplyCommand.Member ok(Inst inst) {
        return new FleetApplyCommand.Member(inst.instance(), inst.placement(), 1, null);
    }

    static FleetApplyCommand.Member refused(Inst inst, FleetApplyCommand.RefusalCode code) {
        return new FleetApplyCommand.Member(inst.instance(), inst.placement(), 1, code);
    }

    FleetApplyCommand command(String strategy, int batchSize, String onFailure, FleetApplyCommand.Member... members) {
        return new FleetApplyCommand(solution, List.of(members), strategy, null, batchSize, onFailure, ACTOR,
                UUID.randomUUID());
    }

    FleetApplyCommand manual(int batchSize, String onFailure, FleetApplyCommand.Member... members) {
        return command("manual", batchSize, onFailure, members);
    }

    // ---- reading and driving ---------------------------------------------------------------------------------

    List<MemberRecord> members(UUID fleet) {
        return rows.members(tenant, fleet);
    }

    MemberRecord member(UUID fleet, int ordinal) {
        return members(fleet).get(ordinal);
    }

    String disposition(UUID fleet, int ordinal) {
        return member(fleet, ordinal).disposition();
    }

    String fleetState(UUID fleet) {
        return rows.find(tenant, fleet).orElseThrow().state();
    }

    long fleetStamp(UUID fleet) {
        return rows.find(tenant, fleet).orElseThrow().stateVersion();
    }

    int requestsOf(UUID instance) {
        return superuser.queryForObject("SELECT count(*)::int FROM sm_data.apply_request WHERE tenant_id = :t"
                + " AND instance_id = :i", new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance),
                Integer.class);
    }

    int requests() {
        return superuser.queryForObject("SELECT count(*)::int FROM sm_data.apply_request WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Integer.class);
    }

    boolean requestExists(UUID applyId) {
        return superuser.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_data.apply_request WHERE apply_id = :a)",
                new MapSqlParameterSource("a", applyId), Boolean.class);
    }

    String requestState(UUID applyId) {
        return superuser.queryForObject("SELECT state FROM sm_data.apply_request WHERE apply_id = :a",
                new MapSqlParameterSource("a", applyId), String.class);
    }

    /** Walks a member's request to {@code end} through the real outcome service (REQUESTED is where admission leaves it). */
    void drive(UUID applyId, RequestState end) {
        TenantId t = new TenantId(tenant);
        ApplyId id = new ApplyId(applyId);
        switch (end) {
            case REQUESTED -> { }
            case RUNNING -> outcomes.start(t, id);
            default -> {
                outcomes.start(t, id);
                outcomes.land(t, id, end);
            }
        }
    }

    /** The reconciler's give-up classifier, written as the runtime role writes it (the column grant V011 gave it). */
    void giveUp(UUID applyId) {
        runtime.update("UPDATE sm_data.apply_request SET reconcile_class = 'host.unreachable' WHERE apply_id = :a",
                new MapSqlParameterSource("a", applyId));
    }

    // ---- a simulated crash -----------------------------------------------------------------------------------

    /** The process died: thrown from a write that never happened. */
    static final class SimulatedCrash extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SimulatedCrash() {
            super("simulated crash");
        }
    }

    /**
     * {@code real}, except that the named method throws {@link SimulatedCrash} instead of running while {@code armed}
     * answers true - the write after the one under test never reaches the database.
     */
    static FleetRows crashing(FleetRows real, String method, java.util.function.BooleanSupplier armed) {
        return (FleetRows) Proxy.newProxyInstance(FleetRows.class.getClassLoader(), new Class<?>[] {FleetRows.class},
                (proxy, m, args) -> {
                    if (m.getName().equals(method) && armed.getAsBoolean()) {
                        throw new SimulatedCrash();
                    }
                    try {
                        return m.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
