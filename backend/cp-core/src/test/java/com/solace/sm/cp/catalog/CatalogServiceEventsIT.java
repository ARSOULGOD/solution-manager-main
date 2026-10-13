package com.solace.sm.cp.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.catalog.EntitlementRefusedException;
import com.solace.sm.cp.api.catalog.PackageLoad;
import com.solace.sm.cp.events.CatalogEventEmitter;
import com.solace.sm.cp.events.CpTransactionBoundary;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcCatalogRepository;
import com.solace.sm.cp.persistence.JdbcEntitlementRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalRepository;
import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 010 T3.3: the catalog's own commit publishes {@code catalog.package} - against a real PostgreSQL 14, every
 * write as {@code sm_cp_runtime}, and the real {@link CpTransactionBoundary} over the real
 * {@code DataSourceTransactionManager}. The raw transport is a recorder; what is under test is WHEN the control
 * plane hands it an event, and what the event says.
 */
class CatalogServiceEventsIT {

    private static final String KEY = "catalog-events-it";

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(KEY, "013");
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(KEY);
    private final DataSource runtimeSource = runtime.getJdbcTemplate().getDataSource();
    private final DataSourceTransactionManager runtimeTm = new DataSourceTransactionManager(runtimeSource);
    private final List<EventEnvelope> published = new CopyOnWriteArrayList<>();
    /** Counts rows visible at the instant the raw transport is reached: a post-commit publish sees the package. */
    private final List<Long> packageRowsVisibleAtPublish = new ArrayList<>();
    private final CatalogService catalog = newCatalog();

    private CatalogService newCatalog() {
        JdbcAuditContext audit = new JdbcAuditContext(runtime);
        TransactionTemplate tx = new TransactionTemplate(runtimeTm);
        EntitlementService entitlements = new EntitlementService(new JdbcEntitlementRepository(runtime),
                new JdbcPrincipalRepository(runtime), audit, tx, Clock.systemUTC());
        AfterCommitEventPublisher cpPublisher = new AfterCommitEventPublisher(event -> {
            // Read through a SEPARATE connection (the migrator's): only a committed row is visible to it.
            packageRowsVisibleAtPublish.add(migrator.queryForObject(
                    "SELECT count(*) FROM sm_control.solution_package WHERE organization_id = :o",
                    java.util.Map.of("o", UUID.fromString(event.organization())), Long.class));
            published.add(event);
        }, new CpTransactionBoundary(runtimeTm));
        return new CatalogService(new CatalogConfiguration().packageValidator(), new JdbcCatalogRepository(runtime),
                entitlements, audit, tx, Clock.systemUTC(), UUID::randomUUID,
                new CatalogEventEmitter(cpPublisher, Clock.systemUTC(), UUID::randomUUID),
                FirstLoadEntitlement.disabled());
    }

    @Test
    void aNewLoadPublishesExactlyOneCatalogPackageEventAndOnlyAfterItsRowsAreCommitted() {
        Org org = org();
        entitle(org, "cache");

        PackageLoad load = catalog.load(org.actor(), origin(), org.id, CatalogFixtures.json(CatalogFixtures.cache()));

        assertThat(published).hasSize(1);
        EventEnvelope event = published.get(0);
        assertThat(event.producer()).isEqualTo("cp-core");
        assertThat(event.event()).isEqualTo("catalog.package");
        assertThat(event.organization()).as("the organization, never a request field (I5)").isEqualTo(org.id.toString());
        assertThat(event.entity().kind()).isEqualTo("solution");
        assertThat(event.entity().solution()).isEqualTo("cache");
        assertThat(event.stateVersion()).isEqualTo(load.loaded().stateVersion());
        assertThat(event.payload()).containsEntry("solution", "cache").containsEntry("packageVersion", "[1]")
                .hasSize(2);
        assertThat(packageRowsVisibleAtPublish).as("deferred until the load's transaction committed")
                .containsExactly(1L);
    }

    @Test
    void anIdenticalReloadChangesNothingAndPublishesNothing() {
        Org org = org();
        entitle(org, "cache");
        String document = CatalogFixtures.json(CatalogFixtures.cache());
        catalog.load(org.actor(), origin(), org.id, document);

        PackageLoad again = catalog.load(org.actor(), origin(), org.id, document);

        assertThat(again.newlyLoaded()).isFalse();
        assertThat(published).as("no state changed, so no notice is owed").hasSize(1);
    }

    @Test
    void aLoadRolledBackAfterTheEventWasOfferedPublishesNothing() {
        Org org = org();     // no entitlement: refused INSIDE the transaction, which rolls it back
        assertThatThrownBy(() -> catalog.load(org.actor(), origin(), org.id,
                CatalogFixtures.json(CatalogFixtures.cache()))).isInstanceOf(EntitlementRefusedException.class);

        assertThat(published).isEmpty();
    }

    @Test
    void aTransactionRolledBackAfterTheEmitCallPublishesNothing() {
        // The harder rollback: the event WAS offered to the decorator inside a real control-plane transaction.
        Org org = org();
        CatalogEventEmitter emitter = new CatalogEventEmitter(new AfterCommitEventPublisher(published::add,
                new CpTransactionBoundary(runtimeTm)), Clock.systemUTC(), UUID::randomUUID);

        new TransactionTemplate(runtimeTm).executeWithoutResult(status -> {
            emitter.onPackageLoaded(new com.solace.sm.cp.api.authz.TenantId(org.id), "cache", "[1]", 1L);
            assertThat(published).as("held while the transaction is open").isEmpty();
            status.setRollbackOnly();
        });

        assertThat(published).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ helpers

    private record Org(UUID id, UUID actorId) {
        PrincipalRef actor() {
            return new PrincipalRef(actorId);
        }
    }

    private Org org() {
        UUID organization = UUID.randomUUID();
        UUID principal = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id) VALUES (?)",
                organization);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", principal, organization, "catalog-events-" + principal);
        return new Org(organization, principal);
    }

    private void entitle(Org org, String solution) {
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.entitlement (organization_id, solution,"
                + " max_instances, set_by, set_at) VALUES (?, ?, 5, ?, now())", org.id, solution, org.actorId);
    }

    private static RequestOrigin origin() {
        return new RequestOrigin("203.0.113.78", UUID.randomUUID());
    }
}
