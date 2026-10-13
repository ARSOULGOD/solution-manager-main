package com.solace.sm.cp.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.catalog.CatalogEntry;
import com.solace.sm.cp.api.catalog.EntitlementRefusedException;
import com.solace.sm.cp.api.catalog.LoadedPackage;
import com.solace.sm.cp.api.catalog.PackageLoad;
import com.solace.sm.cp.api.catalog.PackageRejectedException;
import com.solace.sm.cp.api.catalog.PackageVersionId;
import com.solace.sm.cp.authz.ScopePathResolver;
import com.solace.sm.cp.events.CatalogEventEmitter;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcCatalogRepository;
import com.solace.sm.cp.persistence.JdbcEntitlementRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalRepository;
import com.solace.sm.cp.persistence.JdbcResourceScopeDirectory;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The package catalog against a real PostgreSQL 14, wired exactly as {@link CatalogConfiguration} wires it, and
 * every write made as {@code sm_cp_runtime}: a write by the migrator or the owner is exempt from OR-25 and would
 * prove nothing about the role the application connects as.
 *
 * <p>Fixture rows (organizations, principals, entitlements) are seeded as the migrator, so each test's own load
 * is the only thing under test.
 */
class CatalogServiceIT {

    private static final String KEY = "catalog-service-it";
    private static final String CLIENT = "203.0.113.77";

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(KEY, "013");
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(KEY);
    private final DataSource runtimeSource = runtime.getJdbcTemplate().getDataSource();
    private final TransactionTemplate runtimeTx =
            new TransactionTemplate(new DataSourceTransactionManager(runtimeSource));
    private final CatalogService catalog = newCatalog(new FirstLoadEntitlement(true, 10));
    private final CatalogService strictCatalog = newCatalog(FirstLoadEntitlement.disabled());

    private CatalogService newCatalog(FirstLoadEntitlement firstLoad) {
        JdbcAuditContext audit = new JdbcAuditContext(runtime);
        EntitlementService entitlements = new EntitlementService(new JdbcEntitlementRepository(runtime),
                new JdbcPrincipalRepository(runtime), audit, runtimeTx, Clock.systemUTC());
        return new CatalogService(new CatalogConfiguration().packageValidator(), new JdbcCatalogRepository(runtime),
                entitlements, audit, runtimeTx, Clock.systemUTC(), UUID::randomUUID,
                new CatalogEventEmitter(event -> { }, Clock.systemUTC(), UUID::randomUUID), firstLoad);
    }

    // ------------------------------------------------------------------------------------------ the load

    @Test
    void aFirstLoadMintsTheEntryStoresTheDocumentUnsignedAndReturnsTheCatalogId() {
        Org org = org();
        entitle(org, "cache");

        PackageLoad load = catalog.load(org.actor(), origin(), org.id, CatalogFixtures.json(CatalogFixtures.cache()));

        assertThat(load.newlyLoaded()).isTrue();
        LoadedPackage loaded = load.loaded();
        assertThat(loaded.solution()).isEqualTo("cache");
        assertThat(loaded.packageVersion()).containsExactly(PackageVersionId.of(1));
        assertThat(loaded.signature()).as("OR-40: nothing verifies a signature").isEqualTo("unsigned");
        assertThat(loaded.digest()).matches("sha256:[0-9a-f]{64}");
        assertThat(count("catalog_solution", org)).isEqualTo(1);
        assertThat(count("solution_package", org)).isEqualTo(1);
        Map<String, Object> row = migrator.queryForMap(
                "SELECT catalog_solution_id, package_version, applies_to, signature_status, loaded_by, digest,"
                        + " document::text AS document FROM sm_control.solution_package WHERE organization_id = :o",
                Map.of("o", org.id));
        assertThat(row.get("catalog_solution_id")).isEqualTo(loaded.solutionId());
        assertThat(row.get("package_version")).isEqualTo("[1]");
        assertThat(row.get("applies_to")).isEqualTo(CatalogFixtures.cache().get("appliesTo").asText());
        assertThat(row.get("signature_status")).isEqualTo("unsigned");
        assertThat(row.get("loaded_by")).isEqualTo(org.actorId);
        assertThat(row.get("digest")).isEqualTo(loaded.digest());
        assertThat(row.get("document").toString()).contains("\"solution\"");
    }

    @Test
    void aFirstLoadWithNoEntitlementCreatesOneWithTheDefaultLimitAndAuditsItWithTheLoaderAsActor() {
        Org org = org();     // OR-148: no entitlement row at all
        UUID correlation = UUID.randomUUID();

        PackageLoad load = catalog.load(org.actor(), new RequestOrigin(CLIENT, correlation), org.id,
                CatalogFixtures.json(CatalogFixtures.cache()));

        assertThat(load.newlyLoaded()).isTrue();
        Map<String, Object> entitlement = migrator.queryForMap("SELECT max_instances, set_by FROM"
                + " sm_control.entitlement WHERE organization_id = :o AND solution = 'cache'", Map.of("o", org.id));
        assertThat(entitlement.get("max_instances")).isEqualTo(10);
        assertThat(entitlement.get("set_by")).isEqualTo(org.actorId);
        List<Map<String, Object>> audit = migrator.queryForList("SELECT table_name, audit_operation, actor_principal_id"
                + " FROM sm_control.change_audit WHERE correlation_id = :c", Map.of("c", correlation));
        assertThat(audit).filteredOn(r -> "entitlement".equals(r.get("table_name"))).singleElement().satisfies(r -> {
            assertThat(r.get("audit_operation")).isEqualTo("ENTITLEMENT_SET");
            assertThat(r.get("actor_principal_id")).isEqualTo(org.actorId);
        });
        assertThat(audit).filteredOn(r -> !"entitlement".equals(r.get("table_name"))).isNotEmpty()
                .allSatisfy(r -> assertThat(r.get("audit_operation")).isEqualTo("PACKAGE_LOADED"));
    }

    @Test
    void aLoadNeverChangesAnExistingEntitlement() {
        Org org = org();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.entitlement (organization_id, solution,"
                + " max_instances, set_by, set_at) VALUES (?, 'cache', 3, ?, now())", org.id, org.actorId);

        UUID correlation = UUID.randomUUID();
        catalog.load(org.actor(), new RequestOrigin(CLIENT, correlation), org.id,
                CatalogFixtures.json(CatalogFixtures.cache()));

        assertThat(migrator.queryForObject("SELECT max_instances FROM sm_control.entitlement WHERE organization_id = :o"
                + " AND solution = 'cache'", Map.of("o", org.id), Integer.class)).isEqualTo(3);
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.change_audit WHERE correlation_id = :c"
                + " AND audit_operation = 'ENTITLEMENT_SET'", Map.of("c", correlation), Long.class)).isZero();
    }

    @Test
    void withFirstLoadEntitlementDisabledAnUnentitledSolutionIsRefusedAndNothingIsWritten() {
        Org org = org();     // no entitlement row at all

        assertThatThrownBy(() -> strictCatalog.load(org.actor(), origin(), org.id,
                CatalogFixtures.json(CatalogFixtures.cache()))).isInstanceOf(EntitlementRefusedException.class);

        assertNothingWritten(org);
    }

    @Test
    void withFirstLoadEntitlementDisabledAnEntitlementForAnotherSolutionOrAnotherOrganizationDoesNotEntitle() {
        Org org = org();
        Org other = org();
        entitle(org, "engine-edge");
        entitle(other, "cache");

        assertThatThrownBy(() -> strictCatalog.load(org.actor(), origin(), org.id,
                CatalogFixtures.json(CatalogFixtures.cache()))).isInstanceOf(EntitlementRefusedException.class);

        assertNothingWritten(org);
    }

    @Test
    void aPackageFailingAGateIsRefusedNamingTheGateAndWritesNothing() {
        Org org = org();
        entitle(org, "cache");
        ObjectNode broken = CatalogFixtures.cache();
        broken.remove("components");        // gate 1: the schema requires components

        assertThatThrownBy(() -> catalog.load(org.actor(), origin(), org.id, CatalogFixtures.json(broken)))
                .isInstanceOfSatisfying(PackageRejectedException.class,
                        e -> assertThat(e.gates()).startsWith("1"));

        assertNothingWritten(org);
    }

    @Test
    void anInvalidPackageIsRefusedForItsGateEvenWhenTheSolutionIsNotEntitled() {
        Org org = org();      // validation precedes the entitlement check: a broken document is 422, not 409
        ObjectNode broken = CatalogFixtures.cache();
        broken.remove("components");

        assertThatThrownBy(() -> catalog.load(org.actor(), origin(), org.id, CatalogFixtures.json(broken)))
                .isInstanceOf(PackageRejectedException.class);
        assertNothingWritten(org);
    }

    @Test
    void notJsonAtAllIsAGateOneRefusalThatQuotesNothing() {
        Org org = org();
        entitle(org, "cache");

        assertThatThrownBy(() -> catalog.load(org.actor(), origin(), org.id, "{\"secret\": \"hunter2\""))
                .isInstanceOfSatisfying(PackageRejectedException.class, e -> {
                    assertThat(e.gates()).containsExactly("1");
                    assertThat(e.getMessage()).doesNotContain("hunter2");
                });
        assertNothingWritten(org);
    }

    /**
     * S3 review: U+0000 passes both gate-1 implementations, then fails the jsonb cast with an error whose CONTEXT
     * line carries part of the document - a 500 that logs a value. It is a gate-1 refusal that writes and quotes nothing.
     */
    @Test
    void aNulCharacterIsAGateOneRefusalThatWritesAndQuotesNothing() {
        Org org = org();
        entitle(org, "cache");
        ObjectNode withNul = CatalogFixtures.cache();
        ((ObjectNode) withNul.get("authoring")).put("source", "hunter2-SECRET\u0000");

        assertThatThrownBy(() -> catalog.load(org.actor(), origin(), org.id, CatalogFixtures.json(withNul)))
                .isInstanceOfSatisfying(PackageRejectedException.class, e -> {
                    assertThat(e.gates()).containsExactly("1");
                    assertThat(String.valueOf(e.getMessage())).doesNotContain("hunter2");
                });
        assertNothingWritten(org);
    }

    @Test
    void aDevModePackageIsRefusedByGateTwoInProduction() {
        Org org = org();
        entitle(org, "engine-edge");

        assertThatThrownBy(() -> catalog.load(org.actor(), origin(), org.id,
                CatalogFixtures.json(CatalogFixtures.fixture("engine-edge.package.json"))))
                .isInstanceOfSatisfying(PackageRejectedException.class,
                        e -> assertThat(e.gates()).containsExactly("2"));
        assertNothingWritten(org);
    }

    @Test
    void aSecondPackageOfTheSameSolutionClaimingAnOverlappingRangeIsRefusedByGateThree() {
        Org org = org();
        entitle(org, "cache");
        catalog.load(org.actor(), origin(), org.id, CatalogFixtures.json(CatalogFixtures.cache()));   // >=2.2 <2.5

        assertThatThrownBy(() -> catalog.load(org.actor(), origin(), org.id,
                CatalogFixtures.json(CatalogFixtures.cacheVersion(2, ">=2.4 <3.0"))))
                .isInstanceOfSatisfying(PackageRejectedException.class,
                        e -> assertThat(e.gates()).containsExactly("3"));

        assertThat(count("solution_package", org)).as("the refused load wrote no package").isEqualTo(1);
        assertThat(count("catalog_solution", org)).isEqualTo(1);
    }

    @Test
    void aTouchingRangeIsNotAnOverlapAndLoadsUnderTheSameEntry() {
        Org org = org();
        entitle(org, "cache");
        LoadedPackage first = catalog.load(org.actor(), origin(), org.id,
                CatalogFixtures.json(CatalogFixtures.cache())).loaded();

        LoadedPackage second = catalog.load(org.actor(), origin(), org.id,
                CatalogFixtures.json(CatalogFixtures.cacheVersion(2, ">=2.5 <3.0"))).loaded();

        assertThat(second.solutionId()).as("the same catalog entry").isEqualTo(first.solutionId());
        assertThat(count("catalog_solution", org)).isEqualTo(1);
        assertThat(count("solution_package", org)).isEqualTo(2);
        assertThat(second.stateVersion()).isGreaterThan(first.stateVersion());
    }

    // ------------------------------------------------------------------------------------- idempotence

    @Test
    void anIdenticalReloadIsIdempotentAndWritesAndAuditsNothing() {
        Org org = org();
        entitle(org, "cache");
        String document = CatalogFixtures.json(CatalogFixtures.cache());
        PackageLoad first = catalog.load(org.actor(), origin(), org.id, document);
        long auditBefore = auditRows(org);

        PackageLoad again = catalog.load(org.actor(), origin(), org.id, document);

        assertThat(again.newlyLoaded()).isFalse();
        assertThat(again.loaded()).isEqualTo(first.loaded());
        assertThat(count("solution_package", org)).isEqualTo(1);
        assertThat(auditRows(org)).as("a re-load changes no state, so it leaves no audit row").isEqualTo(auditBefore);
    }

    @Test
    void theSameDocumentSpelledDifferentlyIsStillTheIdenticalDocument() {
        Org org = org();
        entitle(org, "cache");
        String compact = CatalogFixtures.json(CatalogFixtures.cache());
        String indented = CatalogFixtures.cache().toPrettyString();
        PackageLoad first = catalog.load(org.actor(), origin(), org.id, compact);

        PackageLoad again = catalog.load(org.actor(), origin(), org.id, indented);

        assertThat(again.newlyLoaded()).as("whitespace is not content").isFalse();
        assertThat(again.loaded().digest()).isEqualTo(first.loaded().digest());
    }

    @Test
    void aDifferentDocumentUnderAnExistingVersionIsRefused() {
        Org org = org();
        entitle(org, "cache");
        catalog.load(org.actor(), origin(), org.id, CatalogFixtures.json(CatalogFixtures.cache()));
        ObjectNode changed = CatalogFixtures.cache();
        changed.put("appliesTo", ">=2.2 <2.6");       // same packageVersion [1], different content

        assertThatThrownBy(() -> catalog.load(org.actor(), origin(), org.id, CatalogFixtures.json(changed)))
                .isInstanceOfSatisfying(PackageRejectedException.class,
                        e -> assertThat(e.gates()).containsExactly(PackageRejectedException.VERSION_GATE));

        assertThat(count("solution_package", org)).isEqualTo(1);
    }

    @Test
    void twoConcurrentLoadsOfTheSameDocumentStoreExactlyOne() throws Exception {
        Org org = org();
        entitle(org, "cache");
        String document = CatalogFixtures.json(CatalogFixtures.cache());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<PackageLoad> load = () -> {
                start.await();
                return catalog.load(org.actor(), origin(), org.id, document);
            };
            Future<PackageLoad> a = pool.submit(load);
            Future<PackageLoad> b = pool.submit(load);
            start.countDown();
            List<PackageLoad> results = List.of(a.get(), b.get());

            assertThat(results.stream().filter(PackageLoad::newlyLoaded)).hasSize(1);
            assertThat(count("solution_package", org)).isEqualTo(1);
            assertThat(count("catalog_solution", org)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------------- audit

    @Test
    void theLoadIsAuditedAsPackageLoadedWithTheActorAndNeverCopiesTheDocument() {
        Org org = org();
        entitle(org, "cache");
        UUID correlation = UUID.randomUUID();

        catalog.load(org.actor(), new RequestOrigin(CLIENT, correlation), org.id,
                CatalogFixtures.json(CatalogFixtures.cache()));

        List<Map<String, Object>> rows = migrator.queryForList(
                "SELECT table_name, operation, audit_operation, actor_principal_id, client_address, db_role,"
                        + " new_values::text AS new_values FROM sm_control.change_audit"
                        + " WHERE correlation_id = :c ORDER BY change_audit_id", Map.of("c", correlation));
        assertThat(rows).extracting(r -> r.get("table_name")).containsExactly("catalog_solution", "solution_package");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("operation")).isEqualTo("INSERT");
            assertThat(r.get("audit_operation")).isEqualTo("PACKAGE_LOADED");
            assertThat(r.get("actor_principal_id")).isEqualTo(org.actorId);
            assertThat(r.get("client_address")).isEqualTo(CLIENT);
            assertThat(r.get("db_role")).isEqualTo("sm_cp_runtime");
        });
        assertThat(rows.get(1).get("new_values").toString()).doesNotContain("\"document\"").contains("digest");
    }

    // ------------------------------------------------------------------------------------------ reads

    @Test
    void listReturnsTheOrganizationsEntriesWithAStateVersionThatRisesOnEachLoad() {
        Org org = org();
        entitle(org, "cache");
        entitle(org, "engine-edge");
        assertThat(catalog.list(org.id)).isEmpty();

        catalog.load(org.actor(), origin(), org.id, CatalogFixtures.json(CatalogFixtures.cache()));
        List<CatalogEntry> one = catalog.list(org.id);
        catalog.load(org.actor(), origin(), org.id,
                CatalogFixtures.json(CatalogFixtures.cacheVersion(2, ">=2.5 <3.0")));
        List<CatalogEntry> two = catalog.list(org.id);

        assertThat(one).hasSize(1);
        assertThat(two).hasSize(1);
        CatalogEntry entry = two.get(0);
        assertThat(entry.solution()).isEqualTo("cache");
        assertThat(entry.name()).isEqualTo("cache");
        assertThat(entry.signature()).isEqualTo("unsigned");
        assertThat(entry.packageVersions()).containsExactly(PackageVersionId.of(1), PackageVersionId.of(2));
        assertThat(entry.latestPackageVersion()).isEqualTo(PackageVersionId.of(2));
        assertThat(entry.stateVersion()).isGreaterThan(one.get(0).stateVersion());
        assertThat(entry.solutionId()).isEqualTo(one.get(0).solutionId());
    }

    @Test
    void listIsOrderedBySolutionAndNeverShowsAnotherOrganizationsEntries() {
        Org mine = org();
        Org theirs = org();
        entitle(mine, "cache");
        entitle(mine, "zeta-cache");
        entitle(theirs, "cache");
        entitle(theirs, "secret-solution");
        catalog.load(mine.actor(), origin(), mine.id, CatalogFixtures.json(CatalogFixtures.cache()));
        catalog.load(mine.actor(), origin(), mine.id,
                CatalogFixtures.json(CatalogFixtures.asSolution(CatalogFixtures.cache(), "zeta-cache")));
        UUID theirsId = catalog.load(theirs.actor(), origin(), theirs.id,
                CatalogFixtures.json(CatalogFixtures.cache())).loaded().solutionId();
        catalog.load(theirs.actor(), origin(), theirs.id,
                CatalogFixtures.json(CatalogFixtures.asSolution(CatalogFixtures.cache(), "secret-solution")));

        List<CatalogEntry> listed = catalog.list(mine.id);

        assertThat(listed).extracting(CatalogEntry::solution).containsExactly("cache", "zeta-cache");
        assertThat(listed).extracting(CatalogEntry::solutionId).doesNotContain(theirsId);
        assertThat(catalog.list(theirs.id)).extracting(CatalogEntry::solution)
                .containsExactly("cache", "secret-solution");
    }

    @Test
    void findAndDocumentOfServeAMemberOfThePackageVersionArrayAndAreTenantBound() {
        Org org = org();
        Org other = org();
        entitle(org, "cache");
        LoadedPackage loaded = catalog.load(org.actor(), origin(), org.id,
                CatalogFixtures.json(CatalogFixtures.cache())).loaded();
        catalog.load(org.actor(), origin(), org.id,
                CatalogFixtures.json(CatalogFixtures.cacheVersion(2, ">=2.5 <3.0")));

        assertThat(catalog.find(org.id, loaded.solutionId(), "1")).hasValueSatisfying(
                p -> assertThat(p.packageVersion()).containsExactly(PackageVersionId.of(1)));
        assertThat(catalog.find(org.id, loaded.solutionId(), "2")).isPresent();
        assertThat(catalog.find(org.id, loaded.solutionId(), "3")).isEmpty();
        assertThat(catalog.find(org.id, loaded.solutionId(), "\"1\""))
                .as("the string \"1\" is not the integer 1").isEmpty();
        assertThat(catalog.documentOf(org.id, loaded.solutionId(), "1")).hasValueSatisfying(
                text -> assertThat(CatalogFixtures.parse(text).get("solution").asText()).isEqualTo("cache"));
        // jsonb re-spells the text (key order, spacing), so the digest is NOT sha256 of what documentOf returns; it
        // is the digest of the canonical form of it (S3 review) - the integrity check a projection may run
        assertThat(catalog.documentOf(org.id, loaded.solutionId(), "1")).hasValueSatisfying(text ->
                assertThat(PackageCanonicalizer.digest(PackageCanonicalizer.canonical(CatalogFixtures.parse(text))))
                        .isEqualTo(loaded.digest()));
        assertThat(catalog.find(other.id, loaded.solutionId(), "1")).as("another organization's id").isEmpty();
        assertThat(catalog.documentOf(other.id, loaded.solutionId(), "1")).isEmpty();
        assertThat(catalog.find(org.id, UUID.randomUUID(), "1")).isEmpty();
    }

    // ----------------------------------------------------------- the resource directory (OR-14)

    @Test
    void aCatalogEntryResolvesToItsOrganizationAndNothingFiner() {
        Org org = org();
        Org other = org();
        entitle(org, "cache");
        UUID entryId = catalog.load(org.actor(), origin(), org.id,
                CatalogFixtures.json(CatalogFixtures.cache())).loaded().solutionId();
        JdbcResourceScopeDirectory directory = new JdbcResourceScopeDirectory(runtime);

        assertThat(directory.findScopePath(new ResourceRef(ResourceKind.SOLUTION_PACKAGE, entryId,
                new TenantId(org.id)))).contains(new ScopePath(org.id, null, null, null, null));
        assertThat(directory.findScopePaths(ResourceKind.SOLUTION_PACKAGE, org.id, List.of(entryId)))
                .containsOnlyKeys(entryId);
        assertThat(directory.findScopePaths(ResourceKind.SOLUTION_PACKAGE, other.id, List.of(entryId)))
                .as("another organization's bound query never returns it").isEmpty();
        assertThatThrownBy(() -> new ScopePathResolver(directory).scopePathOf(
                new ResourceRef(ResourceKind.SOLUTION_PACKAGE, UUID.randomUUID(), new TenantId(org.id))))
                .isInstanceOf(ResourceNotFoundException.class);
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
                + " VALUES (?, ?, 'idp', ?)", principal, organization, "catalog-" + principal);
        return new Org(organization, principal);
    }

    private void entitle(Org org, String solution) {
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.entitlement (organization_id, solution,"
                + " max_instances, set_by, set_at) VALUES (?, ?, 5, ?, now())", org.id, solution, org.actorId);
    }

    private static RequestOrigin origin() {
        return new RequestOrigin(CLIENT, UUID.randomUUID());
    }

    private long count(String table, Org org) {
        return migrator.queryForObject("SELECT count(*) FROM sm_control." + table + " WHERE organization_id = :o",
                Map.of("o", org.id), Long.class);
    }

    private long auditRows(Org org) {
        return migrator.queryForObject("SELECT count(*) FROM sm_control.change_audit WHERE table_name IN"
                + " ('catalog_solution', 'solution_package') AND (new_values ->> 'organization_id') = :o",
                Map.of("o", org.id.toString()), Long.class);
    }

    private void assertNothingWritten(Org org) {
        assertThat(count("catalog_solution", org)).as("no catalog entry").isZero();
        assertThat(count("solution_package", org)).as("no package").isZero();
        assertThat(auditRows(org)).as("no audit row").isZero();
    }
}
